# Model Context Protocol (MCP) Integration Guide

This document outlines the strategy for creating a Model Context Protocol (MCP) server for Fukuii's JSON-RPC API.

**Status**: Planning Phase  
**Last Updated**: 2025-11-24  
**Target MCP Version**: 2025-03-26

## Table of Contents

- [Overview](#overview)
- [MCP Server Architecture](#mcp-server-architecture)
- [Resource Mapping](#resource-mapping)
- [Tool Definitions](#tool-definitions)
- [Security Considerations](#security-considerations)
- [Implementation Roadmap](#implementation-roadmap)
- [Testing Strategy](#testing-strategy)

## Overview

### What is MCP?

The Model Context Protocol (MCP) is an open protocol that enables seamless integration between AI applications and external data sources. An MCP server exposes resources, tools, and prompts that AI assistants can use to interact with a system.

### Why MCP for Fukuii?

An MCP server for Fukuii would enable:
- **AI-Powered Blockchain Analysis**: LLMs can directly query blockchain data
- **Smart Contract Interaction**: Natural language contract calls and deployments
- **Transaction Creation**: Simplified transaction building via conversational interface
- **Network Monitoring**: Real-time blockchain state analysis
- **Development Assistant**: Help developers understand and interact with ETC

### Goals

1. **Complete API Coverage**: Expose all safe JSON-RPC methods via MCP
2. **Type Safety**: Provide strong typing for all parameters and responses
3. **Documentation**: Self-documenting with descriptions and examples
4. **Security**: Implement proper authentication and rate limiting
5. **Extensibility**: Easy to add new tools and resources

## MCP Server Architecture

### Components

```
┌─────────────────────────────────────────────┐
│         MCP Client (AI Assistant)           │
└─────────────────┬───────────────────────────┘
                  │ MCP Protocol (JSONRPC 2.0)
                  │
┌─────────────────▼───────────────────────────┐
│            Fukuii MCP Server                │
│  ┌──────────────────────────────────────┐   │
│  │  Resource Providers                  │   │
│  │  - Block Resources                   │   │
│  │  - Transaction Resources             │   │
│  │  - Account Resources                 │   │
│  │  - Network Resources                 │   │
│  └──────────────────────────────────────┘   │
│  ┌──────────────────────────────────────┐   │
│  │  Tool Implementations                │   │
│  │  - Query Tools                       │   │
│  │  - Transaction Tools                 │   │
│  │  - Contract Tools                    │   │
│  │  - Analysis Tools                    │   │
│  └──────────────────────────────────────┘   │
│  ┌──────────────────────────────────────┐   │
│  │  Security & Rate Limiting            │   │
│  └──────────────────────────────────────┘   │
└─────────────────┬───────────────────────────┘
                  │ JSON-RPC
                  │
┌─────────────────▼───────────────────────────┐
│         Fukuii JSON-RPC Endpoint            │
│            (http://localhost:8546)          │
└─────────────────────────────────────────────┘
```

### Technology Stack

**Recommended**: Node.js/TypeScript implementation
- `@modelcontextprotocol/sdk`: Official MCP SDK
- `ethers.js` or `web3.js`: Ethereum interaction
- `zod`: Runtime type validation
- `express`: HTTP server (if needed)

**Alternative**: Python implementation
- `mcp`: Official Python MCP SDK
- `web3.py`: Ethereum interaction
- `pydantic`: Data validation

## Resource Mapping

MCP Resources represent data that can be retrieved. Each resource has:
- **URI**: Unique identifier (e.g., `fukuii://block/latest`)
- **MIME Type**: Content type (e.g., `application/json`)
- **Description**: Human-readable description

### Proposed Resource URIs

#### Blocks
```
fukuii://block/latest          - Latest block
fukuii://block/earliest        - Genesis block
fukuii://block/pending         - Pending block
fukuii://block/{number}        - Block by number
fukuii://block/{hash}          - Block by hash
fukuii://block/{hash}/txs      - Transactions in block
fukuii://block/{hash}/uncles   - Uncles in block
```

#### Transactions
```
fukuii://tx/{hash}             - Transaction by hash
fukuii://tx/{hash}/receipt     - Transaction receipt
fukuii://tx/pending            - Pending transactions
```

#### Accounts
```
fukuii://account/{address}                    - Account info
fukuii://account/{address}/balance            - Account balance
fukuii://account/{address}/code               - Contract code
fukuii://account/{address}/storage/{position} - Storage slot
fukuii://account/{address}/txs                - Account transactions
```

#### Network
```
fukuii://network/info          - Network information
fukuii://network/peers         - Peer information
fukuii://network/sync          - Sync status
```

#### Logs
```
fukuii://logs?filter={spec}    - Event logs matching filter
```

### Example Resource Implementation

```typescript
import { Resource } from "@modelcontextprotocol/sdk/types.js";

const blockResources: Resource[] = [
  {
    uri: "fukuii://block/latest",
    name: "Latest Block",
    description: "The most recently mined block on the Ethereum Classic network",
    mimeType: "application/json"
  },
  {
    uri: "fukuii://block/{number}",
    name: "Block by Number",
    description: "Retrieve a specific block by its number",
    mimeType: "application/json"
  }
];

async function getResource(uri: string): Promise<{ contents: any }> {
  const url = new URL(uri);
  
  if (url.hostname === "block") {
    const blockId = url.pathname.slice(1); // Remove leading /
    
    if (blockId === "latest" || blockId === "earliest" || blockId === "pending") {
      const block = await ethClient.getBlock(blockId);
      return {
        contents: [{
          uri,
          mimeType: "application/json",
          text: JSON.stringify(block, null, 2)
        }]
      };
    }
    
    // Handle block number or hash
    const block = await ethClient.getBlock(blockId);
    return {
      contents: [{
        uri,
        mimeType: "application/json",
        text: JSON.stringify(block, null, 2)
      }]
    };
  }
  
  throw new Error(`Unknown resource: ${uri}`);
}
```

## Tool Definitions

MCP Tools represent actions that can be performed. Each tool has:
- **Name**: Unique identifier
- **Description**: What the tool does
- **Input Schema**: JSON Schema for parameters
- **Handler**: Implementation function

### Core Tools

#### 1. Query Tools (Read-Only)

##### get_block
```typescript
{
  name: "get_block",
  description: "Retrieve a block by number or hash",
  inputSchema: {
    type: "object",
    properties: {
      blockId: {
        type: "string",
        description: "Block number (hex or decimal), hash, or tag (latest/earliest/pending)"
      },
      includeTransactions: {
        type: "boolean",
        description: "If true, includes full transaction objects",
        default: false
      }
    },
    required: ["blockId"]
  }
}
```

##### get_transaction
```typescript
{
  name: "get_transaction",
  description: "Retrieve a transaction by hash",
  inputSchema: {
    type: "object",
    properties: {
      hash: {
        type: "string",
        pattern: "^0x[a-fA-F0-9]{64}$",
        description: "Transaction hash"
      }
    },
    required: ["hash"]
  }
}
```

##### get_account_balance
```typescript
{
  name: "get_account_balance",
  description: "Get the balance of an account",
  inputSchema: {
    type: "object",
    properties: {
      address: {
        type: "string",
        pattern: "^0x[a-fA-F0-9]{40}$",
        description: "Account address"
      },
      blockTag: {
        type: "string",
        description: "Block tag (latest/earliest/pending) or block number",
        default: "latest"
      }
    },
    required: ["address"]
  }
}
```

##### get_contract_code
```typescript
{
  name: "get_contract_code",
  description: "Get the bytecode of a contract",
  inputSchema: {
    type: "object",
    properties: {
      address: {
        type: "string",
        pattern: "^0x[a-fA-F0-9]{40}$",
        description: "Contract address"
      },
      blockTag: {
        type: "string",
        description: "Block tag or number",
        default: "latest"
      }
    },
    required: ["address"]
  }
}
```

##### query_logs
```typescript
{
  name: "query_logs",
  description: "Query event logs matching filter criteria",
  inputSchema: {
    type: "object",
    properties: {
      fromBlock: {
        type: "string",
        description: "Starting block (number or tag)"
      },
      toBlock: {
        type: "string",
        description: "Ending block (number or tag)"
      },
      address: {
        oneOf: [
          { type: "string" },
          { type: "array", items: { type: "string" } }
        ],
        description: "Contract address(es) to filter"
      },
      topics: {
        type: "array",
        items: {
          oneOf: [
            { type: "string" },
            { type: "array", items: { type: "string" } },
            { type: "null" }
          ]
        },
        description: "Topics to filter (null for wildcard)"
      }
    }
  }
}
```

##### call_contract
```typescript
{
  name: "call_contract",
  description: "Execute a read-only contract call",
  inputSchema: {
    type: "object",
    properties: {
      to: {
        type: "string",
        pattern: "^0x[a-fA-F0-9]{40}$",
        description: "Contract address"
      },
      data: {
        type: "string",
        pattern: "^0x[a-fA-F0-9]*$",
        description: "Encoded function call data"
      },
      from: {
        type: "string",
        pattern: "^0x[a-fA-F0-9]{40}$",
        description: "Sender address (optional)"
      },
      blockTag: {
        type: "string",
        description: "Block tag or number",
        default: "latest"
      }
    },
    required: ["to", "data"]
  }
}
```

##### estimate_gas
```typescript
{
  name: "estimate_gas",
  description: "Estimate gas required for a transaction",
  inputSchema: {
    type: "object",
    properties: {
      from: {
        type: "string",
        pattern: "^0x[a-fA-F0-9]{40}$",
        description: "Sender address"
      },
      to: {
        type: "string",
        pattern: "^0x[a-fA-F0-9]{40}$",
        description: "Recipient address"
      },
      value: {
        type: "string",
        description: "Value to send (in wei, as hex string)"
      },
      data: {
        type: "string",
        pattern: "^0x[a-fA-F0-9]*$",
        description: "Transaction data"
      }
    },
    required: ["to"]
  }
}
```

#### 2. Transaction Tools (Write Operations)

⚠️ **Note**: These tools should require explicit user confirmation in the MCP client.

##### send_raw_transaction
```typescript
{
  name: "send_raw_transaction",
  description: "Broadcast a signed transaction",
  inputSchema: {
    type: "object",
    properties: {
      signedTransaction: {
        type: "string",
        pattern: "^0x[a-fA-F0-9]+$",
        description: "RLP-encoded signed transaction"
      }
    },
    required: ["signedTransaction"]
  }
}
```

#### 3. Analysis Tools

##### analyze_transaction
```typescript
{
  name: "analyze_transaction",
  description: "Analyze a transaction and provide insights",
  inputSchema: {
    type: "object",
    properties: {
      hash: {
        type: "string",
        pattern: "^0x[a-fA-F0-9]{64}$",
        description: "Transaction hash"
      }
    },
    required: ["hash"]
  }
}

// Returns structured analysis:
{
  transaction: {...},
  receipt: {...},
  analysis: {
    success: boolean,
    gasUsed: string,
    gasEfficiency: number, // percentage of gas limit used
    events: [...], // decoded events if possible
    value: {
      wei: string,
      ether: string
    },
    trace: [...] // if debug_traceTransaction is available
  }
}
```

##### get_network_status
```typescript
{
  name: "get_network_status",
  description: "Get comprehensive network status",
  inputSchema: {
    type: "object",
    properties: {}
  }
}

// Returns:
{
  chainId: string,
  networkId: string,
  latestBlock: number,
  peerCount: number,
  syncing: boolean | object,
  gasPrice: string,
  clientVersion: string
}
```

### Tool Implementation Example

```typescript
import { Tool } from "@modelcontextprotocol/sdk/types.js";

const getBlockTool: Tool = {
  name: "get_block",
  description: "Retrieve a block by number, hash, or tag (latest/earliest/pending)",
  inputSchema: {
    type: "object",
    properties: {
      blockId: {
        type: "string",
        description: "Block identifier (number, hash, or tag)"
      },
      includeTransactions: {
        type: "boolean",
        description: "Include full transaction objects",
        default: false
      }
    },
    required: ["blockId"]
  }
};

async function handleGetBlock(args: {
  blockId: string;
  includeTransactions?: boolean;
}): Promise<any> {
  const { blockId, includeTransactions = false } = args;
  
  // Validate input
  const isHash = blockId.startsWith("0x") && blockId.length === 66;
  const isTag = ["latest", "earliest", "pending"].includes(blockId);
  const isNumber = !isNaN(parseInt(blockId));
  
  if (!isHash && !isTag && !isNumber) {
    throw new Error("Invalid block identifier");
  }
  
  // Call Fukuii JSON-RPC
  const response = await fetch("http://localhost:8546", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      jsonrpc: "2.0",
      id: 1,
      method: isHash ? "eth_getBlockByHash" : "eth_getBlockByNumber",
      params: [blockId, includeTransactions]
    })
  });
  
  const data = await response.json();
  
  if (data.error) {
    throw new Error(`RPC Error: ${data.error.message}`);
  }
  
  return {
    content: [{
      type: "text",
      text: JSON.stringify(data.result, null, 2)
    }]
  };
}
```

## Security Considerations

### Authentication

**Option 1: API Key Authentication**
```typescript
class FukuiiMCPServer {
  private validApiKeys: Set<string>;
  
  authenticate(headers: Headers): boolean {
    const apiKey = headers.get("X-API-Key");
    return apiKey !== null && this.validApiKeys.has(apiKey);
  }
}
```

**Option 2: OAuth 2.0**
- More complex but industry-standard
- Supports token refresh and revocation
- Better for multi-user scenarios

### Rate Limiting

```typescript
import { RateLimiter } from "limiter";

class RateLimitedMCPServer {
  private limiters = new Map<string, RateLimiter>();
  
  getRateLimiter(apiKey: string): RateLimiter {
    if (!this.limiters.has(apiKey)) {
      // 60 requests per minute
      this.limiters.set(apiKey, new RateLimiter({ tokensPerInterval: 60, interval: "minute" }));
    }
    return this.limiters.get(apiKey)!;
  }
  
  async checkRateLimit(apiKey: string): Promise<boolean> {
    const limiter = this.getRateLimiter(apiKey);
    return await limiter.tryRemoveTokens(1);
  }
}
```

### Method Restrictions

**Safe Methods** (Always allowed):
- All `eth_get*` methods
- All `eth_call` and read-only operations
- `web3_*` methods
- `net_*` methods

**Restricted Methods** (Require additional permissions):
- `eth_sendTransaction`
- `eth_sendRawTransaction`
- `personal_*` methods
- `debug_*` methods (performance impact)

**Forbidden Methods** (Never expose via MCP):
- `personal_unlockAccount`
- `personal_newAccount`
- `test_*` methods
- `qa_*` methods

```typescript
const METHOD_PERMISSIONS = {
  safe: [/^eth_get/, /^eth_call/, /^eth_estimate/, /^web3_/, /^net_/],
  restricted: [/^eth_send/, /^personal_send/],
  forbidden: [/^personal_unlock/, /^personal_new/, /^test_/, /^qa_/]
};

function checkMethodPermission(method: string, apiKey: string): boolean {
  // Check if forbidden
  if (METHOD_PERMISSIONS.forbidden.some(pattern => pattern.test(method))) {
    return false;
  }
  
  // Check if restricted
  if (METHOD_PERMISSIONS.restricted.some(pattern => pattern.test(method))) {
    return hasRestrictedPermission(apiKey);
  }
  
  // Safe methods allowed
  return METHOD_PERMISSIONS.safe.some(pattern => pattern.test(method));
}
```

### Input Validation

```typescript
import { z } from "zod";

const AddressSchema = z.string().regex(/^0x[a-fA-F0-9]{40}$/);
const HashSchema = z.string().regex(/^0x[a-fA-F0-9]{64}$/);
const HexDataSchema = z.string().regex(/^0x[a-fA-F0-9]*$/);
const BlockTagSchema = z.enum(["latest", "earliest", "pending"]);

const GetBalanceArgsSchema = z.object({
  address: AddressSchema,
  blockTag: z.union([BlockTagSchema, z.string()]).default("latest")
});

function validateGetBalanceArgs(args: unknown) {
  return GetBalanceArgsSchema.parse(args);
}
```

## Implementation Roadmap

### Phase 1: Core Infrastructure (Week 1-2)
- [ ] Set up MCP server project structure
- [ ] Implement basic server with MCP SDK
- [ ] Add Fukuii JSON-RPC client
- [ ] Implement authentication and rate limiting
- [ ] Create configuration system

### Phase 2: Essential Resources (Week 2-3)
- [ ] Implement block resources
- [ ] Implement transaction resources
- [ ] Implement account resources
- [ ] Implement network resources
- [ ] Add resource caching

### Phase 3: Core Tools (Week 3-4)
- [ ] Implement query tools (get_block, get_transaction, etc.)
- [ ] Implement analysis tools (analyze_transaction, get_network_status)
- [ ] Implement estimation tools (estimate_gas)
- [ ] Add comprehensive error handling

### Phase 4: Advanced Features (Week 4-5)
- [ ] Implement log querying tools
- [ ] Add contract interaction tools
- [ ] Implement transaction tools (with confirmations)
- [ ] Add batch operation support

### Phase 5: Testing & Documentation (Week 5-6)
- [ ] Unit tests for all tools and resources
- [ ] Integration tests with Fukuii
- [ ] Performance testing and optimization
- [ ] Complete documentation
- [ ] Example MCP client implementations

### Phase 6: Deployment (Week 6-7)
- [ ] Docker container for MCP server
- [ ] Kubernetes manifests
- [ ] CI/CD pipeline
- [ ] Monitoring and logging
- [ ] Production deployment guide

## Testing Strategy

### Unit Tests

```typescript
import { describe, it, expect, beforeEach } from "vitest";

describe("FukuiiMCPServer", () => {
  let server: FukuiiMCPServer;
  
  beforeEach(() => {
    server = new FukuiiMCPServer({
      rpcUrl: "http://localhost:8546",
      apiKeys: ["test-key"]
    });
  });
  
  describe("get_block tool", () => {
    it("should retrieve latest block", async () => {
      const result = await server.handleTool("get_block", {
        blockId: "latest",
        includeTransactions: false
      });
      
      expect(result).toHaveProperty("content");
      expect(result.content[0].type).toBe("text");
    });
    
    it("should validate block identifier", async () => {
      await expect(
        server.handleTool("get_block", { blockId: "invalid" })
      ).rejects.toThrow("Invalid block identifier");
    });
  });
  
  describe("authentication", () => {
    it("should reject invalid API key", async () => {
      const headers = new Headers({ "X-API-Key": "invalid-key" });
      expect(server.authenticate(headers)).toBe(false);
    });
    
    it("should accept valid API key", async () => {
      const headers = new Headers({ "X-API-Key": "test-key" });
      expect(server.authenticate(headers)).toBe(true);
    });
  });
});
```

### Integration Tests

```typescript
import { describe, it, expect } from "vitest";

describe("Integration with Fukuii", () => {
  it("should retrieve real block data", async () => {
    const server = new FukuiiMCPServer({
      rpcUrl: process.env.FUKUII_RPC_URL || "http://localhost:8546"
    });
    
    const result = await server.handleTool("get_block", {
      blockId: "latest"
    });
    
    const block = JSON.parse(result.content[0].text);
    expect(block).toHaveProperty("number");
    expect(block).toHaveProperty("hash");
    expect(block).toHaveProperty("transactions");
  });
});
```

### End-to-End Tests

```typescript
import { MCPClient } from "@modelcontextprotocol/sdk/client.js";

describe("MCP Client Integration", () => {
  it("should list available resources", async () => {
    const client = new MCPClient();
    await client.connect({
      url: "http://localhost:3000/mcp"
    });
    
    const resources = await client.listResources();
    expect(resources).toContainEqual(
      expect.objectContaining({ uri: "fukuii://block/latest" })
    );
  });
  
  it("should execute tools", async () => {
    const client = new MCPClient();
    await client.connect({
      url: "http://localhost:3000/mcp"
    });
    
    const result = await client.callTool("get_network_status", {});
    expect(result).toHaveProperty("chainId");
    expect(result).toHaveProperty("latestBlock");
  });
});
```

## Configuration

### Environment Variables

```bash
# Fukuii connection
FUKUII_RPC_URL=http://localhost:8546
FUKUII_WS_URL=ws://localhost:8546

# MCP server
MCP_PORT=3000
MCP_HOST=0.0.0.0

# Security
API_KEYS=key1,key2,key3
ALLOWED_ORIGINS=http://localhost:3000,https://app.example.com

# Rate limiting
RATE_LIMIT_REQUESTS=60
RATE_LIMIT_WINDOW=60000

# Caching
CACHE_TTL_BLOCKS=60000
CACHE_TTL_TXS=300000
CACHE_MAX_SIZE=1000

# Logging
LOG_LEVEL=info
LOG_FORMAT=json
```

### Configuration File

```yaml
# config.yaml
fukuii:
  rpc:
    url: http://localhost:8546
    timeout: 30000
  ws:
    url: ws://localhost:8546
    reconnect: true

mcp:
  server:
    port: 3000
    host: 0.0.0.0
  
  security:
    apiKeys:
      - name: "admin"
        key: "${ADMIN_API_KEY}"
        permissions: ["*"]
      - name: "read-only"
        key: "${READONLY_API_KEY}"
        permissions: ["read"]
    
    cors:
      enabled: true
      origins:
        - http://localhost:3000
        - https://app.example.com
  
  rateLimit:
    enabled: true
    requests: 60
    window: 60000
    
  cache:
    enabled: true
    ttl:
      blocks: 60000
      transactions: 300000
    maxSize: 1000

logging:
  level: info
  format: json
  destinations:
    - console
    - file: /var/log/fukuii-mcp/server.log
```

## Deployment

### Docker Compose

```yaml
version: '3.8'

services:
  fukuii:
    image: ghcr.io/chippr-robotics/chordodes_fukuii:latest
    ports:
      - "8546:8546"
      - "30303:30303"
    volumes:
      - fukuii-data:/app/data
    environment:
      - FUKUII_NETWORK=etc
  
  mcp-server:
    build: ./mcp-server
    ports:
      - "3000:3000"
    environment:
      - FUKUII_RPC_URL=http://fukuii:8546
      - API_KEYS=${API_KEYS}
      - LOG_LEVEL=info
    depends_on:
      - fukuii
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:3000/health"]
      interval: 30s
      timeout: 10s
      retries: 3

volumes:
  fukuii-data:
```

### Kubernetes Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: fukuii-mcp-server
spec:
  replicas: 3
  selector:
    matchLabels:
      app: fukuii-mcp-server
  template:
    metadata:
      labels:
        app: fukuii-mcp-server
    spec:
      containers:
      - name: mcp-server
        image: fukuii-mcp-server:latest
        ports:
        - containerPort: 3000
        env:
        - name: FUKUII_RPC_URL
          value: "http://fukuii-service:8546"
        - name: API_KEYS
          valueFrom:
            secretKeyRef:
              name: mcp-api-keys
              key: keys
        resources:
          requests:
            memory: "256Mi"
            cpu: "250m"
          limits:
            memory: "512Mi"
            cpu: "500m"
        livenessProbe:
          httpGet:
            path: /health
            port: 3000
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /ready
            port: 3000
          initialDelaySeconds: 10
          periodSeconds: 5
---
apiVersion: v1
kind: Service
metadata:
  name: fukuii-mcp-service
spec:
  selector:
    app: fukuii-mcp-server
  ports:
  - port: 80
    targetPort: 3000
  type: LoadBalancer
```

## Monitoring & Observability

### Metrics to Track

```typescript
import prometheus from "prom-client";

const register = new prometheus.Registry();

// Request metrics
const requestDuration = new prometheus.Histogram({
  name: "mcp_request_duration_seconds",
  help: "Duration of MCP requests",
  labelNames: ["tool", "status"],
  registers: [register]
});

const requestCounter = new prometheus.Counter({
  name: "mcp_requests_total",
  help: "Total number of MCP requests",
  labelNames: ["tool", "status"],
  registers: [register]
});

// RPC metrics
const rpcCallDuration = new prometheus.Histogram({
  name: "fukuii_rpc_call_duration_seconds",
  help: "Duration of Fukuii RPC calls",
  labelNames: ["method", "status"],
  registers: [register]
});

// Cache metrics
const cacheHitRate = new prometheus.Gauge({
  name: "mcp_cache_hit_rate",
  help: "Cache hit rate",
  registers: [register]
});

// Rate limit metrics
const rateLimitHits = new prometheus.Counter({
  name: "mcp_rate_limit_hits_total",
  help: "Total number of rate limit hits",
  labelNames: ["api_key"],
  registers: [register]
});
```

### Health Checks

```typescript
app.get("/health", (req, res) => {
  res.json({ status: "ok" });
});

app.get("/ready", async (req, res) => {
  try {
    // Check Fukuii connection
    await ethClient.getBlockNumber();
    res.json({ status: "ready" });
  } catch (error) {
    res.status(503).json({ status: "not ready", error: error.message });
  }
});
```

## Next Steps

1. **Prototype Development**
   - Create minimal MCP server with 3-5 core tools
   - Test with MCP Inspector and Claude Desktop
   - Gather feedback on API design

2. **Community Feedback**
   - Share design document with community
   - Collect use cases and requirements
   - Iterate on tool and resource definitions

3. **Full Implementation**
   - Follow roadmap phases
   - Maintain comprehensive test coverage
   - Document all features

4. **Production Deployment**
   - Deploy to testnet first
   - Monitor and optimize performance
   - Gradual rollout to mainnet

## References

- [MCP Specification](https://modelcontextprotocol.io/specification/2025-03-26)
- [MCP SDK Documentation](https://github.com/modelcontextprotocol/sdk)
- [Fukuii JSON-RPC API Reference](./JSON_RPC_API_REFERENCE.md)
- [Ethereum JSON-RPC Specification](https://ethereum.org/en/developers/docs/apis/json-rpc/)

---

**Maintained by**: Chippr Robotics LLC  
**Last Updated**: 2025-11-24  
**License**: Apache 2.0
