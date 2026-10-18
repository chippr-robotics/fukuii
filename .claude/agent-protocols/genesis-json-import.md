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
or by a generator. `blobSchedule` CANNOT be mapped: blob target/max/update-fraction per fork are
hard-coded to mainnet/Sepolia values (EngineApiService, OpCode BLOBBASEFEE, BlobGasUtils). A chain with
different BPO values (Ephemery: BPO1 8/12/6676955, BPO2 10/15/8346193) diverges once blob usage
reaches the target. Compare and report; do not assume.

## G4 — the proof is the hash

Every genesis import needs a spec that builds block 0 through `GenesisDataLoader` and asserts the
state root first (alloc read correctly) then the hash (header shape/fields), against a value from the
release or a live node — see `PlatabergetGenesisSpec`, `EphemeryGenesisSpec`. Add the fork-id
checksums from the live node's `eth_config` when available.
