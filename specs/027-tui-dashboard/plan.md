# Implementation Plan: TUI Dashboard — "the worm goes to the brain"

**Branch**: `027-tui-dashboard` | **Date**: 2026-11-07 | **Spec**: [spec.md](./spec.md) (its Clarifications session of
2026-11-07 is binding)

**Input**: Feature specification from `/specs/027-tui-dashboard/spec.md`; design record [research.md](./research.md)
(#1490's proposal, refreshed against staging `f0b4a8627`, corrections C-1 to C-16).

## Summary

The `--tui` dashboard becomes a set of views over one pure renderer. The frame keeps today's invariant (exactly
`height × width`) and gains tiers (S/M/L), an alert row that always wins, the worm-to-brain bar the logs already draw,
emoji and ASCII glyph sets, a monochrome theme, and motion driven only by a frame counter `tick`. Polling moves off the
frame loop to its own thread with per-source freshness, so a slow actor degrades one panel. The splash becomes a view
(no `Thread.sleep`). Later slices add the Sync, Peers, Chain, Logs and Help views, a log ring appender, and the per-chain
consensus-layer and mining panels, which read new state that metrics objects record at existing call sites. Slice 4a
(D-1, answered (a)) wires the never-used `PeerScoringManager` into the peer events and exports its scores through
`NetworkMetrics`, so slice 4's Peers view has a score for every peer. Six slices (1, 2, 3, 4a, 4, 5), one PR each, in the
order of research §9 with 4a inserted before 4. Nothing changes consensus behaviour, and no decision reads the scores.

## Technical Context

**Language/Version**: Scala 3.9.0 (LTS line, spec 020) with `-source:3.9` and the DC-4 warning ratchet (#1717), JDK 25,
sbt 1.13.0 (constitution "Technology & Architecture Constraints"; `CLAUDE.md`).

**Primary Dependencies**: JLine 3.30.13 (`org.jline:jline`, `project/Dependencies.scala:157`): `Terminal`,
`AttributedString`/`AttributedStyle`, `WCWidth`, `NonBlockingReader`. Pekko Typed: the probe asks
`PeerManagerActor`, `SyncController`, `NetworkPeerManagerActor` and `PendingTransactionsManager` with `AskPattern` from
its own thread, never from a dispatcher. Logback 1.5.34 (`UnsynchronizedAppenderBase[ILoggingEvent]`, no layout) for the ring.
Micrometer through `MetricsContainer` objects (`SNAPSyncMetrics`, `NetworkMetrics`, `RegularSyncMetrics`,
`EngineApiMetrics`, `PoWMiningMetrics`). Typesafe Config for `fukuii.tui`. **No new dependency.**

**Storage**: N/A. The TUI reads no database except the best block header (already read today through
`blockchainReader.getBestBlockNumber`, `StdNode.scala:296`).

**Testing**: ScalaTest `AnyFlatSpec` + `Matchers`, tag `UnitTest` (`testing/Tags.scala:55`); ScalaCheck 1.19 through
scalatestplus `scalacheck-1-18` for the width property; the in-memory xterm `TuiTestTerminal`
(`src/test/scala/com/chipprbots/ethereum/console/TuiTestTerminal.scala`); golden files under
`src/test/resources/tui/golden/`. No `Thread.sleep`: blocking stubs wait on latches the test releases, and waits use
`eventually(timeout(...))`. Per `CLAUDE.md`: `sbt compile-all` per edit,
`sbt "testOnly com.chipprbots.ethereum.console.* *Tui*"` per slice (`*Tui*` alone misses console specs whose names
lack it, such as `SplashSpec` or `LayoutSpec`), `./scripts/fukuii-test essential` once at the end of the sprint.

**Target Platform**: Linux terminals over a TTY, SSH, `tmux` and `screen` (xterm-256color, screen-256color, linux
console); the node JVM.

**Project Type**: single sbt root module; the existing `com.chipprbots.ethereum.console` package.

**Performance Goals**: a 160×48 frame renders in under 1 ms; the TUI threads use under 2 % of one core at 4 fps and
under 0.5 % at 1 fps idle (SC-003). Polling stays at 1 Hz with one bounded 500 ms wait.

**Constraints**: no consensus code (spec non-goals, FR-025); frames exact (FR-001); the renderer is pure (FR-004); no
`Thread.sleep` in tests or in `console/`; 120-column scalafmt; scalafix clean; **one heavy JVM at a time on this
machine**, so sbt runs and the manual node runs are serialized (see "Sequencing").

**Scale/Scope**: about 2,250 lines of main code and 950 of tests over the five TUI slices (research §9: 350/450/600/450/400 and
150/200/250/200/150); roughly 20 new files in `console/`, edits to the seven existing `console/` files, `Fukuii.scala`,
`StdNode.scala`, five metrics objects, `MilestoneLog.scala`, `EthashDAGManager.scala`, `EngineApiHttpServer.scala`,
`BlockImporter.scala`, `conf/base/fukuii.conf` and a new `conf/base/tui.conf`. Slice 4a adds about 250 lines of main code
and 300 of tests in `network/` (`PeerScoringManager`, `PeerScore`, `NetworkMetrics`, `PeerManagerActor`, `PeerActor`),
`blockchain/sync/` (`PeersClient`, `Blacklist`, `snap/SNAPRequestTracker`) and `nodebuilder/NodeBuilder.scala`.

## Constitution Check

*GATE: passed before research (the design record exists); re-checked after this design (this table).*

| Principle | Engaged? | Status / justification |
| --- | --- | --- |
| **I. Consensus determinism** | Only by proximity, in slice 5: E1/E3 add state to `EngineApiMetrics` and a counter at `EngineApiHttpServer.scala:100`; M2 adds gauges at `EthashDAGManager.scala:75,98`. | **PASS.** Each is a write to a metrics object at an existing call site; no return value, branch, status, timing or DAG byte changes. `beacon` (ETH half) and `forge` (ETC half) review slice 5 before merge, reporting only. The import-duration gauge is set in `blockchain/sync/regular/BlockImporter.scala`, not in `consensus/ConsensusImpl.scala` (C-7). ETC and ETH code paths are untouched. Slice 4a touches `network/` and `blockchain/sync/` only, in one code path for both chains; its scores feed no dial, selection or blacklist decision. |
| **II. Spec-driven** | Yes | PASS. spec.md (clarified 2026-11-07), research.md (design record), this plan, tasks.md. |
| **III. Tests & coverage** | Yes | PASS. Tests first in every slice; goldens for 3 tiers × 2 glyph sets from slice 1; a ScalaCheck width property; latches and `eventually`, never `Thread.sleep` (research §8.5); the renderer is pure, so most new code is unit-testable and coverage rises (≥ 70 %). |
| **IV. Idiomatic Scala 3** | Yes | PASS. `enum` for `TuiView`, `Tier`, `Motion`, `GlyphSet`, `Severity` and `Sourced`; explicit result types; extension methods, not implicit classes (spec 020 P3b); no `return`; `scalafmtAll` before each commit; `formatCheck` per slice. |
| **V. Quality gates** | Yes | PASS. One PR per slice; the per-slice gate below plus `sbt pp` before opening each PR (run alone); CI green. |
| **VI. Security & operational safety** | Yes: the TUI writes peer-supplied text to the operator's terminal. | PASS. FR-020 sanitises every external string (peer client ids, log text) before it reaches a frame (C-16), with a test that an escape sequence in a client id is stripped. The TUI opens no port and changes no RPC binding. |
| **VII. Versioning & decisions** | Yes | PASS. PATCH per merge. Operator-visible changes (footer label, new keys, `fukuii.tui.*`, `FUKUII_TUI_*`) are documented in `docs/architecture/console-ui.md` in the closing task. No ADR: no architectural or consensus decision beyond this spec. |
| **Module boundaries** | Yes | PASS. `console/` depends on `network/`, `blockchain/sync/`, `transactions/`, `utils/` and the metrics objects; nothing depends on `console/` except `Fukuii` and `StdNode`. No metrics object gains a dependency on `console/`. Slice 4a: `blockchain/sync/` gains a dependency on `network/PeerScoringManager`, the same direction as its existing dependencies on `network/`. |

## Project Structure

### Documentation (this feature)

```text
specs/027-tui-dashboard/
├── spec.md       # clarified 2026-11-07
├── research.md   # #1490's design proposal, refreshed (§0), decisions (§10)
├── plan.md       # this file
└── tasks.md      # 150 tasks, one phase per slice (4a's are T128–T150)
```

No data-model.md, contracts/ or quickstart.md: the entities are UI state described in research §7.1, there is no
external contract, and the manual gate below is the quickstart.

### Source code (repository root)

```text
src/main/scala/com/chipprbots/ethereum/console/
├── TuiGlyphs.scala            # S1 NEW  GlyphSet enum, per-set (String, cols), log-text mapper
├── TuiTheme.scala             # S1 NEW  colour roles; 16/256/mono; Theme.of(env, maxColors)
├── Tier.scala                 # S1 NEW  S/M/L from (width, height); logo decision table
├── WormBar.scala              # S1 NEW  column-budget worm; ripple, stall, pulse from S2
├── TuiEnv.scala               # S1 NEW  pure parsing of NO_COLOR, FUKUII_TUI_*, locale (env map injected)
├── Sourced.scala              # S2 NEW  Fresh | Stale | Unresponsive
├── TuiProbeThread.scala       # S2 NEW  1 Hz poll, parallel asks, one bounded wait, AtomicReference publish
├── Splash.scala               # S2 NEW  splash view, tiered logo (whole / cropped / 12-line / wordmark)
├── TuiView.scala              # S3 NEW  views, key dispatch table
├── Layout.scala               # S3 NEW  Panel, Columns, Layout.allocate
├── Alerts.scala               # S3 NEW  §2.3 conditions → Severity rows (E1/M1 rows added in S5)
├── ChainFacts.scala           # S3 NEW  family, chain id, ETC/ETH fork timelines
├── panels/                    # S3 NEW  Overview, Sync, Peers, Chain, Help (Logs S4; Cl, Mining S5)
├── Sanitize.scala             # S4 NEW  ESC/C0/C1 strip, one line, cap (logs and client ids)
├── TuiLogRingAppender.scala   # S4 NEW  ring of LogLine (level, logger, text)
├── Tui.scala                  # EDIT S2 diff-skip, splash view, no sleep; S3 keys; S4 ring attach/detach, ladder lifted
├── TuiRenderer.scala          # EDIT S1 worm/tiers/wordmark/footer; S3 becomes the panel compositor
├── TuiState.scala             # EDIT S1 SnapSyncState fields; S2 tick/motion/clock-free; S3 view; S4 logs/history
├── TuiStatusProbe.scala       # EDIT S1 8 fields; S2 parallel asks + Sourced; S3 disk/JVM/discovery/head; S4 peers/txpool
├── TuiUpdater.scala           # EDIT S2 frame loop, adaptive pacing, `p`
├── TuiConfig.scala            # EDIT S1 fromConfig + glyphs; S2 motion/intervals/splash; S4 ring size, log level
└── TuiLogSuppressor.scala     # unchanged (the ring is a separate appender)

src/main/scala/com/chipprbots/ethereum/
├── Fukuii.scala                                   # EDIT S1 load TuiConfig + env (C-12); S2 splash
├── nodebuilder/StdNode.scala                      # EDIT S1 TuiConfig; S2 probe thread; S3/S4/S5 probe inputs
├── MilestoneLog.scala                             # EDIT S3 namedMilestones → private[ethereum]
├── blockchain/sync/snap/SNAPSyncMetrics.scala     # EDIT S2 latestProgressAt; S3 counter accessors (additive)
├── blockchain/sync/regular/RegularSyncMetrics.scala, BlockImporter.scala   # EDIT S4 last-import duration gauge
├── network/PeerScoringManager.scala, PeerScore.scala   # EDIT S4a injected clock, atomic updates, cap, blacklist rule
├── network/NetworkMetrics.scala                   # EDIT S4a peer-score snapshot export (in-process) + aggregate gauges
├── network/PeerManagerActor.scala                 # EDIT S4a handshake success (`:969`); publish on RefreshPeerStatuses (`:758`)
├── network/PeerActor.scala                        # EDIT S4a handshake failure (`:428`); BreachOfProtocol disconnect
├── blockchain/sync/PeersClient.scala              # EDIT S4a response and timeout (`:263-281`)
├── blockchain/sync/Blacklist.scala                # EDIT S4a scoring decorator: PeerId blacklists → recordBlacklist
├── blockchain/sync/snap/SNAPRequestTracker.scala  # EDIT S4a SNAP response and timeout (top-level snap/ file)
├── nodebuilder/NodeBuilder.scala                  # EDIT S4a construct the scorer beside `blacklist` (`:269`); pass it
├── consensus/engine/EngineApiMetrics.scala        # EDIT S5 E1 last-call times/statuses, E3 auth-failure count
├── consensus/engine/EngineApiHttpServer.scala     # EDIT S5 E3 count on the auth-failure path (`:100`)
├── consensus/pow/PoWMiningMetrics.scala           # EDIT S5 M2 DAG epoch/progress/loaded gauges
└── consensus/pow/miners/EthashDAGManager.scala    # EDIT S5 M2 gauge updates at `:75`, `:98`

src/main/resources/conf/base/
├── tui.conf      # S1 NEW  fukuii.tui { glyphs }, grown in S2 and S4
└── fukuii.conf   # S1 EDIT include "tui.conf"

src/test/scala/com/chipprbots/ethereum/console/
├── TuiGlyphsSpec, TuiThemeSpec, TuiTierSpec, TuiWormBarSpec, TuiEnvSpec, TuiGoldenSpec, TuiWidthPropertySpec # S1
├── TuiAnimationSpec, TuiProbeThreadSpec, SplashSpec, SourcedSpec                                          # S2
├── LayoutSpec, AlertsSpec, ChainFactsSpec, TuiKeysSpec                                                    # S3
├── SanitizeSpec, TuiLogRingAppenderSpec, PeersPanelSpec                                                   # S4
└── ClPanelSpec, MiningPanelSpec                                                                           # S5
src/test/scala/com/chipprbots/ethereum/network/PeerScoringManagerSpec, NetworkMetricsSpec                  # S4a NEW
    (S4a edits: network/PeerScoreSpec, PeerScoreFeedbackSpec, PeerManagerSpec, p2p/PeerActorSpec;
     blockchain/sync/PeersClientSpec, CacheBasedBlacklistSpec, snap/SNAPRequestTrackerSpec.
     Not NetworkPeerManagerSpec.scala, a spec 016 pin in scripts/snap-split/protected-paths.txt)
    (edits: TuiSpec, TuiUpdaterSpec, TuiStatusProbeSpec in console/; TuiRendererSpec, TuiStateSpec, TuiConfigSpec,
     TuiLogSuppressorSpec in the root test package; SNAPSyncMetricsSpec; MilestoneLogSpec; new EngineApiMetricsSpec,
     PoWMiningMetricsSpec, NetworkMetricsSpec)
src/test/resources/tui/golden/<view>-<tier>-<state>-<glyphs>.txt
```

**Structure Decision**: everything new for the TUI lives in `console/`. Outside it, edits are additive reads or metric
writes at existing call sites, plus wiring in `Fukuii` and `StdNode`. Slice 4a is the exception: a network-layer change,
owned by `herald`, that wires the existing scorer where its events happen (see "Slice 4a: peer scoring wired").

## Architecture (research §7)

- **State (§7.1).** `TuiState` becomes the frame's whole input: `tick`, `view`, `motion`, `glyphs`, `splashUntilTick`,
  `celebrationStartedTick`, `celebrated` (once per process, Q8), `lastAdvanceTick`, `snapshot`, a bounded `history`,
  `logs`, `nodeSettings`, `chain`. All four `Instant.now()` calls leave `TuiState` (C-3); uptime and rates use
  `snapshot.now`. Because Q1 varies the pace, every duration (the 120 s stall, the alert thresholds, freshness ages) is
  measured on `snapshot.now` (research §4.1's `stallAfterTicks` becomes `stallAfter = 120 s`, and `lastAdvanceTick`
  becomes `lastAdvanceAt`); only animation frames (ripple, pulse, spinner, splash, celebration) count ticks, and those
  run only at the 250 ms pace.
- **Snapshot freshness (§7.1).** `NodeStatusSnapshot` holds `Sourced[A]` per source (peers, sync, txpool, consensus
  layer, mining) plus `now`. `connectionStatus` and `syncStatus` become derived strings, so today's tests keep their
  meaning.
- **Loop (§7.2).** `TuiProbeThread` (daemon) polls every `statusPollIntervalMs` (1000): it starts all asks at once and
  waits once, up to 500 ms, then publishes to an `AtomicReference`. A source that misses the wait becomes `Stale` (it
  answered before) or `Unresponsive` (it never answered, or failed). `TuiUpdater` runs `tick + 1 → snapshot = ref.get →
  render → checkInput(pace)`, where `pace` is 250 ms if anything animates and 1000 ms otherwise (Q1). `Tui.render` keeps
  its lock and skips the write when the lines equal the last frame. The splash is a view; `showStartupBanner` and its
  `Thread.sleep` go.
- **Renderer (§7.3).** `render(state, w, h) = header ++ alertRow ++ Layout.allocate(View.panels(view, tier), h − 3) ++
  footer`, then `fitToWidth` per line (today's `TuiRenderer.scala:52-60`). `Panel(minRows, preferredRows, priority,
  render)`; `Columns(left, right, splitAt)` for tier L. `WormBar` is in `console/`; `WormToBrainBar` is untouched.
- **Log tail (§7.4).** `TuiLogRingAppender` is attached to the root logger after suppression succeeds and detached
  before restore, through the teardown ladder, which is lifted from a local def in `releaseTerminal` to a private method
  first (C-11). It keeps `LogLine(time, level, logger, text)`, sanitised on capture (FR-020).
- **Config and environment (§7.5).** `TuiConfig.fromConfig(config.getConfig("fukuii.tui"))`, then `TuiEnv` overrides
  from an injected environment map, so both are unit-tested without touching the process environment. HOCON errors are
  named startup errors; bad environment values log a warning and fall back.

## Delivery slices (one PR each)

Every slice is a `feat/027-*` branch off the latest `origin/staging`, ends with the gate below, and leaves the TUI
working at every intermediate commit.

| # | Slice (research §9 phase) | Scope | Branch | Reviewers |
| --- | --- | --- | --- | --- |
| 1 | **Worms, glyphs, tiers** | `TuiGlyphs`, `TuiTheme`, `TuiEnv`, `Tier`, `WormBar` (static), SNAP five worm rows and the regular-sync worm in today's layout, the 8 `SyncProgress` fields, tiered logo, FUKUII wordmark, footer labels (`d Detach`, no new keys), `conf/base/tui.conf` with `glyphs`, `TuiConfig` loaded (C-12). Goldens: 3 tiers × 2 glyph sets. | `feat/027-s1-worms` | prism; eye |
| 2 | **Tick, motion, non-blocking loop** | `tick`, `Motion`, `TuiProbeThread`, `Sourced`, adaptive pacing (Q1), diff-skip, ripple/spinner/pulse/stall, celebration and "back at head" (Q8), `p`, reduced motion, splash view with tiered and cropped logo (Q2), clock-free `TuiState` (C-3), `SNAPSyncMetrics.latestProgressAt`. | `feat/027-s2-motion` | prism; eye |
| 3 | **Views, layout, alerts** | `Panel`/`Layout`, Overview/Sync/Peers (`GetPeersCmd` data only)/Chain/Help, keys `1-5 Tab ? h g`, alert row (§2.3 rows that need no slice 5 data), `ChainFacts` (C-4, C-5), `MilestoneLog` visibility, SNAP counter accessors, disk free, JVM heap/GC, discovery counts, head timestamp. | `feat/027-s3-views` | prism; eye |
| 4a | **Peer scoring wired** (new; D-1 = (a)) | Construct one `PeerScoringManager`; fix what wiring exposes (injected clock, atomic updates, a bounded map, blacklist must not raise a score); feed it from the peer events in the table below; export a score snapshot and aggregate gauges through `NetworkMetrics`. Observational only. Needs only staging, not slices 1–3. | `feat/027-s4a-peer-scoring` | **herald** owns it; prism reviews; eye |
| 4 | **Logs, rich peers** | `Sanitize`, `TuiLogRingAppender`, Logs view with `+`/`-`, `l`, Recent strips, `GetHandshakedPeersCmd` (client id, best block), the score and trend columns **on**, read from 4a's `NetworkMetrics` export, txpool ask, import-duration gauge, `fukuii.tui.log-level` (Q4). Depends on 4a. | `feat/027-s4-logs-peers` | prism; eye |
| 5 | **Per-chain panels** | ETH: E1 (`EngineApiMetrics`, Q6), E2 (`ForkChoiceManager.getState` to the probe), E3 (auth-failure count), CL panel and its two alerts. ETC: M1 (reads), M2 (DAG gauges), coinbase, mining panel and its alert. | `feat/027-s5-chain-panels` (may split as `feat/027-s5a-eth-cl` and `feat/027-s5b-etc-mining`) | prism; **beacon** (ETH half, E1–E3), **forge** (ETC half, M1–M2), both reporting only; eye |

Rollback per slice: each slice is one revert. Slice 1 also reverts `tui.conf` and its include; slice 2's probe thread
replaces the in-loop poll in one commit, so the revert restores the old loop; slices 4 and 5 add metrics state that
nothing else reads. Reverting 4a leaves the scorer unconstructed again, as today; nothing but slice 4's columns reads its
scores, so slice 4 must be reverted first or its columns show `—`.

### Slice 4a: peer scoring wired (D-1 = (a))

`PeerScoringManager` (`network/PeerScoringManager.scala`) and `PeerScore` (`network/PeerScore.scala`) have existed since
`bee01af48` and have never been constructed in `src/main`. Its API names the events; this is where each one happens on
staging `f0b4a8627`, for `herald` to confirm before code:

| Scorer call | Event source | Peer id available? |
| --- | --- | --- |
| `recordSuccessfulHandshake` | `PeerManagerActor.handlePeerHandshakeSuccessful` (`PeerManagerActor.scala:969`), fed by `PeerEvent.PeerHandshakeSuccessful` (`PeerEventBusActor.scala:73`) | yes: `handshakedPeer.id`, the hex node id (`PeerActor.scala:603`) |
| `recordFailedHandshake` | `PeerActor` on `Left(HandshakeFailure(reason))` (`PeerActor.scala:428`); the manager only gets host and reason (`PeerClosedConnectionCmd`, `PeerManagerActor.scala:98`) | yes, inside `PeerActor`: `PeerId(hex(remoteNodeId))`. The scorer must reach `PeerActor` (factory parameter) or a new internal `PeerEvent` carries it; `herald` picks |
| `recordResponse(bytes, latencyMs)` | ETH requests: `PeersClient` on `PeerRequestHandler.ResponseReceived(…, timeTaken)` (`PeersClient.scala:263-277`, `PeerRequestHandler.scala:32`). SNAP requests: `SNAPRequestTracker` on completion (`SNAPRequestTracker.scala:59`, its clock is injectable, `:29`) | yes: `peer.id` |
| `recordTimeout` | `PeersClient` on `RequestFailed(…, "request timeout")` (`PeerRequestHandler.scala:129`; not "connection closed", `:141`); `SNAPRequestTracker`'s armed timeout (`:141`) | yes |
| `recordProtocolViolation` | `PeerActor` told `DisconnectPeer(BreachOfProtocol)` by its connection on a decode error (`RLPxConnectionHandler.scala:503-508`) | yes, inside `PeerActor` |
| `recordBlacklist` | the node-wide `Blacklist` (`NodeBuilder.scala:269`) wrapped by a decorator that records `add` calls whose id is a `PeerId`. `PeerManagerActor` blacklists by `PeerAddress` (IP) (`:558`, `:837`, `:923`); those cannot be mapped to a peer and are not scored | only for `PeerId` ids |
| export | the scorer publishes a snapshot to `NetworkMetrics` on update, at most once a second by its clock, and on `PeerManagerActor`'s existing 10 s `RefreshPeerStatuses` tick (`:398`, `:758`) so recency decay shows without events | — |

What wiring exposes in today's scorer, all fixed in 4a (tests first):

1. **Wall clock.** `PeerScore.score` and every `record*` call `Instant.now()` (`PeerScore.scala:74`, `:82-127`), so no test
   of a score change is deterministic. 4a injects a `java.time.Clock` into the manager and passes `now` to `PeerScore`;
   `PeerScoreSpec` and `PeerScoreFeedbackSpec` move to explicit times, with no case deleted.
2. **Lost updates.** `getScore` then `put` (`PeerScoringManager.scala:44-46` and each `record*`) is not atomic. Events
   arrive from several actors (`PeerManagerActor`, `PeerActor`s, `PeersClient`, `SNAPRequestTracker`), so 4a uses
   `TrieMap.updateWith`.
3. **Unbounded map.** Entries are only removed by `removePeer`, which nothing calls. Every failed handshake from a crawler
   adds a node id. 4a caps the map and evicts the entry with the oldest `lastSeen`.
4. **Blacklisting raises a score.** `blacklistCount` is not in `score`, and `recordBlacklist`, `recordTimeout` and
   `recordProtocolViolation` all refresh `lastSeen`, which raises the recency term (10 % of the score). `herald` picks the
   formula change so a blacklist never raises a score (for example, `blacklistCount` in the compliance term); a test pins
   it. The recency comment (`PeerScore.scala:75`, "<5min=1.0") is also wrong: the decay is linear from 0 minutes.
5. **Not used for decisions.** `getBestPeers`, `getBestPeersExcluding` and `shouldRetry` stay unused (spec non-goal).
   The logger stays at ERROR (`logback.xml:180`), so the debug "score ↑" lines in research §3's mockups will not reach
   the INFO+ log ring; the Peers view shows the trend instead.

### Per-slice gate (research §8, §9)

Run in this order, one JVM at a time:

1. `sbt compile-all` after each file; 0 errors, no new warnings (DC-4 ratchet).
2. `sbt "testOnly com.chipprbots.ethereum.console.* *Tui*"` (every console spec, and the root-package `Tui*` specs), plus the named non-TUI specs the slice touches (`*SNAPSyncMetrics*`, `*MilestoneLog*`,
   `*NetworkMetrics*`, `*EngineApiMetrics*`, `*PoWMiningMetrics*`, `*BlockImporter*`). Record the counts in the PR.
3. `sbt scalafmtAll`, then `sbt formatCheck`.
4. `scripts/snap-split/verify.sh --branch <branch> origin/staging` (Python, no JVM). Must print `snap-split verify: OK`.
5. Manual, with no sbt running: `fukuii mordor --tui` (slice 5's ETH half: `fukuii sepolia --tui` against a CL) in a
   `tmux` pane of 80×24 and one of 160×48 (`tmux new-session -d -x 80 -y 24 …`, then `tmux capture-pane -p`), plus
   `LANG=C` and `NO_COLOR=1` runs; from slice 2, `FUKUII_TUI_MOTION=reduced` and a CPU sample (`pidstat -t -p <pid> 1
   60`, SC-003). Transcripts go in the PR (SC-011).
6. `sbt pp` before opening the PR (constitution V), alone.

**Slice 4a's gate** replaces steps 2 and 5:

- 2a. Targeted tests: `sbt "testOnly *PeerScor* *NetworkMetrics* *PeerManagerSpec* *PeerActorSpec* *PeersClientSpec
  *SNAPRequestTracker* *CacheBasedBlacklist*"`, which must include the deterministic export test: with a fixed clock, a
  recorded timeout for a peer lowers that peer's score in the `NetworkMetrics` snapshot (SC-013).
- 5a. Manual, no sbt running: a Mordor node for 10 minutes; `/metrics` shows the aggregate score gauges, the scored-peer
  count covers every handshaked peer, and the count of dials, blacklistings and disconnects in the log is in line with
  an unwired run (the scores change no decision).

At the end of the sprint, after slice 5 merges: `./scripts/fukuii-test essential` once (`CLAUDE.md`).

### Branch names and `scripts/snap-split/verify.sh`

`verify.py` classifies a branch by name (`verify.py:565-570`): `refactor/snap-016-*` and `test/snap-016-*` are spec 016
kinds, `fix/*` and `hotfix/*` (without `snap-016`) are "fix", anything else is "other".

- **Use `feat/027-*` for every slice** (kind "other"). For it the verifier checks only that no commit changes a spec 016
  module file (`blockchain/sync/snap/controller/**`, `snap/actors/{account,storage,healing,bytecode}/**`), that no
  commit marked as a spec 016 move (`# moved:`, `# test-files:`, or `#1401` in the message) touches `src/test/**/sync/snap/`,
  and the storage ratchet on the PR head. None of this spec's edits are in those paths.
- **Do not use `fix/*`** for slices 1, 2 or 4: on a fix branch any change under `src/main/resources/` fails step 4, and
  those slices create or grow `conf/base/tui.conf`. A later bug fix to a merged slice that touches no resource may use
  `fix/027-*`.
- **Files under `blockchain/sync/snap/`**: `SNAPSyncMetrics.scala` (slices 2 and 3) and `SyncProgressMonitor.scala`
  (read only) are top-level `snap/` files, not module files and not in the ratchet set (`RATCHET_DIRS`,
  `RATCHET_FILES = SNAPSyncController.scala`), so `feat/027-*` edits pass. Keep them additive, never put `# moved:`,
  `# test-files:`, `# capability:` or `#1401` in a 027 commit message, and rebase onto spec 022's latest before review
  (022 is Active there).
- **Slice 4a: `feat/027-s4a-peer-scoring`** (kind "other"). Its `blockchain/sync/snap/SNAPRequestTracker.scala` edit is
  also a top-level `snap/` file (not a module file, not in the ratchet set), and its test edit in
  `src/test/**/sync/snap/SNAPRequestTrackerSpec.scala` passes because no 027 commit carries a spec 016 marker. 4a
  touches no resource. It must not edit `NetworkPeerManagerSpec.scala`, a spec 016 pin
  (`scripts/snap-split/protected-paths.txt`).

## Sequencing

- The TUI slices are serial: 2 needs 1's glyphs and worm, 3 needs 2's snapshot and pacing, 4 and 5 need 3's panels and
  alert row. Slice 4 also needs 4a's export. Slice 4a needs only staging, so its code and tests can be written while
  slices 1–3 are in review (sbt still serialized); it merges before slice 4. Slice 5 does not need 4a or 4.
- **One heavy JVM at a time.** This machine hangs with two (memory: one heavy JVM). sbt runs for different slices never
  overlap, sbt never runs while a manual node run is up, and another session's sbt job takes precedence. Writing tests
  and code for slice N+1 while slice N is in review is fine; compiling it waits.
- Spec 020: PR 5b (#1714, extension methods) and PR 6 (#1717, `-source:3.9` and the DC-4 ratchet) are on staging. Read
  020's `**In flight**` line before editing `StdNode.scala` or `Fukuii.scala`; if a 020 rewrite still holds them,
  rebase onto it before review.

## Decisions

- **D-1: where peer scores come from. Resolved (user, 2026-11-07): (a), wire `PeerScoringManager` into production.**
  Q5 fixes the path (`NetworkMetrics` export, no scoring manager in the probe), but nothing produced scores:
  `PeerScoringManager` had never been constructed in `src/main` (C-1). The options were (a) wire it, (b) export
  `SnapGoodPeers.decayedScore` (SNAP servers only), (c) no score column. The plan recommended (b); the user chose (a), so
  every peer gets a score. Delivered as slice 4a.
- **Dead-code review verdict: Wire it** (protocol: `.claude/agent-protocols/dead-code-review.md`). Gap: the scorer is
  complete and tested (`PeerScoreSpec`, `PeerScoreFeedbackSpec`) but was never constructed. Git history: added in
  `bee01af48` ("Implement Priority 1-3: Enhanced peer selection, adaptive sync, exponential backoff"); the only later
  change touching it is `f5768e670` (its logger set to ERROR); no commit ever removed a caller. Supersession: none.
  `SnapGoodPeers` scores SNAP servers only, for redial, and stays as it is. Value: per-peer reliability for every peer,
  shown in the Peers view.

Recorded here because they are not in the user's list, and either can be reversed in review:

- The alert row warns below 25 % of max peers (§2.3) while the peer-count colour keeps today's 50 % (C-2).
- The Fetcher row is deferred (C-7); the Import row's duration gauge is set in `BlockImporter`, not `ConsensusImpl`.

## Specialist routing

| When | Agent | Why |
| --- | --- | --- |
| End of every slice | `prism` | 8-lens review of non-consensus code. |
| End of every slice | `eye` | Runs the gate's sbt steps (one JVM) and reports pass/fail with counts. |
| Slice 5, ETH half, before merge | `beacon` | Confirms E1–E3 change no Engine API response, status or timing (reporting only). |
| Slice 5, ETC half, before merge | `forge` | Confirms M1–M2 change no mining, DAG content or timing (reporting only). |
| Slice 4a, before code and before merge | `herald` (owner) | Confirms the event map above, chooses how the scorer reaches `PeerActor`, implements and reviews the network change. |
| Slice 4a, before merge | `prism` | 8-lens review: thread safety, the map bound, test determinism. |
| Any compile failure | `wraith` | Scala 3.9 and DC-4 fallout. |

## Complexity Tracking

No constitution violations. Two design choices that add moving parts, both from the spec:

| Choice | Why needed | Simpler alternative rejected because |
| --- | --- | --- |
| A second daemon thread (`TuiProbeThread`) | FR-011: a slow actor must not delay frames | Polling in the frame loop blocks it for up to 1 s (`TuiStatusProbe.scala:37-45`); polling from a Pekko scheduler would run blocking waits on a dispatcher. |
| Two frame paces (250 ms / 1000 ms) | Q1 | A fixed 250 ms loop wakes four times a second on an idle node for nothing; a fixed 1000 ms loop makes motion unreadable (§4.4). |
