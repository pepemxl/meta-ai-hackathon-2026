"""Team metrics in SCREEN coordinates (no pitch calibration yet).

Foot point = bottom center of the box. Possession: the owner is the player
closest to the ball within OWNER_DISTANCE; runs shorter than MIN_RUN frames
are noise (between two runs of the same team they count for that team,
otherwise for nobody).
"""

from __future__ import annotations

import math
from collections import defaultdict

import numpy as np

from .types import FrameInfo, TrackRow, TrackSet

OWNER_DISTANCE = 0.06
PRESS_DISTANCE = 0.10
MIN_RUN = 5
MIN_SHAPE_PLAYERS = 3
HEATMAP = (12, 8)
MAX_STEP = 0.2


def _dist(a: tuple[float, float], b: tuple[float, float]) -> float:
    return math.hypot(a[0] - b[0], a[1] - b[1])


def ball_per_frame(trackset: TrackSet) -> dict[int, TrackRow]:
    best: dict[int, TrackRow] = {}
    for r in trackset.rows:
        if r.label == "ball" and (r.frame not in best or r.conf > best[r.frame].conf):
            best[r.frame] = r
    return best


def raw_owner(players: list[TrackRow], ball: TrackRow | None) -> tuple[str, int] | None:
    """(team, track id) of the player closest to the ball within OWNER_DISTANCE."""
    if ball is None:
        return None
    bc = ball.center
    near = [(min(_dist(p.foot, bc), _dist(p.center, bc)), p) for p in players if p.team]
    near = [t for t in near if t[0] <= OWNER_DISTANCE]
    if not near:
        return None
    _, p = min(near, key=lambda t: t[0])
    return p.team, p.track_id


def smooth_owner(raw: list[str | None]) -> list[str | None]:
    """Runs shorter than MIN_RUN: the surrounding team if both sides agree, else nobody."""
    runs: list[list] = []  # [team, start, length]
    for i, t in enumerate(raw):
        if runs and runs[-1][0] == t:
            runs[-1][2] += 1
        else:
            runs.append([t, i, 1])
    out = list(raw)
    for k, (team, start, length) in enumerate(runs):
        if team is None or length >= MIN_RUN:
            continue
        before = next((runs[j][0] for j in range(k - 1, -1, -1) if runs[j][0] is not None and runs[j][2] >= MIN_RUN), None)
        after = next((runs[j][0] for j in range(k + 1, len(runs)) if runs[j][0] is not None and runs[j][2] >= MIN_RUN), None)
        fill = before if before is not None and before == after else None
        for i in range(start, start + length):
            out[i] = fill
    # Gaps (no owner) between two long runs of the same team, shorter than MIN_RUN, keep that team.
    i = 0
    while i < len(out):
        if out[i] is None:
            j = i
            while j < len(out) and out[j] is None:
                j += 1
            if 0 < i and j < len(out) and j - i < MIN_RUN and out[i - 1] == out[j]:
                for k in range(i, j):
                    out[k] = out[j]
            i = j
        else:
            i += 1
    return out


def compute(trackset: TrackSet, fps: float = 10.0) -> dict:
    frames: list[FrameInfo] = trackset.frames
    n = len(frames)
    frame_ms = 1000.0 / fps if fps else 100.0
    by_frame = trackset.by_frame()
    balls = ball_per_frame(trackset)
    players_of = {f.frame: [r for r in by_frame.get(f.frame, []) if r.label == "player"] for f in frames}

    teams_out = {}
    for team in ("A", "B"):
        counts, shapes, tracks = [], [], set()
        heat = np.zeros((HEATMAP[1], HEATMAP[0]))
        for f in frames:
            ps = [p for p in players_of[f.frame] if p.team == team]
            counts.append(len(ps))
            for p in ps:
                tracks.add(p.track_id)
                fx, fy = p.foot
                heat[min(HEATMAP[1] - 1, max(0, int(fy * HEATMAP[1]))), min(HEATMAP[0] - 1, max(0, int(fx * HEATMAP[0])))] += 1
            if len(ps) >= MIN_SHAPE_PLAYERS:
                pts = np.array([p.foot for p in ps])
                shapes.append((pts[:, 0].mean(), pts[:, 1].mean(), pts[:, 0].std(), pts[:, 1].std()))
        total = heat.sum()
        teams_out[team] = {
            "players_per_frame": round(float(np.mean(counts)) if counts else 0.0, 2),
            "max_players": int(max(counts)) if counts else 0,
            "tracks": len(tracks),
            "shape": None if not shapes else {
                "centroid_x": round(float(np.mean([s[0] for s in shapes])), 4),
                "centroid_y": round(float(np.mean([s[1] for s in shapes])), 4),
                "spread_x": round(float(np.mean([s[2] for s in shapes])), 4),
                "spread_y": round(float(np.mean([s[3] for s in shapes])), 4),
                "frames": len(shapes),
            },
            "heatmap": {"cols": HEATMAP[0], "rows": HEATMAP[1], "values": (heat / total).round(4).tolist() if total else heat.tolist()},
        }

    raw = []
    for f in frames:
        o = raw_owner(players_of[f.frame], balls.get(f.frame))
        raw.append(o[0] if o else None)
    owner = smooth_owner(raw)

    owned = {t: sum(1 for o in owner if o == t) for t in ("A", "B")}
    total_owned = owned["A"] + owned["B"]
    longest = {"A": 0, "B": 0}
    run_team, run_len = None, 0
    for o in owner + [None]:
        if o == run_team and o is not None:
            run_len += 1
        else:
            if run_team in longest:
                longest[run_team] = max(longest[run_team], run_len)
            run_team, run_len = o, 1
    events = []
    last_team, last_idx = None, None
    for i, o in enumerate(owner):
        if o is None:
            continue
        if last_team is not None and o != last_team:
            events.append({"type": "possession_change", "frame": frames[i].frame, "t_ms": frames[i].t_ms, "from": last_team, "to": o})
        last_team, last_idx = o, i

    pressers = {"A": [], "B": []}
    for i, f in enumerate(frames):
        o, ball = owner[i], balls.get(f.frame)
        if o is None or ball is None:
            continue
        rival = "B" if o == "A" else "A"
        pressers[rival].append(sum(1 for p in players_of[f.frame] if p.team == rival and _dist(p.foot, ball.center) <= PRESS_DISTANCE))

    for t in ("A", "B"):
        teams_out[t]["possession"] = round(owned[t] / total_owned, 4) if total_owned else None
        teams_out[t]["possession_frames"] = owned[t]
        teams_out[t]["longest_possession_ms"] = int(longest[t] * frame_ms)
        teams_out[t]["pressers_per_frame"] = round(float(np.mean(pressers[t])), 2) if pressers[t] else None

    duration_ms = (frames[-1].t_ms - frames[0].t_ms + frame_ms) if frames else 0
    minutes = duration_ms / 60000 if duration_ms else 0

    timeline = []
    by_second: dict[int, list[int]] = defaultdict(list)
    for i, f in enumerate(frames):
        by_second[int((f.t_ms - frames[0].t_ms) // 1000)].append(i)
    for s in sorted(by_second):
        idx = by_second[s]
        owners = [owner[i] for i in idx if owner[i]]
        timeline.append({
            "t_ms": frames[idx[0]].t_ms,
            "players_a": round(float(np.mean([sum(1 for p in players_of[frames[i].frame] if p.team == "A") for i in idx])), 1),
            "players_b": round(float(np.mean([sum(1 for p in players_of[frames[i].frame] if p.team == "B") for i in idx])), 1),
            "ball": any(frames[i].frame in balls for i in idx),
            "owner": max(set(owners), key=owners.count) if owners else None,
        })

    tracks_out = []
    for tid, rows in sorted(trackset.by_track().items()):
        rows = sorted(rows, key=lambda r: r.frame)
        dist = 0.0
        for a, b in zip(rows, rows[1:]):
            step = _dist(a.foot, b.foot)
            if step <= MAX_STEP:
                dist += step
        tracks_out.append({
            "id": tid,
            "label": rows[0].label,
            "team": rows[0].team,
            "frames": len(rows),
            "first_ms": rows[0].t_ms,
            "last_ms": rows[-1].t_ms,
            "distance": round(dist, 4),
            "mean_x": round(float(np.mean([r.foot[0] for r in rows])), 4),
            "mean_y": round(float(np.mean([r.foot[1] for r in rows])), 4),
        })

    players_total = [len(players_of[f.frame]) for f in frames]
    unassigned = [sum(1 for p in players_of[f.frame] if not p.team) for f in frames]
    return {
        "frames": n,
        "teams": teams_out,
        "unassigned_per_frame": round(float(np.mean(unassigned)), 2) if n else 0.0,
        "players_per_frame": round(float(np.mean(players_total)), 2) if n else 0.0,
        "ball": {"seen_frames": len(balls), "seen_ratio": round(len(balls) / n, 4) if n else 0.0},
        "possession_changes": len(events),
        "possession_changes_per_minute": round(len(events) / minutes, 2) if minutes else 0.0,
        "timeline": timeline,
        "tracks": tracks_out,
        "events": events,
        "duration_ms": int(duration_ms),
    }
