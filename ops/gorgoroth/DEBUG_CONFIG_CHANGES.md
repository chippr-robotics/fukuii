# Gorgoroth 3-Node Test Network - Debug Configuration Updates

## Changes Made (December 6, 2025)

### 1. Enhanced Base Configuration (`conf/base-gorgoroth.conf`)

**Sync Configuration:**
- ✅ Fast sync: **DISABLED** (`do-fast-sync = false`)
- ✅ SNAP sync: **DISABLED** (`do-snap-sync = false`)
- ✅ Force regular sync mode (`sync-mode = "regular"`)
- Increased timeouts for debugging:
  - `peers-scan-interval = 10 seconds`
  - `print-status-interval = 30 seconds`

**Network/Peer Configuration:**
- Extended handshake timeout: `30 seconds` (was default ~15s)
- Increased retry settings:
  - `connect-retry-delay = 10 seconds`
  - `connect-max-retries = 10`
- Increased peer limits for debugging:
  - `max-outgoing-peers = 25`
  - `max-incoming-peers = 25`
  - `max-pending-peers = 25`

**Logging Configuration:**
- Set log level to `DEBUG` for enhanced debugging
- Output directory: `./logs`
- JSON output: disabled (plain text for easier reading)

### 2. Custom Debug Logging (`conf/logback-debug.xml`)

Created a dedicated logback configuration with DEBUG-level logging for:

**Network & Peer Debugging:**
- `com.chipprbots.ethereum.network.*` - All network components
- `com.chipprbots.ethereum.network.rlpx.*` - RLPx protocol details
- `com.chipprbots.ethereum.network.handshaker.*` - Handshake process
- `com.chipprbots.ethereum.network.PeerManagerActor` - Peer management
- `com.chipprbots.ethereum.network.EtcPeerManagerActor` - ETC peer management
- `com.chipprbots.ethereum.network.PeerActor` - Individual peer connections
- `com.chipprbots.ethereum.network.ServerActor` - Server operations

**Sync Debugging:**
- `com.chipprbots.ethereum.blockchain.sync.*` - All sync components
- `com.chipprbots.ethereum.blockchain.sync.fast.*` - Fast sync (disabled but logged)
- `com.chipprbots.ethereum.blockchain.sync.snap.*` - SNAP sync (disabled but logged)
- `com.chipprbots.ethereum.blockchain.sync.regular.*` - Regular sync (active)

**Additional Debug Areas:**
- `com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist` - Peer blacklisting
- `com.chipprbots.ethereum.forkid.*` - Fork ID validation
- `com.chipprbots.ethereum.network.discovery.*` - Peer discovery

**Reduced Verbosity:**
- Pekko actors: INFO level (WARN for LocalActorRef)
- Netty: INFO level
- UPnP: WARN level

### 3. Docker Compose Updates (`docker-compose-3nodes.yml`)

Added logback configuration mount to all three nodes:
```yaml
- ./conf/logback-debug.xml:/app/conf/logback.xml:ro
```

## Expected Debugging Improvements

With these changes, you should now see:

1. **Detailed peer connection logs:**
   - RLPx handshake details
   - Auth exchange information
   - Status message exchanges
   - Connection state transitions

2. **Blacklisting reasons:**
   - Why peers are being blacklisted
   - Subprotocol incompatibilities
   - Fork ID mismatches
   - Timeout issues

3. **Sync state details:**
   - Regular sync progress
   - Block fetcher status
   - Peer selection for sync
   - Header/body download progress

4. **Actor message flow:**
   - Handshake success/failure messages
   - Peer manager events
   - Sync controller state changes

## Next Steps

1. **Restart the network:**
   ```bash
   ./deploy.sh down 3nodes
   ./deploy.sh up 3nodes
   ```

2. **Monitor logs in real-time:**
   ```bash
   ./deploy.sh logs 3nodes
   ```

3. **Collect debug logs after running for a few minutes:**
   ```bash
   ./collect-logs.sh 3nodes ./debug-logs-enhanced
   ```

4. **Look for specific patterns in the enhanced logs:**
   - Search for "FORKID_VALIDATION" messages
   - Check "RLPx" connection establishment details
   - Review "STATUS_EXCHANGE" messages for incompatibilities
   - Examine "Blacklisting peer" messages for specific reasons

## Known Issues to Investigate

Based on previous logs, focus on:

1. **Subprotocol blacklisting** - The "Some other reason specific to a subprotocol" message should now have more context
2. **Fork ID mismatches** - Debug logs will show exact fork ID validation results
3. **Connection timeouts** - Extended timeouts should help determine if it's a timing issue
4. **Static peer configuration** - Verify enode addresses are correct

## Reverting Changes

To revert to production settings:
1. Remove the logback mount from docker-compose
2. Change `logs-level = "DEBUG"` to `logs-level = "INFO"` in base-gorgoroth.conf
3. Reduce peer limits and timeouts to defaults
