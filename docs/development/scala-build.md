# Scala 3 build layout

This document explains how the Fukuii build works after the Scala 3 migration and what tooling is actually required inside the dev container or any local environment.

## Toolchain

| Tool | Version | Purpose |
| --- | --- | --- |
| JDK | 21 (Temurin) | Runtime/toolchain for Scala 3 and Pekko |
| sbt | 1.10.7 | Single build driver (no nested builds) |
| Scala | 3.3.4 (LTS) | Only Scala version compiled in this repo |
| scalafmt / scalafix | 2.5.2 / 0.13.0 | Formatting and linting (invoked via sbt) |
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

- Scala 3.3.4, fatal warnings disabled when `FUKUII_DEV=true`
- shared scalac options and test settings
- scalafix dependencies (organize-imports)
- cross compilation configs for Integration/Evm/Rpc/Benchmark

`project/Dependencies.scala` is the single source of truth for library versions (Pekko, Cats, Circe, RocksDB, etc.). Keep it updated there only; the main build file consumes those values exclusively.

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

1. The dev container already installs sbt and JDK 21; you only need to run `sbt sbtVersion` once to warm the caches.
2. `.gitignore` now blocks `project/metals.sbt` and the entire `project/project/` hierarchy. If Metals asks to create those files, let it—they will appear as untracked artifacts and should stay that way.
3. To refresh Metals/BSP after dependency changes, run:

```bash
sbt "reload plugins" clean compile
```

Metals will detect the updated `.bsp/sbt.json` and re-import automatically.

## Usage checkpoints

- Format all modules: `sbt formatAll`
- Compile everything (Scala + protobuf + Solidity): `sbt compile-all`
- Run essential tests (fast suite): `sbt testEssential`
- Build the distribution artifact: `sbt dist`

Running these commands successfully is the verification gate for any build change. Keep new tools or plugins off the critical path unless they are required by the Scala 3 codebase and documented here.
