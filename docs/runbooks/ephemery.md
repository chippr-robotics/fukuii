# Running fukuii on Ephemery

Ephemery is a public ETH testnet that **resets to a new genesis every 28 days** (2,419,200 s). Each
iteration `N` is a new chain: chain id `39438000 + N`, a new genesis timestamp, new BPO timestamps and a
new genesis hash, published as release `ephemery-N` of
[ephemery-testnet/ephemery-genesis](https://github.com/ephemery-testnet/ephemery-genesis/releases).
The current iteration, chain id and next reset are at <https://ephemery.dev/latestInfo.php>.

Shape of the chain (checked for `ephemery-164`, 2026-09-24 → 2026-10-22): merged at genesis
(TTD 0), Shanghai, Cancun, Prague and Osaka active at genesis, BPO1 at CL epoch 2048 (~9.1 days in) and
BPO2 at epoch 4096 (~18.2 days in), no Amsterdam. Deposit contract
`0x00000000219ab540356cBB839Cbe05303d7705Fa`, ~284 genesis accounts including the system contracts.

From `ephemery-165` (2026-10-22) every fork through Amsterdam, BPO1 and BPO2 included, is active at genesis, so block 0 has
the 23-field header. Check any new iteration the same way: the hash fukuii computes must equal the release's published one,
`GENESIS_HASH` in `release.env`.

**Traffic is close to zero.** On 2026-10-17 (iteration 164, head ~133,300) a sample of every 50th block
from genesis found 3 transactions in 2,666 blocks, and the last 2,000 blocks had none (no blobs). Importing
Ephemery exercises empty-block processing, system calls and withdrawals; anything that needs real
execution load (e.g. an import differential) must generate it — fund an account from
<https://faucet.bordel.wtf> or <https://ephemery-faucet.pk910.de> and send your own transactions.

fukuii ships the current iteration inside the jar (spec 028), so `fukuii ephemery` needs no setup; see
[Quick start: built in](#quick-start-built-in). `scripts/ephemery/fetch-genesis.sh` is the optional way to pin or
prepare an iteration by hand: it downloads a release and generates the chain config and genesis fukuii reads, in a
`custom-chains-dir`.

## Quick start: built in

```bash
fukuii ephemery          # nothing to prepare
```

At start, before the node's configuration loads, fukuii picks the Ephemery iteration to run:

1. **A directory you prepared.** If `fukuii.blockchains.custom-chains-dir` is set and holds `ephemery-chain.conf`
   (for example by launching with `FUKUII_CONF_DIR=<out>/current/conf`, as the sections below describe), fukuii uses it
   exactly as before: no fetch, no exit at the reset. It logs a WARN when that iteration has ended.
2. **The newest current iteration it holds**: a cached one under `<datadir>/releases/ephemery-N`, or the iteration
   built into the jar (`ephemery-165`, until 2026-11-19T19:00:00Z). The built-in one is copied into the cache on
   first use.
3. After that iteration's reset, and while this build cannot fetch the next one yet, fukuii refuses to start (exit 78)
   and names the manual way: `fetch-genesis.sh`, then `FUKUII_CONF_DIR=<out>/current/conf`.

The startup report is one INFO line: iteration, chain id, genesis hash, where it came from (`bundled`, `cache`), the
reset time in UTC, the local clock, the datadir, and where the consensus-layer directory is or how to get it.

**The root and its layout.** `<datadir>` here is `fukuii.datadir` as you set it (default `~/.fukuii/ephemery`):

| Path | What it is |
|------|------------|
| `<datadir>/releases/ephemery-N/` | one validated iteration (`chains/`, `release.json`, `testnet/` when present); never modified or deleted by fukuii |
| `<datadir>/ephemery-N/` | the node's datadir for iteration N: `rocksdb/`, `node.key`, `keystore/`, `tmp/`; never deleted by fukuii |
| `<datadir>/logs/` | the logs, pinned here for every iteration |
| `<datadir>/current` | a symlink to the iteration last selected |
| `<datadir>/static-nodes.json` | copied into each new iteration's datadir, never overwritten there |

Every path derived from `fukuii.datadir` moves with the iteration, unless you set it as an absolute path (the node key,
the keystore, the IPC socket, the JWT secret). The full table, with what to set to keep each one across resets, is in
the configuration contract of spec 028 (`contracts/config.md`, "What moves at each reset"). A datadir from before
built-in Ephemery (`<datadir>/rocksdb`) is not used and not touched; fukuii logs it, and the earlier iterations'
datadirs with their sizes, at each start.

**At the reset the node exits with code 75** (`EX_TEMPFAIL`): it logs a WARN, then shuts down through its normal
shutdown hooks, which close RocksDB. Run it under a supervisor that restarts it, so that it picks up the next iteration:

```ini
# systemd
Restart=on-failure
RestartSec=120
RestartPreventExitStatus=78
```

Exit 78 (`EX_CONFIG`) is permanent, so the unit must not restart on it; Docker's `--restart on-failure` cannot exclude
it, so watch for 78 in the logs. Do not cap the retries. Set `fukuii.ephemery.exit-at-reset = false` to only log a WARN
at the reset and keep running (for a run with no supervisor). Exit codes are 0 (a normal stop), 75 (the reset), 69 (the
iteration has ended and the next cannot be had now), 78 (permanent refusal) and 1 (the existing configuration errors).

## 1. Fetch the current iteration

`OUT` below is the script's output root (absolute; `--out`, default `~/.fukuii/ephemery-network`).

```bash
scripts/ephemery/fetch-genesis.sh                      # latest release -> ~/.fukuii/ephemery-network
scripts/ephemery/fetch-genesis.sh --release ephemery-164 --out /chipprbots/blockchain/ephemery
```

Requires bash >= 4, `curl`, `tar` and the fukuii launcher (`FUKUII_BIN`, default `fukuii` on `PATH`; from a
checkout, `FUKUII_BIN=target/universal/stage/bin/fukuii` after `sbt stage`). The script downloads and unpacks the
release; every rule, the re-encoding and the genesis-hash check are fukuii's own (`fukuii cli ephemery-fetch`). It
writes `OUT/ephemery-N/` and points `OUT/current` at it:

| Path | What |
|------|------|
| `testnet/` | the release's `testnet-all.tar.gz`, verbatim — also Lighthouse's `--testnet-dir` |
| `chains/ephemery-chain.conf` | generated chain config: chain/network id, fork timestamps, deposit contract, EL bootnodes |
| `chains/ephemery-genesis.json` | the release genesis re-encoded for fukuii (see below) |
| `conf/ephemery.conf` | operator conf: includes the shipped `conf/ephemery.conf`, sets `custom-chains-dir` and a per-iteration `datadir = OUT/ephemery-N/data` |
| `release.env` | `CHAIN_ID`, `GENESIS_HASH` (published), `RESET_TIME`, `BOOTNODE_ENR_LIST`, ... |
| `release.json` | the same facts as JSON, with where the release came from (`source`, `archiveSha256`) |
| `OUT/ephemery-local.conf` | your overrides (ports, JWT); created once, never overwritten, included by every iteration's conf |

Exit codes: `0` written; `1` the output could not be written (nothing of the new iteration is left); `2` a usage
error or no fukuii launcher (set `FUKUII_BIN`); `78` the release is refused (the message says why).

Re-running for an iteration that exists keeps its `data/` and replaces only the generated members, all or nothing: the
old `testnet/`, `chains/`, `conf/`, `release.env` and `release.json` are moved aside, the new ones moved in, and if any
step fails the old ones are put back, so `OUT/current` never points at a half-written iteration. A failure after that
point (updating `current`, creating `ephemery-local.conf`) is a warning: the iteration is written. The script's own log
goes to a scratch directory, so it never touches a node's log. `retention.vars` must carry `CHAIN_ID`,
`ITERATION_NUMBER` and `GENESIS_TIMESTAMP` (every release does).

The script refuses (non-zero exit, nothing written) a release whose genesis declares something fukuii
cannot represent: a non-zero block fork, an unknown fork key (e.g. `bpo3Time`), a non-zero TTD, or a header
quantity it cannot re-encode.

**Why a re-encoded genesis.** `GenesisDataLoader` parses `timestamp`, `gasLimit`, `difficulty`,
`baseFeePerGas`, `blobGasUsed` and `excessBlobGas` as hex even without `0x`; Ephemery writes the
timestamp as the decimal string `"1790276400"`. Loaded verbatim, fukuii builds a genesis with timestamp
`0x1790276400` and a hash no peer accepts, without any error. The script converts those fields to
`0x`-hex (and cross-checks against `chainspec.json` and `retention.vars`); `mixhash` becomes `mixHash`.

## 2. Run fukuii

Set the JWT path in `OUT/ephemery-local.conf` (`fukuii.network.engine-api.jwt-secret-path`) and create
the secret once: `openssl rand -hex 32 > OUT/jwt.hex`.

```bash
FUKUII_CONF_DIR=OUT/current/conf java -XX:+UnlockDiagnosticVMOptions -XX:-ReduceAllocationMerges \
  -jar fukuii-assembly.jar ephemery
```

The two `-XX` flags are required on JDK 25 (a C2 miscompile; see
[known-issues Issue 16](known-issues.md#issue-16-jdk-25-c2-reduceallocationmerges-workaround)) and belong
in the systemd unit's `ExecStart` too.

For a systemd unit, use `Environment=FUKUII_CONF_DIR=OUT/current/conf` and `... fukuii-assembly.jar ephemery`
like `fukuii-sepolia.service`. The first start should log the generated chain and `Genesis data successfully
loaded`; check block 0's hash with `eth_getBlockByNumber 0x0` (port 8556) against `GENESIS_HASH` in `release.env`.

Ports in the generated `ephemery-local.conf`, chosen so they do not collide with the other units on the
soak host (fukuii-sepolia / fukuii-plataberget / fukuii-mordor use p2p 30303, RPC 8546, WS 8552,
Engine API 8551, metrics 13798; the Lighthouse containers use 9000 / 5052 / 5054):

| fukuii | port | Lighthouse | port |
|--------|------|------------|------|
| p2p + discovery | 30313 | p2p (tcp/udp) | 9010, quic 9011 |
| JSON-RPC HTTP | 8556 | HTTP API | 5062 |
| JSON-RPC WS | 8557 | metrics | 5064 |
| Engine API | 8561 | | |
| metrics | 13799 | | |

Even with distinct ports, the host rule from the Platåberget soak still holds: do not run this next to
another node or an sbt build on the 16 GB soak host.

## 3. Run Lighthouse

Lighthouse has no built-in Ephemery network; use the release directory as `--testnet-dir`. It reads the CL
bootnodes from `boot_enr.yaml` in that directory, so no `--boot-nodes` flag is needed (`release.env` also
carries them as `BOOTNODE_ENR_LIST`, from the release's `boot_enr.txt`, for other CL clients). From `ephemery-165`
Amsterdam and its consensus counterpart Gloas are active at genesis, so the CL must support them:
`sigp/lighthouse:v8.3.0-rc.0` followed `ephemery-165` to the head (2026-11-01).

```bash
source OUT/current/release.env      # sets OUT_DIR (absolute), TESTNET_DIR
docker run -d --name ephemery-lighthouse --network host \
  -v "$OUT_DIR/ephemery-lighthouse":/data \
  -v "$TESTNET_DIR":/network:ro \
  -v "$OUT_DIR/jwt.hex":/jwt.hex:ro \
  sigp/lighthouse:v8.3.0-rc.0 \
  lighthouse bn --testnet-dir /network --datadir /data \
    --execution-endpoint http://127.0.0.1:8561 --execution-jwt /jwt.hex \
    --checkpoint-sync-url https://checkpoint-sync.ephemery.ethpandaops.io \
    --port 9010 --quic-port 9011 \
    --http --http-address 127.0.0.1 --http-port 5062 \
    --metrics --metrics-address 127.0.0.1 --metrics-port 5064
```

Other checkpoint endpoints (all served the 164 genesis on 2026-10-17): `https://checkpointz.bordel.wtf`,
`https://ephemery.beaconstate.ethstaker.cc`, `https://beaconstate-ephemery.chainsafe.dev`.

## 4. After a reset

At `RESET_TIME` the old chain is gone. Stop both clients, re-run `fetch-genesis.sh`, wipe or drop the
Lighthouse datadir (it holds the old chain's state), and start again. fukuii's datadir is per iteration
(`OUT/ephemery-N/data`), so the new iteration starts empty; a datadir holding another iteration's
genesis is refused at startup ("Genesis data present in the database does not match"). Delete old
`OUT/ephemery-N/` directories when done with them.

## 5. Run a validator on fukuii

fukuii can be the execution client of a validator: the Lighthouse beacon node from section 3 drives it over the
Engine API, and a Lighthouse validator client signs. This was run end to end on `ephemery-165` (#1655 part 2,
2026-11-05 to 2026-11-10). Every step that put a request or a withdrawal on chain was compared field by field with
geth v1.17.9 at its inclusion block. All matched: hash, stateRoot, receiptsRoot, withdrawalsRoot, requestsHash, the
block access list hash and the receipt.

| Step | What it needs | Seen on `ephemery-165` |
|---|---|---|
| Keys | `lighthouse validator-manager create` with `--compounding` (0x02 credentials), `--eth1-withdrawal-address <addr>` and `--deposit-gwei`; then `validator-manager import` into the running validator client over its HTTP API | one validator, 335 ETH, then a 500 ETH top-up |
| Deposit (EIP-6110) | a transaction to the deposit contract from a funded account, carrying the `deposits.json` fields and its `deposit_data_root` | block 90654; 260,654 gas |
| Activation | wait for the deposit to be processed and the activation epoch | about 1 h: deposit at 22:26Z, active from epoch 3191 at 23:32Z (short queue) |
| Partial withdrawal (EIP-7002) | a transaction from the withdrawal address to `0x00000961Ef480Eb55e80D19ad83579A64c007002` | requested in block 102304, paid in block 109778 |
| Exit (EIP-7002, amount 0) | the same call with amount 0, once no partial is pending | requested in block 115065; exit epoch 4050 |
| Full-balance sweep | wait for the withdrawable epoch (exit epoch + 256, about 27 h) | 834.07 ETH paid at the first slot of epoch 4306, block 122552 |

The validator client, with the fee recipient set to an address you control:

```bash
docker run -d --name ephemery-vc --network host \
  -v "$TESTNET_DIR":/network:ro -v "$VALIDATOR_DIR":/v \
  sigp/lighthouse:v8.3.0-rc.0 \
  lighthouse --testnet-dir /network vc --datadir /v/vc \
    --beacon-nodes http://127.0.0.1:5062 \
    --suggested-fee-recipient <addr> --graffiti <text> \
    --http --http-port 5072 --init-slashing-protection
```

The graffiti can be changed later without a restart through the keymanager API
(`POST /eth/v1/validator/{pubkey}/graffiti`). The API token is in the validator client's datadir, so keep it out of logs.

### EIP-7002 requests

A request is a transaction from the validator's withdrawal address to the request contract:
- **Calldata:** the 48-byte validator pubkey followed by the amount in gwei as an 8-byte big-endian integer. Amount 0
  asks for a full exit.
- **Value:** the current fee. An `eth_call` to the contract with empty calldata returns it as a uint256. It was 1 wei
  on `ephemery-165`.
- **Gas:** the request cost 575,581 gas on `ephemery-165`, because Amsterdam's state-creation gas applies. geth
  estimated 581,316, so send it with a 1,000,000 gas limit.
- **Exits:** under EIP-7251, the consensus layer ignores an exit while a partial withdrawal for the same validator is
  still pending. Wait until the partial is paid (`withdrawal_done` for it in the beacon API, or the withdrawal in an
  execution block) before you send the exit.

Before 0.11.0, fukuii's `eth_call` reported an out-of-gas as success ("0x"), so a dry run with too little gas looked
fine on fukuii and failed on geth. Fixed in #1725.

### Watching it

- **Beacon node:** `GET /eth/v1/beacon/states/head/validators/<index>` shows the status, balance, exit epoch and
  withdrawable epoch.
- **Payouts:** from Gloas (`ephemery-165`) the beacon block body has no execution payload. Find a payout in the
  execution blocks instead: the `withdrawals` list of `eth_getBlockByNumber`, matched on `validatorIndex`.
- **Proposals:** the expected interval scales with the network's total stake. With ~30.7M ETH staked on `ephemery-165`,
  835 ETH proposes about once every 122 h, so a short run may see no proposal at all. None was seen in this run.

## Blob schedule

Ephemery's blob parameters differ from mainnet's at the BPO forks. `fetch-genesis.sh` copies
genesis.json `config.blobSchedule` verbatim into the generated chain config's `blob-schedule` (#1637),
and fukuii takes every blob parameter from there: excess blob gas and blob base fee, the per-block blob
limit in header validation and block building, `BLOBBASEFEE`, `eth_blobBaseFee`, `eth_feeHistory` and
`eth_config`.

| Fork | Ephemery target/max/updateFraction | mainnet / Sepolia |
|------|------------------------------------|-------------------|
| Cancun | 3 / 6 / 3338477 | 3 / 6 / 3338477 |
| Prague, Osaka | 6 / 9 / 5007716 | 6 / 9 / 5007716 |
| BPO1 | 8 / 12 / 6676955 | 10 / 15 / 8346193 |
| BPO2 | 10 / 15 / 8346193 | 14 / 21 / 11684671 |

The script refuses a schedule entry for a fork fukuii cannot schedule (bpo3 and later) or with a missing
or non-integer field. fukuii itself refuses to load a chain config whose schedule lacks an entry for a
declared blob fork, has max < target, target or update fraction 0, out-of-order blob-fork timestamps, or
an `osaka` entry different from Prague's (go-ethereum ignores `osaka`, so a differing value would split
from it). A refused custom chain config is logged at ERROR ("Failed to load chain config from
ephemery-chain.conf") and startup then fails with "blockchain network 'ephemery' is not configured".

A chain config generated before #1637 has no `blob-schedule` and is refused this way: re-run
`fetch-genesis.sh` for the current release.

## Sources

- Releases and genesis: <https://github.com/ephemery-testnet/ephemery-genesis/releases>
  (`values.env` on `master` announces the next iteration)
- Network overview, RPC, checkpoint endpoints: <https://github.com/ephemery-testnet/ephemery-resources>
- Reset/retention tooling for other clients: <https://github.com/ephemery-testnet/ephemery-scripts>
- Fixture check: `EphemeryGoldenRenderSpec` renders the raw inputs of `ephemery-164` and `ephemery-165`
  (`src/test/resources/ephemery/releases/`) with the release engine and compares them byte for byte with
  `src/test/resources/ephemery/ephemery-16x/`. A change of the rendered output is a reviewed fixture diff.
- Test: `EphemeryGenesisSpec` builds the `ephemery-164` genesis from the script's output and checks the
  published hash and the live fork ids.
