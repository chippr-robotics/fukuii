# CLAUDE.md — Working in fukuii

`fukuii` is an **Ethereum Classic (ETC)** client (forked from IOHK Mantis,
repackaged under `com.chipprbots`), running on **Scala 3.3.7 LTS** with Akka
actors and PoW (Ethash) mining. This file orients Claude and any subagents.

## ETC ≠ Ethereum mainnet (read this first)

ETC **keeps**: PoW/Ethash mining, ECIP-1017 fixed-supply emission, the
traditional gas model, pre-merge opcodes. Chain ID is **61** (mainnet).
ETC hard forks: Atlantis → Agharta → Phoenix → Thanos (ECIP-1099) → Magneto →
Mystique. ETC does **NOT** have Proof-of-Stake/the merge, EIP-1559, blob
transactions, or account abstraction. Never add ETH-mainnet-only features.

## Build & test commands

```bash
sbt compile-all        # compile every module + test sources
sbt pp                 # "prepare PR": compile-all, scalafmt, quick + integration tests
sbt formatAll          # scalafix + scalafmt across all modules
sbt formatCheck        # verify formatting without changing files

# Test tiers (ADR-017)
sbt testEssential      # Tier 1 (<5 min): fast unit tests
sbt testStandard       # Tier 2 (<30 min): unit + integration
sbt testComprehensive  # Tier 3 (<3 h): full ethereum/tests compliance suite

# Targeted by tag
sbt testVM testCrypto testNetwork testRLP testMPT testEthereum
sbt "IntegrationTest / test"
```

Modules: root `main`, plus `bytes`, `crypto`, `rlp`, `Evm`, `Benchmark`,
`RpcTest`, `IntegrationTest`.

## Specialist subagents

This project ships project-scoped subagents in `.claude/agents/`. The **main
session is the orchestrator** — subagents cannot spawn other subagents, so you
(the main thread) decide which specialist to delegate to and in what order.

| Agent     | Use it for | Proactive? |
| :-------- | :--------- | :--------- |
| `forge`   | Consensus-critical code: EVM, mining, crypto, state, block rewards, hard forks, any EIP/ECIP | **Before** any consensus change |
| `eye`     | Validation: compile + run the right test tier, check ETC compatibility, report pass/fail | **After** code changes |
| `wraith`  | Scala 3 compile errors / build failures | On compile failures |
| `herald`  | P2P / RLPx / ETH wire protocol, Snappy, handshakes, core-geth interop | On networking issues |
| `mithril` | Idiomatic Scala 3 modernization (opaque types, enums, given/using) | On-demand |

### Consensus-Critical Change Protocol (mandatory)

Any change to consensus — EIP/ECIP, chain ID, gas costs, state roots, block
rewards, transaction validation/signing, hard-fork config, mining/PoW, crypto —
**must** follow this order. Do not hand-edit consensus code reactively.

1. **Plan** (main session): read the spec completely, identify every affected
   component, map side effects.
2. **`forge`** — consult *before* implementing for impact analysis, then let it
   implement/review with byte-perfect validation against core-geth.
3. **`wraith`** — fix any compilation errors without altering forge's semantics.
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
- Run `sbt scalafmtAll` (or `sbt pp`) before pushing.
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
- Scala 3.3.7 LTS only; code MUST pass `scalafmt` + `scalafix`.
- Tests MUST be deterministic (no `Thread.sleep`); keep statement coverage ≥ 70%.
- Run `sbt pp` before opening a PR; CI gates and review must be green to merge.

<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan
<!-- SPECKIT END -->
