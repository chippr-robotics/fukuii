# Contract: build command surface

The commands named in CLAUDE.md's build table, the agent protocols and CI keep their names and meaning across the
migration. Only the toolchain under them changes. PR 7 updates CLAUDE.md so that each row stays true.

| Command | Defined in | Meaning that MUST hold after 020 | Changes in 020 |
|---|---|---|---|
| `sbt compile-all` | `build.sbt` alias | compiles `bytes`, `crypto`, `rlp` (main + test), root main + test, `Evm`, `IntegrationTest`, `RpcTest`, `Benchmark`. 0 errors. Warnings ≤ the DC-4 bound | none to the alias. The warning table changes (R-6) |
| `sbt compile` | sbt | root main only | none |
| `sbt pp` | alias | `compile-all` + scalafmtAll (bytes, crypto, rlp, root) + `rlp/test` + `testQuick` + `IntegrationTest/test` | none |
| `sbt formatAll` | alias | `compile-all` + `scalafixAll` + `scalafmtAll` on every module | scalafix version (0.14.9) and the R-2 rule. Still aborts on pre-existing root scalafix debt (CLAUDE.md already says "pre-PR on a clean codebase only") |
| `sbt formatCheck` | alias | the `--check`/`CheckAll` forms of `formatAll` | same as `formatAll`. Not green on staging before or after 020 (F-2) |
| `sbt scalafmtAll` / `scalafmtCheckAll` | sbt-scalafmt | scalafmt **3.8.3** over all modules | plugin 2.5.2 → 2.6.2; core unchanged |
| `sbt testEssential` | alias | Tier 1. Count = GT-2 baseline, 0 failures | none |
| `sbt testStandard` | alias | Tier 2. Coverage ≥ GT-2 and ≥ 70% | scoverage plugin 2.4.4 |
| `sbt testComprehensive` | alias | Tier 3 | none |
| `sbt runScapegoat` | alias | scapegoat on bytes, crypto, rlp, root | compiler plugin 3.4.1 |
| `sbt console` | sbt | REPL with the build's options | needs sbt 1.13.0 for the 3.9 REPL |
| `./scripts/fukuii-test {only,essential,quick}` | script | wrappers over the above | none |
| CI "Check formatting" | `ci.yml` | `sbt scalafmtCheckAll` | unchanged; preceded by the new import-guard step (R-2) |
| CI import guard (new step) | `ci.yml` + `scripts/ci/check_scala_imports.sh` | exit 1 on any `import x._`, `{…, _}` or `{a => b}` in `*.scala` outside `project/` | **new** in PR 2 |
| CI check contexts | `.github/gates.yml` | unchanged set (the guard is a step inside an existing job, not a new context) | none. A future split into its own job must be declared |

## Compiler options contract (`build.sbt` `scala3Options`)

| Option | Before | After |
|---|---|---|
| `-source` | `future` | `3.9` (DC-6 mapping in `scala3-style.md`) |
| `-Ykind-projector` | present | **removed** (deprecated on 3.9; 0 users) |
| `-Wconf:id=E198:error`, `-Wconf:cat=unchecked:error` | present | present |
| `-Wconf:id=E205:error`, `-Wconf:id=E028:error`, implicit-`using` msg filter `:error` | — | **added** (R-6) |
| `-Wconf:cat=feature:s`, `-language:adhocExtensions`, `-Wunused:all`, `-Xmax-inlines:64` | present | present |
