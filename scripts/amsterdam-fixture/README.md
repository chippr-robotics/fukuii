# Amsterdam fixture verification harness

Re-derives every measured figure in [`specs/009-amsterdam-fork-support/`](../../specs/009-amsterdam-fork-support/)
from the reference fixture, so a reader can **check** them rather than trust them.

```bash
python3 scripts/amsterdam-fixture/verify.py <path-to-fixture-dir>
```

Exit 0 = all checks pass. Exit 1 = a check failed. Exit 2 = fixture not found.

## Getting the fixture

The fixture is go-ethereum's devp2p test chain — 600 blocks, 10-second spacing,
`amsterdamTime: 360`, so block 36 is the first Amsterdam block. It is **not** vendored here
(~900 KB, and it belongs to hive). Obtain it from a hive checkout:

```
hive/simulators/devp2p/  →  the ethtest testdata directory
```

The harness needs `chain.rlp`; it additionally uses `headblock.json` when present. The full set
the Phase 0 work used was `chain.rlp`, `txinfo.json`, `headstate.json`, `headblock.json`,
`genesis.json`, `forkenv.json` and `accounts.json`.

## Why it is staged

Stage 1 validates the machinery — keccak, RLP, single-entry MPT root, receipt and bloom
construction — against data whose answer is **already known**: a published keccak vector, and
block 600's 23-field header re-encoded to the hash `headblock.json` independently reports. Only
then do later stages apply it to figures nobody has checked.

This ordering is not ceremony. Four diagnoses earlier in this effort were wrong from reasoning
about source without checking what the harness emits; the ones that held came from decoding the
fixture. A harness that has not been validated on a known answer establishes nothing about an
unknown one.

## What it checks

| Stage | Establishes |
|---|---|
| 1 | The machinery is correct — keccak vector, 600 blocks decode, block 600 header round-trips to its canonical hash, block 35 has 21 items and block 36 has 23 |
| 2 | The live header-decoder defect: block 36's canonical hash is `6372c88f…`, but dropping items 21–22 (what `case n if n >= 21` does today) yields `94844dfd…`. Decoding **succeeds and returns the wrong answer**, which is why the fix is to reject rather than tolerate |
| 3 | **V1** — block 41's receipts root reconstructs only with `cumulativeGasUsed = 326,947`, while the header reports `gasUsed = 183,600`. The header is `max(execution, state)`; receipts are the sum. A single-counter implementation cannot satisfy both |
| 4 | **V3** — blocks 36 and 37 are exact multiples of `GAS_STORAGE_SET` (8× and 5× 97,920), so the state dimension is the maximum in each |
| 5 | **R-1** — the largest gas limit in all 612 transactions is 1,628,065, far below `TX_MAX_GAS_LIMIT` (2²⁴), so the state-gas reservoir is empty throughout and the fixture cannot exercise it |

Stage 3 also demonstrates why EIP-7708 is in scope: the block-41 receipts root does **not**
reconstruct without the system-address transfer log. That measurement is what added FR-019.

## One trap worth naming

`gasLimit` sits at a different RLP index per transaction shape — 2 for legacy, 3 for EIP-2930,
4 for EIP-1559/4844/7702. Reading index 3 on a type-2 transaction returns `maxFeePerGas`, which
looks like a plausible gas limit and silently **inverts** stage 5's conclusion. The first draft
of this harness did exactly that and reported a maximum of 393,266,804, which would have made
R-1 look false. The per-shape table in `verify.py` is load-bearing.

## Files

| File | Contents |
|---|---|
| `keccak.py` | Keccak-256, pure Python, no dependencies |
| `rlp.py` | RLP decode (`decode`, `decode_stream`) and encode (`enc`) |
| `receipts.py` | Bloom construction, receipt encoding, single-entry MPT root, CREATE address derivation |
| `verify.py` | The staged checks above |

Pure standard library — no pip install, no network.
