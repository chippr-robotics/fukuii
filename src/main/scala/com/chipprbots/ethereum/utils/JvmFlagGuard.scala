package com.chipprbots.ethereum.utils

import java.lang.management.ManagementFactory

import scala.util.Try

import com.sun.management.HotSpotDiagnosticMXBean

/** Startup check for HotSpot C2 bugs that need a JVM flag workaround.
  *
  * JDK-8385993 (dup JDK-8393049): on JDK 23+ the C2 optimisation `ReduceAllocationMerges` can miscompile a field read
  * through a phi that merges a fresh allocation with an existing object, producing a null or a wrong field value with
  * no exception. It was reproduced here on Temurin and Ubuntu OpenJDK 25.0.4.1 and is fixed only in JDK 28. A client
  * whose results must be byte-for-byte deterministic cannot run with it, so every launcher fukuii ships passes
  * `-XX:+UnlockDiagnosticVMOptions -XX:-ReduceAllocationMerges`. This guard catches the JVMs that were started some
  * other way (a hand-written `java -jar`, a systemd unit, a third-party image) and says so loudly at startup.
  *
  * It only logs; it does not refuse to start.
  */
object JvmFlagGuard extends Logger:

  val BugId = "JDK-8385993"
  val WorkaroundFlags = "-XX:+UnlockDiagnosticVMOptions -XX:-ReduceAllocationMerges"

  /** First JDK feature release that carries the ReduceAllocationMerges code path. */
  private val FirstAffectedFeature = 23

  /** Whether this JVM runs C2 with ReduceAllocationMerges enabled.
    *
    * `ReduceAllocationMerges` is a diagnostic flag, and HotSpot hides diagnostic flags from the management API unless
    * `UnlockDiagnosticVMOptions` is on. So a JVM started with no flags at all — the case that matters — reports the
    * option as absent, and has to be recognised by diagnostics being locked.
    *
    * @param featureVersion
    *   `Runtime.version().feature()`
    * @param reduceAllocationMerges
    *   the option's value, or None if the management API does not expose it
    * @param diagnosticsUnlocked
    *   the value of `UnlockDiagnosticVMOptions`, or None if the JVM has no HotSpot diagnostic bean (not HotSpot)
    */
  def isAtRisk(
      featureVersion: Int,
      reduceAllocationMerges: Option[String],
      diagnosticsUnlocked: Option[String]
  ): Boolean =
    if featureVersion < FirstAffectedFeature then false
    else
      reduceAllocationMerges match
        case Some(value) => value == "true"
        // Absent and diagnostics locked: hidden, so it exists at its default, which is on.
        // Absent and diagnostics unlocked: this JVM has no such flag (no C2, or a JDK that removed it).
        // No diagnostic bean at all (None): not HotSpot.
        case None => diagnosticsUnlocked.contains("false")

  /** Reads the live JVM and logs an ERROR when [[isAtRisk]] holds. Never throws. */
  def check(): Unit =
    val bean = Try(ManagementFactory.getPlatformMXBean(classOf[HotSpotDiagnosticMXBean])).toOption.flatMap(Option(_))
    def option(name: String): Option[String] =
      bean.flatMap(b => Try(b.getVMOption(name).getValue).toOption)
    val feature = Runtime.version().feature()
    if isAtRisk(feature, option("ReduceAllocationMerges"), option("UnlockDiagnosticVMOptions")) then
      log.error(
        "Unsafe JVM: jdk={} runs C2 with ReduceAllocationMerges enabled, which can silently miscompile field reads " +
          "({}, fixed in JDK 28). Results of this node may be wrong without any error. Restart with the JVM flags " +
          "\"{}\" (bin/fukuii and the Docker images set them; see docs/runbooks/known-issues.md, Issue 16).",
        Runtime.version(),
        BugId,
        WorkaroundFlags
      )
