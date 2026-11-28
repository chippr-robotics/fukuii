# Backlog drive plan: 021, TUI, TLS, SNAP, RPC, P2P, CI (2026-11-24)

The scope is the open tracker items outside spec 018 and unified storage, which the `cloud-dc960fdc` host owns. Every
item was checked against the code at staging `d4d679236`, not just against its tracker text. That host is also picking
up sync, consensus and rpc items in these areas (B-440..B-461), so claim before you start (`backlog.py claim`).

## 1. Status validation

### Close now (fixed, or settled by design)
| Item | Evidence |
|---|---|
| B-017 | `snap/StateValidator.scala:16-21` records the decision that a corrupt node is not a hole. `RecoveryScannersPositionSpec:352` pins it |
| B-091 | `DiscoveryRuntime.scala:16-36` gives discovery its own `discovery-compute` pool. The comment there cites the starvation incident |
| B-099 | `SnapResumePlanner.scala:521-523` leaves finished storage tasks alone (`storageDoneStorage.unfinished`), and is always on for Path |
| B-113 | `HelloExchangeState.scala:32` passes the Hello capabilities to SNAP and ETH negotiation |
| B-156 | `HealNoWalkCompletionSpec:395-411` counts the root asks as they arrive, with a deadline (CI c9d7716) |
| B-215 | Fixed by #1994 (8d4dd6974): each test has its own topic names |
| B-335 | Duplicate of B-350 (same spec, same cases; B-350 has the root cause) |

### Close after one live check
- **B-092:** #2007 (B-435) answers GetStatus in every state. Confirm on dev-node.
- **B-366:** T081 was met by dev-node's manual three-node run (user, 2026-11-20). Close it, unless an automated regression is wanted.

### Wontfix candidates (user call)
- **B-112:** the decoder takes no p2p version, but snappy, the only version-dependent behaviour, is handled in `MessageCodec`. No bug behind it.
- **B-114:** pre-EIP-8 handshake. geth still accepts both forms. Removing it gains nothing and risks interop.
- **B-115:** "consistent rate limiting" has no definition. Rewrite it as "batch requests are rate-limited, not 405", or drop it.
- **B-202:** no NaN source found in `PeerScore.scala`. Add one property test, then close it.

### Rescope (part done)
| Item | What is left |
|---|---|
| B-020 | Residual fixed waits only: AccountRangeCoordinatorSpec (11), StaleVerificationWalk (1), HealRequestFixtures 30 s poll |
| B-028 | Only the one-request-in-flight-per-peer limit and the 128 KB byte cap. Per-hash dedup and 256-hash batches are done |
| B-093 | Only the `admin_peers` names (`AdminService.scala:373`). The nodeInfo timeout is gone (`IO.pure`) |
| B-098 | Only the resume half: the pivot is reused up to `maxPivotStalenessBlocks = 4096`, against a 128-block serve window |
| B-101 | The BAL prefetch exists (`BalPrefetcher.scala`). Only the non-BAL IO strategy is left |
| B-188 | Only the E2EHandshakeSpec half. The FastSyncBranchResolver half → B-350 |
| B-195 | Confirmed: the recency step is 0.1/60 ≈ 0.0017 per minute, above the TUI's 0.001 comparison. Round, or compare at 2 dp |
| B-352 | Only 'save fetched node'. The deadline test was fixed by #2015 |
| B-374 | The EngineApiHttpServerObservationSpec fix (e1b5790b0) is **on main only**. Cherry-pick it to staging. JsonRpcHttpServerLifecycleSpec (22 waits) remains |

### Merge (one PR, one item)
- **B-180 + B-181 + B-166 (+ B-163):** simulate and trace replays call `BlockExecution.applyBlockPreamble`. The ordering bug goes away with it.
- **B-026 + B-171 + B-172:** error text and structLog parity in the same files. Re-pin the trace goldens once.
- **B-278 + B-279:** miner control with real state (error when there is no coordinator, respawn after a stop, a running flag).
- **B-021 + B-416:** scalanetDiscovery tests; neither suite runs in CI.
- **B-286 + B-288:** read the operator IP block from config, and enforce it in discovery.
- **B-140 + B-143:** both in `BlockImporter.handleOrphanAnnounce`.
- **B-419 + B-387:** JWT `exp` check plus the clock-edge audit.

### Status corrections
- **B-049 (021 S1), blocked → open.** Per plan.md Gates, B-046/B-047 gate S1's *merge* (GT-3 row 9, row 10b), not its code. Only the swap commit T035 waits for RP-2, which waits for T008.
- **B-404** (Chocolatey): the claim has been stale since 2026-11-20. Check moderation, then close it or re-heartbeat.
- **Stale spec headers.** These go in one docs PR, regenerating `specs/README.md`:
  - **021:** S0 merged as #1737 on 11-08.
  - **022:** slices 6a–6d merged as #1698–#1701. T062 passed. T063 is partial by the user's decision. T064 is deferred to 018. T065's sign-off is open.
  - **027:** slice 5 merged as #1789. Tick the T027/T028/T030/T058/T082/T107/T124/T150 bookkeeping boxes.

### Confirmed still open
Everything else in scope reproduced in the code. The items and their file:line references are in §2.

## 2. Workstreams

The owners are the specialist agents. Sizes come from the validation pass.

### WS-CI: first, because every other lane pays for red CI
| # | Item | Fix | Size |
|---|---|---|---|
| 1 | B-374 | Cherry-pick e1b5790b0's spec change to staging. Capture the Lifecycle spec's failure text when it recurs | S |
| 2 | B-417 | Coursier retry (`COURSIER_RETRY`, or a prefetch step with retry) before `formatCheck` (`ci.yml:100`) | S |
| 3 | B-352 | 'save fetched node' (`RegularSyncSpec:2929`, runs in testEssential): wait on the event | S |
| 4 | B-020 | Residual waits (see §1) | S |
| 5 | B-468 | After #2026 (B-256) merges: move `testing/LogCapture` and 28 addAppender sites to the shared turbo-filter capture | M |
| 6 | B-353 | Autofix pushes with a GitHub App token, or fails and asks for `formatAll` | S |
| 7 | B-174 | Hive: use `.github/actions/setup-sbt` (it has the fallback), retry the assembly step, warm `~/.sbt/boot` | S |
| 8 | B-206 | Make `TestMptStorage` incremental; saves about 3.5 min of Tier 1 | S |
| 9 | B-205, B-247 | Root guard; inject props into JdkHttpsTransportSpec (the `create(..., props)` seam exists) | S |
| 10 | B-350 (+B-188b), B-218 | SyncTest-tagged; they fail in snap-synctest.yml. Order the bus subscribe before the reply | M |
| 11 | B-021+B-416 | Fix, then add `scalanetDiscovery / test` to CI | M |
| 12 | B-368, B-429, B-018, B-188a, B-162, B-080 | Hygiene: TempDirs, a legacytests checkout in the nightly job, E2E test design, test gaps | S–M |
| — | B-096, B-157 | Flagged as unverified: reproduce once, else close | S |

### WS-RPC: geth parity (conduit; beacon/forge where marked)
| PR | Items | Note |
|---|---|---|
| R1 Engine answer shape | **B-198** (V2 all-or-nothing null; Lighthouse errors on Sepolia today), B-090 (-38004), B-419+B-387 | beacon. Do it first: B-198 is visible to the CL |
| R2 HTTP and error codes | B-025 (200, not 400), B-197 (missing state is -32000, not 3), B-093 names | Check hive rpc-compat's expectations first |
| R3 Trace and error-text parity | B-026+B-171+B-172 | Must land **before 021 S1**: S0's trace goldens lock in these divergences. Fix them and re-pin once, so S1 to S4 compare against the final goldens |
| R4 Simulate | B-180/181/166/163 (shared preamble), B-158, B-161, B-458, B-176, B-173 | beacon and forge: `applyBlockPreamble` is import code (`proposer-import-parity.md`). Then **B-072**, the live run of simulate-165 on Ephemery |
| R5 Head move and pool | B-245 (check newHeads on a post-merge node first; raise to P1 if silent), B-253 (FCU re-injects reversed txs, like BlockImporter:1927) | beacon |
| R6 Mining RPC | B-278+B-279 | forge |
| R7 Small | B-105, B-339, B-423 (beacon: `consensus/engine`), B-438 (fetch geth `miner/worker.go`) | — |
| Decision | B-210 (account reads above best) | conduit and beacon recommend; the user decides |
| Live | B-340 (hive engine cancun/prague/osaka) | Needs a hive host (dev-node) |

### WS-SNAP: correctness before polish (beacon for ETH, forge for ETC, vault where it writes)
1. **B-190, a confirmed data skip:** `next` advances before `StoreAccountChunk`, and the failure re-queue keeps it. Healing covers the gap, but it is still wrong. S.
2. **B-098 (the resume half):** cap the resume at the serve window (128), not 4096. S–M.
3. **Serve-window cluster, one design:** B-031 (roll at about 3/4 of the window, ERROR → WARN-once), B-033 (re-peg on the root's own age, not 2×64 since the last re-peg), B-032 (log the fetch root and its age), B-185 (CL-anchored serve and recovery roots post-merge). One beacon design note, then one PR. M.
4. **B-213:** SnapServer answers an unreadable node with an empty response, as geth does. It touches 018's StateFault, so coordinate with the 018 owner. S.
5. **B-191:** progress after a restart, and the storage phase starting before its tasks stream. Cosmetic and ordering only. S.
6. **Cleanup:** B-085 (rename, in a `refactor/snap-016-*` commit with `# moved:`), B-084 (with the 018 owner: db/storage). B-079 becomes moot when B-069 (C8a) lands.
7. **B-106** (work-stealing): a throughput feature, last.
8. **Spec 022 closure:** the T065 sign-offs (beacon, forge, vault, eye) and ticking #1671/#1659. The user decides whether T063's skipped 24 h head-follow is subsumed by the 018 C7 live gates. T064 and the T050a sub-boxes that touch storage move to 018. Then mark 022 Done.

### WS-P2P (herald)
- **Quick:**
  - **B-405:** a locally initiated UselessPeer disconnect waits out `disconnect-poison-pill-timeout` (15 s). Close the TCP connection after the Disconnect is flushed.
  - **B-030:** UDP send failures log at DEBUG or WARN, rate-limited, with no stack trace.
  - **B-199:** cap the Hello clientId length.
  - **B-237:** filter tcp=0 enodes in PeerDiscoveryManager.
  - **B-195:** the TUI trend noise.
- **Medium:**
  - **B-028:** one GetPooledTransactions in flight per peer, and the 128 KB cap.
  - **B-035:** re-walk the DNS tree every 30 minutes.
  - **B-286+B-288:** the IP block.
  - **B-140+B-143:** forge reviews the seal check and its cost under Ethash.
- **B-214:** delete the GetNodeData requesting side, after the dead-code-review verdict. M.

### WS-TLS: spec 030 follow-ups, one PR
- B-367: NodeAllowlistStore moves onto `tls/FilePoller`.
- B-397: read `release-tls-profile` before the Ephemery fetch.
- B-339: SecureRandomBuilder reads the instance config.
- B-366: close it (see §1).

### WS-EVM: spec 021 and the EVM items (forge and beacon, consensus protocol)
Two tracks run side by side:
- **Code track, can start now.** The pre-swap S1 tests T023–T031: the oracle copy, the `DifferentialTest` tag, the GT-2 generators, the allocation spec, the DC-3 and no-closures scripts. Draft the kernels T033/T034 on a branch.
- **Gate track, the long poles:**
  - T008 GT-1 part B: forge redacts it; the user approves.
  - T022 RP-2 sign-offs: beacon, forge, mithril.
  - B-047 (T002, Mordor 0–10M export) and B-046 (T004/T005, ETC datadir copy and the H+1..+5000 export), on dev-node at planned stops.
  - Build jar A from today's staging.
  - T006, row 10b.
  - T020/T021, the S0 baseline on a quiet measurement host.
  - T001, T003, T007, T009: the remaining prerequisites.
- **Order:** WS-RPC R3 (trace parity), then T035 (the swap, after RP-2), then the S1 merge (after GT-2/GT-3), then S2 (B-050), S3 (B-051), S4 (B-052).
- **The other EVM items:**
  - B-094 goes away with S1, which removes the lazy `bytes`.
  - B-137 (text) and B-155 (config-gated KZG, TuiRestore on halt): small, before S1.
  - B-103 (CREATE and CALL gas dedup) waits until after S3, to avoid churning the opcodes being converted.
  - B-108 (fork registry) comes after 021, folding into spec 024 (chain registry).
  - B-101: the non-BAL IO design.
- **B-151 (P1):** no backend work exists; the handoff is to dev-node. The user decides: a holder for blst or a pure-Java backend, or an "ETH not supported on Windows" release note.
- **B-284:** the PoW tie rule is the user's decision (the core-geth rule, or keep the strict `>`).

### WS-TUI: specs 027 and 029 follow-ups (prism)
- **Quick:**
  - B-399: the shutdown NPE when the TUI probe was never set. Null-guard it.
  - B-192: read disk-free on a daemon thread.
  - B-267: keep a scroll offset in lists.
  - B-331: an ASCII ellipsis, and regenerate the goldens.
  - B-265: say on the Logs view that the log level hides lines.
- **Medium:**
  - B-259: free the least recently used images in pixel mode.
  - B-287: report the UPnP outcome (PortForwarder records it).
  - B-263: pixel charts.
- **Spec 027 closure:**
  - Tick the merged-slice boxes.
  - T122: beacon and forge review the CL and mining panels.
  - T125: check the docs.
  - T126: one `essential` run.
  - **B-189:** the user's manual UAT.
  - T127: close the spec.

## 3. Sequencing

| Wave | When | Content | Exit |
|---|---|---|---|
| 0 | Day 0 | Tracker hygiene (§1), the spec-header docs PR, the B-374 cherry-pick | Tracker matches the code |
| 1 | Days 1–3 | WS-CI items 1–6. RPC R1 (B-198). SNAP B-190. TUI quick. P2P quick. TLS PR | "Test and Build" green 5 runs in a row on staging |
| 2 | Week 1–2 | RPC R2, R3, R6, R7. SNAP B-098 plus the serve-window design. 021 code track and T008/T022 requests. P2P medium. CI 7–12 | R3 merged (unblocks the 021 swap) |
| 3 | Week 2–4 | RPC R4 and R5. The SNAP serve-window PR, B-213, cleanup. 021 S1 swap and gates (ops data from dev-node). B-214 | 021 S1 merged. 022 closed |
| 4 | Live (dev-node/user) | B-072, B-340, B-245 check, B-092, B-189 UAT, 021 GT-3/GT-4, the 022 T063 decision | Specs 022 and 027 Done |

Lane parallelism is safe except in these places:
- R3 and the 021 S0 goldens touch the same trace goldens.
- R4 touches import code (`BlockExecution`), so it goes in a forge+beacon review slot.
- B-213 and B-084 touch 018-owned storage, so coordinate them with the 018 host.

## 4. Decisions (user, 2026-11-24)
1. **B-151:** build the Windows BLS backend (not a release note). It needs a holder.
2. **B-284:** adopt core-geth's tie rule. Raised to P2; a consensus change, forge first.
3. **B-210:** follow core-geth's answers for a held block above best.
4. **Wontfix:** B-112, B-114 and B-115 are dropped. B-202 gets a property test, then closes.
5. **B-366:** closed as met by the manual three-node run.
6. **Spec 022 T063:** the skipped 24 h head-follow is covered by spec 018's C7 live gates (recorded in 022's header).
7. **B-023:** close #1571 and #1660 on GitHub.

## 5. Wave 0 record (2026-11-24)
- **Tracker:** one commit on `tracker`.
  - Closed as done: B-017, B-091, B-099, B-113, B-156, B-215, B-366.
  - Dropped: B-335, B-112, B-114, B-115, and the merged losers (B-181 and B-166 into B-180; B-171 and B-172 into B-026; B-278 into B-279; B-416 into B-021; B-286 into B-288; B-143 into B-140; B-387 into B-419).
  - Rescope and decision notes added. B-049 moved from blocked to open. B-284 raised to P2.
- **PR #2029:** this plan, the spec 021/022/027 header refresh (B-469), and the B-374 cherry-pick of e1b5790b0's ObservationSpec wait to staging.
- **Not done in wave 0:** B-404's stale claim (another host's Chocolatey moderation; check it with that host), and ticking spec 027's merged-slice exit boxes, which needs each box's exit criteria checked first.
