package com.chipprbots.ethereum.ledger

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*
import scala.util.Using

import org.json4s.*
import org.json4s.native.JsonMethods.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.KzgTestSetup
import com.chipprbots.ethereum.testing.Tags.*

/** Replays a whole execution-specs `blockchain_tests` corpus through [[EestBlockchainReplay]].
  *
  * The corpus is not vendored: a release is ~1 GB, and the Amsterdam-fork subset alone is 2.3 GB unpacked. Fetch it
  * with `scripts/eest/fetch_fixtures.py DIR` (tests@v21.0.0's Amsterdam and BPO2→Amsterdam blockchain tests by
  * default), then point this spec at it:
  *
  * {{{
  * EEST_FIXTURES=DIR sbt "testOnly *EestFixtureCorpusSpec"
  * }}}
  *
  * `EEST_FILTER` (a regex over the path below DIR, e.g. `eip8024`) narrows the run, `EEST_THREADS` sets the parallelism
  * (default: one per core), and `EEST_REPORT` moves the report (default `target/eest-report.txt`). The report groups
  * results by the two directories under `for_<fork>/` (e.g. `amsterdam/eip7928_block_level_access_lists`) and lists
  * every failing test with its first divergence. Each variable also has a `-Deest.*` system property form.
  *
  * With no corpus configured the test is canceled, not passed: a conformance claim needs the corpus to have run.
  */
class EestFixtureCorpusSpec extends AnyFlatSpec with Matchers:

  private def setting(name: String): Option[String] =
    sys.props.get(s"eest.${name.toLowerCase}").orElse(sys.env.get(s"EEST_$name")).filter(_.nonEmpty)

  private case class Outcome(file: String, test: String, divergences: Seq[String])

  /** Every error is a divergence of that one fixture, fatal ones included: a StackOverflowError from a 1024-deep call
    * chain or a LinkageError from a native precompile would otherwise escape `Try`, kill the worker and silently drop
    * the rest of its file from the count.
    */
  private def replayOne(t: JValue): Seq[String] =
    try EestBlockchainReplay.replay(t)
    catch case e: Throwable => Seq(s"threw $e")

  /** The node runs the EVM on threads sized by `.jvmopts` (-Xss4M); a pool thread's default stack is smaller than a
    * 1024-frame call chain needs, so workers get the node's headroom and then some.
    */
  private def workerFactory: java.util.concurrent.ThreadFactory =
    val counter = new java.util.concurrent.atomic.AtomicInteger()
    (r: Runnable) => new Thread(null, r, s"eest-replay-${counter.incrementAndGet()}", 64L * 1024 * 1024)

  /** `blockchain_tests/for_amsterdam/amsterdam/eip8024_dupn_swapn_exchange/x/y.json` → `amsterdam/eip8024_...`. */
  private def group(relative: String): String =
    val parts = relative.split('/').toSeq
    val afterFork = parts.indexWhere(_.startsWith("for_")) match
      case -1 => parts
      case i  => parts.drop(i + 1)
    afterFork.dropRight(1).take(2).mkString("/")

  "the execution-specs blockchain_test corpus" should "replay every fixture byte-for-byte" taggedAs (
    EthereumTest,
    SlowTest,
    ConsensusTest
  ) in {
    val root = setting("FIXTURES")
      .map(Paths.get(_))
      .getOrElse(cancel("no corpus configured: set EEST_FIXTURES (see scripts/eest/fetch_fixtures.py)"))
    assert(Files.isDirectory(root), s"EEST_FIXTURES=$root is not a directory")
    val filter = setting("FILTER").map(_.r)
    val threads = setting("THREADS").map(_.toInt).getOrElse(Runtime.getRuntime.availableProcessors)
    val reportPath = Paths.get(setting("REPORT").getOrElse("target/eest-report.txt"))

    val files: Seq[Path] = Using.resource(Files.walk(root)) { walk =>
      walk.iterator.asScala
        .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".json"))
        .filterNot(p => root.relativize(p).toString.split('/').exists(_.startsWith(".")))
        .filter(p => filter.forall(_.findFirstIn(root.relativize(p).toString).isDefined))
        .toSeq
        .sortBy(_.toString)
    }
    withClue(s"no fixture files under $root${filter.fold("")(f => s" matching $f")}: ") {
      files should not be empty
    }

    // The point-evaluation precompile (0x0a) needs the trusted setup the node loads at startup.
    KzgTestSetup.ensureLoaded()
    val outcomes = new ConcurrentLinkedQueue[Outcome]()
    val pool = Executors.newFixedThreadPool(threads, workerFactory)
    val started = System.nanoTime()
    files.foreach { file =>
      pool.execute { () =>
        val relative = root.relativize(file).toString
        try
          parse(Files.readString(file)) match
            case JObject(tests) =>
              tests.foreach { case (name, t) => outcomes.add(Outcome(relative, name, replayOne(t))) }
            case other => outcomes.add(Outcome(relative, "<file>", Seq(s"not a fixture object: ${other.getClass}")))
        catch case e: Throwable => outcomes.add(Outcome(relative, "<file>", Seq(s"unreadable: $e")))
      }
    }
    pool.shutdown()
    pool.awaitTermination(12, TimeUnit.HOURS) shouldBe true
    val seconds = (System.nanoTime() - started) / 1e9

    val all = outcomes.asScala.toSeq.sortBy(o => (o.file, o.test))
    val failed = all.filter(_.divergences.nonEmpty)
    val byGroup = all.groupBy(o => group(o.file)).toSeq.sortBy(_._1).map { case (g, os) =>
      f"  ${os.count(_.divergences.isEmpty)}%6d / ${os.size}%-6d $g"
    }
    val report = Seq(
      s"execution-specs blockchain_test replay: $root${filter.fold("")(f => s" (filter $f)")}",
      f"${all.size} tests in ${files.size} files, ${all.size - failed.size} passed, ${failed.size} failed, $seconds%.0f s",
      "",
      "passed / total by directory:"
    ) ++ byGroup ++ Seq("", "failures (first divergence):") ++
      failed.map(o => s"  ${o.file} :: ${o.test}\n      ${o.divergences.head}")
    Option(reportPath.getParent).foreach(Files.createDirectories(_))
    Files.write(reportPath, report.asJava)
    info(report.take(4 + byGroup.size).mkString("\n"))

    withClue(s"${failed.size} of ${all.size} fixtures diverge; full list in $reportPath. First: ") {
      failed.take(20).map(o => s"${o.file} :: ${o.test}: ${o.divergences.head}") shouldBe empty
    }
  }
