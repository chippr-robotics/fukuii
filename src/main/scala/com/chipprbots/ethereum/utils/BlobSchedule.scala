package com.chipprbots.ethereum.utils

import scala.jdk.CollectionConverters.*

import com.typesafe.config.Config as TypesafeConfig

import com.chipprbots.ethereum.domain.Timestamp

/** One fork's blob parameters (EIP-7840 `blobSchedule.<fork>`: `target`, `max`, `baseFeeUpdateFraction`), in BLOBS.
  *
  * The gas forms are precomputed: the resolver runs per transaction and per `BLOBBASEFEE` execution, so it must hand
  * back a stored instance and do no arithmetic.
  */
final case class BlobParams(target: Int, max: Int, baseFeeUpdateFraction: BigInt):
  val targetBlobGas: BigInt = BigInt(target) * BlobParams.GasPerBlob
  val maxBlobGas: BigInt = BigInt(max) * BlobParams.GasPerBlob

object BlobParams:

  /** EIP-4844 GAS_PER_BLOB (go-ethereum `params.BlobTxBlobGasPerBlob`), a protocol constant, not a schedule value. */
  val GasPerBlob: BigInt = BigInt(131072)

  /** go-ethereum `params.DefaultCancunBlobConfig` (EIP-4844). */
  val Cancun: BlobParams = BlobParams(3, 6, BigInt(3338477))

  /** go-ethereum `params.DefaultPragueBlobConfig` (EIP-7691). */
  val Prague: BlobParams = BlobParams(6, 9, BigInt(5007716))

  /** go-ethereum `params.DefaultBPO1BlobConfig` (EIP-7892, mainnet and Sepolia BPO1). */
  val Bpo1: BlobParams = BlobParams(10, 15, BigInt(8346193))

  /** go-ethereum `params.DefaultBPO2BlobConfig` (EIP-7892, mainnet and Sepolia BPO2). */
  val Bpo2: BlobParams = BlobParams(14, 21, BigInt(11684671))

  /** EIP-4844 fake_exponential(factor, numerator, denominator): factor * e^(numerator / denominator) by Taylor
    * expansion, integer division at every step (go-ethereum `eip4844.fakeExponential`).
    */
  def fakeExponential(factor: BigInt, numerator: BigInt, denominator: BigInt): BigInt =
    var i = BigInt(1)
    var output = BigInt(0)
    var numeratorAccum = factor * denominator
    while numeratorAccum > 0 do
      output += numeratorAccum
      numeratorAccum = (numeratorAccum * numerator) / (denominator * i)
      i += 1
    output / denominator

  /** EIP-4844 MIN_BASE_FEE_PER_BLOB_GAS (go-ethereum `params.BlobTxMinBlobGasprice`). */
  val MinBlobBaseFee: BigInt = BigInt(1)

/** A chain's blob schedule: EIP-7840 `blobSchedule`, with the forks go-ethereum's `params.BlobScheduleConfig` carries
  * and fukuii schedules (Cancun, Prague, BPO1, BPO2). Each entry applies from its fork's activation timestamp, which
  * lives in [[ForkTimestamps]], not here: tests rewrite `forkTimestamps` with `copy`, and the schedule follows.
  *
  * Named forks after Prague (Osaka, Amsterdam) carry no entry of their own and inherit the latest active one, as in
  * go-ethereum master ("Named forks such as Osaka or Amsterdam inherit the most recently configured BPO entry and must
  * not declare their own BlobConfig"). The loader accepts an `osaka` / `amsterdam` key, which EIP-7892's example and
  * Ephemery's genesis declare, only when it equals the entry it would inherit; see [[BlobSchedule.fromConfig]].
  *
  * BPO3..BPO5 are not supported: fukuii has no `bpo3-timestamp`..`bpo5-timestamp`, and the loader refuses both the
  * timestamps and the schedule keys rather than ignoring them.
  */
final case class BlobSchedule(
    cancun: Option[BlobParams] = None,
    prague: Option[BlobParams] = None,
    bpo1: Option[BlobParams] = None,
    bpo2: Option[BlobParams] = None
):

  /** The parameters in force at `timestamp`, go-ethereum `eip4844.latestBlobConfig`: the latest fork, in fork order
    * BPO2 > BPO1 > Prague > Cancun, that is active at `timestamp` and has an entry. `None` before the first blob fork
    * (and on every chain without one, ETC included).
    */
  def paramsAt(timestamp: Timestamp, forks: ForkTimestamps): Option[BlobParams] =
    def active(fork: Option[Long]): Boolean = fork.exists(ts => timestamp.isAtOrAfter(ts))
    if bpo2.isDefined && active(forks.bpo2Timestamp) then bpo2
    else if bpo1.isDefined && active(forks.bpo1Timestamp) then bpo1
    else if prague.isDefined && active(forks.pragueTimestamp) then prague
    else if cancun.isDefined && active(forks.cancunTimestamp) then cancun
    else None

  def isEmpty: Boolean = cancun.isEmpty && prague.isEmpty && bpo1.isEmpty && bpo2.isEmpty

object BlobSchedule:

  val Empty: BlobSchedule = BlobSchedule()

  /** go-ethereum's `Default{Cancun,Prague,BPO1,BPO2}BlobConfig`: the schedule `MainnetChainConfig` and
    * `SepoliaChainConfig` reference, and the values fukuii hard-coded before #1637.
    *
    * This is the default of [[BlockchainConfig.blobSchedule]] for configs built in code (tests, EEST fixture replays),
    * so their results do not move. Configs read from a chain conf do NOT get it implicitly: an ETH-family conf that
    * declares a blob fork must declare its `blob-schedule` ([[validate]]). Not go-ethereum's `DefaultBlobSchedule`,
    * which is Cancun and Prague only.
    */
  val GethDefaults: BlobSchedule = BlobSchedule(
    cancun = Some(BlobParams.Cancun),
    prague = Some(BlobParams.Prague),
    bpo1 = Some(BlobParams.Bpo1),
    bpo2 = Some(BlobParams.Bpo2)
  )

  /** Schedule keys in fork order. `osaka` and `amsterdam` are inheritance-checked only; BPO3+ are refused. */
  private val ScheduledKeys: List[String] = List("cancun", "prague", "bpo1", "bpo2")
  private val InheritingKeys: Set[String] = Set("osaka", "amsterdam")
  private val ParamKeys: Set[String] = Set("target", "max", "base-fee-update-fraction")

  /** Timestamp keys fukuii does not support. Declaring one is an error, not a silently ignored fork. */
  val UnsupportedTimestampKeys: List[String] = List("bpo3-timestamp", "bpo4-timestamp", "bpo5-timestamp")

  final case class InvalidBlobSchedule(msg: String) extends IllegalArgumentException(s"invalid blob-schedule: $msg")

  /** Reads the chain conf's optional `blob-schedule` block:
    * {{{
    *   blob-schedule {
    *     cancun { target = 3, max = 6, base-fee-update-fraction = 3338477 }
    *     prague { target = 6, max = 9, base-fee-update-fraction = 5007716 }
    *     bpo1   { target = 10, max = 15, base-fee-update-fraction = 8346193 }
    *   }
    * }}}
    * Absent ⇒ [[Empty]]. Values may be numbers or decimal strings (hive's adapter passes strings). Then validates the
    * schedule against the conf's fork timestamps; any problem throws [[InvalidBlobSchedule]].
    */
  def fromConfig(chainConfig: TypesafeConfig, forks: ForkTimestamps): BlobSchedule =
    UnsupportedTimestampKeys.find(chainConfig.hasPath).foreach { key =>
      throw InvalidBlobSchedule(s"$key is declared, but fukuii supports blob-parameter forks up to bpo2 only")
    }
    if !chainConfig.hasPath("blob-schedule") then
      validate(Empty, forks)
      Empty
    else
      val block = chainConfig.getConfig("blob-schedule")
      val keys = block.root().keySet().asScala.toSet
      val unknown = keys -- ScheduledKeys -- InheritingKeys
      if unknown.nonEmpty then
        throw InvalidBlobSchedule(
          s"unknown fork key(s) ${unknown.toSeq.sorted.mkString(", ")}; supported: " +
            (ScheduledKeys ++ InheritingKeys.toSeq.sorted).mkString(", ")
        )
      def entry(fork: String): Option[BlobParams] =
        Option.when(keys.contains(fork))(readParams(block.getConfig(fork), fork))
      val schedule = BlobSchedule(entry("cancun"), entry("prague"), entry("bpo1"), entry("bpo2"))
      validate(schedule, forks)
      // osaka / amsterdam: EIP-7892 lets a named fork add an entry, but go-ethereum master ignores one (its
      // BlobScheduleConfig has no such field). A value that differs from what the fork inherits would therefore be
      // honoured by fukuii and ignored by geth: refuse it instead of picking a side silently.
      def inherited(fork: String): Option[BlobParams] = fork match
        case "osaka"     => schedule.prague.orElse(schedule.cancun)
        case "amsterdam" => schedule.bpo2.orElse(schedule.bpo1).orElse(schedule.prague).orElse(schedule.cancun)
      InheritingKeys.filter(keys.contains).toSeq.sorted.foreach { fork =>
        val declared = readParams(block.getConfig(fork), fork)
        if !inherited(fork).contains(declared) then
          throw InvalidBlobSchedule(
            s"$fork declares $declared but inherits ${inherited(fork).getOrElse("nothing")}; a named fork's entry " +
              "must equal the one it inherits (go-ethereum ignores it). Declare a BPO fork to change blob parameters"
          )
      }
      schedule

  private def readParams(c: TypesafeConfig, fork: String): BlobParams =
    val unknown = c.root().keySet().asScala.toSet -- ParamKeys
    if unknown.nonEmpty then throw InvalidBlobSchedule(s"$fork: unknown key(s) ${unknown.toSeq.sorted.mkString(", ")}")
    def num(key: String): BigInt =
      if !c.hasPath(key) then throw InvalidBlobSchedule(s"$fork.$key is missing")
      val raw = c.getString(key).trim
      try BigInt(raw)
      catch case _: NumberFormatException => throw InvalidBlobSchedule(s"$fork.$key is not an integer: '$raw'")
    val target = num("target")
    val max = num("max")
    val fraction = num("base-fee-update-fraction")
    if !target.isValidInt || !max.isValidInt then throw InvalidBlobSchedule(s"$fork: target/max out of range")
    BlobParams(target.toInt, max.toInt, fraction)

  /** Fails loudly on a schedule fukuii would mis-execute:
    *   - per entry: target > 0, max ≥ target, baseFeeUpdateFraction > 0 (go-ethereum `BlobConfig.validate` allows a
    *     zero target or max; a blob fork with no blobs has no use, and max 0 divides by zero in EIP-7918's branch);
    *   - every blob fork with a declared timestamp (cancun, prague, bpo1, bpo2) has an entry (go-ethereum
    *     `CheckConfigForkOrder`: "missing entry for fork %q in blobSchedule");
    *   - the declared timestamps are ordered cancun ≤ prague ≤ osaka ≤ bpo1 ≤ bpo2 ≤ amsterdam (go-ethereum's fork
    *     order), so the fork-order resolution in [[BlobSchedule.paramsAt]] is also the time order.
    */
  def validate(schedule: BlobSchedule, forks: ForkTimestamps): Unit =
    List("cancun" -> schedule.cancun, "prague" -> schedule.prague, "bpo1" -> schedule.bpo1, "bpo2" -> schedule.bpo2)
      .foreach { case (fork, params) =>
        params.foreach { p =>
          if p.target <= 0 then throw InvalidBlobSchedule(s"$fork.target must be > 0 (got ${p.target})")
          if p.max < p.target then throw InvalidBlobSchedule(s"$fork.max (${p.max}) must be >= target (${p.target})")
          if p.baseFeeUpdateFraction <= 0 then
            throw InvalidBlobSchedule(s"$fork.base-fee-update-fraction must be > 0 (got ${p.baseFeeUpdateFraction})")
        }
      }
    List(
      ("cancun", forks.cancunTimestamp, schedule.cancun),
      ("prague", forks.pragueTimestamp, schedule.prague),
      ("bpo1", forks.bpo1Timestamp, schedule.bpo1),
      ("bpo2", forks.bpo2Timestamp, schedule.bpo2)
    ).foreach { case (fork, ts, params) =>
      if ts.isDefined && params.isEmpty then
        throw InvalidBlobSchedule(s"$fork-timestamp is declared but blob-schedule has no $fork entry")
    }
    val ordered = List(
      "cancun" -> forks.cancunTimestamp,
      "prague" -> forks.pragueTimestamp,
      "osaka" -> forks.osakaTimestamp,
      "bpo1" -> forks.bpo1Timestamp,
      "bpo2" -> forks.bpo2Timestamp,
      "amsterdam" -> forks.amsterdamTimestamp
    ).collect { case (fork, Some(ts)) => fork -> ts }
    ordered.zip(ordered.drop(1)).foreach { case ((a, ta), (b, tb)) =>
      if java.lang.Long.compareUnsigned(ta, tb) > 0 then
        throw InvalidBlobSchedule(s"$a-timestamp ($ta) is after $b-timestamp ($tb); blob forks must be ordered")
    }
