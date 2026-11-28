---
name: fukuii-peer-management
description: >-
  Inspect and manage a Fukuii node's peer connections — list peers, add/remove
  peers, mark trusted peers, tune max-peers, configure static/bootstrap nodes,
  and manage the consortium node-ID allowlist (`network.peer.node-allowlist`,
  `admin_addNodesToAllowlist` / `admin_removeNodesFromAllowlist` /
  `admin_getNodesAllowlist`). Use when peer count is low, the node is isolated,
  you need to pin a specific peer, admit or remove a consortium member, or
  troubleshoot P2P connectivity. Listing is read-only; add/remove/trust/maxPeers
  and allowlist edits are reversible writes under the guarded-write protocol.
---

# Fukuii peer management

Read `../CONVENTIONS.md` first. `admin_peers`/`admin_nodeInfo` are 🟢; the
add/remove/trust/maxPeers calls are 🟡 (confirm before executing). `admin_*` is opt-in
over HTTP: check `rpc_modules` first (CONVENTIONS §2). The peer methods that carry a
`net_` name (`net_listPeers`, `net_connectToPeer`, `net_disconnectPeer`, `net_nodeInfo`,
the blacklist ones) are served with the `admin` namespace too, so they are no fallback:
without `admin`, only `net_peerCount` and `net_listening` answer.

## When to use
- Low/zero peer count, node "isolated", sync starved for peers.
- Pin a known-good peer, or build a private/consortium peer set.
- Diagnose discovery/connectivity (ports, NAT, firewall).

## Inputs to gather first
Network, RPC port, datadir. Target peer enode URI(s) for any write. CONVENTIONS §1.

## Background
- Both discovery (UDP, devp2p v4) and the Ethereum wire protocol (TCP/RLPx)
  default to port **30303** (`network.server-address.port` /
  `network.discovery.port` in `base/network.conf`) — confirm from config; some
  older runbooks quote `9076`, which is stale.
- Healthy EL peer count: **~20–40 peers** — `min-outgoing-peers = 20`,
  `max-outgoing-peers = 50` in `base/network.conf` applies to all networks (no
  chain-specific override). Sepolia is a smaller testnet, so you may see fewer
  connected peers in practice. CL beacon peers are managed by the CL client
  separately and are not visible via `net_peerCount`. **< 3 peers = degraded**
  on any chain.
- Known peers persist in `~/.fukuii/<network>/knownNodes.json`.
- `admin_*` peer methods have `net_*` equivalents that also exist
  (`net_listPeers`, `net_connectToPeer`, `net_disconnectPeer`) — both families
  need the `admin` namespace, so use whichever name your tooling already uses.

## Procedure
1. **Inspect** (🟢) — `admin_peers` (or `mcp_peer_list`) for current peers;
   `net_peerCount` for the total; `admin_nodeInfo` for your own enode/ports.
2. **Diagnose low count** (🟢) — check listening (`net_listening`), that
   discovery/TCP ports are open/forwarded, and that bootstrap nodes are reachable.
   If sync (not peering) is the real issue, hand to `fukuii-sync-troubleshooting`.
3. **Add a peer** (🟡) — `admin_addPeer("enode://<id>@<host>:<port>")`. State the
   enode and confirm, then call; re-run `admin_peers` to verify it connected.
4. **Remove a peer** (🟡) — `admin_removePeer("enode://…")`. Confirm first.
5. **Trusted peers** (🟡) — `admin_addTrustedPeer` / `admin_removeTrustedPeer` to
   keep a peer connected past normal limits (private/consortium pinning).
6. **Capacity** (🟡) — `admin_maxPeers(<n>)` to raise/lower the cap. Note effect on
   bandwidth/memory before raising.
7. **Static / bootstrap nodes** (🔴 — config edit, restart) — for permanent
   pinning list static nodes in `<datadir>/static-nodes.json`; see the static-nodes
   runbook and the `fukuii-cli sync-static-nodes` helper for multi-client test nets.
   `bootstrap-nodes` is a chain-config key; the `enterprise` modifier ignores it
   and dials only `static-nodes.json`.

## Consortium node allowlist (spec 030)
`network.peer.node-allowlist { enabled = false, nodes = [], file = null,
reload-interval = 1 minute }`. With `enabled = true`, only listed node IDs may
connect (checked right after the RLPx auth handshake) or be dialled (every dial,
including `admin_addPeer`, `admin_addTrustedPeer`, `net_connectToPeer`, which
answer `node <id>... is not on the node allowlist` for an unlisted one), and
discovery (discv4, discv5, DNS) is off whatever `discovery-enabled` says. Entries:
`"enode://<128 hex>@host:port"` (also a dial candidate) or a bare 128-hex id.
Every static node and chain bootstrap node must be listed or the node refuses to
start. Enabling/disabling it is a config edit + restart (🔴).

- **Read** (🟢) — `admin_getNodesAllowlist` → `{ enabled, file, nodes: [ { id,
  enode, sources } ] }` (`sources` ⊂ `config`, `file`, `static`, `bootnode`).
- **Add / remove** (🟡) — `admin_addNodesToAllowlist([["enode://…", …]])`,
  `admin_removeNodesFromAllowlist([["<id or enode>", …]])`: one array of 1–256
  entries, all-or-nothing, result `true`. They need `file` set (else `-32000 …
  runtime edits need fukuii.network.peer.node-allowlist.file`) and the `admin`
  namespace: IPC by default, or an mTLS identity rule granting `admin` (no HTTP/WS
  default has it; without it `-32601`). The node re-reads the file if it changed,
  writes it atomically (tmp + fsync + rename), then swaps the list; a removed node
  is disconnected. Entries from `nodes`, static nodes or bootnodes cannot be
  removed at runtime (the error names the source). State the enodes, confirm,
  call, then re-read with `admin_getNodesAllowlist` and check the file.
- **Hand edit** (🟡) — editing the file (JSON array, static-nodes.json format)
  is picked up within `reload-interval`; an unparseable version is refused and
  the last good list stays.
- **Metrics** — `app_p2p_allowlist_rejections_total{direction}`,
  `app_p2p_allowlist_size`, `app_p2p_allowlist_reload_failures_total`,
  `app_p2p_allowlist_edits_total{op, result}`.

## Decision guide
| Symptom | Action |
| :-- | :-- |
| 0 peers, `net_listening` false | ports/discovery misconfigured → fix config/firewall |
| Few peers, ports open | `admin_addPeer` known-good enodes; check bootstrap list |
| Need a peer to never drop | `admin_addTrustedPeer` (🟡) or static node (🔴) |
| Abusive/bad peer | `admin_removePeer` then consider `fukuii-security-hardening` blockIP |
| Consortium: only members may connect | node allowlist (🔴 to enable; 🟡 `admin_` edits) |
| Allowlist on, a member never connects | both sides list each other? `admin_getNodesAllowlist`; `app_p2p_allowlist_rejections_total` rising |

## Deep reference
- `docs/runbooks/peering.md` (incl. `#consortium-node-allowlist`), `docs/runbooks/network-management.md`
- `specs/030-zero-trust-tls/contracts/admin-allowlist.md` (method contract and errors)
- `docs/for-operators/static-nodes-configuration.md`

## Output
CONVENTIONS §4 block. Evidence = before/after peer counts and the specific enodes
touched; list every 🟡/🔴 call executed.
