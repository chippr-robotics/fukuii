/**
 * Fukuii Configuration Wizard
 * Institutional-grade blockchain node configuration tool
 */

// Version from version.sbt
const FUKUII_VERSION = '0.1.121';

// Configuration State
const configState = {
  selectedProfile: null,
  selectedChain: 'etc',
  customConfig: {},
  chainConfig: {}
};

// Pre-configured Profiles
const profiles = {
  default: {
    name: 'Default Configuration',
    description: 'Balanced configuration suitable for most users',
    specs: {
      'Memory Usage': 'Moderate (2-4 GB)',
      'Peer Connections': 'Standard (20-50 peers)',
      'Sync Speed': 'Balanced',
      'Use Case': 'General purpose node'
    },
    config: {
      'fukuii.network.peer.min-outgoing-peers': 20,
      'fukuii.network.peer.max-outgoing-peers': 50,
      'fukuii.network.peer.max-incoming-peers': 30,
      'fukuii.db.rocksdb.block-cache-size': 33554432, // 32 MB
      'fukuii.sync.max-concurrent-requests': 10,
      'fukuii.network.rpc.http.interface': 'localhost',
      'fukuii.network.rpc.http.enabled': true,
      'fukuii.mining.mining-enabled': false
    }
  },
  
  raspberrypi: {
    name: 'Raspberry Pi / Small System',
    description: 'Optimized for low-resource environments',
    specs: {
      'Memory Usage': 'Low (1-2 GB)',
      'Peer Connections': 'Minimal (5-15 peers)',
      'Sync Speed': 'Slower but stable',
      'Use Case': 'Raspberry Pi, VPS, light clients'
    },
    config: {
      'fukuii.network.peer.min-outgoing-peers': 5,
      'fukuii.network.peer.max-outgoing-peers': 15,
      'fukuii.network.peer.max-incoming-peers': 10,
      'fukuii.db.rocksdb.block-cache-size': 16777216, // 16 MB
      'fukuii.sync.max-concurrent-requests': 5,
      'fukuii.sync.block-headers-per-request': 64,
      'fukuii.sync.block-bodies-per-request': 64,
      'fukuii.network.rpc.http.interface': 'localhost',
      'fukuii.network.rpc.http.enabled': true,
      'fukuii.mining.mining-enabled': false
    }
  },
  
  security: {
    name: 'Security Optimized',
    description: 'Maximum security configuration for sensitive operations',
    specs: {
      'Memory Usage': 'Moderate (2-4 GB)',
      'Peer Connections': 'Controlled (10-25 peers)',
      'Security Level': 'Maximum',
      'Use Case': 'Custody, financial operations'
    },
    config: {
      'fukuii.network.peer.min-outgoing-peers': 10,
      'fukuii.network.peer.max-outgoing-peers': 25,
      'fukuii.network.peer.max-incoming-peers': 15,
      'fukuii.network.rpc.http.interface': 'localhost',
      'fukuii.network.rpc.http.enabled': true,
      'fukuii.network.rpc.http.mode': 'https',
      'fukuii.network.rpc.http.rate-limit.enabled': true,
      'fukuii.network.rpc.http.rate-limit.min-request-interval': '10.seconds',
      'fukuii.keyStore.minimal-passphrase-length': 12,
      'fukuii.keyStore.allow-no-passphrase': false,
      'fukuii.mining.mining-enabled': false,
      'fukuii.network.automatic-port-forwarding': false
    }
  },
  
  miner: {
    name: 'Mining Configuration',
    description: 'Optimized for mining operations',
    specs: {
      'Memory Usage': 'High (4-8 GB)',
      'Peer Connections': 'Optimized (30-75 peers)',
      'Mining': 'Enabled',
      'Use Case': 'Mining pools, solo mining'
    },
    config: {
      'fukuii.network.peer.min-outgoing-peers': 30,
      'fukuii.network.peer.max-outgoing-peers': 75,
      'fukuii.network.peer.max-incoming-peers': 40,
      'fukuii.db.rocksdb.block-cache-size': 67108864, // 64 MB
      'fukuii.sync.max-concurrent-requests': 15,
      'fukuii.mining.mining-enabled': true,
      'fukuii.mining.num-threads': 4,
      'fukuii.mining.header-extra-data': 'fukuii-miner',
      'fukuii.network.rpc.http.interface': 'localhost',
      'fukuii.network.rpc.http.enabled': true
    }
  },
  
  archive: {
    name: 'Archive Node',
    description: 'Full archive node with maximum retention',
    specs: {
      'Memory Usage': 'Very High (8-16 GB)',
      'Peer Connections': 'Maximum (50-100 peers)',
      'Disk Usage': 'Full chain history',
      'Use Case': 'Block explorers, analytics'
    },
    config: {
      'fukuii.network.peer.min-outgoing-peers': 50,
      'fukuii.network.peer.max-outgoing-peers': 100,
      'fukuii.network.peer.max-incoming-peers': 50,
      'fukuii.db.rocksdb.block-cache-size': 134217728, // 128 MB
      'fukuii.sync.max-concurrent-requests': 20,
      'fukuii.sync.block-headers-per-request': 256,
      'fukuii.sync.block-bodies-per-request': 256,
      'fukuii.network.rpc.http.interface': 'localhost',
      'fukuii.network.rpc.http.enabled': true,
      'fukuii.mining.mining-enabled': false
    }
  }
};

// Chain Configurations
const chainConfigs = {
  etc: {
    name: 'Ethereum Classic Mainnet',
    networkId: 1,
    chainId: '0x3d',
    description: 'Ethereum Classic mainnet with ECIP support',
    forks: {
      'homestead-block-number': { value: '1150000', eip: 'EIP-2', url: 'https://github.com/ethereum/EIPs/blob/master/EIPS/eip-2.md' },
      'eip150-block-number': { value: '2500000', eip: 'EIP-150', url: 'https://github.com/ethereum/EIPs/issues/150' },
      'eip155-block-number': { value: '3000000', eip: 'EIP-155', url: 'https://github.com/ethereum/eips/issues/155' },
      'eip160-block-number': { value: '3000000', eip: 'EIP-160', url: 'https://github.com/ethereum/EIPs/issues/160' },
      'atlantis-block-number': { value: '8772000', eip: 'ECIP-1054', url: 'https://ecips.ethereumclassic.org/ECIPs/ecip-1054' },
      'agharta-block-number': { value: '9573000', eip: 'ECIP-1056', url: 'https://ecips.ethereumclassic.org/ECIPs/ecip-1056' },
      'phoenix-block-number': { value: '10500839', eip: 'ECIP-1088', url: 'https://ecips.ethereumclassic.org/ECIPs/ecip-1088' },
      'magneto-block-number': { value: '13189133', eip: 'ECIP-1103', url: 'https://ecips.ethereumclassic.org/ECIPs/ecip-1103' },
      'mystique-block-number': { value: '14525000', eip: 'ECIP-1104', url: 'https://ecips.ethereumclassic.org/ECIPs/ecip-1104' },
      'spiral-block-number': { value: '19250000', eip: 'ECIP-1109', url: 'https://ecips.ethereumclassic.org/ECIPs/ecip-1109' }
    }
  },
  
  mordor: {
    name: 'Mordor Testnet',
    networkId: 7,
    chainId: '0x3f',
    description: 'Ethereum Classic testnet for development and testing',
    forks: {
      'homestead-block-number': { value: '0', eip: 'EIP-2', url: 'https://github.com/ethereum/EIPs/blob/master/EIPS/eip-2.md' },
      'eip150-block-number': { value: '0', eip: 'EIP-150', url: 'https://github.com/ethereum/EIPs/issues/150' },
      'eip155-block-number': { value: '0', eip: 'EIP-155', url: 'https://github.com/ethereum/eips/issues/155' },
      'eip160-block-number': { value: '0', eip: 'EIP-160', url: 'https://github.com/ethereum/EIPs/issues/160' },
      'atlantis-block-number': { value: '0', eip: 'ECIP-1054', url: 'https://ecips.ethereumclassic.org/ECIPs/ecip-1054' },
      'agharta-block-number': { value: '0', eip: 'ECIP-1056', url: 'https://ecips.ethereumclassic.org/ECIPs/ecip-1056' },
      'phoenix-block-number': { value: '0', eip: 'ECIP-1088', url: 'https://ecips.ethereumclassic.org/ECIPs/ecip-1088' },
      'magneto-block-number': { value: '0', eip: 'ECIP-1103', url: 'https://ecips.ethereumclassic.org/ECIPs/ecip-1103' },
      'mystique-block-number': { value: '301243', eip: 'ECIP-1104', url: 'https://ecips.ethereumclassic.org/ECIPs/ecip-1104' },
      'spiral-block-number': { value: '778507', eip: 'ECIP-1109', url: 'https://ecips.ethereumclassic.org/ECIPs/ecip-1109' }
    }
  },
  
  eth: {
    name: 'Ethereum Mainnet',
    networkId: 1,
    chainId: '0x01',
    description: 'Ethereum mainnet (historical support)',
    forks: {
      'homestead-block-number': { value: '1150000', eip: 'EIP-2', url: 'https://github.com/ethereum/EIPs/blob/master/EIPS/eip-2.md' },
      'eip150-block-number': { value: '2463000', eip: 'EIP-150', url: 'https://github.com/ethereum/EIPs/issues/150' },
      'eip155-block-number': { value: '2675000', eip: 'EIP-155', url: 'https://github.com/ethereum/eips/issues/155' },
      'eip160-block-number': { value: '2675000', eip: 'EIP-160', url: 'https://github.com/ethereum/EIPs/issues/160' },
      'byzantium-block-number': { value: '4370000', eip: 'EIP-609', url: 'https://github.com/ethereum/EIPs/blob/master/EIPS/eip-609.md' },
      'constantinople-block-number': { value: '7280000', eip: 'EIP-1013', url: 'https://github.com/ethereum/pm/issues/53' },
      'petersburg-block-number': { value: '7280000', eip: 'EIP-1716', url: 'https://github.com/ethereum/EIPs/blob/master/EIPS/eip-1716.md' },
      'istanbul-block-number': { value: '9069000', eip: 'EIP-1679', url: 'https://eips.ethereum.org/EIPS/eip-1679' }
    }
  }
};

// Advanced Configuration Schema
const advancedConfigSections = {
  network: {
    title: 'Network Configuration',
    description: 'P2P networking, peer management, and discovery settings',
    fields: {
      'fukuii.network.server-address.interface': { label: 'P2P Interface', type: 'text', default: '0.0.0.0', description: 'Network interface for P2P connections' },
      'fukuii.network.server-address.port': { label: 'P2P Port', type: 'number', default: 9076, description: 'Port for Ethereum P2P protocol' },
      'fukuii.network.discovery.discovery-enabled': { label: 'Enable Discovery', type: 'boolean', default: true, description: 'Enable peer discovery protocol' },
      'fukuii.network.discovery.port': { label: 'Discovery Port', type: 'number', default: 30303, description: 'UDP port for peer discovery' },
      'fukuii.network.automatic-port-forwarding': { label: 'UPnP Port Forwarding', type: 'boolean', default: true, description: 'Automatically configure router port forwarding' }
    }
  },
  
  peers: {
    title: 'Peer Management',
    description: 'Configure peer connection limits and behavior',
    fields: {
      'fukuii.network.peer.min-outgoing-peers': { label: 'Min Outgoing Peers', type: 'number', default: 20, description: 'Minimum outbound peer connections to maintain' },
      'fukuii.network.peer.max-outgoing-peers': { label: 'Max Outgoing Peers', type: 'number', default: 50, description: 'Maximum outbound peer connections' },
      'fukuii.network.peer.max-incoming-peers': { label: 'Max Incoming Peers', type: 'number', default: 30, description: 'Maximum inbound peer connections' },
      'fukuii.network.peer.connect-retry-delay': { label: 'Retry Delay', type: 'text', default: '5.seconds', description: 'Delay between connection retry attempts' },
      'fukuii.network.peer.wait-for-hello-timeout': { label: 'Hello Timeout', type: 'text', default: '3.seconds', description: 'Timeout waiting for peer hello message' }
    }
  },
  
  rpc: {
    title: 'JSON-RPC Configuration',
    description: 'HTTP and IPC RPC endpoint settings',
    fields: {
      'fukuii.network.rpc.http.enabled': { label: 'Enable HTTP RPC', type: 'boolean', default: true, description: 'Enable JSON-RPC over HTTP' },
      'fukuii.network.rpc.http.interface': { label: 'RPC Interface', type: 'text', default: 'localhost', description: 'Interface to bind RPC server (localhost for security)' },
      'fukuii.network.rpc.http.port': { label: 'RPC Port', type: 'number', default: 8546, description: 'HTTP JSON-RPC port' },
      'fukuii.network.rpc.http.mode': { label: 'RPC Mode', type: 'select', options: ['http', 'https'], default: 'http', description: 'HTTP or HTTPS mode' },
      'fukuii.network.rpc.apis': { label: 'Enabled APIs', type: 'text', default: 'eth,web3,net,personal,fukuii,debug,qa,checkpointing', description: 'Comma-separated list of enabled RPC APIs' }
    }
  },
  
  sync: {
    title: 'Blockchain Sync',
    description: 'Synchronization and fast sync settings',
    fields: {
      'fukuii.sync.do-fast-sync': { label: 'Enable Fast Sync', type: 'boolean', default: true, description: 'Use fast sync for initial blockchain download' },
      'fukuii.sync.max-concurrent-requests': { label: 'Concurrent Requests', type: 'number', default: 10, description: 'Maximum parallel block requests' },
      'fukuii.sync.block-headers-per-request': { label: 'Headers Per Request', type: 'number', default: 128, description: 'Block headers to request at once' },
      'fukuii.sync.block-bodies-per-request': { label: 'Bodies Per Request', type: 'number', default: 128, description: 'Block bodies to request at once' },
      'fukuii.sync.pivot-block-offset': { label: 'Pivot Block Offset', type: 'number', default: 500, description: 'Offset from chain head for fast sync pivot' }
    }
  },
  
  database: {
    title: 'Database Configuration',
    description: 'RocksDB storage settings',
    fields: {
      'fukuii.db.data-source': { label: 'Data Source', type: 'select', options: ['rocksdb'], default: 'rocksdb', description: 'Database backend' },
      'fukuii.db.rocksdb.block-cache-size': { label: 'Block Cache Size', type: 'number', default: 33554432, description: 'RocksDB block cache size in bytes (32 MB default)' },
      'fukuii.db.rocksdb.create-if-missing': { label: 'Create If Missing', type: 'boolean', default: true, description: 'Create database if it doesn\'t exist' },
      'fukuii.db.rocksdb.paranoid-checks': { label: 'Paranoid Checks', type: 'boolean', default: true, description: 'Enable extra database validation' }
    }
  },
  
  mining: {
    title: 'Mining Configuration',
    description: 'Mining and block production settings',
    fields: {
      'fukuii.mining.mining-enabled': { label: 'Enable Mining', type: 'boolean', default: false, description: 'Enable mining on this node' },
      'fukuii.mining.coinbase': { label: 'Coinbase Address', type: 'text', default: '0011223344556677889900112233445566778899', description: 'Address to receive mining rewards (40 hex chars)' },
      'fukuii.mining.num-threads': { label: 'Mining Threads', type: 'number', default: 1, description: 'Number of parallel mining threads' },
      'fukuii.mining.header-extra-data': { label: 'Extra Data', type: 'text', default: 'fukuii', description: 'Extra data to include in mined blocks' },
      'fukuii.mining.protocol': { label: 'Mining Protocol', type: 'select', options: ['pow', 'mocked', 'restricted-pow'], default: 'pow', description: 'Proof of Work protocol variant' }
    }
  },
  
  keystore: {
    title: 'Keystore & Security',
    description: 'Key management and security settings',
    fields: {
      'fukuii.keyStore.keystore-dir': { label: 'Keystore Directory', type: 'text', default: '${fukuii.datadir}/keystore', description: 'Directory for encrypted private keys' },
      'fukuii.keyStore.minimal-passphrase-length': { label: 'Min Passphrase Length', type: 'number', default: 7, description: 'Minimum passphrase length for key encryption' },
      'fukuii.keyStore.allow-no-passphrase': { label: 'Allow No Passphrase', type: 'boolean', default: true, description: 'Allow unencrypted keystores (not recommended)' }
    }
  }
};

// Initialize wizard
document.addEventListener('DOMContentLoaded', () => {
  initializeTabs();
  initializeProfiles();
  initializeChains();
  initializeAdvanced();
  initializeUpload();
  updatePreview();
});

// Tab Management
function initializeTabs() {
  const tabs = document.querySelectorAll('.wizard-tab');
  tabs.forEach(tab => {
    tab.addEventListener('click', () => {
      const targetId = tab.dataset.tab;
      switchTab(targetId);
    });
  });
}

function switchTab(tabId) {
  // Update tab buttons
  document.querySelectorAll('.wizard-tab').forEach(tab => {
    tab.classList.toggle('active', tab.dataset.tab === tabId);
  });
  
  // Update content
  document.querySelectorAll('.wizard-content').forEach(content => {
    content.classList.toggle('active', content.id === tabId);
  });
}

// Profile Management
function initializeProfiles() {
  const container = document.getElementById('profiles-container');
  
  Object.keys(profiles).forEach(profileId => {
    const profile = profiles[profileId];
    const card = createProfileCard(profileId, profile);
    container.appendChild(card);
  });
}

function createProfileCard(id, profile) {
  const card = document.createElement('div');
  card.className = 'profile-card';
  if (configState.selectedProfile === id) {
    card.classList.add('selected');
  }
  
  card.innerHTML = `
    <h4>${profile.name}</h4>
    <p>${profile.description}</p>
    <ul class="profile-specs">
      ${Object.entries(profile.specs).map(([key, value]) => 
        `<li><strong>${key}:</strong> ${value}</li>`
      ).join('')}
    </ul>
  `;
  
  card.addEventListener('click', () => selectProfile(id));
  
  return card;
}

function selectProfile(profileId) {
  configState.selectedProfile = profileId;
  configState.customConfig = { ...profiles[profileId].config };
  
  // Update UI - re-render all cards to update selection state
  const container = document.getElementById('profiles-container');
  container.innerHTML = '';
  Object.keys(profiles).forEach(id => {
    const profile = profiles[id];
    const card = createProfileCard(id, profile);
    container.appendChild(card);
  });
  
  updatePreview();
}

// Chain Configuration
function initializeChains() {
  const selector = document.getElementById('chain-selector');
  const container = document.getElementById('chain-fields-container');
  
  // Populate chain selector
  Object.keys(chainConfigs).forEach(chainId => {
    const option = document.createElement('option');
    option.value = chainId;
    option.textContent = chainConfigs[chainId].name;
    selector.appendChild(option);
  });
  
  selector.value = configState.selectedChain;
  selector.addEventListener('change', (e) => {
    configState.selectedChain = e.target.value;
    renderChainFields();
    updatePreview();
  });
  
  renderChainFields();
}

function renderChainFields() {
  const chain = chainConfigs[configState.selectedChain];
  const container = document.getElementById('chain-fields-container');
  
  container.innerHTML = `
    <div class="alert alert-info">
      <strong>${chain.name}</strong><br>
      ${chain.description}
    </div>
    
    <div class="config-grid">
      <div class="form-group">
        <label class="form-label">Network ID</label>
        <input type="number" class="form-input" value="${chain.networkId}" 
               onchange="updateChainConfig('network-id', this.value)">
        <span class="form-label-description">Network identifier for peer handshaking</span>
      </div>
      
      <div class="form-group">
        <label class="form-label">Chain ID</label>
        <input type="text" class="form-input" value="${chain.chainId}" 
               onchange="updateChainConfig('chain-id', this.value)">
        <span class="form-label-description">Chain ID for transaction signing (EIP-155)</span>
      </div>
    </div>
    
    <h4>Fork Block Numbers</h4>
    <div class="config-grid">
      ${Object.entries(chain.forks).map(([forkName, forkData]) => `
        <div class="form-group">
          <label class="form-label">${formatForkName(forkName)}</label>
          <input type="text" class="form-input" value="${forkData.value}" 
                 onchange="updateChainConfig('${forkName}', this.value)">
          <a href="${forkData.url}" target="_blank" class="reference-link">${forkData.eip}</a>
          <span class="form-label-description">Block number where ${forkData.eip} activates</span>
        </div>
      `).join('')}
    </div>
  `;
}

function formatForkName(name) {
  return name.split('-').map(word => 
    word.charAt(0).toUpperCase() + word.slice(1)
  ).join(' ');
}

function updateChainConfig(key, value) {
  if (!configState.chainConfig) {
    configState.chainConfig = {};
  }
  configState.chainConfig[key] = value;
  updatePreview();
}

// Advanced Configuration
function initializeAdvanced() {
  const container = document.getElementById('advanced-container');
  
  Object.entries(advancedConfigSections).forEach(([sectionId, section]) => {
    const sectionEl = createAdvancedSection(sectionId, section);
    container.appendChild(sectionEl);
  });
}

function createAdvancedSection(id, section) {
  const sectionEl = document.createElement('div');
  sectionEl.className = 'config-section';
  
  sectionEl.innerHTML = `
    <div class="config-section-header" onclick="toggleSection('${id}')">
      <h4>${section.title}</h4>
      <span class="config-section-toggle">▼</span>
    </div>
    <div class="config-section-body">
      <p style="color: var(--wizard-text-secondary); margin-bottom: 1.5rem;">${section.description}</p>
      <div class="config-grid">
        ${Object.entries(section.fields).map(([key, field]) => 
          createFieldInput(key, field)
        ).join('')}
      </div>
    </div>
  `;
  
  return sectionEl;
}

function createFieldInput(key, field) {
  const currentValue = configState.customConfig[key] ?? field.default;
  
  let input;
  if (field.type === 'boolean') {
    // Normalize to boolean
    const boolValue = currentValue === true || currentValue === 'true';
    input = `
      <select class="form-select" onchange="updateCustomConfig('${key}', this.value)">
        <option value="true" ${boolValue ? 'selected' : ''}>Enabled</option>
        <option value="false" ${!boolValue ? 'selected' : ''}>Disabled</option>
      </select>
    `;
  } else if (field.type === 'select') {
    input = `
      <select class="form-select" onchange="updateCustomConfig('${key}', this.value)">
        ${field.options.map(opt => 
          `<option value="${opt}" ${currentValue === opt ? 'selected' : ''}>${opt}</option>`
        ).join('')}
      </select>
    `;
  } else if (field.type === 'number') {
    input = `<input type="number" class="form-input" value="${currentValue}" 
                    onchange="updateCustomConfig('${key}', this.value)">`;
  } else {
    input = `<input type="text" class="form-input" value="${currentValue}" 
                    onchange="updateCustomConfig('${key}', this.value)">`;
  }
  
  return `
    <div class="form-group">
      <label class="form-label">${field.label}</label>
      ${input}
      <span class="form-label-description">${field.description}</span>
    </div>
  `;
}

function toggleSection(sectionId) {
  const sections = document.querySelectorAll('.config-section');
  sections.forEach(section => {
    const header = section.querySelector('.config-section-header h4');
    if (header && header.textContent === advancedConfigSections[sectionId].title) {
      section.classList.toggle('expanded');
    }
  });
}

function updateCustomConfig(key, value) {
  // Parse boolean values
  if (value === 'true') {
    value = true;
  } else if (value === 'false') {
    value = false;
  } else if (!isNaN(value) && value !== '' && typeof value === 'string') {
    // Only parse numbers from strings, not already-number values
    value = Number(value);
  }
  
  configState.customConfig[key] = value;
  updatePreview();
}

// Upload/Download
function initializeUpload() {
  const uploadArea = document.getElementById('upload-area');
  const fileInput = document.getElementById('file-input');
  
  uploadArea.addEventListener('click', () => fileInput.click());
  
  uploadArea.addEventListener('dragover', (e) => {
    e.preventDefault();
    uploadArea.classList.add('dragover');
  });
  
  uploadArea.addEventListener('dragleave', () => {
    uploadArea.classList.remove('dragover');
  });
  
  uploadArea.addEventListener('drop', (e) => {
    e.preventDefault();
    uploadArea.classList.remove('dragover');
    
    const files = e.dataTransfer.files;
    if (files.length > 0) {
      handleFileUpload(files[0]);
    }
  });
  
  fileInput.addEventListener('change', (e) => {
    if (e.target.files.length > 0) {
      handleFileUpload(e.target.files[0]);
    }
  });
}

function handleFileUpload(file) {
  const reader = new FileReader();
  
  reader.onload = (e) => {
    try {
      const content = e.target.result;
      parseConfigFile(content, file.name);
      showAlert('success', `Successfully loaded configuration from ${file.name}`);
    } catch (error) {
      showAlert('error', `Failed to parse configuration: ${error.message}`);
    }
  };
  
  reader.readAsText(file);
}

function parseConfigFile(content, filename) {
  // Basic HOCON parsing - extract key-value pairs
  // Note: This is a simplified parser. For complex HOCON files, manual review may be needed.
  const lines = content.split('\n');
  const config = {};
  
  lines.forEach(line => {
    line = line.trim();
    
    // Skip comments and empty lines
    if (line.startsWith('#') || line.startsWith('//') || line === '') return;
    
    // Simple key = value parsing (single line only)
    const match = line.match(/^\s*([a-zA-Z0-9._-]+)\s*=\s*(.+)$/);
    if (match) {
      let [, key, value] = match;
      value = value.trim().replace(/,$/, ''); // Remove trailing comma
      
      // Remove quotes
      if ((value.startsWith('"') && value.endsWith('"')) || 
          (value.startsWith("'") && value.endsWith("'"))) {
        value = value.slice(1, -1);
      }
      
      // Parse boolean
      if (value === 'true') {
        value = true;
      } else if (value === 'false') {
        value = false;
      } else if (!isNaN(value) && value !== '' && !/[a-zA-Z]/.test(value)) {
        // Parse number (if it doesn't contain letters)
        value = Number(value);
      }
      
      config[key] = value;
    }
  });
  
  // Merge into custom config
  configState.customConfig = { ...configState.customConfig, ...config };
  
  // Try to detect profile
  const detectedProfile = detectProfile(config);
  if (detectedProfile) {
    configState.selectedProfile = detectedProfile;
  }
  
  // Show warning for complex files
  if (content.includes('{') || content.includes('[')) {
    showAlert('warning', 'Complex HOCON structures detected. Some nested configurations may not be fully parsed. Please review the imported settings.');
  }
  
  updatePreview();
  switchTab('custom');
}

function detectProfile(config) {
  // Simple heuristic to detect which profile matches best
  let bestMatch = null;
  let bestScore = 0;
  
  Object.entries(profiles).forEach(([profileId, profile]) => {
    let score = 0;
    Object.entries(profile.config).forEach(([key, value]) => {
      if (config[key] === value) score++;
    });
    
    if (score > bestScore) {
      bestScore = score;
      bestMatch = profileId;
    }
  });
  
  return bestMatch;
}

function downloadConfig() {
  const config = generateHOCON();
  const blob = new Blob([config], { type: 'text/plain' });
  const url = URL.createObjectURL(blob);
  
  const a = document.createElement('a');
  a.href = url;
  a.download = `fukuii-${configState.selectedChain || 'custom'}-${Date.now()}.conf`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
  
  showAlert('success', 'Configuration downloaded successfully');
}

function downloadChainConfig() {
  const config = generateChainHOCON();
  const blob = new Blob([config], { type: 'text/plain' });
  const url = URL.createObjectURL(blob);
  
  const a = document.createElement('a');
  a.href = url;
  a.download = `${configState.selectedChain}-chain-${Date.now()}.conf`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
  
  showAlert('success', 'Chain configuration downloaded successfully');
}

// Preview Generation
function updatePreview() {
  const previewCode = document.getElementById('preview-code');
  if (previewCode) {
    previewCode.innerHTML = `<pre>${escapeHtml(generateHOCON())}</pre>`;
  }
}

function generateHOCON() {
  const lines = [];
  
  // Header comment
  lines.push('# Fukuii Configuration');
  lines.push(`# Generated by Configuration Wizard on ${new Date().toISOString()}`);
  lines.push(`# Fukuii Version: ${FUKUII_VERSION}`);
  lines.push(`# Profile: ${configState.selectedProfile || 'custom'}`);
  lines.push(`# Chain: ${configState.selectedChain}`);
  lines.push('');
  
  // Include base config
  lines.push('include "app.conf"');
  lines.push('');
  
  // Fukuii block
  lines.push('fukuii {');
  
  // Set blockchain network
  lines.push('  blockchains {');
  lines.push(`    network = "${configState.selectedChain}"`);
  lines.push('  }');
  lines.push('');
  
  // Organize config by section
  const configBySection = organizeConfigBySection(configState.customConfig);
  
  Object.entries(configBySection).forEach(([section, configs]) => {
    if (Object.keys(configs).length > 0) {
      lines.push(`  ${section} {`);
      
      Object.entries(configs).forEach(([key, value]) => {
        const formattedValue = formatConfigValue(value);
        lines.push(`    ${key} = ${formattedValue}`);
      });
      
      lines.push('  }');
      lines.push('');
    }
  });
  
  lines.push('}');
  
  return lines.join('\n');
}

function generateChainHOCON() {
  const chain = chainConfigs[configState.selectedChain];
  const lines = [];
  
  // Header
  lines.push('# Fukuii Chain Configuration');
  lines.push(`# Generated by Configuration Wizard on ${new Date().toISOString()}`);
  lines.push(`# Fukuii Version: ${FUKUII_VERSION}`);
  lines.push(`# Chain: ${chain.name}`);
  lines.push('');
  
  lines.push('{');
  
  // Network identity
  lines.push(`  network-id = ${configState.chainConfig['network-id'] || chain.networkId}`);
  lines.push(`  chain-id = "${configState.chainConfig['chain-id'] || chain.chainId}"`);
  lines.push('');
  
  // Fork block numbers
  Object.entries(chain.forks).forEach(([forkName, forkData]) => {
    const value = configState.chainConfig[forkName] || forkData.value;
    lines.push(`  # ${forkData.eip}: ${forkData.url}`);
    lines.push(`  ${forkName} = "${value}"`);
    lines.push('');
  });
  
  lines.push('}');
  
  return lines.join('\n');
}

function organizeConfigBySection(config) {
  const sections = {};
  
  Object.entries(config).forEach(([key, value]) => {
    // Extract section from key (e.g., fukuii.network.peer.max -> network.peer)
    const parts = key.split('.');
    if (parts[0] === 'fukuii') {
      parts.shift(); // Remove 'fukuii' prefix
    }
    
    if (parts.length >= 2) {
      const section = parts[0];
      const subkey = parts.slice(1).join('.');
      
      if (!sections[section]) {
        sections[section] = {};
      }
      
      sections[section][subkey] = value;
    } else {
      if (!sections['general']) {
        sections['general'] = {};
      }
      sections['general'][parts[0]] = value;
    }
  });
  
  return sections;
}

function formatConfigValue(value) {
  if (typeof value === 'boolean') {
    return value.toString();
  } else if (typeof value === 'number') {
    return value.toString();
  } else if (typeof value === 'string') {
    // Check if it's a duration or special format
    if (value.includes('.') && (value.endsWith('seconds') || value.endsWith('minutes'))) {
      return value;
    }
    // Check if it needs quotes
    if (value.startsWith('${') || /^[0-9a-zA-Z._-]+$/.test(value)) {
      return value;
    }
    return `"${value}"`;
  }
  return String(value);
}

function escapeHtml(text) {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

function showAlert(type, message) {
  const alertContainer = document.getElementById('alert-container');
  if (!alertContainer) return;
  
  const alert = document.createElement('div');
  alert.className = `alert alert-${type}`;
  alert.textContent = message;
  
  alertContainer.appendChild(alert);
  
  setTimeout(() => {
    alert.remove();
  }, 5000);
}

// Make functions available globally for inline event handlers
window.updateCustomConfig = updateCustomConfig;
window.updateChainConfig = updateChainConfig;
window.toggleSection = toggleSection;
window.downloadConfig = downloadConfig;
window.downloadChainConfig = downloadChainConfig;
