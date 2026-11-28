---
name: fukuii-custom-networks
description: >-
  Stand up a private, consortium, or otherwise custom EVM network on Fukuii —
  author a custom genesis (chain id, allocs, fork schedule), wire bootstrap/static
  nodes, restrict membership with the P2P node allowlist, secure RPC with mutual
  TLS, and configure consensus, without modifying source. Use when creating a
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
   helpers like `start 3nodes` / `sync-static-nodes`). Under the `enterprise`
   modifier only `<datadir>/static-nodes.json` is dialled; the chain's
   `bootstrap-nodes` are ignored.
5. **Restrict membership** (consortium; config + restart, 🔴) — turn on
   `network.peer.node-allowlist { enabled = true, file = "<path>.json" }` listing
   every member's enode (each node's ID comes from its `node.key`). Only listed
   node IDs may connect or be dialled, discovery is forced off, and every static
   node and chain bootstrap node must be listed or the node refuses to start.
   Later membership changes go through `admin_addNodesToAllowlist` /
   `admin_removeNodesFromAllowlist` over IPC (🟡) or a file edit, no restart
   (`fukuii-peer-management`).
6. **Secure the services** (config + restart, 🔴) — every listener stays on
   `localhost` by default. One that must be reached from other hosts (RPC HTTP/WS,
   Engine API, metrics) needs `tls { enabled = true, profile = "<name>" }` with a
   `fukuii.tls.profiles.<name>` (or a deliberate `allow-insecure-bind = true`),
   or the node refuses to start; use `client-auth = "need"` for mutual TLS between
   organizations (`fukuii-tls-operations`). The `enterprise` modifier pins RPC
   HTTP to `localhost` (a system property the config file cannot override); to
   expose it, launch without the modifier and set its keys yourself
   (`docs/runbooks/enterprise-deployment.md`).
7. **Start & verify** (🟢) — launch each node (`./bin/fukuii -Dconfig.file=<your.conf>`),
   confirm `eth_chainId` matches, peers connect, and blocks advance
   (`fukuii-node-health-check`). With the allowlist on, `admin_getNodesAllowlist`
   lists the members and `app_p2p_allowlist_rejections_total` stays flat for them.

## Validation checklist
- `eth_chainId` returns the intended id on every node.
- Genesis hash identical across all members (mismatched genesis = no peering).
- Funding accounts hold the expected balances.
- Consortium: the allowlist is on and lists exactly the members; no listener is
  off-loopback in plaintext unless `allow-insecure-bind` was chosen deliberately.

## Deep reference
- `docs/runbooks/custom-networks.md`, `docs/runbooks/enterprise-deployment.md`
- `docs/runbooks/peering.md#consortium-node-allowlist`, `docs/runbooks/tls-operations.md`
- `src/universal/custom-config-example.conf`, `conf/enterprise-template.conf`
- `docs/for-operators/static-nodes-configuration.md`

## Output
CONVENTIONS §4 block. Evidence = chain id, matching genesis hash across nodes,
and first-block progression. Never include funding private keys.
