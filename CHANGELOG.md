# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- **On proof-of-work chains (ETC, Mordor) the SNAP header download validates every header against its parent, and SNAP
  finalisation takes the pivot from that validated chain.**
  - **Header download.** Each header must pass the block-import header rules against its parent (extra data including
    the DAO rule, timestamp, difficulty, gas used and gas limit, number). The Ethash seal is checked on a sample, as
    go-ethereum and core-geth do: one header at a random position in every 100 of a response, the last header of every
    response, and every header in the 100 blocks up to the pivot. A response with a header that fails stores nothing;
    the peer that served it is blacklisted and the range is asked of another peer. The download continues from the
    hash of the last header it stored (new `AppStateStorage` key `BackfillBestHeaderHash`), and stores nothing above
    its target, whose hash it records (new key `BackfillTargetHash`). Headers a datadir stored before this release,
    during a SNAP sync still in progress, are downloaded again from genesis once, with the body and receipt cursors.
  - **Pivot.** The pivot's header hash is persisted with its number and state root (new key `SnapSyncPivotHash`).
    Finalisation waits for the header download to reach the pivot, then requires the pivot to be the downloaded chain's
    header at that number, with the persisted state root. A pivot that is not is replaced by the downloaded chain's
    header, and SNAP restarts and heals toward its state root. A pivot persisted without a hash (an older datadir) is
    checked by its state root.
  - **Pivot bootstrap.** The pivot header's header-only rules, Ethash seal included, are checked when it arrives; a
    header that fails is not stored, its peer is blacklisted and another peer is asked. When no eligible peer is
    left after such a header, the bootstrap stops, and the pivot is chosen again from the peers that remain.
  - **Blacklisting.** A peer that served a header failing these rules is blacklisted for 15 minutes (or the
    configured `sync.blacklist-duration`, if longer), in the node's shared sync blacklist as well as the download's
    own, and a static or trusted peer is not exempt. Other header-response errors keep the configured duration.
  - **Observability.** New counter `chain.header.rejected` (tags `reason`, `phase`); these logs carry the tag
    `[HEADER-CHECK]`. The header download warns, at most once a minute, when every connected peer is blacklisted.
  - **Config.** On proof-of-work chains the header hold is always on: `sync.snap-sync.chain-download-enabled` and
    `defer-chain-backfill-until-state-complete` are forced to `true`, and a config that sets either to `false` gets a
    warning at SNAP start. As a result, the body and receipt backfill after SNAP now runs on these chains even where
    the config turned `chain-download-enabled` off.
  - ETH and Sepolia: see the next entry.
- **On chains with a terminal total difficulty (ETH, Sepolia, and the chains that were proof of stake from genesis) the
  SNAP pivot is always the consensus layer's head or one of its ancestors, found by parent hash, and the SNAP header
  download is checked against the post-merge rules and anchored to that pivot.**
  - **Pivot refresh.** The new pivot is the header a pivot offset below the CL head, found by walking back from the
    head's hash: through the CL's blocks stored by hash when they are all there, else with one reverse
    `GetBlockHeaders` request of 192 headers from the head's hash. The run must start at the head and each header must
    be its predecessor's parent down to the pivot; the linked part, the pivot's ancestors in it included, is stored
    with the pivot (by hash only, apart from the pivot). A run that does not start at the head, or is longer than asked, is stored nowhere, its peer is blacklisted
    (as for a header that fails validation) and another peer is asked; a run that is short, or leaves the head's
    ancestry above the pivot (Besu answers all but the first header by its canonical number, so after a reorg an honest
    Besu peer serves one), is skipped without blame. A request is given `sync.peer-response-timeout` plus 5 s, so a
    peer that times out is not asked again. A failed fetch is retried from the newest CL head, not backtracked by block
    number, after 5 s doubling to 60 s while fetches keep failing. The pivot is at most 191 blocks below the CL head (a
    deeper `pivot-block-offset` is clamped, with a warning). The fallback for a pivot no peer serves stays at or below
    the CL head (the peers' best block may lead it by the serve-window margin only). The heal-at-a-newer-pivot recovery
    takes its pivot the same way.
  - **Pivot selection.** The CL head's stored header must have its fork's field layout and pass the post-merge
    header-only rules before it is committed. A pivot bootstrap target by block number persisted by an earlier run is
    not resumed. A pivot from the CL is not compared with the peers' advertised heights.
  - **`sync.engine-api-required = false` is ignored on these chains**: SNAP keeps waiting for the CL's
    `engine_forkchoiceUpdated` and never takes the peers' best block as its pivot; a warning is logged once
    `cl-wait-timeout` has passed.
  - **Header download.** A header with difficulty 0 must have a parent at or above the terminal total difficulty, its
    fork's field layout, and pass the import validator's post-merge rules against its parent; a header with difficulty
    above 0 must have a parent below the terminal total difficulty and not after a difficulty-0 parent, and must follow
    its parent's number. A response with a header that fails stores nothing and its peer is blacklisted, as on
    proof-of-work chains.
  - **Finalisation.** It waits for the header download to reach the pivot (the header hold is forced on, as on
    proof-of-work chains) and requires the downloaded header there to be the pinned pivot. When it is not, the pivot is
    kept: the downloaded headers above the highest header the two chains share are discarded and downloaded again
    (from genesis when none is within the pinned pivot's stored ancestry), and that header's number-index entry is put
    back in the same write; if the same pivot is missed again after such a rewind was applied, SNAP restarts and
    chooses a pivot from the CL. A pivot with no pinned hash, or whose pinned header is not stored, also restarts SNAP.
    The pinned hash of a pivot persisted after healing is the one the best-block record names, never the number index.
  - Where the hold comes before the Path-scheme publish, the publish now still runs when the hold ends.
- **Ops templates publish JSON-RPC on localhost only, keep the Engine API on the internal Docker network, and enable
  only the eth, web3, net and fukuii namespaces.** In every `ops/` compose stack (barad-dur ETC, Sepolia, ETH mainnet,
  multi-network, cirith-ungol, gorgoroth) the JSON-RPC HTTP and WebSocket ports, and the Kong Admin API, are published
  as `127.0.0.1:<port>`. The Sepolia and mainnet stacks no longer publish the Engine API (8561/8562); Lighthouse reaches
  it by service name on the compose network. The fukuii configs drop `personal`, `debug` and `qa` from
  `network.rpc.apis`; gorgoroth keeps `admin` for its static-peering workflow, and its `apis` setting, which sat under
  `server-address` and had no effect, now applies. Inside the containers the RPC and Engine API still bind `0.0.0.0`
  so Docker can forward to them. To reach RPC from another host, use an SSH tunnel or an authenticating TLS proxy.
  The `fukuii-cl-setup` and `fukuii-engine-api-setup` skills now say how to run a CL on another host.
- **The JSON-RPC WebSocket server accepts browser connections only from allowed origins (`ws.allowed-origins`, default
  localhost); clients that send no Origin header are unaffected.**
  The upgrade checks the `Origin` header against `fukuii.network.rpc.ws.allowed-origins` and answers 403, before the
  connection is accepted, when it is not listed. A client that sends no `Origin` header (a CLI, a library, a consensus
  client) is not a browser and is upgraded as before.
  - **Default.** The key is optional and is not in `network.conf`. When it is absent or empty, pages served from this
    machine are accepted: `localhost`, `127.0.0.1` and `[::1]`, on any port, over http or https. A browser dapp served
    from another origin needs that origin listed, for example `allowed-origins = ["https://app.example"]` (a list, or
    one comma-separated string); `allowed-origins = "*"` accepts every origin. A non-empty list replaces the default,
    so list `http://localhost` as well to keep it.
  - **Matching** follows go-ethereum's `--ws.origins`. An entry is `scheme://host[:port]` or `host[:port]`; a part it
    leaves out matches anything, so `https://app.example` accepts any port. Case is ignored and the port is compared as
    written. An entry that is not a valid origin, or has no host, is ignored with a warning.
- **JSON-RPC namespaces are chosen per transport, and HTTP and WebSocket serve only `eth`, `net`, `web3` and `fukuii` by
  default, as go-ethereum does (`network.rpc.http.apis`, `ws.apis`, `ipc.apis`). Breaking for operators who relied on the
  old default.**
  One list, `network.rpc.apis`, used to apply to every transport and defaulted to
  `eth,web3,net,personal,fukuii,debug,qa,admin`. Each transport now has its own key, and the shipped defaults are
  narrower on the two that face the network.
  - **Keys.** `fukuii.network.rpc.http.apis`, `fukuii.network.rpc.ws.apis` and `fukuii.network.rpc.ipc.apis`. Each falls
    back to `fukuii.network.rpc.apis`, which stays the shared key (it also sets the namespaces of the Engine API's
    `eth_`, `net_` and `web3_` pass-through). A value is a comma-separated string or a list; an unknown name stops the
    node at startup and names its key. None of the four keys is in `network.conf` any more, so a key an operator sets
    always wins over a default, and an `rpc.apis` that is set applies to all three transports as before.
  - **Defaults.** HTTP and WebSocket: `eth,net,web3,fukuii`. IPC: `eth,net,web3,fukuii,personal,debug,admin,miner,txpool,trace`,
    every production namespace, because file permissions protect the socket. `qa` (a dev-network tool), `test`,
    `testing` and `mcp` are not in the IPC default either. The `rpc` namespace (`rpc_modules`) is always on and now
    lists what the transport you call serves.
  - **Opting back in.** A node that needs `personal`, `debug`, `admin`, `miner`, `qa`, `txpool`, `trace` or `mcp` over
    HTTP or WebSocket must list it, for example `fukuii.network.rpc.http.apis = "eth,net,web3,fukuii,admin"` (or
    `ws.apis`). To keep the old behaviour on every transport, set
    `fukuii.network.rpc.apis = "eth,web3,net,personal,fukuii,debug,qa,admin,miner"` (or pass
    `-Dfukuii.network.rpc.apis=eth,web3,net,personal,fukuii,debug,qa,admin,miner`); `admin` and `miner` now carry what
    `net` and `eth` used to (below). A method outside its transport's list is answered with `-32601`, before any
    service is called. The GraphQL endpoint (`/graphql` on the HTTP port) is not governed by these keys;
    `fukuii.network.rpc.graphql.enabled` controls it.
  - **`net` keeps three methods.** The non-standard peer-management methods that carry a `net_` name
    (`net_nodeInfo`, `net_listPeers`, `net_connectToPeer`, `net_disconnectPeer`, `net_listBlacklistedPeers`,
    `net_addToBlacklist`, `net_removeFromBlacklist`) are served with the `admin` namespace, under the same names;
    `net` serves only `net_version`, `net_listening` and `net_peerCount`, as in go-ethereum. A caller that used one of
    them over HTTP or WebSocket needs `admin` in that transport's list.
  - **WebSocket subscriptions need `eth`.** `eth_subscribe` and `eth_unsubscribe` are answered with `-32601` on a
    WebSocket endpoint whose list has no `eth`; before, they were answered whatever the list said.
  - **The IPC socket.** The node sets the socket file to owner read/write only (0600) when it binds it, as go-ethereum
    does, and the node stops with an error if that fails on a POSIX file system. IPC serves more namespaces than HTTP and
    WebSocket by default, so the file permissions are what protect it.
  - **The `miner` namespace.** `miner_start`, `miner_stop`, `miner_getStatus` and `miner_setEtherbase` are in a new
    `miner` namespace, opt-in on HTTP and WebSocket (and in the IPC default), as in go-ethereum; they were in `eth`.
    `eth_setEtherbase` stays as a deprecated alias of `miner_setEtherbase`, served only where `miner` is enabled, and
    logs a deprecation warning once per node. `eth_mining`, `eth_hashrate`, `eth_coinbase`, `eth_getWork`, `eth_submitWork` and
    `eth_submitHashrate` stay in `eth`, so external miners and stratum proxies are not affected. To control mining over
    HTTP, list it: `fukuii.network.rpc.http.apis = "eth,net,web3,fukuii,miner"`. When `test` and `miner` are both
    enabled, `test` answers `miner_setEtherbase` (the retesteth harness uses it) whatever the order of the list; before,
    the namespace listed last won.
  - **Startup warning.** Enabling `personal`, `debug`, `admin`, `qa`, `miner`, `test` or `testing` on an HTTP or
    WebSocket endpoint that is not bound to a loopback address logs a warning that names the endpoint and the
    namespaces.
  - **Also fixed.** An `apis` value written as a list (`["eth","web3"]`) or as numbered keys
    (`-Dfukuii.network.rpc.apis.0=eth`), as `docs/MCP.md` showed, was not accepted; both are read now. The configurator
    wrote its `apis` and `websocket` settings where the node does not read them and never enabled WebSocket or IPC; it
    now writes `http`, `ws` and `ipc` blocks, each with `enabled` and its own `apis`, and marks the opt-in namespaces.
- **The `--tui` dashboard draws the worm-to-brain bars, in emoji or ASCII, at any size from 80×24 (spec 027 slice 1).**
  The progress bars are now the bars the logs already draw (`[====🪱....]🧠 52%`). Regular sync shows one Blocks worm;
  SNAP sync shows five worm rows (Overall, Accounts, Storage, Bytecode, Healing), each with its count and rate once, plus
  the contract count, the chain download and the time in the current phase. The account count and rate were drawn twice.
  - **Glyph sets.** `emoji`, or `ascii` (`~o` worm, `@@` brain) with the same column widths. The new
    `fukuii.tui.glyphs` (`conf/base/tui.conf`, default `auto`) draws ASCII when the locale is not UTF-8 or is unset
    (Windows hosts included); set `FUKUII_TUI_GLYPHS=emoji` to override (`emoji|ascii|auto`). An invalid `fukuii.tui.glyphs` stops the node with a named error;
    an invalid `FUKUII_TUI_GLYPHS` logs a warning and is ignored.
  - **Colour.** `NO_COLOR`, set to a non-empty value (no-color.org), draws without colour (bold and reverse video
    only), as does a terminal reporting fewer than 8 colours. A 256-colour terminal gets the 256-colour palette.
  - **Sizes.** Below 100 columns or 32 rows, and from 100×32, the dashboard has no logo; from 140×45 the logo sits in
    a column on the right. Before, it appeared above the body wherever it fit.
  - **Smaller fixes.** The startup wordmark spells FUKUII (the old one spelled FOOO). The footer reads
    `r Redraw  d Detach  q Quit`; the keys are unchanged. The idle SNAP phase no longer carries U+FE0F, which terminals
    drew wider than the TUI counted. A row cut at the right edge through a two-column glyph is padded back to the
    terminal's width instead of ending one column short.
- **The `--tui` dashboard moves, never waits on the node, and opens on a splash (spec 027 slice 2).**
  - **Motion, from a frame counter only.** A worm that is moving ripples behind itself, an `[ACTIVE]` row pulses, and a
    spinner turns beside "Starting node" and "Searching for peers". While anything moves the dashboard draws 4 frames a
    second, otherwise one. A frame equal to the last one is not written again, so an idle node at the head writes at
    most one frame a second.
  - **Stalls.** A Blocks worm whose block has not moved for 2 minutes stops rippling, shows `💤` (ASCII `z`) and turns
    yellow.
  - **Catching up.** The first time the node reaches the head, the worm crawls into the brain over 3 s and the header
    flashes; every later catch-up shows one quiet "✓ back at head" frame.
  - **Polling off the frame loop.** The peer manager and sync controller are asked together, on their own thread, with
    one 500 ms wait. A slow actor shows as `Unresponsive` on its own line while the rest keeps updating, and keys are
    handled when they arrive, at either pace.
  - **The splash is a view.** It shows the FUKUII logo whole, cropped to the creature, as the 12-line logo or as the
    wordmark, by terminal size, while the node starts, and any key skips it. It replaces the banner that held the
    terminal for 1 s with a sleep.
  - **Keys and settings.** `p` pauses and resumes the motion. New in `fukuii.tui`: `animation-interval-ms` (250),
    `status-poll-interval-ms` (1000), `reduced-motion` (false) and `splash-ms` (3000). `FUKUII_TUI_MOTION=reduced`
    (or `full`) and `FUKUII_TUI_NO_SPLASH` override them; under reduced motion only the worm moves, with progress.
- **The `--tui` dashboard has views, a layout that fits every size, and an alert row (spec 027 slice 3).**
  - **Views.** `1` Overview, `2` Sync, `3` Peers, `4` Chain; `Tab` cycles them; `?` or `h` opens Help over the view
    (the keys and the legend of the glyph set on screen), and any key but `q`, `r` and `d` closes it. `g` switches
    between the emoji and ASCII glyph sets without moving a column. Arrow, Home, Insert, Delete and function keys
    are ignored (their escape sequences used to be read as `d`, `h` and view keys). The header names the network, chain id, PoW or
    PoS, the uptime and the view; from 100×32 also the head and the peers, from 140×45 the version. The footer lists
    the keys.
  - **Alert row.** The row under the header, on every view, reads `✓ no alerts` or lists up to three conditions,
    errors first, with a count: no peer for more than 30 s, the peer manager or sync controller not answering (error),
    a stalled sync (2 minutes without a new block while blocks remain), a SNAP progress sample older than 90 s while
    SNAP runs, disk below 20G free on the datadir (below 5G an error), and fewer peers than a quarter of the maximum.
    It never moves. The peer count's own colour still turns yellow below half the maximum.
  - **Overview.** Peers and blocks (while SNAP runs, the pivot is the target), the endpoints, disk free, the JVM heap (and at 140×45 the GC share and the
    discovery table), the SNAP worm rows or the Blocks worm with its rate and ETA, the forks (the last active and the
    next, with its distance), and at 140×45 the longest-connected peers beside the logo.
  - **Sync view.** The worm rows on a 20-, 40- or 60-cell track by size; for SNAP, from 100×32, the recent and overall
    rates, the request, failure and timeout counters, the snap-capable peers and the phase timeline; for a regular
    sync, the session's imported blocks and the lag.
  - **Peers view.** Each handshaked peer's id, direction, address and how long it has been connected. Client, best
    block and score come with slice 4.
  - **Chain view.** Chain id, PoW or PoS and the fork timeline: on ETC by block number, from the fork names the
    startup log prints, with Olympia "not scheduled"; on ETH by timestamp, in activation order with the BPO forks and
    Amsterdam, against the head block's timestamp, each with its UTC time. Read for display only: no fork
    configuration changes.
  - **New readings, off the frame loop.** The status probe also reads disk free on the datadir, the JVM heap and GC
    time, the discovery counts, the best header's timestamp and SNAP's request counters (new read-only
    `SNAPSyncMetrics` accessors), each failure leaving only its own field empty.
- **The `--tui` dashboard has a log tail, the peers' clients and scores, the txpool and the import time (spec 027
  slice 4).**
  - **Logs view.** `5` or `l` opens the newest log lines, INFO and above, with their time, level and text; `-` goes a
    page back to older lines and `+` a page forward (they do nothing on the other views). A view scrolled back keeps
    its lines on screen as new ones arrive. The lines come from an appender of the TUI's own on the root logger,
    attached once the console appenders are suppressed and detached before they are restored, so the console and the
    log file see what they saw before. New in `fukuii.tui`: `log-ring-size` (1000, at most 10,000) and `log-level`
    (`INFO`; a logger's own level in `logback.xml` applies first). An invalid value stops the node with an error naming
    the key.
  - **Recent strips.** The Overview ends with the newest log lines (6 at 80×24, 10 from 100×32, the rest of the
    screen from 140×45); the Sync view with "Recent (sync)", the lines of the loggers under `blockchain.sync`.
  - **Peers view.** Each peer's client (name and version from its Hello) and best block, from the network peer
    manager, beside its direction and age; from 100×32 its address and score, from 140×45 the score's trend (`↑`, `↓`,
    `→` against the previous published scores, at the column's three decimals). Highest score first; a peer the
    scorer has no score for yet shows `—`, never 0. When the network peer manager does not answer, the title says
    so and the client and best block read `?`. The Overview's peers table at 140×45 lists the top 8 by score.
  - **Text from peers and logs is cleaned.** A client id and a log line are drawn without escape sequences, control
    characters, zero-width and direction-override characters, as one line of at most 512 characters, and in the
    ASCII glyph set as ASCII only, in the same columns. A peer can no longer write to the operator's terminal through
    its client id. A failure's reason on the alert row goes through the same cleaning.
  - **Txpool.** The pending transactions, and how many carry a blob (`1,204 (3 blob)`), on the Overview at every
    size and on the vitals row from 100×32. At 140×45 the GC share moves to the second row, beside the discovery
    table, so every cell fits down to 140 columns.
  - **Import time.** New gauges `regularsync.import.last.duration.seconds.gauge` and
    `regularsync.import.last.blocks.gauge`: how long the last block import's consensus call took (one block or a
    batch), and how many blocks it put on the chain, timed in regular sync's importer. The Sync view shows them for a
    regular sync (`Import   last 3 blocks in 61ms`).
  - **Motion.** A frame counts as moving when either of the next two ticks draws it differently: a worm near the start
    of its track drew two ticks alike, and the dashboard dropped to one frame a second for a beat.
- **The `--tui` dashboard has a consensus-layer panel on ETH and a mining panel on ETC, each with its alerts (spec 027
  slice 5).** Both only report: no Engine API response, status or timing, and no mining or DAG behaviour, changes.
  - **Consensus layer (ETH, Engine API on).** The Overview shows whether the consensus layer is calling (`waiting`,
    `connected`, `DISCONNECTED`), the age and status of its last `engine_forkchoiceUpdated` and `engine_newPayload`, the
    head, safe and finalized blocks (the safe and finalized numbers are looked up once and remembered), the JWT
    authentication failures when there are any, and the payload counts. The counts are labelled by what is counted:
    `app_engine_newpayload_invalid` has always counted an ACCEPTED payload with the INVALID ones, and keeps doing so, so
    the panel reads a new count of the ACCEPTED ones and shows VALID, ACCEPTED and INVALID apart (SYNCING only when
    something counts it; no caller does).
  - **Mining (ETC).** The Overview shows `Mining  off`, `Mining  off (internal) · external: 48.2 MH/s · getWork 1,204
    (20/min)` when external miners are asking the node for work (`mining-enabled` gates the internal CPU miner only), or
    mining on with the coinbase, hashrate, blocks mined and
    when the last was, stale shares, the `eth_getWork` count and its rate over the last minute, and the DAG with its
    ECIP-1099 epoch, its phase (preparing, loading from file, generating), its percent and a spinner. The hashrate is the
    last one submitted, so it does not fall when a miner goes quiet. Nothing of Olympia, a treasury or a base fee is on
    the panel.
  - **Alerts.** Error: no `engine_forkchoiceUpdated` for more than 60 s after one was heard. Warning: none yet 60 s
    after the node started, with the count of failed JWT checks when there are any (a secret that does not match
    leaves the consensus layer waiting for ever, and the panel says so on its first row); no `engine_newPayload` for more
    than 5 minutes while a peer, or the sync's target, is ahead of the node's head (a payload the node already held is
    answered without a record, so a node at its head is quiet, and one never recorded counts from when the consensus
    layer was first seen, so a late attach is not measured against the node's uptime);
    ETC mining on with a reported hashrate of 0 for more than 10 minutes, counted from when the DAG is loaded.
  - **New metrics.** `app_engine_newpayload_accepted`, `app_engine_auth_failures` (JWT checks answered 401, one number,
    no tag), `app_pow_dag_epoch` (`-1` before the first build), `app_pow_dag_phase`, `app_pow_dag_progress` and
    `app_pow_dag_loaded`. The 11 `app_engine_*` and 6 `app_pow_*` series that were there are unchanged. The mining
    metrics object is now initialised before the miner starts, when mining is enabled. With `--tui` on, an ETC node
    now exposes the `app_pow_*` series even with mining off, as the panel reads them on every poll.
- **SNAP sync on ETC and Mordor now ends with the exact pivot total difficulty (#1654).**
  `fukuii.sync.snap-sync.defer-chain-backfill-until-state-complete` is now `true` for every chain; before, only the
  Ethereum, Sepolia, Platåberget and Ephemery configs set it. With it off, ETC and Mordor finished SNAP before the
  header download reached the pivot, so the pivot's total difficulty was a peer interpolation (the block number with
  eth/69-only peers), and every block imported above it inherited the error. When the backfill later reached the
  pivot it wrote the exact value there, leaving a jump in the stored weights. Fork choice compares stored weights, so
  a fork branching at or below the pivot was weighed across that jump.
  - **What changes on ETC and Mordor.** SNAP finalisation waits until the header download reaches the pivot. Headers
    are stored from genesis with their parent's weight plus their difficulty, so the pivot's weight is exact. Bodies
    and receipts are fetched after the state is finalised instead of alongside it, as on the ETH chains, so they no
    longer compete with state writes for disk IO. Nothing changes on the ETH chains; their configs already set the key.
    A node whose SNAP config cannot be loaded now falls back to the same value.
  - **Cost in sync time.** This adds no download work: the headers were already fetched alongside state sync. Only
    the order changes, and regular sync now starts once both the state and the headers are in. Headers are fetched one
    1,024-header range at a time. At 0.5 to 1 s per range, ETC mainnet's ~25.7M headers (about 25,100 ranges) take
    roughly 3.5 to 7 h from the start of SNAP. Regular sync starts later only by the part of that still left when
    state sync finishes. This estimate is not measured yet; the fresh Mordor sync gate will measure it.
  - **Not covered.** Setting the key to `false`, or `chain-download-enabled = false`, still finishes with an estimate.
    The change applies from the next SNAP run, and it does not repair jumps that earlier syncs already wrote; that
    repair is planned for 0.12.0.
- **Scala 3.9.0 LTS at `-source:3.9` (spec 020, #1717). Breaking for projects that consume the published modules.** The
  build moves from Scala 3.3.8 (sbt 1.13.0 already shipped in 0.10.0). Node behaviour is unchanged. It was checked by importing
  the same fixed Mordor and Ephemery block ranges with the 0.10.0 build and with this one (every state and receipt root
  equal, import time inside the budget set for the move), and by the unit and ethereum/tests suites.
  - **Rebuild downstream projects.** `bytes`, `crypto`, `rlp`, `scalanet` and `scalanet-discovery` are compiled by
    Scala 3.9 and carry its TASTy; a project still on Scala 3.3 cannot consume them.
  - **Public `implicit class` wrappers in those modules are now `extension` methods with the same method names (#1714).** Call
    syntax (`bs.toHex`, `codec.xmap(f, g)`, `a + b`) and wildcard imports (`import X.*`) are unchanged. Code that imports
    or names a removed wrapper class itself (`import ByteStringUtils.ByteStringOps`, `ByteStringOps(bs)`) must import the
    extension method instead (`import ByteStringUtils.toHex`) or use the wildcard import. The removed classes are
    `ByteStringUtils.Padding`, `ByteStringUtils.ByteStringOps`, `RLPCodec.Ops`, `FiniteField.Ops.FiniteFieldOps`, and
    scalanet's `peergroup.implicits.{NextOps, PeerGroupOps, ChannelOps}` and `PeerGroup.ServerEvent.ServerOps`. The
    conversion wrappers (`ByteStringUtils.ByteString{Self,Array,Byte}Element`) are unchanged.
  - **for-comprehensions are elaborated differently from `-source:3.8` (SIP-62 "better fors").** A trailing
    `map(identity)` is dropped and value definitions are no longer tuple-wrapped. Comparing the 3.7 and 3.9 builds' bytecode,
    the only change is `map` removals on `Either`, `Option`, `Try`, `EitherT`, `IO`, `Resource` and scodec `Attempt`, where
    `fa.map(identity)` is `fa`; none on `List`, `Seq`, `Vector`, `Iterator`, `Array`, `Map`, `Future`, Pekko `Source` or
    fs2. Downstream code compiled at `-source:3.8` or later gets the same elaboration; it matters only for a type whose
    `map` has observable side effects.
  - **For contributors:** six more compiler warnings are build errors (`E028`, an implicit argument without `using`,
    alphanumeric infix, `_` as a type wildcard, `with` as a type operator, a type ascription after a pattern; `E205` is
    an error too, but cannot fire at `-source:3.9`), and `sbt formatCheck` (scalafix `--check` on `bytes`, `crypto`,
    `rlp` and the root, scalafmt on every module) now runs in CI's required "Test and Build" job (#1721). Scala 2
    wildcard imports are rejected everywhere; `return` is rejected in the four scalafixed modules (`scalanet` is not
    scalafix-checked). Run `sbt formatAll` before pushing: `scalafmtAll` alone covers the root project only.
- **RPC: `eth_call` now returns an error for a message whose frame fails, as go-ethereum does, on every chain.** A
  failure other than a revert (out of gas, an invalid opcode, a stack error, ...) used to come back as a successful
  `"0x"`. It is now JSON-RPC error -32000 with geth's message (`out of gas`, `invalid opcode: INVALID`). A revert is
  unchanged: error 3 with its data. Callers that read `"0x"` as success were told such a message would succeed. On
  Ephemery (Amsterdam), a 335 ETH beacon deposit "succeeded" from 47,880 gas, the calldata floor; on chain it needs
  260,654. The invalid-opcode message now names the opcode as geth does (`invalid opcode: PUSH0`, or `opcode 0xc not
  defined` for a byte with no name), in `eth_estimateGas` and the `eth_createAccessList` `error` field too. GraphQL `call`
  now reports a failed frame with `status` 0, the frame's output as `data` (so a revert reason reaches the client) and
  the gas it used. Before, an out-of-gas frame had `status` 1, and a revert had empty `data` and `gasUsed` 0.
- **SNAP healing completes on a live chain without walking the whole state trie (spec 022, #1671). Both chains.**
  Healing used to prove the state complete by walking every stored trie node: about 17 h and 787M nodes on Sepolia, and
  16-20 h on ETC mainnet. The Sepolia pivot moved about every 12 minutes, so the walk's result was thrown away and
  healing fell back to slow on-demand repair. A healed node now reaches disk only after its whole subtree is present,
  so a node on disk means its subtree is there, and healing is done when nothing is pending, held or excluded and the
  current root is present.
  - **What changed in the download and in healing.** Heal requests are keyed by trie position (Path) or by hash with
    parent edges (Hash), so a node needed at two positions is no longer lost, and two accounts with the same storage
    root both get their storage trie healed (#1682). A healed node waits in memory until its subtree is present (128 MiB
    by default, `fukuii.sync.snap-sync.healing.held-memory-limit`), then it and the ancestors it completes are written
    in one batch (#1684). Every give-up (an account skipped, abandoned at a storage force-complete, a root mismatch,
    unservable bytecode) is recorded durably, so an incomplete account never looks complete (#1685). Gaps at the
    account-to-storage edge are closed (#1690, #1691): an account with storage but no code now gets its storage
    fetched, and a storage reply with no slots no longer marks an account done when nothing was written; the account is
    recorded as excluded. The filter that de-duplicates code hashes was sized for 3M and, by calculation from Sepolia's
    logs, never queued about 17% of that chain's code hashes; it now holds 40M (about 206 MiB) and warns if a sync
    reaches that load.
  - **When the walk still runs.** A marker records that everything on disk was written under this rule, or covered by a
    clean walk (#1688); where it stands, healing completes with no walk and logs `[HEAL-COMPLETE] ... no full-trie gate
    walk` (#1695). The walk stays for a datadir an earlier jar wrote (a SNAP sync resumed on it pays one walk, once), a
    run that lost track, a fresh Path account phase over existing nodes, `pruning.mode = inmemory` and deferred
    merkleization.
  - **Measured.** Mordor (Hash), fresh datadir, public peers: hand-off 239 s after start with the root equal to the
    pivot's `stateRoot`, then 24 h following the head with no stuck sync and no ERROR lines (T062). Sepolia (Path),
    fresh datadir, public peers, NVMe host: storage done to hand-off 1 h 24 min 46 s, no gate walk, and the root
    matched block 12072545's `stateRoot` (T063, #1718). The 24 h head-follow was not run on Sepolia: the copy described
    under Known issues was still running when T063 was stopped.
  - **Upgrade.** The persisted healing frontier is retired: its column family is cleared once at the first start, and
    the disk it held is returned only when RocksDB compacts it (the size is not measured) (#1688). The `sync.conf` keys
    `healing-frontier-persistence`, `scoped-heal-verification`, `scoped-heal-max-paths` and `pruned-heal-verification`
    are gone, and a config that still sets them is ignored (#1700, #1701).
- **SNAP trie-node and bytecode replies are matched to their request by id, and a peer that does not echo the id now
  times out (#1693). Every SNAP sync.** `TrieNodes` and `ByteCodes` were taken from a peer whichever request they
  answered, so two requesters on one peer could cross. snap/1 requires the echo and fukuii's own server does it.
  geth-family, Besu and Nethermind are expected to comply; this was checked from the spec and from memory, not from
  their code, and Erigon and Reth are not verified.
- **RPC: a request's `type` field is ignored and the transaction type is inferred from the fields, as go-ethereum
  does (#1660, #1661). Every RPC path, both chains.** It covers `eth_call`, `eth_estimateGas`, `eth_createAccessList`,
  the `debug` and `trace` call methods, `eth_simulateV1`, `eth_sendTransaction`, `eth_signTransaction`,
  `eth_fillTransaction` and `personal_*`. This reverses the one deviation from geth that 0.10.0 documented (#1633):
  `gasPrice` with an access list and no `type` was rejected, and now builds a type 0x1 transaction. On ETC and Mordor,
  `"type":"0x2"` with no 1559 fields before Olympia now builds a legacy transaction instead of returning -32602. A
  type that is not active is still never signed or sent, because the activation check works from the fields present.
- **`eth_simulateV1` follows go-ethereum where a 159-case comparison with Erigon on Ephemery found differences (#1660,
  #1661).** Each item was settled against go-ethereum master's source. With `traceTransfers`, a contract creation's
  transfer log records the created address, not zero. `blockOverrides.blobBaseFee` reaches `BLOBBASEFEE`, in child
  frames too. `state` and `stateDiff` on one account, and moving a precompile onto an address already overridden in the
  same set, return -32000; malformed block and state override values return -32602 instead of being dropped silently,
  and so does an empty `blockStateCalls`. Per-call gas defaults to the block's remaining gas; asking for more returns
  -38015 `block gas limit reached`, then gas is clamped to the 50M budget. The default coinbase is the previous
  header's, not zero, and `blockOverrides.withdrawals` is applied from Shanghai on (credited before the request system
  calls, committed in the withdrawals root, returned in the body). Results change wherever the default coinbase, gas
  clamping or withdrawals matter. A second comparison, against geth v1.17.9, found more differences; they are listed
  under Known issues (#1727).
- **The sample TLS keystore is no longer shipped; generate a per-deployment server certificate with
  `tls/gen-cert.sh` (now a proper server certificate with SANs).** `tls/fukuiiCA.p12` and `tls/password` are gone.
  The script writes `tls/fukuii-rpc.p12`, a random `tls/fukuii-rpc.password` (both mode 600) and the PEM certificate
  `tls/fukuii-rpc.crt` for clients to trust; `--san` lists the names and addresses clients use (default
  `DNS:localhost,IP:127.0.0.1`), and the certificate is `CA:FALSE` with `serverAuth`. It needs `openssl`, no longer
  `pwgen` or `keytool`. HTTPS stays off by default (`certificate = null`); a config that points at the old files must
  point at the generated ones. The Gate Integrity check (C15) now rejects committed keystores, private keys and
  `.env` files.

### Added
- **Hoodi is a built-in network (spec 028 slice 1).** `fukuii hoodi` starts the Hoodi testnet (chain id 560048)
  from the shipped `conf/hoodi.conf` and `conf/base/chains/hoodi-chain.conf`, with no setup. The genesis hash fukuii
  computes (`0xbbe312868b376a3001692a646dd2d7d1e4406380dfd86b98aa8a34d1557c971b`, state root `0xda87d7f5...7576`) and the
  five fork ids (`0xbef71d30` through `0x23aa1351`) are pinned by tests against go-ethereum and the public Hoodi RPCs.
  Peers come from four static `:30303` enodes and the Ethereum Foundation's DNS tree `all.hoodi.ethdisco.net`
  (added to the shipped trust anchors); the five EF NodeOps enodes that Hoodi lists with TCP port 0 are not listed,
  because fukuii would dial port 0. Hoodi has no Amsterdam fork scheduled. See `docs/runbooks/hoodi.md`.

- **Every peer the node deals with has a reliability score, exported to the metrics registry (spec 027 slice 4a).**
  `PeerScoringManager` (written long ago, never constructed) is now built once per node and fed from the peer events:
  a successful handshake (`PeerManagerActor`), a failed handshake and a protocol violation (`PeerActor`), a response
  with its round-trip time and a timeout (`PeersClient` for eth requests, `SNAPRequestTracker` for SNAP requests), and a
  blacklisting of a peer id. Nothing decides on a score: no dial, peer selection, blacklist or disconnect reads it; the
  scores are for the operator and the TUI's Peers view.
  - **Metrics.** Gauges `network_peers_scored_gauge`, `network_peers_score_average_gauge` and
    `network_peers_score_{high,medium,low}_gauge` (high 0.7 and above, medium 0.4 up to 0.7, low below 0.4). They count
    every peer the scorer tracks, connected or not, crawlers that failed a handshake included, so they are not the
    number of connected peers (compare `network_peers_incoming_handshaked_gauge` and
    `network_peers_outgoing_handshaked_gauge`). The per-peer scores are not metric series; they are read through
    `NetworkMetrics.peerScores`.
  - **Scoring changes that wiring needed.** A score is a function of the history and an injected clock (it read the wall
    clock). Events for one peer are applied atomically (the get-then-put lost updates under load). The score map is
    bounded at 4096 peers; entries never seen alive (a crawler's failed handshake) are evicted first, then the oldest
    `lastSeen`. A failed handshake, a timeout, a protocol violation and a blacklisting never raise a score: only a
    successful handshake and a response refresh `lastSeen`, a blacklisting counts against the compliance term, and
    timeouts no longer dilute the violation rate. The compliance term counts blacklistings as they are added: one
    request timeout is scored as a timeout and again when the owner blacklists the peer for it, a refresh of a standing
    blacklisting counts again, and the count never decays.
- **Background state audit after SNAP or checkpoint hand-off (spec 022, #1693). Both chains.** After hand-off a
  throttled walk (25% duty cycle) from the current head checks every node's hash, every account's code and every
  storage trie, fetches what is missing and logs each hole with its position. It does not block import, and it replaces
  the blocking recovery scan at start: a start with a recovery flag down goes straight to regular sync. A node that
  fails its hash check is deleted only after 3 failed passes at least 1 h apart; a node that passes it is never
  deleted. A node that never ran SNAP owes nothing; every other node, an upgraded one included, owes one full pass. On
  Mordor a clean pass over 5.54M nodes took 20 to 34 minutes; the length at Sepolia and mainnet sizes is not measured
  (T063 stopped before the audit ran). It is on by default, is switched off with
  `fukuii.sync.snap-sync.background-audit.enabled = false`, and never runs under `pruning.mode = inmemory`. Holes met on
  demand are counted by `state_ondemand_fetch_total` and `engine_state_hole_total{kind}` (#1695).
- **GraphQL `Block` gains `slotNumber` and `blockAccessListHash` (#1629, #1668).** They carry geth's names, types and
  nullability (`Long` and `Bytes32`, both nullable) and are null before Amsterdam, so always null on ETC. No other field
  changes, so a client that does not select them sees the same output.

### Fixed
- **SNAP progress no longer goes backwards in the log, in the log's worm block or on the `--tui` Overall worm
  (B-044).** The accounts percentage fell and climbed because the account-count estimate left out a range whose
  response was still being inserted (it counted the rest of that range as done); the storage percentage swung because it
  divided the slot count, which rises with every response, by an estimate pushed only when a contract completes (51%
  against 1% of contracts done, then back down); and the Overall worm dropped to its stage floor when a percentage
  passed 100. The storage percentage is now the share of contracts done, and the percentage is held at its highest
  value within a phase, never past 100. A restart still starts from zero, and now logs what it dropped.
  - **Fixed here:** the log line, the Accounts and Storage rows of the log's worm block, and the `--tui` Overall worm.
  - **Not fixed here:** the `--tui` dashboard's own Accounts and Storage rows (`SnapRows`) still divide the raw count by
    the estimate, so its Storage row can still run ahead of the contracts done between estimate pushes. They follow the
    held percentage in spec 027 slice 3.
  - **Still can fall,** because their total is a running count of work found so far: the Bytecode row, and the Storage
    row while the account phase is still finding contracts.
- **A PoW block mined by fukuii is built on the state block import computes for it (B-167).** fukuii's PoW miner
  (internal, or `eth_getWork` / `eth_submitWork`) now runs import's steps before the first transaction
  (`BlockExecution.applyBlockPreamble`, moved there unchanged: the DAO fork block's drain, EIP-4788, EIP-2935), and pays
  the rewards on the header as it is sealed, with the gas the block used. On ETH configs it gives a block after London
  EIP-1559's base fee, with the gate and the function import's header validation uses (`BaseFeeCalculator`); before,
  such a block failed import's header rules. ETC and Mordor blocks are unchanged: the steps do nothing there, and the
  headers carry no base fee. ETH's real blocks come from the Engine API, which this does not touch.
- **A block whose parent this node lacks no longer gets the peer that sent it blacklisted (B-007, #1683).** Regular sync
  read such a block as a bad block, and blacklisted the peer for 60 s. The gap is the node's, not the peer's. T062 (Mordor,
  24 h) blacklisted 33 peers for `Block import error: UNKNOWN_PARENT: parent header not found` in a run with only five
  reorgs, and the peer count drifted from 13 to about 7. #1683 saw it right after SNAP hand-off (`Could not get weight for
  parent`), where the only peer was blacklisted and sync waited for it to expire. No block is imported that was not
  before: a block is still not imported until its parent is here.
  - **Why.** A block a peer announces (`NewBlock`) can hang from a block this node does not have: a competing tip on a
    reorging chain, or a fetcher whose head is ahead of the chain's after an announce the importer was too busy to take.
    Block import used to answer that `UnknownParent`, which the importer takes as no one's fault; ETCM-1058 (consensus
    replacing block import) turned it into a failed import, and the failed import blacklists the announcing peer. Right
    after hand-off the pivot floor made the same mistake for a fetched batch: it imported a batch without checking that
    it continues the chain, and failed on its first block.
  - **Now.** The block is an orphan (`UnknownParent` again), the fetcher goes back to its parent and fetches the branch,
    and no one is blamed. core-geth does the same: `eth/fetcher/block_fetcher.go` (`importBlocks`) logs `Unknown parent
    of propagated block` at debug and drops the block, and drops a peer only for a header that fails verification. At the
    pivot floor, a batch that does not continue the chain makes the fetcher fetch the blocks between the best block and
    the batch, instead of importing it and walking back one block at a time.
  - **Protection kept, bounded.** A peer that announces 5 blocks in a row whose parent never arrives, with none of its
    blocks imported or queued in between, is blacklisted for the usual period, that peer itself and not whoever the fetcher
    last recorded for the block's number. A block it announces that this node already has does not reset the count. A
    block that fails to import 3 times in a row still evicts its peer, and a block that is invalid is blacklisted at
    once, as before.
  - **To watch.** `regularsync.orphan.blocks.total{source,outcome}` counts them (`announce` or `batch`; `branch-fetch` or
    `blacklisted`). An orphan announce logs at INFO (`Orphan announce: ...`), and an orphan batch at WARN, no longer
    ERROR. If the pivot floor sends the fetcher back to the same block 3 times in a row, the fetched blocks above it do
    not continue from our best block, which is what a pivot that is not on the network's chain looks like: a WARN
    (`Pivot floor: ...`, again every 3 more) and `regularsync.pivot.floor.gap.repeats.total`. A re-sync is then needed.
- **A plain HTTP request to the JSON-RPC WebSocket port no longer registers a connection.** The server set up a
  connection with the subscription manager before it knew the request was a WebSocket upgrade, and for a request that
  was not one, nothing ever closed it. The connection is now set up only once the upgrade is accepted, and a request
  that is not an upgrade is answered with 400 as before.
- **The Engine API answers 401 to a malformed JWT instead of a server error, and accepts only HS256.**
  A token whose signature was not valid base64url made the authenticator throw, so the request got HTTP 500 and a
  stack trace in the log. Every malformed token now gets 401 with a short reason: wrong number of segments, a header,
  payload or signature that is not base64url, JSON that does not parse, an `iat` that is missing or out of range. A
  header whose `alg` is not `HS256` is rejected too, as go-ethereum does. A valid token is accepted as before.
- **A missing BLS12-381 or KZG native library no longer forks the node off the network (B-135).** The BLS12-381
  precompiles (0x0b-0x11) and the KZG point-evaluation precompile (0x0a) run on native libraries. When one was missing,
  the precompile counted it as an ordinary failure: the call consumed all its gas and returned nothing, where every
  other client returned the result. The node then computed a different state root from the network and logged nothing.
  Besu ships no Windows build of the BLS12-381 library, and the KZG trusted setup was loaded only when a Cancun
  timestamp was configured, so never for ETC Olympia.
  - **At startup** the node loads and probes each native library the configured forks need: BLS12-381 from ETH Prague,
    KZG from ETH Cancun, and both from ETC Olympia once it is scheduled. If one is missing, the node logs which library
    and exits with status 1. ETC and Mordor need neither while Olympia is unscheduled.
  - **At call time** a missing library halts the node with status 1, naming the library in the log, instead of
    failing the call. The halt happens where the library is found missing: a fatal error thrown inside Cats Effect, on
    the block import and RPC paths, left the JVM running with the import stuck. A thread that dies of any other
    `VirtualMachineError` is now also logged at ERROR. An input the library rejects still fails the call with the same
    gas as before, and with the libraries present every result is unchanged.
  - **Breaking on Windows for ETH mainnet and Sepolia:** these nodes now refuse to start there, because the BLS12-381
    library has no Windows build. Before, they ran and diverged on any block that calls a BLS12-381 precompile.
- **Stored total difficulty is repaired once, in the background, and kept exact (#1654). ETC/Mordor; ETH too.**
  Fork choice compares stored chain weights. Earlier releases left jumps in them: a SNAP pivot finished with a peer
  estimate, whose exact value the header backfill later wrote under every block that had inherited the estimate, and
  the restart calibration of 0.7.0 to 0.10.x, which rewrote the head. A fork branching below a jump was weighed
  across it. A new datadir marker, `chain.weight.invariant`, says every stored canonical weight is the genesis weight
  plus the difficulties up to its block, the value import writes.
  - **The one-time pass.** On a datadir without the marker (every datadir an older release made), regular sync starts
    a background pass with no lock held. It scans forward from genesis (heights, headers and weights, in batches of at
    most 256 that do not fill the block cache, paced at a 50% duty cycle), saving its place every 10,000 blocks so a
    restart resumes. It waits wherever the header backfill has not stored a header yet, and a reorg below its place
    sends it back to the highest place still canonical. Every weight that differs is then rewritten bottom-up in
    chunks of 10,000, paced like the scan and still with no lock (a block's exact weight depends only on its
    ancestry); a run that breaks under it (the chain moved) goes back to the scan. Blocks imported meanwhile, which
    inherit the not-yet-repaired head (tens a second during a consensus client's catch-up), are rewritten the same way
    while 1,000 or more remain. Only the last step takes the import lock and then the chain insert lock exclusively:
    it checks the blocks imported since, and every block mapped above the best block (an Engine API payload not yet
    promoted), then writes those and the marker in one batch. That hold covers fewer than 1,000 blocks, so Engine API
    `newPayload` calls are not held past a consensus client's timeout. Only one pass runs at a time.
    Logs are tagged `[CW-RECONCILE]`, with a progress line every 1,000,000 blocks and a summary. Gauges:
    `chainweight.reconcile.block.gauge`, `.differing.blocks.gauge`, `.repaired.blocks.gauge` and `.state.gauge`. More
    than 10,000 distinct differences stops the pass with an ERROR and leaves the marker off; nothing is lost.
  - **Operator note: duration.** ETC mainnet has about 25.7M blocks, read as roughly 77M batched point reads at a 50%
    duty cycle. That is about 0.5 to 3 h on an SSD and more on a spinning disk; this estimate is not yet measured.
    The pass runs once per datadir, and import goes on throughout.
  - **The marker.** It is set by the write that follows a new datadir's genesis and by the pass. A SNAP hand-off sets
    it only if the marker stood when that SNAP began (or the datadir held nothing above genesis) and the pivot's weight
    is exact (`REAL_DB_TD` or `DERIVED_FROM_PARENT`). A re-SNAP over a datadir an older release wrote leaves it to the
    pass. It is taken away by a SNAP start, a checkpoint import, an accepted seed and any calibration write. While it
    stands, the timed TD calibration writes nothing, and a peer's higher TD no longer gets it disconnected. Before, a
    chain that was PoS from genesis (Ephemery, Hoodi), whose TD never clears the plausibility floor, got a peer
    estimate written over its head at every restart, and evicted eth/68 peers on every reconnect.
  - **The peer manager no longer stops on a chain whose TD is 0.** On a chain that was PoS from genesis with a terminal
    TD of 0 (Platåberget), an eth/68 peer with a higher TD made the peer-info actor divide by our TD of 0, and it
    stopped for good. Our TD is 0 there after the repair too.
  - **`-Dfukuii.seed-chain-weights` seeds a base, not a head.** A seed is accepted only for a canonical block whose
    parent has no plausible weight, such as a SNAP pivot whose headers below are not in yet. Only that block's weight
    is written at start-up; the pass then re-derives every block above it in the background, each its parent's weight
    plus its own difficulty, without setting the marker. A seed over a parent with a plausible weight is refused
    with a WARN: it would leave a jump, and the calibration reverted it 30 s later anyway. On a chain that was PoS
    from genesis every seed is refused, because the pass derives the weights. A seed is trusted, not proven, so the
    pass checks it from genesis once the headers are in. To get the value, ask a client that still reports total
    difficulty (core-geth; go-ethereum dropped it in 1.14.11); after the merge every Sepolia block's TD is
    17000018015853232. The recipe is in `ChainWeightSeed`.
  - **Known gap.** A side payload stored only by hash while the pass repairs, and later made canonical by a
    forkchoiceUpdated reorg, keeps the weight its parent had then. On a PoS chain this affects only the TD it
    advertises.
- **`--tui` after Ctrl-C, and without `--tui` (B-045).** After Ctrl-C, JLine's shutdown hook closed the terminal and
  the TUI logged `Error rendering TUI: Terminal has been closed` at ERROR on every frame until the node stopped it. Now
  the first frame or key read that finds the terminal closed restores the screen through the process output, logs one
  WARN, and ends the frame loop. A key read that fails for any other reason is logged at ERROR, with the terminal and
  console logging given back, where before it was swallowed on every frame. A node started without `--tui` no longer
  creates a TUI instance at shutdown.
- **`--tui` no longer says "no target yet" during SNAP sync (B-044).** While SNAP runs the sync controller reports no
  block target, and the dashboard read that as "Syncing (no target yet)" for the whole run; with a SNAP sample in hand
  it now reads "Syncing (SNAP)".
- **Quitting `--tui` restores the terminal.** After `Q`, the shell was left in the alternate screen with the cursor
  hidden, and the shutdown logs were drawn over it. The restore was left to a JVM shutdown hook, and shutdown hooks run
  concurrently, in no order: JLine's own hook closed the terminal first (it restores the terminal mode but not the
  screen), so the reset could not be written (`Error shutting down TUI (reset screen): Terminal has been closed`). `Q`
  now restores the terminal before it exits. On Ctrl-C or SIGTERM, JLine's hook can still close the terminal before any
  restore step or between two of them. From that step on nothing else goes through the terminal, and a screen not yet
  reset gets the reset sequence (leave the alternate screen, show the cursor, reset attributes) straight on the process
  output. The race is logged once at WARN; any other failure is still an ERROR.
- **EIP-1459 DNS trees are verified, as the spec requires.** The node used whatever TXT records a tree's name returned:
  it did not check the root's signature, that an entry matched the hash it was fetched under, or an ENR's own signature.
  It now checks what go-ethereum's `p2p/dnsdisc` checks (`tree.go`, `client.go`):
  - **Root.** `sig` in `enrtree-root:v1 e=… l=… seq=… sig=…` is a 65-byte secp256k1 signature over keccak256 of the record
    without it, and must verify against the key in the tree's `enrtree://<key>@<domain>` URL. A root that does not parse
    or verify rejects the whole tree: one WARN, no nodes from it, and nothing under it is fetched.
  - **Entries.** A branch, ENR or link entry must hash to the label it was fetched under (keccak256, first 16 bytes,
    base32). One that does not is dropped with its subtree and counted in the walk's WARN as failed verification.
  - **ENRs.** Each ENR's own EIP-778 `v4` signature is checked. An ENR with a bad signature, another identity scheme or
    none is dropped. An ENR in the `l=` subtree or a link in the `e=` subtree is dropped too.
  - **Links are followed.** Link entries in a tree's `l=` subtree lead to further trees, each verified against the key in
    its link. The walk goes at most 3 links from the tree asked for and covers at most 16 trees, each once, under the same
    deadline, concurrency and 500-record cap. The shipped trees link to nothing today.
  - **Keys.** An entry of `dns-discovery-domains` is an `enrtree://<key>@<domain>` URL, or one of the five shipped
    domains, whose keys are in code as go-ethereum's are (`params/bootnodes.go:72`; core-geth `params/bootnodes_classic.go:28`):
    the Ethereum Classic key for the `all.classic.*` and `all.mordor.*` trees, the Ethereum Foundation's for
    `all.sepolia.ethdisco.net`. **A bare domain with no key is no longer walked** (one WARN naming it and the form to use).
    Custom chain configs that list one must switch to the URL form.
  - **Log.** A clean walk logs `verified` with the root's `seq`; a WARN carries the count that failed verification.
  - **Measured** against the live trees, with no verification failures: `all.classic.etcdisco.net` 350 enodes (382 queries),
    `all.sepolia.ethdisco.net` 301 (330), `all.mordor.etcdisco.net` and `all.mordor.blockd.info` 14 each (18).
    The tests include a snapshot of the real Mordor tree: its root signature, its 17 entry hashes and its 14 ENR signatures.
- **EIP-1459 DNS discovery dropped one entry from every long record, and each drop cost 15 s.** JNDI returns a TXT
  record that is stored as several strings (EIP-1459 splits any entry over 255 bytes; a 13-child branch, the shape on
  ETC and Mordor, is 365) with the strings joined by a space, and the space fell inside a child hash. The walk asked for a
  name that does not exist, the resolver never answered, and the query waited out its 15 s timeout (5 s, then 10 s)
  before the child was dropped. Branch, ENR and link entries have no whitespace in their grammar, so it is now removed;
  the root, which has spaces between its fields, is left alone. go-ethereum's resolver concatenates the strings with
  nothing between them.
  - **Measured** against the live trees, repairing the space: ETC mainnet has 378 queries and 29 records carrying the
    space (7.7%), Sepolia 335 and 38, Mordor 16 and 1, and no repaired query took over a second. Those are the "about 8%
    of queries lost, 15 s each" of the 5 min 34 s serial walk seen on one runner (29 x 15 s is 7 min 15 s on today's ETC
    tree); the resolver was not lossy. `all.mordor.etcdisco.net` went from 13 enodes in 15.4 s with 1 of 17 queries lost
    to 14 enodes in 0.6 s with none lost; `all.mordor.blockd.info` from 15.5 s to 0.7 s.
- **EIP-1459 DNS discovery no longer holds up start-up (H9).** The DNS trees were walked on `main` while the config
  loaded, one query at a time, and the Engine API, JSON-RPC, metrics and the TUI did not start until the walk returned.
  On WSL2 `fukuii mordor --tui` drew its first frame at 2 s and then sat frozen (no keys, no refresh) for 32 s.
  Config load now keeps only the domain names. `PeerDiscoveryManager` walks the trees after `Start`, off `main` and off
  the actor, on the discovery runtime's blocking pool, with sibling branches queried in parallel, and adds each peer to
  discovery as its ENR arrives. The static and bootstrap nodes are used from the first request.
  - **Measured**, `fukuii mordor --tui`, one WSL2 host, throwaway datadir, seconds after the first log line:
    "Metrics started" 33.4 to 2.1, "Starting TUI updater" 34.6 to 2.9. The two Mordor trees now walk in parallel in the
    background, 15.5 s each.
  - **Bounded.** Each tree has a deadline (`network.discovery.dns-tree-deadline`, default 120 s) and at most
    `dns-concurrency` (default 8) queries in flight. A tree that fails, loses queries or times out logs one WARN with the
    resolved, lost and query counts and the elapsed time. It never delays start-up and never throws; the peers found
    before the deadline are kept. Both are optional keys under `network.discovery`; they are not listed in the shipped
    `network.conf`, so the defaults apply unless an operator sets them.
  - **Same code path on every chain**, ETC, Mordor and Sepolia. Nothing about which records are accepted changes: root,
    branch and ENR parsing and the fork-ID filter are as before. The filter now runs after genesis is loaded rather than
    at config load. With `use-bootstrap-nodes=false` (enterprise mode) no DNS is walked, as before. The walk runs once
    per `Start`; nothing re-walks a tree, as before.
- **Pruning no longer deletes a trie node it cannot prove dead (#1692). Operator note for ETC and Mordor.** A node's
  reference count is the number of positions that emitted it. SNAP healing and resume, the checkpoint importer and an old
  fast sync skip a node that is already there, so a subtree two contracts share was counted once. The first block to
  rewrite a slot of one of them sent the nodes on its path to zero, and pruning deleted them 64 blocks later while the
  other contract still read them (`MissingNodeException`; import fetched the node back). Block import counts every
  position, so only datadirs those writers filled were exposed.
  - **Basic pruning now deletes no trie node unless the datadir's counts are proven exact, on every chain.** One stamp
    proves it. This version writes it with a datadir's genesis, when the genesis counts every position (at most one
    account with storage, as the shipped genesis file is), and nothing writes it again. It is taken away for good, before
    the first write that could count a node short: any Hash-scheme SNAP start (ETC, Mordor), even one that hands off
    without writing; a checkpoint import, on any scheme; and the publish of a Path-scheme hand-off (ETH, Sepolia) that is
    not clean, that is a lazy hand-off with a hole in the path store. **A datadir synced by SNAP or a checkpoint keeps
    every trie node until spec 018's resync, and so does one created by an older version, ETH and Sepolia included (a
    Path datadir that SNAPped before 0.10.16 counted a shared storage trie once), and one that handed off lazily.** A
    fresh datadir that hands off cleanly on the Path scheme, or is synced from genesis by block import, prunes as
    before. A kept datadir still prunes its death rows, snapshot rows, reference-count rows and undo records, so only
    the dead state of each block stays. The size of it is `db.pruning.retained.nodes` and `db.pruning.retained.bytes`
    (counters of nodes and their RLP bytes; each stored entry adds its 32-byte key and a few bytes of wrapper).
  - **`pruning.mode = "inmemory"` is rejected at start**, with a message naming `basic` and `archive`, and so is any
    unknown mode. No shipped configuration sets it.
  - **One background audit pass per datadir** runs on the first start of this version (an operator with the audit
    enabled sees `[HEAL-AUDIT] One more full audit pass is owed`). It looks for nodes already deleted and fetches them
    back; it runs off the critical path, at a 25% duty cycle, and takes roughly one to three days on ETC mainnet.
  - Import's on-demand fetch of a missing state node no longer sets the node's count back to one.
- **SNAP finalisation labels the pivot total difficulty by what it is, and uses the boosted request budget while it
  waits for the headers (#1654). Both chains.**
  - **Label and value.** `SNAP finalize pivot TD ... source=REAL_DB_TD` now means the stored weight is the
    pivot's parent's weight plus the pivot's difficulty, with a plausible parent. Before, any stored weight above
    1,000 times the genesis TD was kept and labelled `REAL_DB_TD`, including the peer estimate written when the pivot
    was chosen. A pivot whose parent's weight is exact but whose own is not now gets the exact value
    (`DERIVED_FROM_PARENT`). An estimate over a parent with no weight is kept as before, now labelled
    `PIVOT_SELECTION_ESTIMATE`.
  - **Request budget.** While finalisation holds for the header download, every peer is free. The downloader now gets
    `chain-download-boosted-concurrent-requests` (16), which nothing read before, and a 200 ms dispatch interval instead
    of 2 s; a downloader the hold's stall watchdog restarts gets it too. Header ranges still go one at a time, so the
    extra slots only re-send a slow range to another peer sooner. At hand-off the budget drops to
    `chain-backfill-concurrent-requests`.
- **`eth_simulateV1` no longer builds blocks without limit (#1727). Both chains.** A block-number override far past the
  base block was narrowed to 32 bits before the 256-block check, so a gap of 2^32 + 1 counted as one block and the node
  then built 2^32 of them: the RPC thread never returned and block import stalled until a restart. The gap is now
  compared as a big integer, as go-ethereum does, and a request that reaches more than 256 blocks past the base
  returns -38026 `too many blocks` before any block is built. The call limits are go-ethereum's too: -38026 for more
  than 5,000 calls in a block (`too many calls in block: 5001 > 5000`) or 10,000 in a request. Quantities in
  `blockOverrides` and `stateOverrides` are bounded like go-ethereum's `hexutil` types and return -32602 beyond that:
  256 bits for `number`, `difficulty`, `baseFeePerGas`, `blobBaseFee`, `balance` and a storage key or value, 64 bits for
  `time`, `gasLimit`, `nonce` and a withdrawal's `index`, `validatorIndex` and `amount`. An `Error(string)` revert
  whose length word is wider than 32 bits no longer reads as a shorter string.
- **`eth_simulateV1` reports a failed call and a legacy transaction as go-ethereum does (#1727). Both chains.** A
  legacy transaction in a block returned with `returnFullTransactions` no longer carries `yParity`; only the typed
  transactions do. A call that fails with a VM error other than a revert returns `returnData` `0x`: a creation that
  returned code and then failed its deposit (a first byte of `0xef`, for one) used to return that code. The error text
  is go-ethereum's where fukuii printed a placeholder: an invalid opcode is named (`invalid opcode: INVALID`, not
  `invalid opcode: 0xfffffffe`), a jump to a non-`JUMPDEST` says `invalid jump destination`, a write in a static call
  `write protection`, and so on. Stack-underflow counts and precompile failure reasons
  followed (B-133, below).
- **VM and precompile errors read as go-ethereum v1.17.9's (#1727, B-133). Both chains.** `eth_call`, `eth_estimateGas`,
  `eth_createAccessList` and `eth_simulateV1` print go-ethereum's texts, on ETC too (where core-geth words a few BN254
  errors differently). Gas, state roots and receipts do not change. Tracers print the error's name, so two names
  change there: a failed code deposit reads `MaxCodeSizeExceeded` or `CodeStoreOutOfGas` (it read `OutOfGas`), and a
  call that cannot pay for a precompile input that always fails reads `OutOfGas` (it read `PreCompiledContractFail`).
  - **Stack:** `stack underflow (1 <=> 2)` and `stack limit reached 1024 (1023)` carry go-ethereum's counts.
  - **Contract creation:** `invalid code: must not begin with 0xef`, `max code size exceeded` and `contract creation
    code storage out of gas`, checked in go-ethereum's order (from Amsterdam the size before the deposit). On ETC
    before Homestead, where a creation that cannot pay its code deposit succeeds without code, `eth_call`,
    `eth_createAccessList` and the estimator's ceiling report -32000 `contract creation code storage out of gas`, as
    go-ethereum does; GraphQL reports the call as failed.
  - **Precompiles:** a failure states its reason (`invalid input length`, `invalid fp.Element encoding`, `point is not
    on curve`, `g1 point is not on correct subgroup`, `invalid final flag`, `mismatched versioned hash`, and so on),
    and `MODEXP` above the EIP-7823 limit (from Osaka; on ETC from Olympia) says `one or more of base/exponent/modulus
    length exceeded 1024 bytes`.
    Inputs that always fail are priced as go-ethereum prices them, so the error is the one it reports: `MODEXP` above
    the limit and `BLAKE2F` with a bad final flag are `out of gas` when the call cannot pay their price, and an empty
    BLS12-381 MSM is `invalid input length`. Such a call consumes all its gas either way.
  - **`eth_call`:** an error raised before execution reads `err: <reason> (supplied gas N)`.
- **`eth_simulateV1` reads `blockOverrides` and `stateOverrides` as go-ethereum v1.17.9 does (#1727, B-128). Both
  chains.** It now decodes them with the decoder `eth_call` and `eth_estimateGas` already used for their overrides, so
  the three RPCs share one set of rules, and every decode error is -32602.
  - **Malformed values are refused.** A quantity needs `0x` and has no leading zero (`""` is zero, as `hexutil` reads
    it). `prevRandao`, `beaconRoot` and every `state` / `stateDiff` slot key and value are exactly 32 bytes; a
    `feeRecipient`, a `movePrecompileToAddress`, a withdrawal's `address` and a state override's key are exactly 20.
    `"prevRandao":"0x00"`, a one-byte storage slot and a quantity without `0x` were accepted before; a request that sent
    such values must now send go-ethereum's. A `blockOverrides`, `stateOverrides` or account override that is not an
    object is refused (`json: cannot unmarshal number into Go value of type override.BlockOverrides`); before, the
    simulation ignored a `blockOverrides` or `stateOverrides` of another type and ran without it.
  - **Keys match case-insensitively**, as go-ethereum's `checkOverrideKeys` matches them: `Number`, `GASLIMIT`,
    `StateDiff`, and a block state call's `BlockOverrides`, `stateOverrides` and `Calls`. Two keys that name one field
    are -32602 (`keys "Number" and "number" in block override name the same field`), as an unknown key still is.
    `eth_call` and `eth_estimateGas` match the same way, and still refuse an unknown key, which go-ethereum ignores.
  - **`beaconRoot` is accepted.** From Cancun on it is the simulated block's `parentBeaconBlockRoot`: the response
    carries it, the EIP-4788 system call stores it and the block hash commits to it. It was refused as an unknown key. In
    `eth_call` and `eth_estimateGas` it, and `withdrawals`, stay -32000 `not supported for this RPC method`, now only
    once they have decoded; a malformed one is -32602.
  - **Withdrawals** decode as go-ethereum's `Withdrawal` does: no field is required (one left out is zero), field names
    match case-insensitively, other keys are ignored and the quantities are `hexutil.Uint64`. A `null` entry is still
    refused. A `null` account override is an override that changes nothing, as in go-ethereum; `eth_call` refused it.
- **State overrides that touch a precompile act as in go-ethereum v1.17.9, in `eth_call`, `eth_estimateGas`,
  `eth_createAccessList` and `eth_simulateV1` (#1727, B-129). Both chains.** The four RPCs share one implementation of
  state overrides, and it judged precompiles by a fixed list of addresses that no block's fork matched.
  - **An entry at a precompile's address deletes the precompile** (`eth_call`, `eth_estimateGas`, `eth_simulateV1`).
    Any entry does: a `balance`, `nonce`, `code`, `state` or `stateDiff` override, an empty one and a `null` one. The
    call then reaches whatever the account holds (the overridden `code`, or nothing), where it used to reach the
    precompile; `code` at a precompile's address never ran.
  - **The precompiles an override can name or move are the block's own** (the same three). `0x0a` (KZG point
    evaluation, from Cancun) was refused with `is not a precompile`. BLS12-381 (from Prague) and P256VERIFY (from Osaka;
    on ETC from Olympia) were accepted at blocks that do not have them, and now are refused. The set is the one the EVM
    runs the block with, after the block overrides (`number`, `time`) are applied.
  - **Two moves to one target: the one from the higher source address wins, and both sources are deleted.** Before, both
    moves were dropped and both sources kept answering. Entries are taken in address order, as geth takes them.
  - **The `is not a precompile` text** names the account in EIP-55 mixed case (`0x…0A`), as geth prints it.
  - **`eth_createAccessList` applies its state overrides with no precompiles, as geth does** (`Apply(db, nil)`,
    `api.go:1366`). Every `movePrecompileToAddress` there is `account … is not a precompile`, a precompile the block has
    included, and no entry deletes a precompile. It used to move and delete precompiles as `eth_call` does. The other
    overrides apply as before.
- **`eth_simulateV1` reports `maxUsedGas` as go-ethereum does: the peak gas before the refund, raised to the calldata
  floor from Prague (#1727, B-130). Both chains.** `maxUsedGas` was a copy of `gasUsed`, which is the gas after the
  refund, so a call that earned a refund (one that clears a storage slot, for example) reported the same figure twice.
  It is now the gas the call had used before the refund, which is above `gasUsed` by the refund when no floor applies.
  From Prague (EIP-7623; on ETC from Olympia, ECIP-1121) it is also never below the calldata floor, which is how
  go-ethereum's `settleGas` computes it. `gasUsed`, the receipts, the block's `gasUsed` and the state root of a
  simulated block do not change, and neither does block import: the new `TxResult.maxUsedGas` is read only by this
  RPC, and `TxResultBlockImportPinSpec` pins the gas, receipts and state roots of block execution on ETH and ETC.
- **`eth_simulateV1` refuses a simulated Amsterdam block whose request contract has no code, and runs the block's
  system calls on its precompiles, as go-ethereum v1.17.9 does (#1727, B-131). ETH only: no ETC chain schedules
  Amsterdam.** A state override that cleared the code of the EIP-7002 withdrawal queue, the EIP-7251 consolidation
  queue or either EIP-8282 builder queue was skipped: the block was built without that request call. It now ends the
  whole simulation with -32000 and go-ethereum's text, `failed to process withdrawal queue: empty system contract: no
  code at 0x00000961Ef480Eb55e80D19ad83579A64c007002`; the queues are checked in the order withdrawal, consolidation,
  builder deposit, builder exit (`withdrawal queue`, `consolidation queue`, `builder deposit queue`, `builder exit
  queue`), and the address is in EIP-55 form. The EIP-4788 and EIP-2935 calls and the request calls also run with the
  block's precompile moves and deletions (`movePrecompileToAddress`, an entry at a precompile's address), as its
  transactions do. Before, a contract those calls reached found every precompile where it had been, and a precompile
  moved onto a request contract's address was not the one called. Block import is unchanged: it still refuses a block
  with an empty request contract before any call (`SYSTEM_CONTRACT_EMPTY`), and `RequestSystemCallsBlockImportPinSpec`
  pins the requests, requests hash, receipts and state root of Prague, Osaka and Amsterdam blocks on ETH, and of an
  Olympia block on ETC, as recorded before the change.
- **`eth_simulateV1` pays a simulated block the miner reward block import pays it (#1727, B-132). Both chains.** On
  ETC a simulated block was paid the first era's 5 ETC at any height, not ECIP-1017's era reward (4 ETC from block
  5,000,001 on ETC and 2,000,001 on Mordor, and 20% less each era after). A simulated block now takes its difficulty as
  go-ethereum's `makeHeaders` gives it: the previous block's, or zero after the merge (geth's `IsPostMerge`, which no
  ETC chain ever reaches). A block with a difficulty is paid the miner reward by the code block import pays it with
  (`BlockRewards.payMinerAndOmmers`): the era reward on ETC; on ETH before the merge, the Frontier, Byzantium or
  Constantinople reward. On ETH, a simulated block after London and before the merge now keeps its parent's difficulty,
  takes a `difficulty` override, and is paid 2 ETH, as in go-ethereum, where it had zero difficulty and no reward.
  Blocks before London and after the merge are unchanged. core-geth has no `eth_simulateV1`, so on ETC the reference is
  block import: `EthSimulateEtcRewardSpec` checks a simulated block's state root against `BlockPreparator`'s for the
  same header and transactions, on Mordor and on ETH after London. Block import is unchanged: `BlockRewardImportPinSpec`
  pins the balances and the state root of blocks with two ommers in several ETC and Mordor eras and on ETH before and
  after the merge, as recorded before the change.
- **`scripts/ephemery/fetch-genesis.sh` no longer refuses an iteration with Amsterdam at genesis.** It called that
  unverified, which kept fukuii off the live network from `ephemery-165` on, though the genesis loader handles it. Checked
  against the published genesis hash and a live sync to the head.
- **The copy of the Path trie into the hash-keyed store after SNAP no longer runs out of heap (#1724). Path-scheme ETH
  chains; it also closes the same growth on ETC.** On Sepolia (T063, #1718) a 4 GiB heap was exhausted 17 minutes after
  a clean hand-off: live heap rose from 146 MiB to 4,020 MiB in about 15 minutes, with one reorg-undo map entry (#1471)
  per published node. A second out-of-memory would have followed about 13 minutes later: the copy wrote a snapshot row
  per node, and the prune at the 64th save after hand-off built every one of those keys in memory. The copy now uses a
  bulk-load store that keeps no undo entries and writes no snapshot rows, and a prune of more than 10,000 rows runs in
  bounded writes that an interruption resumes. The Hash-scheme SNAP download and the checkpoint import pushed whole
  syncs through one instance of the same store, so the growth was there too. With the fix the live set was 49 MiB after
  a full GC and the heap stayed under 600 MiB after collections for the whole run, about 10.5 h (#1718). A datadir that
  died mid-copy restarts safely on this build: `SnapSyncDone` is written only after the copy, so the restart heals on
  the same pivot and copies again from the start. Nodes the crashed copy had written stay unprunable, which wastes space
  but is not a correctness issue.
- **A restart no longer overwrites an exact stored head weight with a peer estimate (#1654, #1723). ETC/Mordor; ETH
  calibration writes stop too.** Since 0.7.0, the calibration 30 s after regular sync starts, and the TD-PROXY-GAP
  handshake trigger, replaced the head's stored total difficulty with `peerTD x ourBest / peerMax` even when the stored
  value was exact. Fork choice compares stored weights, so each rewrite left a jump: on ETC, right after a restart, a
  node could reject a heavier chain or stick on a lighter branch, and STATUS and `admin_nodeInfo` carried the wrong
  value. The head is now classified first. An exact head (its parent's weight is plausible and head = parent +
  difficulty) is left alone; a head that does not follow from a plausible parent becomes parent + difficulty; otherwise
  the old estimates run, now logged as `PEER_INTERPOLATED_ESTIMATE` or `PEER_STATUS_ESTIMATE`. A peer is no longer
  evicted over a total-difficulty gap while the head is exact. The old anchor floor (1e13 per block) was never met on
  Mordor, at about 9e6 per block, so its local-chain anchor never applied; it is replaced by the plausibility floor
  used for the parent's weight (genesis + 131072 per block). After the merge a normally synced head is exact, so on
  Sepolia and mainnet the writes stop too; chains whose total difficulty never clears that floor (Ephemery,
  Platåberget, Hoodi) are not classified exact and keep the old estimate writes. Jumps that earlier releases already
  wrote are not repaired; that is planned for 0.12.0. This closes the 0.10.0 known issue "Total difficulty rewritten
  on restart".
- **A stored trie node whose bytes do not decode is treated as missing, is fetched again, and blames no peer (#1709).
  Both chains.** Import took the decode failure for a bad block from the peer. On Mordor, with the top miner's account
  leaf corrupted, it blacklisted between 183 and 2,200 honest peers depending on the kind of corruption, evicted up to
  401 blocks and stayed stuck, and a leaf corrupted to the empty node made `eth_getBalance` return 0x0 for a funded
  account. The node is now deleted, after a keccak re-check so a good node is never deleted, and fetched, with one
  `[STATE-CORRUPT]` ERROR and no strike, eviction or fork-detect. Re-run on Mordor, each case was caught in 14-53 s and
  repaired by one on-demand fetch or, after three exhausted fetches (about 10 min), by a re-SNAP of about 6.5 min, and
  the balance and `stateRoot` then equalled the public RPC's. Behaviour changes: an RPC read of a corrupt node answers
  "node not found" (before, a silent 0x0 or an internal error); `engine_newPayload` over a corrupt parent-state node
  answers ACCEPTED, not INVALID; a corrupt node that fetching cannot repair escalates to a re-SNAP after about 10
  minutes, which is cheap on Mordor and costly on Sepolia or mainnet. A corrupt node that import never touches can still
  stall the heal phase of a re-SNAP; that predates this change and is planned for 0.12.0. Only bytes that fail to
  decode, or a bare value that fails its hash, are caught when read: a node that parses but holds the wrong content is
  read as it is, so a block over it can still be answered INVALID. The background audit, which checks every node's
  hash, is what finds those.
- **A missing node or contract under `engine_newPayload` or import is repaired without stopping regular sync, and it
  takes longer to trigger a re-SNAP (#1694, #1697). Both chains.** #1694 reported that nothing repairs a hole met under
  `engine_newPayload`. Measured on Ephemery, import does: `newPayload` stores the block by hash and answers ACCEPTED,
  and import then fetches the node (storage holes at the head, 12.8 s and 24.5 s). What was wrong: on a SNAP-synced node
  the first missing contract went straight to a bulk recovery scan that reads the whole state with regular sync stopped
  (98 ms there, estimated at hours on Sepolia). Each miss is now fetched with `GetByteCodes` first (23.9 s, against
  about 60 s with a scan), and the scan is asked for only after 8 distinct misses in a process, once. A storage hole met
  while writing has no position; import used to ask for the storage root, and the peer's honest answer got it
  blacklisted. It now locates the position by walking what is present (up to 4,096 reads) and asks no peer where it
  finds none. Only exhausted fetches count towards a re-SNAP: before, each 30 s retry of the blocked import counted too,
  so a hole no peer served for about 150 s cost a re-SNAP after 2.5 to 3 minutes. It now takes three exhausted fetches
  with no block imported between them (at least about 17 minutes), or about 10 minutes for a storage hole that cannot be
  located. This applies to ETC as well. A hole in a large storage trie, such as a token's or an exchange's, is mostly
  not located within 4,096 reads and so goes to the re-SNAP.
- **SNAP storage sync recovers from a genuine stall that follows an early force-complete (#1708). Every SNAP sync, since
  0.7.0.** A storage force-complete during the account phase latched its guard for good, so a later genuine stall could
  never force-complete and SNAP hung instead of recovering. The guard now re-arms when the account phase ends; what the
  earlier force-complete recorded stays in the excluded set.
- **Storage recovery can no longer be marked done while an account still lacks proven storage (#1712). Every SNAP
  sync; introduced after 0.10.0 by spec 022 slice 3 (#1685).** The excluded set was read in two scans, and the healing
  coordinator promoting an entry from dormant to active between them hid it, so `storageRecoveryDone` could be set with
  an entry left and the next start skipped the recovery scan for that account. The window was about 60 microseconds and
  needed a hand-off while healing was promoting, for example after a re-peg. Both scans and every write to the set now
  hold one lock. An entry inserted between finalisation's read and its flags commit is still not seen; that is planned
  for 0.12.0.
- **The SNAP controller no longer hangs after going dormant, and no longer crashes on a second capability check (#1515,
  #1516, #1662). Every SNAP sync.** Three paths (state-validation retries exhausted, pivot repeatedly unservable,
  account-stall limit) entered dormant mode but discarded the dormant behaviour they got back, so the controller stayed
  in `syncing`, dropped the wake-up and SNAP hung for good. A capability check while the account coordinator was already
  running spawned a second one under the same name, which threw `InvalidActorNameException` and crashed the controller.
- **A restart in the middle of SNAP resumes SNAP when `do-snap-sync = false`, and storage recovery uses the node's
  storage scheme (#1434, #1595, #1666).** With `do-snap-sync = false`, a node holding an unfinished SNAP sync (accounts
  complete, or the best block still the SNAP pivot) went to regular sync on a half-built trie after a restart. It now
  resumes SNAP and logs a WARN; a finished, checkpoint-imported or regular-synced node never re-enters SNAP. ETC and
  Mordor ship with `do-snap-sync = true`, so their default path is unchanged. The pivot's header, chain weight and best
  block are also written in one batch, so a crash cannot leave a best block without its header. Storage recovery had
  spawned its coordinator with the Hash scheme and no progress store; on Path-scheme ETH nodes recovered storage was
  written hash-keyed, where the Path reader may not find it. It now uses the node's scheme.
- **Three storage-integrity fixes (#1607, #1549, #1581, #1667). No state root or trie content changes.**
  `ReadOnlyNodeStorage`, the scratch buffer behind `eth_call`, `eth_estimateGas`, `eth_simulateV1`, replay, snap serving
  and the checkpoint exporter, applied removals to content-addressed nodes, so an earlier root in the same buffer could
  lose a node it still referenced; it now ignores removals (block import does not use it). `flushTrieNodeWrites`
  cleared its buffer before the write succeeded; it now commits and then clears, and a failed write keeps the buffer
  and throws. With `pruning.mode = inmemory`, SNAP finalization and checkpoint import could record `snapSyncDone` while
  the ingested state lived only in the node cache; both now force the state to disk before the flags are written.
  Archive and basic pruning, the ETC default, were not affected.
- **A peer disconnect is reported at once, not 15 s late (#1527, #1669). Both chains.** `PeerActor` ignored
  `RlpxTerminated` once disconnected and waited out the 15 s `disconnectPoisonPillTimeout` even when its RLPx
  connection was already dead, which delayed `PeerTerminated` and `PeerDisconnected`. It now stops when its own
  connection ends. This covers a remote that closes TCP; against a silent remote the timer still governs. Maintained
  peers redial about 15 s sooner after a local disconnect.
- **SNAP downloads and other peer-driven paths no longer slow down as decoded lists grow (#1672, #1673, #1675, #1678).**
  RLP lists decoded into an immutable `Queue`, where reading item i costs O(i), so every indexed walk over a decoded
  list was quadratic, and peers choose several of those lengths. In a Mordor SNAP sync against one local core-geth,
  0.10.3 fetched 903k accounts (34% of the keyspace) in 23 minutes at about 640/s and falling, with fukuii at 350% CPU
  in `Queue.apply`; with the fix it fetched all 2.72M in 8 minutes at about 5,400/s and about 80% CPU, and the whole
  SNAP sync took 25.6 minutes. Serving `GetTrieNodes` was quadratic in the peer's path list too, a way to amplify our
  CPU use. RLP lists now decode as `Vector`s, with the same accepted inputs, values and re-encoded bytes and an
  unchanged `fukuii-rlp` public API, and the transaction pool, receipt accumulation, the ledger's persist step, receipt
  presence checks and healing's attempt counting were made linear. Measured before the change: the `PooledTransactions`
  size check took 155 ms for 4,096 transactions and 2.7 s for 16,384, on the pool actor; the ledger's change set took
  128 ms for 5,000 dirty entries and 2 s for 20,000, per persist. Two limits are new, both go-ethereum's: at most 256
  hashes per `GetPooledTransactions`, and at most 4,096 tracked announced hashes per peer.
- **The transaction sender cache is keyed by chain id as well as hash (#1629, #1668).** A legacy EIP-155 transaction
  whose `v` names another chain recovers a garbage sender, or none, under a mismatched config, and that result was then
  served to callers on the right config. On a single-chain node the new key is a relabelling, so nothing changes there.

### Known issues
- **`eth_simulateV1`: expect some differences from go-ethereum (#1727, planned for 0.12.0). Both chains.** The
  unbounded block-gap loop that stalled the node, the malformed override values it accepted, the precompile override
  cases it got wrong, its `maxUsedGas` and its VM and precompile error texts are fixed (see Fixed). A comparison
  of 815 cases with geth v1.17.9 on Ephemery found this difference:
  - a simulated block before Amsterdam (Prague, Osaka) runs none of the request system calls, which go-ethereum runs
    for every Prague block: its `requestsHash` stays the empty-requests hash, the queues are not dequeued in its
    state, and an overridden-away request contract is not an error there.

  The difference listed does not touch block import or mining.
- **Path-scheme ETH chains copy the whole state into a second store after a SNAP sync, and the node does not follow
  the chain until the copy ends (removal planned in spec 018).** This applies only when SNAP sync is switched on
  (`fukuii.sync.do-snap-sync = true`); the shipped Sepolia, mainnet, Ephemery and Platåberget configs sync by
  importing blocks and never reach it. After SNAP completes on a chain that uses the Path storage scheme, fukuii copies
  the whole state into a second, hash-keyed store before it can execute blocks. It logs `[PATH-PUBLISH] account=...
  storage=...` while it runs and `[PATH-PUBLISH] done` at the end; until then the node executes no block, the consensus
  client sees it as syncing, and a validator should not rely on it. Measured on Sepolia (T063, #1718), on an NVMe host:
  the account trie (215M nodes) took 79 min; the storage tries ran at a rate that fell from about 36k to 9k nodes/s as
  the store grew, and the run was stopped at roughly 40-50% of the copy after 9.3 h (the total is an estimate). A full
  copy is expected to take days on Sepolia; mainnet, about 3-4 times larger, has not been run and should be expected to
  take weeks. After the copy the node still executes every block since the SNAP pivot. **Do not restart the node
  during the copy**: a restart begins the copy again from the start (the SNAP download is kept; a restart long after
  the hand-off may first heal the state forward to a newer pivot), and every node copied before the restart keeps one
  extra reference that is never pruned. That costs disk space only; state reads, block execution and proofs are
  unaffected, and only a fresh sync into an empty datadir removes it. The Sepolia hand-off also left 30 accounts
  excluded, for on-demand fetches and the background audit to repair: a block that touches one waits on a fetch, and
  one that cannot be fetched ends in a re-SNAP, which on a Path chain means the copy again. ETC and Mordor use the Hash
  scheme and need no copy. 0.11.0 ships no workaround.
- **Plan disk for Sepolia, and for a full-history node generally: backfill has no limit and nothing stops at a full disk
  (#1483).** A SNAP sync of Sepolia needs, by estimate, about 310-340 GiB once the post-SNAP copy above has finished
  (extrapolated from T063, whose datadir was 255 GiB with roughly half of the copy done). It then backfills the whole
  block history into the same datadir, roughly another 500-900 GB (estimated from a sample of the chain at block 12.04M,
  with receipts the least certain part). Mainnet needs far more. There is no history expiry yet, and a separate path for
  history is not available. Check free space before you start a sync, and watch it during backfill. To skip backfill, set
  `fukuii.sync.snap-sync.chain-download-enabled = false`: the node then follows the head and holds no block history from
  before the point it synced to.
- **Running an older build on a datadir this one has synced.** An older binary neither reads nor clears the healing
  invariant marker, so what it writes is not covered by it, and a later upgrade cannot tell. After going back up, start
  once with `-Dfukuii.snap.clearDoneOnStart=true`, which removes the marker and runs healing again. An older build
  also finds the healing-frontier column family emptied by this release's one-time cleanup, so an older build
  restarted in the middle of healing pays a full verification walk.

## [0.10.0] - 2026-10-20

### Changed
- **JDK 25 is the baseline, and the JVM must run with the C2 `ReduceAllocationMerges` workaround (#1642, #1643).
  Breaking for hand-written launchers.** HotSpot C2 on JDK 23 and later miscompiles `ReduceAllocationMerges`
  (JDK-8385993, fixed in JDK 28; JDK-8393049 reports silently wrong field values), a consensus-divergence risk for a
  node. Every JVM fukuii runs in now carries `-XX:+UnlockDiagnosticVMOptions -XX:-ReduceAllocationMerges`: sbt
  (`.jvmopts`), forked test JVMs, `bin/fukuii` (`application.ini`), and every Docker image (`JDK_JAVA_OPTIONS`). The
  node, the multi-instance runtime and the checkpoint exporter **refuse to start (exit status 78)** on JDK 23+
  without the flag, unless you set `-Dfukuii.allow-c2-reduce-allocation-merges=true` or
  `FUKUII_ALLOW_C2_REDUCE_ALLOCATION_MERGES=true` (the node then starts and logs the same message as an ERROR). A
  hand-written `java -jar` line or systemd unit must add the two flags. Existing containers are protected only after
  you pull the rebuilt images. The Debian package now depends on JDK 25. See docs/runbooks/known-issues.md Issue 16
  and ADR INF-007 (supersedes INF-001's JDK 21 minimum).
- **Blob parameters are per chain (EIP-7840 `blobSchedule`, EIP-7892 BPO forks, #1637).** The blob target, max and
  base-fee update fraction of each fork now come from the chain config's `blob-schedule` instead of constants fixed to
  the mainnet/Sepolia schedule. Every consumer reads it: excess blob gas and blob base fee, the per-block blob limit in
  header validation and block building, `BLOBBASEFEE`, `eth_blobBaseFee`, `eth_feeHistory`'s blob fields,
  `eth_config.blobSchedule`, receipts' `blobGasPrice`, GraphQL, and the `maxFeePerBlobGas` the node fills in for a transaction it signs. mainnet, Sepolia, Platåberget and hive keep exactly today's values
  (go-ethereum's default Cancun 3/6/3338477, Prague 6/9/5007716, BPO1 10/15/8346193, BPO2 14/21/11684671); ETC chains
  have no blob forks and are unchanged. Ephemery now follows its own schedule (BPO1 8/12/6676955, BPO2 10/15/8346193):
  before, its blocks at or above the blob target failed import with `INCORRECT_EXCESS_BLOB_GAS` and blocks with more
  blobs than it allows were accepted. `scripts/ephemery/fetch-genesis.sh` maps genesis.json `config.blobSchedule`
  instead of warning, and hive's adapter maps `HIVE_{CANCUN,PRAGUE,BPO1,BPO2}_BLOB_*`.

  **Breaking for custom ETH-family chain configs:** a chain config that declares `cancun-`, `prague-`, `bpo1-` or
  `bpo2-timestamp` must declare a `blob-schedule` entry for each, or the node refuses to load it (as it does a schedule
  with max < target, a zero target or update fraction, out-of-order blob-fork timestamps, `bpo3-timestamp` or later,
  or an `osaka`/`amsterdam` entry that differs from the one it inherits). An Ephemery chain config generated before
  this change has no `blob-schedule`: re-run `fetch-genesis.sh`. See docs/runbooks/custom-networks.md.
- **Debian package: the launcher now reads `/etc/fukuii/application.ini`.** Deb installs never applied it before, so
  the JVM ran with its own defaults (a heap of about a quarter of RAM, no OOM exit). After upgrading, a deb install
  runs with the shipped defaults: `-Xmx3g -Xms1g`, `MaxDirectMemorySize=512M`, G1, exit and heap dump
  (`/var/lib/fukuii/heapdump.hprof`) on OutOfMemoryError. **Large mainnet nodes should raise `-J-Xmx` in
  `/etc/fukuii/application.ini`** (a conffile; edits survive upgrades), and make `/var/lib/fukuii` writable by the user
  running the node.
- **Transaction submission over RPC follows go-ethereum's rules (#1629 S0b).** `eth_sendRawTransaction`,
  `eth_sendTransaction`, `personal_sendTransaction` and GraphQL `sendRawTransaction`:
  - refuse legacy transactions that are not replay-protected (no EIP-155 chain id) with "only replay-protected
    (EIP-155) transactions allowed over RPC", on every chain, ETC included (core-geth does the same). Opt back in with
    `fukuii.network.rpc.allow-unprotected-txs = true` (go-ethereum `--rpc.allow-unprotected-txs`);
  - refuse a transaction whose fee (gas * fee cap) exceeds `fukuii.network.rpc.tx-fee-cap` (default 1 ether, 0 = no
    cap; go-ethereum `--rpc.txfeecap`) with "tx fee (X ether) exceeds the configured cap (Y ether)".
    `eth_signTransaction` and `personal_signTransaction` check the cap too; `eth_fillTransaction` does not;
  - on ETH-family chains, apply the pool's rules against what it holds: "already known" for a transaction already
    pooled, "replacement transaction underpriced" unless a replacement raises both fee caps by `txPool.price-bump`
    (default 10%; `txPool.blob-price-bump`, default 100%, for blob transactions, as go-ethereum's blobpool), "insufficient funds" when the sender's pending transactions together overdraw its balance, and
    EIP-7702's one-in-flight-transaction limit for delegated senders. Peers' transactions meet the overdraft and
    delegation rules too. ETC's pool is unchanged;
  - on ETH-family chains, keep a sender's transactions to one of go-ethereum's subpools, blob or every other type:
    a blob transaction from a sender with a pooled non-blob transaction, or a non-blob one from a sender with a pooled
    blob transaction, is refused with "address already reserved" (-32000; go-ethereum `txpool.ErrAlreadyReserved`),
    a same-nonce one included, until the sender's transactions of the other kind leave the pool. Peers' transactions
    meet the rule too. Replacement within the same kind still follows the price-bump rules. ETC is unchanged;
  - check blob sidecars as go-ethereum's pool does: from Osaka a version-1 (cell proof) sidecar is required (a
    version-0 one sent to `eth_sendRawTransaction` is converted, as go-ethereum does), before Osaka a version-0 one; versioned hashes must match the commitments, at most 6 blobs, and the KZG proofs must
    verify.
- **`eth_getTransactionCount(addr, "pending")` answers the pool's next nonce, as go-ethereum and core-geth do**
  (`GetPoolNonce`): the account's nonce at the head advanced over its pooled transactions with consecutive nonces; a
  nonce-gapped (queued) transaction does not count. Before, it did not read the pool.
  GraphQL `pending { account { transactionCount } }` answers the same, and `eth_sendTransaction` /
  `personal_sendTransaction` fill the same nonce (they took one past the sender's highest pooled nonce, a gapped one
  included). All chains, ETC included.
- **Announced transactions are fetched from one peer at a time.** The pool requested an announced transaction from
  every peer that announced it, and when the peer it got it from was dropped for serving a bad copy, it asked nobody
  else. As in go-ethereum's tx fetcher, a hash is now requested from one announcer; the others are kept as
  alternates and asked in turn when the request goes unanswered for `txPool.announcement-fetch-timeout` (default
  5 s, go-ethereum's `txFetchTimeout`), when the requesting peer disconnects, or at once when it is dropped for a
  delivery that contradicts its announcement. Each delivery is checked against its sender's own announcement. Fixes
  hive devp2p `TestBlobTxWithoutSidecar` / `TestBlobTxWithMismatchedSidecar`, which go-ethereum #35869 rewrote to
  have three peers announce the same blob transaction.
- **Behaviour changes to check before upgrading (#1640, #1639).** A fresh ETC, Mordor or Gorgoroth node now answers
  `eth_gasPrice` / `eth_maxPriorityFeePerGas` with 1 gwei (was 1 wei). `eth_pendingTransactions` now returns full
  objects only for executable (not nonce-gapped) pool transactions sent from keystore accounts, as geth does.
- **Internal.** The SNAP controller is split into modules (spec 016, #1401, no behaviour change intended); sbt is
  1.13.0 (#1631); Scala 3 import syntax is enforced by a scalafix rule and a CI grep (#1632).

### Added
- **Portable chain files: export and import in go-ethereum's format (#1651).** `fukuii <network> export <file>
  [first [last]]` and `fukuii <network> import <file>` run offline against a stopped node's datadir, and
  `admin_exportChain` / `admin_importChain` use the same code. The file is geth's format (consensus-encoded blocks
  back to back, gzipped when the name ends in `.gz`), so files move between fukuii and geth in either direction.
  Import executes and validates every block and adopts each one as it is imported; a block that fails stops the
  import with the chain left at the last good block. Before, `admin_exportChain` wrote a 4-byte length before every
  block, which no other client reads; the importer still accepts those files with a warning. Checked on Ephemery:
  a 12,520-block file written by Erigon imports, and re-exports byte-identical, with hashes, state roots and receipt
  roots matching the live chain. See docs/runbooks/backup-restore.md, "Portable Chain Snapshots".
- **`eth_getHeaderByNumber` and `eth_getHeaderByHash` (#1616).** New RPCs returning the `eth_getBlockByNumber` header
  object without size, transactions, uncles, withdrawals and totalDifficulty; by-number resolves the same tags,
  not found returns `null`.
- **Typed transactions on every RPC interface (#1629, slices S0-S5).** Legacy, EIP-2930, EIP-1559, EIP-4844 and
  EIP-7702 transactions are handled with geth's rules, error strings and defaults on JSON-RPC and GraphQL:
  - S1 (#1633): one shared `TxArgs` model with strict, geth-style JSON decoding (`-32602` with geth's hexutil text).
    Type inference follows geth's `ToTransaction`; one deliberate deviation: `gasPrice` together with an access list
    or blob fields and no explicit `type` is rejected instead of silently building a legacy transaction.
  - S0 (#1634): admission rules are #1641's (see the transaction submission entry above). Delta: pending typed
    transactions report the fee cap as `gasPrice`; on ETC and Mordor, peer transactions of a type not active at the
    head (0x2/0x4 before Olympia) are no longer pooled or gossiped.
  - S2 (#1635): `eth_sendTransaction` and `personal_sendTransaction` build all five types; new
    `eth_signTransaction`, `personal_signTransaction` (`{raw, tx}`) and `eth_fillTransaction`. Defaults follow geth's
    `setDefaults`; gas is estimated rather than fixed at 90000; blob sidecars are built and checked with KZG.
  - S3 (#1636): `eth_call`, `eth_estimateGas`, `eth_createAccessList`, `debug_traceCall(Many)`,
    `trace_call(Many)` and GraphQL call/estimateGas share one message builder and accept state and block overrides.
    `eth_createAccessList` now iterates the access-list tracer to a fixed point. RPC gas cap
    `fukuii.network.rpc.gas-cap` (default 50M, as geth).
  - S4 (#1638): `eth_simulateV1` calls use the strict decoder and geth's type selection and validation error codes.
  - S5 (#1639): the GraphQL `Transaction` matches geth's schema (`yParity`, effective-price fields, blob fields);
    `eth_pendingTransactions` returns executable pool transactions from keystore accounts, as geth does.
- **Gas price oracle (#1640).** `eth_gasPrice`, `eth_maxPriorityFeePerGas`, GraphQL and the tip default of
  send/sign/fill use a port of geth's `eth/gasprice` oracle, replacing the fixed `minTip` (1 wei on ETH, so locally
  sent mainnet transactions could sit unincluded). Config under `fukuii.network.rpc.gpo` with geth's defaults
  (`blocks` 20, `percentile` 60, `max-price` 500 gwei, `ignore-price` 2 wei, `max-header-history` and
  `max-block-history` 1024, the last two now read by `eth_feeHistory`). Start price: 1 mwei on ETH-family chains,
  1 gwei on ETC, Mordor and Gorgoroth (core-geth); override with `gpo-default-price` in the chain config or
  `fukuii.network.rpc.gpo.default-price`. A fresh ETC node now answers 1 gwei instead of 1 wei.
- **Ephemery testnet support (#1628).** `fukuii ephemery` is a known network.
  `scripts/ephemery/fetch-genesis.sh` downloads the current `ephemery-N` genesis and generates the chain config,
  genesis (timestamp re-encoded to hex, `mixhash` renamed `mixHash`), a Lighthouse `testnet/` directory and a
  `current` symlink; it fails on genesis features fukuii cannot represent. See docs/runbooks/ephemery.md.
- **Ephemery load generator (#1649, tooling).** `scripts/ephemery/load-gen.py` sends typed (0x0-0x4, including
  blob and EIP-7702) transactions through fukuii's own RPC plus `eth_fillTransaction`, `eth_estimateGas`,
  `eth_createAccessList` and `eth_call` probes, for import differentials. Nothing in the build depends on it.

### Fixed
- **SNAP pivot block stored without its body (#1652). Every chain.** SNAP finalization stored the pivot block with
  an empty body to record its number, overwriting the body `newPayload` had stored; backfill never refetched it. On
  Ephemery the pivot served `withdrawals: null` under a non-empty `withdrawalsRoot`, over RPC and to peers. An ETC
  pivot with transactions or ommers was affected the same way. Finalization now writes only the header and number
  mapping; a body that does not match its header is refetched; a Shanghai genesis is stored with its empty
  withdrawals list (matching geth's genesis RLP); a one-time startup repair removes a bad pivot body and rewrites a
  bad genesis body; and a block whose header commits to withdrawals but whose body has none is rejected, as geth does.
- **Checkpoint archives carried an estimated total difficulty (#1653). ETC/Mordor.** A node that SNAP-synced stores
  a peer-interpolated total difficulty for its pivot, and `checkpoint export` copied it into the archive (on Mordor,
  off by 114,098,267). A node that imported such an archive kept the error in the total difficulty it advertises to
  peers and reports over RPC. Fork choice was not affected. The exporter now sums block difficulties from genesis and
  refuses (writing nothing) when a header below the export block is missing or the chain does not reach this
  network's genesis; `--allow-estimated-td` exports the stored value with a warning.
- **Tx pool memory leak (OOM).** A node that was not synced kept taking transactions from peers. During SNAP sync
  the pool rejected every one (no state to check them against), but the EIP-4844 blob sidecars that came with them
  (130-830 KB each) were stored before admission and never removed. A Sepolia node held 2.6 GB of them and was
  OOM-killed every ~10 hours. Two changes. (1) As in go-ethereum (`AcceptTxs`), a node ignores
  `NewPooledTransactionHashes`, `Transactions` and `PooledTransactions` from peers until it is synced: an Engine API
  forkchoiceUpdated has set the head, regular sync has reached the best block peers announced, or block production
  is enabled. It also ignores them while SNAP sync or state recovery runs. Transactions submitted over JSON-RPC are
  not affected. This applies to ETC too: an ETC node stops taking peer transactions while it catches up. (2) The pool
  keeps a blob sidecar only while its transaction is pooled, drops it on every removal path, and caps the total at
  `txPool.blob-sidecar-budget` (default 256 MiB); past the cap the oldest blob transactions are evicted.
- **TUI (`--tui`).** The terminal UI froze after its first frame: key input used JLine's `peek(0)`, which waits
  forever, so the update loop blocked until a key was pressed. It also never showed real data, because the status
  queries were never wired in, so peers, blocks and sync status sat at their defaults. It now polls the peer manager,
  the sync controller (the same queries `net_peerCount` and `eth_syncing` answer), the best block and SNAP progress.
  Further fixes: `D` no longer shuts down the node; `Q` exits through the normal shutdown hook instead of
  interrupting its own shutdown; frames fit the terminal exactly (no scrolling or wrapping, footer pinned);
  non-TTY/`dumb` terminals fall back to plain logging; the terminal mode and screen are restored on exit;
  configuration errors are printed before the TUI suppresses console logging; sync speed/ETA is measured from
  the first block observed rather than from genesis.
- **discv4 saturated the CPU and starved block import and the Engine API (#1648).** On an Ephemery node (4 cores)
  fukuii sat at about 286% CPU with 3 peers, Lighthouse's engine upcheck timed out and Engine requests queued for
  over a minute (`JWT expired`); thread dumps showed discv4 ECDSA signing and recovery. Causes: an unbounded full
  Kademlia lookup per peer request, 2-3 signature recoveries per datagram, and discovery sharing
  `IORuntime.global` with block import. Now: discovery runs on its own pool (`network.discovery.compute-threads`,
  default 1, threads `discovery-compute-N`); one lookup runs at a time, feeding a bounded queue, with the pause
  doubling from 2 s to 64 s while lookups find nothing new; each packet is type-checked before any crypto and
  recovered keys are cached (4096 entries); inbound requests are limited to 10/s per IP (burst 30, shared across source ports) and 100/s
  overall (burst 200); these inbound limits are new behaviour on the public discovery UDP port and are fixed in code
  (`DiscoveryNetwork.InboundRequestLimits.default`), not configurable. Measured on Ephemery: about 286% to about 15% CPU.
- **`eth_simulateV1` override keys are strict (#1616).** An unknown key in `blockOverrides` or a `stateOverrides` entry
  returns `-32602`; the `difficulty` block override works pre-merge and returns `-32602` after.
- **`eth_feeHistory` returned 0 for every reward percentile (#1605).** It now computes them as geth does: transactions
  sorted by effective tip, first whose cumulative gas reaches `gasUsed * p / 100`.
- **Amsterdam rpc-compat vectors (#1606).** `eth_getBlockByNumber` emits `blockAccessListHash` and `slotNumber` for
  Amsterdam headers; `testing_buildBlockV1` / `testing_commitBlockV1` handle PayloadAttributesV4 and the block access
  list; `eth_simulateV1` builds Amsterdam block identity (slot number, EIP-7928 access list, header `gasUsed` =
  max(execution, state)). Engine API block building and block import are unchanged.
- **PoW rewind abandoned the canonical prefix (#1592, #1594). ETC/Mordor.** A branch-resolution rewind re-presented
  the node's own canonical blocks, which took the reorg path and had their state abandoned; under basic pruning
  live nodes were deleted 64 blocks later and a later import failed with `MissingAccountNodeException`. The importer
  now drops the already-canonical prefix (as core-geth does) and `settleHead` never abandons a block it just
  re-executed with the same hash.
- **SNAP resume checkpoint lost completed ranges (#1597, #1599).** Ranges finished before a restart dropped out of
  later checkpoints (16 cursors became 15), so the resume was rejected and the record and its task files destroyed.
  Completed ranges are now kept, a recoverable keyset is filled (WARN lists the filled ranges), and a layout mismatch
  holds the record instead of deleting it.
- **Accounts-complete recovery paths were not persisted (#1603, #1604).** The controller asked the coordinator for
  the storage and codeHashes task-file paths and handled the replies off the actor thread (Pekko Typed threw
  `UnsupportedOperationException`); the codeHashes path was never saved, so a restart during the storage phase with
  bytecode incomplete restarted SNAP from the accounts phase. The coordinator now tells the controller both paths
  on completion.

### Known issues
- **ETC/Mordor checkpoint archives from SNAP-synced nodes.** With the default ETC config, header backfill below the
  SNAP pivot runs after state sync, so `checkpoint export` refuses until it has reached genesis. Do not publish
  archives made with `--allow-estimated-td` from a SNAP-synced node, or archives made before 0.10.0.
- **Total difficulty rewritten on restart (#1654, ETC/Mordor, since 0.7.0).** A calibration 30 s after regular sync
  starts overwrites the head's stored total difficulty with an estimate, leaving a step in the stored values that can
  bias fork choice between branches forking below that block. Fix in progress.
- **`sbt formatCheck` fails (#1656).** Scalafix violations accumulated because CI does not run scalafix; tooling only,
  no effect on the node.

## [0.9.0] - 2026-10-05

The Glamsterdam release. Sepolia activates Amsterdam at timestamp 1791294816 (2026-10-06 13:53:36 UTC);
every Sepolia node must run 0.9.0 before then. Tracking issue: #1415 (spec: `specs/009-amsterdam-fork-support`,
#1409). ETC, Mordor and Gorgoroth do not activate any of it. See
[docs/releases/0.9.0.md](docs/releases/0.9.0.md) and
[docs/specifications/GLAMSTERDAM.md](docs/specifications/GLAMSTERDAM.md).

### Added
- **Networks.** Platåberget, the public Glamsterdam testnet (`glamsterdam-devnet-8`, chain id 7091047534), as
  a launcher network: `fukuii plataberget` (#1417). It ships the devnet-8 genesis (hash `0xee33ef92…2b31`),
  the 20 devnet-8 EL bootnodes and every fork through Osaka, BPO1 and BPO2 at genesis, with
  `amsterdam-timestamp = 1787212224` (2026-08-20). Sepolia gets `amsterdam-timestamp = 1791294816`; its fork
  id announces it as the next fork (`0x268956b6`, next `1791294816`), as go-ethereum does.
- **Amsterdam (Glamsterdam) execution rules**, all gated on the Amsterdam timestamp and unreachable on any ETC
  chain. EIP-2780, 8037, 8038, 7778, 7954 and 7708 first shipped in 0.8.5 (#1408); 0.9.0 completes them against
  the execution-specs corpus and adds the rest:
  - EIP-2780 resource-based intrinsic gas, with authorization processing and dispatch as execution-specs
    `create_evm` (#1423)
  - EIP-7778 block gas without refunds; EIP-7954 contract size limit 64 KiB (initcode 128 KiB); EIP-7708 ETH
    transfer logs
  - EIP-8037 state-creation gas: per-dimension block capacity (#1420); header `gasUsed` is the maximum of the
    execution and state dimensions, receipts sum both; state-gas reservoir kept across precompile calls
    (#1437); CALL's new-account charge before sizing the child's gas, and system calls (EIP-4788, EIP-2935) with
    their own reservoir (#1440)
  - EIP-8038 state-access gas, with the EXTCODESIZE / EXTCODECOPY code-read surcharge (#1440)
  - EIP-7976 calldata floor and EIP-7981 access-list cost, with the floor as a validity rule (#1421)
  - EIP-8246: SELFDESTRUCT no longer burns (#1422)
  - EIP-7843 `SLOTNUM` opcode (`0x4b`) and EIP-8024 `DUPN` / `SWAPN` / `EXCHANGE` (`0xe6`-`0xe8`) (#1424)
  - EIP-7928 block-level access lists: codec and header accessors (#1418); access recording through the VM,
    per-index diff, validation against the header hash on import, and a `blockHash -> BAL RLP` store (#1426)
  - EIP-8282 builder execution requests, with `eth_config` reporting the builder deposit and exit contracts
    (#1430)
  - EIP-7997 needs no client code (networks provide the factory contract); EIP-7688, 7732, 8045 and 8061 are
    consensus-layer changes
- **Engine API** (#1425, #1427, #1428):
  - `engine_newPayloadV5` with `ExecutionPayloadV4` (`blockAccessList`, `slotNumber`); a missing list is
    `-32602`, an undecodable one is `INVALID`
  - `engine_forkchoiceUpdatedV4` with `PayloadAttributesV4` (`slotNumber`, optional `targetGasLimit`) and
    `custodyColumns` (validated, otherwise ignored)
  - `engine_getPayloadV6`
  - `engine_getPayloadBodiesByHashV2` and `engine_getPayloadBodiesByRangeV2`, adding the stored access list
    (`null` before Amsterdam or when pruned)
  - `engine_newPayloadV4`, `engine_getPayloadV5` and `engine_forkchoiceUpdatedV3` refuse Amsterdam timestamps
    with `-38005`; `engine_exchangeCapabilities` advertises the new methods
  - `engine_getBlobsV3` / `V4` are not implemented (#1431)
- **Amsterdam payload builder** (#1427): `forkchoiceUpdatedV4` -> `getPayloadV6` -> `newPayloadV5` produces a block
  an independent node accepts as VALID. It builds the 23-field header, the slot number and the access list,
  and steers the gas limit toward `targetGasLimit`.
- **eth/71** block access list serving (EIP-8159, #1428): `GetBlockAccessLists` is answered from the stored
  lists within a 2 MiB / 1,024-entry limit. Opt-in via `fukuii.network.protocols.eth71` (default `false`).
  fukuii does not fetch lists from peers.
- **Conformance:** the execution-specs `tests@v21.0.0` blockchain-test replay (`EestBlockchainReplay`,
  `scripts/eest/fetch_fixtures.py`) and the CI job `EEST Amsterdam` (#1419): 26,503 / 26,503 Amsterdam tests
  through block import, and the payload builder rebuilds the corpus's 27,352 blocks with identical hashes.
- `docs/specifications/GLAMSTERDAM.md` (EIP set, schedule, implementation status, Platåberget test guide) and
  the 0.9.0 release notes.
- **Block access list prefetch and fetch.** On an Amsterdam block whose access list hashes to the header's
  `blockAccessListHash`, a bounded pool reads the listed accounts, storage slots and contract code from the
  parent state in parallel with execution (`state-read-caches.bal-prefetch-*`, #1467). Regular-sync catch-up
  fetches the lists from an eth/71 peer (`GetBlockAccessLists`); a list that does not match its header hash is
  dropped and the peer blacklisted (#1468). A peer list is only a prefetch hint: block validity is decided by the
  list fukuii computes itself. ETC never supplies a list and does not prefetch.
- **SNAP sync option** `fukuii.sync.snap-sync.defer-chain-backfill-until-state-complete`: body and receipt backfill
  waits until state is finalised while headers keep downloading. `true` on Ethereum, Sepolia and Platåberget,
  `false` elsewhere (#1466).
- **Read-cache controls** (`state-read-caches`): `jumpdest-block-memo-bytes` (#1460, #1463) and strongly held
  explicit cache sizes (#1476, see Changed). `[IMPORT-TIMING]` reports where a block's import time went; it logs
  at DEBUG unless `import-timing-log = true`.
- Diagnostics: a Disconnect received instead of Hello is logged with its reason, as are `TCP_CLOSED` and
  `DISCONNECT_SENT` for handshaked peers (#1479).

### Changed
- The fork id carries the head's timestamp in eth/68, 69 and 70+ `Status`, the ENR and DNS filters (#1429), so
  peers at a timestamp fork agree on it.
- Engine API payloads on every ETH-family fork steer the gas limit toward the node's gas ceiling
  (`mining.gas-limit-target`, default 60,000,000), as go-ethereum does; an Amsterdam payload without
  `targetGasLimit` does the same.
- The txpool pre-filter for peer and re-org transactions admits under the fork active at the chain head: EIP-2780's
  intrinsic cost and the EIP-7976 / EIP-7981 floor at Amsterdam, EIP-7623's floor before. `eth_sendRawTransaction`
  does not use it (#1430).
- `eth_estimateGas` starts its search at the least valid gas limit, `max(intrinsic, calldata floor)` (#1430).
- `eth_simulateV1` builds the 23-field Amsterdam header at Amsterdam timestamps (#1430).
- `debug_*` and `trace_*` replays of an Amsterdam block re-execute it as block import did (#1430).
- The docs link check builds the site at its served `/fukuii/` path.
- **eth/70 and eth/71 are advertised on Ethereum, Sepolia and Platåberget** (`network.protocols { eth70 = true,
  eth71 = true }` in their configs, #1475). Peers that offer only eth/68-69 negotiate down. ETC, Mordor and
  Gorgoroth are unchanged; eth/72 and snap/2 stay off.
- **`state-read-caches` explicit sizes (#1476).** A byte-size key that is set is used as given and held with strong
  references, so the collector cannot empty it. Each explicit cache is clamped to 50% of the max heap; explicit
  caches totalling more than 60% are scaled down proportionally with a WARN; the recommended total is 35% or less.
  A key left unset keeps its default, capped at a fraction of the heap and held softly, as before. The shipped
  `state-read-caches.conf` leaves the byte-size keys unset. Each cache's effective size and reference type is
  logged at INFO at startup.
- Block execution analyses each contract's JUMPDESTs once per block and caches decoded trie nodes and contract
  code by hash (#1460, #1463); code reads no longer fill RocksDB's block cache (#1467); JUMPDEST sizing is
  O(words) and `Address.hashCode` is computed once (#1477). State roots, gas and iteration order are unchanged.
- Contract code that is missing is no longer executed as empty: the read throws, the node fetches the code over
  SNAP `GetByteCodes`, and a node that was SNAP-synced recovers all missing code in one bulk scan (#1456, #1459).
- SNAP sync fetches bytecode of healed accounts before it finalises (#1456). State-node fetches rotate across
  snap peers at once instead of waiting 5 s per bad reply; an empty reply no longer blacklists the peer (#1470).
  Snap-serving peers are kept through a local stall (#1479).

### Fixed
- **Engine API blobs bundle dropped EIP-7594 sidecars.** The payload builder read only the version-0 blob sidecar
  wrapper; a pooled blob transaction carrying a version-1 (cell proof) wrapper, from a peer or from
  `eth_sendRawTransaction`, went into the payload without its blobs in `blobsBundle`, so the bundle no longer matched
  the payload's versioned hashes and a consensus client would reject it. Both wrappers are now read.
- Sepolia's EIP-6110 deposits are read from the chain's own deposit contract; it was wrong for Sepolia since
  Prague (#1416).
- A genesis that activates Amsterdam builds the 23-field header, and hive's genesis `slotNumber` is passed
  through.
- A successful precompile CALL keeps the caller's transient storage (Cancun and later); P256VERIFY returns empty
  output on failure and takes exactly 160 bytes (Osaka); EIP-7934's block size cap is checked on
  `engine_newPayload` from Osaka (#1439).
- A transaction whose gas is below its EIP-7623 floor is invalid from Prague (#1438).
- Post-merge regular sync imports the head's extension after a rewind, and fork recovery no longer swallows the
  import's `ImportDone` and wedges regular sync (every chain, ETC included); deferred batches are dropped when
  the failed import already rewound the fetcher (#1432).
- The Platåberget configs no longer set the removed `do-fast-sync` key.
- **A failed CREATE / CREATE2 keeps its memory expansion** (devnet-8 block 319453 state root, #1461).
- **Import atomicity and reference counts (basic pruning, the default; every chain).** Each block's state, block,
  receipts and best-block pointer are committed in one batch, a failed block leaves nothing behind, and the
  validated prefix of a failed batch is adopted rather than re-applied (#1465, which supersedes the closed #1464).
  Block execution and commit are serialised per block hash, so Engine API `newPayload` and the
  regular-sync importer cannot commit one block twice, and the commit is idempotent (#1481). Applying a block
  twice skewed reference counts and could prune a live node 64 blocks later ("Missing account trie node").
- **Reorg reference counts.** Each block records its own reference-count delta, which is undone when the block
  stops being canonical: on a reorg (#1471), on an Engine API forkchoice move, side payload or rejected payload
  (#1473), and on a SYNC-FORK rewind (#1474). Blocks committed before 0.9.0 have no record; undoing them is a
  no-op. The additions to the database are new keys only. Basic pruning no longer prunes past the canonical head
  (#1458).
- A decoded-node cache insert that raced a prune's delete-then-evict is dropped (#1469).
- Engine API: an already-executed side payload answers VALID without re-execution (#1478). A consensus client
  that timed out and re-sent a slow payload previously caused repeated executions.
- **SNAP sync:**
  - parallel storage sub-ranges are applied in order and a restarted storage coordinator recovers quickly
    (#1449); healing continues while the consensus client's head is not advancing (#1450); recovery task files
    stay in the datadir and a phase is not completed without them (#1453)
  - Path-scheme: healing presence is checked by path (#1454); the trie is published to hash-keyed storage so block
    import can read it (#1455); every location that shares a node hash is healed (#1480)
  - healing is not declared clean on a dirty or superseded verification pass (#1472)
  - body and receipt backfill is deferred until state is finalised on ETH-family networks (#1466)
  - an unservable pivot is replaced by a newer one (#1478)
  - the best block's header is no longer rewritten at startup after SNAP (#1457)

### Known issues
- On a deliberately underpowered host (4 cores, 16 GB, DRAM-less SATA SSD with dm-crypt), heavy devnet blocks of
  about 190M gas import in about 8-10 s warm, longer than the roughly 6.5 s slot. A consensus client can briefly
  mark the execution client offline.
- Memory: an 8 GB heap with large explicit `state-read-caches` sizes reached 11.5 GB resident on that host. Keep
  explicit cache sizes at 35% of the heap or less and leave room for RocksDB and the page cache.
- SNAP healing of a large or high-churn state can fall back to the lazy state fetch handoff instead of converging.
  Follow-up work is tracked.
- Sparse blobpool, `engine_getBlobsV3` / `V4` and negotiating a snap version are not implemented (#1431); none is
  needed to follow the chain.

## [0.8.12] - 2026-09-28

### Fixed
- SNAP sync finishes after state healing re-pegs its pivot (#1448)

## [0.8.11] - 2026-09-28

### Fixed
- A handshaked peer no longer reconnects itself when its connection drops (#1447)

## [0.8.10] - 2026-09-28

### Fixed
- `eth_simulateV1`'s per-call overrides are isolated from block import and other RPC calls (#1446)

## [0.8.9] - 2026-09-27

### Changed
- **Olympia is deferred on every chain** until a confirmed test block number exists. Gorgoroth
  (`gorgoroth-chain.conf`, was 15,800,850) and the private-network template
  (`enterprise-template.conf`, was 0) now set `olympia-block-number` to the unscheduled sentinel
  `1000000000000000000`, as ETC mainnet and Mordor already do. Everything keyed to that block stays
  off with it, including the EIP-1559 base fee and ECIP-1111 treasury credit, the Olympia opcodes and
  precompiles, EIP-7702, the Olympia gas-limit rules and the MESS reactivation window. ETC mainnet,
  Mordor and the ETH-family chains are unchanged (on ETH-family configs `olympia-block-number` is the
  London block).
  - **Gorgoroth operators:** move every fukuii node to this build at the same time. A node left on
    an older build activates Olympia at 15,800,850 and splits from the rest there. If your chain
    already ran past 15,800,850 on an Olympia-active build, reset the datadir: this build rejects
    those blocks, because their headers carry a base fee. The Gorgoroth fork ID no longer announces
    15,800,850 as the next fork.
  - **Private networks from the template:** new copies leave Olympia unscheduled, so genesis has no
    base fee field. A network already running from an earlier copy keeps what that copy sets;
    changing `olympia-block-number` on a live chain is a hard fork.

## [0.8.8] - 2026-09-27

### Removed
- **Fast sync.** It could not finish on current networks (eth/67 and later peers do not serve
  `GetNodeData`), no other client supports it, and SNAP sync replaces it. A node now picks SNAP sync
  when `fukuii.sync.do-snap-sync` is true and regular sync otherwise. When SNAP cannot proceed (no
  snap-capable peers, repeated critical failures, no peers at all), it goes dormant and retries on a
  fresh pivot after a back-off of 3 minutes doubling to 20. Before, it handed the node to fast sync.
  - JSON-RPC: `fukuii_resetFastSync` and `fukuii_restartFastSync` (and `fukuii-cli reset-fast-sync`).
  - Metrics (exported as `app_fastsync_*`): the gauges `fastsync.block.pivotBlock.number.gauge`,
    `fastsync.block.bestFullBlock.number.gauge`, `fastsync.block.bestHeader.number.gauge`,
    `fastsync.state.totalNodes.gauge`, `fastsync.state.downloadedNodes.gauge` and
    `fastsync.totaltime.minutes.gauge`, and the timers `fastsync.block.downloadBlockHeaders.timer`,
    `fastsync.block.downloadBlockBodies.timer`, `fastsync.block.downloadBlockReceipts.timer` and
    `fastsync.state.downloadState.timer`. Dashboard panels built on them go empty.
    `app_network_peers_blacklisted_fastSyncGroup_counter_total` stays: the SNAP chain downloader and
    the branch resolver still count there.
  - Configuration keys under `fukuii.sync`, now ignored with a startup warning (not an error):
    `do-fast-sync`, `fast-sync-restart-cooloff`, `max-snap-fast-cycle-transitions`,
    `start-retry-interval`, `sync-switch-delay`, `critical-blacklist-duration`,
    `persist-state-snapshot-interval`, `max-concurrent-requests`, `nodes-per-request`,
    `min-peers-to-choose-pivot-block`, `peers-to-choose-pivot-block-margin`, `pivot-block-offset`
    (the top-level one; `snap-sync.pivot-block-offset` is unaffected),
    `pivot-block-max-total-selection-attempts`, `pivot-block-reschedule-interval`,
    `max-pivot-block-age`, `max-pivot-block-failures-count`, `max-target-difference`,
    `maximum-target-update-failures`, `fastsync-block-chain-only-peers-pool`, `fastsync-throttle`,
    `fast-sync-block-validation-k`, `fast-sync-block-validation-n`, `fast-sync-block-validation-x`,
    `fast-sync-max-batch-retries`, `state-sync-bloom-filter-size`, `state-sync-persist-batch-size`.
  - The `-Dfukuii.reset-fast-sync-done` system property (now only logs a warning).
  - **Upgrading:** nothing has to be done first.
    - A node stopped part-way through fast sync starts SNAP sync, with the pivot kept above the
      block fast sync had reached (its best block has no state behind it). The floor is stored as
      the `SnapSyncMinPivotBlock` app-state key, so it survives restarts, and is cleared when SNAP
      completes. With `do-snap-sync = false` the node starts regular sync and logs an error:
      regular sync fetches the missing state node by node and, if peers cannot serve it, re-syncs
      with SNAP from a newer pivot, even with `do-snap-sync` off.
    - A node where fast sync finished earlier continues with regular sync, even with
      `do-snap-sync` on, unless SNAP has progress there (its saved pivot is the node's best block,
      or its accounts are complete): then SNAP resumes. Regular sync does not need fast sync's trie
      to be complete: it fetches missing state from peers node by node and, if that fails,
      re-syncs with SNAP from a newer pivot.
    - If your configuration set `do-fast-sync = false` to sync from genesis (an archive node, for
      example), set `do-snap-sync = false`; otherwise the node uses SNAP sync.
    - Fast sync's progress record (column family `f`, key `fast-sync-state`) is deleted at the
      first start, once the node has checked whether fast sync left it stranded. The
      `FastSyncCooldownUntilMillis` and `SnapFastCycleCount` app-state keys stay on disk, unread.
      `FastSyncDone` is still read: with SNAP on, it sends a node where SNAP has no progress to
      regular sync.

## [0.8.7] - 2026-09-27

### Fixed
- RLP decoding caps nesting depth (#1442)

## [0.8.6] - 2026-09-27

### Fixed
- MODEXP reads a truncated length word right-padded, as EIP-198 and other clients do (consensus fix, #1441)

## [0.8.5] - 2026-09-26

### Changed
- Olympia-era gas now follows EIP-2537 (G1/G2 MSM discount tables) and EIP-7702 (authorization
  refunds, authority warming, delegation access cost). No ETC-family chain activates Olympia (see 0.8.9), so ETC mainnet, Mordor and Gorgoroth are unaffected.
- Hive compliance work (#1407, #1408): Engine API payload building and error codes, eth/69+ receipts, opt-in eth/70-72 and snap/2, and the first Amsterdam rules (EIP-2780, 8037, 8038, 7778, 7954, 7708).

## [0.8.4] - 2026-09-23

### Added
- Configurable external IP detection strategy via `network.server-address.external-ip-detection`
  (`none` | `upnp` | `full`, default `upnp`); every detected candidate is now validated as a public
  IPv4 address before being advertised to peers

## [0.8.2 and earlier] - not previously versioned

### Added
- Production release checklist in ETC-HANDOFF.md
- Shared test helper library for Gorgoroth test scripts (`ops/gorgoroth/test-scripts/lib/test-helpers.sh`)
- Static nodes configuration support via `static-nodes.json` file in datadir
  - Nodes can now load peer configuration from `<datadir>/static-nodes.json`
  - **Public mode**: Static nodes are merged with bootstrap nodes from chain config for better sync experience
  - **Enterprise mode**: Uses ONLY static nodes, ignores bootstrap nodes to prevent unintentional public network connections
  - Enables dynamic peer management for private/test networks without config file changes
  - Fully integrated with existing peer discovery system
  - Controlled via `public` and `enterprise` command-line modifiers
- Release automation with one-click releases
- Automated CHANGELOG generation from commit history
- SBOM (Software Bill of Materials) generation in CycloneDX format
- Assembly JAR attachment to GitHub releases
- Release Drafter for auto-generated release notes
- EIP-3651 implementation: Warm COINBASE address at transaction start (see VM-003)
  - Added `eip3651Enabled` configuration flag to `EvmConfig`
  - Added helper method to check EIP-3651 activation status
  - COINBASE address is now marked as warm when EIP-3651 is enabled, reducing gas costs by 2500 for first access
  - Comprehensive test suite with 11 tests covering gas cost changes and edge cases


### Changed
- Renamed GHCR image path from `chordodes_fukuii` to `fukuii` across all CI/CD, docs, and scripts
- Modernized CI apt-key pattern to use `signed-by` keyring (replaces deprecated `apt-key add`)
- Renamed `logback-node2-sync-trace.xml` → `logback-sync-trace.xml` (not node-specific)
- Deduplicated `CONTRIBUTING.md` — root and `docs/` copies now redirect to canonical `docs/development/contributing.md`
- Fixed confused rebrand text in contributing docs (was "Fukuii to Fukuii", now "Mantis to Fukuii")
- Enhanced release workflow to include all artifacts
- Updated documentation for release process
- Modified `ProgramState` initialization to conditionally include COINBASE in warm addresses set

### Fixed
- **Critical**: Fixed ETH68 peer connection failures due to incorrect message decoder order
  - Network protocol messages (Hello, Disconnect, Ping, Pong) are now decoded before capability-specific messages
  - Resolves issue where peers would disconnect immediately after handshake with "Cannot decode Disconnect" error
  - Fixes "Unknown eth/68 message type: 1" debug messages
  - Node can now maintain stable peer connections and sync properly with ETH68-capable peers
- **Critical**: Fixed SNAP sync OOM from unbounded in-memory trie and contract account buffers (Bug 18)
  - `DeferredWriteMptStorage` now flushes periodically (~32K accounts per batch) instead of once at finalization
  - `contractAccounts`/`contractStorageAccounts` ArrayBuffers replaced with file-backed storage (~45M entries on ETC)
  - Added disk persistence for account range progress with crash recovery
- **Critical**: Fixed RPC starvation under SNAP sync — all RPCs timeout when sync workers saturate default dispatcher (Bug 6)
- **High**: Fixed SNAP fallback resilience — two separate code paths to fast sync fallback (Bug 2)
  - Consecutive pivot refresh counter no longer resets in `restartSnapSync()`
  - Bootstrap retry now uses exponential backoff (2s → 60s cap) instead of fixed 2s
  - 5-minute timeout triggers fallback to fast sync when no peers are found
  - Fixed stale retry code in `Some(header)` match case that prevented SNAP start from local pivot
- **High**: Fixed block body download stall — peer switching + exponential backoff for body fetches (Bug 8)
- **Medium**: Fixed FastSync best block hash tracking during sync (Bug 3)
- **Medium**: Fixed `net_listPeers` timeout with 30+ peers — added peer status cache (Bug 9)
- **Medium**: Fixed `MissingNodeException` in `eth_call`/`eth_estimateGas`/`eth_getCode` during sync (Bug 7)
- **Medium**: Fixed `personal_sendTransaction` `MissingNodeException` during sync (Bug 10)
- **Low**: Fixed JSON-RPC null id coercion and malformed request error format (Bug 4)
- Fixed actor name collision on sync restart — generation counter for unique names (Bug 5)
- Added `ResilientRollingFileAppender` to recreate log file if deleted while running (Bug 19)
- SNAP capability check — verify snap/1 peers before starting account sync (Bug 11)
- SNAP stagnation watchdog — track `accountsDownloaded` as liveness signal (Bug 12)
- SNAP partial range resume — preserve progress across pivot refreshes (Bug 13)
- SNAP dynamic concurrency — cap workers to snap peer count (Bug 14)
- SNAP in-place pivot refresh — update state root without stop/restart (Bug 15)
- SNAP stale peer accumulation — deduplicate peers by remote address on reconnection (Bug 16)
- SNAP false stateless marking after pivot refresh — added stale-root guard (Bug 17)

## [0.1.0] - Initial Version

### Added
- Initial Fukuii Ethereum Client codebase (forked from Mantis)
- Rebranded from Fukuii to Fukuii throughout codebase
- Updated package names from io.iohk to com.chipprbots
- GitHub Actions CI/CD pipeline
- Docker container support with signed images
- Comprehensive documentation

---

**Note:** This CHANGELOG is maintained by hand and cut when a release is prepared. See the
[Releases page](https://github.com/chippr-robotics/fukuii/releases) for published artifacts.
