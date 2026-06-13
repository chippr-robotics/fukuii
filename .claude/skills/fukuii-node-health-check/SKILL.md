---
name: fukuii-node-health-check
description: >-
  Produce a comprehensive health verdict for a running Fukuii node — client
  version, sync state, peer count, latest block height, and a recent-log scan
  — rolled into HEALTHY / DEGRADED / ACTION REQUIRED. For ETC/Mordor nodes also
  checks mining state. For ETH/Sepolia nodes also checks Engine API / Consensus
  Layer connectivity. Use when asked to "check the node", "is my node OK", do a
  routine/scheduled health check, or verify a node before or after maintenance.
  Read-only; safe to run anytime.
---

# Fukuii node health check

Read `../CONVENTIONS.md` first (locating the node, calling RPC, output contract).
This skill is **read-only** — no guarded writes.

## When to use
- Routine "is everything OK?" checks, dashboards-by-hand, on-call triage.
- Before starting any maintenance, and again after, to confirm a clean state.
- As the first step when a vaguer problem is reported, to localize it.

## Inputs to gather first
Network (`etc`/`mordor`/`sepolia`/…), RPC host/port, datadir. See CONVENTIONS §1.

## Procedure (all 🟢 read-only)
1. **Liveness / identity** — `web3_clientVersion`, `net_version`,
   `net_listening`. Node up? Right network?
2. **Sync** — `eth_syncing`. `false` = synced; otherwise inspect
   `currentBlock` vs `highestBlock` (and SNAP pivot if present). Cross-check
   with MCP `mcp_sync_status`.
3. **Chain tip** — `eth_blockNumber`; compare against a known-good external tip
   for the network if available. A tip far behind wall-clock = stale.
4. **Peers** — `net_peerCount` and `admin_peers` (or `mcp_peer_list`).
   Healthy peer count: **~20–40 peers** (same `min-outgoing-peers = 20` config
   target for all networks; Sepolia may run lower in practice due to smaller
   network). CL beacon peers are separate and not visible here.
   **< 3 peers = degraded**; 0 = isolated.
5. **Mining (ETC/Mordor only)** — `eth_mining`, `miner_getStatus`,
   `eth_hashrate`, `eth_coinbase`. Skip for ETH/Sepolia (PoS — no mining).
   See `fukuii-mining-operations` for depth.
6. **Engine API / CL connectivity (ETH/Sepolia only)** — check logs for
   `engine_forkchoiceUpdated` calls succeeding. If `eth_syncing` shows no
   progress and the node has peers, the Consensus Layer (CL) is likely not
   connected. A healthy ETH/Sepolia EL requires an active CL driving it via
   the Engine API.
7. **Resources** — `admin_datadir` → then `df -h <datadir>` for free space; flag
   < 10% free. (Hand off to `fukuii-disk-management` if tight.)
8. **Log scan** — tail `~/.fukuii/<network>/logs/fukuii.log` for `ERROR`/`WARN`
   bursts, stack traces, `OutOfMemory`, repeated peer drops, RocksDB errors,
   or Engine API timeouts (ETH only).

## Decision guide
| Symptom | Verdict | Hand off to |
| :-- | :-- | :-- |
| Synced, peers ≥ 10, no error burst | HEALTHY | — |
| Syncing but progressing, peers OK | DEGRADED (expected) | `fukuii-sync-troubleshooting` if stalled |
| Peers < 3 / 0 | ACTION REQUIRED | `fukuii-peer-management` |
| ETH/Sepolia: `eth_syncing` stuck, peers OK, Engine API timeouts in logs | ACTION REQUIRED | Verify CL is running and connected; `fukuii-sync-troubleshooting` |
| Disk < 10% free | ACTION REQUIRED | `fukuii-disk-management` |
| Repeated ERROR / OOM / RocksDB corruption | ACTION REQUIRED | `fukuii-log-triage` |

## Deep reference
- `docs/operations/metrics-and-monitoring.md` (Prometheus/JMX/Grafana/Kamon)
- `docs/operations/LOGGING.md`
- The in-process MCP prompt `mcp_node_health_check` covers the same intent.

## Output
Emit the CONVENTIONS §4 block: **Verdict**, **Evidence** (actual numbers/lines),
**Actions taken** (none — read-only), **Recommended next steps** (with the exact
hand-off skill and commands).
