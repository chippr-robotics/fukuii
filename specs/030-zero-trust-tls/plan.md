# Implementation Plan: Zero-Trust TLS — mTLS on Every Listener, One Outbound TLS Factory, Node-ID Allowlist

**Branch**: `spec/b146-030-clarify` (this plan; each slice gets its own `feature/b146-sN-…` branch) | **Date**: 2026-11-18
| **Spec**: [spec.md](./spec.md) (its Clarifications session of 2026-11-18 is binding)

**Input**: Feature specification from `specs/030-zero-trust-tls/spec.md`; design record [research.md](./research.md).

## Summary

Every service listener (JSON-RPC HTTP with GraphQL and health, WebSocket, Engine API, metrics) gets one `tls` block
over shared named profiles, validated in full before any port binds. HTTP, WS and metrics take `client-auth = none |
want | need`; the Engine API takes server TLS only and keeps JWT as its client check (Q1). A verified client
certificate on HTTP or WS selects the caller's namespaces through ordered identity rules that may grant more than the
listener's `apis` (Q3), and gate GraphQL (pending confirmation). Profiles, identity rules and the P2P allowlist reload
without a restart. A listener that binds a non-loopback interface without TLS refuses the start unless
`allow-insecure-bind` is set (Q2, a breaking change with a migration section). All outbound HTTPS goes through one
factory, held by a CI ratchet. A consortium node accepts and dials only allowlisted node IDs, checked right after the
RLPx auth handshake; discovery is off in that mode, and three `admin_` methods edit the allowlist at runtime and
persist it atomically (Q4). Revocation is out of scope (Q5).

The approach (research D-1): one `SSLContext` per profile and role whose key and trust managers delegate to a
swappable reference, so rotation needs no rebind and no client rebuild; a Pekko HTTP engine factory per listener sets
client auth and the protocol floor (D-2); identity is resolved once per TLS session and cached in the session (D-3).

Eleven slices, one PR each (table below). No consensus rule, wire format or storage schema changes. No new
dependency.

## Technical Context

**Language/Version**: Scala 3.9.0 (LTS) with `-source:3.9`, JDK 25, sbt 1.13.0.

**Primary Dependencies**: JSSE (`SSLContext`, `X509ExtendedKeyManager`, `X509ExtendedTrustManager`, `SSLEngine`);
Pekko HTTP 1.3.0 (`ConnectionContext.httpsServer(() => SSLEngine)`, `AttributeKeys.sslSession`, verified in the jar,
research D-2); JDK `java.net.http.HttpClient` and `HttpsURLConnection` for outbound; Prometheus Java client 1.3.10
(`HTTPServer.Builder.hostname`, `PrometheusScrapeHandler`, verified with `javap`, research D-7); BouncyCastle for the
test PKI only (already a dependency); Typesafe Config; json4s for the allowlist file (the `StaticNodesLoader` parser).
**No new dependency.**

**Storage**: files only — keystores, truststores, password files, an optional identity-rules file and the allowlist
file, all operator-owned. The allowlist file is written atomically by the node (FR-057). No RocksDB change.

**Testing**: ScalaTest `AnyFlatSpec`/`AnyWordSpec` tagged `UnitTest`; `IntegrationTest` for the multi-node
allowlist and the end-to-end listener tests; Pekko HTTP testkit; Pekko TestKit for `PeerManagerActor` and
`RLPxConnectionHandler`; ScalaCheck for the allowlist and identity-rule parsers. Certificates are generated per test
by `TestPki` (research D-12). No `Thread.sleep`: `eventually`, latches and a manual clock for the reload poller.

**Target Platform**: Linux servers and containers (Docker, Kubernetes with secret mounts); macOS for development.

**Project Type**: single sbt root module; new package `com.chipprbots.ethereum.tls`.

**Performance Goals**: SC-006 — `client-auth = need` with 20 identity rules within 5 % of `client-auth = none` on
sustained `eth_blockNumber` (identity once per session, D-3). Reload poll: a few file hashes a minute. RLPx allowlist
check: one `AtomicReference` read and a set lookup per inbound handshake.

**Constraints**: fail closed (FR-010–FR-012); no secret in any log line (tested); the Engine API's request handling
unchanged except the transport (beacon); the RLPx check before any `Hello` decoding (herald); scalafmt 120 columns,
scalafix clean; one heavy JVM at a time on the build host.

**Scale/Scope**: about 3,800 lines of main code and 5,000 of tests over eleven slices; ~20 new files under `tls/`,
`network/` and `scripts/`; edits listed under Project Structure.

## Constitution Check

*GATE: passed before research; re-checked after the design below (2026-11-18).*

| Principle | Engaged? | Status / justification |
| --- | --- | --- |
| **I. Consensus determinism** | By proximity: slice 5 edits `consensus/engine/EngineApiHttpServer.scala`; slices 9–10 edit `network/rlpx/` and `PeerManagerActor`. | **PASS.** Slice 5 changes the Engine API's transport only (TLS on the bind); `JwtAuthenticator`, routes, responses, ordering and timing are untouched; `beacon` reviews before merge. Slices 9–10 filter connections by node ID before the devp2p `Hello`; no message format, capability negotiation or sync logic changes (FR-054); `herald` reviews. Both chain families get the same filter; no ETC/ETH path mixes. |
| **II. Spec-driven** | Yes | PASS. spec.md (clarified 2026-11-18), research.md, this plan, data-model.md, contracts/, quickstart.md, tasks.md. |
| **III. Tests & coverage** | Yes | PASS. Tests first in every slice (tasks.md). TLS behaviour is tested against real handshakes with a generated PKI, not mocks. Reload uses a manual clock. The kill-at-each-step test of FR-057 (SC-009) runs the store in a child JVM in `IntegrationTest`. No `Thread.sleep`. |
| **IV. Idiomatic Scala 3** | Yes | PASS. `enum` for `ClientAuth`, `ListenerId`, `Caller`, `RedirectPolicy`; opaque `NodeId` reuse where it exists; explicit result types; `Either` for validation with one error type `TlsConfigError`; no `return`. `formatCheck` per slice. |
| **V. Quality gates** | Yes | PASS. One PR per slice; `sbt pp` and `formatCheck` before each PR; the new ratchet joins `ci.yml` (slice 7). |
| **VI. Security & operational safety** | **Central** | PASS. Every slice is on a network-exposed surface and names its reviewer (table). Defaults narrow exposure (metrics leave the wildcard; plaintext on a routable interface becomes an explicit choice). No secret logged (a planted-password test in slice 1, extended per slice). Key files stay out of git (`.gitignore` unchanged; `TestPki` writes to temp dirs). The `admin_` allowlist methods are in a namespace no HTTP/WS default grants (FR-059). Threat items (downgrade, default-trust fallback, label cardinality, log flooding, redirects, forged SAN on an untrusted chain, half-written allowlist) each have a test named in tasks.md. |
| **VII. Versioning & decisions** | Yes | PASS. PATCH per merge. Slice 6 is **breaking**: CHANGELOG "Breaking" entry, a release-notes "Read before upgrading" item and the runbook migration section in the same PR (FR-019). FR-007's deprecation names its removal release. One ADR, `docs/adr/operations/OPS-005-zero-trust-listeners.md` (the listener posture, identity-as-grant, allowlist-over-TLS for P2P), written in slice 6. |
| **VIII. Backlog** | Yes | PASS. B-146 (claimed by `cloud-dc960fdc` / `spec-b146`) holds this spec PR. Each slice is a new backlog item, added and claimed when it starts (table); B-147 and B-149 close in slice 11. |
| **Module boundaries** | Yes | PASS. `tls/` depends on `security/` (to be folded into it), `utils/` and `metrics/`; `jsonrpc/`, `consensus/engine/`, `metrics/`, `faucet/`, `blockchain/checkpoint/`, `ephemery/`, `console/` and `network/` depend on `tls/`; `tls/` depends on none of them. The allowlist lives in `network/` and does not depend on `tls/` except for the shared file poller (`tls/FilePoller`, which depends on nothing). |

## Project Structure

### Documentation (this feature)

```text
specs/030-zero-trust-tls/
├── spec.md               # clarified 2026-11-18
├── research.md           # the survey and decisions D-1…D-13
├── plan.md               # this file
├── data-model.md         # types, validation, state, metrics, by slice
├── quickstart.md         # the manual gate
├── contracts/
│   ├── config.md         # every key, default and slice
│   ├── admin-allowlist.md# the three admin_ methods and their errors
│   └── observability.md  # refusals, startup lines, runtime log lines
└── tasks.md
```

### Source code (repository root)

```text
src/main/scala/com/chipprbots/ethereum/
├── tls/                                   # NEW package
│   ├── TlsProfile.scala, TlsConfig.scala  # S1  profiles, listener blocks, legacy mapping (FR-001–FR-008)
│   ├── TlsMaterialLoader.scala            # S1  load + validate (FR-010), char[] passwords zeroed
│   ├── TlsContexts.scala                  # S1  one SSLContext per (profile, role); S4 reloading managers (D-1)
│   ├── TlsServerEngine.scala              # S1  engine factory; S2 client auth, handshake-failure reasons (FR-017)
│   ├── TlsStartupCheck.scala              # S1  the pre-bind validation pass; S6 BindPolicy
│   ├── BindPolicy.scala                   # S6  loopback rule (FR-018); shared with RpcExposureCheck
│   ├── IdentityRules.scala                # S3  rules, matching, Caller, session cache (D-3, D-4)
│   ├── FilePoller.scala, TlsReloader.scala# S4  SHA-256 polling, reload, expiry gauge (FR-030–FR-033)
│   └── OutboundTls.scala                  # S7  the factory (FR-040–FR-046)
├── security/SSLContextFactory.scala, KeyStoreUtils.scala, SSLContextBuilder.scala
│                                          # S1  EDIT: become thin adapters over tls/, then deleted in S8
├── jsonrpc/server/http/
│   ├── JsonRpcHttpServer.scala            # S1  tls block; S3 per-caller controller, GraphQL gate (FR-028)
│   ├── SecureJsonRpcHttpServer.scala      # S1  TlsServerEngine instead of httpsServer(sslContext)
│   ├── JsonRpcWsServer.scala              # S2  WSS; S3 identity at upgrade (FR-026)
│   └── JsonRpcController.scala (jsonrpc/) # S3  forApis copies per rule; denial counting (FR-024)
├── jsonrpc/server/RpcExposureCheck.scala  # S3  transport set only (FR-027); S6 BindPolicy.isLoopback
├── consensus/engine/EngineApiHttpServer.scala   # S5 server TLS (FR-015)
├── metrics/Metrics.scala, MetricsConfig.scala   # S5 interface, tls; MetricsTlsServer.scala NEW (D-7)
├── nodebuilder/NodeBuilder.scala, StdNode.scala # S1–S10 wiring; the pre-bind pass; startup lines (FR-060)
├── faucet/jsonrpc/FaucetBuilder.scala, WalletRpcClient.scala, jsonrpc/client/RpcClient.scala   # S7
├── blockchain/checkpoint/CheckpointDownloader.scala, ephemery/ReleaseTransport.scala            # S7
├── consensus/pow/WorkNotifier.scala, BootstrapDownload.scala, network/ExternalIPDetector.scala,
│   console/ops/EndpointProbe.scala                                                              # S8
├── network/
│   ├── NodeAllowlist.scala                # S9  NEW snapshot, parse, contains (D-11)
│   ├── NodeAllowlistStore.scala           # S9  NEW load + reload; S10 atomic edits (FR-057)
│   ├── rlpx/RLPxConnectionHandler.scala   # S9  check in processHandshakeResult (`:593`) before ConnectionEstablished
│   ├── PeerActor.scala                    # S9  NotOnAllowlist rejection: no retry
│   ├── PeerManagerActor.scala             # S9  dial filter in connectWith (`:723`); S9 disconnect on removal
│   └── discovery/DiscoveryConfig.scala, PeerDiscoveryManager.scala   # S9 forced off + DNS walk off (FR-055)
├── jsonrpc/AdminService.scala, AdminJsonMethodsImplicits.scala, JsonRpcController.scala (`:639-670`)
│                                          # S9 FR-052 errors; S10 three allowlist methods
└── console/panels/RpcPanels.scala         # S11 TLS state and client-auth per listener (FR-061)

src/main/resources/conf/base/
├── tls.conf                               # S1 NEW; included from fukuii.conf
├── network.conf                           # S1 rpc.http.tls; S2 rpc.ws.tls; S3 client-identities; S5 engine-api.tls;
│                                          # S6 allow-insecure-bind ×3; S9 peer.node-allowlist
└── metrics.conf                           # S5 interface, tls; S6 allow-insecure-bind

scripts/check-outbound-tls.sh, scripts/outbound-tls-allowlist.txt   # S7 NEW; S8 allow-list emptied
.github/workflows/ci.yml                   # S7 run the ratchet beside check-head-writers.sh
tls/gen-cert.sh                            # S2 ca / server / client subcommands
hive/fukuii/fukuii.sh, docker/fukuii/docker-compose.yml, docker/fukuii/prometheus/prometheus.yml,
ops/barad-dur/**/fukuii-conf*/*.conf, ops/cirith-ungol/conf/etc.conf      # S6 migration (FR-019)
docs/runbooks/tls-operations.md, enterprise-deployment.md, security.md, peering.md;
.claude/skills/fukuii-tls-operations/, fukuii-security-hardening/, fukuii-peer-management/,
fukuii-custom-networks/; docs/releases/<version>.md; CHANGELOG.md; docs/adr/operations/OPS-005-*.md
                                           # S6 migration + ADR; S11 the rest (FR-062, B-147)
docker/Dockerfile, Dockerfile.mordor (healthcheck.sh)                  # S11 B-149

src/test/scala/com/chipprbots/ethereum/
├── tls/TestPki.scala                      # S1 NEW test PKI (D-12)
├── tls/*Spec.scala                        # a spec per new tls/ file
├── jsonrpc/server/http/SecureJsonRpcHttpServerStartSpec.scala         # S1 EDIT (from #1905)
├── jsonrpc/server/http/{MtlsHttpSpec, MtlsWsSpec, IdentityRulesRouteSpec}.scala   # S2, S3
├── metrics/MetricsTlsServerSpec.scala, consensus/engine/EngineApiTlsSpec.scala    # S5
├── nodebuilder/BindPolicyStartupSpec.scala                            # S6
├── scripts/OutboundTlsRatchetSpec.scala                               # S7
└── network/{NodeAllowlistSpec, NodeAllowlistStoreSpec, AllowlistHandshakeSpec}.scala   # S9, S10
src/it/scala/com/chipprbots/ethereum/
├── tls/TlsRotationItSpec.scala                                        # S4 (SC-003)
└── network/{AllowlistThreeNodeItSpec, AllowlistCrashSafetyItSpec}.scala   # S9 (SC-005), S10 (SC-009)
```

**Structure Decision**: one new package, `tls/`, owns profiles, contexts, engines, reload, the bind rule and the
outbound factory; `security/`'s three TLS files become adapters in S1 and are removed in S8 once nothing calls them
(Chesterton: they exist for the single HTTPS listener and the faucet client, both moved in S1/S7). The allowlist is
P2P and lives in `network/`, sharing only the file poller.

## Architecture

### Server side (S1, S2, S5)

`TlsStartupCheck.run(config)` runs in `StdNode` before the first bind (today the first bind is the Engine API or the
HTTP server, `StdNode.scala:421-440`): it parses every listener block and profile, loads and validates material
(data-model.md table), applies the legacy mapping and, from S6, `BindPolicy`. The first failure becomes
`StdNode.startupRefusal`. Each server then takes `TlsServerEngine.connectionContext(listenerTls)`: a function that
creates an `SSLEngine` from the profile's server context, sets client auth and the TLS ≥ 1.2 protocol list, and wraps
nothing else. Refused handshakes surface in Pekko as a failed TLS stage; S2 classifies them from the
`SSLHandshakeException` cause chain and the trust manager's last verdict (recorded per engine in a small map keyed by
the engine) into FR-017's reasons, with a per-address rate limiter.

### Identity (S3)

The HTTP route reads `optionalAttribute(AttributeKeys.sslSession)`, resolves the `Caller` (D-3) and dispatches to that
caller's controller: `controllers(rule.name)` or the transport controller. Batches use the same controller for every
element. The GraphQL branch asks `graphQlAllowed(caller)` first. The WS route resolves at the upgrade and passes the
controller to the connection's handler. `rpc_modules` already answers the controller's own set (FR-025).

### Rotation (S4)

`TlsReloader` owns one `FilePoller` (D-13). For each profile it hashes the named files each interval; on a change it
loads and validates (the same loader as startup) and swaps the managers (D-1), or keeps the old ones and counts the
failure (FR-031). The identity-rules file and, from S9, the allowlist file register with the same poller.

### Engine API and metrics (S5)

Engine API: `start()` binds with `TlsServerEngine.connectionContext(engineApiTls)`, client auth always `None`. Metrics:
plaintext keeps `PrometheusHTTPServer` with `.hostname(interface)`; TLS uses `MetricsTlsServer` (D-7).

### Non-loopback rule (S6)

`BindPolicy` in the startup check for every enabled listener; `RpcExposureCheck` and the TUI exposure column reuse
`BindPolicy.isLoopback`. The PR carries the migration of hive, Docker and `ops/` (research 1.7).

### Outbound (S7, S8)

`OutboundTls` builds clients from profiles (D-9). S7 moves the faucet client (`RpcClient`, `WalletRpcClient`), the
checkpoint downloader and the Ephemery release transport, and lands the ratchet with the other sites allow-listed;
S8 moves `WorkNotifier`, `BootstrapDownload`, `ExternalIPDetector` and `EndpointProbe`, empties those lines, and
deletes `security/SSLContextFactory`, `SSLContextBuilder` and `KeyStoreUtils.getTrustManager`.

### P2P allowlist (S9, S10)

`NodeAllowlist` (snapshot behind an `AtomicReference`) is read by `RLPxConnectionHandler.processHandshakeResult` and
`PeerManagerActor.connectWith`. Startup builds it from config, the file, static nodes and bootnodes, checks FR-053,
and passes `allowlistEnabled` to `DiscoveryConfig`. A generation change makes `PeerManagerActor` drop peers no longer
listed and add dial candidates. S10 adds `NodeAllowlistStore.edit` and the three `admin_` methods (D-11,
contracts/admin-allowlist.md).

## Slices

Each slice is one PR to `staging`, independently mergeable and revertible, with tests first. Sizes are main + test
lines, approximate. Each slice is a **new backlog item** (added with `backlog.py add`, refs `B-146`), claimed before
work; the title below is the item's title.

| # | Backlog item title (new) | Stories / FRs | Size | Depends on | Reviewer |
| --- | --- | --- | --- | --- | --- |
| S1 | Spec 030 S1: TLS profiles, pre-bind validation, RPC HTTP on profiles (+ legacy `mode = https` mapping) | US1; FR-001, -003–-008, -010–-012, -014 (HTTP, `none`), -060 | M: 700 + 900 | — (B-148 landed) | conduit, prism |
| S2 | Spec 030 S2: client auth want/need on RPC HTTP and WSS; handshake-failure metric; gen-cert CA/client | US2; FR-003, -014, -017, -062 (gen-cert) | M: 500 + 800 | S1 | conduit |
| S3 | Spec 030 S3: client-certificate identity rules grant namespaces; GraphQL gate | US3; FR-020–FR-028 | M: 450 + 700 | S2 | conduit, prism |
| S4 | Spec 030 S4: hot reload of profiles and identity rules; expiry gauge | US4; FR-030–FR-033 | M: 400 + 600 (+ IT) | S1, S3 | conduit |
| S5 | Spec 030 S5: Engine API server TLS; metrics interface and TLS | US6; FR-015, FR-016 | M: 350 + 500 | S1 (S2 for metrics `need`) | beacon (Engine API), conduit |
| S6 | Spec 030 S6 (BREAKING): refuse plaintext non-loopback binds; allow-insecure-bind; migration, ADR | US8; FR-018, FR-019, FR-027 (helper) | S–M: 200 + 400, plus configs and docs | S2, S5 | conduit, beacon (hive Engine API), prism |
| S7 | Spec 030 S7: outbound TLS factory; faucet, checkpoint, Ephemery clients; CI ratchet | US5; FR-040–FR-045, FR-008 | M: 500 + 600 | S1 | conduit |
| S8 | Spec 030 S8: remaining outbound clients on the factory; ratchet clean; retire security/ TLS code | US5; FR-040, FR-045, FR-046; SC-004 | S–M: 300 + 400 | S7 | conduit, prism |
| S9 | Spec 030 S9: P2P node-ID allowlist at the RLPx handshake and every dial; discovery off | US7 (1–4); FR-050–FR-055, FR-058 (reload) | M: 500 + 800 (+ IT) | — (S4's poller if merged first; else its own) | herald |
| S10 | Spec 030 S10: admin_ allowlist methods with atomic file persistence | US7 (5–7); FR-056–FR-059 | M: 350 + 600 (+ crash IT) | S9 | herald, conduit |
| S11 | Spec 030 S11: TUI TLS view, TLS/consortium docs and skills, container healthchecks (closes B-147, B-149) | FR-061, FR-062; B-147, B-149 | S: 150 + 150, docs | S1–S10 | prism |

Critical path: S1 → S2 → S3 → S4; S5 after S1; S6 after S2 and S5; S7 → S8 after S1; S9 → S10 independent of all TLS
slices and can run in parallel from day one. If S9 lands before S4, it carries a minimal poller that S4 then
generalises (tasks.md notes it).

**Per-slice gate**: tests first and failing; `sbt compile-all` per file; `testOnly` the slice's specs; `sbt pp`;
`sbt formatCheck`; for S6, the hive Engine API smoke (`hive/` with `engine-api`), because the opt-out is what keeps
it green; for S9/S10, the three-node IT. `testEssential` once, at the end of S11.

## Risks

| Risk | Mitigation |
| --- | --- |
| Pekko's TLS stage hides why a handshake failed, so FR-017's `reason` is coarse | S2 records the trust manager's verdict per engine before the stage fails; `other` remains for the rest; tested per reason. |
| Session resumption skips a removed CA after a reload (SC-003, Story 4 scenario 3) | Invalidate the context's session caches on every trust change (D-1); a test resumes a session after a CA removal and expects a full, failing handshake. |
| Slice 6 breaks a deployment nobody listed | The refusal names both keys; the migration section lists the shapes; slice 6 lands in a minor release with the "Read before upgrading" item; hive and Docker are migrated in the PR. |
| `HttpsURLConnection` redirect handling in `BootstrapDownload` | Redirects off and a manual https-only loop in the factory (D-9); tested with a local server. |
| The allowlist file edited by hand while an `admin_` edit runs | One lock, re-read-if-changed, atomic rename, SHA-256 bookkeeping (D-11); SC-009 test. |
| A rule set narrower than the anonymous set surprises an operator | The startup line prints each rule's effective set (FR-060); docs call it out. |

## Complexity Tracking

No constitution violation. One choice recorded for review: metrics over TLS are served by a Pekko route rather than
the Prometheus server (research D-7), so two code paths serve `/metrics` (plaintext and TLS); the alternative loses
FR-017 on that listener.
