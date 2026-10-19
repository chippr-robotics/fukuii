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

**Traffic is close to zero.** On 2026-10-17 (iteration 164, head ~133,300) a sample of every 50th block
from genesis found 3 transactions in 2,666 blocks, and the last 2,000 blocks had none (no blobs). Importing
Ephemery exercises empty-block processing, system calls and withdrawals; anything that needs real
execution load (e.g. an import differential) must generate it — fund an account from
<https://faucet.bordel.wtf> or <https://ephemery-faucet.pk910.de> and send your own transactions.

fukuii ships **no** Ephemery iteration. `scripts/ephemery/fetch-genesis.sh` downloads a release and
generates the chain config and genesis fukuii reads; `conf/ephemery.conf` (the `fukuii ephemery`
launcher config) loads them from a `custom-chains-dir`.

## 1. Fetch the current iteration

`OUT` below is the script's output root (absolute; `--out`, default `~/.fukuii/ephemery-network`).

```bash
scripts/ephemery/fetch-genesis.sh                      # latest release -> ~/.fukuii/ephemery-network
scripts/ephemery/fetch-genesis.sh --release ephemery-164 --out /chipprbots/blockchain/ephemery
```

Requires bash >= 4, `curl`, `tar`, `jq`. It writes `OUT/ephemery-N/` and points `OUT/current` at it:

| Path | What |
|------|------|
| `testnet/` | the release's `testnet-all.tar.gz`, verbatim — also Lighthouse's `--testnet-dir` |
| `chains/ephemery-chain.conf` | generated chain config: chain/network id, fork timestamps, deposit contract, EL bootnodes |
| `chains/ephemery-genesis.json` | the release genesis re-encoded for fukuii (see below) |
| `conf/ephemery.conf` | operator conf: includes the shipped `conf/ephemery.conf`, sets `custom-chains-dir` and a per-iteration `datadir = OUT/ephemery-N/data` |
| `release.env` | `CHAIN_ID`, `GENESIS_HASH` (published), `RESET_TIME`, `BOOTNODE_ENR_LIST`, ... |
| `OUT/ephemery-local.conf` | your overrides (ports, JWT); created once, never overwritten, included by every iteration's conf |

The script refuses (non-zero exit, nothing written) a release whose genesis declares something fukuii
cannot represent: a non-zero block fork, an unknown fork key (e.g. `bpo3Time`), a non-zero TTD,
Amsterdam at genesis, or a header quantity it cannot re-encode.

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
carries them as `BOOTNODE_ENR_LIST`, from the release's `boot_enr.txt`, for other CL clients). Osaka/Fulu is
active at genesis, so the CL must be Fulu-capable (the `sigp/lighthouse:v8.3.0-rc.0` image already on the
host is).

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

## Known limitation: blob schedule

fukuii hard-codes the mainnet/Sepolia blob parameters per fork (`EngineApiService` blob-gas constants,
`OpCode` `BLOBBASEFEE` update fraction, `BlobGasUtils`); a chain config cannot change them. Ephemery
uses different BPO values, which `fetch-genesis.sh` reports as a WARNING:

| Fork | Ephemery target/max/updateFraction | fukuii |
|------|------------------------------------|--------|
| BPO1 | 8 / 12 / 6676955 | 10 / 15 / 8346193 |
| BPO2 | 10 / 15 / 8346193 | 14 / 21 / 11684671 |

Cancun/Prague/Osaka match. From BPO1 on, the excess-blob-gas and blob-base-fee computations diverge as
soon as a block's blob usage reaches the target, and fukuii accepts more blobs per block than Ephemery
allows. The expected failure is loud — a header `excessBlobGas` mismatch stalls import — not silent.
Ephemery's blob usage was zero in every block sampled on 2026-10-17, so import is expected to match
before and after the BPOs, but only per-chain blob-schedule configuration makes it correct.

## Sources

- Releases and genesis: <https://github.com/ephemery-testnet/ephemery-genesis/releases>
  (`values.env` on `master` announces the next iteration)
- Network overview, RPC, checkpoint endpoints: <https://github.com/ephemery-testnet/ephemery-resources>
- Reset/retention tooling for other clients: <https://github.com/ephemery-testnet/ephemery-scripts>
- Fixture check: `scripts/ephemery/check-fixture.sh` regenerates `ephemery-164` with the current script
  and diffs it against `src/test/resources/ephemery/ephemery-164/` (run after any script change).
- Test: `EphemeryGenesisSpec` builds the `ephemery-164` genesis from the script's output and checks the
  published hash and the live fork ids.
