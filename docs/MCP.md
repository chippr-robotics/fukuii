# Fukuii MCP Server

## Overview

Fukuii includes Model Context Protocol (MCP) support integrated into its existing JSON-RPC infrastructure. The MCP methods are available via the standard JSON-RPC endpoints (HTTP on port 8546 and WebSocket on port 8552 by default, and the IPC socket), alongside traditional Ethereum JSON-RPC methods.

This integration enables AI assistants and other intelligent agents to interact with, monitor, and manage the Fukuii node through a standardized protocol using the same robust, production-tested infrastructure that powers the Ethereum JSON-RPC API.

## What is MCP?

The Model Context Protocol (MCP) is an open standard that enables AI assistants to securely connect to external data sources and tools. It provides a unified way for AI systems to:

- **Execute Tools**: Perform actions like querying node status or managing peers
- **Access Resources**: Read node state, configuration, and blockchain data
- **Use Prompts**: Access pre-defined conversation templates for common operations

For more information, visit the [Model Context Protocol specification](https://github.com/modelcontextprotocol).

## Enabling MCP Support

MCP methods are exposed through the JSON-RPC API. To enable them, add "mcp" to the enabled APIs of the transport you
use. HTTP and WebSocket serve `eth`, `net`, `web3` and `fukuii` by default, so the list below is the default plus `mcp`:

### Configuration

Add to your `application.conf` or `fukuii.conf`:

```hocon
fukuii.network.rpc {
  http.apis = "eth,net,web3,fukuii,mcp"
}
```

(`ws.apis` and `ipc.apis` work the same way; `fukuii.network.rpc.apis` sets one list for every transport. The value
is a comma-separated string.)

Or via command-line parameter:
```bash
fukuii -Dfukuii.network.rpc.http.apis=eth,net,web3,fukuii,mcp
```

The MCP tools are read-only and answer from the node's own state; they do not need `personal`, `debug` or `admin`.

## Using the MCP API

### JSON-RPC Integration

MCP methods follow the same JSON-RPC 2.0 protocol as standard Ethereum methods. You can call them via:

**HTTP/HTTPS** (`network.rpc.http.port`, default 8546):
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "mcp_initialize",
    "params": [{}]
  }'
```

**WebSocket** (`network.rpc.ws.port`, default 8552; the WebSocket listener is off until `network.rpc.ws.enabled = true`):
```javascript
const ws = new WebSocket('ws://localhost:8552');
ws.send(JSON.stringify({
  jsonrpc: "2.0",
  id: 1,
  method: "tools/list",
  params: []
}));
```

### Integration with AI Assistants

For AI assistants like Claude Desktop, you can create a simple proxy script that converts stdio to HTTP JSON-RPC:

```bash
#!/bin/bash
# mcp-proxy.sh
while IFS= read -r line; do
  curl -s -X POST http://localhost:8546 \
    -H "Content-Type: application/json" \
    -d "$line"
done
```

Then configure Claude Desktop:
```json
{
  "mcpServers": {
    "fukuii": {
      "command": "/path/to/mcp-proxy.sh"
    }
  }
}
```

## Available MCP Methods

### Initialize

**Method:** `mcp_initialize`

Initialize the MCP session and retrieve server capabilities.

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "mcp_initialize",
  "params": [{}]
}
```

Response:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2025-11-25",
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

### Tools

#### List Tools

**Method:** `tools/list`

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/list",
  "params": []
}
```

#### Call Tool

**Method:** `tools/call`

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": [{
    "name": "mcp_node_status",
    "arguments": {}
  }]
}
```

Available tools:
- `mcp_node_status` - Get current node status
- `mcp_node_info` - Get node information
- `mcp_blockchain_info` - Get blockchain state
- `mcp_sync_status` - Get sync status
- `mcp_peer_list` - List connected peers

### Resources

#### List Resources

**Method:** `resources/list`

```json
{
  "jsonrpc": "2.0",
  "id": 4,
  "method": "resources/list",
  "params": []
}
```

#### Read Resource

**Method:** `resources/read`

```json
{
  "jsonrpc": "2.0",
  "id": 5,
  "method": "resources/read",
  "params": [{
    "uri": "fukuii://node/status"
  }]
}
```

Available resources:
- `fukuii://node/status` - Node status as JSON
- `fukuii://node/config` - Node configuration
- `fukuii://blockchain/latest` - Latest block information
- `fukuii://peers/connected` - Connected peers
- `fukuii://sync/status` - Sync status

### Prompts

#### List Prompts

**Method:** `prompts/list`

```json
{
  "jsonrpc": "2.0",
  "id": 6,
  "method": "prompts/list",
  "params": []
}
```

#### Get Prompt

**Method:** `prompts/get`

```json
{
  "jsonrpc": "2.0",
  "id": 7,
  "method": "prompts/get",
  "params": [{
    "name": "mcp_node_health_check",
    "arguments": {}
  }]
}
```

Available prompts:
- `mcp_node_health_check` - Comprehensive node health check
- `mcp_sync_troubleshooting` - Troubleshoot sync issues
- `mcp_peer_management` - Manage peer connections

## Security Considerations

Since MCP methods are integrated into the JSON-RPC API, they benefit from all existing security features:

- **Authentication**: client certificates (mutual TLS). With `fukuii.network.rpc.http.tls.client-auth = "need"` (or
  `ws.tls`), only clients holding a certificate from the profile's truststore connect; an identity rule in
  `fukuii.network.rpc.client-identities` can grant `mcp` to one certificate only. There is no password or API-key
  authentication
- **CORS**: Configure CORS settings in `fukuii.network.rpc.http.cors-allowed-origins`
- **Rate Limiting**: Per-IP rate limiting via `fukuii.network.rpc.http.rate-limit` (off by default: `enabled = false`)
- **HTTPS**: `fukuii.network.rpc.http.tls { enabled = true, profile = "<name>" }` with a profile under
  `fukuii.tls.profiles` (the deprecated `mode = "https"` with a `certificate` block is removed in 0.13.0)
- **API Control**: `mcp` is opt-in per transport (`fukuii.network.rpc.http.apis`, `ws.apis`, `ipc.apis`)

**Production Recommendations:**
1. Keep the listener on `localhost` (the default), or enable TLS for all external access: a non-loopback listener
   without TLS refuses to start unless its `allow-insecure-bind = true` is set
2. Use client certificates (`client-auth = "need"`) for callers on other hosts
3. Configure appropriate CORS restrictions
4. Enable only required APIs
5. Monitor rate limits and adjust as needed

See the [TLS Operations runbook](runbooks/tls-operations.md) for profiles, mutual TLS and identity rules.

## Example Interactions

### Check Node Health

```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/call",
    "params": [{
      "name": "mcp_node_status",
      "arguments": {}
    }]
  }'
```

### Read Node Configuration

```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "resources/read",
    "params": [{
      "uri": "fukuii://node/config"
    }]
  }'
```

### Get Troubleshooting Prompt

```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "prompts/get",
    "params": [{
      "name": "mcp_sync_troubleshooting"
    }]
  }'
```

## Architecture

The MCP implementation leverages Fukuii's existing infrastructure:

- **McpService**: Service class that handles MCP operations
- **JsonRpcController**: Routes MCP methods alongside eth/web3 methods
- **Actor Integration**: Queries PeerManagerActor and SyncController for real node state
- **JSON Encoding**: Uses the same json4s encoders/decoders as other JSON-RPC methods

Benefits of this approach:
- Reuses battle-tested JSON-RPC infrastructure
- Automatic authentication, rate limiting, and security
- HTTP and WebSocket support out of the box
- Consistent with existing Ethereum JSON-RPC patterns
- Easy to extend with new MCP methods

## Future Enhancements

Planned improvements include:

1. **Real Node State Integration**: Connect to actual actor refs for live data
2. **Write Operations**: Tools for configuration changes and peer management
3. **Event Subscriptions**: WebSocket notifications for blockchain events
4. **Enhanced Authentication**: OAuth/JWT integration for AI assistants
5. **Advanced Diagnostics**: More detailed debugging and profiling tools
6. **Batch Operations**: Execute multiple MCP operations efficiently

## Contributing

We welcome contributions to enhance the MCP integration! Areas for improvement include:

- Integration with actual node state via actors
- Additional tools for node management
- More comprehensive resource providers
- Additional prompts for common scenarios
- Performance optimizations
- Documentation improvements

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on contributing to Fukuii.

## References

- [Model Context Protocol Specification](https://github.com/modelcontextprotocol)
- [MCP Introduction](https://modelcontextprotocol.io/docs/2026-07-28/getting-started/intro)
- [JSON-RPC 2.0 Specification](https://www.jsonrpc.org/specification)
- [Fukuii Documentation](https://chippr-robotics.github.io/fukuii/)

