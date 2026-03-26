# First Successful Mordor SNAP Sync

**Date:** 2026-03-26
**Operator:** Chris Mercer
**Client:** Fukuii v0.1.240 (`march-onward` branch, commit `6220ce58b`)
**Network:** Mordor testnet (chain ID 63, network ID 7)
**Hardware:** Intel NUC10i7FNH (i7-10710U 6C/12T, 32GB RAM, NVMe SSD)

---

## Result

**Genesis to chain head in ~35 minutes via SNAP sync.**

| Metric | Value |
|--------|-------|
| SNAP sync start | 13:43:06 UTC |
| SNAP sync complete | 14:17:46 UTC |
| Chain head reached | 14:18:16 UTC |
| Total time | ~35 minutes |
| Final SNAP pivot block | 15,821,493 |
| Chain head at completion | 15,821,589 |
| Pivot refreshes | 6 (in-place, zero restarts) |
| Accounts downloaded | 2,628,940 |
| Contract accounts | 148,486 |
| Unique bytecodes | 57,337 |
| Peak throughput | 6,786 accounts/sec |
| Sustained throughput | ~2,400 accounts/sec |
| JVM heap | 4 GB (`-Xmx4g`) |

---

## Multi-Client Peer Setup

SNAP sync requires a peer that speaks `snap/1` protocol and serves state. On Mordor, no public peers serve SNAP — so we run a local Besu instance as the SNAP server.

### Architecture

```
┌────────────────┐     snap/1     ┌────────────────┐     eth/68     ┌────────────────┐
│   Fukuii       │ ◄──────────── │   Besu         │ ◄──────────── │   core-geth    │
│   :30305       │               │   :30304       │               │   :30303       │
│   SNAP client  │               │   SNAP server  │               │   Full node    │
│   RPC :8551    │               │   RPC :8548    │               │   RPC :8545    │
└────────────────┘               └────────────────┘               └────────────────┘
        │                                │                                │
        └────────────────────────────────┴────────────────────────────────┘
                              Mordor testnet (chain 63)
```

### Step 1: Sync core-geth to Mordor Head

core-geth syncs natively from public Mordor peers. This is the foundation — it provides blocks to Besu.

```bash
# core-geth on Mordor (default ports)
./build/bin/geth --mordor \
  --http --http.addr 0.0.0.0 --http.port 8545 \
  --http.api admin,eth,net,web3 \
  --port 30303 \
  --datadir /path/to/coregeth-mordor
```

Wait until core-geth reaches chain head (`eth.syncing` returns `false`).

### Step 2: Start Besu as SNAP Server

Besu SNAP-syncs from core-geth (fast), then serves SNAP to Fukuii.

**Critical flags:**
- `--data-storage-format=BONSAI` — Required for SNAP serving
- `--sync-mode=SNAP` — Besu itself SNAP-syncs from core-geth (avoids the FULL sync Bonsai stall at block ~558K)
- `--snapsync-server-enabled` — Serve SNAP protocol to Fukuii
- `--sync-min-peers=1` — Don't wait for multiple peers (only core-geth is available)

```bash
# Get core-geth's enode
COREGETH_ENODE=$(curl -sf -X POST -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","method":"admin_nodeInfo","params":[],"id":1}' \
  http://localhost:8545 | jq -r '.result.enode' | sed 's/@[^:]*:/@127.0.0.1:/')

# Start Besu
besu \
  --genesis-file=mordor-genesis.json \
  --network-id=7 \
  --data-path=/path/to/besu-mordor \
  --data-storage-format=BONSAI \
  --sync-mode=SNAP \
  --sync-min-peers=1 \
  --snapsync-server-enabled \
  --rpc-http-enabled \
  --rpc-http-host=0.0.0.0 \
  --rpc-http-port=8548 \
  --rpc-http-api=ADMIN,ETH,NET,WEB3 \
  --p2p-port=30304 \
  --bootnodes="$COREGETH_ENODE" \
  --logging=INFO
```

Wait until Besu reaches chain head. Besu's own SNAP sync from core-geth takes ~10-15 minutes on Mordor.

### Step 3: Configure Fukuii Static Peers

Create `static-nodes.json` in Fukuii's datadir with both local peers:

```bash
# Get Besu enode
BESU_ENODE=$(curl -sf -X POST -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","method":"admin_nodeInfo","params":[],"id":1}' \
  http://localhost:8548 | jq -r '.result.enode' | sed 's/@[^:]*:/@127.0.0.1:/')

# Get core-geth enode
COREGETH_ENODE=$(curl -sf -X POST -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","method":"admin_nodeInfo","params":[],"id":1}' \
  http://localhost:8545 | jq -r '.result.enode' | sed 's/@[^:]*:/@127.0.0.1:/')

# Write static-nodes.json
DATADIR=/path/to/fukuii-mordor
mkdir -p "$DATADIR"
cat > "$DATADIR/static-nodes.json" <<EOF
[
  "$BESU_ENODE",
  "$COREGETH_ENODE"
]
EOF
```

### Step 4: Launch Fukuii with SNAP Sync

```bash
java -Xmx4g \
  -Dfukuii.datadir=/path/to/fukuii-mordor \
  -Dfukuii.network=mordor \
  -Dfukuii.network.rpc.http.interface=0.0.0.0 \
  -Dfukuii.network.rpc.http.port=8553 \
  -Dfukuii.network.server-address.port=30305 \
  -Dfukuii.network.discovery.port=30305 \
  -Dfukuii.sync.do-fast-sync=true \
  -Dfukuii.sync.do-snap-sync=true \
  -jar target/scala-3.3.4/fukuii-assembly-0.1.240.jar mordor
```

**IMPORTANT:** Set `discovery.port=30305` to avoid UDP port conflict with core-geth on 30303.

### Port Summary

| Client | HTTP RPC | P2P | Discovery |
|--------|----------|-----|-----------|
| core-geth | 8545 | 30303 | 30303 |
| Besu | 8548 | 30304 | 30304 |
| Fukuii | 8553 | 30305 | 30305 |

---

## Phase-by-Phase Timeline

### Phase 0: Bootstrap (13:43:06)

Fukuii starts, discovers peers, and probes for `snap/1` capability.

```
13:43:06  SNAP sync mode initialized
13:43:06  Peer discovery — 3 peers visible:
          - 24.199.107.164:30303 (public Mordor bootnode)
          - 127.0.0.1:30303 (core-geth, eth/68 only)
          - 127.0.0.1:30304 (Besu, eth/68 + snap/1)
13:43:06  SNAP capability probe — 2 of 3 peers support snap/1
13:43:06  Initial pivot block: 15,821,349 (network best 15,821,413 - offset 64)
```

The 64-block pivot offset is configurable (`snap-sync.pivot-block-offset`). It provides a safety margin — the pivot state root must be stable enough that peers can serve it.

### Phase 1: Account Range Download (13:43:06 → 14:01:44, ~18.5 min)

16 concurrent workers partition the 256-bit address space and download account ranges in parallel.

```
13:43:06  Account range download started (16 workers)
          Initial response size: 524KB per request
          Max in-flight per peer: 5
13:44:00  ~1,200 accounts/sec (ramping up)
13:48:00  ~2,400 accounts/sec (sustained)
13:52:00  Peak: 6,786 accounts/sec
14:01:44  Account range complete
          Total: 2,628,940 accounts
          Contract accounts: 148,486
          Unique codeHashes: 57,337
```

During this phase, 6 in-place pivot refreshes occurred as the chain advanced:

| Refresh # | Old Pivot | New Pivot | Time |
|-----------|-----------|-----------|------|
| 1 | 15,821,349 | 15,821,355 | ~13:45 |
| 2 | 15,821,355 | 15,821,365 | ~13:48 |
| 3 | 15,821,365 | 15,821,387 | ~13:52 |
| 4 | 15,821,387 | 15,821,411 | ~13:56 |
| 5 | 15,821,411 | 15,821,436 | ~14:01 |
| 6 | 15,821,436 | 15,821,493 | ~14:11 |

Each refresh updates the coordinator's state root in-place (`PivotRefreshed` message) without stopping or restarting the download. Account range progress is preserved across refreshes.

### Phase 2: Bytecode Download (14:01:44 → 14:17:46, ~16 min)

Download contract bytecodes for all 57,337 unique `codeHash` values discovered during account download. A Bloom filter deduplicates the 148,486 contract accounts down to 57,337 unique codes.

```
14:01:44  Bytecode download started (57,337 codes)
14:17:46  Bytecode download complete
```

### Phase 3: Storage Range Download (14:11:18 → 14:17:38, overlapping with bytecode)

Download storage tries for contract accounts. Multi-account batching sends `GetStorageRanges` for up to 128 accounts per request.

```
14:11:18  Storage range download — first "complete" messages
14:17:38  Storage range download complete
```

Storage and bytecode phases run concurrently — this was a critical optimization (Bug 20a fix). The original design ran them sequentially, but the 5-second phase handoff timeout would expire trying to read 73.5M contract accounts from disk.

### Phase 4: SNAP Complete → Regular Sync (14:17:46)

```
14:17:46  All state downloads complete
          Deferred merkleization enabled — building tries
          SNAP sync → Regular sync transition
14:17:46  Batch import: blocks 15,821,494 → 15,821,519
14:17:52  Batch import: blocks 15,821,520 → 15,821,554
14:18:05  Batch import: blocks 15,821,555 → 15,821,586
14:18:16  BlockFetcher: is on top -> true (block 15,821,589)
```

The transition from SNAP to regular sync is seamless. After state is complete, the node immediately starts importing blocks from the pivot point forward and catches up to chain head within ~30 seconds.

### Phase 5: Steady State (14:18:16+)

```
14:18:16  Regular sync — importing blocks at chain speed (~1 block/15sec)
14:39:17  Block 15,821,715 — is on top -> true
          Node is fully synced and tracking chain head in real-time
```

---

## Key Configuration Parameters

These are the SNAP sync defaults in `src/main/resources/conf/base/sync.conf`:

| Parameter | Value | Notes |
|-----------|-------|-------|
| `pivot-block-offset` | 64 | Blocks behind network best |
| `account-concurrency` | 16 | Parallel account range workers |
| `storage-concurrency` | 16 | Parallel storage range workers |
| `storage-batch-size` | 128 | Accounts per GetStorageRanges |
| `max-inflight-per-peer` | 5 | Request pipelining depth |
| `account-initial-response-bytes` | 524KB | Starting response size hint |
| `storage-initial-response-bytes` | 1MB | Storage is denser data |
| `deferred-merkleization` | true | Build tries after download |
| `healing-concurrency` | 16 | Trie healing workers |
| `healing-batch-size` | 512 | Nodes per GetTrieNodes |
| `snap-capability-grace-period` | 6s | Wait for snap/1 peers |
| `account-stagnation-timeout` | 3min | Detect non-SNAP peers |
| `max-snap-sync-failures` | 5 | Before fast sync fallback |
| `account-trie-flush-threshold` | 50,000 | In-memory trie node cap |

---

## Bugs Fixed to Enable This

This sync succeeds because of 20 bugs fixed during the alpha phase. The critical ones for SNAP:

1. **Bug 18 (OOM):** Unbounded in-memory trie + contract account buffers. Fixed with periodic trie flush and file-backed storage.
2. **Bug 20 (Post-download pipeline):** Phase handoff timeout, Bloom filter dedup, concurrent bytecode+storage, recovery actor message forwarding, state validation traversal fix.
3. **Bug 11 (Capability check):** Verify snap/1 peers exist before starting.
4. **Bug 12 (Stagnation watchdog):** Track `accountsDownloaded` as liveness signal.
5. **Bug 13 (Partial range resume):** Preserve progress across pivot refreshes.
6. **Bug 14 (Dynamic concurrency):** Cap workers to snap peer count.
7. **Bug 15 (In-place pivot refresh):** Update state root without restart.
8. **Bug 6 (RPC starvation):** Sync actors on dedicated dispatcher.

See `CHANGELOG.md` and `.claude/CLAUDE.md` for the full list.

---

## Known Limitations

- **ETC mainnet SNAP:** Disabled by default (`do-snap-sync=false` in `etc-chain.conf`). No public ETC peers serve `snap/1`. Requires a local Besu SNAP server synced to ETC mainnet. Besu must use `--sync-mode=SNAP` (not FULL) — FULL sync with BONSAI stalls indefinitely on blocks containing SELFDESTRUCT of contracts with large storage tries due to O(n²) `clearStorage()` trie iteration. Besu SNAP syncs from core-geth in ~10-15 minutes.
- **No public Mordor/ETC SNAP peers:** SNAP sync on both networks requires the local Besu setup described above.
- **Memory:** 4GB heap is sufficient for Mordor (2.6M accounts). ETC mainnet (~45M accounts) may require 8-12GB and has not been tested with SNAP.
- **SNAP server:** Fukuii does not yet serve `snap/1` to other clients (`snap-server-enabled=false`). This is backlog item H-015.

---

## Replication Checklist

1. [ ] Build Fukuii JAR: `sbt assembly`
2. [ ] Sync core-geth to Mordor head (takes ~30 min from genesis)
3. [ ] Start Besu with `--snapsync-server-enabled --data-storage-format=BONSAI --sync-mode=SNAP`
4. [ ] Wait for Besu to reach Mordor head (~10-15 min SNAP from core-geth)
5. [ ] Create `static-nodes.json` with Besu + core-geth enodes
6. [ ] Launch Fukuii with `-Dfukuii.sync.do-snap-sync=true`
7. [ ] Monitor logs — expect chain head in ~35 minutes
8. [ ] Verify: `curl -s localhost:8553 -X POST -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":1}'` returns `false`

---

## Test Scripts

Automated versions of the above are in `ops/test-scripts/`:

| Script | Purpose |
|--------|---------|
| `test-fukuii-snap.sh` | Launch Fukuii SNAP sync (auto-configures static-nodes from Besu) |
| `test-besu-snap-snapserver.sh` | Start Besu as SNAP server on Mordor (BONSAI + SNAP sync) |
| `test-besu-etc-snapserver.sh` | Start Besu as SNAP server on ETC mainnet (BONSAI + SNAP sync) |
| `test-besu-full-snapserver.sh` | Start Besu FULL sync on Mordor (FOREST — no SNAP serving) |
| `test-coregeth-full.sh` | Start core-geth full node |

Set `FUKUII_DIR`, `BESU_DIR`, `BINARY`, `DATADIR` environment variables or create `ops/local.env` with local paths (gitignored).
