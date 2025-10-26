# Scala 3.0 Migration Report for Fukuii

**Date**: October 26, 2025  
**Repository**: chippr-robotics/fukuii  
**Current Version**: Scala 2.13.6  
**Target Version**: Scala 3.3+ (LTS)  
**Purpose**: Investigation and roadmap for migrating the Fukuii Ethereum client from Scala 2.13 to Scala 3.0+

---

## Executive Summary

This report analyzes the requirements and steps needed to migrate the Fukuii Ethereum client from Scala 2.13.6 to Scala 3.0+. Fukuii is a continuation of the Mantis Ethereum Classic client with ~270,000 lines of Scala code across 1,941 files. The migration presents moderate complexity due to:

1. **Large codebase**: ~270K lines across multiple modules (bytes, crypto, rlp, node)
2. **External dependencies**: 30+ libraries that need Scala 3 compatibility
3. **Static analysis toolchain**: Scalafmt, Scalafix, Scapegoat, Scoverage need updates
4. **Implicit conversions**: 284+ implicit vals, 103+ implicit classes, requiring migration to new `given`/`using` syntax
5. **Build system**: SBT 1.10.7 with multiple plugins requiring updates

**Recommendation**: Proceed with a phased migration approach, starting with dependency compatibility assessment, followed by automated migration using Scala 3 migration tooling, then manual fixes for complex cases.

**Estimated Timeline**: 4-8 weeks for a complete migration with testing and validation.

---

## Table of Contents

1. [Current State Analysis](#current-state-analysis)
2. [Scala 3 Migration Overview](#scala-3-migration-overview)
3. [Dependency Compatibility Assessment](#dependency-compatibility-assessment)
4. [Code Changes Required](#code-changes-required)
5. [Tooling Migration](#tooling-migration)
6. [Build System Updates](#build-system-updates)
7. [Migration Strategy](#migration-strategy)
8. [Risk Assessment](#risk-assessment)
9. [Timeline and Resources](#timeline-and-resources)
10. [Recommendations](#recommendations)

---

## 1. Current State Analysis

### 1.1 Project Overview

- **Current Scala Version**: 2.13.6 (with support for 2.12.13)
- **SBT Version**: 1.10.7
- **JDK Version**: 17 (Temurin)
- **Code Metrics**:
  - Total Scala files: 1,941
  - Total lines of code: ~270,000
  - Modules: bytes, crypto, rlp, node (main)
  
### 1.2 Module Structure

```
fukuii/
├── bytes/          - Byte manipulation utilities
├── crypto/         - Cryptographic operations
├── rlp/            - RLP (Recursive Length Prefix) encoding
└── node/           - Main Ethereum client implementation
```

### 1.3 Current Build Configuration

**build.sbt**:
- Cross-compilation: Scala 2.12.13, 2.13.6
- Compiler flags: Extensive use of `-Xlint`, `-Ywarn-unused`, fatal warnings
- Optimization flags: `-opt:l:method`, `-opt:l:inline` for production
- SemanticDB enabled for Scalafix

**Compiler Options**:
```scala
baseScalacOptions = Seq(
  "-unchecked",
  "-deprecation",
  "-feature",
  "-Ywarn-unused",
  "-Xlint",
  "-encoding", "utf-8"
)
```

### 1.4 Static Analysis Toolchain

| Tool | Version | Purpose | Scala 3 Status |
|------|---------|---------|----------------|
| Scalafmt | 2.7.5 | Code formatting | ✅ 3.x available |
| Scalafix | 0.10.4 | Linting/refactoring | ✅ 0.12+ for Scala 3 |
| Scapegoat | 1.4.11 | Bug detection | ⚠️ 3.x requires Scala 3 |
| Scoverage | 2.0.10 | Code coverage | ✅ 2.1+ for Scala 3 |
| sbt-sonar | 2.2.0 | SonarQube integration | ✅ Compatible |

---

## 2. Scala 3 Migration Overview

### 2.1 Key Changes in Scala 3

Scala 3 (formerly Dotty) represents a major evolution of the language with significant improvements:

#### 2.1.1 Syntax Changes

1. **New Control Structure Syntax**
   - Optional braces with significant indentation
   - `then` keyword in conditions (optional)
   - Quiet syntax improvements

2. **Implicit System Overhaul**
   - `implicit` → `given` instances
   - `implicit` parameters → `using` clauses
   - Context bounds remain similar but underlying mechanism changed
   - Extension methods replace implicit classes

3. **Type System Improvements**
   - Union types: `A | B`
   - Intersection types: `A & B`
   - Type lambdas simplified
   - Match types for type-level programming

4. **Pattern Matching**
   - No change needed for basic patterns
   - New features available (type tests, etc.)

5. **Macros**
   - Complete rewrite (Scala 2 macros incompatible)
   - New macro system with quotes and splices

#### 2.1.2 Removed Features

1. **Procedure Syntax**: Already deprecated in Scala 2.13
2. **XML Literals**: Moved to library
3. **Symbol Literals**: `'symbol` syntax removed
4. **DelayedInit**: No longer supported
5. **Early Initializers**: Removed
6. **Existential Types**: Removed

#### 2.1.3 Compiler Flags Changes

Many Scala 2 compiler flags are removed or renamed:
- `-Xlint` → individual lints need explicit enabling
- `-Ywarn-*` flags → new warning system
- `-Xfatal-warnings` → `-Werror`

### 2.2 Binary Compatibility

**Important**: Scala 3 is NOT binary compatible with Scala 2. This means:
- All dependencies must be published for Scala 3
- Cannot mix Scala 2 and Scala 3 compiled code
- Cross-compilation requires separate artifacts

---

## 3. Dependency Compatibility Assessment

### 3.1 Critical Dependencies

| Dependency | Current Version | Scala 3 Status | Migration Impact |
|------------|----------------|----------------|------------------|
| Akka | 2.6.9 | ✅ 2.6.x available for Scala 3 | Update to 2.6.20+ |
| Akka HTTP | 10.2.0 | ✅ 10.2.x available for Scala 3 | Update to 10.2.10+ |
| Cats Core | 2.6.1 | ✅ 2.x available for Scala 3 | Update to 2.9.0+ |
| Cats Effect | 2.5.1 | ✅ 3.x available for Scala 3 | Major upgrade needed |
| Circe | 0.13.0 | ✅ 0.14.x for Scala 3 | Minor upgrade |
| Monix | 3.2.2 | ⚠️ 3.4.x partial support | May need migration to Cats Effect 3 |
| ScalaTest | 3.2.2 | ✅ 3.2.x for Scala 3 | Minor upgrade |
| ScalaMock | 5.0.0 | ✅ 5.x for Scala 3 | OK |
| ScalaCheck | 1.15.1 | ✅ 1.17.x for Scala 3 | Minor upgrade |
| json4s | 3.6.9 | ⚠️ 4.0.x for Scala 3 | Major upgrade, breaking changes |
| Shapeless | 2.3.3 | ✅ Shapeless 3 available | Major rewrite, significant changes |
| Enumeratum | 1.6.1 | ✅ 1.7.x for Scala 3 | Minor upgrade |
| Boopickle | 1.3.3 | ✅ 1.4.x for Scala 3 | Minor upgrade |
| Scalanet | 0.6.0 | ❓ Check with IOHK | May need update or fork |

### 3.2 Java Dependencies

All pure Java dependencies are compatible:
- ✅ Bouncy Castle (bcprov-jdk15on)
- ✅ RocksDB
- ✅ Logback
- ✅ Prometheus client
- ✅ Guava
- ✅ Apache Commons

### 3.3 High-Risk Dependencies

#### 3.3.1 Scalanet (IOHK Network Library)
- **Current**: 0.6.0
- **Issue**: May not be published for Scala 3
- **Impact**: Critical - core P2P networking
- **Solution Options**:
  1. Request Scala 3 version from IOHK
  2. Fork and migrate if necessary
  3. Evaluate alternative networking libraries

#### 3.3.2 json4s
- **Current**: 3.6.9
- **Issue**: 4.0.x has breaking API changes
- **Impact**: Moderate - used for JSON serialization
- **Solution Options**:
  1. Migrate to json4s 4.0.x (breaking changes)
  2. Consider migrating to Circe entirely (already in use)

#### 3.3.3 Shapeless
- **Current**: 2.3.3 (Shapeless 2)
- **Issue**: Shapeless 3 is completely different
- **Impact**: Moderate - used in RLP module
- **Solution Options**:
  1. Rewrite Shapeless code for Shapeless 3
  2. Consider alternative approaches (Scala 3 native features)

#### 3.3.4 Monix
- **Current**: 3.2.2
- **Issue**: Limited Scala 3 support, community moving to Cats Effect 3
- **Impact**: Moderate - used for reactive streams
- **Solution Options**:
  1. Update to Monix 3.4.x (partial Scala 3 support)
  2. Migrate to Cats Effect 3 (recommended by community)

### 3.4 Dependency Update Priority

**Phase 1 - Prerequisites** (before Scala 3 migration):
1. Update Akka to 2.6.20+
2. Update Akka HTTP to 10.2.10+
3. Update Cats to 2.9.0+
4. Evaluate Monix vs Cats Effect 3 migration

**Phase 2 - During Migration**:
1. Update all test frameworks (ScalaTest, ScalaCheck, ScalaMock)
2. Update Circe to 0.14.x
3. Evaluate json4s migration strategy
4. Check Scalanet availability

**Phase 3 - Post Migration**:
1. Migrate Shapeless usage
2. Complete Monix migration if needed
3. Update all remaining dependencies

---

## 4. Code Changes Required

### 4.1 Implicit Conversions (284+ instances)

**Current Pattern (Scala 2.13)**:
```scala
implicit val ordering: Ordering[Transaction] = ...
implicit def byteString2GByteString(b: ByteString): GByteString = ...
implicit class RichByteString(val bs: ByteString) extends AnyVal { ... }
```

**Scala 3 Pattern**:
```scala
given Ordering[Transaction] = ...
given Conversion[ByteString, GByteString] = (b: ByteString) => ...
extension (bs: ByteString)
  def richMethod: Result = ...
```

**Migration Scope**:
- **284 implicit vals**: Need conversion to `given` instances
- **103 implicit classes**: Need conversion to extension methods
- **Implicit parameters**: Need `using` clauses

**Automated Migration**: Scalafix has rules for automatic conversion:
- `scala/scala3-migrate` rule converts most implicits
- Manual review recommended for complex cases

### 4.2 Procedure Syntax

**Status**: ✅ Already handled by Scalafix ProcedureSyntax rule
- Project already has this rule enabled in `.scalafix.conf`
- Should be minimal or zero instances remaining

### 4.3 Symbol Literals

**Search Required**: Need to check for `'symbol` usage
```bash
grep -r "'\w\+" --include="*.scala" src/
```

**Migration**: Convert to plain strings or `Symbol("name")`

### 4.4 Type Lambdas

**Current Syntax**:
```scala
type Lambda[A] = ({ type T[X] = Map[X, A] })#T
```

**Scala 3 Syntax**:
```scala
type Lambda[A] = [X] =>> Map[X, A]
```

**Scope**: Need to search codebase for type lambda usage

### 4.5 Wildcard Import Changes

Scala 3 uses `*` instead of `_` for wildcard imports:
```scala
// Scala 2
import scala.collection.mutable._

// Scala 3
import scala.collection.mutable.*
```

**Migration**: Can be automated with Scalafix

### 4.6 Vararg Splicing

**Scala 2**: `seq: _*`  
**Scala 3**: `seq*`

**Scope**: Common pattern, needs systematic replacement

### 4.7 Package Objects

Package objects are discouraged in Scala 3. Found in:
- `crypto/src/main/scala/com/chipprbots/ethereum/crypto/package.scala`

**Migration**: Move definitions to regular objects or top-level definitions

### 4.8 Compiler Plugin Dependencies

Check for compiler plugins that may not work with Scala 3:
- ✅ SemanticDB: Scala 3 compatible
- ✅ Scalafmt: Scala 3 compatible
- ❓ Others: Need audit

---

## 5. Tooling Migration

### 5.1 Scalafmt

**Current**: 2.7.5  
**Target**: 3.7.x (latest)

**Changes Required**:
- Update `.scalafmt.conf` version
- Some formatting rules may change behavior
- Test formatting on Scala 3 code

**Migration Path**:
```scala
// .scalafmt.conf
version = "3.7.17"
runner.dialect = scala3
```

### 5.2 Scalafix

**Current**: 0.10.4 (for Scala 2.13.6)  
**Target**: 0.12.x (for Scala 3)

**Changes Required**:
- Update sbt-scalafix plugin
- Some rules may not be available for Scala 3
- Use Scala 3 migration rules

**Migration Rules**:
```scala
rules = [
  Scala3Migrate,           // Main migration rule
  RemoveUnused,
  OrganizeImports
]
```

**Note**: Scalafix 0.12+ requires Scala 2.13.8+ or Scala 3.x

### 5.3 Scapegoat

**Current**: 1.4.11 (Scala 2.13 only)  
**Target**: 3.x (Scala 3 only)

**Changes Required**:
- Update sbt-scapegoat plugin to 3.x
- Plugin version 3.x only works with Scala 3
- Cannot use during cross-compilation

**Migration Strategy**:
1. Keep Scapegoat 1.4.11 for Scala 2.13 builds
2. Switch to Scapegoat 3.x when fully on Scala 3
3. Consider alternative static analyzers if needed during transition

### 5.4 Scoverage

**Current**: 2.0.10  
**Target**: 2.1.x (Scala 3 support)

**Changes Required**:
- Update sbt-scoverage plugin to 2.1.x
- Coverage collection may have different behavior
- Regenerate baseline coverage

**Migration Path**:
```scala
// project/plugins.sbt
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.1.0")
```

### 5.5 IDE Support

**IntelliJ IDEA**:
- ✅ Scala 3 support available in latest versions
- Requires Scala plugin update

**Metals (VS Code)**:
- ✅ Scala 3 support available
- Better Scala 3 support than IntelliJ for some features

**Recommendation**: Ensure all team members update IDE/plugins

---

## 6. Build System Updates

### 6.1 SBT Configuration

**Current**: SBT 1.10.7 ✅ (Scala 3 compatible)

**build.sbt Changes Required**:

```scala
// Update Scala version
scalaVersion := "3.3.1" // LTS version

// Update cross-build settings
crossScalaVersions := List("2.13.12", "3.3.1")

// Update compiler options for Scala 3
scalacOptions := baseScalacOptions ++ scala3Options

val scala3Options = Seq(
  "-Werror",                    // Replaces -Xfatal-warnings
  "-Xcheck-macros",            // Enable macro checking
  "-explain",                   // Explain errors in more detail
  "-no-indent",                 // Disable significant indentation (for gradual migration)
  "-old-syntax",               // Allow Scala 2 syntax (for gradual migration)
  "-source:3.3"                // Specify source version
)

// For gradual migration
scalacOptions ++= {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((3, _)) => scala3Options
    case _ => baseScalacOptions ++ fatalWarnings
  }
}
```

### 6.2 SBT Plugins

| Plugin | Current | Scala 3 Status | Update Required |
|--------|---------|----------------|-----------------|
| sbt-buildinfo | 0.10.0 | ✅ 0.11.x | Minor update |
| sbt-native-packager | 1.7.5 | ✅ 1.9.x | Minor update |
| sbt-scalafmt | 2.4.2 | ✅ 2.5.x | Minor update |
| sbt-scalafix | 0.10.4 | ✅ 0.12.x | Major update |
| sbt-scapegoat | 1.2.13 | ⚠️ 3.x for Scala 3 | Major update |
| sbt-scoverage | 2.0.10 | ✅ 2.1.x | Minor update |
| sbt-sonar | 2.2.0 | ✅ 2.3.x | Minor update |

### 6.3 Protocol Buffers

**Current**: ScalaPB for protobuf generation

**Scala 3 Status**: ✅ ScalaPB 0.11.x supports Scala 3

**Update Required**:
```scala
// project/scalapb.sbt
libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.11.13"
```

---

## 7. Migration Strategy

### 7.1 Recommended Approach: Phased Migration

**Phase 0: Preparation (1-2 weeks)**
1. Update to latest Scala 2.13 patch version (2.13.12)
2. Update dependencies to versions with Scala 3 support
3. Fix all deprecation warnings
4. Ensure all tests pass
5. Create migration branch
6. Document current test coverage baseline

**Phase 1: Tool Setup (1 week)**
1. Install Scala 3 compiler alongside Scala 2.13
2. Update SBT plugins for cross-compilation
3. Set up Scala 3 migration tooling (Scalafix rules)
4. Configure build for cross-compilation
5. Update CI pipeline to test both versions

**Phase 2: Automated Migration (2-3 weeks)**
1. Run Scalafix `Scala3Migrate` rule on entire codebase
2. Fix any compilation errors from automated migration
3. Module-by-module approach:
   - Start with `bytes` (smallest, fewest dependencies)
   - Then `rlp`
   - Then `crypto`
   - Finally `node` (largest, most complex)
4. Verify tests pass for each module after migration

**Phase 3: Manual Fixes (1-2 weeks)**
1. Fix implicit conversion issues not caught by automation
2. Update macro code if any exists
3. Fix type inference issues
4. Address Shapeless migration
5. Update compiler flags
6. Verify all linters work correctly

**Phase 4: Validation & Testing (1-2 weeks)**
1. Run full test suite extensively
2. Run integration tests
3. Performance testing and comparison
4. Update all documentation
5. Code review of all changes
6. Address any regression issues

**Phase 5: Cleanup (1 week)**
1. Remove Scala 2.13 cross-compilation if desired
2. Remove compatibility shims
3. Enable new Scala 3 features where beneficial
4. Update static analysis configuration
5. Final documentation updates

### 7.2 Alternative: Cross-Compilation

Maintain both Scala 2.13 and Scala 3 versions simultaneously:

**Pros**:
- Lower risk
- Can revert easily
- Gradual adoption

**Cons**:
- More complex build configuration
- Slower development
- Must maintain compatibility with both versions
- Some dependencies may not support both

**Recommendation**: Use cross-compilation during Phases 2-3, then commit fully to Scala 3.

### 7.3 Module-by-Module Strategy

```
Migration Order (dependency-based):
1. bytes (no internal dependencies)
2. rlp (depends on bytes)
3. crypto (depends on bytes)
4. node (depends on all)
```

For each module:
1. Run automated migration tools
2. Fix compilation errors
3. Run module-specific tests
4. Verify integration with dependent modules
5. Commit and review

---

## 8. Risk Assessment

### 8.1 High Risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Scalanet unavailable for Scala 3 | Medium | Critical | Contact IOHK early, prepare fork plan |
| Major dependency incompatibility | Low | High | Audit dependencies first, find alternatives |
| Macro code incompatibility | Medium | High | Identify macros early, plan rewrite |
| Performance regression | Low | High | Benchmark extensively, profile |
| Subtle behavior changes | Medium | Medium | Extensive testing, gradual rollout |

### 8.2 Medium Risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Shapeless 3 migration complexity | High | Medium | Evaluate alternatives, budget time |
| json4s breaking changes | High | Medium | Consider Circe migration instead |
| Type inference changes | Medium | Medium | Expect manual fixes, budget time |
| CI/CD pipeline issues | Medium | Low | Update pipeline early in process |

### 8.3 Low Risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Test framework compatibility | Low | Low | Well-supported, minor updates |
| Build tool issues | Low | Low | SBT 1.x fully supports Scala 3 |
| IDE compatibility | Low | Low | Modern IDEs support Scala 3 |

---

## 9. Timeline and Resources

### 9.1 Estimated Timeline

**Conservative Estimate**: 8 weeks (2 months)
- Preparation: 1.5 weeks
- Tool setup: 1 week
- Automated migration: 2.5 weeks
- Manual fixes: 2 weeks
- Testing & validation: 2 weeks
- Cleanup: 0.5 week
- Buffer: 0.5 week

**Optimistic Estimate**: 4 weeks (1 month)
- Assumes fewer manual fixes needed
- Assumes all dependencies are readily available
- Assumes minimal issues during testing

**Recommendation**: Plan for 6-8 weeks to account for unknowns

### 9.2 Resource Requirements

**Personnel**:
- 1-2 senior Scala developers (full-time)
- 1 DevOps engineer (part-time for CI/CD)
- 1 QA engineer (during validation phase)

**Skills Required**:
- Deep Scala 2.13 knowledge
- Understanding of Scala 3 new features
- Experience with large-scale refactoring
- Familiarity with Ethereum/blockchain concepts (for testing validation)

**Infrastructure**:
- Additional CI/CD capacity for parallel builds
- Test environments for integration testing
- Monitoring for performance comparison

### 9.3 Cost Considerations

**Development Time**: 320-640 hours (2 devs × 4-8 weeks)
**Testing Time**: 80-160 hours
**Code Review**: 40-80 hours
**Documentation**: 20-40 hours

**Total**: 460-920 hours of engineering effort

---

## 10. Recommendations

### 10.1 Immediate Actions (Before Migration)

1. **Dependency Audit** (Priority: Critical)
   - Verify Scalanet availability for Scala 3
   - Contact IOHK if needed
   - Identify all dependencies without Scala 3 versions
   - Create backup plans for problematic dependencies

2. **Update to Latest Scala 2.13** (Priority: High)
   - Update from 2.13.6 to 2.13.12
   - Fix all deprecation warnings
   - This makes migration smoother

3. **Update Dependencies** (Priority: High)
   - Update Akka, Cats, Circe, test frameworks
   - Ensure using latest Scala 2.13-compatible versions
   - Test thoroughly after updates

4. **Scalafix Compatibility** (Priority: High)
   - Update to Scala 2.13.8+ to use Scalafix 0.11.x
   - This enables better migration rules

5. **Test Coverage Analysis** (Priority: Medium)
   - Ensure >70% coverage maintained
   - Add tests for critical paths if needed
   - Document coverage baseline

### 10.2 Migration Decision

**Recommendation**: **PROCEED** with Scala 3 migration, but with careful planning

**Justification**:
- Scala 3 is now mature (3.3.x LTS available)
- Most critical dependencies have Scala 3 support
- Long-term maintainability benefits
- Performance improvements in Scala 3
- Better type system and tooling

**When to Migrate**:
- **Best Time**: After resolving dependency compatibility issues
- **Not Before**: Verifying Scalanet availability
- **Consider Delaying If**: Major feature development ongoing

### 10.3 Alternative: Incremental Migration

If full migration seems too risky:

**Option**: Maintain Scala 2.13 for now, prepare for Scala 3
- Keep dependencies up-to-date
- Follow Scala 3-compatible coding practices
- Avoid features removed in Scala 3
- Revisit decision in 6-12 months

**When to Revisit**:
- After Scala 3 adoption increases in community
- After more dependencies publish Scala 3 versions
- After team gains Scala 3 experience on smaller projects

### 10.4 Post-Migration

1. **Monitor Performance**
   - Compare performance metrics with Scala 2.13 baseline
   - Address any regressions immediately

2. **Update Documentation**
   - CONTRIBUTING.md
   - README.md
   - STATIC_ANALYSIS_INVENTORY.md
   - All developer guides

3. **Team Training**
   - Conduct Scala 3 workshops for team
   - Create internal style guide for Scala 3 features
   - Document lessons learned

4. **Continuous Improvement**
   - Gradually adopt new Scala 3 features
   - Refactor old Scala 2 patterns
   - Update code style guide

---

## Appendices

### Appendix A: Scala 3 Resources

**Official Documentation**:
- [Scala 3 Book](https://docs.scala-lang.org/scala3/book/introduction.html)
- [Migration Guide](https://docs.scala-lang.org/scala3/guides/migration/compatibility-intro.html)
- [Scala 3 Reference](https://docs.scala-lang.org/scala3/reference/)

**Migration Tools**:
- [Scala 3 Migration Plugin](https://github.com/scalacenter/scala3-migrate)
- [Scalafix Migration Rules](https://scalacenter.github.io/scalafix/docs/rules/overview.html)

**Community Resources**:
- [Scala 3 Migration Forum](https://contributors.scala-lang.org/c/scala-3-migration/52)
- [Scala Discord #scala3-migration](https://discord.com/invite/scala)

### Appendix B: Dependency Compatibility Checklist

- [ ] Akka 2.6.20+ (Scala 3 compatible)
- [ ] Akka HTTP 10.2.10+ (Scala 3 compatible)
- [ ] Cats Core 2.9.0+ (Scala 3 compatible)
- [ ] Cats Effect 3.x (Scala 3 compatible, breaking changes from 2.x)
- [ ] Circe 0.14.x (Scala 3 compatible)
- [ ] json4s 4.0.x (Scala 3 compatible, breaking changes)
- [ ] Shapeless 3.x (Scala 3 compatible, complete rewrite)
- [ ] Scalanet (❓ Check availability)
- [ ] Monix 3.4.x (partial Scala 3 support) or migrate to Cats Effect 3
- [ ] All test frameworks updated
- [ ] All SBT plugins updated

### Appendix C: Code Pattern Search Commands

```bash
# Find implicit conversions
grep -r "implicit def" --include="*.scala" src/

# Find implicit classes
grep -r "implicit class" --include="*.scala" src/

# Find implicit vals
grep -r "implicit val" --include="*.scala" src/

# Find symbol literals
grep -r "'\w\+" --include="*.scala" src/

# Find package objects
find src/ -name "package.scala"

# Find wildcard imports
grep -r "import.*\._$" --include="*.scala" src/

# Find vararg splicing
grep -r ": _\*" --include="*.scala" src/

# Find type lambdas
grep -r "type.*=.*#" --include="*.scala" src/
```

### Appendix D: Compiler Flag Migration

| Scala 2.13 Flag | Scala 3 Equivalent | Notes |
|-----------------|-------------------|-------|
| `-Xfatal-warnings` | `-Werror` | Renamed |
| `-Xlint` | Individual `-Wconf` settings | More granular |
| `-Ywarn-unused` | `-Wunused:all` | Renamed |
| `-Ywarn-value-discard` | `-Wvalue-discard` | Renamed |
| `-deprecation` | `-deprecation` | Same |
| `-feature` | `-feature` | Same |
| `-unchecked` | `-unchecked` | Same |
| `-opt:l:inline` | N/A | Optimizer improved, flag removed |

### Appendix E: Testing Checklist

**Pre-Migration**:
- [ ] All tests pass on Scala 2.13.6
- [ ] Baseline performance metrics collected
- [ ] Integration tests documented
- [ ] Code coverage baseline: ____%

**During Migration**:
- [ ] Each module tests pass after migration
- [ ] Cross-module integration tests pass
- [ ] No new compiler warnings introduced

**Post-Migration**:
- [ ] Full test suite passes on Scala 3
- [ ] Integration tests pass
- [ ] Performance metrics within acceptable range
- [ ] Code coverage maintained or improved
- [ ] Manual testing of critical paths completed

---

## Conclusion

The migration of Fukuii from Scala 2.13 to Scala 3.0+ is feasible and recommended for long-term maintainability. The key success factors are:

1. **Thorough preparation**: Audit and update dependencies first
2. **Systematic approach**: Use module-by-module migration
3. **Leverage automation**: Use Scalafix and migration tools extensively
4. **Comprehensive testing**: Validate at each step
5. **Team readiness**: Ensure team has Scala 3 knowledge

With proper planning and execution, the migration can be completed in 6-8 weeks with minimal risk to the project.

**Next Steps**:
1. Review this report with the team
2. Approve migration plan and timeline
3. Begin Phase 0 (Preparation)
4. Create detailed technical task breakdown
5. Assign resources and begin execution

---

**Document Control**:
- **Version**: 1.0
- **Date**: October 26, 2025
- **Author**: Fukuii Development Team
- **Status**: Draft for Review
- **Next Review**: After dependency audit completion
