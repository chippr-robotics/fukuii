# Backup & Restore Runbook

**Audience**: Operators managing data protection and disaster recovery  
**Estimated Time**: 1-3 hours (depending on data size)  
**Prerequisites**: Running Fukuii node, sufficient backup storage

## Overview

This runbook covers backup strategies, restoration procedures, and disaster recovery planning for Fukuii nodes. Proper backups are essential for protecting against data loss from hardware failures, corruption, or operational errors.

## Table of Contents

1. [Backup Strategies](#backup-strategies)
2. [What to Backup](#what-to-backup)
3. [Backup Procedures](#backup-procedures)
4. [Restore Procedures](#restore-procedures)
5. [Disaster Recovery](#disaster-recovery)
6. [Testing and Validation](#testing-and-validation)

## Backup Strategies

### Strategy Comparison

**Legend:**
- RTO = Recovery Time Objective (how long to restore)
- RPO = Recovery Point Objective (how much data loss)

| Strategy | RTO | RPO | Storage Cost | Complexity | Use Case |
|----------|-----|-----|--------------|------------|----------|
| Full Backup | Hours | 24h | High | Low | Development |
| Incremental | 1-2h | 1h | Medium | Medium | Production |
| Snapshot | Minutes | Minutes | Medium | Medium | Cloud/VM |
| Live Replication | Seconds | Seconds | High | High | Critical |
| Hybrid | 30m-1h | 30m | Medium-High | Medium | Recommended |

### Recommended Strategy

For most production deployments, use a **hybrid approach**:

1. **Critical data** (keys, config): Frequent backups (hourly) to multiple locations
2. **Blockchain database**: Periodic backups (daily/weekly) + on-demand before major changes
3. **Known nodes**: Daily backups
4. **Logs**: Optional (can be retained but not critical for recovery)

## What to Backup

### Essential Files (MUST backup)

These are small but critical:

```bash
~/.fukuii/etc/
├── node.key                    # ~100 bytes - CRITICAL
├── keystore/                   # ~1 KB per key - CRITICAL
│   └── UTC--2024...
├── app-state.json              # ~1 KB - Important
└── knownNodes.json             # ~50 KB - Helpful
```

**Priority**: **HIGHEST** - These files are small and cannot be recreated.

### Database (Optional but recommended)

```bash
~/.fukuii/etc/rocksdb/          # 300-400 GB - Large but valuable
├── blockchain/
└── state/
```

**Priority**: **MEDIUM** - Can be re-synced from network (takes days) but backup saves time.

### Configuration Files

```bash
/path/to/fukuii/conf/
├── custom.conf                 # Your custom configuration
└── .jvmopts                    # JVM tuning parameters
```

**Priority**: **HIGH** - Small files that define your node's behavior.

### Logs (Usually not needed)

```bash
~/.fukuii/etc/logs/             # ~500 MB - Rotated automatically
```

**Priority**: **LOW** - Useful for debugging but not needed for recovery.

### Backup Size Estimates

| Component | Size | Backup Frequency | Storage (1 month) |
|-----------|------|------------------|-------------------|
| Keys + Config | ~1 MB | Daily | ~30 MB |
| Known Nodes | ~50 KB | Daily | ~1.5 MB |
| Database | ~350 GB | Weekly | ~1.4 TB |
| **Total** | **~350 GB** | Mixed | **~1.4 TB** |

## Backup Procedures

### Method 1: Essential Files Only (Recommended for All)

Backs up critical files that cannot be recreated.

**Frequency**: Daily (or after any key generation)  
**Duration**: < 1 minute  
**Storage**: < 10 MB

```bash
#!/bin/bash
# backup-essentials.sh

DATADIR=~/.fukuii/etc
BACKUP_DIR=/backup/fukuii/essentials
DATE=$(date +%Y%m%d-%H%M%S)
BACKUP_PATH="$BACKUP_DIR/fukuii-essentials-$DATE"

mkdir -p "$BACKUP_PATH"

# Backup critical files
cp "$DATADIR/node.key" "$BACKUP_PATH/" 2>/dev/null || echo "No node.key"
cp -r "$DATADIR/keystore" "$BACKUP_PATH/" 2>/dev/null || echo "No keystore"
cp "$DATADIR/app-state.json" "$BACKUP_PATH/" 2>/dev/null || echo "No app-state"
cp "$DATADIR/knownNodes.json" "$BACKUP_PATH/" 2>/dev/null || echo "No knownNodes"

# Create archive
cd "$BACKUP_DIR"
tar -czf "fukuii-essentials-$DATE.tar.gz" "fukuii-essentials-$DATE/"
rm -rf "fukuii-essentials-$DATE/"

# Keep only last 30 backups
ls -t fukuii-essentials-*.tar.gz | tail -n +31 | xargs rm -f

echo "Backup completed: fukuii-essentials-$DATE.tar.gz"
```

**Schedule with cron**:
```bash
# Daily at 3 AM
0 3 * * * /path/to/backup-essentials.sh
```

### Method 2: Full Database Backup (Offline)

Complete backup including blockchain database.

**Frequency**: Weekly or before major upgrades  
**Duration**: 30-60 minutes (depending on disk speed)  
**Storage**: ~350 GB per backup

**Important**: Stop the node first for consistent backup.

```bash
#!/bin/bash
# backup-full-offline.sh

DATADIR=~/.fukuii/etc
BACKUP_DIR=/backup/fukuii/full
DATE=$(date +%Y%m%d-%H%M%S)
BACKUP_PATH="$BACKUP_DIR/fukuii-full-$DATE"

# Stop Fukuii
echo "Stopping Fukuii..."
# For systemd:
# sudo systemctl stop fukuii
# For Docker:
# docker stop fukuii
# For screen/tmux: send stop command or kill process
pkill -f fukuii || echo "Fukuii not running"

sleep 10  # Wait for clean shutdown

# Create backup
echo "Creating backup..."
mkdir -p "$BACKUP_DIR"
rsync -avh --progress "$DATADIR/" "$BACKUP_PATH/"

# Create compressed archive (optional, saves space but takes longer)
# tar -czf "$BACKUP_DIR/fukuii-full-$DATE.tar.gz" -C "$BACKUP_DIR" "fukuii-full-$DATE"
# rm -rf "$BACKUP_PATH"

# Restart Fukuii
echo "Restarting Fukuii..."
# ./bin/fukuii etc &
# Or restore your startup method

echo "Backup completed: $BACKUP_PATH"
```

### Method 3: Live Database Backup (Online)

Backup while node is running using RocksDB checkpoint feature.

**Note**: This requires RocksDB checkpoint API support in Fukuii. Check if available.

```bash
#!/bin/bash
# backup-live.sh

DATADIR=~/.fukuii/etc
BACKUP_DIR=/backup/fukuii/live
DATE=$(date +%Y%m%d-%H%M%S)
BACKUP_PATH="$BACKUP_DIR/fukuii-checkpoint-$DATE"

# Create RocksDB checkpoint (if supported)
# This would require exposing checkpoint functionality via CLI or RPC
# Example (hypothetical):
# ./bin/fukuii cli create-checkpoint --output "$BACKUP_PATH"

# Alternative: Use filesystem snapshots (LVM, ZFS, Btrfs)
# LVM example:
# sudo lvcreate -L 10G -s -n fukuii-snap /dev/vg0/fukuii-lv
# sudo mount /dev/vg0/fukuii-snap /mnt/snapshot
# rsync -avh /mnt/snapshot/ "$BACKUP_PATH/"
# sudo umount /mnt/snapshot
# sudo lvremove -f /dev/vg0/fukuii-snap

echo "Live backup requires snapshot support - see disk-management.md"
```

### Method 4: Incremental Backup

Backup only changes since last backup.

```bash
#!/bin/bash
# backup-incremental.sh

DATADIR=~/.fukuii/etc
BACKUP_DIR=/backup/fukuii/incremental
DATE=$(date +%Y%m%d-%H%M%S)
LINK_DEST="$BACKUP_DIR/latest"

mkdir -p "$BACKUP_DIR"

# Use rsync with hard links to save space
rsync -avh --delete \
  --link-dest="$LINK_DEST" \
  "$DATADIR/" \
  "$BACKUP_DIR/backup-$DATE/"

# Update latest link
rm -f "$LINK_DEST"
ln -s "$BACKUP_DIR/backup-$DATE" "$LINK_DEST"

echo "Incremental backup completed: backup-$DATE"
```

### Method 5: Cloud Backup

Upload to cloud storage (S3, Google Cloud Storage, Azure Blob, etc.)

```bash
#!/bin/bash
# backup-to-s3.sh

DATADIR=~/.fukuii/etc
S3_BUCKET=s3://my-fukuii-backups
DATE=$(date +%Y%m%d-%H%M%S)

# Backup essentials to S3
aws s3 sync "$DATADIR/keystore/" "$S3_BUCKET/keystore-$DATE/" --exclude "*"
aws s3 cp "$DATADIR/node.key" "$S3_BUCKET/node.key-$DATE"
aws s3 cp "$DATADIR/app-state.json" "$S3_BUCKET/app-state-$DATE.json"

# Optionally backup database (expensive and slow)
# aws s3 sync "$DATADIR/rocksdb/" "$S3_BUCKET/rocksdb-$DATE/"

echo "Cloud backup completed"
```

**Configure AWS CLI first**:
```bash
aws configure
```

### Encrypting Backups

For sensitive data (especially keys):

```bash
#!/bin/bash
# backup-encrypted.sh

DATADIR=~/.fukuii/etc
BACKUP_DIR=/backup/fukuii/encrypted
DATE=$(date +%Y%m%d-%H%M%S)

# Create archive
tar -czf - "$DATADIR/keystore" "$DATADIR/node.key" | \
  gpg --symmetric --cipher-algo AES256 \
  -o "$BACKUP_DIR/fukuii-keys-$DATE.tar.gz.gpg"

echo "Encrypted backup created"
echo "Decrypt with: gpg -d fukuii-keys-$DATE.tar.gz.gpg | tar -xzf -"
```

## Restore Procedures

### Restore Essential Files

**Scenario**: Fresh installation, need to restore node identity and accounts.

```bash
#!/bin/bash
# restore-essentials.sh

BACKUP_FILE=/backup/fukuii/essentials/fukuii-essentials-20250102-030000.tar.gz
DATADIR=~/.fukuii/etc

# Stop node if running
pkill -f fukuii

# Extract backup
mkdir -p "$DATADIR"
tar -xzf "$BACKUP_FILE" -C /tmp/

# Restore files
cp /tmp/fukuii-essentials-*/node.key "$DATADIR/"
cp -r /tmp/fukuii-essentials-*/keystore "$DATADIR/"
cp /tmp/fukuii-essentials-*/app-state.json "$DATADIR/" 2>/dev/null
cp /tmp/fukuii-essentials-*/knownNodes.json "$DATADIR/" 2>/dev/null

# Set permissions
chmod 600 "$DATADIR/node.key"
chmod 700 "$DATADIR/keystore"

# Cleanup
rm -rf /tmp/fukuii-essentials-*

echo "Essential files restored"
echo "Database will sync from network on next start"
```

### Restore Full Database

**Scenario**: Hardware failure, need complete restoration.

```bash
#!/bin/bash
# restore-full.sh

BACKUP_PATH=/backup/fukuii/full/fukuii-full-20250101-030000
DATADIR=~/.fukuii/etc

# Stop node
pkill -f fukuii

# Remove existing data (be careful!)
read -p "This will delete $DATADIR. Continue? (yes/no) " confirm
if [ "$confirm" != "yes" ]; then
    echo "Aborted"
    exit 1
fi

rm -rf "$DATADIR"

# Restore from backup
mkdir -p "$(dirname $DATADIR)"
rsync -avh --progress "$BACKUP_PATH/" "$DATADIR/"

# Verify critical files
if [ ! -f "$DATADIR/node.key" ]; then
    echo "ERROR: node.key not found in backup!"
    exit 1
fi

echo "Full restoration completed"
echo "Start Fukuii normally: ./bin/fukuii etc"
```

### Restore from Cloud

```bash
#!/bin/bash
# restore-from-s3.sh

S3_BUCKET=s3://my-fukuii-backups
DATADIR=~/.fukuii/etc
DATE=20250102-030000

mkdir -p "$DATADIR"

# Restore from S3
aws s3 sync "$S3_BUCKET/keystore-$DATE/" "$DATADIR/keystore/"
aws s3 cp "$S3_BUCKET/node.key-$DATE" "$DATADIR/node.key"
aws s3 cp "$S3_BUCKET/app-state-$DATE.json" "$DATADIR/app-state.json"

chmod 600 "$DATADIR/node.key"
chmod 700 "$DATADIR/keystore"

echo "Restored from cloud backup"
```

### Restore from Encrypted Backup

```bash
#!/bin/bash
# restore-encrypted.sh

BACKUP_FILE=/backup/fukuii/encrypted/fukuii-keys-20250102-030000.tar.gz.gpg
DATADIR=~/.fukuii/etc

# Decrypt and extract
gpg -d "$BACKUP_FILE" | tar -xzf - -C "$DATADIR/"

chmod 600 "$DATADIR/node.key"
chmod 700 "$DATADIR/keystore"

echo "Decrypted and restored"
```

### Selective Restore

**Scenario**: Only restore specific components.

```bash
# Restore only node.key
tar -xzf fukuii-essentials-DATE.tar.gz \
  --strip-components=1 \
  -C ~/.fukuii/etc/ \
  fukuii-essentials-DATE/node.key

# Restore only keystore
tar -xzf fukuii-essentials-DATE.tar.gz \
  --strip-components=1 \
  -C ~/.fukuii/etc/ \
  fukuii-essentials-DATE/keystore/
```

## Disaster Recovery

### Scenario 1: Corrupted Database

**Symptoms**: Node won't start, RocksDB errors

**Recovery Steps**:

1. **Try automatic repair** (see [disk-management.md](disk-management.md))
   ```bash
   # Restart - RocksDB may auto-repair
   ./bin/fukuii etc
   ```

2. **If repair fails, restore from backup**
   ```bash
   ./restore-full.sh
   ```

3. **If no backup, resync from genesis**
   ```bash
   # Backup keys first
   cp ~/.fukuii/etc/node.key ~/node.key.backup
   cp -r ~/.fukuii/etc/keystore ~/keystore.backup
   
   # Remove database only
   rm -rf ~/.fukuii/etc/rocksdb/
   
   # Restore keys
   cp ~/node.key.backup ~/.fukuii/etc/node.key
   cp -r ~/keystore.backup ~/.fukuii/etc/keystore/
   
   # Resync (will take days)
   ./bin/fukuii etc
   ```

### Scenario 2: Lost Node Key

**Symptoms**: node.key file deleted or lost

**Recovery**:

If you have a backup:
```bash
tar -xzf fukuii-essentials-DATE.tar.gz fukuii-essentials-DATE/node.key
cp fukuii-essentials-DATE/node.key ~/.fukuii/etc/
chmod 600 ~/.fukuii/etc/node.key
```

If NO backup:
- Node will generate a new key on next start
- You will have a new node identity
- Known peers will not recognize your node
- **Impact**: Minimal - node will still work, just with new identity

### Scenario 3: Lost Keystore

**Symptoms**: Keystore directory deleted or lost

**Recovery**:

If you have a backup:
```bash
tar -xzf fukuii-essentials-DATE.tar.gz fukuii-essentials-DATE/keystore/
cp -r fukuii-essentials-DATE/keystore ~/.fukuii/etc/
chmod 700 ~/.fukuii/etc/keystore
```

If NO backup:
- **CRITICAL**: Private keys are permanently lost
- Accounts are inaccessible
- Funds cannot be recovered
- **Prevention**: ALWAYS backup keystore after creating accounts

### Scenario 4: Hardware Failure

**Complete server/disk failure**

**Recovery Steps**:

1. **Provision new hardware**
2. **Install Fukuii** (see [first-start.md](first-start.md))
3. **Restore from backup**
   ```bash
   ./restore-full.sh
   ```
4. **Verify restoration**
   ```bash
   ./bin/fukuii etc
   # Check logs, RPC, peer count
   ```
5. **Resume operations**

**Time estimate**: 1-3 hours (if database backup exists), 1-7 days (if resync needed)

### Scenario 5: Accidental Data Deletion

**Recovery**:

1. **Stop immediately** to prevent more writes
2. **Attempt file recovery** (if just deleted)
   ```bash
   # Linux - may recover recently deleted files
   sudo extundelete /dev/sdX --restore-directory /home/user/.fukuii
   ```
3. **Restore from backup**
4. **Implement safeguards**:
   ```bash
   # Make critical files immutable
   sudo chattr +i ~/.fukuii/etc/node.key
   ```

## Testing and Validation

### Regular Backup Testing

**Test restores regularly** - A backup you can't restore is useless.

```bash
#!/bin/bash
# test-restore.sh

BACKUP_FILE=/backup/fukuii/essentials/fukuii-essentials-latest.tar.gz
TEST_DIR=/tmp/fukuii-restore-test

# Extract to test directory
mkdir -p "$TEST_DIR"
tar -xzf "$BACKUP_FILE" -C "$TEST_DIR"

# Verify critical files exist
if [ ! -f "$TEST_DIR"/fukuii-essentials-*/node.key ]; then
    echo "FAIL: node.key missing"
    exit 1
fi

if [ ! -d "$TEST_DIR"/fukuii-essentials-*/keystore ]; then
    echo "FAIL: keystore missing"
    exit 1
fi

echo "PASS: Backup is valid"
rm -rf "$TEST_DIR"
```

**Schedule monthly**:
```bash
0 4 1 * * /path/to/test-restore.sh && mail -s "Backup Test: PASS" admin@example.com
```

### Verification Checklist

After any restore:

- [ ] Node starts successfully
- [ ] Node key matches backup
- [ ] Keystore accounts match backup
- [ ] Peers connect normally
- [ ] Synchronization progresses
- [ ] RPC queries work
- [ ] No errors in logs

## Best Practices

### For All Deployments

1. **3-2-1 Rule**: 3 copies, 2 different media, 1 offsite
2. **Backup keys immediately** after generation
3. **Test restores regularly** (monthly)
4. **Automate backups** (cron jobs)
5. **Monitor backup success** (alerting)
6. **Document procedures** (this runbook)
7. **Encrypt sensitive backups** (keys, keystore)

### For Production Nodes

1. **Multiple backup locations** (local + cloud)
2. **Frequent essentials backups** (hourly)
3. **Weekly database backups**
4. **Versioned backups** (keep multiple generations)
5. **Offsite replication** (different datacenter)
6. **Automated testing** (restore to test environment)
7. **Disaster recovery plan** (documented, tested)
8. **RTO/RPO targets** (defined and measured)

### For Personal Nodes

1. **Daily essentials backup** (minimum)
2. **Manual database backup** before upgrades
3. **Cloud backup for keys** (encrypted)
4. **Document restore procedure**

### Security Considerations

1. **Encrypt backups** containing private keys
2. **Restrict backup access** (file permissions)
3. **Secure backup storage** (encrypted at rest)
4. **Secure transfer** (SSH, TLS)
5. **Key management** (store encryption keys separately)
6. **Audit backup access** (log who accessed backups)

## Backup Automation Example

Complete automated backup solution:

```bash
#!/bin/bash
# /usr/local/bin/fukuii-backup-automation.sh

DATADIR=~/.fukuii/etc
BACKUP_BASE=/backup/fukuii
LOG_FILE=/var/log/fukuii-backup.log

log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') - $1" | tee -a "$LOG_FILE"
}

# Daily essentials backup
daily_essentials() {
    log "Starting daily essentials backup"
    /usr/local/bin/backup-essentials.sh >> "$LOG_FILE" 2>&1
    
    # Upload to cloud
    aws s3 sync "$BACKUP_BASE/essentials/" s3://my-backups/fukuii/essentials/
    
    log "Daily backup completed"
}

# Weekly full backup (Sunday)
weekly_full() {
    log "Starting weekly full backup"
    /usr/local/bin/backup-full-offline.sh >> "$LOG_FILE" 2>&1
    log "Weekly backup completed"
}

# Monthly test restore
monthly_test() {
    log "Starting monthly restore test"
    /usr/local/bin/test-restore.sh >> "$LOG_FILE" 2>&1
    
    if [ $? -eq 0 ]; then
        log "Restore test: PASSED"
    else
        log "Restore test: FAILED - ALERT"
        mail -s "ALERT: Fukuii Backup Test Failed" admin@example.com < "$LOG_FILE"
    fi
}

# Run appropriate backup based on day
DAY=$(date +%u)  # 1-7 (Monday-Sunday)
if [ "$DAY" -eq 7 ]; then
    weekly_full
fi

daily_essentials

# First day of month
if [ "$(date +%d)" -eq "01" ]; then
    monthly_test
fi
```

**Cron schedule**:
```cron
# Daily at 3 AM
0 3 * * * /usr/local/bin/fukuii-backup-automation.sh
```

## Related Runbooks

- [First Start](first-start.md) - Initial setup and configuration
- [Disk Management](disk-management.md) - Storage and database management
- [Known Issues](known-issues.md) - Database corruption and recovery

---

**Document Version**: 1.0  
**Last Updated**: 2025-11-02  
**Maintainer**: Chippr Robotics LLC
