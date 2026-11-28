# For Operators/SRE

This section contains guides for deploying, monitoring, and managing Fukuii in production environments.

## Start Here

1. **[Docker Compose](../deployment/docker.md)** — Deploy Fukuii with Docker
2. **[Metrics & Monitoring](../operations/metrics-and-monitoring.md)** — Set up Prometheus and Grafana
3. **[Log Triage](../runbooks/log-triage.md)** — Understand log messages and alerts
4. **[Sync Lifecycle](../operations/sync-lifecycle.md)** — What a syncing node does, phase by phase, and what "normal" looks like
5. **[State Healing — Operator's Guide](../operations/state-healing-operations.md)** — The long post-SNAP phase: logs, knobs, dashboards, healthy-vs-stuck
6. **[Metrics Reference](../operations/metrics-reference.md)** — Every exported Prometheus metric and what it means

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
| `ghcr.io/chippr-robotics/fukuii:<version>` | Release image, Cosign-signed, with provenance and SBOM attestations. Pin this in production |
| `ghcr.io/chippr-robotics/fukuii:main` | Built from `main` on every push; not signed |
| `ghcr.io/chippr-robotics/fukuii-dev:latest` | Development environment (JDK and sbt) |
| `docker.io/chipprbots/fukuii` | Unsigned mirror for environments where GHCR is restricted |

Release tags are the bare version, for example `0.12.79` (no `v`).

### Verify Image Signatures

```bash
cosign verify \
  --certificate-identity-regexp=https://github.com/chippr-robotics/fukuii \
  --certificate-oidc-issuer=https://token.actions.githubusercontent.com \
  ghcr.io/chippr-robotics/fukuii:<version>
```

## Monitoring Stack

### Prometheus Metrics

Metrics are off by default. Turn them on in your configuration:

```hocon
fukuii {
  metrics {
    enabled = true
    port = 13798
  }
}
```

Metrics are served at `http://localhost:13798/metrics`. The `ops/` reference stacks set the port to 9095.

### Key Metrics

| Metric | Description |
|--------|-------------|
| `app_network_peers_outgoing_handshaked_gauge` | Peers this node dialled that completed the handshake |
| `app_network_peers_incoming_handshaked_gauge` | Peers that dialled in and completed the handshake |
| `app_regularsync_block_current_number_gauge` | The node's current block |
| `app_regularsync_block_bestKnown_number_gauge` | The best block the node knows of from its peers |
| `app_snapsync_pivot_block_number_gauge` | The SNAP sync pivot block |
| `jvm_memory_used_bytes` | JVM memory usage |

The full catalog is in the [Metrics Reference](../operations/metrics-reference.md).

### Sample Prometheus Alerts

```yaml
groups:
  - name: fukuii
    rules:
      - alert: LowPeerCount
        expr: app_network_peers_outgoing_handshaked_gauge + app_network_peers_incoming_handshaked_gauge < 5
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Low peer count on {{ $labels.instance }}"

      - alert: NodeNotImporting
        expr: increase(app_regularsync_block_current_number_gauge[15m]) == 0
        for: 15m
        labels:
          severity: critical
        annotations:
          summary: "No new blocks imported on {{ $labels.instance }}"
```

The `NodeNotImporting` alert applies once the node follows the head; during SNAP sync watch the SNAP metrics instead
(see [Sync Lifecycle](../operations/sync-lifecycle.md)).

## Health Endpoints

The JSON-RPC HTTP server (port 8546) serves:

| Endpoint | Meaning |
|----------|---------|
| `GET /health` | 200 while the RPC server is up. Use it for liveness |
| `GET /readiness` | 200 when the node is ready, 503 otherwise. Use it for readiness |
| `GET /healthcheck` | The detailed health report |
| `GET /buildinfo` | Version and build information |

```bash
curl -s http://localhost:8546/readiness
```

### Kubernetes Probes

```yaml
livenessProbe:
  httpGet:
    path: /health
    port: 8546
  initialDelaySeconds: 60
  periodSeconds: 30

readinessProbe:
  httpGet:
    path: /readiness
    port: 8546
  initialDelaySeconds: 120
  periodSeconds: 60
```

The RPC server must listen on the pod's interface for the kubelet to reach it. The container images keep RPC on
loopback, so set `FUKUII_RPC_INTERFACE=0.0.0.0` (or `fukuii.network.rpc.http.interface`) together with TLS or
`fukuii.network.rpc.http.allow-insecure-bind = true`; a plaintext non-loopback listener refuses the start otherwise
(spec 030). Without that, use an `exec` probe that runs `curl` against `localhost:8546`.

## Logging

### Log Levels

Set the level with `logging.logs-level` (the default is `ERROR`); `logging.json-output = true` switches to JSON lines
for a log pipeline. Logs are written to `<datadir>/logs`.

```hocon
logging.logs-level = "INFO"
```

Or pass `-Dlogging.logs-level=INFO` on the command line.

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
| High memory | JVM settings | Check the heap flags in `conf/application.ini` (`/etc/fukuii/application.ini` for the deb) |
| RPC timeout | Too many requests | Enable rate limiting |

### Emergency Procedures

For security incidents, see the [Security Runbook - Incident Response](../runbooks/security.md#incident-response).

## Related Documentation

- [Security Runbook](../runbooks/security.md) — Production security guidelines
- [Backup & Restore](../runbooks/backup-restore.md) — Disaster recovery
- [Known Issues](../runbooks/known-issues.md) — Common problems
