#!/usr/bin/env python3
"""Draws the sprite sheets of the TUI command center (spec 029, contracts/art.md).

The art is code: every sheet under src/main/resources/tui/art/ is this script's output, so a change to a sprite is a
change here, and `--check` fails when the committed sheets differ from what the script draws.

    python3 -I scripts/tui/make_art.py                # rewrite the sheets, art.conf and the pixel hashes
    python3 -I scripts/tui/make_art.py --check        # exit 1 if the committed sheets are stale
    python3 -I scripts/tui/make_art.py --preview DIR  # also write contact sheets for people to look at

Each sprite has two sheets. The `hi` sheet, for pixel mode, is smooth full-colour art drawn by hi_art.py, at density 2:
16 × 32 pixels a terminal cell. The `lo` sheet, for block mode, is pixel art in the 240 xterm colours drawn by
lo_art.py, one pixel a half cell. Needs Pillow and NumPy, at the versions the TUI Art workflow pins: the smooth art is
filtered, and another version of either may round a few edge pixels differently, which `--check` tolerates.
"""

from __future__ import annotations

import argparse
import functools
import hashlib
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import hi_art  # noqa: E402
import lo_art  # noqa: E402
from lo_art import T, xterm_rgb  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
ART_DIR = REPO / "src" / "main" / "resources" / "tui" / "art"
# What each sheet's pixels hash to: the node's own PNG reader is tested against it (GfxSpec).
PIXEL_HASHES = REPO / "src" / "test" / "resources" / "tui" / "art-pixels.sha256"

POSES = hi_art.POSES
MASCOT_FRAMES = {"starting": 2, "syncing": 4, "watching": 2, "warning": 2, "error": 2, "asleep": 2, "celebrating": 4}
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

DENSITY = 2  # a `hi` sheet's pixels a VGA pixel: a cell is 16 × 32
CELL = 8 * DENSITY  # a `hi` cell's width; its height is twice that
MAX_BYTES = 8 * 1024 * 1024
# How far a redrawn `hi` sheet may stray from the committed one and still count as the same drawing.
TOLERANCE, TOLERATED_SHARE = 2, 0.001


def rgba(im: Image.Image) -> np.ndarray:
    """An image as RGBA bytes, with every fully transparent pixel made 0 so that equal-looking sheets are equal."""
    a = np.array(im.convert("RGBA"), dtype=np.uint8)
    a[a[..., 3] == 0] = 0
    return a


def grid(frames: list[list[np.ndarray]]) -> np.ndarray:
    """Poses down, frames across; a pose with fewer frames leaves the rest of its row transparent."""
    fh, fw = frames[0][0].shape[:2]
    cols = max(len(row) for row in frames)
    out = np.zeros((fh * len(frames), fw * cols) + frames[0][0].shape[2:], dtype=np.uint8)
    for r, row in enumerate(frames):
        for c, f in enumerate(row):
            out[r * fh : (r + 1) * fh, c * fw : (c + 1) * fw] = f
    return out


@functools.lru_cache(maxsize=1)
def sheets() -> dict[str, np.ndarray]:
    """Every sheet, by file name: a `lo` sheet as palette indices, a `hi` one as RGBA."""
    big = 32 * CELL
    cabins = {(p, f): hi_art.cabin(p, f, big) for p in POSES for f in range(MASCOT_FRAMES[p])}
    return {
        "mascot.hi.png": grid([[rgba(cabins[p, f]) for f in range(MASCOT_FRAMES[p])] for p in POSES]),
        "mascot.lo.png": grid([[lo_art.mascot(p).a] for p in POSES]),
        "mascot-small.hi.png": grid([[rgba(hi_art.resample(cabins[p, f], big // 2, big // 2))
                                      for f in SMALL_PICK.get(p, (0,))] for p in POSES]),
        "mascot-small.lo.png": grid([[lo_art.mascot16(p).a] for p in POSES]),
        "badge.hi.png": rgba(hi_art.badge(16 * CELL, wordmark=False)),
        "badge.lo.png": lo_art.badge(16).a,
        "badge-large.hi.png": rgba(hi_art.badge(32 * CELL)),
        "badge-large.lo.png": lo_art.badge(32).a,
        "network-etc.hi.png": rgba(hi_art.network_icon("etc", 16 * CELL)),
        "network-etc.lo.png": lo_art.network_icon("etc").a,
        "network-eth.hi.png": rgba(hi_art.network_icon("eth", 16 * CELL)),
        "network-eth.lo.png": lo_art.network_icon("eth").a,
        "splash-scene.hi.png": rgba(hi_art.splash_scene(80 * CELL, 24 * 2 * CELL)),
        "splash-scene.lo.png": lo_art.splash_scene(80, 48).a,
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


def pixel_hash(pixels: np.ndarray) -> str:
    """The SHA-256 of a sheet's width and height (four bytes each, big-endian) and then its pixels: a `lo` sheet's
    palette indices, a byte each, or a `hi` sheet's pixels as 0xAARRGGBB, four bytes each, big-endian."""
    h, w = pixels.shape[:2]
    if pixels.ndim == 2:
        body = pixels.astype(np.uint8).tobytes()
    else:
        p = pixels.astype(np.uint32)
        body = ((p[..., 3] << 24) | (p[..., 0] << 16) | (p[..., 1] << 8) | p[..., 2]).astype(">u4").tobytes()
    return hashlib.sha256(w.to_bytes(4, "big") + h.to_bytes(4, "big") + body).hexdigest()


def pixel_hashes(directory: Path) -> str:
    """One line a sheet in `directory`: the hash of its pixels as they were saved, which the node's reader must match."""
    return "".join(f"{pixel_hash(load(directory / name))}  {name}\n" for name in sorted(sheets()))


def palette_bytes() -> bytes:
    flat = bytearray(768)
    for i in range(16, 256):
        flat[i * 3 : i * 3 + 3] = bytes(xterm_rgb(i))
    return bytes(flat)


def save(pixels: np.ndarray, path: Path) -> None:
    if pixels.ndim == 2:
        im = Image.fromarray(pixels, "P")
        im.putpalette(palette_bytes())
        im.save(path, format="PNG", transparency=0, optimize=True)
    else:
        Image.fromarray(pixels, "RGBA").save(path, format="PNG", compress_level=9)


def load(path: Path) -> np.ndarray:
    """A committed sheet as its script draws it: a `lo` sheet as palette indices (a colour that is not one of the 240
    becomes 0, so the sheet no longer matches), a `hi` sheet as RGBA."""
    if path.name.endswith(".hi.png"):
        return rgba(Image.open(path))
    px = np.asarray(Image.open(path).convert("RGBA"))
    out = np.zeros(px.shape[:2], dtype=np.uint8)
    for i in range(16, 256):
        r, g, b = xterm_rgb(i)
        out[(px[..., 0] == r) & (px[..., 1] == g) & (px[..., 2] == b) & (px[..., 3] == 255)] = i
    return out


def same(committed: np.ndarray, drawn: np.ndarray) -> bool:
    """Whether a committed sheet is what the script draws: exactly for a `lo` sheet; for a `hi` one, all but a few
    edge pixels within a step or two, as a different build of the filters rounds them."""
    if committed.shape != drawn.shape:
        return False
    if drawn.ndim == 2:
        return bool(np.array_equal(committed, drawn))
    off = np.abs(committed.astype(np.int16) - drawn.astype(np.int16)).max(axis=-1) > TOLERANCE
    return bool(off.mean() <= TOLERATED_SHARE)


def lo_rgb(a: np.ndarray, k: int, back=(30, 28, 46)) -> Image.Image:
    img = np.zeros(a.shape + (3,), dtype=np.uint8)
    img[...] = back
    for i in np.unique(a):
        if i != T:
            img[a == i] = xterm_rgb(int(i))
    return Image.fromarray(np.kron(img, np.ones((k, k, 1), dtype=np.uint8)), "RGB")


def preview(out_dir: Path) -> None:
    """Contact sheets on a dark ground, for people. Not read by the node."""
    out_dir.mkdir(parents=True, exist_ok=True)
    s = sheets()
    back = (30, 28, 46)

    def on_ground(a: np.ndarray, size=None) -> Image.Image:
        im = Image.fromarray(a, "RGBA")
        if size:
            im = hi_art.resample(im, *size)
        ground = Image.new("RGBA", im.size, back + (255,))
        ground.alpha_composite(im)
        return ground.convert("RGB")

    def contact(tiles: list[list[Image.Image]], gap: int = 8) -> Image.Image:
        width = max(sum(t.width + gap for t in row) for row in tiles) + gap
        height = sum(max(t.height for t in row) + gap for row in tiles) + gap
        sheet = Image.new("RGB", (width, height), (52, 50, 72))
        y = gap
        for row in tiles:
            x = gap
            for t in row:
                sheet.paste(t, (x, y))
                x += t.width + gap
            y += max(t.height for t in row) + gap
        return sheet

    frame = 32 * CELL
    mascot = s["mascot.hi.png"]
    rows = [[on_ground(mascot[r * frame : (r + 1) * frame, f * frame : (f + 1) * frame], (256, 256))
             for f in range(MASCOT_FRAMES[p])] for r, p in enumerate(POSES)]
    contact(rows).save(out_dir / "mascot.png")
    lo, small = s["mascot.lo.png"], s["mascot-small.lo.png"]
    contact([[lo_rgb(lo[r * 32 : (r + 1) * 32], 8) for r in range(len(POSES))],
             [lo_rgb(small[r * 16 : (r + 1) * 16], 16) for r in range(len(POSES))],
             [lo_rgb(s["badge-large.lo.png"], 8), lo_rgb(s["badge.lo.png"], 16), lo_rgb(s["network-etc.lo.png"], 16),
              lo_rgb(s["network-eth.lo.png"], 16)]]).save(out_dir / "block-mode.png")
    contact([[on_ground(s["badge-large.hi.png"]), on_ground(s["badge.hi.png"]),
              on_ground(s["network-etc.hi.png"]), on_ground(s["network-eth.hi.png"])],
             [on_ground(s["splash-scene.hi.png"], (640, 384)), lo_rgb(s["splash-scene.lo.png"], 8)]]).save(
        out_dir / "badge-and-splash.png")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--check", action="store_true", help="exit 1 if the committed sheets differ from the drawings")
    ap.add_argument("--out", type=Path, default=ART_DIR, help="where the sheets go (default: the node's resources)")
    ap.add_argument("--preview", type=Path, help="also write contact sheets to this directory")
    args = ap.parse_args()

    stale = []
    for name, pixels in sheets().items():
        path = args.out / name
        if args.check:
            if not path.exists() or not same(load(path), pixels):
                stale.append(name)
        else:
            args.out.mkdir(parents=True, exist_ok=True)
            save(pixels, path)
            if path.stat().st_size > MAX_BYTES:
                stale.append(f"{name} (over 8 MiB)")
    conf = args.out / "art.conf"
    hashes = PIXEL_HASHES if args.out == ART_DIR else args.out / "art-pixels.sha256"
    if args.check:
        if not conf.exists() or conf.read_text() != manifest():
            stale.append("art.conf")
        if not stale and (not hashes.exists() or hashes.read_text() != pixel_hashes(args.out)):
            stale.append(hashes.name)
    else:
        conf.write_text(manifest())
        hashes.parent.mkdir(parents=True, exist_ok=True)
        hashes.write_text(pixel_hashes(args.out))
    if args.preview:
        preview(args.preview)
    if stale:
        print("stale, run scripts/tui/make_art.py: " + ", ".join(stale), file=sys.stderr)
        return 1
    print(f"{'checked' if args.check else 'wrote'} {len(sheets()) + 2} files in {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
