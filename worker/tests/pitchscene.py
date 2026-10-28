"""A synthetic 7-a-side pitch seen by a fixed camera, with players at known speeds.

The ground truth for the pitch-in-meters chain: the background (textured
grass and the white lines) is drawn with a known camera, each player runs a
straight line at a constant speed, and the fake SAM answers with their exact
boxes. Own synthetic video: no third-party footage.
"""

from __future__ import annotations

import hashlib
import math
from pathlib import Path

import numpy as np

from ppcoachtracker_worker.calibration.solver import Camera, project
from ppcoachtracker_worker.calibration.template import build, circle_polyline
from ppcoachtracker_worker.chunks import chunk_yaml, index_yaml

W, H = 640, 360
TPL = build(7)
CAM = Camera(cx=0.0, cy=-42.0, cz=28.0, pan=0.0, tilt=math.atan2(28.0, 42.0) + 0.02, roll=0.0, focal=320.0, width=W, height=H)
BGR = {"red": (53, 57, 229), "blue": (216, 91, 30), "white": (245, 245, 245)}

# (team color, start (x, y) in meters, speed m/s along x)
PLAYERS = [
    ("red", (-24.0, 12.0), 1.0), ("red", (-22.0, 4.0), 2.0), ("red", (-24.0, -4.0), 3.0), ("red", (-24.0, -12.0), 4.0),
    ("blue", (20.0, 10.0), -1.5), ("blue", (22.0, 2.0), -2.5), ("blue", (24.0, -6.0), -3.5), ("blue", (18.0, -14.0), -2.0),
]


def project3d(cam: Camera, X) -> np.ndarray:
    K = np.array([[cam.focal, 0, cam.width / 2], [0, cam.focal, cam.height / 2], [0, 0, 1]])
    q = K @ (cam.rotation() @ (np.asarray(X, dtype=float) - [cam.cx, cam.cy, cam.cz]))
    return q[:2] / q[2]


def position(i: int, t_s: float) -> tuple[float, float]:
    _, (x0, y0), v = PLAYERS[i]
    return (x0 + v * t_s, y0)


def box(cam: Camera, x: float, y: float) -> tuple[float, float, float, float]:
    """Normalized (x, y, w, h) of a 1.8 m player standing at (x, y)."""
    foot = project3d(cam, (x, y, 0.0))
    head = project3d(cam, (x, y, 1.8))
    h = foot[1] - head[1]
    w = 0.4 * h
    return ((foot[0] - w / 2) / cam.width, head[1] / cam.height, w / cam.width, h / cam.height)


def background(cam: Camera = CAM, seed: int = 7) -> np.ndarray:
    import cv2

    rng = np.random.default_rng(seed)
    img = np.zeros((H, W, 3), dtype=np.uint8)
    img[:] = (40, 135, 55)
    # Grass texture: blotches give KLT something to follow.
    noise = cv2.GaussianBlur(rng.integers(0, 60, (H, W)).astype(np.float32), (0, 0), 1.2)
    img[..., 1] = np.clip(img[..., 1] + noise - 30, 0, 255).astype(np.uint8)
    img[..., 0] = np.clip(img[..., 0] + noise * 0.3 - 9, 0, 255).astype(np.uint8)
    Hm = cam.homography()
    for a, b in TPL.lines.values():
        p, q = project(Hm, [a, b]).round().astype(int)
        cv2.line(img, tuple(p), tuple(q), (235, 235, 235), 2, cv2.LINE_AA)
    pts = project(Hm, circle_polyline(TPL)).round().astype(np.int32)
    cv2.polylines(img, [pts], False, (235, 235, 235), 2, cv2.LINE_AA)
    return img


def render(t_s: float, bg: np.ndarray, cam: Camera = CAM) -> np.ndarray:
    img = bg.copy()
    for i, (color, _, _) in enumerate(PLAYERS):
        x, y, w, h = box(cam, *position(i, t_s))
        x1, y1, x2, y2 = int(x * W), int(y * H), int((x + w) * W), int((y + h) * H)
        img[max(0, y1):y2, max(0, x1):x2] = BGR[color]
    return img


def make_clip(directory: Path, seconds: float = 12.0) -> Path:
    import cv2

    directory.mkdir(parents=True, exist_ok=True)
    bg = background()
    n = int(seconds * 10)
    name = "video_part_001.mp4"
    wr = cv2.VideoWriter(str(directory / name), cv2.VideoWriter_fourcc(*"mp4v"), 10, (W, H))
    fmap = []
    for k in range(n):
        wr.write(render(k / 10, bg))
        fmap.append((k, k * 3, k * 100))
    wr.release()
    sha = hashlib.sha256((directory / name).read_bytes()).hexdigest()
    (directory / "video_part_001.yaml").write_text(chunk_yaml(
        clip={"id": 9, "match_id": 1, "source_file": "synthetic_pitch.mp4", "source_fps": 30.0, "source_frames": n * 3, "duration_ms": n * 100},
        chunk={"number": 1, "count": 1, "file": name, "seconds": 60, "start_ms": 0, "end_ms": n * 100, "overlap_ms": 0},
        encoding={"fps": 10, "width": W, "height": H, "codec": "mp4v", "bitrate": 0, "audio": 0},
        fmap=fmap, overlap_frames=0, sha256=sha,
    ), encoding="utf-8")
    (directory / "chunks.yaml").write_text(index_yaml(9, "test", 60, 2000, [{"number": 1, "file": name, "start_ms": 0, "end_ms": n * 100, "frames": n}]), encoding="utf-8")
    return directory


def sam_text(color: str, frames: int) -> str:
    parts = []
    for k in range(frames):
        parts.append(f"<{k}f>")
        for j, (i, (c, _, _)) in enumerate([(i, p) for i, p in enumerate(PLAYERS) if p[0] == color]):
            x, y, w, h = box(CAM, *position(i, k / 10))
            parts.append(f" {j + 1} <|box;x1={x:.5f};y1={y:.5f};x2={x + w:.5f};y2={y + h:.5f};c=0.95|>")
    return "".join(parts)


def touches(names=("corner_far_left", "corner_far_right", "corner_near_left", "corner_near_right", "center"), frame: int = 0) -> dict:
    Hm = CAM.homography()
    pts = []
    for n in names:
        x, y = project(Hm, [TPL.points[n]])[0]
        pts.append({"name": n, "x": round(float(x / W), 5), "y": round(float(y / H), 5)})
    return {"pitch": {"size": 7}, "frames": [{"frame": frame, "points": pts}]}


def stroke(name: str, start: float = 0.0, end: float = 1.0, n: int = 12, noise_px: float = 0.0, seed: int = 0) -> dict:
    """A line the coach painted with a finger: points along the named template
    line from [start] to [end] of its length (normalized image), with noise."""
    rng = np.random.default_rng(seed)
    a, b = np.array(TPL.lines[name], dtype=float)
    pts = project(CAM.homography(), [a + (b - a) * s for s in np.linspace(start, end, n)])
    pts = pts + rng.normal(0, noise_px, pts.shape)
    return {"name": name, "points": [{"x": round(float(x / W), 5), "y": round(float(y / H), 5)} for x, y in pts]}
