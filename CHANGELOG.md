# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Configurable external IP detection strategy via `network.server-address.external-ip-detection`
  (`none` | `upnp` | `full`, default `upnp`); every detected candidate is now validated as a public
  IPv4 address before being advertised to peers
- Production release checklist in ETC-HANDOFF.md
- Shared test helper library for Gorgoroth test scripts (`ops/gorgoroth/test-scripts/lib/test-helpers.sh`)
- Static nodes configuration support via `static-nodes.json` file in datadir
  - Nodes can now load peer configuration from `<datadir>/static-nodes.json`
  - **Public mode**: Static nodes are merged with bootstrap nodes from chain config for better sync experience
  - **Enterprise mode**: Uses ONLY static nodes, ignores bootstrap nodes to prevent unintentional public network connections
  - Enables dynamic peer management for private/test networks without config file changes
  - Fully integrated with existing peer discovery system
  - Controlled via `public` and `enterprise` command-line modifiers
- Release automation with one-click releases
- Automated CHANGELOG generation from commit history
- SBOM (Software Bill of Materials) generation in CycloneDX format
- Assembly JAR attachment to GitHub releases
- Release Drafter for auto-generated release notes
- EIP-3651 implementation: Warm COINBASE address at transaction start (see VM-003)
  - Added `eip3651Enabled` configuration flag to `EvmConfig`
  - Added helper method to check EIP-3651 activation status
  - COINBASE address is now marked as warm when EIP-3651 is enabled, reducing gas costs by 2500 for first access
  - Comprehensive test suite with 11 tests covering gas cost changes and edge cases

### Changed
- Gorgoroth devnet: Olympia-era gas now follows EIP-2537 (G1/G2 MSM discount tables) and EIP-7702
  (authorization refunds, authority warming, delegation access cost). Gorgoroth datadirs that ran
  past the Olympia block (15,800,850) with an older build must be reset. ETC mainnet and Mordor are
  unaffected — Olympia is not active there.
- Renamed GHCR image path from `chordodes_fukuii` to `fukuii` across all CI/CD, docs, and scripts
- Modernized CI apt-key pattern to use `signed-by` keyring (replaces deprecated `apt-key add`)
- Renamed `logback-node2-sync-trace.xml` → `logback-sync-trace.xml` (not node-specific)
- Deduplicated `CONTRIBUTING.md` — root and `docs/` copies now redirect to canonical `docs/development/contributing.md`
- Fixed confused rebrand text in contributing docs (was "Fukuii to Fukuii", now "Mantis to Fukuii")
- Enhanced release workflow to include all artifacts
- Updated documentation for release process
- Modified `ProgramState` initialization to conditionally include COINBASE in warm addresses set

### Removed
- **Fast sync.** It could not finish on current networks (eth/67 and later peers do not serve
  `GetNodeData`), no other client supports it, and SNAP sync replaces it. A node now picks SNAP sync
  when `fukuii.sync.do-snap-sync` is true and regular sync otherwise. When SNAP cannot proceed (no
  snap-capable peers, repeated critical failures, no peers at all), it goes dormant and retries on a
  fresh pivot after a back-off of 3 minutes doubling to 20. Before, it handed the node to fast sync.
  - JSON-RPC: `fukuii_resetFastSync` and `fukuii_restartFastSync` (and `fukuii-cli reset-fast-sync`).
  - Metrics (exported as `app_fastsync_*`): the gauges `fastsync.block.pivotBlock.number.gauge`,
    `fastsync.block.bestFullBlock.number.gauge`, `fastsync.block.bestHeader.number.gauge`,
    `fastsync.state.totalNodes.gauge`, `fastsync.state.downloadedNodes.gauge` and
    `fastsync.totaltime.minutes.gauge`, and the timers `fastsync.block.downloadBlockHeaders.timer`,
    `fastsync.block.downloadBlockBodies.timer`, `fastsync.block.downloadBlockReceipts.timer` and
    `fastsync.state.downloadState.timer`. Dashboard panels built on them go empty.
    `app_network_peers_blacklisted_fastSyncGroup_counter_total` stays: the SNAP chain downloader and
    the branch resolver still count there.
  - Configuration keys under `fukuii.sync`, now ignored with a startup warning (not an error):
    `do-fast-sync`, `fast-sync-restart-cooloff`, `max-snap-fast-cycle-transitions`,
    `start-retry-interval`, `sync-switch-delay`, `critical-blacklist-duration`,
    `persist-state-snapshot-interval`, `max-concurrent-requests`, `nodes-per-request`,
    `min-peers-to-choose-pivot-block`, `peers-to-choose-pivot-block-margin`, `pivot-block-offset`
    (the top-level one; `snap-sync.pivot-block-offset` is unaffected),
    `pivot-block-max-total-selection-attempts`, `pivot-block-reschedule-interval`,
    `max-pivot-block-age`, `max-pivot-block-failures-count`, `max-target-difference`,
    `maximum-target-update-failures`, `fastsync-block-chain-only-peers-pool`, `fastsync-throttle`,
    `fast-sync-block-validation-k`, `fast-sync-block-validation-n`, `fast-sync-block-validation-x`,
    `fast-sync-max-batch-retries`, `state-sync-bloom-filter-size`, `state-sync-persist-batch-size`.
  - The `-Dfukuii.reset-fast-sync-done` system property (now only logs a warning).
  - **Upgrading:** nothing has to be done first.
    - A node stopped part-way through fast sync starts SNAP sync, with the pivot kept above the
      block fast sync had reached (its best block has no state behind it). The floor is stored as
      the `SnapSyncMinPivotBlock` app-state key, so it survives restarts, and is cleared when SNAP
      completes. With `do-snap-sync = false` the node starts regular sync and logs an error:
      regular sync fetches the missing state node by node and, if peers cannot serve it, re-syncs
      with SNAP from a newer pivot, even with `do-snap-sync` off.
    - A node where fast sync finished earlier continues with regular sync, even with
      `do-snap-sync` on, unless SNAP has progress there (its saved pivot is the node's best block,
      or its accounts are complete): then SNAP resumes. Regular sync does not need fast sync's trie
      to be complete: it fetches missing state from peers node by node and, if that fails,
      re-syncs with SNAP from a newer pivot.
    - If your configuration set `do-fast-sync = false` to sync from genesis (an archive node, for
      example), set `do-snap-sync = false`; otherwise the node uses SNAP sync.
    - Fast sync's progress record (column family `f`, key `fast-sync-state`) is deleted at the
      first start, once the node has checked whether fast sync left it stranded. The
      `FastSyncCooldownUntilMillis` and `SnapFastCycleCount` app-state keys stay on disk, unread.
      `FastSyncDone` is still read: with SNAP on, it sends a node where SNAP has no progress to
      regular sync.

### Fixed
- **Critical**: Fixed ETH68 peer connection failures due to incorrect message decoder order
  - Network protocol messages (Hello, Disconnect, Ping, Pong) are now decoded before capability-specific messages
  - Resolves issue where peers would disconnect immediately after handshake with "Cannot decode Disconnect" error
  - Fixes "Unknown eth/68 message type: 1" debug messages
  - Node can now maintain stable peer connections and sync properly with ETH68-capable peers
- **Critical**: Fixed SNAP sync OOM from unbounded in-memory trie and contract account buffers (Bug 18)
  - `DeferredWriteMptStorage` now flushes periodically (~32K accounts per batch) instead of once at finalization
  - `contractAccounts`/`contractStorageAccounts` ArrayBuffers replaced with file-backed storage (~45M entries on ETC)
  - Added disk persistence for account range progress with crash recovery
- **Critical**: Fixed RPC starvation under SNAP sync — all RPCs timeout when sync workers saturate default dispatcher (Bug 6)
- **High**: Fixed SNAP fallback resilience — two separate code paths to fast sync fallback (Bug 2)
  - Consecutive pivot refresh counter no longer resets in `restartSnapSync()`
  - Bootstrap retry now uses exponential backoff (2s → 60s cap) instead of fixed 2s
  - 5-minute timeout triggers fallback to fast sync when no peers are found
  - Fixed stale retry code in `Some(header)` match case that prevented SNAP start from local pivot
- **High**: Fixed block body download stall — peer switching + exponential backoff for body fetches (Bug 8)
- **Medium**: Fixed FastSync best block hash tracking during sync (Bug 3)
- **Medium**: Fixed `net_listPeers` timeout with 30+ peers — added peer status cache (Bug 9)
- **Medium**: Fixed `MissingNodeException` in `eth_call`/`eth_estimateGas`/`eth_getCode` during sync (Bug 7)
- **Medium**: Fixed `personal_sendTransaction` `MissingNodeException` during sync (Bug 10)
- **Low**: Fixed JSON-RPC null id coercion and malformed request error format (Bug 4)
- Fixed actor name collision on sync restart — generation counter for unique names (Bug 5)
- Added `ResilientRollingFileAppender` to recreate log file if deleted while running (Bug 19)
- SNAP capability check — verify snap/1 peers before starting account sync (Bug 11)
- SNAP stagnation watchdog — track `accountsDownloaded` as liveness signal (Bug 12)
- SNAP partial range resume — preserve progress across pivot refreshes (Bug 13)
- SNAP dynamic concurrency — cap workers to snap peer count (Bug 14)
- SNAP in-place pivot refresh — update state root without stop/restart (Bug 15)
- SNAP stale peer accumulation — deduplicate peers by remote address on reconnection (Bug 16)
- SNAP false stateless marking after pivot refresh — added stale-root guard (Bug 17)

## [0.1.0] - Initial Version

### Added
- Initial Fukuii Ethereum Client codebase (forked from Mantis)
- Rebranded from Fukuii to Fukuii throughout codebase
- Updated package names from io.iohk to com.chipprbots
- GitHub Actions CI/CD pipeline
- Docker container support with signed images
- Comprehensive documentation

---

**Note:** This CHANGELOG is automatically generated during releases. For the most up-to-date
information, see the [Releases page](https://github.com/chippr-robotics/fukuii/releases).
