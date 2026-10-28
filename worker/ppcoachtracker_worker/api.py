"""Client of the pp-coach-tracker API's /internal routes (worker side)."""

from __future__ import annotations

import json
import shutil
import urllib.error
import urllib.request
import uuid
from pathlib import Path


class ApiError(Exception):
    pass


class PpApi:
    def __init__(self, base_url: str, secret: str, timeout: float = 120):
        self.base_url = base_url.rstrip("/")
        self._secret = secret
        self.timeout = timeout

    def _req(self, path: str, data: bytes | None = None, content_type: str | None = None, method: str = "GET"):
        req = urllib.request.Request(f"{self.base_url}{path}", data=data, method=method)
        req.add_header("Authorization", f"Bearer {self._secret}")
        if content_type:
            req.add_header("Content-Type", content_type)
        try:
            return urllib.request.urlopen(req, timeout=self.timeout)
        except urllib.error.HTTPError as e:
            body = e.read(300).decode("utf-8", "replace")
            raise ApiError(f"{method} {path}: HTTP {e.code} {body}") from None
        except urllib.error.URLError as e:
            raise ApiError(f"{method} {path}: {e.reason}") from None

    def next_job(self) -> dict | None:
        with self._req("/internal/jobs/next") as resp:
            if resp.status == 204:
                return None
            return json.loads(resp.read().decode("utf-8"))

    def download(self, clip_id: int, name: str, dest: Path) -> None:
        dest.parent.mkdir(parents=True, exist_ok=True)
        tmp = dest.with_suffix(dest.suffix + ".tmp")
        with self._req(f"/internal/clips/{clip_id}/files/{name}") as resp, tmp.open("wb") as f:
            shutil.copyfileobj(resp, f, 1 << 20)
        tmp.replace(dest)

    def post_result(self, job_id: int, status: str, error: str | None = None, analysis: dict | None = None) -> dict:
        """Multipart: status, error and the analysis as the file "analysis" (too big for JSON's 64 KB)."""
        boundary = uuid.uuid4().hex
        parts = [f"--{boundary}\r\nContent-Disposition: form-data; name=\"status\"\r\n\r\n{status}\r\n".encode()]
        if error:
            parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"error\"\r\n\r\n".encode() + error[:500].encode("utf-8") + b"\r\n")
        if analysis is not None:
            parts.append(
                f"--{boundary}\r\nContent-Disposition: form-data; name=\"analysis\"; filename=\"analysis.json\"\r\nContent-Type: application/json\r\n\r\n".encode()
                + json.dumps(analysis, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
                + b"\r\n"
            )
        parts.append(f"--{boundary}--\r\n".encode())
        with self._req(f"/internal/jobs/{job_id}/result", b"".join(parts), f"multipart/form-data; boundary={boundary}", "POST") as resp:
            return json.loads(resp.read().decode("utf-8") or "{}")
