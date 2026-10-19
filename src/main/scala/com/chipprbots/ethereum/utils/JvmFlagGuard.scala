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
  * other way (a hand-written `java -jar`, a systemd unit, a third-party image) and refuses to start them: a node that
  * may compute wrong results without any error must not run silently.
  *
  * Escape hatch, for an operator who accepts the risk (e.g. a JDK build known to carry the fix): the system property
  * `-Dfukuii.allow-c2-reduce-allocation-merges=true` or the environment variable
  * `FUKUII_ALLOW_C2_REDUCE_ALLOCATION_MERGES=true`. With it the node starts and logs the same message as an ERROR.
  */
object JvmFlagGuard extends Logger:

  val BugId = "JDK-8385993"
  val WorkaroundFlags = "-XX:+UnlockDiagnosticVMOptions -XX:-ReduceAllocationMerges"
  val AllowProperty = "fukuii.allow-c2-reduce-allocation-merges"
  val AllowEnv = "FUKUII_ALLOW_C2_REDUCE_ALLOCATION_MERGES"

  /** Exit status when the guard refuses to start (sysexits EX_CONFIG). */
  val RefuseExitCode = 78

  enum Verdict:
    case Safe, AllowedAtRisk, Refuse

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

  /** What to do: refuse an at-risk JVM unless the operator opted in. */
  def verdict(atRisk: Boolean, allowed: Boolean): Verdict =
    if !atRisk then Verdict.Safe
    else if allowed then Verdict.AllowedAtRisk
    else Verdict.Refuse

  /** Whether the escape hatch is set, from the system property or the environment variable. */
  def allowedBy(property: Option[String], env: Option[String]): Boolean =
    property.orElse(env).exists(_.trim.equalsIgnoreCase("true"))

  /** Reads the live JVM and returns the verdict, logging an ERROR when it is at risk. Never throws or exits. */
  def evaluate(): Verdict =
    val bean = Try(ManagementFactory.getPlatformMXBean(classOf[HotSpotDiagnosticMXBean])).toOption.flatMap(Option(_))
    def option(name: String): Option[String] =
      bean.flatMap(b => Try(b.getVMOption(name).getValue).toOption)
    val atRisk =
      isAtRisk(Runtime.version().feature(), option("ReduceAllocationMerges"), option("UnlockDiagnosticVMOptions"))
    val result = verdict(atRisk, allowedBy(sys.props.get(AllowProperty), sys.env.get(AllowEnv)))
    result match
      case Verdict.Safe => ()
      case Verdict.AllowedAtRisk =>
        log.error(
          "Unsafe JVM: jdk={} runs C2 with ReduceAllocationMerges enabled, which can silently miscompile field reads " +
            "({}, fixed in JDK 28). Starting anyway because {} is set. Results of this node may be wrong without " +
            "any error; restart with \"{}\" (see docs/runbooks/known-issues.md, Issue 16).",
          Runtime.version(),
          BugId,
          AllowProperty,
          WorkaroundFlags
        )
      case Verdict.Refuse =>
        log.error(
          "Refusing to start: jdk={} runs C2 with ReduceAllocationMerges enabled, which can silently miscompile " +
            "field reads ({}, fixed in JDK 28). Restart with the JVM flags \"{}\" (bin/fukuii and the Docker images " +
            "set them; see docs/runbooks/known-issues.md, Issue 16). To start anyway, accepting the risk, set " +
            "-D{}=true or {}=true.",
          Runtime.version(),
          BugId,
          WorkaroundFlags,
          AllowProperty,
          AllowEnv
        )
    result

  /** Startup entry point: [[evaluate]], then exit with [[RefuseExitCode]] when the verdict is Refuse. */
  def check(): Unit =
    if evaluate() == Verdict.Refuse then
      System.err.println(s"fukuii: refusing to start on an unsafe JVM ($BugId); see the log for details")
      sys.exit(RefuseExitCode)
