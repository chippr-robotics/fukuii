# For Node Operators

## Quick Start

**Important:** Always use the assembly JAR, not `sbt run`.

```bash
# Build the assembly JAR
sbt assembly

# Run on Mordor testnet
java -Xmx4g \
  -Dfukuii.datadir=~/.fukuii/mordor \
  -Dfukuii.network=mordor \
  -jar target/scala-3.3.7/fukuii-assembly-0.1.240.jar mordor

# Run on ETC mainnet
java -Xmx4g \
  -Dfukuii.datadir=~/.fukuii/etc \
  -Dfukuii.network=etc \
  -Dfukuii.sync.do-fast-sync=true \
  -jar target/scala-3.3.7/fukuii-assembly-0.1.240.jar etc
```

## Runbooks

| Guide | Description |
|-------|-------------|
| [First Start](../runbooks/first-start.md) | Initial node setup and first sync |
| [Node Configuration](../runbooks/node-configuration.md) | Complete configuration reference |
| [Operating Modes](../runbooks/operating-modes.md) | Full node, archive node, light client |
| [Static Nodes](../for-operators/static-nodes-configuration.md) | Peer configuration via static-nodes.json |
| [Security](../runbooks/security.md) | Firewall, access control, key management |
| [TLS Operations](../runbooks/tls-operations.md) | HTTPS/TLS for RPC endpoints |
| [Peering](../runbooks/peering.md) | Peer discovery and connectivity |
| [Disk Management](../runbooks/disk-management.md) | Managing blockchain data growth |
| [Backup & Restore](../runbooks/backup-restore.md) | Protecting your data |
| [Log Triage](../runbooks/log-triage.md) | Understanding and analyzing logs |
| [Known Issues](../runbooks/known-issues.md) | Common problems and solutions |

## Network Information

| Network | Chain ID | Network ID | Default Data Dir |
|---------|----------|------------|------------------|
| ETC Mainnet | 61 (0x3d) | 1 | `~/.fukuii/etc/` |
| Mordor Testnet | 63 (0x3f) | 7 | `~/.fukuii/mordor/` |

## Essential Ports

| Port | Protocol | Purpose | Exposure |
|------|----------|---------|----------|
| 30303 | TCP+UDP | P2P + Discovery | Public |
| 8546 | TCP | JSON-RPC | **Private** |

## Health Checks

```bash
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
| [Kong API Gateway](../deployment/kong.md) | Barad-dur API gateway integration |
| [Monitoring](../operations/metrics-and-monitoring.md) | Prometheus + Grafana |
| [API Reference](../api/JSON_RPC_API_REFERENCE.md) | JSON-RPC endpoint documentation |
