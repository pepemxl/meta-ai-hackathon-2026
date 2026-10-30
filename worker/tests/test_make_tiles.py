import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import make_tiles  # noqa: E402

COLS = ["frame", "t_ms", "track", "label", "team", "x", "y", "w", "h", "conf", "interp", "mx", "my"]


def _run(tmp: Path, name: str, rows: list[list]) -> Path:
    d = tmp / name
    d.mkdir()
    (d / "tracks.json").write_text(json.dumps({"columns": COLS, "rows": rows}), encoding="utf-8")
    return d


def test_merge_joins_a_player_crossing_the_overlap_and_keeps_one_box(tmp_path):
    # Two tiles of 1000 px over a 1800 px source: they share x 800..1000.
    spec = {"source": {"width": 1800, "height": 500}, "tiles": [
        {"index": 1, "source": {"x": 0, "y": 0, "w": 1000, "h": 500}},
        {"index": 2, "source": {"x": 800, "y": 0, "w": 1000, "h": 500}},
    ]}
    (tmp_path / "tiles.json").write_text(json.dumps(spec), encoding="utf-8")
    left, right = [], []
    for f in range(30):
        sx = 700 + f * 10  # walks from tile 1 into the overlap and on into tile 2
        if sx + 10 <= 1000:
            left.append([f, f * 100, 7, "player", "A", (sx - 0) / 1000, 0.4, 0.02, 0.1, 0.9, 0, None, None])
        if sx >= 800:
            right.append([f, f * 100, 3, "player", "A", (sx - 800) / 1000, 0.4, 0.02, 0.1, 0.9, 0, None, None])
        # Someone else, only in tile 1, far from the overlap.
        left.append([f, f * 100, 9, "player", "B", 0.1, 0.5, 0.02, 0.1, 0.9, 0, None, None])
    out = tmp_path / "merged.json"
    code = make_tiles.main(["merge", str(tmp_path / "tiles.json"), str(_run(tmp_path, "r1", left)), str(_run(tmp_path, "r2", right)), "--out", str(out)])
    assert code == 0
    m = json.loads(out.read_text(encoding="utf-8"))
    assert m["stats"]["tracks_before"] == 3
    assert m["stats"]["tracks_after"] == 2
    assert m["joins"] == [{"tiles": [1, 2], "tracks": [7, 3], "frames": 20}]
    rows = [dict(zip(m["columns"], r)) for r in m["rows"]]
    # One box per player and frame, in the source's coordinates.
    walker = [r for r in rows if r["team"] == "A"]
    assert len(walker) == 30 and len({r["frame"] for r in walker}) == 30
    assert abs(walker[-1]["x"] - (700 + 29 * 10) / 1800) < 1e-9
    # Inside the overlap, the box of the tile whose edge is farther away.
    at = {r["frame"]: r["tile"] for r in walker}
    assert at[11] == 1 and at[25] == 2
