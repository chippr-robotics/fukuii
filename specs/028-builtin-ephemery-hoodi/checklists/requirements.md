# Specification Quality Checklist: Ephemery and Hoodi as Built-in Networks

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-11-12
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain (FR-016 answered by the user, 2026-11-12: switch in place)
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- The spec names some interfaces that operators already use, because they are part of the product's surface:
  `fukuii ephemery` and `fukuii hoodi`, `FUKUII_CONF_DIR`, `scripts/ephemery/fetch-genesis.sh`, the ephemery-genesis
  releases, HTTPS, and go-ethereum as the reference for Hoodi's values. It names no internal module, class or library.
- **FR-016** was the one open question, about a running node at a reset. The user chose to switch iteration in place,
  so that long-running services survive resets. It is now the largest part of the work, and the plan must design it
  first: stopping and restarting the chain's components inside one process.
