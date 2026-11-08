package com.chipprbots.ethereum.network.discovery

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.concurrent.Await
import scala.concurrent.Promise
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.LongPatience
import com.chipprbots.ethereum.network.discovery.DnsDiscovery.TxtResult
import com.chipprbots.ethereum.network.discovery.DnsDiscovery.WalkOutcome
import com.chipprbots.ethereum.network.discovery.DnsDiscovery.WalkSettings
import com.chipprbots.ethereum.network.discovery.DnsDiscovery.WalkSummary
import com.chipprbots.ethereum.network.discovery.DnsTreeFixture.Stub
import com.chipprbots.ethereum.testing.Tags.*

/** How a DNS tree is walked (H9): in parallel at a bounded width, streamed to the caller, under a deadline, with lost
  * queries counted and nothing ever thrown. All against an in-memory tree, so no DNS and no real waiting: the stub
  * blocks on latches, and the only clock involved is the deadline of the one test that is about the deadline.
  */
class DnsDiscoveryWalkSpec extends AnyFlatSpec with Matchers with Eventually with LongPatience:

  given IORuntime = IORuntime.global

  private val domain = "tree.test"
  private val generous = WalkSettings(treeDeadline = 60.seconds, concurrency = 8)

  /** A sink that remembers each enode it is given, in a set safe to read while the walk is still running. */
  private class Collector:
    private val seen = ConcurrentHashMap.newKeySet[String]()
    val onEnode: String => IO[Unit] = enode => IO(seen.add(enode)).void
    def enodes: Set[String] = seen.asScala.toSet

  /** Run a walk to its end. A walk that never returns fails the test instead of hanging the suite. */
  private def walk(
      lookup: DnsDiscovery.TxtLookup,
      settings: WalkSettings = generous,
      collector: Collector = new Collector
  ): WalkSummary =
    DnsDiscovery
      .walkTree(domain, lookup, settings, None, collector.onEnode)
      .unsafeRunTimed(60.seconds)
      .getOrElse(fail("the walk did not return"))

  private def expectedEnodes(count: Int): Set[String] = (0 until count).map(DnsTreeFixture.enode).toSet

  behavior.of("DnsDiscovery.walkTree")

  it should "deliver every enode of the tree" taggedAs (UnitTest, NetworkTest) in {
    val collector = new Collector
    val summary =
      walk(new Stub(DnsTreeFixture.records(domain, branches = 4, leavesPerBranch = 5)), collector = collector)

    collector.enodes shouldBe expectedEnodes(20)
    summary.outcome shouldBe WalkOutcome.Completed
    summary.resolved shouldBe 20
    summary.lost shouldBe 0
    // root + ROOT + 4 branches + 20 leaves
    summary.queries shouldBe 26
  }

  it should "query in parallel, but never more at once than the concurrency bound" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val concurrency = 4
    val inFlight = new AtomicInteger(0)
    val peak = new AtomicInteger(0)
    val allArrived = new CountDownLatch(concurrency)
    val lookup = new Stub(
      DnsTreeFixture.records(domain, branches = 6, leavesPerBranch = 5),
      name =>
        if DnsTreeFixture.isLeaf(name) then
          peak.accumulateAndGet(inFlight.incrementAndGet(), (a: Int, b: Int) => math.max(a, b))
          allArrived.countDown()
          // Hold the first leaves until `concurrency` of them are in flight together. A walk that is not parallel
          // never gets there and waits out the 2 s, which is what fails it below.
          allArrived.await(2, TimeUnit.SECONDS)
          inFlight.decrementAndGet()
    )

    walk(lookup, WalkSettings(treeDeadline = 60.seconds, concurrency = concurrency)).resolved shouldBe 30
    peak.get shouldBe concurrency
  }

  it should "hand over enodes while the walk is still running" taggedAs (UnitTest, NetworkTest) in {
    val gate = new CountDownLatch(1)
    val lastLeaf = DnsTreeFixture.leafName(domain, branch = 3, leaf = 4)
    val lookup = new Stub(
      DnsTreeFixture.records(domain, branches = 4, leavesPerBranch = 5),
      name => if name == lastLeaf then gate.await(30, TimeUnit.SECONDS)
    )
    val allButOne = Promise[Unit]()
    val received = new AtomicInteger(0)
    val summary = DnsDiscovery
      .walkTree(
        domain,
        lookup,
        generous,
        None,
        _ => IO(if received.incrementAndGet() == 19 then allButOne.trySuccess(())).void
      )
      .unsafeToFuture()

    Await.result(allButOne.future, 30.seconds)
    withClue("19 of 20 enodes arrived, one query is still held: ") {
      summary.isCompleted shouldBe false
    }
    gate.countDown()
    Await.result(summary, 30.seconds).resolved shouldBe 20
  }

  it should "give up at the deadline, keep what it found, and interrupt the query it was waiting on" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val stuckLeaf = DnsTreeFixture.leafName(domain, branch = 3, leaf = 4)
    val never = new CountDownLatch(1)
    val interrupted = new AtomicBoolean(false)
    val lookup = new Stub(
      DnsTreeFixture.records(domain, branches = 4, leavesPerBranch = 5),
      name =>
        if name == stuckLeaf then
          try never.await()
          catch
            case e: InterruptedException =>
              interrupted.set(true)
              throw e
    )
    val collector = new Collector

    val summary = walk(lookup, WalkSettings(treeDeadline = 2.seconds, concurrency = 8), collector)

    summary.outcome shouldBe WalkOutcome.TimedOut
    summary.resolved shouldBe 19
    collector.enodes.size shouldBe 19
    summary.elapsed.toSeconds should be < 30L
    eventually(interrupted.get shouldBe true)
  }

  it should "count lost queries, drop only the subtree under a lost branch, and still complete" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val records = DnsTreeFixture.records(domain, branches = 4, leavesPerBranch = 5)
    val lookup = new Stub(
      records
        .updated(DnsTreeFixture.branchName(domain, 1), TxtResult.Lost("no reply"))
        .updated(DnsTreeFixture.leafName(domain, branch = 2, leaf = 0), TxtResult.Lost("no reply"))
    )

    val summary = walk(lookup)

    summary.outcome shouldBe WalkOutcome.Completed
    summary.lost shouldBe 2
    // 20 leaves, minus branch 1's five, minus the one lost leaf
    summary.resolved shouldBe 14
  }

  it should "treat a lookup that throws as a lost query, not as a failure of the walk" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val boom = DnsTreeFixture.leafName(domain, branch = 0, leaf = 0)
    val lookup = new Stub(
      DnsTreeFixture.records(domain, branches = 2, leavesPerBranch = 3),
      name => if name == boom then throw new IllegalStateException("resolver exploded")
    )

    val summary = walk(lookup)

    summary.outcome shouldBe WalkOutcome.Completed
    summary.lost shouldBe 1
    summary.resolved shouldBe 5
  }

  it should "lose only the one enode whose hand-over fails" taggedAs (UnitTest, NetworkTest) in {
    val failing = DnsTreeFixture.enode(0)
    val collector = new Collector
    val sink: String => IO[Unit] = enode =>
      if enode == failing then IO.raiseError(new IllegalStateException("sink is down")) else collector.onEnode(enode)

    val summary = DnsDiscovery
      .walkTree(domain, new Stub(DnsTreeFixture.records(domain, 2, 3)), generous, None, sink)
      .unsafeRunTimed(60.seconds)
      .getOrElse(fail("the walk did not return"))

    summary.outcome shouldBe WalkOutcome.Completed
    collector.enodes shouldBe expectedEnodes(6) - failing
  }

  it should "end with Failed, delivering nothing, when the root cannot be used" taggedAs (UnitTest, NetworkTest) in {
    val roots = Seq(
      "missing" -> Map.empty[String, TxtResult],
      "lost" -> Map(domain -> TxtResult.Lost("no reply")),
      "unparsable" -> Map(domain -> TxtResult.Found("enrtree-root:v1 l=LINKS seq=1")),
      "not an enrtree" -> Map(domain -> TxtResult.Found("v=spf1 -all"))
    )
    roots.foreach { case (label, records) =>
      withClue(s"$label root: ") {
        val collector = new Collector
        val summary = walk(new Stub(records), collector = collector)
        summary.outcome shouldBe a[WalkOutcome.Failed]
        summary.resolved shouldBe 0
        collector.enodes shouldBe empty
      }
    }
  }

  it should "stop at the per-tree record cap" taggedAs (UnitTest, NetworkTest) in {
    val collector = new Collector
    // 26 * 20 = 520 leaves, 20 over the cap of 500
    val summary =
      walk(new Stub(DnsTreeFixture.records(domain, branches = 26, leavesPerBranch = 20)), collector = collector)

    summary.resolved shouldBe 500
    collector.enodes.size shouldBe 500
  }

  it should "walk a hash that is reachable twice, or from itself, only once" taggedAs (UnitTest, NetworkTest) in {
    val records = DnsTreeFixture
      .records(domain, branches = 1, leavesPerBranch = 2)
      .updated(s"ROOT.$domain", TxtResult.Found("enrtree-branch:B0,B0,ROOT"))
    val lookup = new Stub(records)

    walk(lookup).resolved shouldBe 2
    // root + ROOT + B0 + 2 leaves, each once
    lookup.asked.get shouldBe 5
  }

  behavior.of("DnsDiscovery.resolveEnodes")

  it should "return the enodes of a tree through the same walk" taggedAs (UnitTest, NetworkTest) in {
    val lookup = new Stub(DnsTreeFixture.records(domain, branches = 2, leavesPerBranch = 3))

    DnsDiscovery.resolveEnodes(domain, lookup = lookup) shouldBe expectedEnodes(6)
  }

  behavior.of("DnsDiscovery.seed")

  it should "walk every domain and deliver their nodes as parsed Node values" taggedAs (UnitTest, NetworkTest) in {
    val records = DnsTreeFixture.records("one.test", 2, 3) ++ DnsTreeFixture.records("two.test", 2, 3, firstNode = 6)
    val delivered = ConcurrentHashMap.newKeySet[Node]()

    val summaries = DnsDiscovery
      .seed(
        Seq("one.test", "two.test"),
        new Stub(records),
        generous,
        None,
        nodes => IO(nodes.foreach(delivered.add)).void
      )
      .unsafeRunTimed(60.seconds)
      .getOrElse(fail("the seed did not return"))

    summaries.map(_.domain) shouldBe List("one.test", "two.test")
    summaries.map(_.resolved) shouldBe List(6, 6)
    delivered.size shouldBe 12
    delivered.asScala.map(_.tcpPort).toSet shouldBe Set(30303)
  }
