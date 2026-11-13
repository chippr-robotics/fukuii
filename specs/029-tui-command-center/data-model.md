# Data Model: TUI Command Center

The types the plan introduces, by the slice that adds them. All are in-memory values in
`com.chipprbots.ethereum.console`; nothing is persisted. Names are the plan's working names. Spec 027's types
(`TuiState`, `NodeStatusSnapshot`, `Sourced`, `Panel`, `Tier`, `TuiGlyphs`, `TuiTheme`, `LogLine`, …) stay as they
are unless a row says otherwise.

## Slice 1: input and navigation

| Type | Fields | Rules |
| --- | --- | --- |
| `Key` (enum) | `Char(c)`, `Up`, `Down`, `Left`, `Right`, `PageUp`, `PageDown`, `Home`, `End`, `Enter`, `Esc`, `Backspace`, `Tab`, `BackTab`, `F(n)`, `Paste(text)` | The only thing dispatch ever sees. A terminal reply or an unknown sequence produces no `Key`. |
| `KeyDecoder` | state: `Ground`, `Escape(since)`, `Csi(buf)`, `Ss3`, `Str(kind, buf)`, `Paste(buf)` | `step(state, input: Byte \| Timeout(elapsed)) → (state, Seq[Key])`. Pure. Bounds: 64 bytes for a CSI, 4 KiB for a string sequence, 64 KiB for a paste; beyond a bound the sequence is dropped and the state is `Ground`. Lone-Esc timeout 50 ms. |
| `Screen` (enum) | `View(view)`, `PeerDetail(id)`, `BlockDetail(ref)`, `TxDetail(hash)`, `AccountDetail(address)`, `HealthDetail(subsystem)`, `Config(filter)`, `Art`, … | What fills the body. |
| `Nav` | `stack: List[Screen]` (head is on screen), `focus: Map[Screen, Int]`, `selection: Map[(Screen, Int), Selection]` | Push on Enter, pop on Esc. A pop restores focus and selection because they are keyed by screen and never cleared on push. Stack depth ≤ 16. |
| `Selection` | `index: Int`, `top: Int` | `top ≤ index < top + rows`; clamped to the list's size at every render. |
| `Overlay` (enum) | `None`, `Help`, `Prompt(PromptState)`, `Filter(text)`, `Dialog(DialogState)` | At most one. While one is open it takes every key but `Ctrl-C`. |
| `PromptState` | `text`, `cursor`, `history: Vector[String]` (≤ 100), `historyAt`, `message: Option[String]` | Completion and parsing are pure functions of `text` and the command table. |
| `Command` (enum) | `Open(screen)`, `Lookup(query)`, `Start(action, args)`, `Display(setting)`, `Unknown(text, nearest)` | `Commands.parse(text): Command`. |
| `Effect` (enum) | `Quit`, `Detach`, `Redraw`, `SetGraphics(mode)`, `StartLookup(query)`, `CancelLookup`, `StartAction(id, args)`, `ReloadArt`, `Audit(line)` | What a key may ask of the world. |
| `Update` | — | `Update(state, key, now): (TuiState, Seq[Effect])`. Pure; the only place keys change state. Replaces `Tui.handleCommand`'s body. |

`TuiState` gains `nav`, `overlay`, `graphics` and `lookup`; its `view` and `helpOpen` become derived from them.

## Slice 2: graphics

| Type | Fields | Rules |
| --- | --- | --- |
| `GraphicsMode` (enum) | `Pixel(cell: CellSize)`, `Block`, `Text` | `Pixel` exists only with a measured cell size. |
| `CellSize` | `width: Int`, `height: Int` (pixels) | Both ≥ 4, from the terminal's answer. |
| `TerminalCaps` | `kittyGraphics: Boolean`, `cell: Option[CellSize]`, `colors: Int`, `multiplexer: Boolean` | Result of the start-up query phase and the environment. `GraphicsMode.choose(setting, caps, noColor)` is pure. |
| `Palette` | 240 entries: xterm index 16–255 → RGB | Fixed. Index 0 of a `PixelBuffer` means transparent. |
| `PixelBuffer` | `width`, `height`, `pixels: Array[Byte]` (palette index, 0 = transparent) | Immutable once built. Equality by content, so a frame's graphics compare cheaply by a cached hash. |
| `Sprite` | `name`, `cols`, `rows`, `lo: SpriteSheet`, `hi: SpriteSheet`, `frames: Vector[Int]` (per pose) | From [contracts/art.md](./contracts/art.md). |
| `SpriteSheet` | `frameWidth`, `frameHeight`, `pixels: PixelBuffer` | Dimensions are exactly frame × grid. |
| `ArtStore` | `sprites: Map[String, Sprite]`, `problems: Seq[String]` | Loaded once at start and on `r` when `art-dir` is set. A sprite that fails a rule is replaced by its placeholder and adds a problem. |
| `Pose` (enum) | `Starting`, `Syncing`, `Watching`, `Warning`, `Error`, `Asleep`, `Celebrating` | `Pose.of(state): Pose` is pure: errors over warnings over stall over celebration over sync over head. |
| `Graphic` (enum) | `SpriteFrame(sprite, pose, frame)`, `Chart(id, buffer: PixelBuffer)` | What a region shows. |
| `GraphicRegion` | `id: String`, `row`, `col`, `cols`, `rows`, `graphic: Graphic` | `id` is stable across frames for the same on-screen slot. Regions of one frame never overlap and never include the alert row. |
| `Frame` (changed) | `lines: Seq[AttributedString]`, `regions: Seq[GraphicRegion]`, `animated: Boolean` | In `Block` and `Text` mode `regions` is empty: block art is already in `lines`. |
| `PixelLayer` | `uploaded: Set[ImageKey]`, `placed: Map[String, Placement]` | The writer's memory of what the terminal holds. `PixelLayer.diff(previous, regions, cell): (bytes, next)` is pure. Cleared on resize, `r`, and mode change. |

## Slice 3: chain

| Type | Fields | Rules |
| --- | --- | --- |
| `BlockSummary` | `number`, `hash`, `parentHash`, `timestamp`, `txCount: Option[Int]`, `gasUsed`, `gasLimit`, `baseFee: Option[BigInt]`, `sizeBytes: Option[Long]`, `miner`, `difficulty`, `slot: Option[Long]`, `seenAt` | `txCount` and `sizeBytes` are `None` when the body is not stored (SNAP). |
| `HeadObserver` | `recent: Vector[BlockSummary]` (ring, `observatory-blocks`), `reorgs: Vector[ReorgRecord]` (≤ 64), `sampled: Boolean` | `observe(head, parentOf, now)`: walks parents from the new head to a block in `recent`, at most 64 steps and 8 body reads a poll. A gap sets `sampled`. |
| `ReorgRecord` | `at`, `depth`, `depthIsLowerBound`, `oldTip`, `newTip`, `commonAncestor: Option[…]` | Recorded when the walk reaches a block in `recent` that is not the previous head. |
| `ChainSample` | `head: Option[BlockSummary]`, `recent`, `reorgs`, `sampled`, `blocksPerSecond`, `gasPerSecond`, `forkId` | In `NodeStatusSnapshot` as `Sourced[ChainSample]`. |
| `FeeSample` | `baseFees: Vector[BigInt]`, `tipPercentiles: Vector[(p10, p50, p90)]`, `suggestedTip`, `suggestedGasPrice` | One entry per recent block. On a chain with no base fee only the gas-price fields are set. |
| `Lookup` | `query`, `startedAt`, `state: Running \| Done(result) \| Failed(reason) \| Cancelled` | One at a time. A result for a cancelled or replaced lookup is discarded by its id. |

## Slice 4: mempool

| Type | Fields | Rules |
| --- | --- | --- |
| `PoolTx` | `hash`, `sender`, `nonce`, `tip`, `feeCap`, `gasPrice`, `isBlob`, `local`, `addedAt` | From `PendingTransaction`. |
| `FeeTier` (enum) | `Low`, `Normal`, `High`, `Urgent` | `tier(tip, suggested)`: below ½×, ½× to 1½×, 1½× to 3×, above 3× of the node's suggested tip (gas price on a chain with no base fee). The boundaries are shown in gwei. |
| `PoolObserver` | `previous: Map[hash, PoolTx]` | `observe(current, includedHashes, now)` → `PoolSample`. |
| `PoolSample` | `size`, `blobs`, `capacity`, `tiers: Map[FeeTier, Int]`, `arrived`, `included`, `dropped`, `replaced` (each since the last sample), `oldest`, `topSenders: Seq[(sender, count)]` (≤ 10), `local`, `txs: Vector[PoolTx]` (≤ 500, by tip) | Full only while the Mempool view is open; otherwise `size` and `blobs` only. |
| `PoolCounters` | `rejectedByReason: Map[String, Long]`, `evicted`, `expired`, `announced`, `fetched`, `fetchTimedOut` | Read from a new `TxPoolMetrics` object. Cumulative since start. |

## Slice 5: RPC, Engine API

| Type | Fields | Rules |
| --- | --- | --- |
| `LatencyHistogram` | 40 fixed log-spaced buckets, 100 µs to 60 s; `count`, `sum`, `max` | In `metrics/`. Lock-free adds. Percentile error ≤ the bucket ratio (about 1.4×, reported at the bucket's geometric mean, inside SC-010's 10 % for the fixtures). |
| `MethodStats` | `method`, `calls`, `errors`, `p50`, `p95`, `p99`, `lastAt` | At most 256 methods; further names are counted under `other`. |
| `TransportStatus` | `kind: Http \| Ws \| Ipc \| Engine`, `enabled`, `running`, `address`, `port`, `apis`, `authenticates`, `exposed: Boolean`, `rateLimited: Long` | `exposed` is `RpcExposureCheck`'s rule. |
| `EngineCall` | `at`, `method`, `status: Option[String]`, `duration`, `blockNumber: Option[Long]` | A ring of 256 on `EngineApiMetrics`. |
| `ClIdentity` | `name`, `version`, `commit`, `capabilities: Int` | From the consensus client's own `engine_getClientVersionV1` argument and `exchangeCapabilities` list. Sanitised before display. |
| `ClSample` (extended) | adds `identity`, `recent: Vector[EngineCall]`, `authFailureReasons: Map[String, Long]`, `randomSecret: Boolean`, `headNumber`, `safeNumber`, `finalizedNumber` | |

## Slice 6: storage, system, health

| Type | Fields | Rules |
| --- | --- | --- |
| `StoreStats` | `namespace`, `label`, `keys`, `sstBytes`, `liveBytes` | One per column family. |
| `StorageSample` | `dbBytes`, `liveBytes`, `diskFree`, `diskTotal`, `stores`, `compactionPending`, `compactionsRunning`, `pendingCompactionBytes`, `writeStopped`, `delayedWriteRate`, `cache: Option[…]`, `statisticsOn`, `pruningMode`, `scheme`, `markers`, `audit: Option[AuditStatus]` | Polled every 5 s. Reads that find the database write-locked return the last sample as `Stale`. |
| `SystemSample` | `processCpu`, `systemCpu`, `loadAverage`, `heapUsed`, `heapMax`, `nonHeap`, `gcCount`, `gcShare`, `lastGcPause`, `threads`, `uptime`, `bytesIn`, `bytesOut`, `messagesIn`, `messagesOut` | A CPU figure the JVM cannot give is `None`, not 0. |
| `History` | `capacity`, a ring of `Double` | One per meter; `history-seconds` long at the poll interval. Memory: 20 meters × 3600 × 8 bytes at most. |
| `Subsystem` (enum) | `P2p`, `Chain`, `ClLink` / `Mining`, `TxPool`, `Rpc`, `Database`, `Disk`, `Memory`, `Cpu` | `dependsOn: Set[Subsystem]`, fixed. |
| `HealthCheck` | `subsystem`, `name`, `value`, `warnAt`, `errorAt`, `direction`, `state: Ok \| Warn \| Error \| Unknown`, `since` | The alert rules of `Alerts.scala`, restated as data. `Alerts.row` is computed from the same checks. |
| `HealthBar` | `subsystem`, `headroom: Double` (0 to 1), `limiting: HealthCheck` | `headroom` is the smallest, over the subsystem's checks, of the distance from value to `warnAt` over the distance from the check's healthy end to `warnAt`. |
| `AlertThresholds` | one field per key of `fukuii.tui.alerts` | From configuration; the session copy is what `threshold` edits. |

## Slice 7: actions

| Type | Fields | Rules |
| --- | --- | --- |
| `RiskClass` (enum) | `Observe`, `Adjust`, `Disrupt`, `Halt` | Ordered. Read-only mode allows `Observe` only. |
| `Action` | `id`, `title`, `risk`, `families: Set[Family]`, `preview: Args => IO[Preview]`, `run: Args => IO[Outcome]`, `longRunning: Boolean` | The registry is a fixed `Seq[Action]`; a test asserts every row of [contracts/actions.md](./contracts/actions.md) is in it and nothing else. |
| `Preview` | `lines: Seq[String]`, `facts: Seq[(String, String)]` | `facts` are what the dialog re-checks before it runs the action. |
| `DialogState` | `action`, `args`, `preview: Loading \| Ready(preview) \| Failed(reason)`, `focus: Cancel \| Confirm`, `typed: String` | `confirmable` is false while loading, when the preview does not fit the terminal, and in read-only mode above `Observe`. |
| `Outcome` | `Done(message)`, `Refused(reason)`, `Failed(reason)` | Shown as a one-line toast in the alert row's right side for 5 s, and logged. |
| `Running` | `action`, `startedAt`, `progress: Option[String]` | At most one long-running action at a time per action id. |
| `Redaction` | `keys: Set[String]`, `pattern: Regex` | `redact(config): Seq[(key, shownValue, origin)]`. |
