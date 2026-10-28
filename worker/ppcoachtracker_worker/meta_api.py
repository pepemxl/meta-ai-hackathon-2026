"""HTTP client for the Meta Model API (SAM 3.1 and Muse Spark), with urllib.

- Retries: 429, 5xx, timeouts and cut streams are retried 3 times with
  exponential backoff, respecting Retry-After. Anything else is a MetaError
  and the job fails: there is no local fallback.
- Cache: every raw response is stored under META_CACHE_DIR with a key that
  includes the base URL, so a response from the local SAM simulator never
  comes out of the cache in a run against real SAM. It is a cache, not a
  fallback: a miss calls Meta.
- The key never appears in errors or logs.
"""

from __future__ import annotations

import hashlib
import json
import socket
import time
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from .types import MetaError, RetryableError

MAX_RETRIES = 3
SIMULATOR_HEADER = "X-PP-Simulator"


@dataclass
class Usage:
    calls: int = 0
    retries: int = 0
    frames_sent: int = 0
    cached: int = 0

    def as_dict(self) -> dict:
        return {"calls": self.calls, "retries": self.retries, "frames_sent": self.frames_sent, "cached": self.cached}


@dataclass
class MetaResponse:
    text: str  # output text (concatenated deltas when streamed)
    raw: str  # the raw body as received
    simulator: bool = False
    cached: bool = False


def cache_key(base_url: str, *parts: str) -> str:
    """Key of a cached response: base URL + content hash + model + concept/prompt."""
    return hashlib.sha256("|".join([base_url.rstrip("/"), *parts]).encode("utf-8")).hexdigest()


class ResponseCache:
    def __init__(self, directory: Path | None):
        self.dir = directory

    def get(self, key: str) -> MetaResponse | None:
        if self.dir is None:
            return None
        f = self.dir / f"{key}.json"
        if not f.is_file():
            return None
        try:
            d = json.loads(f.read_text(encoding="utf-8"))
            return MetaResponse(text=d["text"], raw=d["raw"], simulator=bool(d.get("simulator")), cached=True)
        except (ValueError, KeyError):
            return None

    def put(self, key: str, resp: MetaResponse, meta: dict) -> None:
        if self.dir is None:
            return
        self.dir.mkdir(parents=True, exist_ok=True)
        tmp = self.dir / f"{key}.tmp"
        tmp.write_text(json.dumps({"text": resp.text, "raw": resp.raw, "simulator": resp.simulator, **meta}), encoding="utf-8")
        tmp.replace(self.dir / f"{key}.json")


def parse_sse(body: str) -> tuple[str, bool]:
    """Output text of a Responses API event stream and whether it completed."""
    parts: list[str] = []
    completed = False
    for line in body.splitlines():
        if not line.startswith("data:"):
            continue
        payload = line[5:].strip()
        if not payload or payload == "[DONE]":
            continue
        try:
            event = json.loads(payload)
        except ValueError:
            continue
        kind = event.get("type", "")
        if kind == "response.output_text.delta":
            parts.append(str(event.get("delta", "")))
        elif kind == "response.completed":
            completed = True
            if not parts:
                parts.append(output_text(event.get("response") or {}))
        elif kind in ("response.failed", "error", "response.error"):
            err = event.get("error") or (event.get("response") or {}).get("error") or {}
            raise MetaError("", f"stream error: {str(err.get('message') or err)[:200]}")
    return "".join(parts), completed


def output_text(response: dict) -> str:
    """Text of a non-streamed Responses API body."""
    if isinstance(response.get("output_text"), str):
        return response["output_text"]
    texts = []
    for item in response.get("output") or []:
        for c in item.get("content") or []:
            if c.get("type") in ("output_text", "text") and isinstance(c.get("text"), str):
                texts.append(c["text"])
    return "".join(texts)


class MetaClient:
    """One base URL (Meta, or the SAM simulator in development)."""

    def __init__(
        self,
        base_url: str,
        api_key: str,
        service: str,
        usage: Usage,
        cache: ResponseCache,
        sleep: Callable[[float], None] = time.sleep,
        timeout: float = 900,
    ):
        self.base_url = base_url.rstrip("/")
        self._key = api_key
        self.service = service
        self.usage = usage
        self.cache = cache
        self.sleep = sleep
        self.timeout = timeout
        self._files: dict[str, str] = {}

    # ── errors ───────────────────────────────────────────────────────────────

    def _scrub(self, text: str) -> str:
        return text.replace(self._key, "***") if self._key else text

    def _fail(self, message: str) -> MetaError:
        return MetaError(self.service, self._scrub(message))

    def _with_retries(self, what: str, fn):
        delay = 1.0
        for attempt in range(MAX_RETRIES + 1):
            try:
                return fn()
            except RetryableError as e:
                if attempt == MAX_RETRIES:
                    raise self._fail(f"{what}: {e} (after {MAX_RETRIES + 1} attempts)") from None
                self.usage.retries += 1
                wait = e.retry_after if e.retry_after is not None else delay
                self.sleep(min(max(wait, 0.0), 120.0))
                delay *= 2
        raise AssertionError("unreachable")

    def _open(self, req: urllib.request.Request):
        req.add_header("Authorization", f"Bearer {self._key}")
        self.usage.calls += 1
        try:
            return urllib.request.urlopen(req, timeout=self.timeout)
        except urllib.error.HTTPError as e:
            snippet = ""
            try:
                snippet = e.read(300).decode("utf-8", "replace")
            except Exception:  # noqa: BLE001 - the body is only for the message
                pass
            if e.code == 429 or e.code >= 500:
                retry_after = None
                ra = e.headers.get("Retry-After") if e.headers else None
                if ra:
                    try:
                        retry_after = float(ra)
                    except ValueError:
                        retry_after = None
                raise RetryableError(f"HTTP {e.code}", retry_after) from None
            raise self._fail(f"HTTP {e.code}: {self._scrub(snippet)[:200]}") from None
        except (urllib.error.URLError, socket.timeout, TimeoutError, ConnectionError) as e:
            raise RetryableError(f"network: {getattr(e, 'reason', e)}") from None

    # ── calls ────────────────────────────────────────────────────────────────

    def upload(self, path: Path, sha256: str) -> tuple[str, bool]:
        """POST /files (multipart, purpose=user_data) → (file_id, simulator). Once per content."""
        if sha256 in self._files:
            return self._files[sha256], False
        boundary = uuid.uuid4().hex
        data = path.read_bytes()
        body = b"".join([
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\nuser_data\r\n".encode(),
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{path.name}\"\r\nContent-Type: video/mp4\r\n\r\n".encode(),
            data,
            f"\r\n--{boundary}--\r\n".encode(),
        ])

        def call():
            req = urllib.request.Request(f"{self.base_url}/files", data=body, method="POST")
            req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
            with self._open(req) as resp:
                sim = resp.headers.get(SIMULATOR_HEADER) == "1"
                try:
                    payload = json.loads(resp.read().decode("utf-8"))
                except (ValueError, OSError) as e:
                    raise RetryableError(f"unreadable upload response: {e}") from None
            file_id = payload.get("id")
            if not isinstance(file_id, str):
                raise self._fail("upload response without id")
            return file_id, sim

        file_id, sim = self._with_retries("upload", call)
        self._files[sha256] = file_id
        return file_id, sim

    def respond(self, body: dict, key: str, cache_meta: dict, stream: bool = True) -> MetaResponse:
        """POST /responses; cached under [key]."""
        hit = self.cache.get(key)
        if hit is not None:
            self.usage.cached += 1
            return hit
        data = json.dumps({**body, "stream": stream}).encode("utf-8")

        def call():
            req = urllib.request.Request(f"{self.base_url}/responses", data=data, method="POST")
            req.add_header("Content-Type", "application/json")
            if stream:
                req.add_header("Accept", "text/event-stream")
            with self._open(req) as resp:
                sim = resp.headers.get(SIMULATOR_HEADER) == "1"
                try:
                    raw = resp.read().decode("utf-8", "replace")
                except (OSError, ValueError) as e:
                    raise RetryableError(f"stream cut: {e}") from None
            if stream:
                try:
                    text, completed = parse_sse(raw)
                except MetaError as e:
                    raise self._fail(str(e)) from None
                if not completed:
                    raise RetryableError("stream ended before response.completed")
            else:
                try:
                    text = output_text(json.loads(raw))
                except ValueError:
                    raise RetryableError("unreadable response body") from None
            return MetaResponse(text=text, raw=raw, simulator=sim)

        resp = self._with_retries("responses", call)
        self.cache.put(key, resp, cache_meta)
        return resp
