"""Recomputes the frames that count, the ball and possession of a saved run,
without calling SAM again (from raw_tracks.json), and prints before/after.

    python tools/rescore.py RUN_DIR
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from ppcoachtracker_worker import metrics  # noqa: E402
from ppcoachtracker_worker.pipeline import post_sam  # noqa: E402
from ppcoachtracker_worker.pitch import PitchMask  # noqa: E402
from ppcoachtracker_worker.types import FrameInfo, TrackRow, TrackSet  # noqa: E402


def load(run: Path):
    raw = json.loads((run / "raw_tracks.json").read_text(encoding="utf-8"))
    tracks = json.loads((run / "tracks.json").read_text(encoding="utf-8"))
    frames = [FrameInfo(f, t, s) for f, t, s in raw["frames"]]
    rows = [TrackRow(*r) for r in raw["rows"]]
    masks: list = [None] * len(frames)
    for f, frac, poly in tracks.get("pitch_masks", []):
        masks[f] = PitchMask(frac, tuple(poly))
    grass = {(f, t): g for f, t, g in raw.get("ball_grass", [])}
    return TrackSet(rows=rows, frames=frames), masks, set(raw.get("cuts", [])), grass


def main(argv: list[str] | None = None) -> int:
    args = argv if argv is not None else sys.argv[1:]
    if len(args) != 1:
        print(__doc__)
        return 2
    run = Path(args[0])
    before = json.loads((run / "result.json").read_text(encoding="utf-8"))
    trackset, masks, cuts, grass = load(run)
    shots, ts, ball_report = post_sam(trackset, masks, cuts, grass)
    m = metrics.compute(TrackSet(rows=ts.rows, frames=shots.counted), breaks=shots.breaks)
    lines = [
        ("frames used", before["video"].get("frames_used"), len(shots.counted)),
        ("ball seen", before["ball"]["seen_ratio"], m["ball"]["seen_ratio"]),
        ("possession A", before["teams"]["A"]["possession"], m["teams"]["A"]["possession"]),
        ("possession changes", before["possession_changes"], m["possession_changes"]),
        ("ball trajectories kept", before["ball"].get("kept_trajectories"), ball_report["kept_trajectories"]),
    ]
    print("| | before | after |\n|---|---|---|")
    for name, a, b in lines:
        print(f"| {name} | {a} | {b} |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
