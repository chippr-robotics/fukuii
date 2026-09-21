# Quickstart: validating Amsterdam support

**Feature**: [spec.md](./spec.md) · **Plan**: [plan.md](./plan.md)

How to prove each slice works, and — equally — how to prove it broke nothing. Contracts and figures
live in [contracts/](./contracts/); this is the run guide.

## Prerequisites

- JDK 25, sbt 1.10.7, Scala 3.3.8 LTS (already the project baseline)
- Docker, for hive
- The decoded devp2p fixture, if you want to re-derive rather than trust: `chain.rlp`, `txinfo.json`,
  `headstate.json`, `headblock.json`, `genesis.json`, `forkenv.json` from hive's `devp2p` simulator
  testdata

## Fast loop, during implementation

```bash
sbt compile-all                      # after every file edit
sbt scalafmtAll                      # before every commit
sbt "testOnly *Amsterdam*"           # the new specs
sbt "testOnly *BlockHeader*"         # slice A
```

`sbt pp` only pre-PR on a clean tree — it runs `formatAll`, which aborts on pre-existing scalafix
violations.

## Slice A — header arity

```bash
sbt "testOnly *BlockHeaderAmsterdamRlpSpec*"
```

Passes when: a 23-item header decodes to `HefPostAmsterdam`, re-encodes to the same bytes, and hashes
to `6372c88fef519c6e4bebbe02d8447c084fc8cd759fae25c5b5cc3c6e2be99bfe`; a 22-item header is **rejected**
with the count in the message; and the round-trip invariant holds for all six shapes in
[contracts/header-rlp.md](./contracts/header-rlp.md).

Fails before the change: the 23-item case currently succeeds with hash `94844dfd…`. **Confirm it fails
first** — a test that passes before the fix is testing the wrong thing.

Slice A does **not** move devp2p. Block 36 still fails, just earlier and honestly. That is expected and
is not a reason to bundle it with slice B.

## Slice B — the gas change

```bash
sbt "testOnly *AmsterdamGasAccountingSpec*"
```

The five vectors in [contracts/gas-accounting.md](./contracts/gas-accounting.md), of which two decide
the slice:

- **V1**: block 41 produces header `gasUsed` **183,600** *and* receipt `cumulativeGasUsed` **326,947**.
  One counter cannot satisfy both.
- **V2**: the four `tx-emit-*` transactions each halt with status 0, empty bloom, zero logs, 100,000
  consumed.

Then the fixture end to end:

```bash
cd hive && ./hive --sim devp2p --client fukuii
```

Passes when: the node imports past block 36 to head ~89 with no `ValidationAfterExecError`, and the
`wrong head block in status` peering failures clear. **No numeric prediction is made** for the
resulting failure count — the chain must import fully before those failures can move, and how far they
then move is not derivable in advance. A prediction made earlier in this effort was wrong for a
knowable reason; the same caution applies.

## Non-regression — the part that is easy to skip and shouldn't be

Both of these chains reach Cancun/Prague, neither declares an Amsterdam activation, and both carry
substantial contract activity. They are the oracle proving Amsterdam code is inert where it should be.

```bash
cd hive
./hive --sim ethereum/rpc-compat --client fukuii     # baseline 40 failures / 247
./hive --sim ethereum/graphql    --client fukuii     # baseline 2 failures / 52
```

**Must not move in either direction.** An improvement is as much a signal as a regression: it means
Amsterdam code ran on a chain that never activated it.

Derive counts from the simulator's `testCases[].summaryResult.pass` verdicts, not from grepping
fukuii's own logs. Reading client logs instead of simulator verdicts produced three wrong diagnoses
earlier in this effort, including one retracted publicly.

## ETC safety — every slice, no exceptions

```bash
sbt "testOnly *SpiralToOlympiaGasTransitionSpec*"
sbt "testOnly *OlympiaBlockHeaderValidationSpec*"
sbt "testOnly *OlympiaGasLimitSpec*"
sbt "testOnly *GasLimitCalculationSpec*"
sbt "testOnly *ChainConfigMatrixSpec*"          # asserts no ETC config declares amsterdamTimestamp
```

Green **with no assertion changes**. If one goes red, the change is wrong and the spec is right
(Constitution V). Pin `amsterdamTimestamp = None` explicitly in those fixtures rather than inheriting
a parse default, so the ETC assumption is stated at the assertion site.

## End of thread, once

```bash
sbt testEssential      # ~24 min, 3,621 tests — not between phases
```

## Reporting

Use `VERIFY: ran <command> — result: PASS | FAIL | DID NOT RUN`, and name the tier. "All tests pass"
without a tier is not a claim this project accepts.
