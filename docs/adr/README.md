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

## ADR Organization by Category

To support parallel development and prevent naming collisions, ADRs are organized into categories:

### Infrastructure (`infrastructure/`)
Platform, language, runtime, and build system decisions.
- [INF-001: Migration to Scala 3 and JDK 21](infrastructure/INF-001-scala-3-migration.md) - Accepted
  - [INF-001a: Netty Channel Lifecycle with Cats Effect IO](infrastructure/INF-001a-netty-cats-effect-integration.md) - Accepted (Addendum)
- [INF-002: Actor System Architecture - Untyped vs Typed Actors](infrastructure/INF-002-actor-system-architecture.md) - Accepted
- [INF-003: Apache HttpClient Transport for JupnP UPnP Port Forwarding](infrastructure/INF-003-jupnp-apache-httpclient-transport.md) - Accepted

[View all Infrastructure ADRs →](infrastructure/README.md)

### VM (EVM) (`vm/`)
EVM implementations, EIPs, and VM-specific features.
- [VM-001: EIP-3541 Implementation](vm/VM-001-eip-3541-implementation.md) - Accepted
- [VM-002: EIP-3529 Implementation](vm/VM-002-eip-3529-implementation.md) - Accepted
- [VM-003: EIP-3651 Implementation](vm/VM-003-eip-3651-implementation.md) - Accepted
- [VM-004: EIP-3855 Implementation](vm/VM-004-eip-3855-implementation.md) - Accepted
- [VM-005: EIP-3860 Implementation](vm/VM-005-eip-3860-implementation.md) - Accepted
- [VM-006: EIP-6049 Implementation](vm/VM-006-eip-6049-implementation.md) - Accepted
- [VM-007: EIP-161 noEmptyAccounts Configuration Fix](vm/VM-007-eip-161-noemptyaccounts-fix.md) - Accepted

[View all VM ADRs →](vm/README.md)

### Consensus (`consensus/`)
Consensus mechanisms, networking protocols, P2P communication, and blockchain synchronization.
- [CON-001: RLPx Protocol Deviations and Peer Bootstrap Challenge](consensus/CON-001-rlpx-protocol-deviations-and-peer-bootstrap.md) - Accepted
- [CON-002: Bootstrap Checkpoints for Improved Initial Sync](consensus/CON-002-bootstrap-checkpoints.md) - Accepted
- [CON-003: Block Sync Improvements - Enhanced Reliability and Performance](consensus/CON-003-block-sync-improvements.md) - Accepted
- [CON-004: MESS (Modified Exponential Subjective Scoring) Implementation](consensus/CON-004-mess-implementation.md) - Accepted
- [CON-005: ETH66 Protocol Aware Message Formatting](consensus/CON-005-eth66-protocol-aware-message-formatting.md) - Accepted

[View all Consensus ADRs →](consensus/README.md)

### Testing (`testing/`)
Testing infrastructure, strategies, test suites, and quality assurance.
- [TEST-001: Ethereum Tests Adapter](testing/TEST-001-ethereum-tests-adapter.md) - Accepted
- [TEST-002: Test Suite Strategy, KPIs, and Execution Benchmarks](testing/TEST-002-test-suite-strategy-and-kpis.md) - Accepted

[View all Testing ADRs →](testing/README.md)

### Operations (`operations/`)
Operational features, administration, monitoring, user interfaces, and deployment.
- [OPS-001: Enhanced Console User Interface](operations/OPS-001-console-ui.md) - Accepted
- [OPS-002: Logging Level Categorization Standards](operations/OPS-002-logging-level-categorization.md) - Accepted

[View all Operations ADRs →](operations/README.md)

## Creating a New ADR

When creating a new ADR:

1. Choose the appropriate category (infrastructure, vm, consensus, testing, operations)
2. Use the next sequential number for that category (e.g., `VM-008-title.md`, `CON-006-title.md`)
3. Follow the template structure
4. Link it in both the category README and this main index
5. Keep it concise but comprehensive
6. Focus on the "why" not just the "what"

### Category Naming Conventions

- **Infrastructure**: `INF-NNN-title.md`
- **VM**: `VM-NNN-title.md`
- **Consensus**: `CON-NNN-title.md`
- **Testing**: `TEST-NNN-title.md`
- **Operations**: `OPS-NNN-title.md`

This categorization allows different teams to work on ADRs in parallel without naming conflicts.

## References

- [ADR GitHub Organization](https://adr.github.io/)
- [Documenting Architecture Decisions](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions)
