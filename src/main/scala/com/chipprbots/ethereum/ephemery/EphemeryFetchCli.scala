package com.chipprbots.ethereum.ephemery

import java.io.PrintStream
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import java.util.regex.Pattern

import cats.implicits.*

import com.monovore.decline.Command
import com.monovore.decline.Opts

/** `fukuii cli ephemery-fetch`: validates an unpacked Ephemery release with [[EphemeryRelease]] and writes the layout
  * of [[ReleaseLayout]]. It is the one rule engine; `scripts/ephemery/fetch-genesis.sh` calls it for everything but the
  * download.
  *
  * In this slice `--from-dir` and `--out` are required. Exit codes (spec 028 contracts/exit-codes.md): 0 on success,
  * the refusal's class (78) when the release is refused, 1 when the output cannot be written, 2 for a usage error.
  *
  * It never starts a node, never opens RocksDB and reads no `Config`.
  */
object EphemeryFetchCli:

  val CommandName: String = "ephemery-fetch"

  /** @param release
    *   when given, the release must be this one (a cross-check for the wrapper script, which knows the tag it
    *   downloaded)
    * @param source
    *   recorded in `release.json`: the release page URL the wrapper downloaded from; default the absolute `fromDir`
    * @param archiveSha256
    *   recorded in `release.json`: the hash of the tarball the wrapper downloaded
    */
  final case class Args(
      fromDir: Path,
      out: Path,
      release: Option[String],
      source: Option[String],
      archiveSha256: Option[String]
  )

  private val ReleaseName = Pattern.compile("ephemery-[0-9]+")
  private val Sha256 = Pattern.compile("[0-9a-fA-F]{64}")

  val ExitUsage: Int = 2
  val ExitOutput: Int = 1

  private val fromDirOpt: Opts[String] =
    Opts.option[String]("from-dir", "Read an unpacked release from DIR (no network).", metavar = "DIR")

  private val outOpt: Opts[String] =
    Opts.option[String](
      "out",
      "Write the release's layout under DIR: DIR/ephemery-N/{testnet,chains,conf,release.env,release.json}, " +
        "DIR/current and DIR/ephemery-local.conf.",
      metavar = "DIR"
    )

  private val releaseOpt: Opts[Option[String]] =
    Opts.option[String]("release", "Require the release to be ephemery-N.", metavar = "ephemery-N").orNone

  private val sourceOpt: Opts[Option[String]] =
    Opts
      .option[String](
        "source",
        "Record URL as the release's source in release.json (for the wrapper script; default: the --from-dir path).",
        metavar = "URL"
      )
      .orNone

  private val archiveOpt: Opts[Option[String]] =
    Opts
      .option[String](
        "archive-sha256",
        "Record HEX as the sha256 of the downloaded archive in release.json (for the wrapper script).",
        metavar = "HEX"
      )
      .orNone

  /** The options of the command, with the raw text of the paths: [[validate]] checks them. */
  val opts: Opts[(String, String, Option[String], Option[String], Option[String])] =
    (fromDirOpt, outOpt, releaseOpt, sourceOpt, archiveOpt).tupled

  val command: Command[(String, String, Option[String], Option[String], Option[String])] =
    Command(
      name = CommandName,
      header = "Validate an unpacked Ephemery release and write the files fukuii reads (spec 028)."
    )(opts)

  /** `--out` ends up in a path, HOCON and `release.env`: no quotes, backslashes, `$`, backticks or newlines. */
  private def outProblem(out: String): Option[String] =
    Option.when(out.exists(c => c == '"' || c == '\\' || c == '$' || c == '`' || c == '\'' || c == '\n'))(
      s"--out must not contain quotes, backslashes, $$, backticks or newlines (got '$out')"
    )

  /** Checks the arguments the parser accepts. `Left` is a usage error. */
  def validate(raw: (String, String, Option[String], Option[String], Option[String])): Either[String, Args] =
    val (fromDir, out, release, source, sha) = raw
    for
      _ <- outProblem(out).toLeft(())
      _ <- release
        .filterNot(r => ReleaseName.matcher(r).matches())
        .map(r => s"--release must look like ephemery-<N> (got '$r')")
        .toLeft(())
      _ <- source.flatMap(ReleaseMetadata.sourceProblem).map(p => s"--source: $p").toLeft(())
      _ <- sha
        .filterNot(h => Sha256.matcher(h).matches())
        .map(h => s"--archive-sha256 must be 64 hex digits (got '$h')")
        .toLeft(())
    yield Args(Paths.get(fromDir), Paths.get(out), release, source, sha.map(_.toLowerCase))

  /** Parses `argv` and runs the command. The exit code is the result: nothing here calls `sys.exit`. */
  def main(
      argv: Seq[String],
      env: Map[String, String],
      clock: Clock,
      producedBy: String,
      stdout: PrintStream,
      stderr: PrintStream
  ): Int =
    command.parse(argv, env) match
      case Left(help) if help.errors.isEmpty =>
        stdout.println(help.toString)
        0
      case Left(help) =>
        stderr.println(help.toString)
        ExitUsage
      case Right(raw) =>
        validate(raw) match
          case Left(usage) =>
            stderr.println(s"ephemery-fetch: ERROR: $usage")
            ExitUsage
          case Right(args) => run(args, clock, producedBy, stdout, stderr)

  /** Validates the release in `args.fromDir` and writes it under `args.out`. */
  def run(args: Args, clock: Clock, producedBy: String, stdout: PrintStream, stderr: PrintStream): Int =
    def refuse(code: Int, message: String): Int =
      stderr.println(s"ephemery-fetch: ERROR: $message")
      code
    ReleaseInputs.fromDir(args.fromDir).flatMap(inputs => EphemeryRelease.validate(inputs)) match
      case Left(refusal) => refuse(refusal.exitCode, refusal.message)
      case Right(release) =>
        args.release.filter(_ != release.release) match
          case Some(asked) =>
            refuse(
              ReleaseRefusal.ExitConfig,
              s"--release $asked disagrees with the release in ${args.fromDir}, which is ${release.release}"
            )
          case None =>
            val source = args.source.getOrElse(args.fromDir.toAbsolutePath.normalize.toString)
            val metadata =
              ReleaseMetadata.of(release, source, args.archiveSha256, producedBy, clock.instant())
            ReleaseLayout.write(release, args.fromDir, args.out, metadata) match
              case Left(problem) => refuse(ExitOutput, problem)
              case Right(written) =>
                release.warnings.foreach(w => stderr.println(s"ephemery-fetch: WARNING: $w"))
                written.notes.foreach(n => stderr.println(s"ephemery-fetch: WARNING: $n"))
                stdout.print(written.env)
                stderr.println(
                  s"ephemery-fetch: wrote ${written.dest} (chain id ${release.chainId}, genesis ${release.genesisTimestamp}, " +
                    s"genesis hash ${release.genesisHashHex})"
                )
                stderr.println(s"ephemery-fetch: next reset: ${java.time.Instant.ofEpochSecond(release.resetTime)}")
                0

  /** The command as a `cli` subcommand, for `fukuii cli --help` only: it does nothing. The launcher runs
    * `ephemery-fetch` itself ([[main]]), because it needs the exit code, which a `Command[String]` cannot carry.
    */
  def helpStub: Command[String] =
    Command(name = CommandName, header = command.header)(
      opts.map(_ => s"run `fukuii cli $CommandName` through the launcher: it exits with the class of a refusal")
    )
