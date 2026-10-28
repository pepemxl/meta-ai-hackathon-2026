"""Which frames count (docs/prompts/11-worker-calidad.md and 12-mascara-cancha.md).

A frame does not count:
- without pitch (the mask);
- if a person is taller than 1/3 of the image (close-up, celebration, the
  stands), unless there are small players WITH THEIR FEET ON THE GRASS behind.
  The tallest person is measured with ALL the boxes, before any filter.
The rule goes by shots (between cuts), not by frame: a short shot with two
thirds discarded is discarded whole; otherwise frame by frame. Short
discarded runs (< 5 frames) inside a counted shot are refilled.

What is discarded enters no metric, splits the tracks and is a break for
possession. With pitch, a player box counts if its feet fall in the polygon
widened 0.02. If fewer than half the frames have pitch, the mask is not used.
"""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass

from .pitch import PitchMask
from .types import FrameInfo, TrackRow, TrackSet

MIN_SHARE = 0.5
CLOSEUP_HEIGHT = 1 / 3
SMALL_PLAYER_HEIGHT = 0.15  # "small players behind": shorter than this
MIN_SMALL_BEHIND = 2
SHORT_SHOT_FRAMES = 30  # 3 s at 10 fps
SHORT_SHOT_DISCARDED = 2 / 3
REFILL_FRAMES = 5
MIN_COUNTED_SECONDS = 30


@dataclass
class Shots:
    trackset: TrackSet  # rows that count, tracks split at cuts and discarded stretches; all frames
    counted: list[FrameInfo]  # frames that enter the metrics
    discarded: list[list[int]]  # [start_ms, end_ms] of each discarded stretch
    breaks: set[int]  # frames where continuity breaks (after a cut or a discarded stretch)
    used_mask: bool
    with_pitch: int
    closeups: int


def _closeup(rows: list[TrackRow], mask: PitchMask | None) -> bool:
    people = [r for r in rows if r.label == "player"]
    if not people or max(r.h for r in people) <= CLOSEUP_HEIGHT:
        return False
    # Small players with their feet on the grass behind: a wide shot with someone in front.
    small = [r for r in people if r.h < SMALL_PLAYER_HEIGHT and (mask is None or not mask.has_pitch or mask.contains(*r.foot, margin=0.0))]
    return len(small) < MIN_SMALL_BEHIND


def _runs(flags: list[bool]) -> list[tuple[int, int, bool]]:
    out = []
    i = 0
    while i < len(flags):
        j = i
        while j < len(flags) and flags[j] == flags[i]:
            j += 1
        out.append((i, j, flags[i]))
        i = j
    return out


def apply(trackset: TrackSet, masks: list[PitchMask | None], cuts: set[int] | None = None) -> Shots:
    frames = trackset.frames
    n = len(frames)
    cuts = set(cuts or ())
    by_frame: dict[int, list[TrackRow]] = defaultdict(list)
    for r in trackset.rows:
        by_frame[r.frame].append(r)

    with_pitch = sum(1 for m in masks if m is not None and m.has_pitch)
    use_mask = n > 0 and with_pitch >= MIN_SHARE * n
    closeup = [_closeup(by_frame.get(i, []), masks[i] if i < len(masks) else None) for i in range(n)]
    ok = [(not closeup[i]) and (not use_mask or (masks[i] is not None and masks[i].has_pitch)) for i in range(n)]

    # By shots: a short shot mostly discarded goes whole; short discarded runs are refilled.
    bounds = [0] + sorted(c for c in cuts if 0 < c < n) + [n]
    for a, b in zip(bounds, bounds[1:]):
        shot = ok[a:b]
        if not shot:
            continue
        if b - a < SHORT_SHOT_FRAMES and shot.count(False) >= SHORT_SHOT_DISCARDED * len(shot):
            ok[a:b] = [False] * (b - a)
            continue
        for s, e, val in _runs(shot):
            if not val and e - s < REFILL_FRAMES and s > 0 and e < len(shot):
                ok[a + s:a + e] = [True] * (e - s)

    # Breaks: the first frame of every shot and the first counted frame after a discarded stretch.
    breaks = {c for c in cuts if 0 < c < n}
    for i in range(1, n):
        if ok[i] and not ok[i - 1]:
            breaks.add(i)

    # Split tracks at every break: same SAM id, new identity after it.
    segment_of = [0] * n
    seg = 0
    for i in range(n):
        if i in breaks:
            seg += 1
        segment_of[i] = seg
    new_id: dict[tuple[int, int], int] = {}
    next_id = max((r.track_id for r in trackset.rows), default=0) + 1
    rows = []
    for r in sorted(trackset.rows, key=lambda r: (r.frame, r.track_id)):
        if r.frame >= n or not ok[r.frame]:
            continue
        m = masks[r.frame] if r.frame < len(masks) else None
        if use_mask and r.label == "player" and not m.contains(*r.foot):
            continue
        key = (r.track_id, segment_of[r.frame])
        if key not in new_id:
            first_seg = min((s for (t, s) in new_id if t == r.track_id), default=None)
            new_id[key] = r.track_id if first_seg is None else next_id
            if first_seg is not None:
                next_id += 1
        rows.append(r.with_(track_id=new_id[key]))

    counted = [f for i, f in enumerate(frames) if ok[i]]
    discarded: list[list[int]] = []
    for s, e, val in _runs(ok):
        if not val:
            end = frames[e].t_ms if e < n else frames[e - 1].t_ms + 100
            discarded.append([frames[s].t_ms, end])
    return Shots(TrackSet(rows=rows, frames=frames), counted, discarded, breaks, use_mask, with_pitch, sum(closeup))
