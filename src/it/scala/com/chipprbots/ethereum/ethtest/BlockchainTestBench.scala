package com.chipprbots.ethereum.ethtest

import java.lang.management.ManagementFactory

import scala.io.Source

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
  *   com.chipprbots.ethereum.ethtest.BlockchainTestBench <fixture.json> [caseRegex] [repeat]
  * }}}
  * `scripts/bench/blockchain-test-bench.sh` resolves the classpath and applies hive's JVM flags.
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
      System.err.println("usage: BlockchainTestBench <fixture.json> [caseRegex] [repeat]")
      sys.exit(2)
    val file = args(0)
    val selector = if args.length > 1 then args(1).r else ".*".r
    val repeat = if args.length > 2 then args(2).toInt else 1

    val source = Source.fromFile(file)
    val json =
      try source.mkString
      finally source.close()
    val suite = parse(json).flatMap(_.as[BlockchainTestSuite]) match
      case Right(s) => s
      case Left(e)  => sys.error(s"cannot read $file: ${e.getMessage}")

    val selected = suite.tests.toSeq.filter { case (name, _) => selector.matches(name) }.sortBy(_._1)
    if selected.isEmpty then
      System.err.println(s"no case in $file matches '$selector'")
      sys.exit(2)

    val baseConfig = Config.blockchains.blockchainConfig
    val threads = ManagementFactory.getThreadMXBean
    var failures = 0
    for
      round <- 1 to repeat
      (name, test) <- selected
    do
      val gas = test.blocks.map(b => hexToBigInt(b.blockHeader.gasUsed)).sum
      val cpu0 = threads.getCurrentThreadCpuTime
      val t0 = System.nanoTime()
      val result = EthereumTestExecutor.executeTest(test, baseConfig)
      val seconds = (System.nanoTime() - t0) / 1e9
      val cpuSeconds = (threads.getCurrentThreadCpuTime - cpu0) / 1e9
      val mgasPerSecond = gas.toDouble / 1e6 / seconds
      val verdict = result match
        case Right(_) => "PASS"
        case Left(error) =>
          failures += 1
          s"FAIL ${error.linesIterator.take(3).mkString(" | ")}"
      println(
        f"BENCH round=$round%d case=$name%s gas=$gas%s seconds=$seconds%.2f cpu_seconds=$cpuSeconds%.2f " +
          f"mgas_per_s=$mgasPerSecond%.2f mgas_per_cpu_s=${gas.toDouble / 1e6 / cpuSeconds}%.2f $verdict%s"
      )

    sys.exit(if failures == 0 then 0 else 1)

  private def hexToBigInt(s: String): BigInt =
    val digits = s.stripPrefix("0x")
    if digits.isEmpty then BigInt(0) else if s.startsWith("0x") then BigInt(digits, 16) else BigInt(digits)
