# Contract: configuration (spec 028)

## Operator keys: `fukuii.ephemery`

They are set in `conf/ephemery.conf`, and an operator conf or `-D` overrides them.

```hocon
fukuii.ephemery {
  # FR-014. false: never contact the network. Run the newest current cached or bundled iteration; after its reset,
  # refuse with exit 78.
  refresh = true

  # FR-014. Where releases come from. One of:
  #   - "https://github.com/ephemery-testnet/ephemery-genesis" (the default; hop allow-list enforced, R8)
  #   - another https base URL, a mirror laid out as <base>/releases/download/ephemery-N/testnet-all.tar.gz
  #     (every hop must be https; any host)
  #   - an absolute path to a directory holding an unpacked release (no network at all)
  source = "https://github.com/ephemery-testnet/ephemery-genesis"

  # SC-002. Total time a startup may spend on the refresh, retries included. Bounded to [10s, 300s].
  refresh-timeout = 60s

  # FR-016. true: at the reset, WARN and exit 75. false: WARN only (for runs without a supervisor).
  exit-at-reset = true
}
```

**Validation.**
- A `source` that is neither `https:` nor an absolute directory is `BadSource`, exit 78.
- An `http:` source is refused, and so is a URL carrying credentials.
- A `refresh-timeout` out of bounds is a configuration error, exit 1, as other `ConfigValidator` errors are.

**Proxy (FR-011).** There are no fukuii keys. The JVM properties `https.proxyHost`, `https.proxyPort` and
`http.nonProxyHosts` apply. When they are unset, `HTTPS_PROXY`/`https_proxy` and `NO_PROXY`/`no_proxy` are read from
the environment.

## Properties the resolver sets (internal, read-only for operators)

They are set as JVM system properties before `utils.Config` loads (R5), only for a managed iteration (data-model.md,
row 3, 4 or 6).

| Property | Value |
|----------|-------|
| `fukuii.blockchains.custom-chains-dir` | `<root>/releases/ephemery-N/chains` |
| `fukuii.datadir` | `<root>/ephemery-N` |
| `logging.logs-dir` | the value before selection (normally `<root>/logs`) |
| `fukuii.network.engine-api.jwt-secret-path` | **only when the operator set none** (B-246): `<root>/jwt.hex`. An operator's value is left alone and no property is set. The node learns it is the resolver's own path from `EphemeryResolver.pinnedJwtSecretPath`, a typed value, and creates the file there; an operator's path is never created |
| `fukuii.ephemery.active.iteration` | `N` |
| `fukuii.ephemery.active.reset-time` | epoch seconds |
| `fukuii.ephemery.active.genesis-hash` | `0x…` |
| `fukuii.ephemery.active.managed` | `true` |

## What moves at each reset (managed iteration)

Every path derived from `fukuii.datadir` follows the iteration, unless the operator sets it as an absolute path. Checked
in `conf/base/*.conf` and `DiscoveryConfig.scala:56` on 2026-11-12.

| Key or file | Default | At each new iteration | To keep it across iterations |
|-------------|---------|-----------------------|------------------------------|
| `db.rocksdb.path` | `${fukuii.datadir}/rocksdb/` | new (by design, FR-009) | – |
| `tmpdir` | `${fukuii.datadir}/tmp` | new | not needed |
| `node-key-file` | `${fukuii.datadir}/node.key` | new: a new enode, as with the script today (R6) | set an absolute path |
| `keystore.keystore-dir` | `${fukuii.datadir}/keystore` | new: accounts do not carry over | set an absolute path |
| `network.rpc.ipc.socket-file` | `${fukuii.datadir}/fukuii.ipc` | moves | set an absolute path |
| `static-nodes.json` | `${fukuii.datadir}/static-nodes.json` | **seeded**: when the resolver creates a new iteration datadir, it copies `<root>/static-nodes.json` into it if that file exists, and never overwrites | put it at `<root>/static-nodes.json` |
| `logging.logs-dir` | `${fukuii.datadir}/logs` | **pinned** to `<root>/logs` by the resolver (R5) | – |
| Engine API `jwt-secret-path` | unset: `<fukuii.datadir>/jwt.hex`, created at the first start (B-246) | unaffected: **pinned** to `<root>/jwt.hex` by the resolver when the operator set none. An operator conf that writes `${fukuii.datadir}/…` would move it, so the runbook says to use an absolute path, as on Sepolia | an absolute path |

An operator conf that substitutes `${fukuii.datadir}` into any other key gets the iteration's directory. The runbook
says so.

## `conf/ephemery.conf` changes

- **Removed:** `fukuii.blockchains.custom-chains-dir = ${user.home}"/.fukuii/ephemery-network/current/chains"`
  (R5). A `custom-chains-dir` now counts only when the operator sets it.
- **Added:** the `fukuii.ephemery` defaults above.
- **Updated:** the header comment. Ephemery is built in; the script is optional; the runbook link stays.
- **Unchanged:** `network = "ephemery"`, `do-snap-sync = false`, `storage-scheme = "path"`, eth70 and eth71, and
  `mining.protocol = "engine-api"`.

## Chain-entry keys added for spec 024 (FR-022, R14)

They go inside the chain object of `hoodi-chain.conf`, the bundled `ephemery-chain.conf`, and every rendered Ephemery
config:

```hocon
consensus = "engine-api"
genesis-hash = "0x<64 hex>"
```

Today's loader ignores both (`BlockchainConfig.fromRawConfig` reads only the keys it names). Spec 024's
`ConfigValidator` takes them over. 028 adds no load-time check of `genesis-hash`.

## `conf/hoodi.conf` (new)

A copy of `conf/sepolia.conf`, with these changes:
- `fukuii.blockchains.network = "hoodi"`;
- the header comment names Hoodi;
- every other key is kept: `do-snap-sync`, `storage-scheme = "path"`, eth70 and eth71, and
  `mining.protocol = "engine-api"`.
