"""Camera cuts: the hue/saturation histogram jumps 0.44 or more between two
frames (a moving shot reaches about 0.27). Tracks start again at each cut.
"""

from __future__ import annotations

import numpy as np

CUT_THRESHOLD = 0.44


def histogram(image_bgr: np.ndarray) -> np.ndarray:
    import cv2

    hsv = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2HSV)
    h = cv2.calcHist([hsv], [0, 1], None, [30, 32], [0, 180, 0, 256])
    return cv2.normalize(h, h).flatten()


def distance(a: np.ndarray, b: np.ndarray) -> float:
    import cv2

    return float(cv2.compareHist(a, b, cv2.HISTCMP_BHATTACHARYYA))


def is_cut(prev_hist: np.ndarray, hist: np.ndarray) -> bool:
    return distance(prev_hist, hist) >= CUT_THRESHOLD


def cut_frames(histograms: list[np.ndarray | None]) -> set[int]:
    """Indices where a new shot starts (the frame after the jump)."""
    cuts = set()
    for i in range(1, len(histograms)):
        a, b = histograms[i - 1], histograms[i]
        if a is not None and b is not None and is_cut(a, b):
            cuts.add(i)
    return cuts
