# ADR Reorganization Migration Guide

This document provides a reference for the ADR reorganization completed on November 16, 2025.

## What Changed

ADRs have been reorganized from a flat, sequential numbering scheme to a category-based structure to prevent naming collisions in parallel development.

## Old → New Mapping

### Infrastructure ADRs
| Old Name | New Name | Description |
|----------|----------|-------------|
| ADR-001 | INF-001 | Migration to Scala 3 and JDK 21 |
| ADR-001a | INF-001a | Netty Channel Lifecycle with Cats Effect IO |
| ADR-009 | INF-002 | Actor System Architecture - Untyped vs Typed Actors |
| ADR-010 | INF-003 | Apache HttpClient Transport for JupnP UPnP Port Forwarding |

### VM (EVM) ADRs
| Old Name | New Name | Description |
|----------|----------|-------------|
| ADR-002 | VM-001 | EIP-3541 Implementation |
| ADR-003 | VM-002 | EIP-3529 Implementation |
| ADR-004 | VM-003 | EIP-3651 Implementation |
| ADR-005 | VM-004 | EIP-3855 Implementation |
| ADR-006 | VM-005 | EIP-3860 Implementation |
| ADR-007 | VM-006 | EIP-6049 Implementation |
| ADR-014 | VM-007 | EIP-161 noEmptyAccounts Configuration Fix |

### Consensus ADRs
| Old Name | New Name | Description |
|----------|----------|-------------|
| ADR-011 | CON-001 | RLPx Protocol Deviations and Peer Bootstrap Challenge |
| ADR-012 | CON-002 | Bootstrap Checkpoints for Improved Initial Sync |
| ADR-013 | CON-003 | Block Sync Improvements - Enhanced Reliability and Performance |
| ADR-016 (MESS) | CON-004 | MESS (Modified Exponential Subjective Scoring) Implementation |
| ADR-016 (ETH66) | CON-005 | ETH66 Protocol Aware Message Formatting |

**Note:** The old ADR-016 had two different documents with the same number - this was one of the collision issues the reorganization solves.

### Testing ADRs
| Old Name | New Name | Description |
|----------|----------|-------------|
| ADR-015 | TEST-001 | Ethereum Tests Adapter |
| ADR-017 | TEST-002 | Test Suite Strategy, KPIs, and Execution Benchmarks |

### Operations ADRs
| Old Name | New Name | Description |
|----------|----------|-------------|
| ADR-008 | OPS-001 | Enhanced Console User Interface |

## Path Changes

### Old Structure
```
docs/adr/
  ├── 001-scala-3-migration.md
  ├── 002-eip-3541-implementation.md
  └── ...
```

### New Structure
```
docs/adr/
  ├── infrastructure/
  │   ├── INF-001-scala-3-migration.md
  │   └── ...
  ├── vm/
  │   ├── VM-001-eip-3541-implementation.md
  │   └── ...
  ├── consensus/
  ├── testing/
  └── operations/
```

## Updating References

If you have local branches or documentation that reference the old ADR names:

1. **In markdown links**: Replace `docs/adr/NNN-*` with `docs/adr/CATEGORY/PREFIX-NNN-*`
   - Example: `docs/adr/001-scala-3-migration.md` → `docs/adr/infrastructure/INF-001-scala-3-migration.md`

2. **In text references**: Replace `ADR-NNN` with the appropriate `PREFIX-NNN`
   - Example: `ADR-001` → `INF-001`
   - Example: `ADR-015` → `TEST-001`

## Creating New ADRs

When creating a new ADR:

1. Choose the appropriate category directory
2. Use the next sequential number for that category
3. Follow the naming convention: `PREFIX-NNN-descriptive-title.md`
4. Update both the category README and the main `docs/adr/README.md`

### Category Prefixes
- **INF-** Infrastructure (platform, language, runtime, build)
- **VM-** Virtual Machine (EVM, EIPs, VM features)
- **CON-** Consensus (consensus, networking, P2P, sync)
- **TEST-** Testing (test infrastructure, strategies)
- **OPS-** Operations (admin, monitoring, UI, deployment)

## Benefits

This new structure provides:

1. **No naming collisions**: Different teams can work on ADRs in parallel without conflicts
2. **Clear categorization**: Easy to find relevant ADRs by domain
3. **Independent numbering**: Each category has its own sequence
4. **Scalability**: New categories can be added as needed

## Questions?

See the main [ADR README](README.md) for full documentation on the new structure.
