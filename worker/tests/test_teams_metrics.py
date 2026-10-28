import numpy as np
import pytest

from fakes import BGR, render
from ppcoachtracker_worker.colors import JERSEY_RGB, rgb_to_lab
from ppcoachtracker_worker.metrics import compute, smooth_owner
from ppcoachtracker_worker.teams import team_for_color, torso_lab, vote
from ppcoachtracker_worker.types import FrameInfo, TrackRow, TrackSet


def test_torso_color_ignores_grass_and_finds_the_team():
    img = render(0, 320, 180)
    red = torso_lab(img, (0.27 - 0.02, 0.43, 0.06 + 0.04, 0.18))  # box wider than the player: grass around
    assert team_for_color(red, "red", "blue") == "A"
    assert team_for_color(red, "blue", "red") == "B"  # colors declared the other way around


def test_referee_far_from_both_colors_has_no_team():
    black = rgb_to_lab(np.array(JERSEY_RGB["black"]))
    assert team_for_color(black, "red", "blue") == ""
    assert team_for_color(None, "red", "blue") == ""
    # Two samples of red and one unreadable → still red.
    red = rgb_to_lab(np.array(BGR["red"][::-1]))
    assert vote([red, None, red], "red", "blue") == "A"


def _frames(n):
    return [FrameInfo(i, i * 100, i * 3) for i in range(n)]


def test_smoothing_removes_short_blips():
    raw = ["A"] * 6 + ["B"] * 2 + ["A"] * 6 + [None] * 2 + ["A"] * 5 + ["B"] * 3 + [None] * 4 + ["B"] * 6
    out = smooth_owner(raw)
    assert out[6:8] == ["A", "A"]  # a B blip between two A runs → A
    assert out[14:16] == ["A", "A"]  # a short gap between two A runs → A
    assert out[21:24] == [None, None, None]  # a B blip between A and a later B run → nobody
    assert out[-6:] == ["B"] * 6


def test_possession_pressers_and_changes():
    frames = _frames(40)
    rows = []
    for f in frames:
        i = f.frame
        rows += [
            TrackRow(i, f.t_ms, f.source_frame, 1, "player", 0.20, 0.40, 0.05, 0.15, team="A"),
            TrackRow(i, f.t_ms, f.source_frame, 2, "player", 0.30, 0.40, 0.05, 0.15, team="A"),
            TrackRow(i, f.t_ms, f.source_frame, 3, "player", 0.40, 0.40, 0.05, 0.15, team="A"),
            TrackRow(i, f.t_ms, f.source_frame, 4, "player", 0.70, 0.40, 0.05, 0.15, team="B"),
            TrackRow(i, f.t_ms, f.source_frame, 5, "player", 0.27, 0.42, 0.05, 0.15, team="B"),  # presses A1
        ]
        # Ball at A1's feet for 20 frames, then at B4's; a 2-frame blip near B4 early on.
        owner_x = 0.225 if i < 20 and i not in (5, 6) else 0.725
        rows.append(TrackRow(i, f.t_ms, f.source_frame, 9, "ball", owner_x - 0.01, 0.54, 0.02, 0.02))
    m = compute(TrackSet(rows=rows, frames=frames), fps=10)
    assert m["teams"]["A"]["possession"] == pytest.approx(0.5)
    assert m["possession_changes"] == 1
    assert m["events"][0]["t_ms"] == 2000
    assert m["teams"]["A"]["longest_possession_ms"] == 2000
    assert m["teams"]["B"]["pressers_per_frame"] > 0
    assert m["teams"]["A"]["shape"]["frames"] == 40
    assert m["teams"]["B"]["shape"] is None  # fewer than 3 players
    assert m["ball"]["seen_ratio"] == 1.0
    assert sum(sum(r) for r in m["teams"]["A"]["heatmap"]["values"]) == pytest.approx(1.0, abs=1e-3)
    assert len(m["timeline"]) == 4
    assert {t["id"] for t in m["tracks"]} == {1, 2, 3, 4, 5, 9}


def test_distance_ignores_jumps():
    frames = _frames(3)
    rows = [
        TrackRow(0, 0, 0, 1, "player", 0.1, 0.1, 0.1, 0.1, team="A"),
        TrackRow(1, 100, 3, 1, "player", 0.15, 0.1, 0.1, 0.1, team="A"),
        TrackRow(2, 200, 6, 1, "player", 0.9, 0.9, 0.1, 0.1, team="A"),  # a jump > 0.2: another person
    ]
    m = compute(TrackSet(rows=rows, frames=frames))
    assert m["tracks"][0]["distance"] == pytest.approx(0.05)
