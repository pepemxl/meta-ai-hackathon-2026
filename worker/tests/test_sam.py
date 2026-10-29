import pytest

from fakes import make_clip
from ppcoachtracker_worker.chunks import load_clip
from ppcoachtracker_worker.meta_api import MetaClient, ResponseCache, Usage, cache_key
from ppcoachtracker_worker.sam import SamSegmenter, concepts_for, parse_stream, stitch

CONCEPTS = ["player in red jersey", "player in blue jersey", "soccer ball"]


def test_parser_reads_normalized_and_pixel_boxes():
    text = "<0f> 1 <|box;x1=0.1;y1=0.2;x2=0.3;y2=0.6;c=0.9|> 2 <|box;x1=640;y1=360;x2=704;y2=432;c=0.5|><1f>"
    frames = parse_stream(text, 1280, 720)
    (o1, b1, c1), (o2, b2, c2) = frames[0]
    assert (o1, c1) == (1, 0.9)
    assert b1 == pytest.approx((0.1, 0.2, 0.2, 0.4))
    assert b2 == pytest.approx((0.5, 0.5, 0.05, 0.1))
    assert 1 not in frames  # an empty frame


def test_parser_tolerates_missing_ordinals_and_cut_streams():
    text = "noise <|box;x1=0.1;y1=0.1;x2=0.2;y2=0.2|> <3f><|box;x1=0.1;y1=0.1;x2=0.2;y2=0.3|> 7: <|box;x1=0.5;y1=0.5;x2=0.6;y2=0.7;c=0.8|> <4f> 1 <|box;x1=0.1;y1=0.1;x2"
    frames = parse_stream(text, 1280, 720)
    assert list(frames) == [3]  # before the first mark: ignored; unfinished box: dropped
    assert [o for o, _, _ in frames[3]] == [1, 7]
    assert frames[3][0][2] == 1.0  # no c → 1.0


def test_parser_reads_the_documented_records_and_skips_the_masks():
    # dev.meta.ai/docs/sam/reading-segmentation: id, box in pixels with w and h, mask; records split by ",".
    # The base85 payloads hold what looks like a frame mark, an id and a box end.
    text = ("<0f>0<|box;x1=211;y1=228;x2=270;y2=254;w=320;h=334;c=0.91|><|mask;x=0;y=0;c=0.9;data=27,60,!a<3f>b,4|>c|>"
            ",2<|box;x1=155;y1=228;x2=202;y2=254;w=320;h=334|><|mask;x=0;y=0;data=27,48,!<7f>|>\n"
            "<5f>2<|box;x1=150;y1=230;x2=200;y2=256;w=320;h=334|><|mask;x=0;y=0;data=27,51,!x<9f")
    frames = parse_stream(text, 320, 334)
    assert sorted(frames) == [0, 5]
    assert [(o, c) for o, _, c in frames[0]] == [(0, 0.91), (2, 1.0)]
    assert frames[0][0][1] == pytest.approx((211 / 320, 228 / 334, 59 / 320, 26 / 334))
    assert [o for o, _, _ in frames[5]] == [2]  # the cut mask at the end is dropped


@pytest.mark.parametrize("overlap", [20, 50])
def test_stitch_joins_tracks_on_the_overlap(overlap):
    prev, cur = [], []
    for k in range(overlap):
        src = 1000 + k * 3
        prev += [(src, 1, "player", (0.1, 0.1, 0.1, 0.2)), (src, 2, "player", (0.5, 0.5, 0.1, 0.2)), (src, 3, "ball", (0.3, 0.3, 0.02, 0.02))]
        # Same people with other SAM ordinals; slightly moved boxes.
        cur += [(src, (0, 9), "player", (0.101, 0.1, 0.1, 0.2)), (src, (1, 4), "player", (0.5, 0.505, 0.1, 0.2)), (src, (2, 1), "ball", (0.3, 0.3, 0.02, 0.02))]
        if k < 4:
            cur.append((src, (0, 5), "player", (0.8, 0.8, 0.05, 0.1)))  # only 4 frames: a new track
    m = stitch(prev, cur)
    assert m == {(0, 9): 1, (1, 4): 2, (2, 1): 3}


def test_stitch_needs_half_of_the_shared_frames():
    prev = [(k, 1, "player", (0.1, 0.1, 0.1, 0.2)) for k in range(20)]
    cur = [(k, (0, 1), "player", (0.1, 0.1, 0.1, 0.2) if k < 6 else (0.6, 0.6, 0.1, 0.2)) for k in range(20)]
    assert stitch(prev, cur) == {}


def _segmenter(meta, tmp_path, is_meta=True):
    usage = Usage()
    client = MetaClient(meta.url, "k", "meta_sam", usage, ResponseCache(tmp_path / "cache"), sleep=lambda s: None)
    return SamSegmenter(client, "sam-3.1", tmp_path / "raw", is_meta_url=is_meta), usage


def test_tracks_two_chunks_and_joins_them(meta, tmp_path):
    clip_dir = make_clip(tmp_path / "clip", total_ms=12000, chunk_seconds=7, overlap_ms=2000)
    meta.register_clip(clip_dir, CONCEPTS, ordinal_offsets={2: 10})
    seg, usage = _segmenter(meta, tmp_path)
    ts = seg.track(load_clip(clip_dir), "red", "blue")
    assert seg.segmenter == "meta_sam"
    tracks = ts.by_track()
    # 3 + 3 players and the ball, each one track across both chunks.
    assert len(tracks) == 7
    teams = sorted(rs[0].team for rs in tracks.values() if rs[0].label == "player")
    assert teams == ["A", "A", "A", "B", "B", "B"]
    clip = load_clip(clip_dir)
    for rs in tracks.values():
        assert len(rs) == clip.unique_frames  # every frame once, overlap not repeated
    assert usage.frames_sent == 3 * clip.frames_sent_per_concept
    assert len(seg.streams) == 6


def test_simulator_header_marks_sam_simulator(meta, tmp_path):
    clip_dir = make_clip(tmp_path / "clip", total_ms=3000, chunk_seconds=60, overlap_ms=2000)
    meta.register_clip(clip_dir, CONCEPTS)
    meta.simulator = True
    seg, _ = _segmenter(meta, tmp_path)
    seg.track(load_clip(clip_dir), "red", "blue")
    assert seg.segmenter == "sam_simulator"


def test_another_base_url_is_the_simulator_even_without_the_header(meta, tmp_path):
    clip_dir = make_clip(tmp_path / "clip", total_ms=3000, chunk_seconds=60, overlap_ms=2000)
    meta.register_clip(clip_dir, CONCEPTS)
    seg, _ = _segmenter(meta, tmp_path, is_meta=False)
    seg.track(load_clip(clip_dir), "red", "blue")
    assert seg.segmenter == "sam_simulator"


def test_cache_key_depends_on_the_base_url():
    a = cache_key("http://sam-sim:8090/v1", "sha", "sam-3.1", "soccer ball")
    b = cache_key("https://api.meta.ai/v1", "sha", "sam-3.1", "soccer ball")
    assert a != b
    assert a == cache_key("http://sam-sim:8090/v1/", "sha", "sam-3.1", "soccer ball")


def test_the_cache_avoids_the_second_call(meta, tmp_path):
    clip_dir = make_clip(tmp_path / "clip", total_ms=3000, chunk_seconds=60, overlap_ms=2000)
    meta.register_clip(clip_dir, CONCEPTS)
    seg, usage = _segmenter(meta, tmp_path)
    seg.track(load_clip(clip_dir), "red", "blue")
    calls = len(meta.sam_calls())
    seg2, usage2 = _segmenter(meta, tmp_path)
    ts = seg2.track(load_clip(clip_dir), "red", "blue")
    assert len(meta.sam_calls()) == calls
    assert usage2.cached == 3 and usage2.calls == 0 and usage2.frames_sent == 0
    assert len(ts.by_track()) == 7


def test_same_colors_use_the_generic_concept_and_the_color_decides(meta, tmp_path):
    assert [c.text for c in concepts_for("red", "red")] == ["soccer player", "soccer ball"]
    assert [c.text for c in concepts_for("light_blue", "Black")] == ["player in light blue jersey", "player in black jersey", "soccer ball"]
    clip_dir = make_clip(tmp_path / "clip", total_ms=3000, chunk_seconds=60, overlap_ms=2000)
    meta.register_clip(clip_dir, ["soccer player", "soccer ball"])
    seg, _ = _segmenter(meta, tmp_path)
    # "red" vs "unknown" → generic concept; red players → A, the rest has no team.
    ts = seg.track(load_clip(clip_dir), "red", "unknown")
    players = [rs[0] for rs in ts.by_track().values() if rs[0].label == "player"]
    assert sorted(p.team for p in players) == ["", "", "", "", "A", "A", "A"]
