# For Operators/SRE

This section contains guides for deploying, monitoring, and managing Fukuii in production environments.

## Start Here

1. **[Docker Compose](../deployment/docker.md)** — Deploy Fukuii with Docker
2. **[Metrics & Monitoring](../operations/metrics-and-monitoring.md)** — Set up Prometheus and Grafana
3. **[Log Triage](../runbooks/log-triage.md)** — Understand log messages and alerts

## Deployment Options

<div class="grid cards" markdown>

-   :whale: **Docker Compose**

    ---

    Production-ready deployment with monitoring stack.

    [:octicons-arrow-right-24: Docker Guide](../deployment/docker.md)

-   :shield: **Kong API Gateway**

    ---

    API gateway integration for RPC endpoints.

    [:octicons-arrow-right-24: Kong Guide](../deployment/kong.md)

-   :test_tube: **Test Network**

    ---

    Set up a local multi-node network for testing.

    [:octicons-arrow-right-24: Test Network](../deployment/test-network.md)

</div>

## Quick Reference

### Docker Images

| Image | Purpose |
|-------|---------|
| `ghcr.io/chippr-robotics/chordodes_fukuii:latest` | Production (signed) |
| `ghcr.io/chippr-robotics/chordodes_fukuii:v1.0.0` | Specific version |
| `ghcr.io/chippr-robotics/fukuii:main` | Development (unsigned) |
| `ghcr.io/chippr-robotics/fukuii-dev:latest` | Development environment |

### Verify Image Signatures

```bash
cosign verify \
  --certificate-identity-regexp=https://github.com/chippr-robotics/fukuii \
  --certificate-oidc-issuer=https://token.actions.githubusercontent.com \
  ghcr.io/chippr-robotics/chordodes_fukuii:latest
```

## Monitoring Stack

### Prometheus Metrics

Enable metrics in your Fukuii configuration:

```hocon
fukuii {
  metrics {
    enabled = true
    port = 9095
  }
}
```

Access metrics at: `http://localhost:9095/metrics`

### Key Metrics

| Metric | Description |
|--------|-------------|
| `ethereum_peer_count` | Current number of connected peers |
| `ethereum_block_height` | Current synchronized block number |
| `ethereum_sync_status` | Synchronization state |
| `jvm_memory_used_bytes` | JVM memory usage |

### Sample Prometheus Alerts

```yaml
groups:
  - name: fukuii
    rules:
      - alert: LowPeerCount
        expr: ethereum_peer_count < 10
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Low peer count on {{ $labels.instance }}"
          
      - alert: NodeNotSyncing
        expr: rate(ethereum_block_height[5m]) == 0
        for: 15m
        labels:
          severity: critical
        annotations:
          summary: "Node stopped syncing on {{ $labels.instance }}"
```

## Health Endpoints

### RPC Health Check

```bash
curl -X POST --data '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":1}' \
  http://localhost:8546
```

### Kubernetes Probes

```yaml
livenessProbe:
  exec:
    command:
      - /bin/sh
      - -c
      - |
        curl -sf -X POST \
          --data '{"jsonrpc":"2.0","method":"web3_clientVersion","params":[],"id":1}' \
          http://localhost:8546 || exit 1
  initialDelaySeconds: 60
  periodSeconds: 30

readinessProbe:
  exec:
    command:
      - /bin/sh
      - -c
      - |
        PEERS=$(curl -sf -X POST \
          --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
          http://localhost:8546 | jq -r '.result' | xargs printf '%d')
        [ "$PEERS" -gt 5 ] || exit 1
  initialDelaySeconds: 120
  periodSeconds: 60
```

## Logging

### Log Levels

Configure log levels in `logback.xml` or via environment:

```bash
export FUKUII_LOG_LEVEL=INFO
```

| Level | Use Case |
|-------|----------|
| ERROR | Production |
| WARN | Production with warnings |
| INFO | Standard operation |
| DEBUG | Troubleshooting |
| TRACE | Deep debugging |

### Log Analysis

See the [Log Triage Runbook](../runbooks/log-triage.md) for:

- Common log patterns
- Error message interpretation
- Troubleshooting workflows

## Incident Response

### Common Issues

| Symptom | Likely Cause | Resolution |
|---------|--------------|------------|
| Zero peers | Firewall blocking | [Peering Guide](../runbooks/peering.md) |
| Sync stalled | Disk full or slow | [Disk Management](../runbooks/disk-management.md) |
| High memory | JVM settings | Check `.jvmopts` |
| RPC timeout | Too many requests | Enable rate limiting |

### Emergency Procedures

For security incidents, see the [Security Runbook - Incident Response](../runbooks/security.md#incident-response).

## Related Documentation

- [Security Runbook](../runbooks/security.md) — Production security guidelines
- [Backup & Restore](../runbooks/backup-restore.md) — Disaster recovery
- [Known Issues](../runbooks/known-issues.md) — Common problems
