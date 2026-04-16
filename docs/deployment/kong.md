# Barad-dûr (Kong API Gateway) for Fukuii

This directory contains a complete Kong API Gateway setup (named Barad-dûr, Sauron's Dark Tower) for managing Fukuii Ethereum Classic nodes with high availability, security, and monitoring capabilities.

## ⚠️ CRITICAL SECURITY NOTICE

**This setup includes EXAMPLE CREDENTIALS for demonstration purposes. These MUST be changed before any production deployment!**

Default credentials that MUST be changed:
- Basic Auth passwords
- API keys  
- JWT secrets
- Grafana admin password
- PostgreSQL password

See [Kong Security Guide](kong-security.md) for detailed instructions on securing your deployment.

## Overview

The Barad-dûr (Kong) setup provides:

- **API Gateway**: Kong Gateway for routing and managing all traffic to Fukuii nodes
- **High Availability**: Load balancing across multiple Fukuii instances with health checks
- **Database**: PostgreSQL for Kong's configuration and state (replaces deprecated Cassandra support)
- **Security**: Basic Auth, JWT, rate limiting, and CORS support
- **Monitoring**: Prometheus metrics collection and Grafana dashboards
- **Multi-Network Support**: HD wallet hierarchy routing for Bitcoin, Ethereum, and Ethereum Classic
- **Data Directory Bindings**: Configurable host directories for persistent data

## Architecture

```
┌─────────────┐
│   Clients   │
└──────┬──────┘
       │
       ▼
┌─────────────────────────────────────────────┐
│            Kong API Gateway                  │
│  - Authentication (Basic Auth / JWT)        │
│  - Rate Limiting                             │
│  - Load Balancing                            │
│  - CORS Support                              │
│  - Metrics Export                            │
└──────┬──────────────────────────────────────┘
       │
       ├──────────────┬──────────────┐
       ▼              ▼              ▼
┌──────────┐   ┌──────────┐   ┌──────────┐
│ Fukuii   │   │ Fukuii   │   │ Fukuii   │
│ Primary  │   │Secondary │   │  ...     │
└──────────┘   └──────────┘   └──────────┘
       │              │              │
       └──────────────┴──────────────┘
                      │
       ┌──────────────┴──────────────┐
       ▼                             ▼
┌──────────────┐              ┌─────────────┐
│  Prometheus  │              │   Grafana   │
│   Metrics    │◄─────────────┤  Dashboard  │
└──────────────┘              └─────────────┘
```

## Services

### Kong Gateway
- **Ports**: 
  - `8000` - HTTP Proxy
  - `8443` - HTTPS Proxy
  - `8001` - Admin API
  - `8444` - Admin API HTTPS
- **Features**: Load balancing, authentication, rate limiting, monitoring

### PostgreSQL Database
- **Port**: `5432` (internal)
- **Purpose**: Kong's configuration and state storage
- **Note**: Replaces Cassandra which is no longer supported in Kong 3.x+

### Fukuii Nodes
- **Primary Instance**:
  - JSON-RPC HTTP: `8545`
  - JSON-RPC WebSocket: `8546`
  - P2P: `30303`
  - Metrics: `9095`
  
- **Secondary Instance**:
  - JSON-RPC HTTP: `8547`
  - JSON-RPC WebSocket: `8548`
  - P2P: `30304`
  - Metrics: `9096`

### Prometheus
- **Port**: `9090`
- **Purpose**: Metrics collection and storage
- **Retention**: 30 days (configurable)

### Grafana
- **Port**: `3000`
- **Default Credentials**: 
  - Username: `admin`
  - Password: `fukuii_grafana_admin`

## Quick Start

### Prerequisites
- Docker 20.10+
- Docker Compose 2.0+
- At least 8GB RAM available
- 20GB free disk space

### Start the Stack

```bash
# Navigate to the Barad-dûr directory
cd ops/barad-dur

# Start all services
docker-compose up -d

# Check service status
docker-compose ps

# View logs
docker-compose logs -f
```

### Verify Services

```bash
# Check Kong is running
curl -i http://localhost:8001/status

# Check Fukuii via Kong
curl -X POST http://localhost:8000/ \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'

# Check health endpoint
curl http://localhost:8000/health

# Access Grafana
open http://localhost:3000
```

## API Endpoints

### JSON-RPC Endpoints

All endpoints require authentication (see Security section below).

#### Main JSON-RPC Endpoint
```bash
# Standard Ethereum JSON-RPC calls
curl -X POST http://localhost:8000/ \
  -u admin:fukuii_admin_password \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'

# Alternative path
curl -X POST http://localhost:8000/rpc \
  -u admin:fukuii_admin_password \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'
```

#### HD Wallet Multi-Network Support

##### Bitcoin
```bash
curl -X POST http://localhost:8000/bitcoin \
  -u admin:fukuii_admin_password \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"getblockcount","params":[],"id":1}'

# Alternative: /btc
curl -X POST http://localhost:8000/btc \
  -u admin:fukuii_admin_password \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"getblockcount","params":[],"id":1}'
```

##### Ethereum
```bash
curl -X POST http://localhost:8000/ethereum \
  -u admin:fukuii_admin_password \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'

# Alternative: /eth
curl -X POST http://localhost:8000/eth \
  -u admin:fukuii_admin_password \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'
```

##### Ethereum Classic
```bash
curl -X POST http://localhost:8000/etc \
  -u admin:fukuii_admin_password \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'

# Alternative: /ethereum-classic
curl -X POST http://localhost:8000/ethereum-classic \
  -u admin:fukuii_admin_password \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'
```

### Health & Readiness Endpoints

```bash
# Health check (no auth required)
curl http://localhost:8000/health

# Readiness check (no auth required)
curl http://localhost:8000/readiness
```

## Security

### Authentication Methods

#### 1. Basic Authentication (Default)

Basic Auth is enabled by default with two pre-configured users:

**Admin User:**
- Username: `admin`
- Password: `fukuii_admin_password`

**Developer User:**
- Username: `developer`
- Password: `fukuii_dev_password`

Usage:
```bash
curl -X POST http://localhost:8000/ \
  -u admin:fukuii_admin_password \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'
```

#### 2. API Key Authentication

Each consumer has an API key for programmatic access:

**Admin API Key:** `admin_api_key_change_me`
**Developer API Key:** `dev_api_key_change_me`

Usage:
```bash
curl -X POST http://localhost:8000/ \
  -H "apikey: admin_api_key_change_me" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'
```

#### 3. JWT Authentication (Optional)

JWT authentication is configured but not enforced by default. To use JWT:

1. Generate a JWT token with the configured secret
2. Include the token in the Authorization header

```bash
# Example JWT token generation (using a JWT library)
# Token should be signed with: your_jwt_secret_change_me

curl -X POST http://localhost:8000/ \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'
```

### Changing Default Credentials

⚠️ **IMPORTANT**: Change default passwords and API keys before deploying to production!

Edit `kong.yml` and update:
- `basicauth_credentials` passwords
- `keyauth_credentials` keys
- `jwt_secrets` secrets

Then restart Kong:
```bash
docker-compose restart kong
```

### Rate Limiting

Rate limits are configured per service:
- 100 requests per minute
- 5000 requests per hour

To adjust, edit the `rate-limiting` plugin configuration in `kong.yml`.

### CORS Configuration

CORS is enabled for all origins by default. For production:

1. Edit `kong.yml`
2. Update the `cors` plugin configuration
3. Set specific `origins` instead of `"*"`

```yaml
plugins:
  - name: cors
    config:
      origins:
        - "https://your-frontend-domain.com"
```

### IP Restriction (Optional)

Uncomment the `ip-restriction` plugin in `kong.yml` to whitelist specific IP ranges:

```yaml
plugins:
  - name: ip-restriction
    config:
      allow:
        - 10.0.0.0/8
        - 172.16.0.0/12
        - 192.168.0.0/16
```

## High Availability & Disaster Recovery (HADR)

### Load Balancing

Kong automatically load balances requests across all healthy Fukuii instances using:

- **Algorithm**: Round-robin
- **Health Checks**: Active and passive
- **Failover**: Automatic removal of unhealthy instances

### Active Health Checks

- **Interval**: Every 10 seconds
- **Endpoint**: `/health`
- **Success Threshold**: 2 consecutive successes
- **Failure Threshold**: 3 consecutive failures

### Passive Health Checks

- **HTTP Failures**: 5 failures mark instance as unhealthy
- **Timeouts**: 2 timeouts mark instance as unhealthy

### Adding More Fukuii Instances

To add additional Fukuii instances for higher availability:

1. Add the service to `docker-compose.yml`:

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
  networks:
    - fukuii-network
```

2. Add the target to `kong.yml`:

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

3. Restart the stack:

```bash
docker-compose up -d
```

## Monitoring

### Prometheus Metrics

Prometheus collects metrics from:
- Kong API Gateway
- Fukuii primary instance
- Fukuii secondary instance
- Grafana

Access Prometheus at: http://localhost:9090

#### Key Metrics

**Kong Metrics:**
- `kong_http_requests_total` - Total HTTP requests
- `kong_latency` - Request latency
- `kong_bandwidth_bytes` - Bandwidth usage
- `kong_upstream_status` - Upstream service status

**Fukuii Metrics:**
- Node sync status
- Block height
- Peer count
- Transaction pool size

### Grafana Dashboards

Access Grafana at: http://localhost:3000

**Default Login:**
- Username: `admin`
- Password: `fukuii_grafana_admin`

**Pre-configured Dashboards:**
- Kong API Gateway metrics
- Fukuii node metrics
- System metrics

### Log Management

All services log to stdout/stderr. To view logs:

```bash
# All services
docker-compose logs -f

# Specific service
docker-compose logs -f kong
docker-compose logs -f fukuii-primary

# With tail
docker-compose logs -f --tail=100 kong
```

To export logs for external analysis:

```bash
# Export Kong logs
docker-compose logs --no-color kong > kong-logs.txt

# Export all logs
docker-compose logs --no-color > all-logs.txt
```

## Configuration

### Kong Configuration (`kong.yml`)

The `kong.yml` file uses Kong's declarative configuration format. Key sections:

- **Services**: Define upstream APIs (Fukuii instances)
- **Routes**: Map request paths to services
- **Upstreams**: Configure load balancing
- **Consumers**: Define users and authentication
- **Plugins**: Configure features like auth, rate limiting, CORS

To reload configuration after changes:

```bash
docker-compose restart kong
```

### Fukuii Configuration

Place custom Fukuii configuration files in `fukuii-conf/`:

```bash
mkdir -p fukuii-conf
cp /path/to/your/app.conf fukuii-conf/
```

Then restart the Fukuii services:

```bash
docker-compose restart fukuii-primary fukuii-secondary
```

### Prometheus Configuration

Edit `prometheus/prometheus.yml` to:
- Add new scrape targets
- Configure alerting rules
- Set up remote storage

After changes:

```bash
# Reload Prometheus configuration (no restart needed)
curl -X POST http://localhost:9090/-/reload
```

## Backup and Restore

### Backup PostgreSQL Data

```bash
# Create backup
docker exec fukuii-postgres pg_dump -U kong kong > kong-backup.sql

# Or use compressed backup
docker exec fukuii-postgres pg_dump -U kong kong | gzip > kong-backup.sql.gz
```

### Backup Fukuii Data

```bash
# Backup primary instance data
docker run --rm \
  -v fukuii-data:/source \
  -v $(pwd):/backup \
  alpine tar czf /backup/fukuii-data-backup.tar.gz -C /source .
```

### Restore from Backup

```bash
# Stop services
docker-compose down

# Restore data
docker run --rm \
  -v fukuii-data:/target \
  -v $(pwd):/backup \
  alpine sh -c "cd /target && tar xzf /backup/fukuii-data-backup.tar.gz"

# Start services
docker-compose up -d
```

## Troubleshooting

### Kong Not Starting

1. Check PostgreSQL is healthy:
```bash
docker-compose ps postgres
docker-compose logs postgres
```

2. Run migrations manually:
```bash
docker-compose run --rm kong-migrations
```

### Fukuii Nodes Not Syncing

1. Check node logs:
```bash
docker-compose logs fukuii-primary
```

2. Verify network connectivity:
```bash
docker exec fukuii-primary netstat -an | grep 30303
```

3. Check peer count via JSON-RPC:
```bash
curl -X POST http://localhost:8000/ \
  -u admin:fukuii_admin_password \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}'
```

### Load Balancing Not Working

1. Check upstream health:
```bash
curl http://localhost:8001/upstreams/fukuii-cluster/health
```

2. Check Kong logs:
```bash
docker-compose logs kong
```

3. Verify Fukuii health endpoints:
```bash
curl http://localhost:8546/health
curl http://localhost:8548/health
```

### High Memory Usage

Adjust JVM memory settings for Fukuii:

```yaml
environment:
  - JAVA_OPTS=-Xmx8g -Xms8g  # Increase from 4g to 8g
```

### Authentication Issues

1. Verify credentials in `kong.yml`
2. Check Kong consumer configuration:
```bash
curl http://localhost:8001/consumers
```

3. Test without auth to isolate issue:
```bash
# Temporarily disable auth plugin in kong.yml for debugging
```

## Production Deployment Checklist

Before deploying to production:

- [ ] Change all default passwords and API keys
- [ ] Configure SSL/TLS certificates for HTTPS
- [ ] Set up proper CORS origins (not `"*"`)
- [ ] Enable IP restriction if needed
- [ ] Configure PostgreSQL backup strategy
- [ ] Set up monitoring alerts in Prometheus/Alertmanager
- [ ] Configure log aggregation and retention
- [ ] Set up automated backups
- [ ] Review and adjust rate limits
- [ ] Configure firewall rules
- [ ] Set up reverse proxy (e.g., nginx) if needed
- [ ] Enable additional Kong plugins as needed
- [ ] Document disaster recovery procedures
- [ ] Test failover scenarios
- [ ] Configure resource limits in Docker Compose
- [ ] Set up health check monitoring
- [ ] Review security headers and CSP
- [ ] Enable audit logging

## Advanced Configuration

### Using Kong in DB-less Mode

For simpler deployments, Kong can run without PostgreSQL using declarative configuration only:

1. Use `docker-compose-dbless.yml` instead of the main compose file
2. This mode uses only the declarative configuration from `kong.yml`

### Custom Plugins

To add custom Kong plugins:

1. Create plugin directory:
```bash
mkdir -p kong-plugins/my-plugin
```

2. Mount plugin directory:
```yaml
volumes:
  - ./kong-plugins:/usr/local/share/lua/5.1/kong/plugins
```

3. Enable plugin:
```yaml
environment:
  - KONG_PLUGINS=bundled,my-plugin
```

### Multi-Region Deployment

For multi-region HADR:

1. Deploy Barad-dûr (Kong) + Fukuii stack in each region
2. Use PostgreSQL replication or external database service
3. Configure DNS-based routing or global load balancer
4. Set up cross-region monitoring

## Support and Resources

- **Fukuii Documentation**: [Documentation Home](../index.md)
- **Kong Documentation**: https://docs.konghq.com/
- **Kong Plugins**: https://docs.konghq.com/hub/
- **Prometheus**: https://prometheus.io/docs/
- **Grafana**: https://grafana.com/docs/

## License

This Kong configuration is part of the Fukuii project and is distributed under the Apache 2.0 License.
