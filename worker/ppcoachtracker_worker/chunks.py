"""Reads a clip as the app writes it (docs/12-videos-largos-chunks.md).

chunks.yaml + video_part_NNN.(mp4|yaml). The chunk profile (chunk_seconds,
overlap_ms) is read from the files, never assumed. The chunks are read in
order as one video, skipping the overlap_frames of every chunk after the
first, with the time of the original from each chunk's map.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterator

import yaml

from .types import FrameInfo, InputError

INDEX = "chunks.yaml"


def read_yaml(path: Path) -> dict:
    """PyYAML does not accept OpenCV's "%YAML:1.0" header: drop that line first."""
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as e:
        raise InputError(f"cannot read {path.name}: {e.strerror}") from None
    lines = text.splitlines()
    if lines and lines[0].startswith("%YAML"):
        lines = lines[1:]
    try:
        data = yaml.safe_load("\n".join(lines))
    except yaml.YAMLError as e:
        raise InputError(f"invalid YAML in {path.name}: {str(e).splitlines()[0]}") from None
    if not isinstance(data, dict):
        raise InputError(f"{path.name} is not a mapping")
    return data


@dataclass
class Chunk:
    number: int
    video: Path
    start_ms: int
    end_ms: int
    overlap_ms: int
    overlap_frames: int
    fps: float
    width: int
    height: int
    frame_count: int
    map: list[tuple[int, int, int]]  # (chunk frame, source frame, ms in the original)
    pitch: dict | None = None
    _sha: str | None = field(default=None, repr=False)

    def time_of(self, i: int) -> int:
        """ms in the original for chunk frame [i]; extrapolated past the end of the map."""
        if 0 <= i < len(self.map) and self.map[i][0] == i:
            return self.map[i][2]
        if not self.map:
            return self.start_ms + round(i * 1000 / self.fps)
        last = self.map[-1]
        return last[2] + round((i - last[0]) * 1000 / self.fps)

    def source_of(self, i: int) -> int:
        if 0 <= i < len(self.map) and self.map[i][0] == i:
            return self.map[i][1]
        if not self.map:
            return -1
        last = self.map[-1]
        step = (last[1] - self.map[0][1]) / max(1, last[0] - self.map[0][0]) if len(self.map) > 1 else 3
        return round(last[1] + (i - last[0]) * step)

    @property
    def sha256(self) -> str:
        if self._sha is None:
            h = hashlib.sha256()
            with self.video.open("rb") as f:
                for block in iter(lambda: f.read(1 << 20), b""):
                    h.update(block)
            self._sha = h.hexdigest()
        return self._sha


@dataclass
class Clip:
    dir: Path
    clip_id: int | None
    profile: str
    chunk_seconds: int
    overlap_ms: int
    chunks: list[Chunk]

    @property
    def frames_sent_per_concept(self) -> int:
        """Frames SAM receives per concept (overlaps included: each chunk is a call)."""
        return sum(c.frame_count for c in self.chunks)

    @property
    def unique_frames(self) -> int:
        return sum(c.frame_count - (c.overlap_frames if c.number > 1 else 0) for c in self.chunks)

    @property
    def duration_ms(self) -> int:
        return max((c.end_ms for c in self.chunks), default=0)

    @property
    def fps(self) -> float:
        return self.chunks[0].fps if self.chunks else 10.0

    @property
    def width(self) -> int:
        return self.chunks[0].width if self.chunks else 0

    @property
    def height(self) -> int:
        return self.chunks[0].height if self.chunks else 0

    def frame_infos(self) -> list[FrameInfo]:
        """Every frame of the clip once (overlaps skipped), with its global index."""
        out: list[FrameInfo] = []
        for c in self.chunks:
            first = c.overlap_frames if c.number > 1 else 0
            for i in range(first, c.frame_count):
                out.append(FrameInfo(len(out), c.time_of(i), c.source_of(i)))
        return out

    def global_index(self, chunk: Chunk, local: int) -> int | None:
        """Global frame of a chunk frame; None inside the overlap of chunks after the first."""
        base = 0
        for c in self.chunks:
            if c.number == chunk.number:
                first = c.overlap_frames if c.number > 1 else 0
                return None if local < first else base + local - first
            base += c.frame_count - (c.overlap_frames if c.number > 1 else 0)
        raise KeyError(chunk.number)


def _int(d: dict, key: str, where: str) -> int:
    v = d.get(key)
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        raise InputError(f"{where}: missing {key}")
    return int(v)


def load_clip(directory: Path) -> Clip:
    directory = Path(directory)
    index = read_yaml(directory / INDEX)
    count = _int(index, "count", INDEX)
    chunk_seconds = _int(index, "chunk_seconds", INDEX)
    overlap_ms = _int(index, "overlap_ms", INDEX)
    if count < 1:
        raise InputError("chunks.yaml: count must be >= 1")
    chunks: list[Chunk] = []
    for n in range(1, count + 1):
        video = directory / f"video_part_{n:03d}.mp4"
        meta_path = directory / f"video_part_{n:03d}.yaml"
        if not video.is_file() or not meta_path.is_file():
            raise InputError(f"chunk {n} missing ({video.name} or {meta_path.name})")
        meta = read_yaml(meta_path)
        ch = meta.get("chunk") or {}
        enc = meta.get("encoding") or {}
        frames = meta.get("frames") or {}
        where = meta_path.name
        rows = frames.get("map") or []
        try:
            fmap = [(int(r[0]), int(r[1]), int(r[2])) for r in rows]
        except (TypeError, ValueError, IndexError):
            raise InputError(f"{where}: invalid frames.map") from None
        chunks.append(Chunk(
            number=n,
            video=video,
            start_ms=_int(ch, "start_ms", where),
            end_ms=_int(ch, "end_ms", where),
            overlap_ms=int(ch.get("overlap_ms") or 0),
            overlap_frames=int(frames.get("overlap_frames") or 0) if n > 1 else 0,
            fps=float(enc.get("fps") or 10),
            width=int(enc.get("width") or 0),
            height=int(enc.get("height") or 0),
            frame_count=_int(frames, "count", where),
            map=fmap,
            pitch=meta.get("pitch") if isinstance(meta.get("pitch"), dict) else None,
        ))
    return Clip(
        dir=directory,
        clip_id=index.get("clip_id") if isinstance(index.get("clip_id"), int) else None,
        profile=str(index.get("profile") or ""),
        chunk_seconds=chunk_seconds,
        overlap_ms=overlap_ms,
        chunks=chunks,
    )


def read_frames(chunk: Chunk, wanted: set[int] | None = None) -> Iterator[tuple[int, "object"]]:
    """Decodes a chunk in order: (chunk frame, BGR image) for [wanted] (all if None)."""
    import cv2  # OpenCV only where frames are decoded

    cap = cv2.VideoCapture(str(chunk.video))
    if not cap.isOpened():
        raise InputError(f"cannot open {chunk.video.name}")
    try:
        i = 0
        last = max(wanted) if wanted else None
        while True:
            if last is not None and i > last:
                break
            ok = cap.grab()
            if not ok:
                break
            if wanted is None or i in wanted:
                ok, img = cap.retrieve()
                if ok:
                    yield i, img
            i += 1
    finally:
        cap.release()


def iter_frames(clip: Clip) -> Iterator[tuple[FrameInfo, Chunk, int, "object"]]:
    """The clip as one video: (frame info, chunk, chunk frame, image), overlaps skipped."""
    g = 0
    for c in clip.chunks:
        first = c.overlap_frames if c.number > 1 else 0
        for i, img in read_frames(c):
            if i < first:
                continue
            yield FrameInfo(g, c.time_of(i), c.source_of(i)), c, i, img
            g += 1


# ── writing (tools/make_chunks.py and tests): the same format the app writes ──

def plan_cuts(duration_ms: int, chunk_seconds: int, overlap_ms: int) -> list[tuple[int, int]]:
    """Planned [start, end) of each chunk: [0, S), [S - T, 2S), ... A last piece
    shorter than twice the overlap is merged into the previous chunk."""
    s, t = chunk_seconds * 1000, overlap_ms
    cuts: list[tuple[int, int]] = []
    start = 0
    while True:
        end = start + s if not cuts else (len(cuts) + 1) * s
        if end >= duration_ms:
            cuts.append((start, duration_ms))
            break
        cuts.append((start, end))
        start = end - t
    if len(cuts) > 1 and cuts[-1][1] - cuts[-1][0] - t < 2 * t:
        last = cuts.pop()
        cuts[-1] = (cuts[-1][0], last[1])
    return cuts


def _yaml_value(v) -> str:
    if isinstance(v, str):
        return '"' + v.replace("\\", "\\\\").replace('"', '\\"') + '"'
    if isinstance(v, bool):
        return "1" if v else "0"
    if isinstance(v, float):
        return f"{v:.4f}".rstrip("0").rstrip(".") if v != int(v) else f"{v:.1f}"
    if isinstance(v, (list, tuple)):
        return "[" + ", ".join(_yaml_value(x) for x in v) + "]"
    return str(v)


def chunk_yaml(clip: dict, chunk: dict, encoding: dict, fmap: list[tuple[int, int, int]], overlap_frames: int, sha256: str, pitch: dict | None = None) -> str:
    """A video_part_NNN.yaml as the app writes it (%YAML:1.0, simple maps and lists)."""
    lines = ["%YAML:1.0", "---", "version: 1", "clip:"]
    lines += [f"  {k}: {_yaml_value(v)}" for k, v in clip.items()]
    lines.append("chunk:")
    lines += [f"  {k}: {_yaml_value(v)}" for k, v in chunk.items()]
    lines.append("encoding:")
    lines += [f"  {k}: {_yaml_value(v)}" for k, v in encoding.items()]
    lines += ["frames:", f"  count: {len(fmap)}", f"  overlap_frames: {overlap_frames}", "  map:"]
    lines += [f"    - [{a}, {b}, {c}]" for a, b, c in fmap]
    if pitch:
        lines.append("pitch:")
        for k, v in pitch.items():
            if k == "frames":
                lines.append("  frames:")
                lines += [f"    - {_yaml_value(row)}" for row in v]
            else:
                lines.append(f"  {k}: {_yaml_value(v)}")
    lines.append(f'sha256: "{sha256}"')
    return "\n".join(lines) + "\n"


def index_yaml(clip_id: int, profile: str, chunk_seconds: int, overlap_ms: int, chunks: list[dict]) -> str:
    lines = ["%YAML:1.0", "---", "version: 1", f"clip_id: {clip_id}", f'profile: "{profile}"', f"chunk_seconds: {chunk_seconds}",
             f"overlap_ms: {overlap_ms}", f"count: {len(chunks)}", "complete: 1", "chunks:"]
    for c in chunks:
        lines.append("  - { " + ", ".join(f"{k}: {_yaml_value(v)}" for k, v in c.items()) + " }")
    return "\n".join(lines) + "\n"
