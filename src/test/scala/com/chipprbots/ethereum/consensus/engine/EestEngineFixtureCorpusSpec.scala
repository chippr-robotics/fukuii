package com.chipprbots.ethereum.consensus.engine

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

/** Replays a whole execution-specs `blockchain_tests_engine` corpus through [[EestEngineReplay]]: every payload through
  * the real Engine API controller and service, checking each answer's status or error code.
  *
  * The corpus is not vendored. Fetch the Amsterdam engine fixtures of a release with
  *
  * {{{
  * scripts/eest/fetch_fixtures.py DIR \
  *   --prefix fixtures/blockchain_tests_engine/for_amsterdam/ \
  *   --prefix fixtures/blockchain_tests_engine/for_bpo2toamsterdamattime15k/
  * EEST_ENGINE_FIXTURES=DIR sbt "testOnly *EestEngineFixtureCorpusSpec"
  * }}}
  *
  * The settings mirror [[com.chipprbots.ethereum.ledger.EestFixtureCorpusSpec]]'s under their own prefix:
  * `EEST_ENGINE_FILTER` (a regex over the path below DIR), `EEST_ENGINE_THREADS` (default: one per core),
  * `EEST_ENGINE_REPORT` (default `target/eest-engine-report.txt`) and `EEST_ENGINE_MIN_TESTS` (fail when fewer tests
  * were replayed). Each also has a `-Deest.engine.*` form. The report groups results by `for_<fork>/<fork>/<test
  * directory>` and lists every failing test with its first divergence.
  *
  * Run it in its own sbt invocation, not together with EestFixtureCorpusSpec: every fixture's EphemBlockchainTestSetup,
  * with its whole in-memory chain, stays reachable until the JVM exits (the ShutdownHookBuilder in its cake registers a
  * shutdown hook that captures it), so the two ~26,500-fixture corpora together exhaust the default test heap, where
  * either one alone fits.
  *
  * With no corpus configured the test is canceled, not passed: a conformance claim needs the corpus to have run.
  */
class EestEngineFixtureCorpusSpec extends AnyFlatSpec with Matchers:

  private def setting(name: String): Option[String] =
    sys.props.get(s"eest.engine.${name.toLowerCase}").orElse(sys.env.get(s"EEST_ENGINE_$name")).filter(_.nonEmpty)

  private case class Outcome(file: String, test: String, divergences: Seq[String])

  /** Every error is a divergence of that one fixture, fatal ones included, as in EestFixtureCorpusSpec. */
  private def replayOne(t: JValue): Seq[String] =
    try EestEngineReplay.replay(t)
    catch case e: Throwable => Seq(s"threw $e")

  /** The node runs the EVM with `-Xss4M` (`.jvmopts`); replay workers get more than a 1024-frame call chain needs. */
  private def workerFactory: java.util.concurrent.ThreadFactory =
    val counter = new java.util.concurrent.atomic.AtomicInteger()
    (r: Runnable) => new Thread(null, r, s"eest-engine-replay-${counter.incrementAndGet()}", 64L * 1024 * 1024)

  /** `blockchain_tests_engine/for_amsterdam/amsterdam/eip7928_x/y/z.json` → `for_amsterdam/amsterdam/eip7928_x`. */
  private def group(relative: String): String =
    val parts = relative.split('/').toSeq
    val fromFork = parts.indexWhere(_.startsWith("for_")) match
      case -1 => parts
      case i  => parts.drop(i)
    fromFork.dropRight(1).take(3).mkString("/")

  "the execution-specs blockchain_test_engine corpus" should "replay every payload through the Engine API" taggedAs (
    EthereumTest,
    SlowTest,
    ConsensusTest
  ) in {
    val root = setting("FIXTURES")
      .map(Paths.get(_))
      .getOrElse(cancel("no corpus configured: set EEST_ENGINE_FIXTURES (see scripts/eest/fetch_fixtures.py)"))
    assert(Files.isDirectory(root), s"EEST_ENGINE_FIXTURES=$root is not a directory")
    val filter = setting("FILTER").map(_.r)
    val threads = setting("THREADS").map(_.toInt).getOrElse(Runtime.getRuntime.availableProcessors)
    val reportPath = Paths.get(setting("REPORT").getOrElse("target/eest-engine-report.txt"))

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
      s"execution-specs blockchain_test_engine replay: $root${filter.fold("")(f => s" (filter $f)")}",
      f"${all.size} tests in ${files.size} files, ${all.size - failed.size} passed, ${failed.size} failed, $seconds%.0f s",
      "",
      "passed / total by directory:"
    ) ++ byGroup ++ Seq("", "failures (first divergence):") ++
      failed.map(o => s"  ${o.file} :: ${o.test}\n      ${o.divergences.head}")
    Option(reportPath.getParent).foreach(Files.createDirectories(_))
    Files.write(reportPath, report.asJava)
    info(report.take(4 + byGroup.size).mkString("\n"))

    setting("MIN_TESTS").map(_.toInt).foreach { min =>
      withClue(s"only ${all.size} tests replayed where EEST_ENGINE_MIN_TESTS=$min: the corpus is incomplete. ") {
        all.size should be >= min
      }
    }
    withClue(s"${failed.size} of ${all.size} fixtures diverge; full list in $reportPath. First: ") {
      failed.take(20).map(o => s"${o.file} :: ${o.test}: ${o.divergences.head}") shouldBe empty
    }
  }
