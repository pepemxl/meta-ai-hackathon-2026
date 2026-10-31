import json

from fakes import FakePpApi, make_clip
from ppcoachtracker_worker.api import PpApi
from ppcoachtracker_worker.pipeline import analyze, run_job

CONCEPTS = ["player in red jersey", "player in blue jersey", "soccer ball"]
MATCH = {"home": "Tigres", "away": "Halcones", "home_color": "red", "away_color": "blue", "date": "2026-10-25", "venue": "Cancha 3"}


def job_for(clip_dir, chunks):
    files = ["chunks.yaml"] + [f"video_part_{n:03d}.{ext}" for n in range(1, chunks + 1) for ext in ("mp4", "yaml")]
    return {"job_id": 12, "type": "segment", "clip": {"id": 7, "title": "Jugada", "duration_ms": 12000, "chunk_count": chunks, "files": files}, "match": MATCH}


def test_a_full_job_against_a_fake_api(meta, tmp_path, config_for):
    clip_dir = make_clip(tmp_path / "clip", total_ms=12000, chunk_seconds=7, overlap_ms=2000)
    meta.register_clip(clip_dir, CONCEPTS, ordinal_offsets={2: 5})
    pp = FakePpApi(clip_dir, job_for(clip_dir, 2)).start()
    try:
        cfg = config_for(meta.url, api_url=pp.url)
        api = PpApi(pp.url, "worker-secret")
        assert run_job(api, cfg, api.next_job(), sleep=lambda s: None) == "done"
        assert api.next_job() is None
    finally:
        pp.stop()
    (res,) = pp.results
    assert res["status"] == "done"
    a = res["analysis"]
    assert a["version"] == 1 and a["clip_id"] == 7 and a["job_id"] == 12
    assert a["backends"]["segmenter"] == "meta_sam" and a["backends"]["analyst"] == "muse_spark"
    assert a["backends"]["usage"]["frames_sent"] > 0
    assert a["video"]["chunks"] == 2 and a["video"]["chunk_seconds"] == 7 and a["video"]["overlap_ms"] == 2000
    assert a["coordinates"] == "screen"
    assert a["video"]["frames_used"] == a["video"]["frames"] and a["video"]["discarded"] == []
    assert any("Máscara de la cancha" in n for n in a["notes"])
    assert a["teams"]["A"]["tracks"] == 3 and a["teams"]["B"]["tracks"] == 3
    assert a["insights"][0]["source"] == "muse_spark" and a["insights"][0]["players"] == []
    assert a["match"] == {k: MATCH[k] for k in ("home", "away", "home_color", "away_color")}
    assert not (cfg.work_dir / "job_000012").exists()  # cleaned after success
    json.dumps(a)


def test_the_play_area_drops_the_people_outside_it_with_a_still_camera(meta, tmp_path, config_for):
    clip_dir = make_clip(tmp_path / "clip", total_ms=12000, chunk_seconds=7, overlap_ms=2000)
    meta.register_clip(clip_dir, CONCEPTS, ordinal_offsets={2: 5})
    # The left 65 % of the frame: blue 2 and 3 (feet at x ≈ 0.70 and 0.82) stay out, even with the margin.
    area = {"points": [{"x": 0.0, "y": 0.0}, {"x": 0.65, "y": 0.0}, {"x": 0.65, "y": 1.0}, {"x": 0.0, "y": 1.0}], "margin": 0.02}
    results = {}
    for camera in ("static", "moving"):
        job = {**job_for(clip_dir, 2), "options": {"camera": camera, "play_area": area}}
        pp = FakePpApi(clip_dir, job).start()
        try:
            api = PpApi(pp.url, "worker-secret")
            assert run_job(api, config_for(meta.url, api_url=pp.url), api.next_job(), sleep=lambda s: None) == "done"
        finally:
            pp.stop()
        results[camera] = pp.results[0]["analysis"]
    a = results["static"]
    assert a["teams"]["A"]["tracks"] == 3 and a["teams"]["B"]["tracks"] == 1
    assert a["video"]["play_area"]["points"] == 4 and a["video"]["play_area"]["margin"] == 0.02
    assert a["video"]["play_area"]["removed"] > 0
    assert any("Zona de juego marcada" in n for n in a["notes"]) and not any("Máscara de la cancha" in n for n in a["notes"])
    # A moving camera: the area means nothing on the next frames, so it is not used.
    m = results["moving"]
    assert m["teams"]["B"]["tracks"] == 3 and "play_area" not in m["video"]
    assert any("solo se usa con la cámara fija" in n for n in m["notes"])


def test_a_job_with_a_missing_chunk_fails_and_keeps_its_files(meta, tmp_path, config_for):
    clip_dir = make_clip(tmp_path / "clip", total_ms=12000, chunk_seconds=7, overlap_ms=2000)
    (clip_dir / "video_part_002.mp4").unlink()
    pp = FakePpApi(clip_dir, job_for(clip_dir, 2)).start()
    try:
        cfg = config_for(meta.url, api_url=pp.url)
        api = PpApi(pp.url, "worker-secret")
        assert run_job(api, cfg, api.next_job(), sleep=lambda s: None) == "failed"
    finally:
        pp.stop()
    (res,) = pp.results
    assert res["status"] == "failed"
    assert res["error"].startswith("input: cannot download video_part_002.mp4")
    assert (cfg.work_dir / "job_000012").exists()
    assert meta.calls == []  # failed before spending credits


def test_meta_401_fails_the_job_with_meta_sam(meta, tmp_path, config_for):
    clip_dir = make_clip(tmp_path / "clip", total_ms=3000, chunk_seconds=60, overlap_ms=2000)
    meta.failures = [(401, {})]
    pp = FakePpApi(clip_dir, job_for(clip_dir, 1)).start()
    try:
        cfg = config_for(meta.url, api_url=pp.url)
        api = PpApi(pp.url, "worker-secret")
        assert run_job(api, cfg, api.next_job()) == "failed"
    finally:
        pp.stop()
    err = pp.results[0]["error"]
    assert err.startswith("meta_sam: ") and "401" in err
    assert "test-key" not in err


def test_too_many_frames_fails_before_calling_meta(meta, tmp_path, config_for):
    clip_dir = make_clip(tmp_path / "clip", total_ms=3000, chunk_seconds=60, overlap_ms=2000)
    cfg = config_for(meta.url, max_frames_per_job=10)
    try:
        analyze(clip_dir, MATCH, cfg)
        raise AssertionError("expected InputError")
    except Exception as e:  # noqa: BLE001
        assert type(e).__name__ == "InputError" and "META_MAX_FRAMES_PER_JOB" in str(e)
    assert meta.calls == []


def test_simulator_results_are_marked(meta, tmp_path, config_for):
    clip_dir = make_clip(tmp_path / "clip", total_ms=3000, chunk_seconds=60, overlap_ms=2000)
    meta.register_clip(clip_dir, CONCEPTS)
    meta.simulator = True
    result, _ = analyze(clip_dir, MATCH, config_for(meta.url), raw_dir=tmp_path / "raw")
    assert result["backends"]["segmenter"] == "sam_simulator"
    assert result["backends"]["analyst"] == "muse_spark_simulator"
    assert result["limitations"][0].startswith("Análisis de desarrollo")
    assert (tmp_path / "raw" / "tracks.json").is_file()


def test_a_run_is_saved_for_the_viewer(meta, tmp_path, config_for):
    from ppcoachtracker_worker.chunks import load_clip
    from ppcoachtracker_worker.debug import save_run

    clip_dir = make_clip(tmp_path / "clip", total_ms=12000, chunk_seconds=7, overlap_ms=2000)
    meta.register_clip(clip_dir, CONCEPTS)
    result, ts = analyze(clip_dir, MATCH, config_for(meta.url))
    run = save_run(tmp_path / "runs", load_clip(clip_dir), result, ts, name="r1")
    data = json.loads((run / "tracks.json").read_text())
    assert data["columns"][:3] == ["frame", "t_ms", "track"]
    assert [c["skip"] for c in data["chunks"]] == [0, 20]
    assert data["chunks"][1]["base"] == data["chunks"][0]["frames"]
    assert len(data["frames"]) == result["frames"]
    assert sorted(p.name for p in (run / "chunks").iterdir()) == ["video_part_001.mp4", "video_part_001.yaml", "video_part_002.mp4", "video_part_002.yaml"]
    assert json.loads((run / "result.json").read_text())["backends"]["segmenter"] == "meta_sam"


def test_compare_runs_and_rescore_read_a_saved_run(meta, tmp_path, config_for, capsys):
    import sys as _sys
    from pathlib import Path as _P

    _sys.path.insert(0, str(_P(__file__).resolve().parents[1] / "tools"))
    import compare_runs
    import rescore
    from ppcoachtracker_worker.chunks import load_clip
    from ppcoachtracker_worker.debug import save_run

    clip_dir = make_clip(tmp_path / "clip", total_ms=12000, chunk_seconds=7, overlap_ms=2000)
    meta.register_clip(clip_dir, CONCEPTS)
    result, ts = analyze(clip_dir, MATCH, config_for(meta.url))
    run = save_run(tmp_path / "runs", load_clip(clip_dir), result, ts, name="r1", masks=analyze.last_masks, raw=analyze.last_raw)
    assert compare_runs.main([str(run)]) == 0
    assert "players/frame" in capsys.readouterr().out
    assert rescore.main([str(run)]) == 0
    out = capsys.readouterr().out
    # The same inputs give the same numbers.
    line = next(l for l in out.splitlines() if l.startswith("| frames used"))
    _, _, a, b, _ = line.split("|")
    assert a.strip() == b.strip()
