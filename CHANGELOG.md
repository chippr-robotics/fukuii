# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- **JDK 25 is the baseline, and the JVM must run with the C2 `ReduceAllocationMerges` workaround (#1642, #1643).
  Breaking for hand-written launchers.** HotSpot C2 on JDK 23 and later miscompiles `ReduceAllocationMerges`
  (JDK-8385993, fixed in JDK 28; JDK-8393049 reports silently wrong field values), a consensus-divergence risk for a
  node. Every JVM fukuii runs in now carries `-XX:+UnlockDiagnosticVMOptions -XX:-ReduceAllocationMerges`: sbt
  (`.jvmopts`), forked test JVMs, `bin/fukuii` (`application.ini`), and every Docker image (`JDK_JAVA_OPTIONS`). The
  node, the multi-instance runtime and the checkpoint exporter **refuse to start (exit status 78)** on JDK 23+
  without the flag, unless you set `-Dfukuii.allow-c2-reduce-allocation-merges=true` or
  `FUKUII_ALLOW_C2_REDUCE_ALLOCATION_MERGES=true` (the node then starts and logs the same message as an ERROR). A
  hand-written `java -jar` line or systemd unit must add the two flags. Existing containers are protected only after
  you pull the rebuilt images. The Debian package now depends on JDK 25. See docs/runbooks/known-issues.md Issue 16
  and ADR INF-007 (supersedes INF-001's JDK 21 minimum).
- **Blob parameters are per chain (EIP-7840 `blobSchedule`, EIP-7892 BPO forks, #1637).** The blob target, max and
  base-fee update fraction of each fork now come from the chain config's `blob-schedule` instead of constants fixed to
  the mainnet/Sepolia schedule. Every consumer reads it: excess blob gas and blob base fee, the per-block blob limit in
  header validation and block building, `BLOBBASEFEE`, `eth_blobBaseFee`, `eth_feeHistory`'s blob fields,
  `eth_config.blobSchedule`, receipts' `blobGasPrice`, GraphQL, and the `maxFeePerBlobGas` the node fills in for a transaction it signs. mainnet, Sepolia, Platåberget and hive keep exactly today's values
  (go-ethereum's default Cancun 3/6/3338477, Prague 6/9/5007716, BPO1 10/15/8346193, BPO2 14/21/11684671); ETC chains
  have no blob forks and are unchanged. Ephemery now follows its own schedule (BPO1 8/12/6676955, BPO2 10/15/8346193):
  before, its blocks at or above the blob target failed import with `INCORRECT_EXCESS_BLOB_GAS` and blocks with more
  blobs than it allows were accepted. `scripts/ephemery/fetch-genesis.sh` maps genesis.json `config.blobSchedule`
  instead of warning, and hive's adapter maps `HIVE_{CANCUN,PRAGUE,BPO1,BPO2}_BLOB_*`.

  **Breaking for custom ETH-family chain configs:** a chain config that declares `cancun-`, `prague-`, `bpo1-` or
  `bpo2-timestamp` must declare a `blob-schedule` entry for each, or the node refuses to load it (as it does a schedule
  with max < target, a zero target or update fraction, out-of-order blob-fork timestamps, `bpo3-timestamp` or later,
  or an `osaka`/`amsterdam` entry that differs from the one it inherits). An Ephemery chain config generated before
  this change has no `blob-schedule`: re-run `fetch-genesis.sh`. See docs/runbooks/custom-networks.md.
- **Debian package: the launcher now reads `/etc/fukuii/application.ini`.** Deb installs never applied it before, so
  the JVM ran with its own defaults (a heap of about a quarter of RAM, no OOM exit). After upgrading, a deb install
  runs with the shipped defaults: `-Xmx3g -Xms1g`, `MaxDirectMemorySize=512M`, G1, exit and heap dump
  (`/var/lib/fukuii/heapdump.hprof`) on OutOfMemoryError. **Large mainnet nodes should raise `-J-Xmx` in
  `/etc/fukuii/application.ini`** (a conffile; edits survive upgrades), and make `/var/lib/fukuii` writable by the user
  running the node.
- **Transaction submission over RPC follows go-ethereum's rules (#1629 S0b).** `eth_sendRawTransaction`,
  `eth_sendTransaction`, `personal_sendTransaction` and GraphQL `sendRawTransaction`:
  - refuse legacy transactions that are not replay-protected (no EIP-155 chain id) with "only replay-protected
    (EIP-155) transactions allowed over RPC", on every chain, ETC included (core-geth does the same). Opt back in with
    `fukuii.network.rpc.allow-unprotected-txs = true` (go-ethereum `--rpc.allow-unprotected-txs`);
  - refuse a transaction whose fee (gas * fee cap) exceeds `fukuii.network.rpc.tx-fee-cap` (default 1 ether, 0 = no
    cap; go-ethereum `--rpc.txfeecap`) with "tx fee (X ether) exceeds the configured cap (Y ether)".
    `eth_signTransaction` and `personal_signTransaction` check the cap too; `eth_fillTransaction` does not;
  - on ETH-family chains, apply the pool's rules against what it holds: "already known" for a transaction already
    pooled, "replacement transaction underpriced" unless a replacement raises both fee caps by `txPool.price-bump`
    (default 10%; `txPool.blob-price-bump`, default 100%, for blob transactions, as go-ethereum's blobpool), "insufficient funds" when the sender's pending transactions together overdraw its balance, and
    EIP-7702's one-in-flight-transaction limit for delegated senders. Peers' transactions meet the overdraft and
    delegation rules too. ETC's pool is unchanged;
  - on ETH-family chains, keep a sender's transactions to one of go-ethereum's subpools, blob or every other type:
    a blob transaction from a sender with a pooled non-blob transaction, or a non-blob one from a sender with a pooled
    blob transaction, is refused with "address already reserved" (-32000; go-ethereum `txpool.ErrAlreadyReserved`),
    a same-nonce one included, until the sender's transactions of the other kind leave the pool. Peers' transactions
    meet the rule too. Replacement within the same kind still follows the price-bump rules. ETC is unchanged;
  - check blob sidecars as go-ethereum's pool does: from Osaka a version-1 (cell proof) sidecar is required (a
    version-0 one sent to `eth_sendRawTransaction` is converted, as go-ethereum does), before Osaka a version-0 one; versioned hashes must match the commitments, at most 6 blobs, and the KZG proofs must
    verify.
- **`eth_getTransactionCount(addr, "pending")` answers the pool's next nonce, as go-ethereum and core-geth do**
  (`GetPoolNonce`): the account's nonce at the head advanced over its pooled transactions with consecutive nonces; a
  nonce-gapped (queued) transaction does not count. Before, it did not read the pool.
  GraphQL `pending { account { transactionCount } }` answers the same, and `eth_sendTransaction` /
  `personal_sendTransaction` fill the same nonce (they took one past the sender's highest pooled nonce, a gapped one
  included). All chains, ETC included.
- **Announced transactions are fetched from one peer at a time.** The pool requested an announced transaction from
  every peer that announced it, and when the peer it got it from was dropped for serving a bad copy, it asked nobody
  else. As in go-ethereum's tx fetcher, a hash is now requested from one announcer; the others are kept as
  alternates and asked in turn when the request goes unanswered for `txPool.announcement-fetch-timeout` (default
  5 s, go-ethereum's `txFetchTimeout`), when the requesting peer disconnects, or at once when it is dropped for a
  delivery that contradicts its announcement. Each delivery is checked against its sender's own announcement. Fixes
  hive devp2p `TestBlobTxWithoutSidecar` / `TestBlobTxWithMismatchedSidecar`, which go-ethereum #35869 rewrote to
  have three peers announce the same blob transaction.
- **Tx pool memory leak (OOM).** A node that was not synced kept taking transactions from peers. During SNAP sync
  the pool rejected every one (no state to check them against), but the EIP-4844 blob sidecars that came with them
  (130-830 KB each) were stored before admission and never removed. A Sepolia node held 2.6 GB of them and was
  OOM-killed every ~10 hours. Two changes. (1) As in go-ethereum (`AcceptTxs`), a node ignores
  `NewPooledTransactionHashes`, `Transactions` and `PooledTransactions` from peers until it is synced: an Engine API
  forkchoiceUpdated has set the head, regular sync has reached the best block peers announced, or block production
  is enabled. It also ignores them while SNAP sync or state recovery runs. Transactions submitted over JSON-RPC are
  not affected. This applies to ETC too: an ETC node stops taking peer transactions while it catches up. (2) The pool
  keeps a blob sidecar only while its transaction is pooled, drops it on every removal path, and caps the total at
  `txPool.blob-sidecar-budget` (default 256 MiB); past the cap the oldest blob transactions are evicted.
- **TUI (`--tui`).** The terminal UI froze after its first frame: key input used JLine's `peek(0)`, which waits
  forever, so the update loop blocked until a key was pressed. It also never showed real data, because the status
  queries were never wired in, so peers, blocks and sync status sat at their defaults. It now polls the peer manager,
  the sync controller (the same queries `net_peerCount` and `eth_syncing` answer), the best block and SNAP progress.
  Further fixes: `D` no longer shuts down the node; `Q` exits through the normal shutdown hook instead of
  interrupting its own shutdown; frames fit the terminal exactly (no scrolling or wrapping, footer pinned);
  non-TTY/`dumb` terminals fall back to plain logging; the terminal mode and screen are restored on exit;
  configuration errors are printed before the TUI suppresses console logging; sync speed/ETA is measured from
  the first block observed rather than from genesis.

### Added
- **Typed transactions on every RPC interface (#1629, slices S0-S5).** Legacy, EIP-2930, EIP-1559, EIP-4844 and
  EIP-7702 transactions are handled with geth's rules, error strings and defaults on JSON-RPC and GraphQL:
  - S1 (#1633): one shared `TxArgs` model with strict, geth-style JSON decoding (`-32602` with geth's hexutil text).
    Type inference follows geth's `ToTransaction`; one deliberate deviation: `gasPrice` together with an access list
    or blob fields and no explicit `type` is rejected instead of silently building a legacy transaction.
  - S0 (#1634): `eth_sendRawTransaction` and GraphQL `sendRawTransaction` pass one admission gate in geth's order
    before the pool. Pending typed transactions report the fee cap as `gasPrice`. On ETC and Mordor, peer
    transactions of a type not active at the head (0x2/0x4 before Olympia) are no longer pooled or gossiped.
  - S2 (#1635): `eth_sendTransaction` and `personal_sendTransaction` build all five types; new
    `eth_signTransaction`, `personal_signTransaction` (`{raw, tx}`) and `eth_fillTransaction`. Defaults follow geth's
    `setDefaults`; gas is estimated rather than fixed at 90000; blob sidecars are built and checked with KZG.
  - S3 (#1636): `eth_call`, `eth_estimateGas`, `eth_createAccessList`, `debug_traceCall(Many)`,
    `trace_call(Many)` and GraphQL call/estimateGas share one message builder and accept state and block overrides.
    `eth_createAccessList` now iterates the access-list tracer to a fixed point. RPC gas cap
    `fukuii.network.rpc.gas-cap` (default 50M, as geth).
  - S4 (#1638): `eth_simulateV1` calls use the strict decoder and geth's type selection and validation error codes.
  - S5 (#1639): the GraphQL `Transaction` matches geth's schema (`yParity`, effective-price fields, blob fields);
    `eth_pendingTransactions` returns executable pool transactions from keystore accounts, as geth does.
- **Gas price oracle (#1640).** `eth_gasPrice`, `eth_maxPriorityFeePerGas`, GraphQL and the tip default of
  send/sign/fill use a port of geth's `eth/gasprice` oracle, replacing the fixed `minTip` (1 wei on ETH, so locally
  sent mainnet transactions could sit unincluded). Config under `fukuii.network.rpc.gpo` with geth's defaults
  (`blocks` 20, `percentile` 60, `max-price` 500 gwei, `ignore-price` 2 wei, `max-header-history` and
  `max-block-history` 1024, the last two now read by `eth_feeHistory`). Start price: 1 mwei on ETH-family chains,
  1 gwei on ETC, Mordor and Gorgoroth (core-geth); override with `gpo-default-price` in the chain config or
  `fukuii.network.rpc.gpo.default-price`. A fresh ETC node now answers 1 gwei instead of 1 wei.
- **Ephemery testnet support (#1628).** `fukuii ephemery` is a known network.
  `scripts/ephemery/fetch-genesis.sh` downloads the current `ephemery-N` genesis and generates the chain config,
  genesis (timestamp re-encoded to hex, `mixhash` renamed `mixHash`), a Lighthouse `testnet/` directory and a
  `current` symlink; it fails on genesis features fukuii cannot represent. See docs/runbooks/ephemery.md.
- **Ephemery load generator (#1649, tooling).** `scripts/ephemery/load-gen.py` sends typed (0x0-0x4, including
  blob and EIP-7702) transactions through fukuii's own RPC plus `eth_fillTransaction`, `eth_estimateGas`,
  `eth_createAccessList` and `eth_call` probes, for import differentials. Nothing in the build depends on it.

### Fixed
- **discv4 saturated the CPU and starved block import and the Engine API (#1648).** On an Ephemery node (4 cores)
  fukuii sat at about 286% CPU with 3 peers, Lighthouse's engine upcheck timed out and Engine requests queued for
  over a minute (`JWT expired`); thread dumps showed discv4 ECDSA signing and recovery. Causes: an unbounded full
  Kademlia lookup per peer request, 2-3 signature recoveries per datagram, and discovery sharing
  `IORuntime.global` with block import. Now: discovery runs on its own pool (`network.discovery.compute-threads`,
  default 1, threads `discovery-compute-N`); one lookup runs at a time, feeding a bounded queue, with the pause
  doubling from 2 s to 64 s while lookups find nothing new; each packet is type-checked before any crypto and
  recovered keys are cached (4096 entries); inbound requests are limited to 10/s per address (burst 30) and 100/s
  overall (burst 200). Measured on Ephemery: about 286% to about 15% CPU.
- **`eth_getHeaderByNumber` / `eth_getHeaderByHash` added; `eth_simulateV1` override keys are strict (#1616).** An
  unknown key in `blockOverrides` or a `stateOverrides` entry returns `-32602`; the `difficulty` block override works
  pre-merge and returns `-32602` after.
- **`eth_feeHistory` returned 0 for every reward percentile (#1605).** It now computes them as geth does: transactions
  sorted by effective tip, first whose cumulative gas reaches `gasUsed * p / 100`.
- **Amsterdam rpc-compat vectors (#1606).** `eth_getBlockByNumber` emits `blockAccessListHash` and `slotNumber` for
  Amsterdam headers; `testing_buildBlockV1` / `testing_commitBlockV1` handle PayloadAttributesV4 and the block access
  list; `eth_simulateV1` builds Amsterdam block identity (slot number, EIP-7928 access list, header `gasUsed` =
  max(execution, state)). Engine API block building and block import are unchanged.
- **PoW rewind abandoned the canonical prefix (#1592, #1594). ETC/Mordor.** A branch-resolution rewind re-presented
  the node's own canonical blocks, which took the reorg path and had their state abandoned; under basic pruning
  live nodes were deleted 64 blocks later and a later import failed with `MissingAccountNodeException`. The importer
  now drops the already-canonical prefix (as core-geth does) and `settleHead` never abandons a block it just
  re-executed with the same hash.
- **SNAP resume checkpoint lost completed ranges (#1597, #1599).** Ranges finished before a restart dropped out of
  later checkpoints (16 cursors became 15), so the resume was rejected and the record and its task files destroyed.
  Completed ranges are now kept, a recoverable keyset is filled (WARN lists the filled ranges), and a layout mismatch
  holds the record instead of deleting it.
- **Accounts-complete recovery paths were not persisted (#1603, #1604).** The controller asked the coordinator for
  the storage and codeHashes task-file paths and handled the replies off the actor thread (Pekko Typed threw
  `UnsupportedOperationException`); the codeHashes path was never saved, so a restart during the storage phase with
  bytecode incomplete restarted SNAP from the accounts phase. The coordinator now tells the controller both paths
  on completion.

### Internal
- SNAP controller split into modules (spec 016, #1401; R1 #1622 and the C-S/C-H/C-X series, #1608-#1620), with
  golden and `snap-split-verify` guards. No behaviour change intended.
- sbt 1.13.0 and sbt-1 plugin bumps (spec 020 PR 1, #1631).
- Scala 3 import syntax enforced by a scalafix rule and a CI grep (spec 020 PR 2, #1632).

## [0.9.0] - 2026-10-05

The Glamsterdam release. Sepolia activates Amsterdam at timestamp 1791294816 (2026-10-06 13:53:36 UTC);
every Sepolia node must run 0.9.0 before then. Tracking issue: #1415 (spec: `specs/009-amsterdam-fork-support`,
#1409). ETC, Mordor and Gorgoroth do not activate any of it. See
[docs/releases/0.9.0.md](docs/releases/0.9.0.md) and
[docs/specifications/GLAMSTERDAM.md](docs/specifications/GLAMSTERDAM.md).

### Added
- **Networks.** Platåberget, the public Glamsterdam testnet (`glamsterdam-devnet-8`, chain id 7091047534), as
  a launcher network: `fukuii plataberget` (#1417). It ships the devnet-8 genesis (hash `0xee33ef92…2b31`),
  the 20 devnet-8 EL bootnodes and every fork through Osaka, BPO1 and BPO2 at genesis, with
  `amsterdam-timestamp = 1787212224` (2026-08-20). Sepolia gets `amsterdam-timestamp = 1791294816`; its fork
  id announces it as the next fork (`0x268956b6`, next `1791294816`), as go-ethereum does.
- **Amsterdam (Glamsterdam) execution rules**, all gated on the Amsterdam timestamp and unreachable on any ETC
  chain. EIP-2780, 8037, 8038, 7778, 7954 and 7708 first shipped in 0.8.5 (#1408); 0.9.0 completes them against
  the execution-specs corpus and adds the rest:
  - EIP-2780 resource-based intrinsic gas, with authorization processing and dispatch as execution-specs
    `create_evm` (#1423)
  - EIP-7778 block gas without refunds; EIP-7954 contract size limit 64 KiB (initcode 128 KiB); EIP-7708 ETH
    transfer logs
  - EIP-8037 state-creation gas: per-dimension block capacity (#1420); header `gasUsed` is the maximum of the
    execution and state dimensions, receipts sum both; state-gas reservoir kept across precompile calls
    (#1437); CALL's new-account charge before sizing the child's gas, and system calls (EIP-4788, EIP-2935) with
    their own reservoir (#1440)
  - EIP-8038 state-access gas, with the EXTCODESIZE / EXTCODECOPY code-read surcharge (#1440)
  - EIP-7976 calldata floor and EIP-7981 access-list cost, with the floor as a validity rule (#1421)
  - EIP-8246: SELFDESTRUCT no longer burns (#1422)
  - EIP-7843 `SLOTNUM` opcode (`0x4b`) and EIP-8024 `DUPN` / `SWAPN` / `EXCHANGE` (`0xe6`-`0xe8`) (#1424)
  - EIP-7928 block-level access lists: codec and header accessors (#1418); access recording through the VM,
    per-index diff, validation against the header hash on import, and a `blockHash -> BAL RLP` store (#1426)
  - EIP-8282 builder execution requests, with `eth_config` reporting the builder deposit and exit contracts
    (#1430)
  - EIP-7997 needs no client code (networks provide the factory contract); EIP-7688, 7732, 8045 and 8061 are
    consensus-layer changes
- **Engine API** (#1425, #1427, #1428):
  - `engine_newPayloadV5` with `ExecutionPayloadV4` (`blockAccessList`, `slotNumber`); a missing list is
    `-32602`, an undecodable one is `INVALID`
  - `engine_forkchoiceUpdatedV4` with `PayloadAttributesV4` (`slotNumber`, optional `targetGasLimit`) and
    `custodyColumns` (validated, otherwise ignored)
  - `engine_getPayloadV6`
  - `engine_getPayloadBodiesByHashV2` and `engine_getPayloadBodiesByRangeV2`, adding the stored access list
    (`null` before Amsterdam or when pruned)
  - `engine_newPayloadV4`, `engine_getPayloadV5` and `engine_forkchoiceUpdatedV3` refuse Amsterdam timestamps
    with `-38005`; `engine_exchangeCapabilities` advertises the new methods
  - `engine_getBlobsV3` / `V4` are not implemented (#1431)
- **Amsterdam payload builder** (#1427): `forkchoiceUpdatedV4` -> `getPayloadV6` -> `newPayloadV5` produces a block
  an independent node accepts as VALID. It builds the 23-field header, the slot number and the access list,
  and steers the gas limit toward `targetGasLimit`.
- **eth/71** block access list serving (EIP-8159, #1428): `GetBlockAccessLists` is answered from the stored
  lists within a 2 MiB / 1,024-entry limit. Opt-in via `fukuii.network.protocols.eth71` (default `false`).
  fukuii does not fetch lists from peers.
- **Conformance:** the execution-specs `tests@v21.0.0` blockchain-test replay (`EestBlockchainReplay`,
  `scripts/eest/fetch_fixtures.py`) and the CI job `EEST Amsterdam` (#1419): 26,503 / 26,503 Amsterdam tests
  through block import, and the payload builder rebuilds the corpus's 27,352 blocks with identical hashes.
- `docs/specifications/GLAMSTERDAM.md` (EIP set, schedule, implementation status, Platåberget test guide) and
  the 0.9.0 release notes.
- **Block access list prefetch and fetch.** On an Amsterdam block whose access list hashes to the header's
  `blockAccessListHash`, a bounded pool reads the listed accounts, storage slots and contract code from the
  parent state in parallel with execution (`state-read-caches.bal-prefetch-*`, #1467). Regular-sync catch-up
  fetches the lists from an eth/71 peer (`GetBlockAccessLists`); a list that does not match its header hash is
  dropped and the peer blacklisted (#1468). A peer list is only a prefetch hint: block validity is decided by the
  list fukuii computes itself. ETC never supplies a list and does not prefetch.
- **SNAP sync option** `fukuii.sync.snap-sync.defer-chain-backfill-until-state-complete`: body and receipt backfill
  waits until state is finalised while headers keep downloading. `true` on Ethereum, Sepolia and Platåberget,
  `false` elsewhere (#1466).
- **Read-cache controls** (`state-read-caches`): `jumpdest-block-memo-bytes` (#1460, #1463) and strongly held
  explicit cache sizes (#1476, see Changed). `[IMPORT-TIMING]` reports where a block's import time went; it logs
  at DEBUG unless `import-timing-log = true`.
- Diagnostics: a Disconnect received instead of Hello is logged with its reason, as are `TCP_CLOSED` and
  `DISCONNECT_SENT` for handshaked peers (#1479).

### Changed
- The fork id carries the head's timestamp in eth/68, 69 and 70+ `Status`, the ENR and DNS filters (#1429), so
  peers at a timestamp fork agree on it.
- Engine API payloads on every ETH-family fork steer the gas limit toward the node's gas ceiling
  (`mining.gas-limit-target`, default 60,000,000), as go-ethereum does; an Amsterdam payload without
  `targetGasLimit` does the same.
- The txpool pre-filter for peer and re-org transactions admits under the fork active at the chain head: EIP-2780's
  intrinsic cost and the EIP-7976 / EIP-7981 floor at Amsterdam, EIP-7623's floor before. `eth_sendRawTransaction`
  does not use it (#1430).
- `eth_estimateGas` starts its search at the least valid gas limit, `max(intrinsic, calldata floor)` (#1430).
- `eth_simulateV1` builds the 23-field Amsterdam header at Amsterdam timestamps (#1430).
- `debug_*` and `trace_*` replays of an Amsterdam block re-execute it as block import did (#1430).
- The docs link check builds the site at its served `/fukuii/` path.
- **eth/70 and eth/71 are advertised on Ethereum, Sepolia and Platåberget** (`network.protocols { eth70 = true,
  eth71 = true }` in their configs, #1475). Peers that offer only eth/68-69 negotiate down. ETC, Mordor and
  Gorgoroth are unchanged; eth/72 and snap/2 stay off.
- **`state-read-caches` explicit sizes (#1476).** A byte-size key that is set is used as given and held with strong
  references, so the collector cannot empty it. Each explicit cache is clamped to 50% of the max heap; explicit
  caches totalling more than 60% are scaled down proportionally with a WARN; the recommended total is 35% or less.
  A key left unset keeps its default, capped at a fraction of the heap and held softly, as before. The shipped
  `state-read-caches.conf` leaves the byte-size keys unset. Each cache's effective size and reference type is
  logged at INFO at startup.
- Block execution analyses each contract's JUMPDESTs once per block and caches decoded trie nodes and contract
  code by hash (#1460, #1463); code reads no longer fill RocksDB's block cache (#1467); JUMPDEST sizing is
  O(words) and `Address.hashCode` is computed once (#1477). State roots, gas and iteration order are unchanged.
- Contract code that is missing is no longer executed as empty: the read throws, the node fetches the code over
  SNAP `GetByteCodes`, and a node that was SNAP-synced recovers all missing code in one bulk scan (#1456, #1459).
- SNAP sync fetches bytecode of healed accounts before it finalises (#1456). State-node fetches rotate across
  snap peers at once instead of waiting 5 s per bad reply; an empty reply no longer blacklists the peer (#1470).
  Snap-serving peers are kept through a local stall (#1479).

### Fixed
- **Engine API blobs bundle dropped EIP-7594 sidecars.** The payload builder read only the version-0 blob sidecar
  wrapper; a pooled blob transaction carrying a version-1 (cell proof) wrapper, from a peer or from
  `eth_sendRawTransaction`, went into the payload without its blobs in `blobsBundle`, so the bundle no longer matched
  the payload's versioned hashes and a consensus client would reject it. Both wrappers are now read.
- Sepolia's EIP-6110 deposits are read from the chain's own deposit contract; it was wrong for Sepolia since
  Prague (#1416).
- A genesis that activates Amsterdam builds the 23-field header, and hive's genesis `slotNumber` is passed
  through.
- A successful precompile CALL keeps the caller's transient storage (Cancun and later); P256VERIFY returns empty
  output on failure and takes exactly 160 bytes (Osaka); EIP-7934's block size cap is checked on
  `engine_newPayload` from Osaka (#1439).
- A transaction whose gas is below its EIP-7623 floor is invalid from Prague (#1438).
- Post-merge regular sync imports the head's extension after a rewind, and fork recovery no longer swallows the
  import's `ImportDone` and wedges regular sync (every chain, ETC included); deferred batches are dropped when
  the failed import already rewound the fetcher (#1432).
- The Platåberget configs no longer set the removed `do-fast-sync` key.
- **A failed CREATE / CREATE2 keeps its memory expansion** (devnet-8 block 319453 state root, #1461).
- **Import atomicity and reference counts (basic pruning, the default; every chain).** Each block's state, block,
  receipts and best-block pointer are committed in one batch, a failed block leaves nothing behind, and the
  validated prefix of a failed batch is adopted rather than re-applied (#1465, which supersedes the closed #1464).
  Block execution and commit are serialised per block hash, so Engine API `newPayload` and the
  regular-sync importer cannot commit one block twice, and the commit is idempotent (#1481). Applying a block
  twice skewed reference counts and could prune a live node 64 blocks later ("Missing account trie node").
- **Reorg reference counts.** Each block records its own reference-count delta, which is undone when the block
  stops being canonical: on a reorg (#1471), on an Engine API forkchoice move, side payload or rejected payload
  (#1473), and on a SYNC-FORK rewind (#1474). Blocks committed before 0.9.0 have no record; undoing them is a
  no-op. The additions to the database are new keys only. Basic pruning no longer prunes past the canonical head
  (#1458).
- A decoded-node cache insert that raced a prune's delete-then-evict is dropped (#1469).
- Engine API: an already-executed side payload answers VALID without re-execution (#1478). A consensus client
  that timed out and re-sent a slow payload previously caused repeated executions.
- **SNAP sync:**
  - parallel storage sub-ranges are applied in order and a restarted storage coordinator recovers quickly
    (#1449); healing continues while the consensus client's head is not advancing (#1450); recovery task files
    stay in the datadir and a phase is not completed without them (#1453)
  - Path-scheme: healing presence is checked by path (#1454); the trie is published to hash-keyed storage so block
    import can read it (#1455); every location that shares a node hash is healed (#1480)
  - healing is not declared clean on a dirty or superseded verification pass (#1472)
  - body and receipt backfill is deferred until state is finalised on ETH-family networks (#1466)
  - an unservable pivot is replaced by a newer one (#1478)
  - the best block's header is no longer rewritten at startup after SNAP (#1457)

### Known issues
- On a deliberately underpowered host (4 cores, 16 GB, DRAM-less SATA SSD with dm-crypt), heavy devnet blocks of
  about 190M gas import in about 8-10 s warm, longer than the roughly 6.5 s slot. A consensus client can briefly
  mark the execution client offline.
- Memory: an 8 GB heap with large explicit `state-read-caches` sizes reached 11.5 GB resident on that host. Keep
  explicit cache sizes at 35% of the heap or less and leave room for RocksDB and the page cache.
- SNAP healing of a large or high-churn state can fall back to the lazy state fetch handoff instead of converging.
  Follow-up work is tracked.
- Sparse blobpool, `engine_getBlobsV3` / `V4` and negotiating a snap version are not implemented (#1431); none is
  needed to follow the chain.

## [0.8.12] - 2026-09-28

### Fixed
- SNAP sync finishes after state healing re-pegs its pivot (#1448)

## [0.8.11] - 2026-09-28

### Fixed
- A handshaked peer no longer reconnects itself when its connection drops (#1447)

## [0.8.10] - 2026-09-28

### Fixed
- `eth_simulateV1`'s per-call overrides are isolated from block import and other RPC calls (#1446)

## [0.8.9] - 2026-09-27

### Changed
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

## [0.8.8] - 2026-09-27

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

## [0.8.7] - 2026-09-27

### Fixed
- RLP decoding caps nesting depth (#1442)

## [0.8.6] - 2026-09-27

### Fixed
- MODEXP reads a truncated length word right-padded, as EIP-198 and other clients do (consensus fix, #1441)

## [0.8.5] - 2026-09-26

### Changed
- Olympia-era gas now follows EIP-2537 (G1/G2 MSM discount tables) and EIP-7702 (authorization
  refunds, authority warming, delegation access cost). No ETC-family chain activates Olympia (see 0.8.9), so ETC mainnet, Mordor and Gorgoroth are unaffected.
- Hive compliance work (#1407, #1408): Engine API payload building and error codes, eth/69+ receipts, opt-in eth/70-72 and snap/2, and the first Amsterdam rules (EIP-2780, 8037, 8038, 7778, 7954, 7708).

## [0.8.4] - 2026-09-23

### Added
- Configurable external IP detection strategy via `network.server-address.external-ip-detection`
  (`none` | `upnp` | `full`, default `upnp`); every detected candidate is now validated as a public
  IPv4 address before being advertised to peers

## [0.8.2 and earlier] - not previously versioned

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

**Note:** This CHANGELOG is maintained by hand and cut when a release is prepared. See the
[Releases page](https://github.com/chippr-robotics/fukuii/releases) for published artifacts.
