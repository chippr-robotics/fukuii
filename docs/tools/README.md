# Interactive Tools

This directory contains interactive tools and utilities for working with Fukuii.

## Contents

### Configuration Tools
- **[Fukuii Configurator](fukuii-configurator.html)** - Interactive web-based configuration generator

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
