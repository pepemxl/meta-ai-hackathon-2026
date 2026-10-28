"""Shared types: track rows and the errors that decide how a job ends."""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field, replace


class InputError(Exception):
    """The clip is invalid or too big: the job fails before spending credits."""


class MetaError(Exception):
    """A definitive Meta Model API error (401/403, unknown model, unreadable output).

    There is no fallback: the job fails with "<service>: <message>".
    """

    def __init__(self, service: str, message: str):
        super().__init__(message)
        self.service = service


class RetryableError(Exception):
    """429, 5xx, timeout or a cut stream: the same call is retried."""

    def __init__(self, message: str, retry_after: float | None = None):
        super().__init__(message)
        self.retry_after = retry_after


class ConfigError(Exception):
    """Missing or invalid configuration: the worker does not start."""


@dataclass(frozen=True)
class TrackRow:
    """One object in one frame. Coordinates are normalized 0..1 (screen)."""

    frame: int  # global frame index of the clip (overlaps counted once)
    t_ms: int  # time in the original video
    source_frame: int  # frame of the original video
    track_id: int
    label: str  # "player" | "ball"
    x: float
    y: float
    w: float
    h: float
    conf: float = 1.0
    team: str = ""  # "A" (home) | "B" (away) | ""
    mx: float | None = None  # meters on the pitch (None without a homography)
    my: float | None = None

    @property
    def foot(self) -> tuple[float, float]:
        return (self.x + self.w / 2, self.y + self.h)

    @property
    def center(self) -> tuple[float, float]:
        return (self.x + self.w / 2, self.y + self.h / 2)

    def with_(self, **changes) -> "TrackRow":
        return replace(self, **changes)


@dataclass
class FrameInfo:
    frame: int
    t_ms: int
    source_frame: int


@dataclass
class TrackSet:
    rows: list[TrackRow] = field(default_factory=list)
    frames: list[FrameInfo] = field(default_factory=list)

    def by_frame(self) -> dict[int, list[TrackRow]]:
        out: dict[int, list[TrackRow]] = defaultdict(list)
        for r in self.rows:
            out[r.frame].append(r)
        return out

    def by_track(self) -> dict[int, list[TrackRow]]:
        out: dict[int, list[TrackRow]] = defaultdict(list)
        for r in self.rows:
            out[r.track_id].append(r)
        return out


def iou(a: tuple[float, float, float, float], b: tuple[float, float, float, float]) -> float:
    """IoU of two (x, y, w, h) boxes."""
    ax2, ay2 = a[0] + a[2], a[1] + a[3]
    bx2, by2 = b[0] + b[2], b[1] + b[3]
    iw = max(0.0, min(ax2, bx2) - max(a[0], b[0]))
    ih = max(0.0, min(ay2, by2) - max(a[1], b[1]))
    inter = iw * ih
    union = a[2] * a[3] + b[2] * b[3] - inter
    return inter / union if union > 0 else 0.0
