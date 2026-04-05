# Fukuii Hive Client Adapter

Ethereum [Hive](https://github.com/ethereum/hive) test framework adapter for Fukuii.

## Setup

### Prerequisites
- Go 1.21+ (for building hive)
- Docker
- Fukuii Docker image (`chipprbots/fukuii:latest`)

### Clone and Build Hive

```bash
git clone https://github.com/ethereum/hive
cd hive
go build .
go build ./cmd/hiveview
```

### Install Fukuii Client

Copy the adapter files into hive's clients directory:

```bash
cp -r /path/to/fukuii/hive/fukuii /path/to/hive/clients/fukuii
```

Or symlink:

```bash
ln -s /path/to/fukuii/hive/fukuii /path/to/hive/clients/fukuii
```

## Running Tests

### Engine API Tests (Primary Target)

```bash
# All engine API tests
./hive --sim ethereum/engine --client fukuii --sim.parallelism 1 --client.checktimelimit 5m

# Specific test
./hive --sim ethereum/engine --client fukuii --sim.limit "ForkchoiceUpdated"

# With verbose logging
./hive --sim ethereum/engine --client fukuii --sim.loglevel 5 --docker.output
```

### RPC Compatibility Tests

```bash
./hive --sim ethereum/rpc-compat --client fukuii
```

### EVM Consensus Tests

```bash
./hive --sim ethereum/consensus --client fukuii
```

### Smoke Tests

```bash
./hive --sim smoke --client fukuii
```

### View Results

```bash
./hiveview --serve --logdir ./workspace/logs
# Open http://127.0.0.1:8080
```

## Adapter Architecture

```
hive/fukuii/
├── Dockerfile       # Builds from chipprbots/fukuii:latest
├── fukuii.sh        # Entry point — translates HIVE_* env vars to fukuii config
├── mapper.jq        # Converts geth-format genesis to fukuii format
├── enode.sh         # Returns enode:// URL for P2P discovery
└── hive.yaml        # Declares roles: eth1, eth1_engine
```

### Configuration Flow

1. Hive uploads `/genesis.json` (geth format) and sets `HIVE_FORK_*` env vars
2. `fukuii.sh` runs `mapper.jq` to convert genesis to fukuii format
3. `fukuii.sh` maps env vars to `-Dfukuii.*` JVM system properties
4. If `HIVE_TERMINAL_TOTAL_DIFFICULTY` is set, enables Engine API on port 8551
5. Starts Fukuii JAR with `test` network config
6. Hive polls TCP port 8545 until ready

### Supported Test Suites

| Suite | Status | Notes |
|-------|--------|-------|
| `ethereum/engine` | Target | Engine API V1-V4 |
| `ethereum/rpc-compat` | Target | Standard JSON-RPC |
| `ethereum/consensus` | Target | EVM correctness |
| `smoke` | Target | Basic sanity |
| `devp2p` | Future | Wire protocol |
| `ethereum/sync` | Future | Cross-client sync |
