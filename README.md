<div align="center">
  <img src="https://raw.githubusercontent.com/chippr-robotics/fukuii/HEAD/docs/images/fukuii-hex-logo.png" alt="Fukuii Logo" width="400"/>
</div>

# 🧠🪱 Fukuii Ethereum Virtual Machine Client

### The multi-EVM execution client. From home miners to enterprise infrastructure.

Fukuii is an execution layer client for EVM-compatible networks — an independent continuation of IOHK's Mantis client, maintained by Chippr Robotics LLC. Built as production-grade infrastructure for the full range of EVM deployment scenarios.

**Build & quality**

[![CI](https://github.com/chippr-robotics/fukuii/actions/workflows/ci.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/ci.yml)
[![Docker Build](https://github.com/chippr-robotics/fukuii/actions/workflows/docker.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/docker.yml)
[![Nightly Build](https://github.com/chippr-robotics/fukuii/actions/workflows/nightly.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/nightly.yml)
[![Dependency Check](https://github.com/chippr-robotics/fukuii/actions/workflows/dependency-check.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/dependency-check.yml)
[![codecov](https://codecov.io/gh/chippr-robotics/fukuii/graph/badge.svg)](https://codecov.io/gh/chippr-robotics/fukuii)

**Release & docs**

[![Release](https://github.com/chippr-robotics/fukuii/actions/workflows/release.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/release.yml)
[![Fast Distro](https://github.com/chippr-robotics/fukuii/actions/workflows/fast-distro.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/fast-distro.yml)
[![Docs Preview](https://github.com/chippr-robotics/fukuii/actions/workflows/docs-preview.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/docs-preview.yml)
[![GitHub Pages](https://github.com/chippr-robotics/fukuii/actions/workflows/gh-pages.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/gh-pages.yml)

**Project metadata**

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Scala](https://img.shields.io/badge/Scala-3.3.7%20LTS-DC322F?logo=scala&logoColor=white)](https://www.scala-lang.org/)
[![JDK](https://img.shields.io/badge/JDK-25%20LTS-orange?logo=openjdk&logoColor=white)](https://adoptium.net/)
[![Latest Release](https://img.shields.io/github/v/release/chippr-robotics/fukuii?include_prereleases&sort=semver)](https://github.com/chippr-robotics/fukuii/releases)
[![Docker Pulls](https://img.shields.io/docker/pulls/chipprbots/fukuii.svg)](https://hub.docker.com/r/chipprbots/fukuii)

---

## Why Fukuii

- **No network lock-in** — one binary runs ETC mainnet, Ethereum mainnet, testnets, and custom chains. Eliminate the operational overhead of running separate clients for separate networks.
- **Consensus-agnostic** — PoW for ETC, PoS via Engine API for Ethereum, PoA for enterprise consortium chains, custom derivation for L2s. The client adapts to the network; the network does not dictate the client.
- **Future-proof** — as networks evolve and new consensus mechanisms emerge, the pluggable architecture absorbs them without a rewrite.
- **Verifiable artifact chain** — every release ships with SLSA Level 3 provenance, a CycloneDX SBOM, and a Cosign keyless signature. Operators under SOC2 audit or institutional procurement can verify exactly what they're running, from source commit to deployed container.
- **Client diversity** — Scala/JVM is a distinct implementation with a different dependency tree and failure profile. Risk frameworks requiring multiple independent client implementations can deploy Fukuii without running two instances of the same codebase.

## Who Uses Fukuii

From a home mining rig to multi-region enterprise infrastructure — same binary, same API, same configuration model.

- Home miners running ETC on consumer hardware
- Professional miners and mining pool operators
- ETC and Ethereum node operators and infrastructure providers
- Ethereum stakers running a self-hosted execution layer
- Centralized exchanges running deposit and withdrawal nodes
- Custodians requiring auditable infrastructure software
- Institutional staking operators
- Oracle networks and on-chain data providers
- Cross-chain bridge operators
- Block explorers and blockchain data indexers
- RPC endpoint providers serving developers and applications
- Enterprise blockchain teams running multi-network infrastructure
- L2 development teams deploying custom EVM chains
- Self-custody users running a local node to verify their own transactions

---

## Origin

> *Chordodes Fukuii is a parasitic worm that hijacks a mantis, rewires its brain, and drives it toward water. Fukuii hijacks Mantis (IOHK's abandoned Scala ETC client), rewires the codebase, and drives it toward consensus.*

*Chordodes Fukuii* is a nematomorph parasite. It infects a mantis, takes over its nervous system, and compels the host toward water — where the worm completes its lifecycle. The mantis becomes a vehicle for something else entirely.

Input Output (HK) built Mantis as a Scala client for the Ethereum Classic network, then abandoned it. Chippr Robotics LLC took the codebase, rewired it, and drove it to production.

---

## What Makes Fukuii Different

### Multi-EVM Execution

One binary, every major EVM network:

- **ETC Mainnet** — full PoW node with SNAP sync
- **Ethereum Mainnet** — full PoS execution layer via Engine API
- **Sepolia, Holesky** — Ethereum testnets
- **Mordor** — ETC testnet
- **Private and consortium chains** — custom genesis, no source modification required
- **EVM-compatible L2s** — chains configured via custom genesis and derivation rules

A complete PoW node, an Engine API execution layer for PoS, or a configurable base for private networks, consortium chains, and L2 deployments.

Run ETC mainnet and Ethereum mainnet concurrently from the same binary, the same configuration model, and the same monitoring stack. Deposit monitoring for multiple networks, served by a single operational team. Deploy private consortium chains alongside public networks without duplicating infrastructure.

Built on **Scala 3 LTS** and **JDK 25 LTS**. Execution logic runs on an **Apache Pekko** actor system — reactive actors with bounded dispatchers, generation tokens, and phase gates.

### Pluggable Consensus

Three layers separate execution from consensus:

1. **fukuii-core** — Consensus-agnostic EVM execution, state storage, JSON-RPC
2. **fukuii-env** — Per-network parameters: chain ID, genesis, fork schedules, gas mechanics
3. **Consensus Module** — Swappable backends: PoW, Engine API, PoA, OP-style derivation, ZK verification, Orbita sidechain

Deployment scenarios the architecture enables:

- **PoW** — ETC mainnet and Mordor; compatible with GPU rigs, ASICs, and mining pools
- **PoS** — Ethereum mainnet and testnets via Engine API + any CL client
- **PoA** — Enterprise consortium chains via configurable consensus module
- **L2 / custom** — Derivation pipelines, ZK verification, checkpoint-based sidechains

For regulated industries: PoA consortium deployments satisfy jurisdictional requirements for permissioned blockchain infrastructure. The pluggable architecture absorbs future compliance requirements — ZK verification, permissioned state, privacy layers — without replacing the client.

The design draws from ETCDEV's Orbita proposal — a generalized sidechain architecture for ETC presented at ETC Summit 2018 — extended to cover the full range of modern consensus backends. Full architectural vision: [Pluggable Consensus & Multi-Network Architecture](docs/architecture/pluggable-consensus-vision.md).

### MESS — Chain Security for ETC

Modified Exponential Subjective Scoring (ECIP-1100) is Fukuii's implementation of ETC's finality mechanism. It protects against deep reorgs by requiring exponentially increasing proof-of-work to override older blocks — a block from two hours ago requires roughly 31x more work to displace than the chain tip.

For ETC miners and node operators, MESS provides finality-like guarantees without proof-of-stake. Exchange operators can apply shorter confirmation windows. Infrastructure providers get protection against the 51% attacks that have historically targeted ETC.

### SNAP Sync

Six-phase state download pipeline:

1. **Account Harvest** — Account states downloaded from SNAP peers, Merkle-proof verified
2. **Bytecode Fetch** — Contract bytecodes fetched in parallel with storage
3. **Storage Download** — Contract storage with multi-account batching
4. **Trie Healing** — Node reconciliation at chunk boundaries after pivot refreshes
5. **Validation** — Full trie walk to verify completeness
6. **Chain Assembly** — Block headers, bodies, and receipts from genesis to pivot

Three pruning modes are available: **Archive** retains full historical state from genesis (required for exchange deposit verification and block explorer indexing), **Basic** retains a configurable window of recent state, and **InMemory** for ephemeral testing environments.

The implementation optimizes for throughput at each phase:

| Optimization | Impact |
|---|---|
| **Deferred Merkleization** | Zero CPU on trie construction during download — flat storage only |
| **Flat Slot Storage** | O(1) SLOAD during EVM execution |
| **StackTrie Shortcut** | Skip MPT entirely for ~95% of contracts |
| **Two-Phase Storage** | Buffer raw slots, sort by key, build tries on bounded thread pool |
| **Adaptive Batching** | Request window scales up and down per peer based on response size and latency |
| **Binary Stateless Detection** | Instant pivot refresh when all peers lose state |
| **Deferred Write MPT** | ~200x speedup for account trie insertion |

### Ethereum Compliance

The Ethereum Foundation [Hive](https://github.com/ethereum/hive) simulator suite is the industry standard for EL client compliance testing. Each simulator runs independently — a failing suite is immediately visible in the badge wall below, not buried in a monolithic pass/fail. Nightly reference tests from the Ethereum Foundation run across the full ETC and Ethereum fork schedules.

**Ethereum compliance — Hive simulators**

[![Hive · smoke-genesis](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-smoke-genesis.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-smoke-genesis.yml)
[![Hive · smoke-network](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-smoke-network.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-smoke-network.yml)
[![Hive · rpc](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-rpc.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-rpc.yml)
[![Hive · rpc-compat](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-rpc-compat.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-rpc-compat.yml)
[![Hive · graphql](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-graphql.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-graphql.yml)
[![Hive · devp2p](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-devp2p.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-devp2p.yml)
[![Hive · sync](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-sync.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-sync.yml)
[![Hive · consensus](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-consensus.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-consensus.yml)
[![Hive · pyspec](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-pyspec.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-pyspec.yml)
[![Hive · engine](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-engine.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-engine.yml)
[![Hive · consume-engine](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-consume-engine.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-consume-engine.yml)
[![Hive · consume-rlp](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-consume-rlp.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-consume-rlp.yml)
[![Hive Prague Suite](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-prague.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/hive-prague.yml)
[![Ethereum Tests](https://github.com/chippr-robotics/fukuii/actions/workflows/ethereum-tests-nightly.yml/badge.svg)](https://github.com/chippr-robotics/fukuii/actions/workflows/ethereum-tests-nightly.yml)

| Suite | What it verifies |
|---|---|
| `smoke-genesis` | Genesis block consistency |
| `smoke-network` | Basic network connectivity |
| `rpc` | JSON-RPC API correctness |
| `rpc-compat` | Cross-client RPC compatibility |
| `graphql` | GraphQL API |
| `devp2p` | Wire protocol handshake and framing |
| `sync` | Chain sync correctness |
| `consensus` | Ethereum consensus test vectors |
| `pyspec` | Python-based spec conformance |
| `engine` | Engine API request/response |
| `consume-engine` | Engine payload consumption |
| `consume-rlp` | RLP encoding correctness |
| `prague` | Prague/Electra hard fork suite |

### JSON-RPC and API Surface

Broad namespace coverage across `eth_`, `debug_`, `trace_`, `txpool_`, `admin_`, `personal_`, `miner_`, `net_`, `web3_`, and `engine_` — the API breadth production operators expect.

- **HTTP and WebSocket** — both transports on configurable ports. WebSocket supports `eth_subscribe` for real-time block headers, logs, and pending transaction subscriptions. `debug_traceChain` streams results over WebSocket.
- **GraphQL** — full block, transaction, account, and log queries via the `/graphql` endpoint
- **Trace API** — `trace_*` namespace with Parity-compatible transaction and block tracing; required by bridge operators, DeFi infrastructure, and MEV analysis tooling
- **txpool introspection** — `txpool_content`, `txpool_status`, and Besu-compatible extensions for mining pool integration
- **IPC** — Unix domain socket for low-latency local connectivity from applications on the same host
- **SSL/TLS** — HTTPS and WSS with PEM, PKCS12, or JKS keystore for encrypted RPC endpoints

### Agentic Control via MCP

Operations teams query node state through Claude or GPT directly — no custom dashboards required. Compliance teams pull reorg history on demand. Infrastructure teams automate capacity planning through natural language.

Fukuii is the first EVM execution client with a functional MCP server — built into the JSON-RPC API, not bolted on. Enable by adding `"mcp"` to `fukuii.network.rpc.apis`. Works with any MCP-compatible AI assistant on port 8545.

**Operational** — node status, sync progress, peer list, fork history, chain config

**Investigative** — block, transaction, and account queries; reorg detection; emission schedule

**Compliance** — historical fork history, unit conversion, chain config audit

**9 live resources** — node status, config, sync state, peer list, latest block, plus URI-templated access (`fukuii://block/123`, `fukuii://tx/0x...`, `fukuii://account/0x...`)

**Guided prompts** — pre-built templates for health checks, troubleshooting, and operational tasks

See [MCP Documentation](docs/MCP.md).

---

## Enterprise Deployment

The Barad-dûr reference deployment is the enterprise topology: Kong API gateway, Prometheus metrics collection, Grafana dashboards, and a dual-node configuration running ETC mainnet and Mordor concurrently. See [`ops/barad-dur/`](ops/barad-dur/) for the full stack.

**Observability**
- Prometheus metrics across all sync phases, peer health, and system resources
- Grafana dashboards ship with the Barad-dûr deployment (`ops/barad-dur/grafana/dashboards/`)
- Kubernetes-ready liveness and readiness probes on port 8546
- Structured logging with configurable levels (Logback)

**High Availability**
- Kong API gateway provides load balancing and health-check-based routing across nodes in the Barad-dûr topology
- Kubernetes liveness and readiness probes enable orchestrator-managed restart and traffic routing
- State persists across restarts — RocksDB on named volumes survives container lifecycle events
- Dual-node configuration provides network redundancy and rolling maintenance capability

**Endpoint Security**
- JWT authentication required for Engine API endpoints
- RPC API namespace exposure is configurable — restrict to only the namespaces a deployment requires
- Kong handles TLS termination and IP allowlisting at the gateway layer
- Container images run as non-root with minimal attack surface

**Supply Chain Security**
- SLSA Level 3 provenance attestations on all release artifacts
- Cosign keyless image signing (GitHub OIDC — no long-lived signing keys)
- Software Bill of Materials in CycloneDX format attached to every release
- Weekly automated dependency monitoring
- CodeQL static analysis on every push

For SOC2-audited operators: SLSA provenance, SBOM, and signed artifacts support evidence collection for software integrity and change management controls. Structured logging integrates with SIEM systems.

**Operational patterns**

| Environment | Purpose | Network |
|---|---|---|
| **Barad-dûr** 🏰 | Production — Kong, Prometheus, Grafana, dual-node | ETC Mainnet + Mordor |
| **Cirith Ungol** 🕷️ | Staging — dual-node, rapid iteration | Mordor / ETC |
| **Gorgoroth** 🌋 | Private — 3-node cluster, integration tests | Private |

**Hardware sizing**

| Deployment | RAM | Storage | Use case |
|---|---|---|---|
| Home node | 8 GB | 500 GB | ETC mainnet, single network |
| Professional miner | 16 GB | 1 TB | ETC mainnet + pool infrastructure |
| Enterprise node | 32–64 GB | 2–4 TB | Multi-network, high RPC load |
| Archive node | 64 GB+ | 4 TB+ | Full history, indexing workloads |

RocksDB column families, WAL tuning, and memory budgets are configurable per deployment. See [Disk Management](docs/runbooks/disk-management.md) and [Docker Documentation](docs/deployment/docker.md) for Compose examples, image variants, multi-environment configuration, and Kubernetes deployment patterns.

---

## Home Miner Setup

ETC's identity is PoW with accessible home mining. Fukuii is built with that audience as a first-class deployment target.

### fukuii-gui

**[fukuii-gui](https://github.com/chippr-robotics/fukuii-gui)** is a native desktop application for self-custody asset management and node operation — built on the same Qt/QML model as Monero GUI, cross-platform across Windows, Linux, and macOS.

- **Hardware wallet support** — Ledger (Nano S, Nano S Plus, Nano X, Nano Gen 5, Stax, Flex) and Trezor (Model T, Safe 3, Safe 5)
- **Self-custody wallet** — mnemonic seed backup, view-only wallet support, offline transaction signing
- **Send and receive** — address book, QR codes, payment requests, transaction history
- **Node management** — sync monitoring, peer management, local or remote node configuration
- **Mining controls** — solo mining and P2Pool integration

Not just for miners — fukuii-gui is for anyone who wants to hold and transact ETC or ETH against their own node rather than trusting a third-party RPC endpoint.

### CLI Setup

- **Binaries for all major operating systems** — Windows, Linux, and macOS builds are included in every release. Download, extract, run. No build toolchain required.
- **Consumer GPU support** — Etchash mining on standard GPU rigs
- **ASIC compatible** — Antminer, iPollo, Jasminer, Bombax hardware
- **Mining pool integration** — standard stratum protocol; compatible with Ethermine, 2Miners, and other major ETC pools
- **Bootstrap checkpoints** — begin syncing immediately without waiting for peer consensus
- **Low memory floor** — runs on consumer hardware

Download the latest release from the [Releases page](https://github.com/chippr-robotics/fukuii/releases) and see the [Quick Start Guide](.github/QUICKSTART.md) for the 3-step setup.

---

## Getting Started

> **ALPHA SOFTWARE — DO NOT USE IN PRODUCTION**

### Option 1: Docker (Recommended)

```bash
# GHCR is the primary registry — all images are SLSA-signed with provenance attestations
docker pull ghcr.io/chippr-robotics/fukuii:<version>

# Verify the image signature
cosign verify \
  --certificate-identity-regexp=https://github.com/chippr-robotics/fukuii \
  --certificate-oidc-issuer=https://token.actions.githubusercontent.com \
  ghcr.io/chippr-robotics/fukuii:<version>

# Run
docker run -d --name fukuii \
  -p 8545:8545 -p 8546:8546 -p 30303:30303 \
  -v fukuii-data:/app/data -v fukuii-conf:/app/conf \
  ghcr.io/chippr-robotics/fukuii:<version>
```

Docker Hub (`chipprbots/fukuii:latest`) is also available as a mirror for environments where GHCR is restricted.

See [Docker Documentation](docs/deployment/docker.md) for Compose examples, image variants, and security configuration.

### Option 2: Pre-built Binary

1. Download `fukuii-<version>.zip` from the [Releases page](https://github.com/chippr-robotics/fukuii/releases)
2. Extract: `unzip fukuii-<version>.zip && cd fukuii-<version>`
3. Run: `./bin/fukuii etc` (or `mordor`, `gorgoroth`, `test`)

Builds for Windows, Linux, and macOS are included in every release. Also available: `fukuii-assembly-<version>.jar` for `java -jar` execution (requires JDK 25+).

### Option 3: Build from Source

```bash
git submodule update --init --recursive
sbt dist
```

The distribution archive is placed under `target/universal/`. See [Contributing Guide](CONTRIBUTING.md) for development environment setup.

---

## Sync Architecture

```
🪱[====..............]🧠
  ↑                    ↑
  The Worm             The Brain
  (sync progress)      (assembled state)
```

See [SNAP Sync](#snap-sync) above for the full six-phase pipeline and optimization table. Prometheus metrics and the progress display track each phase in real time.

---

## Operations and Maintenance

- [First Start](docs/runbooks/first-start.md)
- [Metrics & Monitoring](docs/operations/metrics-and-monitoring.md)
- [Security](docs/runbooks/security.md)
- [Peering](docs/runbooks/peering.md)
- [Disk Management](docs/runbooks/disk-management.md)
- [Backup & Restore](docs/runbooks/backup-restore.md)
- [Log Triage](docs/runbooks/log-triage.md)
- [Known Issues](docs/runbooks/known-issues.md)

See [Operations Runbooks](docs/runbooks/README.md) for complete operational documentation.

---

## Roadmap

**Home miners and self-custody users**
- **[fukuii-gui](https://github.com/chippr-robotics/fukuii-gui)** — hardware wallet support (Ledger, Trezor), self-custody wallet, cross-platform desktop app *(active development)*
- Mining pool partnerships and validation program *(in progress)*
- Hardware vendor testing — ASICs and GPU rigs *(planned)*

**Node operators**
- Enhanced RPC compatibility audit against reference client test suites
- Expanded MCP tool surface and resource coverage *(planned)*

**Architecture**
- fukuii-core / fukuii-env architectural split enabling Orbita sidechain support *(planned)*
- Additional consensus modules — PoA, OP-style derivation *(planned)*

---

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for environment setup, code standards, pre-commit hooks, and PR guidelines. When modifying code derived from Mantis, include a notice in changed file headers with your own copyright line.

For contributors: run `sbt pp` before submitting a PR to check formatting, style, and tests locally.

**Quick links:**
- [Documentation Site](https://chippr-robotics.github.io/fukuii/)
- [Documentation Index](docs/index.md)
- [Quick Start Guide](.github/QUICKSTART.md)

---

## Important Notes

<b>Licence:</b> This project continues to be distributed under the Apache 2.0 licence. A copy of the licence is included in the LICENSE file. The original NOTICE file from IOHK is preserved as required by the licence, and Chippr Robotics LLC has added its own attribution.

<b>Origin:</b> Fukuii is derived from the Mantis client. Mantis is a trademark of IOHK; we use the name here only to describe the origin of this fork.

## Contact

For questions or support, reach out to Chippr Robotics LLC via our GitHub repository.
