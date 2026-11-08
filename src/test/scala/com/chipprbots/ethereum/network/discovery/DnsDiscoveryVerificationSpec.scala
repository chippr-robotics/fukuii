package com.chipprbots.ethereum.network.discovery

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.network.discovery.DnsDiscovery.TxtResult
import com.chipprbots.ethereum.network.discovery.DnsDiscovery.WalkOutcome
import com.chipprbots.ethereum.network.discovery.DnsDiscovery.WalkSettings
import com.chipprbots.ethereum.network.discovery.DnsDiscovery.WalkSummary
import com.chipprbots.ethereum.network.discovery.DnsTreeFixture.Stub
import com.chipprbots.ethereum.network.discovery.DnsTreeFixture.Tree
import com.chipprbots.ethereum.network.discovery.Eip1459.DnsTree
import com.chipprbots.ethereum.testing.Tags.*

/** What the walk checks before it believes a record (EIP-1459, matching go-ethereum's `p2p/dnsdisc`): the root's
  * signature against the tree's key, every entry against the label it was fetched under, every ENR's own signature, and
  * the kind of entry in each subtree; and which links it follows. Every tree here is genuinely signed and hashed (see
  * [[DnsTreeFixture]]), each test breaks exactly one thing.
  */
class DnsDiscoveryVerificationSpec extends AnyFlatSpec with Matchers:

  given IORuntime = IORuntime.global

  private val domain = "tree.test"
  private val settings = WalkSettings(treeDeadline = 60.seconds, concurrency = 8)

  /** Walk `url` over `lookup`; the summary and every enode delivered. */
  private def walk(url: DnsTree, lookup: Stub): (WalkSummary, Set[String]) =
    val seen = ConcurrentHashMap.newKeySet[String]()
    val summary = DnsDiscovery
      .walkTree(url, lookup, settings, None, enode => IO(seen.add(enode)).void)
      .unsafeRunTimed(60.seconds)
      .getOrElse(fail("the walk did not return"))
    (summary, seen.asScala.toSet)

  private def enodes(nodes: Range): Set[String] = nodes.map(DnsTreeFixture.enode).toSet

  private def found(text: String): TxtResult = TxtResult.Found(text)

  private def textOf(records: Map[String, TxtResult], name: String): String = records(name) match
    case TxtResult.Found(text) => text
    case other                 => fail(s"no record at $name: $other")

  behavior.of("a verified tree")

  it should "deliver all of its nodes, report its sequence number and count no failures" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val tree = new Tree(domain, branches = 3, leavesPerBranch = 4, seq = 7L)

    val (summary, delivered) = walk(tree.url, new Stub(tree.records))

    summary.outcome shouldBe WalkOutcome.Completed
    summary.rejected shouldBe 0
    summary.seq shouldBe Some(7L)
    delivered shouldBe enodes(0 until 12)
  }

  behavior.of("the root")

  it should "reject the whole tree when the root text was changed after it was signed" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val tree = new Tree(domain, branches = 2, leavesPerBranch = 3, seq = 1L)
    val tampered = tree.rootRecord.replace(" seq=1 ", " seq=2 ")
    tampered should not be tree.rootRecord
    val lookup = new Stub(tree.records.updated(domain, found(tampered)))

    val (summary, delivered) = walk(tree.url, lookup)

    summary.outcome shouldBe a[WalkOutcome.Failed]
    summary.rejected shouldBe 1
    summary.resolved shouldBe 0
    delivered shouldBe empty
    // nothing under an untrusted root is even fetched
    lookup.asked.get shouldBe 1
  }

  it should "reject the whole tree when it is signed by a key other than the one in the tree's URL" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val tree = new Tree(domain, branches = 2, leavesPerBranch = 3, keySeed = 1)
    val wrongKey = DnsTreeFixture.urlOf(domain, keySeed = 2)

    val (summary, delivered) = walk(wrongKey, new Stub(tree.records))

    summary.outcome shouldBe a[WalkOutcome.Failed]
    summary.rejected shouldBe 1
    delivered shouldBe empty
  }

  it should "reject a root whose signature is not 65 bytes" taggedAs (UnitTest, NetworkTest) in {
    val tree = new Tree(domain, branches = 2, leavesPerBranch = 3)
    val signature = Eip1459.parseRoot(tree.rootRecord).toOption.get.signature
    val short = Base64.getUrlEncoder.withoutPadding.encodeToString(signature.take(64).toArray)
    val withShortSignature = tree.rootRecord.take(tree.rootRecord.indexOf(" sig=") + 1) + s"sig=$short"
    withShortSignature should not be tree.rootRecord
    val lookup = new Stub(tree.records.updated(domain, found(withShortSignature)))

    val (summary, delivered) = walk(tree.url, lookup)

    summary.outcome shouldBe a[WalkOutcome.Failed]
    summary.rejected shouldBe 1
    delivered shouldBe empty
  }

  it should "reject a root that is not in the v1 format" taggedAs (UnitTest, NetworkTest) in {
    val tree = new Tree(domain, branches = 1, leavesPerBranch = 1)
    val reordered = tree.rootRecord.replace("enrtree-root:v1 e=", "enrtree-root:v1 l=0 e=")

    val (summary, delivered) = walk(tree.url, new Stub(tree.records.updated(domain, found(reordered))))

    summary.outcome shouldBe a[WalkOutcome.Failed]
    summary.rejected shouldBe 1
    delivered shouldBe empty
  }

  behavior.of("an entry")

  it should "be dropped when it does not hash to the label it was fetched under" taggedAs (UnitTest, NetworkTest) in {
    val tree = new Tree(domain, branches = 2, leavesPerBranch = 3)
    // a perfectly valid, correctly signed ENR, but not the one this label names
    val swapped = tree.records.updated(tree.leafName(0, 0), found(DnsTreeFixture.enr(99)))

    val (summary, delivered) = walk(tree.url, new Stub(swapped))

    summary.outcome shouldBe WalkOutcome.Completed
    summary.rejected shouldBe 1
    summary.resolved shouldBe 5
    delivered shouldBe enodes(1 until 6)
  }

  it should "drop a branch whose content does not hash to its label, with its whole subtree" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val tree = new Tree(domain, branches = 3, leavesPerBranch = 4)
    // branch 2's text served under branch 1's label
    val swapped = tree.records.updated(tree.branchName(1), found(textOf(tree.records, tree.branchName(2))))

    val (summary, delivered) = walk(tree.url, new Stub(swapped))

    summary.outcome shouldBe WalkOutcome.Completed
    summary.rejected shouldBe 1
    summary.resolved shouldBe 8
    delivered shouldBe enodes(0 until 4) ++ enodes(8 until 12)
  }

  it should "be dropped when the label it is fetched under is shorter than a hash may be" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val tree = new Tree(domain, branches = 1, leavesPerBranch = 2)
    // an e= root whose children are 8-byte labels (13 base32 characters): go-ethereum's isValidHash wants 12 to 32 bytes
    val shortLabel = Eip1459.entryLabel("x").take(13)
    val records = tree.withRoots(s"${Eip1459.BranchPrefix}$shortLabel")

    val (summary, delivered) = walk(tree.url, new Stub(records))

    summary.rejected shouldBe 1
    delivered shouldBe empty
  }

  behavior.of("an ENR")

  it should "be dropped when its own signature does not verify, though the tree around it is intact" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val tree = new Tree(
      domain,
      branches = 2,
      leavesPerBranch = 3,
      enrOverrides = Map(3 -> DnsTreeFixture.enrWithBadSignature(3))
    )

    val (summary, delivered) = walk(tree.url, new Stub(tree.records))

    summary.outcome shouldBe WalkOutcome.Completed
    summary.rejected shouldBe 1
    summary.resolved shouldBe 5
    delivered shouldBe enodes(0 until 6) - DnsTreeFixture.enode(3)
  }

  it should "be dropped when it names no identity scheme" taggedAs (UnitTest, NetworkTest) in {
    val tree =
      new Tree(domain, branches = 2, leavesPerBranch = 3, enrOverrides = Map(4 -> DnsTreeFixture.enrWithoutScheme(4)))

    val (summary, delivered) = walk(tree.url, new Stub(tree.records))

    summary.rejected shouldBe 1
    delivered shouldBe enodes(0 until 6) - DnsTreeFixture.enode(4)
  }

  behavior.of("the kind of entry in a subtree")

  it should "reject an ENR in the link subtree" taggedAs (UnitTest, NetworkTest) in {
    val tree = new Tree(domain, branches = 1, leavesPerBranch = 2)
    val records = tree.withRoots(tree.eRootText, lText = DnsTreeFixture.enr(50))

    val (summary, delivered) = walk(tree.url, new Stub(records))

    summary.outcome shouldBe WalkOutcome.Completed
    summary.rejected shouldBe 1
    delivered shouldBe enodes(0 until 2)
  }

  it should "reject a link in the ENR subtree" taggedAs (UnitTest, NetworkTest) in {
    val tree = new Tree(domain, branches = 1, leavesPerBranch = 2)
    val link = DnsTreeFixture.urlOf("elsewhere.test", keySeed = 9).url
    val records = tree.withRoots(link)

    val (summary, delivered) = walk(tree.url, new Stub(records))

    summary.rejected shouldBe 1
    summary.trees shouldBe 1
    delivered shouldBe empty
  }

  behavior.of("links")

  it should "be followed, each linked tree verified against the key in its link" taggedAs (UnitTest, NetworkTest) in {
    val child = new Tree("child.test", 1, 3, firstNode = 10, keySeed = 2)
    val parent = new Tree(domain, 1, 3, keySeed = 1, links = Seq(child.url))

    val (summary, delivered) = walk(parent.url, DnsTreeFixture.stubOf(parent, child))

    summary.outcome shouldBe WalkOutcome.Completed
    summary.trees shouldBe 2
    summary.rejected shouldBe 0
    summary.seq shouldBe Some(parent.seq)
    delivered shouldBe enodes(0 until 3) ++ enodes(10 until 13)
  }

  it should "not be trusted past their key: a linked tree signed by another key is dropped, the rest kept" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val child = new Tree("child.test", 1, 3, firstNode = 10, keySeed = 2)
    val linkedWithWrongKey = DnsTreeFixture.urlOf("child.test", keySeed = 3)
    val parent = new Tree(domain, 1, 3, keySeed = 1, links = Seq(linkedWithWrongKey))

    val (summary, delivered) = walk(parent.url, DnsTreeFixture.stubOf(parent, child))

    summary.outcome shouldBe WalkOutcome.Completed
    summary.rejected shouldBe 1
    delivered shouldBe enodes(0 until 3)
  }

  it should "be walked once each when trees link to each other in a ring" taggedAs (UnitTest, NetworkTest) in {
    val a = new Tree("a.test", 1, 2, firstNode = 0, keySeed = 1, links = Seq(DnsTreeFixture.urlOf("b.test", 2)))
    val b = new Tree("b.test", 1, 2, firstNode = 10, keySeed = 2, links = Seq(DnsTreeFixture.urlOf("a.test", 1)))
    val lookup = DnsTreeFixture.stubOf(a, b)

    val (summary, delivered) = walk(a.url, lookup)

    summary.trees shouldBe 2
    summary.rejected shouldBe 0
    delivered shouldBe enodes(0 until 2) ++ enodes(10 until 12)
  }

  it should "stop at three links from the tree asked for" taggedAs (UnitTest, NetworkTest) in {
    // t0 -> t1 -> t2 -> t3 -> t4 -> t5: t3 is three links from t0, so t4 and t5 are not followed
    val chain = (0 to 5).map { i =>
      val next = if i < 5 then Seq(DnsTreeFixture.urlOf(s"t${i + 1}.test", keySeed = i + 2)) else Seq.empty
      new Tree(s"t$i.test", 1, 1, firstNode = i, keySeed = i + 1, links = next)
    }

    val (summary, delivered) = walk(chain.head.url, DnsTreeFixture.stubOf(chain*))

    summary.trees shouldBe 4
    delivered shouldBe enodes(0 until 4)
  }

  it should "stop at sixteen trees in all" taggedAs (UnitTest, NetworkTest) in {
    val leaves = (1 to 20).map(i => new Tree(s"leaf$i.test", 1, 1, firstNode = i, keySeed = 100 + i))
    val hub = new Tree(domain, 1, 1, keySeed = 1, links = leaves.map(_.url))

    val (summary, delivered) = walk(hub.url, DnsTreeFixture.stubOf((hub +: leaves)*))

    summary.trees shouldBe 16
    summary.rejected shouldBe 0
    delivered.size shouldBe 16
  }
