"""The pitch in meters from the coach's points (docs/prompts/13-cancha-en-metros.md).

Rule of the whole thing: BETTER NO METERS THAN FALSE METERS.

- pitch_mode "off": nothing. "no_lines": only the coach's points and painted
  lines. "lines" or absent: the same, and SAM for the lines when PP_PITCH_SAM=1
  (sam_lines.py; with "no_lines" only if the coach painted something).
- The homography of each touched frame, from 5 or more marks that agree
  (points, and lines the coach painted over a named line when they are faint
  or the light is poor; with 4 any names fit), not used if the median error passes 1.5 % of the
  height (a note says so), carried along its shot by
  propagate.py. The chain stops at a cut, where too few points match, and
  where fewer than 90 % of the feet fall on the pitch with 5 m of margin.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

from ..chunks import Clip, iter_frames
from ..types import MetaError, RetryableError, TrackSet
from . import sam_lines
from .propagate import chain, motion_steps
from .solver import Camera, Correspondences, fit_homography, fit_line, project, ransac_points, residuals, unproject, well_posed
from .template import Template, build

MAX_TOUCH_ERROR = 0.015  # of the image height
# Any 4 points give an exact homography, even with the names wrong: a frame
# counts only with 5 or more points that agree.
MIN_CONSISTENT = 5
# A painted line shorter than this (of the image height) says little about its direction.
MIN_STROKE = 0.05
FEET_INSIDE = 0.90
FEET_MARGIN_M = 5.0


@dataclass
class PitchCalibration:
    template: Template
    width: int
    height: int
    # global frame → (homography pitch m → image px, "manual" | "propagated")
    homographies: dict[int, tuple[np.ndarray, str]]
    report: dict

    def to_meters(self, frame: int, foot_xy: tuple[float, float]) -> tuple[float, float] | None:
        h = self.homographies.get(frame)
        if h is None:
            return None
        mx, my = unproject(h[0], [[foot_xy[0] * self.width, foot_xy[1] * self.height]])[0]
        return float(mx), float(my)


def _fmt(ms: int) -> str:
    s = max(0, ms // 1000)
    return f"{s // 60}:{s % 60:02d}"


def solve_frame(t: dict, template: Template, w: int, h: int) -> tuple[np.ndarray | None, dict, str | None]:
    """The homography of one touched frame from its named points and painted
    lines (a stroke over a line the coach named). Returns (H or None, report,
    note with {at} for the time). Unnamed strokes do not enter here: they only
    show SAM what a line looks like (sam_lines.py)."""
    pts = [p for p in t.get("points") or [] if p["name"] in template.points]
    lines = []
    for ln in t.get("lines") or []:
        stroke = [[q["x"] * w, q["y"] * h] for q in ln.get("points") or []]
        if ln.get("name") in template.lines and len(stroke) >= 2:
            a, b, dev = fit_line(stroke)
            # A stroke that bends more than the tolerance is not one straight line.
            if dev <= MAX_TOUCH_ERROR * h and math.dist(a, b) >= MIN_STROKE * h:
                lines.append((ln["name"], a, b))
    n = len(pts) + len(lines)
    entry = {"points": len(pts), "lines": len(lines), "used": False, "inliers": 0, "median_error_px": None}
    if n < MIN_CONSISTENT:
        what = f"{len(pts)} puntos" + (f" y {len(lines)} líneas" if lines else "")
        return None, entry, "En {at} hay " + what + ": con 4 cualquier nombre encaja, así que hacen falta 5 o más (puntos y líneas pintadas) para calcular la cancha."
    pitch_pts = np.array([template.points[p["name"]] for p in pts]).reshape(-1, 2)
    img_pts = np.array([[p["x"] * w, p["y"] * h] for p in pts]).reshape(-1, 2)
    threshold = MAX_TOUCH_ERROR * h
    H, err, inl = None, float("inf"), 0
    try:
        if not lines:
            H, mask = ransac_points(pitch_pts, img_pts, threshold_px=threshold)
            inl = int(mask.sum())
            err = float(np.median(np.linalg.norm(project(H, pitch_pts[mask]) - img_pts[mask], axis=1)))
        else:
            H, inl, err = _fit_points_and_lines(template, pitch_pts, img_pts, lines, threshold)
        if inl < MIN_CONSISTENT:
            raise ValueError("fewer than 5 consistent marks")
    except (ValueError, np.linalg.LinAlgError):
        H, err = None, float("inf")
    entry.update(inliers=inl, median_error_px=round(err, 2) if np.isfinite(err) else None)
    if H is None or err > threshold:
        return None, entry, "Los puntos y líneas marcados en {at} no encajan con la cancha y no se usaron."
    entry["used"] = True
    return H, entry, None


def _fit_points_and_lines(template: Template, pitch_pts, img_pts, lines, threshold: float) -> tuple[np.ndarray, int, float]:
    """Fits points and lines together; while something is off by more than the
    threshold and more than 5 marks remain, drops the worst and fits again
    (RANSAC over 4 marks does not work with lines: 4 lines alone can be
    parallel). A bad mark pulls the first fit, so a good one can be dropped
    first: at the end every mark that fits is taken back."""

    def corr(kp, kl):
        return Correspondences(
            [tuple(pitch_pts[i]) for i in kp], [tuple(img_pts[i]) for i in kp],
            [template.lines[lines[i][0]] for i in kl], [(lines[i][1], lines[i][2]) for i in kl],
        )

    def errors(H, kp, kl):
        r = residuals(H, corr(kp, kl))
        n = len(kp)
        return [math.hypot(r[2 * i], r[2 * i + 1]) for i in range(n)] + [max(abs(r[2 * n + 2 * i]), abs(r[2 * n + 2 * i + 1])) for i in range(len(kl))]

    def fit(kp, kl):
        c = corr(kp, kl)
        if not well_posed(c):
            raise ValueError("points and lines leave the pitch free")
        return fit_homography(c)

    all_p, all_l = list(range(len(pitch_pts))), list(range(len(lines)))
    keep_p, keep_l = list(all_p), list(all_l)
    while True:
        H = fit(keep_p, keep_l)
        errs = errors(H, keep_p, keep_l)
        worst = int(np.argmax(errs))
        if errs[worst] <= threshold or len(errs) <= MIN_CONSISTENT:
            break
        if worst < len(keep_p):
            keep_p.pop(worst)
        else:
            keep_l.pop(worst - len(keep_p))
    errs = errors(H, all_p, all_l)
    keep_p = [i for i in all_p if errs[i] <= threshold]
    keep_l = [i for i in all_l if errs[len(all_p) + i] <= threshold]
    if len(keep_p) + len(keep_l) >= MIN_CONSISTENT:
        H = fit(keep_p, keep_l)
    errs = errors(H, keep_p, keep_l) if keep_p or keep_l else [math.inf]
    return H, sum(1 for e in errs if e <= threshold), float(np.median(errs))


def _reference_pictures(clip: Clip, refs: list[dict]) -> list["sam_lines.Reference"]:
    """The coach's pictures downloaded next to the clip (reference_N.jpg) with their strokes."""
    import cv2

    out = []
    for r in refs:
        path = clip.dir / f"reference_{int(r.get('n', 0))}.jpg"
        img = cv2.imread(str(path)) if path.is_file() else None
        if img is None:
            continue
        strokes = [[(p["x"], p["y"]) for p in st.get("points") or []] for st in r.get("strokes") or []]
        out.append(sam_lines.Reference(img, [s for s in strokes if len(s) >= 2], r.get("source", "photo")))
    return out


def _with_sam_lines(lines, video, chained, keys, template, w, h, steps, accept, method):
    """SAM's lines on the key frames: new keys where the template falls on them,
    cuts where they contradict the propagated pitch; then the chain again."""
    notes: list[str] = []
    report = {"concept": lines.concept, "key_frames": 0, "frames_charged": 0, "references": sum(1 for g in video.order if g is None),
              "strokes": len(video.objects), "strokes_sent": len(video.sent_objects()), "found": 0, "used": 0, "confirmed": 0, "contradicted": 0, "frames": []}
    try:
        white, charged = sam_lines.find_white(lines, video)
    except (MetaError, RetryableError, OSError) as e:
        notes.append(f"SAM no pudo buscar las líneas de la cancha ({str(e)[:120]}): la cancha queda solo con los puntos marcados.")
        return chained, report, notes
    report.update(key_frames=sum(1 for g in video.order if g is not None), frames_charged=charged, found=len(white))
    if not white:
        notes.append("SAM no encontró líneas de la cancha en los cuadros clave: la cancha queda solo con los puntos marcados.")
        return chained, report, notes
    # The camera's place from the first touched frame (a tripod or a broadcast camera does not move).
    k0 = min(keys)
    camera = Camera.from_homography(keys[k0], w, h, list(template.points.values()))
    start = {i: Hm for i, (Hm, _) in chained.items()}
    new_keys, contradicted, frames = sam_lines.refine_keys({g: m for g, m in white.items() if g not in keys}, start, camera, template)
    confirmed = sum(1 for f in frames if f.get("confirmed"))
    report.update(used=len(new_keys), confirmed=confirmed, contradicted=len(contradicted), frames=frames[:60])
    if not new_keys and not contradicted and not confirmed:
        notes.append("Las líneas que encontró SAM no confirmaron la cancha en ningún cuadro clave: queda la de los puntos marcados.")
        return chained, report, notes
    if not new_keys and not contradicted:
        return chained, report, notes
    for g in new_keys:
        method[g] = "meta_sam"

    def accept2(i, Hm):
        return i not in contradicted and accept(i, Hm)

    return chain(steps, {**keys, **new_keys}, accept2), report, notes


def calibrate(clip: Clip, trackset: TrackSet, calibration: dict | None, pitch_mode: str | None, frames=None,
              lines: "sam_lines.SamLines | None" = None) -> tuple[PitchCalibration | None, list[str]]:
    """Returns the calibration (or None) and notes for the result. [frames] overrides decoding (tests).

    [lines] (PP_PITCH_SAM=1): SAM looks for the lines on key frames, shown by
    the coach's strokes. With "Se ven poco o no hay" only when the coach
    painted something: that is the coach saying where the lines are."""
    if pitch_mode == "off":
        return None, []
    touches = (calibration or {}).get("frames") or []
    if not touches:
        if pitch_mode == "no_lines":
            return None, ["Sin puntos marcados no se calcula la cancha: el análisis queda en posiciones de la imagen."]
        return None, ["No hay puntos de la cancha marcados: marque al menos 5 puntos en un cuadro para medir en metros."]

    pitch = calibration.get("pitch") or {}
    template = build(int(pitch.get("size") or 11), pitch.get("length_m"), pitch.get("width_m"))
    w, h = clip.width, clip.height
    infos = trackset.frames
    notes: list[str] = []

    keys: dict[int, np.ndarray] = {}
    key_report = []
    touched_at: dict[int, int] = {}  # index in calibration["frames"] → global frame
    for ti, t in enumerate(touches):
        if "frame" in t:
            g = min(max(int(t["frame"]), 0), len(infos) - 1)
        else:
            g = min(range(len(infos)), key=lambda i: abs(infos[i].t_ms - int(t["t_ms"])))
        touched_at[ti] = g
        H, entry, note = solve_frame(t, template, w, h)
        entry = {"frame": g, "t_ms": infos[g].t_ms, **entry}
        if note:
            notes.append(note.format(at=_fmt(infos[g].t_ms)))
        if H is not None:
            keys[g] = H
        key_report.append(entry)

    report = {
        "pitch": {"size": template.size, "length_m": template.length, "width_m": template.width, "source": "default" if template.approximate else "coach"},
        "mode": pitch_mode,
        "key_frames": key_report,
        "frames_with_homography": 0.0,
        "by_method": {"manual": 0.0, "propagated": 0.0},
        "median_error": None,
        "lines": "off",
    }
    if not keys:
        return None, notes

    by_frame = trackset.by_frame()

    def boxes_of(g):
        return [(r.x, r.y, r.w, r.h) for r in by_frame.get(g, []) if r.label == "player"]

    def accept(i, Hm):
        feet = [r.foot for r in by_frame.get(i, []) if r.label == "player"]
        if len(feet) < 3:
            return True
        m = unproject(Hm, [[fx * w, fy * h] for fx, fy in feet])
        inside = sum(1 for x, y in m if template.contains(x, y, FEET_MARGIN_M))
        return inside / len(feet) >= FEET_INSIDE

    source = frames if frames is not None else ((fi.frame, img) for fi, _, _, img in iter_frames(clip))
    refs, touched_strokes = sam_lines.strokes_of(calibration)
    painted = bool(touched_strokes) or any(r.get("strokes") for r in refs)
    video = None
    if lines is not None and (pitch_mode != "no_lines" or painted):
        # The key frames go into SAM's video as the frames pass for the motion.
        stroke_frames = {touched_at[i]: st for i, st in touched_strokes.items() if i in touched_at}
        step = max(1, round(clip.fps))
        wanted = set(range(0, len(infos), step)[:sam_lines.MAX_KEY_FRAMES]) | set(stroke_frames)
        video = sam_lines.PromptVideo(clip.dir / "sam_lines" / "prompt.mp4", w, h, _reference_pictures(clip, refs))

        def tap(src):
            for g, img in src:
                if g in wanted:
                    video.add(g, img, stroke_frames.get(g))
                yield g, img

        source = tap(source)
    steps = motion_steps(source, boxes_of)
    chained = chain(steps, keys, accept)
    method = {k: "manual" for k in keys}
    if video is not None:
        chained, sam_report, sam_notes = _with_sam_lines(lines, video, chained, keys, template, w, h, steps, accept, method)
        report["lines"] = "sam"
        report["sam"] = sam_report
        notes += sam_notes
    homographies = {i: (Hm, method[k] if i == k else "propagated") for i, (Hm, k) in chained.items()}

    n = max(1, len(infos))
    manual = sum(1 for _, s in homographies.values() if s == "manual")
    by_sam = sum(1 for _, s in homographies.values() if s == "meta_sam")
    errs = [e["median_error_px"] for e in key_report if e["used"]]
    report.update(
        frames_with_homography=round(len(homographies) / n, 4),
        by_method={"manual": round(manual / n, 4), "propagated": round((len(homographies) - manual - by_sam) / n, 4),
                   **({"meta_sam": round(by_sam / n, 4)} if video is not None else {})},
        median_error=round(float(np.median(errs)) / h, 5) if errs else None,
    )
    if len(homographies) / n < 0.5:
        notes.append(f"La cancha solo se calculó en el {round(100 * len(homographies) / n)} % del video.")
    return PitchCalibration(template, w, h, homographies, report), notes
