# Test Coverage Integration Guide

**Quick Reference for Coverage Reporting and Test Execution**

---

## Quick Commands

### Generate Coverage Reports

```bash
# Per-system coverage (recommended for targeted improvements)
sbt clean coverage testVM coverageReport
sbt clean coverage testCrypto coverageReport
sbt clean coverage testNetwork coverageReport
sbt clean coverage "testOnly -- -n RPCTest" coverageReport
sbt clean coverage "testOnly -- -n ConsensusTest" coverageReport
sbt clean coverage testDatabase coverageReport

# Full coverage with aggregation
sbt clean coverage testAll coverageReport coverageAggregate

# View HTML report
open target/scala-3.3.4/scoverage-report/index.html
```

### Run Tests by System

```bash
# Single system
sbt testVM          # All VM tests
sbt testCrypto      # All Crypto tests
sbt testNetwork     # All Network tests
sbt testDatabase    # All Database tests
sbt testRLP         # All RLP tests
sbt testMPT         # All MPT tests

# Multiple systems
sbt testVM testCrypto testNetwork

# Tag-based filtering
sbt "testOnly -- -n RPCTest"                    # Include RPCTest
sbt "testOnly -- -n NetworkTest -n RPCTest"     # Include Network OR RPC
sbt "testOnly -- -n VMTest -l SlowTest"         # Include VM, exclude Slow
```

### Tier-based Execution

```bash
sbt testEssential       # <5 min  - excludes SlowTest, IntegrationTest, SyncTest
sbt testStandard        # <30 min - excludes BenchmarkTest, EthereumTest
sbt testComprehensive   # <3 hrs  - all tests including ethereum/tests
```

---

## Tag-to-System Mapping

| Tag | System | Files | Command |
|-----|--------|-------|---------|
| VMTest | VM & Execution | ~29 | `sbt testVM` |
| CryptoTest | Cryptography | ~12 | `sbt testCrypto` |
| NetworkTest | Network & P2P | 21 | `sbt testNetwork` |
| RPCTest | JSON-RPC API | 26 | `testOnly -n RPCTest` |
| ConsensusTest | Consensus & Mining | 20 | `testOnly -n ConsensusTest` |
| DatabaseTest | Database & Storage | ~15 | `sbt testDatabase` |
| StateTest | Blockchain State | ~15 | `testOnly -n StateTest` |
| SyncTest | Synchronization | ~33 | `testOnly -n SyncTest` |
| RLPTest | RLP Encoding | ~6 | `sbt testRLP` |
| MPTTest | Merkle Patricia Trie | ~8 | `sbt testMPT` |

---

## Coverage Report Structure

When generating per-system coverage, organize reports as:

```
coverage-reports/
├── vm-coverage/
│   ├── index.html          # Main HTML report
│   └── scoverage.xml       # XML for CI tools
├── crypto-coverage/
├── network-coverage/
├── rpc-coverage/
├── consensus-coverage/
├── database-coverage/
└── aggregate-coverage/     # Full system coverage
```

### Script to Generate All Reports

Create `scripts/generate_coverage_baseline.sh`:

```bash
#!/bin/bash
set -e

REPORT_DIR="coverage-reports"
mkdir -p "$REPORT_DIR"

echo "Generating per-system coverage reports..."

# VM Coverage
echo "VM & Execution..."
sbt clean coverage testVM coverageReport
cp -r target/scala-3.3.4/scoverage-report "$REPORT_DIR/vm-coverage"

# Crypto Coverage
echo "Cryptography..."
sbt clean coverage testCrypto coverageReport
cp -r target/scala-3.3.4/scoverage-report "$REPORT_DIR/crypto-coverage"

# Network Coverage
echo "Network & P2P..."
sbt clean coverage testNetwork coverageReport
cp -r target/scala-3.3.4/scoverage-report "$REPORT_DIR/network-coverage"

# RPC Coverage
echo "JSON-RPC API..."
sbt clean coverage "testOnly -- -n RPCTest" coverageReport
cp -r target/scala-3.3.4/scoverage-report "$REPORT_DIR/rpc-coverage"

# Consensus Coverage
echo "Consensus & Mining..."
sbt clean coverage "testOnly -- -n ConsensusTest" coverageReport
cp -r target/scala-3.3.4/scoverage-report "$REPORT_DIR/consensus-coverage"

# Database Coverage
echo "Database & Storage..."
sbt clean coverage testDatabase coverageReport
cp -r target/scala-3.3.4/scoverage-report "$REPORT_DIR/database-coverage"

# Aggregate Coverage
echo "Full system aggregate..."
sbt clean coverage testAll coverageReport coverageAggregate
cp -r target/scala-3.3.4/scoverage-report "$REPORT_DIR/aggregate-coverage"

echo "✅ Coverage reports generated in $REPORT_DIR/"
echo "View reports: open $REPORT_DIR/*/index.html"
```

---

## CI/CD Integration

### GitHub Actions Example

```yaml
name: Test Coverage by System

on: [push, pull_request]

jobs:
  coverage-vm:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: olafurpg/setup-scala@v13
      - name: VM Coverage
        run: sbt clean coverage testVM coverageReport
      - uses: codecov/codecov-action@v3
        with:
          files: target/scala-3.3.4/scoverage-report/scoverage.xml
          flags: vm-tests

  coverage-crypto:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: olafurpg/setup-scala@v13
      - name: Crypto Coverage
        run: sbt clean coverage testCrypto coverageReport
      - uses: codecov/codecov-action@v3
        with:
          files: target/scala-3.3.4/scoverage-report/scoverage.xml
          flags: crypto-tests

  coverage-network:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: olafurpg/setup-scala@v13
      - name: Network Coverage
        run: sbt clean coverage testNetwork coverageReport
      - uses: codecov/codecov-action@v3
        with:
          files: target/scala-3.3.4/scoverage-report/scoverage.xml
          flags: network-tests

  coverage-aggregate:
    runs-on: ubuntu-latest
    needs: [coverage-vm, coverage-crypto, coverage-network]
    steps:
      - uses: actions/checkout@v3
      - uses: olafurpg/setup-scala@v13
      - name: Full Coverage
        run: sbt clean coverage testAll coverageReport coverageAggregate
      - uses: codecov/codecov-action@v3
        with:
          files: target/scala-3.3.4/scoverage-report/scoverage.xml
          flags: aggregate
```

---

## Isolated Logging Configuration

### logback-test.xml Setup

Create `src/test/resources/logback-test.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
  <!-- Console appender for test feedback -->
  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
    </encoder>
    <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
      <level>WARN</level>
    </filter>
  </appender>

  <!-- VM & Execution tests -->
  <appender name="VM_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>target/test-logs/vm-tests.log</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
      <fileNamePattern>target/test-logs/vm-tests-%d{yyyy-MM-dd}.log</fileNamePattern>
      <maxHistory>7</maxHistory>
    </rollingPolicy>
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
    </encoder>
  </appender>

  <!-- Network & P2P tests -->
  <appender name="NETWORK_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>target/test-logs/network-tests.log</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
      <fileNamePattern>target/test-logs/network-tests-%d{yyyy-MM-dd}.log</fileNamePattern>
      <maxHistory>7</maxHistory>
    </rollingPolicy>
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
    </encoder>
  </appender>

  <!-- RPC tests -->
  <appender name="RPC_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>target/test-logs/rpc-tests.log</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
      <fileNamePattern>target/test-logs/rpc-tests-%d{yyyy-MM-dd}.log</fileNamePattern>
      <maxHistory>7</maxHistory>
    </rollingPolicy>
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
    </encoder>
  </appender>

  <!-- Logger mappings -->
  <logger name="com.chipprbots.ethereum.vm" level="INFO" additivity="false">
    <appender-ref ref="VM_FILE"/>
    <appender-ref ref="CONSOLE"/>
  </logger>

  <logger name="com.chipprbots.ethereum.network" level="INFO" additivity="false">
    <appender-ref ref="NETWORK_FILE"/>
    <appender-ref ref="CONSOLE"/>
  </logger>

  <logger name="com.chipprbots.ethereum.jsonrpc" level="INFO" additivity="false">
    <appender-ref ref="RPC_FILE"/>
    <appender-ref ref="CONSOLE"/>
  </logger>

  <!-- Root logger -->
  <root level="WARN">
    <appender-ref ref="CONSOLE"/>
  </root>
</configuration>
```

---

## Coverage Goals by System

| System | Current | Goal | Priority |
|--------|---------|------|----------|
| VM & Execution | TBD | 90% | Critical |
| Cryptography | TBD | 95% | Critical |
| Network & P2P | TBD | 80% | High |
| JSON-RPC API | TBD | 85% | High |
| Consensus & Mining | TBD | 85% | High |
| Database & Storage | TBD | 80% | Medium |
| State Management | TBD | 85% | High |
| Synchronization | TBD | 75% | Medium |

**Action:** Run baseline coverage generation to populate "Current" column

---

## Common Coverage Patterns

### Exclude Generated Code

```scala
// In build.sbt
coverageExcludedPackages := Seq(
  "com\\.chipprbots\\.ethereum\\.extvm\\.msg.*",      // Protobuf
  "com\\.chipprbots\\.ethereum\\.utils\\.BuildInfo",  // BuildInfo
  ".*\\.protobuf\\..*"                                // All protobuf
).mkString(";")

coverageExcludedFiles := Seq(
  ".*/src_managed/.*",
  ".*/target/.*/src_managed/.*"
).mkString(";")
```

### Coverage Thresholds

```scala
// In build.sbt
coverageMinimumStmtTotal := 70
coverageFailOnMinimum := true
```

---

## Troubleshooting

### Coverage Reports Not Generating

```bash
# Clean and try again
sbt clean cleanCoverage
sbt coverage testVM coverageReport
```

### Tags Not Working

```bash
# Verify tags are imported
grep -r "import.*Tags\._" src/test/scala/

# Verify tag usage
grep -r "taggedAs" src/test/scala/
```

### Module Tests Not Running

```bash
# Check module structure
sbt projects

# Run specific module
sbt "bytes/test"
sbt "crypto/test"
sbt "rlp/test"
```

---

**Last Updated:** 2025-11-18  
**See Also:** TEST_AUDIT_SUMMARY.md for next actions and priorities
