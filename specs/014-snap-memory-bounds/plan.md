# Implementation Plan: SNAP Memory Bounds (spec 014)

## Approach

The gate is a credit counter shared through atomics, not a pull feed owned by the coordinator.

A pull design would move the carried-replay reader into StorageRangeCoordinator. That reader works on the account
coordinator's own copy of the carried prefix (`contractStorageFile`, copied when the coordinator is built). The
controller may delete the source files once the new checkpoint points at the copy. The reader also drives
`replayDone`, the gate on account completion and therefore on removing the checkpoint. Moving it would reopen
the #1487/#1498 resume guarantees.

The credit counter leaves every reader where it is. It only changes *when* a reader may read, and the producer
answers that question synchronously.

## Components

| File | Change |
|------|--------|
| `snap/SnapIntakeBudget.scala` (new) | Atomic in-transit + queued counters per kind, a heap-pressure flag, stale write-off and metrics. |
| `snap/SnapHeapWatchdog.scala` (new) | `HeapPressureHysteresis` (pure), `OldGenReading`, the JMX threshold listener and a daemon poll. |
| `snap/GatedTaskFileReplay.scala` (new) | Chunked, gated reader for fixed-size-entry task files. |
| `snap/StorageTaskFile.scala` | `foreachEntry` moves here from AccountRangeCoordinator, shared by both readers. |
| `snap/StorageTask.scala` | Full-range `next`/`last` boundaries are shared singletons, saving 2 ByteStrings per task. |
| `actors/AccountRangeCoordinator.scala` | `intakeGateClosed` gates `dispatchIfPossible` and `replayCarriedChunk`, and counts as a deliberate pause (not a stall). Sends reserve. |
| `actors/StorageRangeCoordinator.scala` | Attach on start, `storageReceived` on `AddStorageTasks`, queue depth on dispatch and checks, in-flight gauge. |
| `actors/ByteCodeCoordinator.scala` | Same as storage, counted in hashes. `AddByteCodeTasks(skipPresent)`: the presence check for replayed codeHashes moves here from the controller thread. Skipped hashes are reported as progress. |
| `SNAPSyncController.scala` | Owns the budget and the watchdog (started with the coordinators, stopped on completion or stop). Releases dropped `IncrementalContractData`. Recovery streams become `runGatedReplay`. Config. |
| `SNAPSyncMetrics.scala` | 8 gauges. |
| `conf/base/sync.conf` | New keys, documented. |
| `scalanet/.../v4/DiscoveryNetwork.scala` | `nextChannelEvent.timeout(messageExpiration)` + `unNoneTerminate`. |

## Invariants (preserved)

1. The carried replay emits entries `[0, carriedStorageCount)` and `[0, carriedCodeHashesCount)` exactly once,
   in order, with the same done-marker and Hash-scheme root filters. A closed gate only delays the next chunk
   (`ReplayPausedRetry`, 1 s).
2. `replayDone` gates account `CheckCompletion` → `AccountRangeSyncComplete` → checkpoint removal. This is unchanged.
3. The recovery stream sends `NoMoreStorageTasks` / `NoMoreByteCodeTasks` only after the last chunk. On failure it
   logs at ERROR and sends neither, so the phase cannot complete on a partial stream.
4. No work is dropped by the gate. The only drops are the existing no-coordinator and idle drops, and they now
   release their credit.

## Failure analysis

- **Undercount** (a reset on restart, unreserved healing bytecodes, a stale write-off): one extra chunk gets
  through. That chunk is bounded.
- **Overcount** (a message lost to a stopped coordinator in a behaviour that drops with `case _`): the 120 s
  stale write-off clears it with a WARN.
- **Heap pressure from non-SNAP memory**: intake stays paused (WARN logged every transition), consumers keep
  draining, and the account stall watchdog treats it as a deliberate pause. The sync stalls visibly rather than
  OOMing.

## Validation

- No local JVM build (the soak host is shared with the live Sepolia node). CI runs `compile-all`, scalafmt and the
  Tier 1/2 tests.
- Unit tests: `SnapIntakeBudgetSpec`, `SnapHeapWatchdogSpec`, `GatedTaskFileReplaySpec`.
- Soak follow-up: restart the Sepolia node on the new jar with the same checkpoint. Expected results:
  - `snapsync.memory.storage.pending` stays at or below 200k plus one chunk.
  - The live set stays flat.
  - `IOFiber` count is in the low thousands.
