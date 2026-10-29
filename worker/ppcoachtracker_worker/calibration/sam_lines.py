"""The pitch lines with SAM 3.1, shown by the coach (docs/prompts/14-referencias-y-lineas-pintadas.md).

OFF by default (PP_PITCH_SAM=1). When the light is poor or the lines are
faint, the coach paints over a line in a reference picture (a photo of the
pitch, a frame of the video, or the frame where the points were touched):
"this is a line". SAM gets one short MP4 at 1 fps (first the coach's
pictures, letterboxed to the video size, then the key frames: one per second,
plus the touched frames) in two requests, because the SAM docs say a noun
phrase cannot be combined with box or point prompts
(dev.meta.ai/docs/sam/segmenting):

- the concept (SAM_LINE_CONCEPT, a short noun phrase): every line SAM sees;
- the strokes as objects: each stroke is one object id with positive points
  along it, on its frame of the prompt video, written as tokens in the text
  ("<0f>1<|point;x=..;y=..;w=..;h=..|>..."); SAM follows each one through the
  video. A finger is wider than a far line, so every point is moved to the
  brightest pixel near it. At most 16 objects: the longest strokes go.

The masks of both, joined, are the white pixels of each key frame. With the
camera's place from a touched frame, pan, tilt and zoom are fitted so the
template falls on them, measured both ways (template → white and white →
template; one way alone lets the template shrink onto a strong line or
stretch over noise). A key frame that passes the quality filter becomes a new
key of the propagation; one with lines that contradict the propagated pitch
cuts it. There is no local line detector: if SAM fails, the coach's points
stay alone and a note says so.

Both requests follow the SAM docs but are NOT VERIFIED against real SAM: the
mask reader takes the documented "data=H,W,<marker><base85>" of the object's
box (alphabet and packing guessed) and is tolerant of other shapes (COCO RLE,
packed bits, PNG). Whether SAM follows a line from a photo into the video
frames is to be measured. Every raw stream is kept.
"""

from __future__ import annotations

import base64
import hashlib
import math
import re
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

from ..meta_api import MetaClient, cache_key
from ..types import MetaError
from .solver import Camera, project
from .template import Template, circle_polyline

# A short noun phrase: the SAM docs say filler and spatial words degrade matching.
DEFAULT_CONCEPT = "white line"
# Points along a stroke: one every this length (of the height), at most this many.
POINT_STEP = 0.08
MAX_POINTS = 8
# A point moves to the brightest pixel within this distance (of the height).
SNAP = 0.012
# Objects per request (SAM docs) and frames billed at least per request.
MAX_OBJECTS = 16
MIN_BILLED_FRAMES = 50
MAX_KEY_FRAMES = 900
MIN_WHITE_PX = 60
# Quality filter (measured in the prototype with local lines; same numbers for SAM).
MAX_TEMPLATE_TO_WHITE_PX = 2.5
MAX_WHITE_TO_TEMPLATE_PX = 2.0
MIN_EXPLAINED = 0.60
EXPLAINED_PX = 3.0
# Lines that contradict the propagated pitch: white mostly farther than this from it.
CONTRADICT_PX = 6.0
TAUS = (30.0, 8.0, 2.0)

# A token ends at the "|>" before the next token, record, frame or line end:
# a base85 mask payload may hold "|>" itself.
_TOKEN = re.compile(r"<(?P<frame>\d+)f>|<\|(?P<kind>box|mask);(?P<body>.*?)\|>(?=<\||,\s*\d+\s*<\||\s*<\d+f>|\s|$)", re.S)
# Attributes split only at a ";" followed by a known "name=": a compressed COCO
# string has ";", "=" and the letters a-o inside (";c=" can be part of it).
_NAMES = r"(?:counts|rle|size|height|width|png|bits|data|format|score|id)"
_ATTR = re.compile(r"(?:^|;)\s*(" + _NAMES + r")\s*=\s*(.*?)(?=;\s*" + _NAMES + r"\s*=|$)", re.S)


# ── what the coach painted ───────────────────────────────────────────────────


@dataclass
class Reference:
    """A picture of the pitch with the coach's strokes (normalized to the picture)."""

    image: np.ndarray | None  # BGR; None for a touched frame (taken from the video as it passes)
    strokes: list[list[tuple[float, float]]]
    source: str  # photo | frame | touched
    frame: int | None = None  # global frame (touched)


def strokes_of(calibration: dict | None) -> tuple[list[dict], dict[int, list[list[tuple[float, float]]]]]:
    """(references [{n, source, strokes}], {touched frame index in calibration["frames"]: strokes})."""
    cal = calibration or {}
    refs = [r for r in cal.get("references") or [] if isinstance(r, dict)]
    touched: dict[int, list[list[tuple[float, float]]]] = {}
    for i, f in enumerate(cal.get("frames") or []):
        lines = [[(p["x"], p["y"]) for p in ln.get("points") or []] for ln in f.get("lines") or []]
        lines = [s for s in lines if len(s) >= 2]
        if lines:
            touched[i] = lines
    return refs, touched


def stroke_points(strokes: list[list[tuple[float, float]]], aspect: float) -> list[list[tuple[float, float]]]:
    """Normalized points along each stroke, evenly by length: one every
    POINT_STEP of the height, both ends included, at most MAX_POINTS."""
    out = []
    for s in strokes:
        pts = np.asarray(s, dtype=float)
        if len(pts) < 2:
            continue
        seg = np.diff(pts, axis=0) * [aspect, 1.0]
        cum = np.r_[0.0, np.cumsum(np.hypot(seg[:, 0], seg[:, 1]))]
        if cum[-1] <= 0:
            continue
        n = min(MAX_POINTS, max(2, int(math.ceil(cum[-1] / POINT_STEP)) + 1))
        ts = np.linspace(0, cum[-1], n)
        out.append([(float(np.interp(t, cum, pts[:, 0])), float(np.interp(t, cum, pts[:, 1]))) for t in ts])
    return out


def snap(img: np.ndarray, points: list[tuple[float, float]], radius: int) -> list[tuple[int, int]]:
    """Each normalized point → the brightest pixel within radius (in pixels)."""
    h, w = img.shape[:2]
    gray = img if img.ndim == 2 else img.min(axis=2)  # white is bright in every channel; grass is not
    out = []
    for x, y in points:
        px, py = min(w - 1, max(0, int(x * w))), min(h - 1, max(0, int(y * h)))
        x0, y0 = max(0, px - radius), max(0, py - radius)
        win = gray[y0:py + radius + 1, x0:px + radius + 1]
        ys, xs = np.nonzero(win == win.max())
        i = int(np.argmin((xs + x0 - px) ** 2 + (ys + y0 - py) ** 2))  # the nearest of the brightest
        out.append((x0 + int(xs[i]), y0 + int(ys[i])))
    return out


@dataclass
class StrokeObject:
    """One stroke for SAM: its frame of the prompt video and its points in pixels."""

    frame: int
    points: list[tuple[int, int]]

    @property
    def length(self) -> float:
        p = np.asarray(self.points, dtype=float)
        return float(np.hypot(*np.diff(p, axis=0).T).sum()) if len(p) > 1 else 0.0


def object_prompt(objects: list[StrokeObject], width: int, height: int) -> str:
    """The point prompt of the SAM docs: "<Nf>" per frame, then per object its
    id and its points, records split by ","."""
    by_frame: dict[int, list[tuple[int, StrokeObject]]] = {}
    for i, o in enumerate(objects, start=1):
        by_frame.setdefault(o.frame, []).append((i, o))
    parts = []
    for k in sorted(by_frame):
        recs = [f"{i}" + "".join(f"<|point;x={x};y={y};w={width};h={height}|>" for x, y in o.points) for i, o in by_frame[k]]
        parts.append(f"<{k}f>" + ",".join(recs))
    return "".join(parts)


def letterbox(img: np.ndarray, width: int, height: int) -> tuple[np.ndarray, tuple[float, float, float, float]]:
    """The picture fitted inside width × height on black; (sx, sy, ox, oy) maps a
    normalized point of the picture to the frame: (x * sx + ox, y * sy + oy)."""
    import cv2

    h, w = img.shape[:2]
    s = min(width / w, height / h)
    nw, nh = max(1, round(w * s)), max(1, round(h * s))
    out = np.zeros((height, width, 3), dtype=np.uint8)
    x0, y0 = (width - nw) // 2, (height - nh) // 2
    out[y0:y0 + nh, x0:x0 + nw] = cv2.resize(img, (nw, nh), interpolation=cv2.INTER_AREA)
    return out, (nw / width, nh / height, x0 / width, y0 / height)


# ── SAM's masks ──────────────────────────────────────────────────────────────


def _coco_string(s: str) -> list[int]:
    """pycocotools' compressed RLE string → counts."""
    counts: list[int] = []
    p = 0
    while p < len(s):
        x, k, more = 0, 0, True
        while more:
            c = ord(s[p]) - 48
            x |= (c & 0x1F) << (5 * k)
            more = bool(c & 0x20)
            p += 1
            k += 1
            if not more and (c & 0x10):
                x |= -1 << (5 * k)
        if len(counts) > 2:
            x += counts[-2]
        counts.append(x)
    return counts


def _from_counts(counts: list[int], h: int, w: int) -> np.ndarray:
    """COCO RLE (column-major, starting with zeros) → bool mask h × w."""
    flat = np.zeros(h * w, dtype=bool)
    pos, val = 0, False
    for c in counts:
        if val:
            flat[pos:pos + c] = True
        pos += c
        val = not val
    return flat.reshape(w, h).T


def _sam_payload(h: int, w: int, marker: str, payload: str) -> np.ndarray | None:
    """The documented SAM mask: base85 after "H,W," and a marker ("!" one bit,
    "~" lossless). The base85 alphabet and the packing are not documented:
    RFC 1924, Z85 and Ascii85 are tried, then packed bits or one byte a pixel,
    row-major (zlib-compressed for "~", if it decompresses)."""
    import zlib

    decoders = [base64.b85decode, getattr(base64, "z85decode", None), base64.a85decode]
    for dec in decoders:
        if dec is None:
            continue
        try:
            raw = dec(payload.encode("ascii"))
        except (ValueError, TypeError):
            continue
        if marker == "~":
            try:
                raw = zlib.decompress(raw)
            except zlib.error:
                pass
        data = np.frombuffer(raw, np.uint8)
        if marker == "~" and data.size == h * w:
            return data.reshape(h, w) > 0
        bits = np.unpackbits(data)
        if bits.size >= h * w and bits.size - h * w < 8:
            return bits[: h * w].reshape(h, w).astype(bool)
    return None


def decode_mask(body: str, box: tuple[float, float, float, float] | None, width: int, height: int) -> np.ndarray | None:
    """One <|mask;...|> → bool mask of the whole frame, or None if unreadable.

    Tries COCO RLE (counts as numbers or as pycocotools' string), base64 packed
    bits and a base64 PNG; the mask may be of the whole frame or of the box.
    """
    import cv2

    attrs = {k: v.strip() for k, v in _ATTR.findall(body)}
    size = attrs.get("size", "")
    nums = [int(v) for v in re.findall(r"\d+", size)]
    h, w = (nums[0], nums[1]) if len(nums) == 2 else (int(attrs.get("height", 0) or 0), int(attrs.get("width", 0) or 0))
    mask = None
    try:
        if "counts" in attrs or "rle" in attrs:
            c = attrs.get("counts", attrs.get("rle", "")).strip().strip("[]\"'")
            counts = [int(v) for v in re.split(r"[\s,]+", c) if v] if re.fullmatch(r"[\d\s,]+", c) else _coco_string(c)
            if h and w:
                mask = _from_counts(counts, h, w)
        elif "png" in attrs or attrs.get("format") == "png":
            data = base64.b64decode(attrs.get("png") or attrs.get("data", ""))
            img = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_GRAYSCALE)
            mask = img > 127 if img is not None else None
        elif (sam := re.fullmatch(r"(\d+),(\d+),([~!])(\S*)", attrs.get("data", ""), re.S)):
            # dev.meta.ai/docs/sam/reading-segmentation: the mask of the object's box.
            h, w = int(sam.group(1)), int(sam.group(2))
            mask = _sam_payload(h, w, sam.group(3), sam.group(4))
        elif "bits" in attrs or "data" in attrs:
            data = np.frombuffer(base64.b64decode(attrs.get("bits") or attrs["data"]), np.uint8)
            if h and w:
                mask = np.unpackbits(data)[: h * w].reshape(h, w).astype(bool)
    except (ValueError, IndexError, TypeError):
        return None
    if mask is None or mask.size == 0:
        return None
    if mask.shape == (height, width):
        return mask
    if box is not None:
        # The mask of the object's box.
        x, y, bw, bh = box
        x1, y1 = int(round(x * width)), int(round(y * height))
        x2, y2 = max(x1 + 1, int(round((x + bw) * width))), max(y1 + 1, int(round((y + bh) * height)))
        out = np.zeros((height, width), dtype=bool)
        crop = cv2.resize(mask.astype(np.uint8), (x2 - x1, y2 - y1), interpolation=cv2.INTER_NEAREST).astype(bool)
        out[y1:y2, x1:x2] = crop[: height - y1, : width - x1]
        return out
    return cv2.resize(mask.astype(np.uint8), (width, height), interpolation=cv2.INTER_NEAREST).astype(bool)


def parse_masks(text: str, width: int, height: int) -> tuple[dict[int, np.ndarray], int]:
    """SAM output → ({prompt frame: union of the masks}, objects without a readable mask)."""
    out: dict[int, np.ndarray] = {}
    frame: int | None = None
    box = None
    unreadable = 0
    for m in _TOKEN.finditer(text):
        if m.group("frame") is not None:
            frame, box = int(m.group("frame")), None
            continue
        if frame is None:
            continue
        if m.group("kind") == "box":
            kv = {k: float(v) for k, v in re.findall(r"(x1|y1|x2|y2)\s*=\s*(-?\d+(?:\.\d+)?)", m.group("body"))}
            if len(kv) == 4:
                x1, y1, x2, y2 = kv["x1"], kv["y1"], kv["x2"], kv["y2"]
                if max(x1, y1, x2, y2) > 1.5:  # source pixels, the end inclusive
                    x1, x2, y1, y2 = x1 / width, (x2 + 1) / width, y1 / height, (y2 + 1) / height
                box = (x1, y1, x2 - x1, y2 - y1)
            continue
        mask = decode_mask(m.group("body"), box, width, height)
        if mask is None:
            unreadable += 1
            continue
        out[frame] = out[frame] | mask if frame in out else mask
    return out, unreadable


def thin(mask: np.ndarray) -> np.ndarray:
    """A morphological skeleton: a thick mask becomes strokes about a pixel wide."""
    import cv2

    img = mask.astype(np.uint8) * 255
    skel = np.zeros_like(img)
    kernel = cv2.getStructuringElement(cv2.MORPH_CROSS, (3, 3))
    for _ in range(64):
        eroded = cv2.erode(img, kernel)
        skel |= cv2.subtract(img, cv2.dilate(eroded, kernel))
        img = eroded
        if not img.any():
            break
    return skel > 0


# ── fitting the camera to the white pixels ───────────────────────────────────


@dataclass
class Samples:
    """Template segments every ~1 m: ends (N×2 m) and family (0 along, 1 across, 2 circle)."""

    a: np.ndarray
    b: np.ndarray
    family: np.ndarray

    @staticmethod
    def of(t: Template, step: float = 1.0) -> "Samples":
        a, b, fam = [], [], []
        for p, q in t.lines.values():
            p, q = np.array(p, dtype=float), np.array(q, dtype=float)
            n = max(1, int(math.ceil(np.linalg.norm(q - p) / step)))
            pts = [p + (q - p) * k / n for k in range(n + 1)]
            a += pts[:-1]
            b += pts[1:]
            fam += [0 if abs(q[1] - p[1]) < 1e-9 else 1] * n
        circle = circle_polyline(t, 96)
        a += circle[:-1]
        b += circle[1:]
        fam += [2] * (len(circle) - 1)
        return Samples(np.array(a, dtype=float), np.array(b, dtype=float), np.array(fam))


def _in_front(H: np.ndarray, pts: np.ndarray) -> np.ndarray:
    return (np.hstack([pts, np.ones((len(pts), 1))]) @ H.T)[:, 2] > 1e-9


def _bilinear(img: np.ndarray, xy: np.ndarray) -> np.ndarray:
    h, w = img.shape
    x = np.clip(xy[:, 0], 0, w - 1.001)
    y = np.clip(xy[:, 1], 0, h - 1.001)
    x0, y0 = x.astype(int), y.astype(int)
    fx, fy = x - x0, y - y0
    return (img[y0, x0] * (1 - fx) * (1 - fy) + img[y0, x0 + 1] * fx * (1 - fy)
            + img[y0 + 1, x0] * (1 - fx) * fy + img[y0 + 1, x0 + 1] * fx * fy)


@dataclass
class WhiteFrame:
    """The white pixels of one key frame and what is precomputed on them."""

    pts: np.ndarray  # N×2 pixels (subsampled)
    dist: np.ndarray  # distance (px) of every pixel to the nearest white one
    width: int
    height: int

    @staticmethod
    def of(mask: np.ndarray, max_pts: int = 500, seed: int = 0) -> "WhiteFrame":
        import cv2

        sk = thin(mask)
        ys, xs = np.nonzero(sk)
        pts = np.column_stack([xs, ys]).astype(float)
        if len(pts) > max_pts:
            pts = pts[np.random.default_rng(seed).choice(len(pts), max_pts, replace=False)]
        dist = cv2.distanceTransform((~sk).astype(np.uint8), cv2.DIST_L2, 3).astype(np.float32)
        return WhiteFrame(pts, dist, mask.shape[1], mask.shape[0])


def measure(H: np.ndarray, s: Samples, wf: WhiteFrame) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """(template → white per visible sample, white → template per white pixel, nearest family per white pixel)."""
    ok = _in_front(H, s.a) & _in_front(H, s.b)
    pa, pb = project(H, s.a[ok]), project(H, s.b[ok])
    margin = 0.25 * max(wf.width, wf.height)
    keep = ((np.minimum(pa, pb) > -margin) & (np.maximum(pa, pb) < [wf.width + margin, wf.height + margin])).all(axis=1)
    pa, pb, fam = pa[keep], pb[keep], s.family[ok][keep]
    inside = (pa[:, 0] >= 0) & (pa[:, 0] < wf.width) & (pa[:, 1] >= 0) & (pa[:, 1] < wf.height)
    t2w = _bilinear(wf.dist, pa[inside]) if inside.any() else np.zeros(0)
    if not len(pa) or not len(wf.pts):
        return t2w, np.full(len(wf.pts), np.inf), np.full(len(wf.pts), -1)
    # White pixel → nearest projected template segment (brute force, small sizes).
    d = pb - pa
    L2 = np.maximum((d ** 2).sum(axis=1), 1e-12)
    rel = wf.pts[:, None, :] - pa[None, :, :]
    t = np.clip((rel * d[None]).sum(axis=2) / L2[None], 0, 1)
    dist = np.linalg.norm(rel - t[..., None] * d[None], axis=2)
    j = dist.argmin(axis=1)
    return t2w, dist[np.arange(len(wf.pts)), j], fam[j]


def score(H: np.ndarray, s: Samples, wf: WhiteFrame) -> dict:
    t2w, w2t, fam = measure(H, s, wf)
    near = t2w[t2w < 10.0]
    explained = w2t <= EXPLAINED_PX
    fams = [int((fam[explained] == f).sum()) for f in (0, 1, 2)]
    total = max(1, int(explained.sum()))
    return {
        "template_to_white_px": float(np.median(near)) if len(near) else math.inf,
        "white_to_template_px": float(np.median(w2t)) if len(w2t) else math.inf,
        "explained": float(explained.mean()) if len(w2t) else 0.0,
        "families": sum(1 for f in fams if f >= 0.1 * total),
    }


def passes(sc: dict) -> bool:
    return (sc["template_to_white_px"] <= MAX_TEMPLATE_TO_WHITE_PX and sc["white_to_template_px"] <= MAX_WHITE_TO_TEMPLATE_PX
            and sc["explained"] >= MIN_EXPLAINED and sc["families"] >= 2)


def fit_camera(cam: Camera, s: Samples, wf: WhiteFrame) -> Camera:
    """Pan, tilt and zoom (place and roll fixed) that put the template on the white
    pixels, both ways, with a truncation that drops from 30 px to 2 px."""
    from .solver import least_squares

    for tau in TAUS:
        def fun(q, tau=tau):
            p = cam.params.copy()
            p[[3, 4, 6]] = q
            H = cam.with_params(p).homography()
            ok = _in_front(H, s.a)
            img = project(H, s.a[ok]) if ok.any() else np.zeros((0, 2))
            inside = (img[:, 0] >= 0) & (img[:, 0] < wf.width) & (img[:, 1] >= 0) & (img[:, 1] < wf.height)
            r1 = np.full(len(s.a), tau)
            idx = np.nonzero(ok)[0][inside]
            r1[idx] = np.minimum(_bilinear(wf.dist, img[inside]), tau)
            _, w2t, _ = measure(H, s, wf)
            r2 = np.minimum(w2t, tau)
            # Both ways weigh the same whatever their sizes.
            return np.r_[r1 / math.sqrt(len(r1)), r2 / math.sqrt(max(1, len(r2)))]

        q = least_squares(fun, cam.params[[3, 4, 6]], iters=15, scale=np.array([2e-4, 2e-4, 0.5]))
        p = cam.params.copy()
        p[[3, 4, 6]] = q
        cam = cam.with_params(p)
    return cam


def two_directions(mask: np.ndarray) -> bool:
    """White pixels along two clearly different directions (doubled-angle histogram of the gradient)."""
    import cv2

    img = cv2.GaussianBlur(mask.astype(np.float32), (0, 0), 1.5)
    gx = cv2.Sobel(img, cv2.CV_32F, 1, 0)
    gy = cv2.Sobel(img, cv2.CV_32F, 0, 1)
    mag = np.hypot(gx, gy)
    strong = mag > 0.2 * mag.max() if mag.max() > 0 else mag > 1
    if strong.sum() < 20:
        return False
    ang = (np.arctan2(gy[strong], gx[strong]) * 2) % (2 * math.pi)
    hist, _ = np.histogram(ang, bins=18, range=(0, 2 * math.pi), weights=mag[strong])
    hist = hist / hist.sum()
    peak = int(hist.argmax())
    far = [hist[k] for k in range(18) if min(abs(k - peak), 18 - abs(k - peak)) >= 3]  # ≥ 30° apart
    return bool(far) and max(far) >= 0.15


# ── the call ─────────────────────────────────────────────────────────────────


@dataclass
class SamLines:
    client: MetaClient
    model: str
    concept: str = DEFAULT_CONCEPT
    raw_dir: Path | None = None
    simulator: bool = False
    streams: list[str] = field(default_factory=list)

    def call(self, video: Path, text: str, frames: int, name: str) -> str:
        """One streamed request: the text (the concept or the point prompt) and the prompt video."""
        sha = hashlib.sha256(video.read_bytes()).hexdigest()
        key = cache_key(self.client.base_url, sha, self.model, text)
        cached = self.client.cache.get(key)
        if cached is not None:
            self.client.usage.cached += 1
            resp = cached
        else:
            file_id, sim = self.client.upload(video, sha)
            self.simulator |= sim
            content = [{"type": "input_text", "text": text}, {"type": "input_video", "file_id": file_id}]
            body = {"model": self.model, "input": [{"type": "message", "role": "user", "content": content}],
                    "metadata": {"include_confidence": "true", "mask_encoding": "one_bit"}}
            resp = self.client.respond(body, key, {"model": self.model, "prompt": text[:200], "video_sha256": sha})
            self.client.usage.frames_sent += max(frames, MIN_BILLED_FRAMES)
        self.simulator |= resp.simulator
        if self.raw_dir is not None:
            self.raw_dir.mkdir(parents=True, exist_ok=True)
            fname = f"sam_pitch_lines_{name}_{sha[:8]}.sse"
            (self.raw_dir / fname).write_text(resp.raw, encoding="utf-8")
            self.streams.append(fname)
        return resp.text


class PromptVideo:
    """The MP4 for SAM: the coach's pictures first, then the key frames as the
    video passes (frames are never all kept in memory)."""

    def __init__(self, path: Path, width: int, height: int, references: list[Reference]):
        import cv2

        self.path, self.width, self.height = path, width, height
        path.parent.mkdir(parents=True, exist_ok=True)
        self.writer = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"mp4v"), 1, (width, height))
        self.order: list[int | None] = []  # prompt frame → global frame (None for a picture)
        self.objects: list[StrokeObject] = []
        for ref in references:
            if ref.image is None:
                continue
            img, (sx, sy, ox, oy) = letterbox(ref.image, width, height)
            self._strokes(img, [[(x * sx + ox, y * sy + oy) for x, y in st] for st in ref.strokes])
            self.order.append(None)
            self.writer.write(img)

    def _strokes(self, img: np.ndarray, strokes: list[list[tuple[float, float]]]) -> None:
        radius = max(1, round(SNAP * self.height))
        for pts in stroke_points(strokes, self.width / self.height):
            self.objects.append(StrokeObject(len(self.order), snap(img, pts, radius)))

    def add(self, g: int, img: np.ndarray, strokes: list[list[tuple[float, float]]] | None = None) -> None:
        if strokes:
            self._strokes(img, strokes)
        self.order.append(g)
        self.writer.write(img)

    def close(self) -> int:
        self.writer.release()
        return len(self.order)

    def sent_objects(self) -> list[StrokeObject]:
        """The longest MAX_OBJECTS strokes, in their painted order."""
        keep = sorted(sorted(range(len(self.objects)), key=lambda i: -self.objects[i].length)[:MAX_OBJECTS])
        return [self.objects[i] for i in keep]


def refine_keys(white: dict[int, np.ndarray], start: dict[int, np.ndarray], camera: Camera, t: Template) -> tuple[dict[int, np.ndarray], set[int], list[dict]]:
    """For each key frame with white pixels: if its propagated homography
    already falls on them it is confirmed; if not, pan, tilt and zoom are
    fitted from it (or from the nearest one). Returns (new keys, frames where
    the lines contradict the propagated pitch, report per frame)."""
    s = Samples.of(t)
    keys: dict[int, np.ndarray] = {}
    contradicted: set[int] = set()
    report = []
    known = sorted(start)
    for g, mask in sorted(white.items()):
        if mask.sum() < MIN_WHITE_PX or not known:
            continue
        wf = WhiteFrame.of(mask)
        nearest = min(known, key=lambda k: abs(k - g))
        H0 = start.get(g, start[nearest])
        before = score(H0, s, wf)
        if g in start and passes(before):
            report.append({"frame": g, "from": "propagated", "used": False, "confirmed": True})
            continue
        try:
            cam = fit_camera(camera.params_for(H0, s.a[:: max(1, len(s.a) // 200)]), s, wf)
            H = cam.homography()
            sc = score(H, s, wf)
        except (np.linalg.LinAlgError, ValueError, FloatingPointError):
            H, sc = None, None
        entry = {"frame": g, "from": "propagated" if g in start else "nearest", "used": False}
        if sc is not None:
            entry.update({k: (round(v, 3) if isinstance(v, float) and math.isfinite(v) else v) for k, v in sc.items()})
        if H is not None and sc is not None and passes(sc):
            keys[g] = H / H[2, 2]
            entry["used"] = True
        elif g in start:
            if before["white_to_template_px"] > CONTRADICT_PX and before["explained"] < 0.3 and two_directions(mask):
                contradicted.add(g)
                entry["contradicts"] = True
        report.append(entry)
    return keys, contradicted, report


def find_white(lines: SamLines, video: PromptVideo) -> tuple[dict[int, np.ndarray], int]:
    """SAM's white pixels per global key frame (the concept and the strokes,
    joined), and the prompt frames charged. Raises MetaError when no request
    worked: each one failed or answered objects without a readable mask."""
    frames = video.close()
    requests = [("concept", lines.concept)]
    if objects := video.sent_objects():
        requests.append(("strokes", object_prompt(objects, video.width, video.height)))
    joined: dict[int, np.ndarray] = {}
    errors: list[MetaError] = []
    charged = 0
    for name, text in requests:
        before = lines.client.usage.frames_sent
        try:
            masks, unreadable = parse_masks(lines.call(video.path, text, frames, name), video.width, video.height)
        except MetaError as e:
            errors.append(e)
            continue
        finally:
            charged += lines.client.usage.frames_sent - before
        if unreadable and not masks:
            errors.append(MetaError("meta_sam", f"SAM returned {unreadable} line objects without a readable mask ({name}; raw stream kept)"))
            continue
        for k, mask in masks.items():
            joined[k] = joined[k] | mask if k in joined else mask
    if len(errors) == len(requests):
        raise errors[0]
    out = {}
    for k, mask in joined.items():
        if 0 <= k < len(video.order) and video.order[k] is not None:
            out[video.order[k]] = mask
    return out, charged
