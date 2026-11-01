import numpy as np

import pitchscene as ps
from ppcoachtracker_worker.calibration.propagate import Step, chain, motion_steps
from ppcoachtracker_worker.calibration.solver import project
from ppcoachtracker_worker.pipeline import analyze

MATCH = {"home": "Tigres", "away": "Halcones", "home_color": "red", "away_color": "blue"}


def _register(meta, clip_dir, frames=120):
    from ppcoachtracker_worker.chunks import load_clip

    sha = load_clip(clip_dir).chunks[0].sha256
    meta.streams[(sha, "player in red jersey")] = ps.sam_text("red", frames)
    meta.streams[(sha, "player in blue jersey")] = ps.sam_text("blue", frames)


def test_the_synthetic_camera_sees_the_whole_7_a_side_pitch():
    img = project(ps.CAM.homography(), list(ps.TPL.points.values()))
    assert ((img >= 0) & (img < [ps.W, ps.H])).all()


def test_synthetic_pitch_gives_the_known_speeds(meta, tmp_path, config_for):
    clip_dir = ps.make_clip(tmp_path / "pitch")
    _register(meta, clip_dir)
    result, ts = analyze(clip_dir, MATCH, config_for(meta.url), pitch_mode="no_lines", calibration=ps.touches())
    cal = result["calibration"]
    assert cal["frames_with_homography"] == 1.0
    assert cal["pitch"] == {"size": 7, "length_m": 60.0, "width_m": 40.0, "source": "default"}
    assert cal["key_frames"][0]["used"] is True
    pitch = result["pitch"]
    assert pitch["seconds"] >= 10 and pitch["approximate"] is True and pitch["dropped_steps"] == 0
    # Each track runs at the speed of its player, within 1 %.
    by_team = {"A": [], "B": []}
    for t in pitch["tracks"]:
        by_team[t["team"]].append(t["distance_m"] / (t["seconds"] - 1))
    expected = {"A": sorted(abs(p[2]) for p in ps.PLAYERS if p[0] == "red"), "B": sorted(abs(p[2]) for p in ps.PLAYERS if p[0] == "blue")}
    for team in ("A", "B"):
        got = sorted(by_team[team])
        assert len(got) == 4
        for g, e in zip(got, expected[team]):
            assert abs(g - e) / e < 0.01, (team, got, expected[team])
    top = sorted(t["top_speed"] for t in pitch["tracks"] if t["team"] == "A")
    assert abs(top[-1] - 4.0) / 4.0 < 0.01
    # Every player row has meters; the limitation says where the meters come from.
    assert all(r.mx is not None for r in ts.rows if r.label == "player")
    assert result["limitations"][0].startswith("Las métricas en metros salen del 100 %")
    assert "por omisión" in result["limitations"][1]
    assert sum(sum(r) for r in pitch["teams"]["A"]["heatmap"]["values"]) > 0.99
    # Muse Spark received the aggregates in meters.
    muse = [b for p, b in meta.calls if isinstance(b, dict) and str(b.get("model", "")).startswith("muse")][0]
    assert "pitch_meters" in muse["input"][1]["content"]


def test_off_ignores_the_points(meta, tmp_path, config_for):
    clip_dir = ps.make_clip(tmp_path / "pitch")
    _register(meta, clip_dir)
    result, ts = analyze(clip_dir, MATCH, config_for(meta.url), pitch_mode="off", calibration=ps.touches())
    assert "calibration" not in result and "pitch" not in result
    assert all(r.mx is None for r in ts.rows)
    assert result["limitations"][0].startswith("Coordenadas de pantalla")


def test_without_points_there_is_no_pitch_and_a_note_says_why(meta, tmp_path, config_for):
    clip_dir = ps.make_clip(tmp_path / "pitch", seconds=3)
    _register(meta, clip_dir, frames=30)
    result, _ = analyze(clip_dir, MATCH, config_for(meta.url), pitch_mode="no_lines", calibration=None)
    assert "pitch" not in result
    assert any("Sin puntos marcados" in n for n in result["notes"])


def test_two_swapped_points_are_dropped_and_the_rest_calibrates(tmp_path):
    from ppcoachtracker_worker.calibration import calibrate
    from ppcoachtracker_worker.calibration.solver import unproject
    from ppcoachtracker_worker.chunks import load_clip
    from ppcoachtracker_worker.types import TrackSet

    clip = load_clip(ps.make_clip(tmp_path / "pitch", seconds=3))
    names = ("corner_far_left", "corner_far_right", "corner_near_left", "corner_near_right", "center", "spot_left", "box_far_right")
    bad = ps.touches(names=names)
    pts = bad["frames"][0]["points"]
    pts[0]["name"], pts[1]["name"] = pts[1]["name"], pts[0]["name"]  # the coach confused the far corners
    cal, notes = calibrate(clip, TrackSet(rows=[], frames=clip.frame_infos()), bad, "lines")
    key = cal.report["key_frames"][0]
    assert key["used"] and key["inliers"] == 5 and notes == []
    Hm = cal.homographies[0][0]
    true = project(ps.CAM.homography(), [ps.TPL.points["corner_far_left"], ps.TPL.points["box_far_right"]])
    back = unproject(Hm, true)
    assert np.allclose(back, [ps.TPL.points["corner_far_left"], ps.TPL.points["box_far_right"]], atol=0.5)


def test_four_points_are_not_enough(tmp_path):
    from ppcoachtracker_worker.calibration import calibrate
    from ppcoachtracker_worker.chunks import load_clip
    from ppcoachtracker_worker.types import TrackSet

    clip = load_clip(ps.make_clip(tmp_path / "pitch", seconds=3))
    four = ps.touches(names=("corner_far_left", "corner_far_right", "corner_near_left", "corner_near_right"))
    cal, notes = calibrate(clip, TrackSet(rows=[], frames=clip.frame_infos()), four, "lines")
    assert cal is None and any("hacen falta 5" in n for n in notes)


def test_points_that_do_not_fit_are_not_used(tmp_path):
    from ppcoachtracker_worker.calibration import calibrate
    from ppcoachtracker_worker.chunks import load_clip
    from ppcoachtracker_worker.types import TrackSet

    clip = load_clip(ps.make_clip(tmp_path / "pitch", seconds=3))
    bad = ps.touches(names=("corner_far_left", "corner_far_right", "corner_near_left", "corner_near_right", "center"))
    # Every point shuffled: no 4 of them agree on a pitch.
    names = [p["name"] for p in bad["frames"][0]["points"]]
    for p, n in zip(bad["frames"][0]["points"], names[2:] + names[:2]):
        p["name"] = n
    cal, notes = calibrate(clip, TrackSet(rows=[], frames=clip.frame_infos()), bad, "lines")
    assert cal is None
    assert any("no encajan" in n for n in notes)


def test_a_static_camera_keeps_the_touched_homography_without_reading_the_video(tmp_path):
    from ppcoachtracker_worker.calibration import calibrate
    from ppcoachtracker_worker.chunks import load_clip
    from ppcoachtracker_worker.types import TrackSet

    clip = load_clip(ps.make_clip(tmp_path / "pitch", seconds=3))
    infos = clip.frame_infos()
    # No frames at all: a static camera needs no motion pass.
    cal, notes = calibrate(clip, TrackSet(rows=[], frames=infos), ps.touches(), "no_lines", frames=iter(()), camera="static")
    assert notes == []
    assert len(cal.homographies) == len(infos)
    assert {s for _, s in cal.homographies.values()} == {"manual", "static"}
    assert cal.report["camera"] == "static"
    assert cal.report["frames_with_homography"] == 1.0
    assert cal.report["by_method"]["static"] == round((len(infos) - 1) / len(infos), 4)
    assert cal.report["by_method"]["propagated"] == 0.0
    key = cal.homographies[0][0]
    assert all(np.allclose(Hm, key) for Hm, _ in cal.homographies.values())
    # Moving (the default) with no frames to follow: only the touched frame.
    moving, _ = calibrate(clip, TrackSet(rows=[], frames=infos), ps.touches(), "no_lines", frames=iter(()))
    assert moving.report["camera"] == "moving" and len(moving.homographies) == 1


def test_propagation_follows_a_pan_and_stops_at_a_cut():
    import cv2

    rng = np.random.default_rng(3)
    big = cv2.GaussianBlur(rng.integers(0, 255, (400, 1000)).astype(np.uint8), (0, 0), 1.5)
    big = cv2.merge([big // 4, (100 + big // 2).astype(np.uint8), big // 5])  # green-ish texture
    frames = [(i, big[20:380, 5 * i:5 * i + 640].copy()) for i in range(20)]
    frames.append((20, np.full((360, 640, 3), (30, 30, 200), dtype=np.uint8)))  # a cut
    steps = motion_steps(iter(frames), lambda g: [])
    assert all(s.motion is not None for s in steps[1:20])
    assert steps[20].cut and steps[20].motion is None
    # Each step moves the image 5 px to the left.
    assert abs(steps[10].motion[0, 2] + 5.0) < 0.3
    H0 = np.eye(3)
    out = chain(steps, {0: H0}, lambda i, H: True)
    assert set(out) == set(range(20))
    assert abs(out[19][0][0, 2] + 95.0) < 2.0


def test_the_feet_check_cuts_the_chain_only_after_failing_several_frames_in_a_row():
    steps = [Step(np.eye(3), 100, 1.0, False) for _ in range(30)]
    bad = set(range(5, 8)) | set(range(20, 30))  # a moment out of place, then for good
    accept = lambda i, H: i not in bad  # noqa: E731
    assert set(chain(steps, {0: np.eye(3)}, accept)) == set(range(5))
    # With patience the moment is crossed and kept; the cut drops the failing frames.
    assert set(chain(steps, {0: np.eye(3)}, accept, patience=4)) == set(range(20))
    assert set(chain(steps, {29: np.eye(3)}, accept, patience=4)) == {29}
    # Never carried farther than max_carry from the key, either way.
    assert set(chain(steps, {10: np.eye(3)}, lambda i, H: True, max_carry=3)) == set(range(7, 14))


def test_the_feet_check_counts_only_players_with_a_team_and_waits_a_second(tmp_path):
    from ppcoachtracker_worker.calibration import calibrate
    from ppcoachtracker_worker.chunks import load_clip
    from ppcoachtracker_worker.types import TrackRow, TrackSet

    clip = load_clip(ps.make_clip(tmp_path / "pitch", seconds=3))
    infos = clip.frame_infos()
    on = project(ps.CAM.homography(), [[-10, 0], [0, 5], [10, -5]]) / [ps.W, ps.H]
    off = project(ps.CAM.homography(), [[-10, 40], [0, 40], [10, 40]]) / [ps.W, ps.H]  # 20 m past the far touchline

    def people(f, feet, team):
        return [TrackRow(f, f * 100, f * 3, n + (10 if team else 20), "player", x - 0.01, y - 0.05, 0.02, 0.05, team=team) for n, (x, y) in enumerate(feet)]

    def run(rows):
        cal, _ = calibrate(clip, TrackSet(rows=rows, frames=infos), ps.touches(), "no_lines", frames=iter(()), camera="static")
        return len(cal.homographies)

    # Staff and the bench outside the lines, in every frame: they have no team and do not count.
    staff = [r for f in range(len(infos)) for r in people(f, on, "A") + people(f, off, "")]
    assert run(staff) == len(infos)
    # Players out of place for half a second do not cut it; for good from frame 15, they do.
    out = set(range(5, 10)) | set(range(15, len(infos)))
    rows = [r for f in range(len(infos)) for r in people(f, off if f in out else on, "B")]
    assert run(rows) == 15


def test_people_outside_the_lines_most_of_the_time_leave_the_feet_check_and_lose_their_team(tmp_path):
    from ppcoachtracker_worker.calibration import calibrate
    from ppcoachtracker_worker.chunks import load_clip
    from ppcoachtracker_worker.types import TrackRow, TrackSet

    clip = load_clip(ps.make_clip(tmp_path / "pitch", seconds=4))
    infos = clip.frame_infos()
    on = project(ps.CAM.homography(), [[-10, 0], [0, 5], [10, -5], [20, 8]]) / [ps.W, ps.H]
    bench = project(ps.CAM.homography(), [[-5, -26], [5, -26]]) / [ps.W, ps.H]  # 6 m past the near touchline
    winger = project(ps.CAM.homography(), [[15, 21.5]]) / [ps.W, ps.H]  # 1.5 m past the far touchline
    rows = []
    for f in range(len(infos)):
        rows += [TrackRow(f, f * 100, f * 3, 1 + n, "player", x - 0.01, y - 0.05, 0.02, 0.05, team="A") for n, (x, y) in enumerate(on)]
        # The bench in dark clothes came out as the team in black.
        rows += [TrackRow(f, f * 100, f * 3, 10 + n, "player", x - 0.01, y - 0.05, 0.02, 0.05, team="B") for n, (x, y) in enumerate(bench)]
        # A winger outside the line a third of the time is still a player.
        x, y = winger[0] if f % 3 == 0 else on[3]
        rows.append(TrackRow(f, f * 100, f * 3, 20, "player", x - 0.01, y - 0.05, 0.02, 0.05, team="B"))
    cal, notes = calibrate(clip, TrackSet(rows=rows, frames=infos), ps.touches(), "no_lines", frames=iter(()), camera="static")
    assert cal.off_pitch == {10, 11} and cal.report["off_pitch_tracks"] == 2
    assert len(cal.homographies) == len(infos)
    assert any("fuera de las líneas" in n for n in notes)


def test_a_saved_run_keeps_the_coach_marks_for_the_viewer(meta, tmp_path, config_for):
    import json

    from ppcoachtracker_worker.chunks import load_clip
    from ppcoachtracker_worker.debug import save_run

    clip_dir = ps.make_clip(tmp_path / "pitch", seconds=3)
    _register(meta, clip_dir, frames=30)
    touches = ps.touches()
    result, ts = analyze(clip_dir, MATCH, config_for(meta.url), pitch_mode="no_lines", calibration=touches, camera="static")
    run = save_run(tmp_path / "runs", load_clip(clip_dir), result, ts, name="r1", calibration=analyze.last_calibration)
    pitch = json.loads((run / "tracks.json").read_text())["pitch"]
    names = [p["name"] for p in touches["frames"][0]["points"]]
    assert [m[1] for m in pitch["marks"]] == names and {m[0] for m in pitch["marks"]} == {0}
    assert all(0 <= m[2] <= 1 and 0 <= m[3] <= 1 for m in pitch["marks"])
    assert pitch["points"]["center"] == [0.0, 0.0] and set(names) <= set(pitch["points"])
    assert pitch["camera"] == "static"
