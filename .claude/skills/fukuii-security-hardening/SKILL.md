---
name: fukuii-security-hardening
description: >-
  Review and tighten a Fukuii node's security posture — block/unblock abusive
  peer IPs, manage trusted peers, audit which RPC namespaces, interfaces,
  TLS/mTLS settings, identity rules and plaintext opt-outs are exposed, check the
  P2P node allowlist, and apply exposure best practices. Use when responding to
  abusive peers, before exposing a node, during a security/audit review, or to
  lock down RPC. IP blocking and trusted-peer changes are reversible writes;
  config/exposure changes that need a restart are irreversible — all under the
  guarded-write protocol.
---

# Fukuii security hardening

Read `../CONVENTIONS.md` first. Block/trusted-peer calls are 🟡; RPC-exposure
config changes (restart) are 🔴. HTTP and WebSocket serve only `eth,net,web3,fukuii` by
default; `personal`, `debug`, `admin`, `qa` (and `miner`) are opt-in per transport
(`network.rpc.http.apis`, `ws.apis`, `ipc.apis`), and the node logs a warning at startup
when one is enabled on a non-loopback HTTP/WS endpoint. `admin_blockIP` needs `admin`.

## When to use
- An abusive/misbehaving peer or IP needs blocking.
- About to expose RPC/P2P beyond localhost, or doing a hardening pass.
- Audit asks "what's exposed and to whom?"

## Procedure
1. **Audit exposure first** (🟢) —
   - RPC namespaces: `rpc_modules` (or read `network.rpc.apis`). Disable what you
     don't need — `admin`, `personal`, `debug`, `qa` are powerful; don't expose
     them publicly.
   - Bind interfaces: `network.rpc.http.interface`, `network.rpc.ws.interface`,
     `network.engine-api.interface`, `metrics.interface` (all `localhost` by
     default). A non-loopback one starts only with `tls.enabled = true` or that
     listener's `allow-insecure-bind = true`; list every `allow-insecure-bind = true`
     (each logs `Plaintext listener … allow-insecure-bind=true` at start) and ask
     whether it is deliberate.
   - TLS: if reachable off-host, the listener needs `tls { enabled, profile,
     client-auth }` naming a `fukuii.tls.profiles.<name>`, preferably
     `client-auth = "need"` (`fukuii-tls-operations`). Flag the deprecated
     `mode = "https"` + `certificate` block (removed in 0.13.0).
   - Identity rules: `network.rpc.client-identities` (or `client-identities-file`)
     can grant `admin`/`debug`/`personal` to a certificate beyond the listener's
     `apis`; audit each rule's grant (start-up line `TLS listener <label> rule
     <name>: <namespaces>`) and which CA the profile's truststore trusts.
   - P2P (consortium): is `network.peer.node-allowlist.enabled` on, and does
     `admin_getNodesAllowlist` list only members? (`fukuii-peer-management`)
2. **Block an abusive IP** (🟡) — `admin_blockIP("<ip>")`; verify with
   `admin_listBlockedIPs`; reverse with `admin_unblockIP("<ip>")`. (Equivalent
   `net_addToBlacklist` / `net_removeFromBlacklist` / `net_listBlacklistedPeers`
   are served with the same `admin` namespace.) The block refuses RLPx dial and
   accept, and discovery drops the address's UDP packets. It lasts until the
   node restarts; to keep it, also list the address in
   `network.peer.blocked-ips` (🔴, restart).
3. **Pin / drop peers** (🟡) — `admin_addTrustedPeer` to keep a vetted peer;
   `admin_removePeer` to drop a bad one (→ `fukuii-peer-management`).
4. **Protect secrets** (🔴) — `node.key` and `keystore/` must be permission-
   restricted and backed up off-box (`fukuii-key-management`,
   `fukuii-backup-restore`). Never commit or log key material.
5. **Re-audit** (🟢) — confirm the exposed surface matches intent; document it.

## Exposure quick rules
- Off-host RPC/WS/metrics: TLS on with `client-auth = "need"` (client certificates
  are the node's only built-in authentication), `admin`/`personal`/`debug`/`qa`
  off in the listener's `apis`; grant them to one operator certificate with an
  identity rule, or use IPC.
- Never bind a non-loopback interface in plaintext: the node refuses unless
  `allow-insecure-bind = true`, which belongs only on a private network you
  control, plus a firewall.
- Engine API: server TLS (`network.engine-api.tls`) if it crosses hosts; JWT stays.
- Metrics bind `localhost` by default; a remote scraper needs `metrics.interface`
  and `metrics.tls` (or a deliberate `allow-insecure-bind`).
- Least privilege: enable only the namespaces a consumer actually calls.
- Revocation: short-lived client certs + truststore/rule reload; no CRL/OCSP.

## Deep reference
- `docs/runbooks/security.md`, `docs/runbooks/tls-operations.md` (bind rule:
  `#migration`), `docs/runbooks/peering.md#consortium-node-allowlist`
- `AdminService` block/trusted-peer methods; `base/network.conf` (rpc block,
  `client-identities`, `peer.node-allowlist`), `base/tls.conf`, `base/metrics.conf`
- Metrics: `app_tls_handshake_failures_total`, `app_rpc_namespace_denials_total`,
  `app_p2p_allowlist_rejections_total`

## Output
CONVENTIONS §4 block. Evidence = the exposed surface before/after and any IPs/
peers blocked or trusted; list every 🟡/🔴 action and its confirmation.
