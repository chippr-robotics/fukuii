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

  /** go-ethereum `params.DefaultBPO3BlobConfig` (EIP-7892; execution-specs' BPO3 test fork). No public network
    * schedules it, so it is not in [[BlobSchedule.GethDefaults]].
    */
  val Bpo3: BlobParams = BlobParams(21, 32, BigInt(20609697))

  /** go-ethereum `params.DefaultBPO4BlobConfig` (EIP-7892; execution-specs' BPO4 test fork, which LOWERS the target and
    * max again). Not in [[BlobSchedule.GethDefaults]].
    */
  val Bpo4: BlobParams = BlobParams(14, 21, BigInt(13739630))

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
  * (Cancun, Prague, BPO1..BPO5). Each entry applies from its fork's activation timestamp, which lives in
  * [[ForkTimestamps]], not here: tests rewrite `forkTimestamps` with `copy`, and the schedule follows.
  *
  * Named forks after Prague (Osaka, Amsterdam) carry no entry of their own and inherit the latest active one, as in
  * go-ethereum master ("Named forks such as Osaka or Amsterdam inherit the most recently configured BPO entry and must
  * not declare their own BlobConfig"). The loader accepts an `osaka` / `amsterdam` key, which EIP-7892's example and
  * Ephemery's genesis declare, only when it equals the entry it would inherit; see [[BlobSchedule.fromConfig]].
  *
  * The BPO forks are go-ethereum's five (`BPO1`..`BPO5`, the most its chain config can schedule); [[bpos]] is the same
  * five entries in fork order, and every rule below treats them alike.
  */
final case class BlobSchedule(
    cancun: Option[BlobParams] = None,
    prague: Option[BlobParams] = None,
    bpo1: Option[BlobParams] = None,
    bpo2: Option[BlobParams] = None,
    bpo3: Option[BlobParams] = None,
    bpo4: Option[BlobParams] = None,
    bpo5: Option[BlobParams] = None
):

  /** BPO1..BPO5's entries, in fork order (index 0 is BPO1); always [[ForkTimestamps.BpoForkCount]] long. */
  def bpos: List[Option[BlobParams]] = List(bpo1, bpo2, bpo3, bpo4, bpo5)

  /** The parameters in force at `timestamp`, go-ethereum `eip4844.latestBlobConfig`: the latest fork, in fork order
    * BPO5 > .. > BPO1 > Prague > Cancun, that is active at `timestamp` and has an entry. `None` before the first blob
    * fork (and on every chain without one, ETC included).
    */
  def paramsAt(timestamp: Timestamp, forks: ForkTimestamps): Option[BlobParams] =
    paramsAt(timestamp, forks.cancunTimestamp, forks.pragueTimestamp, forks.bpoTimestamps)

  /** [[paramsAt]] over the blob-fork activation timestamps directly, for the EVM's config subset. `bpoTimestamps` is
    * BPO1.. in fork order, as [[ForkTimestamps.bpoTimestamps]]; a missing tail means "not scheduled".
    */
  def paramsAt(
      timestamp: Timestamp,
      cancunTimestamp: Option[Long],
      pragueTimestamp: Option[Long],
      bpoTimestamps: Seq[Option[Long]]
  ): Option[BlobParams] =
    def active(fork: Option[Long]): Boolean = fork.exists(ts => timestamp.isAtOrAfter(ts))
    val latestBpo = bpos
      .zip(bpoTimestamps)
      .reverseIterator
      .collectFirst { case (Some(params), ts) if active(ts) => params }
    latestBpo
      .orElse(prague.filter(_ => active(pragueTimestamp)))
      .orElse(cancun.filter(_ => active(cancunTimestamp)))

  def isEmpty: Boolean = cancun.isEmpty && prague.isEmpty && bpos.forall(_.isEmpty)

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

  /** The BPO fork names, `bpo1`..`bpo5`, in fork order. */
  val BpoKeys: List[String] = (1 to ForkTimestamps.BpoForkCount).map(n => s"bpo$n").toList

  /** Schedule keys in fork order. `osaka` and `amsterdam` are inheritance-checked only. */
  private val ScheduledKeys: List[String] = List("cancun", "prague") ++ BpoKeys
  private val InheritingKeys: Set[String] = Set("osaka", "amsterdam")
  private val ParamKeys: Set[String] = Set("target", "max", "base-fee-update-fraction")

  /** A `bpoN-timestamp` chain-conf key. One beyond bpo5 is an error, not a silently ignored fork. */
  private val BpoTimestampKey = "bpo(\\d+)-timestamp".r

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
    chainConfig.root().keySet().asScala.toSeq.sorted.foreach {
      case key @ BpoTimestampKey(n) if BigInt(n) < 1 || BigInt(n) > ForkTimestamps.BpoForkCount =>
        throw InvalidBlobSchedule(
          s"$key is declared, but blob-parameter-only forks are bpo1..bpo${ForkTimestamps.BpoForkCount} " +
            "(go-ethereum's BPO1Time..BPO5Time)"
        )
      case _ => ()
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
      val schedule = BlobSchedule(
        cancun = entry("cancun"),
        prague = entry("prague"),
        bpo1 = entry("bpo1"),
        bpo2 = entry("bpo2"),
        bpo3 = entry("bpo3"),
        bpo4 = entry("bpo4"),
        bpo5 = entry("bpo5")
      )
      validate(schedule, forks)
      // osaka / amsterdam: EIP-7892 lets a named fork add an entry, but go-ethereum master ignores one (its
      // BlobScheduleConfig has no such field). A value that differs from what the fork inherits would therefore be
      // honoured by fukuii and ignored by geth: refuse it instead of picking a side silently. Amsterdam inherits from
      // BPO2 at most: go-ethereum's fork order puts BPO3..BPO5 after Amsterdam (validate).
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
    *   - every blob fork with a declared timestamp (cancun, prague, bpo1..bpo5) has an entry (go-ethereum
    *     `CheckConfigForkOrder`: "missing entry for fork %q in blobSchedule");
    *   - the declared timestamps are ordered cancun ≤ prague ≤ osaka ≤ bpo1 ≤ bpo2 ≤ amsterdam ≤ bpo3 ≤ bpo4 ≤ bpo5
    *     (go-ethereum `CheckConfigForkOrder`'s list, which puts BPO3..BPO5 after Amsterdam; undeclared forks are
    *     skipped), so the fork-order resolution in [[BlobSchedule.paramsAt]] is also the time order.
    */
  def validate(schedule: BlobSchedule, forks: ForkTimestamps): Unit =
    val blobForks: List[(String, Option[Long], Option[BlobParams])] =
      ("cancun", forks.cancunTimestamp, schedule.cancun) ::
        ("prague", forks.pragueTimestamp, schedule.prague) ::
        BpoKeys.lazyZip(forks.bpoTimestamps).lazyZip(schedule.bpos).toList
    blobForks
      .map((fork, _, params) => fork -> params)
      .foreach { case (fork, params) =>
        params.foreach { p =>
          if p.target <= 0 then throw InvalidBlobSchedule(s"$fork.target must be > 0 (got ${p.target})")
          if p.max < p.target then throw InvalidBlobSchedule(s"$fork.max (${p.max}) must be >= target (${p.target})")
          if p.baseFeeUpdateFraction <= 0 then
            throw InvalidBlobSchedule(s"$fork.base-fee-update-fraction must be > 0 (got ${p.baseFeeUpdateFraction})")
        }
      }
    blobForks.foreach { case (fork, ts, params) =>
      if ts.isDefined && params.isEmpty then
        throw InvalidBlobSchedule(s"$fork-timestamp is declared but blob-schedule has no $fork entry")
    }
    val ordered = List(
      "cancun" -> forks.cancunTimestamp,
      "prague" -> forks.pragueTimestamp,
      "osaka" -> forks.osakaTimestamp,
      "bpo1" -> forks.bpo1Timestamp,
      "bpo2" -> forks.bpo2Timestamp,
      "amsterdam" -> forks.amsterdamTimestamp,
      "bpo3" -> forks.bpo3Timestamp,
      "bpo4" -> forks.bpo4Timestamp,
      "bpo5" -> forks.bpo5Timestamp
    ).collect { case (fork, Some(ts)) => fork -> ts }
    ordered.zip(ordered.drop(1)).foreach { case ((a, ta), (b, tb)) =>
      if java.lang.Long.compareUnsigned(ta, tb) > 0 then
        throw InvalidBlobSchedule(s"$a-timestamp ($ta) is after $b-timestamp ($tb); blob forks must be ordered")
    }
