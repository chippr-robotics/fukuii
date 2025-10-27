# Scalanet Fork and Migration Action Plan

**Date**: October 27, 2025  
**Repository**: chippr-robotics/fukuii  
**Decision**: Fork and maintain scalanet as part of Fukuii project  
**Status**: Action Plan - Ready for Execution

---

## Executive Decision

Chippr Robotics will **take over maintenance and continue development** of scalanet as it is a critical asset for Fukuii. The scalanet library will be forked from IOHK and migrated to Scala 3 as part of the Fukuii project.

**Source Repository**: https://github.com/input-output-hk/scalanet  
**Target Organization**: chippr-robotics  
**License**: Apache 2.0 (compatible with Fukuii)

---

## Phase 1: Repository Fork and Setup (Days 1-3)

### Task 1.1: Fork the Repository ✅ READY
**Estimated Time**: 1 hour

**Steps**:
1. Navigate to https://github.com/input-output-hk/scalanet
2. Click "Fork" button in top-right corner
3. Select "chippr-robotics" as the destination organization
4. Name: `scalanet` (keep original name)
5. Description: "Scala 3 fork of IOHK's scalanet networking library (maintained by Chippr Robotics)"
6. Ensure "Copy the main branch only" is **unchecked** to preserve all branches

**Verification**:
- [ ] Fork created at https://github.com/chippr-robotics/scalanet
- [ ] All branches copied
- [ ] All tags copied
- [ ] LICENSE file preserved (Apache 2.0)
- [ ] NOTICE file preserved (if present)

### Task 1.2: Initial Repository Audit
**Estimated Time**: 4 hours

**Clone and Inspect**:
```bash
git clone https://github.com/chippr-robotics/scalanet.git
cd scalanet
git remote add upstream https://github.com/input-output-hk/scalanet.git

# Analyze repository structure
find . -name "*.scala" | wc -l
find . -name "*.sbt" -o -name "*.scala" | xargs wc -l

# Check build configuration
cat build.sbt | head -50
cat project/build.properties
cat project/plugins.sbt

# Review dependencies
grep -r "libraryDependencies" build.sbt project/Dependencies.scala
```

**Document**:
- [ ] Total lines of code
- [ ] Number of modules/subprojects
- [ ] Current Scala version
- [ ] Current SBT version
- [ ] All dependencies and their versions
- [ ] Test framework used
- [ ] CI/CD configuration (if present)

### Task 1.3: Create Fork Documentation
**Estimated Time**: 2 hours

**Create `FORK_NOTICE.md` in repository root**:
```markdown
# Fork Notice

This is a fork of the original scalanet library by Input Output Hong Kong (IOHK).

**Original Repository**: https://github.com/input-output-hk/scalanet  
**Fork Date**: October 27, 2025  
**Fork Reason**: Scala 3 migration for Fukuii Ethereum client  
**Maintained By**: Chippr Robotics LLC (https://github.com/chippr-robotics)

## Changes from Upstream

- Migrated to Scala 3.3.4 (LTS)
- Updated dependencies to Scala 3 compatible versions
- [Additional changes will be documented here]

## License

This project continues to be licensed under the Apache 2.0 license.
Original copyright notices are preserved in the NOTICE file.

## Upstream Attribution

Original work copyright Input Output Hong Kong (IOHK).
We acknowledge and appreciate the original development work by IOHK.

## Contributing

Contributions are welcome! See CONTRIBUTING.md for guidelines.
```

**Update README.md**:
- Add fork notice at top
- Update organization references
- Add Scala 3 compatibility note
- Update CI badges (if applicable)

### Task 1.4: Set Up CI/CD Pipeline
**Estimated Time**: 4 hours

**Create `.github/workflows/ci.yml`** (if not present, or update existing):
```yaml
name: CI

on:
  push:
    branches: [ main, develop, scala3-migration ]
  pull_request:
    branches: [ main, develop ]

jobs:
  test:
    name: Test - Scala ${{ matrix.scala }}
    runs-on: ubuntu-latest
    strategy:
      matrix:
        scala: ['2.13.8', '3.3.4']
        java: ['17']
    
    steps:
    - uses: actions/checkout@v3
    
    - name: Set up JDK ${{ matrix.java }}
      uses: actions/setup-java@v3
      with:
        java-version: ${{ matrix.java }}
        distribution: 'temurin'
    
    - name: Cache SBT
      uses: actions/cache@v3
      with:
        path: |
          ~/.ivy2/cache
          ~/.sbt
        key: ${{ runner.os }}-sbt-${{ hashFiles('**/build.sbt') }}
    
    - name: Compile
      run: sbt ++${{ matrix.scala }} compile
    
    - name: Run tests
      run: sbt ++${{ matrix.scala }} test
    
    - name: Check formatting
      run: sbt ++${{ matrix.scala }} scalafmtCheckAll
      if: matrix.scala == '3.3.4'
```

**Configure GitHub Packages for artifact publishing** (in build.sbt):
```scala
ThisBuild / organization := "com.chipprbots"
ThisBuild / organizationName := "Chippr Robotics LLC"
ThisBuild / organizationHomepage := Some(url("https://github.com/chippr-robotics"))

// GitHub Packages publishing
ThisBuild / publishTo := Some(
  "GitHub Package Registry" at "https://maven.pkg.github.com/chippr-robotics/scalanet"
)
ThisBuild / credentials += Credentials(
  "GitHub Package Registry",
  "maven.pkg.github.com",
  "chippr-robotics",
  sys.env.getOrElse("GITHUB_TOKEN", "")
)
```

---

## Phase 2: Scala 3 Migration Preparation (Days 4-5)

### Task 2.1: Create Migration Branch
**Estimated Time**: 30 minutes

```bash
cd scalanet
git checkout -b scala3-migration
git push -u origin scala3-migration
```

**Branch Protection**:
- [ ] Protect `main` branch
- [ ] Require PR reviews
- [ ] Require CI passing
- [ ] Enable "scala3-migration" as working branch

### Task 2.2: Update Build Configuration for Cross-Compilation
**Estimated Time**: 4 hours

**Update `build.sbt`**:
```scala
val scala213 = "2.13.8"
val scala3 = "3.3.4"

ThisBuild / scalaVersion := scala213
ThisBuild / crossScalaVersions := Seq(scala213, scala3)

// Scala version-specific compiler options
def scalacOptionsFor(scalaVersion: String): Seq[String] = {
  CrossVersion.partialVersion(scalaVersion) match {
    case Some((3, _)) =>
      Seq(
        "-Xfatal-warnings",
        "-deprecation",
        "-feature",
        "-unchecked"
      )
    case Some((2, 13)) =>
      Seq(
        "-Xfatal-warnings",
        "-deprecation",
        "-feature",
        "-unchecked",
        "-Xlint",
        "-Ywarn-unused"
      )
    case _ => Seq.empty
  }
}

lazy val commonSettings = Seq(
  scalacOptions := scalacOptionsFor(scalaVersion.value)
)
```

### Task 2.3: Dependency Audit and Updates
**Estimated Time**: 6 hours

**Analyze Current Dependencies**:
```bash
sbt dependencyTree > dependencies-before.txt
```

**Update dependencies to Scala 3 compatible versions**:

Common dependencies that may need updates:
- Akka → 2.6.20+ (or 2.8.x for latest)
- Cats → 2.9.0+
- Monix → 3.4.1+ (or migrate to Cats Effect 3)
- Test frameworks → ScalaTest 3.2.19, ScalaCheck 1.18.1

**Create `project/Dependencies.scala`** (if not present):
```scala
object Dependencies {
  val akkaVersion = "2.6.20"
  val catsVersion = "2.9.0"
  val scalaTestVersion = "3.2.19"
  
  // Update all dependency versions for Scala 3 compatibility
}
```

**Verification**:
```bash
sbt ++2.13.8 update
sbt ++3.3.4 update
```

### Task 2.4: Install Scala 3 Migration Tooling
**Estimated Time**: 2 hours

**Update `project/plugins.sbt`**:
```scala
addSbtPlugin("ch.epfl.scala" % "sbt-scala3-migrate" % "0.6.1")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.11.1")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")
```

**Run Migration Analysis**:
```bash
sbt scala3Migrate
```

This generates a report of incompatibilities.

---

## Phase 3: Automated Migration (Days 6-10)

### Task 3.1: Run Scalafix Migration Rules
**Estimated Time**: 8 hours (iterative)

**Create `.scalafix.conf`**:
```hocon
rules = [
  ProcedureSyntax
  OrganizeImports
]

OrganizeImports.groupedImports = Merge
```

**Run automated fixes**:
```bash
# Run for each module separately if multi-module
sbt "scalafixAll"
```

### Task 3.2: Syntax Migration with scala3-migrate
**Estimated Time**: 8 hours (iterative)

**Migrate syntax for each module**:
```bash
# This rewrites source files in place
sbt "migrateSyntax <module-name>"
```

**Key syntax changes**:
- `_` → `*` in wildcard imports
- `: _*` → `*` in varargs
- Implicit conversions → given instances
- Implicit parameters → using clauses
- Implicit classes → extension methods

### Task 3.3: Fix Compilation Errors
**Estimated Time**: 16-24 hours

**Iterative process**:
```bash
# Start with Scala 2.13 to ensure nothing broke
sbt ++2.13.8 compile

# Then attempt Scala 3 compilation
sbt ++3.3.4 compile
```

**Common issues to fix**:
1. **Type inference changes**: Add explicit types where needed
2. **Implicit system**: Convert to givens/using
3. **Pattern matching**: Update exhaustiveness checks
4. **Macro code**: Rewrite if present (most complex)

**Document all manual changes** in `SCALA3_MIGRATION_NOTES.md`

---

## Phase 4: Testing and Validation (Days 11-15)

### Task 4.1: Restore Test Suite
**Estimated Time**: 8-12 hours

```bash
# Run tests on Scala 2.13 first
sbt ++2.13.8 test

# Then Scala 3
sbt ++3.3.4 test
```

**Fix test failures**:
- Update test dependencies
- Fix test syntax issues
- Address mock/stub library changes

### Task 4.2: Integration Testing with Fukuii
**Estimated Time**: 8 hours

**In fukuii repository**:

**Update `project/Dependencies.scala`**:
```scala
val network: Seq[ModuleID] = {
  val scalanetVersion = "0.7.0-SNAPSHOT" // or first release version
  Seq(
    "com.chipprbots" %% "scalanet" % scalanetVersion,
    "com.chipprbots" %% "scalanet-discovery" % scalanetVersion
  )
}
```

**Add resolver for GitHub Packages** (in `build.sbt`):
```scala
resolvers += "Chippr GitHub Packages" at 
  "https://maven.pkg.github.com/chippr-robotics/scalanet"
```

**Test compilation**:
```bash
cd /path/to/fukuii
sbt ++2.13.8 compile
sbt ++3.3.4 compile
```

**Run discovery tests**:
```bash
sbt "testOnly *PeerDiscoveryManagerSpec"
sbt "testOnly *DiscoveryServiceBuilderSpec"
```

### Task 4.3: Protocol Compliance Testing
**Estimated Time**: 8 hours

**DevP2P v4 compliance tests**:
- Test packet encoding/decoding
- Test ENR (Ethereum Node Record) handling
- Test Kademlia DHT operations
- Test peer discovery against reference clients (geth, besu)

**Performance benchmarks**:
- Measure discovery latency
- Measure packet processing throughput
- Compare Scala 2.13 vs Scala 3 performance

---

## Phase 5: Release and Documentation (Days 16-20)

### Task 5.1: Prepare First Release
**Estimated Time**: 4 hours

**Create release branch**:
```bash
git checkout -b release/0.7.0
```

**Update version**:
```scala
// version.sbt
ThisBuild / version := "0.7.0"
```

**Generate CHANGELOG**:
```markdown
# Changelog

## [0.7.0] - 2025-XX-XX

### Added
- Scala 3.3.4 (LTS) support
- Cross-compilation for Scala 2.13.8 and 3.3.4

### Changed
- Updated dependencies to Scala 3 compatible versions
- Migrated implicit system to givens/using
- Updated syntax for Scala 3 compatibility

### Maintained
- Full API compatibility with scalanet 0.6.0
- DevP2P v4 protocol compliance
- All existing functionality preserved

### Notes
- This is a fork of IOHK's scalanet maintained by Chippr Robotics
- See FORK_NOTICE.md for details
```

**Create GitHub Release**:
- Tag: `v0.7.0`
- Title: "scalanet 0.7.0 - Scala 3 Support"
- Attach artifacts: JARs for both Scala versions
- Publish to GitHub Packages

### Task 5.2: Update Fukuii Documentation
**Estimated Time**: 4 hours

**Update in fukuii repository**:

1. **`docs/SCALANET_COMPATIBILITY_ASSESSMENT.md`**:
   - Mark fork as complete ✅
   - Update with actual timeline
   - Note final repository location

2. **`docs/DEPENDENCY_UPDATE_REPORT.md`**:
   - Change scalanet status from "blocker" to "resolved"
   - Document fork details
   - Update version to 0.7.0

3. **`docs/SCALA_3_MIGRATION_REPORT.md`**:
   - Update scalanet section
   - Adjust timeline based on actual fork duration

4. **`README.md`**:
   - Update scalanet status
   - Note forked version in use

### Task 5.3: Communication and Attribution
**Estimated Time**: 2 hours

**Notify IOHK** (courtesy):
```
Subject: Scalanet Fork for Scala 3 - chippr-robotics/scalanet

Hello IOHK Team,

We are the maintainers of Fukuii, a fork of the Mantis Ethereum client.
As part of our Scala 3 migration, we have forked scalanet to maintain
Scala 3 compatibility.

Fork: https://github.com/chippr-robotics/scalanet
Reason: Scala 3 migration for Fukuii project
License: Apache 2.0 (preserved)
Attribution: Fully maintained in FORK_NOTICE.md and NOTICE files

We acknowledge the original work and appreciate the library. If IOHK
decides to pursue Scala 3 support in the future, we're happy to
collaborate or transfer maintenance back.

Thank you,
Chippr Robotics Team
```

**Community announcements**:
- Post in Ethereum development forums (if appropriate)
- Update any relevant documentation
- Make fork public and well-documented

---

## Timeline Summary

| Phase | Duration | Key Deliverables |
|-------|----------|------------------|
| **Phase 1: Fork & Setup** | 3 days | Forked repo, CI/CD, documentation |
| **Phase 2: Preparation** | 2 days | Build config, dependencies, tooling |
| **Phase 3: Migration** | 5 days | Syntax fixes, compilation, manual fixes |
| **Phase 4: Testing** | 5 days | Tests passing, integration verified |
| **Phase 5: Release** | 5 days | v0.7.0 released, docs updated |
| **Total** | **20 days** | Fully migrated and integrated |

**Buffer**: 5 days for unexpected issues  
**Total with Buffer**: **25 days (~5 weeks)**

---

## Success Criteria

- [ ] Scalanet forked to chippr-robotics organization
- [ ] Compiles successfully with Scala 3.3.4
- [ ] All tests pass on both Scala 2.13.8 and 3.3.4
- [ ] Published to GitHub Packages as version 0.7.0+
- [ ] Fukuii uses forked scalanet successfully
- [ ] DevP2P protocol compliance maintained
- [ ] Performance within 5% of Scala 2.13 version
- [ ] Documentation complete and accurate
- [ ] License and attribution properly maintained

---

## Risk Mitigation

| Risk | Mitigation Strategy |
|------|---------------------|
| Scalanet more complex than expected | Allocate buffer time; break into smaller modules |
| Macro code present | Rewrite macros using Scala 3 macro system; allocate extra time |
| Protocol compliance breaks | Extensive testing against reference clients; maintain test suite |
| Performance regression | Profile and optimize; acceptable if within 10% |
| Dependency conflicts | Use dependencyOverrides in SBT; test thoroughly |

---

## Team Assignments (Suggested)

- **Lead Engineer**: Repository fork, build configuration, Scala 3 migration
- **Testing Engineer**: Test suite restoration, protocol compliance testing
- **DevOps**: CI/CD setup, artifact publishing, GitHub Packages configuration
- **Documentation**: Fork notices, changelogs, documentation updates

Estimated total effort: **60-100 hours** across team

---

## Next Immediate Actions

1. **TODAY**: Fork repository to chippr-robotics ✅
2. **Day 1**: Clone, audit, document structure
3. **Day 2**: Set up CI/CD pipeline
4. **Day 3**: Create migration branch, update build config
5. **Day 4-10**: Execute migration
6. **Day 11-15**: Testing and validation
7. **Day 16-20**: Release and documentation

---

**Document Status**: Ready for Execution  
**Approval**: Confirmed by @realcodywburns  
**Execution Start**: Pending fork creation

---

## Additional Resources

- **Scalanet Repository**: https://github.com/input-output-hk/scalanet
- **Scala 3 Migration Guide**: https://docs.scala-lang.org/scala3/guides/migration/compatibility-intro.html
- **sbt-scala3-migrate**: https://github.com/scalacenter/scala3-migrate
- **Fukuii Repository**: https://github.com/chippr-robotics/fukuii
- **Contact**: Chippr Robotics team via GitHub issues
