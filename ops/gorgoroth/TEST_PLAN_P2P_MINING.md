# Gorgoroth P2P Mining Validation – Test Plan

## Objective
Validate that a two-node subset of the Gorgoroth three-node network can communicate over static peers while only node1 performs mining. Node1 must build the DAG, mine blocks, and propagate them to node2. Node3 remains shut down for the duration of the test.

## Configuration Review Summary
| Component | Current Setting (from repo) | Required Action |
|-----------|-----------------------------|-----------------|
| `docker-compose-3nodes.yml` – node1 `JAVA_OPTS` | `-Dfukuii.mining.mining-enabled=false` | Change to `true` (or override at runtime) so node1 mines. |
| `conf/node1/gorgoroth.conf` | `fukuii.mining.mining-enabled = false` | Flip to `true` before starting containers. |
| `conf/node2/gorgoroth.conf` | Mining disabled (desired) | No change. |
| `docker-compose-3nodes.yml` – node3 service | Enabled by default | Do not start node3 (`docker compose up fukuii-node1 fukuii-node2`) or scale node3 to `0`. |
| Static nodes (`conf/node*/static-nodes.json`) | Persisted from prior sync | Verify that node1 + node2 entries reflect actual enodes (if not, run `fukuii-cli sync-static-nodes`). |

> **Note:** Mining can be toggled either by editing the config file or by setting `JAVA_OPTS="... -Dfukuii.mining.mining-enabled=true"` just for node1 before launch. This plan assumes editing `conf/node1/gorgoroth.conf` (and reverting afterward) to keep the change explicit.

## Prerequisites
- Docker ≥ 20.10 and Docker Compose ≥ 2.0 (run `./ops/tools/check-docker.sh`).
- Existing Fukuii images pulled (`docker pull ghcr.io/chippr-robotics/fukuii:latest`).
- `fukuii-cli` available (`ops/tools/fukuii-cli.sh`).
- Static node files already aligned with actual enodes (otherwise run `fukuii-cli sync-static-nodes`).

## Test Procedure

### Phase 0 – Prep & Configuration
1. **Stop any running stack and clean lingering containers**
   ```bash
   cd ops/gorgoroth
   ../../ops/tools/fukuii-cli.sh stop 3nodes || true
   docker compose -f docker-compose-3nodes.yml down
   ```
2. **Persist volumes for node1 + node2, ensure node3 is removed**
   ```bash
   docker volume rm gorgoroth_fukuii-node3-data gorgoroth_fukuii-node3-logs || true
   ```
3. **Enable mining on node1 only**
   ```bash
   sed -i 's/mining-enabled = false/mining-enabled = true/' conf/node1/gorgoroth.conf
   # or set JAVA_OPTS override before compose up
   ```
4. **Verify node2 remains without mining** (no edits required).
5. **Confirm static-nodes** point to actual enodes for node1 + node2 and exclude self-references.

### Phase 1 – Launch subset (node1 & node2)
1. **Start only the desired services**
   ```bash
   docker compose -f docker-compose-3nodes.yml up -d fukuii-node1 fukuii-node2
   ```
   - Ensure node3 is not started. If using `fukuii-cli`, pass `--services node1,node2`.
2. **Allow node1 to build DAG and begin mining**
   ```bash
   docker logs -f gorgoroth-fukuii-node1 | grep -E "(Generating DAG|mining)"
   ```
   - Wait for log lines: `Generating DAG for epoch…` then `Starting miner`.

### Phase 2 – Connectivity Validation
1. **Check container health**
   ```bash
   docker ps --format 'table {{.Names}}\t{{.Status}}'
   ```
2. **Verify node1 RPC health**
   ```bash
   curl -s http://localhost:8546/health | jq
   ```
3. **Confirm node2 RPC health**
   ```bash
   curl -s http://localhost:8548/health | jq
   ```
4. **Peer count from each node** (node3 absent ⇒ expect 1 peer)
   ```bash
   # node1
   curl -s -X POST -H "Content-Type: application/json" \
     --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
     http://localhost:8546

   # node2
   curl -s -X POST -H "Content-Type: application/json" \
     --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
     http://localhost:8548
   ```
   - Expected result: `0x1` (one peer each).
5. **Inspect peer metadata** to ensure they are connected to each other (optional)
   ```bash
   curl -s -X POST -H "Content-Type: application/json" \
     --data '{"jsonrpc":"2.0","method":"admin_peers","params":[],"id":1}' \
     http://localhost:8546
   ```

### Phase 3 – Mining & Block Propagation Verification
1. **Monitor node1 mining status**
   ```bash
   curl -s -X POST -H "Content-Type: application/json" \
     --data '{"jsonrpc":"2.0","method":"eth_mining","params":[],"id":1}' \
     http://localhost:8546
   ```
   - Expect `true`.
2. **Check DAG completion logs** (node1)
   ```bash
   docker logs gorgoroth-fukuii-node1 | grep -n "DAG" | tail -5
   ```
3. **Compare block heights between nodes**
   ```bash
   for port in 8546 8548; do
     curl -s -X POST -H "Content-Type: application/json" \
       --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
       http://localhost:$port
   done
   ```
   - Acceptance: block numbers differ by ≤1 over a 30 s window.
4. **Check sync status on node2** (should report `false` once caught up)
   ```bash
   curl -s -X POST -H "Content-Type: application/json" \
     --data '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":1}' \
     http://localhost:8548
   ```
5. **Capture logs for evidence**
   ```bash
   ../../ops/tools/fukuii-cli.sh collect-logs 3nodes
   ```

### Phase 4 – Teardown & Revert
1. **Stop services**
   ```bash
   docker compose -f docker-compose-3nodes.yml stop fukuii-node1 fukuii-node2
   ```
2. **Optional cleanup** (remove data volumes if fresh state is preferred)
   ```bash
   docker compose -f docker-compose-3nodes.yml down -v
   ```
3. **Revert node1 mining flag** (if the change was committed for test only)
   ```bash
   sed -i 's/mining-enabled = true/mining-enabled = false/' conf/node1/gorgoroth.conf
   ```

## Success Criteria
- Node1 mining flag is enabled and DAG generation completes without errors.
- Node2 remains a non-mining full node but receives blocks with minimal lag.
- `net_peerCount` shows exactly one peer for each running node.
- `eth_blockNumber` parity between node1 and node2 within the test window.
- No logs showing peer disconnect loops, mining errors, or DAG generation failures.

## Follow-up / Extensions
- Re-enable node3 and confirm it syncs from the already mining node1.
- Introduce Besu/Core-Geth observers to confirm cross-client propagation.
- Automate the mining toggle via dedicated `docker compose` profile for repeatability.
