---
name: fukuii-node-health-check
description: >-
  Produce a comprehensive health verdict for a running Fukuii node — client
  version, sync state, peer count, latest block height, mining state, and a
  recent-log scan — rolled into HEALTHY / DEGRADED / ACTION REQUIRED. Use when
  asked to "check the node", "is my node OK", do a routine/scheduled health
  check, or verify a node before or after maintenance. Read-only; safe to run
  anytime.
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
4. **Peers** — `net_peerCount` and `admin_peers` (or `mcp_peer_list`). Healthy
   ETC node: ~20–40 peers. **< 5 peers = degraded**, 0 = isolated.
5. **Mining** (only if this node mines) — `eth_mining`, `miner_getStatus`,
   `eth_hashrate`, `eth_coinbase`. See `fukuii-mining-operations` for depth.
6. **Resources** — `admin_datadir` → then `df -h <datadir>` for free space; flag
   < 10% free. (Hand off to `fukuii-disk-management` if tight.)
7. **Log scan** — tail `~/.fukuii/<network>/logs/fukuii.log` for `ERROR`/`WARN`
   bursts, stack traces, `OutOfMemory`, repeated peer drops, RocksDB errors.

## Decision guide
| Symptom | Verdict | Hand off to |
| :-- | :-- | :-- |
| Synced, peers ≥ 10, no error burst | HEALTHY | — |
| Syncing but progressing, peers OK | DEGRADED (expected) | `fukuii-sync-troubleshooting` if stalled |
| Peers < 5 / 0 | ACTION REQUIRED | `fukuii-peer-management` |
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
