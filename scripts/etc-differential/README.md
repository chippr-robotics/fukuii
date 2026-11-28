# ETC rewards and era-boundary differential (spec 018 C6, T246)

`compare-balances.sh` reads, for every block of the ranges given, the balance of the block's miner and of each of its
ommers' miners, at the block's parent and at the block. Those are the accounts ECIP-1017 pays (the block reward, the
1/32 nephew bonus, the ommer reward), so a reward computed differently anywhere in a range shows as a difference. It
feeds T238 (B-381, the comparison with the pinned jar), EG-1 and EG-6.

| Mode | Use |
|---|---|
| `record --rpc URL --out FILE --label TEXT RANGES` | Record the values from the reference node: the **pinned jar** (`specs/018-storage-engine-consolidation/baselines/pinned-jar.md`, fukuii v0.12.1 on its default Hash-scheme config). A recording is never overwritten, and a partial one is never left behind. |
| `compare --rpc URL --recorded FILE` | Read the recorded rows from the node under test and fail on any difference. The genesis must match the recording's. |
| `diff --rpc URL --rpc-b URL RANGES` | Read both from two live nodes (fukuii and core-geth on the private net; `check.sh` uses it). |

Ranges: `--boundary N` (the blocks `N-W .. N+W`, `--window W`, default 64) or `--range A-B`, repeatable.

- **ETC mainnet**: the first era boundary is 5,000,000 (`era-duration` in `src/main/resources/conf/base/chains/etc-chain.conf`).
  Under ECIP-1017, 5,000,000 is the last block of era 0 and 5,000,001 the first of era 1; the window covers both.
- **Mordor**: `era-duration` is 2,000,000 (`mordor-chain.conf`), so its boundaries are multiples of 2,000,000. Which
  two T238 compares is for whoever runs it to name with `--boundary`; the script does not choose.

Exit status: 0 every row equal (or recorded), 1 a difference or a read failure, 2 a usage error. A balance the node
cannot serve is a failure, never a pass.

## Required inputs

- `--rpc` (and `--rpc-b` for `diff`): the nodes. They must serve the state at every block of a range and at the block
  before it: an **archive** node, or a range inside the node's retained history (`pruning.history`, 64 by default).
- `--label` for `record`: what the reference node runs (the jar's version and SHA-256, its config). Required: a
  recording that does not say what produced it is not evidence.
- No recorded values ship with this script. No ETC-mainnet or Mordor recording from the pinned jar exists yet; making
  one needs a pinned-jar node that holds the state around each boundary (B-381).

## Checked offline

`shellcheck`, and the three modes against a mock JSON-RPC node: `record` writes the rows (ommers included), `compare`
and `diff` pass on equal nodes and fail with the differing rows when one balance moves by 1 wei, `record` refuses to
overwrite, and a node that does not answer is an error that writes nothing.
