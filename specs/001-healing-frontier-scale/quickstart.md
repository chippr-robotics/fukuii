# Quickstart / Validation Guide: Healing Frontier-Rebuild Scalability

How to prove each layer works. Details live in [data-model.md](./data-model.md),
[research.md](./research.md), and [contracts/](./contracts/).

## Prerequisites

- Build: `sbt compile-all`
- Format/lint before PR: `sbt pp`
- Tier-1 gate: `sbt testEssential` — **do not** run while Barad-dûr nodes are active (freezes
  the host; see project memory).
- Targeted suites (faster loop): `sbt "testOnly *TrieNodeHealingCoordinator*"`,
  `sbt "testOnly *HealingFrontierStorage*"`.

---

## Layer 1 — bounded `visited` (the OOM-stopper) — *implemented, validate*

**Unit/property (deterministic, no node):**

1. **Eviction bound**: drive `newBoundedVisitedSet()` past `HealingVisitedCap` and assert
   `size() <= cap` throughout. Expected: live size capped (INV-3).
2. **Completeness under eviction (property)**: build a synthetic trie with known missing nodes
   that exceeds the cap; run `rebuildFrontierDFS`; assert the resulting frontier contains
   **every** missing node exactly once (INV-1), regardless of cap size (test with a tiny cap to
   force heavy eviction).
3. **Termination with sharing**: a trie where many leaves share one present subtrie (model the
   empty-storage-root); assert the walk terminates and visits the shared node a bounded number
   of times (INV-2).
4. **Config**: set `snap-sync.healing-visited-cap` and assert `SyncConfig` carries it and the
   coordinator uses it (default `4000000` when unset).

**Operational acceptance (the real bug):**

5. On a node with 35M+ state nodes that restarts during healing: the process reaches regular
   sync with **zero** OOM restarts and walk-bookkeeping heap stays ≤ ~350 MB (SC-001, SC-002).
   Watch logs for `[HEAL-RESTART-DFS] Progress: N nodes visited …` climbing past the old
   ~36.6M ceiling without the heap pegging at 96%.

---

## Layer 2 — persisted frontier + resume — *to build*

**Storage round-trip (unit):**

1. `HealingFrontierStorage` put/remove/loadAll round-trip; `pathset` (de)serialization property
   (C6): `deserialize(serialize(p)) == p`.

**Coordinator behaviour (Pekko TestKit, deterministic):**

2. **Resume skips DFS**: pre-populate the persisted CF with a known frontier, send
   `[HEAL-RESTART]`; assert the coordinator loads it via `queueNodes` and does **not** run
   `rebuildFrontierDFS` (FR-007). Verify with a spy/flag, not a sleep.
3. **Write-on-queue / delete-on-heal**: queue nodes → assert rows present; heal-flush those
   hashes → assert rows removed; dispatch without heal → assert rows **still present** (C2,
   INV-4).
4. **Fail-safe fallback**: empty CF, missing CF, and a forced read error each fall back to the
   full DFS and log loudly — never skip healing (FR-009, C4, INV-5).
5. **Idempotent resume**: load an entry whose node is already in storage → no-op, no error
   (FR-010, C5/INV-6).
6. **Disabled flag**: `healing-frontier-persistence = false` ⇒ no CF writes, always full DFS
   (Layer-1 parity).

**Operational acceptance:** after a healing restart with a populated persisted frontier, healing
resumes in minutes (O(frontier)), not a multi-day full-state re-walk (SC-004). Confirm no
`[HEAL-RESTART-DFS]` full walk in the logs when the persisted frontier is non-empty.

**Migration check:** start a pre-Layer-2 datadir on the Layer-2 build; confirm the new column
family auto-creates (`Successfully opened RocksDB … column family handles` count +1) with no
manual migration.

---

## Layer 3 — first-walk throughput — *deferred / optional*

1. **Throughput**: measure nodes/s of the full DFS before vs after (ETC-mainnet-sized state);
   assert a material improvement over the ~142 nodes/s baseline and acceptable total wall-clock
   (SC-005).
2. **Memory budget**: with any RocksDB tuning enabled, assert host memory stays within budget —
   no regression into the over-subscription failure mode (`snap_etc_host_freeze_oversubscription`).

---

## Definition of done (per layer)

- L1: tests 1–4 green; operational acceptance 5 observed on a large-state node; `sbt pp` clean.
- L2: tests 1–6 green; operational acceptance + migration check observed; `sbt pp` clean.
- L3 (if pursued): throughput + memory-budget checks green.
- All: consensus unaffected — `eye` confirms node imports blocks under regular sync after healing
  (SC-003).
