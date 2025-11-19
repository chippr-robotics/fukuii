# Node Security Runbook

**Audience**: Operators securing production Fukuii nodes  
**Estimated Time**: 1-2 hours for initial setup  
**Prerequisites**: Running Fukuii node, basic Linux security knowledge

## Overview

This runbook covers security best practices for running Fukuii nodes in production. Proper security is critical to protect your node, network, and any assets managed by the node from unauthorized access and attacks.

## Table of Contents

1. [Security Principles](#security-principles)
2. [Network Security](#network-security)
3. [Firewall Configuration](#firewall-configuration)
4. [Access Control](#access-control)
5. [RPC Security](#rpc-security)
6. [System Hardening](#system-hardening)
7. [Key Management](#key-management)
8. [Monitoring and Auditing](#monitoring-and-auditing)
9. [Security Checklist](#security-checklist)

## Security Principles

### Defense in Depth

Implement multiple layers of security:
1. **Network layer**: Firewall rules, port restrictions
2. **System layer**: OS hardening, access controls
3. **Application layer**: RPC authentication, rate limiting
4. **Data layer**: Encryption, secure key storage
5. **Monitoring layer**: Logging, alerting, intrusion detection

### Principle of Least Privilege

- Grant minimum necessary permissions
- Restrict network exposure
- Limit RPC access to trusted sources
- Use dedicated user accounts with minimal privileges

### Security by Default

- Start with most restrictive configuration
- Only open what's necessary
- Disable unused features
- Regular security audits

## Network Security

### Port Strategy

Fukuii uses three main ports:

| Port | Protocol | Purpose | Exposure |
|------|----------|---------|----------|
| 30303 | UDP | Discovery | Public (required for peer discovery) |
| 9076 | TCP | P2P Ethereum | Public (required for full participation) |
| 8546 | TCP | JSON-RPC HTTP | **PRIVATE** (internal only) |

**Critical**: Never expose RPC ports (8546, 8545) to the public internet.

### Network Architecture

**Recommended setup for production:**

```
Internet
    │
    ├─── Port 30303 (UDP) ──→ Fukuii Discovery
    ├─── Port 9076 (TCP) ──→ Fukuii P2P
    │
Internal Network
    │
    └─── Port 8546 (TCP) ──→ RPC (internal apps only)
```

**For API services:**

```
Internet
    │
    └─── HTTPS (443) ──→ Reverse Proxy (nginx/caddy)
                            │ Authentication
                            │ Rate Limiting
                            │ TLS Termination
                            └──→ Fukuii RPC (localhost:8546)
```

### Network Isolation

**Separate networks for different functions:**

1. **Public-facing**: Discovery and P2P only
2. **Management**: SSH access from specific IPs
3. **Application**: RPC access from trusted services
4. **Monitoring**: Metrics collection (Prometheus)

**Using VLANs or cloud security groups:**
```bash
# AWS Security Group example
# Public subnet: Discovery + P2P
Inbound: 30303/UDP from 0.0.0.0/0
Inbound: 9076/TCP from 0.0.0.0/0

# Private subnet: RPC
Inbound: 8546/TCP from 10.0.0.0/16 (internal only)
Inbound: 22/TCP from YOUR_IP/32 (SSH)
```

## Firewall Configuration

### Using UFW (Ubuntu/Debian)

**Basic setup:**

```bash
# Reset to defaults (careful on remote systems!)
# sudo ufw --force reset

# Default policies: deny incoming, allow outgoing
sudo ufw default deny incoming
sudo ufw default allow outgoing

# Allow SSH (CRITICAL - do this first on remote systems!)
sudo ufw allow from YOUR_IP_ADDRESS to any port 22 proto tcp
# Or if using key-based auth from anywhere:
# sudo ufw limit 22/tcp  # Rate limit SSH

# Allow Fukuii discovery (required for peer discovery)
sudo ufw allow 30303/udp comment 'Fukuii discovery'

# Allow Fukuii P2P (required for full node operation)
sudo ufw allow 9076/tcp comment 'Fukuii P2P'

# DO NOT allow RPC from internet
# sudo ufw deny 8546/tcp comment 'Fukuii RPC blocked'

# Allow RPC only from specific internal IPs (if needed)
sudo ufw allow from 10.0.1.5 to any port 8546 proto tcp comment 'App server RPC'
sudo ufw allow from 10.0.1.6 to any port 8546 proto tcp comment 'Backup RPC'

# Enable firewall
sudo ufw enable

# Verify rules
sudo ufw status numbered
```

**Expected output:**
```
Status: active

     To                         Action      From
     --                         ------      ----
[ 1] 22/tcp                     ALLOW IN    YOUR_IP_ADDRESS
[ 2] 30303/udp                  ALLOW IN    Anywhere
[ 3] 9076/tcp                   ALLOW IN    Anywhere
[ 4] 8546/tcp                   ALLOW IN    10.0.1.5
[ 5] 8546/tcp                   ALLOW IN    10.0.1.6
```

### Using firewalld (RHEL/CentOS/Fedora)

**Basic setup:**

```bash
# Check status
sudo firewall-cmd --state

# Set default zone
sudo firewall-cmd --set-default-zone=public

# Allow SSH (if not already allowed)
sudo firewall-cmd --permanent --add-service=ssh

# Allow Fukuii ports
sudo firewall-cmd --permanent --add-port=30303/udp
sudo firewall-cmd --permanent --add-port=9076/tcp

# Restrict RPC to specific source IPs
sudo firewall-cmd --permanent --add-rich-rule='
  rule family="ipv4"
  source address="10.0.1.5/32"
  port protocol="tcp" port="8546" accept'

sudo firewall-cmd --permanent --add-rich-rule='
  rule family="ipv4"
  source address="10.0.1.6/32"
  port protocol="tcp" port="8546" accept'

# Reload firewall
sudo firewall-cmd --reload

# Verify
sudo firewall-cmd --list-all
```

### Using iptables (Advanced)

**Basic setup:**

```bash
#!/bin/bash
# fukuii-firewall.sh

# Flush existing rules
iptables -F
iptables -X
iptables -t nat -F
iptables -t nat -X
iptables -t mangle -F
iptables -t mangle -X

# Default policies
iptables -P INPUT DROP
iptables -P FORWARD DROP
iptables -P OUTPUT ACCEPT

# Allow loopback
iptables -A INPUT -i lo -j ACCEPT
iptables -A OUTPUT -o lo -j ACCEPT

# Allow established connections
iptables -A INPUT -m state --state ESTABLISHED,RELATED -j ACCEPT

# Allow SSH from specific IP
iptables -A INPUT -p tcp --dport 22 -s YOUR_IP_ADDRESS -j ACCEPT

# Allow Fukuii discovery (UDP)
iptables -A INPUT -p udp --dport 30303 -j ACCEPT

# Allow Fukuii P2P (TCP)
iptables -A INPUT -p tcp --dport 9076 -j ACCEPT

# Allow RPC only from internal network
iptables -A INPUT -p tcp --dport 8546 -s 10.0.0.0/16 -j ACCEPT

# Log dropped packets (optional, for debugging)
# iptables -A INPUT -j LOG --log-prefix "IPTables-Dropped: "

# Save rules
iptables-save > /etc/iptables/rules.v4
```

### Docker Firewall Configuration

When running Fukuii in Docker, configure firewall on the host:

```bash
# Docker bypasses UFW by default
# Use Docker's built-in port publishing controls

# SECURE: Only expose discovery and P2P
docker run -d \
  --name fukuii \
  -p 30303:30303/udp \
  -p 9076:9076/tcp \
  ghcr.io/chippr-robotics/chordodes_fukuii:v1.0.0

# INSECURE: Do NOT do this
# -p 8546:8546  # Exposes RPC to public internet!

# For internal RPC access, use Docker networks
docker network create fukuii-internal
docker run -d --network fukuii-internal --name fukuii ...
docker run -d --network fukuii-internal --name app ...
# App can access Fukuii RPC via http://fukuii:8546
```

**Docker with host firewall integration:**

```bash
# Configure UFW before Docker starts
# Edit /etc/default/ufw
# DEFAULT_FORWARD_POLICY="DROP"

# Or use iptables to restrict Docker
iptables -I DOCKER-USER -i eth0 -p tcp --dport 8546 -j DROP
iptables -I DOCKER-USER -i eth0 -s 10.0.1.0/24 -p tcp --dport 8546 -j ACCEPT
```

### Cloud Provider Firewalls

**AWS Security Groups:**
```
# Public node group
Inbound:
  - Type: Custom UDP, Port: 30303, Source: 0.0.0.0/0
  - Type: Custom TCP, Port: 9076, Source: 0.0.0.0/0
  - Type: SSH, Port: 22, Source: YOUR_IP/32

Outbound:
  - All traffic
```

**Google Cloud Firewall Rules:**
```bash
# Allow discovery
gcloud compute firewall-rules create fukuii-discovery \
  --allow udp:30303 \
  --source-ranges 0.0.0.0/0 \
  --target-tags fukuii-node

# Allow P2P
gcloud compute firewall-rules create fukuii-p2p \
  --allow tcp:9076 \
  --source-ranges 0.0.0.0/0 \
  --target-tags fukuii-node
```

**Azure Network Security Groups:**
```
# Similar to AWS Security Groups
# Configure via Azure Portal or CLI
```

## Access Control

### SSH Hardening

**Disable password authentication** (use keys only):

Edit `/etc/ssh/sshd_config`:
```
# Disable password authentication
PasswordAuthentication no
PubkeyAuthentication yes

# Disable root login
PermitRootLogin no

# Use protocol 2 only
Protocol 2

# Limit users
AllowUsers fukuii_user admin_user

# Change default port (optional, security through obscurity)
# Port 2222
```

Restart SSH:
```bash
sudo systemctl restart sshd
```

**Use SSH keys:**
```bash
# Generate key pair (on your local machine)
ssh-keygen -t ed25519 -C "fukuii-admin"

# Copy to server
ssh-copy-id -i ~/.ssh/id_ed25519.pub user@fukuii-server

# Test login
ssh -i ~/.ssh/id_ed25519 user@fukuii-server
```

**Fail2Ban** (prevent brute force):
```bash
# Install
sudo apt-get install fail2ban

# Configure
sudo cp /etc/fail2ban/jail.conf /etc/fail2ban/jail.local

# Edit /etc/fail2ban/jail.local
[sshd]
enabled = true
maxretry = 3
bantime = 3600

# Start
sudo systemctl enable fail2ban
sudo systemctl start fail2ban
```

### User Management

**Run Fukuii as dedicated user** (not root):

```bash
# Create dedicated user
sudo useradd -r -m -s /bin/bash fukuii

# Set up directories
sudo mkdir -p /data/fukuii
sudo chown fukuii:fukuii /data/fukuii

# Set permissions
sudo chmod 700 /data/fukuii

# Run as fukuii user
sudo -u fukuii /path/to/fukuii/bin/fukuii etc
```

**Systemd service with user isolation:**

Create `/etc/systemd/system/fukuii.service`:
```ini
[Unit]
Description=Fukuii Ethereum Classic Node
After=network.target

[Service]
Type=simple
User=fukuii
Group=fukuii
WorkingDirectory=/home/fukuii
ExecStart=/opt/fukuii/bin/fukuii etc

# Security hardening
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=full
ProtectHome=true
ReadWritePaths=/data/fukuii

Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Enable and start:
```bash
sudo systemctl daemon-reload
sudo systemctl enable fukuii
sudo systemctl start fukuii
```

### File Permissions

**Secure sensitive files:**

```bash
# Node key
chmod 600 ~/.fukuii/etc/node.key
chown fukuii:fukuii ~/.fukuii/etc/node.key

# Keystore
chmod 700 ~/.fukuii/etc/keystore
chown -R fukuii:fukuii ~/.fukuii/etc/keystore

# Configuration files
chmod 640 ~/.fukuii/etc/*.conf
chown fukuii:fukuii ~/.fukuii/etc/*.conf

# Make node.key immutable (optional, prevents accidental deletion)
sudo chattr +i ~/.fukuii/etc/node.key
# To remove: sudo chattr -i ~/.fukuii/etc/node.key
```

## RPC Security

### Never Expose RPC Publicly

**DO NOT DO THIS:**
```bash
# INSECURE - Allows anyone to access your node
-p 8546:8546  # Docker
ufw allow 8546/tcp  # Firewall
```

**Why it's dangerous:**
- Attackers can drain accounts if keystore is unlocked
- DoS attacks via expensive RPC calls
- Information disclosure (balances, transactions)
- Potential for exploitation of RPC vulnerabilities

### RPC Access Patterns

**Pattern 1: Localhost only** (most secure)

```hocon
# Fukuii config
fukuii.network.rpc.http {
  mode = "http"
  interface = "127.0.0.1"  # Localhost only
  port = 8546
}
```

Access via SSH tunnel:
```bash
# From your local machine
ssh -L 8546:localhost:8546 user@fukuii-server

# Now access RPC on your local machine
curl http://localhost:8546
```

**Pattern 2: Internal network with IP whitelist**

```hocon
fukuii.network.rpc.http {
  interface = "0.0.0.0"  # Listen on all interfaces
  port = 8546
}
```

Restrict with firewall (see above) to specific IPs only.

**Pattern 3: Reverse proxy with authentication** (for external access)

Use nginx or Caddy as reverse proxy:

**Note**: For direct TLS/HTTPS configuration on Fukuii (without reverse proxy), see the [TLS Operations](tls-operations.md) runbook for detailed instructions on certificate generation, configuration, and testing.

**Nginx example:**
```nginx
# /etc/nginx/sites-available/fukuii-rpc
upstream fukuii_rpc {
    server 127.0.0.1:8546;
}

server {
    listen 443 ssl http2;
    server_name rpc.example.com;

    # TLS certificates
    ssl_certificate /etc/letsencrypt/live/rpc.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/rpc.example.com/privkey.pem;

    # Basic authentication
    auth_basic "Restricted Access";
    auth_basic_user_file /etc/nginx/.htpasswd;

    # Rate limiting
    limit_req_zone $binary_remote_addr zone=rpc_limit:10m rate=10r/s;
    limit_req zone=rpc_limit burst=20 nodelay;

    # API key validation (alternative to basic auth)
    # if ($http_x_api_key != "YOUR_SECRET_KEY") {
    #     return 403;
    # }

    location / {
        proxy_pass http://fukuii_rpc;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        
        # Security headers
        add_header X-Content-Type-Options nosniff;
        add_header X-Frame-Options DENY;
        add_header X-XSS-Protection "1; mode=block";
    }

    # Disable admin methods
    location ~ /(admin_|personal_|debug_) {
        return 403;
    }
}
```

Create password file:
```bash
sudo apt-get install apache2-utils
sudo htpasswd -c /etc/nginx/.htpasswd rpcuser
```

**Caddy example** (simpler):
```
rpc.example.com {
    basicauth {
        rpcuser $2a$14$hashed_password_here
    }
    
    reverse_proxy localhost:8546 {
        # Rate limiting
        header_up X-Real-IP {remote_host}
    }
}
```

**Alternative: Direct HTTPS on Fukuii**

Instead of using a reverse proxy, you can enable TLS/HTTPS directly on Fukuii:

```hocon
fukuii.network.rpc.http {
  mode = "https"
  interface = "0.0.0.0"
  port = 8546
  
  certificate {
    keystore-path = "tls/fukuiiCA.p12"
    keystore-type = "pkcs12"
    password-file = "tls/password"
  }
}
```

For complete TLS setup instructions including certificate generation, testing, and production considerations, see the **[TLS Operations Runbook](tls-operations.md)**.

### RPC Method Filtering

**Disable dangerous methods:**

If Fukuii supports method filtering, restrict to read-only methods:

```hocon
# Hypothetical configuration
fukuii.network.rpc {
  allowed-methods = [
    "eth_*",
    "net_*",
    "web3_*"
  ]
  
  blocked-methods = [
    "personal_*",  # Account management
    "admin_*",     # Node administration
    "debug_*",     # Debugging
    "miner_*"      # Mining control
  ]
}
```

Implement at reverse proxy level:
```nginx
# Block dangerous RPC methods in nginx
location / {
    if ($request_body ~* "personal_|admin_|debug_|miner_") {
        return 403;
    }
    proxy_pass http://fukuii_rpc;
}
```

### Rate Limiting

Prevent DoS attacks on RPC:

**Nginx rate limiting:**
```nginx
# Limit to 10 requests per second per IP
limit_req_zone $binary_remote_addr zone=rpc_limit:10m rate=10r/s;

server {
    limit_req zone=rpc_limit burst=20 nodelay;
    # ... rest of config
}
```

**Application-level** (if supported by Fukuii):
```hocon
fukuii.network.rpc {
  rate-limit {
    enabled = true
    requests-per-second = 10
    burst = 20
  }
}
```

## System Hardening

### Operating System Updates

Keep system up-to-date:

```bash
# Ubuntu/Debian
sudo apt-get update
sudo apt-get upgrade
sudo apt-get dist-upgrade

# Enable unattended security updates
sudo apt-get install unattended-upgrades
sudo dpkg-reconfigure -plow unattended-upgrades

# RHEL/CentOS
sudo yum update
```

### Disable Unnecessary Services

```bash
# List running services
systemctl list-units --type=service --state=running

# Disable unused services
sudo systemctl disable bluetooth
sudo systemctl stop bluetooth
```

### AppArmor/SELinux

**Ubuntu (AppArmor):**
```bash
# Check status
sudo aa-status

# Create profile for Fukuii (advanced)
# See: https://gitlab.com/apparmor/apparmor/-/wikis/Documentation
```

**RHEL/CentOS (SELinux):**
```bash
# Check status
getenforce

# Ensure enforcing mode
sudo setenforce 1

# Make persistent in /etc/selinux/config
SELINUX=enforcing
```

### Kernel Hardening

Edit `/etc/sysctl.conf`:

```bash
# IP Forwarding (disable if not needed)
net.ipv4.ip_forward = 0

# Protect against SYN flood attacks
net.ipv4.tcp_syncookies = 1
net.ipv4.tcp_max_syn_backlog = 2048
net.ipv4.tcp_synack_retries = 2

# Disable ICMP redirect acceptance
net.ipv4.conf.all.accept_redirects = 0
net.ipv4.conf.all.send_redirects = 0

# Disable IP source routing
net.ipv4.conf.all.accept_source_route = 0

# Log suspicious packets
net.ipv4.conf.all.log_martians = 1

# Ignore ICMP ping requests (optional)
# net.ipv4.icmp_echo_ignore_all = 1
```

Apply:
```bash
sudo sysctl -p
```

### Intrusion Detection

**Install AIDE (file integrity monitoring):**
```bash
sudo apt-get install aide

# Initialize database
sudo aideinit

# Check for changes
sudo aide --check
```

**Install rkhunter (rootkit detection):**
```bash
sudo apt-get install rkhunter

# Update database
sudo rkhunter --update

# Scan system
sudo rkhunter --check
```

## Key Management

### Private Key Security

**Node key** (`node.key`):
- Generated automatically on first start
- Used for peer authentication
- **Low sensitivity** (losing it just changes node identity)
- Backup recommended but not critical

**Account keys** (keystore):
- Control funds
- **HIGHEST sensitivity**
- Must be backed up securely
- Should be encrypted at rest

### Key Storage Best Practices

**1. Use encrypted keystore** (default in Fukuii)

Keystores are encrypted with passphrase. Use strong passphrases:
```bash
# Generate random passphrase
openssl rand -base64 32
```

**2. Separate keys from node** (optional, for high-value accounts)

Don't store account keys on the node server. Instead:
- Sign transactions offline (cold wallet)
- Use hardware wallet (Ledger, Trezor)
- Use multisig contracts

**3. Encrypt data at rest**

Use full disk encryption:

**LUKS (Linux Unified Key Setup):**
```bash
# Encrypt partition (during setup)
cryptsetup luksFormat /dev/sdb1
cryptsetup luksOpen /dev/sdb1 fukuii_data
mkfs.ext4 /dev/mapper/fukuii_data
```

**Cloud provider encryption:**
- AWS: EBS volume encryption
- GCP: Customer-managed encryption keys
- Azure: Disk encryption

**4. Hardware Security Modules (HSM)** (enterprise)

For high-value deployments:
- AWS CloudHSM
- Google Cloud HSM
- YubiHSM
- Thales HSM

### Key Backup

See [backup-restore.md](backup-restore.md) for detailed procedures.

**Key points:**
- Encrypt backups: `gpg --symmetric`
- Multiple locations: Local + cloud + offline
- Test restoration regularly
- Document recovery procedures

## Monitoring and Auditing

### Log Security Events

**Enable audit logging:**

Install auditd:
```bash
sudo apt-get install auditd

# Monitor critical files
sudo auditctl -w /home/fukuii/.fukuii/etc/keystore/ -p wa -k keystore_access
sudo auditctl -w /etc/ssh/sshd_config -p wa -k sshd_config_change

# View logs
sudo ausearch -k keystore_access
```

**Monitor authentication:**
```bash
# Failed login attempts
sudo grep "Failed password" /var/log/auth.log

# Successful logins
sudo grep "Accepted publickey" /var/log/auth.log

# sudo usage
sudo grep "sudo:" /var/log/auth.log
```

### Monitor Network Activity

**Monitor connections:**
```bash
# Active connections to Fukuii
sudo netstat -antp | grep -E "9076|30303|8546"

# Detect unauthorized RPC access
sudo tcpdump -i eth0 port 8546 -n
```

**Detect port scans:**
```bash
# Install portsentry
sudo apt-get install portsentry

# Configure in /etc/portsentry/portsentry.conf
```

### Security Monitoring Tools

**Install Lynis (security auditing):**
```bash
sudo apt-get install lynis

# Run audit
sudo lynis audit system
```

**Install OSSEC (intrusion detection):**
```bash
# See: https://www.ossec.net/
# Monitors logs, files, and system calls
```

### Alerting

Set up alerts for:
- Failed login attempts
- Unauthorized file access
- Unusual network activity
- Service failures
- Disk space issues
- Configuration changes

**Example: Email alerts on failed SSH login**

Create `/etc/security/failed_login_alert.sh`:
```bash
#!/bin/bash
FAILED=$(grep "Failed password" /var/log/auth.log | tail -5)
if [ ! -z "$FAILED" ]; then
    echo "Failed SSH login attempts:" | mail -s "Security Alert" admin@example.com
fi
```

Schedule with cron:
```cron
*/15 * * * * /etc/security/failed_login_alert.sh
```

### Regular Security Audits

**Monthly checklist:**
- [ ] Review authentication logs
- [ ] Check for system updates
- [ ] Verify firewall rules
- [ ] Test backup restoration
- [ ] Review user accounts
- [ ] Check for unusual processes
- [ ] Verify file integrity (AIDE)
- [ ] Scan for rootkits (rkhunter)
- [ ] Review network connections

**Quarterly:**
- [ ] Full security audit (Lynis)
- [ ] Penetration testing
- [ ] Update documentation
- [ ] Review incident response plan

## Security Checklist

### Pre-Deployment

- [ ] Operating system hardened and updated
- [ ] Firewall configured (allow only 30303/UDP and 9076/TCP)
- [ ] RPC not exposed to public internet
- [ ] SSH hardened (key-based auth, no root login)
- [ ] Dedicated user account created for Fukuii
- [ ] Fail2Ban configured
- [ ] Disk encryption enabled
- [ ] Security monitoring tools installed

### Post-Deployment

- [ ] Node key backed up securely
- [ ] Keystore backed up and encrypted
- [ ] Firewall rules verified
- [ ] RPC access tested (should be blocked from internet)
- [ ] Monitoring and alerting configured
- [ ] Logs reviewed for security events
- [ ] Documentation updated

### Ongoing Maintenance

- [ ] Weekly: Review logs for anomalies
- [ ] Monthly: Security audit and updates
- [ ] Quarterly: Full penetration test
- [ ] Annually: Disaster recovery drill

## Incident Response

### If Compromised

**Immediate actions:**

1. **Isolate the node**
   ```bash
   # Block all traffic
   sudo ufw deny out
   # Or disconnect network
   sudo ip link set eth0 down
   ```

2. **Secure accounts**
   ```bash
   # Transfer funds to secure wallet immediately
   # Change all passwords
   # Rotate SSH keys
   ```

3. **Preserve evidence**
   ```bash
   # Copy logs
   sudo cp -r /var/log /backup/incident-$(date +%Y%m%d)
   # Take disk snapshot
   sudo dd if=/dev/sda of=/backup/disk-image.dd
   ```

4. **Investigate**
   ```bash
   # Check for unauthorized access
   sudo last
   sudo lastlog
   
   # Check running processes
   ps auxf
   
   # Check for backdoors
   sudo netstat -antp
   sudo find / -name "*.sh" -mtime -7
   ```

5. **Rebuild**
   - Reinstall from scratch
   - Restore from clean backup
   - Update all credentials

### Contact Information

Document emergency contacts:
- Security team
- Infrastructure team
- Cloud provider support
- Cryptocurrency security experts

## Related Runbooks

- [First Start](first-start.md) - Initial secure setup
- [Peering](peering.md) - Network security considerations
- [Backup & Restore](backup-restore.md) - Secure backup procedures
- [Known Issues](known-issues.md) - Security-related issues

## Further Reading

- [OWASP Top 10](https://owasp.org/www-project-top-ten/)
- [CIS Benchmarks](https://www.cisecurity.org/cis-benchmarks/)
- [Linux Security Hardening Guide](https://www.cisecurity.org/benchmark/ubuntu_linux)
- [Ethereum Node Security](https://ethereum.org/en/developers/docs/nodes-and-clients/run-a-node/#security)

---

**Document Version**: 1.0  
**Last Updated**: 2025-11-02  
**Maintainer**: Chippr Robotics LLC
