# ADR-006: Implementation of EIP-3529 (Reduction in Refunds)

**Date:** 2024-10-25  
**Status:** Accepted  
**Related Fork:** Mystique (Ethereum Classic)  
**EIP Reference:** [EIP-3529: Reduction in refunds](https://eips.ethereum.org/EIPS/eip-3529)

## Context

EIP-3529 was introduced as part of the Berlin/London hard fork series in Ethereum to address several issues with the gas refund mechanism:

1. **Storage refunds were too high**: The previous `R_sclear` refund of 15,000 gas incentivized "gas tokens" which stored data just to get refunds later, bloating the state.
2. **SELFDESTRUCT refunds enabled gaming**: The 24,000 gas refund for `SELFDESTRUCT` could be exploited and didn't align with the actual cost of state cleanup.
3. **Maximum refund cap needed adjustment**: The maximum refund was capped at `gasUsed / 2`, which was too generous.

For Ethereum Classic, EIP-3529 was adopted as part of the **Mystique hard fork**, aligning with Ethereum's Berlin/London changes while maintaining ETC's independent consensus rules.

## Decision

Implement EIP-3529 in the Fukuii codebase with the following changes:

### 1. Reduce SSTORE Clear Refund (`R_sclear`)

**Previous Value:** 15,000 gas  
**New Value:** 4,800 gas

The new value is calculated as:
```
R_sclear = SSTORE_RESET_GAS + ACCESS_LIST_STORAGE_KEY_COST
         = 2,900 + 1,900
         = 4,800 gas
```

This makes the refund proportional to the actual cost of accessing and modifying storage in the post-EIP-2929 gas model.

### 2. Remove SELFDESTRUCT Refund (`R_selfdestruct`)

**Previous Value:** 24,000 gas  
**New Value:** 0 gas

The `SELFDESTRUCT` opcode no longer provides any gas refund. This removes the incentive to create contracts solely for the purpose of self-destructing them to claim refunds.

### 3. Reduce Maximum Refund Quotient

**Previous Value:** `gasUsed / 2` (maximum 50% refund)  
**New Value:** `gasUsed / 5` (maximum 20% refund)

This change limits the total amount of gas that can be refunded in a single transaction, preventing excessive refund gaming.

## Implementation Details

### Code Locations

The EIP-3529 implementation spans three main files:

#### 1. Fee Schedule Configuration (`EvmConfig.scala`)

**Location:** `src/main/scala/com/chipprbots/ethereum/vm/EvmConfig.scala`

The `MystiqueFeeSchedule` class implements the new gas values:

```scala
class MystiqueFeeSchedule extends MagnetoFeeSchedule {
  // EIP-3529: Reduce refunds for SSTORE
  // R_sclear = SSTORE_RESET_GAS + ACCESS_LIST_STORAGE_KEY_COST = 2900 + 1900 = 4800
  override val R_sclear: BigInt = 4800
  
  // EIP-3529: Remove SELFDESTRUCT refund
  override val R_selfdestruct: BigInt = 0
}
```

The `MystiqueConfigBuilder` creates an EVM configuration with:
- The new `MystiqueFeeSchedule` with updated refund values
- EIP-3541 enabled (separate from EIP-3529 but part of same fork)

#### 2. Fork Detection (`BlockchainConfigForEvm.scala`)

**Location:** `src/main/scala/com/chipprbots/ethereum/vm/BlockchainConfigForEvm.scala`

The `isEip3529Enabled` helper function determines if EIP-3529 rules apply:

```scala
def isEip3529Enabled(etcFork: EtcFork): Boolean =
  etcFork >= EtcForks.Mystique
```

This function returns `true` for the Mystique fork and all subsequent forks, ensuring the new refund rules are applied at the correct block height.

#### 3. Refund Calculation (`BlockPreparator.scala`)

**Location:** `src/main/scala/com/chipprbots/ethereum/ledger/BlockPreparator.scala`

The `calcTotalGasToRefund` method implements the maximum refund quotient logic:

```scala
private[ledger] def calcTotalGasToRefund(
    stx: SignedTransaction,
    result: PR,
    blockNumber: BigInt
)(implicit blockchainConfig: BlockchainConfig): BigInt =
  result.error.map(_.useWholeGas) match {
    case Some(true)  => 0
    case Some(false) => result.gasRemaining
    case None =>
      val gasUsed = stx.tx.gasLimit - result.gasRemaining
      val blockchainConfigForEvm = BlockchainConfigForEvm(blockchainConfig)
      val etcFork = blockchainConfigForEvm.etcForkForBlockNumber(blockNumber)
      // EIP-3529: Changes max refund from gasUsed / 2 to gasUsed / 5
      val maxRefundQuotient = if (BlockchainConfigForEvm.isEip3529Enabled(etcFork)) 5 else 2
      result.gasRemaining + (gasUsed / maxRefundQuotient).min(result.gasRefund)
  }
```

**Key Logic:**
- If transaction has an error that uses all gas: no refund
- If transaction has an error that doesn't use all gas: return remaining gas only
- For successful transactions:
  - Calculate gas used: `gasUsed = gasLimit - gasRemaining`
  - Determine fork-appropriate quotient: 5 for Mystique+, 2 for pre-Mystique
  - Calculate capped refund: `min(gasUsed / quotient, actualRefund)`
  - Return: `gasRemaining + cappedRefund`

### Configuration Integration

The Mystique fork block number is configured in the blockchain configuration files (`src/universal/conf/`). When a block number equals or exceeds the `mystiqueBlockNumber`, the EVM uses `MystiqueConfigBuilder` which applies the new fee schedule.

## Unit Tests

Comprehensive unit tests verify the EIP-3529 implementation:

**Test File:** `src/test/scala/com/chipprbots/ethereum/vm/Eip3529Spec.scala`

### Test Suite: `Eip3529SpecPostMystique`

This test suite validates that EIP-3529 rules are correctly applied for the Mystique fork:

```scala
class Eip3529SpecPostMystique extends Eip3529Spec {
  override val config: EvmConfig = EvmConfig.MystiqueConfigBuilder(blockchainConfig)
  override val forkBlockHeight = Fixtures.MystiqueBlockNumber
}
```

### Test Cases

#### 1. **Test: R_sclear Value**
```scala
test("EIP-3529: R_sclear should be 4800") {
  config.feeSchedule.R_sclear shouldBe 4800
}
```

**Validates:** The SSTORE clear refund is set to 4,800 gas (down from 15,000).

#### 2. **Test: R_selfdestruct Value**
```scala
test("EIP-3529: R_selfdestruct should be 0") {
  config.feeSchedule.R_selfdestruct shouldBe 0
}
```

**Validates:** The SELFDESTRUCT refund is set to 0 gas (down from 24,000).

#### 3. **Test: Fork Detection for Mystique**
```scala
test("EIP-3529: isEip3529Enabled should return true for Mystique fork") {
  val etcFork = blockchainConfig.etcForkForBlockNumber(forkBlockHeight)
  BlockchainConfigForEvm.isEip3529Enabled(etcFork) shouldBe true
}
```

**Validates:** EIP-3529 is correctly enabled for blocks at or after the Mystique fork height.

#### 4. **Test: Fork Detection for Pre-Mystique Forks**
```scala
test("EIP-3529: isEip3529Enabled should return false for pre-Mystique forks") {
  val magnetoFork = blockchainConfig.etcForkForBlockNumber(Fixtures.MagnetoBlockNumber)
  BlockchainConfigForEvm.isEip3529Enabled(magnetoFork) shouldBe false

  val phoenixFork = blockchainConfig.etcForkForBlockNumber(Fixtures.PhoenixBlockNumber)
  BlockchainConfigForEvm.isEip3529Enabled(phoenixFork) shouldBe false
}
```

**Validates:** EIP-3529 is correctly disabled for blocks before the Mystique fork (Magneto and Phoenix forks).

### Test Coverage

The test suite provides coverage for:
- ✅ Fee schedule constant values (`R_sclear`, `R_selfdestruct`)
- ✅ Fork detection logic (`isEip3529Enabled`)
- ✅ Correct behavior across fork boundaries
- ✅ Backward compatibility with pre-Mystique forks

**Note:** The maximum refund quotient logic in `BlockPreparator.scala` is tested indirectly through integration tests that execute transactions and verify gas refunds. Additional unit tests for `calcTotalGasToRefund` may be found in `BlockPreparatorSpec.scala` or similar test files.

## Consequences

### Positive

1. **Reduced State Bloat**: The lower `R_sclear` refund discourages "gas token" patterns that were bloating the state.
2. **More Accurate Gas Economics**: Refunds now better reflect actual computational and storage costs.
3. **Simplified Gas Model**: Removing the `SELFDESTRUCT` refund eliminates a special case in gas calculation.
4. **Network Alignment**: Keeping Ethereum Classic aligned with Ethereum's gas economics reduces confusion and improves tooling compatibility.
5. **Security Improvement**: Reduces attack surface by limiting gas refund gaming strategies.

### Negative

1. **Breaking Change for Contracts**: Smart contracts that relied on high refunds or `SELFDESTRUCT` economics may behave differently.
2. **Gas Token Obsolescence**: Existing gas token contracts lose their primary value proposition.
3. **Higher Transaction Costs**: Some transaction patterns that benefited from refunds will now cost more gas.

### Mitigation

- The changes are fork-gated, so old behavior is preserved for historical blocks.
- The Ethereum Classic community was notified of the changes before the Mystique fork activation.
- Developers were encouraged to audit and update contracts that depended on refund mechanics.

## Alternatives Considered

### 1. Keep Full Refunds
**Rejected:** Maintaining the old refund values would perpetuate state bloat and gas gaming issues.

### 2. Gradual Refund Reduction
**Rejected:** A gradual approach would complicate the implementation and delay the benefits. The single-step change aligns with Ethereum's approach.

### 3. Complete Removal of Refunds
**Rejected:** While this would be simpler, some refunds (like clearing storage) provide legitimate gas savings and incentivize good state hygiene.

## References

- [EIP-3529: Reduction in refunds](https://eips.ethereum.org/EIPS/eip-3529)
- [EIP-2929: Gas cost increases for state access opcodes](https://eips.ethereum.org/EIPS/eip-2929)
- Ethereum Classic Mystique Hard Fork Specification
- Fukuii Source Code:
  - `src/main/scala/com/chipprbots/ethereum/vm/EvmConfig.scala`
  - `src/main/scala/com/chipprbots/ethereum/vm/BlockchainConfigForEvm.scala`
  - `src/main/scala/com/chipprbots/ethereum/ledger/BlockPreparator.scala`
  - `src/test/scala/com/chipprbots/ethereum/vm/Eip3529Spec.scala`

## Related ADRs

- ADR-005: Modular Package Structure (inherited architectural decision)
- Future ADR: EIP-3541 (Code validation) - implemented alongside EIP-3529 in Mystique fork

---

**Changelog:**
- **2024-10-25**: Initial ADR created documenting EIP-3529 implementation
