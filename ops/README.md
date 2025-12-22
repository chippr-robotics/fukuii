# Operations Configuration

This directory contains operational configuration files and resources for running and monitoring Fukuii in production environments.

## Directory Structure

```
ops/
├── barad-dur/                                    # Barad-dûr - Kong API Gateway stack
│   ├── docker-compose.yml                        # Full stack with PostgreSQL
│   ├── docker-compose-dbless.yml                 # DB-less mode
│   ├── kong.yml                                  # Kong declarative config
│   ├── setup.sh                                  # Setup and initialization script
│   ├── test-api.sh                               # API testing script
│   ├── fukuii-conf/                              # Fukuii node configurations
│   ├── grafana/                                  # Grafana dashboards and provisioning
│   └── prometheus/                               # Prometheus configuration
├── cirith-ungol/                                 # Cirith Ungol - ETC mainnet testing environment
│   ├── conf/
│   │   ├── etc.conf                              # ETC mainnet configuration
│   │   └── logback.xml                           # DEBUG logging configuration
│   ├── docker-compose.yml                        # Docker Compose deployment
│   ├── start.sh                                  # Quick start/stop script
│   ├── README.md                                 # Cirith Ungol documentation
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
├── grafana/                                      # Grafana dashboard configurations
│   ├── fukuii-dashboard.json                    # Control Tower - Main monitoring dashboard
│   ├── fukuii-miners-dashboard.json             # Miners - Mining-focused metrics
│   ├── fukuii-node-troubleshooting-dashboard.json  # Node Troubleshooting - Debug dashboard
│   └── fukuii-casual-dashboard.json             # Casual View - Minimalist dashboard
├── prometheus/                                   # Prometheus configuration
├── run-007-research/                             # Research directory for investigations
└── README.md                                     # This file
```

## Grafana

The `grafana/` directory contains pre-configured Grafana dashboards for monitoring Fukuii nodes, designed for Barad-dûr integration.

## Run Configurations

### Barad-dûr - Kong API Gateway Stack

The `barad-dur/` directory contains a production-ready API ops stack with Kong API Gateway, named after Sauron's Dark Tower - the fortified gateway to Fukuii.

**Purpose**: Production API gateway with high availability, monitoring, and security features.

**Features**:
- Kong API Gateway (3.9) with PostgreSQL backend
- Multiple Fukuii instances with load balancing
- Prometheus metrics collection
- Grafana visualization dashboards
- DB-less mode option for simpler deployments
- Automated setup and testing scripts

**Quick Start**:
```bash
cd ops/barad-dur
./setup.sh
```

For detailed information, see [barad-dur/README.md](barad-dur/README.md).

### Cirith Ungol - ETC Mainnet Testing Environment

The `cirith-ungol/` directory contains the testing configuration for running a Fukuii node on **ETC mainnet** with comprehensive logging, named after the pass of the spider in Mordor.

**Purpose**: General testing and validation environment for ETC mainnet operations.

**Features**:
- Network: ETC mainnet
- DEBUG logging configuration
- Docker Compose deployment ready
- Quick start script for easy management
- Historical issue resolution documentation

**Quick Start**:
```bash
cd ops/cirith-ungol
./start.sh start
```

For detailed information, see [cirith-ungol/README.md](cirith-ungol/README.md) and [cirith-ungol/ISSUE_RESOLUTION.md](cirith-ungol/ISSUE_RESOLUTION.md).

### Gorgoroth - Internal Test Network

The `gorgoroth/` directory contains configurations for an internal private test network, named after the plateau in Mordor where Sauron trained his armies.

**Purpose**: Private network testing for multi-client interoperability and Fukuii validation.

**Features**:
- Network: Private test network (Mordor-aligned; Chain ID: 0x3f / 63; Network ID: 7)
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
fukuii-cli start 3nodes
```

**New to Gorgoroth?** See the [Quick Start Guide](gorgoroth/QUICKSTART.md) for a complete step-by-step setup walkthrough.

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
