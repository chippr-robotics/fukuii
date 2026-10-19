# ADR-INF-007: JDK 25 Baseline and the C2 ReduceAllocationMerges Workaround

**Status**: Accepted

**Date**: 2026-10-19

**Deciders**: Chippr Robotics LLC Engineering Team (user decision 2026-10-19)

**Supersedes**: the JDK 21 minimum in [INF-001](INF-001-scala-3-migration.md). The Scala 3 decision in INF-001 stands.

## Context

INF-001 (October 2025) set JDK 21 as the minimum runtime. In April 2026 the build moved to JDK 25 (commit
`9df0da4b1`): CI, the Docker images and the getting-started docs all use 25, but the Debian package still accepted
JDK 21 and no record replaced INF-001's minimum.

JDK 25 is the current LTS. Its support window runs well past JDK 21's, and it brings runtime improvements fukuii
already uses (the AOT class cache, JEP 483/514, in the hive image).

JDK 25 also carries a HotSpot C2 miscompile: [JDK-8385993](https://bugs.openjdk.org/browse/JDK-8385993), with
duplicate JDK-8393049 reporting **silent wrong field values**. The `ReduceAllocationMerges` optimisation, on by default
since JDK 23, can mis-read a field through a phi that merges a fresh allocation with an existing object. The fix is in
JDK 28 only. We reproduced it on Temurin 25.0.4.1 and on Ubuntu OpenJDK 25.0.4.1: scaladoc crashed intermittently in
`sbt dist` with an impossible `NullPointerException`. With `-XX:+UnlockDiagnosticVMOptions -XX:-ReduceAllocationMerges`
the reproducer passed.

A consensus client must be byte-for-byte deterministic. A JIT that can return a wrong field value without raising
anything is not acceptable on any path that computes state roots, hashes, gas or validation results.

## Decision

1. **JDK 25 is the required runtime and build JDK everywhere**: CI, Docker images, the Debian package
   (`openjdk-25-jre-headless | openjdk-25-jre | java25-runtime-headless`; build-depends `openjdk-25-jdk-headless`),
   and the docs.
2. **Every fukuii JVM on JDK 23+ runs with** `-XX:+UnlockDiagnosticVMOptions -XX:-ReduceAllocationMerges`. Unlock must
   come first, because the flag is diagnostic. The flags are carried by each launch path:

   | Launch path | Mechanism |
   |-------------|-----------|
   | `bin/fukuii` (release zip, `sbt stage`) | `src/universal/conf/application.ini` (`-J` flags) |
   | Debian `/usr/bin/fukuii` | `debian/fukuii-wrapper.sh` (the package does not install the launcher's `application.ini`) |
   | Docker images, including composes that override the entrypoint to `java` | `ENV JDK_JAVA_OPTIONS` in every runtime stage and in the hive workflows' inline Dockerfiles |
   | `hive/fukuii/fukuii.sh` (and the AOT training run through it), `ops/start.sh`, `scripts/bench/blockchain-test-bench.sh` | explicit flags |
   | sbt (compile, scaladoc, `run`) | `.jvmopts` |
   | Forked test JVMs (all configs) | `build.sbt` `Test / javaOptions` |

   `JDK_JAVA_OPTIONS` was chosen over `JAVA_TOOL_OPTIONS` because ops compose files already use `JAVA_TOOL_OPTIONS` for
   heap tuning; a second variable cannot be clobbered by them. It is read only by the `java` launcher, which is the only
   way these images start a JVM.
3. **A startup guard logs loudly.** `JvmFlagGuard` (called from `Fukuii.main` and the `runtime` launcher) reads the
   live JVM through `HotSpotDiagnosticMXBean`. On JDK 23+ with the optimisation on, it logs an ERROR naming the bug and
   the flags. Because the option is diagnostic, HotSpot hides it unless diagnostics are unlocked, so "option hidden and
   `UnlockDiagnosticVMOptions=false`" counts as on. On a non-HotSpot JVM, or one without the flag, it stays quiet. It
   does not refuse to start; making it fatal (with an explicit escape hatch property) is a separate decision.
4. **Test and sbt JVMs tolerate JDK 21** with `-XX:+IgnoreUnrecognizedVMOptions` in front, so a developer on 21 can
   still build. The production launchers do not: with this ADR, JDK 21 is unsupported at runtime, and a JVM that does
   not know the flag fails at startup with an explicit error.

## Consequences

- An operator who starts the jar by hand (`java -jar`, their own systemd unit or image) must add the flags.
  [known-issues Issue 16](../../runbooks/known-issues.md#issue-16-jdk-25-c2-reduceallocationmerges-workaround)
  documents this, and the startup ERROR catches the omission.
- `java` prints `NOTE: Picked up JDK_JAVA_OPTIONS: ...` on stderr in the containers. Expected; nothing parses it.
- Setting `JDK_JAVA_OPTIONS` in a compose file replaces the image's value, so it must include both flags.
- Running containers are protected only after their image is rebuilt and pulled.
- Disabling `ReduceAllocationMerges` returns C2 to its JDK 22 behaviour for these merges. The cost has not been
  measured on fukuii workloads.
- `.specify/memory/constitution.md` still describes the runtime separately; amending it is a separate decision.

## Removal

When every supported runtime JDK carries the JDK-8385993 fix (JDK 28, or a 25.0.x backport, checked against the
OpenJDK backport record), remove the flags from every location in the table above and retire the guard (or narrow
`JvmFlagGuard.isAtRisk` to the affected versions first).

## References

- JDK-8385993: C2 ReduceAllocationMerges miscompile; JDK-8393049 (duplicate, silent wrong values)
- `.jvmopts` (PR #1642): the first workaround, for the sbt JVM
- [INF-001: Migration to Scala 3 and JDK 21](INF-001-scala-3-migration.md)
- [INF-005: Docker Deployment Strategy](INF-005-docker-deployment-strategy.md)
