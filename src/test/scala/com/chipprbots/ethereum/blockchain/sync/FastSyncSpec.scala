package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.pattern.ask
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.Timeout

import cats.effect.IO

import scala.concurrent.duration.DurationInt

import fs2.Stream

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.FreeSpecBase
import com.chipprbots.ethereum.ObjectGenerators
import com.chipprbots.ethereum.SpecFixtures
import com.chipprbots.ethereum.WithActorSystemShutDown
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol.Status
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol.Status.Progress
import com.chipprbots.ethereum.blockchain.sync.fast.FastSync
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.EtcPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.utils.GenOps.GenOps

class FastSyncSpec
    extends TestKit(ActorSystem("FastSync_testing"))
    with FreeSpecBase
    with SpecFixtures
    with WithActorSystemShutDown { self =>
  implicit val timeout: Timeout = Timeout(30.seconds)

  class Fixture extends EphemBlockchainTestSetup with TestSyncConfig with TestSyncPeers {
    implicit override lazy val system: ActorSystem = self.system

    val blacklistMaxElems: Int = 100
    val blacklist: CacheBasedBlacklist = CacheBasedBlacklist.empty(blacklistMaxElems)

    override lazy val syncConfig: SyncConfig =
      defaultSyncConfig.copy(pivotBlockOffset = 5, fastSyncBlockValidationX = 5, fastSyncThrottle = 1.millis)
    lazy val (stateRoot, trieProvider) = {
      val stateNodesData = ObjectGenerators.genMultipleNodeData(20).pickValue

      lazy val trieProvider = StateSyncUtils.TrieProvider()
      lazy val stateRoot = trieProvider.buildWorld(stateNodesData)

      (stateRoot, trieProvider)
    }

    lazy val testBlocks: List[Block] = BlockHelpers.generateChain(
      20,
      BlockHelpers.genesis,
      block => block.copy(header = block.header.copy(stateRoot = stateRoot))
    )

    lazy val bestBlockAtStart: Block = testBlocks(10)
    lazy val expectedPivotBlockNumber: BigInt = bestBlockAtStart.number - syncConfig.pivotBlockOffset
    lazy val expectedTargetBlockNumber: BigInt = expectedPivotBlockNumber + syncConfig.fastSyncBlockValidationX
    lazy val testPeers: Map[Peer, EtcPeerManagerActor.PeerInfo] = twoAcceptedPeers.map { case (k, peerInfo) =>
      val lastBlock = bestBlockAtStart
      k -> peerInfo
        .withBestBlockData(lastBlock.number, lastBlock.hash)
        .copy(remoteStatus = peerInfo.remoteStatus.copy(bestHash = lastBlock.hash))
    }
    lazy val etcPeerManager =
      new EtcPeerManagerFake(
        syncConfig,
        testPeers,
        testBlocks,
        req => trieProvider.getNodes(req).map(_.data)
      )
    lazy val peerEventBus: TestProbe = TestProbe("peer_event-bus")
    lazy val fastSync: ActorRef = system.actorOf(
      FastSync.props(
        fastSyncStateStorage = storagesInstance.storages.fastSyncStateStorage,
        appStateStorage = storagesInstance.storages.appStateStorage,
        blockNumberMappingStorage = storagesInstance.storages.blockNumberMappingStorage,
        blockchain = blockchain,
        blockchainReader = blockchainReader,
        blockchainWriter = blockchainWriter,
        evmCodeStorage = storagesInstance.storages.evmCodeStorage,
        nodeStorage = storagesInstance.storages.nodeStorage,
        stateStorage = storagesInstance.storages.stateStorage,
        validators = validators,
        peerEventBus = peerEventBus.ref,
        etcPeerManager = etcPeerManager.ref,
        blacklist = blacklist,
        syncConfig = syncConfig,
        scheduler = system.scheduler,
        configBuilder = this
      )
    )

    val saveGenesis: IO[Unit] = IO {
      blockchainWriter.save(
        BlockHelpers.genesis,
        receipts = Nil,
        ChainWeight.totalDifficultyOnly(1),
        saveAsBestBlock = true
      )
    }

    val startSync: IO[Unit] = IO(fastSync ! SyncProtocol.Start)

    val getSyncStatus: IO[Status] =
      IO.fromFuture(IO((fastSync ? SyncProtocol.GetStatus).mapTo[Status]))
  }

  override def createFixture(): Fixture = new Fixture

  "FastSync" - {
    "for reporting progress" - {
      "returns NotSyncing until pivot block is selected and first data being fetched" taggedAs(UnitTest, SyncTest) in testCaseM {
        (fixture: Fixture) =>
          import fixture._

          (for {
            _ <- startSync
            status <- getSyncStatus
          } yield assert(status === Status.NotSyncing)).timeout(timeout.duration)
      }

      "returns Syncing when pivot block is selected and started fetching data" taggedAs(UnitTest, SyncTest) in testCaseM { (fixture: Fixture) =>
        import fixture._

        (for {
          _ <- startSync
          _ <- saveGenesis
          _ <- etcPeerManager.onPeersConnected
          _ <- etcPeerManager.pivotBlockSelected.head.compile.lastOrError
          _ <- etcPeerManager.fetchedHeaders.head.compile.lastOrError
          status <- getSyncStatus
        } yield status match {
          case Status.Syncing(startingBlockNumber, blocksProgress, stateNodesProgress) =>
            assert(startingBlockNumber === BigInt(0))
            assert(blocksProgress.target === expectedPivotBlockNumber)
            assert(stateNodesProgress === Some(Progress(0, 1)))
          case Status.NotSyncing | Status.SyncDone => fail("Expected syncing status")
        })
          .timeout(timeout.duration)
      }

      "returns Syncing with block progress once both header and body is fetched" taggedAs(UnitTest, SyncTest) in testCaseM { (fixture: Fixture) =>
        import fixture._

        (for {
          _ <- saveGenesis
          _ <- startSync
          _ <- etcPeerManager.onPeersConnected
          _ <- etcPeerManager.pivotBlockSelected.head.compile.lastOrError
          blocksBatch <- etcPeerManager.fetchedBlocks.head.compile.lastOrError
          status <- getSyncStatus
          lastBlockFromBatch = blocksBatch.lastOption.map(_.number).getOrElse(BigInt(0))
        } yield status match {
          case Status.Syncing(startingBlockNumber, blocksProgress, stateNodesProgress) =>
            assert(startingBlockNumber === BigInt(0))
            assert(blocksProgress.current >= lastBlockFromBatch)
            assert(blocksProgress.target === expectedPivotBlockNumber)
            assert(stateNodesProgress === Some(Progress(0, 1)))
          case Status.NotSyncing | Status.SyncDone => fail("Expected other state")
        })
          .timeout(timeout.duration)
      }

      "returns Syncing with state nodes progress" taggedAs(UnitTest, SyncTest) in customTestCaseM(new Fixture {
        override lazy val syncConfig: SyncConfig =
          defaultSyncConfig.copy(
            peersScanInterval = 1.second,
            pivotBlockOffset = 5,
            fastSyncBlockValidationX = 1,
            fastSyncThrottle = 1.millis
          )
      }) { (fixture: Fixture) =>
        import fixture._

        (for {
          _ <- saveGenesis
          _ <- startSync
          _ <- etcPeerManager.onPeersConnected
          _ <- etcPeerManager.pivotBlockSelected.head.compile.lastOrError
          _ <- Stream
            .awakeEvery[IO](10.millis)
            .evalMap(_ => getSyncStatus)
            .collect {
              case stat @ Status.Syncing(_, Progress(current, _), _) if current >= expectedTargetBlockNumber => stat
            }
            .head
            .compile
            .lastOrError
          _ <- Stream
            .awakeEvery[IO](10.millis)
            .evalMap(_ => getSyncStatus)
            .collect {
              case stat @ Status.Syncing(_, _, Some(stateNodesProgress)) if stateNodesProgress.target > 1 =>
                stat
            }
            .head
            .compile
            .lastOrError
        } yield succeed).timeout(timeout.duration)
      }
    }
  }
}
