---
name: fukuii-backup-restore
description: >-
  Back up and restore a Fukuii node — node key, keystore, config, known peers,
  and the RocksDB blockchain/state database — plus chain export/import for
  portable backups. Use when asked to back up before an upgrade or risky change,
  set up a backup strategy, restore after data loss/corruption, or run disaster
  recovery. Backups are read-only; restore and chain import are irreversible
  writes that must follow the guarded-write protocol.
---

# Fukuii backup & restore

Read `../CONVENTIONS.md` first. Backups are 🟢; **restore over an existing datadir
and `admin_importChain` are 🔴** — explicit confirmation, and verify a backup
exists before overwriting anything.

## When to use
- Before an upgrade, config change, prune, or any 🔴 maintenance elsewhere.
- Recovering from disk failure, RocksDB corruption, or operator error.
- Standing up a standby/replica from a known-good snapshot.

## Inputs to gather first
Network, datadir (`admin_datadir`), backup destination, available space.

## What to back up (priority order)
| Tier | Paths under `~/.fukuii/<network>/` | Notes |
| :-- | :-- | :-- |
| **Critical, tiny** | `node.key`, `keystore/`, config (`conf/fukuii.conf`) | Irreplaceable — keys cannot be regenerated. Back up frequently, off-box. |
| **Operational** | `knownNodes.json`, `app-state.json` | Speeds recovery; small. |
| **Bulk, regenerable** | `rocksdb/` (blockchain + state) | Large; can be re-synced but slow. Snapshot daily/before changes. |
| Skip | `logs/` | Not needed for recovery. |

## Procedure
1. **Backup critical files** (🟢) — copy keys/config to secure, off-box storage.
   Treat `node.key` and `keystore/` as secrets; restrict permissions.
2. **Backup the database** (🟢, but quiesce first) — for a consistent RocksDB
   copy, **stop the node** (🔴 — it's a disruption) or use a filesystem/VM
   snapshot (preferred: minimal downtime). A hot file copy of `rocksdb/` while
   running can be inconsistent — avoid unless using a snapshot.
3. **Portable chain export** (🟡) — `admin_exportChain` writes a portable chain
   file (good for moving between hosts / sharing a known-good chain).
4. **Restore** (🔴) — confirm the target datadir's current contents are
   expendable / already backed up, stop the node, replace `rocksdb/` + restore
   keys/config, fix permissions, restart, then run `fukuii-node-health-check`.
5. **Import chain** (🔴) — `admin_importChain(<file>)` to load an exported chain.
   Confirm intent; this mutates local chain state.
6. **Validate** (🟢) — after any restore, confirm version/network, `eth_syncing`
   resumes, peers connect, and the tip is sane.

## Strategy quick-pick
Hybrid (recommended): hourly key/config backups + daily DB snapshot + on-demand
snapshot before changes. RTO/RPO trade-offs are tabulated in the runbook.

## Deep reference
- `docs/runbooks/backup-restore.md` (strategy matrix, DR planning, validation)

## Output
CONVENTIONS §4 block. Evidence = what was backed up/restored, sizes, and the
post-restore health result. Record every 🔴 step and its confirmation.
