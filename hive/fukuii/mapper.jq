# mapper.jq — Convert geth-format genesis.json to fukuii-compatible genesis.json
# Simplified version that passes through alloc as-is (fukuii handles the format)
{
  "difficulty": .difficulty,
  "gasLimit": .gasLimit,
  "nonce": (.nonce // "0x0000000000000000"),
  "timestamp": (.timestamp // "0x0"),
  "coinbase": (.coinbase // "0x0000000000000000000000000000000000000000"),
  "mixHash": (.mixHash // "0x0000000000000000000000000000000000000000000000000000000000000000"),
  "extraData": (.extraData // "0x"),
  "ommersHash": "0x1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347",
  "alloc": (.alloc // {})
}
