# Sync Lifecycle — From Empty Datadir to Chain Tip

This page narrates the full journey of a fukuii node from first start to following the chain
tip: which phases run, what each looks like in the logs and metrics, what "normal" is at each
step, and roughly how long things take. It reflects the current implementation (SNAP sync
with stack-trie account building, BFS state healing, concurrent header/body/receipt
download, eth/68 + eth/69 wire protocols).

Companion pages: [State Healing — Operator's Guide](state-healing-operations.md),
[Monitoring SNAP Sync](monitoring-snap-sync.md), and the
[Metrics Reference](metrics-reference.md).

## The map

```
boot ─► peer discovery ─► pivot selection ─► SNAP download ─► state healing ─► validation
                                                  │                                │
                                                  ▼                                ▼
                                          accounts ► bytecode+storage      regular sync (tip)
```

The SNAP phase gauge (`app_snapsync_phase_current_gauge`) tracks where you are:

| Value | Phase | Long pole on |
| --- | --- | --- |
| 0 | Idle / not started | — |
| 1 | AccountRange download | network |
| 3 | ByteCode + Storage download | network |
| 5 | StateHealing | local I/O (walk) + network (heal) |
| 6 | Validation | local I/O |
| 7 | Chain download / handoff | network |
| 8 | Completed (regular sync from here) | network |

## 0. Boot and peer discovery

On start the node loads its key (`node.key` in the datadir), binds P2P/discovery on
**30303** (TCP+UDP must match — the ENR advertises the bind port), detects its external IP,
and seeds discovery from the config bootnodes plus **EIP-1459 DNS trees**
(`all.classic.blockd.info` for ETC; `all.mordor.etcdisco.net` and `all.mordor.blockd.info`
for Mordor). Within seconds you should see:

```
DNS_DISCOVERY: Resolved 8 enode(s) from all.mordor.etcdisco.net
PEER_HANDSHAKE_SUCCESS: Peer … cap=ETH68 client=core-geth … forkAccepted=true supportsSnap=true
```

fukuii advertises **eth/68, eth/69, snap/1**. eth/69 peers send no total difficulty in their
STATUS; the node derives their chain weight via a calibration cascade (and you may see
`SNAP_CALIBRATION_PEER` / `ETH69_BRU_POST_HANDSHAKE` lines — informational). Off-network
peers are rejected at the networkId/genesis check after a tolerant STATUS decode, not by
crashing the codec.

**Normal:** double-digit handshakes within a couple of minutes on mainnet. **Not normal:**
`peers=0` for >5 min — check UDP 30303 reachability and that your advertised IP is correct.

## 1. Pivot selection (and bootstrap checkpoints)

SNAP sync downloads state at a recent block — the **pivot** — chosen
`pivot-block-offset` (default 64) blocks behind the network head, inside the window peers
actually serve (core-geth indexes snapshots ~128 blocks back). With
`use-bootstrap-checkpoints = true` a packaged checkpoint can seed the pivot header so a
fresh node doesn't wait on header consensus.

Pivots age: peers serve a root for roughly 128 blocks (~28 min on Mordor, ~17 min on
mainnet). Long phases therefore **refresh the pivot in place** when all peers go stateless
for the current root (`Pivot refreshed: block N -> M, root x -> y`). This is routine — trie
nodes are content-addressed, so virtually all downloaded data stays valid across a refresh;
healing reconciles the rest.

## 2. SNAP download phases

**AccountRange (phase 1):** contiguous account ranges with Merkle proofs, built directly
into the trie via the streaming stack-trie (`use-stack-trie = true`, the default — no
multi-GB in-memory trie, no multi-minute flush). Throughput is peer-bound: thousands of
accounts/s when servers are healthy. Mordor's ~2.7M accounts take ~30–50 min; mainnet's
~86M take a day-plus on a 2-server network.

**ByteCode + Storage (phase 3):** contract code and per-account storage tries, fetched in
adaptive batches with per-peer response-size ramping; flat slot data is also written to a
dedicated column family for O(1) `SLOAD` later. Watch the queue/backpressure gauges — the
Backpressure dashboard row exists precisely because this phase alternates between
network-bound and local-trie-build-bound.

Progress for all three: `app_snapsync_{accounts,bytecodes,storage_slots}_*` gauges and the
`SNAP Sync Progress` log blocks.

## 3. State healing (phase 5)

The long, quiet phase — covered in depth in the
[State Healing Operator's Guide](state-healing-operations.md). Summary: a level-order BFS
walk over the local trie finds what's missing (`[HEAL-BFS] Level N …`), missing nodes are
fetched via `GetTrieNodes`, a completeness marker enables restart-resume, and a verification
pass confirms there are no gaps. On mainnet expect the walk to dominate wall-clock (hours);
on Mordor it's minutes.

## 4. Validation and handoff (phases 6–7)

State validation spot-checks the assembled state; SNAP finalizes (`snapSyncDone`), the block
import baseline is set at the pivot, and the chain-weight for the pivot is calibrated from
peers (a SNAP-synced node never executed the historical blocks, so cumulative difficulty is
seeded from the network and accumulated normally from there).

## 5. Regular sync (phase 8 / steady state)

Block-by-block import to the tip: the block fetcher requests headers and bodies through the
peers client, which tracks per-peer response rates. Catch-up imports in 50-block batches:

```
Imported blocks 16176171 - 16176220
```

Reference point: a Mordor node ~180K blocks behind catches up at ~100+ blocks/s with a
healthy peer set (single-digit blocks/s means peer starvation, not CPU). At the tip, blocks
arrive on the ETC ~13 s cadence. If block execution ever hits a state node SNAP missed, it
is fetched on demand from SNAP peers and import continues — occasional
`FetchStateNode` activity right after handoff is normal and self-quenching.

## Checkpoint import (archive format version 2)

Instead of SNAP, a fresh node can import a `.checkpoint` archive: `fukuii.sync.checkpoint-sync-file` (a path, `.gz`
accepted) or `fukuii.sync.checkpoint-sync-url`. It runs only while the best block is 0 and no sync is done. It works on
both state layouts, including `fukuii.state.path-live = true`. Version 1 archives are refused: export again with this
release. **Stop sync before exporting**: an export takes hours and fails with `StateNotHeld` if the state moves.

**What the node trusts.**

- **Total difficulty is trusted from the archive**, not re-derived, and proof-of-work fork choice compares it. An archive
  whose exporter could neither sum it from genesis nor held the chain-weight invariant carries an *estimated* TD: the
  import accepts it and logs a WARN naming the block and the TD. `ChainWeightReconcile` checks it once the headers below
  the checkpoint are in.
- **The expected block hash is required.** Set `fukuii.sync.checkpoint-sync-expected-hash` (32 bytes, hex), or rely on the
  hash the chain config pins for a public chain (`checkpoint-expected-block-hash`); the operator's wins. An archive
  whose checkpoint block hashes to anything else is refused before anything is written. ETH and ETC share a genesis
  hash, so this and the chain id are what tie an archive to its chain. With neither, no archive is imported (a
  configuration error, not recorded as a refusal).
- **Proof of stake: finalized blocks only.** On Sepolia, ETH mainnet and Hoodi the exporter refuses a block above the
  finalized block, and defaults to the finalized one. Take the expected hash from a finalized beacon block's
  `execution_payload.block_hash`. The importer requires TD >= TTD on a post-merge header.

**Failure and no retry.** The import is atomic on the unified layout: the rows are written at their positions in
batches, and one synced batch then writes the anchor, `state.origin`, the window and the weight together, so a kill
leaves either no anchor or the whole state, never an anchor over part of the rows. An archive that is refused after its
header is read (a bad window, node, code or checksum, a block hash that is not the expected one), and an import that was
killed, is **recorded as refused by its id** (keccak of the archive's header block; AppState key
`checkpoint.import.refused`, the last 32 ids). The rows it wrote are cleared and the node syncs by SNAP from a clean
state. The same archive is **not imported again on a restart**, since each attempt would wipe the SNAP progress made
since. A different archive (another block) is imported without any flag.

To import a refused archive again (after correcting `checkpoint-sync-expected-hash`, or after replacing a truncated file
whose header is the same), set `fukuii.sync.checkpoint-sync-retry-refused = true` for **one start**: while it is set,
every start re-runs the import. A successful import removes the archive from the record.

## Timing cheat-sheet (live-observed reference points, June 2026)

| Network | Phase | Observed |
| --- | --- | --- |
| Mordor | full SNAP (empty datadir → tip) | ~1 h |
| Mordor | checkpoint import bootstrap | ~2 min to start SNAP at pivot |
| Mordor | regular-sync catch-up | ~100–220 blocks/s |
| ETC mainnet | accounts+storage+bytecode | day(s), peer-bound (~2 SNAP servers) |
| ETC mainnet | healing walk (one pass) | hours (~119M nodes at ~1,000 nodes/s avg) |
| ETC mainnet | healing fetch of an ~835K-node gap | minutes-to-hours, peer-bound |

## Restart behavior at each stage

| Restarted during | What happens |
| --- | --- |
| Accounts / storage / bytecode | Phase progress is persisted; completed phases are skipped (`Recovery: storage phase already complete — skipping re-download`) |
| Healing, walk incomplete | Full re-walk (partial frontiers are never trusted); already-healed nodes stay healed |
| Healing, walk complete (marker set) | Frontier resumes — the multi-hour walk is skipped |
| Regular sync | Resumes from the last imported block |
| Checkpoint import | The import key marks it: the archive is recorded as refused, its rows are cleared (unified layout), SNAP starts clean; the archive is not retried unless `checkpoint-sync-retry-refused` is set |
