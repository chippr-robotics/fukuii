# Fukuii Codebase Map

Quick-reference for navigating the Fukuii Ethereum Classic client. Designed for LLM context windows — scan this file to locate any component without searching.

## Project Root

```
fukuii/
├── README.md                    # Project overview, quick commands, running the node
├── ARCHITECTURE.md              # THIS FILE — codebase navigation map
├── CHANGELOG.md                 # Release history
├── CONTRIBUTING.md              # Contribution guidelines
├── .claude/CLAUDE.md            # Claude Code project instructions (sync bugs, test tiers, boundaries)
├── build.sbt                    # SBT build definition (Scala 3.3.7, JDK 25)
├── version.sbt                  # Version: 0.1.240
│
├── src/                         # Scala source code
├── docs/                        # Documentation (ADRs, runbooks, reports, guides)
├── ops/                         # Deployment environments (Docker, configs, dashboards)
├── ets/                         # Ethereum Test Suite (retesteth integration)
├── docker/                      # 6 Dockerfile variants
├── scripts/                     # Python helper scripts
│
├── bytes/                       # ByteString utilities (git submodule)
├── crypto/                      # Cryptographic primitives (git submodule)
├── rlp/                         # RLP encoding/decoding (git submodule)
└── scalanet/                    # P2P networking library (git submodule)
```

## Source Code (`src/main/scala/com/chipprbots/ethereum/`)

### Sync Pipeline — The Critical Path

```
blockchain/sync/
├── SyncController.scala              # Top-level sync orchestrator (SNAP → Fast → Regular fallback)
├── AdaptiveSyncStrategy.scala        # Strategy selection with fallback chain
├── PeersClient.scala                 # Peer request routing (ETH63/66/68, SNAP)
├── BlockchainHostActor.scala         # Serves blocks/state to remote peers
├── StorageRecoveryActor.scala        # Post-SNAP storage recovery (Bug 20)
│
├── snap/                             # SNAP sync (primary sync mode)
│   ├── SNAPSyncController.scala      # SNAP state machine (2700+ lines, phases: accounts → storage → healing)
│   ├── SyncProgressMonitor.scala     # Progress display (🪱→🧠) and Prometheus metrics
│   ├── SNAPRequestTracker.scala      # Request ID tracking, timeout management
│   ├── MerkleProofVerifier.scala     # Verify SNAP proof responses
│   ├── StorageTask.scala             # Storage task definition (account hash, range, continuation)
│   ├── ChainDownloader.scala         # Parallel header/body download during SNAP
│   └── actors/
│       ├── AccountRangeCoordinator.scala     # Account download orchestration
│       ├── StorageRangeCoordinator.scala     # Storage download + two-phase + flat storage (1100+ lines)
│       ├── ByteCodeCoordinator.scala         # Bytecode download orchestration
│       ├── TrieNodeHealingCoordinator.scala  # Post-download trie repair
│       ├── StorageRangeWorker.scala          # Individual storage request worker
│       └── Messages.scala                    # All coordinator message types
│
├── fast/                             # Fast sync (fallback from SNAP)
│   ├── FastSync.scala                # Fast sync controller
│   ├── SyncStateSchedulerActor.scala # State download scheduler
│   └── PivotBlockSelector.scala      # Pivot block consensus
│
└── regular/                          # Regular sync (block-by-block)
    ├── RegularSync.scala             # Block import orchestrator
    ├── BlockImporter.scala           # Block execution and import
    ├── BlockFetcher.scala            # Block download from peers
    └── StateNodeFetcher.scala        # On-demand state node fetching (GetTrieNodes)
```

### Storage Layer

```
db/
├── storage/
│   ├── MptStorage.scala              # Merkle Patricia Trie storage trait
│   ├── DeferredWriteMptStorage.scala  # Deferred batch writes (~200x speedup)
│   ├── FlatSlotStorage.scala         # O(1) flat slot storage (accountHash++slotHash → value)
│   ├── StateStorage.scala            # Block-versioned MPT (pruning modes)
│   ├── AppStateStorage.scala         # Key-value metadata (sync state, best block, checkpoints)
│   ├── EvmCodeStorage.scala          # Bytecode storage by hash
│   ├── NodeStorage.scala             # Raw MPT node storage
│   ├── Namespaces.scala              # RocksDB column families (n=nodes, c=code, d=flat slots, etc.)
│   └── TransactionalKeyValueStorage.scala  # Base trait for all key-value stores
├── dataSource/
│   ├── DataSource.scala              # Abstract data source interface
│   ├── RocksDbDataSource.scala       # RocksDB implementation
│   └── EphemDataSource.scala         # In-memory (tests)
├── components/
│   ├── Storages.scala                # Storage factory (creates all storage instances)
│   └── StoragesComponent.scala       # Storage trait (declares all storage fields)
└── cache/
    └── LruCache.scala                # LRU cache for MPT nodes
```

### Network Layer

```
network/
├── NetworkPeerManagerActor.scala     # Central peer management + SNAP message routing
├── Peer.scala                        # Peer identity and state
├── PeerId.scala                      # Peer identification
├── discovery/
│   ├── PeerDiscoveryManager.scala    # DHT + DNS peer discovery
│   └── DnsDiscovery.scala            # EIP-1459 DNS ENR tree resolution
├── handshaker/                       # ETH protocol handshake (version negotiation)
├── p2p/
│   └── messages/
│       ├── ETH.scala                 # ETH protocol messages (63/66/68)
│       └── SNAP.scala                # SNAP protocol messages (GetAccountRange, GetStorageRanges, etc.)
└── rlpx/                             # RLPx encrypted transport
```

### EVM & Consensus

```
ledger/
├── BlockExecution.scala              # Block transaction execution
├── BlockPreparator.scala             # Block preparation for mining
├── InMemoryWorldStateProxy.scala     # EVM world state (account balances, storage, code)
└── StxLedger.scala                   # Signed transaction processing

consensus/
├── ConsensusAdapter.scala            # Consensus algorithm abstraction
├── pow/                              # Proof of Work (Ethash/ETChash)
│   ├── PoWMining.scala               # Mining coordinator
│   └── blocks/PoWBlockGenerator.scala
├── eip1559/                          # EIP-1559 base fee (Olympia fork)
├── validators/                       # Block/header/body validators
└── mess/                             # Modified Exponential Subjective Scoring

vm/                                   # EVM implementation (opcodes, gas, memory)
```

### JSON-RPC API

```
jsonrpc/
├── server/
│   ├── http/JsonRpcHttpServer.scala  # Pekko HTTP server
│   └── controllers/                  # RPC method handlers (eth_, net_, personal_, debug_)
├── mcp/McpService.scala              # Model Context Protocol integration
└── client/                           # JSON-RPC client (for testing)
```

### Node Bootstrap

```
nodebuilder/
├── NodeBuilder.scala                 # Main node assembly (wires all components)
└── tooling/                          # CLI argument parsing

domain/
├── Blockchain.scala                  # Blockchain operations (store/retrieve blocks)
├── BlockchainReader.scala            # Read-only blockchain access
├── BlockchainWriter.scala            # Block storage writes
└── Account.scala                     # Account state (balance, nonce, storageRoot, codeHash)

mpt/
├── MerklePatriciaTrie.scala          # Immutable MPT implementation
├── MptNode.scala                     # MPT node types (Branch, Extension, Leaf, Hash)
└── MptVisitors/                      # Trie traversal visitors (state validation, recovery)
```

## Configuration (`src/main/resources/conf/`)

```
conf/
├── base/
│   ├── fukuii.conf                   # Core defaults (network, mining, pruning)
│   ├── sync.conf                     # Sync defaults (batch sizes, timeouts, response bytes)
│   ├── pekko.conf                    # Actor system config (dispatchers, mailboxes)
│   ├── db.conf                       # RocksDB tuning
│   └── logging.conf                  # Logback levels
├── etc.conf                          # ETC mainnet overrides
└── mordor.conf                       # Mordor testnet overrides
```

## Deployment Environments (`ops/`)

```
ops/
├── barad-dur/                        # Production: dual-node + Kong gateway + monitoring
│   ├── docker-compose.yml            # Primary (ETC) + Secondary (Mordor) + Kong + Prometheus + Grafana
│   ├── fukuii-conf-1/                # Primary node config (ETC mainnet)
│   ├── fukuii-conf-2/                # Secondary node config (Mordor testnet)
│   ├── grafana/dashboards/           # Olympia Sync, Dark Lands Network, SNAP Sync, Main
│   └── prometheus/                   # Scrape configs for both nodes
│
├── cirith-ungol/                     # Testing: single Fukuii + Core-Geth on real network
│   ├── docker-compose.yml
│   └── conf/                         # Network-specific configs
│
├── gorgoroth/                        # Private test network: multi-client (Fukuii + Geth + Besu)
│   ├── docker-compose-*.yml          # 3-node, 6-node, mixed-client variants
│   ├── conf/                         # Private network genesis and configs
│   └── test-scripts/                 # Integration tests (consensus, mining, sync, Olympia opcodes)
│
└── tools/                            # CLI tools (deprecated, see OPS-003 ADR — use /fukuii skill)
    ├── fukuii-cli.sh                 # Unified CLI (59KB, covers all environments)
    ├── build-all-images.sh           # Multi-client Docker image builder
    ├── validate-build.sh             # CI/CD build validation
    └── check-docker.sh               # Docker prerequisite checks
```

## Documentation (`docs/`)

```
docs/
├── adr/                              # Architecture Decision Records
│   ├── consensus/                    # CON-001..004 (Ethash, checkpoints, block validation, MESS)
│   ├── infrastructure/               # INF-001..003 (Scala 3, RocksDB, CI/CD)
│   ├── operations/                   # OPS-001..003 (TUI, logging, Claude Code skills)
│   ├── protocols/                    # PROTO-001..003 (snap/1, ETH68, discovery)
│   ├── testing/                      # TEST-001..002 (Ethereum tests, SNAP testing)
│   └── vm/                           # VM-001 (EVM refactoring)
│
├── architecture/                     # System design overview, component diagrams
├── runbooks/                         # Operational procedures (22 guides)
├── troubleshooting/                  # Common issues and solutions (12 guides)
├── reports/                          # Technical reports, field reports, handoff docs
├── analysis/                         # Protocol analysis, investigation reports
├── deployment/                       # Docker, Kong, test network deployment
├── testing/                          # Test strategy, frameworks, ETC test matrix
├── development/                      # Contributing, CI/CD, repository structure
├── api/                              # JSON-RPC API reference
├── guides/                           # MESS configuration, targeted how-tos
├── specifications/                   # Technical specifications
└── examples/                         # Custom chain config template
```

## Test Structure (`src/test/`, `src/it/`)

```
src/test/                             # Unit tests (2,314 tests, ~9 min)
├── scala/com/chipprbots/ethereum/
│   ├── blockchain/sync/              # Sync controller, fast sync, regular sync, SNAP coordinator tests
│   ├── ledger/                       # Block execution, world state tests
│   ├── db/storage/                   # Storage layer tests
│   ├── network/                      # P2P protocol tests
│   ├── consensus/                    # Ethash, validator tests
│   └── testing/                      # Test helpers (TestMptStorage, PeerTestHelpers, etc.)

src/it/                               # Integration tests (~30 min)
├── scala/com/chipprbots/ethereum/
│   ├── blockchain/sync/snap/         # SNAP sync integration
│   ├── ethtest/                      # Ethereum reference tests
│   └── txExecTest/                   # Transaction execution tests
```

## Key Patterns

- **Actor system**: Apache Pekko 1.1.2 — all sync coordination uses actors
- **Dispatchers**: `sync-dispatcher` for sync actors, `default-dispatcher` for HTTP/RPC
- **Storage**: RocksDB with namespace-based column families, `TransactionalKeyValueStorage` base trait
- **Sync pipeline**: SNAP → Fast → Regular fallback chain with escape hatch after 3 cycles
- **Two-phase storage**: Download slots to flat storage first, build MPT tries asynchronously
- **Bootstrap checkpoints**: Config-based trusted block references for instant pivot selection
