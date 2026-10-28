"""The pitch in meters from the coach's points (docs/prompts/13-cancha-en-metros.md).

Rule of the whole thing: BETTER NO METERS THAN FALSE METERS.

- pitch_mode "off": nothing. "no_lines": only the coach's points. "lines" or
  absent: the points too (lines with SAM are step 5, off by default).
- The homography of each touched frame, from 5 or more points that agree
  (with 4 any names fit), not used if the median error passes 1.5 % of the
  height (a note says so), carried along its shot by
  propagate.py. The chain stops at a cut, where too few points match, and
  where fewer than 90 % of the feet fall on the pitch with 5 m of margin.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from ..chunks import Clip, iter_frames
from ..types import TrackSet
from .propagate import chain, motion_steps
from .solver import project, ransac_points, unproject
from .template import Template, build

MAX_TOUCH_ERROR = 0.015  # of the image height
# Any 4 points give an exact homography, even with the names wrong: a frame
# counts only with 5 or more points that agree.
MIN_CONSISTENT = 5
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
        names = [p["name"] for p in t["points"] if p["name"] in template.points]
        pitch_pts = np.array([template.points[n] for n in names])
        img_pts = np.array([[p["x"] * w, p["y"] * h] for p in t["points"] if p["name"] in template.points])
        entry = {"frame": g, "t_ms": infos[g].t_ms, "points": len(names), "used": False}
        if len(names) < MIN_CONSISTENT:
            notes.append(f"En {_fmt(infos[g].t_ms)} hay {len(names)} puntos: con 4 cualquier nombre encaja, así que hacen falta 5 o más para calcular la cancha.")
            entry.update(inliers=0, median_error_px=None)
            key_report.append(entry)
            continue
        try:
            H, inl = ransac_points(pitch_pts, img_pts, threshold_px=MAX_TOUCH_ERROR * h)
            if inl.sum() < MIN_CONSISTENT:
                raise ValueError("fewer than 5 consistent points")
            err = float(np.median(np.linalg.norm(project(H, pitch_pts[inl]) - img_pts[inl], axis=1)))
        except (ValueError, np.linalg.LinAlgError):
            H, err, inl = None, float("inf"), np.zeros(len(names), dtype=bool)
        entry.update(inliers=int(inl.sum()), median_error_px=round(err, 2) if np.isfinite(err) else None)
        if H is None or err > MAX_TOUCH_ERROR * h:
            notes.append(f"Los puntos marcados en {_fmt(infos[g].t_ms)} no encajan con la cancha y no se usaron.")
        else:
            keys[g] = H
            entry["used"] = True
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
