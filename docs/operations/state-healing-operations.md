# State Healing — Operator's Guide

State healing is the last SNAP-sync phase before a node hands off to regular sync. It finds
and downloads the trie nodes that are still missing after the account/storage/bytecode
download phases (pivot movement during a long sync guarantees there are some), then verifies
the state is complete. On a small network this takes minutes; on ETC mainnet (~86M accounts,
~119M state-trie nodes, ~85 GB on disk) the *discovery walk* is the long pole and runs for
hours. This page explains what you will see, which knobs exist, and how to tell healthy from
stuck.

For the design rationale see
[`docs/design/healing-frontier-scale.md`](../design/healing-frontier-scale.md); for dashboards
see [Monitoring SNAP Sync](monitoring-snap-sync.md).

## How it works (one paragraph)

The healing coordinator rebuilds its "frontier" (the set of missing node hashes) by walking
the locally stored state trie **breadth-first, level by level** (`rebuildFrontierBFS`). The
level queue is kept in a dedicated RocksDB column family, so memory stays flat no matter how
wide a level gets (ETC mainnet level 7 exceeds 70M entries). Node lookups are batched (50K
hashes per `multiGet`), the visited set is a bounded LRU
(`healing-visited-cap`, default 4M entries ≈ 320 MB), and **exactly one walk runs at a
time** (a single-flight gate covers both the rebuild and the later verification pass).
Missing nodes stream out in batches of 1,000 while the walk is still running and are fetched
from peers via SNAP `GetTrieNodes`. When the walk finishes, a completeness marker is
persisted so a restart can resume from the saved frontier instead of re-walking.

## What you will see in the logs

A healthy mainnet-scale healing phase, in order:

```
[HEAL-RESTART] Root 65d85b06… already in local storage — rebuilding frontier via local DFS in batches of 1000
[HEAL-RESTART] Persisted frontier has 15907 entries but no completeness marker — re-running full-state DFS
[HEAL-BFS] Level 0 complete: 1 processed, 0 frontier total, 16 queued for L1
[HEAL-BFS] Level 3 complete: 4096 processed, 0 frontier total, 65536 queued for L4
[HEAL-BFS] Level 6: 12900000 nodes visited, 1 frontier found, 51467209 L7 queued
[HEAL-FRONTIER] 1000 missing nodes identified — queuing for healing
[HEAL-PULSE] 37% (est) | healed=56346 (+1141 last 2min) | pending=0 active=4 peers=31 | rate=230 nodes/s walkRunning=true pivotRefreshPending=false
[HEAL-BFS] Complete: 118754095 nodes traversed, 835707 missing nodes identified
[HEAL-RESTART] Full-state rebuild complete — persisted frontier marked as a complete snapshot
```

Reference points from a live ETC-mainnet run (June 2026, 4-core host, SSD-class storage):

| Observation | Value |
| --- | --- |
| Full-state walk total | ~119M nodes traversed |
| Walk throughput | ~700–1,400 nodes/s sustained in the wide levels; bursts to ~6,000 nodes/s |
| Widest level | L7, 70M+ entries in the disk-backed queue (memory flat) |
| Healing fetch rate | bursty, peer-bound: ~50–250 nodes/s on a ~2-SNAP-server network |
| Frontier found after a large pivot gap | ~835K nodes, healed in one pass |

The levels grow roughly 16× per level down to the account-leaf band (L6–L7 on mainnet),
then collapse; storage-trie nodes are enqueued as account leaves are decoded, so the queue
counter for the next level keeps growing while a level is processed. Long stretches of
`0 frontier found` are normal — they mean the state really is complete in those regions.

### The HEAL-PULSE line

Logged every 2 minutes:

| Field | Meaning |
| --- | --- |
| `NN% (est)` | completed heal tasks vs known tasks — **not** walk progress |
| `healed=` | trie nodes downloaded and written so far this phase (cumulative) |
| `pending= active=` | heal tasks queued / requests in flight to peers |
| `peers=` | peers currently usable for healing |
| `rate=` | recent heal throughput (nodes/s) |
| `walkRunning=` | true while **any** frontier walk (rebuild or verification) is running |
| `pivotRefreshPending=` | a pivot refresh is waiting to be applied |

A pulse of `healed +0, pending=0, active=0` while `walkRunning=true` is **normal** — the
walk is in a fully-healed region and has nothing to hand to the downloader.

### The watchdog

If three consecutive pulses show no walk, no pending work, and no healing progress, the
dead-pulse watchdog force-starts a verification walk:

```
[HEAL-WATCHDOG] Dead pulse 3/3: walkRunning=false verifyRunning=false pending=0 active=0 healed=0 in last 2min
[HEAL-WATCHDOG] 3 consecutive dead pulses — force-starting verification DFS
```

Seeing this once after a restart is fine. Seeing it repeatedly means peers are not serving
(check `peers=` and pivot age).

## Configuration knobs

All under `fukuii.sync.snap-sync` (defaults from `conf/base/sync.conf`):

| Key | Default | What it does |
| --- | --- | --- |
| `healing-visited-cap` | `4000000` | Bound on the walk's LRU visited set (~80 B/entry). At the cap the **eldest entries are evicted** (never truncating the walk) at the cost of some re-walking of shared subtries. 4M ≈ 320 MB is right for a 5 GB heap. |
| `healing-frontier-persistence` | `true` | Layer-2 resume: persist discovered missing nodes to a RocksDB CF as the walk runs. On restart, a frontier with the **completeness marker** is resumed (skips the multi-hour walk); a partial frontier without the marker triggers a full re-walk (safe). |
| `healing-traversal-parallelism` | `4` | Sub-range workers per BFS level (large levels are split across the healing-writer thread pool). |

Restart semantics worth knowing:

- The walk's visited counter and progress are **per-walk**: a process restart re-walks from
  the root unless the completeness marker was set. Healed nodes are durable — every restart
  has *less* to heal even when it must re-walk.
- A pivot refresh clears the persisted frontier and marker (the target root changed). If a
  walk is running at that moment, verification is deferred until it finishes — never run
  two walks at once (they share the level queue).

## Dashboard

Grafana → **Fukuii SNAP Sync** → row **"State Healing — BFS frontier rebuild + heal"**:
nodes healed, frontier backlog, active requests, BFS visited counter (+5m rate), and a
walk-progress estimate (visited ÷ ~119M mainnet estimate — labeled estimate for a reason;
the counter resets when a new walk starts).

## Healthy vs. stuck

| Symptom | Verdict |
| --- | --- |
| `[HEAL-BFS] Level N:` lines advancing every minute or two, memory flat | Healthy — walk in progress |
| `healed +0` for hours **while** `walkRunning=true` and visited advancing | Healthy — walking a complete region |
| `frontier found` jumps by thousands, then `healed` climbs in bursts | Healthy — gap region found and being cured |
| Pulse `peers=0` or single digits for long stretches | Peer-starved: healing is network-bound; check discovery/firewall and pivot age |
| `[HEAL-WATCHDOG] … force-starting` repeating every few minutes | Stuck: peers won't serve the current root; expect a pivot refresh, or investigate peering |
| Two interleaved `Level N complete` streams with different sizes | Bug territory (two concurrent walks) — should be impossible since the single-flight gate; capture logs and file an issue |
| `OutOfMemoryError` during healing | Check `healing-visited-cap` × concurrent components vs heap; the walk itself is disk-backed and should not OOM |

## When it ends

Walk completes → residual frontier heals → a verification walk re-runs over the (possibly
pivot-rolled) root → finds nothing → `StateHealing` exits, SNAP sync finalizes, and the node
enters regular sync. From there, any stragglers are fetched on demand during block execution.
See [Sync Lifecycle](sync-lifecycle.md) for the full phase walk-through.
