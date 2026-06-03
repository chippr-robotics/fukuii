---
name: fukuii-mining-operations
description: >-
  Validate and control Ethereum Classic (ETC) PoW/Ethash mining on a Fukuii node
  — check mining state, start/stop the internal miner, set the coinbase, inspect
  hashrate and work, and wire up external miners. Use when asked to start/stop
  mining, verify a miner is producing blocks, change the reward address, or debug
  why mining isn't working. ETC-only (PoW); status checks are read-only,
  start/stop/set-etherbase are reversible writes under the guarded-write protocol.
---

# Fukuii mining operations

Read `../CONVENTIONS.md` first. ETC is PoW/Ethash — mining is real here (never on
ETH-mainnet PoS networks). Status reads are 🟢; `miner_start`/`miner_stop`/
`miner_setEtherbase` are 🟡 (confirm — they change block production and rewards).

## When to use
- Start or stop block production without restarting the node.
- Confirm the node is actually mining and earning to the right address.
- Connect/validate an external miner (stratum/getWork) against the node.

## Prerequisites
- ETC (or other PoW) network, node **fully synced**, Ethash consensus.
- `mining { mining-enabled, coinbase }` configured (`base/mining.conf`). A miner
  with no/zero coinbase earns nothing — check first.

## Procedure
1. **Status** (🟢) — `eth_mining` (bool), `miner_getStatus`, `eth_hashrate`,
   `eth_coinbase`. MCP `mcp_mining_rpc_summary` gives a one-shot summary.
2. **Confirm readiness** (🟢) — node synced (`eth_syncing == false`) and coinbase
   set to the intended address. Mining while unsynced wastes work.
3. **Start** (🟡) — `miner_start` (params `[]`). Then re-check `eth_mining == true`
   and watch `eth_hashrate` climb.
4. **Stop** (🟡) — `miner_stop`.
5. **Change reward address** (🟡) — `miner_setEtherbase("0x…")` (or
   `eth_setEtherbase`). Double-check the address before confirming — misdirected
   rewards are unrecoverable.
6. **External miners** (🟢/config) — expose `eth_getWork` / `eth_submitWork` /
   `eth_submitHashrate`; point the external miner at the RPC endpoint.
7. **Persisting across restarts** (🔴 — config edit + restart) — set
   `mining.mining-enabled = true` and `mining.coinbase` in `fukuii.conf`.

## Troubleshooting
| Symptom | Check |
| :-- | :-- |
| `eth_mining` false after `miner_start` | consensus is Ethash? node synced? errors in log |
| Mining but no rewards | coinbase correct? blocks actually sealed (check tip author) |
| Zero/low hashrate | CPU/external miner; `mine-rounds`; resource starvation |

## Deep reference
- `docs/runbooks/mining-operations.md` (config, external-miner integration)
- `src/main/resources/conf/base/mining.conf`, `base/pow.conf`

## Output
CONVENTIONS §4 block. Evidence = before/after `eth_mining`/hashrate/coinbase;
list every 🟡 call and its confirmation.
