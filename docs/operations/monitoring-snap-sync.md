# SNAP Sync Monitoring Guide

This guide describes how to monitor SNAP sync operations in Fukuii using Prometheus metrics, Kamon instrumentation, and Grafana dashboards.

## Overview

SNAP sync is monitored through multiple observability layers:

- **Prometheus Metrics**: Numeric gauges, counters, and timers for sync progress
- **Kamon Instrumentation**: Actor-level metrics for SNAPSyncController and related actors
- **Grafana Dashboard**: Pre-built visualization for SNAP sync monitoring
- **Structured Logging**: JSON-formatted logs with SNAP sync context
- **Alerting**: Prometheus alert rules for sync failures and performance issues

## Architecture

### Component Hierarchy

```
SNAPSyncController (Pekko Actor)
├── AccountRangeDownloader
├── BytecodeDownloader
├── StorageRangeDownloader
├── TrieNodeHealer
├── SyncProgressMonitor
└── SNAPRequestTracker
```

### Sync Phases

SNAP sync progresses through the following phases:

1. **Idle** (0): Not started
2. **AccountRangeSync** (1): Downloading account ranges with Merkle proofs
3. **BytecodeSync** (2): Downloading smart contract bytecodes
4. **StorageRangeSync** (3): Downloading storage slots for contracts
5. **StateHealing** (4): Filling missing trie nodes
6. **StateValidation** (5): Verifying state completeness
7. **Completed** (6): SNAP sync finished

## Prometheus Metrics

### Enabling Metrics

Metrics are exposed on port 13798 by default. Enable metrics in your configuration:

```hocon
fukuii.metrics {
  enabled = true
  port = 13798
}
```

Access metrics at: `http://localhost:13798/metrics`

### Available Metrics

#### Sync Phase Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `app_snapsync_phase_current_gauge` | Gauge | Current sync phase (0-6) |
| `app_snapsync_totaltime_minutes_gauge` | Gauge | Total time spent in SNAP sync (minutes) |
| `app_snapsync_phase_time_seconds_gauge` | Gauge | Time spent in current phase (seconds) |

#### Pivot Block Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `app_snapsync_pivot_block_number_gauge` | Gauge | Pivot block number selected for sync |

#### Account Range Sync Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `app_snapsync_accounts_synced_gauge` | Gauge | Total accounts synced |
| `app_snapsync_accounts_estimated_total_gauge` | Gauge | Estimated total accounts |
| `app_snapsync_accounts_throughput_overall_gauge` | Gauge | Accounts/sec since start |
| `app_snapsync_accounts_throughput_recent_gauge` | Gauge | Accounts/sec (last 60s) |
| `app_snapsync_accounts_download_timer` | Timer | Account range download time |
| `app_snapsync_accounts_requests_total` | Counter | Total account range requests |
| `app_snapsync_accounts_requests_failed` | Counter | Failed account range requests |

#### Bytecode Download Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `app_snapsync_bytecodes_downloaded_gauge` | Gauge | Total bytecodes downloaded |
| `app_snapsync_bytecodes_estimated_total_gauge` | Gauge | Estimated total bytecodes |
| `app_snapsync_bytecodes_throughput_overall_gauge` | Gauge | Codes/sec since start |
| `app_snapsync_bytecodes_throughput_recent_gauge` | Gauge | Codes/sec (last 60s) |
| `app_snapsync_bytecodes_download_timer` | Timer | Bytecode download time |
| `app_snapsync_bytecodes_requests_total` | Counter | Total bytecode requests |
| `app_snapsync_bytecodes_requests_failed` | Counter | Failed bytecode requests |

#### Storage Range Sync Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `app_snapsync_storage_slots_synced_gauge` | Gauge | Total storage slots synced |
| `app_snapsync_storage_slots_estimated_total_gauge` | Gauge | Estimated total slots |
| `app_snapsync_storage_throughput_overall_gauge` | Gauge | Slots/sec since start |
| `app_snapsync_storage_throughput_recent_gauge` | Gauge | Slots/sec (last 60s) |
| `app_snapsync_storage_download_timer` | Timer | Storage range download time |
| `app_snapsync_storage_requests_total` | Counter | Total storage requests |
| `app_snapsync_storage_requests_failed` | Counter | Failed storage requests |

#### State Healing Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `app_snapsync_healing_nodes_healed_gauge` | Gauge | Total trie nodes healed |
| `app_snapsync_healing_throughput_overall_gauge` | Gauge | Nodes/sec since start |
| `app_snapsync_healing_throughput_recent_gauge` | Gauge | Nodes/sec (last 60s) |
| `app_snapsync_healing_timer` | Timer | State healing operation time |
| `app_snapsync_healing_requests_total` | Counter | Total healing requests |
| `app_snapsync_healing_requests_failed` | Counter | Failed healing requests |
| `app_snapsync_validation_missing_nodes_gauge` | Gauge | Missing nodes detected |

#### Peer Performance Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `app_snapsync_peers_capable_gauge` | Gauge | SNAP-capable peers connected |
| `app_snapsync_peers_blacklisted_total` | Counter | Peers blacklisted |
| `app_snapsync_requests_timeouts_total` | Counter | Request timeouts |
| `app_snapsync_requests_retries_total` | Counter | Request retries |

#### Error Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `app_snapsync_errors_total` | Counter | Total sync errors |
| `app_snapsync_validation_failures_total` | Counter | State validation failures |
| `app_snapsync_proofs_invalid_total` | Counter | Invalid Merkle proofs |
| `app_snapsync_responses_malformed_total` | Counter | Malformed responses |

## Kamon Instrumentation

### Actor Metrics

Kamon automatically tracks SNAPSyncController actor metrics:

```hocon
kamon.instrumentation.pekko.filters {
  actors.track {
    includes = [ "fukuii_system/user/*" ]
  }
}
```

### Available Kamon Metrics

| Metric | Description |
|--------|-------------|
| `pekko_actor_processing_time_seconds{actor="SNAPSyncController"}` | Message processing time |
| `pekko_actor_mailbox_size{actor="SNAPSyncController"}` | Mailbox queue size |
| `pekko_actor_messages_processed_total{actor="SNAPSyncController"}` | Total messages processed |

## Grafana Dashboard

### Loading the Dashboard

A pre-configured Grafana dashboard is available at `/ops/grafana/fukuii-snap-sync-dashboard.json`.

**Import Steps:**

1. Open Grafana UI (typically `http://localhost:3000`)
2. Click **+** → **Import**
3. Upload `/ops/grafana/fukuii-snap-sync-dashboard.json`
4. Select your Prometheus datasource
5. Click **Import**

### Dashboard Panels

The SNAP Sync dashboard includes the following sections:

#### 1. Overview

- **Current Phase**: Visual indicator of sync phase
- **Sync Progress**: Overall completion percentage
- **ETA**: Estimated time to completion
- **SNAP-Capable Peers**: Number of connected peers

#### 2. Account Range Sync

- **Accounts Synced**: Progress graph
- **Download Throughput**: Accounts/sec (overall and recent)
- **Request Success Rate**: Percentage of successful requests
- **Account Range Download Time**: Histogram

#### 3. Bytecode Download

- **Bytecodes Downloaded**: Progress graph
- **Download Throughput**: Codes/sec
- **Failure Rate**: Failed requests over time

#### 4. Storage Range Sync

- **Storage Slots Synced**: Progress graph
- **Download Throughput**: Slots/sec
- **Request Distribution**: Requests by peer

#### 5. State Healing

- **Nodes Healed**: Progress graph
- **Healing Throughput**: Nodes/sec
- **Missing Nodes Detected**: Validation results

#### 6. Performance & Errors

- **Phase Duration**: Time spent in each phase
- **Error Rate**: Errors by type
- **Peer Performance**: Blacklisting events
- **Request Timeouts**: Timeout rate over time

## Structured Logging

### SNAP Sync Log Fields

When JSON logging is enabled (`logging.json-output = true`), SNAP sync logs include:

```json
{
  "timestamp": "2025-12-02T23:30:00.000Z",
  "level": "INFO",
  "logger": "com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController",
  "message": "📈 SNAP Sync Progress: phase=AccountRange (45%), accounts=1234567@850/s",
  "service": "fukuii",
  "node": "fukuii-node-1",
  "phase": "AccountRangeSync",
  "pivot_block": "12345678",
  "accounts_synced": "1234567",
  "throughput": "850"
}
```

### Log Queries

#### Elasticsearch/Kibana

```json
{
  "query": {
    "bool": {
      "must": [
        { "match": { "logger": "SNAPSyncController" } },
        { "range": { "@timestamp": { "gte": "now-1h" } } }
      ]
    }
  }
}
```

#### Loki/Grafana

```logql
{service="fukuii"} |= "SNAPSyncController" | json | phase="AccountRangeSync"
```

## Alerting

### Prometheus Alert Rules

Create `/etc/prometheus/snap_sync_alerts.yml`:

```yaml
groups:
  - name: snap_sync
    interval: 30s
    rules:
      # No SNAP-capable peers
      - alert: SnapSyncNoPeers
        expr: app_snapsync_peers_capable_gauge == 0
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "No SNAP-capable peers connected"
          description: "SNAP sync cannot proceed without SNAP-capable peers"
      
      # Sync stalled
      - alert: SnapSyncStalled
        expr: rate(app_snapsync_accounts_synced_gauge[5m]) == 0
          and app_snapsync_phase_current_gauge == 1
        for: 10m
        labels:
          severity: critical
        annotations:
          summary: "SNAP sync appears stalled"
          description: "No accounts synced in the last 10 minutes during AccountRangeSync phase"
      
      # High error rate
      - alert: SnapSyncHighErrorRate
        expr: rate(app_snapsync_errors_total[5m]) > 1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High SNAP sync error rate"
          description: "More than 1 error per second in the last 5 minutes"
      
      # Invalid proofs
      - alert: SnapSyncInvalidProofs
        expr: rate(app_snapsync_proofs_invalid_total[5m]) > 0.1
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "SNAP sync receiving invalid proofs"
          description: "Peers are sending invalid Merkle proofs - potential security issue"
      
      # Request timeouts
      - alert: SnapSyncHighTimeoutRate
        expr: rate(app_snapsync_requests_timeouts_total[5m]) > 5
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High SNAP sync request timeout rate"
          description: "More than 5 request timeouts per second - network issues?"
      
      # Low throughput
      - alert: SnapSyncLowThroughput
        expr: app_snapsync_accounts_throughput_recent_gauge < 100
          and app_snapsync_phase_current_gauge == 1
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "SNAP sync throughput is low"
          description: "Account sync throughput is below 100 accounts/sec"
      
      # State validation failures
      - alert: SnapSyncValidationFailures
        expr: rate(app_snapsync_validation_failures_total[5m]) > 0
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "SNAP sync state validation failing"
          description: "State validation is failing - sync may be incomplete"
```

### Alertmanager Configuration

Example Alertmanager routing for SNAP sync alerts:

```yaml
route:
  group_by: ['alertname', 'severity']
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 12h
  receiver: 'snap-sync-team'
  routes:
    - match:
        alertname: SnapSyncInvalidProofs
      receiver: 'security-team'
      continue: true

receivers:
  - name: 'snap-sync-team'
    slack_configs:
      - channel: '#snap-sync-alerts'
        text: '{{ range .Alerts }}{{ .Annotations.summary }}: {{ .Annotations.description }}{{ end }}'
  
  - name: 'security-team'
    pagerduty_configs:
      - service_key: '<your-pagerduty-key>'
```

## Troubleshooting

### Common Issues

#### 1. No SNAP-Capable Peers

**Symptom**: `app_snapsync_peers_capable_gauge` is 0

**Solutions**:
- Check network connectivity
- Verify SNAP/1 capability is advertised in Hello message
- Ensure firewall allows peer connections
- Try connecting to specific SNAP-capable peers

#### 2. Sync Stalled

**Symptom**: No progress in accounts/bytecodes/slots for >10 minutes

**Solutions**:
- Check peer connectivity
- Review error metrics for failures
- Verify storage disk space
- Check for database locks
- Review logs for exceptions

#### 3. High Error Rate

**Symptom**: `app_snapsync_errors_total` increasing rapidly

**Solutions**:
- Identify error types in logs
- Check peer quality (blacklisting)
- Verify network stability
- Review error handler statistics

#### 4. Invalid Proofs

**Symptom**: `app_snapsync_proofs_invalid_total` incrementing

**Solutions**:
- **SECURITY ALERT**: Invalid proofs may indicate malicious peers
- Review blacklisted peers
- Consider stricter peer filtering
- Report to network operators

#### 5. Low Throughput

**Symptom**: Throughput below 100 accounts/sec or 500 slots/sec

**Solutions**:
- Increase concurrency (`account-concurrency`, `storage-concurrency`)
- Optimize database performance
- Add more peers
- Check CPU/disk I/O utilization

## Performance Tuning

### Configuration Parameters

Optimize SNAP sync performance in `conf/base.conf`:

```hocon
sync {
  do-snap-sync = true
  
  snap-sync {
    enabled = true
    pivot-block-offset = 1024
    
    # Concurrency tuning
    account-concurrency = 16    # Increase for faster account sync
    storage-concurrency = 8     # Balance with account concurrency
    storage-batch-size = 8      # Slots per storage request
    healing-batch-size = 16     # Nodes per healing request
    
    # Reliability tuning
    max-retries = 3             # Request retry limit
    timeout = 30.seconds        # Request timeout
    
    # Quality gates
    state-validation-enabled = true
  }
}
```

### Recommended Values

| Network | Account Concurrency | Storage Concurrency | Notes |
|---------|---------------------|---------------------|-------|
| Mordor Testnet | 16 | 8 | Good starting point |
| ETC Mainnet | 32 | 16 | High-performance setup |
| Limited Resources | 8 | 4 | Lower memory/CPU usage |

### Monitoring Performance Tuning

1. **Monitor throughput**: Watch `*_throughput_recent_gauge` metrics
2. **Adjust concurrency**: Increase if throughput plateaus
3. **Check resource usage**: Ensure CPU/memory/disk not saturated
4. **Balance phases**: Some phases may need different concurrency

## Integration with Existing Monitoring

### Adding to Existing Prometheus Configuration

Add SNAP sync scraping to your `prometheus.yml`:

```yaml
scrape_configs:
  - job_name: 'fukuii-snap-sync'
    scrape_interval: 10s
    static_configs:
      - targets: ['localhost:13798']
        labels:
          service: 'fukuii'
          component: 'snap-sync'
```

### Combining with Node Metrics

SNAP sync metrics complement existing Fukuii metrics:

- **Network**: Use `app_network_peers_connected` with `app_snapsync_peers_capable_gauge`
- **Blockchain**: Compare `app_blockchain_best_block_number` with `app_snapsync_pivot_block_number_gauge`
- **JVM**: Monitor heap usage during SNAP sync phases

## Best Practices

1. **Enable metrics in production**: Always enable Prometheus metrics
2. **Use structured logging**: Enable JSON logging for log aggregation
3. **Set up alerting**: Configure critical alerts (no peers, stalled sync, invalid proofs)
4. **Monitor peer quality**: Track blacklisting and timeout rates
5. **Tune concurrency**: Adjust based on observed throughput and resource usage
6. **Regular dashboard review**: Check Grafana dashboard daily during sync
7. **Correlate with logs**: Use metrics and logs together for troubleshooting
8. **Benchmark performance**: Record sync times for future comparison

## References

- [Metrics and Monitoring Guide](./metrics-and-monitoring.md) - General Fukuii observability
- [SNAP Sync Implementation](../architecture/SNAP_SYNC_IMPLEMENTATION.md) - Technical architecture
- [SNAP Sync Status](../architecture/SNAP_SYNC_STATUS.md) - Current implementation status
- [Prometheus Documentation](https://prometheus.io/docs/)
- [Grafana Documentation](https://grafana.com/docs/)
- [Kamon Documentation](https://kamon.io/docs/)

## See Also

- [Log Triage Guide](../runbooks/log-triage.md) - Analyzing SNAP sync logs
- [Peering Runbook](../runbooks/peering.md) - Managing peer connections
- [Disk Management](../runbooks/disk-management.md) - Storage optimization
