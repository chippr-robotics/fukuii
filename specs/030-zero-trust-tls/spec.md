# Feature Specification: Zero-Trust TLS — mTLS on Every Service Listener, One Outbound TLS Factory, Node-ID Allowlist

**Feature Branch**: `docs/b146-zero-trust-tls-spec` (spec only; code branches are named by the plan)

**Created**: 2026-11-17

**Status**: Draft (2026-11-17). Written from backlog B-146 and a survey of the code at `origin/staging`; five product questions are open (Q1–Q5 below), so `/speckit-clarify` is next.

**In flight**: `docs/b146-zero-trust-tls-spec` (cloud session `cloud-dc960fdc`, agent `spec-b146`): the draft

**Input**: Backlog B-146 (P1, from the B-145 TLS audit, issue #1756): "Spec: zero-trust TLS and mTLS on every service
listener (RPC, WS, Engine API, metrics) plus outbound TLS. Shared tls block, client-auth need/want, truststore,
rotation, cert-identity to namespace map, outbound factory; node-ID allowlist for consortium P2P. Spec Kit first
(Principle VI)." Related: B-147 (TLS docs and the enterprise runbook's mTLS claim), B-148 (HTTPS misconfiguration is
only logged), B-149 (container healthchecks under `mode = https`).

---

## Context: what the node does today

Surveyed at `origin/staging` on 2026-11-17. File paths are under `src/main/scala/com/chipprbots/ethereum/` unless they
start with `src/` or `docs/`.

| Surface | Today | Gap this spec closes |
| --- | --- | --- |
| JSON-RPC HTTP (+ GraphQL `/graphql`, `/health`, `/readiness` on the same port) | `fukuii.network.rpc.http.mode = "http" \| "https"` and a `certificate { keystore-path, keystore-type, password-file }` block (`src/main/resources/conf/base/network.conf`). `JsonRpcHttpServer.apply` picks `InsecureJsonRpcHttpServer` or `SecureJsonRpcHttpServer`; the latter builds `ConnectionContext.httpsServer(sslContext)` with no client-auth setting. | No client authentication; no truststore of its own; restart to rotate. |
| JSON-RPC WebSocket | `JsonRpcWsServer` binds `newServerAt(interface, port).bind(route)`; no TLS setting exists. | No TLS at all. |
| Engine API (8551) | `EngineApiHttpServer` binds plain HTTP; `JwtAuthenticator` checks the HS256 token (60 s skew). | No TLS option; JWT is the only control. |
| Metrics (Prometheus, 13798) | `metrics.Metrics` starts `io.prometheus…HTTPServer.builder().port(port)`; `metrics.conf` has `enabled` and `port` only. | No TLS, no client auth, no interface key. |
| IPC | Unix socket, owner read/write only. | None (out of scope: file permissions are the control). |
| TLS material | `security/SSLConfig`, `SSLContextFactory`, `KeyStoreUtils`, `SSLContextBuilder`. `SSLContextBuilder.sslContext(key)` reads `ConfigFactory.load()` (the global config, not the node's resolved config); the `TrustManager` is built from the **server's own keystore**. | One trust source for everything; config read from the wrong place (B-148). |
| Misconfiguration | A bad keystore becomes a failed bind future that the server lifecycle logs; the node keeps running with no RPC (B-148). | Fail closed at startup. |
| Outbound HTTPS | Five independent clients: `jsonrpc/client/RpcClient` (faucet → node; on an SSL error it falls back to `Http().defaultClientHttpsContext`, B-148), `blockchain/checkpoint/CheckpointDownloader` (JDK `HttpClient`, default trust, redirects `NORMAL`), `ephemery/ReleaseTransport` (JDK `HttpClient`, endpoint identification on, redirects `NEVER`, `javax.net.ssl.trustStore` honoured), `consensus/pow/WorkNotifier` (Pekko `singleRequest`, default context), `BootstrapDownload` (`URL.openStream`). The TUI's `console/ops/EndpointProbe` probes the node's own listeners. | No shared policy: trust, hostname checks, client certificates and redirect rules differ per client. |
| P2P (RLPx, devp2p) | Not TLS. RLPx's ECIES handshake authenticates the remote node's secp256k1 key (`network/rlpx/RLPxConnectionHandler`, `AuthHandshakeSuccess(secrets, remotePubKey)`). `PeerManagerActor` keeps trusted node IDs (bypass max-peers) and static nodes (`static-nodes.json`). | No way to refuse every peer that is not on a list — the consortium requirement. |

The enterprise runbook (`docs/runbooks/enterprise-deployment.md`) already promises "TLS/mTLS for inter-service
communication" and "Mutual TLS authentication between organizations"; the node cannot do either today (B-147 fixes the
text; this spec makes the claim true).

**Why P2P is an allowlist and not TLS.** A node ID *is* a public key, and RLPx already proves possession of it during
the handshake. Wrapping RLPx in TLS would break every other client on the network and add nothing a node-ID check does
not. The consortium control is therefore "only these keys may connect", enforced at the RLPx handshake.

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Fail closed on any TLS misconfiguration (Priority: P1)

An operator turns TLS on for a listener and makes a mistake: a wrong path, a wrong password, a keystore with no private
key, an expired certificate, or `client-auth = need` with no truststore. Today the node starts and silently serves no
RPC. With this feature the node refuses to start and says exactly which listener and which file is wrong.

**Why this priority**: A security setting that degrades silently is worse than none: the operator believes the
endpoint is protected or available when it is neither. It is also the smallest slice and absorbs B-148.

**Independent Test**: Start the node with each misconfiguration in a table of cases; each exits non-zero before any
listener binds, with a message naming the config path and the cause. A valid config starts every listener.

**Acceptance Scenarios**:

1. **Given** `rpc.http.tls.enabled = true` and a keystore path that does not exist, **When** the node starts, **Then**
   it exits non-zero before binding any port and logs `fukuii.network.rpc.http.tls: keystore … not found`.
2. **Given** a valid keystore whose certificate expired yesterday, **When** the node starts, **Then** it exits
   non-zero naming the listener and the certificate's `notAfter`.
3. **Given** `client-auth = need` and no truststore resolvable for that listener, **When** the node starts, **Then** it
   exits non-zero.
4. **Given** the faucet's RPC client is configured for `https` and its TLS material fails to load, **When** the faucet
   starts, **Then** it exits non-zero; it never falls back to the JDK default trust.

---

### User Story 2 — Mutual TLS on the JSON-RPC HTTP and WebSocket listeners (Priority: P1)

An enterprise operator exposes RPC to application servers on an internal network. Only holders of a client
certificate issued by the organisation's CA may connect; anyone else is refused during the TLS handshake, before any
HTTP or JSON-RPC parsing.

**Why this priority**: This is the core of B-146 and of the runbook's mTLS promise; HTTP and WS are the surfaces that
carry `personal`, `admin` and `debug` when an operator enables them.

**Independent Test**: With `client-auth = need` and a truststore holding a test CA: a client with a certificate from
that CA gets a JSON-RPC answer over HTTPS and over WSS; a client with no certificate, a self-signed certificate, or a
certificate from another CA fails the handshake; the server logs one line per refusal and increments a counter.

**Acceptance Scenarios**:

1. **Given** `client-auth = need`, **When** a client connects without a certificate, **Then** the TLS handshake fails
   and no HTTP request is read.
2. **Given** `client-auth = want`, **When** a client connects without a certificate, **Then** the request is served
   with the listener's anonymous namespace set (FR-021); **When** a client presents an untrusted certificate, **Then**
   the handshake fails (a presented certificate is always verified).
3. **Given** WS with `client-auth = need`, **When** an authenticated client subscribes with `eth_subscribe`, **Then**
   the subscription works and the identity established at the handshake applies to the whole session.
4. **Given** `client-auth = none` (server-only TLS), **When** any client connects, **Then** behaviour equals today's
   `mode = "https"`.

---

### User Story 3 — Client-certificate identity selects the RPC namespaces (Priority: P2)

A team gives its monitoring system a certificate that may call `eth`, `net` and `web3` only, and its operations
tooling a certificate that may also call `admin` and `debug` — on the same port. A call to a namespace the identity is
not granted is refused with the same error a disabled namespace returns.

**Why this priority**: mTLS alone is all-or-nothing; per-identity namespaces are what make one listener safe to share.
It depends on Story 2.

**Independent Test**: Two client certificates mapped to two namespace sets; each calls one method in each set; the
granted calls succeed, the others return `-32601` (method not found, as for a disabled namespace) and are counted per
identity; `rpc_modules` returns the caller's own set.

**Acceptance Scenarios**:

1. **Given** identity rule `{ san-dns = "monitor.internal", apis = "eth,net,web3" }`, **When** that client calls
   `admin_peers`, **Then** it gets `-32601` and the denial is logged with the identity and the method name.
2. **Given** a trusted client certificate that matches no rule, **When** it calls a method, **Then** the listener's
   `unmatched` policy applies (FR-022).
3. **Given** a batch request mixing granted and refused methods, **When** it is served, **Then** each element is
   answered on its own, as for disabled namespaces today.

---

### User Story 4 — Certificate and truststore rotation without a restart (Priority: P2)

An organisation issues 30-day certificates through an internal CA and an automated renewer that replaces the files in
place. The node picks up the new keystore and truststore without a restart; connections already open finish on the old
material; new handshakes use the new one.

**Why this priority**: Short-lived certificates are the zero-trust norm; a restart per renewal on a syncing node
(minutes of RPC and Engine API outage, and for ETH-family chains a consensus-client stall) is not acceptable.

**Independent Test**: Start with certificate A; replace the files with certificate B; within one reload interval a new
handshake sees B; a connection opened before the swap still completes its requests; a corrupt replacement keeps A in
service, logs an error and sets a reload-failure metric.

**Acceptance Scenarios**:

1. **Given** a running listener, **When** the keystore file is replaced with a valid one, **Then** within the reload
   interval new handshakes present the new certificate and a reload counter increments.
2. **Given** a running listener, **When** the keystore is replaced with an unreadable file, **Then** the old material
   stays in service, an error is logged, and `tls_reload_failures_total{listener}` increments. The node does not stop.
3. **Given** a CA is removed from a listener's truststore, **When** the reload has run, **Then** new handshakes from
   certificates issued only by that CA fail.

---

### User Story 5 — One outbound TLS client factory (Priority: P2)

Every HTTPS call the node or the faucet makes — faucet to node RPC, checkpoint download, Ephemery release fetch,
work-package notification, bootstrap download, the TUI's probe of the node's own listeners — goes through one factory
that applies the same rules: hostname verification on, a named trust source, an optional client certificate for
upstream mTLS, no silent fallback, no HTTPS-to-HTTP redirect.

**Why this priority**: The audit found five clients with five policies, one of which falls back to default trust on
error. A single factory removes the class of bug. It is independent of Stories 2–4.

**Independent Test**: A local HTTPS server with a certificate from a test CA. Each client, configured with that CA's
truststore, connects; with the JDK default trust it fails; with a certificate whose SAN does not match the host it
fails; against a server that requires a client certificate, it succeeds only when a client profile with a key is
named. A static check (grep ratchet) shows no outbound HTTPS client built outside the factory.

**Acceptance Scenarios**:

1. **Given** the faucet's `rpc-client` names a profile whose truststore holds the node's CA, **When** the node's RPC
   listener requires client auth and the profile has a keystore, **Then** the faucet's calls succeed.
2. **Given** a checkpoint URL whose server certificate's SAN does not match the host, **When** a download starts,
   **Then** it fails with a hostname-verification error naming the URL.
3. **Given** an HTTPS URL that redirects to `http://`, **When** any factory client follows it, **Then** the redirect
   is refused.

---

### User Story 6 — TLS on the Engine API and the metrics endpoint (Priority: P3)

An operator runs the consensus client and a Prometheus server on other hosts. The Engine API and the metrics endpoint
can be served over TLS (and mTLS where the client supports it) using the same `tls` block as RPC.

**Why this priority**: Both default to loopback, the Engine API already has JWT, and the reach of the consensus
clients' TLS support bounds what is useful (Q1). It is still in B-146's title.

**Independent Test**: Engine API over HTTPS with a test CA: a JWT-bearing client with the CA in its trust store calls
`engine_exchangeCapabilities`; the same call without a JWT is refused as today. Metrics over HTTPS with `client-auth =
need`: a scraper with a client certificate gets `/metrics`; one without fails the handshake.

**Acceptance Scenarios**:

1. **Given** Engine API TLS enabled, **When** a client calls with a valid JWT over TLS, **Then** it is served; **When**
   it calls without a JWT, **Then** it is refused exactly as today. TLS never replaces the JWT.
2. **Given** metrics TLS with `client-auth = need`, **When** a scraper presents a trusted certificate, **Then**
   `/metrics` is served.

---

### User Story 7 — Consortium P2P: only allowlisted node IDs (Priority: P2)

A consortium of five organisations runs a private chain. Each node must refuse every devp2p peer whose node ID is not
on the consortium list, inbound and outbound, and must not leak its existence through discovery to others.

**Why this priority**: It is the P2P half of B-146 and the control the runbook's consortium section needs; it is
independent of all TLS stories.

**Independent Test**: Three nodes A, B, C on a private network; A's allowlist holds B only. B connects to A and syncs;
C's inbound connection to A is closed right after the RLPx authentication handshake, before the devp2p `Hello` is
processed; A never dials C even when discovery offers it; a counter of rejections increments.

**Acceptance Scenarios**:

1. **Given** an allowlist with B, **When** C dials A, **Then** A closes the connection after the RLPx auth handshake
   reveals C's key, without processing C's `Hello`.
2. **Given** an allowlist, **When** discovery returns nodes not on it, **Then** they are never dialled.
3. **Given** a trusted or static peer that is not on the allowlist, **When** the node starts, **Then** it exits
   non-zero naming the peer (a contradiction in config fails closed).
4. **Given** the allowlist is disabled (the default), **When** the node runs, **Then** peer behaviour is unchanged.

---

### Edge Cases

- **Health endpoints and container healthchecks (B-149).** `/health`, `/readiness` and `/healthcheck` share the RPC
  port, so under `client-auth = need` a probe must present a certificate. The bundled healthchecks and the TUI probe
  (`EndpointProbe`) must use the outbound factory with a client profile; the docs must say so. No unauthenticated side
  port is added (see Out of Scope).
- **GraphQL** on the RPC port is covered by the listener's TLS and client auth; it is not governed by namespace
  mapping today and stays so (the identity map covers JSON-RPC namespaces only; Q3 may extend it).
- **CORS / browser clients** cannot present client certificates reliably; `want` with an anonymous namespace set is the
  path for them.
- **Loopback binding with TLS** is allowed (some operators want TLS everywhere); **non-loopback binding without TLS**
  is the posture question Q2.
- **A certificate that expires while the node runs**: the listener keeps serving (a running node never stops itself
  for this), logs a warning from the warning threshold onward, and exposes seconds-to-expiry; renewal is Story 4.
- **Rotation that swaps the key type** (RSA ↔ EC) is a valid rotation.
- **Truststore with no CA certificates** under `need` is a startup error; under `want` it is a startup error too (every
  presented certificate would fail).
- **Keystore with several private-key entries**: the entry is chosen by `key-alias`; without an alias and with more
  than one entry, startup fails (no silent first-entry choice).
- **Password files** with a trailing newline are tolerated (trimmed), as `gen-cert.sh` writes one.
- **Same port for HTTP and WS** (if an operator configures it) inherits one TLS setting; differing settings on one port
  are a startup error.
- **Engine API in the Hive harness** and the multi-instance confs keep working with TLS off (the default).
- **Allowlist and node-ID format**: entries accept a 128-hex node ID or a full `enode://` URI; case-insensitive; an
  entry that does not parse is a startup error. The node's own ID on the list is ignored.
- **Allowlist and discv5/discv4**: discovery packets from unlisted nodes are dropped (Q4 decides whether discovery
  runs at all in allowlist mode).
- **IPv6 and dual-stack** listeners use the same TLS context.

## Requirements *(mandatory)*

### Functional Requirements

**Shared TLS configuration**

- **FR-001**: The node MUST read TLS material from one shared block, `fukuii.tls.profiles.<name>`, each profile naming
  a keystore (`path`, `type`, `password-file`, optional `key-alias`), an optional truststore (`path`, `type`,
  `password-file`), `protocols` and `cipher-suites` (optional, JDK defaults when empty), and reload settings (FR-030).
- **FR-002**: Each listener — `fukuii.network.rpc.http`, `fukuii.network.rpc.ws`, `fukuii.network.engine-api`,
  `metrics` — MUST have a `tls { enabled, profile, client-auth }` block. `enabled = false` is the default for every
  listener (Q2 may change the default for non-loopback binds).
- **FR-003**: `client-auth` MUST accept exactly `none`, `want` or `need`, with the TLS meaning: `none` requests no
  client certificate; `want` requests one and serves clients without one; `need` refuses the handshake without one. A
  certificate that is presented MUST always be verified against the truststore under `want` and `need`.
- **FR-004**: The truststore MUST be separate from the keystore. A listener's server keystore MUST NOT be used as its
  client truststore (today's `KeyStoreUtils.getTrustManager(keyStore)` behaviour is removed for listeners).
- **FR-005**: TLS configuration MUST be read from the node's resolved configuration (the one the node was started
  with), never from `ConfigFactory.load()` (B-148).
- **FR-006**: The minimum protocol MUST be TLS 1.2; a `protocols` list naming anything lower is a startup error.
- **FR-007**: The existing `rpc.http.mode = "https"` with its `certificate` block MUST keep working for one minor
  release, mapped to `tls { enabled = true, client-auth = none }` with an implicit profile, and MUST log a deprecation
  warning naming the new keys. Setting both forms for one listener is a startup error.
- **FR-008**: The faucet's own RPC listener and its `rpc-client` MUST use the same profile mechanism.

**Fail closed**

- **FR-010**: Before any listener binds, the node MUST validate every enabled listener's and every named outbound
  profile's TLS material (files exist and are readable, the password opens the keystore, exactly one usable private
  key entry or the named alias, certificate not expired and not yet-to-be-valid, truststore present and non-empty when
  `client-auth` is `want` or `need`), and MUST exit non-zero on the first failure with a message that names the config
  path and the cause and contains no secret.
- **FR-011**: A listener that fails to bind at startup for a TLS reason MUST stop the node, not leave it running
  without that listener. (A bind failure the TUI's `rpc start` action triggers at runtime stays a reported failure of
  that action, as spec 029 FR-039 defines.)
- **FR-012**: A component MUST NOT fall back from configured TLS material to the JDK default trust or to plaintext
  on an error.

**Listeners**

- **FR-014**: JSON-RPC HTTP (with GraphQL and the health routes) and JSON-RPC WebSocket MUST support TLS with all three
  `client-auth` modes.
- **FR-015**: The Engine API MUST support server TLS; JWT authentication MUST remain mandatory whether or not TLS is on.
  Client-certificate support on the Engine API is decided by Q1.
- **FR-016**: The metrics endpoint MUST support TLS with all three `client-auth` modes and MUST gain an `interface`
  key defaulting to loopback.
- **FR-017**: Refused handshakes MUST be logged at most once per remote address per minute (rate-limited, with a
  suppressed count) and counted in `tls_handshake_failures_total{listener, reason}` where `reason` is one of
  `no_client_cert`, `untrusted_client_cert`, `expired_client_cert`, `protocol`, `other`.

**Identity and namespaces**

- **FR-020**: For JSON-RPC over HTTP and WS, the node MUST derive a client identity from a verified client certificate
  and evaluate an ordered rule list `fukuii.network.rpc.client-identities = [ { match { … }, apis = "…" } ]`, first
  match wins. A match MUST support at least: SAN DNS name, SAN URI, subject CN, and SHA-256 certificate fingerprint;
  exact match only (no wildcards or regular expressions in the first version).
- **FR-021**: Requests from clients with no certificate (possible only under `want`) MUST be served with the
  listener's anonymous set, `tls.anonymous-apis`, defaulting to the transport's existing `apis` setting.
- **FR-022**: A verified certificate that matches no rule MUST be handled by `tls.unmatched`: `deny` (refuse every
  method except `rpc_modules`) or `default` (the transport's `apis`). The default is `deny` under `need`.
- **FR-023**: How a rule's `apis` relates to the transport's `apis` (a ceiling, or a grant beyond it) is decided by Q3.
- **FR-024**: A refused method MUST return `-32601`, the same error a disabled namespace returns, and MUST be counted in
  `rpc_namespace_denials_total{identity_rule, namespace}` (the rule's configured name, never the raw certificate
  subject, to keep label cardinality bounded).
- **FR-025**: `rpc_modules` MUST return the caller's effective namespace set.
- **FR-026**: For WebSocket the identity MUST be fixed at the handshake and apply to every message and subscription on
  that connection.
- **FR-027**: The existing loopback warning (opt-in namespaces on a non-loopback HTTP/WS bind) MUST NOT fire for a
  namespace that is reachable only through an identity rule on a `need` listener.

**Rotation**

- **FR-030**: Each profile MUST support reload without a restart: `reload.enabled` (default true) and `reload.interval`
  (default 1 minute). On a change to any file the profile names, the node MUST build and validate new material and,
  only if validation passes, use it for new handshakes. Open connections MUST NOT be closed by a reload.
- **FR-031**: A failed reload MUST keep the previous material in service, log an error naming the file and cause, and
  increment `tls_reload_failures_total{profile}`. It MUST NOT stop the node.
- **FR-032**: The node MUST expose `tls_certificate_expiry_seconds{profile, role}` (role `server` or `client`) and log
  a warning daily once a certificate is within `expiry-warning` (default 14 days) of expiry.
- **FR-033**: The identity rules (FR-020) and the P2P allowlist (FR-050) MUST be reloadable by the same mechanism, or
  explicitly restart-only — decided with Q4.

**Outbound TLS factory**

- **FR-040**: All outbound HTTPS in the node and the faucet MUST obtain its TLS context from one factory, keyed by a
  profile name from `fukuii.tls.profiles` or by the built-in `system` profile (the JDK default trust, no client
  certificate). The clients in scope are `RpcClient` (faucet), `CheckpointDownloader`, `ReleaseTransport`,
  `WorkNotifier`, `BootstrapDownload`, `EndpointProbe`, and any later HTTPS client.
- **FR-041**: Every outbound client MUST verify the server hostname against the certificate's SAN. There MUST be no
  option to disable it outside test code.
- **FR-042**: A profile with a keystore MUST present its certificate when the server requests one (outbound mTLS).
- **FR-043**: Outbound clients MUST NOT follow a redirect from `https` to `http`.
- **FR-044**: Each outbound use site MUST name its profile in config (for example
  `fukuii.sync.checkpoint.tls-profile`); unset means `system`.
- **FR-045**: A build-time ratchet (a grep script in `scripts/`, run in CI) MUST fail when an `SSLContext`, an
  `HttpsConnectionContext`, a JDK `HttpClient` with TLS, or `URL.openStream` on an `https` URL is created outside the
  factory and its tests.
- **FR-046**: The TUI's endpoint probe MUST use the profile of the listener it probes when that listener is `need`.

**Consortium P2P node-ID allowlist**

- **FR-050**: The node MUST support `fukuii.network.peer.node-allowlist { enabled, nodes = [ … ], file }` where
  `nodes` and the optional file list node IDs (128 hex) or `enode://` URIs. `enabled = false` is the default.
- **FR-051**: With the allowlist enabled, an inbound RLPx connection whose authenticated remote key is not on the list
  MUST be closed immediately after the auth handshake, before the devp2p `Hello` is processed, and counted in
  `p2p_allowlist_rejections_total{direction}`.
- **FR-052**: With the allowlist enabled, the node MUST NOT dial a node that is not on the list, from any source
  (discovery, bootstrap nodes, `admin_addPeer`, `admin_addTrustedPeer`); a refused `admin_*` call MUST return an error
  that says the node is not on the allowlist.
- **FR-053**: A trusted, static or bootstrap node that is not on the enabled allowlist MUST be a startup error.
- **FR-054**: The allowlist MUST apply on both chain families (ETC and ETH) and MUST NOT change any consensus rule,
  block validation or sync logic; it filters connections only.

**Observability and docs**

- **FR-060**: Startup MUST log one line per listener: scheme, interface, port, `client-auth`, profile, certificate
  subject and `notAfter`, and the number of identity rules; never a password or key material.
- **FR-061**: The TUI RPC view (spec 029) MUST show each listener's TLS state and client-auth mode.
- **FR-062**: `docs/runbooks/tls-operations.md`, the `fukuii-tls-operations` skill and `enterprise-deployment.md` MUST
  describe the new keys, mTLS, rotation and the allowlist (with B-147), and `tls/gen-cert.sh` MUST be able to issue a
  test CA and client certificates.

### Key Entities

- **TLS profile**: named TLS material — keystore, optional truststore, protocols, ciphers, reload policy. Used by
  listeners (server role) and outbound clients (client role).
- **Listener TLS setting**: per listener — enabled, profile name, client-auth mode, anonymous namespace set, unmatched
  policy.
- **Client identity rule**: an ordered rule — a name, a match on certificate fields, the namespaces it yields.
- **Outbound use site**: a named HTTPS client in the node and the profile it uses.
- **Node allowlist**: a set of node IDs, from config and an optional file, enforced at the RLPx handshake and on every
  dial.

## Security Considerations *(Principle VI)*

- Network-exposed surface: every change in this spec is on a listener or a dialler; the plan MUST name the reviewer
  for each slice (`conduit` for RPC/WS/identity, `beacon` for the Engine API, `herald` for the allowlist, no storage change, so
  not `vault`).
- The defaults do not widen exposure: every listener stays loopback with TLS off unless the operator turns it on;
  JSON-RPC stays private by default (Principle VI).
- Key material is never logged, never committed (the `.gitignore` rules for `*.p12` and `/tls/*` stay), and password files are
  read, used and dropped (char arrays zeroed where the JDK API allows).
- Identity rules authorise by certificate fields only after chain verification; an unverified certificate never reaches
  the rule list.
- Threat-model items the plan must cover: downgrade (FR-006), fallback to default trust (FR-012), cardinality attacks
  on metrics labels (FR-024), log flooding by failed handshakes (FR-017), and an attacker-controlled redirect
  (FR-043).
- Revocation of client certificates is open (Q5).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every misconfiguration case in the Story 1 table (at least 10 cases across the four listeners and two
  outbound clients) makes the node exit non-zero before any port binds; 0 cases leave the node running without the
  configured listener.
- **SC-002**: With `client-auth = need` on HTTP, WS and metrics, 100 % of connections without a trusted client
  certificate fail at the TLS handshake in an integration test, and 100 % of trusted ones are served.
- **SC-003**: A certificate replaced on disk is presented to new handshakes within one reload interval (default 60 s),
  with 0 failed requests on connections opened before the swap, in an integration test.
- **SC-004**: The outbound ratchet reports 0 HTTPS clients built outside the factory on `staging`.
- **SC-005**: In a three-node private-network test, 0 devp2p messages beyond the RLPx auth handshake are processed from
  an unlisted node, and 0 dials go to unlisted nodes over a 10-minute run.
- **SC-006**: Request latency on a TLS listener with `client-auth = need` and 20 identity rules is within 5 % of
  `client-auth = none` for a sustained `eth_blockNumber` load (identity evaluation happens once per connection, not per
  request).
- **SC-007**: With every new setting at its default, the RPC, Engine API, metrics and P2P behaviour is unchanged:
  `testEssential` and the Hive Engine API suites pass with no config edits.

## Assumptions

- Pekko HTTP 1.3 lets a server set `needClientAuth`/`wantClientAuth` on the `SSLEngine` and read the peer certificate
  chain per connection; the Prometheus `HTTPServer` accepts an `HttpsConfigurator`; the JDK `HttpClient` accepts a
  per-client `SSLContext`. The plan confirms each before slicing.
- Certificates come from the operator's PKI (or `gen-cert.sh` for tests); the node does not issue certificates, run an
  ACME client, or talk to a secrets manager.
- PKCS#12 is the primary keystore format; JKS stays readable. PEM files are out of scope for the first version unless
  the plan finds a cheap path.
- Consortium networks run on a custom genesis (`fukuii-custom-networks`); the allowlist is meaningful on public chains
  too but is not recommended there.
- B-148 (fail closed for today's `mode = "https"`) can land first as a small fix; this spec's FR-010–FR-012 generalise
  it and must not contradict it.

## Out of Scope

- TLS on RLPx or discovery (not interoperable; the node-ID allowlist is the control).
- IPC (file permissions are the control).
- An unauthenticated side port for health checks.
- On-chain permissioning (Besu-style permissioning contracts) and account allowlists.
- Certificate issuance, ACME, OCSP stapling for the server certificate, HSM/PKCS#11 keys.
- Authorisation finer than a namespace (per-method or per-parameter rules).
- Reverse-proxy deployment guidance beyond what `tls-operations.md` already has.

## Open Questions [NEEDS CLARIFICATION]

- **Q1 — Engine API client certificates.** [NEEDS CLARIFICATION: Should the Engine API support `client-auth = want |
  need`, or server TLS only (JWT remains the client control)? Options: (a) server TLS only, since few consensus clients
  can present a client certificate to the execution endpoint; (b) all three modes, documented as "only if your CL
  supports it"; (c) no TLS on the Engine API at all — keep it loopback/private network with JWT, as go-ethereum does.]
- **Q2 — Default posture on non-loopback binds.** [NEEDS CLARIFICATION: When an HTTP, WS, Engine API or metrics
  listener binds a non-loopback interface, should the node (a) keep today's behaviour and warn, (b) refuse to start
  unless TLS is enabled, or (c) refuse to start unless TLS with `client-auth = need` is enabled ("zero trust by
  default", a breaking change for existing deployments, which would then need an explicit `allow-insecure-bind`
  opt-out)?]
- **Q3 — What an identity rule's `apis` means.** [NEEDS CLARIFICATION: Is a rule's namespace set (a) capped by the
  transport's `apis` (identity can only narrow), or (b) a grant that may exceed it (so `admin` can be reachable only to
  one certificate on a listener whose default set is `eth,net,web3`)? And should rules also gate GraphQL?]
- **Q4 — Allowlist management and discovery.** [NEEDS CLARIFICATION: Is the P2P allowlist (a) static config/file only,
  reloaded like TLS material, or (b) also editable at runtime through new `admin_` methods that persist to the file
  (Besu's `perm_addNodesToAllowlist` model)? And in allowlist mode, should discovery (a) keep running with unlisted
  nodes filtered, or (b) be forced off so the node is invisible to everyone outside the consortium?]
- **Q5 — Client-certificate revocation.** [NEEDS CLARIFICATION: Is revocation in scope? Options: (a) out of scope —
  short-lived certificates plus truststore/identity-rule reload (FR-030, FR-033) are the revocation path; (b) a local
  CRL file per profile, reloaded with the truststore; (c) OCSP/CRL distribution points fetched online, which makes a
  listener depend on an external service.]
