package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe

import cats.data.NonEmptyList
import cats.effect.IO

import scala.concurrent.Future
import scala.concurrent.duration.*

import org.scalamock.scalatest.AsyncMockFactory
import org.scalatest.Assertion
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.WordSpecBase
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.*
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** The last-import duration (spec 027 C-7, T090, T099): `BlockImporter` times the consensus call that imports a block
  * and sets `RegularSyncMetrics`' gauge, so the TUI can show how long imports take without a change to any consensus
  * file. Only an import that put blocks on the chain sets it; a block that did not import leaves the last value.
  *
  * The consensus call is a stub that takes at least a known time (`IO.sleep`): the gauge must be at least that, which
  * holds on any machine. No upper bound is asserted.
  */
class BlockImporterMetricsSpec extends WordSpecBase with Matchers with AsyncMockFactory with RegularSyncFixtures:

  def sync[T <: RegularSyncFixture](test: => T): Future[Assertion] =
    import scala.concurrent.ExecutionContext.Implicits.global
    Future {
      val fixture = test
      try succeed
      finally fixture.shutdownFixture()
    }

  class ImporterFixture extends RegularSyncFixture:

    // No retry ticks within a test: the one PickBlocks an importer sends is then the one its own ImportDone asks for, so
    // `awaitIdle` says exactly that an import has ended.
    override lazy val syncConfig: SyncConfig = defaultSyncConfig.copy(syncRetryInterval = 1.hour)

    /** An importer of its own, outside `regularSync`, driving `fetcher` (as `UnknownParentImportSpec` builds one). */
    def spawnImporter(fetcher: TestProbe, name: String): TypedActorRef[BlockImporter.Command] =
      val importer = testKit.spawn(
        BlockImporter.apply(
          fetcher.ref.toTyped[BlockFetcher.FetchCommand],
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
          exhausts = exhausts,
          importLock = importLock
        ),
        name
      )
      fetcher.expectMsgClass(10.seconds, classOf[BlockFetcher.Start])
      importer

    /** The importer is idle again: it asks the fetcher for blocks only when no import runs. */
    def awaitIdle(fetcher: TestProbe): Unit =
      fetcher.fishForSpecificMessage(10.seconds) {
        case _: BlockFetcher.PickBlocks       => ()
        case _: BlockFetcher.StrictPickBlocks => ()
      }

    def imported(block: Block): BlockImportResult =
      BlockImportedToTop(List(BlockData(block, Nil, ChainWeight.totalDifficultyOnly(block.number.value))))

  "BlockImporter" should {

    "set the last-import duration around the consensus call of a batch it imports, with the batch's block count" taggedAs (
      UnitTest,
      SyncTest
    ) in sync(
      new ImporterFixture:
        val fetcher: TestProbe = TestProbe("timed-batch-fetcher")
        val importer: TypedActorRef[BlockImporter.Command] = spawnImporter(fetcher, "timed-batch-importer")
        // A batch on the local best block (genesis): evaluateBranch imports it block by block, 50 ms each.
        val batch: List[Block] = BlockHelpers.generateChain(3, BlockHelpers.genesis)
        batch.foreach(b => setImportResult(b, IO.sleep(50.millis) *> IO.pure(imported(b))))
        RegularSyncMetrics.clearLastImport()
        try
          importer ! BlockImporter.FetcherResponse(BlockFetcher.PickedBlocks(NonEmptyList.fromListUnsafe(batch)))
          awaitIdle(fetcher)

          val last: RegularSyncMetrics.LastImport = RegularSyncMetrics.lastImport.getOrElse(fail("no import recorded"))
          last.blocks shouldBe 3
          last.nanos should be >= 150.millis.toNanos
          // The duration is for the whole batch, so /metrics carries the batch's size beside it.
          RegularSyncMetrics.metrics.registry.find("regularsync.import.last.blocks.gauge").gauge().value() shouldBe 3d
        finally RegularSyncMetrics.clearLastImport()
        testKit.stop(importer)
    )

    "count a reorganisation's new branch, not its old one" taggedAs (UnitTest, SyncTest) in sync(
      new ImporterFixture:
        val fetcher: TestProbe = TestProbe("timed-reorg-fetcher")
        val importer: TypedActorRef[BlockImporter.Command] = spawnImporter(fetcher, "timed-reorg-importer")
        val block: Block = BlockHelpers.generateBlock(BlockHelpers.genesis)
        val newBranch: List[Block] = block :: BlockHelpers.generateChain(2, block)
        val oldBranch: List[Block] = List(BlockHelpers.generateBlock(BlockHelpers.genesis))
        setImportResult(
          block,
          IO.sleep(30.millis) *> IO.pure(
            ChainReorganised(oldBranch, newBranch, newBranch.map(b => ChainWeight.totalDifficultyOnly(b.number.value)))
          )
        )
        RegularSyncMetrics.clearLastImport()
        try
          importer ! BlockImporter.ImportNewBlock(block, peerId(1))
          awaitIdle(fetcher)

          val last: RegularSyncMetrics.LastImport = RegularSyncMetrics.lastImport.getOrElse(fail("no import recorded"))
          last.blocks shouldBe 3
          last.nanos should be >= 30.millis.toNanos
        finally RegularSyncMetrics.clearLastImport()
        testKit.stop(importer)
    )

    "set the last-import duration around the consensus call of a block it imports (C-7)" taggedAs (
      UnitTest,
      SyncTest
    ) in sync(
      new ImporterFixture:
        val fetcher: TestProbe = TestProbe("timed-import-fetcher")
        val importer: TypedActorRef[BlockImporter.Command] = spawnImporter(fetcher, "timed-import-importer")
        val block: Block = BlockHelpers.generateBlock(BlockHelpers.genesis)
        setImportResult(block, IO.sleep(120.millis) *> IO.pure(imported(block)))
        RegularSyncMetrics.clearLastImport()
        try
          importer ! BlockImporter.ImportNewBlock(block, peerId(1))
          awaitIdle(fetcher)

          val last: RegularSyncMetrics.LastImport = RegularSyncMetrics.lastImport.getOrElse(fail("no import recorded"))
          last.blocks shouldBe 1
          last.nanos should be >= 120.millis.toNanos
        finally RegularSyncMetrics.clearLastImport()
        testKit.stop(importer)
    )

    "leave the last-import duration as it was for a block that did not import" taggedAs (UnitTest, SyncTest) in sync(
      new ImporterFixture:
        val fetcher: TestProbe = TestProbe("untimed-import-fetcher")
        val importer: TypedActorRef[BlockImporter.Command] = spawnImporter(fetcher, "untimed-import-importer")
        val block: Block = BlockHelpers.generateBlock(BlockHelpers.genesis)
        setImportResult(block, IO.sleep(50.millis) *> IO.pure(DuplicateBlock))
        RegularSyncMetrics.recordLastImport(blocks = 7, nanos = 3_000L)
        try
          importer ! BlockImporter.ImportNewBlock(block, peerId(1))
          awaitIdle(fetcher)

          RegularSyncMetrics.lastImport shouldBe Some(RegularSyncMetrics.LastImport(7, 3_000L))
        finally RegularSyncMetrics.clearLastImport()
        testKit.stop(importer)
    )
  }
