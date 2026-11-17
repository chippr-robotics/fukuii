#!/usr/bin/env python3
"""Draws the sprite sheets of the TUI command center (spec 029, contracts/art.md).

The art is code: every sheet under src/main/resources/tui/art/ is this script's output, so a change to a sprite is a
change here, and `--check` fails when the committed sheets differ from what the script draws.

    python3 -I scripts/tui/make_art.py                # rewrite the sheets and art.conf
    python3 -I scripts/tui/make_art.py --check        # exit 1 if the committed sheets are stale
    python3 -I scripts/tui/make_art.py --preview DIR  # also write enlarged contact sheets for people to look at

A sprite is drawn on a small canvas of palette indices (0 is transparent, 16 to 255 are the xterm-256 colours of
contracts/xterm-240.gpl). The same drawing code runs at three sizes: 64, 32 and 16 art pixels square.
Needs Pillow and NumPy.
"""

from __future__ import annotations

import argparse
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image

REPO = Path(__file__).resolve().parents[2]
ART_DIR = REPO / "src" / "main" / "resources" / "tui" / "art"
# What each sheet's pixels hash to: the node's own PNG reader is tested against it (PngSpec).
PIXEL_HASHES = REPO / "src" / "test" / "resources" / "tui" / "art-pixels.sha256"

# ── palette ──────────────────────────────────────────────────────────────────────────────────────────────────────

CUBE = (0, 95, 135, 175, 215, 255)


def xterm_rgb(index: int) -> tuple[int, int, int]:
    """The RGB value of xterm-256 colour `index` (16 to 255)."""
    if 16 <= index <= 231:
        n = index - 16
        return CUBE[n // 36], CUBE[(n // 6) % 6], CUBE[n % 6]
    if 232 <= index <= 255:
        v = 8 + 10 * (index - 232)
        return v, v, v
    raise ValueError(f"not a sprite colour: {index}")


# Colour roles, by xterm index.
T = 0  # transparent
BLACK, WHITE = 16, 231
G_LINE, G_DEEP, G_DARK, G_BASE, G_LIGHT, G_HI = 22, 28, 34, 40, 82, 155  # the mantis, dark to light
EYE, EYE_GLOSS, EYE_RIM = 16, 60, 34
MOUTH, MOUTH_DARK, TONGUE = 196, 88, 211
W_LINE, W_DARK, W_BASE, W_LIGHT, W_HI = 125, 162, 205, 211, 218  # the worm
C_LINE, C_DARK, C_BASE, C_LIGHT, C_KEY, C_KEY_HI = 238, 234, 236, 242, 246, 252  # the console
CYAN, AMBER, RED, RED_HI, YELLOW = 44, 214, 196, 203, 226
HEX_LINE, HEX_A, HEX_B = 231, 28, 34


# ── canvas ───────────────────────────────────────────────────────────────────────────────────────────────────────


class Canvas:
    """A grid of palette indices with the few drawing operations pixel art needs. Nothing is anti-aliased."""

    def __init__(self, width: int, height: int):
        self.w, self.h = width, height
        self.a = np.zeros((height, width), dtype=np.uint8)

    def px(self, x: float, y: float, c: int) -> None:
        xi, yi = int(math.floor(x)), int(math.floor(y))
        if 0 <= xi < self.w and 0 <= yi < self.h:
            self.a[yi, xi] = c

    def get(self, x: int, y: int) -> int:
        return int(self.a[y, x]) if 0 <= x < self.w and 0 <= y < self.h else T

    def rect(self, x0: float, y0: float, x1: float, y1: float, c: int) -> None:
        """Fills the pixels whose centres lie in [x0, x1) × [y0, y1)."""
        xa, xb = max(0, math.ceil(x0 - 0.5)), min(self.w, math.ceil(x1 - 0.5))
        ya, yb = max(0, math.ceil(y0 - 0.5)), min(self.h, math.ceil(y1 - 0.5))
        if xa < xb and ya < yb:
            self.a[ya:yb, xa:xb] = c

    def where(self, inside, colour) -> None:
        """Paints every pixel whose centre satisfies `inside(x, y)`; `colour` is an index or a function of (x, y)."""
        ys, xs = np.mgrid[0 : self.h, 0 : self.w]
        x, y = xs + 0.5, ys + 0.5
        mask = inside(x, y)
        if callable(colour):
            self.a[mask] = colour(x, y)[mask]
        else:
            self.a[mask] = colour

    def disc(self, cx: float, cy: float, r: float, c: int) -> None:
        self.where(lambda x, y: (x - cx) ** 2 + (y - cy) ** 2 <= r * r, c)

    def ellipse(self, cx, cy, rx, ry, colour, tilt: float = 0.0) -> None:
        self.where(_in_ellipse(cx, cy, rx, ry, tilt), colour)

    def stroke(self, points, r: float, c: int) -> None:
        """A thick line through `points`: discs of radius `r` along each segment."""
        for (x0, y0), (x1, y1) in zip(points, points[1:]):
            steps = max(1, int(math.hypot(x1 - x0, y1 - y0) * 3))
            for i in range(steps + 1):
                t = i / steps
                self.disc(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, r, c)

    def line(self, x0, y0, x1, y1, c: int) -> None:
        """A one-pixel line."""
        steps = max(1, int(round(max(abs(x1 - x0), abs(y1 - y0)))))
        for i in range(steps + 1):
            t = i / steps
            self.px(x0 + (x1 - x0) * t + 0.5, y0 + (y1 - y0) * t + 0.5, c)

    def outline(self, c: int, only=None) -> None:
        """Paints `c` on every transparent pixel that touches (by an edge) a painted one, or one of `only`."""
        painted = np.isin(self.a, list(only)) if only is not None else self.a != T
        near = np.zeros_like(painted)
        near[1:, :] |= painted[:-1, :]
        near[:-1, :] |= painted[1:, :]
        near[:, 1:] |= painted[:, :-1]
        near[:, :-1] |= painted[:, 1:]
        self.a[near & (self.a == T)] = c

    def blit(self, other: "Canvas", x: int, y: int) -> None:
        """Draws `other` over this canvas with its top-left corner at (x, y); transparent pixels leave ours alone."""
        for sy in range(other.h):
            for sx in range(other.w):
                c = int(other.a[sy, sx])
                if c != T:
                    self.px(x + sx, y + sy, c)

    def tri(self, a, b, c, colour: int) -> None:
        """Fills the triangle a, b, c."""
        (ax, ay), (bx, by), (cx, cy) = a, b, c

        def inside(x, y):
            d1 = (x - bx) * (ay - by) - (ax - bx) * (y - by)
            d2 = (x - cx) * (by - cy) - (bx - cx) * (y - cy)
            d3 = (x - ax) * (cy - ay) - (cx - ax) * (y - ay)
            neg = (d1 < 0) | (d2 < 0) | (d3 < 0)
            pos = (d1 > 0) | (d2 > 0) | (d3 > 0)
            return ~(neg & pos)

        self.where(inside, colour)

    def bitmap(self, rows: list[str], x: int, y: int, colours: dict[str, int]) -> None:
        """Draws rows of characters, one character a pixel; a character missing from `colours` is transparent."""
        for dy, row in enumerate(rows):
            for dx, ch in enumerate(row):
                if ch in colours:
                    self.px(x + dx, y + dy, colours[ch])


def _in_ellipse(cx, cy, rx, ry, tilt=0.0):
    ct, st = math.cos(tilt), math.sin(tilt)

    def inside(x, y):
        dx, dy = x - cx, y - cy
        u, v = dx * ct + dy * st, -dx * st + dy * ct
        return (u / rx) ** 2 + (v / ry) ** 2 <= 1.0

    return inside


def lit(cx, cy, rx, ry, dark, base, light, hi=None, light_at=0.38, dark_at=-0.42, hi_at=0.8):
    """A colour function: bands of light from the upper left across an ellipse, cel-shaded."""

    def colour(x, y):
        t = (-(x - cx) / rx * 0.62) + (-(y - cy) / ry * 0.78)
        out = np.full(x.shape, base, dtype=np.uint8)
        out[t > light_at] = light
        if hi is not None:
            out[t > hi_at] = hi
        out[t < dark_at] = dark
        return out

    return colour


# ── the mantis ───────────────────────────────────────────────────────────────────────────────────────────────────


def _head_half_width(t):
    """Half the head's width at height `t` (0 at the crown, 1 at the chin), as a share of its widest."""
    cap = np.sqrt(np.clip(1 - ((0.36 - t) / 0.36) ** 2, 0, 1))
    taper = 1 - 0.80 * np.clip((t - 0.36) / 0.64, 0, 1) ** 1.45
    return np.where(t < 0.36, cap, taper)


def head(cv: Canvas, cx: float, cy: float, s: float, eyes="open", mouth="smile", look=(0.0, 0.0)) -> None:
    """The mantis's head: a rounded triangle, two great eyes at its upper corners, a small red mouth.

    (cx, cy) is the centre between the eyes; `s` is the scale (1.0 on the 64-pixel canvas).
    """
    half, top, height = 17.5 * s, cy - 11 * s, 29 * s

    def in_head(x, y):
        t = (y - top) / height
        return (t >= 0) & (t <= 1) & (np.abs(x - cx) <= half * _head_half_width(t))

    cv.where(in_head, G_BASE)
    # Light on the crown, shade under the chin, a pale ridge down the brow and a glint beside it.
    cv.where(lambda x, y: in_head(x, y) & (y < top + max(1.0, 4 * s)), G_LIGHT)
    cv.where(lambda x, y: in_head(x, y) & (y > cy + 7 * s) & ((x - cx) > -(y - cy - 7 * s) * 0.9), G_DARK)
    if s >= 0.75:
        cv.where(lambda x, y: in_head(x, y) & (np.abs(x - cx) < 1.1) & (y < cy + 4), G_LIGHT)
        cv.rect(cx - 6, top + 1, cx - 2, top + 3, G_HI)
        cv.rect(cx - 7, top + 2, cx - 6, top + 4, G_HI)
    elif s >= 0.45:
        cv.where(lambda x, y: in_head(x, y) & (np.abs(x - cx) < 0.6) & (y < cy + 2), G_LIGHT)
        cv.px(cx - 3, top + 0.5, G_HI)

    for side in (-1, 1):
        ex, ey = cx + side * 11.6 * s, cy - 0.5 * s
        rx, ry, tilt = 5.9 * s, 7.9 * s, side * 0.24
        cv.ellipse(ex, ey, rx + 1.0, ry + 1.0, EYE_RIM, tilt)
        if eyes == "closed":
            cv.ellipse(ex, ey, rx, ry, lit(ex, ey, rx, ry, G_DEEP, G_DARK, G_BASE, light_at=0.55), tilt)
            for i in range(int(-rx) + 1, int(rx)):
                yy = ey + 2.5 * s - (abs(i) / max(rx, 1)) ** 2 * 2.4 * s
                cv.px(ex + i, yy, G_LINE)
                if s >= 0.75:
                    cv.px(ex + i, yy + 1, G_LINE)
            continue
        cv.ellipse(ex, ey, rx, ry, EYE, tilt)
        if s >= 0.75:
            cv.where(lambda x, y: _in_ellipse(ex, ey, rx, ry, tilt)(x, y)
                     & ~_in_ellipse(ex - 0.9, ey - 1.1, rx, ry, tilt)(x, y), EYE_GLOSS)
        lx, ly = look
        hx, hy = ex - 2.6 * s + lx * 2.2 * s, ey - 4.2 * s + ly * 2.2 * s
        big = max(1.0, round(3 * s))
        cv.rect(hx - big / 2, hy - big / 2, hx + big / 2, hy + big / 2, WHITE)
        if s >= 0.75:
            cv.px(hx + 3.0 + big / 2, hy + 4.5, WHITE)
        if eyes == "half":
            cv.where(lambda x, y: _in_ellipse(ex, ey, rx, ry, tilt)(x, y) & (y < ey - 1.5 * s), G_BASE)
            cv.where(lambda x, y: _in_ellipse(ex, ey, rx, ry, tilt)(x, y) & (np.abs(y - (ey - 1.5 * s)) < 0.6), G_LINE)

    mx, my = cx, cy + 10.5 * s
    if mouth == "smile":
        if s >= 0.75:
            cv.rect(mx - 3, my - 1, mx + 3, my, MOUTH_DARK)
            cv.rect(mx - 3, my, mx + 3, my + 2, MOUTH)
            cv.rect(mx - 2, my + 2, mx + 2, my + 3, MOUTH)
            cv.rect(mx - 1, my + 1, mx + 1, my + 2, TONGUE)
        else:
            cv.rect(mx - 1.5 * max(s * 2, 1), my - 0.5, mx + 1.5 * max(s * 2, 1), my + max(1, round(2 * s)) - 0.5, MOUTH)
    elif mouth == "open":
        # A round shout: a red rim, dark inside.
        r = max(1.0, 2.7 * s)
        cv.ellipse(mx, my + 1.0 * s, r, r * 1.25, MOUTH)
        if s >= 0.75:
            cv.ellipse(mx, my + 1.0, r - 1.1, r * 1.25 - 1.1, MOUTH_DARK)
            cv.rect(mx - 1, my + 2.5, mx + 1, my + 3.5, TONGUE)
    elif mouth == "grin":
        if s >= 0.75:
            cv.rect(mx - 4, my - 1, mx + 4, my, MOUTH_DARK)
            cv.rect(mx - 4, my, mx + 4, my + 2, MOUTH)
            cv.rect(mx - 3, my + 2, mx + 3, my + 3, MOUTH)
            cv.rect(mx - 2, my + 3, mx + 2, my + 4, MOUTH)
            cv.rect(mx - 2, my + 1, mx + 2, my + 3, TONGUE)
        else:
            w = max(1.0, round(4 * s))
            cv.rect(mx - w, my - 0.5, mx + w, my + max(1, round(3 * s)) - 0.5, MOUTH)
    elif mouth == "small":
        cv.rect(mx - max(1, round(1.5 * s)), my, mx + max(1, round(1.5 * s)), my + 1, MOUTH_DARK)
    elif mouth == "frown":
        if s >= 0.75:
            cv.rect(mx - 2, my, mx + 2, my + 1, MOUTH_DARK)
            cv.px(mx - 3, my + 1, MOUTH_DARK)
            cv.px(mx + 2, my + 1, MOUTH_DARK)
        else:
            cv.rect(mx - 1, my, mx + 1, my + 1, MOUTH_DARK)


def antennae(cv: Canvas, cx: float, cy: float, s: float, droop: float = 0.0) -> None:
    shapes = {
        -1: [(-2, -10), (-1, -15), (-2, -19), (-4 - droop * 2, -21 + droop * 5)],
        1: [(4, -10), (7, -15), (11, -19), (15 + droop * 2, -21 + droop * 7)],
    }
    for pts in shapes.values():
        pts = [(cx + x * s, cy + y * s) for x, y in pts]
        for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
            cv.line(x0, y0, x1, y1, G_BASE)
        cv.px(pts[-1][0] + 0.5, pts[-1][1] + 0.5, G_HI)


def worm(cv: Canvas, cx: float, cy: float, s: float, face="smile") -> None:
    """The worm, draped over the mantis's brow from the left. Call before `head` so that its tail goes behind."""
    body = [(cx - 21 * s, cy - 9 * s), (cx - 20 * s, cy - 15 * s), (cx - 15 * s, cy - 19.5 * s),
            (cx - 9 * s, cy - 19 * s), (cx - 5 * s, cy - 14 * s), (cx - 4 * s, cy - 8 * s)]
    r = max(1.0, 2.7 * s)
    cv.stroke(body, r + 1.0, W_LINE)
    cv.stroke(body, r, W_BASE)
    if s >= 0.75:
        # The lit upper edge, and the rings.
        cv.stroke([(x - 0.6, y - 1.0) for x, y in body[1:5]], r - 1.7, W_LIGHT)
        for x, y in ((cx - 12.5, cy - 19.5), (cx - 7, cy - 17), (cx - 17.5, cy - 17.5)):
            for d in (-2, -1, 0, 1, 2):
                if cv.get(int(x), int(y + d)) in (W_BASE, W_LIGHT):
                    cv.px(x, y + d, W_DARK)


def worm_face(cv: Canvas, cx: float, cy: float, s: float, face="smile") -> None:
    """The worm's head, in front of the mantis's brow."""
    hx, hy, r = cx - 21 * s, cy - 8 * s, max(1.5, 4.3 * s)
    cv.disc(hx, hy, r + 1.0, W_LINE)
    cv.disc(hx, hy, r, W_BASE)
    if s >= 0.75:
        cv.where(lambda x, y: ((x - hx) ** 2 + (y - hy) ** 2 <= r * r) & ((x - hx) + (y - hy) < -2.2), W_LIGHT)
        cv.px(hx - 2.4, hy - 2.6, W_HI)
        if face == "asleep":
            cv.rect(hx - 3, hy - 0.5, hx - 1, hy + 0.5, BLACK)
            cv.rect(hx + 1, hy - 0.5, hx + 3, hy + 0.5, BLACK)
        else:
            cv.rect(hx - 2.5, hy - 1.5, hx - 1.5, hy + 0.5, BLACK)
            cv.rect(hx + 1.5, hy - 1.5, hx + 2.5, hy + 0.5, BLACK)
        if face == "alarm":
            cv.rect(hx - 1, hy + 1.5, hx + 1, hy + 3.5, MOUTH_DARK)
        elif face == "smile":
            cv.rect(hx - 1.5, hy + 1.5, hx + 1.5, hy + 2.5, MOUTH_DARK)
            cv.px(hx - 0.5, hy + 2.5, MOUTH)
            cv.px(hx + 0.5, hy + 2.5, MOUTH)
    elif s >= 0.45:
        cv.px(hx - 1.5, hy - 0.5, BLACK)
        cv.px(hx + 0.5, hy - 0.5, BLACK)
        if face != "asleep":
            cv.px(hx - 0.5, hy + 0.9, MOUTH_DARK)


def torso(cv: Canvas, cx: float, cy: float, s: float) -> None:
    cv.rect(cx - 2.5 * s, cy + 16 * s, cx + 2.5 * s, cy + 21 * s, G_DARK)
    cv.ellipse(cx, cy + 27 * s, 6.5 * s, 9 * s, lit(cx, cy + 27 * s, 6.5 * s, 9 * s, G_DEEP, G_DARK, G_BASE))
    if s >= 0.75:
        for i in range(3):
            cv.rect(cx - 3, cy + 23 + i * 3.4, cx + 3, cy + 24 + i * 3.4, G_DEEP)


def arm(cv: Canvas, shoulder, elbow, hand, s: float) -> None:
    """One raptorial foreleg: a dark upper arm, a paler forearm with its row of spines, a claw."""
    if s < 0.75:
        cv.stroke([shoulder, elbow], 0.95, G_DARK)
        cv.stroke([elbow, hand], 1.0, G_BASE)
        cv.px(hand[0], hand[1], G_LIGHT)
        cv.px(hand[0] - 1, hand[1], G_LIGHT)
        return
    r = 1.3 * s
    cv.stroke([shoulder, elbow], r + 0.9, G_LINE)
    cv.stroke([elbow, hand], r + 1.5, G_LINE)
    cv.stroke([shoulder, elbow], r, G_DARK)
    cv.stroke([elbow, hand], r + 0.6, G_BASE)
    if s >= 0.75:
        (x0, y0), (x1, y1) = elbow, hand
        n = max(2, int(math.hypot(x1 - x0, y1 - y0) / 2.4))
        for i in range(1, n):
            t = i / n
            cv.px(x0 + (x1 - x0) * t - 0.5, y0 + (y1 - y0) * t - 1.0, G_HI)
    cv.disc(hand[0], hand[1], r + 0.5, G_LIGHT)


def console(cv: Canvas, s: float, leds=(T, T, T), size: int = 64) -> None:
    """The operator's desk across the bottom: a keyboard on top, a front panel with three lamps."""
    top, front, bottom = size - 14 * s, size - 9 * s, size
    x0, x1 = 5 * s, size - 5 * s
    cv.rect(x0 + 2 * s, top, x1 - 2 * s, front, C_LIGHT)
    cv.rect(x0, front, x1, bottom, C_BASE)
    cv.rect(x0, front, x1, front + max(1, s), C_LINE)
    if s >= 0.75:
        for row in range(2):
            y = top + 1 + row * 2
            for i, x in enumerate(np.arange(x0 + 5, x1 - 5, 3)):
                cv.rect(x, y, x + 2, y + 1, C_KEY_HI if (i + row) % 4 == 0 else C_KEY)
        cv.rect(x0 + 3, front + 3, x0 + 17, front + 6, C_DARK)
        cv.rect(x0 + 4, front + 4, x0 + 16, front + 5, CYAN)
        for i, c in enumerate(leds):
            lx = x1 - 6 - i * 5
            cv.rect(lx - 1.5, front + 2.5, lx + 1.5, front + 5.5, C_DARK)
            if c != T:
                cv.rect(lx - 1, front + 3, lx + 1, front + 5, c)
    else:
        for i, x in enumerate(np.arange(x0 + 2, x1 - 2, 2)):
            cv.px(x, top + 1, C_KEY_HI if i % 3 == 0 else C_KEY)
        for i, c in enumerate(leds):
            if c != T:
                cv.px(x1 - 2 - i * 2, front + 2, c)
        cv.rect(x0 + 1, front + 2, x0 + 1 + max(3, 7 * s), front + 3, CYAN)


def sparkle(cv: Canvas, x: float, y: float, big: bool, c: int = YELLOW) -> None:
    cv.px(x, y, WHITE)
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        cv.px(x + dx, y + dy, c)
        if big:
            cv.px(x + 2 * dx, y + 2 * dy, c)


GLYPHS = {
    "Z": ["#####", "   ##", "  ## ", " ##  ", "#####"],
    "z": ["####", "  # ", " #  ", "####"],
    "!": ["#", "#", "#", " ", "#"],
    "F": ["#####", "#    ", "#### ", "#    ", "#    ", "#    ", "#    "],
    "U": ["#   #", "#   #", "#   #", "#   #", "#   #", "#   #", " ### "],
    "K": ["#   #", "#  # ", "# #  ", "##   ", "# #  ", "#  # ", "#   #"],
    "I": ["###", " # ", " # ", " # ", " # ", " # ", "###"],
}

POSES = ("starting", "syncing", "watching", "warning", "error", "asleep", "celebrating")
MASCOT_FRAMES = {"starting": 2, "syncing": 4, "watching": 2, "warning": 2, "error": 2, "asleep": 2, "celebrating": 4}


def mascot(size: int, pose: str, frame: int = 0) -> Canvas:
    """The operator at its console: `pose` is the node's state (contracts/art.md), `frame` a step of its loop."""
    s = size / 64.0
    cv = Canvas(size, size)
    cx, cy = size / 2.0, 23 * s
    eyes, mouth, look, face, droop = "open", "smile", (0.0, 0.0), "smile", 0.0
    lift = 0.0
    leds = (46, 46, T)
    # An arm is (elbow, hand), each an offset from the point between the eyes, drawn for the left side: a negative
    # x is away from the body. The right arm is the same numbers mirrored.
    rest = ((-13, 25), (-7, 31))
    left, right = rest, rest

    if pose == "starting":
        leds = (T, T, T) if frame == 0 else (AMBER, T, T)
        look = (0.8, 0.9)
        right = ((-12, 23), (-19, 30))
        mouth = "small"
    elif pose == "syncing":
        look = (0.0, 0.9)
        up, down = ((-13, 24), (-7, 28)), ((-13, 26), (-6, 32))
        left, right = ((up, down), (rest, rest), (down, up), (rest, rest))[frame]
        leds = ((46, T, 46), (T, 46, 46), (46, 46, T), (46, T, T))[frame]
    elif pose == "watching":
        eyes = "closed" if frame == 1 else "open"
    elif pose == "warning":
        look, mouth = (0.9, -0.9), "small"
        right = ((-14, 22), (-10, 13))
        leds = (AMBER, AMBER, T) if frame == 0 else (AMBER, T, T)
    elif pose == "error":
        lift, mouth, face = 3.0, "open", "alarm"
        left = right = ((-17, 14), (-22, 3))
        leds = (RED, RED, RED) if frame == 0 else (RED, T, RED)
    elif pose == "asleep":
        eyes, mouth, face, droop, lift = "closed", "small", "asleep", 1.0, -3.0
        leds = (T, T, 46)
    elif pose == "celebrating":
        mouth = "grin"
        hi, lo = ((-17, 13), (-24, 1)), ((-17, 15), (-23, 7))
        left = hi if frame % 2 == 0 else lo
        right = lo if frame % 2 == 0 else hi
        leds = ((46, AMBER, CYAN), (CYAN, 46, AMBER), (AMBER, CYAN, 46), (46, 46, 46))[frame]

    cy -= lift * s
    torso(cv, cx, cy, s)
    antennae(cv, cx, cy, s, droop)
    worm(cv, cx, cy, s, face)
    head(cv, cx, cy, s, eyes, mouth, look)
    worm_face(cv, cx, cy, s, face)
    cv.outline(G_LINE, only=(G_DEEP, G_DARK, G_BASE, G_LIGHT, G_HI))
    console(cv, s, leds, size)
    sh = cy + 19 * s
    for side, (elbow, hand) in ((-1, left), (1, right)):
        arm(cv, (cx + side * 5 * s, sh), (cx - side * elbow[0] * s, cy + elbow[1] * s),
            (cx - side * hand[0] * s, cy + hand[1] * s), s)

    if pose == "warning":
        _warning_sign(cv, size - 13 * s, 2 * s, s, bright=frame == 0)
    elif pose == "error":
        _alarm_lamp(cv, size - 11 * s, 3 * s, s, lit_up=frame == 0)
    elif pose == "asleep":
        if s >= 0.75:
            cv.bitmap(GLYPHS["Z"], int(size - 15 - frame), int(3 - frame + 2), {"#": CYAN})
            cv.bitmap(GLYPHS["z"], int(size - 7 + frame), int(11 - frame), {"#": WHITE})
        else:
            cv.bitmap(GLYPHS["z"], int(size - 5 * max(1, s * 2)), 1, {"#": CYAN})
    elif pose == "celebrating":
        spots = [(6, 30), (57, 26), (50, 4), (12, 44), (60, 44), (30, 2)]
        for i, (x, y) in enumerate(spots):
            if (i + frame) % 2 == 0:
                sparkle(cv, x * s, y * s, big=s >= 0.75 and (i + frame) % 4 == 0, c=(YELLOW, CYAN, 201)[i % 3])
    return cv


def _warning_sign(cv: Canvas, x: float, y: float, s: float, bright: bool) -> None:
    hgt = max(5, round(11 * s))
    c = AMBER if bright else 172
    for row in range(hgt):
        half = (row + 1) * 0.55
        cv.rect(x + hgt * 0.55 - half, y + row, x + hgt * 0.55 + half, y + row + 1, c)
    mid = x + hgt * 0.55
    if s >= 0.75:
        cv.rect(mid - 1, y + 3, mid + 1, y + 7, BLACK)
        cv.rect(mid - 1, y + 8, mid + 1, y + 10, BLACK)
    else:
        cv.rect(mid - 0.5, y + 1.5, mid + 0.5, y + hgt - 2, BLACK)


def _alarm_lamp(cv: Canvas, x: float, y: float, s: float, lit_up: bool) -> None:
    r = max(2.0, 4.0 * s)
    cx, cy = x + r, y + r + 1 * s
    cv.where(lambda px, py: ((px - cx) ** 2 + (py - cy) ** 2 <= r * r) & (py <= cy + 0.5), RED if lit_up else 124)
    if lit_up and s >= 0.75:
        cv.px(cx - 2, cy - 2.5, RED_HI)
        cv.px(cx - 1, cy - 3.5, WHITE)
    cv.rect(cx - r - 1, cy + 0.5, cx + r + 1, cy + 0.5 + max(1, 2 * s), C_KEY)
    if lit_up:
        rays = [(-1.7, -0.6), (-1.2, -1.5), (0, -1.9), (1.2, -1.5), (1.7, -0.6)]
        for dx, dy in rays:
            cv.px(cx + dx * r, cy + dy * r, YELLOW)
            if s >= 0.75:
                cv.px(cx + dx * (r + 1.2), cy + dy * (r + 1.2), YELLOW)


# ── the 16-pixel mascot, drawn by hand ───────────────────────────────────────────────────────────────────────────

LEGEND = {
    "o": G_LINE, "d": G_DARK, "g": G_BASE, "l": G_LIGHT, "h": G_HI, "K": EYE, "W": WHITE, "R": MOUTH, "m": MOUTH_DARK,
    "q": W_LINE, "p": W_BASE, "P": W_LIGHT, "c": C_LIGHT, "k": C_KEY, "b": C_BASE, "n": C_LINE, "C": CYAN,
    "A": AMBER, "X": RED, "x": 124, "Y": YELLOW, "Z": CYAN, "M": 201,
}

TINY = {
    "watching": [
        "...qqq....h.....",
        "..qpPpq..d......",
        ".qpq.qpdd.......",
        ".pKpdgllgggd....",
        ".qpdKWKglKWKd...",
        "...dKKKglKKKd...",
        "...dKKKggKKKd...",
        "....ddggggdd....",
        ".....dgRRgd.....",
        "......dggd......",
        "....g..dd..g....",
        "...lg.dggd.gl...",
        "..kckckckckck...",
        ".nnnnnnnnnnnnnn.",
        ".bCCCbbbbb1b2b3.",
        ".bbbbbbbbbbbbbb.",
    ],
    "asleep": [
        "...qqq.......ZZZ",
        "..qpPpq.......Z.",
        ".qpq.qp.dd...ZZZ",
        ".pmpdgllgggd....",
        ".qpdgggglgggd...",
        "...dgggglgggd...",
        "...doooggoood...",
        "....ddggggdd....",
        ".....dgmmgd.....",
        "......dggd......",
        "....g..dd..g....",
        "...lg.dggd.gl...",
        "..kckckckckck...",
        ".nnnnnnnnnnnnnn.",
        ".bCCCbbbbb1b2b3.",
        ".bbbbbbbbbbbbbb.",
    ],
}


def _tiny(pose: str) -> list[str]:
    """The 16-pixel drawing of `pose`: the hand-drawn base with that pose's eyes, mouth, arms and prop."""
    rows = [list(r) for r in TINY["asleep" if pose == "asleep" else "watching"]]

    def put(y: int, x: int, text: str) -> None:
        for i, ch in enumerate(text):
            rows[y][x + i] = ch

    if pose == "starting":
        put(8, 7, "mm")
        put(10, 11, ".")
        put(11, 11, ".gl")
        put(10, 12, "g")
    elif pose == "syncing":
        put(10, 4, ".")
        put(11, 3, ".g")
        put(12, 3, "l")
    elif pose == "warning":
        put(0, 13, ".A.")
        put(1, 13, ".A.")
        put(2, 12, "AAAA")
        put(3, 12, "AAAA")
        put(8, 7, "mm")
        put(10, 11, ".")
        put(11, 11, "..")
        put(8, 11, "l")
        put(9, 11, "g")
        put(10, 10, "g")
    elif pose == "error":
        put(0, 12, "Y.Y")
        put(1, 12, ".XX.")
        put(2, 12, "XXXX")
        put(3, 12, "kkkk")
        put(8, 7, "RR")
        put(9, 7, "RR")
        put(10, 4, ".")
        put(11, 3, "..")
        put(10, 11, ".")
        put(11, 11, "..")
        put(7, 2, "l")
        put(8, 3, "g")
        put(9, 4, "g")
        put(7, 13, "l")
        put(8, 12, "g")
        put(9, 11, "g")
    elif pose == "celebrating":
        put(8, 6, "RRRR")
        put(10, 4, ".")
        put(11, 3, "..")
        put(10, 11, ".")
        put(11, 11, "..")
        put(6, 1, "l")
        put(7, 2, "g")
        put(8, 3, "g")
        put(9, 4, "g")
        put(6, 14, "l")
        put(7, 13, "g")
        put(8, 12, "g")
        put(9, 11, "g")
        put(0, 13, ".Y.")
        put(1, 13, "YWY")
        put(2, 13, ".Y.")
        put(9, 0, "Z")
        put(4, 15, "M")
    return ["".join(r) for r in rows]


TINY_LEDS = {
    "starting": (T, T, T), "syncing": (46, 46, T), "watching": (46, 46, T), "warning": (AMBER, AMBER, T),
    "error": (RED, RED, RED), "asleep": (46, T, T), "celebrating": (46, AMBER, CYAN),
}


def mascot16(pose: str) -> Canvas:
    cv = Canvas(16, 16)
    rows = _tiny(pose)
    assert all(len(r) == 16 for r in rows) and len(rows) == 16, pose
    leds = dict(zip("123", TINY_LEDS[pose]))
    cv.bitmap(rows, 0, 0, {**LEGEND, **{k: v for k, v in leds.items() if v != T}, **{k: C_BASE for k, v in leds.items() if v == T}})
    return cv


# ── the badge ────────────────────────────────────────────────────────────────────────────────────────────────────

WORDMARK = {
    "F": ["######", "######", "##    ", "##### ", "##### ", "##    ", "##    ", "##    "],
    "U": ["##  ##", "##  ##", "##  ##", "##  ##", "##  ##", "##  ##", "######", " #### "],
    "K": ["##  ##", "## ## ", "####  ", "###   ", "####  ", "## ## ", "##  ##", "##  ##"],
    "I": ["####", " ## ", " ## ", " ## ", " ## ", " ## ", " ## ", "####"],
}


TINY_BADGE = [
    ".......WW.......",
    ".....WWabWW.....",
    "...WWqqqababWW..",
    ".WWqpPpqbababWW.",
    ".WqpKpdgllgdbaW.",
    ".WbqpdWKglWKdbW.",
    ".WabadKKglKKdbW.",
    ".WbabdKKggKKdaW.",
    ".WababdggggdbaW.",
    ".WbabadgRRgdabW.",
    ".WabababddababW.",
    ".WbabababababaW.",
    ".WWabababababWW.",
    "...WWbababaWW...",
    ".....WWabWW.....",
    ".......WW.......",
]


def _hexagon(size: int):
    """Inside-test of a hexagon standing on a point, as tall as the canvas."""
    c, r = size / 2.0, size / 2.0

    def inside(x, y, shrink=0.0):
        rr = r - shrink
        return (np.abs(x - c) <= rr * 0.866) & (np.abs(y - c) <= rr - np.abs(x - c) / 1.732)

    return inside


def badge(size: int) -> Canvas:
    """The hexagonal badge: the mantis and its worm on a dithered green field; at 64 pixels, the wordmark."""
    cv = Canvas(size, size)
    inside = _hexagon(size)
    edge = 2.4 if size >= 64 else (1.3 if size >= 32 else 1.0)
    cv.where(lambda x, y: inside(x, y), HEX_LINE)
    cv.where(lambda x, y: inside(x, y, edge), BLACK)
    cv.where(lambda x, y: inside(x, y, edge + (1.2 if size >= 32 else 0.0)),
             lambda x, y: np.where((np.floor(x) + np.floor(y)) % 2 == 0, HEX_A, HEX_B).astype(np.uint8))

    bust = Canvas(size, size)
    if size >= 32:
        s = size / 64.0 * (0.80 if size >= 64 else 1.0)
        cx, cy = size / 2.0 + 1 * s, size * (0.40 if size >= 64 else 0.50)
        if size >= 64:
            torso(bust, cx, cy - 2, s)
            for side in (-1, 1):
                arm(bust, (cx + side * 5 * s, cy + 17 * s), (cx + side * 15 * s, cy + 22 * s),
                    (cx + side * 13 * s, cy + 11 * s), 1.0)
        antennae(bust, cx, cy, s)
        worm(bust, cx, cy, s)
        head(bust, cx, cy, s)
        worm_face(bust, cx, cy, s)
        bust.outline(BLACK)
        cv.blit(bust, 0, 0)
        if size >= 64:
            _wordmark(cv, "FUKUII", int(size / 2), int(cy + 19))
    else:
        assert all(len(r) == 16 for r in TINY_BADGE) and len(TINY_BADGE) == 16
        cv = Canvas(16, 16)
        cv.bitmap(TINY_BADGE, 0, 0, {**LEGEND, "a": HEX_A, "b": HEX_B})
    return cv


def _wordmark(cv: Canvas, text: str, centre_x: int, y: int) -> None:
    width = sum(len(WORDMARK[ch][0]) for ch in text) + len(text) - 1
    word = Canvas(width + 2, 10)
    x = 1
    for ch in text:
        word.bitmap(WORDMARK[ch], x, 1, {"#": WHITE})
        x += len(WORDMARK[ch][0]) + 1
    word.outline(BLACK)
    cv.blit(word, centre_x - (width + 2) // 2, y)


# ── the network icons ────────────────────────────────────────────────────────────────────────────────────────────

ETH_LIGHT, ETH_MID, ETH_DARK = 146, 103, 60
ETC_LIGHT, ETC_MID, ETC_DARK = 77, 28, 22


def network_icon(family: str, size: int) -> Canvas:
    """The chain family's mark, so that the network is recognised at a glance: "eth" or "etc".

    Both are the familiar octahedron seen edge-on, lit from the left. Ethereum's is two pieces, a kite over a
    chevron; Ethereum Classic's is three, with the middle of the solid floating free.
    """
    cv = Canvas(size, size)
    k = size / 32.0

    def t(a, b, c, colour):
        cv.tri(*[(x * k, y * k) for x, y in (a, b, c)], colour)

    m = 16.0
    if family == "eth":
        top, left, right, heart, low = (m, 0.5), (6.0, 16.2), (26.0, 16.2), (m, 12.2), (m, 21.8)
        t(top, left, heart, ETH_LIGHT)
        t(top, heart, right, ETH_MID)
        t(left, heart, low, ETH_MID)
        t(heart, right, low, ETH_DARK)
        t((6.0, 18.2), (m, 23.8), (m, 31.5), ETH_LIGHT)
        t((26.0, 18.2), (m, 23.8), (m, 31.5), ETH_MID)
    elif family == "etc":
        top, notch = (m, 0.5), (m, 9.6)
        t(top, (6.5, 13.6), notch, ETC_LIGHT)
        t(top, notch, (25.5, 13.6), ETC_MID)
        west, north, east, south, heart = (7.0, 16.0), (m, 11.6), (25.0, 16.0), (m, 20.6), (m, 16.6)
        t(west, north, heart, ETC_LIGHT)
        t(north, east, heart, ETC_MID)
        t(west, heart, south, ETC_MID)
        t(heart, east, south, ETC_DARK)
        t((6.5, 18.4), (m, 22.6), (m, 31.5), ETC_LIGHT)
        t((25.5, 18.4), (m, 22.6), (m, 31.5), ETC_MID)
    else:
        raise ValueError(family)
    return cv


# ── the splash's backdrop ────────────────────────────────────────────────────────────────────────────────────────


def splash_scene(width: int, height: int) -> Canvas:
    """Stars over the limb of a planet. Decoration: it never changes, and shows nothing about the node."""
    cv = Canvas(width, height)
    k = width / 160.0
    rng = np.random.default_rng(29)
    for _ in range(int(150 * k * k) + 30):
        x, y = rng.integers(0, width), rng.integers(0, int(height * 0.8))
        cv.px(x, y, int(rng.choice([WHITE, 250, 244, 240, 81, 229, 240, 244])))
    for x, y, c in ((0.08, 0.14, WHITE), (0.90, 0.10, 229), (0.70, 0.36, 81), (0.21, 0.52, WHITE), (0.52, 0.07, 219)):
        sparkle(cv, int(x * width), int(y * height), big=k >= 1.0, c=c)

    pcx, pcy, r = width * 0.50, height * 2.05, height * 1.42
    ys, xs = np.mgrid[0:height, 0:width]
    x, y = xs + 0.5, ys + 0.5
    d = np.hypot(x - pcx, y - pcy)
    land = (np.sin(x / (9.0 * k) + 1.3) + np.sin(y / (5.0 * k) + x / (17.0 * k)) + np.sin((x - y) / (6.5 * k) + 4.0)) > 0.9
    depth = (r - d) / (height * 0.34)  # 0 at the limb, growing towards the middle of the disc
    dither = (np.floor(x) + np.floor(y)) % 2 == 0
    sea = np.select([depth < 0.25, depth < 0.55], [np.where(dither, 18, 19), np.where(dither, 19, 25)], 25)
    ground = np.select([depth < 0.25, depth < 0.55], [np.where(dither, 22, 28), np.where(dither, 28, 34)], 34)
    planet = d <= r
    cv.a[planet] = np.where(land, ground, sea).astype(np.uint8)[planet]
    cv.a[(d <= r) & (d > r - max(1.0, 1.2 * k))] = 44
    cv.a[(d <= r - max(1.0, 1.2 * k)) & (d > r - max(2.0, 2.6 * k)) & dither] = 37
    return cv


# ── sheets ───────────────────────────────────────────────────────────────────────────────────────────────────────

SMALL_FRAMES = {"starting": 1, "syncing": 2, "watching": 2, "warning": 1, "error": 1, "asleep": 1, "celebrating": 2}
# Which frames of the large mascot the small one keeps.
SMALL_PICK = {"syncing": (0, 2), "watching": (0, 1), "celebrating": (0, 1)}

# How long each frame of a pose stays up, in frames of the TUI's clock (four a second): (frame, ticks).
TIMELINES = {
    "starting": [(0, 2), (1, 2)],
    "syncing": [(0, 1), (1, 1), (2, 1), (3, 1)],
    "watching": [(0, 15), (1, 1)],
    "warning": [(0, 3), (1, 1)],
    "error": [(0, 2), (1, 2)],
    "asleep": [(0, 4), (1, 4)],
    "celebrating": [(0, 1), (1, 1), (2, 1), (3, 1)],
}
SMALL_TIMELINES = {**{p: [(0, 1)] for p in POSES}, "syncing": [(0, 2), (1, 2)], "watching": [(0, 15), (1, 1)],
                   "celebrating": [(0, 1), (1, 1)]}

HI_SCALE = 4  # one art pixel of a `hi` sheet is 4 × 4 sheet pixels: an 8 × 16 cell is 2 × 4 art pixels


def enlarge(cv: Canvas, k: int) -> np.ndarray:
    return np.kron(cv.a, np.ones((k, k), dtype=np.uint8))


def grid(frames: list[list[np.ndarray]]) -> np.ndarray:
    """Poses down, frames across; a pose with fewer frames leaves the rest of its row transparent."""
    fh, fw = frames[0][0].shape
    cols = max(len(row) for row in frames)
    out = np.zeros((fh * len(frames), fw * cols), dtype=np.uint8)
    for r, row in enumerate(frames):
        for c, f in enumerate(row):
            out[r * fh : (r + 1) * fh, c * fw : (c + 1) * fw] = f
    return out


def sheets() -> dict[str, np.ndarray]:
    """Every sheet, by file name."""
    big = [[enlarge(mascot(64, p, f), HI_SCALE) for f in range(MASCOT_FRAMES[p])] for p in POSES]
    small = [[enlarge(mascot(32, p, f), HI_SCALE) for f in SMALL_PICK.get(p, (0,))] for p in POSES]
    return {
        "mascot.hi.png": grid(big),
        "mascot.lo.png": grid([[mascot(32, p, 0).a] for p in POSES]),
        "mascot-small.hi.png": grid(small),
        "mascot-small.lo.png": grid([[mascot16(p).a] for p in POSES]),
        "badge.hi.png": enlarge(badge(32), HI_SCALE),
        "badge.lo.png": badge(16).a,
        "badge-large.hi.png": enlarge(badge(64), HI_SCALE),
        "badge-large.lo.png": badge(32).a,
        "network-etc.hi.png": enlarge(network_icon("etc", 32), HI_SCALE),
        "network-etc.lo.png": network_icon("etc", 16).a,
        "network-eth.hi.png": enlarge(network_icon("eth", 32), HI_SCALE),
        "network-eth.lo.png": network_icon("eth", 16).a,
        "splash-scene.hi.png": enlarge(splash_scene(160, 96), HI_SCALE),
        "splash-scene.lo.png": splash_scene(80, 48).a,
    }


def manifest() -> str:
    def timeline(t):
        return "[" + ", ".join(f"[{f}, {n}]" for f, n in t) + "]"

    def poses(frames, timelines):
        rows = [f"      {{ name = {p}, frames = {frames[p]}, timeline = {timeline(timelines[p])} }}" for p in POSES]
        return "[\n" + "\n".join(rows) + "\n    ]"

    return f"""# Written by scripts/tui/make_art.py. Do not edit: change the script and run it again.
# The format is specs/029-tui-command-center/contracts/art.md.
art {{
  mascot {{
    cols = 32, rows = 16
    poses = {poses(MASCOT_FRAMES, TIMELINES)}
  }}
  mascot-small {{
    cols = 16, rows = 8
    poses = {poses(SMALL_FRAMES, SMALL_TIMELINES)}
  }}
  badge        {{ cols = 16, rows = 8 }}
  badge-large  {{ cols = 32, rows = 16 }}
  network-etc  {{ cols = 16, rows = 8 }}
  network-eth  {{ cols = 16, rows = 8 }}
  splash-scene {{ cols = 80, rows = 24 }}
}}
"""


def pixel_hashes() -> str:
    """One line a sheet: the SHA-256 of its width, its height (four bytes each, big-endian) and its palette indices."""
    import hashlib

    lines = []
    for name, pixels in sorted(sheets().items()):
        h, w = pixels.shape
        digest = hashlib.sha256(w.to_bytes(4, "big") + h.to_bytes(4, "big") + pixels.tobytes()).hexdigest()
        lines.append(f"{digest}  {name}")
    return "\n".join(lines) + "\n"


def palette_bytes() -> bytes:
    flat = bytearray(768)
    for i in range(16, 256):
        flat[i * 3 : i * 3 + 3] = bytes(xterm_rgb(i))
    return bytes(flat)


def save(pixels: np.ndarray, path: Path) -> None:
    im = Image.fromarray(pixels, "P")
    im.putpalette(palette_bytes())
    im.save(path, format="PNG", transparency=0, optimize=True)


def load(path: Path) -> np.ndarray:
    """A committed sheet as palette indices; anything that is not one of the 240 colours becomes 255 + mismatch."""
    rgba = np.asarray(Image.open(path).convert("RGBA"))
    lookup = {xterm_rgb(i): i for i in range(16, 256)}
    out = np.zeros(rgba.shape[:2], dtype=np.uint8)
    for (r, g, b), i in lookup.items():
        out[(rgba[..., 0] == r) & (rgba[..., 1] == g) & (rgba[..., 2] == b) & (rgba[..., 3] == 255)] = i
    return out


def preview(out_dir: Path) -> None:
    """Enlarged contact sheets on black, for people. Not read by the node."""
    out_dir.mkdir(parents=True, exist_ok=True)

    def rgb(a: np.ndarray, k: int) -> Image.Image:
        img = np.zeros(a.shape + (3,), dtype=np.uint8)
        for i in np.unique(a):
            if i != T:
                img[a == i] = xterm_rgb(int(i))
        return Image.fromarray(np.kron(img, np.ones((k, k, 1), dtype=np.uint8)), "RGB")

    def contact(rows: list[list[np.ndarray]], k: int, gap: int = 6) -> Image.Image:
        tiles = [[rgb(a, k) for a in row] for row in rows]
        width = max(sum(t.width + gap for t in row) for row in tiles) + gap
        height = sum(max(t.height for t in row) + gap for row in tiles) + gap
        sheet = Image.new("RGB", (width, height), (38, 38, 58))
        y = gap
        for row in tiles:
            x = gap
            for t in row:
                sheet.paste(t, (x, y))
                x += t.width + gap
            y += max(t.height for t in row) + gap
        return sheet

    contact([[mascot(64, p, f).a for f in range(MASCOT_FRAMES[p])] for p in POSES], 4).save(out_dir / "mascot.png")
    contact([[mascot(32, p, 0).a for p in POSES], [mascot16(p).a for p in POSES] + [badge(16).a]], 8).save(
        out_dir / "block-mode.png")
    contact([[badge(64).a, badge(32).a], [splash_scene(160, 96).a]], 6).save(out_dir / "badge-and-splash.png")
    contact([[network_icon("etc", 32).a, network_icon("eth", 32).a,
              np.kron(network_icon("etc", 16).a, np.ones((2, 2), dtype=np.uint8)),
              np.kron(network_icon("eth", 16).a, np.ones((2, 2), dtype=np.uint8))]], 8).save(out_dir / "network-icons.png")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--check", action="store_true", help="exit 1 if the committed sheets differ from the drawings")
    ap.add_argument("--out", type=Path, default=ART_DIR, help="where the sheets go (default: the node's resources)")
    ap.add_argument("--preview", type=Path, help="also write enlarged contact sheets to this directory")
    args = ap.parse_args()

    stale = []
    for name, pixels in sheets().items():
        path = args.out / name
        if args.check:
            if not path.exists() or load(path).shape != pixels.shape or not np.array_equal(load(path), pixels):
                stale.append(name)
        else:
            args.out.mkdir(parents=True, exist_ok=True)
            save(pixels, path)
    conf = args.out / "art.conf"
    hashes = PIXEL_HASHES if args.out == ART_DIR else args.out / "art-pixels.sha256"
    if args.check:
        if not conf.exists() or conf.read_text() != manifest():
            stale.append("art.conf")
        if not hashes.exists() or hashes.read_text() != pixel_hashes():
            stale.append(hashes.name)
    else:
        conf.write_text(manifest())
        hashes.parent.mkdir(parents=True, exist_ok=True)
        hashes.write_text(pixel_hashes())
    if args.preview:
        preview(args.preview)
    if stale:
        print("stale, run scripts/tui/make_art.py: " + ", ".join(stale), file=sys.stderr)
        return 1
    print(f"{'checked' if args.check else 'wrote'} {len(sheets()) + 2} files in {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
