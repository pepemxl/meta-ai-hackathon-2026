"""Pitch → image homographies from points AND lines, RANSAC, and a camera model.

- Homography H maps pitch meters (x, y, 1) to image pixels.
- DLT normalized with both kinds of correspondences (a point gives rows for
  H, a line for Hᵀ), then refined on the distance in pixels.
- RANSAC over points when a mark may be mislabeled.
- Camera: fixed place (cx, cy, cz), pan, tilt, roll and focal length = 7
  numbers. A phone on a tripod or a broadcast camera does not move: once its
  place is known, a frame needs only pan, tilt and zoom. Two lines alone do
  not fix it (tilt and zoom compensate): two lines and a point do.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, replace
from typing import Callable

import numpy as np

# ── small linear algebra helpers ─────────────────────────────────────────────


def _h(p) -> np.ndarray:
    p = np.asarray(p, dtype=float)
    return np.hstack([p, np.ones((len(p), 1))])


def project(H: np.ndarray, pts) -> np.ndarray:
    """Pitch points (N×2) → image points (N×2) with H."""
    q = _h(pts) @ H.T
    return q[:, :2] / q[:, 2:3]


def unproject(H: np.ndarray, pts) -> np.ndarray:
    """Image points (N×2) → pitch points (N×2)."""
    return project(np.linalg.inv(H), pts)


def line_through(p1, p2) -> np.ndarray:
    """Homogeneous line through two 2-D points, normalized so (a, b) is unit."""
    l = np.cross([p1[0], p1[1], 1.0], [p2[0], p2[1], 1.0])
    n = math.hypot(l[0], l[1])
    return l / n if n > 0 else l


def _normalizer(pts: np.ndarray) -> np.ndarray:
    c = pts.mean(axis=0)
    d = np.sqrt(((pts - c) ** 2).sum(axis=1)).mean()
    s = math.sqrt(2) / d if d > 0 else 1.0
    return np.array([[s, 0, -s * c[0]], [0, s, -s * c[1]], [0, 0, 1]])


@dataclass
class Correspondences:
    """Pitch → image: points (pitch xy, image xy) and lines (pitch segment, two image points on it)."""

    pitch_points: list[tuple[float, float]]
    image_points: list[tuple[float, float]]
    pitch_lines: list[tuple[tuple[float, float], tuple[float, float]]]
    image_lines: list[tuple[tuple[float, float], tuple[float, float]]]

    @classmethod
    def of_points(cls, pitch, image) -> "Correspondences":
        return cls([tuple(p) for p in pitch], [tuple(p) for p in image], [], [])


def _dlt_system(c: Correspondences) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """The normalized DLT rows and both normalizers."""
    allp = np.array(c.pitch_points + [p for seg in c.pitch_lines for p in seg], dtype=float)
    alli = np.array(c.image_points + [p for seg in c.image_lines for p in seg], dtype=float)
    Tp, Ti = _normalizer(allp), _normalizer(alli)
    rows = []
    for P, I in zip(c.pitch_points, c.image_points):
        X = Tp @ [P[0], P[1], 1.0]
        x = Ti @ [I[0], I[1], 1.0]
        # x × (H X) = 0
        rows.append(np.r_[np.zeros(3), -x[2] * X, x[1] * X])
        rows.append(np.r_[x[2] * X, np.zeros(3), -x[0] * X])
    TpInvT = np.linalg.inv(Tp).T
    TiInvT = np.linalg.inv(Ti).T
    for (pa, pb), (ia, ib) in zip(c.pitch_lines, c.image_lines):
        lp = TpInvT @ line_through(pa, pb)
        li = TiInvT @ line_through(ia, ib)
        # lp ∝ Hᵀ li → lp × (Hᵀ li) = 0; (Hᵀ li)_j = Σ_i H_ij li_i
        M = np.zeros((3, 9))
        for j in range(3):
            for i in range(3):
                M[j, 3 * i + j] = li[i]
        cross = np.array([[0, -lp[2], lp[1]], [lp[2], 0, -lp[0]], [-lp[1], lp[0], 0]])
        A = cross @ M
        rows.extend([A[0], A[1]])
    return np.array(rows), Tp, Ti


def dlt(c: Correspondences) -> np.ndarray:
    """Normalized DLT with points and lines. Needs 4 elements (points + lines)."""
    if len(c.pitch_points) + len(c.pitch_lines) < 4:
        raise ValueError("need at least 4 points or lines")
    rows, Tp, Ti = _dlt_system(c)
    _, _, vt = np.linalg.svd(rows)
    Hn = vt[-1].reshape(3, 3)
    H = np.linalg.inv(Ti) @ Hn @ Tp
    return H / H[2, 2]


def well_posed(c: Correspondences) -> bool:
    """False when the correspondences leave the homography free (five parallel
    lines, or lines of one direction and a single point): then a tiny error
    proves nothing."""
    if len(c.pitch_points) + len(c.pitch_lines) < 4:
        return False
    rows = _dlt_system(c)[0]
    sv = np.linalg.svd(rows / np.linalg.norm(rows, axis=1, keepdims=True), compute_uv=False)
    return len(sv) >= 9 and sv[-2] > 1e-3 * sv[0]


def fit_line(pts) -> tuple[tuple[float, float], tuple[float, float], float]:
    """A straight line through a painted stroke (total least squares): its two
    ends (the extreme points projected on it) and the largest distance of a
    stroke point to it, in the stroke's units."""
    P = np.asarray(pts, dtype=float)
    c = P.mean(axis=0)
    _, _, vt = np.linalg.svd(P - c)
    d = vt[0]
    t = (P - c) @ d
    dev = float(np.max(np.abs((P - c) @ vt[1]))) if len(P) > 2 else 0.0
    a, b = c + t.min() * d, c + t.max() * d
    return (float(a[0]), float(a[1])), (float(b[0]), float(b[1])), dev


def residuals(H: np.ndarray, c: Correspondences) -> np.ndarray:
    """Pixel residuals: point distances (x and y) and, for lines, the distance of each observed image point to the projected line."""
    out = []
    if c.pitch_points:
        out.append((project(H, c.pitch_points) - np.array(c.image_points)).ravel())
    if c.pitch_lines:
        Hinv_t = np.linalg.inv(H).T
        d = []
        for (pa, pb), (ia, ib) in zip(c.pitch_lines, c.image_lines):
            l = Hinv_t @ line_through(pa, pb)
            l = l / math.hypot(l[0], l[1])
            d += [l @ [ia[0], ia[1], 1.0], l @ [ib[0], ib[1], 1.0]]
        out.append(np.array(d))
    return np.concatenate(out) if out else np.zeros(0)


def least_squares(fun: Callable[[np.ndarray], np.ndarray], x0: np.ndarray, iters: int = 60, scale: np.ndarray | None = None) -> np.ndarray:
    """Levenberg–Marquardt with a numeric Jacobian (small problems only)."""
    x = np.asarray(x0, dtype=float).copy()
    step = scale if scale is not None else np.maximum(np.abs(x) * 1e-6, 1e-6)
    lam = 1e-3
    r = fun(x)
    cost = r @ r
    for _ in range(iters):
        J = np.empty((len(r), len(x)))
        for k in range(len(x)):
            dx = np.zeros_like(x)
            dx[k] = step[k]
            J[:, k] = (fun(x + dx) - r) / step[k]
        A = J.T @ J
        g = J.T @ r
        improved = False
        for _ in range(10):
            try:
                delta = -np.linalg.solve(A + lam * np.diag(np.diag(A) + 1e-12), g)
            except np.linalg.LinAlgError:
                lam *= 10
                continue
            r_new = fun(x + delta)
            c_new = r_new @ r_new
            if np.isfinite(c_new) and c_new < cost:
                x, r, cost = x + delta, r_new, c_new
                lam = max(lam / 3, 1e-9)
                improved = True
                break
            lam *= 10
        if not improved or np.linalg.norm(delta) < 1e-10 * (1 + np.linalg.norm(x)):
            break
    return x


def refine(H: np.ndarray, c: Correspondences) -> np.ndarray:
    """Refines H on the distance in pixels (h33 fixed at 1)."""
    h0 = (H / H[2, 2]).ravel()[:8]

    def fun(h):
        return residuals(np.r_[h, 1.0].reshape(3, 3), c)

    h = least_squares(fun, h0, scale=np.maximum(np.abs(h0) * 1e-6, 1e-9))
    return np.r_[h, 1.0].reshape(3, 3)


def fit_homography(c: Correspondences) -> np.ndarray:
    return refine(dlt(c), c)


def ransac_points(pitch, image, threshold_px: float = 6.0, iters: int = 400, seed: int = 0) -> tuple[np.ndarray, np.ndarray]:
    """Homography robust to mislabeled points. Returns (H, inlier mask)."""
    P = np.asarray(pitch, dtype=float)
    I = np.asarray(image, dtype=float)
    n = len(P)
    if n < 4:
        raise ValueError("need at least 4 points")
    rng = np.random.default_rng(seed)
    best = np.zeros(n, dtype=bool)
    for _ in range(iters):
        idx = rng.choice(n, 4, replace=False)
        try:
            H = dlt(Correspondences.of_points(P[idx], I[idx]))
            err = np.linalg.norm(project(H, P) - I, axis=1)
        except (np.linalg.LinAlgError, ValueError, FloatingPointError):
            continue
        inl = err < threshold_px
        if inl.sum() > best.sum():
            best = inl
    if best.sum() < 4:
        raise ValueError("RANSAC found no consistent homography")
    H = fit_homography(Correspondences.of_points(P[best], I[best]))
    err = np.linalg.norm(project(H, P) - I, axis=1)
    return H, err < threshold_px


# ── camera model ─────────────────────────────────────────────────────────────


def _rot(axis: str, a: float) -> np.ndarray:
    c, s = math.cos(a), math.sin(a)
    if axis == "x":
        return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])
    if axis == "y":
        return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


# World (x along, y across toward the far side, z up) → camera looking along +y
# with image x to the right and image y down.
_BASE = np.array([[1.0, 0, 0], [0, 0, -1.0], [0, 1.0, 0]])


@dataclass(frozen=True)
class Camera:
    """A pinhole camera: place in meters, angles in radians, focal length in pixels."""

    cx: float
    cy: float
    cz: float
    pan: float
    tilt: float
    roll: float
    focal: float
    width: int
    height: int

    @property
    def params(self) -> np.ndarray:
        return np.array([self.cx, self.cy, self.cz, self.pan, self.tilt, self.roll, self.focal])

    def with_params(self, p) -> "Camera":
        return replace(self, cx=p[0], cy=p[1], cz=p[2], pan=p[3], tilt=p[4], roll=p[5], focal=p[6])

    def rotation(self) -> np.ndarray:
        return _rot("z", self.roll) @ _rot("x", self.tilt) @ _BASE @ _rot("z", -self.pan)

    def homography(self) -> np.ndarray:
        K = np.array([[self.focal, 0, self.width / 2], [0, self.focal, self.height / 2], [0, 0, 1]])
        R = self.rotation()
        t = -R @ np.array([self.cx, self.cy, self.cz])
        H = K @ np.column_stack([R[:, 0], R[:, 1], t])
        return H / H[2, 2]

    def fit(self, c: Correspondences, free: str = "all") -> "Camera":
        """Fits the camera to correspondences. free="ptz": only pan, tilt and zoom (place and roll fixed)."""
        idx = [0, 1, 2, 3, 4, 5, 6] if free == "all" else [3, 4, 6]
        base = self.params

        def fun(q):
            p = base.copy()
            p[idx] = q
            return residuals(self.with_params(p).homography(), c)

        q = least_squares(fun, base[idx], scale=np.array([1e-4, 1e-4, 1e-4, 1e-6, 1e-6, 1e-6, 1e-3])[idx])
        p = base.copy()
        p[idx] = q
        return self.with_params(p)

    def params_for(self, H: np.ndarray, template_pts) -> "Camera":
        """Pan, tilt and zoom (this camera's place and roll) that best reproduce H on [template_pts]."""
        pts = np.asarray(template_pts, dtype=float)
        return self.fit(Correspondences.of_points(pts, project(H, pts)), free="ptz")

    @staticmethod
    def from_homography(H: np.ndarray, width: int, height: int, template_pts) -> "Camera":
        """Fits all 7 numbers to a real homography (the closed formula left the pitch 55 px off)."""
        pts = np.asarray(template_pts, dtype=float)
        target = project(H, pts)
        c = Correspondences.of_points(pts, target)
        best, best_cost = None, math.inf
        # Seeds: behind each touchline, at several heights and focal lengths.
        cx0 = float(unproject(H, [[width / 2, height / 2]])[0, 0])
        for cy0 in (-60.0, -35.0, -15.0, 15.0, 35.0, 60.0):
            for cz0 in (4.0, 12.0, 25.0):
                for f0 in (0.8 * width, 1.6 * width):
                    pan0 = 0.0 if cy0 < 0 else math.pi
                    tilt0 = math.atan2(cz0, abs(cy0))
                    cam = Camera(cx0, cy0, cz0, pan0, tilt0, 0.0, f0, width, height)
                    try:
                        cam = cam.fit(c)
                        r = residuals(cam.homography(), c)
                    except (np.linalg.LinAlgError, FloatingPointError, ValueError):
                        continue
                    cost = float(r @ r) if np.all(np.isfinite(r)) else math.inf
                    if cost < best_cost and cam.cz > 0:
                        best, best_cost = cam, cost
        if best is None:
            raise ValueError("no camera fits this homography")
        return best


def pitch_error_m(H_est: np.ndarray, H_true: np.ndarray, pitch_pts) -> float:
    """Max distance in meters on the pitch between where both homographies put the same image points."""
    img = project(H_true, pitch_pts)
    back = unproject(H_est, img)
    return float(np.max(np.linalg.norm(back - np.asarray(pitch_pts, dtype=float), axis=1)))
