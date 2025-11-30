# Security Guide for Barad-dûr (Kong API Gateway)

This document outlines security best practices and configurations for deploying Barad-dûr (Kong API Gateway) with Fukuii in production environments.

## Table of Contents

1. [Security Overview](#security-overview)
2. [Authentication](#authentication)
3. [Authorization](#authorization)
4. [Network Security](#network-security)
5. [SSL/TLS Configuration](#ssltls-configuration)
6. [Rate Limiting](#rate-limiting)
7. [Monitoring and Alerting](#monitoring-and-alerting)
8. [Secrets Management](#secrets-management)
9. [Security Checklist](#security-checklist)

## Security Overview

The Barad-dûr (Kong) setup provides multiple layers of security:

```
┌─────────────────────────────────────────────────┐
│          Defense in Depth Layers                │
├─────────────────────────────────────────────────┤
│ 1. Network Firewall (External)                  │
│ 2. Kong IP Restriction                          │
│ 3. Kong Rate Limiting                           │
│ 4. Kong Authentication (Basic/JWT/Key)          │
│ 5. Kong Authorization (ACL)                     │
│ 6. Kong Request Validation                      │
│ 7. Fukuii Internal Security                     │
└─────────────────────────────────────────────────┘
```

## Authentication

### Basic Authentication

**Configuration in kong.yml:**

```yaml
consumers:
  - username: admin
    basicauth_credentials:
      - username: admin
        password: STRONG_PASSWORD_HERE
```

**Best Practices:**
- Use strong passwords (minimum 16 characters)
- Include uppercase, lowercase, numbers, and special characters
- Rotate passwords regularly (every 90 days)
- Never commit passwords to version control

**Generating Strong Passwords:**

```bash
# Generate a random 32-character password
openssl rand -base64 32

# Or use Python
python3 -c "import secrets; print(secrets.token_urlsafe(32))"
```

### API Key Authentication

API keys provide programmatic access without requiring user credentials.

**Configuration:**

```yaml
consumers:
  - username: app-service
    keyauth_credentials:
      - key: YOUR_GENERATED_API_KEY
```

**Best Practices:**
- Generate cryptographically secure random keys
- Use different keys for different environments (dev/staging/prod)
- Rotate keys regularly
- Revoke compromised keys immediately
- Log all API key usage

**Generating API Keys:**

```bash
# Generate a secure API key
uuidgen | sha256sum | awk '{print $1}'

# Or use Python
python3 -c "import uuid, hashlib; print(hashlib.sha256(str(uuid.uuid4()).encode()).hexdigest())"
```

### JWT Authentication

JWT provides stateless authentication with token expiration and claims.

**Configuration:**

```yaml
consumers:
  - username: jwt-user
    jwt_secrets:
      - key: unique-jwt-issuer-key
        algorithm: HS256
        secret: YOUR_JWT_SECRET
```

**Best Practices:**
- Use strong secrets (minimum 256 bits)
- Set appropriate token expiration (e.g., 1 hour for access tokens)
- Implement refresh tokens for long-lived sessions
- Include minimal claims in tokens
- Validate token signatures and expiration

**Generating JWT Secrets:**

```bash
# Generate a 256-bit secret
openssl rand -hex 32

# Generate a 512-bit secret (more secure)
openssl rand -hex 64
```

**Example JWT Token Generation (Node.js):**

```javascript
const jwt = require('jsonwebtoken');

const token = jwt.sign(
  { 
    sub: 'user123',
    iss: 'unique-jwt-issuer-key',
    exp: Math.floor(Date.now() / 1000) + (60 * 60) // 1 hour
  },
  'YOUR_JWT_SECRET',
  { algorithm: 'HS256' }
);
```

## Authorization

### Access Control Lists (ACL)

ACLs control which consumers can access specific routes.

**Configuration:**

```yaml
consumers:
  - username: admin
    acls:
      - group: admin

  - username: developer
    acls:
      - group: developer

# On routes that need ACL protection
plugins:
  - name: acl
    config:
      allow:
        - admin
        - developer
```

**Best Practices:**
- Follow principle of least privilege
- Create separate ACL groups for different roles
- Regularly audit ACL configurations
- Document ACL policies

### Request Validation

Validate incoming requests to prevent injection attacks:

```yaml
plugins:
  - name: request-validator
    config:
      body_schema: |
        {
          "type": "object",
          "properties": {
            "jsonrpc": {"type": "string"},
            "method": {"type": "string"},
            "params": {"type": "array"},
            "id": {"type": ["number", "string"]}
          },
          "required": ["jsonrpc", "method", "id"]
        }
```

## Network Security

### IP Restriction

Limit access to trusted IP addresses or networks:

```yaml
plugins:
  - name: ip-restriction
    config:
      allow:
        - 10.0.0.0/8        # Internal network
        - 172.16.0.0/12     # Private network
        - 192.168.0.0/16    # Local network
        - 203.0.113.0/24    # Your office IP range
      deny: []
```

**Best Practices:**
- Use CIDR notation for IP ranges
- Whitelist only necessary IPs
- Document all allowed IPs
- Review IP allowlist quarterly

### Firewall Configuration

**Docker Host Firewall (iptables):**

```bash
# Allow Kong proxy ports from anywhere
iptables -A INPUT -p tcp --dport 8000 -j ACCEPT
iptables -A INPUT -p tcp --dport 8443 -j ACCEPT

# Allow Kong admin API only from localhost
iptables -A INPUT -p tcp --dport 8001 -s 127.0.0.1 -j ACCEPT
iptables -A INPUT -p tcp --dport 8001 -j DROP

# Allow Prometheus only from monitoring network
iptables -A INPUT -p tcp --dport 9090 -s 10.0.1.0/24 -j ACCEPT
iptables -A INPUT -p tcp --dport 9090 -j DROP

# Allow Grafana only from monitoring network
iptables -A INPUT -p tcp --dport 3000 -s 10.0.1.0/24 -j ACCEPT
iptables -A INPUT -p tcp --dport 3000 -j DROP

# Save rules
iptables-save > /etc/iptables/rules.v4
```

**Cloud Provider Security Groups:**

If deploying on AWS, Azure, or GCP, configure security groups:

```yaml
# Example AWS Security Group rules
Inbound Rules:
  - Port 8000: 0.0.0.0/0 (HTTP Proxy - public)
  - Port 8443: 0.0.0.0/0 (HTTPS Proxy - public)
  - Port 8001: YOUR_IP/32 (Admin API - restricted)
  - Port 9090: MONITORING_SUBNET (Prometheus)
  - Port 3000: MONITORING_SUBNET (Grafana)
  - Port 30303: 0.0.0.0/0 (Fukuii P2P)
```

## SSL/TLS Configuration

### Enabling HTTPS

1. **Generate SSL Certificates:**

```bash
# Using Let's Encrypt (recommended for production)
certbot certonly --standalone -d api.yourdomain.com

# Or self-signed for testing
openssl req -x509 -nodes -days 365 -newkey rsa:4096 \
  -keyout kong.key -out kong.crt \
  -subj "/CN=api.yourdomain.com"
```

2. **Configure Kong to Use Certificates:**

Update `docker-compose.yml`:

```yaml
kong:
  environment:
    - KONG_SSL_CERT=/etc/kong/ssl/kong.crt
    - KONG_SSL_CERT_KEY=/etc/kong/ssl/kong.key
    - KONG_PROXY_LISTEN=0.0.0.0:8000, 0.0.0.0:8443 ssl
  volumes:
    - ./ssl:/etc/kong/ssl:ro
```

3. **Update kong.yml Routes to Use HTTPS:**

```yaml
routes:
  - name: jsonrpc-main
    protocols:
      - https  # Only HTTPS
    paths:
      - /
```

### TLS Best Practices

- Use TLS 1.2 or higher only
- Disable weak cipher suites
- Enable HTTP Strict Transport Security (HSTS)
- Use Certificate Transparency
- Renew certificates before expiration

**Kong TLS Configuration:**

```yaml
environment:
  - KONG_SSL_PROTOCOLS=TLSv1.2 TLSv1.3
  - KONG_SSL_CIPHERS=ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384
  - KONG_HEADERS=off
```

### HSTS Configuration

Add HSTS header via Kong plugin:

```yaml
plugins:
  - name: response-transformer
    config:
      add:
        headers:
          - "Strict-Transport-Security: max-age=31536000; includeSubDomains"
```

## Rate Limiting

### Global Rate Limits

Prevent abuse across all endpoints:

```yaml
plugins:
  - name: rate-limiting
    config:
      second: 10
      minute: 100
      hour: 5000
      day: 50000
      policy: local
      fault_tolerant: true
      hide_client_headers: false
```

### Per-Consumer Rate Limits

Different limits for different user types:

```yaml
# Admin user - higher limits
consumers:
  - username: admin
    plugins:
      - name: rate-limiting
        config:
          minute: 1000
          hour: 50000

# Regular user - standard limits
consumers:
  - username: developer
    plugins:
      - name: rate-limiting
        config:
          minute: 100
          hour: 5000
```

### Distributed Rate Limiting

For multi-instance deployments, use Redis:

```yaml
plugins:
  - name: rate-limiting
    config:
      minute: 100
      hour: 5000
      policy: redis
      redis_host: redis
      redis_port: 6379
      redis_password: YOUR_REDIS_PASSWORD
      redis_database: 0
```

## Monitoring and Alerting

### Security Monitoring

Monitor these metrics for security incidents:

```yaml
# Prometheus alert rules (create alert_rules.yml)
groups:
  - name: security_alerts
    interval: 30s
    rules:
      # High rate of 401 responses
      - alert: HighAuthenticationFailureRate
        expr: rate(kong_http_requests_total{code="401"}[5m]) > 10
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High authentication failure rate detected"
          
      # High rate of 403 responses
      - alert: HighAuthorizationFailureRate
        expr: rate(kong_http_requests_total{code="403"}[5m]) > 5
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High authorization failure rate detected"
          
      # Rate limit violations
      - alert: RateLimitViolations
        expr: rate(kong_http_requests_total{code="429"}[5m]) > 20
        for: 5m
        labels:
          severity: info
        annotations:
          summary: "High rate of rate limit violations"
```

### Security Logging

Enable comprehensive logging:

```yaml
plugins:
  - name: file-log
    config:
      path: /var/log/kong/access.log
      reopen: true
      
  - name: http-log
    config:
      http_endpoint: https://your-siem-system.com/logs
      method: POST
      content_type: application/json
```

### Audit Logging

Log all administrative actions:

```yaml
# Enable admin API logging
environment:
  - KONG_ADMIN_ACCESS_LOG=/dev/stdout
  - KONG_ADMIN_ERROR_LOG=/dev/stderr
```

## Secrets Management

### Using Docker Secrets

For production deployments, use Docker secrets instead of environment variables:

```yaml
services:
  kong:
    secrets:
      - kong_db_password
      - jwt_secret
    environment:
      - KONG_PG_PASSWORD_FILE=/run/secrets/kong_db_password

secrets:
  kong_db_password:
    file: ./secrets/db_password.txt
  jwt_secret:
    file: ./secrets/jwt_secret.txt
```

### Using HashiCorp Vault

Integrate with Vault for dynamic secrets:

```bash
# Install Kong Vault plugin
# Configure Vault authentication
# Reference secrets from Vault in Kong configuration
```

### Secrets Rotation

Implement regular secrets rotation:

1. **Database Passwords**: Rotate every 90 days
2. **API Keys**: Rotate every 180 days
3. **JWT Secrets**: Rotate every 365 days
4. **SSL Certificates**: Auto-renew 30 days before expiration

## Security Checklist

### Pre-Deployment

- [ ] Change all default passwords and secrets
- [ ] Generate strong, random credentials
- [ ] Configure SSL/TLS certificates
- [ ] Set up firewall rules
- [ ] Configure IP restrictions
- [ ] Enable rate limiting
- [ ] Set up authentication (Basic Auth, JWT, or Key Auth)
- [ ] Configure ACLs for authorization
- [ ] Review and minimize exposed ports
- [ ] Disable unnecessary plugins
- [ ] Set up security monitoring and alerting
- [ ] Configure log aggregation
- [ ] Document security policies

### Post-Deployment

- [ ] Verify SSL/TLS is working correctly
- [ ] Test authentication mechanisms
- [ ] Verify rate limiting is effective
- [ ] Check firewall rules are active
- [ ] Review access logs for anomalies
- [ ] Set up automated security scanning
- [ ] Configure backup procedures
- [ ] Test disaster recovery procedures
- [ ] Document incident response procedures
- [ ] Schedule regular security audits

### Ongoing Maintenance

- [ ] Rotate credentials regularly
- [ ] Update Docker images for security patches
- [ ] Review and update firewall rules
- [ ] Monitor security metrics and logs
- [ ] Respond to security alerts promptly
- [ ] Conduct quarterly security reviews
- [ ] Keep documentation up to date
- [ ] Test backup and recovery procedures
- [ ] Review and update ACLs
- [ ] Perform penetration testing annually

## Incident Response

### Security Incident Procedures

1. **Detection**: Monitor logs and metrics for anomalies
2. **Assessment**: Determine severity and scope of incident
3. **Containment**: Isolate affected systems
4. **Eradication**: Remove threat and close vulnerabilities
5. **Recovery**: Restore normal operations
6. **Lessons Learned**: Document and improve processes

### Emergency Contacts

Maintain a list of emergency contacts:

- Security team lead
- Infrastructure team
- Legal/Compliance
- External security consultants

### Rollback Procedures

In case of security compromise:

```bash
# Stop compromised services
docker-compose stop kong

# Rotate all credentials
# Update kong.yml with new credentials

# Restart with new configuration
docker-compose up -d kong

# Verify security posture
# Monitor for continued threats
```

## Additional Resources

- [Kong Security Documentation](https://docs.konghq.com/gateway/latest/security/)
- [OWASP API Security Top 10](https://owasp.org/www-project-api-security/)
- [CIS Docker Benchmark](https://www.cisecurity.org/benchmark/docker)
- [NIST Cybersecurity Framework](https://www.nist.gov/cyberframework)

## Support

For security-related questions or to report vulnerabilities:

- Email: security@chippr-robotics.io
- Responsible Disclosure: See the SECURITY.md file in the repository root
