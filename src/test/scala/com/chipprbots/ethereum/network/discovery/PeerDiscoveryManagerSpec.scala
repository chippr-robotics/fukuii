package com.chipprbots.ethereum.network.discovery

import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.pattern.AskTimeoutException
import org.apache.pekko.pattern.ask
import org.apache.pekko.testkit.TestActorRef
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import cats.effect.IO
import cats.effect.Resource
import cats.effect.unsafe.IORuntime

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.math.Ordering.Implicits._
import scala.util.control.NoStackTrace

import com.chipprbots.scalanet.discovery.crypto.PublicKey
import com.chipprbots.scalanet.discovery.ethereum.v4.DiscoveryService
import com.chipprbots.scalanet.discovery.ethereum.{Node => ENode}
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import scodec.bits.BitVector

import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.db.storage.KnownNodesStorage
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.network.discovery.Node
import scala.collection.immutable.Range.Inclusive

class PeerDiscoveryManagerSpec
    extends AnyFlatSpecLike
    with Matchers
    with Eventually
    with MockFactory
    with ScalaFutures
    with NormalPatience {

  implicit val runtime: IORuntime = IORuntime.global
  implicit val timeout: Timeout = 1.second

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

  trait Fixture {
    implicit lazy val system: ActorSystem = ActorSystem("PeerDiscoveryManagerSpec_System")
    lazy val discoveryConfig = defaultConfig
    lazy val knownNodesStorage: KnownNodesStorage = mock[KnownNodesStorage]
    lazy val discoveryService: DiscoveryService = mock[DiscoveryService]
    lazy val discoveryServiceResource: Resource[IO, DiscoveryService] =
      Resource.pure[IO, DiscoveryService](discoveryService)

    lazy val peerDiscoveryManager: TestActorRef[PeerDiscoveryManager] =
      TestActorRef[PeerDiscoveryManager](
        PeerDiscoveryManager.props(
          localNodeId = ByteString.fromString("test-node"),
          discoveryConfig = discoveryConfig,
          knownNodesStorage = knownNodesStorage,
          discoveryServiceResource = discoveryServiceResource
        )
      )

    def getPeers: Future[PeerDiscoveryManager.DiscoveredNodesInfo] =
      (peerDiscoveryManager ? PeerDiscoveryManager.GetDiscoveredNodesInfo)
        .mapTo[PeerDiscoveryManager.DiscoveredNodesInfo]

    def getRandomPeer: Future[PeerDiscoveryManager.RandomNodeInfo] =
      (peerDiscoveryManager ? PeerDiscoveryManager.GetRandomNodeInfo)
        .mapTo[PeerDiscoveryManager.RandomNodeInfo]

    def test(): Unit
  }

  def test(fixture: Fixture): Unit =
    try fixture.test()
    finally {
      fixture.system.stop(fixture.peerDiscoveryManager)
      TestKit.shutdownActorSystem(fixture.system, verifySystemShutdown = true)
    }

  def toENode(node: Node): ENode =
    ENode(
      id = PublicKey(BitVector(node.id.toArray[Byte])),
      address = ENode.Address(ip = node.addr, udpPort = node.udpPort, tcpPort = node.tcpPort)
    )

  behavior.of("PeerDiscoveryManager")

  it should "serve no peers if discovery is disabled and known peers are disabled and the manager isn't started" in test {
    new Fixture {
      override lazy val discoveryConfig: DiscoveryConfig =
        defaultConfig.copy(discoveryEnabled = false, reuseKnownNodes = false)

      override def test(): Unit =
        getPeers.futureValue.nodes shouldBe empty
    }
  }

  it should "serve the bootstrap nodes if known peers are reused even discovery isn't enabled and the manager isn't started" in test {
    new Fixture {
      override lazy val discoveryConfig: DiscoveryConfig =
        defaultConfig.copy(discoveryEnabled = false, reuseKnownNodes = true, bootstrapNodes = sampleNodes)

      override def test(): Unit =
        getPeers.futureValue.nodes should contain theSameElementsAs sampleNodes
    }
  }

  it should "serve the known peers if discovery is enabled and the manager isn't started" in test {
    new Fixture {
      override lazy val discoveryConfig: DiscoveryConfig =
        defaultConfig.copy(discoveryEnabled = true, reuseKnownNodes = true)

      (knownNodesStorage.getKnownNodes _)
        .expects()
        .returning(sampleKnownUris)
        .once()

      override def test(): Unit =
        getPeers.futureValue.nodes.map(_.toUri) should contain theSameElementsAs sampleKnownUris
    }
  }

  it should "merge the known peers with the service if it's started" in test {
    new Fixture {
      override lazy val discoveryConfig: DiscoveryConfig =
        defaultConfig.copy(discoveryEnabled = true, reuseKnownNodes = true)

      (knownNodesStorage.getKnownNodes _)
        .expects()
        .returning(sampleKnownUris)
        .once()

      (() => discoveryService.getNodes)
        .expects()
        .returning(IO(sampleNodes.map(toENode)))
        .once()

      val expected: Set[URI] = sampleKnownUris ++ sampleNodes.map(_.toUri)

      override def test(): Unit = {
        peerDiscoveryManager ! PeerDiscoveryManager.Start
        eventually {
          getPeers.futureValue.nodes.map(_.toUri) should contain theSameElementsAs expected
        }
      }
    }
  }

  it should "keep serving the known peers if the service fails to start" in test {
    new Fixture {
      override lazy val discoveryConfig: DiscoveryConfig =
        defaultConfig.copy(discoveryEnabled = true, reuseKnownNodes = true)

      @volatile var started = false

      override lazy val discoveryServiceResource: Resource[IO, DiscoveryService] =
        Resource.eval {
          IO { started = true } >>
            IO.raiseError[DiscoveryService](new RuntimeException("Oh no!") with NoStackTrace)
        }

      (knownNodesStorage.getKnownNodes _)
        .expects()
        .returning(sampleKnownUris)
        .once()

      override def test(): Unit = {
        peerDiscoveryManager ! PeerDiscoveryManager.Start
        eventually {
          started shouldBe true
        }
        getPeers.futureValue.nodes should have size (sampleKnownUris.size)
      }
    }
  }

  it should "stop using the service after it is stopped" in test {
    new Fixture {
      override lazy val discoveryConfig: DiscoveryConfig =
        defaultConfig.copy(discoveryEnabled = true, reuseKnownNodes = true)

      (() => knownNodesStorage.getKnownNodes())
        .expects()
        .returning(sampleKnownUris)
        .once()

      (() => discoveryService.getNodes)
        .expects()
        .returning(IO(sampleNodes.map(toENode)))
        .once()

      override def test(): Unit = {
        peerDiscoveryManager ! PeerDiscoveryManager.Start
        eventually {
          getPeers.futureValue.nodes should have size (sampleKnownUris.size + sampleNodes.size)
        }
        peerDiscoveryManager ! PeerDiscoveryManager.Stop
        eventually {
          getPeers.futureValue.nodes should have size (sampleKnownUris.size)
        }
      }
    }
  }

  it should "propagate any error from the service to the caller" in test {
    new Fixture {
      override lazy val discoveryConfig: DiscoveryConfig =
        defaultConfig.copy(discoveryEnabled = true, reuseKnownNodes = false)

      (() => discoveryService.getNodes)
        .expects()
        .returning(IO.raiseError(new RuntimeException("Oh no!") with NoStackTrace))
        .atLeastOnce()

      override def test(): Unit = {
        peerDiscoveryManager ! PeerDiscoveryManager.Start
        eventually {
          a[RuntimeException] shouldBe thrownBy(getPeers.futureValue)
        }
      }
    }
  }

  it should "do lookups in the background as it's asked for random nodes" in test {
    new Fixture {
      val bufferCapacity = 3
      val randomNodes: Set[Node] = sampleNodes.take(2)
      // 2 to fill the buffer initially
      // 1 to replace consumed items
      // 1 finished waiting to push items in the full buffer (this may or may not finish by the end of the test)
      val expectedLookups: Inclusive = Range.inclusive(3, 4)
      val lookupCount = new AtomicInteger(0)

      implicit val nodeOrd: Ordering[ENode] =
        Ordering.by(_.id.value.toByteArray.toSeq)

      (() => discoveryService.getRandomNodes)
        .expects()
        .returning(IO { lookupCount.incrementAndGet(); randomNodes.map(toENode).toSet })
        .repeat(expectedLookups)

      override lazy val discoveryConfig: DiscoveryConfig =
        defaultConfig.copy(discoveryEnabled = true, reuseKnownNodes = false, kademliaBucketSize = bufferCapacity)

      override def test(): Unit = {
        peerDiscoveryManager ! PeerDiscoveryManager.Start

        eventually {
          val n0 = getRandomPeer.futureValue.node
          val n1 = getRandomPeer.futureValue.node
          getRandomPeer.futureValue.node

          Set(n0, n1) shouldBe randomNodes
        }

        lookupCount.get() shouldBe >=(expectedLookups.start)
        lookupCount.get() shouldBe <=(expectedLookups.end)
      }
    }
  }

  it should "not send any random node if discovery isn't started" in test {
    new Fixture {
      override lazy val discoveryConfig: DiscoveryConfig =
        defaultConfig.copy(reuseKnownNodes = true)

      (knownNodesStorage.getKnownNodes _)
        .expects()
        .returning(sampleKnownUris)
        .once()

      override def test(): Unit =
        getRandomPeer.failed.futureValue shouldBe an[AskTimeoutException]
    }
  }
}
