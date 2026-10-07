# Constitution Enforcement Map

**Binding document:** [`.specify/memory/constitution.md`](https://github.com/chippr-robotics/fukuii/blob/main/.specify/memory/constitution.md) v1.1.0
**Gate matrix:** [`.github/gates.yml`](https://github.com/chippr-robotics/fukuii/blob/main/.github/gates.yml)
**Enforced by:** `scripts/ci/check_gate_integrity.py` (checks C8, C9) — required status check
**Spec:** `specs/008-ci-gate-integrity/` · **Issue:** [#1404](https://github.com/chippr-robotics/fukuii/issues/1404)

---

## Why this file exists

The constitution states seven principles, each written as MUST. Until now, none of them
mapped to anything that could fail a build. v0.8.0 shipped in direct violation of
Principle V ("`main` is always releasable", "a red build is never merged by lowering a
gate") and no check objected — because there was no check.

A MUST with no enforcement behind it is not a standard; it is an aspiration wearing a
standard's clothes. This file makes the distinction explicit and machine-checked:

- **`enforced`** — a named gate from the matrix fails the build when the rule is broken.
  The gate must be `tier: required`; the Gate Integrity check rejects a row that claims
  `enforced` via a merely informational check (C9).
- **`aspirational`** — not machine-checkable today. The row states the human review step
  that substitutes, so the gap is *visible* rather than implied.

Adding or renaming a principle without adding a row here fails CI (C8). That is deliberate:
amending the constitution forces the enforcement question to be answered in the same PR.

**Honest summary as of 2026-09-20:** most rules are currently `aspirational`. That is not a
weakening — it is the first accurate statement of where this project stands. The gate
matrix schedules the promotions; this file records what is true today.

---

## Principle I — Consensus Determinism Is Sacred (NON-NEGOTIABLE)

| # | Rule | Status | Enforced by / substitute |
|---|---|---|---|
| I | Consensus Determinism Is Sacred | partial | see rows below |
| I.1 | State roots, block hashes, gas costs match the governing spec | `enforced` | `gate:ci-test-build` — ethereum/tests integration job + Tier-1/Tier-2 suites |
| I.2 | Consensus changes designed and reviewed before implementation | `aspirational` | Human: `forge` (ETC) / `beacon` (ETH) consultation per `.github/agents/`; PR review verifies the impact analysis exists. Not machine-checkable — the artifact is a judgement, not a diff property. |
| I.3 | ETC and ETH code paths do not mix (`OlympiaOpCodes`/`forBlock()` vs `OsakaOpCodes`/`forTimestamp()`) | `enforced` | `gate:ci-test-build` — `ChainIsolationSpec` (Tier-1) asserts the invariant for every shipped chain config |
| I.4 | ETC remains Proof-of-Work; PoS logic must not enter the ETC path | `enforced` | `gate:ci-test-build` — `ChainIsolationSpec` asserts `terminalTotalDifficulty` is absent on every ETC-typed config |
| I.5 | ETH/Sepolia must not inherit ECIP-1017 / Ethash assumptions | `enforced` | `gate:ci-test-build` — `ChainIsolationSpec` asserts ETC-only emission parameters are inert on ETH-typed configs |
| I.6 | Wire messages formatted for the negotiated peer capability | `aspirational` | Human: `herald` review on P2P wire changes. `hive-devp2p` covers part of this but is `informational` — it may not be cited as enforcement until promoted. |

> **Note on I.3.** The invariant that protects ETC today is that ETC chain configs leave
> `forkTimestamps` unset, making the timestamp-aware `EvmConfig.forBlock` overload inert on
> ETC. Before this spec that was an unasserted convention, one config edit from a chain
> split. `ChainIsolationSpec` converts it into an enforced invariant.

## Principle II — Spec-Driven Development

| # | Rule | Status | Enforced by / substitute |
|---|---|---|---|
| II.1 | Non-trivial changes flow through the Spec Kit workflow | `aspirational` | Human: PR review confirms a linked spec for non-trivial work. Triviality is a judgement call; a mechanical check would be trivially gamed by splitting PRs. |
| II.2 | The plan passes the Constitution Check gate | `aspirational` | Human: reviewer verifies the plan's Constitution Check section. |
| II.3 | Spec artifacts live under `specs/<NNN-feature-name>/` | `aspirational` | Convention; a path check would fire on every docs-only PR for no benefit. |

## Principle III — Test Discipline & Tiered Coverage

| # | Rule | Status | Enforced by / substitute |
|---|---|---|---|
| III.1 | Established test stack (ScalaTest/ScalaCheck/Pekko TestKit/Cats Effect) | `aspirational` | Human: review. |
| III.2 | No `Thread.sleep` in tests | `aspirational` | Human: review. **Deferred mechanisation** — a `scalafix` `DisableSyntax` rule scoped to test sources would enforce this; owner realcodywburns, tracked by #1404, target 2026-12-31. |
| III.3 | Test tiers respected (`testEssential` gates PRs) | `enforced` | `gate:ci-test-build` — Tier-1 `testEssential` runs on every PR |
| III.4 | Statement coverage ≥ 70% (`coverageFailOnMinimum`) | `enforced` | `gate:ci-test-build` — Tier-2 `testStandard` with coverage on develop and PRs into main |
| III.5 | Consensus changes validated against ethereum/tests | `enforced` | `gate:ci-test-build` — ethereum/tests integration job. **Scope caveat:** the *nightly* full-schedule run (`ethereum-tests-nightly`) is `informational` and runs `continue-on-error`; only the in-CI subset is enforced. |

## Principle IV — Idiomatic, Formatted Scala 3

| # | Rule | Status | Enforced by / substitute |
|---|---|---|---|
| IV.1 | All code passes `scalafmt` | `enforced` | `gate:ci-test-build` — `sbt scalafmtCheckAll` |
| IV.2 | All code passes `scalafix` (DisableSyntax, ExplicitResultTypes, OrganizeImports, RemoveUnused, …) | `aspirational` | **Formally deferred** (#1404, FR-020). `scalafixAll` is not in the CI path — only `scalafmtCheckAll` is. The codebase carries pre-existing violations, which is why `sbt formatAll` is documented as "pre-PR on a clean codebase ONLY". Owner realcodywburns; ratchet target 2027-03-31; approach per `.claude/agent-protocols/warning-ratchet.md`. |
| IV.3 | Import group order, `com.chipprbots.ethereum.*` last | `aspirational` | Part of the deferred `scalafix` ratchet above (OrganizeImports). |
| IV.4 | No new `io.iohk` / `mantis` package or config references | `aspirational` | Human: review. Mechanisable as a grep gate; folded into the IV.2 ratchet. |
| IV.5 | Explicit result types on public definitions | `aspirational` | Part of the deferred `scalafix` ratchet above (ExplicitResultTypes). |

## Principle V — Quality Gates Are Mandatory

| # | Rule | Status | Enforced by / substitute |
|---|---|---|---|
| V.1 | Contributors run `sbt pp` before opening a PR | `aspirational` | Local discipline; unobservable from CI. The CI gates re-run the same checks, so a skipped `sbt pp` costs a cycle rather than escaping detection. |
| V.2 | CI must pass: format, compile-all, Tier-1, Tier-2 + coverage, KPI, ethereum/tests, assembly | `enforced` | `gate:ci-test-build` |
| V.3 | Docker images build | `enforced` | `gate:docker-build` |
| V.4 | ≥1 approving review; all conversations resolved | `aspirational` | GitHub branch protection (configured outside the repo). Declared in `.github/gates.yml` → `required_contexts` and documented in `.github/BRANCH_PROTECTION.md`; the repo cannot verify the setting. |
| V.5 | A red build is never merged by lowering a gate or deleting a test | `enforced` | `gate:gate-integrity` — undeclared checks, required-but-unfailable gates, and undated exclusions all fail. This is the rule v0.8.0 broke; it now has a mechanism. |
| V.6 | Hive + comprehensive as a hard release gate | `aspirational` | **Scheduled, not yet true.** No Hive suite is `required` today — see `docs/STATUS.md` and `.github/gates.yml` → `first_required_slice` (#1402, target 2026-12-31). Declaring it enforced before a suite can pass would recreate the defect. |

## Principle VI — Security & Operational Safety

| # | Rule | Status | Enforced by / substitute |
|---|---|---|---|
| VI.1 | No secrets, keys, keystores, `.env` committed | `aspirational` | GitHub secret scanning + `.gitignore`. **Deferred mechanisation:** add a pre-merge secret-scan gate; owner realcodywburns, target 2027-03-31. |
| VI.2 | JSON-RPC defaults to private/localhost binding | `aspirational` | Human: review of `src/main/resources/conf/**`. Mechanisable as a config assertion spec; not yet written. |
| VI.3 | Dependency/CVE updates applied promptly; SBOM + signed artifacts | `aspirational` | `dependency-check.yml` runs with `continue-on-error: true` — it reports, it does not gate. Release signing is in `release.yml`. |
| VI.4 | Crypto / key / network-surface changes get explicit security consideration | `aspirational` | Human: review + `/security-review`. |

## Principle VII — Transparent Versioning & Decision Records

| # | Rule | Status | Enforced by / substitute |
|---|---|---|---|
| VII.1 | Semantic versioning, `version.sbt` the single source | `enforced` | `gate:gate-integrity` — check C10 fails on a second versioning scheme (FR-019) |
| VII.2 | Conventional commit prefixes, atomic, issue-referencing | `aspirational` | Human: review. `pr-management.yml` provides advisory checks. |
| VII.3 | Significant decisions recorded as ADRs under `docs/adr/` | `aspirational` | Human: review. |
| VII.4 | Breaking changes flagged in PR and changelog | `aspirational` | Human: review. |

---

## Deferred mechanisation register

Every `aspirational` row above that is *mechanisable but not yet mechanised* is listed here
with an owner and a date, so "aspirational" cannot quietly become "forever" (FR-020).

| Rule | What would enforce it | Owner | Target |
|---|---|---|---|
| III.2 | `scalafix` `DisableSyntax` rule banning `Thread.sleep` in test sources | realcodywburns | 2026-12-31 |
| IV.2–IV.5 | `scalafixAll` in the CI path, reached via the warning-ratchet protocol | realcodywburns | 2027-03-31 |
| IV.4 | grep gate on `io.iohk` / `mantis` package and config references | realcodywburns | 2027-03-31 |
| V.6 | promotion of the `first_required_slice` gates to `required` | realcodywburns | 2026-12-31 |
| VI.1 | pre-merge secret-scan gate | realcodywburns | 2027-03-31 |
| VI.2 | config assertion spec for RPC default binding | realcodywburns | 2027-03-31 |
| VI.3 | `dependency-check.yml` without `continue-on-error` | realcodywburns | 2027-03-31 |

## Applying the required contexts

The `required_contexts` list in `.github/gates.yml` is the source of truth for branch
protection. The repository cannot read or verify its own GitHub settings, so a maintainer
with admin rights applies it — see `.github/BRANCH_PROTECTION.md` for the `gh api` invocation.
