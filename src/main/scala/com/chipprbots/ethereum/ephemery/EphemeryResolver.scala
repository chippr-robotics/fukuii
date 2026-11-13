package com.chipprbots.ethereum.ephemery

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.regex.Pattern

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import scala.util.Try
import scala.util.control.NonFatal

import com.typesafe.config.Config as TConfig
import com.typesafe.config.ConfigFactory

import com.chipprbots.ethereum.ConfigValidator
import com.chipprbots.ethereum.ephemery.StrictJson.*
import com.chipprbots.ethereum.utils.BuildInfo
import com.chipprbots.ethereum.utils.LazyLogger
import com.chipprbots.ethereum.utils.LogbackReload

/** The startup resolver of `fukuii ephemery` (spec 028 R5): it decides which Ephemery iteration the node runs, before
  * the node's configuration loads, and hands the choice over as JVM system properties.
  *
  * It reads the raw configuration with `ConfigFactory.load()`, never the `Config` object of the `utils` package: that
  * object is process-wide, read once, and must not be initialised until the properties are set. Nothing on this path
  * may touch it, and `EphemeryLaunchItSpec` checks the class-load order.
  *
  * The decision (data-model.md, "Startup decision"):
  *   1. a network other than ephemery: nothing;
  *   1. an operator's `custom-chains-dir` that holds `ephemery-chain.conf`: stand down, as before this spec;
  *   1. a valid cached entry or the bundle that is current: the highest such iteration, placed in the root. Nothing is
  *      fetched, and no transport is made, while one is current (SC-003);
  *   1. none current, `source` a directory: its release, no network (row 6);
  *   1. none current, `refresh = false`: refuse (exit 78);
  *   1. none current: fetch the target (R7) with [[ReleaseFetcher]], validate, place with its `testnet/`, run it; or
  *      refuse (69 when it is not out or not reachable, 78 when it is refused).
  */
object EphemeryResolver extends LazyLogger:

  enum Level:
    case Info, Warn, Error

  /** One line for the log, in the wording of contracts/exit-codes.md. */
  final case class Line(level: Level, text: String)

  /** Where the iteration the node runs came from. */
  enum Origin:
    /** `Fetched` is also a release read from a `source` directory: it came from outside the node. */
    case Bundled, Cache, Fetched

    /** The word of the startup report. */
    def label: String = this match
      case Bundled => "bundled"
      case Cache   => "cache"
      case Fetched => "fetched"

  /** The raw configuration the resolver reads.
    *
    * @param root
    *   `fukuii.datadir` before the selection: the Ephemery root (R6)
    * @param logsDir
    *   `logging.logs-dir` before the selection, which the resolver pins so the log stays at `<root>/logs`
    */
  final case class Settings(
      network: String,
      root: Path,
      customChainsDir: Option[Path],
      logsDir: String,
      refresh: Boolean,
      source: String,
      refreshTimeout: FiniteDuration,
      exitAtReset: Boolean
  )

  object Settings:
    val DefaultSource: String = "https://github.com/ephemery-testnet/ephemery-genesis"

    /** From the whole configuration (`ConfigFactory.load()`), with the operator keys' defaults where they are absent.
      */
    def from(raw: TConfig): Settings =
      def optional(path: String): Option[String] =
        Option.when(raw.hasPath(path))(raw.getString(path)).filter(_.nonEmpty)
      val root = Paths.get(raw.getString("fukuii.datadir"))
      Settings(
        network = raw.getString("fukuii.blockchains.network"),
        root = root,
        customChainsDir = optional("fukuii.blockchains.custom-chains-dir").map(Paths.get(_)),
        logsDir = optional("logging.logs-dir").getOrElse(root.resolve("logs").toString),
        refresh = !raw.hasPath("fukuii.ephemery.refresh") || raw.getBoolean("fukuii.ephemery.refresh"),
        source = optional("fukuii.ephemery.source").getOrElse(DefaultSource),
        // Unreachable defence: `startup` refuses a malformed refresh-timeout (exit 1) before it reads Settings, so no
        // caller of it sees this fallback. It only keeps `Settings.from` itself from throwing for a direct caller.
        refreshTimeout = Try(Duration.fromNanos(raw.getDuration("fukuii.ephemery.refresh-timeout").toNanos))
          .getOrElse(Duration(60, "s")),
        exitAtReset = !raw.hasPath("fukuii.ephemery.exit-at-reset") || raw.getBoolean("fukuii.ephemery.exit-at-reset")
      )

  sealed trait Resolution extends Product with Serializable

  /** The network is not ephemery. */
  case object NotApplicable extends Resolution

  /** An operator's prepared directory: nothing is fetched, no datadir is chosen, and the node exits at no reset. */
  final case class Prepared(dir: Path, resetTime: Option[Instant]) extends Resolution

  /** A managed iteration: the entry, where it came from, and the paths the node will use. */
  final case class Managed(
      entry: ReleaseEntry,
      origin: Origin,
      datadir: Path,
      chainsDir: Path,
      clDir: Option[Path]
  ) extends Resolution

  /** No iteration can be run; the process exits with the refusal's code. */
  final case class Refused(refusal: ReleaseRefusal) extends Resolution

  /** @param properties
    *   the JVM system properties to set before the configuration loads
    */
  final case class Result(resolution: Resolution, lines: List[Line], properties: Map[String, String])

  private val Prefix = "fukuii.ephemery.active."

  /** Every property the resolver may set, for a caller that must clear them again. */
  val PropertyKeys: List[String] = List(
    "fukuii.blockchains.custom-chains-dir",
    "fukuii.datadir",
    "logging.logs-dir",
    s"${Prefix}iteration",
    s"${Prefix}reset-time",
    s"${Prefix}genesis-hash",
    s"${Prefix}managed"
  )

  private val NetworkId = Pattern.compile("(?m)^\\s*network-id\\s*=\\s*([0-9]+)\\s*$")
  private val ResetTimeVar = Pattern.compile("(?m)^RESET_TIME=\"?([0-9]+)\"?\\s*$")

  // ---- the text of the operator-facing lines -------------------------------------------------------------------------

  /** The WARN for an operator's prepared directory whose iteration has ended. */
  def preparedEndedText(dir: Path, reset: Instant): String =
    s"Ephemery: the prepared iteration in $dir ended at $reset; re-run scripts/ephemery/fetch-genesis.sh, " +
      "or unset custom-chains-dir to use the built-in refresh"

  private def shortHash(hash: String): String = hash.take(6) + "…" + hash.takeRight(4)

  // ---- the decision --------------------------------------------------------------------------------------------------

  /** Decides, with the file I/O of [[ReleaseStore]] and nothing else: no system property is set, no logger is touched.
    * The caller applies [[Result.properties]] and writes [[Result.lines]].
    */
  def resolve(settings: Settings, deps: StartupDeps, bundle: BundleSource): Result =
    if settings.network != "ephemery" then Result(NotApplicable, Nil, Map.empty)
    else
      val now = deps.clock.instant()
      val lines = mutable.ListBuffer.empty[Line]
      val prepared =
        settings.customChainsDir.filter(d => Files.isRegularFile(d.resolve(EphemeryRelease.ChainConfFileName)))
      val (resolution, properties) = prepared match
        case Some(dir) => standDown(dir, now, lines)
        case None =>
          settings.customChainsDir.foreach(d =>
            lines += Line(
              Level.Info,
              s"Ephemery: ignoring custom-chains-dir $d: it holds no ${EphemeryRelease.ChainConfFileName}; " +
                "using the built-in iteration"
            )
          )
          managed(settings, deps, bundle, now, lines)
      Result(resolution, lines.toList, properties)

  /** Row 2: the operator's directory stays in charge. */
  private def standDown(
      dir: Path,
      now: Instant,
      lines: mutable.ListBuffer[Line]
  ): (Resolution, Map[String, String]) =
    val reset = preparedReset(dir)
    lines += Line(
      Level.Info,
      s"Ephemery: using the prepared chain directory $dir (custom-chains-dir); fukuii will not fetch or exit at its reset"
    )
    reset.filterNot(now.isBefore(_)).foreach(r => lines += Line(Level.Warn, preparedEndedText(dir, r)))
    val iteration =
      Try(Files.readString(dir.resolve(EphemeryRelease.ChainConfFileName), StandardCharsets.UTF_8)).toOption
        .flatMap { text =>
          val m = NetworkId.matcher(text)
          Option.when(m.find())(BigInt(m.group(1)) - EphemeryRelease.IterationBase).filter(_.isValidInt).map(_.toInt)
        }
    val properties = (reset, iteration) match
      case (Some(r), Some(n)) =>
        Map(
          s"${Prefix}managed" -> "false",
          s"${Prefix}iteration" -> n.toString,
          s"${Prefix}reset-time" -> r.getEpochSecond.toString
        )
      case _ => Map.empty[String, String]
    (Prepared(dir, reset), properties)

  /** When the operator's iteration ends: `RESET_TIME` of the `release.env` next to the chains directory, else the
    * genesis timestamp plus the default interval; unknown when neither can be read.
    */
  private def preparedReset(dir: Path): Option[Instant] =
    val fromEnv = Option(dir.getParent)
      .map(_.resolve("release.env"))
      .filter(Files.isRegularFile(_))
      .flatMap(f => Try(Files.readString(f, StandardCharsets.UTF_8)).toOption)
      .flatMap { text =>
        val m = ResetTimeVar.matcher(text)
        Option.when(m.find())(m.group(1)).flatMap(_.toLongOption)
      }
    fromEnv
      .orElse(genesisTimestamp(dir).map(_ + EphemeryRelease.DefaultResetInterval))
      .map(Instant.ofEpochSecond)

  private def genesisTimestamp(dir: Path): Option[Long] =
    Try(Files.readString(dir.resolve(EphemeryRelease.GenesisFileName), StandardCharsets.UTF_8)).toOption
      .flatMap(text => StrictJson.parse(text).toOption)
      .collect { case o: JObj => o }
      .flatMap(_.get("timestamp"))
      .flatMap {
        case JStr(s) if s.startsWith("0x") => Try(java.lang.Long.parseLong(s.drop(2), 16)).toOption
        case JStr(s)                       => s.toLongOption
        case n: JNum                       => n.raw.toLongOption
        case _                             => None
      }

  /** Every iteration this node holds or carries, the cached copy standing in for the bundled one of the same number:
    * what the refresh counts forward from, and what a fetched release is compared with (`Republished`). With no store,
    * the bundle alone.
    */
  def knownEntries(store: Option[ReleaseStore], bundle: BundleSource): List[ReleaseEntry] =
    val cached = store.toList.flatMap(_.list().entries.map(_.entry))
    val bundled = bundle.entries().flatMap(t => ReleaseEntry.verify(t.metadata, t.chainConf, t.genesisJson).toOption)
    (cached ++ bundled.filterNot(b => cached.exists(_.iteration == b.iteration))).sortBy(_.iteration)

  final private case class Candidate(entry: ReleaseEntry, origin: Origin, held: Option[ReleaseStore.Held])

  /** Rows 3 and 5 (and, in a later slice, 4 and 6). */
  private def managed(
      settings: Settings,
      deps: StartupDeps,
      bundle: BundleSource,
      now: Instant,
      lines: mutable.ListBuffer[Line]
  ): (Resolution, Map[String, String]) =
    val store = new ReleaseStore(settings.root, deps.clock)
    store.sweepStages()
    val listing = store.list()
    listing.problems.foreach(p =>
      lines += Line(Level.Warn, s"Ephemery: ignoring a cached release that does not validate: $p")
    )
    val bundled = bundle.entries().flatMap { t =>
      ReleaseEntry.verify(t.metadata, t.chainConf, t.genesisJson) match
        case Right(entry) => List(entry)
        case Left(problem) =>
          lines += Line(Level.Warn, s"Ephemery: ignoring the bundled ${t.name}: $problem")
          Nil
    }
    val cached = listing.entries.map(h => Candidate(h.entry, Origin.Cache, Some(h)))
    val builtIn = bundled.map(e => Candidate(e, Origin.Bundled, None))
    // For one iteration the entry on disk is the node's own; the bundle only stands in where none is held.
    val all = cached ++ builtIn.filterNot(b => cached.exists(_.entry.iteration == b.entry.iteration))
    all.filter(_.entry.isCurrent(now)).maxByOption(_.entry.iteration) match
      case Some(chosen) =>
        warnWhenCachedDiffers(chosen, cached, builtIn, lines)
        select(store, settings, chosen, now, lines)
      case None => ended(store, settings, deps, all, now, lines)

  /** A cached copy of an iteration that the bundle also holds, with another genesis hash: say so. */
  private def warnWhenCachedDiffers(
      chosen: Candidate,
      cached: List[Candidate],
      builtIn: List[Candidate],
      lines: mutable.ListBuffer[Line]
  ): Unit =
    for
      c <- cached
      b <- builtIn.find(b => b.entry.iteration == c.entry.iteration && b.entry.genesisHash != c.entry.genesisHash)
    do
      val differs =
        s"Ephemery: the cached ${c.entry.release} (genesis ${c.entry.genesisHash}) differs from the bundled one " +
          s"(${b.entry.genesisHash})"
      lines += Line(
        Level.Warn,
        if c.entry.iteration == chosen.entry.iteration then s"$differs; running the cached one"
        else s"$differs; it is not the iteration run now"
      )

  private def refuse(refusal: ReleaseRefusal): (Resolution, Map[String, String]) = (Refused(refusal), Map.empty)

  /** What `release.json` says made the entry. */
  private def producedBy: String = s"fukuii ${BuildInfo.version}"

  /** Nothing is current. Before the newest known iteration's window opens: 69. After it has ended: the source decides
    * (rows 4 to 6).
    */
  private def ended(
      store: ReleaseStore,
      settings: Settings,
      deps: StartupDeps,
      all: List[Candidate],
      now: Instant,
      lines: mutable.ListBuffer[Line]
  ): (Resolution, Map[String, String]) =
    all.map(_.entry).maxByOption(_.iteration) match
      case None => refuse(ReleaseRefusal.BadSource("no Ephemery iteration is built in or cached"))
      case Some(newest) if now.isBefore(newest.genesisTime) =>
        refuse(
          ReleaseRefusal.WindowMismatch(newest.iteration, s"${newest.genesisTime} to ${newest.resetTime}", now.toString)
        )
      case Some(newest) =>
        ReleaseSource.parse(settings.source) match
          case Left(bad) => refuse(bad)
          // a directory is no network: `refresh = false` does not stop it, which is what an operator who offers one wants
          case Right(ReleaseSource.Directory(dir)) => fromDirectory(store, settings, all, newest, dir, now, lines)
          case Right(_) if !settings.refresh =>
            val next = newest.iteration + 1
            refuse(
              ReleaseRefusal.Ended(
                newest.iteration,
                newest.resetTime.toString,
                s"fetching is disabled (fukuii.ephemery.refresh = false); supply ephemery-$next with " +
                  "`fukuii cli ephemery-fetch --from-dir DIR` or enable refresh"
              )
            )
          case Right(ReleaseSource.Https(base, policy)) =>
            fetchNext(store, settings, deps, all, newest, base, policy, now, lines)

  /** Row 6: the release in the operator's directory, read, validated and placed. No network. */
  private def fromDirectory(
      store: ReleaseStore,
      settings: Settings,
      all: List[Candidate],
      newest: ReleaseEntry,
      dir: Path,
      now: Instant,
      lines: mutable.ListBuffer[Line]
  ): (Resolution, Map[String, String]) =
    val read = ReleaseInputs.fromDir(dir).flatMap(EphemeryRelease.validate).map { valid =>
      ReleaseEntry.of(valid, dir.toAbsolutePath.normalize.toString, None, producedBy, now)
    }
    read match
      case Left(refusal) => refuse(refusal)
      case Right(entry) if !entry.isCurrent(now) =>
        val state =
          if now.isBefore(entry.genesisTime) then s"does not begin until ${entry.genesisTime}"
          else s"ended at ${entry.resetTime}"
        refuse(
          ReleaseRefusal.Ended(
            newest.iteration,
            newest.resetTime.toString,
            s"the source directory $dir holds ${entry.release}, which $state; nothing newer is available"
          )
        )
      case Right(entry) =>
        known(all)(entry.iteration).filter(_ != entry.genesisHash) match
          case Some(held) => refuse(ReleaseRefusal.Republished(entry.iteration, held, entry.genesisHash))
          case None       => placeAndSelect(store, settings, entry, None, Origin.Fetched, now, lines)

  private def known(all: List[Candidate])(iteration: Int): Option[String] =
    all.find(_.entry.iteration == iteration).map(_.entry.genesisHash)

  /** Row 4: fetch the target with [[ReleaseFetcher]] under the one budget, then place it with its `testnet/`. */
  private def fetchNext(
      store: ReleaseStore,
      settings: Settings,
      deps: StartupDeps,
      all: List[Candidate],
      newest: ReleaseEntry,
      base: java.net.URI,
      policy: SourcePolicy,
      now: Instant,
      lines: mutable.ListBuffer[Line]
  ): (Resolution, Map[String, String]) =
    deps.transportFor(policy) match
      case Left(why) => refuse(ReleaseRefusal.BadSource(s"cannot make the HTTPS client: $why"))
      case Right(transport) =>
        val stages = mutable.ListBuffer.empty[Path]
        try
          val target = ReleaseFetcher.targetIteration(newest, now)
          lines += Line(
            Level.Info,
            s"Ephemery: ${newest.release} ended at ${newest.resetTime}; fetching ephemery-$target from ${base.getHost} " +
              s"(within ${settings.refreshTimeout.toSeconds} s)"
          )
          val fetcher = new ReleaseFetcher(
            transport,
            deps.clock,
            deps.sleeper,
            base,
            java.time.Duration.ofNanos(settings.refreshTimeout.toNanos)
          )
          val fetched = fetcher.fetchCurrent(
            target,
            () =>
              val d = store.newStageDir()
              stages += d
              d
            ,
            known(all)
          )
          fetched match
            case Left(refusal) => refuse(refusal)
            case Right(f) =>
              val entry =
                ReleaseEntry.of(f.release, f.sourceUrl, Some(f.archiveSha256), producedBy, deps.clock.instant())
              placeAndSelect(store, settings, entry, Some(f.stage), Origin.Fetched, now, lines)
        catch
          case NonFatal(e) => refuse(ReleaseRefusal.CannotStore(s"cannot fetch into ${settings.root}: ${e.getMessage}"))
        finally
          transport match
            case c: AutoCloseable => Try(c.close())
            case _                => ()
          stages.foreach(d => Try(StageSupport.deleteTree(d)))

  /** Places a release that came from outside (with the `testnet/` staged beside it, when there is one), and runs it. */
  private def placeAndSelect(
      store: ReleaseStore,
      settings: Settings,
      entry: ReleaseEntry,
      testnet: Option[Path],
      origin: Origin,
      now: Instant,
      lines: mutable.ListBuffer[Line]
  ): (Resolution, Map[String, String]) =
    store.place(entry, testnet) match
      case Left(refusal) => refuse(refusal)
      case Right(placed) =>
        placed match
          case ReleaseStore.SetAside(aside) => lines += Line(Level.Warn, asideText(store, entry, aside))
          case ReleaseStore.AlreadyPresent =>
            testnet.foreach(t =>
              store.addTestnet(entry.iteration, t).left.foreach(w => lines += Line(Level.Warn, s"Ephemery: $w"))
            )
          case ReleaseStore.Placed => ()
        store.list().entries.find(_.entry.iteration == entry.iteration) match
          case None =>
            refuse(
              ReleaseRefusal.CannotStore(s"${store.releaseDir(entry.iteration)} does not validate after it was placed")
            )
          case Some(held) => select(store, settings, Candidate(entry, origin, Some(held)), now, lines)

  private def asideText(store: ReleaseStore, entry: ReleaseEntry, aside: Path): String =
    s"Ephemery: ${store.releaseDir(entry.iteration)} did not validate; set aside as $aside (kept, never " +
      s"deleted) and placed ${entry.release} again"

  /** Row 3: run the chosen entry: place it, make its datadir, and name what the node must be given. */
  private def select(
      store: ReleaseStore,
      settings: Settings,
      chosen: Candidate,
      now: Instant,
      lines: mutable.ListBuffer[Line]
  ): (Resolution, Map[String, String]) =
    val entry = chosen.entry
    // The entry's files were validated above (cache: on load; bundle: before it was placed), and the node reads them
    // again from `custom-chains-dir` when `Config` loads. Between the two, the files are operator-trusted local state:
    // whoever can rewrite `<root>/releases` can already rewrite the node's datadir and keys, so that window is accepted
    // and not closed with a second read of the text (spec 028 review B2). Nothing else in the hand-over names a path.
    val held: Either[ReleaseRefusal, ReleaseStore.Held] = chosen.held match
      case Some(h) => Right(h)
      case None =>
        store.place(entry).flatMap { result =>
          result match
            case ReleaseStore.SetAside(aside) =>
              lines += Line(Level.Warn, asideText(store, entry, aside))
            case _ => ()
          store.list().entries.find(_.entry.iteration == entry.iteration).toRight {
            ReleaseRefusal.CannotStore(s"${store.releaseDir(entry.iteration)} does not validate after it was placed")
          }
        }
    val datadir: Either[ReleaseRefusal, Path] = held.flatMap { _ =>
      try
        val seeded = store.seedDatadir(entry.iteration)
        seeded.warning.foreach(w => lines += Line(Level.Warn, s"Ephemery: $w"))
        Right(seeded.dir)
      catch case NonFatal(e) => Left(ReleaseRefusal.CannotStore(s"cannot make the datadir: ${e.getMessage}"))
    }
    (held, datadir) match
      case (Right(h), Right(dir)) =>
        store.pointCurrent(entry.iteration).left.foreach(w => lines += Line(Level.Warn, s"Ephemery: $w"))
        report(entry, chosen.origin, h, dir, store, now, lines)
        val properties = Map(
          "fukuii.blockchains.custom-chains-dir" -> h.chainsDir.toString,
          "fukuii.datadir" -> dir.toString,
          "logging.logs-dir" -> settings.logsDir,
          s"${Prefix}iteration" -> entry.iteration.toString,
          s"${Prefix}reset-time" -> entry.metadata.resetTime.toString,
          s"${Prefix}genesis-hash" -> entry.genesisHash,
          s"${Prefix}managed" -> "true"
        )
        (Managed(entry, chosen.origin, dir, h.chainsDir, h.testnetDir), properties)
      case (Left(refusal), _) => (Refused(refusal), Map.empty)
      case (_, Left(refusal)) => (Refused(refusal), Map.empty)

  /** The startup report (US1-3), the earlier datadirs, and a datadir from before built-in Ephemery. */
  private def report(
      entry: ReleaseEntry,
      origin: Origin,
      held: ReleaseStore.Held,
      datadir: Path,
      store: ReleaseStore,
      now: Instant,
      lines: mutable.ListBuffer[Line]
  ): Unit =
    lines += Line(
      Level.Info,
      s"Ephemery ${entry.release} (chain id ${entry.metadata.chainId}, genesis ${shortHash(entry.genesisHash)}) " +
        s"from ${origin.label}; resets ${entry.resetTime} (local clock $now); datadir $datadir; " +
        held.testnetDir.fold(s"CL dir not present: run `fukuii cli ephemery-fetch --release ${entry.release}`")(d =>
          s"CL dir $d"
        )
    )
    val earlier = store.earlierDatadirs(except = entry.iteration)
    if earlier.nonEmpty then
      lines += Line(
        Level.Info,
        "Ephemery: earlier iteration datadirs kept: " + earlier.mkString(", ") + "; fukuii never deletes them"
      )
    store.legacyDatadir().foreach { path =>
      lines += Line(
        Level.Info,
        s"Ephemery: found a datadir from before built-in Ephemery at $path; it is not used and fukuii does not touch it"
      )
    }

  // ---- the startup ---------------------------------------------------------------------------------------------------

  /** Runs at startup, after `App.setNetworkConfig("ephemery")` and before anything reads that `Config` object: decides,
    * sets the system properties, drops the configuration cache, moves the log to where the properties say, and writes
    * the report. `Some(code)` is the exit code of a refusal: the caller ends the process with it.
    *
    * @param raw
    *   the raw configuration, by name so that a test can supply its own
    * @param reloadLogging
    *   moves the log to where the properties say; a test passes its own so it does not reset the test JVM's logger
    */
  def startup(
      deps: StartupDeps,
      bundle: BundleSource = BundleSource.classpath,
      raw: => TConfig = ConfigFactory.load(),
      reloadLogging: () => Unit = () => LogbackReload.reload()
  ): Option[Int] =
    // The operator keys are checked first, with the validator the node runs later: a bad refresh-timeout must not be
    // replaced by a default and used for a fetch, and a command that never reaches `Fukuii.run` would never be checked.
    val config = raw
    val configErrors =
      if config.hasPath("fukuii.blockchains.network") && config.getString("fukuii.blockchains.network") == "ephemery" &&
        config.hasPath("fukuii")
      then ConfigValidator.ephemeryErrors(config.getConfig("fukuii"))
      else Nil
    if configErrors.nonEmpty then
      configErrors.foreach(e => log.error(s"Configuration error: $e"))
      Some(ConfigErrorExit)
    else startupChecked(config, deps, bundle, reloadLogging)

  /** The exit code of a configuration error, as `Fukuii.run`'s own validation exits. */
  val ConfigErrorExit: Int = 1

  private def startupChecked(
      raw: TConfig,
      deps: StartupDeps,
      bundle: BundleSource,
      reloadLogging: () => Unit
  ): Option[Int] =
    val result = resolve(Settings.from(raw), deps, bundle)
    result.resolution match
      case NotApplicable => None
      case Refused(refusal) =>
        emit(result.lines)
        log.error(s"Ephemery: ${refusal.message}; exiting ${refusal.exitCode}")
        Some(refusal.exitCode)
      case _ =>
        result.properties.foreach { case (k, v) => System.setProperty(k, v) }
        ConfigFactory.invalidateCaches()
        // The logger was set up when the process started, from the configuration of that moment; the log must go where
        // the properties now say (`<root>/logs`), and `truncateLogs()` reads the same place.
        if result.properties.contains("fukuii.datadir") then reloadLogging()
        // Held back, not written: `Fukuii.run` truncates the log files when it starts, which would erase these lines.
        // It writes them right after ([[flushReport]]); `App.run` does when the command does not go through it.
        pending.set(result.lines)
        None

  /** The report lines of [[startup]] that have not been written yet. */
  private val pending = new java.util.concurrent.atomic.AtomicReference[List[Line]](Nil)

  /** Writes the report lines [[startup]] held back, once. Called after the log files are truncated. */
  def flushReport(sink: List[Line] => Unit = emit): Unit = sink(pending.getAndSet(Nil))

  private def emit(lines: List[Line]): Unit =
    lines.foreach { l =>
      l.level match
        case Level.Info  => log.info(l.text)
        case Level.Warn  => log.warn(l.text)
        case Level.Error => log.error(l.text)
    }
