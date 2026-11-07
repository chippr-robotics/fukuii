# Testing Protocol

Test cadence for all agent sessions on the fukuii codebase. The full test suite
takes 24 minutes — running it between phases compounds into hours of stall time.
This protocol keeps feedback fast without sacrificing coverage.

Used by: ALL agents
Referenced by: `fukuii/CLAUDE.md`, loom.md

---

## The three tiers (ADR-017)

| Command | What runs | Time | Baseline |
|---------|-----------|------|---------|
| `sbt testEssential` | 7,219 root unit tests, plus rlp 46, bytes 11, crypto 80 (their own `test`) | ~24 min | 7,219 / 0 failures |
| `sbt testStandard` | Unit + integration | ~30 min | — |
| `sbt testComprehensive` | Full ethereum/tests compliance | <3 h | — |

---

## Per-phase cadence

### After EVERY file edit
```bash
sbt compile-all    # mandatory, fast — type errors surface immediately
```
Never batch multiple file edits before compiling. One file, one compile.

**Exception — core domain type sweeps:** see the dedicated section below.

### After formatting-only phases
(Returns removal, Messages.scala type additions, import cleanup)
```bash
sbt formatAll      # formatting + scalafix, every module — no tests needed, no logic changed
```
No tests. These phases change syntax only — compile is the full signal. (`sbt scalafmtAll` alone formats the root
project only; see "Format commands".)

### After logic-changing phases
(Main migration, caller updates, behavior changes)
```bash
./scripts/fukuii-test only "*<ActorNameSpec>*"          # actor-specific, seconds
./scripts/fukuii-test only "*<SubsystemSuite>*"         # subsystem if callers touched
```
Run targeted tests only. Do not run testEssential here.

### End of thread — once
```bash
./scripts/fukuii-test essential                          # full testEssential (~24 min)
```
Run exactly once per thread after all phases are complete. This is the regression gate.
Do not run it between phases. Do not run it as a mid-session sanity check.

---

## Core domain type sweeps

When a sweep touches a **heavily-imported domain type** — `BlockHeader`,
`Account`, `Block`, `Transaction`, or any type imported by >50 files — Zinc's
incremental compiler cascades the change through every dependent compilation
unit across all 12 modules. This is not a hang or a freeze: it is the expected
one-time cost of touching the most-depended-on types in the codebase.

### How to detect before starting

```bash
# Count files importing the type you are about to change
grep -rl "BlockHeader\b" src/ --include="*.scala" | wc -l   # e.g. 180+
grep -rl "Account\b"     src/ --include="*.scala" | wc -l   # e.g. 90+
```

If result > 50, you are in core domain sweep territory.

### Compile strategy during the sweep

Replace the per-file `sbt compile-all` with `sbt compile` (root main only):

```bash
sbt compile     # root main sources only — no test/IT/Benchmark modules
```

- The first `sbt compile` after touching a core type will be slow (1–3 min
  full cascade). Every subsequent compile in the same sweep is fast (incremental
  delta only, seconds).
- `sbt compile` still catches all type errors in main-source files — it is a
  complete signal for correctness of the changes.
- Test sources, IT, and Benchmark modules are excluded. They import the same
  types but do not need to recompile between each main-source edit.

At the very end of the sweep (all 18 / N files done):

```bash
sbt compile-all    # once — catches any issues in test/IT/Benchmark sources
```

### Root cause (why `compile-all` is slow here but not normally)

Normal changes touch leaf or mid-level files; Zinc's dependency graph is
shallow, so the cascade is small. Touching `BlockHeader` (or similar) is
touching the root of the dependency graph — every file that imports it must
recompile. `compile-all` includes test + IT + Benchmark sources, so the
cascade is 3–4× larger than root-main alone. Using `sbt compile` between
edits limits each cascade to main sources only; test sources recompile once
at the end.

### Affected types (known core domain, as of June 2026)

| Type | Location | Approx. dependents |
|------|----------|-------------------|
| `BlockHeader` | `domain/src/…/domain/blockchain/block/BlockHeader.scala` | 180+ |
| `Account` | `domain/src/…/domain/blockchain/state/Account.scala` | 90+ |
| `Block` | `domain/src/…/domain/blockchain/block/Block.scala` | 120+ |
| `Transaction` | `domain/src/…/domain/blockchain/transaction/Transaction.scala` | 100+ |

If you are unsure whether a type qualifies, run the grep above before starting.

---

## Targeted test patterns

```bash
# By actor name:
./scripts/fukuii-test only "*AccountRangeCoordinatorSpec*"
./scripts/fukuii-test only "*ByteCodeCoordinatorSpec*"

# By subsystem:
./scripts/fukuii-test only "*SNAPSuite*"      # all SNAP tests (~263)
./scripts/fukuii-test only "*NetworkSuite*"

# By tag (sbt native):
sbt testNetwork
sbt testRLP
sbt testCrypto
sbt testVM
```

---

## Format commands

| Command | What it does | When to use |
|---------|-------------|-------------|
| `sbt scalafmtAll` | scalafmt on the **root project only**: its main, `Test` and `IntegrationTest` sources. Not bytes, crypto, rlp, scalanet, scalanetDiscovery (the root has no `aggregate`), and not `src/evmTest`, `src/rpcTest` or `src/benchmark` (verified 2026-11-05 with a misformatted file in each; `formatAll` and `formatCheck` run the same root command, so no alias formats those three) | Root-only edits |
| `sbt scalafmt` | scalafmt ROOT module only | **Never** — misses submodules |
| `sbt formatAll` | compile-all, then scalafixAll + scalafmtAll on bytes, crypto, rlp, root; scalafmtAll on scalanet and scalanetDiscovery | After editing any module; before a PR. The tree is scalafix-clean since spec 020 PR 6b, so it no longer aborts on old findings |
| `sbt formatCheck` | the read-only form: compile-all, `scalafixAll --check` (bytes, crypto, rlp, root), `scalafmtCheckAll` (all six modules) | What CI runs in "Test and Build" (from #1721); run it before pushing |
| `sbt pp` | compile-all, scalafmtAll on bytes, crypto, rlp, root, then `rlp / test`, `testQuick`, `IntegrationTest / test` | Pre-PR gate; it runs no scalafix and skips scalanet, so add `formatCheck` |

**scalafix parse failure on a continuation-line `new X:`.** scalafix 0.14.9 bundles scalameta 4.17.4, which cannot parse
a fewer-braces template opened on a continuation line, as in `it should "x" in` with `new X:` on the next line. It
fails on the *next* sibling in the file (`indent` expected, or `;` expected but `:` found), so the error points at a
test that looks fine, and `formatCheck` goes red. scalafmt 3.8.3 (scalameta 4.9.9) formats such files without complaint,
so `scalafmtCheckAll` stays green. Write `in (new X:` ... `)`, the shape scalafmt itself emits for a braced operand. Braces
do not survive: scalafmt's `removeOptionalBraces` rewrites `in new X { ... }` back to `in new X:`. Spec 020 PR 6b-1
(#1719) rewrote the affected test files.

---

## scalanet and scalanetDiscovery tests

`compile-all` builds neither module's test sources. `scalanet / Test / compile` and `scalanetDiscovery / Test / compile`
run on their own, and both compile since spec 020 PR 6b-1 (#1719). `scalanetDiscovery / test` is **not** a CI step and
should not be added: the v4 `DiscoveryNetworkSpec` (`scalanet/discovery/ut`) hangs (a pre-existing fault, noted in
the description of #1721). `formatCheck` does scalafmt-check both modules but runs scalafix on neither.

---

## What "baseline holds" means

The thread-end `testEssential` run must show:
- Test count: **7,219** root tests, plus rlp 46, bytes 11, crypto 80 (not fewer — a missing test class = silent deletion). Last measured 2026-11-05 on spec 020 PR 6b-1 and 6b-2
- Failures: **0**
- If count drops: investigate before closing the thread

**Per-thread delta tracking:** Before starting any migration or sweep thread, record
the current test count from the most recent `testEssential` run (see the test quality
log at `.local/docs/test-quality-log.md` for the last known baseline). At thread end,
compare. A negative delta — even by 1 — means a test class was silently deleted or a
`@Test` annotation was dropped. This commonly happens when a Classic actor spec is
deleted during migration but no Typed replacement spec is written. Investigate before
closing; do not accept a lower count as the new baseline without a recorded reason.

---

## Inline test standards (when writing new tests)

- No `Thread.sleep` — use `eventually(...)` or `TestProbe.expectMsg(duration)`
- No `@Ignore` without a one-line comment explaining the gate condition
- Deterministic: same result on every machine and CI run
- Each test covers one behavior — not a scenario script
- New tests for migrated actors: use `ActorTestKit` (Typed), not `TestActorRef` (Classic)

---

## Protocol for failing tests after migration

1. Run targeted test first: `./scripts/fukuii-test only "*<FailingSpec>*"`
2. Read the failure — understand it before touching anything
3. If failure is in the migrated actor: fix the migration, not the test
4. If failure is in a test that tests Classic behavior: update the test for Typed API
5. If failure is in an unrelated test: note it, do not fix it (out of scope), surface to user
6. Never modify a test to make it pass without understanding why it failed
