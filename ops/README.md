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
├── run-001/                                      # Run 001 - Mordor testnet with debug sync/snap logging
│   ├── conf/
│   │   ├── mordor.conf                           # Mordor testnet configuration
│   │   └── logback.xml                           # DEBUG logging for sync/snap
│   ├── docker-compose.yml                        # Docker Compose deployment
│   ├── start.sh                                  # Quick start/stop script
│   └── README.md                                 # Run 001 documentation
├── run-002/                                      # Run 002 - Mordor testnet with network diagnostics
│   ├── conf/
│   │   ├── mordor.conf                           # Mordor testnet configuration
│   │   └── logback.xml                           # DEBUG logging for network + snap
│   ├── docker-compose.yml                        # Docker Compose deployment
│   ├── start.sh                                  # Quick start/stop script
│   └── README.md                                 # Run 002 documentation
├── run-003/                                      # Run 003 - ETC mainnet with SNAP sync focus
│   ├── conf/
│   │   ├── etc.conf                              # ETC mainnet configuration
│   │   └── logback.xml                           # DEBUG logging for snap/network
│   ├── docker-compose.yml                        # Docker Compose deployment
│   ├── start.sh                                  # Quick start/stop script
│   ├── README.md                                 # Run 003 documentation
│   └── SYNC_BEHAVIOR.md                          # Sync mode switching behavior
├── run-004/                                      # Run 004 - ETC mainnet with extended timeouts
│   ├── conf/
│   │   ├── etc.conf                              # ETC mainnet with extended timeouts
│   │   └── logback.xml                           # Enhanced DEBUG logging
│   ├── docker-compose.yml                        # Docker Compose deployment
│   ├── start.sh                                  # Quick start/stop script
│   ├── README.md                                 # Run 004 documentation
│   ├── TIMEOUT_ANALYSIS.md                       # Analysis of run-003 timeout issue
│   └── 003.log                                   # Run 003 log file (analyzed)
└── README.md                                     # This file
```

## Grafana

The `grafana/` directory contains pre-configured Grafana dashboards for monitoring Fukuii nodes, designed for Barad-dûr integration.

## Run Configurations

### Run 001 - Mordor Testnet with Debug Logging

The `run-001/` directory contains a complete deployment configuration for running a Fukuii node on the **Mordor testnet** (Ethereum Classic testnet) with enhanced debug logging for sync and snap components.

**Purpose**: Safe development and debugging environment for troubleshooting synchronization issues on testnet.

**Features**:
- Network: Mordor (Ethereum Classic testnet - safe for testing)
- DEBUG logging enabled for all sync components (regular, fast, snap)
- Docker Compose deployment ready
- Quick start script for easy management

**Quick Start**:
```bash
cd ops/run-001
./start.sh start
```

For detailed information, see [run-001/README.md](run-001/README.md).

⚠️ **Note**: This configuration uses Mordor testnet for safety. It is optimized for debugging and should not be used in production due to verbose logging.

### Run 002 - Mordor Testnet with Network Diagnostics

The `run-002/` directory extends run-001 with enhanced network and RLPx protocol debugging.

**Purpose**: Diagnose peer communication and network protocol issues.

**Features**:
- Network: Mordor (Ethereum Classic testnet)
- DEBUG logging for network, RLPx, and peer discovery
- Enhanced snap sync diagnostics
- Docker Hub image (no authentication required)

**Quick Start**:
```bash
cd ops/run-002
./start.sh start
```

For detailed information, see [run-002/README.md](run-002/README.md).

### Run 003 - ETC Mainnet with SNAP Sync Focus

The `run-003/` directory focuses on SNAP sync testing on ETC mainnet with reduced blacklist durations.

**Purpose**: Test SNAP sync protocol on mainnet with better peer availability.

**Key Changes from Run 002**:
- Network: ETC mainnet (better peer availability than Mordor)
- FastSync logging reduced to INFO (too verbose)
- SNAP sync exclusive focus (fast sync disabled)
- Reduced blacklist durations for faster peer retry

**Quick Start**:
```bash
cd ops/run-003
./start.sh start
```

For detailed information, see [run-003/README.md](run-003/README.md) and [run-003/SYNC_BEHAVIOR.md](run-003/SYNC_BEHAVIOR.md).

### Run 004 - ETC Mainnet with Extended Timeouts

The `run-004/` directory addresses timeout and peer disconnection issues identified in run-003.

**Purpose**: Diagnose and fix SNAP sync timeout/blacklist race condition.

**Key Findings from Run 003**:
- Peers blacklisted 15s after GetAccountRange requests
- SNAP request timeouts at 30s (no responses received)
- Root cause: Peers blacklisted before they can respond

**Key Changes from Run 003**:
- Extended timeouts: peer-response-timeout 60s → 90s, snap-sync.request-timeout 30s → 60s
- Enhanced DEBUG logging for blacklist and request tracking
- Focus on understanding "Some other reason specific to a subprotocol" blacklisting

**Quick Start**:
```bash
cd ops/run-004
./start.sh start
```

For detailed information, see:
- [run-004/README.md](run-004/README.md) - Configuration and setup
- [run-004/TIMEOUT_ANALYSIS.md](run-004/TIMEOUT_ANALYSIS.md) - Detailed analysis of run-003 issue
- [run-004/003.log](run-004/003.log) - Original run-003 log file analyzed

⚠️ **Note**: Run-004 uses extended timeouts which may result in slower failure detection. This is acceptable for diagnostic purposes.

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
