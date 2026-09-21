# Implementation Plan: Amsterdam Hard Fork Support (ETH-family)

**Branch**: `claude/ci-gate-verification-spec-37nmn9` | **Date**: 2026-09-21 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/009-amsterdam-fork-support/spec.md` · Tracking issue #1409

> **Status**: Phase 0 research in flight. Sections marked **[PENDING Phase 0]** depend on closing
> FR-017's gas arithmetic and will be completed from `research.md`. Everything else is settled.

## Summary

fukuii cannot import hive's `devp2p` fixture chain past block 36, the first Amsterdam block. The node
stalls at head 35 of ~89 and advertises a head peers reject, which is why 27 of that suite's 34
failures are `wrong head block in status`. fukuii has no Amsterdam support at all.

The work splits into three independently shippable P1 slices plus one P2. The header-decoder slice
fixes live corruption today and has no dependency on any Amsterdam gas work, so it lands first. The
gas work is gated behind closing the arithmetic in Phase 0 — the spec makes that a completion gate
(FR-017), not a footnote.

## Technical Context

**Language/Version**: Scala 3.3.8 LTS, JDK 25, sbt 1.10.7

**Primary Dependencies**: no new runtime dependencies anticipated. Existing: Pekko, Cats Effect,
BouncyCastle, RocksDB.

**Storage**: N/A — no schema change. State trie contents change only in that Amsterdam blocks execute
under different gas rules; no storage-layer work.

**Testing**: ScalaTest; tiers `testEssential` (PR gate) / `testStandard` / `testComprehensive`.
Consensus validation additionally against ethereum/tests — **see the gate gap under Constitution
Check**.

**Target Platform**: ETH-family chains only (ETH mainnet, Sepolia, hive fixtures). ETC/Mordor/Gorgoroth
explicitly out of scope and must be unaffected.

**Project Type**: Single-project JVM consensus client.

**Performance Goals**: none specific. This is a correctness feature. The state-gas dimension adds
per-frame bookkeeping; it must not regress block-import throughput materially, but no target is set.

**Constraints**:
- Byte-for-byte consensus determinism (Constitution I). Gas figures, state roots and block hashes must
  match the reference client exactly.
- ETC behaviour byte-identical. Enforced by omission: `ForkTimestamps` fields are `Option[Long] = None`
  and no ETC config sets them, so `isAmsterdamTimestamp` is false on every ETC block.
- No `Thread.sleep` in tests (Constitution III).

**Scale/Scope**: ~8 EIPs. One of them (EIP-8037) restructures gas accounting rather than adding
constants; the rest are repricing, header fields, or system-contract wiring.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Assessment |
|---|---|
| **I. Consensus Determinism (NON-NEGOTIABLE)** | **Directly engaged.** Every slice touches gas, header encoding or state transition. Routing: `beacon` owns ETH implementation; `forge` signs off that ETC is untouched. Per Principle I, ETH dispatch uses timestamp gating — this feature extends the existing `EvmConfig.forBlock(blockNumber, timestamp, config)` cascade and must not touch `forBlock(blockNumber, config)` or ETC block-number paths. |
| **II. Spec-Driven Development** | Satisfied. Spec written and validated before planning; artifacts under `specs/009-amsterdam-fork-support/`. |
| **III. Test Discipline & Tiered Coverage** | Satisfied in form, **with a declared gap** — see below. Deterministic tests, no `Thread.sleep`, ≥70% statement coverage. |
| **IV. Idiomatic Scala 3** | No tension. `scalafmt` + `scalafix` apply as usual. |
| **V. Quality Gates Are Mandatory** | No tension. `sbt pp` pre-PR; CI green to merge. No test may be weakened to pass. |
| **VI. Security & Operational Safety** | No new external surface. Two new system-contract addresses are fixed constants from the EIP. |
| **VII. Transparent Versioning** | Consensus-affecting for ETH-family chains; needs a version note and changelog entry at release. |

### Declared gate gap — recorded, not waived

Constitution III requires consensus-critical changes to be *"validated against ethereum/tests and
confirmation that state roots, gas, and hashes are unchanged versus the reference."*

**Only 12 JSON fixtures (~452 KB) ship in `src/it/resources/ethereum-tests`.** The full
GeneralStateTests / BlockchainTests corpus is not present in this environment. A 15-test pass there is
a smoke check, not byte-exact compliance, and it would be dishonest to present it as satisfying
Principle III for a change of this size.

Mitigations, in order of strength:
1. **The hive fixture itself is the strongest available oracle** — ~600 blocks with per-block `gasUsed`
   and state roots produced by the reference client. Reproducing its Amsterdam-era figures exactly is a
   harder test than the 12 local fixtures.
2. `headstate.json` gives the reference client's final state for 48 accounts — a direct state-level
   comparison, not just a root match.
3. If the full corpus can be fetched in CI, the Amsterdam subset should be added to the
   `testComprehensive` tier. **Open question for the user** — not a blocker for planning.

This gap is not introduced by this feature; it is pre-existing and equally affects every consensus
change. It is recorded here because a plan that silently relies on a weak gate is the exact pattern
this repository's CI-integrity work exists to eliminate.

### Complexity note

EIP-8037's two-dimensional gas reservoir is a genuine structural change to `ProgramState`, not a
constant swap. That is inherent to the EIP, not an architectural choice this plan is making, so it is
not a Constitution violation requiring justification in Complexity Tracking. It does drive the
sequencing decision below.

## Project Structure

### Documentation (this feature)

```text
specs/009-amsterdam-fork-support/
├── plan.md              # This file
├── research.md          # Phase 0 output — IN FLIGHT
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
├── checklists/
│   └── requirements.md  # Spec quality checklist (complete, 16/16)
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

Anticipated touch points, to be confirmed against Phase 0 findings:

```text
src/main/scala/com/chipprbots/ethereum/
├── utils/
│   └── BlockchainConfig.scala        # ForkTimestamps + amsterdamTimestamp; isAmsterdamTimestamp
├── vm/
│   ├── EvmConfig.scala               # timestamp cascade: Amsterdam branch
│   ├── FeeSchedule (in EvmConfig)    # AmsterdamFeeSchedule — EIP-8038 / 2780 constants
│   ├── ProgramState.scala            # [PENDING Phase 0] state-gas dimension (EIP-8037)
│   ├── ProgramContext.scala          # [PENDING Phase 0] reservoir plumbing
│   ├── ProgramResult.scala           # [PENDING Phase 0] reservoir plumbing
│   └── OpCode.scala                  # [PENDING Phase 0] per-opcode state-gas charges
├── domain/
│   └── BlockHeader.scala             # HefPostAmsterdam (23 fields); strict field-count decoding
├── ledger/
│   ├── BlockPreparator.scala         # intrinsic gas decomposition (EIP-2780)
│   └── BlockExecution.scala          # EIP-8282 builder system calls; requestsHash contribution
└── consensus/validators/
    └── std/StdValidators.scala       # block gas accounting (EIP-7778); BAL validation (EIP-7928)

src/main/resources/conf/base/chains/
├── eth-chain.conf                    # amsterdam-timestamp (when scheduled)
├── sepolia-chain.conf                # amsterdam-timestamp (when scheduled)
└── hive-chain.conf                   # amsterdam-timestamp, set from HIVE_AMSTERDAM_TIMESTAMP

hive/fukuii/fukuii.sh                 # map HIVE_AMSTERDAM_TIMESTAMP -> amsterdam-timestamp
```

**Structure Decision**: Single project, existing layout. No new modules. The work follows the
established timestamp-fork pattern already used for Shanghai, Cancun, Prague and Osaka, which keeps
ETC safe by omission rather than by an explicit guard.

## Sequencing — three P1 slices, landed separately

The spec deliberately assigns P1 to three stories because each is independently shippable. This plan
commits to landing them as **separate bucket-C commits**, not one change.

### Slice 1 — Strict header field-count decoding (User Story 2)

**Lands first. No dependency on any Amsterdam work.**

`BlockHeader.scala` currently matches `case n if n >= 21` and discards items beyond the 21st.
Re-encoding emits 21 fields, so a 23-field header hashes differently from canonical — measured
`6372c88f…` versus `94844dfd…`. Wrong parent linkage, wrong `BLOCKHASH`, wrong wire responses: silent
corruption where a loud failure belongs.

Fixing this **does not** make devp2p progress — block 36 still fails, just earlier and more honestly.
It buys correctness and a clear error, which is worth landing on its own.

Chesterton's Fence: the `>= 21` catch-all was presumably written to be forward-tolerant of unknown
forks. That intent is wrong in a consensus client — tolerance here means computing a wrong hash rather
than refusing a block it cannot represent. The replacement must reject, not truncate.

**Risk**: low. Reject-unknown is strictly safer than truncate-silently.

### Slice 2 — Activation plumbing + non-structural repricing

`amsterdamTimestamp` on `ForkTimestamps`, `isAmsterdamTimestamp`, the `EvmConfig` cascade branch, an
`AmsterdamFeeSchedule`, EIP-2780 intrinsic decomposition, EIP-7954 code-size limits, and the two new
header fields as a `HefPostAmsterdam` variant.

**[PENDING Phase 0]** — whether EIP-2780/8038 can land without EIP-8037/7778 depends on the research
outcome. If the gas dimensions are entangled, slices 2 and 3 merge.

**Risk**: medium. Touches the shared `EvmConfig` cascade. ETC safety rests on omission; a
config-matrix test asserting no ETC chain declares an Amsterdam activation is required, mirroring the
one added for `olympiaGasLimitElasticity`.

### Slice 3 — EIP-8037 state-gas reservoir + EIP-7778 block accounting

The structural change: a second gas dimension on `ProgramState` with LIFO refills and frame-local
rollback baselines, plus revised block-level accounting.

**[PENDING Phase 0]** — the precise model is exactly what Phase 0 must establish. Implementation must
not begin until the two fixture figures are derived.

**Risk**: high. This is the slice that can silently mis-meter every Amsterdam transaction.

### Slice 4 — EIP-7928 BAL validation + EIP-8282 builder requests (P2)

Block-level access list computation and validation; the two builder system contracts and their
contribution to `requestsHash`. Their system calls must not count against the block gas limit — the
same rule fukuii already implements correctly for EIP-7002/7251, verified during diagnosis.

## Test Strategy

Per slice, the test that fails before and passes after:

| Slice | Decisive test |
|---|---|
| 1 | A header with an unrecognised field count is rejected; round-trip decode/encode reproduces canonical bytes and hash for every supported fork |
| 2 | Intrinsic cost: value transfer to existing EOA unchanged; zero-value call to contract cheaper by the derived delta. Config matrix: no ETC chain declares Amsterdam |
| 3 | The fixture's Amsterdam gas figures reproduced exactly, including the two currently-unexplained ones (FR-017) |
| 4 | BAL commitment matches; builder request commitment matches; builder system calls excluded from block gas |

**ETC regression guard, every slice**: `SpiralToOlympiaGasTransitionSpec`,
`OlympiaBlockHeaderValidationSpec`, `OlympiaGasLimitSpec`, `GasLimitCalculationSpec` must stay green
**with no assertion changes**. If any goes red, the change is wrong and the specs are right
(Constitution V). Pin the Amsterdam activation to `None` explicitly in those fixtures rather than
inheriting a parse default — the same hardening applied to the elasticity work, so the ETC assumption
is stated at the assertion site.

**Non-regression**: rpc-compat (currently 40/247) and graphql (currently 2/52) must not move. Neither
chain declares an Amsterdam activation, so Amsterdam code must be provably inert for them. Both are
also the regression oracle for slice 3, since both reach Cancun/Prague with substantial contract
activity.

**No numeric prediction** is made for devp2p's post-fix failure count. The chain must import fully
before the `wrong head block in status` failures can move, and how far they then move is not derivable
in advance.

## Phase 0: Research — IN FLIGHT

Open items, all assigned:

1. **Derive 183,600** (`tx-calltree` Amsterdam total) from named constants and normative clauses.
2. **Derive 100,000** (four `tx-emit-*` variants collapsing to their gas limit).
3. **Establish the gas model**: relationship between execution-gas and state-gas dimensions; what the
   header's `gasUsed` contains under EIP-7778; refill, refund and frame-revert semantics.
4. **EIP dependency ordering**: can 2780/8038 land without 8037/7778, or are they atomic?
5. **Blast radius** per EIP, flagging anything shared with the ETC path.

Method constraint: reproduce against the decoded fixture, not from EIP text alone. Four diagnoses in
the preceding effort were wrong from reasoning about source without checking what the harness emits;
the two that held came from decoding the fixture.

**Output**: `research.md` with each figure either closed by arithmetic or explicitly declared open.

## Phase 1: Design & Contracts — after Phase 0

- `data-model.md`: `ForkTimestamps` extension, `HefPostAmsterdam` header variant, the state-gas
  dimension's shape, builder request types.
- `contracts/`: header RLP field ordering for 23-field headers; the two builder system-contract
  addresses and their invocation contract.
- `quickstart.md`: how to validate — import the fixture chain past block 36, confirm head ~89, confirm
  rpc-compat and graphql unmoved.
- Update `CLAUDE.md`'s SPECKIT plan reference to this file.

## Complexity Tracking

> No Constitution violations requiring justification.

EIP-8037's reservoir model is structural, but it is imposed by the specification being implemented,
not by a design choice in this plan. The mitigation is sequencing — it lands as its own slice, behind
closed arithmetic, with the two non-activating suites as regression oracles.
