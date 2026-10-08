# CON-013: SNAP controller and coordinator module form: move into traits, then required narrowing

**Status**: Accepted (user decision, 2026-10-08)

**Date**: 2026-10-08

**Spec**: `specs/016-snap-controller-split/` (#1401)

**Related**: [[CON-009]]–[[CON-012]] (healing behaviour that the split moves but does not change), spec 014 (#1501),
`.claude/agent-protocols/snap-sync.md`

## Context

`SNAPSyncController.scala` is 6,646 lines on `staging@9a6681cc0`; the four SNAP coordinators add 8,208 more.
Spec 016 splits them into phase modules with **zero behaviour change**:
- on-disk formats stay frozen;
- no existing test assertion changes;
- no local builds, since CI is the only compiler.

The split has to be safe to review, and it has to produce modules that are actually separate, not just separate
files.

The static characterization (spec 016 research.md) found 82 mutable items in the controller, 45 of them shared across
two or more candidate modules. A module boundary that the compiler does not enforce would let that coupling grow back
unseen.

## Options considered

1. **Separate classes with an explicit context object, from the start.** Every moved body changes (`state.x`
   prefixes, extra parameters) in the same commit as the move. The move can no longer be verified mechanically, and a
   behaviour change could hide inside a 600-line diff.
2. **Traits mixed into the impl class, with the self-type left as the impl class.** The move is byte-identical and
   easy to verify. But each "module" can still reach all 83 items: it is a file split, not a module. Coupling is
   neither visible nor bounded, and a module cannot be tested on its own.
3. **Traits for the move, then *required* narrowing (chosen).**

## Decision

Every module PR has two commits.

1. **Move.** The module goes into a trait whose self-type is temporarily the impl class. Bodies and exclusive var
   declarations are byte-identical, which a `git diff --color-moved` check and a per-symbol body diff confirm.
2. **Narrow (required).** The self-type becomes `<Module>State & <capability traits>`:
   - `<Module>State` is an abstract interface listing exactly the shared fields the module reads or writes (from the
     var × method matrix, confirmed by the compiler);
   - the capabilities are `SnapSharedState` (pivot, root, phase, progress monitor, request tracker),
     `CoordinatorHandles`, `PhaseFlags`, `SnapControllerEnv`, and the API traits of modules it calls.

   Exclusive vars become private to the trait. The only body-adjacent edit allowed is `val` → `lazy val`/`def`.

Acceptance per module PR:
- **(a)** No module self-type names the concrete impl class (grep).
- **(b)** At least one unit test mixes the module into a stub of its state interface.
- **(c)** The interface member count is recorded in the PR and the routing doc, and may not grow without
  justification.
- **(d)** No concrete `val` in a module trait, and `var` initializers on an allow-list (grep). This rules out the
  trait-before-class initialization hazard.

The coordinators follow the same rule, with no self-type naming a `*CoordinatorImpl`.

## Consequences

**Positive:**
- Moves stay mechanically reviewable.
- Each module ends with a compiler-checked dependency list, a coupling number that can be ratcheted, and a stub-based
  test seam.
- A later conversion of any module to a class needs no further body changes.

**Negative:**
- About 0.5 agent-day of extra work per module PR (26 PRs), and about 1,500 extra test lines.
- The controller stays one runtime object. Isolation is enforced at the type level, not by separate instances.
- Getter/setter interfaces are more verbose than direct fields.

**Risk:** narrowing may show more coupling than the matrix estimated. The rule is to record it, not to work around
it in the narrowing commit. A non-trivial body change needed to narrow is a stop-and-raise signal.

**Not decided here:** turning narrowed traits into classes or separate actors. That would be a later spec.
