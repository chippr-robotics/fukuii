# Static Analysis Toolchain Inventory

**Date**: October 26, 2025  
**Repository**: chippr-robotics/fukuii  
**Purpose**: Inventory current static analysis toolchain for state, versioning, appropriateness, ordering, and current issues

---

## Executive Summary

The Fukuii project uses a comprehensive static analysis toolchain for Scala development consisting of 6 primary tools:
1. **Scalafmt** - Code formatting
2. **Scalafix** - Code refactoring and linting
3. **Scalastyle** - Style checking
4. **Scapegoat** - Static code analysis for bugs
5. **Scoverage** - Code coverage
6. **SBT Sonar** - Integration with SonarQube

**Current State**: The toolchain has **3 issues** that need attention:
- 1 formatting violation (scalafmt)
- 3 import organization issues (scalafix)
- 976 scapegoat findings (190 errors, 215 warnings, 571 infos)

---

## Tool Inventory

### 1. Scalafmt (Code Formatter)

**Purpose**: Automatic code formatting to enforce consistent style across the codebase.

**Configuration Files**:
- `.scalafmt.conf`

**Version Information**:
- **Scalafmt Version**: 2.7.5
- **SBT Plugin**: org.scalameta:sbt-scalafmt:2.4.2

**Configuration Details**:
```scala
version = "2.7.5"
align.preset = some
maxColumn = 120
rewrite.rules = [AvoidInfix, RedundantBraces, RedundantParens, SortModifiers]
```

**Current State**: ❌ **FAILING**
- 1 file not formatted properly: `src/test/scala/com/chipprbots/ethereum/extvm/VMServerSpec.scala`

**SBT Commands**:
- `sbt scalafmtAll` - Format all sources
- `sbt scalafmtCheckAll` - Check formatting without modifying
- `sbt bytes/scalafmtAll`, `crypto/scalafmtAll`, `rlp/scalafmtAll` - Format individual modules

**Analysis**:
- ✅ **Version**: 2.7.5 is relatively recent (latest stable is 3.x series, but 2.7.5 is the last of 2.x and widely used)
- ✅ **Appropriateness**: Excellent tool for automated formatting
- ⚠️ **Issue**: Minor formatting violation exists - should be fixed
- ✅ **Ordering**: Correctly runs early in CI pipeline before other checks

**Recommendation**: 
- Fix the formatting violation in VMServerSpec.scala
- Consider updating to Scalafmt 3.x in the future for additional features

---

### 2. Scalafix (Refactoring and Linting)

**Purpose**: Automated refactoring and enforcing code quality rules through semantic analysis.

**Configuration Files**:
- `.scalafix.conf`

**Version Information**:
- **SBT Plugin**: ch.epfl.scala:sbt-scalafix:0.9.29
- **SemanticDB**: Auto-configured via scalafixSemanticdb.revision

**Rules Enabled**:
1. `ExplicitResultTypes` - Require explicit return types
2. `NoAutoTupling` - Prevent automatic tupling
3. `NoValInForComprehension` - Prevent val in for comprehensions
4. `OrganizeImports` - Organize and clean up imports
5. `ProcedureSyntax` - Remove deprecated procedure syntax
6. `RemoveUnused` - Remove unused code

**Additional Dependencies**:
- `com.github.liancheng:organize-imports:0.5.0`
- `com.github.vovapolu:scaluzzi:0.1.16`

**Configuration Details**:
```scala
OrganizeImports {
  groupedImports = Explode
  groups = [
    "re:javax?\\."
    "akka."
    "cats."
    "monix."
    "scala."
    "scala.meta."
    "*"
    "com.chipprbots.ethereum."
  ]
  removeUnused = true
}
```

**Current State**: ❌ **FAILING**
- 2 unused imports in `src/it/scala/com/chipprbots/ethereum/sync/FastSyncItSpec.scala`
- 1 import ordering issue in `src/test/scala/com/chipprbots/ethereum/extvm/VMServerSpec.scala`
- 1 unused variable in `src/test/scala/com/chipprbots/ethereum/domain/SignedLegacyTransactionSpec.scala`

**SBT Commands**:
- `sbt scalafixAll` - Apply fixes to all sources
- `sbt scalafixAll --check` - Check without modifying
- Module-specific: `bytes/scalafixAll`, `crypto/scalafixAll`, `rlp/scalafixAll`

**Analysis**:
- ⚠️ **Version**: 0.9.29 is outdated (current stable is 0.11+)
- ✅ **Appropriateness**: Excellent for semantic linting
- ⚠️ **Issues**: 3 violations found that should be fixed
- ✅ **Ordering**: Runs after compilation, appropriate placement
- ⚠️ **organize-imports**: Version 0.5.0 is old (latest is 0.6.0+)
- ⚠️ **scaluzzi**: Version 0.1.16 appears abandoned (last update 2020)

**Recommendation**: 
- Fix the current violations
- Update sbt-scalafix to 0.11.x or latest
- Update organize-imports to 0.6.x
- Consider replacing scaluzzi with more actively maintained rules

---

### 3. Scalastyle (Style Checker)

**Purpose**: Enforce code style rules and detect common code smells.

**Configuration Files**:
- `scalastyle-config.xml` (for main sources)
- `scalastyle-test-config.xml` (for test sources)

**Version Information**:
- **SBT Plugin**: org.scalastyle:scalastyle-sbt-plugin:1.0.0

**Key Rules Enabled**:
- FileTabChecker
- FileLengthChecker (max 800 lines, currently disabled)
- WhitespaceEndOfLineChecker
- FileLineLengthChecker (max 160 chars)
- ClassNamesChecker, ObjectNamesChecker (PascalCase)
- EqualsHashCodeChecker
- IllegalImportsChecker (sun._, java.awt._)
- ParameterNumberChecker (max 8 parameters)
- MagicNumberChecker
- CyclomaticComplexityChecker (max 16)
- MethodLengthChecker (max 50 lines)
- NumberOfMethodsInTypeChecker (max 30)
- PublicMethodsHaveTypeChecker
- RegexChecker (prevents println statements)

**Current State**: ✅ **PASSING**
- Main sources: 0 errors, 0 warnings, 0 infos (401 files)
- Test sources: 0 errors, 0 warnings, 0 infos (213 files)

**SBT Commands**:
- `sbt scalastyle` - Check main sources
- `sbt Test/scalastyle` - Check test sources
- Module-specific: `bytes/scalastyle`, `crypto/scalastyle`, `rlp/scalastyle`

**Analysis**:
- ⚠️ **Version**: 1.0.0 is the last release (2017) - project appears unmaintained
- ✅ **Appropriateness**: Good for basic style checking
- ✅ **Current State**: All checks passing
- ✅ **Ordering**: Runs after formatting, before tests
- ⚠️ **Maintenance**: No updates since 2017, community moving to Scalafix for linting

**Recommendation**: 
- Keep using for now as it's stable and passing
- Long-term: Migrate rules to Scalafix as it's more actively maintained
- Consider if some rules overlap with Scalafix and can be removed

---

### 4. Scapegoat (Static Bug Detection)

**Purpose**: Static code analysis to detect common bugs, anti-patterns, and code smells.

**Configuration**:
- Configured in `build.sbt`

**Version Information**:
- **SBT Plugin**: com.sksamuel.scapegoat:sbt-scapegoat:1.1.0
- **Scapegoat Version**: 1.4.9

**Output Format**:
- XML reports in `target/scala-2.13/scapegoat-report/scapegoat.xml`

**Current State**: ❌ **FAILING**
- **Total Files Analyzed**: 423
- **Errors**: 190
- **Warnings**: 215
- **Infos**: 571
- **Total Issues**: 976

**Key Issues Found**:
1. **Generated Code Issues**: Many warnings in protobuf-generated code (MethodNames violations for `__computeSerializedValue`)
2. **PreferSeqEmpty**: Using `Seq()` instead of `Seq.empty`
3. **MaxParameters**: Methods with too many parameters
4. Various code quality issues in main codebase

**SBT Commands**:
- `sbt scapegoat` - Run analysis and generate reports

**Analysis**:
- ⚠️ **Version**: 1.1.0 (sbt plugin) and 1.4.9 (analyzer) are outdated (latest is 2.x)
- ✅ **Appropriateness**: Excellent for finding bugs and code quality issues
- ❌ **Current State**: 976 findings need review
- ⚠️ **Issue**: Many false positives from generated protobuf code
- ❓ **Ordering**: Not integrated into CI pipeline - runs separately
- ⚠️ **Configuration**: Missing exclusion for generated code directories

**Recommendation**: 
- Update to Scapegoat 2.x for better Scala 2.13 support
- Add scapegoat to CI pipeline
- Configure to exclude generated code directories:
  - `target/scala-2.13/src_managed/main/protobuf/`
  - `target/scala-2.13/src_managed/main/sbt-buildinfo/`
- Review and fix legitimate issues in main codebase
- Set up proper thresholds for errors/warnings

---

### 5. Scoverage (Code Coverage)

**Purpose**: Measure code coverage during test execution.

**Configuration**:
- Configured in `build.sbt`

**Version Information**:
- **SBT Plugin**: org.scoverage:sbt-scoverage:1.6.1

**Configuration Details**:
```scala
coverageExcludedPackages := "com\\.chipprbots\\.ethereum\\.extvm\\.msg.*"
```

**Current State**: ⚠️ **NOT ACTIVELY USED**
- Plugin is installed but not run in CI
- Coverage exclusions configured for protobuf messages
- No coverage reports being generated

**SBT Commands**:
- `sbt coverage test` - Run tests with coverage
- `sbt coverageReport` - Generate coverage reports
- `sbt coverageAggregate` - Aggregate coverage across modules

**Analysis**:
- ⚠️ **Version**: 1.6.1 is outdated (latest is 2.x)
- ✅ **Appropriateness**: Essential for measuring test coverage
- ❌ **Current State**: Not being used despite being configured
- ❓ **Ordering**: Would run during test phase (not currently in CI)
- ⚠️ **Missing**: No coverage thresholds or enforcement

**Recommendation**: 
- Update to Scoverage 2.x for better performance and Scala 2.13 support
- Add coverage execution to CI pipeline
- Set minimum coverage thresholds
- Configure proper exclusions for generated code
- Publish coverage reports as artifacts

---

### 6. SBT Sonar (SonarQube Integration)

**Purpose**: Integration with SonarQube for centralized code quality management.

**Configuration**:
- Available via plugin, likely needs additional setup

**Version Information**:
- **SBT Plugin**: com.github.mwz:sbt-sonar:2.2.0

**Current State**: ⚠️ **NOT ACTIVELY USED**
- Plugin is installed
- No SonarQube server configured
- Not integrated into CI pipeline

**SBT Commands**:
- `sbt sonarScan` - Upload analysis to SonarQube

**Analysis**:
- ⚠️ **Version**: 2.2.0 (2020) - moderately outdated
- ✅ **Appropriateness**: Good for centralized quality management
- ❌ **Current State**: Not being used
- ❓ **Prerequisites**: Requires SonarQube server setup
- ⚠️ **Alternative**: Could use SonarCloud for hosted solution

**Recommendation**: 
- Decide if SonarQube/SonarCloud is needed
- If yes: Set up server and configure project
- If no: Remove plugin to reduce dependencies
- Consider SonarCloud as easier alternative to self-hosted

---

## CI Pipeline Analysis

### Current CI Workflow (`.github/workflows/ci.yml`)

**Execution Order**:
1. **Compile** - `sbt compile-all` (compiles all modules)
2. **Format Check** - `sbt formatCheck` (scalafmt + scalafix --check)
3. **Style Check** - `sbt scalastyle` + `Test/scalastyle` (for all modules)
4. **Tests** - `sbt testAll` (runs all tests)
5. **Build** - `sbt assembly` + `sbt dist`

**Missing from CI**:
- ❌ Scapegoat analysis
- ❌ Code coverage measurement (scoverage)
- ❌ SonarQube integration

### Analysis of Ordering

✅ **Good Ordering**:
1. Compile first - Ensures code compiles before style checks
2. Formatting check early - Fast feedback on style issues
3. Scalastyle after formatting - Checks additional style rules
4. Tests run after all static checks - Tests are slower

⚠️ **Improvements Needed**:
1. Scapegoat should run after compilation (currently not in CI)
2. Coverage should run during tests
3. Consider running some checks in parallel for speed

**Recommended Ordering**:
```
1. Compile (all modules)
2. Parallel:
   - Format Check (scalafmt)
   - Scalafix Check
   - Scalastyle
   - Scapegoat
3. Tests with Coverage
4. Build artifacts
5. (Optional) SonarQube upload
```

---

## Custom Aliases in build.sbt

The project defines several useful aliases for running multiple checks:

### `pp` (Prepare PR)
```
compile-all → scalafmt → scalastyle → testQuick → IntegrationTest
```
- Comprehensive pre-PR check
- ⚠️ Missing scapegoat and coverage

### `formatAll`
```
compile-all → scalafixAll → scalafmtAll (all modules)
```
- Applies all formatting fixes
- ✅ Good for batch updates

### `formatCheck`
```
compile-all → scalafixAll --check → scalafmtCheckAll (all modules)
```
- Checks all formatting without changes
- ✅ Used in CI

### `testAll`
```
compile-all → test (all modules + IntegrationTest)
```
- Runs all tests
- ⚠️ Missing coverage

---

## Tool Comparison Matrix

| Tool | Version | Status | In CI | Issues | Update Priority |
|------|---------|--------|-------|--------|----------------|
| Scalafmt | 2.7.5 / 2.4.2 | ❌ Failing | ✅ Yes | 1 file | Medium |
| Scalafix | 0.9.29 | ❌ Failing | ✅ Yes | 3 issues | High |
| Scalastyle | 1.0.0 | ✅ Passing | ✅ Yes | 0 | Low |
| Scapegoat | 1.1.0 / 1.4.9 | ❌ Failing | ❌ No | 976 | High |
| Scoverage | 1.6.1 | ⚠️ Inactive | ❌ No | N/A | Medium |
| SBT Sonar | 2.2.0 | ⚠️ Inactive | ❌ No | N/A | Low |

---

## Issues Summary

### Critical Issues
1. **Scapegoat**: 976 findings (190 errors, 215 warnings, 571 infos)
   - Not in CI pipeline
   - No exclusions for generated code
   - Outdated version

2. **Scalafix**: Outdated version (0.9.29 vs 0.11+)
   - 3 current violations
   - Outdated dependencies

### Important Issues
3. **Scoverage**: Not being used despite being configured
4. **Scalafmt**: 1 formatting violation needs fixing

### Minor Issues
5. **Scalastyle**: Outdated and unmaintained (but working)
6. **SBT Sonar**: Installed but not configured or used

---

## Recommendations

### Immediate Actions (High Priority)
1. **Fix current violations**:
   - Fix scalafmt issue in VMServerSpec.scala
   - Fix scalafix issues (2 unused imports, 1 import order)
   
2. **Update Scalafix**:
   - Update sbt-scalafix to 0.11.x
   - Update organize-imports to 0.6.x
   - Evaluate scaluzzi replacement

3. **Configure Scapegoat**:
   - Add to CI pipeline
   - Exclude generated code directories
   - Update to version 2.x
   - Set thresholds for errors/warnings

### Medium Priority
4. **Enable Code Coverage**:
   - Update scoverage to 2.x
   - Add to CI pipeline
   - Set minimum thresholds (e.g., 70% coverage)
   - Publish reports

5. **Update Scalafmt**:
   - Consider upgrading to 3.x series
   - Evaluate new features and rules

### Low Priority
6. **Evaluate SonarQube**:
   - Decide if needed for the project
   - If yes: Set up and configure
   - If no: Remove plugin

7. **Long-term Scalastyle Migration**:
   - Evaluate migrating rules to Scalafix
   - Remove redundant checks
   - Phase out if Scalafix covers all needs

---

## Dependency Updates Needed

```scala
// Current versions → Recommended versions

// Plugins (project/plugins.sbt)
"ch.epfl.scala" % "sbt-scalafix" % "0.9.29"              → "0.11.1"
"org.scalameta" % "sbt-scalafmt" % "2.4.2"               → "2.5.2"
"com.sksamuel.scapegoat" % "sbt-scapegoat" % "1.1.0"    → "1.2.4"
"org.scoverage" % "sbt-scoverage" % "1.6.1"              → "2.0.9"
"org.scalastyle" %% "scalastyle-sbt-plugin" % "1.0.0"   → Keep (no updates)
"com.github.mwz" % "sbt-sonar" % "2.2.0"                 → "2.3.0"

// Configuration files
.scalafmt.conf: version = "2.7.5"                        → "3.7.17"

// Build.sbt dependencies
scapegoatVersion := "1.4.9"                              → "2.1.0"
"com.github.liancheng" %% "organize-imports" % "0.5.0"   → "0.6.0"
"com.github.vovapolu" %% "scaluzzi" % "0.1.16"           → Evaluate replacement
```

---

## Appropriateness Assessment

### Tools Fit for Purpose ✅
- **Scalafmt**: Perfect for automated formatting
- **Scalafix**: Excellent for semantic linting and refactoring
- **Scapegoat**: Great for bug detection
- **Scoverage**: Standard for coverage measurement

### Questionable Tools ⚠️
- **Scalastyle**: Unmaintained but functional; consider phasing out in favor of Scalafix
- **SBT Sonar**: Not being used; either configure or remove

### Tool Overlap
Some overlap exists between:
- Scalastyle and Scalafix (both do linting)
- Scalastyle and Scalafmt (both enforce style)

**Recommendation**: Keep both for now, but gradually migrate Scalastyle rules to Scalafix

---

## Execution Time Analysis

Based on CI logs and manual runs:
- **Compile**: ~60s (initial), ~10s (incremental)
- **Scalafmt check**: ~20s
- **Scalafix check**: ~170s (2m 50s) - slowest check
- **Scalastyle**: ~5s each module
- **Scapegoat**: ~43s
- **Tests**: Variable (several minutes)

**Total static analysis time**: ~3-4 minutes

**Optimization opportunities**:
- Run some checks in parallel (formatCheck + scalastyle)
- Consider caching compiled artifacts
- Scalafix is the bottleneck (may need optimization or newer version)

---

## Conclusion

The Fukuii project has a comprehensive static analysis toolchain with good coverage of formatting, linting, and code quality. However, several improvements are needed:

1. **Fix current violations** (scalafmt + scalafix)
2. **Update outdated tools** (scalafix, scapegoat, scoverage)
3. **Add missing tools to CI** (scapegoat, coverage)
4. **Clean up unused tools** (evaluate SBT Sonar)
5. **Configure exclusions** (generated code in scapegoat)

**Overall Assessment**: 🟡 **Good foundation, needs maintenance and optimization**

The toolchain is well-structured but has fallen behind on updates. With targeted improvements, it can be brought to excellent state.

---

## Next Steps

Based on this inventory, the following sub-issues should be created:

1. **Fix Current Static Analysis Violations**
   - Fix scalafmt formatting in VMServerSpec.scala
   - Fix scalafix import issues
   
2. **Update Scalafix Toolchain**
   - Update sbt-scalafix to 0.11.x
   - Update organize-imports
   - Evaluate scaluzzi replacement

3. **Integrate Scapegoat into CI**
   - Add to CI pipeline
   - Configure exclusions for generated code
   - Update to version 2.x

4. **Enable Code Coverage Tracking**
   - Update scoverage to 2.x
   - Add to CI pipeline
   - Set thresholds

5. **Tool Maintenance and Cleanup**
   - Evaluate and configure or remove SBT Sonar
   - Consider Scalafmt 3.x upgrade
   - Plan Scalastyle migration to Scalafix

---

**Document Version**: 1.0  
**Last Updated**: October 26, 2025  
**Author**: Static Analysis Inventory Tool
