# Spec 018 Track C: C6 live gates and the C7 exit-gate report (T248, T258, T264, T269)

**Status: in progress.** This file collects the live-gate results as they are run. T269 completes it with one row per
EG-1 to EG-16. Until then, a gate with no row here has not been reported. Rows are added by the host that ran the gate.

## Rows so far

| Gate | Task | Host, date | Build | Result |
| --- | --- | --- | --- | --- |
| T248, the Mordor rehearsal of the EG-12 harness | T248 | dev-node, 2026-11-20 | staging `44413fd95` (0.12.65), jar SHA-256 `63ed3d6a…f5873bb87` | **Every node-side criterion passed.** Two rows read FAIL. Both were harness checks, now fixed, and the node was right in both. See [T248](#t248-the-mordor-rehearsal) |
| EG-6, the Mordor half: a fresh SNAP sync, then 24 h with a local miner | T258 | dev-node, from 2026-11-21 | staging `abb4bce57` (0.12.73), jar SHA-256 `2c86abc2…4c161cbd` | **running** |
| EG-6, the private ETC-config PoW net with Olympia | T258 | — | — | **NOT-RUN.** dev-node has no core-geth with Olympia (white-b0x/core-geth `main`), no Olympia genesis JSON and no Go toolchain to build one. The user skipped this step on 2026-11-20 |
| EG-12, the first ETC mainnet sync | T264 | — | — | **NOT-RUN.** User decision, 2026-11-20 (decision 1, the machines): deferred until the NVMe the user is procuring arrives. dev-node's root SSD has 162 GB free, and its raidz1 HDD is too slow for a sync plus a reference core-geth. C8a still waits for this gate |

## Step 0 on dev-node, before the gates (2026-11-20, staging `44413fd95`)

- `sbt assembly` and `sbt compile-all`: pass.
- `sbt "IntegrationTest / test"`: 243 succeeded, 17 failed, 2 canceled. All 17 failures are in the real-socket specs
  `E2EHandshakeSpec` (1), `E2ESyncSpec` (1) and `E2EStateTestSpec` (15). `ci.yml` excludes these from the gate, and they
  are tracked as B-018 and B-188.
- T248's spec list (corrected in #1969):
  - unit `EtcPowReorgMatrixSpec`, `OlympiaSelfDestructSpec`, `SNAPSyncControllerSpec`,
    `SnapStoragePeerEligibilitySpec` and `SyncControllerSpec`: 222 passed, 1 failed;
  - IT `ECIP1017Test` and `ECIP1017OnPositionsTest`: 2 of 2 passed;
  - `scalafmtAll`: no diff.

  The one failure, `SyncControllerSpec` "count the hand-off's samples again", timed out at 3 s. It is a known flake,
  fixed by B-409, and passed 2 of 2 when run alone.

## T248: the Mordor rehearsal

`scripts/etc-mainnet-sync/run-eg12.sh --network mordor`, run end to end: start, SNAP sync on path-live, 4 h of head
following, the v2 archive export, a re-import into a fresh datadir and 1 h of following. The raw `results.tsv`,
`summary.txt` and `run-info.txt` are in [`c7/t248-mordor-rehearsal/`](c7/t248-mordor-rehearsal/). The node logs (14 MB and
2 MB) stay on dev-node, under `~/work/s018-eg12-mordor/OUT`.

| | |
| --- | --- |
| Jar | `fukuii-assembly-0.12.65.jar`, staging `44413fd95`, SHA-256 `63ed3d6a1d317963dd6a104aba66b3ebfe8bc0815c4d9f188c5acec5f873bb87` |
| JVM | OpenJDK 25.0.5.1, `-Xmx4g -XX:-ReduceAllocationMerges` |
| Host | dev-node: Linux 7.0.0-42, 4 CPUs, 16 GB, root SSD (LUKS, SATA) |
| Reference | a local core-geth v1.12.23 (`96b2afc2`), snap-synced fresh for this run |
| `--follow-hours` | **4**, a rehearsal value. EG-6 and T264 carry the 24 h follow |
| Wall time | 6.1 h. Max RSS 8.7 GiB, max heap used 2.2 GiB, datadir 0.8 GiB |

Result by criterion:

- **Snap-capable peers.** PASS: 14 snap-capable peers.
  - The `[SNAP-PEERS]` line: 13 snap-capable, 0 declared, and SNAP began at or over min-snap-peers = 2.
  - A local core-geth was passed as `--local-peer`. It was not needed, and is not counted.
  - The node log counts 13 peers and RPC counts 14. The two were counted at different moments.
- **Sync reaches the head.** PASS: pivot 17,434,792, head 17,435,094, 51 min after launch.
- **No copy at the hand-off.** PASS: no `[PATH-PUBLISH]` line.
- **Root equals the head's stateRoot.** PASS, at block 17,435,082.
- **Ancestor window: BLOCKHASH below the pivot.** FAIL as written by the old harness. The node is right; see
  [Harness note 1](#harness-note-1).
- **Ancestor window: blocks pivot+1 to +256 canonical.** PASS: 256 compared.
- **Ancestor window: an ommer whose parent is below the pivot.** NOT-EXERCISED: Mordor gave no such block.
- **Follows the head for 4 h.** PASS: head 17,436,213, equal to the reference.
- **Node A canonical chain equals the reference.** PASS: 1,409 blocks from the pivot up, every block compared.
- **Node A REORG-EXEC-FAIL.** PASS: 0.
- **Node A rejected canonical blocks.** PASS: 0.
- **Node A fork moves.** RECORDED: one move at depth 1.
- **Node A local state faults.** RECORDED: 0.
- **v2 archive export.** PASS: block 17,436,221 in 555 s, 861,021,134 bytes, SHA-256 `3ab16fe2…b99fff6d0`.
- **Archive import: root equal.** FAIL by the old check; passes the header-level check. See
  [Harness note 2](#harness-note-2).
- **Archive import time** (ETC resync time, SC-013). RECORDED: 69 s for the import, about 111 s from launch to the
  success line.
- **Re-imported node follows for 1 h.** PASS: head 17,436,578, equal to the reference.
- **Node B canonical chain equals the reference.** PASS: 345 blocks above the archive.
- **Node B REORG-EXEC-FAIL and rejected canonical blocks.** PASS: 0 and 0.
- **Node B fork moves.** RECORDED: three moves at depth 1.

### Harness note 1

**BLOCKHASH below the pivot (fixed in #1972).** The pivot was fixed at the start, and SNAP took 51 min with no re-peg.
After the `[HANDOFF]` line (19:08:23.7Z), the node imported pivot+1 to pivot+302 in 5 s. The harness's sync loop probed
only at the live head, which was already outside the window, so no probe ran. #1972 also probes at past blocks.

The same probes were run by hand on node A. Each is an `eth_call` with a state override (`PUSH4 n, BLOCKHASH, …`) at a
past block AT, compared with core-geth's `eth_getBlockByNumber(n).hash`:

| AT | n | BLOCKHASH(n) on node A | Reference |
| --- | --- | --- | --- |
| pivot+1 | pivot | `0xdef81ca8e10f0efd10322b07acb7804f8572d95ba44f00b813fdacacd204e51b` | equal |
| pivot+100 | pivot | `0xdef81ca8…e51b` | equal |
| pivot+255 | pivot | `0xdef81ca8…e51b` | equal |
| pivot+1 | pivot−1 | `0x7fd486c600d35662f85d56740e895cc822e56b63b4fd0e83f7623af6612bb62f` | equal |
| pivot+255 | pivot−1 (the deepest AT can reach) | `0x7fd486c6…b62f` | equal |
| pivot+1 | pivot−255 | `0x576e3073bb01a0953c68b3f12b0ac3b1e906a15e917fccd7c2ed34a8456ee5d8` | equal |
| pivot+100 | pivot−156 | `0xc541663a982c4e239dd2e70c7f8b2425e00e24af24028de251a3c53c90a209a1` | equal |

The pivot is 17,434,792. The storage session accepted this evidence for the ancestor-window criterion of this rehearsal.

### Harness note 2

**Archive import, root equal (header-level check: branch `fix/eg12-reimport-header-check`).** Node B logged
`[CHECKPOINT IMPORT] complete: block=17436221 … elapsed=69s` at 23:21:00.7Z. The harness then asked
`eth_getBlockByNumber(17436221)` and got `null`.

The archive carries the window's 256 headers and 0 bodies (`header and window checked: … window=256 headers and 0
bodies`). The archive's own block is therefore not served over RPC:
- `eth_getBlockByNumber` and `eth_getBlockByHash` return `null`;
- `eth_getBalance` and `eth_getBlockTransactionCountByNumber` at that block return "Block 17436221 not found".

The import was right. Node B's block 17,436,222 is `0x5f310fb136e3101bf6b5c8dba96410ebfa5cf865a1ccf4a0182f40678cb457f1`, with
parentHash `0x68f084f6…7921`, the exported block. It is equal to core-geth's 17,436,222 and was executed on the imported
root `0xe5c6c03c…1171`. The gap (block N not served after an import) is B-411.
