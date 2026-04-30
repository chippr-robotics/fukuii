# SNAP Sync Current State — may-fields

**Branch:** `may-fields` (tracks upstream/main)
**Upstream HEAD at branch creation:** `306580dd3`
**Last updated:** 2026-04-30

---

## Status Summary

**HEAD:** `eb2272fb1` — 60 commits above upstream/main

**BUG-006 OPEN. BUG-007, BUG-BC1, BUG-008, BUG-009, BUG-010, BUG-011, BUG-012 FIXED.** See BUG_LOG.md.

**Phase 2 audit COMPLETE** (all Groups A–H from merry-roaming-falcon plan — 2026-04-30).
**Phase 3 test suite COMPLETE: J4 → J1 → J2 → J3 → J5 → J6 → J7 → J9 (2026-04-30).** 133 new tests, all passing.
**D1 stale walk epoch fix COMMITTED (`169c4b64c`) — walkGeneration counter, all 4 invalidation sites.**
**Fast-sync high-water mark + legacy bootstrap seed COMMITTED (`bed0ef512`, `7ab05832f`).**

**JAR:** `target/scala-3.3.7/fukuii-assembly-0.2.7.jar` — needs rebuild from `eb2272fb1`.

**RocksDB state:** WIPED (2026-04-27). Previous run had `SnapSyncDone=true`, bestBlock=24447667
from a prior successful SNAP run, but healing was incomplete — storage node `05975df2` for
account `fc27cd13` was missing. RegularSync blocked permanently. Local Besu cannot serve
GetNodeData (BONSAI storage — no hash-keyed trie nodes). RocksDB wiped for clean restart.

**Next milestone:** Run a fresh SNAP sync end-to-end: accounts → storage → bytecode → healing
(complete, no pivot refresh mid-heal) → RegularSync → chain head.

---

## What Upstream/main Has

SNAP sync has a substantial foundation already committed:

### Main SNAP Files (22 files)
```
blockchain/sync/snap/
├── SNAPSyncController.scala          # 2,882 lines — main state machine
├── SyncProgressMonitor.scala         # Progress display + Prometheus metrics
├── SNAPRequestTracker.scala          # Request ID tracking, timeout management
├── MerkleProofVerifier.scala         # Verify SNAP proof responses
├── StorageTask.scala                 # Storage task definition
├── ChainDownloader.scala             # Parallel header/body download during SNAP
├── AccountTask.scala                 # Account task definition
├── ByteCodeTask.scala                # Bytecode task definition
├── HealingTask.scala                 # Healing task definition
├── PeerRateTracker.scala             # Per-peer rate tracking
├── StateValidator.scala              # Post-sync state validation
└── actors/
    ├── AccountRangeCoordinator.scala    # Account download orchestration
    ├── AccountRangeWorker.scala         # Individual account request worker
    ├── StorageRangeCoordinator.scala    # Storage download (1,185 lines)
    ├── StorageRangeWorker.scala         # Individual storage request worker
    ├── ByteCodeCoordinator.scala        # Bytecode download orchestration
    ├── ByteCodeWorker.scala             # Individual bytecode request worker
    ├── TrieNodeHealingCoordinator.scala # Post-download trie repair
    ├── TrieNodeHealingWorker.scala      # Individual healing request worker
    └── Messages.scala                   # All coordinator message types

network/snapserver/SnapServer.scala      # Serving SNAP to peers
network/p2p/messages/SNAP.scala         # Protocol messages
```

---

## Commits on may-fields (43 above upstream/main)

### SNAP Sync Bug Fixes (all from 2026-04-25 first run)

| Commit | Description |
|--------|-------------|
| `029385886` | BUG-001: BlockchainReader pivot-body absence → DEBUG (was ERROR) |
| `1da249c37` | BUG-005: Per-connection log noise → DEBUG, 60s network summary |
| `e944b2718` | BUG-004: Cancel in-flight SNAP requests immediately on peer disconnect |
| `2cf5608ca` | BUG-003: StorageRangeCoordinator idle escape valve + pendingTaskKeys dedup |
| `0c967ed88` | BUG-003 (part 2): Guard storage stall when maxInFlightPerPeer=0 |
| `7b6620ac7` | BUG-002 (visibility): Aggregate empty-headers counter into 60s summary |

### Additional SNAP / Protocol Fixes

| Commit | Description |
|--------|-------------|
| `34e905712` | BUG-BC1: ByteCodeWorker stuck in working state for 30s after each response |
| `5f43a83a5` | Accept empty account/storage range as valid SNAP response |
| `04b51e789` | ByteCode coordinator peer tracking and consecutive failure guard |
| `5c0751de3` | Healing coordinator pendingHashSet invariant + idle stagnation |
| `0c967ed88` | Suppress false storage stall during account sync phase |
| `8b6b5a4c5` | ETH/69 peers are always SNAP-capable — force supportsSnap=true |
| `e5b00ee7c` | Guard genesis fallback when peer heights are unknown at startup |

### Peer Management Fixes

| Commit | Description |
|--------|-------------|
| `e78646c9e` | Accept all handshaked peers in GetHandshakedPeers response |
| `090fd0462` | Track ETH/69 peer height via BlockRangeUpdate messages |
| `725221f2e` | Reconnect maintained peers that fail pre-handshake TCP connection |
| `6d631653a` | Skip blacklisting for maintained peers on disconnect |
| `cb4ffc025` | Exempt maintained and trusted peers from incoming peer pruning |
| `4469917a3` | Exempt maintained peers from inbound max-peer limit |
| `fe441c907` | Reduce handshake mismatch logs from WARN to DEBUG |

### RegularSync Healing Fixes (pre-emptive — Phase 2)

| Commit | Description |
|--------|-------------|
| `7ffd36048` | Use SNAP GetByteCodes for missing contract code recovery |
| `725aaea47` | Route GetByteCodes→ByteCodes in PeersClient request dispatcher |
| `162baf81e` | Prevent state node request multiplication + extend retry timeout |
| `5363fcecc` | Fall back to GetNodeData after 5 consecutive empty SNAP responses |
| `83ea16988` | Revert SkipBlock; MaxStateNodeRetries=10 (formula: 5+5); restore 5-min backoff |
| `ba3cc9d84` | StateNodeFetcher peer rotation: BestSnapPeerExcluding / BestNodeDataPeerExcluding |

### Infrastructure / Config

| Commit | Description |
|--------|-------------|
| `76af524a2` | Enable admin namespace in default JSON-RPC APIs |
| `1a9c6cfe3` | Truncate log files on each startup |
| `c2e1763f5` | Redirect JVM temp files to datadir/tmp |
| `efd5ed1d4` | Add local/ working directory to gitignore |
| `307d56d32` | Filter port-0 nodes from Neighbors responses (BUG-DISC-001) |
| `5ff2f040c` | Add research workflow protocol to CLAUDE.md |

---

## Known Gaps / Work Needed

### Phase 2 Audit Complete (2026-04-30)
Full historical audit of march-onward + april-confluence bug branches against may-fields.
**Result:** ~35 N/A (already fixed by architecture), 3 code fixes, 5 deliberate keeps, 3 deferred.

**Key architectural confirmations (won't regress):**
- ETH64+ peers correctly skip EtcForkBlockExchange — ForkId (EIP-2124) used instead (A4)
- Peer blocking is bounded per-task cooldown, never permanent IP block (C5/C6/C7)
- Stagnation watchdogs escalate to controller, never mass-abandon tasks (H2)
- Adaptive request sizing bounded by `minResponseBytes` floor — no runaway tiny requests (H4)
- Phase completion flags persisted; all phases resume correctly on restart (F2/F3/F4/F5)
- `blockchainWriter.storeBlockHeader().commit()` is atomic for pivot storage (E3/E4)

**Phase 2 fixes committed (2026-04-30, 4 new commits):**
- BUG-009 `BlockFetcher` reorg peer blacklist — FIXED `fe1df878a`
- B2(1) storage recovery `accountHash != emptyHash` guard — FIXED `e283cf326`
- E1-W6 two-commit pivot/stateRoot write — FIXED `e283cf326` (atomic via `.and()`)
- F1 consumedKeyspace reset on restart — FIXED `f7b35bd6f` (derived from checkpoints)
- B6 large-storage range splitting — N/A (continuation handles; adaptive budget bounded)
- B8 bytecode single-peer stall — N/A (time-based expiring cooldowns, not permanent)

### Phase 3: Test Suite (Group J) — COMPLETE (2026-04-30)

| Item | Status | Commits | Tests Added |
|------|--------|---------|-------------|
| J4 — ETH69 handshake (BlockRangeUpdate + supportsSnap) | ✅ DONE | `c38813414` | +3 (EtcPeerManagerSpec, EtcHandshakerSpec) |
| J1 — Worker actors (zero → full coverage) | ✅ DONE | `a652e7e7b` | +24 (4 new files) |
| J2 — Coordinator depth (StorageRange + TrieNodeHealing) | ✅ DONE | `0163b98ba` | +7 (StorageRangeCoordinatorSpec, TrieNodeHealingCoordinatorSpec) |
| J3 — SNAP message round-trips (2 → 18 tests) | ✅ DONE | `7c3773698` | +16 (SNAPMessagesSpec) |

**91 total tests added across Phase 3. All passing.**

**Additional J-group tests from extended session (2026-04-30):**

| Item | Status | Commit | Tests Added |
|------|--------|--------|-------------|
| J5 — Range math (AccountTask/StorageTask/HealingTask) | ✅ DONE | `df2621264` | +33 (3 new files: 11+12+10) |
| J6 — Merkle proof gaps (monotonic ordering, bounds) | ✅ DONE | `df2621264` | +7 (MerkleProofVerifierSpec) |
| J7 — Peer reputation cleared on pivot refresh | ✅ DONE | `eb2272fb1` | +1 (ByteCodeCoordinatorSpec) |
| J9 — Corruption detection (hash mismatch → cooldown) | ✅ DONE | `eb2272fb1` | +1 (ByteCodeCoordinatorSpec) |

**42 additional tests.** Running total: 133 new tests across all J-groups.

Remaining (J8 state machine, J10 persistence) deferred until after next mainnet sync run.

### Under Investigation
- [ ] Does stored SNAP progress correctly resume from persisted account state?
- [ ] Does BUG-002 behavioral backoff need implementation? (Per-peer skip on empty GetBlockHeaders)

### Not Yet Analyzed
- [ ] Are the 6 core-geth SNAP serving improvements in our SnapServer?
- [ ] Does ChainDownloader handle PoW pivot selection correctly?
- [ ] Does healing handle large path sets efficiently?

---

## Active Bugs

> **BUG-006 OPEN** (incomplete healing → missing storage nodes in RegularSync — wipe + re-SNAP required).
> BUG-007, BUG-BC1, BUG-008 fixed. BUG-009 deferred. See BUG_LOG.md.

---

## Next Steps

**Immediate (before next sync run):**
1. `sbt assembly` — rebuild JAR from `eb2272fb1` (D1 fix + fast-sync fixes + all J-group tests)
2. Wipe RocksDB (BUG-006 — incomplete healing from prior run)
3. Start node — confirm SNAP resumes cleanly from genesis

**During sync run:**
4. Monitor account download — watch for idle-escape-valve false triggers (BUG-003 fix)
5. Watch bytecode phase — confirm no coordinator stall (BUG-BC1 fix)
6. Watch storage phase — confirm storage dedup + idle escape correct
7. Watch healing phase — full trie walk with no pivot refresh mid-heal
8. Monitor RegularSync transition — confirm BlockFetcher doesn't deplete peers on first reorg

**After sync:**
9. Fix BUG-009 (BlockFetcher reorg blacklist) if it fires during RegularSync
10. J5–J10 test gaps (range math, proof coverage, state machine, corruption, persistence) — deferred

**If new bugs found:** document in BUG_LOG.md using the template at the bottom, check april-confluence for prior fix, verify against Besu + go-ethereum, implement.

---

## Session Log

| Date | Work Done |
|------|-----------|
| 2026-04-25 | Created may-fields branch from upstream/main (306580dd3). Set up ./local/ context system. Wrote reference docs for all 5 clients. First ETC mainnet SNAP run: 442K accounts / 0.5% keyspace in 2.7 min before manual stop. Documented BUG-001 through BUG-005. Fixed all 5 bugs same day. Added SNAP, peer management, and protocol fixes (43 total commits). |
| 2026-04-27 | Session context lost; re-oriented from ./local/ files. Added RegularSync healing fixes (SkipBlock revert, MaxStateNodeRetries=10, 5-min backoff, peer rotation) as pre-emptive Phase 2 work. Updated BUG_LOG.md and CURRENT_STATE.md. JAR rebuilt. Ready to resume SNAP sync. |
| 2026-04-29 | Historical bug audit (merry-roaming-falcon plan). Fixed BUG-BC1 (ByteCodeWorker stuck in working state for 30s per response — coordinator never sent Release signal). P1 bugs assessed: C5/C6 bounded (not permanent IP block), C7 handled via cooldown, E5 N/A, D2/D3 already guarded, D5 N/A. D1 (stale walk epoch) is a recoverable low-impact scenario. B5/H5 handled. P2 audit in progress. |
| 2026-04-30 | **Phase 2 audit COMPLETE.** All Groups A–H assessed across march-onward + april-confluence. Fixed BUG-008 (PivotHeaderBootstrap ask-timeout stall — `.recover` before `.foreach`, commit `8bfff9936`). Corrected A4 from "BUG PRESENT" to N/A — ETH64+ bypass EtcForkBlockExchange via ForkId. Key findings: ~35 N/A (architecture already correct), 3 code fixes total (B7+A5+A4-correction), 5 deliberate keeps (C11/H1/H2/H4/H5), 3 deferred (G3 reorg blacklist, B2 empty-hash guard, E1 two-commit pivot writes). BUG_LOG.md updated with BUG-008 (fixed) and BUG-009 (deferred). Updated ./local files. Phase 3 next: J4 (ETH68/69 tests) + J1 (worker actor tests). |
| 2026-04-30 | **Phase 3 test suite COMPLETE (J4 → J1 → J2 → J3).** 91 tests added. J4: ETH69 BlockRangeUpdate (EtcPeerManagerSpec) + supportsSnap forced for ETH69 (EtcHandshakerSpec). J1: 4 new worker spec files — AccountRangeWorker (7 tests), ByteCodeWorker (7), StorageRangeWorker (5), TrieNodeHealingWorker (5). J2: StorageRangeCoordinatorSpec 5→8 tests (sentinel pattern, AddStorageTasks, PivotRefreshed); TrieNodeHealingCoordinatorSpec 6→9 tests (HealingForceComplete, HealingPivotRefreshed deferred, pending-task no-complete). J3: SNAPMessagesSpec 2→18 tests (all 8 message type round-trips, code verification, malformed RLP). HEAD: `7c3773698`. JAR needs rebuild. |
| 2026-04-30 | **Extended session (context continued).** D1 stale walk epoch fix: added `walkGeneration: Long` counter to SNAPSyncController; all 4 TrieWalk* private messages carry generation tag; 4 invalidation sites (startTrieWalk, triggerHealingForMissingNodes, completePivotRefreshWithStateRoot, restartSnapSync); stale results discarded silently. Fast-sync fixes: high-water mark prevents 95%-complete false-positive after JVM bounce; legacy bootstrap seeds from max(maxTotalNodesCount, totalNodesCount) for backward compatibility. J5 range math tests: AccountTaskSpec (11), StorageTaskSpec (12), HealingTaskSpec (10). J6 Merkle proof gaps: 7 new tests in MerkleProofVerifierSpec (monotonic ordering, bounds, proof-of-absence, malformed). J7+J9: ByteCodeCoordinatorSpec expanded to 12 tests — pivot refresh clears peer cooldown (J7), corrupted bytecode hash mismatch rejected + peer in cooldown (J9). 149/149 snap tests passing. HEAD: `eb2272fb1`. JAR needs rebuild from `eb2272fb1`. |
