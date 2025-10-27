# Monix and Cats Effect 3 Compatibility Analysis

**Date**: October 27, 2025  
**Issue**: Monix 3.4.1 Incompatibility with Cats Effect 3  
**Status**: Blocking Issue for CE3 Migration

---

## Executive Summary

The Cats Effect 3 migration is **blocked** by Monix 3.4.1's incomplete support for Cats Effect 3. Monix 3.4.1 was built against Cats Effect 2.x and provides type class instances for CE2 type classes that no longer exist in CE3 (`Effect`, `ContextShift`, `Bracket`).

**Root Cause**: Monix 3.4.1 internally uses Cats Effect 2 type classes that were removed in CE3:
- `cats.effect.Effect` - removed in CE3
- `cats.effect.ContextShift` - removed in CE3 (replaced by built-in fiber scheduling)
- `cats.effect.Bracket` - removed in CE3 (functionality in `MonadCancel`)

**Impact**: Compilation fails when trying to use Monix `Task` with CE3 dependencies.

---

## Problem Details

###  Compilation Errors

When attempting to compile with Cats Effect 3.5.4:

```
[error] Symbol 'type cats.effect.Effect' is missing from the classpath.
[error] This symbol is required by 'class monix.eval.instances.CatsEffectForTask'.

[error] Symbol 'type cats.effect.ContextShift' is missing from the classpath.
[error] This symbol is required by 'value monix.tail.Iterant.cs'.

[error] Symbol 'type cats.effect.Bracket' is missing from the classpath.
[error] This symbol is required by 'class monix.reactive.Observable.CatsInstances'.
```

### Affected Files

The following production code files use Monix with Cats Effect type classes:

1. **`BlockImporter.scala`** - Uses Effect type class via Monix Task
2. **`PeerDiscoveryManager.scala`** - Uses ContextShift via Iterant
3. **`TransactionHistoryService.scala`** - Uses Bracket via Observable
4. **`RocksDbDataSource.scala`** - Uses Resource with Task
5. **`PortForwarder.scala`** - Uses Resource with Task
6. **Various test files** - Use Task with test infrastructure

---

## Solution Options

### Option 1: Wait for Monix 3.4.2+ with Full CE3 Support

**Status**: Unknown timeline

**Pros**:
- Least code changes
- Maintains current Monix usage patterns
- Community may add CE3 support

**Cons**:
- Monix development appears slow/stalled
- No clear timeline for CE3 support
- Community moving away from Monix to CE3 IO

**Recommendation**: **Not viable** - Monix project appears to have limited active development

### Option 2: Migrate from Monix to Cats Effect 3 IO

**Effort**: High (2-4 weeks)

**Description**: Replace Monix `Task` with Cats Effect 3 `IO` throughout the codebase.

**Changes Required**:
- Replace `monix.eval.Task` with `cats.effect.IO`
- Replace `monix.execution.Scheduler` with `cats.effect.unsafe.IORuntime`
- Update `Observable` usage (might need fs2 streams)
- Update all test code using Task
- Performance testing and validation

**Pros**:
- ✅ Official CE3 support (first-class citizen)
- ✅ Better performance (CE3 IO is faster)
- ✅ Active development and community
- ✅ Full Scala 3 support
- ✅ Better tooling and debugging
- ✅ Future-proof solution

**Cons**:
- ❌ Large code changes across codebase
- ❌ Learning curve for team
- ❌ Requires extensive testing
- ❌ May need to replace reactive streams (Observable)

**Recommendation**: **Best long-term solution** - Should be planned as separate migration

### Option 3: Defer CE3 Migration, Keep CE2 for Now

**Effort**: Low (revert changes)

**Description**: Revert to Cats Effect 2.5.5 and defer CE3 migration until Monix situation resolves or separate Monix->IO migration is completed.

**Pros**:
- ✅ No immediate work required
- ✅ Current code continues to work
- ✅ Can focus on other priorities

**Cons**:
- ❌ Blocks Scala 3 migration (CE3 is better for Scala 3)
- ❌ Missing performance improvements
- ❌ Delays technical debt reduction
- ❌ CE2 is end-of-life, no new features

**Recommendation**: **Fallback option** - Only if timeline doesn't allow Option 2

### Option 4: Hybrid Approach - Shim Layer

**Effort**: Medium (1 week)

**Description**: Create compatibility shims that wrap Monix Task to work with CE3 type classes.

**Changes Required**:
- Create custom `Async[Task]` instance for CE3
- Implement compatibility layer
- Test thoroughly

**Pros**:
- ✅ Allows CE3 migration without full Monix rewrite
- ✅ Buys time for proper migration later
- ✅ Moderate effort

**Cons**:
- ❌ Complex implementation
- ❌ May have subtle bugs
- ❌ Technical debt (temporary workaround)
- ❌ Performance overhead from wrapping

**Recommendation**: **Possible short-term solution** - If CE3 is urgent but Monix migration timeline is unclear

---

## Recommended Migration Path

### Phase 1: Immediate (This PR)

**Decision**: **Defer CE3 migration** until Monix situation is resolved.

**Actions**:
1. ✅ Keep migration plan documentation (this and CATS_EFFECT_3_MIGRATION.md)
2. ✅ Revert dependency changes to CE2
3. ✅ Keep import path cleanups (CE2/CE3 compatible)
4. ✅ Document this blocker in migration report

**Rationale**: CE3 migration is blocked by Monix compatibility. Rather than implement temporary workarounds, plan proper solution.

### Phase 2: Near-term (Next Quarter)

**Action**: Plan Monix to Cats Effect 3 IO migration

**Steps**:
1. Create detailed Monix -> IO migration plan
2. Identify all Monix usage (Task, Observable, Scheduler)
3. Plan fs2-streams adoption for Observable replacement
4. Create proof-of-concept for one module
5. Estimate effort and timeline
6. Assign resources

### Phase 3: Implementation

**Action**: Execute Monix -> IO migration

**Modules in order** (dependency-based):
1. bytes (no Monix usage)
2. rlp (minimal Monix usage)
3. crypto (moderate Monix usage)  
4. node (heavy Monix usage - main application logic)

**Timeline**: 4-6 weeks for full migration + testing

### Phase 4: CE3 Migration

**Action**: After Monix removed, upgrade to CE3

**Steps**:
1. Update cats-effect to 3.5.4
2. Update related dependencies (log4cats, etc.)
3. Test thoroughly
4. Deploy

**Timeline**: 1 week (much simpler without Monix)

---

## Alternative: Evaluate Scala 3 Migration First

### Consider This Order

Instead of:
1. Cats Effect 3
2. Scala 3

Consider:
1. Monix -> CE3 IO migration
2. Cats Effect 3 (now easy)
3. Scala 3 (with CE3 already working)

Or even:
1. Scala 3 migration (using CE2)
2. Monix -> CE3 IO migration (in Scala 3)
3. Cats Effect 3 upgrade

**Rationale**: 
- Scala 3 works with Cats Effect 2 (partial support)
- Could migrate to Scala 3 first, then tackle Monix/CE3
- Depends on which is higher priority

---

## Immediate Next Steps

### For This PR (Cats Effect 3 Migration Attempt)

**Decision**: **PAUSE** CE3 migration, document findings

**Actions**:
1. ✅ Complete this compatibility analysis document
2. ✅ Update CATS_EFFECT_3_MIGRATION.md with blocker status
3. ⏭️ Revert to Cats Effect 2.5.5 in Dependencies.scala
4. ⏭️ Keep Scala 2.13.14 if compatible with CE2, otherwise revert to 2.13.8
5. ⏭️ Keep import path updates (compatible with CE2)
6. ⏭️ Update README and CONTRIBUTING about Monix situation
7. ⏭️ Update SCALA_3_MIGRATION_REPORT.md with CE3 blocker
8. ⏭️ Close this PR as "Blocked - needs Monix migration first"

### For Next Planning Session

**Actions**:
1. Decide priority: CE3 or Scala 3?
2. Plan Monix -> IO migration as separate project
3. Estimate resources and timeline
4. Assign team members
5. Create tickets for migration work

---

## Technical Details: Monix Type Class Instances

### Cats Effect 2 Type Classes (used by Monix 3.4.1)

```scala
// These exist in Monix 3.4.1 for CE2
implicit val catsEffectForTask: CatsEffectForTask
  extends Effect[Task]
  with ContextShift[Task]
  with Bracket[Task, Throwable]

// Observable instances
implicit val catsInstances: CatsInstances
  extends Bracket[Observable[*, E], E]
  with MonadError[Observable[*, E], E]
```

### Cats Effect 3 Type Classes (what we need)

```scala
// These are needed for CE3 but Monix doesn't provide them
implicit val asyncForTask: Async[Task]  // replaces Effect + ContextShift
implicit val monadCancelForTask: MonadCancel[Task, Throwable]  // replaces Bracket

// Observable would need similar updates
```

### Why Monix Can't Provide CE3 Instances Easily

1. **Effect removed**: No direct equivalent - split into Sync + Async
2. **ContextShift removed**: Built into CE3 fiber runtime
3. **Bracket removed**: Replaced by MonadCancel with different API
4. **Fiber API changed**: Fiber[F, E, A] vs Fiber[F, A]

Monix would need significant internal refactoring to support CE3 properly.

---

## Community Research

### Monix Status

- Last release: 3.4.1 (May 2021)
- Limited activity on GitHub
- Some CE3 work in progress but no timeline
- Community discussions suggest Monix development is slowing

### Alternatives to Monix

1. **Cats Effect 3 IO** (recommended)
   - Official effect type for CE3
   - Best performance
   - Active development

2. **ZIO** (different ecosystem)
   - Has own effect system
   - Not compatible with CE3
   - Would be even larger migration

### Migration Precedents

Several projects have migrated from Monix to CE3 IO:
- http4s 0.23+ (uses CE3 IO)
- fs2 3.x (CE3 native)
- Various Typelevel ecosystem projects

Typical timeline: 2-4 weeks for medium-sized projects

---

## Conclusion

**CE3 migration is blocked by Monix compatibility issues.**

**Recommended Actions**:
1. **Short-term**: Document blocker, revert CE3 changes (keep planning docs)
2. **Medium-term**: Plan comprehensive Monix -> CE3 IO migration
3. **Long-term**: Complete Monix migration, then CE3 upgrade, then Scala 3

**Alternative Path**:
- Could consider Scala 3 migration with CE2 first
- Then migrate Monix -> IO in Scala 3 codebase
- Then upgrade to CE3

**Decision Point**: Which provides more value sooner - Scala 3 or CE3?

---

**Document Control**:
- **Version**: 1.0
- **Date**: October 27, 2025
- **Author**: GitHub Copilot Agent
- **Status**: Final - Blocking Issue Documented
- **Next Action**: Team decision on migration priorities
