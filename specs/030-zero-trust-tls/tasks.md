---

description: "Task list for spec 030, zero-trust TLS"
---

# Tasks: Zero-Trust TLS — mTLS on Every Listener, One Outbound TLS Factory, Node-ID Allowlist

**Input**: Design documents from `specs/030-zero-trust-tls/` — [plan.md](./plan.md), [spec.md](./spec.md),
[research.md](./research.md), [data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: Required. The spec's stories each name an independent test, the constitution (Principle III) requires
tests with behaviour, and the user asked for tests first. In every phase the test tasks come first and MUST fail
before the implementation tasks start.

**Organization**: by user story, in the order of plan.md's slices. Each slice (S1–S11) is one PR and one backlog item;
the phase headers name the slice. Paths are relative to the repository root; `M/` = `src/main/scala/com/chipprbots/ethereum/`,
`T/` = `src/test/scala/com/chipprbots/ethereum/`, `IT/` = `src/it/scala/com/chipprbots/ethereum/`.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: the user story (US1–US8 in spec.md)

---

## Phase 1: Setup (shared, lands with S1)

- [ ] T001 Add the S1 backlog item ("Spec 030 S1: TLS profiles, pre-bind validation, RPC HTTP on profiles") with `python3 -I scripts/backlog/backlog.py add`, refs B-146, and claim it; repeat at the start of each later slice with plan.md's title for it
- [ ] T002 [P] Create the test PKI `T/tls/TestPki.scala`: BouncyCastle CA, server and client certificates, expired and not-yet-valid leaves, SAN DNS/URI/CN variants, PKCS#12 and JKS stores with password files (with and without a trailing newline), all under a per-test temp dir (research D-12)
- [ ] T003 [P] Create `src/main/resources/conf/base/tls.conf` with an empty `fukuii.tls.profiles {}` and the commented example of contracts/config.md, and include it from `src/main/resources/conf/base/fukuii.conf`

---

## Phase 2: Foundational (S1; blocks every TLS story)

**Purpose**: profiles, material loading and validation, the context holder and the pre-bind pass. US1 is the story
that exercises it.

### Tests first

- [ ] T004 [P] `T/tls/TlsConfigSpec.scala`: parse profiles and listener `tls` blocks; `client-auth` accepts exactly none/want/need; `system` is reserved; legacy `mode = "https"` + `certificate` maps to an implicit profile and logs the deprecation once; both forms on one listener is an error (FR-001–FR-003, FR-007)
- [ ] T005 [P] `T/tls/TlsMaterialLoaderSpec.scala`: one case per row of data-model.md's validation table, each asserting the `<config path>: <cause>` message; a planted password never appears in any message (FR-006, FR-010)
- [ ] T006 [P] `T/tls/TlsContextsSpec.scala`: the server context never trusts its own keystore (FR-004); protocols below TLSv1.2 are absent from created engines (FR-006); key alias selection
- [ ] T007 Extend `T/jsonrpc/server/http/SecureJsonRpcHttpServerStartSpec.scala` (#1905) so a misconfigured `rpc.http.tls` refuses the start before any port binds, including when the Engine API would bind first (FR-011, SC-001)

### Implementation

- [ ] T008 [P] `M/tls/TlsProfile.scala` and `M/tls/TlsConfig.scala`: `ClientAuth`, `StoreRef`, `TlsProfile`, `ListenerId`, `ListenerTls`, the legacy mapping (data-model.md)
- [ ] T009 [P] `M/tls/TlsMaterialLoader.scala`: load stores with `char[]` passwords zeroed after use, validate per data-model.md, return `Either[TlsConfigError, TlsMaterial]`
- [ ] T010 `M/tls/TlsContexts.scala`: one `SSLContext` per (profile, role), built from `TlsMaterial` (static in S1; reloading managers in T046) (depends on T008, T009)
- [ ] T011 `M/tls/TlsServerEngine.scala`: `connectionContext(listener)` = `ConnectionContext.httpsServer(() => engine)` with the protocol floor and `client-auth = none` (want/need in T024) (depends on T010)
- [ ] T012 `M/tls/TlsStartupCheck.scala`: validate every enabled listener and named profile; first failure → `StdNode.startupRefusal`; call it from `M/nodebuilder/StdNode.scala` before the first bind (`:421-440`) (depends on T009)
- [ ] T013 Make `M/security/SSLContextBuilder.scala` and `M/security/SSLContextFactory.scala` thin adapters over `tls/` (the faucet client still calls them until T067)

**Checkpoint**: profiles load and validate; nothing listens differently yet.

---

## Phase 3: User Story 1 — Fail closed on any TLS misconfiguration (P1) — S1 🎯 MVP

**Goal**: every TLS misconfiguration stops the node before any bind, naming the key and the cause.

**Independent Test**: spec US1; quickstart Q1.

### Tests first

- [ ] T014 [P] [US1] `T/nodebuilder/TlsStartupRefusalSpec.scala`: table of ≥ 10 misconfigurations across the HTTP listener and the faucet's listener and client; each exits through `startupRefusal` with the contracts/observability.md text; a valid config starts (SC-001)
- [ ] T015 [P] [US1] `T/faucet/jsonrpc/FaucetTlsStartSpec.scala`: the faucet's `rpc-client` with broken TLS material refuses the faucet's start, never JDK default trust (FR-008, FR-012)

### Implementation

- [ ] T016 [US1] `M/jsonrpc/server/http/JsonRpcHttpServer.scala` and `SecureJsonRpcHttpServer.scala`: read `rpc.http.tls`, bind with `TlsServerEngine`; `isTls` from the listener block (feeds the TUI)
- [ ] T017 [US1] `M/nodebuilder/NodeBuilder.scala:1265-1275` and `M/faucet/jsonrpc/FaucetBuilder.scala`: build servers from `TlsConfig`, not `sslContext(config, key)`
- [ ] T018 [US1] Startup line per listener (FR-060, first part) in `M/nodebuilder/StdNode.scala`; add `tls` keys to `src/main/resources/conf/base/network.conf` (`rpc.http.tls`) with comments pointing to `docs/runbooks/tls-operations.md`
- [ ] T019 [US1] Run `sbt pp` and `sbt formatCheck`; open S1's PR with `Backlog:` the S1 item

**Checkpoint**: S1 merged; US1 testable on the HTTP listener and faucet.

---

## Phase 4: User Story 2 — Mutual TLS on JSON-RPC HTTP and WebSocket (P1) — S2

**Goal**: `client-auth = want | need` on HTTP and WSS; refusals at the handshake, logged and counted.

**Independent Test**: spec US2; quickstart Q2.

### Tests first

- [x] T020 [P] [US2] `T/jsonrpc/server/http/MtlsHttpSpec.scala`: under `need`, no certificate / self-signed / other-CA certificate fail the handshake and no HTTP request is read; a trusted one is answered; under `want`, no certificate is served with the transport's `apis`; under `none`, behaviour equals the legacy `https` (US2 scenarios 1, 2, 4; SC-002)
- [x] T021 [P] [US2] `T/jsonrpc/server/http/MtlsWsSpec.scala`: WSS with `need`; `eth_subscribe` works for a trusted client for the life of the connection (US2 scenario 3)
- [x] T022 [P] [US2] `T/tls/HandshakeFailureClassifierSpec.scala`: each FR-017 `reason` from a real failed handshake; the per-address limiter logs once a minute with a suppressed count
- [x] T023 [P] [US2] `T/tls/GenCertScriptSpec.scala` (or a shell test under `scripts/`): `tls/gen-cert.sh ca|server|client` produce stores `TestPki`-equivalent tests accept

### Implementation

- [x] T024 [US2] `M/tls/TlsServerEngine.scala`: need/want on the engine; record the trust manager's verdict per engine; classify failures; `tls_handshake_failures_total{listener, reason}` (FR-003, FR-017)
- [x] T025 [US2] `M/jsonrpc/server/http/JsonRpcWsServer.scala:137-141`: bind with `TlsServerEngine` when `rpc.ws.tls.enabled`; per-server `ServerSettings` with `parsing.ssl-session-attribute = on` for HTTP and WS (research D-2); add `rpc.ws.tls` to `network.conf`
- [x] T026 [US2] Same-port HTTP/WS with differing TLS settings is a startup error (spec Edge Cases) in `M/tls/TlsStartupCheck.scala`
- [x] T027 [P] [US2] `tls/gen-cert.sh`: `ca`, `server`, `client` subcommands (FR-062, first part)
- [ ] T028 [US2] `sbt pp`, `formatCheck`; S2's PR

**Checkpoint**: mTLS on HTTP and WS; the identity of the peer is available on every request.

---

## Phase 5: User Story 3 — Client-certificate identity selects namespaces (P2) — S3

**Goal**: identity rules grant namespaces (beyond the transport's `apis` if they say so) and gate GraphQL.

**Independent Test**: spec US3; quickstart Q3.

### Tests first

- [x] T029 [P] [US3] `T/tls/IdentityRulesSpec.scala`: parsing (unique names, label-safe, known namespaces or `graphql`, at least one match key), first match wins, AND of keys, case rules, fingerprint with or without colons; ScalaCheck: a rule never matches a certificate that does not carry its values
- [x] T030 [P] [US3] `T/jsonrpc/server/http/IdentityRulesRouteSpec.scala`: US3 scenarios 1–5 over HTTPS: denial = `-32601` and counted with the rule name; unmatched and anonymous get the transport set; a rule grants `admin` beyond `eth,net,web3`; batch mixes; `rpc_modules` returns the caller's set; GraphQL refused unless the rule lists `graphql` (FR-021–FR-025, FR-028)
- [x] T031 [P] [US3] `T/jsonrpc/server/http/IdentityRulesWsSpec.scala`: identity fixed at the upgrade (FR-026)
- [x] T032 [P] [US3] `T/tls/ForgedIdentitySpec.scala`: a self-signed certificate copying a rule's SAN and CN fails the handshake and never reaches the rules (Security Considerations)
- [x] T033 [P] [US3] Extend the `RpcExposureCheck` spec in `T/jsonrpc/server/`: a namespace granted only by a rule does not warn (FR-027)
- [x] T034 [P] [US3] `T/tls/IdentityStartupSpec.scala`: a rule naming `graphql` with `rpc.graphql.enabled = false` refuses the start; a non-empty rule list with no want/need listener refuses the start (contracts/config.md)

### Implementation

- [x] T035 [US3] `M/tls/IdentityRules.scala`: `CertMatch`, `IdentityRule`, `IdentityRules`, `Caller`, `effectiveApis`, `graphQlAllowed`, session cache with generation (data-model.md, research D-3, D-4)
- [x] T036 [US3] `M/jsonrpc/JsonRpcController.scala`: a `forApis` copy per rule, built when the rules load; `rpc_namespace_denials_total{identity_rule, namespace}` at the not-found path for a namespace outside the caller's set (FR-024)
- [x] T037 [US3] `M/jsonrpc/server/http/JsonRpcHttpServer.scala`: resolve the caller from `AttributeKeys.sslSession`, dispatch to its controller (batches included), gate `/graphql` (FR-028) (depends on T035, T036)
- [x] T038 [US3] `M/jsonrpc/server/http/JsonRpcWsServer.scala`: resolve at the upgrade, hand the controller to the connection (FR-026)
- [x] T039 [US3] `M/jsonrpc/server/RpcExposureCheck.scala`: evaluate the transport set only (FR-027); startup lines per rule (FR-060); `network.rpc.client-identities` in `network.conf`
- [ ] T040 [US3] `sbt pp`, `formatCheck`; S3's PR (note in the PR body that FR-028 awaits the user's confirmation, and which tasks drop it if declined: T030's GraphQL cases, T034's first case, T037's gate)

---

## Phase 6: User Story 4 — Rotation without a restart (P2) — S4

**Goal**: profiles and identity rules reload; failures keep the old material; expiry is visible.

**Independent Test**: spec US4; quickstart Q4.

### Tests first

- [x] T041 [P] [US4] `T/tls/FilePollerSpec.scala` with a manual clock: SHA-256 change detection, a same-second edit detected, a symlinked `..data` swap detected (Kubernetes shape)
- [x] T042 [P] [US4] `T/tls/TlsReloaderSpec.scala`: a valid replacement swaps managers and bumps `tls_reloads_total`; an unreadable one keeps the old material, logs, bumps `tls_reload_failures_total{profile}`, and the node stays up (FR-030, FR-031)
- [x] T043 [P] [US4] `T/tls/SessionInvalidationSpec.scala`: after a CA is removed from a truststore, a client resuming an earlier session gets a full handshake that fails (US4 scenario 3; research D-1)
- [x] T044 [P] [US4] `T/tls/ExpiryGaugeSpec.scala`: `tls_certificate_expiry_seconds{profile, role}` and the daily warning within `expiry-warning` (FR-032)
- [x] T045 [US4] `IT/tls/TlsRotationItSpec.scala`: certificate A → B on disk while a keep-alive loop runs; new handshakes see B within one interval; 0 failed requests on the open connection; identity-rules file reload applies to new HTTP requests and not to an open WS (SC-003, FR-033)

### Implementation

- [x] T046 [US4] `M/tls/TlsContexts.scala`: `ReloadingKeyManager`, `ReloadingTrustManager`, swap + session invalidation (research D-1)
- [x] T047 [US4] `M/tls/FilePoller.scala` and `M/tls/TlsReloader.scala`: one named daemon scheduler per node; reload settings per profile; metrics (research D-13)
- [x] T048 [US4] `network.rpc.client-identities-file` in `M/tls/IdentityRules.scala` and `network.conf`; both inline and file set is a startup error (research D-5)
- [ ] T049 [US4] `sbt pp`, `formatCheck`; S4's PR

---

## Phase 7: User Story 6 — TLS on the Engine API and metrics (P3) — S5

**Goal**: Engine API server TLS with JWT unchanged; metrics on loopback by default, with TLS and client auth.

**Independent Test**: spec US6; quickstart Q5.

### Tests first

- [x] T050 [P] [US6] `T/consensus/engine/EngineApiTlsSpec.scala`: over TLS, a JWT-bearing call to `engine_exchangeCapabilities` is served and a JWT-less one gets 401 exactly as today; a `client-auth` key under `engine-api.tls` refuses the start with the contracts/observability.md text (FR-015; US6 scenarios 1, 3)
- [x] T051 [P] [US6] `T/metrics/MetricsTlsServerSpec.scala`: `need` serves `/metrics` to a trusted client and fails the handshake otherwise, with FR-017 reasons; the body equals the plaintext scrape of the same registry (US6 scenario 2)
- [x] T052 [P] [US6] `T/metrics/MetricsBindSpec.scala`: with no `interface`, the plaintext server binds loopback, not the wildcard (FR-016; US8 scenario 3)

### Implementation

- [x] T053 [US6] `M/consensus/engine/EngineApiHttpServer.scala:190-204, 232` and `M/nodebuilder/NodeBuilder.scala:1095`: `tls { enabled, profile }` in the config; bind with `TlsServerEngine` and `ClientAuth.None`; `engine-api.tls` in `network.conf` (beacon reviews: transport only)
- [x] T054 [US6] `M/metrics/MetricsConfig.scala`, `M/metrics/Metrics.scala:21-22`: `interface` (default `localhost`) passed as `.hostname`; `tls` block; `src/main/resources/conf/base/metrics.conf` (fix its "over localhost" comment)
- [x] T055 [US6] `M/metrics/MetricsTlsServer.scala`: Pekko route `GET /metrics` via `PrometheusScrapeHandler` and a `PrometheusHttpExchange` adapter, bound with `TlsServerEngine` (research D-7)
- [ ] T056 [US6] `sbt pp`, `formatCheck`; S5's PR

---

## Phase 8: User Story 8 — No plaintext listener on a non-loopback interface (P2) — S6, BREAKING

**Goal**: FR-018 on all four listeners, with the opt-out, the migration of the repository's own deployments, and the
release-note and runbook requirements of FR-019.

**Independent Test**: spec US8; quickstart Q6.

### Tests first

- [ ] T057 [P] [US8] `T/tls/BindPolicySpec.scala`: loopback = every resolved address is loopback; `0.0.0.0`, `::`, a LAN address and a name resolving to one are not; an unresolvable name is an error (spec Edge Cases)
- [ ] T058 [P] [US8] `T/nodebuilder/BindPolicyStartupSpec.scala`: the 12 cases of SC-008 refuse before any bind with the contracts/observability.md text; TLS on or `allow-insecure-bind = true` starts (with the WARN line); loopback values start (US8 scenarios 1, 2)

### Implementation

- [ ] T059 [US8] `M/tls/BindPolicy.scala`; call it from `M/tls/TlsStartupCheck.scala` for HTTP, WS, Engine API, metrics and the faucet listener; `RpcExposureCheck` and `M/console/panels/RpcPanels.scala`'s exposure use `BindPolicy.isLoopback`; `allow-insecure-bind` keys in `network.conf` and `metrics.conf`
- [ ] T060 [US8] Migrate the repository's own deployments (research 1.7): `hive/fukuii/fukuii.sh` (`allow-insecure-bind` for rpc.http and engine-api), `docker/fukuii/docker-compose.yml` and `prometheus.yml` (rpc.http and metrics, comment pointing to TLS), `ops/barad-dur/**/fukuii-conf*/*.conf`, `ops/cirith-ungol/conf/etc.conf`
- [ ] T061 [US8] FR-019 docs in the same PR: `CHANGELOG.md` Unreleased entry marked **Breaking**; the "Read before upgrading" item in the next `docs/releases/<version>.md`; the migration section of `docs/runbooks/tls-operations.md` (`#migration`, the anchor the refusal text names)
- [ ] T062 [US8] ADR `docs/adr/operations/OPS-005-zero-trust-listeners.md` (listener posture, identity as grant, allowlist instead of P2P TLS) and its row in `docs/adr/operations/README.md`
- [ ] T063 [US8] Hive Engine API smoke run with the migrated `fukuii.sh`; `sbt pp`, `formatCheck`; S6's PR, flagged breaking in title and body

---

## Phase 9: User Story 5 — One outbound TLS client factory (P2) — S7, S8

**Goal**: every outbound HTTPS client from one factory; hostname verification, named trust, optional client
certificate, no fallback, no https→http redirect; a CI ratchet.

**Independent Test**: spec US5; quickstart Q7.

### Tests first (S7)

- [x] T064 [P] [US5] `T/tls/OutboundTlsSpec.scala` against a local HTTPS server from `TestPki`: per client kind (Pekko, JDK `HttpClient`, `HttpsURLConnection`): the test CA's profile connects, `system` fails, a SAN mismatch fails, a `need` server succeeds only with a keyed profile, an https→http redirect is refused (FR-041–FR-043; US5 scenarios 1–3)
- [x] T065 [P] [US5] `T/scripts/OutboundTlsRatchetSpec.scala`, modelled on `T/scripts/HeadWriterRatchetSpec.scala`: a planted `SSLContext.getInstance` outside `tls/` fails; an allow-listed one passes; a stale line fails (FR-045)

### Implementation (S7)

- [x] T066 [US5] `M/tls/OutboundTls.scala`: `sslContext`, `jdkHttpClient`, `pekkoHttpsContext`, `openHttpsStream`; `system` profile; endpoint identification on every client engine (research D-9)
- [x] T067 [US5] Move `M/jsonrpc/client/RpcClient.scala` and `M/faucet/jsonrpc/WalletRpcClient.scala` / `FaucetBuilder.scala` onto the factory with `faucet.rpc-client.tls-profile`, mapping the legacy `certificate` block (FR-007, FR-008)
- [x] T068 [P] [US5] Move `M/blockchain/checkpoint/CheckpointDownloader.scala:194-198` (redirect `HttpsOnly`) and `M/ephemery/ReleaseTransport.scala:389-392` (keeps `NEVER`) onto the factory with their `tls-profile` keys (FR-044); extend `T/ephemery/JdkHttpsTransportSpec.scala`
- [x] T069 [US5] `scripts/check-outbound-tls.sh` + `scripts/outbound-tls-allowlist.txt` (sites of S8 listed "migrated in S8"); run it in `.github/workflows/ci.yml` beside `check-head-writers.sh`
- [ ] T070 [US5] `sbt pp`, `formatCheck`; S7's PR

### Tests first (S8)

- [ ] T071 [P] [US5] Extend or add specs for `WorkNotifier`, `BootstrapDownload`, `ExternalIPDetector` and `EndpointProbe`: each uses its profile; `EndpointProbe` uses the probed listener's profile for a `need` listener and still reports "not verified" otherwise (FR-046)

### Implementation (S8)

- [ ] T072 [P] [US5] `M/consensus/pow/WorkNotifier.scala:49`, `M/BootstrapDownload.scala:44`, `M/network/ExternalIPDetector.scala:342` onto the factory with their `tls-profile` keys
- [ ] T073 [US5] `M/console/ops/EndpointProbe.scala:84-104`: profile selection per FR-046; its recording trust manager stays as the one allow-listed exception, with that reason
- [ ] T074 [US5] Remove the S8 lines from `scripts/outbound-tls-allowlist.txt` (SC-004); delete `M/security/SSLContextFactory.scala`, `SSLContextBuilder.scala`, `SSLConfig.scala` and `KeyStoreUtils.getTrustManager` once unreferenced (record the Chesterton note in the PR: they served the single HTTPS listener and the faucet client)
- [ ] T075 [US5] `sbt pp`, `formatCheck`; S8's PR

---

## Phase 10: User Story 7 — Consortium P2P node-ID allowlist (P2) — S9, S10

**Goal**: only allowlisted node IDs connect, inbound and outbound; discovery off; runtime edits persisted atomically.

**Independent Test**: spec US7; quickstart Q8.

### Tests first (S9)

- [x] T076 [P] [US7] `T/network/NodeAllowlistSpec.scala` (ScalaCheck): 128-hex and `enode://` parsing, case-insensitive ids, own id dropped, bad entry is an error, sources merged (FR-050)
- [x] T077 [P] [US7] `T/network/AllowlistHandshakeSpec.scala` with Pekko TestKit on `RLPxConnectionHandler`: an unlisted key is rejected after `AuthHandshakeSuccess` and **before** a `Hello` already present in `remainingData` is decoded; `ConnectionEstablished` is never sent; `p2p_allowlist_rejections_total{direction="inbound"}` +1 (FR-051)
- [x] T078 [P] [US7] `T/network/PeerManagerAllowlistSpec.scala`: `connectWith` never dials an unlisted node from any source; `admin_addPeer`, `admin_addTrustedPeer`, `net_connectToPeer` answer the FR-052 error; a generation change disconnects a removed peer and dials an added entry with an address (FR-052, FR-058)
- [x] T079 [P] [US7] `T/network/discovery/DiscoveryAllowlistSpec.scala`: with the allowlist on, `DiscoveryConfig` disables discovery and DNS trees; no UDP bind; the WARN line when the config said `true` (FR-055)
- [x] T080 [P] [US7] `T/nodebuilder/AllowlistStartupSpec.scala`: a static node, bootnode or trusted peer not listed refuses the start (FR-053); allowlist off = unchanged behaviour (US7 scenario 4)
- [ ] T081 [US7] `IT/network/AllowlistThreeNodeItSpec.scala`: A, B, C on a dev chain; B syncs with A, C is refused, A never dials C, 0 devp2p messages from C past auth over the run (SC-005)

### Implementation (S9)

- [x] T082 [US7] `M/network/NodeAllowlist.scala` (snapshot, generation) and `M/network/NodeAllowlistStore.scala` (load from config, file, static nodes, bootnodes; reload through the poller — or a minimal local poller if S4 has not merged, replaced when it does) (research D-11)
- [x] T083 [US7] `M/network/rlpx/RLPxConnectionHandler.scala:593`: the check before `ConnectionEstablished`; `ConnectionRejected(NotOnAllowlist)`; `M/network/PeerActor.scala` never retries it
- [x] T084 [US7] `M/network/PeerManagerActor.scala:723-770`: dial filter; subscribe to generation changes; disconnect removed peers (`UselessPeer`, herald to confirm); dial added entries with an address
- [x] T085 [US7] `M/network/discovery/DiscoveryConfig.scala`, `PeerDiscoveryManager.scala`: `allowlistEnabled` forces discovery and the DNS walk off (FR-055)
- [ ] T086 [US7] (S9 landed: FR-052 errors in `AdminService.addPeer`/`addTrustedPeer` and `NetService.connectToPeer`, the FR-053 startup check, the FR-060 line and the `network.conf` block; the FR-060 line for the file path is still to do) `M/jsonrpc/AdminService.scala` and `M/jsonrpc/NetService.scala` (`connectToPeer`, dispatched at `JsonRpcController.scala:196`): FR-052 errors; startup checks and lines in `M/nodebuilder/StdNode.scala` (FR-053, FR-060); `peer.node-allowlist` in `network.conf`
- [ ] T087 [US7] `sbt pp`, `formatCheck`, the three-node IT; S9's PR

### Tests first (S10)

- [ ] T088 [P] [US7] `T/network/NodeAllowlistStoreSpec.scala`: edit = re-read-if-changed → apply → tmp → fsync → rename → record SHA-256; the next poll sees no change; a hand edit is picked up; an unparseable file refuses the edit and keeps the list; a failed write leaves file and list unchanged (FR-057)
- [ ] T089 [P] [US7] `T/jsonrpc/AdminAllowlistMethodsSpec.scala`: every row of contracts/admin-allowlist.md's error table; all-or-nothing; `admin_getNodesAllowlist` shape; INFO line with the caller's rule name (FR-056, FR-059)
- [ ] T090 [P] [US7] `T/jsonrpc/server/http/AllowlistAuthSpec.scala`: without `admin` in the caller's set the three methods answer `-32601` over HTTP; with an identity rule granting `admin` to one certificate on a `need` listener, only that certificate can call them (US7 scenario 7; FR-059)
- [ ] T091 [US7] `IT/network/AllowlistCrashSafetyItSpec.scala`: a child JVM killed before the tmp write, after it, and after the rename; the file always parses and is the old or the new list; 100 alternating `admin_` and file edits leave the in-memory list equal to the file (SC-009)

### Implementation (S10)

- [ ] T092 [US7] `M/network/NodeAllowlistStore.scala`: `edit` under one lock with the atomic write sequence (research D-11); removal rules by source (data-model.md)
- [ ] T093 [US7] `M/jsonrpc/AdminService.scala`, `M/jsonrpc/AdminJsonMethodsImplicits.scala`, `M/jsonrpc/JsonRpcController.scala:639-670`: `admin_addNodesToAllowlist`, `admin_removeNodesFromAllowlist`, `admin_getNodesAllowlist`; `p2p_allowlist_edits_total`; not added to any default `apis` (FR-056, FR-059)
- [ ] T094 [US7] `sbt pp`, `formatCheck`, the crash IT; S10's PR

---

## Phase 11: Polish and cross-cutting — S11

- [ ] T095 [P] `T/console/panels/RpcPanelsTlsSpec.scala` then `M/console/panels/RpcPanels.scala`: TLS state, client-auth mode and `allow-insecure-bind` per listener (FR-061)
- [ ] T096 [P] `docs/runbooks/tls-operations.md`, `docs/runbooks/enterprise-deployment.md` (make the mTLS claim true; B-147), `docs/runbooks/security.md`, `docs/runbooks/peering.md` (allowlist, discovery off, `admin_` methods), `docs/MCP.md` per B-147 (FR-062); revocation guidance: short-lived certificates, reload, bounded WS idle timeout (Security Considerations)
- [ ] T097 [P] Skills: `.claude/skills/fukuii-tls-operations/`, `fukuii-security-hardening/`, `fukuii-peer-management/`, `fukuii-custom-networks/` updated to the new keys and methods
- [ ] T098 [P] B-149: `docker/Dockerfile`, `docker/Dockerfile.mordor` healthcheck scripts use the configured port and scheme and, under `need`, a client profile (spec Edge Cases)
- [ ] T099 Run quickstart.md Q1–Q8 by hand and record the results in S11's PR
- [ ] T100 `./scripts/fukuii-test essential` once; `sbt formatCheck`; S11's PR closes B-147 and B-149; then close spec 030 per `docs/development/spec-lifecycle.md` (Status `Done`, Closed block, index regenerated)

---

## Dependencies & Execution Order

### Phase / slice dependencies

- Phase 1–2 + Phase 3 = S1: no dependency (B-148 landed in #1905).
- S2 (Phase 4) ← S1. S3 (Phase 5) ← S2. S4 (Phase 6) ← S1, S3.
- S5 (Phase 7) ← S1 (S2 for metrics `need` tests).
- S6 (Phase 8) ← S2, S5 (every guarded listener must have a TLS option first).
- S7 ← S1; S8 ← S7.
- S9 ← nothing (uses S4's poller if merged, else its own minimal one); S10 ← S9.
- S11 ← S1–S10.

### Within each slice

Tests first and failing; config and types before services; services before wiring in `NodeBuilder`/`StdNode`;
`sbt compile-all` after every file; `testOnly` the slice's specs; `sbt pp` + `formatCheck` before the PR.

### Parallel opportunities

- Two lanes from the start: TLS (S1 → S2 → S3 → S4, with S5, S7 → S8 branching off S1) and P2P (S9 → S10).
- Inside a slice, every `[P]` test task runs in parallel; T008/T009, T068, T072 are parallel implementation tasks.

## Parallel Example: User Story 7 (S9)

```bash
Task: "T076 NodeAllowlistSpec in src/test/scala/com/chipprbots/ethereum/network/NodeAllowlistSpec.scala"
Task: "T077 AllowlistHandshakeSpec in src/test/scala/com/chipprbots/ethereum/network/AllowlistHandshakeSpec.scala"
Task: "T078 PeerManagerAllowlistSpec in src/test/scala/com/chipprbots/ethereum/network/PeerManagerAllowlistSpec.scala"
Task: "T079 DiscoveryAllowlistSpec in src/test/scala/com/chipprbots/ethereum/network/discovery/DiscoveryAllowlistSpec.scala"
Task: "T080 AllowlistStartupSpec in src/test/scala/com/chipprbots/ethereum/nodebuilder/AllowlistStartupSpec.scala"
```

## Implementation Strategy

### MVP first

S1 (Phases 1–3): fail closed on every TLS misconfiguration, the profile model in place, the HTTP listener on it.
Stop and validate with quickstart Q1.

### Incremental delivery

S2 (mTLS) and S9 (allowlist) are each a usable increment on their own; S3–S5 build the TLS lane out; S6 is the
breaking change and waits for S2 and S5; S7–S8 close the outbound class of bugs; S10 and S11 finish.

## Notes

- [P] = different files, no dependency on an unfinished task; [USn] maps to spec.md's stories.
- Each slice is an item in the shared backlog, added and claimed before work (constitution Principle VIII); the PR
  body carries `Backlog: B-0xx` for the slice and `Refs: B-146`.
- Consensus-adjacent slices name their reviewer in plan.md (beacon for S5/S6's Engine API, herald for S9/S10).
- FR-028 (GraphQL gating) was confirmed by the user on 2026-11-18.
