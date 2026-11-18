# Research: Zero-Trust TLS (spec 030)

**Date**: 2026-11-18 | **Spec**: [spec.md](./spec.md) (Clarifications of 2026-11-18 are binding) | **Plan**: [plan.md](./plan.md)

Surveyed at `origin/staging` (`spec/b146-030-clarify`, cut 2026-11-18, after #1905). Paths are under
`src/main/scala/com/chipprbots/ethereum/` unless they start with `src/`, `docs/`, `scripts/`, `hive/`, `docker/`,
`ops/` or `project/`. Each section ends in a Decision / Rationale / Alternatives block, as the plan skill asks.

## 1. What is there today

### 1.1 TLS material and the HTTPS listener

- `security/SSLConfig.scala` parses one `certificate { keystore-path, keystore-type, password-file }` block.
- `security/SSLContextFactory.scala` checks that the two files exist, loads the keystore, and calls
  `KeyStoreUtils.getKeyManager` and `getTrustManager(keyStore)`: the **server keystore is the truststore** (FR-004
  removes that). It uses `SunX509` for both factories and `SSLContext.getInstance("TLS")` with JDK default protocols.
  The password is read with `getLines().mkString` (a trailing newline is dropped, which is why `gen-cert.sh`'s files
  work) and kept as a `String` (the spec's Security Considerations ask for a `char[]` that is zeroed after use).
- `security/SSLContextBuilder.scala`: since B-148 (#1905) `build(config, key, secureRandom, keyPrefix)` reads the
  instance's config and every error names the full key. Callers: `nodebuilder/NodeBuilder.scala:1271`
  (`sslContext(instanceConfig.config, "network.rpc.http", "fukuii")`) and `faucet/jsonrpc/FaucetBuilder.scala`
  (`faucet.rpc-client` and the faucet's own `network.rpc.http`).
- `jsonrpc/server/http/JsonRpcHttpServer.scala`: `apply` picks `InsecureJsonRpcHttpServer` or
  `SecureJsonRpcHttpServer` on `mode`; `isTls = mode == "https"` feeds the TUI. `SecureJsonRpcHttpServer.bind()`
  calls `ConnectionContext.httpsServer(sslContext)`: no client auth, no protocol floor, no engine factory.
- `jsonrpc/server/http/ServerLifecycle.scala` keeps the binding for the TUI's `rpc stop` / `rpc start` (spec 029
  FR-052); a bind failure is a failed future, and `StdNode.startJsonRpcHttpServer` turns it into
  `StdNode.startupRefusal` (B-148). The TUI's runtime `rpc start` keeps reporting it as an action failure (FR-011).
- `src/test/scala/.../jsonrpc/server/http/SecureJsonRpcHttpServerStartSpec.scala` (198 lines, #1905) and
  `security/SSLContextFactorySpec.scala` are the existing tests to extend.

### 1.2 The other listeners

| Listener | Code | Bind | TLS today |
| --- | --- | --- | --- |
| WS | `jsonrpc/server/http/JsonRpcWsServer.scala:137-141` | `Http(system).newServerAt(interface, port).bind(route)` | none |
| Engine API | `consensus/engine/EngineApiHttpServer.scala:190-204`, config `:232`, built at `nodebuilder/NodeBuilder.scala:1095` | `Http().newServerAt(...)` on its own actor system; `JwtAuthenticator` wraps the route (`:102`) | none |
| Metrics | `metrics/Metrics.scala:21-22` | `PrometheusHTTPServer.builder().port(serverPort).buildAndStart()` | none; **binds the wildcard address** |
| Faucet | `faucet/jsonrpc/FaucetBuilder.scala` | the same `JsonRpcHttpServer` | `mode = https` |

The metrics bind was checked by disassembling `HTTPServer$Builder.makeInetSocketAddress` in
`prometheus-metrics-exporter-httpserver-1.3.10.jar` (`project/Dependencies.scala:191`): with neither `hostname` nor
`inetAddress` set it returns `new InetSocketAddress(port)`, the wildcard. `metrics.conf` says "over localhost"; that
is wrong today. The Docker compose file relies on it: `docker/fukuii/prometheus/prometheus.yml` scrapes
`fukuii:13798` from another container.

### 1.3 Namespaces per transport

- `jsonrpc/server/controllers/JsonRpcBaseController.scala:49-86` folds `enabledApis` into one partial function;
  `:158-242` reads `network.rpc.{http,ws,ipc}.apis` with the `rpc.apis` fallback (B-154, #1768).
- `jsonrpc/JsonRpcController.scala:119-121` `forApis(apis)` returns a copy that answers only `apis` and reports them
  in `rpc_modules` (`:695`); `perTransport` (`:131`) builds one controller per transport
  (`nodebuilder/NodeBuilder.scala:1070`). An identity rule is therefore one more `forApis` copy: no change to the
  dispatch itself.
- `jsonrpc/server/RpcExposureCheck.scala`, called by `StdNode.warnOnExposedNamespaces` (`StdNode.scala:502-515`),
  warns on opt-in namespaces on a non-loopback HTTP/WS bind. FR-027 keeps it on the transport's set; FR-018 adds the
  refusal next to it, with the same notion of loopback.
- GraphQL is a route on the HTTP server (`JsonRpcHttpServer.scala:85-101`), outside the controller; disabled, it
  answers `404 {"errors":[{"message":"graphql disabled"}]}`. FR-028 reuses that answer for a refused caller.

### 1.4 Outbound HTTP(S)

| Site | Client | Trust | Redirects |
| --- | --- | --- | --- |
| `jsonrpc/client/RpcClient.scala` (faucet `WalletRpcClient`) | Pekko `Http().singleRequest` with `ConnectionContext.httpsClient(ctx)` | the faucet's keystore as truststore; throws since #1905 | Pekko default (none followed) |
| `blockchain/checkpoint/CheckpointDownloader.scala:194-198` | JDK `HttpClient` | JDK default | `NORMAL` (refuses https→http, follows https→https to any host) |
| `ephemery/ReleaseTransport.scala:389-392` | JDK `HttpClient`, HTTP/1.1 | JDK default (`javax.net.ssl.trustStore` honoured) | `NEVER` |
| `consensus/pow/WorkNotifier.scala:49` | Pekko `singleRequest` | default | none |
| `BootstrapDownload.scala:44` | `URL.openStream()` | default | `HttpURLConnection` follows same-protocol redirects |
| `console/ops/EndpointProbe.scala:84-104` | JDK `HttpClient` with a recording trust manager that **verifies nothing** (on purpose: it probes the node's own, often self-signed, listener and says so) | none | default |
| `network/ExternalIPDetector.scala:88-89, 342` | `URI.toURL().openConnection()` to `https://checkip.amazonaws.com`, `https://api4.ipify.org` | default | default |
| `network/ApacheHttpClientStreamClient.scala` | Apache HttpClient 5 for UPnP (jUPnP) | — | plain `http` to the LAN gateway; **not HTTPS**, out of scope, allow-listed in the ratchet |

`ExternalIPDetector` was not in the spec's list of five; FR-040's "any later HTTPS client" covers it and the plan
migrates it.

### 1.5 P2P

- `network/rlpx/RLPxConnectionHandler.scala:588-602`: `processHandshakeResult` gets `AuthHandshakeSuccess(secrets,
  remotePubKey)`, sends `ConnectionEstablished(remotePubKey)` to the peer actor, then calls
  `extractHelloAndTransition(..., remainingData, ...)`, which may decode a `Hello` already in `remainingData`. The
  allowlist check therefore goes **between** the success match and that call, so FR-051's "before the Hello is
  processed" holds even for a Hello that arrived in the same TCP segment.
- `network/PeerManagerActor.scala:723-770` `connectWith` is the one outbound dial path (maintained, trusted,
  discovered, explicit `admin_addPeer` / `net_connectToPeer`); it already refuses blocked IPs there (`:758-770`).
  The allowlist dial filter goes in the same place. Trusted IDs: `:321-324`, `:615-627`.
- `network/StaticNodesLoader.scala`: `${datadir}/static-nodes.json`, a JSON array of enode URLs (Besu and core-geth
  format), parsed with full key and port validation. The allowlist file reuses this parser and format.
- `network/discovery/DiscoveryConfig.scala` (`discoveryEnabled`, `dnsTrees`), `DiscoveryServiceBuilder.scala`
  (discv4 + discv5 responders chained on one UDP group, `:123-168`), `PeerDiscoveryManager.scala:78, 106-114, 227`
  (DNS walk and enrollment gated on `discoveryEnabled`). `App.scala:120-139`'s `enterprise` modifier already sets
  `discovery-enabled = false` through system properties; FR-055 forces the same from the allowlist and also stops the
  DNS walk, which `discovery-enabled = false` alone does not stop (it is gated on `useBootstrapNodes`).
- `network/BlockedIPRegistry.scala` + `admin_blockIP` / `admin_unblockIP` / `admin_listBlockedIPs`
  (`jsonrpc/AdminService.scala:501-540`, dispatched at `jsonrpc/JsonRpcController.scala:655-660`) are the closest
  precedent for a runtime-mutable connection filter; they are not persisted.

### 1.6 Ratchet precedent

`scripts/check-head-writers.sh` + `scripts/head-writer-allowlist.txt`, run in `.github/workflows/ci.yml:83` before
`compile-all`, tested by `src/test/scala/.../scripts/HeadWriterRatchetSpec.scala`: count each pattern per file,
compare with an allow-list whose lines carry a reason. FR-045's ratchet copies this shape.

### 1.7 Deployments that bind a non-loopback interface without TLS (FR-019's migration list)

- `hive/fukuii/fukuii.sh:267` (`rpc.http.interface=0.0.0.0`) and `:306` (`engine-api.interface=0.0.0.0`).
- `docker/fukuii/docker-compose.yml` publishes 8546 and 13798; `prometheus.yml` scrapes `fukuii:13798`.
- `ops/barad-dur/{eth,sepolia}/fukuii-conf/*.conf`, `ops/barad-dur/fukuii-conf-2/mordor.conf`,
  `ops/cirith-ungol/conf/etc.conf`: several `interface = "0.0.0.0"` blocks (the P2P and discovery ones are not
  affected; each RPC, WS, Engine API and metrics one is).
- `src/main/resources/conf/*.conf`: only P2P/discovery bind `0.0.0.0` (`gorgoroth.conf`, `bootnode.conf`,
  `enterprise-template.conf`); no shipped conf is affected.

## 2. Decisions

### D-1. One `SSLContext` per profile and role, with swappable managers

**Decision**: `tls/TlsContexts` builds, for each profile and role (server, client), one `SSLContext` for the life of
the process, initialised with a `ReloadingKeyManager` (an `X509ExtendedKeyManager`) and a `ReloadingTrustManager` (an
`X509ExtendedTrustManager`) that delegate to an `AtomicReference` of the current managers. A reload (FR-030) builds
and validates new managers and swaps the reference; on a truststore change it also invalidates every session in the
context's server and client `SSLSessionContext`, so an abbreviated (resumed) handshake cannot skip the new trust
check.

**Rationale**: every consumer takes an `SSLContext` once: Pekko's engine factory, the JDK `HttpClient`
(`builder.sslContext`), the JDK `HttpsServer`. Swapping managers under a fixed context rotates all of them without
rebinding a port or rebuilding a client, and open connections keep their already-negotiated keys (FR-030).

**Alternatives**: rebuild the `SSLContext` and rebind (drops connections, port gap); a delegating `SSLContextSpi`
(more code, same effect); per-connection `SSLContext` lookup (works for Pekko only).

### D-2. Pekko HTTP server: an engine factory per listener, and the session as a request attribute

**Decision**: `TlsServerEngine.connectionContext(listener)` returns `ConnectionContext.httpsServer(() => engine)`;
each engine comes from the profile's context with `setUseClientMode(false)`, `setNeedClientAuth` /
`setWantClientAuth` from `client-auth`, and the enabled protocols and suites filtered to TLS 1.2 and later (FR-006).
The HTTP and WS servers bind with `ServerSettings` whose `parsing.ssl-session-attribute = on`, and routes read the
session with `optionalAttribute(AttributeKeys.sslSession)`.

**Rationale**: verified in `pekko-http-core_3-1.3.0.jar`: `ConnectionContext.httpsServer(Function0[SSLEngine])` and
`AttributeKeys.sslSession` exist, and `reference.conf` has `ssl-session-attribute = off` (line 821). Setting it per
server, not globally, leaves the Engine API's and other servers' parsing unchanged.

**Alternatives**: `ConnectionContext.https(..., clientAuth = Some(TLSClientAuth.Need), ...)` (deprecated shape, no
per-engine protocol filter); `tls-session-info-header` (a header an upstream proxy could also send).

### D-3. Identity evaluated once per TLS session, controllers built once per rule

**Decision**: `IdentityRules.resolve(session)` reads the verified peer chain (`getPeerCertificates`; an unverified
chain cannot reach it, because the handshake failed), matches the leaf against the rules in order, and caches the
result in the session with `SSLSession.putValue("fukuii.rpc.identity", (generation, rule))`. The rule set has a
generation number that a reload (FR-033) bumps; a cached value from an older generation is recomputed. Each rule's
`JsonRpcController.forApis(rule.apis)` copy is built when the rules are loaded, so a request costs one attribute read,
one session lookup and one map lookup. A WebSocket connection resolves once at the upgrade and keeps the result
(FR-026).

**Rationale**: SC-006 (within 5 % of `client-auth = none` with 20 rules) needs evaluation per connection, not per
request; HTTP keep-alive and TLS resumption both reuse the session.

**Alternatives**: per-request evaluation (20 rule comparisons per request; fine but needless); a weak map keyed by
connection (Pekko has no stable connection handle at the route level).

### D-4. A rule is a grant; the transport's `apis` is the anonymous and unmatched set (Q3)

**Decision**: effective set = `rule.apis + rpc` for a matched certificate, else the transport's `apis` (FR-021–023).
`graphql` is accepted in a rule's `apis` as a pseudo-namespace (FR-028); the GraphQL route checks the caller's
effective set before it calls the service. A denial is answered as a disabled namespace or a disabled GraphQL is, and
counted in `rpc_namespace_denials_total{identity_rule, namespace}` with `identity_rule` = the rule's `name`, or
`anonymous` / `unmatched`.

**If FR-028 is declined by the user**: drop the `graphql` pseudo-namespace and the route check (T-numbers in tasks.md
marked FR-028); GraphQL then follows `rpc.graphql.enabled` for every caller, as today. Nothing else changes.

### D-5. Rules from a file for reload

**Decision**: identity rules may live in `network.rpc.client-identities-file` (HOCON, the same shape as the inline
key); when set, the inline key must be empty (both is a startup error) and the file reloads on the profile interval
(FR-033). Inline rules are restart-only.

**Rationale**: the node's resolved config is not re-read at runtime anywhere today; a separate file gives the reload a
clear unit, as the TLS files and the allowlist file are.

### D-6. Engine API: server TLS only (Q1)

**Decision**: `EngineApiHttpServer.start()` binds with `TlsServerEngine.connectionContext` using `ClientAuth.None`
always; the config reader rejects a `client-auth` key under `engine-api.tls`. `JwtAuthenticator` is untouched.

**Rationale**: the clarification; JWT is the consensus clients' universal check. Consulted path for `beacon`:
transport only, no response, status, ordering or timing change.

### D-7. Metrics over TLS: a Pekko route when TLS is on, the Prometheus server otherwise

**Decision**: with `metrics.tls.enabled = false`, `Metrics` keeps `PrometheusHTTPServer` but passes
`.hostname(interface)` (FR-016). With TLS on, `metrics/MetricsTlsServer` serves `GET /metrics` from a Pekko HTTP route
on the node's actor system, bound through `TlsServerEngine` like RPC, the body produced by `PrometheusScrapeHandler`
(`prometheus-metrics-exporter-common-1.3.10.jar`, already on the classpath) over a small `PrometheusHttpExchange`
adapter.

**Rationale**: one server TLS stack, so FR-017's reasons (`no_client_cert` included), rotation (D-1) and the startup
line are the same on every listener.

**Alternatives**: `HTTPServer.Builder.httpsConfigurator` (exists in 1.3.10, verified with `javap`): simplest, but the
JDK `HttpsServer` gives the node no hook on a handshake refused for want of a client certificate, so FR-017 could not
count it. Kept as the fallback if the adapter turns out heavier than expected; the trade-off is then recorded in the
PR.

### D-8. Non-loopback guard (Q2)

**Decision**: `tls/BindPolicy.check(listener, interface, tlsEnabled, allowInsecure)` resolves `interface` once
(`InetAddress.getAllByName`) and passes only when every address `isLoopbackAddress`, or TLS is on, or the opt-out is
set. `StdNode` runs it for every enabled listener in the pre-bind validation pass (FR-010), so a refusal happens before
any port binds. `RpcExposureCheck` and the TUI's exposure column use the same `isLoopback` helper.

**Migration (FR-019)**: one PR changes the default and migrates hive (`fukuii.sh` adds
`-Dfukuii.network.rpc.http.allow-insecure-bind=true` and the Engine API equivalent; hive runs in a private Docker
network), the compose file (`allow-insecure-bind` on RPC and metrics, with a comment pointing to TLS), and the `ops/`
confs. The CHANGELOG entry and the release-notes item go in the same PR.

### D-9. Outbound factory

**Decision**: `tls/OutboundTls` offers `sslContext(profile)`, `jdkHttpClient(profile, redirect)` (redirect is
`NEVER` or `SameSchemeHttpsOnly`, never `ALWAYS`), `pekkoHttpsContext(profile)` and `openHttpsStream(url, profile)`
(an `HttpsURLConnection` with the profile's socket factory, `setInstanceFollowRedirects(false)` and a manual
https-only redirect loop). The `system` profile is `SSLContext.getDefault`'s trust with no client key. Hostname
verification: the JDK `HttpClient` and `HttpsURLConnection` verify by default; Pekko's client verifies when given an
`SSLContext` (it sets the endpoint identification algorithm); the factory also sets
`SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")` on every client engine it creates, and its tests check a
mismatched SAN fails on all four paths (FR-041).

`EndpointProbe` keeps its "verifies nothing, and says so" mode for a listener without client auth (it reports the
certificate it saw); for a `need` listener it uses that listener's profile (FR-046) and then verifies. Its recording
trust manager is the one allow-listed exception in the ratchet, with that reason.

### D-10. Ratchet

**Decision**: `scripts/check-outbound-tls.sh` + `scripts/outbound-tls-allowlist.txt`, same contract as
`check-head-writers.sh` (counts per file, reason per line, `--inventory`), patterns: `SSLContext.getInstance`,
`ConnectionContext.https(Client|Server)?\(`, `HttpClient.newBuilder`, `.openStream(`, `.openConnection(`,
`Http\(\)\.singleRequest`. Allowed: `tls/` itself, `ApacheHttpClientStreamClient` (UPnP, plain http), the probe's
recording trust manager. Run in `ci.yml` beside `check-head-writers.sh`; tested by an `OutboundTlsRatchetSpec`.
First landed in slice 7 with the not-yet-migrated sites listed (each with "migrated in slice 8"); slice 8 removes
those lines, which is SC-004.

### D-11. P2P allowlist

**Decision**:
- `network/NodeAllowlist` holds an immutable `Set[NodeId]` plus the dial addresses, behind an `AtomicReference`, with
  a `generation`. Readers (`RLPxConnectionHandler`, `PeerManagerActor.connectWith`) read the reference; no actor hop
  on the handshake path.
- The RLPx check sits in `processHandshakeResult` before `ConnectionEstablished` (1.5). A rejection sends
  `ConnectionRejected(NotOnAllowlist)` and stops; `PeerActor.rejectionRetryDelay` gets a case that never retries it.
- `connectWith` refuses an unlisted node with the same reason; `admin_addPeer`, `admin_addTrustedPeer` and
  `net_connectToPeer` check first and answer the FR-052 error.
- Startup (`StdNode`): contradictions (FR-053) and the discovery override (FR-055) are checked before any listener or
  the P2P server starts. `DiscoveryConfig.apply` takes `allowlistEnabled` and returns `discoveryEnabled = false`,
  `dnsTrees = Seq.empty`; `DiscoveryServiceBuilder` then binds no UDP socket.
- Removal (FR-058): `PeerManagerActor` subscribes to allowlist generation changes and disconnects peers no longer
  listed, with the devp2p disconnect reason `UselessPeer` (0x03), the generic policy drop (herald to confirm against
  geth's `p2p/server.go` in review).
- Runtime edits (FR-056/057): `network/NodeAllowlistStore` owns the file: one lock; `edit(f)` re-reads the file if its
  SHA-256 differs from the last loaded one, applies `f`, writes `<file>.tmp-<pid>` in the same directory, `force(true)`,
  `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`, fsyncs the directory, records the new SHA-256 as "last loaded", then
  swaps the in-memory set. The reload poller compares SHA-256 (not mtime, which a same-second edit can leave
  unchanged) and skips a file whose hash is the last loaded one. A failed write deletes the temp file and changes
  nothing.
- Method names: Besu's `perm_` names moved into `admin` (`admin_addNodesToAllowlist`, `admin_removeNodesFromAllowlist`,
  `admin_getNodesAllowlist`), because fukuii has no `perm` namespace and adding one would need its own
  default-off/exposure wiring for three methods. Besu answers `"Success"`; fukuii answers `true` like
  `admin_addTrustedPeer`, and errors with code `-32000` and a message naming the entry (contracts/admin-allowlist.md).
  Besu's `perm_reloadPermissionsFromFile` is not copied: the poller reloads.

**Alternatives**: the allowlist as a `PeerManagerActor` field (an ask on every inbound handshake); persisting in
RocksDB (operators edit a file; consortium tooling distributes files); a `perm_` namespace (rejected above).

### D-12. Test PKI

**Decision**: a test-only `TestPki` (BouncyCastle, already a dependency) issues a CA, server and client certificates,
expired and not-yet-valid ones, and SAN/CN variants into a temp directory per test. No key material is committed
(`.gitignore` covers `*.p12` and `/tls/*`). `tls/gen-cert.sh` gains `ca`, `server` and `client` subcommands for
operators (FR-062), tested by a shell test that runs it into a temp dir.

### D-13. Reload trigger

**Decision**: polling on `reload.interval` (default 1 minute) with SHA-256 of each named file, one scheduler per
node (a single-thread `ScheduledExecutorService`, named, daemon). No `WatchService`.

**Rationale**: `WatchService` misses edits through symlinked directories, which is how Kubernetes mounts secrets
(`..data` swaps), and needs a thread anyway. Polling a few small files a minute costs nothing.

## 3. Open points for the user (not blocking the plan)

1. FR-028 (rules gate GraphQL): confirmed by the user on 2026-11-18, with the other decisions here and D-4 to D-7.
2. The release that carries slice 6 (the breaking default) is not named; the spec has no `**Release**` line yet.
3. D-7's choice of a Pekko route for metrics over TLS replaces the Prometheus server only when TLS is on; if the user
   prefers one metrics server, the HttpsConfigurator fallback loses FR-017's `no_client_cert` count on that listener.
