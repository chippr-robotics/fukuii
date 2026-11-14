# Contract: sprite sheets

What a sprite sheet for the TUI command center must look like. The loader (plan, slice 2) accepts exactly this and
nothing else. A sheet that breaks a rule is reported at start, by file and rule, and that sprite's region is left to
the text the frame already carries. A bad sheet never stops the node.

**The sheets exist.** They are in `src/main/resources/tui/art/`, drawn by `scripts/tui/make_art.py` (spec D-3: the
art is made in this spec's own work). The art is code: to change a sprite, change the script and run it. Enlarged
views are in [`../art-preview/`](../art-preview/): `mascot.png` (every pose and frame), `block-mode.png` (the small
sizes), `badge-and-splash.png`, `network-icons.png`, and two mock screens that show the art at the scale a terminal draws it,
`overview-pixel.png` and `overview-block.png`. Anyone replacing the art, by editing the script or by drawing sheets
by hand, keeps to the rules below.

## The short version

- PNG, 8 bits per channel, not interlaced. Indexed colour or RGBA.
- Every visible pixel is one of the 240 colours in [`xterm-240.gpl`](./xterm-240.gpl). Load it as the palette in
  Aseprite, LibreSprite, GIMP or Krita.
- A pixel is fully transparent or fully opaque. No soft edges, no anti-aliasing.
- Each sprite comes at two sizes: `lo` for block mode, `hi` for pixel mode.
- Poses run down the sheet in a fixed order. In a `hi` sheet the frames of a pose run left to right; a `lo` sheet has
  one frame a pose, because block mode does not cycle frames (spec FR-017).
- A manifest, `art.conf`, says how many frames each pose has and how long each stays up.

## Why these rules

**The 240 colours.** They are xterm-256 colours 16 to 255: the 6×6×6 cube (each channel one of 0, 95, 135, 175, 215,
255) and 24 greys. Every 256-colour terminal draws them the same, so block mode shows the colours you drew, exactly
(research §3 E2). Colours 0 to 15 are left out because terminals let users redefine them.

**Two sizes.** In block mode one character cell is two pixels, one above the other, so a region of *C* columns and *R*
rows is *C* × 2*R* pixels. That is a small canvas, and scaling a large drawing down to it looks bad, so it is drawn
at that size, not scaled down. In pixel mode the canvas is 8 × 16 pixels per cell, the VGA text cell. The TUI enlarges
a `hi` frame by a whole factor (×1 on most terminals, ×2 or more on high-density displays) and centres it, so every
pixel stays a sharp square. The shipped `hi` sheets are drawn at a quarter of that resolution and enlarged four
times, which gives the chunky look of the period: the large mascot is 64 × 64 art pixels.

**Hard edges.** A half-transparent pixel has no meaning in block mode, and in pixel mode it would be blended against
a background you do not control.

## Sprites

| Sprite | Region in cells | `lo` frame | `hi` frame | Poses | Where it shows |
| --- | --- | --- | --- | --- | --- |
| `mascot` | 32 × 16 | 32 × 32 px | 256 × 256 px | 7, up to 4 frames each | Overview at 140 columns and more; Ops; the splash |
| `mascot-small` | 16 × 8 | 16 × 16 px | 128 × 128 px | 7, up to 2 frames each | Overview from 100 to 139 columns |
| `network-etc`, `network-eth` | 16 × 8 | 16 × 16 px | 128 × 128 px | 1 | The top of the sidebar: the chain family the node is on (spec FR-061) |
| `badge` | 16 × 8 | 16 × 16 px | 128 × 128 px | 1 | Help, Ops |
| `badge-large` | 32 × 16 | 32 × 32 px | 256 × 256 px | 1 | The splash; carries the FUKUII wordmark |
| `splash-scene` *(optional)* | 80 × 24 | 80 × 48 px | 640 × 384 px | 1 | Behind the splash; never changes |

Below 100 columns or 32 rows no sprite is drawn; charts still are.

## Poses

Rows of a `mascot` or `mascot-small` sheet, top to bottom. The pose is chosen by the node's state and by nothing
else (spec FR-018).

| Row | Pose | The node is | Suggestion |
| --- | --- | --- | --- |
| 0 | `starting` | starting up | switching the console on |
| 1 | `syncing` | syncing | typing; the code plays this loop faster as the import rate rises |
| 2 | `watching` | at the head, no alerts | watching the screens; one frame can be a glance to the side |
| 3 | `warning` | under a warning | looking up at the alert row |
| 4 | `error` | under an error | standing, alarm lamp lit |
| 5 | `asleep` | stalled, or a source is not answering | asleep at the console |
| 6 | `celebrating` | at the head for the first time | once per run, three seconds |

A pose's frames follow its `timeline` in `art.conf`: pairs of (frame, how many quarter-seconds it stays up), played in
a loop. `watching` is `[[0, 15], [1, 1]]`: eyes open for 3.75 s, a blink for 0.25 s. The code may play `syncing`
faster or slower with the import rate. In block mode, and under reduced motion, only the first frame of a pose is
shown, so the first frame must read on its own.

## Sheet layout

A sheet is a grid with no gaps and no margins. A `hi` sheet's width is the frame width times the largest frame
count; a `lo` sheet is one frame wide. The height is the frame height times the number of poses. Cells beyond a
pose's frame count are left fully transparent.

Example: `mascot.hi.png` with up to 4 frames is 1024 × 1792; `mascot.lo.png` is 32 × 224. A one-pose sprite is just
its frame: `badge.hi.png` is 128 × 128.

## Files

```text
art.conf
mascot.lo.png          mascot.hi.png
mascot-small.lo.png    mascot-small.hi.png
badge.lo.png           badge.hi.png
network-etc.lo.png     network-etc.hi.png
network-eth.lo.png     network-eth.hi.png
badge-large.lo.png     badge-large.hi.png
splash-scene.lo.png    splash-scene.hi.png      (optional)
```

`art.conf`:

```hocon
art {
  mascot {
    cols = 32, rows = 16
    poses = [
      { name = starting,    frames = 2, timeline = [[0, 2], [1, 2]] }
      { name = syncing,     frames = 4, timeline = [[0, 1], [1, 1], [2, 1], [3, 1]] }
      { name = watching,    frames = 2, timeline = [[0, 15], [1, 1]] }
      # … one entry per pose, in the order of the table above
    ]
  }
  badge        { cols = 16, rows = 8 }     # one pose, one frame: no `poses`
  splash-scene { cols = 80, rows = 24 }    # leave the entry out if there is no scene
}
```

A sprite left out of `art.conf`, or whose files are missing or invalid, is not drawn: its region shows the text
equivalent the frame carries in every mode (spec FR-001).

## Limits the loader enforces

| Rule | Limit |
| --- | --- |
| Format | PNG, bit depth 8, colour type 3 (indexed, with `tRNS` for transparency) or 6 (RGBA), no interlace |
| Dimensions | exactly frame size × grid, as above: `hi` is as many frames wide as the pose with the most; `lo` is one frame wide |
| Colours | every pixel with alpha 255 has an RGB value from `xterm-240.gpl` |
| Alpha | 0 or 255 only |
| Frames | each count in `art.conf` between 1 and 4 (2 for `mascot-small`); every timeline entry names a frame that exists and a duration of 1 to 240; the first frame of every pose has at least one opaque pixel |
| File size | at most 4 MiB a file |

## Background

The TUI does not paint behind a sprite. Transparent pixels show the panel's background, which is near-black in the
command-center theme and is the terminal's own background in others. Give a sprite its own outline and do not rely
on a black background for contrast.

Colours the interface itself uses, for sprites that should sit well beside it:

| Use | xterm index | RGB |
| --- | --- | --- |
| Panel background | 16 | `#000000` |
| Panel lines | 30, 37 | `#008787`, `#00afaf` |
| Labels | 44, 51 | `#00d7d7`, `#00ffff` |
| Titles, healthy | 46, 82 | `#00ff00`, `#5fff00` |
| Values | 255 | `#eeeeee` |
| Warning | 214, 220 | `#ffaf00`, `#ffd700` |
| Error | 196 | `#ff0000` |
| Accent | 199 | `#ff00af` |
| Mantis greens | 22, 28, 34, 40, 46, 118 | `#005f00`, `#008700`, `#00af00`, `#00d700`, `#00ff00`, `#87ff00` |
| Worm pinks | 205, 211, 218 | `#ff5faf`, `#ff87af`, `#ffafd7` |
| Greys | 233, 238, 244, 250 | `#121212`, `#444444`, `#808080`, `#bcbcbc` |

## Making and checking the art

- `python3 -I scripts/tui/make_art.py` redraws every sheet and `art.conf`; `--check` fails when the committed files
  are not what it draws; `--preview DIR` writes the enlarged views.
- `python3 -I scripts/tui/check_art.py [DIRECTORY]` checks every rule in the table above. It shares no code with
  the drawing script.
- `python3 -I scripts/tui/mock_screen.py DIR` draws the two mock screens from the real sheets.
- `python3 -I -m unittest discover -s scripts/tui -v` runs both checks and the checker's own tests; the `TUI Art`
  workflow runs it on every pull request that touches the art or the scripts.
- Once the loader exists (plan, slice 2): `FUKUII_TUI_ART_DIR=<directory> fukuii mordor --tui`, then `:art` at the
  prompt, shows every sprite, pose and frame in the current graphics mode from another directory, and `r` reloads
  it. That is how a hand-drawn replacement is tried.

## Text

A sprite carries no text. The FUKUII wordmark stays in character cells, where it is spelt by the code and readable in
every mode. A wordmark may appear inside `badge-large` as drawn art; it must read `FUKUII`.
