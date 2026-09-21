# Feature Specification: Amsterdam Hard Fork Support (ETH-family)

**Feature Branch**: `009-amsterdam-fork-support`

**Created**: 2026-09-21

**Status**: Draft

**Input**: User description: "Amsterdam hard fork support for ETH-family chains (ETH/Sepolia/hive), timestamp-gated, with zero change to ETC/Mordor behaviour." (full description retained in `research-input.md` context below)

## Context: Why Now

This is not a speculative fork-readiness exercise. It is the current, measured blocker on an existing test suite.

hive's `devp2p` fixture chain (go-ethereum `cmd/devp2p/internal/ethtest/testdata`, ~600 blocks, 10-second block spacing) declares `amsterdamTime: 360`. Block 36's timestamp is exactly 360. The node cannot import it:

```
Chain import: block 36 failed — post-execution validation failed:
  ValidationAfterExecError(Block has invalid gas used, ...)
Chain import: block 37..89 failed — UNKNOWN_PARENT   (cascade)
```

The node therefore advertises head 35 of ~89. **27 of devp2p's 34 current failures are `peering failed: status exchange failed: wrong head block in status`** — peers reject a node whose head is wrong. Those are a consequence of truncation, not 27 independent networking defects.

The chain reached block 36 only after two consensus fixes landed earlier in the same effort (EIP-1559 activation-block elasticity, then EIP-4788 and EIP-6780). Each removed one wall and exposed the next. Amsterdam is the current wall.

**fukuii has no Amsterdam support of any kind.** Searching the main source tree for "amsterdam" returns three results, all comments at `EngineApiController.scala:143-145` reading *"When Amsterdam is later defined"*.

### Evidence the fork identification is correct

Three independent confirmations, all measured against the fixture rather than inferred:

1. **Genesis declares it.** `amsterdamTime: 360`; block 36's timestamp is 360.
2. **The header grows exactly there.** Header RLP field count jumps 21 → 23 at block 36 and stays 23. Per go-ethereum `core/types/block.go:103-107` the two new items are `BlockAccessListHash` (EIP-7928) and `SlotNumber` (EIP-7843).
3. **Two exact gas matches.** `tx-callrevert` drops 23,201 → 17,201 across the boundary, which is precisely EIP-2780's `TX_BASE_COST 12,000 + COLD_ACCOUNT_ACCESS 3,000 + 16 calldata + 2,100 cold SLOAD + 85`. `tx-calltree` lands on 183,600, which is EIP-8037's `STATE_BYTES_PER_NEW_ACCOUNT 120 × CPSB 1,530` verbatim. The second match also pins `COLD_ACCOUNT_ACCESS = 3,000`, since `12,000 + 3,000 = 15,000` only holds if EIP-8038 raised it from 2,600.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Import an Amsterdam chain without stalling (Priority: P1)

An operator runs fukuii against an ETH-family network whose fork schedule has reached Amsterdam. The node imports blocks across the Amsterdam activation boundary and continues to the chain head, rather than halting at the first Amsterdam block and silently serving a stale head to peers.

**Why this priority**: Everything else depends on it. A node that stops at the activation block is not merely failing tests — it is advertising a head that peers reject, which makes it useless as a network participant. This single capability unblocks the majority of the currently failing devp2p cases.

**Independent Test**: Point the node at the devp2p fixture chain and confirm import proceeds past block 36 to head ~89 with no `ValidationAfterExecError`. Delivers value on its own: the node syncs.

**Acceptance Scenarios**:

1. **Given** a chain whose genesis declares an Amsterdam activation timestamp, **When** the node imports the first block at or after that timestamp, **Then** the block validates and import continues.
2. **Given** an imported Amsterdam chain, **When** a peer performs a status exchange, **Then** the advertised head matches the canonical head and peering succeeds.
3. **Given** a chain that does **not** declare an Amsterdam activation, **When** the node imports it, **Then** behaviour is identical to before this feature existed.

---

### User Story 2 - Reject unrecognised block headers instead of corrupting them (Priority: P1)

A node encountering a block header with more fields than it understands refuses it with a clear error, rather than discarding the extra fields and computing a wrong block hash.

**Why this priority**: Also P1, and independently valuable — it is a live correctness defect that exists today regardless of Amsterdam. Header decoding currently matches "21 or more fields" and drops anything beyond the 21st. Re-encoding then emits 21 fields, so a 23-field header yields a **different block hash** than the canonical one (measured: canonical `6372c88f…`, truncated `94844dfd…`). That means wrong parent linkage, wrong `BLOCKHASH` results, and wrong responses on the wire — a silent corruption rather than a visible failure.

This violates the project's standing rule against silent fallbacks that turn hard failures into quiet corruption.

**Independent Test**: Feed the node a header with an unrecognised field count and confirm it is rejected with a diagnostic naming the count, with no block admitted. Testable and shippable before any Amsterdam gas work exists.

**Acceptance Scenarios**:

1. **Given** a header with more fields than any known fork produces, **When** it is decoded, **Then** it is rejected with an error identifying the unexpected field count.
2. **Given** a header with a field count matching a known fork, **When** it is decoded and re-encoded, **Then** the resulting hash is byte-identical to the canonical hash.

---

### User Story 3 - Preserve Ethereum Classic behaviour exactly (Priority: P1)

An operator running an ETC or Mordor node observes no behavioural change whatsoever from this feature.

**Why this priority**: P1 because it is a correctness constraint on everything else, not an enhancement. ETC will not adopt the Amsterdam repricing EIPs. Any leakage of Amsterdam rules onto an ETC chain is a consensus split — the most severe failure this project can produce, and irreversible once mined.

**Independent Test**: Run the existing ETC consensus regression suites unchanged and confirm they pass with no assertion modifications, and confirm no ETC chain configuration declares an Amsterdam activation.

**Acceptance Scenarios**:

1. **Given** an ETC or Mordor chain configuration, **When** any block is validated or executed, **Then** gas costs, intrinsic transaction cost, header field count, and state transition are identical to the pre-feature behaviour.
2. **Given** the established ETC regression suites, **When** they run against the changed code, **Then** every one passes with no assertion or expected-value changes.

---

### User Story 4 - Serve and validate the new Amsterdam block data (Priority: P2)

A node participating in an Amsterdam network correctly handles the block-level access list and slot number carried in each header, and the builder deposit and exit request types contributed by the new system contracts.

**Why this priority**: P2 because the chain must import first (P1) before this data is meaningfully exercised. Required for full conformance but not for the initial unblocking.

**Independent Test**: Validate an Amsterdam block whose header carries a block-level access list hash and confirm the computed value matches; process a block containing builder deposit and exit requests and confirm the aggregate request commitment matches the header.

**Acceptance Scenarios**:

1. **Given** an Amsterdam block, **When** its block-level access list commitment is recomputed, **Then** it matches the header's value.
2. **Given** a block containing builder deposit or exit requests, **When** the request commitment is computed, **Then** it matches the header's value.
3. **Given** the builder system contract invocations at block boundaries, **When** block gas is accounted, **Then** those invocations do not count against the block gas limit.

---

### Edge Cases

- **Activation boundary**: the first block at or after the activation timestamp uses the new rules; its parent does not. Both must validate.
- **Activation at genesis**: a chain declaring the activation at timestamp 0 must apply the new rules from the first block, with no transition special-case.
- **Never-activating chains**: a chain that declares no Amsterdam activation must behave exactly as before, with the new code paths unreachable.
- **Transactions that changed price in both directions**: simple value transfers to existing accounts must stay at the old intrinsic cost while contract calls get cheaper and state-creating calls get more expensive. A single blanket change in either direction is wrong.
- **Transactions that newly run out of gas**: some transactions valid before the fork exceed their gas limit after the repricing. The node must treat these as ordinary out-of-gas outcomes, not as validation errors.
- **Unknown future header shapes**: a header with a field count beyond Amsterdam's must be rejected, not truncated (User Story 2).
- **Refund accounting**: the storage-clearing refund changes value; refund caps must be applied against the new figure.

## Requirements *(mandatory)*

### Functional Requirements

**Activation and gating**

- **FR-001**: The system MUST support declaring an Amsterdam activation point per chain, expressed as a timestamp, consistent with how other post-merge forks are declared.
- **FR-002**: The system MUST apply Amsterdam rules to a block if and only if that block's timestamp is at or after the declared activation, and the chain declares one.
- **FR-003**: Chains that declare no Amsterdam activation MUST be unaffected in every observable respect.
- **FR-004**: Ethereum Classic chain configurations MUST NOT declare an Amsterdam activation, and Amsterdam rules MUST NOT be reachable from block-number-gated fork dispatch.

**Transaction and execution pricing**

- **FR-005**: Intrinsic transaction cost MUST be decomposed into its constituent resource charges rather than a single flat figure, such that a value transfer to an existing externally-owned account costs the same as before, while a zero-value call to a contract costs less.
- **FR-006**: State-access charges (cold account access, account write, storage write, value-bearing call, contract creation access, access-list entries) MUST use the Amsterdam figures for Amsterdam blocks and the prior figures otherwise.
- **FR-007**: The storage-clearing refund MUST use the Amsterdam figure for Amsterdam blocks.
- **FR-008**: State-creating operations MUST be charged according to the Amsterdam state-creation model, including its separate accounting dimension for state growth with correct behaviour on frame revert.
- **FR-009**: Block-level gas accounting MUST use the Amsterdam rule for Amsterdam blocks.
- **FR-010**: Contract code size and initialisation code size limits MUST use the Amsterdam figures for Amsterdam blocks.

**Value-transfer logging**

- **FR-019**: On Amsterdam blocks, every balance-changing value transfer MUST emit a transfer log from the designated system address, and those logs MUST participate in receipt and bloom construction exactly as ordinary logs do. This is not optional decoration: without it, every receipt root and bloom after activation is wrong. It is also inseparable from the intrinsic value-transfer charge, which prices it.

**Block structure**

- **FR-011**: Amsterdam block headers MUST carry the two additional fields (block-level access list commitment, slot number), and MUST encode, decode, and hash identically to the reference client.
- **FR-012**: The block-level access list commitment MUST be validated against the block's actual accesses.
- **FR-013**: Headers whose field count matches no known fork MUST be rejected with a diagnostic naming the count. The system MUST NOT silently discard unrecognised fields.
- **FR-014**: For every fork the system supports, decoding a header and re-encoding it MUST reproduce the original bytes and therefore the original hash.

**Builder execution requests**

- **FR-015**: The two builder system contracts MUST be invoked at the appropriate block boundary, and their outputs MUST contribute to the block's aggregate request commitment in the specified order.
- **FR-016**: Builder system contract invocations MUST NOT count against the block gas limit, consistent with the existing treatment of other system-contract invocations.

**Verification obligations**

- **FR-017**: The gas figures observed in the reference fixture MUST be reproduced exactly by the implementation, before this feature is considered complete. A figure that cannot be derived is a gap in understanding, not a rounding difference. **The two previously-unexplained figures are now closed** against the decoded fixture (see `research.md`): the header gas figure is a per-dimension maximum rather than a total, so the header and the receipt counter legitimately disagree; and the four collapsing transactions are measured out-of-gas, with status 0, empty bloom and zero logs recovered byte-exactly from the receipt trie. FR-017 now requires the implementation to reproduce those derivations, not to discover them.
- **FR-018**: The established Ethereum Classic consensus regression suites MUST pass with no assertion or expected-value changes.

### Key Entities

- **Amsterdam activation point**: the per-chain timestamp at which the new rules begin. Absent on chains that never activate.
- **Block-level access list commitment**: a header-carried summary of the accesses performed by a block, validated against execution.
- **Slot number**: a header-carried value introduced alongside the access list commitment.
- **State-growth accounting dimension**: a second gas dimension tracked alongside ordinary execution gas, with its own refill and revert semantics.
- **Builder deposit / exit requests**: two new request kinds produced by system contracts and committed to in the header alongside existing request types.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A node importing the reference Amsterdam chain reaches the chain head with no validation errors, where today it stops at the activation block.
- **SC-002**: Peers performing a status exchange with the node succeed, eliminating the head-mismatch peering failures that currently account for 27 of 34 failures in the affected suite.
- **SC-003**: Every gas figure in the reference fixture across the activation boundary is reproduced exactly, with no unexplained residuals.
- **SC-004**: Suites exercising chains that do not activate Amsterdam show no change in outcome — specifically, the two suites currently at 40 failures and 2 failures neither improve nor regress, confirming the new rules are inert for them.
- **SC-005**: Ethereum Classic consensus behaviour is provably unchanged: all existing ETC regression suites pass without modification to their assertions.
- **SC-006**: A header with an unrecognised field count is rejected rather than admitted with an incorrect hash — verifiable by a test that fails before the change and passes after.

## Assumptions

- **Fork scope is defined by the reference fixture.** The EIPs treated as in scope are those the devp2p fixture actually exercises: resource-based intrinsic gas, state-access repricing, state-creation gas with its reservoir model, block gas accounting, **value-transfer logs**, block-level access lists, slot number, builder execution requests, and raised code size limits. Any further Amsterdam EIP not exercised by that fixture is out of scope for this feature and should be specified separately.
- **Value-transfer logs were added to scope by Phase 0, as a correction rather than an expansion.** They were absent from the first draft because the diagnosis that produced it did not reconstruct receipts. Phase 0 did: post-activation transfer blocks carry bloom content where their pre-activation counterparts carry none, and two of them reconstruct byte-exactly as a single transfer log from the system address. Since scope is defined by what the fixture exercises, and the fixture exercises this, the spec was wrong and is corrected (FR-019). One open scope question remains recorded in `research.md` (R-4): a related EIP referenced by the transfer-log specification was not read, and the fixture does not discriminate on it.
- **The two previously-open gas figures are closed.** They were closed in Phase 0 by reproduction against the decoded fixture, not by reasoning from specification text. The first is not a total at all: the header reports the larger of the two gas dimensions, while the receipt counter reports their sum, so the two fields legitimately disagree on Amsterdam blocks and an implementation carrying one scalar will fail one of them. The second is an ordinary out-of-gas outcome, measured: the state-creation charge for a storage slot exceeds what those transactions' gas limits leave, because their limits sit far below the threshold at which a separate state-gas allowance is seeded, so the state charge competes with execution gas in the same budget. Four different prior costs collapsing to one identical number is the signature of the limit being hit.
- **Passing the reference fixture is necessary but not sufficient.** Every transaction in that chain has a gas limit far below the threshold that seeds a separate state-gas allowance, so the allowance is empty throughout and the fixture never exercises its seeding, its propagation between call frames, its refill ordering, or the merge that follows a successful sub-call. Those paths require the reference test vectors, which are only partially available (see the declared gate gap in the plan). An implementation can be fixture-green and still wrong on a chain with larger transactions.
- **The header-decoder defect is independently shippable.** User Story 2 does not depend on any Amsterdam gas work and fixes a live correctness bug. It can land first.
- **The reference implementation is authoritative for byte-level behaviour.** Where specification text and reference client behaviour could be read differently, the reference client's observable output on the fixture decides.
- **ETC will not adopt these EIPs.** This is treated as settled for the purposes of this feature. If it changes, the activation mechanism added here accommodates it without restructuring, but no ETC activation is configured.
- **No migration or operator action is required.** Nodes on chains without an Amsterdam activation are unaffected; nodes on chains with one gain the ability to follow the chain past activation.
- **Existing system-contract gas treatment is correct and is the model to follow.** The current handling of prior system-contract invocations — not charged against the block gas limit — was verified correct during diagnosis and should be extended to the new builder contracts rather than re-derived.
