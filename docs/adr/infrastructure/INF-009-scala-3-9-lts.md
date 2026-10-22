# ADR-INF-009: Scala 3.9 LTS Toolchain

**Status**: Accepted

**Date**: 2026-10-20

**Deciders**: Chippr Robotics LLC Engineering Team (user decisions 2026-10-17 and 2026-10-20; spec 020)

**Amends**: the Scala version in [INF-001](INF-001-scala-3-migration.md). The JDK baseline stays as in
[INF-007](INF-007-jdk-25-baseline.md).

## Context

Scala 3.9.0 (2026-09-03) opened a new Long Term Support line, with at least three years of maintenance. The 3.3 LTS
line fukuii builds on (3.3.8) is maintained for about a year after that, to roughly 2027-09, with fewer backports.
Staying on 3.3 is safe until then and not after.

Three roadmap specs add new code: 017, 018 and 019. Spec 019's TASTy-reading translator is tied to the TASTy version it
is built against, and the TASTy format follows the compiler minor version. Migrating after those specs would mean
writing their code against 3.3, then migrating it, and writing the translator twice.

The audit is in `docs/reports/LTS_TOOLCHAIN_AUDIT_2026-10.md`. Spec 020's GT-1 inventory
(`specs/020-scala-3-9-lts-migration/gt1-inventory.md`) sized the move:
- 8 hand fixes;
- two `-source` steps that change source: 3.4 (16 files) and 3.7 (514 `using` insertions in 187 files);
- 371 given-prioritisation sites, 364 of them from one duplicated-`given` pattern in `RLPImplicits`;
- 91 warnings on the final tree.

## Decision

1. **Adopt the Scala 3.9 LTS line** with spec 020. The build moves 3.3.8 → 3.9.x through the
   `-source:3.4-migration` … `3.9-migration` rewrite steps, one step per commit, run on the 3.9 compiler. sbt is
   1.13.0 (#1631).
2. **Use `-source:3.9`, not `-source:future`.** `future` on 3.9 would cost about 1,541 implicit-to-given sites after a
   63-file syntax rewrite, which would turn a toolchain move into a full given/using rewrite. What `future` was kept
   for, namely the `import x.*` wildcard syntax, is enforced instead by a scalafix `DisableSyntax.regex` rule plus a CI
   grep (#1632).
3. **Sequence: spec 016, then 020, then 017** (spec 018 and 019 after). No spec 017 implementation PR opens before
   020 lands (GT-0). The stable 0.10.0 release shipped on 3.3.8 first (user decision 2026-10-20). 020 lands in the
   0.11.0 line.
4. **Stay on scalafmt 3.8.3 for spec 020.** Moving to 3.11.x reformats up to 96 test files, which DC-8 forbids
   hand-editing inside a behaviour-neutral migration. That move is follow-up FU-1, before spec 017's first PR.
5. **Clear the accumulated scalafix findings inside spec 020 and gate them in CI.** These are #1656: 209 files of
   automatic import/syntax fixes, 58 `DisableSyntax.return` findings (51 in main code) and 37 files scalameta cannot
   parse. User decision 2026-10-20: they are part of the implementation. `formatCheck` becomes a required CI step, so
   findings cannot build up again.
6. **Consensus safety is shown, not assumed.** Each consensus-touching PR imports the same fixed ranges with a 3.3 jar
   and a 3.9 jar onto the same frozen datadir: Mordor 17,207,612–17,212,611 and Ephemery-164 135,638–147,609. The
   canonical headers' state and receipt roots are the oracle. `[IMPORT-TIMING]` p50 and p99 must stay within 10% of 3.3.

## Consequences

- **TASTy break for downstream consumers.** The five published modules (`bytes`, `crypto`, `rlp`, `scalanet`,
  `scalanet-discovery`) carry Scala 3.9 TASTy from 0.11.0. Projects still on 3.3 cannot consume them. The release notes
  must say so (DC-7).
- The required CI check on `main` is renamed from "Test and Build (JDK 25, Scala 3.3.8)" to the 3.9 name, and the
  ruleset must be updated in the same step.
- New code from spec 017 on is written once against 3.9. Spec 019 targets 3.9 TASTy.
- `scalafix` and `scalafmt` both run as CI gates on every module once spec 020's ratchet PR lands.

## References

- Spec: `specs/020-scala-3-9-lts-migration/` (spec, plan, research §B baseline, GT-1 inventory)
- Constitution 1.1.1 (technology section)
- Issue #1656 (scalafix findings), PRs #1631 (sbt 1.13.0) and #1632 (import guard)
