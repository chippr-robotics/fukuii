package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdate
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.testing.Tags.*

/** The commit that ends a sync from scratch on the unified layout (spec 018 C5a part 5; T201's storage half, B-243):
  * the rows the sync wrote become the state of the pivot in one synced batch, with nothing copied.
  *
  * What is in the batch and that it is one is pinned on a store that records its writes. That it is on the disk with
  * the rows written before it is RocksDB's, and is for the kill test on a real one.
  */
class HandoffCommitSpec extends AnyFlatSpec with Matchers:
  import PathStateStorage.*
  import PathStateTestKit.*

  /** A store that records its batches, synced or not, by the keys of the application state they put and remove. */
  final private class Recording extends EphemDataSource(Map.empty):
    @volatile var writes: Vector[String] = Vector.empty
    @volatile var failing: Boolean = false
    private def keysOf(updates: Seq[DataUpdate]): String =
      updates
        .collect {
          case u: DataSourceUpdate if u.namespace == Namespaces.AppStateNamespace =>
            u.toUpsert.map(put => "+" + new String(put._1.toArray)) ++ u.toRemove.map(key =>
              "-" + new String(key.toArray)
            )
          case u: DataSourceUpdateOptimized if u.namespace == Namespaces.AppStateNamespace =>
            u.toUpsert.map(put => "+" + new String(put._1)) ++ u.toRemove.map(key => "-" + new String(key))
        }
        .flatten
        .sorted
        .mkString(",")
    override def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
      writes :+= s"batch[${keysOf(dataSourceUpdates)}]"
      super.update(dataSourceUpdates)
    override def updateSync(dataSourceUpdates: Seq[DataUpdate]): Unit =
      if failing then throw new IllegalStateException("the store failed on the synced batch")
      writes :+= s"synced[${keysOf(dataSourceUpdates)}]"
      super.update(dataSourceUpdates)

  /** A datadir of the unified layout at the end of a sync: the rows of the pivot's state as the sync wrote them, the
    * layout's stamp from the genesis this datadir was created with, and no anchor.
    */
  private class Synced(stamped: Boolean = true):
    val source = new Recording
    val hashed = new HashedState
    val appState = new AppStateStorage(source)
    writeState(new PathNodeStorage(source), hashed.positions())
    if stamped then appState.putStateLayout().commit()
    val pivot: BlockRef = block(5000, hashed.root)
    val state = new PathStateStorage(new PathNodeStorage(source), appState)
    source.writes = Vector.empty

    /** What the sync's finalization writes with the anchor: the best block. */
    def best: Some[DataSourceBatchUpdate] = Some(appState.putBestBlockInfo(BlockInfo(pivot.hash, pivot.number)))

  "The commit of a hand-off" should "make the rows the state of the pivot, in one synced batch with the caller's own writes" taggedAs UnitTest in {
    val s = new Synced
    s.state.anchor shouldBe None

    s.state.commitHandoff(s.pivot, s.best)

    withClue("one batch, synced, and nothing else: no row is written, no record is made: ") {
      s.source.writes shouldBe Vector(
        "synced[+BestBlockHash,+BestBlockNumber,+SnapSyncDone,+state.anchor,+state.origin]"
      )
    }
    s.state.anchor shouldBe Some(s.pivot)
    s.appState.getStateAnchor() shouldBe Some(s.pivot)
    withClue("the block the state began at: no move of the head goes below it (B-243): ") {
      s.appState.getStateOrigin() shouldBe Some(s.pivot)
    }
    s.appState.isSnapSyncDone() shouldBe true
    s.appState.getBestBlockInfo() shouldBe BlockInfo(s.pivot.hash, s.pivot.number)
    s.appState.getChangesetPrunedTo() shouldBe None
    s.state.stateAvailable(s.pivot) shouldBe true
  }

  it should "leave a state that is read where it lies, and that the next block is committed on in place" taggedAs UnitTest in {
    val s = new Synced
    s.state.commitHandoff(s.pivot, s.best)

    val world = worldOver(s.state.readOnly(s.pivot), s.pivot.stateRoot, s.hashed.codes)
    world.getAccount(dave) shouldBe Some(Account(7, 7))
    world.getStorage(bob).load(BigInt(manySlots.head._1)) shouldBe BigInt(manySlots.head._2)

    val staged = s.state.stage(s.pivot)
    val after = com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
      .persistState(
        worldOver(staged.storage, s.pivot.stateRoot, s.hashed.codes).saveAccount(dave, Account(8, UInt256(9)))
      )
      .stateRootHash
    after shouldBe s.hashed.change(s.hashed.root)(_.saveAccount(dave, Account(8, UInt256(9))))
    val next = BlockRef(ByteString(Array.fill[Byte](32)(6)), s.pivot.number + 1, after)
    s.state.commitCanonical(Some(staged), next, None) shouldBe a[CommitOutcome.Applied]
    s.state.anchor shouldBe Some(next)
    withClue("the pivot's state is reached again by the record of the block above it: ") {
      s.state.stateAvailable(s.pivot) shouldBe true
    }
  }

  // The clear's deletes take the flat copies of another state with them, which no read checks by hash: a sync that
  // ran over a clear that was not finished does not end with a state.
  it should "refuse while the mark of a clear is set, and hand off once the clear is finished" taggedAs UnitTest in {
    val s = new Synced
    s.appState.stateClearBatch(ByteString(Array.fill[Byte](32)(1)), 1L, None).commit()
    s.appState.isStateResetPending() shouldBe true

    val thrown = the[IllegalStateException] thrownBy s.state.commitHandoff(s.pivot, s.best)

    thrown.getMessage should include("the mark of a clear that was not finished (state.reset-pending)")
    s.state.anchor shouldBe None
    s.appState.isSnapSyncDone() shouldBe false
    s.appState.isStateResetPending() shouldBe true
  }

  it should "refuse a datadir that holds a finalized block, or a sync that says it is done" taggedAs UnitTest in {
    val finalized = new Synced
    finalized.appState.putStateFinalized(FinalizedBlock(4000, ByteString(Array.fill[Byte](32)(4)))).commit()
    (the[IllegalStateException] thrownBy finalized.state.commitHandoff(finalized.pivot, None)).getMessage should
      include("a finalized block (4000)")
    finalized.state.anchor shouldBe None

    val done = new Synced
    done.appState.snapSyncDone().commit()
    (the[IllegalStateException] thrownBy done.state.commitHandoff(done.pivot, None)).getMessage should
      include("a sync that says it is done")
    done.state.anchor shouldBe None
  }

  it should "change nothing, on disk or in memory, when the batch fails" taggedAs UnitTest in {
    val s = new Synced
    s.source.failing = true

    an[IllegalStateException] should be thrownBy s.state.commitHandoff(s.pivot, s.best)

    s.source.writes shouldBe Vector.empty
    s.state.anchor shouldBe None
    s.appState.getStateAnchor() shouldBe None
    s.appState.isSnapSyncDone() shouldBe false
    s.appState.getBestBlockInfo() shouldBe BlockInfo(ByteString.empty, 0)

    // and it can be made again
    s.source.failing = false
    s.state.commitHandoff(s.pivot, s.best)
    s.state.anchor shouldBe Some(s.pivot)
  }

  it should "refuse a datadir that holds a state already" taggedAs UnitTest in {
    val s = new Synced
    s.state.commitHandoff(s.pivot, s.best)
    s.source.writes = Vector.empty

    val thrown = the[IllegalStateException] thrownBy s.state.commitHandoff(block(5001, s.hashed.root), None)

    thrown.getMessage should include("already holds the state of block 5000")
    s.source.writes shouldBe Vector.empty
    s.state.anchor shouldBe Some(s.pivot)
  }

  it should "refuse a datadir with an anchor on disk that this store was not built over" taggedAs UnitTest in {
    val s = new Synced
    s.appState.putStateAnchor(block(4000, s.hashed.root)).commit()
    val thrown = the[IllegalStateException] thrownBy s.state.commitHandoff(s.pivot, s.best)
    thrown.getMessage should include("a state anchor on disk")
    s.appState.isSnapSyncDone() shouldBe false
  }

  it should "refuse a datadir that holds the block a state began at, or a count of pruned records" taggedAs UnitTest in {
    val withOrigin = new Synced
    withOrigin.appState.putStateOrigin(block(4000, withOrigin.hashed.root)).commit()
    (the[IllegalStateException] thrownBy withOrigin.state.commitHandoff(withOrigin.pivot, None)).getMessage should
      include("a state that began at block 4000")

    val withPruned = new Synced
    withPruned.appState.putChangesetPrunedTo(12).commit()
    (the[IllegalStateException] thrownBy withPruned.state.commitHandoff(withPruned.pivot, None)).getMessage should
      include("a count of pruned changesets (12)")
  }

  it should "refuse a datadir that holds records of changes: they are of a state that is not this one" taggedAs UnitTest in {
    val s = new Synced
    s.source.update(
      Seq(
        DataSourceUpdateOptimized(
          Namespaces.StateChangesetNamespace,
          Nil,
          Seq(Array.fill[Byte](33)(1) -> Array[Byte](1))
        )
      )
    )
    val thrown = the[IllegalStateException] thrownBy s.state.commitHandoff(s.pivot, s.best)
    thrown.getMessage should include("changeset records")
    s.state.anchor shouldBe None
    s.appState.isSnapSyncDone() shouldBe false
  }

  it should "refuse a datadir that is not stamped as this layout's" taggedAs UnitTest in {
    val s = new Synced(stamped = false)
    val thrown = the[IllegalStateException] thrownBy s.state.commitHandoff(s.pivot, s.best)
    thrown.getMessage should include("not stamped as the unified layout's (no stamp)")
    s.state.anchor shouldBe None
    s.appState.isSnapSyncDone() shouldBe false
  }

  it should "refuse a pivot whose state root is not the node at the root of the account trie" taggedAs UnitTest in {
    val s = new Synced
    val other = block(5000, ByteString(Array.fill[Byte](32)(3)))

    val thrown = the[IllegalStateException] thrownBy s.state.commitHandoff(other, None)

    thrown.getMessage should include("is not the node its state root names")
    s.state.anchor shouldBe None
    s.appState.getStateAnchor() shouldBe None
    s.source.writes shouldBe Vector.empty
  }

  it should "refuse a pivot with a state when the rows hold none" taggedAs UnitTest in {
    val source = new Recording
    val appState = new AppStateStorage(source)
    appState.putStateLayout().commit()
    val state = new PathStateStorage(new PathNodeStorage(source), appState)
    an[IllegalStateException] should be thrownBy state.commitHandoff(block(7, new HashedState().root), None)
    state.anchor shouldBe None
  }

  it should "refuse a pivot whose state is empty over rows that are there: they are another state's" taggedAs UnitTest in {
    val s = new Synced
    val thrown = the[IllegalStateException] thrownBy s.state.commitHandoff(block(5000, EmptyRoot), None)
    thrown.getMessage should include("there are rows")
    s.state.anchor shouldBe None
  }

  it should "hand off at a pivot whose state is empty, over rows that hold none" taggedAs UnitTest in {
    val source = new Recording
    val appState = new AppStateStorage(source)
    appState.putStateLayout().commit()
    val state = new PathStateStorage(new PathNodeStorage(source), appState)
    val pivot = block(7, EmptyRoot)
    state.commitHandoff(pivot, None)
    state.anchor shouldBe Some(pivot)
  }

  it should "refuse the genesis: a sync hands off above it" taggedAs UnitTest in {
    val s = new Synced
    an[IllegalArgumentException] should be thrownBy s.state.commitHandoff(block(0, s.hashed.root), None)
    s.state.anchor shouldBe None
  }

  it should "refuse updates of another data source: a block and its state are one batch" taggedAs UnitTest in {
    val s = new Synced
    val elsewhere = new AppStateStorage(EphemDataSource()).putBestBlockInfo(BlockInfo(s.pivot.hash, s.pivot.number))
    an[IllegalArgumentException] should be thrownBy s.state.commitHandoff(s.pivot, Some(elsewhere))
    s.state.anchor shouldBe None
  }

  it should "refuse on a store that failed closed" taggedAs UnitTest in {
    val s = new Synced
    s.state.failClosed("a test")
    withClue("the fault is recorded, synced, and nothing else is written (B-252): ")(
      s.source.writes shouldBe Vector(s"synced[+${StateFaultMark.Key}]")
    )
    a[RuntimeException] should be thrownBy s.state.commitHandoff(s.pivot, s.best)
    s.state.anchor shouldBe None
    s.source.writes shouldBe Vector(s"synced[+${StateFaultMark.Key}]")
  }
