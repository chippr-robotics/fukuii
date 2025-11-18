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
- Non-blocking keyboard input for responsive control
- Automatic terminal size detection and adjustment
- Proper cleanup on exit (restores cursor, clears colors)

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

If the console UI fails to initialize (e.g., unsupported terminal), Fukuii will automatically:
1. Log a warning message
2. Fall back to standard logging mode
3. Continue running normally

## Architecture

The console UI system consists of three main components:

### ConsoleUI

Main UI rendering class that:
- Manages terminal initialization and cleanup
- Handles keyboard input
- Renders the display with sections and formatting
- Maintains state (peer count, blocks, etc.)

### ConsoleUIUpdater

Background updater that:
- Periodically queries node status
- Updates the ConsoleUI state
- Triggers re-renders
- Processes keyboard commands

### Integration Points

The console UI integrates with:
- `Fukuii.scala`: Initialization and command-line flag parsing
- `StdNode.scala`: Node lifecycle (start/stop)
- Actor system: Queries PeerManager and SyncController for status

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

- [First Start Guide](runbooks/first-start.md)
- [Operations Runbooks](runbooks/README.md)
- [Metrics & Monitoring](operations/metrics-and-monitoring.md)
