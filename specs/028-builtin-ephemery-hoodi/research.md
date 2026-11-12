# Research: Ephemery and Hoodi as Built-in Networks (spec 028)

**Date**: 2026-11-12. **Inputs**:
- three read-only research passes over the code and the published sources;
- measurements taken on 2026-11-12: the ephemery-165 tarball's tar headers, the release publication times, and the
  GitHub redirect hosts;
- a review of the first design, which settled R5, R6, R9 and R12 below.

Line references are to staging `a3044e85d`. `F` is `scripts/ephemery/fetch-genesis.sh`.

Each topic gives the decision, the rationale, and the alternatives that were rejected.

---

## R1. One implementation of the Ephemery release rules

**Decision.** Port the rules of `F` to Scala, in a new package `com.chipprbots.ethereum.ephemery` that has no actors
and no global `Config`. The CLI subcommand `fukuii cli ephemery-fetch` and the node's startup resolver both call it.
`fetch-genesis.sh` becomes a wrapper in two steps:
- **Slice 2.** The script keeps its download and extraction and delegates every rule, re-encoding and rendering step
  to `fukuii cli ephemery-fetch --from-dir <extracted> --out <out>`. From this slice on, only one rule engine exists.
- **Slice 4b.** The CLI gains the download as well, and the script shrinks to `exec fukuii cli ephemery-fetch "$@"`.
  Its flags `--release`, `--out` and `--from-dir` keep their meaning (FR-015).

**Rationale.**
- The node must apply the rules at runtime (FR-004 to FR-008).
- Shelling out to bash, jq and curl from the node would make three host tools a runtime dependency, and would run a
  script at startup, against FR-012.
- Two copies of the rules would drift. Today nothing pins the refusal paths: only valid releases have fixtures.

**Alternatives rejected.**
- *Keep bash as the engine, and have the node exec it.* Rejected for the host dependencies and FR-012.
- *Keep both, with a parity test.* Rejected: two engines by design, which FR-006 forbids.

**Port order (golden first).**
1. A byte-identical port, proved by golden tests that render the 164 and 165 raw release inputs and compare them with
   the committed fixtures `src/test/resources/ephemery/ephemery-16x/{ephemery-chain.conf,ephemery-genesis.json}`.
   These tests replace `scripts/ephemery/check-fixture.sh`.
2. In a separate commit, add the spec 024 keys (R14) to the renderer and regenerate the fixtures. A change of rendered
   output is then always a reviewed fixture diff.

**Raw inputs to commit.** The current fixtures are generated outputs. A test cannot re-derive them without the inputs
from inside the tarball. Add `src/test/resources/ephemery/releases/ephemery-{164,165}/` holding:
- genesis.json, about 47 KB;
- retention.vars;
- deposit_contract_block_hash.txt;
- enodes.txt;
- boot_enr.txt.

Do not commit parsedBeaconState.json (18.9 MB) or genesis.ssz (7.3 MB).

## R2. The rules, as ported

Every rule of `F` carries over with its message text. Each refusal becomes a case of a `ReleaseRefusal` ADT (see
data-model.md), and each gets a unit test built from a copy of the 165 genesis with one field mutated. No such tests
exist today.

| # | Rule | `F` |
|---|------|-----|
| R1 | `config.chainId` present and an integer | :121-122 |
| R2 | retention.vars `CHAIN_ID` equals `chainId`. Iteration is `ITERATION_NUMBER`, else `chainId - 39438000` | :123-126 |
| R3 | config keys within the allow-lists (block forks, time forks shanghai..amsterdam and bpo1..bpo2, other) | :129-138 |
| R4 | every block fork is 0 | :140-143 |
| R5 | `terminalTotalDifficulty` is "0" | :144-145 |
| R6 | time keys are integer or null; shanghai, cancun and prague are required | :147-157 |
| R7 | `depositContractAddress` matches `^0x[0-9a-fA-F]{40}$` | :159-160 |
| R8 | blobSchedule forks known; target, max and fraction integers; copied verbatim as `blob-schedule` | :165-174, :306-307 |
| R9 | top-level keys within the allow-list; parentHash, number and gasUsed zero; mixHash/mixhash agree | :188-199 |
| R10 | re-encode timestamp, gasLimit, difficulty, baseFeePerGas, blobGasUsed and excessBlobGas to 0x-hex; reject decimals of 10^18 or more | :201-218 |
| R11 | retention `GENESIS_TIMESTAMP` equals the genesis timestamp; chainspec.json timestamp agrees if the file is present; alloc balance and nonce are JSON strings | :221-231 |

R8 has a second half: the semantic blob-schedule check stays in `BlobSchedule.scala`. The resolver runs it when it
parses the rendered config, so a release that would fail at node startup fails at fetch instead.

**New rules** (needed by FR-007 and FR-013):

| # | Rule | Why |
|---|------|-----|
| N1 | `deposit_contract_block_hash.txt` is present and holds `0x` plus 64 hex digits | Today a missing file silently gives an "unknown" hash (`F`:233-234) |
| N2 | `ITERATION_NUMBER == chainId - 39438000` | Not cross-checked today. Holds for 164 (39438164) and 165 (39438165) |
| N3 | computed genesis hash equals the N1 hash (FR-007) | R3 |
| N4 | `resetTime = GENESIS_TIMESTAMP + GENESIS_RESET_INTERVAL`, default interval 2,419,200 s | 165: 1792695600 + 2419200 = 1795114800 (2026-11-19 19:00 UTC) |

**Porting details.**
- Read retention.vars with a key=value regex (`^export ([A-Z_]+)="?([0-9A-Za-z_-]*)"?$`), never by sourcing it.
- Parse JSON numbers as BigInt or BigDecimal. jq loses precision above 2^53, so the port must be at least as strict
  as jq, never looser.

## R3. Computing the genesis hash without the node (FR-007)

**Decision.** Extract a pure function from `GenesisDataLoader`:
`GenesisDataLoader.genesisHeader(genesisData)(using BlockchainConfig): BlockHeader`.
- It computes the state root over an in-memory MPT storage (`EphemDataSource`), then runs today's `prepareHeader`,
  which is protected at `:250`.
- The existing `loadGenesisData` calls the same function, so import and check share one code path, per the
  proposer-import-parity protocol.
- The `BlockchainConfig` comes from `BlockchainConfig.fromRawConfig` over the rendered chain config, the same parse
  `BlockchainsConfig` applies to a `custom-chains-dir` file.

**Verified 2026-11-12.** None of GenesisDataLoader, BlockchainConfig, genesis.scala or BlobSchedule.scala imports the
global `utils.Config`. A slice 2 task greps the transitive calls (MPT storage, `EphemDataSource`) for any `Config.`
read. The resolver runs before `Config` exists (R5): a `Config` read on this path would freeze the pre-selection
datadir for the whole process.

**The override hook stays.** `prepareHeader` is `protected` and overridden in test code: `EestBlockchainReplay.scala:279`
substitutes a known header. So the pure function goes in the companion, and the instance method keeps delegating to
it. `loadGenesisData` keeps calling the overridable instance method; only the resolver calls the companion directly.

**Review.** The extraction is a refactor of a path shared with ETC. beacon reviews it for the ETH genesis shapes:
Cancun for Hoodi, Amsterdam with 23 fields for Ephemery 165. forge confirms the ETC genesis tests are unchanged
(FR-021).

**Alternative rejected.** Compute the hash inside the node, after storage opens, using the existing guard
(`GenesisDataLoader.scala:139-149`). That is too late: the datadir is chosen from the iteration, so the check has to
happen before it.

## R4. Where the bundled iteration lives (FR-001 to FR-003)

**Decision.** Put the bundle in `src/main/resources/ephemery/bundled/ephemery-165/`:
- `ephemery-chain.conf`;
- `ephemery-genesis.json`;
- `release.json` (contracts/release-json.md).

It is about 100 KB, and only one iteration is bundled at a time. It is not listed in `conf/base/blockchains.conf`.
`scripts/ephemery/update-bundled.sh ephemery-N` regenerates it (FR-003). The script calls
`fukuii cli ephemery-fetch --release ephemery-N --out <tmp>` and copies the result into place. It also writes the raw
inputs to `src/test/resources/ephemery/releases/ephemery-N/`, so the golden test always covers the bundled iteration.

**Rationale.**
- Ephemery's chain id, genesis and forks change every 28 days. A permanent `ephemery { include ... }` entry among the
  built-ins would be stale data posing as a static chain.
- Spec 024 T036 stops `custom-chains-dir` entries from overriding built-ins. Ephemery reaches the node through
  `custom-chains-dir` (R5), so a built-in entry of the same name would then win over the refreshed iteration.

**Alternative rejected.** `conf/base/chains/ephemery-chain.conf` plus a `blockchains.conf` entry. It is the simplest
and works with today's loader, but fails for the two reasons above.

**Not bundled: the consensus-layer files.**
- `testnet-all.tar.gz` is 3.7 MB, 26.3 MB unpacked. `genesis.ssz` alone, which a CL needs, is 7.3 MB.
- The files go stale within 28 days and grow with the validator count.
- For the bundled iteration, the CL directory is therefore fetched on request (R10), which amends FR-017.

## R5. Selection: the startup resolver

**Decision.** Add `EphemeryResolver`. It runs in `App.main` right after `setNetworkConfig("ephemery")` and before
anything touches `utils.Config`. It does nothing for any other network. The steps:

1. **Read.** Read the raw configuration with `ConfigFactory.load()`, not the `Config` object. It reads
   `fukuii.ephemery.*`, `fukuii.datadir` (this becomes the Ephemery root, R6),
   `fukuii.blockchains.custom-chains-dir` and `logging.logs-dir`.
2. **Operator-prepared directory (FR-015).** If `custom-chains-dir` is set and contains `ephemery-chain.conf`, the
   resolver stands down, and the node behaves exactly as it does today:
   - no fetch;
   - no per-iteration datadir;
   - no exit at the reset, because a restart would land on the same pinned directory.

   It logs one line naming the directory. It also logs a WARN if the directory's genesis timestamp plus 2,419,200 s
   (or `RESET_TIME` from an adjacent `release.env`) has passed.
3. **Managed iteration.** Otherwise it picks the newest valid iteration with `genesis ≤ now < reset`:
   - candidates are the cached entries under `<root>/releases/` (each re-validated on load; about 50 KB, cheap) and
     the bundled iteration;
   - if none is current and fetching is enabled, it fetches (R7);
   - otherwise it refuses (R12).
4. **Hand over.** It sets these system properties, then calls `ConfigFactory.invalidateCaches()`:
   - `fukuii.blockchains.custom-chains-dir` to `<root>/releases/ephemery-N/chains`;
   - `fukuii.datadir` to `<root>/ephemery-N`;
   - `logging.logs-dir` to its value before selection, so the logback appender and `truncateLogs()`
     (`Fukuii.scala:145`) keep agreeing;
   - `fukuii.ephemery.active.{iteration,reset-time,genesis-hash}`, so `Config` sees them and the watchdog (R11), logs
     and MCP can read them.
5. **Report (US1-3).** It logs: iteration, chain id, genesis hash, reset time in UTC, local clock, source (bundled,
   cached or fetched), datadir, and the CL directory path or how to get it.

**Test seam.**
- `App.main(args)` becomes `App.run(args, StartupDeps.production)`. `StartupDeps` carries the `Clock`, the
  `ReleaseTransport` (R8) and the exit function.
- The resolver and the watchdog take them as constructor arguments.
- Tests build their own `StartupDeps` (R16). No test-only configuration key exists.

**Shipped default removed.** `conf/ephemery.conf` stops setting
`custom-chains-dir = ~/.fukuii/ephemery-network/current/chains`. While that default exists, stale script output at
the default path would outrank the bundle forever, which is the B-225 trap.
- Operators who launch with `FUKUII_CONF_DIR` are unaffected: the generated conf sets the directory explicitly (`F`:375-387).
- An operator who ran the script with its default `--out` and launched without `FUKUII_CONF_DIR` now gets the managed
  iteration, which is the same one, in a new datadir. Their old datadir is untouched.
- This goes in the CHANGELOG as a behaviour change.
- `EphemeryGenesisSpec:83-91` pins the old default and changes with it.

**Alternatives rejected.**
- *Stand down whenever `findFilesystemConfig` finds an `ephemery.conf`, or a `-D` sets the directory.* Rejected: a
  conf directory, the operator's or the distribution's (`-Dconfig.file=${app_home}/../conf/app.conf`), can hold an
  `ephemery.conf` that only tweaks ports and prepares no iteration. Whether the directory contains
  `ephemery-chain.conf` is the property that matters, and it needs no knowledge of file layout.
- *Supply the `BlockchainConfig` to the node directly instead of through system properties.* Rejected: `Config` is a
  process-wide object read in about 25 places (the FR-016 research). System properties are how `App` already
  configures it (`applyModifiers`).

## R6. Layout of the Ephemery root

**Decision.** `<root>` is `fukuii.datadir` as configured before selection. The default is `~/.fukuii/ephemery`, from
`conf/base/fukuii.conf:6`.

```text
<root>/
├── releases/ephemery-N/      one validated release, immutable once in place
│   ├── chains/ephemery-chain.conf, chains/ephemery-genesis.json
│   ├── release.json          (contracts/release-json.md)
│   └── testnet/              CL network dir (R10), present when the release was fetched or on request
├── ephemery-N/               datadir of iteration N (rocksdb/, node.key, keystore/, tmp/)
├── logs/                     logs, pinned (R5)
├── current -> releases/ephemery-N   updated at selection; for `--testnet-dir <root>/current/testnet`
└── .stage-<random>/          work dirs; stale ones are swept at start
```

**Rationale.**
- **A release entry is created with one rename.** The resolver builds it in `.stage-*` and renames it to
  `releases/ephemery-N` only if that entry is absent, so a failure leaves nothing partial behind (FR-010). A
  `testnet/` added later is staged and renamed into the existing entry in the same way.
- **The datadir is separate from the release entry.** Keeping it out of `releases/` keeps the entry immutable. The
  script's layout puts `data/` inside the entry, which makes the entry impossible to replace atomically.
- **An explicit datadir works too.** Edge case "explicit datadir": the per-iteration directories land under whatever
  `fukuii.datadir` the operator set.
- **Old datadirs stay.** A pre-028 run may have used `~/.fukuii/ephemery` itself as a datadir, with `rocksdb/` at the
  root. The resolver only adds new names, never touches it, and logs it, together with earlier iterations' datadirs
  and their sizes. The node never deletes any of them (FR-009).
- **Node key per iteration.** It follows the datadir, as the script's per-iteration datadir does today. An operator
  who wants a stable enode sets `fukuii.node-key-file`.

**A republished iteration** (N again, different hash):
- It can only be noticed when the node fetches, which it never does while N is current (SC-003).
- If a fetch of N finds a genesis hash other than the cached entry's, the resolver refuses with exit 78. The message
  names both hashes and the two directories to move aside.
- The spec's "retracted release" edge case is amended to say this. While N is current, a republished N shows up as
  zero peers (fork-id mismatch), which the TUI and logs already report.

**Alternative rejected.** Reuse the script layout `<out>/ephemery-N/{chains,data,testnet,release.env}` for the node
too. It cannot make an entry atomic once `data/` exists. The CLI keeps writing that layout when given `--out`, so
FR-015 is unaffected.

## R7. Which release to fetch, and when (FR-004, FR-005)

**Measured 2026-11-12.** Each release's `published_at`, compared with its genesis, which is 19:00 UTC on each 28-day
boundary:

Only 165's genesis is measured (from its retention.vars). The other genesis times are derived by subtracting
28 days; the publication times are all measured.

| Release | Genesis (UTC, derived) | Published (UTC, measured) | Lag |
|---------|---------------|-----------------|-----|
| ephemery-165 | 2026-10-22 19:00 | 2026-10-22 17:37 | −1 h 22 m |
| ephemery-164 | 2026-09-24 19:00 | 2026-09-24 19:59 | +59 m |
| ephemery-163 | 2026-08-27 19:00 | 2026-08-27 22:42 | +3 h 42 m |
| ephemery-162 | 2026-07-30 19:00 | 2026-07-30 17:46 | −1 h 14 m |
| ephemery-161 | 2026-07-02 19:00 | 2026-07-02 17:15 | −1 h 45 m |
| ephemery-160 | 2026-06-04 19:00 | 2026-06-04 17:20 | −1 h 40 m |
| ephemery-159 | 2026-05-07 19:00 | 2026-05-07 18:54 | −6 m |
| ephemery-158 | 2026-04-09 19:00 | 2026-04-09 17:41 | −1 h 19 m |

Two consequences:
- **"latest" is often not live yet.** For one to two hours before a reset, the API's `releases/latest` names an
  iteration whose genesis is still in the future.
- **The next release can be late.** In the worst case it came 3 h 42 m after the reset.

**Decision.**
1. **No network contact while current.** The resolver fetches only when no candidate satisfies
   `genesis ≤ now < reset` (FR-005, SC-003).
2. **Compute the target.** It derives the iteration K whose window contains now, from the newest candidate's genesis
   and interval: `K = N + floor((now - genesis_N) / interval)`.
3. **Fetch by the asset URL, not the API.** It fetches
   `https://github.com/ephemery-testnet/ephemery-genesis/releases/download/ephemery-K/testnet-all.tar.gz`. A 404
   means K is not published yet. Asset downloads do not count against the REST API's limit of 60 per hour.
4. **Check the window.** After validation, K's own genesis and reset must contain now.
   - If they do not, because the interval changed, call the API's `releases/latest` once and accept that release if
     its window contains now.
   - Otherwise refuse with exit 69 and both windows.
5. **Several iterations behind** (edge case): K skips the iterations in between directly.

**Alternatives rejected.**
- *Always use `releases/latest`.* It returns a not-yet-live iteration in the pre-reset hours above, and spends API
  quota.
- *Step through tags N+1, N+2, and so on.* Too many requests when far behind.

## R8. The HTTPS client (FR-011, FR-012)

**Decision.** One small `ReleaseTransport` trait. `JdkHttpsTransport` implements it on `java.net.http.HttpClient`, as
`CheckpointDownloader.scala:194-199` already does. The rules:

- **Start URL.** It must be `https:`. The only non-network source is a local directory (FR-014, R13).
- **Redirects.** The client uses `Redirect.NEVER` and follows up to 5 hops itself. Every hop must be `https:`.
  - For the default source, every hop's host must be on the allow-list `github.com`, `api.github.com` or
    `*.githubusercontent.com`. Measured 2026-11-12: the asset 302s to `release-assets.githubusercontent.com`, not
    `objects.githubusercontent.com`.
  - For an operator-configured https mirror, any https hop is accepted.
  - The redirect URL carries a signed query, so hops are logged as scheme and host only. No full URL is logged.
- **Time.** A 10 s connect timeout. One 60 s budget per startup covers every attempt (SC-002). Each body read has its
  own deadline from the remaining budget, because `HttpRequest.timeout` covers only the response headers.
- **Retries.** Up to 3 attempts, with 2 s and 4 s backoff, for connect errors, timeouts and 5xx, all inside the
  budget. A 404 is retried only while budget remains, since it is the not-yet-published case.
- **Size caps.** A compressed body over 32 MiB is refused while streaming. The real body is 3.7 MB.
- **Proxy.**
  - The JDK's default `ProxySelector` already honours `-Dhttps.proxyHost`, `-Dhttps.proxyPort` and
    `-Dhttp.nonProxyHosts`.
  - When those are absent, `HTTPS_PROXY` (or `https_proxy`) and `NO_PROXY` from the environment are mapped to a
    `ProxySelector`. Nothing in the repo reads them today.
  - Proxy credentials in the URL are refused (logged without the credential), not used.
- **Headers.** `User-Agent: fukuii/<version>`, with no host details. `Accept: application/vnd.github+json` on the one
  API call.

**Test seam (constructor injection, never configuration).**
- The resolver and the CLI take a `ReleaseTransport`. Refresh logic is tested with an in-memory fake: a fake release
  server that maps URLs to bytes, 404s and delays.
- `JdkHttpsTransport` is tested against a local JDK `HttpsServer`. It has a self-signed certificate and a
  constructor-injected `SSLContext` and allow-list, which exercise the redirect, host, cap and deadline rules.
- No configuration key disables HTTPS or the allow-list.

**Alternatives rejected.**
- *pekko-http client.* Server-oriented in this codebase, and heavier.
- *Apache httpclient5* (on the classpath for UPnP). It would add a second pattern.
- *`Redirect.NORMAL`.* It refuses only https→http, and cannot check each hop's host.

## R9. Unpacking the tarball safely (FR-012, FR-017)

The reader is on the refresh path itself, not only FR-017: retention.vars, the hash file and enodes.txt exist only
inside `testnet-all.tar.gz`.

**Measured 2026-11-12** (ephemery-165):
- GNU tar format: magic `ustar  \0`.
- 29 entries: typeflag `0` (regular file) for 28 of them, and `5` (directory) for `bootnode-keys/`.
- Modes 0644 and 0755; no long-name (`L`) or pax (`x`, `g`) headers; names relative, without `./`.
- 3,714,100 B compressed, 26,357,760 B uncompressed. Largest member: parsedBeaconState.json, 18.9 MB.

**Decision.** A small strict reader, about 150 lines, written in-house. All rules are enforced by our code, in streaming:

1. **Header.** Magic is POSIX `ustar\0` with version `00`, or GNU `ustar  \0`. The header checksum must verify.
2. **Typeflags.**
   - Accepted: `0`, NUL (regular file), `5` (directory), and GNU `L` (long name, at most 1 KiB, applied to the next
     entry).
   - Everything else is refused with the entry named: symlink, hardlink, device, fifo, sparse, pax `x` and `g`,
     GNU `K`.
3. **Names.** Refused if:
   - empty;
   - absolute, or starting with a drive letter;
   - any segment is `..`;
   - containing a backslash or NUL;
   - longer than 255 bytes, or deeper than 8 levels;
   - a duplicate.

   The resolved path must stay inside a freshly created empty stage directory.
4. **Files.** Created with `CREATE_NEW` and `NOFOLLOW_LINKS`, mode 0644 for files and 0755 for directories, whatever
   the archive says. Never an exec bit, setuid or setgid; uid, gid and mtime are ignored.
5. **Limits**, counting bytes actually read rather than the header sizes:
   - at most 256 entries;
   - 64 MiB per entry;
   - 128 MiB total;
   - 32 MiB compressed;
   - an uncompressed-to-compressed ratio of at most 50 (real: 7).

   A truncated archive is refused.
6. **Members.** The EL inputs (R1's list) are read into memory. The CL directory gets every member except
   `parsedBeaconState.json` and `bootnode-keys/` (R10). Nothing is executed or sourced.

**Failure mode and fallback.**
- An archive that breaks a rule is refused with exit 78, "cannot represent the release archive: <entry, rule>; a newer
  fukuii is needed" (FR-013).
- The fallback is FR-014's local source. The operator unpacks the release with system `tar` and points
  `fukuii.ephemery.source` at the directory. The same EL rules apply, and no archive parser is involved.

**Alternative rejected.** Apache commons-compress, Apache-2.0, not on the classpath today.
- It is a new dependency for one archive, with a recent history of tar-parser denial-of-service CVEs (2024:
  CVE-2024-25710, CVE-2024-26308).
- Every rule above would still have to be ours.
- Measured, the archive is the simplest form of tar: two entry types and no extensions.

## R10. The consensus-layer directory (FR-017, amended)

**Decision.**
- **Contents.** `testnet/` holds every archive member except `parsedBeaconState.json` (18.9 MB, not read by
  Lighthouse) and `bootnode-keys/` (encrypted bootnode private keys). That is about 7.4 MB per iteration, almost all
  of it `genesis.ssz`. It is written whenever the node or the CLI fetches a release, and the startup line logs its path.
- **The bundled iteration.** Nothing is fetched while the iteration is current (SC-003), so the startup line prints
  the one command that fetches the CL directory: `fukuii cli ephemery-fetch --release ephemery-N`, with no `--out`
  (contracts/cli.md).
- **The spec change.** FR-017 is amended to say "whenever a release is fetched, and on request for the bundled
  iteration". This is flagged to the user: it changes what US1 delivers on a clean machine, which is a working EL plus
  one command for the CL files.

**Alternative rejected.** Bundle the CL minimum (config.yaml, genesis.ssz, deploy_block.txt and boot_enr.yaml). That
is 7.3 MB of binary state in the jar per iteration, regenerated every 28 days.

## R11. A running node at its reset (FR-016)

**Decision.** `EphemeryResetWatchdog` starts after the node starts, and only for a managed iteration (R5 step 3).
- **Inputs.** `fukuii.ephemery.active.reset-time`, a `Clock`, and a scheduler. The exit is passed in as
  `Int => Unit`, defaulting to `sys.exit`.
- **At the reset time** it logs this WARN:

  > `Ephemery iteration ephemery-N ended at <UTC>; exiting with code 75 so a supervisor restart picks up
  > ephemery-N+1`

  and then calls `exit(75)`. The JVM shutdown hooks do the rest: `NodeBuilder.scala:1276`'s `ShutdownHooks` (a
  graceful node shutdown that closes RocksDB) and the TUI restore (`Fukuii.scala:122`).
- **Opt-out.** `fukuii.ephemery.exit-at-reset = false` only logs the WARN and keeps the node running, for runs without
  a supervisor.

**Rationale.** This is the user's decision of 2026-11-12. The in-place switch was rejected: it needed a sweep of about
25 global `Config` reads and a split of the node object. The node already exits cleanly through its shutdown hooks
when the TUI's `q` calls `sys.exit(0)`, so the reset exit adds no new shutdown path.

**Check in slice 3.** Show that `sys.exit` from the watchdog thread completes the graceful shutdown. The forked
integration test (R16) should see exit code 75 and a RocksDB that reopens cleanly.

## R12. Exit codes and the restart loop

**Decision.** Exit codes follow sysexits(3), as `JvmFlagGuard.RefuseExitCode = 78` already does:

| Code | Meaning | Supervisor |
|------|---------|------------|
| 75 `EX_TEMPFAIL` | the running iteration reached its reset (FR-016) | restart, which refreshes |
| 69 `EX_UNAVAILABLE` | at startup, the iteration has ended and the next cannot be fetched now: not yet published, unreachable, or the source fails | restart later |
| 78 `EX_CONFIG` | permanent refusal: cannot represent, hash mismatch, a republished iteration, fetching disabled after the reset, or a bad local source | do not restart (`RestartPreventExitStatus=78`) |

FR-016 asks for a distinct code. 75 is used for nothing else in fukuii; today's codes are 0, 1, 2 and 78. Splitting
69 from 75 tells an operator whether the node stopped *at* the reset or *could not get past* it.

**The restart loop, sized for the runbook.**
- **Supervisor settings.** systemd `Restart=on-failure`, `RestartSec=120`, `RestartPreventExitStatus=78`. Under
  Docker, `--restart on-failure` cannot exclude 78; the runbook says so.
- **Worst observed case.** The release came 3 h 42 m late. Each start takes up to 60 s of refresh plus JVM start, and
  then waits 120 s, so the loop runs about 75 starts.
- **Load per start.** At most 4 HTTPS requests to github.com asset URLs, which are not API-limited.
- **systemd's start limit** (5 starts in 10 s by default) is never reached.
- **Common case.** The release is already out at the reset (6 of 8 measured), so the first restart refreshes.
- **SC-008** asks for the new iteration within 10 minutes of publication. 120 s of wait plus 60 s of refresh meets it.

## R13. Operator control (FR-014)

**Decision.** Two keys, `fukuii.ephemery.refresh` and `fukuii.ephemery.source` (contracts/config.md):
- `refresh = false`: never fetch. Run the newest current cached or bundled iteration, and refuse with exit 78 after
  its reset.
- `source`: a local directory holding an unpacked release (no network), or an https mirror base URL. The default is
  the public ephemery-genesis releases.

The `FUKUII_CONF_DIR` path (R5 step 2) is the third control.

## R14. Spec 024 alignment (FR-022)

**Decision.** The Hoodi chain config, the bundled Ephemery config and every rendered Ephemery config carry, inside the
chain object:
- `consensus = "engine-api"`;
- `genesis-hash = "0x…"`.

Today's loader ignores both. `BlockchainConfig.fromRawConfig` reads only the keys it names
(`BlockchainConfig.scala:248+`). The keys must sit inside the chain object: a sibling key under `fukuii.blockchains`
would be taken for a chain name by `BlockchainsConfig`'s `keys(rawConfig)`.

028 adds no load-time check of `genesis-hash`. That check is spec 024's `ConfigValidator` work, and 028's resolver
checks Ephemery's hash itself (N3). Hoodi's hash is pinned by a test (FR-019).

Ephemery is not a built-in (R4), so spec 024 T036 (custom entries no longer override built-ins) leaves it untouched.

## R15. Hoodi values (FR-018 to FR-020)

**Sources.**
- **[H]** `eth-clients/hoodi`: metadata/genesis.json, config.yaml, enodes.yaml and deposit_contract_block_hash.txt.
- **[G]** go-ethereum: params/config.go, params/bootnodes.go, params/chainspecs/hoodi.json and
  core/forkid/forkid_test.go.

Fetched 2026-11-12. Raw copies are in the session scratch, not committed.

| Value | Hoodi | Agreement |
|-------|-------|-----------|
| chain id = network id | 560048 (`0x88bb0`) | [H] = [G] |
| genesis hash | `0xbbe312868b376a3001692a646dd2d7d1e4406380dfd86b98aa8a34d1557c971b` | [H] = [G] |
| genesis timestamp | 1742212800 (`0x67d80ec0`) | [H] = [G] |
| genesis header | nonce `0x1234`, gasLimit `0x2255100`, difficulty 1, extraData empty, 335 alloc accounts | [H] |
| block forks | all 0; TTD 0 | [H] = [G] |
| Shanghai, Cancun | 0 | [H] = [G] |
| Prague | 1742999832 | [H] = [G] |
| Osaka | 1761677592 | [H] = [G] |
| BPO1 | 1762365720 | [H] = [G] |
| BPO2 | 1762955544 | [H] = [G] |
| Amsterdam | not scheduled | [H] = [G] |
| blob schedule | cancun 3/6/3338477, prague 6/9/5007716, bpo1 10/15/8346193, bpo2 14/21/11684671. Osaka inherits. Byte-identical to fukuii's Sepolia | [H] = [G] |
| deposit contract | `0x00000000219ab540356cBB839Cbe05303d7705Fa` | [H] |
| DNS discovery | `enrtree://AKA3AM6LPBYEUDMVNU3BSVQJ5AD45Y7YPOHJLEF6W26QOE4VTUDPE@all.hoodi.ethdisco.net`, the EF key that already signs Sepolia's tree | [G] |

**Fork ids (EIP-2124 and EIP-6122).** Taken from go-ethereum's `forkid_test.go`, and re-derived 2026-11-12 with
crc32 over the genesis hash and the big-endian timestamps. All match.

| Head | FORK_HASH | FORK_NEXT |
|------|-----------|-----------|
| genesis … 1742999831 | `0xbef71d30` | 1742999832 |
| Prague | `0x0929e24e` | 1761677592 |
| Osaka | `0xe7e0e7ff` | 1762365720 |
| BPO1 | `0x3893353e` | 1762955544 |
| BPO2 | `0x23aa1351` | 0 |

Shanghai and Cancun at timestamp 0 are earlier than the genesis timestamp, so EIP-6122 drops them from the fork list
(`ForkIdGenesisTimestampSpec` is the precedent).

**Genesis encoding.**
- Hoodi writes `timestamp` as the decimal string `"1742212800"`, which `prepareHeader` would read as hex
  (`GenesisDataLoader.scala:254,318`). The result would be a wrong hash, silently. It must be re-encoded to
  `"0x67d80ec0"`.
- Hoodi writes `mixhash`. Rename it to `mixHash`: the value is zero either way, but json4s would not bind the
  lowercase key.
- Run the re-encoding through the slice 2 `GenesisJsonEncoder` if slice 2 has landed. Otherwise use a one-off jq
  conversion that the pinned hash test checks.

**Correction to the first research pass.** It claimed that `GenesisDataLoader.unifiedLayoutRefusal` refuses a
genesis with more than one storage account. It does not. `:399-433` refuses only:
- an address allocated twice;
- storage on a chain with `eth-compatible-storage = false`.

Hoodi has three storage accounts (the deposit contract, 7002 and 7251) and `eth-compatible-storage = true`, so it
loads, as Ephemery's genesis with the same three accounts already does. With more than one storage account,
`countsExact` (`:429`) is false, so the datadir is not stamped as having exact reference counts. That is the existing
behaviour for Ephemery and for any genesis with storage in more than one account. It costs only the exact-count fast
path that #1692 introduced for single-storage geneses, and no correctness. Slice 1 confirms this with the genesis load
test.

**Bootnodes (an open pin, not a guess).**
- [H]'s `enodes.yaml` lists the 5 EF NodeOps bootnodes as `enode://…@ip:0?discport=N`: TCP port 0, discovery on N.
  It also lists a Lodestar enode at `:30303`.
- [G] lists the same 5 as ENRs, plus 3 legacy EF enodes at `:30303`.
- `Node.fromUri` (`Node.scala:23-32`) takes the TCP port from the URI, which is 0, and the UDP port from `discport`.
- **Slice 1 task.** Write a pin test showing what fukuii does with a TCP-0 bootnode: discovery ping to `discport`,
  and no RLPx dial to port 0.
  - If fukuii would dial port 0, ship only the `:30303` enodes ([G]'s legacy 3 plus Lodestar) and rely on DNS
    discovery for the rest.
  - Otherwise ship [H]'s 6.
- Add `all.hoodi.ethdisco.net` to `DnsTrustAnchors.shipped` (`Eip1459.scala:230`) with the EF key. A bare domain
  without an anchor is not walked.

**Sync and launch defaults (FR-020).** `conf/hoodi.conf` copies `conf/sepolia.conf`:
- `storage-scheme = "path"`;
- eth70 and eth71 on;
- `mining.protocol = "engine-api"`;
- the same `do-snap-sync` default as Sepolia.

## R16. Test strategy

| What | How | Pins |
|------|-----|------|
| Rules R1 to R11, N1 to N4 | `EphemeryReleaseSpec`: one test per refusal, from a mutated 165 copy | FR-006, FR-010, FR-013, SC-004 |
| Renderer and encoder | golden: raw 164 and 165 inputs render to the committed fixtures | FR-006, R1 |
| Genesis hash | 164 `0x39c91f8e…68f0` (existing), 165 `0x9485bbe8…305ecc` (new), the bundled iteration equals its release.json, Hoodi `0xbbe312…971b` | FR-002, FR-007, FR-019 |
| Fork ids | `ForkIdHoodiSpec`: the 5 rows above | FR-019 |
| Selection | `EphemeryResolverSpec`: fixed `Clock`, fake transport. Cases: bundled current, cached current, both ended plus fetch, not published, unreachable, refresh off, operator dir, several behind, republished, window mismatch, stale stage swept | FR-004 to FR-010, FR-014, FR-015, US2, US4 |
| Transport | `JdkHttpsTransportSpec`: local `HttpsServer`, self-signed certificate. Cases: https-only, hop allow-list, 5-hop cap, size cap, body deadline, proxy env mapping | FR-011, FR-012 |
| Tar reader | `StrictTarReaderSpec`: crafted in-memory archives. Cases: absolute path, `..`, `a/../../x`, symlink, hardlink, device, pax, oversize, too many entries, ratio bomb, truncation, duplicate, exec bit cleared, plus the real 165 header list | FR-012, FR-017 |
| Reset exit | `EphemeryResetWatchdogSpec`: fixed `Clock`, manual scheduler, captured exit. A forked-JVM launcher test sees code 75 and a RocksDB that reopens cleanly | FR-016, US5 |
| Launch | `LauncherIntegrationSpec`: add hoodi to the known networks. `ChainConfigMatrixSpec`: add hoodi only, since Ephemery is not in `blockchains.conf` (R4) | FR-018, FR-020 |
| Property hand-over | In process: apply the resolver's property map, call `ConfigFactory.invalidateCaches()`, load a fresh `ConfigFactory.load()` (never the `Config` object), and check where `fukuii.datadir`, `db.rocksdb.path`, `node-key-file` and `logging.logs-dir` resolve | FR-009, R5 |
| End to end | IntegrationTest under `src/it`, forked: `EphemeryForkedHarness`, a test-scope main calling `App.run` with its own `StartupDeps`. It uses an offset clock anchored to the fixture's reset (see the next table), and a fake transport serving fixture bytes or a synthetic next iteration. Checks: start on the right iteration and datadir; exit 75 at the reset; RocksDB reopens; the restart refreshes | FR-001, FR-004, FR-016, US2, US5 |
| ETC unchanged | the existing ETC, Mordor and Gorgoroth suites pass with no assertion changes, and forge reviews | FR-021, SC-006 |

There is no live network in any unit test, and no `Thread.sleep` (Principle III). Waits use a fixed `Clock`, a
manually advanced scheduler, or `eventually`.

**Time rules.**

| Rule | Why |
|------|-----|
| The `ephemery` unit tests never read the system clock. A grep gate in S2 enforces it: no `Clock.systemUTC`, `Instant.now`, `System.currentTimeMillis` or `LocalDateTime.now` under `src/test/scala/.../ephemery/`. Only the forked harness uses a clock offset from real time, so that its watchdog fires a few seconds after start | The bundle ends 2026-11-19, and every 28 days after a bundle bump. A test on the real clock would go red on that day, or reach for the network, in every PR's CI |
| Releases beyond the committed fixtures come from `SyntheticRelease.next(raw)`, a test helper. It shifts the timestamp and reset by one interval, adds 1 to the chain id and iteration, and computes the published hash with the library itself | It tests the refresh path without waiting for real iterations, and stays internally consistent |

## R17. Live validation (SC-007, SC-008)

- **SC-007.** On dev-node: `fukuii ephemery` with no `FUKUII_CONF_DIR`, next to the `eb-lighthouse` container
  re-pointed at `<root>/current/testnet`, follows the live iteration for 24 hours.
- **SC-008, the live check.** Iteration 165 resets at 1795114800, 2026-11-19 19:00 UTC; by R7, 166 is likely
  published between 17:30 and 19:00 UTC that day, or up to a few hours late.
  - Run it if slices 3 and 4b are merged and installed on dev-node by 2026-11-18.
  - Otherwise do a test reset with a controllable clock and the fake transport, and make the live check at the 167
    reset (1797534000, 2026-12-17 19:00 UTC).
- **Live Hoodi** (SC-005). Reach Hoodi peers and start syncing from a clean datadir on dev-node. Not a 24 h soak:
  Hoodi state does not fit next to Sepolia on this host (memory: host disk and IO). A full sync is fukuii-fc's call.
