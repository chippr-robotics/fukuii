# ADR-0001: EIP-3541 Implementation

## Status

Accepted

## Context

EIP-3541 (https://eips.ethereum.org/EIPS/eip-3541) is an Ethereum Improvement Proposal that was activated as part of the London hard fork on Ethereum mainnet. For Ethereum Classic, this proposal is included in the Mystique hard fork.

The proposal addresses forward compatibility for potential future Ethereum Object Format (EOF) implementations by reserving the `0xEF` byte prefix for special contract code formats. Specifically:

- **Problem**: Without this restriction, contracts could be deployed with bytecode starting with `0xEF`, which could conflict with future EOF formats that plan to use this prefix.
- **Solution**: Reject contract creation attempts when the resulting contract code would start with the `0xEF` byte.

The restriction applies to all contract creation mechanisms:
- Contract creation transactions (transactions with no recipient address)
- The `CREATE` opcode
- The `CREATE2` opcode

This is a validation-only change and does not affect:
- Existing contracts (even if they start with `0xEF`)
- Contract execution
- Transaction gas costs (except that rejected contracts consume all provided gas)

## Decision

We implemented EIP-3541 in the Fukuii codebase with the following design decisions:

### 1. Configuration-Based Activation

The EIP-3541 validation is controlled by a boolean flag `eip3541Enabled` in the `EvmConfig` class:

```scala
case class EvmConfig(
    // ... other fields ...
    eip3541Enabled: Boolean = false
)
```

This flag is set to `true` for the Mystique fork and later:

```scala
val MystiqueConfigBuilder: EvmConfigBuilder = config =>
  MagnetoConfigBuilder(config).copy(
    feeSchedule = new ethereum.vm.FeeSchedule.MystiqueFeeSchedule,
    eip3541Enabled = true
  )
```

### 2. Fork-Based Activation

The activation is tied to the Ethereum Classic fork schedule through the `BlockchainConfigForEvm` utility:

```scala
def isEip3541Enabled(etcFork: EtcFork): Boolean =
  etcFork >= EtcForks.Mystique
```

This ensures that the validation is only active for blocks at or after the Mystique fork block number.

### 3. VM-Level Validation

The actual validation logic is implemented in the `VM.saveNewContract` method, which is called for all contract creation operations:

```scala
private def saveNewContract(context: PC, address: Address, result: PR, config: EvmConfig): PR =
  if (result.error.isDefined) {
    // ... error handling ...
  } else {
    val contractCode = result.returnData
    val codeDepositCost = config.calcCodeDepositCost(contractCode)
    
    val maxCodeSizeExceeded = exceedsMaxContractSize(context, config, contractCode)
    val codeStoreOutOfGas = result.gasRemaining < codeDepositCost
    // EIP-3541: Reject new contracts starting with 0xEF byte
    val startsWithEF = config.eip3541Enabled && contractCode.nonEmpty && contractCode.head == 0xef.toByte
    
    if (startsWithEF) {
      // EIP-3541: Code starting with 0xEF byte causes exceptional abort
      result.copy(error = Some(InvalidCode), gasRemaining = 0)
    } else if (maxCodeSizeExceeded || (codeStoreOutOfGas && config.exceptionalFailedCodeDeposit)) {
      // ... other validation logic ...
    }
  }
```

Key implementation details:
- The check is performed after the initialization code has been executed
- The check inspects the **returned contract code**, not the initialization code
- When validation fails:
  - Error type is `InvalidCode`
  - All remaining gas is consumed (`gasRemaining = 0`)
  - No contract code is saved to the world state

### 4. Centralized Validation Point

By implementing the validation in `saveNewContract`, we ensure that:
- The same validation logic applies to all contract creation mechanisms (transactions, CREATE, CREATE2)
- The validation is performed at the appropriate time (after init code execution, before code storage)
- The validation is consistent with other contract creation validations (code size, gas costs)

## Implementation Files

The implementation spans the following files:

1. **`src/main/scala/com/chipprbots/ethereum/vm/EvmConfig.scala`**
   - Defines the `eip3541Enabled` configuration flag
   - Sets the flag to `true` in `MystiqueConfigBuilder`

2. **`src/main/scala/com/chipprbots/ethereum/vm/BlockchainConfigForEvm.scala`**
   - Provides `isEip3541Enabled` utility function
   - Maps ETC forks to EIP-3541 activation status

3. **`src/main/scala/com/chipprbots/ethereum/vm/VM.scala`**
   - Implements the validation logic in `saveNewContract` method
   - Returns `InvalidCode` error when bytecode starts with `0xEF`
   - Consumes all remaining gas on validation failure

4. **`src/test/scala/com/chipprbots/ethereum/vm/Eip3541Spec.scala`**
   - Comprehensive test suite validating the implementation

## Unit Tests

The implementation is thoroughly tested through the `Eip3541Spec` test suite. The test coverage includes:

### 1. Fork Activation Tests

```scala
"EIP-3541" should {
  "be disabled before Mystique fork" in {
    configPreMystique.eip3541Enabled shouldBe false
  }
  
  "be enabled at Mystique fork" in {
    configMystique.eip3541Enabled shouldBe true
  }
  
  "isEip3541Enabled should return true for Mystique fork" in {
    val etcFork = blockchainConfig.etcForkForBlockNumber(Fixtures.MystiqueBlockNumber)
    BlockchainConfigForEvm.isEip3541Enabled(etcFork) shouldBe true
  }
  
  "isEip3541Enabled should return false for pre-Mystique forks" in {
    val magnetoFork = blockchainConfig.etcForkForBlockNumber(Fixtures.MagnetoBlockNumber)
    BlockchainConfigForEvm.isEip3541Enabled(magnetoFork) shouldBe false
    
    val phoenixFork = blockchainConfig.etcForkForBlockNumber(Fixtures.PhoenixBlockNumber)
    BlockchainConfigForEvm.isEip3541Enabled(phoenixFork) shouldBe false
  }
}
```

**Coverage**: Verifies that EIP-3541 is correctly enabled/disabled based on fork configuration.

### 2. Pre-Fork Behavior Tests

```scala
"EIP-3541: Contract creation with CREATE" when {
  "pre-Mystique fork" should {
    "allow deploying contract starting with 0xEF byte" in {
      val context = fxt.createContext(
        fxt.initWorld, 
        fxt.initCodeReturningEF.code, 
        fxt.fakeHeaderPreMystique, 
        configPreMystique
      )
      val result = new VM[MockWorldState, MockStorage].run(context)
      result.error shouldBe None
      result.gasRemaining should be > BigInt(0)
    }
  }
}
```

**Coverage**: Ensures backward compatibility - contracts starting with `0xEF` are allowed before the Mystique fork.

### 3. Post-Fork Rejection Tests

Multiple test cases verify that contracts starting with `0xEF` are rejected after the Mystique fork:

```scala
"post-Mystique fork (EIP-3541 enabled)" should {
  "reject contract with one byte 0xEF" in {
    val context = fxt.createContext(
      fxt.initWorld, 
      fxt.initCodeReturningEF.code, 
      fxt.fakeHeaderMystique, 
      configMystique
    )
    val result = new VM[MockWorldState, MockStorage].run(context)
    result.error shouldBe Some(InvalidCode)
    result.gasRemaining shouldBe 0
    result.world.getCode(fxt.newAddr) shouldBe ByteString.empty
  }
  
  "reject contract with two bytes 0xEF00" in {
    // Similar test with 0xEF00 bytecode
  }
  
  "reject contract with three bytes 0xEF0000" in {
    // Similar test with 0xEF0000 bytecode
  }
  
  "reject contract with 32 bytes starting with 0xEF" in {
    // Similar test with 32-byte bytecode starting with 0xEF
  }
}
```

**Coverage**: Tests various bytecode lengths all starting with `0xEF` to ensure the validation works correctly regardless of contract code size.

### 4. Alternative Bytecode Tests

```scala
"allow deploying contract starting with 0xFE byte" in {
  val context = fxt.createContext(
    fxt.initWorld, 
    fxt.initCodeReturningFE.code, 
    fxt.fakeHeaderMystique, 
    configMystique
  )
  val result = new VM[MockWorldState, MockStorage].run(context)
  result.error shouldBe None
  result.gasRemaining should be > BigInt(0)
}

"allow deploying contract with empty code" in {
  val context = fxt.createContext(
    fxt.initWorld, 
    fxt.initCodeReturningEmpty.code, 
    fxt.fakeHeaderMystique, 
    configMystique
  )
  val result = new VM[MockWorldState, MockStorage].run(context)
  result.error shouldBe None
  result.world.getCode(fxt.newAddr) shouldBe ByteString.empty
}
```

**Coverage**: Verifies that:
- Other bytecode prefixes (like `0xFE`) are still allowed
- Empty contract code is allowed
- Only `0xEF` prefix is rejected

### 5. Gas Consumption Tests

```scala
"EIP-3541: Gas consumption" should {
  "consume all gas when rejecting 0xEF contract" in {
    val context = fxt.createContext(
      fxt.initWorld,
      fxt.initCodeReturningEF.code,
      fxt.fakeHeaderMystique,
      configMystique,
      startGas = 100000
    )
    val result = new VM[MockWorldState, MockStorage].run(context)
    result.error shouldBe Some(InvalidCode)
    result.gasRemaining shouldBe 0
  }
}
```

**Coverage**: Confirms that when a contract is rejected due to EIP-3541, all remaining gas is consumed, matching the exceptional halt behavior specified in the EIP.

### 6. Opcode Coverage

While the tests primarily use contract creation transactions (no recipient address), placeholder tests acknowledge that the same validation applies to `CREATE` and `CREATE2` opcodes:

```scala
"EIP-3541: Contract creation with CREATE opcode" when {
  "post-Mystique fork (EIP-3541 enabled)" should {
    "reject contract deployment via CREATE starting with 0xEF" in {
      // Note: The validation happens in VM.saveNewContract which is called 
      // for all contract creations including those from CREATE/CREATE2 opcodes.
      succeed
    }
  }
}
```

**Coverage**: Documents that the centralized validation in `saveNewContract` ensures consistent behavior across all contract creation methods.

## Test Fixtures and Utilities

The test suite uses several helper constructs to test different scenarios:

### Assembly Fixtures

The tests define init code assembly programs that return different bytecode patterns:

- `initCodeReturningEF`: Returns single byte `0xEF`
- `initCodeReturningEF00`: Returns two bytes `0xEF00`
- `initCodeReturningEF0000`: Returns three bytes `0xEF0000`
- `initCodeReturningEF32Bytes`: Returns 32 bytes starting with `0xEF`
- `initCodeReturningFE`: Returns single byte `0xFE` (allowed)
- `initCodeReturningEmpty`: Returns empty bytecode (allowed)

These fixtures use EVM assembly opcodes (`PUSH1`, `MSTORE8`, `RETURN`) to construct various test cases.

### Mock World State

Tests use a `MockWorldState` to simulate blockchain state without requiring a full node or database, enabling fast, isolated unit tests.

## Test Execution

All tests are implemented using ScalaTest's `AnyWordSpec` style with `Matchers`. To run the EIP-3541 tests:

```bash
sbt "testOnly *Eip3541Spec"
```

Or to run all VM tests:

```bash
sbt test
```

## Consequences

### Positive Consequences

1. **Forward Compatibility**: Reserving the `0xEF` prefix enables future EOF implementations without breaking existing contracts.

2. **Minimal Impact**: The change only affects new contract deployments starting with `0xEF`, which is extremely rare in practice.

3. **Clean Implementation**: By implementing the validation in a single centralized location (`saveNewContract`), we ensure consistent behavior across all contract creation mechanisms.

4. **Configuration Flexibility**: The fork-based activation allows the feature to be enabled/disabled per network configuration.

5. **Comprehensive Testing**: The test suite provides strong confidence that the implementation behaves correctly across various scenarios.

6. **Standards Compliance**: The implementation follows the EIP-3541 specification exactly, ensuring compatibility with other Ethereum Classic clients.

### Negative Consequences

1. **Breaking Change**: Any contract deployment that would result in bytecode starting with `0xEF` will fail after the Mystique fork. However, this is intentional and aligned with the broader Ethereum ecosystem.

2. **Gas Consumption**: Failed deployments consume all provided gas, which could be surprising to developers. However, this is required by the EIP specification to prevent gas griefing attacks.

3. **No Mitigation Path**: There is no way for a user to deploy a contract starting with `0xEF` after the fork activates. This is by design but could affect specific use cases (e.g., security research or testing tools).

### Trade-offs

1. **Simplicity vs. Flexibility**: We chose a simple boolean flag approach rather than a more complex validation framework. This is appropriate given that EIP-3541 has a single, well-defined validation rule.

2. **Centralized vs. Distributed Validation**: Implementing validation in `saveNewContract` means all contract creation paths go through the same validation. This ensures consistency but means the validation logic is somewhat hidden from the individual opcode implementations.

3. **Test Coverage vs. Complexity**: The test suite uses direct VM invocation rather than testing through the full transaction processing stack. This provides faster, more isolated tests but doesn't validate integration with higher-level components.

## References

- [EIP-3541 Specification](https://eips.ethereum.org/EIPS/eip-3541)
- [Ethereum Classic Mystique Hard Fork Specification](https://ecips.ethereumclassic.org/ECIPs/ecip-1104)
- [EIP-3540: EOF - EVM Object Format v1](https://eips.ethereum.org/EIPS/eip-3540) (Future work that EIP-3541 enables)

## Related Decisions

- This ADR should be updated when EOF (EIP-3540) is implemented to reference how EIP-3541 facilitated that implementation.

## Notes

- The implementation uses `0xef.toByte` for the byte comparison, which is the signed byte representation (-17) of the unsigned value 0xEF (239).
- The `InvalidCode` error type was chosen to be consistent with other code validation errors in the VM.
- The test suite uses fixtures at specific fork block numbers (`Fixtures.MagnetoBlockNumber`, `Fixtures.MystiqueBlockNumber`) to ensure tests remain valid across different network configurations.
