package com.chipprbots.ethereum.consensus.engine

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import scala.io.Source
import scala.jdk.CollectionConverters.*
import scala.util.Using

import org.json4s.*
import org.json4s.native.JsonMethods.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.KzgTestSetup
import com.chipprbots.ethereum.testing.Tags.*

/** The Amsterdam payload builder against execution-specs (#1427): every block an execution-specs `blockchain_test`
  * accepts is rebuilt by [[EngineApiService.buildBlockOnParent]] from its parent and its own attributes, transactions,
  * extra data and gas limit, and must come out with execution-specs' block hash ([[EestBuilderReplay]]). Where
  * EngineApiAmsterdamBuildSpec shows that a second fukuii node accepts what the builder makes, this shows that what the
  * builder makes is what the reference makes.
  *
  * The vendored fixtures (`eest-regression/amsterdam-builder.json`) are eight tests@v21.0.0 `blockchain_tests`, trimmed
  * to the fields the replay reads (see EestBuilderReplay), each chosen for a part of the header the builder derives:
  *   - `eip7843_slotnum/slotnum_value[slot_max_u64]`: SLOTNUM executes with slot 2^64-1, which the header encodes as 8
  *     bytes;
  *   - `eip7843_slotnum/slotnum_distinct_per_block`: four blocks, a different slot each;
  *   - `eip8282/eip_8282[single_builder_deposit_and_exit_request]`: both EIP-8282 request types in one requestsHash;
  *   - `eip8282/requests_exhaust_block_gas[BuilderDepositRequest-full_block]`: eight transactions filling the block's
  *     gas, then the builder-deposit dequeue;
  *   - `eip7928/bal_withdrawals_and_dequeues_net_balance_at_last_index[forward_all]`: withdrawals and request system
  *     calls sharing the last block access index;
  *   - `eip7708/transfer_with_all_tx_types[typed_transaction_3, typed_transaction_4]`: a blob and a set-code
  *     transaction, each with a 120,000,000 gas limit, so an EIP-8037 state-gas reservoir;
  *   - `eip8037/multi_block_mixed_state_operations`: three blocks of state creation above 2^24 gas.
  *
  * With `EEST_FIXTURES` set (fetch with `scripts/eest/fetch_fixtures.py`, as for EestFixtureCorpusSpec), the second
  * test does the same for every fixture under it: `EEST_FILTER` narrows the run, `EEST_THREADS` sets the parallelism
  * and `EEST_BUILDER_REPORT` moves the report (default `target/eest-builder-report.txt`). Without a corpus it is
  * canceled.
  */
class EngineApiAmsterdamBuilderEestSpec extends AnyFlatSpec with Matchers:

  private def setting(name: String): Option[String] =
    sys.props.get(s"eest.${name.toLowerCase}").orElse(sys.env.get(s"EEST_$name")).filter(_.nonEmpty)

  "The Amsterdam payload builder" should "reproduce execution-specs' block hash for every block of the vendored fixtures" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    KzgTestSetup.ensureLoaded()
    val source = Source.fromResource("eest-regression/amsterdam-builder.json")
    val fixtures =
      try
        parse(source.mkString) match
          case JObject(tests) => tests
          case other          => fail(s"not a fixture file: ${other.getClass}")
      finally source.close()

    fixtures should have size 8
    val rebuilt = fixtures.map { case (name, t) =>
      val outcome = EestBuilderReplay.rebuild(t)
      withClue(s"$name: ")(outcome.divergences shouldBe empty)
      outcome.blocksRebuilt
    }
    // Every fixture block is one execution-specs accepts: 1 + 4 + 2 + 1 + 1 + 1 + 1 + 3.
    rebuilt.sum shouldBe 14
  }

  it should "reproduce execution-specs' block hash for every block of a configured corpus" taggedAs (
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
    val reportPath = Paths.get(setting("BUILDER_REPORT").getOrElse("target/eest-builder-report.txt"))

    val files: Seq[Path] = Using.resource(Files.walk(root)) { walk =>
      walk.iterator.asScala
        .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".json"))
        .filterNot(p => root.relativize(p).toString.split('/').exists(_.startsWith(".")))
        .filter(p => filter.forall(_.findFirstIn(root.relativize(p).toString).isDefined))
        .toSeq
        .sortBy(_.toString)
    }
    withClue(s"no fixture files under $root${filter.fold("")(f => s" matching $f")}: ")(files should not be empty)

    KzgTestSetup.ensureLoaded()
    final case class Result(file: String, test: String, outcome: EestBuilderReplay.Outcome)
    val results = new ConcurrentLinkedQueue[Result]()
    // A worker's default stack is smaller than a 1024-frame call chain needs (see EestFixtureCorpusSpec).
    val counter = new java.util.concurrent.atomic.AtomicInteger()
    val pool = Executors.newFixedThreadPool(
      threads,
      (r: Runnable) => new Thread(null, r, s"eest-builder-${counter.incrementAndGet()}", 64L * 1024 * 1024)
    )
    val started = System.nanoTime()
    files.foreach { file =>
      pool.execute { () =>
        val relative = root.relativize(file).toString
        try
          parse(Files.readString(file)) match
            case JObject(tests) =>
              tests.foreach { case (name, t) =>
                val outcome =
                  try EestBuilderReplay.rebuild(t)
                  catch case e: Throwable => EestBuilderReplay.Outcome(0, Seq(s"threw $e"))
                results.add(Result(relative, name, outcome))
              }
            case other =>
              results.add(Result(relative, "<file>", EestBuilderReplay.Outcome(0, Seq(s"not a fixture: $other"))))
        catch
          case e: Throwable =>
            results.add(Result(relative, "<file>", EestBuilderReplay.Outcome(0, Seq(s"unreadable: $e"))))
      }
    }
    pool.shutdown()
    pool.awaitTermination(12, TimeUnit.HOURS) shouldBe true
    val seconds = (System.nanoTime() - started) / 1e9

    val all = results.asScala.toSeq.sortBy(r => (r.file, r.test))
    val failed = all.filter(_.outcome.divergences.nonEmpty)
    val blocks = all.map(_.outcome.blocksRebuilt).sum
    val report = Seq(
      s"payload builder rebuild of execution-specs blockchain_tests: $root${filter.fold("")(f => s" (filter $f)")}",
      f"${all.size} tests in ${files.size} files, $blocks blocks rebuilt, ${all.size - failed.size} passed, " +
        f"${failed.size} failed, $seconds%.0f s",
      "",
      "failures (first divergence):"
    ) ++ failed.map(r => s"  ${r.file} :: ${r.test}\n      ${r.outcome.divergences.head}")
    Option(reportPath.getParent).foreach(Files.createDirectories(_))
    Files.write(reportPath, report.asJava)
    info(report.take(2).mkString("\n"))

    withClue(s"${failed.size} of ${all.size} fixtures diverge; full list in $reportPath. First: ") {
      failed.take(20).map(r => s"${r.file} :: ${r.test}: ${r.outcome.divergences.head}") shouldBe empty
    }
  }
