"""Draws the block-mode (`lo`) art of the TUI command center: pixel art in the 240 xterm colours (contracts/art.md).

The same cast as the pixel-mode art (hi_art.py), drawn close up for a grid of half-cells: the operator's head in its
headphones, the worm in sunglasses beside it, Earth in the window, the console below. Every pixel is placed by the
drawing code here; nothing is shrunk from the smooth art, which turns to mush at this size. Needs NumPy.
"""

from __future__ import annotations

import math

import numpy as np

CUBE = (0, 95, 135, 175, 215, 255)
T = 0  # transparent


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
INK = 233  # the outline
WHITE = 231
SPACE, SPACE_DEEP, NEBULA, STAR, STAR_DIM = 17, 16, 54, 231, 147
WALL, WALL_DARK, NEON = 55, 54, 51
SEA, SEA_LIGHT, LAND, LAND_LIGHT, CLOUD = 26, 33, 70, 113, 254
BUG_DEEP, BUG_DARK, BUG, BUG_LIGHT, BUG_HI = 22, 70, 112, 155, 193
BELLY, BELLY_LIGHT = 149, 193
EYE, EYE_MID, EYE_GLOW = 16, 19, 63
PHONES, PHONES_LIGHT, RING = 236, 240, 118
PINK_DEEP, PINK_DARK, PINK, PINK_LIGHT = 125, 168, 211, 218
SHADES, MOUTH, TONGUE = 16, 52, 211
CONSOLE_TOP, CONSOLE, CONSOLE_DARK, SCREEN = 141, 55, 54, 23
GREEN, AMBER, RED, RED_DARK, CYAN, YELLOW, MAGENTA = 46, 214, 196, 88, 51, 227, 207
LED_OFF = 60


class Canvas:
    """A grid of palette indices with the few drawing operations pixel art needs. Nothing is anti-aliased."""

    def __init__(self, width: int, height: int):
        self.w, self.h = width, height
        self.a = np.zeros((height, width), dtype=np.uint8)
        ys, xs = np.mgrid[0:height, 0:width]
        self.x, self.y = xs + 0.5, ys + 0.5

    def px(self, x: float, y: float, c: int) -> None:
        xi, yi = int(math.floor(x)), int(math.floor(y))
        if 0 <= xi < self.w and 0 <= yi < self.h:
            self.a[yi, xi] = c

    def paint(self, mask: np.ndarray, colour) -> None:
        if callable(colour):
            colour = colour(self.x, self.y)
        if np.ndim(colour):
            self.a[mask] = np.asarray(colour, dtype=np.uint8)[mask]
        else:
            self.a[mask] = colour

    def ellipse(self, cx, cy, rx, ry) -> np.ndarray:
        return ((self.x - cx) / rx) ** 2 + ((self.y - cy) / ry) ** 2 <= 1.0

    def rect(self, x0, y0, x1, y1) -> np.ndarray:
        """The pixels with x0 <= x < x1 and y0 <= y < y1 (whole pixel coordinates)."""
        return (self.x > x0) & (self.x < x1) & (self.y > y0) & (self.y < y1)

    def stroke(self, points, r: float) -> np.ndarray:
        """A thick line through `points`: the pixels within `r` of a segment."""
        out = np.zeros((self.h, self.w), dtype=bool)
        for (x0, y0), (x1, y1) in zip(points, points[1:]):
            dx, dy = x1 - x0, y1 - y0
            t = np.clip(((self.x - x0) * dx + (self.y - y0) * dy) / max(dx * dx + dy * dy, 1e-9), 0, 1)
            out |= np.hypot(self.x - x0 - t * dx, self.y - y0 - t * dy) <= r
        return out

    def tri(self, a, b, c) -> np.ndarray:
        (ax, ay), (bx, by), (cx, cy) = a, b, c
        x, y = self.x, self.y
        d1 = (x - bx) * (ay - by) - (ax - bx) * (y - by)
        d2 = (x - cx) * (by - cy) - (bx - cx) * (y - cy)
        d3 = (x - ax) * (cy - ay) - (cx - ax) * (y - ay)
        return ~(((d1 < 0) | (d2 < 0) | (d3 < 0)) & ((d1 > 0) | (d2 > 0) | (d3 > 0)))

    def edge(self, mask: np.ndarray) -> np.ndarray:
        """The pixels outside `mask` that touch it by an edge: where its outline goes."""
        near = np.zeros_like(mask)
        near[1:, :] |= mask[:-1, :]
        near[:-1, :] |= mask[1:, :]
        near[:, 1:] |= mask[:, :-1]
        near[:, :-1] |= mask[:, 1:]
        return near & ~mask

    def part(self, mask: np.ndarray, colour, ink: bool = True) -> None:
        if ink:
            self.paint(self.edge(mask), INK)
        self.paint(mask, colour)

    def bitmap(self, rows: list[str], x: int, y: int, colours: dict[str, int]) -> None:
        """Draws rows of characters, one a pixel; a character missing from `colours` leaves the pixel alone."""
        for dy, row in enumerate(rows):
            for dx, ch in enumerate(row):
                if colours.get(ch) is not None:
                    self.px(x + dx + 0.5, y + dy + 0.5, colours[ch])


def bands(cx, cy, rx, ry, deep, dark, base, light, hi=None):
    """A colour function: cel bands of light from the upper left across an ellipse."""

    def colour(x, y):
        t = -(x - cx) / rx * 0.6 - (y - cy) / ry * 0.8
        out = np.full(x.shape, base, dtype=np.uint8)
        out[t > 0.45] = light
        if hi is not None:
            out[t > 0.95] = hi
        out[t < -0.35] = dark
        out[t < -0.85] = deep
        return out

    return colour


# ── the backdrop ─────────────────────────────────────────────────────────────────────────────────────────────────────

def space(cv: Canvas, mask: np.ndarray, seed: int, stars: int) -> None:
    cv.paint(mask, SPACE)
    cloud = np.sin(cv.x / 4.5 + 1.0) * np.sin(cv.y / 3.5 + 0.5) + 0.5 * np.sin((cv.x - cv.y) / 3.0) > 0.85
    cv.paint(mask & cloud & ((np.floor(cv.x) + np.floor(cv.y)) % 2 == 0), NEBULA)
    rng = np.random.default_rng(seed)
    for _ in range(stars):
        x, y = int(rng.integers(0, cv.w)), int(rng.integers(0, cv.h))
        if mask[y, x]:
            cv.a[y, x] = STAR if rng.random() < 0.45 else STAR_DIM


def earth(cv: Canvas, mask: np.ndarray, cx, cy, r) -> None:
    d = np.hypot(cv.x - cx, cv.y - cy)
    disc = mask & (d <= r)
    lit = (cx - cv.x) * 0.5 + (cy - cv.y)
    cv.paint(disc, np.where(lit > r * 0.55, SEA_LIGHT, SEA))
    land = np.sin(cv.x / 3.1 + 0.6) + np.sin(cv.y / 2.3 + cv.x / 6.0) + np.sin((cv.x - cv.y) / 2.7 + 4.0) > 0.85
    cv.paint(disc & land, np.where(lit > r * 0.55, LAND_LIGHT, LAND))
    clouds = np.sin(cv.x / 2.0 + cv.y / 5.0) + np.sin(cv.y / 1.7 - cv.x / 9.0) > 1.35
    cv.paint(disc & clouds, CLOUD)
    cv.paint(mask & (d <= r) & (d > r - 1), 45)


# ── the mascot, close up ─────────────────────────────────────────────────────────────────────────────────────────────

# For each pose: the operator's eyes and mouth, the worm's face, and the three lamps on the console.
FACES = {
    "starting": ("open", "smile", "o", (GREEN, None, None)),
    "syncing": ("open", "smile", "shades", (GREEN, GREEN, None)),
    "watching": ("open", "smile", "shades", (GREEN, GREEN, None)),
    "warning": ("wide", "o", "o", (AMBER, AMBER, None)),
    "error": ("wide", "shout", "scared", (RED, RED, RED)),
    "asleep": ("closed", "sleep", "asleep", (None, None, GREEN)),
    "celebrating": ("happy", "grin", "party", (GREEN, AMBER, CYAN)),
}


def mascot(pose: str) -> Canvas:
    """The 32 × 32 close-up of `pose`, one frame: the block-mode mascot."""
    eyes, mouth, worm_face, leds = FACES[pose]
    cv = Canvas(32, 32)
    everything = np.ones((32, 32), dtype=bool)
    # the window and the room
    cv.paint(everything, WALL_DARK)
    window = cv.rect(0, 0, 32, 25)
    space(cv, window, seed=4, stars=26)
    earth(cv, window, 26, 34, 15)
    if pose == "error":
        cv.paint(window & ((np.floor(cv.x) + np.floor(cv.y)) % 2 == 0) & (cv.a == SPACE), RED_DARK)
    if pose == "asleep":
        cv.paint(window & (cv.a == SPACE), SPACE_DEEP)
    # the operator: antennae, body, head, headphones
    hx, hy = 17.0, 15.0
    straight = pose in ("error",)
    for side in (-1, 1):
        if straight:
            stalk = [(hx + side * 3, hy - 6), (hx + side * 5, hy - 10), (hx + side * 6.5, hy - 13)]
            tip = (hx + side * 6.5, hy - 13)
        elif pose == "asleep":
            stalk = [(hx + side * 3, hy - 6), (hx + side * 6, hy - 9), (hx + side * 9, hy - 8.5)]
            tip = (hx + side * 10, hy - 8)
        else:
            stalk = [(hx + side * 3, hy - 6), (hx + side * 5, hy - 10), (hx + side * 9, hy - 12.5)]
            tip = (hx + side * 10, hy - 12.5)
        cv.part(cv.stroke(stalk, 0.55), BUG_DARK)
        cv.part(cv.ellipse(*tip, 1.4, 1.4), BUG)
        cv.px(tip[0] - 0.6, tip[1] - 0.6, BUG_HI)
    body = cv.ellipse(hx + 0.5, hy + 13, 7.5, 6)
    cv.part(body, bands(hx + 0.5, hy + 13, 7.5, 6, BUG_DEEP, BUG_DARK, BUG, BUG_LIGHT))
    cv.paint(cv.ellipse(hx + 1.5, hy + 13.5, 3, 4.5), BELLY)
    head = cv.ellipse(hx, hy, 9.6, 7.8)
    cv.part(head, bands(hx, hy, 9.6, 7.8, BUG_DEEP, BUG_DARK, BUG, BUG_LIGHT, BUG_HI))
    band = cv.ellipse(hx, hy + 0.5, 11.2, 9.5) & ~cv.ellipse(hx, hy + 0.5, 10.0, 8.4) & (cv.y < hy - 1)
    cv.part(band, PHONES)
    for side in (-1, 1):
        cx = hx + side * 10.0
        cup = cv.ellipse(cx, hy + 1, 2.2, 3.4)
        cv.part(cup, PHONES)
        cv.paint(cv.ellipse(cx + side * 0.2, hy + 1, 1.0, 2.0), PHONES_LIGHT if pose == "asleep" else RING)
        cv.px(cx - 1, hy - 1.5, PHONES_LIGHT)
    # the eyes
    for ex in (hx - 4.0, hx + 4.0):
        ey = hy + 0.5
        if eyes in ("closed",):
            cv.paint(cv.rect(ex - 2, ey + 1, ex + 2, ey + 2), INK)
            cv.px(ex - 2.5, ey + 0.5, INK)
            cv.px(ex + 2.5, ey + 0.5, INK)
            continue
        if eyes == "happy":
            cv.paint(cv.rect(ex - 1, ey - 2, ex + 1, ey - 1), INK)
            cv.paint(cv.rect(ex - 2, ey - 1, ex - 1, ey + 1) | cv.rect(ex + 1, ey - 1, ex + 2, ey + 1), INK)
            continue
        rx, ry = (3.0, 4.6) if eyes == "wide" else (2.7, 4.2)
        eye = cv.ellipse(ex, ey, rx, ry)
        cv.part(eye, EYE)
        cv.paint(eye & (cv.y > ey + 0.5), EYE_MID)
        cv.paint(eye & (cv.y > ey + ry - 1.2), EYE_GLOW)
        cv.paint(cv.rect(ex - 2, ey - 3, ex, ey), WHITE)
        if eyes == "wide":
            cv.px(ex - 1.5, ey - 1.5, EYE)
        cv.px(ex + 1.0, ey + 1.0, WHITE)
    # the mouth
    my = hy + 5
    if mouth == "smile":
        cv.paint(cv.rect(hx - 2, my, hx + 2, my + 1), MOUTH)
        cv.paint(cv.rect(hx - 1, my + 1, hx + 1, my + 2), TONGUE)
        cv.px(hx - 3, my - 1, INK)
        cv.px(hx + 2, my - 1, INK)
    elif mouth == "grin":
        cv.paint(cv.rect(hx - 3, my - 1, hx + 3, my), MOUTH)
        cv.paint(cv.rect(hx - 2, my, hx + 2, my + 1), MOUTH)
        cv.paint(cv.rect(hx - 1, my + 1, hx + 1, my + 2), TONGUE)
        cv.paint(cv.rect(hx - 2, my - 1, hx + 2, my - 0.5) & (cv.y < my - 0.5), WHITE)
    elif mouth == "o":
        cv.paint(cv.rect(hx - 1, my, hx + 1, my + 2), MOUTH)
    elif mouth == "shout":
        cv.paint(cv.rect(hx - 2, my - 1, hx + 2, my + 2) & ~(cv.rect(hx - 2, my - 1, hx - 1, my) | cv.rect(
            hx + 1, my - 1, hx + 2, my) | cv.rect(hx - 2, my + 1, hx - 1, my + 2) | cv.rect(hx + 1, my + 1, hx + 2,
                                                                                            my + 2)), MOUTH)
        cv.paint(cv.rect(hx - 1, my + 1, hx + 1, my + 2), TONGUE)
    elif mouth == "sleep":
        cv.paint(cv.rect(hx - 1, my, hx + 1, my + 1), INK)
    cv.px(hx - 7, hy + 3.5, 217)  # cheeks
    cv.px(hx + 6, hy + 3.5, 217)
    # the worm
    worm_mask = cv.stroke([(3.0, 26), (3.0, 21), (4.0, 18)], 1.7)
    cv.part(worm_mask, PINK_DARK)
    cv.paint(cv.stroke([(2.6, 26), (2.6, 21), (3.5, 18)], 0.7), PINK)
    for y in (20, 23):
        cv.paint(cv.rect(1, y, 5, y + 1) & worm_mask, PINK_DEEP)
    wx, wy = 3.5, 14.5 + (1.0 if worm_face == "asleep" else 0.0)
    worm_head = cv.ellipse(wx, wy, 3.2, 2.9)
    cv.part(worm_head, bands(wx, wy, 3.2, 2.9, PINK_DEEP, PINK_DARK, PINK, PINK_LIGHT))
    if worm_face in ("shades", "party", "o"):
        cv.paint(cv.rect(0, wy - 1.5, 7, wy - 0.5), SHADES)
        cv.paint(cv.rect(1, wy - 0.5, 3, wy + 0.5) | cv.rect(4, wy - 0.5, 6, wy + 0.5), SHADES)
        cv.px(1.5, wy - 1, WHITE)
        cv.px(4.5, wy - 1, WHITE)
    if worm_face == "scared":
        cv.px(2, wy - 1.5, WHITE)
        cv.px(5, wy - 1.5, WHITE)
        cv.px(2, wy - 0.5, EYE)
        cv.px(5, wy - 0.5, EYE)
        cv.paint(cv.rect(0, wy + 0.5, 7, wy + 1.5) & worm_head, SHADES)
        cv.paint(cv.rect(3, wy + 1.5, 5, wy + 3) & worm_head, MOUTH)
    elif worm_face == "asleep":
        cv.paint(cv.rect(1, wy, 3, wy + 1) | cv.rect(4, wy, 6, wy + 1), PINK_DEEP)
    elif worm_face == "o":
        cv.px(3.5, wy + 1.5, MOUTH)
    else:
        cv.paint(cv.rect(2, wy + 1, 6, wy + 2) & worm_head, MOUTH)
    if worm_face == "party":
        hat = cv.tri((wx - 2.5, wy - 2.5), (wx + 0.5, wy - 8), (wx + 2.5, wy - 2.5))
        cv.part(hat, np.where((np.floor(cv.x) + np.floor(cv.y)) % 3 == 0, CYAN, YELLOW).astype(np.uint8))
        cv.px(wx + 0.5, wy - 8.5, MAGENTA)
    # the console
    cv.paint(cv.rect(0, 25, 32, 26), CONSOLE_TOP)
    cv.paint(cv.rect(0, 26, 32, 27), NEON)
    cv.paint(cv.rect(0, 27, 32, 32), CONSOLE)
    cv.paint(cv.rect(0, 30, 32, 32), CONSOLE_DARK)
    for i, colour in enumerate(leds):
        cv.px(4 + i * 2, 28, colour if colour is not None else LED_OFF)
    on = pose not in ("asleep",)
    for x0 in (19, 25):
        cv.paint(cv.rect(x0, 27, x0 + 5, 30), INK)
        if on:
            cv.paint(cv.rect(x0 + 1, 28, x0 + 4, 29), {"error": RED, "warning": AMBER}.get(pose, SCREEN))
            cv.px(x0 + 1 + (0 if pose != "syncing" else 2), 28, {"error": RED, "warning": AMBER}.get(pose, CYAN))
    # what the pose adds
    if pose == "starting":
        cv.paint(cv.stroke([(28.5, 2.5), (28.5, 5)], 0.5), 66)
        ring = cv.ellipse(28.5, 5.5, 2.6, 2.6) & ~cv.ellipse(28.5, 5.5, 1.5, 1.5) & ~cv.rect(27, 2, 30, 4.5)
        cv.paint(ring, 66)
    elif pose == "syncing":
        cv.bitmap([".CCC", ".C.C", ".C.C", "CC.C", "CCCC", "C.CC"], 25, 1, {"C": CYAN})
        cv.bitmap(["M", "M", "MM", "MM"], 29, 6, {"M": PINK})
    elif pose == "warning":
        cv.bitmap(["..I..", ".IAI.", ".IKI.", "IAKAI", "IAAAI", "IIIII"], 26, 1, {"A": AMBER, "K": INK, "I": 130})
        cv.bitmap(["W", "C"], 25, 8, {"W": WHITE, "C": 81})
    elif pose == "error":
        cv.bitmap([".RRRR.", "RWRRWR", "RRWWRR", "RRWWRR", "RWRRWR", ".RRRR."], 25, 1, {"R": RED, "W": WHITE})
        cv.bitmap(["W", "C"], 26, 8, {"W": WHITE, "C": 81})
        cv.bitmap(["W", "C"], 7, 7, {"W": WHITE, "C": 81})
    elif pose == "asleep":
        cv.bitmap(["ZZZ", "..Z", ".Z.", "Z..", "ZZZ"], 28, 1, {"Z": 189})
        cv.bitmap(["ZZ", ".Z", "ZZ"], 25, 6, {"Z": 189})
    elif pose == "celebrating":
        rng = np.random.default_rng(5)
        for _ in range(14):
            x, y = int(rng.integers(0, 32)), int(rng.integers(0, 23))
            if cv.a[y, x] in (SPACE, NEBULA, STAR, STAR_DIM):
                cv.a[y, x] = int(rng.choice([YELLOW, CYAN, MAGENTA, GREEN, WHITE]))
        cv.bitmap([".Y.", "YWY", ".Y."], 27, 2, {"Y": YELLOW, "W": WHITE})
    return cv


# ── the small mascot, drawn pixel by pixel ───────────────────────────────────────────────────────────────────────────

SMALL_LEGEND = {
    "o": INK, "d": BUG_DARK, "g": BUG, "l": BUG_LIGHT, "h": BUG_HI, "K": EYE, "E": EYE_MID, "W": WHITE, "R": MOUTH,
    "t": TONGUE, "p": PINK, "P": PINK_LIGHT, "q": PINK_DARK, "s": SHADES, "f": PHONES, "r": RING, "c": CONSOLE_TOP,
    "n": NEON, "b": CONSOLE, ".": SPACE, ",": WALL_DARK, "*": STAR, "e": SEA, "a": LAND,
}

SMALL = [
    ".*...l......l...",
    "......d.....d...",
    ".......d...d....",
    "......fffffff...",
    ".qq..fgllllggf..",
    "qPpqogllllggggo.",
    "ssssrgWKgggWKgr.",
    "qpRqrlKKgggKKgr.",
    ".qp.rgEEgggEEdr.",
    ".qp.fgggRRRggdfe",
    ".pq...dggtggd.ea",
    "qpp....ddddd.eae",
    "cccccccccccccccc",
    "nnnnnnnnnnnnnnnn",
    "bb1b2b3bbbbbbbbb",
    "bbbbbbbbbbbbbbbb",
]


def mascot16(pose: str) -> Canvas:
    """The 16 × 16 drawing of `pose`: the hand-drawn base with that pose's eyes, mouth and prop."""
    rows = [list(r) for r in SMALL]
    assert len(rows) == 16 and all(len(r) == 16 for r in rows), "SMALL is 16 × 16"

    def put(y: int, x: int, text: str) -> None:
        for i, ch in enumerate(text):
            rows[y][x + i] = ch

    eyes, mouth, worm_face, leds = FACES[pose]
    for x in (6, 11):
        if eyes == "wide":
            put(5, x, "WK")
            put(6, x, "KK")
        elif eyes == "closed":
            put(6, x, "gg")
            put(7, x, "oo")
            put(8, x, "gg")
        elif eyes == "happy":
            put(6, x, "oo")
            put(7, x, "gg")
            put(8, x, "gg")
    if mouth == "o":
        put(9, 8, "gRg")
        put(10, 9, "R")
    elif mouth == "shout":
        put(9, 8, "RRR")
        put(10, 8, "RtR")
    elif mouth == "sleep":
        put(9, 8, "gog")
    elif mouth == "grin":
        put(9, 7, "RRRRR")
        put(10, 8, "RtR")
    if worm_face == "scared":
        put(5, 0, "qWpW")
        put(6, 0, "pKpK")
        put(7, 0, "ssss")
    elif worm_face == "asleep":
        put(6, 0, "qpqp")
        put(7, 0, "pppp")
    elif worm_face == "o":
        put(7, 0, "qpqq")
    elif worm_face == "party":
        put(2, 1, "Y")
        put(3, 0, ".CY")
    if pose == "starting":
        put(0, 13, ".D.")
        put(1, 13, "D.D")
        put(2, 13, ".D.")
    elif pose == "syncing":
        put(0, 14, "CC")
        put(1, 14, "C.")
        put(2, 13, "CC.")
    elif pose == "warning":
        put(0, 14, "A.")
        put(1, 13, "AKA")
        put(2, 13, "AAA")
    elif pose == "error":
        put(0, 13, "XWX")
        put(1, 13, "WXW")
        put(2, 13, "XWX")
    elif pose == "asleep":
        put(0, 13, "ZZZ")
        put(1, 13, ".Z.")
        put(2, 13, "ZZZ")
    elif pose == "celebrating":
        put(0, 9, "M")
        put(1, 3, "Y")
        put(2, 14, "C")
        put(0, 14, "Y")
    cv = Canvas(16, 16)
    colours = {**SMALL_LEGEND, "Y": YELLOW, "C": CYAN, "A": AMBER, "X": RED, "Z": 189, "M": MAGENTA, "D": 66,
               "1": leds[0] or LED_OFF, "2": leds[1] or LED_OFF, "3": leds[2] or LED_OFF}
    if pose == "asleep":
        colours = {**colours, ".": SPACE_DEEP, "r": PHONES_LIGHT}
    if pose == "error":
        colours = {**colours, "r": RED}
    cv.bitmap(["".join(r) for r in rows], 0, 0, colours)
    return cv


# ── the badge ────────────────────────────────────────────────────────────────────────────────────────────────────────

def hexagon(cv: Canvas, shrink: float = 0.0) -> np.ndarray:
    c, r = cv.w / 2.0, cv.h / 2.0 - shrink
    return (np.abs(cv.x - c) <= r * 0.866) & (np.abs(cv.y - c) <= r - np.abs(cv.x - c) / 1.732)


def badge(size: int) -> Canvas:
    """The hexagonal badge at 32 or 16 pixels: the operator's head over Earth."""
    cv = Canvas(size, size)
    k = size / 32.0
    cv.paint(hexagon(cv), INK)
    cv.paint(hexagon(cv, 0.8 * k + 0.2), WALL)
    inner = hexagon(cv, 1.6 * k + 0.4)
    cv.paint(hexagon(cv, 1.2 * k + 0.3) & ~inner, NEON)
    space(cv, inner, seed=7, stars=int(14 * k * k) + 2)
    earth(cv, inner, size / 2, size * 1.5, size * 0.78)
    hx, hy = size / 2.0, size * 0.52
    if size >= 32:
        for side in (-1, 1):
            cv.part(cv.stroke([(hx + side * 3, hy - 5), (hx + side * 5, hy - 9), (hx + side * 8, hy - 11)], 0.55),
                    BUG_DARK)
            cv.part(cv.ellipse(hx + side * 8.5, hy - 11, 1.4, 1.4), BUG)
        head = cv.ellipse(hx, hy, 8.6, 7.0)
        cv.part(head, bands(hx, hy, 8.6, 7.0, BUG_DEEP, BUG_DARK, BUG, BUG_LIGHT, BUG_HI))
        cv.part(cv.ellipse(hx, hy + 0.5, 10.2, 8.6) & ~cv.ellipse(hx, hy + 0.5, 9.0, 7.6) & (cv.y < hy - 1), PHONES)
        for side in (-1, 1):
            cv.part(cv.ellipse(hx + side * 9, hy + 1, 2.0, 3.0), PHONES)
            cv.paint(cv.ellipse(hx + side * 9, hy + 1, 0.9, 1.8), RING)
        for ex in (hx - 3.6, hx + 3.6):
            eye = cv.ellipse(ex, hy + 0.5, 2.5, 3.9)
            cv.part(eye, EYE)
            cv.paint(eye & (cv.y > hy + 1), EYE_MID)
            cv.paint(cv.rect(ex - 1.6, hy - 2.5, ex + 0.4, hy), WHITE)
        cv.paint(cv.rect(hx - 2, hy + 4.5, hx + 2, hy + 5.5), MOUTH)
        cv.paint(cv.rect(hx - 1, hy + 5.5, hx + 1, hy + 6.5), TONGUE)
        cv.a[~hexagon(cv)] = T
    else:
        cv.bitmap(["..l....l..",
                   "...d..d...",
                   "..ffffff..",
                   ".fglllggf.",
                   "rgWKggWKgr",
                   "rlKKggKKgr",
                   "rgEEggEEdr",
                   ".fggRRgdf.",
                   "..dgttgd..",
                   "...dddd..."], 3, 2, {**SMALL_LEGEND, ".": None})
        cv.a[~hexagon(cv)] = T
    return cv


# ── the network marks ────────────────────────────────────────────────────────────────────────────────────────────────

def network_icon(family: str, size: int = 16) -> Canvas:
    """The chain family's mark, the octahedron seen edge-on, lit from the left: Ethereum's in two pieces, Ethereum
    Classic's in three."""
    cv = Canvas(size, size)
    k = size / 32.0
    light, mid, dark = (155, 76, 28) if family == "etc" else (189, 141, 98)
    whole = np.zeros((size, size), dtype=bool)
    faces = []

    def t(a, b, c, colour):
        nonlocal whole
        m = cv.tri(*[(x * k, y * k) for x, y in (a, b, c)])
        faces.append((m, colour))
        whole |= m

    m = 16.0
    if family == "eth":
        top, left, right, heart, low = (m, 1.0), (6.0, 16.2), (26.0, 16.2), (m, 12.2), (m, 21.8)
        t(top, left, heart, light)
        t(top, heart, right, mid)
        t(left, heart, low, mid)
        t(heart, right, low, dark)
        t((6.0, 18.4), (m, 24.0), (m, 31.0), light)
        t((26.0, 18.4), (m, 24.0), (m, 31.0), mid)
    elif family == "etc":
        top, notch = (m, 1.0), (m, 9.6)
        t(top, (6.5, 13.6), notch, light)
        t(top, notch, (25.5, 13.6), mid)
        west, north, east, south, heart = (7.0, 16.0), (m, 11.6), (25.0, 16.0), (m, 20.6), (m, 16.6)
        t(west, north, heart, light)
        t(north, east, heart, mid)
        t(west, heart, south, mid)
        t(heart, east, south, dark)
        t((6.5, 18.6), (m, 22.8), (m, 31.0), light)
        t((25.5, 18.6), (m, 22.8), (m, 31.0), mid)
    else:
        raise ValueError(family)
    cv.paint(cv.edge(whole), INK)
    for mask, colour in faces:
        cv.paint(mask, colour)
    cv.px(m * k - 1, 5 * k, WHITE)
    return cv


# ── the splash's backdrop ────────────────────────────────────────────────────────────────────────────────────────────

def splash_scene(width: int = 80, height: int = 48) -> Canvas:
    """Deep space over Earth's limb, and the station far off. Decoration: it never changes."""
    cv = Canvas(width, height)
    everything = np.ones((height, width), dtype=bool)
    space(cv, everything, seed=29, stars=110)
    for x, y in ((6, 8), (40, 5), (66, 20), (22, 30), (55, 34)):
        cv.bitmap([".s.", "sWs", ".s."], x - 1, y - 1, {"s": STAR_DIM, "W": WHITE})
    earth(cv, everything, width / 2, height * 2.35, height * 1.62)
    sx, sy = 61.0, 15.0
    ring = cv.ellipse(sx, sy, 7, 2.2) & ~cv.ellipse(sx, sy, 5.6, 1.2)
    cv.part(ring, 61)
    hub = cv.rect(sx - 3, sy - 3, sx + 2, sy + 3) | cv.rect(sx - 1, sy - 5, sx, sy - 2)
    cv.part(hub, 60)
    cv.paint(cv.rect(sx - 2, sy - 2, sx + 1, sy), CYAN)
    cv.px(sx - 0.5, sy - 5.5, GREEN)
    return cv
