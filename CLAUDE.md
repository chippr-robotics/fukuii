# CLAUDE.md — Working in fukuii

`fukuii` is a **multi-network EVM client** (forked from IOHK Mantis, repackaged
under `com.chipprbots`), running on **Scala 3.x LTS** with Pekko actors.
It supports two independent chain families:

- **Ethereum Classic (ETC/Mordor)** — PoW/Ethash, ECIP-1017 fixed-supply
  emission, block-number fork dispatch. Chain ID 61 (mainnet), 63 (Mordor).
- **Ethereum (ETH/Sepolia)** — PoS (post-merge), timestamp fork dispatch.
  Chain ID 1 (mainnet), 11155111 (Sepolia), 7091047534 (Platåberget — public
  Glamsterdam testnet; wider than 32 bits).

## ETC vs ETH — read this first

**ETC keeps**: PoW/Ethash, ECIP-1017 fixed-supply emission, traditional gas
model, pre-merge opcodes, block-number fork dispatch. Hard forks: Atlantis →
Agharta → Phoenix → Thanos (ECIP-1099) → Magneto → Mystique → **Olympia**
(planned: ECIP-1111/1112/1121).

**ETH/Sepolia has**: PoS consensus, timestamp fork dispatch, EIP-1559 base-fee
burned, validator withdrawals, blob transactions (EIP-4844), Osaka fork. Next:
**Amsterdam** (Glamsterdam, EIP-7773) — live on Platåberget since 2026-08-20,
Sepolia 2026-10-06 (`amsterdam-timestamp = 1791294816`), mainnet unscheduled.
Target release 0.9.0; status and schedule in `docs/specifications/GLAMSTERDAM.md`.

**Do not mix these code paths.** ETC fork config uses `OlympiaOpCodes` /
`forBlock()`; ETH fork config uses `OsakaOpCodes` / `forTimestamp()`.

## Build & test commands

| Command | What it runs | When to use |
|---------|-------------|-------------|
| `sbt compile-all` | Compiles all modules + test sources | After every file change (exception: core domain sweeps — use `sbt compile` instead, then `compile-all` once at end; see `testing-protocol.md`) |
| `sbt compile` | Root main sources only — no test/IT/Benchmark | **Core domain type sweeps only** (BlockHeader, Account, Block, Transaction → 50+ dependents) — avoids cascade on every file; `compile-all` once at end |
| `sbt scalafmtAll` | scalafmt across ALL modules (formatting only) | After every migration commit; safe at any time |
| `sbt scalafmt` | scalafmt on ROOT module only | **Do not use** — misses submodules (bytes, crypto, rlp, Evm, etc.) |
| `sbt formatAll` | scalafixAll + scalafmtAll across all modules | Pre-PR on a clean codebase ONLY — aborts on pre-existing scalafix violations |
| `sbt formatCheck` | Verify formatting without writing | CI / pre-flight check |
| `sbt pp` | compile-all + formatAll + quick + integration tests | Pre-PR gate — same caveat as formatAll |
| `sbt "testOnly *Foo*"` | Single test class (seconds) | After each phase that changes logic — not compile-only phases |
| `./scripts/fukuii-test only "*Foo*"` | Wrapper for targeted test | Same as testOnly — prefer this form |
| `./scripts/fukuii-test essential` | Full testEssential via wrapper | **End of thread only, once** — 24 min, do not run between phases |
| `./scripts/fukuii-test quick` | `crypto / test` only (~20 s) | Fast smoke check of the crypto sub-module |
| `sbt testEssential` | Tier 1 full suite (24 min, 3,621 tests) | End of thread only — stalls development if run mid-thread |
| `sbt testStandard` | Tier 2 tests | Before opening a PR |
| `sbt testComprehensive` | Tier 3 full compliance suite (<3 h) | Release gate only |
| `sbt testVM testCrypto` | Tagged test subsets | Targeted validation of specific subsystem |
| `sbt "IntegrationTest / test"` | Integration test module | After protocol-level changes |

**Test cadence during a migration thread:**
1. Every file edit → `sbt compile-all` (mandatory, fast) — **exception**: if the sweep touches a core domain type (BlockHeader, Account, Block, Transaction), use `sbt compile` between files and `sbt compile-all` once at the end (see `testing-protocol.md` → "Core domain type sweeps")
2. Phases that only add types (returns removal, Messages.scala additions) → compile only, no tests
3. After Phase 2 (main migration) and Phase 3 (callers) → `testOnly *<ActorName>*` + any touched caller specs
4. End of thread, once → full `testEssential` to confirm baseline holds

**The two format commands that look similar but are not:**
- `scalafmt` → root module only → **wrong for this codebase**
- `scalafmtAll` → all modules → **always use this one**
- `formatAll` → runs scalafix first → aborts mid-migration on pre-existing violations → **pre-PR only**

Modules: root `main`, plus `bytes`, `crypto`, `rlp`, `Evm`, `Benchmark`,
`RpcTest`, `IntegrationTest`.

## Shared agent protocols

Tracked protocols that all agents reference live in `.claude/agent-protocols/`:

| Protocol | Purpose |
|----------|---------|
| `warning-ratchet.md` | 4-step pattern: inventory → risk-stratified commit → defer with @nowarn → promote to build error |
| `testing-protocol.md` | Per-phase test cadence (compile-all per file, testOnly after logic, testEssential once at end) |
| `risk-stratified-commit.md` | Bucket A/B/C commit discipline for sweep changes |
| `consensus-change-protocol.md` | Hard stop + routing table before touching consensus paths |
| `inline-cleanup.md` | "Hunt and seek" — what to fix opportunistically, what to log in CHASE-QUEUE |
| `logging-standards.md` | Preferred logging API, levels, message format, SLF4J patterns |
| `scala3-style.md` | S1–S11 Scala 3 standards with grep-verifiable ratchets (S11: opaque type full-layer propagation) |
| `scala3-given-migration.md` | G1–G3 operational pitfalls for `given/using` migration (P3a findings, applies to P3b) |
| `pekko-typed-api.md` | P1–P25 Pekko Typed API preferences + TL1/TL2 Cats Effect integration rules |
| `pre-migration-checklist.md` | LOOM pre-flight: grep each actor for sender(), returns, timers, workers before migrating |
| `migration-handoff.md` | Continuation file protocol when a thread ends mid-migration |
| `storage-rocksdb.md` | DataSource contract, column families, iterator lifecycle, WriteBatch, EphemDataSource, RocksDB config |
| `dead-code-review.md` | Three verdicts before any deletion: Wire it / Delete it / Defer — assess gap, git history, and supersession before `git rm` |
| `worktree-protocol.md` | Sprint vs task worktree patterns, naming (`wt/<id>`), lifecycle, bin scripts, agent rules for worktree context |

Working documents (public, code patterns only): `.claude/agent-protocols/working-docs/`
- `CHASE-QUEUE.md` — cross-file issues logged during inline sessions, batched into sprint clusters

Best practices library (research-backed patterns, June 2026 sprint): `.local/best-practices/`
- `scala/type-safety.md` — 10 opaque type propagation patterns (full S11 reference)
- `pekko/typed-patterns.md` — P17–P25 detailed patterns with greps
- `pekko/concurrency.md` — Pekko concurrency and dispatcher patterns
- `evm-clients/` — snap/2 protocol patterns, anti-patterns, p2p, error recovery
- `typelevel/patterns.md` — IO/Resource/Fiber idiomatic patterns
- `codebase-audit.md` — 52 known violations across 9 categories (11 critical, 27 medium, 14 low) with file:line

## Specialist subagents

This project ships project-scoped subagents in `.claude/agents/`. The **main
session is the orchestrator** — subagents cannot spawn other subagents, so you
(the main thread) decide which specialist to delegate to and in what order.

| Agent     | Use it for | Proactive? |
| :-------- | :--------- | :--------- |
| `forge`   | **ETC/Mordor** consensus: EVM, Ethash mining, crypto, state, block rewards, hard forks, EIP/ECIP | **Before** any ETC consensus change |
| `beacon`  | **ETH/Sepolia** consensus: PoS, timestamp forks, Osaka EIPs, withdrawals, blobs, execution payload | **Before** any ETH consensus change |
| `eye`     | Validation: compile + run the right test tier, check chain compatibility, report pass/fail | **After** code changes |
| `wraith`  | Scala 3 compile errors / build failures | On compile failures |
| `herald`  | P2P / RLPx / ETH wire protocol, Snappy, handshakes, multi-client interop | On networking issues |
| `mithril` | Idiomatic Scala 3 modernization (opaque types, enums, given/using) | On-demand |
| `prism`   | 8-lens code quality review (non-consensus only): functionality, tests, readability, structure, simplicity, performance, security, scala-fp | Before PRs on non-consensus code |
| `loom`    | Pekko Classic→Typed migration: one actor per session, pre-flight checks, Command ADT, replyTo, timers | On-demand per actor migration |
| `vault`   | RocksDB / storage layer: DataSource contract, iterator lifecycle, WriteBatch, WAL, cache tuning (`db/`) | On storage bugs / config changes |
| `conduit` | JSON-RPC, HTTP, WebSocket, IPC, GraphQL: method compliance, codec, subscriptions (`jsonrpc/`) | On API / transport bugs |
| `flow`    | Pekko Streams: Source/Sink/Flow graphs, materialization, backpressure, `preMaterialize` anti-patterns, stream test synchronization | On streaming graph bugs / silent element drops |

### Consensus-Critical Change Protocol (mandatory)

Any change to consensus — EIP/ECIP, chain ID, gas costs, state roots, block
rewards, transaction validation/signing, hard-fork config, mining/PoW, crypto —
**must** follow this order. Do not hand-edit consensus code reactively.

0. **Identify the chain**: ETC/Mordor → use `forge`. ETH/Sepolia → use `beacon`.
   Both chains affected → use both in sequence.
1. **Plan** (main session): read the spec completely, identify every affected
   component, map side effects.
2. **`forge` or `beacon`** — consult *before* implementing for impact analysis,
   then implement/review with byte-perfect validation against the reference client.
3. **`wraith`** — fix any compilation errors without altering consensus semantics.
4. **`eye`** — validate: tests, consensus compliance, performance.

Triggers: PR/diff mentions "EIP"/"ECIP"; changes under `consensus/`, `vm/`,
`crypto/`, `domain/`; anything affecting block validation, rewards, or signing.
May skip for docs-only, build config, non-consensus test infra, or pure network
formatting (use `herald`).

## Working discipline (applies to every task)

- **Sequential thinking before action.** State what you understand, what you
  don't, your theory, and your plan. "I don't know" is a valid output.
- **Failure is information.** When something fails, your next move is *words*:
  the raw error, your theory, the proposed step — not another blind tool call.
- **Small batches, then checkpoint.** ~3 changes, then verify reality matches
  your model (compile/test, read output, confirm). >5 actions without
  verification means you're accumulating unjustified beliefs.
- **Evidence standards.** One example is an anecdote; three may be a pattern.
  Never say "all tests pass" unless you ran them — say which tier ran. Use
  `VERIFY: ran <command> — result: PASS | FAIL | DID NOT RUN`.
- **Chesterton's Fence.** Explain why code exists (git history, the tests that
  cover it, the bug it fixed) before changing or deleting it.
- **Root cause, not symptom.** Separate the immediate cause from the systemic one.
- **Fail loudly.** No silent `catch {}` fallbacks that turn hard failures into
  quiet corruption. Let it crash; crashes are data.
- **Irreversible = 10× thought.** Consensus rules, public APIs, DB schemas, and
  git history are one-way doors. When uncertain on a consequential or
  irreversible call, surface options to the user instead of guessing.

## Continuation protocol (applies to every agent)

When a session is running low on turns **or** a logical phase ends with more work
remaining, write a continuation file before the session ends:

```
.local/docs/continuations/<AgentName>-<Topic>.md
```

**Standard sections** (keep each short — the next thread reads this cold):

```markdown
# <AgentName> continuation — <Topic>

## Status
What phase just completed. What phase is next.

## Files modified
| File | Status | Last action |
|------|--------|-------------|
| path/to/File.scala | IN PROGRESS | removed returns, not yet migrated |

## Open compile errors
Paste exact error messages. If clean, write: "sbt compile-all — 0 errors".

## Next action (first thing to do)
One specific instruction: "Open X.scala line N and change Y to Z."

## Test baseline
Last known result: e.g. "3,621 / 0 — run before and after any change."
```

**The continuation thread** reads this file as its first action before anything
else. Do not re-research what is documented here.

**Prior group summaries** (modernization sprint only):
`.local/docs/moderization-review-june/implementation-sprint/summaries/`
Read relevant entries for established patterns before starting any migration.

## OODA loop for large migrations / multi-file work

For comprehensive changes, cycle through:

- **Observe** — map the affected code, read ADRs/specs, study core-geth, and run
  an initial compile to enumerate the real errors. For consensus-touching work,
  run the Consensus-Critical Change Protocol's planning step here.
- **Orient** — prioritize P0 (blocking/core) → P1 (production-readiness) → P2
  (tests) → P3 (polish); map dependencies and the critical path.
- **Decide** — scope what to do now vs. later; route work to the right specialist
  subagent; assess risk and rollback.
- **Act** — small focused commits, compile/test after each, update tracking docs.
  Loop back when new information emerges.

## Conventions

- Add files to git individually; know what you're committing (avoid `git add .`).
- Run `sbt scalafmtAll` before committing during a migration. Run `sbt pp` (which includes `formatAll`) only before a PR on a clean codebase.
- Refer to the human as **user**; be authentic, surface disagreement rather than
  burying it.

## Spec-Driven Development (Spec Kit)

New features are built through the Spec Kit workflow, not ad hoc:
`/speckit-specify` → `/speckit-plan` → `/speckit-tasks` → `/speckit-implement`
(use `/speckit-clarify` and `/speckit-analyze` to de-risk). Spec artifacts live
under `specs/<NNN-feature-name>/`.

**The project constitution at `.specify/memory/constitution.md` is binding.**
Read it before planning or implementing. Highlights:

- Consensus-critical code (EVM/gas, state roots, hashes, RLP, Ethash, rewards,
  hard forks) MUST be byte-for-byte deterministic and ETC-spec compliant — design
  before implementing; follow the `forge` protocol in `.github/agents/forge.md`.
- Scala 3.x LTS only; code MUST pass `scalafmt` + `scalafix`.
- Tests MUST be deterministic (no `Thread.sleep`); keep statement coverage ≥ 70%.
- Run `sbt pp` before opening a PR; CI gates and review must be green to merge.

<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan:
`specs/009-amsterdam-fork-support/plan.md` (Amsterdam hard fork support for ETH-family
chains, timestamp-gated, ETC byte-for-byte unchanged. Motivation is measured: hive's devp2p
fixture declares `amsterdamTime: 360`, so block 36 is the first Amsterdam block and import
dies there, stranding the node at head 35 of ~89 — which is why 27 of that suite's 34
failures are `wrong head block in status`. Four slices. A: strict header field-count
decoding — the `case n if n >= 21` catch-all truncates 23-field headers and emits a WRONG
hash today; ships first, alone, and does NOT move devp2p. B (atomic): EIP-2780 + 8037 +
8038 + 7778 + 7954 + 7708 — Phase 0 proved these are numerically inseparable. C: EIP-7928
block-level access lists. D: EIP-8282 builder requests. THE load-bearing fact: under
EIP-8037 the header's `gasUsed` is max(execution, state), NOT a total, while receipts sum
both — block 41 carries header 183,600 and receipt 326,947 and a single-counter
implementation fails one of them. Fixture-green is necessary but NOT sufficient: every
fixture tx sits below TX_MAX_GAS_LIMIT so the state-gas reservoir is empty throughout and
its seeding, cross-frame passing, LIFO refills and successful-child merge go untested.
`beacon` owns ETH; `forge` signs off that ETC is untouched; rpc-compat (40) and graphql (2)
are the inertness oracles and must not move in EITHER direction.)
Prior plans: `specs/007-hotpath-alloc-reduction/plan.md`,
`specs/004-decoupled-heal-serve-root/plan.md`.
<!-- SPECKIT END -->
