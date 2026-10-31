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
from .debug import save_run
from . import ball as ball_mod, cuts as cuts_mod, pitch as pitch_mod, shots as shots_mod, tiles as tiles_mod
from .calibration import calibrate
from .calibration.sam_lines import SamLines
from .chunks import iter_frames
from .insights import MuseSpark, warnings_of
from .meta_api import MetaClient, ResponseCache, Usage
from .sam import SamSegmenter
from .types import InputError, MetaError, TrackSet

log = logging.getLogger("ppcoachtracker_worker")


def analyze(clip_dir: Path, match: dict, cfg: Config, clip_id: int | None = None, job_id: int | None = None, raw_dir: Path | None = None, sleep=time.sleep,
            pitch_mode: str | None = None, calibration: dict | None = None, frames=None, camera: str | None = None,
            play_area: dict | None = None) -> tuple[dict, TrackSet]:
    started = time.monotonic()
    clip = load_clip(clip_dir)
    if clip.frames_sent_per_concept > cfg.max_frames_per_job:
        raise InputError(f"{clip.frames_sent_per_concept} frames, more than META_MAX_FRAMES_PER_JOB={cfg.max_frames_per_job}")

    usage = Usage()
    cache = ResponseCache(cfg.cache_dir)
    sam_client = MetaClient(cfg.sam_base_url, cfg.api_key, "meta_sam", usage, cache, sleep=sleep)
    muse_client = MetaClient(cfg.meta_base_url, cfg.api_key, "muse_spark", usage, cache, sleep=sleep)
    segmenter = SamSegmenter(sam_client, cfg.sam_model, raw_dir, is_meta_url=cfg.sam_is_meta, player_concept=cfg.player_concept,
                             ball_concept=cfg.ball_concept, tile_mode=cfg.tile_mode)
    home_color, away_color = match.get("home_color") or "", match.get("away_color") or ""
    # The play area the coach marked: with a still camera it is the pitch of every frame.
    area = pitch_mod.from_play_area(play_area, clip.width, clip.height) if camera == "static" else None
    # Wide video goes to SAM in tiles: each tile costs one request per concept.
    tile_plan = segmenter.plan_tiles(clip, area)
    if tile_plan is not None and clip.frames_sent_per_concept * len(tile_plan.tiles) > cfg.max_frames_per_job:
        raise InputError(f"{clip.frames_sent_per_concept} frames × {len(tile_plan.tiles)} tiles, more than META_MAX_FRAMES_PER_JOB={cfg.max_frames_per_job}")

    trackset = segmenter.track(clip, home_color, away_color)

    # The pitch mask: the app's where it came, measured from the video elsewhere.
    # One pass over the video: the masks that did not come from the app, the
    # histograms for the cuts and the grass around each ball candidate.
    masks = clip.pitch_masks()
    from_app = sum(1 for m in masks if m is not None)
    if area is not None:
        masks = [area] * len(masks)
    missing = {i for i, m in enumerate(masks) if m is None}
    histograms: list = [None] * len(masks)
    ball_grass: dict[tuple[int, int], float] = {}
    balls_by_frame: dict[int, list] = {}
    for r in trackset.rows:
        if r.label == "ball":
            balls_by_frame.setdefault(r.frame, []).append(r)
    source = frames if frames is not None else ((fi.frame, img) for fi, _, _, img in iter_frames(clip))
    for g, img in source:
        if g >= len(masks):
            continue
        if g in missing:
            masks[g] = pitch_mod.measure(img)
        histograms[g] = cuts_mod.histogram(img)
        for b in balls_by_frame.get(g, []):
            ball_grass[(b.frame, b.track_id)] = grass_around_box(img, b)
    frames = None  # a test iterator is consumed here
    cut_frames = cuts_mod.cut_frames(histograms)
    analyze.last_raw = (trackset, sorted(cut_frames), ball_grass)
    outside = sum(1 for r in trackset.rows if r.label == "player" and not area.contains(*r.foot)) if area is not None else 0
    shots, trackset, ball_report = post_sam(trackset, masks, cut_frames, ball_grass)
    if area is not None:
        mask_notes = [f"Zona de juego marcada por el entrenador ({len(area.polygon) // 2} puntos, margen de {area.margin * 100:g} % del alto): "
                      f"es la cancha de todo el video y se quitaron {outside} cajas de personas fuera de ella."]
    else:
        mask_notes = [f"Máscara de la cancha: {from_app} cuadros vinieron de la app y {len(missing)} se midieron del video."]
    if play_area and area is None:
        mask_notes.append("Se ignoró la zona de juego marcada: solo se usa con la cámara fija todo el video." if camera != "static"
                          else "Se ignoró la zona de juego marcada: no es un polígono válido.")
    if not shots.used_mask:
        mask_notes.append(f"Solo {shots.with_pitch} de {len(trackset.frames)} cuadros tienen cancha: no se usó la máscara y se analizó todo el video.")
    if len(shots.counted) / clip.fps < shots_mod.MIN_COUNTED_SECONDS:
        mask_notes.append(f"Solo {len(shots.counted) / clip.fps:.0f} s del video cuentan (sin acercamientos ni tramos sin cancha): las métricas son de poco tiempo.")


    # The pitch in meters: only from the coach's points, never guessed.
    lines = SamLines(sam_client, cfg.sam_model, cfg.line_concept, raw_dir) if cfg.pitch_sam else None
    cal, cal_notes = calibrate(clip, trackset, calibration, pitch_mode, frames=frames, lines=lines, camera=camera)
    if lines is not None:
        segmenter.simulator |= lines.simulator
    pitch = None
    if cal is not None:
        rows = []
        for r in trackset.rows:
            m_xy = cal.to_meters(r.frame, r.foot) if r.label == "player" else None
            rows.append(r.with_(mx=m_xy[0], my=m_xy[1]) if m_xy else r)
        trackset = TrackSet(rows=rows, frames=trackset.frames)
        counted_h = set(cal.homographies) & {f.frame for f in shots.counted}
        pitch = metrics_mod.pitch_metrics(TrackSet(rows=trackset.rows, frames=shots.counted), counted_h, cal.template.length, cal.template.width, cal.template.approximate, clip.fps)
        if pitch is None:
            cal_notes.append("Menos de 10 s del video tienen la cancha calculada: no se dan métricas en metros.")
    analyze.last_calibration = cal  # for save_run (the viewer draws the pitch)
    analyze.last_masks = masks
    if raw_dir is not None:
        save_tracks(trackset, raw_dir / "tracks.json")
    m = metrics_mod.compute(TrackSet(rows=trackset.rows, frames=shots.counted), clip.fps, breaks=shots.breaks)
    start_ms = trackset.frames[0].t_ms if trackset.frames else 0
    analyst = MuseSpark(muse_client, cfg.muse_model, raw_dir, is_meta_url=cfg.muse_is_meta)
    ins = analyst.insights(m, match, start_ms, pitch=pitch)

    notes = warnings_of(m) + mask_notes + cal_notes
    if tile_plan is not None:
        notes.append(f"Video ancho: SAM lo vio en {len(tile_plan.tiles)} partes de {tiles_mod.SIZE} px (escala {tile_plan.scale:g}); "
                     f"{segmenter.tile_joins} tracks se unieron entre partes.")
    if shots.used_mask and len(shots.counted) / max(1, len(trackset.frames)) < 0.999:
        notes.append(f"Se descartaron {len(trackset.frames) - len(shots.counted)} cuadros sin cancha (acercamientos, tribuna o gráficos): no entran a las métricas.")
    if pitch is not None:
        limitations = [f"Las métricas en metros salen del {round(100 * pitch['frames_with_homography'])} % del video (los cuadros con la cancha calculada); las de la imagen, de todo el video."]
        if pitch["approximate"]:
            limitations.append(f"Se usaron las medidas por omisión de la cancha ({pitch['length_m']:g}×{pitch['width_m']:g} m): las distancias son aproximadas.")
    else:
        limitations = ["Coordenadas de pantalla: sin la cancha calculada, las distancias y posiciones son fracciones de la imagen, no metros."]
    limitations.append("Los tracks son trayectorias detectadas por SAM 3.1: un jugador que sale de cuadro y vuelve puede contar como otro track.")
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
            "frames_used": len(shots.counted),
            "discarded": shots.discarded,
            "cuts": len(cut_frames),
            "closeups": shots.closeups,
            **({"play_area": {"points": len(area.polygon) // 2, "margin": area.margin, "removed": outside}} if area is not None else {}),
            **({"tiles": {"count": len(tile_plan.tiles), "size": tiles_mod.SIZE, "scale": tile_plan.scale, "overlap_px": tile_plan.overlap_px,
                          "joins": segmenter.tile_joins}} if tile_plan is not None else {}),
        },
        "coordinates": "screen",
        "frames": m["frames"],
        "teams": m["teams"],
        "unassigned_per_frame": m["unassigned_per_frame"],
        "ball": {**m["ball"], **ball_report},
        "possession_changes": m["possession_changes"],
        "possession_changes_per_minute": m["possession_changes_per_minute"],
        "timeline": m["timeline"],
        "tracks": m["tracks"],
        "events": m["events"],
        **({"calibration": cal.report} if cal is not None else {}),
        **({"pitch": pitch} if pitch is not None else {}),
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


def post_sam(trackset: TrackSet, masks: list, cut_frames: set[int], ball_grass: dict) -> tuple:
    """What happens to SAM's boxes before measuring (also tools/rescore.py, without calling SAM):
    the frames that count and the ball. Returns (shots, trackset, ball report)."""
    shots = shots_mod.apply(trackset, masks, cut_frames)
    # The ball: one trajectory per candidate, the one that lasts and passes by the feet.
    players_rows = [r for r in shots.trackset.rows if r.label == "player"]
    ball_rows, ball_report = ball_mod.select([r for r in shots.trackset.rows if r.label == "ball"], players_rows, shots.counted, shots.breaks, ball_grass)
    return shots, TrackSet(rows=players_rows + ball_rows, frames=trackset.frames), ball_report


def grass_around_box(image_bgr, row) -> float:
    """Grass fraction in a square 4 times the ball's size around it (balls on an advert have none)."""
    h, w = image_bgr.shape[:2]
    cx, cy = row.center
    side = 4 * max(row.w * w, row.h * h, 4)
    x1, x2 = int(max(0, cx * w - side / 2)), int(min(w, cx * w + side / 2))
    y1, y2 = int(max(0, cy * h - side / 2)), int(min(h, cy * h + side / 2))
    crop = image_bgr[y1:y2, x1:x2]
    if crop.size == 0:
        return 1.0
    return float(pitch_mod.grass_cells(crop[..., ::-1].astype("int64")).mean())


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
    options = job.get("options") or {}
    work = cfg.work_dir / f"job_{job_id:06d}"
    log.info("job %s: clip %s (%s chunks)", job_id, clip["id"], clip.get("chunk_count"))
    try:
        # The coach's reference pictures (if any) go next to the chunks.
        for name in list(clip["files"]) + list(clip.get("references") or []):
            try:
                api.download(int(clip["id"]), name, work / "clip" / name)
            except ApiError as e:
                raise InputError(f"cannot download {name}: {e}") from None
        result, trackset = analyze(work / "clip", job.get("match") or {}, cfg, clip_id=int(clip["id"]), job_id=job_id, raw_dir=work / "raw", sleep=sleep,
                                   pitch_mode=options.get("pitch_mode"), camera=options.get("camera"), calibration=job.get("calibration"),
                                   play_area=options.get("play_area"))
        if cfg.debug_dir is not None:
            run = save_run(cfg.debug_dir, load_clip(work / "clip"), result, trackset, calibration=analyze.last_calibration, masks=analyze.last_masks, raw=analyze.last_raw)
            log.info("job %s: run saved in %s", job_id, run)
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
