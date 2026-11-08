package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.actor.PoisonPill
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe

import scala.concurrent.Future
import scala.concurrent.duration.*

import org.scalamock.scalatest.AsyncMockFactory
import org.scalatest.Assertion
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.WordSpecBase
import com.chipprbots.ethereum.blockchain.sync.PeersClient
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.GetHandshakedPeersCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscribeCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders as ETHGetBlockHeaders
import com.chipprbots.ethereum.testing.Tags.*

/** #1705: RegularSync owns its fetch-and-import pipeline. When the fetcher, the importer, or a child of the fetcher
  * stops, the whole pipeline is replaced from the best block, after a backoff, for as long as the node runs. Before, a
  * stopped fetcher was re-spawned with an importer that was never started, a stopped child of the fetcher was only
  * logged, and a stopped importer was not watched: each stopped regular sync for good.
  */
class RegularSyncPipelineSpec extends WordSpecBase with Matchers with AsyncMockFactory with RegularSyncFixtures:

  def sync[T <: RegularSyncFixture](test: => T): Future[Assertion] =
    import scala.concurrent.ExecutionContext.Implicits.global
    Future {
      val fixture = test
      try succeed
      finally fixture.shutdownFixture()
    }

  class PipelineFixture extends RegularSyncFixture:

    /** The subscriber of the next BlockFetcher that starts: each subscribes to new blocks as it starts. */
    def awaitFetcherStart(): TypedActorRef[PeerEvent] =
      peerEventBus.fishForSpecificMessage(10.seconds) {
        case SubscribeCmd(MessageClassifier(codes, _), subscriber) if codes.contains(Codes.BlockRangeUpdateCode) =>
          subscriber
      }

    def awaitHeadersRequestFrom(block: BigInt): Unit =
      peersClient.fishForSpecificMessage(10.seconds) {
        case PeersClient.Request(ETHGetBlockHeaders(_, Left(from), _, _, _), _, _, _) if from == block => ()
      }

    /** Stops the child of RegularSync at `path` (wildcards allowed), as a failure inside it would. */
    def stopChild(path: String): Unit =
      system.actorSelection(s"${regularSync.path}/$path") ! PoisonPill

  "The pipeline backoff" should {
    val backoff = RegularSync.PipelineBackoff(initial = 1.second, max = 30.seconds, stableAfter = 60.seconds)

    "wait the initial interval before replacing the first pipeline" taggedAs (UnitTest, SyncTest) in {
      backoff.next(None, 0.seconds) shouldBe 1.second
    }

    "double while pipelines keep stopping soon after they start, up to the cap, and never give up" taggedAs (
      UnitTest,
      SyncTest
    ) in {
      val waits = Iterator.iterate(backoff.next(None, 0.seconds))(w => backoff.next(Some(w), 5.seconds)).take(8).toList
      waits shouldBe List(1, 2, 4, 8, 16, 30, 30, 30).map(_.seconds)
    }

    "go back to the initial interval once a pipeline has run for the stable interval, imports or not" taggedAs (
      UnitTest,
      SyncTest
    ) in {
      backoff.next(Some(30.seconds), 60.seconds) shouldBe 1.second
      backoff.next(Some(30.seconds), 59.seconds) shouldBe 30.seconds
    }
  }

  "Regular sync's fetch-and-import pipeline" should {

    // T1: the re-spawned importer was never started, nor was the fetcher it would have started.
    "start a new pipeline from the best block when its fetcher stops" taggedAs (UnitTest, SyncTest) in sync(
      new PipelineFixture:
        regularSync ! SyncProtocol.Start
        val first: TypedActorRef[PeerEvent] = awaitFetcherStart()
        awaitHeadersRequestFrom(1)

        stopChild("block-fetcher-*")

        awaitFetcherStart() should not be first
        awaitHeadersRequestFrom(1)
    )

    // T2: every re-spawned importer zeroed the count, so a fault that kept stopping the fetcher never escalated.
    "keep the session's exhaust count across a new pipeline" taggedAs (UnitTest, SyncTest) in sync(
      new PipelineFixture:
        regularSync ! SyncProtocol.Start
        awaitFetcherStart()
        exhausts.increment()
        exhausts.increment()

        stopChild("block-fetcher-*")

        awaitFetcherStart()
        exhausts.get shouldBe 2
    )

    // T3: the importer was restarted by Pekko into a state that waited for a start, then stopped unwatched.
    "start a new pipeline when its importer stops" taggedAs (UnitTest, SyncTest) in sync(
      new PipelineFixture:
        regularSync ! SyncProtocol.Start
        val first: TypedActorRef[PeerEvent] = awaitFetcherStart()
        awaitHeadersRequestFrom(1)

        stopChild("block-importer-*")

        awaitFetcherStart() should not be first
        awaitHeadersRequestFrom(1)
    )

    // T5: a stopped headers fetcher was logged, and its request, counted in flight for good, held every later one.
    "start a new pipeline when the fetcher's headers fetcher stops" taggedAs (UnitTest, SyncTest) in sync(
      new PipelineFixture:
        regularSync ! SyncProtocol.Start
        val first: TypedActorRef[PeerEvent] = awaitFetcherStart()
        awaitHeadersRequestFrom(1) // never answered: the child takes it with it

        stopChild("block-fetcher-*/headers-fetcher")

        awaitFetcherStart() should not be first
        awaitHeadersRequestFrom(1)
    )
  }

  "The importer" should {
    // CHASE B: the resolver was not watched, and `resolvingFork` has no timeout, so one that stopped without an answer
    // left the importer there for good.
    "fall back to the blind rewind when its branch resolver stops without an answer" taggedAs (
      UnitTest,
      SyncTest
    ) in sync(
      new PipelineFixture:
        override lazy val blockchainReader: BlockchainReader = stub[BlockchainReader]
        (() => blockchainReader.getBestBlockNumber).when().returns(BigInt(20))
        (() => blockchainReader.getSnapSyncPivotBlock).when().returns(None)
        (blockchainReader.getBlockHeaderByNumber(_: BigInt)).when(BigInt(0)).returns(Some(BlockHelpers.genesis.header))
        (blockchainReader.getCanonicalHashByNumber(_: BigInt)).when(*).returns(None)
        override lazy val blockchainWriter: BlockchainWriter = stub[BlockchainWriter]

        val importerFetcher: TestProbe = TestProbe("resolverImporterFetcher")
        val importer: TypedActorRef[BlockImporter.Command] = testKit.spawn(
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
            TestProbe().ref,
            pendingTransactionsManager.ref
              .toTyped[com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command],
            blockTopic,
            TestProbe().ref,
            peerEventBus.ref,
            networkPeerManager.ref,
            blockchain,
            blacklist,
            this,
            exhausts = exhausts
          ),
          "resolver-stopped-importer"
        )
        importerFetcher.expectMsgClass(10.seconds, classOf[BlockFetcher.Start])

        importer ! BlockImporter.StartForkRecovery(BigInt(15))
        // The resolver asks for peers as it starts: it is up.
        networkPeerManager.fishForMessage(10.seconds) {
          case _: GetHandshakedPeersCmd => true
          case _                        => false
        }
        system.actorSelection(s"${importer.path}/*") ! PoisonPill

        importerFetcher
          .fishForSpecificMessage(10.seconds) { case m: BlockFetcher.InvalidateBlocksFrom => m }
          .fromBlock shouldBe 1
        testKit.stop(importer)
    )
  }
