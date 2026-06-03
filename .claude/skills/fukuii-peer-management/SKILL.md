---
name: fukuii-peer-management
description: >-
  Inspect and manage a Fukuii node's peer connections — list peers, add/remove
  peers, mark trusted peers, tune max-peers, and configure static/bootstrap
  nodes. Use when peer count is low, the node is isolated, you need to pin a
  specific peer, connect to a private/consortium set, or troubleshoot P2P
  connectivity. Listing is read-only; add/remove/trust/maxPeers are reversible
  writes under the guarded-write protocol.
---

# Fukuii peer management

Read `../CONVENTIONS.md` first. `admin_peers`/`admin_nodeInfo` are 🟢; the
add/remove/trust/maxPeers calls are 🟡 (confirm before executing).

## When to use
- Low/zero peer count, node "isolated", sync starved for peers.
- Pin a known-good peer, or build a private/consortium peer set.
- Diagnose discovery/connectivity (ports, NAT, firewall).

## Inputs to gather first
Network, RPC port, datadir. Target peer enode URI(s) for any write. CONVENTIONS §1.

## Background (ETC defaults)
- Both discovery (UDP, devp2p v4) and the Ethereum wire protocol (TCP/RLPx)
  default to port **30303** (`network.server-address.port` /
  `network.discovery.port` in `base/network.conf`) — confirm from config; some
  older runbooks quote `9076`, which is stale. Healthy node: ~20–40 peers.
- Known peers persist in `~/.fukuii/<network>/knownNodes.json`.
- `admin_*` peer methods have `net_*` equivalents that also exist
  (`net_listPeers`, `net_connectToPeer`, `net_disconnectPeer`) — use whichever
  the deployment enables.

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
   pinning set static nodes in `fukuii.conf`; see static-nodes runbook and the
   `fukuii-cli sync-static-nodes` helper for multi-client test nets.

## Decision guide
| Symptom | Action |
| :-- | :-- |
| 0 peers, `net_listening` false | ports/discovery misconfigured → fix config/firewall |
| Few peers, ports open | `admin_addPeer` known-good enodes; check bootstrap list |
| Need a peer to never drop | `admin_addTrustedPeer` (🟡) or static node (🔴) |
| Abusive/bad peer | `admin_removePeer` then consider `fukuii-security-hardening` blockIP |

## Deep reference
- `docs/runbooks/peering.md`, `docs/runbooks/network-management.md`
- `docs/for-operators/static-nodes-configuration.md`

## Output
CONVENTIONS §4 block. Evidence = before/after peer counts and the specific enodes
touched; list every 🟡/🔴 call executed.
