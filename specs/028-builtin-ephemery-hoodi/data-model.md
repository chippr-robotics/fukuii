# Data Model: Ephemery and Hoodi as Built-in Networks (spec 028)

The entities of spec.md's Key Entities, as the design (research.md) shapes them. The types are Scala sketches of
fields and invariants, not code. Everything in `ephemery` is pure, except `ReleaseStore`, which does file I/O, and
`ReleaseTransport`, which does network I/O.

## ReleaseInputs

The EL members of one release, read from a directory (`--from-dir`, `fukuii.ephemery.source = <dir>`) or from the
`testnet-all.tar.gz` stream (R9).

| Field | Source member | Required | Notes |
|-------|---------------|----------|-------|
| `genesisJson` | genesis.json | yes | parsed with big numbers kept exact (R2) |
| `retention` | retention.vars | yes | `Map[String,String]` from the key=value regex; never sourced |
| `publishedGenesisHash` | deposit_contract_block_hash.txt | yes (N1) | trimmed; `0x` + 64 hex |
| `chainspecJson` | chainspec.json | no | absent from 165; checked only when present (R11) |
| `enodes` | enodes.txt | no | `#` comments stripped; `enode://` lines only; none is a WARN, not a refusal |
| `bootEnrs` | boot_enr.txt | no | passed through to release.env `BOOTNODE_ENR_LIST` |
| `clMembers` | every other member | no | streamed to `testnet/` (R10); never parsed |

## ValidRelease

What `EphemeryRelease.validate(inputs)` returns once every rule R1 to R11 and N1 to N4 passes.

| Field | Type | Invariant |
|-------|------|-----------|
| `iteration` | Int | `== retention.ITERATION_NUMBER == chainId - 39438000` (R2, N2) |
| `chainId` | BigInt | `== retention.CHAIN_ID` |
| `genesisTimestamp` | Long | `== retention.GENESIS_TIMESTAMP == genesis.timestamp` (R11) |
| `resetInterval` | Long | `retention.GENESIS_RESET_INTERVAL`, default 2,419,200 |
| `resetTime` | Long | `genesisTimestamp + resetInterval` (N4) |
| `forkTimes` | shanghai, cancun and prague required; osaka, bpo1, bpo2 and amsterdam optional | integers or null (R6) |
| `blobSchedule` | per fork: target, max, base-fee-update-fraction | forks known (R8); semantics checked by `BlobSchedule` at parse |
| `depositContract` | Address | R7 |
| `encodedGenesis` | String (JSON) | the R10 re-encoding; only allow-listed keys (R9) |
| `chainConf` | String (HOCON) | `ChainConfRenderer` output, golden-pinned. It carries `consensus = "engine-api"` and `genesis-hash` (R14) |
| `genesisHash` | ByteString | computed through `GenesisDataLoader.genesisHeader` over `encodedGenesis` and the parsed `chainConf`, and `== publishedGenesisHash` (N3) |
| `bootstrapNodes` | Seq[String] | from `enodes` |

The order matters: N3 runs last, because it needs the rendered config and the encoded genesis.

## ReleaseRefusal

A sealed ADT. Each case has an **exit class** (contracts/exit-codes.md) and a message that names the cause and the
next step (SC-004). The R-rule texts keep `fetch-genesis.sh`'s wording.

| Case | Exit | Example message (abridged) |
|------|------|----------------------------|
| `CannotRepresent(feature, detail)` | 78 | `ephemery-166 declares bpo3Time, which this fukuii cannot represent; a newer fukuii is needed` (R3, R4, R5, R6, R8, R9, R10 and an archive-rule break) |
| `Inconsistent(detail)` | 78 | `retention.vars CHAIN_ID=… disagrees with genesis.json chainId=…` (R1, R2, R7, R11, N1, N2) |
| `HashMismatch(published, computed)` | 78 | `ephemery-166: computed genesis 0x… does not match the published 0x…` (N3) |
| `Republished(iteration, cached, published)` | 78 | names both hashes and the two directories to move aside (R6) |
| `NotYetPublished(iteration)` | 69 | `ephemery-165 ended at …; ephemery-166 is not published yet` |
| `Unreachable(cause)` | 69 | names the host, and whether TLS, DNS, a timeout or the proxy failed; never the signed URL |
| `WindowMismatch(iteration, window, now)` | 69 | the release's window does not contain now (R7 step 4) |
| `Ended(iteration, resetTime, why)` | 78 | the iteration ended and fetching is disabled, or the source is a local dir with nothing newer |
| `BadSource(detail)` | 78 | the operator's `source` is not https, not a readable dir, or not a release |

## ReleaseMetadata (`release.json`)

The metadata written next to a validated release, in the cache entry and in the bundle. The schema is in
contracts/release-json.md.

- On load, an entry is trusted only after it re-runs `validate` over its own `chains/` files and its `genesisHash`
  matches.
- A missing or failing `release.json` makes the entry invisible: it is logged and ignored, never deleted.

## Ephemery root and its entries (ReleaseStore)

```text
<root>                         = fukuii.datadir before selection (default ~/.fukuii/ephemery)
<root>/releases/ephemery-N/    ReleaseEntry (immutable): chains/, release.json, testnet/?
<root>/ephemery-N/             IterationDatadir: the node's datadir for N
<root>/logs/                   logs
<root>/current                 symlink to releases/ephemery-N, the last selected
<root>/.stage-<random>/        StageDir, swept at start when older than 1 h
```

**ReleaseEntry.**
- **Created** by `ReleaseStore.place(stage)`: the stage is fully written, then renamed to `releases/ephemery-N` with
  `ATOMIC_MOVE`, only if the target is absent.
- **Already present.** If the entry exists with the same `genesisHash`, the stage is discarded. With a different
  hash, the result is `Republished`.
- **`testnet/` added later** (the CL directory on request) is staged and renamed into the existing entry with
  `ATOMIC_MOVE`, only if `testnet/` is absent.
- **Never modified otherwise**, and never deleted by fukuii.

**IterationDatadir.**
- Created by the node on first start of iteration N.
- Never opened for another iteration: `GenesisDataLoader.scala:139-149` stays as the backstop.
- Never deleted by the node (FR-009).
- At start the resolver logs the earlier ones, with their sizes.

**BundledIteration.**
- `classpath:ephemery/bundled/ephemery-N/`, the same three files as an entry.
- The resolver copies it into `releases/ephemery-N` through a stage on first use, so the node always runs from an
  entry on disk.

## Startup decision (EphemeryResolver)

The inputs are the raw config, the `Clock`, the bundle, `ReleaseStore`, and the transport. "Current" means
`genesisTimestamp ≤ now < resetTime`.

| # | Condition | Action | Result |
|---|-----------|--------|--------|
| 1 | network ≠ ephemery | nothing | no properties set |
| 2 | `custom-chains-dir` set and holds `ephemery-chain.conf` | stand down (FR-015); WARN if its genesis ts plus 2,419,200 (or adjacent `release.env RESET_TIME`) ≤ now | today's behaviour; no watchdog |
| 3 | a valid entry or the bundle is current | choose the highest current N; place the bundle if it is chosen | Managed(N) |
| 4 | none current, `refresh = true` | fetch target K (R7) → validate → place | Managed(K), or refusal 69/78 |
| 5 | none current, `refresh = false` | none | `Ended`, exit 78 |
| 6 | `source = <dir>` and none current | read the dir → validate → place (no network) | Managed(M), or `Ended`/`BadSource`, exit 78 |

**Managed(N).** The resolver sets these properties, then calls `ConfigFactory.invalidateCaches()`:
- `fukuii.blockchains.custom-chains-dir = <root>/releases/ephemery-N/chains`;
- `fukuii.datadir = <root>/ephemery-N`;
- `logging.logs-dir = <its value before selection>`;
- `fukuii.ephemery.active.{iteration,reset-time,genesis-hash,managed=true}`.

When it creates `<root>/ephemery-N` for the first time, it copies `<root>/static-nodes.json` into it if that file
exists (contracts/config.md). It then updates `current` and logs the startup report (contracts/exit-codes.md).

## ResetWatchdog state

```text
Idle ──(node started, active.managed = true, exit-at-reset = true)──▶ Armed(resetTime)
Armed ──(clock ≥ resetTime)──▶ Fired: WARN, then exit(75); the shutdown hooks close the node
Armed ──(node shutting down for another reason)──▶ Cancelled
Idle ──(operator dir, or exit-at-reset = false)──▶ WarnOnly(resetTime): WARN at the reset, no exit
```

## HoodiNetworkEntry

A static chain config, `conf/base/chains/hoodi-chain.conf`, whose keys mirror `sepolia-chain.conf`.

| Key | Value |
|-----|-------|
| `network-id` | 560048 |
| `chain-id` | `"0x88bb0"` |
| `network-type` | `"eth"` |
| fork keys | as in research.md R15, including `shanghai-timestamp = 0` and `cancun-timestamp = 0` |
| `blob-schedule` | as in research.md R15 |
| `deposit-contract-address` | as in research.md R15 |
| `bootstrap-nodes` | as in research.md R15 |
| `dns-discovery-domains` | `["all.hoodi.ethdisco.net"]` |
| `custom-genesis-file` | `{ include required("hoodi-genesis.json") }` |
| `consensus` | `"engine-api"` |
| `genesis-hash` | `"0xbbe312868b376a3001692a646dd2d7d1e4406380dfd86b98aa8a34d1557c971b"` |

The other keys (`olympia-block-number = "0"`, the sentinels, `eth-compatible-storage = true`) are copied from
Sepolia.

The genesis is `hoodi-genesis.json`, the [H] genesis with `timestamp: "0x67d80ec0"`, `mixHash` renamed, and the alloc
verbatim.
