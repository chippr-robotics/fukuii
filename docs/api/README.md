# Fukuii API Documentation

Welcome to the Fukuii JSON-RPC API documentation. This directory contains comprehensive documentation for interacting with Fukuii's JSON-RPC interface and planning for Model Context Protocol (MCP) integration.

## 📚 Documentation Index

### Core API Documentation

1. **[Interactive API Reference](./interactive-api-reference.md)** 🆕
   - **Fully browsable OpenAPI specification** with Swagger UI
   - All 83 JSON-RPC endpoints with live examples
   - Organized by namespace with filtering
   - Try-it-out functionality for testing endpoints
   - **Use this for**: Exploring the API interactively, testing endpoints, integration planning

2. **[JSON-RPC API Reference](./JSON_RPC_API_REFERENCE.md)**
   - Complete reference for all JSON-RPC endpoints
   - Request/response examples for each method
   - Parameter descriptions and validation rules
   - Error codes and handling
   - Best practices for API usage
   - **Use this for**: Learning the API, integrating clients, reference lookup

3. **[RPC Endpoint Inventory](./RPC_ENDPOINT_INVENTORY.md)** 🆕
   - Comprehensive catalog of all 97 RPC endpoints
   - Organized by namespace with safety classifications
   - MCP coverage analysis
   - Production readiness indicators
   - Priority gaps for agent control
   - **Use this for**: Complete RPC endpoint reference, MCP planning

3. **[JSON-RPC Coverage Analysis](./JSON_RPC_COVERAGE_ANALYSIS.md)**
   - Comprehensive gap analysis vs Ethereum specification
   - Implementation status by namespace
   - Missing methods and their priority
   - EIP support status
   - Recommendations for completeness
   - **Use this for**: Understanding what's implemented, planning enhancements

### MCP & Agent Control Documentation

4. **[MCP Analysis Summary](./MCP_ANALYSIS_SUMMARY.md)** 🆕
   - Executive summary of RPC inventory and MCP planning
   - Key findings and strategic recommendations
   - Security architecture overview
   - Implementation timeline and success metrics
   - **Use this for**: Understanding MCP strategy, stakeholder communication

5. **[MCP Enhancement Plan](./MCP_ENHANCEMENT_PLAN.md)** 🆕
   - Complete roadmap for agent-controlled node management
   - 6-phase implementation plan (12-16 weeks)
   - 45+ new tools, 20+ resources, 15+ prompts
   - Security considerations and acceptance criteria
   - Testing strategy and documentation requirements
   - **Use this for**: Implementing complete agent control, detailed planning

6. **[MCP Integration Guide](./MCP_INTEGRATION_GUIDE.md)**
   - Architecture for Model Context Protocol server
   - Resource and tool definitions
   - Security considerations and authentication
   - Implementation roadmap
   - Deployment strategies
   - **Use this for**: Building AI integrations, planning MCP server development

### Quick Links

- **[Interactive API Reference](./interactive-api-reference.md)** - Browse all endpoints with Swagger UI
- **[Insomnia Guide](INSOMNIA_WORKSPACE_GUIDE.md)** - How to use the Insomnia API collection
- **[Maintaining API Reference](MAINTAINING_API_REFERENCE.md)** - Guide for updating the API spec
- **[Runbooks](../runbooks/README.md)** - Operational documentation
- **[Documentation Home](../index.md)** - Project overview and getting started

## 🎯 Quick Start

### For Developers

1. **Start Fukuii**:
   ```bash
   ./bin/fukuii etc
   ```

2. **Test the API**:
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

3. **Import Insomnia Workspace**:
   - Open Insomnia
   - Import the Insomnia workspace from the repository root
   - Start exploring all 78 endpoints

### For AI Integration

See [MCP Integration Guide](./MCP_INTEGRATION_GUIDE.md) for building AI-powered blockchain tools using the Model Context Protocol.

## 📖 API Overview

### Namespaces

Fukuii organizes JSON-RPC methods into namespaces:

| Namespace | Endpoints | Purpose | Production Ready |
|-----------|-----------|---------|------------------|
| **ETH** | 52 | Core blockchain operations | ✅ Yes |
<!-- Note: Keep endpoint counts synchronized with RPC_ENDPOINT_INVENTORY.md -->
| **WEB3** | 2 | Utility methods | ✅ Yes |
| **NET** | 9 | Network & peer management | ✅ Yes |
| **PERSONAL** | 8 | Account management | ⚠️ Dev only |
| **DEBUG** | 3 | Debugging and analysis | ⚠️ Use with caution |
| **QA** | 3 | Testing utilities | ❌ Testing only |
| **CHECKPOINTING** | 2 | ETC checkpointing | ✅ Yes (ETC specific) |
| **FUKUII** | 1 | Custom extensions | ✅ Yes |
| **TEST** | 7 | Test harness | ❌ Testing only |
| **IELE** | 2 | IELE VM support | ⚠️ If IELE enabled |
| **MCP** | 7 | Model Context Protocol | ✅ Yes |
| **RPC** | 1 | RPC metadata | ✅ Yes |
| **TOTAL** | 97 | All namespaces | Mixed |

### Core Features

#### ✅ Complete Coverage
- All standard Ethereum JSON-RPC methods
- Block queries and transactions
- Account state and balances
- Contract calls and gas estimation
- Event logs and filtering
- Mining operations

#### 🔧 ETC Extensions
- Raw transaction retrieval
- Storage root queries
- Checkpointing system
- Account transaction history

#### 🧪 Development Tools
- Test chain manipulation
- QA mining utilities
- Debug peer information
- Account management (personal namespace)

## 🔐 Security Considerations

### Production Configuration

For production deployments:

1. **Disable dangerous namespaces**:
   ```hocon
   fukuii.network.rpc {
     http {
       apis = "eth,web3,net"  # Exclude personal, debug, test, qa
     }
   }
   ```

2. **Enable authentication**:
   - Use reverse proxy (nginx, Caddy) for auth
   - Implement API key validation
   - Use firewall rules for IP whitelisting

3. **Rate limiting**:
   ```hocon
   fukuii.network.rpc {
     rate-limit {
       enabled = true
       min-request-interval = 100.milliseconds
     }
   }
   ```

4. **Use HTTPS/TLS**:
   - Never expose RPC over plain HTTP
   - See [TLS Operations Runbook](../runbooks/tls-operations.md)

### Method Safety

| Safety Level | Namespaces | Notes |
|--------------|------------|-------|
| **Safe** | eth (read-only), web3, net | Always safe to expose |
| **Restricted** | eth (write ops) | Require authentication |
| **Dangerous** | personal, debug | Never expose publicly |
| **Testing Only** | test, qa | Disable in production |

## 🚀 Common Use Cases

### 1. Query Latest Block

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

### 2. Get Account Balance

```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_getBalance",
    "params": ["0xYourAddress", "latest"]
  }'
```

### 3. Call Smart Contract

```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_call",
    "params": [{
      "to": "0xContractAddress",
      "data": "0xFunctionSignature"
    }, "latest"]
  }'
```

### 4. Query Event Logs

```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_getLogs",
    "params": [{
      "fromBlock": "0x0",
      "toBlock": "latest",
      "address": "0xContractAddress"
    }]
  }'
```

### 5. Send Raw Transaction

```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_sendRawTransaction",
    "params": ["0xSignedTransactionData"]
  }'
```

## 📊 API Performance

### Response Times (Typical)

| Operation | Average | 95th Percentile |
|-----------|---------|-----------------|
| Block queries | <10ms | <50ms |
| Transaction receipts | <10ms | <30ms |
| Account balance | <5ms | <20ms |
| Contract calls | <50ms | <200ms |
| Log queries (1000 blocks) | <100ms | <500ms |
| Gas estimation | <20ms | <100ms |

### Caching Strategy

Fukuii caches:
- ✅ Historical blocks (immutable)
- ✅ Transaction receipts (immutable)
- ⚠️ Latest block (TTL: 30s)
- ❌ Pending data (not cached)

## 🔧 Troubleshooting

### Common Issues

#### 1. Connection Refused

```
Error: connect ECONNREFUSED 127.0.0.1:8546
```

**Solution**: Ensure Fukuii is running and RPC is enabled:
```hocon
fukuii.network.rpc {
  http {
    enabled = true
    interface = "127.0.0.1"
    port = 8546
  }
}
```

#### 2. Method Not Found

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "error": {
    "code": -32601,
    "message": "Method not found"
  }
}
```

**Solution**: Check that the namespace is enabled in configuration:
```hocon
fukuii.network.rpc {
  http {
    apis = "eth,web3,net,personal"  # Add required namespaces
  }
}
```

#### 3. Rate Limited

**Solution**: Adjust rate limiting configuration or implement backoff in client:
```typescript
async function callWithRetry(method, params, maxRetries = 3) {
  for (let i = 0; i < maxRetries; i++) {
    try {
      return await rpcCall(method, params);
    } catch (error) {
      if (error.code === -32005 && i < maxRetries - 1) {
        await sleep(1000 * (i + 1)); // Exponential backoff
      } else {
        throw error;
      }
    }
  }
}
```

## 📈 Monitoring

### Health Checks

Fukuii provides built-in health check endpoints:

```bash
# Liveness (is server running?)
curl http://localhost:8546/health

# Readiness (is node synced and ready?)
curl http://localhost:8546/readiness

# Detailed health info
curl http://localhost:8546/healthcheck
```

See [Metrics & Monitoring](../operations/metrics-and-monitoring.md) for comprehensive monitoring setup.

### Key Metrics to Monitor

1. **Sync Status**: `eth_syncing`
2. **Peer Count**: `net_peerCount`
3. **Latest Block**: `eth_blockNumber`
4. **Gas Price**: `eth_gasPrice`
5. **Chain ID**: `eth_chainId`

## 🤝 Contributing

Found an issue or want to suggest an improvement? See our [Contributing Guide](../development/contributing.md).

### Documentation Updates

When updating API documentation:

1. Update the relevant markdown file
2. **Update the Insomnia workspace** (`insomnia_workspace.json` in repository root) if adding/modifying endpoints
3. **Regenerate the OpenAPI spec** by running: `python3 scripts/convert_insomnia_to_openapi.py`
4. Update [coverage analysis](./JSON_RPC_COVERAGE_ANALYSIS.md) if implementation status changes
5. Test all examples and code snippets
6. Submit PR with clear description

#### Updating the Interactive API Reference

The interactive API reference is automatically generated from the Insomnia workspace:

```bash
# 1. Update insomnia_workspace.json with new/modified endpoints
# 2. Run the conversion script
python3 scripts/convert_insomnia_to_openapi.py

# 3. Verify the OpenAPI spec
python3 -c "import json; print(json.load(open('docs/api/openapi.json'))['info']['title'])"

# 4. Build and preview the docs
mkdocs serve
# Visit http://localhost:8000/api/interactive-api-reference/
```

## 📚 Additional Resources

### Official Documentation
- [Ethereum JSON-RPC Specification](https://ethereum.org/en/developers/docs/apis/json-rpc/)
- [Ethereum Classic Documentation](https://ethereumclassic.org/development)
- [Model Context Protocol](https://modelcontextprotocol.io/)

### Tools & Libraries
- **JavaScript/TypeScript**: ethers.js, web3.js
- **Python**: web3.py
- **Go**: go-ethereum (geth)
- **Rust**: ethers-rs
- **Java**: web3j

### Community
- [GitHub Repository](https://github.com/chippr-robotics/fukuii)
- [Issue Tracker](https://github.com/chippr-robotics/fukuii/issues)

## 📋 Changelog

### 2025-12-12
- ✅ Complete RPC endpoint inventory (97 endpoints cataloged)
- ✅ MCP analysis and enhancement plan created
- ✅ Identified MCP coverage gaps (7.2% → 85% target)
- ✅ 6-phase roadmap for agent-controlled node management
- ✅ Security architecture for multi-level access control

### 2025-11-24
- ✅ Created comprehensive API documentation
- ✅ Completed coverage analysis
- ✅ Added MCP integration guide
- ✅ Updated Insomnia workspace
- ✅ Documented all endpoints

### Future Plans
- [ ] Implement MCP Enhancement Plan (Phases 1-6)
- [ ] Add 45+ new MCP tools for complete node control
- [ ] Implement multi-level access control for agents
- [ ] Add transaction tracing (debug namespace)
- [ ] Add WebSocket subscription support
- [ ] Implement GraphQL endpoint (optional)

---

**Maintained by**: Chippr Robotics LLC  
**Last Updated**: 2025-11-24  
**License**: Apache 2.0
