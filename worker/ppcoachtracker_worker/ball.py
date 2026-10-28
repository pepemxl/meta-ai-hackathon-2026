"""The ball (docs/prompts/11-worker-calidad.md).

SAM may return several "soccer ball" candidates per frame (an advertisement,
a white boot, the real ball). One trajectory per candidate, each with its own
constant-velocity Kalman filter; the one that lasts and passes by the players'
feet is chosen. A Kalman that follows the most confident detection sticks to
an advertisement detected in every frame (it always fits its prediction):
hence one per candidate.

- A SAM ball without grass around (a third of grass in a square 4 times its
  size) is dropped before tracking.
- Gaps of up to 5 frames are filled only INSIDE one trajectory (interp=1):
  filling between two different trajectories invents a pass.
- Nothing is carried across a break (cut or discarded stretch).
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np

from .types import TrackRow

GATE = 0.06  # normalized distance from the prediction to join a trajectory
MAX_MISSES = 5
MIN_FRAMES = 5
NEAR_FEET = 0.08
MIN_GRASS_AROUND = 1 / 3


class Kalman:
    """Constant velocity in 2-D (x, y, vx, vy), one step per frame."""

    def __init__(self, x: float, y: float):
        self.s = np.array([x, y, 0.0, 0.0])
        self.P = np.diag([1e-4, 1e-4, 1e-2, 1e-2])
        self.F = np.array([[1, 0, 1, 0], [0, 1, 0, 1], [0, 0, 1, 0], [0, 0, 0, 1]], dtype=float)
        self.H = np.array([[1, 0, 0, 0], [0, 1, 0, 0]], dtype=float)
        self.Q = np.diag([1e-5, 1e-5, 1e-4, 1e-4])
        self.R = np.diag([2e-5, 2e-5])

    def predict(self) -> tuple[float, float]:
        self.s = self.F @ self.s
        self.P = self.F @ self.P @ self.F.T + self.Q
        return float(self.s[0]), float(self.s[1])

    def update(self, x: float, y: float) -> None:
        z = np.array([x, y])
        S = self.H @ self.P @ self.H.T + self.R
        K = self.P @ self.H.T @ np.linalg.inv(S)
        self.s = self.s + K @ (z - self.H @ self.s)
        self.P = (np.eye(4) - K @ self.H) @ self.P


@dataclass
class Trajectory:
    kf: Kalman
    rows: dict[int, TrackRow] = field(default_factory=dict)  # frame → detection
    misses: int = 0
    near_feet: int = 0

    @property
    def score(self) -> float:
        return len(self.rows) + 3 * self.near_feet


def grass_around(grass_ratio) -> bool:
    return grass_ratio is None or grass_ratio >= MIN_GRASS_AROUND


def build(frames: list[int], candidates: dict[int, list[TrackRow]], feet: dict[int, list[tuple[float, float]]], breaks: set[int]) -> list[Trajectory]:
    """Trajectories over [frames] (in order); a break closes every open trajectory."""
    done: list[Trajectory] = []
    open_: list[Trajectory] = []
    for f in frames:
        if f in breaks:
            done += open_
            open_ = []
        preds = [t.kf.predict() for t in open_]
        cands = candidates.get(f, [])
        used: set[int] = set()
        # Greedy: the closest (trajectory, candidate) pairs inside the gate.
        pairs = sorted(
            (math.hypot(c.center[0] - p[0], c.center[1] - p[1]), ti, ci)
            for ti, p in enumerate(preds) for ci, c in enumerate(cands)
        )
        matched_t: set[int] = set()
        for d, ti, ci in pairs:
            if d > GATE or ti in matched_t or ci in used:
                continue
            matched_t.add(ti)
            used.add(ci)
            t = open_[ti]
            c = cands[ci]
            t.kf.update(*c.center)
            t.rows[f] = c
            t.misses = 0
            if any(math.hypot(c.center[0] - fx, c.center[1] - fy) <= NEAR_FEET for fx, fy in feet.get(f, [])):
                t.near_feet += 1
        for ti, t in enumerate(open_):
            if ti not in matched_t:
                t.misses += 1
        still = []
        for t in open_:
            (done if t.misses > MAX_MISSES else still).append(t)
        open_ = still
        for ci, c in enumerate(cands):
            if ci not in used:
                t = Trajectory(Kalman(*c.center))
                t.rows[f] = c
                if any(math.hypot(c.center[0] - fx, c.center[1] - fy) <= NEAR_FEET for fx, fy in feet.get(f, [])):
                    t.near_feet += 1
                open_.append(t)
    return done + open_


def select(ball_rows: list[TrackRow], player_rows: list[TrackRow], frames: list, breaks: set[int], grass: dict[tuple[int, int], float] | None = None) -> tuple[list[TrackRow], dict]:
    """The chosen ball, one row per frame at most (interp=True where filled). Returns (rows, report)."""
    grass = grass or {}
    kept = [r for r in ball_rows if grass_around(grass.get((r.frame, r.track_id)))]
    candidates: dict[int, list[TrackRow]] = {}
    for r in kept:
        candidates.setdefault(r.frame, []).append(r)
    feet: dict[int, list[tuple[float, float]]] = {}
    for r in player_rows:
        feet.setdefault(r.frame, []).append(r.foot)
    order = [f.frame for f in frames]
    trajectories = build(order, candidates, feet, breaks)
    good = [t for t in trajectories if len(t.rows) >= MIN_FRAMES and t.near_feet > 0]

    info_of = {fi.frame: fi for fi in frames}
    chosen: dict[int, TrackRow] = {}
    for t in sorted(good, key=lambda t: -t.score):
        for f, r in t.rows.items():
            chosen.setdefault(f, r)
        # Fill short gaps inside this trajectory only.
        fs = sorted(t.rows)
        for a, b in zip(fs, fs[1:]):
            if 1 < b - a <= MAX_MISSES + 1:
                ra, rb = t.rows[a], t.rows[b]
                for k in range(a + 1, b):
                    if k in chosen:
                        continue
                    w = (k - a) / (b - a)
                    info = info_of.get(k)
                    if info is None:
                        continue
                    chosen[k] = ra.with_(frame=k, t_ms=info.t_ms, source_frame=info.source_frame,
                                         x=ra.x + w * (rb.x - ra.x), y=ra.y + w * (rb.y - ra.y), conf=0.0, interp=True)
    rows = [r.with_(track_id=0) for _, r in sorted(chosen.items())]
    report = {
        "candidates": len(ball_rows),
        "dropped_no_grass": len(ball_rows) - len(kept),
        "trajectories": len(trajectories),
        "kept_trajectories": len(good),
        "interpolated": sum(1 for r in rows if r.interp),
    }
    return rows, report
