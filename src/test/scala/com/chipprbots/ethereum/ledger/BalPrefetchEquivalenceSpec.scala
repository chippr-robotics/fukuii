package com.chipprbots.ethereum.ledger

import java.nio.file.Files
import java.nio.file.Paths

import scala.jdk.CollectionConverters.*
import scala.util.Using

import org.json4s.*
import org.json4s.native.JsonMethods.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.consensus.engine.EestEngineReplay
import com.chipprbots.ethereum.crypto.KzgTestSetup
import com.chipprbots.ethereum.testing.Tags.*

/** The same Amsterdam payloads, through the real Engine API, with the BAL prefetch off and on: every fixture must give
  * the same verdict (the replay checks status, error code and the head hash against the release), and the "on" run must
  * actually have prefetched. A sample of the `eip7928` fixtures, so it runs in a minute; the whole corpus passes with
  * the prefetch on by default in `EestEngineFixtureCorpusSpec`.
  *
  * Needs `EEST_ENGINE_FIXTURES` (see `scripts/eest/fetch_fixtures.py`); canceled without it.
  */
class BalPrefetchEquivalenceSpec extends AnyFlatSpec with Matchers:

  "the BAL prefetch" should "not change the outcome of any Engine API payload" taggedAs (
    EthereumTest,
    SlowTest,
    ConsensusTest
  ) in {
    val root = sys.env
      .get("EEST_ENGINE_FIXTURES")
      .filter(_.nonEmpty)
      .map(Paths.get(_))
      .getOrElse(cancel("no corpus configured: set EEST_ENGINE_FIXTURES"))
    val files = Using.resource(Files.walk(root)) { walk =>
      walk.iterator.asScala
        .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".json") && p.toString.contains("eip7928"))
        .toSeq
        .sortBy(_.toString)
        .take(60)
    }
    files should not be empty
    KzgTestSetup.ensureLoaded()
    val tests = files.flatMap(f =>
      parse(Files.readString(f)) match
        case JObject(ts) => ts.map { case (n, t) => (s"${root.relativize(f)} :: $n", t) }
        case _           => Nil
    )
    def run(disabled: Boolean): Map[String, Seq[String]] =
      BalPrefetcher.disabled = disabled
      try
        tests.map { case (name, t) =>
          name -> (try EestEngineReplay.replay(t)
          catch case e: Throwable => Seq(s"threw $e"))
        }.toMap
      finally BalPrefetcher.disabled = false
    val off = run(disabled = true)
    val runsBefore = BalPrefetcher.runsStarted.get
    val on = run(disabled = false)
    (BalPrefetcher.runsStarted.get - runsBefore) should be > 0L
    on shouldBe off
    on.values.flatten.toSeq shouldBe empty
  }
