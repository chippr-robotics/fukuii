#!/usr/bin/env python3
"""Draws mock screens of the TUI command center from the real palette and the real sprite sheets (spec 029).

A mock is an illustration of the look, at the scale a terminal would show it: 160 × 48 cells of 10 × 20 pixels. It is
not a layout specification, and its numbers are invented. It exists so that the art can be judged where it will be
seen, beside text, in pixel mode and in block mode.

    python3 -I scripts/tui/mock_screen.py OUT_DIR

Needs Pillow, NumPy and the DejaVu Sans Mono font.
"""

from __future__ import annotations

import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parent))
import make_art as art  # noqa: E402

CW, CH, COLS, ROWS = 10, 20, 160, 48
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"
FONT_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf"

BG, LINE, LABEL, TITLE, VALUE, MUTED, WARN, ERROR, ACCENT, OK = 16, 30, 44, 46, 255, 244, 214, 196, 199, 46


def rgb(i: int) -> tuple[int, int, int]:
    return art.xterm_rgb(i)


class Screen:
    def __init__(self) -> None:
        self.im = Image.new("RGB", (COLS * CW, ROWS * CH), rgb(BG))
        self.d = ImageDraw.Draw(self.im)
        self.font = ImageFont.truetype(FONT, 16)
        self.bold = ImageFont.truetype(FONT_BOLD, 16)

    def text(self, col: int, row: int, s: str, fg: int = VALUE, bg: int | None = None, bold: bool = False) -> None:
        for i, ch in enumerate(s):
            x, y = (col + i) * CW, row * CH
            if bg is not None:
                self.d.rectangle([x, y, x + CW - 1, y + CH - 1], fill=rgb(bg))
            if ch != " ":
                self.d.text((x, y), ch, font=self.bold if bold else self.font, fill=rgb(fg))

    def band(self, row: int, fg: int, bg: int) -> None:
        self.d.rectangle([0, row * CH, COLS * CW - 1, (row + 1) * CH - 1], fill=rgb(bg))

    def panel(self, c0: int, r0: int, c1: int, r1: int, title: str, colour: int = LINE) -> None:
        """A box through the middles of its edge cells, as box-drawing characters would draw it."""
        x0, y0 = c0 * CW + CW // 2, r0 * CH + CH // 2
        x1, y1 = c1 * CW + CW // 2, r1 * CH + CH // 2
        self.d.rectangle([x0, y0, x1, y1], outline=rgb(colour))
        self.text(c0 + 2, r0, f" {title} ", TITLE, BG, bold=True)

    def bar(self, col: int, row: int, cells: int, share: float, fg: int, back: int = 236) -> None:
        x, y = col * CW, row * CH + 5
        self.d.rectangle([x, y, x + cells * CW - 1, y + 9], fill=rgb(back))
        self.d.rectangle([x, y, x + int(cells * CW * share) - 1, y + 9], fill=rgb(fg))

    def pixels(self, a: np.ndarray, x: int, y: int, k: int) -> None:
        """Palette indices `a`, each drawn as a k × k square, with (x, y) the top-left in screen pixels."""
        for i in np.unique(a):
            if i == art.T:
                continue
            ys, xs = np.nonzero(a == i)
            for yy, xx in zip(ys, xs):
                self.d.rectangle([x + xx * k, y + yy * k, x + xx * k + k - 1, y + yy * k + k - 1], fill=rgb(int(i)))

    def sprite(self, hi: np.ndarray, lo: np.ndarray, col: int, row: int, cols: int, rows: int, mode: str) -> None:
        """A sprite in its region of cells: `hi` at one sheet pixel per screen pixel, centred; or `lo` as half-blocks."""
        if mode == "pixel":
            h, w = hi.shape
            self.pixels(hi, col * CW + (cols * CW - w) // 2, row * CH + (rows * CH - h) // 2, 1)
        else:
            self.pixels(lo, col * CW, row * CH, CW)


def observatory(rng, cols: int, rows: int, mode: str) -> np.ndarray:
    """Recent blocks: one bar a block, as tall as the gas it used, coloured by its fee level."""
    px = 2 if mode == "pixel" else CW
    w, h = cols * CW // px, rows * CH // px
    a = np.zeros((h, w), dtype=np.uint8)
    n = 64 if mode == "pixel" else w // 2
    bw = w // n
    use = 0.5 + 0.3 * np.sin(np.arange(n) / 5.0) + rng.normal(0, 0.12, n)
    for i, u in enumerate(np.clip(use, 0.05, 1.0)):
        top = int(round((h - 2) * (1 - u)))
        c = 46 if u < 0.6 else (WARN if u < 0.85 else ACCENT)
        a[top : h - 1, i * bw : i * bw + max(1, bw - 1)] = c
        a[top, i * bw : i * bw + max(1, bw - 1)] = 155 if c == 46 else (229 if c == WARN else 218)
    a[h - 1, :] = 30
    a[int((h - 2) * 0.5), ::3] = 23
    return a


def radar(rng, cols: int, rows: int, mode: str) -> np.ndarray:
    """The peer radar: this node in the middle, a blip a peer, further out the slower it answers."""
    px = 2 if mode == "pixel" else CW
    w, h = cols * CW // px, rows * CH // px
    a = np.zeros((h, w), dtype=np.uint8)
    cx, cy, r = w / 2, h / 2, min(w, h) / 2 - 1
    ys, xs = np.mgrid[0:h, 0:w]
    d = np.hypot(xs + 0.5 - cx, ys + 0.5 - cy)
    for ring in (0.33, 0.66, 1.0):
        on = np.abs(d - r * ring) < 0.6
        a[on & (((xs + ys) % 2 == 0) | (ring == 1.0))] = 23 if ring < 1.0 else 30
    a[int(cy), ::2] = np.where(a[int(cy), ::2] == 0, 23, a[int(cy), ::2])
    a[::2, int(cx)] = np.where(a[::2, int(cx)] == 0, 23, a[::2, int(cx)])
    blip = 2 if mode == "pixel" else 1
    for i in range(24 if mode == "pixel" else 14):
        ang, dist = rng.uniform(0, 2 * math.pi), r * rng.beta(2, 3)
        bx, by = int(cx + math.cos(ang) * dist), int(cy + math.sin(ang) * dist)
        c = (46, 46, 46, 82, WARN, 51, ERROR)[i % 7]
        a[max(0, by - blip + 1) : by + blip, max(0, bx - blip + 1) : bx + blip] = c
    a[int(cy) - blip : int(cy) + blip + 1, int(cx) - blip : int(cx) + blip + 1] = 231
    return a


def draw(mode: str, state: str) -> Image.Image:
    s = Screen()
    rng = np.random.default_rng(7)
    sheets = art.sheets()
    warn = state == "warning"

    s.band(0, BG, 34)
    s.text(1, 0, "◆ FUKUII  mordor (63) · PoW · head 14,201,337 · 24/50 peers · up 3d 14h · v0.12.0", BG, 34, bold=True)
    s.text(COLS - 10, 0, "Overview", BG, 34, bold=True)
    if warn:
        s.text(1, 1, "⚠ 1 warning", WARN, bold=True)
        s.text(14, 1, "disk 18.2 GiB free (warn below 20 GiB)", WARN)
    else:
        s.text(1, 1, "✓ no alerts", OK)

    # The sidebar: the network's icon (Mordor is an Ethereum Classic network), the views, the node.
    s.sprite(sheets["network-etc.hi.png"], sheets["network-etc.lo.png"], 2, 2, 16, 8, mode)
    views = ["OVERVIEW", "CHAIN", "PEERS", "MEMPOOL", "RPC", "STORAGE", "SYSTEM", "LOGS", "OPS"]
    for i, v in enumerate(views):
        if i == 0:
            s.text(1, 11 + i, f" [{i + 1}] {v:<11}", BG, 46, bold=True)
        else:
            s.text(1, 11 + i, f" [{i + 1}] ", LABEL)
            s.text(6, 11 + i, v, VALUE)
    for i, (k, v) in enumerate((("NETWORK", "mordor"), ("FAMILY", "PoW"), ("CHAIN ID", "63"), ("SYNC", "at head"),
                                ("GRAPHICS", mode))):
        s.text(2, 22 + i * 2, k, LABEL)
        s.text(2, 23 + i * 2, v, VALUE, bold=True)

    # The Block Observatory.
    s.panel(19, 2, 98, 15, "BLOCK OBSERVATORY")
    chart = observatory(rng, 76, 7, mode)
    s.pixels(chart, 21 * CW, 3 * CH + CH // 2, 2 if mode == "pixel" else CW)
    s.text(21, 11, "BLOCK        AGE   TXS   GAS USED          SIZE    MINER", LABEL)
    for i, (n, age, txs, gas, size, miner) in enumerate((("14,201,337", "4s", "12", 0.31, "2.1 kB", "0x9f3a…c210"),
                                                        ("14,201,336", "17s", "3", 0.08, "0.9 kB", "0x51e2…07bd"),
                                                        ("14,201,335", "31s", "41", 0.92, "7.4 kB", "0x9f3a…c210"))):
        s.text(21, 12 + i, f"{n:<12} {age:<5} {txs:<5}", VALUE)
        s.bar(45, 12 + i, 10, gas, 46 if gas < 0.6 else (WARN if gas < 0.85 else ACCENT))
        s.text(56, 12 + i, f"{int(gas * 100):>3}%   {size:<7} {miner}", VALUE)

    # The head, and health.
    s.panel(100, 2, 158, 11, "CURRENT HEAD")
    for i, (k, v, c) in enumerate((("Block", "14,201,337", OK), ("Hash", "0x7c1e…a94f", VALUE), ("Age", "4s", VALUE),
                                   ("Gas used", "31.0% of 8.0M", VALUE), ("Difficulty", "412.6 T", VALUE),
                                   ("Sync dist", "0", OK), ("Fork", "Spiral  ·  next: Olympia, not scheduled", VALUE))):
        s.text(102, 3 + i, f"{k}:", LABEL)
        s.text(116, 3 + i, v, c, bold=c == OK)
    s.panel(100, 12, 158, 23, "INFRASTRUCTURE HEALTH")
    health = (("P2P", 0.92, "24 of 50 peers", OK), ("CHAIN", 1.0, "head 4s old", OK), ("MINING", 0.80, "118 MH/s", OK),
              ("TXPOOL", 0.71, "286 of 1,000", OK), ("RPC", 0.97, "0.3% errors", OK), ("DATABASE", 0.88, "no stall", OK),
              ("DISK", 0.12 if warn else 0.61, "18.2 GiB free" if warn else "38.4 GiB free", WARN if warn else OK),
              ("MEMORY", 0.55, "heap 68%", OK), ("CPU", 0.74, "32%", OK))
    for i, (k, share, why, c) in enumerate(health):
        s.text(102, 13 + i, f"{k:<9}", LABEL)
        s.bar(112, 13 + i, 20, share, c)
        s.text(134, 13 + i, why, WARN if c == WARN else VALUE, bold=c == WARN)

    # The peer radar and the mempool's tiers.
    s.panel(19, 16, 58, 45, "PEER RADAR")
    rchart = radar(rng, 36, 22, mode)
    s.pixels(rchart, 21 * CW, 17 * CH + CH // 2, 2 if mode == "pixel" else CW)
    s.text(21, 41, "■", 46); s.text(23, 41, "good", VALUE); s.text(29, 41, "■", WARN); s.text(31, 41, "slow", VALUE)
    s.text(37, 41, "■", ERROR); s.text(39, 41, "failing", VALUE); s.text(48, 41, "■", 51); s.text(50, 41, "inbound", VALUE)
    s.text(21, 43, "nearer the centre = answers faster", MUTED)
    s.panel(60, 16, 98, 24, "MEMPOOL RADAR")
    for i, (k, n, share, c) in enumerate((("URGENT", 9, 0.06, ACCENT), ("HIGH", 41, 0.22, WARN), ("NORMAL", 188, 0.9, 46),
                                          ("LOW", 48, 0.26, 37))):
        s.text(62, 18 + i, f"{k:<7}", LABEL)
        s.bar(70, 18 + i, 20, share, c)
        s.text(92, 18 + i, f"{n:>4}", VALUE)
    s.text(62, 22, "286 pending · 4.1 in/s · 3.9 out/s", MUTED)

    # The log and the prompt.
    s.panel(60, 25, 122, 45, "EVENT LOG")
    log = (("14:27:31", "INFO", "Imported new block 14,201,337 (12 txs, 41 ms)"),
           ("14:27:18", "INFO", "Imported new block 14,201,336 (3 txs, 12 ms)"),
           ("14:27:11", "INFO", "Peer 0x8f3a… connected (total: 24)"),
           ("14:27:04", "WARN", "Disk free below 20 GiB" if warn else "Peer 0x22c1… answered late (3.2 s)"),
           ("14:26:58", "INFO", "Imported new block 14,201,335 (41 txs, 118 ms)"),
           ("14:26:40", "INFO", "ops: loglevel root INFO → INFO (cancelled)"),
           ("14:26:12", "INFO", "Mined block 14,201,333"))
    for i, (t, lvl, msg) in enumerate(log):
        s.text(62, 27 + i, t, MUTED)
        s.text(71, 27 + i, lvl, WARN if lvl == "WARN" else OK, bold=True)
        s.text(76, 27 + i, msg, WARN if lvl == "WARN" else VALUE)
    s.text(62, 43, "fukuii>", OK, bold=True)
    s.d.rectangle([70 * CW, 43 * CH + 2, 71 * CW - 1, 44 * CH - 3], fill=rgb(OK))

    # The operator.
    pose = "warning" if warn else "watching"
    row = art.POSES.index(pose)
    hi = sheets["mascot.hi.png"][row * 256 : (row + 1) * 256, 0:256]
    lo = sheets["mascot.lo.png"][row * 32 : (row + 1) * 32, 0:32]
    s.sprite(hi, lo, 125, 25, 32, 16, mode)
    s.text(126, 42, "OPERATOR", LABEL)
    s.text(135, 42, "looking at the alert" if warn else "watching · at head", WARN if warn else OK, bold=True)

    s.band(ROWS - 1, BG, 34)
    s.text(1, ROWS - 1, "Tab panel  ↑↓ select  Enter open  Esc back  : prompt  / filter  ? help  v graphics  d detach  q quit",
           BG, 34, bold=True)
    return s.im


def main() -> int:
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    out.mkdir(parents=True, exist_ok=True)
    draw("pixel", "watching").save(out / "overview-pixel.png", optimize=True)
    draw("block", "warning").save(out / "overview-block.png", optimize=True)
    print(f"wrote 2 mock screens in {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
