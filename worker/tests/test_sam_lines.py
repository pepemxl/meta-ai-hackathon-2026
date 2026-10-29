import base64
import math

import cv2
import numpy as np
import pytest

import pitchscene as ps
from ppcoachtracker_worker.calibration import calibrate, sam_lines
from ppcoachtracker_worker.calibration.solver import Camera, pitch_error_m, project
from ppcoachtracker_worker.calibration.template import circle_polyline
from ppcoachtracker_worker.chunks import load_clip
from ppcoachtracker_worker.meta_api import MetaClient, ResponseCache, Usage
from ppcoachtracker_worker.types import TrackSet

GRID = [(x, y) for x in range(-30, 31, 10) for y in range(-20, 21, 10)]


def lines_mask(cam: Camera = ps.CAM, width: int = 3) -> np.ndarray:
    """What SAM would answer for "white line": the template drawn with the camera."""
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


def test_the_documented_mask_is_the_objectʼs_box():
    # dev.meta.ai/docs/sam/reading-segmentation: "data=H,W,<marker><base85>", H × W the box (end inclusive).
    small = np.zeros((27, 60), bool)
    small[5:20, 10:50] = True
    payload = base64.b85encode(np.packbits(small.ravel()).tobytes()).decode()
    text = f"<2f>0<|box;x1=211;y1=228;x2=270;y2=254;w={ps.W};h={ps.H};c=0.9|><|mask;x=0;y=0;c=0.9;data=27,60,!{payload}|>"
    masks, unreadable = sam_lines.parse_masks(text, ps.W, ps.H)
    assert unreadable == 0
    expected = np.zeros((ps.H, ps.W), bool)
    expected[228:255, 211:271] = small
    assert np.array_equal(masks[2], expected)


def test_strokes_become_points_snapped_to_the_line_in_the_documented_prompt():
    diagonal = [[(0.1, 0.9), (0.5, 0.5), (0.9, 0.1)]]
    (pts,) = sam_lines.stroke_points(diagonal, 16 / 9)
    assert len(pts) == sam_lines.MAX_POINTS and pts[0] == (0.1, 0.9) and pts[-1] == pytest.approx((0.9, 0.1))
    assert len(sam_lines.stroke_points([[(0.5, 0.5), (0.52, 0.5)]], 16 / 9)[0]) == 2
    # A finger off the line by a few pixels: the point moves onto the white.
    img = np.full((100, 200, 3), (40, 120, 40), np.uint8)
    img[60, :] = 255
    assert sam_lines.snap(img, [(0.5, 0.57)], 4) == [(100, 60)]
    objects = [sam_lines.StrokeObject(3, [(10, 20), (30, 40)]), sam_lines.StrokeObject(0, [(5, 6), (7, 8)]), sam_lines.StrokeObject(3, [(1, 2), (3, 4)])]
    assert sam_lines.object_prompt(objects, 200, 100) == (
        "<0f>2<|point;x=5;y=6;w=200;h=100|><|point;x=7;y=8;w=200;h=100|>"
        "<3f>1<|point;x=10;y=20;w=200;h=100|><|point;x=30;y=40;w=200;h=100|>,3<|point;x=1;y=2;w=200;h=100|><|point;x=3;y=4;w=200;h=100|>")


def test_the_photo_is_letterboxed_and_only_the_longest_strokes_are_sent(tmp_path):
    # A portrait photo inside a landscape frame: centered, black bands on the sides.
    photo = np.full((400, 300, 3), 200, np.uint8)
    img, (sx, sy, ox, oy) = sam_lines.letterbox(photo, ps.W, ps.H)
    assert img.shape == (ps.H, ps.W, 3) and img[:, 0].max() == 0
    assert math.isclose(0.5 * sx + ox, 0.5, abs_tol=0.01) and math.isclose(sy, 1.0) and oy == 0
    strokes = [[(0.1, 0.1 + i * 0.04), (0.1 + 0.02 * (i + 1), 0.1 + i * 0.04)] for i in range(sam_lines.MAX_OBJECTS + 2)]
    video = sam_lines.PromptVideo(tmp_path / "p.mp4", ps.W, ps.H, [sam_lines.Reference(photo, strokes, "photo")])
    sent = video.sent_objects()
    video.close()
    assert len(video.objects) == sam_lines.MAX_OBJECTS + 2 and len(sent) == sam_lines.MAX_OBJECTS
    assert video.objects[0] not in sent and video.objects[1] not in sent  # the two shortest


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
    # The concept finds the lines on two key frames, the strokes on the other one: joined, all three.
    meta.by_concept[sam_lines.DEFAULT_CONCEPT] = stream(lines_mask(), [1, 2])
    meta.objects = stream(lines_mask(), [3])
    cal, notes = calibrate(clip, TrackSet(rows=[], frames=clip.frame_infos()), _calibration_with_strokes(), "no_lines", lines=_lines(meta, tmp_path, tmp_path / "raw"))
    sam = cal.report["sam"]
    assert cal.report["lines"] == "sam" and notes == [], notes
    # Two requests of 4 prompt frames, each billed at the 50-frame minimum.
    assert (sam["references"], sam["key_frames"], sam["strokes"], sam["strokes_sent"], sam["frames_charged"], sam["found"]) == (1, 3, 2, 2, 100, 3)
    assert all(f.get("confirmed") for f in sam["frames"])
    concept, strokes = (b["input"][0] for b in meta.sam_calls())
    assert concept["type"] == "message" and [c["type"] for c in concept["content"]] == ["input_text", "input_video"]
    assert concept["content"][0]["text"] == sam_lines.DEFAULT_CONCEPT
    # No noun phrase with the points: the photo's stroke on prompt frame 0, the touched one on frame 1 (the clip's frame 0).
    text = strokes["content"][0]["text"]
    assert text.startswith("<0f>1<|point;") and "<1f>2<|point;" in text and sam_lines.DEFAULT_CONCEPT not in text
    assert f"w={ps.W};h={ps.H}|>" in text
    assert len(list((tmp_path / "raw").glob("sam_pitch_lines_*.sse"))) == 2
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


def test_one_request_failing_keeps_the_other(meta, tmp_path):
    clip = load_clip(ps.make_clip(tmp_path / "pitch", seconds=3))
    meta.by_concept[sam_lines.DEFAULT_CONCEPT] = "<1f> 1 <|box;x1=0;y1=0;x2=1;y2=1|><|mask;format=unknown|>"
    meta.objects = stream(lines_mask(), [1, 2])
    cal, notes = calibrate(clip, TrackSet(rows=[], frames=clip.frame_infos()), _calibration_with_strokes(), "lines", lines=_lines(meta, tmp_path))
    assert notes == [] and cal.report["sam"]["found"] == 2
