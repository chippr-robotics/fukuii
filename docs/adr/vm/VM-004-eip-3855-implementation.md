# ADR-005: EIP-3855 Implementation (PUSH0 Instruction)

## Status

Accepted

## Context

EIP-3855 (https://eips.ethereum.org/EIPS/eip-3855) is an Ethereum Improvement Proposal that was activated as part of the Shanghai hard fork on Ethereum mainnet. For Ethereum Classic, this proposal is included in the Spiral hard fork (ECIP-1109: https://ecips.ethereumclassic.org/ECIPs/ecip-1109) at block 19,250,000 on mainnet and block 9,957,000 on Mordor testnet.

The proposal introduces a new EVM instruction `PUSH0` that pushes the constant value 0 onto the stack. Specifically:

- **Problem**: Before EIP-3855, contracts that needed to push zero onto the stack had to use `PUSH1 0x00`, which costs 3 gas (G_verylow) and occupies 2 bytes in the bytecode (opcode + immediate data). However, pushing zero is a very common operation in smart contracts (for comparisons, default values, etc.), and this inefficiency adds unnecessary gas costs and code size.

- **Solution**: Introduce a new opcode `PUSH0` at byte value `0x5f` that pushes the constant value 0 onto the stack. This instruction:
  - Has no immediate data (0 bytes after the opcode)
  - Pops 0 items from the stack (delta = 0)
  - Pushes 1 item onto the stack (alpha = 1)
  - Costs 2 gas (G_base)

The change affects:
- EVM bytecode compilation and interpretation
- Gas costs for pushing zero values
- Bytecode size optimization
- Opcode dispatch in the VM execution loop

This is both a gas cost optimization and bytecode size optimization. It does not affect:
- Existing contract behavior (the opcode was previously unused)
- Transaction validity
- Contract storage or state
- Any other opcodes

## Decision

We implemented EIP-3855 in the Fukuii codebase with the following design decisions:

### 1. Opcode Definition

The `PUSH0` opcode is defined as a case object in `OpCode.scala` at byte value `0x5f`:

```scala
case object PUSH0 extends OpCode(0x5f, 0, 1, _.G_base) with ConstGas {
  protected def exec[W <: WorldStateProxy[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    val stack1 = state.stack.push(UInt256.Zero)
    state.withStack(stack1).step()
  }
}
```

Key characteristics:
- **Opcode byte**: `0x5f` (positioned between `JUMPDEST` at `0x5b` and `PUSH1` at `0x60`)
- **Delta (stack pops)**: 0 (pops no items)
- **Alpha (stack pushes)**: 1 (pushes one item)
- **Gas cost**: `G_base` (2 gas)
- **Constant gas**: Implements `ConstGas` trait (no variable gas component)

### 2. Opcode List Integration

The `PUSH0` opcode is added to the Spiral opcode list:

```scala
val SpiralOpCodes: List[OpCode] =
  PUSH0 +: PhoenixOpCodes
```

This ensures that `PUSH0` is only available in the Spiral fork and later forks.

### 3. Fork Configuration

The Spiral fork configuration is added to `EvmConfig`:

```scala
val SpiralOpCodes: OpCodeList = OpCodeList(OpCodes.SpiralOpCodes)

val SpiralConfigBuilder: EvmConfigBuilder = config =>
  MystiqueConfigBuilder(config).copy(
    opCodeList = SpiralOpCodes,
    eip3651Enabled = true
  )
```

And added to the fork transition mapping with priority 12:

```scala
(blockchainConfig.spiralBlockNumber, 12, SpiralConfigBuilder)
```

### 4. Fork Enumeration

A new `Spiral` value is added to the `EtcForks` enumeration in `BlockchainConfigForEvm`:

```scala
object EtcForks extends Enumeration {
  type EtcFork = Value
  val BeforeAtlantis, Atlantis, Agharta, Phoenix, Magneto, Mystique, Spiral = Value
}
```

And a helper method is provided to check if EIP-3855 is enabled:

```scala
def isEip3855Enabled(etcFork: EtcFork): Boolean =
  etcFork >= EtcForks.Spiral
```

### 5. Configuration Files

The Spiral fork block numbers are added to all chain configuration files:

**ETC Mainnet** (`etc-chain.conf`):
```
spiral-block-number = "19250000"
```

**Mordor Testnet** (`mordor-chain.conf`):
```
spiral-block-number = "9957000"
```

**Other chains**: Set to far future (`1000000000000000000`) as they don't support ETC-specific forks.

### 6. Implementation Rationale

#### Gas Cost (G_base = 2)
The `G_base` (2 gas) cost is used for instructions that place constant values onto the stack, such as `ADDRESS`, `ORIGIN`, `CALLER`, `CALLVALUE`, etc. This is cheaper than `PUSH1 0x00` which costs `G_verylow` (3 gas).

#### Opcode Position (0x5f)
The opcode `0x5f` is in a "contiguous" space with the rest of the PUSH implementations (`PUSH1` at `0x60`, `PUSH2` at `0x61`, etc.). This positioning makes sense logically: `PUSH0` comes immediately before `PUSH1` in the opcode space.

#### Implementation Simplicity
Unlike `PUSH1`-`PUSH32` which need to read immediate data from the bytecode, `PUSH0` has no immediate data. It simply:
1. Pushes `UInt256.Zero` onto the stack
2. Advances the program counter by 1 (just the opcode byte)

This makes the implementation very simple and efficient.

## Consequences

### Positive

1. **Gas Cost Reduction**: Contracts that push zero can save 1 gas per operation (2 instead of 3).

2. **Bytecode Size Reduction**: Each `PUSH0` is 1 byte instead of 2 bytes for `PUSH1 0x00`, reducing contract deployment costs and improving cache efficiency.

3. **Compiler Optimization**: Compilers like Solidity can optimize zero-pushing operations, leading to more efficient smart contracts.

4. **No Breaking Changes**: The opcode `0x5f` was previously unused (would cause an invalid opcode error), so existing contracts are not affected.

5. **EVM Specification Alignment**: Keeps Ethereum Classic aligned with Ethereum mainnet's Shanghai fork.

6. **Simple Implementation**: The change is straightforward and localized to opcode definition and fork configuration.

### Negative

1. **Consensus-Critical Change**: This is a consensus-critical change that affects contract execution. All nodes must activate it at the same block number to maintain consensus.

2. **Testing Requirement**: Requires comprehensive testing to ensure correct stack behavior, gas costs, and edge cases (stack overflow, out of gas).

3. **Fork Coordination**: Requires coordination with other ETC clients and the ETC community for fork activation.

### Neutral

1. **Limited Immediate Impact**: Existing contracts won't automatically benefit; only newly deployed contracts can use `PUSH0`.

2. **Compiler Dependency**: Full benefits require compiler support (Solidity, Vyper, etc.) to emit `PUSH0` instead of `PUSH1 0x00`.

## Implementation Details

### Files Modified

1. **OpCode.scala**: 
   - Added `PUSH0` case object
   - Added `SpiralOpCodes` list

2. **EvmConfig.scala**:
   - Added `SpiralOpCodes` OpCodeList
   - Added `SpiralConfigBuilder`
   - Added Spiral fork to transition mapping

3. **BlockchainConfigForEvm.scala**:
   - Added `Spiral` to `EtcForks` enumeration
   - Added `spiralBlockNumber` parameter
   - Updated `etcForkForBlockNumber` method
   - Added `isEip3855Enabled` helper method

4. **BlockchainConfig.scala**:
   - Added `spiralBlockNumber` to `ForkBlockNumbers` case class
   - Updated config parsing to read `spiral-block-number`

5. **VMServer.scala**:
   - Added `spiralBlockNumber` parameter (set to far future as TODO)

6. **Configuration files**:
   - Updated `etc-chain.conf`, `mordor-chain.conf`, `eth-chain.conf`, `test-chain.conf`, `ropsten-chain.conf`, `testnet-internal-nomad-chain.conf`

7. **Test files**:
   - Updated `Fixtures.scala`, `VMSpec.scala`, `VMClientSpec.scala` to include `spiralBlockNumber`
   - Created `Push0Spec.scala` with 11 comprehensive tests

### Testing Strategy

1. **Unit Tests**: Verify `PUSH0` behavior in isolation
   - Pushes zero onto stack
   - Uses 2 gas (G_base)
   - Advances program counter by 1
   - Fails with `StackOverflow` when stack is full
   - Fails with `OutOfGas` when insufficient gas

2. **EIP-3855 Specification Tests**: From the EIP specification
   - Single `PUSH0` execution (stack contains one zero)
   - 1024 `PUSH0` operations (stack contains 1024 zeros)
   - 1025 `PUSH0` operations (fails with `StackOverflow`)

3. **Gas Cost Comparison**: Verify `PUSH0` is cheaper than `PUSH1 0x00`
   - `PUSH0` costs 2 gas
   - `PUSH1 0x00` costs 3 gas

4. **Integration Tests**: Verify correct opcode availability
   - `PUSH0` available in Spiral fork
   - `PUSH0` not available in pre-Spiral forks

### Test Results

All 11 tests in `Push0Spec.scala` pass:
- ✓ PUSH0 opcode is available in Spiral fork
- ✓ PUSH0 should push zero onto the stack
- ✓ PUSH0 should use 2 gas (G_base)
- ✓ PUSH0 should fail with StackOverflow when stack is full
- ✓ PUSH0 should fail with OutOfGas when not enough gas
- ✓ PUSH0 multiple times should push multiple zeros
- ✓ PUSH0 has correct opcode properties
- ✓ PUSH0 should be cheaper than PUSH1 with zero
- ✓ EIP-3855 test case: single PUSH0 execution
- ✓ EIP-3855 test case: 1024 PUSH0 operations
- ✓ EIP-3855 test case: 1025 PUSH0 operations should fail

## Security Considerations

The EIP-3855 specification notes:
> The authors are not aware of any impact on security. Note that jumpdest-analysis is unaffected, as PUSH0 has no immediate data bytes.

Our implementation maintains this security:

1. **No Immediate Data**: `PUSH0` has no immediate data bytes, so jumpdest analysis is not affected.

2. **Stack Validation**: The standard stack overflow/underflow checks apply to `PUSH0` just like any other opcode.

3. **Gas Metering**: The gas cost is correctly applied and checked before execution.

4. **Deterministic Execution**: `PUSH0` always pushes exactly `UInt256.Zero`, ensuring deterministic behavior.

5. **No State Changes**: `PUSH0` only affects the stack, not storage, memory, or account state.

## References

- [EIP-3855: PUSH0 instruction](https://eips.ethereum.org/EIPS/eip-3855)
- [ECIP-1109: Spiral Hard Fork](https://ecips.ethereumclassic.org/ECIPs/ecip-1109)
- [Ethereum Yellow Paper](https://ethereum.github.io/yellowpaper/paper.pdf)
- [EVM Opcodes Reference](https://www.evm.codes/)

## Notes

- This EIP was part of Ethereum's Shanghai hard fork (March 2023)
- For ETC, this is part of the Spiral hard fork (ECIP-1109):
  - **Mainnet activation**: Block 19,250,000
  - **Mordor testnet activation**: Block 9,957,000
- The implementation is designed to be consistent with other ETC fork activations
- The opcode byte `0x5f` was previously unused and would cause `InvalidOpCode` error
- Backwards compatibility: Existing deployed contracts are unaffected as they couldn't have used `0x5f`
- Forward compatibility: Compilers can start emitting `PUSH0` after the fork activation

## Performance Implications

1. **Gas Savings**: 1 gas saved per zero-push operation (33% reduction: 2 vs 3 gas)

2. **Bytecode Size**: 1 byte saved per zero-push operation (50% reduction: 1 vs 2 bytes)

3. **Execution Speed**: Slightly faster execution as no immediate data needs to be read from bytecode

4. **Deployment Cost**: Reduced deployment costs for contracts that frequently push zero

Example savings for a contract with 100 zero-push operations:
- Gas saved: 100 gas
- Bytecode bytes saved: 100 bytes
- Deployment cost saved: ~20,000 gas (100 bytes * 200 gas/byte)

Total savings: ~20,100 gas per contract deployment + 100 gas per contract execution
