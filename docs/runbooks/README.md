# Fukuii Operations Runbooks

This directory contains operational runbooks for running and maintaining Fukuii Ethereum Classic nodes in production environments.

## Table of Contents

### Getting Started
- **[First Start](first-start.md)** - Initial node setup, configuration, and first-time startup procedures
- **[Operating Modes](operating-modes.md)** - Comprehensive guide to full nodes, archive nodes, boot nodes, and mining nodes
- **[Node Configuration](node-configuration.md)** - Chain configs, node configs, and command line options
- **[Custom Networks](custom-networks.md)** - Deploy Fukuii on private or custom Ethereum networks
- **[Enterprise Deployment](enterprise-deployment.md)** - Deploy private/permissioned EVM networks for enterprise use cases
- **[Configuration Tool](../tools/fukuii-configurator.html)** - Interactive web-based configuration generator (open in browser)
- **[Security](security.md)** - Node security, firewall configuration, and security best practices
- **[TLS Operations](tls-operations.md)** - TLS/HTTPS configuration for secure JSON-RPC connections
- **[Checkpoint Service](checkpoint-service.md)** - Running and using the checkpoint update service for production

### Operations
- **[Peering](peering.md)** - Peer discovery, network connectivity, and peering troubleshooting
- **[Disk Management](disk-management.md)** - Data directory management, pruning strategies, and disk space monitoring
- **[Backup & Restore](backup-restore.md)** - Backup strategies, data recovery, and disaster recovery procedures
- **[Log Triage](log-triage.md)** - Logging configuration, log analysis, and troubleshooting from logs

### SNAP Sync
- **[SNAP Sync User Guide](snap-sync-user-guide.md)** - How to enable, configure, and monitor SNAP sync
- **[SNAP Sync Performance Tuning](snap-sync-performance-tuning.md)** - Advanced optimization and tuning strategies
- **[SNAP Sync FAQ](snap-sync-faq.md)** - Frequently asked questions about SNAP sync

### API Operations (Barad-dûr)
- **[Barad-dûr Operations](barad-dur-operations.md)** - Kong API Gateway stack operations, monitoring, and maintenance

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
- **8545** - JSON-RPC HTTP API
- **8546** - Alternative JSON-RPC port (WebSocket, configurable)

## Configuration Tool

An interactive web-based configuration generator is available to help create custom node configurations:

**[Open Fukuii Configurator](../tools/fukuii-configurator.html)**

Features:
- 🎯 **Visual Configuration** - Configure all node settings through an intuitive web interface
- ✅ **Automatic Validation** - Ensures all required settings are included
- 📝 **Proper Imports** - Automatically includes `include "app.conf"` in generated configs
- 💾 **Export Ready** - Download configuration files ready to use with `--config` flag
- 🚀 **Quick Setup** - Perfect for mining nodes, archive nodes, or custom configurations

Usage:
1. Open `docs/tools/fukuii-configurator.html` in your web browser
2. Configure your node settings using the tabs
3. Click "Generate Configuration"
4. Download or copy the generated config
5. Use with: `./bin/fukuii --config your-config.conf`

## Support

For additional support:
- Review the [Documentation Home](../index.md)
- Check the [Architecture Overview](../architecture/architecture-overview.md)
- Visit the [GitHub Issues](https://github.com/chippr-robotics/fukuii/issues) page
- Review the [Contributing Guide](../development/contributing.md)

## Document Status

These runbooks are living documents. If you encounter issues not covered here or find errors, please:
1. Open an issue in the repository
2. Submit a pull request with corrections or improvements
3. Contact the maintainers at Chippr Robotics LLC

**Last Updated**: 2025-12-03
