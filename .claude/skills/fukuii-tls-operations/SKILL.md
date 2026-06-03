---
name: fukuii-tls-operations
description: >-
  Secure Fukuii's JSON-RPC endpoint with TLS/HTTPS — switch the RPC server to
  https, configure the certificate keystore and password, and rotate certs. Use
  when exposing RPC beyond localhost, hardening an endpoint, fixing TLS/cert
  errors, or meeting an audit requirement for encrypted RPC. Changing the RPC
  mode is a config edit that requires a restart — an irreversible/disruptive
  action under the guarded-write protocol.
---

# Fukuii TLS operations

Read `../CONVENTIONS.md` first. TLS config lives in the node config and a change
needs a restart → 🔴 (confirm; the RPC endpoint drops during restart).

## When to use
- The RPC endpoint will be reachable off-host (always use TLS then).
- Audit/compliance requires encrypted RPC.
- Diagnosing `SSL`/certificate errors in logs (`fukuii-log-triage`).

## Config (under `network.rpc.http`, see `base/network.conf`)
`mode` switches the endpoint; the cert lives in a nested `certificate { … }`
object (default `certificate = null` = HTTPS off). Exact shape:

```hocon
network.rpc.http {
  mode = "https"            # "http" (default) or "https"
  interface = "localhost"   # bind address
  port = 8546               # default RPC port
  certificate {
    keystore-path = "tls/fukuiiCA.p12"   # PKCS#12/JKS keystore
    keystore-type = "pkcs12"             # keystore type
    password-file = "tls/password"       # file holding the keystore password
  }
}
```

> Note: these are **nested** keys under `certificate`. The flat
> `certificate-keystore-path` / `certificate-password-file` form is the separate
> **faucet** config (`conf/faucet.conf`), not the node RPC — don't mix them up.

## Procedure
1. **Obtain a certificate** — a CA-issued cert for public endpoints, or a
   self-signed keystore for internal use. Store the keystore + password file
   under a permission-restricted path (treat like keys — see
   `fukuii-key-management`).
2. **Configure** (🔴) — set `mode = "https"` and populate the nested
   `certificate { keystore-path, keystore-type, password-file }` block in
   `fukuii.conf` (replacing `certificate = null`). Keep `interface` as restrictive
   as the use case allows; do not bind `0.0.0.0` without a firewall + auth in front.
3. **Restart & verify** (🔴) — restart the node, then verify the endpoint speaks
   TLS (`curl -v https://<host>:<port>` → handshake succeeds; a known RPC call
   returns). Confirm http is no longer served on that port if that was intended.
4. **Rotate** (🔴) — replace the keystore, update the password file if changed,
   restart. Plan a brief RPC outage; coordinate with dependents.

## Security notes
- TLS encrypts transport; it is **not** authentication. Combine with network
  controls / a reverse proxy for exposed endpoints. See `fukuii-security-hardening`.
- The faucet has its own separate TLS block (`conf/faucet.conf`) — don't confuse
  the two.

## Deep reference
- `docs/runbooks/tls-operations.md`, `docs/runbooks/security.md`
- `src/main/resources/conf/base/network.conf` (rpc.http block)

## Output
CONVENTIONS §4 block. Evidence = the TLS handshake/curl result post-restart.
