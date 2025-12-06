# Launcher Integration - Enterprise Modifier

This document describes the launcher integration changes that add enterprise modifier support for private/permissioned EVM network deployments.

## Overview

The Fukuii launcher now supports an `enterprise` modifier that automatically configures the node with industry best practices for private/permissioned blockchain networks. This complements the existing `public` modifier and provides a streamlined deployment experience for enterprise use cases.

## Valid Launch Configurations

### Basic Network Selection

```bash
fukuii                  # Launch ETC mainnet (default)
fukuii etc             # Launch ETC mainnet (explicit)
fukuii mordor          # Launch Mordor testnet
fukuii pottery         # Launch Pottery testnet
```

### Public Discovery Mode

Enable public peer discovery explicitly (useful for testnets):

```bash
fukuii public          # Launch ETC mainnet with public discovery
fukuii public etc      # Launch ETC mainnet with public discovery
fukuii public mordor   # Launch Mordor testnet with public discovery
```

### Enterprise Mode (NEW)

Configure for private/permissioned networks automatically:

```bash
fukuii enterprise               # Launch in enterprise mode (default network)
fukuii enterprise pottery       # Launch pottery in enterprise mode
fukuii enterprise etc           # Launch ETC in enterprise mode
fukuii enterprise mordor        # Launch Mordor in enterprise mode
```

## Enterprise Mode Configuration

When the `enterprise` modifier is used, the following settings are automatically applied:

### Network Settings
- **Public Discovery**: Disabled (only bootstrap nodes are used)
- **Port Forwarding**: Disabled (UPnP/NAT-PMP)
- **Known Nodes**: Reuse from configuration enabled

### Security Settings
- **RPC Interface**: Bound to localhost by default
- **Peer Blacklisting**: Disabled (for faster recovery in controlled environments)

### Rationale

These settings are designed for:
1. **Private Networks**: No connection to public Ethereum networks
2. **Controlled Environments**: All peers are known and trusted
3. **Enterprise Security**: API access restricted to localhost by default
4. **Operational Simplicity**: Simplified configuration for common enterprise deployments

## Implementation Details

### Code Changes

1. **App.scala**:
   - Added `enterprise` to `knownModifiers` set
   - Enhanced `applyModifiers()` to configure enterprise-specific settings
   - Updated help text to document enterprise mode

2. **Tests**:
   - Added unit tests in `AppSpec.scala`
   - Added integration tests in `AppLauncherIntegrationSpec.scala`
   - All tests pass successfully

### Configuration Files

1. **enterprise-template.conf**: Comprehensive configuration template for enterprise deployments
2. **enterprise-deployment.md**: Detailed deployment guide with architecture patterns and best practices

## Testing

Run the validation script:

```bash
./test-launcher-integration.sh
```

Run unit tests:

```bash
sbt "testOnly *AppSpec"
```

Run integration tests:

```bash
sbt "it:testOnly *AppLauncherIntegrationSpec"
```

## Examples

### Development Environment

```bash
# Quick development network with instant blocks
fukuii enterprise pottery -Dfukuii.mining.protocol=mocked
```

### Production Validator

```bash
# Enterprise validator node with custom config
fukuii enterprise -Dconfig.file=/opt/fukuii/validator.conf
```

### Consortium Network

```bash
# Multi-organization network with shared configuration
fukuii enterprise \
  -Dfukuii.blockchains.network=consortium \
  -Dfukuii.blockchains.custom-chains-dir=/etc/fukuii/chains
```

## Migration Guide

### From Public to Enterprise

If you have an existing private network using custom configuration:

**Before** (manual configuration):
```bash
fukuii -Dconfig.file=private-network.conf \
  -Dfukuii.network.discovery.discovery-enabled=false \
  -Dfukuii.network.automatic-port-forwarding=false
```

**After** (with enterprise modifier):
```bash
fukuii enterprise -Dconfig.file=private-network.conf
```

The enterprise modifier automatically applies the common settings, reducing configuration complexity.

## Override Behavior

Enterprise mode settings can be overridden with explicit configuration:

```hocon
# In your custom config file
fukuii {
  network {
    rpc {
      http {
        # Override enterprise default to expose RPC on network
        # Only do this on trusted networks!
        interface = "0.0.0.0"
      }
    }
  }
}
```

Then launch:
```bash
fukuii enterprise -Dconfig.file=custom.conf
```

## Documentation

- **User Guide**: `docs/runbooks/enterprise-deployment.md`
- **Configuration Template**: `src/main/resources/conf/enterprise-template.conf`
- **Custom Networks**: `docs/runbooks/custom-networks.md`
- **Security Best Practices**: `docs/runbooks/security.md`

## Benefits

### For Operators
- **Simplified Deployment**: One modifier instead of multiple configuration flags
- **Best Practices Built-in**: Industry standards applied automatically
- **Reduced Errors**: Common enterprise settings configured correctly

### For Organizations
- **Faster Time-to-Value**: Quick setup for private networks
- **Compliance**: Security-first defaults (localhost RPC, no public discovery)
- **Flexibility**: Override defaults when needed for specific requirements

## Compatibility

- **Backward Compatible**: Existing launch commands continue to work
- **Non-Breaking**: Enterprise modifier is additive, doesn't change default behavior
- **Scalable**: Supports small development networks to large consortium deployments

## Future Enhancements

Potential future additions to enterprise mode:
- TLS/HTTPS enforcement option
- Built-in metrics/monitoring enablement
- Permissioned mining configuration helpers
- Integration with enterprise key management systems

## Support

For issues or questions:
- GitHub Issues: https://github.com/chippr-robotics/fukuii/issues
- Documentation: https://github.com/chippr-robotics/fukuii/tree/main/docs

---

**Version**: 1.0  
**Date**: 2025-12-06  
**Author**: Chippr Robotics LLC
