---
name: fukuii-checkpoint-service
description: >-
  Operate Fukuii's checkpoint-based rapid sync for ETC/Mordor nodes — point a
  fresh node at a trusted checkpoint archive (local file or HTTP URL) so it
  imports a known pivot and starts SNAP sync immediately instead of waiting on
  peer consensus, and produce checkpoint archives for others. Use when
  bootstrapping an ETC/Mordor node fast, running a checkpoint source for a fleet,
  or debugging why checkpoint import didn't engage. ETH/Sepolia nodes do NOT use
  checkpoint-sync — they sync via Consensus Layer / Engine API instead. Config
  changes require a restart — an irreversible/disruptive action under the
  guarded-write protocol.
---

# Fukuii checkpoint service (rapid sync)

Read `../CONVENTIONS.md` first. This is **config-driven**, not an RPC API — there
are no `checkpoint_*` JSON-RPC methods. Config edits need a restart → 🔴.

## What it is
This feature applies to **ETC/Mordor only**. ETH/Sepolia nodes sync through the
Consensus Layer (CL) via the Engine API — checkpoint-sync is not relevant for
ETH/Sepolia and the config keys below are silently ignored in that context.

On a **fresh** ETC/Mordor database (best-block == 0, SNAP not already done),
Fukuii can import a trusted `.checkpoint` archive and use its block as the pivot,
skipping the "wait for 3+ peers" phase before SNAP sync. Backed by
`blockchain/checkpoint/CheckpointArchive.scala` (+ `CheckpointDownloader`,
`CheckpointExporter`).

## When to use
- Bootstrapping a node and you want sync to start in seconds, not minutes.
- Running a checkpoint archive endpoint for your own fleet.
- Investigating why a configured checkpoint was ignored.

## Config keys (under `sync`, see `base/sync.conf`)
| Key | Meaning |
| :-- | :-- |
| `sync.checkpoint-sync-file` | Path to a local `.checkpoint` archive to import (empty = off) |
| `sync.checkpoint-sync-url` | HTTP URL to fetch the archive to `${datadir}/checkpoint.bin` (resumable), then import |
| `sync.do-snap-sync` | Must be enabled for the rapid-sync path |

## Procedure
1. **Obtain a trusted archive** — only import checkpoints from a source you
   trust; a malicious checkpoint is a trust-root attack. Verify provenance.
2. **Configure** (🔴) — set `checkpoint-sync-file` *or* `checkpoint-sync-url` (a
   local file takes priority). Ensure the DB is fresh — checkpoint import only
   engages when best-block == 0 and SNAP isn't already marked done.
3. **Start & confirm** (🟢 after restart) — watch logs for checkpoint import,
   then `eth_syncing`/`mcp_sync_status` should show SNAP starting from the
   checkpoint pivot rather than block 0.
4. **Produce an archive** — use `CheckpointExporter` to generate a `.checkpoint`
   from a synced node for distribution (see runbook for the exact invocation).

## Gotchas (verify before blaming the feature)
- Won't engage on a **non-fresh** DB — that's by design.
- Requires SNAP enabled; with `do-snap-sync=false` the pivot path differs.
- **ETH/Sepolia does not use checkpoint-sync.** If `eth_syncing` is not advancing
  on Sepolia, the cause is the Consensus Layer not being connected — not a
  checkpoint configuration issue. See `fukuii-sync-troubleshooting`.

## Deep reference
- `docs/runbooks/checkpoint-service.md`
- `src/main/resources/conf/base/sync.conf` (lines defining `checkpoint-sync-*`)
- `src/main/scala/com/chipprbots/ethereum/blockchain/checkpoint/`

## Output
CONVENTIONS §4 block. Evidence = the log line showing checkpoint import and the
pivot block the node started from.
