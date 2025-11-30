# Barad-dûr (Kong API Gateway) Architecture for Fukuii

This document describes the architecture of the Barad-dûr (Kong API Gateway) setup for Fukuii Ethereum Classic nodes.

## High-Level Architecture

```
                                    Internet
                                       │
                                       │ HTTPS/HTTP
                                       ▼
                         ┌─────────────────────────┐
                         │   Reverse Proxy/CDN     │
                         │  (nginx/CloudFlare)     │
                         │      (Optional)         │
                         └─────────────────────────┘
                                       │
                                       │ HTTPS/HTTP
                                       ▼
                         ┌─────────────────────────┐
                         │    Firewall/WAF         │
                         │   (iptables/AWS SG)     │
                         └─────────────────────────┘
                                       │
                    ┌──────────────────┴──────────────────┐
                    │                                     │
                    │  Kong API Gateway (Port 8000/8443) │
                    │                                     │
                    │  ┌─────────────────────────────┐   │
                    │  │  Authentication Plugins      │   │
                    │  │  - Basic Auth               │   │
                    │  │  - JWT Auth                 │   │
                    │  │  - API Key Auth             │   │
                    │  └─────────────────────────────┘   │
                    │                                     │
                    │  ┌─────────────────────────────┐   │
                    │  │  Security Plugins           │   │
                    │  │  - Rate Limiting            │   │
                    │  │  - IP Restriction           │   │
                    │  │  - CORS                     │   │
                    │  │  - Request Validation       │   │
                    │  └─────────────────────────────┘   │
                    │                                     │
                    │  ┌─────────────────────────────┐   │
                    │  │  Routing & Load Balancing   │   │
                    │  │  - Round Robin              │   │
                    │  │  - Health Checks            │   │
                    │  │  - Failover                 │   │
                    │  └─────────────────────────────┘   │
                    │                                     │
                    │  ┌─────────────────────────────┐   │
                    │  │  Observability              │   │
                    │  │  - Prometheus Metrics       │   │
                    │  │  - Access Logs              │   │
                    │  │  - Error Logs               │   │
                    │  └─────────────────────────────┘   │
                    │                                     │
                    └──────────────────┬──────────────────┘
                                       │
                         ┌─────────────┴─────────────┐
                         │                           │
                         │   PostgreSQL Database     │
                         │   (Kong Configuration)    │
                         │                           │
                         └───────────────────────────┘
                                       │
          ┌────────────────────────────┼────────────────────────────┐
          │                            │                            │
          ▼                            ▼                            ▼
┌─────────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐
│  Fukuii Primary     │  │  Fukuii Secondary   │  │  Fukuii Tertiary    │
│  ─────────────────  │  │  ─────────────────  │  │  ─────────────────  │
│  JSON-RPC: 8546     │  │  JSON-RPC: 8546     │  │  JSON-RPC: 8546     │
│  WebSocket: 8546    │  │  WebSocket: 8546    │  │  WebSocket: 8546    │
│  P2P: 30303         │  │  P2P: 30303         │  │  P2P: 30303         │
│  Metrics: 9095      │  │  Metrics: 9095      │  │  Metrics: 9095      │
│                     │  │                     │  │                     │
│  Status: Active     │  │  Status: Active     │  │  Status: Standby    │
└─────────────────────┘  └─────────────────────┘  └─────────────────────┘
          │                            │                            │
          │                            │                            │
          └────────────────────────────┼────────────────────────────┘
                                       │
                                       │ Metrics Scraping
                                       ▼
                         ┌─────────────────────────┐
                         │     Prometheus          │
                         │  (Metrics Collection)   │
                         │                         │
                         │  - Kong Metrics         │
                         │  - Fukuii Metrics       │
                         │  - System Metrics       │
                         │                         │
                         │  Retention: 30 days     │
                         └─────────────────────────┘
                                       │
                                       │ Data Source
                                       ▼
                         ┌─────────────────────────┐
                         │       Grafana           │
                         │   (Visualization)       │
                         │                         │
                         │  - Kong Dashboard       │
                         │  - Fukuii Dashboard     │
                         │  - System Dashboard     │
                         │                         │
                         │  Port: 3000             │
                         └─────────────────────────┘
```

## Component Details

### Kong API Gateway

**Role**: Central API gateway for all client requests

**Responsibilities**:
- Route requests to healthy Fukuii instances
- Authenticate and authorize requests
- Apply rate limiting and security policies
- Collect and expose metrics
- Log all API access

**Key Features**:
- Load balancing with round-robin algorithm
- Active health checks every 10 seconds
- Passive health checks on request failures
- Automatic failover to healthy instances
- Prometheus metrics export on port 8001

**Ports**:
- `8000`: HTTP proxy (client-facing)
- `8443`: HTTPS proxy (client-facing)
- `8001`: Admin API (internal)
- `8444`: Admin API HTTPS (internal)

### PostgreSQL Database

**Role**: Persistent storage for Kong configuration

**Responsibilities**:
- Store services, routes, and plugins configuration
- Store consumer credentials and ACLs
- Track rate limiting counters
- Log plugin data

**Configuration**:
- Database: `kong`
- User: `kong` (configurable via POSTGRES_USER)

**Scaling**:
For production, consider using managed PostgreSQL services (AWS RDS, Cloud SQL, etc.) or configure PostgreSQL replication:
```yaml
postgres-primary:
  environment:
    - POSTGRES_USER=kong
    - POSTGRES_PASSWORD=secure_password
    - POSTGRES_DB=kong
  # Configure streaming replication for HA
```

### Fukuii Instances

**Role**: Ethereum Classic blockchain nodes

**Responsibilities**:
- Sync with Ethereum Classic network
- Process JSON-RPC requests
- Maintain blockchain state
- Expose metrics

**High Availability Configuration**:
- **Primary**: Main active instance handling requests
- **Secondary**: Backup instance for failover and load sharing
- **Tertiary+**: Additional instances for higher capacity

**Health Endpoints**:
- `/health`: Liveness check (process running)
- `/readiness`: Readiness check (synced and ready)
- `/healthcheck`: Detailed health status

### Prometheus

**Role**: Metrics collection and storage

**Responsibilities**:
- Scrape metrics from Kong, Fukuii, and system
- Store time-series data
- Evaluate alerting rules
- Provide query API for Grafana

**Metrics Collected**:
- **Kong**: Request rate, latency, status codes, bandwidth
- **Fukuii**: Block height, peer count, sync status, transaction pool
- **System**: CPU, memory, disk, network

**Retention**: 30 days (configurable)

### Grafana

**Role**: Visualization and dashboards

**Responsibilities**:
- Visualize metrics from Prometheus
- Create alerting rules
- Provide dashboards for monitoring

**Pre-configured Dashboards**:
- Kong API Gateway metrics
- Fukuii node status and performance
- System resource utilization

## Request Flow

### Standard JSON-RPC Request

```
1. Client sends request to Kong (HTTP POST to /rpc)
   │
   ▼
2. Kong validates request
   ├── Check authentication (Basic Auth/JWT/API Key)
   ├── Check rate limits
   ├── Validate request format
   └── Check ACLs
   │
   ▼
3. Kong selects upstream target
   ├── Check health status of all targets
   ├── Select healthy target using round-robin
   └── Mark failed targets as unhealthy
   │
   ▼
4. Kong proxies request to Fukuii instance
   │
   ▼
5. Fukuii processes JSON-RPC request
   │
   ▼
6. Fukuii returns response
   │
   ▼
7. Kong returns response to client
   ├── Add response headers (CORS, etc.)
   ├── Log request/response
   └── Update metrics
   │
   ▼
8. Client receives response
```

### HD Wallet Multi-Network Request

```
1. Client sends request to Kong (HTTP POST to /bitcoin or /eth or /etc)
   │
   ▼
2. Kong routes based on path
   ├── /bitcoin, /btc → Bitcoin JSON-RPC backend (if configured)
   ├── /ethereum, /eth → Ethereum JSON-RPC backend (if configured)
   └── /etc, /ethereum-classic → Fukuii ETC backend
   │
   ▼
3. [Same as standard flow steps 2-8]
```

## Network Topology

### Docker Network

All services communicate on the `fukuii-network` bridge network:

```
fukuii-network (172.18.0.0/16)
├── cassandra (172.18.0.2)
├── kong (172.18.0.3)
├── fukuii-primary (172.18.0.4)
├── fukuii-secondary (172.18.0.5)
├── prometheus (172.18.0.6)
└── grafana (172.18.0.7)
```

### Port Mapping

**External (Host) → Internal (Container)**

```
Host Port  →  Service         Container Port  Purpose
─────────────────────────────────────────────────────────
8000       →  kong            8000            HTTP Proxy
8443       →  kong            8443            HTTPS Proxy
8001       →  kong            8001            Admin API
8444       →  kong            8444            Admin API HTTPS
8545       →  fukuii-primary  8546            JSON-RPC (direct, for testing)
8546       →  fukuii-primary  8546            WebSocket (direct, for testing)
8547       →  fukuii-secondary 8546           JSON-RPC (direct, for testing)
8548       →  fukuii-secondary 8546           WebSocket (direct, for testing)
30303      →  fukuii-primary  30303           P2P
30304      →  fukuii-secondary 30303          P2P
9090       →  prometheus      9090            Web UI
9095       →  fukuii-primary  9095            Metrics
9096       →  fukuii-secondary 9095           Metrics
3000       →  grafana         3000            Web UI
```

## Security Layers

### Layer 1: Network Security
- Firewall rules (iptables/cloud security groups)
- IP whitelisting
- VPC/subnet isolation

### Layer 2: Kong IP Restriction (Optional)
- Plugin: `ip-restriction`
- Whitelist trusted IP ranges
- Block malicious IPs

### Layer 3: Kong Rate Limiting
- Plugin: `rate-limiting`
- Limits: 100 req/min, 5000 req/hour per consumer
- Prevents DoS attacks

### Layer 4: Kong Authentication
- Plugins: `basic-auth`, `jwt`, `key-auth`
- Validates user credentials
- Required for all API endpoints

### Layer 5: Kong Authorization
- Plugin: `acl`
- Group-based access control
- Role-based permissions (admin, developer, user)

### Layer 6: Request Validation
- Plugin: `request-validator`
- Schema validation for JSON-RPC
- Prevents injection attacks

### Layer 7: Fukuii Internal Security
- RPC API configuration
- Node key authentication for P2P
- Internal firewall rules

## Scalability

### Vertical Scaling

Increase resources for individual components:

```yaml
fukuii-primary:
  deploy:
    resources:
      limits:
        cpus: '4'
        memory: 16G
      reservations:
        cpus: '2'
        memory: 8G
```

### Horizontal Scaling

Add more instances:

**Kong Scaling**:
```bash
docker-compose up -d --scale kong=3
```

**Fukuii Scaling**:
Add more fukuii instances in `docker-compose.yml` and update Kong upstream targets.

**Cassandra Scaling**:
Deploy multi-node Cassandra cluster with proper replication.

### Multi-Region Deployment

For global distribution:

1. Deploy stack in multiple regions
2. Use Cassandra multi-datacenter replication
3. Configure DNS-based routing (Route53, CloudFlare)
4. Set up cross-region monitoring

## Monitoring and Observability

### Key Metrics

**Kong Metrics**:
- `kong_http_requests_total`: Total requests
- `kong_latency`: Request latency (min, max, avg)
- `kong_bandwidth_bytes`: Bandwidth usage
- `kong_upstream_status`: Upstream health status

**Fukuii Metrics**:
- Block height (best block number)
- Peer count
- Sync status
- Transaction pool size
- Memory usage

**System Metrics**:
- CPU utilization
- Memory usage
- Disk I/O
- Network throughput

### Alerting Rules

Example Prometheus alert rules:

```yaml
groups:
  - name: kong_alerts
    rules:
      - alert: HighErrorRate
        expr: rate(kong_http_requests_total{code=~"5.."}[5m]) > 0.05
        for: 5m
        annotations:
          summary: "High error rate detected"

      - alert: HighLatency
        expr: histogram_quantile(0.95, rate(kong_latency_bucket[5m])) > 5000
        for: 5m
        annotations:
          summary: "High API latency detected"

  - name: fukuii_alerts
    rules:
      - alert: FukuiiNotSyncing
        expr: increase(fukuii_best_block_number[10m]) == 0
        for: 10m
        annotations:
          summary: "Fukuii node stopped syncing"

      - alert: LowPeerCount
        expr: fukuii_peer_count < 5
        for: 5m
        annotations:
          summary: "Low peer count detected"
```

## Disaster Recovery

### Backup Strategy

**Cassandra Backups**:
```bash
# Daily automated snapshot
docker exec cassandra nodetool snapshot fukuii-backup

# Export to external storage
docker cp cassandra:/var/lib/cassandra/snapshots ./backups/
```

**Fukuii Data Backups**:
```bash
# Stop node for consistent backup
docker-compose stop fukuii-primary

# Backup data volume
docker run --rm \
  -v fukuii-data:/source \
  -v $(pwd)/backups:/backup \
  alpine tar czf /backup/fukuii-$(date +%Y%m%d).tar.gz -C /source .

# Restart node
docker-compose start fukuii-primary
```

### Recovery Procedures

**Kong Recovery**:
1. Restore Cassandra from backup
2. Restart Kong with existing configuration
3. Verify services and routes

**Fukuii Recovery**:
1. Restore data volume from backup
2. Start Fukuii instance
3. Wait for sync to resume
4. Verify block height and peers

### Failover Testing

Regular failover drills:
```bash
# Simulate primary failure
docker-compose stop fukuii-primary

# Verify Kong routes to secondary
curl -X POST http://localhost:8000/ \
  -u admin:password \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'

# Restore primary
docker-compose start fukuii-primary
```

## Performance Tuning

### Kong Optimization

```yaml
environment:
  - KONG_NGINX_WORKER_PROCESSES=auto
  - KONG_NGINX_WORKER_CONNECTIONS=4096
  - KONG_MEM_CACHE_SIZE=128m
  - KONG_DB_CACHE_TTL=3600
```

### Fukuii Optimization

```hocon
fukuii {
  sync {
    do-fast-sync = true
    block-headers-per-request = 128
    max-concurrent-requests = 50
  }
  
  db {
    rocks-db {
      block-cache-size = 512000000
      write-buffer-size = 67108864
    }
  }
}
```

### Cassandra Optimization

```yaml
environment:
  - MAX_HEAP_SIZE=4G
  - HEAP_NEWSIZE=800M
  - CASSANDRA_NUM_TOKENS=256
```

## Troubleshooting

See the [README.md](README.md#troubleshooting) for detailed troubleshooting steps.

## References

- [Kong Documentation](https://docs.konghq.com/)
- [Cassandra Documentation](https://cassandra.apache.org/doc/)
- [Prometheus Documentation](https://prometheus.io/docs/)
- [Grafana Documentation](https://grafana.com/docs/)
- [Fukuii Documentation](../index.md)
