# TLS Operations Runbook

**Audience**: Operators configuring secure HTTPS access for Fukuii node RPC endpoints  
**Estimated Time**: 30-60 minutes for initial setup  
**Prerequisites**: Running Fukuii node, basic understanding of TLS/SSL certificates

## Overview

This runbook covers Transport Layer Security (TLS) configuration for Fukuii nodes. TLS encrypts communication between clients and your node's JSON-RPC API, protecting sensitive data and API calls from eavesdropping and tampering.

Fukuii supports both HTTP and HTTPS modes for the JSON-RPC endpoint. The TLS implementation has been verified to be functional after the repository migration from Mantis.

## Table of Contents

1. [When to Use TLS](#when-to-use-tls)
2. [TLS Architecture](#tls-architecture)
3. [Certificate Generation](#certificate-generation)
4. [Configuration](#configuration)
5. [Testing TLS Setup](#testing-tls-setup)
6. [Certificate Management](#certificate-management)
7. [Production Considerations](#production-considerations)
8. [Troubleshooting](#troubleshooting)
9. [Security Best Practices](#security-best-practices)

## When to Use TLS

### Use TLS When:

- ✅ **Exposing RPC to external services**: Any network communication beyond localhost
- ✅ **Connecting from mobile/web applications**: Client apps need encrypted connections
- ✅ **Compliance requirements**: Industry regulations (PCI-DSS, HIPAA, etc.)
- ✅ **Multi-server deployments**: Communication between servers over network
- ✅ **Public API services**: Any publicly accessible RPC endpoint

### May Not Need TLS When:

- ⚠️ **Localhost-only access**: Single-server setup with all services on localhost
- ⚠️ **Behind reverse proxy**: If reverse proxy (nginx/Caddy) handles TLS termination
- ⚠️ **Testing/development**: Non-production environments (still recommended for production-like testing)

**Important**: Even when not using TLS directly on Fukuii, ensure your reverse proxy or load balancer implements TLS for external connections.

## TLS Architecture

### Components

Fukuii's TLS implementation consists of several key components:

1. **SSLConfig** (`src/main/scala/com/chipprbots/ethereum/security/SSLConfig.scala`)
   - Configuration data class for TLS settings
   - Reads certificate configuration from HOCON files

2. **SSLContextFactory** (`src/main/scala/com/chipprbots/ethereum/security/SSLContextFactory.scala`)
   - Creates and initializes SSL contexts
   - Validates certificate files and passwords
   - Loads PKCS12 keystores

3. **SecureJsonRpcHttpServer** (`src/main/scala/com/chipprbots/ethereum/jsonrpc/server/http/SecureJsonRpcHttpServer.scala`)
   - HTTPS-enabled JSON-RPC server
   - Uses Apache Pekko HTTP with SSL/TLS support

### How It Works

```
Client Request (HTTPS)
    │
    ├──> TLS Handshake (SecureJsonRpcHttpServer)
    │       │
    │       ├──> Load SSL Context (SSLContextFactory)
    │       │       │
    │       │       ├──> Read Certificate (PKCS12 keystore)
    │       │       └──> Validate Password
    │       │
    │       └──> Establish Encrypted Connection
    │
    └──> Process JSON-RPC Request
            │
            └──> Return Encrypted Response
```

## Certificate Generation

### Quick Start: Generate Self-Signed Certificate

Fukuii provides a certificate generation script in the `tls/` directory:

```bash
cd tls/
./gen-cert.sh
```

This script:
1. Generates a random password using `pwgen`
2. Stores the password in `tls/password`
3. Creates a PKCS12 keystore at `tls/fukuiiCA.p12`
4. Generates a 4096-bit RSA certificate
5. Sets validity for 9999 days (~27 years)
6. Configures certificate for localhost (127.0.0.1)

**Prerequisites**: The script requires `pwgen` to be installed:
```bash
# Debian/Ubuntu
sudo apt-get install pwgen

# macOS
brew install pwgen

# Or use manual password generation (see below)
```

### Manual Certificate Generation

If you prefer to generate certificates manually or need custom settings:

#### Option 1: Manual keytool command

```bash
cd tls/

# Generate a random password or use your own
# Using 24 bytes provides approximately 192 bits of entropy for strong security
export PW=$(openssl rand -base64 24)
echo "$PW" > ./password

# Generate certificate
keytool -genkeypair \
  -keystore fukuiiCA.p12 \
  -storetype PKCS12 \
  -dname "CN=your-hostname.example.com" \
  -ext "san=dns:your-hostname.example.com,ip:YOUR_IP_ADDRESS" \
  -keypass:env PW \
  -storepass:env PW \
  -keyalg RSA \
  -keysize 4096 \
  -validity 365 \
  -ext KeyUsage:critical="keyCertSign" \
  -ext BasicConstraints:critical="ca:true"
```

**Important**: Replace `your-hostname.example.com` and `YOUR_IP_ADDRESS` with your actual values.

#### Option 2: OpenSSL (for more control)

```bash
cd tls/

# Generate private key
openssl genrsa -out server.key 4096

# Generate certificate signing request (CSR)
openssl req -new -key server.key -out server.csr \
  -subj "/CN=your-hostname.example.com"

# Generate self-signed certificate
openssl x509 -req -days 365 -in server.csr \
  -signkey server.key -out server.crt

# Convert to PKCS12 format
openssl pkcs12 -export -out fukuiiCA.p12 \
  -inkey server.key -in server.crt \
  -passout pass:your-password

# Save password
echo "your-password" > password
```

### Using CA-Signed Certificates

For production environments, use certificates from a trusted Certificate Authority:

#### Step 1: Generate CSR

```bash
keytool -certreq -alias mykey \
  -keystore fukuiiCA.p12 \
  -storepass "$(cat password)" \
  -file fukuii.csr
```

#### Step 2: Submit CSR to CA

Submit `fukuii.csr` to your Certificate Authority (Let's Encrypt, DigiCert, etc.)

#### Step 3: Import Signed Certificate

```bash
# Import CA root certificate
keytool -import -trustcacerts -alias root \
  -file ca-root.crt \
  -keystore fukuiiCA.p12 \
  -storepass "$(cat password)"

# Import signed certificate
keytool -import -alias mykey \
  -file signed-certificate.crt \
  -keystore fukuiiCA.p12 \
  -storepass "$(cat password)"
```

### Certificate Verification

Verify your certificate is correctly generated:

```bash
cd tls/

# List keystore contents
keytool -list -v -keystore fukuiiCA.p12 \
  -storepass "$(cat password)"

# Check certificate details
keytool -list -v -keystore fukuiiCA.p12 \
  -storepass "$(cat password)" | grep -A 10 "Certificate\[1\]"
```

**Expected output should show:**
- Alias name: mykey (default) or custom name you specified
- Entry type: PrivateKeyEntry
- Certificate chain length: 1
- Valid from/to dates
- 4096-bit RSA key
- Subject Alternative Names (SAN) matching your domain/IP

**Note**: The default alias generated by keytool is "mykey". If you need to reference the certificate later (for rotation, export, etc.), use this alias or discover it with `keytool -list -keystore fukuiiCA.p12`.

## Configuration

### Step 1: Locate Configuration File

Fukuii's configuration is in `src/main/resources/conf/base.conf` or your custom configuration file:

```bash
# Default location after extraction
conf/base.conf

# Or custom config
conf/my-custom.conf
```

### Step 2: Enable HTTPS Mode

Edit the configuration file and modify the RPC section:

```hocon
fukuii {
  network {
    rpc {
      http {
        # Change mode from "http" to "https"
        mode = "https"
        
        enabled = true
        interface = "0.0.0.0"  # Listen on all interfaces (or specific IP)
        port = 8546
        
        # Uncomment and configure certificate section
        certificate {
          # Path to the keystore storing the certificates
          keystore-path = "tls/fukuiiCA.p12"
          
          # Type of certificate keystore
          keystore-type = "pkcs12"
          
          # File with the password for the keystore
          password-file = "tls/password"
        }
        
        # CORS settings (adjust as needed)
        cors-allowed-origins = "*"
        
        # Rate limiting configuration
        rate-limit {
          enabled = false
          min-request-interval = 1.second
          latest-timestamp-cache-size = 1024
        }
      }
    }
  }
}
```

### Step 3: Verify Certificate Files

Ensure certificate files are in the correct location:

```bash
# From Fukuii distribution directory
ls -l tls/
# Should show:
# - fukuiiCA.p12 (keystore file)
# - password (password file)
# - gen-cert.sh (generation script)
```

**Important**: File paths in configuration are relative to the Fukuii working directory (where you run the `fukuii` command).

### Configuration Options Reference

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `mode` | String | `"http"` | Protocol mode: `"http"` or `"https"` |
| `enabled` | Boolean | `true` | Enable/disable JSON-RPC endpoint |
| `interface` | String | `"localhost"` | Listening interface (use `"0.0.0.0"` for all) |
| `port` | Int | `8546` | Listening port |
| `certificate.keystore-path` | String | - | Path to PKCS12 keystore file |
| `certificate.keystore-type` | String | `"pkcs12"` | Keystore type (typically PKCS12) |
| `certificate.password-file` | String | - | Path to file containing keystore password |
| `cors-allowed-origins` | String | - | CORS configuration (`"*"` for all, or specific origins) |

### Alternative: Environment Variables

You can override configuration using environment variables:

```bash
# Set HTTPS mode
export FUKUII_NETWORK_RPC_HTTP_MODE="https"

# Set certificate path
export FUKUII_NETWORK_RPC_HTTP_CERTIFICATE_KEYSTORE_PATH="tls/fukuiiCA.p12"
export FUKUII_NETWORK_RPC_HTTP_CERTIFICATE_PASSWORD_FILE="tls/password"
```

### Alternative: Command-Line Config

Create a separate TLS configuration file:

```bash
cat > conf/tls-override.conf <<EOF
fukuii.network.rpc.http {
  mode = "https"
  certificate {
    keystore-path = "tls/fukuiiCA.p12"
    keystore-type = "pkcs12"
    password-file = "tls/password"
  }
}
EOF
```

Then start Fukuii with:
```bash
./bin/fukuii -Dconfig.file=conf/tls-override.conf etc
```

## Testing TLS Setup

### Step 1: Start Fukuii with TLS

```bash
# Start the node
./bin/fukuii etc

# Watch logs for SSL initialization
tail -f ~/.fukuii/etc/logs/fukuii.log | grep -i "ssl\|https\|certificate"
```

**Expected log output:**
```
INFO  - Loaded ssl config successful
INFO  - JSON RPC HTTPS server listening on /0.0.0.0:8546
```

**Error indicators:**
```
ERROR - Cannot start JSON HTTPS RPC server due to: SSLError(...)
ERROR - Certificate keystore path configured but file is missing
ERROR - Invalid Certificate keystore
```

### Step 2: Test HTTPS Connection

#### Using curl

```bash
# Self-signed certificate (skip verification for testing)
curl -k https://localhost:8546 \
  -X POST \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'

# With CA-signed certificate (verify)
curl https://your-domain.com:8546 \
  -X POST \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'
```

**Expected response:**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": "0x12345"
}
```

#### Using openssl s_client

Test TLS handshake:
```bash
openssl s_client -connect localhost:8546 -showcerts
```

**Expected output:**
```
CONNECTED(00000003)
depth=0 CN = 127.0.0.1
verify error:num=18:self signed certificate
verify return:1
...
SSL-Session:
    Protocol  : TLSv1.3
    Cipher    : TLS_AES_256_GCM_SHA384
...
```

#### Using Python

```python
import requests
import json

# Disable SSL verification for self-signed certs (testing only!)
import urllib3
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

url = "https://localhost:8546"
headers = {"Content-Type": "application/json"}
payload = {
    "jsonrpc": "2.0",
    "method": "eth_blockNumber",
    "params": [],
    "id": 1
}

response = requests.post(url, 
                        json=payload, 
                        headers=headers,
                        verify=False)  # Use verify=True with CA certs

print(json.dumps(response.json(), indent=2))
```

#### Using JavaScript (Node.js)

```javascript
const https = require('https');

const options = {
  hostname: 'localhost',
  port: 8546,
  path: '/',
  method: 'POST',
  headers: {
    'Content-Type': 'application/json'
  },
  // For self-signed certificates (testing only!)
  rejectUnauthorized: false
};

const data = JSON.stringify({
  jsonrpc: '2.0',
  method: 'eth_blockNumber',
  params: [],
  id: 1
});

const req = https.request(options, (res) => {
  let body = '';
  res.on('data', (chunk) => body += chunk);
  res.on('end', () => console.log(JSON.parse(body)));
});

req.write(data);
req.end();
```

### Step 3: Verify TLS Version and Ciphers

Check which TLS versions and ciphers are negotiated:

```bash
# Check TLS 1.3
openssl s_client -connect localhost:8546 -tls1_3

# Check TLS 1.2
openssl s_client -connect localhost:8546 -tls1_2

# List supported ciphers
nmap --script ssl-enum-ciphers -p 8546 localhost
```

### Health Check Endpoints

Test health endpoints over HTTPS:

```bash
# Health check
curl -k https://localhost:8546/health

# Readiness check
curl -k https://localhost:8546/readiness

# Full healthcheck
curl -k https://localhost:8546/healthcheck
```

## Certificate Management

### Certificate Rotation

Regularly rotate certificates to maintain security:

#### Step 1: Generate New Certificate

```bash
cd tls/
# Backup old certificate
mv fukuiiCA.p12 fukuiiCA.p12.old
mv password password.old

# Generate new certificate
./gen-cert.sh
```

#### Step 2: Update Configuration (if needed)

If paths or passwords changed, update `conf/base.conf`.

#### Step 3: Restart Fukuii

```bash
# Graceful restart
kill -TERM $(pgrep -f fukuii)
./bin/fukuii etc
```

#### Step 4: Verify New Certificate

```bash
curl -k https://localhost:8546/health
```

### Certificate Expiration Monitoring

Set up monitoring for certificate expiration:

```bash
#!/bin/bash
# check-cert-expiry.sh

KEYSTORE="tls/fukuiiCA.p12"
PASSWORD=$(cat tls/password)
WARN_DAYS=30

# Extract certificate (use 'mykey' as default alias, or discover with: keytool -list -keystore "$KEYSTORE")
ALIAS=$(keytool -list -keystore "$KEYSTORE" -storepass "$PASSWORD" 2>/dev/null | grep PrivateKeyEntry | head -1 | awk '{print $1}' | tr -d ',')
keytool -exportcert -alias "${ALIAS:-mykey}" \
  -keystore "$KEYSTORE" \
  -storepass "$PASSWORD" \
  -rfc -file /tmp/cert.pem

# Check expiration
EXPIRY=$(openssl x509 -enddate -noout -in /tmp/cert.pem | cut -d= -f2)
EXPIRY_EPOCH=$(date -d "$EXPIRY" +%s)
NOW_EPOCH=$(date +%s)
DAYS_LEFT=$(( ($EXPIRY_EPOCH - $NOW_EPOCH) / 86400 ))

echo "Certificate expires in $DAYS_LEFT days"

if [ $DAYS_LEFT -lt $WARN_DAYS ]; then
  echo "WARNING: Certificate expires in less than $WARN_DAYS days!"
  exit 1
fi

rm /tmp/cert.pem
```

Add to cron:
```bash
# Run daily at 9 AM
0 9 * * * /path/to/check-cert-expiry.sh
```

### Backup and Recovery

#### Backup Certificate

```bash
# Create backup directory
mkdir -p backups/tls/$(date +%Y%m%d)

# Backup certificate and password
cp tls/fukuiiCA.p12 backups/tls/$(date +%Y%m%d)/
cp tls/password backups/tls/$(date +%Y%m%d)/

# Create encrypted archive
tar czf backups/tls-$(date +%Y%m%d).tar.gz \
  backups/tls/$(date +%Y%m%d)/
```

#### Restore Certificate

```bash
# Extract backup
tar xzf backups/tls-20251106.tar.gz

# Copy to TLS directory
cp backups/tls/20251106/fukuiiCA.p12 tls/
cp backups/tls/20251106/password tls/

# Restart node
kill -TERM $(pgrep -f fukuii)
./bin/fukuii etc
```

## Production Considerations

### Security Hardening

1. **Use CA-Signed Certificates**: Avoid self-signed certificates in production
2. **Strong Passwords**: Use long, random passwords for keystores
3. **File Permissions**: Restrict access to certificate files
   ```bash
   chmod 600 tls/fukuiiCA.p12 tls/password
   chown fukuii:fukuii tls/fukuiiCA.p12 tls/password
   ```
4. **Certificate Pinning**: Implement certificate pinning in clients
5. **HSTS Headers**: Use HTTP Strict Transport Security

### Reverse Proxy Configuration

For production, consider TLS termination at reverse proxy:

#### Nginx Example

```nginx
upstream fukuii_rpc {
    server localhost:8546;
}

server {
    listen 443 ssl http2;
    server_name api.example.com;

    # SSL Configuration
    ssl_certificate /etc/nginx/ssl/cert.pem;
    ssl_certificate_key /etc/nginx/ssl/key.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;
    ssl_prefer_server_ciphers on;

    # Security headers
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Content-Type-Options nosniff;
    add_header X-Frame-Options DENY;

    # Rate limiting
    limit_req_zone $binary_remote_addr zone=rpc_limit:10m rate=10r/s;
    limit_req zone=rpc_limit burst=20 nodelay;

    location / {
        proxy_pass http://fukuii_rpc;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        
        # Timeouts
        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
    }

    location /health {
        proxy_pass http://fukuii_rpc/health;
        access_log off;
    }
}

# Redirect HTTP to HTTPS
server {
    listen 80;
    server_name api.example.com;
    return 301 https://$server_name$request_uri;
}
```

#### Caddy Example (Automatic HTTPS)

```caddyfile
api.example.com {
    reverse_proxy localhost:8546
    
    # Automatic HTTPS with Let's Encrypt
    tls {
        protocols tls1.2 tls1.3
    }
    
    # Rate limiting
    rate_limit {
        zone static 10r/s
    }
    
    # Headers
    header {
        Strict-Transport-Security "max-age=31536000;"
        X-Content-Type-Options "nosniff"
        X-Frame-Options "DENY"
    }
}
```

### Load Balancing with TLS

For high-availability setups:

```nginx
upstream fukuii_cluster {
    least_conn;
    server fukuii-1.internal:8546 max_fails=3 fail_timeout=30s;
    server fukuii-2.internal:8546 max_fails=3 fail_timeout=30s;
    server fukuii-3.internal:8546 max_fails=3 fail_timeout=30s;
    
    keepalive 32;
}

server {
    listen 443 ssl http2;
    server_name api.example.com;
    
    ssl_certificate /etc/nginx/ssl/cert.pem;
    ssl_certificate_key /etc/nginx/ssl/key.pem;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 10m;
    
    location / {
        proxy_pass http://fukuii_cluster;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        
        # Health check
        health_check interval=10s fails=3 passes=2 uri=/health;
    }
}
```

### Monitoring and Logging

Monitor TLS connections:

```bash
# Monitor SSL connections
watch -n 1 'ss -t -a | grep :8546'

# Check for SSL errors in logs
tail -f ~/.fukuii/etc/logs/fukuii.log | grep -i "ssl\|certificate\|https"

# Monitor certificate expiration
openssl s_client -connect localhost:8546 -servername localhost </dev/null 2>/dev/null \
  | openssl x509 -noout -enddate
```

### Performance Tuning

TLS adds computational overhead. Optimize for production:

1. **Enable SSL Session Caching**: Reduce handshake overhead
2. **Use TLS 1.3**: Faster handshakes, better security
3. **Hardware Acceleration**: Use CPU with AES-NI support
4. **Connection Pooling**: Reuse connections in clients

## Troubleshooting

### Common Issues

#### Issue: "Certificate keystore path configured but file is missing"

**Cause**: Certificate file not found at configured path

**Solution**:
```bash
# Check if file exists
ls -l tls/fukuiiCA.p12

# Verify configuration path is correct
grep "keystore-path" conf/base.conf

# Ensure path is relative to Fukuii working directory
pwd
```

#### Issue: "Invalid Certificate keystore"

**Cause**: Incorrect password or corrupted keystore

**Solution**:
```bash
# Verify password is correct
cat tls/password

# Try to list keystore contents
keytool -list -v -keystore tls/fukuiiCA.p12 \
  -storepass "$(cat tls/password)"

# If corrupted, regenerate certificate
cd tls/
./gen-cert.sh
```

#### Issue: "Certificate keystore invalid type set: X"

**Cause**: Incorrect keystore type specified

**Solution**:
```bash
# Verify keystore type
keytool -list -keystore tls/fukuiiCA.p12 -storepass "$(cat tls/password)"

# Should show: Keystore type: PKCS12
# Update config to match:
# keystore-type = "pkcs12"
```

#### Issue: SSL Handshake Fails

**Cause**: TLS version mismatch, cipher incompatibility, or certificate validation failure

**Solution**:
```bash
# Test TLS connection
openssl s_client -connect localhost:8546 -showcerts

# Check for specific errors:
# - "certificate verify failed": Certificate validation issue
# - "no shared cipher": Cipher mismatch
# - "protocol version": TLS version mismatch

# For self-signed certificates, clients must skip validation
curl -k https://localhost:8546/health  # -k skips verification
```

#### Issue: "Connection Refused"

**Cause**: Node not listening on configured interface/port

**Solution**:
```bash
# Check if node is running
ps aux | grep fukuii

# Check if port is listening
netstat -tulpn | grep 8546
# or
ss -tulpn | grep 8546

# Verify interface binding
# Use "0.0.0.0" to listen on all interfaces
# Use "localhost" for local-only access

# Check firewall
sudo ufw status | grep 8546
sudo iptables -L -n | grep 8546
```

#### Issue: HTTP/HTTPS Mixed Content

**Cause**: Client expecting HTTP, server using HTTPS (or vice versa)

**Solution**:
```bash
# Verify mode in logs
tail -f ~/.fukuii/etc/logs/fukuii.log | grep "listening on"

# Should show either:
# "JSON RPC HTTP server listening on ..." (HTTP mode)
# "JSON RPC HTTPS server listening on ..." (HTTPS mode)

# Update client URL scheme to match
# HTTP mode: http://localhost:8546
# HTTPS mode: https://localhost:8546
```

### Certificate Validation Errors

#### Self-Signed Certificate Issues

When using self-signed certificates, clients must explicitly trust them or skip validation:

**curl**:
```bash
# Skip validation (testing only)
curl -k https://localhost:8546

# Trust specific certificate
curl --cacert tls/fukuiiCA.p12 https://localhost:8546
```

**Python**:
```python
# Skip validation (testing only)
requests.post(url, verify=False)

# Trust specific certificate
requests.post(url, verify='/path/to/cert.pem')
```

**Node.js**:
```javascript
// Skip validation (testing only)
const options = {
  rejectUnauthorized: false
};

// Trust specific certificate
const options = {
  ca: fs.readFileSync('/path/to/cert.pem')
};
```

### Debug Mode

Enable detailed SSL/TLS debugging:

```bash
# Start Fukuii with SSL debugging
./bin/fukuii -Djavax.net.debug=ssl,handshake etc

# Or set environment variable
export JAVA_OPTS="-Djavax.net.debug=ssl"
./bin/fukuii etc
```

This will show detailed TLS handshake information in logs.

### Log Analysis

Key log patterns to watch for:

```bash
# Successful SSL initialization
grep "Loaded ssl config successful" ~/.fukuii/etc/logs/fukuii.log

# HTTPS server started
grep "JSON RPC HTTPS server listening" ~/.fukuii/etc/logs/fukuii.log

# SSL errors
grep -i "ssl.*error\|certificate.*error" ~/.fukuii/etc/logs/fukuii.log

# Connection attempts
grep "TLS handshake" ~/.fukuii/etc/logs/fukuii.log
```

## Security Best Practices

### Certificate Security

1. **Use Strong Key Sizes**: Minimum 2048-bit RSA, recommended 4096-bit
2. **Short Validity Periods**: 1-2 years maximum, prefer shorter for rotation
3. **Strong Algorithms**: Use SHA-256 or SHA-384, avoid SHA-1 and MD5
4. **Secure Storage**: Encrypt certificate backups, restrict file permissions
5. **Certificate Pinning**: Pin certificates in critical applications

### Password Management

1. **Strong Passwords**: Minimum 20 characters, random generation
2. **Secure Storage**: Store passwords in encrypted vaults (HashiCorp Vault, AWS Secrets Manager)
3. **Access Control**: Limit access to password files
4. **Rotation**: Change keystore passwords during certificate rotation
5. **Never Commit**: Add `tls/password` to `.gitignore`

### TLS Configuration

1. **Minimum TLS 1.2**: Disable TLS 1.0 and 1.1
2. **Strong Ciphers Only**: Disable weak and export ciphers
3. **Perfect Forward Secrecy**: Use ECDHE key exchange
4. **Certificate Validation**: Always validate certificates in production
5. **HSTS**: Use HTTP Strict Transport Security headers

### Network Security

1. **Firewall Rules**: Restrict TLS port access to trusted IPs
2. **VPN/Private Network**: Keep RPC on private networks when possible
3. **Rate Limiting**: Implement rate limiting at firewall or reverse proxy
4. **DDoS Protection**: Use DDoS mitigation services for public endpoints
5. **Network Segmentation**: Separate RPC network from public P2P network

### Compliance

#### Common Requirements

- **PCI-DSS**: TLS 1.2+, strong ciphers, certificate validation
- **HIPAA**: Encryption in transit, access controls, audit logging
- **SOC 2**: Certificate management, key rotation, security monitoring
- **GDPR**: Data encryption, secure key management, breach notification

#### Audit Checklist

- [ ] TLS 1.2 or higher enabled
- [ ] Weak ciphers disabled
- [ ] Certificate from trusted CA (production)
- [ ] Certificate expiration monitoring
- [ ] Key rotation policy and schedule
- [ ] Access controls on certificate files
- [ ] Encrypted certificate backups
- [ ] Security event logging enabled
- [ ] Regular security audits scheduled

## Related Documentation

- [Security Runbook](security.md) - Comprehensive node security guide
- [Node Configuration](node-configuration.md) - General configuration reference
- [Operations Runbooks](README.md) - Complete runbook index

## References

- **Fukuii Source Code**: 
  - `src/main/scala/com/chipprbots/ethereum/security/`
  - `src/main/scala/com/chipprbots/ethereum/jsonrpc/server/http/`
- **Java Keytool Documentation**: https://docs.oracle.com/en/java/javase/17/docs/specs/man/keytool.html
- **OpenSSL Documentation**: https://www.openssl.org/docs/
- **TLS Best Practices**: https://wiki.mozilla.org/Security/Server_Side_TLS

## Support

For issues or questions:
1. Check [Known Issues](known-issues.md)
2. Review [Log Triage](log-triage.md) for debugging
3. Open an issue at https://github.com/chippr-robotics/fukuii/issues
4. Contact Chippr Robotics LLC

---

**Last Updated**: 2025-11-06  
**Verified**: TLS implementation tested and confirmed functional after repository migration
