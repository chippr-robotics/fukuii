# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.9.0]

Glamsterdam (Amsterdam) support for the ETH-family chains, released ahead of Sepolia's Amsterdam
activation on 2026-10-06 13:53:36 UTC. This section covers everything merged into `staging` since 0.8.0
(the 0.8.1 – 0.8.13 builds). Release notes: [`docs/releases/0.9.0.md`](docs/releases/0.9.0.md).

### Added
- **Glamsterdam (Amsterdam) execution layer**, active on an ETH-family chain from its
  `amsterdam-timestamp`; no ETC-family chain config declares one (#1409, #1413):
  - EIP-2780, 7708, 7778, 7843 (`SLOTNUM`), 7928 (block-level access lists, built while a block executes,
    checked against the header's `blockAccessListHash` and stored), 7954, 7976, 7981, 8024 (`DUPN`,
    `SWAPN`, `EXCHANGE`), 8037, 8038, 8246 and 8282 (builder execution requests). EIP-7997 needs no
    client code.
  - Engine API: `engine_newPayloadV5`, `engine_forkchoiceUpdatedV4`, `engine_getPayloadV6`, and
    `engine_getPayloadBodiesByHashV2` / `engine_getPayloadBodiesByRangeV2`, which add the stored access
    list (`null` before Amsterdam or once pruned).
  - Amsterdam block building: the 23-field header, the payload attributes' slot number, the block's own
    access list, EIP-8282 builder requests, and EIP-8037's per-dimension transaction packing.
  - eth/71 (EIP-8159) serves stored block access lists within go-ethereum's 2 MiB / 1,024-entry limits.
    It is opt-in, like eth/70, eth/72 and snap/2 (below).
  - The fork id follows the chain head's timestamp in the `Status` handshake, the ENR and DNS discovery.
  - JSON-RPC at Amsterdam: `eth_config` reports the EIP-8282 builder contracts, `eth_simulateV1` builds
    the 23-field header, and trace and debug replays of an Amsterdam block re-execute it as block import
    did.
- Platåberget, the public Glamsterdam testnet (chain id 7091047534), as a launcher network:
  `fukuii plataberget`. Ships the devnet-8 genesis (hash `0xee33ef92…2b31`) and EL bootnodes.
  Its Amsterdam fork activated 2026-08-20, so following the chain head needs the Amsterdam rule
  set tracked in #1409
- Sepolia Amsterdam (Glamsterdam) activation at timestamp 1791294816 (2026-10-06 13:53:36 UTC).
  The Sepolia fork id now announces it as the next fork (`0x268956b6`, next `1791294816`), as
  go-ethereum does
- `docs/specifications/GLAMSTERDAM.md`: Glamsterdam EIP set, activation schedule, fukuii
  implementation status and a Platåberget test guide
- Configurable external IP detection strategy via `network.server-address.external-ip-detection`
  (`none` | `upnp` | `full`, default `upnp`); every detected candidate is now validated as a public
  IPv4 address before being advertised to peers
- Conformance runner for the execution-specs fixtures: `EestFixtureCorpusSpec` replays every
  `blockchain_test` through block import, and `EngineApiAmsterdamBuilderEestSpec` rebuilds every block
  those tests accept through the payload builder, which must reproduce the block hash. Against
  `tests@v21.0.0` Amsterdam (26,503 tests, 27,352 blocks) both pass in full. The CI job `EEST Amsterdam`
  runs both, at the informational tier (#1419, #1427).
- eth/70 (EIP-7975), eth/71 (EIP-8159), eth/72 (EIP-8070) and snap/2 (EIP-8189) capabilities, each opt-in
  under `network.protocols` and off by default (#1408).
- CI gate matrix: `.github/gates.yml` declares every gate and its tier, Gate Integrity fails a public
  claim that no check backs, and `docs/STATUS.md` is generated from it (#1406).

### Changed
- **Engine API payloads move toward the node's gas ceiling on every fork**, as go-ethereum's builder
  does: toward an Amsterdam payload's `targetGasLimit` when the attributes carry one, and otherwise toward
  `mining.gas-limit-target` (default 60,000,000, go-ethereum's `--miner.gaslimit` default), at
  `CalcGasLimit`'s rate. A payload used to keep its parent's gas limit, so a validator proposing through
  fukuii now votes the limit toward 60M unless `mining.gas-limit-target` says otherwise (#1427).
- The txpool's pre-filter for transactions from peers and re-orgs applies the rules of the fork active at
  the chain head, as go-ethereum's pool does. It used to take the latest configured fork up to Osaka, so
  on Sepolia before Amsterdam a peer's transaction below EIP-7623's floor is now refused, as it already
  was on mainnet (#1430).
- `eth_estimateGas` starts its search at `max(intrinsic gas, calldata floor)`, the least gas limit a node
  accepts (EIP-7623 from Prague, EIP-7976 / EIP-7981 at Amsterdam). It could answer the intrinsic cost,
  below the floor (#1430).
- **Olympia is deferred on every chain** until a confirmed test block number exists. Gorgoroth
  (`gorgoroth-chain.conf`, was 15,800,850) and the private-network template
  (`enterprise-template.conf`, was 0) now set `olympia-block-number` to the unscheduled sentinel
  `1000000000000000000`, as ETC mainnet and Mordor already do. Everything keyed to that block stays
  off with it, including the EIP-1559 base fee and ECIP-1111 treasury credit, the Olympia opcodes and
  precompiles, EIP-7702, the Olympia gas-limit rules and the MESS reactivation window. ETC mainnet,
  Mordor and the ETH-family chains are unchanged (on ETH-family configs `olympia-block-number` is the
  London block).
  - **Gorgoroth operators:** move every fukuii node to this build at the same time. A node left on
    an older build activates Olympia at 15,800,850 and splits from the rest there. If your chain
    already ran past 15,800,850 on an Olympia-active build, reset the datadir: this build rejects
    those blocks, because their headers carry a base fee. The Gorgoroth fork ID no longer announces
    15,800,850 as the next fork.
  - **Private networks from the template:** new copies leave Olympia unscheduled, so genesis has no
    base fee field. A network already running from an earlier copy keeps what that copy sets;
    changing `olympia-block-number` on a live chain is a hard fork.
- Olympia-era gas now follows EIP-2537 (G1/G2 MSM discount tables) and EIP-7702 (authorization
  refunds, authority warming, delegation access cost). No ETC-family chain activates Olympia (see the
  entry above), so ETC mainnet, Mordor and Gorgoroth are unaffected.
- EVM interpreter: about 5x throughput on arithmetic-heavy code, with no differences across a
  27,039-case ethereum/tests differential (#1408).
- Engine API behaviour from hive's engine suite: payload building and transaction selection follow
  go-ethereum; a sidechain block never overwrites a canonical one; `engine_forkchoiceUpdated*` keeps the
  canonical index to exactly the head's ancestry and answers a VALID ancestor without rewinding;
  `engine_getPayload*` is fork-windowed and includes transactions that arrived after the forkchoice
  update (#1408).
- P2P: eth/69 and later receipts use EIP-7642's encoding, and eth/68 typed receipts are one RLP byte
  string (core-geth interop); the ENR `eth` entry is fixed, so go-ethereum dials fukuii; bodies and
  receipts are served as prefixes under a 2 MiB soft limit, and backfilled ones are checked against
  their header roots (#1408).

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
- **Sepolia: blocks carrying EIP-6110 deposits were rejected since Prague.** Execution read deposits
  only from the mainnet deposit contract, so a Sepolia block with a deposit computed the wrong
  `requestsHash` and was refused as `INVALID_REQUESTS`. Each chain's own deposit contract is now read
  (#1416).
- Consensus rules on live ETH networks, found by the Amsterdam corpus (#1413):
  - from Prague, a transaction whose gas is below its EIP-7623 calldata floor is invalid; it was executed
    (#1438);
  - from Cancun, a successful precompile CALL keeps the caller's transient storage; it was wiped (#1439);
  - from Osaka, P256VERIFY returns empty output when verification fails (#1439);
  - from Osaka, the Engine API path enforces EIP-7934's block size limit (#1439).
  - The same three rules apply from Olympia, which no chain schedules (see Changed).
- MODEXP (EIP-198) reads a length word cut short by the end of its input as right-padded with zeros, on
  every network that has the precompile (#1441, first in 0.8.6).
- RLP decoding caps list nesting depth at 64, so a deeply nested message is refused instead of crashing
  the node (#1442, first in 0.8.7).
- `eth_simulateV1`'s per-call overrides (precompile moves, `traceTransfers`, the blob base fee) no longer
  reach block import or other RPC calls running at the same time (#1446).
- A handshaked peer no longer reconnects itself when its connection drops (#1447).
- SNAP sync finishes after state healing re-pegs its pivot (#1448).
- Post-merge regular sync imports the head's extension after a rewind instead of stalling, and fork
  recovery no longer swallows an import's completion and wedges regular sync, on every chain (#1432).

## Before 0.9.0

The entries below were written under `[Unreleased]` between 0.1.0 and 0.8.0, before this file cut
versioned sections; 0.9.0 is the first. They are kept as written. Release notes for some of those versions
are in [`docs/releases/`](docs/releases/), and each GitHub release links its full commit range.

### Added
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
- Renamed GHCR image path from `chordodes_fukuii` to `fukuii` across all CI/CD, docs, and scripts
- Modernized CI apt-key pattern to use `signed-by` keyring (replaces deprecated `apt-key add`)
- Renamed `logback-node2-sync-trace.xml` → `logback-sync-trace.xml` (not node-specific)
- Deduplicated `CONTRIBUTING.md` — root and `docs/` copies now redirect to canonical `docs/development/contributing.md`
- Fixed confused rebrand text in contributing docs (was "Fukuii to Fukuii", now "Mantis to Fukuii")
- Enhanced release workflow to include all artifacts
- Updated documentation for release process
- Modified `ProgramState` initialization to conditionally include COINBASE in warm addresses set

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

**Note:** This changelog is written by hand; releases do not generate it. Each release on the
[Releases page](https://github.com/chippr-robotics/fukuii/releases) links its full commit range.
