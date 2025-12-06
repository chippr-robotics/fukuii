# Launcher Integration Tests

## Overview

The launcher integration tests validate all supported Fukuii launch configurations, ensuring that command-line argument parsing and system configuration work correctly across different network and modifier combinations.

These tests are implemented in `LauncherIntegrationSpec` and are fully integrated into the automated CI/CD pipeline.

## Test Coverage

The launcher integration tests cover:

### Basic Launch Configurations
- Default ETC mainnet launch (no arguments)
- Explicit network launches (ETC, Mordor, Pottery, Sagano, etc.)
- All known network names validation

### Public Discovery Configurations
- Public modifier alone (defaults to ETC)
- Public modifier with explicit networks
- Public modifier in different argument positions

### Enterprise Mode Configurations
- Enterprise mode alone (defaults to ETC)
- Enterprise mode with various networks
- Enterprise mode feature validation:
  - Disabled public peer discovery
  - Disabled automatic port forwarding
  - RPC bound to localhost
  - Disabled peer blacklisting
  - Known nodes reuse

### Combined Modifiers and Options
- Public discovery with TUI
- Enterprise mode with custom configuration
- Multiple option flags
- Complex argument combinations

### Argument Parsing
- Modifier filtering
- Network name recognition
- Option flag parsing
- Argument order independence
- Edge cases and error handling

## Running Tests Locally

### Run Only Launcher Integration Tests

```bash
sbt "testOnly *LauncherIntegrationSpec"
```

### Run as Part of Essential Tests

The launcher integration tests are tagged with `UnitTest` and are included in the essential test suite:

```bash
sbt testEssential
```

### Run as Part of Full Test Suite

```bash
sbt test
```

## CI/CD Integration

The launcher integration tests are automatically run in the CI/CD pipeline as part of the essential test tier (Tier 1).

### CI Workflow Steps

1. **Essential Tests (Tier 1)** - Runs on every commit and PR
   - Command: `sbt testEssential`
   - Includes: `LauncherIntegrationSpec`
   - Target time: < 5 minutes

2. **Standard Tests (Tier 2)** - Runs on push to main branches
   - Command: `sbt testStandard`
   - Includes all tests from Tier 1
   - Target time: < 30 minutes

See `.github/workflows/ci.yml` for the complete CI configuration.

## Available Launch Configurations

### Networks

The following networks are supported and validated:

- `etc` - Ethereum Classic mainnet (default)
- `eth` - Ethereum mainnet
- `mordor` - Mordor testnet
- `pottery` - Pottery testnet
- `sagano` - Sagano testnet
- `bootnode` - Bootnode configuration (advanced)
- `testnet-internal-nomad` - Internal Nomad testnet (advanced)

### Modifiers

- `public` - Explicitly enable public peer discovery
- `enterprise` - Configure for private/permissioned networks

### Options

- `--tui` - Enable Terminal UI
- `--force-pivot-sync` - Disable checkpoint bootstrapping
- `--help`, `-h` - Show help message
- `-Dconfig.file=/path` - Specify custom configuration file

## Example Usage

### Testing Basic Configurations

```bash
# Launch default ETC mainnet
fukuii

# Launch explicit ETC mainnet
fukuii etc

# Launch Mordor testnet
fukuii mordor
```

### Testing Public Discovery

```bash
# Enable public discovery on ETC (default)
fukuii public

# Enable public discovery on Mordor
fukuii public mordor

# Public discovery with TUI
fukuii public etc --tui
```

### Testing Enterprise Mode

```bash
# Enterprise mode on default ETC
fukuii enterprise

# Enterprise mode on pottery network
fukuii enterprise pottery

# Enterprise mode with custom config
fukuii enterprise -Dconfig.file=/custom.conf
```

## Test Structure

The `LauncherIntegrationSpec` test suite is organized into the following behavior blocks:

1. **Basic launch configurations** - Tests for network selection
2. **Public discovery configurations** - Tests for public modifier
3. **Enterprise mode configurations** - Tests for enterprise modifier
4. **Combined modifiers and options** - Tests for complex scenarios
5. **Argument filtering and parsing** - Tests for argument processing
6. **Network configuration** - Tests for network config setup
7. **Enterprise mode features validation** - Tests for enterprise properties
8. **Modifier validation** - Tests for modifier recognition
9. **Option flag validation** - Tests for option flag parsing
10. **Complex launch scenarios** - Tests for real-world use cases
11. **Edge cases** - Tests for error conditions and edge cases

## Migration from Bash Script

The `test-launcher-integration.sh` bash script has been **deprecated** and replaced by `LauncherIntegrationSpec`.

### Why the Migration?

1. **CI/CD Integration** - Automated testing in the continuous integration pipeline
2. **Better Test Reporting** - Detailed test results and failure messages
3. **Maintainability** - Easier to extend and maintain in Scala
4. **Coverage** - More comprehensive test coverage with edge cases
5. **Consistency** - Uses the same testing framework as the rest of the codebase

### Migration Timeline

- **Current**: Both bash script and Scala tests available
- **Deprecated**: `test-launcher-integration.sh` is deprecated but functional
- **Future**: Bash script will be removed in a future release

## Adding New Tests

To add new launcher configuration tests:

1. Open `src/test/scala/com/chipprbots/ethereum/LauncherIntegrationSpec.scala`
2. Add a new test case in the appropriate behavior block
3. Use the `UnitTest` tag for fast-running tests
4. Run the tests locally to verify
5. Submit a PR with the changes

Example:

```scala
it should "validate new network configuration" taggedAs (UnitTest) in {
  val args = Array("newnetwork")
  val networks = args.filter(isNetwork)
  
  networks should contain only "newnetwork"
  isNetwork("newnetwork") shouldBe true
}
```

## Troubleshooting

### Tests Fail Locally But Pass in CI

- Ensure you have the latest changes: `git pull`
- Clean and rebuild: `sbt clean compile`
- Clear system properties between test runs

### Config File Not Found Warnings

These warnings are expected when config files don't exist in the test environment. The launcher correctly falls back to default configuration.

### Reflection Access Errors

The tests use reflection to access private methods in `App`. If you encounter access errors, ensure the test is running with the correct JVM permissions.

## Related Documentation

- [Testing Guide](README.md) - General testing documentation
- [Test Tagging Guide](TEST_TAGGING_GUIDE.md) - How to tag tests
- [CI/CD Integration](ETHEREUM_TESTS_CI_INTEGRATION.md) - CI/CD testing strategy
- [Enterprise Deployment](../runbooks/enterprise-deployment.md) - Enterprise mode details

## References

- Issue: Integrate launcher test script into automated CI/CD test suite
- Bash Script: `test-launcher-integration.sh` (deprecated)
- Test Suite: `src/test/scala/com/chipprbots/ethereum/LauncherIntegrationSpec.scala`
- Main Class: `src/main/scala/com/chipprbots/ethereum/App.scala`
