package com.chipprbots.ethereum.network

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.*

import com.typesafe.config.ConfigValueFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.FreeSpecBase
import com.chipprbots.ethereum.metrics.Metrics
import com.chipprbots.ethereum.metrics.MetricsConfig
import com.chipprbots.ethereum.network.discovery.Node
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.sync.util.HandshakeItSpecSupport
import com.chipprbots.ethereum.sync.util.RegularSyncItSpecUtils.FakePeer
import com.chipprbots.ethereum.sync.util.SilentTcpServer
import com.chipprbots.ethereum.sync.util.SyncCommonItSpec.IdentityUpdate
import com.chipprbots.ethereum.sync.util.SyncCommonItSpecUtils.FakePeerCustomConfig
import com.chipprbots.ethereum.sync.util.SyncCommonItSpecUtils.retryUntilWithDelay
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config

/** End-to-End test suite for P2P handshake functionality.
  *
  * This test suite validates the P2P handshake protocol including:
  *   - RLPx connection establishment
  *   - Ethereum protocol handshake (ETH/64, ETH/63)
  *   - Node status exchange
  *   - Fork block validation
  *   - Handshake timeout and retry handling
  *
  * These tests ensure that fukuii can successfully establish P2P connections with peers, which is critical for
  * blockchain synchronization.
  *
  * @see
  *   Issue: E2E testing - test driven development for resolving p2p handshake, block exchange or storage issues
  */
class E2EHandshakeSpec extends FreeSpecBase with Matchers with BeforeAndAfterAll with HandshakeItSpecSupport:
  implicit val testRuntime: IORuntime = IORuntime.global

  override def beforeAll(): Unit =
    // Close any previous metrics instance so the new one starts with a clean registry
    Metrics.closeInstance("default")
    Metrics.configure(
      MetricsConfig(Config.config.withValue("metrics.enabled", ConfigValueFactory.fromAnyRef(true)))
    )

  override def afterAll(): Unit = {
    // No need to shutdown IORuntime.global
  }

  "E2E P2P Handshake" - {

    "RLPx Connection Establishment" - {

      "should successfully establish RLPx connection between two peers" taggedAs (
        IntegrationTest,
        NetworkTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        for
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          // Returns once both PeerManagers hold the other as a handshaked peer, which an RLPx connection that was not
          // encrypted and acknowledged on both sides cannot give
          held <- dialAndAwaitHandshake(peer2, peer1)
        yield
          val (outgoing, incoming) = held
          assertOutgoingAndIncoming(outgoing, incoming, peer1)
      }

      "should establish multiple simultaneous connections" taggedAs (
        IntegrationTest,
        NetworkTest
      ) in customTestCaseResourceM(
        FakePeer.start3FakePeersRes()
      ) { case (peer1, peer2, peer3) =>
        for
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer3.startRegularSync()
          // Peer1 dials both at once
          _ <- peer1.connectToPeers(Set(peer2.node, peer3.node))
          dialed <- peer1.waitForHandshakedPeers(2)
          accepted2 <- peer2.waitForHandshakedPeers(1)
          accepted3 <- peer3.waitForHandshakedPeers(1)
        yield
          dialed.map(_.incomingConnection).distinct shouldBe Seq(false)
          dialed.map(_.remoteAddress.getPort).toSet shouldBe Set(
            peer2.listenAddress.getPort,
            peer3.listenAddress.getPort
          )
          accepted2.map(_.incomingConnection) shouldBe Seq(true)
          accepted3.map(_.incomingConnection) shouldBe Seq(true)
      }

      "should handle bidirectional connection attempts" taggedAs (
        IntegrationTest,
        NetworkTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        for
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()

          // Both peers try to connect to each other. The first dial must establish an outgoing
          // handshake (and persist a known node). The reverse dial is best-effort: the PeerManager
          // de-duplicates simultaneous connections (canConnectTo / hasIncomingPendingFromHost), so
          // the reverse outgoing dial is legitimately suppressed once the inbound connection from
          // the first dial already exists. Asserting a known-node registration for the reverse
          // direction would be asserting behaviour that correct dedup prevents.
          _ <- peer1.connectToPeers(Set(peer2.node))
          _ <- peer2.connectToPeersBestEffort(Set(peer1.node))
          held1 <- peer1.waitForHandshakedPeers(1)
          held2 <- peer2.waitForHandshakedPeers(1)
        yield
          // Duplicate attempts end in one connection between the two, not two: each side holds exactly one peer, and
          // the pair agree on which end dialed
          held1 should have size 1
          held2 should have size 1
          held1.head.incomingConnection should not be held2.head.incomingConnection
      }
    }

    "Ethereum Protocol Handshake" - {

      "should exchange node status successfully" taggedAs (IntegrationTest, NetworkTest) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        for
          // Import some blocks to create different chain states
          _ <- peer1.importBlocksUntil(100)(IdentityUpdate)
          _ <- peer2.importBlocksUntil(50)(IdentityUpdate)

          // The chains as they stand at the handshake: nothing syncs before it is done
          best1 = peer1.blockchainReader.getBestBlock.get
          best2 = peer2.blockchainReader.getBestBlock.get
          _ <- dialAndAwaitHandshake(peer2, peer1)
          infoOfPeer1 <- singlePeerInfo(peer2)
          infoOfPeer2 <- singlePeerInfo(peer1)
        yield
          // Each side holds the STATUS the other sent: its best block as it was then, and its chain position
          best1.number.value shouldBe 100
          best2.number.value shouldBe 50
          assertChainPosition(infoOfPeer1, peer1)
          assertChainPosition(infoOfPeer2, peer2)
      }

      "should validate protocol version compatibility" taggedAs (
        IntegrationTest,
        NetworkTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        for
          _ <- dialAndAwaitHandshake(peer2, peer1)
          infoOfPeer1 <- singlePeerInfo(peer2)
          infoOfPeer2 <- singlePeerInfo(peer1)
        yield
          // Both ends settled on the same version, one the node supports
          infoOfPeer1.remoteStatus.capability shouldBe infoOfPeer2.remoteStatus.capability
          Config.supportedCapabilities should contain(infoOfPeer1.remoteStatus.capability)
      }

      "should exchange genesis block hash correctly" taggedAs (IntegrationTest, NetworkTest) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        for
          _ <- dialAndAwaitHandshake(peer2, peer1)
          infoOfPeer1 <- singlePeerInfo(peer2)
          infoOfPeer2 <- singlePeerInfo(peer1)
        yield
          // Both peers should have the same genesis block
          val peer1Genesis = peer1.blockchainReader.getBlockByNumber(peer1.blockchainReader.getBestBranch, 0)
          val peer2Genesis = peer2.blockchainReader.getBlockByNumber(peer2.blockchainReader.getBestBranch, 0)

          peer1Genesis shouldBe defined
          peer2Genesis shouldBe defined
          peer1Genesis.get.hash shouldBe peer2Genesis.get.hash
          // And that is the one each sent the other in its STATUS
          infoOfPeer1.remoteStatus.genesisHash shouldBe peer1Genesis.get.hash.value
          infoOfPeer2.remoteStatus.genesisHash shouldBe peer2Genesis.get.hash.value
      }
    }

    "Fork Block Exchange" - {

      // The harness has no fork resolver (forkResolverOpt = None), so the fork check of its handshake is the ForkId
      // (EIP-2124) validation of the STATUS exchange: a peer whose fork schedule or genesis the local one cannot
      // follow is dropped there. A peer on a fork the local node rejects needs a second blockchain config in the harness.
      "should validate fork blocks during handshake" taggedAs (IntegrationTest, NetworkTest) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        for
          _ <- dialAndAwaitHandshake(peer2, peer1)
          infoOfPeer1 <- singlePeerInfo(peer2)
          infoOfPeer2 <- singlePeerInfo(peer1)
        yield
          // The ForkId check passed on both sides, so each recorded the fork of the other as accepted
          infoOfPeer1.forkAccepted shouldBe true
          infoOfPeer2.forkAccepted shouldBe true
          peer1.wrongNetworkHandshakes.get shouldBe 0
          peer2.wrongNetworkHandshakes.get shouldBe 0
      }

      "should handle peers with compatible fork configurations" taggedAs (
        IntegrationTest,
        NetworkTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        for
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()

          // Both peers run the same fork configuration
          held <- dialAndAwaitHandshake(peer1, peer2)
          infoOfPeer2 <- singlePeerInfo(peer1)
          infoOfPeer1 <- singlePeerInfo(peer2)
        yield
          val (outgoing, incoming) = held
          assertOutgoingAndIncoming(outgoing, incoming, peer2)
          infoOfPeer2.forkAccepted shouldBe true
          infoOfPeer1.forkAccepted shouldBe true
      }
    }

    "Handshake Timeout Handling" - {

      // The slow end of the handshake is not injected here (the harness has no delaying proxy): this asserts that the
      // handshake completes, waited for on the state of the peers for as long as the handshake timeouts allow.
      "should handle slow handshake responses" taggedAs (
        IntegrationTest,
        NetworkTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        for
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node), maxRetries = 60)
          outgoing <- peer2.waitForHandshakedPeers(1, maxRetries = 600)
          incoming <- peer1.waitForHandshakedPeers(1, maxRetries = 600)
        yield assertOutgoingAndIncoming(outgoing.head, incoming.head, peer1)
      }

      "should retry failed handshakes" taggedAs (IntegrationTest, NetworkTest, SlowTest) in customTestCaseResourceM(
        for
          peers <- FakePeer.start2FakePeersRes()
          silent <- SilentTcpServer.resource
        yield (peers._1, peers._2, silent)
      ) { case (peer1, peer2, silent) =>
        for
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()

          // A node that never answers the RLPx handshake: the first attempt ends by its timeout, and the peer dials
          // the same node again (connectMaxRetries is 3), which the node sees as a second connection
          _ <- IO(peer2.peerManager ! PeerManagerActor.DiscoveredNodesReceived(Set(silent.node)))
          _ <- retryUntilWithDelay(IO(silent.accepted), 100.millis, 1200)(_ >= 2)
          // And a node that does answer is still reached
          held <- dialAndAwaitHandshake(peer2, peer1)
        yield
          silent.accepted should be >= 2
          assertOutgoingAndIncoming(held._1, held._2, peer1)
      }
    }

    "Peer Discovery and Handshake" - {

      // connectToPeers hands the node to the PeerManager as a discovered one, as discovery does
      "should successfully handshake with discovered peers" taggedAs (
        IntegrationTest,
        NetworkTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        for
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()

          held <- dialAndAwaitHandshake(peer1, peer2)
        yield
          val (outgoing, incoming) = held
          assertOutgoingAndIncoming(outgoing, incoming, peer2)
          // The handshake is what made peer2 a known node of peer1, and nothing else
          peer1.storagesInstance.storages.knownNodesStorage.getKnownNodes.map(Node.fromUri(_).id) shouldBe
            Set(peer2.node.id)
      }

      "should maintain connections after handshake" taggedAs (
        IntegrationTest,
        NetworkTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 120
        for
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          held <- dialAndAwaitHandshake(peer2, peer1)
          // The connection made by the handshake carries what happens after it: blocks that peer1 imports now reach peer2
          _ <- peer1.importBlocksUntil(blockNumber)(IdentityUpdate)
          synced <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
          stillHeld2 <- peer2.waitForHandshakedPeers(1)
          stillHeld1 <- peer1.waitForHandshakedPeers(1)
        yield
          synced shouldBe true
          // Still the same two connections, none re-made
          stillHeld2.map(_.id) shouldBe Seq(held._1.id)
          stillHeld1.map(_.id) shouldBe Seq(held._2.id)
      }
    }

    "Handshake with Chain State" - {

      "should handshake with peers having different chain heights" taggedAs (
        IntegrationTest,
        NetworkTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        for
          // Create chains with different heights
          _ <- peer1.importBlocksUntil(200)(IdentityUpdate)
          _ <- peer2.importBlocksUntil(50)(IdentityUpdate)

          _ <- dialAndAwaitHandshake(peer2, peer1)
          infoOfPeer1 <- singlePeerInfo(peer2)
          infoOfPeer2 <- singlePeerInfo(peer1)
        yield
          // Handshake should succeed regardless of chain height difference, each side holding the own height of the other
          assertStatusOf(infoOfPeer1, peer1)
          peer1.blockchainReader.getBestBlockNumber shouldBe 200
          infoOfPeer2.remoteStatus.bestHash shouldBe
            peer2.blockchainReader.getBlockByNumber(peer2.blockchainReader.getBestBranch, 50).get.hash.value
      }

      "should handshake with peers at genesis" taggedAs (IntegrationTest, NetworkTest) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        for
          // Peer1 has blocks, peer2 is at genesis
          _ <- peer1.importBlocksUntil(100)(IdentityUpdate)
          peer2AtStart = peer2.blockchainReader.getBestBlockNumber

          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          // B-188: the handshake from genesis succeeded iff peer2 then syncs the chain of peer1. The old check, that
          // peer2 was still at 0 after a 3 s sleep, failed whenever regular sync worked. A condition wait, no sleep.
          synced <- peer2.waitForRegularSyncLoadLastBlock(100)
        yield
          peer2AtStart shouldBe 0
          synced shouldBe true
          peer2.blockchainReader.getBestBlockNumber shouldBe 100
          peer2.blockchainReader.getBestBlockHeader.map(_.hash) shouldBe peer1.blockchainReader.getBestBlockHeader.map(
            _.hash
          )
      }

      "should exchange total difficulty information" taggedAs (IntegrationTest, NetworkTest) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        for
          _ <- peer1.importBlocksUntil(150)(IdentityUpdate)

          _ <- dialAndAwaitHandshake(peer2, peer1)
          infoOfPeer1 <- singlePeerInfo(peer2)
        yield
          // What peer2 holds of peer1 is what peer1 sent. Total difficulty is on the wire in eth/68 only, where it is the
          // own value of peer1 at its best block, and more than that of the genesis; from eth/69 the STATUS has the
          // latest block instead, and the receiver works the difficulty out itself
          val peer1BestBlock = peer1.blockchainReader.getBestBlock.get
          val peer1Difficulty = peer1.blockchainReader.getChainWeightByHash(peer1BestBlock.hash)
          peer1Difficulty shouldBe defined
          assertChainPosition(infoOfPeer1, peer1)
          infoOfPeer1.remoteStatus.capability match
            case Capability.ETH68 =>
              infoOfPeer1.remoteStatus.chainWeight.totalDifficulty should be >
                peer2.blockchainReader.getChainWeightByHash(peer2.genesis.hash).get.totalDifficulty
            case _ =>
              infoOfPeer1.remoteStatus.latestBlock shouldBe Some(BigInt(150))
      }
    }

    "Concurrent Handshakes" - {

      "should handle multiple concurrent handshakes" taggedAs (IntegrationTest, NetworkTest) in customTestCaseResourceM(
        FakePeer.start3FakePeersRes()
      ) { case (peer1, peer2, peer3) =>
        for
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer3.startRegularSync()

          // Two dials from peer1 in flight at the same moment, as two calls, each waiting out its own handshake
          _ <- IO.both(peer1.connectToPeers(Set(peer2.node)), peer1.connectToPeers(Set(peer3.node)))
          dialed <- peer1.waitForHandshakedPeers(2)
          held2 <- peer2.waitForHandshakedPeers(1)
          held3 <- peer3.waitForHandshakedPeers(1)
        yield
          dialed.map(_.incomingConnection).distinct shouldBe Seq(false)
          dialed.map(_.remoteAddress.getPort).toSet shouldBe Set(
            peer2.listenAddress.getPort,
            peer3.listenAddress.getPort
          )
          held2.map(_.incomingConnection) shouldBe Seq(true)
          held3.map(_.incomingConnection) shouldBe Seq(true)
      }

      "should handle handshakes while syncing" taggedAs (
        IntegrationTest,
        NetworkTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start3FakePeersRes()
      ) { case (peer1, peer2, peer3) =>
        val blockNumber = 300
        for
          _ <- peer1.importBlocksUntil(blockNumber)(IdentityUpdate)

          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer3.startRegularSync()

          // Peer2 starts syncing from peer1, and peer1 dials peer3 straight after, with that sync under way
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer1.connectToPeers(Set(peer3.node))
          held1 <- peer1.waitForHandshakedPeers(2)
          synced2 <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
          held3 <- peer3.waitForHandshakedPeers(1)
        yield
          // Both handshakes held, and the sync came to the end through the first
          held1.map(_.incomingConnection).toSet shouldBe Set(true, false)
          held3.map(_.incomingConnection) shouldBe Seq(true)
          synced2 shouldBe true
          peer2.blockchainReader.getBestBlock.map(_.hash) shouldBe peer1.blockchainReader.getBestBlock.map(_.hash)
      }
    }

    "Handshake Error Recovery" - {

      "should recover from handshake failures and retry" taggedAs (
        IntegrationTest,
        NetworkTest,
        SlowTest
      ) in customTestCaseResourceM(
        for
          peers <- FakePeer.start2FakePeersRes()
          // A third peer on another network: its handshake with peer1 cannot succeed
          other <- FakePeer.start1FakePeerRes(FakePeerCustomConfig.defaultConfig.copy(networkId = 2L), "Peer3")
        yield (peers._1, peers._2, other)
      ) { case (peer1, peer2, other) =>
        for
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- other.startRegularSync()

          // The failure: one of the two sees the handshake end on the network mismatch
          _ <- IO(other.peerManager ! PeerManagerActor.DiscoveredNodesReceived(Set(peer1.node)))
          _ <- retryUntilWithDelay(
            IO(other.wrongNetworkHandshakes.get + peer1.wrongNetworkHandshakes.get),
            100.millis,
            600
          )(_ >= 1)
          afterFailure <- peer1.handshakedPeers()
          // The retry: the next dial of the same node, from a peer on the right network, goes through
          held <- dialAndAwaitHandshake(peer2, peer1)
          otherHeld <- other.handshakedPeers()
        yield
          afterFailure shouldBe empty
          otherHeld shouldBe empty
          assertOutgoingAndIncoming(held._1, held._2, peer1)
      }

      "should disconnect on incompatible handshake parameters" taggedAs (
        IntegrationTest,
        NetworkTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes(
          fakePeerCustomConfig2 = FakePeerCustomConfig.defaultConfig.copy(networkId = 2L)
        )
      ) { case (peer1, peer2) =>
        for
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          // Peer2 is on network 2, peer1 on network 1: the STATUS exchange fails the handshake
          _ <- IO(peer2.peerManager ! PeerManagerActor.DiscoveredNodesReceived(Set(peer1.node)))
          _ <- retryUntilWithDelay(
            IO(peer1.wrongNetworkHandshakes.get + peer2.wrongNetworkHandshakes.get),
            100.millis,
            600
          )(_ >= 1)
          // Nothing is left of the connection on either side
          held1 <- retryUntilWithDelay(peer1.allPeers(), 100.millis, 600)(_.isEmpty)
          held2 <- retryUntilWithDelay(peer2.allPeers(), 100.millis, 600)(_.isEmpty)
        yield
          held1 shouldBe empty
          held2 shouldBe empty
          // And the node is not remembered as one that handshaked
          peer2.storagesInstance.storages.knownNodesStorage.getKnownNodes shouldBe empty
      }
    }
  }
