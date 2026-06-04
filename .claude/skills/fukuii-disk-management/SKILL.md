---
name: fukuii-disk-management
description: >-
  Triage and manage Fukuii's disk usage — understand the datadir layout, measure
  RocksDB growth, free space safely, and size storage for ETC's large chain. Use
  when disk is filling up, the node warns about space, you're planning capacity,
  or sync is slow due to I/O. Measurement is read-only; any deletion/relocation
  of the database is an irreversible action under the guarded-write protocol.
---

# Fukuii disk management

Read `../CONVENTIONS.md` first. Inspection is 🟢; deleting/moving `rocksdb/` or
relocating the datadir is 🔴 — back up (`fukuii-backup-restore`) and confirm
first.

## When to use
- Low free space / "no space left on device" in logs.
- Planning storage for a new ETC node (the DB is large — hundreds of GB).
- I/O-bound sync (often a disk-type/space problem, not a sync bug).

## Datadir layout (`~/.fukuii/<network>/`)
| Path | Typical size (ETC) | Prunable? |
| :-- | :-- | :-- |
| `node.key`, `keystore/` | tiny | never delete |
| `rocksdb/` | hundreds of GB (bulk) | regenerable by re-sync only |
| `logs/` | up to ~500 MB (rotated) | yes, safe |
| `knownNodes.json`, `app-state.json` | tiny | operational |

## Procedure
1. **Locate & measure** (🟢) — `admin_datadir`, then `df -h <datadir>` and
   `du -sh <datadir>/rocksdb`. Identify the biggest consumer.
2. **Reclaim the easy wins first** (🟢/🟡) — rotated logs under `logs/` are safe
   to remove; temp/JFR files under the configured temp dir can be cleared. This
   alone often relieves pressure without touching the DB.
3. **Database growth is mostly unavoidable** — block bodies and headers cannot be
   pruned (they're the chain). Do **not** expect a "prune blockchain" command:
   there is **no `fukuii cli compact-database` subcommand** today (it appears only
   as a commented example in `known-issues.md`). RocksDB compaction is internal
   and tuned via `base/db.conf`, not an operator action.
4. **If space is genuinely exhausted** (🔴) — the real levers are: add storage /
   grow the volume, **relocate the datadir** to a larger/faster disk (stop node,
   move `rocksdb/`, point `fukuii.datadir` at the new path, restart), or as a last
   resort re-sync onto fresh storage. Each requires a backup and confirmation.
5. **Verify** (🟢) — re-check `df -h` and run `fukuii-node-health-check`.

## Sizing guidance
ETC mainnet is large and grows steadily (~30 GB/yr bodies). Provision SSD/NVMe
with generous headroom; HDDs starve SNAP sync. See the runbook's breakdown table.

## Deep reference
- `docs/runbooks/disk-management.md` (storage breakdown, optimization)
- `docs/runbooks/known-issues.md` (note the `compact-database` caveat above)
- `src/main/resources/conf/base/db.conf` (RocksDB tuning — internal compaction)

## Output
CONVENTIONS §4 block. Evidence = actual `df`/`du` figures before and after.
