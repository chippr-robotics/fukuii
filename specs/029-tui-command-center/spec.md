# Feature Specification: TUI Command Center

**Feature Branch**: `029-tui-command-center`

**Created**: 2026-11-13

**Status**: Draft (2026-11-13). Written from the user's brief of 2026-11-13; three scope questions (D-1 to D-3) are open and there is no plan yet.

**In flight**: `ccr-452bbde8-2hrv25` (cloud session, B-244): spec and research in PR; the plan and tasks follow the answers to D-1 to D-3

**Input**: The user's brief of 2026-11-13: "the tui has basic ascii graphics currently and no real input controls …
we can use Kitty Graphics Protocol to build a vga inspired execution command center … a 1980s arcade-style Network
Operations Center combined with a fully functional Ethereum execution-client control terminal. The key design
principle: it should look like a video game, but behave like serious infrastructure software. The game aesthetic is the
interface, not a simulation of the underlying functionality." With it came three concept images
([`mockups/`](./mockups/)) and a feature list in six groups: chain intelligence with a Block Observatory, a Mempool
Radar, RPC and Engine API monitoring with an Engine Synchronization Monitor, storage and system operations with
Infrastructure Health, and operations and administration with explicit confirmation of destructive actions. Design
record: [research.md](./research.md), cited below as §n.

---

## Context

`fukuii <network> --tui` is spec 027's dashboard: five views of text and emoji, a worm for a progress bar, an alert
row, a log tail, and keys that are single characters. Its renderer is a pure function, its frames are exact, and a
slow actor degrades one panel. That is the wiring this spec builds on (§2).

Three things are missing for what the brief describes:

- **It cannot be driven.** There is no selection, no scrolling list, no drill-down, no way to type a block number.
  Arrow keys are read and thrown away. The only key that acts on the node is `q`, which stops it at once.
- **It has no graphics.** Everything is character cells. The terminals many operators use can draw pixels.
- **It shows a tenth of what the node knows.** Nothing about recent blocks, fees, the transaction pool, RPC traffic,
  the database or the machine, although most of it is one read away (§6).

The brief's rule cuts both ways. The interface may look like an arcade cabinet. Every element in it must still be
true: a number has a source, a picture encodes a state, and a control does what its preview says.

fukuii runs two chain families. Ethereum Classic and Mordor are proof of work: mining, no consensus client, no slots,
a base fee only after Olympia. Ethereum, Sepolia, Hoodi and the others are proof of stake: a consensus client over
the Engine API, base fee, blobs. Every view says which it is on, and shows only what that family has.

---

## Clarifications

### Open (asked 2026-11-13)

Three choices change the scope and are the user's to make. Each is marked `[NEEDS CLARIFICATION]` where it applies;
the options and a recommendation are in research §9.

- **D-1**: starting and restarting the node from a TUI that runs inside it (FR-055).
- **D-2**: the topology panel: a peer radar, or a world map that needs a bundled location database (FR-030).
- **D-3**: who draws the sprites (FR-060).

### Defaults taken without asking

Each is reversible in review (§9):

- Operator actions are available unless `fukuii.tui.read-only` is set.
- Under tmux and screen the graphics are half-block cells, not Kitty pixels.
- `q` asks before it stops the node.
- The views are renumbered `1`–`9`, and `Tab` moves focus between panels instead of cycling views.
- A slot number is shown only when the block header carries one.

---

## Goals

1. **Driven from the keyboard.** Every panel, list, lookup and action is reached with keys, at any terminal size from
   80×24.
2. **A command center that is still a terminal.** Pixel graphics where the terminal draws them, block graphics where
   it does not, plain text where neither works. One layout for all three. Nothing is lost by losing the pixels.
3. **The node explains itself.** Chain, fees, pool, RPC, consensus-client link, database and machine can be read
   without running an RPC command.
4. **Honest by construction.** No element depicts something the node does not know.
5. **Operable.** The actions an operator takes on a running node can be taken here, each with a preview of exactly
   what will happen, a confirmation in proportion to what it can break, and a line in the log.

## Non-goals

- **No consensus change.** As in spec 027: nothing under `consensus/`, `vm/`, `ledger/` block execution, `mpt/`,
  `rlp/`, `crypto/` or `domain/` changes behaviour. Observation added there is state written at existing call sites.
- **No new network-reachable interface.** No new RPC method, no new port. New actions are called in process.
- **No new dependency.** Compression and the management beans come from the JDK. A bundled location database, if D-2
  chooses a map, is the one exception and is justified in the plan.
- **No mouse.**
- **No other image protocol**: no Sixel, no iTerm2 inline images.
- **No Kitty pixels through tmux or screen** in the first release. Block graphics are the answer there. The frame
  model allows it later (§5.2).
- **No action that deletes or rewrites chain data.**
- **No snapshots** (spec 013 is a draft), no configuration reload from file, no restart of the Engine API server, peer
  discovery or sync (§7.2).
- **No sound, no themes or skins, no CRT bezel or scan-line effects.**
- **No decision reads a peer score**, as in spec 027.
- **No disk I/O rate.** The JVM has no portable source for it (§6.5).

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Drive it with the keyboard (Priority: P1) 🎯 MVP

An operator opens the TUI and moves through it the way they move through any full-screen program. Number keys or the
menu choose a view. `Tab` moves between the panels of a view. Arrow keys move the selection in a list, and Page Up,
Page Down, Home and End scroll it. Enter opens the selected row: a peer's details, a block's transactions, a
transaction's receipt. Esc goes back to exactly where they were. `:` opens a prompt where they type `block 21234567`
or `tx 0x…` or the name of a view. `/` filters the list in focus. F1 or `?` lists every key that works right now.

**Why this priority**: every later story needs selection, scrolling, dialogs and a prompt. It is also useful alone:
spec 027's peers and logs become lists that can be walked and filtered.

**Independent Test**: key-dispatch tests over the decoder and the focus model; golden frames of a focused list, a
dialog and the prompt at 80×24 and 160×48; the generated-input test of SC-003.

**Acceptance Scenarios**:

1. **Given** the Peers view with 40 peers at 80×24, **When** the operator presses Down 12 times and Enter, **Then** the
   thirteenth peer's details open, and Esc returns to the list with that peer still selected and in view.
2. **Given** any view, **When** the terminal sends a sequence the TUI does not know, or a reply to a question the TUI
   asked, **Then** no key is dispatched.
3. **Given** the operator presses Esc alone and, later, `d`, **Then** the first closes the dialog or goes back and the
   second is the `d` key; it is never read as Left-arrow.
4. **Given** the prompt, **When** the operator types `bl`, Tab, a block number and Enter, **Then** the block opens;
   when they type an unknown command, the prompt names the closest ones and nothing else happens.
5. **Given** a terminal or link that delivers no arrow or function keys, **Then** every list, dialog and action is
   still reachable with letters, digits, Tab and Enter.
6. **Given** a view at 80×24, **Then** the footer shows the keys of the panel in focus, and Help shows all of them.

---

### User Story 2 — The command center look (Priority: P1)

The operator starts the TUI in kitty or Ghostty and sees the command center: the badge, pixel panels, bar histories,
the mascot at its console. The mascot is the operator's status lamp: it types while the node syncs, watches the
screens at the head, turns to the alert row on a warning, stands under an alarm lamp on an error, sleeps when sync
stalls, and celebrates once when the node first reaches the head. The splash shows the node's real start-up phases
ticking off.

The same operator opens the same node in tmux over SSH and sees the same layout with the art drawn in half-block
cells: coarser, the same colours, the same information. On a console with no colour they see spec 027's text. A key
switches between the three at any time and nothing on screen moves.

**Why this priority**: it is the memorable part of the brief, and the frame model has to carry pixels before any view
is designed around them.

**Independent Test**: golden frames of every graphic region in the three modes; the information-parity test (SC-005);
the encoder's byte-level tests; the clean-up test (SC-006); the manual gate on real terminals (SC-014).

**Acceptance Scenarios**:

1. **Given** a terminal that answers the graphics query and the cell-size query, **When** the TUI starts with default
   settings, **Then** it draws in pixel mode; **Given** one that answers neither within the bound, **Then** it draws in
   block mode and no reply is ever dispatched as a key.
2. **Given** `TMUX` is set, or the terminal type is a multiplexer's, **Then** the default is block mode.
3. **Given** `NO_COLOR` is set or the terminal has fewer than 256 colours, **Then** the mode is text and the frame is
   spec 027's.
4. **Given** any frame, **When** the mode is switched, **Then** every text cell outside the graphic regions is
   unchanged.
5. **Given** the node has an error alert, **Then** the mascot shows the alarm pose, the alert row reads as it does
   today, and the mascot covers no part of it.
6. **Given** reduced motion, **Then** no sprite moves, and each shows the pose for the node's state.
7. **Given** the operator presses `q` and confirms, `d`, or Ctrl-C, or the terminal is lost, **Then** no image the TUI
   placed remains on the terminal.
8. **Given** the splash, **Then** each line of its checklist is a start-up phase of the node, checked when that phase
   has completed.

---

### User Story 3 — Chain intelligence (Priority: P1)

The operator opens the Chain view to see what the node is doing with the chain. The head panel shows number, hash,
age, transactions, gas used against the limit, size and base fee. The **Block Observatory** shows the recent blocks
side by side: one column per block, its height the gas used, its colour the fee level, with the numbers in a table
beneath; a new block slides in as it is imported. A fee panel shows the base fee and priority fees over the recent
blocks. A reorg appears in the history with its depth and both tips, and raises an alert. Enter on a block opens it;
Enter on a transaction opens its receipt; `account 0x…` at the prompt shows balance, nonce and whether it is a
contract.

**Why this priority**: it is the first group in the brief and the reason an operator would keep the TUI open on a
synced node.

**Independent Test**: fixture chains fed to the head observer (a straight chain, a gap during fast import, a reorg of
depth 3); golden frames of the Observatory in three modes; lookup tests with a stubbed slow reader.

**Acceptance Scenarios**:

1. **Given** a synced node, **When** a block is imported, **Then** it appears in the Observatory and the head panel
   within 2 s.
2. **Given** the head moves from a block to one that is not its descendant, **Then** the reorg history gains an entry
   with the depth, the old and new tips and the time, and the alert row reports it when the depth is at or above the
   threshold.
3. **Given** an ETC node before Olympia, **Then** no base fee is shown and the fee panel shows gas prices; **Given** an
   ETH node, **Then** base fee and priority-fee percentiles are shown; **Given** a header with no slot number, **Then**
   no slot is shown.
4. **Given** a lookup that takes longer than 2 s, **Then** the panel shows it is working, frames keep advancing, and
   Esc cancels it.
5. **Given** a lookup for state the node has pruned, or a hash it does not have, **Then** the panel says which, in
   words.
6. **Given** the node imports thousands of blocks a second during sync, **Then** the Observatory shows a sample and
   says it is sampled, and the head observer reads a bounded number of blocks per second.

---

### User Story 4 — Mempool Radar (Priority: P2)

The operator opens the Mempool view. The **Radar** sorts pending transactions into four fee tiers — low, normal, high,
urgent — measured against the fee the node itself would suggest, and shows the tiers as a histogram that changes as
transactions arrive and leave. Beside it: how many are pending and how full the pool is, how many arrive and leave
each second, how many of those that left were included in a block and how many were dropped, the oldest, the busiest
senders, replacements, blob transactions. Enter on a transaction opens it.

**Why this priority**: valuable on a node that serves transactions; most of it is derived by watching the pool, so it
needs little new plumbing.

**Independent Test**: a fixture pool with hand-computed tier counts; successive snapshots with known additions,
inclusions, drops and replacements; the snapshot-cadence test (SC-009).

**Acceptance Scenarios**:

1. **Given** a pool of known transactions and a known suggested fee, **Then** the four tier counts match the
   hand-computed ones and the tier boundaries are shown in gwei.
2. **Given** a transaction leaves the pool and appears in an observed block, **Then** it counts as included;
   otherwise as dropped.
3. **Given** the same sender and nonce with a new hash, **Then** it counts as a replacement.
4. **Given** this client keeps no queue of transactions with nonce gaps, **Then** the panel says so and shows no
   number for it.
5. **Given** any other view is open, **Then** the whole pool is not read; **Given** the Mempool view is open, **Then**
   it is read at most once a second.

---

### User Story 5 — RPC and the consensus-client link (Priority: P2)

The operator opens the RPC view. A table lists each JSON-RPC method that has been called: calls, errors, median and
tail latency, when last. Above it, each transport — HTTP, WebSocket, IPC, the Engine API — with whether it is up,
where it listens, which namespaces it serves, and a warning when an admin namespace is reachable from another host.

On an ETH node the **Engine Synchronization Monitor** fills the rest: the consensus client on one side, fukuii on the
other, and the link between them pulsing once for each call that actually arrives. Under it: the consensus client's
name and version when it has said them, the last `forkchoiceUpdated` and `newPayload` with their status and how long
each took, the head, safe and finalized blocks with the distance between them, authentication failures with their
reasons, and a scrolling log of recent calls. On an ETC node the same place holds the mining monitor.

**Why this priority**: the Engine API is the link a validator's uptime depends on, and "is my RPC slow or erroring?"
has no answer in the node today.

**Independent Test**: recorded latencies against known percentiles (SC-010); golden frames per link state; the
`beacon` and `forge` reports of FR-042.

**Acceptance Scenarios**:

1. **Given** a method that has failed 3 times in 100 calls, **Then** its row shows 100 calls, 3 errors, and latency
   that includes the failed calls.
2. **Given** an ETH node whose consensus client last called 61 s ago, **Then** the monitor shows the link down with
   the age and the last status, and the alert row reports it as it does today.
3. **Given** two `newPayload` calls arrive between two frames, **Then** the link pulses and the call log gains two
   entries with status and duration.
4. **Given** an admin namespace served on a non-loopback address, **Then** the transport's row carries the warning.
5. **Given** an ETC node, **Then** no consensus-client element is shown and the mining monitor is.

---

### User Story 6 — Infrastructure Health (Priority: P2)

The operator opens the System view, or looks at the health strip on the Overview. Each subsystem — P2P, chain and
sync, consensus-client link or mining, transaction pool, RPC, database, disk, memory, CPU — has a bar like a game's
health bar. The bar is not a mood: it is the headroom to that subsystem's nearest threshold, and beside it is the
check that sets it ("Disk: 38 GiB free, warn below 20 GiB"). When a bar drops, Enter on it lists every check behind
it with its value, its threshold and since when.

The Storage view shows the database: its size and how fast it grows, what each store holds, whether compaction is
running or writes are stalled, the caches, the pruning mode and state scheme, and the progress of the integrity audit.
The System view shows CPU, memory, garbage collection, threads, uptime and network throughput, each with a short
history.

**Why this priority**: it turns the alert row's "something is wrong" into "what, and how close was it".

**Independent Test**: fixtures per subsystem at healthy, warning and error; the bar-and-alert agreement test (SC-011);
golden frames of the drill-down.

**Acceptance Scenarios**:

1. **Given** disk free at 38 GiB with thresholds 20 and 5, **Then** the disk bar shows its headroom and names the
   check; at 19 GiB the bar and the alert row both show the warning; at no point does one show a problem the other
   does not.
2. **Given** a degraded bar, **When** the operator presses Enter on it, **Then** every check of that subsystem is
   listed with value, threshold, state and time since the state began.
3. **Given** database statistics are switched off in the node's configuration, **Then** the cache hit rate reads
   "statistics off", not zero.
4. **Given** `fukuii.tui.alerts.disk-warn = 50 GiB`, **Then** the disk warning fires below 50 GiB; **Given** an invalid
   value, **Then** the node fails at start with an error naming the key.

---

### User Story 7 — Operate the node (Priority: P2)

The operator opens the Ops view, or selects a peer, or types an action's name at the prompt. Each action opens a
dialog that says exactly what will happen, computed from the node's state at that moment. Changing a log level shows
the level before and after and takes Enter. Disconnecting a peer, stopping mining, or stopping the HTTP server shows
what is interrupted and takes `y`. Stopping the node shows everything that stops with it — on an ETH node, that a
validator using it will miss duties — and takes typing the word the dialog shows. Every attempt is a line in the log.

The view also answers questions without changing anything: the effective configuration with secrets hidden, the
settings that can change without a restart, whether each RPC endpoint answers, what address the node believes it has.
It can write a support bundle whose contents the preview lists file by file.

On a shared screen the operator starts the node with `fukuii.tui.read-only = true`, and no action can run.

**Why this priority**: this is where the TUI becomes more than a dashboard, and where a mistake costs the most, so it
lands after the input and dialog work has been used by the read-only stories.

**Independent Test**: for every action, tests of its preview, its confirmation, its cancellation and its log line;
the read-only test; the pasted-input test; the planted-secret test (SC-012).

**Acceptance Scenarios**:

1. **Given** the operator presses `q`, **Then** the node does not stop; a dialog lists what stopping it stops, and only
   typing the shown word and Enter stops it. Ctrl-C stops it as before.
2. **Given** a confirmation dialog is open, **When** text is pasted into the terminal, **Then** the action does not
   run.
3. **Given** a preview that says a peer is connected, **When** the peer disconnects before the operator confirms,
   **Then** the dialog shows the new state and asks again.
4. **Given** read-only mode, **Then** no action dialog can be confirmed, the Ops view says the TUI is read-only, and
   the read-only screens still work.
5. **Given** the configuration view or a support bundle, **Then** no value of a secret-bearing key appears; a path is
   shown, a file's contents are not.
6. **Given** a log-level change through the TUI and the same change through the admin interface, **Then** both run the
   same code, and the TUI needs no RPC namespace enabled.
7. **Given** a compaction is running, **Then** it shows progress, cannot be started a second time, continues while the
   operator changes view, and reports when it ends.

---

### Edge Cases

- **Resize or `r`** in pixel mode: the screen is cleared, so every image is placed again, and the cell size is asked
  again because a font change alters it.
- **The terminal stops answering mid-session**, or pixel mode fails to draw: `g`-style switching to block or text is
  always available and needs no answer from the terminal.
- **A terminal that claims graphics support but draws nothing**: the operator switches mode by key or by
  `FUKUII_TUI_GRAPHICS`; the text carries everything, so nothing is lost meanwhile.
- **The terminal is lost with images on screen** (SSH drop, JLine's shutdown hook wins the race): the clean-up goes
  through the process stream with the screen reset, as spec 027's teardown does.
- **A lone Esc over a slow link** whose following key arrives late: Esc, then the key. A sequence split across two
  reads is one key.
- **An escape sequence longer than the decoder's bound, or never terminated**: dropped at the bound; the bytes after
  it are keys again.
- **Paste into a list, a dialog, or the prompt**: ignored, ignored, inserted as text with control characters removed.
- **A terminal smaller than a dialog**: the dialog shrinks to the alert row and one line with its question; an action
  that cannot show its preview cannot be confirmed.
- **Head observer during SNAP**: the head header exists and its body may not; the Observatory shows what the header
  gives and marks the rest unknown.
- **A reorg deeper than the observer's walk bound**: recorded as "at least N deep".
- **An empty pool, a node with no RPC traffic, an ETH node with no consensus client yet**: each panel says so; no
  chart is drawn from nothing.
- **A lookup of a contract with megabytes of code**: the size is shown, the code is not.
- **Two operators, two terminals**: only one TUI runs per node (as today); the other uses the log.
- **An action whose target vanished** (the peer left, mining was already stopped): the result says so; it is not an
  error.
- **The node is shutting down while a dialog is open**: the dialog closes and the terminal is restored.
- **Database property reads while a long write holds the database**: the Storage panel shows its last values marked
  stale; frames keep advancing.

---

## Requirements *(mandatory)*

Each requirement names the research section it rests on.

### Functional Requirements

**What carries over from spec 027**

- **FR-001** Every invariant of spec 027 holds for every new view, dialog and mode: exact frames (027 FR-001), the row
  order with the alert row first (FR-002), a renderer that reads no clock (FR-004), a frame loop no source can block
  (FR-011), and sanitised external strings (FR-020). The text of a frame MUST carry every value and state on screen:
  removing the graphic layer loses no information (§5.1).
- **FR-002** 80×24, no colour, ASCII glyphs and reduced motion stay first-class: every view, dialog and action MUST be
  usable there (§2).

**Input**

- **FR-003** The TUI MUST accept the arrow keys, Page Up, Page Down, Home, End, Enter, Esc, Backspace, Tab, Shift-Tab
  and F1, and MUST offer a printable-key way to do everything those do, so that a link that delivers no escape
  sequences loses no function (§3 E3).
- **FR-004** An escape sequence or a terminal's reply MUST never be dispatched as its characters. A sequence the TUI
  does not know is dropped whole. A lone Esc is recognised within 150 ms. A sequence that arrives in pieces is one key
  (§3 E3, E4; §2 R1).
- **FR-005** Each view has one panel in focus, shown as such. Lists have a selected row that stays in view. Enter
  opens the selection; Esc or Backspace returns to the previous screen with its focus, selection and scroll position
  as they were (§2 R3).
- **FR-006** Views are chosen with `1`–`9` and from a menu shown where the terminal is wide enough: Overview, Chain,
  Peers, Mempool, RPC (with the Engine monitor on ETH and the mining monitor on ETC), Storage, System, Logs, Ops.
  Sync status leads the Chain view while the node is syncing. The footer shows the keys of the panel in focus; Help
  lists every key active in the current context. No key works that Help does not list (§1, §2 R3).
- **FR-007** A prompt, opened with `:`, MUST take commands to open a view, look up a block, transaction, receipt,
  account or peer, and start an action by name. It has line editing, a history for the session and completion. An
  unknown command names the closest ones and does nothing (§1).
- **FR-008** `/` MUST filter the list in focus (logs, peers, transactions, RPC methods, configuration keys) as the
  operator types.
- **FR-009** Pasted text MUST be accepted only by a text field. Elsewhere it is ignored. Pasted text MUST never
  confirm an action (§8.1).
- **FR-010** A key MUST be handled when it arrives, at either frame pace, and no lookup, action or slow source may
  delay it (027 FR-010, FR-011).

**Graphics**

- **FR-011** The TUI MUST draw in one of three modes: **pixel** (the Kitty graphics protocol), **block** (half-block
  character cells) or **text** (spec 027's glyphs). The mode is chosen automatically, can be set by
  `fukuii.tui.graphics` and `FUKUII_TUI_GRAPHICS`, and can be changed by a key while running (§5.1).
- **FR-012** A graphic region MUST occupy the same rectangle of cells in every mode. Changing mode changes no cell
  outside the graphic regions (§5.1).
- **FR-013** Pixel mode MUST be chosen only when the terminal itself answers that it supports the protocol and
  reports its cell size, never from the terminal's name. The questions are asked once, before any key is read, within
  a bound of 500 ms; with no answer the mode is block. Under a terminal multiplexer the automatic choice is block.
  With `NO_COLOR` or fewer than 256 colours it is text (§3 E4, §4.2, §4.5, §5.6).
- **FR-014** All art MUST use one fixed palette that every 256-colour terminal draws exactly, so that block mode shows
  the same colours as pixel mode (§3 E2, §5.4).
- **FR-015** Pixel art MUST stay sharp: an image is enlarged by a whole factor to the pixels of the cells it covers,
  and the terminal is given nothing to scale (§4.5).
- **FR-016** No image may outlive the TUI. Every image is removed on `q`, `d`, Ctrl-C and a lost terminal, and placed
  again after a resize or `r` (§4.4, §5.6).
- **FR-017** Sprite sheets are sent to the terminal once. A moving sprite MUST then cost no pixel data per frame, and a
  chart MUST be redrawn only when its data changes, at most once a second (§5.3).
- **FR-018** The mascot MUST show the node's state and nothing else: starting, syncing, at the head, a warning, an
  error, stalled, and the one celebration of spec 027. It never covers the alert row, never moves in a confirmation
  dialog, and never reacts to an action except through the state the action leaves (§5.5).
- **FR-019** Which sprite frame is shown is a function of the frame counter and the state, as all motion is in spec
  027. Reduced motion and pause MUST stop sprites as they stop the worm (§5.3).
- **FR-020** The splash MUST show the node's real start-up phases, each marked done when it has completed, and its
  progress bar MUST be the share of phases done (§1).
- **FR-021** Every element on screen MUST either encode something the node knows or be decoration that never changes.
  Nothing may depict what the node does not know: no link between two peers, no place without a location source, no
  slot without one in the header. What this client does not keep is absent or says "not kept by this client". A zero
  is only ever a measured zero (§1, §8.5).

**Chain**

- **FR-022** The head panel MUST show the head's number, hash, timestamp and age, transaction count, gas used against
  the limit, size, and base fee where the chain has one; on ETC the difficulty and miner; on ETH the fee recipient and
  the slot when the header carries it (§6.1).
- **FR-023** The Block Observatory MUST show the most recent blocks the node has seen, at least 32 where the terminal
  has room, each with its transaction count, gas used against the limit, size and base fee, updated as blocks arrive,
  as a chart and as a table. When blocks arrive faster than it reads them it shows a sample and says so (§6.1).
- **FR-024** The view MUST show the import rate in blocks and gas per second and how long the last import took
  (§6.1).
- **FR-025** A fee panel MUST show the base fee and priority-fee percentiles over the recent blocks on a chain with a
  base fee, and gas prices on one without (§6.1).
- **FR-026** A change of head to a block that is not a descendant of the previous head MUST be recorded as a reorg
  with its depth, both tips and the time; the history since the TUI started is listed; a reorg at or above a
  configurable depth raises an alert (§6.1).
- **FR-027** The view MUST show the fork active at the head and the next one with its distance (spec 027's Chain
  view), and the chain's identity: chain id, network id, family, genesis hash and fork id (§6.1).
- **FR-028** The operator MUST be able to open a block by number or hash, a transaction by hash, its receipt, and an
  account by address (balance, nonce, whether it has code and how much) at the head, and to move from a block to its
  transactions to a receipt to the addresses in it (§6.1).
- **FR-029** A lookup MUST never run on the frame loop or the polling thread. After 2 s it shows that it is working;
  Esc cancels it; a failure says what was not found or not available, in words (§8.4).

**Peers and topology**

- **FR-030** The Peers view keeps spec 027's list, now selectable, with a details screen per peer (client,
  capabilities, direction, age, best block, score and its components, request latency, recent activity). It gains a
  topology panel: [NEEDS CLARIFICATION: D-2 — a peer radar that plots only what the node knows (latency, score,
  direction, activity), or a world map, which needs a bundled offline location database; research §6.6 and §9
  recommend the radar].

**Mempool**

- **FR-031** The Mempool view MUST show the pending count, the blob-transaction count, the pool's capacity and how
  full it is, the oldest transaction's age, and the transactions arriving and leaving per second, with those that left
  split into included in a block and dropped (§6.2).
- **FR-032** The Mempool Radar MUST sort pending transactions into four tiers by their fee against the fee the node
  itself would suggest, show the count in each as a chart and in figures, and show the tier boundaries in gwei
  (§6.2).
- **FR-033** The view MUST show replacements (same sender and nonce), the most active senders, and how many
  transactions were submitted locally (§6.2).
- **FR-034** The node MUST count transactions rejected by reason, evicted, and expired, and transaction announcements
  received, fetched and timed out, and the view MUST show them (§6.2).
- **FR-035** The view MUST say that this client keeps no queue of transactions with nonce gaps, and show no figure
  for it (§6.2).
- **FR-036** Enter on a transaction, or its hash at the prompt, MUST open it whether it is pending or mined (§6.2).
- **FR-037** Watching the pool MUST not burden it: the whole pool is read at most once a second and only while the
  Mempool view is open; other views use a count (§6.2, §8.4; B-196).

**RPC, Engine API, mining**

- **FR-038** The RPC view MUST list, per method called, the calls, the errors, the 50th, 95th and 99th percentile
  latency and the time of the last call, with failed calls timed like successful ones, and totals per transport
  (§6.3).
- **FR-039** It MUST show each transport's state: enabled, listening address and port, namespaces served, whether it
  authenticates, requests refused by the rate limit, and a warning when an admin namespace is reachable from another
  host (§6.3).
- **FR-040** On an ETH node the Engine Synchronization Monitor MUST show the link's state; the last calls of each
  kind with status and duration; the head, safe and finalized blocks and the distances between them; the consensus
  client's name and version when it has reported them; authentication failures with their reasons; payload-building
  activity; and the last 100 calls or more as a log. Its link animation MUST move once per call that arrived (§6.3).
- **FR-041** On an ETC node the same place MUST hold the mining monitor: spec 027's mining panel with the hash rate
  and work requests over time (§6.3).
- **FR-042** What FR-038 to FR-041 add outside `console/` MUST be observation only: it changes no response, status,
  ordering or timing of any RPC or Engine API call. `beacon` reports on the Engine API half and `forge` on the mining
  half before merge (§6).

**Storage, system, health**

- **FR-043** The Storage view MUST show the database's size on disk and its growth; the disk's free and total space; a
  breakdown by store; whether compaction is pending or running, the bytes waiting, and whether writes are stalled or
  slowed; cache use, and hit rates when the node's database statistics are on; the pruning mode and state scheme; and
  the integrity markers and the background audit's progress. Where this client has no such thing (an archive store, a
  snapshot) the view says so (§6.4).
- **FR-044** The System view MUST show CPU use by the process and the machine, memory in and out of the heap,
  garbage-collection pauses (count, share of time, the last), threads, the process's uptime, and network throughput in
  bytes and messages per second, each with a history of at least 10 minutes (§6.5).
- **FR-045** Each subsystem MUST have a health bar whose value is its headroom to its nearest threshold, labelled with
  the check that sets it. A bar's colour comes only from the alert rules, so a bar and the alert row never disagree.
  Enter on a bar lists every check behind it with its value, threshold, state and time in that state (§8.3).
- **FR-046** Every alert threshold MUST be configurable under `fukuii.tui.alerts`, with spec 027's values as defaults;
  an invalid value fails at start with an error naming the key (§7.2).

**Operator actions**

- **FR-047** Every action MUST declare its name, what it changes, its class (Observe, Adjust, Disrupt or Halt), how
  its preview is computed, and how it is undone if it can be (§7.3).
- **FR-048** Confirmation follows the class. Observe: none. Adjust: a dialog showing the value before and after, and
  Enter. Disrupt: the preview, and `y`. Halt: the preview, and typing a word the dialog shows. A dialog opens with
  Cancel in focus, is never animated, and closes on Esc (§7.3).
- **FR-049** `q` MUST open the Halt dialog for stopping the node and MUST NOT stop it. Ctrl-C and signals stop the node
  as they do today (§2 R4, §3 E6).
- **FR-050** A preview MUST be computed from the node's state when the dialog opens and MUST name what will change in
  terms the operator can check afterwards. If what it shows stops being true before the operator confirms, the dialog
  shows the new state and asks again (§7.3).
- **FR-051** Every attempt at an action, whether confirmed, cancelled or failed, MUST be logged at INFO from one
  logger with the action, its parameters and the outcome, and no secret (§8.2).
- **FR-052** An action that exists in the admin interface MUST run the same code as it does there. The TUI MUST need
  no RPC namespace enabled and MUST enable none. No action may add a network-reachable method (§7.5).
- **FR-053** With `fukuii.tui.read-only = true` no action other than Observe can run, and the Ops view says so. The
  default is false (§8.1).
- **FR-054** The first release MUST offer:
  - *Observe*: the effective configuration, searchable, with secrets hidden; the list of settings that can change
    without a restart; a test of each enabled RPC endpoint that reports the answer and the time; the node's listening
    and detected external addresses with the inbound peer count as evidence of reachability; the list of subsystems
    with what each depends on and its health.
  - *Adjust*: a log level, root or by logger, with the current levels shown and a reset to the configured ones; the
    peer limit; trusting and untrusting a peer; alert thresholds for the session, with the configuration lines that
    would make them permanent.
  - *Disrupt*: adding, disconnecting and blocking a peer; starting and stopping mining and setting its coinbase (ETC);
    a database consistency check; a database compaction, whose preview says writers may wait; re-detecting the
    external address, whose preview names the outside services it contacts; writing a support bundle.
  - *Halt*: stopping the node (§7.1, §7.2).
- **FR-055** Node and service lifecycle: [NEEDS CLARIFICATION: D-1 — the TUI runs inside the node, so it can stop the
  node and, with new work, stop and start the HTTP and WebSocket RPC servers and mining, but it cannot start the
  node. Research §9 recommends in-process only; the alternatives are a supervisor-driven restart, or a separate
  attachable TUI, which is its own spec].
- **FR-056** What the brief lists and this release does not offer MUST be named in the Ops view with the reason:
  snapshots (spec 013 has no code), configuration reload from file (no key supports it), and restart of the Engine
  API server, peer discovery or sync (§7.2).
- **FR-057** An action that takes time MUST show progress, MUST NOT be startable a second time while it runs, MUST
  continue while the operator changes view, and MUST report its end. It never runs on the frame loop (§8.4).
- **FR-058** No secret may reach a frame or a file. The configuration view and the support bundle hide the value of
  every key on an explicit list and every key whose name matches a secret pattern; they show paths, never a file's
  contents; they show a URL without its credentials. A support bundle is written under the data directory, readable
  by the owner only, is never sent anywhere, and its preview lists every file in it and says that it holds peer
  addresses and the node id (§7.4).

**Settings and art**

- **FR-059** New settings live under `fukuii.tui` (`graphics`, `read-only`, `alerts.*`, history lengths) with
  environment overrides in the style of spec 027 (`FUKUII_TUI_GRAPHICS`, `FUKUII_TUI_READ_ONLY`). An invalid setting
  fails at start with an error naming the key; an invalid environment value logs a warning and uses the default
  (027 FR-024).
- **FR-060** Art is data, not code: sprite sheets in the fixed palette with a manifest naming each sprite, its frames
  and the state each is for, so that art can be replaced without a code change. A test rejects art outside the
  palette. [NEEDS CLARIFICATION: D-3 — who draws the first sprites: a starter set traced from the supplied images
  during the build (recommended), sheets supplied by the user before the graphics slice, or no characters at all;
  research §5.4 and §9].

### Key Entities

- **Key, KeyDecoder**: a key as the operator means it (Up, Enter, `q`, pasted text) and the function from the
  terminal's bytes to keys.
- **Focus, Selection, Screen stack**: which panel has the keys, which row is selected, and the screens Esc returns
  through.
- **Prompt, Command**: the typed line and what it resolves to: a view, a lookup or an action.
- **GraphicsMode, GraphicRegion, PixelLayer**: pixel, block or text; a rectangle of cells that holds art; what a
  frame asks the terminal to draw beside its lines.
- **Palette, Sprite, SpriteSheet, ArtManifest**: the fixed colours; one character's frames; the file that names them.
- **MascotState**: the node state the mascot shows.
- **HeadObservation, BlockSummary, ReorgRecord**: what the head observer saw, one block's figures, one reorg.
- **PoolObservation, FeeTier**: one reading of the pool with what arrived and left since the last; the four tiers.
- **MethodStats, EngineCall, TransportStatus**: one RPC method's counts and latencies; one Engine API call; one
  listener.
- **StoreStats, SystemSample, History**: one database store's figures; one reading of the machine; a bounded series.
- **Subsystem, HealthCheck, HealthBar**: a part of the node, one threshold check, and the bar made from its checks.
- **Action, RiskClass, Preview, Confirmation, AuditLine**: what can be done, how dangerous it is, what the operator
  is shown, what they must do, and what is logged.
- **Redaction**: the rule that hides a secret.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001 Frames stay exact.** Zero violations of spec 027 FR-001 over every new golden fixture and over at least
  1,000 generated states per run, at widths 20–200 and heights 3–80, in each graphics mode and both glyph sets.
- **SC-002 Reachable by keyboard.** A test walks every view, panel, dialog and action using printable keys, Tab and
  Enter only, at 80×24, and reaches all of them.
- **SC-003 No stray keys.** Over at least 10,000 generated inputs mixing keys, escape sequences, terminal replies and
  truncated sequences, every dispatched key is one that was sent as a key.
- **SC-004 Input stays quick.** A key is dispatched within 250 ms at either frame pace with a lookup and an action in
  flight (asserted), and within 100 ms in the manual gate.
- **SC-005 Nothing is only a picture.** For every golden fixture, every value a graphic region encodes appears in the
  text of the same frame; the text-mode frame of each fixture answers the five-second questions of spec 027 SC-002.
- **SC-006 No image left behind.** After `q` with confirmation, `d`, an interrupt, and a lost terminal, the test
  terminal holds no image and the output ends with the removal of all of them.
- **SC-007 Cheap to run.** After the one-time upload of art, the busiest view at 4 frames a second on a 160×48
  terminal writes under 20 KiB/s, and the TUI's threads use under 3 % of one core, and under 0.5 % when nothing moves.
  A 160×48 frame renders in under 2 ms (median of 100).
- **SC-008 The chain is current.** On a synced node a new block is on screen within 2 s. A fixture reorg of depth 3 is
  recorded with the right depth and tips. On a synced Mordor node a transaction lookup answers within 1 s (manual).
- **SC-009 The pool is not burdened.** With a fixture pool the tier counts, inclusions, drops and replacements match
  the hand-computed ones. The whole pool is read at most once a second, and never while another view is open.
- **SC-010 Latencies are right.** For recorded latencies with known percentiles, the reported 50th, 95th and 99th are
  each within 10 % of the true value.
- **SC-011 Bars and alerts agree.** For every subsystem fixture, a bar shows a warning or an error exactly when the
  alert row does, and its label names a check that the drill-down lists.
- **SC-012 Actions are safe.** Every action has a passing test of its preview, confirmation, cancellation and log
  line. In read-only mode no action beyond Observe runs. No pasted input confirms an action. With a marker planted in
  every secret-bearing key, the marker appears in no frame and no bundle file.
- **SC-013 No consensus change.** Outside `console/`, the diff of all slices touches only the files the plan lists;
  `beacon` and `forge` report no change in consensus behaviour; `testEssential` is at baseline at the end.
- **SC-014 Seen on real terminals.** Each slice's PR carries captures from kitty and Ghostty (pixel), from an 80×24
  and a 160×48 tmux pane (block), and from a `LANG=C NO_COLOR=1` run (text), showing no wrap, no scroll, no stray
  glyph, sharp art, and a clean exit on confirmed `q`, `d` and Ctrl-C.
- **SC-015 Coverage.** Statement coverage stays at or above 70 % (constitution III).

## Assumptions

- JLine stays on 3.30.13 and adds nothing for graphics; the protocol is spoken directly (§3). The JDK is 25.
- The TUI stays opt-in (`--tui`), in process, one per node, on an interactive terminal.
- Operators who want pixels use a terminal that implements the Kitty graphics protocol and reports its cell size;
  everyone else gets block graphics, which is the expected majority because most run under tmux.
- Whoever holds the terminal is the operator. Confirmation guards against mistakes; it is not access control (§8.1).
- Spec 027's manual gate (B-189) runs before this spec's first slice changes the files it covers, or that slice
  rebases onto its fixes.
- B-193 (Engine API status counters) and B-196 (a count-only pool query) land before the panels that read them.
- Art shipped in the repository is Chippr Robotics' own or made for this feature.
- "One heavy JVM at a time" on the build host still holds, so slices are built and gated serially, as in spec 027.

## Related specs

- **027 tui-dashboard**: the base. This spec changes four of its rules (escape-sequence input, `Tab`, the view keys,
  a bare `q`) and says why in research §2. It edits no 027 document.
- **024 chain-registry**: a wall-clock slot would need a beacon genesis time per network there (§6.1).
- **013 state-snapshots**: a snapshot action waits for it.
- **022 snap-heal-convergence**: owns the background audit whose progress the Storage view shows (§6.4).
- **018 storage-engine-consolidation**: owns the state layout the Storage view names.
- **020 Scala 3.9**: new code builds under `-source:3.9` and the warning ratchet.
