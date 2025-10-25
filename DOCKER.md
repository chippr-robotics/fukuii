# Fukuii Docker Setup

This directory contains the Docker configuration for running Fukuii as a containerized Ethereum Classic client.

## Features

- **Multi-stage build**: Optimized build process that separates build dependencies from runtime
- **Slim runtime**: Based on Eclipse Temurin JRE for a smaller image size
- **Non-root user**: Runs as the `fukuii` user for enhanced security
- **Health checks**: Built-in container health monitoring
- **Volume support**: Persistent data storage via Docker volumes
- **Environment configuration**: Configurable via environment variables
- **Standard data location**: Data stored in `/var/lib/mantis` as per requirements

## Quick Start

### Build the Docker image

```bash
./scripts/dev/docker-build.sh
```

### Run the container

```bash
./scripts/dev/docker-run.sh
```

This will start a Fukuii node connected to the Ethereum Classic mainnet (etc).

### Run with a different network

```bash
./scripts/dev/docker-run.sh mordor
```

Available networks: `etc`, `eth`, `mordor`, `testnet-internal`

## Using Docker Compose

Alternatively, you can use Docker Compose:

```bash
# Build and start
docker-compose up -d

# View logs
docker-compose logs -f

# Stop
docker-compose down
```

## Configuration

### Environment Variables

- `FUKUII_DATA_DIR`: Data directory (default: `/var/lib/mantis`)
- `FUKUII_NETWORK`: Network to connect to (default: `etc`)
- `FUKUII_JAVA_OPTS`: JVM options (default: `-Xmx2g`)
- `RPC_HOST`: RPC host for health checks (default: `localhost`)
- `RPC_PORT`: RPC port for health checks (default: `8545`)

### Volumes

- `/var/lib/mantis`: Blockchain data directory
- `/opt/fukuii/conf`: Configuration files (read-only)

### Ports

- `9076`: Ethereum P2P protocol connections
- `8545`: JSON-RPC over HTTP
- `8546`: JSON-RPC over WebSocket

## Example: Custom Configuration

```bash
docker run -d \
  --name fukuii-custom \
  -p 9076:9076 \
  -p 8545:8545 \
  -v /path/to/data:/var/lib/mantis \
  -v /path/to/conf:/opt/fukuii/conf:ro \
  -e FUKUII_JAVA_OPTS="-Xmx4g -Xms2g" \
  fukuii:latest \
  etc
```

## Health Check

The container includes a built-in health check that monitors the RPC endpoint. You can check the health status with:

```bash
docker inspect --format='{{.State.Health.Status}}' fukuii-node
```

## Logs

View container logs:

```bash
docker logs -f fukuii-node
```

## Security

- The container runs as a non-root user (`fukuii`)
- Only necessary runtime dependencies are included
- Configuration files are mounted read-only
- Data directory is isolated in a volume

## Troubleshooting

### Container fails to start

Check the logs:
```bash
docker logs fukuii-node
```

### Health check failing

The health check waits 60 seconds before starting. If it continues to fail, check:
- RPC is enabled in configuration
- Network connectivity is working
- Sufficient resources (CPU, memory) are available

### Data persistence

Ensure the data volume is properly mounted:
```bash
docker volume inspect fukuii-data
```

## Building for Production

For production builds with specific version tags:

```bash
./scripts/dev/docker-build.sh v1.0.0
```

This creates multiple tags:
- `fukuii:v1.0.0`
- `fukuii:latest`
- `fukuii:<git-commit-hash>`

## CI/CD Integration

The Dockerfile is designed to work with CI/CD pipelines and supports build arguments:

```bash
docker build \
  --build-arg BUILD_DATE="$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
  --build-arg VCS_REF="$(git rev-parse --short HEAD)" \
  --build-arg VERSION="1.0.0" \
  -t fukuii:latest \
  .
```
