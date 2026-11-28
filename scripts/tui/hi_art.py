"""Draws the pixel-mode (`hi`) art of the TUI command center: smooth, full-colour cartoon art (contracts/art.md).

The scene is the operator, a green bug in headphones with its feet up on the console of a small station, and the
worm in sunglasses beside it, with Earth in the window. Everything is drawn in a 128-unit square (160 × 96 for the
splash scene) at 8 pixels a unit: soft-edged masks, shaded into cel bands from a blurred-mask height field, outlined
part by part, then resampled to the sheet's size. The art is original and drawn by this code alone.

make_art.py builds the sheets from it; lo_art.py draws the block-mode sheets, which are pixel art. Needs Pillow and
NumPy.
"""

from __future__ import annotations

import math

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

SS = 8  # pixels a drawing unit


def rgbf(h: int) -> np.ndarray:
    return np.array([(h >> 16) & 255, (h >> 8) & 255, h & 255], dtype=np.float32) / 255.0


OUTLINE = rgbf(0x150F2A)
BUG = [rgbf(0x1E6B2A), rgbf(0x3FA52C), rgbf(0x7BD934), rgbf(0xC4F57A)]
BUG_BACK = [rgbf(0x174F22), rgbf(0x2E8426), rgbf(0x5DBB2C), rgbf(0x9BE55A)]
BELLY = [rgbf(0x6DB83A), rgbf(0xA8DE5A), rgbf(0xCDF08A), rgbf(0xEDFDC0)]
PINK = [rgbf(0x9E3E6E), rgbf(0xD96A98), rgbf(0xF59BBE), rgbf(0xFFD3E3)]
CHAIR = [rgbf(0x1E1B44), rgbf(0x34306A), rgbf(0x4D4892), rgbf(0x7A74C4)]
SHOE = [rgbf(0x15151C), rgbf(0x2A2A34), rgbf(0x44444F), rgbf(0x6E6E7C)]
SOLE = [rgbf(0x55555F), rgbf(0x8A8A96), rgbf(0xB4B4C0), rgbf(0xDCDCE6)]
PHONES = [rgbf(0x1C1C24), rgbf(0x34343F), rgbf(0x50505E), rgbf(0x7E7E8E)]
MUG = [rgbf(0x2A2370), rgbf(0x3F36A8), rgbf(0x5A50D0), rgbf(0x8C84F0)]
REMOTE = [rgbf(0x1A1830), rgbf(0x2C2A4A), rgbf(0x46436E), rgbf(0x6E6AA0)]
WHITE = rgbf(0xFFFFFF)
NEON = rgbf(0x3FE6FF)
GREEN_GLOW = rgbf(0x7CFF4E)
AMBER = rgbf(0xFFB21E)
RED = rgbf(0xFF3B4E)
MOUTH = rgbf(0x5A0F25)
TONGUE = rgbf(0xFF7FA6)
DIM = rgbf(0x2C2A40)
LIGHT = np.array([-0.55, -0.70, 0.45], dtype=np.float32)
LIGHT /= np.linalg.norm(LIGHT)
RIM = np.array([0.75, 0.35, 0.15], dtype=np.float32)
RIM /= np.linalg.norm(RIM)

POSES = ("starting", "syncing", "watching", "warning", "error", "asleep", "celebrating")


def smoothstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0, 1)
    return t * t * (3 - 2 * t)


class Patch:
    """A colour image that covers only part of the canvas: rows and columns from (y0, x0)."""

    def __init__(self, y0: int, x0: int, rgb: np.ndarray):
        self.y0, self.x0, self.rgb = y0, x0, rgb

    def crop(self, sl) -> np.ndarray:
        ys, xs = sl
        out = np.zeros((ys.stop - ys.start, xs.stop - xs.start, 3), dtype=np.float32)
        h, w = self.rgb.shape[:2]
        a0, a1 = max(ys.start, self.y0), min(ys.stop, self.y0 + h)
        b0, b1 = max(xs.start, self.x0), min(xs.stop, self.x0 + w)
        if a0 < a1 and b0 < b1:
            out[a0 - ys.start : a1 - ys.start, b0 - xs.start : b1 - xs.start] = self.rgb[
                a0 - self.y0 : a1 - self.y0, b0 - self.x0 : b1 - self.x0
            ]
        return out


class Canvas:
    """An RGBA picture being drawn, in drawing units of `ss` pixels. Masks are float arrays the canvas's size."""

    def __init__(self, w: int = 128, h: int | None = None, ss: int = SS):
        h = w if h is None else h
        self.uw, self.uh, self.ss = w, h, ss
        self.W, self.H = w * ss, h * ss
        self.rgb = np.zeros((self.H, self.W, 3), dtype=np.float32)
        self.a = np.zeros((self.H, self.W), dtype=np.float32)
        xs = ((np.arange(self.W, dtype=np.float32) + 0.5) / ss)[None, :]
        ys = ((np.arange(self.H, dtype=np.float32) + 0.5) / ss)[:, None]
        self.X = np.broadcast_to(xs, (self.H, self.W))
        self.Y = np.broadcast_to(ys, (self.H, self.W))

    def copy(self) -> Canvas:
        c = Canvas.__new__(Canvas)
        c.__dict__.update(self.__dict__)
        c.rgb, c.a = self.rgb.copy(), self.a.copy()
        return c

    def zeros(self) -> np.ndarray:
        return np.zeros((self.H, self.W), dtype=np.float32)

    def bbox(self, m: np.ndarray, pad: int = 0):
        rows = np.flatnonzero(m.max(axis=1) > 1e-4)
        if rows.size == 0:
            return None
        cols = np.flatnonzero(m[rows[0] : rows[-1] + 1].max(axis=0) > 1e-4)
        return (slice(max(0, rows[0] - pad), min(self.H, rows[-1] + 1 + pad)),
                slice(max(0, cols[0] - pad), min(self.W, cols[-1] + 1 + pad)))

    # ── masks, anti-aliased by drawing at twice the size and halving ─────────────────────────────────────────────────
    def _draw(self, fn, x0, y0, x1, y1) -> np.ndarray:
        px0, py0 = max(0, int(math.floor(x0 * self.ss)) - 2), max(0, int(math.floor(y0 * self.ss)) - 2)
        px1, py1 = min(self.W, int(math.ceil(x1 * self.ss)) + 2), min(self.H, int(math.ceil(y1 * self.ss)) + 2)
        out = self.zeros()
        if px1 <= px0 or py1 <= py0:
            return out
        k = self.ss * 2
        im = Image.new("L", ((px1 - px0) * 2, (py1 - py0) * 2), 0)
        fn(ImageDraw.Draw(im), k, px0 * 2, py0 * 2)
        out[py0:py1, px0:px1] = np.asarray(im.resize((px1 - px0, py1 - py0), Image.BOX), dtype=np.float32) / 255.0
        return out

    def ellipse(self, cx, cy, rx, ry, tilt=0.0) -> np.ndarray:
        if tilt == 0.0:
            return self._draw(
                lambda d, k, ox, oy: d.ellipse([(cx - rx) * k - ox, (cy - ry) * k - oy, (cx + rx) * k - ox,
                                                (cy + ry) * k - oy], fill=255),
                cx - rx, cy - ry, cx + rx, cy + ry)
        pts = [(cx + rx * math.cos(t) * math.cos(tilt) - ry * math.sin(t) * math.sin(tilt),
                cy + rx * math.cos(t) * math.sin(tilt) + ry * math.sin(t) * math.cos(tilt))
               for t in np.linspace(0, 2 * math.pi, 96)]
        return self.poly(pts)

    def poly(self, pts) -> np.ndarray:
        xs, ys = [p[0] for p in pts], [p[1] for p in pts]
        return self._draw(lambda d, k, ox, oy: d.polygon([(x * k - ox, y * k - oy) for x, y in pts], fill=255),
                          min(xs), min(ys), max(xs), max(ys))

    def tube(self, pts, r) -> np.ndarray:
        def fn(d, k, ox, oy):
            d.line([(x * k - ox, y * k - oy) for x, y in pts], fill=255, width=max(1, int(round(2 * r * k))),
                   joint="curve")
            for x, y in (pts[0], pts[-1]):
                d.ellipse([(x - r) * k - ox, (y - r) * k - oy, (x + r) * k - ox, (y + r) * k - oy], fill=255)
        xs, ys = [p[0] for p in pts], [p[1] for p in pts]
        return self._draw(fn, min(xs) - r, min(ys) - r, max(xs) + r, max(ys) + r)

    def rrect(self, x0, y0, x1, y1, r) -> np.ndarray:
        return self._draw(lambda d, k, ox, oy: d.rounded_rectangle([x0 * k - ox, y0 * k - oy, x1 * k - ox,
                                                                    y1 * k - oy], radius=r * k, fill=255),
                          x0, y0, x1, y1)

    def blur(self, m, units) -> np.ndarray:
        sigma = units * self.ss
        sl = self.bbox(m, int(math.ceil(sigma * 3)) + 2)
        out = self.zeros()
        if sl is None:
            return out
        im = Image.fromarray((np.clip(m[sl], 0, 1) * 255).astype(np.uint8))
        out[sl] = np.asarray(im.filter(ImageFilter.GaussianBlur(sigma)), dtype=np.float32) / 255.0
        return out

    def round_off(self, m, units) -> np.ndarray:
        """Rounds a mask's corners: blur, then threshold softly."""
        return smoothstep(0.42, 0.58, self.blur(m, units))

    def grow(self, m, units) -> np.ndarray:
        return smoothstep(0.02, 0.12, self.blur(m, units * 0.55))

    # ── painting ─────────────────────────────────────────────────────────────────────────────────────────────────────
    def over(self, colour, alpha) -> None:
        sl = self.bbox(alpha)
        if sl is None:
            return
        a = np.clip(alpha[sl], 0, 1)[..., None]
        if isinstance(colour, Patch):
            col = colour.crop(sl)
        else:
            col = np.asarray(colour, dtype=np.float32)
            if col.ndim == 3:
                col = col[sl]
        self.rgb[sl] = self.rgb[sl] * (1 - a) + col * a
        self.a[sl] = self.a[sl] + a[..., 0] * (1 - self.a[sl])

    def glow(self, m, colour, units, strength=0.8) -> None:
        self.over(colour, self.blur(m, units) * strength)

    def shade(self, m, ramp, bevel=3.0, rim=0.0) -> Patch:
        """Cel bands from a blurred-mask height field: deep, shadow, base, light; a neon rim light on one side."""
        sl = self.bbox(m, int(math.ceil(bevel * self.ss * 3)) + 2)
        if sl is None:
            return Patch(0, 0, np.zeros((0, 0, 3), dtype=np.float32))
        h = self.blur(m, bevel)[sl]
        gy, gx = np.gradient(h)
        k = bevel * self.ss * 1.4
        n = np.stack([-gx * k, -gy * k, np.ones_like(h)], axis=-1)
        n /= np.linalg.norm(n, axis=-1, keepdims=True)
        lam = n @ LIGHT
        deep, shadow, base, light = ramp
        t0 = smoothstep(0.05, 0.12, lam)[..., None]
        t1 = smoothstep(0.38, 0.45, lam)[..., None]
        t2 = smoothstep(0.86, 0.90, lam)[..., None]
        col = deep * (1 - t0) + shadow * t0
        col = col * (1 - t1) + base * t1
        col = col * (1 - t2) + light * t2
        if rim:
            r = (smoothstep(0.55, 0.7, n @ RIM) * rim)[..., None]
            col = col * (1 - r) + NEON * r
        return Patch(sl[0].start, sl[1].start, col.astype(np.float32))

    def part(self, m, ramp, bevel=3.0, line=1.1, rim=0.25) -> None:
        if line:
            self.over(OUTLINE, self.grow(m, line))
        self.over(self.shade(m, ramp, bevel, rim), m)

    def lerp(self, a, b, t) -> np.ndarray:
        t = np.asarray(t, dtype=np.float32)[..., None]
        return a * (1 - t) + b * t

    def image(self) -> Image.Image:
        rgba = np.dstack([self.rgb, self.a])
        return Image.fromarray((np.clip(rgba, 0, 1) * 255 + 0.5).astype(np.uint8), "RGBA")


def resample(im: Image.Image, w: int, h: int) -> Image.Image:
    """`im` at `w × h`, filtered on premultiplied colour so a transparent pixel lends no colour to an edge."""
    if im.size == (w, h):
        return im
    return im.convert("RGBa").resize((w, h), Image.LANCZOS).convert("RGBA")


def bezier(p0, p1, p2, p3, n=32):
    out = []
    for i in range(n + 1):
        t = i / n
        a, b, c, d = (1 - t) ** 3, 3 * (1 - t) ** 2 * t, 3 * (1 - t) * t * t, t ** 3
        out.append((a * p0[0] + b * p1[0] + c * p2[0] + d * p3[0], a * p0[1] + b * p1[1] + c * p2[1] + d * p3[1]))
    return out


def fbm(h, w, seed, base, octaves=4, stretch=1.0) -> np.ndarray:
    """Smooth value noise in 0..1: random grids enlarged bicubically and summed."""
    rng = np.random.default_rng(seed)
    acc, wsum = np.zeros((h, w), dtype=np.float32), 0.0
    for o in range(octaves):
        g = base * 2 ** o
        gw = g * max(1, round(w / h))
        grid = rng.random((g, max(2, int(gw / stretch))))
        im = Image.fromarray((grid * 255).astype(np.uint8)).resize((w, h), Image.BICUBIC)
        weight = 0.5 ** o
        acc += weight * np.asarray(im, dtype=np.float32) / 255.0
        wsum += weight
    return acc / wsum


# ── space ────────────────────────────────────────────────────────────────────────────────────────────────────────────

def planet(cv: Canvas, clip, cx, cy, r, seed=2) -> None:
    earth = cv.ellipse(cx, cy, r, r) * clip
    cv.glow(earth, rgbf(0x6FD0FF), 2.5, 0.9)
    d = np.hypot(cv.X - cx, cv.Y - cy) / r
    lit = np.clip(((cx - cv.X) * 0.55 + (cy - cv.Y)) / r + 0.35, 0, 1)
    col = cv.lerp(rgbf(0x081E5E), rgbf(0x2266D6), smoothstep(0.1, 0.95, lit))
    land_n = fbm(cv.H, cv.W, seed, 5)
    land = smoothstep(0.54, 0.58, land_n)[..., None]
    landc = cv.lerp(rgbf(0x1F7A3A), rgbf(0x7ACB4E), smoothstep(0.2, 0.9, lit))
    sand = smoothstep(0.66, 0.75, land_n)[..., None] * 0.25
    landc = landc * (1 - sand) + rgbf(0xC9B46A) * sand
    col = col * (1 - land) + landc * land
    clouds = smoothstep(0.70, 0.78, fbm(cv.H, cv.W, seed + 7, 6, stretch=2.2))[..., None] * 0.7
    col = col * (1 - clouds) + WHITE * clouds
    col = col * (1 - smoothstep(0.35, 0.0, lit)[..., None] * 0.55)
    atmo = (smoothstep(0.94, 1.0, d) * smoothstep(0.1, 0.8, lit))[..., None] * 0.5
    col = col * (1 - atmo) + rgbf(0x8FE3FF) * atmo
    cv.over(col, earth)


def space(cv: Canvas, clip, seed=3, stars=46, star_rows=(4, 70)) -> None:
    cv.over(cv.lerp(rgbf(0x070620), rgbf(0x1A0F4A), smoothstep(0, cv.uh, cv.Y + cv.X * 0.3)), clip)
    neb = np.clip(np.sin(cv.X / 9 + 1.2) * np.sin(cv.Y / 7 + 0.4) + 0.4 * np.sin((cv.X - cv.Y) / 5), 0, 1)
    cv.over(rgbf(0x8A2FB5), cv.blur(neb * clip, 3) * 0.35 * clip)
    rng = np.random.default_rng(seed)
    for _ in range(stars):
        x, y = rng.uniform(4, cv.uw - 4), rng.uniform(*star_rows)
        r = rng.choice([0.35, 0.45, 0.6], p=[0.6, 0.3, 0.1])
        s = cv.ellipse(x, y, r, r) * clip
        cv.over(rgbf(0xCFD8FF), s)
        if r > 0.5:
            cv.glow(s, rgbf(0xB9C6FF), 1.0, 0.9)
            cross = np.clip(cv.tube([(x - 2.2, y), (x + 2.2, y)], 0.18) + cv.tube([(x, y - 2.2), (x, y + 2.2)], 0.18), 0, 1)
            cv.over(WHITE, cross * clip)


# ── the cabin ────────────────────────────────────────────────────────────────────────────────────────────────────────

_ROOM: dict[int, Canvas] = {}


def room(ss: int = SS) -> Canvas:
    """The parts of the cabin that never change: walls, window, Earth, console, rack, mug. Drawn once."""
    if ss in _ROOM:
        return _ROOM[ss].copy()
    cv = Canvas(ss=ss)
    walls = cv.rrect(0.5, 0.5, 127.5, 127.5, 10)
    cv.over(cv.lerp(rgbf(0x1B1150), rgbf(0x0D0A33), smoothstep(0, 128, cv.Y)), walls)
    # the window, and Earth in it
    cv.over(cv.lerp(rgbf(0x7C55E8), rgbf(0x3A1F8F), smoothstep(30, 140, cv.X + cv.Y)), cv.rrect(3, 3, 95, 91, 8))
    cv.over(OUTLINE, cv.rrect(5.5, 5.5, 92.5, 88.5, 6.5))
    win = cv.rrect(7, 7, 91, 87, 6)
    space(cv, win)
    planet(cv, win, 66, 106, 60)
    cv.over(WHITE, cv.blur(win, 0.1) * smoothstep(9, 0, cv.X + cv.Y - 20) * 0.08)
    ring = np.clip(cv.rrect(6.2, 6.2, 91.8, 87.8, 6.4) - cv.rrect(7.2, 7.2, 90.8, 86.8, 5.8), 0, 1)
    cv.glow(ring, NEON, 1.6, 0.7)
    cv.over(NEON, ring)
    # the status screen's housing
    cv.over(OUTLINE, cv.rrect(96, 5, 126, 45, 4))
    cv.over(cv.lerp(rgbf(0x6A47D6), rgbf(0x3A1F8F), smoothstep(0, 60, cv.Y)), cv.rrect(97, 6, 125, 44, 3.5))
    cv.over(OUTLINE, cv.rrect(99, 8, 123, 42, 2.4))
    # the console
    cv.over(cv.lerp(rgbf(0x3B1B86), rgbf(0x160A3D), smoothstep(96, 128, cv.Y)),
            cv.poly([(-1, 96), (129, 96), (129, 129), (-1, 129)]) * walls)
    top = cv.poly([(-1, 93.5), (129, 93.5), (129, 99), (-1, 99)]) * walls
    cv.over(OUTLINE, cv.grow(top, 0.6) * walls)
    cv.over(cv.lerp(rgbf(0x9A8BFF), rgbf(0x4B3BB0), smoothstep(93.5, 99, cv.Y)), top)
    trim = cv.poly([(-1, 99.2), (129, 99.2), (129, 100.3), (-1, 100.3)]) * walls
    cv.glow(trim, NEON, 1.2, 0.6)
    cv.over(NEON, trim)
    for x0 in (86, 106):
        cv.over(OUTLINE, cv.rrect(x0, 103, x0 + 17, 116, 2.2))
    for i in range(3):
        lx = 40 + i * 7
        cv.over(OUTLINE, cv.rrect(lx - 2.6, 105.6, lx + 2.6, 110.4, 1))
    # the mug
    mug = cv.rrect(110.5, 84, 120.5, 95, 1.6)
    handle = np.clip(cv.ellipse(121, 89.5, 3, 3) - cv.ellipse(121, 89.5, 1.5, 1.5), 0, 1) * (cv.X > 119.5)
    cv.over(OUTLINE, cv.grow(np.maximum(mug, handle), 0.9))
    cv.over(cv.shade(handle, MUG, 0.8), handle)
    cv.over(cv.shade(mug, MUG, 2.0), mug)
    cv.over(GREEN_GLOW, cv.ellipse(114, 90, 1.8, 1.8))
    cv.over(OUTLINE, cv.ellipse(113.4, 89.6, 0.35, 0.35) + cv.ellipse(114.6, 89.6, 0.35, 0.35))
    # the server rack
    rack = cv.rrect(2.5, 78, 27.5, 118, 1.5)
    cv.over(OUTLINE, cv.grow(rack, 1.0))
    cv.over(cv.lerp(rgbf(0x34324A), rgbf(0x1E1C2E), smoothstep(78, 118, cv.Y)), rack)
    cv.over(rgbf(0x5A5776), cv.rrect(3.5, 78.8, 26.5, 81, 0.8))
    for y in range(84, 116, 6):
        cv.over(rgbf(0x15141F), cv.rrect(5.5, y, 24.5, y + 4, 0.8))
        cv.over(rgbf(0x2E2C40), cv.rrect(15, y + 1.5, 23, y + 2.5, 0.4))
    cv.walls = walls
    _ROOM[ss] = cv
    return cv.copy()


def status_screen(cv: Canvas, pose: str, frame: int) -> None:
    """The big screen top right: what the node is doing, in a picture."""
    scr = cv.rrect(99.5, 8.5, 122.5, 41.5, 2)
    tone = {"starting": "boot" if frame else "off", "syncing": "sync", "watching": "ok", "warning": "warn",
            "error": "err", "asleep": "off", "celebrating": "win"}[pose]
    glow = {"boot": NEON, "sync": NEON, "ok": GREEN_GLOW, "warn": AMBER, "err": RED, "win": GREEN_GLOW}.get(tone)
    if glow is not None:
        cv.glow(scr, glow, 2.0, 0.6 if not (tone == "err" and frame) else 0.3)
    base = {"boot": 0x05222A, "sync": 0x05222A, "ok": 0x062A12, "warn": 0x3A2200, "err": 0x3A0610, "off": 0x0A0A12,
            "win": 0x062A12}[tone]
    cv.over(cv.lerp(rgbf(base) * 1.6, rgbf(base), smoothstep(8, 42, cv.Y)), scr)
    if tone == "off":
        power = np.clip(cv.ellipse(111, 26, 5, 5) - cv.ellipse(111, 26, 3.8, 3.8), 0, 1) * (
            1 - cv.rrect(109.4, 19, 112.6, 24, 0.5))
        power = np.maximum(power, cv.rrect(110.4, 19.5, 111.6, 26, 0.5))
        cv.over(rgbf(0x3A6E7A) if pose == "starting" else rgbf(0x1E1E2C), power)
    elif tone == "boot":
        for i, w in enumerate((14, 9, 17, 6, 12)):
            cv.over(rgbf(0x6FF2FF), cv.rrect(102, 12 + i * 3.2, 102 + w, 13.1 + i * 3.2, 0.5) * 0.9)
        cv.over(rgbf(0x6FF2FF), cv.rrect(102, 28.4, 104, 30.4, 0.3))
    elif tone == "sync":
        # blocks arriving down the chain: they drop a step each frame
        for i in range(4):
            y = 11 + ((i * 6 + frame * 1.5) % 24)
            blk = cv.round_off(cv.rrect(107.5, y, 114.5, y + 4.4, 0.8), 0.25)
            cv.glow(blk, NEON, 0.6, 0.35)
            cv.over(cv.lerp(rgbf(0xB8FBFF), rgbf(0x2BC6D8), smoothstep(y, y + 4.4, cv.Y)), blk * (0.55 + 0.15 * i))
        cv.over(rgbf(0x0E4450), cv.rrect(102, 36.5, 120, 39, 1))
        cv.over(NEON, cv.rrect(102, 36.5, 102 + 4.5 * (frame + 1), 39, 1))
    elif tone == "ok":
        for i, h in enumerate((5, 8, 6, 11, 9, 14, 12, 17)):
            x0 = 102 + i * 2.5
            bar = cv.rrect(x0, 38 - h, x0 + 1.7, 38, 0.4)
            cv.glow(bar, GREEN_GLOW, 0.7, 0.5)
            cv.over(cv.lerp(rgbf(0x2BB52B), rgbf(0xB9FF7A), smoothstep(38, 20, cv.Y)), bar)
        for y, w in ((13, 15), (16, 10)):
            cv.over(rgbf(0x6BE24A), cv.rrect(102, y, 102 + w, y + 1.0, 0.5))
    elif tone == "warn":
        tri = cv.round_off(cv.poly([(111, 11), (121, 37.5), (101, 37.5)]), 0.8)
        cv.glow(tri, AMBER, 1.5, 0.8 if frame == 0 else 0.3)
        cv.over(cv.lerp(rgbf(0xFFE07A), rgbf(0xFF9A0A), smoothstep(12, 37, cv.Y)) * (1.0 if frame == 0 else 0.7), tri)
        cv.over(rgbf(0x3A1A00), cv.rrect(110, 19, 112, 30, 1.0))
        cv.over(rgbf(0x3A1A00), cv.ellipse(111, 33, 1.3, 1.3))
    elif tone == "err":
        octo = cv.round_off(cv.poly([(111 + 10 * math.cos(math.pi / 8 + k * math.pi / 4),
                                      25 + 10 * math.sin(math.pi / 8 + k * math.pi / 4)) for k in range(8)]), 0.6)
        bright = 1.0 if frame == 0 else 0.6
        cv.glow(octo, RED, 1.5, 0.8 * bright)
        cv.over(cv.lerp(rgbf(0xFF7A85), rgbf(0xC4102A), smoothstep(15, 35, cv.Y)) * bright, octo)
        cross = np.clip(cv.tube([(107, 21), (115, 29)], 1.1) + cv.tube([(115, 21), (107, 29)], 1.1), 0, 1)
        cv.over(WHITE * (0.95 if frame == 0 else 0.7), cross)
    elif tone == "win":
        ring = cv.ellipse(111, 25, 9.5, 9.5)
        cv.glow(ring, GREEN_GLOW, 1.4, 0.7)
        cv.over(cv.lerp(rgbf(0xB9FF7A), rgbf(0x2BB52B), smoothstep(15, 35, cv.Y)), ring)
        tick = cv.tube([(106.5, 25.5), (110, 29), (116, 21)], 1.3)
        cv.over(rgbf(0x0B3A10), tick)
        for k in range(4):
            a = frame * 0.4 + k * math.pi / 2
            sx, sy = 111 + 13.2 * math.cos(a) * 0.75, 25 + 13.2 * math.sin(a)
            sp = np.clip(cv.tube([(sx - 1.6, sy), (sx + 1.6, sy)], 0.3) + cv.tube([(sx, sy - 1.6), (sx, sy + 1.6)], 0.3),
                         0, 1) * cv.rrect(99.5, 8.5, 122.5, 41.5, 2)
            cv.over(rgbf(0xFFF6A8), sp)
    # scanlines and glass
    cv.over(np.zeros(3, dtype=np.float32), scr * (np.floor(cv.Y * 2) % 2 == 0) * 0.12)
    cv.over(WHITE, scr * smoothstep(1.5, 0, np.abs((cv.X - 100) + (cv.Y - 10) * 0.6 - 4)) * 0.18)


def alarm_lamp(cv: Canvas, pose: str, frame: int) -> None:
    lamp = cv.ellipse(111, 5.2, 4, 2.6) * (cv.Y < 5.6)
    colour = {"warning": (rgbf(0xFFE08A), rgbf(0xFF8A00), AMBER), "error": (rgbf(0xFFB0B8), rgbf(0xE0102A), RED)}
    lit = pose in colour and frame == 0
    if lit:
        top, bottom, glow = colour[pose]
        cv.glow(lamp, glow, 3, 0.9)
        if pose == "error":  # a beam sweeping the room
            beam = cv.poly([(111, 5), (60, 40), (78, 52)]) * cv.walls
            cv.over(glow, cv.blur(beam, 3.0) * 0.18)
    cv.over(OUTLINE, cv.grow(lamp, 0.8))
    cv.over(cv.lerp(top, bottom, smoothstep(2.5, 5.6, cv.Y)) if lit else rgbf(0x55506A), lamp)


def console_screens(cv: Canvas, pose: str, frame: int) -> None:
    on = pose not in ("asleep",) and not (pose == "starting" and frame == 0)
    tint = {"error": (rgbf(0x3A0610), RED, rgbf(0xFF6A7A))}.get(pose, (rgbf(0x062B33), NEON, rgbf(0x3BF5A0)))
    for x0, kind in ((86, "graph"), (106, "net")):
        sm = cv.rrect(x0 + 1, 104, x0 + 16, 115, 1.6)
        if on:
            cv.glow(sm, tint[1], 1.2, 0.35)
        cv.over(tint[0] if on else rgbf(0x0A0A12), sm)
        if not on:
            continue
        if kind == "graph":
            heights = (3, 5, 4, 7, 6, 9)
            shift = frame if pose in ("syncing", "celebrating") else 0
            for i in range(6):
                h = heights[(i + shift) % 6]
                cv.over(tint[2], cv.rrect(x0 + 3 + i * 2, 113 - h, x0 + 4.2 + i * 2, 113, 0.3))
        else:
            pts = ((x0 + 4, 107), (x0 + 12, 106), (x0 + 8, 110), (x0 + 4, 113), (x0 + 13, 112))
            for a, b in ((0, 2), (1, 2), (2, 3), (2, 4), (0, 1)):
                cv.over(rgbf(0x2AA7B8) if pose != "error" else rgbf(0xB8323F), cv.tube([pts[a], pts[b]], 0.25))
            for j, (x, y) in enumerate(pts):
                dot = cv.ellipse(x, y, 0.9, 0.9)
                hot = pose == "syncing" and j == frame % len(pts)
                cv.glow(dot, tint[1], 0.6, 0.6 if not hot else 1.0)
                cv.over(rgbf(0xA8F6FF) if pose != "error" else rgbf(0xFFB0B8), dot)
                if hot:
                    cv.over(WHITE, cv.ellipse(x, y, 1.3, 1.3))


LEDS = {
    "starting": (("g", None, None), ("g", "g", None)),
    "syncing": (("g", None, None), (None, "g", None), (None, None, "g"), (None, "g", None)),
    "watching": (("g", "g", None), ("g", "g", None)),
    "warning": (("a", "a", None), ("a", None, None)),
    "error": (("r", "r", "r"), (None, None, None)),
    "asleep": ((None, None, "g"), (None, None, "g")),
    "celebrating": (("g", "a", "c"), ("c", "g", "a"), ("a", "c", "g"), ("g", "c", "a")),
}


def lamps(cv: Canvas, pose: str, frame: int) -> None:
    """The node's three lamps on the console, and the rack's."""
    for i, s in enumerate(LEDS[pose][frame % len(LEDS[pose])]):
        lx = 40 + i * 7
        led = cv.rrect(lx - 1.7, 106.6, lx + 1.7, 109.4, 0.8)
        col = {"g": GREEN_GLOW, "a": AMBER, "r": RED, "c": NEON, None: DIM}[s]
        if s:
            cv.glow(led, col, 1.4, 0.8)
        cv.over(col, led)
        cv.over(WHITE, cv.ellipse(lx - 0.6, 107.4, 0.6, 0.4) * (0.7 if s else 0.15))
    for y in range(84, 116, 6):
        for k, x in enumerate((8, 11)):
            on = pose != "asleep" and not (pose == "starting" and frame == 0) and (y // 6 + k + frame) % 3 != 0
            colour = RED if pose == "error" and on and k == 0 else GREEN_GLOW
            led = cv.ellipse(x + 0.8, y + 2, 0.8, 0.8)
            if on:
                cv.glow(led, colour, 0.8, 0.8)
            cv.over(colour if on else rgbf(0x33314A), led)


def steam(cv: Canvas, frame: int) -> None:
    for i, (sx, sy) in enumerate(((114, 80.5), (116, 77.5), (114.5, 74.5))):
        dx = 0.6 * math.sin(frame * 1.3 + i)
        curl = cv.tube(bezier((sx + dx, sy + 2), (sx - 1.5 + dx, sy + 1), (sx + 1.5 + dx, sy - 1), (sx + dx, sy - 2), 8),
                       0.35)
        cv.over(rgbf(0xB9B0FF), curl * (0.5 - 0.12 * i))


# ── the operator ─────────────────────────────────────────────────────────────────────────────────────────────────────

ARMS = {
    # (shoulder, elbow, wrist, hand), back arm then near arm; None: the hand is behind the head
    "behind": (((51, 62), (40, 60), (31, 52), (40, 38)), ((61, 64), (70, 60), (76, 48), (63, 37))),
    "remote": (((51, 62), (40, 60), (31, 52), (40, 38)), ((61, 64), (72, 71), (80, 66), (84, 58))),
    "up": (((51, 62), (37, 60), (29, 44), (29, 29)), ((61, 64), (75, 60), (81, 45), (79, 30))),
    "up2": (((51, 62), (38, 62), (31, 49), (32, 35)), ((61, 64), (74, 63), (79, 50), (76, 36))),
    "out": (((51, 62), (38, 66), (27, 61), (23, 50)), ((61, 64), (74, 69), (85, 63), (88, 52))),
}


def hand(cv: Canvas, x, y, ramp, angle=0.0) -> None:
    palm = cv.ellipse(x, y, 3.6, 3.2)
    thumb = cv.ellipse(x - 3.0 * math.cos(angle) + 1.5 * math.sin(angle), y + 1.2, 1.5, 1.2)
    cv.part(np.maximum(palm, thumb), ramp, 1.4, line=0.9)


def operator(cv: Canvas, pose: str, frame: int) -> None:
    alarm, asleep = pose in ("warning", "error"), pose == "asleep"
    arms = {"starting": "remote", "error": "out", "celebrating": "up" if frame % 2 == 0 else "up2"}.get(pose, "behind")
    bob = (0.0, -0.8, 0.0, 0.8)[frame % 4] if pose == "syncing" else (-1.2 if pose == "celebrating" and frame % 2 else 0)
    # the chair
    back = cv.round_off(cv.poly([(30, 40), (47, 33), (66, 92), (48, 100)]), 1.5)
    seat = cv.round_off(cv.poly([(44, 88), (80, 82), (84, 94), (48, 100)]), 1.2)
    cv.part(back, CHAIR, 3.0)
    cv.over(rgbf(0x8E87DA), cv.tube([(35, 44), (50, 92)], 0.5) * 0.6)
    cv.part(cv.rrect(59, 98, 64, 112, 1), CHAIR, 1.0)
    cv.part(cv.rrect(46, 110.5, 78, 115, 1.5), CHAIR, 1.0)
    for wx in (48, 76):
        cv.part(cv.ellipse(wx, 116, 2.5, 2.5), SHOE, 1.0)
    cv.part(seat, CHAIR, 2.5)
    lift = {"warning": -3.0, "error": -4.0}.get(pose, 0.0)
    tap = -1.6 if pose == "syncing" and frame % 2 == 1 else 0.0
    # the far leg and shoe
    cv.part(cv.tube(bezier((62, 84), (70, 74), (84, 71), (95, 79 + lift), 24), 4.1), BUG_BACK, 2.4)
    cv.part(cv.ellipse(97, 75 + lift, 5.2, 6.6, 0.3), SHOE, 2.2)
    cv.part(cv.ellipse(99.6, 75 + lift, 2.3, 5.2, 0.3), SOLE, 1.2)
    back_arm, near_arm = ARMS[arms]
    raised = arms in ("up", "up2", "out")
    if not raised:
        cv.part(cv.tube(bezier(*back_arm, 24), 3.4), BUG_BACK, 2.2)
    else:
        cv.part(cv.tube(bezier(*back_arm, 24), 3.2), BUG_BACK, 2.2)
        hand(cv, back_arm[3][0], back_arm[3][1] - 1.5, BUG_BACK)
    # the torso and belly
    cv.part(cv.ellipse(58, 74, 9.6, 15.6, -0.45), BUG, 5.0)
    belly = cv.ellipse(61.6, 76, 4.6, 11.6, -0.45)
    cv.part(belly, BELLY, 2.5, line=0.6)
    for k in range(3):
        y = 70 + k * 5
        cv.over(rgbf(0x6DB83A), cv.tube([(57.5 + (y - 70) * 0.48, y), (64.5 + (y - 70) * 0.48, y - 3.2)], 0.35) * belly * 0.9)
    # the near leg and shoe
    cv.part(cv.tube(bezier((64, 84), (73, 74), (88, 71), (101, 82 + lift + tap), 24), 4.6), BUG, 2.6)
    cv.part(cv.ellipse(103, 77 + lift + tap, 5.7, 7.1, 0.3 + tap * 0.05), SHOE, 2.4)
    cv.part(cv.ellipse(106.2, 77 + lift + tap, 2.5, 5.8, 0.3 + tap * 0.05), SOLE, 1.3)
    for k in range(3):
        cv.over(rgbf(0x6E6E7C), cv.tube([(105.2 + k * 0.5, 73.5 + lift + tap + k * 2.8),
                                         (107.6 + k * 0.5, 74.2 + lift + tap + k * 2.8)], 0.3))
    if arms == "behind":
        cv.part(cv.tube(bezier(*near_arm, 24), 3.4), BUG, 2.2)
    hx, hy0 = 51.0, 46.0 + bob
    antennae(cv, pose, frame, hy0 - 46.0)
    head(cv, pose, frame, hx, hy0)
    if arms == "remote":
        cv.part(cv.tube(bezier(*near_arm, 24), 3.4), BUG, 2.2)
        x, y = near_arm[3]
        remote = cv.round_off(cv.poly([(x - 1.5, y + 2.5), (x + 4.5, y - 5.5), (x + 7.5, y - 3.5), (x + 1.5, y + 4.5)]),
                              0.4)
        cv.part(remote, REMOTE, 0.8, line=0.8)
        tip = cv.ellipse(x + 6.3, y - 4.8, 0.9, 0.9)
        cv.over(RED if frame == 0 else rgbf(0x6A2030), tip)
        if frame == 0:
            cv.glow(tip, RED, 0.8, 0.8)
            beam = cv.tube([(x + 7.5, y - 6), (99, 31)], 0.5)
            cv.over(rgbf(0xFF8A9A), cv.blur(beam, 0.6) * 0.5)
        hand(cv, x, y + 1, BUG)
    elif raised:
        cv.part(cv.tube(bezier(*near_arm, 24), 3.2), BUG, 2.2)
        hand(cv, near_arm[3][0], near_arm[3][1] - 1.5, BUG)
    # sweat, sleep and music
    if alarm:
        for dx, dy, s in ((13.5, -6, 1.0), (-14.5, -4, 0.8)) if pose == "error" else ((13.5, -6, 1.0),):
            drop_y = hy0 + dy + (frame * 1.2 if pose == "error" else 0)
            drop = cv.round_off(np.maximum(cv.ellipse(hx + dx, drop_y, 1.8 * s, 2.4 * s),
                                           cv.poly([(hx + dx - 1.3 * s, drop_y - 1), (hx + dx, drop_y - 5.5 * s),
                                                    (hx + dx + 1.3 * s, drop_y - 1)])), 0.3)
            cv.over(OUTLINE, cv.grow(drop, 0.6))
            cv.over(cv.lerp(rgbf(0xE6FDFF), rgbf(0x5FC9FF), smoothstep(drop_y - 5, drop_y + 2, cv.Y)), drop)
    if asleep:
        for zx, zy, s in ((70, 30 - frame * 1.5, 1.0), (77, 20 - frame * 1.5, 1.4), (85, 9 - frame * 1.5, 1.0 + frame * 0.3)):
            z = cv.tube([(zx, zy), (zx + 3.2 * s, zy), (zx, zy + 3.4 * s), (zx + 3.2 * s, zy + 3.4 * s)], 0.55 * s)
            cv.over(OUTLINE, cv.grow(z, 0.7))
            cv.over(rgbf(0xE6E8FF), z)
    if pose == "syncing":
        for i in range(3):
            t = (frame + i * 4 / 3) % 4 / 4
            nx, ny = 70 + t * 16 + i * 1.5, 40 - t * 26
            note(cv, nx, ny, (NEON, rgbf(0xF59BBE), GREEN_GLOW)[i], 1.0 - 0.15 * i, fade=1.0 - max(0.0, t - 0.6) * 2)


def note(cv: Canvas, x, y, colour, s=1.0, fade=1.0) -> None:
    head_m = cv.ellipse(x, y, 2.0 * s, 1.5 * s, -0.4)
    stem = cv.tube([(x + 1.6 * s, y - 0.5 * s), (x + 1.6 * s, y - 7 * s)], 0.38 * s)
    flag = cv.tube(bezier((x + 1.6 * s, y - 7 * s), (x + 4 * s, y - 6 * s), (x + 4.4 * s, y - 4.2 * s),
                          (x + 3.2 * s, y - 3 * s), 10), 0.38 * s)
    m = np.clip(head_m + stem + flag, 0, 1)
    cv.glow(m, colour, 0.8, 0.5 * fade)
    cv.over(OUTLINE, cv.grow(m, 0.6) * fade)
    cv.over(colour, m * fade)


def antennae(cv: Canvas, pose: str, frame: int, dy: float) -> None:
    if pose == "error":
        wig = 1.5 if frame else -1.5
        paths = (bezier((44, 36), (40, 26), (32 + wig, 16), (28 + wig, 8), 32),
                 bezier((56, 34), (58, 24), (64 - wig, 14), (70 - wig, 7), 32))
    else:
        paths = (bezier((44, 36), (40, 24), (30, 20), (22, 18), 32), bezier((56, 34), (58, 22), (66, 14), (76, 13), 32))
    for pts in paths:
        pts = [(x, y + dy) for x, y in pts]
        if pose == "asleep":
            pts = [(x, y + 0.02 * (x - pts[0][0]) ** 2) for x, y in pts]
        cv.part(cv.tube(pts, 1.0), BUG, 0.8, line=0.8)
        tip = cv.ellipse(*pts[-1], 2.1, 2.1)
        glow = {"error": RED, "warning": AMBER}.get(pose, GREEN_GLOW)
        cv.glow(tip, glow, 1.2, 0.35 if pose != "asleep" else 0.1)
        cv.part(tip, BUG, 1.2, line=0.8)


def head(cv: Canvas, pose: str, frame: int, hx: float, hy0: float) -> None:
    asleep = pose == "asleep"
    eyes = {"warning": "wide", "error": "wide", "asleep": "closed", "celebrating": "happy"}.get(pose, "open")
    if pose == "watching" and frame == 1:
        eyes = "blink"
    mouth = {"starting": "smile", "warning": "o", "error": "shout", "asleep": "sleep", "celebrating": "grin"}.get(
        pose, "smile")
    hd = np.maximum(cv.ellipse(hx, hy0, 15.6, 12.6), cv.ellipse(hx + 1, hy0 + 5, 11, 9))
    cv.part(hd, BUG, 6.0, rim=0.35)
    # the headphones
    band = np.clip(cv.ellipse(hx, hy0 - 1, 17.6, 15.2) - cv.ellipse(hx, hy0 - 1, 15.4, 13), 0, 1) * smoothstep(
        hy0 - 2, hy0 - 4, cv.Y)
    cv.part(band, PHONES, 1.0, line=0.8)
    ring_on = {"asleep": None, "error": RED, "warning": AMBER}.get(pose, GREEN_GLOW)
    for cx in (hx - 15.5, hx + 15.5):
        cv.part(cv.ellipse(cx, hy0 + 1, 4.1, 6.0), PHONES, 2.2, line=0.9)
        ring = np.clip(cv.ellipse(cx, hy0 + 1, 2.4, 3.6) - cv.ellipse(cx, hy0 + 1, 1.5, 2.6), 0, 1)
        if ring_on is not None:
            cv.glow(ring, ring_on, 0.8, 0.7)
        cv.over(ring_on if ring_on is not None else rgbf(0x44445A), ring)
    # the eyes
    ey = hy0 + 1
    look = 0.8 if pose == "starting" else 0.0
    for ex in (hx - 6.6, hx + 6.6):
        if eyes in ("closed", "blink"):
            lid = np.clip(cv.ellipse(ex, ey - 0.4, 4.6, 3.0) - cv.ellipse(ex, ey - 1.8, 4.8, 3.0), 0, 1) * (
                cv.Y > ey - 0.6)
            cv.over(OUTLINE, lid)
            continue
        if eyes == "happy":
            arc = cv.tube(bezier((ex - 4.2, ey + 1.6), (ex - 2.4, ey - 3.2), (ex + 2.4, ey - 3.2), (ex + 4.2, ey + 1.6), 14),
                          0.95)
            cv.over(OUTLINE, arc)
            continue
        rx, ry = (5.5, 6.8) if eyes == "wide" else (5.1, 6.1)
        e = cv.ellipse(ex, ey, rx, ry)
        cv.over(OUTLINE, cv.grow(e, 0.7))
        cv.over(cv.lerp(rgbf(0x0B0A1E), rgbf(0x2C3C8A), smoothstep(ey - 1, ey + ry, cv.Y)), e)
        cv.over(rgbf(0x4F7BE0), cv.ellipse(ex + 0.8, ey + 3.3, rx - 2.4, 1.6) * e * 0.55)
        small = 0.75 if pose == "error" else 1.0
        cv.over(WHITE, cv.ellipse(ex - 1.5 + look, ey - 2.3, 2.0 * small, 2.4 * small, -0.4))
        cv.over(WHITE, cv.ellipse(ex + 2.2 + look, ey + 2.2, 0.9, 0.9))
    # the mouth
    my = hy0 + 9
    if mouth == "o":
        o = cv.ellipse(hx, my + 0.6, 2.4, 2.8)
        cv.over(OUTLINE, cv.grow(o, 0.6))
        cv.over(MOUTH, o)
    elif mouth == "shout":
        o = cv.ellipse(hx, my + 1.0, 3.4, 3.9 if frame == 0 else 3.4)
        cv.over(OUTLINE, cv.grow(o, 0.6))
        cv.over(MOUTH, o)
        cv.over(TONGUE, cv.ellipse(hx + 0.4, my + 3.2, 2.2, 1.4) * o)
    elif mouth == "sleep":
        cv.over(OUTLINE, cv.tube(bezier((hx - 2.4, my), (hx - 0.8, my + 1), (hx + 0.8, my + 1), (hx + 2.4, my), 10), 0.45))
    else:
        big = mouth == "grin"
        smile = cv.ellipse(hx, my - 1.6, 7.0 if big else 6.0, 6.6 if big else 5.2) * smoothstep(my - 1.8, my - 1.2, cv.Y)
        cv.over(OUTLINE, cv.grow(smile, 0.6))
        cv.over(MOUTH, smile)
        cv.over(TONGUE, cv.ellipse(hx + 0.8, my + (4.2 if big else 3.2), 3.8 if big else 3.4, 2.3 if big else 2.1) * smile)
        cv.over(WHITE, cv.rrect(hx - 4.2, my - 1.5, hx + 4.2, my - 0.4, 0.5) * smile * 0.9)
    # cheeks
    for sx in (hx - 11.2, hx + 11.2):
        cv.over(WHITE, cv.ellipse(sx, my - 2.2, 1.6, 0.8) * 0.35)
        if pose == "celebrating":
            cv.over(rgbf(0xFF7FA6), cv.ellipse(sx, my - 0.4, 2.2, 1.2) * 0.45)


# ── the worm ─────────────────────────────────────────────────────────────────────────────────────────────────────────

def worm(cv: Canvas, pose: str, frame: int) -> None:
    asleep = pose == "asleep"
    droop = 2.0 if asleep else 0.0
    bob = {"syncing": (0.0, -1.2, 0.0, 1.0)[frame % 4], "celebrating": (-2.0, 0.0, -2.0, 0.0)[frame % 4],
           "error": -1.5}.get(pose, 0.0)
    wag = frame % 2 if pose != "error" else 0
    body = (bezier((26.5, 72.5 - wag), (27.5, 77.5), (22, 78), (17, 77.5), 12)
            + bezier((17, 77.5), (6, 77.5), (5, 71), (10, 67), 16)[1:]
            + bezier((10, 67), (15, 63 + bob * 0.5), (16, 59 + droop + bob), (14, 54 + droop + bob), 14)[1:])
    m = cv.tube(body, 3.6)
    cv.part(m, PINK, 2.4, rim=0.2)
    for i in range(12, len(body) - 3, 4):
        (x0, y0), (x1, y1) = body[i - 1], body[i + 1]
        nx, ny = -(y1 - y0), x1 - x0
        n = math.hypot(nx, ny) or 1
        nx, ny = nx / n, ny / n
        cx, cy = body[i]
        ring = cv.tube(bezier((cx - nx * 3, cy - ny * 3), (cx - nx * 1 + 0.6 * (x1 - x0), cy - ny * 1 + 0.6 * (y1 - y0)),
                              (cx + nx * 1 + 0.6 * (x1 - x0), cy + ny * 1 + 0.6 * (y1 - y0)), (cx + nx * 3, cy + ny * 3),
                              10), 0.32)
        cv.over(rgbf(0xC2507F), ring * m * 0.8)
    fx, fy = 14.5, 50.0 + droop + bob
    cv.part(cv.ellipse(fx, fy, 6.6, 5.8), PINK, 3.0, rim=0.2)
    shades = "off" if asleep else ("slipped" if pose == "error" else "on")
    if asleep:
        for x0 in (fx - 2.6, fx + 2.2):
            cv.over(OUTLINE, cv.tube(bezier((x0 - 1.4, fy - 0.2), (x0 - 0.5, fy + 0.8), (x0 + 0.5, fy + 0.8),
                                            (x0 + 1.4, fy - 0.2), 8), 0.35))
    if shades == "slipped":  # wide eyes over the glasses
        for x0 in (fx - 2.5, fx + 2.5):
            e = cv.ellipse(x0, fy - 2.4, 1.9, 2.1)
            cv.over(OUTLINE, cv.grow(e, 0.4))
            cv.over(WHITE, e)
            cv.over(OUTLINE, cv.ellipse(x0, fy - 2.2, 0.8, 0.9))
    if shades != "off":
        sy = fy + (1.6 if shades == "slipped" else 0.0)
        lens = np.maximum(cv.ellipse(fx - 2.5, sy - 0.7, 2.6, 1.9), cv.ellipse(fx + 2.5, sy - 0.7, 2.6, 1.9))
        frame_bar = cv.rrect(fx - 6.4, sy - 2.6, fx + 6.4, sy - 1.5, 0.5)
        glasses = np.maximum(lens, frame_bar)
        cv.over(OUTLINE, cv.grow(glasses, 0.4))
        cv.over(cv.lerp(rgbf(0x25204A), rgbf(0x0B0A18), smoothstep(sy - 2.5, sy + 1.2, cv.Y)), glasses)
        streak = np.clip(cv.tube([(fx - 3.8, sy + 0.2), (fx - 2.4, sy - 1.6)], 0.35)
                         + cv.tube([(fx + 1.2, sy + 0.2), (fx + 2.6, sy - 1.6)], 0.35), 0, 1)
        cv.over(WHITE, streak * lens * 0.85)
    if pose in ("warning", "starting"):
        o = cv.ellipse(fx + 0.6, fy + 3.0, 1.4, 1.5)
        cv.over(OUTLINE, o)
    elif pose == "error":
        o = cv.ellipse(fx + 0.6, fy + 3.4, 1.9, 1.9)
        cv.over(OUTLINE, o)
        cv.over(TONGUE, cv.ellipse(fx + 0.8, fy + 4.4, 1.1, 0.7) * o)
    elif not asleep:
        big = pose == "celebrating"
        grin = cv.ellipse(fx + 0.5, fy + 2.0, 3.8 if big else 3.4, 3.2 if big else 2.5) * smoothstep(fy + 1.8, fy + 2.4,
                                                                                                     cv.Y)
        cv.over(OUTLINE, grin)
        cv.over(TONGUE, cv.ellipse(fx + 0.9, fy + (4.2 if big else 3.6), 1.8, 1.1) * grin)
    cv.over(rgbf(0xFF6F9E), cv.ellipse(fx - 4.6, fy + 2.2, 1.4, 0.9) * 0.6)
    if pose == "celebrating":  # a party hat
        hat = cv.round_off(cv.poly([(fx - 4.6, fy - 4.4), (fx + 1.4, fy - 16.0), (fx + 4.4, fy - 3.6)]), 0.3)
        cv.over(OUTLINE, cv.grow(hat, 0.6))
        stripes = (np.sin((cv.X + cv.Y * 1.4) * 1.3) > 0.2).astype(np.float32)
        cv.over(cv.lerp(rgbf(0xFFD23F), rgbf(0x3FE6FF), stripes), hat)
        pom = cv.ellipse(fx + 1.4, fy - 16.2, 1.6, 1.6)
        cv.over(OUTLINE, cv.grow(pom, 0.5))
        cv.over(rgbf(0xFF5FAF), pom)


CONFETTI = [rgbf(c) for c in (0xFFD23F, 0x3FE6FF, 0xFF5FAF, 0x7CFF4E, 0xB48CFF, 0xFFFFFF)]


def confetti(cv: Canvas, frame: int) -> None:
    rng = np.random.default_rng(11)
    for _ in range(30):
        x, y0 = rng.uniform(6, 122), rng.uniform(0, 92)
        colour, turn, spin = CONFETTI[rng.integers(len(CONFETTI))], rng.uniform(0, math.pi), rng.uniform(0.5, 1.2)
        y = 4 + (y0 + frame * 6) % 88
        x += math.sin(y / 9 + turn) * 1.5
        a = turn + frame * spin
        dx, dy = 1.6 * math.cos(a), 1.6 * math.sin(a)
        ex, ey = -0.7 * math.sin(a) * abs(math.cos(frame + turn)), 0.7 * math.cos(a) * abs(math.cos(frame + turn))
        piece = cv.poly([(x - dx - ex, y - dy - ey), (x + dx - ex, y + dy - ey), (x + dx + ex, y + dy + ey),
                         (x - dx + ex, y - dy + ey)])
        cv.over(OUTLINE, cv.grow(piece, 0.35) * 0.7)
        cv.over(colour, piece)


def cabin(pose: str = "watching", frame: int = 0, size: int = 512) -> Image.Image:
    """One frame of the mascot: the whole cabin, `size` pixels square."""
    cv = room()
    status_screen(cv, pose, frame)
    alarm_lamp(cv, pose, frame)
    console_screens(cv, pose, frame)
    lamps(cv, pose, frame)
    if pose != "asleep":
        steam(cv, frame)
    operator(cv, pose, frame)
    worm(cv, pose, frame)
    if pose == "celebrating":
        confetti(cv, frame)
    if pose == "error":
        cv.over(RED, cv.walls * (0.16 if frame == 0 else 0.06))
    if pose == "asleep":
        cv.over(rgbf(0x0A0830), cv.walls * 0.28)
    edge = np.clip(cv.walls - cv.rrect(1.8, 1.8, 126.2, 126.2, 9), 0, 1)
    cv.over(OUTLINE, edge)
    cv.a = cv.a * cv.walls
    return resample(cv.image(), size, size)


# ── the network marks and the badge ──────────────────────────────────────────────────────────────────────────────────

def network_icon(family: str = "etc", size: int = 256) -> Image.Image:
    cv = Canvas(32, ss=32)
    m = 16.0
    if family == "etc":
        cols = (rgbf(0xB9FF6A), rgbf(0x6BDB2E), rgbf(0x2E9A26))
        top, notch = (m, 1.5), (m, 10.0)
        faces = [((top, (7.0, 13.4), notch), 0), ((top, notch, (25.0, 13.4)), 1)]
        west, north, east, south, heart = (7.5, 16.0), (m, 12.0), (24.5, 16.0), (m, 20.2), (m, 16.4)
        faces += [((west, north, heart), 0), ((north, east, heart), 1), ((west, heart, south), 1),
                  ((heart, east, south), 2)]
        faces += [(((7.0, 18.6), (m, 22.6), (m, 30.5)), 1), (((25.0, 18.6), (m, 22.6), (m, 30.5)), 2)]
    else:
        cols = (rgbf(0xD6D2FF), rgbf(0x9A92F2), rgbf(0x5E54C8))
        top, left, right, heart, low = (m, 1.5), (6.5, 16.2), (25.5, 16.2), (m, 12.4), (m, 21.6)
        faces = [((top, left, heart), 0), ((top, heart, right), 1), ((left, heart, low), 1), ((heart, right, low), 2)]
        faces += [(((6.5, 18.4), (m, 23.6), (m, 30.5)), 1), (((25.5, 18.4), (m, 23.6), (m, 30.5)), 2)]
    whole = cv.zeros()
    masks = []
    for tri, k in faces:
        fm = cv.poly(list(tri))
        masks.append((fm, k))
        whole = np.maximum(whole, fm)
    cv.glow(whole, cols[1], 1.2, 0.45)
    cv.over(OUTLINE, cv.grow(whole, 1.3))
    for fm, k in masks:
        cv.over(np.clip(cv.lerp(cols[k] * 1.08, cols[k] * 0.88, smoothstep(0, 32, cv.Y + cv.X * 0.4)), 0, 1), fm)
    cv.over(WHITE, cv.round_off(cv.poly([(m - 0.6, 4), (m + 0.4, 4), (m - 3.6, 11.2), (m - 4.6, 11.2)]), 0.3) * 0.85)
    cv.over(WHITE, cv.ellipse(m - 5.0, 15.2, 0.8, 0.5) * 0.7)
    return resample(cv.image(), size, size)


WORDMARK = {
    "F": ["######", "######", "##    ", "##### ", "##### ", "##    ", "##    ", "##    "],
    "U": ["##  ##", "##  ##", "##  ##", "##  ##", "##  ##", "##  ##", "######", " #### "],
    "K": ["##  ##", "## ## ", "####  ", "###   ", "####  ", "## ## ", "##  ##", "##  ##"],
    "I": ["####", " ## ", " ## ", " ## ", " ## ", " ## ", " ## ", "####"],
}


def hexagon(cv: Canvas, r, cx=64.0, cy=64.0) -> np.ndarray:
    return cv.poly([(cx + r * math.cos(math.pi / 2 + k * math.pi / 3), cy + r * math.sin(math.pi / 2 + k * math.pi / 3))
                    for k in range(6)])


def badge_head(q: Canvas, cx: float, cy: float, s: float) -> None:
    """The operator's head as the badge shows it, centred on (cx, cy) and `s` times its usual size."""
    def at(x, y):
        return (cx + (x - 64) * s, cy + (y - 55) * s)
    for pts in (bezier(at(58, 40), at(54, 30), at(46, 26), at(38, 25), 24),
                bezier(at(70, 40), at(74, 30), at(82, 26), at(90, 25), 24)):
        q.part(q.tube(pts, 1.1 * s), BUG, 0.8 * s, line=0.8)
        q.part(q.ellipse(*pts[-1], 2.3 * s, 2.3 * s), BUG, 1.2 * s, line=0.8)
    q.part(q.ellipse(cx, cy, 15.5 * s, 12.8 * s), BUG, 6.0 * s, rim=0.3)
    by = cy - 1 * s
    band = np.clip(q.ellipse(cx, by, 17.5 * s, 15.0 * s) - q.ellipse(cx, by, 15.3 * s, 12.8 * s), 0, 1) * smoothstep(
        by - 1 * s, by - 3 * s, q.Y)
    q.part(band, PHONES, 1.0 * s, line=0.8)
    for x in (48.5, 79.5):
        ex, ey = at(x, 56)
        q.part(q.ellipse(ex, ey, 4.0 * s, 5.8 * s), PHONES, 2.2 * s, line=0.9)
        cup = np.clip(q.ellipse(ex, ey, 2.3 * s, 3.5 * s) - q.ellipse(ex, ey, 1.4 * s, 2.5 * s), 0, 1)
        q.glow(cup, GREEN_GLOW, 0.8 * s, 0.7)
        q.over(GREEN_GLOW, cup)
    for x in (57.6, 70.4):
        ex, ey = at(x, 54)
        e = q.ellipse(ex, ey, 5.0 * s, 6.0 * s)
        q.over(OUTLINE, q.grow(e, 0.7))
        q.over(q.lerp(rgbf(0x0B0A1E), rgbf(0x2C3C8A), smoothstep(ey - 1 * s, ey + 6 * s, q.Y)), e)
        q.over(WHITE, q.ellipse(ex - 1.5 * s, ey - 2.3 * s, 1.9 * s, 2.3 * s, -0.4))
        q.over(WHITE, q.ellipse(ex + 2.1 * s, ey + 2.3 * s, 0.9 * s, 0.9 * s))
    mx, my = at(64, 62.5)
    smile = q.ellipse(mx, my - 1.4 * s, 4.6 * s, 3.8 * s) * smoothstep(my - 1.6 * s, my - 1.0 * s, q.Y)
    q.over(OUTLINE, q.grow(smile, 0.5))
    q.over(MOUTH, smile)
    q.over(TONGUE, q.ellipse(mx + 0.6 * s, my + 1.6 * s, 2.4 * s, 1.4 * s) * smile)


def badge(size: int = 512, wordmark: bool = True) -> Image.Image:
    """The FUKUII badge: the operator rising over Earth in a hexagon, the wordmark across it."""
    cv = Canvas()
    outer, inner = hexagon(cv, 63.0), hexagon(cv, 56.5)
    cv.over(OUTLINE, outer)
    cv.over(cv.lerp(rgbf(0x8E6BFF), rgbf(0x3A1F8F), smoothstep(20, 140, cv.X + cv.Y * 0.8)), hexagon(cv, 61.5))
    ring = np.clip(hexagon(cv, 58.0) - inner, 0, 1)
    cv.glow(ring, NEON, 1.5, 0.6)
    cv.over(NEON, ring)
    space(cv, inner, seed=8)
    planet(cv, inner, 64, 160, 92, seed=5)
    q = Canvas()
    if wordmark:
        badge_head(q, 64, 55, 1.0)
    else:  # the small badge: the head larger and whole in the frame, no letters
        badge_head(q, 64, 70, 1.3)
    cv.over(q.rgb, q.a * (inner * smoothstep(69, 66, cv.Y) if wordmark else inner))
    if wordmark:
        k, kv = 2.6, 3.3
        text = "FUKUII"
        width = (sum(len(WORDMARK[ch][0]) for ch in text) + len(text) - 1) * k
        x0, y0 = 64 - width / 2, 66.0
        rects = []
        x = x0
        for ch in text:
            for dy, row in enumerate(WORDMARK[ch]):
                for dx, cell in enumerate(row):
                    if cell == "#":
                        rects.append((x + dx * k, y0 + dy * kv, x + dx * k + k, y0 + dy * kv + kv))
            x += (len(WORDMARK[ch][0]) + 1) * k
        letters = cv.zeros()
        for a, b, c, e in rects:
            letters = np.maximum(letters, cv.rrect(a, b, c, e, 0.01))
        letters = cv.round_off(letters, 0.45)
        cv.glow(letters, NEON, 2.2, 0.9)
        shadow = np.roll(np.roll(letters, int(1.2 * cv.ss), 0), int(0.8 * cv.ss), 1)
        cv.over(OUTLINE, cv.grow(np.maximum(letters, shadow), 1.0))
        cv.over(rgbf(0x1E6B2A), shadow)
        cv.over(cv.lerp(rgbf(0xD8FF8A), rgbf(0x3FB52A), smoothstep(y0, y0 + 8 * kv, cv.Y)), letters)
        cv.over(WHITE, letters * smoothstep(y0 + 1.6, y0 + 0.4, cv.Y) * 0.7)
    cv.a = cv.a * outer
    return resample(cv.image(), size, size)


# ── the splash scene ─────────────────────────────────────────────────────────────────────────────────────────────────

def splash_scene(width: int = 1280, height: int = 768) -> Image.Image:
    """A backdrop: deep space, Earth's limb below, and the station far off with its window lit."""
    cv = Canvas(160, 96)
    sky = cv.rrect(0, 0, 160, 96, 0.01)
    space(cv, sky, seed=21, stars=90, star_rows=(3, 80))
    planet(cv, sky, 80, 228, 150, seed=9)
    # the station: a ring and a hub, its cabin window lit
    sx, sy = 122.0, 30.0
    ring_m = np.clip(cv.ellipse(sx, sy, 13, 4.2, -0.25) - cv.ellipse(sx, sy, 10.6, 2.8, -0.25), 0, 1)
    cv.part(ring_m, CHAIR, 1.2, line=0.6, rim=0.3)
    hub = cv.round_off(np.maximum(cv.rrect(sx - 4.5, sy - 6, sx + 4.5, sy + 5, 2.0),
                                  cv.rrect(sx - 1.2, sy - 10, sx + 1.2, sy - 5, 0.6)), 0.3)
    cv.part(hub, CHAIR, 1.6, line=0.7, rim=0.3)
    win = cv.rrect(sx - 2.6, sy - 3.4, sx + 2.6, sy + 0.4, 0.8)
    cv.glow(win, NEON, 1.2, 0.8)
    cv.over(cv.lerp(rgbf(0xB8FBFF), NEON, smoothstep(sy - 3.4, sy + 0.4, cv.Y)), win)
    cv.over(rgbf(0x1E6B2A), cv.ellipse(sx - 0.6, sy - 0.6, 1.0, 1.1))  # someone at the window
    beacon = cv.ellipse(sx, sy - 10.6, 0.8, 0.8)
    cv.glow(beacon, GREEN_GLOW, 1.0, 0.9)
    cv.over(GREEN_GLOW, beacon)
    return resample(cv.image(), width, height)
