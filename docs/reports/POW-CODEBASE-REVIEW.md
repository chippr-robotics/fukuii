# M-020: go-ethereum Pre-Merge PoW Codebase Review

**Date:** 2026-03-26
**Reviewer:** Chris Mercer (AI-assisted)
**Scope:** Compare Fukuii's PoW consensus implementation against go-ethereum v1.10.26 (last pre-merge release) to identify missing hardening, patterns, or optimizations relevant to ETC.
**Branch:** `march-onward` at `e7ab739c6`

---

## Executive Summary

Fukuii's PoW consensus layer is **functionally complete and correct** for ETC. The Ethash implementation, difficulty calculation, block rewards, and uncle handling all follow the expected patterns. Key areas where Fukuii diverges from go-ethereum are documented below — most are **intentional** (different language, different architecture) or **irrelevant** (features removed from ETC at various forks).

**Critical findings:** 0
**Recommendations:** 5 (all low priority)

---

## 1. Ethash Implementation

### go-ethereum Pattern (consensus/ethash/)
- `ethash.go`: Cache + dataset management with LRU eviction
- `algorithm.go`: hashimoto, hashimotoFull, hashimotoLight, makeCache, generateDataset
- `sealer.go`: Mining loop with nonce search, remote mining support
- `consensus.go`: Block header verification, CalcDifficulty, CalcBaseFee
- DAG generation: Background goroutine, memory-mapped files, 2 epoch prefetch

### Fukuii Implementation
- `EthashUtils.scala`: hashimotoLight, hashimoto, makeCache, calcDatasetItem, seed, epoch, cacheSize, dagSize
- `EthashMiner.scala`: CPU mining worker with DAG lookup
- `EthashDAGManager.scala`: DAG lifecycle management with epoch prefetch
- `EthashBlockHeaderValidator.scala`: PoW verification via cache lookup
- `PoWBlockHeaderValidator.scala`: Extends `BlockHeaderValidatorSkeleton` with Ethash check

### Comparison

| Feature | go-ethereum | Fukuii | Status |
|---------|-------------|--------|--------|
| hashimotoLight (verification) | Yes | Yes | Match |
| hashimotoFull (mining) | Yes | Yes (via DAG array) | Match |
| Cache LRU (3 epochs) | Yes | Yes (`PowCacheData`) | Match |
| DAG prefetch (next epoch) | Yes (background goroutine) | Yes (`EthashDAGManager`) | Match |
| Memory-mapped DAG files | Yes | No (in-memory arrays) | Intentional — JVM handles memory differently |
| Remote mining (Stratum) | No (separate miner) | No | Both lack Stratum |
| ECIP-1099 (doubled epoch) | N/A (ETH) | Yes | ETC-specific, correct |
| nonce range partitioning | Yes (for parallel mining) | No | Low priority — CPU mining only |

### Recommendation
**R-001 (LOW):** Consider adding nonce range partitioning for multi-threaded CPU mining. go-ethereum partitions the nonce space across goroutines. Fukuii's `EthashMiner` uses a single-threaded search. Impact: mining throughput only — irrelevant for nodes that don't mine.

---

## 2. Difficulty Calculation

### go-ethereum Pattern (consensus/ethash/consensus.go)
- `CalcDifficulty()`: Routes to era-specific calculators based on block number
- Supports: Frontier, Homestead, Byzantium, Constantinople, Muir Glacier, Arrow Glacier, Gray Glacier
- Each era: `makeDifficultyCalculator(bombDelay)` with closure pattern
- EIP-2: Homestead difficulty adjustment (uncle factor)
- EIP-100: Byzantium+ adjustment (1 or 2 for uncle presence)
- Bomb delays at each fork

### Fukuii Implementation
- `EthashDifficultyCalculator.scala`: Single `calculateDifficulty()` with era-aware branching
- `TargetTimeDifficultyCalculator.scala`: Alternative for custom target times
- Constants: `DifficultyBoundDivision=2048`, `MinimumDifficulty=131072`
- Bomb delays: `ExpDifficultyPeriod=100_000`, Byzantine/Constantinople/MuirGlacier relaxation constants
- `DifficultyCalculator` trait: Clean interface for pluggable implementations

### Comparison

| Feature | go-ethereum | Fukuii | Status |
|---------|-------------|--------|--------|
| Frontier difficulty | Yes | Yes | Match |
| EIP-2 (Homestead) | Yes | Yes | Match |
| EIP-100 (Byzantium) | Yes | Yes | Match |
| Bomb delay tracking | Per-fork constants | Per-fork constants | Match |
| ECIP-1041 (bomb removal) | N/A (ETH) | Yes (deactivated) | ETC-specific, correct |
| ECIP-1010 (bomb delay) | N/A (ETH) | Yes | ETC-specific, correct |
| Minimum difficulty floor | 131072 | 131072 | Match |
| Parent timestamp validation | Yes (> parent) | Yes (in `BlockHeaderValidatorSkeleton`) | Match |

### Finding
Fukuii's difficulty calculation is correct and handles all ETC-specific forks (ECIP-1010 bomb delay, ECIP-1041 bomb removal) that don't exist in go-ethereum. No gaps identified.

---

## 3. Block Rewards

### go-ethereum Pattern (consensus/ethash/consensus.go)
- `accumulateRewards()`: Calculates miner + uncle rewards
- Era-based reward reduction: 5 ETH → 3 ETH (Byzantium) → 2 ETH (Constantinople)
- Uncle reward: `(uncleBlock + 8 - block) * blockReward / 8`
- Uncle inclusion reward: `blockReward / 32` per uncle
- Max 2 uncles per block

### Fukuii Implementation
- `BlockRewardCalculator.scala`: `calculateMiningRewardForBlock()`, `calculateMiningRewardForOmmers()`, `calculateOmmerRewardForInclusion()`
- Era-based ETC rewards with 20% reduction per era (5M blocks = ECIP-1017)
- `eraNumber()`: `(blockNumber - 1) / eraDuration`
- `newBlockReward()`: `firstEraBlockReward * (4/5)^era`
- Uncle rewards: 1/32 ratio matching go-ethereum pattern
- `BlockPreparator.payBlockReward()`: Distributes rewards to world state

### Comparison

| Feature | go-ethereum (ETH) | Fukuii (ETC) | Status |
|---------|-------------------|--------------|--------|
| Base reward calculation | Discrete per-fork (5→3→2) | Era-based 20% reduction (ECIP-1017) | Correct for each chain |
| Uncle inclusion reward | blockReward/32 per uncle | blockReward/32 per uncle | Match |
| Uncle miner reward | (uncle+8-block)*reward/8 | Equivalent formula | Match |
| Max uncles per block | 2 | 2 | Match |
| Treasury allocation | No | Yes (Olympia: EIP-1559 basefee) | ETC-specific addition |
| Reward application to state | `accumulateRewards()` | `payBlockReward()` | Equivalent |

### Finding
Block reward logic is correct. The ETC-specific ECIP-1017 era system replaces go-ethereum's discrete fork-based reductions. Uncle reward ratios match.

---

## 4. Block Header Validation

### go-ethereum Pattern (consensus/ethash/consensus.go)
- `verifyHeader()`: 12 validation checks in sequence
- Checks: future block (15s), gas limit bounds (±1/1024), extra data (32 bytes), uncle hash, nonce+mixHash, difficulty, gas used ≤ gas limit, base fee (post-London)
- `CalcGasLimit()`: ±1/1024 adjustment from parent

### Fukuii Implementation
- `BlockHeaderValidatorSkeleton.scala`: Orchestrates validation chain
- `validate()`: parent hash, slot, timestamp, extra data, gas limit, gas used, nonce, difficulty, extra fields (baseFee)
- Gas limit: ±1/1024 bounds check matching go-ethereum
- `EthashBlockHeaderValidator.scala`: PoW nonce/mixHash verification
- `PoWBlockHeaderValidator.scala`: Extends skeleton with Ethash check
- H-010 (fork-gate tx types), H-011 (baseFee field rejection), H-012 (baseFee corruption guard) — all recently added

### Comparison

| Validation | go-ethereum | Fukuii | Status |
|-----------|-------------|--------|--------|
| Parent hash | Yes | Yes | Match |
| Timestamp > parent | Yes | Yes | Match |
| Future block limit (15s) | Yes | Yes (configurable) | Match |
| Extra data ≤ 32 bytes | Yes | Yes | Match |
| Gas limit ±1/1024 | Yes | Yes | Match |
| Gas used ≤ gas limit | Yes | Yes | Match |
| Difficulty calculation match | Yes | Yes | Match |
| PoW (nonce/mixHash) | Yes | Yes | Match |
| baseFee validation (post-EIP-1559) | Yes | Yes (post-Olympia) | Match |
| Transaction type fork-gating | Yes | Yes (H-010) | Match |
| baseFee corruption guard | Panic | IllegalStateException (H-012) | Match |

### Finding
Block header validation is comprehensive and matches go-ethereum. The recent H-010/H-011/H-012 fixes closed the remaining gaps.

---

## 5. Uncle (Ommer) Validation

### go-ethereum Pattern (consensus/ethash/consensus.go)
- `verifyUncles()`: Max 2 per block, no duplicates, within 7 generations, valid ancestor
- Uncle must be a valid block header (PoW, difficulty, etc.)
- Uncle must not be an ancestor of the including block

### Fukuii Implementation
- Uncle validation in block validators: max 2 uncles, duplicate detection, ancestor depth check
- `OmmersPool`: Actor-based uncle pool management
- Uncle rewards calculated correctly per ECIP-1017 era

### Comparison

| Feature | go-ethereum | Fukuii | Status |
|---------|-------------|--------|--------|
| Max 2 uncles | Yes | Yes | Match |
| Duplicate detection | Yes | Yes | Match |
| 7-generation depth limit | Yes | Yes | Match |
| Uncle = valid header | Yes | Yes | Match |
| Uncle ≠ ancestor | Yes | Yes | Match |
| Uncle pool management | Per-block | Actor-based pool | Equivalent |

---

## 6. TD-Based Peer Selection

### go-ethereum Pattern (eth/downloader/)
- `bestPeer()`: Selects peer with highest total difficulty
- Peer scoring based on: TD, bandwidth, latency, error history
- Stale peer eviction on TD regression

### Fukuii Implementation
- `PeersClient.scala`: Best peer selection by total difficulty
- `Blacklist.scala`: 30 blacklist reasons with configurable durations
- `PeerScoringManager.scala`: Peer quality tracking
- `AdaptiveSyncStrategy.scala`: Dynamic sync mode selection based on peer capabilities

### Comparison

| Feature | go-ethereum | Fukuii | Status |
|---------|-------------|--------|--------|
| TD-based best peer | Yes | Yes | Match |
| Peer blacklisting | Reputation score | Explicit blacklist (30 reasons) | Equivalent |
| Bandwidth scoring | Yes | Via scoring manager | Equivalent |
| Stale peer disconnect | On TD regression | Via handshake + scoring | Equivalent |

### Recommendation
**R-002 (LOW):** go-ethereum weighs peer selection by both TD and download bandwidth. Fukuii's peer selection is primarily TD-based. Adding bandwidth-weighted selection could improve sync speed with many peers. Impact: marginal for typical peer counts (<50).

---

## 7. Mining Coordination

### go-ethereum Pattern (miner/)
- `miner.go`: Mining coordinator, recommit interval (3s default)
- `worker.go`: Block assembly, transaction selection, seal submission
- Background work preparation + instant recommit on new head
- getWork/submitWork for external miners (deprecated before merge)

### Fukuii Implementation
- `PoWMiningCoordinator.scala`: Pekko typed actor, RecurrentMining/OnDemandMining modes
- `PoWBlockCreator.scala`: Block template generation with transaction picking
- `EthashMiner.scala`: CPU miner worker
- RecommitInterval, SetMiningMode, StopMining protocol messages
- `EthashDAGManager.scala`: DAG lifecycle with INFO-level progress logging

### Comparison

| Feature | go-ethereum | Fukuii | Status |
|---------|-------------|--------|--------|
| Recommit on new head | Yes | Yes (RecommitWork) | Match |
| Configurable recommit interval | Yes (3s default) | Yes (SetRecommitInterval) | Match |
| Transaction selection | Gas price ordered | Via PendingTransactionsManager | Equivalent |
| External miner (getWork) | Yes (deprecated) | Yes (EthMiningService) | Match |
| DAG management | Background goroutine | Dedicated manager actor | Equivalent |
| Mining modes | Start/Stop | Recurrent/OnDemand | Richer |

---

## 8. PoW-Specific Safety Mechanisms

### go-ethereum Mechanisms (removed post-merge)

| Mechanism | go-ethereum | Fukuii | Relevance |
|-----------|-------------|--------|-----------|
| Bad block tracking | DB-backed (10 max) | Scaffeine cache (128, 1h TTL) | Fukuii has more capacity |
| Invalid block peer penalty | Downloader reputation | Blacklist (30 reasons) | Both effective |
| ECBP-1100 MESS | N/A (ETH) | Implemented (deactivated at Spiral) | ETC-specific, correct |
| Fork ID (EIP-2124) | Yes | Yes (ForkIdValidator) | Match |
| Deep reorg protection | None (pre-merge) | MESS (deactivated) | Correct for current state |
| TD overflow protection | uint256 bounds | BigInt (unbounded) | Fukuii safer |

### Recommendation
**R-003 (LOW):** go-ethereum had a `TerminalTotalDifficulty` concept for the merge transition. ETC doesn't need this, but the pattern of "halt PoW at a configured TD" could be useful as an emergency mechanism (e.g., network-wide halt if 51% attack detected). No action needed now.

---

## 9. Areas Where Fukuii is Ahead

1. **BadBlockTracker** — 128 entries vs go-ethereum's 10. Better for forensic analysis.
2. **ECBP-1100 MESS** — Anti-51% attack mechanism that go-ethereum never had (ETC-specific).
3. **Typed actor mining coordinator** — Pekko typed actors provide compile-time message safety vs go-ethereum's channel-based approach.
4. **Fork boundary tests** — 12 tests in `OlympiaForkBoundarySpec`, 13 in `OlympiaForkIdSpec`, 11 in `AdversarialForkBoundarySpec` — more coverage than go-ethereum's implicit boundary testing.
5. **Transaction type fork-gating** (H-010) — Explicit type validation per fork. go-ethereum does this but Fukuii's implementation was recently audited and hardened.
6. **Runtime log verbosity** — `debug_setVerbosity`/`debug_setVmodule` for live debugging without restart.

---

## 10. Summary of Recommendations

| ID | Priority | Description | Impact |
|----|----------|-------------|--------|
| R-001 | LOW | Add nonce range partitioning for multi-threaded CPU mining | Mining throughput (irrelevant for non-miners) |
| R-002 | LOW | Add bandwidth-weighted peer selection alongside TD | Marginal sync speed improvement |
| R-003 | LOW | Consider emergency PoW halt mechanism (configurable TD ceiling) | Safety mechanism for 51% attack response |
| R-004 | LOW | Memory-map DAG files instead of JVM arrays for large DAGs | Memory efficiency for future DAG growth |
| R-005 | LOW | Add mining hashrate metrics to Prometheus | Observability for mining operators |

**Overall assessment:** Fukuii's PoW consensus implementation is production-ready for ETC. No critical or high-priority gaps relative to go-ethereum v1.10.26. The recent H-010/H-011/H-012/H-013/H-014/H-015/H-016 hardening pass addressed the most important consensus safety gaps.
