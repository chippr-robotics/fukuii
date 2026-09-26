# Implementation Plan: Amsterdam Hard Fork Support (ETH-family)

**Branch**: `claude/ci-gate-verification-spec-37nmn9` | **Date**: 2026-09-21 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/009-amsterdam-fork-support/spec.md` · Tracking issue #1409

> **Status**: Phase 0 research in flight. Sections marked **[PENDING Phase 0]** depend on closing
> FR-017's gas arithmetic and will be completed from `research.md`. Everything else is settled.

## Summary

fukuii cannot import hive's `devp2p` fixture chain past block 36, the first Amsterdam block. The node
stalls at head 35 of ~89 and advertises a head peers reject, which is why 27 of that suite's 34
failures are `wrong head block in status`. fukuii has no Amsterdam support at all.

The header-decoder slice fixes live corruption today, has no dependency on any Amsterdam gas work,
and lands first. **Phase 0 is complete** (`research.md`): both FR-017 figures are closed against the
decoded fixture, and the answer changed this plan's sequencing. The provisional split of the gas work
into two slices does not survive — EIP-2780, 8037, 8038 and 7778 are numerically interdependent and
land as one atomic slice. Phase 0 also found a missing EIP (value-transfer logs), now added to the
spec as FR-019.

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

## Sequencing — four slices, landed separately

**Revised by Phase 0.** The spec assigns P1 to three stories on the judgement that each is
independently shippable. Research confirmed that for the header work (slice A) and refuted it for the
gas work: the two gas slices are numerically interdependent and merge into one atomic slice B. What
follows is the corrected sequence, landed as **separate bucket-C commits**, not one change.

### Slice A — Strict header field-count decoding (User Story 2)

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

### Slice B — the atomic gas change (was slices 2 and 3)

`amsterdamTimestamp` on `ForkTimestamps`, `isAmsterdamTimestamp`, the `EvmConfig` cascade branch, an
`AmsterdamFeeSchedule`, EIP-2780 intrinsic decomposition, EIP-8038 state-access repricing, EIP-8037's
state-gas dimension, EIP-7778 block accounting, EIP-7954 code-size limits, and EIP-7708 value-transfer
logs.

**Phase 0 merged the two slices.** They cannot be sequenced independently and the evidence is
arithmetic, not stylistic: block 41's header figure is only reproducible with 8037's per-dimension
maximum, 8038's creation-access price and 2780's decomposed intrinsic *simultaneously* — no
two-of-three gives it. Beyond that, 2780's parameter table imports 8038's values and 8037's products,
so 2780 alone is not numerically defined; 2780 moves account creation out of intrinsic gas into a
runtime charge that must land in 8037's state dimension, so 2780 without 8037 leaves account creation
uncharged; and 8038 explicitly defers two of its constants to 8037, so 8038 alone produces a pricing
state that exists in no client. Landing them separately would ship two intermediate states that are
wrong by construction.

EIP-7708 joins this slice rather than slice C because it is inseparable from the intrinsic
value-transfer charge that prices it, and because without it every receipt root and bloom after
activation is wrong — it is not deferrable behind the gas work, it *is* part of it.

**Shape of the change** (from `research.md`, and the reason this is not a constant swap):
`ProgramState` gains frame-local state-gas counters with save/restore on frame entry and exit;
`ProgramContext`'s starting gas becomes a two-dimensional split; and the block-level result must carry
**two** block counters plus a **separate** receipt counter, because the header reports a maximum while
receipts report a sum. An implementation carrying one scalar will fail one of them. The constant
tables are the easy part and should land last within the slice.

**Risk**: high, and now concentrated rather than spread. This is the slice that can silently mis-meter
every Amsterdam transaction. Two mitigations: the non-activating suites (rpc-compat, graphql) are
regression oracles that must not move at all, and a config-matrix test must assert no ETC chain
declares an Amsterdam activation, mirroring the one added for `olympiaGasLimitElasticity`.

**Known blind spot — carried from `research.md` R-1.** Every transaction in the fixture has a gas
limit far below `TX_MAX_GAS_LIMIT`, so the state-gas reservoir is empty throughout and the fixture
never exercises reservoir seeding, cross-frame passing, LIFO refill ordering, or the successful-child
merge. Fixture-green does not close this slice. Those paths need `execution-spec-tests`
`tests/amsterdam/` vectors, which compounds the declared gate gap above.

### Slice C — EIP-7928 block-level access lists

Block-level access list computation and validation against the header commitment.

**Sequencing dependency on slice B is real, not cosmetic** (`research.md` R-2): EIP-8037's creation
charge is conditional on an ordering that EIP-7928 defines — the computed destination must not be read
until after the sender-balance, nonce-overflow and call-depth checks. Implementing 8037's creation
charge without that ordering gives wrong answers for creations that fail their pre-checks, and the
fixture does not cover that path. The ordering must be implemented in B even though the commitment
itself lands in C.

### Slice D — EIP-8282 builder requests (P2)

The two builder system contracts and their contribution to `requestsHash`. Their system calls must not count against the block gas limit — the
same rule fukuii already implements correctly for EIP-7002/7251, verified during diagnosis.

**Open before this slice starts** (`research.md` R-6): EIP-8282 has not been read. Two Amsterdam
blocks carry gas totals that are exact multiples of the storage-set state charge, which is measured,
but attributing those to specific builder queue slots is inference and must be closed first.

## Test Strategy

Per slice, the test that fails before and passes after:

| Slice | Decisive test |
|---|---|
| A | A header with an unrecognised field count is rejected; round-trip decode/encode reproduces canonical bytes and hash for every supported fork |
| B | Intrinsic cost: transfer to an existing EOA unchanged at 21,000, self-transfer at 12,000, zero-value contract call cheaper by the derived delta. **Header/receipt divergence**: block 41 must produce header `gasUsed` 183,600 *and* receipt `cumulativeGasUsed` 326,947 — a single-counter implementation fails this and passes nothing weaker. **Measured OOG**: the four `tx-emit-*` variants must each halt with status 0, empty bloom, zero logs and 100,000 consumed. **Transfer logs**: post-activation transfer blocks must reproduce their blooms, which are non-empty where the pre-activation ones are empty. Config matrix: no ETC chain declares Amsterdam |
| C | BAL commitment matches; creation pre-check ordering gives the right answer for a creation that fails its pre-checks (not fixture-covered — needs a written vector) |
| D | Builder request commitment matches; builder system calls excluded from block gas |

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

## Phase 0: Research — COMPLETE

Output: [`research.md`](./research.md). All five open items closed.

| # | Item | Outcome |
|---|---|---|
| 1 | Derive 183,600 (`tx-calltree`) | **Closed.** The header reports `max(execution, state)`, not a total. State gas is one term; execution gas 143,347 reproduced term-by-term from the pre-fork baseline |
| 2 | Derive 100,000 (four `tx-emit-*`) | **Closed, and measured rather than derived.** Out-of-gas: status 0, empty bloom, zero logs recovered byte-exactly from the receipt trie, with the contract's counter frozen at the pre-fork invocation count |
| 3 | Establish the gas model | **Closed.** Two block counters plus a separate receipt counter; reservoir empty below `TX_MAX_GAS_LIMIT`; state charges restore to baseline on exceptional halt |
| 4 | EIP dependency ordering | **Closed — atomic.** Drove the slice merge above |
| 5 | Blast radius | **Closed.** Shared-signature surface enumerated in `research.md`; `forge` sign-off required before any of it is edited |

The method constraint held: the machinery (RLP decoder, keccak, single-entry MPT root, receipt and
bloom construction) was validated against known pre-Amsterdam blocks *before* being trusted on
Amsterdam ones, and every figure above is marked in `research.md` as measured, derived, or inferred.
Two findings — the missing EIP and the slice merge — would not have surfaced from EIP text alone,
which is the point.

**Research changed the plan.** Both corrections are carried above rather than noted and ignored:
slices 2 and 3 are merged into B, and EIP-7708 is added to B and to the spec as FR-019.

## Phase 1: Design & Contracts — COMPLETE

| Artifact | Contents |
|---|---|
| [`data-model.md`](./data-model.md) | `ForkTimestamps.amsterdamTimestamp`; the `EvmConfig` cascade branch; `HefPostAmsterdam` and why the existing decoder is wrong; `BlockResult`'s three counters; `ProgramState`'s five state-gas fields; the `calcTransactionIntrinsicGas` signature change and its five call sites; transfer logs; builder requests |
| [`contracts/header-rlp.md`](./contracts/header-rlp.md) | The 23-field ordering, measured by re-encoding block 600 to a byte-identical hash; the exact-arity decode table; the round-trip invariant |
| [`contracts/gas-accounting.md`](./contracts/gas-accounting.md) | The `max()` rule and reservoir split; five conformance vectors V1–V5 from the fixture; an explicit list of what the fixture does **not** cover |
| [`quickstart.md`](./quickstart.md) | Per-slice run commands, the two non-regression oracles, the ETC guard suites, and the reporting convention |

`CLAUDE.md`'s SPECKIT plan reference now points here.

### Constitution re-check, post-design

| Principle | Post-design assessment |
|---|---|
| **I. Consensus Determinism** | **Still the dominant risk, now better bounded.** Design isolates the ETC exposure to shared *signatures* rather than fork gates — the gates are `Option`-typed and absent in every ETC config, while `calcTransactionIntrinsicGas`, `OpCode`, `ProgramState` and `BlockPreparator` are genuinely shared. `research.md` enumerates that surface and every item goes to `forge` before it is edited. |
| **III. Test Discipline** | **The declared gate gap got worse, and is stated rather than smoothed over.** R-1 establishes that the fixture cannot exercise the state-gas reservoir at all, so the strongest oracle available is weaker than it looked when the gap was first recorded. `contracts/gas-accounting.md` lists the uncovered paths explicitly so nobody reads fixture-green as compliance. Unchanged as a blocker: it is pre-existing and affects every consensus change here. |
| **V. Quality Gates** | No change. No test may be weakened; the four ETC regression suites must pass with no assertion edits. |
| Others | Unchanged from the pre-Phase-0 assessment. |

**No new Constitution violations.** The one thing design made worse — the strength of the available
oracle — is a fact about the fixture, not a choice this plan made, and it is now recorded in three
places rather than one.

## Complexity Tracking

> No Constitution violations requiring justification.

EIP-8037's reservoir model is structural, but it is imposed by the specification being implemented,
not by a design choice in this plan. The mitigation is sequencing — it lands as its own slice, behind
closed arithmetic, with the two non-activating suites as regression oracles.
