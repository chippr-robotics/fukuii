package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C3c: the destruction of the state a datadir holds, for test mode's new genesis
  * ([[PathStateStorage.destroyStateForNewGenesis]]).
  *
  * A test harness sets a new chain on one datadir before each test. On the unified layout a genesis is committed only
  * to a datadir that holds no state, so the state the test before left has to go first: every row, every changeset
  * record, the count of pruned records, the layout stamp and the anchor, in one batch. Nothing but test mode may do it,
  * and a reader that held a view of the state that went must be told its view is stale, never that the state has no
  * rows.
  */
class StateDestructionSpec extends AnyFlatSpec with Matchers:

  private val owner = ByteString(Array.fill[Byte](32)(0x0a))
  private val held = BlockRef(ByteString(Array.fill[Byte](32)(7)), 5, ByteString(Array.fill[Byte](32)(9)))
  private val aNode = Array.fill[Byte](40)(1)

  /** A datadir with a state on it: rows of the account trie and of one storage trie, a changeset record and its index
    * entry, a count of pruned records, the stamp and the anchor. And the store over it.
    */
  private def withState(
      source: DataSource,
      testMode: Boolean = true,
      maxKeys: Long = PathStateStorage.MaxKeysDestroyed
  ): PathStateStorage =
    val rows = new PathNodeStorage(source)
    (0 until 12).foreach(i => rows.writeAccountNode(Array[Byte]((i % 16).toByte, (i / 16).toByte), aNode))
    (0 until 7).foreach(i => rows.writeStorageNode(owner, Array[Byte](i.toByte), aNode))
    source.update(
      Seq(
        DataSourceUpdate(
          Namespaces.StateChangesetNamespace,
          Seq.empty,
          Seq(
            IndexedSeq.fill[Byte](32)(4) -> IndexedSeq[Byte](1, 2, 3),
            IndexedSeq[Byte](0, 0, 0, 5) -> IndexedSeq.fill[Byte](32)(4)
          )
        )
      )
    )
    val appState = new AppStateStorage(source)
    appState
      .putStateAnchor(held)
      .and(appState.putStateOrigin(held))
      .and(appState.putStateLayout())
      .and(appState.putChangesetPrunedTo(3))
      .commit()
    new PathStateStorage(
      rows,
      appState,
      settings = PathStateStorage.CommitSettings(allowsStateDestruction = testMode, maxKeysDestroyed = maxKeys)
    )

  private def stateOf(source: DataSource) =
    val appState = new AppStateStorage(source)
    (
      PathStateTestKit.allRows(new PathNodeStorage(source)),
      new ChangesetStorage(source).isEmpty,
      appState.getStateAnchor(),
      appState.getStateOrigin(),
      appState.getStateLayout(),
      appState.getChangesetPrunedTo()
    )

  private val nothing = ((Map.empty, Map.empty), true, None, None, None, None)

  /** The in-memory source, which says what was written to it and how, and can be made to fail a write. (A wrapper will
    * not do here: the storages ask a source whether it is the in-memory one, to know the shape of its keys.)
    */
  final private class Recording extends EphemDataSource(Map.empty):
    import com.chipprbots.ethereum.db.dataSource.DataUpdate
    @volatile var writes: Vector[String] = Vector.empty
    @volatile var failing = false
    override def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
      writes :+= "update"
      if failing then throw new java.io.IOException("the disk is full")
      super.update(dataSourceUpdates)
    override def updateSync(dataSourceUpdates: Seq[DataUpdate]): Unit =
      writes :+= "updateSync"
      if failing then throw new java.io.IOException("the disk is full")
      super.update(dataSourceUpdates)

  "The destruction of a datadir's state" should "delete every row, every record, the count, the stamp and the anchor, in one synced batch" taggedAs
    UnitTest in {
      val watched = new Recording
      val store = withState(watched)
      store.anchor shouldBe Some(held)
      stateOf(watched) should not be nothing
      val before = watched.writes.size
      // 12 account rows, 7 storage rows, a record and an index entry
      store.destroyStateForNewGenesis() shouldBe 21
      watched.writes.drop(before) shouldBe Vector("updateSync")
      stateOf(watched) shouldBe nothing
      store.anchor shouldBe None
      store.lock.holder shouldBe None
      // and a genesis can be committed to it again
      val genesis = BlockRef(ByteString(Array.fill[Byte](32)(1)), 0, ByteString(MerklePatriciaTrie.EmptyRootHash))
      store.commitGenesis(store.genesisLayer(), genesis, None)
      store.anchor shouldBe Some(genesis)
      new AppStateStorage(watched).getStateLayout() shouldBe Some(StateLayout.PathLive1)
    }

  it should "do the same on RocksDB, where a key comes without its column family's byte" taggedAs UnitTest in {
    PathStateTestKit.onRocksDbReopened { source =>
      withState(source).destroyStateForNewGenesis() shouldBe 21
      stateOf(source) shouldBe nothing
    } { (reopened, _) =>
      // and it is gone from the disk: a store opened over the datadir again finds no state
      stateOf(reopened) shouldBe nothing
      new PathStateStorage(new PathNodeStorage(reopened), new AppStateStorage(reopened)).anchor shouldBe None
    }
  }

  // A view reads a row and then the clock. A row that was deleted must not be taken for a position with no node.
  it should "leave a view made before it stale, and allow no view after it" taggedAs UnitTest in {
    val source = EphemDataSource()
    val store = withState(source)
    // a move before the view, so that the log reaches back past it: a log kept across the destruction would say that
    // nothing moved since, and the view would take the rows that are gone for its state
    store.clock.inFlight(StateMove(held, Map.empty))(())
    val view = store.view(held)
    val position =
      PositionKey(Some(owner), ByteString(com.chipprbots.ethereum.mpt.HexPrefix.encode(Array[Byte](3), false)))
    view.read(position).map(_.toSeq) shouldBe Some(aNode.toSeq)
    store.refusalToDestroyState() shouldBe None
    view.isAnchorViewNow shouldBe true
    store.destroyStateForNewGenesis()
    a[StaleStateViewException] should be thrownBy view.read(position)
    a[StaleStateViewException] should be thrownBy view.read(PositionKey.AccountRoot)
    view.isAnchorViewNow shouldBe false
    a[StateUnavailable] should be thrownBy store.view(held)
  }

  it should "be refused by a store that was not built for test mode, before it reads anything" taggedAs UnitTest in {
    val watched = new Recording
    val store = withState(watched, testMode = false)
    val before = (stateOf(watched), watched.writes.size)
    val view = store.view(held)
    store.refusalToDestroyState().getOrElse("") should include("only in test mode")
    val refused = the[IllegalStateException] thrownBy store.destroyStateForNewGenesis()
    refused.getMessage should include("only in test mode")
    watched.writes.size shouldBe before._2
    stateOf(watched) shouldBe before._1
    store.anchor shouldBe Some(held)
    // the clock did not move: a view made before it is still the view of what is on disk
    view.isAnchorViewNow shouldBe true
  }

  it should "leave the datadir and the anchor as they were when its write fails" taggedAs UnitTest in {
    val watched = new Recording
    val store = withState(watched)
    val before = stateOf(watched)
    val view = store.view(held)
    val position =
      PositionKey(Some(owner), ByteString(com.chipprbots.ethereum.mpt.HexPrefix.encode(Array[Byte](3), false)))
    watched.failing = true
    a[java.io.IOException] should be thrownBy store.destroyStateForNewGenesis()
    watched.failing = false
    stateOf(watched) shouldBe before
    store.anchor shouldBe Some(held)
    store.lock.holder shouldBe None
    // the clock is at rest again, and a new view reads the state that is still there
    store.clock.atRest.map(_._2) shouldBe Some(Some(held))
    store.view(held).read(position).map(_.toSeq) shouldBe Some(aNode.toSeq)
    // a view made before it is stale all the same: the log it would be corrected by was emptied
    a[StaleStateViewException] should be thrownBy view.read(position)
    // and the next try destroys it
    store.destroyStateForNewGenesis() shouldBe 21
    stateOf(watched) shouldBe nothing
  }

  // 19 rows (12 of the account trie, 7 of a storage trie) and 2 changeset keys
  it should "refuse a state of more keys than it may delete, of rows or of records, and write nothing" taggedAs
    UnitTest in {
      Seq(18L, 1L).foreach { max =>
        withClue(s"at most $max keys: ") {
          val watched = new Recording
          val store = withState(watched, maxKeys = max)
          val before = (stateOf(watched), watched.writes.size)
          val view = store.view(held)
          store.refusalToDestroyState().getOrElse("") should include(s"more than $max rows or changeset keys")
          val refused = the[IllegalStateException] thrownBy store.destroyStateForNewGenesis()
          refused.getMessage should include(s"more than $max rows or changeset keys")
          watched.writes.size shouldBe before._2
          stateOf(watched) shouldBe before._1
          store.anchor shouldBe Some(held)
          store.lock.holder shouldBe None
          view.isAnchorViewNow shouldBe true
        }
      }
      // the bound itself is taken
      val source = EphemDataSource()
      val store = withState(source, maxKeys = 19)
      // each of the two is bounded by itself: the rows at 19, the changeset keys at 2
      new PathNodeStorage(source).removalOfEveryRow(18) shouldBe None
      new PathNodeStorage(source).removalOfEveryRow(19).map(_.map(_.toRemove.size).sum) shouldBe Some(19)
      new ChangesetStorage(source).removalOfEverything(1) shouldBe None
      new ChangesetStorage(source).removalOfEverything(2).map(_.map(_.toRemove.size).sum) shouldBe Some(2)
      store.refusalToDestroyState() shouldBe None
      store.destroyStateForNewGenesis() shouldBe 21
    }

  it should "delete nothing on a datadir that holds no state, and be done again without harm" taggedAs UnitTest in {
    val source = EphemDataSource()
    val store = new PathStateStorage(
      new PathNodeStorage(source),
      new AppStateStorage(source),
      settings = PathStateStorage.CommitSettings(allowsStateDestruction = true)
    )
    store.destroyStateForNewGenesis() shouldBe 0
    val withOne = withState(EphemDataSource())
    withOne.destroyStateForNewGenesis() shouldBe 21
    withOne.destroyStateForNewGenesis() shouldBe 0
  }
