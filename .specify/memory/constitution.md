<!--
SYNC IMPACT REPORT
==================
Version change: (template) → 1.0.0
Rationale: Initial ratification. First concrete constitution derived from the
repository's existing, tool-enforced standards (build.sbt, .scalafmt.conf,
.scalafix.conf, CI workflows, VERSIONING.md, BRANCH_PROTECTION.md,
docs/development/contributing.md, and .github/agents/forge.md).

Principles defined:
  I.   Consensus Determinism Is Sacred (NON-NEGOTIABLE)
  II.  Spec-Driven Development
  III. Test Discipline & Tiered Coverage
  IV.  Idiomatic, Formatted Scala 3
  V.   Quality Gates Are Mandatory
  VI.  Security & Operational Safety
  VII. Transparent Versioning & Decision Records

Added sections:
  - Technology & Architecture Constraints
  - Development Workflow & Quality Gates
  - Governance

Templates & artifacts reviewed for alignment:
  ✅ .specify/templates/plan-template.md  (Constitution Check gate references this file)
  ✅ .specify/templates/spec-template.md  (no mandatory-section changes required)
  ✅ .specify/templates/tasks-template.md (task categories cover testing/consensus)
  ✅ .specify/templates/checklist-template.md (generic; no change required)

Follow-up TODOs: none. RATIFICATION_DATE set to first adoption (2026-06-05).
-->

# Fukuii Constitution

Fukuii is an Ethereum Classic (ETC) full node, written in Scala 3 and descended
from IOHK Mantis. It participates in a live, adversarial, value-bearing network.
This constitution defines the non-negotiable standards every change MUST uphold
so that contributions remain safe, repeatable, and reviewable. It applies to all
contributors, human and automated.

## Core Principles

### I. Consensus Determinism Is Sacred (NON-NEGOTIABLE)

Consensus-critical code MUST be byte-for-byte deterministic and compliant with
the Ethereum Classic specification. This covers the EVM and opcode/gas semantics,
state and Merkle Patricia Trie roots, block and transaction hashing, RLP
serialization, signature verification, Ethash PoW and DAG generation, block
reward schedules (ECIP-1017), chain-ID handling (EIP-155), and ETC hard-fork
activation (Atlantis, Agharta, Phoenix, Thanos, Magneto, Mystique, Spiral, and
later).

Rules:
- State roots, block hashes, and gas costs MUST match the reference/ETC
  specification exactly. "Close enough" is a consensus bug.
- Any change touching the domains above MUST be designed and reviewed BEFORE
  implementation — never patched reactively after a failure. The `forge` agent
  protocol in `.github/agents/forge.md` is the authoritative workflow for this.
- ETC is and remains Proof-of-Work. Post-Merge Ethereum features (PoS, beacon
  chain, EIP-1559 base fee, account abstraction) MUST NOT be introduced.
- Wire-protocol messages MUST be formatted for the negotiated peer capability
  (e.g. ETH66+ requestId framing vs. ETH62 framing); formats MUST NOT be mixed
  on a connection.

Rationale: A single non-deterministic line can split the chain. This principle
outranks all others; when it conflicts with convenience, convenience loses.

### II. Spec-Driven Development

Features are built through the Spec Kit flow, not ad hoc. Each non-trivial change
flows through `/speckit-specify` → `/speckit-plan` → `/speckit-tasks` →
`/speckit-implement`, with `/speckit-clarify` and `/speckit-analyze` used to
de-risk ambiguity.

Rules:
- The specification captures the *what* and *why* (user-facing behavior and
  requirements) and MUST avoid premature implementation detail.
- The plan MUST pass the Constitution Check gate (see
  `.specify/templates/plan-template.md`) before and after design; violations are
  either removed or explicitly justified in the plan's Complexity Tracking.
- Spec artifacts live under `specs/<NNN-feature-name>/` and are committed
  alongside the code they govern.

Rationale: A written, reviewed spec makes intent explicit and review repeatable,
and lets both humans and agents pick up work without re-deriving context.

### III. Test Discipline & Tiered Coverage

Behavioral changes ship with tests, and tests MUST be deterministic.

Rules:
- Use the established stack: ScalaTest, ScalaMock/Mockito, ScalaCheck for
  property tests, Pekko TestKit for actors, Cats Effect for async.
- Tests MUST NOT use `Thread.sleep`. Use TestKit (`expectMsg`,
  `expectNoMessage`, `awaitCond`) or ScalaTest `eventually(timeout(...))`.
- Respect the three test tiers: Essential (`testEssential`, < 5 min, the PR
  gate), Standard (`testStandard`, < 30 min, + coverage), and Comprehensive
  (`testComprehensive`, full ethereum/tests). Tag tests appropriately
  (`SlowTest`, `IntegrationTest`, `SyncTest`, `FlakyTest`, `DisabledTest`).
- Statement coverage MUST stay at or above the enforced 70% minimum
  (`coverageFailOnMinimum := true`); do not lower the gate to make a build pass.
- Consensus-critical changes additionally require validation against
  ethereum/tests and confirmation that state roots, gas, and hashes are
  unchanged versus the reference.

Rationale: A node that fails silently corrupts state or forks. Deterministic,
tiered tests keep the feedback loop fast locally and exhaustive in CI.

### IV. Idiomatic, Formatted Scala 3

The codebase is Scala 3.3.7 LTS only, under the `com.chipprbots.ethereum`
package root.

Rules:
- All code MUST pass `scalafmt` (3.8.3 config: 120 columns, Scala 3 dialect) and
  `scalafix` (DisableSyntax, ExplicitResultTypes, OrganizeImports, RemoveUnused,
  NoAutoTupling, NoValInForComprehension, ProcedureSyntax). `return` and
  `finalize` are disallowed.
- Imports MUST follow the configured group order, with
  `com.chipprbots.ethereum.*` last.
- No new `io.iohk` / `mantis` package or config references — the namespace is
  `com.chipprbots.ethereum` and the config root is `fukuii`.
- Public/exported definitions carry explicit result types (enforced by
  scalafix); prefer total, side-effect-honest functions (Cats Effect `IO`).

Rationale: A single, machine-enforced style removes bikeshedding from review and
keeps diffs about behavior, not formatting.

### V. Quality Gates Are Mandatory

`main` is always releasable. Code merges only when the automated gates are green.

Rules:
- Before opening a PR, contributors MUST run `sbt pp` (compile-all → scalafmt →
  fast tests + integration tests) locally and resolve all findings.
- CI MUST pass: format check (`scalafmtCheckAll`), `compile-all`, Tier-1
  `testEssential`, Tier-2 `testStandard` with coverage, KPI baselines, and the
  ethereum/tests integration job, plus the assembly/dist build.
- PRs require at least one approving review and resolution of all review
  conversations before merge, per `.github/BRANCH_PROTECTION.md`.
- A red build is never merged by lowering a gate, deleting a failing test, or
  using `--no-verify` to bypass checks.

Rationale: Gates that can be skipped are not standards. Enforcing them in CI is
what makes quality repeatable rather than aspirational.

### VI. Security & Operational Safety

Fukuii guards keys and a publicly reachable network surface; security is a
first-class requirement, not an afterthought.

Rules:
- Secrets, private keys, keystores, and credentials MUST NOT be committed; the
  `.gitignore` protections for keys/keystores/`.env` MUST be preserved.
- JSON-RPC endpoints MUST default to private/localhost binding and MUST NOT be
  documented or shipped as publicly exposed. Only discovery (UDP) and P2P (TCP)
  are intended to be internet-facing.
- Dependency and CVE updates MUST be applied promptly and kept Scala 3
  compatible; releases ship an SBOM and signed (Cosign/OIDC) artifacts.
- Changes affecting cryptography, key handling, or network-exposed surfaces
  require explicit security consideration in the spec/plan and review.

Rationale: A node holds value and trust; a leaked key or an exposed RPC port is
an immediate, irreversible compromise.

### VII. Transparent Versioning & Decision Records

Change history MUST be legible to operators and contributors.

Rules:
- Versioning is semantic (`MAJOR.MINOR.PATCH`, tracked in `version.sbt`): PATCH
  per merge, MINOR at milestones, MAJOR for completion/breaking releases, per
  `.github/VERSIONING.md`.
- Commits use conventional prefixes (`feat:`, `fix:`, `security:`, `docs:`,
  `chore:`), are atomic and imperative, and reference issues (`Fixes #NNN`).
- Architecturally significant or consensus-relevant decisions are recorded as
  ADRs under `docs/adr/`; the spec/plan links the governing ADR.
- Breaking changes MUST be flagged clearly in the PR and changelog.

Rationale: Operators run this software against real value; they must be able to
trust that a version number and changelog accurately describe what changed.

## Technology & Architecture Constraints

- **Language/Runtime**: Scala 3.3.7 LTS on JDK 25 (Temurin); build with sbt
  1.10.7+. No Scala 2 and no cross-build.
- **Core libraries**: Apache Pekko (actors/HTTP), Cats Effect (`IO`), Monix,
  RocksDB for storage, BouncyCastle for crypto. New dependencies MUST be Scala 3
  compatible and justified in the plan.
- **Module boundaries**: respect the layered modules — `bytes`, `crypto`, `rlp`,
  `scalanet` (vendored P2P) as foundations; `src/main` for the node
  (`blockchain/sync`, `consensus`, `db/storage`, `domain`, `jsonrpc`, `ledger`,
  `mpt`, `network`, `vm`, `nodebuilder`). Do not create cyclic or layer-violating
  dependencies.
- **Determinism budget**: consensus/state code MUST remain within ~10% of the
  established performance baseline and produce identical results to the
  reference implementation.
- Vendored/submodule code (e.g. `scalanet`, `ets/tests`) MUST retain its
  attribution and licensing.

## Development Workflow & Quality Gates

1. Branch from the appropriate base using a descriptive name
   (`feature/...`, `fix/...`); never commit directly to protected branches.
2. Drive the work through the Spec Kit flow (Principle II); keep spec artifacts
   in `specs/<NNN-feature-name>/`.
3. Implement in idiomatic Scala 3 (Principle IV), with deterministic tests
   (Principle III).
4. Run `sbt pp` locally; pre-commit hooks may be used to enforce
   format/scalafix on staged files.
5. Open a PR with a clear title (< 70 chars), a what/why description, the testing
   approach, and any breaking-change callouts; link the spec/ADR.
6. CI gates (Principle V) and at least one review MUST pass; all conversations
   resolved before merge.
7. Consensus-critical work additionally follows the `forge` consultation
   protocol and ethereum/tests validation before merge.

## Governance

This constitution supersedes ad hoc practice. Where a guideline elsewhere in the
repo conflicts with it, this document wins; where this document is silent,
`docs/development/contributing.md` and the agent definitions in `.github/agents/`
provide operational detail.

- **Amendments**: Proposed via PR that edits this file, states the rationale, and
  bumps the version. Changes that alter or remove a principle require maintainer
  approval. Use `/speckit-constitution` to keep dependent templates in sync.
- **Versioning of this document**: Semantic. MAJOR for backward-incompatible
  governance changes or principle removal/redefinition; MINOR for a new principle
  or materially expanded section; PATCH for clarifications and wording.
- **Compliance**: Every PR and review MUST verify compliance with the applicable
  principles. The plan's Constitution Check gate is the primary enforcement
  point; unavoidable deviations MUST be justified in Complexity Tracking and
  approved, not hidden.
- **Runtime guidance**: Agents and contributors read `CLAUDE.md` and the current
  plan for execution context; this file defines the standards those plans must
  satisfy.

**Version**: 1.0.0 | **Ratified**: 2026-06-05 | **Last Amended**: 2026-06-05
