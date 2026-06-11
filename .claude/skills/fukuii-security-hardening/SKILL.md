---
name: fukuii-security-hardening
description: >-
  Review and tighten a Fukuii node's security posture — block/unblock abusive
  peer IPs, manage trusted peers, audit which RPC namespaces and interfaces are
  exposed, and apply RPC/TLS exposure best practices. Use when responding to
  abusive peers, before exposing a node, during a security/audit review, or to
  lock down RPC. IP blocking and trusted-peer changes are reversible writes;
  config/exposure changes that need a restart are irreversible — all under the
  guarded-write protocol.
---

# Fukuii security hardening

Read `../CONVENTIONS.md` first. Block/trusted-peer calls are 🟡; RPC-exposure
config changes (restart) are 🔴.

## When to use
- An abusive/misbehaving peer or IP needs blocking.
- About to expose RPC/P2P beyond localhost, or doing a hardening pass.
- Audit asks "what's exposed and to whom?"

## Procedure
1. **Audit exposure first** (🟢) —
   - RPC namespaces: `rpc_modules` (or read `network.rpc.apis`). Disable what you
     don't need — `admin`, `personal`, `debug`, `qa` are powerful; don't expose
     them publicly.
   - Bind interfaces: confirm `network.rpc.http.interface` is `localhost` unless a
     firewall/proxy fronts it; same scrutiny for WS and Engine API ports.
   - TLS: if reachable off-host, RPC must be `https` (`fukuii-tls-operations`).
2. **Block an abusive IP** (🟡) — `admin_blockIP("<ip>")`; verify with
   `admin_listBlockedIPs`; reverse with `admin_unblockIP("<ip>")`. (Equivalent
   `net_addToBlacklist` / `net_removeFromBlacklist` / `net_listBlacklistedPeers`
   exist if that namespace is enabled.)
3. **Pin / drop peers** (🟡) — `admin_addTrustedPeer` to keep a vetted peer;
   `admin_removePeer` to drop a bad one (→ `fukuii-peer-management`).
4. **Protect secrets** (🔴) — `node.key` and `keystore/` must be permission-
   restricted and backed up off-box (`fukuii-key-management`,
   `fukuii-backup-restore`). Never commit or log key material.
5. **Re-audit** (🟢) — confirm the exposed surface matches intent; document it.

## Exposure quick rules
- Public RPC: TLS on, `admin`/`personal`/`debug`/`qa` off, auth/proxy in front.
- Never bind `0.0.0.0` for RPC without a firewall and authentication.
- Least privilege: enable only the namespaces a consumer actually calls.

## Deep reference
- `docs/runbooks/security.md`, `docs/runbooks/tls-operations.md`
- `AdminService` block/trusted-peer methods; `base/network.conf` (rpc block)

## Output
CONVENTIONS §4 block. Evidence = the exposed surface before/after and any IPs/
peers blocked or trusted; list every 🟡/🔴 action and its confirmation.
