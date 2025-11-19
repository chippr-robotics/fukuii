# Logging Guidelines for Fukuii

This document describes the categorization guidelines for log messages in the Fukuii Ethereum Classic client.

## Log Level Categories

### ERROR
Use for failures requiring immediate attention or indicating serious problems:
- Connection failures that terminate connections
- Message decoding errors that close connections
- Critical system failures
- Database corruption or access failures
- Security-related issues

**Examples:**
- `log.error("Cannot decode message from {}, because of {}", peerId, errorMsg)`
- `log.error("[Stopping Connection] Auth handshake FAILED for peer {}", peerId)`
- `log.error("Database corruption detected at block {}", blockNumber)`

### WARN
Use for unexpected but recoverable situations that may indicate problems:
- Dismissed or invalid data from peers
- Branch resolution issues
- Timeout conditions that trigger retries
- Blacklisting events (though INFO is also acceptable)
- Configuration issues that have fallbacks

**Examples:**
- `log.warn("Dismissed received headers due to: {} (peer: {})", reason, peerId)`
- `log.warn("Unknown branch, going back to block nr {} in order to resolve branches", blockNumber)`
- `log.warn("Pivot block header receive timeout. Retrying in {}", interval)`

### INFO
Use for significant state changes and important milestones:
- System startup and shutdown
- Sync mode changes (starting/stopping fast sync, regular sync)
- Mining state changes
- Successful pivot block selection
- Block synchronization completion
- Important configuration changes
- Major component initialization

**Examples:**
- `log.info("Starting regular sync")`
- `log.info("Block synchronization in fast mode finished, switching to regular mode")`
- `log.info("Auth handshake SUCCESS for peer {}, establishing secure connection", peerId)`
- `log.info("Connection FULLY ESTABLISHED with peer {}, entering handshaked state", peerId)`
- `log.info("Blacklisting peer [{}] for {}. Reason: {}", id, duration, reason.description)`

### DEBUG
Use for detailed operational information useful during troubleshooting:
- Detailed protocol handshake steps
- Received/sent message counts from peers
- Block/header/receipt processing details
- Pivot block updates and changes
- Checkpoint processing
- Block import operations
- State sync progress details
- Peer voting and selection details
- Request/response cycles

**Examples:**
- `log.debug("[RLPx] Received auth handshake init message for peer {} ({} bytes)", peerId, data.length)`
- `log.debug("Received {} block headers from peer [{}] in {} ms", count, peerId, time)`
- `log.debug("Changing pivot block to {}, new safe target is {}", blockNumber, target)`
- `log.debug("Imported new block [number = {}, internally = {}]", blockNumber, internally)`
- `log.debug("Requesting {} from peer {}", request.nodes.size, peerId)`

### TRACE
Currently not widely used in the codebase. Reserve for extremely detailed debugging information.

## Specific Subsystem Guidelines

### RLPx Connection Handling
- Connection initiation/acceptance: **DEBUG**
- TCP connection established: **DEBUG**
- Detailed handshake steps: **DEBUG**
- Protocol negotiation details: **DEBUG**
- Successful handshake completion: **INFO**
- Full connection establishment: **INFO**
- Connection failures/timeouts: **ERROR**
- Message decode errors: **ERROR**

### Sync (Fast and Regular)
- Sync start/stop: **INFO**
- Received block data from peers: **DEBUG**
- Pivot block selection/updates: **DEBUG** (except final selection: **INFO**)
- State sync progress: **DEBUG**
- Sync strategy selection: **INFO**
- Sync completion: **INFO**
- Retry attempts: **DEBUG**
- Sync failures requiring fallback: **WARN**

### Block Processing
- Block mined: **INFO**
- Block imported: **DEBUG**
- Checkpoint received: **DEBUG**
- Branch resolution issues: **WARN**
- Invalid blocks: **WARN**

### Peer Management
- Peer discovery start/stop: **INFO**
- Blacklisting peers: **INFO**
- Dismissed invalid data: **WARN**

### Mining
- Mining mode changes: **INFO**
- Miner instantiation: **INFO**
- Mining enable/disable: **INFO**

### Database
- Database open/close: **INFO**
- Detailed initialization steps: **DEBUG**
- Database errors: **ERROR**

## Migration Notes

During the recent log categorization effort (Issue: "reduce the noise"), the following changes were made:

1. **RLPx Protocol**: Moved detailed handshake steps from INFO to DEBUG to reduce noise during normal operation
2. **Fast Sync**: Moved routine receive operations (headers, bodies, receipts) from INFO to DEBUG
3. **Regular Sync**: Moved block import and checkpoint operations from INFO to DEBUG
4. **Block Fetcher**: Changed dismissed headers from INFO to WARN (they indicate problems)
5. **Block Importer**: Changed branch resolution issues from INFO to WARN
6. **Sync Strategies**: Moved retry messages from INFO to DEBUG
7. **State Sync**: Moved node request/response logs from INFO to DEBUG
8. **Pivot Selection**: Moved voting details from INFO to DEBUG, timeouts to WARN

## General Principles

1. **INFO should be sparse**: A running node should produce INFO logs only occasionally for significant events
2. **DEBUG is for troubleshooting**: Enable DEBUG level when diagnosing sync or connection issues
3. **ERROR means action needed**: ERROR logs should indicate something requiring investigation or action
4. **WARN indicates problems**: WARN logs should flag unexpected but handled situations
5. **Consider frequency**: If a log would fire hundreds of times during normal operation, it's probably DEBUG
6. **Consider audience**: INFO is for operators, DEBUG is for developers

## Testing Log Levels

When adding or modifying log statements, consider:

1. Will this fire frequently during normal operation? → DEBUG
2. Does this indicate a problem? → WARN or ERROR
3. Is this a major state transition? → INFO
4. Is this helpful for troubleshooting specific issues? → DEBUG
5. Would an operator need to see this in production? → INFO, otherwise DEBUG

## Log Format Conventions

- Use structured logging with placeholders: `log.info("Block {} imported", blockNumber)`
- Include relevant context: peer IDs, block numbers, error details
- Keep messages concise but informative
- Use consistent terminology across the codebase
- Prefix subsystem-specific logs with tags like `[RLPx]`, `[FastSync]`, etc. when helpful
