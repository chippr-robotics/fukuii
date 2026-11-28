# Spec 018 Track C: C6 live gates and the C7 exit-gate report (T248, T258, T264, T269)

**Status: in progress.** This file collects the live-gate results as they are run. T269 completes it with one row per
EG-1 to EG-16. Until then, a gate with no row here has not been reported. Rows are added by the host that ran the gate.

## Rows so far

| Gate | Task | Host, date | Build | Result |
| --- | --- | --- | --- | --- |
| T248, the Mordor rehearsal of the EG-12 harness | T248 | dev-node, 2026-11-20 | staging `44413fd95` (0.12.65), jar SHA-256 `63ed3d6a…f5873bb87` | **Every node-side criterion passed.** Two rows read FAIL. Both were harness checks, now fixed, and the node was right in both. See [T248](#t248-the-mordor-rehearsal) |
| EG-6, the Mordor half: a fresh SNAP sync, then 24 h with a local miner | T258 | dev-node, 2026-11-21 to 22 | staging `abb4bce57` (0.12.73), jar SHA-256 `2c86abc2…4c161cbd` | **PASS.** Every node-side criterion passed. 1,200 mined blocks: 1,072 (89 %) are canonical or ommers on core-geth, 128 orphans, and none invalid. One row reads FAIL: the old re-import check. The header-level check passes (#1974; gap B-411). See [EG-6](#eg-6-the-mordor-half) |
| Kill -9 recovery: SIGKILL after the hand-off | spec 018 kill-safety | dev-node, 2026-11-22 | the same build | **PASS.** Reopened cleanly at the head held at the kill, audit resumed with no faults, followed to the live head with the root equal to core-geth's. See [Unclean-stop reopens](#unclean-stop-reopens) |
| Unclean stop: OOM exit during the follow | spec 018 kill-safety | dev-node, 2026-11-22 | the same build | **PASS.** As above |
| EG-6, the private ETC-config PoW net with Olympia | T258 | — | — | **NOT-RUN.** dev-node has no core-geth with Olympia (white-b0x/core-geth `main`), no Olympia genesis JSON and no Go toolchain to build one. The user skipped this step on 2026-11-20 |
| EG-15, Platåberget live head-follow | T267 | — | — | **DROPPED.** The user, 2026-11-25: Platåberget was retired on 2026-10-28, so its gate is dropped (B-068). EG-4 and EG-14 carry the ETH tombstone guard and the `forkchoiceUpdated` latency |
| EG-1 (ETH) and EG-10, their Platåberget halves | T251, T262 | — | — | **DROPPED,** for the same reason. EG-1's ETH half and EG-10's ETH run are Sepolia only |
| EG-12, the first ETC mainnet sync | T264 | dev-node, 2026-11-25 to 26 | staging `c2ae9d555` (0.12.175), jar SHA-256 `46ff8b62…f34fe20d` | **Every node-side criterion passed.** The first fukuii SNAP sync of ETC mainnet: pivot 25,801,564 in 206 min, 24 h at the head, the v2 export at 25,808,558 re-imported with its root equal, and 1 h of following after it. One row reads FAIL: a canonical-chain comparison scored one unanswered public-RPC read as a difference, and that block re-read equals ours (harness fix #2146). That row's verdict is the user's. See [EG-12](#eg-12-the-first-etc-mainnet-sync) |

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

## EG-6: the Mordor half

`scripts/etc-mainnet-sync/run-eg12.sh --network mordor` from staging `abb4bce57`, which includes the fixed ancestor
probes (#1972). The run did a fresh SNAP sync on path-live, then 24 h of head following **with a local CPU miner**, then the
v2 export and a re-import followed for 1 h. The raw `results.tsv`, `summary.txt` and `run-info.txt`, every mined block
(`mined-blocks.tsv`) and every fork move (`reorg-settle.tsv`) are in [`c7/eg6-mordor/`](c7/eg6-mordor/).

| | |
| --- | --- |
| Jar | `fukuii-assembly-0.12.73.jar`, staging `abb4bce57`, SHA-256 `2c86abc29975196ce52f6a816a7962725fab3b6720300673f039180b4c161cbd` |
| JVM | OpenJDK 25.0.5.1, `-Xmx8g`. A miner needs the Ethash DAG on the heap; see [Aborted attempts](#aborted-attempts) |
| Mining | `mining-enabled = true`, `gas-limit-target = 8000000` (Mordor's current limit, so our blocks do not vote it toward fukuii's 60 M default), coinbase `0x7be516f414bc7f8570e08eb2dfa750d98576e683`. That is a keyed chipprbots Mordor test account, so the rewards are retrievable |
| Reference | the local core-geth v1.12.23 (`96b2afc2`) |
| Wall time | 26.8 h. Max RSS 13.1 GiB, max heap used 7.6 GiB, datadir 0.9 GiB. Host: 4 CPUs, 16 GB, with the 4 GB swap full for most of the follow |

Results:

- **Snap-capable peers.** PASS: 13. The `[SNAP-PEERS]` line: 12 snap-capable, 0 declared.
- **Sync reaches the head.** PASS: pivot 17,437,483, 81 min after launch.
- **No copy at the hand-off.** PASS.
- **Root equals the head's stateRoot.** PASS, at block 17,437,929.
- **Ancestor window: BLOCKHASH below the pivot.** PASS: 8 probes, live and at past blocks, all equal to the reference.
- **Ancestor window: blocks pivot+1 to +256 canonical.** PASS: 256 compared.
- **Ancestor window: an ommer whose parent is below the pivot.** NOT-EXERCISED.
- **Follows the head for 24 h.** PASS: head 17,444,954, equal to the reference.
- **Node A canonical chain equals the reference.** PASS: 7,459 blocks from the pivot up, every one compared.
- **Node A REORG-EXEC-FAIL.** PASS: 0.
- **Node A rejected canonical blocks.** PASS: 0.
- **Node A local state faults.** RECORDED: 0.
- **Node A fork moves.** RECORDED: 62, of which 61 at depth 1 and 1 at depth 2.
- **v2 archive export.** PASS: block 17,445,001 in 745 s, 861,052,322 bytes, SHA-256 `f34439c0…2148da51`.
- **Archive import: root equal.** FAIL by the old check. This run's harness predates #1974, and the archive's own block
  is not served (B-411). It passes the header-level check: core-geth's 17,445,002 has parentHash `0x4de0d739…5748`, the
  exported block, and node B's chain equals the reference for the 349 blocks above it. The import took 93 s.
- **Re-imported node follows for 1 h.** PASS: head 17,445,362, equal to the reference.
- **Node B canonical chain equals the reference.** PASS: 349 blocks.
- **Node B REORG-EXEC-FAIL, rejected canonical blocks and local state faults.** 0, 0 and 0.
- **Node B mining.** Node B ran with the miner on and loaded the DAG from the file (05:41:28 to about 05:43:58Z), not
  generating it again. It mined 47 blocks in its hour.

### Mined blocks valid on another client

Every `Block mined [number, hash]` line of node A and node B was classified against core-geth:
- **canonical:** the reference's block at that number has that hash;
- **ommer:** the hash is in the `uncles` of one of the next seven reference blocks;
- **orphan:** anything else.

| Node | Canonical | Ommer | Orphan | Total |
| --- | ---: | ---: | ---: | ---: |
| A | 990 | 42 | 121 | 1,153 |
| B | 38 | 2 | 7 | 47 |
| **All** | **1,028** | **44** | **128** | **1,200** |

1,072 of 1,200 (89 %) are canonical or ommers on core-geth, so core-geth accepted them as valid blocks.
- 126 of the orphans are unknown to the reference: they lost the race before propagating.
- 2 are known to it as side blocks.
- One orphan is node B's block #1, mined on genesis while its checkpoint import ran (B-424).

The coinbase held 884.85 Mordor ETC at the end. Mordor is in ECIP-1017 era 8, where the block reward is 5 × 0.8⁸ ≈ 0.839.

### B-141, M10 and M13

- **B-141.** PASS. `regularsync.orphan.blocks.total{outcome="blacklisted"}` was **0** in every one of 25,036 samples over
  the run; the only outcome seen was `branch-fetch` (49 on node A). So no peer was blacklisted for a missing parent in 24 h.
- **M10, forks.** 62 fork moves in 24 h, about 2.6 an hour, from `REORG-SETTLE` (`reorg-settle.tsv`).
  - Depth (old head − fork): 61 at depth 1 and 1 at depth 2.
  - `regularsync.reorg.total` = 61 and `regularsync.branch.resolution.rounds.total` = 10.
  - The proposer view: `PoWBlockGenerator` built 31,818 blocks in 30.16 s, a mean of 0.95 ms per block.
  - The **`moveHead` cost could not be read**: no `db.state.*` meter was exported (B-425). Record it again once that is
    fixed.
- **M13, branch lengths.** Blocks executed per `settleHead`: 1 block ×6, 2 ×50, 3 ×5, 4 ×1. The longest branch adopted
  was 4 blocks, far below any cap.
  - Layer bytes (`db.state.overlay.*`) are not readable, for the same reason (B-425).
  - Whether real branches exceed the pinned cap: they do not. The longest was 4.

### Aborted attempts

These were stopped or failed before the counted run, and their datadirs are kept on dev-node.

| Attempt | What ended it |
| --- | --- |
| 00:24Z, 11-21 | Restarted after 3 min to hold the gas-limit target at 8 M (storage session's request) |
| 00:26Z | Restarted to change the coinbase from a keyless placeholder to the keyed test account (user's request) |
| 00:31Z | SIGKILLed with the Claude CLI's terminal scope at 01:43:37Z, after the hand-off (pivot 17,436,552), mid-DAG. Its datadir is the kill -9 reopen below. The runs since then are `systemd-run --user` units |
| 02:13Z | `OutOfMemoryError: Java heap space` at 03:50Z during the follow, at `-Xmx4g`. Mordor's epoch is 290 (ECIP-1099), so its DAG is 3.27 GiB of data, about 4.1 GiB as `Array[Array[Int]]` on the heap. At 53 % of the DAG `heap_used` was 4.23 GB. A heap-sizing fault in the run, not storage. #1977 moves the DAG off the heap |
| 03:54Z | Stopped after 1 min to raise `-Xmx7g` to `8g` |

### Findings from the run

- **B-424.** The PoW mining coordinator hot-loops when there is no best block, during SNAP or a checkpoint import. In
  `PoWMiningCoordinator.scala:117-120` it logs at ERROR and sends itself `MineNext` with no delay.
  - Node A wrote 90,681,933 such lines, 11.8 GB of its 12.5 GB log, in the 80-min sync (03:55 to 05:15Z).
  - Its sync took 9,145 CPU seconds, against 4,828 in the unmined T248 run.
  - Node B wrote 280,475 lines in 5 s, then 12,207 `StateUnavailable: the state of block 0 … no state anchor` errors
    from the block generator, and mined block #1 on genesis. That block was harmless: B's chain equals the reference.
- **B-425.** The `db.state.*` meters are never exported on a path-live node: 189 `app_*` series and 0 `app_db_state_*`.
  - The theory: `StateMetrics` is a `MetricsContainer` first touched as the commit observer when the storage opens. In
    node B's log RocksDB opened at 05:41:23 and "Metrics started" came at 05:41:25. Its meters therefore bind to
    `Metrics.get()`'s default registry, the pattern fixed for the node allowlist in #1959.
- **Heap for a miner.** An ETC or Mordor miner needs about DAG × 1.25 + 3 GB of heap, which the operations docs do not
  say. #1977 (the DAG in an mmap'd file) removes the need. Re-measure RSS on a run with it.

## EG-12: the first ETC mainnet sync

`scripts/etc-mainnet-sync/run-eg12.sh --network etc`, the harness pinned at #2121 (`141120f63`), against the staging
jar below. It was the first SNAP sync fukuii has completed on ETC mainnet. The run had five stages:

1. a fresh SNAP sync on path-live, with public ETC peers;
2. 24 h of head following;
3. a v2 export;
4. a re-import into a fresh datadir;
5. 1 h of following on the re-imported node.

No core-geth reference fitted on dev-node (decision 1), so the reference was a public RPC, as the user chose. The
raw `results.tsv`, `summary.txt` and `run-info.txt`, and every fork move (`reorg-settle.tsv`, node A then node B), are
in [`c7/eg12-etc/`](c7/eg12-etc/).

| | |
| --- | --- |
| Jar | `fukuii-assembly-0.12.175.jar`, staging `c2ae9d555`, SHA-256 `46ff8b62516e9b7e0fdd0ef90bfd3e21e1bb0a07b0f6ea1035477db3f34fe20d` |
| JVM | OpenJDK 25.0.5.1, `-Xmx6g` |
| Reference | `https://etc.drpc.org` (Geth/v10.0.0/drpc), a public RPC |
| Host | dev-node: 4 CPUs, 16 GB, a DRAM-less SATA SSD on LUKS |
| Wall time | 31.2 h. Max RSS 11.8 GiB, max heap used 4.7 GiB. Node A's datadir was 50 GiB at the end of the follow; node B's was 11 GiB after the re-import and 1 h of following |
| Run | 2026-11-25 05:30Z to 2026-11-26 12:42Z |

Results:

- **Snap-capable peers.** PASS: 16 of 17 handshaked over RPC. The node's `[SNAP-PEERS]` line, written earlier, counted 2
  of 2.
- **Sync reaches the head.** PASS: pivot 25,801,564, 206 min after launch. State sync ended at 133 min. The remaining
  72 min were the header download from genesis (B-522, below).
- **No copy at the hand-off.** PASS: no `[PATH-PUBLISH]` line.
- **Root equals the head's stateRoot.** PASS: at block 25,801,991, the hash and stateRoot equal the reference's.
- **Ancestor window: BLOCKHASH below the pivot.** PASS: 14 probes, all equal to the reference.
- **Ancestor window: blocks pivot+1 to +256 canonical.** PASS: 256 compared.
- **Ancestor window: an ommer whose parent is below the pivot.** NOT-EXERCISED: none in pivot+1 to +6.
- **Follows the head for 24 h.** PASS: head 25,808,409, equal to the reference.
- **Node A canonical chain equals the reference.** The harness wrote **FAIL** ("1 of 6833 block(s) differ").
  - The block was 25,808,199, and the harness's own line reads `ours 0xde16f763…4610, reference unreadable`: the public
    RPC did not answer one request, and the harness read each block once and scored a failed read as a difference.
  - Re-read at 2026-11-26 09:38Z, the reference's hash for 25,808,199 equals ours, and so does 0xrpc.io/etc's. So all
    6,833 compared blocks equal the reference.
  - The harness fix is #2146: retry reference reads, and count a block the reference cannot serve apart from a
    difference. **This row's verdict is the user's.** On the evidence it is a harness fault, not a node one.
- **Node A REORG-EXEC-FAIL.** PASS: 0.
- **Node A rejected canonical blocks.** PASS: 0.
- **Node A local state faults.** RECORDED: 0.
- **Node A fork moves.** RECORDED: 159, of which 156 at depth 1 and 3 at depth 2.
- **v2 archive export.** PASS: block 25,808,558 in 4,157 s, 17,708,025,821 bytes, SHA-256
  `39a5dc64326ced884f32bb6c6119ccccd02a8d4e338490e6254f6088fc11fe95`. Block hash
  `0x7b3be997e45d25215ab47131b8595516cc0b7cde746f461c82abe8d42e399422`, stateRoot
  `0x67021a0e0e014cca4c9aaddfb506b31897ec351561993270d7433ca40c494d84`.
- **Archive import: root equal.** PASS.
  - The import took 2,949 s: 171,966,918 nodes (15,939 MiB) and 37,220 bytecodes.
  - `tdExact=true`, so the archive carried the exact total difficulty and decision 3 did not arise.
  - Block 25,808,559, executed on the imported state, equals the reference's.
- **Archive block served over RPC.** RECORDED: `null`. The archive carries no body for its own block (B-411).
- **Re-imported node follows for 1 h.** PASS: head 25,809,353, equal to the reference.
- **Node B canonical chain equals the reference.** PASS: 783 blocks above the archive.
- **Node B REORG-EXEC-FAIL, rejected canonical blocks.** PASS: 0 and 0.
- **Node B local state faults.** RECORDED: 0.
- **Node B fork moves.** RECORDED: 5, all at depth 1.

Findings carried to the backlog:

- **B-522: the header download is the critical path.**
  - State sync finished at 07:43Z, 133 min in. Finalisation was then held 72 min, until the header download from
    genesis reached the pivot.
  - During state sync the chain download ran at concurrency 2: headers reached 10.56 M at 07:43Z, about 1,320 a second.
  - After state sync, at concurrency 16, it covered the remaining 15.2 M in 72 min, about 3,500 a second.
- **B-526: account-range timeouts.**
  - There were 353 `GetAccountRange` timeouts, from 20 peers, over 3,801 completed account tasks. That compares with 421
    in the tester-findings run.
  - They were concentrated: three peers gave 207 of them (76, 74 and 57), and the rest gave 26 or fewer each. That
    points at the peers, not the response size.
  - The node does not log peers' client ids, so the timing-out peers are not identified by client (B-563).
- **B-562: the node advertised loopback.**
  - External-IP detection failed at start, even though UPnP port forwarding had been set up 3 s earlier. The enode
    therefore carried `127.0.0.1:30351`.
  - The node had outbound peers only, and crawlers such as etcnodes did not list it.
  - It followed the head on outbound peers throughout.

## Unclean-stop reopens

Both datadirs were reopened with the same jar and **mining off**, after EG-6 ended and one at a time. Each ran as a user unit
on its own ports (RPC 8643), against the same core-geth.

| | SIGKILL after the hand-off (`node-a-killed-cli`) | OOM exit during the follow (`node-a-oom-4g`) |
| --- | --- | --- |
| How it stopped | 2026-11-21 01:43:37Z, SIGKILL, mid-DAG, head 17,436,959 | 2026-11-21 03:50Z, `-XX:+ExitOnOutOfMemoryError`, head 17,437,513 |
| Opens cleanly | yes: `State reconciled at start: the best block is the block whose state is held (17436959 (fdf12018d636ab24))` | yes: `State reconciled at start: … (17437522 (6de17505b6ea8836))` |
| Anchor | the audit resumed for anchor **17,436,552** (the pivot) at 1 saved position | the audit resumed for anchor **17,437,022** (the pivot) at 2,719 saved positions |
| Resumes following | about 11,700 blocks to the live head (17,448,699) in under 2 min | about 11,200 blocks to the live head (17,448,708) in under 90 s |
| Root against the reference | head−5: hash `0x86b0032b…9402` and stateRoot `0x0670287a…f040` equal core-geth's | head−5: hash `0x33662fe5…6e0c` and stateRoot `0xae726958…f9d4` equal core-geth's |
| Repair or audit errors | none. `[HEAL-AUDIT]` holes=0 repaired=0 unresolved=0 badNodes=0 | none. `[HEAL-AUDIT]` nodes=4,300,556 accounts=2,101,561 holes=0 repaired=0 badNodes=0 |
| `[LOCAL-STATE-FAULT]`, `REORG-EXEC-FAIL`, ERROR lines | 0, 0, 0 | 0, 0, 0 |

The anchor is read from the `[HEAL-AUDIT]` line, because the `db.state.anchor.number` gauge is not exported (B-425).
Stopping the first reopen with SIGTERM logged `Error while shutting down… TimeoutException: Future timed out after
[15000 milliseconds]` (`StdNode.scala:852`). The JVM then exited 143. A shutdown step's 15 s bound, noted for the
storage session.
