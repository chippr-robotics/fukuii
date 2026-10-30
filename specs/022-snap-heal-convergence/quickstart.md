# Validation Runbook: Spec 022

Run these in order. Every live step is a **no-build window**: no sbt or test runs on the host while a node is syncing or following the head, because oomd kills the node. Only one sbt JVM may run at a time, through the `/home/dontpanic/work/.sbt.lock` flock.

## 0. Dev loop (after each slice; minutes, not days)

A full Mordor SNAP sync against a local snap server. It isolates fukuii's own cost from peer scarcity. It is **not** a release gate: the release gates in §2–§3 run on public peers.

- **Local snap server:** `coregeth-mordor.service` (core-geth v1.12.23, datadir `/home/dontpanic/coregeth-mordor`, ancients on `/chipprbots/blockchain/coregeth-mordor-ancient`, port 30313). Enode `9e5d0ea7…602a@127.0.0.1:30313`. It must be synced to the Mordor head before a run.
- **Run config template:** `/home/dontpanic/mordor-scarcity/B/conf/mordor.conf`. It sets discovery off and `blockchains.mordor.dns-discovery-domains = []`, and makes the local peer both a bootstrap node and the only `snap-server-peers` entry. Copy it to a fresh run directory with a fresh datadir. Never use `/home/dontpanic/mordor-data`, the frozen spec 020 differential base.
- **Profiling:** JFR during healing:
  ```
  jcmd <pid> JFR.start name=heal settings=profile filename=<run-dir>/heal.jfr   # when [HEAL] starts
  jcmd <pid> JFR.stop name=heal                                                 # at handoff
  jfr print --events jdk.ExecutionSample <run-dir>/heal.jfr | <summarise top frames>
  ```
- **Host rules:**
  - No sbt run while the dev-loop node is syncing (oomd).
  - No other fukuii unit running.
  - One big disk job at a time: the root SSD is DRAM-less SATA on LUKS.
- **Measured baseline** (2026-10-28, staging plus #1672, Hash scheme, before any spec 022 slice):

  | Phase | Time / cost |
  |---|---|
  | Total Mordor SNAP | **25.6 min** |
  | Accounts | 8 min (about 5,400 accounts/s; 640/s before #1672) |
  | Storage | 8 min |
  | Bytecode | 3.5 min (with one local peer, geth answers each peer's requests in order; a one-peer artifact) |
  | Healing fetches | 19 nodes, fetched inline |
  | Verification walks | 3 walks of 4.78 M nodes (48 s, 50 s, 96 s) at 80–340% CPU, then about 2.5 min to handoff |

  Slice 5 should remove the three walks from the critical path. Record each slice's run against this table.

## 1. Unit and fault-injection tests (builds allowed)

1. `./scripts/fukuii-test only "*Heal*"` and the new 022 specs. For the full list, see research.md R-7.
2. Kill-point harness (child JVM, real RocksDB). Kill at each of:
   - after a node is held;
   - mid-chain commit;
   - mid-re-peg;
   - mid-excluded-set write.

   After each kill, reopen and run the audit. **Expect:** zero present nodes with an absent child (SC-006).
3. Run the fault tests under both `pruning.mode=basic` and `inmemory`.
4. Consensus suites, with no assertion changes (research.md R-4): `testEthereum`, `testVM`, then the ETC-specific list.
5. `./scripts/fukuii-test essential`, once, at the end.

## 2. Gate 1: Mordor fresh sync (ETC/Hash)

**Prerequisites:**
- the Sepolia node is stopped;
- no other fukuii unit is running;
- a fresh datadir, created by this jar (one an older jar made pays a walk). Do not reuse `/home/dontpanic/mordor-data`, which is the frozen base for the spec 020 differential.

1. Start `fukuii-mordor` with SNAP from scratch.
2. **Watch for:**
   - `snap_heal_held_bytes` staying at or below the bound;
   - `snap_heal_nodes_per_repeg` small relative to the trie;
   - no `[HEAL-VERIFY]` gate walk;
   - `[HEAL-COMPLETE]` logged with the root.
3. **At handoff:** the exported root equals the pivot header's `stateRoot`.
4. Follow the head for 24 h. **Expect:** no root mismatch, and `state_ondemand_fetch_total{cause=hole}` = 0 or each case explained (SC-003, SC-004).
5. Record the time from storage-done to handoff (SC-002 baseline).

## 3. Gate 2: Sepolia fresh sync (ETH/Path)

**Prerequisites:**
- the Mordor node is stopped;
- Lighthouse checkpoint sync is running;
- a fresh datadir, created by this jar (one an older jar made pays a walk). **Do not touch** `/home/dontpanic/sepolia-data`.

1. Start `fukuii-sepolia` against the fresh datadir.
2. Watch as for Mordor. **Expect:** from storage-done to handoff in under 12 h (SC-001), and no full-trie walk before handoff (SC-005). Record the pending-task gauge, RSS, the RocksDB gauges, the live set and the fiber count (T063).
3. Follow the head through the Engine API for 24 h. Expect the same as Mordor step 4.

## 4. Secondary: repair of the kept datadir (User Story 4)

1. Start the fixed build on `/home/dontpanic/sepolia-data`, which has no invariant marker.
2. **Expect:** the walk finds holes, healing fills them, and the node hands off. A stamp is not expected: a walk here outlasts a re-peg, so with holes in the datadir it abandons to a lazy hand-off (tasks.md T064).
3. Record the holes found and filled, the walks run, whether it abandoned, and the duration. This tests repair, not convergence.

## 5. Sign-off

- `beacon` signs off the Path slices.
- `forge` signs off the Hash slices, and confirms the ETC suites were green with no assertion changes.
- `vault` signs off write order and atomicity.
- `eye` validates the release gates.
- Tick the gates on #1659.
