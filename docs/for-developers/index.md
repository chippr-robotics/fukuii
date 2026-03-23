# For Developers

## Quick Setup

**Prerequisites:** JDK 21, sbt 1.10.7+, Git

```bash
git clone https://github.com/chippr-robotics/fukuii.git
cd fukuii
git submodule update --init --recursive
sbt compile
sbt test
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
| [JSON-RPC Reference](../api/JSON_RPC_API_REFERENCE.md) | 77 documented methods |
| [Coverage Analysis](../api/JSON_RPC_COVERAGE_ANALYSIS.md) | Gap analysis vs specification |
| [MCP Integration](../../MCP.md) | Model Context Protocol tools and resources |

## Development Commands

```bash
sbt compile           # Compile all modules
sbt test              # Unit tests (~5 min)
sbt it:test           # Integration tests
sbt scalafmtAll       # Format all code
sbt pp                # Pre-PR: format + style + tests
sbt assembly          # Build fat JAR
```

## Test Tiers

| Tier | Command | Duration | When |
|------|---------|----------|------|
| Essential | `sbt test` | < 5 min | Every change |
| Standard | `sbt it:test` | < 30 min | Before PR |
| Comprehensive | `sbt pp` | < 3 hr | Pre-merge |

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
| `scalanet` | P2P networking |
| `src` | Main Fukuii application |

## Before Submitting a PR

```bash
sbt scalafmtCheckAll   # Formatting check
sbt compile-all        # Compilation
sbt test               # All tests pass
```

See [Contributing Guide](../development/contributing.md) for full details.
