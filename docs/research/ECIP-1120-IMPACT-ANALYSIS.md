# ECIP-1120 Impact Analysis: Basefee Market with Miner Rewards

**Document Type:** Research Analysis  
**Status:** Draft  
**Date:** 2025-12-30  
**Related ECIPs:** ECIP-1120  
**Related EIPs:** EIP-1559  

## Executive Summary

This document analyzes the technical and design implications of supporting ECIP-1120 "Basefee Market with Miner Rewards" in fukuii. ECIP-1120 adapts Ethereum's EIP-1559 fee mechanism for Ethereum Classic by implementing a dynamic basefee market where the basefee is paid to miners (not burned), preserving PoW miner incentives and ETC's monetary policy.

**Key Findings:**
- ECIP-1120 implements EIP-1559 basefee mechanism with basefee going to miners
- Implementation requires adding EIP-1559 base fee fields to block headers
- No treasury component - all fees go to miners
- Simpler than treasury-based alternatives (no governance layer needed)
- Configuration system must support optional basefee activation

## Background

### Current State in Fukuii

Fukuii currently uses traditional Ethereum gas pricing:
- Transactions specify a `gasPrice`
- Total transaction cost = `gasUsed * gasPrice`
- All transaction fees go to the block miner
- No dynamic fee adjustment mechanism

### ECIP-1120 Overview

ECIP-1120 "Basefee Market with Miner Rewards" adapts EIP-1559 for Ethereum Classic:

**Core Differences from Ethereum's EIP-1559:**
- **Ethereum**: Base fee is burned (deflationary)
- **ETC (ECIP-1120)**: Base fee goes to miner (preserves PoW incentives)

**Transaction Fee Structure:**
```
Total User Fee = Base Fee + Priority Fee (Tip)
Total Miner Reward = Block Reward + Base Fee + Priority Fee
```

**Distribution:**
- Base Fee → Miner (predictable component)
- Priority Fee → Miner (tip for faster inclusion)
- Block Reward → Miner (unchanged)

**Key Benefits:**
- Predictable transaction fees for users
- Dynamic adjustment based on network congestion
- Full miner compensation (no burning)
- No treasury governance complexity
- Preserves ETC monetary policy

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

### 3. Miner Reward Distribution Logic

**Current Implementation:**
Location: `BlockPreparator.payBlockReward()`

```scala
protected[ledger] def payBlockReward(
    block: Block,
    worldStateProxy: InMemoryWorldStateProxy
)(implicit blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy = {
  val blockNumber = block.header.number
  val minerRewardForBlock = blockRewardCalculator.calculateMiningRewardForBlock(blockNumber)
  val minerRewardForOmmers = blockRewardCalculator.calculateMiningRewardForOmmers(...)
  val minerAddress = Address(block.header.beneficiary)
  
  // Current: Pay block reward + ommer rewards
  val minerReward = minerRewardForOmmers + minerRewardForBlock
  increaseAccountBalance(minerAddress, UInt256(minerReward))(worldStateProxy)
}
```

**Required Changes for ECIP-1120:**

The base fee and priority fees must be calculated and paid to miner:

```scala
protected[ledger] def payBlockReward(
    block: Block,
    worldStateProxy: InMemoryWorldStateProxy
)(implicit blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy = {
  
  val blockNumber = block.header.number
  val minerRewardForBlock = blockRewardCalculator.calculateMiningRewardForBlock(blockNumber)
  val minerRewardForOmmers = blockRewardCalculator.calculateMiningRewardForOmmers(...)
  val minerAddress = Address(block.header.beneficiary)
  
  val (totalBaseFee, totalPriorityFee) = if (ecip1120Enabled(blockNumber)) {
    // ECIP-1120: Calculate base fees and priority fees from transactions
    calculateTransactionFees(block.body.transactionList, block.header.baseFeePerGas)
  } else {
    // Pre-ECIP-1120: All fees are "priority fees" (no basefee concept)
    (BigInt(0), calculateTotalLegacyFees(block.body.transactionList))
  }
  
  // Pay miner: block reward + ommer rewards + all transaction fees
  val totalMinerReward = minerRewardForBlock + minerRewardForOmmers + totalBaseFee + totalPriorityFee
  
  val worldAfterMinerReward = increaseAccountBalance(
      minerAddress,
      UInt256(totalMinerReward)
  )(worldStateProxy)
  
  log.debug(
    "ECIP-1120: Paying total miner reward {} (block: {}, ommers: {}, basefee: {}, priority: {}) to miner {}",
    totalMinerReward, minerRewardForBlock, minerRewardForOmmers, totalBaseFee, totalPriorityFee,
    minerAddress
  )
  
  // Pay ommer rewards
  block.body.uncleNodesList.foldLeft(worldAfterMinerReward) { (ws, ommer) =>
    val ommerAddress = Address(ommer.beneficiary)
    val ommerReward = blockRewardCalculator.calculateOmmerRewardForInclusion(blockNumber, ommer.number)
    increaseAccountBalance(ommerAddress, UInt256(ommerReward))(ws)
  }
}

private def calculateTransactionFees(
    transactions: Seq[SignedTransaction],
    baseFeePerGas: Option[BigInt]
): (BigInt, BigInt) = {
  baseFeePerGas match {
    case Some(baseFee) =>
      // Type 2 transactions: separate base fee and priority fee
      val fees = transactions.map { tx =>
        val effectiveGasPrice = calculateEffectiveGasPrice(tx, baseFee)
        val baseFeeAmount = tx.tx.gasLimit * baseFee
        val priorityFeeAmount = tx.tx.gasLimit * (effectiveGasPrice - baseFee)
        (baseFeeAmount, priorityFeeAmount)
      }
      fees.foldLeft((BigInt(0), BigInt(0))) { case ((accBase, accPriority), (base, priority)) =>
        (accBase + base, accPriority + priority)
      }
    case None =>
      // Legacy transactions: no basefee
      (BigInt(0), calculateTotalLegacyFees(transactions))
  }
}
```

**Key Changes:**
- Miner receives **all** transaction fees (basefee + priority fee)
- No treasury component
- Simpler than treasury-based models
- Preserves total miner compensation

### 4. Block Validation Changes

**Consensus Rule Enforcement:**

Block validity must require that base fee is correctly calculated. This involves:

1. **Base Fee Calculation Validation:**
   - Verify base fee in block header matches calculated value from parent block
   - Reject blocks with incorrect base fee

2. **Transaction Validation:**
   - EIP-1559 transactions must have `maxFeePerGas >= baseFeePerGas`
   - Transaction's priority fee correctly calculated as `min(maxPriorityFeePerGas, maxFeePerGas - baseFeePerGas)`

3. **Fee Distribution Validation:**
   - All transaction fees (basefee + priority fee) go to miner
   - No separate validation needed - standard balance increase validation applies

**Implementation Location:**
- `BlockHeaderValidator`: Add base fee validation
- `BlockValidator`: Verify miner receives all fees
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

def validateMinerReward(
    block: Block,
    parentWorldState: WorldState,
    resultWorldState: WorldState
)(implicit config: BlockchainConfig): Either[BlockError, Unit] = {
  // Standard validation - ensure miner balance increased correctly
  // This works for both pre and post ECIP-1120 blocks
  val minerAddress = Address(block.header.beneficiary)
  val expectedReward = calculateTotalMinerReward(block)
  
  val parentBalance = parentWorldState.getBalance(minerAddress)
  val resultBalance = resultWorldState.getBalance(minerAddress)
  val actualIncrease = resultBalance - parentBalance
  
  if (actualIncrease >= expectedReward) Right(())
  else Left(BlockError(
    s"Miner reward mismatch: expected at least $expectedReward, got $actualIncrease"
  ))
}
```

**Note:** ECIP-1120 validation is simpler than treasury-based models because:
- No separate treasury address to validate
- No split validation needed
- Standard miner balance validation applies

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
  
  # New: ECIP-1120 / EIP-1559 configuration
  ecip1120-block-number = "1000000000000000000"  # Activation block
  
  eip1559 {
    # Enable EIP-1559 transaction type support and basefee mechanism
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
}
```

**Configuration Loading:**
- Extend `BlockchainConfig` class with EIP-1559/ECIP-1120 settings
- Add `Eip1559Config` case class for encapsulation
- Update fork block number tracking in `ForkBlockNumbers`

**Simpler Configuration:**
- No treasury address needed
- No governance configuration needed
- Standard miner reward distribution

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
   - Add miner reward validation in `BlockValidator`

8. **Block Preparation**
   - Update `BlockPreparator.payBlockReward()` for ECIP-1120 mode
   - Calculate total base fee from block transactions
   - Pay all fees (basefee + priority fee) to miner
   - Update state transition logic

9. **Mining/Block Creation**
   - Update `PoWBlockCreator` to:
     - Calculate and include base fee in block header
     - Prioritize transactions by effective priority fee
     - Ensure miner receives all transaction fees
   - Update block reward calculation for mining

### Phase 4: Edge Cases and Safety

**Priority: High**

10. **Fork Transition Logic**
    - Handle transition from pre-EIP-1559 to post-EIP-1559 blocks
    - Initialize base fee at fork block
    - Ensure first EIP-1559 block has correct initial base fee
    - Validate backward compatibility

11. **Error Handling**
    - Define comprehensive error types for EIP-1559 failures
    - Add detailed logging for debugging
    - Ensure graceful degradation on configuration errors

12. **Gas Price Calculation**
    - Handle legacy transactions in ECIP-1120 blocks
    - Ensure correct effective gas price for mixed transaction types
    - Validate refund calculations

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
    - Miner balance verification

15. **Documentation**
    - ADR for ECIP-1120 implementation
    - Configuration guide for operators
    - Migration guide for existing deployments
    - API documentation updates

## Interaction with Existing Features

### Current Reward Distribution

**Current Behavior:**
- Transaction fees calculated as `gasUsed * gasPrice`
- All fees go to block miner
- Block reward calculated based on era (ECIP-1017)
- Ommer rewards paid separately

**ECIP-1120 Behavior:**
- Transaction fees split into basefee and priority fee
- **All fees go to miner** (basefee + priority fee)
- Block reward calculation unchanged
- Total miner compensation = block reward + all transaction fees

**Key Difference:**
- Fee calculation method changes (dynamic basefee)
- Fee destination unchanged (all to miner)
- No treasury component

### Block Reward Calculation

**Current Implementation:**
`BlockRewardCalculator` computes rewards based on:
- Era-based reduction (ECIP-1017)
- Fork-specific adjustments (Byzantium, Constantinople)
- Ommer rewards

**ECIP-1120 Impact:**
- Block reward calculation unchanged
- Transaction fee calculation changed (basefee mechanism)
- All fees still go to miner

**Code Location:**
- `BlockRewardCalculator.scala`: No changes needed
- `BlockPreparator.payBlockReward()`: Requires modification

### Gas Accounting

**Current Gas Calculation:**
```
Transaction Cost = gasUsed * gasPrice
All Fees → Miner
```

**ECIP-1120 Gas Calculation:**
```
Effective Gas Price = min(maxFeePerGas, baseFeePerGas + maxPriorityFeePerGas)
Transaction Cost = gasUsed * effectiveGasPrice

Distribution:
  Base Fee Portion = gasUsed * baseFeePerGas → Miner
  Priority Fee Portion = gasUsed * (effectiveGasPrice - baseFeePerGas) → Miner
  
Total to Miner = gasUsed * effectiveGasPrice
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

**Risk 2: Transaction Validation Failure**
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

**Risk 3: Base Fee Manipulation Attempts**
- **Description:** Miners might attempt to manipulate base fee through strategic block construction
- **Impact:** Low-Medium - Fee market inefficiency
- **Mitigation:**
  - EIP-1559 algorithm is designed to resist manipulation
  - Monitor base fee behavior on testnet
  - Document expected base fee dynamics

**Risk 4: Miner Revenue Impact**
- **Description:** Change in fee structure could affect miner profitability
- **Impact:** Low - All fees still go to miners
- **Mitigation:**
  - ECIP-1120 preserves all miner revenue (no burning, no treasury)
  - Actually increases fee predictability for miners
  - Clear communication about revenue preservation

### Implementation Risks

**Risk 5: RLP Encoding Incompatibility**
- **Description:** Block header RLP changes could break P2P communication
- **Impact:** Critical - Network connectivity loss
- **Mitigation:**
  - Follow EIP-1559 specification exactly
  - Test with other clients (Core-Geth, Besu)
  - Phased rollout with compatibility checks

**Risk 6: Performance Degradation**
- **Description:** Base fee calculation adds computational overhead
- **Impact:** Low - Marginal performance impact
- **Mitigation:**
  - Base fee calculation is simple arithmetic
  - Profile performance impact
  - Optimize if needed

**Risk 7: State Transition Bugs**
- **Description:** Errors in miner payout could corrupt world state
- **Impact:** High - Invalid state transitions
- **Mitigation:**
  - Extensive integration testing
  - Property-based testing for state transitions
  - Formal verification where feasible

## Open Questions and Blockers

### Technical Questions

**Q1: Orphaned Block Handling**
- How should base fees be handled in uncle/ommer blocks?
- Do ommers have basefee fields?

**Recommendation:**
- Ommers don't contain transactions, so no base fees to calculate
- Ommer headers should still have basefee field for consistency
- Only canonical block transactions generate fees
- Document this behavior clearly

**Q2: Transaction Pool Validation**
- How should the transaction pool validate Type 2 transactions before a block is created?
- What base fee should be used for validation?

**Recommendation:**
- Use parent block's base fee + 12.5% as minimum for pool acceptance
- Allow configurable margin for safety
- Document transaction pool behavior

**Q3: Legacy Transaction Handling**
- How should legacy (Type 0) transactions be handled in ECIP-1120 blocks?
- What is the effective priority fee for legacy transactions?

**Recommendation:**
- Legacy transaction `gasPrice` treated as both max fee and max priority fee
- Effective priority fee = gasPrice - baseFeePerGas
- If gasPrice < baseFeePerGas, transaction is invalid
- Clear documentation for wallet developers

### Governance Questions

**Q4: Community Consensus**
- What is the current community stance on ECIP-1120?
- Is there consensus among ETC stakeholders?

**Current Status (as of research):**
- ECIP-1120 "Basefee Market with Miner Rewards" under development
- Requires community consensus before activation
- Simpler than alternative treasury-based proposals

**Recommendation:**
- Implement with basefee mechanism disabled by default
- Allow testnet/private network activation for testing
- Defer mainnet activation to community governance

**Q5: Interoperability with Other Clients**
- Have other ETC clients (Core-Geth, Besu) implemented ECIP-1120?
- Is there a coordinated activation plan?

**Recommendation:**
- Coordinate with other client teams
- Ensure specification alignment
- Joint testnet validation

### Edge Cases

**Q6: Genesis Block Base Fee**
- What base fee should the first post-fork block have?

**Recommendation:**
- Use configurable `initial-base-fee` parameter (default: 1 Gwei)
- Document rationale for initial value
- Allow network-specific tuning

**Q7: Zero Base Fee Blocks**
- Can base fee ever reach zero?
- What happens if base fee becomes zero?

**Recommendation:**
- EIP-1559 has minimum base fee of 1 wei (implicitly)
- Ensure implementation enforces minimum > 0
- Document minimum base fee behavior

**Q8: Maximum Base Fee**
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

**Duration:** 1-2 weeks  
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

### Stage 4: Consensus Integration (Estimated: 3-4 weeks)
- Implement block validation for base fee
- Update block preparation for miner payouts
- Integrate with mining logic
- Integration tests for full block execution

**Key Milestones:**
- Blocks with ECIP-1120 can be validated
- Mining produces valid ECIP-1120 blocks
- Miners receive all transaction fees correctly

### Stage 5: Testing and Hardening (Estimated: 2-3 weeks)
- Comprehensive test suite
- Edge case testing
- Performance testing
- Cross-client compatibility testing

**Key Milestones:**
- All tests pass
- Performance acceptable
- Compatible with other clients

### Stage 6: Documentation and Release (Estimated: 1-2 weeks)
- Operator documentation
- API documentation
- Migration guide
- Release notes

**Key Milestones:**
- Documentation complete
- Configuration examples provided
- Release candidate ready

**Total Estimated Duration:** 15-21 weeks (4-5 months)

## Recommendations

### For Implementation

1. **Adopt Modular Design**
   - Separate EIP-1559 base fee logic as independent component
   - ECIP-1120 is simpler: just directs all fees to miner
   - No separate treasury logic needed

2. **Prioritize Testing**
   - Invest heavily in unit and integration tests
   - Use property-based testing for fee calculations
   - Test fork transition scenarios thoroughly

3. **Follow Specification Exactly**
   - Align with EIP-1559 reference implementation
   - Don't deviate from specification without strong justification
   - Document any ETC-specific modifications

4. **Configuration Simplicity**
   - Simple enable/disable for ECIP-1120
   - Make all parameters configurable for testnet experimentation
   - Provide sensible defaults for mainnet

5. **Comprehensive Validation**
   - Validate base fee calculation at consensus level
   - Validate miner receives all fees correctly
   - Reject invalid blocks early with clear error messages

### For Deployment

1. **Staged Rollout**
   - Deploy first on private testnets
   - Then on public ETC testnet (Mordor)
   - Finally coordinate mainnet activation with community

2. **Monitoring and Metrics**
   - Add Prometheus metrics for base fee tracking
   - Monitor miner fee revenue
   - Track Type 2 transaction adoption

3. **Operator Communication**
   - Provide clear upgrade instructions
   - Document breaking changes
   - Explain benefits to miners (revenue preservation)

4. **Coordination with Ecosystem**
   - Work with other ETC client teams
   - Coordinate with mining pools
   - Engage with ETC community governance

### For Future Considerations

1. **Miner Communication**
   - ECIP-1120 preserves all miner revenue
   - Actually improves fee predictability
   - No burning, no treasury split

2. **Fee Market Analysis**
   - Monitor base fee dynamics after deployment
   - Compare with Ethereum's behavior (noting miner vs burning difference)
   - Adjust parameters if needed (requires fork)

3. **Alternative Approaches**
   - ECIP-1120 is simpler than treasury-based alternatives
   - Preserves PoW miner incentives
   - No governance complexity

## References

### ECIPs (Ethereum Classic Improvement Proposals)

- **ECIP-1120**: "Basefee Market with Miner Rewards"
  - Official Site: https://ecip1120.dev/
  - EIP-1559 adaptation for ETC with basefee going to miners

### EIPs (Ethereum Improvement Proposals)

- **EIP-1559**: Fee market change for ETH 1.0 chain
  - Specification: https://eips.ethereum.org/EIPS/eip-1559
  - Base fee burning mechanism (ETC differs: pays miners)
  - Dynamic fee adjustment algorithm

- **EIP-2930**: Access Lists (Transaction Type 1)
- **EIP-658**: Embedding transaction status code in receipts

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

ECIP-1120 "Basefee Market with Miner Rewards" adapts Ethereum's EIP-1559 fee mechanism for Ethereum Classic by directing the basefee to miners instead of burning it. This preserves PoW miner incentives and ETC's monetary policy while providing the benefits of predictable transaction fees.

**Key Takeaways:**

1. **Feasibility**: Implementation is technically feasible with fukuii's current architecture
2. **Complexity**: Moderate complexity - requires changes to block headers, validation, and reward distribution
3. **Simplicity**: Simpler than treasury-based alternatives (no governance layer needed)
4. **Miner-Friendly**: All fees go to miners (no burning, no treasury split)
5. **Timeline**: Estimated 4-5 months for complete implementation and testing

**Critical Success Factors:**

- Exact alignment with EIP-1559 specification (except basefee destination)
- Comprehensive testing, especially fork transition
- Coordination with other ETC clients
- Community consensus on activation

**Advantages of ECIP-1120 Approach:**

- Preserves 100% of fees for miners
- No treasury governance complexity
- Simpler implementation and validation
- Maintains PoW incentive structure
- Predictable fees for users

**Next Steps:**

1. Create formal ADR (Architecture Decision Record)
2. Develop detailed technical design document
3. Begin Phase 1 implementation (foundation layer)
4. Establish testnet for validation
5. Coordinate with ETC community on governance timeline

---

**Document Prepared By:** Fukuii Research Team  
**Review Status:** Revised per ECIP-1120 Specification  
**Last Updated:** 2025-12-30
