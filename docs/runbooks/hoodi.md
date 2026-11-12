# Running fukuii on Hoodi

Hoodi is a public Ethereum testnet (chain id and network id 560048), post-merge from genesis (2025-03-17 12:00:00 UTC),
used to test validator and staking changes. fukuii has it built in: no genesis file, no chain config to fetch.

Shape of the chain (eth-clients/hoodi and go-ethereum `params.HoodiChainConfig`, checked 2026-11-12): merged at genesis
(TTD 0), Shanghai and Cancun active at genesis, Prague at 1742999832, Osaka at 1761677592, BPO1 at 1762365720, BPO2 at
1762955544. **No Amsterdam is scheduled.** Deposit contract `0x00000000219ab540356cBB839Cbe05303d7705Fa`. Genesis hash
`0xbbe312868b376a3001692a646dd2d7d1e4406380dfd86b98aa8a34d1557c971b`.

## 1. Launch the execution client

```bash
fukuii hoodi -Dfukuii.datadir=/chipprbots/blockchain/hoodi
```

This loads `conf/hoodi.conf`: `storage-scheme = "path"`, eth/70 and eth/71 offered, `mining.protocol = "engine-api"`,
and the same sync defaults as Sepolia. Peers come from four static enodes and DNS discovery
(`all.hoodi.ethdisco.net`).

## 2. Enable the Engine API

Blocks arrive from a consensus-layer client, so the Engine API must be on and share a JWT secret with it. Give the
secret as an **absolute path**:

```hocon
fukuii.network.engine-api {
  enabled = true
  jwt-secret-path = "/chipprbots/blockchain/hoodi/jwt.hex"
}
```

The default Engine API port is 8551. See `fukuii-engine-api-setup`.

## 3. Pair a consensus client

Lighthouse, with a checkpoint so the CL reaches the head quickly:

```bash
lighthouse bn --network hoodi \
  --execution-endpoint http://127.0.0.1:8551 \
  --execution-jwt /chipprbots/blockchain/hoodi/jwt.hex \
  --checkpoint-sync-url https://hoodi.checkpoint.ethpandaops.io
```

Without a CL the execution client does not reach the head.

## 4. Check it

- `eth_chainId` returns `0x88bb0`; `net_version` returns `560048`.
- Block 0's hash is the one above (`eth_getBlockByNumber` with `0x0`).
- The log lists the Hoodi bootstrap nodes, and `admin_peers` shows peers after a minute or two.

## Bootnodes

Four static `:30303` enodes are shipped: go-ethereum's three legacy EF bootnodes and Lodestar's. eth-clients/hoodi also
lists five EF NodeOps enodes written `@ip:0?discport=N`, with TCP port 0. fukuii does not list them: a bootstrap node is
also handed to the peer dialler, which would dial port 0 (pinned by `HoodiBootnodeSpec`). DNS discovery finds those
nodes through their ENRs instead.
