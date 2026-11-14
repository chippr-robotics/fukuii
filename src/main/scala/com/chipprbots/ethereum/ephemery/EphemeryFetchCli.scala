package com.chipprbots.ethereum.ephemery

import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import java.util.regex.Pattern

import cats.implicits.*

import scala.util.Try
import scala.util.control.NonFatal

import com.monovore.decline.Command
import com.monovore.decline.Opts
import com.typesafe.config.Config as TConfig
import com.typesafe.config.ConfigFactory

import com.chipprbots.ethereum.ephemery.EphemeryRelease.ValidRelease

/** `fukuii cli ephemery-fetch`: gets an Ephemery release, validates it with [[EphemeryRelease]], and writes it. It is
  * the one rule engine; `scripts/ephemery/fetch-genesis.sh` is `exec` of it.
  *
  * Where the release comes from: `--from-dir DIR` (no network), else `fukuii.ephemery.source` when that is a directory,
  * else a download from `fukuii.ephemery.source` with [[ReleaseFetcher]] (the iteration `--release` names, or the one
  * whose window contains now). `fukuii.ephemery.refresh = false` does not stop it: the operator asked.
  *
  * Where it writes: with `--out DIR`, the layout of [[ReleaseLayout]] (what the script always wrote); without it, the
  * node's own Ephemery root, `<root>/releases/ephemery-N/`, where `fukuii ephemery` reads, `testnet/` (the consensus
  * client's directory) included, added to an entry that is there already with one rename.
  *
  * Exit codes (spec 028 contracts/exit-codes.md): 0 on success, the refusal's class (78, or 69 for a release that is
  * not out or a source that is not reachable) when the release is refused, 1 when the output cannot be written, 2 for a
  * usage error.
  *
  * It never starts a node and never opens RocksDB; it reads the raw configuration, never the process-wide `Config`
  * object.
  */
object EphemeryFetchCli:

  val CommandName: String = "ephemery-fetch"

  /** @param release
    *   when given, the release to download, or, with `--from-dir`, a cross-check that it is that one
    * @param source
    *   recorded in `release.json`: the release page URL the wrapper downloaded from; default the absolute `fromDir`
    * @param archiveSha256
    *   recorded in `release.json`: the hash of the tarball the wrapper downloaded
    */
  final case class Args(
      fromDir: Option[Path],
      out: Option[Path],
      release: Option[String],
      source: Option[String],
      archiveSha256: Option[String]
  )

  private val ReleaseName = Pattern.compile("ephemery-[0-9]+")
  private val Sha256 = Pattern.compile("[0-9a-fA-F]{64}")

  val ExitUsage: Int = 2
  val ExitOutput: Int = 1

  private val fromDirOpt: Opts[Option[String]] =
    Opts
      .option[String](
        "from-dir",
        "Read an unpacked release from DIR (no network). Without it the release is downloaded from " +
          "fukuii.ephemery.source.",
        metavar = "DIR"
      )
      .orNone

  private val outOpt: Opts[Option[String]] =
    Opts
      .option[String](
        "out",
        "Write the release's layout under DIR: DIR/ephemery-N/{testnet,chains,conf,release.env,release.json}, " +
          "DIR/current and DIR/ephemery-local.conf. Without --out the release goes into the node's Ephemery root.",
        metavar = "DIR"
      )
      .orNone

  private val releaseOpt: Opts[Option[String]] =
    Opts
      .option[String](
        "release",
        "The release to fetch (default: the iteration whose window contains now); with --from-dir, require it.",
        metavar = "ephemery-N"
      )
      .orNone

  private val sourceOpt: Opts[Option[String]] =
    Opts
      .option[String](
        "source",
        "With --from-dir: record URL as the release's source in release.json (for the wrapper script).",
        metavar = "URL"
      )
      .orNone

  private val archiveOpt: Opts[Option[String]] =
    Opts
      .option[String](
        "archive-sha256",
        "With --from-dir: record HEX as the sha256 of the downloaded archive in release.json (for the wrapper script).",
        metavar = "HEX"
      )
      .orNone

  type Raw = (Option[String], Option[String], Option[String], Option[String], Option[String])

  /** The options of the command, with the raw text of the paths: [[validate]] checks them. */
  val opts: Opts[Raw] =
    (fromDirOpt, outOpt, releaseOpt, sourceOpt, archiveOpt).tupled

  val command: Command[Raw] =
    Command(
      name = CommandName,
      header = "Fetch an Ephemery release, validate it, and write the files fukuii reads (spec 028)."
    )(opts)

  /** `--out` ends up in a path, HOCON and `release.env`: no quotes, backslashes, `$`, backticks or newlines. */
  private def outProblem(out: String): Option[String] =
    Option.when(out.exists(c => c == '"' || c == '\\' || c == '$' || c == '`' || c == '\'' || c == '\n'))(
      s"--out must not contain quotes, backslashes, $$, backticks or newlines (got '$out')"
    )

  /** Checks the arguments the parser accepts. `Left` is a usage error. */
  def validate(raw: Raw): Either[String, Args] =
    val (fromDir, out, release, source, sha) = raw
    for
      _ <- out.flatMap(outProblem).toLeft(())
      _ <- release
        .filterNot(r => ReleaseName.matcher(r).matches())
        .map(r => s"--release must look like ephemery-<N> (got '$r')")
        .toLeft(())
      _ <- source.flatMap(ReleaseMetadata.sourceProblem).map(p => s"--source: $p").toLeft(())
      _ <- sha
        .filterNot(h => Sha256.matcher(h).matches())
        .map(h => s"--archive-sha256 must be 64 hex digits (got '$h')")
        .toLeft(())
      _ <- Either.cond(
        fromDir.isDefined || (source.isEmpty && sha.isEmpty),
        (),
        "--source and --archive-sha256 describe a release read with --from-dir; a download records its own"
      )
    yield Args(fromDir.map(Paths.get(_)), out.map(Paths.get(_)), release, source, sha.map(_.toLowerCase))

  /** Parses `argv` and runs the command. The exit code is the result: nothing here calls `sys.exit`.
    *
    * @param deps
    *   the HTTPS client and the way to wait; its clock is not used, `clock` is
    * @param raw
    *   the raw configuration, read only when the command needs the node's root or the source
    */
  def main(
      argv: Seq[String],
      env: Map[String, String],
      clock: Clock,
      producedBy: String,
      stdout: PrintStream,
      stderr: PrintStream,
      deps: StartupDeps = StartupDeps.production,
      raw: => TConfig = ConfigFactory.load()
  ): Int =
    command.parse(argv, env) match
      case Left(help) if help.errors.isEmpty =>
        stdout.println(help.toString)
        0
      case Left(help) =>
        stderr.println(help.toString)
        ExitUsage
      case Right(parsed) =>
        validate(parsed) match
          case Left(usage) =>
            stderr.println(s"ephemery-fetch: ERROR: $usage")
            ExitUsage
          case Right(args) =>
            run(args, clock, producedBy, stdout, stderr, deps.copy(clock = clock), raw)

  /** What was obtained: the validated release, the directory of its members, and what `release.json` should say. */
  final private case class Obtained(
      release: ValidRelease,
      members: Path,
      source: String,
      archiveSha256: Option[String],
      staged: Boolean
  )

  /** Gets the release `args` names and writes it where `args` says. */
  def run(
      args: Args,
      clock: Clock,
      producedBy: String,
      stdout: PrintStream,
      stderr: PrintStream,
      deps: StartupDeps,
      raw: => TConfig
  ): Int =
    def refuse(code: Int, message: String): Int =
      stderr.println(s"ephemery-fetch: ERROR: $message")
      code
    def refusal(r: ReleaseRefusal): Int = refuse(r.exitCode, r.message)

    val temporary = scala.collection.mutable.ListBuffer.empty[Path]
    var closeTransport: () => Unit = () => ()
    try
      val settings: Either[Int, Option[EphemeryResolver.Settings]] =
        if args.fromDir.isDefined && args.out.isDefined then Right(None)
        else
          Try(EphemeryResolver.Settings.from(raw)).toEither.left
            .map(e => refuse(ExitOutput, s"cannot read the configuration: ${e.getMessage}"))
            .map(Some(_))
      // With --out the node's root is not touched, not even read: the store exists only without it.
      val store = settings.toOption.flatten.filter(_ => args.out.isEmpty).map(s => new ReleaseStore(s.root, clock))

      def newStage(): Path =
        val dir = store.map(_.newStageDir()).getOrElse(Files.createTempDirectory("ephemery-fetch-"))
        temporary += dir
        dir

      val obtained: Either[Int, Obtained] = settings.flatMap { s =>
        args.fromDir match
          case Some(dir) => fromDirectory(args, dir).left.map(refusal)
          case None =>
            ReleaseSource.parse(s.get.source).left.map(refusal).flatMap {
              case ReleaseSource.Directory(dir) => fromDirectory(args, dir).left.map(refusal)
              case ReleaseSource.Https(base, policy) =>
                deps.transportFor(policy) match
                  case Left(why) => Left(refusal(ReleaseRefusal.BadSource(s"cannot make the HTTPS client: $why")))
                  case Right(transport) =>
                    closeTransport = () =>
                      transport match
                        case c: AutoCloseable => Try(c.close())
                        case _                => ()
                    download(args, s.get, store, transport, deps, clock, base, newStage _).left.map(refusal)
            }
      }

      obtained.fold(
        code => code,
        got =>
          args.release.filter(_ != got.release.release) match
            case Some(asked) =>
              // a directory the operator named that holds another release is a usage error; an archive that holds another
              // release than its tag is the publisher's inconsistency
              refuse(
                if got.staged then ReleaseRefusal.ExitConfig else ExitUsage,
                s"--release $asked disagrees with the release ${if got.staged then "fetched" else "in --from-dir"}, which is ${got.release.release}"
              )
            case None =>
              args.out match
                case Some(out) => writeLayout(got, out, clock, producedBy, stdout, stderr, refuse)
                case None      => writeRoot(got, store.get, clock, producedBy, stdout, stderr, refuse, newStage _)
      )
    catch case NonFatal(e) => refuse(ExitOutput, s"${e.getClass.getSimpleName}: ${e.getMessage}")
    finally
      closeTransport()
      temporary.foreach(d => Try(StageSupport.deleteTree(d)))

  /** A release in a directory: read, validated. No network. */
  private def fromDirectory(args: Args, dir: Path): Either[ReleaseRefusal, Obtained] =
    ReleaseInputs
      .fromDir(dir)
      .flatMap(EphemeryRelease.validate)
      .map(release =>
        Obtained(
          release,
          dir,
          args.source.getOrElse(dir.toAbsolutePath.normalize.toString),
          args.archiveSha256,
          staged = false
        )
      )

  /** A download: the iteration `--release` names, or the one whose window contains now. */
  private def download(
      args: Args,
      settings: EphemeryResolver.Settings,
      store: Option[ReleaseStore],
      transport: ReleaseTransport,
      deps: StartupDeps,
      clock: Clock,
      base: java.net.URI,
      newStage: () => Path
  ): Either[ReleaseRefusal, Obtained] =
    val known = EphemeryResolver.knownEntries(store, BundleSource.classpath)
    val fetcher = new ReleaseFetcher(
      transport,
      clock,
      deps.sleeper,
      base,
      java.time.Duration.ofNanos(settings.refreshTimeout.toNanos)
    )
    val held: Int => Option[String] = n => known.find(_.iteration == n).map(_.genesisHash)
    val fetched = args.release match
      case Some(name) => fetcher.fetchExact(name.stripPrefix("ephemery-").toInt, newStage, held)
      case None =>
        known.lastOption match
          case Some(newest) =>
            fetcher.fetchCurrent(ReleaseFetcher.targetIteration(newest, clock.instant()), newStage, held)
          case None =>
            Left(ReleaseRefusal.BadSource("no Ephemery iteration is built in or cached to count from; pass --release"))
    fetched.map(f => Obtained(f.release, f.stage, f.sourceUrl, Some(f.archiveSha256), staged = true))

  /** `--out`: the script's layout, with `testnet/` from the members' directory. */
  private def writeLayout(
      got: Obtained,
      out: Path,
      clock: Clock,
      producedBy: String,
      stdout: PrintStream,
      stderr: PrintStream,
      refuse: (Int, String) => Int
  ): Int =
    val metadata = ReleaseMetadata.of(got.release, got.source, got.archiveSha256, producedBy, clock.instant())
    ReleaseLayout.write(got.release, got.members, out, metadata) match
      case Left(problem) => refuse(ExitOutput, problem)
      case Right(written) =>
        got.release.warnings.foreach(w => stderr.println(s"ephemery-fetch: WARNING: $w"))
        written.notes.foreach(n => stderr.println(s"ephemery-fetch: WARNING: $n"))
        stdout.print(written.env)
        stderr.println(
          s"ephemery-fetch: wrote ${written.dest} (chain id ${got.release.chainId}, genesis ${got.release.genesisTimestamp}, " +
            s"genesis hash ${got.release.genesisHashHex})"
        )
        stderr.println(s"ephemery-fetch: next reset: ${java.time.Instant.ofEpochSecond(got.release.resetTime)}")
        0

  /** No `--out`: the node's root. The entry is placed with its `testnet/`, or `testnet/` is added to the entry that is
    * there with one rename.
    */
  private def writeRoot(
      got: Obtained,
      store: ReleaseStore,
      clock: Clock,
      producedBy: String,
      stdout: PrintStream,
      stderr: PrintStream,
      refuse: (Int, String) => Int,
      newStage: () => Path
  ): Int =
    val entry = ReleaseEntry.of(got.release, got.source, got.archiveSha256, producedBy, clock.instant())
    // A directory read with --from-dir holds the consensus-layer files too: stage them without the two members the node
    // never keeps. A download's stage is already that.
    val stage =
      if got.staged then got.members
      else
        val dir = newStage()
        StageSupport.copyMembers(got.members, dir, ReleaseFetcher.TarExcludes)
        dir
    store.place(entry, Some(stage)) match
      case Left(refusal) => refuse(refusal.exitCode, refusal.message)
      case Right(placed) =>
        val added: Either[String, Boolean] = placed match
          case ReleaseStore.AlreadyPresent => store.addTestnet(entry.iteration, stage)
          case _                           => Right(true)
        added match
          case Left(problem) => refuse(ExitOutput, problem)
          case Right(didAdd) =>
            val dest = store.releaseDir(entry.iteration)
            got.release.warnings.foreach(w => stderr.println(s"ephemery-fetch: WARNING: $w"))
            stdout.print(ReleaseLayout.releaseEnv(got.release, dest.getParent.getParent, dest))
            val what = placed match
              case ReleaseStore.AlreadyPresent => if didAdd then "added testnet/ to" else "already holds testnet/ in"
              case _                           => "wrote"
            stderr.println(
              s"ephemery-fetch: $what $dest (chain id ${got.release.chainId}, genesis ${got.release.genesisTimestamp}, " +
                s"genesis hash ${got.release.genesisHashHex})"
            )
            stderr.println(s"ephemery-fetch: next reset: ${java.time.Instant.ofEpochSecond(got.release.resetTime)}")
            0

  /** The command as a `cli` subcommand, for `fukuii cli --help` only: it does nothing. The launcher runs
    * `ephemery-fetch` itself ([[main]]), because it needs the exit code, which a `Command[String]` cannot carry.
    */
  def helpStub: Command[String] =
    Command(name = CommandName, header = command.header)(
      opts.map(_ => s"run `fukuii cli $CommandName` through the launcher: it exits with the class of a refusal")
    )
