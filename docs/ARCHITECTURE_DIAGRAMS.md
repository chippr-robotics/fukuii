# Fukuii Application Architecture - C4 Diagrams

This document contains C4 architecture diagrams for the Fukuii Ethereum Classic client, showing the current state with vendored modules and the proposed integrated architecture.

## System Context Diagram (Level 1)

Shows Fukuii in the context of its users and external systems.

```mermaid
C4Context
    title System Context - Fukuii Ethereum Classic Client

    Person(user, "Node Operator", "Runs and manages Fukuii node")
    Person(developer, "dApp Developer", "Interacts with blockchain via JSON-RPC")
    
    System(fukuii, "Fukuii Client", "Ethereum Classic full node implementation in Scala 3")
    
    System_Ext(ethNetwork, "ETC P2P Network", "Ethereum Classic peer-to-peer network")
    System_Ext(monitoring, "Monitoring System", "Prometheus/Grafana for metrics")
    SystemDb_Ext(rocksdb, "RocksDB", "Blockchain data storage")
    
    Rel(user, fukuii, "Manages", "CLI, Config Files")
    Rel(developer, fukuii, "Queries", "JSON-RPC API")
    Rel(fukuii, ethNetwork, "Syncs with", "DevP2P Protocol")
    Rel(fukuii, rocksdb, "Reads/Writes", "Key-Value Storage")
    Rel(monitoring, fukuii, "Scrapes metrics", "HTTP/Prometheus")
```

## Container Diagram (Level 2)

Shows the high-level technical building blocks of Fukuii.

```mermaid
C4Container
    title Container - Fukuii Application Components

    Person(user, "User")
    System_Ext(peers, "ETC Peers")
    SystemDb(rocksdb, "RocksDB", "Blockchain Storage")

    Container_Boundary(fukuii, "Fukuii Client") {
        Container(jsonrpc, "JSON-RPC Server", "Pekko HTTP", "Ethereum JSON-RPC API (eth_, web3_, net_)")
        Container(consensus, "Consensus Engine", "Scala", "Block validation, mining coordination")
        Container(blockchain, "Blockchain Manager", "Scala", "Block processing, chain management")
        Container(evm, "EVM Executor", "Scala", "Smart contract execution")
        Container(network, "Network Layer", "Scalanet/DevP2P", "P2P communication with peers")
        Container(storage, "Storage Layer", "RocksDB wrapper", "Persistent blockchain data")
        Container(txpool, "Transaction Pool", "Scala", "Pending transaction management")
    }

    Rel(user, jsonrpc, "JSON-RPC calls", "HTTP/WebSocket")
    Rel(jsonrpc, blockchain, "Queries blocks/txs")
    Rel(jsonrpc, txpool, "Submits transactions")
    Rel(consensus, blockchain, "Validates blocks")
    Rel(blockchain, evm, "Executes transactions")
    Rel(blockchain, storage, "Persists data")
    Rel(network, peers, "Syncs", "DevP2P")
    Rel(network, blockchain, "Receives blocks")
    Rel(network, txpool, "Broadcasts txs")
    Rel(storage, rocksdb, "Read/Write")
```

## Component Diagram (Level 3) - Current State

Shows the internal structure of Fukuii with vendored modules as separate SBT subprojects.

```mermaid
C4Component
    title Component - Fukuii Internal Architecture (Current State)

    Container_Boundary(main, "Main Application (src/)") {
        Component(app, "App", "Main entry point, node initialization")
        Component(jsonrpc_api, "JSON-RPC API", "eth_, web3_, net_ endpoints")
        Component(blockchain_mgr, "Blockchain Manager", "Block processing, sync")
        Component(consensus_eng, "Consensus", "PoW validation, mining")
        Component(evm_exec, "EVM", "Smart contract execution")
        Component(ledger, "Ledger", "State management, account storage")
        Component(tx_pool, "Transaction Pool", "Mempool management")
        Component(network_p2p, "Network P2P", "Peer management, message handling")
        Component(storage_mgr, "Storage Manager", "Database abstraction")
        Component(mpt, "MPT", "Merkle Patricia Trie")
    }

    Container_Boundary(bytes_mod, "bytes/ (Vendored Module)") {
        Component(bytes, "Bytes Utils", "Hex, ByteString utilities")
    }

    Container_Boundary(crypto_mod, "crypto/ (Vendored Module)") {
        Component(crypto, "Crypto", "ECDSA, ECIES, zkSNARK")
    }

    Container_Boundary(rlp_mod, "rlp/ (Vendored Module)") {
        Component(rlp, "RLP Codec", "Recursive Length Prefix encoding")
    }

    Container_Boundary(scalanet_mod, "scalanet/ (Vendored Module)") {
        Component(scalanet, "Scalanet", "Low-level networking, TCP")
        Component(discovery, "Discovery", "Peer discovery, Kademlia DHT")
    }

    Rel(app, jsonrpc_api, "Initializes")
    Rel(app, blockchain_mgr, "Initializes")
    Rel(jsonrpc_api, blockchain_mgr, "Queries")
    Rel(blockchain_mgr, consensus_eng, "Validates")
    Rel(blockchain_mgr, evm_exec, "Executes")
    Rel(blockchain_mgr, ledger, "Updates state")
    Rel(blockchain_mgr, storage_mgr, "Persists")
    Rel(network_p2p, blockchain_mgr, "Delivers blocks")
    Rel(network_p2p, scalanet, "Uses")
    Rel(network_p2p, discovery, "Uses")
    Rel(consensus_eng, crypto, "Uses")
    Rel(evm_exec, crypto, "Uses")
    Rel(ledger, mpt, "Uses")
    Rel(mpt, rlp, "Uses")
    Rel(blockchain_mgr, rlp, "Uses")
    Rel(crypto, bytes, "Uses")
    Rel(rlp, bytes, "Uses")
    Rel(storage_mgr, bytes, "Uses")
```

## Component Diagram (Level 3) - Proposed Integrated State

Shows how the architecture will look after fully incorporating vendored modules into the main application.

```mermaid
C4Component
    title Component - Fukuii Fully Integrated Architecture (Proposed)

    Container_Boundary(main, "Main Application (src/)") {
        Component(app, "App", "Main entry point")
        Component(jsonrpc_api, "JSON-RPC API", "API endpoints")
        Component(blockchain_mgr, "Blockchain Manager", "Block processing")
        Component(consensus_eng, "Consensus", "PoW/mining")
        Component(evm_exec, "EVM", "Contract execution")
        Component(ledger, "Ledger", "State management")
        Component(tx_pool, "Transaction Pool", "Mempool")
        Component(storage_mgr, "Storage", "DB layer")
        Component(mpt, "MPT", "Merkle trie")
        
        Component_Boundary(utils, "Utils Package") {
            Component(bytes_int, "Bytes Utils", "Previously bytes/ module")
        }
        
        Component_Boundary(crypto_pkg, "Crypto Package") {
            Component(crypto_int, "Crypto Utils", "Previously crypto/ module")
            Component(crypto_app, "App Crypto", "Application crypto logic")
        }
        
        Component_Boundary(rlp_pkg, "RLP Package") {
            Component(rlp_int, "RLP Codec", "Previously rlp/ module")
            Component(rlp_app, "App RLP", "Application RLP logic")
        }
        
        Component_Boundary(network_pkg, "Network Package") {
            Component(network_p2p, "P2P Layer", "Peer management")
            Component(scalanet_int, "Scalanet", "Previously scalanet/ module")
            Component(discovery_int, "Discovery", "Previously scalanet/discovery")
        }
    }

    Rel(app, jsonrpc_api, "Initializes")
    Rel(blockchain_mgr, consensus_eng, "Validates")
    Rel(blockchain_mgr, evm_exec, "Executes")
    Rel(network_p2p, scalanet_int, "Uses")
    Rel(network_p2p, discovery_int, "Uses")
    Rel(consensus_eng, crypto_int, "Uses")
    Rel(evm_exec, crypto_int, "Uses")
    Rel(mpt, rlp_int, "Uses")
    Rel(crypto_int, bytes_int, "Uses")
    Rel(rlp_int, bytes_int, "Uses")
```

## Architecture Comparison

### Current State: Multi-Module SBT Build

**Structure:**
- 5 separate SBT projects (node, bytes, crypto, rlp, scalanet)
- Explicit `.dependsOn()` relationships in build.sbt
- Each module compiles independently
- Cross-project dependencies managed by SBT
- Can publish modules separately (currently disabled)

**Pros:**
- Clear module boundaries
- Can version modules independently
- Parallel compilation of independent modules

**Cons:**
- Complex build.sbt configuration
- Slower overall build due to dependency resolution
- IDE integration challenges
- Artificial barriers to refactoring

### Proposed State: Single Module

**Structure:**
- Single SBT project with all code in src/
- Package-based organization for logical separation
- Unified compilation process
- Internal dependencies only

**Pros:**
- Simpler build configuration
- Faster compilation and testing
- Better IDE support
- Easier refactoring across boundaries
- No cross-project dependency issues

**Cons:**
- Less enforced separation (mitigated by clear package structure)
- All code compiled together

## Module Responsibilities

### bytes
**Purpose**: Foundation utilities for byte manipulation  
**Key Classes**: `Hex`, `ByteStringUtils`, `ByteUtils`  
**Dependencies**: None  
**Used By**: crypto, rlp, storage, network

### crypto
**Purpose**: Cryptographic operations for Ethereum  
**Key Classes**: `ECDSASignature`, `ECIESCoder`, `SymmetricCipher`, zkSNARK implementations  
**Dependencies**: bytes  
**Used By**: consensus, evm, blockchain, network

### rlp
**Purpose**: Recursive Length Prefix encoding/decoding  
**Key Classes**: `RLP`, `RLPDerivation`, `RLPImplicits`  
**Dependencies**: bytes  
**Used By**: blockchain, mpt, network, storage

### scalanet
**Purpose**: Low-level networking and peer discovery  
**Key Packages**: TCP networking, Kademlia DHT, peer discovery  
**Dependencies**: None (on other vendored modules)  
**Used By**: network layer, P2P communication

## Related Documentation

- [Vendored Modules Integration Plan](VENDORED_MODULES_INTEGRATION_PLAN.md) - Detailed implementation plan
- [Repository Structure](../REPOSITORY_STRUCTURE.md) - Current repository organization
- [ADR-001: Scala 3 Migration](adr/001-scala-3-migration.md) - Context on why modules were vendored

## References

- [C4 Model](https://c4model.com/) - Architecture diagram notation
- [SBT Multi-Project Builds](https://www.scala-sbt.org/1.x/docs/Multi-Project.html)
- [Scala Package Objects](https://docs.scala-lang.org/tour/package-objects.html)
