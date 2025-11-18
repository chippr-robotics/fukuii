# Operations Configuration

This directory contains operational configuration files and resources for running and monitoring Fukuii in production environments.

## Directory Structure

```
ops/
├── grafana/              # Grafana dashboard configurations
│   └── fukuii-dashboard.json
└── README.md            # This file
```

## Grafana

The `grafana/` directory contains pre-configured Grafana dashboards for monitoring Fukuii nodes.

### Available Dashboards

- **fukuii-dashboard.json**: Main Fukuii node monitoring dashboard
  - System overview and health
  - Blockchain synchronization metrics
  - Network peer and message statistics
  - Mining metrics (if mining is enabled)
  - Transaction pool status
  - JVM metrics and performance

### Using the Dashboard

1. Import the dashboard into your Grafana instance:
   - Navigate to Grafana UI (typically `http://localhost:3000`)
   - Go to Dashboards → Import
   - Upload `ops/grafana/fukuii-dashboard.json`
   - Select your Prometheus datasource
   - Click Import

2. The dashboard requires:
   - Grafana 7.0 or later
   - Prometheus datasource configured
   - Fukuii metrics enabled (`fukuii.metrics.enabled = true`)

### Dashboard Requirements

The dashboard expects the following Prometheus scrape jobs to be configured:

```yaml
scrape_configs:
  - job_name: 'fukuii-node'
    static_configs:
      - targets: ['localhost:13798']  # Fukuii metrics endpoint
  
  - job_name: 'fukuii-pekko'
    static_configs:
      - targets: ['localhost:9095']   # JMX/Kamon metrics endpoint
```

## Metrics Configuration

For detailed information about metrics, logging, and monitoring, see:
- [Metrics and Monitoring Guide](../docs/operations/metrics-and-monitoring.md)

## Prometheus Configuration

Example Prometheus configuration files can be found in:
- `docker/fukuii/prometheus/prometheus.yml`

## Related Documentation

- [Operations Runbooks](../docs/runbooks/README.md)
- [Docker Documentation](../docs/deployment/docker.md)
- [Architecture Overview](../docs/architecture/architecture-overview.md)
