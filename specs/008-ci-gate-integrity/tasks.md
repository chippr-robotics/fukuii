---
description: "Dependency-ordered tasks for spec 008 — CI gate integrity"
---

# Tasks: CI Gate Integrity

**Feature**: `008-ci-gate-integrity` | **Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

**Overriding rule:** nothing in this feature may declare a check `required` without evidence of a
green run. Promotion needs proof, not intent — that asymmetry is the whole point.

**Story map:** US1 = honest gates (P1) · US2 = declared matrix (P1) · US3 = waiver expiry (P1) ·
US4 = constitution enforcement (P2) · US5 = ETC/ETH isolation (P2) · US6 = public truth (P3).

## Phase 1: Foundation — the declaration

- [X] T001 Create `.github/gates.yml`: tiers, owners, `covers`, waivers, `canonical` facts,
      `required_contexts`, `first_required_slice`.
- [X] T002 Assign tiers from measured evidence, not intent — all Hive suites `informational`
      (no green `main` run to cite), legacy standalone callers `quarantined`.

## Phase 2: US1 — honest gates 🎯

- [X] T003 Add `min_tests` input to `_hive-sim.yml` (FR-002).
- [X] T004 Replace the two conditional gate steps with one terminal `if: always()` gate
      (FR-001, FR-003, FR-004, FR-005).
- [X] T005 Expose `json_count` as a job output so the gate can see whether results were parsed.
- [X] T006 Wire `min_tests` into all 11 `_hive-sim.yml` callers from the matrix.
- [X] T007 Validate the gate shell: `bash -n` plus 7 executed cases (zero results, truncated,
      hive crash, gated-subset failure, tabulation-never-ran, healthy, upstream-failure-outside-gate).
      **Result: 7/7.**

## Phase 3: US2 + US3 — the meta-check

- [X] T008 Write `scripts/ci/check_gate_integrity.py` — C1–C13, offline (FR-030).
- [X] T009 Add `.github/workflows/gate-integrity.yml`; daily schedule so expiry surfaces on its own
      day rather than ambushing an unrelated PR (FR-029).
- [X] T010 Write `scripts/ci/test_gate_integrity.py` — sandboxed mutation per check.
      **Result: 16/16.**
- [X] T011 Declare both existing `hive-sync` exclusions as dated waivers (FR-011, FR-014).

## Phase 4: US4 — constitution enforcement

- [X] T012 Write `docs/governance/constitution-enforcement.md` — every principle `enforced` or
      `aspirational`, plus a deferred-mechanisation register with owners and dates (FR-016, FR-020).
- [X] T013 C8/C9 enforce coverage and reject `enforced` claims backed by non-required gates.
- [X] T014 C10 enforces one versioning scheme (FR-019).

## Phase 5: US5 — ETC/ETH isolation

- [X] T015 Write `ChainIsolationSpec.scala` — iterates every chain in `blockchains.conf`
      (FR-021–FR-024).
- [ ] **T016 Run `sbt "testOnly *ChainIsolationSpec*"` and confirm green.** NOT DONE — this
      environment has no `sbt` and JDK 21 against a JDK 25 project. **Blocking before merge.**
- [ ] T017 Demonstrate the spec fails in the other direction: set an ETH fork timestamp on an ETC
      config in a scratch branch, confirm red, revert (SC-006).

## Phase 6: US6 — public truth

- [X] T018 Write `scripts/ci/generate_status.py`; generate `docs/STATUS.md` (FR-025).
- [X] T019 Fix `docs/index.md` — "Hive-verified compliance" → accurate wording pointing at STATUS.
- [X] T020 Fix README — remove the false "a failing suite is immediately visible in the badge wall"
      claim; add the tier caveat.
- [X] T021 Remove the dead `hive-rpc.yml` badge (FR-027).
- [X] T022 State canonical facts once in the matrix; surface them via STATUS (FR-028).
- [X] T023 Add `Verification Status` to the mkdocs nav.
- [X] T024 Update `.github/BRANCH_PROTECTION.md` to mirror `required_contexts`.

## Phase 7: Housekeeping (user request, same cleanup)

- [X] T025 Remove the `nightly-bootnode-update` job and `scripts/update-bootnodes.sh`. Verified
      before deleting: 15 consecutive weekly PRs (#1353–#1405), **zero merged**, each triggering
      the full Hive matrix. `refresh-bootnodes-dns.sh` (manual alternative) retained.
- [X] T026 Rewrite `scripts/README.md`, which documented only the removed script.

## Remaining before merge

1. **T016** — run `ChainIsolationSpec`. If it fails, the config is the finding: route through
   `forge`/`beacon`. Do not weaken an assertion to make it pass.
2. **T017** — demonstrate the isolation spec fails in the other direction.
3. Run `sbt scalafmtAll` (the new Scala file has not been formatted by the tool).
4. Apply `required_contexts` to branch protection — needs admin rights.

## Follow-up (not this PR)

- Promote the `first_required_slice` gates as they go green, each with an `evidence:` URL (#1402).
- Migrate or delete the quarantined `hive-prague.yml` / `hive-osaka.yml` standalone callers.
- Work the deferred-mechanisation register in `constitution-enforcement.md`.
