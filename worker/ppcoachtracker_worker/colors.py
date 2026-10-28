"""Jersey colors: the same keys and RGB as JerseyColor in the app.

The key goes as-is into the SAM prompts ("player in red jersey").
"""

from __future__ import annotations

import numpy as np

JERSEY_RGB: dict[str, tuple[int, int, int]] = {
    "red": (0xE5, 0x39, 0x35),
    "white": (0xF5, 0xF5, 0xF5),
    "blue": (0x1E, 0x5B, 0xD8),
    "light blue": (0x6E, 0xC6, 0xFF),
    "black": (0x21, 0x21, 0x21),
    "yellow": (0xFD, 0xD8, 0x35),
    "green": (0x2E, 0x7D, 0x32),
    "orange": (0xFB, 0x8C, 0x00),
    "purple": (0x7B, 0x1F, 0xA2),
    "gray": (0x9E, 0x9E, 0x9E),
}


def normalize(name: str) -> str:
    return " ".join(str(name).strip().lower().replace("_", " ").split())


def is_known(name: str) -> bool:
    return normalize(name) in JERSEY_RGB


def rgb_to_lab(rgb: np.ndarray) -> np.ndarray:
    """sRGB (..., 3) in 0..255 → CIE Lab (D65), without OpenCV."""
    c = np.asarray(rgb, dtype=np.float64) / 255.0
    c = np.where(c > 0.04045, ((c + 0.055) / 1.055) ** 2.4, c / 12.92)
    m = np.array([[0.4124564, 0.3575761, 0.1804375], [0.2126729, 0.7151522, 0.0721750], [0.0193339, 0.1191920, 0.9503041]])
    xyz = c @ m.T / np.array([0.95047, 1.0, 1.08883])
    f = np.where(xyz > 216 / 24389, np.cbrt(xyz), (24389 / 27 * xyz + 16) / 116)
    lab = np.stack([116 * f[..., 1] - 16, 500 * (f[..., 0] - f[..., 1]), 200 * (f[..., 1] - f[..., 2])], axis=-1)
    return lab


def jersey_lab(name: str) -> np.ndarray | None:
    rgb = JERSEY_RGB.get(normalize(name))
    return None if rgb is None else rgb_to_lab(np.array(rgb))
