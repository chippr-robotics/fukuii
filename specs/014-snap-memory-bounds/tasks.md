# Tasks: SNAP Memory Bounds (spec 014)

- [x] T001 Root-cause the 190k live fibers. Found 35,612 `ChannelImpl` and 31,543 `DiscoveryNetwork` handlers in a live
  histogram. Discovery server channels are never evicted.
- [x] T002 Restore the discv4 server-channel idle timeout and stop on a closed queue (`DiscoveryNetwork.scala`).
- [x] T003 Root-cause the 2.37M pending storage tasks. The watermark signal arrived 3 min late over two mailboxes,
  and the recovery stream was ungated.
- [x] T004 `SnapIntakeBudget`: in-transit + queued credit, clamping, attach reset, stale write-off, heap flag, metrics.
- [x] T005 Gate account dispatch and the carried replay. Reserve on every `IncrementalContractData` send.
- [x] T006 Storage/bytecode coordinators acknowledge receipts and publish queue depth. Bytecode is counted in hashes.
- [x] T007 Move the replayed-codeHash presence check off the controller thread (`AddByteCodeTasks.skipPresent`).
- [x] T008 Controller releases dropped work. Recovery streams become `GatedTaskFileReplay` + `runGatedReplay`.
- [x] T009 `SnapHeapWatchdog`: old-gen collection-usage threshold + JMX listener + daemon poll, pure hysteresis core.
- [x] T010 Config keys under `sync.snap-sync` and documented defaults in `base/sync.conf`.
- [x] T011 Metrics: pending storage/bytecode, in-flight requests, intake paused, heap pressure, old-gen post-GC.
- [x] T012 Share the full-range boundaries in `StorageTask.createStorageTask`.
- [x] T013 Tests: `SnapIntakeBudgetSpec` (ceiling, in-transit, release, attach, stale write-off, heap).
- [x] T014 Tests: `SnapHeapWatchdogSpec` (engage, band, release, stale post-GC, watchdog → gate).
- [x] T015 Tests: `GatedTaskFileReplaySpec` (chunks, order, gate pause/resume, bound under a slow consumer).
- [x] T019 (review) Account-side liveness: `RecheckIntakeGate` timer while the gate holds dispatch; test that dispatch
  resumes without PeerAvailable.
- [x] T020 (review) Watchdog escape: ineffective-pause WARN plus a bounded force-release (`heap-watchdog-max-pause`).
  The G1 engage lag is documented.
- [ ] T016 Soak: Sepolia restart from the 12M-entry checkpoint on the new jar. Record the pending-task gauge, the
  live set and the fiber count.
- [ ] T017 (deferred) Wire the legacy storage/bytecode watermarks to config, or retire them in favour of the gate.
- [ ] T018 (deferred, herald) Run the scalanet unit tests in CI. The existing "close idle channels" spec covers T002,
  but no CI tier runs it.
