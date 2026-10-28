"""Saved runs for the viewer (viewer/) and tools/compare_runs.py.

With DEBUG_DIR (or `analyze --debug-dir`) each analysis is kept as
DEBUG_DIR/<run>/ with result.json, tracks.json and a copy of the chunks
(video, so under worker/.local/, never versioned).
"""

from __future__ import annotations

import json
import shutil
from datetime import datetime, timezone
from pathlib import Path

from .chunks import Clip
from .types import TrackSet

TRACK_COLUMNS = ["frame", "t_ms", "track", "label", "team", "x", "y", "w", "h", "conf", "interp"]


def run_name(clip_id: int | None, job_id: int | None) -> str:
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    return f"{stamp}_clip{clip_id or 0}_job{job_id or 0}"


def save_run(root: Path, clip: Clip, result: dict, trackset: TrackSet, name: str | None = None) -> Path:
    run = root / (name or run_name(result.get("clip_id"), result.get("job_id")))
    (run / "chunks").mkdir(parents=True, exist_ok=True)
    (run / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    rows = [[r.frame, r.t_ms, r.track_id, r.label, r.team, round(r.x, 4), round(r.y, 4), round(r.w, 4), round(r.h, 4), round(r.conf, 3), 0] for r in trackset.rows]
    # Per chunk: where its frames start in the global numbering, to sync the video.
    chunks = []
    base = 0
    for c in clip.chunks:
        first = c.overlap_frames if c.number > 1 else 0
        chunks.append({"number": c.number, "file": c.video.name, "fps": c.fps, "frames": c.frame_count, "skip": first, "base": base,
                       "start_ms": c.start_ms, "end_ms": c.end_ms})
        base += c.frame_count - first
        shutil.copy2(c.video, run / "chunks" / c.video.name)
        yaml_src = c.video.with_suffix(".yaml")
        if yaml_src.is_file():
            shutil.copy2(yaml_src, run / "chunks" / yaml_src.name)
    frames = [[f.frame, f.t_ms] for f in trackset.frames]
    (run / "tracks.json").write_text(json.dumps({"columns": TRACK_COLUMNS, "rows": rows, "frames": frames, "chunks": chunks}, separators=(",", ":")), encoding="utf-8")
    return run
