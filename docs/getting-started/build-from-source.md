# Build from Source

This guide covers building Fukuii from source for development or custom builds.

## Prerequisites

### Required Software

| Software | Version | Purpose |
|----------|---------|---------|
| JDK | 21 | Runtime and build |
| sbt | 1.10.7+ | Scala build tool |
| Git | Any | Version control |

### Install JDK 21

=== "Ubuntu/Debian"

    ```bash
    sudo apt-get update
    sudo apt-get install openjdk-21-jdk
    ```

=== "macOS"

    ```bash
    brew install openjdk@21
    ```

=== "Windows"

    Download from [Adoptium](https://adoptium.net/) and install.

Verify installation:

```bash
java -version
# Should show: openjdk version "21.x.x"
```

### Install sbt

=== "Ubuntu/Debian"

    ```bash
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | sudo tee /etc/apt/sources.list.d/sbt.list
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | sudo apt-key add
    sudo apt-get update
    sudo apt-get install sbt
    ```

=== "macOS"

    ```bash
    brew install sbt
    ```

=== "Windows"

    Download the MSI installer from [scala-sbt.org](https://www.scala-sbt.org/download.html)

## Clone the Repository

```bash
git clone https://github.com/chippr-robotics/fukuii.git
cd fukuii
```

## Initialize Submodules

Fukuii uses git submodules for some dependencies:

```bash
git submodule update --init --recursive
```

## Build Commands

### Compile

```bash
# Compile all modules
sbt compile-all

# Or compile individual modules
sbt bytes/compile
sbt crypto/compile
sbt rlp/compile
sbt compile  # Main module
```

### Build Distribution

```bash
# Create distribution ZIP
sbt dist
```

The distribution is created at: `target/universal/fukuii-<version>.zip`

### Build Assembly JAR

```bash
# Create fat JAR with all dependencies
sbt assembly
```

The JAR is created at: `target/scala-3.3.4/fukuii-assembly-<version>.jar`

## Run from Source

### Using sbt

```bash
# Run ETC mainnet node
sbt "run etc"

# Run Mordor testnet node
sbt "run mordor"
```

### Using Distribution

```bash
# Extract distribution
cd target/universal
unzip fukuii-*.zip
cd fukuii-*/

# Make launcher executable
chmod +x bin/fukuii

# Run
./bin/fukuii etc
```

## Development Commands

### Code Quality

```bash
# Format all code
sbt formatAll

# Check formatting (what CI runs)
sbt formatCheck

# Run static analysis
sbt runScapegoat
```

### Testing

```bash
# Run all tests
sbt testAll

# Run with coverage
sbt testCoverage

# Run specific module tests
sbt bytes/test
sbt crypto/test
sbt rlp/test

# Run integration tests
sbt "IntegrationTest / test"
```

### Prepare for PR

```bash
# Format, lint, and test
sbt pp
```

## Configuration

### JVM Options

Create or edit `.jvmopts` in the project root:

```
-Xms1g
-Xmx4g
-XX:+UseG1GC
```

### Build Settings

The main build configuration is in `build.sbt`. Module dependencies are in `project/Dependencies.scala`.

## Troubleshooting

### Out of Memory

Increase heap size in `.jvmopts`:

```
-Xmx8g
```

### Compilation Errors

Ensure you're using JDK 21:

```bash
java -version
```

### Submodule Issues

Re-initialize submodules:

```bash
git submodule deinit -f .
git submodule update --init --recursive
```

### sbt Resolution Errors

Clear caches:

```bash
rm -rf ~/.ivy2/cache
rm -rf ~/.sbt/1.0/plugins/target
sbt clean compile
```

## Next Steps

- [Contributing Guide](../development/contributing.md)
- [Repository Structure](../development/REPOSITORY_STRUCTURE.md)
- [Testing Guide](../testing/README.md)
