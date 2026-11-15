package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealInvariant
import com.chipprbots.ethereum.db.dataSource.DataSource.Namespace
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdate
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.testing.Tags.*

/** The clear of the state for a sync from scratch (spec 018 C5a part 3; T165's order, T172): what it leaves, in what
  * order it writes, and what a start makes of a clear that a kill interrupted.
  *
  * The order is pinned on a store that records its writes. That a synced write is on disk and the range deletes with it
  * is RocksDB's, and is for the kill test on a real one.
  */
class ClearForResyncSpec extends AnyFlatSpec with Matchers:

  private val stateFamilies: Seq[Namespace] =
    Seq(Namespaces.StateChangesetNamespace, Namespaces.StateTriePathNamespace, Namespaces.StorageTriePathNamespace)

  /** A store that records what is written to it, in order: a batch (synced or not, with the keys of the application
    * state it puts and removes), and a range delete by its column family.
    */
  final private class Recording extends EphemDataSource(Map.empty):
    @volatile var writes: Vector[String] = Vector.empty
    @volatile var failing: Option[String] = None
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
    private def refuse(what: String): Unit =
      if failing.contains(what) then throw new IllegalStateException(s"the store failed on $what")
    override def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
      refuse("batch")
      writes :+= s"batch[${keysOf(dataSourceUpdates)}]"
      super.update(dataSourceUpdates)
    override def updateSync(dataSourceUpdates: Seq[DataUpdate]): Unit =
      refuse("synced")
      writes :+= s"synced[${keysOf(dataSourceUpdates)}]"
      super.update(dataSourceUpdates)
    override def deleteRange(namespace: Namespace, fromKey: Array[Byte], toKeyExclusive: Array[Byte]): Unit =
      refuse("range")
      writes :+= s"range[${namespace.map(_.toChar).mkString}]"
      super.deleteRange(namespace, fromKey, toKeyExclusive)

  private val genesisHash = ByteString(Array.fill[Byte](32)(1))

  /** The range deletes of a clear, in order, by column family. */
  private val ClearRanges: Vector[String] =
    Vector("range[v]", "range[t]", "range[u]", "range[p]", "range[q]", "range[a]", "range[d]", "range[s]", "range[s]")

  /** A chain of four blocks with accounts and a contract's storage, synced and followed: the datadir an escalation
    * finds.
    */
  private def followed(source: EphemDataSource = EphemDataSource()): CanonicalChainOnPositions =
    val chain = new CanonicalChainOnPositions(source = source)
    def pays(to: Int, amount: Int): PathStateTestKit.World => PathStateTestKit.World =
      _.saveAccount(Address(to), Account(balance = UInt256(amount)))
    chain.next(pays(0x10, 1))
    chain.next(pays(0x11, 2), pays(0x12, 3))
    chain.next(pays(0x10, 4))
    chain.appState.snapSyncDone().and(chain.appState.putSnapSyncAccountsComplete(true)).commit()
    chain

  private def rowsLeft(source: EphemDataSource): Map[String, Int] =
    // by a scan of each family: the in-memory store's `getAll` returns every family's keys
    val above = Array.fill[Byte](PathStateStorage.KeyBoundBytes)(0xff.toByte)
    stateFamilies
      .map(family => family.map(_.toChar).mkString -> source.scanRange(family, Array.emptyByteArray, above).size)
      .toMap

  private val escalation =
    StateEscalation(1763236800000L, EscalationKind.Unreachable, ByteString(Array.fill[Byte](32)(7)), 9000)

  "A clear of the state for a sync from scratch" should "leave no row, no record and no anchor, on a datadir SNAP starts on as on a new one" taggedAs
    UnitTest in {
      val chain = followed()
      val held = chain.refOf(chain.head)
      rowsLeft(chain.source).values.foreach(_ should be > 0)
      chain.state.anchor shouldBe Some(held)
      chain.state.stateAvailable(held) shouldBe true

      chain.state.clearForResync(genesisHash, atMillis = escalation.atMillis, Some(escalation))

      rowsLeft(chain.source) shouldBe Map("v" -> 0, "t" -> 0, "u" -> 0)
      new PathNodeStorage(chain.source).isEmpty shouldBe true
      // no state, on disk and in the store that held it: nothing reads one, and no block is committed in place
      chain.state.anchor shouldBe None
      chain.appState.getStateAnchor() shouldBe None
      chain.state.stateAvailable(held) shouldBe false
      chain.appState.isSnapSyncDone() shouldBe false
      chain.appState.getBestBlockInfo() shouldBe BlockInfo(genesisHash, 0)
      chain.appState.isStateResetPending() shouldBe false
      chain.appState.getStateEscalationLast() shouldBe Some(escalation)
      chain.appState.markDatadirCreatedUnderInvariant().commit()
      HealInvariant.isEmpty(chain.appState, Some(new PathNodeStorage(chain.source))) shouldBe true
      // the blocks themselves are the node's still: the chain it had is not what was at fault
      chain.reader.getBlockHeaderByHash(chain.head.hash) shouldBe Some(chain.head)
      // nothing is finished at a start that follows
      chain.state.finishPendingClear(Some(genesisHash)) shouldBe false
    }

  it should "write in this order: one synced batch that takes the state away, a range delete of each family, then the mark removed, synced" taggedAs
    UnitTest in {
      val source = new Recording
      val chain = followed(source)
      source.writes = Vector.empty
      chain.state.clearForResync(genesisHash, escalation.atMillis, Some(escalation))

      val first = source.writes.head
      first should startWith("synced[")
      // in the one batch: the anchor and the sync's end go with the mark and the record of why
      Seq(
        "-state.anchor",
        "-SnapSyncDone",
        "-SnapSyncAccountsComplete",
        "+state.reset-pending",
        "+state.escalation.last"
      )
        .foreach(key => withClue(s"$key in $first: ")(first should include(key)))
      (first should not).include("state.layout")
      // the rows and the records, then what a sync kept that is of them: its ranges, its walk's queue, the flat
      // copies, the accounts it left out and the storage tries it marked done (two ranges of the application state)
      source.writes.tail shouldBe ClearRanges :+ "synced[-state.reset-pending]"
    }

  it should "change nothing when its first batch fails" taggedAs UnitTest in {
    val source = new Recording
    val chain = followed(source)
    val held = chain.refOf(chain.head)
    val before = rowsLeft(source)
    source.failing = Some("synced")
    an[IllegalStateException] should be thrownBy chain.state.clearForResync(genesisHash, 1L, Some(escalation))
    source.failing = None
    rowsLeft(source) shouldBe before
    chain.state.anchor shouldBe Some(held)
    chain.appState.getStateAnchor() shouldBe Some(held)
    chain.appState.isSnapSyncDone() shouldBe true
    chain.appState.isStateResetPending() shouldBe false
    chain.appState.getStateEscalationLast() shouldBe None
    // and the state is the head's still: the next block is committed on it
    chain.next()
    chain.state.anchor shouldBe Some(chain.refOf(chain.head))
  }

  "The genesis state as a sync starts" should "be cleared when it is the state held, and no other state be touched" taggedAs
    UnitTest in {
      // a datadir whose state is the block its chain began at: what a node holds before it has synced
      val fresh = new CanonicalChainOnPositions(source = new Recording)
      val genesis = fresh.refOf(fresh.head)
      fresh.state.anchor shouldBe Some(genesis)
      rowsLeft(fresh.source)("t") should be > 0
      fresh.state.clearGenesisStateForSync(genesis, atMillis = 9L) shouldBe true
      rowsLeft(fresh.source) shouldBe Map("v" -> 0, "t" -> 0, "u" -> 0)
      fresh.state.anchor shouldBe None
      fresh.appState.isStateResetPending() shouldBe false
      // no escalation is recorded for it
      fresh.appState.getStateEscalationLast() shouldBe None
      // asked again, as a sync that is resumed asks: there is no anchor, and nothing is done
      fresh.source.asInstanceOf[Recording].writes = Vector.empty
      fresh.state.clearGenesisStateForSync(genesis, atMillis = 10L) shouldBe false
      fresh.source.asInstanceOf[Recording].writes shouldBe Vector.empty

      // a state the node has followed the chain to is not the genesis state, and is not cleared
      val chain = followed()
      val before = rowsLeft(chain.source)
      chain.state.clearGenesisStateForSync(genesis, atMillis = 9L) shouldBe false
      rowsLeft(chain.source) shouldBe before
      chain.state.anchor shouldBe Some(chain.refOf(chain.head))
    }

  "A start after a clear that was interrupted" should "delete the rows again and remove the mark, before anything reads the state" taggedAs
    UnitTest in {
      val source = new Recording
      val chain = followed(source)
      // the clear is killed between its first batch and its range deletes: the first delete fails, as a kill would
      // leave it
      source.failing = Some("range")
      an[IllegalStateException] should be thrownBy chain.state.clearForResync(genesisHash, 1L, Some(escalation))
      source.failing = None
      chain.appState.isStateResetPending() shouldBe true
      chain.appState.getStateAnchor() shouldBe None
      rowsLeft(source).values.sum should be > 0

      // the node is started again on the datadir
      val again = new PathStateStorage(new PathNodeStorage(source), chain.appState)
      again.anchor shouldBe None
      source.writes = Vector.empty
      again.finishPendingClear(Some(genesisHash)) shouldBe true
      // what a sync wrote of its run is taken away again first, then the rows, then the mark
      source.writes.head should startWith("synced[")
      source.writes.head should include("-SnapSyncAccountsComplete")
      (source.writes.head should not).include("state.reset-pending")
      source.writes.tail shouldBe ClearRanges :+ "synced[-state.reset-pending]"
      rowsLeft(source) shouldBe Map("v" -> 0, "t" -> 0, "u" -> 0)
      chain.appState.isStateResetPending() shouldBe false
      chain.appState.getStateEscalationLast() shouldBe Some(escalation)
      again.finishPendingClear(Some(genesisHash)) shouldBe false
    }

  it should "take away what a sync wrote of its run while the mark was still set: its rows are deleted, so its record of them must go" taggedAs
    UnitTest in {
      val source = new Recording
      val chain = followed(source)
      source.failing = Some("range")
      an[IllegalStateException] should be thrownBy chain.state.clearForResync(genesisHash, 1L, Some(escalation))
      source.failing = None
      // a sync runs all the same, and says its accounts are complete, at a pivot of its own
      chain.appState
        .putSnapSyncAccountsComplete(true)
        .and(chain.appState.putSnapSyncPivotBlock(7777))
        .and(chain.appState.putBestBlockInfo(BlockInfo(ByteString(Array.fill[Byte](32)(5)), 7777)))
        .commit()
      val progress = new com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage(source)
      progress.recordExcluded(
        Seq(
          com.chipprbots.ethereum.db.storage.ExcludedAccount(
            ByteString(Array.fill[Byte](32)(9)),
            com.chipprbots.ethereum.db.storage.ExcludedReason.StorageRootMismatch
          )
        )
      )
      progress.hasAnyExcluded shouldBe true

      new PathStateStorage(new PathNodeStorage(source), chain.appState)
        .finishPendingClear(Some(genesisHash)) shouldBe true
      chain.appState.isSnapSyncAccountsComplete() shouldBe false
      chain.appState.getSnapSyncPivotBlock() shouldBe None
      chain.appState.getBestBlockInfo() shouldBe BlockInfo(genesisHash, 0)
      progress.hasAnyExcluded shouldBe false
      rowsLeft(source) shouldBe Map("v" -> 0, "t" -> 0, "u" -> 0)
    }

  it should "refuse a datadir that has the mark and a state, and delete nothing of it" taggedAs UnitTest in {
    for withASyncDone <- Seq(false, true) do
      val source = new Recording
      val chain = followed(source)
      // the mark, on a datadir that holds a state: no clear leaves that
      val mark = DataSourceUpdate(
        Namespaces.AppStateNamespace,
        Nil,
        Seq(StateResetPending.Key.getBytes.toIndexedSeq -> StateResetPending.encode(1L).getBytes.toIndexedSeq)
      )
      source.update(Seq(mark))
      // each alone refuses: an anchor with no sync said to be done, and a sync said to be done with no anchor
      if !withASyncDone then
        chain.appState.clearSnapSyncDone().commit()
        chain.appState.isSnapSyncDone() shouldBe false
        chain.appState.getStateAnchor() shouldBe defined
      if withASyncDone then
        // a sync that says it is done and no anchor: no interrupted clear either
        chain.state.clearForResync(genesisHash, 1L, None)
        source.update(Seq(mark))
        chain.appState.snapSyncDone().commit()
      val before = rowsLeft(source)
      source.writes = Vector.empty
      val refused = the[IllegalStateException] thrownBy
        new PathStateStorage(new PathNodeStorage(source), chain.appState).finishPendingClear(Some(genesisHash))
      refused.getMessage should include("holds a state")
      source.writes shouldBe Vector.empty
      rowsLeft(source) shouldBe before
      chain.appState.isStateResetPending() shouldBe true
  }
