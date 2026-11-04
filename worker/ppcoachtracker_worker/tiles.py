"""Wide video (a panorama, or 4K/8K from a phone) cut into tiles SAM sees at their size.

SAM resizes every frame so its long side is 1024 px (and pads it square)
before the encoder: in a 3840-wide panorama the players shrink to a quarter,
a few pixels, and SAM finds nobody. Tiles of up to 1024 px across the pitch,
side by side with some overlap, keep the players at the size they were
recorded; the tracks of neighbouring tiles are joined again in their overlap
(docs/learnings/04-video-ancho-y-sam.md).

A 16:9 video can need it too: a high tactical camera shows the whole pitch
with players of 30–40 px, about 20 once SAM shrinks 1920 to 1024.
- player_heights: how tall the players are, without detecting them: the
  non-grass blobs inside the pitch that are taller than wide. It only decides
  whether to tile; the players still come only from SAM.
- reason: "wide" (a panorama, or a long side over twice SAM's), "small"
  (the median player under MIN_SAM_PLAYER_PX once SAM shrinks the frame) or
  None. Results (docs/results/01): SAM found nobody at 10 px, worked at 28.
- mask_outside: what is not the pitch (stands, benches, the press area) goes
  black in the tiles, so SAM does not track it. The pitch is widened by more
  than a player's height: the heads of the far players and a ball in the air
  stay in.
- plan: the fewest tiles (2 to MAX_TILES) that cover the pitch with the
  video scaled down at most to MIN_SCALE; no tiles if the pitch fits one.
- join_tracks: two tracks of neighbouring tiles are the same player when
  their boxes overlap (IoU > 0.5) in at least half of the frames both are
  inside the overlap, and in 5 or more (the rule between chunks, sam.py).
- dedupe: inside an overlap one box per player and frame, the one of the
  tile whose edge is farther away (near an edge the box may be cut). Also
  for tracks that were not joined: two boxes of different tiles with
  IoU > 0.5 in a frame are one person (in a 5-minute chunk a player changes
  track inside a tile, and Test03 kept 2.5 boxes too many per frame).
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
# A player SAM sees smaller than this (median, in the 1024 px it works at) goes in tiles.
MIN_SAM_PLAYER_PX = 24
MIN_PLAYER_BLOBS = 30  # fewer over the sampled frames: no measure, no tiles for size
SAMPLE_FRAMES = 12
# The black outside the pitch starts this many player heights (or MIN_MASK_MARGIN of the height) out.
MASK_PLAYER_HEIGHTS = 2.0
MIN_MASK_MARGIN = 0.03


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


def reason(mode: str, width: int, height: int, player_px: float | None) -> str | None:
    """Why this video goes in tiles: "on", "wide", "small" (players too small for SAM) or None."""
    if mode == "off" or width <= 0 or height <= 0:
        return None
    if mode == "on":
        return "on"
    if should_tile(mode, width, height):
        return "wide"
    # Only a video larger than what SAM sees loses detail when SAM shrinks it: tiles cannot enlarge.
    if player_px is not None and max(width, height) > SIZE and player_px * SIZE / max(width, height) < MIN_SAM_PLAYER_PX:
        return "small"
    return None


def _pitch_pixels(shape: tuple[int, int], polygon, grow_px: float = 0.0, aspect: float | None = None) -> np.ndarray:
    """255 inside the (normalized) polygon, widened [grow_px] outward (negative: shrunk)."""
    import cv2

    h, w = shape
    out = np.zeros((h, w), np.uint8)
    pts = np.array([[x * w, y * h] for x, y in zip(polygon[0::2], polygon[1::2])], np.int32)
    cv2.fillPoly(out, [pts], 255)
    r = int(round(abs(grow_px)))
    if r > 0:
        k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1))
        out = cv2.dilate(out, k) if grow_px > 0 else cv2.erode(out, k)
    return out


def player_heights(image_bgr: np.ndarray, polygon) -> list[int]:
    """Heights in pixels of the non-grass blobs inside the pitch shaped like a
    standing person (taller than wide, fairly solid). Lines, the circle and
    the grass stripes are not."""
    import cv2

    h, w = image_bgr.shape[:2]
    hsv = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2HSV)
    grass = cv2.inRange(hsv, (35, 60, 40), (85, 255, 255))  # pitch.py's thresholds
    inside = _pitch_pixels((h, w), polygon, -7)  # away from the edge of the pitch
    fg = cv2.bitwise_and(cv2.bitwise_not(grass), inside)
    fg = cv2.morphologyEx(fg, cv2.MORPH_OPEN, np.ones((2, 2), np.uint8))
    fg = cv2.morphologyEx(fg, cv2.MORPH_CLOSE, np.ones((5, 3), np.uint8))
    _, _, stats, _ = cv2.connectedComponentsWithStats(fg)
    return [int(bh) for _, _, bw, bh, area in stats[1:] if 8 <= bh <= 0.3 * h and 0.2 <= bw / bh <= 0.8 and area >= 0.3 * bw * bh]


def median_player(heights: list[int]) -> float | None:
    return float(np.median(heights)) if len(heights) >= MIN_PLAYER_BLOBS else None


def seen_px(player_px: float | None, width: int, height: int, tile_plan: "TilePlan | None") -> float | None:
    """The median player's height as SAM sees it: at the tiles' scale, else
    with the whole frame shrunk to SIZE on its long side (never enlarged)."""
    if not player_px or width <= 0 or height <= 0:
        return None
    if tile_plan is not None:
        return player_px * tile_plan.scale
    return player_px * min(1.0, SIZE / max(width, height))


def mask_margin(player_px: float | None, height: int, coach_margin: float = 0.0) -> float:
    """How far out the black starts, as a fraction of the height."""
    heads = MASK_PLAYER_HEIGHTS * player_px / height if player_px else 0.0
    return max(MIN_MASK_MARGIN, coach_margin + heads)


def mask_outside(image_bgr: np.ndarray, polygon, margin: float) -> np.ndarray:
    """The frame with what is farther than [margin] (fraction of the height) from the pitch polygon in black."""
    keep = _pitch_pixels(image_bgr.shape[:2], polygon, margin * image_bgr.shape[0])
    out = image_bgr.copy()
    out[keep == 0] = 0
    return out


def grass_region(image_bgr: np.ndarray, scale: int = 4, surface: str = "grass", sample=None) -> np.ndarray | None:
    """The pitch as the grass really is (not its convex hull, which takes in a
    press area between two corners): the largest grass blob with its holes
    (the players, the lines) filled, at full size. None when there is none.
    On a dirt pitch ([surface] "dirt"), the largest blob of dirt; with the
    coach's [sample] (pitch.SurfaceSample), of its colors."""
    import cv2

    from .pitch import DIRT, DIRT_HUE, DIRT_SAT, DIRT_VAL

    h, w = image_bgr.shape[:2]
    small = cv2.resize(image_bgr, (max(1, w // scale), max(1, h // scale)), interpolation=cv2.INTER_AREA)
    hsv = cv2.cvtColor(small, cv2.COLOR_BGR2HSV)
    if sample is not None:
        grass = cv2.inRange(hsv, (sample.hue[0], sample.sat[0], sample.val[0]), (sample.hue[1], sample.sat[1], sample.val[1]))
    elif surface == DIRT:
        grass = cv2.inRange(hsv, (DIRT_HUE[0], DIRT_SAT[0] + 1, DIRT_VAL[0] + 1), (DIRT_HUE[1], DIRT_SAT[1], DIRT_VAL[1]))
    else:
        grass = cv2.inRange(hsv, (35, 60, 40), (85, 255, 255))
    grass = cv2.morphologyEx(grass, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
    n, labels, stats, _ = cv2.connectedComponentsWithStats(grass)
    if n < 2:
        return None
    best = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
    if stats[best, cv2.CC_STAT_AREA] < 0.05 * grass.size:
        return None
    blob = np.where(labels == best, 255, 0).astype(np.uint8)
    # Holes: what is not grass and not reachable from the border.
    outside = cv2.bitwise_not(blob)
    pad = cv2.copyMakeBorder(outside, 1, 1, 1, 1, cv2.BORDER_CONSTANT, value=255)
    cv2.floodFill(pad, None, (0, 0), 0)
    blob = cv2.bitwise_or(blob, pad[1:-1, 1:-1])
    return cv2.resize(blob, (w, h), interpolation=cv2.INTER_NEAREST)


def grass_margins(player_px: float | None, height: int) -> tuple[int, int]:
    """(up, around) in pixels: above the grass the far players' heads and a
    ball in the air (MASK_PLAYER_HEIGHTS players); elsewhere the feet are on
    the grass and half a player is enough."""
    p = player_px or MIN_MASK_MARGIN * height / MASK_PLAYER_HEIGHTS
    return int(round(max(MIN_MASK_MARGIN * height, MASK_PLAYER_HEIGHTS * p))), int(round(max(0.015 * height, p / 2)))


def mask_outside_grass(image_bgr: np.ndarray, up: int, around: int, surface: str = "grass", sample=None) -> np.ndarray:
    """The frame with what is not near the grass (or the dirt) in black: [up]
    pixels above it, [around] to the sides and below. As it is when there is no pitch."""
    import cv2

    region = grass_region(image_bgr, surface=surface, sample=sample)
    if region is None:
        return image_bgr
    if up > 0:
        # Each pixel takes the grass up to [up] below it: the region grows upward only.
        region = cv2.dilate(region, np.ones((up + 1, 1), np.uint8), anchor=(0, 0))
    if around > 0:
        region = cv2.dilate(region, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * around + 1, 2 * around + 1)))
    out = image_bgr.copy()
    out[region == 0] = 0
    return out


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


def write_tiles(video: Path, tile_plan: TilePlan, out_dir: Path, stem: str, fps: float, mask=None) -> list[Path]:
    """Writes each tile of [video] as its own MP4 (MPEG-4, which SAM accepts) in [out_dir].
    [mask](image) gives the frame with what is not the pitch in black (None: as it is)."""
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
            if mask is not None:
                img = mask(img)
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
    """One box per joined track and frame, and per person in a frame: the one farthest from its tile's edge."""
    best: dict[tuple[int, tuple[int, int]], TileBox] = {}
    for b in boxes:
        group = roots.get((b.tile, b.track), (b.tile, b.track))
        k = (b.frame, group)
        prev = best.get(k)
        if prev is None or margin_in_tile(b, edges) > margin_in_tile(prev, edges):
            best[k] = b
    by_frame: dict[int, list[TileBox]] = defaultdict(list)
    for b in best.values():
        by_frame[b.frame].append(b)
    kept: list[TileBox] = []
    for frame_boxes in by_frame.values():
        # Farthest from an edge first: a box of another tile on the same person goes.
        frame_boxes.sort(key=lambda b: -margin_in_tile(b, edges))
        mine: list[TileBox] = []
        for b in frame_boxes:
            if not any(o.tile != b.tile and iou(o.box, b.box) > JOIN_IOU for o in mine):
                mine.append(b)
        kept += mine
    return sorted(kept, key=lambda b: (b.frame, b.tile, b.track))
