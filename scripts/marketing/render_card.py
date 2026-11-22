#!/usr/bin/env python3
"""Render a fukuii announcement card (1200x675 SVG, optional PNG) from a JSON spec.

Usage:
    python3 -I scripts/marketing/render_card.py marketing/announcements/v0.12.1/card.json
    python3 -I scripts/marketing/render_card.py card.json --png        # also write card.png next to it
    python3 -I scripts/marketing/render_card.py card.json --check      # validate line lengths only

The SVG is written next to the JSON as card.svg. SVG text does not wrap, so the
script refuses lines longer than the layout holds (see LIMITS) instead of
rendering text that runs out of its box.

card.json:
{
  "kicker":    "CHIPPR ROBOTICS · RELEASE",          # optional
  "title":     "fukuii",
  "highlight": "v0.12.1",                            # drawn in green after the title
  "subtitle":  "Multi-network EVM client · ETC · ...",
  "tiles": [                                         # exactly 4: the headline items
    {"heading": "TUI COMMAND CENTER", "lines": ["...", "..."], "color": "green"}
  ],
  "groundwork": {"heading": "GROUNDWORK", "lines": ["...", "...", "..."]},   # optional side box
  "footer_left":  "github.com/chippr-robotics/fukuii/releases/tag/v0.12.1",
  "footer_right": "Scala 3 · JDK 25"
}

Colors: green, blue, orange, purple, amber (or any #rrggbb).

Discord does not preview an attached SVG, so post the PNG. --png tries, in order:
Playwright (node, with its Chromium), rsvg-convert, inkscape.
"""

from __future__ import annotations

import argparse
import base64
import io
import json
import shutil
import subprocess
import sys
from html import escape
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
LOGO = REPO / "docs" / "images" / "fukuii-hex-logo.png"

PALETTE = {
    "green": "#7ee787",
    "blue": "#79c0ff",
    "orange": "#ffa657",
    "purple": "#d2a8ff",
    "amber": "#f2cc60",
}

# Characters each slot holds in the monospace face at its size (measured on the 0.12.1 card).
LIMITS = {
    "title+highlight": 18,
    "subtitle": 72,
    "tile.heading": 26,
    "tile.line": 37,
    "groundwork.heading": 20,
    "groundwork.line": 27,
    "footer_left": 90,
    "footer_right": 40,
}


def color(name: str) -> str:
    if name.startswith("#") and len(name) == 7:
        return name
    if name not in PALETTE:
        raise SystemExit(f"unknown color {name!r}; use one of {sorted(PALETTE)} or #rrggbb")
    return PALETTE[name]


def check(spec: dict) -> list[str]:
    errors: list[str] = []

    def lim(key: str, text: str, where: str) -> None:
        if len(text) > LIMITS[key]:
            errors.append(f"{where}: {len(text)} chars > {LIMITS[key]}: {text!r}")

    lim("title+highlight", spec["title"] + " " + spec.get("highlight", ""), "title")
    lim("subtitle", spec.get("subtitle", ""), "subtitle")
    tiles = spec.get("tiles", [])
    if len(tiles) != 4:
        errors.append(f"tiles: need exactly 4, got {len(tiles)}")
    for i, t in enumerate(tiles):
        lim("tile.heading", t["heading"], f"tiles[{i}].heading")
        if len(t["lines"]) != 2:
            errors.append(f"tiles[{i}].lines: need exactly 2, got {len(t['lines'])}")
        for j, line in enumerate(t["lines"]):
            lim("tile.line", line, f"tiles[{i}].lines[{j}]")
    gw = spec.get("groundwork")
    if gw:
        lim("groundwork.heading", gw["heading"], "groundwork.heading")
        if not 1 <= len(gw["lines"]) <= 3:
            errors.append(f"groundwork.lines: need 1 to 3, got {len(gw['lines'])}")
        for j, line in enumerate(gw["lines"]):
            lim("groundwork.line", line, f"groundwork.lines[{j}]")
    lim("footer_left", spec.get("footer_left", ""), "footer_left")
    lim("footer_right", spec.get("footer_right", ""), "footer_right")
    return errors


def logo_b64() -> str:
    raw = LOGO.read_bytes()
    try:
        from PIL import Image  # optional: shrinks the embedded logo from ~260 KB to ~110 KB

        im = Image.open(io.BytesIO(raw)).convert("RGBA").resize((320, 320), Image.LANCZOS)
        buf = io.BytesIO()
        im.save(buf, format="PNG", optimize=True)
        raw = buf.getvalue()
    except ImportError:
        pass
    return base64.b64encode(raw).decode("ascii")


def tile(i: int, t: dict) -> str:
    x = 60 + (i % 2) * 400
    y = 262 + (i // 2) * 170
    c = color(t.get("color", "green"))
    l1, l2 = (escape(s) for s in t["lines"])
    return f"""<g transform="translate({x},{y})">
  <rect width="380" height="148" rx="14" fill="#0f1a14" stroke="{c}" stroke-opacity="0.55" stroke-width="1.5"/>
  <rect x="0" y="0" width="6" height="148" rx="3" fill="{c}"/>
  <text x="26" y="46" class="h" fill="{c}">{escape(t["heading"])}</text>
  <text x="26" y="86" class="b">{l1}</text>
  <text x="26" y="114" class="b">{l2}</text>
</g>"""


def groundwork(gw: dict | None) -> str:
    if not gw:
        return ""
    lines = "".join(
        f'\n  <text x="22" y="{72 + 24 * j}" class="b">{escape(s)}</text>' for j, s in enumerate(gw["lines"])
    )
    return f"""<g transform="translate(860,492)">
  <rect width="290" height="140" rx="14" fill="#1a1408" stroke="#f2cc60" stroke-opacity="0.6" stroke-width="1.5" stroke-dasharray="6 5"/>
  <text x="22" y="40" class="h" fill="#f2cc60">{escape(gw["heading"])}</text>{lines}
</g>"""


def render(spec: dict) -> str:
    b64 = logo_b64()
    kicker = escape(spec.get("kicker", "CHIPPR ROBOTICS · RELEASE"))
    tiles = "\n".join(tile(i, t) for i, t in enumerate(spec["tiles"]))
    return f"""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="1200" height="675" viewBox="0 0 1200 675">
<defs>
  <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="#07110b"/><stop offset="1" stop-color="#0d2317"/>
  </linearGradient>
  <radialGradient id="glow" cx="0.82" cy="0.38" r="0.45">
    <stop offset="0" stop-color="#3fb950" stop-opacity="0.35"/><stop offset="1" stop-color="#3fb950" stop-opacity="0"/>
  </radialGradient>
  <pattern id="hex" width="56" height="97" patternUnits="userSpaceOnUse" patternTransform="scale(0.6)">
    <path d="M28 0 L56 16 L56 48 L28 64 L0 48 L0 16 Z M28 64 L28 97" fill="none" stroke="#3fb950" stroke-opacity="0.07" stroke-width="1.5"/>
  </pattern>
  <style>
    text {{ font-family: 'JetBrains Mono','Fira Code','DejaVu Sans Mono',Menlo,Consolas,monospace; }}
    .h {{ font-size: 19px; font-weight: 700; letter-spacing: 1.5px; }}
    .b {{ font-size: 15px; fill: #c9d6cd; }}
  </style>
</defs>
<rect width="1200" height="675" fill="url(#bg)"/>
<rect width="1200" height="675" fill="url(#hex)"/>
<rect width="1200" height="675" fill="url(#glow)"/>

<text x="60" y="92" fill="#7ee787" style="font-size:20px;letter-spacing:4px;font-weight:700">{kicker}</text>
<text x="56" y="178" fill="#f0f6f2" style="font-size:88px;font-weight:800">{escape(spec["title"])} <tspan fill="#3fb950">{escape(spec.get("highlight", ""))}</tspan></text>
<text x="60" y="220" fill="#9fb3a6" style="font-size:18px">{escape(spec.get("subtitle", ""))}</text>

{tiles}

<g transform="translate(862,200)">
  <image x="0" y="0" width="280" height="280" href="data:image/png;base64,{b64}" xlink:href="data:image/png;base64,{b64}"/>
</g>

{groundwork(spec.get("groundwork"))}

<rect x="0" y="655" width="1200" height="20" fill="#3fb950" fill-opacity="0.12"/>
<text x="60" y="669" fill="#7d9585" style="font-size:12px;letter-spacing:1px">{escape(spec.get("footer_left", ""))}</text>
<text x="1140" y="669" fill="#7d9585" text-anchor="end" style="font-size:12px;letter-spacing:1px">{escape(spec.get("footer_right", ""))}</text>
</svg>
"""


def to_png(svg: Path, png: Path) -> str:
    """Rasterise at 2x (2400x1350). Returns the tool used; exits if none is available."""
    if shutil.which("node"):
        js = (
            "const {chromium}=require('playwright');(async()=>{const b=await chromium.launch();"
            "const p=await b.newPage({viewport:{width:1200,height:675},deviceScaleFactor:2});"
            f"await p.goto({json.dumps(svg.resolve().as_uri())});"
            f"await p.screenshot({{path:{json.dumps(str(png))}}});await b.close();}})()"
        )
        if subprocess.run(["node", "-e", js], capture_output=True).returncode == 0:
            return "playwright"
    if shutil.which("rsvg-convert"):
        subprocess.run(["rsvg-convert", "-z", "2", "-o", str(png), str(svg)], check=True)
        return "rsvg-convert"
    if shutil.which("inkscape"):
        subprocess.run(
            ["inkscape", str(svg), "--export-type=png", f"--export-filename={png}", "-w", "2400"], check=True
        )
        return "inkscape"
    raise SystemExit("no rasteriser found: install Playwright (node), librsvg (rsvg-convert) or inkscape")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("spec", type=Path, help="card.json")
    ap.add_argument("--png", action="store_true", help="also write card.png (2400x1350)")
    ap.add_argument("--check", action="store_true", help="validate line lengths and exit")
    args = ap.parse_args()

    spec = json.loads(args.spec.read_text(encoding="utf-8"))
    errors = check(spec)
    if errors:
        print("card.json does not fit the layout:", file=sys.stderr)
        for e in errors:
            print(f"  {e}", file=sys.stderr)
        return 1
    if args.check:
        print("card.json fits the layout")
        return 0

    svg = args.spec.with_name("card.svg")
    svg.write_text(render(spec), encoding="utf-8")
    print(f"wrote {svg}")
    if args.png:
        png = args.spec.with_name("card.png")
        tool = to_png(svg, png)
        print(f"wrote {png} ({tool})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
