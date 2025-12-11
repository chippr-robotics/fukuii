# Gorgoroth 3-Node Phase 2 Field Report

**Date:** 2025-12-11  \
**Tester:** @copilot  \
**Trial Type:** Gorgoroth 3nodes

---

## System Information
- **OS:** Ubuntu 22.04 (WSL2 host)
- **Docker Version:** 28.5.2 (build ecc6942)
- **Docker Compose:** v2.40.3
- **Available RAM:** ~15 GiB total / 3 GiB free (per `free -h`)
- **Available Disk:** 23 GiB free on root volume (per `df -h /`)
- **Network:** Wired >100 Mbps (not directly measured)

---

## Test Window
- **Start:** 2025-12-11 19:35 UTC
- **End:** 2025-12-11 20:20 UTC
- **Total Duration:** ~45 minutes

---

## Phase 2 Verification Steps

| Step | Command / Action | Observations |
| ---- | ---------------- | ------------ |
| 1 | `./ops/tools/fukuii-cli.sh start 3nodes` | All three containers start healthy. |
| 2 | `fukuii-cli sync-static-nodes` (post-clean + after restart) | Static node files updated, containers restarted cleanly. |
| 3 | Baseline block checks using `eth_blockNumber` on ports 8546/8548/8550 | All nodes reported `0x0`. |
| 4 | Wait periods (30s, 60s, 120s) with repeated `eth_blockNumber` queries | Block height remained `0x0` on every node despite peers = 2/2. |
| 5 | `eth_mining` RPC checks | Returned `false` for node1, indicating mining never enabled. |
| 6 | `ops/gorgoroth/test-scripts/test-mining.sh` | Script failed (`jq: Invalid numeric literal`) because it detected `0` Fukuii nodes producing blocks. |
| 7 | `fukuii-cli logs 3nodes --tail 80` | Logs show repeated DAG generation attempts (40%+) but no successful block sealing; peers repeatedly disconnect during handshake. |

---

## Results Summary
- **Network Connectivity:** ✅ Containers healthy with `net_peerCount = 0x2` on each node after `sync-static-nodes`.
- **Mining Activity:** ❌ `eth_blockNumber` and `eth_mining` show that no node produces blocks even after multiple waits.
- **Automated Mining Test:** ❌ `test-mining.sh` exits with code 5 because it cannot find any recent Fukuii blocks.
- **Block Inspection:** ❌ `eth_getBlockByNumber` (latest) always returns the genesis block hash `0x039853...`.

### Key Logs
```
2025-12-11 20:03:14 INFO  EthashDAGManager - Generating DAG 42%
2025-12-11 20:03:15 DEBUG PeerManagerActor - No suitable peer found to issue a request (handshaked: 0)
2025-12-11 20:03:15 DEBUG RLPxConnectionHandler - Stopping Connection ... Connection reset
```
```
$ curl -s localhost:8546 -d '{"method":"eth_mining"...}' | jq
{"result": false}
```

---

## Issues Encountered
1. **Mining never starts:** Despite `-Dfukuii.mining.mining-enabled=true`, nodes stay at block `0x0` and `eth_mining=false`.
2. **Peer handshakes flap:** Logs show repeated connection resets during RLPx auth negotiation, preventing header/body exchange.
3. **Automation failure:** `test-mining.sh` assumes at least one block exists; with zero producers, the embedded jq parsing fails.

### Impact
- Phase 2 requirements (mining validation) are **blocked**. The network cannot advance past genesis, so downstream phases cannot proceed.

---

## Recommendations / Next Steps
1. **Inspect compose config/environment:** Confirm no missing DAG cache mounts or GPU requirements; ensure `fukuii` containers have access to CPU mining.
2. **Enable verbose mining logs:** Set `-Dfukuii.logging.mining=TRACE` to capture why Ethash sealing never begins.
3. **Check hardware entropy / CPU throttling:** Mining might require `ethash.full-dag` path or additional settings when running under constrained VMs.
4. **Retry Phase 2 once mining starts:** Re-run `eth_blockNumber` sampling and `test-mining.sh` after addressing above issues.

---

## Follow-up Investigation (2025-12-11 20:45–21:30 UTC)

### Context
- Compose stack rebuilt so **only node1 mines**; nodes2/3 were reset with fresh volumes and static peers.
- DAG generation finished; node1 advanced to block `0xe7` (`eth_mining=true`), yet followers stayed at `0x0` while reporting `eth_syncing` with `highestBlock` matching node1.
- `fukuii-cli logs 3nodes` showed `BlockFetcher` fetching 213 headers starting at block `1`, immediately rejecting them with `"Given headers should form a sequence without gaps"` and blacklisting node1 for "UnrequestedHeaders".

### Root Cause
- Gorgoroth activates **ECIP-1097 checkpointing at block 0**, so every header carries the 16th RLP field reserved for `HefPostEcip1097`, even when no checkpoint signatures exist.
- The Fukuii client decoded those headers and **normalized empty checkpoints back to `HefEmpty`** (see `BlockHeaderDec`), effectively removing the extra field before recomputing `hash`.
- As a result, the locally recomputed hash for block *n* no longer matched the `parentHash` embedded in block *n+1*, causing `HeadersSeq.areChain` to fail and followers to mark node1's response as "unrequested".

### Fix Implemented
- Updated `src/main/scala/.../BlockHeader.scala` so decoded ECIP-1097 headers **preserve** their `HefPostEcip1097` extra field even when the checkpoint payload is `None` or has zero signatures. This keeps the RLP layout (and therefore the block hash) identical to what the miner produced.
- Extended the property generators (`ObjectGenerators.extraFieldsGen`) so tests now exercise headers with `HefPostEcip1097(None)`.
- Added a regression test in `BlockHeaderSpec` (`"should decode post ECIP1097 headers without checkpoint without losing the extra field"`) to ensure encoding↔decoding remains symmetric for the checkpointed format.
- `sbt "testOnly com.chipprbots.ethereum.domain.BlockHeaderSpec"` now passes, confirming hashes stay stable for ECIP-1097 headers.

### Next Validation Steps
1. Build/publish a Fukuii image with this fix and update the 3-node stack.
2. Repeat the RPC sweep (`eth_blockNumber`, `eth_mining`, `eth_syncing`) on ports 8546/8548/8550.
3. Verify followers progress past block `0x0` and that `test-mining.sh` succeeds.
4. Capture fresh logs to confirm the `BlockFetcher` no longer emits "Given headers should form a sequence" warnings.

### Configuration Change
- **ECIP-1097 disabled**: All PoW-focused chain configs (Gorgoroth, Pottery, and internal Nomad) now set `ecip1097-block-number = "1000000000000000000"` so checkpointing never activates. This keeps test deployments aligned with production ETC, where ECIP-1097 was withdrawn, and prevents false positives caused by synthetic header fields.

---

## Attachments / Evidence
- Terminal transcripts for `eth_blockNumber`, `eth_mining`, `test-mining.sh`, and `fukuii-cli logs` (available upon request from this workspace session).
