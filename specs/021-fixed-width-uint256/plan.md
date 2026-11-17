# Implementation Plan: Fixed-Width UInt256 (allocation-light EVM arithmetic, byte-identical)

**Branch**: `wt/021-plan` (plan); each slice is its own PR cut from `staging` | **Date**: 2026-11-07 |
**Spec**: [spec.md](./spec.md)

**Input**: [spec.md](./spec.md), its Clarifications of 2026-11-07 (binding: one end-state design in slices, no
measure-then-decide gate, D1–D4), GT-1 part A ([gt1-impact-analysis-part-a.md](./gt1-impact-analysis-part-a.md)) and
GT-1 part B (forge), added before GT-2. Plan author: `beacon` (ETH and `vm/` owner). `forge` reviewed the ETC side on
2026-11-07 (4 × SIGN WITH CHANGES, applied below as C1–C17); S4 awaits forge's re-check.

## Summary

This is one end-state design, byte-identical on every input and on both chains. It makes three replacements:
- `UInt256`'s `BigInt` becomes four `Long` limbs;
- the `List[UInt256]` EVM stack becomes an in-place word stack: an `Array[Long]`, 4 limbs per slot;
- the immutable, copy-on-store `Memory` becomes a mutable byte buffer per call frame (G-6, FR-012; folded in by the
  user on 2026-11-07).

It is delivered in **five slices**. Each lands as its own PR and passes its gates on its own:

| Slice | What | Size (rough, lines) |
|---|---|---|
| **S0** | Pins before the swap: codec goldens (forge S2), opcode-level rows O01–O15 with FR-010's invariants, forge's rows F01–F07 (every S7 vector, ETC SSTORE and pre-EIP-150 CALL rows, the Olympia opcodes, the opcode-table golden), trace goldens (structLog with memory capture off and on). Tests only | tests +1,400 |
| **S1** | The limb type behind the same API, with GT-2 (the `BigInt` oracle kept in the test tree, ≥ 10⁷ cases per operation) and the DC-3 usage check | main +1,000 / −210; tests +1,600; build, CI, script +150 |
| **S2** | The T-005 hot-path conversions in the GT-1 ranking, one commit each with its measurement (S7's unbounded size rule); S8 judged here | main +250 / −120; tests +300 |
| **S3** | The word stack and every opcode's stack data flow (DC-8: peek before pop, validate before mutate, tracer snapshots). W04–W06 also over the ETC (config, block) matrix. `Memory` is untouched | main +1,650 / −1,200; tests +1,400 / −900 |
| **S4** | Mutable `Memory` (FR-012): a per-frame buffer, owned copies only at escapes whose bytes are paid for, CALL input as a read-only view (no per-CALL copy), precompile output owned, region reads that never expand, the tracers' pre-step snapshot, `Int` offsets and word transfer, MCOPY as memmove. Its own GT-2 (a differential against today's immutable `Memory`) and GT-3 | main +800 / −450; tests +1,300 / −250 |
| **Total** | | main about +3,700 / −1,980; tests about +6,000 / −1,150; build, CI, script +150 |

The order is S0 → S1 → S2 → S3 → S4:
- S2 needs S1's members.
- S3 needs S2's allocation-free members (`isValidInt`, `isNegative`, `numberOfLeadingZeros`).
- S4 needs S3's stack for word transfer (`loadWordInto`, `storeWordFrom`) and the snapshot path in `VM.exec`.

Each memory call site is reworked once, in S4.

GT-3 (both chains, including the ETC mainnet head window, row 10b) gates every slice's merge. GT-4 measures every
slice and gates none, except forge's S8 budget:
- judged at S2's tip, on Mordor and the ETC head window;
- re-judged at S3's and S4's tips;
- backed by a release rule: no release with S1 and without S2, otherwise S1 is reverted.

The phase 0 decisions:
- **OQ-1**: limb fields, with one algorithm source shared with the stack through inline kernels.
- **OQ-2**: EXP by square-and-multiply mod 2²⁵⁶.
- **OQ-3**: storage stays `BigInt`, measured and handed to 018 Track C.
- **OQ-5**: no external vectors are needed; geth's BSD-3 `uint256` tables are an optional supplement after a licence
  check.
- **The word stack**: designed against DC-8.
- **Memory**: one buffer per frame, never pooled. It grows by 1.5× and materialises only what is written, so the lazy
  zero tail that `randomStatetest94` and `static_Call1MB1024Calldepth` need survives.
  - Owned copies are made only where gas pays for the escaped bytes: initcode, RETURN, REVERT, LOG.
  - CALL-family input is a read-only view while the caller is suspended, copied only if retained.
  - Precompile output is always owned.
  - Region reads never expand; expansion commits where today's code attaches the expanded memory.
  - MCOPY is a memmove. The pre-step snapshot is taken only when a tracer captures memory (research R-11).

The decisions are in [research.md](./research.md), the layouts in [data-model.md](./data-model.md), the surfaces in
[contracts/](./contracts/), and the run guide in [quickstart.md](./quickstart.md).

## Technical Context

**Language/Version**:
- Scala **3.9.0** with `-source:3.9` and the DC-4 warning ratchet (`-Wconf:id=E205:error`). Spec 020 has landed in
  code, PRs 3 to 7, the last #1722.
- JDK **25** (Temurin). **No Valhalla value objects**, so the value API allocates one flat object per result (48
  bytes), and only the word stack reaches "no per-opcode allocation from words".
- sbt 1.13.0.

**Primary Dependencies**:
- No new library.
- JDK intrinsics used: `Math.unsignedMultiplyHigh` (JDK 18+), `Long.compareUnsigned`, `Long.divideUnsigned` (an
  intrinsic since JDK 19), `Long.numberOfLeadingZeros`.
- Scala 3 `inline` methods with inline function parameters (research R-1).
- Test stack: ScalaTest, and ScalaCheck 1.19 through scalatestplus `scalacheck-1-18`.

**Packages touched**:
- `domain/UInt256.scala`, and a new `domain/UInt256Kernels.scala`.
- `vm/`:
  - `Stack` (S3), `Memory` (S4), `OpCode`, `ProgramState`, `VM`, `EvmConfig` (the `/` caller and `calcMemCost`),
    `package.scala` (`wordsForBytes`), `WorldStateProxy` (`getBalance`), `Program` (PUSH immediates);
  - the tracers: `StructLogTracer`, `VmTracer`, `PrestateTracer`, `AccessListTracer`, and the `ExecutionTracer`
    contract (S4 adds `readsPreStepMemory`).
- Not touched:
  - `ledger/` (including `BlockPreparator.scala`, the file shared with 018 Track C), `rlp`, `jsonrpc`, `network`,
    `consensus`, `mpt/`, `db/`. Their `UInt256` uses compile unchanged (NG-2);
  - the fork tables and dispatch (`forBlock`, `forTimestamp`, `EtcOlympiaOpCodes`, `OsakaOpCodes`,
    `AmsterdamOpCodes`; forge S5).

**Storage**: none. There is no on-disk or wire format change, so a revert needs no datadir action. The storage trie
keeps `BigInt` keys and values (NG-3, research R-8).

**Testing**:
- GT-2 is a ScalaCheck differential against the `BigInt` oracle (`domain.oracle.BigIntUInt256`, test tree). It runs
  as a fast `VMTest` suite (≥ 10⁴ cases per op) and a full suite (≥ 10⁷) under a new `DifferentialTest` tag.
- S4's GT-2 is a differential of generated memory operation sequences against `vm.oracle.ImmutableMemory`, today's
  `Memory` kept in the test tree: ≥ 10³ sequences fast, ≥ 10⁶ full.
- The named rows: E, C and O (part A), P (GT-1 part B), and L, W, M and F (this plan; F from forge's review).
- S0's goldens.
- GT-3: ethereum/tests on both network types, EEST Amsterdam, hive consensus, and the import differentials on Mordor,
  Ephemery, a post-Amsterdam range, Mordor from genesis, ETC 0–5.1M and the ETC mainnet head window (row 10b).

**Target Platform**: the JVM on Linux x86_64 and aarch64, on every network. One code path serves ETC, ETH and their
testnets.

**Project Type**: the root node module of the multi-module sbt build. No new module.

**Performance Goals** (measured, never asserted, DC-6):
- SC-003: zero `BigInteger` allocations on the arithmetic path, and allocation per opcode down by ≥ 3×. That target
  may be revised from the first profile; research R-14 estimates about 2.6× for an arithmetic opcode, because the
  `ProgramState` copy and `BigInt` gas remain.
- G-6 (S4): no O(memory) copy per MSTORE, MLOAD, MCOPY, copy opcode or RETURN. Escapes cost O(region).
- SC-004 and forge's S8: `[IMPORT-TIMING]` p50 and p99 within 10% on the fixed ranges.

**Constraints**:
- Byte-identical on both chains (DC-1, NG-1).
- The same API (DC-2), with `/` retired (D1).
- No `BigInt` on the arithmetic path, checked by usage (DC-3).
- A `final class`, not an opaque type (DC-4).
- Inside spec 019's subset (DC-5).
- No flag that outlives development. One code path for both chains, built to the end state: no staged or back-compat
  work.
- No sbt beside a running node.

**Scale/Scope**:
- 46 referencing files compile unchanged.
- 64 `stack.pop` sites and 22 `withError` sites are reworked in S3.
- 15 vm test files are migrated in S3.
- S4 reworks about 30 memory sites in `OpCode.scala`: 13 `calcMemCost` users keep their gas code, about 20
  `withMemory` uses go, and the 6 escaping loads move to `copyOut`. It also touches 2 tracers, and `MemorySpec` plus
  the opcode specs that compare memory states.
- The test tree's 709 `int2UInt256` sites and its cooperative `equals` are kept working (S6).

All NEEDS CLARIFICATION items are resolved (research.md). The open items are listed under **Decisions for review**.
They do not change this plan's structure.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Pre-design | Post-design |
|---|---|---|
| **I. Consensus determinism** | Engaged at its core: the type decides gas, state roots and receipts on both chains; memory decides LOG data, return data and deployed code. | **PASS by design.** <ul><li>GT-2 compares every member with the `BigInt` oracle; the exclusions are named and E29/E30 are unreachable from EVM paths.</li><li>S4's GT-2 compares memory with today's `Memory`, including escape isolation.</li><li>GT-3 runs on both chains before every merge, and the header is the oracle.</li><li>The before-code reviews RP-1 to RP-5 come first, then a forge (ETC) and beacon (ETH) sign-off per slice.</li><li>ETC and ETH paths stay separate in configuration: no fork-table, `forBlock` or `forTimestamp` change.</li><li>The shared type is one code path by the user's rule, not a mixing of chain logic.</li></ul> |
| **II. Spec-driven** | PASS: spec, GT-1 and Clarifications precede this plan. | PASS. Mutable memory entered by a user decision recorded in the spec (Clarifications, G-6, FR-012), not by the plan. Deviations are recorded for review rather than taken silently: R-7 (a conditional `given Ordering`), R-10 (stack capacity growth). |
| **III. Tests and coverage** | Engaged: the differential, the goldens, the test migration. | PASS. Every slice's first commit is tests that pass on the pre-slice tree, and those tests are unchanged after it. Fixed seeds. No `Thread.sleep`. The kernels reach 100% from GT-2. Coverage ≥ 70% holds (the new code is covered). ethereum/tests on both chains. |
| **IV. Formatted Scala 3** | Engaged: `inline`, `final class`, `derives CanEqual`. | PASS. Scala 3.9 only; `scalafmtAll` and `formatCheck` green; explicit result types; no `return`; mithril reviews DC-4's shape at RP-2. |
| **V. Quality gates** | Engaged: a new CI step and a new tag. | PASS. The DC-3 check is a step inside `ci.yml`'s existing job, with no new required context. `DifferentialTest` is excluded from Tiers 1 and 2 and run as a CI step. Tier 1 stays under its budget. No gate is lowered. |
| **VI. Security** | Low: no crypto, key or network-surface change. | PASS. GT-4's trace profiling binds JSON-RPC to `localhost` only, on datadir copies. |
| **VII. Versioning and ADRs** | Engaged: an architecturally significant consensus change. | PASS. S1 adds an ADR under `docs/adr/vm/` (next free number) recording the representation, the kernel sharing and the word stack; S3 and S4 amend it. Conventional commits; PATCH bumps per merge. The release rule: no release, image or deployment from `staging` with S1 and without S2, otherwise S1 is reverted (S8, C6). |

**Declared gaps (recorded, not waived).**
1. **ETC mainnet blocks between 5,100,000 and the head window**, including the Atlantis, Agharta, Phoenix, Magneto,
   Mystique and Spiral transitions on mainnet. Their rules are covered by rows 7 to 10b (research R-13). forge signed
   it on 2026-11-07. Either closer ends it, and no slice waits for either:
   - 018 EG-12;
   - a state-free strict replay of a fukuii-fc export from 0 to at least 19,260,000 (Spiral + 10,000), run once at S3's
     tip.

   The ETC head itself **is** covered: ETC mainnet is synced on fukuii-fc (pivot 25,688,690, root verified), and row 10b
   imports a head window into a copy of that state.
2. SC-003's 3× may need the revision the spec allows (research R-14).

## Gates

| Gate | Condition | When | Evidence |
|---|---|---|---|
| GT-1 | impact analysis signed | done 2026-11-06; conditions FR-009 to FR-011 are carried into S0 to S3. Memory has no separate GT-1: research R-11 does that analysis for S4 from part A §3 and the code, and RP-5 reviews it | part A; GT-1 part B (forge), added before GT-2; research R-11 |
| RP-1 | forge and beacon sign the S0 golden list, the O rows and forge's rows F01–F07 (C10, C12, C13, C15, C16, C17) | before S0's tests are written | data-model §9. **Signed.** beacon: the whole S0 list (the codec goldens, O01–O15, F01–F07), as its author (this plan, 2026-11-07). forge: F01–F07 (forge plan review 2026-11-07 (re-check), R3: "SIGN as written"); the codec goldens (its own condition S2, mapped to S0's `UInt256GoldenBytesSpec` in that review's §6, with C1–C17 re-checked "as intended" in R4); O01–O15 as a set (forge, T010 review 2026-11-07: "SIGN". The chain-neutral rows run on ETH Osaka and ETC Spiral; the ETH-only rows are covered on ETC by F05 and F06, or are not reachable there; O12's vectors equal EIP-1014's examples) |
| RP-2 | beacon and forge sign the limb rules (data-model §1–§3), research R-2 to R-7 and GT-2's exclusions; mithril signs the DC-4 shape. The P rows need GT-1 part B (forge), added before GT-2 | before S1's swap commit | data-model, research |
| RP-3 | forge signs the S7 rewrite (`calcMemCost` in today's order with the exact sentinel, C9; `wordsForBytes`' `Long` threshold n < 2⁶³, C11) and the SLOAD/SSTORE conversions once per method (C12); beacon signs JUMP, BLOBHASH, CLZ, SAR and the tracers | before S2's code | the S2 commit list below |
| RP-4 | beacon presents three tables: the 64 pop sites (peek or consume), the 22 `withError` sites (the stack each returns) and the tracer contract. forge signs the ETC-only paths: pre-EIP-150 CALL gas read unbounded through `peek` (C13), the pre-Phoenix SSTORE path, the Olympia opcodes, and W04–W06 over the ETC (config, block) matrix (C14) | before S3's code | contracts/word-stack.md §1–§6 |
| RP-5 | beacon presents three tables and an audit. **The memory escape table**: per site, view (in-step, or for the duration of `vm.call`) or owned copy, and **what pays for each owned copy's bytes** (R-11.3 (c); forge R1-c). **The memory path table**: for every memory-touching path, error paths included, the `size` and bytes it leaves today (Rule M5), on the ETH forks and the pre-Atlantis ETC eras (R1-b). **The tracer capability** (`readsPreStepMemory`). **The `toArrayUnsafe` audit** (Rule M7). forge signs the ETC paths: pre-Byzantium CALL failures expanding in and out, Frontier and Homestead CREATE, LOG data into pre-Atlantis receipts | before S4's code | contracts/memory.md, research R-11 |
| GT-2 | 0 differences, ≥ 10⁷ per op plus every named row; for S4, ≥ 10⁶ memory sequences plus M01–M15 | before S1, S2, S3 and S4 merge (S3 adds the stack-vs-value kernel differential; S4 adds the memory differential) | quickstart §2, §2b; research §B rows 1–3, 14 |
| GT-3 | rows 1–10b of research R-13 green; forge signs ETC, beacon ETH | before S1, S2, S3 and S4 merge | quickstart §4, §B row 13 |
| GT-4 | recorded at every slice tip, including row 10's wall time (C8). **S8 is a gate**: judged at S2's tip on Mordor and the ETC head window, re-judged at S3's tip (C5, C7) and at S4's | every slice | quickstart §5, §B rows 4–12, 15 |

## Slices

Every slice is one PR from `staging`, in order. Within a slice:
1. The first commit is **tests that pass on the pre-slice tree**.
2. Then the code commits, each compiling (`compile-all`).
3. Then format-only commits, if any.

Consensus code follows bucket C of the risk-stratified commit protocol: it is never batched with cleanup.

### S0: pins before the swap (tests only)

**Tests (the whole slice).**
- `UInt256GoldenBytesSpec`: forge S2's codec goldens and P07–P09 (data-model §9). The bytes are literals captured from
  the pre-swap tree and cross-checked against an independent source where one exists (the RLP rules, the core-geth
  CREATE vector already pinned in `UInt256RlpEncodingSpec`).
- `OpCodeEdgeRowsSpec`: O01–O15 and FR-010's truncation invariants:
  - JUMP `isValidInt` (2³²+d does not alias d);
  - BLOBHASH bounds (2³² is out of range);
  - `sliceBytes` min-before-truncate;
  - CALL's requested gas (2⁶⁴+5 is not 5);
  - RETURNDATACOPY saturation.
- forge's S0 rows F01–F07 (data-model §9), in `OpCodeEdgeRowsSpec` and its ETC siblings:
  - F01 and F02: all of S7's vectors for `calcMemCost`, at their exact values including the sentinel 2²⁵⁵−1, and for
    `wordsForBytes` (C10);
  - F03: SSTORE on ETC configs below Phoenix, at Phoenix and at Magneto (C12);
  - F04: pre-EIP-150 CALL gas on an ETC Homestead config (C13);
  - F05: the Olympia opcodes on the ETC table (C15);
  - F06: the opcode-table golden that gives forge's S5 a test (C17);
  - F07: the "state-gas delta is zero" contract over the ETC (config, block) matrix (C16).
- `TraceGoldenSpec`: `debug_trace*` structLog (memory capture off **and on**), callTracer and prestateTracer JSON, and
  `trace_*` vmTrace JSON, over a frozen fixture set that includes error steps:
  - OOG, InvalidJump, underflow;
  - RETURNDATACOPY overflow;
  - CREATE's initcode limit and its preflight OOG;
  - the EIP-8037 shortfall on a CALL;
  - a CALL that fails without revert, and one that reverts.

  These pin DC-8 for S2 and S3, and FR-012's tracer view for S4.
- `BlockchainTestBench` gains an allocated-bytes column (test tree), so GT-4 has a baseline.

**Gate**: green on `staging`, with the GT-4 baseline recorded at S0's tip (§B, column one), including row 10's wall
time with jar A (C8). **Reviewers**: RP-1 (forge, beacon), eye.

### S1: the limb type behind the same API (FR-001, FR-002, FR-004 half, FR-009)

**Tests first** (commit 1, green against the `BigInt` type):
- the oracle copy `domain/oracle/BigIntUInt256.scala`;
- `UInt256DifferentialSpec` (fast) and `UInt256DifferentialFullSpec` (full, `DifferentialTest`), with E01–E35,
  C01–C12, P01–P06, P10–P16 and L01–L09;
- `UInt256Spec`'s gaps found by GT-1:
  - direct tests for `<<`, `>>`, `sshift`, `toLong`, `toHexString`, `toString`, `hashCode`, the non-`UInt256` `equals`,
    `max`, `apply(Boolean)`, negative `apply(Long)` and `byte2UInt256`;
  - a `smod` property;
  - SDIV(−2²⁵⁵, −1);
  - its `"/"` test, which exercises `*`, is replaced;
- `build.sbt` adds `-l DifferentialTest` to the `testEssential` and `testStandard` aliases, and a CI step runs the full
  suite.

**Code.**
1. D1: retire `/`. `EvmConfig`'s sentinel becomes a constant built with `div` (P15).
2. The swap:
   - `domain/UInt256.scala` on four limbs, and `domain/UInt256Kernels.scala` (research R-1 to R-6);
   - the `lazy val`s go;
   - the shift members take EVM semantics for every shift (D2);
   - `toByte` is added (FR-009);
   - `hashCode` stays equal to `BigInt.hashCode` and the cooperative `equals` is kept (S6);
   - the `given Ordering` is added only if it resolves cleanly (R-7);
   - `apply(BigInt)` masking and `apply(Long)` sign extension are preserved exactly (S3).
3. The DC-3 check, `domain/` half: the fences, `scripts/spec021/check_uint256_bigint.sh` with its self-test, and a
   step in `ci.yml` (R-15).
4. The ADR under `docs/adr/vm/`.

**Gate**:
- GT-2 full, 0 differences; coverage of the kernels 100%; the `javap` check (R-1, with its fallback decided here);
- S0's pins unchanged;
- `testEssential` once;
- GT-3 rows 1–10b;
- GT-4 recorded, including S1's `[IMPORT-TIMING]`. S8 is not judged yet.

**Release rule** (forge C6). No release tag, image or operator deployment is cut from a `staging` commit that has S1
without S2. If S2 cannot meet S8 before the next release cut, S1 is reverted under the rollback rule. It does not wait
on `staging`.

**Reviewers**: RP-2 before the swap; forge and beacon sign GT-3; mithril (DC-4); eye; wraith if compile fallout
appears.

### S2: the T-005 hot-path conversions (FR-006, FR-010; forge S7, S8)

**Tests first**: rows for each new member (`isValidInt`, `isNegative`, `numberOfLeadingZeros`, `toHexQuantity`) in
both GT-2 suites, at ≥ 10⁷ cases each. S0's O and F rows and its trace goldens already pin the call sites, with every
S7 vector in S0 (C10).

**Code**: one commit per item, in the GT-1 ranking, each with its bench measurement in the commit message:
1. `calcMemCost` typed on `UInt256` (S7, forge C9). Today's order is kept exactly:
   1. size zero, tested on the full word → `memNeeded = 0`;
   2. otherwise, if offset or size ≥ 2³¹ → the sentinel;
   3. otherwise `memNeeded = offset + size` in `Long` (both operands are known to be below 2³¹), and
      `memNeeded > 2³¹ − 1` → the sentinel;
   4. `memNeeded <= memSize` → 0;
   5. otherwise `c(memNeeded) − c(memSize)`.

   The sentinel is a **value**, the exact `BigInt` 2²⁵⁵ − 1, not just an OutOfGas trigger: structLog prints it as
   `gasCost`. P15, F01 and every O07 vector assert it exactly. It is never a wrapping `UInt256` `+`: F01's
   (2³¹, 2²⁵⁶ − 2³¹) wraps to exactly 0.
2. SLOAD and SSTORE (C12): convert `offset` and `newValue` to `BigInt` **once per method**. `baseGas`/`varGas` and
   `exec` are separate calls, so the counts become 2 per SLOAD and 4 per SSTORE. That one `BigInt` goes to `load`,
   `store`, `StorageKey`, `addressAndKey` and `BlockAccessRecorder.slot`. NG-3 is intact, and the reverse conversions
   stay as written. F03 pins the ETC paths.
3. JUMP and JUMPI: `isJumpDestination` uses `isValidInt` instead of `toBigInt.isValidInt` (O04).
4. `wordsForBytes(UInt256)`, unbounded (C11):
   - the `Long` path iff w1 = w2 = w3 = 0 and w0 ≥ 0 signed, i.e. n < 2⁶³;
   - everything else takes the `BigInt` path.

   F02's 2⁶³ − 1 and 2⁶³ pin the boundary.
5. `WorldStateProxy.getBalance`: `UInt256(a.balance)` becomes `a.balance` (P16).
6. StructLogTracer: render the stack with `toHexQuantity` (and VmTracer's `exPush` the same way), with the trace
   goldens unchanged. The stale "toSeq = underlying.reverse" comment is corrected in the same lines (part A §5a).
7. Remove `BigInt` from the arithmetic opcodes:
   - CLZ uses `numberOfLeadingZeros`;
   - SAR uses `isNegative`, and `toSign` is retired;
   - BLOBHASH bounds use `isValidInt` (O05);
   - MSTORE8 already resolves to the `toByte` member.
8. The DC-3 check, opcode half: the arithmetic fence in `vm/OpCode.scala`.

**Gate**:
- GT-2; S0's pins unchanged; `testEssential` once; GT-3 rows 1–10b;
- **S8 judged here** (C5): A = pre-S1, B = S2 tip, within 10% on p50 and p99 (quickstart §5.3), on **both** the
  Mordor fixed range and the ETC head window (row 10b);
- the storage-boundary conversion counts recorded for OQ-3 (§B rows 9 and 10).

**Reviewers**: RP-3 before code; forge and beacon sign GT-3; eye.

### S3: the word stack and the opcode stack data flow (FR-007, DC-7, DC-8)

**Tests first** (commit 1, green on S2's tip):
- `StackSpec` rewritten against its `Vector` model through the new API's surface (W08–W10);
- W02 and W03;
- W04 as an `OpCodeContractSpec` extension;
- W05, from RP-4's 22-site table;
- W06, with TLOAD, TSTORE and MCOPY added to its list (C15);
- W07;
- W01 in `Push*Spec`;
- **W04, W05 and W06 run over the ETC (config, block) matrix** (C14): the ETC chain config at one block in each era
  (below 1.15M; 1.15M–2.5M; 2.5M–8.772M; Atlantis; Agharta; Phoenix; Magneto; Mystique; Spiral; Olympia at a test
  block), as well as the ETH builders. SSTORE's path and the EIP-2929 costs dispatch on the header's block number, not
  on the `EvmConfig` builder.
- W04's generator draws full-range words (GT-2's anchors), not only `stackGen`'s small values.

The trace goldens from S0 cover the user-visible output.

**Code** (commits in order):
1. **Read-only accessors** (contracts/word-stack.md §1) on the current `Stack`. Every pop-as-peek site of RP-4's table
   moves to them. CALL's gas word is read with `peek(i).toBigInt` or an unbounded compare, never with `int31At` or a
   `toLong` (C13, F04). There is no representation change yet: the accessors are end-state API, and this commit keeps
   step 2's diff reviewable.
2. **The swap**:
   - `vm/Stack.scala` on the word array, with mutators returning `Unit`;
   - `ProgramState` sharing semantics;
   - `VM.exec` snapshots for tracers (contract §5);
   - `OpCode.execute`'s checkpoint and rollback (§6);
   - every `exec` reworked to Rule V;
   - the 15 vm test files migrated to `Stack.of` and `snapshot()`;
   - the `ExecutionTracer` doc contract.
3. **In-place arithmetic**:
   - `UnaryOp`, `BinaryOp`, `TernaryOp`, `ShiftingOp`, SAR, CLZ, BYTE, SIGNEXTEND, EXP, DIV/MOD/ADDMOD/MULMOD through
     `UInt256Kernels` on the stack's slots;
   - PUSH1..PUSH32 write their immediate bytes straight into the slot (W01);
   - DUP, SWAP and EXCHANGE copy limbs.
4. The DC-3 fence extended over the in-place region, and the `javap` check over `vm/Stack*` and the arithmetic
   opcodes.

`Memory` is not touched in S3. Its opcodes keep its `UInt256`-typed API, reading offsets with `peek` or `pop`. S4
changes memory's interface and representation together, so each memory site is reworked once.

**Gate**:
- GT-2, plus a **stack-vs-value differential**: each in-place kernel against the `UInt256` member on the same
  generators, ≥ 10⁷ per op;
- S0's pins unchanged, the trace goldens above all;
- `testEssential` once;
- GT-3 rows 1–10b;
- **S8 re-judged** (C7): A = pre-S1, B = S3 tip, on Mordor and the ETC head window. A breach blocks S3's merge,
  because the per-frame array is an ETC cost: pre-EIP-150 ETC makes 1024-deep recursion cheap;
- GT-4 recorded, including SC-003 and the residual shares (§B rows 4–12).

**Reviewers**: RP-4 before step 1; forge and beacon sign GT-3; eye.

### S4: mutable `Memory` (G-6, FR-012)

**Tests first** (commit 1, green on S3's tip, against today's immutable `Memory`):
- the oracle copy `vm/oracle/ImmutableMemory.scala`;
- `MemoryDifferentialSpec` (fast, `VMTest`) and `MemoryDifferentialFullSpec` (full, `DifferentialTest`), over
  generated operation sequences (research R-12, "S4's memory differential");
- rows M01–M15 (data-model §9), at the `Memory` and opcode levels;
- M06's escape-isolation rows for LOG (including a pre-Atlantis ETC config), RETURN, REVERT and CREATE initcode;
- M10 from RP-5's path table, and M10 and M13 over the pre-Atlantis ETC eras (forge R1-b);
- M15, the CALL-input view (forge R1-a):
  - (a) identity precompile output owned;
  - (b) retained input owned under internal-tx tracing and under a tracer;
  - (c) the bench case where allocated bytes per CALL must not depend on the input size;
- M11 through the S0 trace goldens with memory capture on;
- M07 and M08 as bench runs under the 512 MB heap (quickstart §2b).

**Code** (commits in order):
1. **Commit order at the memory sites, on the immutable `Memory`.** Every memory-touching opcode is restructured to
   check, then read (reads that never expand), then commit (`expand` or write at the points where today's code
   attaches the expanded memory: Rule M5). Offsets become `Int` after the gas check (Rules M1–M3).
   - It adds an `Int`-typed API on the current class.
   - This is a no-result-change refactor, reviewable on its own, and S4's tests stay green.
2. **The swap**:
   - `vm/Memory.scala` becomes the per-frame buffer (data-model §8): 1.5× growth, the lazy zero tail, the zero-page
     views kept;
   - `copyOut` at the escapes whose bytes are paid for (Rule M4);
   - CALL-family input as a read-only `view`, with an owned copy only where it is retained, and precompile output
     copied in the VM's precompile-call wrapper (Rule M7);
   - `ProgramState.memory` sharing semantics;
   - `ExecutionTracer.readsPreStepMemory`, with `VM.exec`'s memory snapshot (contract §4);
   - about 20 `withMemory` uses removed;
   - `MemorySpec` and the opcode specs that compare memory states migrated to `snapshot()`;
   - the `UInt256`-typed `Memory` methods removed with their last caller.
3. **In-place data flow**:
   - MLOAD, MSTORE and MSTORE8 as word transfer between the buffer and stack slots;
   - MCOPY as `move` (memmove, EIP-5656);
   - the copy opcodes through `writePadded` (the overlap, then written zeros);
   - SHA3 through `hashRegion`;
   - CALL's output written in place.

**Gate**:
- the memory differential (full), 0 differences, ≥ 10⁶ sequences plus M01–M15;
- the heap bounds M07 and M08 (§B row 15);
- M15 (c): allocated bytes per CALL independent of the input size (§B row 16). A dependence fails S4, because S8's
  ordinary traffic cannot see this case;
- the UInt256 GT-2 suites still green;
- S0's pins unchanged, the trace goldens with memory capture above all;
- `testEssential` once;
- GT-3 rows 1–10b. LOG data reaches receipts roots, RETURN data reaches deployed code, and CALL input reaches child
  frames, so GT-3 is the consumer-side oracle;
- **S8 re-judged** at S4's tip, on the same two ranges as S3 (one `Memory` object per frame is also a per-frame cost);
- GT-4 recorded: the memory-dense fixture, trace re-execution with memory capture, §B rows 4, 6 and 12.

**Reviewers**: RP-5 before code (beacon presents; forge signs the ETC paths); forge and beacon sign GT-3; eye.

**Size**: main about +800 / −450 (`Memory.scala` about 150 → 350; about 30 `OpCode.scala` sites; `VM`,
`ExecutionTracer`, `StructLogTracer`, `VmTracer` and `ProgramState` small); tests about +1,300 / −250 (the oracle, the
differential, the M rows, the migrations).

### After S4

- The final GT-3 and GT-4 figures go into research.md §B.
- The ETC declared gap closes when either closer in research R-13 has run. Neither blocks any slice.
- 018 SC-016 is notified that trace re-execution can be re-measured.
- Spec 019 FR-003b is marked satisfied when its subset checker runs green over `domain/UInt256*` (FR-008, SC-005).
- This spec's status moves to `Done` by the lifecycle procedure.

**Sequencing.**
- Code slices start after 0.11.0, beside 018 Track C (spec, Sequence). S0 is tests only and may land earlier.
- 021 does not edit `ledger/BlockPreparator.scala`, so no per-PR ordering with Track C is needed. If S2's measurements
  ever point there, that commit is sequenced with Track C first.

## Project Structure

### Documentation (this feature)

```text
specs/021-fixed-width-uint256/
├── spec.md                          # Clarified 2026-11-07; status Planned
├── gt1-impact-analysis-part-a.md    # GT-1, beacon
├── plan.md                          # this file
├── research.md                      # Phase 0: R-1…R-17, §B measurements
├── data-model.md                    # Phase 1: limbs, kernels, 512-bit ops, word stack, views, named rows
├── quickstart.md                    # Phase 1: GT-2, GT-3, GT-4 run guide
├── contracts/
│   ├── uint256-api.md               # the public API kept, added and removed
│   ├── word-stack.md                # Stack, ProgramState, VM/tracer, OpCode.execute (S3)
│   └── memory.md                    # the per-frame mutable Memory, escapes, commit points, tracer view (S4)
├── gt1-impact-analysis-part-b.md    # GT-1 part B (forge), added before GT-2 (tasks T008)
├── rp4-stack-tables.md              # RP-4's pop-site, withError and tracer tables (tasks T059)
├── rp5-memory-tables.md             # RP-5's escape, path and toArrayUnsafe tables (tasks T081)
├── checklists/                      # existing
└── tasks.md                         # /speckit-tasks: 104 tasks in 7 phases
```

### Source (repository root): files each slice touches

```text
# S0 (tests only)
src/test/scala/com/chipprbots/ethereum/domain/UInt256GoldenBytesSpec.scala      # new
src/test/scala/com/chipprbots/ethereum/vm/EtcForkMatrix.scala                   # new: the ETC (config, block) matrix (C14)
src/test/scala/com/chipprbots/ethereum/vm/OpCodeEdgeRowsSpec.scala              # new: O rows, F01, F02
src/test/scala/com/chipprbots/ethereum/vm/EtcOpCodeEdgeRowsSpec.scala           # new: F03–F05 on ETC configs
src/test/scala/com/chipprbots/ethereum/vm/OpCodeTableGoldenSpec.scala           # new: F06 (forge S5)
src/test/scala/com/chipprbots/ethereum/vm/OpCodeContractSpec.scala              # F07: state-gas zero over the ETC matrix
src/test/scala/com/chipprbots/ethereum/vm/TraceGoldenSpec.scala                 # new, + src/test/resources/trace-goldens/
src/it/scala/com/chipprbots/ethereum/ethtest/BlockchainTestBench.scala          # allocated-bytes column

# S1
src/main/scala/com/chipprbots/ethereum/domain/UInt256.scala                     # rewritten on limbs
src/main/scala/com/chipprbots/ethereum/domain/UInt256Kernels.scala              # new
src/main/scala/com/chipprbots/ethereum/vm/EvmConfig.scala                       # the `/` caller (D1)
src/test/scala/com/chipprbots/ethereum/domain/oracle/BigIntUInt256.scala        # new: the oracle, verbatim
src/test/scala/com/chipprbots/ethereum/domain/UInt256Differential*Spec.scala    # new: GT-2, fast and full
src/test/scala/com/chipprbots/ethereum/domain/UInt256Spec.scala                 # gaps filled
src/test/scala/com/chipprbots/ethereum/testing/Tags.scala                       # DifferentialTest
build.sbt; .github/workflows/ci.yml                                             # tag exclusions; check and full-suite steps
scripts/spec021/check_uint256_bigint.sh (+ self-test)                           # new (R-15)
docs/adr/vm/VM-NNN-fixed-width-uint256.md (+ index rows)                        # new ADR

# S2
src/main/scala/com/chipprbots/ethereum/vm/{EvmConfig,package,OpCode,WorldStateProxy,StructLogTracer,VmTracer}.scala
src/main/scala/com/chipprbots/ethereum/domain/UInt256.scala                     # isValidInt, isNegative, numberOfLeadingZeros, toHexQuantity; toSign retired

# S3
src/main/scala/com/chipprbots/ethereum/vm/{Stack,OpCode,ProgramState,VM,Program,ExecutionTracer}.scala
src/main/scala/com/chipprbots/ethereum/vm/{StructLogTracer,VmTracer,PrestateTracer,AccessListTracer}.scala
src/test/scala/com/chipprbots/ethereum/vm/  # StackSpec, Generators, OpCodeFunSpec, OpCodeGasSpec, OpCodeGasSpecPostEip2929,
                                            # ShiftingOpCodeSpec, Push0Spec, CreateOpcodeSpec, CallOpFixture, OpCodeContractSpec,
                                            # LargeMemoryExpansionSpec, BlockAccessRecordingSpec, AmsterdamOpCodesSpec,
                                            # AmsterdamCodeReadSurchargeSpec, StructLogTracerSpec

# S4
src/main/scala/com/chipprbots/ethereum/vm/{Memory,OpCode,ProgramState,VM,ExecutionTracer,StructLogTracer,VmTracer}.scala
src/test/scala/com/chipprbots/ethereum/vm/oracle/ImmutableMemory.scala          # new: the memory oracle, verbatim
src/test/scala/com/chipprbots/ethereum/vm/MemoryDifferential*Spec.scala         # new: S4's GT-2, fast and full
src/test/scala/com/chipprbots/ethereum/vm/  # MemorySpec, OpCodeFunSpec, OpCodeGasSpec, LargeMemoryExpansionSpec, CallOpFixture,
                                            # CreateOpcodeSpec, OlympiaMcopySpec, StructLogTracerSpec (memory-state comparisons)
```

**Structure Decision**: no new module or package. There is one new main file, `UInt256Kernels.scala` (S1). The word
stack replaces `Stack.scala` in place (S3), and the buffer replaces `Memory.scala` in place (S4). The two oracles live
only in the test tree.

## Risks and rollback

| # | Risk | Mitigation | Detected by |
|---|---|---|---|
| 1 | A limb bug: carry, borrow, signed versus unsigned compare, the `>>> 64` trap, Algorithm D's correction or add-back | generator strata (R-12), L01–L09, 100% kernel coverage | GT-2 |
| 2 | `toBigInt` comes out negative, silently changing storage-trie keys | the unsigned constructor; every GT-2 word asserts `signum >= 0` | GT-2 (S1), GT-3 |
| 3 | The hot-path conversions now allocate, and import time regresses (S8) | S2 in the GT-1 ranking; the release rule (no release with S1 and without S2, otherwise revert S1) | GT-4 at S2, re-judged at S3 and S4, on Mordor and the ETC head window |
| 4 | A truncation regression in an S2, S3 or S4 rewrite (JUMP, BLOBHASH, `sliceBytes`, CALL gas, RETURNDATACOPY, memory size, the `calcMemCost` order and sentinel) | O rows and every S7 vector (F01, F02) landed in S0; Rules M1–M3 | S0's pins |
| 5 | An in-place aliasing bug: a gas function mutating, a tracer seeing the post-step stack, an error path's stack, a shared DUP | Rules P and V; snapshots; checkpoint; mutators return `Unit`; RP-4's tables; W04–W06 over the ETC matrix | W04–W07, the trace goldens |
| 5b | A memory escape that shares the buffer and is retained, so LOG data, return data, deployed code or RETURNDATA (via the identity precompile) changes after the fact: a receipt-root or code-hash divergence | Rules M4 and M7 (paid escapes copied; CALL input a view only for the call; precompile output owned), no pool, RP-5's escape table and `toArrayUnsafe` audit | M06, M15 (a, b), GT-3 (receipts and state roots) |
| 5f | A copy where nothing pays per byte, e.g. CALL input copied on every CALL: about 15 GB copied by one ETC block, a liveness DoS (forge R1-a) | Rule M4's "copy only what is paid for"; RP-5 lists what pays for each owned copy | M15 (c) |
| 5c | The lazy zero tail lost, so `randomStatetest94` or `static_Call1MB1024Calldepth` runs out of memory | only writes materialise; zero-page views in `copyOut` | M07, M08 under 512 MB, the differential's heap check |
| 5d | A memory failure path that expands or writes when today's does not, or the reverse | Rule M5, commit order refactored on the immutable class first (S4 step 1), RP-5's path table | M10, M13, the trace goldens with memory capture |
| 5e | Opcode tables changed by S3's rewrite of `OpCode.scala` (forge S5) | the tables are not edited | F06's golden |
| 6 | Scala does not beta-reduce the inline continuation, so closures allocate | the `javap` check | S1 (fallback: array kernels, R-1) |
| 7 | `given Ordering` is ambiguous under `-source:3.9` | added only if it compiles cleanly (R-7) | compile, at S1 |
| 8 | Test churn (709 `int2UInt256` sites, the cooperative `equals`) | the implicits and the cooperative branch are kept (DC-2, S6) | compile |
| 9 | ETC mainnet coverage gap between 5,100,000 and the head window | declared, signed by forge; the rules are covered by rows 7–10b; closed by EG-12 or a state-free replay to 19,260,000 (R-13) | sign-off |
| 10 | GT-2's runtime in CI | two suites, a tag, measured at S1 | §B row 1 |

**Rollback.**
- One PR per slice, so a rollback is `git revert` of that PR. S2's items can be reverted individually.
- Reverts go in reverse order (S4, then S3, then S2, then S1), because each later slice builds on the earlier one's
  members. S4 can be reverted alone: it depends on S3, and nothing depends on it.
- S0's tests stay, since they are true on both sides.
- There is no flag to flip and none to remove later.
- No on-disk, wire or RPC format changes, so a revert needs no datadir or client action.
- **The release rule** (C6) makes one revert mandatory: if S2 cannot meet S8 before the next release cut, S1 is
  reverted.

## Decisions for review

### Resolved 2026-11-07

1. **Mutable `Memory`** is in this spec as slice S4 (user decision; spec Clarifications, G-6, FR-012).
2. **ETC mainnet coverage** (forge, review of 2026-11-07, C1–C4):
   - sign S4 (the ETC oracle condition) with the gap recorded, and do **not** hold S3;
   - add the ETC head window (P-6, row 10b) from S1 on.
3. **S8** (forge C5–C7): judged at S2's tip on Mordor and the ETC head window, and re-judged at S3's tip. Also
   re-judged at S4's tip, which forge confirmed in its re-check. The release rule makes "promoted together"
   enforceable.
4. **forge's re-check** (2026-11-07). S8 and F01–F07 are signed. S4 is SIGN WITH CHANGES, applied:
   - CALL input as a view, not copied per CALL;
   - precompile output owned;
   - M15;
   - M10 and M13 over the pre-Atlantis ETC eras;
   - RP-5 tables that state view or owned copy and what pays;
   - MLOAD's expansion wording aligned.

   C1–C17 gain R4-a: row 10b is run once with jar A before it gates S1 (quickstart P-6).

### Still open

These are surfaced rather than decided silently. None of them changes the slice structure.

5. **Stack capacity (beacon's call, flagged).** DC-7's "4×1024 limbs" is read as the layout and the bound; the array
   grows from 16 words instead of preallocating 32 KiB per frame (R-10).
6. **Residual per-step allocations (user).** The `ProgramState` copy and `BigInt` gas remain (R-17). SC-003's 3× may
   be revised from the first profile, as the spec allows. A frame-state and gas-as-`Long` redesign is a separate spec
   if the user wants the interpreter loop at zero allocation.

## Complexity Tracking

| Choice | Why needed | Simpler alternative rejected because |
|---|---|---|
| Inline kernels with inline continuations | one algorithm per operation for both the value and the word stack, with no closures and no boxing (R-1) | two implementations could diverge; an array inside every value doubles the objects per result |
| The `BigInt` oracle kept in the test tree | GT-2 needs today's behaviour after `main` loses it (spec Assumptions) | vectors alone cannot reach 10⁷ cases per operation |
| Two GT-2 suites and a new tag | ≥ 10⁷ cases per operation must not blow Tier 1 or 2 (R-12) | one suite is either too slow for Tier 1 or too thin for SC-001 |
| The EIP-8037 checkpoint in `OpCode.execute` | the one path that fails after `exec` has mutated must still show the pre-instruction stack to `trace_*` (R-10 (c)) | reordering the state-gas check before `exec` is impossible: it needs `exec`'s gas |
| Fenced regions for the DC-3 check | a usage check needs scope; an import check is vacuous (spec DC-3) | scalameta-based analysis is more tooling than a grep with fences |
| Two escape kinds: owned copies where the bytes are paid for, and a read-only view for CALL input only | the buffer is mutable and escaped bytes reach receipts and code, yet CALL's gas does not pay per input byte (R-11.3 (c), forge R1-a) | copying everything is a DoS through CALL; sharing everything needs a proof per consumer that no reviewer can keep true. The view's single use is bounded by `vm.call` |
| `ExecutionTracer.readsPreStepMemory` | a pre-step memory copy only when a tracer captures memory (R-11.3 (h)) | always snapshotting costs O(memory) per traced step; copy-on-write adds a branch to every write on the import path |
| The ETC (config, block) matrix for W04–W06 and F07 | SSTORE's path and the access costs dispatch on the header's block number, not the builder (C14) | the builders alone miss the pre-Phoenix and pre-Magneto paths |

**Agent context.** The plan template's `CLAUDE.md` plan-reference update and the optional `after_plan`
`speckit.agent-context.update` hook were not run, because this session's brief limits writes to this directory and the
spec index. `CLAUDE.md`'s "current plan" pointer stays on spec 022.
