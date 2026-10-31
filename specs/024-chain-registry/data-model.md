# Data Model: Chain Registry (ChainSpec) — spec 024

**Branch**: `024-chain-registry` | **Date**: 2026-10-31 | **Spec**: [spec.md](./spec.md) | **Research**:
[research.md](./research.md)

Types live in `com.chipprbots.ethereum.chainspec` unless stated. HOCON keys are the entry's keys (today's
`<chainKey>-chain.conf` shape, research R-1); model field names are camelCase. Nothing here changes
`BlockchainConfig` (`utils/BlockchainConfig.scala:40-76`).

---

## 1. Enumerations

### `ConsensusType` (HOCON `consensus`, **required**, FR-003)

| Value | Family implied | Fork dispatch | Validator set (`ValidatorsExecutor.apply`, `:55-60`) | Allowed sync modes | Storage scheme (transitional, R-5) |
| --- | --- | --- | --- | --- | --- |
| `ethash-pow` | `etc` | block-number (`forBlock()`) | `PoWBlockHeaderValidator` (profile may select `restricted-pow` / `mocked`) | regular, snap | `hash` |
| `engine-api` | `eth` | timestamp (`forTimestamp()`) | `TransitionBlockHeaderValidator` | regular, snap (both CL-driven) | `path` |

Exactly these two. `ConsensusType.fromKey(s): Either[UnknownConsensusType, ConsensusType]`.

### `Family` (HOCON `network-type`, optional, default `etc`; model name `family`)

`etc | eth`. Maps 1:1 to `utils.NetworkType` (`BlockchainConfig.scala:19-27`). The HOCON key keeps today's name so the
eight built-ins need no rename; the model, the JSON Schema description and every log line say "family". Must agree
with `consensus` (`FamilyConsensusMismatch`).

### `FinalityRule` (derived, **not settable**; FR-008; consumed by spec 025)

| `consensus` | `finalityRule` |
| --- | --- |
| `ethash-pow` | `genesis-only` |
| `engine-api` | `consensus-layer` |

A `finality-rule` key in an entry is an unknown field (`UnknownField`).

### `ForkDispatch` (derived): `block-number | timestamp`. `StorageScheme` (derived): reuses
`blockchain.sync.snap.StorageScheme` (`Hash | Path`).

### `Role` (from spec 023): `ingest | replica | archive | sim`. Used only as the key of resource profiles here.

---

## 2. `ChainSpec` — the registry entry

| Field | Type | HOCON key | Required | Family | Notes |
| --- | --- | --- | --- | --- | --- |
| `chainKey` | `String` matching `^[a-z][a-z0-9-]{0,31}$` | file name `<chainKey>-chain.conf` / object key under `blockchains` | yes | both | The only selector (DC-3). Not a field inside the file; derived from the file name or the `blockchains.<key>` object key; the two must agree for operator files (`ChainKeyFileNameMismatch`). |
| `source` | `ChainSpecSource` = `BuiltIn(resource)` \| `Operator(path)` | — | derived | both | Shown by `fukuii chains list`; used in collision and shadow messages. |
| `consensus` | `ConsensusType` | `consensus` | **yes** | both | New key; added to all eight built-ins (not read by `fromRawConfig`, GT-1 safe). |
| `family` | `Family` | `network-type` | no (default `etc`) | both | Must agree with `consensus`. |
| `finalityRule` | `FinalityRule` | — | derived | both | |
| `forkDispatch` | `ForkDispatch` | — | derived | both | |
| `storageScheme` | `StorageScheme` | — | derived | both | Transitional until 018 DC-9. |
| `chainId` | `ChainId` (`BigInt`) | `chain-id` | yes | both | Hex `"0x..."` or decimal string (`NumericUtils.parseHexOrDecNumber`); wider than 32 bits allowed (Platåberget `0x1a6a8cc6e`). |
| `networkId` | `Long` | `network-id` | yes | both | Number or decimal string (`BlockchainConfig.scala:279-281`). The implicit `1L` fallback is kept for GT-1 but the validator requires the key (`MissingRequiredField`). |
| `genesis` | `GenesisRef` (§3) | `custom-genesis-file`, `genesis-hash` | file/inline yes; hash optional (recommended) | both | |
| `forkSchedule` | the keys `fromRawConfig` reads | `*-block-number`, `*-timestamp`, `terminal-total-difficulty`, `difficulty-bomb-*`, `merge-netsplit-block-number`, `arrow-glacier-block-number`, `gray-glacier-block-number` | see §5 | both / ETH | Values are decimal strings (block numbers) or integers (timestamps), as today. |
| `blobSchedule` | `BlobSchedule` | `blob-schedule { cancun prague bpo1 bpo2 }` | ETH, when a blob fork is declared | eth | Already validated by `BlobSchedule.fromConfig` (`BlockchainConfig.scala:353-355`). |
| `defaultSyncMode` | `SyncModeDefault(doSnapSync: Boolean)` | `sync { do-snap-sync }` | **yes** (FR-007) | both | Injected into the deployment config when the profile is silent (R-5). Only `do-snap-sync` is allowed under `sync` in an entry. |
| `bootstrapNodes` | `Set[String]` | `bootstrap-nodes` | yes (may be `[]`) | both | enode URLs. |
| `dnsDiscoveryDomains` | `Seq[String]` | `dns-discovery-domains` | no | both | |
| `mess` | `MESSConfig` | `mess { enabled, ecbp1100-block-number, ecbp1100-deactivate-block-number, ecbp1100-reactivate-block-number }` | **required for `etc`, forbidden for `eth`** (DC-2) | etc | `enabled` required inside the block; the three window keys optional. Tuning keys nobody reads (`decay-constant`, `max-time-delta`, `min-weight-multiplier`, `gorgoroth-chain.conf:221-230`) are `UnknownField`. |
| `resourceProfiles` | `Map[Role, ResourceProfile]` (§4) | `resources { ingest replica archive sim }` | no | both | Defaults fukuii never enforces (FR-007). |
| `blockchainConfig` | `BlockchainConfig` | the whole entry | derived | both | `BlockchainConfig.fromRawConfig(entry)` unchanged (R-1). |
| other `fromRawConfig` keys | as today | `pow-target-time`, `max-code-size`, `dao {...}`, `account-start-nonce`, `monetary-policy {...}`, `gas-tie-breaker`, `eth-compatible-storage`, `allowed-miners`, `treasury-address`, `base-fee-floor`, `min-tip`, `deposit-contract-address`, `gpo-default-price`, `spiral-gas-target`, `olympia-gas-target`, `olympia-gas-limit-elasticity` | per §5 | both | Every key `fromRawConfig` reads is a known key; nothing else is. |

**Deployment keys that MUST NOT appear in an entry** (FR-007, `DeploymentKeyInChainSpec`): anything under
`network.*` except nothing (ports, `server-address`, `rpc`, `engine-api`, `peer`, `discovery`), `datadir`,
`db.*`, `mining.*`, `sync.*` other than `do-snap-sync`, `pruning.*`, `logging.*`, `metrics.*`, `keystore.*`.

**Invariants** (each has a named error, §6):
1. `family` agrees with `consensus`.
2. `consensus = ethash-pow` ⇒ `mess` present with `enabled`; `pow-target-time` present and not `null`; no
   `*-timestamp` key and no `terminal-total-difficulty` (today's `ChainConfigMatrixSpec` property becomes a load rule).
3. `consensus = engine-api` ⇒ no `mess` block; `terminal-total-difficulty` present.
4. Every key is known for the family (`UnknownField`); every required key is present (`MissingRequiredField`).
5. `genesis-hash`, when declared, equals the hash of the genesis header computed from the referenced genesis
   (`GenesisMismatch`).
6. A key appears once across the built-in set and the operator directory unless shadowing is allowed
   (`ChainKeyCollision`).

---

## 3. `GenesisRef`

| Variant | HOCON | Notes |
| --- | --- | --- |
| `File(path: Path, declaredHash: Option[ByteString])` | `custom-genesis-file = "chains/sepolia-genesis.json"` + optional `genesis-hash = "0x..."` | Built-in: resolved file-then-classpath as today (`GenesisDataLoader.scala:63-66`). Operator: a relative path resolves against the entry file's directory; absolute paths as given. Parsed once with json4s from a `Reader` (no `mkString` copy); `customGenesisJsonOpt` stays `None` as today. |
| `Inline(json: String, declaredHash: Option[ByteString])` | `custom-genesis-file = { ... }` (object) | Today's object form, rendered concise (`BlockchainConfig.scala:268-270`). |
| `Default` | key absent | `blockchain/default-genesis.json` (`GenesisDataLoader.scala:77-80`). Only `etc` uses it today. |

`genesis-hash` is a `0x`-prefixed 32-byte hex string. Validation computes the header hash with
`GenesisDataLoader.prepareHeader` over an in-memory `MptStorage` (same code path the node uses on first start, so the
two cannot disagree) and compares. Optional in this release so no built-in needs a value to load; the plan adds the
hash to every built-in (slice 4) and `fetch-genesis.sh` already knows Ephemery's (`EXPECTED_HASH`,
`fetch-genesis.sh:289`).

---

## 4. `ResourceProfile` (FR-007; optional defaults, never enforced)

```hocon
resources {
  ingest  { heap-hint = "8g",  engine-memory-budget = "6g" }
  replica { heap-hint = "6g",  engine-memory-budget = "4g", retention-window = "24h" }
  archive { heap-hint = "16g", engine-memory-budget = "12g" }
  sim     { heap-hint = "6g",  engine-memory-budget = "4g" }
}
```

| Field | Type | Roles | Reader |
| --- | --- | --- | --- |
| `heapHint` | HOCON size string (`"8g"`), parsed with `Config.getBytes` | any | Orbita / operator tooling; `fukuii chains list --verbose` prints it |
| `engineMemoryBudget` | size string | any | Orbita; maps onto `db.rocksdb.memory-budget` (`InstanceConfig.scala:187-189`) only if the operator copies it — fukuii never does |
| `retentionWindow` | HOCON duration (`"24h"`); 026's default is 24 h of blocks | `replica` only (`UnknownField` elsewhere) | spec 026 (`ChainSpec.resourceProfile(Role.Replica).retentionWindow`) |

Unknown keys inside a profile are `UnknownField`. Absent profiles are `Map.empty`; 026 applies its own default.

---

## 5. Required and known keys per family (the `ChainSpecSchema` tables)

`requiredKeys(consensus)`:

- **both**: `consensus`, `chain-id`, `network-id`, `custom-genesis-file` *(or* absent → default genesis, `etc` only;
  the validator requires it for every entry whose `chainKey != "etc"`)*, `sync.do-snap-sync`, `bootstrap-nodes`,
  `account-start-nonce`, `monetary-policy.{first-era-block-reward, first-era-reduced-block-reward,
  first-era-constantinople-reduced-block-reward, era-duration, reward-reduction-rate}`, `gas-tie-breaker`,
  `eth-compatible-storage`, and every block-number key `fromRawConfig` reads without a `Try`
  (`BlockchainConfig.scala:242-301`): `frontier`, `homestead`, `eip106`, `eip150`, `eip155`, `eip160`, `eip161`,
  `byzantium`, `constantinople`, `istanbul`, `atlantis`, `agharta`, `phoenix`, `petersburg`,
  `difficulty-bomb-pause/continue/removal`, `ecip1099`, `muir-glacier`, `magneto`, `berlin`, `mystique`, `spiral`
  (`-block-number`).
- **`ethash-pow`** adds: `pow-target-time` (non-null), `mess.enabled`.
- **`engine-api`** adds: `network-type = "eth"` (explicit, so the family is visible), `terminal-total-difficulty`.

`knownKeys` = `requiredKeys(both families)` ∪ the optional keys in §2 ∪ `dao.*` (`DaoForkConfig`, `Config.scala:332-
348`) ∪ `blob-schedule.{cancun,prague,bpo1,bpo2}.{target,max,base-fee-update-fraction}` ∪ `mess.*` (four keys) ∪
`resources.<role>.*` ∪ `genesis-hash`. The JSON Schema's flattened `properties` must equal this set (R-2).

---

## 6. Validation error catalogue (`ChainSpecError`, sealed ADT)

Every error renders as `[<Name>] <message>` through `ConfigValidator.validate` (one path; `Fukuii.scala:56-59`) and
is listed in [contracts/errors.md](./contracts/errors.md) with its message template and exit status. Fields in the
ADT are what the template needs; `source` is the entry's `ChainSpecSource`.

| Name | Fields | Raised when | FR / DC |
| --- | --- | --- | --- |
| `RegistryDirectoryMissing` | `path` | `custom-chains-dir` set, path absent or not a directory | edge case |
| `EntryParseError` | `source`, `hoconMessage` | HOCON parse failure (today swallowed, `Config.scala:390-394`) | DC-1 |
| `ChainKeyFileNameMismatch` | `source`, `fileKey`, `innerKey` | operator file whose top-level object key differs from the file name (only when the file wraps its body in `blockchains.<key> {}`) | DC-3 |
| `ChainKeyCollision` | `chainKey`, `builtInSource`, `operatorSource` | same key in both sets, shadowing off | FR-001 |
| `UnknownChainKey` | `chainKey`, `known: Seq[String]` | selected network not in the registry | FR-002 |
| `UnknownField` | `source`, `path` | key not in `knownKeys` for the family (includes `finality-rule`, `storage-scheme`, `mining.*`, `network.*`) | FR-004 |
| `DeploymentKeyInChainSpec` | `source`, `path` | a known deployment key in an entry (specialisation of `UnknownField` with a pointer to the profile) | FR-007 |
| `MissingRequiredField` | `source`, `path`, `consensus` | required key absent | FR-004 |
| `UnknownConsensusType` | `source`, `value`, `supported = Seq("ethash-pow","engine-api")` | `consensus` not in the enum (e.g. `clique`) | FR-003 |
| `FamilyConsensusMismatch` | `source`, `family`, `consensus` | `network-type` disagrees with `consensus` | FR-004 |
| `TimestampForkOnBlockDispatchChain` | `source`, `key` | `*-timestamp` or `terminal-total-difficulty` on `ethash-pow` | DC-4 |
| `MessBlockMissing` | `source` | `ethash-pow` entry without `mess.enabled` | DC-2 |
| `MessBlockOnEngineApiChain` | `source` | `engine-api` entry with a `mess` block | DC-2 |
| `GenesisFileUnreadable` | `source`, `path`, `cause` | referenced genesis missing / unreadable / not JSON | DC-1 |
| `GenesisMismatch` | `source`, `declared`, `computed` | `genesis-hash` ≠ computed header hash; also datadir genesis ≠ ChainSpec genesis | FR-004, FR-005 |
| `ProfileConsensusMismatch` | `chainKey`, `key` (`mining.protocol` \| `sync.snap-sync.storage-scheme`), `profileValue`, `profileOrigin`, `derivedValue`, `consensus` | profile sets a consensus-derived key to an incompatible value | DC-5 |
| `EngineApiNotConfigured` | `chainKey`, `missingKeys` | `engine-api` chain, role `ingest` (single-node default), `network.engine-api.enabled` false or `jwt-secret-path` unset; skipped by offline tools (`chains validate`, `import`, `export`) | edge case |
| `DatadirChainKeyMismatch` | `recorded`, `requested`, `datadir` | AppState `chain.spec.chainKey` ≠ selected key | DC-3, DC-6 |
| `ForkScheduleIncompatible` | `fork`, `recorded`, `requested`, `head` (number or timestamp), `datadir` | geth `CheckCompatible` rule: activation changed and recorded or requested activation ≤ head | FR-005, DC-6 |

Errors are **collected, not short-circuited**, per entry and across entries (a `validate` run reports all of them), with
one exception: a parse error stops further checks on that entry.

---

## 7. Datadir compatibility record (`DatadirChainRecord`)

Stored in AppState (`AppStateStorage`, string→string, `db/storage/AppStateStorage.scala:17-27`), keys added to
`AppStateStorage.Keys` beside spec 022's markers (`:652-655`):

| Key | Value | Written |
| --- | --- | --- |
| `chain.spec.chainKey` | `chainKey` | first genesis store; backfill |
| `chain.spec.genesisHash` | `0x` + 64 hex of the stored genesis header hash | same |
| `chain.spec.forkSchedule` | `v1;<name>=<value>;...` sorted by `<name>`; names are the HOCON keys (`atlantis-block-number`, `shanghai-timestamp`, `terminal-total-difficulty`, ...); values decimal; `Long.MaxValue` / `10^18` sentinels and `None` rendered as `-` | same; rewritten after a compatible change |

Written in one `updateSync` batch (the `onFirstStored` hook, `GenesisDataLoader.scala:128`, already runs after the
genesis write). Read-only on every open; the `v1;` prefix lets a later version re-render. The check
(`DatadirCompatibility.check(record, spec, head): Either[ChainSpecError, Option[DatadirChainRecord]]`) is a pure
function unit-tested against a table of (recorded, requested, head) cases, with `Some(newRecord)` meaning "compatible
with a change; rewrite".

**Head** for the comparison: block forks use `appStateStorage.getBestBlockNumber()`; timestamp forks use the best
header's `unixTimestamp` found through `blockNumberMappingStorage` → `blockHeadersStorage` (as `StdNode.scala:428-434`
does). On a datadir mid-SNAP (`isSnapSyncDone == false`) the best block is the pivot (comment at `StdNode.scala:157-
165`); the rule still holds (the pivot has been synced past).

---

## 8. Registry

```
ChainRegistry(
  builtIn:  Map[chainKey, ChainSpec],       // from blockchains.conf includes on the classpath
  operator: Map[chainKey, ChainSpec],       // from custom-chains-dir, every *-chain.conf
  allowShadow: Boolean,                     // fukuii.blockchains.registry.allow-shadow
  warnings: List[String]                    // shadowing notices
)
  def keys: SortedSet[String]
  def spec(chainKey): Either[UnknownChainKey, ChainSpec]
  def toBlockchainsConfig(network): BlockchainsConfig   // the adapter InstanceConfig uses
```

`ChainRegistry.load(blockchainsConfig: TypesafeConfig): Either[NonEmptyList[ChainSpecError], ChainRegistry]`. The
operator directory is read in file-name order; a directory listed but missing is `RegistryDirectoryMissing` (today a
WARN, `Config.scala:398-400`).
