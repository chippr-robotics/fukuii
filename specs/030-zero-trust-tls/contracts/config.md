# Contract: configuration keys (spec 030)

Every key is under `fukuii.` in the operator's file. Defaults are what `src/main/resources/conf/base/*.conf` ships.
A key not listed here does not change. Types follow `data-model.md`.

## Profiles — `tls.profiles.<name>` (new file `conf/base/tls.conf`, slice 1)

```hocon
tls {
  profiles {
    # example; none is defined by default
    # rpc {
    #   keystore   { path = "tls/rpc-server.p12", type = "pkcs12", password-file = "tls/rpc-server.password" }
    #   key-alias  = null            # required when the keystore holds more than one private key
    #   truststore { path = "tls/clients-ca.p12", type = "pkcs12", password-file = "tls/clients-ca.password" }
    #   protocols = []               # e.g. ["TLSv1.3"]; anything below TLSv1.2 is a startup error
    #   cipher-suites = []
    #   reload { enabled = true, interval = 1.minute }      # slice 4
    #   expiry-warning = 14.days                             # slice 4
    # }
  }
}
```

`system` is reserved: the JDK default trust, no client key; outbound only.

## Listeners

| Key | Default | Slice | Notes |
| --- | --- | --- | --- |
| `network.rpc.http.tls.enabled` | `false` | S1 | |
| `network.rpc.http.tls.profile` | `null` | S1 | required when enabled |
| `network.rpc.http.tls.client-auth` | `none` | S1 (`none`), S2 (`want`, `need`) | |
| `network.rpc.ws.tls.{enabled, profile, client-auth}` | `false`, `null`, `none` | S2 | WSS |
| `network.engine-api.tls.{enabled, profile}` | `false`, `null` | S5 | no `client-auth` key: setting one is a startup error (FR-015) |
| `metrics.interface` | `"localhost"` | S5 | **was the wildcard address** (FR-016, FR-019) |
| `metrics.tls.{enabled, profile, client-auth}` | `false`, `null`, `none` | S5 | |
| `network.rpc.http.allow-insecure-bind` | `false` | S6 | FR-018 |
| `network.rpc.ws.allow-insecure-bind` | `false` | S6 | |
| `network.engine-api.allow-insecure-bind` | `false` | S6 | |
| `metrics.allow-insecure-bind` | `false` | S6 | |

Deprecated (FR-007, one minor release): `network.rpc.http.mode = "https"` with `network.rpc.http.certificate { … }`.
It maps to `tls { enabled = true, client-auth = none }` with an implicit server profile and logs, once:

```
fukuii.network.rpc.http.mode = "https" and .certificate are deprecated; use fukuii.network.rpc.http.tls { enabled,
profile, client-auth } and fukuii.tls.profiles.<name> (docs/runbooks/tls-operations.md). They will be removed in <next minor>.
```

Setting `mode = "https"` and `tls.enabled = true` together is a startup error. `faucet.rpc-client.certificate` maps the
same way to an outbound profile (slice 7).

## Identity rules (slice 3; file and reload slice 4)

```hocon
network.rpc {
  client-identities = [
    # { name = "monitor", match { san-dns = "monitor.internal" }, apis = "eth,net,web3" }
    # { name = "ops", match { sha256 = "AB:CD:…" }, apis = "eth,net,web3,admin,debug,graphql" }
  ]
  client-identities-file = null   # a HOCON file holding `client-identities = [ … ]`; reloads (FR-033)
}
```

`match` keys: `san-dns`, `san-uri`, `subject-cn`, `sha256`; all given keys must match. Rules apply to whichever of the
HTTP and WS listeners has `client-auth` `want` or `need`; a non-empty rule list with no such listener enabled is a
startup error (it could never match). `apis` accepts the namespace names of `network.rpc.apis` plus `graphql` (FR-028).

## Outbound sites (slices 7, 8; FR-044)

| Key | Default | Site |
| --- | --- | --- |
| `faucet.rpc-client.tls-profile` | `system` | faucet → node |
| `sync.checkpoint.tls-profile` | `system` | `CheckpointDownloader` |
| `ephemery.release.tls-profile` | `system` | `ReleaseTransport` |
| `mining.work-notify.tls-profile` | `system` | `WorkNotifier` |
| `bootstrap-download.tls-profile` | `system` | `BootstrapDownload` |
| `network.external-ip.tls-profile` | `system` | `ExternalIPDetector` |
| `tui.probe.tls-profile` | `null` | `EndpointProbe`: unset = the probed listener's own profile when it is `need`, else the unverified probe it does today (research D-9) |

The exact parent paths follow each site's existing config block; slice 7 confirms each against `conf/base/*.conf`
before adding it.

## P2P allowlist (slice 9; edits slice 10)

```hocon
network.peer.node-allowlist {
  enabled = false
  nodes = []                        # "enode://<id>@host:port" or a 128-hex node id
  file = null                       # JSON array, static-nodes.json format; needed for admin_ edits
  reload-interval = 1.minute        # how often the file is checked for changes (SHA-256), by the TLS poller
}
```

With `enabled = true`: `network.discovery.discovery-enabled` is treated as `false` (FR-055) and a static node,
bootnode or configured trusted peer not on the list is a startup error (FR-053).
