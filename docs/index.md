# Fukuii Documentation

Welcome to the official documentation for **Fukuii**, an EVM-compliant Ethereum execution layer (EL) client written in Scala 3, with pluggable consensus.

<div class="grid cards" markdown>

-   :rocket: **Getting Started**

    ---

    New to Fukuii? Start here to get your node up and running.

    [:octicons-arrow-right-24: Quick Start](getting-started/index.md)

-   :person_running: **For Node Operators**

    ---

    Running a Fukuii node? Find configuration, security, and maintenance guides.

    [:octicons-arrow-right-24: Node Operations](for-node-operators/index.md)

-   :gear: **For Operators/SRE**

    ---

    Deploy and monitor Fukuii in production environments.

    [:octicons-arrow-right-24: Operations Guide](for-operators/index.md)

-   :wrench: **For Developers**

    ---

    Contributing to Fukuii or building on top of it? Find architecture docs and development guides.

    [:octicons-arrow-right-24: Developer Guide](for-developers/index.md)

</div>

## What is Fukuii?

Fukuii is an EVM-compliant execution layer client built with Scala 3. Originating as a fork of Mantis for Ethereum Classic, it has evolved into a general-purpose EVM engine that participates in any consensus model via a pluggable three-layer architecture (`fukuii-core` / `fukuii-env` / consensus module — see [Pluggable Consensus Vision](architecture/pluggable-consensus-vision.md)). It provides:

- **Dual PoW/PoS operation** — Native Ethash mining for Ethereum Classic and Mordor, plus Engine API V1–V4 (through Prague/Electra) for post-Merge Ethereum networks paired with any CL client (Lighthouse, Prysm, Teku, Lodestar, Nimbus)
- **Full EVM through Prague/Electra** — All mainstream EIPs supported, including EIP-1559, EIP-3855 (PUSH0), EIP-4844 (blob transactions), EIP-4895 (withdrawals), EIP-4788 (beacon root), EIP-7685 (execution requests), plus ETC's ECIP-1066 hard-fork schedule through Olympia (ECIP-1111/1112/1121)
- **Multi-mode sync** — SNAP, fast, and regular sync for PoW chains; optimistic block import via Engine API for PoS chains
- **JSON-RPC API** — `eth_*`, `net_*`, `web3_*`, `debug_*`, `trace_*`, `admin_*`, `txpool_*`, `personal_*`, `engine_*` (authrpc), plus MCP 2025-11-25 for agentic AI control
- **Hive-verified compliance** — Full Ethereum Foundation Hive simulator suite (`smoke`, `rpc`, `graphql`, `devp2p`, `sync`, `consensus`, `pyspec`, `engine`, `consume-engine`, `consume-rlp`) runs per-simulator in CI
- **Docker support** — Production-ready container images with signed releases and SLSA provenance
- **Comprehensive monitoring** — Prometheus metrics, Grafana dashboards, health/readiness endpoints

## Quick Links

| I want to... | Go to... |
|--------------|----------|
| Run a node quickly | [Quick Start](getting-started/quickstart.md) |
| Deploy with Docker | [Docker Guide](deployment/docker.md) |
| Configure my node | [Node Configuration](runbooks/node-configuration.md) |
| Secure my node | [Security Runbook](runbooks/security.md) |
| Understand the architecture | [Architecture Overview](architecture/architecture-overview.md) |
| Understand the pluggable-consensus design | [Pluggable Consensus Vision](architecture/pluggable-consensus-vision.md) |
| Use the JSON-RPC API | [API Reference](api/JSON_RPC_API_REFERENCE.md) |
| Run as an Engine API execution layer | [API Reference — Engine API section](api/README.md) |
| Contribute code | [Contributing Guide](development/contributing.md) |
| Test compatibility | [Gorgoroth Network Testing](testing/GORGOROTH_COMPATIBILITY_TESTING.md) |

## Supported Networks

| Network | Chain ID | Consensus | Status |
|---------|----------|-----------|--------|
| Ethereum Classic | 61 | PoW (Ethash) | Full sync (SNAP / fast / regular) |
| Mordor | 63 | PoW (Ethash) | Full sync (SNAP / fast / regular) |
| Sepolia | 11155111 | PoS (Engine API) | Validated — 21+ EL peers, Lighthouse CL |
| Ethereum Mainnet | 1 | PoS (Engine API) | Configuration available |

## Documentation Organization

This documentation is organized by audience:

- **[Getting Started](getting-started/index.md)** — Installation and first-run guides
- **[For Node Operators](for-node-operators/index.md)** — Day-to-day node operation
- **[For Operators/SRE](for-operators/index.md)** — Production deployment and monitoring
- **[For Developers](for-developers/index.md)** — Architecture, contributing, and API docs
- **[Reference](specifications/README.md)** — Specifications, ADRs, and technical details
- **[Troubleshooting](troubleshooting/README.md)** — Common issues and solutions

## Community

- [GitHub Repository](https://github.com/chippr-robotics/fukuii)
- [GitHub Issues](https://github.com/chippr-robotics/fukuii/issues)
- [GitHub Discussions](https://github.com/chippr-robotics/fukuii/discussions)

---

*Built with :heart: by Chippr Robotics LLC*
