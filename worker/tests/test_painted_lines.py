import json
from pathlib import Path

import numpy as np

import pitchscene as ps
from ppcoachtracker_worker.calibration import solve_frame
from ppcoachtracker_worker.calibration.solver import Correspondences, pitch_error_m, well_posed
from ppcoachtracker_worker.calibration.template import build

GRID = [(x, y) for x in range(-30, 31, 10) for y in range(-20, 21, 10)]


def frame(points=(), lines=()):
    t = ps.touches(names=points) if points else {"frames": [{"frame": 0, "points": []}]}
    f = t["frames"][0]
    f["lines"] = list(lines)
    return f


def test_three_points_and_two_painted_lines_calibrate():
    f = frame(("corner_far_left", "center", "spot_right"), [ps.stroke("touch_near", 0.1, 0.9, noise_px=0.7), ps.stroke("halfway", 0.2, 0.8, noise_px=0.7, seed=1)])
    H, entry, note = solve_frame(f, ps.TPL, ps.W, ps.H)
    assert note is None and entry["used"] and (entry["points"], entry["lines"], entry["inliers"]) == (3, 2, 5)
    assert pitch_error_m(H, ps.CAM.homography(), GRID) < 0.5


def test_faint_lines_painted_only_in_part_still_count():
    # Only the middle of each line was visible enough to paint: no ends, no corners.
    lines = [ps.stroke(n, 0.35, 0.65, noise_px=0.5, seed=k) for k, n in enumerate(("touch_far", "touch_near", "goal_left", "box_right"))]
    H, entry, _ = solve_frame(frame(("center",), lines), ps.TPL, ps.W, ps.H)
    assert entry["used"] and entry["lines"] == 4
    assert pitch_error_m(H, ps.CAM.homography(), GRID) < 1.0


def test_lines_of_one_direction_do_not_fix_the_pitch():
    lines = [ps.stroke(n) for n in ("goal_left", "box_left", "goal_area_left", "halfway", "box_right")]
    H, entry, note = solve_frame(frame((), lines), ps.TPL, ps.W, ps.H)
    assert H is None and not entry["used"] and "no encajan" in note
    c = Correspondences([], [], [ps.TPL.lines[n] for n in ("goal_left", "halfway")], [((0, 0), (0, 1)), ((5, 0), (5, 1))])
    assert not well_posed(c)


def test_unnamed_and_bent_strokes_do_not_enter_the_solver():
    unnamed = {**ps.stroke("touch_near"), "name": None}
    bent = ps.stroke("touch_far")
    bent["points"][6]["y"] += 0.05  # a stroke that bends: not one straight line
    H, entry, note = solve_frame(frame(("corner_far_left", "center", "spot_right"), [unnamed, bent]), ps.TPL, ps.W, ps.H)
    assert H is None and entry["lines"] == 0 and "hacen falta 5" in note


def test_a_line_with_the_wrong_name_is_dropped():
    lines = [ps.stroke("touch_near"), ps.stroke("halfway"), ps.stroke("box_left")]
    lines[2]["name"] = "box_right"  # the coach picked the other area
    H, entry, note = solve_frame(frame(("corner_far_left", "corner_far_right", "center", "spot_right"), lines), ps.TPL, ps.W, ps.H)
    assert note is None and entry["used"] and entry["inliers"] == 6
    assert pitch_error_m(H, ps.CAM.homography(), GRID) < 0.5


def test_template_lines_match_the_shared_fixture():
    """The app (data/PitchTouches.kt) checks the same file: the coach names the same lines."""
    fixture = Path(__file__).parent / "fixtures" / "pitch_template_lines.json"
    current = {str(s): {n: [[round(x, 3), round(y, 3)] for x, y in seg] for n, seg in sorted(build(s).lines.items())} for s in (11, 7)}
    if not fixture.exists():
        fixture.write_text(json.dumps(current, indent=1, sort_keys=True) + "\n", encoding="utf-8")
    assert json.loads(fixture.read_text(encoding="utf-8")) == current
