# Contract: sprite sheets

What a sprite sheet for the TUI command center must look like. The user draws the sheets (spec D-3); the loader
(plan, slice 2) accepts exactly this and nothing else. A sheet that breaks a rule is reported at start, by file and
rule, and the built-in placeholder for that sprite is drawn instead. A bad sheet never stops the node.

## The short version

- PNG, 8 bits per channel, not interlaced. Indexed colour or RGBA.
- Every visible pixel is one of the 240 colours in [`xterm-240.gpl`](./xterm-240.gpl). Load it as the palette in
  Aseprite, LibreSprite, GIMP or Krita.
- A pixel is fully transparent or fully opaque. No soft edges, no anti-aliasing.
- Each sprite comes at two sizes: `lo` for block mode, `hi` for pixel mode.
- Poses run down the sheet in a fixed order. Frames of a pose run left to right.
- A manifest, `art.conf`, says how many frames each pose has.

## Why these rules

**The 240 colours.** They are xterm-256 colours 16 to 255: the 6×6×6 cube (each channel one of 0, 95, 135, 175, 215,
255) and 24 greys. Every 256-colour terminal draws them the same, so block mode shows the colours you drew, exactly
(research §3 E2). Colours 0 to 15 are left out because terminals let users redefine them.

**Two sizes.** In block mode one character cell is two pixels, one above the other, so a region of *C* columns and *R*
rows is *C* × 2*R* pixels. That is a small canvas, and scaling a large drawing down to it looks bad, so it is drawn
by hand. In pixel mode the canvas is 8 × 16 pixels per cell, the VGA text cell. The TUI enlarges a `hi` frame by a
whole factor (×1 on most terminals, ×2 or more on high-density displays) and centres it, so every pixel stays a sharp
square.

**Hard edges.** A half-transparent pixel has no meaning in block mode, and in pixel mode it would be blended against
a background you do not control.

## Sprites

| Sprite | Region in cells | `lo` frame | `hi` frame | Poses | Where it shows |
| --- | --- | --- | --- | --- | --- |
| `mascot` | 32 × 16 | 32 × 32 px | 256 × 256 px | 7, up to 4 frames each | Overview at 140 columns and more; Ops; the splash |
| `mascot-small` | 16 × 8 | 16 × 16 px | 128 × 128 px | 7, up to 2 frames each | Overview from 100 to 139 columns |
| `badge` | 16 × 8 | 16 × 16 px | 128 × 128 px | 1 | The header at 140 columns and more; Help |
| `badge-large` | 40 × 20 | 40 × 40 px | 320 × 320 px | 1 | The splash |
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

Frames play at four a second and loop. In block mode, and under reduced motion, only the first frame of a pose is
shown, so the first frame must read on its own.

## Sheet layout

A sheet is a grid with no gaps and no margins. Its width is the frame width times the largest frame count; its
height is the frame height times the number of poses. Cells beyond a pose's frame count are left fully transparent.

Example: `mascot.hi.png` with up to 4 frames is 1024 × 1792; `mascot.lo.png` is 128 × 224. A one-pose, one-frame
sprite is just its frame: `badge.hi.png` is 128 × 128.

## Files

```text
art.conf
mascot.lo.png          mascot.hi.png
mascot-small.lo.png    mascot-small.hi.png
badge.lo.png           badge.hi.png
badge-large.lo.png     badge-large.hi.png
splash-scene.lo.png    splash-scene.hi.png      (optional)
```

`art.conf`:

```hocon
art {
  mascot       { frames = [2, 4, 2, 2, 2, 2, 4] }   # one count per pose, rows 0 to 6
  mascot-small { frames = [1, 2, 1, 1, 1, 1, 2] }
  badge        { frames = [1] }
  badge-large  { frames = [1] }
  splash-scene { frames = [1] }                      # leave the entry out if there is no scene
}
```

A sprite left out of `art.conf`, or whose files are missing, uses the placeholder. Sprites can be delivered one at a
time.

## Limits the loader enforces

| Rule | Limit |
| --- | --- |
| Format | PNG, bit depth 8, colour type 3 (indexed, with `tRNS` for transparency) or 6 (RGBA), no interlace |
| Dimensions | exactly frame size × grid, as above |
| Colours | every pixel with alpha 255 has an RGB value from `xterm-240.gpl` |
| Alpha | 0 or 255 only |
| Frames | each count in `art.conf` between 1 and 4 (2 for `mascot-small`); the first frame of every pose has at least one opaque pixel |
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

## Trying a sheet

- `python3 -I scripts/tui/check_art.py <directory>` checks every rule in the table above without starting a node
  (plan, slice 2, first task).
- `FUKUII_TUI_ART_DIR=<directory> fukuii mordor --tui`, then `:art` at the prompt, shows every sprite, pose and frame
  in the current graphics mode. `r` reloads the directory, so a sheet can be edited and looked at again without
  restarting.
- Sheets that are accepted go in `src/main/resources/tui/art/` in a PR of their own.

## Text

A sprite carries no text. The FUKUII wordmark stays in character cells, where it is spelt by the code and readable in
every mode. A wordmark may appear inside `badge-large` as drawn art; it must read `FUKUII`.
