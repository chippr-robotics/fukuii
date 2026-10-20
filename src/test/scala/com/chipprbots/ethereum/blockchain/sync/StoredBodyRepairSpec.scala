package com.chipprbots.ethereum.blockchain.sync

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.testing.Tags.*

/** CQ-020-07 start-up repair: an existing node holds its SNAP pivot as `BlockBody.empty` (and a Shanghai genesis with a
  * 3-item body). The repair must take the wrong body out of service and get the real one fetched through the validating
  * backfill path — without inventing one, and without starting a whole-chain download.
  */
class StoredBodyRepairSpec extends AnyFlatSpec with Matchers:
  import StoredBodyCheckSpec.*

  class Fixture extends EphemBlockchainTestSetup:
    val appState = storagesInstance.storages.appStateStorage
    val pivot: BlockHeader = withdrawalsPivot
    val pivotNumber: BigInt = pivot.number.value

    def storeGenesis(header: BlockHeader, body: BlockBody): Unit =
      blockchainWriter.save(Block(header, body), Nil, ChainWeight.totalDifficultyOnly(1), saveAsBestBlock = true)

    /** The state the pre-fix finalization left: header + mapping + placeholder body, SNAP done, backfill complete. */
    def corruptedNode(headerCursor: BigInt = withdrawalsPivot.number.value): Unit =
      storeGenesis(Fixtures.Blocks.Genesis.header, Fixtures.Blocks.Genesis.body)
      blockchainWriter.storeBlock(Block(pivot, BlockBody.empty)).commit() // exactly what SnapFinalization:245 wrote
      appState
        .snapSyncDone()
        .and(appState.putSnapSyncPivotBlock(pivotNumber))
        .and(appState.putBackfillBestHeader(headerCursor))
        .and(appState.putBackfillBestBody(pivotNumber))
        .and(appState.putBackfillBestReceipt(pivotNumber))
        .and(appState.removeBackfillTarget()) // what ChainDownloader.checkCompletion leaves
        .commit()

    def repair(): Seq[StoredBodyRepair.Outcome] = StoredBodyRepair.run(blockchainReader, blockchainWriter, appState)

  "StoredBodyRepair" should "remove the placeholder pivot body and arm the backfill resumer for that one block" taggedAs
    UnitTest in new Fixture:
      corruptedNode()
      appState.needsBackfillResume() shouldBe false

      val outcomes = repair()

      outcomes.map(o => (o.label, o.number, o.refetchArmed)) shouldBe Seq(("SNAP pivot", pivotNumber, true))
      outcomes.head.action shouldBe a[StoredBodyCheck.Action.Remove]
      blockchainReader.getBlockBodyByHash(pivot.hash) shouldBe None // no wrong body served any more
      blockchainReader.getBlockHeaderByNumber(pivotNumber).map(_.hash) shouldBe Some(pivot.hash) // mapping kept
      appState.getBackfillTarget() shouldBe pivotNumber
      appState.getBackfillBestBody() shouldBe pivotNumber - 1
      appState.getBackfillBestHeader() shouldBe pivotNumber // headers are not re-downloaded
      appState.needsBackfillResume() shouldBe true // SyncController.maybeStartBackfillResume spawns the resumer

  it should "be idempotent: a second start finds nothing to do while the refetch is pending" taggedAs UnitTest in new Fixture:
    corruptedNode()
    repair()
    repair() shouldBe empty
    appState.getBackfillTarget() shouldBe pivotNumber
    appState.getBackfillBestBody() shouldBe pivotNumber - 1

  it should "do nothing once the real body is stored" taggedAs UnitTest in new Fixture:
    corruptedNode()
    repair()
    blockchainWriter.storeBlockBody(pivot.hash, withdrawalsBody).commit() // what the backfill does once fetched
    repair() shouldBe empty
    blockchainReader.getBlockBodyByHash(pivot.hash) shouldBe Some(withdrawalsBody)

  it should "remove the wrong body but not arm a whole-chain download when the header backfill never reached the pivot" taggedAs
    UnitTest in new Fixture:
      corruptedNode(headerCursor = 0) // chain download disabled
      val outcomes = repair()
      outcomes.map(_.refetchArmed) shouldBe Seq(false)
      blockchainReader.getBlockBodyByHash(pivot.hash) shouldBe None
      appState.needsBackfillResume() shouldBe false

  it should "rewrite, not refetch, a pivot whose header commits to an empty body" taggedAs UnitTest in new Fixture:
    storeGenesis(Fixtures.Blocks.Genesis.header, Fixtures.Blocks.Genesis.body)
    blockchainWriter.storeBlock(Block(emptyShanghai, BlockBody.empty)).commit()
    appState.snapSyncDone().and(appState.putSnapSyncPivotBlock(emptyShanghai.number.value)).commit()

    repair().map(o => (o.label, o.refetchArmed)) shouldBe Seq(("SNAP pivot", false))
    blockchainReader.getBlockBodyByHash(emptyShanghai.hash) shouldBe Some(BlockBody(Nil, Nil, Some(Nil)))
    appState.needsBackfillResume() shouldBe false

  it should "rewrite a Shanghai genesis stored with a 3-item body" taggedAs UnitTest in new Fixture:
    val genesis = emptyShanghai.copy(number = com.chipprbots.ethereum.domain.BlockNumber(0))
    storeGenesis(genesis, BlockBody(Nil, Nil)) // what GenesisDataLoader wrote before the fix

    repair().map(_.label) shouldBe Seq("genesis")
    blockchainReader.getBlockBodyByHash(genesis.hash) shouldBe Some(BlockBody(Nil, Nil, Some(Nil)))

  it should "leave an ETC-shaped node untouched" taggedAs UnitTest in new Fixture:
    storeGenesis(Fixtures.Blocks.Genesis.header, Fixtures.Blocks.Genesis.body)
    val etcPivot = Fixtures.Blocks.Block3125369
    blockchainWriter.storeBlock(Block(etcPivot.header, etcPivot.body)).commit()
    appState.snapSyncDone().and(appState.putSnapSyncPivotBlock(etcPivot.header.number.value)).commit()

    repair() shouldBe empty
    blockchainReader.getBlockBodyByHash(etcPivot.header.hash) shouldBe Some(etcPivot.body)

  it should "not touch the pivot before SNAP is done" taggedAs UnitTest in new Fixture:
    corruptedNode()
    appState.clearSnapSyncDone().commit()
    repair() shouldBe empty
    blockchainReader.getBlockBodyByHash(pivot.hash) shouldBe Some(BlockBody.empty)
