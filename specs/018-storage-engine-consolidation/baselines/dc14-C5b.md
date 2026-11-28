# DC-14 figures, C5b part 3: the recovery scans by position (T221, EG-8)

Recorded 2026-11-18 by `vault-b065c` (B-065) in a cloud session. No file of this name has a template elsewhere in
`baselines/` (no `dc14-C*.md` is in the tree yet), so the layout is the list EG-8 names: the figures a live host
produces are **pending**, and what a unit-level measurement can say is recorded here with its method.

## What part 3 changes on the read path

`StorageRecoveryActor`, `BytecodeRecoveryActor`, `CombinedRecoveryScanner` and its actor read the state through
`RecoveryScanStores.forRoot`. With a position store (the unified layout) that is `DirectRowMptStorage`: each node of the
anchor's tries is one point read of CF `t` or `u` by position (`rawRowsAt`, no cache fill), verified against the hash
its parent names; a storage trie's root is a presence read (`holdsNodeAt`, one raw row, nothing decoded). With no
position store (Hash scheme, guard off) it is `getBackingStorage(number)` as before, and the presence read is
`holdsNodeAt` over `rawNodes`, as B-034 did for the others.

Nothing here runs on the import, Engine API or RPC path: the scans are a recovery phase, off the actors' threads.

## Measured here (test level)

Method: a throwaway spec (not committed; timings are not asserted anywhere) built a state of 20,000 accounts, 2,000 of
them contracts with a 3-slot storage trie, so 26,922 account-trie rows and 35,318 rows in all, over `EphemDataSource`
(no disk, no block cache: it isolates the CPU and allocation of the read path, not RocksDB). `CombinedRecoveryScan.scanFrom`
over the whole state, 3 warm-up runs, then the median of 7, one thread, allocation from the thread's
`getThreadAllocatedBytes`. JDK 21, 5 GB heap, shared 15 GB machine with another build running: read the times as
rough, the allocation as steadier.

| Store read through | median wall | min / max | allocated (median) | storage tries missing |
| --- | --- | --- | --- | --- |
| hash store (`ReferenceStore`, the Hash scheme's shape) | 61 ms | 59 / 144 ms | 127 MiB | 0 |
| position view before part 3 (`PathStateStorage.readOnly`, `getAt` per node, decoding get of each storage root) | 63 ms | 48 / 75 ms | 111 MiB | 0 |
| direct rows after part 3 (`DirectRowMptStorage`, `holdsNodeAt` for each storage root) | 71 ms | 67 / 78 ms | 123 MiB | 0 |

So on a store with no disk the direct reads cost about 13 % more CPU than the view and 11 % more allocation (a
`PositionKey` and a one-element batch per node, where the view reads through its own row cache). The ranges overlap
the hash store's. At mainnet scale the scan is bound by RocksDB point reads (about 86 M accounts), which neither read
path batches, so this figure says the wrapper is not the cost; it does not say what the disk does.

## Not measurable here (pending, live host)

| Figure | Status |
| --- | --- |
| p50 and p99 `[IMPORT-TIMING]` during a recovery scan | pending: needs a live node on the unified layout (EG-4, EG-5) |
| longest read wait on the state lock | pending: the scans take no lock (reads are not under the state lock; a move ends the scan as failed) |
| bytes written per block, database size | not changed by this part: the scans write only `RecoveryProgress` in AppState, as before |
| RSS, `[STORAGE-PERF]` | pending: live host |
| scan time over a Mordor or Sepolia state, direct rows against the view | pending: the RocksDB cost of the per-node read |
| the comparison with the pinned 0.12.1 jar's verdicts | pending: follow-up on B-053 (the jar is not runnable in the cloud session). `RecoveryScansVerdictSpec` pins the verdicts against today's hash-layout scan instead |
