# Metrics and Monitoring

This document describes the metrics collection, logging, and monitoring capabilities of the Fukuii Ethereum Classic client.

## Overview

Fukuii provides comprehensive observability through:

- **Structured Logging** with stable JSON fields
- **Prometheus Metrics** for monitoring system and application health
- **JMX Metrics** exportable to Prometheus
- **Grafana Dashboards** for visualization
- **Kamon Instrumentation** for Apache Pekko actors

## Structured Logging

### Configuration

Logging is configured in `src/main/resources/logback.xml` and can be controlled via application configuration.

### Log Formats

Fukuii supports two log output formats:

1. **Plain Text** (Default): Human-readable format for console output
2. **JSON** (Structured): Machine-parseable format for log aggregation systems

### Enabling JSON Logging

To enable JSON structured logging, set the configuration property:

```hocon
logging {
  json-output = true
  logs-dir = "logs"
  logs-file = "fukuii"
  logs-level = "INFO"
}
```

Or set the environment variable:

```bash
export FUKUII_LOGGING_JSON_OUTPUT=true
```

### JSON Log Fields

When JSON logging is enabled, each log entry contains the following stable fields:

| Field | Description | Example |
|-------|-------------|---------|
| `timestamp` | ISO 8601 timestamp | `2024-11-02T02:00:00.000Z` |
| `level` | Log level | `INFO`, `WARN`, `ERROR`, `DEBUG` |
| `level_value` | Numeric log level | `20000` |
| `logger` | Logger name | `com.chipprbots.ethereum.blockchain.sync.SyncController` |
| `thread` | Thread name | `fukuii-system-pekko.actor.default-dispatcher-5` |
| `message` | Log message | `Block synchronization started` |
| `stack_trace` | Exception stack trace (if present) | Full stack trace string |
| `service` | Service name | `fukuii` |
| `node` | Node identifier | System hostname (default) or `FUKUII_NODE_ID` |
| `environment` | Deployment environment | `production` (default), `staging`, `dev` |

### MDC (Mapped Diagnostic Context) Fields

The following MDC fields are included when available:

- `peer`: Peer ID or address
- `block`: Block number or hash
- `transaction`: Transaction hash
- `actor`: Actor path or name

### Example JSON Log Entry

```json
{
  "timestamp": "2024-11-02T02:00:00.000Z",
  "level": "INFO",
  "level_value": 20000,
  "logger": "com.chipprbots.ethereum.blockchain.sync.SyncController",
  "thread": "fukuii-system-pekko.actor.default-dispatcher-5",
  "message": "Starting blockchain synchronization",
  "service": "fukuii",
  "node": "fukuii-node-1",
  "environment": "production",
  "block": "12345678"
}
```

### Environment Variables for Logging

- `FUKUII_NODE_ID`: Set a custom node identifier (defaults to hostname)
- `FUKUII_ENV`: Set the deployment environment (defaults to "production")

## Prometheus Metrics

### Enabling Metrics

Metrics collection is disabled by default. To enable, configure in `src/main/resources/conf/metrics.conf`:

```hocon
fukuii.metrics {
  enabled = true
  port = 13798
}
```

Or set the environment variable:

```bash
export FUKUII_METRICS_ENABLED=true
export FUKUII_METRICS_PORT=13798
```

### Accessing Metrics

Once enabled, metrics are exposed via HTTP at:

```
http://localhost:13798/metrics
```

### Available Metrics

Fukuii exposes metrics in several categories:

#### JVM Metrics

- `jvm_memory_used_bytes`: JVM memory usage by pool
- `jvm_memory_committed_bytes`: JVM memory committed
- `jvm_memory_max_bytes`: JVM memory maximum
- `jvm_gc_collection_seconds`: Garbage collection time
- `jvm_threads_current`: Current thread count
- `jvm_threads_daemon`: Daemon thread count

#### Application Metrics

Prefixed with `app_` or `fukuii_`:

- **Blockchain Sync:**
  - `app_regularsync_blocks_propagation_timer_seconds`: Block import timing
  - `app_fastsync_headers_received_total`: Headers received during fast sync
  - `app_fastsync_bodies_received_total`: Block bodies received

- **Network:**
  - `app_network_peers_connected`: Currently connected peer count
  - `app_network_messages_received_total`: Messages received by type
  - `app_network_messages_sent_total`: Messages sent by type

- **Mining:**
  - `app_mining_blocks_mined_total`: Total blocks mined
  - `app_mining_hashrate`: Current hashrate

- **Transaction Pool:**
  - `app_txpool_pending_count`: Pending transactions
  - `app_txpool_queued_count`: Queued transactions

#### Logback Metrics

Automatic logging metrics:

- `logback_events_total`: Log events by level
- `logback_appender_total`: Appender invocations

### Metric Labels

Many metrics include labels for filtering:

- `level`: Log level (for logging metrics)
- `blocktype`: Type of block (for sync metrics)
- `message_type`: Network message type
- `peer`: Peer identifier

## JMX to Prometheus Export

### JMX Configuration

Fukuii exposes JMX metrics on port **9095** by default. These metrics can be scraped by Prometheus using the JMX exporter.

### Using JMX Exporter

1. **Download JMX Exporter:**

   ```bash
   wget https://repo1.maven.org/maven2/io/prometheus/jmx/jmx_prometheus_javaagent/0.20.0/jmx_prometheus_javaagent-0.20.0.jar
   ```

2. **Create JMX Exporter Configuration** (`jmx-exporter-config.yml`):

   ```yaml
   lowercaseOutputName: true
   lowercaseOutputLabelNames: true
   rules:
   - pattern: ".*"
   ```

3. **Start Fukuii with JMX Exporter:**

   ```bash
   java -javaagent:jmx_prometheus_javaagent-0.20.0.jar=9095:jmx-exporter-config.yml \
        -jar fukuii.jar etc
   ```

4. **Configure Prometheus to scrape JMX metrics:**

   ```yaml
   scrape_configs:
     - job_name: 'fukuii-jmx'
       static_configs:
         - targets: ['localhost:9095']
           labels:
             service: 'fukuii'
             type: 'jmx'
   ```

### Key JMX Metrics

- `java_lang_Memory_*`: Memory pool metrics
- `java_lang_GarbageCollector_*`: GC metrics
- `java_lang_Threading_*`: Thread metrics
- `java_lang_OperatingSystem_*`: OS metrics

## Kamon Instrumentation

### Apache Pekko Actor Metrics

Kamon provides instrumentation for Apache Pekko (formerly Akka) actors:

```hocon
kamon.instrumentation.pekko.filters {
  actors.track {
    includes = [ "fukuii_system/user/*" ]
  }
  
  dispatchers {
    includes = ["**"]
  }
}
```

### Kamon Metrics

Available at `http://localhost:9095/metrics`:

- `pekko_actor_processing_time_seconds`: Actor message processing time
- `pekko_actor_mailbox_size`: Mailbox queue size
- `pekko_actor_messages_processed_total`: Total messages processed
- `pekko_dispatcher_threads_active`: Active dispatcher threads

## Grafana Dashboards

### Loading the Dashboard

A pre-configured Grafana dashboard is available at `/ops/grafana/fukuii-dashboard.json`.

### Importing the Dashboard

1. **Open Grafana UI** (typically `http://localhost:3000`)

2. **Import Dashboard:**
   - Click **+** → **Import**
   - Upload `/ops/grafana/fukuii-dashboard.json`
   - Select your Prometheus datasource
   - Click **Import**

### Dashboard Panels

The Fukuii dashboard includes:

- **System Overview:** Node info, uptime, peers
- **Blockchain Sync:** Sync status, block height, sync speed
- **Network:** Peer count, message rates, bandwidth
- **Mining:** Hashrate, blocks mined, mining rewards
- **Transaction Pool:** Pending/queued transactions
- **JVM Metrics:** Memory usage, GC activity, thread count
- **Performance:** Block import time, transaction processing

### Customization

The dashboard can be customized by:

1. Editing panels in Grafana UI
2. Modifying the JSON file and re-importing
3. Creating new dashboards using the Prometheus datasource

## Prometheus Configuration

### Basic Configuration

Example `prometheus.yml` for Fukuii:

```yaml
global:
  scrape_interval: 1m
  scrape_timeout: 10s
  evaluation_interval: 1m

scrape_configs:
  # Fukuii application metrics
  - job_name: 'fukuii-node'
    scrape_interval: 10s
    static_configs:
      - targets: ['localhost:13798']
        labels:
          service: 'fukuii'
          type: 'application'
  
  # Fukuii JMX/Pekko metrics
  - job_name: 'fukuii-pekko'
    scrape_interval: 10s
    static_configs:
      - targets: ['localhost:9095']
        labels:
          service: 'fukuii'
          type: 'jmx'
```

### Docker Compose Setup

For Docker deployments, see `docker/fukuii/prometheus/prometheus.yml` for the reference configuration.

## Best Practices

### Production Deployments

1. **Enable Metrics:** Always enable metrics in production
2. **Use JSON Logging:** Enable structured logging for log aggregation
3. **Set Environment:** Use `FUKUII_ENV` to tag logs by environment
4. **Set Node Identifier:** Use `FUKUII_NODE_ID` instead of hostname for security (e.g., `node-1`, `node-2`)
5. **Monitor Disk:** Alert on log file growth and metrics retention
6. **Secure Endpoints:** Use firewall rules to restrict metrics access

### Performance Considerations

1. **Scrape Intervals:** Use appropriate intervals (10-60s recommended)
2. **Retention:** Configure Prometheus retention based on disk space
3. **Cardinality:** Be cautious with high-cardinality labels
4. **Caller Data:** Keep `includeCallerData=false` in production

### Alerting

Configure Prometheus alerts for:

- High memory usage (>80%)
- Low peer count (<5 peers)
- Blockchain sync stalled (no new blocks in 10 minutes)
- High error rate in logs
- JVM GC pressure

### Log Aggregation

For centralized logging:

1. Enable JSON output
2. Use filebeat/fluentd to ship logs to:
   - Elasticsearch + Kibana
   - Loki + Grafana
   - Splunk
   - Datadog

### Example Filebeat Configuration

```yaml
filebeat.inputs:
- type: log
  enabled: true
  paths:
    - /var/log/fukuii/*.log
  json.keys_under_root: true
  json.add_error_key: true

output.elasticsearch:
  hosts: ["localhost:9200"]
  index: "fukuii-logs-%{+yyyy.MM.dd}"

setup.template.name: "fukuii-logs"
setup.template.pattern: "fukuii-logs-*"
```

## Troubleshooting

### Metrics Not Available

1. Check `fukuii.metrics.enabled = true`
2. Verify port 13798 is not blocked
3. Check logs for metrics initialization errors

### JSON Logs Not Working

1. Verify `logging.json-output = true`
2. Check logback.xml for STASH appender
3. Ensure janino dependency is present

### High Memory Usage

1. Check JVM heap settings
2. Review GC metrics in Grafana
3. Enable GC logging for analysis

### Grafana Dashboard Not Loading

1. Verify Prometheus datasource is configured
2. Check Prometheus is scraping Fukuii
3. Verify metrics are available at `/metrics` endpoint

## References

- [Prometheus Documentation](https://prometheus.io/docs/)
- [Grafana Documentation](https://grafana.com/docs/)
- [Logback Documentation](https://logback.qos.ch/)
- [Logstash Encoder](https://github.com/logfellow/logstash-logback-encoder)
- [Kamon Documentation](https://kamon.io/docs/)
- [Micrometer Documentation](https://micrometer.io/docs/)

## See Also

- [Operations Runbooks](../runbooks/README.md)
- [Log Triage Guide](../runbooks/log-triage.md)
- [Architecture Overview](../architecture-overview.md)
