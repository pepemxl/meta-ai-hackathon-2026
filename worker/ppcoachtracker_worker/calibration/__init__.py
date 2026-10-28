"""The pitch in meters from the coach's points (docs/prompts/13-cancha-en-metros.md).

Rule of the whole thing: BETTER NO METERS THAN FALSE METERS.

- pitch_mode "off": nothing. "no_lines": only the coach's points. "lines" or
  absent: the points too (lines with SAM are step 5, off by default).
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
from ..types import TrackSet
from .propagate import chain, motion_steps
from .solver import Correspondences, fit_homography, fit_line, project, ransac_points, residuals, unproject, well_posed
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


def calibrate(clip: Clip, trackset: TrackSet, calibration: dict | None, pitch_mode: str | None, frames=None) -> tuple[PitchCalibration | None, list[str]]:
    """Returns the calibration (or None) and notes for the result. [frames] overrides decoding (tests)."""
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
    for t in touches:
        if "frame" in t:
            g = min(max(int(t["frame"]), 0), len(infos) - 1)
        else:
            g = min(range(len(infos)), key=lambda i: abs(infos[i].t_ms - int(t["t_ms"])))
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
    steps = motion_steps(source, boxes_of)
    chained = chain(steps, keys, accept)
    homographies = {i: (Hm, "manual" if i == k else "propagated") for i, (Hm, k) in chained.items()}

    n = max(1, len(infos))
    manual = sum(1 for _, s in homographies.values() if s == "manual")
    errs = [e["median_error_px"] for e in key_report if e["used"]]
    report.update(
        frames_with_homography=round(len(homographies) / n, 4),
        by_method={"manual": round(manual / n, 4), "propagated": round((len(homographies) - manual) / n, 4)},
        median_error=round(float(np.median(errs)) / h, 5) if errs else None,
    )
    if len(homographies) / n < 0.5:
        notes.append(f"La cancha solo se calculó en el {round(100 * len(homographies) / n)} % del video.")
    return PitchCalibration(template, w, h, homographies, report), notes
