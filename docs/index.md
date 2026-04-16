# Fukuii Documentation

Welcome to the official documentation for **Fukuii**, an Ethereum Classic client written in Scala 3.

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

Fukuii is a high-performance Ethereum Classic (ETC) client built with Scala 3. It provides:

- **Full node operation** — Sync and validate the Ethereum Classic blockchain
- **JSON-RPC API** — Standard Ethereum JSON-RPC interface for dApp integration
- **Docker support** — Production-ready container images with signed releases
- **Comprehensive monitoring** — Prometheus metrics and Grafana dashboards

## Quick Links

| I want to... | Go to... |
|--------------|----------|
| Run a node quickly | [Quick Start](getting-started/quickstart.md) |
| Deploy with Docker | [Docker Guide](deployment/docker.md) |
| Configure my node | [Node Configuration](runbooks/node-configuration.md) |
| Secure my node | [Security Runbook](runbooks/security.md) |
| Understand the architecture | [Architecture Overview](architecture/architecture-overview.md) |
| Use the JSON-RPC API | [API Reference](api/JSON_RPC_API_REFERENCE.md) |
| Contribute code | [Contributing Guide](development/contributing.md) |
| Test compatibility | [Gorgoroth Network Testing](testing/GORGOROTH_COMPATIBILITY_TESTING.md) |

## Supported Networks

| Network | Chain ID | Description |
|---------|----------|-------------|
| Ethereum Classic | 61 | ETC mainnet |
| Mordor | 63 | ETC testnet |
| Ethereum | 1 | ETH mainnet (limited support) |

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
