#!/usr/bin/env python3
"""Checks a directory of TUI sprite sheets against specs/029-tui-command-center/contracts/art.md.

    python3 -I scripts/tui/check_art.py [DIRECTORY]      # default: src/main/resources/tui/art

Exit 0 when every sheet passes, 1 with one line per broken rule otherwise. It reads the sheets and the manifest only,
and shares no code with make_art.py, so it also checks that script's output. Needs Pillow and NumPy.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

import numpy as np
from PIL import Image

REPO = Path(__file__).resolve().parents[2]
DEFAULT = REPO / "src" / "main" / "resources" / "tui" / "art"

# sprite -> (columns, rows, number of poses, most frames a pose may have, optional)
SPRITES = {
    "mascot": (32, 16, 7, 4, False),
    "mascot-small": (16, 8, 7, 2, False),
    "badge": (16, 8, 1, 1, False),
    "badge-large": (32, 16, 1, 1, False),
    "network-etc": (16, 8, 1, 1, False),
    "network-eth": (16, 8, 1, 1, False),
    "splash-scene": (80, 24, 1, 1, True),
}
POSES = ("starting", "syncing", "watching", "warning", "error", "asleep", "celebrating")
MAX_BYTES = 4 * 1024 * 1024
CUBE = (0, 95, 135, 175, 215, 255)


def palette() -> set[tuple[int, int, int]]:
    colours = {(r, g, b) for r in CUBE for g in CUBE for b in CUBE}
    colours |= {(v, v, v) for v in range(8, 239, 10)}
    assert len(colours) == 240
    return colours


def frames_of(conf: str, sprite: str) -> list[int] | None:
    """The frame count of each pose of `sprite` in art.conf, or None when the sprite declares no poses."""
    block = re.search(rf"^\s*{re.escape(sprite)}\s*\{{(.*?)^\s*\}}", conf, re.S | re.M)
    if block is None or "poses" not in block.group(1):
        return None
    found = dict(re.findall(r"name\s*=\s*([a-z]+)\s*,\s*frames\s*=\s*(\d+)", block.group(1)))
    return [int(found[p]) for p in POSES] if all(p in found for p in POSES) else []


def check(directory: Path) -> list[str]:
    problems: list[str] = []
    conf_path = directory / "art.conf"
    conf = conf_path.read_text() if conf_path.exists() else ""
    if not conf:
        problems.append("art.conf: missing")
    allowed = palette()
    for sprite, (cols, rows, poses, most, optional) in SPRITES.items():
        counts = frames_of(conf, sprite)
        if poses > 1:
            if not counts:
                problems.append(f"art.conf: {sprite} must list {poses} poses, each with its frames")
                counts = [1] * poses
            for pose, n in zip(POSES, counts):
                if not 1 <= n <= most:
                    problems.append(f"art.conf: {sprite} {pose} has {n} frames, not 1 to {most}")
        else:
            counts = [1]
        for res, (fw, fh), across in (("lo", (cols, rows * 2), 1), ("hi", (cols * 8, rows * 16), max(counts))):
            name = f"{sprite}.{res}.png"
            path = directory / name
            if not path.exists():
                if not optional:
                    problems.append(f"{name}: missing")
                continue
            if path.stat().st_size > MAX_BYTES:
                problems.append(f"{name}: larger than 4 MiB")
            im = Image.open(path)
            if im.format != "PNG" or im.mode not in ("P", "RGBA") or im.info.get("interlace"):
                problems.append(f"{name}: must be a non-interlaced PNG, indexed or RGBA, 8 bits a channel")
                continue
            want = (fw * across, fh * len(counts))
            if im.size != want:
                problems.append(f"{name}: is {im.size[0]}x{im.size[1]}, must be {want[0]}x{want[1]}")
                continue
            px = np.asarray(im.convert("RGBA"))
            alpha = px[..., 3]
            if np.any((alpha != 0) & (alpha != 255)):
                problems.append(f"{name}: has half-transparent pixels")
            opaque = px[alpha == 255][:, :3]
            off = {tuple(int(v) for v in c) for c in np.unique(opaque, axis=0)} - allowed
            if off:
                shown = ", ".join("#%02x%02x%02x" % c for c in sorted(off)[:4])
                problems.append(f"{name}: {len(off)} colours outside xterm-240.gpl ({shown})")
            for r in range(len(counts)):
                if not np.any(alpha[r * fh : (r + 1) * fh, 0:fw] == 255):
                    problems.append(f"{name}: the first frame of pose {r} is empty")
    return problems


def main() -> int:
    directory = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT
    problems = check(directory)
    for line in problems:
        print(line, file=sys.stderr)
    if not problems:
        print(f"art in {directory}: ok")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
