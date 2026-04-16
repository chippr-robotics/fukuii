# Fukuii MCP Configuration for GitHub Copilot

This directory contains the Model Context Protocol (MCP) configuration for integrating Fukuii with GitHub Copilot's coding agent.

## What is MCP?

The Model Context Protocol (MCP) enables AI assistants like GitHub Copilot to connect to external tools and data sources. This configuration allows Copilot to interact with your Fukuii Ethereum Classic node directly.

## Configuration File

The `mcp.json` file defines how GitHub Copilot can connect to and interact with your Fukuii node through the MCP protocol.

## Setup Instructions

### 1. Enable MCP API in Fukuii

First, ensure MCP is enabled in your Fukuii configuration. Add to your `application.conf`:

```hocon
fukuii.network.rpc {
  apis = ["eth", "web3", "net", "personal", "mcp"]
  http {
    enabled = true
    interface = "localhost"
    port = 8545
  }
}
```

### 2. Configure GitHub Copilot

To use this MCP configuration with GitHub Copilot:

1. **Install Fukuii** and ensure it's available in your PATH
2. **Start your Fukuii node** with MCP enabled
3. **Configure Copilot** to use the MCP server

#### Option A: Repository-Level Configuration (Recommended)

This configuration is already set up in `.github/copilot/mcp.json`. GitHub Copilot will automatically detect and use it when working in this repository.

#### Option B: User-Level Configuration

Copy the configuration to your GitHub Copilot settings:

**For VS Code:**
1. Open Settings (⌘+, on Mac, Ctrl+, on Windows/Linux)
2. Search for "Copilot MCP"
3. Add the server configuration from `mcp.json`

**For GitHub Copilot CLI:**
Add to your `~/.config/github-copilot/mcp-servers.json`:

```json
{
  "mcpServers": {
    "fukuii": {
      "transport": "http",
      "url": "http://localhost:8545",
      "headers": {
        "Content-Type": "application/json"
      }
    }
  }
}
```

### 3. Verify Connection

Once configured, you can ask GitHub Copilot to interact with your Fukuii node:

```
@copilot What's the current status of my Fukuii node?
@copilot How many peers am I connected to?
@copilot What's the latest block number?
```

## Available Capabilities

### Tools (5)
- **mcp_node_status**: Get current node status (running, syncing, peers, block)
- **mcp_node_info**: Get node information (version, network, capabilities)
- **mcp_blockchain_info**: Get blockchain data (latest block, difficulty)
- **mcp_sync_status**: Get sync progress details
- **mcp_peer_list**: List connected peers

### Resources (5)
- **fukuii://node/status**: Node runtime status
- **fukuii://node/config**: Node configuration
- **fukuii://blockchain/latest**: Latest block information
- **fukuii://peers/connected**: Connected peers list
- **fukuii://sync/status**: Synchronization status

### Prompts (3)
- **mcp_node_health_check**: Comprehensive health check
- **mcp_sync_troubleshooting**: Sync issue troubleshooting
- **mcp_peer_management**: Peer management guidance

## Technical Details

### Protocol
- **Transport**: HTTP
- **Endpoint**: http://localhost:8545
- **Format**: JSON-RPC 2.0

### JSON-RPC Method Examples

Call MCP methods directly via JSON-RPC:

```bash
# Initialize MCP session
curl -X POST http://localhost:8545 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"mcp_initialize","params":[{}]}'

# List available tools
curl -X POST http://localhost:8545 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":[]}'

# Call a tool
curl -X POST http://localhost:8545 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":[{"name":"mcp_node_status","arguments":{}}]}'
```

## Security Considerations

- The default configuration connects to `localhost:8545`
- Ensure your Fukuii node is properly secured if exposing the RPC interface
- Consider using authentication for production deployments
- Review the [MCP Security Documentation](../../docs/MCP.md#security-considerations)

## Troubleshooting

### Copilot can't connect to Fukuii
1. Verify Fukuii is running: `ps aux | grep fukuii`
2. Check MCP is enabled: `curl http://localhost:8545 -X POST -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":1,"method":"mcp_initialize","params":[{}]}'`
3. Verify port 8545 is accessible: `netstat -an | grep 8545`

### Tools not appearing
1. Ensure `"mcp"` is in your `fukuii.network.rpc.apis` configuration
2. Restart Fukuii after configuration changes
3. Check logs for any MCP-related errors

## Learn More

- [MCP Documentation](../../docs/MCP.md)
- [Model Context Protocol Specification](https://github.com/modelcontextprotocol)
- [GitHub Copilot MCP Integration](https://docs.github.com/en/copilot/using-github-copilot/using-extensions/using-mcp-servers-with-github-copilot)
- [Fukuii JSON-RPC API](../../docs/api/json-rpc.md)

## Contributing

See [CONTRIBUTING.md](../../CONTRIBUTING.md) for guidelines on contributing to Fukuii's MCP implementation.
