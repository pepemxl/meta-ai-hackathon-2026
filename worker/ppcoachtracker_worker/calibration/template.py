"""The pitch in meters, with every point and line named.

Origin at the center of the pitch, x along its length (left → right as the
camera sees it), y across (y > 0 is the far touchline). 11-a-side 105×68 and
7-a-side 60×40 by default; amateur pitches vary, so the length and width are
configurable and the result says when the defaults were used.

The app (data/PitchTouches.kt) uses EXACTLY these names; a test compares them.
"""

from __future__ import annotations

from dataclasses import dataclass, field

# Markings per size: area depth and width, goal area depth and width, penalty
# spot, center circle radius, goal width. 7-a-side varies by league: these are
# common amateur values.
MARKINGS = {
    11: dict(box_depth=16.5, box_width=40.32, goal_area_depth=5.5, goal_area_width=18.32, spot=11.0, circle=9.15, goal=7.32),
    7: dict(box_depth=9.0, box_width=24.0, goal_area_depth=3.0, goal_area_width=12.0, spot=8.0, circle=6.0, goal=6.0),
}
DEFAULT_SIZE = {11: (105.0, 68.0), 7: (60.0, 40.0)}


@dataclass(frozen=True)
class Template:
    size: int = 11
    length: float = 105.0
    width: float = 68.0
    approximate: bool = True  # default measures, not measured by the coach
    points: dict[str, tuple[float, float]] = field(default_factory=dict)
    lines: dict[str, tuple[tuple[float, float], tuple[float, float]]] = field(default_factory=dict)

    def contains(self, x: float, y: float, margin: float = 0.0) -> bool:
        return abs(x) <= self.length / 2 + margin and abs(y) <= self.width / 2 + margin


def build(size: int = 11, length: float | None = None, width: float | None = None) -> Template:
    if size not in MARKINGS:
        raise ValueError("size must be 11 or 7")
    dl, dw = DEFAULT_SIZE[size]
    L = float(length or dl)
    W = float(width or dw)
    m = MARKINGS[size]
    hx, hy = L / 2, W / 2
    pts: dict[str, tuple[float, float]] = {
        "corner_far_left": (-hx, hy), "corner_far_right": (hx, hy),
        "corner_near_left": (-hx, -hy), "corner_near_right": (hx, -hy),
        "halfway_far": (0.0, hy), "halfway_near": (0.0, -hy), "center": (0.0, 0.0),
        "circle_far": (0.0, m["circle"]), "circle_near": (0.0, -m["circle"]),
        "circle_left": (-m["circle"], 0.0), "circle_right": (m["circle"], 0.0),
        "spot_left": (-hx + m["spot"], 0.0), "spot_right": (hx - m["spot"], 0.0),
    }
    lines: dict[str, tuple[tuple[float, float], tuple[float, float]]] = {
        "touch_far": ((-hx, hy), (hx, hy)), "touch_near": ((-hx, -hy), (hx, -hy)),
        "goal_left": ((-hx, -hy), (-hx, hy)), "goal_right": ((hx, -hy), (hx, hy)),
        "halfway": ((0.0, -hy), (0.0, hy)),
    }
    for side, sx in (("left", -1.0), ("right", 1.0)):
        gx = sx * hx
        pts[f"post_far_{side}"] = (gx, m["goal"] / 2)
        pts[f"post_near_{side}"] = (gx, -m["goal"] / 2)
        for prefix, depth, w in (("box", m["box_depth"], m["box_width"]), ("goal_area", m["goal_area_depth"], m["goal_area_width"])):
            inner = gx - sx * depth
            pts[f"{prefix}_far_{side}_goal"] = (gx, w / 2)
            pts[f"{prefix}_far_{side}"] = (inner, w / 2)
            pts[f"{prefix}_near_{side}_goal"] = (gx, -w / 2)
            pts[f"{prefix}_near_{side}"] = (inner, -w / 2)
            lines[f"{prefix}_{side}"] = ((inner, -w / 2), (inner, w / 2))
            lines[f"{prefix}_{side}_far"] = ((gx, w / 2), (inner, w / 2))
            lines[f"{prefix}_{side}_near"] = ((gx, -w / 2), (inner, -w / 2))
    return Template(size=size, length=L, width=W, approximate=length is None or width is None, points=pts, lines=lines)


def circle_polyline(t: Template, n: int = 48) -> list[tuple[float, float]]:
    import math

    r = MARKINGS[t.size]["circle"]
    return [(r * math.cos(2 * math.pi * k / n), r * math.sin(2 * math.pi * k / n)) for k in range(n + 1)]
