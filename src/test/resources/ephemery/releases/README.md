# Raw inputs of the Ephemery releases the tests start from

Each directory holds the five execution-layer members of one release, copied unchanged from its published
`testnet-all.tar.gz`: `genesis.json`, `retention.vars`, `deposit_contract_block_hash.txt`, `enodes.txt` and
`boot_enr.txt`. The consensus-layer members (`parsedBeaconState.json`, `genesis.ssz`, and the rest) are not committed.

| Release | Source | sha256 of `testnet-all.tar.gz` |
|---------|--------|--------------------------------|
| ephemery-164 | https://github.com/ephemery-testnet/ephemery-genesis/releases/download/ephemery-164/testnet-all.tar.gz | `47d065271db046351ca01415aad7fe8e469807021ffddde8644382edaf5ea1bf` |
| ephemery-165 | https://github.com/ephemery-testnet/ephemery-genesis/releases/download/ephemery-165/testnet-all.tar.gz | `c076b69e5cf37a27c1162ad37d8a8432ae086f80eefb53a388579fc4b6cc8757` |

Downloaded 2026-11-12.

`EphemeryGoldenRenderSpec` renders these inputs with the Ephemery release engine and compares the result byte for byte
with the committed `../ephemery-164/` and `../ephemery-165/` (the chain configuration and genesis the node reads). The
published genesis hash of a release is its `deposit_contract_block_hash.txt`.
