# Importing an upstream (go-ethereum) genesis.json into fukuii

Applies whenever a chain's genesis comes from another client's `genesis.json` (Platåberget, Ephemery,
any ethpandaops devnet). Hit twice; both times the only symptom was a wrong genesis hash.

## G1 — header quantities are parsed as HEX, with or without `0x`

`GenesisDataLoader.prepareHeader` reads `timestamp`, `gasLimit`, `difficulty`, `baseFeePerGas`,
`blobGasUsed`, `excessBlobGas` with `BigInt(s.replace("0x", ""), 16)`. go-ethereum accepts decimal for
these, and genesis generators emit decimal timestamps (`"1790276400"`). Loaded verbatim: timestamp
`0x1790276400` (year ~5100), wrong hash, **no error**. Re-encode to `0x`-hex before shipping/loading
(`plataberget-genesis.json` was hand-converted; `scripts/ephemery/fetch-genesis.sh` converts and
cross-checks against `chainspec.json`). Do not "fix" the loader to accept decimal without a sweep of
every genesis file it reads — that changes the meaning of existing un-prefixed values.

## G2 — `mixhash` vs `mixHash`

geth writes `mixhash`; `GenesisData` extracts `mixHash` (case-sensitive) and silently defaults to zero.
Harmless while upstream is zero; rename it anyway.

## G3 — the chain `config` block is not read

Fork times, chain id, deposit contract and `blobSchedule` must be mapped into a `*-chain.conf` by hand
or by a generator (`scripts/ephemery/fetch-genesis.sh` does all four). `blobSchedule` maps to
`blob-schedule { <fork> { target, max, base-fee-update-fraction } }` (#1637): lower-case fork keys,
`baseFeeUpdateFraction` → `base-fee-update-fraction`, decimal values. The loader refuses a conf that
declares a blob fork (`cancun-`/`prague-`/`bpo1-`/`bpo2-timestamp`) without its entry.

Traps (`BlobSchedule.fromConfig`):
- **Named-fork entries.** EIP-7892's example and Ephemery's genesis declare `osaka` (and some fixtures
  `amsterdam`); go-ethereum master's `BlobScheduleConfig` has no such field and silently ignores them —
  named forks inherit the latest cancun/prague/bpoN entry. fukuii accepts them only when equal to what
  they inherit and refuses otherwise: honouring a differing value would split from geth.
- **bpo3+** (`bpo3Time`, `blobSchedule.bpo3`) is not supported and is refused, not ignored.
- **Per-tx vs per-block.** The schedule's `max` is per BLOCK; the per-transaction cap (6, EIP-7594
  `params.BlobTxMaxBlobs`) is a protocol constant and not part of it.
- EEST fixtures carry `config.blobSchedule` with capitalised keys (`Cancun`, `BPO1`) and hex values;
  `EestBlockchainReplay.withFixtureBlobSchedule` feeds them through the same loader.

## G4 — the proof is the hash

Every genesis import needs a spec that builds block 0 through `GenesisDataLoader` and asserts the
state root first (alloc read correctly) then the hash (header shape/fields), against a value from the
release or a live node — see `PlatabergetGenesisSpec`, `EphemeryGenesisSpec`. Add the fork-id
checksums from the live node's `eth_config` when available.
