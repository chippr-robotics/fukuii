# Data Model: Zero-Trust TLS (spec 030)

Types are Scala 3 in `com.chipprbots.ethereum.tls` unless another package is named. The slice that introduces each type
is in brackets (plan.md, "Slices"). Config keys are in [contracts/config.md](./contracts/config.md).

## TLS material

```text
enum ClientAuth: None, Want, Need                                              [S1]

final case class StoreRef(path: Path, storeType: String, passwordFile: Path)   [S1]
  invariants: storeType ∈ {"pkcs12", "jks"} (case-insensitive); both files are regular, readable files.

final case class TlsProfile(                                                   [S1]
    name: String,                       // key under fukuii.tls.profiles; "system" is reserved
    keystore: Option[StoreRef],         // required for the server role
    keyAlias: Option[String],
    truststore: Option[StoreRef],
    protocols: Seq[String],             // empty = JDK default filtered to ≥ TLSv1.2
    cipherSuites: Seq[String],          // empty = JDK default
    reload: ReloadPolicy,               // [S4]: enabled (default true), interval (default 1 minute)
    expiryWarning: FiniteDuration       // default 14 days
)
```

Validation (FR-006, FR-010), all before any bind, first failure wins, message = `<config path>: <cause>` and never a
password:

| Check | Error when |
| --- | --- |
| files | a named file is missing, not a regular file, or unreadable |
| password | the keystore or truststore does not open with its password (a trailing newline in the file is trimmed) |
| key entry | the keystore has no private-key entry; or `key-alias` names none; or no alias and more than one entry |
| validity | the leaf is expired or not yet valid (`notBefore`/`notAfter` vs. the node clock) |
| truststore | `client-auth` is `want` or `need` and the listener's profile has no truststore, or it holds no certificate |
| protocols | `protocols` names anything below TLSv1.2, or a name the JDK does not support |
| role | a listener names a profile with no keystore; an outbound site names a profile that does not exist |

```text
final case class TlsMaterial(                                                  [S1]
    keyManager: Option[X509ExtendedKeyManager],
    trustManager: X509ExtendedTrustManager,  // profile truststore, or the JDK default for the client role only
    leaf: Option[X509Certificate],
    fingerprint: Option[String],             // SHA-256 of the files, for reload change detection [S4]
    loadedAt: Instant
)

final class TlsContexts                                                        [S1; reload in S4]
  one SSLContext per (profile, role), initialised with ReloadingKeyManager / ReloadingTrustManager that delegate
  to AtomicReference[TlsMaterial] (research D-1).
  state: Loaded(material, generation) --reload ok--> Loaded(material', generation + 1)
                                       --reload fails--> Loaded(material, generation), tls_reload_failures_total += 1
```

## Listeners

```text
enum ListenerId: RpcHttp, RpcWs, EngineApi, Metrics, FaucetHttp               [S1; WS S2; Engine/metrics S5]

final case class ListenerTls(                                                  [S1]
    id: ListenerId,
    enabled: Boolean,
    profile: Option[String],            // required when enabled
    clientAuth: ClientAuth,             // EngineApi: always None, a configured key is an error (FR-015)
    allowInsecureBind: Boolean          // [S6] FR-018
)

final case class BindDecision(listener: ListenerId, interface: String, resolved: Seq[InetAddress],
                              loopback: Boolean, outcome: Allowed | AllowedInsecure | Refused(message))   [S6]
```

Legacy mapping (FR-007), [S1]: `rpc.http.mode = "https"` + `certificate { … }` ⇒ `ListenerTls(RpcHttp, enabled = true,
profile = "legacy-rpc-http", clientAuth = None)` with an implicit profile whose keystore is the certificate block and
which has **no truststore** (server TLS needs none). Both forms on one listener ⇒ startup error.

## Identity

```text
final case class CertMatch(                                                    [S3]
    sanDns: Option[String], sanUri: Option[String], subjectCn: Option[String], sha256: Option[String])
  invariant: at least one field set; all set fields must match (AND); exact, case-insensitive for DNS and the
  fingerprint (hex, colons ignored), case-sensitive for URI and CN.

final case class IdentityRule(name: String, matchOn: CertMatch, apis: Seq[String])   [S3]
  invariants: name unique, [a-z0-9_-]{1,32} (it is a metric label); apis ⊆ availableApis ∪ {"graphql"};
              "graphql" ∈ apis ⇒ rpc.graphql.enabled (FR-028).

final case class IdentityRules(rules: Vector[IdentityRule], generation: Long)  [S3; file + reload S4]

enum Caller:                                                                    [S3]
  case Anonymous                            // no certificate (want only)
  case Unmatched(subject: String)           // verified, no rule; subject is for logs only, never a label
  case Matched(rule: IdentityRule)

effectiveApis(caller, transportApis) =                                          [S3] (FR-021–FR-023)
  Matched(r)  ⇒ r.apis :+ "rpc"
  otherwise   ⇒ transportApis               // already includes "rpc"
graphQlAllowed(caller) =                                                        [S3] (FR-028)
  Matched(r)  ⇒ r.apis.contains("graphql")
  otherwise   ⇒ rpc.graphql.enabled
```

Cached per TLS session as `SSLSession.putValue("fukuii.rpc.identity", (generation, caller))`; recomputed when the
cached generation is older than `IdentityRules.generation` (research D-3). WS: fixed at upgrade (FR-026).

## Outbound

```text
enum RedirectPolicy: Never, HttpsOnly                                          [S7]
final case class OutboundSite(key: String, profile: String /* default "system" */, redirect: RedirectPolicy)  [S7]
  sites: faucet.rpc-client, sync.checkpoint, ephemery.release, mining.work-notify, bootstrap-download,
         network.external-ip, tui.probe (FR-044)
```

## P2P allowlist

```text
final case class AllowlistEntry(id: NodeId /* 64 bytes */, address: Option[InetSocketAddress],
                                source: Config | File | Static | Bootnode)     [S9]
  parse: 128 hex (optional 0x) or enode://<128 hex>@host:port; case-insensitive id; the node's own id is dropped.

final case class AllowlistSnapshot(entries: Map[NodeId, AllowlistEntry], generation: Long)   [S9]
  contains(id); dialable = entries with an address.

final class NodeAllowlist(AtomicReference[AllowlistSnapshot])                   [S9]
final class NodeAllowlistStore(file: Option[Path], lastLoadedSha256: Option[String])   [S9 reload; S10 edits]
  state: Idle --edit--> Locked: reread-if-changed → apply → write tmp → fsync → atomic rename → fsync dir
                                → lastLoadedSha256 := sha(new) → swap snapshot → Idle
         Locked --any step fails--> delete tmp → Idle, snapshot and file unchanged, error to caller
         Idle --poll, sha ≠ lastLoaded--> parse → ok: swap snapshot, lastLoaded := sha
                                                → fail: keep snapshot, log, p2p_allowlist_reload_failures_total += 1
```

Removal rules (FR-056, Edge Cases): an entry whose only source is `Config`, `Static` or `Bootnode` cannot be removed
at runtime; an entry with a `File` source and another source loses the `File` source only, and stays listed.

## Metrics (names as exported; Micrometer adds the `app_` prefix the node uses)

| Name | Labels | Slice |
| --- | --- | --- |
| `tls_handshake_failures_total` | `listener`, `reason` ∈ {`no_client_cert`, `untrusted_client_cert`, `expired_client_cert`, `protocol`, `other`} | S2 |
| `rpc_namespace_denials_total` | `identity_rule` (a rule name, `anonymous` or `unmatched`), `namespace` | S3 |
| `tls_reloads_total`, `tls_reload_failures_total` | `profile` | S4 |
| `tls_certificate_expiry_seconds` | `profile`, `role` ∈ {`server`, `client`} | S4 |
| `p2p_allowlist_rejections_total` | `direction` ∈ {`inbound`, `outbound`} | S9 |
| `p2p_allowlist_size`, `p2p_allowlist_reload_failures_total` | — | S9 |
| `p2p_allowlist_edits_total` | `op` ∈ {`add`, `remove`}, `result` ∈ {`ok`, `refused`, `failed`} | S10 |
