# Fukuii MCP Server

## Overview

Fukuii includes a Model Context Protocol (MCP) server that provides agentic control over the Ethereum Classic node. The MCP server enables AI assistants and other intelligent agents to interact with, monitor, and manage the Fukuii node through a standardized protocol.

## What is MCP?

The Model Context Protocol (MCP) is an open standard that enables AI assistants to securely connect to external data sources and tools. It provides a unified way for AI systems to:

- **Execute Tools**: Perform actions like querying node status or managing peers
- **Access Resources**: Read node state, configuration, and blockchain data
- **Use Prompts**: Access pre-defined conversation templates for common operations

For more information, visit the [Model Context Protocol specification](https://github.com/modelcontextprotocol).

## Starting the MCP Server

The MCP server communicates over stdio (standard input/output), making it easy to integrate with AI assistants and automation tools.

### Basic Usage

```bash
fukuii mcp
```

This starts the MCP server, which listens for JSON-RPC 2.0 messages on stdin and responds on stdout.

### Integration with Claude Desktop

To use the Fukuii MCP server with Claude Desktop, add the following to your Claude configuration file:

**macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
**Windows**: `%APPDATA%\Claude\claude_desktop_config.json`

```json
{
  "mcpServers": {
    "fukuii": {
      "command": "/path/to/fukuii/bin/fukuii",
      "args": ["mcp"]
    }
  }
}
```

Replace `/path/to/fukuii` with the actual path to your Fukuii installation.

### Integration with Other AI Assistants

The MCP server can be integrated with any AI assistant or automation tool that supports the Model Context Protocol. The server communicates via stdin/stdout using JSON-RPC 2.0, making it compatible with various integration methods.

## Available Capabilities

### Tools

Tools are functions that can be called to perform actions or retrieve information about the node:

| Tool Name | Description |
|-----------|-------------|
| `node_status` | Get the current status of the Fukuii node (syncing state, peer count, block height) |
| `node_info` | Get detailed information about the node (version, network, client ID) |
| `blockchain_info` | Get blockchain state information (best block, chain ID, difficulty) |
| `sync_status` | Get detailed synchronization status (mode, progress, speed) |
| `peer_list` | List all connected peers with their capabilities and block heights |

### Resources

Resources provide read-only access to node state and configuration:

| Resource URI | Description |
|-------------|-------------|
| `fukuii://node/status` | Current node status as JSON |
| `fukuii://node/config` | Current node configuration as JSON |
| `fukuii://blockchain/latest` | Latest block information as JSON |
| `fukuii://peers/connected` | Connected peers information as JSON |
| `fukuii://sync/status` | Synchronization status as JSON |

### Prompts

Prompts are pre-defined conversation templates for common operations:

| Prompt Name | Description |
|------------|-------------|
| `node_health_check` | Comprehensive health check of the Fukuii node |
| `sync_troubleshooting` | Troubleshoot blockchain synchronization issues |
| `peer_management` | Manage and optimize peer connections |

## Example Interactions

### Health Check

```
AI: Use the node_health_check prompt to verify my node is healthy.

MCP Server: [Returns a guided template for checking:
- Node status and responsiveness
- Blockchain sync progress
- Peer connectivity
- Recent block production
- Resource usage and errors]

AI: [Executes tools to gather data and provides comprehensive assessment]
```

### Node Status

```
AI: What's the current status of my Fukuii node?

MCP Server: [Executes node_status tool]

Response:
Node Status:
• Running: true
• Syncing: true
• Peers: 5
• Current Block: 12345678
• Best Known Block: 12345700
• Sync Progress: 99.98%
```

### Peer Management

```
AI: Show me my connected peers.

MCP Server: [Executes peer_list tool]

Response:
Connected Peers (5):

1. Peer: 52.12.123.45:30303
   • Node ID: enode://abc123...def456
   • Client: Geth/v1.10.26
   • Capabilities: eth/66, eth/67
   • Best Block: 12345700
[...]
```

## Protocol Details

The Fukuii MCP server implements JSON-RPC 2.0 over stdio. Each request is a JSON object on a single line:

### Initialize

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2024-11-05",
    "capabilities": {},
    "clientInfo": {
      "name": "ExampleClient",
      "version": "1.0.0"
    }
  }
}
```

Response:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2024-11-05",
    "capabilities": {
      "tools": {},
      "resources": {},
      "prompts": {}
    },
    "serverInfo": {
      "name": "Fukuii ETC Node MCP Server",
      "version": "1.0.0"
    }
  }
}
```

### List Tools

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/list",
  "params": {}
}
```

### Call Tool

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "node_status",
    "arguments": {}
  }
}
```

## Testing

You can test the MCP server using the MCP Inspector tool:

```bash
npx @modelcontextprotocol/inspector /path/to/fukuii/bin/fukuii mcp
```

This launches an interactive web interface for testing all MCP capabilities.

## Future Enhancements

The current MCP server implementation provides read-only access to node state. Future enhancements may include:

- **Write Operations**: Tools to modify node configuration
- **Event Subscriptions**: Real-time notifications for blockchain events
- **Advanced Diagnostics**: More detailed debugging and profiling tools
- **Batch Operations**: Execute multiple operations efficiently
- **Authentication**: Secure access control for production deployments

## Technical Implementation

The Fukuii MCP server is implemented in Scala using:
- **fs2**: For streaming I/O and message processing
- **Cats Effect**: For functional effects and concurrency
- **Circe**: For JSON encoding/decoding
- **JSON-RPC 2.0**: Standard protocol for request/response messaging

The implementation is located at:
`src/main/scala/com/chipprbots/ethereum/mcp/FukuiiMcpServer.scala`

## Contributing

We welcome contributions to enhance the MCP server! Areas for improvement include:

- Integration with actual node state (currently uses mock data)
- Additional tools for node management
- More comprehensive resource providers
- Additional prompts for common scenarios
- Performance optimizations
- Documentation improvements

See [CONTRIBUTING.md](../CONTRIBUTING.md) for guidelines on contributing to Fukuii.

## References

- [Model Context Protocol Specification](https://github.com/modelcontextprotocol)
- [MCP Introduction](https://modelcontextprotocol.io/introduction)
- [MCP SDK Documentation](https://modelcontextprotocol.io/docs/tools/inspector)
- [Fukuii Documentation](https://chippr-robotics.github.io/fukuii/)
