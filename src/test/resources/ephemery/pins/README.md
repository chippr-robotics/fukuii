# Pinned upstream genesis files

## hoodi-genesis.json

- Source: https://github.com/eth-clients/hoodi, `metadata/genesis.json` on `main`.
- Fetched: 2026-11-12.
- sha256: `f1cb023a971540f1502d9115d1c3d2aae7912422d312e073f6de828140640083`
- Published genesis block hash (go-ethereum `params/config.go` and the same repository):
  `0xbbe312868b376a3001692a646dd2d7d1e4406380dfd86b98aa8a34d1557c971b`.

The RAW upstream file is pinned, not a re-encoded copy, on purpose: it writes the timestamp as the decimal string
`"1742212800"`, the difficulty as `"0x01"` and `mixhash` in lowercase, so it exercises the release encoder
(`GenesisJsonEncoder`) on a real genesis. Its fork schedule has Shanghai and Cancun at genesis and Prague after it, so
block 0 is a Cancun-shape header: `GenesisHeaderExtractionSpec` pins that header shape to the published hash.

Spec 028 slice S1 ships a separate, re-encoded `hoodi-genesis.json` with the built-in network; this file is a test
input only.
