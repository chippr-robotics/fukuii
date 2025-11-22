# Infrastructure ADRs

This directory contains Architecture Decision Records related to infrastructure, platform, language, runtime, and build system decisions.

## Naming Convention

Infrastructure ADRs use the format: `INF-NNN-title.md` where NNN is a zero-padded sequential number.

Examples:
- `INF-001-scala-3-migration.md`
- `INF-002-future-decision.md`

## Current ADRs

- [INF-001: Migration to Scala 3 and JDK 21](INF-001-scala-3-migration.md) - Accepted
  - [INF-001a: Netty Channel Lifecycle with Cats Effect IO](INF-001a-netty-cats-effect-integration.md) - Accepted (Addendum)
- [INF-002: Actor System Architecture - Untyped vs Typed Actors](INF-002-actor-system-architecture.md) - Accepted
- [INF-003: Apache HttpClient Transport for JupnP UPnP Port Forwarding](INF-003-jupnp-apache-httpclient-transport.md) - Accepted
- [INF-004: Actor IO Error Handling Pattern with Cats Effect](INF-004-actor-io-error-handling.md) - Accepted

## Creating a New Infrastructure ADR

1. Use the next sequential number (e.g., `INF-005-title.md`)
2. Follow the standard ADR template structure
3. Link it in the index above
4. Update the main ADR README
