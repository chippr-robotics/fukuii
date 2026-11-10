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
 RPC     :8545 on                 Disk   1.2T free
────────────────────────────────────────────────────────────────────────────────
 Sync    regular                                 rate 184.37 blk/s · ETA 5h 14m
   Blocks   [===============🪱....]🧠 84%   18,402,113 / 21,884,102
────────────────────────────────────────────────────────────────────────────────
 Forks   Mystique ✓ ════════════════════════▶ Spiral @ 19,250,000 (847.9K away)
────────────────────────────────────────────────────────────────────────────────
 Node    …/fukuii/etc · Full sync · archive pruning · 50 peers max · mining off











 1 Over 2 Sync 3 Peers 4 Chain │ p Pause ? Help r Redraw d Detach q Quit
```

The blank rows at the bottom are where spec 027 slice 4 puts the recent-log strip. Sizes: below 100 columns or 32
rows is tier S, from 100×32 tier M, from 140×45 tier L; each view fills its tier (at L the Overview draws the logo in a
right-hand column). A panel that does not fit gives up its rows to the ones that matter more; the header, the alert
row and the footer always stay.

### Views

| Key | View | What it shows |
|-----|------|---------------|
| `1` | Overview | Peers and blocks, endpoints, disk and heap (GC and discovery at L), the sync (SNAP's five worm rows or the Blocks worm, with ETA), the forks, the node's settings; at L the longest-connected peers |
| `2` | Sync | The worm rows on a 20-, 40- or 60-cell track; SNAP's rates, request counters, snap-capable peers and phase timeline (M and L); a regular sync's session and lag |
| `3` | Peers | Each handshaked peer: id, direction, address, time connected |
| `4` | Chain | Chain id, PoW or PoS, the fork timeline: by block number on ETC, by timestamp on ETH, the next fork with its distance |
| `?` `H` | Help | The keys and the legend of the glyph set on screen |

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

## Keyboard Commands

| Key | Action |
|-----|--------|
| `1` `2` `3` `4` | Overview, Sync, Peers, Chain |
| `Tab` | The next view (wraps) |
| `?` or `H` | Help over the current view: the keys and the glyph legend; any key but `Q`, `R` and `D` returns |
| `G` | Switch the glyph set (emoji / ASCII); no column moves |
| `P` | Pause or resume the motion (the frame counter stops; polling continues) |
| `R` | Redraw the display |
| `D` | Detach: the TUI closes and standard logging returns; the node keeps running |
| `Q` | Quit: stops the node |

Any key skips the startup splash, and does nothing else. A change of view cancels the catch-up celebration. The Logs
view (`5`, `L`, `+`, `-`) arrives with spec 027 slice 4.

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
| `TuiStatusProbe` | One poll of the node: handshaked peers with their id, address, direction and connection time (`PeerManagerActor.GetPeersCmd`, same as `net_peerCount`), sync status (`SyncProtocol.GetStatus`, same as `eth_syncing`), local best block, and the latest SNAP progress sample. Both asks start together and share one 500 ms wait. It also reads, without an actor (`LocalSources`): disk free on the datadir, the JVM heap and GC time, `NetworkMetrics`' discovery counts, the best header's timestamp and `SNAPSyncMetrics`' request counters; a failed reading leaves only its own field empty. |
| `Sourced` | Each polled source's freshness: `Fresh`, `Stale` (it missed a wait; its last answer is kept), `Unresponsive` (it never answered, failed, or has been silent for 5 s). A slow actor degrades its own line only. |
| `TuiProbeThread` | Daemon thread running the probe every `status-poll-interval-ms` and publishing the snapshot; it stops with the TUI. |
| `TuiUpdater` | Daemon frame loop: count a frame (`tick`), take the latest snapshot, render, then wait for a key at the frame's pace: `animation-interval-ms` (250 ms) while anything on screen moves, 1 s otherwise. The key wait is the pacing, so a key is handled as it arrives. A frame equal to the last is not written. |
| `Splash` | The startup splash, a view of the same renderer: the FUKUII logo by terminal size (`FukuiiBanner`, the literal the startup log prints), skipped by any key. |
| `TuiLogSuppressor` | Detaches the console appender while the TUI is active (file logging continues). |
| `TuiView` | The views and their keys; Help is a flag over the current view (`TuiState.helpOpen`). |
| `Layout`, `Panel` | Each view is a list of panels with minimum rows, preferred rows and a priority. `Layout.allocate` gives minimums in priority order (a panel whose minimum no longer fits is not drawn), then preferred rows, then the fill panel takes the rest; `Layout.columns` puts two row lists side by side, measured by columns. |
| `panels/` | The panels of each view: `OverviewPanels`, `SyncPanels`, `PeersPanel`, `ChainPanel`, `HelpPanel`, with shared rows (`Rows`, `ForkRows`). |
| `Alerts` | The alert row's conditions, thresholds and severities (spec 027 research §2.3), measured on the snapshot's clock. |
| `ChainFacts` | Chain id, PoW/PoS (from `networkType`) and the fork timeline (ETC: `MilestoneLog` names by block; ETH: `ForkTimestamps` by time), read for display only. |

### Lifecycle

1. `Fukuii.main` sees `--tui`, validates config (errors are still printed to the console), then initializes the TUI and registers a JVM shutdown hook that restores the terminal.
2. `StdNode.start()` starts the frame loop first (`startTuiFrames()`), so the splash moves while the node starts, then, once the actors it asks exist, the probe thread (`startTuiUpdater()`).
3. `Q` restores the terminal, then exits the JVM; the node's shutdown hook stops the updater. `D` restores the terminal and console logging and the node keeps running. `Ctrl-C` behaves as without the TUI; the restore hook leaves the alternate screen even when JLine's own shutdown hook has closed the terminal first (JVM hooks run in no order), by writing the reset sequence to the process output.

Motion (the worm's ripple, the `[ACTIVE]` pulse, spinners, the splash and the first catch-up's celebration) comes only from the frame counter; the renderer reads no clock. Settings under `fukuii.tui` (`conf/base/tui.conf`): `glyphs`, `animation-interval-ms`, `status-poll-interval-ms`, `reduced-motion` and `splash-ms`; the environment overrides are `NO_COLOR`, `FUKUII_TUI_GLYPHS`, `FUKUII_TUI_MOTION` (`full` or `reduced`) and `FUKUII_TUI_NO_SPLASH`. Spec 027's closing task rewrites this page for the full dashboard.

### Testing

`TuiSpec`, `TuiUpdaterSpec` and `TuiStatusProbeSpec` (under `src/test/.../console/`) drive the real classes against an in-memory xterm (`TuiTestTerminal`) and stub actors; `TuiRendererSpec` and `TuiStateSpec` cover layout and state.

## Future Enhancements

Potential improvements for future releases:

- **Logs view and richer peers** (spec 027 slice 4): the recent-log strip, client ids, best blocks and peer scores
- **Transaction Pool**: Display pending transaction count and details
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
