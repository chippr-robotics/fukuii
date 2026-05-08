# Fukuii Application Architecture Overview

**Document Status:** Living Document  
**Last Updated:** 2025-10-25  
**Version:** 1.0

## Table of Contents

1. [Introduction](#introduction)
2. [System Overview](#system-overview)
3. [High-Level Architecture](#high-level-architecture)
4. [Major Systems](#major-systems)
5. [Subsystems](#subsystems)
6. [Data Flow](#data-flow)
7. [Technology Stack](#technology-stack)
8. [Architectural Decision Log](#architectural-decision-log)

## Introduction

Fukuii is an EVM-compliant Ethereum execution layer (EL) client written in Scala 3. It originated as a continuation and re-branding of the Mantis client (Input Output HK) for Ethereum Classic, and is now maintained by Chippr Robotics LLC as a general-purpose EVM engine capable of driving multiple networks through a pluggable consensus architecture. Fukuii retains native PoW/Ethash support for Ethereum Classic while operating as a full post-Merge execution layer for Ethereum mainnet and testnets via the Engine API (V1–V4, through Prague/Electra) when paired with any standard consensus-layer client (Lighthouse, Prysm, Teku, Lodestar, Nimbus).

This document provides a comprehensive overview of Fukuii's current architecture, identifying major systems, subsystems, and their interactions. It serves as a reference for developers, architects, and contributors to understand the system's design and structure. The longer-term direction — a three-layer `fukuii-core` / `fukuii-env` / consensus-module split — is described in the [Pluggable Consensus Vision](pluggable-consensus-vision.md).

## System Overview

Fukuii is a full EVM execution layer implementation that:

- **Maintains an EVM blockchain**: Stores and validates blocks, headers, receipts, and transaction data for ETC or any Engine-API-driven network
- **Executes the EVM through Prague/Electra**: Runs the Ethereum Virtual Machine with support for EIP-1559 (base fee), EIP-3855 (PUSH0), EIP-4844 (blob transactions), EIP-4895 (withdrawals), EIP-4788 (beacon root), EIP-7685 (execution requests), EIP-1153 (transient storage), and the full ECIP-1066 ETC fork schedule through Olympia
- **Synchronizes with the network**: SNAP / fast / regular sync over devp2p for PoW chains; optimistic block import via Engine API `engine_newPayload` for PoS chains
- **Supports pluggable consensus**: Ethash PoW for ETC mainnet and Mordor; Engine API-driven PoS for Ethereum mainnet and Sepolia; architected for future PoA, OP-style derivation, ZK verification, and checkpoint-based sidechains
- **Provides JSON-RPC API**: Exposes standard Ethereum JSON-RPC (`eth_*`, `net_*`, `web3_*`, `debug_*`, `trace_*`, `admin_*`, `txpool_*`, `personal_*`) plus the Engine API (`engine_*` on JWT-authenticated authrpc, default port 8551) and MCP 2025-11-25 for agentic AI control
- **Manages peer connections**: Discovers, connects to, and communicates with other nodes via RLPx over devp2p, with `eth/63`, `eth/66`, `eth/68`, and `snap/1` capability support

### Supported Networks

| Network          | Chain ID  | Consensus        | Status                                      |
|------------------|-----------|------------------|---------------------------------------------|
| Ethereum Classic | 61        | PoW (Ethash)     | Full sync (SNAP / fast / regular)           |
| Mordor           | 63        | PoW (Ethash)     | Full sync (SNAP / fast / regular)           |
| Sepolia          | 11155111  | PoS (Engine API) | Validated — 21+ EL peers, Lighthouse CL     |
| Ethereum Mainnet | 1         | PoS (Engine API) | Configuration available                     |

## High-Level Architecture

Fukuii follows a modular, layered architecture built on the Actor model using Apache Pekko (Scala 3 compatible fork of Akka). The system can be visualized as follows:

```mermaid
graph TB
    subgraph "External Interfaces"
        JSONRPC[JSON-RPC API<br/>HTTP / WS / IPC]
        ENGINE[Engine API<br/>authrpc 8551 JWT]
        CLI[Command Line Interface]
        P2P[P2P Network Layer<br/>devp2p / RLPx]
    end

    subgraph "External Peers"
        CL[Consensus Layer Client<br/>Lighthouse / Prysm / Teku / Lodestar / Nimbus]
        ELPEERS[EL Peers<br/>eth/63 · eth/66 · eth/68 · snap/1]
    end

    subgraph "Application Layer"
        APP[App Entry Point<br/>Fukuii.scala]
        NODE[Node Builder<br/>StdNode/TestNode]
    end

    subgraph "Core Systems"
        BLOCKCHAIN[Blockchain System]
        CONSENSUS[Consensus System<br/>Pluggable: PoW / Engine API]
        NETWORK[Network System]
        LEDGER[Ledger System]
        VM[Virtual Machine]
    end

    subgraph "Supporting Systems"
        DB[Database Layer]
        CRYPTO[Cryptography]
        KEYSTORE[Keystore]
        METRICS[Metrics & Monitoring]
    end

    CL --> ENGINE
    ELPEERS --> P2P

    JSONRPC --> APP
    ENGINE --> APP
    ENGINE --> LEDGER
    ENGINE --> BLOCKCHAIN
    CLI --> APP
    P2P --> NETWORK

    APP --> NODE
    NODE --> BLOCKCHAIN
    NODE --> CONSENSUS
    NODE --> NETWORK
    NODE --> LEDGER

    BLOCKCHAIN --> DB
    LEDGER --> VM
    LEDGER --> DB
    CONSENSUS --> BLOCKCHAIN
    NETWORK --> P2P

    VM --> CRYPTO
    LEDGER --> CRYPTO
    KEYSTORE --> CRYPTO

    BLOCKCHAIN --> METRICS
    NETWORK --> METRICS
```

## Major Systems

### 1. Application Layer

**Location:** `com.chipprbots.ethereum.App`, `com.chipprbots.ethereum.Fukuii`

The Application Layer serves as the entry point for Fukuii. It handles:
- Command-line argument parsing
- Mode selection (standard node, test node, CLI tools, faucet, etc.)
- System initialization and startup
- Lifecycle management

```mermaid
graph LR
    A[App.scala] --> B{Command}
    B -->|fukuii| C[Fukuii.main]
    B -->|cli| D[CLI Launcher]
    B -->|faucet| E[Faucet]
    B -->|vm-server| F[VM Server]
    B -->|keytool| G[Key Tool]
    
    C --> H[StdNode]
    C --> I[TestNode]
```

**Key Components:**
- `App.scala`: Main entry point with command routing
- `Fukuii.scala`: Core node initialization
- `BootstrapDownload.scala`: Bootstrap data loader

### 2. Node Builder System

**Location:** `com.chipprbots.ethereum.nodebuilder`

The Node Builder system is responsible for constructing and configuring all components of a Fukuii node. It uses a builder pattern with trait composition to assemble the various subsystems.

**Key Components:**
- `NodeBuilder.scala`: Core builder with configuration traits
- `StdNode.scala`: Standard production node implementation
- `TestNode.scala`: Test mode node for development

**Startup Sequence:**
1. Initialize metrics client
2. Fix/validate database
3. Load genesis data
4. Run database consistency check
5. Start peer manager
6. Start server (P2P listener)
7. Start sync controller
8. Start mining (if enabled)
9. Start peer discovery
10. Start JSON-RPC servers (HTTP/IPC)

### 3. Blockchain System

**Location:** `com.chipprbots.ethereum.blockchain`

The Blockchain system manages the chain of blocks, including storage, validation, and synchronization.

```mermaid
graph TB
    subgraph "Blockchain System"
        BH[BlockchainHostActor]
        SC[SyncController]
        GDL[GenesisDataLoader]
        
        subgraph "Sync Subsystem"
            RS[RegularSync]
            BI[BlockImporter]
            BF[BlockFetcher]
            BB[BlockBroadcaster]
        end
        
        subgraph "Data Management"
            BD[BlockData]
            BQ[BlockQueue]
            BL[Blacklist]
        end
    end
    
    SC --> RS
    RS --> BI
    RS --> BF
    BI --> BB
    
    BH --> SC
    BH --> BD
    SC --> BQ
    SC --> BL
```

**Key Subsystems:**
- **Sync Subsystem**: Synchronizes blockchain state with peers
  - Regular sync for ongoing synchronization
  - Fast sync for initial blockchain download
  - Block import and validation
  - Block broadcasting to peers
  
- **Data Management**: Handles block storage and retrieval
  - Block headers, bodies, and receipts
  - Block number to hash mapping
  - Chain weight tracking

### 4. Consensus System

**Location:** `com.chipprbots.ethereum.consensus`

The Consensus system implements the rules for achieving agreement on the blockchain state. Consensus in Fukuii is **pluggable**: the engine supports Ethash PoW natively (for ETC mainnet and Mordor) and delegates to an external consensus-layer client via the Engine API for post-Merge PoS networks (Ethereum mainnet, Sepolia). The longer-term three-layer architecture — `fukuii-core` (execution) / `fukuii-env` (chain parameters) / consensus module — is described in the [Pluggable Consensus Vision](pluggable-consensus-vision.md).

```mermaid
graph TB
    subgraph "Consensus System"
        C[Consensus Interface]
        CA[ConsensusAdapter]
        CI[ConsensusImpl]
        
        subgraph "Mining"
            MB[MiningBuilder]
            MC[MiningConfig]
            MINER[Miner Actors]
        end
        
        subgraph "Validation"
            BV[Block Validators]
            HV[Header Validators]
            DV[Difficulty Validators]
        end
        
        subgraph "PoW"
            ETHASH[Ethash Algorithm]
            CACHE[DAG Cache]
        end
    end
    
    C --> CA
    CA --> CI
    CI --> BV
    CI --> HV
    
    MB --> MINER
    MINER --> ETHASH
    ETHASH --> CACHE
    
    CI --> DV
    DV --> ETHASH
```

**Key Components:**
- **Consensus Interface**: Defines consensus operations
- **Validators**: Validate blocks, headers, and difficulty
- **Mining**: Proof-of-Work mining implementation
  - Ethash algorithm support
  - DAG generation and caching
  - Block generation and sealing
- **Difficulty Calculation**: Computes block difficulty based on network rules

### 5. Network System

**Location:** `com.chipprbots.ethereum.network`

The Network system handles all peer-to-peer communication, discovery, and protocol implementation.

```mermaid
graph TB
    subgraph "Network System"
        PM[PeerManagerActor]
        SA[ServerActor]
        
        subgraph "Peer Management"
            PA[PeerActor]
            EPM[NetworkPeerManagerActor]
            PS[PeerStatistics]
            KN[KnownNodesManager]
        end
        
        subgraph "Discovery"
            PDM[PeerDiscoveryManager]
            DS[DiscoveryService]
        end
        
        subgraph "Protocol"
            HS[Handshaker]
            RLPX[RLPx Protocol]
            P2P[P2P Messages]
        end
        
        subgraph "Connection"
            AUTH[AuthHandshaker]
            SSL[SSL Context]
        end
    end
    
    PM --> PA
    PM --> EPM
    PM --> KN
    
    SA --> PM
    
    PDM --> DS
    PDM --> PM
    
    PA --> HS
    HS --> RLPX
    HS --> AUTH
    RLPX --> P2P
```

**Key Subsystems:**
- **Peer Management**: Manages connections to other nodes
  - Connection establishment and maintenance
  - Peer blacklisting
  - Peer statistics and scoring
  
- **Discovery**: Finds and connects to peers
  - UDP-based discovery protocol
  - Known nodes persistence
  - Bootstrap nodes
  
- **Protocol Layer**: Implements Ethereum wire protocol
  - RLPx encryption and framing
  - ETH protocol messages
  - Handshaking and capability negotiation

### 6. Ledger System

**Location:** `com.chipprbots.ethereum.ledger`

The Ledger system manages state transitions and transaction execution.

```mermaid
graph TB
    subgraph "Ledger System"
        BP[BlockPreparator]
        BE[BlockExecution]
        BV[BlockValidation]
        
        subgraph "State Management"
            WSP[WorldStateProxy]
            IWSP[InMemoryWorldStateProxy]
            SMP[SimpleMapProxy]
        end
        
        subgraph "Transaction Processing"
            TR[TxResult]
            SL[StxLedger]
        end
        
        subgraph "Block Processing"
            BR[BlockResult]
            BRC[BlockRewardCalculator]
            PB[PreparedBlock]
        end
    end
    
    BP --> WSP
    BP --> PB
    BE --> BP
    BE --> TR
    BE --> BR
    
    WSP --> IWSP
    IWSP --> SMP
    
    SL --> TR
    BRC --> BR
```

**Key Components:**
- **Block Preparator**: Prepares blocks for execution
- **Block Execution**: Executes transactions in blocks
- **World State Proxy**: Manages Ethereum world state
  - Account balances and nonces
  - Contract storage
  - Account code
- **Transaction Processing**: Executes individual transactions
- **Block Rewards**: Calculates mining rewards

### 7. Virtual Machine (VM)

**Location:** `com.chipprbots.ethereum.vm`

The VM system implements the Ethereum Virtual Machine for smart contract execution.

```mermaid
graph TB
    subgraph "Virtual Machine"
        VM[VM Core]
        
        subgraph "Execution"
            PROG[Program]
            PS[ProgramState]
            PC[ProgramContext]
        end
        
        subgraph "Operations"
            OC[OpCodes]
            PREC[Precompiled Contracts]
        end
        
        subgraph "Environment"
            MEM[Memory]
            STACK[Stack]
            STORAGE[Storage]
        end
        
        subgraph "Configuration"
            EVC[EvmConfig]
            BC[BlockchainConfigForEvm]
        end
    end
    
    VM --> PROG
    PROG --> PS
    PS --> MEM
    PS --> STACK
    PS --> STORAGE
    
    PROG --> PC
    PROG --> OC
    PROG --> PREC
    
    VM --> EVC
    EVC --> BC
```

**Key Components:**
- **VM Core**: Main execution engine
- **OpCodes**: Implements all EVM opcodes
- **Program State**: Tracks execution state (stack, memory, storage)
- **Precompiled Contracts**: Native implementations of special contracts
- **EVM Config**: Configuration for different hard forks (Frontier, Homestead, Byzantium, Constantinople, Istanbul, Berlin, London, etc.)

### 8. JSON-RPC System

**Location:** `com.chipprbots.ethereum.jsonrpc`

The JSON-RPC system provides the standard Ethereum JSON-RPC API for external applications.

```mermaid
graph TB
    subgraph "JSON-RPC System"
        CTRL[JsonRpcController]
        
        subgraph "Transport"
            HTTP[HTTP Server]
            IPC[IPC Server]
        end
        
        subgraph "Services"
            ETH[Eth Service]
            NET[Net Service]
            WEB3[Web3 Service]
            PERSONAL[Personal Service]
            DEBUG[Debug Service]
            TRACE[Trace Service]
            TXPOOL[Txpool Service]
            ADMIN[Admin Service]
            ENGINESVC[Engine Service<br/>authrpc 8551 JWT]
            MCP[MCP Service<br/>MCP 2025-11-25]
            TEST[Test Service]
            FUKUII[Fukuii Service]
        end
        
        subgraph "Components"
            FM[FilterManager]
            RB[ResolveBlock]
            HC[HealthChecker]
        end
    end
    
    HTTP --> CTRL
    IPC --> CTRL
    
    CTRL --> ETH
    CTRL --> NET
    CTRL --> WEB3
    CTRL --> PERSONAL
    CTRL --> DEBUG
    CTRL --> TRACE
    CTRL --> TXPOOL
    CTRL --> ADMIN
    CTRL --> ENGINESVC
    CTRL --> MCP
    CTRL --> TEST
    CTRL --> FUKUII
    
    ETH --> FM
    ETH --> RB
    CTRL --> HC
```

**Key Services:**
- **Eth Service**: Core Ethereum RPC methods
  - Block queries (eth_getBlockByNumber, eth_getBlockByHash)
  - Transaction submission (eth_sendRawTransaction)
  - State queries (eth_getBalance, eth_getCode, eth_call, eth_getProof)
  - Fee history (eth_feeHistory, eth_maxPriorityFeePerGas, eth_blobBaseFee)
  - Mining methods (eth_getWork, eth_submitWork) — PoW chains only

- **Net Service**: Network information and peer management
- **Web3 Service**: Client version and utilities
- **Debug Service**: Raw block / header / receipt / transaction access, `debug_traceTransaction`, `debug_traceCall`, intermediate-root inspection
- **Trace Service**: Geth-style `trace_transaction`, `trace_block`, `trace_call`, `trace_filter` for EVM-level introspection
- **Txpool Service**: `txpool_status`, `txpool_content`, `txpool_inspect` mempool views
- **Admin Service**: Peer manipulation (`addPeer`, `removePeer`), datadir query, log-level control, chain import/export
- **Engine Service**: post-Merge CL/EL interface (see "Engine API / Consensus Layer Integration" below)
- **MCP Service**: Model Context Protocol 2025-11-25 — 15 tools, 9 resources, guided prompts for agentic AI clients
- **Personal Service**: Account management
- **Test Service**: Testing utilities (test mode only)

### 9. Engine API / Consensus Layer Integration

**Location:** `com.chipprbots.ethereum.consensus.engine`

The Engine API is the standardized interface between an execution layer (EL) and a consensus layer (CL) for post-Merge Ethereum networks. Fukuii implements Engine API V1 through V4 (Prague/Electra), enabling it to operate as a full execution layer paired with any CL client. This is the primary seam that makes Fukuii a general-purpose EVM runtime rather than an ETC-specific client — see [Pluggable Consensus Vision](pluggable-consensus-vision.md).

**Key Components:**
- **EngineApiService**: core request handling for all `engine_*` methods
- **EngineApiController**: JSON-RPC routing on the authrpc port
- **ForkChoiceManager**: maintains head / safe / finalized pointers from the CL
- **PostMergeBlockHeaderValidator**: PoS-era header rules (difficulty=0, nonce=0, empty ommers)
- **TransitionBlockHeaderValidator**: merge-transition block validation
- **JwtAuthenticator**: RFC 7519 JWT authentication for authrpc requests

**Implemented Methods:**
- `engine_newPayloadV1`–`V4` — validate and execute a CL-built payload (with EIP-4844 blob gas tracking and EIP-7685 execution-requests validation in V3/V4)
- `engine_forkchoiceUpdatedV1`–`V3` — update head / safe / finalized pointers, optionally start payload building
- `engine_getPayloadV1`–`V4` — return a locally built payload
- `engine_exchangeCapabilities` — capability negotiation
- `engine_getClientVersionV1` — client identity
- `engine_getBlobsV1` — blob retrieval for EIP-4844
- `engine_getPayloadBodiesByHashV1` / `engine_getPayloadBodiesByRangeV1` — payload body lookup

**Bind & lifecycle:** the authrpc server binds **synchronously** with a 10-second timeout at node startup — the node fails loudly rather than silently if the port is unavailable. Default port: 8551. See the [Startup Sequence](#system-overview) notes in the project README for full detail.

**Operational semantics:**
- **Optimistic block import**: when `engine_newPayload` arrives with an unknown parent, the payload is accepted as `SYNCING` and imported once the state becomes available, supporting checkpoint-sync-style operation where the CL supplies the chain tip
- **Post-Merge validation**: PoS headers are validated for `difficulty = 0`, `nonce = 0`, empty ommers, presence of `withdrawalsRoot` (EIP-4895), `blobGasUsed` / `excessBlobGas` (EIP-4844), and `requestsHash` (EIP-7685)
- **EIP support**: EIP-3651 (warm COINBASE), EIP-4895 (withdrawals), EIP-4844 (blob transactions), EIP-4788 (beacon root contract), EIP-7685 (execution requests), EIP-6122 (timestamp ForkID), EIP-1153 (transient storage), EIP-5656 (MCOPY)

The feature-level summary of Engine API capabilities, along with `ops/barad-dur/sepolia/` deployment instructions for pairing with Lighthouse, is maintained in the project [`README.md`](https://github.com/chippr-robotics/fukuii/blob/develop/README.md).

### 10. Database System

**Location:** `com.chipprbots.ethereum.db`

The Database system provides persistent storage for blockchain data.

```mermaid
graph TB
    subgraph "Database System"
        DS[DataSource]
        
        subgraph "Storage Components"
            BHS[BlockHeadersStorage]
            BBS[BlockBodiesStorage]
            BRS[BlockReceiptsStorage]
            BNM[BlockNumberMapping]
            ASS[AppStateStorage]
            NS[NodeStorage]
            TS[TransactionStorage]
        end
        
        subgraph "State Storage"
            MPT[Merkle Patricia Trie]
            SMPT[StateMPT]
            CMPT[ContractStorageMPT]
            EMPT[EvmCodeStorage]
        end
        
        subgraph "Backend"
            ROCKS[RocksDB]
        end
        
        subgraph "Pruning"
            PM[PruningMode]
            ARCH[Archive Mode]
            BASIC[Basic Pruning]
        end
    end
    
    DS --> ROCKS
    
    DS --> BHS
    DS --> BBS
    DS --> BRS
    DS --> BNM
    DS --> ASS
    DS --> NS
    DS --> TS
    
    DS --> MPT
    MPT --> SMPT
    MPT --> CMPT
    MPT --> EMPT
    
    DS --> PM
    PM --> ARCH
    PM --> BASIC
```

**Key Components:**
- **DataSource**: Abstraction over storage backend (RocksDB)
- **Block Storage**: Stores blocks, headers, bodies, receipts
- **State Storage**: Merkle Patricia Trie for world state
- **App State**: Stores best block, sync state
- **Pruning**: Configurable state pruning strategies

## Subsystems

### Transaction Management

**Location:** `com.chipprbots.ethereum.transactions`

- `PendingTransactionsManager`: Manages the transaction pool (mempool)
- `TransactionHistoryService`: Tracks transaction history

### Ommers Management

**Location:** `com.chipprbots.ethereum.ommers`

- `OmmersPool`: Manages uncle blocks (ommers) for inclusion in new blocks

### Cryptography

**Location:** `com.chipprbots.ethereum.crypto`

- ECDSA signature generation and verification
- Keccak-256 hashing
- Key generation and management
- Secure random number generation

### Keystore

**Location:** `com.chipprbots.ethereum.keystore`

- `KeyStore`: Manages encrypted private keys
- `KeyStoreImpl`: File-based keystore implementation
- Passphrase-based encryption

### RLP Encoding

**Location:** `com.chipprbots.ethereum.rlp`

- Recursive Length Prefix encoding/decoding
- Used throughout the system for serialization

### Merkle Patricia Trie

**Location:** `com.chipprbots.ethereum.mpt`

- Implementation of Ethereum's modified Merkle Patricia Trie
- Used for state storage and proof generation

### Metrics & Monitoring

**Location:** `com.chipprbots.ethereum.metrics`

- Kamon-based metrics collection
- Prometheus exposition
- Performance monitoring
- Health checks

### Health Check

**Location:** `com.chipprbots.ethereum.healthcheck`

- Node health monitoring
- Readiness and liveness probes
- Integration with JSON-RPC health endpoints

### CLI Tools

**Location:** `com.chipprbots.ethereum.cli`

- Private key generation
- Address utilities
- Development tools

### Faucet

**Location:** `com.chipprbots.ethereum.faucet`

- Test network faucet implementation
- Automated ETH distribution for testing

### External VM

**Location:** `com.chipprbots.ethereum.extvm`

- External VM server for testing
- VM conformance testing

### Fork ID

**Location:** `com.chipprbots.ethereum.forkid`

- EIP-2124 fork identifier implementation
- Network compatibility checks

### Domain Models

**Location:** `com.chipprbots.ethereum.domain`

Core domain objects used throughout the system:
- `Block`, `BlockHeader`, `BlockBody`
- `Transaction`, `SignedTransaction`
- `Account`, `Address`
- `Receipt`, `Log`
- `Blockchain`, `BlockchainConfig`

### Utilities

**Location:** `com.chipprbots.ethereum.utils`

- Configuration management
- Logging
- Byte utilities
- Numeric utilities
- Time utilities

## Data Flow

### Block Synchronization Flow

```mermaid
sequenceDiagram
    participant P as Peer
    participant PM as PeerManager
    participant SC as SyncController
    participant BI as BlockImporter
    participant L as Ledger
    participant DB as Database
    
    P->>PM: Announce new block
    PM->>SC: NewBlock message
    SC->>SC: Validate block header
    SC->>BI: Import block
    BI->>L: Execute block
    L->>L: Execute transactions
    L->>L: Validate state root
    BI->>DB: Save block
    DB-->>BI: Saved
    BI-->>SC: Block imported
    SC->>PM: Broadcast to peers
```

### Transaction Submission Flow

```mermaid
sequenceDiagram
    participant C as Client (JSON-RPC)
    participant RPC as JSON-RPC Controller
    participant PTM as PendingTransactionsManager
    participant PM as PeerManager
    participant MINER as Miner
    
    C->>RPC: eth_sendRawTransaction
    RPC->>PTM: Add transaction
    PTM->>PTM: Validate transaction
    PTM->>PM: Broadcast to peers
    PTM->>MINER: Notify new tx
    MINER->>MINER: Include in next block
    RPC-->>C: Transaction hash
```

### Block Mining Flow

```mermaid
sequenceDiagram
    participant MINER as Miner
    participant L as Ledger
    participant C as Consensus
    participant PTM as PendingTransactionsManager
    participant DB as Database
    participant PM as PeerManager
    
    MINER->>PTM: Get pending transactions
    PTM-->>MINER: Transactions
    MINER->>L: Prepare block
    L->>L: Execute transactions
    L-->>MINER: Prepared block
    MINER->>C: Mine block (PoW)
    C->>C: Calculate nonce
    C-->>MINER: Sealed block
    MINER->>DB: Save block
    MINER->>PM: Broadcast block
```

### Smart Contract Execution Flow

```mermaid
sequenceDiagram
    participant RPC as JSON-RPC
    participant L as Ledger
    participant VM as EVM
    participant WS as WorldState
    participant DB as Database
    
    RPC->>L: Execute call/transaction
    L->>L: Create execution context
    L->>VM: Execute bytecode
    loop For each opcode
        VM->>VM: Execute opcode
        VM->>WS: Read/write state
        WS->>DB: Load/save storage
    end
    VM-->>L: Execution result
    L->>L: Apply state changes
    L->>DB: Commit state
    L-->>RPC: Result
```

## Technology Stack

### Languages & Frameworks
- **Scala 3.3.7** (LTS): Primary programming language
- **Apache Pekko**: Actor-based concurrency framework (Scala 3 compatible fork of Akka)
- **Cats Effect 3**: Functional effect system
- **fs2**: Functional streaming library
- **Cats**: Functional programming library

### Storage
- **RocksDB**: Embedded key-value store for blockchain data

### Networking
- **Apache Pekko IO** (`pekko-io`): Low-level TCP/UDP networking
- **Pekko HTTP 1.1.0**: HTTP server for JSON-RPC and Engine API (authrpc)
- **Engine API / CL integration**: JWT-authenticated `engine_*` interface (default port 8551) for pairing with any consensus-layer client
- **UDP/TCP**: Network protocols (UDP for peer discovery, TCP for RLPx/devp2p)

### Cryptography
- **Bouncy Castle**: Cryptographic primitives
- **Keccak**: Hash function

### Serialization
- **RLP**: Recursive Length Prefix encoding
- **JSON**: JSON-RPC serialization

### Monitoring & Metrics
- **Kamon**: Metrics collection
- **Prometheus**: Metrics exposition

### Testing
- **ScalaTest**: Unit and integration testing
- **ScalaCheck**: Property-based testing

### Build & Deployment
- **SBT**: Build tool
- **Docker**: Containerization
- **Nix**: Reproducible builds
- **GitHub Actions**: CI/CD

### Configuration
- **Typesafe Config (HOCON)**: Configuration management

## Architectural Decision Log

This section documents significant architectural decisions made during the development of Fukuii. Each entry should include the context, decision, and rationale.

**Note:** Detailed ADRs are maintained in the [`docs/adr/`](../adr/README.md) directory. This section provides summaries of key decisions.

### ADL-001: Continuation of Mantis as Fukuii

**Date:** 2024-10-24  
**Status:** Accepted  
**Context:** Mantis development by IOHK had slowed down, but the codebase was solid and well-architected.  
**Decision:** Fork Mantis and continue development as Fukuii under Chippr Robotics LLC.  
**Consequences:**
- Maintains compatibility with Ethereum Classic network
- Preserves years of development work
- Requires rebranding throughout codebase
- Enables independent development and modernization

### ADL-002: Actor-Based Architecture (Apache Pekko, migrated from Akka)

**Date:** Historical (inherited from Mantis); migrated to Pekko 2024/2025
**Status:** Accepted
**Context:** Ethereum nodes require high concurrency and need to handle multiple simultaneous operations (network I/O, block processing, mining, RPC requests). The original Mantis codebase used Akka; Akka's change of licensing model and lack of Scala 3 support prompted migration to its community fork, Apache Pekko.
**Decision:** Use the actor model for concurrency management, now running on Apache Pekko 1.1.2 (Pekko HTTP 1.1.0) rather than Akka.
**Consequences:**
- Clear separation of concerns through actors
- Natural message-passing for async operations
- Built-in supervision and fault tolerance
- Scala 3 compatibility via Pekko
- Dedicated `sync-dispatcher` isolates all sync actors from the default dispatcher, preventing JSON-RPC/TCP I/O starvation during heavy sync load — any sync actor child must be created with `.withDispatcher("sync-dispatcher")`
- Learning curve for contributors unfamiliar with actors
- Some complexity in tracking message flows

### ADL-003: RocksDB as Primary Storage Backend

**Date:** Historical (inherited from Mantis)  
**Status:** Accepted  
**Context:** Need for high-performance, persistent key-value storage for blockchain data.  
**Decision:** Use RocksDB as the primary storage backend.  
**Consequences:**
- Excellent read/write performance
- Efficient storage with compression
- Well-tested in production blockchain applications
- Platform-specific native library dependency
- Limited to single-node deployment

### ADL-004: Scala as Implementation Language

**Date:** Historical (inherited from Mantis)  
**Status:** Accepted  
**Context:** Need for a language that supports functional programming, strong typing, and JVM interoperability.  
**Decision:** Implement Fukuii in Scala.  
**Consequences:**
- Strong type system catches errors at compile time
- Functional programming paradigms for safer code
- Excellent concurrency support with Apache Pekko
- JVM ecosystem and tooling
- Slower compilation times compared to some languages
- Smaller contributor pool than mainstream languages

### ADL-005: Modular Package Structure

**Date:** Historical (inherited from Mantis)  
**Status:** Accepted  
**Context:** Large codebase requires clear organization and separation of concerns.  
**Decision:** Organize code into distinct packages by functionality (blockchain, consensus, network, ledger, vm, etc.).  
**Consequences:**
- Clear boundaries between subsystems
- Easier to understand and navigate codebase
- Enables parallel development
- Reduces coupling between modules
- Requires discipline to maintain boundaries

### VM-002: Implementation of EIP-3529 (Reduction in Refunds)

**Date:** 2024-10-25  
**Status:** Accepted  
**Context:** EIP-3529 changes gas refund mechanics to reduce state bloat and prevent gas refund gaming.  
**Decision:** Implement EIP-3529 as part of the Mystique hard fork with reduced `R_sclear` (4,800 gas), zero `R_selfdestruct`, and reduced maximum refund quotient (gasUsed / 5).  
**Consequences:**
- Reduced state bloat from "gas tokens"
- More accurate gas economics
- Breaking change for contracts relying on refunds
- Improved network security

**See:** [Full VM-002 documentation](../adr/vm/VM-002-eip-3529-implementation.md)

### ADL-006: Engine API as the Pluggable-Consensus Seam

**Date:** 2025
**Status:** Accepted
**Context:** Fukuii's original consensus was ETC-specific (Ethash PoW). Adopting the Ethereum Engine API — the standard EL/CL boundary since the Merge — gives the execution engine a well-defined seam over which any consensus driver can be connected, without forking or rewriting the EVM core.
**Decision:** Implement Engine API V1–V4 (through Prague/Electra) as the primary consensus-driver interface, and treat it as the architectural boundary for the three-layer `fukuii-core` / `fukuii-env` / consensus-module design described in [pluggable-consensus-vision.md](pluggable-consensus-vision.md).
**Consequences:**
- Fukuii operates as a full EVM execution layer for post-Merge Ethereum networks paired with any CL client (Lighthouse, Prysm, Teku, Lodestar, Nimbus)
- Sepolia validated with 21+ EL peers and a Lighthouse CL; Ethereum mainnet configuration is available
- ETC PoW (Ethash) is retained as a first-class consensus module, not a special case
- Opens the door to future consensus modules: PoA/Clique, OP-style derivation, ZK proof verification, and checkpoint-based sidechains (Orbita)
- Introduces an additional attack surface (JWT-authenticated authrpc); requires operators to manage and protect the shared JWT secret

---

## Future Enhancements

Areas identified for potential architectural improvements:

1. **Observability**: Enhanced metrics, tracing, and logging
2. **Performance**: Profiling and optimization of critical paths
3. **Modularity**: Further decoupling of subsystems
4. **Testing**: Increased test coverage and integration tests
5. **Documentation**: Expanded API and developer documentation
6. **Scalability**: Optimizations for large-scale deployments

---

**Note:** This is a living document. As architectural decisions are made or the system evolves, this document should be updated to reflect the current state of the system. Contributors should add new ADL entries for significant architectural changes.
