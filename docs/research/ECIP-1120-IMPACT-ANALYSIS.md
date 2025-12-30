# ECIP-1120 Impact Analysis and Integration with EIP-1559

**Document Type:** Research Analysis  
**Status:** Draft  
**Date:** 2025-12-30  
**Related ECIPs:** ECIP-1120, ECIP-1097, ECIP-1098  
**Related EIPs:** EIP-1559  

## Executive Summary

This document analyzes the technical and design implications of supporting ECIP-1120 in fukuii, with particular focus on its interaction with EIP-1559-style base fee mechanisms. ECIP-1120 proposes redirecting EIP-1559 base fees to a treasury address instead of burning them, fundamentally altering Ethereum Classic's fee distribution while preserving its non-deflationary monetary policy.

**Key Findings:**
- ECIP-1120 is part of the broader Olympia Upgrade suite (ECIPs 1111-1115)
- Implementation requires adding EIP-1559 base fee fields to block headers
- Treasury address must be enforced at consensus level for block validity
- Existing ECIP-1097/1098 treasury implementation provides architectural precedent
- Configuration system must support optional and configurable treasury activation

## Background

### Current State in Fukuii

Fukuii currently implements a treasury system via ECIP-1097 and ECIP-1098:
- **ECIP-1098**: Defines a proto-treasury system with 80/20 split of block rewards (80% miner, 20% treasury)
- **ECIP-1097**: Adds checkpointing mechanism and treasury validation
- Configuration: Treasury address and activation block numbers are defined in chain configs
- Implementation: `BlockPreparator.payBlockReward()` handles treasury distribution

**Note:** While ECIP-1097/1098 were withdrawn from ETC mainnet adoption in 2021, fukuii retains the implementation with treasury disabled by default (activation block set to infinity).

### ECIP-1120 Overview

ECIP-1120 is part of the Olympia Upgrade, which adapts EIP-1559 for Ethereum Classic:

**Core Differences from Ethereum's EIP-1559:**
- **Ethereum**: Base fee is burned (deflationary)
- **ETC (ECIP-1120)**: Base fee is redirected to treasury (non-deflationary, sustainable funding)

**Transaction Fee Structure:**
```
Total User Fee = Base Fee + Priority Fee (Tip)
```

**Distribution:**
- Base Fee → Treasury Address (via consensus enforcement)
- Priority Fee → Miner (immediate incentive)
- Block Reward → Miner (unchanged)

### EIP-1559 Mechanics

**Base Fee Calculation:**
- Algorithmically adjusted each block based on network congestion
- If previous block > gas target: base fee increases
- If previous block < gas target: base fee decreases
- Adjustment is gradual (12.5% max change per block)

**Block Header Changes Required:**
- `baseFeePerGas`: New field added to block header (post-EIP-1559)
- Dynamic adjustment based on parent block's gas usage

**Transaction Types:**
- Type 0: Legacy transactions (pre-EIP-1559)
- Type 1: EIP-2930 transactions (access lists)
- Type 2: EIP-1559 transactions (dynamic fees)

## Technical Analysis

### 1. Block Header Modifications

**Current Structure:**
```scala
case class BlockHeader(
    parentHash: ByteString,
    ommersHash: ByteString,
    beneficiary: ByteString,
    stateRoot: ByteString,
    transactionsRoot: ByteString,
    receiptsRoot: ByteString,
    logsBloom: ByteString,
    difficulty: BigInt,
    number: BigInt,
    gasLimit: BigInt,
    gasUsed: BigInt,
    unixTimestamp: Long,
    extraData: ByteString,
    mixHash: ByteString,
    nonce: ByteString,
    extraFields: HeaderExtraFields = HefEmpty
)
```

**Required Changes for ECIP-1120/EIP-1559:**

Add base fee field to block header structure. This requires extending the `HeaderExtraFields` ADT:

```scala
sealed trait HeaderExtraFields
object HeaderExtraFields {
  case object HefEmpty extends HeaderExtraFields
  case class HefPostEcip1097(checkpoint: Option[Checkpoint]) extends HeaderExtraFields
  // New variant for EIP-1559/ECIP-1120:
  case class HefPostEcip1120(
      checkpoint: Option[Checkpoint], 
      baseFeePerGas: Option[BigInt]
  ) extends HeaderExtraFields
}
```

**Alternative Design:**
Since EIP-1559 was designed with the base fee as a standard field (not an "extra" field), consider adding it as a core field with `Option[BigInt]` type:

```scala
case class BlockHeader(
    // ... existing fields ...
    nonce: ByteString,
    baseFeePerGas: Option[BigInt] = None, // None for pre-EIP-1559 blocks
    extraFields: HeaderExtraFields = HefEmpty
)
```

**RLP Encoding Considerations:**
- Pre-EIP-1559 blocks: 15 fields (current)
- Post-ECIP-1097 blocks: 16 fields (with checkpoint)
- Post-ECIP-1120 blocks: 16 or 17 fields (with base fee)
- Backward compatibility must be maintained for block decoding

### 2. Base Fee Calculation

**Algorithm (from EIP-1559):**

```
Base Fee Adjustment:
  if parent_gas_used > parent_gas_target:
      base_fee_per_gas = parent_base_fee_per_gas + 
          max(1, parent_base_fee_per_gas * (parent_gas_used - parent_gas_target) 
              / parent_gas_target / BASE_FEE_MAX_CHANGE_DENOMINATOR)
  elif parent_gas_used < parent_gas_target:
      base_fee_per_gas = parent_base_fee_per_gas - 
          parent_base_fee_per_gas * (parent_gas_target - parent_gas_used) 
              / parent_gas_target / BASE_FEE_MAX_CHANGE_DENOMINATOR
  else:
      base_fee_per_gas = parent_base_fee_per_gas

Constants:
  BASE_FEE_MAX_CHANGE_DENOMINATOR = 8  (12.5% max change)
  ELASTICITY_MULTIPLIER = 2
  INITIAL_BASE_FEE = 1_000_000_000 (1 Gwei)
```

**Implementation Location:**
- New module: `com.chipprbots.ethereum.consensus.eip1559.BaseFeeCalculator`
- Integration point: `BlockPreparator` and `BlockValidator`

### 3. Treasury Payout Logic

**Current Implementation (ECIP-1098):**
Location: `BlockPreparator.payBlockReward()`

```scala
if (!treasuryEnabled(blockNumber) || !existsTreasuryContract) {
  // Pay 100% block reward to miner
  val minerReward = minerRewardForOmmers + minerRewardForBlock
  increaseAccountBalance(minerAddress, UInt256(minerReward))
} else {
  // Pay 80% to miner, 20% to treasury
  val minerReward = minerRewardForOmmers + 
      minerRewardForBlock * MinerRewardPercentageAfterECIP1098 / 100
  val treasuryReward = minerRewardForBlock * TreasuryRewardPercentageAfterECIP1098 / 100
  // ... distribute rewards ...
}
```

**Required Changes for ECIP-1120:**

The base fee must be calculated and redirected to treasury:

```scala
protected[ledger] def payBlockReward(
    block: Block,
    worldStateProxy: InMemoryWorldStateProxy,
    baseFeeAmount: BigInt  // NEW: Total base fee from all transactions
)(implicit blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy = {
  
  val blockNumber = block.header.number
  val minerRewardForBlock = blockRewardCalculator.calculateMiningRewardForBlock(blockNumber)
  val minerRewardForOmmers = blockRewardCalculator.calculateMiningRewardForOmmers(...)
  val minerAddress = Address(block.header.beneficiary)
  val treasuryAddress = blockchainConfig.treasuryAddress
  
  // Calculate priority fees (tips) - goes to miner
  val priorityFees = calculateTotalPriorityFees(block.body.transactionList)
  
  val worldAfterPayingRewards = 
    if (!ecip1120Enabled(blockNumber)) {
      // Pre-ECIP-1120: Traditional reward distribution
      // or ECIP-1098 style (80/20 split of block reward only)
      payTraditionalRewards(...)
    } else {
      // ECIP-1120: Base fee to treasury, tips + block reward to miner
      
      // 1. Pay base fee to treasury
      val worldAfterTreasury = increaseAccountBalance(
          treasuryAddress, 
          UInt256(baseFeeAmount)
      )(worldStateProxy)
      
      // 2. Pay miner: block reward + ommer rewards + priority fees
      val totalMinerReward = minerRewardForBlock + minerRewardForOmmers + priorityFees
      val worldAfterMiner = increaseAccountBalance(
          minerAddress,
          UInt256(totalMinerReward)
      )(worldAfterTreasury)
      
      log.debug(
        "ECIP-1120: Paying base fee {} to treasury {}, " +
        "paying miner reward {} (block: {}, ommers: {}, tips: {}) to miner {}",
        baseFeeAmount, treasuryAddress,
        totalMinerReward, minerRewardForBlock, minerRewardForOmmers, priorityFees,
        minerAddress
      )
      
      worldAfterMiner
    }
  
  // Pay ommer rewards
  block.body.uncleNodesList.foldLeft(worldAfterPayingRewards) { ... }
}
```

### 4. Block Validation Changes

**Consensus Rule Enforcement:**

Block validity must require that base fee is correctly handled. This involves:

1. **Base Fee Calculation Validation:**
   - Verify base fee in block header matches calculated value from parent block
   - Reject blocks with incorrect base fee

2. **Transaction Validation:**
   - EIP-1559 transactions must have `maxFeePerGas >= baseFeePerGas`
   - Transaction's priority fee correctly calculated as `min(maxPriorityFeePerGas, maxFeePerGas - baseFeePerGas)`

3. **Treasury Payout Validation:**
   - State transition must show treasury address balance increased by exact base fee amount
   - This is a **critical consensus rule** that makes treasury inclusion mandatory

**Implementation Location:**
- `BlockHeaderValidator`: Add base fee validation
- `BlockValidator`: Add treasury payout validation
- `SignedTransactionValidator`: Add EIP-1559 transaction validation

**Validation Pseudocode:**

```scala
def validateBaseFee(
    block: Block, 
    parent: BlockHeader
)(implicit config: BlockchainConfig): Either[BlockError, Unit] = {
  if (!ecip1120Enabled(block.header.number)) {
    // Pre-ECIP-1120: No base fee validation
    Right(())
  } else {
    block.header.baseFeePerGas match {
      case None => Left(BlockError("ECIP-1120 block missing base fee"))
      case Some(baseFee) =>
        val expectedBaseFee = calculateBaseFee(parent)
        if (baseFee == expectedBaseFee) Right(())
        else Left(BlockError(s"Invalid base fee: expected $expectedBaseFee, got $baseFee"))
    }
  }
}

def validateTreasuryPayout(
    block: Block,
    parentWorldState: WorldState,
    resultWorldState: WorldState
)(implicit config: BlockchainConfig): Either[BlockError, Unit] = {
  if (!ecip1120Enabled(block.header.number)) {
    Right(())
  } else {
    val totalBaseFee = calculateTotalBaseFee(block)
    val treasuryAddress = config.treasuryAddress
    
    val parentBalance = parentWorldState.getBalance(treasuryAddress)
    val resultBalance = resultWorldState.getBalance(treasuryAddress)
    val actualIncrease = resultBalance - parentBalance
    
    if (actualIncrease == totalBaseFee) Right(())
    else Left(BlockError(
      s"Treasury balance increase mismatch: expected $totalBaseFee, got $actualIncrease"
    ))
  }
}
```

### 5. Transaction Type Support

**Current Support:**
- Type 0 (Legacy): ✅ Supported
- Type 1 (EIP-2930): ✅ Supported (access lists)
- Type 2 (EIP-1559): ❌ Not currently supported

**Type 2 Transaction Structure:**
```
TransactionType || TransactionPayload

Where TransactionType = 0x02 for EIP-1559

TransactionPayload = RLP([
    chainId,
    nonce,
    maxPriorityFeePerGas,
    maxFeePerGas,
    gasLimit,
    to,
    value,
    data,
    accessList,
    signatureYParity,
    signatureR,
    signatureS
])
```

**Required Implementation:**
- New `Type02Transaction` case class in `domain/Transaction.scala`
- RLP encoding/decoding for Type 2 transactions
- Gas calculation logic using `maxFeePerGas` and `maxPriorityFeePerGas`
- Validation logic in `SignedTransactionValidator`

### 6. Configuration Schema

**Chain Configuration Extensions:**

```hocon
{
  # Existing config
  chain-id = "0x3d"
  
  # Treasury configuration (existing)
  treasury-address = "0011223344556677889900112233445566778899"
  ecip1098-block-number = "1000000000000000000"  # Disabled
  ecip1097-block-number = "1000000000000000000"  # Disabled
  
  # New: ECIP-1120 / EIP-1559 configuration
  ecip1120-block-number = "1000000000000000000"  # Activation block
  
  eip1559 {
    # Enable EIP-1559 transaction type support
    enabled = false
    
    # Fork block where EIP-1559 becomes active
    activation-block-number = "1000000000000000000"
    
    # Initial base fee when EIP-1559 activates (in Wei)
    initial-base-fee = "1000000000"  # 1 Gwei
    
    # Base fee adjustment parameters
    base-fee-max-change-denominator = 8  # 12.5% max change
    elasticity-multiplier = 2             # 2x gas target for limit
    
    # Gas target and limit
    # target = limit / elasticity-multiplier
  }
  
  # Treasury behavior with ECIP-1120
  treasury {
    # Treasury address for base fee redirection
    # Must match treasury-address for consistency
    ecip1120-address = ${treasury-address}
    
    # Require treasury address to exist as contract
    # If false, base fees may be sent to non-existent address (edge case)
    require-contract-exists = true
    
    # Treasury mode selection
    mode = "ecip1120"  # Options: "disabled", "ecip1098", "ecip1120"
  }
}
```

**Configuration Loading:**
- Extend `BlockchainConfig` class with EIP-1559/ECIP-1120 settings
- Add `Eip1559Config` case class for encapsulation
- Update fork block number tracking in `ForkBlockNumbers`

## Implementation Requirements

### Phase 1: Core Infrastructure (Foundation)

**Priority: Critical**

1. **Block Header Extensions**
   - Add `baseFeePerGas: Option[BigInt]` field to `BlockHeader`
   - Extend RLP encoding/decoding for new field
   - Update `HeaderExtraFields` ADT if using extra fields approach
   - Implement backward compatibility for block deserialization

2. **Base Fee Calculator**
   - Create `BaseFeeCalculator` utility class
   - Implement EIP-1559 base fee adjustment algorithm
   - Add unit tests for edge cases (initial fork, gas target hits, min/max changes)

3. **Configuration Schema**
   - Add `Eip1559Config` to blockchain configuration
   - Add ECIP-1120 activation block number
   - Add initial base fee configuration
   - Update configuration parsing and validation

### Phase 2: Transaction Support

**Priority: High**

4. **Type 2 Transaction Implementation**
   - Define `Type02Transaction` case class
   - Implement RLP encoding/decoding
   - Add signature validation for Type 2 transactions
   - Update transaction hash calculation
   - Update `SignedTransactionValidator` for Type 2 validation rules

5. **Transaction Fee Calculation**
   - Implement effective gas price calculation for Type 2 transactions
   - Calculate priority fees vs. base fees
   - Handle max fee refunds
   - Update gas accounting in transaction execution

6. **Receipt Updates**
   - Type 2 transactions use same receipt format as Type 1
   - Verify receipt encoding handles Type 2 correctly

### Phase 3: Consensus Integration

**Priority: Critical**

7. **Block Validation**
   - Implement `validateBaseFee()` in `BlockHeaderValidator`
   - Validate base fee calculation against parent block
   - Validate all transactions comply with current base fee
   - Add treasury payout validation in `BlockValidator`

8. **Block Preparation**
   - Update `BlockPreparator.payBlockReward()` for ECIP-1120 mode
   - Calculate total base fee from block transactions
   - Redirect base fee to treasury address
   - Pay priority fees + block rewards to miner
   - Update state transition logic

9. **Mining/Block Creation**
   - Update `PoWBlockCreator` to:
     - Calculate and include base fee in block header
     - Prioritize transactions by effective priority fee
     - Ensure total base fee correctly redirected
   - Update block reward calculation for mining

### Phase 4: Edge Cases and Safety

**Priority: High**

10. **Treasury Address Validation**
    - Validate treasury address exists (if configured)
    - Handle case where treasury address is not a contract
    - Consider treasury address self-destruct scenario
    - Log warnings for treasury configuration issues

11. **Fork Transition Logic**
    - Handle transition from pre-EIP-1559 to post-EIP-1559 blocks
    - Initialize base fee at fork block
    - Ensure first EIP-1559 block has correct initial base fee
    - Validate backward compatibility

12. **Error Handling**
    - Define comprehensive error types for EIP-1559 failures
    - Add detailed logging for debugging
    - Ensure graceful degradation on configuration errors

### Phase 5: Testing and Documentation

**Priority: High**

13. **Unit Tests**
    - Base fee calculation tests (all edge cases)
    - Transaction Type 2 encoding/decoding tests
    - Block header serialization tests
    - Treasury payout validation tests

14. **Integration Tests**
    - Full block execution with ECIP-1120 active
    - Fork transition tests
    - Mixed transaction type blocks
    - Treasury balance verification

15. **Documentation**
    - ADR for ECIP-1120 implementation
    - Configuration guide for operators
    - Migration guide for existing deployments
    - API documentation updates

## Interaction with Existing Features

### ECIP-1097/1098 Treasury (Current Implementation)

**Current Behavior:**
- Block rewards split 80/20 between miner and treasury
- Treasury funded from newly minted coins (inflation-based)
- Activation controlled by `ecip1098-block-number`

**ECIP-1120 Behavior:**
- Base fees redirected to treasury (fee-based, not inflation-based)
- Block rewards go 100% to miners (no split)
- Tips (priority fees) go 100% to miners

**Compatibility Modes:**

1. **Mode: Disabled** (Default for ETC mainnet)
   - No treasury
   - All rewards to miners
   - No base fee mechanism

2. **Mode: ECIP-1098** (Inflation-based treasury)
   - 80% block reward to miner
   - 20% block reward to treasury
   - No base fee mechanism

3. **Mode: ECIP-1120** (Fee-based treasury)
   - 100% block reward to miner
   - 100% priority fees to miner
   - 100% base fees to treasury
   - Requires EIP-1559 enabled

**Configuration Conflicts:**
- Cannot enable both ECIP-1098 and ECIP-1120 simultaneously
- Configuration validation must enforce mutual exclusivity
- Migration path from ECIP-1098 to ECIP-1120 requires hard fork

### Block Reward Calculation

**Current Implementation:**
`BlockRewardCalculator` computes rewards based on:
- Era-based reduction (ECIP-1017)
- Fork-specific adjustments (Byzantium, Constantinople)
- Ommer rewards

**ECIP-1120 Impact:**
- Block reward calculation unchanged
- Distribution mechanism changed (separate from base fees)
- Treasury receives base fees instead of percentage of block rewards

**Code Location:**
- `BlockRewardCalculator.scala`: No changes needed
- `BlockPreparator.payBlockReward()`: Requires modification

### Gas Accounting

**Current Gas Calculation:**
```
Transaction Cost = gasUsed * gasPrice
```

**EIP-1559 Gas Calculation:**
```
Effective Gas Price = min(maxFeePerGas, baseFeePerGas + maxPriorityFeePerGas)
Transaction Cost = gasUsed * effectiveGasPrice

Distribution:
  Base Fee Portion = gasUsed * baseFeePerGas → Treasury
  Priority Fee Portion = gasUsed * (effectiveGasPrice - baseFeePerGas) → Miner
```

**Backward Compatibility:**
- Legacy transactions (Type 0) still use `gasPrice`
- `gasPrice` is treated as both `maxFeePerGas` and `maxPriorityFeePerGas` for legacy transactions

## Risk Analysis

### Consensus Risks

**Risk 1: Chain Split from Base Fee Mismatch**
- **Description:** Nodes with different base fee calculations produce incompatible blocks
- **Impact:** Critical - Network fork
- **Mitigation:**
  - Comprehensive unit tests for base fee calculation
  - Reference implementation alignment with Ethereum
  - Testnet validation before mainnet deployment
  - Coordination with other ETC clients

**Risk 2: Treasury Payout Validation Failure**
- **Description:** Blocks may be rejected if treasury payout validation is too strict or has bugs
- **Impact:** High - Block propagation failures
- **Mitigation:**
  - Thorough testing of edge cases
  - Graceful handling of non-existent treasury addresses
  - Detailed error logging
  - Phased rollout with monitoring

**Risk 3: Transaction Type Confusion**
- **Description:** Incorrect handling of Type 2 transactions could cause transaction failures
- **Impact:** Medium - User transaction failures
- **Mitigation:**
  - Comprehensive transaction validation tests
  - Clear error messages for invalid transactions
  - API documentation for Type 2 transaction format

### Economic Risks

**Risk 4: Treasury Address Configuration Error**
- **Description:** Incorrect treasury address could send base fees to wrong recipient
- **Impact:** Critical - Loss of funds
- **Mitigation:**
  - Configuration validation on startup
  - Checksum verification for addresses
  - Require explicit operator confirmation for treasury changes
  - Document correct treasury address for each network

**Risk 5: Base Fee Manipulation Attempts**
- **Description:** Miners might attempt to manipulate base fee through strategic block construction
- **Impact:** Low-Medium - Fee market inefficiency
- **Mitigation:**
  - EIP-1559 algorithm is designed to resist manipulation
  - Monitor base fee behavior on testnet
  - Document expected base fee dynamics

### Implementation Risks

**Risk 6: RLP Encoding Incompatibility**
- **Description:** Block header RLP changes could break P2P communication
- **Impact:** Critical - Network connectivity loss
- **Mitigation:**
  - Follow EIP-1559 specification exactly
  - Test with other clients (Core-Geth, Besu)
  - Phased rollout with compatibility checks

**Risk 7: Performance Degradation**
- **Description:** Base fee calculation adds computational overhead
- **Impact:** Low - Marginal performance impact
- **Mitigation:**
  - Base fee calculation is simple arithmetic
  - Profile performance impact
  - Optimize if needed

**Risk 8: State Transition Bugs**
- **Description:** Errors in treasury payout could corrupt world state
- **Impact:** Critical - Chain halt or invalid state
- **Mitigation:**
  - Extensive integration testing
  - Property-based testing for state transitions
  - Formal verification where feasible

## Open Questions and Blockers

### Technical Questions

**Q1: Treasury Address Contract Requirements**
- Should the treasury address be required to be a smart contract?
- What happens if the treasury address is an EOA?
- What if treasury contract self-destructs?

**Recommendation:** 
- Allow both contract and EOA addresses for flexibility
- Log warnings if treasury address doesn't exist
- Document best practices for treasury contract design

**Q2: Orphaned Block Handling**
- How should base fees be handled in uncle/ommer blocks?
- Are ommer base fees redirected to treasury?

**Recommendation:**
- Ommers don't contain transactions, so no base fees to redirect
- Only canonical block base fees go to treasury
- Document this behavior clearly

**Q3: Transaction Pool Validation**
- How should the transaction pool validate Type 2 transactions before a block is created?
- What base fee should be used for validation?

**Recommendation:**
- Use parent block's base fee + 12.5% as minimum for pool acceptance
- Allow configurable margin for safety
- Document transaction pool behavior

**Q4: Configuration Migration Path**
- How should operators migrate from ECIP-1098 to ECIP-1120?
- Can both be active in different eras?

**Recommendation:**
- Mutual exclusivity enforcement in configuration
- Clear documentation of migration procedure
- Require hard fork for switching modes

### Governance Questions

**Q5: Community Consensus**
- What is the current community stance on ECIP-1120?
- Is there consensus among ETC stakeholders?

**Current Status (as of research):**
- ECIP-1120 is part of proposed Olympia Upgrade
- Requires community consensus before activation
- Previous treasury proposals (ECIP-1097/1098) were withdrawn

**Recommendation:**
- Implement with treasury disabled by default
- Allow testnet/private network activation for testing
- Defer mainnet activation to community governance

**Q6: Interoperability with Other Clients**
- Have other ETC clients (Core-Geth, Besu) implemented ECIP-1120?
- Is there a coordinated activation plan?

**Recommendation:**
- Coordinate with other client teams
- Ensure specification alignment
- Joint testnet validation

### Edge Cases

**Q7: Genesis Block Base Fee**
- What base fee should the first post-fork block have?

**Recommendation:**
- Use configurable `initial-base-fee` parameter (default: 1 Gwei)
- Document rationale for initial value
- Allow network-specific tuning

**Q8: Zero Base Fee Blocks**
- Can base fee ever reach zero?
- What happens if base fee becomes zero?

**Recommendation:**
- EIP-1559 has minimum base fee of 1 wei (implicitly)
- Ensure implementation enforces minimum > 0
- Document minimum base fee behavior

**Q9: Maximum Base Fee**
- Is there a maximum base fee?
- Could base fee grow unbounded?

**Recommendation:**
- No explicit maximum in EIP-1559
- Practical maximum limited by gas price users willing to pay
- Monitor base fee behavior on testnet

## Implementation Roadmap

### Stage 1: Research and Design (Current)
- ✅ Analyze ECIP-1120 specification
- ✅ Analyze EIP-1559 specification
- ✅ Document technical requirements
- ✅ Identify risks and open questions
- ⏳ Create ADR for implementation approach

**Duration:** 2 weeks  
**Deliverables:** This document, ADR draft

### Stage 2: Foundation (Estimated: 4-6 weeks)
- Implement block header extensions
- Implement base fee calculator
- Add configuration schema
- Unit tests for core functionality

**Key Milestones:**
- Block header can serialize/deserialize with base fee
- Base fee calculation matches EIP-1559 spec
- Configuration loads correctly

### Stage 3: Transaction Support (Estimated: 3-4 weeks)
- Implement Type 2 transaction encoding/decoding
- Add transaction validation for Type 2
- Update transaction fee calculation
- Unit tests for transaction handling

**Key Milestones:**
- Type 2 transactions can be created and validated
- Transaction pool accepts valid Type 2 transactions
- Fee calculation is correct

### Stage 4: Consensus Integration (Estimated: 4-6 weeks)
- Implement block validation for base fee
- Update block preparation for treasury payouts
- Integrate with mining logic
- Integration tests for full block execution

**Key Milestones:**
- Blocks with ECIP-1120 can be validated
- Mining produces valid ECIP-1120 blocks
- Treasury receives base fees correctly

### Stage 5: Testing and Hardening (Estimated: 3-4 weeks)
- Comprehensive test suite
- Edge case testing
- Performance testing
- Cross-client compatibility testing

**Key Milestones:**
- All tests pass
- Performance acceptable
- Compatible with other clients

### Stage 6: Documentation and Release (Estimated: 2 weeks)
- Operator documentation
- API documentation
- Migration guide
- Release notes

**Key Milestones:**
- Documentation complete
- Configuration examples provided
- Release candidate ready

**Total Estimated Duration:** 18-24 weeks (4-6 months)

## Recommendations

### For Implementation

1. **Adopt Modular Design**
   - Separate EIP-1559 base fee logic from ECIP-1120 treasury logic
   - Allow EIP-1559 to be enabled without ECIP-1120 (base fee burning)
   - Make treasury redirection a separate, optional feature

2. **Prioritize Testing**
   - Invest heavily in unit and integration tests
   - Use property-based testing for fee calculations
   - Test fork transition scenarios thoroughly

3. **Follow Specification Exactly**
   - Align with EIP-1559 reference implementation
   - Don't deviate from specification without strong justification
   - Document any ETC-specific modifications

4. **Configuration Flexibility**
   - Support multiple treasury modes (disabled, ECIP-1098, ECIP-1120)
   - Make all parameters configurable for testnet experimentation
   - Provide sensible defaults for mainnet

5. **Comprehensive Validation**
   - Validate base fee calculation at consensus level
   - Validate treasury payout in state transition
   - Reject invalid blocks early with clear error messages

### For Deployment

1. **Staged Rollout**
   - Deploy first on private testnets
   - Then on public ETC testnet (Mordor)
   - Finally coordinate mainnet activation with community

2. **Monitoring and Metrics**
   - Add Prometheus metrics for base fee tracking
   - Monitor treasury balance changes
   - Track Type 2 transaction adoption

3. **Operator Communication**
   - Provide clear upgrade instructions
   - Document breaking changes
   - Offer configuration migration tools

4. **Coordination with Ecosystem**
   - Work with other ETC client teams
   - Coordinate with mining pools
   - Engage with ETC community governance

### For Future Considerations

1. **Treasury Governance**
   - ECIP-1120 only handles accumulation
   - Treasury spending requires ECIP-1113/1114 (governance layer)
   - Consider if fukuii should support governance smart contracts

2. **Fee Market Analysis**
   - Monitor base fee dynamics after deployment
   - Compare with Ethereum's behavior
   - Adjust parameters if needed (requires fork)

3. **Alternative Treasury Models**
   - Consider hybrid models (partial burning, partial treasury)
   - Evaluate economic impacts
   - Document tradeoffs

## References

### ECIPs (Ethereum Classic Improvement Proposals)

- **ECIP-1097**: Checkpointing and Treasury System
- **ECIP-1098**: Proto-Treasury System (Withdrawn)
- **ECIP-1120**: EIP-1559 adaptation for ETC (Olympia Upgrade)
  - Official Site: https://ecip1120.dev/
  - Specification: https://ecips.ethereumclassic.org/ECIPs/ecip-1111

### EIPs (Ethereum Improvement Proposals)

- **EIP-1559**: Fee market change for ETH 1.0 chain
  - Specification: https://eips.ethereum.org/EIPS/eip-1559
  - Base fee burning mechanism
  - Dynamic fee adjustment algorithm

- **EIP-2930**: Access Lists (Transaction Type 1)
- **EIP-658**: Embedding transaction status code in receipts

### Olympia Upgrade Suite

- **ECIP-1111**: Olympia EVM and Protocol Upgrades
- **ECIP-1112**: Olympia Treasury
- **ECIP-1113**: Treasury Governance
- **ECIP-1114**: Treasury Withdrawal Mechanism
- **ECIP-1115**: Miner Payout Smoothing

### Code References

**Fukuii Implementation:**
- `BlockHeader.scala`: Block header structure with extra fields
- `BlockRewardCalculator.scala`: Current reward calculation
- `BlockPreparator.scala`: Block execution and reward distribution
- `BlockValidator.scala`: Block validation logic
- `Receipt.scala`: Transaction receipt handling (Type 0, 1 support)

**Configuration:**
- `etc-chain.conf`: ETC mainnet configuration
- `BlockchainConfig.scala`: Configuration data structures

## Conclusion

ECIP-1120 represents a significant architectural change to Ethereum Classic's fee mechanism, redirecting EIP-1559 base fees to a treasury instead of burning them. This aligns with ETC's non-deflationary monetary policy while providing sustainable ecosystem funding.

**Key Takeaways:**

1. **Feasibility**: Implementation is technically feasible with fukuii's current architecture
2. **Complexity**: Moderate complexity - requires changes to block headers, validation, and reward distribution
3. **Precedent**: ECIP-1097/1098 provide architectural patterns for treasury handling
4. **Risk**: Main risks are consensus-level - require thorough testing and coordination
5. **Timeline**: Estimated 4-6 months for complete implementation and testing

**Critical Success Factors:**

- Exact alignment with EIP-1559 specification
- Comprehensive testing, especially fork transition
- Coordination with other ETC clients
- Community consensus on activation

**Next Steps:**

1. Create formal ADR (Architecture Decision Record)
2. Develop detailed technical design document
3. Begin Phase 1 implementation (foundation layer)
4. Establish testnet for validation
5. Coordinate with ETC community on governance timeline

---

**Document Prepared By:** Fukuii Research Team  
**Review Status:** Pending Review  
**Last Updated:** 2025-12-30
