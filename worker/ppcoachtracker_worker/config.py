"""Configuration from environment variables (names in .env.example)."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from .types import ConfigError

DEFAULT_META_URL = "https://api.meta.ai/v1"


def load_dotenv(path: Path) -> None:
    """Loads KEY=VALUE lines into os.environ without overriding what is set."""
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        os.environ.setdefault(key.strip(), value)


@dataclass(frozen=True)
class Config:
    api_url: str
    worker_secret: str
    poll_seconds: float
    meta_base_url: str
    sam_base_url: str
    api_key: str
    sam_model: str
    muse_model: str
    max_frames_per_job: int
    cache_dir: Path
    work_dir: Path
    # The URL that counts as real Meta (tests point it at their fake API).
    meta_reference_url: str = DEFAULT_META_URL
    # Saved runs for the viewer (debug.py); None = do not save.
    debug_dir: Path | None = None
    # SAM concepts (measured with tools/compare_runs.py; worker/README.md).
    player_concept: str = "player in {color} jersey"
    ball_concept: str = "soccer ball"
    # The pitch lines with SAM on key frames (calibration/sam_lines.py); off by default.
    pitch_sam: bool = False
    line_concept: str = "white line"

    @property
    def sam_is_meta(self) -> bool:
        """False when SAM points elsewhere (the local simulator in development)."""
        return self.sam_base_url.rstrip("/") == self.meta_reference_url.rstrip("/")

    @property
    def muse_is_meta(self) -> bool:
        return self.meta_base_url.rstrip("/") == self.meta_reference_url.rstrip("/")

    def require_api(self) -> None:
        if not self.api_url:
            raise ConfigError("PP_COACH_TRACKER_API_URL is not set")
        if not self.worker_secret:
            raise ConfigError("WORKER_CALLBACK_SECRET is not set")


def from_env(env: dict[str, str] | None = None) -> Config:
    e = os.environ if env is None else env
    api_key = e.get("MODEL_API_KEY", "").strip()
    if not api_key:
        # No fallback without Meta: the worker refuses to start.
        raise ConfigError("MODEL_API_KEY is not set (the worker has no local fallback)")
    meta = (e.get("META_API_BASE_URL") or DEFAULT_META_URL).rstrip("/")
    try:
        poll = float(e.get("POLL_SECONDS") or 10)
        max_frames = int(e.get("META_MAX_FRAMES_PER_JOB") or 15000)
    except ValueError as ex:
        raise ConfigError(f"invalid number: {ex}") from None
    return Config(
        api_url=(e.get("PP_COACH_TRACKER_API_URL") or "").rstrip("/"),
        worker_secret=e.get("WORKER_CALLBACK_SECRET", ""),
        poll_seconds=poll,
        meta_base_url=meta,
        sam_base_url=(e.get("SAM_API_BASE_URL") or meta).rstrip("/"),
        api_key=api_key,
        sam_model=e.get("SAM_MODEL") or "sam-3.1",
        muse_model=e.get("MUSE_SPARK_MODEL") or "muse-spark-1.3",
        max_frames_per_job=max_frames,
        cache_dir=Path(e.get("META_CACHE_DIR") or ".local/meta_cache"),
        work_dir=Path(e.get("WORK_DIR") or ".local/jobs"),
        debug_dir=Path(e["DEBUG_DIR"]) if e.get("DEBUG_DIR") else None,
        player_concept=e.get("SAM_PLAYER_CONCEPT") or "player in {color} jersey",
        ball_concept=e.get("SAM_BALL_CONCEPT") or "soccer ball",
        pitch_sam=(e.get("PP_PITCH_SAM") or "0").strip() == "1",
        line_concept=e.get("SAM_LINE_CONCEPT") or "white line",
    )
