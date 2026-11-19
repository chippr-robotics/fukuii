# TLS Operations Runbook

**Audience**: Operators securing a Fukuii node's listeners (JSON-RPC HTTP and WebSocket, Engine API, metrics) and its
outbound HTTPS clients  
**Estimated Time**: 30-60 minutes for initial setup  
**Prerequisites**: Running Fukuii node, basic understanding of TLS/SSL certificates

## Overview

This runbook covers Transport Layer Security (TLS) on a Fukuii node (spec 030, zero-trust TLS):

- **Server TLS on every listener**: JSON-RPC HTTP (which also serves GraphQL, `/health`, `/readiness` and
  `/healthcheck`), JSON-RPC WebSocket (WSS), the Engine API and the Prometheus metrics endpoint. Each listener names a
  shared **profile** under `fukuii.tls.profiles`.
- **Mutual TLS (mTLS)** on JSON-RPC HTTP, WebSocket and metrics: `client-auth = want | need` verifies client
  certificates against the profile's truststore. The Engine API takes server TLS only; JWT stays its client check.
- **Client-certificate identities**: a verified certificate can be granted its own set of JSON-RPC namespaces.
- **Reload without a restart**: keystores, truststores and the identity-rules file are re-read when they change.
- **The non-loopback rule**: a listener on an interface that is not loopback refuses to start in plaintext unless you
  opt out for it ([Migration](#migration)).
- **Outbound TLS**: every outbound HTTPS client of the node takes its TLS from a profile, or `system`.

P2P (RLPx) is not TLS: the RLPx handshake already authenticates each node's key. A consortium restricts who may peer
with the node ID allowlist instead; see [Peering](peering.md#consortium-node-allowlist).

## Table of Contents

1. [When to Use TLS](#when-to-use-tls)
2. [TLS Architecture](#tls-architecture)
3. [Certificate Generation](#certificate-generation)
4. [Configuration](#configuration)
5. [Mutual TLS and Identity Rules](#mutual-tls-and-identity-rules)
6. [Outbound TLS](#outbound-tls)
7. [Testing TLS Setup](#testing-tls-setup)
8. [Certificate Management](#certificate-management) (reload, rotation, revocation)
9. [Production Considerations](#production-considerations)
10. [Metrics and Log Lines](#metrics-and-log-lines)
11. [Troubleshooting](#troubleshooting)
12. [Security Best Practices](#security-best-practices)
13. [Migration: plaintext listeners on a non-loopback interface](#migration)

## When to Use TLS

### Use TLS When:

- ✅ **Exposing RPC to external services**: Any network communication beyond localhost
- ✅ **Connecting from mobile/web applications**: Client apps need encrypted connections
- ✅ **Compliance requirements**: Industry regulations (PCI-DSS, HIPAA, etc.)
- ✅ **Multi-server deployments**: Communication between servers over network
- ✅ **Public API services**: Any publicly accessible RPC endpoint

### May Not Need TLS When:

- ⚠️ **Localhost-only access**: Single-server setup with all services on localhost
- ⚠️ **Behind reverse proxy**: If reverse proxy (nginx/Caddy) handles TLS termination
- ⚠️ **Testing/development**: Non-production environments (still recommended for production-like testing)

**Important**: Even when not using TLS directly on Fukuii, ensure your reverse proxy or load balancer implements TLS for external connections.

A listener bound to anything but loopback (`0.0.0.0`, `::`, a LAN address) refuses to start without TLS unless its
`allow-insecure-bind = true` is set: a proxy on another host or container needs one or the other. See
[Migration](#migration).

## TLS Architecture

### Components

All TLS, inbound and outbound, is built in `src/main/scala/com/chipprbots/ethereum/tls/` (spec 030):

1. **TlsConfig / TlsProfile** — read `fukuii.tls.profiles` and each listener's `tls { enabled, profile, client-auth }`
   (and the deprecated `mode = "https"` + `certificate` block, mapped to a profile until 0.13.0).
2. **TlsMaterialLoader** — loads and validates a profile's keystore and truststore; a bad profile refuses the start.
3. **TlsContexts / TlsServerEngine** — the listeners' server contexts (JSON-RPC HTTP and WebSocket, Engine API,
   metrics), with key and trust managers that reload when the files change.
4. **OutboundTls** — the one factory for outbound HTTPS clients (faucet, checkpoint download, work notifier,
   external-IP lookup, bootstrap download): host names always checked, no redirect from https to http.
   `scripts/check-outbound-tls.sh` fails CI on TLS made anywhere else.

### How It Works

```
Client Request (HTTPS)
    │
    ├──> TLS Handshake (TlsServerEngine, on the listener's profile)
    │       │
    │       ├──> Server certificate (profile keystore, reloaded on change)
    │       ├──> Client certificate, under want/need (profile truststore)
    │       └──> Handshake failures counted by reason
    │
    └──> Process JSON-RPC Request (namespaces granted by client-identities)
```

## Certificate Generation

Fukuii does not ship a certificate or keystore. Every deployment generates its own key pair
and keystore password, and none of them belongs in version control.

`tls/gen-cert.sh` is in the source repository, not in the distribution archive: run it from a checkout, or copy it.

### Quick Start: Generate a Server Certificate

Without a command, `tls/gen-cert.sh` generates a self-signed server certificate for one deployment.
List every host name and address that clients use to reach the node with `--san`:

```bash
# Defaults: DNS:localhost,IP:127.0.0.1, 365 days, files written next to the script (tls/)
tls/gen-cert.sh

# A node that clients reach as rpc.example.org and 10.0.0.5
tls/gen-cert.sh --san DNS:rpc.example.org,IP:10.0.0.5

# Somewhere else, with its own file names
tls/gen-cert.sh --out-dir /opt/fukuii/tls --name node1 --san DNS:node1.internal
```

The script:
1. Generates a 4096-bit RSA key and a self-signed **server** certificate: `basicConstraints`
   `CA:FALSE`, `keyUsage` `digitalSignature, keyEncipherment`, `extendedKeyUsage` `serverAuth`,
   and the `subjectAltName` entries from `--san`
2. Generates a random keystore password for this run
3. Writes, in the output directory (`tls/` by default):
   - `fukuii-rpc.p12` — the PKCS#12 keystore with the key and certificate, mode 600
   - `fukuii-rpc.password` — the keystore password, mode 600
   - `fukuii-rpc.crt` — the certificate in PEM, for clients to trust, mode 644
4. Refuses to overwrite existing files unless you pass `--force`
5. Prints the certificate's subject, expiry and SHA-256 fingerprint, and the
   `fukuii.tls.profiles.rpc.keystore` block and `fukuii.network.rpc.http.tls` line to put in the node configuration

Options: `--san`, `--cn` (subject CN; default the first SAN value), `--days` (default 365),
`--out-dir`, `--name` (file name stem; default `fukuii-rpc`), `--force`. Run
`tls/gen-cert.sh --help` for details.

**Prerequisites**: `openssl`.

> **Upgrading:** releases before this change shipped a sample keystore, `tls/fukuiiCA.p12`, with
> its password in `tls/password`. Both are gone. If a configuration points at them, generate a
> keystore with `tls/gen-cert.sh` and point the profile's `keystore { path, password-file }` at the new files.

### A Test CA, Server and Client Certificates (mTLS)

For mutual TLS, `gen-cert.sh` also issues a small CA and the certificates it signs:

```bash
# 1. A CA (default name fukuii-ca, valid 1825 days). Writes fukuii-ca.p12/.password/.crt and
#    fukuii-ca-truststore.p12/.password: a truststore holding the CA alone. Keep fukuii-ca.p12 offline.
tls/gen-cert.sh ca --out-dir /etc/fukuii/tls

# 2. The node's server certificate, issued by that CA
tls/gen-cert.sh server --ca /etc/fukuii/tls/fukuii-ca --out-dir /etc/fukuii/tls --name node \
  --san DNS:node1.internal,IP:10.0.0.5

# 3. One client certificate per caller; --cn is required (identity rules can match it).
#    Default file stem: fukuii-client-<cn>
tls/gen-cert.sh client --ca /etc/fukuii/tls/fukuii-ca --out-dir /etc/fukuii/tls --cn monitor \
  --san DNS:monitor.internal
tls/gen-cert.sh client --ca /etc/fukuii/tls/fukuii-ca --out-dir /etc/fukuii/tls --cn ops \
  --san URI:spiffe://example.org/ops
```

`--ca` takes the CA's file stem, without the extension. A client `--san` may hold `DNS:`, `IP:` or `URI:` entries.
`ca` also needs `keytool` (shipped with the JDK) to write the truststore. Each run prints the profile block to use.

Clients present the PKCS#12 file (`curl --cert-type P12 --cert "fukuii-client-monitor.p12:$(cat fukuii-client-monitor.password)"`).
Tools that want a PEM key (the container healthcheck's `FUKUII_HEALTH_KEY`, `openssl s_client -key`) need it exported
from the keystore:

```bash
openssl pkcs12 -in fukuii-client-monitor.p12 -passin file:fukuii-client-monitor.password \
  -nocerts -nodes -out fukuii-client-monitor.key && chmod 600 fukuii-client-monitor.key
```

This CA is for tests and small deployments. In production, issue from your own PKI (for example cert-manager or Vault)
and give the node a truststore holding only the CA that issues your clients' certificates.

### Manual Certificate Generation

If you prefer to generate the keystore yourself, keep the same properties: a server
certificate (not a CA), `serverAuth`, SANs for every name clients use, and a random password.

#### Option 1: keytool

```bash
cd tls/
umask 077

# A random password, ~192 bits
openssl rand -hex 24 > fukuii-rpc.password
export PW="$(cat fukuii-rpc.password)"

keytool -genkeypair \
  -alias fukuii-rpc \
  -keystore fukuii-rpc.p12 \
  -storetype PKCS12 \
  -dname "CN=your-hostname.example.com" \
  -keypass:env PW \
  -storepass:env PW \
  -keyalg RSA \
  -keysize 4096 \
  -validity 365 \
  -ext BasicConstraints:critical=ca:false \
  -ext KeyUsage:critical=digitalSignature,keyEncipherment \
  -ext ExtendedKeyUsage=serverAuth \
  -ext "SAN=dns:your-hostname.example.com,ip:YOUR_IP_ADDRESS"
unset PW
```

**Important**: Replace `your-hostname.example.com` and `YOUR_IP_ADDRESS` with your actual values.

#### Option 2: OpenSSL

```bash
cd tls/
umask 077

openssl rand -hex 24 > fukuii-rpc.password
cp fukuii-rpc.password key.password

# Key and self-signed server certificate (OpenSSL 1.1.1 or later)
openssl req -x509 -newkey rsa:4096 -sha256 -days 365 \
  -subj "/CN=your-hostname.example.com" \
  -addext "basicConstraints=critical,CA:FALSE" \
  -addext "keyUsage=critical,digitalSignature,keyEncipherment" \
  -addext "extendedKeyUsage=serverAuth" \
  -addext "subjectAltName=DNS:your-hostname.example.com,IP:YOUR_IP_ADDRESS" \
  -keyout server.key -passout file:key.password \
  -out fukuii-rpc.crt

# Package as PKCS#12, protected by the keystore password
openssl pkcs12 -export -name fukuii-rpc \
  -inkey server.key -passin file:key.password \
  -in fukuii-rpc.crt \
  -out fukuii-rpc.p12 -passout file:fukuii-rpc.password

rm -f server.key key.password
```

Two password files, because OpenSSL reads the second line of a file when `-passin` and
`-passout` name the same one.

### Using CA-Signed Certificates

For production environments, use certificates from a trusted Certificate Authority:

#### Step 1: Generate CSR

```bash
keytool -certreq -alias fukuii-rpc \
  -keystore fukuii-rpc.p12 \
  -storepass "$(cat fukuii-rpc.password)" \
  -ext "SAN=dns:your-hostname.example.com" \
  -file fukuii.csr
```

#### Step 2: Submit CSR to CA

Submit `fukuii.csr` to your Certificate Authority (Let's Encrypt, DigiCert, etc.)

#### Step 3: Import Signed Certificate

```bash
# Import CA root certificate
keytool -import -trustcacerts -alias root \
  -file ca-root.crt \
  -keystore fukuii-rpc.p12 \
  -storepass "$(cat fukuii-rpc.password)"

# Import signed certificate
keytool -import -alias fukuii-rpc \
  -file signed-certificate.crt \
  -keystore fukuii-rpc.p12 \
  -storepass "$(cat fukuii-rpc.password)"
```

### Certificate Verification

Verify your certificate is correctly generated:

```bash
cd tls/

# Certificate details: subject, validity, extensions
openssl x509 -in fukuii-rpc.crt -noout -text

# Keystore contents (reads the keystore with its password)
keytool -list -v -keystore fukuii-rpc.p12 \
  -storepass "$(cat fukuii-rpc.password)"
```

**Expected output should show:**
- Alias name: `fukuii-rpc` (the `--name` given to `gen-cert.sh`)
- Entry type: PrivateKeyEntry
- Certificate chain length: 1 (self-signed) or more (CA-signed)
- Valid from/to dates
- 4096-bit RSA key
- Basic Constraints `CA:FALSE`, Extended Key Usage `serverAuth`
- Subject Alternative Names (SAN) matching your domain/IP

## Configuration

### Where the keys live

The shipped defaults are in `conf/base/*.conf` (in the source tree, `src/main/resources/conf/base/`): profiles in
`tls.conf`; the listeners in `network.conf` (`rpc.http`, `rpc.ws`, `engine-api`) and `metrics.conf`. Do not edit
those; put your settings in your own file, which starts with `include "app.conf"`, and start the node with it:

```bash
./bin/fukuii -Dconfig.file=/etc/fukuii/node.conf etc
```

Every node key below is under `fukuii.` (the faucet's are under `faucet.`). Relative paths are resolved against the
node's working directory (where you run `fukuii`); absolute paths avoid surprises. Environment variables such as
`FUKUII_NETWORK_RPC_HTTP_*` are not read; use the file or `-Dfukuii.<key>=<value>`.

### Step 1: Define a Profile

A profile is named TLS material that listeners (server role) and outbound clients (client role) refer to by name. None
is defined by default.

```hocon
fukuii.tls.profiles {
  rpc {
    keystore   { path = "/etc/fukuii/tls/node.p12", type = "pkcs12", password-file = "/etc/fukuii/tls/node.password" }
    # Only for client-auth = want or need: the CA(s) whose client certificates this listener accepts
    truststore {
      path = "/etc/fukuii/tls/fukuii-ca-truststore.p12"
      type = "pkcs12"
      password-file = "/etc/fukuii/tls/fukuii-ca-truststore.password"
    }
  }
}
```

| Key | Default | Meaning |
| --- | --- | --- |
| `keystore { path, type, password-file }` | none | The private key and certificate this side presents. Required for a listener; optional for an outbound client (presented to a server that asks) |
| `key-alias` | `null` | The keystore entry to use; required when the keystore holds more than one private key (no silent first-entry choice) |
| `truststore { path, type, password-file }` | none | The only trust: client CAs for a listener with `want`/`need`; server CAs for an outbound client. It is never mixed with the keystore or the JDK's trust |
| `protocols` | `[]` (the JDK's defaults) | For example `["TLSv1.3"]`; anything below TLSv1.2 refuses the start |
| `cipher-suites` | `[]` (the JDK's defaults) | Restrict the suites |
| `reload { enabled, interval }` | `true`, `1.minute` | Re-read the files above when their content changes ([Certificate Management](#certificate-management)); the interval is at least one second |
| `expiry-warning` | `14.days` | A WARN line once a day when a certificate is within this of `notAfter` |

The profile name `system` is reserved: the JDK's default trust and no client key, for outbound clients only.

A password file may end with a newline (as `gen-cert.sh` writes it); it is trimmed.

**Fail closed.** Every profile a listener or client names is loaded and checked before any port binds. The node
refuses to start, with a line that names the key, on: a missing or unreadable file, a wrong password, a missing or
ambiguous `key-alias`, an expired certificate, `client-auth = want` or `need` without a truststore (or with one holding
no CA certificate), and `protocols` below TLSv1.2. It never falls back to plaintext or to the JDK's trust. No message
contains a password or key material. For example:

```
Refusing to start: fukuii.tls.profiles.rpc.keystore.path: /etc/fukuii/tls/rpc.p12 not found
Refusing to start: fukuii.tls.profiles.rpc: certificate CN=node1 expired 2026-11-17T00:00:00Z (notAfter)
Refusing to start: fukuii.network.rpc.http.tls: client-auth = need, but profile rpc has no truststore
```

### Step 2: Turn TLS On per Listener

Each listener has its own `tls` block naming a profile. Several listeners may share one profile.

| Listener (log label) | Block | Port | `client-auth` | Opt-out of the bind rule |
| --- | --- | --- | --- | --- |
| JSON-RPC HTTP (`rpc-http`), with GraphQL and the health endpoints | `network.rpc.http.tls { enabled, profile, client-auth }` | 8546 | `none`, `want`, `need` | `network.rpc.http.allow-insecure-bind` |
| JSON-RPC WebSocket (`rpc-ws`) | `network.rpc.ws.tls { enabled, profile, client-auth }` | 8552 | `none`, `want`, `need` | `network.rpc.ws.allow-insecure-bind` |
| Engine API (`engine-api`) | `network.engine-api.tls { enabled, profile }` | 8551 | none: setting the key refuses the start | `network.engine-api.allow-insecure-bind` |
| Metrics (`metrics`) | `metrics.tls { enabled, profile, client-auth }`; bind address `metrics.interface` (default `localhost`) | 13798 | `none`, `want`, `need` | `metrics.allow-insecure-bind` |

All are off by default (`enabled = false`, `profile = null`, `client-auth = "none"`), and every listener binds
`localhost` by default.

```hocon
fukuii {
  network {
    rpc {
      http {
        interface = "10.0.0.5"          # not loopback: TLS (or allow-insecure-bind) is required
        tls { enabled = true, profile = "rpc", client-auth = "need" }
      }
      ws {
        enabled = true
        interface = "10.0.0.5"
        tls { enabled = true, profile = "rpc", client-auth = "need" }
      }
    }
    # Server TLS only; the consensus client still sends its JWT (<datadir>/jwt.hex) on every call
    engine-api.tls { enabled = true, profile = "rpc" }
  }
  metrics {
    enabled = true
    interface = "10.0.0.5"
    tls { enabled = true, profile = "rpc", client-auth = "need" }
  }
}
```

Notes:

- **Engine API**: TLS protects the JWT and the payloads in transit; it never replaces the JWT. Consensus clients
  differ in whether they can trust a private CA on their execution endpoint; check yours before turning this on.
- **HTTP and WebSocket on one port** must have the same TLS setting, or the node refuses to start.
- **Health endpoints** (`/health`, `/readiness`, `/healthcheck`) share the RPC HTTP port and its TLS. Under
  `client-auth = need` a probe must present a client certificate: there is no unauthenticated side port. The container
  images' healthcheck (`docker/scripts/healthcheck.sh`) reads `FUKUII_HEALTH_URL` (default `http://localhost:8546`),
  `FUKUII_HEALTH_CACERT`, `FUKUII_HEALTH_CERT` and `FUKUII_HEALTH_KEY` (PEM files), and `FUKUII_HEALTH_RPC=required`
  to make an unanswered listener unhealthy.
- **The TUI's endpoint probe** reports the listener's certificate without verifying it, presenting the listener's own
  keystore to a `need` listener. Set `fukuii.tui.probe.tls-profile` to a profile (or `system`) to verify it instead.
- **Faucet**: `fukuii faucet` serves its own listener through its `fukuii.network.rpc.http` keys, and its client of
  the node takes `faucet.rpc-client.tls-profile` (see [Outbound TLS](#outbound-tls)).

At start the node logs one INFO line per TLS listener, and one per identity rule with the namespaces it grants:

```
TLS listener rpc-http https://0.0.0.0:8546 client-auth=need profile=rpc cert="CN=node1" notAfter=2027-01-01 rules=2
TLS listener rpc-http rule monitor: eth,net,web3,rpc
```

### Deprecated: `mode = "https"` and `certificate`

Before spec 030 the HTTP listener took `network.rpc.http.mode = "https"` and a
`certificate { keystore-path, keystore-type, password-file }` block. That form still works until it is **removed in
0.13.0**: it maps to `tls { enabled = true, client-auth = "none" }` on an implicit profile (`legacy-rpc-http`) and logs,
once at start:

```
fukuii.network.rpc.http.mode = "https" and .certificate are deprecated; use fukuii.network.rpc.http.tls { enabled, profile, client-auth } and fukuii.tls.profiles.<name> (docs/runbooks/tls-operations.md). They will be removed in 0.13.0.
```

Setting `mode = "https"` together with `tls.enabled = true` refuses the start. To migrate:

```hocon
# Before
fukuii.network.rpc.http {
  mode = "https"
  certificate { keystore-path = "tls/fukuii-rpc.p12", keystore-type = "pkcs12", password-file = "tls/fukuii-rpc.password" }
}

# After
fukuii.tls.profiles.rpc.keystore { path = "tls/fukuii-rpc.p12", type = "pkcs12", password-file = "tls/fukuii-rpc.password" }
fukuii.network.rpc.http.tls { enabled = true, profile = "rpc" }
```

The faucet's `faucet.rpc-client.certificate` block maps the same way and goes in 0.13.0 too; use
`faucet.rpc-client.tls-profile` (setting both refuses the faucet's start).

## Mutual TLS and Identity Rules

### `client-auth`

| Value | Handshake | Who is served what |
| --- | --- | --- |
| `none` | No client certificate is asked for | Everyone gets the listener's `apis` |
| `want` | A certificate is asked for; a client without one still connects | A verified certificate matching a rule gets that rule's namespaces; everyone else the listener's `apis` |
| `need` | The handshake fails without a certificate that chains to the profile's truststore | As for `want`, but every caller has a verified certificate |

A presented certificate is always verified against the profile's truststore; an untrusted one fails the handshake
under `want` too. Browsers cannot present client certificates reliably: for them, use `want` and give the listener's
`apis` only what an anonymous caller may call. Refused handshakes are counted by reason in
`app_tls_handshake_failures_total{listener, reason}` and logged at WARN, at most once per remote address per minute.

### Identity Rules Grant Namespaces

On an HTTP or WS listener with `client-auth = want` or `need`, `fukuii.network.rpc.client-identities` maps verified
client certificates to JSON-RPC namespaces:

```hocon
fukuii.network.rpc {
  http.apis = "eth,net,web3"            # anonymous and unmatched callers
  client-identities = [
    { name = "monitor", match { san-dns = "monitor.internal" }, apis = "eth,net,web3" }
    { name = "ops", match { sha256 = "AB:CD:..." }, apis = "eth,net,web3,admin,debug,graphql" }
  ]
}
```

- `match` keys: `san-dns`, `san-uri`, `subject-cn`, `sha256` (the certificate's SHA-256 fingerprint, hex, colons
  allowed: `openssl x509 -in fukuii-client-ops.crt -noout -fingerprint -sha256`). Every key given must match, exactly.
- `name`: 1-32 characters of `a-z 0-9 _ -`; it labels metrics and log lines.
- `apis`: any namespace of `network.rpc.apis`, plus `graphql` for `POST /graphql`. `rpc` (`rpc_modules`) is always
  added. A rule's set **replaces** the listener's for that certificate: it can grant more (`admin` to one operator
  certificate) or less than an anonymous caller gets.
- The first matching rule wins. A caller with no certificate, or one no rule matches, gets the listener's `apis`.
- `graphql`: a matched caller whose rule does not list it is answered as if GraphQL were off. Anonymous and unmatched
  callers get GraphQL exactly when `network.rpc.graphql.enabled` is true.
- A rule only ever sees a certificate that chained to the listener's truststore: a self-signed certificate copying a
  rule's SAN or CN never matches. The grant is as strong as that truststore, so keep it to the CA that issues your
  clients' certificates.
- A refused call answers `-32601`, counts in `app_rpc_namespace_denials_total{identity_rule, namespace}`
  (`identity_rule` is the rule name, `anonymous` or `unmatched`) and logs `RPC denied <method> to <rule> on <listener>`
  at INFO, rate-limited. `rpc_modules` returns the caller's own set.
- A non-empty rule list with no `want` or `need` listener enabled refuses the start (it could never match).

To change rules without a restart, keep them in a file instead:

```hocon
fukuii.network.rpc.client-identities-file = "/etc/fukuii/client-identities.conf"   # holds client-identities = [ ... ]
```

The file is re-read when its content changes, on the reload interval of the listeners' profiles; a version that
validates serves new requests and new WebSocket connections, one that does not is logged at ERROR and the previous
rules stay. Setting both the file and a non-empty inline `client-identities` refuses the start. Inline rules change
only with a restart.

## Outbound TLS

The node's outbound HTTPS clients take their TLS from one factory. Each site names a profile, or `system` (the JDK's
default trust). A named profile's truststore is then the only trust, and its keystore, if any, is presented to a
server that asks for a client certificate. Host names are always checked, and a redirect from https to http is never
followed.

| Key | Default | Site |
| --- | --- | --- |
| `faucet.rpc-client.tls-profile` | `system` (shipped as `null`, which means `system`) | The faucet's client of the node |
| `fukuii.sync.checkpoint-sync-tls-profile` | `system` | Checkpoint archive download (`checkpoint-sync-url`) |
| `fukuii.mining.notify-tls-profile` | `system` | PoW work notifications to an https `notify-urls` entry |
| `fukuii.network.server-address.external-ip-tls-profile` | `system` | The HTTPS step of external-IP detection |
| `fukuii.bootstrap-download.tls-profile` | `system` (not in the shipped conf) | `download-bootstrap` (https URLs) |
| `fukuii.tui.probe.tls-profile` | `null` (unverified probe) | The TUI's endpoint test of the node's own RPC listener |

The Ephemery release fetch runs before the node's configuration is read and uses `system`; it has no key yet (B-397).
Outbound profiles are read when the node builds the client, at start: the reload loop covers listeners and the
identity-rules file, so a changed outbound profile needs a restart.

Example: the faucet calling a node whose RPC listener is `need`, with the faucet's own client certificate:

```hocon
fukuii.tls.profiles.faucet-client {
  keystore   { path = "/etc/fukuii/tls/fukuii-client-faucet.p12", type = "pkcs12", password-file = "/etc/fukuii/tls/fukuii-client-faucet.password" }
  truststore { path = "/etc/fukuii/tls/fukuii-ca-truststore.p12", type = "pkcs12", password-file = "/etc/fukuii/tls/fukuii-ca-truststore.password" }
}
faucet.rpc-client {
  rpc-address = "https://node1.internal:8546/"
  tls-profile = "faucet-client"
}
```

## Testing TLS Setup

### Step 1: Start Fukuii with TLS

```bash
# Start the node
./bin/fukuii -Dconfig.file=/etc/fukuii/node.conf etc

# Watch the TLS start-up lines
tail -f ~/.fukuii/etc/logs/fukuii.log | grep -E "TLS listener|Plaintext listener|server listening|Refusing to start"
```

**Expected log output** (INFO):
```
TLS listener rpc-http https://10.0.0.5:8546 client-auth=need profile=rpc cert="CN=node1.internal" notAfter=2027-11-19 rules=2
JSON RPC HTTPS server listening on /10.0.0.5:8546
JSON RPC WSS server listening on /10.0.0.5:8552
```

**Error indicators**: a misconfigured profile or listener never runs half-configured. The node exits non-zero
before any port binds, with a line starting `Refusing to start:` that names the key
([Step 1](#step-1-define-a-profile) has examples). `ss -ltn` shows no RPC port.

### Step 2: Test HTTPS Connection

#### Using curl

```bash
# Self-signed certificate: trust the certificate gen-cert.sh wrote
curl --cacert tls/fukuii-rpc.crt https://localhost:8546 \
  -X POST \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'

# With CA-signed certificate (verify)
curl https://your-domain.com:8546 \
  -X POST \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'
```

**Expected response:**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": "0x12345"
}
```

#### Mutual TLS with curl

Against a listener with `client-auth = need` and the test CA of [Certificate Generation](#a-test-ca-server-and-client-certificates-mtls):

```bash
cd /etc/fukuii/tls
BODY='{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'

# Answers: a certificate issued by the CA in the profile's truststore
curl --cacert fukuii-ca.crt --cert-type P12 \
  --cert "fukuii-client-monitor.p12:$(cat fukuii-client-monitor.password)" \
  -H "Content-Type: application/json" -d "$BODY" https://node1.internal:8546

# Handshake failure: no client certificate (counted as reason="no_client_cert")
curl --cacert fukuii-ca.crt -H "Content-Type: application/json" -d "$BODY" https://node1.internal:8546

# The namespaces this certificate is served
curl --cacert fukuii-ca.crt --cert-type P12 \
  --cert "fukuii-client-monitor.p12:$(cat fukuii-client-monitor.password)" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"rpc_modules","params":[],"id":1}' https://node1.internal:8546
```

A certificate from another CA fails the handshake too (`reason="untrusted_client_cert"`). A method outside the
caller's set answers `-32601`.

#### Using openssl s_client

Test TLS handshake, trusting the certificate `gen-cert.sh` wrote:
```bash
openssl s_client -connect localhost:8546 -CAfile tls/fukuii-rpc.crt -verify_hostname localhost
```

**Expected output:**
```
CONNECTED(00000003)
depth=0 CN = localhost
verify return:1
...
    Verify return code: 0 (ok)
...
SSL-Session:
    Protocol  : TLSv1.3
    Cipher    : TLS_AES_256_GCM_SHA384
...
```

#### Using Python

```python
import requests
import json

# Disable SSL verification for self-signed certs (testing only!)
import urllib3
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

url = "https://localhost:8546"
headers = {"Content-Type": "application/json"}
payload = {
    "jsonrpc": "2.0",
    "method": "eth_blockNumber",
    "params": [],
    "id": 1
}

response = requests.post(url, 
                        json=payload, 
                        headers=headers,
                        verify=False)  # Use verify=True with CA certs

print(json.dumps(response.json(), indent=2))
```

#### Using JavaScript (Node.js)

```javascript
const https = require('https');

const options = {
  hostname: 'localhost',
  port: 8546,
  path: '/',
  method: 'POST',
  headers: {
    'Content-Type': 'application/json'
  },
  // For self-signed certificates (testing only!)
  rejectUnauthorized: false
};

const data = JSON.stringify({
  jsonrpc: '2.0',
  method: 'eth_blockNumber',
  params: [],
  id: 1
});

const req = https.request(options, (res) => {
  let body = '';
  res.on('data', (chunk) => body += chunk);
  res.on('end', () => console.log(JSON.parse(body)));
});

req.write(data);
req.end();
```

### Step 3: Verify TLS Version and Ciphers

Check which TLS versions and ciphers are negotiated:

```bash
# Check TLS 1.3
openssl s_client -connect localhost:8546 -tls1_3

# Check TLS 1.2
openssl s_client -connect localhost:8546 -tls1_2

# List supported ciphers
nmap --script ssl-enum-ciphers -p 8546 localhost
```

### Health Check Endpoints

Test health endpoints over HTTPS:

```bash
# Health check
curl --cacert tls/fukuii-rpc.crt https://localhost:8546/health

# Readiness check
curl --cacert tls/fukuii-rpc.crt https://localhost:8546/readiness

# Full healthcheck
curl --cacert tls/fukuii-rpc.crt https://localhost:8546/healthcheck
```

Under `client-auth = need` these need a client certificate too (add `--cert-type P12 --cert ...` as above).

## Certificate Management

### Reload Without a Restart

With `reload.enabled = true` (the default) a listener's profile re-reads its keystore, truststore and their password
files every `reload.interval` (default one minute). A change is detected by the SHA-256 of the files' content, so an
edit within the same second and a Kubernetes secret update (the `..data` symlink swap) are both seen.

- A replacement that validates serves **new handshakes**; open connections keep the material they started with. The
  node logs `TLS profile <name> reloaded (<files> changed); new handshakes use cert <subject> notAfter=<date>` and
  `app_tls_reloads_total{profile}` rises.
- One that does not validate (truncated file, wrong password, expired certificate) keeps the previous material in
  service, logs `TLS profile <name> reload failed: <file>: <cause>; keeping the previous material` at ERROR, and
  `app_tls_reload_failures_total{profile}` rises. The node never stops serving because of it.
- The identity-rules file (`client-identities-file`) is polled the same way.
- There is no signal-triggered reload (SIGHUP does nothing special); the poll interval is the bound. With
  `reload.enabled = false`, a change takes a restart.

### Certificate Rotation

1. **Issue the new certificate into a staging directory**, with the same `--san` as before (and `--ca` for a
   CA-issued one):

   ```bash
   mkdir -p /etc/fukuii/tls/next
   tls/gen-cert.sh server --ca /etc/fukuii/tls/fukuii-ca --out-dir /etc/fukuii/tls/next --name node \
     --san DNS:node1.internal,IP:10.0.0.5
   ```

2. **Move the keystore and its password file into place**, back to back. `mv` within one filesystem is an atomic
   rename, so the node never reads a half-written file. If a poll lands between the two moves, that pass logs a reload
   failure and keeps the old certificate; the next pass loads the pair.

   ```bash
   mv /etc/fukuii/tls/next/node.p12 /etc/fukuii/tls/node.p12
   mv /etc/fukuii/tls/next/node.password /etc/fukuii/tls/node.password
   ```

   On Kubernetes, update the mounted secret instead; the kubelet swaps every file at once.

3. **Verify within one interval**, without restarting:

   ```bash
   openssl s_client -connect node1.internal:8546 -CAfile /etc/fukuii/tls/fukuii-ca.crt </dev/null 2>/dev/null \
     | openssl x509 -noout -subject -enddate
   grep "TLS profile rpc reloaded" ~/.fukuii/etc/logs/fukuii.log | tail -1
   ```

Clients that pinned the old self-signed certificate need the new `.crt`; clients that trust the CA need nothing.
Rotating a truststore (adding or removing a client CA) works the same way. Switching the key type (RSA to EC) is a
valid rotation. Changing a profile's paths or any other key in the configuration still takes a restart.

### Revoking Client Access

The node does not check CRLs or OCSP. Revoke access with:

1. **Short-lived client certificates** (days, not years), so a certificate you stop renewing ages out on its own.
2. **A truststore or identity-rule change, reloaded**: remove the issuing CA from the truststore, or remove or narrow
   the rule (in `client-identities-file`) that grants the namespaces. New handshakes and new requests see the change
   within one reload interval.
3. **Bounded connection lifetimes**: an open connection keeps the identity it had when it was established until it
   closes, and an open WebSocket keeps its namespaces across a rule reload. Clients holding long-lived WebSocket
   sessions (subscriptions) should use a bounded idle timeout and reconnect periodically, so a revocation reaches them.

### Certificate Expiration Monitoring

The node exports `app_tls_certificate_expiry_seconds{profile, role}` (seconds until `notAfter`) for every TLS
listener's profile, and logs `TLS profile <name> <role> certificate expires in <n> days (<date>)` at WARN once a day
within the profile's `expiry-warning` (default 14 days). A certificate that expires while the node runs is still
served (a running node does not stop itself), but an expired one refuses the next start. Alert on the gauge, for
example `app_tls_certificate_expiry_seconds < 7 * 86400`.

Without metrics, check the keystore directly:

```bash
#!/bin/bash
# check-cert-expiry.sh

KEYSTORE="tls/fukuii-rpc.p12"
PASSWORD_FILE="tls/fukuii-rpc.password"
WARN_DAYS=30

# The server certificate's expiry, read from the keystore
EXPIRY=$(openssl pkcs12 -in "$KEYSTORE" -passin "file:$PASSWORD_FILE" -nokeys -clcerts \
  | openssl x509 -enddate -noout | cut -d= -f2)
EXPIRY_EPOCH=$(date -d "$EXPIRY" +%s)
NOW_EPOCH=$(date +%s)
DAYS_LEFT=$(( ($EXPIRY_EPOCH - $NOW_EPOCH) / 86400 ))

echo "Certificate expires in $DAYS_LEFT days"

if [ $DAYS_LEFT -lt $WARN_DAYS ]; then
  echo "WARNING: Certificate expires in less than $WARN_DAYS days!"
  exit 1
fi
```

Add to cron:
```bash
# Run daily at 9 AM
0 9 * * * /path/to/check-cert-expiry.sh
```

### Backup and Recovery

#### Backup Certificate

```bash
# Create backup directory
mkdir -p backups/tls/$(date +%Y%m%d)

# Backup certificate and password
cp tls/fukuii-rpc.p12 backups/tls/$(date +%Y%m%d)/
cp tls/fukuii-rpc.password backups/tls/$(date +%Y%m%d)/

# Create encrypted archive
tar czf backups/tls-$(date +%Y%m%d).tar.gz \
  backups/tls/$(date +%Y%m%d)/
```

#### Restore Certificate

```bash
# Extract backup
tar xzf backups/tls-20251106.tar.gz

# Copy to TLS directory
cp -p backups/tls/20251106/fukuii-rpc.p12 tls/
cp -p backups/tls/20251106/fukuii-rpc.password tls/

# Restart node
kill -TERM $(pgrep -f fukuii)
./bin/fukuii etc
```

## Production Considerations

### Security Hardening

1. **Use CA-Signed Certificates**: Avoid self-signed certificates in production
2. **Strong Passwords**: Use long, random passwords for keystores
3. **File Permissions**: Restrict access to certificate files
   ```bash
   chmod 600 tls/fukuii-rpc.p12 tls/fukuii-rpc.password
   chown fukuii:fukuii tls/fukuii-rpc.p12 tls/fukuii-rpc.password
   ```
4. **Certificate Pinning**: Implement certificate pinning in clients
5. **HSTS Headers**: Use HTTP Strict Transport Security

### Reverse Proxy Configuration

A proxy on the same host reaches the node on loopback, so the node needs neither TLS nor `allow-insecure-bind` there.
A proxy on another host or container reaches a non-loopback interface: give the node TLS (and the proxy a client
certificate under `need`, as in the load-balancing example below), or set `allow-insecure-bind = true` on a network
you control.

#### Nginx Example

```nginx
# In the http {} context (nginx.conf), not inside server {}
limit_req_zone $binary_remote_addr zone=rpc_limit:10m rate=10r/s;

upstream fukuii_rpc {
    server 127.0.0.1:8546;
}

server {
    listen 443 ssl http2;
    server_name api.example.com;

    # SSL Configuration
    ssl_certificate /etc/nginx/ssl/cert.pem;
    ssl_certificate_key /etc/nginx/ssl/key.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;
    ssl_prefer_server_ciphers on;

    # Security headers
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Content-Type-Options nosniff;
    add_header X-Frame-Options DENY;

    # Rate limiting (the zone is declared in the http {} context above)
    limit_req zone=rpc_limit burst=20 nodelay;

    location / {
        proxy_pass http://fukuii_rpc;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        
        # Timeouts
        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
    }

    location /health {
        proxy_pass http://fukuii_rpc/health;
        access_log off;
    }
}

# Redirect HTTP to HTTPS
server {
    listen 80;
    server_name api.example.com;
    return 301 https://$server_name$request_uri;
}
```

#### Caddy Example (Automatic HTTPS)

```caddyfile
api.example.com {
    reverse_proxy localhost:8546
    
    # Automatic HTTPS with Let's Encrypt
    tls {
        protocols tls1.2 tls1.3
    }
    
    # Rate limiting needs a plugin (for example caddy-ratelimit); stock Caddy has no rate_limit directive
    
    # Headers
    header {
        Strict-Transport-Security "max-age=31536000;"
        X-Content-Type-Options "nosniff"
        X-Frame-Options "DENY"
    }
}
```

### Load Balancing with TLS

For high-availability setups the nodes are on other hosts, so each node serves TLS on its non-loopback interface
(`client-auth = need`) and the proxy presents a client certificate issued by the CA in the nodes' truststore. The
proxy's identity rule (or the listener's `apis`) decides what it may call.

```nginx
# http {} context
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      '';          # plain HTTP requests: empty Connection header, so upstream keep-alive works
}

upstream fukuii_cluster {
    least_conn;
    server fukuii-1.internal:8546 max_fails=3 fail_timeout=30s;
    server fukuii-2.internal:8546 max_fails=3 fail_timeout=30s;
    server fukuii-3.internal:8546 max_fails=3 fail_timeout=30s;
    
    keepalive 32;
}

server {
    listen 443 ssl http2;
    server_name api.example.com;
    
    ssl_certificate /etc/nginx/ssl/cert.pem;
    ssl_certificate_key /etc/nginx/ssl/key.pem;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 10m;
    
    location / {
        proxy_pass https://fukuii_cluster;
        proxy_http_version 1.1;
        # Keeps WebSocket upgrades working while plain requests reuse upstream connections
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;

        # mTLS to the nodes: verify them against the internal CA, present the proxy's client certificate
        proxy_ssl_trusted_certificate /etc/nginx/fukuii/fukuii-ca.crt;
        proxy_ssl_verify on;
        proxy_ssl_name fukuii-1.internal;      # or a name every node certificate carries in its SAN
        proxy_ssl_certificate /etc/nginx/fukuii/fukuii-client-proxy.crt;
        proxy_ssl_certificate_key /etc/nginx/fukuii/fukuii-client-proxy.key;
    }
}
```

Active health checks (`health_check`) are an NGINX Plus feature; open-source nginx marks upstreams down passively
through `max_fails`. The WebSocket listener has its own port (8552 by default) unless you configure HTTP and WS on
one port, so proxy it from its own `location` or `server` when it is separate.

### Monitoring and Logging

Monitor TLS connections:

```bash
# Monitor SSL connections
watch -n 1 'ss -t -a | grep :8546'

# TLS events in the log (refusals, reloads, expiry warnings, refused handshakes)
tail -f ~/.fukuii/etc/logs/fukuii.log | grep -E "TLS |Refusing to start|RPC denied"

# Monitor certificate expiration
openssl s_client -connect localhost:8546 -servername localhost </dev/null 2>/dev/null \
  | openssl x509 -noout -enddate
```

See [Metrics and Log Lines](#metrics-and-log-lines) for what to alert on.

### Performance Tuning

TLS adds computational overhead. Optimize for production:

1. **Enable SSL Session Caching**: Reduce handshake overhead
2. **Use TLS 1.3**: Faster handshakes, better security
3. **Hardware Acceleration**: Use CPU with AES-NI support
4. **Connection Pooling**: Reuse connections in clients

## Metrics and Log Lines

With `fukuii.metrics.enabled = true`, the Prometheus endpoint exports (names as scraped, with the node's `app_`
prefix):

| Metric | Labels | Meaning |
| --- | --- | --- |
| `app_tls_handshake_failures_total` | `listener` (`rpc-http`, `rpc-ws`, `engine-api`, `metrics`), `reason` (`no_client_cert`, `untrusted_client_cert`, `expired_client_cert`, `protocol`, `other`) | Refused TLS handshakes |
| `app_rpc_namespace_denials_total` | `identity_rule` (a rule name, `anonymous` or `unmatched`), `namespace` | Calls refused with `-32601` because the caller's set lacks the namespace |
| `app_tls_reloads_total`, `app_tls_reload_failures_total` | `profile` | Profile and identity-rule reloads, and the ones that kept the previous material |
| `app_tls_certificate_expiry_seconds` | `profile`, `role` (`server`, `client`) | Seconds until the certificate's `notAfter` |

The P2P node allowlist has its own (`app_p2p_allowlist_*`); see [Peering](peering.md#consortium-node-allowlist).

Log lines (none ever holds a password or key material):

| Event | Level | Text |
| --- | --- | --- |
| Start, per TLS listener | INFO | `TLS listener <label> https://<interface>:<port> client-auth=<mode> profile=<name> cert="<subject>" notAfter=<date> rules=<n>`, then one `TLS listener <label> rule <name>: <namespaces>` per rule |
| Start, per plaintext opt-out | WARN | `Plaintext listener <label> http://<interface>:<port> allow-insecure-bind=true (<key>.tls.enabled = false: ...)` |
| Misconfiguration | (exit) | `Refusing to start: <key>: <cause>` |
| Refused handshake | WARN | `TLS handshake refused on <listener> from <addr>: <reason>`, once per address per minute, then `(<n> suppressed)` |
| Namespace denial | INFO | `RPC denied <method> to <rule, anonymous or unmatched> on <listener>`, rate-limited |
| Reload | INFO / ERROR | `TLS profile <name> reloaded (...)` / `TLS profile <name> reload failed: ...; keeping the previous material` |
| Expiry | WARN | `TLS profile <name> <role> certificate expires in <n> days (<date>)`, daily within `expiry-warning` |

Suggested alerts: `app_tls_certificate_expiry_seconds` below a week; any increase of `app_tls_reload_failures_total`;
a sustained rate of `app_tls_handshake_failures_total{reason="untrusted_client_cert"}` (someone probing with a foreign
certificate).

## Troubleshooting

### Common Issues

#### Issue: "Refusing to start: fukuii.tls.profiles.<name>.keystore.path: ... not found"

**Cause**: The keystore (or truststore, or a password file) is not at the configured path

**Solution**:
```bash
# Check if file exists and the node's user can read it
ls -l /etc/fukuii/tls/node.p12

# The configured path: relative paths are resolved against the node's working directory
grep -n "path" /etc/fukuii/node.conf
pwd
```

#### Issue: The keystore does not open (wrong password or corrupted file)

**Cause**: Incorrect password or corrupted keystore

**Solution**:
```bash
# Check that the password file opens the keystore
openssl pkcs12 -in tls/fukuii-rpc.p12 -passin file:tls/fukuii-rpc.password -noout \
  && echo "password OK"

# Try to list keystore contents
keytool -list -v -keystore tls/fukuii-rpc.p12 \
  -storepass "$(cat tls/fukuii-rpc.password)"

# If corrupted, regenerate the keystore (same --san as before)
tls/gen-cert.sh --force --san DNS:localhost,IP:127.0.0.1
```

#### Issue: The keystore type does not match

**Cause**: The profile's `type` does not match the file (a JKS file declared `pkcs12`, or the reverse)

**Solution**:
```bash
# Verify keystore type
keytool -list -keystore tls/fukuii-rpc.p12 -storepass "$(cat tls/fukuii-rpc.password)"

# Should show: Keystore type: PKCS12
# Update the profile to match:
# keystore { ..., type = "pkcs12" }
```

#### Issue: "client-auth = need, but profile <name> has no truststore"

**Cause**: `want` and `need` verify client certificates against the profile's truststore, which is missing

**Solution**: add `truststore { path, type, password-file }` to the profile, holding the CA that issues your clients'
certificates (`tls/gen-cert.sh ca` writes one as `<name>-truststore.p12`), or set `client-auth = "none"`.

#### Issue: "Refusing to start: fukuii.network.<listener> binds <address> (not loopback) without TLS"

**Cause**: The listener's interface is not loopback and TLS is off. See [Migration](#migration).

#### Issue: Clients fail the handshake under `want` or `need`

**Cause**: The client sent no certificate (`need`), or one that does not chain to the profile's truststore, or one
that expired. `app_tls_handshake_failures_total{listener, reason}` and the WARN line
`TLS handshake refused on <listener> from <addr>: <reason>` say which.

**Solution**: issue the client a certificate from the CA in the truststore (`gen-cert.sh client --ca ...`) and check
it with `openssl verify -CAfile fukuii-ca.crt fukuii-client-<cn>.crt`.

#### Issue: A call answers `-32601 Method not found` over mTLS

**Cause**: The namespace is not in the caller's set: its identity rule's `apis`, or the listener's `apis` for a
caller no rule matches. `rpc_modules` shows the set; the INFO line `RPC denied <method> to <rule> on <listener>` and
`app_rpc_namespace_denials_total` show which rule was applied.

**Solution**: add the namespace to the rule (in `client-identities-file` it reloads; inline it takes a restart).

#### Issue: SSL Handshake Fails

**Cause**: TLS version mismatch, cipher incompatibility, or certificate validation failure

**Solution**:
```bash
# Test TLS connection
openssl s_client -connect localhost:8546 -showcerts

# Check for specific errors:
# - "certificate verify failed": Certificate validation issue
# - "no shared cipher": Cipher mismatch
# - "protocol version": TLS version mismatch

# For self-signed certificates, clients must trust the certificate
curl --cacert tls/fukuii-rpc.crt https://localhost:8546/health

# "hostname mismatch": the name or address the client used is not in the
# certificate's subjectAltName; regenerate with gen-cert.sh --san
openssl x509 -in tls/fukuii-rpc.crt -noout -text | grep -A1 "Subject Alternative Name"
```

#### Issue: "Connection Refused"

**Cause**: Node not listening on configured interface/port

**Solution**:
```bash
# Check if node is running
ps aux | grep fukuii

# Check if port is listening
netstat -tulpn | grep 8546
# or
ss -tulpn | grep 8546

# Verify interface binding
# "localhost" (the default) for local-only access
# Any other interface needs tls.enabled = true or allow-insecure-bind = true (see Migration)

# Check firewall
sudo ufw status | grep 8546
sudo iptables -L -n | grep 8546
```

#### Issue: HTTP/HTTPS Mixed Content

**Cause**: Client expecting HTTP, server using HTTPS (or vice versa)

**Solution**:
```bash
# Verify mode in logs
tail -f ~/.fukuii/etc/logs/fukuii.log | grep "listening on"

# Should show either:
# "JSON RPC HTTP server listening on ..." (TLS off)
# "JSON RPC HTTPS server listening on ..." (tls.enabled = true)
# and for WebSocket "JSON RPC WS server ..." or "JSON RPC WSS server ..."

# Update client URL scheme to match
# TLS off: http://localhost:8546, ws://localhost:8552
# TLS on:  https://localhost:8546, wss://localhost:8552
```

### Certificate Validation Errors

#### Self-Signed Certificate Issues

When using self-signed certificates, clients must explicitly trust them or skip validation:

**curl**:
```bash
# Skip validation (testing only)
curl -k https://localhost:8546

# Trust specific certificate (the PEM file gen-cert.sh wrote)
curl --cacert tls/fukuii-rpc.crt https://localhost:8546
```

**Python**:
```python
# Skip validation (testing only)
requests.post(url, verify=False)

# Trust specific certificate
requests.post(url, verify='tls/fukuii-rpc.crt')
```

**Node.js**:
```javascript
// Skip validation (testing only)
const options = {
  rejectUnauthorized: false
};

// Trust specific certificate
const options = {
  ca: fs.readFileSync('tls/fukuii-rpc.crt')
};
```

### Debug Mode

Enable detailed SSL/TLS debugging:

```bash
# Start Fukuii with SSL debugging
./bin/fukuii -Djavax.net.debug=ssl,handshake etc

# Or set environment variable
export JAVA_OPTS="-Djavax.net.debug=ssl"
./bin/fukuii etc
```

This will show detailed TLS handshake information in logs.

### Log Analysis

Key log patterns to watch for:

```bash
# TLS listeners and their rules, at start
grep "TLS listener" ~/.fukuii/etc/logs/fukuii.log

# HTTPS / WSS servers bound
grep -E "JSON RPC (HTTPS|WSS) server listening" ~/.fukuii/etc/logs/fukuii.log

# Plaintext opt-outs (WARN at every start)
grep "Plaintext listener" ~/.fukuii/etc/logs/fukuii.log

# Refused handshakes, reloads, expiry warnings
grep -E "TLS handshake refused|TLS profile .* (reloaded|reload failed|certificate expires)" ~/.fukuii/etc/logs/fukuii.log

# Namespace denials (identity rules)
grep "RPC denied" ~/.fukuii/etc/logs/fukuii.log
```

## Security Best Practices

### Certificate Security

1. **Use Strong Key Sizes**: Minimum 2048-bit RSA, recommended 4096-bit
2. **Short Validity Periods**: 1-2 years maximum for server certificates; days to weeks for client certificates,
   which is how access is revoked ([Revoking Client Access](#revoking-client-access)). Reload makes renewal cheap
3. **Strong Algorithms**: Use SHA-256 or SHA-384, avoid SHA-1 and MD5
4. **Secure Storage**: Encrypt certificate backups, restrict file permissions
5. **Certificate Pinning**: Pin certificates in critical applications

### Password Management

1. **Strong Passwords**: Minimum 20 characters, random generation
2. **Secure Storage**: Store passwords in encrypted vaults (HashiCorp Vault, AWS Secrets Manager)
3. **Access Control**: Limit access to password files
4. **Rotation**: Change keystore passwords during certificate rotation (`gen-cert.sh` generates a new one every run)
5. **Never Commit**: Keystores, keys and password files are per deployment. The repository ignores everything
   under `tls/` except `gen-cert.sh`, and CI rejects a committed keystore or private key

### TLS Configuration

1. **Minimum TLS 1.2**: the node refuses a profile whose `protocols` go lower; set `protocols = ["TLSv1.3"]` where
   every client supports it
2. **Strong Ciphers Only**: Disable weak and export ciphers (`cipher-suites` in the profile)
3. **Authenticate clients**: `client-auth = "need"` on any listener reachable from outside the host, with a
   truststore holding only the CA that issues your clients' certificates, and identity rules for any caller that needs
   more than the listener's `apis`
4. **Perfect Forward Secrecy**: Use ECDHE key exchange
5. **Certificate Validation**: Always validate certificates in production
6. **HSTS**: Use HTTP Strict Transport Security headers (at a proxy; the node does not send them)

### Network Security

1. **Firewall Rules**: Restrict TLS port access to trusted IPs
2. **VPN/Private Network**: Keep RPC on private networks when possible
3. **Rate Limiting**: Implement rate limiting at firewall or reverse proxy
4. **DDoS Protection**: Use DDoS mitigation services for public endpoints
5. **Network Segmentation**: Separate RPC network from public P2P network

### Compliance

#### Common Requirements

- **PCI-DSS**: TLS 1.2+, strong ciphers, certificate validation
- **HIPAA**: Encryption in transit, access controls, audit logging
- **SOC 2**: Certificate management, key rotation, security monitoring
- **GDPR**: Data encryption, secure key management, breach notification

#### Audit Checklist

- [ ] TLS 1.2 or higher enabled
- [ ] Weak ciphers disabled
- [ ] Certificate from trusted CA (production)
- [ ] Certificate expiration monitoring
- [ ] Key rotation policy and schedule
- [ ] Access controls on certificate files
- [ ] Encrypted certificate backups
- [ ] Security event logging enabled
- [ ] Regular security audits scheduled

## Related Documentation

- [Security Runbook](security.md) - Comprehensive node security guide
- [Peering](peering.md#consortium-node-allowlist) - The P2P node ID allowlist for consortium networks
- [Enterprise Deployment](enterprise-deployment.md) - Private and consortium networks
- [Node Configuration](node-configuration.md) - General configuration reference
- [Operations Runbooks](README.md) - Complete runbook index
- ADR [OPS-005: Zero-Trust Listeners](../adr/operations/OPS-005-zero-trust-listeners.md)

## References

- **Fukuii Source Code**: 
  - `src/main/scala/com/chipprbots/ethereum/tls/` (profiles, listeners, reload, outbound factory)
  - `src/main/scala/com/chipprbots/ethereum/jsonrpc/server/http/`
  - `src/main/resources/conf/base/tls.conf`, `network.conf`, `metrics.conf`
- **Spec**: `specs/030-zero-trust-tls/` (contracts/config.md lists every key)
- **Java Keytool Documentation**: https://docs.oracle.com/en/java/javase/17/docs/specs/man/keytool.html
- **OpenSSL Documentation**: https://www.openssl.org/docs/
- **TLS Best Practices**: https://wiki.mozilla.org/Security/Server_Side_TLS

## Support

For issues or questions:
1. Check [Known Issues](known-issues.md)
2. Review [Log Triage](log-triage.md) for debugging
3. Open an issue at https://github.com/chippr-robotics/fukuii/issues
4. Contact Chippr Robotics LLC

---

**Last Updated**: 2026-11-19 (spec 030: profiles, mTLS, identity rules, reload, outbound TLS)

<a id="migration"></a>

## Migration: plaintext listeners on a non-loopback interface

Since spec 030 slice 6, a node refuses to start when a listener binds an interface that is not loopback without TLS,
and says which key to set:

```
Refusing to start: fukuii.network.rpc.http binds 0.0.0.0 (not loopback) without TLS. Set fukuii.network.rpc.http.tls.enabled = true with a profile, or fukuii.network.rpc.http.allow-insecure-bind = true to accept plaintext on that interface (docs/runbooks/tls-operations.md#migration)
```

An interface is loopback only when every address it resolves to is (`localhost`, `127.0.0.1`, `::1`); `0.0.0.0` and
`::` are not, and a name that does not resolve refuses the start too. A disabled listener is not checked.

| Listener | Interface key | TLS key | Opt-out key |
| --- | --- | --- | --- |
| JSON-RPC HTTP | `fukuii.network.rpc.http.interface` | `…rpc.http.tls.enabled` (or the deprecated `mode = "https"`) | `…rpc.http.allow-insecure-bind` |
| JSON-RPC WebSocket | `fukuii.network.rpc.ws.interface` | `…rpc.ws.tls.enabled` | `…rpc.ws.allow-insecure-bind` |
| Engine API | `fukuii.network.engine-api.interface` | `…engine-api.tls.enabled` | `…engine-api.allow-insecure-bind` |
| Metrics | `fukuii.metrics.interface` (new; default `localhost`) | `…metrics.tls.enabled` | `…metrics.allow-insecure-bind` |

The faucet's listener follows the same rule through its `fukuii.network.rpc.http` keys.

**Two ways out, per listener:**

1. **Turn on TLS** (the recommendation): a profile under `fukuii.tls.profiles` and `tls { enabled = true, profile }`
   on the listener (see [Configuration](#configuration); `tls/gen-cert.sh` issues test material). For RPC and
   metrics, `client-auth = need` with a truststore also authenticates the clients.
2. **Accept plaintext on that interface deliberately**: `allow-insecure-bind = true` on the listener. The node starts
   and logs one WARN line per such listener at every start:
   `Plaintext listener rpc-http http://0.0.0.0:8546 allow-insecure-bind=true (...)`. Use it on a private network you
   control (a Docker network, a host-only bridge); the Engine API's JWT then crosses that network in the clear.

**Metrics now binds loopback by default.** Before, the Prometheus endpoint bound every interface. A Prometheus server on
another host or container needs `fukuii.metrics.interface` set, and then TLS or `metrics.allow-insecure-bind = true`.

The repository's own deployments were migrated in the same change: hive's `fukuii.sh` (RPC and the Engine API, on
hive's private network), the `ops/` fleet configurations and compose files (each listener on `0.0.0.0` names
`allow-insecure-bind = true`), and `src/universal/custom-config-example.conf`.

