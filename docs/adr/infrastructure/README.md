# Infrastructure ADRs

This directory contains Architecture Decision Records related to infrastructure, platform, language, runtime, and build system decisions.

## Naming Convention

Infrastructure ADRs use the format: `INF-NNN-title.md` where NNN is a zero-padded sequential number.

Examples:
- `INF-001-scala-3-migration.md`
- `INF-002-future-decision.md`

## Current ADRs

- [INF-001: Migration to Scala 3 and JDK 21](INF-001-scala-3-migration.md) - Accepted (JDK baseline superseded by INF-007)
  - [INF-001a: Netty Channel Lifecycle with Cats Effect IO](INF-001a-netty-cats-effect-integration.md) - Accepted (Addendum)
- [INF-002: Actor System Architecture - Untyped vs Typed Actors](INF-002-actor-system-architecture.md) - Accepted
- [INF-003: Apache HttpClient Transport for JupnP UPnP Port Forwarding](INF-003-jupnp-apache-httpclient-transport.md) - Accepted
- [INF-004: Actor IO Error Handling Pattern with Cats Effect](INF-004-actor-io-error-handling.md) - Accepted
- [INF-005: Docker Deployment Strategy and Container Best Practices](INF-005-docker-deployment-strategy.md) - Accepted
- [INF-006: SNAP State-Ingest Capability and the Exporter Durability Boundary](INF-006-snap-state-ingest-capability.md) - Proposed
- [INF-007: JDK 25 Baseline and the C2 ReduceAllocationMerges Workaround](INF-007-jdk-25-baseline.md) - Accepted
- INF-008: reserved by spec 018 (storage layout)
- [INF-009: Scala 3.9 LTS Toolchain](INF-009-scala-3-9-lts.md) - Accepted

## Creating a New Infrastructure ADR

1. Use the next sequential number (e.g., `INF-008-title.md`)
2. Follow the standard ADR template structure
3. Link it in the index above
4. Update the main ADR README
