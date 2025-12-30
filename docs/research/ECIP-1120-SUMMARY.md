# ECIP-1120 Implementation Summary

**Quick Reference Guide**

## Overview

ECIP-1120 adapts EIP-1559 for Ethereum Classic by redirecting base fees to a treasury instead of burning them.

## Key Differences: Ethereum vs. ETC

| Aspect | Ethereum (EIP-1559) | ETC (ECIP-1120) |
|--------|-------------------|----------------|
| Base Fee Destination | Burned (deflationary) | Treasury (non-deflationary) |
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
baseFeeAmount = gasUsed * baseFeePerGas → Treasury
priorityFeeAmount = gasUsed * (effectiveGasPrice - baseFeePerGas) → Miner
```

### 4. Block Validation
**New Consensus Rules:**
1. Base fee must match calculated value from parent block
2. All transactions must satisfy `maxFeePerGas >= baseFeePerGas`
3. Treasury balance must increase by exact total base fee amount

**Critical:** Block invalid if treasury payout incorrect

### 5. Reward Distribution
**Current (ECIP-1098):** 80% miner, 20% treasury from block reward

**ECIP-1120:**
- 100% block reward → Miner
- 100% priority fees → Miner
- 100% base fees → Treasury

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

treasury {
  ecip1120-address = "0x..."
  require-contract-exists = true
  mode = "ecip1120"  # "disabled" | "ecip1098" | "ecip1120"
}
```

## Implementation Phases

### Phase 1: Foundation (4-6 weeks)
- Block header extensions
- Base fee calculator
- Configuration schema

### Phase 2: Transactions (3-4 weeks)
- Type 2 transaction support
- Fee calculation logic
- Transaction validation

### Phase 3: Consensus (4-6 weeks)
- Block validation
- Treasury payout enforcement
- Mining integration

### Phase 4: Safety (3-4 weeks)
- Edge case handling
- Fork transition logic
- Error handling

### Phase 5: Testing (3-4 weeks)
- Unit tests
- Integration tests
- Cross-client compatibility

### Phase 6: Documentation (2 weeks)
- Operator guides
- API documentation
- Migration guides

**Total: 18-24 weeks (4-6 months)**

## Risk Summary

### Critical Risks
1. **Chain Split** - Base fee calculation mismatch between nodes
2. **Treasury Config Error** - Incorrect address sends funds to wrong recipient
3. **RLP Incompatibility** - Block header changes break P2P

### High Risks
4. **Treasury Validation Failure** - Blocks rejected incorrectly
5. **State Corruption** - Treasury payout errors corrupt world state

### Medium Risks
6. **Transaction Type Confusion** - Type 2 transaction failures
7. **Base Fee Manipulation** - Strategic block construction by miners

### Mitigations
- Comprehensive testing (unit, integration, property-based)
- Reference implementation alignment
- Testnet validation
- Cross-client coordination
- Phased rollout with monitoring

## Code Changes Summary

### Files to Modify
- `BlockHeader.scala` - Add base fee field
- `BlockPreparator.scala` - Update reward distribution
- `BlockValidator.scala` - Add base fee/treasury validation
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

## Configuration Modes

### Mode 1: Disabled (Default)
```hocon
treasury.mode = "disabled"
ecip1120-block-number = "1000000000000000000"  # Never activates
```
- No treasury
- Traditional reward distribution
- All rewards to miners

### Mode 2: ECIP-1098 (Inflation-based)
```hocon
treasury.mode = "ecip1098"
ecip1098-block-number = "12345678"
```
- 80/20 split of block rewards
- Treasury from newly minted coins
- No base fee mechanism

### Mode 3: ECIP-1120 (Fee-based)
```hocon
treasury.mode = "ecip1120"
ecip1120-block-number = "12345678"
eip1559.enabled = true
```
- Base fees to treasury
- Block rewards + tips to miner
- Requires EIP-1559 enabled

**Note:** Modes are mutually exclusive

## Key Decision Points

### Q1: Treasury Address Type
**Decision:** Allow both EOA and contract addresses
- Configuration validates address format
- Log warnings if address doesn't exist
- Document best practices

### Q2: Base Fee at Fork Block
**Decision:** Use configurable `initial-base-fee`
- Default: 1 Gwei
- Network-specific tuning allowed
- Document rationale

### Q3: Ommer (Uncle) Handling
**Decision:** Ommers don't have base fees
- Ommers contain no transactions
- Only canonical block base fees redirected
- Ommer rewards unchanged

### Q4: Transaction Pool Validation
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
- Base fee calculation edge cases
- Transaction Type 2 encoding/decoding
- Block header serialization
- Treasury payout calculation

### Integration Tests
- Full block execution with ECIP-1120
- Fork transition scenarios
- Mixed transaction type blocks
- Treasury balance verification

### Cross-Client Tests
- RLP encoding compatibility
- P2P message exchange
- Block propagation
- Consensus alignment

## Monitoring Metrics

**Prometheus Metrics to Add:**
- `fukuii_base_fee_per_gas` - Current base fee
- `fukuii_treasury_balance` - Treasury account balance
- `fukuii_type2_transaction_count` - EIP-1559 transaction adoption
- `fukuii_base_fee_total` - Total base fees collected
- `fukuii_priority_fee_total` - Total priority fees paid to miners

## Open Questions

1. **Community Consensus**: Is ETC community ready to adopt ECIP-1120?
2. **Client Coordination**: Are other clients implementing this?
3. **Activation Timeline**: When should mainnet activation occur?
4. **Treasury Governance**: Who controls treasury spending (ECIP-1113/1114)?
5. **Parameter Tuning**: Are default EIP-1559 parameters appropriate for ETC?

## References

- **Full Analysis**: [ECIP-1120-IMPACT-ANALYSIS.md](ECIP-1120-IMPACT-ANALYSIS.md)
- **ECIP-1120 Spec**: https://ecip1120.dev/
- **EIP-1559 Spec**: https://eips.ethereum.org/EIPS/eip-1559
- **Olympia Upgrade**: https://olympiadao.org/

## Next Steps

1. **Review** this summary and full analysis
2. **Discuss** with ETC community and other client teams
3. **Create ADR** if decision to proceed
4. **Begin Phase 1** implementation (foundation)
5. **Establish testnet** for validation

---

**Status:** Research Complete  
**Implementation Status:** Not Started  
**Mainnet Activation:** Pending Community Consensus
