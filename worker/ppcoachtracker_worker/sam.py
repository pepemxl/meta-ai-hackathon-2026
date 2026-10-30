"""SAM 3.1 through the Meta Model API: players and ball, per chunk and concept.

Per chunk: POST /files (once) and one streamed POST /responses per concept
("player in <color> jersey" for each team and "soccer ball"; "soccer player"
when the colors cannot tell the teams apart): short noun phrases, one concept
per request, as the SAM docs ask (dev.meta.ai/docs/sam/segmenting). The stream
has one line per frame: "<Nf>" and comma-separated records, each the object id,
"<|box;x1=..;y1=..;x2=..;y2=..;w=..;h=..;c=..|>" in source pixels and a
"<|mask;...|>" (dev.meta.ai/docs/sam/reading-segmentation); the masks are not
needed here and are dropped before reading. Every raw stream is kept.

THE FORMAT IS THE DOCUMENTED ONE, NOT YET VERIFIED against a real SAM stream:
the parser is tolerant, and it must be adjusted (with its tests) to the
first real stream (worker/README.md).

SAM keeps identities inside one call (one chunk). Between chunks, tracks are
joined by IoU on the overlap frames (the same frames of the original): the
same identity if IoU > 0.5 in at least half of the overlap frames where both
appear, and in 5 or more. The overlap is kept once.

Wide video (a panorama, or a long side over 2048 px; PP_TILES=auto) goes
to SAM in tiles of up to 1024 px over the pitch (tiles.py): SAM resizes every
frame to 1024 px on its long side, and in a whole panorama the players vanish.
Each tile is one request per concept; the tracks of neighbouring tiles are
joined in their overlap before anything else, so the rest of the worker sees
one chunk as always.

In development SAM_API_BASE_URL points to the local simulator: its results
are marked "sam_simulator", never "meta_sam".
"""

from __future__ import annotations

import re
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from . import teams, tiles
from .chunks import Chunk, Clip, read_frames
from .colors import is_known, normalize
from .meta_api import MetaClient, cache_key
from .types import TrackRow, TrackSet, iou

STITCH_IOU = 0.5
STITCH_MIN_FRAMES = 5
# Two team concepts that return the same person: IoU above this on half of their shared frames.
SAME_OBJECT_IOU = 0.6
COLOR_SAMPLES = 5

_TOKEN = re.compile(r"<(?P<frame>\d+)f>|(?:(?P<ord>\d+)\s*[:.)\]-]?\s*)?<\|box;(?P<box>[^|>]*)\|>")
# A mask record: its base85 payload may hold "<3f>" or digits, so it is cut out
# whole (it ends at the "|>" before the next record, frame or line end).
_MASK = re.compile(r"<\|mask;.*?\|>(?=,\s*\d+\s*<\||\s*<\d+f>|\s|$)", re.S)
_KV = re.compile(r"(x1|y1|x2|y2|c)\s*=\s*(-?\d+(?:\.\d+)?(?:[eE]-?\d+)?)")


@dataclass(frozen=True)
class Concept:
    text: str
    label: str  # "player" | "ball"
    team: str  # "A" | "B" | "" (decided by color)


def concepts_for(home_color: str, away_color: str, player_concept: str = "player in {color} jersey", ball_concept: str = "soccer ball") -> list[Concept]:
    home, away = normalize(home_color), normalize(away_color)
    if is_known(home) and is_known(away) and home != away:
        players = [Concept(player_concept.format(color=home), "player", "A"), Concept(player_concept.format(color=away), "player", "B")]
    else:
        players = [Concept("soccer player", "player", "")]
    return players + [Concept(ball_concept, "ball", "")]


def parse_stream(text: str, width: int, height: int) -> dict[int, list[tuple[int, tuple[float, float, float, float], float]]]:
    """SAM output text → {chunk frame: [(ordinal, (x, y, w, h) normalized, conf)]}.

    Tolerant: boxes before the first frame mark are ignored, a box without an
    ordinal gets its position in the frame, pixel boxes (any value > 1.5) are
    normalized with the video size, an unfinished box at the end of a cut
    stream is dropped.
    """
    out: dict[int, list[tuple[int, tuple[float, float, float, float], float]]] = defaultdict(list)
    frame: int | None = None
    text = _MASK.sub("", text)
    if (cut := text.find("<|mask;")) >= 0:  # an unfinished mask at the end of a cut stream
        text = text[:cut]
    for m in _TOKEN.finditer(text):
        if m.group("frame") is not None:
            frame = int(m.group("frame"))
            continue
        if frame is None:
            continue
        kv = {k: float(v) for k, v in _KV.findall(m.group("box"))}
        if not all(k in kv for k in ("x1", "y1", "x2", "y2")):
            continue
        x1, y1, x2, y2 = kv["x1"], kv["y1"], kv["x2"], kv["y2"]
        if max(x1, y1, x2, y2) > 1.5:
            x1, x2 = x1 / max(width, 1), x2 / max(width, 1)
            y1, y2 = y1 / max(height, 1), y2 / max(height, 1)
        x1, x2 = sorted((min(max(x1, 0.0), 1.0), min(max(x2, 0.0), 1.0)))
        y1, y2 = sorted((min(max(y1, 0.0), 1.0), min(max(y2, 0.0), 1.0)))
        if x2 - x1 <= 0 or y2 - y1 <= 0:
            continue
        ordinal = int(m.group("ord")) if m.group("ord") is not None else len(out[frame]) + 1
        out[frame].append((ordinal, (x1, y1, x2 - x1, y2 - y1), float(kv.get("c", 1.0))))
    return dict(out)


@dataclass
class LocalRow:
    local: int  # chunk frame
    key: tuple[int, int]  # (concept index, SAM ordinal)
    label: str
    team: str
    box: tuple[float, float, float, float]
    conf: float


def _same_object(a: list[LocalRow], b: list[LocalRow]) -> bool:
    bf = {r.local: r.box for r in b}
    shared = [(r.box, bf[r.local]) for r in a if r.local in bf]
    if len(shared) < 3:
        return False
    good = sum(1 for x, y in shared if iou(x, y) > SAME_OBJECT_IOU)
    return good * 2 >= len(shared)


def stitch(prev: list[tuple[int, int, str, tuple]], cur: list[tuple[int, tuple[int, int], str, tuple]]) -> dict[tuple[int, int], int]:
    """Joins tracks across a chunk boundary on the overlap frames.

    prev: (source frame, global id, label, box) of the previous chunk.
    cur: (source frame, local key, label, box) of the current chunk's overlap.
    Returns {local key: global id} for the pairs with IoU > 0.5 in at least
    half of the frames where both appear, and in 5 or more.
    """
    by_src: dict[int, list[tuple[int, str, tuple]]] = defaultdict(list)
    for src, gid, label, box in prev:
        by_src[src].append((gid, label, box))
    shared: dict[tuple[int, tuple[int, int]], int] = defaultdict(int)
    good: dict[tuple[int, tuple[int, int]], int] = defaultdict(int)
    cur_by_src: dict[int, dict[tuple[int, int], tuple[str, tuple]]] = defaultdict(dict)
    for src, key, label, box in cur:
        cur_by_src[src][key] = (label, box)
    for src, objs in cur_by_src.items():
        for gid, plabel, pbox in by_src.get(src, []):
            for key, (label, box) in objs.items():
                if label != plabel:
                    continue
                shared[(gid, key)] += 1
                if iou(pbox, box) > STITCH_IOU:
                    good[(gid, key)] += 1
    candidates = sorted(
        ((g, s, pair) for pair, s in shared.items() if (g := good[pair]) >= STITCH_MIN_FRAMES and g * 2 >= s),
        key=lambda t: (-t[0], -t[0] / t[1]),
    )
    used_gid: set[int] = set()
    out: dict[tuple[int, int], int] = {}
    for _, _, (gid, key) in candidates:
        if gid in used_gid or key in out:
            continue
        out[key] = gid
        used_gid.add(gid)
    return out


def _slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", text.lower()).strip("_")


@dataclass
class TileVideo:
    """One tile of a chunk, sent to SAM as if it were a chunk."""

    number: int
    tile: int
    video: Path
    frame_count: int
    _sha: str | None = None

    @property
    def sha256(self) -> str:
        if self._sha is None:
            import hashlib

            self._sha = hashlib.sha256(self.video.read_bytes()).hexdigest()
        return self._sha


class SamSegmenter:
    def __init__(self, client: MetaClient, model: str, raw_dir: Path | None = None, is_meta_url: bool = True,
                 player_concept: str = "player in {color} jersey", ball_concept: str = "soccer ball", tile_mode: str = "auto"):
        self.tile_mode = tile_mode
        # Set by plan_tiles(): None = whole chunks, as always.
        self.tile_plan: tiles.TilePlan | None = None
        self.tile_joins = 0
        self.player_concept = player_concept
        self.ball_concept = ball_concept
        self.client = client
        self.model = model
        self.raw_dir = raw_dir
        self.simulator = not is_meta_url
        self.streams: list[str] = []

    # ── one chunk ────────────────────────────────────────────────────────────

    def plan_tiles(self, clip: Clip) -> tiles.TilePlan | None:
        """Whether this clip goes in tiles, and which: over the pitch the app
        measured (or measured here on a few frames when it did not come)."""
        self.tile_plan = None
        if not clip.chunks or not tiles.should_tile(self.tile_mode, clip.width, clip.height):
            return None
        polygons = [list(m.polygon) for m in clip.pitch_masks() if m is not None and m.has_pitch]
        if not polygons:
            from .pitch import measure

            first = clip.chunks[0]
            wanted = set(range(0, first.frame_count, max(1, first.frame_count // 12)))
            polygons = [list(m.polygon) for _, img in read_frames(first, wanted) if (m := measure(img)).has_pitch]
        self.tile_plan = tiles.plan(clip.width, clip.height, tiles.pitch_box(polygons, clip.width, clip.height))
        return self.tile_plan

    def _call(self, chunk: Chunk | TileVideo, concept: Concept) -> str:
        key = cache_key(self.client.base_url, chunk.sha256, self.model, concept.text)
        resp = self.client.cache.get(key)
        if resp is not None:
            self.client.usage.cached += 1
        else:
            file_id, sim = self.client.upload(chunk.video, chunk.sha256)
            self.simulator |= sim
            body = {
                "model": self.model,
                "input": [{"type": "message", "role": "user", "content": [
                    {"type": "input_text", "text": concept.text},
                    {"type": "input_video", "file_id": file_id},
                ]}],
                "metadata": {"include_confidence": "true", "mask_encoding": "one_bit"},
            }
            resp = self.client.respond(body, key, {"model": self.model, "concept": concept.text, "chunk_sha256": chunk.sha256})
            self.client.usage.frames_sent += chunk.frame_count
        self.simulator |= resp.simulator
        if self.raw_dir is not None:
            self.raw_dir.mkdir(parents=True, exist_ok=True)
            tile = f"_t{chunk.tile}" if isinstance(chunk, TileVideo) else ""
            name = f"chunk_{chunk.number:03d}{tile}_{_slug(concept.text)}.sse"
            (self.raw_dir / name).write_text(resp.raw, encoding="utf-8")
            self.streams.append(name)
        return resp.text

    def chunk_rows(self, chunk: Chunk, concepts: list[Concept], home_color: str, away_color: str) -> list[LocalRow]:
        if self.tile_plan is not None:
            return self._resolve_teams(chunk, self._tiled_rows(chunk, concepts), concepts, home_color, away_color)
        rows: list[LocalRow] = []
        for ci, concept in enumerate(concepts):
            frames = parse_stream(self._call(chunk, concept), chunk.width or 1280, chunk.height or 720)
            for local, objs in frames.items():
                if local < 0 or local >= chunk.frame_count:
                    continue
                for ordinal, box, conf in objs:
                    rows.append(LocalRow(local, (ci, ordinal), concept.label, concept.team, box, conf))
        return self._resolve_teams(chunk, rows, concepts, home_color, away_color)

    def _tiled_rows(self, chunk: Chunk, concepts: list[Concept]) -> list[LocalRow]:
        """Every concept on every tile, joined back into the chunk's frame."""
        plan = self.tile_plan
        assert plan is not None
        w, h = chunk.width or 1280, chunk.height or 720
        paths = [chunk.video.parent / "tiles" / f"chunk_{chunk.number:03d}_t{t.index}.mp4" for t in plan.tiles]
        if not all(p.exists() for p in paths):
            paths = tiles.write_tiles(chunk.video, plan, chunk.video.parent / "tiles", f"chunk_{chunk.number:03d}", chunk.fps)
        videos = [TileVideo(chunk.number, t.index, p, chunk.frame_count) for t, p in zip(plan.tiles, paths)]
        edges = plan.edges(w)
        rows: list[LocalRow] = []
        for ci, concept in enumerate(concepts):
            boxes: list[tiles.TileBox] = []
            for t, video in zip(plan.tiles, videos):
                for local, objs in parse_stream(self._call(video, concept), t.out_w, t.out_h).items():
                    if 0 <= local < chunk.frame_count:
                        boxes += [tiles.TileBox(local, t.index, ordinal, concept.label, tiles.to_frame(box, t, w, h), conf) for ordinal, box, conf in objs]
            roots, joins = tiles.join_tracks(boxes, edges, label=concept.label)
            self.tile_joins += len(joins)
            for b in tiles.dedupe(boxes, roots, edges):
                rt, rk = roots[(b.tile, b.track)]
                rows.append(LocalRow(b.frame, (ci, rt * 100000 + rk), concept.label, concept.team, b.box, b.conf))
        return rows

    def _resolve_teams(self, chunk: Chunk, rows: list[LocalRow], concepts: list[Concept], home: str, away: str) -> list[LocalRow]:
        """One track per person; the color decides when the concept cannot."""
        tracks: dict[tuple[int, int], list[LocalRow]] = defaultdict(list)
        for r in rows:
            tracks[r.key].append(r)
        team_concepts = [i for i, c in enumerate(concepts) if c.label == "player" and c.team]
        undecided: set[tuple[int, int]] = {k for k in tracks if concepts[k[0]].label == "player" and not concepts[k[0]].team}
        dropped: set[tuple[int, int]] = set()
        if len(team_concepts) == 2:
            a_keys = [k for k in tracks if k[0] == team_concepts[0]]
            b_keys = [k for k in tracks if k[0] == team_concepts[1]]
            for ka in a_keys:
                for kb in b_keys:
                    if kb in dropped or ka in dropped:
                        continue
                    if _same_object(tracks[ka], tracks[kb]):
                        # The same person under both colors: keep the longer track, color decides.
                        keep, drop = (ka, kb) if len(tracks[ka]) >= len(tracks[kb]) else (kb, ka)
                        dropped.add(drop)
                        undecided.add(keep)
        players = {k: rs for k, rs in tracks.items() if concepts[k[0]].label == "player" and k not in dropped}
        labs = self._color_labs(chunk, players)
        decided = {k: teams.team_for_color(labs.get(k), home, away) for k in undecided}
        # A small third color group (the referee) stays without a team, even under a team concept.
        no_team = teams.third_group(labs, home, away)
        out = []
        for key, rs in tracks.items():
            if key in dropped:
                continue
            team = decided.get(key, rs[0].team) if key in undecided else rs[0].team
            if key in no_team:
                team = ""
            out.extend(LocalRow(r.local, r.key, r.label, team, r.box, r.conf) for r in rs)
        return out

    def _color_labs(self, chunk: Chunk, tracks: dict[tuple[int, int], list[LocalRow]]) -> dict[tuple[int, int], object]:
        """Median torso Lab of each track over a few of its frames (None if it cannot be read)."""
        samples: dict[tuple[int, int], list[LocalRow]] = {}
        wanted: set[int] = set()
        for key, rs in tracks.items():
            rs = sorted(rs, key=lambda r: r.local)
            idx = np.linspace(0, len(rs) - 1, min(COLOR_SAMPLES, len(rs))).round().astype(int)
            samples[key] = [rs[i] for i in sorted(set(idx.tolist()))]
            wanted.update(r.local for r in samples[key])
        try:
            images = dict(read_frames(chunk, wanted)) if wanted else {}
        except Exception:  # noqa: BLE001 - without frames the color cannot decide
            images = {}
        out = {}
        for key, rs in samples.items():
            labs = [l for l in (teams.torso_lab(images[r.local], r.box) for r in rs if r.local in images) if l is not None]
            out[key] = np.median(np.stack(labs), axis=0) if labs else None
        return out

    # ── whole clip ───────────────────────────────────────────────────────────

    def track(self, clip: Clip, home_color: str, away_color: str) -> TrackSet:
        concepts = concepts_for(home_color, away_color, self.player_concept, self.ball_concept)
        result = TrackSet(frames=clip.frame_infos())
        next_id = 1
        prev: list[tuple[int, int, str, tuple]] = []
        prev_team: dict[int, str] = {}
        for chunk in clip.chunks:
            rows = self.chunk_rows(chunk, concepts, home_color, away_color)
            mapping: dict[tuple[int, int], int] = {}
            if chunk.number > 1 and chunk.overlap_frames > 0:
                overlap = [(chunk.source_of(r.local), r.key, r.label, r.box) for r in rows if r.local < chunk.overlap_frames]
                mapping = stitch(prev, overlap)
            for key in sorted({r.key for r in rows}):
                if key not in mapping:
                    mapping[key] = next_id
                    next_id += 1
            current: list[tuple[int, int, str, tuple]] = []
            team_of: dict[int, str] = {}
            for r in sorted(rows, key=lambda r: (r.local, r.key)):
                gid = mapping[r.key]
                team = r.team or prev_team.get(gid, "")
                if prev_team.get(gid) and r.team and prev_team[gid] != r.team:
                    team = prev_team[gid]  # identity continues: keep its team
                team_of[gid] = team
                current.append((chunk.source_of(r.local), gid, r.label, r.box))
                g = clip.global_index(chunk, r.local)
                if g is None:
                    continue  # overlap: already in the previous chunk
                x, y, w, h = r.box
                result.rows.append(TrackRow(g, chunk.time_of(r.local), chunk.source_of(r.local), gid, r.label, x, y, w, h, r.conf, team if r.label == "player" else ""))
            prev = current
            prev_team.update(team_of)
        return result

    @property
    def segmenter(self) -> str:
        return "sam_simulator" if self.simulator else "meta_sam"
