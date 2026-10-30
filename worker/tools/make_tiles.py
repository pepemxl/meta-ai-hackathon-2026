"""Cuts a wide video (a panorama, or 4K/8K from a phone) into overlapping tiles
SAM 3.1 can see at their real size, and joins the tracks of the tiles again.

SAM resizes every frame so its long side is 1024 px (and pads it square)
before the encoder: a 3840-wide panorama shrinks to a quarter and its players
to a few pixels. Tiles of 1024 px across the pitch, side by side with some
overlap, keep the players at the size they were recorded.

    python tools/make_tiles.py plan VIDEO OUT/tiles.json [--tiles 3] [--size 1024] [--overlap 160] [--height pitch|full]
    python tools/make_tiles.py encode OUT/tiles.json [--seconds N]
    python tools/make_tiles.py merge OUT/tiles.json RUN_1 RUN_2 RUN_3 --out merged.json

plan: finds the pitch in frames spread over the video (pitch.py, the same
method as the app), and places the tiles over it: as wide as the pitch at its
real size if they reach, else the video is scaled down just enough that the
tiles cover it. Writes tiles.json with each tile's crop in the source.

encode: writes each tile as its own video with ffmpeg (H.264, the source fps,
no audio), next to tiles.json. It only needs ffmpeg: in Windows it runs on the
host, while plan and merge run in the worker image (OpenCV).

merge: takes the saved run of each tile (analyze --debug-dir: tracks.json),
moves every box to the source's coordinates, and joins the tracks of
neighbouring tiles in their overlap: the same player when the boxes overlap
(IoU > 0.5) in at least half of the frames both tracks are inside the
overlap, and in 5 or more (as between chunks, sam.py). Inside the overlap one
box is kept per player, the one farther from its tile's edge.
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

MIN_IOU = 0.5
MIN_SHARED = 5


def _even(v: float) -> int:
    return max(2, int(round(v / 2)) * 2)


def plan(a) -> int:
    import cv2
    import numpy as np

    from ppcoachtracker_worker.pitch import measure

    cap = cv2.VideoCapture(str(a.video))
    if not cap.isOpened():
        print(f"cannot open {a.video}", file=sys.stderr)
        return 2
    W, H = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)), int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    boxes = []
    for k in range(a.samples):
        cap.set(cv2.CAP_PROP_POS_FRAMES, int((k + 0.5) * frames / a.samples))
        ok, img = cap.read()
        if not ok:
            continue
        m = measure(img)
        if m.has_pitch:
            xs, ys = m.polygon[0::2], m.polygon[1::2]
            boxes.append((min(xs), min(ys), max(xs), max(ys)))
    cap.release()
    if len(boxes) < a.samples // 3:
        print(f"pitch found in only {len(boxes)} of {a.samples} frames: is it a wide shot?", file=sys.stderr)
        return 2
    # A camera that pans or a frame with grass outside: the 10/90 percentiles, plus a margin.
    b = np.array(boxes)
    x0 = max(0.0, np.percentile(b[:, 0], 10) - a.margin) * W
    y0 = max(0.0, np.percentile(b[:, 1], 10) - a.margin) * H
    x1 = min(1.0, np.percentile(b[:, 2], 90) + a.margin) * W
    y1 = min(1.0, np.percentile(b[:, 3], 90) + a.margin) * H
    if a.height == "full":
        y0, y1 = 0.0, float(H)
    pw, ph = x1 - x0, y1 - y0
    # Scale 1 when the tiles reach across the pitch; else just enough to cover it.
    reach = a.tiles * a.size - (a.tiles - 1) * a.overlap
    scale = min(1.0, reach / pw)
    if pw * scale < a.size:  # a narrow pitch: one tile already covers it, centered
        pw = min(float(W), a.size / scale)
        x0 = max(0.0, min(W - pw, (x0 + x1 - pw) / 2))
    th = min(a.size, _even(ph * scale))
    span = pw * scale
    step = (span - a.size) / (a.tiles - 1) if a.tiles > 1 else 0
    tiles = []
    for k in range(a.tiles):
        off = round(k * step)
        src_x = x0 + off / scale
        tiles.append({
            "index": k + 1,
            "file": f"{a.name}_t{k + 1}.mp4",
            # Where it sits in the source, in source pixels.
            "source": {"x": round(src_x, 1), "y": round(y0, 1), "w": round(a.size / scale, 1), "h": round(th / scale, 1)},
            "width": a.size,
            "height": th,
        })
    overlaps = [round(a.size - step) for _ in range(a.tiles - 1)]
    out = {
        "version": 1,
        "video": str(a.video.resolve()) if a.absolute else a.video.name,
        "source": {"width": W, "height": H, "fps": round(fps, 3), "frames": frames},
        "pitch": {"x": round(x0, 1), "y": round(y0, 1), "w": round(pw, 1), "h": round(ph, 1), "frames_with_pitch": len(boxes), "frames_sampled": a.samples},
        "scale": round(scale, 4),
        "size": a.size,
        "overlap_px": overlaps,
        "tiles": tiles,
    }
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(json.dumps(out, indent=2) + "\n", encoding="utf-8")
    print(f"pitch {pw:.0f}x{ph:.0f} px at ({x0:.0f},{y0:.0f}) of {W}x{H}; scale {scale:.3f}; {a.tiles} tiles of {a.size}x{th}, overlap {overlaps} px")
    for t in tiles:
        s = t["source"]
        print(f"  {t['file']}: source x {s['x']:.0f}..{s['x'] + s['w']:.0f}")
    return 0


def encode(a) -> int:
    if not shutil.which("ffmpeg"):
        print("ffmpeg is not on the PATH", file=sys.stderr)
        return 2
    spec = json.loads(a.tiles_json.read_text(encoding="utf-8"))
    video = a.video or Path(spec["video"])
    if not video.is_absolute() and not a.video:
        video = a.tiles_json.parent / video
    s = spec["scale"]
    W, H = spec["source"]["width"], spec["source"]["height"]
    tiles = spec["tiles"]
    # One decode: the pitch's rows, scaled, then each tile cut from it.
    th = tiles[0]["height"]
    y = max(0, round(tiles[0]["source"]["y"]))
    rows = min(H - y, round(tiles[0]["source"]["h"]))
    sw = _even(W * s)
    graph = [f"[0:v]crop={W}:{rows}:0:{y},scale={sw}:{th},split={len(tiles)}" + "".join(f"[s{k}]" for k in range(len(tiles)))]
    for k, t in enumerate(tiles):
        x = min(sw - t["width"], max(0, round(t["source"]["x"] * s)))
        graph.append(f"[s{k}]crop={t['width']}:{t['height']}:{x}:0[t{k}]")
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y"]
    if a.start:
        cmd += ["-ss", str(a.start)]
    cmd += ["-i", str(video)]
    if a.seconds:
        cmd += ["-t", str(a.seconds)]
    cmd += ["-filter_complex", ";".join(graph)]
    for k, t in enumerate(tiles):
        cmd += ["-map", f"[t{k}]", "-c:v", "libx264", "-preset", "veryfast", "-crf", str(a.crf), "-pix_fmt", "yuv420p", "-an",
                "-movflags", "+faststart", str(a.tiles_json.parent / t["file"])]
    subprocess.run(cmd, check=True)
    for t in tiles:
        print(a.tiles_json.parent / t["file"])
    return 0


def _boxes(run: Path, tile: dict, src: dict) -> list[dict]:
    """The boxes of one tile's run, in the source's normalized coordinates."""
    t = json.loads((run / "tracks.json").read_text(encoding="utf-8"))
    cols = t["columns"]
    s = tile["source"]
    out = []
    for r in t["rows"]:
        b = dict(zip(cols, r))
        b["x"] = (s["x"] + b["x"] * s["w"]) / src["width"]
        b["y"] = (s["y"] + b["y"] * s["h"]) / src["height"]
        b["w"] = b["w"] * s["w"] / src["width"]
        b["h"] = b["h"] * s["h"] / src["height"]
        b["tile"] = tile["index"]
        out.append(b)
    return out


def _iou(a: dict, b: dict) -> float:
    iw = max(0.0, min(a["x"] + a["w"], b["x"] + b["w"]) - max(a["x"], b["x"]))
    ih = max(0.0, min(a["y"] + a["h"], b["y"] + b["h"]) - max(a["y"], b["y"]))
    inter = iw * ih
    union = a["w"] * a["h"] + b["w"] * b["h"] - inter
    return inter / union if union > 0 else 0.0


def merge(a) -> int:
    spec = json.loads(a.tiles_json.read_text(encoding="utf-8"))
    src = spec["source"]
    tiles = spec["tiles"]
    if len(a.runs) != len(tiles):
        print(f"{len(tiles)} tiles but {len(a.runs)} runs", file=sys.stderr)
        return 2
    rows = []
    for tile, run in zip(tiles, a.runs):
        rows += _boxes(run, tile, src)
    # Every (tile, track) is a node; the overlap joins nodes of neighbouring tiles.
    parent: dict[tuple[int, int], tuple[int, int]] = {}

    def find(n):
        while parent.setdefault(n, n) != n:
            parent[n] = parent[parent[n]]
            n = parent[n]
        return n

    edges = {t["index"]: ((t["source"]["x"]) / src["width"], (t["source"]["x"] + t["source"]["w"]) / src["width"]) for t in tiles}
    by_frame = defaultdict(lambda: defaultdict(list))
    for b in rows:
        if b["label"] == "player":
            by_frame[b["frame"]][b["tile"]].append(b)
    joined = []
    for left, right in zip(tiles, tiles[1:]):
        li, ri = left["index"], right["index"]
        lo, hi = edges[ri][0], edges[li][1]  # the overlap, in source x
        inside = lambda b: lo <= b["x"] + b["w"] / 2 <= hi  # noqa: E731
        both = defaultdict(int)   # frames where the two tracks are inside the overlap
        match = defaultdict(int)  # ... and their boxes overlap
        for f, per_tile in by_frame.items():
            ls = [b for b in per_tile.get(li, []) if inside(b)]
            rs = [b for b in per_tile.get(ri, []) if inside(b)]
            for x in ls:
                for y in rs:
                    k = (x["track"], y["track"])
                    both[k] += 1
                    if _iou(x, y) > MIN_IOU:
                        match[k] += 1
        # Best pairs first; each track joins at most one on the other side.
        used_l, used_r = set(), set()
        for (lt, rt), n in sorted(match.items(), key=lambda kv: -kv[1]):
            if n < MIN_SHARED or n * 2 < both[(lt, rt)] or lt in used_l or rt in used_r:
                continue
            used_l.add(lt)
            used_r.add(rt)
            parent[find((ri, rt))] = find((li, lt))
            joined.append({"tiles": [li, ri], "tracks": [lt, rt], "frames": n})
    # New ids: one per joined group; inside an overlap, one box per player and frame.
    ids: dict[tuple[int, int], int] = {}
    merged = []
    rows_by_frame = defaultdict(list)
    for b in rows:
        rows_by_frame[b["frame"]].append(b)
    for f in sorted(rows_by_frame):
        best: dict[int, dict] = {}
        for b in rows_by_frame[f]:
            root = find((b["tile"], b["track"]))
            gid = ids.setdefault(root, len(ids) + 1)
            lo, hi = edges[b["tile"]]
            cx = b["x"] + b["w"] / 2
            margin = min(cx - lo, hi - cx)
            if b["label"] == "ball":
                gid = 0
            prev = best.get(gid)
            if prev is None or margin > prev["_margin"]:
                best[gid] = {**b, "track": gid, "_margin": margin}
        merged += best.values()
    cols = ["frame", "t_ms", "track", "label", "team", "x", "y", "w", "h", "conf", "interp", "tile"]
    out_rows = [[b.get(c) for c in cols] for b in sorted(merged, key=lambda b: (b["frame"], b["track"]))]
    frames = max(1, len({b["frame"] for b in rows}))
    per_tile = {t["index"]: len({b["track"] for b in rows if b["tile"] == t["index"] and b["label"] == "player"}) for t in tiles}
    players = [b for b in merged if b["label"] == "player"]
    stats = {
        "frames": frames,
        "tracks_per_tile": per_tile,
        "tracks_before": sum(per_tile.values()),
        "tracks_after": len({b["track"] for b in players}),
        "joins": len(joined),
        "players_per_frame_tiles": round(sum(1 for b in rows if b["label"] == "player") / frames, 2),
        "players_per_frame_merged": round(len(players) / frames, 2),
        "ball_frames": round(len({b["frame"] for b in merged if b["label"] == "ball"}) / frames, 3),
    }
    a.out.write_text(json.dumps({"columns": cols, "rows": out_rows, "joins": joined, "stats": stats}, indent=1) + "\n", encoding="utf-8")
    print(json.dumps(stats, indent=2))
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    pl = sub.add_parser("plan")
    pl.add_argument("video", type=Path)
    pl.add_argument("out", type=Path, help="tiles.json")
    pl.add_argument("--tiles", type=int, default=3)
    pl.add_argument("--size", type=int, default=1024, help="tile width (the long side SAM resizes to)")
    pl.add_argument("--overlap", type=int, default=160, help="least overlap between neighbouring tiles, in tile pixels")
    pl.add_argument("--height", choices=["pitch", "full"], default="pitch", help="crop the rows to the pitch, or keep the full height")
    pl.add_argument("--margin", type=float, default=0.02, help="around the pitch, as a share of the frame")
    pl.add_argument("--samples", type=int, default=30, help="frames where the pitch is measured")
    pl.add_argument("--name", default="tile", help="tile files: NAME_t1.mp4 …")
    pl.add_argument("--absolute", action="store_true", help="keep the video's absolute path in tiles.json")
    en = sub.add_parser("encode")
    en.add_argument("tiles_json", type=Path)
    en.add_argument("--video", type=Path, help="the source, if not where tiles.json says")
    en.add_argument("--start", type=float, default=0.0)
    en.add_argument("--seconds", type=float)
    en.add_argument("--crf", type=int, default=18)
    mg = sub.add_parser("merge")
    mg.add_argument("tiles_json", type=Path)
    mg.add_argument("runs", type=Path, nargs="+", help="one saved run per tile, in order")
    mg.add_argument("--out", type=Path, required=True)
    a = p.parse_args(argv)
    if a.cmd == "plan" and a.tiles < 1:
        p.error("--tiles must be 1 or more")
    return {"plan": plan, "encode": encode, "merge": merge}[a.cmd](a)


if __name__ == "__main__":
    sys.exit(main())
