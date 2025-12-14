docker --version          # Docker 20.10+
docker compose version    # Docker Compose 2.0+
curl --version
docker volume rm gorgoroth_fukuii-node3-data || true
echo "Network mining without node3 having data..."
sleep 90
echo "Fukuii Node 3 should now be syncing from both Fukuii and Core-Geth peers..."
sleep 180
echo "=== Block Numbers After Sync ==="
echo "Starting 8-hour stability test for multi-client network..."
echo "Start time: $(date)"
echo "=== Error Summary ==="
echo "=== Fork Detection ==="
docker compose -f docker-compose-6nodes.yml restart node<N>
docker stats
docker compose -f docker-compose-6nodes.yml stop node5 node6
docker compose -f docker-compose-6nodes.yml up -d node5 node6
docker compose -f docker-compose-6nodes.yml logs | grep -i "fork\|reorg"
docker compose -f docker-compose-6nodes.yml restart
# Gorgoroth Long-Range Sync (4 Fukuii Nodes) Validation Walkthrough

**Purpose**: Repurpose the "6-node" validation to stress long-range sync on a four-node Fukuii-only topology. The focus is observing how quickly a wiped node can rejoin the network using fast sync and snap sync after falling 5k+ blocks behind.

**Time Required**: 5-7 hours (includes two re-sync cycles)  
**Difficulty**: Intermediate  
**Prerequisites**: Completed 3-node walkthrough, Docker + Compose, monitoring stack, `ops/tools/fukuii-cli.sh` available in your `$PATH`

---

## Table of Contents

1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Test Topology & Roles](#test-topology--roles)
4. [Setup](#setup)
5. [Phase 1: Baseline Network → Block 5000](#phase-1-baseline-network--block-5000)
6. [Phase 2: Checkpoint & Observability](#phase-2-checkpoint--observability)
7. [Phase 3: Node4 Fast Sync Re-bootstrap](#phase-3-node4-fast-sync-re-bootstrap)
8. [Phase 4: Node4 Snap Sync Re-bootstrap](#phase-4-node4-snap-sync-re-bootstrap)
9. [Phase 5: Long-Range Stability & Metrics](#phase-5-long-range-stability--metrics)
10. [Phase 6: Results Collection & Reporting](#phase-6-results-collection--reporting)
11. [Cleanup](#cleanup)
12. [Troubleshooting & FAQs](#troubleshooting--faqs)

---

## Overview

**Goal**: Validate that Fukuii can recover a cold node over long ranges using both *fast sync* and *snap sync* while the rest of the network keeps advancing.

This scenario intentionally diverges from the historical mixed-client test:
- ✅ 4 Fukuii nodes only (subset of the `docker-compose-6nodes.yml` stack)
- ✅ Node1 is the sole miner and long-range source of truth
- ✅ Nodes2-3 act as continuously synced observers
- ✅ Node4 is repeatedly wiped and re-synced (first with fast sync, then with snap sync)
- ✅ All measurements happen at/after block 5,000 to force long-range state download

Success criteria:
- Node4 fully syncs via fast sync after a data wipe and rejoins the head without forks
- Node4 fully syncs via snap sync after a second wipe, using state snapshots instead of full trie walking
- Nodes2/3 never fall behind by more than 2 blocks during either experiment
- No consensus divergences (matching latest block hash across all nodes)

---

## Prerequisites

1. Finish the [Gorgoroth 3-Node Walkthrough](GORGOROTH_3NODE_WALKTHROUGH.md).
2. Hardware: ≥16 GB RAM, ≥4 CPU cores, ≥40 GB free disk (snap sync temp files are larger than fast sync).
3. Software:
   ```bash
   docker --version            # >= 20.10
   docker compose version      # >= 2.0
   curl --version
   jq --version
   watch --version
   ops/tools/fukuii-cli.sh version
   ```
4. Networking: expose RPC ports 8545-8552 locally; keep firewall open for docker bridge 172.25.0.0/16.

---

## Test Topology & Roles

```
                ┌────────────┐
                │ Fukuii 1   │  (gorgoroth-fukuii-node1)
                │ Miner + Tx │
                └─────┬──────┘
                      │ Static peers only
        ┌─────────────┼─────────────┬─────────────┐
        │             │             │             │
┌───────▼───────┐┌────▼────────┐┌───▼────────┐┌───▼────────┐
│ Fukuii Node2 ││ Fukuii Node3││ Fukuii Node4││  Observers │
│ Full Sync     ││ Full Sync   ││ Resync node ││  & Metrics │
└───────────────┘└──────────────┘└────────────┘└────────────┘
```

- `node1`: inbound-only miner providing the canonical chain
- `node2` & `node3`: stay online for entire test to provide control data
- `node4`: recycled twice (fast sync pass, snap sync pass)

---

## Setup

1. **Clean slate**
   ```bash
   export GORGOROTH_CONFIG="6nodes"
   fukuii-cli clean $GORGOROTH_CONFIG   # removes prior containers/volumes
   ```

2. **Ensure node1 is allowed to mine**
   - `ops/gorgoroth/conf/node1/gorgoroth.conf` already sets `mining-enabled = true`.
   - Remove any `-Dfukuii.mining.mining-enabled=false` overrides in `docker-compose-6nodes.yml` (search the file and delete the flag for `fukuii-node1`).
   - Confirm via RPC later with `eth_mining` → `true`.

3. **Prepare node4 sync profiles**
   - Copy `ops/gorgoroth/conf/node2/gorgoroth.conf` into `conf/node4/gorgoroth.conf` if it does not exist.
   - Add the following block near the bottom so we can switch modes quickly:
     ```hocon
     fukuii {
       sync {
         do-fast-sync = false   # toggled per phase
         do-snap-sync = false   # toggled per phase
       }
     }
     ```

4. **Start only the first four services**
   ```bash
   cd ops/gorgoroth
   docker compose -f docker-compose-6nodes.yml up -d \
     fukuii-node1 fukuii-node2 fukuii-node3 fukuii-node4
   ```
   > Tip: `fukuii-cli start 6nodes` also works, but it brings up nodes5/6. If you use it, immediately stop the extra nodes with `docker compose -f docker-compose-6nodes.yml stop fukuii-node5 fukuii-node6` to keep the test deterministic.

5. **Sync static peers across the four nodes**
   ```bash
   ../tools/fukuii-cli.sh sync-static-nodes
   ```

6. **Helper scripts**
   Save these once; they are reused throughout the walkthrough.

   ```bash
   cat > /tmp/check-blocks-4node.sh <<'EOF'
   #!/bin/bash
   printf "\n=== Block & Peer Snapshot (%s) ===\n" "$(date)"
   for port in 8545 8547 8549 8551; do
     node=$(( (port-8545)/2 + 1 ))
     block=$(curl -s -X POST http://localhost:$port \
       -H "Content-Type: application/json" \
       -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' | jq -r '.result')
     peers=$(curl -s -X POST http://localhost:$((port+1)) \
       -H "Content-Type: application/json" \
       -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":2}' | jq -r '.result')
     printf "  Node%d (RPC %d): block %s, peers %s\n" "$node" "$port" "$block" "$peers"
   done
   EOF
   chmod +x /tmp/check-blocks-4node.sh

   cat > /tmp/mine-to-5000.sh <<'EOF'
   #!/bin/bash
   TARGET=${1:-5000}
   while true; do
     head=$(curl -s -X POST http://localhost:8545 \
       -H "Content-Type: application/json" \
       -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' | jq -r '.result')
     head_dec=$((head))
     if [ "$head_dec" -ge "$TARGET" ]; then
       echo "Reached target block $TARGET"
       break
     fi
     curl -s -X POST http://localhost:8545 \
       -H "Content-Type: application/json" \
       -d '{"jsonrpc":"2.0","method":"test_mineBlocks","params":[50],"id":99}' >/dev/null
     sleep 2
   done
   EOF
   chmod +x /tmp/mine-to-5000.sh
   ```

---

## Phase 1: Baseline Network → Block 5000

1. **Verify containers are healthy**
   ```bash
   docker compose -f docker-compose-6nodes.yml ps
   /tmp/check-blocks-4node.sh
   ```
   Expect `eth_mining` to be `true` only on node1:
   ```bash
   curl -s -X POST http://localhost:8545 \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","method":"eth_mining","params":[],"id":1}'
   ```

2. **Advance the chain to block 5,000**
   - Let organic mining run (≈40–60 minutes) **or** use `/tmp/mine-to-5000.sh 5000` to accelerate.
   - Keep `watch -n 15 /tmp/check-blocks-4node.sh` open to ensure nodes2-4 trail by <2 blocks.

3. **Document the checkpoint**
   ```bash
   mkdir -p /tmp/gorgoroth-long-range
   /tmp/check-blocks-4node.sh | tee /tmp/gorgoroth-long-range/block-5000.txt
   ```

✅ *Exit criteria:* `eth_blockNumber` ≥ `0x1388` (decimal 5000) on node1, and nodes2-4 report the same value ±1.

---

## Phase 2: Checkpoint & Observability

1. **Peer inventory**
   ```bash
   for port in 8545 8547 8549 8551; do
     curl -s -X POST http://localhost:$port \
       -H "Content-Type: application/json" \
       -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' | jq '.result'
   done
   ```

2. **Block hash agreement**
   ```bash
   HASH1=$(curl -s -X POST http://localhost:8545 -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest",false],"id":1}' | jq -r '.result.hash')
   HASH2=$(curl -s -X POST http://localhost:8547 -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest",false],"id":1}' | jq -r '.result.hash')
   test "$HASH1" = "$HASH2" && echo "✅ hashes match"
   ```

3. **Enable detailed logging for node4 before experiments**
   ```bash
   docker exec gorgoroth-fukuii-node4 sed -i 's/INFO/DEBUG/' /app/fukuii/conf/logback.xml || true
   docker restart gorgoroth-fukuii-node4
   ```

4. **Record baseline metrics**
   Capture CPU/memory snapshots using `docker stats --no-stream gorgoroth-fukuii-node{1..4}` and save to the `/tmp/gorgoroth-long-range` folder.

---

## Phase 3: Node4 Fast Sync Re-bootstrap

**Objective**: Prove that a wiped node can fast-sync ~5k blocks of history.

1. **Stop and purge node4**
   ```bash
   docker compose -f docker-compose-6nodes.yml stop fukuii-node4
   docker volume rm gorgoroth_fukuii-node4-data || true
   ```

2. **Flip configuration to fast sync**
   In `conf/node4/gorgoroth.conf` set:
   ```hocon
   fukuii {
     sync {
       do-fast-sync = true
       do-snap-sync = false
     }
   }
   ```

3. **Keep the rest of the network advancing**
   - Continue mining with `/tmp/mine-to-5000.sh 6500` (or let the miner run naturally).
   - Nodes2/3 serve as reference to ensure no regressions.

4. **Restart node4 and follow logs**
   ```bash
   docker compose -f docker-compose-6nodes.yml up -d fukuii-node4
   docker logs -f gorgoroth-fukuii-node4 | tee /tmp/gorgoroth-long-range/node4-fast-sync.log
   ```
   Look for:
   - `Starting fast sync at block ...`
   - `Downloaded <n> state entries`
   - `Fast sync completed, switching to full import`

5. **Measure progress**
   ```bash
   watch -n 10 'curl -s -X POST http://localhost:8551 \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":1}' | jq'
   ```

6. **Validation checklist**
   - When `eth_syncing` becomes `false`, run `/tmp/check-blocks-4node.sh`.
   - Compare latest block hash vs node1 (`eth_getBlockByNumber`).
   - Note total duration (start vs completion timestamps). Append to `/tmp/gorgoroth-long-range/fast-sync-summary.md`.

✅ *Exit criteria:* node4 catches up within 5 minutes after logs show `Fast sync completed`, and block hashes match node1.

---

## Phase 4: Node4 Snap Sync Re-bootstrap

**Objective**: Repeat the experiment using snap sync to validate state snapshot ingestion.

1. **Grow chain to ~8,000 blocks**
   ```bash
   /tmp/mine-to-5000.sh 8000
   /tmp/check-blocks-4node.sh | tee /tmp/gorgoroth-long-range/block-8000.txt
   ```

2. **Stop, purge, and toggle sync mode**
   ```bash
   docker compose -f docker-compose-6nodes.yml stop fukuii-node4
   docker volume rm gorgoroth_fukuii-node4-data || true
   ```
   Update `conf/node4/gorgoroth.conf`:
   ```hocon
   fukuii {
     sync {
       do-fast-sync = false
       do-snap-sync = true
     }
   }
   ```

3. **Restart node4 and tail logs**
   ```bash
   docker compose -f docker-compose-6nodes.yml up -d fukuii-node4
   docker logs -f gorgoroth-fukuii-node4 | tee /tmp/gorgoroth-long-range/node4-snap-sync.log
   ```
   Watch for `Starting snap sync` followed by snapshot chunk imports.

4. **Monitor sync gap**
   ```bash
   watch -n 15 '/tmp/check-blocks-4node.sh'
   ```
   Snap sync should jump directly to the head once snapshots are applied. Expected runtime: <15 minutes for 3k blocks of state.

5. **Post-sync validation**
   - Confirm `eth_syncing` returns `false`.
   - Compare `latest` block hash on ports 8545 and 8551.
   - Record duration + any errors in `/tmp/gorgoroth-long-range/snap-sync-summary.md`.

✅ *Exit criteria:* node4 rejoins chain tip with matching block hash and no `snap sync failed` log entries.

---

## Phase 5: Long-Range Stability & Metrics

Even after both re-syncs, leave the cluster running for ≥60 minutes to ensure the freshly synced node stays healthy.

1. **Continuous monitor**
   ```bash
   cat > /tmp/long-range-monitor.sh <<'EOF'
   #!/bin/bash
   LOG=/tmp/gorgoroth-long-range/stability.log
   mkdir -p $(dirname $LOG)
   while true; do
     /tmp/check-blocks-4node.sh | tee -a "$LOG"
     docker stats --no-stream gorgoroth-fukuii-node{1..4} | tee -a "$LOG"
     sleep 300
   done
   EOF
   chmod +x /tmp/long-range-monitor.sh
   nohup /tmp/long-range-monitor.sh >/dev/null 2>&1 &
   ```

2. **Peer churn audit**
   ```bash
   for idx in 1 2 3 4; do
     docker exec gorgoroth-fukuii-node$idx cat /app/data/static-nodes.json
   done
   ```

3. **Fork detection**
   ```bash
   HASHES=$(for port in 8545 8547 8549 8551; do
     curl -s -X POST http://localhost:$port -H "Content-Type: application/json" \
       -d '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest",false],"id":1}' | jq -r '.result.hash'
   done)
   echo "$HASHES" | sort -u
   ```
   Expect a single unique hash.

4. **Capture metrics**
   - Note fastest/slowest sync durations.
   - Record CPU/RAM averages from `docker stats` output.
   - Save RPC latency snapshots (e.g., `/tmp/gorgoroth-long-range/rpc-latency.json`).

Stop the monitor with `pkill -f long-range-monitor.sh` once satisfied.

---

## Phase 6: Results Collection & Reporting

1. **Gather logs & configs**
   ```bash
   cd ops/gorgoroth
   ./collect-logs.sh 6nodes ../logs-long-range-$(date +%Y%m%d-%H%M%S)
   ```

2. **Write a summary (template)**
   ```bash
   cat > /tmp/gorgoroth-long-range/SUMMARY.md <<'EOF'
   # Gorgoroth Long-Range Sync Report

   | Item | Fast Sync | Snap Sync |
   |------|-----------|-----------|
   | Chain height when node4 wiped | <fill> | <fill> |
   | Duration to reach head | <fill> | <fill> |
   | Final block hash | <fill> | <fill> |
   | Errors / warnings | <fill> | <fill> |

   ## Observations
   - ...

   ## Follow-ups
   - ...
   EOF
   ```

3. **Share artifacts**
   - Attach `node4-fast-sync.log`, `node4-snap-sync.log`, and `SUMMARY.md` to the project issue tracker.
   - Include Grafana screenshots or `docker stats` output if available.

---

## Cleanup

```bash
cd ops/gorgoroth
docker compose -f docker-compose-6nodes.yml down -v
pkill -f long-range-monitor.sh || true
rm -rf /tmp/gorgoroth-long-range
```

If you need the environment later, keep the logs directory and the updated `conf/node4/gorgoroth.conf` tweaks under version control (or stash them elsewhere) before cleaning.

---

## Troubleshooting & FAQs

### Node1 Stops Mining
- Check `docker logs gorgoroth-fukuii-node1 | grep -i miner`.
- Reapply the `JAVA_OPTS` change or run `curl ... eth_mining` to confirm.
- Use `test_mineBlocks` as a backstop to keep the chain moving.

### Node4 Fast Sync Stalls at `Downloading state entries`
- Ensure `do-fast-sync = true` and `do-snap-sync = false`.
- Verify node4 still has peers with `/tmp/check-blocks-4node.sh`.
- Restart node4; fast sync resumes from the last completed pivot.

### Snap Sync Complains About Missing Snapshots
- Keep node1 online—snap sync requires a full node serving snapshots.
- Run `curl -s -X POST ... eth_getBlockByNumber` against node1 to make sure it is ≥ block 7k.
- If snapshots are still unavailable, enable snapshot generation by adding `fukuii.sync.snap-sync-server-enabled=true` in node1's config and restarting.

### Peers Drop Below 2
- Re-run `../tools/fukuii-cli.sh sync-static-nodes`.
- Check for duplicate `static-nodes.json` permissions (should be readable by container user).

### `eth_syncing` Never Returns False
- Inspect `node4` logs for `chain reorganized` spam—this may signal missing peers.
- Confirm the docker host clock is in sync (`timedatectl`). Clock drift can cause snapshot validation failures.

---

## Next Steps

1. Rerun the experiment with `docker-compose-fukuii-besu.yml` to see how an external client behaves as a sync source.
2. Capture metrics in Grafana/Prometheus for future regression testing.
3. Automate the wipe/rejoin loop inside `ops/gorgoroth/test-scripts` to run nightly.

---

**Questions?** Open an issue tagged `validation:gorgoroth` or reach out in the #fukuii-validation Slack channel.
