package com.chipprbots.ethereum.blockchain.sync.regular

import java.net.InetSocketAddress
import java.util.UUID

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

import com.typesafe.config.ConfigFactory
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.Fixtures.Blocks as FixtureBlocks
import com.chipprbots.ethereum.Mocks.MockValidatorsAlwaysSucceed
import com.chipprbots.ethereum.Mocks.MockValidatorsFailingOnBlockBodies
import com.chipprbots.ethereum.Timeouts
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.blockchain.sync.PeersClient
import com.chipprbots.ethereum.blockchain.sync.PeersClient.BlacklistPeer
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.AdaptedMessageFromEventBus
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.FetchResponse
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.InternalLastBlockImport
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.InvalidateBlocksFrom
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.PickBlocks
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImporter
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.HeadersSeq
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscribeCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockRangeUpdate
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlock
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetByteCodes
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Config.SyncConfig

class BlockFetcherSpec
    extends ScalaTestWithActorTestKit(ConfigFactory.load())
    with AnyFreeSpecLike
    with Matchers
    with SecureRandomBuilder:

  "BlockFetcher" - {

    "should not requests headers upon invalidation while a request is already in progress, should resume after response" taggedAs (
      UnitTest,
      SyncTest
    ) in new TestSetup:
      startFetcher()

      handleFirstBlockBatch()

      triggerFetching()

      // handleFirstBlockBatch has already consumed the prefetch headers
      // request for block=11; the sender ref lives in prefetchHeadersSender.
      val refExpectingReply: ActorRef[PeersClient.ResponseMessage] = prefetchHeadersSender
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
      val secondGetBlockHeadersResponse: ETHPackets.BlockHeaders =
        ETHPackets.BlockHeaders(BigInt(0), secondBlocksBatch.map(_.header))
      refExpectingReply ! PeersClient.Response(fakePeer, secondGetBlockHeadersResponse)

      peersClient.expectMsgPF() {
        case PeersClient.Request(msg: ETHPackets.GetBlockHeaders, _, _, _) if msg.block == Left(1) => ()
      }
      testKit.stop(blockFetcher)

    "should not requests headers upon invalidation while a request is already in progress, should resume after failure in response" in new TestSetup:
      startFetcher()

      handleFirstBlockBatch()

      triggerFetching()

      val refExpectingReply: ActorRef[PeersClient.ResponseMessage] = prefetchHeadersSender
        .getOrElse(fail("Expected prefetch GetBlockHeaders captured by handleFirstBlockBatch"))

      awaitBodiesProcessed()

      // Mark first blocks as invalid, no further request should be done
      blockFetcher ! InvalidateBlocksFrom(1, "")
      peersClient.expectMsgClass(classOf[BlacklistPeer])

      peersClient.expectNoMessage()

      // Failure of the second request should make the fetcher resume with his requests
      refExpectingReply ! PeersClient.RequestFailed(fakePeer, BlacklistReason.RegularSyncRequestFailed(""))

      peersClient.expectMsgClass(classOf[BlacklistPeer])
      peersClient.expectMsgPF() {
        case PeersClient.Request(msg: ETHPackets.GetBlockHeaders, _, _, _) if msg.block == Left(1) => ()
      }
      testKit.stop(blockFetcher)

    "should not enqueue requested blocks if the received bodies do not match" in new TestSetup:

      // Important: Here we are forcing the mismatch between request headers and received bodies
      override lazy val validators = new MockValidatorsFailingOnBlockBodies

      startFetcher()

      handleFirstBlockBatch()

      // Fetcher should blacklist the peer and retry asking for the same bodies
      peersClient.expectMsgClass(classOf[BlacklistPeer])
      peersClient.expectMsgPF() {
        case PeersClient.Request(msg: ETHPackets.GetBlockBodies, _, _, _)
            if msg.hashes == firstBlocksBatch.map(_.hash) =>
          ()
      }

      // Fetcher should not enqueue any new block
      importer.send(blockFetcher.toClassic, PickBlocks(syncConfig.blocksBatchSize, importer.ref.toTyped[FetchResponse]))
      importer.expectNoMessage(100.millis)
      testKit.stop(blockFetcher)

    "should be able to handle block bodies received in several parts" in new TestSetup:

      startFetcher()

      // handleFirstBlockBatchHeaders already consumes both follow-ups (the
      // bodies request + the prefetch headers request) and stashes their
      // senders. Use the stashed bodies sender for the partial replies.
      handleFirstBlockBatchHeaders()

      val firstBodiesSender: ActorRef[PeersClient.ResponseMessage] = pendingBodiesSender
        .getOrElse(fail("Expected GetBlockBodies reply address captured by handleFirstBlockBatchHeaders"))

      // It will receive all the requested bodies, but splitted in 2 parts.
      val (subChain1, subChain2) = firstBlocksBatch.splitAt(syncConfig.blockBodiesPerRequest / 2)

      val getBlockBodiesResponse1: ETHPackets.BlockBodies = ETHPackets.BlockBodies(BigInt(0), subChain1.map(_.body))
      firstBodiesSender ! PeersClient.Response(fakePeer, getBlockBodiesResponse1)

      // Second part request
      val secondBodiesReplyTo: ActorRef[PeersClient.ResponseMessage] = peersClient.fishForSpecificMessage() {
        case PeersClient.Request(msg: ETHPackets.GetBlockBodies, _, _, replyTo)
            if msg.hashes == subChain2.map(_.hash) =>
          replyTo
      }

      val getBlockBodiesResponse2: ETHPackets.BlockBodies = ETHPackets.BlockBodies(BigInt(0), subChain2.map(_.body))
      secondBodiesReplyTo ! PeersClient.Response(fakePeer, getBlockBodiesResponse2)

      // We need to wait a while in order to allow fetcher to process all the blocks
      testKit.system.classicSystem.scheduler.scheduleOnce(Timeouts.shortTimeout) {
        // Fetcher should enqueue all the received blocks
        importer.send(blockFetcher.toClassic, PickBlocks(firstBlocksBatch.size, importer.ref.toTyped[FetchResponse]))
      }

      importer.expectMsgPF() { case BlockFetcher.PickedBlocks(blocks) =>
        blocks.map(_.hash).toList shouldEqual firstBlocksBatch.map(_.hash)
      }
      testKit.stop(blockFetcher)

    "should stop requesting, without blacklist the peer, in case empty bodies are received" in new TestSetup:

      startFetcher()

      handleFirstBlockBatchHeaders()

      val firstBodiesSender: ActorRef[PeersClient.ResponseMessage] = pendingBodiesSender
        .getOrElse(fail("Expected GetBlockBodies reply address captured by handleFirstBlockBatchHeaders"))

      // It will receive part of the requested bodies.
      val (subChain1, subChain2) = firstBlocksBatch.splitAt(syncConfig.blockBodiesPerRequest / 2)

      val getBlockBodiesResponse1: ETHPackets.BlockBodies = ETHPackets.BlockBodies(BigInt(0), subChain1.map(_.body))
      firstBodiesSender ! PeersClient.Response(fakePeer, getBlockBodiesResponse1)

      // Second part request
      val secondBodiesReplyTo: ActorRef[PeersClient.ResponseMessage] = peersClient.expectMsgPF() {
        case PeersClient.Request(msg: ETHPackets.GetBlockBodies, _, _, replyTo)
            if msg.hashes == subChain2.map(_.hash) =>
          replyTo
      }

      // We receive empty bodies instead of the second part
      val getBlockBodiesResponse2: ETHPackets.BlockBodies = ETHPackets.BlockBodies(BigInt(0), List())
      secondBodiesReplyTo ! PeersClient.Response(fakePeer, getBlockBodiesResponse2)

      // If we try to pick the whole chain we should only receive the first part
      importer.send(blockFetcher.toClassic, PickBlocks(firstBlocksBatch.size, importer.ref.toTyped[FetchResponse]))
      importer.expectMsgPF() { case BlockFetcher.PickedBlocks(blocks) =>
        blocks.map(_.hash).toList shouldEqual subChain1.map(_.hash)
      }
      testKit.stop(blockFetcher)

    "should ensure blocks passed to importer are always forming chain" in new TestSetup:
      startFetcher()

      triggerFetching()

      val secondBlocksBatch: List[Block] =
        BlockHelpers.generateChain(syncConfig.blockHeadersPerRequest, firstBlocksBatch.last)
      val alternativeSecondBlocksBatch: List[Block] =
        BlockHelpers.generateChain(syncConfig.blockHeadersPerRequest, firstBlocksBatch.last)

      handleFirstBlockBatchHeaders()

      // handleFirstBlockBatchHeaders has captured both follow-up reply addresses.
      val refForAnswerFirstBodiesReq: ActorRef[PeersClient.ResponseMessage] = pendingBodiesSender
        .getOrElse(fail("Expected GetBlockBodies reply address captured"))
      val refForAnswerSecondHeaderReq: ActorRef[PeersClient.ResponseMessage] = prefetchHeadersSender
        .getOrElse(fail("Expected GetBlockHeaders prefetch reply address captured"))

      // Block 16 is mined (we could have reached this stage due to invalidation messages sent to the fetcher)
      val minedBlock: Block = alternativeSecondBlocksBatch.drop(5).head
      val minedBlockNumber = minedBlock.number.value
      blockFetcher ! InternalLastBlockImport(minedBlockNumber)

      // Answer both pending requests: second headers first, then first bodies.
      val secondGetBlockHeadersResponse: ETHPackets.BlockHeaders =
        ETHPackets.BlockHeaders(BigInt(0), secondBlocksBatch.map(_.header))
      refForAnswerSecondHeaderReq ! PeersClient.Response(fakePeer, secondGetBlockHeadersResponse)

      val firstGetBlockBodiesResponse: ETHPackets.BlockBodies =
        ETHPackets.BlockBodies(BigInt(0), firstBlocksBatch.map(_.body))
      refForAnswerFirstBodiesReq ! PeersClient.Response(fakePeer, firstGetBlockBodiesResponse)

      // Third headers + second bodies requests are both in flight; their arrival order is
      // non-deterministic (two independent ask -> IO -> pipeToSelf hops on the shared IORuntime),
      // so a bare expectMsgPF expecting the headers request first raced the order and flaked in
      // release CI — when bodies arrived first the headers PF hit a MatchError. We only need the
      // bodies sender (to answer it); the prefetch headers request is incidental here (never
      // answered), so fish for the bodies request specifically and tolerate it in any position.
      val refForAnswerSecondBodiesReq: ActorRef[PeersClient.ResponseMessage] =
        peersClient.fishForSpecificMessage(Timeouts.normalTimeout) {
          case PeersClient.Request(_: ETHPackets.GetBlockBodies, _, _, replyTo) => replyTo
        }
      refForAnswerSecondBodiesReq ! PeersClient.Response(
        fakePeer,
        ETHPackets.BlockBodies(BigInt(0), alternativeSecondBlocksBatch.drop(6).map(_.body))
      )

      importer.send(blockFetcher.toClassic, PickBlocks(syncConfig.blocksBatchSize, importer.ref.toTyped[FetchResponse]))
      importer.expectMsgPF(Timeouts.normalTimeout) { case BlockFetcher.PickedBlocks(blocks) =>
        val headers = blocks.map(_.header).toList
        assert(HeadersSeq.areChain(headers))
      }
      testKit.stop(blockFetcher)

    // BF-1A: ETH/69 head-following via BlockRangeUpdate
    "should spawn its state-node fetcher again when it stops, and answer the fetch it held as exhausted (#1694)" taggedAs (
      UnitTest,
      SyncTest
    ) in new TestSetup:
      // Nothing supervises that child. Left dead, every fetch after it went to dead letters, and the importer, which
      // escalates on exhausted fetches only, waited on a missing node for good (forge).
      startFetcher() // answered, so the fetcher and its children are up
      val importerReplies: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[FetchResponse] =
        testKit.createTestProbe[FetchResponse]()
      def fetchCode(): Unit =
        blockFetcher ! BlockFetcher.FetchStateNode(
          ByteString(Array.fill[Byte](32)(0x5a)),
          importerReplies.ref,
          isByteCode = true
        )
        peersClient.fishForMessage(3.seconds) {
          case PeersClient.Request(_: GetByteCodes, _, _, _) => true
          case _                                             => false
        }

      fetchCode()
      testKit.system.classicSystem.actorSelection(blockFetcher.path / "state-node-fetcher") !
        org.apache.pekko.actor.PoisonPill

      importerReplies.expectMessage(5.seconds, BlockFetcher.FetchedStateNode(ETHPackets.NodeData(Nil)))
      fetchCode() // asked of a peer again, by the new one

    "should include BlockRangeUpdateCode in peer event subscription" taggedAs (UnitTest, SyncTest) in new TestSetup:
      blockFetcher ! BlockFetcher.Start(importer.ref.toTyped[BlockImporter.Command], 0)
      val sub: SubscribeCmd = peerEventBus.expectMsgType[SubscribeCmd]
      sub.to match
        case MessageClassifier(codes, _) =>
          codes should contain(Codes.BlockRangeUpdateCode)
        case _ => fail("Expected MessageClassifier subscription")
      testKit.stop(blockFetcher)

    // BF-1A: GetBlockHeaders(block=Left(1)) originates from the initial Start dispatch (nextDispatchBlock=1),
    // not from the BRU handler. Real BRU decode-path coverage (malformed disconnect + withPossibleNewTopAt
    // via GotNewBlock) is in BlockRangeUpdateDecodePathSpec.
    "should request headers when BlockRangeUpdate announces a new chain tip" taggedAs (
      UnitTest,
      SyncTest
    ) in new TestSetup:
      startFetcher()
      // ETH/69 peer announces latest block at 100; fetcher should immediately request headers
      val update: BlockRangeUpdate =
        ETHPackets.BlockRangeUpdate(BigInt(0), BigInt(100), org.apache.pekko.util.ByteString.empty)
      blockFetcher ! AdaptedMessageFromEventBus(update, fakePeer.id)
      peersClient.expectMsgPF() {
        case PeersClient.Request(msg: ETHPackets.GetBlockHeaders, _, _, _) if msg.block == Left(1) => ()
      }
      testKit.stop(blockFetcher)

    // BF-1B: PrintStatus heartbeat probes for next block when on top
    "should probe for the next block via PrintStatus when isOnTop" taggedAs (UnitTest, SyncTest) in new TestSetup:
      // Start from block 5; knownTop initialises to 6 so fetcher immediately requests block 6
      startFetcher(fromBlock = 5)
      // Consume the initial GetBlockHeaders(6) request triggered by fetchBlocks at Start
      val initSender: ActorRef[PeersClient.ResponseMessage] = peersClient.expectMsgPF() {
        case PeersClient.Request(msg: ETHPackets.GetBlockHeaders, _, _, replyTo) if msg.block == Left(BigInt(6)) =>
          replyTo
      }
      // Reply with a single header at block 6 — partial batch (blockHeadersPerRequest=10).
      // Generate a parent block at number 5 so the chain starts at 6.
      val parentAt5: Block = BlockHelpers.generateChain(5, FixtureBlocks.Genesis.block).last
      val singleBlock: Block = BlockHelpers.generateChain(1, parentAt5).head
      val singleHeader = singleBlock.header
      initSender ! PeersClient.Response(fakePeer, ETHPackets.BlockHeaders(BigInt(0), List(singleHeader)))
      // BlockFetcher now requests bodies for block 6
      val bodiesSender: ActorRef[PeersClient.ResponseMessage] = peersClient.expectMsgPF() {
        case PeersClient.Request(_: ETHPackets.GetBlockBodies, _, _, replyTo) =>
          replyTo
      }
      bodiesSender ! PeersClient.Response(fakePeer, ETHPackets.BlockBodies(BigInt(0), List(singleBlock.body)))
      // Importer picks the block; this advances lastBlock to 6 so isOnTop becomes true.
      // expectNoMessage gives BlockFetcher time to process the bodies and update state.
      peersClient.expectNoMessage(300.millis)
      importer.send(blockFetcher.toClassic, PickBlocks(1, importer.ref.toTyped[FetchResponse]))
      importer.expectMsgPF() { case BlockFetcher.PickedBlocks(_) => () }
      // isOnTop=true now (set during PickBlocks processing above); PrintStatus should probe for block 7
      blockFetcher ! BlockFetcher.PrintStatus
      // Use fishForSpecificMessage to tolerate any intermediate messages (e.g. status logs)
      peersClient.fishForSpecificMessage() {
        case PeersClient.Request(msg: ETHPackets.GetBlockHeaders, _, _, _) if msg.block == Left(BigInt(7)) => true
      }
      testKit.stop(blockFetcher)

    // BF-2: partial header batch still advances nextBlockToFetch correctly
    "should fetch the next window after a partial header batch response" taggedAs (
      UnitTest,
      SyncTest
    ) in new TestSetup:
      startFetcher()
      // Trigger to set knownTop=1000 (high, so fetcher knows more blocks exist)
      triggerFetching(1000)
      val requestSender: ActorRef[PeersClient.ResponseMessage] = peersClient.expectMsgPF() {
        case PeersClient.Request(msg: ETHPackets.GetBlockHeaders, _, _, replyTo) if msg.block == Left(1) =>
          replyTo
      }
      // Reply with a partial batch (fewer than blockHeadersPerRequest=10 headers, stopping at block 5)
      val partialBatch: List[Block] = BlockHelpers.generateChain(5, FixtureBlocks.Genesis.block)
      requestSender ! PeersClient.Response(fakePeer, ETHPackets.BlockHeaders(BigInt(0), partialBatch.map(_.header)))
      // After a partial batch, fetcher should request the next window starting at block 6
      peersClient.fishForSpecificMessage() {
        case PeersClient.Request(msg: ETHPackets.GetBlockHeaders, _, _, _) if msg.block == Left(6) => true
      }
      testKit.stop(blockFetcher)

    "should properly handle a request timeout" in new TestSetup:
      override lazy val syncConfig: SyncConfig = defaultSyncConfig.copy(
        // Small timeout on ask pattern for testing it here
        peerResponseTimeout = 1.seconds
      )

      startFetcher()

      peersClient.expectMsgPF() {
        case PeersClient.Request(msg: ETHPackets.GetBlockHeaders, _, _, _) if msg.block == Left(1) => ()
      }

      // Verify no message arrives immediately
      peersClient.expectNoMessage(500.millis)

      // Request should timeout and retry - wait for the timeout + retry interval
      peersClient.expectMsgPF(syncConfig.peerResponseTimeout + 5.seconds) {
        case PeersClient.Request(msg: ETHPackets.GetBlockHeaders, _, _, _) if msg.block == Left(1) => ()
      }
      testKit.stop(blockFetcher)

    // P9: context.watchWith — ChildStopped is delivered as a typed FetchCommand instead of Terminated
    "should continue processing after receiving ChildStopped from a watched child" taggedAs (
      UnitTest,
      SyncTest
    ) in new TestSetup:
      startFetcher()

      // Drain the initial in-flight GetBlockHeaders request and reply with empty headers so
      // inFlightHeaders returns to 0 (the slot must be free before TickFetch can dispatch again).
      val initReplyTo: ActorRef[PeersClient.ResponseMessage] = peersClient.expectMsgPF() {
        case PeersClient.Request(msg: ETHPackets.GetBlockHeaders, _, _, replyTo) if msg.block == Left(1) =>
          replyTo
      }
      initReplyTo ! PeersClient.Response(fakePeer, ETHPackets.BlockHeaders(BigInt(0), List.empty))

      // Inject ChildStopped directly — simulates watchWith delivery when a child terminates.
      // The fetcher must absorb the message without crashing (Behaviors.same).
      blockFetcher ! BlockFetcher.ChildStopped("headers-fetcher")

      // Fetcher must remain alive and responsive: TickFetch re-evaluates dispatch and sends
      // another GetBlockHeaders when the slot is available.
      blockFetcher ! BlockFetcher.TickFetch
      peersClient.expectMsgPF() {
        case PeersClient.Request(msg: ETHPackets.GetBlockHeaders, _, _, _) if msg.block == Left(1) => ()
      }
      testKit.stop(blockFetcher)
  }

  trait TestSetup extends TestSyncConfig:
    val peersClient: TestProbe = TestProbe()(using testKit.system.classicSystem)
    val peerEventBus: TestProbe = TestProbe()(using testKit.system.classicSystem)
    val importer: TestProbe = TestProbe()(using testKit.system.classicSystem)
    val regularSync: TestProbe = TestProbe()(using testKit.system.classicSystem)

    lazy val validators = new MockValidatorsAlwaysSucceed

    override lazy val syncConfig: Config.SyncConfig = defaultSyncConfig.copy(
      // Same request size was selected for simplification purposes of the flow
      blockHeadersPerRequest = 10,
      blockBodiesPerRequest = 10,
      blocksBatchSize = 10,
      // Huge timeout on ask pattern
      peerResponseTimeout = 5.minutes
    )

    val fakePeerActor: TestProbe = TestProbe()(using testKit.system.classicSystem)
    val fakePeer: Peer = Peer(PeerId("fakePeer"), new InetSocketAddress("127.0.0.1", 9000), fakePeerActor.ref, false)

    lazy val blockFetcher: ActorRef[BlockFetcher.FetchCommand] = testKit.spawn(
      BlockFetcher(
        peersClient.ref.toTyped[PeersClient.Command],
        peerEventBus.ref,
        regularSync.ref,
        syncConfig,
        validators.blockValidator
      ),
      s"blockFetcher-${UUID.randomUUID()}"
    )

    def startFetcher(fromBlock: BigInt = 0): Unit =
      blockFetcher ! BlockFetcher.Start(importer.ref.toTyped[BlockImporter.Command], fromBlock)

      peerEventBus.expectMsgType[SubscribeCmd].to shouldBe MessageClassifier(
        Set(Codes.NewBlockCode, Codes.NewBlockHashesCode, Codes.BlockHeadersCode, Codes.BlockRangeUpdateCode),
        PeerSelector.AllPeers
      )

    // Sending a far away block as a NewBlock message
    // Currently BlockFetcher only downloads first block-headers-per-request blocks without this
    def triggerFetching(startingNumber: BigInt = 1000): Unit =
      val farAwayBlockTotalDifficulty = 100000
      val farAwayBlock =
        Block(FixtureBlocks.ValidBlock.header.copy(number = BlockNumber(startingNumber)), FixtureBlocks.ValidBlock.body)

      blockFetcher ! AdaptedMessageFromEventBus(NewBlock(farAwayBlock, farAwayBlockTotalDifficulty), fakePeer.id)

    val firstBlocksBatch: List[Block] =
      BlockHelpers.generateChain(syncConfig.blockHeadersPerRequest, FixtureBlocks.Genesis.block)

    // Saved reply addresses for the two parallel follow-ups BlockFetcher emits after
    // the first headers response: GetBlockBodies and GetBlockHeaders(block=
    // last+1) prefetch. Their mailbox order isn't guaranteed. With the Typed AskPattern
    // the reply address travels in `Request.replyTo`, not in `lastSender`.
    var prefetchHeadersSender: Option[ActorRef[PeersClient.ResponseMessage]] = None
    var pendingBodiesSender: Option[ActorRef[PeersClient.ResponseMessage]] = None

    def handleFirstBlockBatchHeaders(): Unit =
      val (requestId, headersReplyTo) = peersClient.expectMsgPF() {
        case PeersClient.Request(msg: ETHPackets.GetBlockHeaders, _, _, replyTo) if msg.block == Left(1) =>
          (msg.requestId, replyTo)
      }
      val firstGetBlockHeadersResponse = ETHPackets.BlockHeaders(requestId, firstBlocksBatch.map(_.header))
      headersReplyTo ! PeersClient.Response(fakePeer, firstGetBlockHeadersResponse)

      def classifyNext(): Unit = peersClient.expectMsgPF() {
        case PeersClient.Request(msg: ETHPackets.GetBlockBodies, _, _, replyTo)
            if msg.hashes == firstBlocksBatch.map(_.hash) =>
          pendingBodiesSender = Some(replyTo)
        case PeersClient.Request(msg: ETHPackets.GetBlockHeaders, _, _, replyTo)
            if msg.block == Left(firstBlocksBatch.last.number.value + 1) =>
          prefetchHeadersSender = Some(replyTo)
      }
      classifyNext()
      classifyNext()

    def handleFirstBlockBatchBodies(): Unit =
      val replyTo = pendingBodiesSender.getOrElse(
        fail("Expected GetBlockBodies reply address captured by handleFirstBlockBatchHeaders")
      )
      replyTo ! PeersClient.Response(fakePeer, ETHPackets.BlockBodies(BigInt(0), firstBlocksBatch.map(_.body)))

    /** Synchronise on BlockFetcher having finished processing the bodies response. expectNoMessage drains the
      * peersClient mailbox for the given window, guaranteeing the actor has processed the bodies reply before the
      * caller sends InvalidateBlocksFrom.
      */
    def awaitBodiesProcessed(): Unit = peersClient.expectNoMessage(1.second)

    def handleFirstBlockBatch(): Unit =
      handleFirstBlockBatchHeaders()
      handleFirstBlockBatchBodies()
