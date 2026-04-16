# Barad-dûr Operations Runbook

This runbook provides operational procedures for running and maintaining the Barad-dûr (Kong API Gateway) stack with Fukuii Ethereum Classic nodes.

## Table of Contents

1. [Overview](#overview)
2. [Daily Operations](#daily-operations)
3. [Startup and Shutdown](#startup-and-shutdown)
4. [Health Monitoring](#health-monitoring)
5. [Incident Response](#incident-response)
6. [Backup and Recovery](#backup-and-recovery)
7. [Scaling Operations](#scaling-operations)
8. [Maintenance Procedures](#maintenance-procedures)
9. [Troubleshooting Guide](#troubleshooting-guide)

---

## Overview

### What is Barad-dûr?

Barad-dûr is a production-ready API ops stack that combines:
- **Kong API Gateway** for request routing, authentication, and rate limiting
- **Multiple Fukuii instances** for high availability
- **PostgreSQL** for Kong configuration storage
- **Prometheus** for metrics collection
- **Grafana** for visualization

### Component Ports

| Component         | Port  | Purpose                    |
|-------------------|-------|----------------------------|
| Kong Proxy        | 8000  | HTTP API endpoint          |
| Kong Proxy HTTPS  | 8443  | HTTPS API endpoint         |
| Kong Admin        | 8001  | Admin API (internal)       |
| Fukuii Primary    | 8545  | JSON-RPC (direct)          |
| Fukuii Primary    | 8546  | WebSocket (direct)         |
| Fukuii Primary    | 30303 | P2P network                |
| Fukuii Secondary  | 8547  | JSON-RPC (direct)          |
| Fukuii Secondary  | 8548  | WebSocket (direct)         |
| Fukuii Secondary  | 30304 | P2P network                |
| Prometheus        | 9090  | Metrics UI                 |
| Grafana           | 3000  | Dashboards                 |

### Directory Structure

```
ops/barad-dur/
├── docker-compose.yml          # Full stack configuration
├── docker-compose-dbless.yml   # DB-less Kong variant
├── kong.yml                    # Kong declarative config
├── .env                        # Environment variables
├── fukuii-conf/                # Fukuii configuration
├── prometheus/                 # Prometheus config
├── grafana/                    # Grafana dashboards
└── data/                       # Persistent data
    ├── fukuii/                 # Primary node data
    ├── fukuii-secondary/       # Secondary node data
    ├── postgres/               # PostgreSQL data
    ├── prometheus/             # Prometheus data
    └── grafana/                # Grafana data
```

---

## Daily Operations

### Morning Checklist

1. **Check Service Health**
   ```bash
   cd ops/barad-dur
   docker-compose ps
   ```
   All services should show `Up (healthy)`.

2. **Verify Kong Gateway**
   ```bash
   curl -s http://localhost:8001/status | jq .
   ```
   Expected: `"database": {"reachable": true}`

3. **Check Fukuii Sync Status**
   ```bash
   curl -X POST http://localhost:8000/ \
     -u admin:YOUR_PASSWORD \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":1}'
   ```
   Expected: `"result": false` (if synced) or sync progress details.

4. **Verify Peer Connectivity**
   ```bash
   curl -X POST http://localhost:8000/ \
     -u admin:YOUR_PASSWORD \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}'
   ```
   Expected: At least 5-10 peers.

5. **Review Grafana Dashboards**
   - Open http://localhost:3000
   - Check Kong request rate and latency
   - Verify Fukuii node metrics

### Health Check Commands

```bash
# Quick health check
./test-api.sh

# Detailed health status
curl http://localhost:8000/healthcheck | jq .

# Kong upstream health
curl http://localhost:8001/upstreams/fukuii-cluster/health | jq .
```

---

## Startup and Shutdown

### Normal Startup

```bash
cd ops/barad-dur

# Pull latest images (optional)
docker-compose pull

# Start all services
docker-compose up -d

# Wait for health checks to pass (2-3 minutes)
watch docker-compose ps
```

**Startup Order:**
1. PostgreSQL starts and becomes healthy
2. Kong migrations run
3. Kong starts and connects to PostgreSQL
4. Fukuii nodes start syncing
5. Prometheus starts scraping
6. Grafana becomes available

### Graceful Shutdown

```bash
cd ops/barad-dur

# Graceful shutdown (allows connections to drain)
docker-compose stop

# Remove containers but keep data
docker-compose down

# Remove containers AND volumes (data loss)
docker-compose down -v
```

### Emergency Shutdown

```bash
# Force stop all containers immediately
docker-compose kill

# Remove all containers
docker-compose down --remove-orphans
```

### Restarting Individual Services

```bash
# Restart Kong (after config changes)
docker-compose restart kong

# Restart Fukuii nodes
docker-compose restart fukuii-primary fukuii-secondary

# Restart monitoring stack
docker-compose restart prometheus grafana
```

---

## Health Monitoring

### Key Metrics to Monitor

#### Kong Gateway Metrics

| Metric                          | Warning Threshold | Critical Threshold |
|---------------------------------|-------------------|-------------------|
| Request rate (5xx errors)       | > 1%              | > 5%              |
| Request latency (p95)           | > 2s              | > 5s              |
| Rate limit violations           | > 10/min          | > 50/min          |
| Authentication failures         | > 5/min           | > 20/min          |

#### Fukuii Node Metrics

| Metric                          | Warning Threshold | Critical Threshold |
|---------------------------------|-------------------|-------------------|
| Peer count                      | < 5               | < 2               |
| Sync lag (blocks behind)        | > 100             | > 1000            |
| Block processing time           | > 1s              | > 5s              |
| Memory usage                    | > 80%             | > 95%             |

### Prometheus Queries

```promql
# Kong request rate
rate(kong_http_requests_total[5m])

# Kong error rate
rate(kong_http_requests_total{code=~"5.."}[5m]) / rate(kong_http_requests_total[5m])

# Kong p95 latency
histogram_quantile(0.95, rate(kong_latency_bucket[5m]))

# Upstream health status
kong_upstream_target_health
```

### Setting Up Alerts

Create `/ops/barad-dur/prometheus/alert_rules.yml`:

```yaml
groups:
  - name: barad-dur-alerts
    rules:
      - alert: KongHighErrorRate
        expr: rate(kong_http_requests_total{code=~"5.."}[5m]) > 0.05
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Kong high error rate detected"

      - alert: FukuiiLowPeerCount
        expr: fukuii_peer_count < 5
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "Fukuii peer count is low"

      - alert: FukuiiNotSyncing
        expr: increase(fukuii_best_block_number[30m]) == 0
        for: 30m
        labels:
          severity: critical
        annotations:
          summary: "Fukuii node stopped syncing"
```

---

## Incident Response

### Service Down Procedures

#### Kong Not Responding

1. **Check container status:**
   ```bash
   docker-compose ps kong
   docker-compose logs --tail=50 kong
   ```

2. **Check PostgreSQL connectivity:**
   ```bash
   docker-compose exec kong kong health
   docker-compose logs --tail=50 postgres
   ```

3. **Restart Kong:**
   ```bash
   docker-compose restart kong
   ```

4. **If migrations failed:**
   ```bash
   docker-compose run --rm kong-migrations
   docker-compose up -d kong
   ```

#### Fukuii Node Not Syncing

1. **Check sync status:**
   ```bash
   docker-compose logs --tail=100 fukuii-primary
   ```

2. **Verify peer connectivity:**
   ```bash
   docker exec fukuii-primary netstat -an | grep 30303
   ```

3. **Check disk space:**
   ```bash
   df -h $(docker volume inspect barad-dur_fukuii-data | jq -r '.[0].Mountpoint')
   ```

4. **Restart with fresh peer connections:**
   ```bash
   docker-compose restart fukuii-primary
   ```

#### High Latency

1. **Check resource usage:**
   ```bash
   docker stats
   ```

2. **Review Kong plugins:**
   ```bash
   curl http://localhost:8001/plugins | jq '.data[] | {name, enabled}'
   ```

3. **Check upstream health:**
   ```bash
   curl http://localhost:8001/upstreams/fukuii-cluster/health | jq .
   ```

4. **Consider scaling if load is high:**
   ```bash
   docker-compose up -d --scale fukuii-primary=2
   ```

### Escalation Matrix

| Severity | Response Time | Escalation |
|----------|---------------|------------|
| Critical | 15 minutes    | On-call + Team Lead |
| High     | 1 hour        | On-call engineer |
| Medium   | 4 hours       | Next available |
| Low      | 24 hours      | Standard queue |

---

## Backup and Recovery

### What to Back Up

1. **PostgreSQL (Kong configuration)**
2. **Fukuii blockchain data**
3. **Configuration files** (`kong.yml`, `.env`)
4. **Grafana dashboards**

### Backup Procedures

#### PostgreSQL Backup

```bash
# Create SQL dump
docker exec fukuii-postgres pg_dump -U kong kong > backup/kong-$(date +%Y%m%d).sql

# Compressed backup
docker exec fukuii-postgres pg_dump -U kong kong | gzip > backup/kong-$(date +%Y%m%d).sql.gz
```

#### Fukuii Data Backup

```bash
# Stop the node for consistent backup
docker-compose stop fukuii-primary

# Create tarball
tar -czf backup/fukuii-data-$(date +%Y%m%d).tar.gz data/fukuii/

# Restart
docker-compose start fukuii-primary
```

#### Configuration Backup

```bash
# Backup all configs
tar -czf backup/config-$(date +%Y%m%d).tar.gz \
  kong.yml \
  .env \
  fukuii-conf/ \
  prometheus/ \
  grafana/
```

### Recovery Procedures

#### Restore PostgreSQL

```bash
# Stop Kong
docker-compose stop kong

# Restore database
cat backup/kong-YYYYMMDD.sql | docker exec -i fukuii-postgres psql -U kong kong

# Start Kong
docker-compose start kong
```

#### Restore Fukuii Data

```bash
# Stop node
docker-compose stop fukuii-primary

# Clear existing data
rm -rf data/fukuii/*

# Restore from backup
tar -xzf backup/fukuii-data-YYYYMMDD.tar.gz -C data/

# Start node
docker-compose start fukuii-primary
```

### Backup Schedule

| Data              | Frequency  | Retention |
|-------------------|------------|-----------|
| PostgreSQL        | Daily      | 30 days   |
| Fukuii blockchain | Weekly     | 4 weeks   |
| Configuration     | On change  | 90 days   |

---

## Scaling Operations

### Horizontal Scaling (Add Instances)

1. **Add Fukuii instance to `docker-compose.yml`:**

   ```yaml
   fukuii-tertiary:
     image: chipprbots/fukuii:latest
     container_name: fukuii-tertiary
     restart: unless-stopped
     ports:
       - "8549:8545"
       - "8550:8546"
       - "30305:30303"
       - "9097:9095"
     volumes:
       - ${FUKUII_TERTIARY_DATA_DIR:-./data/fukuii-tertiary}:/app/data
       - ./fukuii-conf:/app/conf:ro
     environment:
       - JAVA_OPTS=${JAVA_OPTS:--Xmx4g -Xms4g}
     networks:
       - fukuii-network
     healthcheck:
       test: ["CMD", "curl", "-f", "http://localhost:8546/health"]
       interval: 30s
       timeout: 10s
       retries: 3
       start_period: 60s
   ```

2. **Add target to Kong upstream in `kong.yml`:**

   ```yaml
   upstreams:
     - name: fukuii-cluster
       targets:
         - target: fukuii-primary:8546
           weight: 100
         - target: fukuii-secondary:8546
           weight: 100
         - target: fukuii-tertiary:8546
           weight: 100
   ```

3. **Apply changes:**

   ```bash
   docker-compose up -d
   docker-compose restart kong
   ```

### Vertical Scaling (Increase Resources)

Modify resource limits in `docker-compose.yml`:

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
  environment:
    - JAVA_OPTS=-Xmx12g -Xms12g
```

### Traffic Management During Scaling

```bash
# Drain a node before removing
curl -X PATCH http://localhost:8001/upstreams/fukuii-cluster/targets/fukuii-primary:8546 \
  -d "weight=0"

# Wait for connections to drain
sleep 60

# Remove or update the node
docker-compose stop fukuii-primary
```

---

## Maintenance Procedures

### Updating Kong Configuration

1. **Edit `kong.yml`**

2. **Validate configuration:**
   ```bash
   docker-compose exec kong kong config parse /etc/kong/kong.yml
   ```

3. **Reload Kong:**
   ```bash
   docker-compose restart kong
   ```

### Updating Fukuii

1. **Pull new image:**
   ```bash
   docker pull chipprbots/fukuii:latest
   ```

2. **Rolling update (one at a time):**
   ```bash
   # Update secondary first
   docker-compose up -d fukuii-secondary
   
   # Wait 5-10 minutes and verify sync status
   curl -X POST http://localhost:8548/ \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":1}'
   # Expected: "result": false (synced) or sync progress details
   
   # Update primary after secondary is synced
   docker-compose up -d fukuii-primary
   ```

### Certificate Renewal

1. **Generate new certificates:**
   ```bash
   certbot renew
   ```

2. **Update Kong volumes:**
   ```bash
   cp /etc/letsencrypt/live/your-domain/* ssl/
   docker-compose restart kong
   ```

### Database Maintenance

```bash
# Vacuum PostgreSQL
docker exec fukuii-postgres vacuumdb -U kong -a -z

# Check database size
docker exec fukuii-postgres psql -U kong -c "SELECT pg_size_pretty(pg_database_size('kong'));"
```

---

## Troubleshooting Guide

### Common Issues

#### Issue: 502 Bad Gateway

**Cause:** Upstream (Fukuii) is not responding.

**Solution:**
1. Check Fukuii health: `curl http://localhost:8546/health`
2. Check upstream status: `curl http://localhost:8001/upstreams/fukuii-cluster/health`
3. Restart Fukuii: `docker-compose restart fukuii-primary fukuii-secondary`

#### Issue: 429 Too Many Requests

**Cause:** Rate limit exceeded.

**Solution:**
1. Check rate limit config in `kong.yml`
2. Increase limits if legitimate traffic
3. Consider adding more consumer tiers

#### Issue: 401 Unauthorized

**Cause:** Invalid or missing credentials.

**Solution:**
1. Verify credentials in request
2. Check consumer config: `curl http://localhost:8001/consumers`
3. Verify credentials in `kong.yml`

#### Issue: High Memory Usage

**Cause:** JVM heap or RocksDB cache.

**Solution:**
1. Check current usage: `docker stats`
2. Adjust JAVA_OPTS in `.env`
3. Consider vertical scaling

#### Issue: Disk Full

**Cause:** Blockchain data growth.

**Solution:**
1. Check disk usage: `df -h`
2. Clean old logs: `docker-compose logs --tail=0`
3. Consider pruning or archive node settings
4. Expand storage

### Diagnostic Commands

```bash
# Container resource usage
docker stats

# Check all logs
docker-compose logs --tail=100

# Kong configuration validation
docker-compose exec kong kong config parse /etc/kong/kong.yml

# PostgreSQL connection check
docker-compose exec postgres pg_isready

# Network connectivity
docker-compose exec kong ping fukuii-primary

# DNS resolution
docker-compose exec kong nslookup fukuii-primary
```

### Log Locations

| Component   | Log Command                            |
|-------------|----------------------------------------|
| Kong        | `docker-compose logs kong`             |
| PostgreSQL  | `docker-compose logs postgres`         |
| Fukuii      | `docker-compose logs fukuii-primary`   |
| Prometheus  | `docker-compose logs prometheus`       |
| Grafana     | `docker-compose logs grafana`          |

---

## Related Documentation

- [Barad-dûr README](https://github.com/chippr-robotics/fukuii/tree/develop/ops/barad-dur) - Stack overview and quick start
- [Kong Guide](../deployment/kong.md) - Comprehensive Kong documentation
- [Kong Architecture](../deployment/kong-architecture.md) - Architecture details
- [Kong Security](../deployment/kong-security.md) - Security best practices
- [Metrics & Monitoring](../operations/metrics-and-monitoring.md) - Monitoring setup
- [Backup & Restore](backup-restore.md) - General backup procedures
- [Log Triage](log-triage.md) - Log analysis guide

---

**Last Updated:** 2025-11-30
