# Feature Specification: SNAP Resume Re-queues Only Unfinished Storage Tasks

**Feature Branch**: `fix/snap-resume-skip-finished-storage`

**Created**: 2026-10-07

**Status**: Implemented (task #96)

## Problem

A SNAP account-phase resume (AccountResumeCheckpoint, #1487) replays the carried storage-task file to the storage
coordinator. Nothing records which of those tasks already finished, so every carried task is downloaded again. The
Hash scheme partly avoids this (a storage root node present by hash means the trie was completed); the Path scheme,
used by Sepolia and ETH mainnet, has no such check. The accounts-complete recovery path has the same problem: it
streams the whole persisted storage-task file (and the whole codeHash file) back into the coordinators.

## Evidence

Sepolia, v0.9.1, restart 2026-10-07 20:53: the checkpoint carried 9,350,287 storage-task entries. After
`Replayed carried contract work: 9350287 storage-task entries` the StorageRangeCoordinator `pending` count went from
~89k (before the restart) to ~1.91M. Roughly 818k tasks finished before the restart were queued and fetched again.

## Requirements

- **R1 — completion record.** Each storage task that finishes is recorded durably as `accountHash ++ storageRoot`
  (`SnapStorageDoneStorage`, keys prefixed `SnapStorageDone/` in the app-state column family). A resume, whether it
  replays carried task files or streams the accounts-complete file, re-queues only tasks that have no record.
- **R2 — crash consistency.** A record never becomes durable before the data it vouches for. It is written in the
  same RocksDB WriteBatch as the account's last flat slots, after the trie nodes are committed, and only when no
  earlier flat-slot batch is still in flight. A failed batch turns records off for that coordinator. Give-up,
  max-empty skip, force-complete and root-mismatch completions are never recorded. A crash before the record lands
  costs one re-download, which is what happens today.
- **R3 — scope and lifetime.** Records count for one SNAP cycle only. They are cleared when the account phase starts
  without carried task files, and when the storage phase completes (persisted).
- **R4 — compatibility.** `AccountResumeCheckpoint` is unchanged and stays at version 1. An old checkpoint has no
  records yet, so the first restart replays everything, exactly as today. No new column family is added. RocksDB
  will not open a database unless every column family it contains is listed, so a new one would stop a downgrade to
  an earlier 0.9.x build from starting. An older binary never reads the prefixed keys.
- **R5 — bytecode.** CodeHashes already in EvmCodeStorage are skipped. The carried replay already did this
  (controller filter). The accounts-complete recovery stream now does it too.

## Not done: Path-scheme root-presence skip (first restart from an old checkpoint)

Not implemented, because there is no cheap check that is provably safe. In the Path scheme a storage root node with
the expected hash at the account's root path only shows that some download of that trie committed at some point:

1. Path-keyed storage holds one node per path. A later partial download for the same account (a different root after
   a pivot change, or the re-download a previous resume already started) overwrites descendants and leaves the root
   alone. The Path-scheme healing walk stops at any node whose hash matches, so it would never repair them.
2. Flat slots are flushed asynchronously in batches. The trie root can be on disk while the account's last flat-slot
   batch was lost in the crash. Healing works on the trie and does not restore flat slots, but SLOAD and SNAP serving
   read them.

The only way to know more is to walk the whole trie, which costs as much as the download. R1 covers every restart
after the first one.

## Acceptance

- A resume after a simulated restart replays only the carried tasks that have no record. A record for the same
  account under a different root does not count (AccountRangeCoordinatorSpec).
- With no records (old checkpoint, first restart), every carried task is replayed (AccountRangeCoordinatorSpec).
- A finished account's record is not durable until it commits together with its flat slots. It is held back while an
  earlier batch is in flight, never written after a batch failure, and never written for a root mismatch
  (StorageRangeCoordinatorSpec).
- Records commit atomically with the flat slots and require the same data source. `clear` removes all of them and
  leaves neighbouring app-state keys alone (SnapStorageDoneStorageSpec, real RocksDB).

## Out of scope

Storage-cursor (mid-account) resume for very large contracts, in which a partly downloaded account restarts from slot
0. Bitmap-over-task-file-index alternative: it needs an index on StorageTask and generation fencing across copied
prefixes, and is rejected as more invasive. Healing-phase changes. Any ETC/Hash-scheme behaviour change beyond the
added skip.
