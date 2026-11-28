# For Node Operators

## Quick Start

Install Fukuii from the Ubuntu PPA, Chocolatey, a release archive or a signed container (see
[Getting Started](../getting-started/index.md)), then start a network by name:

```bash
fukuii mordor            # ETC testnet
fukuii etc               # ETC mainnet
fukuii sepolia           # Ethereum testnet; needs a consensus client
fukuii etc --tui         # any network, with the terminal dashboard
```

From a release archive the launcher is `./bin/fukuii`. The launcher applies the JVM settings in
`conf/application.ini` (heap, direct-memory cap, GC and the JDK workaround flags), so run it rather than
`java -jar` where you can. The datadir defaults to `~/.fukuii/<network>`; set `fukuii.datadir` to move it.

## Runbooks

| Guide | Description |
|-------|-------------|
| [First Start](../runbooks/first-start.md) | Initial node setup and first sync |
| [Node Configuration](../runbooks/node-configuration.md) | Complete configuration reference |
| [Operating Modes](../runbooks/operating-modes.md) | Full node, archive node, bootstrap node |
| [Static Nodes](../for-operators/static-nodes-configuration.md) | Peer configuration via static-nodes.json |
| [Security](../runbooks/security.md) | Firewall, access control, key management |
| [TLS Operations](../runbooks/tls-operations.md) | HTTPS/TLS for RPC endpoints |
| [Peering](../runbooks/peering.md) | Peer discovery and connectivity |
| [Disk Management](../runbooks/disk-management.md) | Managing blockchain data growth |
| [Backup & Restore](../runbooks/backup-restore.md) | Protecting your data |
| [Log Triage](../runbooks/log-triage.md) | Understanding and analyzing logs |
| [Mining Operations](../runbooks/mining-operations.md) | ETC Ethash mining |
| [Checkpoint Service](../runbooks/checkpoint-service.md) | Fast ETC/Mordor bootstrap from a trusted checkpoint |
| [Hoodi](../runbooks/hoodi.md) and [Ephemery](../runbooks/ephemery.md) | The built-in Ethereum testnets |
| [Known Issues](../runbooks/known-issues.md) | Current problems and workarounds |

## Network Information

| Network | Launcher argument | Chain ID | Network ID | Consensus client |
|---------|-------------------|----------|------------|------------------|
| ETC Mainnet | `etc` | 61 (0x3d) | 1 | Not needed (PoW) |
| Mordor | `mordor` | 63 (0x3f) | 7 | Not needed (PoW) |
| Ethereum Mainnet | `eth` | 1 | 1 | Required |
| Sepolia | `sepolia` | 11155111 | 11155111 | Required |
| Hoodi | `hoodi` | 560048 | 560048 | Required |
| Ephemery | `ephemery` | 39438000 + iteration | same as chain ID | Required |
| Platåberget | `plataberget` | 7091047534 | 7091047534 | Required |

On the networks that need a consensus client the Engine API listens on `localhost:8551`, and the JWT secret is
`<datadir>/jwt.hex`, created at first start. Pass that file to the consensus client's `--execution-jwt`.

## Essential Ports

| Port | Protocol | Purpose | Exposure |
|------|----------|---------|----------|
| 30303 | TCP+UDP | P2P + Discovery | Public |
| 8546 | TCP | JSON-RPC over HTTP, health endpoints | **Private** |
| 8552 | TCP | JSON-RPC over WebSocket (off by default) | **Private** |
| 8551 | TCP | Engine API (Ethereum networks) | **Private** |

## Health Checks

```bash
# Liveness: 200 while the RPC server is up
curl -s http://localhost:8546/health

# Readiness: 503 until the node is ready to serve
curl -s http://localhost:8546/readiness

# Sync status
curl -s -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":1}'

# Peer count
curl -s -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}'
```

## Deployment

| Guide | Description |
|-------|-------------|
| [Docker Guide](../deployment/docker.md) | Container deployment, signing, Kubernetes |
| [Kong API Gateway](../deployment/kong.md) | Barad-dûr API gateway integration |
| [Monitoring](../operations/metrics-and-monitoring.md) | Prometheus + Grafana |
| [API Reference](../api/JSON_RPC_API_REFERENCE.md) | JSON-RPC endpoint documentation |
