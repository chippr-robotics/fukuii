# Quick Start

Pick one install path, start a network, and check the node answers. Every path runs the same `fukuii` launcher; the
first argument picks the network: `etc`, `mordor`, `eth`, `sepolia`, `hoodi`, `ephemery` or `plataberget`.

## :package: Ubuntu (PPA)

For Ubuntu 24.04 (noble) and 22.04 (jammy), from
[ppa:chipprbots/fukuii](https://launchpad.net/~chipprbots/+archive/ubuntu/fukuii).

```bash
sudo add-apt-repository ppa:chipprbots/fukuii
sudo apt update
sudo apt install fukuii

fukuii etc
```

The package puts the launcher at `/usr/bin/fukuii` and its configuration in `/etc/fukuii`.

## :window: Windows (Chocolatey)

From the [Chocolatey community repository](https://community.chocolatey.org/packages/fukuii). The package installs the
Temurin 25 JRE with it.

```powershell
choco install fukuii

fukuii etc
```

## :inbox_tray: Release archive

Linux, macOS and Windows archives, and a standalone JAR, are attached to every
[release](https://github.com/chippr-robotics/fukuii/releases/latest) with `SHA256SUMS.txt`. You need JDK 25 or later.

```bash
sha256sum -c SHA256SUMS.txt --ignore-missing
unzip fukuii-<version>.zip
cd fukuii-<version>

./bin/fukuii mordor --tui
```

## :whale: Signed container

### 1. Pull and verify

```bash
docker pull ghcr.io/chippr-robotics/fukuii:<version>

cosign verify \
  --certificate-identity-regexp=https://github.com/chippr-robotics/fukuii \
  --certificate-oidc-issuer=https://token.actions.githubusercontent.com \
  ghcr.io/chippr-robotics/fukuii:<version>
```

`docker.io/chipprbots/fukuii` is an unsigned mirror for environments where GHCR is not reachable.

### 2. Run the node

```bash
docker run -d \
  --name fukuii \
  --restart unless-stopped \
  -p 30303:30303/tcp \
  -p 30303:30303/udp \
  -v fukuii-data:/app/data \
  ghcr.io/chippr-robotics/fukuii:<version>
```

The image runs ETC mainnet. For Mordor, append `mordor` after the image name. JSON-RPC stays on the container's
loopback, so run the checks under [Verify your node](#verify-your-node) with `docker exec fukuii` in front of `curl`.

!!! note "Images up to v0.12.79"
    Release images up to v0.12.79 do not read `FUKUII_DATADIR`. With one of those, append
    `-Dfukuii.datadir=/app/data` after the image name, or the chain is written inside the container.

!!! warning "Security Notice"
    Since 0.13.0 a plaintext listener on a non-loopback interface refuses the start. To publish JSON-RPC, set
    `-e FUKUII_RPC_INTERFACE=0.0.0.0` and either enable TLS or opt into plaintext deliberately, and publish the port
    only on the host's loopback (`127.0.0.1:8546:8546`). See
    [Exposing JSON-RPC](../deployment/docker.md#exposing-json-rpc).

See the [Docker Guide](../deployment/docker.md) for Compose, image variants and Kubernetes.

## :hammer_and_wrench: Build from source

For development or custom builds. You need JDK 25, sbt 1.13.0+ and Git.

```bash
git clone https://github.com/chippr-robotics/fukuii.git
cd fukuii
git submodule update --init --recursive

sbt dist

cd target/universal
unzip fukuii-*.zip
cd fukuii-*/
./bin/fukuii etc
```

See [Build from Source](build-from-source.md) for details.

## :cloud: GitHub Codespaces

1. Go to [github.com/chippr-robotics/fukuii](https://github.com/chippr-robotics/fukuii)
2. Click **Code** → **Codespaces** → **Create codespace on main**
3. Wait for initialization
4. Run `sbt compile` in the terminal

## Verify your node

```bash
# Liveness
curl -s http://localhost:8546/health

# Client version
curl -s -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"web3_clientVersion","params":[],"id":1}' \
  http://localhost:8546

# Peer count
curl -s -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
  http://localhost:8546

# Sync status
curl -s -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":1}' \
  http://localhost:8546
```

On the Ethereum networks the node waits for a consensus client before it syncs; see
[Getting Started](index.md#ethereum-networks-need-a-consensus-client).

## Next Steps

- [First Start Runbook](../runbooks/first-start.md) — Detailed first-run guide
- [Node Configuration](../runbooks/node-configuration.md) — Customize your node
- [Security Runbook](../runbooks/security.md) — Secure your installation
- [Docker Guide](../deployment/docker.md) — Full Docker documentation
