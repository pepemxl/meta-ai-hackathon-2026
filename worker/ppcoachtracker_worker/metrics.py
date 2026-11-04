"""Team metrics in SCREEN coordinates, plus the ones in meters when there is a pitch.

Foot point = bottom center of the box. Possession with hysteresis: a team gains
the ball after holding it 1 s (within 0.06 of screen, or 2 m when the frame has
meters; those frames count for it from the start), loses it when nobody of the
team is within 0.09 (3 m) and keeps it up to 2 s without seeing the ball; a ball
faster than a run (a pass, a shot) gives it to nobody new. Runs with fewer than
MIN_RUN frames with ball are noise (between two runs of the same team they count
for that team, otherwise for nobody); a gap of up to 10 s with no owner between two
runs of the same team is its possession (its passes); nothing joins across a break (cut or
discarded stretch).
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


RELEASE_DISTANCE = 0.09
# The same in meters, on the frames with a homography (0.06 of a wide shot is ~5 m).
OWNER_DISTANCE_M = 2.0
RELEASE_DISTANCE_M = 3.0
CONFIRM_FRAMES = 10  # frames with ball (1 s at 10 fps) a team must hold it to gain it
# A gap with no owner between two runs of the same team (its passes, the ball rolling
# between teammates, a throw-in) is still its possession, up to 10 s at 10 fps; a longer
# one is not seen play (in Test03 up to 59 s), and filling it moved the share 7 points.
SAME_TEAM_GAP = 100
# Faster than this the ball is in a pass or a shot: whoever it passes by does not get it.
PASS_SPEED_M = 9.0  # m/s; a player running with the ball stays under ~8
PASS_SPEED = 0.11  # screen per second, the same in a wide shot
SPEED_SPAN_MS = (200, 500)  # the earlier ball the speed is measured against
MAX_UNSEEN_MS = 2000


def _team_distance(players: list[TrackRow], team: str, ball: TrackRow) -> float:
    if ball.mx is not None:
        d = [math.hypot(p.mx - ball.mx, p.my - ball.my) for p in players if p.team == team and p.mx is not None]
    else:
        bc = ball.center
        d = [min(_dist(p.foot, bc), _dist(p.center, bc)) for p in players if p.team == team]
    return min(d) if d else math.inf


def _ball_speed(ball: TrackRow, t_ms: int, recent: list[tuple[int, TrackRow]]) -> float | None:
    """m/s against a ball seen 0.2 to 0.5 s before (screen per second without meters on both)."""
    for t0, b0 in reversed(recent):
        dt = t_ms - t0
        if dt < SPEED_SPAN_MS[0]:
            continue
        if dt > SPEED_SPAN_MS[1]:
            return None
        if ball.mx is not None and b0.mx is not None:
            return math.hypot(ball.mx - b0.mx, ball.my - b0.my) * 1000 / dt
        if ball.mx is None and b0.mx is None:
            return _dist(ball.center, b0.center) * 1000 / dt
        return None
    return None


def raw_owner(frames: list[FrameInfo], players_of: dict[int, list[TrackRow]], balls: dict[int, TrackRow], breaks: set[int] | None = None) -> list[str | None]:
    """Team in possession per frame, with hysteresis.

    A team is a candidate on a frame when one of its players is within
    OWNER_DISTANCE (OWNER_DISTANCE_M with meters) and closer than the other team.
    It gains the ball after CONFIRM_FRAMES candidate frames in a row (frames
    without ball or with the ball in a pass do not break the row), and those
    frames go to it; it keeps the ball until nobody of the team is within
    RELEASE_DISTANCE. A touch by the other team shorter than that changes
    nothing. Without seeing the ball the possession lasts up to 2 s. A break
    (cut, discarded stretch) resets it.
    """
    breaks = breaks or set()
    out: list[str | None] = []
    state: str | None = None
    last_seen: int | None = None
    pending: str | None = None  # the team holding it without having gained it yet
    pending_from, pending_n = 0, 0
    recent: list[tuple[int, TrackRow]] = []
    for i, f in enumerate(frames):
        if f.frame in breaks:
            state, last_seen, pending, recent = None, None, None, []
        ball = balls.get(f.frame)
        if ball is None:
            if last_seen is not None and f.t_ms - last_seen > MAX_UNSEEN_MS:
                state, pending = None, None
            out.append(state)
            continue
        last_seen = f.t_ms
        speed = _ball_speed(ball, f.t_ms, recent)
        recent = [(t, b) for t, b in recent if f.t_ms - t <= SPEED_SPAN_MS[1]] + [(f.t_ms, ball)]
        if speed is not None and speed > (PASS_SPEED_M if ball.mx is not None else PASS_SPEED):
            out.append(state)
            continue
        meters = ball.mx is not None
        owner_d, release_d = (OWNER_DISTANCE_M, RELEASE_DISTANCE_M) if meters else (OWNER_DISTANCE, RELEASE_DISTANCE)
        players = players_of.get(f.frame, [])
        d = {t: _team_distance(players, t, ball) for t in ("A", "B")}
        if state is not None and d[state] > release_d:
            state = None
        best = min(("A", "B"), key=lambda t: d[t])
        candidate = best if d[best] <= owner_d and d[best] < d["B" if best == "A" else "A"] else None
        if candidate is None or candidate == state:
            pending = None
        else:
            if candidate != pending:
                pending, pending_from, pending_n = candidate, i, 0
            pending_n += 1
            if pending_n >= CONFIRM_FRAMES:
                state, pending = candidate, None
                out[pending_from:] = [state] * (i - pending_from)
        out.append(state)
    return out


def smooth_owner(raw: list[str | None], has_ball: list[bool] | None = None, breaks_at: set[int] | None = None) -> list[str | None]:
    """Runs with fewer than MIN_RUN frames WITH ball are noise: the surrounding team if both
    sides agree, else nobody. Nothing joins across a break (indices in [breaks_at])."""
    n = len(raw)
    has_ball = has_ball if has_ball is not None else [True] * n
    breaks_at = breaks_at or set()
    runs: list[list] = []  # [team, start, end)
    for i, t in enumerate(raw):
        if runs and runs[-1][0] == t and i not in breaks_at:
            runs[-1][2] = i + 1
        else:
            runs.append([t, i, i + 1])

    def long_run(k):
        t, a, b = runs[k]
        return t is not None and sum(has_ball[a:b]) >= MIN_RUN

    def crosses_break(a, b):
        return any(a < i <= b for i in breaks_at)

    out = list(raw)
    for k, (team, a, b) in enumerate(runs):
        if team is None or long_run(k):
            continue
        before = next((j for j in range(k - 1, -1, -1) if long_run(j)), None)
        after = next((j for j in range(k + 1, len(runs)) if long_run(j)), None)
        fill = None
        if before is not None and after is not None and runs[before][0] == runs[after][0] and not crosses_break(runs[before][2] - 1, runs[after][1]):
            fill = runs[before][0]
        for i in range(a, b):
            out[i] = fill
    # A gap (no owner) between two runs of the same team keeps that team: the same possession
    # goes on through its passes. Never across a break.
    i = 0
    while i < n:
        if out[i] is None:
            j = i
            while j < n and out[j] is None:
                j += 1
            if 0 < i and j < n and j - i <= SAME_TEAM_GAP and out[i - 1] == out[j] and not crosses_break(i - 1, j):
                for k in range(i, j):
                    out[k] = out[j]
            i = j
        else:
            i += 1
    return out


def compute(trackset: TrackSet, fps: float = 10.0, breaks: set[int] | None = None) -> dict:
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

    breaks = breaks or set()
    raw = raw_owner(frames, players_of, balls, breaks)
    breaks_at = {i for i, f in enumerate(frames) if f.frame in breaks}
    owner = smooth_owner(raw, [f.frame in balls for f in frames], breaks_at)

    owned = {t: sum(1 for o in owner if o == t) for t in ("A", "B")}
    total_owned = owned["A"] + owned["B"]
    longest = {"A": 0, "B": 0}
    run_team, run_len = None, 0
    for i, o in enumerate(owner + [None]):
        if o == run_team and o is not None and i not in breaks_at:
            run_len += 1
        else:
            if run_team in longest:
                longest[run_team] = max(longest[run_team], run_len)
            run_team, run_len = o, 1
    events = []
    last_team, last_idx = None, None
    for i, o in enumerate(owner):
        if i in breaks_at:
            last_team = None  # no possession change across a break
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


# ── metrics in meters (docs/prompts/13-cancha-en-metros.md, step 4) ─────────

MIN_PITCH_SECONDS = 10.0
MAX_SPEED = 11.0  # m/s: a faster step is a track switch, not a sprint
OUTSIDE_MARGIN_M = 2.0


def pitch_metrics(trackset: TrackSet, frames_with_h: set[int], length: float, width: float, approximate: bool, fps: float = 10.0) -> dict | None:
    """Metrics in meters, only on the frames with a homography and only with 10 s or more of them.

    Speeds come from the MEAN POSITION OF EACH SECOND (frame to frame, the box
    jitter adds meters nobody ran). Feet more than 2 m outside the lines do not count.
    """
    used = [f for f in trackset.frames if f.frame in frames_with_h]
    seconds_total = len(used) / fps if fps else 0.0
    if seconds_total < MIN_PITCH_SECONDS:
        return None
    t0 = trackset.frames[0].t_ms if trackset.frames else 0
    hx, hy = length / 2, width / 2
    rows = [r for r in trackset.rows if r.label == "player" and r.mx is not None and r.frame in frames_with_h
            and abs(r.mx) <= hx + OUTSIDE_MARGIN_M and abs(r.my) <= hy + OUTSIDE_MARGIN_M]

    per_second: dict[int, dict[int, list[tuple[float, float]]]] = defaultdict(lambda: defaultdict(list))
    team_of: dict[int, str] = {}
    for r in rows:
        per_second[r.track_id][int((r.t_ms - t0) // 1000)].append((r.mx, r.my))
        team_of.setdefault(r.track_id, r.team)

    dropped = 0
    tracks_out = []
    speeds_by_team: dict[str, list[float]] = {"A": [], "B": []}
    dist_by_team = {"A": 0.0, "B": 0.0}
    secs_by_team = {"A": 0, "B": 0}
    for tid, secs in sorted(per_second.items()):
        means = {s: (float(np.mean([p[0] for p in pts])), float(np.mean([p[1] for p in pts]))) for s, pts in secs.items()}
        dist, top = 0.0, 0.0
        for s in sorted(means):
            if s + 1 not in means:
                continue
            step = math.hypot(means[s + 1][0] - means[s][0], means[s + 1][1] - means[s][1])
            if step > MAX_SPEED:
                dropped += 1
                continue
            dist += step
            top = max(top, step)
            if team_of[tid] in speeds_by_team:
                speeds_by_team[team_of[tid]].append(step)
        team = team_of[tid]
        if team in dist_by_team:
            dist_by_team[team] += dist
            secs_by_team[team] += len(means)
        tracks_out.append({"id": tid, "team": team, "distance_m": round(dist, 2), "seconds": len(means), "top_speed": round(top, 2)})

    by_frame: dict[int, list[TrackRow]] = defaultdict(list)
    for r in rows:
        by_frame[r.frame].append(r)
    teams = {}
    for team in ("A", "B"):
        heat = np.zeros((HEATMAP[1], HEATMAP[0]))
        shapes = []
        for f in used:
            ps = [r for r in by_frame.get(f.frame, []) if r.team == team]
            for r in ps:
                col = min(HEATMAP[0] - 1, max(0, int((r.mx + hx) / length * HEATMAP[0])))
                row = min(HEATMAP[1] - 1, max(0, int((hy - r.my) / width * HEATMAP[1])))  # far band on top
                heat[row, col] += 1
            if len(ps) >= MIN_SHAPE_PLAYERS:
                xs = [r.mx for r in ps]
                ys = [r.my for r in ps]
                shapes.append((np.mean(xs), np.mean(ys), max(xs) - min(xs), max(ys) - min(ys)))
        total = heat.sum()
        sp = speeds_by_team[team]
        teams[team] = {
            "player_seconds": secs_by_team[team],
            "meters_per_minute": round(dist_by_team[team] / secs_by_team[team] * 60, 1) if secs_by_team[team] else None,
            "speed_p95": round(float(np.percentile(sp, 95)), 2) if sp else None,
            "shape": None if not shapes else {
                "cx": round(float(np.mean([s[0] for s in shapes])), 2),
                "cy": round(float(np.mean([s[1] for s in shapes])), 2),
                "length": round(float(np.mean([s[2] for s in shapes])), 2),
                "width": round(float(np.mean([s[3] for s in shapes])), 2),
            },
            "heatmap": {"cols": HEATMAP[0], "rows": HEATMAP[1], "values": (heat / total).round(4).tolist() if total else heat.tolist()},
        }
    return {
        "length_m": length,
        "width_m": width,
        "approximate": approximate,
        "seconds": round(seconds_total, 1),
        "frames_with_homography": round(len(used) / max(1, len(trackset.frames)), 4),
        "dropped_steps": dropped,
        "teams": teams,
        "tracks": tracks_out,
    }
