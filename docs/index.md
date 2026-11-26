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

Fukuii is an EVM execution layer client written in Scala 3, maintained by Chippr Robotics LLC. It began as a fork of
IOHK's Mantis client for Ethereum Classic and now runs two chain families from one binary:

- **Ethereum Classic and Mordor, natively** — proof of work with Ethash, block-number forks, a built-in miner, SNAP
  sync and checkpoint bootstrap.
- **Ethereum mainnet, Sepolia, Hoodi, Ephemery and Platåberget** — proof of stake behind any consensus client
  (Lighthouse, Prysm, Teku, Lodestar, Nimbus) over the Engine API, with timestamp forks.

What it provides:

- **EVM-compatible through Amsterdam** — mainnet's schedule through Osaka and the BPO2 blob fork, and the Amsterdam
  (Glamsterdam, EIP-7773) execution rules, live on Sepolia and Platåberget. See [Glamsterdam](specifications/GLAMSTERDAM.md).
- **Engine API** — `engine_newPayloadV1..V5`, `engine_forkchoiceUpdatedV1..V4`, `engine_getPayloadV1..V6`,
  `engine_getPayloadBodiesByHash/ByRange` V1 and V2, `engine_getBlobsV1/V2`, `engine_getClientVersionV1`. It is on by
  default for the chains that need a consensus client, on `localhost:8551`, with a persistent secret at
  `<datadir>/jwt.hex`.
- **Sync** — SNAP (with checkpoint bootstrap) on ETC and Mordor; block import under the consensus client on the
  Ethereum networks. Fast sync was removed.
- **JSON-RPC, GraphQL and IPC** — HTTP on 8546 and WebSocket on 8552, both on localhost. Like go-ethereum, HTTP and
  WebSocket serve `eth`, `net`, `web3` and `fukuii` until you opt in to more per transport (`network.rpc.http.apis`,
  `ws.apis`); IPC serves every production namespace. `debug`, `trace`, `txpool`, `admin`, `miner`, `personal`, and an
  [MCP server](MCP.md) are available. Health endpoints: `/health`, `/readiness`, `/healthcheck`, `/buildinfo`.
- **Operations** — a `--tui` command center, Prometheus metrics, Grafana dashboards in `ops/`, and runbooks for every
  day-to-day task.
- **Packages and images** — the `ppa:chipprbots/fukuii` Ubuntu PPA, the `fukuii` Chocolatey package, release archives
  with `SHA256SUMS.txt`, and Cosign-signed images on GHCR with provenance and SBOM attestations.
- **Verification** — what CI enforces, and what only reports, is published in [Verification Status](STATUS.md). We do
  not claim a suite is verified unless a check can fail on it.

## Quick Links

| I want to... | Go to... |
|--------------|----------|
| Install and run a node | [Quick Start](getting-started/quickstart.md) |
| Deploy with Docker | [Docker Guide](deployment/docker.md) |
| Configure my node | [Node Configuration](runbooks/node-configuration.md) |
| Secure my node | [Security Runbook](runbooks/security.md) |
| Understand the architecture | [Architecture Overview](architecture/architecture-overview.md) |
| Understand the pluggable-consensus design | [Pluggable Consensus Vision](architecture/pluggable-consensus-vision.md) |
| Use the JSON-RPC API | [API Reference](api/JSON_RPC_API_REFERENCE.md) |
| Run as an Engine API execution layer | [API Reference — Engine API section](api/README.md) |
| See what CI enforces | [Verification Status](STATUS.md) |
| Contribute code | [Contributing Guide](development/contributing.md) |
| Test compatibility | [Gorgoroth Network Testing](testing/GORGOROTH_COMPATIBILITY_TESTING.md) |

## Supported Networks

Each network is one argument to the launcher, for example `fukuii etc` or `fukuii sepolia`.

| Network | Chain ID | Consensus | Status |
|---------|----------|-----------|--------|
| Ethereum Classic | 61 | PoW (Ethash) | Built in (`fukuii etc`); SNAP sync with checkpoint bootstrap |
| Mordor | 63 | PoW (Ethash) | Built in (`fukuii mordor`); SNAP sync with checkpoint bootstrap |
| Ethereum Mainnet | 1 | PoS (Engine API) | Built in (`fukuii eth`); schedule through Osaka and BPO2, Amsterdam not yet scheduled on mainnet |
| Sepolia | 11155111 | PoS (Engine API) | Built in (`fukuii sepolia`); Amsterdam active since 2026-10-06 ([details](specifications/GLAMSTERDAM.md)) |
| Hoodi | 560048 | PoS (Engine API) | Built in (`fukuii hoodi`); no Amsterdam scheduled ([runbook](runbooks/hoodi.md)) |
| Ephemery | 39438000 + iteration | PoS (Engine API) | Built in (`fukuii ephemery`); resets every 28 days, the node exits 75 and a restart fetches the next iteration ([runbook](runbooks/ephemery.md)) |
| Platåberget | 7091047534 | PoS (Engine API) | Built in (`fukuii plataberget`); the public Glamsterdam testnet, Amsterdam active since 2026-08-20 ([details](specifications/GLAMSTERDAM.md)) |
| Gorgoroth and custom chains | your genesis | PoW or PoS | Private test network, or a custom genesis with no source changes ([runbook](runbooks/custom-networks.md)) |

## Documentation Organization

This documentation is organized by audience:

- **[Getting Started](getting-started/index.md)** — Installation and first-run guides
- **[For Node Operators](for-node-operators/index.md)** — Day-to-day node operation
- **[For Operators/SRE](for-operators/index.md)** — Production deployment and monitoring
- **[Sync Lifecycle](operations/sync-lifecycle.md)** — Phase-by-phase guide from empty datadir to chain tip
- **[State Healing Guide](operations/state-healing-operations.md)** — Operating the post-SNAP healing phase
- **[Metrics Reference](operations/metrics-reference.md)** — Prometheus metric catalog
- **[For Developers](for-developers/index.md)** — Architecture, contributing, and API docs
- **[Blog](blog/index.md)** — Release notes and spec write-ups
- **[Reference](specifications/README.md)** — Specifications, ADRs, and technical details
- **[Troubleshooting](troubleshooting/README.md)** — Common issues and solutions

## Community

- [GitHub Repository](https://github.com/chippr-robotics/fukuii)
- [GitHub Issues](https://github.com/chippr-robotics/fukuii/issues)
- [GitHub Discussions](https://github.com/chippr-robotics/fukuii/discussions)

---

*Built with :heart: by Chippr Robotics LLC*
