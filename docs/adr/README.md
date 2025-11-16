# Architectural Decision Records (ADR)

This directory contains Architectural Decision Records (ADRs) for the Fukuii Ethereum Client project.

## What is an ADR?

An Architectural Decision Record (ADR) is a document that captures an important architectural decision made along with its context and consequences. ADRs help teams:

- Understand why certain decisions were made
- Track the evolution of the architecture over time
- Onboard new team members more effectively
- Avoid revisiting already-settled discussions

## ADR Format

Each ADR follows this structure:

- **Title**: Short descriptive title
- **Status**: Proposed, Accepted, Deprecated, Superseded
- **Context**: The situation prompting the decision
- **Decision**: The choice that was made
- **Consequences**: The results of the decision (positive and negative)

## Index of ADRs

- [ADR-001: Migration to Scala 3 and JDK 21](001-scala-3-migration.md) - Accepted
  - [ADR-001a: Netty Channel Lifecycle with Cats Effect IO](001a-netty-cats-effect-integration.md) - Accepted (Addendum)
- [ADR-002: EIP-3541 Implementation](002-eip-3541-implementation.md) - Accepted
- [ADR-003: EIP-3529 Implementation](003-eip-3529-implementation.md) - Accepted
- [ADR-004: EIP-3651 Implementation](004-eip-3651-implementation.md) - Accepted
- [ADR-005: EIP-3855 Implementation](005-eip-3855-implementation.md) - Accepted
- [ADR-006: EIP-3860 Implementation](006-eip-3860-implementation.md) - Accepted
- [ADR-007: EIP-6049 Implementation](007-eip-6049-implementation.md) - Accepted
- [ADR-008: Enhanced Console User Interface](008-console-ui.md) - Accepted
- [ADR-009: Actor System Architecture - Untyped vs Typed Actors](009-actor-system-architecture.md) - Accepted
- [ADR-010: Apache HttpClient Transport for JupnP UPnP Port Forwarding](010-jupnp-apache-httpclient-transport.md) - Accepted
- [ADR-011: RLPx Protocol Deviations and Peer Bootstrap Challenge](011-rlpx-protocol-deviations-and-peer-bootstrap.md) - Accepted
- [ADR-012: Bootstrap Checkpoints for Improved Initial Sync](012-bootstrap-checkpoints.md) - Accepted
- [ADR-013: Block Sync Improvements - Enhanced Reliability and Performance](013-block-sync-improvements.md) - Accepted
- [ADR-014: EIP-161 noEmptyAccounts Configuration Fix](014-eip-161-noemptyaccounts-fix.md) - Accepted
- [ADR-015: Ethereum/Tests Adapter Implementation](015-ethereum-tests-adapter.md) - Accepted
- [ADR-016: ETH66 Protocol Aware Message Formatting](016-eth66-protocol-aware-message-formatting.md) - Accepted
- [ADR-017: Test Suite Strategy, KPIs, and Execution Benchmarks](017-test-suite-strategy-and-kpis.md) - Accepted

## Creating a New ADR

When creating a new ADR:

1. Use the next sequential number (e.g., `003-title.md`)
2. Follow the template structure
3. Link it in the index above
4. Keep it concise but comprehensive
5. Focus on the "why" not just the "what"

## References

- [ADR GitHub Organization](https://adr.github.io/)
- [Documenting Architecture Decisions](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions)
