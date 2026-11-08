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
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.blockchain.sync.PeersClient
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.branch.Branch
import com.chipprbots.ethereum.domain.branch.EmptyBranch
import com.chipprbots.ethereum.ledger.*
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders as ETHGetBlockHeaders
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** B-007 (#1683, CHASE A3): a block whose parent this node does not hold is a gap on OUR side, not an offence of the
  * peer that sent it. On a reorging PoW chain an honest peer announces a child of a block we have not seen (a competing
  * tip), and right after SNAP hand-off a fetched batch can start above the best block. Both used to blacklist the peer:
  * T062 (Mordor, 24 h) blacklisted 33, with five "Unknown branch" reorgs resolving normally in the same run.
  *
  * What is asked here, as core-geth's block fetcher does (`eth/fetcher/block_fetcher.go`, `importBlocks`: "Unknown
  * parent of propagated block" is logged and the block dropped, the peer dropped only for a header that fails
  * verification):
  *   - the branch is fetched, and nobody is blamed;
  *   - a peer that keeps announcing blocks whose parent never arrives is still dealt with, after a bounded count;
  *   - a peer that serves a block that is invalid is blacklisted as before.
  */
class UnknownParentImportSpec extends WordSpecBase with Matchers with AsyncMockFactory with RegularSyncFixtures:

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

    /** An importer of its own, outside `regularSync`, driving `fetcher`. */
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
      fetcher.fishForSpecificMessage(10.seconds) { case _: BlockFetcher.PickBlocks => () }

    /** A block whose parent nobody here has: a child of a block that is not in this node's chain. */
    def orphan(): Block = BlockHelpers.generateBlock(BlockHelpers.generateBlock(BlockHelpers.genesis))

    /** What the fetcher is told about `block`, announced by `peer`: the one message that answers it. */
    def announce(
        importer: TypedActorRef[BlockImporter.Command],
        fetcher: TestProbe,
        block: Block,
        peer: PeerId
    ): BlockFetcher.FetchCommand =
      importer ! BlockImporter.ImportNewBlock(block, peer)
      val answer = fetcher.fishForSpecificMessage(10.seconds) {
        case m: BlockFetcher.InvalidateBlocksFrom => m
        case m: BlockFetcher.BlockImportFailed    => m
      }
      awaitIdle(fetcher)
      answer

    def imported(block: Block): BlockImportResult =
      BlockImportedToTop(List(BlockData(block, Nil, ChainWeight.totalDifficultyOnly(block.number.value))))

    def asBackOffWithoutBlame(answer: BlockFetcher.FetchCommand, block: Block): Assertion =
      answer match
        case m: BlockFetcher.InvalidateBlocksFrom =>
          m.fromBlock shouldBe block.number.value
          m.toBlacklist shouldBe None
        case other => fail(s"expected the fetcher to go back with no one blamed, got $other")

  "A block announced by a peer (NewBlock)" should {

    // The reorg tip: a competing branch's child, whose parent this node never saw.
    "fetch the branch and blame no peer when its parent is not here" taggedAs (UnitTest, SyncTest) in sync(
      new ImporterFixture:
        val fetcher: TestProbe = TestProbe("announce-fetcher")
        val importer: TypedActorRef[BlockImporter.Command] = spawnImporter(fetcher, "orphan-announce-importer")
        val block: Block = orphan()
        setImportResult(block, IO.pure(UnknownParent))

        val answer: BlockFetcher.FetchCommand = announce(importer, fetcher, block, peerId(1))

        asBackOffWithoutBlame(answer, block)
        fetcher.receiveWhile(500.millis) { case m => m }.collect { case b: BlockFetcher.BlockImportFailed =>
          b
        } shouldBe empty
        testKit.stop(importer)
    )

    "blacklist the peer that announces a block that is invalid, as before" taggedAs (UnitTest, SyncTest) in sync(
      new ImporterFixture:
        val fetcher: TestProbe = TestProbe("invalid-announce-fetcher")
        val importer: TypedActorRef[BlockImporter.Command] = spawnImporter(fetcher, "invalid-announce-importer")
        val block: Block = orphan()
        setImportResult(block, IO.pure(BlockImportFailed("INVALID_GAS_LIMIT: header gas limit out of bounds")))

        announce(importer, fetcher, block, peerId(1)) shouldBe BlockFetcher.BlockImportFailed(
          block.number.value,
          BlacklistReason.BlockImportError("INVALID_GAS_LIMIT: header gas limit out of bounds")
        )
        testKit.stop(importer)
    )

    "blacklist a peer only once it has announced OrphanAnnounceStrikeThreshold blocks in a row whose parent never arrived" taggedAs (
      UnitTest,
      SyncTest
    ) in sync(
      new ImporterFixture:
        val fetcher: TestProbe = TestProbe("strikes-fetcher")
        val importer: TypedActorRef[BlockImporter.Command] = spawnImporter(fetcher, "orphan-strikes-importer")
        val blocks: List[Block] = List.fill(BlockImporter.OrphanAnnounceStrikeThreshold)(orphan())
        blocks.foreach(b => setImportResult(b, IO.pure(UnknownParent)))

        val answers: List[(Block, BlockFetcher.FetchCommand)] =
          blocks.map(b => b -> announce(importer, fetcher, b, peerId(1)))

        answers.init.foreach { case (b, answer) => asBackOffWithoutBlame(answer, b) }
        val (last, answer) = answers.last
        answer shouldBe BlockFetcher.BlockImportFailed(
          last.number.value,
          BlacklistReason.BlockImportError(
            s"announced ${BlockImporter.OrphanAnnounceStrikeThreshold} blocks in a row whose parent never arrived, " +
              s"the last ${last.header.hashAsHexString}"
          )
        )
        testKit.stop(importer)
    )

    "count a peer's strikes from zero again after a block of its imports" taggedAs (UnitTest, SyncTest) in sync(
      new ImporterFixture:
        val fetcher: TestProbe = TestProbe("cleared-fetcher")
        val importer: TypedActorRef[BlockImporter.Command] = spawnImporter(fetcher, "orphan-cleared-importer")
        val peer: PeerId = peerId(1)
        val before: List[Block] = List.fill(BlockImporter.OrphanAnnounceStrikeThreshold - 1)(orphan())
        val good: Block = BlockHelpers.generateBlock(BlockHelpers.genesis)
        val after: List[Block] = List.fill(BlockImporter.OrphanAnnounceStrikeThreshold - 1)(orphan())
        (before ++ after).foreach(b => setImportResult(b, IO.pure(UnknownParent)))
        setImportResult(good, IO.pure(imported(good)))

        before.foreach(b => asBackOffWithoutBlame(announce(importer, fetcher, b, peer), b))
        importer ! BlockImporter.ImportNewBlock(good, peer)
        awaitIdle(fetcher)
        // Without the block that imported, the next strike would be the threshold's.
        after.foreach(b => asBackOffWithoutBlame(announce(importer, fetcher, b, peer), b))
        testKit.stop(importer)
    )

    "count each peer's strikes apart" taggedAs (UnitTest, SyncTest) in sync(
      new ImporterFixture:
        val fetcher: TestProbe = TestProbe("per-peer-fetcher")
        val importer: TypedActorRef[BlockImporter.Command] = spawnImporter(fetcher, "orphan-per-peer-importer")
        val fromFirst: List[Block] = List.fill(BlockImporter.OrphanAnnounceStrikeThreshold - 1)(orphan())
        val fromSecond: Block = orphan()
        (fromSecond :: fromFirst).foreach(b => setImportResult(b, IO.pure(UnknownParent)))

        fromFirst.foreach(b => asBackOffWithoutBlame(announce(importer, fetcher, b, peerId(1)), b))
        // One strike short of the threshold for the first peer; the second peer's first strike is its own.
        asBackOffWithoutBlame(announce(importer, fetcher, fromSecond, peerId(2)), fromSecond)
        testKit.stop(importer)
    )

    // The whole node: a real fetcher, which looks up who provided the block. OnTopFixture is following the head.
    "end with the fetcher fetching the parent block and no peer blacklisted" taggedAs (UnitTest, SyncTest) in sync(
      new OnTopFixture:
        override def newBlockResult: BlockImportResult = UnknownParent

        goToTop()
        sendNewBlock()
        awaitCond(importedNewBlock)

        // The announced block is `testBlocks.last`'s child; its parent is fetched again, so the branch can be resolved.
        peersClient.fishForSpecificMessage(max = 10.seconds) {
          case b: PeersClient.BlacklistPeer => fail(s"a peer was blamed for a block whose parent we lack: $b")
          case PeersClient.Request(ETHGetBlockHeaders(_, Left(from), _, _, _), _, _, _)
              if from == testBlocks.last.number.value =>
            true
        }
        peersClient.receiveWhile(1.second) { case m => m }.collect { case b: PeersClient.BlacklistPeer =>
          b
        } shouldBe empty
    )

    "end with the peer that announced an invalid block blacklisted" taggedAs (UnitTest, SyncTest) in sync(
      new OnTopFixture:
        override def newBlockResult: BlockImportResult = BlockImportFailed("INVALID_GAS_LIMIT: out of bounds")

        goToTop()
        sendNewBlock()

        fishForBlacklistPeer(defaultPeer).reason shouldBe BlacklistReason.BlockImportError(
          "INVALID_GAS_LIMIT: out of bounds"
        )
    )
  }

  "A batch the fetcher picked" should {

    "fetch the branch and blame no peer when its parent is not here" taggedAs (UnitTest, SyncTest) in sync(
      new ImporterFixture:
        override lazy val branchResolution: BranchResolution = stub[BranchResolution]
        branchResolution.resolveBranch.when(*).returns(NewBetterBranch(Nil))

        val fetcher: TestProbe = TestProbe("batch-fetcher")
        val importer: TypedActorRef[BlockImporter.Command] = spawnImporter(fetcher, "orphan-batch-importer")
        val block: Block = orphan()
        setImportResult(block, IO.pure(UnknownParent))

        importer ! BlockImporter.FetcherResponse(BlockFetcher.PickedBlocks(NonEmptyList.one(block)))

        val back: BlockFetcher.InvalidateBlocksFrom =
          fetcher.fishForSpecificMessage(10.seconds) { case m: BlockFetcher.InvalidateBlocksFrom => m }
        back.fromBlock shouldBe block.number.value
        back.toBlacklist shouldBe None
        testKit.stop(importer)
    )

    "still blame the peer that served a batch that fails as an invalid block" taggedAs (UnitTest, SyncTest) in sync(
      new ImporterFixture:
        override lazy val branchResolution: BranchResolution = stub[BranchResolution]
        branchResolution.resolveBranch.when(*).returns(NewBetterBranch(Nil))

        val fetcher: TestProbe = TestProbe("invalid-batch-fetcher")
        val importer: TypedActorRef[BlockImporter.Command] = spawnImporter(fetcher, "invalid-batch-importer")
        val block: Block = orphan()
        setImportResult(block, IO.pure(BlockImportFailed("INVALID_GAS_LIMIT: header gas limit out of bounds")))

        importer ! BlockImporter.FetcherResponse(BlockFetcher.PickedBlocks(NonEmptyList.one(block)))

        val back: BlockFetcher.InvalidateBlocksFrom =
          fetcher.fishForSpecificMessage(10.seconds) { case m: BlockFetcher.InvalidateBlocksFrom => m }
        back.fromBlock shouldBe block.number.value
        back.toBlacklist shouldBe Some(block.number.value)
        testKit.stop(importer)
    )
  }

  // #1683: right after SNAP hand-off the best block is the pivot, and the pivot floor skips branch resolution, for no
  // header below the pivot is stored. That is right for a batch that continues the pivot. A batch that starts above it
  // does not: the blocks between are not here, and importing it was a failed import, a blacklisting, and a walk back one
  // block at a time (17292670 to 17292665 on Mordor).
  "A batch fetched right after SNAP hand-off, at the pivot floor" should {

    class PivotFixture extends ImporterFixture:
      val chain: List[Block] = BlockHelpers.generateChain(6, BlockHelpers.genesis) // blocks 1 to 6
      val pivot: Block = chain(1) // block 2: the best block, and the pivot
      override lazy val blockchainReader: BlockchainReader = stub[BlockchainReader]
      (() => blockchainReader.getBestBlockNumber).when().returns(pivot.number.value)
      (() => blockchainReader.getSnapSyncPivotBlock).when().returns(Some(pivot.number.value))
      (blockchainReader.getCanonicalHashByNumber(_: BigInt)).when(*).returns(None)
      (() => blockchainReader.getBestBlockHeader).when().returns(Some(pivot.header))
      (() => blockchainReader.getBestBranch).when().returns(EmptyBranch)
      // Only the pivot is stored: it is in the chain, no other block is.
      (blockchainReader.isInChain(_: Branch, _: BlockHash)).when(*, pivot.hash).returns(true)
      override lazy val branchResolution: BranchResolution = stub[BranchResolution]
      // No header below the pivot, so a batch never links to a stored parent through the chain.
      branchResolution.resolveBranch.when(*).returns(UnknownBranch)

    "fetch the blocks between the pivot and the batch, and import nothing, when the batch does not continue the pivot" taggedAs (
      UnitTest,
      SyncTest
    ) in sync(
      new PivotFixture:
        val fetcher: TestProbe = TestProbe("gap-fetcher")
        val importer: TypedActorRef[BlockImporter.Command] = spawnImporter(fetcher, "gap-importer")
        val gap: Block = chain(5) // block 6; its parent, block 5, is not here

        importer ! BlockImporter.FetcherResponse(BlockFetcher.PickedBlocks(NonEmptyList.one(gap)))

        val back: BlockFetcher.InvalidateBlocksFrom =
          fetcher.fishForSpecificMessage(10.seconds) { case m: BlockFetcher.InvalidateBlocksFrom => m }
        back.fromBlock shouldBe pivot.number.value + 1
        back.toBlacklist shouldBe None
        didTryToImportBlock(gap) shouldBe false
        testKit.stop(importer)
    )

    "still import the batch directly when it continues the pivot" taggedAs (UnitTest, SyncTest) in sync(
      new PivotFixture:
        val fetcher: TestProbe = TestProbe("continuing-fetcher")
        val importer: TypedActorRef[BlockImporter.Command] = spawnImporter(fetcher, "continuing-importer")
        val continuing: Block = chain(2) // block 3, the pivot's child

        importer ! BlockImporter.FetcherResponse(BlockFetcher.PickedBlocks(NonEmptyList.one(continuing)))

        awaitCond(didTryToImportBlock(continuing), 10.seconds)
        testKit.stop(importer)
    )
  }

  "BlockImporter.OrphanAnnounceStrikes" should {

    "count a peer's strikes, and forget them when cleared" taggedAs (UnitTest, SyncTest) in {
      val strikes = new BlockImporter.OrphanAnnounceStrikes
      val peer = PeerId("p")
      strikes.strike(peer) shouldBe 1
      strikes.strike(peer) shouldBe 2
      strikes.strike(PeerId("q")) shouldBe 1
      strikes.clear(peer)
      strikes.strike(peer) shouldBe 1
      strikes.strike(PeerId("q")) shouldBe 2
      Future.successful(succeed)
    }

    "stay bounded: past its capacity, it starts over" taggedAs (UnitTest, SyncTest) in {
      val strikes = new BlockImporter.OrphanAnnounceStrikes(capacity = 3)
      (1 to 3).foreach(n => strikes.strike(PeerId(s"p$n")) shouldBe 1)
      strikes.size shouldBe 3
      strikes.strike(PeerId("p4")) shouldBe 1
      strikes.size should be <= 3
      // A tracked peer is counted on, at capacity.
      strikes.strike(PeerId("p4")) shouldBe 2
      Future.successful(succeed)
    }
  }
