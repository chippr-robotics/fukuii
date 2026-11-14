# Implementation Plan: TUI Command Center

**Branch**: `029-tui-command-center` | **Date**: 2026-11-13 | **Spec**: [spec.md](./spec.md) (its Clarifications
session of 2026-11-13 is binding)

**Input**: Feature specification from `/specs/029-tui-command-center/spec.md`; design record
[research.md](./research.md) (the mockups, spec 027's baseline, the JLine experiments, the Kitty protocol facts, the
data inventory).

## Summary

Spec 027's `--tui` dashboard becomes a command center in seven slices, one PR each.

Slice 1 gives it input: a decoder that turns the terminal's bytes into keys and never lets a sequence or a reply
through as characters, a pure update function from (state, key) to (state, effects), focus, selection, a screen
stack, a prompt, filters and dialogs. Slice 2 gives it graphics: a start-up query phase, three modes (Kitty pixels,
half-block cells, text) over one layout, the sprite sheets that are already in the tree, a mascot that is a function
of the node's state, and a writer that sends only the rows that changed. Slices 3 to 6 add the views: chain with a
Block Observatory, the Mempool Radar, RPC with the Engine Synchronization Monitor, and storage, system and health
bars. Slice 7 adds operator actions with a preview, a confirmation by risk class and an audit line.

The TUI stays in process (D-1). Where it can learn something by watching (new heads, reorgs, pool flow) it watches;
observation added outside `console/` is state on a metrics object at an existing call site. Nothing changes consensus
behaviour. No dependency is added.

## Technical Context

**Language/Version**: Scala 3.9.0 (LTS) with `-source:3.9` and the warning ratchet, JDK 25, sbt 1.13.0. Python 3.12
with Pillow and NumPy for the art scripts only (`scripts/tui/`), never at run time.

**Primary Dependencies**: JLine 3.30.13 (`Terminal`, `AttributedString`, `WCWidth`, `KeyMap.key` for terminfo
sequences; it has no graphics support, research §3). `java.util.zip` (`Deflater`, `Inflater`, `CRC32`) and
`java.util.Base64` for the Kitty encoder and the PNG reader. `java.lang.management` beans. Pekko Typed asks from the
probe thread, as in 027. Cats Effect `IO` for the JSON-RPC services called in process. Typesafe Config for
`fukuii.tui` and `art.conf`. **No new dependency.**

**Storage**: none. Reads only: block headers and bodies, RocksDB properties, `AppStateStorage` markers. A support
bundle writes one file under the data directory (FR-058).

**Testing**: ScalaTest `AnyFlatSpec`, tag `UnitTest`; ScalaCheck for the decoder and the width property; the
in-memory `TuiTestTerminal`, extended to record graphics commands; text goldens under
`src/test/resources/tui/golden/`, chart goldens as text (one palette character a pixel). No `Thread.sleep`: latches
and `eventually`. Python `unittest` for the art (`scripts/tui/test_art.py`).

**Target Platform**: Linux terminals over a TTY, SSH, tmux and screen. Pixel mode: terminals that answer the Kitty
graphics query and a cell-size query (kitty, Ghostty; others by the query, research §4.8).

**Project Type**: single sbt root module; `com.chipprbots.ethereum.console`.

**Performance Goals**: SC-007: under 20 KiB/s to the terminal on the busiest view after the art is uploaded; under
3 % of one core at 4 fps and 0.5 % idle; a 160×48 frame in under 2 ms. SC-004: a key dispatched within 250 ms.

**Constraints**: frames exact and the renderer pure (027 FR-001, FR-004); no consensus change (SC-013); no
`Thread.sleep` in `console/` or tests; 120-column scalafmt; scalafix clean; one heavy JVM at a time on the build
host.

**Scale/Scope**: about 6,000 lines of main code and 4,400 of tests over seven slices (table below); roughly 45 new
files in `console/`; edits outside it listed under Project Structure.

## Constitution Check

*GATE: passed before research; re-checked after the design below.*

| Principle | Engaged? | Status / justification |
| --- | --- | --- |
| **I. Consensus determinism** | By proximity, in slices 3, 5 and 6. | **PASS.** Slice 3 reads headers and bodies through `BlockchainReader` and adds no hook to an import path (research §6.1). Slice 5 adds a timer around `EngineApiHttpServer.processRequest` (`:142`), a ring and a consensus-client identity on `EngineApiMetrics`, and a failure reason in `JwtAuthenticator`'s two 401 branches: each is a write to a metrics object at an existing call site; no response, status, ordering or timing changes. `beacon` is consulted before the code and reports before merge. Slice 6's audit status is one read-only message to an actor the sync controller already owns. ETC and ETH paths are not mixed: the head observer and the health model take the family as data. |
| **II. Spec-driven** | Yes | PASS. spec.md (clarified 2026-11-13), research.md, this plan, data-model.md, contracts/, quickstart.md, tasks.md. |
| **III. Tests & coverage** | Yes | PASS. Tests first in every slice. The decoder, the update function, the pixel layer's diff, the observers and the health model are pure, so they are tested without a terminal, a clock or a node. Latches and `eventually`, never `Thread.sleep`. |
| **IV. Idiomatic Scala 3** | Yes | PASS. `enum` for `Key`, `Screen`, `Overlay`, `Effect`, `GraphicsMode`, `Pose`, `RiskClass`, `FeeTier`, `Subsystem`; explicit result types; extension methods; no `return`; `scalafmtAll` before each commit; `formatCheck` per slice. |
| **V. Quality gates** | Yes | PASS. One PR per slice; the per-slice gate below; `sbt pp` before each PR, alone. |
| **VI. Security & operational safety** | Yes: the TUI gains actions, a configuration view and a file it writes. | PASS, with the rules of spec FR-047 to FR-058: no new network-reachable method; one implementation per action, shared with the admin interface; redaction by list and by pattern with a planted-secret test; pasted input can confirm nothing; a read-only switch. Peer-supplied strings stay sanitised (027 FR-020), and the consensus client's self-reported name is treated as one. Art from `art-dir` is size-capped and decoded by a reader that accepts one PNG subset. |
| **VII. Versioning & decisions** | Yes | PASS. PATCH per merge. Operator-visible changes (keys, `q`, settings, environment) are in [contracts/operator.md](./contracts/operator.md) and go to `docs/architecture/console-ui.md` in the closing task. ADR: one, for the operator-action model (risk classes, one implementation per action, no RPC exposure), written with slice 7. |
| **VIII. Backlog** | Yes | PASS. B-244 holds this PR. Each slice gets an item when it starts; B-245 (newHeads), B-193, B-196, B-189, B-192 and B-226 are referenced in tasks.md. |
| **Module boundaries** | Yes | PASS. `console/` depends on `jsonrpc/` services, `network/`, `blockchain/sync/`, `transactions/`, `db/`, `metrics/`; nothing depends on `console/` but `Fukuii` and `StdNode`. `metrics/LatencyHistogram` is new and depends on nothing. |

## Project Structure

### Documentation (this feature)

```text
specs/029-tui-command-center/
├── spec.md               # clarified 2026-11-13
├── research.md           # the design record
├── plan.md               # this file
├── data-model.md         # the types, by slice
├── quickstart.md         # the manual gate
├── contracts/
│   ├── art.md            # the sprite-sheet format; xterm-240.gpl is its palette
│   ├── operator.md       # keys, prompt commands, settings, environment
│   └── actions.md        # every action: class, preview, the function that carries it out, undo
├── art-preview/          # enlarged views of the art and two mock screens
├── mockups/              # the three concept images of the brief
├── checklists/requirements.md
└── tasks.md
```

### Source code (repository root)

```text
src/main/scala/com/chipprbots/ethereum/console/
├── Key.scala, KeyDecoder.scala        # S1 NEW  keys; bytes → keys, pure
├── Nav.scala                          # S1 NEW  Screen, Nav, Selection
├── Overlay.scala, Prompt.scala        # S1 NEW  help, prompt, filter, dialog shells; the line editor
├── Commands.scala                     # S1 NEW  the command table, parse, completion (grown by S3–S7)
├── Update.scala                       # S1 NEW  (state, key, now) → (state, effects); Effect
├── widgets/                           # S1 NEW  ListView, Table, Box, DialogBox, Sidebar, Sparkline, Bar
├── TerminalQuery.scala                # S2 NEW  the start-up query phase; TerminalCaps
├── GraphicsMode.scala                 # S2 NEW  choose(setting, caps, noColor)
├── gfx/Palette.scala, PixelBuffer.scala, Blocks.scala   # S2 NEW  indexed pixels; half-block cells
├── gfx/Png.scala, ArtStore.scala      # S2 NEW  the PNG subset reader; sheets and art.conf
├── gfx/Kitty.scala, PixelLayer.scala  # S2 NEW  the protocol encoder; diff of what the terminal holds
├── gfx/Charts.scala                   # S2 NEW  bars, histograms, the radar; grown by S3–S6
├── Pose.scala                         # S2 NEW  Pose.of(state)
├── FrameWriter.scala                  # S2 NEW  rows that changed, then the pixel layer's bytes
├── HeadObserver.scala                 # S3 NEW  the head walk, reorgs, rates
├── Lookups.scala                      # S3 NEW  the worker; block, tx, receipt, account
├── PoolObserver.scala                 # S4 NEW  snapshot diff, tiers
├── Health.scala                       # S6 NEW  Subsystem, HealthCheck, HealthBar; Alerts reads it
├── ops/Action.scala, Actions.scala, ActionRunner.scala  # S7 NEW  the registry and the runner
├── ops/Redaction.scala, ConfigView.scala, SupportBundle.scala, EndpointProbe.scala   # S7 NEW
├── panels/                            # S3–S7 NEW  Observatory, Fees, Reorgs, Explorer, Radar, Mempool, Rpc,
│                                      #            Engine, Storage, System, HealthPanel, Ops, Art; EDIT the 027 panels
├── Tui.scala                          # EDIT S1 decoder, effects; S2 query phase, writer, image teardown rung
├── TuiState.scala                     # EDIT S1 nav, overlay; S2 graphics; S3–S6 samples, history
├── TuiRenderer.scala, Layout.scala    # EDIT S1 sidebar, focus; S2 regions, Frame.regions
├── TuiView.scala                      # EDIT S1 nine views
├── TuiStatusProbe.scala, TuiProbeThread.scala   # EDIT S3–S6 observers, slow cadence, the view in focus
├── TuiConfig.scala, TuiEnv.scala      # EDIT S1 read-only; S2 graphics, art-dir; S6 alerts.*, history
├── Alerts.scala                       # EDIT S6 thresholds from config; rows from Health
└── TuiTheme.scala                     # EDIT S2 the command-center theme (xterm indices of contracts/art.md)

src/main/scala/com/chipprbots/ethereum/
├── nodebuilder/StdNode.scala                       # EDIT S3–S7 hand the TUI the services, the data source, the actions
├── Fukuii.scala                                    # EDIT S2 start-up phases to the splash
├── metrics/LatencyHistogram.scala                  # S5 NEW  fixed log buckets, lock-free
├── jsonrpc/JsonRpcControllerMetrics.scala          # EDIT S5 per-method calls, errors, histogram, last call
├── jsonrpc/server/controllers/JsonRpcBaseController.scala   # EDIT S5 time failures too (`:53-90`)
├── jsonrpc/server/http/RateLimit.scala             # EDIT S5 a rejection counter
├── jsonrpc/server/http/JsonRpcHttpServer.scala, JsonRpcWsServer.scala   # EDIT S7 keep the binding; stop, start
├── jsonrpc/AdminService.scala                      # EDIT S7 current log levels, reset (beside `changeLogLevel`)
├── consensus/engine/EngineApiMetrics.scala         # EDIT S5 call ring, durations, ClIdentity, auth reasons
├── consensus/engine/EngineApiHttpServer.scala      # EDIT S5 time `processRequest` (`:142`)
├── consensus/engine/EngineApiController.scala      # EDIT S5 record the caller's client version and capabilities
├── consensus/engine/JwtAuthenticator.scala         # EDIT S5 the reason in the two 401 branches
├── transactions/TxPoolMetrics.scala                # S4 NEW  rejections by reason, evictions, expiries, gossip
├── transactions/PendingTransactionsManager.scala   # EDIT S4 count at the removal listener and the admission results
├── transactions/TxGossipTracking.scala             # EDIT S4 announced, fetched, timed out
├── db/dataSource/RocksDbDataSource.scala           # EDIT S6 `properties()`; S7 whole-database compaction
├── network/rlpx/RLPxConnectionHandler.scala, network/NetworkMetrics.scala   # EDIT S6 bytes in and out
├── network/ExternalIPDetector.scala                # EDIT S7 keep the last result
├── blockchain/sync/SyncController.scala            # EDIT S6 forward `BackgroundAudit.GetStatus`
└── nodebuilder/tooling/StorageConsistencyChecker.scala   # EDIT S7 an entry that returns its result

src/main/resources/
├── conf/base/tui.conf                 # EDIT S1, S2, S6 the keys of contracts/operator.md
└── tui/art/                           # IN TREE  the sheets and art.conf (scripts/tui/make_art.py)

scripts/tui/                           # IN TREE  make_art.py, check_art.py, mock_screen.py, test_art.py
src/test/scala/com/chipprbots/ethereum/console/   # a spec per new file; goldens per view, tier and mode
```

**Structure Decision**: everything new for the TUI is under `console/`, with three sub-packages (`widgets`, `gfx`,
`ops`). Outside it, edits are observation at existing call sites, two lifecycle changes for D-1 (the HTTP and
WebSocket servers keep their binding), and wiring in `StdNode`.

## Architecture

### Input (slice 1)

- **Decoder.** `KeyDecoder.step(state, input)` is pure. It frames CSI, SS3, and string sequences (APC, DCS, OSC,
  PM, SOS) by grammar, with bounds (data-model.md). A complete CSI or SS3 is looked up in a table built from
  `KeyMap.key(terminal, capability)` plus the fixed normal and application forms (research E3); unknown ones are
  dropped whole. Bracketed paste (`ESC [ 200 ~` … `ESC [ 201 ~`) becomes one `Key.Paste`. A lone Esc becomes
  `Key.Esc` after 50 ms. `Tui.checkInput` feeds it from `NonBlockingReader.read(timeout)` and returns keys.
  `BindingReader` is not used: it leaks an unknown sequence a character at a time (research E4).
- **Update.** `Update(state, key, now): (TuiState, Seq[Effect])` replaces the body of `Tui.handleCommand`. Routing:
  an open overlay takes the key; else global keys; else the focused panel's keys. `Tui` runs the effects. Every key
  test is a pure test.
- **Navigation.** `Nav` holds a stack of `Screen`s with focus and selection keyed by screen, so Esc restores them.
  Panels declare whether they take focus and how many rows they list; `ListView` keeps the selection in view.
- **Help** is generated from the same key table dispatch uses, so SC-002's "no key works that Help does not list" is
  a property of the code, checked by a test over the table.

### Graphics (slice 2)

- **Query phase.** `TerminalQuery.run(terminal, timeout = 500 ms)` writes the graphics query, the cell-size query
  (`ESC [ 16 t`, then `ESC [ 14 t` as a fallback) and a primary device-attributes request, and reads until the
  device-attributes reply or the timeout, through the same decoder framing. It runs once in `Tui.initialize`, before
  the frame loop starts, and again on resize for the cell size only, where the reply is consumed by the decoder as a
  recognised report and never dispatched.
- **Modes.** `GraphicsMode.choose(setting, caps, noColor)`: text if `NO_COLOR` or fewer than 256 colours; else the
  setting; `auto` is pixel when `caps.kittyGraphics && caps.cell.isDefined && !caps.multiplexer`, else block.
- **Frame.** `Frame(lines, regions, animated)`. A panel asks `Graphic.cells(ctx, graphic, cols, rows)` for its
  rectangle: in block mode it gets the half-block cells; in pixel mode blank cells and a `GraphicRegion`; in text
  mode whatever the panel passes as its text equivalent. So the lines of a frame are complete in every mode and
  `Layout` only offsets region rows.
- **Placement: direct** (research §5.2). `PixelLayer.diff(previous, regions, cell)` yields the bytes: an upload
  (`a=t`, `f=32`, `o=z`, chunked, `q=2`) for an image the terminal does not hold; a cursor move and `a=p` with a
  source rectangle and `C=1` for a new or changed region; `a=d` for a region that left. A sprite sheet is one
  image; a frame change is one placement. Sprites are enlarged in process by `k = min(cell.w / 8, cell.h / 16)`,
  floored, at least 1, and centred with pixel offsets. A chart is drawn at its region's exact pixel size, in art
  pixels of `max(1, cell.w / 4)`, so it fills the region and stays chunky.
- **Writer.** `FrameWriter` compares the new lines with the last written row by row and writes only changed rows
  (`CSI row ; 1 H` and the row), then the pixel layer's bytes. On a resize, `r` or a mode change it clears, forgets
  what the terminal holds, and writes everything. JLine's `Display` is not used (research §5.3).
- **Art.** `gfx/Png` reads 8-bit non-interlaced PNG of colour type 3 or 6 with `Inflater`: about 120 lines, no
  `java.desktop`. `ArtStore` loads `tui/art/` from resources, or `art-dir`, validates against
  [contracts/art.md](./contracts/art.md), and reports problems once at start.
- **Mascot.** `Pose.of(state)`: error alert → `Error`; warning → `Warning`; stalled or an unresponsive source →
  `Asleep`; the celebration window → `Celebrating`; starting → `Starting`; syncing → `Syncing`; else `Watching`.
  The frame is the pose's timeline at `tick`. In block mode and under reduced motion it is frame 0.
- **Teardown.** One rung before "reset screen" (`Tui.scala:425`): delete all placements and free the images. The
  same bytes go through the process stream in `closedByJLine`.

### Data (slices 3 to 6)

- **Probe.** `TuiProbeThread` keeps its 1 Hz poll. `TuiStatusProbe` gains observers that keep state on the probe
  thread and publish immutable samples in the snapshot, each a `Sourced`: `HeadObserver`, `PoolObserver` (full only
  while the Mempool view is on screen: the frame loop publishes the screen in an `AtomicReference`), the RPC and
  Engine reads, the system sample. Storage properties are read every fifth poll with a try-lock; a miss is `Stale`.
- **History.** `TuiState` keeps one bounded ring per meter (data-model.md), advanced once a snapshot.
- **Lookups.** `Lookups` owns one daemon thread. A lookup runs the service's `IO` with `unsafeRunSync` on that
  thread and publishes its result by id; cancel marks the id dead and interrupts the thread. The frame loop only
  reads the published value.
- **Health.** `Health.checks(state, thresholds)` restates `Alerts.scala`'s rules as `HealthCheck`s. `Alerts.row` and
  the bars both read them, which is what makes SC-011 hold.

### Actions (slice 7)

- `Actions.all: Seq[Action]` is the registry; a test holds it to [contracts/actions.md](./contracts/actions.md).
- `ActionRunner` owns one daemon thread for previews and short actions and starts one more per long-running action
  (compaction, consistency check, bundle). It publishes `DialogState` and `Running` for the frame loop.
- Previews and runs call the service functions in the contract. `StdNode` builds the registry, because it holds the
  services, and hands the TUI closures, as it already does for the probe.
- The audit logger is `fukuii.tui.ops`, a named Logback logger, INFO, so it reaches the file log and the log ring.

## Delivery slices (one PR each)

Every slice is a `feat/029-*` branch off the latest `origin/staging`, ends with the gate below, and leaves the TUI
working at every commit.

| # | Slice | Scope | Size (main / test) | Reviewers |
| --- | --- | --- | --- | --- |
| 1 | **Input and navigation** (US1) | Decoder, update function, nav, overlays, prompt, filter, widgets, the nine-view map with the 027 panels re-homed, the sidebar, Help from the key table, `q` asks (a plain `y` dialog until slice 7), `read-only` parsed. Peers and Logs become lists; a peer detail screen. | 900 / 700 | prism; eye |
| 2 | **Graphics** (US2) | Query phase, modes, palette, `Png`, `ArtStore`, `Blocks`, `Kitty`, `PixelLayer`, `FrameWriter`, the command-center theme, `Pose`, the mascot on Overview, the network icon in the sidebar (FR-061), the splash with the badge and real start-up phases, `:art`, `v`, the image teardown rung. | 1,100 / 800 | prism; eye |
| 3 | **Chain** (US3) | `HeadObserver`, Observatory, head panel, fees, reorg history and alert, identity, `Lookups`, the explorer screens, the peer radar. | 900 / 650 | prism; eye |
| 4 | **Mempool** (US4) | `PoolObserver`, the Radar, flow, senders, replacements; `TxPoolMetrics` and its call sites. Needs B-196. | 500 / 400 | prism; `herald` (gossip counters); eye |
| 5 | **RPC, Engine, mining** (US5) | `LatencyHistogram`, per-method stats, transports, rate-limit counter; the Engine call ring, durations, identity, auth reasons; the monitor; the mining monitor. Needs B-193. | 750 / 550 | `conduit` (RPC half); `beacon` before code and before merge (Engine half, reporting); prism; eye |
| 6 | **Storage, system, health** (US6) | RocksDB properties, stores, audit status; system sample, byte counters; `Health`, bars, drill-down; `alerts.*`; uptime from the process. | 850 / 550 | `vault` (properties); `herald` (byte counters); prism; eye |
| 7 | **Actions** (US7) | `Action`, registry, runner, dialogs by class, audit, read-only enforcement, every row of contracts/actions.md, the Ops view, `Redaction`, `ConfigView`, `SupportBundle`, `EndpointProbe`; HTTP and WebSocket stop and start; ADR. | 1,000 / 750 | `conduit` (server lifecycle); `vault` (compaction, consistency check); prism; eye |

Slice 1 comes first and slice 2 second. Slices 3 to 7 need only 1 (their charts need 2, and fall back to text
without it), so their order can change; 7 is last by choice, after its dialogs have been used by read-only screens.

Rollback: each slice is one revert. Slices 4, 5 and 6 add metric state nothing else reads. Slice 7's server
lifecycle change is reverted with it; without it the servers bind once, as today.

### Per-slice gate

Run in this order, one JVM at a time:

1. `sbt compile-all` after each file; 0 errors, no new warnings.
2. `sbt "testOnly com.chipprbots.ethereum.console.* *Tui*"`, plus the named specs outside `console/` the slice
   touches. Counts go in the PR.
3. `sbt scalafmtAll`, then `sbt formatCheck`.
4. `scripts/snap-split/verify.sh --branch <branch> origin/staging`: `snap-split verify: OK`. Use `feat/029-*`
   (never `fix/*`: the slices touch `src/main/resources/`, 027 plan "Branch names").
5. `python3 -I -m unittest discover -s scripts/tui` when the slice touches the art.
6. The manual gate of [quickstart.md](./quickstart.md) for the slice, with no sbt running. Captures go in the PR
   (SC-014).
7. `sbt pp` before opening the PR, alone.

At the end, after slice 7 merges: `./scripts/fukuii-test essential` once.

## Sequencing

- **Spec 027 first.** Slices 1 and 2 rewrite files 027's manual gate (B-189) covers. B-189 runs before slice 1
  starts, or slice 1 rebases onto its fixes. Read 027's `**In flight**` line before touching `console/`.
- **B-196** (a count-only pool query) before slice 4's Overview count; **B-193** (Engine status counters) before
  slice 5's panel reads them. **B-226** closes with slice 1; **B-192** with slice 6.
- **One heavy JVM at a time**, as in 027.
- Slices 5 and 6 touch `consensus/engine/` and `blockchain/sync/SyncController.scala`: rebase onto specs 018 and 022
  before review and read their `**In flight**` lines.

## Decisions

- **D-1 in process only; D-2 the peer radar; D-3 the art is made here** (user, 2026-11-13; spec Clarifications).
- **Own key framing, not `BindingReader`** (research E4).
- **Direct placement first; placeholder cells later** (research §5.2). The pixel layer is behind `PixelLayer.diff`,
  so adding placeholders for tmux changes one file and no frame.
- **Own PNG reader, not `javax.imageio`**: a headless server process should not load AWT, and the contract fixes the
  subset. About 120 lines, tested against every committed sheet.
- **Row-level writes, not JLine `Display`** (research §5.3).
- **Poll and walk for new heads, not a new event** (research §6.1): one mechanism for both families and no hook in
  an import path. B-245 tracks the missing Engine-path publish for `eth_subscribe`; if it is fixed the observer can
  still poll.
- **The art is code** (`scripts/tui/make_art.py`): a sprite changes by a reviewed diff, and a test fails when the
  committed sheets are stale. Hand-drawn sheets remain possible through the same contract.
- **CLAUDE.md's "current plan" block is not changed by this PR.** It names spec 022, a release blocker; a planned
  feature should not displace it. The owner of that block decides when 029 is named there.

## Specialist routing

| When | Agent | Why |
| --- | --- | --- |
| End of every slice | `prism` | 8-lens review of non-consensus code. |
| End of every slice | `eye` | Runs the gate's sbt steps and reports counts. |
| Slice 4, before merge | `herald` | The gossip counters sit in the transaction-announcement path. |
| Slice 5, before code and before merge | `beacon` | Impact analysis of the Engine API observation, then a report that no response, status or timing changed. |
| Slice 5, RPC half; slice 7, server lifecycle | `conduit` | `jsonrpc/` metrics and the HTTP and WebSocket bindings. |
| Slice 6, properties; slice 7, compaction and the check | `vault` | RocksDB property reads under the lock; whole-database compaction. |
| Slice 6, byte counters | `herald` | `network/rlpx/`. |
| Slice 5, only if a file under `consensus/pow/` changes | `forge` | The mining monitor is expected to read existing metrics only. |
| Any compile failure | `wraith` | Scala 3.9 fallout. |

## Complexity Tracking

No constitution violations. Design choices that add moving parts:

| Choice | Why needed | Simpler alternative rejected because |
| --- | --- | --- |
| Two more daemon threads (lookups; actions) | FR-010, FR-029, FR-057: a lookup or an action must never delay a key or a frame | On the frame loop they block it; on the probe thread one slow lookup stales every panel. |
| A third graphics mode (block) | Most operators run under tmux, where pixels do not reach the terminal | Pixel-or-text would give the majority nothing of the look. |
| A start-up query phase | FR-013: support is asked, not guessed from `TERM` | Name sniffing is wrong over SSH and for new terminals; asking during the key loop leaks replies as keys (E4). |
| An own PNG reader | Art is data in a standard format an artist's tools write | `javax.imageio` loads AWT in a server; a home-made format needs home-made tools. |
| A Python art pipeline | D-3: the art is made in this work, reviewably | Binary sheets with no source cannot be reviewed or regenerated. |
