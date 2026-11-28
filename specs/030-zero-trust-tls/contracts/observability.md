# Contract: log lines, startup refusals and metrics (spec 030)

Metric names and labels are in [../data-model.md](../data-model.md#metrics-names-as-exported-micrometer-adds-the-app_-prefix-the-node-uses).
This file fixes the operator-visible text, so tests can assert on it and docs can quote it.

## Startup refusals (FR-010, FR-018, FR-053; exit non-zero before any bind)

All start with `Refusing to start: ` (the prefix `StdNode.startupRefusal` already uses since #1905) and name the key.

```
Refusing to start: fukuii.tls.profiles.rpc.keystore.path: /etc/fukuii/tls/rpc.p12 not found
Refusing to start: fukuii.tls.profiles.rpc: certificate CN=node1 expired 2026-11-17T00:00:00Z (notAfter)
Refusing to start: fukuii.network.rpc.http.tls: client-auth = need, but profile rpc has no truststore
Refusing to start: fukuii.network.engine-api.tls.client-auth is not supported: the Engine API takes server TLS only; JWT authenticates the consensus client
Refusing to start: fukuii.network.rpc.http binds 0.0.0.0 (not loopback) without TLS. Set fukuii.network.rpc.http.tls.enabled = true with a profile, or fukuii.network.rpc.http.allow-insecure-bind = true to accept plaintext on that interface (docs/runbooks/tls-operations.md#migration)
Refusing to start: static node <id prefix> is not on fukuii.network.peer.node-allowlist
```

No message contains a password, a password-file's content, or key bytes; a test plants a known password and greps
every refusal and log line for it.

## Startup lines (FR-060), one per enabled listener, INFO

```
TLS listener rpc-http https://0.0.0.0:8546 client-auth=need profile=rpc cert="CN=node1" notAfter=2027-01-01 rules=2
TLS listener rpc-http rule monitor: eth,net,web3,rpc
TLS listener rpc-http rule ops: eth,net,web3,admin,debug,graphql,rpc
Plaintext listener metrics http://0.0.0.0:13798 allow-insecure-bind=true            (WARN, every start; FR-018)
P2P node allowlist on: 5 node(s), file /etc/fukuii/allowlist.json, reload every 1 minute; discovery is off   (FR-060)
Discovery is off because the node allowlist is on (network.discovery.discovery-enabled = true is ignored)  (WARN; FR-055)
```

## Runtime

| Event | Level | Rate |
| --- | --- | --- |
| refused TLS handshake | WARN `TLS handshake refused on <listener> from <addr>: <reason>` | once per remote address per minute, then `(<n> suppressed)` (FR-017) |
| namespace denial | INFO `RPC denied <method> to <rule name or anonymous/unmatched> on <listener>` | rate-limited as above, per rule and namespace |
| reload ok | INFO `TLS profile <name> reloaded (<file> changed); new handshakes use cert <subject> notAfter=<date>` | per reload |
| reload failed | ERROR `TLS profile <name> reload failed: <file>: <cause>; keeping the previous material` | per attempt |
| expiry warning | WARN `TLS profile <name> <role> certificate expires in <n> days (<date>)` | daily within `expiry-warning` |
| allowlist rejection | DEBUG per connection; INFO summary each minute with counts | FR-051 |
| allowlist edit | INFO, see contracts/admin-allowlist.md | per call |
