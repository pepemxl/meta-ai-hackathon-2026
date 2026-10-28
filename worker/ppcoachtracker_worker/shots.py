"""Which frames count, with the pitch mask (docs/prompts/12-mascara-cancha.md).

- A frame without pitch (close-up, stands, graphics) is discarded: it does
  not enter any metric.
- With pitch, a SAM player box counts if its feet fall in the polygon widened
  0.02 outward. (The tallest person, for close-ups, is measured with ALL the
  boxes: this filter runs after it, never before.)
- Safeguard: if fewer than half the frames have pitch (a dirt pitch, a hue the
  method does not know), the mask is not used and a note says so. A bad mask
  must not leave the analysis empty.
"""

from __future__ import annotations

from dataclasses import dataclass

from .pitch import PitchMask
from .types import FrameInfo, TrackSet

MIN_SHARE = 0.5


@dataclass
class Shots:
    trackset: TrackSet  # rows that count, all frames
    counted: list[FrameInfo]  # frames that enter the metrics
    discarded: list[list[int]]  # [start_ms, end_ms] of each discarded stretch
    used_mask: bool
    with_pitch: int


def apply(trackset: TrackSet, masks: list[PitchMask | None]) -> Shots:
    frames = trackset.frames
    with_pitch = sum(1 for m in masks if m is not None and m.has_pitch)
    if not frames or with_pitch < MIN_SHARE * len(frames):
        return Shots(trackset, list(frames), [], used_mask=False, with_pitch=with_pitch)
    keep = {f.frame for f, m in zip(frames, masks) if m is not None and m.has_pitch}
    rows = []
    for r in trackset.rows:
        if r.frame not in keep:
            continue
        m = masks[r.frame]
        if r.label == "player" and not m.contains(*r.foot):
            continue
        rows.append(r)
    counted = [f for f in frames if f.frame in keep]
    discarded: list[list[int]] = []
    start = None
    for i, f in enumerate(frames):
        if f.frame not in keep and start is None:
            start = f.t_ms
        if (f.frame in keep or i == len(frames) - 1) and start is not None:
            end = f.t_ms if f.frame in keep else f.t_ms + 100
            discarded.append([start, end])
            start = None
    return Shots(TrackSet(rows=rows, frames=frames), counted, discarded, used_mask=True, with_pitch=with_pitch)
