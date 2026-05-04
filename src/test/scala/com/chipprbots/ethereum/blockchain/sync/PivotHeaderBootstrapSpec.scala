package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.{Actor, ActorRef, ActorSystem, Props}
import org.apache.pekko.testkit.{ImplicitSender, TestKit, TestProbe}

import java.net.InetSocketAddress
import scala.concurrent.duration._

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.db.dataSource.{DataSourceBatchUpdate, EphemDataSource}
import com.chipprbots.ethereum.domain.{BlockHeader, BlockchainWriter}
import com.chipprbots.ethereum.network.{Peer, PeerId}
import com.chipprbots.ethereum.network.p2p.messages.ETH66
import com.chipprbots.ethereum.testing.Tags._

class PivotHeaderBootstrapSpec
    extends TestKit(ActorSystem("PivotHeaderBootstrapSpec"))
    with ImplicitSender
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  val targetBlock: BigInt = 1000
  val correctHeader: BlockHeader = Fixtures.Blocks.Block3125369.header.copy(number = targetBlock)
  val wrongHeader: BlockHeader   = Fixtures.Blocks.Block3125369.header.copy(number = BigInt(999))

  val ds        = EphemDataSource()
  val noopBatch = DataSourceBatchUpdate(ds, Array.empty)

  val noopWriter: BlockchainWriter = new BlockchainWriter(null, null, null, null, null, null, null) {
    override def storeBlockHeader(blockHeader: BlockHeader): DataSourceBatchUpdate = noopBatch
  }

  val throwingWriter: BlockchainWriter = new BlockchainWriter(null, null, null, null, null, null, null) {
    override def storeBlockHeader(blockHeader: BlockHeader): DataSourceBatchUpdate =
      throw new RuntimeException("storage error")
  }

  val testPeer: Peer = Peer(PeerId("test-peer"), new InetSocketAddress("127.0.0.1", 9999), TestProbe().ref, false)

  /** Creates a PivotHeaderBootstrap under a wrapper actor that forwards all parent messages to parentProbe. */
  def mkBootstrap(
      peersClientProbe: TestProbe,
      parentProbe: TestProbe,
      writer: BlockchainWriter = noopWriter,
      maxAttempts: Int = 1,
      retryDelay: FiniteDuration = 50.millis,
      preferSnapPeers: Boolean = false
  ): ActorRef = {
    val bootstrapProps = PivotHeaderBootstrap.props(
      peersClient = peersClientProbe.ref,
      blockchainWriter = writer,
      targetBlock = targetBlock,
      syncConfig = null,
      scheduler = system.scheduler,
      maxAttempts = maxAttempts,
      initialRetryDelay = retryDelay,
      maxRetryDelay = retryDelay,
      preferSnapPeers = preferSnapPeers
    )(system.dispatcher)

    system.actorOf(Props(new Actor {
      override def preStart(): Unit = context.actorOf(bootstrapProps, "phb")
      override def receive: Receive = { case msg => parentProbe.ref forward msg }
    }))
  }

  "PivotHeaderBootstrap" should "send Completed to parent when peer returns the correct header" taggedAs (UnitTest, SyncTest) in {
    val peersClient = TestProbe()
    val parent      = TestProbe()
    mkBootstrap(peersClient, parent)

    peersClient.expectMsgType[PeersClient.Request[ETH66.GetBlockHeaders]](3.seconds)
    peersClient.reply(PeersClient.Response(testPeer, ETH66.BlockHeaders(0, Seq(correctHeader))))

    parent.expectMsg(3.seconds, PivotHeaderBootstrap.Completed(targetBlock, correctHeader))
  }

  it should "send Failed after maxAttempts when peer returns a wrong-number header" taggedAs (UnitTest, SyncTest) in {
    val peersClient = TestProbe()
    val parent      = TestProbe()
    mkBootstrap(peersClient, parent)

    peersClient.expectMsgType[PeersClient.Request[ETH66.GetBlockHeaders]](3.seconds)
    peersClient.reply(PeersClient.Response(testPeer, ETH66.BlockHeaders(0, Seq(wrongHeader))))

    parent.expectMsgType[PivotHeaderBootstrap.Failed](3.seconds)
  }

  it should "send Failed after maxAttempts when peer returns an empty headers list" taggedAs (UnitTest, SyncTest) in {
    val peersClient = TestProbe()
    val parent      = TestProbe()
    mkBootstrap(peersClient, parent)

    peersClient.expectMsgType[PeersClient.Request[ETH66.GetBlockHeaders]](3.seconds)
    peersClient.reply(PeersClient.Response(testPeer, ETH66.BlockHeaders(0, Seq.empty)))

    parent.expectMsgType[PivotHeaderBootstrap.Failed](3.seconds)
  }

  it should "send Failed after maxAttempts on RequestFailed" taggedAs (UnitTest, SyncTest) in {
    val peersClient = TestProbe()
    val parent      = TestProbe()
    mkBootstrap(peersClient, parent)

    peersClient.expectMsgType[PeersClient.Request[ETH66.GetBlockHeaders]](3.seconds)
    peersClient.reply(PeersClient.RequestFailed(testPeer, BlacklistReason.RegularSyncRequestFailed("timeout")))

    parent.expectMsgType[PivotHeaderBootstrap.Failed](3.seconds)
  }

  it should "fall back to BestPeerWithMinBlock and complete when preferSnapPeers is set but no SNAP peer available" taggedAs (UnitTest, SyncTest) in {
    val peersClient = TestProbe()
    val parent      = TestProbe()
    mkBootstrap(peersClient, parent, preferSnapPeers = true)

    // First request uses BestSnapPeer — no SNAP peer available
    peersClient.expectMsgType[PeersClient.Request[ETH66.GetBlockHeaders]](3.seconds)
    peersClient.reply(PeersClient.NoSuitablePeer)

    // Fallback request uses BestPeerWithMinBlock — peer responds with the correct header
    peersClient.expectMsgType[PeersClient.Request[ETH66.GetBlockHeaders]](3.seconds)
    peersClient.reply(PeersClient.Response(testPeer, ETH66.BlockHeaders(0, Seq(correctHeader))))

    parent.expectMsg(3.seconds, PivotHeaderBootstrap.Completed(targetBlock, correctHeader))
  }

  it should "send Failed to parent when blockchainWriter throws during storeBlockHeader" taggedAs (UnitTest, SyncTest) in {
    val peersClient = TestProbe()
    val parent      = TestProbe()
    mkBootstrap(peersClient, parent, writer = throwingWriter)

    peersClient.expectMsgType[PeersClient.Request[ETH66.GetBlockHeaders]](3.seconds)
    peersClient.reply(PeersClient.Response(testPeer, ETH66.BlockHeaders(0, Seq(correctHeader))))

    parent.expectMsgType[PivotHeaderBootstrap.Failed](3.seconds)
  }
}
