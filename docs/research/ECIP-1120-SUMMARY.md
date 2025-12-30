# ECIP-1120 Implementation Summary

**Quick Reference Guide**

## Overview

ECIP-1120 "Basefee Market with Miner Rewards" adapts EIP-1559 for Ethereum Classic with all basefees going to miners.

## Key Differences: Ethereum vs. ETC

| Aspect | Ethereum (EIP-1559) | ETC (ECIP-1120) |
|--------|-------------------|----------------|
| Base Fee Destination | Burned (deflationary) | **Miners** (non-deflationary) |
| Priority Fee | To validators | To miners |
| Block Reward | To validators | To miners (unchanged) |
| Consensus Model | Proof of Stake | Proof of Work |

## Required Modifications

### 1. Block Header
**Change:** Add `baseFeePerGas: Option[BigInt]` field

**Impact:** 
- RLP encoding changes (15 → 16+ fields)
- Backward compatibility needed
- P2P protocol compatibility

### 2. Base Fee Calculator
**New Component:** `BaseFeeCalculator`

**Algorithm:**
- Adjust base fee by up to 12.5% per block
- Based on parent block gas usage vs target
- Initial base fee: 1 Gwei (configurable)

### 3. Transaction Support
**Type 2 (EIP-1559) Transactions:**
```
Fields: chainId, nonce, maxPriorityFeePerGas, maxFeePerGas, 
        gasLimit, to, value, data, accessList, signature
```

**Gas Calculation:**
```
effectiveGasPrice = min(maxFeePerGas, baseFeePerGas + maxPriorityFeePerGas)
totalCost = gasUsed * effectiveGasPrice

ALL FEES → Miner:
  baseFeeAmount = gasUsed * baseFeePerGas
  priorityFeeAmount = gasUsed * (effectiveGasPrice - baseFeePerGas)
  Total Miner Revenue = baseFeeAmount + priorityFeeAmount
```

### 4. Block Validation
**New Consensus Rules:**
1. Base fee must match calculated value from parent block
2. All transactions must satisfy `maxFeePerGas >= baseFeePerGas`
3. Miner receives all transaction fees (standard validation)

**Simpler than treasury model:** No separate payout validation needed

### 5. Reward Distribution
**Current:** All fees to miner via `gasUsed * gasPrice`

**ECIP-1120:**
- 100% block reward → Miner
- 100% priority fees → Miner
- 100% base fees → Miner

**No treasury component**

### 6. Configuration
```hocon
ecip1120-block-number = "1000000000000000000"  # Activation

eip1559 {
  enabled = false
  activation-block-number = "1000000000000000000"
  initial-base-fee = "1000000000"  # 1 Gwei
  base-fee-max-change-denominator = 8
  elasticity-multiplier = 2
}
```

**Simpler Configuration:**
- No treasury address needed
- No governance configuration
- Standard miner reward distribution

## Implementation Phases

### Phase 1: Foundation (4-6 weeks)
- Block header extensions
- Base fee calculator
- Configuration schema

### Phase 2: Transactions (3-4 weeks)
- Type 2 transaction support
- Fee calculation logic
- Transaction validation

### Phase 3: Consensus (3-4 weeks)
- Block validation
- Miner payout (all fees)
- Mining integration

### Phase 4: Safety (2-3 weeks)
- Edge case handling
- Fork transition logic
- Error handling

### Phase 5: Testing (2-3 weeks)
- Unit tests
- Integration tests
- Cross-client compatibility

### Phase 6: Documentation (1-2 weeks)
- Operator guides
- API documentation
- Migration guides

**Total: 15-21 weeks (4-5 months)**

## Risk Summary

### Critical Risks
1. **Chain Split** - Base fee calculation mismatch between nodes
2. **RLP Incompatibility** - Block header changes break P2P

### High Risks
3. **State Corruption** - Miner payout errors corrupt world state

### Medium Risks
4. **Transaction Type Confusion** - Type 2 transaction failures
5. **Base Fee Manipulation** - Strategic block construction by miners

### Mitigations
- Comprehensive testing (unit, integration, property-based)
- Reference implementation alignment (EIP-1559 with miner destination)
- Testnet validation
- Cross-client coordination
- Phased rollout with monitoring

## Code Changes Summary

### Files to Modify
- `BlockHeader.scala` - Add base fee field
- `BlockPreparator.scala` - Update reward distribution (pay all fees to miner)
- `BlockValidator.scala` - Add base fee validation
- `BlockHeaderValidator.scala` - Validate base fee calculation
- `SignedTransactionValidator.scala` - Type 2 validation

### Files to Create
- `BaseFeeCalculator.scala` - Base fee algorithm
- `Type02Transaction.scala` - EIP-1559 transaction type
- `Eip1559Config.scala` - Configuration data structure

### Configuration Files
- `etc-chain.conf` - Add ECIP-1120 settings
- `mordor-chain.conf` - Testnet configuration
- `BlockchainConfig.scala` - Load EIP-1559 config

## Configuration Mode

### ECIP-1120 Mode
```hocon
ecip1120-block-number = "12345678"  # Activation block
eip1559.enabled = true
```
- All fees to miners (base fee + priority fee)
- Block rewards to miners
- Requires EIP-1559 enabled
- **No treasury component**

## Key Decision Points

### Q1: Base Fee at Fork Block
**Decision:** Use configurable `initial-base-fee`
- Default: 1 Gwei
- Network-specific tuning allowed
- Document rationale

### Q2: Ommer (Uncle) Handling
**Decision:** Ommers don't have base fees
- Ommers contain no transactions
- Only canonical block base fees apply
- Ommer rewards unchanged

### Q3: Transaction Pool Validation
**Decision:** Use parent base fee + 12.5% margin
- Configurable safety margin
- Prevents immediate rejection
- Document pool behavior

## Compatibility Considerations

### With Other Clients
- **Core-Geth**: Must coordinate activation
- **Besu**: Must coordinate activation
- **ETC Network**: Community consensus required

### With Existing Features
- **ECIP-1097/1098**: Mutually exclusive with ECIP-1120
- **Block Rewards**: Calculation unchanged, distribution changed
- **Mining**: Priorities transactions by effective priority fee

## Testing Strategy

### Unit Tests
### Unit Tests
- Base fee calculation edge cases
- Transaction Type 2 encoding/decoding
- Block header serialization
- Miner payout calculation

### Integration Tests
- Full block execution with ECIP-1120
- Fork transition scenarios
- Mixed transaction type blocks
- Miner balance verification

### Cross-Client Tests
- RLP encoding compatibility
- P2P message exchange
- Block propagation
- Consensus alignment

## Monitoring Metrics

**Prometheus Metrics to Add:**
- `fukuii_base_fee_per_gas` - Current base fee
- `fukuii_miner_fee_revenue` - Total miner fee revenue (basefee + priority)
- `fukuii_type2_transaction_count` - EIP-1559 transaction adoption
- `fukuii_base_fee_total` - Total base fees paid to miners
- `fukuii_priority_fee_total` - Total priority fees paid to miners

## Open Questions

1. **Community Consensus**: Is ETC community ready to adopt ECIP-1120?
2. **Client Coordination**: Are other clients implementing this?
3. **Activation Timeline**: When should mainnet activation occur?
4. **Parameter Tuning**: Are default EIP-1559 parameters appropriate for ETC?
5. **Miner Adoption**: Will miners support EIP-1559 style transactions?

## References

- **Full Analysis**: [ECIP-1120-IMPACT-ANALYSIS.md](ECIP-1120-IMPACT-ANALYSIS.md)
- **ECIP-1120 Spec**: https://ecip1120.dev/
- **EIP-1559 Spec**: https://eips.ethereum.org/EIPS/eip-1559

## Next Steps

1. **Review** this summary and full analysis
2. **Discuss** with ETC community and other client teams
3. **Create ADR** if decision to proceed
4. **Begin Phase 1** implementation (foundation)
5. **Establish testnet** for validation

---

**Status:** Research Complete - Revised per ECIP-1120 Specification  
**Implementation Status:** Not Started  
**Key Finding:** ECIP-1120 gives basefee to miners (not treasury)  
**Mainnet Activation:** Pending Community Consensus
