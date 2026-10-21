# Spec 013: State Snapshots for Fast ETH-Family Sync

**Status**: Draft
**Date**: 2026-10-07
**Scope**: ETH-family chains (Sepolia now, Ethereum mainnet next). ETC/Mordor behaviour is unchanged.
**Owners**: `vault` (storage), `beacon` (ETH trust model), `forge` (ETC-untouched sign-off), `herald` (distribution)
**Related**: CON-002, CON-008, `docs/runbooks/checkpoint-service.md`, #1483 (history expiry), #1466, spec 009
**Type**: Docs only. No code in this PR.

## 1. Problem

A new Sepolia or mainnet operator today SNAP-syncs from peers. SNAP is bounded by peer serving rate, healing
(specs 001-006), and the 4-core / 16 GB class of host we target. Sepolia state is projected at ~300 GB; a fresh
node takes days and is fragile (the 2026-10-05 Sepolia sync and the Platåberget soak both showed I/O stalls and
OOM kills on this host class). Other clients let operators download a pre-built state. We want a fukuii-native
equivalent: an operator exports a snapshot at a finalized block, a new operator imports it, verifies it, and
reaches head after a short catch-up.

## 2. What exists today (findings from reading the code)

Read from `origin/staging` at 1075b0cb2.

**A `.checkpoint` archive (`CheckpointArchive.scala`, format v1) is a full state archive, not a header pivot.**
Layout: magic `C4C4C4C4`, version, `chainId`, RLP header, total difficulty, then a stream of tagged entries
`{trie node (keccak hash -> RLP), bytecode (code hash -> code)}`, an end tag, and a trailing CRC32. It carries
every account-trie node, every storage-trie node of every non-empty account, and all referenced bytecode.
`CheckpointExporter` walks the trie from `header.stateRoot` (DFS, a `HashSet` of visited hashes). It requires
the state at that block to be present as hash-keyed nodes. No bodies, receipts, or ancestor headers are included.

**Import** (`CheckpointImporter`): streams entries into `mpt.storeRawNodes` (batches of 10k), commits bytecode,
verifies CRC32, then atomically writes the header, chain weight, best-block pointer and the
`snapSyncDone / bytecodeRecoveryDone / storageRecoveryDone` markers. `SyncController` (~L1418) only fires on a
fresh DB (`bestBlock == 0 && !snapSyncDone`) and falls through to SNAP on failure. `CheckpointDownloader` is a
resumable `Range` GET.

**Why it is effectively ETC-only (and where the docs disagree).** The SKILL and runbook say ETH/Sepolia does not
use it. The `SyncController` gate itself is not chain-gated, so the real blockers are in the data model
(my reading of the code, not a documented decision):

1. Sepolia/eth/plataberget run `storage-scheme = "path"` (`sepolia.conf`, `eth.conf`). Path scheme stores nodes
   by HP-encoded nibble path in `STATE_TRIE_PATH` / `STORAGE_TRIE_PATH` (`PathNodeStorage`). The archive is
   hash-keyed, and `storeRawNodes` is the hash-scheme writer. A v1 archive cannot be loaded into a Path DB
   without re-deriving paths, which a flat node stream does not carry.
2. The importer never populates flat state (`FlatAccountStorage`, `FlatSlotStorage`), which Path-scheme reads and
   SNAP serving rely on.
3. The trust model is "whoever controls the URL controls the state" (CON-008 constraint 5): CRC32 only, header
   hash not checked against any anchor. Tolerable for ETC with a known operator; not for a chain where the CL
   gives a cryptographic finality anchor we can use for free.
4. CON-008 plans `sepolia/` and `eth/` buckets, so the product intent already includes ETH; the code does not.

**Reusable as-is**: `CheckpointDownloader` (Range resume, redirects, 30 min timeout), the atomic marker commit,
the fresh-DB gate and fall-through-to-SNAP behaviour, `CheckpointCli` plumbing, the `ops/checkpoint-server`
publish script, and the CON-008 object layout (immutable versioned objects, `latest` redirect, `manifest.json`).

**SNAP resume**: `AccountResumeCheckpoint` (JSON in AppState, `putSnapAccountResumeCheckpoint`) records pivot,
progress and contract-task files so a restarted SNAP continues. Snapshot import needs the same crash-resume
property and must not collide with it.

## 3. Prior art (public knowledge; verify before relying on specifics)

- **geth**: maintains a flat "snapshot" (account/storage by hash) beside the trie; SNAP sync is built on it.
  geth does not distribute state snapshots officially. It exports pre-merge history as **era1** files
  (`export-history` / `import-history`), motivated by **EIP-4444** history expiry. Path-scheme (PBSS) keeps one
  full state plus ~128 diff layers, so only a recent state is available locally.
- **Erigon**: ships "snapshots" (immutable segment files of headers, bodies, transactions, and recent state
  domains) and fetches them over **BitTorrent** with web seeds; the manifest is a list of torrent hashes pinned
  in the binary release.
- **Reth**: `reth download` pulls community-hosted DB snapshots over HTTPS; the operator trusts the host and is
  expected to verify against a known block hash.
- **Nethermind**: community / vendor-hosted database snapshots are documented as a faster alternative to snap
  sync; again host-trusted.
- **Besu**: checkpoint sync starts from a CL-style trusted block, then snap-syncs state; no state download.
- **ethPandaOps** publishes Sepolia/Holesky-class devnet snapshots over HTTP.

Lessons used here: (a) flat-state-based formats are scheme-independent and ~2x smaller than node dumps;
(b) host-trusted downloads are the norm, and the weakness is always "wrong or malicious state"; we close it by
verifying the root (section 5.4); (c) HTTP with Range is the lowest common denominator, torrent is an optional
accelerator, not a dependency (agrees with CON-008 section 3).

## 4. User stories

- **U1 Exporter.** As an operator of a synced Sepolia node, I run `fukuii sepolia snapshot export` and get a
  directory of chunks plus a manifest for a block that the CL reports as finalized, without stopping the node or
  stalling block import.
- **U2 Importer.** As a new operator on a fresh datadir, I set `sync.snapshot-url` (or `snapshot-file`), start
  fukuii with a connected CL, and the node reaches head after import plus a bounded catch-up, instead of days of
  SNAP.
- **U3 Verifier.** As a cautious operator, I run `snapshot verify` against a manifest and my own CL / trusted
  block hash before trusting a third-party host.
- **U4 Publisher.** As the chipprbots publisher, I cut a weekly snapshot per chain using the existing
  `ops/checkpoint-server` flow and publish it as immutable objects.
- **U5 ETC operator.** My checkpoint workflow and consensus behaviour do not change.

## 5. Design

### 5.1 Contents (format "snapshot v2", new magic, not an extension of v1)

Decision: store **flat state and code, not trie nodes**; the importer rebuilds the trie.

| Section | Content | Why |
|---|---|---|
| `anchor` | RLP header at block N, chain id, fork-config digest, fukuii version | root and anchor for verification |
| `ancestors` | headers N-255..N-1 (RLP) | BLOCKHASH needs 256 ancestors; also parent lookup after import |
| `accounts` | (keccak(addr) -> slim RLP account) in ascending hash order | the same form SNAP serves |
| `storage` | per account hash, (slot hash -> value) ascending | same |
| `code` | (codeHash -> bytecode), deduplicated | needed for execution |
| optional `nodes` | path-keyed account/storage trie nodes (Path profile only) | skips the rebuild; larger |

Rationale: trie rebuild uses the existing `StackTrie`/`SnapHashTrie` machinery that SNAP already trusts, emits
correct nodes for either storage scheme, and gives the root check for free (5.4). Node dumps (v1 style) are ~2x
flat state and bind the file to one scheme. The optional `nodes` profile is a later optimisation (see Q3).

Not included: block bodies, receipts, transaction index. History is a separate concern (5.6).

### 5.2 Size estimates (order of magnitude, MUST be measured in Phase 0)

- **Sepolia**: ~300 GB projected on-disk state (Path scheme incl. trie + flat + index overhead). Flat account +
  slot payload plus code is likely 40-55% of that; with zstd on sorted hash-keyed data (low compressibility for
  hashes, good for values and code) expect roughly 100-140 GB compressed.
- **Mainnet**: published client figures put pruned state in the several-hundred-GB range, with total datadirs
  near or above 1 TB; expect ~300-450 GB compressed flat snapshot. Treat as a planning range only.
- Rebuild writes the trie again: import disk peak = snapshot + datadir (see 5.5 budget).

Phase 0 deliverable: a measured Sepolia export (bytes per section, entries, wall time, peak RSS).

### 5.3 Chunking, compression, integrity

- Chunks are independent files of ~256 MiB uncompressed, each a contiguous key range in a single section
  (accounts by hash range; storage by (account hash, slot range); code by hash range). Each chunk header
  carries `[firstKey, lastKey]` and entry count.
- Compression: zstd (level 3) per chunk. gzip remains for v1 only. Per-chunk compression gives parallel decode
  and resumable download.
- Integrity: SHA-256 per chunk in `manifest.json` (replaces whole-file CRC32); chunk files are also
  self-describing with an internal CRC32C. Per-chunk hashes provide early failure, parallel verification, and
  retry of only the damaged chunk. They are NOT the security boundary (5.4).
- `manifest.json` (extends CON-008 section 4): chain, chainId, `blockNumber`, `blockHash`, `stateRoot`, chunk
  table (name, section, range, bytes, sha256), totals, `formatVersion`, `createdAt`, `fukuiiVersion`, optional
  `baseSnapshot` (5.7). A detached signature `manifest.json.sig` (minisign/ed25519) authenticates the publisher;
  it is advisory, not required for safety (see Q1).

### 5.4 Verification on import (the trust model)

Never trust the file. The security argument is two links:

1. **Anchor**: the manifest's `blockHash` must be bound to a header the node's own authority considers
   final.
   - **ETH/Sepolia**: the CL is the root of trust. Import proceeds only after the Engine API has delivered a
     `forkchoiceUpdated` with a `finalizedBlockHash`, and the snapshot's block N must be that block or an
     ancestor of it, established by hash-linking the shipped `ancestors` and the header chain from peers
     (block N hash equals a header the node fetched by hash from peers whose chain leads to the finalized hash).
     Alternatively the operator supplies `--trusted-block-hash` explicitly (offline / air-gapped). With no
     anchor available, import refuses to commit.
   - **ETC**: out of scope for v2 here; the existing checkpoint trust model (bootstrap checkpoints, quorum
     `CheckpointUpdateService`) is kept as is.
2. **Content**: after rebuilding the account trie and every storage trie from the flat data, the computed account
   root MUST equal `header.stateRoot`, and each rebuilt storage root MUST equal the account's `storageRoot`;
   every code hash MUST equal keccak(code). A snapshot that passes both links is state-correct by
   construction; a malicious host can only waste bandwidth (denial of service), not alter state.

Failure path: verification failure wipes the snapshot-written namespaces, logs the first failing chunk/account,
and falls through to SNAP (same fall-through as the v1 importer). Nothing is marked done until verification
passes; the `snapSyncDone / bytecodeRecoveryDone / storageRecoveryDone` markers and best-block pointer are written
in one atomic batch exactly as `CheckpointImporter` does today.

### 5.5 Export on a running node (IO and CPU budget)

Host class: 4 cores, 16 GB, SATA SSD (often DRAM-less, possibly dm-crypt). Block import has priority.

- Path scheme only retains the head state (plus short diff layers), so an arbitrary finalized block's state is
  not available later. Approach: take a **RocksDB `Checkpoint`** (hard links; near-zero IO) at head H, record H,
  and export from that frozen view while the node moves on. The snapshot is **publishable only after H becomes
  finalized** (~13 min on mainnet/Sepolia) and the exporter confirms H's hash against the CL; a reorged H is
  discarded.
- The export reads through `scanRange` (fillCache=false per `storage-rocksdb.md`), single reader thread, a
  RocksDB `RateLimiter` or token bucket on read bytes (default 30% of measured device throughput, configurable),
  `ionice`-class idle where available, and a small (<= 64 MiB) block cache for the checkpoint instance.
  Hard-linked SSTs delay space reclamation; the export must release the checkpoint on completion or abort.
- Heap budget: streaming chunk writer with one in-flight chunk (<= 256 MiB) and a bounded zstd buffer. No
  visited-set (the flat scan has no DAG, which removes v1's `HashSet[ByteString]` growth).
- Alternative: run the exporter offline against a stopped node or a copy (documented, lower risk, recommended
  for the publisher).

Import budget: sequential chunk apply, `WriteBatch`es in key order, WAL disabled during bulk load only (per
`storage-rocksdb.md`, then flush and re-enable), trie rebuild streams in hash order so StackTrie keeps O(depth)
memory. Peak disk = compressed download + decompressed working set + resulting datadir; chunks are deleted
after apply to bound this (resume is by manifest index, not by keeping files).

### 5.6 History and #1483

A snapshot carries state plus 256 ancestors, so the imported node's earliest locally available block is
N-255. Interaction with #1483: the manifest records `earliestBlock`; the node must advertise it through eth/69
`BlockRangeUpdate`, answer `eth_getBlockByNumber` etc. as "not available" below it, and then backfill history
per the #1483 cutoff policy (expiry or cold store). Era / era1 files (pre-merge history, EIP-4444) are an
optional separate artifact for #1483 to consume; this spec only guarantees the snapshot does not claim history it
lacks. Snapshot import is orthogonal to the backfill defer logic of #1466: backfill starts after import.

### 5.7 Incremental snapshots

Phase 4, not v2.0. A delta from base snapshot B to target T lists changed/added/deleted accounts, slots and new
code. It is computed by walking the base and target tries simultaneously and skipping subtrees whose node hashes
are equal (cost O(changed), not O(state)), which requires the publisher to retain the base checkpoint. Import
applies the delta onto an imported or in-progress base, then runs the same root verification. Value: a node that
holds a week-old snapshot (or a publisher's mirror) pulls tens of GB, not hundreds. Until then, cadence is weekly
full snapshots.

### 5.8 Distribution

HTTPS with `Range` as in CON-008 (object store + CDN, R2 considered for egress cost), reusing
`CheckpointDownloader` extended to a chunk list: parallel chunk fetch (default 4), per-chunk retry and resume,
`manifest.json` first. Torrent / web-seed (Erigon style) is an optional later mirror; the manifest's chunk hashes
make it a pure transport swap. Config: `fukuii.sync.snapshot-url` (manifest URL), `snapshot-file` (local
directory), `snapshot-trusted-block-hash` (optional). Fresh-DB gate and SNAP fall-through are inherited.

### 5.9 Catch-up after import

State is at N (hours to days old). Two strategies, to be chosen by measurement: (a) regular sync executes
N+1..head from peers (~7,200 blocks/day on both chains; a weekly snapshot means ~50k blocks); (b) treat the
imported state as a stale SNAP state and run healing to a fresh pivot (reuses specs 001-006). Default (a) when
the gap is under a configurable threshold, (b) above it.

### 5.10 Crash safety and SNAP resume

Import progress is a small JSON record in AppState (same mechanism as `AccountResumeCheckpoint`): manifest hash,
last fully applied chunk index per section, rebuild frontier. A crash resumes from that point. SNAP and snapshot
import are mutually exclusive on a datadir: the presence of an unfinished snapshot record blocks SNAP from
starting, and `snapSyncDone` blocks snapshot import. The best-block pointer stays 0 until the final atomic
commit.

### 5.11 ETC compatibility

- No change to consensus, validation, or the v1 format; v1 magic/version stay readable.
- `checkpoint export|import` CLI behaviour unchanged. New verbs `snapshot export|import|verify` sit beside it
  in `CheckpointCli`, gated by storage-scheme and chain family.
- The ETC DB layout (hash scheme) is not altered. Whether ETC adopts v2 later is out of scope.
- `forge` signs off that ETC files, config and tests show no diff.

## 6. Non-goals

Trust-minimised snapshot *serving* over devp2p; pruning policy; Hash-scheme ETH import; building history/era
artifacts; changing SNAP itself.

## 7. Acceptance criteria

Measured on the reference 4-core / 16 GB / SATA SSD host with a CL attached.

1. **AC1 Correctness**: an imported Sepolia DB has account root == `header.stateRoot` and `eth_getProof`/
   `eth_getBalance` on 1,000 random accounts match a SNAP-synced reference at the same block.
2. **AC2 Speed**: fresh datadir to head <= 25% of the median SNAP-from-scratch wall time on the same host for
   the same chain (target: Sepolia within 12 h including catch-up of a <= 7-day-old snapshot; mainnet target set
   after Phase 0).
3. **AC3 Tamper**: flipping one byte in any chunk, swapping a chunk, or editing the manifest `stateRoot` causes
   import to abort with a named error, leaves `bestBlock == 0`, and SNAP then proceeds. 100% of 50 fuzzed
   mutations are detected.
4. **AC4 Anchor**: with no finalized hash from the CL and no `--trusted-block-hash`, import does not commit.
5. **AC5 Export impact**: while exporting on a live synced node, block import throughput stays >= 90% of
   baseline, p99 block import time rises <= 25%, no peer disconnects attributable to stalls, and peak RSS
   increases <= 512 MiB.
6. **AC6 Resume**: kill -9 during import at 3 random points; restart resumes without re-downloading verified
   chunks and finishes with an identical state root. Kill -9 during export leaves no checkpoint hard links
   (verified by SST count after restart).
7. **AC7 ETC**: ETC/Mordor test tiers and the rpc-compat baseline show no change; v1 `.checkpoint` round-trips
   byte-identically.
8. **AC8 Size**: published snapshot is <= 55% of the Sepolia datadir size; Phase 0 report records measured
   bytes per section.
9. **AC9 History**: after import, `BlockRangeUpdate` advertises `earliestBlock == N-255`, and queries below it
   return the #1483-defined response.

## 8. Phasing

0. Measure: Sepolia flat-state size, scan throughput, rebuild throughput (read-only, offline copy).
1. Format + exporter + verifier (no node wiring); AC3, AC5, AC8.
2. Importer + CL anchor + resume; AC1, AC2, AC4, AC6.
3. Publisher flow + CDN + docs/runbook/SKILL update (fix the "ETH does not use checkpoints" text).
4. Mainnet profile; incremental snapshots; optional `nodes` profile; torrent mirror.

## 9. Risks

- Trie rebuild CPU on 4 cores may dominate; mitigated by the optional `nodes` profile (Q3).
- Hard-link retention during a long export grows disk use on a nearly full SSD (the Sepolia host has ~257 GB
  free); exporter must check free space first.
- Publisher key / host compromise is bounded to denial of service by 5.4, but a bad snapshot wastes hours.
- Fork-config drift: importing across a fukuii version that changes encoding of accounts; `formatVersion` and
  fork-config digest are checked.

## 10. Open questions

- **[NEEDS CLARIFICATION] Q1 Signing and hosting**: who holds the manifest signing key, and is a signature
  required (refuse unsigned) or advisory? Security does not depend on it, but trust UX and key custody need an
  owner.
- **[NEEDS CLARIFICATION] Q2 Mainnet budget**: is chipprbots willing to host a ~300-450 GB weekly artifact
  (storage plus egress; R2 versus GCS), or is mainnet "export tooling only, community hosts"?
- **[NEEDS CLARIFICATION] Q3 Optional trie-node profile**: ship Path-native node sections in v2.0 to avoid the
  rebuild, accepting a larger file and tighter coupling to the Path layout, or defer until Phase 0 measures the
  rebuild cost?
