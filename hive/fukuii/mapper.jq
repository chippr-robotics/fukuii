# mapper.jq — Convert geth-format genesis.json to fukuii-compatible genesis.json
# Hive provides genesis in geth format with config section + alloc
# Fukuii expects: difficulty, gasLimit, nonce, timestamp, coinbase, mixHash, extraData, alloc

{
  difficulty: .difficulty,
  gasLimit: .gasLimit,
  nonce: (.nonce // "0x0000000000000000"),
  timestamp: (.timestamp // "0x0"),
  coinbase: (.coinbase // "0x0000000000000000000000000000000000000000"),
  mixHash: (.mixHash // "0x0000000000000000000000000000000000000000000000000000000000000000"),
  extraData: (.extraData // "0x"),
  ommersHash: "0x1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347",
  alloc: (
    .alloc // {} |
    to_entries |
    map({
      key: .key,
      value: {
        balance: (.value.balance // "0x0")
      } + (if .value.code then { code: .value.code } else {} end)
        + (if .value.nonce then { nonce: .value.nonce } else {} end)
        + (if .value.storage then { storage: .value.storage } else {} end)
    }) |
    from_entries
  )
}
