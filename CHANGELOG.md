# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
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

### Added
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
  `write protection`, and so on. A stack underflow still lacks go-ethereum's `(0 <=> 2)` counts and a precompile failure
  its reason; both stay under Known issues.
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
  unbounded block-gap loop that stalled the node is fixed (see Fixed). A comparison of 815 cases with geth v1.17.9 on
  Ephemery found these differences:
  - malformed override values (unprefixed or short hex, overrides sent as an array) are accepted, where geth returns
    -32602;
  - an override at a precompile's address does not disable the precompile;
  - `movePrecompileToAddress` refuses 0x0a (KZG), and two moves to the same address are both dropped;
  - `maxUsedGas` reports gas after refunds;
  - a request system contract whose code is overridden away is skipped instead of failing;
  - VM and precompile error texts differ (`precompiledcontractfail` where geth says, for example, `invalid input
    length`).

  On ETC, simulated blocks pay ETH's 5/3/2 PoW reward, not ECIP-1017. The differences listed do not touch block import
  or mining.
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
