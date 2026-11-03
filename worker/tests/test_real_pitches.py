"""The pitch mask on frames of two real pitches recorded with the app (fixtures/real/README.md).

What works is asserted; what does not yet is marked xfail (strict), so fixing it shows up here.
"""
from pathlib import Path

import cv2
import pytest

from ppcoachtracker_worker import pitch

REAL = Path(__file__).parent / "fixtures" / "real"
# Taken as the app does (SurfaceSample.fromPixels) on the full frames: taps in the sun only,
# and the same pitch with a tap in the shade of a tree too.
DIRT_SUN = pitch.SurfaceSample((9, 20), (44, 117), (117, 255))
DIRT_SUN_AND_SHADE = pitch.SurfaceSample((8, 112), (0, 104), (32, 255))
# Three taps on a pitch of dry, yellow grass, recorded with the phone's camera.
DRY_GRASS = pitch.SurfaceSample((9, 21), (22, 117), (39, 207))


def frame(name: str):
    img = cv2.imread(str(REAL / f"{name}.jpg"))
    assert img is not None and img.shape == (360, 640, 3)
    return img


def top(m: pitch.PitchMask) -> float:
    return min(m.polygon[1::2])


def test_a_grass_pitch_with_a_running_track_from_the_stands():
    m = pitch.measure(frame("yerbabuena1_cerca"))
    # The grass, below the hill and the houses; the track around it is reddish and stays out.
    assert m.has_pitch and 0.3 < top(m) and max(m.polygon[1::2]) < 0.75


def test_a_dirt_pitch_is_found_by_the_color_of_the_dirt_not_of_the_grass():
    img = frame("yerbabuena2_sol")
    assert pitch.measure(img, pitch.DIRT).has_pitch
    assert not pitch.measure(img).has_pitch
    assert pitch.measure(img, pitch.DIRT, DIRT_SUN.widened()).has_pitch


def test_the_sample_in_the_sun_finds_a_dirt_pitch_in_shade_the_fixed_colors_miss():
    img = frame("yerbabuena2_sombra")
    # A tree's shade covers part of it: the fixed dirt colors leave 69.5 % of the polygon, under the 70 %.
    assert not pitch.measure(img, pitch.DIRT).has_pitch
    m = pitch.measure(img, pitch.DIRT, DIRT_SUN.widened())
    assert m.has_pitch and top(m) > 0.05


@pytest.mark.xfail(strict=True, reason="the grass far away, with tree shadows and lines in it, fills 60 % of its polygon, under the 70 %")
def test_the_same_grass_pitch_from_farther():
    assert pitch.measure(frame("yerbabuena1_lejos")).has_pitch


def test_a_sample_with_a_tap_in_the_shade_is_ignored_rather_than_covering_the_sky():
    # As it is, its ranges take the whole frame, sky included.
    assert top(pitch.measure(frame("yerbabuena2_sombra"), pitch.DIRT, DIRT_SUN_AND_SHADE.widened())) == 0.0
    # The worker does not use it (the app no longer takes such a tap); the sunny one stays.
    assert pitch.sample_of(DIRT_SUN_AND_SHADE.as_dict()) is None
    assert pitch.sample_of(DIRT_SUN.as_dict()) == DIRT_SUN


# Recorded with the phone's own camera (HEVC, 720p to 8K), to import into the app.


def test_the_grass_pitch_in_8k_from_the_stands():
    m = pitch.measure(frame("yerbabuena1_cel_8k"))
    assert m.has_pitch and 0.15 < top(m) and max(m.polygon[1::2]) < 0.8


def test_a_dry_grass_pitch_is_found_only_by_its_sample():
    img = frame("yerbabuena1_seca")
    # Yellow, neither green enough for grass nor even enough for the fixed dirt colors.
    assert not pitch.measure(img).has_pitch and not pitch.measure(img, pitch.DIRT).has_pitch
    m = pitch.measure(img, pitch.DIRT, DRY_GRASS.widened())
    assert m.has_pitch and top(m) > 0.3


@pytest.mark.xfail(strict=True, reason="the dry hill and the beige houses behind it are dirt-colored too: the polygon climbs to the top of the frame")
def test_a_dirt_pitch_in_8k_stays_below_the_hills():
    m = pitch.measure(frame("yerbabuena2_cel_8k"), pitch.DIRT)
    assert m.has_pitch and top(m) > 0.1
