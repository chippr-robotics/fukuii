# Interactive API Reference

This page provides an interactive, browsable reference for all Fukuii JSON-RPC API endpoints.

## About This Reference

This interactive API documentation is generated from the Insomnia workspace and covers all **83 JSON-RPC endpoints** across **11 namespaces**:

- **ETH**: Core Ethereum blockchain operations
- **WEB3**: Utility methods
- **NET**: Network and peer information  
- **PERSONAL**: Account management (⚠️ dev/test only)
- **DEBUG**: Debugging and diagnostics (⚠️ use with caution)
- **QA**: Testing utilities (❌ test networks only)
- **CHECKPOINTING**: ETC checkpointing system
- **FUKUII**: Custom Fukuii extensions
- **TEST**: Test harness methods (❌ test networks only)
- **IELE**: IELE VM support (if enabled)
- **RPC**: RPC metadata

## How to Use This Reference

1. **Browse by namespace**: Use the tags on the left to filter methods by namespace
2. **Try it out**: Each endpoint includes example requests you can copy
3. **Explore parameters**: Click on each method to see detailed parameter information
4. **View responses**: See example responses and error codes

## Quick Start

All JSON-RPC methods are called via HTTP POST to the RPC endpoint (default: `http://localhost:8546`).

Example using curl:

```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_blockNumber",
    "params": []
  }'
```

## Security Notice

⚠️ **Important**: Only expose `eth`, `web3`, and `net` namespaces in production environments. Never expose `personal`, `debug`, `test`, or `qa` namespaces on public networks.

---

## API Reference

<swagger-ui src="/api/openapi.json"/>

## Additional Resources

- **[JSON-RPC API Reference (Text)](./JSON_RPC_API_REFERENCE.md)**: Detailed text documentation
- **[RPC Endpoint Inventory](./RPC_ENDPOINT_INVENTORY.md)**: Complete catalog with safety classifications
- **[Insomnia Workspace Guide](./INSOMNIA_WORKSPACE_GUIDE.md)**: How to use the Insomnia collection
- **[API Overview](./README.md)**: Getting started with the API

## Feedback

Found an issue or have suggestions? Please [open an issue](https://github.com/chippr-robotics/fukuii/issues) on GitHub.
