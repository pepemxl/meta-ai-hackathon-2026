"""Markdown table comparing saved runs (worker/.local/runs/<run>).

    python tools/compare_runs.py RUN_DIR [RUN_DIR ...]

Players per frame, box size, ball seen, possession, tracks, frames used,
processing time and the SAM cost (US$0.20 per 1,000 frames and concept).
Measure every change before and after on the same video, and look at it in
the viewer: never accept a number that improves without seeing what changed.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

SAM_USD_PER_1000 = 0.20


def summary(run: Path) -> dict:
    r = json.loads((run / "result.json").read_text(encoding="utf-8"))
    t = json.loads((run / "tracks.json").read_text(encoding="utf-8"))
    cols = t["columns"]
    rows = [dict(zip(cols, x)) for x in t["rows"]]
    players = [x for x in rows if x["label"] == "player"]
    frames = r["video"].get("frames_used", r["frames"]) or 1
    v = r["video"]
    minutes = max(v["duration_ms"], 1) / 60000
    cost = r["backends"]["usage"].get("frames_sent", 0) * SAM_USD_PER_1000 / 1000
    return {
        "run": run.name,
        "segmenter": r["backends"]["segmenter"],
        "players/frame": round(len(players) / frames, 2),
        "box height": round(sum(x["h"] for x in players) / max(1, len(players)), 3),
        "ball seen": f"{round(100 * r['ball']['seen_ratio'])} %",
        "possession A/B": f"{r['teams']['A']['possession']}/{r['teams']['B']['possession']}",
        "tracks": len({x["track"] for x in players}),
        "frames used": f"{frames}/{v['frames']}",
        "cuts": v.get("cuts", "–"),
        "seconds": r.get("processing_seconds"),
        "US$ SAM": round(cost, 2),
        "US$/min": round(cost / minutes, 2),
    }


def table(rows: list[dict]) -> str:
    keys = list(rows[0])
    out = ["| " + " | ".join(keys) + " |", "|" + "---|" * len(keys)]
    out += ["| " + " | ".join(str(r[k]) for k in keys) + " |" for r in rows]
    return "\n".join(out)


def main(argv: list[str] | None = None) -> int:
    runs = [Path(a) for a in (argv if argv is not None else sys.argv[1:])]
    if not runs:
        print(__doc__)
        return 2
    print(table([summary(r) for r in runs]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
