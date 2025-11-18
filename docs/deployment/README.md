# Deployment Documentation

This directory contains documentation for deploying and running Fukuii nodes using Docker and other deployment methods.

## Contents

### Docker Deployment
- **[Docker Guide](docker.md)** - Comprehensive Docker deployment guide
- **[Test Network](test-network.md)** - Setting up a test network with Docker Compose

### Kong API Gateway
- **[Kong Guide](kong.md)** - Kong API gateway integration
- **[Kong Architecture](kong-architecture.md)** - Kong architecture and design
- **[Kong Quickstart](kong-quickstart.md)** - Quick start guide for Kong
- **[Kong Security](kong-security.md)** - Security considerations for Kong

### Client Comparisons
- **[Besu Comparison](besu-comparison.md)** - Besu client setup for comparison testing
- **[Geth Comparison](geth-comparison.md)** - Geth client setup for comparison testing

## Related Documentation

- [Operations Runbooks](../runbooks/) - Operational guides for running nodes
- [Operations Monitoring](../operations/metrics-and-monitoring.md) - Metrics and monitoring setup
- [Architecture Overview](../architecture/architecture-overview.md) - System architecture

## Quick Start

For quick deployment using Docker:

```bash
# Build Docker image
docker build -t fukuii:latest .

# Run with Docker Compose
cd docker/fukuii
docker-compose up -d
```

See the [Docker Guide](docker.md) for detailed instructions.
