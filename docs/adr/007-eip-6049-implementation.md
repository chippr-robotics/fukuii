# ADR-007: EIP-6049 Implementation (Deprecate SELFDESTRUCT)

## Status

Accepted

## Context

EIP-6049 (https://eips.ethereum.org/EIPS/eip-6049) is an informational Ethereum Improvement Proposal that was activated as part of the Shanghai hard fork on Ethereum mainnet. For Ethereum Classic, this proposal is included in the Spiral hard fork (ECIP-1109: https://ecips.ethereumclassic.org/ECIPs/ecip-1109) at block 19,250,000 on mainnet and block 9,957,000 on Mordor testnet.

The proposal officially deprecates the `SELFDESTRUCT` opcode. Specifically:

- **Problem**: The `SELFDESTRUCT` opcode (formerly known as `SUICIDE`) has several problematic characteristics:
  - It can be used to delete contract code and state
  - It transfers all remaining Ether to a beneficiary address
  - It complicates state management and consensus rules
  - It has been used in security exploits
  - It interacts poorly with various EIPs and future protocol changes
  - It creates unpredictable gas costs due to refund mechanisms

- **Solution**: EIP-6049 officially deprecates `SELFDESTRUCT` and warns developers not to use it. However, **the behavior remains unchanged** in this EIP. This is a documentation-only change that:
  - Signals to developers that `SELFDESTRUCT` is deprecated
  - Warns that future EIPs may change or remove `SELFDESTRUCT` functionality
  - Encourages developers to design contracts without relying on `SELFDESTRUCT`

**Important:** EIP-6049 does NOT change the behavior of `SELFDESTRUCT`. The opcode continues to work exactly as before. Future EIPs (such as EIP-6780 in Ethereum's Cancun hard fork) will modify the behavior, but EIP-6049 itself is purely informational.

The deprecation affects:
- Developer guidance and best practices
- Code documentation and comments
- Future protocol planning

This change does NOT affect:
- Smart contract execution behavior
- Gas costs
- Transaction validity
- Existing contract functionality
- EVM bytecode interpretation

## Decision

We implemented EIP-6049 in the Fukuii codebase with the following design decisions:

### 1. Documentation and Annotation

The `SELFDESTRUCT` opcode implementation in `OpCode.scala` is annotated with deprecation warnings:

```scala
/** SELFDESTRUCT opcode (0xff)
  * 
  * @deprecated As of EIP-6049 (Spiral fork), SELFDESTRUCT is officially deprecated.
  *             The behavior remains unchanged for now, but developers should avoid using
  *             this opcode in new contracts as future EIPs may change or remove its functionality.
  *             
  *             See: https://eips.ethereum.org/EIPS/eip-6049
  *             Activated with Spiral fork (ECIP-1109):
  *             - Block 19,250,000 on Ethereum Classic mainnet
  *             - Block 9,957,000 on Mordor testnet
  */
case object SELFDESTRUCT extends OpCode(0xff, 1, 0, _.G_selfdestruct) {
  // Implementation remains unchanged
}
```

### 2. Configuration Files

The Spiral fork configuration files already document the fork activation, but we add explicit mention of EIP-6049:

**ETC Mainnet** (`etc-chain.conf`):
```
# Spiral EVM and Protocol Upgrades (ECIP-1109)
# Implements EIP-3855: PUSH0 instruction
# Implements EIP-3651: Warm COINBASE
# Implements EIP-3860: Limit and meter initcode
# Implements EIP-6049: Deprecate SELFDESTRUCT (informational - behavior unchanged)
# https://ecips.ethereumclassic.org/ECIPs/ecip-1109
spiral-block-number = "19250000"
```

**Mordor Testnet** (`mordor-chain.conf`):
```
# Spiral EVM and Protocol Upgrades (ECIP-1109)
# Implements EIP-3855: PUSH0 instruction
# Implements EIP-3651: Warm COINBASE
# Implements EIP-3860: Limit and meter initcode
# Implements EIP-6049: Deprecate SELFDESTRUCT (informational - behavior unchanged)
# https://ecips.ethereumclassic.org/ECIPs/ecip-1109
spiral-block-number = "9957000"
```

### 3. Configuration Flag

While EIP-6049 does not change behavior, we add a configuration flag for tracking and documentation purposes:

```scala
case class EvmConfig(
    // ... other fields ...
    eip6049DeprecationEnabled: Boolean = false
)
```

This flag is set to `true` for the Spiral fork and later:

```scala
val SpiralConfigBuilder: EvmConfigBuilder = config =>
  MystiqueConfigBuilder(config).copy(
    opCodeList = SpiralOpCodes,
    eip3651Enabled = true,
    eip3860Enabled = true,
    eip6049DeprecationEnabled = true
  )
```

### 4. Fork Detection

A helper method is provided in `BlockchainConfigForEvm` to check if EIP-6049 deprecation is active:

```scala
def isEip6049DeprecationEnabled(etcFork: EtcFork): Boolean =
  etcFork >= EtcForks.Spiral
```

### 5. Implementation Rationale

#### No Behavior Changes
EIP-6049 is purely informational. The `SELFDESTRUCT` opcode implementation remains exactly as it was before. This means:
- No changes to gas costs
- No changes to state transitions
- No changes to refund mechanisms
- No changes to execution semantics

#### Documentation-Only Change
The primary purpose of EIP-6049 is to:
1. Signal to developers that `SELFDESTRUCT` is deprecated
2. Warn that future changes may modify or remove the opcode
3. Update documentation to reflect the deprecation status

#### Future-Proofing
By marking `SELFDESTRUCT` as deprecated now, we:
- Prepare the ecosystem for future changes (like EIP-6780)
- Give developers time to design contracts without `SELFDESTRUCT`
- Maintain clear documentation of protocol evolution

## Consequences

### Positive

1. **Clear Developer Guidance**: Developers are explicitly warned that `SELFDESTRUCT` is deprecated and should be avoided in new contracts.

2. **No Breaking Changes**: Since behavior is unchanged, existing contracts continue to work exactly as before.

3. **Future Compatibility**: Marking the opcode as deprecated prepares the ecosystem for future EIPs that may change `SELFDESTRUCT` behavior.

4. **Documentation Alignment**: Keeps Ethereum Classic documentation aligned with Ethereum mainnet's Shanghai fork.

5. **Low Risk**: This is a documentation-only change with no consensus impact or risk of chain splits.

### Negative

1. **Limited Immediate Impact**: Since behavior is unchanged, contracts can still use `SELFDESTRUCT` without technical consequences.

2. **Developer Confusion**: Some developers may be confused about whether they can still use `SELFDESTRUCT` (answer: yes, but it's not recommended).

### Neutral

1. **Ecosystem Awareness**: The deprecation primarily serves to raise awareness in the developer community rather than enforce technical restrictions.

2. **Compiler Independence**: Solidity and other compilers may add their own warnings about `SELFDESTRUCT`, independent of this EIP.

## Implementation Details

### Files Modified

1. **OpCode.scala**: 
   - Added deprecation annotation to `SELFDESTRUCT` case object
   - No behavior changes

2. **EvmConfig.scala**:
   - Added `eip6049DeprecationEnabled` flag to `EvmConfig`
   - Updated `SpiralConfigBuilder` to set flag to `true`

3. **BlockchainConfigForEvm.scala**:
   - Added `isEip6049DeprecationEnabled` helper method

4. **Configuration files**:
   - Updated `etc-chain.conf` to document EIP-6049
   - Updated `mordor-chain.conf` to document EIP-6049
   - Updated other chain configs with comments

### Testing Strategy

Since EIP-6049 does not change behavior, testing focuses on:

1. **Behavior Verification**: Ensure `SELFDESTRUCT` continues to work exactly as before
   - Verify gas costs remain unchanged
   - Verify state transitions remain unchanged
   - Verify refund mechanisms remain unchanged

2. **Fork Detection**: Verify the `eip6049DeprecationEnabled` flag is set correctly
   - `true` for Spiral fork and later
   - `false` for pre-Spiral forks

3. **Existing Tests**: Run existing `SELFDESTRUCT` test suite to ensure no regressions
   - `OpCodeFunSpec` tests for `SELFDESTRUCT`
   - `CreateOpcodeSpec` tests involving `SELFDESTRUCT`
   - `CallOpcodesSpec` tests with `SELFDESTRUCT`
   - Gas cost tests in `OpCodeGasSpec`

### Test Coverage

The following existing test files cover `SELFDESTRUCT` behavior:
- `src/test/scala/com/chipprbots/ethereum/vm/OpCodeFunSpec.scala`
- `src/test/scala/com/chipprbots/ethereum/vm/OpCodeGasSpec.scala`
- `src/test/scala/com/chipprbots/ethereum/vm/OpCodeGasSpecPostEip161.scala`
- `src/test/scala/com/chipprbots/ethereum/vm/OpCodeGasSpecPostEip2929.scala`
- `src/test/scala/com/chipprbots/ethereum/vm/CallOpcodesSpec.scala`
- `src/test/scala/com/chipprbots/ethereum/vm/CallOpcodesPostEip2929Spec.scala`
- `src/test/scala/com/chipprbots/ethereum/vm/CreateOpcodeSpec.scala`
- `src/test/scala/com/chipprbots/ethereum/vm/StaticCallOpcodeSpec.scala`

All existing tests must continue to pass without modification.

## Security Considerations

The EIP-6049 specification states:
> Deprecating SELFDESTRUCT does not immediately change any security properties. However, it signals that developers should avoid relying on SELFDESTRUCT in new contracts.

Our implementation maintains security by:

1. **No Behavior Changes**: Since behavior is unchanged, there are no new security implications from this EIP.

2. **Documentation**: Clear documentation warns developers about the deprecation and encourages secure contract design without `SELFDESTRUCT`.

3. **Future Planning**: The deprecation prepares for future EIPs that may improve security by modifying or removing `SELFDESTRUCT`.

4. **Existing Security Properties**: All existing security properties of `SELFDESTRUCT` remain:
   - Gas refunds are still calculated (though EIP-3529 reduced the refund amount)
   - State is still cleared
   - Ether is still transferred
   - Access control still applies (cannot be called from static context)

## References

- [EIP-6049: Deprecate SELFDESTRUCT](https://eips.ethereum.org/EIPS/eip-6049)
- [ECIP-1109: Spiral Hard Fork](https://ecips.ethereumclassic.org/ECIPs/ecip-1109)
- [EIP-6780: SELFDESTRUCT only in same transaction](https://eips.ethereum.org/EIPS/eip-6780) (future change to SELFDESTRUCT)
- [EIP-3529: Reduction in refunds](https://eips.ethereum.org/EIPS/eip-3529) (removed SELFDESTRUCT refund)
- [Ethereum Yellow Paper](https://ethereum.github.io/yellowpaper/paper.pdf)

## Notes

- This EIP was part of Ethereum's Shanghai hard fork (April 2023)
- For ETC, this is part of the Spiral hard fork (ECIP-1109):
  - **Mainnet activation**: Block 19,250,000
  - **Mordor testnet activation**: Block 9,957,000
- EIP-6049 is informational only - it does NOT change `SELFDESTRUCT` behavior
- Future EIPs (like EIP-6780 in Ethereum's Cancun fork) will modify `SELFDESTRUCT` behavior
- ETC may or may not adopt future changes to `SELFDESTRUCT` depending on community consensus
- The deprecation warning serves primarily as developer guidance
- Compilers like Solidity 0.8.18+ emit warnings when `selfdestruct` is used

## Related EIPs and Historical Context

### EIP-3529: Reduction in Refunds
In the Mystique fork (before Spiral), EIP-3529 removed the gas refund for `SELFDESTRUCT`:
- **Previous**: 24,000 gas refund
- **After EIP-3529**: 0 gas refund

This made `SELFDESTRUCT` less economically attractive but didn't deprecate it.

### EIP-6780: SELFDESTRUCT Only in Same Transaction (Future)
Ethereum's Cancun hard fork includes EIP-6780, which changes `SELFDESTRUCT` behavior:
- `SELFDESTRUCT` only deletes code if called in the same transaction as contract creation
- Otherwise, it only transfers Ether without deleting code
- ETC has not yet decided whether to adopt EIP-6780

### Why Deprecate SELFDESTRUCT?
1. **State Bloat**: Allows contracts to be deleted, complicating state management
2. **Reentrancy**: Can be used in complex reentrancy attacks
3. **Unpredictable Gas**: Refunds make gas costs unpredictable
4. **Protocol Complexity**: Interacts poorly with other EIPs (storage proofs, state expiry)
5. **Limited Use Cases**: Most legitimate use cases can be achieved without `SELFDESTRUCT`

### Migration Guidance for Developers
Developers should replace `SELFDESTRUCT` patterns with:
1. **Transfer Ether**: Use `transfer()` or `call{value: amount}("")` to send Ether
2. **Disable Contract**: Use a boolean flag to mark contract as disabled
3. **Access Control**: Use role-based access control instead of self-destruction
4. **Upgradeability**: Use proxy patterns instead of self-destruct and redeploy

Example:
```solidity
// Old pattern (deprecated)
function destroy() public onlyOwner {
    selfdestruct(payable(owner));
}

// New pattern (recommended)
bool public disabled;
function disable() public onlyOwner {
    disabled = true;
    payable(owner).transfer(address(this).balance);
}
```

## Performance Implications

Since EIP-6049 does not change behavior, there are no performance implications:
- Gas costs remain the same
- Execution speed remains the same
- State transitions remain the same

## Conclusion

EIP-6049 is a documentation-only change that deprecates `SELFDESTRUCT` without modifying its behavior. The implementation in Fukuii adds clear deprecation warnings in code comments and configuration files, prepares for future protocol changes, and maintains full compatibility with existing contracts. This aligns Ethereum Classic with Ethereum mainnet's Shanghai hard fork while allowing the ETC community to independently decide on future changes to `SELFDESTRUCT` behavior.
