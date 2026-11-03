import json
from pathlib import Path

import numpy as np

from fakes import make_clip
from ppcoachtracker_worker import pitch, shots
from ppcoachtracker_worker.chunks import load_clip
from ppcoachtracker_worker.types import FrameInfo, TrackRow, TrackSet

FIXTURE = Path(__file__).parent / "fixtures" / "pitch_mask_cases.json"

STANDS = [120, 110, 100]
GRASS = [50, 140, 60]
WHITE = [235, 235, 235]
RED = [220, 40, 40]

# Grids described by shapes, so the app's test (data/PitchMask.kt) draws exactly the same.
CASES = {
    "broadcast": {"background": STANDS, "shapes": [
        {"rgb": GRASS, "poly": [0, 0.35, 1, 0.3, 1, 1, 0, 1]},
        {"rgb": WHITE, "poly": [0, 0.6, 1, 0.6, 1, 0.615, 0, 0.615]},  # a pitch line
        {"rgb": WHITE, "poly": [0.5, 0.32, 0.51, 0.32, 0.51, 1, 0.5, 1]},  # halfway line
        {"rgb": RED, "poly": [0.3, 0.5, 0.32, 0.5, 0.32, 0.58, 0.3, 0.58]},  # players
        {"rgb": RED, "poly": [0.7, 0.7, 0.72, 0.7, 0.72, 0.78, 0.7, 0.78]},
        {"rgb": RED, "poly": [0.1, 0.8, 0.12, 0.8, 0.12, 0.9, 0.1, 0.9]},
    ]},
    "no_grass": {"background": STANDS, "shapes": []},
    "ring_of_grass": {"background": STANDS, "shapes": [
        {"rgb": GRASS, "poly": [0, 0, 1, 0, 1, 0.06, 0, 0.06]},
        {"rgb": GRASS, "poly": [0, 0.94, 1, 0.94, 1, 1, 0, 1]},
        {"rgb": GRASS, "poly": [0, 0, 0.04, 0, 0.04, 1, 0, 1]},
        {"rgb": GRASS, "poly": [0.96, 0, 1, 0, 1, 1, 0.96, 1]},
    ]},
    "dark_green_shadow": {"background": STANDS, "shapes": [
        {"rgb": GRASS, "poly": [0, 0.4, 1, 0.4, 1, 1, 0, 1]},
        {"rgb": [20, 35, 22], "poly": [0, 0.4, 0.3, 0.4, 0.3, 1, 0, 1]},  # value < 40: not grass
    ]},
}


def raster(spec) -> np.ndarray:
    grid = np.zeros((pitch.GRID_H, pitch.GRID_W, 3), dtype=np.int64)
    grid[:] = spec["background"]
    for shape in spec["shapes"]:
        pts = list(zip(shape["poly"][0::2], shape["poly"][1::2]))
        for y in range(pitch.GRID_H):
            for x in range(pitch.GRID_W):
                if pitch.point_in_polygon(x / (pitch.GRID_W - 1), y / (pitch.GRID_H - 1), pts):
                    grid[y, x] = shape["rgb"]
    return grid


def test_broadcast_frame_keeps_the_pitch_and_drops_the_stands():
    m = pitch.mask_from_grid(raster(CASES["broadcast"]))
    assert m.has_pitch and len(m.polygon) // 2 <= pitch.MAX_VERTICES
    pts = list(zip(m.polygon[0::2], m.polygon[1::2]))
    # Lines and players do not leave holes: the closed blob and the hull cover them.
    assert pitch.point_in_polygon(0.31, 0.54, pts) and pitch.point_in_polygon(0.5, 0.61, pts)
    # The stands stay out; the band near the touchline stays in.
    assert not pitch.point_in_polygon(0.5, 0.2, pts)
    assert pitch.point_in_polygon(0.5, 0.97, pts)
    assert 0.6 < m.fraction < 0.7


def test_no_grass_and_little_grass_inside_the_contour_are_not_a_pitch():
    assert not pitch.mask_from_grid(raster(CASES["no_grass"])).has_pitch
    ring = pitch.mask_from_grid(raster(CASES["ring_of_grass"]))
    assert not ring.has_pitch and ring.fraction > 0.1
    shadow = pitch.mask_from_grid(raster(CASES["dark_green_shadow"]))
    assert shadow.has_pitch
    assert not pitch.point_in_polygon(0.1, 0.7, list(zip(shadow.polygon[0::2], shadow.polygon[1::2])))


def test_fast_blob_matches_the_plain_one():
    grass = pitch.close(pitch.grass_cells(raster(CASES["broadcast"])))
    slow = pitch.convex_hull([(x, y) for x, y in pitch.largest_blob(grass)])
    fast = pitch.convex_hull(pitch.blob_row_ends(grass))
    assert slow == fast


def test_shared_fixture_for_the_app():
    current = {"grid": [pitch.GRID_W, pitch.GRID_H], "cases": {}}
    for name, spec in CASES.items():
        m = pitch.mask_from_grid(raster(spec))
        current["cases"][name] = {"spec": spec, "fraction": m.fraction, "polygon": list(m.polygon)}
    if not FIXTURE.exists():
        FIXTURE.write_text(json.dumps(current, indent=1) + "\n", encoding="utf-8")
    assert json.loads(FIXTURE.read_text(encoding="utf-8")) == current


def test_yaml_rows_are_validated():
    assert pitch.from_yaml_row([0, 0.62, [0, 0.4, 1, 0.4, 1, 1, 0, 1]], "hsv_v1").has_pitch
    assert not pitch.from_yaml_row([1, 0.03, []], "hsv_v1").has_pitch
    assert pitch.from_yaml_row([0, 0.5, [0, 0.4, 1, 0.4, 1]], "hsv_v1") is None  # odd
    assert pitch.from_yaml_row([0, 0.5, [0, 0.4, 1, 0.4]], "hsv_v1") is None  # fewer than 3 points
    assert pitch.from_yaml_row([0, 0.5, [0, 0, 1, 0, 1, 1]], "hsv_v9") is None  # unknown method
    assert pitch.from_yaml_row([0, "x", []], "hsv_v1") is None
    clipped = pitch.from_yaml_row([0, 1.5, [-0.2, 0, 1.3, 0, 1, 1]], "hsv_v1")
    assert clipped.fraction == 1.0 and clipped.polygon == (0.0, 0.0, 1.0, 0.0, 1.0, 1.0)


def test_clip_reads_the_app_mask_without_the_overlap(tmp_path):
    clip_dir = make_clip(tmp_path / "clip", total_ms=12000, chunk_seconds=7, overlap_ms=2000)
    c2 = clip_dir / "video_part_002.yaml"
    text = c2.read_text()
    rows = "\n".join(f"    - [{i}, 0.9, [0, 0, 1, 0, 1, 1, 0, 1]]" for i in range(load_clip(clip_dir).chunks[1].frame_count))
    c2.write_text(text.replace('sha256:', f'pitch:\n  method: "hsv_v1"\n  grid: [160, 90]\n  with_pitch: 1\n  frames:\n{rows}\nsha256:'))
    clip = load_clip(clip_dir)
    masks = clip.pitch_masks()
    assert len(masks) == clip.unique_frames
    first = clip.chunks[0].frame_count
    assert all(m is None for m in masks[:first])  # chunk 1 has no section: measured from the video
    assert all(m is not None and m.has_pitch for m in masks[first:])


def test_shots_discard_frames_without_pitch_and_feet_off_the_polygon():
    frames = [FrameInfo(i, i * 100, i * 3) for i in range(10)]
    full = pitch.PitchMask(0.8, (0, 0.3, 1, 0.3, 1, 1, 0, 1))
    none = pitch.PitchMask(0.05, ())
    masks = [full] * 6 + [none] * 4
    rows = [TrackRow(i, i * 100, i * 3, 1, "player", 0.4, 0.4, 0.05, 0.2) for i in range(10)]
    rows += [TrackRow(i, i * 100, i * 3, 2, "player", 0.4, 0.05, 0.05, 0.1) for i in range(10)]  # feet in the stands
    s = shots.apply(TrackSet(rows=rows, frames=frames), masks)
    assert s.used_mask and [f.frame for f in s.counted] == list(range(6))
    assert {r.track_id for r in s.trackset.rows} == {1}
    assert s.discarded == [[600, 1000]]
    # Fewer than half the frames with pitch: the mask is not used at all.
    s2 = shots.apply(TrackSet(rows=rows, frames=frames), [full] * 4 + [none] * 6)
    assert not s2.used_mask and len(s2.counted) == 10 and len(s2.trackset.rows) == 20


def test_the_play_area_is_a_mask_whose_margin_is_the_same_across_and_along():
    import pytest

    square = [{"x": 0.25, "y": 0.25}, {"x": 0.75, "y": 0.25}, {"x": 0.75, "y": 0.75}, {"x": 0.25, "y": 0.75}]
    # A panorama 4 times wider than tall, margin 5 % of the height (40 px of 800).
    m = pitch.from_play_area({"points": square, "margin": 0.05}, 3200, 800)
    assert m.has_pitch and m.margin == 0.05 and m.aspect == 4.0
    assert m.contains(0.5, 0.5)
    assert m.contains(0.5, 0.79) and not m.contains(0.5, 0.81)  # 32 px below; 48 px
    assert m.contains(0.76, 0.5) and not m.contains(0.77, 0.5)  # 32 px right; 64 px
    assert not m.contains(0.76, 0.5, margin=0.0)
    assert m.box(3200, 800) == (pytest.approx(760.0), pytest.approx(160.0), pytest.approx(2440.0), pytest.approx(640.0))
    # Not usable: too few points, out of the frame, a margin the API would not accept.
    assert pitch.from_play_area({"points": square[:2]}, 3200, 800) is None
    assert pitch.from_play_area({"points": square[:3] + [{"x": 1.5, "y": 0.5}]}, 3200, 800) is None
    assert pitch.from_play_area({"points": square, "margin": 0.5}, 3200, 800) is None
    assert pitch.from_play_area(None, 3200, 800) is None
    # The masks measured from the video keep their margin as a fraction of each side.
    assert pitch.PitchMask(0.5, (0.25, 0.25, 0.75, 0.25, 0.75, 0.75, 0.25, 0.75)).contains(0.765, 0.5)


def test_tiles_go_over_the_play_area():
    import pytest

    from ppcoachtracker_worker import tiles

    area = pitch.from_play_area({"points": [{"x": 0.05, "y": 0.1}, {"x": 0.95, "y": 0.1}, {"x": 0.95, "y": 0.9}, {"x": 0.05, "y": 0.9}]}, 3840, 800)
    plan = tiles.plan(3840, 800, area.box(3840, 800))
    assert plan is not None and plan.tiles[0].x == pytest.approx(192.0) and plan.tiles[0].y == pytest.approx(80.0)


def test_a_dirt_pitch_is_found_by_the_color_of_dirt_and_not_by_grass():
    import pytest

    from ppcoachtracker_worker import tiles
    from ppcoachtracker_worker.pipeline import grass_around_box

    # BGR: stands, a beige wall (too pale for dirt) and dark trees above a dirt pitch with a line and players.
    img = np.zeros((360, 640, 3), np.uint8)
    img[:] = STANDS[::-1]
    img[:60] = (20, 35, 25)  # trees in the shade (too dark for grass or dirt)
    img[60:100] = (175, 200, 215)  # wall
    img[100:] = (115, 150, 185)  # dirt: hue 15, saturation 96, value 185
    img[200:204] = WHITE[::-1]
    img[150:180, 300:310] = RED[::-1]
    assert pitch.surface_of("dirt") == pitch.DIRT and pitch.surface_of("") == pitch.surface_of(None) == pitch.surface_of("turf") == pitch.GRASS
    assert not pitch.measure(img).has_pitch  # no grass
    m = pitch.measure(img, pitch.DIRT)
    assert m.has_pitch and m.fraction > 0.6
    ys = m.polygon[1::2]
    assert min(ys) == pytest.approx(100 / 360, abs=0.02) and max(ys) == 1.0  # from the wall down
    assert not m.contains(0.5, 0.2)  # the wall and the trees are not the pitch
    region = tiles.grass_region(img, surface=pitch.DIRT)
    assert region is not None and region[300, 320] == 255 and region[30, 320] == 0
    assert tiles.grass_region(img) is None
    ball = TrackRow(frame=0, t_ms=0, source_frame=0, track_id=1, label="ball", x=0.6, y=0.6, w=0.01, h=0.01)
    assert grass_around_box(img, ball, pitch.DIRT) > 0.9 and grass_around_box(img, ball) == 0.0


def test_a_narrow_sample_still_finds_the_pitch_with_its_margin():
    import cv2

    # Turf in two shades (stripes of hue 50 and 42) below the stands; a few taps caught only the first.
    hsv = np.zeros((360, 640, 3), np.uint8)
    hsv[:] = (100, 40, 200)
    hsv[120:] = (50, 150, 120)
    for x in range(0, 640, 16):
        hsv[120:, x:x + 8] = (42, 150, 120)
    img = cv2.cvtColor(hsv, cv2.COLOR_HSV2BGR)
    taps = pitch.SurfaceSample((44, 55), (130, 170), (100, 140))
    assert not pitch.measure(img, pitch.GRASS, taps).has_pitch
    m = pitch.measure(img, pitch.GRASS, taps.widened())
    assert m.has_pitch and min(m.polygon[1::2]) > 0.3


def test_the_coachs_sample_finds_a_dull_pitch_the_fixed_colors_miss():
    from ppcoachtracker_worker import tiles
    from ppcoachtracker_worker.pipeline import grass_around_box

    # Artificial turf at dusk (RGB 55, 70, 58: saturation 55, under the fixed 60) below the stands.
    img = np.zeros((360, 640, 3), np.uint8)
    img[:] = STANDS[::-1]
    img[120:] = (58, 70, 55)
    assert not pitch.measure(img).has_pitch
    sample = pitch.sample_of({"hue": [60, 72], "sat": [40, 75], "val": [50, 90]})
    m = pitch.measure(img, pitch.GRASS, sample)
    assert m.has_pitch and min(m.polygon[1::2]) > 0.3
    region = tiles.grass_region(img, sample=sample)
    assert region is not None and region[300, 320] == 255 and region[60, 320] == 0
    ball = TrackRow(frame=0, t_ms=0, source_frame=0, track_id=1, label="ball", x=0.6, y=0.6, w=0.01, h=0.01)
    assert grass_around_box(img, ball, pitch.GRASS, sample) > 0.9 and grass_around_box(img, ball) == 0.0
    # The same whole numbers as the app (data/SurfaceSample.kt): its tap on sunny dirt 0xB99673 gives these ranges.
    tap = pitch.sample_of({"hue": [11, 19], "sat": [76, 116], "val": [155, 215]})
    cells = pitch.sample_cells(np.array([[[0xB9, 0x96, 0x73], [0x8C, 0x71, 0x55]]]), tap)
    assert cells.tolist() == [[True, False]]
    # Used with a margin on each side, inside OpenCV's limits.
    assert pitch.SurfaceSample((1, 178), (40, 75), (240, 250)).widened() == pitch.SurfaceSample((0, 180), (20, 95), (220, 255))
    # Not usable: upside down, out of range, missing, not whole numbers.
    for bad in ({"hue": [20, 10], "sat": [0, 255], "val": [0, 255]}, {"hue": [0, 200], "sat": [0, 255], "val": [0, 255]},
                {"hue": [0, 10], "sat": [0, 255]}, {"hue": [0, 10.5], "sat": [0, 255], "val": [0, 255]}, [1, 2], None):
        assert pitch.sample_of(bad) is None
