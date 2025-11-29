# Getting Started

Get up and running with Fukuii quickly. Choose the installation method that best fits your needs.

## Choose Your Path

<div class="grid cards" markdown>

-   :whale: **Docker (Recommended)**

    ---

    The fastest way to run Fukuii in production with signed container images.

    [:octicons-arrow-right-24: Docker Quick Start](../deployment/docker.md)

-   :cloud: **GitHub Codespaces**

    ---

    Perfect for development. Get a complete environment in your browser.

    [:octicons-arrow-right-24: Codespaces Setup](codespaces.md)

-   :hammer_and_wrench: **Build from Source**

    ---

    Build Fukuii yourself for development or customization.

    [:octicons-arrow-right-24: Build Guide](build-from-source.md)

</div>

## System Requirements

### Minimum Requirements

| Resource | Minimum | Recommended |
|----------|---------|-------------|
| **CPU** | 4 cores | 8+ cores |
| **RAM** | 8 GB | 16 GB |
| **Disk** | 500 GB SSD | 1 TB NVMe SSD |
| **Network** | 10 Mbps | 100 Mbps |

### Software Requirements

=== "Docker"

    - Docker 20.10 or later
    - docker-compose (optional)

=== "Source Build"

    - JDK 21 (OpenJDK or Oracle JDK)
    - sbt 1.10.7 or later
    - Git

### Required Ports

| Port | Protocol | Purpose |
|------|----------|---------|
| 30303 | UDP | Discovery protocol |
| 9076 | TCP | Ethereum P2P |
| 8546 | TCP | JSON-RPC (internal only!) |

!!! warning "Security Notice"
    Never expose port 8546 to the public internet. See the [Security Runbook](../runbooks/security.md) for details.

## Quick Docker Start

```bash
# Pull the latest signed release
docker pull ghcr.io/chippr-robotics/chordodes_fukuii:latest

# Create data volumes
docker volume create fukuii-data
docker volume create fukuii-conf

# Run the node
docker run -d \
  --name fukuii \
  --restart unless-stopped \
  -p 9076:9076 \
  -p 30303:30303/udp \
  -v fukuii-data:/app/data \
  -v fukuii-conf:/app/conf \
  ghcr.io/chippr-robotics/chordodes_fukuii:latest
```

## Verify Installation

```bash
# Check if node is responding
curl -X POST --data '{"jsonrpc":"2.0","method":"web3_clientVersion","params":[],"id":1}' \
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

## Next Steps

After installation, explore these guides:

1. **[First Start Runbook](../runbooks/first-start.md)** — Complete initial setup
2. **[Node Configuration](../runbooks/node-configuration.md)** — Customize your node
3. **[Security Runbook](../runbooks/security.md)** — Secure your installation
4. **[Monitoring Guide](../operations/metrics-and-monitoring.md)** — Set up Prometheus and Grafana
