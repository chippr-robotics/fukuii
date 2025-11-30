# Deployment Documentation

This directory contains documentation for deploying and running Fukuii nodes using Docker and other deployment methods.

## Contents

### Docker Deployment
- **[Docker Guide](docker.md)** - Comprehensive Docker deployment guide
- **[Test Network](test-network.md)** - Setting up a test network with Docker Compose

### Barad-dûr (Kong API Gateway)
- **[Kong Guide](kong.md)** - Barad-dûr (Kong) API gateway integration
- **[Kong Architecture](kong-architecture.md)** - Barad-dûr architecture and design
- **[Kong Quickstart](kong-quickstart.md)** - Quick start guide for Barad-dûr
- **[Kong Security](kong-security.md)** - Security considerations for Barad-dûr

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

## Troubleshooting

Common deployment issues and their solutions:

### Docker Issues

- **Container won't start**: Check logs with `docker-compose logs -f`
- **Port conflicts**: Verify no other services are using the required ports
- **Permission errors**: Ensure proper ownership of data directories

### Network Issues

- **Node not syncing**: Check peer connectivity and firewall settings
- **RPC not responding**: Verify the service is running and port is exposed

For detailed troubleshooting, see:
- [Known Issues](../runbooks/known-issues.md)
- [Log Triage](../runbooks/log-triage.md)
- [Barad-dûr Operations](../runbooks/barad-dur-operations.md)
