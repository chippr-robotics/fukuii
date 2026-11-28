# OPS-005: Zero-Trust Listeners

**Status**: Accepted

**Date**: November 2026

**Deciders**: Chippr Robotics LLC Engineering Team

## Context

Before spec 030 the node's TLS was one optional server certificate on JSON-RPC HTTP. The WebSocket, the Engine API
and the metrics endpoint had none; no listener could authenticate its clients; the metrics endpoint bound every
interface; outbound HTTPS clients built their own trust; and a consortium had no way to keep unknown nodes off its
peer-to-peer network. The B-145 audit found plaintext RPC, Engine API and metrics ports on routable interfaces to be
the commonest exposure.

## Decision

### Every listener can speak TLS, and a non-loopback plaintext listener is an explicit choice

JSON-RPC HTTP and WS, the Engine API and metrics take a `tls { enabled, profile, client-auth }` block naming a shared
profile (`fukuii.tls.profiles.<name>`). A listener whose interface is not loopback refuses the start unless TLS is on
or `allow-insecure-bind = true` is set for it; the opt-out is logged at every start. Material is validated before any
port binds, never falls back to the JDK's trust or to plaintext, and reloads without a restart.

### A client certificate is an identity, and an identity rule is a grant

`client-auth = want | need` asks for client certificates. A verified certificate matching a rule of
`network.rpc.client-identities` is served exactly that rule's namespaces, which may go beyond the listener's own
`apis` (`admin` to one operator's certificate, for example); callers with no certificate, or none a rule matches, get
the listener's `apis`. GraphQL is gated as the pseudo-namespace `graphql`. The Engine API takes server TLS only: JWT is
its client check.

### The P2P network is protected by a node-ID allowlist, not by TLS

RLPx already authenticates both ends by their node keys; TLS on devp2p is not interoperable. A consortium node checks
the authenticated remote key against `network.peer.node-allowlist` at the handshake and on every dial, with discovery
off, and edits the list at runtime through `admin_` methods that persist to its file atomically.

### Outbound TLS comes from one factory

Every outbound HTTPS client takes its TLS from `tls/OutboundTls`, keyed by a profile or `system`: host names checked,
named trust, a client certificate where the profile has one, no https-to-http redirect. A CI ratchet holds the rest to
an allow-list.

## Consequences

- Binding a non-loopback interface in plaintext became a breaking change (0.13.0), with a migration section in
  `docs/runbooks/tls-operations.md` and the repository's own deployments migrated.
- One listener can be shared by callers of different privilege; the start-up lines print each rule's grant.
- Client-certificate revocation is by short-lived certificates and reload, not CRL or OCSP.

## References

- `specs/030-zero-trust-tls/` (spec, plan, research D-1 to D-13)
- `docs/runbooks/tls-operations.md`
