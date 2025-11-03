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
- [ADR-002: EIP-3541 Implementation](002-eip-3541-implementation.md) - Accepted

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
