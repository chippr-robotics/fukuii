# ADR-001: Migration to Scala 3 and JDK 21

**Status**: Accepted

**Date**: October 2025

**Deciders**: Chippr Robotics LLC Engineering Team

## Context

The Fukuii Ethereum Client (forked from Mantis) was originally built on Scala 2.13.6 and JDK 17. To ensure a modern, maintainable, and future-proof codebase, we needed to evaluate upgrading to newer language and runtime versions.

### Technical Landscape

**Scala Ecosystem:**
- Scala 2.13 entered maintenance mode with long-term support ending
- Scala 3 offers significant improvements in language design, type system, and developer experience
- Many core libraries and frameworks have migrated to Scala 3 (Cats, Circe, etc.)
- Scala 3.3.4 LTS provides long-term stability

**JDK Ecosystem:**
- JDK 17 is an LTS release but JDK 21 is the newer LTS (September 2023)
- JDK 21 offers performance improvements, new language features, and better tooling
- Security updates and long-term support for JDK 21 extend further than JDK 17

**Dependencies:**
- Akka licensing changes necessitated migration to Apache Pekko
- Monix lacked full Cats Effect 3 support, requiring migration to CE3 IO
- Several dependencies (Shapeless, json4s) needed updates for Scala 3 compatibility

## Decision

We decided to migrate the entire codebase to:
- **Scala 3.3.4 (LTS)** as the primary and only supported version
- **JDK 21 (LTS)** as the minimum required runtime
- **Apache Pekko 1.2.1** replacing Akka (Scala 3 compatible)
- **Cats Effect 3.5.4** and **fs2 3.9.3** replacing Monix
- **Native Scala 3 derivation** replacing Shapeless in the RLP module

This decision represents a **non-trivial update** requiring:
- Significant code changes across ~100+ files
- Complete rewrites of type derivation logic
- Migration of all effect handling from Monix Task to Cats Effect IO
- Resolution of 508+ compilation errors
- Updates to static analysis toolchain

## Consequences

### Positive

1. **Modern Language Features**
   - Native `given`/`using` syntax for cleaner implicit handling
   - Union types for flexible type modeling
   - Opaque types for zero-cost abstractions
   - Improved type inference reducing boilerplate
   - Better error messages and developer experience

2. **Performance Improvements**
   - JDK 21 runtime performance enhancements
   - Scala 3 compiler optimizations
   - Cats Effect 3 IO performance improvements over Monix Task
   - Better JIT optimization with modern JVM

3. **Long-term Maintainability**
   - Scala 3 LTS ensures stability for years to come
   - JDK 21 LTS support until September 2028 (and extended support beyond)
   - Active development and security patches for both platforms
   - Growing ecosystem of Scala 3-native libraries

4. **Ecosystem Alignment**
   - Apache Pekko avoids Akka licensing concerns
   - Cats Effect 3 is the standard effect system in Scala 3
   - Native derivation eliminates complex macro dependencies
   - Better tooling support (Metals, IDEs)

5. **Supply Chain Security**
   - Elimination of unmaintained dependencies (scalanet vendored locally)
   - Modern dependency versions with latest security patches
   - Reduced attack surface through simplified dependency tree

### Negative

1. **Migration Complexity**
   - Significant engineering effort (~3-4 weeks full-time)
   - 508+ compilation errors required manual resolution
   - Complete rewrites of RLP derivation and effect handling
   - Learning curve for Scala 3 features

2. **Breaking Changes**
   - No backward compatibility with Scala 2.13
   - Requires JDK 21 minimum (users must upgrade)
   - Some tests temporarily disabled during migration (MockFactory compatibility)
   - Binary incompatibility with Scala 2 libraries

3. **Testing Gaps**
   - 5 test files excluded due to MockFactory/Scala 3 compatibility issues
   - Integration tests required extensive validation
   - Performance benchmarks needed re-baselining

4. **Documentation Debt**
   - All documentation needed updates (Scala 2 → Scala 3)
   - Developer onboarding materials require updates
   - Community might need guidance for migration

5. **Short-term Risk**
   - Potential for subtle behavioral changes in effect handling
   - New bugs introduced during rewrite of complex logic
   - Reduced test coverage during migration period

## Implementation Details

The migration was executed in phases:
1. **Phase 0**: Dependency updates to Scala 3 compatible versions
2. **Phase 1-3**: Automated and manual code migration
3. **Phase 4**: Validation and testing
4. **Phase 5**: Compilation error resolution (508 errors)
5. **Phase 6**: Monix to Cats Effect IO migration (~100 files)

For detailed technical information, see [Migration History](../MIGRATION_HISTORY.md).

## Alternatives Considered

### Stay on Scala 2.13 + JDK 17
- **Pros**: No migration effort, stable and known
- **Cons**: Limited future support, missing modern features, dependency obsolescence
- **Rejected**: Not sustainable long-term

### Scala 3 Only (Keep JDK 17)
- **Pros**: Smaller migration scope
- **Cons**: Misses JDK 21 improvements, shorter LTS support window
- **Rejected**: JDK 21 offers significant benefits worth the upgrade

### Gradual Migration with Cross-Compilation
- **Pros**: Lower risk, incremental approach
- **Cons**: Maintains complexity, delayed benefits, larger codebase
- **Rejected**: Clean break preferred for long-term maintainability

## Related Decisions

- Vendoring of scalanet library (no separate ADR, documented in migration history)
- Adoption of Apache Pekko over Akka (driven by licensing, not separate ADR)

## References

- [Scala 3 Language Reference](https://docs.scala-lang.org/scala3/reference/)
- [JDK 21 Release Notes](https://openjdk.org/projects/jdk/21/)
- [Cats Effect 3 Documentation](https://typelevel.org/cats-effect/)
- [Apache Pekko](https://pekko.apache.org/)
- [Migration History](../MIGRATION_HISTORY.md)

## Review and Update

This ADR should be reviewed when:
- Scala 3 releases a new LTS version
- JDK releases a new LTS version
- Major dependency security issues arise
- Performance or stability issues attributable to these choices
