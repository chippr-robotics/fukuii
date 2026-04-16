# fukuii: Pluggable Consensus & Multi-Network Architecture

## Status

The Engine API surface has been implemented and validated against the Sepolia network with healthy peers. This document defines the next architectural phase.

## Context

fukuii is a Scala 3 EVM execution client, forked from IOHK Mantis, maintained under Chippr Robotics LLC. Its origin is Ethereum Classic, but its architectural goal is broader: a general-purpose EVM execution engine that can participate in any consensus model or network role without forking or rewriting the engine.

This document consolidates a design direction informed by ETCDEV's Orbita project (Igor Artamonov / splix, ETC Summit 2018), which proposed a standardized sidechain framework for ETC but was never completed beyond prototype. The architectural seam that Orbita lacked — clean separation of execution from consensus — now exists via the Engine API, which fukuii already implements.

## Design Principles

1. **Chain identity is a parameter, not a global assumption.** Network-specific behavior (chain ID, gas schedule, precompiles, transaction types, state commitment scheme) must be configurable per-instance, not compiled in.

2. **Consensus is a pluggable module.** The Engine API is the integration boundary. Any component that can drive `engine_newPayload` and `engine_forkchoiceUpdated` is a valid consensus driver — PoW, PoA, OP-style derivation pipeline, ZK proof verification, or a checkpoint-based sidechain model.

3. **One runtime, multiple chain instances.** A single fukuii process should be capable of syncing multiple networks concurrently, each with its own state database, chain configuration, and consensus driver. This is not just a convenience — it is architecturally natural for L2 operation, where the L2's consensus module reads L1 state to derive its own blocks.

4. **Optionality over premature commitment.** The architecture must not force a decision about which deployment context matters most. ETC mainnet, L2 rollups, private chains, and research networks should all be reachable from the same codebase.

## Architecture: Three Layers

### Layer 1: fukuii-core (Execution Engine)

The reusable, consensus-agnostic core. This is what exists today post-Engine API implementation.

Responsibilities:
- EVM bytecode execution
- State trie management (account storage, world state)
- Transaction validation and execution
- Block construction and state transition
- Engine API JSON-RPC surface (`engine_newPayloadV*`, `engine_forkchoiceUpdatedV*`, `engine_getPayloadV*`)
- Standard JSON-RPC (eth_*, net_*, etc.)

This layer must be parameterized but must NOT contain any consensus logic, L1 data availability reading, or chain-specific transaction type handling.

### Layer 2: fukuii-env (Chain Environment)

A thin but non-trivial configuration layer that defines the execution rules for a specific network deployment. This is where EVM divergences live.

Responsibilities:
- Chain ID and network identity
- Gas schedule and fee mechanics (e.g., EIP-1559 base fee, L2 data gas pricing)
- Precompile registry (standard set + chain-specific additions like OP Stack's L1 block info precompile)
- Transaction type definitions (e.g., OP Stack deposited transactions, ETC classic transaction format)
- State commitment scheme selection (MPT vs. ZK-friendly alternatives like Poseidon trie)
- Fork schedule and activation rules
- Genesis state

Each chain instance gets its own fukuii-env configuration. This is what makes "ETC mainnet" different from "OP-style L2" different from "ZK rollup" at the execution level.

### Layer 3: Consensus Module (Pluggable CL)

Independent binaries or modules that drive fukuii-core via the Engine API. Each consensus module is responsible for determining block ordering and finality within its consensus model.

Target consensus modules:
- **ETC PoW**: Classic Ethash/ETCHASH proof-of-work consensus. The default for ETC mainnet.
- **PoA/Clique**: Authority-based consensus for private networks or development sidechains.
- **OP Derivation**: Reads batches/blobs from a parent L1, derives L2 block sequence, drives fukuii via Engine API. Equivalent to `op-node`.
- **ZK Verifier**: Receives ZK proofs, verifies state transitions, drives fork choice accordingly.
- **Checkpoint (Orbita model)**: Periodic state commitment to a parent chain for finality inheritance. Lightweight consensus for application-specific chains.

## Multi-Network Runtime

A single fukuii process manages multiple concurrent chain instances. Each instance is a tuple of:

```
(state_database, fukuii_env_config, consensus_module)
```

### In-Process L2 Architecture

For L2 deployments, the L2's consensus module reads state directly from the L1 instance's state database — in-process, no external RPC calls. This collapses the standard two-node L2 deployment into one runtime.

```
┌─────────────────────────────────────────────┐
│                fukuii runtime               │
│                                             │
│  ┌─────────────┐      ┌─────────────┐      │
│  │  ETC L1      │      │  Orbita L2   │      │
│  │  Instance    │◄─────│  Instance    │      │
│  │             │ read  │             │      │
│  │ state_db_1  │       │ state_db_2  │      │
│  │ env: etc    │       │ env: orbita │      │
│  │ cl: pow     │       │ cl: deriv   │      │
│  └─────────────┘      └─────────────┘      │
│                                             │
│  Engine API (per-instance)                  │
└─────────────────────────────────────────────┘
```

The L2 consensus module's derivation pipeline reads from the L1 instance's state (checkpoint contracts, data availability) and drives the L2 instance's execution via its own Engine API channel.

### Cross-Instance Communication

Following Orbita's original design:
- **Read access**: Direct in-process state queries from one instance to another. No oracle contracts needed when both chains run in the same process.
- **Write access**: Atomic swap patterns or bridge contract interactions for cross-chain state mutations.
- **Checkpointing**: Child instance periodically commits state roots to parent instance. Reverting the child requires reverting the parent's checkpoint — inherited finality.

## Orbita Legacy

The name "Orbita" originated with ETCDEV's 2018 sidechain proposal. Key concepts being carried forward:

| Orbita 2018 | fukuii Implementation |
|---|---|
| Standardized interfaces for sidechain participants | fukuii-env as parameterized chain config |
| PoA sidechains with mainnet finality | Pluggable consensus modules with checkpoint CL |
| Checkpoint-based security inheritance | Parent-child instance relationship, in-process |
| Cross-orbita oracles for read access | Direct in-process state access across instances |
| Atomic swaps for write access | Bridge/swap contracts deployed per-environment |
| Sidekick prototype (modified geth) | fukuii-core with Engine API separation |
| Service discovery and naming | TBD — instance registry and routing |

The Orbita name should be used for any sidechain or L2 instance running under fukuii. An "orbita" is a chain instance within the fukuii runtime.

## EVM Compatibility Notes

fukuii's ETC lineage means its EVM diverges from mainline Ethereum post-Spurious Dragon:
- No EIP-161 (account clearing / empty account removal)
- Different chain ID handling
- ETCHASH vs. Ethash (modified DAG)
- No EIP-1559 (no base fee mechanism on ETC mainnet)
- No Beacon Chain / PoS fork logic

For L2 or non-ETC deployments, fukuii-env must be able to enable these features selectively. OP Stack compatibility, for example, requires EIP-1559, EIP-4844 blob gas accounting, and deposited transaction types — none of which exist in ETC's fork schedule.

This is the primary engineering cost of the multi-network architecture: ensuring fukuii-core's EVM can be configured to run in either ETC-compatible or ETH-compatible mode (and potentially others) based on fukuii-env parameters.

## Implementation Sequence

Given that the Engine API is validated against Sepolia, the recommended implementation order:

### Phase 1: Extract fukuii-env

Identify all chain-specific constants, fork rules, and execution parameters currently hardcoded or assumed in fukuii-core. Extract them into a configuration structure that can be instantiated per-chain. This is the prerequisite for everything else.

Validation: fukuii connects to both ETC mainnet and Sepolia using different fukuii-env configs, same binary.

### Phase 2: Multi-Instance Runtime

Enable a single fukuii process to manage multiple (state_db, env, engine_api_channel) tuples concurrently. Each instance gets its own Engine API endpoint (different port or namespaced routing).

Validation: One fukuii process syncs two different testnets simultaneously.

### Phase 3: Checkpoint Consensus Module

Build the simplest possible Orbita consensus module: a PoA chain that periodically checkpoints state to a parent fukuii instance. This proves the in-process cross-instance architecture.

Validation: A local PoA orbita running inside the same process as an ETC testnet instance, with checkpoint transactions landing on the testnet.

### Phase 4: OP-Style Derivation Module

Build a consensus module that reads L1 batch data and derives L2 blocks, driving a fukuii instance via Engine API. This requires fukuii-env to support ETH-compatible EVM rules.

Validation: fukuii operating as the execution layer behind an OP-style derivation pipeline on a testnet.

## Open Questions

- **State trie format**: Should fukuii support alternative state commitment schemes (e.g., Verkle, Poseidon) as a fukuii-env parameter, or is this a deeper core change?
- **P2P layer**: Each chain instance presumably needs its own devp2p / libp2p networking. How is this managed in a multi-instance process? Shared transport with chain-specific protocol handlers?
- **Instance lifecycle**: Hot-adding or removing orbita instances at runtime, or only at startup?
- **Resource isolation**: Memory and disk budgets per instance to prevent one chain from starving another.
- **Snap sync for orbitas**: Orbita 2018 listed this as a future goal. What's the minimum viable sync strategy for a new orbita instance?

## References

- ETCDEV Orbita presentation, ETC Summit 2018, Seoul (Igor Artamonov / splix)
- ETCDEV Sidekick prototype: github.com/ETCDEVTeam/sidekick-poc
- Ethereum Engine API specification: github.com/ethereum/execution-apis
- OP Stack architecture: docs.optimism.io
- reth NodeBuilder trait system (reference for pluggable EL design)
