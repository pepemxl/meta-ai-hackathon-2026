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
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from ppcoachtracker_worker import tiles  # noqa: E402



def _even(v: float) -> int:
    return max(2, int(round(v / 2)) * 2)


def plan(a) -> int:
    import cv2

    from ppcoachtracker_worker.pitch import measure

    cap = cv2.VideoCapture(str(a.video))
    if not cap.isOpened():
        print(f"cannot open {a.video}", file=sys.stderr)
        return 2
    W, H = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)), int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    polygons = []
    for k in range(a.samples):
        cap.set(cv2.CAP_PROP_POS_FRAMES, int((k + 0.5) * frames / a.samples))
        ok, img = cap.read()
        if ok and (m := measure(img)).has_pitch:
            polygons.append(list(m.polygon))
    cap.release()
    if len(polygons) < a.samples // 3:
        print(f"pitch found in only {len(polygons)} of {a.samples} frames: is it a wide shot?", file=sys.stderr)
        return 2
    x0, y0, x1, y1 = tiles.pitch_box(polygons, W, H, a.margin)
    if a.height == "full":
        y0, y1 = 0.0, float(H)
    tp = tiles.plan(W, H, (x0, y0, x1, y1), size=a.size, overlap=a.overlap, tiles=a.tiles)
    out = {
        "version": 1,
        "video": str(a.video.resolve()) if a.absolute else a.video.name,
        "source": {"width": W, "height": H, "fps": round(fps, 3), "frames": frames},
        "pitch": {"x": round(x0, 1), "y": round(y0, 1), "w": round(x1 - x0, 1), "h": round(y1 - y0, 1), "frames_with_pitch": len(polygons), "frames_sampled": a.samples},
        "scale": tp.scale,
        "size": a.size,
        "overlap_px": [tp.overlap_px] * (len(tp.tiles) - 1),
        "tiles": [{
            "index": t.index,
            "file": f"{a.name}_t{t.index}.mp4",
            # Where it sits in the source, in source pixels.
            "source": {"x": t.x, "y": t.y, "w": t.w, "h": t.h},
            "width": t.out_w,
            "height": t.out_h,
        } for t in tp.tiles],
    }
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(json.dumps(out, indent=2) + "\n", encoding="utf-8")
    print(f"pitch {x1 - x0:.0f}x{y1 - y0:.0f} px at ({x0:.0f},{y0:.0f}) of {W}x{H}; scale {tp.scale:.3f}; "
          f"{len(tp.tiles)} tiles of {tp.tiles[0].out_w}x{tp.tiles[0].out_h}, overlap {tp.overlap_px} px")
    for t in out["tiles"]:
        src = t["source"]
        print(f"  {t['file']}: source x {src['x']:.0f}..{src['x'] + src['w']:.0f}")
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


def _boxes(run: Path, tile: dict, src: dict) -> list[tuple[tiles.TileBox, dict]]:
    """The boxes of one tile's run, in the source's normalized coordinates (with their row)."""
    t = json.loads((run / "tracks.json").read_text(encoding="utf-8"))
    cols = t["columns"]
    s = tile["source"]
    out = []
    for r in t["rows"]:
        b = dict(zip(cols, r))
        box = ((s["x"] + b["x"] * s["w"]) / src["width"], (s["y"] + b["y"] * s["h"]) / src["height"],
               b["w"] * s["w"] / src["width"], b["h"] * s["h"] / src["height"])
        out.append((tiles.TileBox(b["frame"], tile["index"], b["track"], b["label"], box, b.get("conf") or 1.0), b))
    return out


def merge(a) -> int:
    spec = json.loads(a.tiles_json.read_text(encoding="utf-8"))
    src = spec["source"]
    tile_specs = spec["tiles"]
    if len(a.runs) != len(tile_specs):
        print(f"{len(tile_specs)} tiles but {len(a.runs)} runs", file=sys.stderr)
        return 2
    pairs = []
    for tile, run in zip(tile_specs, a.runs):
        pairs += _boxes(run, tile, src)
    boxes = [b for b, _ in pairs]
    row_of = {id(b): r for b, r in pairs}
    edges = {t["index"]: (t["source"]["x"] / src["width"], (t["source"]["x"] + t["source"]["w"]) / src["width"]) for t in tile_specs}
    # The same rule as the worker (ppcoachtracker_worker/tiles.py): players and balls joined per label.
    roots, joined = tiles.join_tracks(boxes, edges, "player")
    ball_roots, _ = tiles.join_tracks(boxes, edges, "ball")
    roots.update({k: v for k, v in ball_roots.items() if k != v})
    ids: dict[tuple[int, int], int] = {}
    merged = []
    for b in tiles.dedupe(boxes, roots, edges):
        root = roots[(b.tile, b.track)]
        gid = 0 if b.label == "ball" else ids.setdefault(root, len(ids) + 1)
        r = row_of[id(b)]
        merged.append({**r, "x": b.box[0], "y": b.box[1], "w": b.box[2], "h": b.box[3], "track": gid, "tile": b.tile})
    cols = ["frame", "t_ms", "track", "label", "team", "x", "y", "w", "h", "conf", "interp", "tile"]
    out_rows = [[b.get(c) for c in cols] for b in sorted(merged, key=lambda b: (b["frame"], b["track"]))]
    frames = max(1, len({b.frame for b in boxes}))
    per_tile = {t["index"]: len({b.track for b in boxes if b.tile == t["index"] and b.label == "player"}) for t in tile_specs}
    players = [b for b in merged if b["label"] == "player"]
    stats = {
        "frames": frames,
        "tracks_per_tile": per_tile,
        "tracks_before": sum(per_tile.values()),
        "tracks_after": len({b["track"] for b in players}),
        "joins": len(joined),
        "players_per_frame_tiles": round(sum(1 for b in boxes if b.label == "player") / frames, 2),
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
