# Fukuii Docker Images

This directory contains Dockerfiles for building and running Fukuii Ethereum Client in containerized environments.

## Available Images

### 1. Production Image (`Dockerfile`)
The main production-ready image for running Fukuii.

**Features:**
- Multi-stage build for optimal size and security
- Based on `eclipse-temurin:17-jre-jammy` (slim JRE)
- Runs as non-root user (`fukuii:fukuii`, UID/GID 1000)
- Includes built-in healthcheck script
- Exposes standard Ethereum ports (8545, 8546, 30303)
- Minimal attack surface with only required dependencies

**Build:**
```bash
# Important: Initialize submodules before building
git submodule update --init --recursive

# Build the Docker image
docker build -f docker/Dockerfile -t fukuii:latest .
```

**Note:** The build requires git submodules to be initialized before running Docker build. The GitHub Actions CI/CD pipeline handles this automatically via the checkout step with `submodules: recursive`.

**Run:**
```bash
# Start with default configuration (ETC network)
docker run -d \
  --name fukuii \
  -p 8545:8545 \
  -p 8546:8546 \
  -p 30303:30303 \
  -v fukuii-data:/app/data \
  -v fukuii-conf:/app/conf \
  fukuii:latest

# Start with custom configuration
docker run -d \
  --name fukuii \
  -p 8545:8545 \
  -v fukuii-data:/app/data \
  -v /path/to/your/conf:/app/conf \
  fukuii:latest etc
```

### 2. Development Image (`Dockerfile-dev`)
A development image with JDK 17 and SBT for building and testing.

**Features:**
- Based on `eclipse-temurin:17-jdk-jammy` (full JDK)
- Includes SBT build tool
- Includes Git for source management
- Runs as non-root user
- Useful for CI/CD and local development

**Build:**
```bash
docker build -f docker/Dockerfile-dev -t fukuii-dev:latest .
```

**Run:**
```bash
# Interactive development shell
docker run -it --rm \
  -v $(pwd):/workspace \
  -w /workspace \
  fukuii-dev:latest /bin/bash

# Run tests
docker run --rm \
  -v $(pwd):/workspace \
  -w /workspace \
  fukuii-dev:latest sbt testAll
```

### 3. Base Image (`Dockerfile-base`)
A minimal base image with common dependencies.

**Features:**
- Based on `ubuntu:22.04` (Ubuntu Jammy)
- Minimal set of packages (curl, ca-certificates, locales)
- Non-root user configured
- Used as a foundation for other custom images

**Build:**
```bash
docker build -f docker/Dockerfile-base -t fukuii-base:latest .
```

### 4. Distroless Image (`Dockerfile.distroless`)
Maximum security image using Google's distroless base.

**Features:**
- Based on `gcr.io/distroless/java17-debian12:nonroot`
- Minimal attack surface - no shell, no package manager
- Smallest possible image size
- Best for production deployments with external orchestration

**Note:** Distroless images don't support shell-based healthchecks. Use external health monitoring (e.g., Kubernetes liveness/readiness probes).

**Build:**
```bash
docker build -f docker/Dockerfile.distroless -t fukuii:distroless .
```

## Health Checks

The production image includes a built-in healthcheck script that:
1. Verifies the Fukuii process is running
2. Optionally tests the JSON-RPC endpoint (if enabled and accessible)

**Docker Healthcheck:**
```bash
# Check container health status
docker inspect --format='{{.State.Health.Status}}' fukuii

# View healthcheck logs
docker inspect --format='{{json .State.Health}}' fukuii | jq
```

**Kubernetes Probes:**
```yaml
livenessProbe:
  exec:
    command:
    - /usr/local/bin/healthcheck.sh
  initialDelaySeconds: 60
  periodSeconds: 30
  timeoutSeconds: 10

readinessProbe:
  exec:
    command:
    - /usr/local/bin/healthcheck.sh
  initialDelaySeconds: 30
  periodSeconds: 10
  timeoutSeconds: 5
```

For distroless images, use HTTP-based probes:
```yaml
livenessProbe:
  httpGet:
    path: /
    port: 8545
  initialDelaySeconds: 60
  periodSeconds: 30

readinessProbe:
  httpGet:
    path: /
    port: 8545
  initialDelaySeconds: 30
  periodSeconds: 10
```

## Security Considerations

### Non-Root User
All images run as the `fukuii` user (UID 1000, GID 1000) for security. This prevents privilege escalation attacks.

### Image Scanning
Regularly scan images for vulnerabilities:
```bash
# Using Docker Scout (if available)
docker scout cves fukuii:latest

# Using Trivy
trivy image fukuii:latest

# Using Grype
grype fukuii:latest
```

### Best Practices
- Always use specific version tags in production (avoid `:latest`)
- Regularly update base images to get security patches
- Use distroless images when possible for maximum security
- Limit exposed ports to only what's necessary
- Use read-only root filesystem when possible
- Set resource limits (memory, CPU) appropriately

## Environment Variables

- `FUKUII_DATA_DIR` - Data directory path (default: `/app/data`)
- `FUKUII_CONF_DIR` - Configuration directory path (default: `/app/conf`)
- `JAVA_OPTS` - Additional JVM options

## Volumes

- `/app/data` - Blockchain data and state
- `/app/conf` - Configuration files

## Ports

- `8545` - HTTP JSON-RPC API
- `8546` - WebSocket JSON-RPC API
- `30303` - P2P networking (TCP and UDP)

## Docker Compose Example

```yaml
version: '3.8'

services:
  fukuii:
    image: fukuii:latest
    container_name: fukuii
    restart: unless-stopped
    ports:
      - "8545:8545"
      - "8546:8546"
      - "30303:30303"
    volumes:
      - fukuii-data:/app/data
      - ./conf:/app/conf:ro
    environment:
      - JAVA_OPTS=-Xmx4g -Xms4g
    healthcheck:
      test: ["/usr/local/bin/healthcheck.sh"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s

volumes:
  fukuii-data:
```

## CI/CD Integration

The GitHub Actions workflow automatically builds and publishes images to GitHub Container Registry:

- **Main Image:** `ghcr.io/chippr-robotics/fukuii:latest`
- **Dev Image:** `ghcr.io/chippr-robotics/fukuii-dev:latest`
- **Base Image:** `ghcr.io/chippr-robotics/fukuii-base:latest`

Images are tagged with:
- Branch names (e.g., `main`, `develop`)
- Git SHA (e.g., `sha-a1b2c3d`)
- Semantic versions (e.g., `v1.0.0`, `1.0`)
- `latest` for the default branch

## Migration from Old Images

If you're migrating from the old Nix-based images:

1. **Data compatibility:** The new images use the same data format. Mount your existing data volume at `/app/data`.

2. **Configuration:** Update configuration file paths if needed. The new images expect config in `/app/conf`.

3. **User/Group:** The new images use UID/GID 1000. If your volumes have different ownership:
   ```bash
   docker run --rm -v fukuii-data:/data alpine chown -R 1000:1000 /data
   ```

4. **Environment variables:** Update any Nix-specific environment variables to standard JVM options.

## Troubleshooting

### Container won't start
```bash
# Check logs
docker logs fukuii

# Run in foreground to see errors
docker run --rm -it fukuii:latest etc
```

### Permission denied errors
```bash
# Check volume ownership
docker run --rm -v fukuii-data:/data alpine ls -la /data

# Fix ownership if needed
docker run --rm -v fukuii-data:/data alpine chown -R 1000:1000 /data
```

### Health check failing
```bash
# Run health check manually
docker exec fukuii /usr/local/bin/healthcheck.sh

# Check if RPC is enabled in configuration
docker exec fukuii cat /app/conf/app.conf | grep rpc
```

## Support

For issues and questions:
- GitHub Issues: https://github.com/chippr-robotics/fukuii/issues
- Documentation: https://github.com/chippr-robotics/fukuii/blob/main/README.md
