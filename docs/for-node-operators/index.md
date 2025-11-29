# For Node Operators

This section contains guides for running and maintaining Fukuii nodes in production.

## Start Here

If you're new to Fukuii, begin with these guides:

1. **[First Start](../runbooks/first-start.md)** — Get your node running for the first time
2. **[Node Configuration](../runbooks/node-configuration.md)** — Understand and customize configuration
3. **[Security](../runbooks/security.md)** — Secure your node properly

## Quick Reference

### Common Tasks

| Task | Guide |
|------|-------|
| Start a new node | [First Start](../runbooks/first-start.md) |
| Configure RPC | [Node Configuration](../runbooks/node-configuration.md#rpc-configuration) |
| Set up TLS/HTTPS | [TLS Operations](../runbooks/tls-operations.md) |
| Optimize peering | [Peering](../runbooks/peering.md) |
| Manage disk space | [Disk Management](../runbooks/disk-management.md) |
| Create backups | [Backup & Restore](../runbooks/backup-restore.md) |
| Debug issues | [Known Issues](../runbooks/known-issues.md) |

### Essential Ports

| Port | Protocol | Purpose | Exposure |
|------|----------|---------|----------|
| 30303 | UDP | Discovery | Public |
| 9076 | TCP | P2P | Public |
| 8546 | TCP | RPC | **Private** |

### Default Paths

| Path | Description |
|------|-------------|
| `~/.fukuii/<network>/` | Data directory |
| `~/.fukuii/<network>/node.key` | Node identity key |
| `~/.fukuii/<network>/keystore/` | Account keystores |

## Runbooks

### Setup & Configuration

- **[First Start](../runbooks/first-start.md)** — Initial node setup and first synchronization
- **[Node Configuration](../runbooks/node-configuration.md)** — Complete configuration reference
- **[Operating Modes](../runbooks/operating-modes.md)** — Full node, archive node, light client

### Security

- **[Security](../runbooks/security.md)** — Firewall, access control, key management
- **[TLS Operations](../runbooks/tls-operations.md)** — HTTPS/TLS for RPC endpoints

### Networking

- **[Peering](../runbooks/peering.md)** — Peer discovery and connectivity troubleshooting

### Maintenance

- **[Disk Management](../runbooks/disk-management.md)** — Managing blockchain data growth
- **[Backup & Restore](../runbooks/backup-restore.md)** — Protecting your data

### Troubleshooting

- **[Log Triage](../runbooks/log-triage.md)** — Understanding and analyzing logs
- **[Known Issues](../runbooks/known-issues.md)** — Common problems and solutions

## Health Checks

### Check Sync Status

```bash
curl -X POST --data '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":1}' \
  http://localhost:8546
```

### Check Peer Count

```bash
curl -X POST --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
  http://localhost:8546
```

### Check Client Version

```bash
curl -X POST --data '{"jsonrpc":"2.0","method":"web3_clientVersion","params":[],"id":1}' \
  http://localhost:8546
```

## Network Information

### Ethereum Classic (Mainnet)

- **Network ID**: 1
- **Chain ID**: 61 (0x3d)
- **Default Data Dir**: `~/.fukuii/etc/`

### Mordor (Testnet)

- **Network ID**: 7
- **Chain ID**: 63 (0x3f)
- **Default Data Dir**: `~/.fukuii/mordor/`

## Related Resources

- [Docker Deployment](../deployment/docker.md)
- [Metrics & Monitoring](../operations/metrics-and-monitoring.md)
- [API Reference](../api/JSON_RPC_API_REFERENCE.md)
