"""python -m ppcoachtracker_worker run | once | analyze DIR [...]"""

from __future__ import annotations

import argparse
import json
import logging
import signal
import sys
import time
from pathlib import Path

from .api import PpApi
from .config import from_env, load_dotenv
from .chunks import load_clip
from .debug import save_run
from .pipeline import analyze, error_message, run_job
from .types import ConfigError


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="ppcoachtracker_worker")
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("run", help="poll the queue every POLL_SECONDS until SIGTERM")
    sub.add_parser("once", help="take and process one job")
    a = sub.add_parser("analyze", help="analyze a folder of chunks without the pp-coach-tracker API (calls Meta)")
    a.add_argument("dir", type=Path)
    a.add_argument("--home", default="Local")
    a.add_argument("--away", default="Visitante")
    a.add_argument("--home-color", default="red")
    a.add_argument("--away-color", default="blue")
    a.add_argument("--out", type=Path, help="result JSON (default DIR/analysis.json)")
    a.add_argument("--debug-dir", type=Path, help="also save the run for the viewer (default DEBUG_DIR)")
    a.add_argument("--touches", type=Path, help="the coach's points (JSON as POST /clips/:id/calibration), to test without the API")
    a.add_argument("--pitch-mode", choices=["lines", "no_lines", "off"], help="as the coach answered (default: as lines)")
    a.add_argument("--camera", choices=["static", "moving"], help="static: one homography for the whole video (default: moving)")
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    load_dotenv(Path.cwd() / ".env")
    try:
        cfg = from_env()
        if args.cmd in ("run", "once"):
            cfg.require_api()
    except ConfigError as e:
        print(f"config: {e}", file=sys.stderr)
        return 2

    if args.cmd == "analyze":
        match = {"home": args.home, "away": args.away, "home_color": args.home_color, "away_color": args.away_color}
        try:
            touches = json.loads(args.touches.read_text(encoding="utf-8")) if args.touches else None
            result, trackset = analyze(args.dir, match, cfg, raw_dir=args.dir / "raw", pitch_mode=args.pitch_mode, camera=args.camera, calibration=touches)
            debug_dir = args.debug_dir or cfg.debug_dir
            if debug_dir is not None:
                print(f"run saved in {save_run(debug_dir, load_clip(args.dir), result, trackset, calibration=analyze.last_calibration, masks=analyze.last_masks, raw=analyze.last_raw)}")
        except Exception as e:  # noqa: BLE001
            print(error_message(e), file=sys.stderr)
            return 1
        out = args.out or args.dir / "analysis.json"
        out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"{out} ({result['backends']['segmenter']}, {result['backends']['analyst']}, usage {result['backends']['usage']})")
        return 0

    api = PpApi(cfg.api_url, cfg.worker_secret)
    if args.cmd == "once":
        job = api.next_job()
        if job is None:
            print("no job")
            return 0
        return 0 if run_job(api, cfg, job) == "done" else 1

    stop = {"now": False}
    signal.signal(signal.SIGTERM, lambda *_: stop.update(now=True))
    signal.signal(signal.SIGINT, lambda *_: stop.update(now=True))
    logging.info("polling %s every %ss (SAM at %s)", cfg.api_url, cfg.poll_seconds, "Meta" if cfg.sam_is_meta else "the local simulator")
    while not stop["now"]:
        try:
            job = api.next_job()
        except Exception as e:  # noqa: BLE001 - the API may be restarting
            logging.warning("queue: %s", e)
            job = None
        if job is not None:
            run_job(api, cfg, job)
            continue
        for _ in range(int(cfg.poll_seconds * 10)):
            if stop["now"]:
                break
            time.sleep(0.1)
    return 0


if __name__ == "__main__":
    sys.exit(main())
