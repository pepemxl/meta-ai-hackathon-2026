import base64
import math

import cv2
import numpy as np

import pitchscene as ps
from ppcoachtracker_worker.calibration import calibrate, sam_lines
from ppcoachtracker_worker.calibration.solver import Camera, pitch_error_m, project
from ppcoachtracker_worker.calibration.template import circle_polyline
from ppcoachtracker_worker.chunks import load_clip
from ppcoachtracker_worker.meta_api import MetaClient, ResponseCache, Usage
from ppcoachtracker_worker.types import TrackSet

GRID = [(x, y) for x in range(-30, 31, 10) for y in range(-20, 21, 10)]


def lines_mask(cam: Camera = ps.CAM, width: int = 3) -> np.ndarray:
    """What SAM would answer for "white line on a soccer field": the template drawn with the camera."""
    img = np.zeros((ps.H, ps.W), np.uint8)
    Hm = cam.homography()
    for a, b in ps.TPL.lines.values():
        p, q = project(Hm, [a, b]).round().astype(int)
        cv2.line(img, tuple(p), tuple(q), 255, width)
    cv2.polylines(img, [project(Hm, circle_polyline(ps.TPL)).round().astype(np.int32)], False, 255, width)
    return img > 0


def rle_counts(mask: np.ndarray) -> list[int]:
    flat = mask.T.ravel().astype(np.int8)
    change = np.flatnonzero(np.diff(np.r_[0, flat, 1 - flat[-1]]))
    counts = np.diff(np.r_[0, change]).tolist()
    return counts  # starts with the zeros (0 when the mask starts with ones)


def rle_string(counts: list[int]) -> str:
    """pycocotools' rleToString."""
    out = []
    for i, x in enumerate(counts):
        if i > 2:
            x -= counts[i - 2]
        more = True
        while more:
            c = x & 0x1F
            x >>= 5
            more = (x != -1) if (c & 0x10) else (x != 0)
            if more:
                c |= 0x20
            out.append(chr(c + 48))
    return "".join(out)


def stream(mask: np.ndarray, frames) -> str:
    rle = rle_string(rle_counts(mask))
    return "".join(f"<{k}f> 1 <|box;x1=0;y1=0;x2=1;y2=1;c=0.9|><|mask;counts={rle};size={ps.H},{ps.W}|>" for k in frames)


def test_masks_are_read_in_every_tolerated_format():
    mask = lines_mask()
    rle = rle_string(rle_counts(mask))
    assert ";" in rle or "=" in rle or any(ch in "abcdefghijklmno" for ch in rle)
    got = sam_lines.decode_mask(f"counts={rle};size={ps.H},{ps.W}", None, ps.W, ps.H)
    assert np.array_equal(got, mask)
    # A string that looks like an attribute inside the counts (";c=") does not split them.
    tricky = sam_lines._ATTR.findall("counts=ab;c=de;size=2,3")
    assert dict(tricky)["counts"] == "ab;c=de"
    numeric = " ".join(map(str, rle_counts(mask)))
    assert np.array_equal(sam_lines.decode_mask(f"counts=[{numeric}];size={ps.H},{ps.W}", None, ps.W, ps.H), mask)
    bits = base64.b64encode(np.packbits(mask.ravel())).decode()
    assert np.array_equal(sam_lines.decode_mask(f"bits={bits};size={ps.H},{ps.W}", None, ps.W, ps.H), mask)
    png = base64.b64encode(cv2.imencode(".png", mask.astype(np.uint8) * 255)[1].tobytes()).decode()
    assert np.array_equal(sam_lines.decode_mask(f"png={png}", None, ps.W, ps.H), mask)
    # The mask of the box only, placed back in the frame.
    small = np.ones((10, 20), bool)
    placed = sam_lines.decode_mask(f"counts={rle_string(rle_counts(small))};size=10,20", (0.5, 0.5, 20 / ps.W, 10 / ps.H), ps.W, ps.H)
    assert placed.sum() == 200 and placed[ps.H // 2, ps.W // 2]
    assert sam_lines.decode_mask("counts=;size=", None, ps.W, ps.H) is None
    masks, unreadable = sam_lines.parse_masks(stream(mask, [0, 3]) + "<4f> 1 <|box;x1=0;y1=0;x2=1;y2=1|><|mask;garbage|>", ps.W, ps.H)
    assert sorted(masks) == [0, 3] and unreadable == 1


def test_exemplars_are_small_boxes_along_the_stroke_and_follow_the_letterbox():
    diagonal = [[(0.1, 0.9), (0.9, 0.1)]]
    boxes = sam_lines.exemplar_boxes(diagonal, 16 / 9)
    assert len(boxes) > 8
    assert all(b[2] - b[0] < 0.1 and b[3] - b[1] < 0.15 for b in boxes)
    # A portrait photo inside a landscape frame: centered, black bands on the sides.
    photo = np.full((400, 300, 3), 200, np.uint8)
    img, (sx, sy, ox, oy) = sam_lines.letterbox(photo, ps.W, ps.H)
    assert img.shape == (ps.H, ps.W, 3) and img[:, 0].max() == 0
    assert math.isclose(0.5 * sx + ox, 0.5, abs_tol=0.01) and math.isclose(sy, 1.0) and oy == 0


def test_a_drifted_homography_is_pulled_back_onto_samʼs_lines():
    s_true = ps.CAM.homography()
    camera = Camera.from_homography(s_true, ps.W, ps.H, list(ps.TPL.points.values()))
    drifted = ps.CAM.with_params(ps.CAM.params + [0, 0, 0, math.radians(1.5), math.radians(-0.8), 0, 25])
    assert pitch_error_m(drifted.homography(), s_true, GRID) > 1.5
    keys, contradicted, report = sam_lines.refine_keys({5: lines_mask()}, {5: drifted.homography()}, camera, ps.TPL)
    assert 5 in keys and not contradicted, report
    assert pitch_error_m(keys[5], s_true, GRID) < 0.5
    # A propagated homography that already falls on the lines is confirmed, not refitted.
    keys2, _, report2 = sam_lines.refine_keys({5: lines_mask()}, {5: s_true}, camera, ps.TPL)
    assert keys2 == {} and report2[0]["confirmed"]


def test_noise_is_not_taken_for_lines():
    rng = np.random.default_rng(3)
    noise = np.zeros((ps.H, ps.W), np.uint8)
    for _ in range(40):
        x, y = rng.integers(0, ps.W), rng.integers(0, ps.H)
        cv2.circle(noise, (int(x), int(y)), 3, 255, -1)
    camera = Camera.from_homography(ps.CAM.homography(), ps.W, ps.H, list(ps.TPL.points.values()))
    keys, _, _ = sam_lines.refine_keys({5: noise > 0}, {5: ps.CAM.homography()}, camera, ps.TPL)
    assert keys == {}


def _calibration_with_strokes(frame: int = 0) -> dict:
    cal = ps.touches(frame=frame)
    cal["frames"][0]["lines"] = [{**ps.stroke("touch_near"), "name": None}]
    cal["references"] = [{"n": 1, "source": "photo", "strokes": [{"points": [{"x": 0.2, "y": 0.7}, {"x": 0.8, "y": 0.7}]}]}]
    return cal


def _lines(meta, tmp_path, raw=None):
    client = MetaClient(meta.url, "test-key-do-not-print-0123456789", "meta_sam", Usage(), ResponseCache(tmp_path / "cache"), sleep=lambda s: None)
    return sam_lines.SamLines(client, "sam-3.1", raw_dir=raw)


def test_sam_gets_the_coachʼs_pictures_and_strokes_and_confirms_the_pitch(meta, tmp_path):
    clip = load_clip(ps.make_clip(tmp_path / "pitch", seconds=3))
    cv2.imwrite(str(clip.dir / "reference_1.jpg"), ps.background())
    # Prompt video: frame 0 the photo, then key frames 0, 10, 20 of the clip (one per second).
    meta.by_concept[sam_lines.DEFAULT_CONCEPT] = stream(lines_mask(), range(4))
    cal, notes = calibrate(clip, TrackSet(rows=[], frames=clip.frame_infos()), _calibration_with_strokes(), "no_lines", lines=_lines(meta, tmp_path, tmp_path / "raw"))
    sam = cal.report["sam"]
    assert cal.report["lines"] == "sam" and notes == [], notes
    assert (sam["references"], sam["key_frames"], sam["frames_charged"], sam["found"]) == (1, 3, 4, 3)
    assert all(f.get("confirmed") for f in sam["frames"])
    body = meta.sam_calls()[0]
    prompts = [c for c in body["input"][0]["content"] if c["type"] == "input_visual_prompt"]
    # The photo (prompt frame 0) and the touched frame (prompt frame 1, the clip's frame 0).
    assert [p["frame"] for p in prompts] == [0, 1] and all(p["boxes"] and p["labels"][0] == 1 for p in prompts)
    assert any(c["type"] == "input_text" and c["text"] == sam_lines.DEFAULT_CONCEPT for c in body["input"][0]["content"])
    assert list((tmp_path / "raw").glob("sam_pitch_lines_*.sse"))
    assert cal.report["frames_with_homography"] == 1.0


def test_no_lines_without_strokes_does_not_call_sam_and_a_failure_keeps_the_points(meta, tmp_path):
    clip = load_clip(ps.make_clip(tmp_path / "pitch", seconds=2))
    ts = TrackSet(rows=[], frames=clip.frame_infos())
    cal, _ = calibrate(clip, ts, ps.touches(), "no_lines", lines=_lines(meta, tmp_path))
    assert meta.sam_calls() == [] and cal.report["lines"] == "off"
    meta.by_concept[sam_lines.DEFAULT_CONCEPT] = "<1f> 1 <|box;x1=0;y1=0;x2=1;y2=1|><|mask;format=unknown|>"
    cal, notes = calibrate(clip, ts, ps.touches(), "lines", lines=_lines(meta, tmp_path))
    assert cal.report["frames_with_homography"] == 1.0
    assert any("SAM no pudo buscar las líneas" in n for n in notes)
