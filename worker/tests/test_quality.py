import numpy as np

from ppcoachtracker_worker import ball, cuts, metrics, shots, teams
from ppcoachtracker_worker.colors import JERSEY_RGB, rgb_to_lab
from ppcoachtracker_worker.pitch import PitchMask
from ppcoachtracker_worker.types import FrameInfo, TrackRow, TrackSet

FULL = PitchMask(0.8, (0, 0.2, 1, 0.2, 1, 1, 0, 1))


def frames(n):
    return [FrameInfo(i, i * 100, i * 3) for i in range(n)]


def player(i, tid, x, y, team="A", h=0.12):
    return TrackRow(i, i * 100, i * 3, tid, "player", x, y, 0.04, h, team=team)


def ball_row(i, x, y, tid=99, conf=0.9):
    return TrackRow(i, i * 100, i * 3, tid, "ball", x - 0.01, y - 0.01, 0.02, 0.02, conf)


def test_a_cut_is_seen_and_a_pan_is_not():
    rng = np.random.default_rng(0)
    big = (rng.integers(0, 255, (360, 1200, 3))).astype(np.uint8)
    big[..., 1] = np.clip(big[..., 1].astype(int) + 80, 0, 255)
    a, b = big[:, 0:640], big[:, 30:670]  # a pan of 30 px
    other = np.zeros((360, 640, 3), np.uint8)
    other[:] = (200, 60, 40)
    assert not cuts.is_cut(cuts.histogram(a), cuts.histogram(b))
    assert cuts.is_cut(cuts.histogram(b), cuts.histogram(other))
    assert cuts.cut_frames([cuts.histogram(a), cuts.histogram(b), cuts.histogram(other)]) == {2}


def test_a_short_discarded_run_is_refilled():
    n = 40
    rows = [player(i, 1, 0.4, 0.5) for i in range(n)]
    masks = [FULL] * n
    masks[20:23] = [PitchMask(0.1, ())] * 3  # 3 frames without pitch inside a long shot
    s = shots.apply(TrackSet(rows=rows, frames=frames(n)), masks)
    assert len(s.counted) == n and s.discarded == []
    masks[20:30] = [PitchMask(0.1, ())] * 10  # 10 frames: a real stretch
    s = shots.apply(TrackSet(rows=rows, frames=frames(n)), masks)
    assert s.discarded == [[2000, 3000]] and len(s.counted) == 30


def test_a_person_in_front_of_the_camera_with_players_behind_still_counts():
    n = 10
    front = [TrackRow(i, i * 100, i * 3, 50, "player", 0.4, 0.3, 0.2, 0.6) for i in range(n)]
    behind = [player(i, 1 + k, 0.1 + 0.2 * k, 0.5, h=0.08) for i in range(n) for k in range(3)]
    s = shots.apply(TrackSet(rows=front + behind, frames=frames(n)), [FULL] * n)
    assert len(s.counted) == n and s.closeups == 0
    s2 = shots.apply(TrackSet(rows=front, frames=frames(n)), [FULL] * n)  # a close-up alone
    assert s2.closeups == n and len(s2.counted) == 0


def test_a_replay_is_discarded_and_possession_does_not_join_across_it():
    # 0-19 team A has the ball, 20-39 a replay (close-up after a cut), 40-59 team A again.
    n = 60
    rows = []
    for i in range(n):
        if 20 <= i < 40:
            rows.append(TrackRow(i, i * 100, i * 3, 50, "player", 0.3, 0.2, 0.3, 0.7))
        else:
            rows += [player(i, 1, 0.40, 0.4), player(i, 2, 0.8, 0.4, team="B"), ball_row(i, 0.42, 0.53)]
    s = shots.apply(TrackSet(rows=rows, frames=frames(n)), [FULL] * n, cuts={20, 40})
    assert s.discarded == [[2000, 4000]]
    assert 40 in s.breaks
    # The same player after the replay is another track: tracks split at the break.
    ids_after = {r.track_id for r in s.trackset.rows if r.frame >= 40 and r.label == "player" and r.team == "A"}
    assert 1 not in ids_after
    m = metrics.compute(TrackSet(rows=s.trackset.rows, frames=s.counted), breaks=s.breaks)
    assert m["teams"]["A"]["longest_possession_ms"] == 2000  # not 4000: nothing joins across the replay
    assert m["possession_changes"] == 0


def test_short_ball_gaps_are_filled_inside_a_trajectory_and_long_ones_are_not():
    fr = frames(40)
    feet = [player(i, 1, 0.3 + 0.005 * i, 0.4) for i in range(40)]
    seen = [i for i in range(40) if not (10 <= i < 13) and not (20 <= i < 30)]
    balls = [ball_row(i, 0.32 + 0.005 * i, 0.52) for i in seen]
    rows, rep = ball.select(balls, feet, fr, set())
    got = {r.frame: r for r in rows}
    assert all(got[k].interp for k in (10, 11, 12))  # 3 missing frames: filled
    assert all(k not in got for k in range(20, 30))  # 10 missing frames: not invented
    assert rep["interpolated"] == 3


def test_nothing_is_carried_across_a_discarded_stretch():
    fr = [f for f in frames(30) if not (10 <= f.frame < 13)]  # 10-12 discarded
    feet = [player(f.frame, 1, 0.3, 0.4) for f in fr]
    balls = [ball_row(f.frame, 0.32, 0.52) for f in fr]
    rows, _ = ball.select(balls, feet, fr, breaks={13})
    assert not any(r.interp for r in rows)
    assert all(r.frame not in (10, 11, 12) for r in rows)


def test_an_isolated_false_ball_is_dropped():
    fr = frames(30)
    feet = [player(i, 1, 0.3, 0.4) for i in range(30)]
    balls = [ball_row(i, 0.32, 0.52) for i in range(30)] + [ball_row(15, 0.9, 0.1, tid=98, conf=0.99)]
    rows, rep = ball.select(balls, feet, fr, set())
    assert len(rows) == 30 and all(abs(r.center[0] - 0.32) < 1e-9 for r in rows)
    assert rep["trajectories"] == 2 and rep["kept_trajectories"] == 1


def test_an_advert_more_confident_than_the_real_ball_is_not_chosen():
    fr = frames(40)
    feet = [player(i, 1, 0.2 + 0.01 * i, 0.5) for i in range(40)]
    real = [ball_row(i, 0.22 + 0.01 * i, 0.62, conf=0.6) for i in range(40)]
    advert = [ball_row(i, 0.5, 0.08, tid=97, conf=0.98) for i in range(40)]  # static, far from any feet
    rows, _ = ball.select(real + advert, feet, fr, set())
    assert all(abs(r.center[1] - 0.62) < 1e-9 for r in rows)
    # And a ball candidate without grass around is dropped before tracking.
    grass = {(r.frame, r.track_id): (0.0 if r.track_id == 99 else 0.9) for r in real + advert}
    rows2, rep2 = ball.select(real + advert, feet, fr, set(), grass)
    assert rep2["dropped_no_grass"] == 40 and rows2 == []


def test_possession_hysteresis():
    fr = frames(42)
    players_of = {f.frame: [player(f.frame, 1, 0.40, 0.4), player(f.frame, 2, 0.6, 0.4, team="B")] for f in fr}
    # The ball walks away from A's player (0.1 of screen per second, under a pass) to B's.
    xs = [0.42] * 12 + [0.43, 0.44, 0.45, 0.46, 0.47, 0.48, 0.49] + [0.49] * 4 + [0.50, 0.51, 0.52, 0.53, 0.54, 0.55, 0.56] + [0.57] * 12
    balls = {f.frame: ball_row(f.frame, xs[f.frame], 0.53) for f in fr}
    raw = metrics.raw_owner(fr, players_of, balls)
    # A gains it after 1 s at its feet, and that second is A's from the start.
    assert raw[:12] == ["A"] * 12
    # At x=0.49 A is 0.07 away (outside 0.06 but inside 0.09): still A.
    assert raw[18:23] == ["A"] * 5
    assert raw[24] is None  # 0.09 from A: released, nobody near
    assert raw[25:30] == [None] * 5
    assert raw[30:] == ["B"] * 12  # B within 0.06 for 1 s: B from its first frame
    # Without seeing the ball, possession lasts up to 2 s, then nobody.
    fr2 = frames(35)
    b2 = {i: ball_row(i, 0.42, 0.53) for i in range(10)}
    raw2 = metrics.raw_owner(fr2, {f.frame: players_of[0] for f in fr2}, b2)
    assert raw2[29] == "A" and raw2[30] is None


def test_a_touch_shorter_than_a_second_does_not_change_possession():
    fr = frames(40)
    near = set(range(15, 20)) | set(range(25, 40))  # B's player on the ball: 0.5 s, then for good
    players_of = {f.frame: [player(f.frame, 1, 0.40, 0.4),
                            player(f.frame, 2, 0.40, 0.41, team="B") if f.frame in near else player(f.frame, 2, 0.6, 0.4, team="B")] for f in fr}
    balls = {f.frame: ball_row(f.frame, 0.42, 0.53) for f in fr}
    raw = metrics.raw_owner(fr, players_of, balls)
    assert raw[:25] == ["A"] * 25
    assert raw[25:] == ["B"] * 15


def test_possession_in_meters_and_not_to_whoever_a_pass_goes_by():
    fr = frames(60)

    def at(i, tid, team, x, mx):
        return TrackRow(i, i * 100, i * 3, tid, "player", x, 0.4, 0.04, 0.12, team=team, mx=mx, my=0.0)

    # On screen everyone is far from the ball; in meters A's player is 1.5 m away.
    players_of = {f.frame: [at(f.frame, 1, "A", 0.1, 0.0), at(f.frame, 2, "B", 0.8, 20.0)] for f in fr}
    # Then a pass at 15 m/s that goes by B's player and stops 10 m past him.
    mxs = [1.5] * 12 + [min(30.0, 1.5 + 1.5 * k) for k in range(1, 49)]
    balls = {i: TrackRow(i, i * 100, i * 3, 99, "ball", 0.49, 0.49, 0.02, 0.02, 0.9, mx=m, my=0.0) for i, m in enumerate(mxs)}
    raw = metrics.raw_owner(fr, players_of, balls)
    assert raw[:12] == ["A"] * 12
    assert raw[14] == "A"  # in flight: still A's
    assert "B" not in raw
    assert raw[-1] is None


def test_referee_in_yellow_with_a_team_in_white_has_no_team():
    labs = {
        ("w", 1): rgb_to_lab(np.array(JERSEY_RGB["white"])),
        ("w", 2): rgb_to_lab(np.array((235, 235, 235))),
        ("w", 3): rgb_to_lab(np.array(JERSEY_RGB["white"])),
        ("b", 1): rgb_to_lab(np.array(JERSEY_RGB["blue"])),
        ("b", 2): rgb_to_lab(np.array(JERSEY_RGB["blue"])),
        ("w", 9): rgb_to_lab(np.array(JERSEY_RGB["yellow"])),  # the referee, found by "player in white jersey"
    }
    assert teams.third_group(labs, "white", "blue") == {("w", 9)}
    assert teams.team_for_color(labs[("w", 9)], "white", "blue") == ""
    # Declared colors wrong (most tracks far from both): nothing is taken away.
    assert teams.third_group({k: rgb_to_lab(np.array(JERSEY_RGB["green"])) for k in labs}, "white", "blue") == set()


def test_with_the_pitch_in_meters_a_ball_beyond_the_lines_is_dropped():
    # Test03: 22 of SAM's 23 wrong balls in 40 frames were on the ad boards, the stands or the bench.
    def candidate(frame):
        return TrackRow(frame, frame * 100, frame, 1, "ball", 0.5, 0.5, 0.01, 0.01)

    meters = {0: (10.0, 5.0), 1: (54.0, 0.0), 2: (0.0, 37.5), 3: None}  # foot → meters, by frame
    rows = [candidate(f) for f in range(4)]
    kept, dropped = ball.on_pitch(rows, lambda f, foot: meters[f], 105.0, 68.0)
    assert [r.frame for r in kept] == [0, 1, 3]  # 1.5 m beyond the goal line stays; 3.5 m beyond the touchline goes
    assert dropped == 1
