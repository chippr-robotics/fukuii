> Closed 2026-10-30 as Parked; boxes below are as they were at closure.

---
description: "Dependency-ordered tasks for spec 007 — hot-path allocation reduction"
---

# Tasks: Hot-Path Allocation Reduction for SNAP Sync

**Feature**: `007-hotpath-alloc-reduction` | **Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

**Overriding gate (Constitution I):** PURE PERFORMANCE — every change MUST be byte-for-byte identical in consensus output. keccak/merkleization are consensus-critical. **The `kec256` helper is CHAIN-SHARED** — consumed by ETC PoW (Ethash/ommers) AND ETH/Sepolia PoS (`EngineApiService` payload-id/block-hash) — so review requires BOTH `forge` (ETC) AND `beacon` (ETH/Sepolia) per Constitution Principle I + Workflow step 7. Parity tests are the only HARD gate; the benchmark is report-and-record. Two CRITICAL chain-split risks govern the work: **omitting reset-on-entry** and **pooling the StackTrie final blob** (see [research.md](./research.md) R1/R2).

**Scope note (out of scope):** two more per-call `new KeccakDigest` sites exist OUTSIDE `kec256` — scalanet discovery `Keccak256.apply` and the RLPx MAC (`AuthHandshaker`/`FrameCodec`). Non-consensus, lower frequency — explicitly NOT in scope for spec 007 (defer to a future story under the same parity bar).

**Story map:** US1 = P1 keccak ThreadLocal reuse (🎯 MVP, independently shippable) · US2 = P2 StackTrie scratch reuse · US3 = P3 opportunistic (each FR-010-gated).

**Prior-analysis remediation provenance:** inline finding IDs from earlier analysis map to the tasks that resolve them — CONST-01 → T012b (`beacon` ETH/Sepolia sign-off on the chain-shared `kec256`); COV-02 → T019 (overload re-dispatch parity); COV-03 → T011 + T020 (the `deferred=true` / `Node.hashFn` path — T011 gates its `kec256` 3-arg facet via `testComprehensive`, T020 gates its `RLP.encode` facet); COV-06 → T022 (per-item parity + measured-win confirmation); SC-006-gap → T009b (bounded-footprint / no-leak check). These bare IDs are resolvable here.

---

## Phase 1: Setup

- [X] T001 Confirm BouncyCastle `bcprov-jdk18on` 1.84 `KeccakDigest` reset/init/squeeze semantics with `javap -c` (or a one-line decompile) of the exact pinned jar, verifying `reset()` == fresh-construct for fixed length 256 (research §3, open Q6) — record the confirmation in `specs/007-hotpath-alloc-reduction/research.md`.
- [ ] T002 Capture BASELINE benchmark numbers from a parent-commit worktree/stash (per-hash digest allocation rate + hot-path GC time) using `ThreadMXBean.getThreadAllocatedBytes` + `-Xlog:gc`, OFFLINE on an in-memory corpus with the node idle; record the numbers in `specs/007-hotpath-alloc-reduction/plan.md` to ground SC-002/SC-003.

## Phase 2: Foundational (blocking prerequisites)

- [ ] T003 [P] Create benchmark scaffolding `src/benchmark/scala/com/chipprbots/ethereum/HotPathAllocBenchmark.scala` — `getThreadAllocatedBytes(threadId)` before/after helper, `System.nanoTime` throughput, warmup ≥3 discarded + ≥5 measured (median + min/max), production JVM flags from `src/universal/conf/application.ini`, tagged `BenchmarkTest` (excluded from CI tiers); methods stubbed, run on the MEASURING thread (not a dispatcher).
- [ ] T004 [P] Create the A/B state-root replay harness as a `BenchmarkTest`-tagged spec under `src/test/scala/com/chipprbots/ethereum/mpt/` — builds state roots over a fixed, seed-pinned SNAP account+storage corpus on an in-memory `EphemDataSource` (no RocksDB, no peers); emits the root set so old-vs-new diffing proves zero change (the dedicated FR-005 parity check + the `deferred=false` measurement vehicle).

---

## Phase 3: User Story 1 — Eliminate per-call keccak digest allocation (Priority: P1) 🎯 MVP

**Goal**: Replace per-call `new KeccakDigest(256)` with a thread-confined `ThreadLocal[KeccakDigest]` reset-on-entry, identical output, no allocation.

**Independent test**: `testCrypto` green (golden vectors + all overloads vs oracle + reset-after-abort + concurrency); `testComprehensive` post-state roots unchanged; benchmark shows ~0 per-hash digest allocation. Shippable alone.

### Tests for User Story 1 (write first; golden vectors stay green on baseline, reset-after-abort/concurrency guard the new reuse logic)

- [X] T005 [US1] Create `crypto/src/test/scala/com/chipprbots/ethereum/crypto/Keccak256Spec.scala` (tags `CryptoTest`+`UnitTest`) with fixed golden vectors — `keccak256("") = c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470`, `"abc"`, 1-byte, 32-byte, **a maximum-trie-node-size input (~532 bytes — a full branch node RLP)**, and the empty-trie root via `kec256(Array(0x80.toByte))` — and assert all public `kec256` signatures (`Array,start,len` / `Array*` / `ByteString` delegation / `kec256PoW`) agree with an independent inline `new KeccakDigest(256)` oracle.
- [X] T006 [US1] Add the reset-after-abort test to `Keccak256Spec.scala` — a closure that throws between `update` and `doFinal` on a thread, then assert the NEXT `kec256` on the SAME thread returns the correct hash (guards INV-1/FR-002 — the load-bearing parity mechanism).
- [X] T007 [US1] Add the concurrency parity test to `Keccak256Spec.scala` — N threads hash independent inputs via `Future` + a fixed thread pool + `Await` (NO `Thread.sleep`), assert every result equals the single-thread oracle (FR-003/FR-007, no cross-thread bleed).

### Implementation for User Story 1

- [X] T008 [US1] In `crypto/src/main/scala/com/chipprbots/ethereum/crypto/package.scala`, add `private val kec256Digest = ThreadLocal.withInitial(() => new KeccakDigest(256))` and rewrite the **THREE digest-owning entry points** (`:32` `Array,start,len`; `:40` `Array*`; `:51` `kec256PoW`) to `val d = kec256Digest.get(); d.reset(); d.update(...); d.doFinal(out, 0); out` — `reset()` as the FIRST statement on the digest; keep the per-call 32-byte `output` array (it escapes); leave `kec512` (`:60`) untouched. NOTE: `kec256(ByteString)` (`:48`) owns NO digest — it delegates to the `Array` form and is covered transitively (do not "rewrite" it). `kec256PoW` (`:51`) is included for consistency but is **dead code** (zero callers, grep-verified) — the LIVE PoW path is `kec256`/`kec512` in `EthashUtils`/`EthashBlockHeaderValidator`/`StdBlockValidator`.
- [X] T009 [US1] Add a guard comment at the `ThreadLocal` definition in `package.scala`: hashing runs on platform-thread Pekko dispatchers ONLY (no per-task virtual-thread executor without revisiting this); never return/store/close-over the digest reference (R4/R5).
- [X] T009b [US1] Verify FR-009/SC-006 (bounded footprint, no leak): in a `BenchmarkTest`-tagged check, hash from a fixed thread pool of K threads and assert the retained per-thread digest footprint is bounded (≈ K × ~500 B, no growth across repeated pool churn); record the analytic bound (research §2.c) and any accepted residual risk in the PR. **ALSO add a tiny DETERMINISTIC assertion runnable under `testEssential`/`testCrypto`** (NOT `BenchmarkTest`-only, so the bounded-footprint invariant has a CI anchor): over a fixed thread pool, capture each thread's `KeccakDigest` instance identity and assert exactly one instance per thread and that no instance is shared across threads (digest identity-per-thread). Keep the long pool-churn soak as report-and-record. (Closes the SC-006 zero-task gap.)

### Benchmark + verification for User Story 1

- [X] T010 [US1] Add the `kec256` micro-benchmark method to `HotPathAllocBenchmark.scala` (N=5M ~136-byte node-blob inputs on the measuring thread); run and record allocation/throughput delta vs the T002 baseline (SC-001/SC-002).
- [X] T011 [US1] Run `sbt compile-all`, then `sbt testCrypto` (Keccak256Spec), `sbt testVM`, and `sbt testComprehensive` (ethereum/tests — run BOTH the ETC-filtered AND ETH/Sepolia post-state-root sets, since `kec256` is chain-shared) for byte-for-byte parity; record `VERIFY: ran <cmd> — PASS|FAIL|DID NOT RUN` per suite (FR-005/SC-004/SC-005). **`testComprehensive` is the parity gate for the `deferred=true` / `Node.hashFn` import path (the `kec256` 3-arg form), which the StackTrie A/B replay (T004) does NOT traverse** — so this tier, not the A/B replay, covers that path's byte-for-byte output.
- [X] T012 [US1] `forge` byte-for-byte sign-off for the **ETC** keccak change — covering the LIVE Ethash/PoW + ommers sites (`consensus/pow/EthashUtils.scala:77/118`, `EthashBlockHeaderValidator.scala:63`, `EthashMiner.scala:67`, `validators/std/StdBlockValidator.scala:57` ommersHash), gated by `sbt testEthereum`. (NOT the dead `kec256PoW`.) (Constitution I gate.)
- [X] T012b [US1] **`beacon` byte-for-byte sign-off for the ETH/Sepolia PoS consumers** of the shared `kec256` helper — `EngineApiService.scala:522/608/613/682` (payload-id + block-hash) — backed by a named ETH/Sepolia post-state-root parity run (NOT ETC-filtered). Enforces FR-006's "ETH unaffected"; mandatory because keccak is chain-shared (Constitution Principle I + Workflow step 7). **This closes the CONST-01 blocker.** (Constitution I gate.)

**Checkpoint**: US1 is a complete, independently shippable increment (the MVP). Both `forge` (T012) AND `beacon` (T012b) sign-offs are required before merge. Could merge here.

---

## Phase 4: User Story 2 — Reduce StackTrie inline-merkleization scratch allocations (Priority: P2)

**Goal**: Reuse StackTrie transient scratch buffers (instance fields) while keeping the per-node final blob/`value` freshly owned.

**Independent test**: `testMPT` (StackTrie root == MerklePatriciaTrie oracle + per-node `kec256(blob)==hash` + ProofTrieInserter path) green; A/B replay (T004) zero-diff; benchmark shows reduced per-node allocation.

### Tests for User Story 2 (write first; extend the MPT oracle)

- [X] T013 [US2] Extend `src/test/scala/com/chipprbots/ethereum/mpt/StackTrieSpec.scala` — add fixed-seed larger corpora (50k and 1M sorted 32-byte keys, seed pinned), assert StackTrie root == `MerklePatriciaTrie` oracle root, add a per-emission assertion that every `(hash, blob)` satisfies `kec256(blob) == hash`, and cover the `ProofTrieInserter` insert/hash path (R8/INV-7). **ALSO build 2+ StackTries CONCURRENTLY on a fixed thread pool over distinct fixed-seed corpora** (`Future` + `Await`, no `Thread.sleep`) and assert each concurrently-built root matches its single-threaded oracle root — closing the concurrent-merkleization edge case and the buffer-state half of FR-007 (instance-field scratch reuse must not bleed across thread-confined tries).

### Implementation for User Story 2

- [X] T014 [US2] In `src/main/scala/com/chipprbots/ethereum/mpt/StackTrie.scala`, reuse copied-then-discarded transient scratch as INSTANCE fields (StackTrie is thread-confined): `encodeBranch` `refs` array (`:294`), `encodeBytes`/`listHeader`/`lengthAsBytes` intermediates (`:337+`), `appendNibble`/`appendNibbles` path buffers (`:494`/`:502`, reuse a path buffer — correct for both Hash and Path schemes), `sliceFrom`/`sliceRange` (`:472`/`:483`). **Keep the per-node final `blob`/`node.value` freshly owned** — never pool it (INV-5/R2; aliased into parent at `:329`/`:311`).
- [X] T015 [US2] (FR-010-gated) Slim the `new Array[StNode](16)` for leaf/ext nodes (`object StNode` ~`:426`) ONLY if the T016 profile shows it is material and escape analysis is not already eliding it; otherwise defer with a one-line note in the PR.

### Benchmark + verification for User Story 2

- [X] T016 [US2] Add the StackTrie-build benchmark method to `HotPathAllocBenchmark.scala` (100k and 1M sorted 32-byte leaves on the measuring thread); run and record per-node allocation delta vs baseline.
- [X] T017 [US2] Run `sbt testMPT` (StackTrieSpec), the A/B replay (T004) asserting zero root diffs, and `sbt testEthereum`; record `VERIFY` lines (FR-005/SC-004).
- [X] T018 [US2] `forge` byte-for-byte sign-off for the StackTrie scratch-reuse change (Constitution I gate). *(Forge-only is correct here: `StackTrie` inline merkleization is SNAP-sync-specific, and SNAP is ETC/Mordor-only — ETH/Sepolia syncs via Engine API, not SNAP — so this change touches no ETH consensus path. The chain-shared surface is the `kec256` helper, gated by `forge`+`beacon` at T012/T012b.)*

---

## Phase 5: User Story 3 — Opportunistic hot-path allocation hotspots (Priority: P3, each FR-010-gated)

**Goal**: Land each opportunistic win ONLY if it proves byte-for-byte parity AND a measurable allocation drop; otherwise drop it.

**Independent test**: per item — its parity check green + its benchmark shows reduced allocation.

- [X] T019 [P] [US3] Add `def kec256(input: Array[Byte]): Array[Byte]` overload in `crypto/.../package.scala` delegating to the 3-arg form (removes the per-call varargs `Seq`/`ArraySeq` wrapper for all single-`Array`-arg sites). ⚠️ This SILENTLY RE-DISPATCHES every existing single-`Array`-arg `kec256(x)` call. **Pin the verified Array-vs-ByteString split:** ByteString-arg sites (e.g. `EthashUtils.scala:77` `kec256(ByteString)`) are UNAFFECTED — they stay on `kec256(ByteString)` and are NOT re-bound. The `Array`-arg sites that RE-BIND to the new overload and MUST be byte-asserted are: `BlockGeneratorSkeleton:53`, `BlockHeader:130`, `GenesisDataLoader:224`, `EthSimulateService:113`, `SignedTransaction:572` & `:293`, `PrecompiledContracts:205`, `BlockPreparator:756/793`. For each of those re-binding sites, add an explicit overload-resolution check (e.g. `scalac -Vprint:typer`) confirming it binds to the intended `Array[Byte]` overload with identical bytes. Verification MUST run `sbt compile-all` + `testCrypto` + **`testEthereum` (block/ommers/payload roots) + a PoW/Ethash spec** + the T012b ETH parity run — NOT `testCrypto` alone (a Scala 3 overload-resolution surprise on a consensus path would change output and pass a crypto-only gate). (Addresses COV-02.)
- [X] T020 [P] [US3] Replace `RLP.encode`'s O(n²) `foldLeft(Array[Byte]())(_ ++ _)` (`rlp/src/main/scala/com/chipprbots/ethereum/rlp/RLP.scala:90`) with a single sized buffer/builder. This is on the `deferred=true` / block-import encode path (`MerklePatriciaTrie` + `Node.hashFn`), which the StackTrie-based A/B replay (T004) does NOT traverse — so its byte-for-byte gate is `sbt testRLP` **AND `sbt testEthereum`** (`BlockchainTests` post-state roots), not `testRLP` alone. (Addresses COV-03.)
- [X] T021 [US3] Elide `SnapHashTrie.emit`'s `blob.clone()` (`.../sync/snap/SnapHashTrie.scala:~94`) ONLY IF US2 establishes an owned (non-reused) emitted blob — and update the `StackTrie.scala:22` "reuses internal buffers" doc-contract in the SAME change (both or neither — Chesterton's fence); verify with testMPT + A/B replay.
- [X] T022 [US3] For each of T019–T021, confirm BOTH byte-for-byte parity AND a measured allocation reduction (FR-010), with a concrete per-item measurement: T019 → `getThreadAllocatedBytes` over a single-arg `kec256` loop (varargs `Seq`-wrapper bytes eliminated); T020 → allocation/time over an `RLP.encode` loop on a large list (O(n²)→O(n)); T021 → per-emitted-node `blob.clone()` bytes eliminated. DROP from scope any item that fails parity or shows no measurable win, and note it in the PR. (Addresses COV-06.)

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T023 [P] Record baseline-vs-post-change benchmark deltas in the PR body — confirm SC-001 (per-hash digest allocation ≈ 0), report SC-002 (bytes/GC reduction) and SC-003 (honest low single-digit to low-double-digit % throughput; state the real fix remains more CPU cores).
- [ ] T024 [P] Run `sbt pp` and `sbt formatCheck` (scalafmt 3.8.3 + scalafix) clean; confirm statement coverage stays ≥70%.
- [ ] T025 `forge` (ETC) AND `beacon` (ETH/Sepolia) FINAL byte-for-byte sign-off across the full corpus — `sbt testComprehensive` (ethereum/tests, BOTH ETC-filtered AND ETH/Sepolia post-state-root sets) + `sbt testVM` green with zero diffs; record the `VERIFY` line per chain (the authoritative SC-004/SC-005 gate). **T011 (US1) and T017 (US2) are the PER-STORY parity gates run at that story's own merge; T025 is the CONSOLIDATED final full-corpus gate, required only for the LAST-merged increment (or once when stories merge together)** — the 3h `testComprehensive` tier does NOT re-run redundantly at every story's merge.
- [ ] T026 PATCH version bump in `version.sbt` **per merged story** (US1 / US2 / US3 ship independently, so each merged increment carries its own bump); conventional `perf(crypto):`/`perf(snap):` commit referencing spec 007; open PR to `staging`; require CI green + BOTH `forge` and `beacon` review before merge. (No redeploy needed for correctness; benefits the next sync and all hosts.)

---

## Dependencies & Execution Order

- **Setup (T001–T002)** → **Foundational (T003–T004)** → **US1 (T005–T012)** → **US2 (T013–T018)** → **US3 (T019–T022)** → **Polish (T023–T026)**.
- **US1 is the MVP** and is independently shippable — it can merge on its own (smallest parity surface). US2 and US3 are additive increments.
- **US2** depends on the A/B replay harness (T004) for its parity gate.
- **US3-T021** (clone elision) depends on **US2** establishing owned-blob handoff; T019/T020 are independent of US2.
- Within US1: tests T005→T006→T007 are sequential (same file `Keccak256Spec.scala`); implementation T008→T009 follows; T011/T012 gate the phase.
- Within US2: T013 (test) → T014 (impl) → T016 (bench) → T017/T018 (gate).
- Every consensus sign-off is a hard gate — do not merge a story without it: `forge` (ETC) at T012/T018/T025 **and** `beacon` (ETH/Sepolia) at T012b/T025 (US1 changes the chain-shared `kec256` helper, so both chains' consensus output is in scope).

## Parallel Opportunities

- **Foundational**: T003 (`HotPathAllocBenchmark.scala`) ∥ T004 (A/B replay spec) — different new files.
- **US3**: T019 (`package.scala` overload) ∥ T020 (`RLP.scala`) — different files, independent.
- **Polish**: T023 (PR metrics) ∥ T024 (format/coverage) — independent.
- *Not parallel*: T005/T006/T007 (same `Keccak256Spec.scala`); anything touching `package.scala` (T008, T019) serializes; T021 after US2.

## Implementation Strategy (MVP first, incremental)

1. **MVP = US1** (T001–T012b): the keccak ThreadLocal reuse alone delivers the highest-frequency win across all sync/heal/import paths, with the smallest parity surface. Ship it first behind full parity + BOTH `forge` (ETC) and `beacon` (ETH/Sepolia) sign-off — keccak is chain-shared.
2. **US2** (T013–T018): StackTrie scratch reuse — gate behind the A/B replay; never pool the final blob.
3. **US3** (T019–T022): opportunistic, each independently dropped if it can't prove parity + a measured win (FR-010).
4. **Polish** (T023–T026): evidence, format, final forge gate, PR to `staging`.

> Honest expectation (carry into implementation): this is allocation/GC relief on a CPU-ceilinged host — low single-digit to low-double-digit % throughput, portable across all hosts. The step-change remains more CPU cores. Parity is the hard gate; perf is report-and-record.
