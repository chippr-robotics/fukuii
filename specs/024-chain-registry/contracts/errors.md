# Contract: named load-time errors (DC-1, DC-2, DC-5, DC-6; SC-003)

All errors are values of the sealed ADT `com.chipprbots.ethereum.chainspec.ChainSpecError` and are reported through
**one path**: `ConfigValidator.validate(rawFukuiiConfig): List[String]` (`ConfigValidator.scala:53-58`), whose caller
logs each line at ERROR and exits (`Fukuii.scala:56-59`). The registry step runs **before `object Config` is
initialised**, so no `RocksDbDataSource` can have been constructed and no port bound (research R-1). The datadir
checks (last two rows) are the exception by nature: they read the open database, run first in `StdNode.start`
(before `loadGenesisData`, `StdNode.scala:60`) and still exit before any listener or actor system.

Rendering: `[<Name>] <message>`; one line per error; every message names the field or file it is about and, where
the fix is a flag or a key, names that too. Messages are stable text (tests match on `[<Name>]` and on the named
field, not on the whole line).

| Name | Message template | Exit | Stage |
| --- | --- | --- | --- |
| `RegistryDirectoryMissing` | `custom-chains-dir '<path>' does not exist or is not a directory (fukuii.blockchains.custom-chains-dir)` | 1 | registry load |
| `EntryParseError` | `<source>: cannot parse HOCON: <hoconMessage>` | 1 | registry load |
| `ChainKeyFileNameMismatch` | `<source>: file name says chain '<fileKey>' but the entry is keyed '<innerKey>'` | 1 | registry load |
| `ChainKeyCollision` | `chain '<key>' is defined twice: built-in <builtInSource> and operator <operatorSource>; remove one, or start with --chain-registry-allow-shadow (development only)` | 1 | registry load |
| `UnknownChainKey` | `'<key>' is not a chain in the registry (known: <k1>, <k2>, ...). An unrecognised launcher word is read as a chain key; run 'fukuii chains list' or 'fukuii --help'.` | 1 | selection |
| `UnknownField` | `<source>: unknown field '<path>' for consensus <consensus>; see contracts/chainspec.schema.json` | 1 | entry validation |
| `DeploymentKeyInChainSpec` | `<source>: '<path>' is a deployment key (ports, datadir, RPC, peers, mining, sync other than do-snap-sync) and must live in the profile, not in the ChainSpec` | 1 | entry validation |
| `MissingRequiredField` | `<source>: required field '<path>' is missing (required for consensus <consensus>)` | 1 | entry validation |
| `UnknownConsensusType` | `<source>: consensus = "<value>" is not provided by this build; supported: ethash-pow, engine-api` | 1 | entry validation |
| `FamilyConsensusMismatch` | `<source>: network-type = "<family>" disagrees with consensus = "<consensus>" (ethash-pow => etc, engine-api => eth)` | 1 | entry validation |
| `TimestampForkOnBlockDispatchChain` | `<source>: '<key>' is a timestamp-dispatch key; consensus ethash-pow dispatches forks by block number` | 1 | entry validation |
| `MessBlockMissing` | `<source>: ETC-family entry must declare mess { enabled = <bool> } (the default is false; ECIP-1100 stays off unless enabled = true)` | 1 | entry validation |
| `MessBlockOnEngineApiChain` | `<source>: 'mess' is ETC-family only; remove the block from this engine-api entry` | 1 | entry validation |
| `GenesisFileUnreadable` | `<source>: genesis '<path>' cannot be read: <cause>` | 1 | entry validation |
| `GenesisMismatch` | `<source>: genesis-hash <declared> does not match the genesis built from '<path>' (<computed>)` — or, from the datadir: `datadir <datadir> holds genesis <recorded>; chain '<key>' has genesis <computed>` | 1 (entry) / 3 (datadir) | entry validation / datadir open |
| `ProfileConsensusMismatch` | `chain '<key>' (consensus <consensus>) derives <key> = <derived>, but the profile sets <profileValue> at <profileOrigin>; remove the key from the profile` | 1 | profile derivation |
| `EngineApiNotConfigured` | `chain '<key>' is engine-api but <missingKeys> are not set (fukuii.network.engine-api.enabled / jwt-secret-path); an ingest node needs a consensus client` | 1 | profile derivation (skipped by `chains validate`, `import`, `export`) |
| `DatadirChainKeyMismatch` | `datadir <datadir> was created for chain '<recorded>', not '<requested>'; use a different datadir` | 3 | datadir open |
| `ForkScheduleIncompatible` | `datadir <datadir> has synced past <fork> (head <head>); recorded activation <recorded>, ChainSpec says <requested>. Refusing: a fork already applied cannot move. Fix the entry or use a fresh datadir.` | 3 | datadir open |

Exit statuses: `0` ok; `1` configuration or registry error (today's `System.exit(1)` at `Fukuii.scala:59`); `2` CLI
usage (as `CheckpointCli`, `:80`); `3` datadir compatibility refusal — distinct so Orbita can tell "fix the config"
from "this datadir belongs to another schedule".

Warnings (never fatal, logged at WARN through the same validator): shadowing (`chain '<key>': operator entry <path>
shadows built-in <resource> (--chain-registry-allow-shadow)`); a genesis without `genesis-hash` (`<source>: no
genesis-hash declared; the loader cannot check the genesis file against the entry`); removed keys
(`ConfigValidator.removedKeyWarnings`, unchanged).

## SC-003 test obligations

For every row with stage "entry validation", "profile derivation" or "selection" a test in
`ChainSpecValidatorSpec` / `DeploymentDerivationSpec` / `ChainRegistrySpec` asserts: the error is returned (not
thrown), its `[<Name>]` prefix and the named field appear in the rendering, and `Files.exists(tmpDatadir)` is false
after `ConfigValidator.validate` on a config whose `db.rocksdb.path` points at a fresh temp path. For the "datadir
open" rows `DatadirCompatibilitySpec` drives the pure check with a table and `DatadirCompatibilityNodeSpec`
(`IntegrationTest`) opens an `EphemDataSource`-backed `Storages` with a recorded schedule and asserts the exit path
is taken before `StdNode` builds its actor system (the node is constructed with a hook that records whether
`loadGenesisData` ran).
