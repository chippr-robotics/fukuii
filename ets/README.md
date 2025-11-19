# Ethereum Test Suite

This folder contains a git submodule pointing at the Ethereum Consensus Tests,
also known as the Ethereum Test Suite (ETS), config files for retesteth (the
tool for running these tests) and a wrapper script to set the its command line
options. Oh, and this readme file is in there too, of course.

* ETS: https://github.com/ethereum/tests
* retesteth: https://github.com/ethereum/retesteth

## Integration Tests (Recommended)

Fukuii includes native Scala integration tests that directly execute ethereum/tests test cases.
These are the recommended way to run ethereum/tests locally and in CI.

### Quick Start

```bash
# Run basic validation tests (4 tests, ~1 minute)
sbt "IntegrationTest / testOnly *SimpleEthereumTest"

# Run standard test suite (14 tests, ~5-10 minutes)
sbt "IntegrationTest / testOnly *SimpleEthereumTest *BlockchainTestsSpec"

# Run comprehensive test suite (98+ tests, ~20-30 minutes)
sbt "IntegrationTest / testOnly com.chipprbots.ethereum.ethtest.*"
```

### Test Categories

- **SimpleEthereumTest**: Basic validation (SimpleTx Berlin/Istanbul variants)
- **BlockchainTestsSpec**: Focused blockchain tests from ValidBlocks
- **ComprehensiveBlockchainTestsSpec**: Extended tests across multiple categories
- **GeneralStateTestsSpec**: State transition tests
- **GasCalculationIssuesSpec**: Gas calculation validation

### Prerequisites

The ethereum/tests submodule must be initialized:

```bash
git submodule init
git submodule update
```

Verify the submodule is populated:
```bash
ls -la ets/tests/BlockchainTests/
# Should show: GeneralStateTests, InvalidBlocks, TransitionTests, ValidBlocks
```

### Documentation

See comprehensive documentation in:
- `docs/ETHEREUM_TESTS_CI_INTEGRATION.md` - CI integration guide
- `docs/ETHEREUM_TESTS_MIGRATION.md` - Migration from custom tests
- `docs/GAS_CALCULATION_ISSUES.md` - Known issues (resolved)
- `docs/PHASE_3_SUMMARY.md` - Phase 3 implementation summary

## Running locally (retesteth)

Use the `test-ets.sh` script to boot Fukuii and run retesteth against it.

## Continuous integration

### Standard CI (Every Push/PR)

The CI pipeline automatically runs ethereum/tests integration tests on every push and pull request.
See `.github/workflows/ci.yml` for configuration.

**What runs:**
- SimpleEthereumTest and BlockchainTestsSpec (~14 tests)
- Execution time: ~5-10 minutes
- Artifacts: Test results and logs (7-day retention)

### Nightly Comprehensive Tests

A nightly workflow runs all ethereum/tests integration tests.
See `.github/workflows/ethereum-tests-nightly.yml` for configuration.

**What runs:**
- All ethereum/tests integration test classes
- Execution time: ~20-30 minutes
- Artifacts: Comprehensive test results and logs (30-day retention)
- Schedule: 02:00 GMT daily

## Running retesteth (Legacy)

Two test suites are run; GeneralStateTests and BlockchainTests. These seem to
be the only ones maintained and recommended at the moment.

## Running ETS locally with retesteth

Start Fukuii in test mode:

    sbt -Dconfig.file=./src/main/resources/conf/testmode.conf -Dlogging.logs-level=WARN run

NB. raising the log level is a good idea as there will be a lot of output,
depending on how many tests you run.

Once the RPC API is up, run retesteth (requires retesteth to be installed separately):

    ets/retesteth -t GeneralStateTests

You can also run parts of the suite; refer to `ets/retesteth --help` for details.

## Running retesteth separately

You should run Fukuii outside of any container as that is probably more convenient for your
tooling (eg. attaching a debugger.)

    sbt -Dconfig.file=./src/main/resources/conf/testmode.conf -Dlogging.logs-level=WARN run

Retesteth will need to be able to connect to Fukuii. If running retesteth in a container,
make sure it can access the host system where Fukuii is running.

## Useful options:

You can run one test by selecting one suite and using `--singletest`, for instance:

However it's not always clear in which subfolder the suite is when looking at the output of retesteth.

To get more insight about what is happening, you can use `--verbosity 6`. It will print every RPC call 
made by retesteth and also print out the state by using our `debug_*` endpoints. Note however that 
`debug_accountRange` and `debug_storageRangeAt` implementations are not complete at the moment :

 - `debug_accountRange` will only list accounts known at the genesis state. 
 - `debug_storageRangeAt` is not able to show the state after an arbitrary transaction inside a block.
It will just return the state after all transaction in the block have run.