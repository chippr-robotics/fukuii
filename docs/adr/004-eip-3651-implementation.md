# ADR-004: EIP-3651 Implementation

## Status

Accepted

## Context

EIP-3651 (https://eips.ethereum.org/EIPS/eip-3651) is an Ethereum Improvement Proposal that was activated as part of the Shanghai hard fork on Ethereum mainnet. For Ethereum Classic, this proposal needs to be evaluated for inclusion in a future hard fork.

The proposal addresses gas cost optimization by marking the COINBASE address as warm at the start of transaction execution. Specifically:

- **Problem**: Before EIP-3651, the COINBASE address (accessed via the `COINBASE` opcode 0x41) was treated as a cold address at the start of transaction execution. This meant that the first access to the COINBASE address in a transaction would incur the cold address access cost (2600 gas) rather than the warm access cost (100 gas). However, the COINBASE address is always loaded at the start of transaction validation because it receives the block reward and transaction fees.

- **Solution**: Initialize the `accessed_addresses` set to include the address returned by the `COINBASE` opcode (the block's beneficiary address) at the start of transaction execution. This makes the first access to the COINBASE address in a transaction use the warm access cost instead of the cold access cost.

The change affects:
- Transaction initialization (adding COINBASE to warm addresses)
- Gas costs for opcodes that access the COINBASE address (BALANCE, EXTCODESIZE, EXTCODECOPY, EXTCODEHASH, CALL, CALLCODE, DELEGATECALL, STATICCALL)
- EIP-2929 access list behavior (COINBASE is treated as pre-warmed)

This is a gas cost optimization and does not affect:
- Transaction validity
- Transaction execution logic (beyond gas costs)
- Contract code or storage
- The behavior of the COINBASE opcode itself

## Decision

We implemented EIP-3651 in the Fukuii codebase with the following design decisions:

### 1. Configuration-Based Activation

The EIP-3651 validation is controlled by a boolean flag `eip3651Enabled` in the `EvmConfig` class:

```scala
case class EvmConfig(
    // ... other fields ...
    eip3651Enabled: Boolean = false
)
```

This flag will be set to `true` for the appropriate ETC fork (to be determined):

```scala
// Example - actual fork to be determined by ETC governance
val FutureEtcForkConfigBuilder: EvmConfigBuilder = config =>
  MystiqueConfigBuilder(config).copy(
    eip3651Enabled = true
  )
```

### 2. Fork-Based Activation

The activation can be tied to the Ethereum Classic fork schedule through the `BlockchainConfigForEvm` utility:

```scala
def isEip3651Enabled(etcFork: EtcFork): Boolean =
  etcFork >= EtcForks.FutureFork  // To be determined
```

This ensures that the optimization is only active for blocks at or after the specified fork block number.

### 3. ProgramState Initialization

The actual implementation is in the `ProgramState.apply` method, which initializes the `accessedAddresses` set at the start of transaction execution:

```scala
accessedAddresses = PrecompiledContracts.getContracts(context).keySet ++ Set(
  context.originAddr,
  context.recipientAddr.getOrElse(context.callerAddr)
) ++ context.warmAddresses ++ 
  (if (context.evmConfig.eip3651Enabled) Set(context.blockHeader.beneficiary) else Set.empty)
```

This adds the COINBASE address (block beneficiary) to the warm addresses if EIP-3651 is enabled.

## Consequences

### Positive

1. **Gas Cost Reduction**: Transactions that access the COINBASE address save 2500 gas on the first access (2600 - 100).

2. **Consistency**: The COINBASE address is logically already loaded at transaction start (to credit fees), so marking it warm aligns gas costs with actual system behavior.

3. **MEV Optimization**: Block builders and validators can more efficiently credit themselves fees and rewards in smart contracts.

4. **Simple Implementation**: The change is localized to transaction initialization and doesn't affect the EVM execution logic.

### Negative

1. **Gas Cost Change**: This is a consensus-critical change that affects gas costs. All nodes must activate it at the same block number to maintain consensus.

2. **Testing Requirement**: Requires comprehensive testing to ensure warm address behavior is correct for COINBASE.

3. **Fork Coordination**: Requires coordination with other ETC clients and the ETC community to determine the appropriate fork for activation.

### Neutral

1. **Limited Impact**: Only affects transactions that actually access the COINBASE address, which are relatively rare.

2. **Configuration Overhead**: Adds one more boolean flag to track in the fork configuration.

## Implementation Details

### Files Modified

1. **EvmConfig.scala**: Add `eip3651Enabled` boolean flag
2. **BlockchainConfigForEvm.scala**: Add `isEip3651Enabled` helper method
3. **ProgramState.scala**: Conditionally add COINBASE address to warm addresses
4. **Test files**: Comprehensive tests for gas cost changes

### Testing Strategy

1. **Unit tests**: Verify COINBASE address is warm when EIP-3651 is enabled
2. **Gas cost tests**: Verify correct gas costs for warm vs cold COINBASE access
3. **Integration tests**: Verify transaction execution with COINBASE access
4. **Fork transition tests**: Verify correct behavior before/after fork activation

## References

- [EIP-3651: Warm COINBASE](https://eips.ethereum.org/EIPS/eip-3651)
- [EIP-2929: Gas cost increases for state access opcodes](https://eips.ethereum.org/EIPS/eip-2929)
- [Ethereum Yellow Paper](https://ethereum.github.io/yellowpaper/paper.pdf)

## Notes

- This EIP was part of Ethereum's Shanghai hard fork (March 2023)
- For ETC, the activation fork should be determined by community governance
- The implementation is designed to be easily configurable for any future ETC fork
