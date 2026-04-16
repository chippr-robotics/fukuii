# Gorgoroth 3-Node Snap Sync Validation Plan

**Objective:** Prove that a late-joining Fukuii node can snap-sync to an in-flight 3-node Gorgoroth network while only node1 is mining. The plan enforces DAG creation, sustained mining, follower validation, and snap-sync log capture using the `ops/tools/fukuii-cli.sh` helper.

## Test Matrix

| Phase | Goal | Primary Commands | Acceptance Criteria |
| --- | --- | --- | --- |
| 0 | Preparation & snap-sync enablement | `fukuii-cli clean 3nodes`, config edits, `docker volume rm` | All three node configs have `do-snap-sync = true`; previous data removed |
| 1 | Bring up node1 & node2 only | `fukuii-cli start 3nodes`, `docker stop gorgoroth-fukuii-node3`, `fukuii-cli status` | node1/2 running, node3 stopped |
| 2 | DAG build + mining / follower health | `fukuii-cli logs 3nodes`, `curl net_peerCount`, `curl eth_blockNumber` | node1 shows DAG generation + mining >0 blocks; node2 reports peers=1 and follows within 5–10 min |
| 3 | Mine until ≥ 10,000 blocks | looped `curl eth_blockNumber` | node1 best block ≥ 10,000 prior to adding node3 |
| 4 | Snap-sync node3 & collect artifacts | `docker start gorgoroth-fukuii-node3`, `fukuii-cli logs 3nodes`, `fukuii-cli collect-logs` | node3 logs show `snap-sync` progress and reaches same head as node1/2 |

## Detailed Procedure

### Phase 0 – Preparation
1. Ensure Docker Desktop / Engine (20.10+) and `ops/tools/fukuii-cli.sh` are available.
2. Clean any prior runs: `fukuii-cli clean 3nodes` (answer `yes`).
3. Remove lingering volumes used for node3 so it must snap-sync later: `docker volume rm gorgoroth_fukuii-node3-data gorgoroth_fukuii-node3-logs || true`.
4. Update the following files to set `do-snap-sync = true`:
   - `ops/gorgoroth/conf/app-gorgoroth-override.conf`
   - `ops/gorgoroth/conf/base-gorgoroth.conf`
   - `ops/gorgoroth/conf/node1/gorgoroth.conf`
5. Verify every config (including node2 & node3, via inherited `base-gorgoroth.conf`) now reports snap-sync enabled.

### Phase 1 – Controlled startup (node1 + node2)
1. Start the 3-node stack: `fukuii-cli start 3nodes`.
2. Immediately stop node3 so it stays offline until Phase 4: `docker stop gorgoroth-fukuii-node3`.
3. Wait 30s, then confirm state: `fukuii-cli status 3nodes` (node1/2 = "Up", node3 = "Exited").
4. Run `fukuii-cli sync-static-nodes` to exchange peer info (node3 will stay stopped afterward).

### Phase 2 – DAG + Miner/Follower validation
1. Tail logs: `fukuii-cli logs 3nodes` (Ctrl+C after spotting `Generating DAG` then `Mining enabled`).
2. After 5 min, validate networking and follower health:
   ```bash
   for port in 8546 8548; do
     curl -s -X POST http://localhost:$port -H 'Content-Type: application/json' \
       -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' | jq -r '.result'
   done
   ```
   Expect `0x1` peers for both.
3. Confirm block alignment:
   ```bash
   for port in 8546 8548; do
     curl -s -X POST http://localhost:$port -H 'Content-Type: application/json' \
       -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' | jq -r '.result'
   done
   ```
   Node2 should trail by ≤2 blocks after 5–10 minutes.

### Phase 3 – Mine to 10,000 blocks
1. Leave network running; use a helper loop to watch head height:
   ```bash
   target=10000
   while true; do
     hex=$(curl -s -X POST http://localhost:8546 -H 'Content-Type: application/json' \
       -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' | jq -r '.result')
     height=$((16#${hex#0x}))
     echo "node1 height=$height"
     if [ "$height" -ge "$target" ]; then break; fi
     sleep 30
   done
   ```
2. Capture the last reported height + timestamp for inclusion in the final summary.

### Phase 4 – Snap-sync node3 and capture evidence
1. Ensure node3 volumes are fresh (already removed in Phase 0). Start the container: `docker start gorgoroth-fukuii-node3`.
2. Wait 30s, then check status and peers: `fukuii-cli status 3nodes`.
3. Follow logs focusing on node3 to spot `snap-sync` entries and `Imported new Chain segment`:
   ```bash
   docker logs -f gorgoroth-fukuii-node3 | tee /tmp/node3-snapsync.log
   ```
4. After 5 minutes, capture RPC health:
   ```bash
   curl -s -X POST http://localhost:8550 -H 'Content-Type: application/json' \
     -d '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":1}' | jq
   ```
   Expect `snapSyncState` fields to appear initially, then `false` once caught up.
5. When node3 catches the same head (block difference ≤1), run `fukuii-cli collect-logs 3nodes ./snap-sync-artifacts` to archive logs for all nodes.
6. Save key RPC outputs, block heights, and timestamps into a short results note for attachment.

### Phase 5 – Cleanup (optional)
- Stop the environment: `fukuii-cli stop 3nodes`.
- Retain `./snap-sync-artifacts` plus `/tmp/node3-snapsync.log` as validation evidence.

## Deliverables
- `docs/validation/GORGOROTH_SNAP_SYNC_VALIDATION.md` (this plan)
- Runtime console captures showing DAG generation, node2 following, node1 ≥10k blocks, and node3 snap-syncing.
- Log bundle from `fukuii-cli collect-logs` + targeted `node3-snapsync.log`.
- Final summary with peer counts, block heights, and confirmation that all nodes ran with snap sync enabled.
