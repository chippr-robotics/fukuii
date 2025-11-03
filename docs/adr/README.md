# Architectural Decision Records (ADR)

This directory contains Architectural Decision Records (ADRs) for the Fukuii project. An ADR is a document that captures an important architectural decision made along with its context and consequences.

## What is an ADR?

An Architectural Decision Record (ADR) is a short text document that describes a choice the team makes about a significant aspect of the software architecture they're planning to build. Each ADR describes the architectural decision, its context, and its consequences.

## ADR Format

Each ADR should follow this structure:

```markdown
# ADR-XXX: [Title]

**Date:** YYYY-MM-DD
**Status:** [Proposed | Accepted | Deprecated | Superseded]
**Related:** [References to related ADRs or components]

## Context

What is the issue that we're seeing that is motivating this decision or change?

## Decision

What is the change that we're proposing and/or doing?

## Consequences

What becomes easier or more difficult to do because of this change?

## Alternatives Considered

What other options were considered and why were they rejected?

## References

Links to relevant resources, discussions, or documentation.
```

## Naming Convention

ADR files should be named using the following format:
```
ADR-XXX-short-title.md
```

Where:
- `XXX` is a zero-padded number (e.g., 001, 002, 010, 100)
- `short-title` is a kebab-case brief description of the decision

Examples:
- `ADR-001-continuation-of-mantis.md`
- `ADR-006-eip-3529-implementation.md`

## ADR Index

| ADR | Title | Status | Date |
|-----|-------|--------|------|
| [ADR-001](../architecture-overview.md#adl-001-continuation-of-mantis-as-fukuii) | Continuation of Mantis as Fukuii | Accepted | 2024-10-24 |
| [ADR-002](../architecture-overview.md#adl-002-actor-based-architecture-with-akka) | Actor-Based Architecture with Akka | Accepted | Historical |
| [ADR-003](../architecture-overview.md#adl-003-rocksdb-as-primary-storage-backend) | RocksDB as Primary Storage Backend | Accepted | Historical |
| [ADR-004](../architecture-overview.md#adl-004-scala-as-implementation-language) | Scala as Implementation Language | Accepted | Historical |
| [ADR-005](../architecture-overview.md#adl-005-modular-package-structure) | Modular Package Structure | Accepted | Historical |
| [ADR-006](ADR-006-eip-3529-implementation.md) | Implementation of EIP-3529 | Accepted | 2024-10-25 |

## When to Create an ADR

Create an ADR when you need to make a significant architectural decision that:

- Affects the structure of the system
- Impacts multiple components or modules
- Has long-term consequences
- Involves trade-offs between different approaches
- Requires explanation for future maintainers

Examples of ADR-worthy decisions:
- Choice of major frameworks or libraries
- Implementation of Ethereum Improvement Proposals (EIPs)
- Changes to consensus mechanisms
- Modifications to the storage layer
- Significant refactoring or migration efforts

## Process

1. **Propose**: Create a new ADR file with status "Proposed"
2. **Discuss**: Share with the team for review and discussion
3. **Decide**: Update status to "Accepted" when the decision is made
4. **Implement**: Execute the decision
5. **Update**: If a decision is reversed or changed, mark as "Deprecated" or "Superseded"

## Contributing

When adding a new ADR:

1. Create a new file following the naming convention
2. Fill in all sections of the ADR template
3. Add an entry to the ADR Index table in this README
4. Submit a pull request with your ADR

## References

- [Michael Nygard's ADR concept](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions)
- [ADR GitHub organization](https://adr.github.io/)
- [Architectural Decision Records: A Guide](https://github.com/joelparkerhenderson/architecture-decision-record)
