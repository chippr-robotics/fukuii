---
name: fukuii-sync-troubleshooting
description: >-
  Diagnose and fix stalled, slow, or stuck blockchain synchronization on a
  Fukuii node. For ETC/Mordor: covers SNAP sync and full/regular sync — pivot
  stalls, account/storage-range progress, peer starvation, and performance
  tuning. For ETH/Sepolia: covers Engine API-driven sync stalls and Consensus
  Layer connectivity issues. Use when sync is "stuck", "not progressing",
  "too slow", block height isn't advancing, or after a restart that won't catch
  up. Diagnosis is read-only; tuning changes config (restart required) under the
  guarded-write protocol.
---

# Fukuii sync troubleshooting

Read `../CONVENTIONS.md` first. Diagnosis is 🟢; config/tuning edits are 🔴
(require restart) — gate them.

**Network-specific sync model:**
- **ETC/Mordor**: SNAP sync is enabled by default (base `sync.conf` defaults). The EL manages sync independently; no CL required. Both ETC mainnet and Mordor use this path.
- **ETH/Sepolia (base config)**: `do-snap-sync = false` in `sepolia.conf`; sync is driven by the Consensus Layer (CL) via Engine API (`engine_newPayload`, `engine_forkchoiceUpdated`). Ops deployments may enable SNAP for initial state download, after which the CL takes over for ongoing block import. If the CL is not connected, `eth_syncing` will not advance.

## When to use
- `eth_syncing` shows no forward progress over several minutes.
- ETC/Mordor: SNAP sync stuck at the pivot, or account/storage ranges not completing.
- ETH/Sepolia: `eth_syncing` stuck despite peers being healthy — CL may not be driving the EL.
- Sync is healthy but far slower than the hardware should allow.

## Inputs to gather first
Network, RPC port, datadir, sync mode (SNAP vs full — ETC), hardware (cores, RAM, disk
type). For ETH/Sepolia: also identify the CL client and its status. See CONVENTIONS §1.

## Procedure
1. **Confirm it's actually stalled** (🟢) — sample `eth_blockNumber` and
   `eth_syncing` ~30s apart. Use `mcp_sync_status` for SNAP phase/pivot detail.
   No movement at all → stall; slow movement → tuning problem.
2. **Check peers first** (🟢) — sync needs peers. `net_peerCount` < 3 means the
   problem is peering, not sync → `fukuii-peer-management`. Verify peers actually
   serve the protocol/snap capability.
3. **ETH/Sepolia: check CL connectivity first** (🟢) — if on ETH/Sepolia and
   peers look healthy but sync is stuck, check logs for Engine API timeouts or
   missing `engine_forkchoiceUpdated` calls. If the CL is not running or not
   reaching the EL's Engine API port, the EL cannot sync — the fix is on the CL
   side, not the EL. Hand off to the CL's troubleshooting guide.
4. **Scan logs** (🟢) — `fukuii-log-triage` for `ERROR`/timeout/`AccountRange`/
   `pivot`/RocksDB lines around the stall window.
5. **ETC/Mordor SNAP** — pivot moves as the chain advances; a stuck pivot or
   stalled `AccountRangeCoordinator` is a known failure family. Check progress
   of account vs storage ranges. If genuinely wedged, the documented recovery
   is a clean restart (🔴 — stops the node) or, last resort, a fast-sync reset
   via `fukuii_resetFastSync` / `fukuii_restartFastSync` (🔴 — discards progress).
6. **Performance tuning — ETC/Mordor** (🔴 — edits `fukuii.conf`, needs restart) —
   match concurrency to CPU before touching anything else:

   | CPU | `snap-sync.account-concurrency` | `snap-sync.storage-concurrency` |
   | :-- | :-- | :-- |
   | 2 cores | 8 | 8 |
   | 4 cores (default 16/16) | 16 | 16 |
   | 8+ cores | 32 | 32 |

   Also relevant: disk must be SSD/NVMe (HDD will starve SNAP), adequate RAM,
   and network bandwidth. Apply **one** change at a time and re-measure.
   This tuning does not apply to ETH/Sepolia (CL-driven sync).

## Decision guide
| Symptom | Likely cause | Action |
| :-- | :-- | :-- |
| 0 peers / < 3 | peering | `fukuii-peer-management` |
| ETH/Sepolia: stuck, peers OK, Engine API timeouts in logs | CL not connected/stalled | verify CL is running; check CL → EL Engine API connectivity |
| ETC/Mordor: Pivot/account-range wedged, errors in log | SNAP stall | restart (🔴) per runbook; re-sync if persistent |
| Progressing but slow, HDD | I/O bound | move datadir to SSD/NVMe |
| ETC/Mordor: Slow on capable HW, low concurrency | under-tuned | raise concurrency to CPU table (🔴 restart) |

## Deep reference
- `docs/runbooks/snap-sync-user-guide.md`, `snap-sync-faq.md`,
  `snap-sync-performance-tuning.md`
- `docs/operations/monitoring-snap-sync.md`
- `docs/runbooks/known-issues.md` (known SNAP failure modes)

## Output
CONVENTIONS §4 block. State the **measured** progress rate as evidence, list any
config change made (and that a restart is required), and the re-measure result.
