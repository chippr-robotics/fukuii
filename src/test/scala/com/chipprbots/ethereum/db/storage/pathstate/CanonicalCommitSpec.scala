package com.chipprbots.ethereum.db.storage.pathstate

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import org.apache.pekko.util.ByteString

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Try

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.mpt.PositionTestKit.RawTrieWalk
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C3b (T078, T081 to T083, T085, T086 and the commit's half of T079 and T080): the canonical commit
  * of a block on the position store.
  *
  * A chain is executed block by block on staged layers and committed. What the disk must hold after each block is said
  * by a hash store the same transactions were run on, walked as raw RLP: every row of CF `t` and CF `u`, and no other.
  */
class CanonicalCommitSpec extends AnyFlatSpec with Matchers:

  import PathStateStorage.*
  import PathStateTestKit.*

  private type Tx = World => World
  private type Disk = (Map[ByteString, ByteString], Map[ByteString, ByteString])

  private def slots(address: Address, written: (Int, Int)*): Tx = withSlots(address, written)
  private def account(address: Address, nonce: Int, balance: Int): Tx = world =>
    world.saveAccount(
      address,
      world.getAccount(address).getOrElse(Account(0, 0)).copy(nonce = nonce, balance = balance)
    )
  private def delete(address: Address): Tx = _.deleteAccount(address)
  private val eve = Address(0xe7e)

  /** A datadir whose position store holds `HashedState`'s state as block 1's, and the chain built on it. */
  final private class Chain(
      val source: DataSource,
      settings: CommitSettings = CommitSettings(),
      anchored: Boolean = true,
      seeded: Option[HashedState] = None
  ):
    val hashed: HashedState = seeded.getOrElse(new HashedState)
    val first: BlockRef = block(1, hashed.root)
    // rows are dumped through the data source itself, which a watched one stands in front of
    private val raw = source match
      case watched: Watched => watched.under
      case other            => other
    private val dump = new PathNodeStorage(raw)
    val rows = new PathNodeStorage(source)
    val appState = new AppStateStorage(source)
    if seeded.isEmpty then
      writeState(dump, hashed.positions())
      if anchored then appState.putStateAnchor(first).commit()
    val state = new PathStateStorage(rows, appState, settings = settings)
    val records = new ChangesetStorage(source)
    var head: BlockRef = first

    def disk: Disk = allRows(dump)

    /** The rows a state is, by a walk of its raw nodes on the hash store. */
    def rowsOf(root: ByteString): Disk =
      val positions = RawTrieWalk.statePositions(root.toArray, hashed.nodes.load)
      (
        positions.getOrElse(None, Map.empty),
        positions.collect { case (Some(owner), trie) => trie.map((hp, node) => (owner ++ hp) -> node) }.flatten.toMap
      )

    /** The best-block pointer, as the block's own half of the batch. */
    def pointer(ref: BlockRef): Option[DataSourceBatchUpdate] =
      Some(appState.putBestBlockInfo(BlockInfo(ref.hash, ref.number)))

    /** A block of `transactions` on `parent`: executed on a fresh staged layer, and on the hash store. */
    final class Executed(val parent: BlockRef, transactions: Seq[Tx], name: String):
      val staged: PathStagedState = state.stage(parent)
      private def run(start: World): World =
        transactions.foldLeft(start)((world, tx) => InMemoryWorldStateProxy.persistState(tx(world)))
      val onPositions: World = run(worldOver(staged.storage, parent.stateRoot, hashed.codes))
      val root: ByteString = run(hashed.world(parent.stateRoot)).stateRootHash
      val ref: BlockRef =
        BlockRef(ByteString(kec256(s"block-${parent.number + 1}$name".getBytes)), parent.number + 1, root)
      def commit(): CommitOutcome = state.commitCanonical(Some(staged), ref, pointer(ref))

    def execute(transactions: Tx*): Executed = new Executed(head, transactions, "")
    def executeAs(name: String)(transactions: Tx*): Executed = new Executed(head, transactions, name)

    /** Executes and commits a block on the head. */
    def advance(transactions: Tx*): Commit =
      val executed = execute(transactions*)
      executed.onPositions.stateRootHash shouldBe executed.root
      val commit = executed.commit() match
        case CommitOutcome.Applied(done) => done
        case other                       => fail(s"block ${executed.ref.show} was not applied: $other")
      head = executed.ref
      commit

    def record(ref: BlockRef): BlockDiff = records.get(ref.hash) match
      case Some(Right(diff)) => diff
      case other             => fail(s"no record of block ${ref.show}: $other")

    /** Undoes `ref`'s rows from its record. */
    def reverse(ref: BlockRef): Unit =
      source.update(rows.rowUpdates(record(ref).changes.view.mapValues(_.prior)))

    /** Every stored node of the state at `root`, read through a fresh view of the disk, as a walk reads it. */
    def readable(ref: BlockRef): Disk =
      val storage = state.readOnly(ref)
      val positions = RawTrieWalk.statePositions(
        ref.stateRoot.toArray,
        hash => hashed.nodes.load(hash) // which node to expect where: the reference's
      )
      // each position is read from the view and must hold the node the reference has there
      positions.foreach { (trie, trieRows) =>
        trieRows.foreach { (hp, node) =>
          val path = PositionKey(trie, hp).path
          withClue(s"${PositionKey(trie, hp).show}: ") {
            ByteString(storage.forOwner(trie).getAt(path, kec256(node.toArray)).encode) shouldBe node
          }
        }
      }
      rowsOf(ref.stateRoot)

  private def anchorOnDisk(source: DataSource): Option[BlockRef] = new AppStateStorage(source).getStateAnchor()

  private val threeBlocks: Seq[Seq[Tx]] = Seq(
    Seq(slots(alice, 1 -> 5, 2 -> 6).andThen(account(dave, 8, 8)), slots(alice, 1 -> 7, 41 -> 1)),
    Seq(
      account(eve, 0, 1).andThen(slots(eve, 1 -> 1, 2 -> 2)),
      slots(carol, otherSlots.take(12).map((k, _) => k -> 0)*)
    ),
    Seq(slots(eve, 1 -> 3), delete(crowd(3)), slots(bob, 4 -> 0, 5 -> 9))
  )

  dataSources.foreach { case (name, onDataSource) =>
    s"A canonical commit on $name" should "leave the disk holding the block's state, row for row, and the block as the anchor" taggedAs
      UnitTest in {
        onDataSource { source =>
          val chain = new Chain(source)
          threeBlocks.foreach { transactions =>
            val parent = chain.head
            val before = chain.disk
            val commit = chain.advance(transactions*)
            withClue(s"after block ${chain.head.show}: ") {
              chain.disk shouldBe chain.rowsOf(chain.head.stateRoot)
              chain.disk should not be before
              chain.state.anchor shouldBe Some(chain.head)
              anchorOnDisk(source) shouldBe Some(chain.head)
              // the block's own half went with it
              chain.appState.getBestBlockInfo() shouldBe BlockInfo(chain.head.hash, chain.head.number)
              // and its record: this block on that parent, with what it changed
              val record = chain.record(chain.head)
              record.validate shouldBe empty
              (record.number, record.parentHash, record.preStateRoot, record.postStateRoot) shouldBe
                (chain.head.number, parent.hash, parent.stateRoot, chain.head.stateRoot)
              record.changes.size shouldBe commit.positions
              chain.records.hashesAt(chain.head.number) shouldBe Seq(chain.head.hash)
              commit.tombstone shouldBe None
              commit.recordBytes shouldBe BlockDiffCodec.encode(record).length
            }
          }
        }
      }

    // T078: forward and then back, from the records alone.
    it should "be undone exactly by its record: every row is the parent's again, block after block" taggedAs UnitTest in {
      onDataSource { source =>
        val chain = new Chain(source)
        val states = chain.disk +: threeBlocks.map { transactions =>
          chain.advance(transactions*)
          chain.disk
        }
        val blocks = Iterator
          .iterate(chain.head)(ref =>
            chain.record(ref).parentHash match
              case hash if hash == chain.first.hash => chain.first
              case hash                             => BlockRef(hash, ref.number - 1, chain.record(ref).preStateRoot)
          )
          .take(threeBlocks.size)
          .toSeq
        blocks.zip(states.reverse.tail).foreach { (ref, parentState) =>
          chain.reverse(ref)
          withClue(s"block ${ref.show} undone: ")(chain.disk shouldBe parentState)
        }
        chain.disk shouldBe chain.rowsOf(chain.first.stateRoot)
      }
    }

    // T079 and T080 at the commit: the rows of a replaced storage trie are the disk's, found by a scan.
    it should "delete the rows of a storage trie the block replaced, and put them all back on a reverse" taggedAs
      UnitTest in {
        onDataSource { source =>
          val chain = new Chain(source)
          val parentState = chain.disk
          chain.advance(
            slots(alice, 1 -> 5, 2 -> 6),
            delete(alice),
            account(alice, 0, 1).andThen(slots(alice, manySlots.take(20).map((k, v) => k -> (v + 1))*)),
            slots(alice, 3 -> 0, 50 -> 50).andThen(delete(carol))
          )
          chain.disk shouldBe chain.rowsOf(chain.head.stateRoot)
          // carol's storage is gone whole; bob holds what alice held, and keeps it
          chain.disk._2.keys.exists(_.startsWith(owner(carol))) shouldBe false
          chain.disk._2.filter((key, _) => key.startsWith(owner(bob))) shouldBe
            parentState._2.filter((key, _) => key.startsWith(owner(bob)))
          chain.reverse(chain.head)
          chain.disk shouldBe parentState
        }
      }

    // A lazy hand-off can leave a trie with a hole (spec 022). A block that removes that account does not read it.
    it should "commit a block that replaces a storage trie with a hole in it" taggedAs UnitTest in {
      onDataSource { source =>
        val chain = new Chain(source)
        val alices = chain.disk._2.keys.filter(_.startsWith(owner(alice))).toSeq.sortBy(_.length)
        val hole = alices.last
        source.update(chain.rows.rowUpdates(Seq(PositionKey(Some(owner(alice)), hole.drop(32)) -> None)))
        chain.advance(delete(alice), account(dave, 1, 1))
        chain.disk shouldBe chain.rowsOf(chain.head.stateRoot)
        chain.record(chain.head).changes.keys.count(_.owner.contains(owner(alice))) shouldBe alices.size - 1
      }
    }

    it should "commit a block that changes nothing: the anchor moves, and its record has no entry" taggedAs UnitTest in {
      onDataSource { source =>
        val chain = new Chain(source)
        val before = chain.disk
        val commit = chain.advance()
        commit.positions shouldBe 0
        chain.disk shouldBe before
        chain.state.anchor shouldBe Some(chain.head)
        chain.record(chain.head).changes shouldBe empty
        chain.record(chain.head).incomplete shouldBe false
      }
    }

    // T081. The bound never fails a block.
    it should "leave the rows of a replaced trie that has too many, write everything else, and record a tombstone" taggedAs
      UnitTest in {
        onDataSource { source =>
          val reference = new Chain(EphemDataSource())
          val alicesRows = reference.disk._2.count((key, _) => key.startsWith(owner(alice)))
          alicesRows should be > 8

          val chain = new Chain(source, CommitSettings(maxEntriesPerOwnerDelete = alicesRows - 1))
          val parentState = chain.disk
          val commit = chain.advance(
            slots(alice, 1 -> 5),
            delete(alice),
            account(alice, 0, 1).andThen(slots(alice, 1 -> 2, 2 -> 3, 77 -> 7)),
            slots(carol, 3 -> 3).andThen(account(dave, 9, 9))
          )
          commit.tombstone shouldBe Some(TombstoneCause.OwnerDeleteBound)
          commit.unlistedOwners shouldBe Seq(owner(alice))
          val record = chain.record(chain.head)
          record.incomplete shouldBe true
          record.changes shouldBe empty
          record.validate shouldBe empty

          // the block's state is all there, readable from the disk as any state is
          val state = chain.readable(chain.head)
          val (accounts, storage) = chain.disk
          accounts shouldBe state._1
          state._2.foreach((key, node) => withClue("a row of the new state: ")(storage.get(key) shouldBe Some(node)))
          // what is there beside it is the dead trie's, and nothing of the dead trie was deleted
          val leftover = storage -- state._2.keys
          leftover should not be empty
          all(leftover.keys.map(_.startsWith(owner(alice)))) shouldBe true
          leftover.foreach((key, node) => parentState._2.get(key) shouldBe Some(node))
          (leftover.size + state._2.count((key, _) => key.startsWith(owner(alice)))) should be >= alicesRows

          // the chain goes on over the leftovers: a block that replaces the trie again, now within the bound or not
          chain.advance(slots(alice, 1 -> 9, 5 -> 5), slots(bob, 1 -> 1))
          chain.readable(chain.head)
          chain.record(chain.head).incomplete shouldBe false
        }
      }

    // vault: the rows are read into memory under the state lock, and a count of rows does not bound that.
    it should "leave the rows of a replaced trie that holds too many bytes, as it does one with too many rows" taggedAs
      UnitTest in {
        onDataSource { source =>
          val reference = new Chain(EphemDataSource())
          val alices = reference.disk._2.filter((key, _) => key.startsWith(owner(alice)))
          val bytes = alices.map((key, node) => key.length.toLong + node.length).sum

          val over = new Chain(source, CommitSettings(maxOwnerScanBytes = bytes - 1))
          val commit = over.advance(delete(alice), account(dave, 1, 1))
          commit.tombstone shouldBe Some(TombstoneCause.OwnerDeleteBound)
          commit.unlistedOwners shouldBe Seq(owner(alice))
          over.disk._2.filter((key, _) => key.startsWith(owner(alice))) shouldBe alices
          over.readable(over.head)

          val at = new Chain(EphemDataSource(), CommitSettings(maxOwnerScanBytes = bytes))
          at.advance(delete(alice), account(dave, 1, 1)).tombstone shouldBe None
          at.disk shouldBe at.rowsOf(at.head.stateRoot)
        }
      }

    // beacon: Amsterdam clears the storage of an account that destroyed itself and keeps the account, when it still
    // holds a balance.
    it should "delete the rows of an account whose storage is cleared while the account stays" taggedAs UnitTest in {
      onDataSource { source =>
        val chain = new Chain(source)
        val parentState = chain.disk
        val cleared: Tx = world =>
          val kept = world.getAccount(carol).get
          world.deleteAccount(carol).saveAccount(carol, Account(nonce = kept.nonce, balance = kept.balance))
        val executed = chain.execute(slots(carol, 3 -> 7), cleared, account(dave, 1, 1))
        executed.onPositions.getAccount(carol).map(_.storageRoot) shouldBe Some(Account.EmptyStorageRootHash)
        executed.commit() shouldBe a[CommitOutcome.Applied]
        chain.head = executed.ref
        chain.disk shouldBe chain.rowsOf(executed.root)
        chain.disk._2.keys.exists(_.startsWith(owner(carol))) shouldBe false
        parentState._2.keys.count(_.startsWith(owner(carol))) should be > 1
        chain.reverse(chain.head)
        chain.disk shouldBe parentState
      }
    }

    // A storage trie small enough that its root is under 32 bytes has a row all the same (rule 7). The world cannot
    // make one, its keys being hashes: the trie is written straight onto the layer.
    it should "write and delete the root row of a storage trie whose root is under 32 bytes" taggedAs UnitTest in {
      onDataSource { source =>
        import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
        import com.chipprbots.ethereum.mpt.byteStringSerializer
        val chain = new Chain(source)
        val tiny = owner(eve)
        val root = PositionKey(Some(tiny), PositionKey.AccountRoot.hp)
        val before = chain.disk

        val created = chain.state.stage(chain.head)
        MerklePatriciaTrie[ByteString, ByteString](created.storage.forOwner(Some(tiny)))
          .put(ByteString(0x12.toByte), ByteString(1.toByte))
        val second = BlockRef(ByteString(kec256("small-2".getBytes)), 2, chain.head.stateRoot)
        chain.state.commitCanonical(Some(created), second, None) shouldBe a[CommitOutcome.Applied]
        val row = chain.disk._2(ByteString(root.rowKey))
        row.length should be < BlockDiff.StoredNodeLength
        chain.disk shouldBe (before._1, before._2 + (ByteString(root.rowKey) -> row))
        chain.record(second).changes shouldBe Map(root -> Change(None, Some(row)))

        val emptied = chain.state.stage(second)
        MerklePatriciaTrie[ByteString, ByteString](kec256(row.toArray), emptied.storage.forOwner(Some(tiny)))
          .remove(ByteString(0x12.toByte))
        val third = BlockRef(ByteString(kec256("small-3".getBytes)), 3, chain.head.stateRoot)
        chain.state.commitCanonical(Some(emptied), third, None) shouldBe a[CommitOutcome.Applied]
        chain.disk shouldBe before
        chain.record(third).changes shouldBe Map(root -> Change(Some(row), None))
      }
    }

    it should "list a replaced trie that has exactly as many rows as may be listed" taggedAs UnitTest in {
      onDataSource { source =>
        val reference = new Chain(EphemDataSource())
        val alicesRows = reference.disk._2.count((key, _) => key.startsWith(owner(alice)))
        val chain = new Chain(source, CommitSettings(maxEntriesPerOwnerDelete = alicesRows))
        val commit = chain.advance(delete(alice))
        commit.tombstone shouldBe None
        chain.disk shouldBe chain.rowsOf(chain.head.stateRoot)
        chain.record(chain.head).changes.keys.count(_.owner.contains(owner(alice))) shouldBe alicesRows
      }
    }

    it should "write a tombstone for a record that would be too large, and still every row" taggedAs UnitTest in {
      onDataSource { source =>
        val sized = new Chain(EphemDataSource())
        val transactions = Seq(slots(alice, 1 -> 5, 2 -> 6), delete(carol), account(dave, 3, 3))
        val whole = sized.advance(transactions*)

        val chain = new Chain(source, CommitSettings(maxRecordBytes = whole.recordBytes - 1))
        val commit = chain.advance(transactions*)
        commit.tombstone shouldBe Some(TombstoneCause.RecordSize)
        commit.unlistedOwners shouldBe empty
        commit.positions shouldBe whole.positions
        chain.record(chain.head).incomplete shouldBe true
        chain.record(chain.head).changes shouldBe empty
        // the rows are exact: carol's dead trie is gone
        chain.disk shouldBe chain.rowsOf(chain.head.stateRoot)

        // at the bound itself the record is written whole
        val atBound = new Chain(EphemDataSource(), CommitSettings(maxRecordBytes = whole.recordBytes))
        atBound.advance(transactions*).tombstone shouldBe None
      }
    }

    // T085.
    it should "delete the records below the retention floor in the batch of the block that puts them there" taggedAs
      UnitTest in {
        onDataSource { source =>
          val keepThree = new RetentionPolicy(StateConfig.Mode.Basic, (_, _) => 3)
          val chain = new Chain(source, CommitSettings(retention = keepThree))
          val blocks = (1 to 9).map { i =>
            chain.advance(account(dave, i, i), slots(carol, 3 -> i))
            // the first block committed here starts the count at itself, and it only ever goes up
            chain.appState.getChangesetPrunedTo() shouldBe Some(BigInt(2).max(chain.head.number - 2))
            chain.head
          }
          val (gone, kept) = blocks.partition(_.number <= chain.head.number - 3)
          kept should have size 3
          gone.foreach { ref =>
            withClue(s"block ${ref.show}: ") {
              chain.records.get(ref.hash) shouldBe None
              chain.records.hashesAt(ref.number) shouldBe empty
            }
          }
          kept.foreach(ref => chain.record(ref).number shouldBe ref.number)
        }
      }

    it should "keep every record under archive, and catch up in one batch when the node is switched to basic" taggedAs
      UnitTest in {
        onDataSource { source =>
          val archive = new Chain(source)
          val blocks = (1 to 40).map { i =>
            archive.advance(account(dave, i, i)).prunedHeights shouldBe 0
            archive.head
          }
          blocks.foreach(ref => archive.record(ref).number shouldBe ref.number)
          archive.appState.getChangesetPrunedTo() shouldBe Some(BigInt(2))

          // the same datadir, opened with records kept for two blocks
          val keepTwo = new RetentionPolicy(StateConfig.Mode.Basic, (_, _) => 2)
          val basic = new Chain(source, CommitSettings(retention = keepTwo), seeded = Some(archive.hashed))
          basic.head = archive.head
          basic.state.anchor shouldBe Some(archive.head)
          val commit = basic.advance(account(dave, 99, 99))
          commit.prunedHeights shouldBe 39
          blocks.init.foreach(ref => basic.records.get(ref.hash) shouldBe None)
          basic.record(blocks.last).number shouldBe blocks.last.number
          basic.record(basic.head).number shouldBe basic.head.number
          basic.appState.getChangesetPrunedTo() shouldBe Some(basic.head.number - 1)
        }
      }

    // The record of the block being committed is written in the batch that prunes: the deletes must not reach it.
    it should "keep the record of the block it commits when nothing else is kept" taggedAs UnitTest in {
      onDataSource { source =>
        val keepOne = new RetentionPolicy(StateConfig.Mode.Basic, (_, _) => 1)
        val chain = new Chain(source, CommitSettings(retention = keepOne))
        (1 to 4).foreach { i =>
          val parent = chain.head
          chain.advance(account(dave, i, i))
          chain.record(chain.head).number shouldBe chain.head.number
          if parent != chain.first then chain.records.get(parent.hash) shouldBe None
        }
      }
    }
  }

  // ─── What a commit refuses, and what it leaves when it does ──────────────────────────────────────────────────────

  /** Everything a refused commit must leave as it was. */
  private def untouched(chain: Chain)(refused: => Any): Unit =
    val rows = chain.disk
    val anchor = anchorOnDisk(chain.source)
    val best = chain.appState.getBestBlockInfo()
    val cursor = chain.appState.getChangesetPrunedTo()
    refused
    chain.disk shouldBe rows
    anchorOnDisk(chain.source) shouldBe anchor
    chain.state.anchor shouldBe anchor
    chain.appState.getBestBlockInfo() shouldBe best
    chain.appState.getChangesetPrunedTo() shouldBe cursor

  // T082 (beacon). While SNAP owns the rows there is no anchor, and nothing may be committed in place over them.
  "A canonical commit with no state anchor" should "be refused, and leave every row as it was" taggedAs UnitTest in {
    val chain = new Chain(EphemDataSource(), anchored = false)
    chain.state.anchor shouldBe None
    // a block cannot even be staged
    (the[StateUnavailable] thrownBy chain.state.stage(chain.first)).reason should include("no state anchor")
    // and a layer made some other way is refused at the commit, under the lock
    val layer = new PathStagedState(chain.first, new StateView(chain.first, 0L, chain.state.clock, chain.rows))
    val next = block(2, chain.hashed.root)
    untouched(chain) {
      val refused = the[StateUnavailable] thrownBy chain.state.commitCanonical(Some(layer), next, chain.pointer(next))
      refused.reason should include("no state anchor")
      (the[StateUnavailable] thrownBy chain.state.commitCanonical(None, next, chain.pointer(next))).reason should
        include("no state anchor")
    }
    chain.records.get(next.hash) shouldBe None
    chain.state.lock.holder shouldBe None
  }

  // T083. Until the overlay, a block is committed in place or not at all.
  "A block that does not extend the anchor" should "be refused when its parent is no longer the anchor" taggedAs
    UnitTest in {
      val chain = new Chain(EphemDataSource())
      // two blocks on block 1; the first is committed, and the second finds the anchor moved
      val taken = chain.executeAs("a")(account(dave, 1, 1))
      val late = chain.executeAs("b")(account(dave, 2, 2), slots(alice, 1 -> 1))
      taken.commit() shouldBe a[CommitOutcome.Applied]
      untouched(chain) {
        val refused = the[StateUnavailable] thrownBy late.commit()
        refused.reason should (include(late.ref.show).and(include(taken.ref.show)))
      }
      chain.records.get(late.ref.hash) shouldBe None
      chain.records.hashesAt(2) shouldBe Seq(taken.ref.hash)
    }

  it should "have only its own records written when it is the anchor already" taggedAs UnitTest in {
    val chain = new Chain(EphemDataSource())
    val executed = chain.execute(account(dave, 1, 1), slots(alice, 1 -> 1))
    val again = chain.execute(account(dave, 1, 1), slots(alice, 1 -> 1))
    val different = chain.execute(delete(carol), slots(bob, 9 -> 9))
    executed.commit() shouldBe a[CommitOutcome.Applied]
    val rows = chain.disk
    val epoch = chain.state.clock.epoch
    // the pointer is put somewhere else, so that the second commit's own half can be seen to be written
    chain.appState.putBestBlockInfo(BlockInfo(chain.first.hash, chain.first.number)).commit()

    again.commit() shouldBe CommitOutcome.AlreadyApplied
    chain.appState.getBestBlockInfo() shouldBe BlockInfo(executed.ref.hash, executed.ref.number)
    chain.disk shouldBe rows
    chain.state.clock.epoch shouldBe epoch
    chain.records.hashesAt(2) shouldBe Seq(executed.ref.hash)

    // a layer that holds something else is dropped like any other: none of it is written
    chain.state.commitCanonical(Some(different.staged), executed.ref, chain.pointer(executed.ref)) shouldBe
      CommitOutcome.AlreadyApplied
    chain.disk shouldBe rows
    chain.disk shouldBe chain.rowsOf(executed.root)
    chain.records.get(executed.ref.hash).map(_.map(_.changes.size)) shouldBe Some(
      Right(executed.staged.positionsTouched)
    )

    // and with nothing staged at all: the caller that found the block executed already
    chain.appState.putBestBlockInfo(BlockInfo(chain.first.hash, chain.first.number)).commit()
    chain.state.commitCanonical(None, executed.ref, chain.pointer(executed.ref)) shouldBe CommitOutcome.AlreadyApplied
    chain.appState.getBestBlockInfo() shouldBe BlockInfo(executed.ref.hash, executed.ref.number)
    chain.state.isApplied(executed.ref.hash) shouldBe true
    chain.state.isApplied(chain.first.hash) shouldBe false
  }

  it should "be refused when nothing was staged for it and it is not the anchor" taggedAs UnitTest in {
    val chain = new Chain(EphemDataSource())
    val next = block(2, chain.hashed.root)
    untouched(chain) {
      (the[StateUnavailable] thrownBy chain.state.commitCanonical(None, next, chain.pointer(next))).reason should
        include("was not executed")
    }
  }

  // The account trie's root row is held to the header's state root before anything is written.
  "A staged layer that is not the block's" should "stop the commit before a row is written" taggedAs UnitTest in {
    val chain = new Chain(EphemDataSource())
    val executed = chain.execute(account(dave, 1, 1))
    val other = chain.execute(account(dave, 2, 2))
    untouched(chain) {
      val defect = the[IllegalStateException] thrownBy
        chain.state.commitCanonical(Some(executed.staged), other.ref, chain.pointer(other.ref))
      defect.getMessage should include("post-state root")
    }
    untouched(chain) {
      // a block that is not the next one
      an[IllegalArgumentException] should be thrownBy
        chain.state.commitCanonical(Some(executed.staged), executed.ref.copy(number = 7), None)
    }
    // the layer is good for its own block still
    executed.commit() shouldBe a[CommitOutcome.Applied]
    chain.disk shouldBe chain.rowsOf(executed.root)
  }

  "A block's own half of the batch" should "be refused when it is for another data source" taggedAs UnitTest in {
    val chain = new Chain(EphemDataSource())
    val executed = chain.execute(account(dave, 1, 1))
    val elsewhere = new AppStateStorage(EphemDataSource()).putBestBlockNumber(2)
    untouched(chain) {
      an[IllegalArgumentException] should be thrownBy
        chain.state.commitCanonical(Some(executed.staged), executed.ref, Some(elsewhere))
    }
  }

  // ─── One batch, under the lock, as a move of the clock ─────────────────────────────────────────────────────────────

  "The batch of a canonical commit" should "be one write, made under the lock while the clock says a move is in flight" taggedAs
    UnitTest in {
      val watched = new Watched(EphemDataSource())
      val chain = new Chain(watched)
      val executed = chain.execute(slots(alice, 1 -> 5), delete(carol), account(dave, 1, 1))
      val epoch = chain.state.clock.epoch
      val callsBefore = watched.callsMade.size
      var seen = 0
      watched.beforeUpdate = _ =>
        seen += 1
        chain.state.clock.epoch shouldBe epoch + 1
        chain.state.clock.atRest shouldBe None
        chain.state.lock.isHeldByCurrentThread shouldBe true
        chain.state.lock.holder.get should include(s"canonical commit of block ${executed.ref.show}")
        // the disk is still the parent's
        anchorOnDisk(watched.under) shouldBe Some(chain.first)
      executed.commit() shouldBe a[CommitOutcome.Applied]
      watched.beforeUpdate = _ => ()

      seen shouldBe 1
      chain.state.clock.epoch shouldBe epoch + 2
      chain.state.lock.holder shouldBe None
      // one write, and nothing asked of the data source after it: every read and scan came first
      val calls = watched.callsMade.drop(callsBefore)
      calls.count(_ == "update") shouldBe 1
      calls.last shouldBe "update"
      calls should contain("scan")
      // the anchor is the last thing in the batch
      val batch = watched.batchesWritten.last
      batch.last match
        case DataSourceUpdate(_, _, upserts) =>
          upserts.map((key, _) => new String(key.toArray, "UTF-8")) shouldBe Seq(StateAnchor.Key)
          new String(upserts.head._2.toArray, "UTF-8") shouldBe StateAnchor.encode(executed.ref)
        case other => fail(s"the batch ends on $other")
    }

  // vault: a write that fails wrote nothing, and is the storage's failure, not a verdict on the block.
  it should "leave nothing when the write fails, and say so as the storage's failure" taggedAs UnitTest in {
    val watched = new Watched(EphemDataSource())
    val chain = new Chain(watched)
    val held = chain.state.view(chain.first)
    val executed = chain.execute(slots(alice, 1 -> 5), delete(carol), account(dave, 1, 1))
    val epoch = chain.state.clock.epoch
    watched.beforeUpdate = _ => throw new java.io.IOException("the disk is full")
    untouched(chain) {
      val failure = the[java.io.IOException] thrownBy executed.commit()
      failure.getMessage shouldBe "the disk is full"
    }
    watched.beforeUpdate = _ => ()
    chain.records.get(executed.ref.hash) shouldBe None
    // the clock moved on and is at rest; the lock is free
    chain.state.clock.epoch shouldBe epoch + 2
    chain.state.clock.atRest shouldBe Some((epoch + 2, Some(chain.first)))
    chain.state.lock.holder shouldBe None
    // a view made before it still reads the state it was made of
    val root = PositionKey.AccountRoot
    held.read(root).map(ByteString(_)) shouldBe chain.disk._1.get(root.hp)
    // and the block commits on a second try, from a fresh layer
    val retried = chain.execute(slots(alice, 1 -> 5), delete(carol), account(dave, 1, 1))
    retried.commit() shouldBe a[CommitOutcome.Applied]
    chain.disk shouldBe chain.rowsOf(retried.root)
  }

  it should "report an index of records that cannot be read as corruption of the datadir, and write nothing" taggedAs
    UnitTest in {
      val chain = new Chain(EphemDataSource())
      val executed = chain.execute(account(dave, 1, 1))
      // the index of height 2 is not a list of 32-byte hashes
      chain.source.update(
        Seq(
          com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized(
            com.chipprbots.ethereum.db.storage.Namespaces.StateChangesetNamespace,
            Nil,
            Seq(ChangesetStorage.heightKey(2) -> Array.fill[Byte](33)(1))
          )
        )
      )
      untouched(chain) {
        val corrupt = the[IllegalStateException] thrownBy executed.commit()
        corrupt.getMessage should (include("corrupt").and(include(executed.ref.show)))
        corrupt should not be a[StateFault]
      }
      chain.state.clock.epoch shouldBe 0L
    }

  // vault: nothing runs under the state lock but the commit and the data source.
  "What a commit asks and tells outside itself" should "be asked before the lock is taken and told after it is free" taggedAs
    UnitTest in {
      var heldWhenAsked, heldWhenTold = Option.empty[Boolean]
      var told = Seq.empty[Commit]
      lazy val chain: Chain = new Chain(
        EphemDataSource(),
        CommitSettings(
          retention = new RetentionPolicy(
            StateConfig.Mode.Basic,
            (_, finalized) => if finalized.contains(BigInt(1)) then 2 else 9
          ),
          finalized = () =>
            heldWhenAsked = Some(chain.state.lock.holder.isDefined)
            Some(FinalizedBlock(BigInt(1), ByteString(Array.fill[Byte](32)(1))))
          ,
          observer = new CommitObserver:
            override def committed(commit: Commit): Unit =
              heldWhenTold = Some(chain.state.lock.holder.isDefined)
              told :+= commit
        )
      )
      val commits = (1 to 4).map(i => chain.advance(account(dave, i, i)))
      heldWhenAsked shouldBe Some(false)
      heldWhenTold shouldBe Some(false)
      told shouldBe commits
      // and what was asked is what retention went by: two blocks keep their record
      chain.appState.getChangesetPrunedTo() shouldBe Some(chain.head.number - 1)
    }

  it should "commit the block whatever its observer does with the news" taggedAs UnitTest in {
    val chain = new Chain(
      EphemDataSource(),
      CommitSettings(observer = new CommitObserver:
        override def lockWaited(nanos: Long): Unit = throw new IllegalStateException("a metric failed")
        override def committed(commit: Commit): Unit = throw new IllegalStateException("another metric failed")
      )
    )
    chain.advance(account(dave, 1, 1), slots(alice, 1 -> 1))
    chain.disk shouldBe chain.rowsOf(chain.head.stateRoot)
    chain.state.anchor shouldBe Some(chain.head)
    chain.state.lock.holder shouldBe None
  }

  it should "write nothing when the finalized block cannot be named" taggedAs UnitTest in {
    val chain = new Chain(EphemDataSource(), CommitSettings(finalized = () => throw new IllegalStateException("no")))
    val executed = chain.execute(account(dave, 1, 1))
    untouched(chain)(an[IllegalStateException] should be thrownBy executed.commit())
    chain.state.clock.epoch shouldBe 0L
    chain.state.lock.holder shouldBe None
  }

  // vault: the clock is in memory and the batch is one write, so a restart finds what the last commit left.
  "A datadir that is closed and opened again" should "hold the last committed block's state, and go on from it" taggedAs
    UnitTest in {
      onRocksDbReopened { source =>
        val chain = new Chain(source)
        threeBlocks.take(2).foreach(transactions => chain.advance(transactions*))
        (chain.hashed, chain.head, chain.disk)
      } { case (source, (hashed, head, rows)) =>
        val chain = new Chain(source, seeded = Some(hashed))
        chain.head = head
        chain.state.anchor shouldBe Some(head)
        chain.state.clock.epoch shouldBe 0L
        chain.disk shouldBe rows
        chain.disk shouldBe chain.rowsOf(head.stateRoot)
        chain.appState.getBestBlockInfo() shouldBe BlockInfo(head.hash, head.number)
        chain.record(head).postStateRoot shouldBe head.stateRoot
        chain.advance(threeBlocks(2)*)
        chain.disk shouldBe chain.rowsOf(chain.head.stateRoot)
        chain.reverse(chain.head)
        chain.disk shouldBe rows
      }
    }

  // ─── Readers across a commit ───────────────────────────────────────────────────────────────────────────────────────

  "A view made before a commit" should "go on reading the state it was made of, also where the block wrote" taggedAs
    UnitTest in {
      val chain = new Chain(EphemDataSource())
      val before = chain.disk
      val old = chain.state.readOnly(chain.first)
      val oldView = chain.state.view(chain.first)
      chain.advance(slots(alice, 1 -> 5), delete(carol), account(dave, 1, 1))
      chain.disk should not be before

      oldView.isAnchorViewNow shouldBe false
      // every row of the old state, account trie and storage, deleted ones included
      before._1.foreach((hp, node) => oldView.read(PositionKey(None, hp)).map(ByteString(_)) shouldBe Some(node))
      before._2.foreach { (key, node) =>
        val position = PositionKey(Some(key.take(32)), key.drop(32))
        withClue(position.show)(oldView.read(position).map(ByteString(_)) shouldBe Some(node))
      }
      // and the world over it is the old world
      val world = worldOver(old, chain.first.stateRoot, chain.hashed.codes)
      world.getAccount(dave) shouldBe chain.hashed.world(chain.first.stateRoot).getAccount(dave)
      world.getStorage(carol).load(otherSlots.head._1) shouldBe BigInt(otherSlots.head._2)
      // the new one is the anchor's
      worldOver(chain.state.readOnly(chain.head), chain.head.stateRoot, chain.hashed.codes)
        .getAccount(dave)
        .map(_.nonce.toBigInt) shouldBe Some(BigInt(1))
    }

  // Under the bound the old rows of the replaced trie are not listed, so what a reader of the parent's state must see
  // where the block wrote over them is read from the disk just before the write.
  it should "read the parent's rows where a block wrote over a trie it could not list" taggedAs UnitTest in {
    val chain = new Chain(EphemDataSource(), CommitSettings(maxEntriesPerOwnerDelete = 3))
    val before = chain.disk
    val oldView = chain.state.view(chain.first)
    chain.advance(
      delete(alice),
      account(alice, 0, 1).andThen(slots(alice, manySlots.take(30).map((k, v) => k -> (v + 1))*))
    )
    val overwritten =
      before._2.filter((key, node) => key.startsWith(owner(alice)) && chain.disk._2.get(key).exists(_ != node))
    overwritten should not be empty
    before._2.filter((key, _) => key.startsWith(owner(alice))).foreach { (key, node) =>
      oldView.read(PositionKey(Some(key.take(32)), key.drop(32))).map(ByteString(_)) shouldBe Some(node)
    }
  }

  it should "say it is stale once more moves have passed than the log keeps" taggedAs UnitTest in {
    val source = EphemDataSource()
    val chain = new Chain(source)
    val oldView = chain.state.view(chain.first)
    (1 to 8).foreach(i => chain.advance(account(dave, i, i)))
    oldView.read(PositionKey.AccountRoot).map(ByteString(_)) shouldBe
      chain.rowsOf(chain.first.stateRoot)._1.get(PositionKey.AccountRoot.hp)
    chain.advance(account(dave, 9, 9))
    a[StaleStateViewException] should be thrownBy oldView.read(PositionKey.AccountRoot)
  }

  // ─── Two writers (T086) ────────────────────────────────────────────────────────────────────────────────────────────

  /** `first` and `second` commit, in that order whatever the scheduler does: `second` asks for the lock while `first`
    * is inside its write, and a reader asks for a view at the same moment. Returns what each got.
    */
  private def raced[A](watched: Watched, chain: Chain)(
      first: => CommitOutcome,
      second: => A
  ): (CommitOutcome, Try[A], Try[StateView]) =
    val pool = Executors.newFixedThreadPool(3)
    given ExecutionContext = ExecutionContext.fromExecutor(pool)
    try
      val inWrite = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      watched.beforeUpdate = _ =>
        inWrite.countDown()
        release.await(30, TimeUnit.SECONDS) shouldBe true
      val one = Future(first)
      inWrite.await(30, TimeUnit.SECONDS) shouldBe true
      // the first writer is inside its write: from here no batch but its own may reach the disk
      watched.beforeUpdate = _ => ()
      val two = Future(Try(second))
      val readerThread = new java.util.concurrent.atomic.AtomicReference[Thread]()
      val reader = Future {
        readerThread.set(Thread.currentThread)
        Try(chain.state.view(chain.first))
      }
      // Both are known to be waiting before the first writer is let go: the second writer in the lock's queue, the
      // reader parked on the clock. Without that a reader that started late would find the move over, and pass.
      val deadline = System.nanoTime() + 30.seconds.toNanos
      def parked = Option(readerThread.get).exists(_.getState == Thread.State.WAITING)
      while (chain.state.lock.waiting == 0 || !parked) && System.nanoTime() < deadline && !two.isCompleted do
        Thread.onSpinWait()
      chain.state.lock.waiting shouldBe 1
      parked shouldBe true
      two.isCompleted shouldBe false
      reader.isCompleted shouldBe false
      release.countDown()
      (Await.result(one, 30.seconds), Await.result(two, 30.seconds), Await.result(reader, 30.seconds))
    finally pool.shutdownNow()

  "Two commits on one anchor" should "be written one after the other, and the one that lost the anchor refused" taggedAs
    UnitTest in {
      Seq(false, true).foreach { swapped =>
        val watched = new Watched(EphemDataSource())
        val chain = new Chain(watched)
        val one = chain.executeAs("a")(account(dave, 1, 1), slots(alice, 1 -> 1))
        val other = chain.executeAs("b")(delete(carol), slots(bob, 2 -> 2))
        val (winner, loser) = if swapped then (other, one) else (one, other)

        val (won, lost, view) = raced(watched, chain)(winner.commit(), loser.commit())
        won shouldBe a[CommitOutcome.Applied]
        lost.failed.get shouldBe a[StateUnavailable]
        // the disk is the winner's state and nothing of the loser's
        chain.disk shouldBe chain.rowsOf(winner.root)
        chain.state.anchor shouldBe Some(winner.ref)
        chain.appState.getBestBlockInfo() shouldBe BlockInfo(winner.ref.hash, winner.ref.number)
        chain.records.hashesAt(2) shouldBe Seq(winner.ref.hash)
        chain.records.get(loser.ref.hash) shouldBe None
        // the reader waited for the write, and was then told the truth: block 1 is no longer what the disk holds
        view.failed.get shouldBe a[StateUnavailable]
        chain.state.view(winner.ref).isAnchorViewNow shouldBe true
        chain.state.clock.epoch shouldBe 2L
      }
    }

  "Two commits of one block" should "apply it once: the second finds it applied and writes its own records only" taggedAs
    UnitTest in {
      val watched = new Watched(EphemDataSource())
      val chain = new Chain(watched)
      val one = chain.execute(account(dave, 1, 1), slots(alice, 1 -> 1))
      val two = chain.execute(account(dave, 1, 1), slots(alice, 1 -> 1))
      one.ref shouldBe two.ref
      val (won, second, _) = raced(watched, chain)(one.commit(), two.commit())
      won shouldBe a[CommitOutcome.Applied]
      second.get shouldBe CommitOutcome.AlreadyApplied
      chain.disk shouldBe chain.rowsOf(one.root)
      chain.records.hashesAt(2) shouldBe Seq(one.ref.hash)
      chain.state.clock.epoch shouldBe 2L
    }
