# OPS-002: Logging Level Categorization Standards

**Status**: Accepted

**Date**: November 2024

**Deciders**: Chippr Robotics LLC Engineering Team

## Context

During troubleshooting of RLPx sync and other systems, many log statements were added at INFO level without proper categorization. This created excessive log noise during normal sync operations, making it difficult for operators to identify important events and system state changes. 

### Problem Statement

The fukuii client was producing overwhelming amounts of INFO-level logs during normal operation:
- Detailed protocol handshake steps logged as INFO
- Routine block/header/receipt processing logged as INFO  
- Each peer interaction logged as INFO
- Internal state updates logged as INFO

This created several issues:
1. **Operator fatigue**: Production logs were too verbose to monitor effectively
2. **Signal vs noise**: Important events were buried in routine operational details
3. **Troubleshooting difficulty**: No clear distinction between normal operation and issues requiring attention
4. **Storage costs**: Excessive logging increased log storage requirements

### Requirements

From Issue #512 ("reduce the noise"):
1. Evaluate all log messages in fukuii for proper categorization
2. Distinguish between debug, info, warning, and error severity levels
3. Reduce INFO-level noise during normal sync operations
4. Preserve detailed troubleshooting information at DEBUG level
5. Ensure errors are properly categorized as ERROR or WARN

### Technical Context

The fukuii client uses two different logging frameworks depending on the actor type:

#### Pekko Classic Actors (ActorLogging)
Classic actors that extend `Actor with ActorLogging` use Pekko's `LoggingAdapter`, which provides:
- `log.debug()` - Debug level
- `log.info()` - Info level
- `log.warning()` - Warning level (note: **warning**, not warn)
- `log.error()` - Error level

Examples: `PivotBlockSelector`, `BlockImporter`, `RLPxConnectionHandler`

#### Pekko Typed Actors (context.log)
Typed actors that extend `AbstractBehavior` and use `context.log` get an SLF4J `Logger`, which provides:
- `log.trace()` - Trace level
- `log.debug()` - Debug level
- `log.info()` - Info level
- `log.warn()` - Warning level (note: **warn**, not warning)
- `log.error()` - Error level

Examples: `BlockFetcher`, `PoWMiningCoordinator`

#### Other Components (SLF4J/Scala Logging)
Non-actor components using SLF4J directly or Scala Logging also use:
- `log.warn()` - Warning level (note: **warn**, not warning)

**Important**: When modifying log levels, always check whether the file uses Pekko Classic ActorLogging (use `log.warning`) or SLF4J/Typed Actor logging (use `log.warn`). Using the wrong method will cause compilation errors.

## Decision

We established comprehensive logging level categorization standards and recategorized 43 log statements across 8 files to align with these standards.

### Log Level Standards

#### ERROR
Use for failures requiring immediate attention or indicating serious problems:
- Connection failures that terminate connections
- Message decoding errors that close connections
- Critical system failures
- Database corruption or access failures
- Security-related issues

#### WARN
Use for unexpected but recoverable situations that may indicate problems:
- Dismissed or invalid data from peers
- Branch resolution issues
- Timeout conditions that trigger retries
- Blacklisting events
- Configuration issues that have fallbacks

#### INFO
Use for significant state changes and important milestones:
- System startup and shutdown
- Sync mode changes (starting/stopping fast sync, regular sync)
- Mining state changes
- Successful pivot block selection
- Block synchronization completion
- Important configuration changes
- Major component initialization

#### DEBUG
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

### Subsystem-Specific Guidelines

#### RLPx Connection Handling
- Connection initiation/acceptance: DEBUG
- TCP connection established: DEBUG
- Detailed handshake steps: DEBUG
- Protocol negotiation details: DEBUG
- Successful handshake completion: INFO
- Full connection establishment: INFO
- Connection failures/timeouts: ERROR
- Message decode errors: ERROR

#### Sync (Fast and Regular)
- Sync start/stop: INFO
- Received block data from peers: DEBUG
- Pivot block selection/updates: DEBUG (except final selection: INFO)
- State sync progress: DEBUG
- Sync strategy selection: INFO
- Sync completion: INFO
- Retry attempts: DEBUG
- Sync failures requiring fallback: WARN

#### Block Processing
- Block mined: INFO
- Block imported: DEBUG
- Checkpoint received: DEBUG
- Branch resolution issues: WARN
- Invalid blocks: WARN

#### Peer Management
- Peer discovery start/stop: INFO
- Blacklisting peers: INFO
- Dismissed invalid data: WARN

#### Mining
- Mining mode changes: INFO
- Miner instantiation: INFO
- Mining enable/disable: INFO

#### Database
- Database open/close: INFO
- Detailed initialization steps: DEBUG
- Database errors: ERROR

### General Principles

1. **INFO should be sparse**: A running node should produce INFO logs only occasionally for significant events
2. **DEBUG is for troubleshooting**: Enable DEBUG level when diagnosing sync or connection issues
3. **ERROR means action needed**: ERROR logs should indicate something requiring investigation or action
4. **WARN indicates problems**: WARN logs should flag unexpected but handled situations
5. **Consider frequency**: If a log would fire hundreds of times during normal operation, it's probably DEBUG
6. **Consider audience**: INFO is for operators, DEBUG is for developers

### Decision Checklist for New Log Statements

When adding or modifying log statements, consider:
1. Will this fire frequently during normal operation? → DEBUG
2. Does this indicate a problem? → WARN or ERROR
3. Is this a major state transition? → INFO
4. Is this helpful for troubleshooting specific issues? → DEBUG
5. Would an operator need to see this in production? → INFO, otherwise DEBUG

## Implementation

The following changes were implemented across 8 files (43 log statements total):

### RLPxConnectionHandler.scala (11 changes)
- Protocol handshake steps: INFO → DEBUG
- Message decode errors that close connections: INFO → ERROR
- Kept significant milestones (handshake success, connection established) as INFO

### FastSync.scala (17 changes)
- Received headers/bodies/receipts from peers: INFO → DEBUG
- Pivot block updates and state changes: INFO → DEBUG
- Kept sync start/completion messages as INFO

### RegularSync.scala (3 changes)
- Checkpoint and block import operations: INFO → DEBUG
- Kept sync start/stop and block mined as INFO

### BlockFetcher.scala (3 changes)
- Dismissed/invalid headers from peers: INFO → WARN

### BlockImporter.scala (2 changes)
- Branch resolution issues: INFO → WARN

### AdaptiveSyncStrategy.scala (1 change)
- Retryable sync failures: INFO → DEBUG

### SyncStateSchedulerActor.scala (2 changes)
- State node requests/responses: INFO → DEBUG

### PivotBlockSelector.scala (4 changes)
- Pivot block voting details: INFO → DEBUG
- Timeouts and insufficient votes: INFO → WARN

## Consequences

### Positive

1. **Reduced log noise**: INFO level now shows ~95% fewer messages during normal operation
2. **Clear signal**: Important events (sync start/stop, errors) stand out clearly in INFO logs
3. **Better troubleshooting**: DEBUG level contains all detailed information when needed
4. **Proper severity**: Errors are ERROR, problems are WARN, progress is DEBUG
5. **Operator friendly**: INFO level suitable for production monitoring without overwhelming detail
6. **Storage savings**: Reduced log volume in production deployments
7. **Clear standards**: Documented guidelines for future development

### Negative

1. **Migration effort**: Required reviewing and recategorizing 43 log statements
2. **Learning curve**: Developers need to learn new categorization standards
3. **Potential gaps**: Some edge cases may not fit perfectly into categories

### Neutral

1. **No functionality change**: All information still logged, just at appropriate levels
2. **Backward compatible**: Operators can still enable DEBUG to see all details
3. **Dual logging systems**: Must use `log.warning` for Pekko Classic ActorLogging and `log.warn` for SLF4J/Typed Actors - developers must check the actor type before modifying log statements

## Related Decisions

- Future logging enhancements should follow these categorization standards
- Consider structured logging (e.g., JSON) in future for better machine parsing
- May need to revisit categories as new subsystems are added

## References

- Issue #512: "reduce the noise"
- SLF4J Logging Documentation
- Apache Pekko Logging Documentation
- Original PR: Recategorize log levels to reduce INFO noise during sync operations

## Appendix: Log Format Conventions

- Use structured logging with placeholders: `log.info("Block {} imported", blockNumber)`
- Include relevant context: peer IDs, block numbers, error details
- Keep messages concise but informative
- Use consistent terminology across the codebase
- Prefix subsystem-specific logs with tags like `[RLPx]`, `[FastSync]`, etc. when helpful

## Appendix: Manual Log Level Configuration

The `logback.xml` file provides comprehensive logger entries for all major components, organized by subsystem. Operators can manually set log levels for troubleshooting by editing the appropriate logger entry.

### Configuration Location Rationale

Fukuii uses a hybrid configuration approach for logging:

| Setting Type | Location | Rationale |
|-------------|----------|-----------|
| **Global log level** | `base.conf` (`logging.logs-level`) | Simple runtime override via `-Dlogging.logs-level=DEBUG` or environment variable |
| **Output format** | `base.conf` (`logging.json-output`) | Deployment-specific setting (JSON for log aggregation) |
| **Log file paths** | `base.conf` (`logging.logs-dir`, `logging.logs-file`) | Environment-specific paths |
| **Per-component log levels** | `logback.xml` | Detailed troubleshooting control (see below) |

**Why per-component log levels belong in `logback.xml`:**

1. **Hierarchical control**: Logback's native logger hierarchy allows setting levels at package and class granularity. HOCON config would require flattening this into string keys.

2. **Well-documented convention**: Logback's XML format is the standard for Java/Scala applications. Operators familiar with SLF4J/Logback will expect logger configuration in `logback.xml`.

3. **IDE and tooling support**: XML schema validation, autocompletion, and logback-specific tooling work natively with `logback.xml`.

4. **Conditional logic**: Logback supports `<if>` conditions for environment-specific behavior (e.g., JSON output toggle), which is already used in the current configuration.

5. **Hot reload capability**: Logback can reload `logback.xml` at runtime with `scan="true"`, enabling log level changes without restart.

The global `logging.logs-level` in `base.conf` is bridged to logback via `ConfigPropertyDefiner`, providing a convenient override for the root logger while preserving fine-grained control in `logback.xml`.

### Configuration Files

- Production: `src/main/resources/logback.xml`
- Unit tests: `src/test/resources/logback-test.xml`
- Integration tests: `src/it/resources/logback-test.xml`
- Global settings: `src/main/resources/conf/base.conf` (logging section)

### Subsystem Categories

The following subsystems have dedicated logger entries in `logback.xml`:

| Subsystem | Package | Description |
|-----------|---------|-------------|
| Scalanet | `com.chipprbots.scalanet` | Low-level networking library |
| Network | `com.chipprbots.ethereum.network` | Peer connections and communication |
| RLPx | `com.chipprbots.ethereum.network.rlpx` | Encrypted transport protocol |
| Sync | `com.chipprbots.ethereum.blockchain.sync` | Block synchronization |
| Fast Sync | `com.chipprbots.ethereum.blockchain.sync.fast` | Fast sync mode |
| Regular Sync | `com.chipprbots.ethereum.blockchain.sync.regular` | Regular sync mode |
| SNAP Sync | `com.chipprbots.ethereum.blockchain.sync.snap` | SNAP protocol sync |
| Ledger | `com.chipprbots.ethereum.ledger` | Block execution and state |
| Database | `com.chipprbots.ethereum.db` | Storage and persistence |
| MPT | `com.chipprbots.ethereum.mpt` | Merkle Patricia Trie |
| VM | `com.chipprbots.ethereum.vm` | Ethereum Virtual Machine |
| Consensus | `com.chipprbots.ethereum.consensus` | Mining and validation |
| Transactions | `com.chipprbots.ethereum.transactions` | Pending transactions |
| JSON-RPC | `com.chipprbots.ethereum.jsonrpc` | API server |
| Faucet | `com.chipprbots.ethereum.faucet` | Test network faucet |
| Metrics | `com.chipprbots.ethereum.metrics` | Performance monitoring |

### Example: Enabling DEBUG for RLPx Troubleshooting

To enable DEBUG logging for RLPx connection issues, modify `logback.xml`:

```xml
<!-- Before: INFO (production default) -->
<logger name="com.chipprbots.ethereum.network.rlpx" level="INFO" />

<!-- After: DEBUG (for troubleshooting) -->
<logger name="com.chipprbots.ethereum.network.rlpx" level="DEBUG" />
```

### Note on Pekko Actor Logging

When setting any level to DEBUG, you may also need to adjust `pekko.loglevel` in `application.conf` for actor-based components to ensure DEBUG messages are not filtered at the actor system level.
