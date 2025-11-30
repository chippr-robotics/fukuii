# Barad-dûr: Kong API Gateway Stack for Fukuii

> *Named after Sauron's Dark Tower — the fortified gateway to Fukuii*

Barad-dûr is a production-ready Docker Compose stack that provides a Kong API Gateway in front of Fukuii Ethereum Classic nodes, with built-in monitoring, high availability, and security features.

## What is Barad-dûr?

Barad-dûr is an "API Ops Starter" — a complete operational stack designed to help you run Fukuii nodes in production with:

- **Kong API Gateway**: Centralized routing, authentication, rate limiting, and observability
- **High Availability**: Multiple Fukuii instances with automatic load balancing and failover
- **PostgreSQL**: Persistent storage for Kong configuration
- **Prometheus**: Metrics collection and alerting
- **Grafana**: Visualization dashboards

## Architecture Overview

```
                          ┌─────────────────────────────────────┐
                          │          Client Requests            │
                          └─────────────────────────────────────┘
                                           │
                                           ▼
                          ┌─────────────────────────────────────┐
                          │        Kong API Gateway              │
                          │  • Authentication (Basic/JWT/Key)   │
                          │  • Rate Limiting                     │
                          │  • Load Balancing                    │
                          │  • CORS / Request Validation         │
                          └─────────────────────────────────────┘
                                           │
                     ┌─────────────────────┼─────────────────────┐
                     ▼                     ▼                     ▼
              ┌──────────────┐     ┌──────────────┐     ┌──────────────┐
              │   Fukuii     │     │   Fukuii     │     │  Additional  │
              │   Primary    │     │  Secondary   │     │   Instances  │
              └──────────────┘     └──────────────┘     └──────────────┘
                     │                     │                     │
                     └──────────┬──────────┴─────────────────────┘
                                ▼
                    ┌─────────────────────────┐
                    │       Prometheus        │
                    │   (Metrics Collection)  │
                    └─────────────────────────┘
                                │
                                ▼
                    ┌─────────────────────────┐
                    │        Grafana          │
                    │     (Dashboards)        │
                    └─────────────────────────┘
```

## Quick Start

### Prerequisites

- Docker 20.10+
- Docker Compose 2.0+
- 8GB RAM available
- 20GB free disk space

### 1. Run the Setup Script

```bash
cd docker/barad-dur
./setup.sh
```

This script will:
- Check prerequisites
- Create necessary directories
- Generate a `.env` file from the template
- Optionally start the stack

### 2. Manual Start (Alternative)

```bash
# Copy environment template
cp .env.example .env

# Start all services
docker-compose up -d

# Check status
docker-compose ps
```

### 3. Verify Services

```bash
# Check Kong status
curl http://localhost:8001/status

# Test JSON-RPC through Kong
curl -X POST http://localhost:8000/ \
  -u admin:fukuii_admin_password \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'

# Run the test suite
./test-api.sh
```

### 4. Access Dashboards

| Service     | URL                     | Default Credentials           |
|-------------|-------------------------|-------------------------------|
| Kong Proxy  | http://localhost:8000   | Basic Auth (see kong.yml)     |
| Kong Admin  | http://localhost:8001   | No auth (internal only)       |
| Prometheus  | http://localhost:9090   | No auth required              |
| Grafana     | http://localhost:3000   | admin / fukuii_grafana_admin  |

## ⚠️ Security Warning

**This setup includes EXAMPLE credentials for demonstration purposes.**

Before deploying to production, you **MUST**:

1. Update all passwords in `.env`:
   - `POSTGRES_PASSWORD`
   - `GRAFANA_ADMIN_PASSWORD`
   - `BASIC_AUTH_ADMIN_PASSWORD`
   - `API_KEY_ADMIN`
   - `JWT_SECRET`

2. Update consumer credentials in `kong.yml`:
   - Basic Auth passwords
   - API keys
   - JWT secrets

3. Review the [Security Guide](../../docs/deployment/kong-security.md)

## Files in This Directory

| File                        | Description                                          |
|-----------------------------|------------------------------------------------------|
| `docker-compose.yml`        | Full stack with PostgreSQL, Kong, Fukuii, monitoring |
| `docker-compose-dbless.yml` | Simpler DB-less Kong setup for development           |
| `kong.yml`                  | Kong declarative configuration (routes, auth, plugins)|
| `setup.sh`                  | Interactive setup script                             |
| `test-api.sh`               | API test suite script                                |
| `.env.example`              | Environment variable template                        |
| `fukuii-conf/`              | Fukuii node configuration files                      |
| `prometheus/`               | Prometheus configuration                             |
| `grafana/`                  | Grafana dashboards and provisioning                  |

## Deployment Options

### Option 1: Full Stack (Production)

Use `docker-compose.yml` for production deployments:

```bash
docker-compose up -d
```

Includes:
- PostgreSQL for Kong configuration persistence
- Automatic database migrations
- Two Fukuii instances for HA
- Full monitoring stack

### Option 2: DB-less Mode (Development)

Use `docker-compose-dbless.yml` for simpler setups:

```bash
docker-compose -f docker-compose-dbless.yml up -d
```

Features:
- No PostgreSQL required
- Configuration from `kong.yml` only
- Faster startup
- Suitable for development and testing

## Common Operations

### View Logs

```bash
# All services
docker-compose logs -f

# Specific service
docker-compose logs -f kong
docker-compose logs -f fukuii-primary
```

### Restart Services

```bash
docker-compose restart kong
docker-compose restart fukuii-primary fukuii-secondary
```

### Stop Stack

```bash
docker-compose down          # Stop containers
docker-compose down -v       # Stop and remove volumes
```

### Update Images

```bash
docker-compose pull
docker-compose up -d
```

### Scale Fukuii Instances

Add more instances to `docker-compose.yml` and update Kong upstream targets in `kong.yml`.

## API Endpoints

### Health Endpoints (No Auth Required)

```bash
curl http://localhost:8000/health      # Liveness check
curl http://localhost:8000/readiness   # Readiness check
```

### JSON-RPC Endpoints (Auth Required)

```bash
# Main endpoint
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

### Multi-Network Routes

| Route                       | Network           |
|-----------------------------|-------------------|
| `/bitcoin` or `/btc`        | Bitcoin           |
| `/ethereum` or `/eth`       | Ethereum          |
| `/etc` or `/ethereum-classic`| Ethereum Classic |

## Troubleshooting

### Kong Won't Start

1. Check PostgreSQL health: `docker-compose logs postgres`
2. Wait for migrations: `docker-compose logs kong-migrations`
3. Run migrations manually: `docker-compose run --rm kong-migrations`

### Fukuii Not Syncing

1. Check logs: `docker-compose logs fukuii-primary`
2. Verify P2P port (30303) is accessible
3. Check peer count via API

### Authentication Issues

1. Verify credentials in `kong.yml`
2. Check Kong consumer config: `curl http://localhost:8001/consumers`
3. Test with known-good credentials

## Related Documentation

- **[Kong Guide](../../docs/deployment/kong.md)** - Comprehensive Kong documentation
- **[Kong Architecture](../../docs/deployment/kong-architecture.md)** - Architecture details
- **[Kong Quick Start](../../docs/deployment/kong-quickstart.md)** - Quick start guide
- **[Kong Security](../../docs/deployment/kong-security.md)** - Security best practices
- **[Operations Runbook](../../docs/runbooks/barad-dur-operations.md)** - Operational procedures

## Support

For questions or issues:
- Review the [Operations Runbook](../../docs/runbooks/barad-dur-operations.md)
- Check the [Troubleshooting Guide](../../docs/runbooks/known-issues.md)
- Open an issue on [GitHub](https://github.com/chippr-robotics/fukuii/issues)

## License

This configuration is part of the Fukuii project and is distributed under the Apache 2.0 License.
