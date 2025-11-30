# Barad-dûr (Kong API Gateway) - Quick Start Guide

This guide will get you up and running with Barad-dûr (Kong API Gateway) for Fukuii in under 5 minutes.

## ⚠️ SECURITY WARNING

**DO NOT use this setup in production without changing the default passwords and secrets!**

The default configuration includes example credentials for demonstration purposes only:
- Default passwords: `fukuii_admin_password`, `fukuii_dev_password`
- Default API keys: `admin_api_key_change_me`, `dev_api_key_change_me`
- Default JWT secret: `your_jwt_secret_change_me`
- Default Grafana password: `fukuii_grafana_admin`
- Default PostgreSQL password: `kong`

**Before production deployment:**
1. Copy `.env.example` to `.env`
2. Generate strong random passwords and secrets
3. Update all credentials in `.env` and `kong.yml`
4. Review the [Kong Security Guide](kong-security.md)

## Prerequisites

- Docker 20.10+
- Docker Compose 2.0+
- 8GB RAM available
- 20GB free disk space

## Step 1: Clone and Navigate

```bash
cd docker/barad-dur
```

## Step 2: Run Setup Script

```bash
./setup.sh
```

The setup script will:
- Check prerequisites
- Create necessary directories (including data directories for container bindings)
- Copy configuration templates
- Optionally start the stack

## Step 3: Start Services (if not started by setup.sh)

```bash
docker-compose up -d
```

Wait for all services to start (2-3 minutes):

```bash
docker-compose ps
```

## Step 4: Verify Services

Check that Kong is running:

```bash
curl -i http://localhost:8001/status
```

Expected response:
```json
{
  "database": {
    "reachable": true
  },
  "server": {
    "connections_accepted": 1,
    "connections_active": 1,
    "connections_handled": 1,
    "connections_reading": 0,
    "connections_waiting": 0,
    "connections_writing": 1,
    "total_requests": 1
  }
}
```

## Step 5: Test JSON-RPC API

Make a test JSON-RPC call:

```bash
curl -X POST http://localhost:8000/ \
  -u admin:fukuii_admin_password \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "method": "eth_blockNumber",
    "params": [],
    "id": 1
  }'
```

Or run the test script:

```bash
./test-api.sh
```

## Step 6: Access Dashboards

### Grafana (Monitoring)
- URL: http://localhost:3000
- Username: `admin`
- Password: `fukuii_grafana_admin`

### Prometheus (Metrics)
- URL: http://localhost:9090

## Step 7: Configure Security (IMPORTANT!)

Before using in production, update default credentials:

1. Edit `.env`:
```bash
nano .env
```

Update these values:
- `BASIC_AUTH_ADMIN_PASSWORD`
- `BASIC_AUTH_DEV_PASSWORD`
- `API_KEY_ADMIN`
- `API_KEY_DEV`
- `JWT_SECRET`

2. Edit `kong.yml`:
```bash
nano kong.yml
```

Update consumer credentials in the `consumers` section.

3. Restart Kong:
```bash
docker-compose restart kong
```

## Common Tasks

### View Logs

```bash
# All services
docker-compose logs -f

# Specific service
docker-compose logs -f kong
docker-compose logs -f fukuii-primary
```

### Stop Services

```bash
docker-compose down
```

### Stop and Remove All Data

```bash
docker-compose down -v
```

### Restart a Service

```bash
docker-compose restart kong
```

### Update Images

```bash
docker-compose pull
docker-compose up -d
```

## API Examples

### Basic Health Check

```bash
curl http://localhost:8000/health
```

### JSON-RPC - Get Block Number

```bash
curl -X POST http://localhost:8000/ \
  -u admin:fukuii_admin_password \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "method": "eth_blockNumber",
    "params": [],
    "id": 1
  }'
```

### JSON-RPC - Get Peer Count

```bash
curl -X POST http://localhost:8000/ \
  -u admin:fukuii_admin_password \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "method": "net_peerCount",
    "params": [],
    "id": 1
  }'
```

### Bitcoin Route Example

```bash
curl -X POST http://localhost:8000/bitcoin \
  -u admin:fukuii_admin_password \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "method": "getblockcount",
    "params": [],
    "id": 1
  }'
```

### Ethereum Route Example

```bash
curl -X POST http://localhost:8000/ethereum \
  -u admin:fukuii_admin_password \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "method": "eth_blockNumber",
    "params": [],
    "id": 1
  }'
```

## Troubleshooting

### Kong Won't Start

1. Check if PostgreSQL is healthy:
```bash
docker-compose ps postgres
docker-compose logs postgres
```

2. Wait for PostgreSQL to be fully ready (can take 30 seconds)

3. Run migrations manually:
```bash
docker-compose run --rm kong-migrations
```

### Cannot Connect to API

1. Verify Kong is running:
```bash
docker-compose ps kong
```

2. Check Kong logs:
```bash
docker-compose logs kong
```

3. Verify port is not in use:
```bash
netstat -an | grep 8000
```

### Fukuii Not Syncing

1. Check Fukuii logs:
```bash
docker-compose logs fukuii-primary
```

2. Verify P2P port is accessible:
```bash
docker exec fukuii-primary netstat -an | grep 30303
```

3. Check peer count:
```bash
curl -X POST http://localhost:8000/ \
  -u admin:fukuii_admin_password \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "method": "net_peerCount",
    "params": [],
    "id": 1
  }'
```

## Next Steps

1. Read the [Deployment Guide](README.md) for detailed documentation
2. Review [Kong Security Guide](kong-security.md) for production deployment
3. Configure additional Fukuii instances for high availability
4. Set up SSL/TLS certificates
5. Configure monitoring alerts
6. Set up automated backups

## Need Help?

- Check logs: `docker-compose logs -f`
- Run tests: `./test-api.sh`
- Review documentation: [Deployment Guide](README.md)
- Security guide: [Kong Security](kong-security.md)
- Main Fukuii docs: [Documentation Home](../index.md)

## Clean Up

To completely remove all services and data:

```bash
# Stop and remove containers, networks
docker-compose down

# Remove data directories (optional)
rm -rf data/

# Remove images (optional)
docker-compose down --rmi all

# Remove Barad-dûr directory (optional)
cd ../..
rm -rf docker/barad-dur
```
