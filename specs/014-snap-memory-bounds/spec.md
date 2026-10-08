# Feature Specification: Bound In-Memory SNAP Work and Add a Heap Watchdog

**Feature Branch**: `fix/snap-memory-bounds`

**Created**: 2026-10-08

**Status**: Implemented (CI-validated; not yet soaked)

## Problem

During the SNAP storage phase the heap grows without a ceiling, and on a 16 GB host it drives systemd-oomd to
kill the node. This must hold up on ETH mainnet, which has many times Sepolia's contracts, so every bound has to be
a fixed size, independent of the chain.

## Evidence

Sepolia, v0.9.6, `-Xmx6g`, restarted 2026-10-08 06:37. The account resume checkpoint carried 12,001,666
storage-task entries.

- **Live set.** After GC the live set was 1.6–1.75 GB and climbing. The top of the class histogram:
  9.55M `byte[]` (647 MB), 9.39M `ByteString1C` (150 MB) and 2.23M `StorageTask` (89 MB). There were also 191,830
  live `IOFiber`s, with matching `TracingEvent[]`/`RingBuffer`, `IOCont` and `CallbackStack` counts.
- **Queue.** The StorageRangeCoordinator queue held 2.37M pending tasks, roughly 400 B each, so about 900 MB.
- **Watermark timeline** (`fukuii.log`):
  - 06:38:19: replay starts.
  - 06:46:06: replay *finishes* (2,507,635 tasks queued).
  - 06:46:09: storage back-pressure ENGAGED at 100,278.
  - 06:49:14: the account coordinator *receives* ENGAGED, with 2.2M already queued.

  The pause signal took 3 minutes to cross two mailboxes. In that time the controller was doing 2.55M RocksDB reads
  on its own thread for replayed codeHashes. Every pivot refresh then force-releases back-pressure, and it re-engages
  3 ms later.
- **Fibers.** A class histogram 78 minutes after the restart (`-all`, no forced GC) showed 35,612
  `StaticUDPPeerGroup$ChannelImpl`, 31,543 `DiscoveryNetwork` channel handlers and 224,735 `IOFiber`.

## Root causes

1. **Open-loop back-pressure.** The producers (the account dispatch, the carried replay at 4096 entries every 50 ms,
   and the accounts-complete recovery `Future`) are paused by a message that goes coordinator → controller → account
   coordinator. Work already in those mailboxes is never counted, and the recovery stream has no gate at all.
2. **Discovery fiber leak.** The CE3 port of scalanet lost the `messageExpiration` read timeout in
   `DiscoveryNetwork.handleChannel` (its `TimeoutException` recover is still there). As a result:
   - Every remote UDP address keeps a server channel, a queue and several parked fibers for the life of the node.
   - `CloseableQueue.next` returns `None` forever after close, and the stream looped on it.

## Requirements

- **R1 — closed-loop intake gate.** `SnapIntakeBudget` counts storage tasks and codeHashes as *in transit* (reserved
  by the producer when it sends) plus *queued* (published by the coordinator). Producers read it synchronously before
  they add work. They pause while the count is at the ceiling or the heap watchdog is engaged. Nothing is dropped:
  producers keep their cursors.
- **R2 — every producer gated.**
  - Fresh account-range dispatch.
  - The carried replay (`replayCarriedChunk`).
  - The accounts-complete recovery streams for storage tasks and codeHashes. These are now `GatedTaskFileReplay`:
    bounded chunks, re-checked by the scheduler, with no blocked thread.
- **R3 — fail safe, fail loud.** Overcounting would wedge intake, so every imprecise path errs low:
  - Counters clamp at 0.
  - A coordinator start or restart resets its counts.
  - The controller releases work it drops.
  - In-transit work that no consumer acknowledges for 120 s is written off with a WARN.
  - The gate state is printed in `[ACCOUNT-IDLE]`.
- **R4 — heap watchdog.**
  - It sets the old-gen `MemoryPoolMXBean` collection-usage threshold and listens for
    `MEMORY_COLLECTION_THRESHOLD_EXCEEDED`.
  - A 5 s daemon poll handles release.
  - It engages when post-GC old-gen occupancy reaches `pause-fraction` of the max heap.
  - It releases when min(post-GC, current) occupancy is at or below `resume-fraction`. Current usage is never below
    the live set, so a stale post-GC reading cannot pin the pause.
  - Both transitions log at WARN with the queue sizes.
- **R5 — resume guarantees unchanged.**
  - `AccountResumeCheckpoint` (#1487) and the done-markers (#1498) are untouched.
  - The replay reads the same entries `[0, carried*Count)` once, with the same done-marker and Hash-scheme root
    filters.
  - `replayDone` still gates account completion, which in turn gates checkpoint removal.
- **R6 — metrics.** `snapsync.memory.{storage,bytecode}.pending`, `snapsync.memory.intake.paused`,
  `snapsync.memory.heap.pressure`, `snapsync.memory.oldgen.postgc.{bytes,ratio}`, and
  `snapsync.{storage,bytecode}.inflight.requests` (gauges).
- **R7 — discovery.** Restore the idle eviction of discv4 server channels (`messageExpiration`, 1 min), and end the
  stream when the queue closes.

## Configuration (`sync.snap-sync`)

| Key | Default | Why |
|-----|---------|-----|
| `max-pending-storage-tasks` | 200000 | About 400 B per task, so about 80 MB. Storage drains a few thousand tasks/s, so this is tens of seconds of buffer. |
| `max-pending-bytecode-hashes` | 200000 | About 100 B per hash, so about 20 MB. Counted in hashes, not 85-hash tasks. The old 50k-*task* watermark was 4.25M hashes. |
| `heap-watchdog-enabled` | true (conf) | The case-class default is false, so unit tests do not install JVM-wide JMX listeners. |
| `heap-watchdog-pause-fraction` | 0.75 | With `-Xmx6g` the healthy live set is about 28%. 75% post-GC (4.5 GB live) is close to GC thrash. |
| `heap-watchdog-resume-fraction` | 0.60 | A 15-point band, so the watchdog does not flap. |
| `heap-watchdog-poll-interval` | 5 s | Release latency. Engagement is immediate through the JMX notification. |

## Out of scope

- RocksDB native memory (spec 015) and the p2p crawler eviction.
- The existing 100k/50k storage and 50k/25k bytecode watermarks are kept, and they still feed the account
  coordinator's `backpressureSources`. Wiring them to config is deferred.
- The pivot-refresh force-release is unchanged. Under the gate it can no longer overfill the queue.
