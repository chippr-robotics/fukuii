# Interactive Tools

This directory contains interactive tools and utilities for working with Fukuii.

## Contents

### Configuration Tools

- **[Configuration Wizard](configuration-wizard.md)** - Professional configuration wizard with institutional banking theme
- **[Fukuii Configurator](fukuii-configurator.html)** - Interactive web-based configuration generator

## Configuration Wizard

The Configuration Wizard is a comprehensive tool designed for institutional-grade node configuration:

**Features:**
- 🏦 **Professional Dark Theme** - Institutional banking aesthetic with deep green and gold accents
- 📊 **Pre-Configured Profiles** - Optimized configurations for Raspberry Pi, security, mining, and archive nodes
- ⛓️ **Chain Tuning** - Edit fork parameters with direct links to ECIP/EIP specifications
- ⚙️ **Advanced Settings** - Full access to all configuration options organized by category
- 📤 **Import/Export** - Upload existing configs for editing and download validated HOCON files
- 🏷️ **Version Control** - All configs stamped with Fukuii version number (0.1.121)

**Quick Start:**
1. Visit the [Configuration Wizard](configuration-wizard.md)
2. Select a pre-configured profile or start from scratch
3. Customize chain and node settings
4. Download your configuration files
5. Deploy: `./bin/fukuii --config your-config.conf`

## Using the Fukuii Configurator

The Fukuii Configurator is a web-based tool that helps you create custom node configurations:

**Features:**
- 🎯 **Visual Configuration** - Configure all node settings through an intuitive web interface
- ✅ **Automatic Validation** - Ensures all required settings are included
- 📝 **Proper Imports** - Automatically includes `include "app.conf"` in generated configs
- 💾 **Export Ready** - Download configuration files ready to use with `--config` flag
- 🚀 **Quick Setup** - Perfect for mining nodes, archive nodes, or custom configurations

**Usage:**
1. Open `fukuii-configurator.html` in your web browser
2. Configure your node settings using the tabs
3. Click "Generate Configuration"
4. Download or copy the generated config
5. Use with: `./bin/fukuii --config your-config.conf`

## Related Documentation

- [Node Configuration](../runbooks/node-configuration.md) - Manual configuration guide
- [Operating Modes](../runbooks/operating-modes.md) - Understanding different node types
- [First Start](../runbooks/first-start.md) - Initial setup guide
