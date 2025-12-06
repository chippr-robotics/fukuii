# Configuration Wizard

<div class="wizard-container">
  <div class="wizard-header">
    <h1>⚙️ Fukuii Configuration Wizard</h1>
    <p>Enterprise-grade blockchain node & network system administration toolkit</p>
    <span class="version-badge">Version 0.1.121</span>
  </div>

  <div id="alert-container"></div>

  <!-- Tab Navigation -->
  <div class="wizard-tabs">
    <button class="wizard-tab active" data-tab="models">Models</button>
    <button class="wizard-tab" data-tab="chains">Chains</button>
    <button class="wizard-tab" data-tab="custom">Custom Tuning</button>
    <button class="wizard-tab" data-tab="upload">Upload / Download</button>
  </div>

  <!-- Models Tab -->
  <div id="models" class="wizard-content active">
    <div class="wizard-card">
      <h3>Pre-Configured Node Profiles</h3>
      <p>Choose a profile optimized for your specific use case. Each profile has been carefully tuned for security, performance, and resource efficiency.</p>
      
      <div id="profiles-container" class="profile-grid"></div>
      
      <div class="alert alert-info">
        <strong>Note:</strong> After selecting a profile, you can further customize settings in the Custom Tuning tab.
      </div>
    </div>
  </div>

  <!-- Chains Tab -->
  <div id="chains" class="wizard-content">
    <div class="wizard-card">
      <h3>Chain Configuration</h3>
      <p>Configure blockchain-specific parameters including network identity, fork activation blocks, and protocol upgrades.</p>
      
      <div class="form-group">
        <label class="form-label">Select Chain</label>
        <select id="chain-selector" class="form-select">
          <!-- Populated by JavaScript -->
        </select>
        <span class="form-label-description">Choose the blockchain network for your node</span>
      </div>
      
      <div id="chain-fields-container"></div>
      
      <div class="btn-group">
        <button class="btn btn-accent" onclick="downloadChainConfig()">Download Chain Config</button>
      </div>
    </div>
  </div>

  <!-- Custom Tuning Tab -->
  <div id="custom" class="wizard-content">
    <div class="wizard-card">
      <h3>Advanced Configuration</h3>
      <p>Fine-tune every aspect of your Fukuii node. Click on any section to expand and edit configuration options.</p>
      
      <div class="alert alert-warning">
        <strong>Warning:</strong> Advanced settings require understanding of Ethereum protocol and node operations. Incorrect values may prevent your node from functioning properly.
      </div>
      
      <div id="advanced-container"></div>
      
      <div class="btn-group">
        <button class="btn btn-primary" onclick="downloadConfig()">Download Node Config</button>
        <button class="btn btn-secondary" onclick="updatePreview()">Refresh Preview</button>
      </div>
    </div>
  </div>

  <!-- Upload/Download Tab -->
  <div id="upload" class="wizard-content">
    <div class="wizard-card">
      <h3>Import Configuration</h3>
      <p>Upload existing Fukuii configuration files to edit them in the wizard.</p>
      
      <div id="upload-area" class="upload-area">
        <div class="upload-icon">📄</div>
        <div class="upload-text">Click to upload or drag and drop</div>
        <div class="upload-hint">Supports .conf files in HOCON format</div>
        <input type="file" id="file-input" accept=".conf" style="display: none;">
      </div>
      
      <div class="alert alert-info" style="margin-top: 1.5rem;">
        <strong>Supported Files:</strong> Node configuration files (.conf) and chain configuration files. The wizard will automatically parse HOCON format and populate the appropriate fields.
      </div>
    </div>

    <div class="wizard-card">
      <h3>Export Configuration</h3>
      <p>Download your customized configuration files ready for deployment.</p>
      
      <div class="config-grid">
        <div>
          <h4>Node Configuration</h4>
          <p style="color: var(--wizard-text-secondary); font-size: 0.9rem;">
            Complete node configuration including network, RPC, sync, and mining settings. Place this file in your Fukuii installation directory.
          </p>
          <button class="btn btn-primary" onclick="downloadConfig()">Download Node Config</button>
        </div>
        
        <div>
          <h4>Chain Configuration</h4>
          <p style="color: var(--wizard-text-secondary); font-size: 0.9rem;">
            Chain-specific parameters including fork block numbers and network identity. Use with custom networks or chain modifications.
          </p>
          <button class="btn btn-accent" onclick="downloadChainConfig()">Download Chain Config</button>
        </div>
      </div>
      
      <div class="alert alert-success" style="margin-top: 1.5rem;">
        <strong>Usage:</strong> Start your node with the custom configuration using:<br>
        <code style="background: rgba(0,0,0,0.3); padding: 0.25rem 0.5rem; border-radius: 3px; margin-top: 0.5rem; display: inline-block;">
          ./bin/fukuii --config /path/to/your-config.conf
        </code>
      </div>
    </div>

    <div class="wizard-card">
      <h3>Version Information</h3>
      <p>All generated configurations are stamped with version information for compatibility tracking.</p>
      
      <div style="background: var(--wizard-bg-secondary); padding: 1rem; border-radius: 4px; border-left: 3px solid var(--wizard-antique-gold);">
        <strong>Fukuii Version:</strong> <span class="version-badge">0.1.121</span><br>
        <span style="color: var(--wizard-text-muted); font-size: 0.9rem;">
          Configuration files generated by this wizard are compatible with Fukuii v0.1.121 and may work with future versions.
        </span>
      </div>
    </div>
  </div>

  <!-- Configuration Preview -->
  <div class="preview-area">
    <div class="preview-header">
      <h4>Configuration Preview</h4>
      <button class="btn btn-secondary" onclick="downloadConfig()">Download</button>
    </div>
    <div id="preview-code" class="preview-code">
      <pre># Configuration will appear here</pre>
    </div>
  </div>
</div>

## Using the Configuration Wizard

This enterprise-grade administration toolkit provides comprehensive configuration management for Fukuii blockchain nodes. Ethereum Classic is the default public network, with support for additional networks and custom configurations.

### Quick Start

1. **Select a Profile**: Choose from pre-configured profiles optimized for different use cases
2. **Configure Chain**: Select your target blockchain and adjust fork parameters if needed
3. **Customize**: Fine-tune advanced settings in the Custom Tuning tab
4. **Download**: Export your configuration and deploy to your Fukuii node

### Profile Guide

- **Default Configuration**: Balanced settings suitable for most users running standard nodes
- **Raspberry Pi / Small System**: Optimized for resource-constrained environments with reduced memory and peer counts
- **Security Optimized**: Maximum security configuration ideal for custody operations and financial institutions
- **Mining Configuration**: Tuned for mining operations with optimized peer counts and block production
- **Archive Node**: Full historical data retention for block explorers and analytics platforms

### Deployment

After generating your configuration:

1. Download the configuration file(s)
2. Place in your Fukuii installation directory
3. Start your node with the custom configuration:

```bash
./bin/fukuii --config /path/to/your-config.conf
```

For chain-specific configurations:

```bash
./bin/fukuii --config /path/to/your-config.conf <network>
```

### Important Notes

- **Version Compatibility**: Configurations are stamped with the Fukuii version (0.1.121)
- **Security**: Never expose RPC endpoints to the public internet without proper security measures
- **Validation**: The wizard validates configuration syntax but always test in a safe environment first
- **Backup**: Keep backups of working configurations before making changes

### Reference Documentation

- [Node Configuration Runbook](/runbooks/node-configuration/) - Comprehensive configuration reference
- [Operating Modes](/runbooks/operating-modes/) - Different node operation modes
- [Security Best Practices](/runbooks/security/) - Security configuration guide
- [Performance Tuning](/runbooks/snap-sync-performance-tuning/) - Performance optimization

### Support

For additional help:

- Review the [Documentation](/)
- Check [Troubleshooting](/troubleshooting/)
- Visit the [GitHub Repository](https://github.com/chippr-robotics/fukuii)

---

**Configuration Wizard Version**: 1.0  
**Compatible with Fukuii**: 0.1.121  
**Last Updated**: 2025-12-06
