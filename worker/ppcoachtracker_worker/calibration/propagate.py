"""Camera motion between consecutive frames, to carry a homography along its shot.

One forward pass over the clip: KLT on the grass (inside the pitch color,
outside the players' boxes) in a 640 px copy, and findHomography with RANSAC.
Each step stores the motion and whether it can be trusted; the chain is cut
at a camera cut or where fewer than 30 % (or fewer than 30) points match.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

WORK_WIDTH = 640
MIN_MATCH_RATIO = 0.30
MIN_MATCHES = 30
CUT_THRESHOLD = 0.44  # hue/saturation histogram jump between frames (a pan reaches ~0.27)
# A step that moves the image corners less than this is a camera that did not
# move: identity, so KLT jitter does not add up over a tripod shot.
STILL_PX = 0.5


@dataclass
class Step:
    """Motion from frame i-1 to frame i in full-resolution pixels (None = the chain is cut here)."""

    motion: np.ndarray | None
    matches: int
    ratio: float
    cut: bool


def grass_mask(bgr: np.ndarray) -> np.ndarray:
    import cv2

    hsv = cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV)
    return cv2.inRange(hsv, (30, 40, 40), (90, 255, 255))


def histogram(bgr: np.ndarray) -> np.ndarray:
    import cv2

    hsv = cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV)
    h = cv2.calcHist([hsv], [0, 1], None, [30, 32], [0, 180, 0, 256])
    return cv2.normalize(h, h).flatten()


def is_cut(prev_hist: np.ndarray, hist: np.ndarray) -> bool:
    import cv2

    return float(cv2.compareHist(prev_hist, hist, cv2.HISTCMP_BHATTACHARYYA)) >= CUT_THRESHOLD


def motion_steps(frames, boxes_of) -> list[Step]:
    """frames: iterable of (global frame, BGR image) in order; boxes_of(frame) → normalized (x, y, w, h) boxes.

    Returns one Step per frame (the first one has no motion).
    """
    import cv2

    steps: list[Step] = []
    prev_gray = prev_hist = None
    prev_pts = None
    scale = 1.0
    for g, img in frames:
        h, w = img.shape[:2]
        scale = WORK_WIDTH / w if w > WORK_WIDTH else 1.0
        small = cv2.resize(img, (int(w * scale), int(h * scale))) if scale != 1.0 else img
        gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
        hist = histogram(small)
        mask = grass_mask(small)
        sh, sw = mask.shape
        for bx, by, bw, bh in boxes_of(g):
            pad = 0.15
            x1, y1 = int((bx - pad * bw) * sw), int((by - pad * bh) * sh)
            x2, y2 = int((bx + bw * (1 + pad)) * sw), int((by + bh * (1 + pad)) * sh)
            mask[max(0, y1):max(0, y2), max(0, x1):max(0, x2)] = 0
        if prev_gray is None:
            steps.append(Step(None, 0, 0.0, cut=False))
        elif is_cut(prev_hist, hist):
            steps.append(Step(None, 0, 0.0, cut=True))
        else:
            motion, matches, ratio = None, 0, 0.0
            if prev_pts is not None and len(prev_pts) >= MIN_MATCHES:
                nxt, status, _ = cv2.calcOpticalFlowPyrLK(prev_gray, gray, prev_pts, None, winSize=(21, 21), maxLevel=3)
                ok = status.ravel() == 1
                a, b = prev_pts[ok].reshape(-1, 2), nxt[ok].reshape(-1, 2)
                if len(a) >= MIN_MATCHES:
                    M, inl = cv2.findHomography(a, b, cv2.RANSAC, 2.0)
                    if M is not None:
                        matches = int(inl.sum())
                        ratio = matches / len(prev_pts)
                        if matches >= MIN_MATCHES and ratio >= MIN_MATCH_RATIO:
                            S = np.diag([scale, scale, 1.0])
                            motion = np.linalg.inv(S) @ M @ S
                            corners = np.array([[0, 0, 1], [w, 0, 1], [0, h, 1], [w, h, 1]], dtype=float)
                            moved = corners @ motion.T
                            shift = np.linalg.norm(moved[:, :2] / moved[:, 2:3] - corners[:, :2], axis=1).max()
                            if shift < STILL_PX:
                                motion = np.eye(3)
            steps.append(Step(motion, matches, ratio, cut=False))
        prev_gray, prev_hist = gray, hist
        prev_pts = cv2.goodFeaturesToTrack(gray, maxCorners=400, qualityLevel=0.01, minDistance=7, mask=mask)
    return steps


def chain(steps: list[Step], keys: dict[int, np.ndarray], accept) -> dict[int, tuple[np.ndarray, int]]:
    """Carries each key frame's homography forward and backward through trusted steps.

    keys: frame index (position in [steps]) → homography. accept(i, H) → False cuts
    the chain there (the feet check). Returns index → (H, key it came from); a
    frame reachable from two keys takes the nearest one.
    """
    out: dict[int, tuple[np.ndarray, int]] = {}
    n = len(steps)
    for k, Hk in sorted(keys.items()):
        cand = {k: Hk}
        H = Hk
        for i in range(k + 1, n):
            m = steps[i].motion
            if m is None:
                break
            H = m @ H
            if not accept(i, H):
                break
            cand[i] = H
        H = Hk
        for i in range(k, 0, -1):
            m = steps[i].motion
            if m is None:
                break
            H = np.linalg.inv(m) @ H
            if not accept(i - 1, H):
                break
            cand[i - 1] = H
        for i, Hi in cand.items():
            if i not in out or abs(i - k) < abs(i - out[i][1]):
                out[i] = (Hi / Hi[2, 2], k)
    return out
