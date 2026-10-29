"""Fake servers for the tests: no test ever calls Meta or the simulator.

- FakeMeta: /v1/files and /v1/responses with SAM streams in the documented
  format (built from a synthetic scene) and Muse Spark answers.
- FakePpApi: the /internal routes of the pp-coach-tracker API.

THE SAM STREAMS ARE SYNTHETIC, in the format documented in stage 08: they
must be replaced by streams captured from real SAM 3.1 once there is a key.
"""

from __future__ import annotations

import hashlib
import json
import math
import re
import threading
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import numpy as np

# ── synthetic scene ──────────────────────────────────────────────────────────

BGR = {"red": (53, 57, 229), "blue": (216, 91, 30), "black": (33, 33, 33), "white": (245, 245, 245)}


@dataclass
class Obj:
    name: str
    label: str  # player | ball | referee
    color: str

    def box(self, t_ms: int) -> tuple[float, float, float, float]:
        s = t_ms / 1000.0
        if self.label == "ball":
            # Goes from red player 1 to blue player 1 and back, every 6 s.
            phase = (s % 6) / 6
            k = phase * 2 if phase < 0.5 else 2 - phase * 2
            x = 0.20 + k * 0.40
            return (x + 0.01, 0.62, 0.02, 0.035)
        i = int(self.name[-1])
        if self.label == "referee":
            return (0.45 + 0.05 * math.sin(s), 0.15, 0.05, 0.16)
        base_x = 0.15 + 0.12 * i if self.color == "red" else 0.55 + 0.12 * (i - 1)
        y = 0.35 + 0.08 * i
        return (base_x + 0.02 * math.sin(s + i), y, 0.06, 0.18)


SCENE = [Obj(f"red{i}", "player", "red") for i in (1, 2, 3)] + [Obj(f"blue{i}", "player", "blue") for i in (1, 2, 3)] + [
    Obj("ref1", "referee", "black"),
    Obj("ball1", "ball", "white"),
]


def render(t_ms: int, width: int, height: int) -> np.ndarray:
    img = np.zeros((height, width, 3), dtype=np.uint8)
    img[:] = (40, 140, 50)  # grass
    for o in SCENE:
        x, y, w, h = o.box(t_ms)
        x1, y1, x2, y2 = int(x * width), int(y * height), int((x + w) * width), int((y + h) * height)
        img[y1:y2, x1:x2] = BGR[o.color]
    return img


def make_clip(directory: Path, total_ms: int, chunk_seconds: int, overlap_ms: int, width: int = 320, height: int = 180, begin_ms: int = 0) -> Path:
    """A clip in the app's format, with the app's cuts and overlap alignment."""
    import cv2

    from ppcoachtracker_worker.chunks import chunk_yaml, index_yaml, plan_cuts

    directory.mkdir(parents=True, exist_ok=True)
    cuts = plan_cuts(total_ms, chunk_seconds, overlap_ms)
    next_start = None
    rows = []
    for n, (cut_start, cut_end) in enumerate(cuts, start=1):
        start = next_start if next_start is not None else cut_start
        times = list(range(start, cut_end, 100))
        name = f"video_part_{n:03d}.mp4"
        w = cv2.VideoWriter(str(directory / name), cv2.VideoWriter_fourcc(*"mp4v"), 10, (width, height))
        fmap = []
        for k, t in enumerate(times):
            w.write(render(begin_ms + t, width, height))
            fmap.append((k, round((begin_ms + t) * 30 / 1000), begin_ms + t))
        w.release()
        overlap_frames = sum(1 for _, _, t in fmap if t - begin_ms < cuts[n - 2][1]) if n > 1 else 0
        if n < len(cuts):
            next_start = next((t for t in times if t >= cuts[n][0]), cuts[n][0])
        sha = hashlib.sha256((directory / name).read_bytes()).hexdigest()
        (directory / f"video_part_{n:03d}.yaml").write_text(chunk_yaml(
            clip={"id": 7, "match_id": 1, "source_file": "synthetic.mp4", "source_fps": 30.0, "source_frames": total_ms * 30 // 1000, "duration_ms": total_ms},
            chunk={"number": n, "count": len(cuts), "file": name, "seconds": chunk_seconds, "start_ms": begin_ms + start, "end_ms": begin_ms + cut_end,
                   "overlap_ms": (cuts[n - 2][1] - start) if n > 1 else 0},
            encoding={"fps": 10, "width": width, "height": height, "codec": "mp4v", "bitrate": 0, "audio": 0},
            fmap=fmap, overlap_frames=overlap_frames, sha256=sha,
        ), encoding="utf-8")
        rows.append({"number": n, "file": name, "start_ms": begin_ms + start, "end_ms": begin_ms + cut_end, "frames": len(fmap)})
    (directory / "chunks.yaml").write_text(index_yaml(7, "test", chunk_seconds, overlap_ms, rows), encoding="utf-8")
    return directory


def sam_stream_text(clip_dir: Path, chunk_number: int, concept: str, pixels: bool = False, ordinal_offset: int = 0) -> str:
    """The SAM output text for one chunk and concept of a synthetic clip."""
    from ppcoachtracker_worker.chunks import load_clip

    clip = load_clip(clip_dir)
    chunk = clip.chunks[chunk_number - 1]
    m = re.match(r"player in (.+) jersey", concept)
    if m:
        objs = [o for o in SCENE if o.label == "player" and o.color == m.group(1)]
    elif concept == "soccer player":
        objs = [o for o in SCENE if o.label in ("player", "referee")]
    elif concept == "soccer ball":
        objs = [o for o in SCENE if o.label == "ball"]
    else:
        objs = []
    parts = []
    for k, _, t in chunk.map:
        parts.append(f"<{k}f>")
        for j, o in enumerate(objs):
            x, y, w, h = o.box(t)
            if pixels:
                x1, y1, x2, y2 = x * chunk.width, y * chunk.height, (x + w) * chunk.width, (y + h) * chunk.height
                parts.append(f" {j + 1 + ordinal_offset} <|box;x1={x1:.1f};y1={y1:.1f};x2={x2:.1f};y2={y2:.1f};c=0.91|>")
            else:
                parts.append(f" {j + 1 + ordinal_offset} <|box;x1={x:.4f};y1={y:.4f};x2={x + w:.4f};y2={y + h:.4f};c=0.93|>")
    return "".join(parts)


def sse(text: str, complete: bool = True, piece: int = 400) -> str:
    events = [{"type": "response.created", "response": {"id": "resp_1"}}]
    events += [{"type": "response.output_text.delta", "delta": text[i:i + piece]} for i in range(0, len(text), piece)]
    if complete:
        events.append({"type": "response.completed", "response": {"id": "resp_1", "status": "completed"}})
    body = "".join(f"data: {json.dumps(e)}\n\n" for e in events)
    return body + ("data: [DONE]\n\n" if complete else "")


def valid_insights(end_ms: int = 5000) -> dict:
    return {
        "summary": "El equipo A tuvo más el balón en la imagen.",
        "insights": [{
            "title": "Posesión del local",
            "description": "El equipo A conservó el balón más tiempo.",
            "t_start_ms": 0,
            "t_end_ms": min(end_ms, 3000),
            "severity": "info",
            "recommendation": "Revisar las salidas del equipo B.",
        }],
    }


# ── fake Meta Model API ──────────────────────────────────────────────────────

@dataclass
class FakeMeta:
    """SAM streams per (chunk sha256, concept), Muse answers in order, planned failures."""

    streams: dict[tuple[str, str], str] = field(default_factory=dict)
    # Answers for any file, by concept (a chunk re-encoded by the phone has another sha256).
    by_concept: dict[str, str] = field(default_factory=dict)
    # Answer to any point prompt (a text that starts with a "<Nf>" frame mark).
    objects: str = ""
    muse: list[str] = field(default_factory=list)
    failures: list[tuple[int, dict]] = field(default_factory=list)  # (status, headers) before normal answers
    simulator: bool = False
    cut_next: int = 0  # how many SAM streams to cut before completing
    calls: list[tuple[str, dict]] = field(default_factory=list)
    files: dict[str, str] = field(default_factory=dict)  # file id → sha256

    def start(self):
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _send(self, status: int, body: bytes, ctype: str = "application/json", headers: dict | None = None):
                self.send_response(status)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(body)))
                if fake.simulator:
                    self.send_header("X-PP-Simulator", "1")
                for k, v in (headers or {}).items():
                    self.send_header(k, v)
                self.end_headers()
                self.wfile.write(body)

            def do_POST(self):
                length = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(length)
                if not (self.headers.get("Authorization") or "").startswith("Bearer "):
                    return self._send(401, b'{"error":"missing key"}')
                if fake.failures:
                    status, headers = fake.failures.pop(0)
                    fake.calls.append((self.path, {"failed": status}))
                    return self._send(status, b'{"error":"planned failure"}', headers=headers)
                if self.path.endswith("/files"):
                    m = re.search(rb'filename="[^"]*"\r\nContent-Type: [^\r]*\r\n\r\n', raw)
                    end = raw.rfind(b"\r\n--")
                    content = raw[m.end():end]
                    sha = hashlib.sha256(content).hexdigest()
                    fid = f"file-{sha[:16]}"
                    fake.files[fid] = sha
                    fake.calls.append((self.path, {"file": fid}))
                    return self._send(200, json.dumps({"id": fid, "object": "file", "bytes": len(content), "purpose": "user_data"}).encode())
                body = json.loads(raw)
                fake.calls.append((self.path, body))
                if body["model"].startswith("muse"):
                    text = fake.muse.pop(0) if fake.muse else json.dumps(valid_insights())
                    out = {"id": "resp_m", "output": [{"type": "message", "content": [{"type": "output_text", "text": text}]}]}
                    return self._send(200, json.dumps(out).encode())
                content = body["input"][0]["content"]
                concept = next(c["text"] for c in content if c["type"] == "input_text")
                fid = next(c["file_id"] for c in content if c["type"] == "input_video")
                default = fake.objects if re.match(r"<\d+f>", concept) else fake.by_concept.get(concept, "")
                text = fake.streams.get((fake.files.get(fid, ""), concept), default)
                cut = fake.cut_next > 0
                if cut:
                    fake.cut_next -= 1
                return self._send(200, sse(text, complete=not cut).encode(), "text/event-stream")

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}/v1"
        return self

    def stop(self):
        self.server.shutdown()

    def sam_calls(self) -> list[dict]:
        return [b for p, b in self.calls if p.endswith("/responses") and isinstance(b, dict) and str(b.get("model", "")).startswith("sam")]

    def register_clip(self, clip_dir: Path, concepts: list[str], ordinal_offsets: dict[int, int] | None = None, pixels: bool = False):
        from ppcoachtracker_worker.chunks import load_clip

        clip = load_clip(clip_dir)
        for c in clip.chunks:
            for concept in concepts:
                off = (ordinal_offsets or {}).get(c.number, 0)
                self.streams[(c.sha256, concept)] = sam_stream_text(clip_dir, c.number, concept, pixels=pixels, ordinal_offset=off)


# ── fake pp-coach-tracker API ────────────────────────────────────────────────

@dataclass
class FakePpApi:
    clip_dir: Path
    job: dict
    results: list[dict] = field(default_factory=list)
    served: bool = False

    def start(self):
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _send(self, status, body=b"", ctype="application/json"):
                self.send_response(status)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                if self.headers.get("Authorization") != "Bearer worker-secret":
                    return self._send(401, b'{"error":"invalid or missing token"}')
                if self.path == "/internal/jobs/next":
                    if fake.served:
                        return self._send(204)
                    fake.served = True
                    return self._send(200, json.dumps(fake.job).encode())
                m = re.match(r"/internal/clips/(\d+)/files/(.+)$", self.path)
                if m and (fake.clip_dir / m.group(2)).is_file():
                    return self._send(200, (fake.clip_dir / m.group(2)).read_bytes(), "application/octet-stream")
                return self._send(404, b'{"error":"file not found"}')

            def do_POST(self):
                raw = self.rfile.read(int(self.headers.get("Content-Length") or 0))
                fields = {}
                for m in re.finditer(rb'name="([^"]+)"(?:; filename="[^"]*")?(?:\r\nContent-Type: [^\r]*)?\r\n\r\n(.*?)\r\n--', raw, re.S):
                    fields[m.group(1).decode()] = m.group(2).decode("utf-8")
                if "analysis" in fields:
                    fields["analysis"] = json.loads(fields["analysis"])
                fake.results.append(fields)
                return self._send(200, b'{"status":"ok"}')

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"
        return self

    def stop(self):
        self.server.shutdown()
