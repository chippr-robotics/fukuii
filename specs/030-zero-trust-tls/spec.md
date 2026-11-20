# Feature Specification: Zero-Trust TLS — mTLS on Every Service Listener, One Outbound TLS Factory, Node-ID Allowlist

**Feature Branch**: `docs/b146-zero-trust-tls-spec` (spec only; code branches are named by the plan)

**Created**: 2026-11-17

**Status**: Active (2026-11-20). All eleven slices have landed (S1 #1914, S9 #1915, S2 #1920, S3 #1933, S4 #1941, S5 #1951, S7 #1952, S10 #1953, S6 #1954, S8 #1955, S11 #1956), and the allowlist metrics fix with FR-051's summary as #1959; dev-node ran quickstart Q8 (three nodes) and Q2-WS by hand on 2026-11-20 and re-checked Q8.4 on #1959, all passing, so `Done` waits only on T081, the automated three-node IT (B-402).

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
| Metrics (Prometheus, 13798) | `metrics.Metrics` starts `io.prometheus…HTTPServer.builder().port(port)`; `metrics.conf` has `enabled` and `port` only. With no `hostname`, the 1.3.10 builder binds the **wildcard** address (checked 2026-11-18), although `metrics.conf` says "over localhost". | No TLS, no client auth, no interface key; exposed on every interface when enabled. |
| IPC | Unix socket, owner read/write only. | None (out of scope: file permissions are the control). |
| TLS material | `security/SSLConfig`, `SSLContextFactory`, `KeyStoreUtils`, `SSLContextBuilder`. Since B-148 (#1905, 2026-11-17) `SSLContextBuilder.build(config, key, …)` reads the instance's own config and names the key in every error. The `TrustManager` is still built from the **server's own keystore**. | One trust source for everything; one profile shape per call site. |
| Misconfiguration | Since B-148 (#1905): an HTTPS server whose context cannot be built, or that cannot bind, refuses the node's start (`StdNode.awaitJsonRpcHttpBind`, `startupRefusal`); the faucet awaits its bind; `RpcClient` throws instead of falling back to default trust. Validation is still lazy (at bind) and covers only the HTTP listener's keystore. | Validate every listener and profile before any bind; expiry, alias and truststore checks. |
| Outbound HTTPS | Five independent clients (six with `network/ExternalIPDetector`, which probes `https://checkip.amazonaws.com` and `https://api4.ipify.org` with `URL.openConnection`): `jsonrpc/client/RpcClient` (faucet → node; on an SSL error it falls back to `Http().defaultClientHttpsContext`, B-148), `blockchain/checkpoint/CheckpointDownloader` (JDK `HttpClient`, default trust, redirects `NORMAL`), `ephemery/ReleaseTransport` (JDK `HttpClient`, endpoint identification on, redirects `NEVER`, `javax.net.ssl.trustStore` honoured), `consensus/pow/WorkNotifier` (Pekko `singleRequest`, default context), `BootstrapDownload` (`URL.openStream`). The TUI's `console/ops/EndpointProbe` probes the node's own listeners. | No shared policy: trust, hostname checks, client certificates and redirect rules differ per client. |
| P2P (RLPx, devp2p) | Not TLS. RLPx's ECIES handshake authenticates the remote node's secp256k1 key (`network/rlpx/RLPxConnectionHandler`, `AuthHandshakeSuccess(secrets, remotePubKey)`). `PeerManagerActor` keeps trusted node IDs (bypass max-peers) and static nodes (`static-nodes.json`). | No way to refuse every peer that is not on a list — the consortium requirement. |

The enterprise runbook (`docs/runbooks/enterprise-deployment.md`) already promises "TLS/mTLS for inter-service
communication" and "Mutual TLS authentication between organizations"; the node cannot do either today (B-147 fixes the
text; this spec makes the claim true).

**Why P2P is an allowlist and not TLS.** A node ID *is* a public key, and RLPx already proves possession of it during
the handshake. Wrapping RLPx in TLS would break every other client on the network and add nothing a node-ID check does
not. The consortium control is therefore "only these keys may connect", enforced at the RLPx handshake.

---

## Clarifications

### Session 2026-11-18

- Q: Should the Engine API support client certificates (`client-auth = want | need`), or server TLS only? → A: (a)
  Server-side TLS only. JWT stays the client check; the Engine API has no client-certificate mode (FR-015).
- Q: When an HTTP, WS, Engine API or metrics listener binds a non-loopback interface, what is the default posture? → A:
  (b) It refuses to start unless TLS is enabled on that listener; `allow-insecure-bind = true` is the explicit opt-out.
  This is a breaking change and ships with a release note and a migration section (FR-018, FR-019).
- Q: Is an identity rule's `apis` capped by the listener's `apis`, or a grant beyond it, and do rules gate GraphQL? →
  A: (b) A grant that may exceed the listener's set. The listener's `apis` is what an anonymous or unmatched caller
  gets; a matched certificate gets the rule's set (FR-021–FR-023). Rules also gate GraphQL (FR-028), confirmed by the
  user.
- Q: Is the P2P allowlist static or editable at runtime, and does discovery run in allowlist mode? → A: Both: a static
  file, reloaded like TLS material, and new `admin_` methods that edit it at runtime and write back to the file (the
  model of Besu's `perm_addNodesToAllowlist` / `perm_removeNodesFromAllowlist` / `perm_getNodesAllowlist`). In
  allowlist mode discovery (discv4 and discv5) is forced off; peers come from static nodes, bootnodes and the
  allowlist (FR-055–FR-059).
- Q: Is client-certificate revocation in scope? → A: (a) Out of scope. Short-lived certificates plus truststore and
  identity-rule reload are the revocation path. A local CRL file per profile is a possible follow-up (Out of Scope).
- Q: The decisions the plan made that Q1–Q5 left open (research.md section 3 and D-4 to D-7). → A: Accepted as
  written: a matched certificate gets exactly its rule's set and the draft's `tls.anonymous-apis` and
  `tls.unmatched` keys are dropped; GraphQL is gated as the pseudo-namespace `graphql`; the allowlist methods are
  `admin_addNodesToAllowlist`, `admin_removeNodesFromAllowlist` and `admin_getNodesAllowlist`, returning `true`, with
  no reload method; entries with an address are dial candidates, removal disconnects, config/static/boot entries are
  not removable at runtime, edits need `file` and are atomic with at most 256 entries; forced-off discovery includes
  the DNS tree walk and overrides `discovery-enabled = true` with a warning; metrics over TLS use a Pekko route. The
  release that carries slice 6 is named when the slice lands.

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
   with the listener's own `apis` (FR-021); **When** a client presents an untrusted certificate, **Then** the
   handshake fails (a presented certificate is always verified).
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
2. **Given** a trusted client certificate that matches no rule, **When** it calls a method, **Then** it is served
   with the listener's own `apis`, as an anonymous caller is (FR-022).
3. **Given** a batch request mixing granted and refused methods, **When** it is served, **Then** each element is
   answered on its own, as for disabled namespaces today.
4. **Given** a listener with `apis = "eth,net,web3"` and a rule `{ name = "ops", san-dns = "ops.internal", apis =
   "eth,net,web3,admin,debug" }`, **When** the `ops` certificate calls `admin_peers`, **Then** it is served; **When**
   an anonymous or unmatched caller calls it, **Then** it gets `-32601` (a rule is a grant, FR-023).
5. **Given** GraphQL enabled and the `monitor` rule above (no `graphql` in its `apis`), **When** that client posts to
   `/graphql`, **Then** it gets the same answer as a node with GraphQL disabled; a rule that lists `graphql` is served
   (FR-028).

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

An operator runs the consensus client and a Prometheus server on other hosts. The Engine API can be served over
server-side TLS, with JWT still the client check; the metrics endpoint can be served over TLS with all three
`client-auth` modes. Both use the same profiles as RPC.

**Why this priority**: Both default to loopback and the Engine API already has JWT; few consensus clients can present
a client certificate to the execution endpoint, so the Engine API gets server TLS only (Clarifications, Q1). It is
still in B-146's title, and Story 8 needs a TLS option on every listener it guards.

**Independent Test**: Engine API over HTTPS with a test CA: a JWT-bearing client with the CA in its trust store calls
`engine_exchangeCapabilities`; the same call without a JWT is refused as today. Metrics over HTTPS with `client-auth =
need`: a scraper with a client certificate gets `/metrics`; one without fails the handshake.

**Acceptance Scenarios**:

1. **Given** Engine API TLS enabled, **When** a client calls with a valid JWT over TLS, **Then** it is served; **When**
   it calls without a JWT, **Then** it is refused exactly as today. TLS never replaces the JWT.
2. **Given** metrics TLS with `client-auth = need`, **When** a scraper presents a trusted certificate, **Then**
   `/metrics` is served.
3. **Given** `network.engine-api.tls.client-auth` set to anything, **When** the node starts, **Then** it exits
   non-zero saying the Engine API takes server TLS only and JWT authenticates the consensus client.

---

### User Story 7 — Consortium P2P: only allowlisted node IDs (Priority: P2)

A consortium of five organisations runs a private chain. Each node must refuse every devp2p peer whose node ID is not
on the consortium list, inbound and outbound, and must not leak its existence through discovery to others. When a
member joins or leaves, an operator edits the list on a running node, and the edit survives a restart.

**Why this priority**: It is the P2P half of B-146 and the control the runbook's consortium section needs; it is
independent of all TLS stories.

**Independent Test**: Three nodes A, B, C on a private network; A's allowlist holds B only. B connects to A and syncs;
C's inbound connection to A is closed right after the RLPx authentication handshake, before the devp2p `Hello` is
processed; A never dials C; A runs no discovery; a counter of rejections increments. Then `admin_addNodesToAllowlist`
on A adds C, C connects, and A's allowlist file lists C after a restart; `admin_removeNodesFromAllowlist` removes C,
C is disconnected, and the file no longer lists it.

**Acceptance Scenarios**:

1. **Given** an allowlist with B, **When** C dials A, **Then** A closes the connection after the RLPx auth handshake
   reveals C's key, without processing C's `Hello`.
2. **Given** an allowlist and `discovery-enabled = true` in config, **When** the node starts, **Then** discv4, discv5
   and the DNS tree walk do not run, no discovery UDP port is bound, and one warning says discovery is off because
   the allowlist is on (FR-055).
3. **Given** a trusted or static peer that is not on the allowlist, **When** the node starts, **Then** it exits
   non-zero naming the peer (a contradiction in config fails closed).
4. **Given** the allowlist is disabled (the default), **When** the node runs, **Then** peer behaviour is unchanged.
5. **Given** an allowlist backed by a file, **When** a caller with `admin` calls `admin_addNodesToAllowlist` with C's
   enode, **Then** C is on the in-memory list at once, the file is replaced atomically with a version that lists C,
   and the next file reload sees no change (FR-056, FR-057).
6. **Given** C connected, **When** C is removed (by `admin_removeNodesFromAllowlist` or by an edit of the file that a
   reload picks up), **Then** A disconnects C and refuses its next connection (FR-058).
7. **Given** a caller whose effective set does not include `admin`, **When** it calls any allowlist method, **Then** it
   gets `-32601` (FR-059).

---

### User Story 8 — No plaintext listener on a non-loopback interface (Priority: P2)

An operator sets `network.rpc.http.interface = "0.0.0.0"` to reach the node from another host and forgets TLS. The
node refuses to start and says which listener, which interface, and the two ways out: enable TLS on it, or set
`allow-insecure-bind = true` on it to accept plaintext deliberately.

**Why this priority**: A plaintext RPC, Engine API or metrics port on a routable interface is the commonest exposure
the B-145 audit found. Making it an explicit choice is the zero-trust default. It is a **breaking change** for every
deployment that binds a non-loopback interface without TLS today (hive, the Docker compose file, the `ops/` fleet
configs), so it lands after every listener has a TLS option (Stories 2 and 6) and ships with a migration section.

**Independent Test**: For each of the four listeners and for `0.0.0.0`, `::`, a LAN address and a host name that
resolves to one, the node exits non-zero before any bind with a message naming the key; with TLS on, it starts; with
`allow-insecure-bind = true`, it starts and logs a warning; `localhost`, `127.0.0.1` and `::1` start as today.

**Acceptance Scenarios**:

1. **Given** `network.engine-api.interface = "0.0.0.0"` and Engine API TLS off, **When** the node starts, **Then** it
   exits non-zero naming `fukuii.network.engine-api.tls.enabled` and `fukuii.network.engine-api.allow-insecure-bind`.
2. **Given** the same with `allow-insecure-bind = true`, **When** the node starts, **Then** it binds and logs one
   warning per insecure listener at every start.
3. **Given** metrics enabled with no `interface` set, **When** the node starts, **Then** metrics bind loopback (not the
   wildcard address they bound before this feature).

---

### Edge Cases

- **Health endpoints and container healthchecks (B-149).** `/health`, `/readiness` and `/healthcheck` share the RPC
  port, so under `client-auth = need` a probe must present a certificate. The bundled healthchecks and the TUI probe
  (`EndpointProbe`) must use the outbound factory with a client profile; the docs must say so. No unauthenticated side
  port is added (see Out of Scope).
- **GraphQL** on the RPC port is covered by the listener's TLS and client auth, and identity rules gate it as the
  pseudo-namespace `graphql` (FR-028). With no identity rules, or for
  anonymous and unmatched callers, GraphQL is served exactly when `rpc.graphql.enabled` is true, as today.
- **A rule narrower than the listener.** A rule's set replaces the listener's set for that certificate (FR-023), so a
  matched certificate can get less than an anonymous caller under `want`. That is the operator's rule, applied as
  written; the startup line (FR-060) prints each rule's effective set so it is visible.
- **CORS / browser clients** cannot present client certificates reliably; `want` with the listener's `apis` as the
  anonymous set is the path for them.
- **Loopback binding with TLS** is allowed (some operators want TLS everywhere); **non-loopback binding without TLS**
  refuses the start unless `allow-insecure-bind = true` (FR-018).
- **What counts as loopback.** The `interface` value is resolved once at startup; it is loopback only when every
  address it resolves to is a loopback address (`127.0.0.0/8`, `::1`). `0.0.0.0` and `::` (the wildcard) are not
  loopback. A name that does not resolve is a startup error, as a bind to it would be.
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
- **Allowlist and discovery**: with the allowlist on, discv4, discv5 and the EIP-1459 DNS tree walk do not run at all
  (FR-055); persisted known nodes are still candidates, but only those on the allowlist are dialled (FR-052).
- **Allowlist edits racing a file edit.** An `admin_` edit and an operator's edit of the file are serialised: the edit
  first re-reads the file if it changed since the last load, applies the change to that, and writes atomically
  (FR-057). An edit to a file that no longer parses is refused, and the node keeps its last good list.
- **Removing a configured entry at runtime.** Entries from `node-allowlist.nodes` (HOCON) are not in the file and
  cannot be removed by an `admin_` call; nor can a static node or a bootnode while it is configured (removing it would
  recreate the startup contradiction of FR-053). The call answers an error naming the source of the entry.
- **No file configured.** Without `node-allowlist.file` there is nothing to persist to, so the `admin_` edit methods
  answer an error saying runtime edits need a file; `admin_getNodesAllowlist` still answers.
- **IPv6 and dual-stack** listeners use the same TLS context.

## Requirements *(mandatory)*

### Functional Requirements

**Shared TLS configuration**

- **FR-001**: The node MUST read TLS material from one shared block, `fukuii.tls.profiles.<name>`, each profile naming
  a keystore (`path`, `type`, `password-file`, optional `key-alias`), an optional truststore (`path`, `type`,
  `password-file`), `protocols` and `cipher-suites` (optional, JDK defaults when empty), and reload settings (FR-030).
- **FR-002**: Each listener — `fukuii.network.rpc.http`, `fukuii.network.rpc.ws`, `fukuii.network.engine-api`,
  `metrics` — MUST have a `tls { enabled, profile, client-auth }` block (the Engine API's has no `client-auth`,
  FR-015). `enabled = false` is the default for every listener; a non-loopback bind without TLS is governed by
  FR-018.
- **FR-003**: `client-auth` MUST accept exactly `none`, `want` or `need`, with the TLS meaning: `none` requests no
  client certificate; `want` requests one and serves clients without one; `need` refuses the handshake without one. A
  certificate that is presented MUST always be verified against the truststore under `want` and `need`.
- **FR-004**: The truststore MUST be separate from the keystore. A listener's server keystore MUST NOT be used as its
  client truststore (today's `KeyStoreUtils.getTrustManager(keyStore)` behaviour is removed for listeners).
- **FR-005**: TLS configuration MUST be read from the node's resolved configuration (the one the node was started
  with), never from `ConfigFactory.load()` (B-148).
- **FR-006**: The minimum protocol MUST be TLS 1.2; a `protocols` list naming anything lower is a startup error.
- **FR-007**: The existing `rpc.http.mode = "https"` with its `certificate` block MUST keep working through the
  0.12 releases and is removed in 0.13.0 (the user's decision of 2026-11-18: the node is not in production yet), mapped to `tls { enabled = true, client-auth = none }` with an implicit profile, and MUST log a deprecation
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
- **FR-015**: The Engine API MUST support server TLS only; JWT authentication MUST remain mandatory whether or not TLS
  is on. The Engine API MUST NOT request a client certificate, and a `client-auth` key under
  `network.engine-api.tls` MUST be a startup error that says so.
- **FR-016**: The metrics endpoint MUST support TLS with all three `client-auth` modes and MUST gain an `interface`
  key defaulting to loopback (today it binds the wildcard address; see FR-019).
- **FR-017**: Refused handshakes MUST be logged at most once per remote address per minute (rate-limited, with a
  suppressed count) and counted in `tls_handshake_failures_total{listener, reason}` where `reason` is one of
  `no_client_cert`, `untrusted_client_cert`, `expired_client_cert`, `protocol`, `other`.
- **FR-018**: An HTTP, WS, Engine API or metrics listener whose `interface` is not loopback (Edge Cases: "What counts
  as loopback") MUST refuse the node's start, before any listener binds, unless that listener has `tls.enabled = true`
  (any `client-auth` mode) or `allow-insecure-bind = true`. The refusal MUST name the listener, the interface, and both
  keys. `allow-insecure-bind` is per listener (`fukuii.network.rpc.http.allow-insecure-bind`, `…rpc.ws.…`,
  `…engine-api.…`, `metrics.allow-insecure-bind`), defaults to `false`, and when it is used the node MUST log one
  warning per insecure listener at every start. The faucet's own listener follows the same rule.
- **FR-019**: FR-018 and FR-016's loopback default are a **breaking change**. The release that ships them MUST carry
  (a) a CHANGELOG entry under "Breaking", (b) a "Read before upgrading" item in that release's notes
  (`docs/releases/<version>.md`), and (c) a migration section in `docs/runbooks/tls-operations.md` listing every
  affected setting and the two ways out. The repository's own non-loopback deployments (hive's `fukuii.sh`, the Docker
  compose file and its Prometheus scrape, the `ops/` fleet configs) MUST be migrated in the same PR, each with an
  explicit `allow-insecure-bind = true` or TLS.

**Identity and namespaces**

- **FR-020**: For JSON-RPC over HTTP and WS, the node MUST derive a client identity from a verified client certificate
  and evaluate an ordered rule list `fukuii.network.rpc.client-identities = [ { name, match { … }, apis = "…" } ]`,
  first match wins. A match MUST support at least: SAN DNS name, SAN URI, subject CN, and SHA-256 certificate
  fingerprint; exact match only (no wildcards or regular expressions in the first version). `name` is required and
  unique.
- **FR-021**: Requests from clients with no certificate (possible only under `want`) MUST be served with the
  transport's existing `apis` setting (`network.rpc.http.apis` / `ws.apis`, with their fallback); no separate
  anonymous key is added.
- **FR-022**: A verified certificate that matches no rule MUST be served with the transport's `apis`, exactly as an
  anonymous caller is.
- **FR-023**: A matched certificate MUST be served with exactly the rule's `apis` (plus `rpc`, which is always on). The
  rule's set is a grant: it MAY name namespaces the transport's `apis` does not, including opt-in namespaces such as
  `admin`, `debug` and `personal`, and MAY omit namespaces the transport's set has. Every name in it MUST be a known
  namespace or `graphql`, or startup fails.
- **FR-024**: A refused method MUST return `-32601`, the same error a disabled namespace returns, and MUST be counted in
  `rpc_namespace_denials_total{identity_rule, namespace}` (the rule's configured name, never the raw certificate
  subject, to keep label cardinality bounded).
- **FR-025**: `rpc_modules` MUST return the caller's effective namespace set.
- **FR-026**: For WebSocket the identity MUST be fixed at the handshake and apply to every message and subscription on
  that connection.
- **FR-027**: The existing loopback warning (`RpcExposureCheck`: opt-in namespaces on a non-loopback HTTP/WS bind)
  MUST evaluate the transport's `apis` only, the set an anonymous or unmatched caller gets; a namespace reachable only
  through an identity rule MUST NOT trigger it. The startup line (FR-060) lists each rule's grant instead.
- **FR-028**: Identity rules MUST gate GraphQL (`POST /graphql`)
  as the pseudo-namespace `graphql`. An anonymous or unmatched caller gets GraphQL when `rpc.graphql.enabled` is true
  (today's behaviour); a matched certificate gets it only when its rule's `apis` lists `graphql`. A refused GraphQL
  request MUST get the same answer as a node with GraphQL disabled and MUST be counted as a denial (FR-024). A rule
  that lists `graphql` while `rpc.graphql.enabled = false` MUST be a startup error.

**Rotation**

- **FR-030**: Each profile MUST support reload without a restart: `reload.enabled` (default true) and `reload.interval`
  (default 1 minute). On a change to any file the profile names, the node MUST build and validate new material and,
  only if validation passes, use it for new handshakes. Open connections MUST NOT be closed by a reload.
- **FR-031**: A failed reload MUST keep the previous material in service, log an error naming the file and cause, and
  increment `tls_reload_failures_total{profile}`. It MUST NOT stop the node.
- **FR-032**: The node MUST expose `tls_certificate_expiry_seconds{profile, role}` (role `server` or `client`) and log
  a warning daily once a certificate is within `expiry-warning` (default 14 days) of expiry.
- **FR-033**: The identity rules (FR-020) MUST be reloadable without a restart: they are read from
  `fukuii.network.rpc.client-identities-file` when that key is set (else from the node config, restart-only), checked
  on the profile reload interval, validated in full before use, and on failure handled as FR-031 handles a profile.
  A reload applies to new connections; an open WebSocket keeps the identity fixed at its handshake (FR-026). The P2P
  allowlist file reloads by the same mechanism (FR-057).

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
  `nodes` and the optional file list node IDs (128 hex) or `enode://` URIs; the file is a JSON array of such strings,
  the format of `static-nodes.json`. `enabled = false` is the default. The effective list is the union of `nodes`,
  the file, and the static nodes and bootnodes (which FR-053 requires to be on it).
- **FR-051**: With the allowlist enabled, an inbound RLPx connection whose authenticated remote key is not on the list
  MUST be closed immediately after the auth handshake, before the devp2p `Hello` is processed, and counted in
  `p2p_allowlist_rejections_total{direction}`.
- **FR-052**: With the allowlist enabled, the node MUST NOT dial a node that is not on the list, from any source
  (discovery, bootstrap nodes, `admin_addPeer`, `admin_addTrustedPeer`); a refused `admin_*` call MUST return an error
  that says the node is not on the allowlist.
- **FR-053**: A trusted, static or bootstrap node that is not on the enabled allowlist MUST be a startup error.
- **FR-054**: The allowlist MUST apply on both chain families (ETC and ETH) and MUST NOT change any consensus rule,
  block validation or sync logic; it filters connections only.
- **FR-055**: With the allowlist enabled, discv4, discv5 and the EIP-1459 DNS tree walk MUST NOT run and the discovery
  UDP port MUST NOT be bound, whatever `discovery.discovery-enabled` says; if it says `true`, the node MUST log one
  warning that discovery is off because the allowlist is on. Peers come from static nodes, bootnodes, persisted known
  nodes and the allowlist's own entries that carry an address (FR-058), each filtered by FR-052.
- **FR-056**: The node MUST provide three JSON-RPC methods in the `admin` namespace, modelled on Besu's `perm_` node
  methods: `admin_addNodesToAllowlist(enodes: [string])`, `admin_removeNodesFromAllowlist(enodes: [string])` and
  `admin_getNodesAllowlist()`. Add and remove are all-or-nothing: an entry that does not parse, a duplicate, an entry
  not present (remove), or an entry that cannot be removed (Edge Cases: "Removing a configured entry at runtime")
  fails the whole call with an error naming it, and nothing changes. With the allowlist disabled, or with no `file`,
  the edit methods answer an error that says so.
- **FR-057**: A runtime edit MUST be persisted to the allowlist file before the call answers success: under one lock,
  re-read the file if its content changed since the last load, apply the edit, write the result to a temporary file in
  the same directory, flush it to disk, and rename it over the file atomically. The node MUST record what it wrote so
  that the next reload of the file sees no change; an external edit is picked up by the reload (on the FR-030
  interval) exactly as at startup, and a file that fails to parse keeps the last good list (FR-031's rule). A failed
  write MUST leave both the file and the in-memory list unchanged and answer an error.
- **FR-058**: A node removed from the allowlist, by an `admin_` call or by a reload, MUST be disconnected if connected
  and refused afterwards. An entry with an address (`enode://<id>@<host>:<port>`) is a dial candidate, like a static
  node, from the moment it is added; an entry that is a bare node ID admits that node inbound only.
- **FR-059**: The allowlist methods MUST be reachable only where the caller's effective namespace set includes
  `admin`: over IPC by default, over HTTP or WS only where the transport's `apis` grants `admin` or an identity rule
  does (FR-023), which can be a single client certificate on a `need` listener. They MUST NOT be added to any default
  `apis` set, and every successful edit MUST be logged at INFO with the entries changed and the caller's identity rule
  name (or the transport, for IPC and anonymous callers).

**Observability and docs**

- **FR-060**: Startup MUST log one line per listener: scheme, interface, port, `client-auth`, profile, certificate
  subject and `notAfter`, whether `allow-insecure-bind` is in use, and the number of identity rules, followed by one
  line per rule with its name and effective namespace set; never a password or key material. With the allowlist on,
  one line gives its size, its file and that discovery is off.
- **FR-061**: The TUI RPC view (spec 029) MUST show each listener's TLS state and client-auth mode.
- **FR-062**: `docs/runbooks/tls-operations.md`, the `fukuii-tls-operations` skill and `enterprise-deployment.md` MUST
  describe the new keys, mTLS, rotation, the non-loopback rule and its migration (FR-019), and the allowlist with its
  `admin_` methods (with B-147), and `tls/gen-cert.sh` MUST be able to issue a test CA and client certificates.

### Key Entities

- **TLS profile**: named TLS material — keystore, optional truststore, protocols, ciphers, reload policy. Used by
  listeners (server role) and outbound clients (client role).
- **Listener TLS setting**: per listener — enabled, profile name, client-auth mode (not on the Engine API), and the
  listener's `allow-insecure-bind`. The anonymous and unmatched namespace set is the transport's existing `apis`.
- **Client identity rule**: an ordered rule — a unique name, a match on certificate fields, the namespaces it grants
  (which may include `graphql` and may exceed the transport's `apis`).
- **Outbound use site**: a named HTTPS client in the node and the profile it uses.
- **Node allowlist**: a set of node IDs (some with an address), from config, an optional file, static nodes and
  bootnodes; enforced at the RLPx handshake and on every dial; edited at runtime by `admin_` methods that persist to
  the file.

## Security Considerations *(Principle VI)*

- Network-exposed surface: every change in this spec is on a listener or a dialler; the plan MUST name the reviewer
  for each slice (`conduit` for RPC/WS/identity, `beacon` for the Engine API, `herald` for the allowlist, no storage change, so
  not `vault`).
- The defaults narrow exposure: every listener stays loopback with TLS off unless the operator turns it on; metrics
  move from the wildcard address to loopback (FR-016); a plaintext non-loopback bind needs an explicit
  `allow-insecure-bind` (FR-018). JSON-RPC stays private by default (Principle VI).
- Identity rules can grant `admin`, `debug` or `personal` beyond the transport's set (FR-023). That grant is only as
  strong as the truststore: a rule matches only a certificate that chained to the listener's truststore, and the plan
  MUST test that a self-signed certificate copying a rule's SAN or CN never matches.
- The runtime allowlist methods (FR-056) change who may connect to the node. They live in `admin`, which is in no HTTP
  or WS default set, are reachable only where `admin` is granted (FR-059), and every edit is logged with the caller's
  rule name. The file is written atomically and never left half-written (FR-057).
- The Engine API keeps JWT as its only client check; TLS on it protects the token and payloads in transit and never
  replaces the JWT (FR-015).
- Key material is never logged, never committed (the `.gitignore` rules for `*.p12` and `/tls/*` stay), and password files are
  read, used and dropped (char arrays zeroed where the JDK API allows).
- Identity rules authorise by certificate fields only after chain verification; an unverified certificate never reaches
  the rule list.
- Threat-model items the plan must cover: downgrade (FR-006), fallback to default trust (FR-012), cardinality attacks
  on metrics labels (FR-024), log flooding by failed handshakes (FR-017), and an attacker-controlled redirect
  (FR-043).
- Client-certificate revocation (CRL, OCSP) is out of scope. The revocation path is short-lived certificates plus a
  truststore or identity-rule reload (FR-030, FR-033), which takes effect for new handshakes within one reload
  interval. An open connection keeps its identity until it closes; the docs MUST say so and MUST recommend a bounded
  idle timeout on clients that use long-lived WebSocket sessions.

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
- **SC-007**: With every new setting at its default and every listener on loopback (the shipped confs), the RPC,
  Engine API and P2P behaviour is unchanged: `testEssential` passes with no config edits, and the Hive Engine API
  suites pass with the one opt-out hive's `fukuii.sh` adds (FR-019). Metrics change only in binding loopback.
- **SC-008**: For each of the four listeners, each of `0.0.0.0`, `::` and a non-loopback address without TLS makes the
  node exit non-zero before any port binds (12 cases); with TLS on, or with `allow-insecure-bind = true`, each starts;
  `localhost`, `127.0.0.1` and `::1` start without either.
- **SC-009**: In a test that kills the node at each step of an allowlist edit (before the temporary file is written,
  after it is written, after the rename), the file afterwards always parses and holds either the old list or the new
  one, never a mix; after 100 alternating `admin_` and file edits, the in-memory list equals the file's.

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
- B-148 (fail closed for today's `mode = "https"`) landed first, in #1905 on 2026-11-17; this spec's FR-010–FR-012
  generalise it and must not contradict it.

## Out of Scope

- TLS on RLPx or discovery (not interoperable; the node-ID allowlist is the control).
- IPC (file permissions are the control).
- An unauthenticated side port for health checks.
- On-chain permissioning (Besu-style permissioning contracts) and account allowlists.
- Certificate issuance, ACME, OCSP stapling for the server certificate, HSM/PKCS#11 keys.
- Client-certificate revocation: CRL files, OCSP, and CRL distribution points fetched online. A local CRL file per
  profile, reloaded with the truststore, is a possible follow-up (a backlog item, not this spec).
- Client certificates on the Engine API (JWT is its client check).
- Discovery in allowlist mode, filtered or otherwise (it is off, FR-055).
- Authorisation finer than a namespace (per-method or per-parameter rules).
- Reverse-proxy deployment guidance beyond what `tls-operations.md` already has.

## Open Questions

None. Q1–Q5 were answered on 2026-11-18 (Clarifications). FR-028 (rules gate GraphQL) is the recommended default and
awaits the user's confirmation; plan.md records what changes if it is declined.
