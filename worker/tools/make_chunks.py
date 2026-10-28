"""Cuts a local video into the app's chunk format, to test without a phone.

    python tools/make_chunks.py VIDEO OUT_DIR [--profile dev|demo] [--overlap-ms 2000..5000]
                                [--start S] [--seconds N] [--width 1280 --height 720]

Writes chunks.yaml and video_part_NNN.(mp4|yaml) at 10 fps without audio,
with the same cuts and overlap alignment as the app (docs/12-videos-largos-chunks.md).

OpenCV's pip wheels cannot write H.264 (no GPL encoder), so the chunks are
MPEG-4 Part 2 ("mp4v"). Real SAM 3.1 asks for H.264/H.265: for a run against
Meta, re-encode them (ffmpeg -c:v libx264) or use the chunks the app makes.
"""

from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from ppcoachtracker_worker.chunks import chunk_yaml, index_yaml, plan_cuts  # noqa: E402
from ppcoachtracker_worker.pitch import GRID_H, GRID_W, METHOD, measure  # noqa: E402

PROFILES = {"dev": (60, 2000), "demo": (300, 3000)}
FPS = 10


def main(argv: list[str] | None = None) -> int:
    import cv2

    p = argparse.ArgumentParser()
    p.add_argument("video", type=Path)
    p.add_argument("out", type=Path)
    p.add_argument("--profile", choices=sorted(PROFILES), default="dev")
    p.add_argument("--overlap-ms", type=int)
    p.add_argument("--start", type=float, default=0.0, help="start in the source, seconds")
    p.add_argument("--seconds", type=float, help="length to cut, seconds (default: to the end)")
    p.add_argument("--width", type=int, default=1280)
    p.add_argument("--height", type=int, default=720)
    p.add_argument("--clip-id", type=int, default=1)
    p.add_argument("--fourcc", default="mp4v")
    p.add_argument("--pitch", action="store_true", help="write the pitch section (method hsv_v1) as the app does")
    a = p.parse_args(argv)

    chunk_seconds, overlap_ms = PROFILES[a.profile]
    if a.overlap_ms is not None:
        if not 2000 <= a.overlap_ms <= 5000:
            p.error("--overlap-ms must be 2000..5000")
        overlap_ms = a.overlap_ms

    cap = cv2.VideoCapture(str(a.video))
    if not cap.isOpened():
        p.error(f"cannot open {a.video}")
    src_fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    src_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    src_ms = int(src_frames * 1000 / src_fps)
    begin = int(a.start * 1000)
    if begin >= src_ms:
        p.error(f"--start {a.start}s is past the end of the video ({src_ms / 1000:.1f}s)")
    end = min(src_ms, begin + int(a.seconds * 1000)) if a.seconds else src_ms
    duration = end - begin

    a.out.mkdir(parents=True, exist_ok=True)
    cuts = plan_cuts(duration, chunk_seconds, overlap_ms)
    index_rows = []
    next_start: int | None = None
    for n, (cut_start, cut_end) in enumerate(cuts, start=1):
        # Alignment: chunk n+1 starts at the first frame chunk n kept inside the overlap.
        start = next_start if next_start is not None else cut_start
        times = list(range(start, cut_end, 1000 // FPS))
        name = f"video_part_{n:03d}.mp4"
        writer = cv2.VideoWriter(str(a.out / name), cv2.VideoWriter_fourcc(*a.fourcc), FPS, (a.width, a.height))
        fmap = []
        pitch_rows = []
        for k, t in enumerate(times):
            src_frame = min(src_frames - 1, round((begin + t) * src_fps / 1000))
            cap.set(cv2.CAP_PROP_POS_FRAMES, src_frame)
            ok, img = cap.read()
            if not ok:
                break
            frame = cv2.resize(img, (a.width, a.height))
            writer.write(frame)
            fmap.append((k, src_frame, begin + t))
            if a.pitch:
                m = measure(frame)
                pitch_rows.append([k, m.fraction, list(m.polygon)])
        writer.release()
        overlap_frames = 0
        if n > 1:
            overlap_frames = sum(1 for _, _, t in fmap if t - begin < cuts[n - 2][1])
        if n < len(cuts):
            next_cut = cuts[n][0]
            next_start = next((t for t in times if t >= next_cut), next_cut)
        sha = hashlib.sha256((a.out / name).read_bytes()).hexdigest()
        real_overlap = (cuts[n - 2][1] - start) if n > 1 else 0
        yaml_text = chunk_yaml(
            clip={"id": a.clip_id, "match_id": 1, "source_file": a.video.name, "source_fps": round(src_fps, 2), "source_frames": src_frames, "duration_ms": duration},
            chunk={"number": n, "count": len(cuts), "file": name, "seconds": chunk_seconds, "start_ms": begin + start, "end_ms": begin + cut_end, "overlap_ms": real_overlap},
            encoding={"fps": FPS, "width": a.width, "height": a.height, "codec": a.fourcc, "bitrate": 0, "audio": 0},
            fmap=fmap,
            overlap_frames=overlap_frames,
            sha256=sha,
            pitch={"method": METHOD, "grid": [GRID_W, GRID_H], "with_pitch": sum(1 for r in pitch_rows if r[2]), "frames": pitch_rows} if a.pitch else None,
        )
        (a.out / f"video_part_{n:03d}.yaml").write_text(yaml_text, encoding="utf-8")
        index_rows.append({"number": n, "file": name, "start_ms": begin + start, "end_ms": begin + cut_end, "frames": len(fmap)})
        print(f"{name}: {len(fmap)} frames, {(begin + start) / 1000:.1f}-{(begin + cut_end) / 1000:.1f}s, overlap {overlap_frames} frames")
    (a.out / "chunks.yaml").write_text(index_yaml(a.clip_id, a.profile, chunk_seconds, overlap_ms, index_rows), encoding="utf-8")
    print(f"{a.out}/chunks.yaml: {len(cuts)} chunks ({a.profile}, {chunk_seconds}s, overlap {overlap_ms}ms)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
