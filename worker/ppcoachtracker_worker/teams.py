"""Team of each player track.

The team comes from the concept SAM found the player with ("player in red
jersey" → the team in red). Only when a track shows up in both team concepts,
or with the generic "soccer player", the torso color decides: median Lab of
the torso (15-50 % of the height, 25-75 % of the width) ignoring grass,
compared with the declared colors. Too far from both (referee, goalkeepers)
→ no team.
"""

from __future__ import annotations

import numpy as np

from .colors import jersey_lab, rgb_to_lab

# Lab distance: closer than this to a declared color, and clearly closer than to the other.
MAX_DISTANCE = 45.0
MIN_MARGIN = 8.0


def torso_lab(image_bgr: np.ndarray, box: tuple[float, float, float, float]) -> np.ndarray | None:
    """Median Lab of the torso of a normalized (x, y, w, h) box, grass pixels ignored."""
    h_img, w_img = image_bgr.shape[:2]
    x, y, w, h = box
    x1 = int((x + 0.25 * w) * w_img)
    x2 = int((x + 0.75 * w) * w_img)
    y1 = int((y + 0.15 * h) * h_img)
    y2 = int((y + 0.50 * h) * h_img)
    x1, x2 = max(0, x1), min(w_img, max(x2, x1 + 1))
    y1, y2 = max(0, y1), min(h_img, max(y2, y1 + 1))
    crop = image_bgr[y1:y2, x1:x2]
    if crop.size == 0:
        return None
    rgb = crop[..., ::-1].reshape(-1, 3).astype(np.float64)
    # Grass: green clearly above red and blue.
    g, r, b = rgb[:, 1], rgb[:, 0], rgb[:, 2]
    grass = (g > r * 1.15) & (g > b * 1.15) & (g > 60)
    keep = rgb[~grass]
    if len(keep) < max(4, 0.15 * len(rgb)):
        return None
    return np.median(rgb_to_lab(keep), axis=0)


def team_for_color(lab: np.ndarray | None, home_color: str, away_color: str) -> str:
    """'A' (home), 'B' (away) or '' when the color is too far from both or ambiguous."""
    if lab is None:
        return ""
    home, away = jersey_lab(home_color), jersey_lab(away_color)
    dists = []
    if home is not None:
        dists.append((float(np.linalg.norm(lab - home)), "A"))
    if away is not None:
        dists.append((float(np.linalg.norm(lab - away)), "B"))
    if not dists:
        return ""
    dists.sort()
    best, team = dists[0]
    if best > MAX_DISTANCE:
        return ""
    if len(dists) > 1 and dists[1][0] - best < MIN_MARGIN:
        return ""
    return team


def vote(labs: list[np.ndarray | None], home_color: str, away_color: str) -> str:
    """Team from several torso samples of one track (median of the samples)."""
    good = [l for l in labs if l is not None]
    if not good:
        return ""
    return team_for_color(np.median(np.stack(good), axis=0), home_color, away_color)


THIRD_GROUP_MAX_SHARE = 0.25


def far_from_both(lab: np.ndarray | None, home_color: str, away_color: str) -> bool:
    """A torso color farther than MAX_DISTANCE from both declared colors."""
    if lab is None:
        return False
    dists = [float(np.linalg.norm(lab - c)) for c in (jersey_lab(home_color), jersey_lab(away_color)) if c is not None]
    return bool(dists) and min(dists) > MAX_DISTANCE


def third_group(labs: dict, home_color: str, away_color: str) -> set:
    """Tracks of a small third color group, far from both teams (the referee, a goalkeeper):
    they stay without a team even if a team concept found them. If the group is big
    (more than a quarter of the tracks), the declared colors are wrong, not the tracks."""
    far = {k for k, lab in labs.items() if far_from_both(lab, home_color, away_color)}
    if not labs or len(far) > max(2, THIRD_GROUP_MAX_SHARE * len(labs)):
        return set()
    return far
