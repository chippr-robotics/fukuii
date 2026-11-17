---
name: fukuii-sepolia-sync
description: >-
  Bring up and diagnose ETH Sepolia testnet sync on Fukuii — set network-type,
  chain-id and Engine API, understand the pre-Merge (peer-driven) vs post-Merge
  (CL-driven) sync phases, verify with eth_syncing, and handle post-Cancun blob
  payloads. Use when standing up a Sepolia node, when Sepolia sync stalls, or
  when deciding whether an EL-peer issue or a CL-driver issue is to blame.
  Sepolia (chain-id 11155111) only — for ETC/Mordor use fukuii-sync-troubleshooting;
  for CL/Engine API faults use fukuii-cl-setup and fukuii-engine-api-debug.
disable-model-invocation: true
user-invokable: true
---

# Fukuii Sepolia sync — ETH testnet

Read `../CONVENTIONS.md` first. Diagnosis is 🟢; config edits are 🔴 (restart).
Sepolia is **post-Merge PoS** — sync at the tip is driven by a Consensus Layer
(CL) client, not by EL peers. ETC/Mordor procedures do **not** apply here.

## Prerequisites
- A CL client (Prysm / Lighthouse / Teku) configured and pointed at Fukuii's
  Engine API — see `fukuii-cl-setup`. Without it the EL will not reach the tip.
- The CL itself should reach the Sepolia head fast via **checkpoint sync**:
  `https://sepolia.checkpoint.ethpandaops.io` (pass as the CL's
  `--checkpoint-sync-url` or equivalent). A from-genesis CL sync is slow and
  leaves the EL idle for hours.

> Hoodi (chain id 560048, `fukuii hoodi`) is built in on the same ETH/PoS path and is run the same way; see
> `docs/runbooks/hoodi.md`. Its CL checkpoint endpoint is `https://checkpoint-sync.hoodi.ethpandaops.io`.

## Config for Sepolia
The shipped `sepolia` network config already sets the consensus-critical values
in `src/main/resources/conf/base/chains/sepolia-chain.conf`:

| Key | Value | Notes |
| :-- | :-- | :-- |
| `network-type` | `"eth"` | Selects the ETH/PoS consensus + timestamp fork path |
| `network-id` | `11155111` | Sepolia |
| `chain-id` | `0xaa36a7` (11155111) | — |
| `merge-netsplit-block-number` | `1735371` | Sepolia's post-Merge net-split block (EIP-3675) |
| `cancun-timestamp` / `prague-timestamp` / `osaka-timestamp` | (timestamps) | Timestamp-gated forks — never block-gated |

Operators add only the `network.engine-api` block (enabled + `jwt-secret-path`)
and any RPC/port overrides — see `fukuii-engine-api-setup`. Reference deployment:
`ops/barad-dur/sepolia/fukuii-conf/sepolia.conf` (note that deployment overrides
the P2P port; confirm the live port from config per CONVENTIONS §1, don't assume
30303).

## Sync phases (this is the diagnostic key)
Sepolia merged at block **1735371** (`merge-netsplit-block-number`). Behaviour
differs sharply across that boundary:

- **Phase 1 — pre-Merge (height < 1735371):** the EL syncs **peer-driven**, the
  same way ETC does — it pulls headers/bodies from EL peers. Peer count and EL
  peering health matter here.
- **Phase 2 — post-Merge (height ≥ 1735371):** the **CL drives** the EL via the
  Engine API (`engine_forkchoiceUpdated*`, `engine_newPayload*`). The EL is
  **passive** — it imports the payloads the CL hands it. EL peers no longer pick
  the head; the CL does.

**Diagnosis shortcut** — sample `eth_blockNumber`:
- `< 1735371` and not advancing → **EL peer problem** (Phase 1) →
  `fukuii-peer-management`.
- `≥ 1735371` and not advancing → **CL driver problem** (Phase 2) →
  `fukuii-cl-setup` / `fukuii-engine-api-debug`.

## Verify sync
```bash
cast rpc eth_syncing     --rpc-url http://localhost:8545   # false = caught up
cast rpc eth_blockNumber --rpc-url http://localhost:8545
```
`eth_syncing` returns `false` once at the tip. While syncing, watch Fukuii
`BlockImporter` logs for steady import progress. Cross-check with
`mcp_sync_status` if the `mcp` namespace is enabled.

## Blob transactions (post-Cancun)
Cancun activates on Sepolia at `cancun-timestamp` (timestamp-gated — verify the
exact value in `sepolia-chain.conf`; do **not** treat it as a block number).
After Cancun the EL handles `engine_newPayloadV3` carrying **versioned blob
hashes** (EIP-4844). If payload validation fails only on post-Cancun blocks:
- Check EIP-4844 blob-sidecar handling — the CL gossips blob sidecars on a
  **separate** gossip network and supplies the versioned hashes with the payload.
- A `newPayloadV3` INVALID at the tip on blob-carrying blocks (EL fully synced)
  is a possible blob-handling consensus fault — capture the block hash and route
  to BEACON. INVALID during catch-up is just a sync gap (see
  `fukuii-engine-api-debug` Case 3).

## Troubleshooting: import stalls mid-chain
1. Sample `eth_blockNumber` 30s apart to confirm a true stall vs slow progress.
2. Identify the **phase** from the height (above) — this decides EL-vs-CL.
3. Phase 1 (peers): `fukuii-peer-management` / `fukuii-sync-troubleshooting`.
4. Phase 2 (CL): check Engine API logs → `fukuii-engine-api-debug`.

## Decision guide
| Symptom | Phase | Action |
| :-- | :-- | :-- |
| Stalled, height < 1735371 | Pre-Merge, EL-driven | `fukuii-peer-management` |
| Stalled, height ≥ 1735371 | Post-Merge, CL-driven | `fukuii-engine-api-debug` |
| `eth_syncing` false but height stale | CL not at tip | sync CL via checkpoint sync |
| INVALID on post-Cancun blocks at tip | blob handling | capture hash → BEACON |

## Deep reference
- `src/main/resources/conf/base/chains/sepolia-chain.conf` (authoritative values).
- `ops/barad-dur/sepolia/fukuii-conf/sepolia.conf` (deployment example).
- `fukuii-cl-setup`, `fukuii-engine-api-setup`, `fukuii-engine-api-debug`.

## Output
CONVENTIONS §4 block. Evidence = `eth_blockNumber` (and which phase it implies),
`eth_syncing`, and the relevant `BlockImporter` / Engine API log lines.
