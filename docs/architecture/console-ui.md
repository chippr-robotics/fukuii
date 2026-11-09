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

```
┌────────────────────────────────────────────────────────────────┐
│             ◆ FUKUII ETHEREUM CLIENT ◆                         │
├────────────────────────────────────────────────────────────────┤
│                    [Ethereum Classic Logo]                     │
├────────────────────────────────────────────────────────────────┤
│ ● NETWORK & CONNECTION                                         │
│   Network: ETHEREUM CLASSIC                                    │
│   Connection: ● Connected                                      │
│   Peers: 25 / 50 ◆◆◆◆◆◆◆◆◆◆                                   │
├────────────────────────────────────────────────────────────────┤
│ ● BLOCKCHAIN                                                   │
│   Current Block: 15,234,567                                    │
│   Best Block: 15,234,890                                       │
│   Sync Status: Syncing                                         │
│   Sync Progress: [████████████████░░░░░░] 98.45%              │
│   Blocks Remaining: 323                                        │
│   Est. Sync Time: 2m 15s                                       │
│   Sync Speed: 2.35 blocks/sec                                  │
├────────────────────────────────────────────────────────────────┤
│ ● RUNTIME                                                      │
│   Uptime: 1h 23m 45s                                           │
├────────────────────────────────────────────────────────────────┤
│ Commands: [Q]uit | [R]efresh | [D]isable UI                   │
└────────────────────────────────────────────────────────────────┘
```

## Keyboard Commands

| Key | Action |
|-----|--------|
| `Q` | Quit the application |
| `R` | Refresh/redraw the display |
| `D` | Disable the console UI (switch to standard logging) |
| `P` | Pause or resume the motion (the frame counter stops; polling continues) |

Any key skips the startup splash, and does nothing else.

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
| `TuiRenderer` | Pure function `TuiState × width × height → lines`. Every frame is exactly `height` rows of exactly `width` columns, so drawing it never scrolls or wraps; the command footer always stays on the bottom row. |
| `TuiState` | Immutable view model. Sync speed/ETA are measured from the first block the TUI observed (not from genesis). |
| `TuiStatusProbe` | One poll of the node: handshaked peers (`PeerManagerActor.GetPeersCmd`, same as `net_peerCount`), sync status (`SyncProtocol.GetStatus`, same as `eth_syncing`), local best block, and the latest SNAP progress sample. Both asks start together and share one 500 ms wait. |
| `Sourced` | Each polled source's freshness: `Fresh`, `Stale` (it missed a wait; its last answer is kept), `Unresponsive` (it never answered, failed, or has been silent for 5 s). A slow actor degrades its own line only. |
| `TuiProbeThread` | Daemon thread running the probe every `status-poll-interval-ms` and publishing the snapshot; it stops with the TUI. |
| `TuiUpdater` | Daemon frame loop: count a frame (`tick`), take the latest snapshot, render, then wait for a key at the frame's pace: `animation-interval-ms` (250 ms) while anything on screen moves, 1 s otherwise. The key wait is the pacing, so a key is handled as it arrives. A frame equal to the last is not written. |
| `Splash` | The startup splash, a view of the same renderer: the FUKUII logo by terminal size (`FukuiiBanner`, the literal the startup log prints), skipped by any key. |
| `TuiLogSuppressor` | Detaches the console appender while the TUI is active (file logging continues). |

### Lifecycle

1. `Fukuii.main` sees `--tui`, validates config (errors are still printed to the console), then initializes the TUI and registers a JVM shutdown hook that restores the terminal.
2. `StdNode.start()` starts the frame loop first (`startTuiFrames()`), so the splash moves while the node starts, then, once the actors it asks exist, the probe thread (`startTuiUpdater()`).
3. `Q` restores the terminal, then exits the JVM; the node's shutdown hook stops the updater. `D` restores the terminal and console logging and the node keeps running. `Ctrl-C` behaves as without the TUI; the restore hook leaves the alternate screen even when JLine's own shutdown hook has closed the terminal first (JVM hooks run in no order), by writing the reset sequence to the process output.

Motion (the worm's ripple, the `[ACTIVE]` pulse, spinners, the splash and the first catch-up's celebration) comes only from the frame counter; the renderer reads no clock. Settings under `fukuii.tui` (`conf/base/tui.conf`): `glyphs`, `animation-interval-ms`, `status-poll-interval-ms`, `reduced-motion` and `splash-ms`; the environment overrides are `NO_COLOR`, `FUKUII_TUI_GLYPHS`, `FUKUII_TUI_MOTION` (`full` or `reduced`) and `FUKUII_TUI_NO_SPLASH`. Spec 027's closing task rewrites this page for the full dashboard.

### Testing

`TuiSpec`, `TuiUpdaterSpec` and `TuiStatusProbeSpec` (under `src/test/.../console/`) drive the real classes against an in-memory xterm (`TuiTestTerminal`) and stub actors; `TuiRendererSpec` and `TuiStateSpec` cover layout and state.

## Future Enhancements

Potential improvements for future releases:

- **Additional Views**: Toggle between different information panels (logs, peers, transactions)
- **Detailed Peer Info**: Show individual peer details
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
