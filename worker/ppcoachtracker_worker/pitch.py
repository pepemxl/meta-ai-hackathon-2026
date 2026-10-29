"""Which part of each frame is the pitch (docs/prompts/12-mascara-cancha.md).

A 160×90 grid of points of the frame in HSV; grass is hue 35–85 (0..180 as
OpenCV), saturation > 60 and value > 40. A 5×5 closing, the largest blob,
its convex hull simplified to at most 12 points. If less than 70 % of the
polygon is grass it is NOT a pitch (camera behind the goal, goalkeeper in
green, VAR graphics). Returns the grass fraction and the polygon (0..1).

The app (data/PitchMask.kt) does EXACTLY the same, with the same grid of
points and the same arithmetic, so both agree; a shared fixture checks it.
Written by hand (no OpenCV) for that reason.
"""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass

import numpy as np

METHOD = "hsv_v1"
GRID_W, GRID_H = 160, 90
HUE = (35, 85)
MIN_SAT = 60
MIN_VAL = 40
CLOSE = 5
MAX_VERTICES = 12
MIN_GRASS_IN_POLYGON = 0.70
MIN_BLOB = 0.02  # of the grid: smaller blobs are noise, not a pitch
FOOT_MARGIN = 0.02


@dataclass(frozen=True)
class PitchMask:
    fraction: float  # grass fraction of the whole frame
    polygon: tuple[float, ...]  # x1, y1, x2, y2, … in 0..1; empty = no pitch

    @property
    def has_pitch(self) -> bool:
        return len(self.polygon) >= 6

    def contains(self, x: float, y: float, margin: float = FOOT_MARGIN) -> bool:
        """Inside the polygon widened [margin] outward."""
        pts = list(zip(self.polygon[0::2], self.polygon[1::2]))
        return point_in_polygon(x, y, pts) or distance_to_polygon(x, y, pts) <= margin


def grid_points(width: int, height: int) -> tuple[np.ndarray, np.ndarray]:
    """Pixel column and row of each grid point: the center of each cell, truncated."""
    xs = ((np.arange(GRID_W) + 0.5) * width / GRID_W).astype(int)
    ys = ((np.arange(GRID_H) + 0.5) * height / GRID_H).astype(int)
    return np.clip(xs, 0, width - 1), np.clip(ys, 0, height - 1)


def sample_rgb(image_bgr: np.ndarray) -> np.ndarray:
    """The grid (GRID_H × GRID_W × 3, RGB) of a BGR frame."""
    h, w = image_bgr.shape[:2]
    xs, ys = grid_points(w, h)
    return image_bgr[np.ix_(ys, xs)][..., ::-1].astype(np.int64)


def grass_cells(rgb: np.ndarray) -> np.ndarray:
    """Boolean grid: HSV as OpenCV computes it for 8-bit images (H in 0..180)."""
    r, g, b = (rgb[..., k].astype(np.float64) for k in range(3))
    v = np.maximum(np.maximum(r, g), b)
    mn = np.minimum(np.minimum(r, g), b)
    d = v - mn
    s = np.where(v > 0, d / np.where(v > 0, v, 1) * 255.0, 0.0)
    safe = np.where(d > 0, d, 1)
    h = np.where(v == r, 60.0 * (g - b) / safe, np.where(v == g, 120.0 + 60.0 * (b - r) / safe, 240.0 + 60.0 * (r - g) / safe))
    h = np.where(d > 0, h, 0.0)
    h = np.where(h < 0, h + 360.0, h) / 2.0
    return (h >= HUE[0]) & (h <= HUE[1]) & (s > MIN_SAT) & (v > MIN_VAL)


def _morph(mask: np.ndarray, dilate: bool) -> np.ndarray:
    """Dilation (any) or erosion (all) with a CLOSE×CLOSE square; outside the grid is ignored."""
    r = CLOSE // 2
    h, w = mask.shape
    padded = np.pad(mask, r, constant_values=not dilate)
    out = np.zeros_like(mask) if dilate else np.ones_like(mask)
    for dy in range(CLOSE):
        for dx in range(CLOSE):
            win = padded[dy:dy + h, dx:dx + w]
            out = (out | win) if dilate else (out & win)
    return out


def close(mask: np.ndarray) -> np.ndarray:
    return _morph(_morph(mask, True), False)


def largest_blob(mask: np.ndarray) -> list[tuple[int, int]]:
    """Cells (x, y) of the largest 8-connected blob; ties: the first found scanning by rows."""
    h, w = mask.shape
    seen = np.zeros_like(mask)
    best: list[tuple[int, int]] = []
    for y in range(h):
        for x in range(w):
            if not mask[y, x] or seen[y, x]:
                continue
            blob = []
            q = deque([(x, y)])
            seen[y, x] = True
            while q:
                cx, cy = q.popleft()
                blob.append((cx, cy))
                for dy in (-1, 0, 1):
                    for dx in (-1, 0, 1):
                        nx, ny = cx + dx, cy + dy
                        if 0 <= nx < w and 0 <= ny < h and mask[ny, nx] and not seen[ny, nx]:
                            seen[ny, nx] = True
                            q.append((nx, ny))
            if len(blob) > len(best):
                best = blob
    return best


def convex_hull(points: list[tuple[float, float]]) -> list[tuple[float, float]]:
    """Andrew's monotone chain, counter-clockwise in image coordinates, without collinear points."""
    pts = sorted(set(points))
    if len(pts) < 3:
        return pts

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    lower: list[tuple[float, float]] = []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    upper: list[tuple[float, float]] = []
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    return lower[:-1] + upper[:-1]


def simplify(poly: list[tuple[float, float]], max_vertices: int = MAX_VERTICES) -> list[tuple[float, float]]:
    """Removes the vertex that loses the least area until at most [max_vertices] (ties: the first)."""
    poly = list(poly)
    while len(poly) > max_vertices:
        n = len(poly)
        best_i, best_a = 0, None
        for i in range(n):
            a, b, c = poly[i - 1], poly[i], poly[(i + 1) % n]
            area = abs((b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])) / 2
            if best_a is None or area < best_a:
                best_i, best_a = i, area
        poly.pop(best_i)
    return poly


def point_in_polygon(x: float, y: float, pts: list[tuple[float, float]]) -> bool:
    inside = False
    n = len(pts)
    for i in range(n):
        x1, y1 = pts[i]
        x2, y2 = pts[(i + 1) % n]
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1:
            inside = not inside
    return inside


def distance_to_polygon(x: float, y: float, pts: list[tuple[float, float]]) -> float:
    best = float("inf")
    n = len(pts)
    for i in range(n):
        (x1, y1), (x2, y2) = pts[i], pts[(i + 1) % n]
        dx, dy = x2 - x1, y2 - y1
        L = dx * dx + dy * dy
        t = 0.0 if L == 0 else max(0.0, min(1.0, ((x - x1) * dx + (y - y1) * dy) / L))
        best = min(best, ((x - x1 - t * dx) ** 2 + (y - y1 - t * dy) ** 2) ** 0.5)
    return best


def _inside_grid(pts: list[tuple[float, float]]) -> np.ndarray:
    """point_in_polygon for every grid point (x/(W-1), y/(H-1)), vectorized with the same arithmetic."""
    X, Y = np.meshgrid(np.arange(GRID_W) / (GRID_W - 1), np.arange(GRID_H) / (GRID_H - 1))
    inside = np.zeros(X.shape, dtype=bool)
    n = len(pts)
    for i in range(n):
        x1, y1 = pts[i]
        x2, y2 = pts[(i + 1) % n]
        if y1 == y2:
            continue  # never crosses: (y1 > y) != (y2 > y) is false
        cross = ((y1 > Y) != (y2 > Y)) & (X < (x2 - x1) * (Y - y1) / (y2 - y1) + x1)
        inside ^= cross
    return inside


def blob_row_ends(mask: np.ndarray) -> list[tuple[int, int]]:
    """The leftmost and rightmost cell of each row of the largest blob (enough for its hull).

    Same blob as largest_blob (8-connected, ties to the first found by rows),
    with OpenCV's labeling, which numbers blobs in that same scan order.
    """
    import cv2

    n, labels, stats, _ = cv2.connectedComponentsWithStats(mask.astype(np.uint8), connectivity=8)
    if n <= 1:
        return []
    areas = stats[1:, cv2.CC_STAT_AREA]
    best = 1 + int(np.argmax(areas))  # argmax keeps the first of equal areas
    if areas[best - 1] < MIN_BLOB * GRID_W * GRID_H:
        return []
    ends = []
    for y in range(mask.shape[0]):
        xs = np.flatnonzero(labels[y] == best)
        if len(xs):
            ends += [(int(xs[0]), y), (int(xs[-1]), y)]
    return ends


def mask_from_grid(rgb: np.ndarray) -> PitchMask:
    """The pitch of one frame from its RGB grid (GRID_H × GRID_W × 3)."""
    grass = grass_cells(rgb)
    fraction = round(float(grass.mean()), 4)
    ends = blob_row_ends(close(grass))
    if not ends:
        return PitchMask(fraction, ())
    hull = simplify(convex_hull([(x / (GRID_W - 1), y / (GRID_H - 1)) for x, y in ends]))
    if len(hull) < 3:
        return PitchMask(fraction, ())
    inside_poly = _inside_grid(hull)
    total = int(inside_poly.sum())
    inside = int((inside_poly & grass).sum())
    if total == 0 or inside / total < MIN_GRASS_IN_POLYGON:
        return PitchMask(fraction, ())
    return PitchMask(fraction, tuple(round(v, 4) for p in hull for v in p))


def measure(image_bgr: np.ndarray) -> PitchMask:
    return mask_from_grid(sample_rgb(image_bgr))


def from_yaml_row(row, method: str | None) -> PitchMask | None:
    """A row [frame, fraction, [x1, y1, …]] of the app's YAML, or None if it cannot be trusted."""
    if method != METHOD:
        return None
    try:
        fraction = float(row[1])
        poly = [min(max(float(v), 0.0), 1.0) for v in (row[2] or [])]
    except (TypeError, ValueError, IndexError):
        return None
    if poly and (len(poly) < 6 or len(poly) % 2):
        return None
    return PitchMask(round(min(max(fraction, 0.0), 1.0), 4), tuple(poly))
