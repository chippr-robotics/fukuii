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
├── testbed/                                      # Testbed - ETC mainnet testing environment
│   ├── conf/
│   │   ├── etc.conf                              # ETC mainnet configuration
│   │   └── logback.xml                           # DEBUG logging configuration
│   ├── docker-compose.yml                        # Docker Compose deployment
│   ├── start.sh                                  # Quick start/stop script
│   ├── README.md                                 # Testbed documentation
│   └── ISSUE_RESOLUTION.md                       # Issue tracking and resolution notes
├── gorgoroth/                                    # Gorgoroth - Internal test network
│   ├── conf/                                     # Configuration files for all nodes
│   ├── docker-compose-3nodes.yml                 # 3 Fukuii nodes
│   ├── docker-compose-6nodes.yml                 # 6 Fukuii nodes
│   ├── docker-compose-fukuii-geth.yml            # 3 Fukuii + 3 Core-Geth
│   ├── docker-compose-fukuii-besu.yml            # 3 Fukuii + 3 Besu
│   ├── docker-compose-mixed.yml                  # 3 Fukuii + 3 Besu + 3 Core-Geth
│   ├── deploy.sh                                 # Deployment management script
│   ├── collect-logs.sh                           # Log collection script
│   └── README.md                                 # Gorgoroth documentation
├── run-007-research/                             # Research directory for investigations
└── README.md                                     # This file
```

## Grafana

The `grafana/` directory contains pre-configured Grafana dashboards for monitoring Fukuii nodes, designed for Barad-dûr integration.

## Run Configurations

### Testbed - ETC Mainnet Testing Environment

The `testbed/` directory contains the primary testing configuration for running a Fukuii node on **ETC mainnet** with comprehensive logging and dual-node setup.

**Purpose**: General testing and validation environment for ETC mainnet operations.

**Features**:
- Network: ETC mainnet
- Dual-node setup: Fukuii + Core-Geth for comparison
- DEBUG logging configuration
- Docker Compose deployment ready
- Quick start script for easy management
- Historical issue resolution documentation

**Quick Start**:
```bash
cd ops/testbed
./start.sh start
```

For detailed information, see [testbed/README.md](testbed/README.md) and [testbed/ISSUE_RESOLUTION.md](testbed/ISSUE_RESOLUTION.md).

⚠️ **Note**: The testbed is the primary environment for all testing activities. Previous run-001 through run-005 folders have been retired.

### Gorgoroth - Internal Test Network

The `gorgoroth/` directory contains configurations for an internal private test network, named after the plateau in Mordor where Sauron trained his armies.

**Purpose**: Private network testing for multi-client interoperability and Fukuii validation.

**Features**:
- Network: Private test network (Chain ID: 1337)
- Discovery disabled (static peer connections)
- Multiple deployment configurations:
  - 3 Fukuii nodes
  - 6 Fukuii nodes
  - 3 Fukuii + 3 Core-Geth
  - 3 Fukuii + 3 Besu
  - 3 Fukuii + 3 Besu + 3 Core-Geth (mixed)
- Easy deployment and log collection scripts
- Pre-configured genesis with funded accounts

**Quick Start**:
```bash
cd ops/gorgoroth
./deploy.sh start 3nodes
```

For detailed information, see [gorgoroth/README.md](gorgoroth/README.md).

### Run 007 - Research

The `run-007-research/` directory contains research and investigation notes related to network protocol analysis.

For detailed information, see [run-007-research/README.md](run-007-research/README.md).

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
