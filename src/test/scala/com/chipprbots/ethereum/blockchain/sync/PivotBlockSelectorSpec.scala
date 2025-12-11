package com.chipprbots.ethereum.blockchain.sync

import java.net.InetSocketAddress

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.ExplicitlyTriggeredScheduler
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfter
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.WithActorSystemShutDown
import com.chipprbots.ethereum.blockchain.sync.fast.PivotBlockSelector
import com.chipprbots.ethereum.blockchain.sync.fast.PivotBlockSelector.Result
import com.chipprbots.ethereum.blockchain.sync.fast.PivotBlockSelector.SelectPivotBlock
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.Subscribe
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.PeerDisconnectedClassifier
import com.chipprbots.ethereum.network.PeerEventBusActor.Unsubscribe
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.NewBlock
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETH62._
import com.chipprbots.ethereum.network.p2p.messages.ETH66
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.testing.Tags._

class PivotBlockSelectorSpec
    extends TestKit(
      ActorSystem("FastSyncPivotBlockSelectorSpec_System", ConfigFactory.load("explicit-scheduler"))
    )
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfter
    with WithActorSystemShutDown {

  "FastSyncPivotBlockSelector" should "download pivot block from peers" taggedAs (UnitTest, SyncTest) in new TestSetup {
    updateHandshakedPeers(HandshakedPeers(threeAcceptedPeers))

    pivotBlockSelector ! SelectPivotBlock

    peerMessageBus.expectMsgAllOf(
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id))),
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id))),
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id)))
    )

    expectGetBlockHeadersRequests(Seq(peer1, peer2, peer3), expectedPivotBlock)

    // Collecting pivot block (for voting)
    pivotBlockSelector ! MessageFromPeer(BlockHeaders(Seq(pivotBlockHeader)), peer1.id)
    pivotBlockSelector ! MessageFromPeer(BlockHeaders(Seq(pivotBlockHeader)), peer2.id)
    pivotBlockSelector ! MessageFromPeer(BlockHeaders(Seq(pivotBlockHeader)), peer3.id)

    peerMessageBus.expectMsgAllOf(
      Unsubscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id))),
      Unsubscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id))),
      Unsubscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id)))
    )

    fastSync.expectMsg(Result(pivotBlockHeader))
    peerMessageBus.expectMsg(Unsubscribe())
  }

  it should "ask for the block number 0 if [bestPeerBestBlockNumber < syncConfig.pivotBlockOffset]" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup {
    val highestNumber: Int = syncConfig.pivotBlockOffset - 1

    updateHandshakedPeers(
      HandshakedPeers(
        threeAcceptedPeers
          .updated(peer1, threeAcceptedPeers(peer1).copy(maxBlockNumber = highestNumber))
          .updated(peer2, threeAcceptedPeers(peer2).copy(maxBlockNumber = highestNumber / 2))
          .updated(peer3, threeAcceptedPeers(peer3).copy(maxBlockNumber = highestNumber / 5))
      )
    )

    pivotBlockSelector ! SelectPivotBlock

    peerMessageBus.expectMsgAllOf(
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id))),
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id))),
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id)))
    )

    expectGetBlockHeadersRequests(Seq(peer1, peer2, peer3), blockNumber = 0)
  }

  it should "retry if there are no enough peers" taggedAs (UnitTest, SyncTest) in new TestSetup {
    updateHandshakedPeers(HandshakedPeers(singlePeer))

    pivotBlockSelector ! SelectPivotBlock

    peerMessageBus.expectNoMessage()

    updateHandshakedPeers(HandshakedPeers(threeAcceptedPeers))

    testScheduler.timePasses(syncConfig.startRetryInterval)

    peerMessageBus.expectMsgAllOf(
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id))),
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id))),
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id)))
    )
  }

  it should "retry if there are no enough votes for one block" taggedAs (UnitTest, SyncTest) in new TestSetup {
    updateHandshakedPeers(HandshakedPeers(threeAcceptedPeers))

    pivotBlockSelector ! SelectPivotBlock

    peerMessageBus.expectMsgAllOf(
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id))),
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id))),
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id)))
    )

    expectGetBlockHeadersRequests(Seq(peer1, peer2, peer3), expectedPivotBlock)

    // Collecting pivot block (for voting)
    pivotBlockSelector ! MessageFromPeer(BlockHeaders(Seq(pivotBlockHeader)), peer1.id)
    pivotBlockSelector ! MessageFromPeer(BlockHeaders(Seq(pivotBlockHeader)), peer2.id)

    // one peer return different header
    pivotBlockSelector ! MessageFromPeer(BlockHeaders(Seq(differentBlockHeader)), peer3.id)

    peerMessageBus.expectMsgAllOf(
      Unsubscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id))),
      Unsubscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id))),
      Unsubscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))),
      Unsubscribe()
    )

    fastSync.expectNoMessage() // consensus not reached - process have to be repeated

    testScheduler.timePasses(syncConfig.startRetryInterval)

    peerMessageBus.expectMsgAllOf(
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id))),
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id))),
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id)))
    )
  }

  it should "find out that there are no enough votes as soon as possible" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup {
    updateHandshakedPeers(HandshakedPeers(threeAcceptedPeers))

    pivotBlockSelector ! SelectPivotBlock

    peerMessageBus.expectMsgAllOf(
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id))),
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id))),
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id)))
    )

    expectGetBlockHeadersRequests(Seq(peer1, peer2, peer3), expectedPivotBlock)

    // Collecting pivot block (for voting)
    pivotBlockSelector ! MessageFromPeer(BlockHeaders(Seq(pivotBlockHeader)), peer1.id)

    // One peer return different header. Because pivotBlockSelector waits only for one peer more - consensus won't be reached
    pivotBlockSelector ! MessageFromPeer(BlockHeaders(Seq(differentBlockHeader)), peer2.id)

    peerMessageBus.expectMsgAllOf(
      Unsubscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id))),
      Unsubscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id))),
      Unsubscribe()
    )

    fastSync.expectNoMessage() // consensus not reached - process have to be repeated

    testScheduler.timePasses(syncConfig.startRetryInterval)

    peerMessageBus.expectMsgAllOf(
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id))),
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id))),
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id)))
    )
  }

  it should "handle case when one peer responded with wrong block header" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup {
    override def minPeersToChoosePivotBlock: Int = 1

    updateHandshakedPeers(HandshakedPeers(singlePeer))

    pivotBlockSelector ! SelectPivotBlock

    peerMessageBus.expectMsgAllOf(
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id)))
    )

    expectGetBlockHeadersRequests(Seq(peer1), expectedPivotBlock)

    // peer responds with block header number
    pivotBlockSelector ! MessageFromPeer(
      BlockHeaders(Seq(pivotBlockHeader.copy(number = expectedPivotBlock + 1))),
      peer1.id
    )

    peerMessageBus.expectMsgAllOf(
      Unsubscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id))),
      Unsubscribe()
    )
    testScheduler.timePasses(syncConfig.syncRetryInterval)

    fastSync.expectNoMessage() // consensus not reached - process have to be repeated
    peerMessageBus.expectNoMessage()
  }

  it should "not ask additional peers if not needed" taggedAs (UnitTest, SyncTest) in new TestSetup {
    override val minPeersToChoosePivotBlock = 2
    override val peersToChoosePivotBlockMargin = 1

    updateHandshakedPeers(HandshakedPeers(allPeers))

    pivotBlockSelector ! SelectPivotBlock

    peerMessageBus.expectMsgAllOf(
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id))),
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id))),
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id)))
    )
    peerMessageBus.expectNoMessage()

    expectGetBlockHeadersRequests(Seq(peer1, peer2, peer3), expectedPivotBlock)
    networkPeerManager.expectNoMessage()

    // Collecting pivot block (for voting)
    pivotBlockSelector ! MessageFromPeer(BlockHeaders(Seq(pivotBlockHeader)), peer1.id)
    pivotBlockSelector ! MessageFromPeer(BlockHeaders(Seq(pivotBlockHeader)), peer2.id)
    pivotBlockSelector ! MessageFromPeer(BlockHeaders(Seq(pivotBlockHeader)), peer3.id)

    peerMessageBus.expectMsgAllOf(
      Unsubscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id))),
      Unsubscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id))),
      Unsubscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))),
      Unsubscribe()
    )
    peerMessageBus.expectNoMessage()

    fastSync.expectMsg(Result(pivotBlockHeader))
  }

  it should "ask additional peers if needed" taggedAs (UnitTest, SyncTest) in new TestSetup {
    override val minPeersToChoosePivotBlock = 2
    override val peersToChoosePivotBlockMargin = 1

    updateHandshakedPeers(HandshakedPeers(allPeers))

    pivotBlockSelector ! SelectPivotBlock

    peerMessageBus.expectMsgAllOf(
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id))),
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id))),
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id)))
    )
    peerMessageBus.expectNoMessage()

    expectGetBlockHeadersRequests(Seq(peer1, peer2, peer3), expectedPivotBlock)
    networkPeerManager.expectNoMessage()

    // Collecting pivot block (for voting)
    pivotBlockSelector ! MessageFromPeer(BlockHeaders(Seq(pivotBlockHeader)), peer1.id)
    pivotBlockSelector ! MessageFromPeer(BlockHeaders(Seq(differentBlockHeader)), peer2.id)
    pivotBlockSelector ! MessageFromPeer(BlockHeaders(Seq(anotherDifferentBlockHeader)), peer3.id)

    peerMessageBus.expectMsgAllOf(
      Unsubscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id))),
      Unsubscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id))),
      Unsubscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))),
      Subscribe(
        MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer4.id))
      ) // Next peer will be asked
    )

    expectGetBlockHeadersRequests(Seq(peer4), expectedPivotBlock)
    pivotBlockSelector ! MessageFromPeer(BlockHeaders(Seq(pivotBlockHeader)), peer4.id)

    peerMessageBus.expectMsgAllOf(
      Unsubscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer4.id))),
      Unsubscribe()
    )
    peerMessageBus.expectNoMessage()

    fastSync.expectMsg(Result(pivotBlockHeader))
  }

  it should "restart whole process after checking additional nodes" taggedAs (UnitTest, SyncTest) in new TestSetup {
    override val minPeersToChoosePivotBlock = 2
    override val peersToChoosePivotBlockMargin = 1

    updateHandshakedPeers(HandshakedPeers(allPeers))

    pivotBlockSelector ! SelectPivotBlock

    peerMessageBus.expectMsgAllOf(
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id))),
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id))),
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id)))
    )
    peerMessageBus.expectNoMessage()

    expectGetBlockHeadersRequests(Seq(peer1, peer2, peer3), expectedPivotBlock)
    networkPeerManager.expectNoMessage()

    // Collecting pivot block (for voting)
    pivotBlockSelector ! MessageFromPeer(BlockHeaders(Seq(pivotBlockHeader)), peer1.id)
    pivotBlockSelector ! MessageFromPeer(BlockHeaders(Seq(differentBlockHeader)), peer2.id)
    pivotBlockSelector ! MessageFromPeer(BlockHeaders(Seq(anotherDifferentBlockHeader)), peer3.id)

    peerMessageBus.expectMsgAllOf(
      Unsubscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id))),
      Unsubscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id))),
      Unsubscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))),
      Subscribe(
        MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer4.id))
      ) // Next peer will be asked
    )

    expectGetBlockHeadersRequests(Seq(peer4), expectedPivotBlock)
    pivotBlockSelector ! MessageFromPeer(BlockHeaders(Seq(nextAnotherDifferentBlockHeader)), peer4.id)

    peerMessageBus.expectMsgAllOf(
      Unsubscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer4.id))),
      Unsubscribe()
    )

    fastSync.expectNoMessage() // consensus not reached - process have to be repeated

    testScheduler.timePasses(syncConfig.startRetryInterval)

    peerMessageBus.expectMsgAllOf(
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id))),
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id))),
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id)))
    )
    peerMessageBus.expectNoMessage()
  }

  it should "check only peers with the highest block at least equal to [bestPeerBestBlockNumber - syncConfig.pivotBlockOffset]" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup {
    updateHandshakedPeers(
      HandshakedPeers(allPeers.updated(peer1, allPeers(peer1).copy(maxBlockNumber = expectedPivotBlock - 1)))
    )

    pivotBlockSelector ! SelectPivotBlock

    peerMessageBus.expectMsgAllOf(
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer2.id))),
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))),
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer4.id)))
    )
    peerMessageBus.expectNoMessage() // Peer 1 will be skipped
  }

  it should "only use only peers from the correct network to choose pivot block" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup() {
    updateHandshakedPeers(HandshakedPeers(peersFromDifferentNetworks))

    pivotBlockSelector ! SelectPivotBlock

    peerMessageBus.expectMsgAllOf(
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id))),
      // Peer 2 is skipped
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))),
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer4.id)))
    )
    peerMessageBus.expectNoMessage()

    expectGetBlockHeadersRequests(Seq(peer1, peer3, peer4), expectedPivotBlock)

    // Collecting pivot block (for voting)
    pivotBlockSelector ! MessageFromPeer(BlockHeaders(Seq(pivotBlockHeader)), peer1.id)
    pivotBlockSelector ! MessageFromPeer(BlockHeaders(Seq(pivotBlockHeader)), peer3.id)
    pivotBlockSelector ! MessageFromPeer(BlockHeaders(Seq(pivotBlockHeader)), peer4.id)

    peerMessageBus.expectMsgAllOf(
      Unsubscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id))),
      Unsubscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))),
      Unsubscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer4.id)))
    )

    fastSync.expectMsg(Result(pivotBlockHeader))
    peerMessageBus.expectMsg(Unsubscribe())
  }

  it should "retry pivot block election with fallback to lower peer numbers" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup {

    override val minPeersToChoosePivotBlock = 2
    override val peersToChoosePivotBlockMargin = 1

    updateHandshakedPeers(
      HandshakedPeers(
        allPeers
          .updated(peer1, allPeers(peer1).copy(maxBlockNumber = 2000))
          .updated(peer2, allPeers(peer2).copy(maxBlockNumber = 800))
          .updated(peer3, allPeers(peer3).copy(maxBlockNumber = 900))
          .updated(peer4, allPeers(peer4).copy(maxBlockNumber = 1400))
      )
    )

    pivotBlockSelector ! SelectPivotBlock

    peerMessageBus.expectMsgAllOf(
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id))),
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))),
      Subscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer4.id)))
    )

    expectGetBlockHeadersRequests(Seq(peer1, peer3, peer4), blockNumber = 900)
    networkPeerManager.expectNoMessage()

    // Collecting pivot block (for voting)
    pivotBlockSelector ! MessageFromPeer(BlockHeaders(Seq(baseBlockHeader.copy(number = 900))), peer1.id)
    pivotBlockSelector ! MessageFromPeer(BlockHeaders(Seq(baseBlockHeader.copy(number = 900))), peer3.id)
    pivotBlockSelector ! MessageFromPeer(BlockHeaders(Seq(baseBlockHeader.copy(number = 900))), peer4.id)

    peerMessageBus.expectMsgAllOf(
      Unsubscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer1.id))),
      Unsubscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer3.id))),
      Unsubscribe(MessageClassifier(Set(Codes.BlockHeadersCode), PeerSelector.WithId(peer4.id))),
      Unsubscribe()
    )
    peerMessageBus.expectNoMessage()

    fastSync.expectMsg(Result(baseBlockHeader.copy(number = 900)))
  }

  class TestSetup extends TestSyncConfig {

    val blacklist: Blacklist = CacheBasedBlacklist.empty(100)

    private def isNewBlock(msg: Message): Boolean = msg match {
      case _: NewBlock => true
      case _           => false
    }

  def expectGetBlockHeadersRequests(peers: Seq[Peer], blockNumber: BigInt): Unit = {
      val expectedPeerIds = peers.map(_.id)
      val receivedMessages = (1 to expectedPeerIds.size).map(_ =>
        networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage]
      )

      expectedPeerIds.foreach { peerId =>
        val sendMsg = receivedMessages.find(_.peerId == peerId).getOrElse(
          fail(s"Expected GetBlockHeaders request for peer $peerId, but received ${receivedMessages.map(_.peerId)}")
        )
        assertGetBlockHeaders(sendMsg.message.underlyingMsg, blockNumber)
      }

      val unexpectedPeers = receivedMessages.map(_.peerId).filterNot(expectedPeerIds.contains)
      withClue(s"Unexpected GetBlockHeaders requests for peers: $unexpectedPeers") {
        unexpectedPeers shouldBe empty
      }
    }

  private def assertGetBlockHeaders(msg: Message, expectedBlockNumber: BigInt): Unit = msg match {
      case GetBlockHeaders(Left(number), maxHeaders, skip, reverse) =>
        number shouldBe expectedBlockNumber
        maxHeaders shouldBe 1
        skip shouldBe 0
        reverse shouldBe false
      case ETH66.GetBlockHeaders(_, Left(number), maxHeaders, skip, reverse) =>
        number shouldBe expectedBlockNumber
        maxHeaders shouldBe 1
        skip shouldBe 0
        reverse shouldBe false
      case other =>
        fail(s"Expected GetBlockHeaders for block $expectedBlockNumber but received $other")
    }

    val networkPeerManager: TestProbe = TestProbe()
    networkPeerManager.ignoreMsg {
      case NetworkPeerManagerActor.SendMessage(msg, _) if isNewBlock(msg.underlyingMsg) => true
      case NetworkPeerManagerActor.GetHandshakedPeers                                   => true
    }

    val peerMessageBus: TestProbe = TestProbe()
    peerMessageBus.ignoreMsg {
      case Subscribe(MessageClassifier(codes, PeerSelector.AllPeers))
          if codes == Set(Codes.NewBlockCode, Codes.NewBlockHashesCode) =>
        true
      case Subscribe(PeerDisconnectedClassifier(_))         => true
      case Unsubscribe(Some(PeerDisconnectedClassifier(_))) => true
    }

    def minPeersToChoosePivotBlock = 3
    def peersToChoosePivotBlockMargin = 1

    override def defaultSyncConfig: SyncConfig = super.defaultSyncConfig.copy(
      doFastSync = true,
      branchResolutionRequestSize = 30,
      checkForNewBlockInterval = 1.second,
      blockHeadersPerRequest = 10,
      blockBodiesPerRequest = 10,
      minPeersToChoosePivotBlock = minPeersToChoosePivotBlock,
      peersToChoosePivotBlockMargin = peersToChoosePivotBlockMargin,
      peersScanInterval = 500.milliseconds,
      peerResponseTimeout = 2.seconds,
      redownloadMissingStateNodes = false,
      fastSyncBlockValidationX = 10,
      blacklistDuration = 1.second
    )

    val fastSync: TestProbe = TestProbe()

    def testScheduler: ExplicitlyTriggeredScheduler = system.scheduler.asInstanceOf[ExplicitlyTriggeredScheduler]

    lazy val pivotBlockSelector: ActorRef = system.actorOf(
      PivotBlockSelector.props(
        networkPeerManager.ref,
        peerMessageBus.ref,
        defaultSyncConfig,
        testScheduler,
        fastSync.ref,
        blacklist
      )
    )

    val baseBlockHeader = Fixtures.Blocks.Genesis.header

    val bestBlock = 400000
    // Ask for pivot block header (the best block from the best peer - offset)
    val expectedPivotBlock: Int = bestBlock - syncConfig.pivotBlockOffset

    val pivotBlockHeader: BlockHeader = baseBlockHeader.copy(number = expectedPivotBlock)
    val differentBlockHeader: BlockHeader =
      baseBlockHeader.copy(number = expectedPivotBlock, extraData = ByteString("different"))
    val anotherDifferentBlockHeader: BlockHeader =
      baseBlockHeader.copy(number = expectedPivotBlock, extraData = ByteString("different2"))
    val nextAnotherDifferentBlockHeader: BlockHeader =
      baseBlockHeader.copy(number = expectedPivotBlock, extraData = ByteString("different3"))

    val peer1TestProbe: TestProbe = TestProbe("peer1")(system)
    val peer2TestProbe: TestProbe = TestProbe("peer2")(system)
    val peer3TestProbe: TestProbe = TestProbe("peer3")(system)
    val peer4TestProbe: TestProbe = TestProbe("peer4")(system)

    val peer1: Peer = Peer(PeerId("peer1"), new InetSocketAddress("127.0.0.1", 0), peer1TestProbe.ref, false)
    val peer2: Peer = Peer(PeerId("peer2"), new InetSocketAddress("127.0.0.2", 0), peer2TestProbe.ref, false)
    val peer3: Peer = Peer(PeerId("peer3"), new InetSocketAddress("127.0.0.3", 0), peer3TestProbe.ref, false)
    val peer4: Peer = Peer(PeerId("peer4"), new InetSocketAddress("127.0.0.4", 0), peer4TestProbe.ref, false)

    val peer1Status: RemoteStatus =
      RemoteStatus(
        Capability.ETH68,
        1,
        ChainWeight.totalDifficultyOnly(20),
        ByteString("peer1_bestHash"),
        ByteString("unused")
      )
    val peer2Status: RemoteStatus = peer1Status.copy(bestHash = ByteString("peer2_bestHash"))
    val peer3Status: RemoteStatus = peer1Status.copy(bestHash = ByteString("peer3_bestHash"))
    val peer4Status: RemoteStatus = peer1Status.copy(bestHash = ByteString("peer4_bestHash"))

    val allPeers: Map[Peer, PeerInfo] = Map(
      peer1 -> PeerInfo(
        peer1Status,
        forkAccepted = true,
        chainWeight = peer1Status.chainWeight,
        maxBlockNumber = bestBlock,
        bestBlockHash = peer1Status.bestHash
      ),
      peer2 -> PeerInfo(
        peer2Status,
        forkAccepted = true,
        chainWeight = peer1Status.chainWeight,
        maxBlockNumber = bestBlock,
        bestBlockHash = peer2Status.bestHash
      ),
      peer3 -> PeerInfo(
        peer3Status,
        forkAccepted = true,
        chainWeight = peer1Status.chainWeight,
        maxBlockNumber = bestBlock,
        bestBlockHash = peer3Status.bestHash
      ),
      peer4 -> PeerInfo(
        peer4Status,
        forkAccepted = true,
        chainWeight = peer1Status.chainWeight,
        maxBlockNumber = bestBlock,
        bestBlockHash = peer4Status.bestHash
      )
    )

    val threeAcceptedPeers: Map[Peer, PeerInfo] = Map(
      peer1 -> PeerInfo(
        peer1Status,
        forkAccepted = true,
        chainWeight = peer1Status.chainWeight,
        maxBlockNumber = bestBlock,
        bestBlockHash = peer1Status.bestHash
      ),
      peer2 -> PeerInfo(
        peer2Status,
        forkAccepted = true,
        chainWeight = peer1Status.chainWeight,
        maxBlockNumber = bestBlock,
        bestBlockHash = peer2Status.bestHash
      ),
      peer3 -> PeerInfo(
        peer3Status,
        forkAccepted = true,
        chainWeight = peer1Status.chainWeight,
        maxBlockNumber = bestBlock,
        bestBlockHash = peer3Status.bestHash
      )
    )

    val singlePeer: Map[Peer, PeerInfo] = Map(
      peer1 -> PeerInfo(
        peer1Status,
        forkAccepted = true,
        chainWeight = peer1Status.chainWeight,
        maxBlockNumber = bestBlock,
        bestBlockHash = peer1Status.bestHash
      )
    )

    val peersFromDifferentNetworks: Map[Peer, PeerInfo] = Map(
      peer1 -> PeerInfo(
        peer1Status,
        forkAccepted = true,
        chainWeight = peer1Status.chainWeight,
        maxBlockNumber = bestBlock,
        bestBlockHash = peer1Status.bestHash
      ),
      peer2 -> PeerInfo(
        peer2Status,
        forkAccepted = false,
        chainWeight = peer1Status.chainWeight,
        maxBlockNumber = bestBlock,
        bestBlockHash = peer2Status.bestHash
      ),
      peer3 -> PeerInfo(
        peer3Status,
        forkAccepted = true,
        chainWeight = peer1Status.chainWeight,
        maxBlockNumber = bestBlock,
        bestBlockHash = peer3Status.bestHash
      ),
      peer4 -> PeerInfo(
        peer4Status,
        forkAccepted = true,
        chainWeight = peer1Status.chainWeight,
        maxBlockNumber = bestBlock,
        bestBlockHash = peer4Status.bestHash
      )
    )

    def updateHandshakedPeers(handshakedPeers: HandshakedPeers): Unit = pivotBlockSelector ! handshakedPeers
  }
}
