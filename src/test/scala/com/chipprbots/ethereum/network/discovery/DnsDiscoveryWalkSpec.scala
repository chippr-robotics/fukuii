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
import com.chipprbots.ethereum.network.discovery.DnsTreeFixture.Tree
import com.chipprbots.ethereum.testing.Tags.*

/** How a DNS tree is walked (H9): in parallel at a bounded width, streamed to the caller, under a deadline, with lost
  * queries counted and nothing ever thrown. All against an in-memory tree, so no DNS and no real waiting: the stub
  * blocks on latches, and the only clock involved is the deadline of the one test that is about the deadline. What the
  * walk verifies is in [[DnsDiscoveryVerificationSpec]].
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
      tree: Tree,
      lookup: DnsDiscovery.TxtLookup,
      settings: WalkSettings = generous,
      collector: Collector = new Collector
  ): WalkSummary =
    DnsDiscovery
      .walkTree(tree.url, lookup, settings, None, collector.onEnode)
      .unsafeRunTimed(60.seconds)
      .getOrElse(fail("the walk did not return"))

  private def expectedEnodes(count: Int): Set[String] = (0 until count).map(DnsTreeFixture.enode).toSet

  behavior.of("DnsDiscovery.walkTree")

  it should "deliver every enode of the tree" taggedAs (UnitTest, NetworkTest) in {
    val tree = new Tree(domain, branches = 4, leavesPerBranch = 5)
    val collector = new Collector

    val summary = walk(tree, new Stub(tree.records), collector = collector)

    collector.enodes shouldBe expectedEnodes(20)
    summary.outcome shouldBe WalkOutcome.Completed
    summary.resolved shouldBe 20
    summary.lost shouldBe 0
    summary.rejected shouldBe 0
    summary.seq shouldBe Some(1L)
    summary.trees shouldBe 1
    // root + e= root + 4 branches + 20 leaves + l= root
    summary.queries shouldBe 27
  }

  it should "query in parallel, but never more at once than the concurrency bound" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val concurrency = 4
    val tree = new Tree(domain, branches = 6, leavesPerBranch = 5)
    val inFlight = new AtomicInteger(0)
    val peak = new AtomicInteger(0)
    val allArrived = new CountDownLatch(concurrency)
    val lookup = new Stub(
      tree.records,
      name =>
        if tree.isLeaf(name) then
          peak.accumulateAndGet(inFlight.incrementAndGet(), (a: Int, b: Int) => math.max(a, b))
          allArrived.countDown()
          // Hold the first leaves until `concurrency` of them are in flight together. A walk that is not parallel
          // never gets there and waits out the 2 s, which is what fails it below.
          allArrived.await(2, TimeUnit.SECONDS)
          inFlight.decrementAndGet()
    )

    walk(tree, lookup, WalkSettings(treeDeadline = 60.seconds, concurrency = concurrency)).resolved shouldBe 30
    peak.get shouldBe concurrency
  }

  it should "hand over enodes while the walk is still running" taggedAs (UnitTest, NetworkTest) in {
    val tree = new Tree(domain, branches = 4, leavesPerBranch = 5)
    val gate = new CountDownLatch(1)
    val lastLeaf = tree.leafName(branch = 3, leaf = 4)
    val lookup = new Stub(
      tree.records,
      name => if name == lastLeaf then gate.await(30, TimeUnit.SECONDS)
    )
    val allButOne = Promise[Unit]()
    val received = new AtomicInteger(0)
    val summary = DnsDiscovery
      .walkTree(
        tree.url,
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
    val tree = new Tree(domain, branches = 4, leavesPerBranch = 5)
    val stuckLeaf = tree.leafName(branch = 3, leaf = 4)
    val never = new CountDownLatch(1)
    val interrupted = new AtomicBoolean(false)
    val lookup = new Stub(
      tree.records,
      name =>
        if name == stuckLeaf then
          try never.await()
          catch
            case e: InterruptedException =>
              interrupted.set(true)
              throw e
    )
    val collector = new Collector

    val summary = walk(tree, lookup, WalkSettings(treeDeadline = 2.seconds, concurrency = 8), collector)

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
    val tree = new Tree(domain, branches = 4, leavesPerBranch = 5)
    val lookup = new Stub(
      tree.records
        .updated(tree.branchName(1), TxtResult.Lost("no reply"))
        .updated(tree.leafName(branch = 2, leaf = 0), TxtResult.Lost("no reply"))
    )

    val summary = walk(tree, lookup)

    summary.outcome shouldBe WalkOutcome.Completed
    summary.lost shouldBe 2
    // 20 leaves, minus branch 1's five, minus the one lost leaf
    summary.resolved shouldBe 14
  }

  it should "treat a lookup that throws as a lost query, not as a failure of the walk" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val tree = new Tree(domain, branches = 2, leavesPerBranch = 3)
    val boom = tree.leafName(branch = 0, leaf = 0)
    val lookup = new Stub(
      tree.records,
      name => if name == boom then throw new IllegalStateException("resolver exploded")
    )

    val summary = walk(tree, lookup)

    summary.outcome shouldBe WalkOutcome.Completed
    summary.lost shouldBe 1
    summary.resolved shouldBe 5
  }

  it should "lose only the one enode whose hand-over fails" taggedAs (UnitTest, NetworkTest) in {
    val tree = new Tree(domain, branches = 2, leavesPerBranch = 3)
    val failing = DnsTreeFixture.enode(0)
    val collector = new Collector
    val sink: String => IO[Unit] = enode =>
      if enode == failing then IO.raiseError(new IllegalStateException("sink is down")) else collector.onEnode(enode)

    val summary = DnsDiscovery
      .walkTree(tree.url, new Stub(tree.records), generous, None, sink)
      .unsafeRunTimed(60.seconds)
      .getOrElse(fail("the walk did not return"))

    summary.outcome shouldBe WalkOutcome.Completed
    collector.enodes shouldBe expectedEnodes(6) - failing
  }

  it should "end with Failed, delivering nothing, when the root cannot be fetched or is not a root" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val tree = new Tree(domain, branches = 1, leavesPerBranch = 1)
    val roots = Seq(
      "missing" -> Map.empty[String, TxtResult],
      "lost" -> Map(domain -> TxtResult.Lost("no reply")),
      "not an enrtree" -> Map(domain -> TxtResult.Found("v=spf1 -all"))
    )
    roots.foreach { case (label, records) =>
      withClue(s"$label root: ") {
        val collector = new Collector
        val summary = walk(tree, new Stub(records), collector = collector)
        summary.outcome shouldBe a[WalkOutcome.Failed]
        summary.resolved shouldBe 0
        collector.enodes shouldBe empty
      }
    }
  }

  it should "stop at the per-walk record cap" taggedAs (UnitTest, NetworkTest) in {
    val collector = new Collector
    // 26 * 20 = 520 leaves, 20 over the cap of 500
    val tree = new Tree(domain, branches = 26, leavesPerBranch = 20)

    val summary = walk(tree, new Stub(tree.records), collector = collector)

    summary.resolved shouldBe 500
    collector.enodes.size shouldBe 500
  }

  it should "walk a hash that is reachable twice only once" taggedAs (UnitTest, NetworkTest) in {
    val tree = new Tree(domain, branches = 1, leavesPerBranch = 2)
    val b0 = tree.eRootText.stripPrefix(Eip1459.BranchPrefix) // the label of the tree's only branch
    // the same branch listed twice under the e= root; the root is re-signed over the new subtree
    val twice = s"${Eip1459.BranchPrefix}$b0,$b0"
    val lookup = new Stub(tree.withRoots(twice))

    walk(tree, lookup).resolved shouldBe 2
    // root + e= root + branch + 2 leaves + l= root, each once
    lookup.asked.get shouldBe 6
  }

  behavior.of("DnsDiscovery.resolveEnodes")

  it should "return the enodes of a tree through the same walk" taggedAs (UnitTest, NetworkTest) in {
    val tree = new Tree(domain, branches = 2, leavesPerBranch = 3)

    DnsDiscovery.resolveEnodes(tree.url.url, lookup = new Stub(tree.records)) shouldBe expectedEnodes(6)
  }

  it should "resolve nothing for a domain it holds no key for" taggedAs (UnitTest, NetworkTest) in {
    val tree = new Tree(domain, branches = 1, leavesPerBranch = 1)
    val lookup = new Stub(tree.records)

    DnsDiscovery.resolveEnodes(domain, lookup = lookup) shouldBe empty
    lookup.asked.get shouldBe 0
  }

  behavior.of("DnsDiscovery.seed")

  it should "walk every tree and deliver their nodes as parsed Node values" taggedAs (UnitTest, NetworkTest) in {
    val one = new Tree("one.test", 2, 3)
    val two = new Tree("two.test", 2, 3, firstNode = 6, keySeed = 2)
    val delivered = ConcurrentHashMap.newKeySet[Node]()

    val summaries = DnsDiscovery
      .seed(
        Seq(one.url, two.url),
        DnsTreeFixture.stubOf(one, two),
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

  behavior.of("DnsDiscovery.concatenateTxtStrings")

  /** An entry stored as two TXT strings, the way JNDI renders it: the first 255 bytes, one space, the rest. */
  private def asJndiRenders(entry: String): String = entry.take(255) + " " + entry.drop(255)

  it should "restore a branch whose child hash was cut by the 255-byte string boundary" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    // 13 children of 26 characters, the shape of an ETC or Mordor branch: 365 bytes, so byte 255 is inside child 8.
    val hashes = (0 until 13).map(i => f"CHILD$i%02d" + "A" * 19)
    val branch = hashes.mkString("enrtree-branch:", ",", "")
    branch.length shouldBe 365

    val joined = asJndiRenders(branch)

    joined should not be branch
    joined.stripPrefix("enrtree-branch:").split(",") should contain(hashes(8).take(24) + " " + hashes(8).drop(24))
    DnsDiscovery.concatenateTxtStrings(joined) shouldBe branch
  }

  it should "restore an ENR cut by the string boundary, which would not even decode before" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val enr = DnsTreeFixture.enr(0)
    val joined = enr.take(100) + " " + enr.drop(100)

    DnsDiscovery.parseEnrToEnode(joined).isLeft shouldBe true
    DnsDiscovery.parseEnrToEnode(DnsDiscovery.concatenateTxtStrings(joined)) shouldBe Right(DnsTreeFixture.enode(0))
  }

  it should "leave the root alone, its fields are separated by spaces" taggedAs (UnitTest, NetworkTest) in {
    val root = "enrtree-root:v1 e=ROOT l=LINKS seq=1 sig=AAAA"

    DnsDiscovery.concatenateTxtStrings(root) shouldBe root
  }
