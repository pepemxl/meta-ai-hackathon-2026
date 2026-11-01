import pytest

from fakes import make_clip
from ppcoachtracker_worker import tiles
from ppcoachtracker_worker.chunks import load_clip
from ppcoachtracker_worker.meta_api import MetaClient, ResponseCache, Usage
from ppcoachtracker_worker.sam import SamSegmenter


def test_only_wide_or_very_large_video_is_tiled():
    assert not tiles.should_tile("auto", 1280, 720)
    assert not tiles.should_tile("auto", 1920, 1080)
    assert tiles.should_tile("auto", 3840, 2160)  # 4K: over twice what SAM sees
    assert tiles.should_tile("auto", 3840, 800)  # a panorama
    assert tiles.should_tile("auto", 2000, 700)  # wide even if small
    assert not tiles.should_tile("off", 3840, 800)
    assert tiles.should_tile("on", 1280, 720)


def test_the_plan_covers_the_pitch_with_the_fewest_tiles():
    # The 3840x800 panorama of docs/results: pitch 3357 px wide from x = 193.
    p = tiles.plan(3840, 800, (193.2, 89.9, 3550.1, 800.0))
    assert len(p.tiles) == 3
    assert p.scale == pytest.approx(0.8389, abs=1e-4)
    assert p.overlap_px == 128
    assert [t.out_w for t in p.tiles] == [1024, 1024, 1024]
    assert p.tiles[0].x == pytest.approx(193.2, abs=0.2)
    assert p.tiles[-1].x + p.tiles[-1].w == pytest.approx(3550, abs=1)
    # A pitch that fits in one tile at 80 % or more: no tiles.
    assert tiles.plan(1280, 720, (0, 0, 1280, 720)) is None
    # Never more than MAX_TILES, even if the scale stays low.
    assert len(tiles.plan(9000, 800, (0, 0, 9000, 800)).tiles) == tiles.MAX_TILES


def _walker(t: int) -> tuple[float, float, float, float]:
    """One player that walks from the first tile across the overlap into the second."""
    return (0.30 + t * 0.0025, 0.45, 0.02, 0.12)  # about 8 px per frame at 10 fps


def test_a_wide_clip_goes_to_sam_in_tiles_and_a_player_keeps_one_track(meta, tmp_path):
    clip_dir = make_clip(tmp_path / "wide", 3000, 60, 2000, width=3200, height=600)
    clip = load_clip(clip_dir)
    usage = Usage()
    client = MetaClient(meta.url, "k", "meta_sam", usage, ResponseCache(None))
    seg = SamSegmenter(client, "sam-3.1", tile_mode="auto")
    plan = seg.plan_tiles(clip)
    assert plan is not None and len(plan.tiles) == 3
    chunk = clip.chunks[0]
    paths = tiles.write_tiles(chunk.video, plan, chunk.video.parent / "tiles", "chunk_001", chunk.fps)
    import hashlib

    # SAM answers per tile: the walker, in each tile's own coordinates, while its box is inside the tile.
    for t, path in zip(plan.tiles, paths):
        sha = hashlib.sha256(path.read_bytes()).hexdigest()
        parts = []
        for k in range(chunk.frame_count):
            parts.append(f"<{k}f>")
            x, y, w, h = _walker(k)
            px, py, pw, ph = x * 3200, y * 600, w * 3200, h * 600
            if t.x <= px and px + pw <= t.x + t.w:
                tx, ty = (px - t.x) / t.w, (py - t.y) / t.h
                parts.append(f" 1 <|box;x1={tx:.5f};y1={ty:.5f};x2={tx + pw / t.w:.5f};y2={ty + ph / t.h:.5f};c=0.9|>")
        meta.streams[(sha, "player in red jersey")] = "".join(parts)
    ts = seg.track(clip, "red", "blue")
    walker = [r for r in ts.rows if r.label == "player"]
    # One track for the whole walk, one box per frame, in the whole frame's coordinates.
    assert len({r.track_id for r in walker}) == 1
    assert len(walker) == len({r.frame for r in walker}) == chunk.frame_count
    assert walker[-1].x == pytest.approx(_walker(chunk.frame_count - 1)[0], abs=0.002)
    assert seg.tile_joins == 1
    # 3 tiles × 3 concepts, each tile charged as a chunk.
    assert len(meta.sam_calls()) == 9
    assert usage.frames_sent == 9 * chunk.frame_count


def test_a_16_9_video_with_players_too_small_for_sam_is_tiled_too():
    # Medians measured in docs/results/01: the tactical camera (39 px), the broadcast (53 px), the panorama (36 px).
    assert tiles.reason("auto", 1920, 1080, 39) == "small"  # SAM would see 21 px
    assert tiles.reason("auto", 1920, 1080, 53) is None  # 28 px: SAM found them
    assert tiles.reason("auto", 1920, 1080, None) is None  # not measured: as before
    assert tiles.reason("auto", 640, 360, 12) is None  # SAM does not shrink it: tiles would not help
    assert tiles.reason("auto", 3840, 800, 36) == "wide"
    assert tiles.reason("off", 1920, 1080, 10) is None and tiles.reason("on", 1920, 1080, None) == "on"
    # Two tiles over a 1920 px pitch bring the 39 px players to SAM at their size.
    need = min(1.0, tiles.MIN_SAM_PLAYER_PX / 39)
    p = tiles.plan(1920, 1080, (0, 97, 1920, 1080), min_scale=need)
    assert len(p.tiles) == 2 and p.scale == pytest.approx(1.0)
    assert p.tiles[0].y == pytest.approx(97) and p.tiles[0].out_h == 984


def test_the_player_size_sam_sees_is_measured_at_the_tiles_scale():
    # The emulator's copy of the panorama: 1920x400, players of 14 px, two tiles at scale 1.
    p = tiles.plan(1920, 400, (100, 80, 1820, 340))
    assert tiles.seen_px(14, 1920, 400, p) == pytest.approx(14 * p.scale)
    assert tiles.seen_px(14, 1920, 400, p) < tiles.MIN_SAM_PLAYER_PX
    # The original 3840x800: players of 31 px, tiles at 0.90, SAM sees them big enough.
    p = tiles.plan(3840, 800, (384, 160, 3456, 680))
    assert tiles.seen_px(31, 3840, 800, p) >= tiles.MIN_SAM_PLAYER_PX
    # Without tiles the frame shrinks to 1024 on its long side, and a small video is not enlarged.
    assert tiles.seen_px(39, 1920, 1080, None) == pytest.approx(39 * 1024 / 1920)
    assert tiles.seen_px(12, 640, 360, None) == 12
    assert tiles.seen_px(None, 1920, 1080, None) is None


def _tactical_frame(players: int = 40, h: int = 36):
    """A 1920x1080 frame: stands on top, grass below with a painted line and [players] people of [h] px."""
    import numpy as np

    img = np.zeros((1080, 1920, 3), np.uint8)
    img[:] = (90, 90, 160)  # stands
    img[200:] = (40, 140, 50)  # grass
    img[640:644, 100:1800] = (245, 245, 245)  # a line
    rng = np.random.default_rng(1)
    for _ in range(players):
        x, y = int(rng.integers(100, 1800)), int(rng.integers(260, 1000))
        img[y:y + h, x:x + h * 2 // 5] = (53, 57, 229)
    return img


def test_players_are_measured_as_upright_blobs_on_the_grass():
    img = _tactical_frame()
    pitch = (0.0, 0.185, 1.0, 0.185, 1.0, 1.0, 0.0, 1.0)
    heights = tiles.player_heights(img, pitch)
    assert len(heights) >= 30  # a few may touch each other; the line is not one
    assert tiles.median_player(heights) == pytest.approx(36, abs=2)
    assert tiles.median_player(heights[:5]) is None  # too few to say


def test_what_is_not_the_pitch_goes_black_but_the_heads_stay():
    img = _tactical_frame()
    pitch = (0.0, 0.25, 1.0, 0.25, 1.0, 1.0, 0.0, 1.0)  # the far touchline at y = 270
    margin = tiles.mask_margin(36, 1080)
    assert margin == pytest.approx(2 * 36 / 1080)
    out = tiles.mask_outside(img, pitch, margin)
    assert out[100, 960].sum() == 0  # the stands
    assert (out[240, 960] == img[240, 960]).all()  # 30 px above the line: a far player's head
    assert (out[700, 960] == img[700, 960]).all()
    assert tiles.mask_margin(None, 1080) == tiles.MIN_MASK_MARGIN


def test_the_grass_mask_leaves_out_a_press_area_between_two_grass_corners():
    img = _tactical_frame()
    img[960:, 300:1620] = (60, 60, 60)  # people at the bottom, grass on both sides of them
    up, around = tiles.grass_margins(36, 1080)
    assert (up, around) == (72, 18)
    out = tiles.mask_outside_grass(img, up, around)
    assert out[985, 960].sum() == 0  # the press area, 25 px below the grass
    assert (out[955, 960] == img[955, 960]).all()  # by the grass: kept
    assert out[100, 960].sum() == 0  # the stands
    assert (out[140, 960] == img[140, 960]).all()  # 60 px above the far line: the heads
    assert (out[1060, 100] == img[1060, 100]).all()  # the grass corner
    # The players inside the pitch are holes in the grass, filled: all kept.
    assert (out[260:960, 100:1800] == img[260:960, 100:1800]).all()
