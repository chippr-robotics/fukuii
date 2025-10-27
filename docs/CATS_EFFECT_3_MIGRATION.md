# Cats Effect 3 Migration Plan for Fukuii

**Date**: October 27, 2025  
**Repository**: chippr-robotics/fukuii  
**Current Version**: Cats Effect 2.5.5  
**Target Version**: Cats Effect 3.5.4 (latest stable)  
**Status**: Planning Complete

---

## Executive Summary

This document outlines the migration plan for upgrading from Cats Effect 2.5.5 to Cats Effect 3.5.4. The migration is a prerequisite for full Scala 3 adoption and provides significant benefits including improved performance, better type safety, and a more ergonomic API.

**Current Usage Assessment:**
- Limited Cats Effect 2 usage in codebase
- Primary usage: `Resource` for resource management
- Test code uses: `Deferred`, `Ref`, and `Effect` type class
- Main production code uses `Resource` with Monix `Task`
- No direct usage of `IO` monad in current codebase
- Usage is concentrated in ~14 files

**Migration Complexity**: **Low to Medium**
- Most usage is in test code
- Breaking changes are well-documented and mechanical
- Primary usage (`Resource`) has minimal API changes
- Concurrent primitives moved to different packages
- `Effect` type class removed (requires small refactor)

**Estimated Effort**: 4-8 hours for code changes + testing

---

## Table of Contents

1. [Breaking Changes Analysis](#breaking-changes-analysis)
2. [Current Usage Inventory](#current-usage-inventory)
3. [Migration Strategy](#migration-strategy)
4. [Code Changes Required](#code-changes-required)
5. [Testing Strategy](#testing-strategy)
6. [Scala 3 Compatibility](#scala-3-compatibility)
7. [Risk Assessment](#risk-assessment)
8. [Timeline](#timeline)
9. [Recommendations](#recommendations)

---

## 1. Breaking Changes Analysis

### 1.1 Major Breaking Changes in Cats Effect 3

#### Package Restructuring
- **CE2**: `cats.effect.concurrent.{Deferred, Ref, Semaphore}`
- **CE3**: `cats.effect.{Deferred, Ref, Semaphore}`
- **Impact**: Simple import changes
- **Files Affected**: 4 files

#### Effect Type Class Removal
- **CE2**: `cats.effect.Effect[F]` type class
- **CE3**: Removed; use `Sync[F]` or `Async[F]` instead
- **Impact**: One test file uses `Effect[Task]`
- **Files Affected**: 1 file (SpecBase.scala)

#### IO Monad Changes
- **CE2**: `IO` from `cats.effect.IO`
- **CE3**: `IO` now requires runtime (`IORuntime`)
- **Impact**: Not applicable - we don't use `IO` directly, we use Monix `Task`
- **Files Affected**: 0 files

#### Resource Changes
- **CE2**: `Resource[F[_], A]`
- **CE3**: `Resource[F[_], A]` - mostly compatible
- **Changes**: 
  - `Resource.make` signature unchanged
  - `Resource.use` remains the same
  - Some internal implementation details changed
- **Impact**: Minimal - existing usage should work
- **Files Affected**: 8 files (mostly compatible)

#### Concurrent Primitives
- **CE2**: `concurrent.Deferred`, `concurrent.Ref`
- **CE3**: Top-level `Deferred`, `Ref` in `cats.effect` package
- **Impact**: Import path changes only
- **Files Affected**: 4 files

#### Fiber and Cancellation
- **CE2**: `Fiber[F, A]`
- **CE3**: `Fiber[F, E, A]` (added error type parameter)
- **Impact**: Not applicable - we don't use Fiber directly
- **Files Affected**: 0 files

### 1.2 Non-Breaking Improvements

These are enhancements in CE3 that we can leverage:

- **Better performance**: ~2-3x faster in benchmarks
- **Improved type inference**: Less need for explicit type annotations
- **Better stack traces**: Easier debugging
- **Dispatcher**: New API for bridging effects to callbacks
- **Supervisor**: Better resource management for child fibers
- **Tracing**: Built-in execution tracing

---

## 2. Current Usage Inventory

### 2.1 Production Code (src/main)

| File | Usage | Migration Required |
|------|-------|-------------------|
| RocksDbDataSource.scala | `Resource` import | ✅ Compatible, no change needed |
| PortForwarder.scala | `Resource` with `Task` | ✅ Compatible, no change needed |
| EthNodeStatus64ExchangeState.scala | `SyncIO` import | ✅ Compatible, no change needed |
| DiscoveryServiceBuilder.scala | `Resource` with `Task` | ✅ Compatible, no change needed |
| PeerDiscoveryManager.scala | `Resource` with `Task` | ✅ Compatible, no change needed |
| ForkIdValidator.scala | `Resource` import | ✅ Compatible, no change needed |

**Summary**: All production code uses `Resource`, which has minimal API changes. No code changes required for production code.

### 2.2 Test Code (src/test)

| File | Usage | Migration Required |
|------|-------|-------------------|
| SpecBase.scala | `Effect[Task]` type class | ⚠️ Needs change: Use `Sync[Task]` instead |
| RegularSyncSpec.scala | `Resource` | ✅ Compatible, no change needed |
| EtcPeerManagerFake.scala | `Deferred[Task, _]` | ⚠️ Import path change |
| FastSyncBranchResolverActorSpec.scala | `Deferred[Task, _]` | ⚠️ Import path change |
| PeerDiscoveryManagerSpec.scala | `Resource` | ✅ Compatible, no change needed |

### 2.3 Integration Test Code (src/it)

| File | Usage | Migration Required |
|------|-------|-------------------|
| RockDbIteratorSpec.scala | `Deferred`, `Ref` | ⚠️ Import path change |
| FastSyncItSpecUtils.scala | `Resource` | ✅ Compatible, no change needed |
| RegularSyncItSpecUtils.scala | `Resource` | ✅ Compatible, no change needed |

### 2.4 Usage Statistics

```
Total files using Cats Effect: 14
- Production code: 6 files (all using Resource)
- Test code: 5 files (Resource + concurrent primitives)
- Integration test code: 3 files (Resource + concurrent primitives)

Import types breakdown:
- cats.effect.Resource: 8 files (100% compatible)
- cats.effect.concurrent.Deferred: 4 files (needs import change)
- cats.effect.concurrent.Ref: 1 file (needs import change)
- cats.effect.Effect: 1 file (needs type class change)
- cats.effect.SyncIO: 1 file (compatible)
```

---

## 3. Migration Strategy

### 3.1 Phased Approach

**Phase 1: Dependency Update** (1 hour)
- Update `project/Dependencies.scala` to Cats Effect 3.5.4
- Update related dependencies (log4cats to 2.x)
- Verify dependency resolution

**Phase 2: Import Path Updates** (1 hour)
- Update `cats.effect.concurrent._` imports to `cats.effect._`
- Automated with find-replace or scalafix rule
- Affects 4 files

**Phase 3: Effect Type Class Migration** (1 hour)
- Replace `Effect[Task]` with `Sync[Task]` in SpecBase.scala
- Verify test infrastructure still works
- Alternative: Use `Async[Task]` if needed

**Phase 4: Testing and Validation** (2-4 hours)
- Run full test suite
- Fix any compilation errors
- Verify all tests pass
- Check integration tests

**Phase 5: Documentation** (1 hour)
- Update DEPENDENCY_UPDATE_REPORT.md
- Update SCALA_3_MIGRATION_REPORT.md
- Update CONTRIBUTING.md
- Update README.md

### 3.2 Rollback Plan

If issues arise:
1. Revert to Cats Effect 2.5.5 in Dependencies.scala
2. Revert import changes
3. All code will compile immediately

Low risk due to minimal usage and well-defined breaking changes.

---

## 4. Code Changes Required

### 4.1 Dependencies.scala Update

**File**: `project/Dependencies.scala`

```scala
// BEFORE (CE2)
val cats: Seq[ModuleID] = {
  val catsVersion = "2.9.0"
  Seq(
    "org.typelevel" %% "mouse" % "1.2.1",
    "org.typelevel" %% "cats-core" % catsVersion,
    "org.typelevel" %% "cats-effect" % "2.5.5" // Keep 2.x for now
  )
}

val logging = Seq(
  "ch.qos.logback" % "logback-classic" % "1.5.12",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
  "net.logstash.logback" % "logstash-logback-encoder" % "8.0",
  "org.codehaus.janino" % "janino" % "3.1.12",
  "org.typelevel" %% "log4cats-core" % "1.7.0", // CE2 compatible
  "org.typelevel" %% "log4cats-slf4j" % "1.7.0"  // CE2 compatible
)

// AFTER (CE3)
val cats: Seq[ModuleID] = {
  val catsVersion = "2.9.0"
  val catsEffectVersion = "3.5.4" // Latest stable CE3
  Seq(
    "org.typelevel" %% "mouse" % "1.2.1",
    "org.typelevel" %% "cats-core" % catsVersion,
    "org.typelevel" %% "cats-effect" % catsEffectVersion // CE3 upgrade
  )
}

val logging = Seq(
  "ch.qos.logback" % "logback-classic" % "1.5.12",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
  "net.logstash.logback" % "logstash-logback-encoder" % "8.0",
  "org.codehaus.janino" % "janino" % "3.1.12",
  "org.typelevel" %% "log4cats-core" % "2.6.0", // CE3 compatible
  "org.typelevel" %% "log4cats-slf4j" % "2.6.0"  // CE3 compatible
)
```

### 4.2 Import Path Changes

**Files to Update**: 
- `src/it/scala/com/chipprbots/ethereum/db/RockDbIteratorSpec.scala`
- `src/test/scala/com/chipprbots/ethereum/blockchain/sync/EtcPeerManagerFake.scala`
- `src/test/scala/com/chipprbots/ethereum/blockchain/sync/fast/FastSyncBranchResolverActorSpec.scala`

```scala
// BEFORE (CE2)
import cats.effect.concurrent.Deferred
import cats.effect.concurrent.Ref

// AFTER (CE3)
import cats.effect.Deferred
import cats.effect.Ref
```

**Search-Replace Command**:
```bash
find src -name "*.scala" -type f -exec sed -i 's/cats\.effect\.concurrent\.Deferred/cats.effect.Deferred/g' {} \;
find src -name "*.scala" -type f -exec sed -i 's/cats\.effect\.concurrent\.Ref/cats.effect.Ref/g' {} \;
```

### 4.3 Effect Type Class Migration

**File**: `src/test/scala/com/chipprbots/ethereum/SpecBase.scala`

```scala
// BEFORE (CE2)
import cats.effect.Effect

trait SpecBase {
  // ... other code ...
  
  // Assuming usage like this:
  def someTestMethod[F[_]: Effect]: F[Unit] = ???
}

// AFTER (CE3)
import cats.effect.Sync
// OR
import cats.effect.Async

trait SpecBase {
  // ... other code ...
  
  // Use Sync for synchronous effects
  def someTestMethod[F[_]: Sync]: F[Unit] = ???
  
  // OR use Async if async capabilities needed
  def someTestMethod[F[_]: Async]: F[Unit] = ???
}
```

**Decision Criteria**:
- Use `Sync[F]` for basic effect operations (most common)
- Use `Async[F]` if code needs `async`/`start` capabilities
- Monix `Task` implements both `Sync` and `Async`

### 4.4 No Changes Required

These files need **no changes** because `Resource` API is compatible:
- `RocksDbDataSource.scala`
- `PortForwarder.scala`
- `DiscoveryServiceBuilder.scala`
- `PeerDiscoveryManager.scala`
- `ForkIdValidator.scala`
- `EthNodeStatus64ExchangeState.scala` (SyncIO is unchanged)
- All files only importing `Resource`

---

## 5. Testing Strategy

### 5.1 Pre-Migration Testing

1. **Baseline Establishment**
   ```bash
   sbt clean
   sbt compile
   sbt testAll
   ```
   
2. **Document Current Test Results**
   - Record which tests pass/fail before migration
   - Establish performance baseline if needed

### 5.2 Post-Migration Testing

1. **Compilation Test**
   ```bash
   sbt clean
   sbt compile
   ```
   Expected: Clean compilation with no errors

2. **Unit Tests**
   ```bash
   sbt test
   ```
   Expected: All existing tests pass (same as baseline)

3. **Integration Tests**
   ```bash
   sbt "IntegrationTest / test"
   ```
   Expected: All integration tests pass

4. **Specific Tests to Verify**
   - `RockDbIteratorSpec` (uses Deferred, Ref)
   - `FastSyncBranchResolverActorSpec` (uses Deferred)
   - `EtcPeerManagerFake` (uses Deferred)
   - Any test using `SpecBase` (Effect type class)

5. **Cross-Compilation Test** (if targeting Scala 3)
   ```bash
   sbt ++3.3.4 compile
   sbt ++3.3.4 test
   ```

### 5.3 Validation Criteria

- ✅ Zero compilation errors
- ✅ Zero new test failures
- ✅ All tests that passed before still pass
- ✅ No runtime exceptions from Cats Effect code
- ✅ Resource cleanup still works correctly
- ✅ Performance remains similar (optional)

---

## 6. Scala 3 Compatibility

### 6.1 Cats Effect 3 and Scala 3

**Good News**: Cats Effect 3 has excellent Scala 3 support:
- ✅ Published for Scala 3.3.x (LTS)
- ✅ Published for Scala 2.13.x (cross-compilation)
- ✅ API is identical between Scala 2 and Scala 3
- ✅ Better type inference in Scala 3
- ✅ Performance improvements in Scala 3

**Scala 3 Specific Benefits**:
- Improved stack traces with Scala 3
- Better inlined performance
- Cleaner error messages
- Full support for new Scala 3 features (unions, etc.)

### 6.2 Monix Compatibility

**Current**: Monix 3.4.1 (partial Scala 3 support)

**Cats Effect 3 Alternative**: We could consider migrating from Monix to CE3's `IO`:
- Monix has limited Scala 3 support
- CE3 `IO` is the recommended effect type
- CE3 `IO` has better performance and support

**Recommendation**: Keep Monix for now, evaluate CE3 `IO` in future:
- Monix `Task` works with CE3 type classes
- Migration from Monix to `IO` would be larger effort
- Can be done as separate phase after CE3 adoption

### 6.3 Cross-Compilation Strategy

```scala
// build.sbt - already configured for cross-compilation
crossScalaVersions := List("2.13.8", "3.3.4")

// CE3 works seamlessly with both versions
libraryDependencies += "org.typelevel" %% "cats-effect" % "3.5.4"
```

---

## 7. Risk Assessment

### 7.1 Low Risks

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Compilation errors from import changes | Very Low | Low | Mechanical changes, easy to fix |
| Test failures from Effect type class | Low | Low | Well-documented replacement |
| Resource API incompatibilities | Very Low | Low | API is mostly compatible |

### 7.2 Medium Risks

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Subtle behavior changes in concurrency | Low | Medium | Extensive testing, review CE3 docs |
| Performance regressions | Very Low | Medium | CE3 is generally faster; benchmark if needed |
| Dependency conflicts with other libraries | Low | Medium | All dependencies updated for CE3 |

### 7.3 High Risks

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| None identified | N/A | N/A | Limited usage reduces risk |

### 7.4 Overall Risk Assessment

**Overall Risk Level**: **LOW**

**Reasoning**:
- Limited Cats Effect usage in codebase
- Well-documented breaking changes
- Mechanical changes with clear migration path
- Most usage (Resource) is compatible
- Easy rollback if needed
- Strong CE3 community support

---

## 8. Timeline

### 8.1 Detailed Schedule

**Total Estimated Time**: 8-12 hours

| Phase | Duration | Tasks |
|-------|----------|-------|
| Pre-migration Testing | 1 hour | Run tests, establish baseline |
| Dependency Updates | 1 hour | Update Dependencies.scala, verify resolution |
| Import Path Changes | 1 hour | Update concurrent imports in 4 files |
| Effect Type Class Migration | 1 hour | Update SpecBase.scala |
| Compilation & Testing | 3-4 hours | Fix errors, run all tests |
| Documentation | 1-2 hours | Update migration docs |
| Code Review & Validation | 1-2 hours | Review changes, final testing |

### 8.2 Milestones

- **Day 1**: Dependencies updated, imports fixed, compiles cleanly
- **Day 1-2**: All tests passing, documented
- **Day 2**: Code review complete, PR ready for merge

---

## 9. Recommendations

### 9.1 Immediate Actions

1. **Approve Migration**: Low risk, high value for Scala 3 migration
2. **Schedule Work**: 1-2 day effort for one developer
3. **Run Baseline Tests**: Establish current state before changes
4. **Review Breaking Changes**: Team should understand CE3 differences

### 9.2 Migration Order

**Recommended Order**:
1. ✅ **Do This First**: Cats Effect 3 migration (this document)
2. ⏭️ **Then Do**: Scala 3 migration (already planned)
3. ⏭️ **Later Consider**: Monix to CE3 IO migration (optional, larger effort)
4. ⏭️ **Later Consider**: json4s to Circe migration (deferred)
5. ⏭️ **Later Consider**: Shapeless 3 migration (during Scala 3 switch)

**Rationale**: CE3 is prerequisite for Scala 3; minimal effort with high value.

### 9.3 Post-Migration Actions

1. **Document Lessons Learned**: Update this document with actual experience
2. **Update Team Guidelines**: Document CE3 best practices
3. **Monitor Performance**: Watch for any issues in production
4. **Consider CE3 Features**: Evaluate Supervisor, Dispatcher, etc. for future use

### 9.4 Decision: Proceed with Migration

**Recommendation**: **PROCEED** with Cats Effect 3 migration

**Justification**:
- Low risk due to limited usage
- Required for Scala 3 adoption
- Well-documented migration path
- Community strongly recommends CE3
- Performance and feature improvements
- ~8-12 hours effort is reasonable
- Easy rollback if needed

---

## Appendices

### Appendix A: Cats Effect 3 Resources

**Official Documentation**:
- [CE3 Migration Guide](https://typelevel.org/cats-effect/docs/migration-guide)
- [CE3 Tutorial](https://typelevel.org/cats-effect/docs/tutorial)
- [CE3 API Docs](https://typelevel.org/cats-effect/api/3.x/)

**Community Resources**:
- [CE3 Release Notes](https://github.com/typelevel/cats-effect/releases/tag/v3.0.0)
- [Cats Effect Discord](https://discord.gg/cats-effect)
- [Typelevel Blog Posts](https://typelevel.org/blog/)

### Appendix B: Breaking Changes Checklist

Migration checklist for code review:

- [ ] Dependencies.scala updated to CE 3.5.4
- [ ] log4cats updated to 2.6.0
- [ ] All `cats.effect.concurrent._` imports changed to `cats.effect._`
- [ ] `Effect[F]` replaced with `Sync[F]` or `Async[F]`
- [ ] All files compile without errors
- [ ] All unit tests pass
- [ ] All integration tests pass
- [ ] No new compiler warnings introduced
- [ ] Documentation updated
- [ ] Code review completed

### Appendix C: Version Compatibility Matrix

| Library | Scala 2.13.8 | Scala 3.3.4 | CE2 Support | CE3 Support |
|---------|--------------|-------------|-------------|-------------|
| Cats Core 2.9.0 | ✅ | ✅ | ✅ | ✅ |
| Cats Effect 2.5.5 | ✅ | ❌ | ✅ | N/A |
| Cats Effect 3.5.4 | ✅ | ✅ | N/A | ✅ |
| Monix 3.4.1 | ✅ | ⚠️ Partial | ✅ | ✅ |
| log4cats 1.7.0 | ✅ | ✅ | ✅ | ❌ |
| log4cats 2.6.0 | ✅ | ✅ | ❌ | ✅ |

### Appendix D: Search Commands for Verification

```bash
# Find all CE2 concurrent imports
grep -r "cats.effect.concurrent" src --include="*.scala"

# Find all Effect type class usage
grep -r "Effect\[" src --include="*.scala"

# Find all Resource usage
grep -r "Resource\[" src --include="*.scala"

# Count total CE imports
grep -r "import cats.effect" src --include="*.scala" | wc -l

# Find potential Fiber usage (CE3 breaking change)
grep -r "Fiber\[" src --include="*.scala"
```

---

## Conclusion

The Cats Effect 3 migration is **ready to proceed** with **low risk** and **clear benefits**:

- ✅ **Minimal code changes**: ~5 files need updates
- ✅ **Clear migration path**: Well-documented breaking changes
- ✅ **Enables Scala 3**: Required for Scala 3 adoption
- ✅ **Performance gains**: CE3 is 2-3x faster
- ✅ **Better tooling**: Improved debugging and tracing
- ✅ **Small effort**: 8-12 hours estimated
- ✅ **Easy rollback**: Can revert if needed

**Next Steps**:
1. Review and approve this migration plan
2. Execute Phase 1 (Dependency Update)
3. Execute Phase 2 (Import Changes)
4. Execute Phase 3 (Type Class Migration)
5. Execute Phase 4 (Testing)
6. Execute Phase 5 (Documentation)

---

**Document Control**:
- **Version**: 1.0
- **Date**: October 27, 2025
- **Author**: GitHub Copilot Agent
- **Status**: Final - Ready for Implementation
- **Next Review**: After implementation completion
