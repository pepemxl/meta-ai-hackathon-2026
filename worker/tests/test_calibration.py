import math

import numpy as np
import pytest

from ppcoachtracker_worker.calibration.solver import Camera, Correspondences, dlt, fit_homography, pitch_error_m, project, ransac_points
from ppcoachtracker_worker.calibration.template import build

W, H = 1920, 1080
TPL = build(11)
# A broadcast-like camera: behind the near touchline, high, looking at the field.
CAM = Camera(cx=0.0, cy=-55.0, cz=18.0, pan=0.05, tilt=math.atan2(18.0, 55.0), roll=0.0, focal=1300.0, width=W, height=H)


def visible(Hm, names):
    out = []
    for n in names:
        x, y = project(Hm, [TPL.points[n]])[0]
        if 0 <= x < W and 0 <= y < H:
            out.append(n)
    return out


def field_grid(step=7.5):
    xs = np.arange(-TPL.length / 2, TPL.length / 2 + 0.1, step)
    ys = np.arange(-TPL.width / 2, TPL.width / 2 + 0.1, step)
    return np.array([(x, y) for x in xs for y in ys])


def marks(Hm):
    """The template's named marks the camera sees."""
    return np.array([TPL.points[n] for n in visible(Hm, TPL.points)])


def in_view(Hm, pts):
    img = project(Hm, pts)
    return pts[(img[:, 0] >= 0) & (img[:, 0] < W) & (img[:, 1] >= 0) & (img[:, 1] < H)]


def test_template_names_and_sizes():
    t7 = build(7)
    assert (t7.length, t7.width, t7.approximate) == (60.0, 40.0, True)
    assert build(7, 55, 35).approximate is False
    assert TPL.points["corner_far_left"] == (-52.5, 34.0)
    assert TPL.points["spot_right"] == (52.5 - 11.0, 0.0)
    assert set(TPL.points) == set(t7.points)
    assert "box_far_left_goal" in TPL.points and "goal_area_near_right" in TPL.points
    with pytest.raises(ValueError):
        build(9)


def test_the_simulated_camera_sees_the_pitch():
    Ht = CAM.homography()
    c = project(Ht, [(0, 0)])[0]
    assert 0 < c[0] < W and 0 < c[1] < H
    assert len(visible(Ht, TPL.points)) >= 15


@pytest.mark.parametrize("noise", [0.5, 1.0])
def test_points_with_noise_recover_the_homography(noise):
    Ht = CAM.homography()
    names = visible(Ht, TPL.points)
    rng = np.random.default_rng(1)
    pitch = [TPL.points[n] for n in names]
    img = project(Ht, pitch) + rng.normal(0, noise, (len(names), 2))
    He = fit_homography(Correspondences.of_points(pitch, img))
    m = marks(Ht)
    err_px = np.linalg.norm(project(He, m) - project(Ht, m), axis=1).max()
    assert err_px < 3.0


def test_lines_seen_without_their_endpoints():
    # A stretch of each line is seen, never its ends, and only inside the image. Short
    # segments near the horizon amplify the noise: a line detector gives the long ones.
    Ht = CAM.homography()
    rng = np.random.default_rng(2)
    plines, ilines = [], []
    stretch = {"touch_far": (0.15, 0.85), "halfway": (0.15, 0.85), "box_left": (0.4, 0.85), "box_right": (0.4, 0.85)}
    for name, (f1, f2) in stretch.items():
        (ax, ay), (bx, by) = TPL.lines[name]
        a = (ax + f1 * (bx - ax), ay + f1 * (by - ay))
        b = (ax + f2 * (bx - ax), ay + f2 * (by - ay))
        seen = project(Ht, [a, b])
        assert ((seen >= 0) & (seen < [W, H])).all(), name
        plines.append(TPL.lines[name])
        ilines.append(tuple(map(tuple, seen + rng.normal(0, 0.5, (2, 2)))))
    pts = ["center", "spot_left", "spot_right"]
    img = project(Ht, [TPL.points[n] for n in pts]) + rng.normal(0, 0.5, (3, 2))
    c = Correspondences([TPL.points[n] for n in pts], [tuple(p) for p in img], plines, ilines)
    He = fit_homography(c)
    # Measured where the observations constrain it: the marks inside their convex hull.
    import cv2

    seen = np.array([p for seg in ilines for p in seg] + [tuple(p) for p in img], dtype=np.float32)
    hull = cv2.convexHull(seen)
    m = np.array([p for p in marks(Ht) if cv2.pointPolygonTest(hull, tuple(map(float, project(Ht, [p])[0])), False) >= 0])
    assert len(m) >= 5
    assert np.linalg.norm(project(He, m) - project(Ht, m), axis=1).max() < 3.0


def test_ransac_survives_two_mislabeled_marks():
    Ht = CAM.homography()
    names = visible(Ht, TPL.points)
    pitch = np.array([TPL.points[n] for n in names])
    img = project(Ht, pitch)
    # The coach confused two marks: swap their image points.
    img[[0, 1]] = img[[1, 0]]
    He, inliers = ransac_points(pitch, img)
    assert not inliers[0] and not inliers[1]
    assert inliers[2:].all()
    assert pitch_error_m(He, Ht, in_view(Ht, field_grid())) < 0.2


def test_camera_of_one_frame_from_its_homography():
    Ht = CAM.homography()
    cam = Camera.from_homography(Ht, W, H, in_view(Ht, field_grid()))
    assert abs(cam.cy - CAM.cy) < 1.0 and abs(cam.cz - CAM.cz) < 1.0
    assert pitch_error_m(cam.homography(), Ht, in_view(Ht, field_grid())) < 0.3


def test_camera_model_with_two_lines_and_a_point():
    # The place is known from an earlier frame; this frame panned and zoomed.
    moved = Camera(CAM.cx, CAM.cy, CAM.cz, pan=0.18, tilt=CAM.tilt + 0.03, roll=0.0, focal=1550.0, width=W, height=H)
    Ht = moved.homography()
    rng = np.random.default_rng(3)
    plines, ilines = [], []
    for name in ("touch_far", "halfway"):
        (ax, ay), (bx, by) = TPL.lines[name]
        a = (ax + 0.45 * (bx - ax), ay + 0.45 * (by - ay))
        b = (ax + 0.6 * (bx - ax), ay + 0.6 * (by - ay))
        plines.append(TPL.lines[name])
        ilines.append(tuple(map(tuple, project(Ht, [a, b]) + rng.normal(0, 0.5, (2, 2)))))
    c = Correspondences([TPL.points["circle_right"]], [tuple(project(Ht, [TPL.points["circle_right"]])[0])], plines, ilines)
    est = CAM.fit(c, free="ptz")
    assert pitch_error_m(est.homography(), Ht, in_view(Ht, field_grid())) < 1.0
    # Two lines alone are not enough for a free homography.
    with pytest.raises(ValueError):
        dlt(Correspondences([], [], plines, ilines))


def test_template_matches_the_shared_fixture():
    """The app (data/PitchTouches.kt) checks the same file: both sides use the same names and meters."""
    import json
    from pathlib import Path

    fixture = Path(__file__).parent / "fixtures" / "pitch_template.json"
    current = {str(size): {n: [round(x, 3), round(y, 3)] for n, (x, y) in sorted(build(size).points.items())} for size in (11, 7)}
    if not fixture.exists():
        fixture.write_text(json.dumps(current, indent=1, sort_keys=True) + "\n", encoding="utf-8")
    assert json.loads(fixture.read_text(encoding="utf-8")) == current
