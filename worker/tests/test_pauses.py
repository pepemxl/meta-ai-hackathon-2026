from ppcoachtracker_worker import pauses
from ppcoachtracker_worker.shots import Shots
from ppcoachtracker_worker.types import FrameInfo, TrackRow, TrackSet


def ball(i, x, y=0.5):
    return TrackRow(i, i * 100, i * 3, 99, "ball", x - 0.01, y - 0.01, 0.02, 0.02, 0.9)


def moving(i):
    return ball(i, 0.1 + 0.02 * ((i // 10) % 30))  # 0.02 of screen per second: in play


def test_the_hydration_break_is_found_and_a_free_kick_is_not():
    rows = [moving(i) for i in range(600)]
    rows += [ball(i, 0.5) for i in range(600, 1600)]  # 100 s still: the break
    rows += [moving(i) for i in range(1600, 2200)]
    rows += [ball(i, 0.3) for i in range(2200, 2600)]  # 40 s still: a free kick, an injury
    rows += [moving(i) for i in range(2600, 3000)]
    # Measured second by second: the second in which the ball starts moving is play.
    assert pauses.find(rows, 3000) == [(600, 1590)]


def test_the_ball_carried_a_few_seconds_or_unseen_does_not_split_the_pause():
    rows = [ball(i, 0.5) for i in range(0, 500)]
    rows += [moving(i) for i in range(500, 550)]  # 5 s carried
    rows += [ball(i, 0.6) for i in range(550, 800)]
    # 8 s without the ball (a close-up) inside
    rows += [ball(i, 0.6) for i in range(880, 1100)]
    rows += [moving(i) for i in range(1100, 1500)]
    assert pauses.find(rows, 1500) == [(0, 1090)]
    # Without any ball nothing is a pause.
    assert pauses.find([], 1500) == []


def test_a_ball_seen_still_now_and_then_while_the_real_one_is_missing_is_not_a_pause():
    # Play with the ball mostly unseen; a still candidate 1 s in every 5 (Test03, 43:04).
    rows = [ball(i, 0.7) for i in range(1200) if (i // 10) % 5 == 0]
    rows += [moving(i) for i in range(1200, 1500)]
    assert pauses.find(rows, 1500) == []


def test_a_pause_leaves_the_metrics_and_breaks_continuity():
    frames = [FrameInfo(i, i * 100, i * 3) for i in range(2000)]
    balls = [moving(i) for i in range(500)] + [ball(i, 0.5) for i in range(500, 1500)] + [moving(i) for i in range(1500, 2000)]
    players = [TrackRow(i, i * 100, i * 3, 1, "player", 0.4, 0.4, 0.04, 0.12, team="A") for i in range(2000)]
    shots = Shots(TrackSet(rows=players + balls, frames=frames), frames, [], set(), True, 2000, 0)
    shots, players, balls = pauses.apply(shots, players, balls)
    assert len(shots.counted) == 1010 and all(not 500 <= f.frame < 1490 for f in shots.counted)
    assert 1490 in shots.breaks
    assert shots.pauses == [[50000, 149000]]
    assert all(not 500 <= r.frame < 1490 for r in players + balls)
