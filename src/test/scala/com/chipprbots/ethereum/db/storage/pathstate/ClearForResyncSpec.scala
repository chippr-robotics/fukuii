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
      // and, for an escalation, the entries of the index by number above the block the chains share (B-303)
      source.writes.tail shouldBe ClearRanges :+ "batch[]" :+ "synced[-state.reset-pending,-state.reset-trim-above]"
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

  // B-465 (forge): an adoption on proof of work holds the records on the import's thread while the escalation's clear
  // is asked for on another. The state it moves the head to may be one the chain follows: the clear is refused, with
  // nothing written, the escalation's port answers it as a clear that could not begin (regular sync goes on), and it is
  // asked for again once the adoption has ended.
  it should "be refused while an attempt holds the records, with nothing written, and clear once the attempt has ended" taggedAs
    UnitTest in {
      val source = new Recording
      val chain = followed(source)
      val held = chain.refOf(chain.head)
      val before = rowsLeft(source)
      // on a thread of its own, and waited for: the import's thread holds the records
      def elsewhere[A](body: => A): scala.util.Try[A] =
        val result = new java.util.concurrent.atomic.AtomicReference[scala.util.Try[A]]()
        val thread = new Thread(() => result.set(scala.util.Try(body)))
        thread.start()
        thread.join()
        result.get
      def clear(): Boolean =
        chain.state.clearForResync(genesisHash, escalation.atMillis, Some(escalation))
        true
      val said = new java.util.concurrent.ConcurrentLinkedQueue[String]()
      def tell(why: String): Unit =
        val _ = said.add(why)
      chain.state.withRecordsHeld(held, chain.state.anchor) {
        source.writes = Vector.empty
        elsewhere(chain.state.recordsHeld).get shouldBe true
        elsewhere(clear()).failed.get shouldBe a[StateClearRefused]
        elsewhere(chain.state.clearForBranchRetry(genesisHash, escalation.atMillis)).failed.get shouldBe
          a[StateClearRefused]
        // as the escalation's port answers it: not cleared, and why. The look before the clear finds the hold, and the
        // clear is not run: what it would undo first (the recoveries that wait, and the failures remembered) is kept
        var resetRan = false
        elsewhere(
          com.chipprbots.ethereum.blockchain.sync.SyncController.StateEscalationPort
            .notClearedWhenRefused(chain.state.recordsHeld, tell) { resetRan = true; clear() }
        ).get shouldBe false
        resetRan shouldBe false
        // and an attempt the look missed (begun just after it) is refused by the clear itself, under its lock
        elsewhere(
          com.chipprbots.ethereum.blockchain.sync.SyncController.StateEscalationPort
            .notClearedWhenRefused(held = false, tell)(clear())
        ).get shouldBe false
        // and from the attempt's own thread
        a[StateClearRefused] should be thrownBy clear()
        source.writes shouldBe Vector.empty
        rowsLeft(source) shouldBe before
        chain.state.anchor shouldBe Some(held)
        chain.appState.getStateAnchor() shouldBe Some(held)
        chain.appState.isSnapSyncDone() shouldBe true
        chain.appState.isStateResetPending() shouldBe false
        chain.appState.getStateEscalationLast() shouldBe None
      }
      said.toArray.toList.map(_.toString) match
        case List(looked, locked) =>
          looked should include("holds the records")
          locked should include("holds the records")
        case other => fail(s"told $other")
      chain.state.recordsHeld shouldBe false
      // asked again once the attempt has ended: cleared
      elsewhere(
        com.chipprbots.ethereum.blockchain.sync.SyncController.StateEscalationPort
          .notClearedWhenRefused(chain.state.recordsHeld, tell)(clear())
      ).get shouldBe true
      rowsLeft(source) shouldBe Map("v" -> 0, "t" -> 0, "u" -> 0)
      chain.state.anchor shouldBe None
      chain.appState.getStateEscalationLast() shouldBe Some(escalation)
      said.size() shouldBe 2
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
      source.writes.tail shouldBe ClearRanges :+ "batch[]" :+ "synced[-state.reset-pending,-state.reset-trim-above]"
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

  // B-303 (vault): after a clear for an escalation the index of blocks by number and the header download's cursors
  // named the chain that was left, above the block it shares with the chain the node is to follow

  /** The chain of [[followed]] (blocks 1 to 4) over a genesis that is indexed, with a weight stored for each block up
    * to `weighedTo` and a header download that reached its head.
    */
  private def downloaded(source: EphemDataSource = EphemDataSource(), weighedTo: Int = 4): CanonicalChainOnPositions =
    val chain = followed(source)
    chain.hashesByNumber.put(0, genesisHash).commit()
    val weights = new com.chipprbots.ethereum.db.storage.ChainWeightStorage(chain.source)
    (0 to weighedTo).foreach { height =>
      val hash = chain.hashesByNumber.get(height).get
      weights.put(hash, com.chipprbots.ethereum.domain.ChainWeight.totalDifficultyOnly(100 + height)).commit()
    }
    chain.appState
      .putBackfillBestHeader(4)
      .and(chain.appState.putBackfillBestHeaderHash(chain.head.hash.value))
      .and(chain.appState.putBackfillBestBody(4))
      .and(chain.appState.putBackfillBestReceipt(1))
      .and(chain.appState.putBackfillTarget(4))
      .and(chain.appState.putBackfillTargetHash(chain.head.hash.value))
      .commit()
    chain

  private def indexed(chain: CanonicalChainOnPositions, upTo: Int = 8): Seq[Int] =
    (0 to upTo).filter(height => chain.hashesByNumber.get(height).isDefined)

  private def refused(first: Int): GivenUpBranch = GivenUpBranch(first, ByteString(Array.fill[Byte](32)(0x21)))

  "The index of blocks by number, at a clear for an escalation" should "lose every height above the block the refused branch stands on, and the header download go back to it" taggedAs
    UnitTest in {
      val chain = downloaded()
      val shared = chain.hashesByNumber.get(3).get
      indexed(chain) shouldBe Seq(0, 1, 2, 3, 4)
      // the branch leaves the chain above block 3: its first block is at height 4
      chain.state.clearForResync(genesisHash, escalation.atMillis, Some(escalation), Some(refused(4)))
      indexed(chain) shouldBe Seq(0, 1, 2, 3)
      chain.appState.getBackfillBestHeader() shouldBe 3
      chain.appState.getBackfillBestHeaderHash() shouldBe Some(shared)
      chain.appState.getBackfillBestBody() shouldBe 3
      // a cursor that was lower stays where it was
      chain.appState.getBackfillBestReceipt() shouldBe 1
      chain.appState.getBackfillTarget() shouldBe 0
      chain.appState.getBackfillTargetHash() shouldBe None
      chain.appState.getStateResetTrim() shouldBe None
      // the blocks of the chain that was left are the node's still, by their hashes
      chain.reader.getBlockHeaderByHash(chain.head.hash) shouldBe Some(chain.head)
    }

  // B-307: a sync whose headers were of another chain than the branch its pivot is to be on is begun again
  "A sync begun again for the branch its pivot is to be on" should "lose what it wrote, with the attempt counted and no escalation recorded" taggedAs
    UnitTest in {
      val chain = downloaded()
      val bound = PivotOnBranch(4, ByteString(Array.fill[Byte](32)(0x21)))
      chain.appState
        // the sync that ended had waited for a peer on the branch: the next one begins its own wait
        .putPivotOnBranch(bound.copy(firstMissMillis = Some(3L)))
        .and(chain.appState.putSnapSyncPivotBlock(4))
        .and(chain.appState.putSnapSyncStateRoot(chain.head.stateRoot.value))
        .commit()
      rowsLeft(chain.source).values.foreach(_ should be > 0)
      chain.state.clearForBranchRetry(genesisHash, 5L) shouldBe Some(bound.copy(attempts = 1))
      chain.appState.getPivotOnBranch() shouldBe Some(bound.copy(attempts = 1))
      rowsLeft(chain.source) shouldBe Map("v" -> 0, "t" -> 0, "u" -> 0)
      // the pivot is forgotten: a sync that resumed it would be on that chain again
      chain.appState.getSnapSyncPivotBlock() shouldBe None
      chain.appState.getSnapSyncStateRoot() shouldBe None
      // the index names the chain the peers served above the block the branch stands on: those entries go, and the
      // header download goes back under the branch
      indexed(chain) shouldBe Seq(0, 1, 2, 3)
      chain.appState.getBackfillBestHeader() shouldBe 3
      chain.appState.getBackfillBestHeaderHash() shouldBe chain.hashesByNumber.get(3)
      chain.appState.getStateEscalationLast() shouldBe None
      chain.appState.isStateResetPending() shouldBe false
      chain.appState.getStateResetTrim() shouldBe None
      // the second miss counts on
      chain.state.clearForBranchRetry(genesisHash, 6L) shouldBe Some(bound.copy(attempts = 2))
    }

  it should "clear nothing when no sync is bound" taggedAs UnitTest in {
    val chain = downloaded()
    val before = rowsLeft(chain.source)
    chain.state.clearForBranchRetry(genesisHash, 5L) shouldBe None
    rowsLeft(chain.source) shouldBe before
    indexed(chain) shouldBe Seq(0, 1, 2, 3, 4)
    chain.appState.getBackfillBestHeader() shouldBe 4
  }

  it should "go back to where the chain the peers served left the branch, when that is lower than the branch's first block" taggedAs
    UnitTest in {
      val chain = downloaded()
      val headers = new com.chipprbots.ethereum.db.storage.BlockHeadersStorage(chain.source)
      val served3 = headers.get(chain.hashesByNumber.get(3).get).get
      // the branch's blocks 3 and 4 are stored by hash and not indexed: the chain the peers served left it above block 2
      val branch3 = served3.copy(
        parentHash = com.chipprbots.ethereum.domain.BlockHash(chain.hashesByNumber.get(2).get),
        extraData = ByteString("the branch")
      )
      val branch4 = chain.head.copy(parentHash = branch3.hash)
      headers.put(branch3.hash.value, branch3).and(headers.put(branch4.hash.value, branch4)).commit()
      chain.appState.putPivotOnBranch(PivotOnBranch(4, branch4.hash.value)).commit()
      chain.state.clearForBranchRetry(genesisHash, 5L).map(_.attempts) shouldBe Some(1)
      indexed(chain) shouldBe Seq(0, 1, 2)
      chain.appState.getBackfillBestHeader() shouldBe 2
      chain.appState.getBackfillBestHeaderHash() shouldBe chain.hashesByNumber.get(2)
    }

  it should "be finished at the start that follows a kill, with the attempt counted once" taggedAs UnitTest in {
    val source = new Recording
    val chain = downloaded(source)
    val bound = PivotOnBranch(4, ByteString(Array.fill[Byte](32)(0x21)))
    chain.appState.putPivotOnBranch(bound).commit()
    source.failing = Some("range")
    an[IllegalStateException] should be thrownBy chain.state.clearForBranchRetry(genesisHash, 5L)
    source.failing = None
    chain.appState.isStateResetPending() shouldBe true
    chain.state.finishPendingClear(Some(genesisHash)) shouldBe true
    chain.appState.getPivotOnBranch() shouldBe Some(bound.copy(attempts = 1))
    indexed(chain) shouldBe Seq(0, 1, 2, 3)
    rowsLeft(source) shouldBe Map("v" -> 0, "t" -> 0, "u" -> 0)
  }

  it should "lose, on proof of stake, what is above the block that was finalized, and with none everything above the genesis" taggedAs
    UnitTest in {
      val finalized = downloaded()
      finalized.appState.putStateFinalized(FinalizedBlock(1, finalized.hashesByNumber.get(1).get)).commit()
      finalized.state.clearForResync(genesisHash, escalation.atMillis, Some(escalation))
      indexed(finalized) shouldBe Seq(0, 1)
      finalized.appState.getBackfillBestHeader() shouldBe 1

      val none = downloaded()
      none.state.clearForResync(genesisHash, escalation.atMillis, Some(escalation))
      indexed(none) shouldBe Seq(0)
      none.appState.getBackfillBestHeader() shouldBe 0
      none.appState.getBackfillBestHeaderHash() shouldBe Some(genesisHash)
    }

  it should "go down to a block whose weight is stored: the header download stores nothing on one that has none" taggedAs UnitTest in {
    val chain = downloaded(weighedTo = 1)
    chain.state.clearForResync(genesisHash, escalation.atMillis, Some(escalation), Some(refused(4)))
    indexed(chain) shouldBe Seq(0, 1)
    chain.appState.getBackfillBestHeader() shouldBe 1
  }

  it should "lose what a header download stored above the best block, a chunk at a time" taggedAs UnitTest in {
    val source = new Recording
    val chain = downloaded(source)
    // a sync was under way when the node gave its state up: headers above the best block, and a target far above
    val stale = ByteString(Array.fill[Byte](32)(0x44))
    Seq(5, 6, 7, 20001)
      .foldLeft(chain.appState.putBackfillBestHeader(7))((batch, height) =>
        batch.and(chain.hashesByNumber.put(height, stale))
      )
      .and(chain.appState.putBackfillTarget(2 * PathStateStorage.IndexTrimChunk + 5))
      .commit()
    source.writes = Vector.empty
    chain.state.clearForResync(genesisHash, escalation.atMillis, Some(escalation), Some(refused(4)))
    indexed(chain) shouldBe Seq(0, 1, 2, 3)
    chain.hashesByNumber.get(20001) shouldBe None
    // heights 4 to 20005 in chunks of 10000: three writes, between the range deletes and the last synced write
    source.writes.count(_ == "batch[]") shouldBe 3
    source.writes.last shouldBe "synced[-state.reset-pending,-state.reset-trim-above]"
  }

  it should "lose what the header download stored above the best block when only its cursor says how far" taggedAs UnitTest in {
    val chain = downloaded()
    val stale = ByteString(Array.fill[Byte](32)(0x44))
    // the cursor is at 7; the best block and the target are at 4
    Seq(5, 6, 7)
      .foldLeft(chain.appState.putBackfillBestHeader(7))((batch, height) =>
        batch.and(chain.hashesByNumber.put(height, stale))
      )
      .commit()
    chain.state.clearForResync(genesisHash, escalation.atMillis, Some(escalation), Some(refused(4)))
    indexed(chain) shouldBe Seq(0, 1, 2, 3)
  }

  it should "be left as it is by a clear that is no escalation" taggedAs UnitTest in {
    val chain = downloaded()
    chain.state.clearForResync(genesisHash, 1L, None)
    indexed(chain) shouldBe Seq(0, 1, 2, 3, 4)
    chain.appState.getBackfillBestHeader() shouldBe 4
    chain.appState.getBackfillTarget() shouldBe 4
  }

  it should "be trimmed at the start that follows a kill, with what a sync stored meanwhile" taggedAs UnitTest in {
    val source = new Recording
    val chain = downloaded(source)
    source.failing = Some("range")
    an[IllegalStateException] should be thrownBy
      chain.state.clearForResync(genesisHash, escalation.atMillis, Some(escalation), Some(refused(4)))
    source.failing = None
    // the first batch is written: what is to be deleted is recorded, the cursors are back, the entries are still there
    chain.appState.getStateResetTrim() shouldBe Some(StateResetTrim(3, 4))
    chain.appState.getBackfillBestHeader() shouldBe 3
    indexed(chain) shouldBe Seq(0, 1, 2, 3, 4)
    // the node went on, and a header download stored a header above the shared block and moved its cursor
    chain.hashesByNumber
      .put(4, ByteString(Array.fill[Byte](32)(0x55)))
      .and(chain.appState.putBackfillBestHeader(4))
      .and(chain.appState.putBackfillTarget(4))
      .commit()

    val again = new PathStateStorage(new PathNodeStorage(source), chain.appState)
    again.finishPendingClear(Some(genesisHash)) shouldBe true
    indexed(chain) shouldBe Seq(0, 1, 2, 3)
    chain.appState.getBackfillBestHeader() shouldBe 3
    chain.appState.getBackfillBestHeaderHash() shouldBe chain.hashesByNumber.get(3)
    chain.appState.getBackfillTarget() shouldBe 0
    chain.appState.getStateResetTrim() shouldBe None
    chain.appState.isStateResetPending() shouldBe false
  }

  // vault, after code: a sync that ran between a clear's first batch and the start that finishes it stores entries above
  // what the clear wrote down
  it should "lose, at the start that follows a kill, what a sync stored above the heights the clear wrote down" taggedAs UnitTest in {
    val source = new Recording
    val chain = downloaded(source)
    source.failing = Some("range")
    an[IllegalStateException] should be thrownBy
      chain.state.clearForResync(genesisHash, escalation.atMillis, Some(escalation), Some(refused(4)))
    source.failing = None
    chain.appState.getStateResetTrim() shouldBe Some(StateResetTrim(3, 4))
    val stale = ByteString(Array.fill[Byte](32)(0x55))
    Seq(4, 5, 6, 7)
      .foldLeft(chain.appState.putBackfillBestHeader(6))((batch, height) =>
        batch.and(chain.hashesByNumber.put(height, stale))
      )
      // a pivot's header at 7, stored with its place in the index before the pivot's number is written down
      .and(chain.appState.putSnapSyncBootstrapTarget(7))
      .commit()
    new PathStateStorage(new PathNodeStorage(source), chain.appState)
      .finishPendingClear(Some(genesisHash)) shouldBe true
    indexed(chain) shouldBe Seq(0, 1, 2, 3)
    chain.appState.getStateResetTrim() shouldBe None
  }

  it should "reach as high as a pivot whose header is stored, by the target it was fetched for or by its hash" taggedAs UnitTest in {
    val byTarget = downloaded()
    val stale = ByteString(Array.fill[Byte](32)(0x66))
    byTarget.hashesByNumber.put(8, stale).and(byTarget.appState.putSnapSyncBootstrapTarget(8)).commit()
    byTarget.state.clearForResync(genesisHash, escalation.atMillis, Some(escalation), Some(refused(4)))
    indexed(byTarget) shouldBe Seq(0, 1, 2, 3)

    val byHash = downloaded()
    val pivot = byHash.headerOf(8, byHash.head.stateRoot.value, salt = 9)
    byHash.headersByHash
      .put(pivot.hash.value, pivot)
      .and(byHash.hashesByNumber.put(8, pivot.hash.value))
      .and(byHash.appState.putSnapSyncPivotHash(pivot.hash.value))
      .commit()
    byHash.state.clearForResync(genesisHash, escalation.atMillis, Some(escalation), Some(refused(4)))
    indexed(byHash) shouldBe Seq(0, 1, 2, 3)
  }

  it should "begin the header download again where the index has nothing at its cursor" taggedAs UnitTest in {
    val chain = downloaded()
    // the cursor says 2, and the index has no block there
    chain.hashesByNumber.remove(2).and(chain.appState.putBackfillBestHeader(2)).commit()
    chain.state.clearForResync(genesisHash, escalation.atMillis, Some(escalation), Some(refused(4)))
    chain.appState.getBackfillBestHeader() shouldBe 0
    chain.appState.getBackfillBestHeaderHash() shouldBe None
    chain.appState.getBackfillBestBody() shouldBe 0
    chain.appState.getBackfillBestReceipt() shouldBe 0
  }

  it should "leave a cursor that was below the shared block where it was" taggedAs UnitTest in {
    val chain = downloaded()
    chain.appState
      .putBackfillBestHeader(2)
      .and(chain.appState.putBackfillBestHeaderHash(chain.hashesByNumber.get(2).get))
      .and(chain.appState.putBackfillBestBody(2))
      .commit()
    chain.state.clearForResync(genesisHash, escalation.atMillis, Some(escalation), Some(refused(4)))
    chain.appState.getBackfillBestHeader() shouldBe 2
    chain.appState.getBackfillBestHeaderHash() shouldBe chain.hashesByNumber.get(2)
    chain.appState.getBackfillBestBody() shouldBe 2
  }

  it should "not start over a record of the trim that cannot be read" taggedAs UnitTest in {
    val source = new Recording
    val chain = downloaded(source)
    source.failing = Some("range")
    an[IllegalStateException] should be thrownBy
      chain.state.clearForResync(genesisHash, escalation.atMillis, Some(escalation), Some(refused(4)))
    source.failing = None
    source.update(
      Seq(
        DataSourceUpdate(
          Namespaces.AppStateNamespace,
          Nil,
          Seq(
            scala.collection.immutable.ArraySeq.unsafeWrapArray(StateResetTrim.Key.getBytes) ->
              scala.collection.immutable.ArraySeq.unsafeWrapArray("v9:1".getBytes)
          )
        )
      )
    )
    val refusedStart = the[IllegalStateException] thrownBy
      new PathStateStorage(new PathNodeStorage(source), chain.appState).finishPendingClear(Some(genesisHash))
    refusedStart.getMessage should include("state.reset-trim-above")
    // nothing was deleted: the mark stands for the start that follows a mend
    chain.appState.isStateResetPending() shouldBe true
    indexed(chain) shouldBe Seq(0, 1, 2, 3, 4)
  }

  "The record of what a clear deletes of the index" should "be read back as it was written, and be an error when it cannot be read" taggedAs
    UnitTest in {
      StateResetTrim.decode(StateResetTrim.encode(StateResetTrim(2, 20005))) shouldBe StateResetTrim(2, 20005)
      StateResetTrim.encode(StateResetTrim(0, 7)) shouldBe "v1:0:7"
      for unreadable <- Seq("", "v2:0:7", "v1:0", "v1:x:7", "v1:0:-7", "v1::7")
      do withClue(s"'$unreadable': ")(an[IllegalStateException] should be thrownBy StateResetTrim.decode(unreadable))
    }
