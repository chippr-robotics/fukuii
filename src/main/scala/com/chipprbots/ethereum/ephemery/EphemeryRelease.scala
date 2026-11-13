package com.chipprbots.ethereum.ephemery

import java.util.regex.Pattern

import org.apache.pekko.util.ByteString

import scala.util.Try

import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigIncludeContext
import com.typesafe.config.ConfigIncluder
import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigSyntax
import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.ephemery.ChainConfRenderer.Params
import com.chipprbots.ethereum.ephemery.ReleaseRefusal.CannotRepresent
import com.chipprbots.ethereum.ephemery.ReleaseRefusal.HashMismatch
import com.chipprbots.ethereum.ephemery.ReleaseRefusal.Inconsistent
import com.chipprbots.ethereum.ephemery.StrictJson.*
import com.chipprbots.ethereum.utils.BlobSchedule
import com.chipprbots.ethereum.utils.BlockchainConfig

/** The one implementation of the Ephemery release rules (spec 028, research R1 and R2).
  *
  * `validate` takes the execution-layer members of a release and either refuses it, with a case of [[ReleaseRefusal]],
  * or returns everything derived from it: the re-encoded genesis, the chain configuration, the iteration's reset time
  * and its genesis block hash. It is what `fukuii cli ephemery-fetch` runs, and what the node runs at startup, so the
  * two can never disagree. The rules are those `scripts/ephemery/fetch-genesis.sh` carried (R1 to R11, with its
  * wording), and the new N1 to N4.
  *
  * Pure: no `Config`, no clock, no file, no network.
  */
object EphemeryRelease:

  /** Chain id of iteration N is `IterationBase + N`. */
  val IterationBase: Int = 39438000

  /** The seconds between resets, when retention.vars does not say otherwise. */
  val DefaultResetInterval: Long = 2419200L

  /** The file name the rendered chain configuration includes its genesis from. */
  val GenesisFileName: String = "ephemery-genesis.json"

  val ChainConfFileName: String = "ephemery-chain.conf"

  private val MaxResetInterval: BigInt = BigInt(Int.MaxValue)

  /** What a valid release comes to.
    *
    * @param params
    *   the facts the chain configuration states
    * @param genesisHash
    *   the computed genesis block hash; it equals the published one (N3)
    * @param warnings
    *   things worth saying that do not refuse the release
    */
  final case class ValidRelease(
      params: Params,
      iteration: Int,
      resetTime: Long,
      encodedGenesis: String,
      chainConf: String,
      genesisHash: ByteString,
      bootEnrs: Seq[String],
      warnings: Seq[String]
  ):
    def release: String = params.release
    def chainId: BigInt = params.chainId
    def genesisTimestamp: Long = params.genesisTimestamp
    def resetInterval: Long = params.resetInterval
    def bootstrapNodes: Seq[String] = params.bootnodes
    def genesisHashHex: String = "0x" + Hex.toHexString(genesisHash.toArray)

  private type R[A] = Either[ReleaseRefusal, A]

  private val BlockKeys: List[String] = List(
    "homesteadBlock",
    "eip150Block",
    "eip155Block",
    "eip158Block",
    "byzantiumBlock",
    "constantinopleBlock",
    "petersburgBlock",
    "istanbulBlock",
    "muirGlacierBlock",
    "berlinBlock",
    "londonBlock",
    "arrowGlacierBlock",
    "grayGlacierBlock",
    "mergeNetsplitBlock"
  )

  /** In the order of the forks. */
  private val TimeKeys: List[String] =
    List("shanghaiTime", "cancunTime", "pragueTime", "osakaTime", "bpo1Time", "bpo2Time", "amsterdamTime")

  private val OtherKeys: Set[String] =
    Set(
      "chainId",
      "terminalTotalDifficulty",
      "terminalTotalDifficultyPassed",
      "blobSchedule",
      "depositContractAddress",
      "ethash"
    )

  private val BlobParamKeys: Set[String] = Set("target", "max", "baseFeeUpdateFraction")

  private val DecimalDigits = Pattern.compile("[0-9]{1,80}")
  private val DepositAddress = Pattern.compile("0x[0-9a-fA-F]{40}")
  private val HashText = Pattern.compile("0x[0-9a-fA-F]{64}")

  private def cannot(feature: String, detail: String): Left[ReleaseRefusal, Nothing] =
    Left(CannotRepresent(feature, detail))

  private def inconsistent(detail: String): Left[ReleaseRefusal, Nothing] = Left(Inconsistent(detail))

  /** Applies R1 to R11 and N1 to N4 to `inputs`. The rules run in the order of `fetch-genesis.sh`, then the new ones;
    * N3 runs last, because it needs the rendered configuration and the encoded genesis.
    */
  def validate(inputs: ReleaseInputs): Either[ReleaseRefusal, ValidRelease] =
    for
      retention <- ReleaseInputs.parseRetention(inputs.retentionVars).left.map[ReleaseRefusal](Inconsistent(_))
      genesis <- parseObject(ReleaseInputs.GenesisFile, inputs.genesisJson)
      config <- configOf(genesis)
      chainId <- chainIdOf(config)
      _ <- retentionChainId(retention, chainId)
      iteration <- iterationOf(chainId, retention)
      _ <- configKeys(config)
      _ <- blockForks(config)
      _ <- terminalDifficulty(config)
      times <- forkTimes(config)
      deposit <- depositContract(config)
      blobs <- blobSchedule(config, times)
      encoded <- GenesisJsonEncoder.encode(genesis)
      _ <- forkOrder(times, encoded.timestamp)
      _ <- retentionTimestamp(retention, encoded.timestamp)
      _ <- chainspecTimestamp(inputs.chainspecJson, encoded)
      interval <- resetInterval(retention)
      published <- publishedHash(inputs.publishedHashText)
      nodes <- bootnodes(inputs.enodesText)
      enrs <- bootEnrs(inputs.bootEnrText)
      params = Params(
        release = s"ephemery-$iteration",
        chainId = chainId,
        genesisTimestamp = encoded.timestamp,
        resetInterval = interval,
        shanghai = times.shanghai,
        cancun = times.cancun,
        prague = times.prague,
        osaka = times.osaka,
        bpo1 = times.bpo1,
        bpo2 = times.bpo2,
        amsterdam = times.amsterdam,
        blobSchedule = blobs,
        depositContract = deposit,
        bootnodes = nodes,
        publishedHash = published
      )
      conf = ChainConfRenderer.render(params)
      hash <- genesisHash(conf, encoded.json)
      _ <- Either.cond(
        "0x" + Hex.toHexString(hash.toArray) == published,
        (),
        HashMismatch(published, "0x" + Hex.toHexString(hash.toArray))
      )
    yield ValidRelease(
      params = params,
      iteration = iteration,
      resetTime = encoded.timestamp + interval,
      encodedGenesis = encoded.json,
      chainConf = conf,
      genesisHash = hash,
      bootEnrs = enrs,
      warnings =
        Option.when(nodes.isEmpty)("release ships no EL enodes (enodes.txt); bootstrap-nodes will be empty").toList ++
          Option.when(!encoded.accounts.contains(GenesisJsonEncoder.normaliseAddress(deposit)))(
            s"the deposit contract $deposit is not in the genesis alloc"
          )
    )

  private def parseObject(name: String, text: String): R[JObj] =
    StrictJson.parse(text) match
      case Left(e)        => inconsistent(s"$name is not valid JSON: $e")
      case Right(o: JObj) => Right(o)
      case Right(_)       => inconsistent(s"$name is not a JSON object")

  private def configOf(genesis: JObj): R[JObj] = genesis.get("config") match
    case Some(c: JObj) => Right(c)
    case _             => inconsistent("genesis.json has no config.chainId")

  /** R1. */
  private def chainIdOf(config: JObj): R[BigInt] = config.get("chainId") match
    case None | Some(JNull) | Some(JBool(false))                => inconsistent("genesis.json has no config.chainId")
    case Some(n: JNum) if n.isInteger && !n.raw.startsWith("-") => Right(BigInt(n.raw))
    case Some(other) => inconsistent(s"config.chainId is not an integer: ${show(other)}")

  /** R2, the first half: retention.vars CHAIN_ID names the same chain. */
  private def retentionChainId(retention: Map[String, String], chainId: BigInt): R[Unit] =
    retention.get("CHAIN_ID") match
      case Some(v) if !(DecimalDigits.matcher(v).matches() && BigInt(v) == chainId) =>
        inconsistent(s"retention.vars CHAIN_ID=$v disagrees with genesis.json chainId=$chainId")
      case Some(_) => Right(())
      case None    => inconsistent("retention.vars has no CHAIN_ID")

  /** R2, the second half, and N2: the iteration is `chainId - 39438000`, and ITERATION_NUMBER and ITERATION_RELEASE,
    * when present, say the same.
    */
  private def iterationOf(chainId: BigInt, retention: Map[String, String]): R[Int] =
    val n = chainId - IterationBase
    if n < 0 || n > Int.MaxValue then
      inconsistent(s"config.chainId $chainId is not an Ephemery chain id (39438000 plus the iteration)")
    else
      val iteration = n.toInt
      retention.get("ITERATION_NUMBER") match
        case Some(v) if !(DecimalDigits.matcher(v).matches() && BigInt(v) == n) =>
          inconsistent(
            s"retention.vars ITERATION_NUMBER=$v disagrees with chainId $chainId, which is iteration $iteration"
          )
        case None => inconsistent("retention.vars has no ITERATION_NUMBER")
        case _ =>
          retention.get("ITERATION_RELEASE") match
            case Some(v) if v != s"ephemery-$iteration" =>
              inconsistent(
                s"retention.vars ITERATION_RELEASE=$v disagrees with chainId $chainId, which is ephemery-$iteration"
              )
            case _ => Right(iteration)

  /** R3. */
  private def configKeys(config: JObj): R[Unit] =
    val known = BlockKeys.toSet ++ TimeKeys ++ OtherKeys
    val unknown = config.keys.filterNot(known).sorted
    if unknown.isEmpty then Right(())
    else cannot("a config key", s"genesis config declares keys fukuii cannot map: ${unknown.mkString(",")}")

  private def isZero(js: Js): Boolean = js match
    case n: JNum => n.raw == "0"
    case _       => false

  /** R4. */
  private def blockForks(config: JObj): R[Unit] =
    val nonZero = BlockKeys.filter(k => config.get(k).exists(v => v != JNull && !isZero(v)))
    if nonZero.isEmpty then Right(())
    else
      cannot(
        "a block fork after genesis",
        s"block forks after genesis are not supported for Ephemery (all must be 0): ${nonZero.mkString(",")}"
      )

  /** R5, and TTDPassed when the release states it. */
  private def terminalDifficulty(config: JObj): R[Unit] =
    val zero = config.get("terminalTotalDifficulty") match
      case Some(n: JNum) => n.raw == "0"
      case Some(JStr(s)) => s == "0"
      case _             => false
    if !zero then
      cannot("a terminal total difficulty", "terminalTotalDifficulty must be 0 (Ephemery is merged at genesis)")
    else
      config.get("terminalTotalDifficultyPassed") match
        case None | Some(JNull) | Some(JBool(true)) => Right(())
        case Some(other) =>
          cannot(
            "a terminal total difficulty",
            s"terminalTotalDifficultyPassed must be true when present (Ephemery is merged at genesis): ${show(other)}"
          )

  final private case class ForkTimes(
      shanghai: Long,
      cancun: Long,
      prague: Long,
      osaka: Option[Long],
      bpo1: Option[Long],
      bpo2: Option[Long],
      amsterdam: Option[Long]
  ):
    def named: List[(String, Long)] =
      List("shanghaiTime" -> shanghai, "cancunTime" -> cancun, "pragueTime" -> prague) ++
        osaka.map("osakaTime" -> _) ++ bpo1.map("bpo1Time" -> _) ++ bpo2.map("bpo2Time" -> _) ++
        amsterdam.map("amsterdamTime" -> _)
    def declared(fork: String): Boolean = named.exists(_._1 == s"${fork}Time")

  /** R6: integers or null; shanghai, cancun and prague are required. */
  private def forkTimes(config: JObj): R[ForkTimes] =
    val parsed = TimeKeys.foldLeft[R[Map[String, Option[Long]]]](Right(Map.empty)) {
      case (Left(e), _) => Left(e)
      case (Right(done), key) =>
        timeValue(key, config.get(key)).map(v => done.updated(key, v))
    }
    parsed.flatMap { times =>
      def required(key: String): R[Long] =
        times(key).toRight(
          CannotRepresent(
            "a fork timestamp",
            s"Ephemery genesis without ${key.stripSuffix("Time").toUpperCase} is not supported (expected it active at genesis)"
          )
        )
      for
        shanghai <- required("shanghaiTime")
        cancun <- required("cancunTime")
        prague <- required("pragueTime")
      yield ForkTimes(
        shanghai,
        cancun,
        prague,
        times("osakaTime"),
        times("bpo1Time"),
        times("bpo2Time"),
        times("amsterdamTime")
      )
    }

  private def timeValue(key: String, value: Option[Js]): R[Option[Long]] = value match
    case None | Some(JNull) => Right(None)
    case Some(js)           =>
      // the same bounded parser as the header quantities, so the two cannot drift
      GenesisJsonEncoder.parseQuantity(Some(js)) match
        case None => cannot("a fork timestamp", s"config.$key is not an integer: ${show(js)}")
        case Some(n) if n > GenesisJsonEncoder.MaxQuantity =>
          cannot("a fork timestamp", s"config.$key is above 2^63-1, the largest timestamp fukuii reads: ${show(js)}")
        case Some(n) => Right(Some(n.toLong))

  /** The forks are in order, and shanghai, cancun and prague are active at the genesis timestamp. */
  private def forkOrder(times: ForkTimes, genesisTimestamp: Long): R[Unit] =
    val named = times.named
    val gaps = List(
      ("bpo1Time", times.bpo1, "osakaTime", times.osaka),
      ("bpo2Time", times.bpo2, "bpo1Time", times.bpo1),
      ("amsterdamTime", times.amsterdam, "osakaTime", times.osaka)
    ).collectFirst { case (a, Some(_), b, None) => (a, b) }
    val misordered = named.zip(named.drop(1)).collectFirst { case ((a, ta), (b, tb)) if ta > tb => (a, ta, b, tb) }
    if gaps.isDefined then
      val (a, b) = gaps.get
      cannot("a fork schedule", s"config.$a is declared but config.$b is not: the fork schedule has a gap")
    else
      misordered match
        case Some((a, ta, b, tb)) =>
          inconsistent(s"fork timestamps are not in the order of the forks: $a ($ta) is after $b ($tb)")
        case None =>
          named.take(3).find(_._2 > genesisTimestamp) match
            case Some((key, t)) =>
              cannot(
                "a fork after genesis",
                s"Ephemery genesis has $key ($t) after the genesis timestamp ($genesisTimestamp); fukuii expects " +
                  "shanghai, cancun and prague active at genesis"
              )
            case None => Right(())

  /** R7. */
  private def depositContract(config: JObj): R[String] = config.get("depositContractAddress") match
    case Some(JStr(s)) if DepositAddress.matcher(s).matches() => Right(s)
    case other => inconsistent(s"config.depositContractAddress missing or malformed: ${other.fold("null")(show)}")

  /** R8, the first half: the forks are known, each entry is three integers, and each fork the config declares an entry
    * for is declared. The schedule's meaning (an entry for every blob fork, the order, the inherited entries) is
    * checked by `BlobSchedule` when the rendered configuration is parsed.
    */
  private def blobSchedule(config: JObj, times: ForkTimes): R[Seq[(String, BigInt, BigInt, BigInt)]] =
    config.get("blobSchedule") match
      case None | Some(JNull) => Right(Nil)
      case Some(schedule: JObj) =>
        val unknown = schedule.keys.filterNot(ChainConfRenderer.BlobForks.toSet).sorted
        if unknown.nonEmpty then
          cannot("a blob fork", s"blobSchedule declares fork(s) fukuii has no blob fork for: ${unknown.mkString(",")}")
        else
          ChainConfRenderer.BlobForks
            .flatMap(f => schedule.get(f).map(f -> _))
            .foldLeft[R[Vector[(String, BigInt, BigInt, BigInt)]]](Right(Vector.empty)) {
              case (Left(e), _)                 => Left(e)
              case (Right(done), (fork, entry)) => blobEntry(fork, entry, times).map(done :+ _)
            }
      case Some(other) => cannot("a blob schedule", s"blobSchedule is not an object: ${show(other)}")

  private def blobEntry(fork: String, entry: Js, times: ForkTimes): R[(String, BigInt, BigInt, BigInt)] =
    entry match
      case e: JObj =>
        def field(k: String): Option[BigInt] = e.get(k) match
          case Some(n: JNum) if n.isInteger && !n.raw.startsWith("-") => Some(BigInt(n.raw))
          case _                                                      => None
        val unknown = e.keys.filterNot(BlobParamKeys).sorted
        (field("target"), field("max"), field("baseFeeUpdateFraction")) match
          case _ if unknown.nonEmpty =>
            cannot(
              "a blob schedule entry",
              s"blobSchedule.$fork declares keys fukuii cannot map: ${unknown.mkString(",")}"
            )
          case (Some(target), Some(max), Some(fraction)) =>
            if times.declared(fork) then Right((fork, target, max, fraction))
            else inconsistent(s"blobSchedule has an entry for $fork, but config.${fork}Time is not declared")
          case (t, m, f) =>
            def text(k: String, v: Option[BigInt]): String = v.fold(e.get(k).fold("null")(show))(_.toString)
            cannot(
              "a blob schedule entry",
              s"blobSchedule.$fork has a missing or non-integer field (target/max/baseFeeUpdateFraction = " +
                s"${text("target", t)}/${text("max", m)}/${text("baseFeeUpdateFraction", f)})"
            )
      case other => cannot("a blob schedule entry", s"blobSchedule.$fork is not an object: ${show(other)}")

  /** R11: retention.vars GENESIS_TIMESTAMP is the genesis timestamp. */
  private def retentionTimestamp(retention: Map[String, String], genesisTimestamp: Long): R[Unit] =
    retention.get("GENESIS_TIMESTAMP") match
      case Some(v) if !(DecimalDigits.matcher(v).matches() && BigInt(v) == BigInt(genesisTimestamp)) =>
        inconsistent(s"retention.vars GENESIS_TIMESTAMP=$v disagrees with genesis.json timestamp=$genesisTimestamp")
      case Some(_) => Right(())
      case None    => inconsistent("retention.vars has no GENESIS_TIMESTAMP")

  /** R11: chainspec.json, when the release ships it, has the same genesis timestamp. */
  private def chainspecTimestamp(chainspec: Option[String], encoded: GenesisJsonEncoder.Encoded): R[Unit] =
    chainspec match
      case None => Right(())
      case Some(text) =>
        for
          spec <- parseObject(ReleaseInputs.ChainspecFile, text)
          stamp = spec.get("genesis").collect { case g: JObj => g }.flatMap(_.get("timestamp"))
          value <- GenesisJsonEncoder.quantity("chainspec.timestamp", stamp)
          _ <- Either.cond(
            value == BigInt(encoded.timestamp),
            (),
            Inconsistent(
              s"chainspec.json genesis timestamp ${stamp.fold("null")(show)} disagrees with genesis.json ${encoded.timestampText}"
            )
          )
        yield ()

  /** N4's input: GENESIS_RESET_INTERVAL is an integer above 0 (and at most `Int.MaxValue` seconds). */
  private def resetInterval(retention: Map[String, String]): R[Long] =
    retention.get("GENESIS_RESET_INTERVAL") match
      case None => Right(DefaultResetInterval)
      case Some(v) if DecimalDigits.matcher(v).matches() && BigInt(v) > 0 && BigInt(v) <= MaxResetInterval =>
        Right(v.toLong)
      case Some(v) =>
        inconsistent(s"retention.vars GENESIS_RESET_INTERVAL must be an integer above 0 (got '$v')")

  /** N1. */
  private def publishedHash(text: String): R[String] =
    val trimmed = text.filterNot(_.isWhitespace)
    if HashText.matcher(trimmed).matches() then Right(trimmed.toLowerCase)
    else
      inconsistent(
        s"deposit_contract_block_hash.txt does not hold 0x and 64 hex digits (got '${trimmed.take(80)}')"
      )

  /** What may not go into a quoted HOCON string or a shell assignment: a quote, a backslash, a `$`, a backtick, and
    * anything outside printable ASCII.
    */
  private def plain(s: String): Boolean =
    s.forall(c => c > ' ' && c < '\u007f' && c != '"' && c != '\\' && c != '$' && c != '`')

  /** The execution-layer bootnodes: `#` comments stripped, whitespace removed, `enode://` lines only. */
  private def bootnodes(text: Option[String]): R[Seq[String]] =
    val nodes = text.toList
      .flatMap(_.linesIterator)
      .map(line => line.takeWhile(_ != '#').filterNot(_.isWhitespace))
      .filter(_.startsWith("enode://"))
    nodes.find(n => !plain(n)) match
      case Some(bad) =>
        inconsistent(s"enodes.txt holds a line fukuii will not copy into a configuration: '${bad.take(40)}...'")
      case None => Right(nodes)

  /** The consensus-layer bootnodes, passed through to `release.env`. */
  private def bootEnrs(text: Option[String]): R[Seq[String]] =
    val enrs = text.toList.flatMap(_.linesIterator).map(_.trim).filter(_.nonEmpty)
    enrs.find(e => !plain(e)) match
      case Some(bad) =>
        inconsistent(s"boot_enr.txt holds a line fukuii will not copy into release.env: '${bad.take(40)}...'")
      case None => Right(enrs)

  /** N3: the genesis block hash of the release, computed from exactly what a node would load. The rendered
    * configuration is parsed by `BlockchainConfig.fromRawConfig` (with its include answered from memory, as
    * `custom-chains-dir` answers it from the file), and the genesis it carries is hashed by the loader's own
    * `genesisHeader`. A configuration `BlobSchedule` rejects fails here, at fetch, and not at node start.
    */
  private def genesisHash(conf: String, genesisJson: String): R[ByteString] =
    recompute(conf, genesisJson).map(_.hash)

  /** What a rendered chain configuration and genesis come to when a node loads them: the chain id the configuration
    * states, the genesis timestamp, the computed genesis block hash, and the hash the configuration itself states
    * (`genesis-hash`, R14), when it states one.
    */
  final case class Recomputed(chainId: BigInt, genesisTimestamp: Long, hash: ByteString, statedHash: Option[String]):
    def hashHex: String = "0x" + Hex.toHexString(hash.toArray)

  /** N3 over any rendered pair: the same load a node does, with the include answered from memory. A cache entry or the
    * bundle is re-validated with it before it is trusted ([[ReleaseEntry.verify]]).
    */
  def recompute(conf: String, genesisJson: String): R[Recomputed] =
    val parsed = Try {
      val options = ConfigParseOptions
        .defaults()
        .setSyntax(ConfigSyntax.CONF)
        .setIncluder(new InMemoryIncluder(Map(GenesisFileName -> genesisJson)))
        .setOriginDescription(ChainConfFileName)
      val raw = ConfigFactory.parseString(conf, options)
      val blockchainConfig = BlockchainConfig.fromRawConfig(raw)
      val json = blockchainConfig.customGenesisJsonOpt.getOrElse(
        throw new IllegalStateException("the rendered configuration carries no genesis")
      )
      val data = GenesisDataLoader.parseGenesisData(json).get
      val hash = GenesisDataLoader.genesisHeader(data)(using blockchainConfig).hash.value
      val timestamp =
        java.lang.Long.parseLong(data.timestamp.stripPrefix("0x"), if data.timestamp.startsWith("0x") then 16 else 10)
      Recomputed(
        blockchainConfig.chainId.value,
        timestamp,
        hash,
        Option.when(raw.hasPath("genesis-hash"))(raw.getString("genesis-hash"))
      )
    }
    parsed.toEither.left.map {
      case e: BlobSchedule.InvalidBlobSchedule => CannotRepresent("a blob schedule", e.getMessage)
      case e: ConfigException                  => CannotRepresent("the chain configuration", e.getMessage)
      case e => CannotRepresent("the genesis", Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
    }

  /** Answers `include "ephemery-genesis.json"` from memory, and nothing else. */
  final private class InMemoryIncluder(files: Map[String, String]) extends ConfigIncluder:
    // Nothing else is ever included, so there is no fallback to defer to.
    override def withFallback(next: ConfigIncluder): ConfigIncluder = this
    override def include(context: ConfigIncludeContext, what: String): ConfigObject =
      files.get(what) match
        case Some(text) =>
          ConfigFactory.parseString(text, context.parseOptions().setSyntax(ConfigSyntax.JSON)).root()
        case None => throw new ConfigException.Generic(s"the rendered configuration includes '$what'")
