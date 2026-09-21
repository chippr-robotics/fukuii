# Specification Quality Checklist: Amsterdam Hard Fork Support (ETH-family)

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-09-21
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
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

## Validation Notes

**Iteration 1 findings and resolutions:**

1. *Implementation detail leakage* — the first draft named specific Scala types, file paths and line
   numbers (`ForkTimestamps`, `EvmConfig.forTimestamp()`, `BlockHeader.scala:307`) in the requirements.
   Resolved: moved all code-level identifiers out of Requirements and Success Criteria. They remain in
   the **Context** section, which is evidence for *why* the feature exists, not a statement of *how* to
   build it. FR-001 now says "expressed as a timestamp, consistent with how other post-merge forks are
   declared" rather than naming the field.

2. *Success criteria stated as raw test counts* — early drafts said "devp2p goes from 34 failures to N".
   Resolved: SC-002 now states the user-facing outcome (peers succeed at status exchange) and cites the
   27-of-34 figure as current evidence rather than as a target. **No predicted post-fix count is given
   anywhere.** A numeric prediction made earlier in this effort proved wrong for a knowable reason, and
   the same caution applies here: the failure count will not move until the chain fully imports, and how
   far it then moves is not derivable in advance.

3. *EIP numbers in requirements* — the functional requirements originally read as an EIP checklist.
   Resolved: requirements now describe the behaviour change (what gets cheaper, what gets more
   expensive, what the header carries) so they are testable by someone who has not read the EIPs. The
   EIP list is retained in Assumptions as scope definition.

4. *The unresolved arithmetic* — two fixture gas figures cannot yet be derived. Rather than omit this or
   paper over it, it is recorded in Assumptions and promoted to a binding completion gate as FR-017.
   An implementation that produces the right totals by accident, without explaining them, does not
   satisfy this specification.

**Zero [NEEDS CLARIFICATION] markers were required.** The feature description supplied measured evidence
for every scope decision. The one genuinely open question — the unexplained gas arithmetic — is not a
clarification the user can answer; it is research that belongs in `/speckit-plan`, and it is recorded as
such rather than deferred to a question.

## Notes

- Items marked incomplete require spec updates before `/speckit-clarify` or `/speckit-plan`
- All items pass as of iteration 1. Spec is ready for `/speckit-plan`.
- **Constitution alignment**: this feature is consensus-critical throughout (Principle I). Planning must
  route ETH implementation through `beacon` and obtain `forge` sign-off that ETC is untouched, per the
  Consensus-Critical Change Protocol. FR-004 and FR-018 encode the ETC-safety constraint as testable
  requirements rather than leaving it to review.
