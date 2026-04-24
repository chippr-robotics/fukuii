package com.chipprbots.ethereum.blockchain.sync.regular

import java.net.InetSocketAddress

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

import org.scalatest.BeforeAndAfterEach
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.Fixtures.{Blocks => FixtureBlocks}
import com.chipprbots.ethereum.Mocks.MockValidatorsAlwaysSucceed
import com.chipprbots.ethereum.Mocks.MockValidatorsFailingOnBlockBodies
import com.chipprbots.ethereum.Timeouts
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.blockchain.sync.PeersClient
import com.chipprbots.ethereum.blockchain.sync.PeersClient.BlacklistPeer
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.AdaptedMessageFromEventBus
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.InternalLastBlockImport
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.InvalidateBlocksFrom
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.PickBlocks
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.HeadersSeq
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.Subscribe
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.NewBlock
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{BlockBodies => ETH66BlockBodies}
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{BlockHeaders => ETH66BlockHeaders}
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{GetBlockBodies => ETH66GetBlockBodies}
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{GetBlockHeaders => ETH66GetBlockHeaders}
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Config.SyncConfig

class BlockFetcherSpec extends AnyFreeSpecLike with Matchers with BeforeAndAfterEach with SecureRandomBuilder {

  // Track all actor systems created during tests for cleanup
  private var actorSystems: List[ActorSystem] = List.empty

  override def afterEach(): Unit = {
    // Shutdown all actor systems to prevent hanging tests
    actorSystems.foreach { as =>
      try
        TestKit.shutdownActorSystem(as, verifySystemShutdown = false)
      catch {
        case _: Exception => // Ignore errors during cleanup
      }
    }
    actorSystems = List.empty
  }

  "BlockFetcher" - {

    "should not requests headers upon invalidation while a request is already in progress, should resume after response" taggedAs (
      UnitTest,
      SyncTest
    ) in new TestSetup {
      startFetcher()

      handleFirstBlockBatch()

      triggerFetching()

      // handleFirstBlockBatch has already consumed the prefetch headers
      // request for block=11; the sender ref lives in prefetchHeadersSender.
      val refExpectingReply: org.apache.pekko.actor.ActorRef = prefetchHeadersSender
        .getOrElse(fail("Expected prefetch GetBlockHeaders captured by handleFirstBlockBatch"))

      // Give the ask-pattern hop time to deliver the bodies response so
      // blockProviders is populated by the time InvalidateBlocksFrom runs.
      awaitBodiesProcessed()

      // Mark first blocks as invalid, no further request should be done
      blockFetcher ! InvalidateBlocksFrom(1, "")
      peersClient.expectMsgClass(classOf[BlacklistPeer])

      peersClient.expectNoMessage()

      // Respond to the second request should make the fetcher resume with his requests
      val secondBlocksBatch: List[Block] =
        BlockHelpers.generateChain(syncConfig.blockHeadersPerRequest, firstBlocksBatch.last)
      val secondGetBlockHeadersResponse: ETH66BlockHeaders = ETH66BlockHeaders(0, secondBlocksBatch.map(_.header))
      peersClient.send(refExpectingReply, PeersClient.Response(fakePeer, secondGetBlockHeadersResponse))

      peersClient.expectMsgPF() {
        case PeersClient.Request(msg: ETH66GetBlockHeaders, _, _) if msg.block == Left(1) => ()
      }
      shutdownActorSystem()
    }

    "should not requests headers upon invalidation while a request is already in progress, should resume after failure in response" in new TestSetup {
      startFetcher()

      handleFirstBlockBatch()

      triggerFetching()

      val refExpectingReply: org.apache.pekko.actor.ActorRef = prefetchHeadersSender
        .getOrElse(fail("Expected prefetch GetBlockHeaders captured by handleFirstBlockBatch"))

      awaitBodiesProcessed()

      // Mark first blocks as invalid, no further request should be done
      blockFetcher ! InvalidateBlocksFrom(1, "")
      peersClient.expectMsgClass(classOf[BlacklistPeer])

      peersClient.expectNoMessage()

      // Failure of the second request should make the fetcher resume with his requests
      peersClient.send(
        refExpectingReply,
        PeersClient.RequestFailed(fakePeer, BlacklistReason.RegularSyncRequestFailed(""))
      )

      peersClient.expectMsgClass(classOf[BlacklistPeer])
      peersClient.expectMsgPF() {
        case PeersClient.Request(msg: ETH66GetBlockHeaders, _, _) if msg.block == Left(1) => ()
      }
      shutdownActorSystem()
    }

    "should not enqueue requested blocks if the received bodies do not match" in new TestSetup {

      // Important: Here we are forcing the mismatch between request headers and received bodies
      override lazy val validators = new MockValidatorsFailingOnBlockBodies

      startFetcher()

      handleFirstBlockBatch()

      // Fetcher should blacklist the peer and retry asking for the same bodies
      peersClient.expectMsgClass(classOf[BlacklistPeer])
      peersClient.expectMsgPF() {
        case PeersClient.Request(msg: ETH66GetBlockBodies, _, _) if msg.hashes == firstBlocksBatch.map(_.hash) => ()
      }

      // Fetcher should not enqueue any new block
      importer.send(blockFetcher.toClassic, PickBlocks(syncConfig.blocksBatchSize, importer.ref))
      importer.expectNoMessage(100.millis)
      shutdownActorSystem()
    }

    "should be able to handle block bodies received in several parts" in new TestSetup {

      startFetcher()

      // handleFirstBlockBatchHeaders already consumes both follow-ups (the
      // bodies request + the prefetch headers request) and stashes their
      // senders. Use the stashed bodies sender for the partial replies.
      handleFirstBlockBatchHeaders()

      val firstBodiesSender = pendingBodiesSender
        .getOrElse(fail("Expected GetBlockBodies sender captured by handleFirstBlockBatchHeaders"))

      // It will receive all the requested bodies, but splitted in 2 parts.
      val (subChain1, subChain2) = firstBlocksBatch.splitAt(syncConfig.blockBodiesPerRequest / 2)

      val getBlockBodiesResponse1: ETH66BlockBodies = ETH66BlockBodies(0, subChain1.map(_.body))
      firstBodiesSender ! PeersClient.Response(fakePeer, getBlockBodiesResponse1)

      // Second part request
      peersClient.fishForSpecificMessage() {
        case PeersClient.Request(msg: ETH66GetBlockBodies, _, _) if msg.hashes == subChain2.map(_.hash) => true
      }

      val getBlockBodiesResponse2: ETH66BlockBodies = ETH66BlockBodies(0, subChain2.map(_.body))
      peersClient.reply(PeersClient.Response(fakePeer, getBlockBodiesResponse2))

      // We need to wait a while in order to allow fetcher to process all the blocks
      as.scheduler.scheduleOnce(Timeouts.shortTimeout) {
        // Fetcher should enqueue all the received blocks
        importer.send(blockFetcher.toClassic, PickBlocks(firstBlocksBatch.size, importer.ref))
      }

      importer.expectMsgPF() { case BlockFetcher.PickedBlocks(blocks) =>
        blocks.map(_.hash).toList shouldEqual firstBlocksBatch.map(_.hash)
      }
      shutdownActorSystem()
    }

    "should stop requesting, without blacklist the peer, in case empty bodies are received" in new TestSetup {

      startFetcher()

      handleFirstBlockBatchHeaders()

      val firstBodiesSender = pendingBodiesSender
        .getOrElse(fail("Expected GetBlockBodies sender captured by handleFirstBlockBatchHeaders"))

      // It will receive part of the requested bodies.
      val (subChain1, subChain2) = firstBlocksBatch.splitAt(syncConfig.blockBodiesPerRequest / 2)

      val getBlockBodiesResponse1: ETH66BlockBodies = ETH66BlockBodies(0, subChain1.map(_.body))
      firstBodiesSender ! PeersClient.Response(fakePeer, getBlockBodiesResponse1)

      // Second part request
      peersClient.expectMsgPF() {
        case PeersClient.Request(msg: ETH66GetBlockBodies, _, _) if msg.hashes == subChain2.map(_.hash) => ()
      }

      // We receive empty bodies instead of the second part
      val getBlockBodiesResponse2: ETH66BlockBodies = ETH66BlockBodies(0, List())
      peersClient.reply(PeersClient.Response(fakePeer, getBlockBodiesResponse2))

      // If we try to pick the whole chain we should only receive the first part
      importer.send(blockFetcher.toClassic, PickBlocks(firstBlocksBatch.size, importer.ref))
      importer.expectMsgPF() { case BlockFetcher.PickedBlocks(blocks) =>
        blocks.map(_.hash).toList shouldEqual subChain1.map(_.hash)
      }
      shutdownActorSystem()
    }

    "should ensure blocks passed to importer are always forming chain" in new TestSetup {
      startFetcher()

      triggerFetching()

      val secondBlocksBatch: List[Block] =
        BlockHelpers.generateChain(syncConfig.blockHeadersPerRequest, firstBlocksBatch.last)
      val alternativeSecondBlocksBatch: List[Block] =
        BlockHelpers.generateChain(syncConfig.blockHeadersPerRequest, firstBlocksBatch.last)

      handleFirstBlockBatchHeaders()

      // handleFirstBlockBatchHeaders has captured both follow-up senders.
      val refForAnswerFirstBodiesReq = pendingBodiesSender
        .getOrElse(fail("Expected GetBlockBodies sender captured"))
      val refForAnswerSecondHeaderReq = prefetchHeadersSender
        .getOrElse(fail("Expected GetBlockHeaders prefetch sender captured"))

      // Block 16 is mined (we could have reached this stage due to invalidation messages sent to the fetcher)
      val minedBlock: Block = alternativeSecondBlocksBatch.drop(5).head
      val minedBlockNumber = minedBlock.number
      blockFetcher ! InternalLastBlockImport(minedBlockNumber)

      // Answer both pending requests: second headers first, then first bodies.
      val secondGetBlockHeadersResponse: ETH66BlockHeaders = ETH66BlockHeaders(0, secondBlocksBatch.map(_.header))
      refForAnswerSecondHeaderReq ! PeersClient.Response(fakePeer, secondGetBlockHeadersResponse)

      val firstGetBlockBodiesResponse: ETH66BlockBodies = ETH66BlockBodies(0, firstBlocksBatch.map(_.body))
      refForAnswerFirstBodiesReq ! PeersClient.Response(fakePeer, firstGetBlockBodiesResponse)

      // Third headers + second bodies requests should now be in flight.
      peersClient.expectMsgPF() { case PeersClient.Request(_: ETH66GetBlockHeaders, _, _) =>
        peersClient.lastSender
      }

      val refForAnswerSecondBodiesReq: org.apache.pekko.actor.ActorRef = peersClient.expectMsgPF() {
        case PeersClient.Request(_: ETH66GetBlockBodies, _, _) =>
          peersClient.lastSender
      }
      peersClient.send(
        refForAnswerSecondBodiesReq,
        PeersClient.Response(fakePeer, ETH66BlockBodies(0, alternativeSecondBlocksBatch.drop(6).map(_.body)))
      )

      importer.send(blockFetcher.toClassic, PickBlocks(syncConfig.blocksBatchSize, importer.ref))
      importer.expectMsgPF() { case BlockFetcher.PickedBlocks(blocks) =>
        val headers = blocks.map(_.header).toList
        assert(HeadersSeq.areChain(headers))
      }
      shutdownActorSystem()
    }

    "should properly handle a request timeout" in new TestSetup {
      override lazy val syncConfig: SyncConfig = defaultSyncConfig.copy(
        // Small timeout on ask pattern for testing it here
        peerResponseTimeout = 1.seconds
      )

      startFetcher()

      peersClient.expectMsgPF() {
        case PeersClient.Request(msg: ETH66GetBlockHeaders, _, _) if msg.block == Left(1) => ()
      }

      // Verify no message arrives immediately
      peersClient.expectNoMessage(500.millis)

      // Request should timeout and retry - wait for the timeout + retry interval
      peersClient.expectMsgPF(syncConfig.peerResponseTimeout + 5.seconds) {
        case PeersClient.Request(msg: ETH66GetBlockHeaders, _, _) if msg.block == Left(1) => ()
      }
      shutdownActorSystem()
    }
  }

  trait TestSetup extends TestSyncConfig {
    val as: ActorSystem = {
      val system = ActorSystem("BlockFetcherSpec_System")
      actorSystems = system :: actorSystems
      system
    }
    val atks: ActorTestKit = ActorTestKit(as.toTyped)

    val peersClient: TestProbe = TestProbe()(as)
    val peerEventBus: TestProbe = TestProbe()(as)
    val importer: TestProbe = TestProbe()(as)
    val regularSync: TestProbe = TestProbe()(as)

    lazy val validators = new MockValidatorsAlwaysSucceed

    override lazy val syncConfig: Config.SyncConfig = defaultSyncConfig.copy(
      // Same request size was selected for simplification purposes of the flow
      blockHeadersPerRequest = 10,
      blockBodiesPerRequest = 10,
      blocksBatchSize = 10,
      // Huge timeout on ask pattern
      peerResponseTimeout = 5.minutes
    )

    val fakePeerActor: TestProbe = TestProbe()(as)
    val fakePeer: Peer = Peer(PeerId("fakePeer"), new InetSocketAddress("127.0.0.1", 9000), fakePeerActor.ref, false)

    lazy val blockFetcher: ActorRef[BlockFetcher.FetchCommand] = atks.spawn(
      BlockFetcher(
        peersClient.ref,
        peerEventBus.ref,
        regularSync.ref,
        syncConfig,
        validators.blockValidator
      )
    )

    def startFetcher(): Unit = {
      blockFetcher ! BlockFetcher.Start(importer.ref, 0)

      peerEventBus.expectMsg(
        Subscribe(
          MessageClassifier(
            Set(Codes.NewBlockCode, Codes.NewBlockHashesCode, Codes.BlockHeadersCode),
            PeerSelector.AllPeers
          )
        )
      )
    }

    def shutdownActorSystem(): Unit = {
      atks.shutdownTestKit()
      TestKit.shutdownActorSystem(as, verifySystemShutdown = true)
    }

    // Sending a far away block as a NewBlock message
    // Currently BlockFetcher only downloads first block-headers-per-request blocks without this
    def triggerFetching(startingNumber: BigInt = 1000): Unit = {
      val farAwayBlockTotalDifficulty = 100000
      val farAwayBlock =
        Block(FixtureBlocks.ValidBlock.header.copy(number = startingNumber), FixtureBlocks.ValidBlock.body)

      blockFetcher ! AdaptedMessageFromEventBus(NewBlock(farAwayBlock, farAwayBlockTotalDifficulty), fakePeer.id)
    }

    val firstBlocksBatch: List[Block] =
      BlockHelpers.generateChain(syncConfig.blockHeadersPerRequest, FixtureBlocks.Genesis.block)

    // Saved senders for the two parallel follow-ups BlockFetcher emits after
    // the first headers response: GetBlockBodies and GetBlockHeaders(block=
    // last+1) prefetch. Their mailbox order isn't guaranteed.
    var prefetchHeadersSender: Option[org.apache.pekko.actor.ActorRef] = None
    var pendingBodiesSender: Option[org.apache.pekko.actor.ActorRef] = None

    def handleFirstBlockBatchHeaders(): Unit = {
      val requestId = peersClient.expectMsgPF() {
        case PeersClient.Request(msg: ETH66GetBlockHeaders, _, _) if msg.block == Left(1) => msg.requestId
      }
      val firstGetBlockHeadersResponse = ETH66BlockHeaders(requestId, firstBlocksBatch.map(_.header))
      peersClient.reply(PeersClient.Response(fakePeer, firstGetBlockHeadersResponse))

      def classifyNext(): Unit = peersClient.expectMsgPF() {
        case PeersClient.Request(msg: ETH66GetBlockBodies, _, _) if msg.hashes == firstBlocksBatch.map(_.hash) =>
          pendingBodiesSender = Some(peersClient.lastSender)
        case PeersClient.Request(msg: ETH66GetBlockHeaders, _, _)
            if msg.block == Left(firstBlocksBatch.last.number + 1) =>
          prefetchHeadersSender = Some(peersClient.lastSender)
      }
      classifyNext()
      classifyNext()
    }

    def handleFirstBlockBatchBodies(): Unit = {
      val sender = pendingBodiesSender.getOrElse(
        fail("Expected GetBlockBodies sender captured by handleFirstBlockBatchHeaders")
      )
      sender ! PeersClient.Response(fakePeer, ETH66BlockBodies(0, firstBlocksBatch.map(_.body)))
    }

    /** Synchronise on BlockFetcher having finished processing the bodies response. 1s is well above observed CI latency
      * for a single message hop; short sleep keeps local runs fast.
      */
    def awaitBodiesProcessed(): Unit = Thread.sleep(1000L)

    def handleFirstBlockBatch(): Unit = {
      handleFirstBlockBatchHeaders()
      handleFirstBlockBatchBodies()
    }
  }
}
