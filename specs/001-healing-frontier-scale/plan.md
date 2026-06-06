# Implementation Plan: Post-SNAP Healing Frontier-Rebuild Scalability

**Branch**: `fix/healing-frontier-scale` | **Date**: 2026-06-06 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-healing-frontier-scale/spec.md`

## Summary

Post-SNAP healing rebuilds the missing-node *frontier* on restart by walking the entire state
trie (`TrieNodeHealingCoordinator.rebuildFrontierDFS`). On ETC mainnet (35M+ nodes, ~77 GB
RocksDB) the unbounded in-memory `visited` set grew to ~2.9 GB, drove the heap to 96%, and —
with `-XX:+ExitOnOutOfMemoryError` — produced an unrecoverable OOM → restart → re-walk loop, so
the node never reached regular sync.

The fix is delivered in three layered, independently shippable slices:

1. **Bounded `visited` set (P1, the OOM-stopper — already in the working draft, compile-validated).**
   Replace the unbounded `mutable.Set[ByteString]` with a fixed-capacity LRU
   (`LinkedHashMap.removeEldestEntry`, default 4M entries ≈ 320 MB). Completeness is preserved
   by the existing `pendingHashSet` de-dup; the walk completes without crashing. This plan adds
   one thing to the draft: make the cap operator-tunable via `sync.conf` (FR-012).
2. **Persisted frontier + resume (P2, follow-on).** A dedicated RocksDB column family
   (`hash → pathset`) written on `queueNodes` and deleted on heal-flush, plus a
   `HealingFrontierStorage` plumbed into the coordinator from `SNAPSyncController` (mirroring
   `FlatSlotStorage`). On `[HEAL-RESTART]`, load the persisted frontier and skip the full DFS;
   fall back to the DFS only when the persisted frontier is empty/absent/corrupt. Restart
   becomes O(frontier) instead of O(full state).
3. **First-walk throughput (P3, optional).** Parallelise/batch the I/O-bound DFS point-reads
   (~142 nodes/s serial) and/or RocksDB tuning, respecting the documented open-files/block-cache
   vs. heap trade-off. Deferred until the first walk is shown to still be too slow after 1+2.

This is a sync-mechanism scalability change. It does **not** alter trie-node hash verification,
RLP, state roots, gas, or any consensus rule — the only correctness invariant it must uphold is
healing **completeness** (every missing node still gets healed).

## Technical Context

**Language/Version**: Scala 3.3.7 LTS on JDK 25 (Temurin); sbt 1.10.7+.

**Primary Dependencies**: Apache Pekko (actors — `TrieNodeHealingCoordinator` is a Pekko
actor); RocksDB via `DataSource` / `TransactionalKeyValueStorage`; Cats Effect `IO` + fs2
(storage streaming, used by `FlatSlotStorage` — the wiring template for Layer 2); BouncyCastle
(hashing, already present). No new external dependency is required.

**Storage**: RocksDB. Layer 2 adds one column family for the persisted frontier
(`node hash → encoded pathset`) via a new `Namespaces` entry. `setCreateMissingColumnFamilies(true)`
is already set (`RocksDbDataSource.scala:389`), so existing nodes auto-create the CF on next
open — **no manual migration**.

**Testing**: ScalaTest + Pekko TestKit (actor behaviour), ScalaCheck (property tests for
walk completeness/termination). Tiers: `testEssential` (PR gate), `testStandard` (+coverage).
Deterministic only — no `Thread.sleep` (use `awaitCond`/`eventually`).

**Target Platform**: Linux server (full node).

**Project Type**: Single project — the `src/main` node module under
`com.chipprbots.ethereum`.

**Performance Goals**: L1 — walk completes with bookkeeping ≤ ~350 MB regardless of state size
(vs ~2.9 GB unbounded). L2 — restart resumes in minutes (O(outstanding frontier)) vs days
(O(full state)). L3 — first-walk throughput materially above the ~142 nodes/s serial baseline
(target: single-digit hours on ETC mainnet, not days).

**Constraints**: Healing completeness MUST be preserved (no missing node skipped). Walk MUST
terminate for any state (re-walks add bounded work, never cycles). Bounded-memory cap default
≈ 320 MB. Must not reintroduce host memory over-subscription (respect open-files/block-cache
trade-off). Tests deterministic; statement coverage ≥ 70%.

**Scale/Scope**: ETC mainnet — 35M+ state nodes (accounts + every storage trie), ~77 GB
on-disk RocksDB. Must also remain correct for larger chains.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Verdict | Notes |
|-----------|---------|-------|
| I. Consensus Determinism (NON-NEGOTIABLE) | ✅ PASS | Mechanism-only. No change to trie-node hash verification (`isNodeInStorage`/store-by-hash), RLP, state roots, gas, rewards, or hard-fork config. Not under `consensus/`, `vm/`, `crypto/`, `domain/`. The `forge` protocol is **not triggered** by the change surface, but the **completeness invariant** (every missing node still healed) is a correctness property and is argued explicitly in research.md (de-dup via `pendingHashSet`; DFS termination). `eye` validation is required after implementation. |
| II. Spec-Driven Development | ✅ PASS | Following the Spec Kit flow; spec.md complete and validated; artifacts under `specs/001-healing-frontier-scale/`. |
| III. Test Discipline & Tiered Coverage | ✅ PASS | New tests: bounded-set eviction + completeness + termination (property), persisted-frontier round-trip, corrupt/empty-CF fallback, idempotent resume. Pekko TestKit for the actor; no `Thread.sleep`; coverage ≥ 70%. Not consensus-critical, so ethereum/tests validation is not additionally required. |
| IV. Idiomatic, Formatted Scala 3 | ✅ PASS | `scalafmt`/`scalafix` clean (L1 draft already compiles). The `LinkedHashMap` LRU uses Java interop deliberately for O(1) insertion-order eviction with a bounded heap; justified in research.md. New storage class follows the existing `FlatSlotStorage` idiom. |
| V. Quality Gates Are Mandatory | ✅ PASS | `sbt pp` before PR; CI gates apply. Layers ship as separate PRs, each independently green. |
| VI. Security & Operational Safety | ✅ PASS | No key/RPC/network surface touched. Net operational-safety improvement (node recovers instead of OOM-looping). New `sync.conf` keys documented; defaults safe. |
| VII. Transparent Versioning & Decision Records | ✅ PASS | Conventional commits (`fix:` for L1, `feat:` for L2). `docs/design/healing-frontier-scale.md` is the decision record; a dedicated ADR is not warranted for a non-consensus mechanism change (link the design doc from PRs). |

**Result**: No violations. Complexity Tracking is empty.

## Project Structure

### Documentation (this feature)

```text
specs/001-healing-frontier-scale/
├── plan.md              # This file
├── spec.md              # Feature specification (already created)
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output (internal storage + config contracts)
│   ├── healing-frontier-storage.md
│   └── config-keys.md
├── checklists/
│   └── requirements.md  # Created by /speckit-specify
└── tasks.md             # /speckit-tasks output (NOT created here)
```

### Source Code (repository root)

```text
src/main/scala/com/chipprbots/ethereum/
├── blockchain/sync/snap/
│   ├── actors/TrieNodeHealingCoordinator.scala   # L1: bounded visited set (done) + cap from config
│   │                                             # L2: load persisted frontier on [HEAL-RESTART],
│   │                                             #     write on queueNodes, delete on heal-flush
│   │                                             # L3: parallel/batched DFS reads
│   └── SNAPSyncController.scala                   # L2: construct HealingFrontierStorage, pass to props
├── db/storage/
│   ├── Namespaces.scala                           # L2: add HealingFrontierNamespace (new CF byte)
│   └── HealingFrontierStorage.scala               # L2: NEW — TransactionalKeyValueStorage[hash, pathset]
└── utils/Config.scala                             # L1+L2: parse healing-visited-cap, healing-frontier-persistence

src/main/resources/conf/base/sync.conf             # L1: healing-visited-cap; L2: healing-frontier-persistence

src/test/scala/com/chipprbots/ethereum/
├── blockchain/sync/snap/actors/                   # L1: bounded-set completeness/termination/eviction
│   └── TrieNodeHealingCoordinator*Spec.scala      # L2: resume-from-frontier, fallback, idempotency
└── db/storage/HealingFrontierStorageSpec.scala    # L2: NEW — round-trip + (de)serialization

docs/design/healing-frontier-scale.md              # Decision record (exists; update if design shifts)
```

**Structure Decision**: Single-project Scala/sbt node module. Layer 1 is localized to
`TrieNodeHealingCoordinator.scala` (+ one config key). Layer 2 adds one storage class
(`HealingFrontierStorage.scala`), one `Namespaces` entry, coordinator constructor wiring, and a
`SNAPSyncController` construction-site change — exactly mirroring how `FlatSlotStorage` is wired
today. Layer 3 is confined to the DFS read path in the coordinator plus optional RocksDB tuning.

## Complexity Tracking

> No Constitution Check violations — section intentionally empty.
