# Getting Started

Install Fukuii from a package manager, a release archive or a signed container image, or build it from source.

## Choose Your Path

<div class="grid cards" markdown>

-   :package: **Packages**

    ---

    `ppa:chipprbots/fukuii` for Ubuntu (noble, jammy) and the `fukuii` package on Chocolatey for Windows.

    [:octicons-arrow-right-24: Quick Start](quickstart.md)

-   :whale: **Signed container**

    ---

    Cosign-signed images on GHCR, with provenance and SBOM attestations.

    [:octicons-arrow-right-24: Docker Guide](../deployment/docker.md)

-   :hammer_and_wrench: **Build from Source**

    ---

    Build Fukuii yourself for development or customization.

    [:octicons-arrow-right-24: Build Guide](build-from-source.md)

-   :cloud: **GitHub Codespaces**

    ---

    A complete development environment in your browser.

    [:octicons-arrow-right-24: Codespaces Setup](codespaces.md)

</div>

## System Requirements

### Hardware

| Resource | Minimum | Recommended |
|----------|---------|-------------|
| **CPU** | 4 cores | 8+ cores |
| **RAM** | 8 GB | 16 GB |
| **Disk** | 500 GB SSD | 1 TB NVMe SSD |
| **Network** | 10 Mbps | 100 Mbps |

These figures are for an ETC full node. A Sepolia or mainnet node that keeps full block history needs much more disk;
see [Known Issues](../runbooks/known-issues.md) and [Disk Management](../runbooks/disk-management.md) before you sync.

### Software

=== "Packages"

    - Ubuntu PPA: Ubuntu 24.04 (noble) or 22.04 (jammy). The package depends on an OpenJDK 25 runtime.
    - Chocolatey: Windows with Chocolatey. The package installs the Temurin 25 JRE.

=== "Release archive"

    - JDK 25 or later

=== "Docker"

    - Docker 20.10 or later
    - `cosign`, to verify image signatures

=== "Source build"

    - JDK 25
    - sbt 1.13.0 or later
    - Git

### Platform Support

Fukuii runs on the JVM, but a few precompiles call native libraries, and each library ships builds for some platforms
only:

| Native library | Precompiles | Linux x86_64 / aarch64 | macOS x86_64 / aarch64 | Windows x86_64 |
|---|---|---|---|---|
| eth_pairings (`org.hyperledger.besu:bls12-381`) | EIP-2537 BLS12-381, 0x0b-0x11 | Yes | Yes | No |
| blst (`tech.pegasys:jblst`) | EIP-2537 BLS12-381, 0x0b-0x11 | Yes | Yes | Yes |
| c-kzg-4844 (`io.consensys.protocols:jc-kzg-4844`) | EIP-4844 point evaluation, 0x0a | Yes | Yes | Yes |

The BLS12-381 precompiles run on eth_pairings where it loads and on blst elsewhere, so ETH chains (mainnet, Sepolia,
Hoodi, Ephemery, Platåberget) and ETC from Olympia run on Windows too. `fukuii.vm.bls12-381-backend` (or
`FUKUII_BLS12381_BACKEND`) can force one: `auto` (the default), `eth-pairings` or `blst`. Both libraries give the same
bytes on the EIP-2537 test corpus (ADR [VM-008](../adr/vm/VM-008-windows-bls12-381-backend.md)). A node whose chain
needs a library that does not load stops at startup with a message naming it, rather than compute different results
from the network.

### Ports

| Port | Protocol | Purpose | Exposure |
|------|----------|---------|----------|
| 30303 | TCP | P2P (RLPx) | Public |
| 30303 | UDP | Discovery | Public |
| 8546 | TCP | JSON-RPC over HTTP, plus `/health`, `/readiness`, `/buildinfo` | Localhost only |
| 8552 | TCP | JSON-RPC over WebSocket (off unless enabled) | Localhost only |
| 8551 | TCP | Engine API, for the consensus client (Ethereum networks) | Localhost or a private network |

!!! warning "Security Notice"
    Never expose the JSON-RPC or Engine API ports to the public internet. They bind to `localhost` by default. See the
    [Security Runbook](../runbooks/security.md) for details.

## Quick Install

=== "Ubuntu (PPA)"

    ```bash
    sudo add-apt-repository ppa:chipprbots/fukuii
    sudo apt update
    sudo apt install fukuii

    fukuii etc      # or mordor, eth, sepolia, hoodi, ephemery
    ```

=== "Windows (Chocolatey)"

    ```powershell
    choco install fukuii

    fukuii etc      # or mordor, eth, sepolia, hoodi, ephemery
    ```

=== "Release archive"

    Download `fukuii-<version>.zip` (or `.tgz`) and `SHA256SUMS.txt` from the
    [latest release](https://github.com/chippr-robotics/fukuii/releases/latest), then:

    ```bash
    sha256sum -c SHA256SUMS.txt --ignore-missing
    unzip fukuii-<version>.zip && cd fukuii-<version>
    ./bin/fukuii etc
    ```

=== "Docker"

    ```bash
    docker pull ghcr.io/chippr-robotics/fukuii:<version>

    cosign verify \
      --certificate-identity-regexp=https://github.com/chippr-robotics/fukuii \
      --certificate-oidc-issuer=https://token.actions.githubusercontent.com \
      ghcr.io/chippr-robotics/fukuii:<version>

    docker run -d --name fukuii --restart unless-stopped \
      -p 30303:30303/tcp -p 30303:30303/udp \
      -p 127.0.0.1:8546:8546 \
      -v fukuii-data:/app/data \
      ghcr.io/chippr-robotics/fukuii:<version>
    ```

    The image keeps its data in `/app/data` and binds JSON-RPC to the container's interfaces so Docker can forward the
    published port. Publishing it on `127.0.0.1` keeps it on the host's loopback.
    Release images up to v0.12.79 need `-Dfukuii.datadir=/app/data
    -Dfukuii.network.rpc.http.interface=0.0.0.0` appended after the image name; see the [Quick Start](quickstart.md).

The datadir defaults to `~/.fukuii/<network>`. Add `--tui` to any launch for the terminal dashboard.

## Verify Installation

```bash
# Liveness
curl -s http://localhost:8546/health

# Client version
curl -s -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"web3_clientVersion","params":[],"id":1}' \
  http://localhost:8546
```

Expected response:

```json
{
  "jsonrpc":"2.0",
  "id":1,
  "result":"Fukuii/v<version>/..."
}
```

## Ethereum networks need a consensus client

On `eth`, `sepolia`, `hoodi`, `ephemery` and `plataberget` the Engine API is on by default on `localhost:8551`. At first
start Fukuii writes a secret to `<datadir>/jwt.hex`. Point your consensus client at it:

```bash
fukuii sepolia

lighthouse bn --network sepolia \
  --execution-endpoint http://localhost:8551 \
  --execution-jwt ~/.fukuii/sepolia/jwt.hex \
  --checkpoint-sync-url <provider>
```

ETC and Mordor need no consensus client.

## Next Steps

1. **[First Start Runbook](../runbooks/first-start.md)** — Complete initial setup
2. **[Node Configuration](../runbooks/node-configuration.md)** — Customize your node
3. **[Security Runbook](../runbooks/security.md)** — Secure your installation
4. **[Monitoring Guide](../operations/metrics-and-monitoring.md)** — Set up Prometheus and Grafana
5. **[Known Issues](../runbooks/known-issues.md)** — Read before your first long sync
