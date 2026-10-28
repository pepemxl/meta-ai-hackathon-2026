import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from fakes import FakeMeta  # noqa: E402
from ppcoachtracker_worker.config import Config  # noqa: E402

KEY = "test-key-do-not-print-0123456789"


@pytest.fixture
def meta():
    fake = FakeMeta().start()
    yield fake
    fake.stop()


def make_config(tmp_path: Path, meta_url: str, sam_url: str | None = None, reference: str | None = None, **over) -> Config:
    values = dict(
        api_url="",
        worker_secret="worker-secret",
        poll_seconds=0.1,
        meta_base_url=meta_url,
        sam_base_url=sam_url or meta_url,
        api_key=KEY,
        sam_model="sam-3.1",
        muse_model="muse-spark-1.3",
        max_frames_per_job=15000,
        cache_dir=tmp_path / "cache",
        work_dir=tmp_path / "jobs",
        meta_reference_url=reference or meta_url,
    )
    values.update(over)
    return Config(**values)


@pytest.fixture
def config_for(tmp_path):
    return lambda *a, **k: make_config(tmp_path, *a, **k)
