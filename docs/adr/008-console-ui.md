# ADR-008: Enhanced Console User Interface (TUI)

**Status**: Accepted

**Date**: November 2025

**Deciders**: Chippr Robotics LLC Engineering Team

## Context

Fukuii Ethereum Client operators and developers need real-time visibility into node status for monitoring, debugging, and operational awareness. Previously, the only way to monitor a running node was through:

1. **Log file inspection**: Requires tailing logs and parsing text output
2. **RPC queries**: Requires separate tools and scripting
3. **External monitoring**: Grafana dashboards and metrics exporters
4. **Health endpoints**: Limited to HTTP checks without rich status information

While these methods work for production deployments and automated monitoring, they lack immediate visual feedback for:
- Initial node startup and sync progress
- Direct operator interaction and debugging
- Development and testing workflows
- Quick health checks without additional tools

### User Stories

**Node Operator**: "I want to see at a glance if my node is syncing, how many peers are connected, and when sync will complete, without setting up external monitoring."

**Developer**: "During development and testing, I want immediate visual feedback on node state without parsing logs or writing scripts."

**System Administrator**: "I need a quick way to check node health during SSH sessions without installing additional monitoring tools."

### Technical Landscape

**Terminal UI Libraries:**
- **JLine 3**: Mature Java library for terminal control and line editing
- **Lanterna**: Pure Java TUI framework (heavier dependency)
- **Scala Native TUI**: Limited ecosystem, not suitable for JVM projects
- **ANSI Escape Codes**: Manual control (complex, error-prone)

**Design Patterns:**
- Dashboard/monitoring TUIs common in infrastructure tools (htop, k9s, lazydocker)
- Non-scrolling, grid-based layouts for status monitoring
- Keyboard-driven interaction for control
- Graceful degradation when terminal features unavailable

### Requirements

From Issue #300:
1. Enabled by default when using fukuii-launcher
2. Can be disabled with a flag on launch
3. Screen should not scroll (fixed layout)
4. Grid layout for organized information display
5. Display: peer connections, network, block height, sync progress
6. Basic keyboard commands (quit, toggle features)
7. Green color scheme matching Ethereum Classic branding
8. Proper terminal cleanup on exit

## Decision

We decided to implement an **Enhanced Console User Interface (TUI)** using JLine 3 with the following design:

### Architecture

**Component Structure:**
- `ConsoleUI`: Core rendering and terminal management
- `ConsoleUIUpdater`: Background status polling and updates
- Integration points: `Fukuii.scala` (initialization), `StdNode.scala` (lifecycle)

**Key Design Choices:**

1. **JLine 3 as Terminal Library**
   - Already a project dependency (used for CLI commands)
   - Cross-platform (Linux, macOS, Windows)
   - Robust terminal capability detection
   - No additional dependencies required

2. **Grid-Based Fixed Layout**
   - Non-scrolling display with sections
   - Automatic terminal size adaptation
   - Organized sections: Network, Blockchain, Runtime
   - Visual separators between sections

3. **Default Enabled with Opt-Out**
   - `--no-tui` flag to disable for headless/background mode
   - Automatic fallback on initialization failure
   - No impact on existing deployments using systemd/docker

4. **Singleton Pattern**
   - Single ConsoleUI instance per process
   - Thread-safe state management with `@volatile` variables
   - Proper cleanup on shutdown

5. **Non-Blocking Updates**
   - Background thread for periodic updates (1 second interval)
   - Non-blocking keyboard input checking
   - Doesn't interfere with actor system or node operations

6. **Visual Design**
   - Ethereum Classic logo (ASCII art from community)
   - Green/cyan color scheme (ETC branding)
   - Progress bars for sync status
   - Color-coded indicators (green=healthy, yellow=warning, red=error)
   - Visual peer count indicators

### Implementation Details

**Keyboard Commands:**
- `Q`: Quit application
- `R`: Refresh/redraw display
- `D`: Disable UI (switch to standard logging)

**Display Sections:**
1. Header with branding
2. Ethereum Classic ASCII logo (when space permits)
3. Network & Connection (network name, status, peer count)
4. Blockchain (current block, best block, sync progress)
5. Runtime (uptime)
6. Footer with keyboard commands

**Graceful Degradation:**
- Initialization failure → automatic fallback to standard logging
- Unsupported terminal → logs warning and continues
- Small terminal → adapts layout (hides logo if needed)
- `--no-tui` flag → skips initialization entirely

## Consequences

### Positive

1. **Improved User Experience**
   - Immediate visual feedback on node status
   - No external tools required for basic monitoring
   - Intuitive, self-documenting interface
   - Reduces time to understand node state

2. **Better Development Workflow**
   - Real-time feedback during development
   - Quick health checks without log parsing
   - Visual confirmation of changes
   - Easier debugging of sync issues

3. **Minimal System Impact**
   - Updates every 1 second (low overhead)
   - No additional dependencies
   - Graceful fallback maintains compatibility
   - Clean separation from core node logic

4. **Operational Flexibility**
   - `--no-tui` for automation and scripting
   - Works in SSH sessions
   - Compatible with screen/tmux
   - Doesn't interfere with log aggregation

5. **Community Alignment**
   - Uses community-contributed ASCII art
   - Matches Ethereum Classic branding
   - Follows TUI best practices from ecosystem
   - Enables better documentation and support

### Negative

1. **Terminal Compatibility**
   - May not work on all terminal emulators
   - Windows requires proper terminal (Windows Terminal, ConEmu)
   - Legacy terminals may have limited color support
   - Mitigated by: automatic fallback, documentation

2. **Accessibility**
   - Screen readers may not work well with TUI
   - Colorblind users may have difficulty with color indicators
   - Mitigated by: `--no-tui` flag, text-based status in addition to colors

3. **Maintenance Overhead**
   - Additional code to maintain and test
   - Cross-platform terminal behavior differences
   - Mitigated by: isolated component, comprehensive error handling

4. **Limited Interaction**
   - Currently read-only monitoring (no configuration changes)
   - Cannot show detailed logs or full peer list
   - Future enhancement: multiple views/tabs

### Trade-offs

**Chosen**: Fixed grid layout with 1-second updates
**Alternative**: Scrolling log view with embedded status
**Rationale**: Non-scrolling layout provides stable, easy-to-read dashboard. Logs still available with `--no-tui`.

**Chosen**: JLine 3 library
**Alternative**: Lanterna framework, raw ANSI codes
**Rationale**: JLine 3 already in dependencies, lighter than Lanterna, more robust than raw ANSI.

**Chosen**: Background polling for status
**Alternative**: Actor messages for real-time push updates
**Rationale**: Simpler implementation, isolated from actor system, easier to maintain. 1-second updates sufficient for monitoring.

**Chosen**: Singleton pattern
**Alternative**: Actor-based UI component
**Rationale**: Terminal is inherently a singleton resource, simpler lifecycle management.

## Implementation Notes

### Code Organization

```
src/main/scala/com/chipprbots/ethereum/console/
├── ConsoleUI.scala          # Main UI rendering and terminal management
└── ConsoleUIUpdater.scala   # Background status polling
```

### Integration Points

1. **Fukuii.scala**: Command-line parsing, initialization
2. **StdNode.scala**: Lifecycle integration, updater startup
3. **App.scala**: Help text with `--no-tui` documentation

### Testing Strategy

- Manual testing on multiple platforms (Linux, macOS, Windows)
- Terminal emulator compatibility testing
- Error handling verification (terminal failures)
- Performance impact measurement (CPU, memory)
- Integration testing with node startup/shutdown

### Documentation

- `docs/console-ui.md`: Comprehensive user guide
- `docs/adr/008-console-ui.md`: This ADR
- Updated README.md with console UI information
- Help text with `--no-tui` flag

## Alternatives Considered

### 1. Web-Based Dashboard

**Approach**: Built-in HTTP server with JavaScript frontend

**Pros:**
- Rich interaction possibilities
- Better accessibility
- Cross-platform consistency
- Can be accessed remotely

**Cons:**
- Significant additional complexity
- Browser dependency
- Security concerns (authentication, CORS)
- Overhead of web server and assets
- Not suitable for quick local monitoring

**Decision**: Rejected - Too complex for basic monitoring needs. Web dashboards better suited as separate projects.

### 2. External Monitoring Only

**Approach**: Rely on metrics exporters, Grafana, and health endpoints

**Pros:**
- No additional code in node
- Production-grade monitoring tools
- Centralized monitoring for multiple nodes

**Cons:**
- Requires setup and infrastructure
- Not suitable for development/testing
- Overhead for single-node operators
- No immediate feedback during startup

**Decision**: Rejected - External monitoring still valuable, but doesn't replace need for immediate local visibility.

### 3. Enhanced Logging Only

**Approach**: Structured logging with better formatting

**Pros:**
- Minimal complexity
- Works everywhere
- Easy to parse programmatically

**Cons:**
- Scrolling output difficult to read
- No real-time status dashboard
- Harder to get quick overview
- Still requires log parsing

**Decision**: Rejected - Logging is complementary but doesn't provide dashboard-style monitoring.

### 4. Curses/ncurses Binding

**Approach**: Use native terminal libraries via JNI

**Pros:**
- Full terminal control
- Rich TUI possibilities
- High performance

**Cons:**
- Platform-specific binaries
- Complex build process
- JNI overhead and complexity
- Harder to maintain

**Decision**: Rejected - JLine 3 provides sufficient functionality without JNI complexity.

## Future Enhancements

Potential improvements for future releases:

1. **Multiple Views/Tabs**
   - Toggle between dashboard, logs, peers, transactions
   - Keyboard shortcuts for view switching

2. **Detailed Peer Information**
   - List of connected peers
   - Per-peer statistics
   - Peer discovery status

3. **Transaction Pool View**
   - Pending transaction count
   - Transaction details
   - Gas price statistics

4. **Interactive Configuration**
   - Runtime configuration changes
   - Feature toggles
   - Log level adjustment

5. **Historical Charts**
   - Block import rate over time
   - Peer count trends
   - Sync progress visualization

6. **Mouse Support**
   - Click to navigate
   - Scroll through lists
   - Select and copy text

7. **Customization**
   - User-configurable layout
   - Theme selection
   - Metric preferences

## References

- Issue #300: Improved c-ux
- PR #301: Implementation
- JLine 3 Documentation: https://github.com/jline/jline3
- Terminal UI Best Practices: https://clig.dev/
- Ethereum Classic Branding: Community-contributed ASCII art

## Related ADRs

- [ADR-001: Scala 3 Migration](001-scala-3-migration.md) - Scala 3 context for implementation

## Changelog

- **November 2025**: Initial implementation with basic monitoring features
