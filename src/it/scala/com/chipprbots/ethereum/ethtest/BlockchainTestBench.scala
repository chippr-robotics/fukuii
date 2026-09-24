package com.chipprbots.ethereum.ethtest

import java.io.File
import java.lang.management.ManagementFactory

import scala.io.Source
import scala.util.control.NonFatal

import io.circe.parser.parse

import com.chipprbots.ethereum.utils.Config

/** Wall-clock EVM throughput harness for gas-heavy BlockchainTest fixtures, such as hive's `legacy/loopMul_*`.
  *
  * Each selected case goes through [[EthereumTestExecutor.executeTest]]: the ETH execution path
  * (`BlockExecution.executeAndValidateBlock`, the same `BlockPreparator` -> `VM` stack chain import uses), with the
  * block's gasUsed, receipts and state root checked against the fixture header and the fixture `postState` compared
  * afterwards. A case that runs fast but computes anything different fails here, so a throughput number is only printed
  * next to a byte-exact result.
  *
  * This is a measuring tool, not a test: it asserts nothing about time. It exits non-zero if any case fails validation.
  *
  * {{{
  * java -Xmx512m -Xss8M -XX:+UseG1GC -cp <it classpath> \
  *   com.chipprbots.ethereum.ethtest.BlockchainTestBench <fixture.json | directory> [caseRegex] [repeat]
  * }}}
  * `scripts/bench/blockchain-test-bench.sh` resolves the classpath and applies hive's JVM flags.
  *
  * Given a directory, every `*.json` below it is run, in path order, and each result line carries the file: two builds
  * run over the same ethereum/tests directory give two outcome lists that can be diffed. A file that does not decode as
  * a BlockchainTest suite is reported and skipped.
  *
  * `repeat` (default 1) re-runs every selected case in the same JVM. The first run is what hive sees (a cold JVM);
  * later runs show the JIT-warm rate.
  *
  * `cpu_seconds` is the CPU time of the thread that ran the case (JIT and GC threads excluded). On a shared machine it
  * moves much less with the load than wall time does, so compare it, not `seconds`, between two builds measured at
  * different times.
  */
object BlockchainTestBench:

  def main(args: Array[String]): Unit =
    if args.isEmpty then
      System.err.println("usage: BlockchainTestBench <fixture.json | directory> [caseRegex] [repeat]")
      sys.exit(2)
    val root = new File(args(0))
    val selector = if args.length > 1 then args(1).r else ".*".r
    val repeat = if args.length > 2 then args(2).toInt else 1

    val files =
      if root.isDirectory then jsonFilesUnder(root).sortBy(_.getPath)
      else Seq(root)
    val fileLabel: File => String =
      if root.isDirectory then f => s" file=${root.toPath.relativize(f.toPath)}" else _ => ""

    val baseConfig = Config.blockchains.blockchainConfig
    val threads = ManagementFactory.getThreadMXBean
    var failures = 0
    var cases = 0
    var unreadable = 0
    for
      round <- 1 to repeat
      file <- files
    do
      readSuite(file) match
        case Left(error) =>
          unreadable += 1
          println(s"UNREADABLE${fileLabel(file)} ${error.linesIterator.nextOption().getOrElse("")}")
        case Right(suite) =>
          val selected = suite.tests.toSeq.filter { case (name, _) => selector.matches(name) }.sortBy(_._1)
          for (name, test) <- selected do
            cases += 1
            val gas = test.blocks.map(b => hexToBigInt(b.blockHeader.gasUsed)).sum
            val cpu0 = threads.getCurrentThreadCpuTime
            val t0 = System.nanoTime()
            val result =
              try EthereumTestExecutor.executeTest(test, baseConfig)
              catch case NonFatal(e) => Left(s"threw ${e.getClass.getName}: ${e.getMessage}")
            val seconds = (System.nanoTime() - t0) / 1e9
            val cpuSeconds = (threads.getCurrentThreadCpuTime - cpu0) / 1e9
            val verdict = result match
              case Right(_) => "PASS"
              case Left(error) =>
                failures += 1
                s"FAIL ${error.linesIterator.take(3).mkString(" | ")}"
            println(
              f"BENCH round=$round%d${fileLabel(file)}%s case=$name%s gas=$gas%s seconds=$seconds%.2f " +
                f"cpu_seconds=$cpuSeconds%.2f mgas_per_s=${gas.toDouble / 1e6 / seconds}%.2f " +
                f"mgas_per_cpu_s=${gas.toDouble / 1e6 / cpuSeconds}%.2f $verdict%s"
            )

    if cases == 0 then
      System.err.println(s"no case under ${root.getPath} matches '$selector'")
      sys.exit(2)
    println(s"SUMMARY cases=$cases pass=${cases - failures} fail=$failures unreadable_files=$unreadable")
    sys.exit(if failures == 0 then 0 else 1)

  private def jsonFilesUnder(dir: File): Seq[File] =
    Option(dir.listFiles()).toSeq.flatten.flatMap { f =>
      if f.isDirectory then jsonFilesUnder(f)
      else if f.getName.endsWith(".json") then Seq(f)
      else Seq.empty
    }

  private def readSuite(file: File): Either[String, BlockchainTestSuite] =
    try
      val source = Source.fromFile(file)
      val json =
        try source.mkString
        finally source.close()
      parse(json).flatMap(_.as[BlockchainTestSuite]).left.map(_.getMessage)
    catch case NonFatal(e) => Left(s"${e.getClass.getName}: ${e.getMessage}")

  private def hexToBigInt(s: String): BigInt =
    val digits = s.stripPrefix("0x")
    if digits.isEmpty then BigInt(0) else if s.startsWith("0x") then BigInt(digits, 16) else BigInt(digits)
