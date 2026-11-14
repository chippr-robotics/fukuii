---

description: "Task list for spec 029 TUI command center"
---

# Tasks: TUI Command Center

**Input**: Design documents from `/specs/029-tui-command-center/`

**Prerequisites**: plan.md, spec.md (clarified 2026-11-13 and 2026-11-14), research.md, data-model.md, contracts/,
quickstart.md

**Tests**: Required by the spec (SC-001 to SC-015). In every slice the tests are written FIRST and must FAIL (a compile
error counts) before the implementation task that makes them pass. ScalaTest `AnyFlatSpec` + `Matchers`, tag
`UnitTest`; ScalaCheck where a task says "property"; no `Thread.sleep` (latches and `eventually`).

**Organization**: Setup → one phase per slice (plan.md "Delivery slices"), each one PR → Closure. Slice 1 is the MVP.
Every slice ends with the same gate. The art (phase 0) is done and in the tree.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task). "Parallel" means writing, not
  sbt: only one heavy JVM runs at a time on the build host.
- **[Story]**: US1 keyboard, US2 the look, US3 chain, US4 mempool, US5 RPC and the consensus-client link, US6 health,
  US7 actions; omitted for setup, gate and closure tasks.
- Paths are repository-relative. `console/` means `src/main/scala/com/chipprbots/ethereum/console/`; `console tests`
  means `src/test/scala/com/chipprbots/ethereum/console/`; `main/` means `src/main/scala/com/chipprbots/ethereum/`.

---

## Phase 0: Art (done in the spec PR, #1820)

- [x] T001 [US2] `scripts/tui/make_art.py`: the mascot in seven poses at 64, 32 and 16 art pixels, the badge at three
      sizes, the ETC and ETH network icons, the splash backdrop; sheets and `art.conf` in `src/main/resources/tui/art/`
- [x] T002 [US2] `scripts/tui/check_art.py`: every rule of contracts/art.md, sharing no code with the drawing script
- [x] T003 [US2] `scripts/tui/test_art.py` and `.github/workflows/tui-art.yml`: the committed sheets are what the
      script draws, keep the contract, and the checker rejects a bad colour, a soft edge, a wrong size, a missing sheet
- [x] T004 [US2] `scripts/tui/mock_screen.py` and `specs/029-tui-command-center/art-preview/`: the art judged at a
      terminal's scale, in pixel and block mode

---

## Phase 1: Setup

**Purpose**: baseline before slice 1. No behaviour change.

- [x] T005 Read spec.md (both Clarifications sessions), research.md §2 and §3, plan.md (sequencing, decisions). Confirm
      spec 027's manual gate (B-189) has run, or note the rebase. Add a backlog item for slice 1 and claim it. On slice
      1's first code commit set spec.md `**Status**` to `Active (<date>)` and `**In flight**` to `feat/029-s1-input`,
      then run `python3 -I scripts/specs/index.py`
- [x] T006 Baseline, one JVM: `sbt "testOnly com.chipprbots.ethereum.console.* *Tui*"` on `origin/staging`; record
      suites and tests for the slice 1 PR
- [x] T007 [P] Move research §3's experiments into `console tests` as `JLineAssumptionsSpec`: placeholder widths,
      indexed-colour emission, a graphics command through the writer. They pin what the design assumes of JLine 3.30.13

---

## Phase 2: Slice 1 — input and navigation (US1) 🎯 MVP

> As built (2026-11-14). Baseline before the slice: 785 tests in 35 suites of
> `testOnly com.chipprbots.ethereum.console.* *Tui*`; after it, 939. What differs from the tasks as written:
>
> - **T012, T021**: there is no `ListView` or `Table` widget yet. The Peers list's selection and paging are in
>   `PeersPanel` and `TuiState` (the selection is kept by peer id, the page is the one that holds it); `widgets/` has
>   `Sidebar` and `DialogBox`. A shared list widget waits for the second list that needs one (slice 3). The list's
>   behaviour is tested in `UpdateSpec` and the goldens, not in a `ListViewSpec`.
> - **T013, T014**: the Help-lists-every-key and reachability tests are in `UpdateSpec` and `TuiViewGoldenSpec`.
> - **T020**: `TuiState` keeps `view` and `helpOpen` as fields and adds `screens`, `overlay`, `selectedPeer`,
>   `filters`: fewer of spec 027's tests change than a derived `view` would cost.
> - **T023**: every view has one panel that takes keys so far, so `Tab` has nothing to move between and no focus
>   marker is drawn. The key is bound and listed.
> - **Beyond the tasks**: the 12-line logo column of the tier-L Overview is gone (the sidebar takes its columns; slice
>   2 puts the mascot there); a detail row that is too wide now drops its last part whole instead of cutting a number
>   or a timestamp (the SNAP rows and the Forks row, B-201).
> - **T005**: spec 027's manual gate (B-189) had not run when this slice was written; the slice rewrites files it
>   covers, so B-189 now runs against this code.
> - **T028 is open**: the manual gate needs a terminal, and the session that built the slice has none.

**Goal**: the TUI is driven from the keyboard. **Branch**: `feat/029-s1-input`.

**Independent test**: key tests over the decoder and `Update`; goldens of a focused list, a dialog and the prompt at
80×24 and 160×48; SC-002 and SC-003.

### Tests first

- [x] T008 [P] [US1] `KeyDecoderSpec` in `console tests`: every row of research E3; a lone Esc after 50 ms; a sequence
      split across reads; an unknown CSI, an APC reply and a cell-size report yield no key (E4); bounds; bracketed paste
      as one `Key.Paste`
- [x] T009 [P] [US1] `KeyDecoderPropertySpec` (property): 10,000 generated inputs mixing keys, sequences, replies and
      truncated sequences; every key out was a key in (SC-003)
- [x] T010 [P] [US1] `UpdateSpec`: view keys, `Tab`, `[`/`]`, selection and paging with both the escape and the
      printable key, Enter pushes a screen, Esc pops it with focus and selection restored, overlays take every key
- [x] T011 [P] [US1] `PromptSpec` and `CommandsSpec`: editing, history, completion, parse of every command in
      contracts/operator.md that slice 1 implements, the nearest-command message
- [x] T012 [P] [US1] `NavSpec` and `ListViewSpec`: selection stays in view and is clamped when the list shrinks
- [x] T013 [P] [US1] `HelpKeysSpec`: Help lists exactly the keys `Update` accepts in each context (SC-002's "no hidden
      key")
- [x] T014 [P] [US1] `ReachabilitySpec`: every view and panel is reached at 80×24 with printable keys, Tab and Enter
      only (SC-002); extended by each later slice
- [x] T015 [P] [US1] Goldens: Peers with a selection, the peer detail screen, Logs with a filter, the prompt, Help, the
      quit dialog, the sidebar at tiers M and L, in both glyph sets; extend `TuiWidthPropertySpec` to the new state
- [x] T016 [P] [US1] `TuiSpec` and `TuiKeysSpec` in `console tests`: `q` opens a dialog and does not quit; pasted `q`
      and `y` do not quit; `d` detaches; Ctrl-C is not read as a key

### Implementation

- [x] T017 [US1] `console/Key.scala` and `console/KeyDecoder.scala` (data-model.md); build the sequence table from
      `KeyMap.key(terminal, capability)` plus the fixed normal and application forms
- [x] T018 [US1] `console/Tui.scala`: `checkInput` feeds the decoder and returns `Seq[Key]`; enable and disable
      bracketed paste in `initialize` and the teardown ladder; remove `drainEscape` (closes B-226)
- [x] T019 [US1] `console/Nav.scala`, `console/Overlay.scala`, `console/Prompt.scala`, `console/Commands.scala`
- [x] T020 [US1] `console/Update.scala`: the pure update and `Effect`; `Tui.handleCommand` becomes "run `Update`, then
      the effects"; `TuiState` gains `nav` and `overlay`, with `view` and `helpOpen` derived
- [x] T021 [P] [US1] `console/widgets/`: `ListView`, `Table`, `Box`, `DialogBox`, `Sidebar`
- [x] T022 [US1] `console/TuiView.scala`: the nine views; re-home the 027 panels (Sync leads Chain while syncing; RPC,
      Storage, System, Mempool and Ops show what 027 already has for them, or a one-line "arrives with slice N")
- [x] T023 [US1] `console/TuiRenderer.scala` and `console/Layout.scala`: the sidebar at tiers M and L, the focused
      panel's marker, the contextual footer, overlays drawn over the body and never over the alert row
- [x] T024 [US1] `console/panels/PeersPanel.scala` and `LogsPanel.scala` as lists; a peer detail screen; `/` filters
- [x] T025 [US1] `console/panels/HelpPanel.scala` generated from the key table
- [x] T026 [US1] The quit dialog (`y` to confirm until slice 7 makes it a Halt); `console/TuiConfig.scala` and
      `TuiEnv.scala` parse `read-only` and `FUKUII_TUI_READ_ONLY`; `conf/base/tui.conf`

### Gate

- [x] T027 The per-slice gate of plan.md, steps 1 to 4 and 7; counts in the PR
- [ ] T028 quickstart.md "Slice 1", captures in the PR; `prism` and `eye` reports; open the PR with `Backlog: B-…`

---

## Phase 3: Slice 2 — graphics (US2)

> As built (2026-11-14), in one pull request (#1835), in two parts. **2a**: the three modes, the terminal query, the
> frame writer, the art loader, the network icon, the mascot, `v`, `:graphics`, `:art`. **2b**: T046 the
> command-center theme and T049 the splash with the badge and the node's real start-up phases. The chart primitives of
> T050 wait for the first chart (slice 3). After the slice: 1,076 tests in 42 suites of
> `testOnly com.chipprbots.ethereum.console.* *Tui*`. What differs from the tasks as written:
>
> - **T031–T035**: the PNG reader, the art store, the half-block cells, the Kitty commands and the pixel layer are
>   tested in one file, `gfx/GfxSpec`. `GraphicsModeSpec` also holds the terminal-query tests (T029) and the settings.
> - **T038**: the information-parity test is in `GraphicsRenderSpec`, over every Overview and alert fixture.
> - **T039**: the no-image-left test is `TuiGraphicsSpec`; `TuiTestTerminal` now plays its output back into rows,
>   images and placements, because a frame write is no longer the whole screen.
> - **T045**: `FrameWriter` is a pure function from (what the terminal shows, the frame) to (bytes, what it shows
>   after); `Tui` holds the value.
> - **T047**: the mascot stands in the bottom-right corner of the Overview's Recent strip, the rows that lose least by
>   being narrower, and only where the strip has the rows for it (9 for the small one, 17 and tier L for the large).
>   Its region is blank while a dialog is open (FR-018). In text mode the region shows a face in plain ASCII.
> - **T048**: a test network is named from the chain id (`ChainFacts.kind`): 63, 11155111, 17000, 560048 and
>   7091047534 are test networks, 1 and 61 main networks, anything else is called neither.
> - **Beyond the tasks**: block mode needs a UTF-8 locale (its one character is not ASCII), so FR-013 gained that
>   clause; FR-012 names the one row that changes with the mode, the sidebar's `graphics <mode>`; the pose table's
>   `asleep` row is "stalled, with no other alert" (a source that does not answer is an error alert, and errors come
>   first); tier M's status strips place their cells by the row's width below 120 columns, so the sidebar's columns
>   cost no cell its value (a slice 1 leftover: `Txpool…` at 120×40).
> - **FR-016, resize**: a resize may be a change of font size, so the cell's size is asked again then. The answer
>   comes back among the keys; a decoder told to read it (`KeyDecoder(cellReports = true)`, the TUI's own) makes it a
>   `Key.CellSize`, which `Tui.handleKey` takes before any key is dispatched and `Update` ignores.
> - **B-201**: the whole Overview frame at 100×32, tier M's narrowest screen, is a golden for an ETH and an ETC
>   fixture, so the golden lint reads the Forks row there; the sidebar gives a chain id a row of its own when it would
>   not fit beside the family (Sepolia's, Platåberget's).
> - **From prism's review**: what a terminal reports is input. A cell is believed only from 4 pixels a side to
>   64 × 128 and only as the answer to a question; art is enlarged at most 8 times; a sheet of the wrong size is
>   refused at its PNG header; an `include` in `art.conf` fetches nothing. The key decoder keeps an unfinished sequence
>   across a quiet input (for up to 1 s) instead of dropping it after 50 ms, so the rest of a slow key or a late reply
>   is never typed as keys: that is B-226's failure, closed here and not by slice 1.
> - **2b as built** (T046, T049): `TuiTheme.CommandCenter` is the art's palette by index, and
>   `Tui` draws with it whenever the mode is not text. `StdNode.start` reports five phases (`StartupPhase`) as each
>   completes; the splash lists them and its worm is the share done, so the worm no longer crawls with time. The
>   splash still ends on its timer (spec 027 FR-013): it does not wait for the start, and a phase still under way
>   shows as such until then. The chain's identity reaches the TUI in phase 0, so the icon and the header name the
>   network while the node starts. In pixel and block mode the badge stands for the logo where it fits above the
>   phases (the large one from 27 rows, the small one at 80×24); text mode keeps spec 027's logos, and since any key
>   leaves the splash, the mode cannot change under it. **Not built**: the `splash-scene` backdrop. A backdrop is
>   under text, which block mode cannot draw, so it would be a picture one mode has and another has not (FR-012);
>   the sheet stays in the art, shown by `:art`, and unused. `Charts.scala` (the rest of T050) waits for slice 3.
> - **Backlog**: 2a is B-257, 2b is B-258; both close with #1835.
> - **T052 is open**: the manual gate needs kitty, Ghostty and tmux, and the session that built the slice has no
>   terminal.

**Goal**: pixel, block and text modes over one layout; the mascot, the network icon, the splash.
**Branch**: `feat/029-s2-graphics`.

**Independent test**: goldens of every graphic region in three modes; SC-005, SC-006, SC-007.

### Tests first

- [x] T029 [P] [US2] `TerminalQuerySpec`: a terminal that answers both queries, one that answers only device
      attributes, one that answers nothing within 500 ms; no reply reaches dispatch
- [x] T030 [P] [US2] `GraphicsModeSpec`: the table of FR-013 (setting × capabilities × `NO_COLOR` × multiplexer)
- [x] T031 [P] [US2] `PngSpec`: reads every committed sheet to the same pixels `scripts/tui/make_art.py` drew (a
      fixture of hashes written by the script); rejects interlaced, 16-bit and truncated files
- [x] T032 [P] [US2] `ArtStoreSpec`: loads the bundled art; every rule of contracts/art.md, each with a broken sheet;
      a broken sprite is left out and reported, and nothing throws; timelines validated
- [x] T033 [P] [US2] `BlocksSpec`: the four half-block cases (both, upper, lower, neither); colours are indexed; a
      region is exactly its cells
- [x] T034 [P] [US2] `KittySpec`: byte-exact upload (chunks of at most 4,096, `m` flags, `o=z`), placement with a
      source rectangle and `C=1`, delete; every command carries `q=2`
- [x] T035 [P] [US2] `PixelLayerSpec`: nothing to send for an unchanged frame; one placement for a frame change; an
      upload once per image; everything again after a clear; enlargement by a whole factor only
- [x] T036 [P] [US2] `FrameWriterSpec`: only changed rows are written; a resize writes all; bytes per frame under the
      budget for the Overview fixture in pixel and block mode (SC-007)
- [x] T037 [P] [US2] `PoseSpec`: every row of research §5.5; errors outrank warnings outrank stall; reduced motion and
      block mode give frame 0; the frame follows the timeline at `tick`
- [x] T038 [P] [US2] `InformationParitySpec`: for every fixture, each value a region declares appears in the frame's
      text, and switching mode changes no cell outside the regions (SC-005, FR-012)
- [x] T039 [P] [US2] `TuiSpec`: after `q` confirmed, `d`, an interrupt and a lost terminal the test terminal holds no
      image (SC-006); extend `TuiTestTerminal` to track uploads and placements
- [x] T040 [P] [US2] Goldens: Overview at tiers M and L in block and text mode; the splash; `:art`; the network icon
      for an ETC and an ETH fixture (FR-061)

### Implementation

- [x] T041 [P] [US2] `console/gfx/Palette.scala`, `PixelBuffer.scala`, `Blocks.scala`
- [x] T042 [P] [US2] `console/gfx/Png.scala` and `console/gfx/ArtStore.scala`; `art-dir` and `FUKUII_TUI_ART_DIR`
- [x] T043 [P] [US2] `console/gfx/Kitty.scala` and `console/gfx/PixelLayer.scala`
- [x] T044 [US2] `console/TerminalQuery.scala` and `console/GraphicsMode.scala`; `graphics` and
      `FUKUII_TUI_GRAPHICS`; run the query in `Tui.initialize` before the frame loop
- [x] T045 [US2] `console/FrameWriter.scala`; `Frame` gains `regions`; `Tui.render` writes through it; the image
      teardown rung and the `closedByJLine` path
- [x] T046 [US2] `console/TuiTheme.scala`: the command-center theme from contracts/art.md's interface colours, used in
      pixel and block mode; 027's themes unchanged for text mode
- [x] T047 [US2] `console/Pose.scala`; the mascot on Overview at tiers M and L with its state named in text beside it
- [x] T048 [US2] The network icon in the sidebar, chosen from `ChainFacts.family` (FR-061), with the network's name
      and a test-network label in text
- [x] T049 [US2] `console/Splash.scala`: the badge, the backdrop, and the node's start-up phases reported from
      `StdNode.start` (FR-020); `Fukuii.scala` and `StdNode.scala` publish each phase as it completes
- [ ] T050 [US2] `v` and `:graphics`; `:art`; `r` reloads `art-dir`; `console/gfx/Charts.scala` with the bar and
      sparkline primitives the 027 meters can use

### Gate

- [ ] T051 The per-slice gate, including `python3 -I -m unittest discover -s scripts/tui`
- [ ] T052 quickstart.md "Slice 2" on kitty, Ghostty, tmux and `NO_COLOR`; screenshots, bytes and CPU in the PR. If
      the art reads badly on a real terminal, change `make_art.py`, not the loader. `prism` and `eye`; open the PR

---

## Phase 4: Slice 3 — chain (US3)

**Goal**: the Block Observatory, fees, reorgs, the explorer, the peer radar. **Branch**: `feat/029-s3-chain`.

### Tests first

- [ ] T053 [P] [US3] `HeadObserverSpec`: a straight chain; a gap larger than the walk bound sets `sampled`; a reorg of
      depth 3 is recorded with both tips; a reorg deeper than the bound is "at least"; a head with no body
- [ ] T054 [P] [US3] `LookupsSpec`: a result is published by id; a slow reader (latch) does not delay a frame; cancel
      discards the result; pruned state and an unknown hash give sentences
- [ ] T055 [P] [US3] `FeeSampleSpec`: a chain with a base fee and one without
- [ ] T056 [P] [US3] Goldens: the Chain view while syncing and at the head, ETC and ETH fixtures, three modes; the
      block, transaction, receipt and account screens; the reorg alert; the radar's chart as a text golden
- [ ] T057 [P] [US3] `RadarSpec`: a blip's radius is its latency band, its angle is stable for a node id, an
      unmeasured peer is on the outer ring; no line joins two peers

### Implementation

- [ ] T058 [US3] `console/HeadObserver.scala`; `TuiStatusProbe` publishes `ChainSample`; bounded reads per poll
- [ ] T059 [US3] `console/Lookups.scala`; `StdNode.scala` hands the TUI `ethBlocksService`, `ethTxService`,
      `ethUserService` and `gasPriceOracle`
- [ ] T060 [P] [US3] `console/panels/ObservatoryPanel.scala`, `HeadPanel.scala`, `FeesPanel.scala`,
      `ReorgsPanel.scala`; the identity rows on `ChainPanel.scala`
- [ ] T061 [P] [US3] `console/panels/ExplorerPanels.scala`: block, transaction, receipt, account; `block`, `tx`,
      `receipt`, `account` in `Commands`
- [ ] T062 [US3] `console/panels/RadarPanel.scala` on the Peers view; the peer detail screen gains score components
      and latency
- [ ] T063 [US3] `console/Alerts.scala`: the reorg alert at `alerts.reorg-depth`

### Gate

- [ ] T064 The per-slice gate; quickstart.md "Slice 3"; `prism` and `eye`; open the PR

---

## Phase 5: Slice 4 — mempool (US4)

**Goal**: the Mempool Radar. **Branch**: `feat/029-s4-mempool`. **Needs**: B-196.

### Tests first

- [ ] T065 [P] [US4] `PoolObserverSpec`: tiers against a known suggestion; arrivals; inclusion against observed
      blocks; drops; replacements; the top senders
- [ ] T066 [P] [US4] `TxPoolMetricsSpec` in `src/test/scala/com/chipprbots/ethereum/transactions/`: a rejection, an
      eviction, an expiry, an announcement, a fetch and a timeout each move one counter and change no result
- [ ] T067 [P] [US4] `TuiStatusProbeSpec`: the full pool is asked for only while the Mempool view is on screen, and at
      most once a second (SC-009)
- [ ] T068 [P] [US4] Goldens: the Mempool view, empty and busy, ETC and ETH fixtures, three modes; "not kept by this
      client" for the queue

### Implementation

- [ ] T069 [US4] `main/transactions/TxPoolMetrics.scala`; counts at the removal listener and the admission results in
      `PendingTransactionsManager.scala`, and in `TxGossipTracking.scala` (`herald` reviews)
- [ ] T070 [US4] `console/PoolObserver.scala`; the frame loop publishes the screen on show; the probe reads it
- [ ] T071 [US4] `console/panels/MempoolPanels.scala`: the Radar, flow, senders, counters; Enter opens a transaction

### Gate

- [ ] T072 The per-slice gate; quickstart.md "Slice 4"; `herald`, `prism` and `eye`; open the PR

---

## Phase 6: Slice 5 — RPC, the Engine API, mining (US5)

**Goal**: per-method RPC figures, transports, the Engine Synchronization Monitor, the mining monitor.
**Branch**: `feat/029-s5-rpc-engine`. **Needs**: B-193.

### Before code

- [ ] T073 [US5] `beacon` impact analysis of the Engine API observation (the timer around `processRequest`, the call
      ring, the client identity, the auth reasons): confirm each is a write at an existing call site. `conduit` reads
      the RPC half

### Tests first

- [ ] T074 [P] [US5] `LatencyHistogramSpec` in `src/test/scala/com/chipprbots/ethereum/metrics/`: recorded latencies
      with known percentiles are reported within 10 % (SC-010); adds from four threads lose nothing
- [ ] T075 [P] [US5] `JsonRpcControllerMetricsSpec`: a success, an error and an exception each count under their
      method and are timed; the 257th method name counts under `other`
- [ ] T076 [P] [US5] `EngineApiMetricsSpec`: a call adds one ring entry with its status and duration; the ring keeps
      256; identity and capabilities are stored sanitised; a response's bytes are unchanged with and without the
      observation
- [ ] T077 [P] [US5] Goldens: the RPC view; the monitor for link waiting, connected and down; the call log; the
      mining monitor; the exposure warning

### Implementation

- [ ] T078 [P] [US5] `main/metrics/LatencyHistogram.scala`; `JsonRpcControllerMetrics.scala` per-method figures;
      `JsonRpcBaseController.scala` times failures too; `RateLimit.scala` counts rejections
- [ ] T079 [US5] `main/consensus/engine/`: `EngineApiHttpServer.scala` times `processRequest`; `EngineApiMetrics.scala`
      gains the ring, `ClIdentity` and auth reasons; `EngineApiController.scala` records the caller's client version
      and capability list; `JwtAuthenticator.scala` names the reason in its two 401 branches
- [ ] T080 [P] [US5] `console/panels/RpcPanels.scala`: methods, transports
- [ ] T081 [P] [US5] `console/panels/EnginePanels.scala`: the monitor, whose link moves once per call that arrived;
      `MiningPanel.scala` with history

### Gate

- [ ] T082 The per-slice gate; quickstart.md "Slice 5" on Sepolia with a consensus client and on Mordor; `beacon`
      reports no response, status or timing changed; `conduit`, `prism` and `eye`; open the PR

---

## Phase 7: Slice 6 — storage, system, health (US6)

**Goal**: the Storage and System views, health bars, configurable thresholds. **Branch**: `feat/029-s6-health`.

### Tests first

- [ ] T083 [P] [US6] `RocksDbDataSourceSpec`: `properties()` returns each figure of data-model.md's `StorageSample`;
      a read that finds the write lock held returns nothing and blocks nobody
- [ ] T084 [P] [US6] `HealthSpec`: for every subsystem fixture a bar shows a warning or an error exactly when the
      alert row does, and names a check the drill-down lists (SC-011); headroom is 1 at the healthy end and 0 at the
      threshold
- [ ] T085 [P] [US6] `AlertThresholdsSpec` and `TuiConfigSpec`: every key of contracts/operator.md; an invalid value
      is a named start-up error; 027's defaults when unset
- [ ] T086 [P] [US6] `NetworkMetricsSpec`: bytes in and out advance by a frame's size; no behaviour of the connection
      changes
- [ ] T087 [P] [US6] Goldens: Storage, System, the health strip on Overview, the drill-down, "statistics off", three
      modes

### Implementation

- [ ] T088 [US6] `main/db/dataSource/RocksDbDataSource.scala`: `properties()` (`vault` reviews)
- [ ] T089 [US6] `main/blockchain/sync/SyncController.scala`: forward `BackgroundAudit.GetStatus`
- [ ] T090 [US6] `main/network/rlpx/RLPxConnectionHandler.scala` and `NetworkMetrics.scala`: byte counters (`herald`
      reviews)
- [ ] T091 [US6] `TuiStatusProbe`: the system sample, the storage sample every fifth poll, uptime from the process
      (closes B-192 if the disk read moves with it); `TuiState` history rings
- [ ] T092 [US6] `console/Health.scala`; `console/Alerts.scala` reads thresholds from config and rows from `Health`
- [ ] T093 [P] [US6] `console/panels/StoragePanels.scala`, `SystemPanels.scala`, `HealthPanel.scala`

### Gate

- [ ] T094 The per-slice gate; quickstart.md "Slice 6"; `vault`, `herald`, `prism` and `eye`; open the PR

---

## Phase 8: Slice 7 — operator actions (US7)

**Goal**: every row of contracts/actions.md, with its preview, confirmation and audit line.
**Branch**: `feat/029-s7-actions`.

### Tests first

- [ ] T095 [P] [US7] `ActionsContractSpec`: the registry is exactly contracts/actions.md, row for row, with each row's
      class
- [ ] T096 [P] [US7] `ActionDialogSpec`: confirmation by class; Cancel has the focus; a Halt needs the word; a stale
      preview refreshes and asks again; a dialog too small to show its preview cannot be confirmed; pasted input
      confirms nothing
- [ ] T097 [P] [US7] One spec per action family (`LogLevelActionSpec`, `PeerActionsSpec`, `MiningActionsSpec`,
      `RpcServerActionsSpec`, `DatabaseActionsSpec`, `BundleActionSpec`): preview, run, cancel, the audit line, the
      same service function as the admin interface
- [ ] T098 [P] [US7] `ReadOnlySpec`: with `read-only`, nothing above Observe runs and the Ops view says so
- [ ] T099 [P] [US7] `RedactionSpec` and `SupportBundleSpec`: a marker planted in every secret-bearing key appears in
      no frame and no bundle file; a path is shown; a URL loses its credentials; the bundle is owner-only (SC-012)
- [ ] T100 [P] [US7] `JsonRpcHttpServerSpec` and `JsonRpcWsServerSpec`: stop, then start, then a request answers; stop
      twice and start twice are harmless
- [ ] T101 [P] [US7] `ActionRunnerSpec`: a long action shows progress, cannot be started twice, survives a view
      change, reports its end, and never runs on the frame loop
- [ ] T102 [P] [US7] Goldens: the Ops view on ETC and ETH; each class of dialog; the configuration view; "not
      offered, and why"

### Implementation

- [ ] T103 [US7] `console/ops/Action.scala`, `Actions.scala`, `ActionRunner.scala`; the audit logger `fukuii.tui.ops`;
      `StdNode.scala` builds the registry from the services it holds
- [ ] T104 [P] [US7] The Adjust actions: log level (with the current levels and reset in `main/jsonrpc/AdminService.scala`),
      peer limit, trust, thresholds
- [ ] T105 [P] [US7] The peer and mining Disrupt actions; `a` on a peer and on the mining monitor
- [ ] T106 [US7] `main/jsonrpc/server/http/JsonRpcHttpServer.scala` and `JsonRpcWsServer.scala` keep their binding and
      gain `stop` and `start` (`conduit` reviews); the `rpc stop` and `rpc start` actions; a stopped server raises no
      alert
- [ ] T107 [US7] `main/db/dataSource/RocksDbDataSource.scala` whole-database compaction and an entry to
      `StorageConsistencyChecker` that returns its result (`vault` reviews); the `compact` and `dbcheck` actions
- [ ] T108 [P] [US7] `console/ops/Redaction.scala`, `ConfigView.scala`, `SupportBundle.scala`, `EndpointProbe.scala`;
      `main/network/ExternalIPDetector.scala` keeps its last result; `config`, `tunables`, `deps`, `test-rpc`, `net`,
      `detect-ip`, `bundle`
- [ ] T109 [US7] `quit` becomes a Halt with the typed word; its preview by family (FR-049, contracts/actions.md)
- [ ] T110 [US7] `console/panels/OpsPanel.scala`; read-only enforcement in `ActionRunner`
- [ ] T111 [US7] ADR under `docs/adr/`: the operator-action model (classes, one implementation per action, no RPC
      exposure, the read-only switch)

### Gate

- [ ] T112 The per-slice gate; quickstart.md "Slice 7"; `conduit`, `vault`, `prism` and `eye`; open the PR

---

## Phase 9: Closure

- [ ] T113 `./scripts/fukuii-test essential` once, alone; the count is at baseline (SC-013)
- [ ] T114 Coverage at or above 70 % (SC-015)
- [ ] T115 [P] Rewrite `docs/architecture/console-ui.md` from contracts/operator.md and contracts/actions.md; add the
      mock screens; note the four changes from spec 027
- [ ] T116 [P] Update the operator skills that name TUI keys (`.claude/skills/fukuii-log-triage`,
      `fukuii-node-health-check`) where they are now wrong
- [ ] T117 Outside `console/`, the diff of all slices touches only the files plan.md lists; `beacon` and `forge`
      reports attached (SC-013)
- [ ] T118 Set spec.md `**Status**` to `Done (<date>)` with the PR numbers, add the Closed block, remove
      `**In flight**`, run `python3 -I scripts/specs/index.py`; close the backlog items

---

## Dependencies

- Phase 0 is done. Phase 1 before everything. Slice 1 (phase 2) before every other slice. Slice 2 (phase 3) before the
  charts of slices 3 to 6 are seen in pixel or block mode; their text is not blocked by it.
- Slices 3 to 7 are independent of each other after slice 1. The plan's order is 3, 4, 5, 6, 7.
- B-189 before slice 1; B-196 before T070's Overview count; B-193 before T081.
- Within a slice: tests first, then implementation in the order listed, then the gate.

## Parallel work

Tasks marked [P] within one "Tests first" block touch different files and can be written together. Across slices,
slice N+1's tests and code can be written while slice N is in review; compiling waits for the one JVM.

## MVP

Slice 1 alone is shippable: spec 027's dashboard, driven from the keyboard, with lists, a prompt, filters and a `q`
that asks. Slice 2 on top of it is the first release that looks like the brief.
