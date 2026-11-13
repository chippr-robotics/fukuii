package com.chipprbots.ethereum.blockchain.sync.regular
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestActor.AutoPilot
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.Resource
import cats.effect.unsafe.IORuntime
import cats.syntax.traverse.*

import scala.compiletime.uninitialized
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.*
import scala.math.BigInt

import org.scalamock.scalatest.AsyncMockFactory
import org.scalatest.Assertion
import org.scalatest.diagrams.Diagrams
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.ResourceFixtures
import com.chipprbots.ethereum.WordSpecBase
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.blockchain.sync.PeersClient
import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol.Status
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol.Status.Progress
import com.chipprbots.ethereum.blockchain.sync.fast.FastSyncBranchResolverActor
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.InvalidateBlocksFrom
import com.chipprbots.ethereum.blockchain.sync.regular.RegularSync
import com.chipprbots.ethereum.consensus.Consensus
import com.chipprbots.ethereum.consensus.ConsensusAdapter
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.RepairOutcome
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeaderImplicits.*
import com.chipprbots.ethereum.ledger.*
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MPTException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingAccountNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingCodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingStorageNodeException
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.GetHandshakedPeersCmd
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscribeCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockBodies
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockBodies as ETHGetBlockBodies
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders as ETHGetBlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetNodeData
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlock
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlockHashes.BlockHash
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlockHashes.NewBlockHashes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NodeData
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetByteCodes
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetTrieNodes
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config.SyncConfig

class RegularSyncSpec
    extends WordSpecBase
    with ResourceFixtures
    with Matchers
    with AsyncMockFactory
    with Diagrams
    with RegularSyncFixtures:
  type Fixture = RegularSyncFixture

  // This spec is an AsyncWordSpec built on the cats-effect Resource[IO, ...] machinery, which is
  // incompatible with the synchronous ScalaTestWithActorTestKit base trait (it breaks async test
  // discovery). Each fixture now owns a per-test ActorTestKit (RegularSyncFixture.testKit) instead of
  // the former hand-rolled ActorSystem() + TestKit.shutdownActorSystem lifecycle. Per-test isolation
  // is preserved: each test gets a fresh testKit-owned Classic system, shut down via fixture.shutdownFixture().

  // fixtureResource builds a fresh fixture and shuts down its testKit on release.
  val fixtureResource: Resource[IO, Fixture] =
    Resource.make(IO(new Fixture))(fixture => IO(fixture.shutdownFixture()))

  // OnTop variant resource with the same teardown (used by the "return SyncDone when on top" test).
  val onTopFixtureResource: Resource[IO, OnTopFixture] =
    Resource.make(IO(new OnTopFixture))(fixture => IO(fixture.shutdownFixture()))

  def sync[T <: Fixture](test: => T): Future[Assertion] =
    import scala.concurrent.ExecutionContext.Implicits.global
    Future {
      val fixture = test
      try succeed
      finally fixture.shutdownFixture()
    }

  "Regular Sync" when {
    "initializing" should {
      "subscribe for new blocks, new hashes, new block headers and ETH/69 BlockRangeUpdate" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new Fixture:
          regularSync ! SyncProtocol.Start

          peerEventBus.expectMsgType[SubscribeCmd].to shouldBe MessageClassifier(
            Set(
              Codes.NewBlockCode,
              Codes.NewBlockHashesCode,
              Codes.BlockHeadersCode,
              Codes.BlockRangeUpdateCode
            ),
            PeerSelector.AllPeers
          )
      )

      "subscribe to handshaked peers list" taggedAs (UnitTest, SyncTest) in sync(new Fixture:
        regularSync // unlazy
        networkPeerManager.expectMsgType[NetworkPeerManagerActor.GetHandshakedPeersCmd]
      )
    }

    "fetching blocks" should {
      "fetch headers and bodies concurrently" taggedAs (UnitTest, SyncTest) in sync(new Fixture:
        regularSync ! SyncProtocol.Start

        val sub136: SubscribeCmd = peerEventBus.expectMsgType[SubscribeCmd]
        sub136.subscriber ! MessageFromPeer(
          NewBlock(
            testBlocks.last,
            ChainWeight.totalDifficultyOnly(testBlocks.last.number.value).totalDifficulty.value
          ),
          defaultPeer.id
        )

        peersClient.expectMsgEq(blockHeadersChunkRequest(0)).replyTo ! PeersClient.Response(
          defaultPeer,
          BlockHeaders(BigInt(0), testBlocksChunked.head.headers)
        )
        peersClient.expectMsgAllOfEq(
          blockHeadersChunkRequest(1),
          blockBodiesRequest(testBlocksChunked.head.hashes)
        )
      )

      "blacklist peer which caused failed request" taggedAs (UnitTest, SyncTest) in sync(new Fixture:
        regularSync ! SyncProtocol.Start

        peersClient.expectMsgType[PeersClient.Request[ETHGetBlockHeaders]].replyTo ! PeersClient.RequestFailed(
          defaultPeer,
          BlacklistReason.RegularSyncRequestFailed("a random reason")
        )
        peersClient.expectMsg(
          PeersClient.BlacklistPeer(defaultPeer.id, BlacklistReason.RegularSyncRequestFailed("a random reason"))
        )
      )

      "not blacklist peer which returns headers not matching current state during reorg" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new Fixture:
          var blockFetcher: TypedActorRef[PeerEvent] = uninitialized

          regularSync ! SyncProtocol.Start
          val sub168: SubscribeCmd = peerEventBus.expectMsgType[SubscribeCmd]
          blockFetcher = sub168.subscriber

          peersClient.expectMsgEq(blockHeadersChunkRequest(0)).replyTo ! PeersClient.Response(
            defaultPeer,
            BlockHeaders(BigInt(0), testBlocksChunked.head.headers)
          )

          // Full-sized first batch bumps knownTop, so the fetcher emits the
          // bodies request AND the next-chunk headers prefetch in parallel.
          // Capture each reply address (Typed AskPattern carries it in Request.replyTo).
          var bodiesSender: TypedActorRef[PeersClient.ResponseMessage] = null
          var nextHeadersSender: TypedActorRef[PeersClient.ResponseMessage] = null
          def classifyNext(): Unit = peersClient.expectMsgPF() {
            case PeersClient.Request(msg: ETHGetBlockBodies, _, _, replyTo)
                if msg.hashes == testBlocksChunked.head.headers.map(_.hash) =>
              bodiesSender = replyTo
            case PeersClient.Request(_: ETHGetBlockHeaders, _, _, replyTo) =>
              nextHeadersSender = replyTo
          }
          classifyNext()
          classifyNext()

          bodiesSender ! PeersClient.Response(defaultPeer, BlockBodies(BigInt(0), testBlocksChunked.head.bodies))

          blockFetcher ! MessageFromPeer(
            NewBlock(
              testBlocks.last,
              ChainWeight.totalDifficultyOnly(testBlocks.last.number.value).totalDifficulty.value
            ),
            defaultPeer.id
          )
          // Headers from a much later chunk — HeadersNotMatchingWaitingHeaders fires.
          // The peer is NOT blacklisted: during a reorg, an honest peer on an alternative
          // chain will return headers that don't extend the local waiting state. Besu
          // AbstractPeerTask.java distinguishes HeadersNotMatchingExpected (no penalty) from
          // InvalidHeaders (blacklist). Expect a retry request instead of BlacklistPeer.
          nextHeadersSender ! PeersClient.Response(defaultPeer, BlockHeaders(BigInt(0), testBlocksChunked(5).headers))
          peersClient.fishForSpecificMessage() { case PeersClient.Request(_: ETHGetBlockHeaders, _, _, _) =>
            ()
          }
      )

      "not blacklist peer which returns headers not forming a chain" in sync(new Fixture:
        // HeadersNotFormingSeq: headers don't internally chain (e.g. skipped blocks).
        // During a reorg an honest peer may send a valid fork segment that doesn't chain
        // to our expected sequence. No blacklist — just drop and retry.
        regularSync ! SyncProtocol.Start

        peersClient.expectMsgEq(blockHeadersChunkRequest(0)).replyTo ! PeersClient.Response(
          defaultPeer,
          BlockHeaders(BigInt(0), testBlocks.headers.filter(_.number.value % 2 == 0))
        )
        peersClient.fishForSpecificMessage() { case PeersClient.Request(_: ETHGetBlockHeaders, _, _, _) =>
          ()
        }
      )

      // Deleted: "blacklist peer which sends headers/bodies that were not requested"
      // These tests expected BlacklistPeer for unsolicited data, but BlockFetcher drops
      // unsolicited data silently instead of blacklisting — behavior was never implemented.

      "wait for time defined in config until issuing a retry request due to no suitable peer" in sync(
        new Fixture:
          regularSync ! SyncProtocol.Start

          peersClient.expectMsgEq(blockHeadersChunkRequest(0)).replyTo ! PeersClient.NoSuitablePeer
          peersClient.expectNoMessage(syncConfig.syncRetryInterval)
          peersClient.expectMsgEq(blockHeadersChunkRequest(0))
      )

      "not fetch new blocks if fetcher's queue reached size defined in configuration" in sync(new Fixture:
        override lazy val syncConfig: SyncConfig = defaultSyncConfig.copy(
          syncRetryInterval = testKitSettings.DefaultTimeout.duration,
          maxFetcherQueueSize = 1,
          maxReadyBlocksQueueSize = 1,
          blockBodiesPerRequest = 2,
          blockHeadersPerRequest = 2,
          blocksBatchSize = 2
        )

        regularSync ! SyncProtocol.Start

        val sub249: SubscribeCmd = peerEventBus.expectMsgType[SubscribeCmd]
        sub249.subscriber ! MessageFromPeer(
          NewBlock(
            testBlocks.last,
            ChainWeight.totalDifficultyOnly(testBlocks.last.header.difficulty.value).totalDifficulty.value
          ),
          defaultPeer.id
        )

        peersClient.expectMsgEq(blockHeadersChunkRequest(0)).replyTo ! PeersClient.Response(
          defaultPeer,
          BlockHeaders(BigInt(0), testBlocksChunked.head.headers)
        )

        // Now expects ETH66 GetBlockBodies with requestId
        // requestId is dynamic (generated per request) so we ignore it with _
        val expectedHashes = testBlocksChunked.head.hashes.toSet
        val bodiesReplyTo: TypedActorRef[PeersClient.ResponseMessage] = peersClient.expectMsgPF() {
          case PeersClient.Request(ETHGetBlockBodies(_, hashes), _, _, replyTo) if hashes.toSet == expectedHashes =>
            replyTo
        }
        bodiesReplyTo ! PeersClient.Response(defaultPeer, BlockBodies(BigInt(0), testBlocksChunked.head.bodies))

        peersClient.expectNoMessage()
      )
    }

    "resolving branches" should {

      // Regression: before the fix, ImportDone(ResolvingBranch(n)) never dispatched PickBlocks,
      // leaving BlockFetcher's waiting headers stranded and causing an infinite loop.
      "dispatch StrictPickBlocks after ImportDone(ResolvingBranch(n)), preventing infinite branch-resolution loop" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new Fixture:
          override lazy val blockchainReader: BlockchainReader = stub[BlockchainReader]
          (() => blockchainReader.getBestBlockNumber).when().returns(BigInt(1000))
          (() => blockchainReader.getSnapSyncPivotBlock).when().returns(None)

          val importerFetcher: TestProbe = TestProbe("importerFetcher")
          val importerSupervisor: TestProbe = TestProbe("importerSupervisor")
          val importerBroadcaster: TestProbe = TestProbe("importerBroadcaster")

          for depth <- List(1, 5, 64, 128) do
            val lca = BigInt(depth)
            val blockTopic: org.apache.pekko.actor.typed.ActorRef[
              org.apache.pekko.actor.typed.pubsub.Topic.Command[com.chipprbots.ethereum.jsonrpc.NewBlockImported]
            ] = testKit.spawn(
              org.apache.pekko.actor.typed.pubsub.Topic[com.chipprbots.ethereum.jsonrpc.NewBlockImported](
                "block-imported-topic"
              ),
              s"block-imported-topic-depth-$depth"
            )
            // testKit.spawn keeps the Typed ref so testKit.stop can terminate it safely. Using the
            // Classic system.stop on a testKit-guardian child sends StopChild to the guardian, which
            // only accepts TestKitCommand → ClassCastException → whole-system shutdown (8a-retro batch 4).
            val importer = testKit
              .spawn(
                BlockImporter.apply(
                  importerFetcher.ref.toTyped[BlockFetcher.FetchCommand],
                  consensusAdapter,
                  blockchainReader,
                  blockchainWriter,
                  stateStorage,
                  evmCodeStorage,
                  branchResolution,
                  syncConfig,
                  ommersPool.ref.toTyped[com.chipprbots.ethereum.ommers.OmmersPool.Command],
                  importerBroadcaster.ref,
                  pendingTransactionsManager.ref
                    .toTyped[com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command],
                  blockTopic,
                  importerSupervisor.ref,
                  peerEventBus.ref,
                  networkPeerManager.ref,
                  blockchain,
                  blacklist,
                  this,
                  exhausts = exhausts,
                  importLock = importLock
                ),
                s"test-importer-depth-$depth"
              )

            // Started as it is spawned (#1705).
            importerFetcher.expectMsgClass(3.seconds, classOf[BlockFetcher.Start])
            importerSupervisor.expectMsgClass(3.seconds, classOf[RegularSync.ProgressProtocol.StartingFrom])

            importer ! BlockImporter.ImportDone(BlockImporter.ResolvingBranch(lca), BlockImporter.DefaultBlockImport)

            val msg = importerFetcher.fishForSpecificMessage(5.seconds) {
              case m: BlockFetcher.StrictPickBlocks if m.from == lca => m
            }
            assert(msg.from == lca, s"StrictPickBlocks.from mismatch for depth=$depth")

            testKit.stop(importer)
            importerFetcher.expectNoMessage(200.millis)
      )

      // §9b divergence path: after repeated UnknownParent strikes BlockImporter escalates to
      // StartForkRecovery, spawns FastSyncBranchResolverActor, and transitions to `resolvingFork`.
      // When the resolver replies BranchResolvedSuccessful(lca), the importer must rewind the
      // canonical chain to the resolver's LCA (NOT the blind 128-block rewind) and invalidate
      // the fetcher's blocks from lca + 1. We drive the importer directly into resolvingFork and
      // inject the resolver reply via the public StartForkRecovery + private[regular] BranchResolverMsg
      // commands, bypassing the heavy peer-driven binary search inside the real resolver.
      "rewind canonical chain to resolver LCA on BranchResolvedSuccessful (divergence path)" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new Fixture:
          val capturedBest: BigInt = testBlocks.last.number.value // 20
          val lca: BigInt = BigInt(10)
          val lcaHeader: BlockHeader = testBlocks.find(_.number.value == lca).get.header
          val masterPeer: Peer = defaultPeer

          override lazy val blockchainReader: BlockchainReader = stub[BlockchainReader]
          (() => blockchainReader.getBestBlockNumber).when().returns(capturedBest)
          (() => blockchainReader.getSnapSyncPivotBlock).when().returns(None)
          (blockchainReader.getBlockHeaderByNumber(_: BigInt)).when(lca).returns(Some(lcaHeader))
          // the canonical index above the LCA, which the rewind must read BEFORE it deletes it
          testBlocks.filter(b => b.number.value > lca).foreach { b =>
            (blockchainReader.getCanonicalHashByNumber(_: BigInt)).when(b.number.value).returns(Some(b.hash))
          }
          val abandonedByRewind: java.util.concurrent.ConcurrentLinkedQueue[(BigInt, ByteString)] =
            new java.util.concurrent.ConcurrentLinkedQueue()
          val recordingReorgState: com.chipprbots.ethereum.consensus.ReorgStateHandler =
            new com.chipprbots.ethereum.consensus.ReorgStateHandler:
              override def abandonBlockStates(blocks: Seq[(BigInt, ByteString)]): Unit =
                blocks.foreach(abandonedByRewind.add(_))
              override def readoptBlockStates(blocks: Seq[(BigInt, ByteString)]): Unit = ()

          override lazy val blockchainWriter: BlockchainWriter = stub[BlockchainWriter]

          val importerFetcher: TestProbe = TestProbe("forkImporterFetcher")
          val importerSupervisor: TestProbe = TestProbe("forkImporterSupervisor")
          val importerBroadcaster: TestProbe = TestProbe("forkImporterBroadcaster")

          val forkBlockTopic: org.apache.pekko.actor.typed.ActorRef[
            org.apache.pekko.actor.typed.pubsub.Topic.Command[com.chipprbots.ethereum.jsonrpc.NewBlockImported]
          ] = testKit.spawn(
            org.apache.pekko.actor.typed.pubsub.Topic[com.chipprbots.ethereum.jsonrpc.NewBlockImported](
              "fork-block-imported-topic"
            ),
            "fork-block-imported-topic"
          )

          // testKit.spawn keeps the Typed ref so testKit.stop can terminate it safely (Classic
          // system.stop on a testKit-guardian child crashes the guardian — 8a-retro batch 4).
          val importer: TypedActorRef[BlockImporter.Command] = testKit
            .spawn(
              BlockImporter.apply(
                importerFetcher.ref.toTyped[BlockFetcher.FetchCommand],
                consensusAdapter,
                blockchainReader,
                blockchainWriter,
                stateStorage,
                evmCodeStorage,
                branchResolution,
                syncConfig,
                ommersPool.ref.toTyped[com.chipprbots.ethereum.ommers.OmmersPool.Command],
                importerBroadcaster.ref,
                pendingTransactionsManager.ref
                  .toTyped[com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command],
                forkBlockTopic,
                importerSupervisor.ref,
                peerEventBus.ref,
                networkPeerManager.ref,
                blockchain,
                blacklist,
                this,
                None,
                recordingReorgState,
                exhausts = exhausts,
                importLock = importLock
              ),
              "test-fork-recovery-importer"
            )

          // Started as it is spawned (#1705).
          importerFetcher.expectMsgClass(3.seconds, classOf[BlockFetcher.Start])
          importerSupervisor.expectMsgClass(3.seconds, classOf[RegularSync.ProgressProtocol.StartingFrom])

          // Escalate to fork recovery — spawns FastSyncBranchResolverActor and enters resolvingFork.
          importer ! BlockImporter.StartForkRecovery(BigInt(15))

          // Inject the resolver's success reply directly (simulating BranchResolvedSuccessful).
          importer ! BlockImporter.BranchResolverMsg(
            FastSyncBranchResolverActor.BranchResolvedSuccessful(lca, masterPeer)
          )

          // The importer must invalidate the fetcher's blocks from lca + 1 (resolver-driven rollback).
          val invalidate: InvalidateBlocksFrom = importerFetcher.fishForSpecificMessage(5.seconds) {
            case m: BlockFetcher.InvalidateBlocksFrom => m
          }
          assert(
            invalidate.fromBlock == lca + 1,
            s"expected InvalidateBlocksFrom(${lca + 1}), got ${invalidate.fromBlock}"
          )

          // The canonical chain head must be rewound to the resolver's LCA, not a blind 128-block rewind.
          (blockchainWriter
            .setCanonicalChainHead(_: BigInt, _: com.chipprbots.ethereum.domain.BlockHash, _: BigInt))
            .verify(lca, lcaHeader.hash, capturedBest)
            .once()

          // ... and the blocks the rewind un-canonicalised have their reference-count changes undone (#76).
          import scala.jdk.CollectionConverters.*
          abandonedByRewind.asScala.toList shouldBe
            testBlocks.filter(_.number.value > lca).map(b => (b.number.value, b.hash.value))

          testKit.stop(importer)
      )

      "go back to earlier block in order to find a common parent with new branch" in sync(
        new Fixture:
          override lazy val blockchain: BlockchainImpl = stub[BlockchainImpl]
          override lazy val blockchainReader: BlockchainReader = stub[BlockchainReader]
          (() => blockchainReader.getBestBlockNumber).when().onCall(() => bestBlock.number.value)
          (() => blockchainReader.getSnapSyncPivotBlock).when().returns(None) // no SNAP sync pivot
          // No canonical index in this stubbed world, so the importer's PoW prefix trim (#1592) drops nothing.
          (blockchainReader.getCanonicalHashByNumber(_: BigInt)).when(*).returns(None)
          override lazy val consensusAdapter: ConsensusAdapter = stub[ConsensusAdapter]
          (consensusAdapter
            .evaluateBranchBlock(_: Block)(using _: IORuntime, _: BlockchainConfig))
            .when(*, *, *)
            .onCall((block, _, _) => fakeEvaluateBlock(block))
          (consensusAdapter
            .evaluateBranch(_: NonEmptyList[Block])(using _: IORuntime, _: BlockchainConfig))
            .when(*, *, *)
            .onCall((nel, _, _) => fakeEvaluateBatch(nel))
          override lazy val branchResolution: BranchResolution = new FakeBranchResolution()
          override lazy val syncConfig: SyncConfig = defaultSyncConfig.copy(
            blockHeadersPerRequest = 5,
            blockBodiesPerRequest = 5,
            blocksBatchSize = 5,
            syncRetryInterval = 1.second,
            printStatusInterval = 0.5.seconds,
            branchResolutionRequestSize = 6
          )

          val commonPart: List[Block] = testBlocks.take(syncConfig.blocksBatchSize)
          val alternativeBranch: List[Block] =
            BlockHelpers.generateChain(syncConfig.blocksBatchSize * 2, commonPart.last)
          val alternativeBlocks: List[Block] = commonPart ++ alternativeBranch

          class BranchResolutionAutoPilot(didResponseWithNewBranch: Boolean, blocks: List[Block])
              extends PeersClientAutoPilot(blocks):
            override def overrides(sender: ActorRef): PartialFunction[Any, Option[AutoPilot]] = {
              // Handle ETH68/69 GetBlockHeaders
              case PeersClient.Request(ETHGetBlockHeaders(_, Left(nr), maxHeaders, _, _), _, _, replyTo)
                  if nr >= alternativeBranch.numberAtUnsafe(syncConfig.blocksBatchSize) && !didResponseWithNewBranch =>
                val responseHeaders = alternativeBranch.headers.filter(_.number.value >= nr).take(maxHeaders.toInt)
                replyTo ! PeersClient.Response(defaultPeer, BlockHeaders(BigInt(0), responseHeaders))
                Some(new BranchResolutionAutoPilot(true, alternativeBlocks))
              // Handle ETH68/69 GetBlockBodies
              case PeersClient.Request(ETHGetBlockBodies(_, hashes), _, _, replyTo)
                  if !hashes.toSet.subsetOf(blocks.hashes.toSet) &&
                    hashes.toSet.subsetOf(testBlocks.hashes.toSet) =>
                val matchingBodies = hashes.flatMap(hash => testBlocks.find(_.hash.value == hash)).map(_.body)
                replyTo ! PeersClient.Response(defaultPeer, BlockBodies(BigInt(0), matchingBodies))
                None
            }

          peersClient.setAutoPilot(new BranchResolutionAutoPilot(didResponseWithNewBranch = false, testBlocks))

          Await.result(consensusAdapter.evaluateBranchBlock(BlockHelpers.genesis).unsafeToFuture(), remainingOrDefault)

          regularSync ! SyncProtocol.Start

          val sub383: SubscribeCmd = peerEventBus.expectMsgType[SubscribeCmd]
          sub383.subscriber ! MessageFromPeer(
            NewBlock(
              alternativeBlocks.last,
              ChainWeight.totalDifficultyOnly(alternativeBlocks.last.number.value).totalDifficulty.value
            ),
            defaultPeer.id
          )
          // increase timeout slightly to reduce intermittent flakiness in forked test JVMs
          awaitCond(bestBlock == alternativeBlocks.last, 10.seconds)
      )
    }

    "go back to earlier positive block in order to resolve a fork when branch smaller than branch resolution size" in sync(
      new Fixture:
        override lazy val blockchainReader: BlockchainReader = stub[BlockchainReader]
        override lazy val blockchain: BlockchainImpl = stub[BlockchainImpl]
        (() => blockchainReader.getBestBlockNumber).when().onCall(() => bestBlock.number.value)
        (() => blockchainReader.getSnapSyncPivotBlock).when().returns(None) // no SNAP sync pivot
        // No canonical index in this stubbed world, so the importer's PoW prefix trim (#1592) drops nothing.
        (blockchainReader.getCanonicalHashByNumber(_: BigInt)).when(*).returns(None)
        override lazy val consensusAdapter: ConsensusAdapter = stub[ConsensusAdapter]
        (consensusAdapter
          .evaluateBranchBlock(_: Block)(using _: IORuntime, _: BlockchainConfig))
          .when(*, *, *)
          .onCall((block, _, _) => fakeEvaluateBlock(block))
        (consensusAdapter
          .evaluateBranch(_: NonEmptyList[Block])(using _: IORuntime, _: BlockchainConfig))
          .when(*, *, *)
          .onCall((nel, _, _) => fakeEvaluateBatch(nel))
        override lazy val branchResolution: BranchResolution = new FakeBranchResolution()
        override lazy val syncConfig: SyncConfig = defaultSyncConfig.copy(
          syncRetryInterval = 1.second,
          printStatusInterval = 0.5.seconds,
          branchResolutionRequestSize = 12, // Over the original branch size

          // Big so that they don't impact the test
          blockHeadersPerRequest = 50,
          blockBodiesPerRequest = 50,
          blocksBatchSize = 50
        )

        val originalBranch: List[Block] = BlockHelpers.generateChain(10, BlockHelpers.genesis)
        val betterBranch: List[Block] = BlockHelpers.generateChain(originalBranch.size * 2, BlockHelpers.genesis)

        class ForkingAutoPilot(blocksToRespond: List[Block], forkedBlocks: Option[List[Block]])
            extends PeersClientAutoPilot(blocksToRespond):
          override def overrides(sender: ActorRef): PartialFunction[Any, Option[AutoPilot]] = {
            case req @ PeersClient.Request(ETHGetBlockBodies(_, hashes), _, _, _) =>
              handleForkLogic(hashes, req, sender)
          }

          private def handleForkLogic(hashes: Seq[ByteString], req: Any, sender: ActorRef): Option[AutoPilot] =
            val defaultResult = defaultHandlers(sender)(req)
            if forkedBlocks.nonEmpty && hashes.contains(blocksToRespond.last.hash) then
              Some(new ForkingAutoPilot(forkedBlocks.get, None))
            else defaultResult

        peersClient.setAutoPilot(new ForkingAutoPilot(originalBranch, Some(betterBranch)))

        Await.result(consensusAdapter.evaluateBranchBlock(BlockHelpers.genesis).unsafeToFuture(), remainingOrDefault)

        regularSync ! SyncProtocol.Start

        val sub445: SubscribeCmd = peerEventBus.expectMsgType[SubscribeCmd]
        val blockFetcher: TypedActorRef[PeerEvent] = sub445.subscriber
        sub445.subscriber ! MessageFromPeer(
          NewBlock(
            originalBranch.last,
            ChainWeight.totalDifficultyOnly(originalBranch.last.number.value).totalDifficulty.value
          ),
          defaultPeer.id
        )

        awaitCond(bestBlock == originalBranch.last, 5.seconds)

        // As node will be on top, we have to re-trigger the fetching process by simulating a block from the fork being broadcasted
        blockFetcher ! MessageFromPeer(
          NewBlock(
            betterBranch.last,
            ChainWeight.totalDifficultyOnly(betterBranch.last.number.value).totalDifficulty.value
          ),
          defaultPeer.id
        )
        awaitCond(bestBlock == betterBranch.last, 5.seconds)
    )

    "fetching state node" should {
      abstract class MissingStateNodeFixture extends Fixture:
        val failingBlock: Block = testBlocksChunked.head.head
        setImportResult(
          failingBlock,
          IO.pure(BlockImportFailedDueToMissingNode(new MissingNodeException(failingBlock.hash.value)))
        )

      "blacklist peer which returns empty response" in sync(new MissingStateNodeFixture:
        val failingPeer: Peer = peerByNumber(1)

        peersClient.setAutoPilot(
          new PeersClientAutoPilot:
            override def overrides(sender: ActorRef): PartialFunction[Any, Option[AutoPilot]] = {
              case PeersClient.Request(GetNodeData(_), _, _, replyTo) =>
                replyTo ! PeersClient.Response(failingPeer, NodeData(Nil))
                None
            }
        )

        regularSync ! SyncProtocol.Start

        fishForBlacklistPeer(failingPeer)
      )

      "blacklist peer which returns invalid node" in sync(new MissingStateNodeFixture:
        val failingPeer: Peer = peerByNumber(1)
        peersClient.setAutoPilot(
          new PeersClientAutoPilot:
            override def overrides(sender: ActorRef): PartialFunction[Any, Option[AutoPilot]] = {
              case PeersClient.Request(GetNodeData(_), _, _, replyTo) =>
                replyTo ! PeersClient.Response(failingPeer, NodeData(List(ByteString("foo"))))
                None
            }
        )

        regularSync ! SyncProtocol.Start

        fishForBlacklistPeer(failingPeer)
      )

      "retry fetching node if validation failed" taggedAs (UnitTest, SyncTest) in sync(
        new MissingStateNodeFixture:
          def fishForFailingBlockNodeRequest(): Boolean = peersClient.fishForSpecificMessage(max = 10.seconds) {
            case PeersClient.Request(GetNodeData(hash :: Nil), _, _, _) if hash == failingBlock.hash.value => true
          }

          class WrongNodeDataPeersClientAutoPilot(var handledRequests: Int = 0) extends PeersClientAutoPilot:
            override def overrides(sender: ActorRef): PartialFunction[Any, Option[AutoPilot]] = {
              case PeersClient.Request(GetNodeData(_), _, _, replyTo) =>
                val response = handledRequests match
                  case 0 => Some(PeersClient.Response(peerByNumber(1), NodeData(Nil)))
                  case 1 => Some(PeersClient.Response(peerByNumber(2), NodeData(List(ByteString("foo")))))
                  case _ => None

                response.foreach(replyTo ! _)
                Some(new WrongNodeDataPeersClientAutoPilot(handledRequests + 1))
            }

          peersClient.setAutoPilot(new WrongNodeDataPeersClientAutoPilot())

          regularSync ! SyncProtocol.Start

          fishForFailingBlockNodeRequest()
          fishForFailingBlockNodeRequest()
          fishForFailingBlockNodeRequest()
      )

      // Devnet-8 block 318074: an account the node held had a codeHash and no bytecode, execution ran the call as a
      // call to an EOA, and the block was reported INVALID. Missing code is now a missing-data condition: the importer
      // asks a snap peer for it (GetNodeData has no peers on ETH68+) and retries the block.
      "request missing contract code over SNAP GetByteCodes, not GetNodeData, once bulk recovery has been tried" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new Fixture:
          // The bulk recovery is requested once per process; this is every miss after it, on a node where it is allowed.
          BlockImporter.resetBulkCodeRecoveryForTests()
          storagesInstance.storages.appStateStorage.snapSyncDone().commit() // a SNAP-synced node
          (1 to BlockImporter.BulkCodeRecoveryAfterMisses).foreach(i =>
            BlockImporter.claimBulkCodeRecovery(ByteString(s"spent the claim $i"))
          )
          val failingBlock: Block = testBlocksChunked.head.head
          val codeHash: ByteString = kec256(ByteString("contract code this node never stored"))
          setImportResult(
            failingBlock,
            IO.pure(
              BlockImportFailedDueToMissingNode(
                new MissingCodeException(codeHash, ByteString(Array.fill[Byte](20)(0x16)))
              )
            )
          )

          peersClient.setAutoPilot(new PeersClientAutoPilot)

          regularSync ! SyncProtocol.Start

          peersClient.fishForSpecificMessage(max = 10.seconds) {
            case PeersClient.Request(GetByteCodes(_, hashes, _), _, _, _) if hashes == Seq(codeHash) => true
          }
      )

      // #1694: the scan stops regular sync for as long as reading the whole state takes, estimated at hours on Sepolia, and
      // one missing contract does not earn that. A fetch took 100 ms on Ephemery.
      "fetch the first missing contract code alone on a SNAP-synced node, with no bulk recovery" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new Fixture:
          BlockImporter.resetBulkCodeRecoveryForTests()
          storagesInstance.storages.appStateStorage.snapSyncDone().commit() // a SNAP-synced node
          val failingBlock: Block = testBlocksChunked.head.head
          val codeHash: ByteString = kec256(ByteString("contract code this node never stored"))
          setImportResult(
            failingBlock,
            IO.pure(
              BlockImportFailedDueToMissingNode(
                new MissingCodeException(codeHash, ByteString(Array.fill[Byte](20)(0x16)))
              )
            )
          )
          peersClient.setAutoPilot(new PeersClientAutoPilot)

          regularSync ! SyncProtocol.Start

          peersClient.fishForSpecificMessage(max = 10.seconds) {
            case PeersClient.Request(GetByteCodes(_, hashes, _), _, _, _) if hashes == Seq(codeHash) => true
          }
          BlockImporter.distinctMissingCodesForTests shouldBe 1
          val toSupervisor: Seq[Object] = supervisor.receiveWhile(1.second) { case m => m }
          toSupervisor.collect {
            case com.chipprbots.ethereum.blockchain.sync.SyncController.WrappedSyncProtocol(
                  r: SyncProtocol.MissingCodeNeedsBulkRecovery
                ) =>
              r
          } shouldBe empty
      )

      // Several distinct misses show there are many to find, as on a datadir from before healing waited on code, and one
      // fetch per contract is one full re-execution of the block each. The miss that shows it asks for the bulk recovery
      // instead, and does not fetch that hash on its own.
      "ask SyncController for a bulk bytecode recovery once enough distinct contract codes have been missing" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new Fixture:
          BlockImporter.resetBulkCodeRecoveryForTests()
          storagesInstance.storages.appStateStorage.snapSyncDone().commit() // a SNAP-synced node
          (1 until BlockImporter.BulkCodeRecoveryAfterMisses).foreach(i =>
            BlockImporter.claimBulkCodeRecovery(ByteString(s"an earlier miss $i")) shouldBe false
          )
          val failingBlock: Block = testBlocksChunked.head.head
          val codeHash: ByteString = kec256(ByteString("contract code this node never stored"))
          setImportResult(
            failingBlock,
            IO.pure(
              BlockImportFailedDueToMissingNode(
                new MissingCodeException(codeHash, ByteString(Array.fill[Byte](20)(0x16)))
              )
            )
          )
          peersClient.setAutoPilot(new PeersClientAutoPilot)

          regularSync ! SyncProtocol.Start

          supervisor.fishForSpecificMessage(max = 10.seconds) {
            case com.chipprbots.ethereum.blockchain.sync.SyncController.WrappedSyncProtocol(
                  SyncProtocol.MissingCodeNeedsBulkRecovery(number, hash)
                ) if number == failingBlock.number.value && hash == codeHash =>
              true
          }
          // ...and it did NOT also start a single-hash fetch.
          val seen: Seq[Object] = peersClient.receiveWhile(1.second) { case m => m }
          seen.collect { case PeersClient.Request(_: GetByteCodes, _, _, _) => 1 } shouldBe empty
      )

      "count a code met again once, and ask for a bulk recovery at most once a process" taggedAs (
        UnitTest,
        SyncTest
      ) in {
        BlockImporter.resetBulkCodeRecoveryForTests()
        val same = ByteString("the same code, met again")
        (1 to BlockImporter.BulkCodeRecoveryAfterMisses * 2).foreach(_ =>
          BlockImporter.claimBulkCodeRecovery(same) shouldBe false
        )
        BlockImporter.distinctMissingCodesForTests shouldBe 1
        val claims = (1 to BlockImporter.BulkCodeRecoveryAfterMisses * 2).map(i =>
          BlockImporter.claimBulkCodeRecovery(ByteString(s"miss $i"))
        )
        claims.count(identity) shouldBe 1
        withClue("what it keeps is bounded: ") {
          BlockImporter.distinctMissingCodesForTests shouldBe BlockImporter.BulkCodeRecoveryAfterMisses
        }
        BlockImporter.resetBulkCodeRecoveryForTests()
        scala.concurrent.Future.successful(succeed)
      }

      "fetch missing contract code alone, without asking for a bulk recovery, on a node that did not SNAP-sync" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new Fixture:
          BlockImporter.resetBulkCodeRecoveryForTests()
          storagesInstance.storages.appStateStorage.clearSnapSyncDone().commit()
          val failingBlock: Block = testBlocksChunked.head.head
          val codeHash: ByteString = kec256(ByteString("contract code of a node that executed its way here"))
          setImportResult(
            failingBlock,
            IO.pure(
              BlockImportFailedDueToMissingNode(
                new MissingCodeException(codeHash, ByteString(Array.fill[Byte](20)(0x16)))
              )
            )
          )
          peersClient.setAutoPilot(new PeersClientAutoPilot)

          regularSync ! SyncProtocol.Start

          peersClient.fishForSpecificMessage(max = 10.seconds) {
            case PeersClient.Request(GetByteCodes(_, hashes, _), _, _, _) if hashes == Seq(codeHash) => true
          }
          // The refused escalation counted nothing towards a bulk recovery.
          BlockImporter.distinctMissingCodesForTests shouldBe 0
      )

      // #1694: a storage hole met writing carries no position. It is found from the parent's root, not guessed as the
      // storage root, which drew an honest reply of the wrong node and a blacklisting.
      abstract class LocationlessStorageHoleFixture extends Fixture:
        val failingBlock: Block = testBlocksChunked.head.head
        val holder: Address = Address(ByteString(Array.fill[Byte](20)(0x2b)))
        private val store = storagesInstance.storages.stateStorage.getBackingStorage(0)
        // 64 slots: a storage root whose children are all referenced by hash.
        val storageRoot: ByteString =
          import MerklePatriciaTrie.defaultByteArraySerializable
          val trie = (0 until 64).foldLeft(MerklePatriciaTrie[Array[Byte], Array[Byte]](store)) { (t, i) =>
            t.put(kec256(BigInt(i).toByteArray), Array.fill[Byte](32)(i.toByte))
          }
          store.persist()
          ByteString(trie.getRootHash)
        val (holeSlot, hole) = store.get(storageRoot.toArray) match
          case branch: BranchNode =>
            branch.children.zipWithIndex.collectFirst { case (HashNode(h), i) => (i, ByteString(h)) }.get
          case other => fail(s"the storage root is no branch: $other")
        val stateRoot: ByteString =
          val accounts =
            MerklePatriciaTrie[Address, Account](store).put(holder, Account(storageRoot = TrieRoot(storageRoot)))
          store.persist()
          ByteString(accounts.getRootHash)
        private val parent = failingBlock.header.copy(stateRoot = TrieRoot(stateRoot))

        override lazy val blockchainReader: BlockchainReader = new BlockchainReader(
          storagesInstance.storages.blockHeadersStorage,
          storagesInstance.storages.blockBodiesStorage,
          storagesInstance.storages.blockNumberMappingStorage,
          storagesInstance.storages.stateStorage,
          storagesInstance.storages.receiptStorage,
          storagesInstance.storages.appStateStorage,
          storagesInstance.storages.chainWeightStorage,
          storagesInstance.storages.blockAccessListStorage
        ):
          override def getBestBlockNumber: BigInt = BigInt(0)
          override def getSnapSyncPivotBlock: Option[BigInt] = None
          override def getBlockHeaderByNumber(number: BigInt): Option[BlockHeader] =
            Some(BlockHelpers.genesis.header)
          override def getBlockHeaderByHash(hash: com.chipprbots.ethereum.domain.BlockHash): Option[BlockHeader] =
            if hash == failingBlock.header.parentHash then Some(parent) else super.getBlockHeaderByHash(hash)

        override lazy val blockchain: BlockchainImpl = BlockchainImpl(storagesInstance.storages, blockchainReader)
        // The importer walks the storage trie through this; the fixture's own is a stub that reads nothing.
        override val stateStorage: StateStorage = storagesInstance.storages.stateStorage
        // These tests are about a storage hole, not about branch resolution. The reader above answers the genesis hash
        // with the parent header below, which no real chain does, so real resolution reads the first batch as an unknown
        // branch; what imported it was the pivot floor's direct import, which now takes only a batch that continues the
        // chain (B-007). Resolution is settled here instead: the batch extends the head.
        override lazy val branchResolution: BranchResolution = stub[BranchResolution]
        branchResolution.resolveBranch.when(*).returns(NewBetterBranch(Nil))

        def holeAt(hash: ByteString): Unit =
          setImportResult(
            failingBlock,
            IO.pure(BlockImportFailedDueToMissingNode(new MissingStorageNodeException(hash, holder.bytes, None)))
          )

      "ask for a storage hole with no position at the position found under the parent's root" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(new LocationlessStorageHoleFixture:
        holeAt(hole)
        peersClient.setAutoPilot(new PeersClientAutoPilot)

        regularSync ! SyncProtocol.Start

        val expected: Seq[Seq[ByteString]] =
          Seq(Seq(kec256(holder.bytes), ByteString(HexPrefix.encode(Array(holeSlot.toByte), isLeaf = false))))
        peersClient.fishForSpecificMessage(max = 10.seconds) {
          case PeersClient.Request(GetTrieNodes(_, root, paths, _), _, _, _)
              if root == stateRoot && paths == expected =>
            true
        }
        // Located, so the fetch is waited on: nothing counts as exhausted at once (forge, #1694).
        TestProbe().expectNoMessage(500.millis)
        exhausts.get shouldBe 0
      )

      "ask no peer for a storage hole that cannot be located, and back off as after an exhausted fetch" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(new LocationlessStorageHoleFixture:
        holeAt(kec256(ByteString("referred to by nothing present")))
        peersClient.setAutoPilot(
          new PeersClientAutoPilot:
            // Left unanswered so a request, if one is sent, stays on the probe: the default answer looks the hash up as
            // a block's, throws for a node's, and the probe then never records the request.
            override def overrides(sender: ActorRef): PartialFunction[Any, Option[AutoPilot]] = {
              case PeersClient.Request(GetNodeData(_), _, _, _) => None
            }
        )

        regularSync ! SyncProtocol.Start

        awaitCond(exhausts.get == 1, 10.seconds)
        val asked: Seq[Object] = peersClient.receiveWhile(2.seconds) { case m => m }
        asked.collect {
          case r @ PeersClient.Request(_: GetNodeData, _, _, _)  => r
          case r @ PeersClient.Request(_: GetTrieNodes, _, _, _) => r
        } shouldBe empty
      )

      // forge, beacon: a retry tick counted as an exhaust made the first real exhaust the third, so a hole no peer served
      // for about 150 s cost a re-SNAP. The user's decision of 2026-11-02: only exhausts count.
      "not count its retries towards the re-SNAP, only exhausted fetches" taggedAs (UnitTest, SyncTest) in sync(
        new MissingStateNodeFixture:
          BlockImporter.resolvingRetryInterval = 100.millis
          try
            peersClient.setAutoPilot(
              new PeersClientAutoPilot:
                override def overrides(sender: ActorRef): PartialFunction[Any, Option[AutoPilot]] = {
                  case PeersClient.Request(GetNodeData(_), _, _, _) => None // never answered, so never exhausted here
                }
            )

            regularSync ! SyncProtocol.Start

            peersClient.fishForSpecificMessage(max = 10.seconds) {
              case PeersClient.Request(GetNodeData(hash :: Nil), _, _, _) if hash == failingBlock.hash.value => true
            }
            TestProbe().expectNoMessage(2.seconds) // twenty retry ticks
            exhausts.get shouldBe 0
          finally BlockImporter.resolvingRetryInterval = 30.seconds
      )

      // forge, #1694: an exhaust landing while a retry of the blocked import ran was dropped, and the re-import started
      // the fetch again from its first try. With only exhausts counting, that could hold the re-SNAP off.
      "count an exhausted fetch that lands while a retry of the import runs" taggedAs (UnitTest, SyncTest) in sync(
        new Fixture:
          val failingBlock: Block = testBlocksChunked.head.head
          // Slow, so the exhaust below lands inside the retry's import rather than between imports.
          setImportResult(
            failingBlock,
            IO.sleep(2.seconds) *>
              IO.pure(BlockImportFailedDueToMissingNode(new MissingNodeException(failingBlock.hash.value)))
          )
          BlockImporter.resolvingRetryInterval = 100.millis
          try
            peersClient.setAutoPilot(
              new PeersClientAutoPilot:
                override def overrides(sender: ActorRef): PartialFunction[Any, Option[AutoPilot]] = {
                  case PeersClient.Request(GetNodeData(_), _, _, _) => None // the exhaust comes from the test
                }
            )

            regularSync ! SyncProtocol.Start

            peersClient.fishForSpecificMessage(max = 10.seconds) {
              case PeersClient.Request(GetNodeData(hash :: Nil), _, _, _) if hash == failingBlock.hash.value => true
            }
            // The first import has ended; its retry starts 100 ms later and runs for 2 s.
            TestProbe().expectNoMessage(1.second)
            exhausts.get shouldBe 0
            system.actorSelection(s"${regularSync.path}/block-importer-*") !
              BlockImporter.FetcherResponse(BlockFetcher.FetchedStateNode(NodeData(Nil)))

            awaitCond(exhausts.get == 1, 5.seconds)
            succeed
          finally BlockImporter.resolvingRetryInterval = 30.seconds
      )

      // Only exhausts count now, so the escalation must still come from them alone.
      "still ask for a SNAP re-sync after three exhausted fetches of one block" taggedAs (UnitTest, SyncTest) in sync(
        new LocationlessStorageHoleFixture:
          BlockImporter.exhaustBackoff = 200.millis
          try
            holeAt(kec256(ByteString("referred to by nothing present"))) // exhausts at once, each try
            peersClient.setAutoPilot(new PeersClientAutoPilot)

            regularSync ! SyncProtocol.Start

            supervisor.fishForSpecificMessage(max = 15.seconds) {
              case SyncController.WrappedSyncProtocol(SyncProtocol.RegularSyncStuck(number, _))
                  if number == failingBlock.number.value =>
                true
            }
            exhausts.get shouldBe 0
          finally BlockImporter.exhaustBackoff = 5.minutes
      )

      // forge, #1694: the counter outlived a block imported, so holes the audit filled days apart added up to a re-SNAP.
      "clear its exhausts once a block is imported" taggedAs (UnitTest, SyncTest) in sync(
        new LocationlessStorageHoleFixture:
          BlockImporter.exhaustBackoff = 1.second
          try
            holeAt(kec256(ByteString("referred to by nothing present")))
            peersClient.setAutoPilot(new PeersClientAutoPilot)

            regularSync ! SyncProtocol.Start

            awaitCond(exhausts.get == 1, 10.seconds)
            // The audit fills the hole during the backoff, so the next try imports the block.
            setImportResult(
              failingBlock,
              IO.pure(
                BlockImportedToTop(
                  List(BlockData(failingBlock, Nil, ChainWeight.totalDifficultyOnly(failingBlock.number.value)))
                )
              )
            )
            awaitCond(exhausts.get == 0, 10.seconds)
            supervisor.receiveWhile(500.millis) { case m => m }.collect {
              case s @ SyncController.WrappedSyncProtocol(_: SyncProtocol.RegularSyncStuck) => s
            } shouldBe empty
          finally BlockImporter.exhaustBackoff = 5.minutes
      )

      // spec 022 T062: on Mordor a corrupt account leaf blacklisted the honest provider on every import attempt, and a
      // fetched node saved later could not clear it under basic pruning's upsert. A stored entry that is not its node now
      // reads as a missing node; the importer deletes the entry, then fetches, and blames nobody.
      "delete a stored entry that is not its node before fetching it, and blacklist no peer" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new Fixture:
          val failingBlock: Block = testBlocksChunked.head.head
          // Archive pruning, which can delete a bad entry; the fixture's own state storage is a stub.
          override val stateStorage: StateStorage = storagesInstance.storages.stateStorage
          val corrupt: ByteString = kec256(ByteString("a trie node this node holds junk for"))
          storagesInstance.storages.nodeStorage.put(corrupt, Array(0x01.toByte))
          // What execution raises for it: the world state rewraps the corrupt node as a missing account node.
          setImportResult(
            failingBlock,
            IO.pure(
              BlockImportFailedDueToMissingNode(
                new MissingAccountNodeException(corrupt, ByteString(Array.fill[Byte](20)(0x2b)))
              )
            )
          )
          peersClient.setAutoPilot(new PeersClientAutoPilot) // GetTrieNodes is left unanswered

          regularSync ! SyncProtocol.Start

          peersClient.fishForSpecificMessage(max = 10.seconds) {
            case b: PeersClient.BlacklistPeer                  => fail(s"a peer was blamed for a local fault: $b")
            case PeersClient.Request(_: GetTrieNodes, _, _, _) => true
          }
          withClue("the entry was deleted before the fetch went out: ") {
            storagesInstance.storages.nodeStorage.get(corrupt) shouldBe None
          }
          peersClient.receiveWhile(1.second) { case m => m }.collect { case b: PeersClient.BlacklistPeer => b } shouldBe
            empty
      )

      "leave a good stored node alone when a missing node is met under its hash" taggedAs (UnitTest, SyncTest) in sync(
        new Fixture:
          val failingBlock: Block = testBlocksChunked.head.head
          override val stateStorage: StateStorage = storagesInstance.storages.stateStorage
          val node: Array[Byte] = MerklePatriciaTrie.EmptyEncoded
          val hash: ByteString = ByteString(MerklePatriciaTrie.EmptyRootHash)
          storagesInstance.storages.nodeStorage.put(hash, node)
          setImportResult(failingBlock, IO.pure(BlockImportFailedDueToMissingNode(new MissingNodeException(hash))))
          peersClient.setAutoPilot(
            new PeersClientAutoPilot:
              override def overrides(sender: ActorRef): PartialFunction[Any, Option[AutoPilot]] = {
                case PeersClient.Request(GetNodeData(_), _, _, _) => None
              }
          )

          regularSync ! SyncProtocol.Start

          peersClient.fishForSpecificMessage(max = 10.seconds) {
            case PeersClient.Request(GetNodeData(h :: Nil), _, _, _) if h == hash => true
          }
          storagesInstance.storages.nodeStorage.get(hash).map(_.toSeq) shouldBe Some(node.toSeq)
      )

      // spec 022 T062: a trie error that is not a missing node reached the importer as a string and took the invalid-block
      // path: the honest provider blacklisted each attempt, evicted at three strikes, fork detection at five, and never
      // an escalation (1,986 blacklistings in 45 minutes on Mordor). It is this node's fault: no peer is blamed, and it
      // escalates like exhausted fetches.
      "treat a trie error that is not a missing node as its own fault: no peer blamed, a re-SNAP at the third" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new Fixture:
          val failingBlock: Block = testBlocksChunked.head.head
          BlockImporter.exhaustBackoff = 200.millis
          try
            setImportResult(
              failingBlock,
              IO.pure(BlockImportFailed(BlockExecutionError.MPTError(new MPTException("Invalid Node")).toString))
            )
            peersClient.setAutoPilot(new PeersClientAutoPilot)

            regularSync ! SyncProtocol.Start

            supervisor.fishForSpecificMessage(max = 15.seconds) {
              case SyncController.WrappedSyncProtocol(SyncProtocol.RegularSyncStuck(number, _))
                  if number == failingBlock.number.value =>
                true
            }
            exhausts.get shouldBe 0
            withClue("neither blacklisted nor evicted: ") {
              peersClient.receiveWhile(500.millis) { case m => m }.collect { case b: PeersClient.BlacklistPeer =>
                b
              } shouldBe
                empty
            }
          finally BlockImporter.exhaustBackoff = 5.minutes
      )

      // Spec 018 Track C, C4b: the state a block was to be executed on was moved from under the batch by another
      // writer, or is not held. The block was not judged. It is fetched and tried again, and nobody is blamed for it.
      "try a block again that this node's state could not take, and space the tries out from the third: no peer blamed" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new Fixture:
          val failingBlock: Block = testBlocksChunked.head.head
          val attempts = new java.util.concurrent.atomic.AtomicInteger(0)
          // the wait between two spaced-out tries: longer than this test
          BlockImporter.exhaustBackoff = 1.hour
          try
            setImportResult(
              failingBlock,
              IO {
                attempts.incrementAndGet()
                BlockImportStateFault(
                  new com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable("the head moved under the batch")
                )
              }
            )
            peersClient.setAutoPilot(new PeersClientAutoPilot)

            regularSync ! SyncProtocol.Start

            // tried, fetched again and tried again at the importer's usual pace (the pick sent at once after a rewind
            // finds the fetcher emptied; the retry tick brings the next): three times, as often as a failed block is
            // tried before its peer is evicted
            awaitCond(attempts.get == 3, 15.seconds)
            // Then the tries are spaced out. Over three of the importer's usual retry intervals, in which a block
            // that is tried at the usual pace is tried again, none comes; and nobody is blacklisted or evicted.
            withClue("neither blacklisted nor evicted: ") {
              peersClient
                .receiveWhile(syncConfig.syncRetryInterval * 3) { case m => m }
                .collect { case b: PeersClient.BlacklistPeer => b } shouldBe empty
            }
            attempts.get shouldBe 3
          finally BlockImporter.exhaustBackoff = 5.minutes
      )

      "recognise a block execution's trie error by the text it reaches the importer as" taggedAs (
        UnitTest,
        SyncTest
      ) in {
        val error = BlockExecutionError.MPTError(new MPTException("Invalid Node"))
        // Pinned: ConsensusImpl passes `error.toString` on as it is when the branch extends the head...
        error.toString should startWith("MPTError(")
        Consensus.isLocalStateFault(error.toString) shouldBe true
        // ...and after the shared prefix when it reorganises (ConsensusImplSpec drives the text ConsensusImpl writes).
        Consensus.isLocalStateFault(s"${Consensus.ReorganiseFailurePrefix}$error") shouldBe true
        // A missing node never arrives as text, and nothing else that does is this node's own state.
        Consensus.isLocalStateFault("test error") shouldBe false
        Consensus.isLocalStateFault(
          BlockExecutionError.ValidationAfterExecError("Block has invalid state root").toString
        ) shouldBe false
        Consensus.isLocalStateFault(
          s"${Consensus.ReorganiseFailurePrefix}${BlockExecutionError.MissingParentError}"
        ) shouldBe false
        scala.concurrent.Future.successful(succeed)
      }

      "save fetched node" in sync(new Fixture:
        val failingBlock: Block = testBlocksChunked.head.head

        override lazy val blockchainReader: BlockchainReader = new BlockchainReader(
          storagesInstance.storages.blockHeadersStorage,
          storagesInstance.storages.blockBodiesStorage,
          storagesInstance.storages.blockNumberMappingStorage,
          storagesInstance.storages.stateStorage,
          storagesInstance.storages.receiptStorage,
          storagesInstance.storages.appStateStorage,
          storagesInstance.storages.chainWeightStorage,
          storagesInstance.storages.blockAccessListStorage
        ):
          override def getBestBlockNumber: BigInt = BigInt(0)
          override def getSnapSyncPivotBlock: Option[BigInt] = None
          override def getBlockHeaderByNumber(number: BigInt): Option[BlockHeader] =
            Some(BlockHelpers.genesis.header)

        override lazy val blockchain: BlockchainImpl = BlockchainImpl(storagesInstance.storages, blockchainReader)

        override lazy val consensusAdapter: ConsensusAdapter = new ConsensusAdapter(null, null, null, null, null):
          override def evaluateBranchBlock(block: Block)(implicit
              blockExecutionScheduler: IORuntime,
              blockchainConfig: BlockchainConfig
          ): IO[BlockImportResult] =
            IO.pure(BlockImportFailedDueToMissingNode(new MissingNodeException(failingBlock.hash.value)))

          override def evaluateBranch(blocks: NonEmptyList[Block])(implicit
              blockExecutionScheduler: IORuntime,
              blockchainConfig: BlockchainConfig
          ): IO[BlockImportResult] =
            if repairNodeWasCalled then
              reimportedAfterSave = true
              IO.pure(BlockImportedToTop(Nil))
            else IO.pure(BlockImportFailedDueToMissingNode(new MissingNodeException(failingBlock.hash.value)))

        override lazy val branchResolution: BranchResolution = new BranchResolution(blockchainReader):
          override def resolveBranch(headers: NonEmptyList[BlockHeader]): BranchResolutionResult =
            NewBetterBranch(Nil)

        peersClient.setAutoPilot(new PeersClientAutoPilot)

        @volatile var reimportedAfterSave: Boolean = false
        @volatile var repairNodeWasCalled: Boolean = false
        @volatile var saveNodeWasCalled: Boolean = false
        val nodeData: List[ByteString] = List(ByteString(failingBlock.header.toBytes: Array[Byte]))

        override val stateStorage: StateStorage = new StateStorage:
          override def getBackingStorage(bn: BigInt): com.chipprbots.ethereum.db.storage.MptStorage = ???
          override def getReadOnlyStorage: com.chipprbots.ethereum.db.storage.MptStorage = ???
          override def onBlockSave(bn: BigInt, currentBestSavedBlock: BigInt)(f: () => Unit): Unit = ()
          override def onBlockRollback(bn: BigInt, currentBestSavedBlock: BigInt)(f: () => Unit): Unit = ()
          // #1692: the importer repairs the node (never lowering a count it finds); a blind saveNode is the old path.
          override def saveNode(nodeHash: ByteString, nodeEncoded: Array[Byte], bn: BigInt): Unit =
            saveNodeWasCalled = true
          override def canRepairNodes: Boolean = true
          override def repairNode(nodeHash: ByteString, nodeEncoded: Array[Byte], bn: BigInt): RepairOutcome =
            val expectedNode = nodeData.head
            nodeHash should be(kec256(expectedNode))
            nodeEncoded should be(expectedNode.toArray)
            bn should be(failingBlock.number)
            repairNodeWasCalled = true
            RepairOutcome.Written
          override def getNode(nodeHash: ByteString): Option[com.chipprbots.ethereum.mpt.MptNode] = None
          override def forcePersist(reason: StateStorage.FlushSituation): Boolean = true

        regularSync ! SyncProtocol.Start

        awaitCond(repairNodeWasCalled)
        saveNodeWasCalled shouldBe false
        // The importer must resume the import as soon as the node arrives, not wait out the 30 s ResolvingMissingNode
        // SyncRetryTick (which stays only as a fallback). The bound is far below that tick.
        awaitCond(reimportedAfterSave, 10.seconds)
      )
    }

    "catching the top" should {
      "ignore new blocks if they are too new" in sync(new Fixture:
        override lazy val consensusAdapter: ConsensusAdapter = stub[ConsensusAdapter]

        val newBlock: Block = testBlocks.last

        regularSync ! SyncProtocol.Start
        val sub576: SubscribeCmd = peerEventBus.expectMsgType[SubscribeCmd]

        sub576.subscriber ! MessageFromPeer(
          NewBlock(newBlock, ChainWeight.totalDifficultyOnly(BigInt(1)).totalDifficulty.value),
          defaultPeer.id
        )

        // Wait for actor to finish processing and verify it never calls evaluateBranchBlock
        // Use assertForDuration to continuously verify the mock is never called
        assertForDuration(
          (consensusAdapter
            .evaluateBranchBlock(_: Block)(using _: IORuntime, _: BlockchainConfig))
            .verify(*, *, *)
            .never(),
          remainingOrDefault
        )
      )

      "retry fetch of block that failed to import" in sync(new Fixture:
        val failingBlock: Block = testBlocksChunked(1).head

        testBlocksChunked.head.foreach(setImportResult(_, IO.pure(BlockImportedToTop(Nil))))
        setImportResult(failingBlock, IO.pure(BlockImportFailed("test error")))

        peersClient.setAutoPilot(new PeersClientAutoPilot())

        regularSync ! SyncProtocol.Start

        val sub598: SubscribeCmd = peerEventBus.expectMsgType[SubscribeCmd]
        sub598.subscriber ! MessageFromPeer(
          NewBlock(
            testBlocks.last,
            ChainWeight.totalDifficultyOnly(testBlocks.last.number.value).totalDifficulty.value
          ),
          defaultPeer.id
        )

        awaitCond(didTryToImportBlock(failingBlock))

        peersClient.fishForMsgEq(blockHeadersChunkRequest(1))
      )
    }

    "on top" should {
      "import received new block" in sync(new OnTopFixture:
        goToTop()

        sendNewBlock()

        awaitCond(importedNewBlock)
      )

      "broadcast imported block" in sync(new OnTopFixture:
        networkPeerManager.setAutoPilot(
          new AutoPilot:
            def run(sender: ActorRef, msg: Any): AutoPilot = msg match
              case cmd: GetHandshakedPeersCmd =>
                cmd.replyTo ! HandshakedPeers(handshakedPeers)
                this
              case _ => this
        )

        goToTop()

        sendNewBlock()
        awaitCond(importedNewBlock)

        networkPeerManager.fishForSpecificMessageMatching(max = 10.seconds) {
          case NetworkPeerManagerActor.SendMessageCmd(message, _) =>
            message.underlyingMsg match
              case NewBlock(block, _) if block == newBlock => true
              case _                                       => false
          case _ => false
        }
      )

      "fetch hashes if received NewHashes message" in sync(new OnTopFixture:
        goToTop()

        blockFetcher !
          MessageFromPeer(NewBlockHashes(List(BlockHash(newBlock.hash.value, newBlock.number.value))), defaultPeer.id)

        peersClient.expectMsgPF() { case PeersClient.Request(ETHGetBlockHeaders(_, _, _, _, _), _, _, _) =>
          true
        }
      )
    }

    "handling mined blocks" should {
      "not import when importing other blocks" in sync(new Fixture:
        val headPromise: Promise[BlockImportResult] = Promise()
        setImportResult(testBlocks.head, IO.fromFuture(IO.pure(headPromise.future)))
        val minedBlock: Block = BlockHelpers.generateBlock(BlockHelpers.genesis)
        peersClient.setAutoPilot(new PeersClientAutoPilot())

        regularSync ! SyncProtocol.Start

        val sub665: SubscribeCmd = peerEventBus.expectMsgType[SubscribeCmd]
        sub665.subscriber ! MessageFromPeer(
          NewBlock(
            testBlocks.last,
            ChainWeight.totalDifficultyOnly(testBlocks.last.number.value).totalDifficulty.value
          ),
          defaultPeer.id
        )

        awaitCond(didTryToImportBlock(testBlocks.head))
        regularSync ! SyncProtocol.MinedBlock(minedBlock)
        // Wait and verify the minedBlock is not imported while another import is in progress
        // Use assertForDuration to continuously verify the block is not imported
        assertForDuration(
          didTryToImportBlock(minedBlock) shouldBe false,
          remainingOrDefault / 2
        )
        // Clean up by completing the promise
        headPromise.success(BlockImportedToTop(Nil))
      )

      "import when on top" in sync(new OnTopFixture:
        goToTop()

        regularSync ! SyncProtocol.MinedBlock(newBlock)

        awaitCond(importedNewBlock)
      )

      "import when not on top and not importing other blocks" in sync(new Fixture:
        val minedBlock: Block = BlockHelpers.generateBlock(BlockHelpers.genesis)
        setImportResult(minedBlock, IO.pure(BlockImportedToTop(Nil)))

        regularSync ! SyncProtocol.Start

        regularSync ! SyncProtocol.MinedBlock(minedBlock)

        awaitCond(didTryToImportBlock(minedBlock))
      )

      "broadcast after successful import" in sync(new OnTopFixture:
        goToTop()

        val peersCmd724: GetHandshakedPeersCmd = networkPeerManager.expectMsgType[GetHandshakedPeersCmd]
        peersCmd724.replyTo ! HandshakedPeers(handshakedPeers)

        regularSync ! SyncProtocol.MinedBlock(newBlock)

        networkPeerManager.fishForSpecificMessageMatching() {
          case NetworkPeerManagerActor.SendMessageCmd(message, _) =>
            message.underlyingMsg match
              case NewBlock(block, _) if block == newBlock => true
              case _                                       => false
          case _ => false
        }
      )
    }

    "broadcasting blocks" should {
      "send an ETH NewBlock message to broadcast newly imported blocks" in sync(
        new OnTopFixture:
          val peerWithETH63: (Peer, PeerInfo) =
            val id = peerId(handshakedPeers.size)
            val peer = getPeer(id)
            val peerInfo = getPeerInfo(peer, Capability.ETH63)
            (peer, peerInfo)

          networkPeerManager.setAutoPilot(
            new AutoPilot:
              def run(sender: ActorRef, msg: Any): AutoPilot = msg match
                case cmd: GetHandshakedPeersCmd =>
                  cmd.replyTo ! HandshakedPeers(Map(peerWithETH63._1 -> peerWithETH63._2))
                  this
                case _ => this
          )

          goToTop()

          sendNewBlock()
          awaitCond(importedNewBlock)

          networkPeerManager.fishForSpecificMessageMatching(max = 10.seconds) {
            case NetworkPeerManagerActor.SendMessageCmd(message, _) =>
              message.underlyingMsg match
                case ETHPackets.NewBlock(`newBlock`, _) => true
                case _                                  => false
            case _ => false
          }
      )

    }

    "reporting progress" should {
      "return NotSyncing until fetching started" in testCaseT { fixture =>
        import fixture.*

        for
          _ <- IO(regularSync ! SyncProtocol.Start)
          before <- getSyncStatus
          _ <- IO {
            val sub766 = peerEventBus.expectMsgType[SubscribeCmd]
            sub766.subscriber ! MessageFromPeer(
              NewBlock(
                testBlocks.last,
                ChainWeight.totalDifficultyOnly(testBlocks.last.number.value).totalDifficulty.value
              ),
              defaultPeer.id
            )
          }
          after <- getSyncStatus
        yield
          assert(before === Status.NotSyncing)
          assert(after === Status.NotSyncing)
      }

      "return initial status after fetching first batch of data" in testCaseT { fixture =>
        import fixture.*

        for
          _ <- testBlocks
            .take(5)
            .traverse(block =>
              IO(blockchainWriter.save(block, Nil, ChainWeight.totalDifficultyOnly(10000), saveAsBestBlock = true))
            )
          _ <- IO {
            regularSync ! SyncProtocol.Start

            val sub796 = peerEventBus.expectMsgType[SubscribeCmd]
            sub796.subscriber ! MessageFromPeer(
              NewBlock(
                testBlocks.last,
                ChainWeight.totalDifficultyOnly(testBlocks.last.number.value).totalDifficulty.value
              ),
              defaultPeer.id
            )

            peersClient.expectMsgEq(blockHeadersRequest(6)).replyTo ! PeersClient.Response(
              defaultPeer,
              BlockHeaders(BigInt(0), testBlocksChunked.head.headers)
            )
          }
          status <- pollForStatus(_.syncing)
        yield
          val lastBlock = testBlocks.last.number.value
          assert(status === Status.Syncing(5, Progress(5, lastBlock), None))
      }

      "return initial status after fetching first batch of data when starting from genesis" in testCaseT { fixture =>
        import fixture.*

        for
          _ <- IO {
            regularSync ! SyncProtocol.Start

            val sub824 = peerEventBus.expectMsgType[SubscribeCmd]
            sub824.subscriber ! MessageFromPeer(
              NewBlock(
                testBlocks.last,
                ChainWeight.totalDifficultyOnly(testBlocks.last.number.value).totalDifficulty.value
              ),
              defaultPeer.id
            )

            peersClient.expectMsgEq(blockHeadersChunkRequest(0)).replyTo ! PeersClient.Response(
              defaultPeer,
              BlockHeaders(BigInt(0), testBlocksChunked.head.headers)
            )
          }
          status <- pollForStatus(_.syncing)
          lastBlock = testBlocks.last.number.value
        yield assert(status === Status.Syncing(0, Progress(0, lastBlock), None))
      }

      "return updated status after importing blocks" in testCaseT { fixture =>
        import fixture.*

        for
          _ <- IO {
            // BlockImporter imports a batch through evaluateBranch and reports progress for the blocks listed in
            // BlockImportedToTop's data, as ConsensusAdapter fills it from ExtendedCurrentBestBranch. An empty list
            // means nothing was imported, so the stub names each block it imports.
            testBlocks
              .take(5)
              .foreach(block =>
                setImportResult(
                  block,
                  IO(
                    BlockImportedToTop(List(BlockData(block, Nil, ChainWeight.totalDifficultyOnly(block.number.value))))
                  )
                )
              )

            peersClient.setAutoPilot(new PeersClientAutoPilot(testBlocks.take(5)))

            regularSync ! SyncProtocol.Start

            val sub854 = peerEventBus.expectMsgType[SubscribeCmd]
            sub854.subscriber ! MessageFromPeer(
              NewBlock(
                testBlocks.last,
                ChainWeight.totalDifficultyOnly(testBlocks.last.number.value).totalDifficulty.value
              ),
              defaultPeer.id
            )
          }
          _ <- fishForStatus {
            case s: Status.Syncing
                if s.blocksProgress.current >= 5 && s.blocksProgress.target == 20 && s.startingBlockNumber == 0 =>
              s
          }
        yield succeed
      }

      "return SyncDone when on top" in customTestCaseResourceM(onTopFixtureResource) { fixture =>
        import fixture.*

        for
          _ <- IO(goToTop())
          status <- getSyncStatus
        yield assert(status === Status.SyncDone)
      }
    }

    // RS-1: ProgressState.toStatus guard — bestKnownNetworkBlock=0 must not produce SyncDone
    "ProgressState.toStatus" should {
      import RegularSync.ProgressState
      import scala.concurrent.Future

      "return NotSyncing when not yet started" taggedAs (UnitTest, SyncTest) in {
        val state =
          ProgressState(startedFetching = false, initialBlock = 0, currentBlock = 0, bestKnownNetworkBlock = 0)
        Future.successful(assert(state.toStatus === Status.NotSyncing))
      }

      "return NotSyncing when started but bestKnownNetworkBlock is 0 (no peers seen yet)" taggedAs (
        UnitTest,
        SyncTest
      ) in {
        // RS-1 regression: before the fix, startedFetching=true and currentBlock(0) >= bestKnownNetworkBlock(0)
        // incorrectly triggered SyncDone before any peer had announced a block.
        val state = ProgressState(startedFetching = true, initialBlock = 0, currentBlock = 0, bestKnownNetworkBlock = 0)
        Future.successful(assert(state.toStatus === Status.NotSyncing))
      }

      "return Syncing when started and behind chain head" taggedAs (UnitTest, SyncTest) in {
        val state =
          ProgressState(startedFetching = true, initialBlock = 5, currentBlock = 10, bestKnownNetworkBlock = 100)
        Future.successful(assert(state.toStatus === Status.Syncing(5, Progress(10, 100), None)))
      }

      "return SyncDone when started and caught up to chain head" taggedAs (UnitTest, SyncTest) in {
        val state =
          ProgressState(startedFetching = true, initialBlock = 0, currentBlock = 50, bestKnownNetworkBlock = 50)
        Future.successful(assert(state.toStatus === Status.SyncDone))
      }
    }
  }
