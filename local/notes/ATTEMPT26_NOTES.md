# Attempt 26 Session Notes — 2026-04-25

## Current Status (as of 04:45 UTC)
- **Phase:** AccountRangeSync — RUNNING
- **Pivot:** 24435932 (state root `4043723e...`)
- **Progress:** ~1.1M accounts (~1.2% keyspace), ~3,900 accounts/sec
- **Peers:** 5 active, 4 snap-capable, but **only 1 actually serving** (3 marked stateless immediately)
- **Estimated completion:** ~6 hours at current rate (single serving peer bottleneck)

---

## Issues Needing Attention

### ISSUE-1 — BUG-B2 resume threshold too tight (lost 3.13M accounts again)

- Attempt 26 started fresh from 0 accounts, did NOT resume from Attempt 25's 3.13M account checkpoint
- `[SNAP-STATE]` at 04:40:47: `pivot=none` — stored pivot was cleared because the restart took ~3 minutes and networkBest advanced ~136 blocks past the saved pivot (24435839)
- Plan says `MaxPreservedPivotDistance=256` blocks, but the actual implemented threshold appears to be ~96–128 blocks — needs verification in `SNAPSyncController.scala`
- **Action needed:** Find the actual constant value and either increase it to 256 or make it configurable in `sync.conf`
- `grep -n "MaxPreservedPivotDistance\|preservedPivot\|pivotAge" SNAPSyncController.scala`

### ISSUE-2 — Besu BONSAI snap server fundamentally broken for historical roots

- Besu (peer `235067...`) reconnects every ~15 seconds throughout the session
- Pattern: connects → GetAccountRange → TCP disconnect (BONSAI cannot reconstruct historical trie roots)
- Every pivot tested this session disconnected besu: 113 blocks behind head, 71 blocks behind, now 64 blocks behind
- Besu is configured as a static peer, so fukuii keeps re-assigning it as a snap worker and absorbing the reconnect overhead
- **Action needed (low priority):** Either remove besu from fukuii's static peer list during SNAP sync, or accept the reconnect noise. BONSAI limitation — no code fix available without changes to besu's snap server.

### ISSUE-3 — core-geth marked stateless immediately on every restart

- Peer `b81f5e...` (core-geth) marked stateless for the new pivot root at 04:41:08 — within milliseconds of account sync start
- core-geth runs in FULL mode with `--snap-server` enabled, but its snapshot database may not be rebuilt for the specific pivot root on each restart
- This is the same issue as Attempt 25: local snap server peers don't survive restarts
- **Action needed:** After restarting core-geth, wait ~5 minutes for snapshot rebuild before starting fukuii, OR investigate whether core-geth can be pinned to the exact pivot block's snapshot

### ISSUE-4 — ETH69 8-field RLP decode error (BUG-S4, noisy)

- Peer at `3.92.198.194:30303` sends 8-field ETH69 Status; fukuii expects 7-field
- Logged at ERROR level at 04:41:04: `Cannot decode ETH69.Status from: RLPList(8 fields)`
- Correct behavior (disconnects), but noisy — this is a recurring peer
- **Action needed:** Downgrade from ERROR to WARN, or add 8-field pattern match (BUG-S4 from plan)

### ISSUE-5 — Pivot changed between restarts (24435911 → 24435932)

- Attempt 26 restart selected pivot 24435932 (not 24435911 from Attempt 25)
- State root changed to `4043723e...` (was `cd90d435...`)
- Not a bug — expected when networkBest advances. But means any per-root peer caching is invalidated
- Low priority; documenting for completeness

---

## Session Timeline (key events)

| Time (UTC) | Event |
|---|---|
| 04:35:11 | Fukuii restarted (full restart: fukuii + core-geth + besu) |
| 04:38:30 | Stall observed — 2 snap-capable peers, 0 progress |
| 04:40:47 | Fukuii restart #2 (clean DB start confirmed) |
| 04:41:04 | ETH69 8-field decode error from 3.92.198.194 |
| 04:41:08 | Pivot selected: 24435932, state root `4043723e` |
| 04:41:08 | core-geth + 2 other peers immediately marked stateless (3/4) |
| 04:41:08 | Account download started from 0 (NOT resumed from 3.13M checkpoint) |
| 04:41:34 | First progress report: 101K accounts, ~3,886/sec, 1 serving peer |
| 04:44:51 | [ACCOUNT] 1% milestone hit (859,697 accounts) |
| 04:45:27 | ~1.01M accounts (1.2%), ~3,900/sec — running steadily |

---

## What's Working

- Download is progressing steadily at ~3,900 accounts/sec
- BUG-B1 confirmed working again: no infinite pivot retry loop on restart
- Phase 1 fixes (BUG-H1, BUG-M1, BUG-M2, BUG-M3, CFG-1) all in place
- Phase 2 fixes (BUG-S1, BUG-S2, CFG-2, BUG-S3) all in place
- 4 snap workers dispatching against the 1 available serving peer

---

## Throughput Context

- **Current:** ~3,900 accounts/sec, 4 workers, 1 serving peer
- **Attempt 25 peak:** ~12,500 accounts/sec (multiple serving peers)
- **Bottleneck:** Only 1 external snap peer is serving this pivot root's state
- Throughput will improve as more peers with this root join (normal for fresh pivot)
- If serving peer count doesn't improve within 1–2 hours, consider a manual pivot refresh

---

## Next Actions on Return

1. Check if BUG-B2 threshold constant — find in code and increase to 256 blocks
2. Consider removing besu from static peer list for SNAP phase (stops reconnect noise)
3. If account phase is still running, let it complete — do NOT interrupt unless stalled >30 min
4. Phase 2 bugs (BUG-S1 through BUG-S4) should be verified once storage phase starts
5. BUG-H3 (TD estimation) deferred — still fine to defer post-Attempt-26

---

## Progress Log (appended by monitor)

| Time (UTC) | Accounts | Keyspace | Rate | Serving Peers |
|---|---|---|---|---|
| 04:45:04 | 910K | 1.1% | 3,860/sec | 1 |
| 04:51:01 | 2.33M | 2.7% | 3,921/sec | 1 |
| 04:52:12 | 2.57M | 3.0% | — | 1 |
| 04:54:44 | 3.14M | 3.7% | 3,844/sec | 1 |
| 04:57:56 | 4.30M | 5.0% | 4,287/sec | 2 ← second peer joined |
| 04:59:43 | 5.06M | 5.9% | 4,536/sec | 2 |

### Notable: Throughput improvement at ~5%
- A second snap-capable peer began serving around 04:57 UTC
- Rate climbed from ~3,860 → ~4,536 accounts/sec (+17%)
- Network briefly showed only 2 active peers total (04:58:45, 04:59:45) — peer churn but both serving
- If a third peer joins, rate could approach Attempt 25 peak (~12,500/sec)
| 05:04:44 | 6.87M | 8.0% | 4,858/sec | 3 ← third peer joined |
| 05:07:31 | 7.74M | 9.0% | — | 2 |
| 05:09:54 | 8.50M | 9.9% | 4,922/sec | 2 |
| 05:10:13 | 8.59M | 10.0% | — | 2 |
| 05:14:59 | 10.32M | 12.0% | 5,079/sec | 2 ← rate broke 5K/sec |
| 05:32:10 | 17.19M | 20.0% | 5,614/sec | 2 |
| 05:33:45 | 17.70M | 20.6% | 5,605/sec | 2 (rate plateau ~5,610) |
| 05:43:52 | 20.43M | 23.8% | 5,427/sec | 2 (gradual rate decline from 5,613 peak) |

### Note: Gradual rate decline post-20%
- Rate declined from peak 5,613 (at ~20%) to 5,427 (at ~23.8%) — ~3.3% over 20 minutes
- Consistent ~20-30 acc/sec drop per 4.5-min check window
- Likely cause: trie data density increasing in this keyspace range (denser storage assignments)
- Not a stall risk; monitoring for stabilization or continued decline
| 05:47:55 | 21.49M | 25.0% | 5,364/sec | 2 (rate decline slowing, stabilizing ~5,360) |
| 05:53:39 | 23.06M | 26.8% | 5,300/sec | 2 (last pre-gap entry) |

### Note: 5.5-minute progress gap 05:53:39 → 05:59:19 (range boundary transition)
- No "Account download progress" for ~5.5 minutes; process alive ([ACTOR-ALIVE] firing every 60s), peers stable at 2 snap-capable
- NOT a stall — resolved naturally at 05:59:19 without intervention
- Rate reset to ~4,930/sec after the gap (down ~7% from ~5,300 pre-gap)
- Cause: likely a range boundary — workers exhausted one range bucket and waited for new range assignments
- Pattern to watch: if similar gaps recur and grow longer, may indicate range task drought (BUG-S2-adjacent in account phase)
| 05:59:19 | 23.16M | 26.9% | 4,937/sec | 2 (resumed after range boundary gap) |
| 06:00:29 | 23.46M | 27.3% | 4,928/sec | 2 (new rate plateau ~4,930) |
| 06:04:55 | 24.68M | 28.7% | 4,909/sec | 2 (plateau confirmed ~4,910) |
| 06:08:35 | 25.79M | 30.0% | 4,915/sec | 2 ← 30% milestone |
| 06:09:43 | 26.09M | 30.4% | 4,908/sec | 2 (rate holding ~4,910) |
| 06:13:39 | 27.20M | 31.7% | 4,900/sec | 2 (very slow continued decline, ~4,900) |
| 06:19:01 | 28.62M | 33.3% | 4,873/sec | 2 (gradual decline continuing, ~27 acc/sec per 5-min window) |
| 06:23:48 | 29.83M | 34.7% | 4,843/sec | 2 (decline continuing, ~30 acc/sec per 5-min window) |
| 06:28:45 | 31.55M | 36.7% | 4,886/sec | 2 ← rate decline reversed, ticking up ~4,843→4,886 |
| 06:33:46 | 33.17M | 38.6% | 4,908/sec | 2 (rate recovery continuing, back to ~4,910 plateau) |
| 06:37:55 | 34.38M | 40.0% | 4,907/sec | 2 ← 40% milestone |
| 06:38:43 | 34.59M | 40.2% | 4,902/sec | 2 (rate stable ~4,910, recovery complete) |
| 06:42:51 | 35.70M | 41.5% | 4,888/sec | 2–3 (new very slow decline, ~14 acc/sec per 4-min window) |
| 06:48:03 | 37.01M | 43.1% | 4,860/sec | 2 (decline continuing, ~28 acc/sec per 5-min window) |
| 06:53:02 | 38.23M | 44.5% | 4,830/sec | 2 (steady decline, ~30 acc/sec per 5-min window since 30%) |
| 06:54:51 | 38.67M | 45.0% | — | 2 ← 45% milestone |
| 06:57:57 | 39.44M | 45.9% | 4,804/sec | 2–3 (decline continuing ~26 acc/sec per 5-min) |
| 07:02:49 | 40.66M | 47.3% | 4,782/sec | 2 (decline slowing, ~22 acc/sec per 5-min) |
| 07:07:52 | 41.87M | 48.7% | 4,755/sec | 2 (decline persisting, ~27 acc/sec per 5-min) |
| 07:12:16 | 42.97M | 50.0% | 4,739/sec | 2 ← 50% milestone (HALFWAY) |
| 07:12:39 | 43.08M | 50.1% | 4,739/sec | 3 (rate stabilizing — flat for last 3 readings) |
| 07:17:59 | 44.90M | 52.2% | 4,771/sec | 2 ← rate recovering again (4,739→4,771), same wave pattern |
| 07:21:07 | 46.12M | 53.7% | 4,804/sec | 2 ← **1/4 ranges DONE** (first range completed) |
| 07:23:00 | 46.92M | 54.6% | 4,831/sec | 2 (rate recovery strong, 4,771→4,831 over 5 min) |
| 07:28:57 | 49.15M | 57.2% | 4,881/sec | 2 (recovery continuing, now 4,831→4,881, +50/sec in 5 min) |
| 07:32:53 | 50.36M | 58.6% | 4,887/sec | 2 (rate plateau stabilizing ~4,888, recovery wave flattening) |
| 07:35:37 | 51.07M | 59.4% | 4,878/sec | 2 (last pre-gap entry — range boundary transition starting) |
| 07:41:20 | 51.17M | 59.5% | 4,732/sec | 2 (resumed after 5.7-min gap — rate reset ~4,731, same boundary pattern as 05:53 gap) |
| 07:47:03 | 52.38M | 61.0% | 4,696/sec | 2 (slow decline continuing, ~4,700 plateau, -35/sec from pre-gap) ← 60% milestone |
| 07:51:44 | 53.40M | 62.1% | 4,669/sec | 2 (steady decline ~27 acc/sec per 5-min window, consistent pattern) |
| 07:56:54 | 54.51M | 63.4% | 4,640/sec | 2 (decline continuing ~29 acc/sec per 5-min, no sign of reversal yet) |
| 08:01:57 | 55.62M | 64.7% | 4,616/sec | 2 (decline slowing — ~24 acc/sec per 5-min, approaching floor) |
| 08:06:34 | 56.73M | 66.0% | 4,602/sec | 2 (decline nearly flat — ~14 acc/sec per 5-min, rate stabilizing ~4,600) ← 2/3 done |
| 08:09:04 | 57.24M | 66.6% | 4,588/sec | 2 ← **2/4 ranges DONE** (second range completed, no gap this transition) |
| 08:11:52 | 57.85M | 67.3% | 4,574/sec | 2 (rate still declining slowly ~14/sec per 5-min, plateau not yet) |
| 08:16:50 | 58.96M | 68.6% | 4,555/sec | 2 (decline persisting, ~19 acc/sec per 5-min — slightly faster than 14, not yet floor) |
| 08:21:38 | 59.97M | 69.8% | 4,532/sec | 2 (decline continuing ~23 acc/sec per 5-min, slow but persistent) |
| 08:24:08 | 60.47M | 70.4% | 4,519/sec | 2 ← 70% milestone |
| 08:26:59 | 61.08M | 71.1% | 4,507/sec | 2 (decline ~12 acc/sec per 5-min, rate approaching new floor ~4,500) |
| 08:31:55 | 62.09M | 72.2% | 4,484/sec | 2 (decline ~23 acc/sec per 5-min — floor not yet reached, still drifting down) |
| 08:35:37 | 62.90M | 73.2% | 4,471/sec | 2 (rate stabilizing — 3 consecutive readings at 4,479/4,479/4,479 before 4,471; plateau forming ~4,470) |
| 08:41:04 | 64.01M | 74.5% | 4,446/sec | 2 (decline still slow ~25 acc/sec per 5-min, plateau not confirmed — watching) |
| 08:43:15 | 64.47M | 75.0% | — | 2 ← **75% milestone** ([ACCOUNT] 75% log fired) |
| 08:45:23 | 64.92M | 75.5% | 4,430/sec | 2 (rate flattening — 4,434/4,434/4,432/4,430 across 4 readings; plateau forming ~4,430) |
| 08:51:43 | 65.13M | 75.8% | 4,331/sec | 2 (resumed after 6.3-min gap — rate reset ~4,331, gap #3 confirmed same pattern) |
| 08:54:36 | 65.73M | 76.5% | 4,322/sec | 2 |
| 09:00:53 | 66.95M | 77.9% | 4,295/sec | 2 (slow decline continuing, ~7 acc/sec per 6-min window — very gradual) |
| 09:05:59 | 67.96M | 79.1% | 4,276/sec | 2 (decline continuing, ~19 acc/sec per 5-min window — slightly faster, consistent wave) |
| 09:10:06 | 68.77M | 80.0% | 4,261/sec | 2 ← **80% milestone** |
| 09:15:50 | 69.88M | 81.3% | 4,239/sec | 2 (slow decline persisting, ~22 acc/sec per 5-min window) |
| 09:20:40 | 70.89M | 82.5% | 4,226/sec | 2 (decline flattening — ~13 acc/sec per 5-min, approaching plateau) |
| 09:25:59 | 72.00M | 83.8% | 4,212/sec | 2 (decline nearly flat — ~14 acc/sec per 5-min, plateau consolidating ~4,210) |
| 09:30:47 | 73.01M | 84.9% | 4,201/sec | 2 (very flat — ~11 acc/sec per 5-min, stable plateau ~4,200; no reversal yet) |
| 09:35:36 | 74.03M | 86.1% | 4,189/sec | 2 (plateau holding — 3 consecutive readings at 4,192/4,192/4,190; essentially flat, ~12 acc/sec per 5-min) |
| 09:41:06 | 75.14M | 87.4% | 4,174/sec | 2 (very slow decline — ~15 acc/sec per 5-min, plateau drifting down ~4,175; still no reversal) |
| 09:45:34 | 76.05M | 88.5% | 4,163/sec | 2 (decline persisting at same rate — ~11 acc/sec per 5-min; 2/4 ranges still, long tail on 3rd range) |
| 09:50:43 | 77.06M | 89.7% | 4,148/sec | 2 (slow drift continues — ~15 acc/sec per 5-min; rate stabilizing ~4,148; 3rd range still active) |
| 09:52:19 | 77.36M | 90.0% | — | 2 ← **90% milestone** ([ACCOUNT] 90% log fired) |
| 09:54:07 | 77.67M | 90.4% | 4,135/sec | 3 ← **3/4 ranges DONE** (smooth transition — NO gap this time, only 43s between last 2/4 and 3/4 reports) |
| 10:02:50 | 78.78M | 91.7% | — | — ← **In-place pivot refresh** (both peers stateless root d38e8932 → pivot 24437270, root dd274662) |
| 10:06:36 | 79.28M | 92.2% | 4,060/sec | 2 (resumed after refresh; actual rate ~2,300/sec, rolling avg inflated; still 3/4, 0 active) |
| 10:11:03 | 79.94M | 93.0% | 4,036/sec | 2 ← **93% milestone** — steady decline continuing, no new pivot events |
| 10:16:58 | 80.80M | 94.0% | 4,010/sec | 2 ← **94% milestone** — stable decline ~15/sec per 5-min, 3/4 ranges still (long 4th range tail) |
| 10:21:53 | 81.66M | 95.0% | — | 2 ← **95% milestone** ([ACCOUNT] 95% log fired) |
| 10:22:06 | 81.71M | 95.1% | 3,994/sec | 2 (decline continuing ~16/sec per 5-min; 3/4 ranges still; StorageCoordinator at 511K tasks) |
| 10:22:45 | 81.81M | 95.2% | 3,991/sec | 2 (stable; ETA ~17 min to 100% at current rate; 512K storage tasks queued) |
| 10:27:07 | 82.52M | 96.0% | 3,975/sec | 2 ← **96% milestone** — rate decline very slow (~3/sec per reading); ~14 min to 100% |
| 10:27:50 | 82.62M | 96.1% | 3,971/sec | 2 (steady; 3/4 ranges still; expect 4/4 gap ~97-99% before 100%) |
| 10:32:41 | 83.37M | 97.0% | — | 2 ← **97% milestone** ([ACCOUNT] 97% log fired) |
| 10:33:01 | — | 97.1% | — | — ← **Third in-place pivot refresh** (root be1e9a43 → 3d9afe40, block 24437333 → 24437396; resolved in ~4s) |
| 10:33:05 | 83.43M | 97.1% | 3,950/sec | 2 (resumed after refresh; ~10 min to 100%; 3/4 ranges still) |
| 10:38:04 | 84.24M | 98.0% | 3,933/sec | 2 ← **98% milestone** — 3/4 ranges STILL done (4th range active since 90.4%, now 44+ min) |
| 10:38:37 | 84.34M | 98.1% | 3,932/sec | 2 (~7 min to 100%; still no 4/4 signal; ETA ~10:46 UTC) |
| 10:43:10 | 85.05M | 98.9% | 3,915/sec | 2 (tasks completing every ~2-3s; 72.8M contract accounts identified; 533K storage tasks queued) |
| 10:43:26 | 85.10M | 99.0% | — | 2 ← **99% milestone** ([ACCOUNT] 99% log fired) |
| 10:43:47 | 85.15M | 99.1% | 3,913/sec | 2 (~3 min to 100%; ETA ~10:47 UTC; tasks firing every 2-3s at 8K each) |
| 10:48:33 | 85.86M | 99.9% | 3,894/sec | 2 (final stretch — tasks completing every 2-3s) |
| 10:49:07 | 85.96M | 100.0% | — | 2 ← **[ACCOUNT] 100% — 85,955,557 accounts** |
| 10:49:11 | — | — | — | — ← **4/4 ranges DONE** (`Account range COMPLETE: [c000000e...c0000000]`) |
| 10:49:11 | — | — | — | — ← **AccountRangeCoordinator STOPPED**: 85,958,781 accounts, 73,548,127 contracts, 36,985 unique codeHashes |
| 10:49:31 | — | — | — | — ← **ISSUE-8: ByteCode + Storage force-completed** (see note below) |
| 10:51:55 | — | — | — | — ← **StateHealing phase STARTED** (trieWalkInProgress=true) |
| 10:52:56 | — | — | — | 2 ← Healing active: 83,778 healed, 306,865 pending (and growing); StorageRangeCoordinator recovery running concurrently |
| 10:51:43 | — | StateHealing | HEAL-PULSE: healed=51,971, +51,971/2min, pending=149,922, 2 peers | 2 |
| 10:53:42 | — | StateHealing | HEAL-PULSE: healed=95,835, +43,864/2min, pending=439,101, 2 peers | 2 |
| 10:55:42 | — | StateHealing | HEAL-PULSE: healed=128,741, +32,906/2min, pending=799,754, 2 peers | 2 ← ~954K nodes total queued; rate declining as trie fan-out grows |
| 10:57:43 | — | StateHealing | HEAL-PULSE: healed=198,996, +70,255/2min, pending=876,424, 2 peers | 2 ← healing rate 4× jumped; response batch 37→147 nodes |
| 10:59:43 | — | StateHealing | HEAL-PULSE: healed=268,831, +69,835/2min, pending=856,785, 2 peers | 2 ← **pending PEAKED at 876K, now declining** — trie walk nearing end |
| 11:01:14 | — | StateHealing | healed=315,436, pending=871,603, 147 nodes/response | 2 ← slight oscillation but total queued growth slowing (~1.19M total) |
| 11:05:43 | — | StateHealing | HEAL-PULSE: healed=420,835, +45,276/2min, pending=902,609, 2 peers | 2 |
| 11:06:03 | — | StateHealing | **LAST REAL HEAL**: healed=426,680 (76 nodes) — frozen since | 2 ← **ISSUE-9 starts here** |
| 11:07:43 | — | StateHealing | HEAL-PULSE: healed=426,680, +5,845/2min (tail of last real batch), pending=904,370 | 2 ← **0-of-0 stall confirmed** |
| 11:11:43 | — | StateHealing | HEAL-PULSE: healed=426,680, **+0/2min**, pending=905,138, **active=0**, 2 peers | 2 ← stall persists; unproductiveRounds=0 |
| 11:13:43 | — | StateHealing | HEAL-PULSE: healed=426,680, **+0/2min**, pending=905,138, **active=0**, 2 peers | 2 ← 7+ min frozen; ChainDownloader 19% headers |
| 11:14:15 | — | ChainDownload | headers=4,977,664/24,437,460 (20%), bodies=50, peers=1 | — ← regular sync running concurrently, ~90 min to 100% headers |
| 11:17:43 | — | **HEAL-FORCE-COMPLETE** | BUG-M3 escape fired: "No active requests for 10 minutes with 905,138 pending tasks" → HealingForceComplete | 2 ← [HEAL] idle escape worked |
| 11:17:43 | — | **SNAP COMPLETE** | `SNAP sync completed successfully at block 24437460` (hash=5a1e5d766b3e88bc) | — ← **Attempt 26 SNAP DONE** |
| 11:17:43 | — | SNAP Summary | accounts=85,958,781, bytecodes=345, slots=2,533,434, nodes=426,680, elapsed=23,815s (6h37m) | — |
| 11:17:43 | — | RegularSync | Starting regular sync from block 24437460 | — |
| 11:17:44 | — | **ISSUE-10: Block import failure** | Block 24437463 failed — missing contract code `32be9ea2` (contract `0xfc27cd13`) | — ← ISSUE-8 consequence |
| 11:18:12 | — | ISSUE-10 | StateNodeFetcher gave up after 4 retries; BlockImporter backing off 300s | — ← retry at ~11:23:12 |
| 11:23:12 | — | ISSUE-10 retry 1 | Block 24437463 failed again; also "gas mismatch on 24437462 but no missing code"; branch resolution attempted (47 blocks) — all fail | — |
| 11:23:50 | — | ISSUE-10 | GetNodeData failed again (4 retries, ~32s); backing off 300s | — ← retry 2 at ~11:28:50 |
| 11:28:51 | — | ISSUE-10 retry 2 | Identical failure; **also**: block 24437462 "gas mismatch, no missing code" — state trie node missing | — |
| 11:29:20 | — | ISSUE-10 | GetNodeData failed (retry 2); backing off 300s | — ← retry 3 at ~11:34:20; **CONFIRMED PERMANENT STALL** |
| 11:34:20 | — | ISSUE-10 retry 3 | New variant: "Branch resolution hit floor at 24437462, no importable blocks" + same 32be9ea2 failure | — |
| 11:34:51 | — | ISSUE-10 | GetNodeData failed (retry 3, ~29s); backing off 300s | — ← retry 4 at ~11:39:51 |
| 11:39:51 | — | ISSUE-10 retry 4 | Same pattern; gave up at 11:40:27 | — |
| 11:40:27 | — | ISSUE-10 | GetNodeData failed (retry 4, ~36s); backing off 300s | — ← retry 5 at ~11:45:27 |
| 11:45:27 | — | ISSUE-10 retry 5 | Same pattern; gave up at 11:46:04 | — |
| 11:46:04 | — | ISSUE-10 | GetNodeData failed (retry 5, ~37s); backing off 300s | — ← retry 6 at ~11:51:04 |
| 11:51:04 | — | ISSUE-10 retry 6 | Gas mismatch cascade: 24437463 → 24437462 → 24437461; branch resolution 47 blocks; same 32be9ea2 failure | — |
| 11:51:40 | — | ISSUE-10 | GetNodeData failed (retry 6, ~36s); backing off 300s | — ← retry 7 at ~11:56:40 |
| 11:56:40 | — | ISSUE-10 retry 7 | "Branch resolution hit floor" + 24437463 fail + 32be9ea2 missing code → GetNodeData | — |
| 11:57:10 | — | ISSUE-10 | GetNodeData failed (retry 7, ~27s); backing off 300s | — ← retry 8 at ~12:02:10 |
| 12:02:10 | — | ISSUE-10 retry 8 | Same cascade; branch resolution + 32be9ea2 missing code → GetNodeData | — |
| 12:03:20 | — | ISSUE-10 | GetNodeData failed (retry 8, ~28s); backing off 300s | — ← retry 9 at ~12:08:20 |
| 12:03–12:25 | — | ISSUE-10 retries 9–12 | Retries every ~5:30 (300s backoff + ~30s attempt). Identical pattern each time. BlockFetcher buffer growing: 456 ready blocks, known top 24437917. 2 peers stable. | — |
| 12:25:16 | — | ISSUE-10 | GetNodeData failed (retry 12, ~28s); backing off 300s | — ← retry 13 at ~12:30:16 |
| 12:25–12:42 | — | ISSUE-10 retries 13–15 | Pattern unchanged. BlockFetcher buffer growing: 567 ready blocks, known top 24438028 (~570 blocks ahead of stuck importer). 2 peers stable throughout. | — |
| 12:42:28 | — | ISSUE-10 | GetNodeData failed (retry 15); backing off 300s | — ← retry 16 at ~12:47:28 |

### Note: 3/4 range transition at 09:54:07 — no gap
- Previous transitions had ~5-6 min gaps (05:53, 07:35, 08:45). This one was instant (~43s between last 2/4 and first 3/4 report)
- Possible explanation: 4th range was already being loaded or the boundary fell at a natural batch boundary
- Rate at transition: 4,135/sec (vs 4,878 at 1/4 transition, 4,887 at 2/4 transition — lower base due to sustained decline wave)
- Now on final range (4/4) — **expect ~3-6 min gap before 4/4 completes or before 100% keyspace coverage**

### Note: StorageRangeCoordinator pre-loading while accounts still at 91% (09:58)
- `StorageRangeCoordinator` started queuing storage tasks at 09:58:16 while AccountRangeSync still active (91.1% keyspace)
- ~490K storage contract tasks already queued by 09:59:14, accumulating at ~50 tasks per few seconds
- This is the concurrent loading pattern — storage tasks being pre-queued from already-downloaded accounts
- Phase is still `AccountRangeSync` per [ACTOR-ALIVE] at 09:58:53 — storage dispatch not started yet

### ISSUE-8 — ByteCode and Storage phases force-completed prematurely at 10:49:31 (CRITICAL)
- Account phase completed at 10:49:07 (100% keyspace, 85,958,781 accounts). AccountRangeCoordinator stopped at 10:49:11.
- **ByteCode phase**: Started but immediately hit 20+ consecutive task failures ("SNAP peers not serving data"). Force-completed at 10:49:41, abandoning 10,196 pending bytecodes.
- **Storage phase**: Started but hit 20→107 consecutive timeouts. Force-completed in multiple waves at 10:49:31-10:51:41. Total storage downloaded: ~300-400K slots out of 537K tasks (remainder abandoned).
- **Root cause**: After account download completed, the peers (b81f5e... and 16264d...) were unresponsive to storage/bytecode requests for ~20 seconds. Likely: pivot root changed (third in-place refresh at 10:33:01) and peers needed to reconnect for the new storage root `3d9afe40`.
- **BUG-S1 pattern** (plan doc): `ByteCodePivotRefreshed` clears `knownAvailablePeers` — peers must re-advertise availability. If they're slow, consecutive timeouts accumulate quickly.
- **BUG-S2 pattern** (plan doc): `StorageRangeCoordinator` has no internal escape when all peers idle — `consecutiveIdleChecks` counter not implemented (Phase 2 fix was identified but deferred). The `SNAPSyncController` external 20-minute `StorageStagnationThreshold` never fires because the coordinator's own timeout escalation triggers first.
- **Mitigation**: Both coordinators deferred missing data to healing phase ("healing phase will recover missing data"). Healing IS running.
- **Risk**: Healing pending count at 306K+ and growing at 10:52:56. Attempt 20 had 227K healed / 1.3M abandoned. This run may have larger healing workload.
- **Action needed (post-Attempt-26)**: Implement BUG-S1 fix (don't clear knownAvailablePeers on pivot refresh) and BUG-S2 fix (idle escape for StorageRangeCoordinator). Both prevent premature force-completion.
- **NOT a kill candidate**: Healing phase has started and is actively progressing.

### ISSUE-10 — Block 24437463 import blocked by missing contract code (HIGH)
- SNAP sync completed at 11:17:43 → regular sync started at block 24437460
- **Block 24437463 failed**: missing contract code `32be9ea27c92ebd253a37526fd070b2ce9e6c4e56c897becdd8a03a9712b3647` for contract `0xfc27cd13b432805f47c90a16646d402566bd3143`
- Root cause: ISSUE-8 (bytecode phase abandoned 10,196 bytecodes). This specific code was in that abandoned set.
- **Recovery attempt**: `StateNodeFetcher` tried GetNodeData to fetch contract bytecode from peers — failed after 4 retries (~28s)
- **BlockImporter** backed off 300s, retrying at ~11:23:12 UTC
- **BlockFetcher** unaffected — has 196 blocks ready (up to 24437657), waiting for importer to catch up
- **Confirmed PERMANENT STALL** (3 retries, same failure each time): GetNodeData for `32be9ea2` fails 4 times every attempt. Pattern will not self-recover.
- **Second failure**: block 24437462 shows "gas mismatch but no missing contract code" — a state trie node missing from the 905K unhealed nodes (ISSUE-9 consequence). Two separate problems blocking import.
- **Process state**: alive, BlockFetcher has 228+ ready blocks (up to 24437689), but BlockImporter permanently blocked. Will retry every 300s indefinitely.
- **Action needed (post-Attempt-26)**:
  1. Fix ISSUE-8 (BUG-S1): remove `knownAvailablePeers.clear()` from ByteCodePivotRefreshed — prevents bytecode abandonment
  2. Fix ISSUE-9 (ISSUE-9): add `consecutiveEmptyDispatch` counter to TrieNodeHealingCoordinator — escapes 0-of-0 stall
  3. Investigate GetNodeData failure: ETH68 peers should respond; check if StateNodeFetcher request format is correct for bytecode retrieval
  4. Consider adding a larger bytecode abandonment threshold before force-completing (e.g., 1000-node minimum) to avoid this class of failure

### ISSUE-9 — Healing stalled: empty "0 of 0" GetTrieNodes requests since 11:06:11 (MEDIUM)
- Last real healing at 11:06:03 (426,680 nodes). Every request since: "Healed 0 of 0 requested trie nodes"
- Both peers (b81f5e and 16264d) returning empty responses at ~1 Hz
- **Trigger**: StorageRangeCoordinator declared all 25 peers stateless for root `aec6ee68` at 11:06:11, sending `PivotStateUnservable` (correctly ignored in StateHealing phase)
- **Root cause hypothesis**: TrieNodeHealingCoordinator's `dispatchIfPossible` is selecting 0 hashes despite 900K+ nodes in `pendingTasks`. Possibly `pendingHashSet` corruption or all tasks marked as in-flight.
- **Why BUG-M3 escape won't fire**: escape checks `activeRequests.isEmpty` — but activeRequests is always 1 (empty requests cycle). The condition never triggers.
- **Why stagnation counter won't fire**: `consecutiveUnproductiveHealingRounds=0` at 11:06:55, 11:07:55 — controller treats "got a response (even 0-of-0)" as productive. Stagnation detection blind to this bug.
- **Mitigation**: ChainDownloader started concurrently at ~11:05 — downloading headers (15% = 3.7M/24.4M at 11:08). If regular sync can catch up, missing healing nodes may be recovered during block import.
- **Action needed (post-Attempt-26)**: Fix `dispatchIfPossible` to detect when `pendingTasks.nonEmpty` but 0 hashes selected, and log WARN. Add separate `consecutiveEmptyDispatch` counter → escape to `HealingForceComplete` after N empty dispatches.
- **NOT a kill candidate**: process alive, chain sync running; kill condition (account progress) doesn't apply.

### ISSUE-7 — Third in-place pivot refresh at 10:33:01 (97.1% accounts, root be1e9a43)
- Both peers marked stateless for root `be1e9a43` at 10:33:01
- `be1e9a43` is the pivot root at this moment — implies a silent refresh from `dd274662` → `be1e9a43` happened earlier (unlogged, likely around block 24437333)
- In-place refresh: block 24437333 → 24437396 (advanced 63 blocks), root → `3d9afe40`
- **Resolved in ~4 seconds** — BUG-15 confirmed working again (second confirmed production trigger)
- Download resumed immediately at 10:33:05 with no interruption to rate
- Pattern: pivot refreshes fired at 91.7% and 97.1% (first logged at these points, silents in between)
- **Not a kill candidate** — self-resolving, BUG-15 working correctly

### ISSUE-6 — In-place pivot refresh triggered at 10:02:50 (91.7% accounts, root d38e8932)
- Both peers marked stateless for root `d38e8932` at 10:02:50 (peer b81f5e... then 16264d48...)
- "All 2 known peers are stateless for root d38e8932. Requesting pivot refresh (attempt=1, backoff=60s)"
- Root `d38e8932` is NOT the original pivot root `4043723e...` — suggests pivot was already refreshed in-place at some earlier point (silently, during the download)
- In-place pivot refresh triggered: "Refreshing pivot in-place: all peers stateless for AccountRange root"
- Grace period: 10 minutes from 10:02:50 → expires at 10:12:50
- Backoff=60s → actual refresh message fires ~10:03:50
- Account download PAUSED (3/4 ranges done, 0 pending, 0 active) at the moment of stateless detection
- **BUG-15 in-place refresh logic is engaged** — accounts should resume after new pivot root received
- **Monitor closely**: if accounts don't resume by 10:12:50, fallback to standard pivot refresh (could restart download)
- **NOT a kill candidate** — process alive, grace period active, BUG-15 fix should handle this
- **RESOLVED at 10:02:51** — in-place pivot refresh completed in ~1 second:
  - New pivot: 24437270 (was 24435932, advanced 1338 blocks)
  - New state root: `dd274662` (was `d38e8932`)
  - Storage coordinator got `Storage pivot refreshed: d38e8932 -> dd274662` + 10s cooldown
  - Account download resumed at 10:03:30 with 78.88M accounts — **BUG-15 confirmed working**
- **Post-refresh observation:** Actual throughput ~2,000-2,500 acc/sec (halved vs pre-refresh), though rolling rate still shows ~4,060. Still `3/4 ranges, 0 pending, 0 active` after refresh — 4th range dispatching between snapshot windows

### Note: Third range boundary gap 08:45:23 → 08:51:43 (6.3 minutes)
- All three gaps now consistent: ~6 min, process alive, peers stable, rate reset ~3% on resume
- Gap #1: 05:53-05:59 (5.5 min), Gap #2: 07:35-07:41 (5.7 min), Gap #3: 08:45-08:51 (6.3 min)
- Rate reset this time was larger: 4,430 → 4,331 (~2.2% drop vs ~3% for prior gaps)
- Pattern confirmed: expect one more gap near the 4/4 range completion (~97-100% keyspace)

### Note: Second range boundary gap 07:35:37 → 07:41:20 (5.7 minutes)
- Same pattern as first gap at 05:53-05:59: ~5-6 min silence, process alive ([ACTOR-ALIVE] every 60s throughout), 2 snap-capable peers stable
- Rate reset from 4,878 → 4,732 after gap (~3% drop), same post-gap reset observed previously
- This is the second occurrence — becoming a consistent pattern at range boundaries
- NOT a stall; no intervention needed

---

## Attempt 27 — Code Fixes and Restart Plan (2026-04-25 ~13:30 UTC)

### Root cause analysis (Attempt 26 failure)

**ISSUE-8 root cause confirmed:** `ByteCodeCoordinator.consecutiveTaskFailures = 20` + missing reset in `PeerAvailable` handler. At 10:49:31, both peers were temporarily unresponsive (~34 seconds) after account phase ended. 20 rapid failures fired `ForceCompleteByteCode` — abandoning 10,196 bytecodes. One of those bytecodes (`32be9ea27c92...` for contract `0xfc27cd13`) was needed at block 24437463, cascading into permanent regular sync stall.

### Fixes committed in `a18257157` (april-confluence)

1. **ByteCodeCoordinator — maxConsecutiveTaskFailures: 20 → 100**: 20 failures in 34 seconds from 2 unresponsive peers is not a "no progress" signal; 100 provides ~83 seconds at current rate before triggering. Only fires if *all* 100+ tasks fail, not just a brief peer outage.

2. **ByteCodeCoordinator — PeerAvailable/ByteCodePeerAvailable resets consecutiveTaskFailures=0**: When peers reconnect, stale failure count no longer carries over. This is the direct fix: peers reconnected at ~10:49:50 but the counter was already at 20.

3. **SNAPSyncController BUG-B3 — re-pivot recovery via healing**: When `accountsComplete=true` but pivot is stale (>96 blocks drift), `skipToHealingOnNextPivot=true` is set instead of clearing accounts. After new pivot is selected and header fetched, `checkAllDownloadsComplete()` is called directly — jumping to healing for the new state root. Healing only downloads the small delta of changed nodes (~5-50K for ~600 blocks) vs re-downloading 85.9M accounts.

### Attempt 27 pre-launch checklist

- [x] `sbt compile` — clean (2 pre-existing warnings)
- [x] `sbt test` — 2601 tests pass
- [x] `sbt assembly` — JAR built: `target/scala-3.3.4/fukuii-assembly-0.1.240.jar` (hash: `5c5b3ca5`)
- [ ] **USER ACTION NEEDED**: `rm -rf /media/dev/2tb/data/blockchain/fukuii/etc/rocksdb`

### Attempt 27 launch command (run AFTER deleting rocksdb)

```bash
java -Xmx4g \
  -Dfukuii.datadir=/media/dev/2tb/data/blockchain/fukuii/etc \
  -Dfukuii.network=etc \
  -Dfukuii.network.rpc.http.interface=0.0.0.0 \
  -Dfukuii.network.rpc.http.port=8553 \
  -Dfukuii.network.server-address.port=30305 \
  -Dfukuii.network.discovery.port=30305 \
  -Dfukuii.sync.do-fast-sync=true \
  -Dfukuii.sync.do-snap-sync=true \
  -jar /media/dev/2tb/dev/fukuii/target/scala-3.3.4/fukuii-assembly-0.1.240.jar etc \
  > /media/dev/2tb/data/blockchain/fukuii/etc/logs/fukuii.log 2>&1 &
```

### What to watch for in Attempt 27

**Bytecode phase (expected at ~10:49 equivalents in future runs):**
```
# Should NOT see "Force-completing bytecode" within first 30 seconds
# Should see "Reset consecutiveTaskFailures=0" on PeerAvailable events
# Should see counter reset to 0 and increment back up more slowly
```

**If process killed mid-run (BUG-B3 test):**
```
# On restart with accountsComplete=true + stale pivot:
[SNAP-RECOVERY-B3] Setting skipToHealingOnNextPivot=true
[SNAP-RECOVER-B3] Accounts already downloaded from prior session; new pivot=X — jumping to healing
```

### Outstanding for Attempt 28+ (if Attempt 27 completes accounts but fails again)

- **BUG-B1**: ETH64-68 `PeerInfo.maxBlockNumber=0` at startup → pivot stuck at -64 until ETH69 peers connect. ETH64 Status has no block number field (only TD + hash). Need alternative: GetBlockHeaders request when new ETH68 peer connects with `bestHash`.
- **BUG-B2**: Per-range checkpointing for mid-download crash recovery (add `putSnapSyncPendingRanges` to AppStateStorage, write in `AccountRangeCoordinator.postStop()`).


### Process shutdown confirmed — 2026-04-25 13:03:57

Old Attempt 26 process (the permanently-stalled one) shut down gracefully at 13:03:57 UTC-6.  
Final log lines: "Stopping peer discovery..." + "Regular Sync stopped".  
No process running as of ~13:05 UTC-6.  
**Rocksdb still exists** — user must delete before Attempt 27 launch:
```bash
rm -rf /media/dev/2tb/data/blockchain/fukuii/etc/rocksdb
```
Then launch Attempt 27 with the command in this file above.
