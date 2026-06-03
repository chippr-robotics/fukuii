---
name: fukuii-first-start
description: >-
  Bring up a brand-new Fukuii node end to end — pick the network, generate the
  node key, set the datadir and config, start the node, and confirm it discovers
  peers and begins syncing. Use when bootstrapping a fresh node, onboarding a new
  host, or setting up ETC/Mordor/Sepolia for the first time. Generating keys and
  starting the node are security-sensitive/irreversible actions under the
  guarded-write protocol.
---

# Fukuii first start

Read `../CONVENTIONS.md` first. Key generation is 🔴 (secrets — see
`fukuii-key-management`); the rest is mostly setup + 🟢 verification.

## When to use
First boot of a node on a new host, or standing up an additional node for a role.

## Procedure
1. **Choose network & datadir** — `etc` (ETC mainnet, chain id 61), `mordor`
   (ETC testnet), `sepolia`/`holesky` (ETH testnets), or a custom genesis
   (`fukuii-custom-networks`). Default datadir `~/.fukuii/<network>/`.
2. **Generate the node key** (🔴) — bootstrap `node.key`:
   `fukuii cli generate-key-pairs > ~/.fukuii/<network>/node.key`
   (line 1 = private key used by the node; line 2 = public node ID for enodes).
   Restrict permissions; back it up (`fukuii-backup-restore`).
3. **Minimal config** — for a standard node the shipped network config suffices.
   Override only what the role needs (RPC apis/port, datadir) in `conf/fukuii.conf`
   — see `fukuii-node-configuration`. For fast bootstrap, consider a checkpoint
   (`fukuii-checkpoint-service`).
4. **Start** — `./bin/fukuii <network>` (or `./fukuii.sh <network>` →
   `ops/tools/fukuii-cli.sh`). Watch the logs come up cleanly.
5. **Verify bring-up** (🟢) — within a few minutes:
   - `web3_clientVersion` / `net_version` → node up, correct network.
   - `net_peerCount` climbing toward 5+ (else `fukuii-peer-management`).
   - `eth_syncing` showing progress (else `fukuii-sync-troubleshooting`).
   - Then a full `fukuii-node-health-check`.

## Common first-start issues
| Symptom | Hand off to |
| :-- | :-- |
| No peers after several minutes | `fukuii-peer-management` |
| Sync not advancing | `fukuii-sync-troubleshooting` |
| Errors in startup log | `fukuii-log-triage` |
| Out of disk during initial sync | `fukuii-disk-management` |

## Deep reference
- `docs/runbooks/first-start.md`, `docs/runbooks/node-configuration.md`

## Output
CONVENTIONS §4 block. Evidence = version/network, peer count, and sync progress
observed after start. Confirm the node key was generated and backed up (no key
material in the output).
