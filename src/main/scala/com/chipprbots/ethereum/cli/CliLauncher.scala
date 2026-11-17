package com.chipprbots.ethereum.cli

import java.time.Clock

import scala.collection.immutable.ArraySeq

import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.ephemery.EphemeryFetchCli

//scalastyle:off
object CliLauncher:

  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit =
    val arguments: Seq[String] = ArraySeq.unsafeWrapArray(args)
    // The only `cli` command with exit codes (spec 028): a refusal exits 78, a failure to write 1, a usage error 2.
    ephemeryFetch(arguments, sys.env, Clock.systemUTC(), System.out, System.err) match
      case Some(code) => sys.exit(code)
      case None =>
        CliCommands.api.map(s => logger.info(s)).parse(arguments, sys.env) match
          case Left(help) => logger.warn(help.toString)
          case Right(_)   => ()

  /** Runs `ephemery-fetch` when it is the command; its exit code, or `None` for any other command. */
  private[ethereum] def ephemeryFetch(
      arguments: Seq[String],
      env: Map[String, String],
      clock: Clock,
      stdout: java.io.PrintStream,
      stderr: java.io.PrintStream
  ): Option[Int] =
    Option.when(arguments.headOption.contains(EphemeryFetchCli.CommandName))(
      EphemeryFetchCli.main(arguments.tail, env, clock, CliCommands.producedBy, stdout, stderr)
    )
