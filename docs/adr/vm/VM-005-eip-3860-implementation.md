# ADR-006: EIP-3860 Implementation (Limit and Meter Initcode)

## Status

Accepted

## Context

EIP-3860 (https://eips.ethereum.org/EIPS/eip-3860) is an Ethereum Improvement Proposal that was activated as part of the Shanghai hard fork on Ethereum mainnet. For Ethereum Classic, this proposal is included in the Spiral hard fork (ECIP-1109: https://ecips.ethereumclassic.org/ECIPs/ecip-1109) at block 19,250,000 on mainnet and block 9,957,000 on Mordor testnet.

The proposal introduces initcode size limits and gas metering for contract creation. Specifically:

- **Problem**: Prior to EIP-3860, there was no limit on initcode size (the bytecode that runs during contract creation), and no gas charged proportional to initcode size beyond the per-byte transaction data cost. This created performance issues because:
  - Jump destination analysis (JUMPDEST) on large initcode was expensive
  - Large initcode could cause DOS attacks through expensive EVM operations
  - No upper bound made worst-case performance analysis difficult

- **Solution**: Introduce two changes:
  1. **Size limit**: Limit maximum initcode size to `MAX_INITCODE_SIZE = 49152` bytes (2 × 24576, where 24576 is `MAX_CODE_SIZE` from EIP-170)
  2. **Gas metering**: Charge `INITCODE_WORD_COST = 2` gas per 32-byte word of initcode, calculated as: `initcode_cost(initcode) = INITCODE_WORD_COST × ceil(len(initcode) / 32)`

The changes affect:
- Contract creation transactions (transactions with empty `to` field)
- CREATE opcode (0xf0)
- CREATE2 opcode (0xf5)
- Transaction intrinsic gas calculation
- Opcode gas costs

This is a consensus-critical change. It affects:
- Transaction validation (transactions can become invalid)
- EVM execution (CREATE/CREATE2 can fail with exceptional abort)
- Gas costs for contract creation

## Decision

We implemented EIP-3860 in the Fukuii codebase with the following design decisions:

### 1. Constants Definition

Constants are added to the `FeeSchedule` trait and implementations:

```scala
trait FeeSchedule {
  // ... existing fields ...
  val G_initcode_word: BigInt  // INITCODE_WORD_COST (2 gas per word)
}

class MystiqueFeeSchedule extends MagnetoFeeSchedule {
  // ... existing fields ...
  override val G_initcode_word: BigInt = 2
}
```

The MAX_INITCODE_SIZE constant (49152 = 2 × 24576) is derived from the existing `maxCodeSize` configuration value:

```scala
def maxInitCodeSize: Option[BigInt] =
  maxCodeSize.map(_ * 2)
```

### 2. Initcode Cost Calculation

A new function is added to `EvmConfig` to calculate initcode gas cost:

```scala
def calcInitCodeCost(initCode: ByteString): BigInt = {
  if (eip3860Enabled) {
    val words = wordsForBytes(initCode.size)
    feeSchedule.G_initcode_word * words
  } else {
    0
  }
}
```

This function uses the existing `wordsForBytes` utility which correctly implements `ceil(len(initcode) / 32)`.

### 3. Transaction Intrinsic Gas Update

The `calcTransactionIntrinsicGas` function in `EvmConfig` is updated to include initcode cost for contract creation transactions:

```scala
def calcTransactionIntrinsicGas(
    txData: ByteString,
    isContractCreation: Boolean,
    accessList: Seq[AccessListItem]
): BigInt = {
  val txDataZero = txData.count(_ == 0)
  val txDataNonZero = txData.length - txDataZero

  val accessListPrice =
    accessList.size * G_access_list_address +
      accessList.map(_.storageKeys.size).sum * G_access_list_storage

  val initCodeCost = if (isContractCreation) calcInitCodeCost(txData) else 0

  txDataZero * G_txdatazero +
    txDataNonZero * G_txdatanonzero + accessListPrice +
    (if (isContractCreation) G_txcreate else 0) +
    G_transaction +
    initCodeCost
}
```

### 4. Transaction Validation Update

Transaction validation in `StdSignedTransactionValidator` checks initcode size for contract creation transactions:

```scala
private def validateInitCodeSize(
    stx: SignedTransaction,
    blockHeaderNumber: BigInt
)(implicit blockchainConfig: BlockchainConfig): Either[SignedTransactionError, SignedTransactionValid] = {
  import stx.tx
  if (tx.isContractInit) {
    val config = EvmConfig.forBlock(blockHeaderNumber, blockchainConfig)
    config.maxInitCodeSize match {
      case Some(maxSize) if config.eip3860Enabled && tx.payload.size > maxSize =>
        Left(TransactionInitCodeSizeError(tx.payload.size, maxSize))
      case _ =>
        Right(SignedTransactionValid)
    }
  } else {
    Right(SignedTransactionValid)
  }
}
```

A new error type is added:

```scala
case class TransactionInitCodeSizeError(actualSize: BigInt, maxSize: BigInt) extends SignedTransactionError {
  override def toString: String =
    s"Transaction initcode size ($actualSize) exceeds maximum ($maxSize)"
}
```

### 5. CREATE/CREATE2 Opcode Updates

The `CreateOp` abstract class is updated to:
1. Check initcode size before execution
2. Charge initcode gas cost

```scala
abstract class CreateOp(code: Int, delta: Int) extends OpCode(code, delta, 1, _.G_create) {
  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    val (Seq(endowment, inOffset, inSize), stack1) = state.stack.pop(3)

    // Check initcode size limit (EIP-3860)
    val maxInitCodeSize = state.config.maxInitCodeSize
    if (state.config.eip3860Enabled && maxInitCodeSize.exists(max => inSize > max)) {
      // Exceptional abort: initcode too large
      return state.withStack(stack1.push(UInt256.Zero)).withError(InitCodeSizeLimit).step()
    }

    // Calculate gas including initcode cost (EIP-3860)
    val initCodeGasCost = if (state.config.eip3860Enabled) {
      val words = wordsForBytes(inSize)
      state.config.feeSchedule.G_initcode_word * words
    } else {
      0
    }

    val baseGas = baseGasFn(state.config.feeSchedule) + varGas(state) + initCodeGasCost
    val availableGas = state.gas - baseGas
    val startGas = state.config.gasCap(availableGas)
    
    // ... rest of CREATE logic ...
  }
}
```

A new program error is added for initcode size violations:

```scala
case object InitCodeSizeLimit extends ProgramError {
  override def description: String = "Initcode size exceeds maximum limit (EIP-3860)"
}
```

### 6. Fork Configuration

The `eip3860Enabled` flag is added to `EvmConfig`:

```scala
case class EvmConfig(
    blockchainConfig: BlockchainConfigForEvm,
    feeSchedule: FeeSchedule,
    opCodeList: OpCodeList,
    exceptionalFailedCodeDeposit: Boolean,
    subGasCapDivisor: Option[Long],
    chargeSelfDestructForNewAccount: Boolean,
    traceInternalTransactions: Boolean,
    noEmptyAccounts: Boolean = false,
    eip3541Enabled: Boolean = false,
    eip3651Enabled: Boolean = false,
    eip3860Enabled: Boolean = false
) {
  // ...
  def maxInitCodeSize: Option[BigInt] =
    if (eip3860Enabled) blockchainConfig.maxCodeSize.map(_ * 2) else None
}
```

The Spiral fork configuration enables EIP-3860:

```scala
val SpiralConfigBuilder: EvmConfigBuilder = config =>
  MystiqueConfigBuilder(config).copy(
    opCodeList = SpiralOpCodes,
    eip3651Enabled = true,
    eip3860Enabled = true
  )
```

A helper function is added to `BlockchainConfigForEvm`:

```scala
def isEip3860Enabled(etcFork: EtcFork): Boolean =
  etcFork >= EtcForks.Spiral
```

## Rationale

### Gas Cost Per Word

The value of `INITCODE_WORD_COST = 2` was selected based on performance benchmarks comparing initcode processing performance to KECCAK256 hashing, which is the baseline for the 70 Mgas/s gas limit target. The per-word (32-byte) cost of 2 gas approximates a per-byte cost of 0.0625 gas.

### Size Limit Value

The `MAX_INITCODE_SIZE = 2 × MAX_CODE_SIZE` allows:
- `MAX_CODE_SIZE` (24576 bytes) for the deployed runtime code
- Another `MAX_CODE_SIZE` for constructor code and initialization logic

This limit is generous for typical contracts while preventing worst-case DOS attacks.

### Order of Checks

For CREATE/CREATE2 opcodes, the initcode size check and cost are applied early, before:
- Contract address calculation
- Balance transfer
- Initcode execution

This matches the specification's requirement that initcode cost is "deducted before the calculation of the resulting contract address and the execution of initcode."

The exceptional abort for size limit violations is grouped with other early out-of-gas checks (stack underflow, memory expansion, etc.) for consistency.

### Backwards Compatibility

This EIP requires a "network upgrade" (hard fork) since it modifies consensus rules.

- **Existing contracts**: Not affected (deployed code size is unchanged)
- **New transactions**: Some previously valid transactions (with large initcode) become invalid
- **CREATE/CREATE2**: Can now fail with exceptional abort for large initcode

## Consequences

### Positive

1. **DOS protection**: Limits worst-case performance impact of large initcode
2. **Predictable costs**: Gas costs better reflect actual computational work
3. **Consistency**: CREATE and CREATE2 gas costs now account for initcode processing
4. **Forward compatibility**: The initcode cost structure allows future optimizations

### Negative

1. **Breaking change**: Some transactions that were valid before become invalid
2. **Increased gas costs**: Contract creation becomes slightly more expensive
3. **Factory contracts**: Multi-level contract factories with very large initcode may fail

### Risks

1. **Consensus critical**: Errors in size checking or gas calculation cause chain splits
2. **Edge cases**: Boundary conditions at MAX_INITCODE_SIZE must be exact
3. **Gas calculation**: Word-based calculation must match specification precisely

## Implementation Notes

### Testing Strategy

Tests must cover:
1. CREATE/CREATE2 with initcode at exactly MAX_INITCODE_SIZE (should succeed)
2. CREATE/CREATE2 with initcode at MAX_INITCODE_SIZE + 1 (should fail)
3. Create transaction with large initcode (validation)
4. Gas cost calculations for various initcode sizes
5. Interaction with other gas costs (memory expansion, hashing for CREATE2)
6. Fork activation boundary (before/after Spiral fork)

### ETC-Specific Considerations

- Activated at block 19,250,000 on ETC mainnet (Spiral fork)
- Activated at block 9,957,000 on Mordor testnet
- Must be controlled by the `spiral-block-number` configuration
- Part of ECIP-1109 (Spiral hard fork specification)

### Performance Impact

The changes have minimal performance impact:
- Initcode size check: O(1) comparison
- Gas cost calculation: O(1) arithmetic
- No change to existing contract execution

## References

- [EIP-3860 Specification](https://eips.ethereum.org/EIPS/eip-3860)
- [ECIP-1109 Spiral Hard Fork](https://ecips.ethereumclassic.org/ECIPs/ecip-1109)
- [EIP-170 Contract Code Size Limit](https://eips.ethereum.org/EIPS/eip-170)
- [EIP-1014 CREATE2](https://eips.ethereum.org/EIPS/eip-1014)
- [Ethereum Yellow Paper](https://ethereum.github.io/yellowpaper/paper.pdf)
