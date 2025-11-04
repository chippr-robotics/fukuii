# Fukuii Operations Runbooks

This directory contains operational runbooks for running and maintaining Fukuii Ethereum Classic nodes in production environments.

## Table of Contents

### Getting Started
- **[First Start](first-start.md)** - Initial node setup, configuration, and first-time startup procedures
- **[Node Configuration](node-configuration.md)** - Chain configs, node configs, and command line options
- **[Security](security.md)** - Node security, firewall configuration, and security best practices

### Operations
- **[Peering](peering.md)** - Peer discovery, network connectivity, and peering troubleshooting
- **[Disk Management](disk-management.md)** - Data directory management, pruning strategies, and disk space monitoring
- **[Backup & Restore](backup-restore.md)** - Backup strategies, data recovery, and disaster recovery procedures
- **[Log Triage](log-triage.md)** - Logging configuration, log analysis, and troubleshooting from logs

### Reference
- **[Known Issues](known-issues.md)** - Common issues with RocksDB, temporary directories, JVM flags, and their solutions

## Quick Reference

### Essential Commands
```bash
# Start node (after extracting distribution)
./bin/fukuii etc

# Generate a new private key
./bin/fukuii cli generate-private-key

# Check node status via RPC
curl -X POST --data '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":1}' http://localhost:8546

# View logs
tail -f ~/.fukuii/etc/logs/fukuii.log
```

### Essential Directories
- **Data Directory**: `~/.fukuii/<network>/` - Blockchain data and node configuration
- **Keystore**: `~/.fukuii/<network>/keystore/` - Encrypted private keys
- **Logs**: `~/.fukuii/<network>/logs/` - Application logs
- **Database**: `~/.fukuii/<network>/rocksdb/` - RocksDB blockchain database

### Essential Ports
- **9076** - Ethereum protocol (P2P)
- **30303** - Discovery protocol (UDP)
- **8546** - JSON-RPC HTTP API
- **8545** - Alternative JSON-RPC port (configurable)

## Support

For additional support:
- Review the main [README.md](../../README.md)
- Check the [Architecture Overview](../architecture-overview.md)
- Visit the [GitHub Issues](https://github.com/chippr-robotics/fukuii/issues) page
- Review the [Contributing Guide](../../CONTRIBUTING.md)

## Document Status

These runbooks are living documents. If you encounter issues not covered here or find errors, please:
1. Open an issue in the repository
2. Submit a pull request with corrections or improvements
3. Contact the maintainers at Chippr Robotics LLC

**Last Updated**: 2025-11-04
