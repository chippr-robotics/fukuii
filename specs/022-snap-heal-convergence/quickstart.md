# Validation Runbook: Spec 022

Run these in order. Every live step is a **no-build window**: no sbt or test runs on the host while a node is syncing or following the head, because oomd kills the node. Only one sbt JVM may run at a time, through the `/home/dontpanic/work/.sbt.lock` flock.

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
- a fresh datadir. Do not reuse `/home/dontpanic/mordor-data`, which is the frozen base for the spec 020 differential.

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
- a fresh datadir. **Do not touch** `/home/dontpanic/sepolia-data`.

1. Start `fukuii-sepolia` against the fresh datadir.
2. Watch as for Mordor. **Expect:** from storage-done to handoff in under 12 h (SC-001), and no full-trie walk before handoff (SC-005).
3. Follow the head through the Engine API for 24 h. Expect the same as Mordor step 4.

## 4. Secondary: repair of the kept datadir (User Story 4)

1. Start the fixed build on `/home/dontpanic/sepolia-data`, which has no invariant marker.
2. **Expect:** the node logs that the datadir is pre-invariant, runs one audit and repair pass, heals the holes it finds, stamps the marker, then hands off.
3. Record the holes found and the pass duration. This tests repair, not convergence.

## 5. Sign-off

- `beacon` signs off the Path slices.
- `forge` signs off the Hash slices, and confirms the ETC suites were green with no assertion changes.
- `vault` signs off write order and atomicity.
- `eye` validates the release gates.
- Tick the gates on #1659.
