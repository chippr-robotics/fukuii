# Research: TUI Command Center

Design record for [spec.md](./spec.md). Written 2026-11-13 against `staging` at `9c2cadd` (0.11.74).

It holds what the spec leaves out: what the three concept images show, what spec 027 already built, the experiments
run for this spec, the Kitty graphics protocol facts it relies on, where every requested datum comes from, and the
options behind each design choice. File and line references are to `src/main/scala/com/chipprbots/ethereum/` unless a
path says otherwise.

**How far to trust each part**

| Part | Basis |
| --- | --- |
| §3 experiments | Run in this session against `jline-3.30.13.jar` on JDK 21. Reproducible; sources in §3.7. |
| §4 protocol facts | Read from the Kitty graphics protocol page on 2026-11-13 through a summarising fetch: paraphrase, not quotation. Confirm each key against the page when writing the encoder. |
| §6 inventory | Three read-only sweeps of the tree at `9c2cadd`. Line numbers drift; re-check before code. |
| A real pixel terminal | **Not tested.** This session has no display. Nothing here has been seen in kitty or Ghostty. The spec's manual gate (SC-014) is the first real test. |

---

## 1. The brief and the three images

The user's brief (2026-11-13) asks for "a 1980s arcade-style Network Operations Center combined with a fully functional
Ethereum execution-client control terminal", with one rule above the rest: **it should look like a video game and
behave like serious infrastructure software; the game aesthetic is the interface, not a simulation of the underlying
functionality.**

The images are in [`mockups/`](./mockups/) (JPEG copies of the originals). They are generated concept art, not
screenshots and not sprite sheets.

| File | What it shows |
| --- | --- |
| `command-center.jpg` | The dashboard. Header band: mascot tile, `FUKUII / ETHEREUM EXECUTION NODE`, network, status, uptime, an ONLINE lamp. Left: a numbered menu, `[1] OVERVIEW` to `[9] SETTINGS` (Chain, Peers, Mempool, RPC / Engine, Storage, Metrics, Logs). Centre: "Network topology", a dot-matrix world map with peers joined by arcs. Right of it: "Current head" (block, slot, gas used with a bar, base fee, peers, sync distance). Far right: six meters with bar histories (CPU, memory, disk I/O, network RX/TX, peer health, RPC requests per second). Bottom left: an event log and a `fukuii>` prompt. Bottom right: the mascot at a keyboard in a server room. Footer: `F1 HELP · TAB NAVIGATE · ENTER SELECT · ? COMMANDS`. |
| `boot-splash.jpg` | The splash. The mascot reclines in a cockpit over a planet; a "boot sequence" checklist with a progress bar; small console screens. |
| `badge.jpg` | The hexagonal mantis-and-worm badge with the FUKUII wordmark. `docs/images/fukuii-hex-logo.png` is an earlier 500×500 version. |

**What the images get right, and the spec keeps:** the palette (green, cyan, amber, magenta on near-black), boxed panels
with pixel-font titles, bar histories beside each number, a numbered menu, a mascot who is the operator, a prompt, and
key hints in the footer.

**What the images show that the node cannot back with data.** The brief's own rule excludes these as drawn:

1. **Arcs between peers.** An execution client knows its own connections. It does not know which of its peers are
   connected to each other. The honest topology is a star: this node in the middle, one edge per peer.
2. **A world map.** Nothing in the tree maps an address to a place: no GeoIP code, no database, no dependency (§6.6).
   A map needs a bundled data file with a licence, and it goes stale. See decision D-2 (§9).
3. **`Slot`.** The execution client has no slot clock: no beacon genesis time, no seconds-per-slot (§6.1). A header
   carries a slot number only from Amsterdam (EIP-7843). On ETC there is no slot at all.
4. **`Syncing headers… (99.8%)` under `STATUS: SYNCED`.** The mockup's log and status disagree. Real panels read one
   source.
5. **`Peer health 99%`.** There is no such number today. §8.3 defines what a health bar may mean.
6. **The splash's checklist** ("Loading terminal graphics…", "Almost there…") is scripted text. The node has real
   startup phases (`nodebuilder/StdNode.scala:70-133`); the splash shows those.
7. **`ETHEREUM EXECUTION NODE`.** fukuii runs two chain families. The header names the network and its family, as
   spec 027's header does.

The wordmarks in the generated images are misspelt ("EXECCUTION", "Initialiing", "FUKUIII"). `badge.jpg` has about
100,000 distinct colours and no regular pixel grid (run lengths cluster at 9–13 px with noise), so it is pixel-art
*style*, not pixel art. Usable sprites have to be drawn, or traced and cleaned by hand. See decision D-3.

---

## 2. What spec 027 built, and the rules this spec changes

Spec 027 ("the worm goes to the brain") is Active. The code of all six slices is in the tree at `9c2cadd`, and its one
manual gate run is open as B-189. It is the "basic wiring" the brief refers to:

| Piece | Where | What it guarantees |
| --- | --- | --- |
| Frame loop | `console/TuiUpdater.scala:76-92` | `nextFrame → render → checkInput(pace)`; the key wait is the pacing: 250 ms while anything animates, 1000 ms otherwise. |
| Renderer | `console/TuiRenderer.scala:25-93` | A pure function of (state, width, height, theme). Exactly `height` rows of `width` columns. Header, alert row, panels, footer. |
| Layout | `console/Layout.scala:39-94` | Panels with minimum and preferred rows and a priority; two-column cells at tier L. |
| Probe | `console/TuiProbeThread.scala`, `TuiStatusProbe.scala:68-164` | 1 Hz on its own thread, asks in parallel with one bounded wait, per-source freshness (`Sourced`). |
| Views | `console/TuiView.scala:13-18` | Overview, Sync, Peers, Chain, Logs on `1`–`5`; Help over any view. |
| Input | `console/Tui.scala:263-360` | One lower-cased character per read. An escape sequence is read whole and **dropped** (`drainEscape`, `:493-505`). |
| Output | `console/Tui.scala:227-256` | The whole frame is rewritten from the home position when it differs from the last; nothing is written otherwise. |
| Glyphs, theme | `console/TuiGlyphs.scala`, `TuiTheme.scala` | Emoji and ASCII sets with declared widths; 16-colour, 256-colour and monochrome themes. |
| Log ring, sanitiser | `console/TuiLogRingAppender.scala`, `Sanitize.scala` | The last 1,000 INFO+ lines; every outside string stripped of control characters. |
| Tests | `src/test/scala/.../console/` | Golden frames, a width property, an in-memory xterm (`TuiTestTerminal`). |

Four of 027's rules are in this spec's way. Each had a reason. This is what changes and why.

**R1. "No arrow keys or other escape sequences as input" (027 non-goal; research §2.2).**
The reason given: input is one character with a bounded timeout, and "a half-read escape sequence would be
mis-dispatched as `[`". The danger is real: the tail of Left-arrow is `D` (Detach), of Home is `H` (Help). The fix
then was to refuse the whole class. Experiments E3 and E4 (§3) show the class can be decoded safely instead: a sequence
split across two writes still decodes as one key, and a lone Esc followed later by `D` is Esc and then `D`, never
Left. What 027 guarded against stays guarded as a requirement (spec FR-004): no sequence is ever dispatched as its
characters. B-226 (the drain's fixed 50 ms tail) is subsumed by the decoder.

**R2. "No panel may change node behaviour" (027 FR-025).**
It stays true of every panel. Operator actions are a separate thing with their own rules (spec FR-047 to FR-058):
named, previewed, confirmed, logged, and able to be switched off as a whole. A panel still only reads.

**R3. Views on `1`–`5`, `Tab` cycles views.**
The command center has nine views and panels that take focus. `Tab` moves focus, as the mockup's footer says; the
number keys and the menu choose views. 027's UAT has not run, so no operator habit is broken. The documented keys
change in `docs/architecture/console-ui.md`.

**R4. `q` stops the node at once.**
`Tui.scala:342-344` returns `Quit` on a bare `q`; `StdNode.scala:345` then calls `sys.exit(0)`. Today that is the only
key that acts on the node, and it has no confirmation. Once the TUI takes typed commands and pasted text, one stray
`q` stopping a validator's execution client is not acceptable (spec FR-049, FR-009).

Rules of 027 this spec keeps without change: no new dependency; no new RPC method; no mouse; nothing under
`consensus/`, `vm/`, `ledger/`, `mpt/`, `rlp/`, `crypto/` or `domain/` changes behaviour; every external string is
sanitised; no `Thread.sleep`; one heavy JVM at a time on the build host.

---

## 3. Experiments (JLine 3.30.13, JDK 21)

JLine 3.30.13 is the pinned version (`project/Dependencies.scala:164`; 4.x is deferred). Its jar has no graphics
support of any kind (no Sixel, Kitty or image class), and `org.jline.terminal.Size` has rows and columns only, no
pixels. Everything below is plain escape sequences through `Terminal.writer()` and `Terminal.reader()`.

### E1. Image placeholder cells measure as ordinary cells

The Kitty protocol can draw an image through text: cells holding U+10EEEE, with combining marks for row and column and
the image id in the foreground colour (§4.6).

| Check | Result |
| --- | --- |
| `WCWidth.wcwidth(0x10EEEE)` | 1 |
| The first 210 row/column marks of the protocol's list (U+0305, U+030D, U+030E, … U+20F0) | all width 0 |
| `columnLength` of three placeholder cells, the first with two marks | 3 |
| `columnSubSequence(0, 1)` of that string | keeps the cell and both its marks |

So a placeholder image fits 027's frame contract with no special case: `fitToWidth`, the width property and the
goldens treat it as text. The mark list was typed from memory; the encoder must take it from the protocol's
`rowcolumn-diacritics.txt` and a test must assert width 0 for every entry (as 027 SC-006 does for glyphs).

### E2. Colours: indexed is exact, RGB is rounded

| Style | `toAnsi(256, …)` |
| --- | --- |
| `foreground(42)` | `ESC[38;5;42m` |
| `foreground(1)`, `foreground(9)` | `ESC[31m`, `ESC[91m` (the 16 base colours use the short forms) |
| `foreground(0x12, 0x34, 0x56)` | `ESC[38;5;24m` (rounded to the 256 palette) |
| the same at 16M colours | `ESC[38;2;18;52;86m` |
| `▀` with `foreground(46).background(21)` | one column, `ESC[38;5;46;48;5;21m` |

`AttributedString.toAnsi(terminal)` takes the colour count from terminfo `max_colors`. `xterm-256color`,
`xterm-kitty` and `tmux-256color` all report 256, so an RGB style is rounded on nearly every terminal fukuii meets.

Consequence: **the art palette is a fixed set of xterm-256 indices from 16 up.** Then a half-block cell (`▀` with a
foreground and a background) draws two art pixels exactly on any 256-colour terminal, and a placeholder cell's
foreground carries an image id from 16 to 255 unambiguously. No part of the design needs 24-bit colour.

### E3. Arrow, function and editing keys decode correctly

`KeyMap` bound from terminfo (`KeyMap.key(terminal, Capability.key_up)` and friends) and read with `BindingReader`,
on an in-memory `xterm-256color` terminal in raw mode:

| Input bytes | Decoded |
| --- | --- |
| `ESC O A`, `ESC [ A` | Up (both the application and the normal form, once both are bound) |
| `ESC [ D`, `ESC O P`, `ESC [ 5 ~`, `ESC [ Z`, `CR` | Left, F1, Page Up, Shift-Tab, Enter |
| `ESC`, then nothing | Esc, after about 100 ms (ambiguity timeout set to 50 ms) |
| `ESC`, pause, then `D` | Esc, then the character `D`. Never Left. |
| `ESC`, 10 ms, `[D` | Left: a sequence split across writes is still one key |

### E4. An unbound sequence leaks one character at a time

Feeding a reply the key map does not know:

| Input | What `BindingReader.readBinding` returned |
| --- | --- |
| `ESC [ 6 ; 20 ; 10 t` (a cell-size report) | `ESC`, `[`, `6`, `;`, `2`, `0`, `;`, `1`, `0`, `t` as ten separate keys |
| `ESC _ G i=31;OK ESC \` (a graphics reply) | `ESC`, `_`, `G`, `i`, `=`, `3`, `1`, `;`, `O`, `K`, `ESC`, `\` |

With number keys bound to views, the first reply would switch views three times. So `BindingReader` alone is not safe
once the TUI asks the terminal questions. Two rules follow:

1. **A framing layer sits under the key map.** It recognises a whole CSI, SS3, APC, DCS or OSC sequence by its
   grammar, hands a complete sequence to the key map, and drops any it does not know, whole. `Tui.drainEscape`
   already does this for CSI and SS3; the decoder generalises it and returns keys instead of dropping them.
2. **Terminal queries happen in one bounded phase before the key loop starts**, ended by a device-attributes reply
   that every terminal sends, with the replies parsed by that phase and never seen by dispatch.

The decoder should be a pure function from (bytes, elapsed time) to keys, so it is tested with no terminal and no
clock.

### E5. Graphics commands pass through the writer unchanged

A query, a cursor move followed by a placement, and a 4,096-byte chunk written with `terminal.writer().print` arrived
byte for byte (4,211 bytes). JLine does not filter APC sequences.

### E6. Raw mode keeps Ctrl-C as a signal

Recorded from the code, not run: JLine's `enterRawMode` clears `ICANON`, `ECHO` and `IEXTEN` and leaves `ISIG`, so
Ctrl-C raises SIGINT and reaches the shutdown hooks (027 relies on this). Ctrl-C therefore stays the unconditional way
to stop the node whatever the TUI does with `q`.

### 3.7 Reproducing

`curl -O https://repo1.maven.org/maven2/org/jline/jline/3.30.13/jline-3.30.13.jar`, then compile against it:
`WCWidth.wcwidth`, `AttributedString.columnLength` and `toAnsi(colors, ForceMode.None)` for E1 and E2; for E3 to E5 a
terminal from `TerminalBuilder.builder().system(false).streams(in, out).type("xterm-256color")` with
`enterRawMode()`, keys written to a `PipedOutputStream`. The checks belong in the test suite as the decoder's and the
encoder's first tests.

---

## 4. The Kitty graphics protocol, as used here

Source: <https://sw.kovidgoyal.net/kitty/graphics-protocol/>, read 2026-11-13 (paraphrase).

### 4.1 Framing

`ESC _ G <key=value,…> ; <base64 payload> ESC \`. The payload goes in chunks of at most 4,096 bytes; every chunk but
the last has a length that is a multiple of 4 and carries `m=1`; the last carries `m=0`. Only the first chunk carries
the full control data.

### 4.2 Detecting support

Send a query for a one-pixel image and then a primary device-attributes request:
`ESC _ G i=31,s=1,v=1,a=q,t=d,f=24;AAAA ESC \` then `ESC [ c`. A terminal with the protocol answers
`ESC _ G i=31;OK ESC \` before the device-attributes reply; one without it answers only the latter. Detection is by
asking, never by the terminal's name.

### 4.3 Sending pixels

`f=24` RGB, `f=32` RGBA, `f=100` PNG. `o=z` marks a zlib-deflated payload and applies to any format. `t=d` sends the
data in the escape itself; it is the only medium that works over SSH, and the only one this spec uses. `s`, `v` give
the pixel size. `i` is the image id. `q=2` suppresses every reply.

### 4.4 Placing, replacing, deleting

- `a=t` sends without showing; `a=p` shows an image already sent; `a=T` does both.
- `p` is a placement id. `c`, `r` scale the image into that many columns and rows. `x`, `y`, `w`, `h` choose a source
  rectangle in pixels. `C=1` leaves the cursor where it is. `z` is the stacking order; a negative value draws under
  the text.
- `a=d` deletes: `d=a`/`A` every placement on screen, `d=i`/`I` by image id, `d=p`/`P` by cell, `d=z`/`Z` by stacking
  order, and others. A capital letter also frees the stored image.
- `ESC [ 2 J` clears all images; so does switching to the alternate screen. Other erase commands leave them.
  Scrolling moves images with the text. The TUI never scrolls and clears only on a resize and on `r`
  (`Tui.scala:233-242`), so those are the moments images must be placed again.
- kitty keeps about 320 MB of images per screen buffer and drops the oldest beyond that. The art here is a few
  hundred KiB.

### 4.5 Scaling: assume nothing

The page says an image is scaled to fit `c` × `r`; it does not say how. A smoothing filter would blur pixel art. So the
TUI **enlarges images itself**, by a whole factor, to the exact pixel size of the cells they cover, and the terminal
has nothing to scale. That needs the cell size in pixels: `ESC [ 16 t` (reply `ESC [ 6 ; height ; width t`), or
`ESC [ 14 t` (window size in pixels) divided by rows and columns. Some terminals answer neither or answer 0; then
there is no pixel mode.

### 4.6 Placeholders

An image can be created with no placement and `U=1` ("virtual"), then drawn by text: cells holding U+10EEEE whose
foreground colour is the image id (8-bit in 256-colour mode) and whose combining marks give the row and column;
underline colour gives a placement id; marks may be omitted for cells that continue the cell to their left. The page
says placeholders are what make images work inside programs that only pass text and colours along, such as tmux, given
a way to pass the transmit commands through.

### 4.7 Animation

`a=f` adds frames to an image and `a=a` runs them in the terminal. Not used here: the terminal would advance frames
on its own clock, which the renderer cannot reproduce or test. See §5.3.

### 4.8 Which terminals

| Terminal | Status | Basis |
| --- | --- | --- |
| kitty | The reference implementation. | The protocol page. |
| Ghostty | Documented as supported. | <https://ghostty.org/docs/features>. |
| WezTerm, Konsole | Believed to implement the basic protocol. **Unverified**: the WezTerm page could not be read on 2026-11-13. Placeholders and animation support unknown. | — |
| tmux, GNU screen | Do not implement it. Pass-through is possible with placeholders (§4.6). | The protocol page. |
| xterm, the Linux console, VTE terminals, Alacritty, Windows Terminal, iTerm2 | No. | General knowledge; the query settles it at run time. |

The list matters less than the query: any terminal that answers §4.2 and §4.5 gets pixels; every other terminal gets
blocks or text.

---

## 5. Graphics design

### 5.1 Three modes, one layout

| Mode | Drawn with | Chosen when | Resolution of a 12×6-cell region |
| --- | --- | --- | --- |
| **pixel** | Kitty graphics | the terminal answers the support query and the cell-size query; no multiplexer; at least 256 colours | cell-size dependent, e.g. 120×120 px at 10×20 px cells |
| **block** | `▀` cells, foreground = upper art pixel, background = lower | at least 256 colours and no pixel mode | 12×12 art pixels |
| **text** | spec 027's glyphs | `NO_COLOR`, fewer than 256 colours, or by choice | — |

A graphic region is a rectangle of cells with the same size in every mode. In pixel mode it shows the art at full
detail, in block mode a small version of the same art in the same palette, in text mode the glyph or figure 027
already draws there, or nothing. Changing mode moves no cell. This is 027's "degrade, never break" extended by one
tier, and block mode is what most operators will see, because most run under tmux.

Overrides: `fukuii.tui.graphics = auto | pixel | block | text` and `FUKUII_TUI_GRAPHICS`, like `glyphs` and
`FUKUII_TUI_GLYPHS`; a key cycles the mode at run time, as `g` does glyphs.

### 5.2 Placing images: two mechanisms

| | Direct placement (`a=p` at a cursor position) | Placeholder cells (§4.6) |
| --- | --- | --- |
| Frame model | The frame is lines **plus** a list of placements. | The frame is lines only; an image is text. |
| Diff and skip (`Tui.scala:238`) | Lines and placements are each compared. | Unchanged. |
| Goldens | Lines, plus the placement list. | Lines only (E1). |
| After a clear or resize | Placements must be issued again. | Nothing to do: the cells are rewritten with the frame. |
| Animation | Re-issue the placement with another source rectangle: about 60 bytes. | Change the cells' foreground (another image id), or the marks (another region of a sheet). |
| Under tmux | No. | Yes, with pass-through. |
| Terminal coverage | Every terminal with the protocol. | kitty and Ghostty for certain; others unknown. |
| Image id space | 32-bit. | 16–255 through JLine's indexed colours (E2); enough. |

**Recommendation for the plan:** build the pixel layer behind one small interface (a frame's graphic regions in, bytes
out) and implement direct placement first, because it works on every terminal that has the protocol. Placeholders are
the path to pixels under tmux; that is out of scope for the first release (spec non-goals) and E1 shows the frame
contract already allows it.

### 5.3 Motion without bytes

- **Sprites.** Each sprite sheet (all frames of one character) is sent once, when the TUI starts or the mode changes.
  A frame change re-issues one placement with a different source rectangle. Steady-state cost: tens of bytes per
  animated sprite per frame.
- **Charts.** A chart (the Block Observatory, the Mempool Radar, a meter's history) is redrawn only when its data
  changes, at most once a second, into a small indexed image: a 480×96 px chart in a 16-colour palette deflates to a
  few KiB.
- **Which frame is shown is a function of `tick` and state**, as in 027. The terminal's own animation (§4.7) is not
  used, so a frame is reproducible and every frame can be a golden.
- **Reduced motion and pause** freeze sprites on a pose chosen by state, exactly as they freeze the worm's ripple.

Budget to hold the design to (spec SC-007): after the one-time upload, under 20 KiB/s to the terminal on the busiest
view. Two things follow from the arithmetic.

- 027 rewrites the whole frame whenever any cell changes (`Tui.scala:242`): up to about 15 KiB at 160×48, so 60 KiB/s
  at 4 fps. The writer must send only the rows that changed. JLine's `Display` diffs by cell, with its own width
  tables and cursor tracking; a whole row is the safe unit beside emoji and placeholder cells.
- A sprite in block mode is cells: a 32×16-cell mascot is 512 cells at about 20 bytes each, 10 KiB a frame. Cycling
  it at 4 fps would cost 40 KiB/s on exactly the links (tmux over SSH) that have least to spare. So in block mode a
  sprite changes only when its pose changes. In pixel mode a frame change is one placement, about 60 bytes.

### 5.4 Art

- **One palette**, a fixed subset of xterm-256 indices ≥ 16 (E2), named by role as `TuiTheme` names colours.
- **Source format**: indexed-colour PNG sheets plus a manifest (sprite name, frame size, frame count, the state each
  row is for), under `src/main/resources/tui/art/`. A test rejects any pixel outside the palette.
- **Decoding**: `javax.imageio` is on every runtime fukuii ships (the Docker and distroless images are full JREs; no
  jlink image exists, §6.6), but `src/main` uses no `java.awt` or `javax.imageio` today. The plan chooses between
  ImageIO under `java.awt.headless=true` and a small decoder for 8-bit indexed PNG on `java.util.zip.Inflater`.
  Encoding needs neither: `f=32` with `o=z` is `java.util.zip.Deflater` over the raw pixels.
- **Who draws it** is decision D-3.

### 5.5 The mascot is a status lamp

The brief's "central animated FUKUII mascot acting as the operator" follows 027's first principle ("charm is a lens,
never a curtain"; the worm *is* the progress bar). Each pose means one thing:

| Node state | Pose | Text-mode equivalent that already exists |
| --- | --- | --- |
| starting | booting up the console | the splash |
| syncing | typing; speed follows import rate in three steps | the worm bars |
| at head, no alerts | watching the screens; a head turn when a block arrives | `✓ no alerts` |
| a warning alert | looking at the alert row | the alert row, warn style |
| an error alert | standing, alarm lamp on | the alert row, error style |
| stalled or source unresponsive | asleep | `💤` |
| first arrival at head | celebrates once (027 Q8) | the 12-frame celebration |

The mascot never covers the alert row, never animates a confirmation dialog, and never reacts to an operator action
with anything but the state the action leaves the node in.

### 5.6 Start and stop

- **Start**: after raw mode and the alternate screen, before the key loop: the support query and the cell-size query,
  ended by the device-attributes reply (E4) and bounded by the spec's 500 ms (FR-013). No answer means block mode.
- **Stop**: one more rung on the teardown ladder (`Tui.scala:389-399`), before the screen is restored: delete every
  placement and free every image (`a=d,d=A`). When JLine's shutdown hook has already closed the terminal, the same
  bytes go through the process stream with the screen reset (`Tui.closedByJLine`, `:442-456`). Leaving the alternate
  screen also drops its images (§4.4), so this is belt and braces.
- **Resize and `r`**: the screen is cleared, so placements are issued again and the cell size is asked again (a font
  change alters it).

---

## 6. Where the data comes from

Legend: **have** = read today or readable with no new code outside `console/`; **derive** = computed by the TUI from
things it can already read; **new** = needs observation added outside `console/`, with the owner noted; **none** = the
node has no such thing.

Two principles from 027 carry over. Observation added outside `console/` is state on a metrics object written at an
existing call site, changing no result, branch or timing. And where the TUI can derive something by watching, it does,
before anyone adds a hook to a consensus path.

### 6.1 Chain

| Requested | Status | Source or gap |
| --- | --- | --- |
| Head number, hash, timestamp, age | have | `domain/BlockchainReader.scala:287` `getBestBlockHeader` (works after SNAP when the body is missing). |
| Gas used and limit, base fee | have | `domain/BlockHeader.scala:32-34`, `:47` (`baseFee: Option`). |
| Transactions per block | have | `getBestBlock` (`:269`), `body.numberOfTxs`. |
| Block size | have, with care | `domain/Block.scala:117` `Block.size` re-encodes the block; compute once per head. |
| A stream of new heads | derive | The block topic (`nodebuilder/NodeBuilder.scala:134-141`) is published only by regular sync (`BlockImporter.scala:1276,1281,1377`) and `TestingService`. Blocks made canonical through the Engine API are not published. So the TUI polls the head once a second and walks parent hashes back to the last head it saw, bounded. One mechanism for both families, no hook in an import path. |
| Import rate | derive, plus have | Difference of observed heads; `RegularSyncMetrics.lastImport` (`:59-90`) for the last import's duration on the regular-sync path. Nothing times `engine_newPayload` execution (new, §6.3). |
| Reorg detection and history | derive | Reorgs are logged (`ImportMessages.scala:78-96`, `BlockchainWriter.scala:296-306`) and counted privately on the regular-sync path only (`RegularSyncMetrics.scala:29-34`); no history is kept anywhere. The head walk sees one whenever the new head's ancestry misses the previous head: depth, both tips, time. This is history since the TUI started, on both families. |
| Fork status | have | `console/ChainFacts.scala:40-97` (027). The ETH timeline starts at Shanghai. |
| Chain id, network id, family, genesis hash | have | `utils/BlockchainConfig.scala:48-60`; `BlockchainReader.genesisHeader`. |
| Fork id | have | `forkid/ForkId.scala:42`, a pure function; compute once per head. |
| Sync status and distance | have | 027. |
| Base-fee and priority-fee trends | have | `jsonrpc/EthBlocksService.scala:384` `feeHistory`, `:470` `maxPriorityFeePerGas`; `jsonrpc/GasPriceOracle.scala:50,69`, one shared instance (`NodeBuilder.scala:702-709`). All callable in-process. |
| Block, transaction, receipt, account lookup | have | `EthBlocksService` (`:189`, `:223`, `:366`), `EthTxService` (`:133`, `:152`), `EthUserService` (`:67`, `:85`, `:47`). Each returns an `IO`; `StdNode` can hand the TUI the same instances. The transaction index is always on (`BlockchainWriter.scala:316-320`). Lookups run on a worker, never on the frame loop or the probe. |
| Slot | none before Amsterdam | `BlockHeader.slotNumber` (`:90-92`) from Amsterdam only. No slot clock exists. Shown when the header has it; otherwise the field is absent. A wall-clock slot needs a beacon genesis time per network, which belongs to the chain registry (spec 024). |

### 6.2 Mempool

The pool is `transactions/PendingTransactionsManager.scala`: a time- and size-bounded cache (default 1,000
transactions, 2 minutes; `conf/base/txpool.conf`), one pool for all transactions including blobs. **There are no
mempool metrics of any kind** and **there is no queued pool**: a transaction with a nonce gap is dropped
(`jsonrpc/TxPoolService.scala:31-32`; `txpool_status.queued` is a constant 0).

| Requested | Status | Source or gap |
| --- | --- | --- |
| Pending count, blob count | have | `GetPendingTransactionsReq` (`:115`), which copies the pool. B-196 asks for a count-only message. |
| Queued count, nonce gaps | none | Shown as "not kept by this client", never as 0. |
| Capacity and utilisation | have | `fukuii.txPool.tx-pool-size`. |
| Transactions per second in and out | derive | Difference of successive pool snapshots. |
| Left the pool because included, or dropped | derive | A hash that left the pool and appears in a block the head walk saw was included; otherwise it was dropped, evicted or expired. |
| Replacement activity | derive | Same sender and nonce, different hash, in successive snapshots. |
| Fee distribution, the Radar's tiers | derive | Each pending transaction's tip against the node's own suggestion (`GasPriceOracle.suggestTipCap`; `suggestGasPrice` on a chain without a base fee). |
| Sender activity, local or remote | have | `PendingTransaction(stx, addTimestamp, receivedFromLocalSource)` (`:128`). |
| Rejections by reason | new (`transactions/`) | `TxAdmission.Rejection` (`TxAdmission.scala:97-240`) names every reason; nothing counts them. |
| Evictions and expiries | new (`transactions/`) | The cache's removal listener only logs at DEBUG (`:242-244`). |
| Propagation: announced, fetched, timed out | new (`transactions/`, review by `herald`) | Lives inside `TxGossipTracking.scala:21,94`; no counters. |
| Transaction lookup | have | `EthTxService.getTransactionByHash` reads the pool, then the index. |

**The cost of watching.** The Radar's derivations need the whole pool each second. At the default size that is cheap;
at a pool configured a hundred times larger it is not. The spec bounds it (FR-037): full snapshots only while the
Mempool view is open, and never more than one a second.

### 6.3 RPC, Engine API, mining

| Requested | Status | Source or gap |
| --- | --- | --- |
| Request volume by method | have, partly | `jsonrpc/JsonRpcControllerMetrics.scala:21-24`: a timer tagged by method, recorded **only on success** (`JsonRpcBaseController.scala:82`). |
| Error rates by method, timeouts | new (`jsonrpc/`, `conduit`) | The error, exception and not-found counters carry no method tag (`:13-19`). No timeout counter. |
| Latency percentiles | new (`jsonrpc/`, `conduit`) | The timer keeps count, sum and maximum (`metrics/Metrics.scala:61-66`). Percentiles need a small fixed-bucket histogram per method, in process, with no dependency. |
| Transports: enabled, address, port, namespaces | have | Config (`JsonRpcHttpServer.scala:288-301`, `JsonRpcBaseController.scala:136-214`); `RpcExposureCheck.scala:23-43` already decides whether an admin namespace is exposed. |
| Authentication status | have | Only the Engine API authenticates (JWT). The other transports have none; the panel says so. |
| Rate-limit rejections | new (`jsonrpc/`) | `RateLimit.scala:23`; no counter. |
| Engine link, last calls and statuses, auth failure count | have | 027 slice 5: `consensus/engine/EngineApiMetrics.scala:46-93`, `ForkChoiceManager.getState`. |
| Engine method activity and latency | new (`consensus/engine/`, `beacon` reports) | Engine methods bypass the JSON-RPC controller (`EngineApiController.scala:44`); nothing times them. `getPayload`, `exchangeCapabilities`, `getBlobs` are not counted. |
| A log of recent Engine API calls | new (`consensus/engine/`, `beacon` reports) | None exists. A bounded ring on the metrics object. |
| Consensus client name and version | new (`consensus/engine/`, `beacon` reports) | `engine_getClientVersionV1` ignores the caller's own version (`EngineApiController.scala:496-516`); `exchangeCapabilities` logs only a count. |
| JWT diagnostics | new, small | Failure reasons are not kept; whether the secret is a random start-up one is only logged (`JwtAuthenticator.scala:141-146`). |
| Payload building events | new (`consensus/engine/`, `beacon` reports) | State is private to `EngineApiService.scala:83-117`. |
| newPayload status counts | have, wrong | B-193: ACCEPTED is counted as INVALID and SYNCING is never recorded. Fixed there, read here. |
| Mining (ETC) | have | 027 slice 5: `consensus/pow/PoWMiningMetrics.scala`. |

### 6.4 Storage

| Requested | Status | Source or gap |
| --- | --- | --- |
| Disk free | have | 027: `File.getUsableSpace` on `fukuii.datadir`. B-192 moves it off the probe's own wait. |
| Disk total, utilisation | derive | `File.getTotalSpace`. |
| Database size | new (`db/`, `vault`) | No SST or live-size property is read anywhere. One aggregated property read each (`rocksdb.total-sst-files-size`, `rocksdb.estimate-live-data-size`). |
| Database growth | derive | From the size history the TUI keeps. |
| Per-store breakdown | new (`db/`, `vault`) | 22 column families (`db/storage/Namespaces.scala:4-35`); only `estimate-num-keys` is read per family today (`RocksDbDataSource.scala:478`). |
| Compaction status, write stalls | new (`db/`, `vault`) | `compaction-pending`, `num-running-compactions`, `estimate-pending-compaction-bytes`, `is-write-stopped`, `actual-delayed-write-rate`: none read today. |
| Cache use | have, partly | Block cache use, pinned, capacity, memtables, table readers: `RocksDbDataSource.memoryStats` (`:76`). Hit rates only with `db.rocksdb.enable-statistics` (off by default; `:46`). The in-JVM caches export nothing (`DecodedNodeCache.sizeBytes` exists, `:47`, unread). |
| Pruning configuration, state scheme | have | `Storages.pruningMode`; `snapSyncConfig.storageScheme`; `AppStateStorage.getStateLayout` (`:61`). |
| "Ancient" database | none | fukuii has no freezer and no history expiry. |
| Snapshot status | none | Spec 013 is a docs-only draft. The nearest thing is the checkpoint archive CLI. |
| Integrity | have, partly | The markers in `AppStateStorage` (`:146-412`) are cheap reads. The background audit answers `GetStatus` (`BackgroundAudit.scala:145`) but its actor is private to `SyncController` (`:378`): new wiring (`vault`, and whichever of `forge` or `beacon` owns the scheme). |

### 6.5 System

| Requested | Status | Source or gap |
| --- | --- | --- |
| Heap, garbage-collection share | have | 027: `TuiStatusProbe.scala:314-332`. |
| Garbage-collection counts and last pause | derive | The same management beans. |
| CPU use, load | derive | `OperatingSystemMXBean`; nothing reads it today. |
| Threads | derive | `ThreadMXBean`. |
| Process uptime | derive | `RuntimeMXBean`. Today's "uptime" counts from when the TUI started (`TuiState.scala:127`), which is wrong after a late attach. |
| Network throughput in bytes | new (`network/rlpx/`, `herald`) | **No byte counter exists anywhere**: not in the frame codec, not per peer (`PeerScore.bytesDownloaded` is fed a constant 0, `PeersClient.scala:522`). Message totals exist (`NetworkMetrics.scala:18,20`). |
| Disk I/O rate | none portable | The JVM has no portable source; `/proc` would be Linux-only. Out of the first release. |

Reading management beans needs no metrics exporter: with `metrics.enabled = false` (the default) the meters are still
live in process (`metrics/Metrics.scala:83`).

### 6.6 Peers and topology

What the node knows about a peer: address, node id, direction, connected since (`network/Peer.scala:18`); client,
capabilities, best block, fork acceptance (`NetworkPeerManagerActor.scala:1352,1427`); a score with request latency
(`network/PeerScore.scala:26`; 027 slice 4a); windowed request and response counts
(`network/PeerStatisticsActor.scala:91`).

What it does not know: where the peer is (no GeoIP anywhere in the tree or the build), its ping round-trip time (the
node answers pings and never times its own, `PeerActor.scala:381-382`), bytes exchanged, or any other peer's
connections.

So a truthful "network topology" is a **radar**: this node at the centre; each peer a blip whose distance is its
request latency, whose angle is fixed by its node id (so it does not jump about), whose colour is its score band,
whose shape is its direction, and which flashes when it answers a request. Every property of a blip is a datum the
node has. The world map is decision D-2.

### 6.7 What 027's inventory already covers

Peers, sync, SNAP phases, discovery counts, ports, the log tail, the fork timeline, the consensus-layer link and
mining are unchanged; see 027 research §6.

---

## 7. Operator actions

### 7.1 What exists

| Action | Exists as | Where |
| --- | --- | --- |
| Stop the node | `q` in the TUI, no confirmation; signals | `Tui.scala:342`; `StdNode.shutdown` (`:424-468`) |
| Detach the TUI | `d` | `Tui.scala:351` |
| Change a log level | `admin_changeLogLevel` | `jsonrpc/AdminService.scala:300-320`. No getter for current levels, no reset. |
| Add, remove, trust a peer; set the peer limit | `admin_addPeer`, `removePeer`, `addTrustedPeer`, `removeTrustedPeer`, `maxPeers` | `AdminService.scala:245-440` |
| Disconnect a peer; blacklist | `net_disconnectPeer`, `net_addToBlacklist`, … | `JsonRpcController.scala` |
| Block an address | `admin_blockIP`, `unblockIP`, `listBlockedIPs` | `AdminService.scala:382-397`. In memory; lost on restart. |
| Start and stop mining, set the coinbase (ETC) | `miner_start`, `miner_stop`, `miner_setEtherbase` | `jsonrpc/EthMiningService.scala:223,232` |
| Export and import chain files | `admin_exportChain`, `importChain`; offline `fukuii <net> export|import` | `AdminService.scala:330,357` |
| Compact a key range | internal only | `DataSource.compactRange` (`db/dataSource/DataSource.scala:108`); it holds the database read lock for the whole call, so writers wait. |
| Database consistency check | at start, and every 10 minutes if configured | `StdNode.scala:170`; `nodebuilder/tooling/PeriodicConsistencyCheck.scala:15`. No trigger. |
| Health checks | `/health`, `/readiness`, `/healthcheck` on the HTTP port | `jsonrpc/NodeJsonRpcHealthChecker.scala:40-106` |
| External address, UPnP | at start | `network/ExternalIPDetector.scala:67-110` (UPnP, then STUN, then HTTP services, then local interfaces); logged only. |

### 7.2 What the brief asks for that the node cannot do today

| Requested | Finding |
| --- | --- |
| **Start** node processes | The TUI is a thread in the node's process. It cannot start the process it runs in. See decision D-1. |
| Restart client services | Almost nothing is restartable. The HTTP and WebSocket servers do not keep their binding (`JsonRpcHttpServer.scala:164`, `JsonRpcWsServer.scala:101`); the Engine API server's `stop()` ends its own actor system, so it cannot start again (`EngineApiHttpServer.scala:190`); discovery and sync stop only at shutdown. Stopping and starting the HTTP and WebSocket servers is new but contained lifecycle work in `jsonrpc/server/` (`conduit`). Restarting the Engine API, discovery or sync is not in this spec. |
| Reload supported configuration | No key is reloaded from file, ever. Five things change at run time, each through an admin method: log levels, the peer limit, trusted peers, blocked addresses, and mining with its coinbase. The honest feature is a panel of exactly those, and a config viewer for everything else. |
| Inspect configuration | Nothing renders the effective configuration. New, with redaction (§7.4). |
| Export diagnostics, support bundles | Nothing exists: no bundle, no thread dump, no script. New. |
| Create snapshots | Spec 013 is a draft with no code. Not in this spec. |
| Database integrity checks | The start-up check can be run on demand (new trigger). The background audit can be watched (§6.4). |
| Inspect service dependencies | New: a fixed list of subsystems, what each needs, and each one's health (§8.3). |
| Test RPC endpoints | New and small: call the node's own listeners over each enabled transport and report the answer and the time. |
| Connectivity checks | Surface what `ExternalIPDetector` found; inbound peers are the evidence of reachability. A real inbound test needs an outside party. Re-detection contacts third parties, so it is an action that says so first. |
| Manage alert thresholds | All ten are constants (`console/Alerts.scala:29-49`, `TuiState.scala:586-737`). Configuration keys, and a session-only edit. |

### 7.3 Classes and friction

An action's friction is set by what it can break, not by where it sits in a menu.

| Class | Meaning | Confirmation | First-release members |
| --- | --- | --- | --- |
| **Observe** | Changes nothing. | None. | Endpoint tests, config viewer, dependency view, lookups, thread-count read-outs. |
| **Adjust** | A setting changes; one step puts it back; nothing is interrupted. | A dialog showing before → after; Enter. | Log level, peer limit, trust or untrust a peer, alert thresholds. |
| **Disrupt** | Something in flight is interrupted or made slow, and recovers. | The preview, then `y`. | Disconnect or block a peer, stop or start mining, stop or start the HTTP or WebSocket server, compaction, a consistency check, connectivity re-detection, writing a support bundle. |
| **Halt** | The node stops doing its job until someone acts from outside, or data is destroyed. | The preview, then typing a word the dialog shows. | Stop the node. |

No first-release action deletes or rewrites chain data. The Halt class exists so that the first one that does has a
rule waiting for it.

**The preview is the contract.** It is computed from live state when the dialog opens and lists what will change in
terms the operator can check afterwards: "disconnect 0x1f3a…, a Besu peer at 203.0.113.7, connected 4 h, score 0.82;
it may reconnect". For stopping the node it says what stops with it: block import, the consensus client's execution
link on an ETH node ("a validator on this node misses duties until it is back"), mining on an ETC node, and every RPC
client. If the facts change while the dialog is open, it refreshes and asks again.

### 7.4 Secrets

A config viewer and a support bundle are the two places a secret could leak to a screen or a file. Keys known today
that hold or point at secrets: `fukuii.node-key-file`; `fukuii.keyStore.*`;
`fukuii.network.rpc.http.certificate.{keystore-path, password-file}`; `fukuii.network.engine-api.jwt-secret-path`;
`faucet.wallet-password` (a literal password) and the faucet's keystore and certificate keys;
`sync.checkpoint-sync-url` (may embed credentials). Rules:

- Redaction is by key, from an explicit list **and** a name pattern (`password`, `secret`, `key`, `token`,
  `passphrase`), so a key added later is hidden by default. A path is shown; a file's contents never are. A URL is
  shown without its user-info.
- A test plants a marker value in every listed key and asserts the marker appears in no frame and no bundle file.
- A bundle is written under the data directory with owner-only permissions and is never sent anywhere. Its preview
  lists every file it will contain. It holds peer addresses and the node id, and says so.

### 7.5 One implementation per action

Where an admin method exists, the TUI calls the same service function (`AdminService.changeLogLevel`, not a second
copy), in process. The TUI needs no RPC namespace enabled, and enables none. A new action gets a service function the
TUI calls; exposing it over RPC would widen the network-reachable admin surface (constitution VI) and is not part of
this spec.

---

## 8. Design notes: incentives and failure modes

### 8.1 The TUI becomes an actuator

Until now the TUI could not hurt the node except by `q`. A terminal that acts needs an answer to "who is allowed":
whoever holds the terminal. That person can already send Ctrl-C, so confirmation is not a security boundary. It is a
guard against the wrong key, the pasted clipboard and the shared screen. Hence: bracketed paste, with pasted text
accepted only by text fields and never by a confirmation (spec FR-009); a typed word for Halt; and one switch,
`fukuii.tui.read-only`, for wall displays, demos and shared tmux sessions, under which no action can run and the Ops
view says so.

### 8.2 The log is the audit trail

Every attempt, confirmed, cancelled or failed, is one INFO line from one logger: the action, its parameters (never a
secret), the outcome. It reaches the file log and the Logs view like any other line. An operator who finds mining
stopped can find out when and from where.

### 8.3 A health bar must be explainable

A single "health 99 %" invites trust nobody can check, and anything shown as a score gets optimised for the score. So
a subsystem's bar is **headroom to its nearest threshold**, and the bar names the check that sets it: "Disk 61 %:
38 GiB free, warn below 20 GiB". Its colour comes only from the alert rules (`console/Alerts.scala`), so a bar is
never red while the alert row is quiet, or the reverse. Enter on a bar lists every check behind it: value, threshold,
state, since when. There is one source of truth for "is something wrong", and it is still the alert row.

Subsystems: P2P, chain and sync, the consensus-client link (ETH) or mining (ETC), the transaction pool, RPC, the
database, the disk, memory and garbage collection, CPU.

### 8.4 Watching must not disturb

The pool snapshot copies the pool; `Block.size` re-encodes a block; a RocksDB property read takes a lock; a lookup
reads state. Each has a budget in the spec, each runs off the frame loop, and a slow one degrades its own panel to
stale (027's `Sourced`) and nothing else.

### 8.5 Nothing is drawn that is not known

The rule that excludes the mockup's peer arcs is general (spec FR-021): an element either encodes a datum the node
has, or it is static decoration that never changes. Anything the node lacks is absent or says "not kept by this
client". A zero is only ever a measured zero.

---

## 9. Decisions for the user

Three choices changed the scope and had no default the spec could take alone. **All three were answered on
2026-11-13: D-1 (a) in process only; D-2 (a) the peer radar; D-3, first (b) and then revised the same day to: the
art is made in this spec's own work, with full control of it, and looked at as it is drawn.** The options are kept
here as the record of what was weighed. The first set of art is in the tree (`src/main/resources/tui/art/`, drawn by
`scripts/tui/make_art.py`, views in [art-preview/](./art-preview/)); §5.4's rules became
[contracts/art.md](./contracts/art.md).

**D-1. Starting and restarting the node.** The brief lists "start and stop node processes" and "restart client
services". In process, the TUI can stop the node and (with new lifecycle work) stop and start the HTTP and WebSocket
RPC servers and mining.

| Option | What it means |
| --- | --- |
| **(a) In process only** (recommended) | Stop the node, with confirmation. Stop and start the HTTP and WebSocket servers and mining. No start, no whole-node restart. Smallest; no new public interface. |
| (b) Plus restart through a supervisor | As (a), plus "restart node": the node exits with a code a supervisor (systemd, Docker's restart policy) restarts on, as spec 028 FR-016 does for an Ephemery reset. It does nothing useful without a supervisor, and the TUI cannot tell whether one is there. |
| (c) An attachable TUI | The TUI becomes its own process speaking to the node over the IPC socket. It can then start, stop and restart a node and survive its restarts. Every datum in §6 becomes an RPC method: a public interface of thirty or more methods, a one-way door, and its own spec. This spec would keep its data access behind one interface so that (c) remains possible. |

**D-2. The topology panel.**

| Option | What it means |
| --- | --- |
| **(a) Peer radar, no geography** (recommended) | §6.6. Every pixel is a datum the node has. Nothing new is bundled. |
| (b) World map | A star from this node to each peer's approximate location. Needs a bundled offline IP-to-country file (several MB; a licence and attribution to carry, such as CC BY 4.0 for DB-IP Lite; stale within months), a new data dependency to justify under the constitution, and a rule that the lookup is never done over the network. Location is approximate and often wrong for hosted nodes. |
| (c) Radar now, map later | Ship (a). The map becomes a later slice once the data source is chosen. |

**D-3. Who draws the sprites.** The supplied images are concept art, not sprite sheets (§1).

| Option | What it means |
| --- | --- |
| **(a) A starter set made in the build, replaceable** (recommended) | The first graphics slice ships the badge and a mascot traced from the supplied images at a small size with a few poses, plus the manifest format (§5.4). Better art replaces the files with no code change. The first release looks plainer than the mockups. |
| (b) The user supplies sprite sheets first | The graphics slice waits for sheets in the manifest's format (palette, frame size, the poses of §5.5). Best result; a dependency outside the build. |
| (c) No characters | Charts, meters, the badge and the palette only. Smallest; loses the mascot the brief centres on. |

Defaults the spec takes without asking, each reversible in review: operator actions are on unless
`fukuii.tui.read-only` is set; under tmux and screen the mode is block; `q` asks before stopping the node; the views
are renumbered `1`–`9` and `Tab` moves focus; "slot" is shown only when the header carries one.

---

## 10. A possible slicing (input to the plan, not a commitment)

| # | Slice | Needs | Outside `console/` |
| --- | --- | --- | --- |
| 1 | Input: the decoder, focus and selection, lists that scroll, dialogs, the prompt, the nine-view map | — | none |
| 2 | Graphics: detection, the three modes, the palette, sprites, the mascot, the splash with real start-up phases | 1 | none |
| 3 | Chain: the head walk, the Block Observatory, fee trends, reorg history, the explorer | 1 (2 for pixels) | `StdNode` wiring only |
| 4 | Mempool Radar | 3 | `transactions/` counters |
| 5 | RPC, the Engine Synchronization Monitor, mining | 1 | `jsonrpc/` (`conduit`); `consensus/engine/` metrics (`beacon` reports) |
| 6 | Storage, system, health bars, thresholds | 1 | `db/` property reads (`vault`); `network/rlpx/` byte counters (`herald`) |
| 7 | Operator actions | 1 | `jsonrpc/server/` lifecycle (`conduit`); a consistency-check trigger (`vault`) |

Slices 3 to 7 are independent of each other once 1 is in. Each is its own PR, as in 027. 027's size was about 2,250
lines of main code and 950 of tests over five slices; this is two to three times that.

---

## 11. Found on the way, outside this spec

- **`eth_subscribe newHeads` on an Engine-API-driven node.** The block topic that feeds it is published only by
  `BlockImporter` and `TestingService` (§6.1). If no other path publishes for blocks made canonical by
  `engine_forkchoiceUpdated`, subscribers on an ETH node get no heads. Not verified against a running node. Backlog
  follow-up added.
- **B-193** (Engine API status counters mislabelled) and **B-196** (a count-only pool query) are prerequisites for
  honest numbers in §6.3 and §6.2.
- **B-226** (the escape drain's fixed tail) is subsumed by the decoder in slice 1.
- **The TUI's uptime** counts from the TUI's start, not the process's (§6.5). Fixed in slice 6.
