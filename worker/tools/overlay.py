"""Draws the boxes and teams of one frame, to check SAM's tracks by eye.

    python tools/overlay.py CLIP_DIR --frame N [--tracks RAW/tracks.json] [--out frame.png]

CLIP_DIR is the chunk folder; tracks.json is written next to the raw streams
by `analyze` (CLIP_DIR/raw/tracks.json) and by the job runner. N is the
global frame (overlaps counted once). Use it around a chunk boundary to check
the join of tracks between chunks.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from ppcoachtracker_worker.chunks import load_clip, read_frames  # noqa: E402

COLORS = {"A": (53, 57, 229), "B": (216, 91, 30), "": (200, 200, 200)}  # BGR


def main(argv: list[str] | None = None) -> int:
    import cv2

    p = argparse.ArgumentParser()
    p.add_argument("dir", type=Path)
    p.add_argument("--frame", type=int, required=True)
    p.add_argument("--tracks", type=Path)
    p.add_argument("--out", type=Path)
    a = p.parse_args(argv)
    clip = load_clip(a.dir)
    tracks_path = a.tracks or a.dir / "raw" / "tracks.json"
    data = json.loads(tracks_path.read_text(encoding="utf-8"))
    cols = data["columns"]
    rows = [dict(zip(cols, r)) for r in data["rows"] if r[cols.index("frame")] == a.frame]

    g = a.frame
    for c in clip.chunks:
        first = c.overlap_frames if c.number > 1 else 0
        n = c.frame_count - first
        if g < n:
            chunk, local = c, g + first
            break
        g -= n
    else:
        p.error(f"frame {a.frame} is past the end of the clip")
    img = dict(read_frames(chunk, {local}))[local]
    h, w = img.shape[:2]
    for r in rows:
        x1, y1 = int(r["x"] * w), int(r["y"] * h)
        x2, y2 = int((r["x"] + r["w"]) * w), int((r["y"] + r["h"]) * h)
        color = (255, 255, 255) if r["label"] == "ball" else COLORS.get(r["team"], COLORS[""])
        cv2.rectangle(img, (x1, y1), (x2, y2), color, 2)
        cv2.putText(img, f"{r['track_id']}{r['team']}", (x1, max(12, y1 - 4)), cv2.FONT_HERSHEY_SIMPLEX, 0.45, color, 1, cv2.LINE_AA)
    cv2.putText(img, f"chunk {chunk.number} frame {local} (global {a.frame})", (10, 24), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 255, 255), 2, cv2.LINE_AA)
    out = a.out or a.dir / f"overlay_{a.frame:05d}.png"
    cv2.imwrite(str(out), img)
    print(f"{out}: {len(rows)} objects")
    return 0


if __name__ == "__main__":
    sys.exit(main())
