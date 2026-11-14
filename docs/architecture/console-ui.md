# Console UI

Fukuii includes an enhanced Terminal User Interface (TUI) for monitoring node status in real-time.

## Features

The Console UI provides a rich, visual interface with:

- **Real-time Status Updates**: Live display of node state without scrolling
- **Grid Layout**: Organized sections for different metrics
- **Network Information**: Current network, peer connections, and connection status
- **Blockchain Sync Progress**: Current block, best block, progress bar, and estimated sync time
- **ASCII Art**: Ethereum Classic logo and visual indicators
- **Color-Coded Status**: Green for healthy, yellow for warnings, red for errors
- **Interactive Commands**: Keyboard shortcuts for control
- **Clean Exit**: Proper terminal cleanup on shutdown

## Usage

### Starting with Standard Logging (Default)

By default, Fukuii uses standard logging output:

```bash
./bin/fukuii etc
```

**Note**: The console UI is currently disabled by default while under further development.

### Enabling Console UI

To enable the enhanced console UI for interactive monitoring:

```bash
./bin/fukuii etc --tui
```

The console UI is useful when:
- Monitoring node status in real-time
- Running interactively in a terminal
- Viewing sync progress with visual indicators
- Using keyboard shortcuts for control

## Display Layout

Every view has the same frame: the header band (network, chain id, PoW or PoS, uptime and the view's name; from
100×32 also the head block and the peers, from 140×45 the version), a separator, the **alert row**, the view's panels,
and the footer with the keys. The Overview at 80×24, regular sync (from the golden file
`src/test/resources/tui/golden/overview-s-regular-emoji.txt`):

```
 ◆ FUKUII  etc (61) · PoW · up 9m 0s                                   Overview
────────────────────────────────────────────────────────────────────────────────
 ✓ no alerts
 Peers   23/50 ◆◆◆◆◆◆◆◆◆◆         Block  18,402,113 → 21,884,102 (−3,481,989)
 RPC     :8545 on                 Disk   1.2T free       Txpool  142
────────────────────────────────────────────────────────────────────────────────
 Sync    regular                                 rate 184.37 blk/s · ETA 5h 14m
   Blocks   [===============🪱....]🧠 84%   18,402,113 / 21,884,102
────────────────────────────────────────────────────────────────────────────────
 Forks   Mystique ✓ ════════════════════════▶ Spiral @ 19,250,000 (847.9K away)
────────────────────────────────────────────────────────────────────────────────
 Node    …/fukuii/etc · Full sync · archive pruning · 50 peers max · mining off
────────────────────────────────────────────────────────────────────────────────
 Recent
 11:59:25 INFO  [====================🪱]🧠 100% — RegularSync
 11:59:30 ERROR Cannot decode a message from 203.0.113.40:30303
 11:59:34 INFO  Imported block 21,884,101 0x9b0c…ff31 with 9 txs in 17ms
 11:59:47 INFO  Imported block 21,884,102 0x6d21…1a0e with 14 txs in 22ms
 11:59:52 INFO  Peers: 41 handshaked · 14 in · 27 out
 12:00:00 INFO  RegularSync: at head, block 21884102



 1-9 view  : prompt  / filter  ? help  p pause  r redraw  d detach  q quit
```

The Recent strip at the bottom is the newest log lines (6 at tier S). Sizes: below 100 columns or 32 rows is tier S,
from 100×32 tier M, from 140×45 tier L; each view fills its tier. A panel that does not fit gives up its rows to the
ones that matter more; the header, the alert row and the footer always stay.

### Views

Nine views, chosen with `1` to `9`, with `[` and `]`, or by name at the `:` prompt. From 100 columns and 32 rows a
sidebar down the left lists them and marks the one on screen. Its top corner carries the icon of the chain family the
node is on, Ethereum Classic's mark or Ethereum's, with the network's name, `PoW` or `PoS`, the chain id and `main
network` or `test network` in words under it; its last row names the graphics mode. Spec 029 is building these out a slice at a time; a view
whose slice has not landed shows what the dashboard already reads for it and one line naming what is to come.

| Key | View | What it shows |
|-----|------|---------------|
| `1` | Overview | Peers and blocks, the txpool, endpoints, disk and heap (GC and discovery at L), the sync (SNAP's five worm rows or the Blocks worm, with ETA), the consensus layer (ETH) or the mining (ETC), the forks, the node's settings; at L the top 8 peers by score; the Recent strip of the newest log lines |
| `2` | Chain | While the node is syncing, the sync's panels lead: the worm rows on a 20-, 40- or 60-cell track; SNAP's rates, request counters, snap-capable peers and phase timeline (M and L); a regular sync's session, lag and last import; the Recent (sync) strip. Always: chain id, PoW or PoS, the fork timeline (by block number on ETC, by timestamp on ETH), the next fork with its distance |
| `3` | Peers | Each handshaked peer, highest score first: id, direction, client, best block, age; from M the address and the score, at L the score's trend. `Up`/`Down` select a peer, `Enter` opens its details, `Esc` returns, `/` filters by id, address or client |
| `4` | Mempool | The pending count (the rest with spec 029 slice 4) |
| `5` | RPC | The endpoints (the rest with slice 5) |
| `6` | Storage | Disk free, the data directory, the pruning mode (the rest with slice 6) |
| `7` | System | Heap, GC share, discovery counts (the rest with slice 6) |
| `8` `L` | Logs | The newest log lines (1,000 by default, INFO and above), with their time and level; `Up`/`Down` scroll a line, `-` and `+` a page, `/` filters by text |
| `9` | Ops | Whether the TUI is read-only; the actions arrive with slice 7 |
| `?` `H` `F1` | Help | Every key, and the legend of the glyph set on screen |

### Alert row

The row under the header reads `✓ no alerts`, or a badge with the count (`✗ 2 errors`, `⚠ 1 warn`) and up to three
conditions, errors first. It is never animated and every view has it.

| Condition | Severity |
|-----------|----------|
| No handshaked peer for more than 30 s | error |
| The peer manager or the sync controller does not answer | error |
| The current block has not moved for 2 minutes while blocks remain | warning |
| The SNAP progress sample is older than 90 s while SNAP runs | warning |
| Disk free on the datadir below 20G (below 5G: error) | warning / error |
| Fewer peers than a quarter of the maximum (the peer count itself turns yellow below half) | warning |
| ETH: no `engine_forkchoiceUpdated` for more than 60 s, after one has been heard | error |
| ETH: no `engine_forkchoiceUpdated` yet, 60 s after the node started (with the count of failed JWT checks, if any) | warning |
| ETH: no `engine_newPayload` for more than 5 minutes while a peer, or the sync's target, is ahead of the node's head | warning |
| ETC: mining on, the DAG loaded, and a reported hashrate of 0 for more than 10 minutes | warning |

### Consensus layer and mining

Two rows of the Overview exist for one chain family each, and each only where the status probe read its source:

**Consensus layer (ETH, with the Engine API on a post-merge chain).** At 80×24 one row: the link and the last
`forkchoiceUpdated` and `newPayload`, each with its age and the status the node answered. From 100×32 three rows: the
same with the full method names; the head hash (first and last four digits), the safe and the finalized block numbers
(`—` while the consensus layer has named none, or the node does not have the block's header yet), and the JWT
authentication failures when there are any; and the payload counts.

```
 Consensus layer 🔗 connected · forkchoiceUpdated 4s ago (VALID) · newPayload 4s ago (VALID)
   head 0x7b3e…a91c   safe 7,411,977   finalized 7,411,945
   payloads 184,203 (VALID 184,199 · ACCEPTED 4 · INVALID 0)
```

At 80×24 it is one row, `CL      connected · forkchoice 4s (VALID) · payload 4s (VALID)`. The link is `waiting` (for
its first `forkchoiceUpdated`) until that call comes, `connected` while the last is no more than 60 s old, and
`DISCONNECTED` after that, with the age of the last call of each method. At the narrowest tier M, 100 columns, a
`DISCONNECTED` row would be cut in the middle of a status, so it drops "ago". A panel with no second row (waiting, or
at 80×24) says its failed JWT checks on the first (`· 3 auth failures`): a secret that does not match leaves the
consensus layer waiting for ever, and the warning's text says how many (`no CL yet: no forkchoiceUpdated in 1m 5s (3
JWT failures)`). The golden files `cl-{s,m,l}-{connected,waiting,disconnected}-{emoji,ascii}.txt` and
`cl-m-100x32-disconnected-{emoji,ascii}.txt` have the states. The counts are the node's own and labelled by what is counted: `engine_newpayload_invalid`
has always counted an ACCEPTED payload (stored, not executed) with the INVALID ones, so the panel reads the separate
`engine_newpayload_accepted` and shows both, and shows SYNCING only when something counted it (no caller records one).
A payload the node already held is answered VALID without being recorded, so "last newPayload" does not move for it,
which is why the newPayload alert needs a peer ahead of the node's head. With none recorded at all it counts from when
the consensus layer was first seen, or from the node's start if that is later, so a consensus layer that attaches to a
node that has been up for an hour is not measured against the hour.

**Mining (ETC).** With the internal miner off, one row: `Mining  off`. `mining-enabled` gates the internal CPU miner
only: `eth_getWork` and `eth_submitHashrate` still serve ethminer and pools, so a node that is being asked for work
reads `Mining  off (internal) · external: 48.2 MH/s · getWork 1,204 (20/min)` (`none reported` when work is asked for
and no hashrate has been submitted). With mining on, at 80×24 one row (hashrate and DAG); from
100×32 two: the coinbase and the DAG, then the hashrate, blocks mined (and when the last was), stale shares and the
`eth_getWork` count with its rate over the last minute.

```
 Mining  on · coinbase 0x4f2a0b9c…c91e · DAG epoch 364 ✓ loaded
   Hashrate 48.2 MH/s · blocks mined 3 (last 2h 14m ago) · stale 0 · getWork 1,204 (20/min)
```

At 80×24 it is `Mining  on · 48.2 MH/s · DAG epoch 364 ✓ loaded`. The golden files
`mining-{s,m,l}-{on,dag,off,no-hashrate}-{emoji,ascii}.txt` have the DAG being generated, mining off, and the warning.

The DAG's epoch is the ECIP-1099 epoch (block number over 60,000 after Thanos): 364 at block 21.9M, where tools that
count 30,000-block epochs say 731. A DAG that is being built shows its phase (`preparing` while the cache is made,
`loading` from its file, `generating`), its percent and a spinner, the one thing in either panel that moves; under
reduced motion or pause the spinner is a still dot. The hashrate is the last one submitted (`eth_submitHashrate`, or
the internal miner); it never falls when a miner goes quiet, and a miner that never submits one shows `none reported`.
The hashrate alert starts counting only once the DAG is loaded, as nothing is submitted while it is built. The panel
has nothing of Olympia, a treasury or a base fee: Olympia is deferred on every chain.

New series, read by the panel and exported on `/metrics` beside the ones that were there: `app_engine_newpayload_accepted`,
`app_engine_auth_failures`, and `app_pow_dag_epoch` (`-1` before the first build), `app_pow_dag_phase` (0 not yet known,
1 loading, 2 generating), `app_pow_dag_progress` and `app_pow_dag_loaded`. None of them has a tag.

### Log tail and Recent strips

The Logs view and the Recent strips read an appender of the TUI's own, `TUI-LOG-RING`, on the root logger: a
lock-free ring of the newest `fukuii.tui.log-ring-size` lines (1000, at most 10,000) at `fukuii.tui.log-level` and
above (`INFO`). A logger's own level in `logback.xml` applies first, so `DEBUG` shows only what the loggers already log at DEBUG. The
ring is attached after the console appenders are suppressed and detached before they are restored, through the same
teardown ladder that gives the terminal back, so the console and the log file get what they got before; if it cannot
be attached, the TUI does not start and standard logging stays.

The Overview's Recent strip shows the newest 6 lines at S, 10 at M and fills the rest of the screen at L. The Sync
view's Recent (sync) strip keeps only the loggers under `com.chipprbots.ethereum.blockchain.sync`.

### Peers columns

| Tier | Columns |
|------|---------|
| S | id, direction, client, best block, age |
| M | id, direction, address, client, best block, score, age |
| L | id, direction, address, client, best block, score, trend, age (the Overview's top-8 table leaves out the address) |

The client is the name and version from the peer's Hello (`CoreGeth/v1.12.19`), and the best block comes from the
network peer manager (`GetHandshakedPeersCmd`). The score is the one `PeerScoringManager` last published to
`NetworkMetrics` (three decimals); a peer with no score yet shows `—`, never 0. The trend compares it with the scores
published before, at the same three decimals: `↑`, `↓` or `→`.

Text that comes from outside the node (a client id, a log line, a failure's reason) is cleaned before it is drawn:
escape sequences, control characters, zero-width and direction-override characters are removed, a multi-line text
keeps its first line and an ellipsis, and the length is capped at 512 characters. With the ASCII glyph set it is drawn
in ASCII only, in the same columns.

### Graphics

The TUI draws its pictures (the network icon, the mascot) in one of three modes over the same layout. A picture has
the same cells in every mode, and nothing is only a picture: what it shows is also in the frame's text.

| Mode | How it draws | Where |
|------|--------------|-------|
| `pixel` | Images, with the Kitty graphics protocol | A terminal that answers the protocol's query and reports its cell size: kitty, Ghostty |
| `block` | The same art in half-block character cells, in the 256-colour palette | Any 256-colour terminal with a UTF-8 locale; tmux and screen |
| `text` | No art: the words that stand for each picture | `NO_COLOR`, fewer than 256 colours |

`fukuii.tui.graphics = auto` (the default) asks the terminal once at start, for at most half a second, and picks
`pixel` if it answers, else `block`; under tmux or screen it picks `block` without asking. `FUKUII_TUI_GRAPHICS`
overrides the setting, `V` switches while the TUI runs, and `:graphics <mode>` sets one by name. Asking for `pixel` on
a terminal that cannot draw it gives `block`, and the footer and the log say why.

The **mascot** stands in the corner of the Overview's Recent strip from 100×32 (the large one from 140×45, where the
strip has the rows). It is a status lamp, and the words under it say the same: `starting`, `syncing`, `at head`,
`warning`, `error`, `stalled`, and `synced` for the one celebration. It moves only in pixel mode under full motion;
`P` and reduced motion stop it. Every image is removed from the terminal when the TUI leaves, by `Q`, `D`, Ctrl-C or
a lost terminal.

`:art` shows every sprite, a pose a page (`Up`/`Down` turn the page), in the mode on screen.
`fukuii.tui.art-dir` (or `FUKUII_TUI_ART_DIR`) names a directory of sprite sheets to use instead of the shipped ones;
`R` on the Art screen reads them again. A sheet that breaks
[the contract](https://github.com/chippr-robotics/fukuii/blob/staging/specs/029-tui-command-center/contracts/art.md)
is named in the log and on the Art screen and left out; its picture is drawn as words.

## Keyboard Commands

The same table Help draws (`Bindings.Global`). Every key that is an escape sequence has a single character that does
the same, so a link that delivers no arrow keys loses nothing.

| Key | Also | Action |
|-----|------|--------|
| `1` … `9` | | Overview, Chain, Peers, Mempool, RPC, Storage, System, Logs, Ops |
| `]` `[` | | The next and the previous view (wrap) |
| `Tab` `Shift-Tab` | | The next and the previous panel of the view |
| `Up` `Down` | `K` `J` | The row above and below (Peers: the selection; Logs: a line) |
| `Page Up` `Page Down` | `-` `+` | A page up and down (Logs: older and newer lines) |
| `Home` `End` | `<` `>` | The first and the last row |
| `Enter` | | Open the selected row (a peer's details) |
| `Esc` | `Backspace` | Back; clear the view's filter |
| `:` | | The prompt: a view's name, `peer <id>`, `glyphs emoji` or `glyphs ascii`, `graphics auto`, `pixel`, `block` or `text`, `art`, `detach`, `quit`, `help`. `Tab` completes, `Up`/`Down` walk the history |
| `/` | | Filter the list on screen as you type (Peers, Logs). `Enter` keeps the filter, `Esc` clears it |
| `?` | `H` `F1` | Help over the current view; any key but `Q`, `R` and `D` returns |
| `L` | | Logs |
| `G` | | Switch the glyph set (emoji / ASCII); no column moves |
| `V` | | Switch the graphics mode: pixel, block, text, as far as the terminal can; no text moves |
| `P` | | Pause or resume the motion (the frame counter stops; polling continues) |
| `R` | | Redraw the display (on the Art screen: read the sprite sheets again first) |
| `D` | | Detach: the TUI closes and standard logging returns; the node keeps running |
| `Q` | | **Ask** to stop the node: a dialog says what stops with it; `Y` stops it, `N` or `Esc` cancels. Nothing else answers it, pasted text included |
| `Ctrl-C` | | Stop the node at once, as a signal |

Any key skips the startup splash, and does nothing else. A change of view cancels the catch-up celebration. Opening
the Logs view shows its newest lines; while it is scrolled back, new lines arriving keep the lines on screen where
they are.

An arrow or a function key is an escape sequence. The TUI reads a sequence whole before deciding what it is: one it
knows is a key, any other is nothing, so the tail of a sequence is never read as keys. Text pasted into the terminal
arrives marked as a paste (bracketed paste) and goes to the prompt or a filter only: it cannot open a view, move a
selection or answer a dialog.

With `fukuii.tui.read-only = true` (or `FUKUII_TUI_READ_ONLY` set) `Q` does not offer to stop the node, and says so.
`Ctrl-C` still stops it.

Commands are case-insensitive (both `q` and `Q` work).

## Color Scheme

The console UI uses a green color scheme to match the Ethereum Classic branding:

- **Green**: Section headers, progress bars, healthy status, connected peers
- **Cyan**: Labels and field names
- **White**: Values and information
- **Yellow**: Warning states (low peers, initializing)
- **Red**: Error states (no peers, connection failures)

## Technical Details

### Implementation

- Built with JLine 3 for cross-platform terminal control
- Keyboard input is read with a bounded timeout (never JLine's `0 == wait forever`)
- Terminal resize is detected each frame and triggers a full redraw
- Runs in the alternate screen buffer; on exit the terminal mode, cursor and scrollback are restored

### Terminal Requirements

The console UI works best with:
- Terminal size: minimum 80x24 characters (larger recommended)
- UTF-8 encoding support for special characters
- ANSI color support

### Compatibility

Tested on:
- Linux (various distributions)
- macOS
- Windows (with proper terminal emulators)

For Windows users, we recommend:
- Windows Terminal
- ConEmu
- Git Bash
- WSL

### Fallback Behavior

If the console UI fails to initialize (no TTY — output redirected, `docker run` without `-t`, a systemd unit — or a `dumb` terminal), Fukuii will automatically:
1. Log a warning message
2. Fall back to standard logging mode
3. Continue running normally

## Architecture

All TUI code lives in `com.chipprbots.ethereum.console`:

| Component | Role |
|-----------|------|
| `Tui` | Owns the terminal: raw mode, alternate screen, frame drawing, key input, teardown. Refuses dumb/non-TTY terminals so escape codes never land in a log stream. |
| `TuiRenderer` | The compositor, a pure function `TuiState × width × height → lines`: header band, separator, alert row, the view's panels through `Layout`, footer. Every frame is exactly `height` rows of exactly `width` columns, so drawing it never scrolls or wraps; the footer always stays on the bottom row. |
| `TuiState` | Immutable view model. Sync speed/ETA are measured from the first block the TUI observed (not from genesis). |
| `TuiStatusProbe` | One poll of the node: handshaked peers with their id, address, direction and connection time (`PeerManagerActor.GetPeersCmd`, same as `net_peerCount`), sync status (`SyncProtocol.GetStatus`, same as `eth_syncing`), local best block, the latest SNAP progress sample, each peer's client id and best block (`NetworkPeerManagerActor.GetHandshakedPeersCmd`) and the txpool's size and blob count (`PendingTransactionsManager.GetPendingTransactionsReq`). All asks start together and share one 500 ms wait. It also reads, without an actor (`LocalSources`): disk free on the datadir, the JVM heap and GC time, `NetworkMetrics`' discovery counts and peer scores, the best header's timestamp, `SNAPSyncMetrics`' request counters and `RegularSyncMetrics`' last import; a failed reading leaves only its own field empty. |
| `Sourced` | Each polled source's freshness: `Fresh`, `Stale` (it missed a wait; its last answer is kept), `Unresponsive` (it never answered, failed, or has been silent for 5 s). A slow actor degrades its own line only. |
| `TuiProbeThread` | Daemon thread running the probe every `status-poll-interval-ms` and publishing the snapshot; it stops with the TUI. |
| `TuiUpdater` | Daemon frame loop: count a frame (`tick`), take the latest snapshot, render, then wait for a key at the frame's pace: `animation-interval-ms` (250 ms) while anything on screen moves, 1 s otherwise. The key wait is the pacing, so a key is handled as it arrives. A frame equal to the last is not written. |
| `Splash` | The startup splash, a view of the same renderer: the FUKUII logo by terminal size (`FukuiiBanner`, the literal the startup log prints), skipped by any key. |
| `TuiLogSuppressor` | Detaches the console appender while the TUI is active (file logging continues). |
| `TuiLogRingAppender` | The log tail: an appender of its own on the root logger, a lock-free ring of the newest lines, sanitised on capture. Attached after the console is suppressed and detached before it is restored, through `Tui`'s teardown ladder. |
| `Sanitize`, `TuiText.external` | Text from outside the node (client ids, log lines, failure reasons): no escape sequence or control character, one line, capped at 512 characters; at most four times that is read, whatever the text's length, so a peer's 16 MiB client id costs no more than a short one; ASCII only under the ASCII glyph set. |
| `TuiView` | The views and their keys; Help is a flag over the current view (`TuiState.helpOpen`). |
| `Layout`, `Panel` | Each view is a list of panels with minimum rows, preferred rows and a priority. `Layout.allocate` gives minimums in priority order (a panel whose minimum no longer fits is not drawn), then preferred rows, then the fill panel takes the rest; `Layout.columns` puts two row lists side by side, measured by columns. |
| `panels/` | The panels of each view: `OverviewPanels`, `SyncPanels`, `PeersPanel`, `ChainPanel`, `LogsPanel` (with the Recent strips), `HelpPanel`, with shared rows (`Rows`, `ForkRows`). |
| `Alerts` | The alert row's conditions, thresholds and severities (spec 027 research §2.3), measured on the snapshot's clock. |
| `ChainFacts` | Chain id, PoW/PoS (from `networkType`) and the fork timeline (ETC: `MilestoneLog` names by block; ETH: `ForkTimestamps` by time), read for display only. |

### Lifecycle

1. `Fukuii.main` sees `--tui`, validates config (errors are still printed to the console), then initializes the TUI and registers a JVM shutdown hook that restores the terminal.
2. `StdNode.start()` starts the frame loop first (`startTuiFrames()`), so the splash moves while the node starts, then, once the actors it asks exist, the probe thread (`startTuiUpdater()`).
3. `Q` restores the terminal, then exits the JVM; the node's shutdown hook stops the updater. `D` restores the terminal and console logging and the node keeps running. `Ctrl-C` behaves as without the TUI; the restore hook leaves the alternate screen even when JLine's own shutdown hook has closed the terminal first (JVM hooks run in no order), by writing the reset sequence to the process output.

Motion (the worm's ripple, the `[ACTIVE]` pulse, spinners, the splash and the first catch-up's celebration) comes only from the frame counter; the renderer reads no clock. Settings under `fukuii.tui` (`conf/base/tui.conf`): `glyphs`, `animation-interval-ms`, `status-poll-interval-ms`, `reduced-motion`, `splash-ms`, `log-ring-size` and `log-level`; the environment overrides are `NO_COLOR`, `FUKUII_TUI_GLYPHS`, `FUKUII_TUI_MOTION` (`full` or `reduced`) and `FUKUII_TUI_NO_SPLASH`. Spec 027's closing task rewrites this page for the full dashboard.

### Testing

`TuiSpec`, `TuiUpdaterSpec` and `TuiStatusProbeSpec` (under `src/test/.../console/`) drive the real classes against an in-memory xterm (`TuiTestTerminal`) and stub actors; `TuiRendererSpec` and `TuiStateSpec` cover layout and state.

## Future Enhancements

Potential improvements for future releases:

- **Transaction Pool**: Details of the pending transactions (the count and blob count are on the Overview)
- **Mining Status**: Show mining statistics when enabled
- **Configuration**: Terminal settings and color schemes
- **Log Viewer**: Browse recent log entries in the UI
- **Performance Metrics**: CPU, memory, disk usage

## Troubleshooting

### Console UI not displaying correctly

1. Check terminal size: `echo $COLUMNS x $LINES`
2. Verify UTF-8 support: `echo $LANG`
3. Try different terminal emulator
4. Remove `--tui` flag to use standard logging as fallback

### Terminal not cleaning up properly

If the terminal is left in a bad state after exit:
```bash
reset
```

### Colors not working

Ensure your terminal supports ANSI colors:
```bash
echo -e "\033[32mGreen\033[0m \033[31mRed\033[0m"
```

## Examples

### Standard startup with logging
```bash
./bin/fukuii etc
```

### Start with console UI for interactive monitoring
```bash
./bin/fukuii etc --tui
```

### Running in screen/tmux with console UI
```bash
screen -S fukuii
./bin/fukuii etc --tui
# Detach with Ctrl+A, D
```

### Background process (standard logging)
```bash
nohup ./bin/fukuii etc > fukuii.log 2>&1 &
```

### Logging to file
```bash
./bin/fukuii etc 2>&1 | tee fukuii.log
```

## See Also

- [First Start Guide](../runbooks/first-start.md)
- [Operations Runbooks](../runbooks/README.md)
- [Metrics & Monitoring](../operations/metrics-and-monitoring.md)
- Spec 027, TUI dashboard (`specs/027-tui-dashboard/` in the repository) — planned views, alert row, glyph sets and per-chain panels
