# Gorgoroth RLPx Compression Validation Plan

**Document Version**: 1.0  
**Date**: December 9, 2025  
**Status**: Draft (repeatable runbook)  
**Target Build**: `main` branch @ latest commit  
**Scope**: Gorgoroth 3-node Fukuii network, RLPx compression/decompression instrumentation

## Overview

This plan defines a repeatable workflow for validating recent RLPx compression/decompression changes using the Gorgoroth 3-node topology. The test enforces a single mining leader (node1) while keeping node2 and node3 as passive peers to isolate block header propagation and protocol message handling. Results feed into compression diagnostics, log harvesting, and future harness automation.

## Objectives

1. **Generate Deterministic Enodes** – Start all three nodes so that fresh node keys and enode URLs can be captured and synchronized via tooling.
2. **Apply Targeted Mining Mix** – Use the new `miner_start`/`miner_stop` RPC endpoints to keep node1 mining while node2/node3 remain passive, avoiding config flips or restarts.
3. **Exercise RLPx Stack** – Let node1 mine at least 30 blocks while peers stay synced via static connections.
4. **Collect Evidence** – Capture logs and docker inspection data for post-run parsing.
5. **Detect Regressions** – Scan logs for RLPx compression errors, decompression failures, or missing block headers on passive nodes.

## Success Criteria

- ✅ `net_connectToPeer` succeeds for all three pairings (node1↔node2, node1↔node3, node2↔node3) without restarting containers.
- ✅ Node1 reports `eth_mining=true`; node2 and node3 return `false`.
- ✅ All three nodes maintain ≥2 peers (i.e., fully connected triangle) during the run.
- ✅ Block height on node2/node3 trails node1 by ≤1 block at steady state.
- ✅ No log entries matching `compression error`, `decompression failed`, or `Snappy` failures.
- ✅ RLPx header propagation confirmed via consistent `eth_getBlockByNumber("latest")` hashes across nodes.
- ✅ Log bundle archived in `./logs/rplx-<timestamp>` directory with README summary.

## Prerequisites

- Docker ≥ 20.10 and Docker Compose v2
- `ops/tools/fukuii-cli.sh` available (either via relative path or installed as `fukuii-cli`)
- ≥8 GB RAM, 10 GB free disk space
- `jq`, `rg` (ripgrep), and `watch` utilities for analysis (optional but recommended)
- Baseline images pulled:
  ```bash
  docker pull ghcr.io/chippr-robotics/fukuii:latest
  ```

## Configuration Checklist

1. **Mining Controls** – Leave `fukuii.mining.mining-enabled` at repo defaults; Phase 2 relies on `miner_start` / `miner_stop` RPCs to toggle roles at runtime without editing configs.
2. **Clean Volumes (Optional)** – If prior state is undesirable:
   ```bash
   cd ops/gorgoroth
   ../../ops/tools/fukuii-cli.sh clean 3nodes
   ```
3. **Environment Variables** – Export helper variables for later steps:
   ```bash
   export FUKUII_CLI="$(pwd)/../../ops/tools/fukuii-cli.sh"
   export RPLX_LOG_DIR="$(pwd)/logs/rplx-$(date +%Y%m%d-%H%M%S)"
   ```

## Test Procedure

### Phase 1 – Bring Up 3-Node Topology

1. **Start Network (Generates Enodes)**
   ```bash
   cd ops/gorgoroth
   $FUKUII_CLI start 3nodes
   sleep 45
   ```
2. **Verify Containers**
   ```bash
   $FUKUII_CLI status 3nodes
   docker ps --filter name=gorgoroth-fukuii
   ```
3. **Wire Up Static Triangle via RPC (No Restart)**
    1. Capture fresh enodes from each node:
         ```bash
         for port in 8545 8547 8549; do
            curl -s -X POST -H "Content-Type: application/json" \
               --data '{"jsonrpc":"2.0","method":"admin_nodeInfo","params":[],"id":1}' \
               http://localhost:$port | jq -r '.result.enode' | \
               tee "enode-$port.txt"
         done
         ```
    2. Push the pairings through the new `net_connectToPeer` endpoint so every node dials every other node (triangle):
         ```bash
         while read -r ENODE; do
            for port in 8545 8547 8549; do
               curl -s -X POST -H "Content-Type: application/json" \
                  --data '{"jsonrpc":"2.0","method":"net_connectToPeer","params":["'"$ENODE"'"],"id":42}' \
                  http://localhost:$port | jq -r '.result'
            done
         done < <(cat enode-*.txt)
         ```
    3. Validate the mesh without bouncing containers:
         ```bash
            for port in 8545 8547 8549; do
               curl -s -X POST -H "Content-Type: application/json" \
                  --data '{"jsonrpc":"2.0","method":"net_listPeers","params":[],"id":2}' \
                  http://localhost:$port | jq '.result.peers | length'
            done
            ```
            - Expect `2` peers per node (fully connected triangle).

### Phase 2 – Validate Mining Roles

1. **Set Mining Roles via RPC (No Restart Needed)**
    ```bash
    # Start mining on node1 (8545)
    curl -s -X POST -H "Content-Type: application/json" \
       --data '{"jsonrpc":"2.0","method":"miner_start","params":[],"id":1}' \
       http://localhost:8545 | jq

    # Ensure node2/node3 stay passive
    for port in 8547 8549; do
       curl -s -X POST -H "Content-Type: application/json" \
          --data '{"jsonrpc":"2.0","method":"miner_stop","params":[],"id":1}' \
          http://localhost:$port | jq '.result'
    done
    ```
    - Optional: run `miner_getStatus` on each port for a one-shot status view.
2. **Check Mining Status**
   ```bash
   for port in 8545 8547 8549; do
     curl -s -X POST -H "Content-Type: application/json" \
       --data '{"jsonrpc":"2.0","method":"eth_mining","params":[],"id":1}' \
       http://localhost:$port | jq --arg port "$port" '.result as $r | {port: $port, mining: $r}'
   done
   ```
   - Expected: `8545` → `true`, `8547/8549` → `false`.
3. **Confirm Peering**
   ```bash
   for port in 8545 8547 8549; do
     curl -s -X POST -H "Content-Type: application/json" \
       --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
       http://localhost:$port | jq --arg port "$port" '.result'
   done
   ```
   - Convert hex to decimal; each should read `0x2` (two peers).

### Phase 3 – Produce Blocks & Capture Telemetry

1. **Allow Mining Window** – Let node1 run for ≥10 minutes (≈40 blocks). Optional watcher:
   ```bash
   watch -n 10 'curl -s -X POST -H "Content-Type: application/json" \
     --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
     http://localhost:8545 | jq'
   ```
2. **Verify Propagation**
   ```bash
   cat > check-blocks.sh <<'EOF'
   #!/bin/bash
   for port in 8545 8547 8549; do
     BLOCK=$(curl -s -X POST -H "Content-Type: application/json" \
       --data '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest",false],"id":1}' \
       http://localhost:$port | jq -r '.result.number // "0x0"')
     HASH=$(curl -s -X POST -H "Content-Type: application/json" \
       --data '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest",false],"id":1}' \
       http://localhost:$port | jq -r '.result.hash // "0x0"')
     printf "Port %s → Block %d, Hash %s\n" "$port" "$((16#${BLOCK:2:-0}))" "$HASH"
   done
   EOF
   chmod +x check-blocks.sh
   ./check-blocks.sh
   ```
   - Expect identical hashes across ports.

### Phase 4 – Collect Logs

```bash
mkdir -p "$RPLX_LOG_DIR"
$FUKUII_CLI collect-logs 3nodes "$RPLX_LOG_DIR"
```

Artifacts include container logs, Docker inspect output, compose config, and a summary README for traceability.

## Log Analysis Checklist

| Check | Command | Expected |
|-------|---------|----------|
| RLPx compression errors | `rg -n "compression|decompress|snappy" "$RPLX_LOG_DIR"` | No matches containing `error`, `failed`, `invalid`, `Snappy decompression failed` |
| Block header propagation | `rg -n "Imported new chain segment" "$RPLX_LOG_DIR"` | Node2 & Node3 show headers shortly after node1 |
| Peer churn | `rg -n "Disconnected" "$RPLX_LOG_DIR"` | Minimal churn; no disconnects tied to compression |
| Message monitor | `ops/gorgoroth/test-scripts/monitor-decompression.sh gorgoroth-fukuii-node1` | No `FAILED` lines |
| Mining role | `rg -n "miner" "$RPLX_LOG_DIR"` | Only node1 logs contain `Starting miner` |

### Harness Integration

- **Watchdog Script**: Wrap steps 2–4 inside `ops/gorgoroth/test-scripts` by cloning the pattern from `monitor-decompression.sh`. Trigger via CI job to gate RLPx changes.
- **Metrics Export**: Feed Docker stats into Prometheus by enabling the Grafana stack under `ops/gorgoroth/grafana` for longer experiments.
- **JUnit Adapter**: Convert log analysis results into XML using `tests/tools/log_parser.py` (if available) so CI dashboards can display pass/fail.

## Optional Manual Spot Checks

1. **Compression handshake** – Search for `"rlpx"`, `"snappy"`, `"compression"` inside node logs.
2. **Header timing** – Compare timestamps of `"Sealing new block"` (node1) vs `"Imported new chain segment"` (node2/3) to ensure propagation <2s.
3. **RPC verification** – Use `net_listPeers` (or `admin_nodeInfo`) on each node to confirm all peers remain connected.


## Reporting Template

After each run, append a row to `docs/testing/GORGOROTH_VALIDATION_STATUS.md` with:

| Date | Commit | Operator | Blocks Mined | RLPx Errors | Propagation Lag | Notes |
|------|--------|----------|--------------|-------------|-----------------|-------|
| 2025-12-09 | `<short-sha>` | `<name>` | `~40` | `None` | `<≤2s>` | `Node1-only mining setup` |

## Cleanup

```bash
$FUKUII_CLI stop 3nodes
# Optional
$FUKUII_CLI clean 3nodes
```

## Next Steps

- Automate the checklist via a dedicated script that toggles mining flags and parses logs automatically.
- Integrate the RLPx validation into `test-launcher-integration.sh` once stable.
- Consider extending the test to mixed-client scenarios (Core-Geth/Besu) for cross-implementation coverage.
