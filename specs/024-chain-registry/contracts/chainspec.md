# Contract: ChainSpec entry (`<chainKey>-chain.conf`)

A ChainSpec is one HOCON file per chain. The nine launcher names today map to **eight** built-in entries on the
classpath (`conf/base/chains/*.conf`, included by `conf/base/blockchains.conf:17-31`) plus Ephemery, which
`scripts/ephemery/fetch-genesis.sh` generates into the operator directory. Operator entries live in
`fukuii.blockchains.custom-chains-dir` and are named `<chainKey>-chain.conf`; a genesis JSON sits beside them. The
machine-readable shape is [chainspec.schema.json](./chainspec.schema.json) (JSON Schema draft 2020-12, the HOCON
rendered as JSON); `ChainSpecSchemaSyncSpec` keeps it equal to the loader's tables.

Rules of thumb:
- **Chain keys** describe the chain: consensus, forks, genesis, bootnodes, MESS, the default sync mode, optional
  resource defaults. **Deployment keys** (ports, datadir, RPC APIs, peer limits, `mining.*`, `sync.*` other than
  `do-snap-sync`) belong in the profile (`conf/<chainKey>.conf`, or `-D` properties) and are rejected in an entry.
- `consensus` is the single source of truth. `network-type` (the family) must agree with it; `mining.protocol`,
  `storage-scheme`, the validator set and the finality rule are derived from it and cannot be set.
- Unknown keys are errors. Every key below is the complete list.

## Field reference

| Key | Type | Required | Family | Meaning |
| --- | --- | --- | --- | --- |
| `consensus` | `"ethash-pow"` \| `"engine-api"` | yes | both | Consensus type. `ethash-pow`: ETC family, Ethash PoW, block-number fork dispatch, `genesis-only` finality, hash storage scheme. `engine-api`: ETH family, PoS via Engine API, timestamp dispatch, `consensus-layer` finality, path scheme. |
| `network-type` | `"etc"` \| `"eth"` | engine-api: yes; ethash-pow: optional (default `etc`) | both | The family. Read by transaction validation, admission, gas oracle (`NetworkType`). |
| `chain-id` | hex `"0x3d"` or decimal string | yes | both | EIP-155 chain id; any width. |
| `network-id` | integer or decimal string | yes | both | devp2p network id. |
| `custom-genesis-file` | path string or inline genesis object | yes except built-in `etc` | both | geth-format genesis. Operator relative paths resolve against the entry's directory. |
| `genesis-hash` | `0x` + 64 hex | recommended | both | Expected genesis block hash; checked against the genesis file at load and against the datadir on open. |
| `sync.do-snap-sync` | boolean | yes | both | The chain's default sync mode. A profile may override `fukuii.sync.do-snap-sync`. Nothing else is allowed under `sync`. |
| `pow-target-time` | HOCON duration | ethash-pow: yes | etc | Ethash target block time. ETH entries may write `null`. |
| `terminal-total-difficulty` | integer or decimal string | engine-api: yes | eth | EIP-3675 TTD. Forbidden on ethash-pow. |
| `merge-netsplit-block-number` | decimal string | optional | eth | EIP-3675 netsplit block, part of the fork-id chain. |
| `*-timestamp` (`shanghai`, `cancun`, `prague`, `osaka`, `amsterdam`, `bpo1`, `bpo2`) | integer seconds | optional | eth only | Timestamp fork activations. Any of them on an ethash-pow entry is an error. |
| `blob-schedule.{cancun,prague,bpo1,bpo2}` | `{ target, max, base-fee-update-fraction }` | when a blob fork is declared | eth | EIP-7840/7892 blob parameters. |
| `deposit-contract-address` | address | optional | eth | EIP-6110 deposit contract. |
| `*-block-number` (see schema `required`) | decimal string; `"1000000000000000000"` = never | yes (the 24 the loader reads unconditionally) | both | Block-number fork activations. ETH entries carry the ETC forks as sentinels and vice versa. |
| `olympia-block-number`, `arrow-glacier-block-number`, `gray-glacier-block-number` | decimal string | optional | both / eth | Optional block forks (absent → never). |
| `spiral-gas-target`, `olympia-gas-target` | integer or decimal string | optional | etc | Fork-embedded gas-limit targets (ECIP-1121). |
| `olympia-gas-limit-elasticity` | integer | optional | both | EIP-1559 one-shot elasticity at the activation block. |
| `max-code-size` | integer or decimal string | optional | both | EIP-170 limit. |
| `account-start-nonce` | integer or decimal string | yes | both | |
| `monetary-policy { ... }` | object (5 required keys) | yes | both | ECIP-1017 schedule; ETH entries carry inert values. |
| `dao { ... }` | object | optional | both | DAO fork handling. |
| `gas-tie-breaker`, `eth-compatible-storage` | boolean | yes | both | |
| `treasury-address` | address | optional | both | ECIP-1112 treasury; built-ins use `${fukuii.olympia.treasury-address}`. |
| `base-fee-floor`, `min-tip`, `gpo-default-price` | integer or decimal string | optional | both | Fee-market and gas-oracle parameters. |
| `bootstrap-nodes` | array of enode URLs | yes (may be empty) | both | |
| `dns-discovery-domains` | array of `enrtree://` URLs | optional | both | |
| `allowed-miners` | array of hex public keys | optional | etc | `restricted-pow` miner set. |
| `mess { enabled, ecbp1100-block-number, ecbp1100-deactivate-block-number, ecbp1100-reactivate-block-number }` | object; `enabled` required | ethash-pow: yes; engine-api: forbidden | etc | ECIP-1100 MESS. Must be declared explicitly so the choice is visible; the default and the recommended value is `false`. |
| `resources.{ingest,replica,archive,sim}` | `{ heap-hint, engine-memory-budget, retention-window (replica only) }` | optional | both | Resource defaults for Orbita and operators. fukuii never enforces them. |

Derived (not keys; an entry that sets them fails with `UnknownField`): `finality-rule`, `fork-dispatch`,
`storage-scheme`, `mining.protocol`.

## Example: an ETC-family operator entry (`mynet-chain.conf`)

```hocon
{
  consensus = "ethash-pow"
  # network-type defaults to "etc"; may be written explicitly.
  network-id = 424242
  chain-id = "0x67932"

  custom-genesis-file = "mynet-genesis.json"      # beside this file
  genesis-hash = "0x4f0c6d0d1e3a2b7f8c9d0e1f2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d"

  sync { do-snap-sync = true }

  pow-target-time = 15.seconds
  frontier-block-number = "0"
  homestead-block-number = "0"
  eip106-block-number = "1000000000000000000"
  eip150-block-number = "0"
  eip155-block-number = "0"
  eip160-block-number = "0"
  eip161-block-number = "0"
  byzantium-block-number = "0"
  constantinople-block-number = "0"
  petersburg-block-number = "0"
  istanbul-block-number = "0"
  atlantis-block-number = "0"
  agharta-block-number = "0"
  phoenix-block-number = "0"
  ecip1099-block-number = "1000000000000000000"
  muir-glacier-block-number = "1000000000000000000"
  magneto-block-number = "0"
  berlin-block-number = "1000000000000000000"
  mystique-block-number = "0"
  spiral-block-number = "0"
  olympia-block-number = "1000000000000000000"
  difficulty-bomb-pause-block-number = "0"
  difficulty-bomb-continue-block-number = "0"
  difficulty-bomb-removal-block-number = "0"

  max-code-size = "24576"
  account-start-nonce = "0"
  monetary-policy {
    first-era-block-reward = "5000000000000000000"
    first-era-reduced-block-reward = "5000000000000000000"
    first-era-constantinople-reduced-block-reward = "5000000000000000000"
    era-duration = 500000000
    reward-reduction-rate = 0
  }
  gas-tie-breaker = false
  eth-compatible-storage = true
  bootstrap-nodes = [
    "enode://<128 hex>@10.0.0.11:30303",
    "enode://<128 hex>@10.0.0.12:30303"
  ]

  mess { enabled = false }                        # required; false unless you mean it

  resources {
    ingest  { heap-hint = "4g", engine-memory-budget = "2g" }
    replica { heap-hint = "3g", engine-memory-budget = "2g", retention-window = "24h" }
  }
}
```

The profile for it (`mynet.conf`, optional) carries only deployment keys:

```hocon
include classpath("application.conf")
fukuii {
  blockchains { network = "mynet", custom-chains-dir = "/etc/fukuii/chains" }
  datadir = "/var/lib/fukuii/mynet"
  network.rpc.http { enabled = true, port = 8545 }
}
```

## Example: an ETH-family (Engine API) entry — Platåberget as an operator entry (`plataberget2-chain.conf`)

The body is `conf/base/chains/plataberget-chain.conf` with `consensus`, `genesis-hash` added and `mess` removed; the
key `plataberget2` avoids the built-in collision (or start with `--chain-registry-allow-shadow`).

```hocon
{
  consensus = "engine-api"
  network-type = "eth"
  network-id = 7091047534
  chain-id = "0x1a6a8cc6e"

  custom-genesis-file = "plataberget-genesis.json"
  genesis-hash = "0xee33ef92<...>2b31"                 # block 0 hash, plataberget.dev

  sync { do-snap-sync = false }

  terminal-total-difficulty = "0"
  merge-netsplit-block-number = "0"
  pow-target-time = null
  shanghai-timestamp = 0
  cancun-timestamp = 0
  prague-timestamp = 0
  osaka-timestamp = 0
  bpo1-timestamp = 0
  bpo2-timestamp = 0
  amsterdam-timestamp = <from genesis.json config.amsterdamTime>
  blob-schedule {
    cancun { target = 3,  max = 6,  base-fee-update-fraction = 3338477 }
    prague { target = 6,  max = 9,  base-fee-update-fraction = 5007716 }
    bpo1   { target = 10, max = 15, base-fee-update-fraction = 8346193 }
    bpo2   { target = 14, max = 21, base-fee-update-fraction = 11684671 }
  }
  deposit-contract-address = "0x00000000219ab540356cBB839Cbe05303d7705Fa"

  # Every pre-Merge block fork active at genesis ("0") or never ("1000000000000000000").
  frontier-block-number = "0"
  homestead-block-number = "0"
  eip106-block-number = "1000000000000000000"
  eip150-block-number = "0"
  eip155-block-number = "0"
  eip160-block-number = "0"
  eip161-block-number = "0"
  byzantium-block-number = "0"
  constantinople-block-number = "0"
  petersburg-block-number = "0"
  istanbul-block-number = "0"
  muir-glacier-block-number = "0"
  berlin-block-number = "0"
  atlantis-block-number = "1000000000000000000"
  agharta-block-number = "1000000000000000000"
  phoenix-block-number = "1000000000000000000"
  ecip1099-block-number = "1000000000000000000"
  magneto-block-number = "1000000000000000000"
  mystique-block-number = "1000000000000000000"
  spiral-block-number = "1000000000000000000"
  olympia-block-number = "1000000000000000000"
  difficulty-bomb-pause-block-number = "0"
  difficulty-bomb-continue-block-number = "0"
  difficulty-bomb-removal-block-number = "0"
  olympia-gas-limit-elasticity = 2

  max-code-size = "24576"
  account-start-nonce = "0"
  monetary-policy {
    first-era-block-reward = "0"
    first-era-reduced-block-reward = "0"
    first-era-constantinople-reduced-block-reward = "0"
    era-duration = 1
    reward-reduction-rate = 0
  }
  gas-tie-breaker = false
  eth-compatible-storage = true
  bootstrap-nodes = [ "enode://<128 hex>@<host>:30303" ]
  # no mess block on an engine-api entry
}
```

What the loader derives for it: `family = eth`, `finalityRule = consensus-layer`, `forkDispatch = timestamp`,
`storageScheme = path`, `mining.protocol = engine-api`, `sync.do-snap-sync = false` unless the profile says otherwise.
The profile must enable the Engine API (`fukuii.network.engine-api { enabled = true, jwt-secret-path = ... }`) for an
`ingest` node; the loader refuses to start one without it (`EngineApiNotConfigured`).

## Writing an entry (replaces step 3 of the `fukuii-custom-networks` skill)

1. Choose `chainKey` (lowercase, `[a-z][a-z0-9-]*`), `chain-id`, `network-id`.
2. Choose `consensus` from the two values. Pick the matching example above.
3. Write the genesis JSON beside the entry (`fukuii cli generate-allocs` for the `alloc`), reference it, and put its
   block hash in `genesis-hash` (`fukuii chains validate <dir>` prints the computed hash when the declared one is
   missing or wrong).
4. Set `sync.do-snap-sync`, bootnodes, forks; for ETC write `mess { enabled = false }` unless you run MESS.
5. `fukuii chains validate <dir>` until it exits `0`; then `fukuii <chainKey>` (with a profile if you need ports).
