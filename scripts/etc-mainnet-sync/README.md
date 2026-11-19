# EG-12 run harness (spec 018 C6, T245)

`run-eg12.sh` runs, records and judges gate **EG-12**, the first successful ETC mainnet sync: a fresh SNAP sync on the
unified state layout (`fukuii.state.path-live = true`) with public ETC peers, 24 h of head following, then the first
ETC v2 checkpoint archive exported and re-imported on a second datadir (the ETC half of **EG-9**). The run itself is
C7's T264; T248 rehearses it end to end on Mordor (`--network mordor`). See
`specs/018-storage-engine-consolidation/plan.md`, "Exit gate", and data-model E ("Operational notes") and G.

Nothing here runs in CI. It has been built and checked offline only (below).

## Phases

| Phase | What it does and records |
|---|---|
| `start` | Checks the reference node's chain id, records the jar, its SHA-256, the JVM, the host and the filesystem, and launches node A (SNAP on, path-live on). After `--peer-wait-min` it counts the snap-capable peers (`debug_listPeersInfo`: `supportsSnap` and `forkAccepted`) and applies `--few-snap-peers` when there are fewer than 2. |
| `sync` | Follows the last `[HANDOFF] sampling the state of pivot N` line for the pivot. While the head is in the ancestor window (pivot+1 .. pivot+255) it probes `BLOCKHASH` of the pivot, the block below it and the deepest reachable ancestor, in the head's context, with `eth_call` and a state override, and compares each with the reference. Once the head is past the window and at the reference's head: no `[PATH-PUBLISH]` line (no copy at the hand-off); the hash and `stateRoot` equal the reference's; blocks pivot+1 .. pivot+256 canonical; any ommer in pivot+1..+6 whose parent is below the pivot. |
| `follow` | `--follow-hours` (24). Then: still at the head; every block from the pivot up equal to the reference's; `REORG-EXEC-FAIL` lines; refused blocks (`batch import failed` at ERROR) whose hash is the reference's canonical block; fork moves by depth from `REORG-SETTLE` (depth 1-2 and others); `[LOCAL-STATE-FAULT]` lines. |
| `export` | Records the head, **stops node A** (an export needs a still state: it fails with `StateNotHeld` if the anchor moves, `CheckpointExporter.scala`), and runs `fukuii <network> checkpoint export --block <head> --output ...` (`CheckpointCli`). |
| `reimport` | EG-9: node B on a fresh datadir with **SNAP off** and `sync.checkpoint-sync-file` / `checkpoint-sync-expected-hash` set. A refused archive falls through to SNAP in `SyncController`; with SNAP off it cannot reach the archive's block another way, so only the import's own `[CHECKPOINT IMPORT] success: block=N` line passes. Then the block's hash and `stateRoot`, `--reimport-follow-hours` (1) of following, the canonical comparison and the same log checks. |

Resources are sampled every `--sample-interval` seconds into `OUT/samples.tsv`: head, peers, RSS, CPU seconds, JVM heap
used (`jvm_memory_used_bytes{area="heap"}`), datadir bytes and `app_snapsync_peers_capable_gauge`, for comparison with
spec 007's finding. Every criterion is a row of `OUT/results.tsv`: `PASS`, `FAIL`, `RECORDED` (a figure the gate reads)
or `NOT-EXERCISED` (the chain gave no case; the gate decides). `OUT/summary.txt` closes the run. Exit 0 only with no
`FAIL`; 3 when `--few-snap-peers stop` ended the run.

`--from sync|follow|export|reimport` resumes a run from `OUT/state.env`; `sync` and `follow` need node A still running.

## Required inputs (nothing is defaulted)

| Input | Flag |
|---|---|
| The jar under test | `--jar` |
| The heap, recorded with the results | `--heap` |
| Two fresh datadirs on the run host | `--datadir`, `--reimport-datadir` |
| The results directory | `--out` |
| An independent synced node of the same chain (a core-geth), the source of canonical hashes and roots | `--reference-rpc` |
| The rule for fewer than 2 snap-capable peers, **the user's decision in T011** (plan open item 3) | `--few-snap-peers stop\|continue\|local-peer` (with `--local-peer enode://...` for `local-peer`) |

The run host is not named here either: it is the user's (T011, plan open item 4).

`--export-allow-estimated-td` passes `--allow-estimated-td` to the export. It is off by default: after a SNAP sync the
headers below the pivot may not all be stored, and an archive with an estimated total difficulty hands that estimate
to every node that imports it. That is the user's call, so a refused export is a `FAIL` row until they make it.

## What cannot be satisfied without a live host

All of it beyond the arguments: the run needs a host with the disk for ETC mainnet, public ETC peers, and a synced
reference node. In particular the snap-capable peer count, the hand-off line, the ancestor-window probes, the export's
total-difficulty question and the import's timing are only known on the first run (T248 on Mordor, then T264).

## Checked offline

`shellcheck`; `--help`; a missing required option is a usage error (exit 2); `--dry-run` prints the three command
lines. Each JSON-RPC method, config key, metric name and log line the harness reads was matched against the code on
`staging` when it was written (`SnapFinalization`, `SyncController`, `ConsensusImpl`, `BlockImporter`, `CheckpointCli`,
`DebugService`, `EthInfoService.call`, `SNAPSyncMetrics`). A change to any of those lines must change this script.
