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
