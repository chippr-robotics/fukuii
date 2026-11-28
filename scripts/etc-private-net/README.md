# Private ETC-config PoW net (spec 018 C6, T244)

A private proof-of-work net with ETC's rules: two or three fukuii miners (real Ethash, each with its own DAG) and one
core-geth, every ETC fork from Homestead to Spiral active at genesis, and Olympia (ECIP-1111/1112/1121/1122) at a block
you choose. It produces the routine depth 1 to 3 forks the exit gate needs. Gate **EG-6** (`tasks.md` T258) runs on it;
see `specs/018-storage-engine-consolidation/plan.md`, "Exit gate".

Nothing here runs in CI, and nothing contacts a public network: discovery is off, the chain has no bootnodes, and the
nodes are wired to each other with `admin_addPeer`.

## Scripts

| Script | What it does |
|---|---|
| `init.sh` | Writes the net's configuration: the fukuii chain file and genesis, one `node.conf` per miner, `nodes.tsv`, `net.env`. Starts nothing. |
| `start.sh` | Starts the nodes (core-geth is `init`ed on first start), refuses a net whose nodes disagree on the chain id or the genesis hash, then runs `connect.sh`. |
| `connect.sh` | `admin_addPeer` both ways between every pair of nodes; waits (bounded) until each has a peer. |
| `stop.sh` | SIGTERM, a bounded wait, `--kill` to force. |
| `load.sh` | Sends 1-wei transfers for a bounded time, so that blocks carry gas and the treasury is credited (ECIP-1111 credits `baseFee * gasUsed`; an empty block credits nothing). |
| `make-fork.sh` | Cuts one miner off, lets both sides mine `--depth` blocks past the split, reconnects, measures the reorg depth and appends it to `NET_DIR/forks.tsv`. |
| `check.sh` | The EG-6 checks over a window of recent blocks (below). Exit 0 only when every one passes. |
| `lib.sh` | Shared helpers, sourced. |

Every script has `--help`. Every wait has a bound and fails loudly when it is reached.

## Required inputs (nothing is defaulted)

| Input | Flag | Why it is not a default |
|---|---|---|
| The fukuii jar under test | `init.sh --fukuii-jar` | |
| The core-geth binary (white-b0x/core-geth `main`, with Olympia) | `init.sh --geth-bin` | |
| core-geth's genesis JSON, written from core-geth's own Olympia config | `init.sh --geth-genesis` | core-geth's config keys for Olympia are not in this repository. fukuii's genesis is **derived** from this file's header fields and allocation. Its chain id must not be a public chain's (1, 61, 63, 11155111 are refused). |
| Olympia's block | `init.sh --olympia-block` | EG-6 names both cases: run the net once with `0` (the genesis on `HefPostOlympia`, a base fee in the genesis) and once with a low `N > 0` (the transition). |
| The ECIP-1112 treasury address | `init.sh --treasury-address` | Must equal core-geth's. |
| MESS (ECBP-1100) | `init.sh --mess never\|N` | Must equal core-geth's; ECIP-1122 ties the reactivation to Olympia, but whether core-geth's config does is for the operator to read. |
| The hosts | `init.sh --host IP` / `--node-host NAME=IP` | The machines are the user's decision (T011, plan open item 4). The default, `127.0.0.1`, puts the whole net on one host. |

Optional: `--era-duration` (default 5,000,000). With the default the net never reaches an ECIP-1017 era boundary; to
cross one, set a short era (for example 100) in **both** the core-geth genesis (`ecip1017EraRounds`, which `init.sh`
checks when present) and here.

## Running it

```bash
D=/path/to/net
scripts/etc-private-net/init.sh --net-dir "$D" --fukuii-jar fukuii.jar --geth-bin core-geth \
  --geth-genesis olympia-genesis.json --olympia-block 20 --treasury-address 0x... --mess never
scripts/etc-private-net/start.sh --net-dir "$D"          # also connects the nodes
scripts/etc-private-net/load.sh --net-dir "$D" --duration 3600 &
for d in 1 2 3 1 2 3; do scripts/etc-private-net/make-fork.sh --net-dir "$D" --depth "$d"; done
scripts/etc-private-net/check.sh --net-dir "$D" --window 32
scripts/etc-private-net/stop.sh --net-dir "$D"
```

On a net spread over hosts: run `init.sh` once with `--node-host` for every node and copy `NET_DIR` to each host (the
paths in it are absolute), run `start.sh --no-connect --node NAME` on each host for its nodes, then `connect.sh` from a
host that reaches every node's JSON-RPC. The JSON-RPC then listens on a non-loopback address with `admin`, `personal`
and `miner` enabled: keep the hosts on a private network.

The fukuii nodes log at INFO into `NET_DIR/nodes/<name>/node.log` (the shipped `logback.xml` keeps the consensus loggers
at ERROR, which would hide `REORG-SETTLE`).

## What `check.sh` checks (EG-6)

- Every node, core-geth included, on the same chain id and genesis; with Olympia at 0, a base fee in the genesis.
- Every node names the same block at every height of the window. A block's hash covers its `stateRoot`,
  `receiptsRoot` and ommers, so this is "each mined block's root equals its import root, with ommers" on every node.
  A window with no ommer is reported, not passed silently.
- From Olympia: a base fee at or above the 1 gwei floor in every header and none before; the treasury's balance grows
  by exactly `baseFee * gasUsed` at every block, on a fukuii node and on core-geth.
- ECIP-1017: every miner and ommer balance equal on a fukuii node and on core-geth
  (`scripts/etc-differential/compare-balances.sh diff`).
- The fukuii logs: no `REORG-EXEC-FAIL`, no block refused at ERROR; the reorg depths seen and `forks.tsv` summarised.

## Known limits

- **The net must agree before it means anything.** fukuii's chain file follows `mordor-chain.conf`; core-geth's
  follows the genesis you give it. A fork-rule difference shows as a genesis or fork-id mismatch at start, or as the
  first block on which `check.sh` sees two hashes. The ECIP-1122 gas-limit elasticity at the Olympia block is not
  settled on the ETC side (`BlockHeaderValidatorSkeleton.validateGasLimit`): fukuii applies none, as on Mordor.
- `make-fork.sh` records the depth it measured before the links came back; a side that mined on while reconnecting
  reorganises a little deeper. The `REORG-SETTLE` lines in the logs give the depth each node actually moved.
- Built and checked offline only: `shellcheck`, `init.sh` against a stand-in genesis (its HOCON parsed with Typesafe
  config: every key `BlockchainConfig.fromRawConfig` reads without a default is present, and the node keys resolve
  against `application.conf`), and `check.sh` against a mock JSON-RPC net (pass,
  and a fail for a forked node, a wrong treasury credit and a `REORG-EXEC-FAIL` line). `start.sh`, `connect.sh`,
  `make-fork.sh` and `load.sh` have not run against real nodes: that is T258's first run, on the hosts of T011.
