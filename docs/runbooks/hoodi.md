# Running fukuii on Hoodi

Hoodi is a public Ethereum testnet (chain id and network id 560048), post-merge from genesis (2025-03-17 12:00:00 UTC),
used to test validator and staking changes. fukuii has it built in: no genesis file, no chain config to fetch.

Shape of the chain (eth-clients/hoodi and go-ethereum `params.HoodiChainConfig`, checked 2026-11-12): merged at genesis
(TTD 0), Shanghai and Cancun active at genesis, Prague at 1742999832, Osaka at 1761677592, BPO1 at 1762365720, BPO2 at
1762955544. **No Amsterdam is scheduled.** Deposit contract `0x00000000219ab540356cBB839Cbe05303d7705Fa`. Genesis hash
`0xbbe312868b376a3001692a646dd2d7d1e4406380dfd86b98aa8a34d1557c971b`.

## 1. Launch the execution client

`-D` options are JVM options: they go before `-jar`, not after the network name (a `-D` after `hoodi` is a program
argument and sets nothing). The same shape as `docs/for-node-operators/index.md`:

```bash
java -Xmx4g -Dfukuii.datadir=/chipprbots/blockchain/hoodi \
  -jar target/scala-3.9.0/fukuii-assembly-<version>.jar hoodi
```

Without `-Dfukuii.datadir` the datadir is `~/.fukuii/hoodi`. This loads `conf/hoodi.conf`:
- `storage-scheme = "path"`;
- `do-snap-sync = false` (SNAP is off, as on Sepolia; blocks come from the CL and the EL fetches state and bodies from
  peers);
- eth/70 and eth/71 offered;
- `mining.protocol = "engine-api"`;
- `network.engine-api.enabled = true`.

Peers come from four static enodes and DNS discovery (`all.hoodi.ethdisco.net`).

## 2. The Engine API

Blocks arrive from a consensus-layer client over the Engine API, so `conf/hoodi.conf` turns it on
(`fukuii.network.engine-api.enabled = true`). **The Engine API is on by default for this chain, on `localhost:8551`**
(Constitution VI: it is not bound to a public interface).

The JWT secret is a file, as in go-ethereum. With no `jwt-secret-path`, fukuii uses `<datadir>/jwt.hex`
(`~/.fukuii/hoodi/jwt.hex` without `-Dfukuii.datadir`). If the file exists it is loaded; if not, fukuii creates it at the
first start with 32 random bytes as 64 hex characters, mode 0600. It refuses to start, naming the file, when the file
is not exactly 64 hex characters (optionally `0x`-prefixed) or is readable by its group or by others (`chmod 600` it).
The log names the path, never the secret. The consensus client takes `--execution-jwt <datadir>/jwt.hex`, or the
configured path.

To share the secret between hosts, or to keep it where it is when the datadir moves, set an absolute path in an
operator conf that includes the shipped one (as `docs/runbooks/ephemery.md` does) and start with `-Dconfig.file=`:

```hocon
include classpath("conf/hoodi.conf")

fukuii.network.engine-api.jwt-secret-path = "/chipprbots/blockchain/hoodi/jwt.hex"
```

A configured path is read as given, and must already exist: a relative path resolves against the JVM's working
directory, so use an absolute one. The secret must be at least 64 hex characters.

## 3. Pair a consensus client

Lighthouse v8.x (a Hoodi-aware build is required; use the `--network hoodi` of your release), with a checkpoint so the CL reaches the head quickly:

```bash
lighthouse bn --network hoodi \
  --execution-endpoint http://127.0.0.1:8551 \
  --execution-jwt ~/.fukuii/hoodi/jwt.hex \
  --checkpoint-sync-url https://checkpoint-sync.hoodi.ethpandaops.io
```

Without a CL the execution client does not reach the head.

## 4. Check it

- `eth_chainId` returns `0x88bb0`; `net_version` returns `560048`.
- Block 0's hash is the one above (`eth_getBlockByNumber` with `0x0`).
- The log shows `Bootstrap nodes: 4 total (4 config)` (a count, not the nodes), and `admin_peers` shows peers after a minute or two.

## Bootnodes

Four static `:30303` enodes are shipped: go-ethereum's three legacy EF bootnodes and Lodestar's. eth-clients/hoodi also
lists five EF NodeOps enodes written `@ip:0?discport=N`, with TCP port 0. fukuii does not list them: a bootstrap node is
also handed to the peer dialler, which would dial port 0 (pinned by `HoodiBootnodeSpec`). DNS discovery finds those
nodes through their ENRs instead.
