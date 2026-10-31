# Contract: `fukuii chains` CLI (FR-006)

Two read-only subcommands, dispatched by `App.main` (`App.scala:245-296`, new `case Some("chains")`) to
`com.chipprbots.ethereum.chainspec.ChainsCli`, built with decline like `cli/CliCommands.scala:109-111`. Neither
opens a datadir, binds a port or starts an actor system. Output goes to stdout (not the logger, so it is pipeable);
diagnostics go to stderr.

```
fukuii chains list     [--json] [--verbose]
fukuii chains validate [<dir>] [--json] [--datadir <path>]
fukuii chains --help
```

Both honour `FUKUII_CONF_DIR` / `-Dfukuii.conf.dir`, `-Dconfig.file`, `-Dfukuii.blockchains.custom-chains-dir=<dir>`
and `-Dfukuii.blockchains.registry.allow-shadow=true` exactly as the node does (`App.scala:51-69`), so what
`validate` sees is what `fukuii <chainKey>` will see.

## `fukuii chains list`

Loads the registry (built-in entries plus the configured operator directory) and prints one row per `chainKey`,
sorted by key. With a broken registry it prints the rows it could load, then the errors (stderr), and exits `1`.

```
CHAIN        CHAIN-ID     FAMILY  CONSENSUS   FINALITY         SNAP  SOURCE
etc          61           etc     ethash-pow  genesis-only     yes   classpath:conf/base/chains/etc-chain.conf
eth          1            eth     engine-api  consensus-layer  no    classpath:conf/base/chains/eth-chain.conf
gorgoroth    1337         etc     ethash-pow  genesis-only     no    classpath:conf/base/chains/gorgoroth-chain.conf
hive         1337         eth     engine-api  consensus-layer  no    classpath:conf/base/chains/hive-chain.conf
mordor       63           etc     ethash-pow  genesis-only     yes   classpath:conf/base/chains/mordor-chain.conf
plataberget  7091047534   eth     engine-api  consensus-layer  no    classpath:conf/base/chains/plataberget-chain.conf
plataberget2 7091047534   eth     engine-api  consensus-layer  no    /etc/fukuii/chains/plataberget2-chain.conf
sepolia      11155111     eth     engine-api  consensus-layer  no    classpath:conf/base/chains/sepolia-chain.conf
test         1337         etc     ethash-pow  genesis-only     yes   classpath:conf/base/chains/test-chain.conf
```

(`CHAIN-ID` values above are illustrative except for the public chains; `SNAP` is the ChainSpec's `sync.do-snap-sync`
default.) `--verbose` adds `GENESIS-HASH`, `NETWORK-ID`, `STORAGE` (derived scheme) and one indented line per
declared resource profile. `--json` prints a JSON array of objects with keys `chainKey`, `chainId` (decimal string),
`networkId`, `family`, `consensus`, `finalityRule`, `forkDispatch`, `storageScheme`, `defaultSnapSync`,
`genesisHash` (nullable), `source`, `shadowed` (boolean: an operator entry shadowing a built-in), `resources`
(object, may be empty). A shadowed built-in is not listed; the shadowing WARN goes to stderr.

Exit: `0` registry loaded; `1` at least one entry failed to load; `2` usage error.

## `fukuii chains validate [<dir>]`

Runs the full loader (every check in `errors.md` except the datadir ones) without starting a node and reports **every**
error, not the first. `<dir>` overrides `custom-chains-dir` for this run. `--datadir <path>` additionally opens that
datadir **read-only** and runs the DC-6 compatibility check for the selected chain (`fukuii.blockchains.network`, or
`--chain` once 023 lands); it never writes the record.

```
$ fukuii chains validate /etc/fukuii/chains
OK    etc          classpath:conf/base/chains/etc-chain.conf
OK    mordor       classpath:conf/base/chains/mordor-chain.conf
...
ERROR mynet        /etc/fukuii/chains/mynet-chain.conf
      [UnknownConsensusType] consensus = "clique" is not provided by this build; supported: ethash-pow, engine-api
      [MessBlockMissing] ETC-family entry must declare mess { enabled = <bool> } (the default is false)
ERROR sepolia      /etc/fukuii/chains/sepolia-chain.conf
      [ChainKeyCollision] chain 'sepolia' is defined twice: built-in classpath:conf/base/chains/sepolia-chain.conf
      and operator /etc/fukuii/chains/sepolia-chain.conf; remove one, or start with --chain-registry-allow-shadow
      (development only)
2 of 10 entries invalid
```

`--json` prints `{ "entries": [ { "chainKey", "source", "ok", "errors": [ { "name", "message", "path"? } ] } ],
"registryErrors": [...], "ok": boolean }`.

Exit: `0` every entry valid (and, with `--datadir`, compatible); `1` at least one validation error; `3` datadir
incompatibility only (every entry valid, `--datadir` check refused); `2` usage error or `<dir>` not a directory.

## `fukuii --help`

The `Networks:` block (`App.scala:158-166`) is replaced by the registry's keys, one per line with the family and
consensus, read the same way `chains list` reads them; when the registry cannot load, the block says so and points to
`fukuii chains validate`.

## Interaction with the launcher (FR-002)

`fukuii <word>` where `<word>` is not a command, modifier or option flag is read as a `chainKey` (research R-4). If
the registry has no such key the node exits `1` with:

```
[UnknownChainKey] 'foo' is not a chain in the registry (known: etc, eth, gorgoroth, hive, mordor, plataberget,
sepolia, test). An unrecognised launcher word is read as a chain key; run 'fukuii chains list' or 'fukuii --help'.
```
