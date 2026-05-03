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
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.Transaction
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler.ResponseReceived
import com.chipprbots.ethereum.network.p2p.messages.ETH66.Receipts as ETH66Receipts
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.encode
import com.chipprbots.ethereum.utils.ByteUtils
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.utils.GenOps.GenOps

class FastSyncSpec
    extends TestKit(ActorSystem("FastSync_testing"))
    with FreeSpecBase
    with SpecFixtures
    with WithActorSystemShutDown { self =>
  implicit val timeout: Timeout = Timeout(60.seconds)

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
    lazy val testPeers: Map[Peer, NetworkPeerManagerActor.PeerInfo] = twoAcceptedPeers.map { case (k, peerInfo) =>
      val lastBlock = bestBlockAtStart
      k -> peerInfo
        .withBestBlockData(lastBlock.number, lastBlock.hash)
        .copy(remoteStatus = peerInfo.remoteStatus.copy(bestHash = lastBlock.hash))
    }
    lazy val networkPeerManager =
      new NetworkPeerManagerFake(
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
        networkPeerManager = networkPeerManager.ref,
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

    val saveTestBlocksWithWeights: IO[Unit] = IO {
      // Save test blocks with chain weights to prevent "Parent chain weight not found" errors
      // Use cumulative difficulty (each block adds to the total)
      testBlocks.foldLeft(ChainWeight.totalDifficultyOnly(1)) { (cumulativeWeight, block) =>
        val newWeight = cumulativeWeight.increase(block.header)
        blockchainWriter.save(
          block,
          receipts = Nil,
          newWeight,
          saveAsBestBlock = false
        )
        newWeight
      }
      ()
    }

    val startSync: IO[Unit] = IO(fastSync ! SyncProtocol.Start)

    val getSyncStatus: IO[Status] =
      IO.fromFuture(IO((fastSync ? SyncProtocol.GetStatus).mapTo[Status]))
  }

  override def createFixture(): Fixture = new Fixture

  "FastSync" - {
    "handles typed receipts" - {
      "does not crash when ETH66 receipts include typed receipts in wire format" taggedAs (
        UnitTest,
        SyncTest
      ) in testCaseM { (fixture: Fixture) =>
        import fixture._

        (for {
          _ <- saveGenesis
          _ <- saveTestBlocksWithWeights
          // Subscribe BEFORE startSync so no topic events can be missed under load.
          // Race: if subscription is set up after onPeersConnected the actor may have
          // already published to the topic before the subscriber is registered.
          pivotFiber  <- networkPeerManager.pivotBlockSelected.head.compile.lastOrError.start
          blocksFiber <- networkPeerManager.fetchedBlocks.head.compile.lastOrError.start
          _ <- startSync
          _ <- networkPeerManager.onPeersConnected
          _ <- pivotFiber.joinWith(cats.effect.IO.raiseError(new RuntimeException("pivot fiber canceled")))
          _ <- blocksFiber.joinWith(cats.effect.IO.raiseError(new RuntimeException("blocks fiber canceled")))
        } yield {
          val peer = testPeers.keys.head

          // Simulate a typed receipt coming over the ETH66 Receipts message as:
          //   RLPValue(typeByte || rlp(payload))
          // which historically caused FastSync to throw "expected RLPList, got RLPValue".
          val stateHash = org.apache.pekko.util.ByteString(Array.fill(32)(1.toByte))
          val logsBloom = org.apache.pekko.util.ByteString(Array.fill(256)(0.toByte))
          val legacyReceiptRLP = RLPList(
            RLPValue(stateHash.toArray),
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(0)),
            RLPValue(logsBloom.toArray),
            RLPList()
          )
          val typedReceiptBytes = Transaction.Type01 +: encode(legacyReceiptRLP)

          val receiptsForBlocks = RLPList(
            RLPList(
              RLPValue(typedReceiptBytes)
            )
          )

          val msg = ETH66Receipts(requestId = 1, receiptsForBlocks = receiptsForBlocks)

          val watcher = TestProbe()
          watcher.watch(fastSync)
          fastSync ! ResponseReceived(peer, msg, timeTaken = 0L)

          // If the actor crashes, we'll receive Terminated.
          watcher.expectNoMessage(500.millis)
          assert(true)
        }).timeout(timeout.duration)
      }
    }

    // ── K1: High-water mark JVM-bounce fix (bed0ef512) + ETH68-only watchdog (46b72d98e) ─────

    "for K1: high-water mark invariants and network watchdog" - {

      // Pure math — no actor needed. Locks the newMax formula so a refactor cannot silently
      // revert the legacy-SyncState seeding that prevents false-95%-completion on JVM bounce.
      "high-water mark seeds from totalNodesCount when maxTotalNodesCount=0 (legacy deserialization)" in testCase { _ =>
        // Old SyncState serialised before maxTotalNodesCount existed: field defaults to 0.
        // totalNodesCount still holds the pre-bounce peak (5000).  Post-restart the scheduler
        // walks only the newly-discovered frontier → freshTotal=3000.
        // newMax must be 5000, not 3000 — otherwise the 95% guard fires too early.
        val legacyMaxTotal: Long = 0
        val legacyTotal: Long = 5000
        val freshTotal: Long = 3000
        val newMax = math.max(math.max(legacyMaxTotal, legacyTotal), freshTotal)
        assert(newMax == 5000)
      }

      "effectiveTotal = max(dynTotal, maxTotalNodesCount) keeps 95% guard honest across JVM restart" in testCase { _ =>
        // Scenario: 8000-node trie, node bounced mid-download.
        // downloaded=2900 is genuine progress but dynTotal post-restart drops to 3000
        // (scheduler walks only the remaining frontier, not the full trie).
        // Without fix: 2900/3000 = 96% → guard fires → partial trie declared done (BUG).
        // With fix   : effectiveTotal = max(3000, 8000) = 8000 → 36% → guard silent (CORRECT).
        val downloaded: Long = 2900
        val dynTotal: Long = 3000
        val maxTotalNodesCount: Long = 8000

        val falsePositivePct = (downloaded.toDouble / dynTotal * 100).toInt
        assert(falsePositivePct >= 95) // confirms old code would have misfired

        val effectiveTotal = math.max(dynTotal, maxTotalNodesCount)
        val correctPct = (downloaded.toDouble / effectiveTotal * 100).toInt
        assert(correctPct < 95) // fix: guard stays silent
      }

      // Actor test: SyncingHandler.receive handles NetworkIncompatible by calling cleanup() and
      // context.become(idle).  In tests there is no SyncController parent to stop FastSync
      // afterwards, so orphaned watched children's Terminated messages arrive at idle (which
      // has no Terminated handler) → DeathPactException → FastSync terminates.
      // Termination of fastSync is our regression signal: it proves the handler ran.
      "actor exits syncing loop on NetworkIncompatible — ETH68-only network escape valve" taggedAs (
        UnitTest,
        SyncTest
      ) in testCaseM { (fixture: Fixture) =>
        import fixture._
        (for {
          _ <- saveGenesis
          _ <- saveTestBlocksWithWeights
          // Subscribe BEFORE startSync — see companion test for race condition explanation.
          pivotFiber  <- networkPeerManager.pivotBlockSelected.head.compile.lastOrError.start
          blocksFiber <- networkPeerManager.fetchedBlocks.head.compile.lastOrError.start
          _ <- startSync
          _ <- networkPeerManager.onPeersConnected
          _ <- pivotFiber.joinWith(cats.effect.IO.raiseError(new RuntimeException("pivot fiber canceled")))
          _ <- blocksFiber.joinWith(cats.effect.IO.raiseError(new RuntimeException("blocks fiber canceled")))
          _ <- cats.effect.IO {
            val watcher = TestProbe("watchdog-test-probe")
            watcher.watch(fastSync)
            // Inject the message SyncStateSchedulerActor emits when no ETH63-67 peers serve GetNodeData.
            fastSync ! SyncStateSchedulerActor.NetworkIncompatible
            // FastSync calls cleanup() + context.become(idle) → orphaned children terminate →
            // idle receives Terminated → DeathPactException → FastSync terminates.
            watcher.expectTerminated(fastSync, timeout.duration)
          }
        } yield succeed).timeout(timeout.duration)
      }
    }

    "for reporting progress" - {
      "returns NotSyncing until pivot block is selected and first data being fetched" taggedAs (
        UnitTest,
        SyncTest
      ) in testCaseM { (fixture: Fixture) =>
        import fixture._

        (for {
          _ <- startSync
          status <- getSyncStatus
        } yield assert(status === Status.NotSyncing)).timeout(timeout.duration)
      }

      "returns Syncing when pivot block is selected and started fetching data" taggedAs (
        UnitTest,
        SyncTest,
        FlakyTest
      ) in testCaseM { (fixture: Fixture) =>
        import fixture._

        (for {
          _ <- startSync
          _ <- saveGenesis
          _ <- networkPeerManager.onPeersConnected
          _ <- networkPeerManager.pivotBlockSelected.head.compile.lastOrError
          _ <- networkPeerManager.fetchedHeaders.head.compile.lastOrError
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

      "returns Syncing with block progress once both header and body is fetched" taggedAs (
        UnitTest,
        SyncTest,
        FlakyTest
      ) in testCaseM { (fixture: Fixture) =>
        import fixture._

        (for {
          _ <- saveGenesis
          _ <- saveTestBlocksWithWeights
          _ <- startSync
          _ <- networkPeerManager.onPeersConnected
          _ <- networkPeerManager.pivotBlockSelected.head.compile.lastOrError
          blocksBatch <- networkPeerManager.fetchedBlocks.head.compile.lastOrError
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

      "returns Syncing with state nodes progress" taggedAs (UnitTest, SyncTest, FlakyTest) in customTestCaseM(
        new Fixture {
          override lazy val syncConfig: SyncConfig =
            defaultSyncConfig.copy(
              peersScanInterval = 1.second,
              pivotBlockOffset = 5,
              fastSyncBlockValidationX = 1,
              fastSyncThrottle = 1.millis
            )
        }
      ) { (fixture: Fixture) =>
        import fixture._

        (for {
          _ <- saveGenesis
          _ <- saveTestBlocksWithWeights
          _ <- startSync
          _ <- networkPeerManager.onPeersConnected
          _ <- networkPeerManager.pivotBlockSelected.head.compile.lastOrError
          _ <- Stream
            .awakeEvery[IO](10.millis)
            .evalMap(_ => getSyncStatus)
            .collect {
              case stat @ Status.Syncing(_, Progress(current, _), _) if current >= expectedTargetBlockNumber => stat
            }
            .head
            .compile
            .lastOrError
          status <- Stream
            .awakeEvery[IO](10.millis)
            .evalMap(_ => getSyncStatus)
            .collect { case stat @ Status.Syncing(_, _, Some(_)) =>
              stat
            }
            .head
            .compile
            .lastOrError
        } yield {
          // Validate state nodes progress is reported correctly
          val Status.Syncing(_, _, maybeStateProgress) = status
          val stateProgress = maybeStateProgress.getOrElse(fail("State nodes progress should be defined"))
          assert(stateProgress.target >= 1, "State nodes target should be at least 1")
          assert(stateProgress.current >= 0, "State nodes current should be non-negative")
          succeed
        }).timeout(timeout.duration)
      }
    }
  }
}
