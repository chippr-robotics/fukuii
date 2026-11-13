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

fukuii ships the current iteration inside the jar (spec 028), so `fukuii ephemery` needs no setup, and after a reset a
restarted node fetches, validates and starts the next iteration itself; see
[Quick start: built in](#quick-start-built-in). `scripts/ephemery/fetch-genesis.sh` is the optional way to pin or
prepare an iteration by hand: it runs `fukuii cli ephemery-fetch`, which downloads a release and generates the chain
config and genesis fukuii reads, in a `custom-chains-dir`.

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
3. **A fetched one.** After the reset of every iteration it holds, fukuii fetches the iteration whose window contains
   now (see [Refresh after a reset](#refresh-after-a-reset)), validates it, keeps it under
   `<datadir>/releases/ephemery-N` and runs it. While an iteration is current fukuii makes no network call at all.

The startup report is one INFO line: iteration, chain id, genesis hash, where it came from (`bundled`, `cache`,
`fetched`), the reset time in UTC, the local clock, the datadir, and the consensus-layer directory (`CL dir <path>`) or,
for the bundled iteration, the one command that fetches it: `fukuii cli ephemery-fetch --release ephemery-N`.

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
datadirs by name, at each start.

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
iteration has ended and the next cannot be had now), 78 (permanent refusal) and 1 (the existing configuration errors, and
unexpected or I/O failures, which a restart may clear).

## Refresh after a reset

At the reset the node exits 75, the supervisor restarts it, and the new process picks the iteration. When none it holds
is current, it does this inside one budget, `fukuii.ephemery.refresh-timeout` (default 60 s, bounded to 10 to 300 s):

1. It works out the iteration `K` whose window contains now, from the newest iteration it knows:
   `K = N + floor((now - genesis_N) / interval)`. Several resets behind, it goes straight to the newest.
2. It downloads
   `https://github.com/ephemery-testnet/ephemery-genesis/releases/download/ephemery-K/testnet-all.tar.gz`. These asset
   downloads do not count against GitHub's API limit.
3. It unpacks the archive with a strict reader (regular files and directories only; every path, size and ratio is
   checked) and validates the release with the same rules `fukuii cli ephemery-fetch` applies, including the genesis
   hash against the published one. The consensus-layer files go to `releases/ephemery-K/testnet/`, without
   `parsedBeaconState.json` and the encrypted `bootnode-keys/`.
4. If the release's own window does not contain now (the interval changed), it asks the GitHub API for the latest
   release once, and accepts that one if its window does. Otherwise it exits 69. A 404 for `ephemery-K` itself gets no
   API fallback, by design: the release is simply not out yet.

Each attempt is retried up to three times (2 s, then 4 s of backoff) for connection errors, timeouts, HTTP 5xx and 429,
a 403 from the API (its rate limit), and a 404 (the release is not published yet). A refusal of the transport (a host
off the allow-list, a non-https hop, too many redirects, a body over the cap) is permanent. A release that is not out
yet exits **69**; one that fails validation, was published again with another genesis, or comes from a bad `source`
exits **78**; a full disk, a held lock or an unexpected exception exits **1**, which a supervisor restarts. A mirror that answers 404 to every request looks the same as "not published yet", so the 69 message for a non-GitHub
source says "or fukuii.ephemery.source is wrong (every request answered 404)": check the base URL and its
`releases/download/ephemery-N/testnet-all.tar.gz` layout. A late release is normal: of the last eight, one came 3 h 42 m after the reset.

The transport is https only, follows at most five redirects and checks every hop against an allow-list (`github.com`,
`api.github.com`, `*.githubusercontent.com`), caps the body, and sends no credentials. No configuration key weakens
any of that.

## Under a supervisor

```ini
# systemd: fukuii.service
[Service]
ExecStart=/opt/fukuii/bin/fukuii ephemery
Restart=on-failure
RestartSec=120
RestartPreventExitStatus=78
```

- **Why 120 s.** A restart that finds the release not published exits 69 after at most one budget and waits 120 s,
  so the loop makes at most about 75 starts over the worst delay seen (3 h 42 m), each asking github.com for at most
  three downloads. systemd's start limit (5 starts in 10 s) is never reached. In the usual case the release is out at
  the reset and the first restart refreshes, so the new iteration is running within about three minutes.
- **Do not cap the retries.** `Restart=on-failure` with no `StartLimit` change is right; a cap such as docker's
  `on-failure:5` would leave the node down for a late release.
- **Docker.** `docker run --restart on-failure` restarts on 78 too, and has no equivalent of
  `RestartPreventExitStatus`. Use it with no retry cap, and watch for exit status 78 in the logs or `docker inspect`:
  it means the operator must act. Kubernetes' `CrashLoopBackOff` backoff is fine as it is.
- **Exit codes:** 75 the reset (restart refreshes), 69 not published or unreachable (restart later), 78 permanent (do
  not restart), 1 an unexpected or I/O failure (a full disk, a held lock, an exception: restartable), and 2 a usage
  error.

## The consensus client across a reset

A reset replaces the beacon chain too, so the consensus client needs the new iteration's `testnet/` directory and a
fresh datadir. Fukuii writes the directory at `<datadir>/releases/ephemery-N/testnet/` and keeps `<datadir>/current`
pointing at the iteration it runs. Run the consensus client as a companion unit that restarts with fukuii, uses
`current/testnet` and keeps one datadir per iteration:

```ini
# lighthouse-ephemery.service
[Unit]
PartOf=fukuii.service
After=fukuii.service

[Service]
# wait for fukuii's Engine API port: it opens only after the startup resolver has chosen (and, after a reset, fetched)
# the iteration and updated `current`, which `After=` alone does not wait for
ExecStartPre=/bin/sh -c 'until nc -z 127.0.0.1 8551; do sleep 2; done'
TimeoutStartSec=600
ExecStart=/bin/sh -c 'ITER=$(basename "$(readlink /data/ephemery/current)"); \
  exec lighthouse bn --testnet-dir /data/ephemery/current/testnet \
    --datadir /data/ephemery-lighthouse/$ITER \
    --execution-endpoint http://127.0.0.1:8551 --execution-jwt /data/ephemery/jwt.hex \
    --checkpoint-sync-url https://checkpoint-sync.ephemery.ethpandaops.io'
Restart=on-failure
RestartSec=30

[Install]
WantedBy=fukuii.service
```

This unit needs `nc` (netcat) on the host and the Engine API enabled in fukuii (`mining.protocol = "engine-api"`, the
default in `conf/ephemery.conf`). Without them the `ExecStartPre` waits until `TimeoutStartSec`, and then the unit
fails.

`PartOf=fukuii.service` stops and restarts the consensus client whenever fukuii restarts. The `ExecStartPre` is what
makes it pick up the new iteration: `After=` only waits for fukuii's JVM to be started, not for its startup resolver,
which after a reset may spend up to the refresh budget (60 s by default) fetching the next iteration. Without the wait
the companion could read the old `current` and start on the old iteration's `testnet/` and datadir. The Engine API port
(8551 here; use your `fukuii.network.engine-api.port`, 8561 in the generated `ephemery-local.conf`) opens only once the
node itself is starting, after `current` has been updated, and the old node has stopped before the new one starts, so
the port cannot be the old node's. The datadir named after `readlink /data/ephemery/current` (`ephemery-166`, ...) is
empty for a new iteration, which is what a new chain needs. (`/data/ephemery` stands for your `fukuii.datadir`.) The
bundled iteration has no `testnet/` until you run `fukuii cli ephemery-fetch --release ephemery-N` once; every iteration
fukuii fetches has it. If a `testnet/` is torn (a required file such as `genesis.ssz` is missing or empty), fukuii
reports it as absent and the same command replaces it, keeping the torn one beside it as `.testnet-torn-<k>`.

## Paths that move at each reset

Every path derived from `fukuii.datadir` follows the iteration unless you set it as an absolute path:

| Key or file | Default | At each new iteration | To keep it across iterations |
|-------------|---------|-----------------------|------------------------------|
| `db.rocksdb.path` | `${fukuii.datadir}/rocksdb/` | new, by design | n/a |
| `tmpdir` | `${fukuii.datadir}/tmp` | new | not needed |
| `node-key-file` | `${fukuii.datadir}/node.key` | new: a new enode | set an absolute path |
| `keystore.keystore-dir` | `${fukuii.datadir}/keystore` | new: accounts do not carry over | set an absolute path |
| `network.rpc.ipc.socket-file` | `${fukuii.datadir}/fukuii.ipc` | moves | set an absolute path |
| `static-nodes.json` | `${fukuii.datadir}/static-nodes.json` | seeded: copied from `<root>/static-nodes.json` into a new iteration's datadir if that file exists, never overwritten | put it at `<root>/static-nodes.json` |
| `logging.logs-dir` | `${fukuii.datadir}/logs` | pinned to `<root>/logs` | n/a |
| Engine API `jwt-secret-path` | unset (a random secret each start) | unaffected, unless your conf writes `${fukuii.datadir}/...` | use an absolute path |

An operator conf that substitutes `${fukuii.datadir}` into any other key gets the iteration's directory.

## Pinning and preparing by hand

`scripts/ephemery/fetch-genesis.sh` is optional now. It is `exec fukuii cli ephemery-fetch` with `--out` defaulted
(`$FUKUII_EPHEMERY_DIR`, else `~/.fukuii/ephemery-network`), and writes the layout of section 1. Launch with
`FUKUII_CONF_DIR=<out>/current/conf` or set `fukuii.blockchains.custom-chains-dir`, and fukuii runs that directory as it
always did: no fetch, no exit at the reset. Without `--out`, `fukuii cli ephemery-fetch [--release ephemery-N]` writes
into the node's own root (`<datadir>/releases/ephemery-N/`, with `testnet/`), which is also how you get the
consensus-layer directory of the bundled iteration, or prepare the next iteration before a reset.

## Offline use and opting out

- `fukuii.ephemery.refresh = false`: never contact the network. fukuii runs the newest current cached or bundled
  iteration and, after its reset, exits 78 naming `fukuii cli ephemery-fetch --from-dir DIR`.
  `ephemery-fetch` itself is not stopped by it: the operator asked.
- `fukuii.ephemery.source = "/absolute/dir"`: an unpacked release (`genesis.json`, `retention.vars`,
  `deposit_contract_block_hash.txt`, ...). No network at all, and `refresh = false` does not stop it. A release in it
  that has ended exits 78.
- `fukuii.ephemery.source = "https://mirror.example.org/base"`: a mirror laid out as
  `<base>/releases/download/ephemery-N/testnet-all.tar.gz`. Every hop must be https, to any host (the mirror's own
  redirects are its business, including to a loopback or link-local address, so point it only at a mirror you trust).
  A source with `http:`, credentials, a query or a fragment is refused with exit 78.
- `fukuii.ephemery.exit-at-reset = false`: warn at the reset and keep running, for a run with no supervisor.

## Proxies

There are no fukuii keys. The JVM properties `https.proxyHost`, `https.proxyPort` and `http.nonProxyHosts` apply; when
they are unset, `HTTPS_PROXY` / `https_proxy` and `NO_PROXY` / `no_proxy` are read from the environment. A proxy URL
with credentials, or a scheme the JDK cannot speak, is refused at start (exit 78) with the variable named.

## Trying it without touching your own datadir

A trial run of `fukuii ephemery` or `fukuii cli ephemery-fetch` writes under `fukuii.datadir` (default
`~/.fukuii/ephemery`). The JVM takes its home from the account database, not from `$HOME`, so setting `HOME` does not
move it. Use `JAVA_OPTS="-Duser.home=/scratch/home -Dlogging.logs-dir=/scratch/logs"` (or `-Dfukuii.datadir=...`). The
launcher also appends one "Loading network configuration" line to the default network's log file before it reads those
options (known, B-241); `fetch-genesis.sh` and `--out` runs write everything else under the `--out` directory.

## 1. Fetch the current iteration

`OUT` below is the script's output root (absolute; `--out`, default `~/.fukuii/ephemery-network`).

```bash
scripts/ephemery/fetch-genesis.sh                      # the current iteration -> ~/.fukuii/ephemery-network
scripts/ephemery/fetch-genesis.sh --release ephemery-164 --out /chipprbots/blockchain/ephemery
```

Requires bash >= 4 and the fukuii launcher (`FUKUII_BIN`, default `fukuii` on `PATH`; from a checkout,
`FUKUII_BIN=target/universal/stage/bin/fukuii` after `sbt stage`). The script is `exec fukuii cli ephemery-fetch`: the
download (https only, host allow-list, size caps), the strict unpacking, every rule, the re-encoding and the
genesis-hash check are fukuii's own, the same the node applies at start. The `testnet/` it writes leaves out
`parsedBeaconState.json` and `bootnode-keys/`. It writes `OUT/ephemery-N/` and points `OUT/current` at it:

| Path | What |
|------|------|
| `testnet/` | the release's `testnet-all.tar.gz` members, less `parsedBeaconState.json` and `bootnode-keys/` — also Lighthouse's `--testnet-dir` |
| `chains/ephemery-chain.conf` | generated chain config: chain/network id, fork timestamps, deposit contract, EL bootnodes |
| `chains/ephemery-genesis.json` | the release genesis re-encoded for fukuii (see below) |
| `conf/ephemery.conf` | operator conf: includes the shipped `conf/ephemery.conf`, sets `custom-chains-dir` and a per-iteration `datadir = OUT/ephemery-N/data` |
| `release.env` | `CHAIN_ID`, `GENESIS_HASH` (published), `RESET_TIME`, `BOOTNODE_ENR_LIST`, ... |
| `release.json` | the same facts as JSON, with where the release came from (`source`, `archiveSha256`) |
| `OUT/ephemery-local.conf` | your overrides (ports, JWT); created once, never overwritten, included by every iteration's conf |

Exit codes: `0` written; `1` the output could not be written (nothing of the new iteration is left); `2` a usage
error or no fukuii launcher (set `FUKUII_BIN`); `69` the release is not published yet or the source is not reachable;
`78` the release is refused (the message says why).

Re-running for an iteration that exists keeps its `data/` and replaces only the generated members, all or nothing: the
old `testnet/`, `chains/`, `conf/`, `release.env` and `release.json` are moved aside, the new ones moved in, and if any
step fails the old ones are put back, so `OUT/current` never points at a half-written iteration. A failure after that
point (updating `current`, creating `ephemery-local.conf`) is a warning: the iteration is written. The script's own log
goes to `OUT/logs`, so it never touches a node's log. `retention.vars` must carry `CHAIN_ID`,
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

At `RESET_TIME` the old chain is gone, and the node (under a supervisor, see [Under a supervisor](#under-a-supervisor))
restarts onto the next iteration by itself. The consensus client needs a fresh datadir for the new chain: use the
companion unit above, or stop it, wipe or drop its datadir (it holds the old chain's state) and start it on
`<datadir>/current/testnet`. fukuii's datadir is per iteration (`<datadir>/ephemery-N`), so the new iteration starts
empty; a datadir holding another iteration's genesis is refused at startup ("Genesis data present in the database does
not match"). fukuii never deletes the old `<datadir>/ephemery-N/` and `releases/ephemery-N/` directories: delete them
yourself when done. With a prepared directory (section 1) nothing restarts for you: re-run `fetch-genesis.sh`, then
restart both clients.

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
