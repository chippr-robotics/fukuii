# Ethereum Tests Adapter

## Overview

This adapter implements support for the official [ethereum/tests](https://github.com/ethereum/tests) test suite, providing comprehensive EVM validation for Ethereum Classic blocks < 19.25M (pre-Spiral fork).

## Rationale

Per ADR-014, Ethereum Classic maintains 100% EVM compatibility with Ethereum through the Spiral fork (block 19.25M, January 2023). This allows us to use the official ethereum/tests repository for validation instead of maintaining our own test fixtures.

### Benefits

- **Comprehensive Coverage**: Thousands of community-maintained test cases
- **Standard Compliance**: Ensures EVM execution matches other execution clients
- **Future-Proof**: Reduces maintenance burden compared to custom fixtures
- **Validated**: Tests are used by all major Ethereum clients (geth, nethermind, besu, etc.)

## Implementation Status

### Phase 1: Infrastructure ✅ (Current)

**Completed:**
- JSON test format parser (`EthereumTestsAdapter.scala`)
- Data model for ethereum/tests format
- Converter from JSON to internal domain objects (`TestConverter.scala`)
- Base test infrastructure (`EthereumTestsSpec.scala`)
- Architecture Decision Record (ADR-015)
- Comprehensive documentation

**Test Format Support:**
- ✅ BlockchainTests JSON parsing
- ✅ Account state (balance, nonce, code, storage)
- ✅ Block headers (all fields)
- ✅ Transactions (signed transactions with v, r, s)
- ✅ Network/fork configuration mapping

### Phase 2: Execution (Next)

**TODO:**
- [ ] Implement test executor that runs blocks
- [ ] Set up initial state from `pre` field
- [ ] Execute transactions and validate state transitions
- [ ] Compare final state with `postState` field
- [ ] Validate state roots match expected values

### Phase 3: Integration (Future)

**TODO:**
- [ ] Download official ethereum/tests repository
- [ ] Create test suite that runs relevant test categories
- [ ] Replace ForksTest with ethereum/tests
- [ ] Replace ContractTest with ethereum/tests
- [ ] Add CI integration to run tests automatically

## Architecture

### Components

```
EthereumTestsAdapter      - Loads and parses JSON test files
  ├── BlockchainTestSuite  - Container for multiple test cases
  ├── BlockchainTest       - Single test scenario
  ├── AccountState         - Account state (pre/post)
  ├── TestBlock            - Block with header + transactions
  ├── TestBlockHeader      - Block header fields
  └── TestTransaction      - Signed transaction

TestConverter             - Converts JSON to domain objects
  ├── toAccount()          - AccountState -> Account
  ├── toBlockHeader()      - TestBlockHeader -> BlockHeader
  ├── toTransaction()      - TestTransaction -> SignedTransaction
  └── networkToConfig()    - Network name -> BlockchainConfig

EthereumTestsSpec         - Base class for running tests
  ├── runTestFile()        - Execute all tests in a file
  └── runSingleTest()      - Execute one test case
```

### Test File Format

ethereum/tests uses JSON format like:

```json
{
  "testName": {
    "pre": {
      "0x1234...": {
        "balance": "0x0de0b6b3a7640000",
        "code": "0x60806040...",
        "nonce": "0x00",
        "storage": {
          "0x00": "0x1234"
        }
      }
    },
    "blocks": [
      {
        "blockHeader": {
          "parentHash": "0x...",
          "stateRoot": "0x...",
          "difficulty": "0x020000",
          ...
        },
        "transactions": [...],
        "uncleHeaders": []
      }
    ],
    "postState": {
      "0x1234...": {
        "balance": "0x...",
        ...
      }
    },
    "network": "Byzantium"
  }
}
```

## Usage

### Running Tests

```scala
class MyBlockchainTest extends EthereumTestsSpec {
  "Ethereum tests" should "pass value transfer tests" in {
    runTestFile("/BlockchainTests/GeneralStateTests/stExample/add11.json")
  }
}
```

### Test Discovery

ethereum/tests repository structure:
```
BlockchainTests/
  GeneralStateTests/
    stArgsZeroOneBalance/
    stAttackTest/
    stBadOpcode/
    stBugs/
    stCallCodes/
    stCallCreateCallCodeTest/
    stCallDelegateCodesCallCodeHomestead/
    stCallDelegateCodesHomestead/
    ...
```

### Fork Support

Supported networks (mapped to ETC fork configurations):
- Frontier
- Homestead
- EIP150 (Tangerine Whistle)
- EIP158 (Spurious Dragon)
- Byzantium
- Constantinople
- Istanbul
- Berlin

Networks after Berlin (London, Shanghai, etc.) are NOT supported as ETC diverged at Spiral fork.

## Integration with Existing Tests

### Replacing ForksTest

Current `ForksTest.scala` (11 blocks, custom fixtures):
```scala
"Ledger" should "execute blocks with respect to forks" in new TestSetup {
  val fixtures = FixtureProvider.loadFixtures("/txExecTest/forksTest")
  // Execute blocks 1-11
}
```

Replacement with ethereum/tests:
```scala
"Ledger" should "pass fork transition tests" in {
  runTestFile("/BlockchainTests/TransitionTests/bcFrontierToHomestead.json")
  runTestFile("/BlockchainTests/TransitionTests/bcHomesteadToDao.json")
  runTestFile("/BlockchainTests/TransitionTests/bcHomesteadToEIP150.json")
}
```

### Replacing ContractTest

Current `ContractTest.scala` (3 blocks, contract deployment):
```scala
"Ledger" should "execute and validate" in new ScenarioSetup {
  val fixtures = FixtureProvider.loadFixtures("/txExecTest/purchaseContract")
  // Execute contract deployment and calls
}
```

Replacement with ethereum/tests:
```scala
"Ledger" should "pass contract tests" in {
  runTestFile("/BlockchainTests/GeneralStateTests/stCreate2/create2.json")
  runTestFile("/BlockchainTests/GeneralStateTests/stCallCodes/callcode.json")
}
```

## Development Roadmap

### Immediate (Phase 2)

1. **Implement Test Executor**
   - Create `EthereumTestExecutor` to run blocks
   - Use existing `BlockExecution` infrastructure
   - Set up state from JSON pre-state
   - Validate post-state matches expected

2. **Basic Test Coverage**
   - Simple value transfer tests
   - Contract deployment tests
   - Contract call tests

### Short-term (Phase 3)

3. **Download ethereum/tests**
   - Add as git submodule or download in CI
   - Select relevant test categories
   - Filter tests by network (only pre-Spiral forks)

4. **Replace Custom Fixtures**
   - Migrate ForksTest to ethereum/tests
   - Migrate ContractTest to ethereum/tests
   - Remove old custom fixture files

### Long-term

5. **Comprehensive Coverage**
   - Run full GeneralStateTests suite
   - Add VMTests (opcode-level validation)
   - Add difficulty tests
   - Add uncle block tests

6. **CI Integration**
   - Automated test updates when ethereum/tests updated
   - Performance tracking
   - Test result reporting

## Code Quality

### Standards Compliance

This implementation follows all guidelines from CONTRIBUTING.md:

- ✅ **Scalafmt**: Code formatted according to `.scalafmt.conf`
- ✅ **Scala 3**: Uses Scala 3.3.4 (LTS) syntax and features
- ✅ **Documentation**: Comprehensive Scaladoc comments
- ✅ **ADR**: ADR-015 documents rationale and design decisions
- ✅ **Type Safety**: Circe decoders for compile-time JSON validation
- ✅ **Separation of Concerns**: Clear component boundaries

### Code Style

- **Max Line Length**: 120 characters (per .scalafmt.conf)
- **Naming**: CamelCase for classes, camelCase for methods/fields
- **Comments**: Scaladoc for public APIs, inline comments for complex logic
- **Error Handling**: Explicit error types, no silent failures

## References

- [ethereum/tests Repository](https://github.com/ethereum/tests)
- [Test Format Documentation](https://ethereum-tests.readthedocs.io/)
- [ADR-015: Ethereum Tests Adapter](../adr/015-ethereum-tests-adapter.md)
- [ADR-014: EIP-161 noEmptyAccounts Fix](../adr/014-eip-161-noemptyaccounts-fix.md)
- [ETC Fork Timeline](https://etclabs.org/etc-forks)
- [CONTRIBUTING.md](../../CONTRIBUTING.md)

## Related Files

- `src/it/scala/com/chipprbots/ethereum/ethtest/EthereumTestsAdapter.scala`
- `src/it/scala/com/chipprbots/ethereum/ethtest/TestConverter.scala`
- `src/it/scala/com/chipprbots/ethereum/ethtest/EthereumTestsSpec.scala`
- `docs/adr/015-ethereum-tests-adapter.md`
