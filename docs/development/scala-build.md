# Scala 3 build layout

This document explains how the Fukuii build works after the Scala 3 migration and what tooling is actually required inside the dev container or any local environment.

## Toolchain

| Tool | Version | Purpose |
| --- | --- | --- |
| JDK | 25 (Temurin) | Runtime/toolchain for Scala 3 and Pekko |
| sbt | 1.13.0 | Single build driver (no nested builds) |
| Scala | 3.9.0 (LTS), `-source:3.9` | Only Scala version compiled in this repo |
| scalafmt / sbt-scalafmt | 3.8.3 / 2.6.2 | Formatting (invoked via sbt); 3.8.3 is pinned in `.scalafmt.conf` |
| sbt-scalafix | 0.14.9 (scalameta 4.17.4) | Linting and rewrites (invoked via sbt) |
| protobuf (protoc) | >= 3.21 | Generates sources via `sbt-protoc` |
| solc | 0.8.x | Solidity compiler for `solidityCompile` task |
| Metals (VS Code) | 1.6.x | Language server only; not part of the published build |

Everything else (Sonar, Zinc experiments, duplicate builds) has been removed so the build graph is deterministic.

## Project graph

```
root (node)
├── bytes
├── crypto  (depends on bytes)
├── rlp     (depends on bytes)
├── scalanet
└── scalanet-discovery (depends on scalanet)
```

Each sub-module inherits `commonSettings` defined in `build.sbt`, which sets:

- Scala 3.9.0, fatal warnings disabled when `FUKUII_DEV=true`
- shared scalac options and test settings
- scalafix dependencies (organize-imports)
- cross compilation configs for Integration/Evm/Rpc/Benchmark

`project/Dependencies.scala` is the single source of truth for library versions (Pekko, Cats, Circe, RocksDB, etc.). Keep it updated there only; the main build file consumes those values exclusively.

## Compiler flags and the warning ratchet

`scala3Options` in `build.sbt` carries the Scala 3 flags:

- `-source:3.9`. It replaced `-source:future` in spec 020 (ADR INF-009 gives the reason). `import x.*` is enforced instead
  by the scalafix `DisableSyntax.regex` rules and by `scripts/ci/check_scala_imports.sh` in CI.
- From `-source:3.8` the compiler elaborates `for` comprehensions by SIP-62 ("better fors"). No rewrite or warning
  announces it. See "The `-source:3.9` section" of `.claude/agent-protocols/scala3-style.md` and
  `specs/020-scala-3-9-lts-migration/dc4-ledger.md`.
- `-Wunused:all`, and `-Wconf:...:error` filters that turn cleared warning categories into build errors: unused
  symbols (`E198`), unchecked patterns, and the seven categories spec 020 promoted (`E205`, `E028`, an implicit argument
  without `using`, alphanumeric infix, `_` as a type wildcard, `with` as a type operator, a type ascription after a
  pattern). In Scala 3.9.0 the **last** matching `-Wconf` wins, so a new filter goes below the existing ones.
  The ledger and the exact filters are in `warning-ratchet.md` and the spec's `dc4-ledger.md`.

## Formatting and linting

scalafmt is pinned to 3.8.3 (`.scalafmt.conf`); moving to 3.11.x is a follow-up (spec 020 FU-1). Which command covers
which module:

| Command | Covers |
| --- | --- |
| `sbt scalafmtAll`, `sbt scalafmtCheckAll` | the root project only (the root has no `aggregate`) |
| `sbt formatAll` | compile-all, then scalafix and scalafmt on `bytes`, `crypto`, `rlp` and the root, and scalafmt on `scalanet` and `scalanetDiscovery` |
| `sbt formatCheck` | the read-only form of `formatAll`; runs in CI's "Test and Build" job |
| `sbt pp` | compile-all, scalafmt on `bytes`, `crypto`, `rlp` and the root, then `rlp / test`, `testQuick`, `IntegrationTest / test`; no scalafix |

The tree has no scalafix findings, so a finding from `formatCheck` is new. scalafix 0.14.9 bundles scalameta 4.17.4,
which cannot parse a fewer-braces `new X:` opened on a continuation line (`it should "x" in` then `new X:` on the next
line); write `in (new X: ... )` instead. `compile-all` builds no scalanet or scalanetDiscovery test sources.

## sbt plugins in use

Only the following plugins remain active in `project/plugins.sbt` because they are referenced by the Scala 3 build:

- `sbt-buildinfo` – emits `BuildInfo.scala`
- `sbt-javaagent` – wires Kanela for Pekko telemetry
- `sbt-native-packager` + `sbt-assembly` – CLI and distribution packaging
- `sbt-git`, `sbt-ci-release` – release metadata and tagging
- `sbt-scalafmt`, `sbt-scalafix`, `sbt-scoverage`, `sbt-scapegoat`, `sbt-updates`, `sbt-api-mappings`
- `sbt-protoc` – invoked via `scalapb.sbt` to compile protobuf overrides

We intentionally removed auto-generated `metals.sbt` files and the recursive `project/project/...` tree to avoid the "fukuii-build-build-build" loops that broke Metals imports. Metals now runs against the regular build via BSP.

## Dev container / Metals notes

1. The dev container already installs sbt and JDK 25; you only need to run `sbt sbtVersion` once to warm the caches.
2. `.gitignore` now blocks `project/metals.sbt` and the entire `project/project/` hierarchy. If Metals asks to create those files, let it—they will appear as untracked artifacts and should stay that way.
3. To refresh Metals/BSP after dependency changes, run:

```bash
sbt "reload plugins" clean compile
```

Metals will detect the updated `.bsp/sbt.json` and re-import automatically.

## Usage checkpoints

- Format all modules: `sbt formatAll`
- Check formatting and scalafix the way CI does: `sbt formatCheck`
- Compile everything (Scala + protobuf + Solidity): `sbt compile-all`
- Run essential tests (fast suite): `sbt testEssential`
- Build the distribution artifact: `sbt dist`

Running these commands successfully is the verification gate for any build change. Keep new tools or plugins off the critical path unless they are required by the Scala 3 codebase and documented here.
