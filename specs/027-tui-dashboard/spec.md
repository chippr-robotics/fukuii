# Feature Specification: TUI Dashboard — "the worm goes to the brain"

**Feature Branch**: `027-tui-dashboard`

**Created**: 2026-11-07

**Status**: Active (2026-11-09). Slice 1 (worms, glyphs, tiers) merged as #1752 and slice 4a (peer scoring) as #1782; slices 2, 3, 4 and 5 follow, one PR each, from the plan and 150 tasks merged as #1742. The manual gate runs (T029, T057, T081, T149 and later) are one UAT across all slices once they are code complete (user, 2026-11-09).

**In flight**: `feat/027-s2-motion` and `feat/027-s3-views` (`dev-node`, local session): slice 2 in PR; slice 3 in progress, on top of slice 2

**Input**: The design proposal #1490 carried as `docs/architecture/tui-design-proposal.md` ("the worm goes to the
brain": five phased increments, views, tiers, animation, glyph and colour system, data inventory, architecture, testing,
eight open questions), and the user's decision of 2026-11-07 to make it a spec and build all five phases this sprint,
each phase its own PR.

---

## Context

`fukuii <network> --tui` works today. #1490 made it functional and shipped to staging and main as #1492 (2026-10-07):
the TUI takes the terminal only after config validation, refuses a dumb or non-TTY terminal, draws every frame as
exactly `height` rows of exactly `width` columns, polls the node through `TuiStatusProbe`, and restores the terminal
on `q`, `d` and Ctrl-C. The code is `src/main/scala/com/chipprbots/ethereum/console/` (`Tui`, `TuiRenderer`,
`TuiState`, `TuiStatusProbe`, `TuiUpdater`, `TuiConfig`, `TuiLogSuppressor`), wired from `Fukuii.scala:40-41,90-100`
and `nodebuilder/StdNode.scala:288-317`.

This spec is the next increment. #1490 also carried a design proposal that was never ported. It is now this spec's
[research.md](./research.md), with every file:line reference refreshed against staging `f0b4a8627` and 16 corrections
against today's code (research §0). The proposal's sections are cited below as §n.

What is missing today: one fixed layout with no views; a plain block bar instead of the worm the logs already draw
(`WormToBrainBar`); no alert row; the probe's two blocking asks (up to 2 × 500 ms) sit on the frame loop; the splash is a
`Thread.sleep` with a figlet that spells "FOOO"; no ASCII or monochrome fallback; no log tail, peer list, fork timeline,
consensus-layer or mining status.

---

## Clarifications

### Session 2026-11-07

The proposal ended with eight open questions (research §10). The user answered all eight with the proposal's own
proposed defaults.

- Q1: Default frame rate? → A: 4 fps when anything animates and 1 fps otherwise, with a reduced-motion environment
  variable (`FUKUII_TUI_MOTION=reduced`). (user, 2026-11-07)
- Q2: Big logo on the splash only, and what when it does not fit? → A: The logo is cropped (creature only) where the
  whole logo does not fit, per the §3.1 tiers. (user, 2026-11-07)
- Q3: Glyph auto-detection? → A: ASCII glyphs when the locale is not UTF-8, plus the `g` runtime toggle. (user,
  2026-11-07)
- Q4: Log level in the ring? → A: INFO and above. (user, 2026-11-07)
- Q5: Peers view scores: hand the scoring manager to the probe, or export? → A: Peer scores are exported through
  `NetworkMetrics`; the scoring manager instance is not handed to the probe. (user, 2026-11-07)
- Q6: E1 placement? → A: "Last call at" timestamps go in `EngineApiMetrics`. (user, 2026-11-07)
- Q7: `d` naming? → A: The `d` key is labelled "Detach". (user, 2026-11-07)
- Q8: Celebrate once per process or once per sync? → A: Once per process, followed later by a quieter one-frame
  "✓ back at head". (user, 2026-11-07)

- Q (D-1): Q5 routes peer scores through `NetworkMetrics`, but nothing produces scores today: `PeerScoringManager` has
  never been constructed in production (research C-1). Where do the scores come from? → A: (a) Wire `PeerScoringManager`
  into production: construct the existing scorer, feed it from the peer events (a network change, routed through
  `herald`), and export the scores through `NetworkMetrics`, the Q5 path, where the TUI reads them. Every peer gets a
  score, not only SNAP peers. Delivered as slice 4a, before slice 4. (user, 2026-11-07)

### Correction 2026-11-08 (slice 1 review)

- `NO_COLOR` counts only when it is set to a non-empty value, as no-color.org now specifies. FR-009 and T008 said "any
  value, including empty", following research §5.1, which is corrected too (C-17). (coordinator decision on prism's
  review of slice 1, 2026-11-08)

Where the proposal stated a default (Q1, Q3, Q4, Q6, Q7, Q8) the answer is that default. Q2 and Q5 named options with
no default; the answer is the option the user chose. D-1 arose while writing this spec (plan.md).

---

## Goals

1. **Five-second rule.** On any view at any terminal size from 80×24, an operator reads network, peers, head vs best
   block, sync phase with ETA, and whether anything is wrong, within five seconds (§1.2).
2. **Degrade, never break.** 80×24, SSH, `screen`/`tmux`, a non-UTF-8 locale, `NO_COLOR` and reduced motion are
   first-class. Emoji, colour and motion are each removable without changing the layout (§1.3, §5).
3. **Never block the frame.** A slow or dead actor degrades one panel to `Unresponsive`; the rest keeps updating (§1.5).
4. **Charm encodes state.** The worm's position is progress; motion is a function of a frame counter, so every frame is
   a golden test (§1.1, §1.4).
5. **Drill-down.** Sync, Peers, Chain and Logs views, plus a consensus-layer panel on ETH and a mining panel on ETC.

## Non-goals

- **No consensus code.** Nothing under `consensus/`, `vm/`, `ledger/` block execution, `mpt/`, `rlp/`, `crypto/` or
  `domain/` changes behaviour. The only `consensus/` files touched are the two metrics objects of FR-022 and FR-023
  (`EngineApiMetrics`, `PoWMiningMetrics`) and two call sites that write to them (`EngineApiHttpServer`'s auth-failure
  path, `EthashDAGManager`'s progress logs). The per-chain panels (phase 5) **only read metrics**: E1–E3 and M1–M2 add state to metrics
  objects at existing call sites and change no response, status, timing or DAG content.
- No new actor, no new wire-protocol message, no new RPC method, no change to `--tui`'s default (off) or to standard
  logging.
- No arrow keys or other escape sequences as input (§2.2).
- The Sync view's **Fetcher** row (headers, bodies, queue depth) has no metric source today and is deferred (research
  C-7).
- Acting on peer scores. Wiring `PeerScoringManager` is in scope (slice 4a, D-1, FR-026), but its scores are
  observational: no dial, peer-selection, blacklist or disconnect decision reads them, and `getBestPeers`,
  `getBestPeersExcluding` and `shouldRetry` stay unused. Score-driven peer selection would be its own spec.
- Windows console hosts beyond what JLine already supports; mouse input; colour themes beyond the four palette tiers of
  §5.1.

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — The operator's five-second rule (Priority: P1) 🎯 MVP

An operator starts `fukuii etc --tui` (or Mordor, Sepolia, mainnet) and glances at the screen. The header and the
first four body rows tell them the network and chain id, PoW or PoS, uptime, peers, head vs best block, the sync phase
with a worm that sits where progress is, the ETA, and whether anything is wrong. SNAP sync shows five worm rows
(Overall, Accounts, Storage, Bytecode, Healing); regular sync shows one Blocks worm with rate and ETA.

**Why this priority**: it is the reason the TUI exists, and slice 1 alone changes the dashboard people already see.

**Independent Test**: golden frames of the Overview for the synced, SNAP mid-storage and regular-sync fixtures at
80×24, 120×40 and 160×48 in both glyph sets (SC-002).

**Acceptance Scenarios**:

1. **Given** a node in SNAP storage download, **When** the Overview renders at 80×24, **Then** rows 1–4 of the body show
   peers, current → best block, and the SNAP phase with five worm rows whose worm cells sit at
   `(progress × (slots − 1))` (§4.1).
2. **Given** a node at head, **When** the Overview renders at 160×48, **Then** the Sync row reads `🪱[COMPLETE ✓]🧠` and
   the 12-line logo sits in the right-hand column (§3.4).
3. **Given** a terminal of 120×40, **When** the node is regular-syncing, **Then** the Blocks worm, the rate and the ETA
   come from the TUI's own baseline (§3.3, §6).

---

### User Story 2 — An operator over SSH or in `screen`/`tmux` (Priority: P1)

An operator watches a remote node in an 80×24 `tmux` pane over SSH, sometimes from a console with no UTF-8 locale and
no colour. The frame never wraps or scrolls, emoji fall back to ASCII (`~o` worm, `@@` brain) with identical column
budgets, `NO_COLOR` turns colour off, reduced motion stops all movement, and an idle node writes at most one frame a
second. `d` detaches the TUI and the node keeps running, as `tmux` detaches.

**Why this priority**: a large share of operators run exactly this way, and a TUI that garbles their terminal is worse
than none.

**Independent Test**: the width-invariant property test (SC-001), the monochrome golden check (SC-007), the pacing and
diff-skip tests (SC-003), and the manual 80×24 `tmux` gate with `LANG=C` and `NO_COLOR=1` (SC-011).

**Acceptance Scenarios**:

1. **Given** `LANG=C`, **When** the TUI starts, **Then** it renders the ASCII glyph set and every row is exactly the
   terminal width (§3.10).
2. **Given** `NO_COLOR=1`, **When** any frame renders, **Then** it carries no foreground or background colour attribute
   (§5.1).
3. **Given** `FUKUII_TUI_MOTION=reduced`, **When** the node syncs, **Then** the worm moves only with progress, nothing
   else moves, and frames are paced at 1 fps (§4.5, Q1).
4. **Given** a synced idle node, **When** no value changes, **Then** the TUI writes no frame (diff-skip), and at most one
   per second when values change (§4.4).
5. **Given** the user presses `g`, **When** the next frame renders, **Then** the glyph set switches with no layout shift.

---

### User Story 3 — Alerts: when something is wrong, it wins (Priority: P1)

When the node has no peers, an actor stops answering, sync stalls, the SNAP sample goes stale, disk runs low, peers fall
below a quarter of the maximum, the consensus layer goes silent (ETH), or mining reports no hashrate (ETC), the alert
row says so in red or yellow. It is the first body row on every view, it is never animated, and the logo never hides
it. A slow actor shows as `Unresponsive` in its own panel while the rest of the screen keeps updating.

**Why this priority**: the second half of the five-second rule; a pretty dashboard that hides a stall is a regression.

**Independent Test**: one test per §2.3 condition with its threshold (SC-005, SC-008), golden frames of the alert
fixtures, and the slow-actor loop test (SC-005).

**Acceptance Scenarios**:

1. **Given** 0 handshaked peers for 31 s, **When** the frame renders, **Then** the alert row reads `✗ 1 error` with
   `0 peers for 31s` in the error style.
2. **Given** the peer manager never replies, **When** 1.5 s pass, **Then** the peers panel shows `Unresponsive`, the
   alert row lists it, the sync panel still shows fresh data, and frames keep advancing.
3. **Given** `currentBlock` unchanged for 121 s with blocks remaining, **When** the frame renders, **Then** the worm
   shows `💤` (ASCII `z`), its ripple stops, and the alert row shows the stall warning.
4. **Given** no condition holds, **Then** the alert row reads `✓ no alerts`.

---

### User Story 4 — Drill-down views and the log tail (Priority: P2)

The operator presses `2` for the Sync view (per-phase worms, rates, request counters, phase timeline), `3` for Peers
(direction, client, best block, age, and score with a trend), `4` for Chain (fork timeline by block
number on ETC, by timestamp on ETH, with the next fork), `5` or `l` for Logs (the last 1,000 INFO+ lines, scrollable with
`+`/`-`), and `?` or `h` for Help with the glyph legend. `Tab` cycles views.

**Why this priority**: answers "why?" after the Overview answers "is it OK?".

**Independent Test**: golden frames per view and tier; key-dispatch tests; the ring-appender tests (SC-009).

**Acceptance Scenarios**:

1. **Given** an ETH node, **When** the user presses `4`, **Then** the Chain view lists the timestamp forks in activation
   order, including the BPO forks, and the next scheduled fork with its UTC time.
2. **Given** the Logs view, **When** 1,500 INFO lines have been logged, **Then** the newest 1,000 are kept and `+`/`-`
   scroll them.
3. **Given** a peer whose client id contains an escape sequence, **When** the Peers view renders, **Then** the escape
   characters are stripped and the frame geometry holds (research C-16).
4. **Given** a handshaked peer whose requests start timing out, **When** the next scores are published, **Then** its
   score in the Peers view drops and its trend shows ↓ (D-1, FR-026).

---

### User Story 5 — Per-chain panels (Priority: P3)

On an ETH/Sepolia node the Overview shows the consensus-layer link: when `engine_forkchoiceUpdated` and
`engine_newPayload` were last called and with what status, head/safe/finalized, payload counts, and JWT auth failures.
On an ETC/Mordor node it shows mining: on/off, coinbase, hashrate, blocks mined, stale shares, getWork rate, and the DAG
epoch with its generation or load progress.

**Why this priority**: valuable for validators' execution clients and miners, but each serves one chain family.

**Independent Test**: metrics-object unit tests for the new state, panel goldens per state, and the E1 and M1 alerts.

**Acceptance Scenarios**:

1. **Given** an ETH node with no forkchoiceUpdated for 61 s, **Then** the alert row shows the CL-disconnected error and
   the panel shows the age and the last status.
2. **Given** an ETC node generating its DAG, **Then** the mining panel shows the epoch and the percentage with a spinner.
3. **Given** either half, **Then** the beacon (ETH) or forge (ETC) review reports that no Engine API response, status,
   timing or DAG content changed.

---

### Edge cases

- Terminal smaller than any tier (down to 1×1), or one that reports 0×0 (fallback 80×24, `Tui.scala:221`): frames stay
  exact; the footer stays on the last row.
- Resize mid-session: the tier is recomputed on the next frame and on `r`; stale glyphs are cleared (today's behaviour).
- Locale is UTF-8 but the font draws emoji badly: `g` switches to ASCII at runtime.
- A reorg or rewind lowers `currentBlock`: the baseline resets and the worm jumps back; this is correct information
  (§3.9).
- The SNAP sample is older than 90 s: the SNAP panel keeps its last values marked stale and the alert row warns.
- Multi-line, ANSI-laden, CJK or emoji log messages: sanitised to one line, control characters stripped, width fitted by
  `columnLength`.
- ETC chain with Olympia at `Long.MaxValue`: the Chain view says "not scheduled on this network".
- ETH node with no consensus client connected at start: the CL panel shows "waiting" until the first call, then ages.
- Mining disabled on ETC: the mining panel is one line ("off") and the hashrate alert does not fire.
- `p` or a view change during the celebration cancels it; the next catch-up shows only "✓ back at head".
- The TUI is detached with `d`, or attaching the log ring fails: the terminal is restored, console logging comes back,
  and the node keeps running.
- An invalid `fukuii.tui.*` value fails at startup with a named error; an invalid `FUKUII_TUI_*` value logs a warning
  naming the variable and the accepted values, then uses the default.

---

## Requirements *(mandatory)*

Each requirement names the research.md section it comes from.

### Functional requirements

**Frame and layout**

- **FR-001** Every frame MUST be exactly `height` rows of exactly `width` columns, measured with
  `AttributedString.columnLength()`, for every width and height ≥ 1, every view, tier, glyph set, theme and state. The
  footer MUST stay on the last row (§1.3, §8.2).
- **FR-002** Row order on every view MUST be header → alert row → view body → footer. The alert row MUST NOT be
  animated, scrolled off or covered by the logo (§1.1, §2.3).
- **FR-003** The layout MUST select tier S (< 100 columns or < 32 rows), M (100–139 columns and ≥ 32 rows) or L
  (≥ 140 columns and ≥ 45 rows) and fill it per the §3.1 table. 80×24 is a first-class target (§3, §3.1).
- **FR-004** The renderer MUST be a pure function of (state, width, height, theme). Motion MUST depend only on the
  state's frame counter `tick`; the renderer MUST NOT read the wall clock or a random source. Every `Instant.now()` in
  `TuiState` moves to the probe (§1.4, §4.2, C-3).
- **FR-005** A layout engine MUST allocate panel rows by minimum rows in priority order, then preferred rows, with the
  recent-log strip absorbing what is left; L-tier two-column layouts MUST pad per cell by `columnLength` (§7.3).

**Worm, glyphs, colour**

- **FR-006** The TUI worm bar MUST place the worm at `(progress × (slots − 1))` as `WormToBrainBar.renderKnown` does
  (`WormToBrainBar.scala:16`), take a column budget rather than a slot count, keep `[QUEUED]`, `[COMPLETE ✓]` and the
  `[ACTIVE…]` pulse at fixed widths, and draw the ripple at cell `wormAt − 1 − (tick mod 3)` except when `wormAt < 2`,
  when stalled, or under reduced motion. Progress unchanged for 120 s MUST show the stalled glyph and the warn style.
  `WormToBrainBar` (the log version) MUST NOT change (§4.1).
- **FR-007** SNAP sync MUST render five worm rows (Overall, Accounts, Storage, Bytecode, Healing), carrying into the TUI
  state the eight `SyncProgress` fields `toSnapSyncState` drops today; regular sync MUST render one Blocks worm with rate
  and ETA (§3.2, §3.5, §3.6, §6).
- **FR-008** The TUI MUST provide an emoji and an ASCII glyph set, each glyph declared with its column width per set
  (C-15). ASCII MUST be selected by `FUKUII_TUI_GLYPHS=ascii`, by `fukuii.tui.glyphs = ascii`, automatically when the
  locale is not UTF-8 (Q3), or at runtime with `g`. Captured log text MUST pass through the same glyph mapper. No glyph
  may contain U+FE0F; ambiguous-width glyphs are pinned to one column (§3.10, §5.2).
- **FR-009** Colours MUST come from one theme with the §5.1 roles. `NO_COLOR` set to a non-empty value (no-color.org;
  an empty value counts as unset), or fewer than 8 colours, MUST select the monochrome theme (bold and reverse only, no colour attributes). `NO_COLOR` MUST NOT imply reduced
  motion (§5.1, §4.5).

**Motion, loop, splash**

- **FR-010** Frames MUST be paced at 250 ms while anything on screen animates and at 1000 ms otherwise (Q1). A frame
  equal to the previous one MUST NOT be written. A key MUST be handled when it arrives, at either pace (§4.4, §7.2).
- **FR-011** Polling MUST run on its own thread every 1000 ms, issue its asks in parallel with one bounded wait
  (500 ms), and publish a snapshot the frame loop reads without waiting. Each source MUST carry its own freshness
  (`Fresh`, `Stale`, `Unresponsive`), so one slow source degrades only its own panel (§1.5, §7.1, §7.2).
- **FR-012** Reduced motion (`FUKUII_TUI_MOTION=reduced` or `fukuii.tui.reduced-motion = true`) MUST stop the ripple,
  spinner, pulse, splash reveal and header flash, and reduce the celebration to one frame. `p` MUST pause the frame
  counter while polling continues (§2.2, §4.5).
- **FR-013** The startup splash MUST be a view of the same renderer: no `Thread.sleep`, skippable by any key, disabled by
  `FUKUII_TUI_NO_SPLASH`. It MUST show the whole `printBanner` logo where it fits, the logo cropped to the creature where
  it does not but 52 rows do (Q2), the 12-line logo with a FUKUII wordmark at tier M, and the wordmark alone at tier S.
  The wordmark MUST spell FUKUII (§3.7, C-8).
- **FR-014** The celebration MUST run once per process, on the first `isSynchronized` false → true transition: 12 frames,
  header accent on frames 2–6, cancelled by `p` or a view change. Each later catch-up MUST show one quiet frame
  "✓ back at head" (Q8, §3.8, §4.3).

**Views, keys, alerts**

- **FR-015** The TUI MUST offer Overview, Sync, Peers, Chain, Logs and Help, bound to `1`–`5`, `Tab` (next, wraps), `l`
  (Logs), `?` and `h` (Help), with `p` (pause), `g` (glyphs), `+`/`-` (scroll the log tail), and today's `q`, `r` and `d`
  unchanged in behaviour. Input MUST stay single printable characters or `Tab`. The footer MUST label `d` "Detach" (Q7)
  (§2.1, §2.2).
- **FR-016** The alert row MUST evaluate the §2.3 conditions with their thresholds and severities: 0 peers > 30 s
  (error); peer manager or sync controller unresponsive (error); ETH no `engine_forkchoiceUpdated` > 60 s (error); ETH no
  `engine_newPayload` > 5 min while peers advance (warn); sync stalled > 120 s with blocks remaining (warn); a SNAP
  sample older than 90 s during sync (warn); disk free < 20 GB (warn) or < 5 GB (error); peers < 25 % of maximum (warn);
  ETC mining enabled with hashrate 0 for more than 10 min (warn). It MUST list up to three with a count and badge, and read `✓ no alerts` when
  none hold. The peer-count value keeps today's 50 % colour threshold (C-2) (§2.3).
- **FR-017** The Chain view MUST show the ETC fork timeline from the block-number forks (`MilestoneLog` names) against the
  current block, and the ETH timeline from `ForkTimestamps` ordered by activation time (including Amsterdam and the BPO
  forks) against the head timestamp, with "not scheduled" for an unscheduled fork. The PoW/PoS family MUST come from
  `BlockchainConfig.networkType` (§2.1, §6, C-4, C-5).

**Data**

- **FR-018** The Overview MUST show the §6 fields for its tier: network, chain id, family, uptime and view (all); head and
  peers inline (M+); version (L); peers and blocks; RPC, WS, IPC and Engine ports; disk free; txpool size with blob count;
  JVM heap and GC share (M+); discovery known/pending/blacklisted (L) (§3.1–§3.4, §6).
- **FR-019** The Peers view MUST show id, address, direction and age (`GetPeersCmd`), and client id and best block
  (`GetHandshakedPeersCmd`). Peer scores and their ↑↓→ trend MUST reach the TUI through the `NetworkMetrics` export, never
  by handing a scoring manager to the probe (Q5), and come from the production `PeerScoringManager` (D-1, FR-026). The
  score column is on; a peer with no published score yet shows `—`, never zero (§6).
- **FR-020** Every string from outside the node that reaches a frame (peer client ids, log text) MUST be sanitised: ESC,
  C0 and C1 control characters removed, one line, length capped (C-16).
- **FR-021** The log tail MUST come from a separate ring appender of 1,000 lines (configurable), INFO and above by
  default with `fukuii.tui.log-level` to override (Q4), keeping the logger name for the "Recent (sync)" filter. Attaching
  and detaching it MUST go through the TUI's teardown ladder and MUST NOT change the set of console appenders the
  suppressor detaches and restores (§7.4, C-11, C-13).
- **FR-022** On ETH, the consensus-layer panel MUST show the last `engine_forkchoiceUpdated` and `engine_newPayload` call
  times and statuses (E1, kept in `EngineApiMetrics`, Q6), head/safe/finalized (E2, from `ForkChoiceManager.getState`),
  payload counts, and JWT authentication failures (E3, a counter at the existing auth site in `EngineApiHttpServer`)
  (§6).
- **FR-023** On ETC, the mining panel MUST show mining on/off, coinbase, hashrate, blocks mined, last mined, stale shares
  and getWork rate (M1, from `PoWMiningMetrics`), and the DAG epoch with generation or load progress (M2, gauges updated
  at the two existing progress-log sites in `EthashDAGManager`) (§6).
- **FR-024** TUI settings MUST be read from HOCON under `fukuii.tui` (a new `conf/base/tui.conf`) with the environment
  overrides `NO_COLOR`, `FUKUII_TUI_GLYPHS`, `FUKUII_TUI_MOTION` and `FUKUII_TUI_NO_SPLASH`; `Fukuii.scala` and
  `StdNode.scala` MUST use the loaded `TuiConfig` instead of `TuiConfig.default` (§7.5, C-12).
- **FR-025** No panel may change node behaviour. Data comes from existing actor messages, `MetricsContainer` objects,
  config and two node-local reads (disk free, the best block header's timestamp). New plumbing is limited to metrics
  objects, the log appender, the probe, and slice 4a's scorer wiring (FR-026) (§1.6).

**Peer scoring (slice 4a, D-1)**

- **FR-026** One `PeerScoringManager` per node MUST be constructed and fed from the peer events its API names:
  successful and failed handshakes, responses with latency, request timeouts, protocol violations, and blacklisting of a
  peer id. Every handshaked peer MUST get a score. The scores MUST be exported through `NetworkMetrics` as an in-process
  snapshot plus aggregate gauges, with no per-peer metric series. The scorer MUST be safe under concurrent events, bound
  its memory, take an injectable clock, and never raise a score on a blacklist event. No dial, selection, blacklist or
  disconnect decision may read the scores (non-goals).

### Key entities

- **TuiState**: the frame's whole input: `tick`, view, motion (`Full | Reduced | Paused`), glyph set, splash and
  celebration markers, the time progress last advanced (durations use the snapshot's clock, not tick counts, because
  the pace varies), the polled snapshot, a bounded history (recent rates, score deltas), the log
  lines, node settings and chain facts (§7.1).
- **NodeStatusSnapshot**: what one poll saw, with `now` and per-source `Sourced[A] = Fresh(a, at) | Stale(last, since) |
  Unresponsive(reason)` for peers, sync, txpool, consensus layer and mining (§7.1).
- **Tier, View, Panel, Layout**: the size class, the screen, a block of rows with min/preferred rows and a priority, and
  the allocator (§7.3).
- **GlyphSet / TuiGlyphs, TuiTheme**: per-set glyphs with declared widths; the colour roles and palette tier (§5).
- **WormBar**: the TUI twin of `WormToBrainBar`, budgeted in columns and animated by `tick` (§4.1, §7.3).
- **TuiLogRingAppender / LogLine**: the bounded, sanitised log tail with level and logger name (§7.4).
- **ChainFacts**: chain id, family, fork timeline (§6).
- **PeerScoringManager / PeerScore**: the existing scorer (`network/`), wired in slice 4a; its snapshot in
  `NetworkMetrics` is what the Peers view reads (FR-026).

---

## Success Criteria *(mandatory)*

### Measurable outcomes

- **SC-001 Frame geometry.** Zero violations of FR-001 over every golden fixture and over at least 1,000 generated
  states per run at widths 20–200 and heights 3–80, plus 1×1, in both glyph sets.
- **SC-002 Five-second rule.** On the synced, SNAP, regular-sync and alert fixtures at 80×24, 120×40 and 160×48, the
  header and the first four body rows contain the network, the peer count, current vs best block, the sync phase with
  ETA (or "at head"), and the alert summary.
- **SC-003 CPU and bytes.** The TUI's threads use under 2 % of one core averaged over 60 s at 4 fps on a 160×48 pane, and
  under 0.5 % at 1 fps idle; a 160×48 frame renders in under 1 ms (median of 100 renders). With nothing changing, no
  frame is written; with values changing once a second, at most one frame is written per second at 1 fps.
- **SC-004 Input latency.** At 1000 ms pacing, `q` is dispatched within 500 ms of being typed (asserted in
  `TuiUpdaterSpec`; a frame-boundary-only implementation would take up to 1000 ms), and within 100 ms in the manual gate.
- **SC-005 No blocking on a slow actor.** With the peer manager stubbed to never reply, frames keep advancing at the
  configured pace (≥ 8 frames in 2.5 s at 4 fps), the peers panel shows `Unresponsive` within 1.5 s, and the sync panel
  keeps showing fresh data.
- **SC-006 Glyph widths.** For every glyph in both sets, the sum of `WCWidth.wcwidth` over its code points equals its
  declared width.
- **SC-007 Monochrome.** Every golden fixture rendered with the monochrome theme carries no colour attribute.
- **SC-008 Determinism.** Rendering the same state twice gives equal frames; under reduced motion, frames for ticks 0–11
  are identical except the single celebration frame; paused frames are identical across ticks; the ripple cell matches
  the FR-006 formula for ticks 0–11.
- **SC-009 Log ring.** Attaching and detaching the ring leaves the root logger's appender set identical; after 1,500
  lines the ring holds the newest 1,000; ESC and control characters never reach a frame.
- **SC-010 No consensus change.** Outside `console/`, the diff of all six slices touches only the files plan.md lists;
  beacon and forge report no consensus semantics changed for phase 5; `testEssential` is at baseline at the end.
- **SC-011 Manual gate.** Each slice's PR carries `tmux capture-pane` transcripts from an 80×24 and a 160×48 pane (and,
  from slice 1, `LANG=C` and `NO_COLOR=1` runs) showing no wrap, no scroll, no stray glyph, and a clean exit on `q`, `d`
  and Ctrl-C.
- **SC-012 Coverage.** Statement coverage stays at or above 70 % (constitution III).
- **SC-013 Scorer.** With a fixed clock, a recorded timeout for a peer lowers that peer's exported score, and a
  blacklist event never raises it; events for one peer from four threads lose no update; the score map never exceeds its
  cap; on a live Mordor node the number of exported scores covers every handshaked peer.

## Assumptions

- JLine stays on 3.30.13 (`project/Dependencies.scala:157`; 4.x is deferred) and Logback on 1.5.x; no new dependency.
- Operators use terminals that report their size and colour depth through terminfo (xterm-256color, tmux, screen);
  where a UTF-8 locale's font draws emoji badly, `g` is the remedy.
- The TUI remains opt-in (`--tui`) and runs only on an interactive TTY, as today.
- Phase 5's ETH and ETC halves may land in one PR or two; either way they are one slice.
- Slice 4a lands before slice 4, so slice 4 ships with the score column on.

## Related specs

- **024 chain-registry**: adds `ChainSpec.consensus`; once it lands, the TUI reads the family from it instead of
  `BlockchainConfig.networkType` (C-4).
- **022 snap-heal-convergence**: owns `SNAPSyncMetrics` (its heal gauges are at `:32-52`). This spec only adds read
  accessors there; slices that touch it rebase onto 022's latest before review.
- **020 Scala 3.9**: new code builds under `-source:3.9` and the DC-4 warning ratchet (#1717): extension methods, not
  implicit classes; no new warnings.
- **012 remember-good-snap-peers**: `SnapGoodPeers` keeps its own decayed score for SNAP servers and is unchanged. D-1
  chose `PeerScoringManager` instead, so every peer is scored; the two scores are independent.
