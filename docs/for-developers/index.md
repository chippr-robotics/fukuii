# For Developers

## Quick Setup

**Prerequisites:** JDK 25, sbt 1.13.0+, Git

```bash
git clone https://github.com/chippr-robotics/fukuii.git
cd fukuii
git submodule update --init --recursive
sbt compile-all
sbt "testOnly *Foo*"
```

## Key Resources

| Resource | Description |
|----------|-------------|
| [Contributing Guide](../development/contributing.md) | How to contribute code |
| [Architecture Overview](../architecture/architecture-overview.md) | System design and components |
| [Architecture Diagrams](../architecture/ARCHITECTURE_DIAGRAMS.md) | C4 diagrams |
| [Repository Structure](../development/REPOSITORY_STRUCTURE.md) | Codebase navigation |
| [ADR Index](../adr/README.md) | Architectural decision records |

## API

| Resource | Description |
|----------|-------------|
| [JSON-RPC Reference](../api/JSON_RPC_API_REFERENCE.md) | 97 endpoints cataloged, 77 with full reference documentation |
| [Coverage Analysis](../api/JSON_RPC_COVERAGE_ANALYSIS.md) | Gap analysis vs specification |
| [MCP Integration](../MCP.md) | Model Context Protocol tools and resources |

## Development Commands

```bash
sbt compile-all       # Compile every module and its test sources (after every change)
sbt "testOnly *Foo*"  # One test class, in seconds
sbt formatAll         # scalafix + scalafmt on every module (rewrites files)
sbt formatCheck       # The read-only check CI runs
sbt pp                # Pre-PR gate: compile-all, scalafmt, rlp tests, testQuick, IntegrationTest
sbt dist              # Build the distribution zip under target/universal/
sbt assembly          # Build the standalone JAR
```

## Test Tiers

| Tier | Command | Duration | When |
|------|---------|----------|------|
| Essential | `sbt testEssential` | about 25 min | Once at the end of a change; gates every merge in CI |
| Standard | `sbt testStandard` | < 30 min | Before opening a PR; runs in CI with coverage |
| Comprehensive | `sbt testComprehensive` | < 3 hr | Release gate |
| Integration | `sbt "IntegrationTest / test"` | varies | After protocol-level changes |

What CI enforces, and what only reports, is published in [Verification Status](../STATUS.md).

## ADR Categories

| Category | Description |
|----------|-------------|
| [Infrastructure](../adr/infrastructure/README.md) | Platform, runtime, and build decisions |
| [Consensus](../adr/consensus/README.md) | Protocol and networking decisions |
| [VM](../adr/vm/README.md) | EVM and EIP implementations |
| [Testing](../adr/testing/README.md) | Testing strategy and frameworks |
| [Operations](../adr/operations/README.md) | Operational tooling decisions |

## Module Overview

All code uses the package prefix `com.chipprbots.ethereum`.

| Module | Purpose |
|--------|---------|
| `bytes` | Byte array utilities |
| `crypto` | Cryptographic operations |
| `rlp` | RLP encoding/decoding |
| `scalanet` | P2P networking (RLPx) |
| `scalanetDiscovery` | Peer discovery (discv4, discv5, ENR) |
| root (`src/`) | Main Fukuii application; `IntegrationTest`, `Evm`, `RpcTest` and `Benchmark` are configurations of it |

## Before Submitting a PR

```bash
sbt formatCheck        # scalafmt and scalafix check (what CI runs)
sbt pp                 # compile-all, formatting, rlp tests, testQuick, IntegrationTest
```

See [Contributing Guide](../development/contributing.md) for full details.
