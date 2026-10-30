"""Wide video (a panorama, or 4K/8K from a phone) cut into tiles SAM sees at their size.

SAM resizes every frame so its long side is 1024 px (and pads it square)
before the encoder: in a 3840-wide panorama the players shrink to a quarter,
a few pixels, and SAM finds nobody. Tiles of up to 1024 px across the pitch,
side by side with some overlap, keep the players at the size they were
recorded; the tracks of neighbouring tiles are joined again in their overlap
(docs/learnings/04-video-ancho-y-sam.md).

- plan: the fewest tiles (2 to MAX_TILES) that cover the pitch with the
  video scaled down at most to MIN_SCALE; no tiles if the pitch fits one.
- join_tracks: two tracks of neighbouring tiles are the same player when
  their boxes overlap (IoU > 0.5) in at least half of the frames both are
  inside the overlap, and in 5 or more (the rule between chunks, sam.py).
- dedupe: inside an overlap one box per player and frame, the one of the
  tile whose edge is farther away (near an edge the box may be cut).
"""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from .types import iou

SIZE = 1024
OVERLAP = 128  # about 5 widths of a far-away player
MAX_TILES = 4
MIN_SCALE = 0.8
# "auto": tile a video whose long side is over twice what SAM sees, or a panorama.
TRIGGER_LONG_SIDE = 2048
TRIGGER_ASPECT = 2.2
JOIN_IOU = 0.5
JOIN_MIN_FRAMES = 5


@dataclass(frozen=True)
class Tile:
    index: int  # 1, 2, ...
    x: float  # its crop in the frame, in pixels of the video it comes from
    y: float
    w: float
    h: float
    out_w: int  # the size it is written at
    out_h: int


@dataclass(frozen=True)
class TilePlan:
    scale: float
    tiles: tuple[Tile, ...]
    pitch: tuple[float, float, float, float]  # x0, y0, x1, y1 in pixels

    @property
    def overlap_px(self) -> int:
        """Overlap between neighbours, in tile pixels."""
        if len(self.tiles) < 2:
            return 0
        return round((self.tiles[0].x + self.tiles[0].w - self.tiles[1].x) * self.scale)

    def edges(self, width: int) -> dict[int, tuple[float, float]]:
        """Each tile's left and right edge, normalized to the frame width."""
        return {t.index: (t.x / width, (t.x + t.w) / width) for t in self.tiles}


def should_tile(mode: str, width: int, height: int) -> bool:
    """mode: "auto" (wide or very large video), "on" or "off"."""
    if mode == "off" or width <= 0 or height <= 0:
        return False
    if mode == "on":
        return True
    return max(width, height) > TRIGGER_LONG_SIDE or width / height >= TRIGGER_ASPECT


def pitch_box(polygons: list[list[float] | tuple[float, ...]], width: int, height: int, margin: float = 0.0) -> tuple[float, float, float, float]:
    """The pitch over many frames (normalized polygons), in pixels: the 10/90
    percentiles of the edges, so a frame with grass outside does not grow it.
    The whole frame when there is no pitch."""
    boxes = [(min(p[0::2]), min(p[1::2]), max(p[0::2]), max(p[1::2])) for p in polygons if p and len(p) >= 6]
    if not boxes:
        return 0.0, 0.0, float(width), float(height)
    b = np.array(boxes)
    x0 = max(0.0, float(np.percentile(b[:, 0], 10)) - margin) * width
    y0 = max(0.0, float(np.percentile(b[:, 1], 10)) - margin) * height
    x1 = min(1.0, float(np.percentile(b[:, 2], 90)) + margin) * width
    y1 = min(1.0, float(np.percentile(b[:, 3], 90)) + margin) * height
    return x0, y0, x1, y1


def _even(v: float) -> int:
    return max(2, int(round(v / 2)) * 2)


def plan(width: int, height: int, pitch: tuple[float, float, float, float], size: int = SIZE, overlap: int = OVERLAP,
         tiles: int | None = None, max_tiles: int = MAX_TILES, min_scale: float = MIN_SCALE) -> TilePlan | None:
    """Tiles over the pitch. [tiles] fixes their number; else the fewest that
    keep the scale at [min_scale] or more (up to [max_tiles]). None when the
    pitch fits in one tile."""
    x0, y0, x1, y1 = pitch
    pw, ph = max(1.0, x1 - x0), max(1.0, y1 - y0)

    def scale_for(n: int) -> float:
        return min(1.0, (n * size - (n - 1) * overlap) / pw, size / ph)

    if tiles is None:
        if scale_for(1) >= min_scale:
            return None
        n = next((k for k in range(2, max_tiles + 1) if scale_for(k) >= min_scale), max_tiles)
    else:
        n = max(1, tiles)
    scale = scale_for(n)
    tw = min(size, _even(pw * scale)) if n == 1 else size
    th = min(size, _even(ph * scale))
    span = pw * scale
    if span < tw:  # a narrow pitch: the tiles are wider than it, centered on it
        pw = min(float(width), tw / scale)
        x0 = max(0.0, min(width - pw, (x0 + x1 - pw) / 2))
        span = pw * scale
    step = (span - tw) / (n - 1) if n > 1 else 0.0
    out = []
    for k in range(n):
        sx = min(max(0.0, x0 + round(k * step) / scale), max(0.0, width - tw / scale))
        out.append(Tile(k + 1, round(sx, 1), round(y0, 1), round(tw / scale, 1), round(min(th / scale, height - y0), 1), tw, th))
    return TilePlan(round(scale, 4), tuple(out), (x0, y0, x0 + pw, y1))


def write_tiles(video: Path, tile_plan: TilePlan, out_dir: Path, stem: str, fps: float) -> list[Path]:
    """Writes each tile of [video] as its own MP4 (MPEG-4, which SAM accepts) in [out_dir]."""
    import cv2

    out_dir.mkdir(parents=True, exist_ok=True)
    paths = [out_dir / f"{stem}_t{t.index}.mp4" for t in tile_plan.tiles]
    writers = [cv2.VideoWriter(str(p), cv2.VideoWriter_fourcc(*"mp4v"), fps, (t.out_w, t.out_h)) for p, t in zip(paths, tile_plan.tiles)]
    cap = cv2.VideoCapture(str(video))
    try:
        while True:
            ok, img = cap.read()
            if not ok:
                break
            for w, t in zip(writers, tile_plan.tiles):
                crop = img[int(t.y):int(round(t.y + t.h)), int(t.x):int(round(t.x + t.w))]
                w.write(cv2.resize(crop, (t.out_w, t.out_h), interpolation=cv2.INTER_AREA))
    finally:
        cap.release()
        for w in writers:
            w.release()
    return paths


def to_frame(box: tuple[float, float, float, float], tile: Tile, width: int, height: int) -> tuple[float, float, float, float]:
    """A box normalized to its tile → normalized to the whole frame."""
    x, y, w, h = box
    return ((tile.x + x * tile.w) / width, (tile.y + y * tile.h) / height, w * tile.w / width, h * tile.h / height)


@dataclass
class TileBox:
    frame: int
    tile: int
    track: int  # the track inside its tile
    label: str
    box: tuple[float, float, float, float]  # normalized to the whole frame
    conf: float = 1.0


def join_tracks(boxes: list[TileBox], edges: dict[int, tuple[float, float]], label: str = "player") -> tuple[dict[tuple[int, int], tuple[int, int]], list[dict]]:
    """Joins the [label] tracks of neighbouring tiles in their overlap.

    Returns {(tile, track): (tile, track) of its group's first member} for
    every node, and the joins made ({tiles, tracks, frames}).
    """
    parent: dict[tuple[int, int], tuple[int, int]] = {}

    def find(n):
        parent.setdefault(n, n)
        while parent[n] != n:
            parent[n] = parent[parent[n]]
            n = parent[n]
        return n

    for b in boxes:
        find((b.tile, b.track))
    by_frame: dict[int, dict[int, list[TileBox]]] = defaultdict(lambda: defaultdict(list))
    for b in boxes:
        if b.label == label:
            by_frame[b.frame][b.tile].append(b)
    order = sorted(edges)
    joins = []
    for li, ri in zip(order, order[1:]):
        lo, hi = edges[ri][0], edges[li][1]
        if hi <= lo:
            continue

        def inside(b):
            return lo <= b.box[0] + b.box[2] / 2 <= hi

        both: dict[tuple[int, int], int] = defaultdict(int)
        good: dict[tuple[int, int], int] = defaultdict(int)
        for per_tile in by_frame.values():
            ls = [b for b in per_tile.get(li, []) if inside(b)]
            rs = [b for b in per_tile.get(ri, []) if inside(b)]
            for x in ls:
                for y in rs:
                    k = (x.track, y.track)
                    both[k] += 1
                    if iou(x.box, y.box) > JOIN_IOU:
                        good[k] += 1
        used_l, used_r = set(), set()
        for (lt, rt), n in sorted(good.items(), key=lambda kv: -kv[1]):
            if n < JOIN_MIN_FRAMES or n * 2 < both[(lt, rt)] or lt in used_l or rt in used_r:
                continue
            used_l.add(lt)
            used_r.add(rt)
            parent[find((ri, rt))] = find((li, lt))
            joins.append({"tiles": [li, ri], "tracks": [lt, rt], "frames": n})
    return {n: find(n) for n in list(parent)}, joins


def margin_in_tile(b: TileBox, edges: dict[int, tuple[float, float]]) -> float:
    """How far the box's center is from its tile's nearer side edge (normalized)."""
    lo, hi = edges[b.tile]
    cx = b.box[0] + b.box[2] / 2
    return min(cx - lo, hi - cx)


def dedupe(boxes: list[TileBox], roots: dict[tuple[int, int], tuple[int, int]], edges: dict[int, tuple[float, float]]) -> list[TileBox]:
    """One box per joined track and frame: the one farthest from its tile's edge."""
    best: dict[tuple[int, tuple[int, int]], TileBox] = {}
    for b in boxes:
        group = roots.get((b.tile, b.track), (b.tile, b.track))
        k = (b.frame, group)
        prev = best.get(k)
        if prev is None or margin_in_tile(b, edges) > margin_in_tile(prev, edges):
            best[k] = b
    return sorted(best.values(), key=lambda b: (b.frame, b.tile, b.track))
