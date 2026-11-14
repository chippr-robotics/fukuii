package com.chipprbots.ethereum.console

import java.time.Duration

import com.typesafe.config.Config as TypesafeConfig
import com.typesafe.config.ConfigException

/** Where each alert begins (spec 029 FR-046): one field for each key of `fukuii.tui.alerts`. The defaults are spec
  * 027's values, so a node with no `alerts` block warns exactly where it did; the last four are new with spec 029.
  *
  * @param zeroPeersAfter
  *   no handshaked peer for longer than this is an error
  * @param lowPeersFraction
  *   fewer peers than this share of the peer limit is a warning
  * @param stallAfter
  *   blocks remain and the head has not moved for this long: the sync is stalled
  * @param snapSampleMaxAge
  *   a SNAP sample older than this while SNAP runs is a warning
  * @param diskWarnBytes
  *   disk free below this, on the datadir's file system, is a warning
  * @param diskErrorBytes
  *   disk free below this is an error
  * @param clSilentAfter
  *   no `forkchoiceUpdated` for longer than this: the consensus client is disconnected (ETH)
  * @param clFirstCallGrace
  *   how long after its start a node may be without its first `forkchoiceUpdated` before a warning (ETH)
  * @param newPayloadSilentAfter
  *   no `newPayload` for longer than this, while peers are ahead, is a warning (ETH)
  * @param hashrateZeroAfter
  *   a reported hashrate of 0 for longer than this, mining on and the DAG loaded, is a warning (ETC)
  * @param reorgDepth
  *   a reorg at least this deep is a warning
  * @param heapWarnFraction
  *   the heap never under this share of its limit for a minute is a warning
  * @param gcShareWarn
  *   more than this share of the last minute spent in garbage-collection pauses is a warning
  * @param rpcErrorRateWarn
  *   more than this share of the last minute's JSON-RPC calls failing, with at least [[AlertThresholds.RpcMinCalls]] of
  *   them, is a warning
  * @param writeStall
  *   whether the database refusing writes is a warning
  */
final case class AlertThresholds(
    zeroPeersAfter: Duration = Duration.ofSeconds(30),
    lowPeersFraction: Double = 0.25,
    stallAfter: Duration = Duration.ofSeconds(120),
    snapSampleMaxAge: Duration = Duration.ofSeconds(90),
    diskWarnBytes: Long = 20 * AlertThresholds.GiB,
    diskErrorBytes: Long = 5 * AlertThresholds.GiB,
    clSilentAfter: Duration = Duration.ofSeconds(60),
    clFirstCallGrace: Duration = Duration.ofSeconds(60),
    newPayloadSilentAfter: Duration = Duration.ofMinutes(5),
    hashrateZeroAfter: Duration = Duration.ofMinutes(10),
    reorgDepth: Int = 3,
    heapWarnFraction: Double = 0.90,
    gcShareWarn: Double = 0.20,
    rpcErrorRateWarn: Double = 0.10,
    writeStall: Boolean = true
)

object AlertThresholds:

  val GiB: Long = 1024L * 1024 * 1024

  /** The fewest JSON-RPC calls in a minute for their error rate to be a warning: three failures of four calls are not a
    * failing server.
    */
  val RpcMinCalls: Long = 20L

  /** Spec 027's thresholds, and spec 029's defaults for the new ones. */
  val Default: AlertThresholds = AlertThresholds()

  /** The keys of `fukuii.tui.alerts`, as `conf/base/tui.conf` and contracts/operator.md list them. */
  val Keys: Seq[String] = Seq(
    "zero-peers-after",
    "low-peers-fraction",
    "stall-after",
    "snap-sample-max-age",
    "disk-warn",
    "disk-error",
    "cl-silent-after",
    "cl-first-call-grace",
    "new-payload-silent-after",
    "hashrate-zero-after",
    "reorg-depth",
    "heap-warn-fraction",
    "gc-share-warn",
    "rpc-error-rate-warn",
    "write-stall"
  )

  /** The thresholds in a `fukuii.tui` block. An absent key keeps its default. An invalid value, or a key under `alerts`
    * that is none of [[Keys]], fails with a [[ConfigException.BadValue]] that names the key: a threshold misspelt is
    * one the operator believes is set.
    */
  def fromConfig(tui: TypesafeConfig): AlertThresholds =
    def bad(key: String, why: String): Nothing =
      throw new ConfigException.BadValue(tui.origin(), s"fukuii.tui.alerts.$key", why)
    def read[A](key: String, default: A)(get: String => A)(check: A => Option[String]): A =
      val path = s"alerts.$key"
      // A key set to null is not a key left out: it is a threshold the operator believes is set.
      if tui.hasPathOrNull(path) && tui.getIsNull(path) then bad(key, "null is not a threshold")
      if !tui.hasPath(path) then default
      else
        val value =
          try get(path)
          catch case e: ConfigException => bad(key, Option(e.getMessage).getOrElse("not a valid value"))
        check(value).foreach(bad(key, _))
        value
    def duration(key: String, default: Duration): Duration =
      read(key, default)(tui.getDuration)(d =>
        Option.when(d.isZero || d.isNegative)(s"$d is not a length of time above 0")
      )
    def fraction(key: String, default: Double): Double =
      read(key, default)(tui.getDouble)(v => Option.when(!(v > 0.0 && v <= 1.0))(s"$v is not above 0 and at most 1"))
    def bytes(key: String, default: Long): Long =
      read(key, default)(p => tui.getBytes(p).longValue)(v => Option.when(v <= 0)(s"$v bytes is not above 0"))
    if tui.hasPath("alerts") then
      if tui.getValue("alerts").valueType() != com.typesafe.config.ConfigValueType.OBJECT then
        throw new ConfigException.BadValue(
          tui.origin(),
          "fukuii.tui.alerts",
          s"it must be a block of thresholds: ${Keys.mkString(", ")}"
        )
      val known = Keys.toSet
      import scala.jdk.CollectionConverters.*
      tui.getConfig("alerts").root().keySet().asScala.toSeq.sorted.find(!known(_)).foreach { unknown =>
        bad(unknown, s"there is no such threshold; the thresholds are ${Keys.mkString(", ")}")
      }
    val d = Default
    val thresholds = AlertThresholds(
      zeroPeersAfter = duration("zero-peers-after", d.zeroPeersAfter),
      lowPeersFraction = fraction("low-peers-fraction", d.lowPeersFraction),
      stallAfter = duration("stall-after", d.stallAfter),
      snapSampleMaxAge = duration("snap-sample-max-age", d.snapSampleMaxAge),
      diskWarnBytes = bytes("disk-warn", d.diskWarnBytes),
      diskErrorBytes = bytes("disk-error", d.diskErrorBytes),
      clSilentAfter = duration("cl-silent-after", d.clSilentAfter),
      clFirstCallGrace = duration("cl-first-call-grace", d.clFirstCallGrace),
      newPayloadSilentAfter = duration("new-payload-silent-after", d.newPayloadSilentAfter),
      hashrateZeroAfter = duration("hashrate-zero-after", d.hashrateZeroAfter),
      reorgDepth = read("reorg-depth", d.reorgDepth)(tui.getInt)(v => Option.when(v < 1)(s"$v is below 1")),
      heapWarnFraction = fraction("heap-warn-fraction", d.heapWarnFraction),
      gcShareWarn = fraction("gc-share-warn", d.gcShareWarn),
      rpcErrorRateWarn = fraction("rpc-error-rate-warn", d.rpcErrorRateWarn),
      writeStall = read("write-stall", d.writeStall)(tui.getBoolean)(_ => None)
    )
    // An error that begins above its warning would never be preceded by one.
    if thresholds.diskErrorBytes > thresholds.diskWarnBytes then
      bad(
        "disk-error",
        s"${thresholds.diskErrorBytes} bytes is above disk-warn, ${thresholds.diskWarnBytes} bytes: the error must begin at or below the warning"
      )
    thresholds
