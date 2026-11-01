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
  where fewer than 90 % of the feet fall on the pitch with 5 m of margin for
  1 s in a row (only players with a team count when the teams are known:
  linesmen, staff and the bench stand outside the lines). People whose feet
  fall outside the lines most of the time on the frames already carried (a
  linesman or the bench the team colors could not tell apart) leave the check
  and the chain is made again; they come out without a team (off_pitch).
  A moving camera's homography is carried at most MAX_CARRY_SECONDS from its
  key frame (the coach's or SAM's): past that the drift is meters.
- camera "static" (the coach said the camera does not move the whole video:
  a tripod or a panorama): the touched homography holds for every frame, with
  no motion pass; only the feet check can cut it. "moving" or absent: carried
  frame by frame as above (a feed that pans and zooms).
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np

from ..chunks import Clip, iter_frames
from ..types import MetaError, RetryableError, TrackSet
from . import sam_lines
from .propagate import Step, chain, motion_steps
from .solver import Camera, Correspondences, fit_homography, fit_line, project, ransac_points, residuals, unproject, well_posed
from .template import Template, build

MAX_TOUCH_ERROR = 0.015  # of the image height
# Any 4 points give an exact homography, even with the names wrong: a frame
# counts only with 5 or more points that agree.
MIN_CONSISTENT = 5
# A painted line shorter than this (of the image height) says little about its direction.
MIN_STROKE = 0.05
FEET_INSIDE = 0.90
# The feet check cuts the chain only after failing this long in a row: a few
# frames with someone out of place (a throw-in, a bad box) do not end it.
FEET_BAD_SECONDS = 1.0
# A track is off the pitch (a linesman, the bench, staff) when its feet fall this far
# outside the lines in this share of its frames with a homography, over at least
# OFF_PITCH_SECONDS: a winger on the touchline is outside now and then, not most of the time.
OFF_PITCH_M = 1.0
OFF_PITCH_SHARE = 0.8
OFF_PITCH_SECONDS = 3.0
OFF_PITCH_ROUNDS = 5
# Evidence also from the chain without the feet check this close to a key frame:
# if staff stand outside from the start, the checked chain never gets going.
OFF_PITCH_WINDOW_SECONDS = 30.0
# A moving camera followed on the grass drifts (2 m at 40 s on a broadcast): no
# homography is carried farther than this from its key frame.
MAX_CARRY_SECONDS = 60.0
# Rounds of SAM re-anchoring: each one reaches the key frames the new anchors carried the chain to.
MAX_SAM_ROUNDS = 50
FEET_MARGIN_M = 5.0


@dataclass
class PitchCalibration:
    template: Template
    width: int
    height: int
    # global frame → (homography pitch m → image px, "manual" | "propagated")
    homographies: dict[int, tuple[np.ndarray, str]]
    report: dict
    # global frame → the coach's points there [(name, x px, y px)], for the viewer
    marks: dict[int, list[tuple[str, float, float]]] = field(default_factory=dict)
    # Player tracks outside the lines most of the time: they get no team.
    off_pitch: set[int] = field(default_factory=set)

    def to_meters(self, frame: int, foot_xy: tuple[float, float]) -> tuple[float, float] | None:
        h = self.homographies.get(frame)
        if h is None:
            return None
        mx, my = unproject(h[0], [[foot_xy[0] * self.width, foot_xy[1] * self.height]])[0]
        return float(mx), float(my)


def _off_pitch_tracks(chained: dict, by_frame: dict, template: Template, w: int, h: int, min_frames: int) -> set[int]:
    """Player tracks whose feet fall more than OFF_PITCH_M outside the lines in
    OFF_PITCH_SHARE of their frames with a homography (at least [min_frames])."""
    seen: dict[int, int] = {}
    out: dict[int, int] = {}
    for i, (Hm, _) in chained.items():
        rows = [r for r in by_frame.get(i, []) if r.label == "player"]
        if not rows:
            continue
        m = unproject(Hm, [[r.foot[0] * w, r.foot[1] * h] for r in rows])
        for r, (x, y) in zip(rows, m):
            seen[r.track_id] = seen.get(r.track_id, 0) + 1
            if not template.contains(x, y, OFF_PITCH_M):
                out[r.track_id] = out.get(r.track_id, 0) + 1
    return {t for t, n in seen.items() if n >= min_frames and out.get(t, 0) >= OFF_PITCH_SHARE * n}


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


def _with_sam_lines(lines, video, chained, keys, template, w, h, steps, accept, method, patience=1, max_carry=None):
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
    new_keys: dict[int, np.ndarray] = {}
    contradicted: set[int] = set()
    frames: list[dict] = []

    def accept2(i, Hm):
        return i not in contradicted and accept(i, Hm)

    # In rounds: a key frame is fitted from the homography carried to it; the new
    # anchors carry the chain past where it stopped, and the next key frames start
    # from there (a pose minutes old rarely fits a moving camera).
    pending = {g: m for g, m in white.items() if g not in keys}
    rounds = 0
    while pending and rounds < MAX_SAM_ROUNDS:
        start = {i: Hm for i, (Hm, _) in chained.items()}
        ready = {g: m for g, m in pending.items() if g in start}
        if not ready:
            break
        nk, con, fr = sam_lines.refine_keys(ready, start, camera, template)
        for g in ready:
            pending.pop(g)
        new_keys.update(nk)
        contradicted |= con
        frames += fr
        rounds += 1
        if not nk and not con:
            break
        chained = chain(steps, {**keys, **new_keys}, accept2, patience, max_carry)
    if pending:
        # Never reached by the chain: from the nearest homography, as a last try.
        start = {i: Hm for i, (Hm, _) in chained.items()}
        nk, con, fr = sam_lines.refine_keys(pending, start, camera, template)
        new_keys.update(nk)
        contradicted |= con
        frames += fr
    frames.sort(key=lambda f: f["frame"])
    report["rounds"] = rounds
    confirmed = sum(1 for f in frames if f.get("confirmed"))
    report.update(used=len(new_keys), confirmed=confirmed, contradicted=len(contradicted), frames=frames[:60])
    if not new_keys and not contradicted and not confirmed:
        notes.append("Las líneas que encontró SAM no confirmaron la cancha en ningún cuadro clave: queda la de los puntos marcados.")
        return chained, report, notes
    if not new_keys and not contradicted:
        return chained, report, notes
    for g in new_keys:
        method[g] = "meta_sam"
    return chain(steps, {**keys, **new_keys}, accept2, patience, max_carry), report, notes


def calibrate(clip: Clip, trackset: TrackSet, calibration: dict | None, pitch_mode: str | None, frames=None,
              lines: "sam_lines.SamLines | None" = None, camera: str | None = None) -> tuple[PitchCalibration | None, list[str]]:
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
    marks: dict[int, list[tuple[str, float, float]]] = {}
    touched_at: dict[int, int] = {}  # index in calibration["frames"] → global frame
    for ti, t in enumerate(touches):
        if "frame" in t:
            g = min(max(int(t["frame"]), 0), len(infos) - 1)
        else:
            g = min(range(len(infos)), key=lambda i: abs(infos[i].t_ms - int(t["t_ms"])))
        touched_at[ti] = g
        marks.setdefault(g, []).extend((p["name"], p["x"] * w, p["y"] * h) for p in t.get("points") or [] if p.get("name") in template.points)
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
        "camera": "static" if camera == "static" else "moving",
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

    # With the teams known, people without one (linesmen, staff, the bench) do not count.
    teams_known = any(r.team for r in trackset.rows if r.label == "player")
    patience = max(1, round(FEET_BAD_SECONDS * clip.fps))
    off_min = max(1, round(OFF_PITCH_SECONDS * clip.fps))
    max_carry = None if camera == "static" else round(MAX_CARRY_SECONDS * clip.fps)
    off: set[int] = set()

    def accept(i, Hm):
        feet = [r.foot for r in by_frame.get(i, []) if r.label == "player" and r.track_id not in off and (r.team or not teams_known)]
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
    static = camera == "static"
    if static:
        # A camera that does not move: every step is the identity, so each key
        # frame's homography holds until the feet say otherwise. The frames are
        # only read when SAM's prompt video needs them.
        if video is not None:
            for _ in source:
                pass
        steps = [Step(np.eye(3), 0, 1.0, False) for _ in infos]
    else:
        steps = motion_steps(source, boxes_of)
    chained = chain(steps, keys, accept, patience, max_carry)
    # Who stands outside the lines on the frames carried so far leaves the feet
    # check; the chain may now go further and show more of them.
    window = round(OFF_PITCH_WINDOW_SECONDS * clip.fps)
    near = {i: v for i, v in chain(steps, keys, lambda i, Hm: True).items() if abs(i - v[1]) <= window}
    for _ in range(OFF_PITCH_ROUNDS):
        more = _off_pitch_tracks({**near, **chained}, by_frame, template, w, h, off_min) - off
        if not more:
            break
        off |= more
        chained = chain(steps, keys, accept, patience, max_carry)
    method = {k: "manual" for k in keys}
    if video is not None:
        chained, sam_report, sam_notes = _with_sam_lines(lines, video, chained, keys, template, w, h, steps, accept, method, patience, max_carry)
        report["lines"] = "sam"
        report["sam"] = sam_report
        notes += sam_notes
    carried = "static" if static else "propagated"
    off |= _off_pitch_tracks(chained, by_frame, template, w, h, off_min)
    report["off_pitch_tracks"] = len(off)
    if off:
        notes.append(f"{len(off)} persona(s) pasan casi todo el tiempo fuera de las líneas (jueces de línea, banca o cuerpo técnico): quedan sin equipo.")
    homographies = {i: (Hm, method[k] if i == k else carried) for i, (Hm, k) in chained.items()}

    n = max(1, len(infos))
    manual = sum(1 for _, s in homographies.values() if s == "manual")
    by_sam = sum(1 for _, s in homographies.values() if s == "meta_sam")
    by_static = sum(1 for _, s in homographies.values() if s == "static")
    errs = [e["median_error_px"] for e in key_report if e["used"]]
    report.update(
        frames_with_homography=round(len(homographies) / n, 4),
        by_method={"manual": round(manual / n, 4), "propagated": round((len(homographies) - manual - by_sam - by_static) / n, 4),
                   **({"static": round(by_static / n, 4)} if static else {}),
                   **({"meta_sam": round(by_sam / n, 4)} if video is not None else {})},
        median_error=round(float(np.median(errs)) / h, 5) if errs else None,
    )
    if len(homographies) / n < 0.5:
        notes.append(f"La cancha solo se calculó en el {round(100 * len(homographies) / n)} % del video.")
    return PitchCalibration(template, w, h, homographies, report, marks, off), notes
