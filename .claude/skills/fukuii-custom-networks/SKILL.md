---
name: fukuii-custom-networks
description: >-
  Stand up a private, consortium, or otherwise custom EVM network on Fukuii —
  author a custom genesis (chain id, allocs, fork schedule), wire bootstrap/static
  nodes, and configure consensus, without modifying source. Use when creating a
  private chain, a consortium/enterprise network, an L2-style custom-genesis
  chain, or a local multi-node test network. Genesis/config authoring plus the
  node start are setup actions; key/alloc generation is security-sensitive under
  the guarded-write protocol.
---

# Fukuii custom networks

Read `../CONVENTIONS.md` first. Building genesis/allocs involves keys → 🔴 for
that part (`fukuii-key-management`); the rest is config + 🟢 verification.

## When to use
- Private or consortium chain with its own chain id and genesis.
- Custom fork schedule / gas params, or an L2-style custom-genesis chain.
- Local multi-node test network across clients.

## Procedure
1. **Define the chain** — choose a unique chain id (avoid collisions with public
   nets — ETC is 61), the fork schedule, and gas/consensus parameters. Start from
   `src/universal/custom-config-example.conf` and `conf/enterprise-template.conf`.
2. **Build genesis allocs** (🔴) — generate funding keys and addresses with
   `fukuii cli generate-key-pairs` / `derive-address`, then
   `fukuii cli generate-allocs --balance <wei> --address <addr> …` to emit the
   `alloc` JSON. Safeguard the funding keys.
3. **Author the network config** — point the node at your genesis and set chain
   id, consensus (PoW/PoA per deployment), and any custom params in your override
   config. Don't edit shipped `base/*.conf`; layer your own file.
4. **Wire peers** — for a closed network, configure bootstrap/static nodes so the
   members find each other (`fukuii-peer-management`,
   `docs/for-operators/static-nodes-configuration.md`; `fukuii-cli` has multi-node
   helpers like `start 3nodes` / `sync-static-nodes`).
5. **Start & verify** (🟢) — launch each node (`./bin/fukuii -Dconfig.file=<your.conf>`),
   confirm `eth_chainId` matches, peers connect, and blocks advance
   (`fukuii-node-health-check`).

## Validation checklist
- `eth_chainId` returns the intended id on every node.
- Genesis hash identical across all members (mismatched genesis = no peering).
- Funding accounts hold the expected balances.

## Deep reference
- `docs/runbooks/custom-networks.md`, `docs/runbooks/enterprise-deployment.md`
- `src/universal/custom-config-example.conf`, `conf/enterprise-template.conf`
- `docs/for-operators/static-nodes-configuration.md`

## Output
CONVENTIONS §4 block. Evidence = chain id, matching genesis hash across nodes,
and first-block progression. Never include funding private keys.
