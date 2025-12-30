# ECIP-1121: Olympia Hardfork Implementation Analysis

**Document Version:** 1.0  
**Date:** 2025-12-30  
**Status:** Research & Planning Phase  
**Target Hardfork:** Olympia (ECIP-1111)

## Executive Summary

This document provides a comprehensive analysis of the Olympia hardfork requirements (ECIP-1111 and related specifications) and their impact on the Fukuii Ethereum Classic client. The Olympia upgrade introduces EIP-1559 and EIP-3198 to Ethereum Classic with a critical modification: instead of burning the `BASEFEE`, it redirects funds to an on-chain treasury contract for sustainable protocol funding.

### Key Findings

1. **Olympia is NOT currently defined as ECIP-1121** - The issue description references ECIP-1121, but the actual Olympia hardfork specification is **ECIP-1111**
2. **Fukuii currently supports up to Spiral (ECIP-1109)** - activated at block 19,250,000
3. **EIP-1559 and EIP-3198 are NOT yet implemented** in Fukuii
4. **This is a consensus-critical upgrade** requiring changes across multiple subsystems
5. **Olympia is currently in Draft status** - activation blocks are TBD

### Specification References

- **ECIP-1111**: Olympia EVM and Protocol Upgrades (Core specification)
- **ECIP-1112**: Olympia Treasury Contract (Treasury implementation)
- **ECIP-1113**: Olympia DAO Governance Framework (Governance layer)
- **ECIP-1114**: Ethereum Classic Funding Proposal Process (Funding process)

## Specification Overview

### ECIP-1111: Olympia Network Upgrade

**Status:** Draft  
**Type:** Meta  
**Requires:** ECIP-1112

#### Included EIPs

1. **EIP-1559: Fee Market Change**
   - Introduces dynamic `basefee` adjustment mechanism
   - Adds new transaction type (Type-2) with `maxFeePerGas` and `maxPriorityFeePerGas`
   - **CRITICAL MODIFICATION**: BASEFEE is redirected to Treasury, NOT burned
   - Miner tips (`priorityFee`) paid directly to block producers
   - Requires consensus-layer changes for basefee calculation and block processing

2. **EIP-3198: BASEFEE Opcode**
   - Adds opcode `0x48` (`BASEFEE`)
   - Returns current block's `basefee` value
   - Enables fee-aware smart contract logic
   - Required for full EVM compatibility

#### Excluded from Olympia

- Any Proof-of-Stake related changes
- Beacon chain functionality
- Validator operations
- Changes beyond EIP-1559 and EIP-3198

### ECIP-1112: Treasury Contract

**Status:** Draft  
**Type:** Meta  
**Requires:** ECIP-1111

#### Key Requirements

- Deterministic deployment of immutable Treasury contract
- Fixed address derived using CREATE2
- Receives 100% of BASEFEE amounts at consensus layer
- No upgradability or modification allowed
- Single authorized withdrawal entry point (defined in ECIP-1113)
- Zero governance logic in the contract itself

### ECIP-1113 & ECIP-1114: Governance & Funding

These ECIPs define the governance framework and funding proposal process. They are **separate from consensus changes** and operate at the application/contract layer.

## Current Fukuii State Analysis

### Supported Hardforks (as of Spiral)

From `src/main/resources/conf/base/chains/etc-chain.conf` and `src/main/scala/com/chipprbots/ethereum/vm/EvmConfig.scala`:

| Hardfork | Block Number | Status | Notes |
|----------|-------------|--------|-------|
| Frontier | 0 | ✅ Implemented | Genesis |
| Homestead | 1,150,000 | ✅ Implemented | EIP-2 |
| EIP-150 | 2,500,000 | ✅ Implemented | Gas cost changes |
| EIP-155 | 3,000,000 | ✅ Implemented | Replay protection |
| EIP-160 | 3,000,000 | ✅ Implemented | EXP cost increase |
| Atlantis | 8,772,000 | ✅ Implemented | ETC fork (Byzantium-equivalent) |
| Agharta | 9,573,000 | ✅ Implemented | Constantinople-equivalent |
| Phoenix | 10,500,839 | ✅ Implemented | Istanbul-equivalent |
| Magneto | 13,189,133 | ✅ Implemented | ECIP-1103 |
| Mystique | 14,525,000 | ✅ Implemented | ECIP-1104, EIP-3529 |
| Spiral | 19,250,000 | ✅ Implemented | ECIP-1109, EIP-3855, EIP-3651, EIP-3860 |
| **Olympia** | **TBD** | ❌ Not Implemented | **Target of this analysis** |

### EIP-1559 Implementation Status

**CURRENT STATUS: NOT IMPLEMENTED**

Evidence from codebase search:
- ❌ No `BASEFEE` opcode (0x48) found in OpCode.scala
- ❌ No EIP-1559 transaction type implementation
- ❌ No basefee calculation logic
- ⚠️ Some preparatory comments reference EIP-1559 in Receipt.scala and ETH67.scala
- ⚠️ Transaction type framework may exist but not activated

### EIP-3198 Implementation Status

**CURRENT STATUS: NOT IMPLEMENTED**

- ❌ BASEFEE opcode (0x48) not present in OpCodes list
- Current opcode 0x44 is DIFFICULTY (not modified for PREVRANDAO)
- OpCode lists: Frontier, Homestead, Byzantium, Constantinople, Phoenix, Spiral
- SpiralOpCodes only adds PUSH0 to PhoenixOpCodes

## Required Implementation Changes

### 1. Consensus Layer Changes (CRITICAL)

#### 1.1 Block Header Modifications
**Location:** `src/main/scala/com/chipprbots/ethereum/domain/BlockHeader.scala`

**Required Changes:**
- Add `baseFeePerGas: Option[BigInt]` field to BlockHeader
- Update RLP encoding/decoding for new header format
- Add validation for baseFeePerGas presence post-Olympia
- Ensure backward compatibility for pre-Olympia blocks

**Impact:** High - Consensus critical
**Risk:** High - Any error causes chain fork

#### 1.2 Basefee Calculation Algorithm
**New Component Required**

**Implementation Needs:**
```scala
// Pseudo-code structure
object BaseFeeCalculator {
  def calculateBaseFee(
    parentHeader: BlockHeader,
    parentGasUsed: BigInt,
    parentGasLimit: BigInt
  ): BigInt = {
    // Implement EIP-1559 basefee adjustment formula
    // BaseFee adjustment based on gas target (50% of gas limit)
    // BASEFEE_MAX_CHANGE_DENOMINATOR = 8
    // Increase if parent gas > target, decrease if < target
  }
  
  val INITIAL_BASE_FEE: BigInt = 1000000000 // 1 gwei
  val BASE_FEE_MAX_CHANGE_DENOMINATOR: BigInt = 8
  val ELASTICITY_MULTIPLIER: BigInt = 2
}
```

**Impact:** High - Consensus critical
**Risk:** High - Incorrect calculation causes divergence

#### 1.3 Block Finalization with Treasury Transfer
**Location:** `src/main/scala/com/chipprbots/ethereum/ledger/BlockPreparator.scala` or `BlockExecution.scala`

**Required Changes:**
- Calculate total BASEFEE from all transactions in block
- Transfer BASEFEE amount to Treasury address at consensus layer
- Ensure transfer happens BEFORE miner reward distribution
- Add state change for Treasury balance increase

**Consensus-Critical Pseudocode:**
```scala
def finalizeBlock(block: Block, state: WorldState): WorldState = {
  val baseFeeAmount = block.transactions.foldLeft(BigInt(0)) { (acc, tx) =>
    acc + (tx.gasUsed * block.header.baseFeePerGas.getOrElse(0))
  }
  
  // CRITICAL: Must transfer to Treasury before any other balance changes
  val stateWithTreasury = state.transfer(
    from = Address.zero, // Protocol-level creation
    to = treasuryAddress,
    value = baseFeeAmount
  )
  
  // Then apply miner rewards (tips only)
  val minerTips = calculateMinerTips(block.transactions)
  stateWithTreasury.transfer(
    from = Address.zero,
    to = block.header.beneficiary,
    value = minerTips + blockReward
  )
}
```

**Impact:** Critical - Core consensus logic
**Risk:** Critical - Incorrect implementation causes hard fork

### 2. Transaction Type Changes

#### 2.1 New Transaction Type (Type-2)
**Location:** `src/main/scala/com/chipprbots/ethereum/domain/SignedTransaction.scala`

**Required Changes:**
- Add EIP-1559 transaction type support (Type-2)
- Fields: `maxFeePerGas`, `maxPriorityFeePerGas`, `gasLimit`, `accessList`
- Update transaction RLP encoding/decoding
- Add validation for Type-2 transactions
- Support legacy (Type-0) and access list (Type-1) transactions

**New Transaction Structure:**
```scala
case class SignedEIP1559Transaction(
  chainId: BigInt,
  nonce: BigInt,
  maxPriorityFeePerGas: BigInt,
  maxFeePerGas: BigInt,
  gasLimit: BigInt,
  to: Option[Address],
  value: BigInt,
  data: ByteString,
  accessList: Seq[AccessListItem],
  v: BigInt,
  r: BigInt,
  s: BigInt
) extends SignedTransaction
```

**Impact:** High - Affects transaction processing
**Risk:** High - Invalid transactions could be included

#### 2.2 Transaction Validation
**Location:** `src/main/scala/com/chipprbots/ethereum/consensus/validators/std/StdSignedTransactionValidator.scala`

**Required Changes:**
- Validate `maxFeePerGas >= maxPriorityFeePerGas`
- Validate `maxFeePerGas >= block.baseFeePerGas`
- Ensure sender balance >= (gasLimit * maxFeePerGas) + value
- Validate transaction type based on fork activation

**Impact:** High - Transaction acceptance
**Risk:** Medium - Could accept invalid transactions

#### 2.3 Gas Payment Calculation
**Location:** Transaction execution logic

**Required Changes:**
```scala
def calculateEffectiveGasPrice(
  tx: SignedEIP1559Transaction,
  baseFeePerGas: BigInt
): (BigInt, BigInt) = {
  val priorityFee = tx.maxPriorityFeePerGas.min(
    tx.maxFeePerGas - baseFeePerGas
  )
  val effectiveGasPrice = baseFeePerGas + priorityFee
  
  (effectiveGasPrice, priorityFee)
}

// Miner receives only priorityFee
// BASEFEE goes to Treasury (handled in block finalization)
```

**Impact:** High - Gas economics
**Risk:** High - Incorrect payment distribution

### 3. EVM Changes

#### 3.1 BASEFEE Opcode (0x48)
**Location:** `src/main/scala/com/chipprbots/ethereum/vm/OpCode.scala`

**Required Changes:**
```scala
case object BASEFEE extends ConstOp(0x48) { state =>
  // Return current block's baseFeePerGas
  state.env.blockHeader.baseFeePerGas
    .map(UInt256(_))
    .getOrElse(UInt256.Zero) // Pre-Olympia blocks
}
```

**Add to OpCode lists:**
```scala
val OlympiaOpCodes: OpCodeList = OpCodeList(
  BASEFEE +: SpiralOpCodes
)
```

**Impact:** Medium - EVM execution
**Risk:** Low - Well-defined opcode behavior

#### 3.2 EVM Configuration
**Location:** `src/main/scala/com/chipprbots/ethereum/vm/EvmConfig.scala`

**Required Changes:**
```scala
val OlympiaConfigBuilder: EvmConfigBuilder = config =>
  SpiralConfigBuilder(config).copy(
    feeSchedule = new FeeSchedule.OlympiaFeeSchedule,
    opCodeList = OlympiaOpCodes,
    eip1559Enabled = true,
    eip3198Enabled = true
  )
```

**Add to fork selection:**
```scala
(blockchainConfig.olympiaBlockNumber, 17, OlympiaConfigBuilder)
```

**Impact:** High - Fork activation
**Risk:** Medium - Incorrect activation logic

### 4. Configuration Changes

#### 4.1 Blockchain Config
**Location:** `src/main/scala/com/chipprbots/ethereum/utils/BlockchainConfig.scala`

**Required Changes:**
```scala
case class ForkBlockNumbers(
  // ... existing forks ...
  spiralBlockNumber: BigInt,
  olympiaBlockNumber: BigInt  // NEW
)

case class BlockchainConfig(
  // ... existing fields ...
  olympiaTreasuryAddress: Address  // NEW - deterministic address
)
```

**Impact:** High - Core configuration
**Risk:** Medium - Must be consistent across clients

#### 4.2 Chain Configuration Files
**Location:** `src/main/resources/conf/base/chains/etc-chain.conf`

**Required Changes:**
```hocon
# Olympia EVM and Protocol Upgrades (ECIP-1111)
# Implements EIP-1559: Fee Market Change (with BASEFEE redirection)
# Implements EIP-3198: BASEFEE opcode
# https://ecips.ethereumclassic.org/ECIPs/ecip-1111
olympia-block-number = "TBD"  # To be determined after testnet coordination

# Olympia Treasury address (deterministic, immutable)
# https://ecips.ethereumclassic.org/ECIPs/ecip-1112
olympia-treasury-address = "TBD"  # To be published before testnet
```

**Similar changes needed for:**
- `mordor-chain.conf` (testnet)
- `gorgoroth-chain.conf` (private test network)
- `test-chain.conf`

**Impact:** High - Network activation
**Risk:** Low - Configuration only

### 5. Network Layer Changes

#### 5.1 Transaction Propagation
**Location:** `src/main/scala/com/chipprbots/ethereum/network/p2p/messages/`

**Required Changes:**
- Update transaction message encoders/decoders
- Support Type-2 transaction propagation
- Ensure backward compatibility with Type-0 and Type-1

**Impact:** High - P2P networking
**Risk:** Medium - Must interoperate with other clients

#### 5.2 Block Propagation
**Impact:** Medium - Block header includes baseFeePerGas
**Risk:** Medium - RLP encoding must match spec

### 6. JSON-RPC API Changes

#### 6.1 New/Modified RPC Methods
**Location:** `src/main/scala/com/chipprbots/ethereum/jsonrpc/`

**Required Changes:**

1. **`eth_gasPrice`** - Should return suggested max fee
2. **`eth_feeHistory`** - NEW method required by EIP-1559 clients
3. **`eth_maxPriorityFeePerGas`** - NEW method for fee estimation
4. **`eth_getBlockByNumber`/`eth_getBlockByHash`** - Include `baseFeePerGas`
5. **`eth_sendRawTransaction`** - Support Type-2 transactions
6. **`eth_getTransactionByHash`** - Include Type-2 fields
7. **`eth_getTransactionReceipt`** - Include `effectiveGasPrice`

**New Response Fields:**
```json
{
  "baseFeePerGas": "0x...",
  "maxFeePerGas": "0x...",
  "maxPriorityFeePerGas": "0x...",
  "type": "0x2",
  "effectiveGasPrice": "0x..."
}
```

**Impact:** High - API compatibility
**Risk:** Medium - Wallet/tool integration

### 7. Storage & Database Changes

#### 7.1 Block Storage
**Location:** `src/main/scala/com/chipprbots/ethereum/db/storage/`

**Required Changes:**
- Update block header serialization with baseFeePerGas
- Store Type-2 transaction format
- Maintain backward compatibility for historical data

**Impact:** High - Data persistence
**Risk:** High - Data corruption risk if not handled properly

#### 7.2 Transaction History
**Location:** `src/main/scala/com/chipprbots/ethereum/transactions/TransactionHistoryService.scala`

**Required Changes:**
- Index Type-2 transactions
- Store maxFeePerGas and maxPriorityFeePerGas

**Impact:** Medium - Transaction queries
**Risk:** Low - Non-consensus

### 8. Fee Schedule Updates

#### 8.1 New Fee Schedule
**Location:** `src/main/scala/com/chipprbots/ethereum/vm/FeeSchedule.scala`

**Required Changes:**
```scala
class OlympiaFeeSchedule extends FeeSchedule {
  // Same as Spiral, but with EIP-1559 awareness
  // BASEFEE opcode gas cost = 2 (G_base)
}
```

**Impact:** Medium - Gas cost calculations
**Risk:** Low - Well-defined costs

## Testing Requirements

### 1. Unit Tests

#### Consensus Tests
- [ ] Basefee calculation algorithm tests
- [ ] Block header encoding/decoding with baseFeePerGas
- [ ] Treasury transfer logic tests
- [ ] Transaction type validation tests
- [ ] Effective gas price calculation tests

#### EVM Tests
- [ ] BASEFEE opcode execution tests
- [ ] Opcode gas cost tests
- [ ] Fork selection tests

#### Transaction Tests
- [ ] Type-2 transaction creation and signing
- [ ] Transaction validation tests
- [ ] RLP encoding/decoding tests
- [ ] Fee validation tests

### 2. Integration Tests

- [ ] Full block processing with mixed transaction types
- [ ] Treasury balance accumulation tests
- [ ] Cross-fork boundary tests (Spiral → Olympia)
- [ ] Network message propagation tests

### 3. Testnet Validation

**Mordor Testnet Requirements:**
- [ ] Deploy and activate Olympia on Mordor
- [ ] Test transaction lifecycle for all types (0, 1, 2)
- [ ] Verify Treasury accumulation
- [ ] Cross-client consensus validation
- [ ] Fee market behavior under various gas usage scenarios
- [ ] Fork ID and replay protection validation

### 4. Compatibility Tests

- [ ] Ethereum test suite (if applicable)
- [ ] Reference client comparison (Core-Geth, Hyperledger Besu)
- [ ] Wallet compatibility (MetaMask, etc.)
- [ ] RPC API compatibility tests

### 5. Performance Tests

- [ ] Block processing performance with Type-2 transactions
- [ ] Basefee calculation overhead
- [ ] Network propagation performance

## Risk Assessment

### Critical Risks

| Risk | Severity | Mitigation |
|------|----------|------------|
| Consensus divergence from incorrect BASEFEE calculation | Critical | Extensive testing, reference implementation comparison |
| Treasury transfer logic error causing double-spend or loss | Critical | Formal verification, testnet validation |
| Transaction validation bypass allowing invalid transactions | High | Comprehensive test suite, security audit |
| RLP encoding incompatibility with other clients | High | Cross-client testing, spec compliance verification |
| Fork activation logic error | High | Testnet dry-run, coordinated activation |

### Medium Risks

| Risk | Severity | Mitigation |
|------|----------|------------|
| JSON-RPC API incompatibility with tools/wallets | Medium | EIP-1559 API test suite, wallet testing |
| Performance degradation from basefee calculation | Medium | Performance benchmarking, optimization |
| Network message propagation issues | Medium | Multi-client integration testing |
| Fee estimation inaccuracy | Medium | Fee history analysis, dynamic adjustment |

### Low Risks

| Risk | Severity | Mitigation |
|------|----------|------------|
| Documentation gaps | Low | Comprehensive documentation review |
| Backward compatibility issues for old blocks | Low | Explicit version handling |
| Configuration errors | Low | Configuration validation, defaults |

## Blockers and Open Questions

### Critical Blockers

1. **Treasury Address Not Yet Published**
   - ECIP-1112 specifies deterministic address derivation
   - Actual address must be published before implementation
   - **Status:** Waiting for ECIP-1112 finalization

2. **Activation Block Numbers Not Set**
   - Mordor testnet block: TBD
   - Mainnet block: TBD
   - **Status:** Requires community coordination

3. **Reference Implementation Availability**
   - ECIP-1111 mentions "prototype implementations under active development"
   - Need to identify which clients have working implementations
   - **Status:** Research required

### Design Questions

1. **Priority Fee Distribution**
   - Does miner receive 100% of priorityFee or is there a split?
   - ECIP-1111 states "miner tips continue to be paid directly to block producers"
   - **Answer:** Miner receives 100% of priority fee

2. **Initial BASEFEE Value**
   - What is the initial baseFeePerGas at Olympia activation block?
   - EIP-1559 uses 1 gwei (1000000000 wei)
   - **Assumption:** Use 1 gwei unless specified otherwise

3. **Transaction Type Backwards Compatibility**
   - How long do we maintain Type-0 (legacy) transaction support?
   - **Answer:** Indefinitely - all transaction types coexist

4. **Fee History Storage**
   - How many blocks of fee history should eth_feeHistory support?
   - **Recommendation:** At least 1024 blocks (standard practice)

### Coordination Requirements

1. **Cross-Client Coordination**
   - Core-Geth implementation status?
   - Hyperledger Besu plans?
   - Need coordination meetings with other client teams

2. **Testnet Coordination**
   - Mordor testnet activation timeline
   - Test scenarios and success criteria
   - Multi-client testnet deployment

3. **Mainnet Activation**
   - Sufficient lead time for operators
   - Exchange/infrastructure provider notification
   - Miner coordination

## Implementation Strategy

### Phase 1: Preparation (Estimated: 2-3 weeks)

1. **Research & Specification Review**
   - [x] Review ECIP-1111, 1112, 1113, 1114
   - [ ] Study reference implementations (Core-Geth, Besu)
   - [ ] Analyze Ethereum's London fork implementation
   - [ ] Review EIP-1559 test vectors

2. **Architecture Design**
   - [ ] Design basefee calculation module
   - [ ] Design transaction type framework
   - [ ] Design block finalization changes
   - [ ] Design RPC API changes
   - [ ] Create ADRs for major design decisions

### Phase 2: Core Implementation (Estimated: 4-6 weeks)

1. **Consensus Layer** (Week 1-2)
   - [ ] Implement BlockHeader changes with baseFeePerGas
   - [ ] Implement BaseFeeCalculator
   - [ ] Implement Treasury transfer logic in block finalization
   - [ ] Update block validation

2. **Transaction Layer** (Week 2-3)
   - [ ] Implement Type-2 transaction structure
   - [ ] Implement transaction validation for EIP-1559
   - [ ] Implement effective gas price calculation
   - [ ] Update transaction RLP encoding/decoding

3. **EVM Layer** (Week 3-4)
   - [ ] Implement BASEFEE opcode
   - [ ] Update OlympiaOpCodes list
   - [ ] Update EvmConfig with Olympia fork
   - [ ] Update FeeSchedule for Olympia

4. **Configuration** (Week 4)
   - [ ] Add olympiaBlockNumber to ForkBlockNumbers
   - [ ] Add olympiaTreasuryAddress to BlockchainConfig
   - [ ] Update all chain configuration files
   - [ ] Add configuration validation

### Phase 3: Integration (Estimated: 2-3 weeks)

1. **Storage & Database** (Week 5)
   - [ ] Update block storage with new header format
   - [ ] Update transaction storage for Type-2
   - [ ] Add migration logic for storage format
   - [ ] Test backward compatibility

2. **Network Layer** (Week 5-6)
   - [ ] Update transaction propagation
   - [ ] Update block propagation
   - [ ] Test P2P message compatibility

3. **JSON-RPC API** (Week 6-7)
   - [ ] Implement eth_feeHistory
   - [ ] Implement eth_maxPriorityFeePerGas
   - [ ] Update existing methods with baseFeePerGas
   - [ ] Add Type-2 transaction support to all methods

### Phase 4: Testing (Estimated: 3-4 weeks)

1. **Unit Testing** (Week 7-8)
   - [ ] Write comprehensive unit tests for all new components
   - [ ] Achieve >90% code coverage for new code
   - [ ] Test edge cases and error conditions

2. **Integration Testing** (Week 8-9)
   - [ ] Full block processing tests
   - [ ] Cross-fork boundary tests
   - [ ] Network integration tests
   - [ ] Performance testing

3. **Testnet Deployment** (Week 9-10)
   - [ ] Deploy to private test network (Gorgoroth)
   - [ ] Deploy to Mordor testnet
   - [ ] Coordinate with other client teams
   - [ ] Run extended validation tests

### Phase 5: Mainnet Preparation (Estimated: 2-3 weeks)

1. **Documentation** (Week 10-11)
   - [ ] Update user documentation
   - [ ] Write operator upgrade guide
   - [ ] Create migration guide
   - [ ] Document API changes

2. **Security Review** (Week 11-12)
   - [ ] Internal code review
   - [ ] Security audit (if budget allows)
   - [ ] Formal verification of critical components
   - [ ] Penetration testing

3. **Release Preparation** (Week 12)
   - [ ] Create release candidate
   - [ ] Final testing
   - [ ] Prepare release notes
   - [ ] Coordinate mainnet activation

### Phase 6: Mainnet Activation (Estimated: 1-2 weeks)

1. **Pre-Activation** (Week 13)
   - [ ] Release final version
   - [ ] Notify all stakeholders
   - [ ] Monitor upgrade progress

2. **Activation & Monitoring** (Week 14)
   - [ ] Monitor mainnet activation
   - [ ] Provide operator support
   - [ ] Track Treasury balance
   - [ ] Monitor for issues

**Total Estimated Timeline: 12-14 weeks (3-3.5 months)**

## Resource Requirements

### Development Resources

- **Senior Blockchain Engineer** (Full-time, 12-14 weeks)
  - Consensus layer implementation
  - Block finalization changes
  - Treasury integration

- **EVM Engineer** (Full-time, 4-6 weeks)
  - BASEFEE opcode implementation
  - EVM configuration updates
  - Gas calculation changes

- **Backend Engineer** (Full-time, 6-8 weeks)
  - Transaction type implementation
  - RPC API changes
  - Storage updates

- **DevOps Engineer** (Part-time, 4 weeks)
  - Testnet deployment
  - Monitoring setup
  - Release management

- **QA Engineer** (Full-time, 4 weeks)
  - Test suite development
  - Integration testing
  - Testnet validation

### Infrastructure Resources

- **Mordor Testnet Nodes** (2-3 nodes)
  - For coordinated testing
  - Multi-client setup

- **Private Test Network** (Gorgoroth upgrade)
  - Early testing environment
  - Performance benchmarking

- **CI/CD Resources**
  - Extended test suite execution
  - Performance testing infrastructure

### External Dependencies

- **ECIP-1112 Finalization**
  - Treasury address publication
  - Contract specification completion

- **Community Coordination**
  - Cross-client team communication
  - Testnet coordination meetings
  - Activation consensus

- **Reference Implementations**
  - Access to Core-Geth implementation
  - Access to Besu implementation
  - EIP-1559 test vectors

## Success Criteria

### Technical Success

- [ ] All unit tests pass with >90% coverage
- [ ] All integration tests pass
- [ ] Successful Mordor testnet activation with no issues
- [ ] Cross-client consensus maintained on testnet
- [ ] Treasury balance accumulates correctly
- [ ] All transaction types process correctly
- [ ] BASEFEE opcode works as specified
- [ ] RPC API compatible with EIP-1559 tools
- [ ] No performance regression

### Operational Success

- [ ] Smooth mainnet activation with no forks
- [ ] Operators successfully upgrade
- [ ] Exchanges support Type-2 transactions
- [ ] Wallets compatible with new transaction format
- [ ] Treasury accumulation matches expectations
- [ ] No critical bugs reported post-activation

### Community Success

- [ ] Clear documentation available
- [ ] Operators trained on upgrade process
- [ ] Community informed of changes
- [ ] Cross-client coordination successful
- [ ] Positive community feedback

## Dependencies on Other Work

### Prerequisites

1. **Formal Treasury Address**
   - Required before any implementation
   - Must be deterministic and agreed upon
   - **Blocks:** All treasury-related code

2. **ECIP-1112 Contract Specification**
   - Full contract interface needed
   - Withdrawal mechanism definition
   - **Blocks:** Treasury integration testing

3. **Activation Block Numbers**
   - Mordor testnet block
   - Mainnet block
   - **Blocks:** Final release

### Parallel Work

1. **ECIP-1113/1114 Governance**
   - Can be implemented separately
   - Not consensus-critical
   - Application-layer only

2. **Documentation Updates**
   - Can proceed alongside implementation
   - User guides, operator manuals

3. **Monitoring & Analytics**
   - Treasury tracking dashboards
   - Fee market analytics

## Future Considerations

### Post-Olympia Enhancements

1. **ECIP-1115: Potential Fee Smoothing**
   - Mentioned in ECIP-1111 as future work
   - Optional miner alignment mechanisms
   - Revenue smoothing proposals

2. **Additional EIP Adoptions**
   - ECIP-1111 Appendix lists future EIP candidates
   - Separate ECIPs required for each
   - Community-driven prioritization

3. **Governance Activation**
   - ECIP-1113 DAO framework
   - ECIP-1114 funding proposals
   - Separate from consensus changes

### Long-term Maintenance

1. **Treasury Monitoring**
   - Balance tracking
   - Accumulation rate analysis
   - Anomaly detection

2. **Fee Market Analysis**
   - Basefee behavior monitoring
   - User experience metrics
   - Optimization opportunities

3. **Cross-Client Compatibility**
   - Ongoing coordination with other clients
   - Shared test suites
   - Interoperability testing

## Appendix A: Key Files Requiring Changes

### Consensus Layer
- `src/main/scala/com/chipprbots/ethereum/domain/BlockHeader.scala`
- `src/main/scala/com/chipprbots/ethereum/ledger/BlockExecution.scala`
- `src/main/scala/com/chipprbots/ethereum/ledger/BlockPreparator.scala`
- `src/main/scala/com/chipprbots/ethereum/ledger/BlockRewardCalculator.scala`
- `src/main/scala/com/chipprbots/ethereum/consensus/validators/BlockValidator.scala`

### Transaction Layer
- `src/main/scala/com/chipprbots/ethereum/domain/SignedTransaction.scala`
- `src/main/scala/com/chipprbots/ethereum/consensus/validators/std/StdSignedTransactionValidator.scala`

### EVM Layer
- `src/main/scala/com/chipprbots/ethereum/vm/OpCode.scala`
- `src/main/scala/com/chipprbots/ethereum/vm/EvmConfig.scala`
- `src/main/scala/com/chipprbots/ethereum/vm/FeeSchedule.scala`
- `src/main/scala/com/chipprbots/ethereum/vm/VM.scala`

### Configuration
- `src/main/scala/com/chipprbots/ethereum/utils/BlockchainConfig.scala`
- `src/main/resources/conf/base/chains/etc-chain.conf`
- `src/main/resources/conf/base/chains/mordor-chain.conf`
- `src/main/resources/conf/base/chains/gorgoroth-chain.conf`

### Network Layer
- `src/main/scala/com/chipprbots/ethereum/network/p2p/messages/ETH67.scala`
- `src/main/scala/com/chipprbots/ethereum/network/p2p/messages/BaseETH6XMessages.scala`

### JSON-RPC API
- `src/main/scala/com/chipprbots/ethereum/jsonrpc/EthTxService.scala`
- `src/main/scala/com/chipprbots/ethereum/jsonrpc/BlockResponse.scala`
- `src/main/scala/com/chipprbots/ethereum/jsonrpc/TransactionReceiptResponse.scala`

### Storage
- `src/main/scala/com/chipprbots/ethereum/db/storage/BlockHeaderStorage.scala`
- `src/main/scala/com/chipprbots/ethereum/db/storage/TransactionStorage.scala`

## Appendix B: Reference Resources

### Specifications
- [ECIP-1111: Olympia EVM and Protocol Upgrades](https://github.com/ethereumclassic/ECIPs/blob/master/_specs/ecip-1111.md)
- [ECIP-1112: Olympia Treasury Contract](https://github.com/ethereumclassic/ECIPs/blob/master/_specs/ecip-1112.md)
- [ECIP-1113: Olympia DAO Governance Framework](https://github.com/ethereumclassic/ECIPs/blob/master/_specs/ecip-1113.md)
- [ECIP-1114: Ethereum Classic Funding Proposal Process](https://github.com/ethereumclassic/ECIPs/blob/master/_specs/ecip-1114.md)
- [EIP-1559: Fee Market Change](https://eips.ethereum.org/EIPS/eip-1559)
- [EIP-3198: BASEFEE Opcode](https://eips.ethereum.org/EIPS/eip-3198)

### Community Resources
- [Olympia Discussions](https://github.com/orgs/ethereumclassic/discussions/530)
- [ETC Discord](https://ethereumclassic.org/discord)
- [ECIPs Repository](https://github.com/ethereumclassic/ECIPs)

### Technical Resources
- [Ethereum's London Fork Documentation](https://ethereum.org/en/history/#london)
- [EIP-1559 Implementation Guide](https://hackmd.io/@timbeiko/1559-updates)
- [Go-Ethereum EIP-1559 Implementation](https://github.com/ethereum/go-ethereum/pulls?q=is%3Apr+EIP-1559)

## Appendix C: Clarification on ECIP-1121

**IMPORTANT NOTE:** The issue title references "ECIP-1121" but this specification number **does not currently exist** in the ECIPs repository. Based on research:

1. The Olympia hardfork is specified in **ECIP-1111**, not ECIP-1121
2. ECIP-1111 is the meta-ECIP for the Olympia upgrade
3. Supporting ECIPs are 1112, 1113, and 1114
4. ECIP-1115 is mentioned as potential future work but not yet proposed

**Possible explanations:**
- The issue may have been created with a typo (1121 vs 1111)
- ECIP-1121 may be a future number reserved for related work
- The reporter may have confused the ECIP number

**Recommendation:** Treat this issue as requesting analysis of the Olympia hardfork (ECIP-1111) and related specifications.

---

## Document Revision History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2025-12-30 | Copilot | Initial comprehensive analysis document |

---

**End of Analysis Document**
