# Research: Chain Registry (ChainSpec) — spec 024

**Branch**: `024-chain-registry` | **Date**: 2026-10-31 | **Spec**: [spec.md](./spec.md)

Every claim below was read on the working tree at `af872f1` (branch `ccr-6ee15a46-q037bc-3`). Nothing was run;
where a statement is about runtime behaviour it is marked as read, not observed.

---

## 0. Evidence re-measured on the tree

| Claim in spec.md | What the tree says | Where |
| --- | --- | --- |
| "nine `chains/<name>-chain.conf` files" | **Eight** entries are included by `blockchains.conf`: `etc`, `mordor`, `gorgoroth`, `test`, `eth`, `sepolia`, `plataberget`, `hive`. `App.knownNetworks` has **nine** names; two of them are not chain entries: `ephemery` (generated into `custom-chains-dir` by `scripts/ephemery/fetch-genesis.sh:283`) and `bootnode` (a profile over the default `etc` chain). | `conf/base/blockchains.conf:17-31`; `App.scala:18-28`; `conf/ephemery.conf:21` |
| `App.knownNetworks` gates the CLI | Yes. `isNetwork` (`App.scala:46`) is used by `determineNetworkArg` (`:94-100`) and the dispatch (`:250, :283`); `LauncherIntegrationSpec` reaches it by reflection (`:31-34, :54-55`). | `App.scala`; `src/test/.../LauncherIntegrationSpec.scala` |
| Bad custom entries are swallowed | `Try { ... }` → `result.failed.foreach(log.error)` → `result.toOption` in `BlockchainsConfig.apply`; a missing directory is `log.warn` + empty map. The later failure is the `NoSuchElementException` at `BlockchainsConfig.blockchainConfig`. | `utils/Config.scala:380-404, 352-359` |
| Three keys must agree | `network-type` read into `NetworkType` (`BlockchainConfig.scala:329-330`, default ETC when absent); `mining.protocol` read by `MiningConfig.readProtocol` (`MiningConfig.scala:60-69`) and mapped to a header validator by `ValidatorsExecutor.apply` (`ValidatorsExecutor.scala:55-60`: `EngineApi → TransitionBlockHeaderValidator`, `PoW → PoWBlockHeaderValidator`); `storage-scheme` read at `SNAPSyncController.scala:1849` (default `Hash`) and checked against `networkType` only at SNAP start (`SyncController.scala:466-472`, a `require`, i.e. after the datadir is open). | as cited |
| Profiles hand-set `mining.protocol` | `conf/sepolia.conf:32-49` (15-line comment), `conf/eth.conf:31-48`, `conf/plataberget.conf:37-42`, `conf/ephemery.conf:44-48` set `engine-api`; `conf/gorgoroth.conf:32` sets `protocol = "ethash"`, which is **not** in `Protocol.KnownProtocolNames` (`pow`, `mocked`, `restricted-pow`, `engine-api`; `Protocol.scala:49-56`), so by reading `MiningConfig.readProtocol` throws `RuntimeException` on that profile. VERIFY: DID NOT RUN. | as cited |
| MESS default unvalidated | `MESSConfig(enabled = false)` default (`MESSConfig.scala:22`); `mess { enabled = false }` in `etc-chain.conf:248-260`, `mordor-chain.conf:217-229`, `gorgoroth-chain.conf:214-231`, **and in the four ETH files** `eth-chain.conf:257`, `sepolia-chain.conf:134`, `plataberget-chain.conf:156`, `hive-chain.conf:102`; **absent** from `test-chain.conf`. `gorgoroth`'s block carries three keys no code reads (`decay-constant`, `max-time-delta`, `min-weight-multiplier`; grep over `src/main` finds no reader). | as cited |
| Fork schedule unchecked against the datadir | The only datadir check is the genesis header comparison in `GenesisDataLoader.loadGenesisData` (`:108-118`): same hash → ok, different → `RuntimeException`. No fork activation is recorded or compared. | `blockchain/data/GenesisDataLoader.scala` |
| Dead keys in shipped entries | `mordor-chain.conf:9 capabilities`, `plataberget-chain.conf:58-60 sync { do-snap-sync = false }` (inside the chain entry; nothing reads `blockchains.<key>.sync`), `gorgoroth` MESS tuning keys above. None is read by `fromRawConfig`, so removing or recognising them does not change any `BlockchainConfig`. | as cited |
| Startup order | `Fukuii.main`: `Config` is first touched at `:27` (tmpdir) — `object Config` eagerly builds `BlockchainsConfig` (`InstanceConfig.scala:86`); `ConfigValidator.validate` runs at `:56-59` and `System.exit(1)` on errors; the datadir opens lazily in `StorageBuilder.storagesInstance` (`NodeBuilder.scala:150-156`) when `StdNode.start` runs (`Fukuii.scala:116`). Ports bind inside `StdNode.start`. | as cited |
| Existing config tests | `ChainConfigValidationSpec` asserts fork numbers of `etc`/`mordor` from `ConfigFactory.load()` (`:19-23`); `ChainConfigMatrixSpec` loads the shipped `blockchains.conf` via `parseResources(...).atPath("fukuii").resolve()` (`:32-36`) — the right assembly for a golden capture; `BlockchainsConfigSpec` covers `custom-chains-dir` incl. "custom overrides built-in" (`:148`), which DC-1 inverts; `MESSConfigParsingSpec` pins the `olympia-block-number` fallback for `reactivationBlock`. | `src/test/scala/com/chipprbots/ethereum/utils/*`, `consensus/mess/*` |

Keys that differ between the two families in the shipped entries (read from the eight files):

| Family | Keys only this family declares |
| --- | --- |
| ETH (`eth`, `sepolia`, `plataberget`, `hive`) | `network-type = "eth"`, `terminal-total-difficulty`, `shanghai/cancun/prague/osaka/amsterdam/bpo1/bpo2-timestamp`, `blob-schedule { cancun prague bpo1 bpo2 }`, `merge-netsplit-block-number`, `deposit-contract-address`, `arrow-glacier-block-number` / `gray-glacier-block-number` (eth only), `olympia-gas-limit-elasticity`, `pow-target-time = null` |
| ETC (`etc`, `mordor`, `gorgoroth`, `test`) | `pow-target-time = <duration>`, `mess.ecbp1100-*` (etc, mordor), `base-fee-floor` / `min-tip` (etc, mordor), `allowed-miners` (test), `capabilities` (mordor, dead), `spiral-gas-target` / `olympia-gas-target` where declared |
| Both | `network-id`, `chain-id`, all `*-block-number` forks (ETH files carry ETC forks as `10^18` sentinels and vice versa), `max-code-size`, `dao`, `account-start-nonce`, `custom-genesis-file`, `monetary-policy`, `gas-tie-breaker`, `eth-compatible-storage`, `bootstrap-nodes`, `dns-discovery-domains` (etc, mordor, sepolia), `treasury-address`, `olympia-block-number`, `mess` (see DC-2 conflict, R-3) |

---

## R-1. Loader design: where ChainSpec sits relative to `BlockchainConfig`

**Decision**: a new package `com.chipprbots.ethereum.chainspec` holds `ChainSpec` (typed entry), `ChainRegistry`
(built-in set + operator directory, collision rules), `ChainSpecValidator` (every FR-004 check, returns a
`ChainSpecError` ADT, never throws), `ChainSpecSchema` (the known-key / required-key / enum tables that both the
validator and the JSON Schema sync test read) and `DeploymentDerivation` (DC-5). `BlockchainsConfig` is **wrapped,
not replaced**: `BlockchainsConfig.apply(rawConfig)` becomes an adapter over `ChainRegistry.load(rawConfig)` and keeps
its `(network, blockchains: Map[String, BlockchainConfig])` shape so `InstanceConfig.scala:86`, `NodeBuilder.scala:76`
and every `blockchains.blockchainConfig` reader are untouched. `BlockchainConfig.fromRawConfig` is **not modified**
(`BlockchainConfig.scala:237-425`); `ChainSpec.blockchainConfig` is its output on the validated entry. That is what
makes GT-1 hold by construction: the bytes come from the same function over the same HOCON.

`ChainSpec` carries, beside `blockchainConfig`: `chainKey`, `source` (classpath resource or absolute file), `consensus`,
`family`, `finalityRule`, `forkDispatch`, `storageScheme`, `defaultSyncMode`, `genesis` (file or inline, declared
hash), `resourceProfiles`. Everything consensus-bearing stays inside `BlockchainConfig`; the new fields are
deployment- and routing-facing.

**Rationale**: `fromRawConfig` is the one function whose output is the golden capture; wrapping it is the smallest
change that satisfies SC-002. A replacement (a typed decoder into a new case class and a mapping back to
`BlockchainConfig`) would re-implement 40 key reads and risk a subtle default change (for example the
`networkId` double-fallback at `BlockchainConfig.scala:279-281` or `minTip` defaulting to `1` at `:326-327` while the
case class default is `10^9` at `:59`).

**Alternatives rejected**: (a) replace `BlockchainsConfig` with `ChainRegistry` everywhere — 20+ call sites in
`nodebuilder/`, `runtime/`, tests; no behavioural gain. (b) Put the validator inside `fromRawConfig` — it must stay
total over the eight shipped entries and is consulted by tests that build synthetic configs (`BlockchainsConfigSpec`);
a stricter `fromRawConfig` would break them and is the wrong layer for "unknown field".

**Load order and fail-fast** (DC-1, SC-003): validation must finish before `object Config` is first touched, because
`InstanceConfig` builds `blockchains` eagerly (`InstanceConfig.scala:86`) and `Fukuii.main` touches `Config` at `:27`
before `ConfigValidator.validate` at `:56`. Plan: `Fukuii.main` (and `ChainTool`, `CheckpointCli`) first call
`ConfigValidator.validate(ConfigFactory.load().getConfig("fukuii"))`, which now also runs `ChainRegistry.load` and
`DeploymentDerivation.check`, and exits `1` on any error — before `Config` is initialised and therefore before anything
can open `db.rocksdb.path`. `BlockchainsConfig.apply` on a `Left` throws `ChainSpecLoadException` carrying the same
rendered errors (so a programmatic caller — tests, `FukuiiRuntime` until 023 T-002 deletes it — also fails loudly,
replacing `result.toOption`). Operator-directory entries whose `custom-genesis-file` is relative resolve against the
entry's directory (built-ins keep today's file-then-classpath lookup, `GenesisDataLoader.scala:63-66`).

**Behind a flag for the MVP**: `fukuii.blockchains.registry.enabled` (default `false` in slice 2, `true` in slice 3,
removed in slice 6). With the flag off the adapter runs today's code path verbatim; with it on, the registry path.
The golden test runs both paths and asserts equality, which is the GT-1 proof.

## R-2. JSON Schema: hand-written, kept in sync by a test

**Decision**: hand-write `contracts/chainspec.schema.json` (draft 2020-12) and add `ChainSpecSchemaSyncSpec`, which
parses the schema with circe (`Dependencies.scala:35-44`, already a dependency) and asserts, against
`ChainSpecSchema`: the flattened set of `properties` paths equals `knownKeys`; `required` equals `requiredKeys`;
`consensus.enum` equals `ConsensusType.values.map(_.key)`; `network-type.enum` equals `{etc, eth}`;
`additionalProperties` is `false` on every object; the `if/then` family conditions name exactly the family-required
keys. A second test renders each shipped entry to JSON (`ConfigRenderOptions.concise()`, as `fromRawConfig` already
does for inline genesis at `BlockchainConfig.scala:268-270`) and checks it against the same tables — the loader *is*
the validator fukuii ships; the JSON Schema is Orbita's offline copy.

**Rationale**: no new dependency (constitution "Technology & Architecture Constraints"); descriptions and examples in
the schema are for Orbita's operators and are better authored than generated; the sync test makes drift a red build.

**Alternatives rejected**: generating the schema from Scala types (no maintained Scala 3 library produces draft
2020-12 with `if/then` conditionals from an ADT; a bespoke generator is more code than the tables); adding a JSON
Schema validator library in test scope (`com.networknt:json-schema-validator`) — useful but not necessary; revisit
if Orbita reports a divergence the equivalence test did not catch.

## R-3. MESS (DC-2) versus GT-1 — the one place the two gates pull apart

**Finding**: four ETH entries carry `mess { enabled = false }` *and* `olympia-block-number`, so today
`fromRawConfig` gives them `messConfig = MESSConfig(enabled = false, reactivationBlock = Some(10^18))`
(`BlockchainConfig.scala:357-366`, fallback at `:364`). `test-chain.conf` (ETC family) has no `mess` block and gets
`MESSConfig()` (`reactivationBlock = None`). DC-2 requires removing the block from the four ETH entries and adding
`mess { enabled = false }` to `test`; both edits flip `messConfig.reactivationBlock` for those five entries. The field
is inert when `enabled = false` (`MESSConfig.isActiveAtBlock`, `MESSConfig.scala:33-34`, is `enabled && ...`), and
`messConfig` is consumed only through `NodeBuilder.messConfigOpt` → `BranchResolution.messConfig`
(`NodeBuilder.scala:291-292`, `BranchResolution.scala:27`). But it is a field-level change, which GT-1's text forbids.

**Decision**: the golden capture is taken on today's files (slice 1) and must be reproduced exactly by the loader
with the registry flag on and off (slice 2). The DC-2 slice (slice 4) edits the five files and re-captures the golden
files **with an enumerated delta allowlist**: the test asserts that for `eth`, `sepolia`, `plataberget`, `hive`,
`test` the only differing field is `messConfig.reactivationBlock`, that `messConfig.enabled` is `false` before and
after, and that every other entry is byte-identical. `beacon` signs the four ETH deltas, `forge` the `test` delta.
This is recorded in plan.md Complexity Tracking and is a **user decision** (D-1 in plan.md "Decisions for the user"):
accept the enumerated inert delta, or amend GT-1's wording.

**Alternative** (zero delta on the ETH four): the loader synthesises `mess { enabled = false }` for ETH-family entries
before calling `fromRawConfig`. Rejected as the default because it hides the very block DC-2 forbids and still leaves
the `test` delta; offered as option (b) in D-1.

## R-4. Removing `App.knownNetworks` and keeping `fukuii <network>` working

**Decision**: the launcher stops knowing chain names. Any first argument that is not a command (`cli`, `keytool`,
`bootstrap`, `faucet`, `signature-validator`, `checkpoint`, `chains`, `runtime`, `import`, `export`), not a modifier
(`public`, `enterprise`) and not an option flag is a **chain-key candidate**. `setNetworkConfig(candidate)` keeps its
profile lookup (`App.scala:51-92`): a filesystem `conf/<key>.conf`, else classpath `conf/<key>.conf`. New: when no
profile exists, it sets `-Dfukuii.blockchains.network=<key>` on the default `application.conf` (the same
`System.setProperty` mechanism `applyModifiers` uses, `App.scala:103-137`) so that an operator entry with no profile
file still starts. Whether `<key>` names a chain is then decided once, by `ChainRegistry.load` inside
`ConfigValidator.validate`: a missing key is `UnknownChainKey(key, known)` whose message lists every registry key and
notes that an unrecognised launcher word is read as a chain key (FR-002). `--help` lists the registry's keys instead
of the hardcoded block at `App.scala:158-166` (it runs the registry read-only).

**Rationale**: the profile file and the chain entry are different things today (`conf/etc.conf` is seven lines
selecting `blockchains.network`), and the registry is the only component that can know the key set, including
operator entries, whose directory is itself a config value (`custom-chains-dir`, `Config.scala:363`).

**Alternatives rejected**: loading the registry in `App` before `setNetworkConfig` (needs the config to find
`custom-chains-dir`: circular); requiring a profile file per operator chain (contradicts SC-001 "no release": a
profile is optional deployment data, the entry is the chain). Consequence to plan: `LauncherIntegrationSpec` loses
its reflective `isNetwork` and asserts the candidate rule instead.

## R-5. Deriving `mining.protocol` and `storage-scheme`; making `sepolia.conf`'s warning case impossible

**Decision**: both keys are derived from `consensus` and injected into the effective `fukuii` config by
`DeploymentDerivation` before `InstanceConfig` reads it; a profile may state them only if equal to the derivation
(or, for `ethash-pow`, one of the permitted PoW variants), otherwise `ProfileConsensusMismatch` names the ChainSpec
value, the profile value and the profile's origin (`ConfigValue.origin().description()`, which HOCON keeps per value).

| `consensus` | `mining.protocol` derived | profile values accepted | `storage-scheme` derived | profile accepted |
| --- | --- | --- | --- | --- |
| `ethash-pow` | `pow` | `pow`, `restricted-pow`, `mocked` (testmode) | `hash` | `hash` or absent |
| `engine-api` | `engine-api` | `engine-api` or absent | `path` | `path` or absent |

To make "absent" observable, `protocol = pow` leaves `conf/base/mining.conf:21` and `storage-scheme` stays absent
from `base/sync.conf` (it already is). `conf/sepolia.conf`, `eth.conf`, `plataberget.conf`, `ephemery.conf` lose their
`mining` blocks and `storage-scheme` lines (SC-004); `conf/gorgoroth.conf:32` loses `protocol = "ethash"` (today an
error by reading, R-0). `SyncController.scala:466-472`'s `require` stays as a post-open guard (defence in depth; it
can no longer fire). The `ValidatorsExecutor.apply` mapping (`:55-60`) and `MiningBuilder.buildMining`
(`MiningBuilder.scala:61-75`) are untouched, so the validator set per chain is exactly today's: `forge` confirms
`etc`/`mordor`/`gorgoroth`/`test` still resolve to `PoWBlockHeaderValidator` (or the profile's permitted variant),
`beacon` that `eth`/`sepolia`/`plataberget`/`hive` resolve to `TransitionBlockHeaderValidator`.

The same mechanism carries the **sync-mode default**: the ChainSpec's `sync.do-snap-sync` (required, FR-007) is
injected when the merged profile lacks the key; `do-snap-sync = true` leaves `conf/base/sync.conf:6` so a profile's
value is distinguishable from the base default. Shipped profiles' `do-snap-sync` lines are removed where they equal
the ChainSpec default (eth, sepolia, plataberget, hive, gorgoroth); `conf/testmode.conf:7` keeps its override.

**Rationale**: HOCON gives one value per path after merge; a derivation that must tell "operator set it" from "base
default" has to own the default. Injection (rather than a parallel accessor) keeps `MiningConfig.apply`
(`MiningConfig.scala:71-74`), `SyncConfig.apply` and `SNAPSyncConfig.fromConfig` unchanged.

**Alternatives rejected**: `storage-scheme` as a settable ChainSpec HOCON key — the binding decision makes it
derived; and it is transitional until spec 018 DC-9 unifies the layout, after which `DeploymentDerivation` drops the
row and `ConfigValidator.RemovedKeys` (pattern at `ConfigValidator.scala:21-48`) reports a profile that still sets it.
Teaching `MiningBuilder` to ignore the profile — would silently change behaviour instead of refusing (working
discipline: fail loudly).

## R-6. Datadir compatibility record (DC-6) and geth's `ChainConfig.CheckCompatible`

**Precedent**: go-ethereum stores the chain config in the database keyed by genesis hash
(`rawdb.WriteChainConfig(db, genesisHash, cfg)`), and on every start `params.ChainConfig.CheckCompatible(newcfg,
headNumber, headTimestamp)` walks every fork field: a fork is *incompatible* iff its activation changed **and** either
the stored or the new activation is at or before the head (`isForkBlockIncompatible` / `isForkTimestampIncompatible`:
`(isForked(stored, head) || isForked(new, head)) && stored != new`). It returns a `ConfigCompatError` naming the fork
and a `RewindTo` height; `core.SetupGenesisBlock` refuses to continue. A datadir with a genesis block but no stored
config gets the current config written ("Found genesis block without chain config").

**Decision**: a versioned record in AppState (`db/storage/AppStateStorage.scala`, string→string under
`Namespaces.AppStateNamespace`), following the key style of spec 022's markers (`snap.heal.invariant`,
`checkpoint.import.inProgress`, `AppStateStorage.scala:652-655`):

| Key | Value |
| --- | --- |
| `chain.spec.chainKey` | the `chainKey` the datadir was created for |
| `chain.spec.genesisHash` | `0x` + 64 hex (the stored genesis header's hash) |
| `chain.spec.forkSchedule` | `v1;` then `name=value;` pairs sorted by name: every `ForkBlockNumbers` field, every `ForkTimestamps` field, `terminal-total-difficulty`, rendered in decimal, `Long.MaxValue` sentinels and `None` rendered as `-` |

Written in one `updateSync` batch when the genesis is first stored (the `onFirstStored` hook already exists,
`GenesisDataLoader.scala:128`). On every open: no record → **backfill**: compare the ChainSpec's `genesis-hash` (or
the computed genesis hash) with `BlockchainReader.genesisHeader.hash` (`BlockchainReader.scala:142`); equal → write
the record from the current ChainSpec (geth's "without chain config" rule); different → `GenesisMismatch`. Record
present → `chainKey` equal (else `DatadirChainKeyMismatch`), genesis equal (else `GenesisMismatch`), then the
`CheckCompatible` rule per fork with `head = appStateStorage.getBestBlockNumber()` for block forks and the best
header's `unixTimestamp` for timestamp forks (via `blockNumberMappingStorage` + `blockHeadersStorage`, the lookup
`StdNode.scala:428-434` already performs). An incompatible fork → `ForkScheduleIncompatible(fork, stored, new, head)`,
exit `3`, before any actor system or port. Compatible with changes (future forks added or moved, bootnodes) → the
record is rewritten to the new schedule. The check needs the DB open (read-only reads of three storages); it runs
first thing in `StdNode.start` before `loadGenesisData` (`StdNode.scala:60`) and before anything binds. `vault`
reviews the keys, the batch and the open-order; the record's renderer is pure and unit-tested.

**Rationale**: AppState is the existing per-datadir metadata store with a WAL-backed batch write; the string format
is diff-able in `fukuii chains validate --datadir` output and needs no RLP schema. `chainKey` is recorded so a datadir
is never silently reused for another chain with the same genesis (DC-3).

**Alternatives rejected**: a sidecar JSON file in the datadir (two sources of truth; not in the same WAL as the
genesis write); storing the whole ChainSpec (bootnodes change legitimately; only the consensus-bearing schedule should
gate).

## R-7. GT-1 golden capture design

**Decision**: `GoldenBlockchainConfigSpec` (tag `UnitTest, ConsensusTest`) with a committed fixture per entry at
`src/test/resources/chainspec/golden/<chainKey>.golden.txt`, written by a **canonical renderer**
(`BlockchainConfigRender.canonical`): one `path = value` line per field, recursively over the case classes, sets
sorted, `ByteString` as `0x` hex, `BigInt` decimal, `Option` as `none`/`some(...)`, `Address` hex, durations in
seconds. The spec (1) assembles the shipped resources exactly as `ChainConfigMatrixSpec.scala:32-36` does
(`parseResources("conf/base/blockchains.conf").atPath("fukuii").resolve()`), (2) for each of the eight entries
asserts the rendering of `BlockchainConfig.fromRawConfig(...)` equals the committed file, (3) once the registry
exists, asserts `ChainRegistry.load(...).spec(key).blockchainConfig == fromRawConfig(...)` (case-class equality; every
component is a case class or `opaque type` over one — `ChainId` is `opaque type ChainId = BigInt`,
`domain/ChainId.scala:3`) and also equals the file, with the flag on and off. A `--regen` system property rewrites
the fixtures; CI never sets it. The delta allowlist of R-3 is a separate assertion that only slice 4 enables.

**Rationale**: `toString` ordering of `Set[String]` (bootstrap nodes) is not stable across JVM versions; a renderer
with sorted collections is. Committed text makes a field change visible in review as a one-line diff.

**Alternatives rejected**: hashing only (no reviewable diff); comparing against a copy of `fromRawConfig` frozen in
the test (tests the copy, not the files).

## R-8. Shadowing flag plumbing (`--chain-registry-allow-shadow`)

**Decision**: the registry reads `fukuii.blockchains.registry.allow-shadow` (boolean, default `false`, declared in
`conf/base/blockchains.conf`). The launcher maps the flag `--chain-registry-allow-shadow` to
`System.setProperty("fukuii.blockchains.registry.allow-shadow", "true")` before `ConfigFactory` loads, the same
way `applyModifiers` sets discovery properties (`App.scala:103-137`), and strips it from the args forwarded to
`Fukuii.main`. With the property unset, a `chainKey` in both the built-in set and the operator directory is
`ChainKeyCollision(key, builtInSource, operatorSource)`; with it set, the operator entry wins and the registry logs
at WARN `chain '<key>': operator entry <path> shadows built-in <resource> (--chain-registry-allow-shadow)`. Two
operator files for one key (impossible in one directory by file name; possible once 023 lets several directories be
listed) are always a collision. `BlockchainsConfigSpec`'s "custom overrides built-in" test (`:148`) becomes two tests:
refuse by default, allow with the key set.

**Rationale**: one mechanism (system property → HOCON) already carries launcher switches; tests can set the HOCON
key directly; the WARN names both files so a devnet operator sees what won.

**Alternative rejected**: a CLI-only flag threaded through `InstanceConfig` (a second plumbing path for one boolean).

## R-9. Sequencing with spec 020 (Scala 3.9) and spec 023

`specs/020-scala-3-9-lts-migration/dc3-sites.md` and `gt1-inventory.md` name **one** file this spec touches:
`blockchain/data/GenesisDataLoader.scala` (`dc3-sites.md:203`, a `given` duplicate; `gt1-inventory.md:66`). The
PR 5 187-file list is not enumerated in the spec documents (`plan.md:105, :151` refer to it); it is the `using`
insertion at implicit call sites, so any file passing `(implicit blockchainConfig: BlockchainConfig)` is a candidate:
`GenesisDataLoader.scala`, `nodebuilder/NodeBuilder.scala`, `nodebuilder/StdNode.scala`, `ValidatorsExecutor.scala`.
`App.scala`, `utils/Config.scala`, `utils/InstanceConfig.scala`, `ConfigValidator.scala`, `MiningBuilder.scala`,
`utils/BlockchainConfig.scala` do not appear in either inventory. Plan consequence: slices 1–2 (new files + tests)
land in alignment-review phase A/B freely; slices 3–5 touch `App.scala`/`Config.scala`/`InstanceConfig.scala`
(outside 020's set) and `StdNode.scala`/`GenesisDataLoader.scala` (inside it) — those two files are edited **after
020 PR 5 is on staging**, or the branch is rebased onto PR 5 before review. Spec 023 T-002 deletes `FukuiiRuntime`
(`runtime/FukuiiRuntime.scala:79-80` calls `ConfigValidator.removedKeyWarnings`); this spec does not edit that file.
023 T-003 adds `--chain`; this spec provides `ChainRegistry.resolve(chainKey)` and the `UnknownChainKey` error 023's
flag parser reports.
