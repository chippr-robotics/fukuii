# Quickstart: validating spec 028

These are runnable checks that prove each slice works. Contracts and the data model hold the details; this file only
says what to run and what to expect.

All sbt commands run from a full clone, one at a time:

```bash
flock /home/dontpanic/work/.sbt.lock sbt -Dsbt.color=false -Dsbt.supershell=false -Dsbt.server.forcestart=false <cmd>
```

## S1: Hoodi

```bash
sbt "testOnly *HoodiGenesisSpec *ForkIdHoodiSpec *ChainConfigMatrixSpec *BlobScheduleSpec *LauncherIntegrationSpec"
sbt "testOnly *ForkId* *GenesisDataLoader* *Mordor* *EtcGenesis*"   # ETC unchanged (FR-021)
```

**Expect:**
- the Hoodi genesis hash is `0xbbe312868b376a3001692a646dd2d7d1e4406380dfd86b98aa8a34d1557c971b`;
- the 5 fork-id rows match go-ethereum (research.md R15);
- the ETC suites pass with no assertion changes.

**Live** (SC-005), on a clean datadir with a Hoodi consensus client on the Engine API:

```bash
fukuii hoodi -Dfukuii.datadir=/tmp/hoodi-check
```

**Expect** in the log: chain id 560048, the genesis hash above, then peers connected with fork id `0x23aa1351` (BPO2,
next 0), and the CL driving sync. Stop once peers are steady and sync has started. This is not a soak (research.md
R17).

## S2: the release library

```bash
sbt "testOnly *EphemeryRelease* *EphemeryGolden* *EphemeryGenesisSpec *EphemeryBlobScheduleVectorsSpec *GenesisDataLoader*"
grep -rn "utils.Config\b\|\bConfig\.\(blockchains\|config\|Db\)" src/main/scala/com/chipprbots/ethereum/ephemery/   # expect nothing
scripts/ephemery/fetch-genesis.sh --from-dir src/test/resources/ephemery/releases/ephemery-165 --out /tmp/eph-s2
diff -u src/test/resources/ephemery/ephemery-165/ephemery-chain.conf /tmp/eph-s2/ephemery-165/chains/ephemery-chain.conf
```

**Expect:**
- every refusal test passes;
- the golden render equals the committed fixtures;
- the 165 hash is `0x9485bbe8e32e278194666d249590c9167057edeabaecc62e7a68355f68305ecc`;
- the script output diffs clean.

## S3: bundled, selection, the exit at the reset

```bash
sbt "testOnly *BundledEphemerySpec *EphemeryResolverSpec *EphemeryResetWatchdogSpec *EphemeryPropertyHandoverSpec *LauncherIntegrationSpec"
sbt "IntegrationTest / testOnly *EphemeryLaunchItSpec"
grep -rnE "Clock\.systemUTC|Instant\.now|currentTimeMillis|LocalDateTime\.now" src/test/scala/com/chipprbots/ethereum/ephemery/   # expect nothing
```

**Expect:**
- the bundle's hash, its `release.json` and the computed hash agree;
- the resolver picks, in order:
  1. the operator directory;
  2. a current cache entry;
  3. the bundle;
- an ended bundle with nothing cached refuses with exit 78;
- the property hand-over test resolves `fukuii.datadir` to `<root>/ephemery-165`, and `logging.logs-dir` to
  `<root>/logs`;
- the forked `EphemeryLaunchItSpec` starts in `<root>/ephemery-165`, then exits 75 when its offset clock passes
  `resetTime`;
- RocksDB reopens cleanly afterwards.

**Live** (start of SC-007), on dev-node with no `FUKUII_CONF_DIR` and a new datadir root:

```bash
fukuii ephemery -Dfukuii.datadir=/chipprbots/blockchain/ephemery/builtin   # a NEW path; never a frozen or other datadir
```

**Expect:**
- the startup report line from contracts/exit-codes.md: `ephemery-165 … from bundled; resets 2026-11-19T19:00:00Z`;
- `eb-lighthouse`, pointed at the iteration's testnet dir, drives the node to the head.

## S4a: transport and tar reader

```bash
sbt "testOnly *JdkHttpsTransportSpec *StrictTarReaderSpec"
```

**Expect** each of these to be refused, with its own message:
- an `http:` start URL, or a redirect hop to http;
- a hop to a host off the allow-list;
- a sixth redirect hop;
- a body over the cap, or past the deadline;
- a tar entry with a symlink, hardlink, device, pax header, `..`, an absolute name, or a duplicate;
- an archive over a size or ratio limit, or truncated.

**Expect also:**
- every file the reader writes is 0644, and every directory 0755;
- the real 165 header list (research.md R9) round-trips.

## S4b: the refresh

```bash
sbt "testOnly *ReleaseFetcherSpec *EphemeryResolverSpec *LauncherIntegrationSpec"
sbt "IntegrationTest / testOnly *EphemeryLaunchItSpec"
```

**Expect:**
- **Clock past the 165 reset, fake transport serving `SyntheticRelease.next(165)`.** Fetch, validate, hash match,
  place `releases/ephemery-166`, then start in `<root>/ephemery-166`; `ephemery-165/` is untouched.
- **Not published.** Exit 69 within the 60 s budget.
- **Refresh off.** Exit 78.
- **Several iterations behind.** Straight to the iteration whose window contains now.
- **Republished.** Exit 78, naming both hashes.

**CLI**, from an empty `HOME` with the network up:

```bash
fukuii cli ephemery-fetch --release ephemery-165                  # into the node's root, with testnet/
ls ~/.fukuii/ephemery/releases/ephemery-165/testnet/config.yaml   # present; no parsedBeaconState.json
scripts/ephemery/fetch-genesis.sh --out /tmp/eph-s4b              # wrapper, same layout as before
```

## S5: live checks

- **SC-007.** On dev-node, `fukuii ephemery` (built-in, no `FUKUII_CONF_DIR`) next to `eb-lighthouse --testnet-dir
  <root>/current/testnet`. Follow the head for 24 h; record the head lag and the peer count in the slice report.
- **SC-008.** Run under a systemd unit with the runbook's `Restart=on-failure`, `RestartSec=120` and
  `RestartPreventExitStatus=78`, across the 165→166 reset (2026-11-19 19:00 UTC). **Expect:**
  - at 19:00:00 UTC, the WARN line and exit 75;
  - restarts (exit 69 while 166 is unpublished, if late);
  - the first start after publication refreshes to `ephemery-166` within 10 minutes of the release's `published_at`;
  - the CL restarted on `<root>/current/testnet` follows the new chain.
- **Fallback when S4b is not installed by 2026-11-18.** Repeat SC-008 with the S3 build (exit 75, then a manual
  `fetch-genesis.sh` refresh), run the controllable-clock S4b tests, and move the live SC-008 to the 167 reset
  (2026-12-17 19:00 UTC).
- **SC-006.** The full `testEssential` once at the end of the last slice; ETC, Mordor and Gorgoroth unchanged.
