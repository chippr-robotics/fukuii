package com.chipprbots.ethereum.network.discovery

import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorRef

import cats.effect.IO
import cats.effect.Resource
import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.*
import scala.math.Ordering.Implicits.*
import scala.util.control.NoStackTrace

import com.chipprbots.scalanet.discovery.crypto.PublicKey
import com.chipprbots.scalanet.discovery.ethereum.Node as ENode
import com.chipprbots.scalanet.discovery.ethereum.v4.DiscoveryService
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import scodec.bits.BitVector

import com.chipprbots.ethereum.LongPatience
import com.chipprbots.ethereum.db.storage.KnownNodesStorage
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config

class PeerDiscoveryManagerSpec extends AnyFlatSpecLike with Matchers with Eventually with MockFactory with LongPatience:

  given runtime: IORuntime = IORuntime.global

  val defaultConfig: DiscoveryConfig = DiscoveryConfig(Config.config, bootstrapNodes = Set.empty)

  val sampleKnownUris: Set[URI] = Set(
    "enode://a59e33ccd2b3e52d578f1fbd70c6f9babda2650f0760d6ff3b37742fdcdfdb3defba5d56d315b40c46b70198c7621e63ffa3f987389c7118634b0fefbbdfa7fd@51.158.191.43:38556?discport=38556",
    "enode://651b484b652c07c72adebfaaf8bc2bd95b420b16952ef3de76a9c00ef63f07cca02a20bd2363426f9e6fe372cef96a42b0fec3c747d118f79fd5e02f2a4ebd4e@51.158.190.99:45678?discport=45678",
    "enode://9b1bf9613d859ac2071d88509ab40a111b75c1cfc51f4ad78a1fdbb429ff2405de0dc5ea8ae75e6ac88e03e51a465f0b27b517e78517f7220ae163a2e0692991@51.158.190.99:30426?discport=30426"
  ).map(new java.net.URI(_))

  val sampleNodes: Set[Node] = Set(
    "enode://111bd28d5b2c1378d748383fd83ff59572967c317c3063a9f475a26ad3f1517642a164338fb5268d4e32ea1cc48e663bd627dec572f1d201c7198518e5a506b1@88.99.216.30:45834?discport=45834",
    "enode://2b69a3926f36a7748c9021c34050be5e0b64346225e477fe7377070f6289bd363b2be73a06010fd516e6ea3ee90778dd0399bc007bb1281923a79374f842675a@51.15.116.226:30303?discport=30303"
  ).map(new java.net.URI(_)).map(Node.fromUri)

  trait Fixture:
    val testKit: ActorTestKit = ActorTestKit()
    lazy val discoveryConfig: DiscoveryConfig = defaultConfig
    lazy val knownNodesStorage: KnownNodesStorage = mock[KnownNodesStorage]
    lazy val discoveryService: DiscoveryService = mock[DiscoveryService]
    lazy val discoveryServiceResource: Resource[IO, DiscoveryService] =
      Resource.pure[IO, DiscoveryService](discoveryService)

    // Never reached unless the config has DNS domains; the tests that give it some replace it with an in-memory tree.
    lazy val dnsLookup: DnsDiscovery.TxtLookup = DnsDiscovery.JndiTxtLookup

    lazy val peerDiscoveryManager: ActorRef[PeerDiscoveryManager.Command] =
      testKit.spawn(
        PeerDiscoveryManager(
          localNodeId = org.apache.pekko.util.ByteString.fromString("test-node"),
          discoveryConfig = discoveryConfig,
          knownNodesStorage = knownNodesStorage,
          discoveryServiceResource = discoveryServiceResource,
          dnsLookup = dnsLookup
        )
      )

    /** Send GetDiscoveredNodesInfoReq and wait for the response. */
    def getPeers(timeout: FiniteDuration = 3.seconds): PeerDiscoveryManager.DiscoveredNodesInfo =
      val probe = testKit.createTestProbe[PeerDiscoveryManager.DiscoveredNodesInfo]()
      peerDiscoveryManager ! PeerDiscoveryManager.GetDiscoveredNodesInfoReq(probe.ref)
      probe.receiveMessage(timeout)

    /** Send GetRandomNodeInfoReq and wait for the response. Throws on timeout. */
    def getRandomPeer(timeout: FiniteDuration = 8.seconds): PeerDiscoveryManager.RandomNodeInfo =
      val probe = testKit.createTestProbe[PeerDiscoveryManager.RandomNodeInfo]()
      peerDiscoveryManager ! PeerDiscoveryManager.GetRandomNodeInfoReq(probe.ref)
      probe.receiveMessage(timeout)

    /** Send GetRandomNodeInfoReq and assert no response arrives within `timeout`. */
    def expectNoRandomPeer(timeout: FiniteDuration = 500.millis): Unit =
      val probe = testKit.createTestProbe[PeerDiscoveryManager.RandomNodeInfo]()
      peerDiscoveryManager ! PeerDiscoveryManager.GetRandomNodeInfoReq(probe.ref)
      probe.expectNoMessage(timeout)

    def test(): Unit

  def test(fixture: Fixture): Unit =
    try fixture.test()
    finally fixture.testKit.shutdownTestKit()

  def toENode(node: Node): ENode =
    ENode(
      id = PublicKey(BitVector(node.id.toArray[Byte])),
      address = ENode.Address(ip = node.addr, udpPort = node.udpPort, tcpPort = node.tcpPort)
    )

  behavior.of("PeerDiscoveryManager")

  it should "serve no peers if discovery is disabled and known peers are disabled and the manager isn't started" taggedAs (
    UnitTest,
    NetworkTest
  ) in test {
    new Fixture:
      override lazy val discoveryConfig: DiscoveryConfig =
        defaultConfig.copy(discoveryEnabled = false, reuseKnownNodes = false)

      override def test(): Unit =
        getPeers().nodes shouldBe empty
  }

  it should "serve the bootstrap nodes if known peers are reused even discovery isn't enabled and the manager isn't started" taggedAs (
    UnitTest,
    NetworkTest
  ) in test {
    new Fixture:
      override lazy val discoveryConfig: DiscoveryConfig =
        defaultConfig.copy(discoveryEnabled = false, reuseKnownNodes = true, bootstrapNodes = sampleNodes)

      override def test(): Unit =
        getPeers().nodes should contain theSameElementsAs sampleNodes
  }

  it should "serve the known peers if discovery is enabled and the manager isn't started" taggedAs (
    UnitTest,
    NetworkTest
  ) in test {
    new Fixture:
      override lazy val discoveryConfig: DiscoveryConfig =
        defaultConfig.copy(discoveryEnabled = true, reuseKnownNodes = true)

      (() => knownNodesStorage.getKnownNodes)
        .expects()
        .returning(sampleKnownUris)
        .once()

      override def test(): Unit =
        getPeers().nodes.map(_.toUri) should contain theSameElementsAs sampleKnownUris
  }

  it should "merge the known peers with the service if it's started" taggedAs (UnitTest, NetworkTest) in test {
    new Fixture:
      override lazy val discoveryConfig: DiscoveryConfig =
        defaultConfig.copy(discoveryEnabled = true, reuseKnownNodes = true)

      (() => knownNodesStorage.getKnownNodes)
        .expects()
        .returning(sampleKnownUris)
        .once()

      // getRandomNodes is wrapped in IO.defer in the manager (PR #1090) so the
      // mock is only invoked when the random-node stream is pulled. This test
      // only exercises GetDiscoveredNodesInfo (which uses getNodes), so allow
      // any call count including zero.
      (() => discoveryService.getRandomNodes)
        .expects()
        .returning(IO(sampleNodes.map(toENode).toSet))
        .anyNumberOfTimes()

      (() => discoveryService.getNodes)
        .expects()
        .returning(IO(sampleNodes.map(toENode)))
        .atLeastOnce()

      val expected: Set[URI] = sampleKnownUris ++ sampleNodes.map(_.toUri)

      override def test(): Unit =
        peerDiscoveryManager ! PeerDiscoveryManager.Start
        eventually {
          getPeers().nodes.map(_.toUri) should contain theSameElementsAs expected
        }
  }

  it should "keep serving the known peers if the service fails to start" taggedAs (UnitTest, NetworkTest) in test {
    new Fixture:
      override lazy val discoveryConfig: DiscoveryConfig =
        defaultConfig.copy(discoveryEnabled = true, reuseKnownNodes = true)

      @volatile var started = false

      override lazy val discoveryServiceResource: Resource[IO, DiscoveryService] =
        Resource.eval {
          IO { started = true } >>
            IO.raiseError[DiscoveryService](new RuntimeException("Oh no!") with NoStackTrace)
        }

      (() => knownNodesStorage.getKnownNodes)
        .expects()
        .returning(sampleKnownUris)
        .once()

      override def test(): Unit =
        peerDiscoveryManager ! PeerDiscoveryManager.Start
        eventually {
          started shouldBe true
        }
        getPeers().nodes should have size sampleKnownUris.size
  }

  it should "stop using the service after it is stopped" taggedAs (UnitTest, NetworkTest) in test {
    new Fixture:
      override lazy val discoveryConfig: DiscoveryConfig =
        defaultConfig.copy(discoveryEnabled = true, reuseKnownNodes = true)

      (() => knownNodesStorage.getKnownNodes)
        .expects()
        .returning(sampleKnownUris)
        .once()

      // getRandomNodes is wrapped in IO.defer in the manager (PR #1090) so the
      // mock is only invoked when the random-node stream is pulled. This test
      // only exercises GetDiscoveredNodesInfo (which uses getNodes), so allow
      // any call count including zero.
      (() => discoveryService.getRandomNodes)
        .expects()
        .returning(IO(sampleNodes.map(toENode).toSet))
        .anyNumberOfTimes()

      (() => discoveryService.getNodes)
        .expects()
        .returning(IO(sampleNodes.map(toENode)))
        .atLeastOnce()

      override def test(): Unit =
        peerDiscoveryManager ! PeerDiscoveryManager.Start
        eventually {
          getPeers().nodes should have size (sampleKnownUris.size + sampleNodes.size)
        }
        peerDiscoveryManager ! PeerDiscoveryManager.Stop
        eventually {
          getPeers().nodes should have size sampleKnownUris.size
        }
  }

  it should "log errors from the service rather than propagating them to callers" taggedAs (
    UnitTest,
    NetworkTest
  ) in test {
    new Fixture:
      override lazy val discoveryConfig: DiscoveryConfig =
        defaultConfig.copy(discoveryEnabled = true, reuseKnownNodes = false)

      // getRandomNodes is wrapped in IO.defer in the manager (PR #1090) so the
      // mock is only invoked when the random-node stream is pulled. This test
      // only exercises GetDiscoveredNodesInfo (which uses getNodes), so allow
      // any call count including zero.
      (() => discoveryService.getRandomNodes)
        .expects()
        .returning(IO.raiseError(new RuntimeException("Oh no!") with NoStackTrace))
        .anyNumberOfTimes()

      (() => discoveryService.getNodes)
        .expects()
        .returning(IO.raiseError(new RuntimeException("Oh no!") with NoStackTrace))
        .atLeastOnce()

      override def test(): Unit =
        peerDiscoveryManager ! PeerDiscoveryManager.Start
        // In Typed, IO errors are logged rather than forwarded as Status.Failure.
        // The actor stays alive and the caller simply receives no response for that request.
        eventually {
          val probe = testKit.createTestProbe[PeerDiscoveryManager.DiscoveredNodesInfo]()
          peerDiscoveryManager ! PeerDiscoveryManager.GetDiscoveredNodesInfoReq(probe.ref)
          probe.expectNoMessage(500.millis)
        }
  }

  it should "do lookups taggedAs (UnitTest, NetworkTest) in the background as it's asked for random nodes" in test {
    new Fixture:
      val bufferCapacity = 3
      val randomNodes: Set[Node] = sampleNodes.take(2)
      val lookupCount = new AtomicInteger(0)

      @scala.annotation.unused
      implicit val nodeOrd: Ordering[ENode] =
        Ordering.by(_.id.value.toByteArray.toSeq)

      (() => discoveryService.getRandomNodes)
        .expects()
        .returning(IO { lookupCount.incrementAndGet(); randomNodes.map(toENode).toSet })
        .atLeastOnce()

      override lazy val discoveryConfig: DiscoveryConfig =
        defaultConfig.copy(discoveryEnabled = true, reuseKnownNodes = false, kademliaBucketSize = bufferCapacity)

      override def test(): Unit =
        peerDiscoveryManager ! PeerDiscoveryManager.Start

        eventually {
          val n0 = getRandomPeer().node
          val n1 = getRandomPeer().node
          val n2 = getRandomPeer().node

          // Verify that we're getting nodes from the random set
          // Due to Set ordering in stream, we may get the same node multiple times
          // but they should all be from the randomNodes set
          randomNodes should contain(n0)
          randomNodes should contain(n1)
          randomNodes should contain(n2)
        }

        // Verify that lookups happened in the background
        lookupCount.get() should be >= 1
  }

  it should "not send any random node if discovery isn't started" taggedAs (UnitTest, NetworkTest) in test {
    new Fixture:
      override lazy val discoveryConfig: DiscoveryConfig =
        defaultConfig.copy(reuseKnownNodes = true)

      (() => knownNodesStorage.getKnownNodes)
        .expects()
        .returning(sampleKnownUris)
        .once()

      override def test(): Unit =
        expectNoRandomPeer()
  }

  // --- EIP-1459 DNS seeding (H9): resolved off the actor, after Start, never ahead of it ---

  private val dnsDomain = "tree.test"

  /** A signed 2 x 3 tree; its six enodes are numbered 0 to 5. */
  private val dnsTree = new DnsTreeFixture.Tree(dnsDomain, branches = 2, leavesPerBranch = 3)

  /** The six enodes of a 2 x 3 in-memory tree, as the manager parses them. */
  private val dnsTreeNodes: Set[Node] =
    (0 until 6).map(i => Node.fromUri(new URI(DnsTreeFixture.enode(i)))).toSet

  it should "serve the static nodes without waiting for DNS, then take the DNS nodes as the walk finds them" taggedAs (
    UnitTest,
    NetworkTest
  ) in test {
    new Fixture:
      // The walk is held on its first leaf until the test opens the gate.
      val gate = new CountDownLatch(1)
      val added: java.util.Set[ENode] = ConcurrentHashMap.newKeySet[ENode]()

      override lazy val dnsLookup: DnsDiscovery.TxtLookup = new DnsTreeFixture.Stub(
        dnsTree.records,
        name => if dnsTree.isLeaf(name) then gate.await(30, TimeUnit.SECONDS)
      )

      override lazy val discoveryConfig: DiscoveryConfig =
        defaultConfig.copy(
          discoveryEnabled = true,
          reuseKnownNodes = true,
          bootstrapNodes = sampleNodes,
          dnsTrees = Seq(dnsTree.url)
        )

      (() => knownNodesStorage.getKnownNodes).expects().returning(Set.empty[URI]).anyNumberOfTimes()
      (() => discoveryService.getRandomNodes).expects().returning(IO.pure(Set.empty[ENode])).anyNumberOfTimes()
      (() => discoveryService.getNodes).expects().returning(IO.pure(Set.empty[ENode])).anyNumberOfTimes()
      discoveryService.addNode.expects(*).onCall((node: ENode) => IO { added.add(node); () }).anyNumberOfTimes()

      override def test(): Unit =
        peerDiscoveryManager ! PeerDiscoveryManager.Start

        // Start has been handled and the DNS walk is stuck on its leaves: a manager that waited for DNS could not answer
        // this at all, and one that answered would have no DNS nodes in it yet.
        getPeers().nodes shouldBe sampleNodes
        added.size shouldBe 0

        gate.countDown()
        eventually {
          getPeers().nodes shouldBe (sampleNodes ++ dnsTreeNodes)
          added.size shouldBe dnsTreeNodes.size
        }
  }

  it should "enroll the DNS nodes that arrive before the discovery service has started, once it has" taggedAs (
    UnitTest,
    NetworkTest
  ) in test {
    new Fixture:
      val serviceGate = new CountDownLatch(1)
      val added: java.util.Set[ENode] = ConcurrentHashMap.newKeySet[ENode]()

      override lazy val dnsLookup: DnsDiscovery.TxtLookup =
        new DnsTreeFixture.Stub(dnsTree.records)

      override lazy val discoveryServiceResource: Resource[IO, DiscoveryService] =
        Resource.eval(IO.blocking(serviceGate.await(30, TimeUnit.SECONDS)).as(discoveryService))

      override lazy val discoveryConfig: DiscoveryConfig =
        defaultConfig.copy(discoveryEnabled = true, reuseKnownNodes = true, dnsTrees = Seq(dnsTree.url))

      (() => knownNodesStorage.getKnownNodes).expects().returning(Set.empty[URI]).anyNumberOfTimes()
      (() => discoveryService.getRandomNodes).expects().returning(IO.pure(Set.empty[ENode])).anyNumberOfTimes()
      (() => discoveryService.getNodes).expects().returning(IO.pure(Set.empty[ENode])).anyNumberOfTimes()
      discoveryService.addNode.expects(*).onCall((node: ENode) => IO { added.add(node); () }).anyNumberOfTimes()

      override def test(): Unit =
        peerDiscoveryManager ! PeerDiscoveryManager.Start

        // The service is still starting, so these are candidates only: nothing can be enrolled yet.
        eventually {
          getPeers().nodes shouldBe dnsTreeNodes
        }
        added.size shouldBe 0

        serviceGate.countDown()
        eventually {
          added.size shouldBe dnsTreeNodes.size
        }
  }

  it should "walk DNS even with discovery disabled, and cancel the walk when stopped" taggedAs (
    UnitTest,
    NetworkTest
  ) in test {
    new Fixture:
      val reachedLeaf = new CountDownLatch(1)
      val never = new CountDownLatch(1)
      val interrupted = new AtomicBoolean(false)

      override lazy val dnsLookup: DnsDiscovery.TxtLookup = new DnsTreeFixture.Stub(
        dnsTree.records,
        name =>
          if dnsTree.isLeaf(name) then
            reachedLeaf.countDown()
            try never.await()
            catch
              case e: InterruptedException =>
                interrupted.set(true)
                throw e
      )

      override lazy val discoveryConfig: DiscoveryConfig =
        defaultConfig.copy(discoveryEnabled = false, reuseKnownNodes = true, dnsTrees = Seq(dnsTree.url))

      override def test(): Unit =
        peerDiscoveryManager ! PeerDiscoveryManager.Start
        reachedLeaf.await(30, TimeUnit.SECONDS) shouldBe true
        interrupted.get shouldBe false

        peerDiscoveryManager ! PeerDiscoveryManager.Stop
        eventually {
          interrupted.get shouldBe true
        }
  }
