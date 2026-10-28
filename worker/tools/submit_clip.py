"""Uploads a chunk folder to the pp-coach-tracker API as the app does.

    PP_COACH_TRACKER_TOKEN=… python tools/submit_clip.py DIR [--api URL] [--title T]
        [--home H --away A --home-color red --away-color blue]

POST /clips (with the coach's consent), each chunk in 8 MB parts resuming at
the server's offset, closes it with its YAML and sha256, then completes the clip.
The token is a coach JWT (POST /auth/login) or a static API token.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from ppcoachtracker_worker.chunks import load_clip  # noqa: E402

CONSENT_VERSION = "2026-10-27"
PART = 8 * 1024 * 1024


def call(method: str, url: str, token: str, data: bytes | None = None, content_type: str | None = None) -> tuple[int, dict]:
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    if content_type:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def multipart(fields: dict[str, tuple[str, bytes] | str]) -> tuple[bytes, str]:
    b = uuid.uuid4().hex
    out = []
    for k, v in fields.items():
        if isinstance(v, tuple):
            out.append(f'--{b}\r\nContent-Disposition: form-data; name="{k}"; filename="{v[0]}"\r\nContent-Type: text/yaml\r\n\r\n'.encode() + v[1] + b"\r\n")
        else:
            out.append(f'--{b}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode())
    out.append(f"--{b}--\r\n".encode())
    return b"".join(out), f"multipart/form-data; boundary={b}"


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser()
    p.add_argument("dir", type=Path)
    p.add_argument("--api", default=os.environ.get("PP_COACH_TRACKER_API_URL", "http://127.0.0.1:3000"))
    p.add_argument("--token", default=os.environ.get("PP_COACH_TRACKER_TOKEN", ""))
    p.add_argument("--title", default="Clip de prueba")
    p.add_argument("--home", default="Local")
    p.add_argument("--away", default="Visitante")
    p.add_argument("--home-color", default="red")
    p.add_argument("--away-color", default="blue")
    a = p.parse_args(argv)
    if not a.token:
        p.error("set PP_COACH_TRACKER_TOKEN or --token")
    api = a.api.rstrip("/")
    clip = load_clip(a.dir)
    client_id = "submit-" + hashlib.sha256(str(a.dir.resolve()).encode()).hexdigest()[:16]
    body = {
        "client_clip_id": client_id, "title": a.title, "duration_ms": clip.duration_ms - clip.chunks[0].start_ms,
        "chunk_count": len(clip.chunks), "chunk_seconds": clip.chunk_seconds, "overlap_ms": clip.overlap_ms,
        "match": {"client_match_id": f"{client_id}-match", "home": a.home, "away": a.away, "home_color": a.home_color,
                  "away_color": a.away_color, "date": datetime.now().strftime("%Y-%m-%d"), "venue": ""},
        "consent": {"version": CONSENT_VERSION, "accepted_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "app_version": "submit_clip"},
    }
    status, reg = call("POST", f"{api}/clips", a.token, json.dumps(body).encode(), "application/json")
    if status != 200:
        print(f"register: {status} {reg}", file=sys.stderr)
        return 1
    cid = reg["clip_id"]
    received = {c["number"] for c in reg["received"]}
    partial = {c["number"]: c["received_bytes"] for c in reg["partial"]}
    for ch in clip.chunks:
        if ch.number in received:
            print(f"chunk {ch.number}: already there")
            continue
        data = ch.video.read_bytes()
        offset = partial.get(ch.number, 0)
        while offset < len(data):
            st, r = call("PUT", f"{api}/clips/{cid}/chunks/{ch.number}/video?offset={offset}", a.token, data[offset:offset + PART], "application/octet-stream")
            if st == 409 and "expected_offset" in r:
                offset = r["expected_offset"]
                continue
            if st != 200:
                print(f"chunk {ch.number} part at {offset}: {st} {r}", file=sys.stderr)
                return 1
            offset = r["received_bytes"]
            print(f"chunk {ch.number}: {offset}/{len(data)} bytes")
        yml = (a.dir / f"video_part_{ch.number:03d}.yaml").read_bytes()
        payload, ctype = multipart({"sha256": ch.sha256, "yaml": (f"video_part_{ch.number:03d}.yaml", yml)})
        st, r = call("PUT", f"{api}/clips/{cid}/chunks/{ch.number}", a.token, payload, ctype)
        if st != 200:
            print(f"close chunk {ch.number}: {st} {r}", file=sys.stderr)
            return 1
    payload, ctype = multipart({"index": ("chunks.yaml", (a.dir / "chunks.yaml").read_bytes())})
    st, r = call("POST", f"{api}/clips/{cid}/complete", a.token, payload, ctype)
    print(f"complete: {st} {r}")
    return 0 if st == 200 else 1


if __name__ == "__main__":
    sys.exit(main())
