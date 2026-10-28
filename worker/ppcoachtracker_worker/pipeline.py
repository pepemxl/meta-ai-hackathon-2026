"""One analysis: chunks → SAM 3.1 tracks → metrics → Muse Spark → result JSON.

There is no fallback. InputError fails the job before spending credits;
MetaError fails it with "<service>: <message>".
"""

from __future__ import annotations

import json
import logging
import shutil
import time
from datetime import datetime, timezone
from pathlib import Path

from . import VERSION, metrics as metrics_mod
from .api import ApiError, PpApi
from .chunks import load_clip
from .config import Config
from .insights import MuseSpark, warnings_of
from .meta_api import MetaClient, ResponseCache, Usage
from .sam import SamSegmenter
from .types import InputError, MetaError, TrackSet

log = logging.getLogger("ppcoachtracker_worker")


def analyze(clip_dir: Path, match: dict, cfg: Config, clip_id: int | None = None, job_id: int | None = None, raw_dir: Path | None = None, sleep=time.sleep) -> tuple[dict, TrackSet]:
    started = time.monotonic()
    clip = load_clip(clip_dir)
    if clip.frames_sent_per_concept > cfg.max_frames_per_job:
        raise InputError(f"{clip.frames_sent_per_concept} frames, more than META_MAX_FRAMES_PER_JOB={cfg.max_frames_per_job}")

    usage = Usage()
    cache = ResponseCache(cfg.cache_dir)
    sam_client = MetaClient(cfg.sam_base_url, cfg.api_key, "meta_sam", usage, cache, sleep=sleep)
    muse_client = MetaClient(cfg.meta_base_url, cfg.api_key, "muse_spark", usage, cache, sleep=sleep)
    segmenter = SamSegmenter(sam_client, cfg.sam_model, raw_dir, is_meta_url=cfg.sam_is_meta)
    home_color, away_color = match.get("home_color") or "", match.get("away_color") or ""

    trackset = segmenter.track(clip, home_color, away_color)
    if raw_dir is not None:
        save_tracks(trackset, raw_dir / "tracks.json")
    m = metrics_mod.compute(trackset, clip.fps)
    start_ms = trackset.frames[0].t_ms if trackset.frames else 0
    analyst = MuseSpark(muse_client, cfg.muse_model, raw_dir, is_meta_url=cfg.muse_is_meta)
    ins = analyst.insights(m, match, start_ms)

    notes = warnings_of(m)
    limitations = [
        "Coordenadas de pantalla: todavía no hay calibración de la cancha, así que las distancias y posiciones son fracciones de la imagen, no metros.",
        "Los tracks son trayectorias detectadas por SAM 3.1: un jugador que sale de cuadro y vuelve puede contar como otro track.",
    ]
    if segmenter.segmenter == "sam_simulator":
        limitations.insert(0, "Análisis de desarrollo: la segmentación viene del simulador local, no de SAM 3.1.")

    result = {
        "version": 1,
        "clip_id": clip_id if clip_id is not None else clip.clip_id,
        "job_id": job_id,
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "worker_version": VERSION,
        "processing_seconds": round(time.monotonic() - started, 1),
        "backends": {
            "segmenter": segmenter.segmenter,
            "sam_model": cfg.sam_model,
            "analyst": analyst.analyst,
            "muse_spark_model": cfg.muse_model,
            "usage": usage.as_dict(),
        },
        "match": {k: match.get(k) for k in ("home", "away", "home_color", "away_color")},
        "video": {
            "duration_ms": clip.duration_ms,
            "fps": clip.fps,
            "frames": clip.unique_frames,
            "width": clip.width,
            "height": clip.height,
            "chunks": len(clip.chunks),
            "chunk_seconds": clip.chunk_seconds,
            "overlap_ms": clip.overlap_ms,
        },
        "coordinates": "screen",
        "frames": m["frames"],
        "teams": m["teams"],
        "unassigned_per_frame": m["unassigned_per_frame"],
        "ball": m["ball"],
        "possession_changes": m["possession_changes"],
        "possession_changes_per_minute": m["possession_changes_per_minute"],
        "timeline": m["timeline"],
        "tracks": m["tracks"],
        "events": m["events"],
        "summary": ins["summary"],
        "insights": [{**i, "players": [], "source": "muse_spark"} for i in ins["insights"]],
        "notes": notes,
        "limitations": limitations,
    }
    return result, trackset


TRACK_COLUMNS = ["frame", "t_ms", "source_frame", "track_id", "label", "x", "y", "w", "h", "conf", "team"]


def save_tracks(trackset: TrackSet, path: Path) -> None:
    """Every box of the clip, for tools/overlay.py and the viewer (compact rows)."""
    path.parent.mkdir(parents=True, exist_ok=True)
    rows = [[r.frame, r.t_ms, r.source_frame, r.track_id, r.label, round(r.x, 4), round(r.y, 4), round(r.w, 4), round(r.h, 4), round(r.conf, 3), r.team] for r in trackset.rows]
    path.write_text(json.dumps({"columns": TRACK_COLUMNS, "rows": rows}, separators=(",", ":")), encoding="utf-8")


def error_message(e: BaseException) -> str:
    if isinstance(e, InputError):
        return f"input: {e}"
    if isinstance(e, MetaError):
        return f"{e.service or 'meta'}: {e}"
    return f"{type(e).__name__}: {e}"


def run_job(api: PpApi, cfg: Config, job: dict, sleep=time.sleep) -> str:
    """Downloads, analyzes and reports one job. Returns "done" or "failed"."""
    job_id = int(job["job_id"])
    clip = job["clip"]
    work = cfg.work_dir / f"job_{job_id:06d}"
    log.info("job %s: clip %s (%s chunks)", job_id, clip["id"], clip.get("chunk_count"))
    try:
        for name in clip["files"]:
            try:
                api.download(int(clip["id"]), name, work / "clip" / name)
            except ApiError as e:
                raise InputError(f"cannot download {name}: {e}") from None
        result, _ = analyze(work / "clip", job.get("match") or {}, cfg, clip_id=int(clip["id"]), job_id=job_id, raw_dir=work / "raw", sleep=sleep)
        api.post_result(job_id, "done", analysis=result)
        log.info("job %s: done (%s, usage %s)", job_id, result["backends"]["segmenter"], result["backends"]["usage"])
        shutil.rmtree(work, ignore_errors=True)
        return "done"
    except Exception as e:  # noqa: BLE001 - every failure is reported to the API
        msg = error_message(e)
        log.error("job %s: failed: %s (files kept in %s)", job_id, msg, work)
        try:
            api.post_result(job_id, "failed", error=msg)
        except Exception as e2:  # noqa: BLE001
            log.error("job %s: could not report the failure: %s", job_id, e2)
        return "failed"
