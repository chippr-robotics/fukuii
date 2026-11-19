# Quickstart: validating spec 030

The automated tests of each slice are in [tasks.md](./tasks.md). This page is the manual gate a reviewer runs before
the closing slice merges, and the scenarios the docs (FR-062) are written from. Keys: [contracts/config.md](./contracts/config.md);
expected text: [contracts/observability.md](./contracts/observability.md).

## Prerequisites

- A built node (`sbt dist` or the assembly) and `openssl`, `curl`, `websocat`, `jq`.
- `tls/gen-cert.sh` with the `ca`, `server` and `client` subcommands (slice 2). Everything is written under a scratch
  directory; nothing is committed.

```bash
D=$(mktemp -d)
tls/gen-cert.sh ca     -o "$D" -n test-ca
tls/gen-cert.sh server -o "$D" --ca "$D/test-ca" -n node --san DNS:localhost,IP:127.0.0.1
tls/gen-cert.sh client -o "$D" --ca "$D/test-ca" -n monitor --cn monitor --san DNS:monitor.internal
tls/gen-cert.sh client -o "$D" --ca "$D/test-ca" -n ops --cn ops --san DNS:ops.internal
tls/gen-cert.sh ca     -o "$D" -n other-ca                     # for the "untrusted" cases
tls/gen-cert.sh client -o "$D" --ca "$D/other-ca" -n stranger --cn stranger
# curl takes PEM: the key of each client keystore
for c in monitor ops stranger; do
  openssl pkcs12 -in "$D/$c.p12" -nocerts -nodes -passin "file:$D/$c.password" -out "$D/$c.key"
done
```

A scratch conf `$D/node.conf` includes the chain conf (Mordor; `gorgoroth.conf` does not start, B-398) and the blocks below.
The profile `rpc` is `keystore = node.p12`, `truststore = test-ca-truststore.p12`, each with its `.password` file.

## Q1. Fail closed (Story 1, SC-001)

Set `network.rpc.http.tls { enabled = true, profile = rpc }` and break one thing per run: the keystore path, the
password file, `key-alias`, `client-auth = need` without a truststore, `protocols = ["TLSv1.1"]`, an expired server
certificate (`gen-cert.sh server -d -1`). **Expect** exit code ≠ 0 within seconds, no port open
(`ss -ltn | grep 8546` empty), and the matching `Refusing to start:` line.

## Q2. mTLS on HTTP and WS (Story 2, SC-002)

`client-auth = need`, truststore = `test-ca`. **Expect**:

```bash
curl --cacert $D/test-ca.crt --cert $D/monitor.crt --key $D/monitor.key https://localhost:8546 \
  -d '{"jsonrpc":"2.0","id":1,"method":"eth_blockNumber","params":[]}'        # answers
curl --cacert $D/test-ca.crt https://localhost:8546 -d '…'                    # handshake failure
curl --cacert $D/test-ca.crt --cert $D/stranger.crt --key $D/stranger.key …   # handshake failure
```

The same with `websocat --client-pkcs12-der …` against `wss://localhost:8552`, and `eth_subscribe` keeps working on the
open connection. `tls_handshake_failures_total{listener="rpc-ws",reason="no_client_cert"}` rises.

## Q3. Identity rules (Story 3)

Listener `apis = "eth,net,web3"`; rules `monitor` (`eth,net,web3`) and `ops` (`eth,net,web3,admin,graphql`).
**Expect**: `monitor` → `admin_peers` = `-32601`, `/graphql` = the "graphql disabled" answer; `ops` → both served;
`rpc_modules` returns each caller's own set; with `client-auth = want`, a call without a certificate gets
`eth,net,web3`.

## Q4. Rotation (Story 4, SC-003)

Start with `node` certificate A. Run a loop of `eth_blockNumber` on one keep-alive connection. Replace the keystore
with certificate B (`gen-cert.sh server … -n node-b`, then `mv` its password file and keystore over the profile's, in that order). **Expect** within 60 s a new
`openssl s_client -connect localhost:8546 -showcerts` shows B; the loop saw no error; `tls_reloads_total` +1. Replace
it with a truncated file: **expect** an ERROR line, B still served, `tls_reload_failures_total` +1.

## Q5. Engine API and metrics (Story 6)

Engine API with `tls { enabled = true, profile = rpc }`: a consensus client (or `curl` with a JWT from
`<datadir>/jwt.hex`) calls `engine_exchangeCapabilities` over https and is served; without the JWT it gets 401.
Adding `client-auth = need` under `engine-api.tls` refuses the start. Metrics with `client-auth = need`: `curl` with
the `monitor` certificate gets `/metrics`; without it the handshake fails.

## Q6. Non-loopback posture (Story 8, SC-008)

For each of `network.rpc.http.interface`, `network.rpc.ws.interface`, `network.engine-api.interface`,
`metrics.interface` set to `0.0.0.0` with TLS off: **expect** the refusal naming both keys. Add
`allow-insecure-bind = true`: starts, WARN line. Metrics enabled with no `interface`: `ss -ltn` shows `127.0.0.1:13798`
(not `*:13798`).

## Q7. Outbound (Story 5, SC-004)

`scripts/check-outbound-tls.sh` exits 0. Point `sync.checkpoint` at a local `openssl s_server` whose certificate SAN is
`wrong.example`: the download fails with a hostname error naming the URL. Make the server answer `302 Location:
http://…`: refused.

## Q8. Consortium allowlist (Story 7, SC-005, SC-009)

Three nodes A, B, C on a Gorgoroth chain with static nodes. A: `node-allowlist { enabled = true, file =
"$D/allowlist.json" }`, file = `[ "<enode of B>" ]`, `discovery-enabled = true` left in the conf. **Expect** on A: the
"discovery is off" WARN, no UDP socket on 30303 (`ss -lun`), B connects and syncs, C is dropped after the auth
handshake (`p2p_allowlist_rejections_total{direction="inbound"}` rises). Over IPC on A:

```bash
echo '{"jsonrpc":"2.0","id":1,"method":"admin_addNodesToAllowlist","params":[["<enode of C>"]]}' | socat - UNIX:$DATADIR/fukuii.ipc
jq . $D/allowlist.json                                   # lists B and C
echo '{"jsonrpc":"2.0","id":2,"method":"admin_removeNodesFromAllowlist","params":[["<enode of C>"]]}' | socat - …
```

C connects after the add, is disconnected after the remove, and the file follows each step. Edit the file by hand to
add C again: within one interval C is admitted. Over HTTP without `admin` granted, the same call gets `-32601`.
