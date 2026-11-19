---
name: fukuii-tls-operations
description: >-
  Secure Fukuii's listeners with TLS and mutual TLS — define a TLS profile
  (`fukuii.tls.profiles`), turn on `tls { enabled, profile, client-auth }` on
  JSON-RPC HTTP/WS, the Engine API or metrics, grant namespaces to client
  certificates with identity rules, rotate certificates without a restart, and
  set outbound TLS profiles. Use when exposing RPC beyond localhost, a node
  refuses to start over TLS or a non-loopback bind, hardening an endpoint,
  fixing TLS/cert errors, or meeting an audit requirement for encrypted or
  authenticated RPC. Config edits require a restart — an irreversible/disruptive
  action under the guarded-write protocol.
---

# Fukuii TLS operations

Read `../CONVENTIONS.md` first. TLS config lives in the node config and a change
needs a restart → 🔴 (confirm; the endpoint drops during restart). Generating a
key or certificate is 🔴 too. Replacing certificate files for a rotation needs
no restart (the profile reloads) → 🟡 (keep the old files to roll back).

## When to use
- A listener will be reachable off-host (TLS then, ideally `client-auth = "need"`).
- The node refuses to start with `Refusing to start: fukuii.network.<listener> binds
  <address> (not loopback) without TLS` (the bind rule, spec 030).
- Audit/compliance requires encrypted or client-authenticated RPC, metrics or Engine API.
- Diagnosing `Refusing to start: fukuii.tls.profiles...`, handshake failures or
  certificate expiry (`fukuii-log-triage`).

## Config (spec 030; `conf/base/tls.conf`, `network.conf`, `metrics.conf`)
A **profile** holds the material; each **listener** names one. Every key is under
`fukuii.` except the faucet's (`faucet.`).

```hocon
tls.profiles.rpc {
  keystore   { path = "/etc/fukuii/tls/node.p12", type = "pkcs12", password-file = "/etc/fukuii/tls/node.password" }
  truststore { path = "/etc/fukuii/tls/fukuii-ca-truststore.p12", type = "pkcs12",
               password-file = "/etc/fukuii/tls/fukuii-ca-truststore.password" }   # only for want/need
  # key-alias = null, protocols = [] (>= TLSv1.2), cipher-suites = [],
  # reload { enabled = true, interval = 1.minute }, expiry-warning = 14.days
}
network.rpc.http.tls { enabled = true, profile = "rpc", client-auth = "need" }   # none | want | need
network.rpc.ws.tls   { enabled = true, profile = "rpc", client-auth = "need" }   # WSS
network.engine-api.tls { enabled = true, profile = "rpc" }   # server TLS only; NO client-auth key (refuses start); JWT stays
metrics { interface = "localhost", tls { enabled = false, profile = null, client-auth = "none" } }
```

- Defaults: every `tls.enabled = false`, `client-auth = "none"`, every listener on
  `localhost` (metrics too, since spec 030: it bound every interface before).
- **Bind rule**: a non-loopback `interface` needs `tls.enabled = true` or the
  listener's `allow-insecure-bind = true` (`network.rpc.http`, `network.rpc.ws`,
  `network.engine-api`, `metrics`); otherwise the node refuses to start. The
  opt-out logs a WARN at every start. Runbook anchor: `tls-operations.md#migration`.
- **Identity rules** (`network.rpc.client-identities = [ { name, match { san-dns |
  san-uri | subject-cn | sha256 }, apis } ]`, or `client-identities-file`, which
  reloads): on a `want`/`need` HTTP or WS listener a matching verified certificate
  gets exactly that rule's `apis` (+ `rpc`, may include `admin` or `graphql`);
  others get the listener's `apis`. Rules with no want/need listener refuse the start.
- **Outbound** clients name a profile or `system`: `faucet.rpc-client.tls-profile`,
  `sync.checkpoint-sync-tls-profile`, `mining.notify-tls-profile`,
  `network.server-address.external-ip-tls-profile`, `bootstrap-download.tls-profile`,
  `tui.probe.tls-profile` (default `null` = unverified probe).
- **Deprecated**: `network.rpc.http.mode = "https"` + `certificate { keystore-path,
  keystore-type, password-file }` (and `faucet.rpc-client.certificate`) still map to
  a server-only profile with a deprecation line, and are **removed in 0.13.0**.
  Don't write them; migrate them. `mode = "https"` with `tls.enabled = true` refuses the start.
- No `FUKUII_*` environment variables are read; use the config file or `-Dfukuii.<key>=`.

## Procedure
1. **Inspect** (🟢) — read the listener blocks and `tls.profiles` in the operator's
   config; check the start-up lines (`TLS listener <label> https://… client-auth=…
   profile=… cert=… notAfter=… rules=<n>`, one `rule <name>: <namespaces>` each) or
   the refusal line. Note which listeners are off-loopback.
2. **Obtain material** (🔴: creates private keys) — production: the operator's PKI.
   Test/small deployments, from a source checkout (`tls/` is not in the distribution):
   `tls/gen-cert.sh ca --out-dir D` (writes `fukuii-ca.*` and
   `fukuii-ca-truststore.p12/.password`; keep `fukuii-ca.p12` offline),
   `tls/gen-cert.sh server --ca D/fukuii-ca --out-dir D --name node --san DNS:<host>,IP:<addr>`,
   `tls/gen-cert.sh client --ca D/fukuii-ca --out-dir D --cn <caller> [--san DNS:…|URI:…]`
   (writes `fukuii-client-<cn>.p12/.password/.crt`). Without a command it writes
   a self-signed server cert. Never reuse or commit material (`fukuii-key-management`).
3. **Configure** (🔴) — add the profile and the listener's `tls` block (and
   `client-identities` if a caller needs more than the listener's `apis`) in the
   operator's override file, not `base/*.conf`. State the diff, confirm, restart.
4. **Verify** (🟢) — the `TLS listener` line appears and no `Refusing to start`;
   `curl --cacert <ca.crt> --cert-type P12 --cert "<client.p12>:$(cat <client.password>)" https://<host>:8546`
   with an `rpc_modules` body returns the expected set; the same without `--cert`
   fails the handshake under `need`. Check `app_tls_handshake_failures_total{listener, reason}`.
5. **Rotate** (🟡, 🔴 for the new key) — issue into a staging dir, then `mv` the
   keystore and its password file over the old ones (atomic rename). Within
   `reload.interval` new handshakes use it: log `TLS profile <name> reloaded …`,
   `app_tls_reloads_total` +1. A bad file logs `reload failed …; keeping the previous
   material` and `app_tls_reload_failures_total` +1. No SIGHUP; no restart needed
   unless config keys change or `reload.enabled = false`.
6. **Revoke a client** (🟡) — no CRL/OCSP. Remove its CA from the truststore or
   its rule from `client-identities-file` (both reload), and rely on short-lived
   client certs. Open connections keep their identity until they close; tell
   long-lived WebSocket clients to use a bounded idle timeout and reconnect.

## Security notes
- `need` + a truststore holding only your clients' CA is what stops a rogue
  workload on the same network; `want` serves anonymous callers the listener's `apis`.
- An identity rule's grant is only as strong as the truststore it chains to.
- Engine API TLS protects the JWT in transit; it never replaces the JWT.
- Health endpoints share the RPC port: under `need` probes present a client cert
  (container healthcheck: `FUKUII_HEALTH_URL`, `_CACERT`, `_CERT`, `_KEY`).
- P2P is not TLS: consortium membership is the node allowlist (`fukuii-peer-management`).

## Deep reference
- `docs/runbooks/tls-operations.md` (keys, rotation, revocation, metrics, migration),
  `docs/runbooks/security.md`, ADR `docs/adr/operations/OPS-005-zero-trust-listeners.md`
- `src/main/resources/conf/base/tls.conf`, `network.conf` (rpc.http, rpc.ws,
  engine-api, client-identities), `metrics.conf`; `specs/030-zero-trust-tls/contracts/config.md`
- Metrics: `app_tls_handshake_failures_total`, `app_rpc_namespace_denials_total`,
  `app_tls_reloads_total`, `app_tls_reload_failures_total`, `app_tls_certificate_expiry_seconds`

## Output
CONVENTIONS §4 block. Evidence = the `TLS listener` start-up line and the
mTLS curl results (with and without a client certificate) after the change.
