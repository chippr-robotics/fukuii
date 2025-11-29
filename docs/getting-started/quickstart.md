# Quick Start

This guide helps you get Fukuii running quickly. Choose your deployment method:

## :whale: Docker (Recommended)

The fastest way to get started with Fukuii.

### 1. Pull the Image

```bash
# Pull the latest signed release
docker pull ghcr.io/chippr-robotics/chordodes_fukuii:latest
```

### 2. Create Volumes

```bash
docker volume create fukuii-data
docker volume create fukuii-conf
```

### 3. Run the Node

```bash
docker run -d \
  --name fukuii \
  --restart unless-stopped \
  -p 9076:9076 \
  -p 30303:30303/udp \
  -v fukuii-data:/app/data \
  -v fukuii-conf:/app/conf \
  ghcr.io/chippr-robotics/chordodes_fukuii:latest
```

!!! warning "Security Notice"
    Do NOT expose port 8546 (RPC) to the public internet. For internal access, use: `-p 127.0.0.1:8546:8546`

### 4. Verify

```bash
# Check logs
docker logs -f fukuii

# Test RPC
curl -X POST --data '{"jsonrpc":"2.0","method":"web3_clientVersion","params":[],"id":1}' \
  http://localhost:8546
```

---

## :hammer_and_wrench: Build from Source

For development or custom builds.

### Prerequisites

- JDK 21
- sbt 1.10.7+
- Git

### Steps

```bash
# Clone
git clone https://github.com/chippr-robotics/fukuii.git
cd fukuii

# Update submodules
git submodule update --init --recursive

# Build distribution
sbt dist

# Extract
cd target/universal
unzip fukuii-*.zip
cd fukuii-*/

# Run
./bin/fukuii etc
```

---

## :cloud: GitHub Codespaces

Perfect for development.

1. Go to [github.com/chippr-robotics/fukuii](https://github.com/chippr-robotics/fukuii)
2. Click **Code** → **Codespaces** → **Create codespace on develop**
3. Wait for initialization
4. Run `sbt compile` in the terminal

---

## Verify Your Installation

After starting the node, verify it's working:

```bash
# Check client version
curl -X POST --data '{"jsonrpc":"2.0","method":"web3_clientVersion","params":[],"id":1}' \
  http://localhost:8546

# Check peer count
curl -X POST --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
  http://localhost:8546

# Check sync status
curl -X POST --data '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":1}' \
  http://localhost:8546
```

## Next Steps

- [First Start Runbook](../runbooks/first-start.md) — Detailed first-run guide
- [Node Configuration](../runbooks/node-configuration.md) — Customize your node
- [Security Runbook](../runbooks/security.md) — Secure your installation
- [Docker Guide](../deployment/docker.md) — Full Docker documentation
