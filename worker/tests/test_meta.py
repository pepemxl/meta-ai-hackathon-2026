import json
import subprocess
import sys
from pathlib import Path

import pytest

from conftest import KEY
from fakes import valid_insights
from ppcoachtracker_worker.config import from_env
from ppcoachtracker_worker.insights import MuseSpark, validate
from ppcoachtracker_worker.meta_api import MetaClient, ResponseCache, Usage
from ppcoachtracker_worker.types import ConfigError, MetaError

METRICS = {
    "frames": 50, "duration_ms": 5000, "players_per_frame": 7.0, "unassigned_per_frame": 0.5,
    "ball": {"seen_frames": 40, "seen_ratio": 0.8}, "possession_changes": 1, "possession_changes_per_minute": 12.0,
    "events": [{"type": "possession_change", "frame": 20, "t_ms": 2000, "from": "A", "to": "B"}],
    "teams": {t: {"players_per_frame": 3.5, "max_players": 4, "tracks": 4, "shape": None, "possession": 0.5,
                  "longest_possession_ms": 2000, "pressers_per_frame": 0.5} for t in ("A", "B")},
}
MATCH = {"home": "Tigres", "away": "Halcones", "home_color": "red", "away_color": "blue"}


def client(meta, tmp_path, sleeps=None, service="muse_spark"):
    return MetaClient(meta.url, KEY, service, Usage(), ResponseCache(tmp_path / "cache"), sleep=(sleeps.append if sleeps is not None else lambda s: None))


def test_validation_of_the_structured_output():
    assert validate(valid_insights(), 5000) is None
    bad = valid_insights()
    bad["insights"][0]["severity"] = "critical"
    assert "severity" in validate(bad, 5000)
    bad = valid_insights()
    bad["insights"][0]["t_end_ms"] = 99_000
    assert "outside" in validate(bad, 5000)
    assert "unexpected" in validate({**valid_insights(), "extra": 1}, 5000)


def test_muse_spark_repairs_once(meta, tmp_path):
    meta.muse = ["this is not json", json.dumps(valid_insights())]
    out = MuseSpark(client(meta, tmp_path), "muse-spark-1.3", tmp_path / "raw").insights(METRICS, MATCH, 0)
    assert out["summary"]
    muse_calls = [b for p, b in meta.calls if isinstance(b, dict) and str(b.get("model", "")).startswith("muse")]
    assert len(muse_calls) == 2
    assert "no es válida" in muse_calls[1]["input"][-1]["content"]
    assert muse_calls[0]["text"]["format"]["strict"] is True
    # Only aggregated numbers go to Meta: no heatmap, no tracks.
    sent = muse_calls[0]["input"][1]["content"]
    assert "heatmap" not in sent and "tracks\": [" not in sent
    assert (tmp_path / "raw" / "muse_spark_repair.json").is_file()


def test_muse_spark_fails_after_a_failed_repair(meta, tmp_path):
    meta.muse = ["{}", "{\"summary\": \"x\"}"]
    with pytest.raises(MetaError) as e:
        MuseSpark(client(meta, tmp_path), "muse-spark-1.3").insights(METRICS, MATCH, 0)
    assert e.value.service == "muse_spark"


def test_retries_429_and_500_respecting_retry_after(meta, tmp_path):
    meta.failures = [(429, {"Retry-After": "7"}), (500, {}), (503, {})]
    sleeps = []
    c = client(meta, tmp_path, sleeps)
    out = MuseSpark(c, "muse-spark-1.3").insights(METRICS, MATCH, 0)
    assert out["insights"]
    assert sleeps == [7.0, 2.0, 4.0]
    assert c.usage.retries == 3 and c.usage.calls == 4


def test_gives_up_after_three_retries(meta, tmp_path):
    meta.failures = [(500, {})] * 4
    with pytest.raises(MetaError) as e:
        MuseSpark(client(meta, tmp_path), "muse-spark-1.3").insights(METRICS, MATCH, 0)
    assert "after 4 attempts" in str(e.value)


def test_401_fails_without_fallback_and_without_the_key(meta, tmp_path):
    meta.failures = [(401, {})]
    sleeps = []
    with pytest.raises(MetaError) as e:
        MuseSpark(client(meta, tmp_path, sleeps, service="meta_sam"), "muse-spark-1.3").insights(METRICS, MATCH, 0)
    assert e.value.service == "meta_sam"
    assert "401" in str(e.value) and KEY not in str(e.value)
    assert sleeps == []  # not retried


def test_cut_stream_is_retried(meta, tmp_path):
    meta.cut_next = 1
    c = MetaClient(meta.url, KEY, "meta_sam", Usage(), ResponseCache(None), sleep=lambda s: None)
    fid, _ = c.upload(Path(__file__), "sha-of-this-file")
    resp = c.respond({"model": "sam-3.1", "input": [{"role": "user", "content": [{"type": "input_text", "text": "soccer ball"}, {"type": "input_video", "file_id": fid}]}]}, "k1", {})
    assert resp.text == ""  # no stream registered for this file: SAM found nothing
    assert c.usage.retries == 1


def test_without_model_api_key_the_worker_does_not_start(tmp_path):
    with pytest.raises(ConfigError):
        from_env({"PP_COACH_TRACKER_API_URL": "http://x"})
    root = Path(__file__).resolve().parents[1]
    r = subprocess.run([sys.executable, "-m", "ppcoachtracker_worker", "once"], cwd=tmp_path, capture_output=True, text=True,
                       env={"PYTHONPATH": str(root), "PATH": "/usr/bin:/bin"})
    assert r.returncode == 2
    assert "MODEL_API_KEY" in r.stderr
