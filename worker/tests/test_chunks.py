from fakes import make_clip
from ppcoachtracker_worker.chunks import index_yaml, load_clip, plan_cuts, read_yaml


def test_reads_the_opencv_yaml_header(tmp_path):
    p = tmp_path / "x.yaml"
    p.write_text('%YAML:1.0\n---\nversion: 1\nchunk:\n  number: 2\nframes:\n  map:\n    - [0, 8901, 297000]\nsha256: "ab"\n', encoding="utf-8")
    d = read_yaml(p)
    assert d["chunk"]["number"] == 2
    assert d["frames"]["map"][0] == [0, 8901, 297000]


def test_two_chunks_are_read_as_one_video_without_repeating_the_overlap(tmp_path):
    clip = load_clip(make_clip(tmp_path / "clip", total_ms=12000, chunk_seconds=7, overlap_ms=2000))
    assert len(clip.chunks) == 2
    c1, c2 = clip.chunks
    assert c2.overlap_frames == 20
    # The overlap repeats exactly the same frames of the original (app alignment).
    assert [m[1] for m in c1.map[-20:]] == [m[1] for m in c2.map[:20]]
    infos = clip.frame_infos()
    assert len(infos) == clip.unique_frames == c1.frame_count + c2.frame_count - 20
    times = [f.t_ms for f in infos]
    assert times == sorted(set(times))
    assert [f.frame for f in infos] == list(range(len(infos)))
    assert clip.global_index(c2, 5) is None
    assert clip.global_index(c2, 20) == c1.frame_count


def test_time_is_extrapolated_past_the_map(tmp_path):
    clip = load_clip(make_clip(tmp_path / "clip", total_ms=3000, chunk_seconds=60, overlap_ms=2000))
    c = clip.chunks[0]
    last = c.map[-1]
    assert c.time_of(last[0] + 3) == last[2] + 300


def test_profiles_are_read_from_chunks_yaml(tmp_path):
    for profile, seconds, overlap in (("dev", 60, 2000), ("demo", 300, 3000)):
        d = tmp_path / profile
        d.mkdir()
        (d / "chunks.yaml").write_text(index_yaml(1, profile, seconds, overlap, [{"number": 1, "file": "video_part_001.mp4", "start_ms": 0, "end_ms": 1000, "frames": 10}]))
        (d / "video_part_001.mp4").write_bytes(b"")
        (d / "video_part_001.yaml").write_text("%YAML:1.0\n---\nchunk:\n  start_ms: 0\n  end_ms: 1000\nframes:\n  count: 10\n  map: []\n")
        clip = load_clip(d)
        assert (clip.profile, clip.chunk_seconds, clip.overlap_ms) == (profile, seconds, overlap)


def test_cut_plan_matches_the_docs():
    assert plan_cuts(75_000, 60, 2000) == [(0, 60_000), (58_000, 75_000)]
    assert plan_cuts(492_012, 300, 3000) == [(0, 300_000), (297_000, 492_012)]
    # 5:04 in the demo profile: the 4 s left over join the first chunk.
    assert plan_cuts(304_000, 300, 3000) == [(0, 304_000)]
    assert plan_cuts(30_000, 60, 2000) == [(0, 30_000)]
