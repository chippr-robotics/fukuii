# Operations Configuration

This directory contains operational configuration files and resources for running and monitoring Fukuii in production environments.

## Directory Structure

```
ops/
├── grafana/                                      # Grafana dashboard configurations
│   ├── fukuii-dashboard.json                    # Control Tower - Main monitoring dashboard
│   ├── fukuii-miners-dashboard.json             # Miners - Mining-focused metrics
│   ├── fukuii-node-troubleshooting-dashboard.json  # Node Troubleshooting - Debug dashboard
│   └── fukuii-casual-dashboard.json             # Casual View - Minimalist dashboard
├── prometheus/                                   # Prometheus configuration
├── run-001/                                      # Run 001 - ETC with debug sync/snap logging
│   ├── conf/
│   │   ├── etc.conf                              # ETC network configuration
│   │   └── logback.xml                           # DEBUG logging for sync/snap
│   ├── docker-compose.yml                        # Docker Compose deployment
│   ├── start.sh                                  # Quick start/stop script
│   └── README.md                                 # Run 001 documentation
└── README.md                                     # This file
```

## Grafana

The `grafana/` directory contains pre-configured Grafana dashboards for monitoring Fukuii nodes, designed for Barad-dûr integration.

## Run Configurations

### Run 001 - ETC with Debug Logging

The `run-001/` directory contains a complete deployment configuration for running a Fukuii node on the Ethereum Classic (ETC) network with enhanced debug logging for sync and snap components.

**Purpose**: Development and debugging environment for troubleshooting synchronization issues.

**Features**:
- Network: Ethereum Classic (ETC)
- DEBUG logging enabled for all sync components (regular, fast, snap)
- Docker Compose deployment ready
- Quick start script for easy management

**Quick Start**:
```bash
cd ops/run-001
./start.sh start
```

For detailed information, see [run-001/README.md](run-001/README.md).

⚠️ **Note**: This configuration is optimized for debugging and should not be used in production due to verbose logging.

### Available Dashboards

#### Control Tower (fukuii-dashboard.json)
Main Fukuii node monitoring dashboard - comprehensive view for operations:
- System overview and health
- Blockchain synchronization metrics (Regular Sync & Fast Sync)
- Network peer and message statistics
- Transaction pool status
- JVM metrics and performance
- Pekko actor system metrics

#### Miners Dashboard (fukuii-miners-dashboard.json)
Focused dashboard for mining operations:
- Block height and mining rate
- Block generation time and difficulty
- Gas usage and utilization
- Block time statistics
- Ommer (uncle/stale) block tracking
- Transaction pool monitoring

#### Node Troubleshooting (fukuii-node-troubleshooting-dashboard.json)
Single-node focused dashboard for debugging:
- Sync phase progress (Regular/Fast sync)
- JVM heap memory usage
- Garbage collection metrics (GC time, GC rate)
- RPC latency and call rates
- Peer churn tracking
- Network message rates
- File descriptor usage

#### Casual View (fukuii-casual-dashboard.json)
Minimalist at-a-glance dashboard:
- Current block height
- Connected peers count
- Transaction pool size
- Memory and gas usage gauges
- Simple sync progress chart

### Using the Dashboards

1. Import the dashboards into your Grafana instance:
   - Navigate to Grafana UI (typically `http://localhost:3000`)
   - Go to Dashboards → Import
   - Upload any of the dashboard JSON files from `ops/grafana/`
   - Select your Prometheus datasource
   - Click Import

2. The dashboards require:
   - Grafana 7.0 or later
   - Prometheus datasource configured
   - Fukuii metrics enabled (`fukuii.metrics.enabled = true`)

### Recommended Setup

| Use Case | Dashboard |
|----------|-----------|
| Day-to-day monitoring | Control Tower |
| Mining operations | Miners |
| Debugging issues | Node Troubleshooting |
| Wall display / casual viewing | Casual View |

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
