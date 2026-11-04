"""Long stoppages: 90 s or more with the ball still (a hydration break, a long injury).

A match has a hydration break near minute 22 of each half; the video does not
always start at kick-off, so it is found by the ball and not by the clock. In
Test03 (49 min) the break left 150-160 s with the ball still and nothing else
went past 40 s (free kicks, throw-ins, goal kicks). The pause leaves the
metrics like a discarded stretch: its frames do not count, its boxes are
dropped and nothing joins across it (possession, tracks).
"""

from __future__ import annotations

from dataclasses import replace

from .metrics import _dist, ball_per_frame
from .types import TrackRow, TrackSet

STILL_STEP = 0.005  # screen the ball moves in 1 s: still (in play it moves 0.01 to 0.08)
MIN_PAUSE_SECONDS = 90
WINDOW_SECONDS = 30
MIN_STILL_SHARE = 0.6  # of the seconds of every window: the break had 88 %, play at most 23 %


def find(ball_rows: list[TrackRow], n_frames: int, fps: float = 10.0) -> list[tuple[int, int]]:
    """[start, end) frame ranges of the pauses: MIN_PAUSE_SECONDS or more where every
    WINDOW_SECONDS around has the ball still in MIN_STILL_SHARE of its seconds. A second
    without the ball is not still: a tracker stuck on something still while the real ball
    is not seen (Test03, 43:04-45:10) would otherwise look like a pause."""
    step = max(1, round(fps))
    balls = ball_per_frame(TrackSet(rows=ball_rows))
    still = []
    for s in range(n_frames // step):
        a, b = balls.get(s * step), balls.get((s + 1) * step)
        still.append(a is not None and b is not None and _dist(a.center, b.center) < STILL_STEP)
    half = WINDOW_SECONDS // 2
    dense = [sum(still[max(0, s - half):s + half]) >= MIN_STILL_SHARE * len(still[max(0, s - half):s + half]) for s in range(len(still))]
    out = []
    i = 0
    while i < len(dense):
        if not dense[i]:
            i += 1
            continue
        j = i
        while j < len(dense) and dense[j]:
            j += 1
        # From the first to the last still second of the run, and the still seconds right
        # next to it (the window thins the edges).
        inside = [s for s in range(i, j) if still[s]]
        if inside:
            a, b = inside[0], inside[-1] + 1
            while a > 0 and still[a - 1]:
                a -= 1
            while b < len(still) and still[b]:
                b += 1
            if b - a >= MIN_PAUSE_SECONDS:
                out.append((a * step, min(n_frames, b * step)))
        i = j
    return out


def apply(shots, players_rows: list[TrackRow], ball_rows: list[TrackRow], fps: float = 10.0):
    """Takes the pauses out of [shots] and the rows. Returns (shots, players_rows, ball_rows)."""
    frames = shots.trackset.frames
    found = find(ball_rows, len(frames), fps)
    if not found:
        return shots, players_rows, ball_rows
    inside = {f for a, b in found for f in range(a, b)}
    breaks = set(shots.breaks) | {b for _, b in found if b < len(frames)}
    pauses = [[frames[a].t_ms, frames[b].t_ms if b < len(frames) else frames[-1].t_ms + round(1000 / fps)] for a, b in found]
    shots = replace(shots, counted=[f for f in shots.counted if f.frame not in inside], breaks=breaks, pauses=pauses)
    return shots, [r for r in players_rows if r.frame not in inside], [r for r in ball_rows if r.frame not in inside]
