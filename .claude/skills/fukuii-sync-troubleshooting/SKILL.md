---
name: fukuii-sync-troubleshooting
description: >-
  Diagnose and fix stalled, slow, or stuck blockchain synchronization on a
  Fukuii node — SNAP sync and full/regular sync alike — covering pivot stalls,
  account/storage-range progress, peer starvation, and performance tuning. Use
  when sync is "stuck", "not progressing", "too slow", block height isn't
  advancing, or after a restart that won't catch up. Diagnosis is read-only;
  tuning changes config (restart required) under the guarded-write protocol.
---

# Fukuii sync troubleshooting

Read `../CONVENTIONS.md` first. Diagnosis is 🟢; config/tuning edits are 🔴
(require restart) — gate them.

## When to use
- `eth_syncing` shows no forward progress over several minutes.
- SNAP sync stuck at the pivot, or account/storage ranges not completing.
- Sync is healthy but far slower than the hardware should allow.

## Inputs to gather first
Network, RPC port, datadir, sync mode (SNAP vs full), hardware (cores, RAM, disk
type). See CONVENTIONS §1.

## Procedure
1. **Confirm it's actually stalled** (🟢) — sample `eth_blockNumber` and
   `eth_syncing` ~30s apart. Use `mcp_sync_status` for SNAP phase/pivot detail.
   No movement at all → stall; slow movement → tuning problem.
2. **Check peers first** (🟢) — sync needs peers. `net_peerCount` < 5 means the
   problem is peering, not sync → `fukuii-peer-management`. Verify peers actually
   serve the protocol/snap capability.
3. **Scan logs** (🟢) — `fukuii-log-triage` for `ERROR`/timeout/`AccountRange`/
   `pivot`/RocksDB lines around the stall window.
4. **SNAP-specific** — pivot moves as the chain advances; a stuck pivot or stalled
   `AccountRangeCoordinator` is a known failure family. Check progress of account
   vs storage ranges. If genuinely wedged, the documented recovery is a clean
   restart (🔴 — stops the node) or, last resort, a fast-sync reset via
   `fukuii_resetFastSync` / `fukuii_restartFastSync` (🔴 — discards progress).
5. **Performance tuning** (🔴 — edits `fukuii.conf`, needs restart) — match
   concurrency to CPU before touching anything else:

   | CPU | `snap-sync.account-concurrency` | `snap-sync.storage-concurrency` |
   | :-- | :-- | :-- |
   | 2 cores | 8 | 4 |
   | 4 cores (default) | 16 | 8 |
   | 8+ cores | 32 | 16 |

   Also relevant: disk must be SSD/NVMe (HDD will starve SNAP), adequate RAM,
   and network bandwidth. Apply **one** change at a time and re-measure.

## Decision guide
| Symptom | Likely cause | Action |
| :-- | :-- | :-- |
| 0 peers / < 5 | peering | `fukuii-peer-management` |
| Pivot/account-range wedged, errors in log | SNAP stall | restart (🔴) per runbook; re-sync if persistent |
| Progressing but slow, HDD | I/O bound | move datadir to SSD/NVMe |
| Slow on capable HW, low concurrency | under-tuned | raise concurrency to CPU table (🔴 restart) |

## Deep reference
- `docs/runbooks/snap-sync-user-guide.md`, `snap-sync-faq.md`,
  `snap-sync-performance-tuning.md`
- `docs/operations/monitoring-snap-sync.md`
- `docs/runbooks/known-issues.md` (known SNAP failure modes)

## Output
CONVENTIONS §4 block. State the **measured** progress rate as evidence, list any
config change made (and that a restart is required), and the re-measure result.
