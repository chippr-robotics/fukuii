package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C4b part 2: the move of the head ([[PathStateStorage.moveHead]]). What a move leaves on disk is
  * held to two things that do not come from it: the rows the hash store's state has at the target's state root, and the
  * data source of a chain that committed the same blocks in place, one by one, records and all.
  */
class HeadMoveSpec extends AnyFlatSpec with Matchers with LayerChains with PlannedMoves:

  import HeadMovePlan.*
  import PathStateStorage.*
  import PathStateTestKit.*

  /** A chain on the unified layout, with the headers of its blocks by hash, which a move is planned from. */
  final private class Net(settings: CommitSettings = CommitSettings(), source: EphemDataSource = EphemDataSource()):
    val chain = new Chain(settings, source = source)
    val links: mutable.Map[ByteString, Link] = mutable.Map(chain.first.hash -> Link(chain.first, hashOf("block 0")))
    var asked: Vector[Chunk] = Vector.empty

    private def noted(block: chain.Executed): chain.Executed =
      links(block.ref.hash) = Link(block.ref, block.parent.hash)
      block

    /** A block committed in place: the head moves to it. */
    def inPlace(parent: BlockRef, transactions: Tx*): BlockRef =
      val block = noted(chain.on(parent)(transactions*))
      block.inPlace()
      block.ref

    /** A block executed on the head or on a layer above it, and kept as a layer. */
    def layered(parent: BlockRef, transactions: Tx*): BlockRef =
      val block = noted(chain.on(parent)(transactions*))
      block.layered()
      block.ref

    /** A block executed on a block behind the head, or on a fork, and kept as a layer. */
    def onFork(parent: BlockRef, transactions: Tx*): BlockRef =
      val block = noted(chain.onAny(parent)(transactions*))
      block.layered()
      block.ref

    /** The caller's half of each batch: the best-block pointer of the block the batch ends on. */
    def pointers(chunk: Chunk): Option[DataSourceBatchUpdate] =
      asked :+= chunk
      chain.pointer(chunk.head)

    def move(target: BlockRef, extraFor: Chunk => Option[DataSourceBatchUpdate] = pointers): MoveOutcome =
      chain.state.moveHead(target, links.get, extraFor)

    def moved(target: BlockRef): MoveOutcome.Moved = move(target) match
      case moved: MoveOutcome.Moved => moved
      case other                    => fail(s"not moved to ${target.show}: $other")

    def anchor: BlockRef = chain.state.anchor.get
    def best: ByteString = chain.appState.getBestBlockInfo().hash

    /** The disk holds exactly `ref`'s state, the anchor says so, and the best block is the anchor. */
    def holds(ref: BlockRef): Unit =
      withClue(s"the state of block ${ref.show}: ") {
        chain.disk shouldBe chain.rowsOf(ref.stateRoot)
        anchor shouldBe ref
        chain.appState.getStateAnchor() shouldBe Some(ref)
        best shouldBe ref.hash
      }

    /** Every hash an index of a height names has a record, and every one of `blocks` is named at its height. */
    def recordsOf(blocks: Iterable[BlockRef]): Map[ByteString, BlockDiff] =
      blocks.map { block =>
        withClue(s"the record of block ${block.show}: ") {
          chain.records.hashesAt(block.number) should contain(block.hash)
          block.hash -> chain.records.get(block.hash).flatMap(_.toOption).get
        }
      }.toMap

  /** The same blocks made on two chains: the one that commits them in place says what the other must hold. */
  private val branch: Seq[Seq[Tx]] = Seq(
    Seq(slots(alice, 1 -> 9, 2 -> 0), account(dave, 3, 3)),
    Seq(delete(bob), slots(carol, 7 -> 7)),
    Seq(slots(bob, (1 to 12).map(i => i -> (i + 40))*), account(eve, 1, 1).andThen(slots(eve, 5 -> 5))),
    // a contract destroyed and made again in one block: its trie is replaced, and the new one is the block's own
    Seq(delete(alice), slots(bob, 3 -> 0, 50 -> 1), delete(crowd(5)), delete(carol), slots(carol, 1 -> 1, 2 -> 2))
  )

  /** The bounds a batch is built within, each tight enough to be met by these blocks: a replaced trie with more rows
    * than may be listed (its block's record is a tombstone, and its old rows stay), and a record above its size.
    */
  private val bounds: Seq[(String, CommitSettings)] = Seq(
    "the node's bounds" -> CommitSettings(),
    "a replaced trie of more than 8 rows keeps them" -> CommitSettings(maxEntriesPerOwnerDelete = 8),
    "every record is above its size" -> CommitSettings(maxRecordBytes = 1L)
  )

  private def grown(from: BlockRef, blocks: Seq[Seq[Tx]])(make: (BlockRef, Seq[Tx]) => BlockRef): Vector[BlockRef] =
    blocks.foldLeft(Vector.empty[BlockRef])((done, txs) => done :+ make(done.lastOption.getOrElse(from), txs))

  "A move up by the layers of executed blocks" should "leave on disk what committing each of them in place leaves: rows, records, anchor and pointer" taggedAs
    (UnitTest, ConsensusTest) in {
      for
        (named, bound) <- bounds
        perBatch <- Seq(1, 2, 3, 256)
      do
        withClue(s"$named, $perBatch blocks to a batch: ") {
          val settings = bound.copy(maxBlocksPerMove = perBatch)
          val inPlace = new Net(settings)
          val committed = grown(inPlace.chain.first, fourBlocks)((p, txs) => inPlace.inPlace(p, txs*))
          val moved = new Net(settings)
          val layers = grown(moved.chain.first, fourBlocks)((p, txs) => moved.layered(p, txs*))
          layers shouldBe committed

          val outcome = moved.moved(layers.last)
          (outcome.from, outcome.to, outcome.fork) shouldBe (moved.chain.first, layers.last, moved.chain.first)
          (outcome.reversed, outcome.applied, outcome.recorded) shouldBe (0, 4, 4)
          outcome.batches shouldBe ((4 + perBatch - 1) / perBatch)
          moved.asked.map(_.head) shouldBe layers.grouped(perBatch).map(_.last).toVector

          moved.anchor shouldBe layers.last
          moved.best shouldBe layers.last.hash
          // (where a replaced trie keeps its old rows the disk holds more than the state: the chain that committed in
          // place says which)
          if bound == CommitSettings() then moved.holds(layers.last)
          // each block was settled against the state of the block before it, which inside one batch is on no disk: a
          // trie replaced in one block, made again in the next and replaced again in the third is listed right
          moved.recordsOf(layers) shouldBe inPlace.recordsOf(committed)
          moved.chain.everything shouldBe inPlace.chain.everything
          // the layers are gone, and nothing is built on them any more
          layers.foreach(block => moved.chain.state.forwardLayer(block.hash) shouldBe None)
        }
    }

  "A move to a sibling branch" should "reverse by the records and apply by the layers, in one batch, to exactly the branch's state" taggedAs
    (UnitTest, ConsensusTest) in {
      for
        // (a block that replaced a trie with too many rows to list cannot be staged on from behind, so no branch
        // stands beside it: that bound is met on the way up, above, and by the case after this one)
        (named, bound) <- bounds.take(1)
        perBatch <- Seq(1, 2, 256)
        // from the block the chain began on, and from the first block committed on it
        forkAt <- Seq(0, 1)
      do
        withClue(s"$named, $perBatch blocks to a batch, a fork $forkAt blocks up: ") {
          val settings = bound.copy(maxBlocksPerMove = perBatch)
          val net = new Net(settings)
          val ours = net.chain.first +: grown(net.chain.first, fourBlocks)((p, txs) => net.inPlace(p, txs*))
          val fork = ours(forkAt)
          val reversed = ours.drop(forkAt + 1)
          // A branch that touches what ours touched, and replaces and remakes tries ours replaced and remade: from the
          // first block, a trie that ours deleted is put back by the reverse and deleted again by a layer, in one batch.
          val theirs = grown(fork, branch)((p, txs) => net.onFork(p, txs*))
          val oursRecords = net.recordsOf(reversed)

          // a chain that committed that branch in place says what the move must write, and whether it can be reversed
          val direct = new Net(settings)
          val base = fourBlocks.take(forkAt).foldLeft(direct.chain.first)((parent, txs) => direct.inPlace(parent, txs*))
          base shouldBe fork
          val committed = grown(base, branch)((p, txs) => direct.inPlace(p, txs*))
          committed shouldBe theirs
          val tombstones = committed.filter(block => direct.recordsOf(Seq(block))(block.hash).incomplete)

          val oursTombstones = reversed.filter(block => oursRecords(block.hash).incomplete)
          if oursTombstones.nonEmpty then
            // a block of the old branch whose record lists nothing is not reversed: the move is refused whole
            val before = net.chain.everything
            (the[ReorgTooDeep] thrownBy net.move(theirs.last)).reason shouldBe ReorgRefusal.Incomplete
            net.chain.everything shouldBe before
          else
            val over = net.moved(theirs.last)
            (over.fork, over.reversed, over.applied, over.recorded) shouldBe (fork, reversed.size, 4, 4)
            net.anchor shouldBe theirs.last
            net.best shouldBe theirs.last.hash
            // the old branch's records stand: they are the way back
            net.recordsOf(reversed) shouldBe oursRecords
            // each block of the branch was settled against its own parent's state, as a commit in place settles it:
            // the same record, a tombstone where that is one, and the same rows left where a trie kept its old ones
            net.recordsOf(theirs) shouldBe direct.recordsOf(committed)
            net.chain.disk shouldBe direct.chain.disk

            if tombstones.isEmpty then
              net.holds(theirs.last)
              // and back, by records alone on both sides: nothing is executed, and nothing recorded anew
              val back = net.moved(ours.last)
              (back.fork, back.reversed, back.applied, back.recorded) shouldBe (fork, 4, reversed.size, 0)
              net.holds(ours.last)
              net.recordsOf(reversed ++ theirs) shouldBe oursRecords ++ direct.recordsOf(committed)
              // and over again, now from records
              net.moved(theirs.last).recorded shouldBe 0
              net.holds(theirs.last)
            else
              // a block whose record lists nothing is not reversed
              (the[ReorgTooDeep] thrownBy net.move(ours.last)).reason shouldBe ReorgRefusal.Incomplete
              net.anchor shouldBe theirs.last
        }
    }

  "A trie the old branch grew and the new branch replaces" should "be listed by the rows it has in the new block's parent, not by those on disk" taggedAs
    (UnitTest, ConsensusTest) in {
      // carol's storage trie as the chain begins, and a bound it is within; the old branch then grows it past the bound
      val atFirst = new Chain()
      val rowsAtFirst = atFirst.rowsOf(atFirst.first, owner(carol)).size
      def bytesOf(rows: Map[ByteString, ByteString]): Long =
        rows.iterator.map((key, node) => key.length.toLong + node.length).sum
      val bytesAtFirst = bytesOf(atFirst.rowsOf(atFirst.first, owner(carol)))
      val grow: Tx = slots(carol, (200 to 260).map(i => i -> i)*)
      val replace: Seq[Tx] = Seq(delete(carol), slots(carol, 1 -> 1))
      for
        // the bound on the rows of a replaced trie, and the bound on what they hold
        (bound, within) <- Seq[(CommitSettings, Map[ByteString, ByteString] => Boolean)](
          (CommitSettings(maxEntriesPerOwnerDelete = rowsAtFirst + 4), _.size <= rowsAtFirst + 4),
          (CommitSettings(maxOwnerScanBytes = bytesAtFirst + 200), bytesOf(_) <= bytesAtFirst + 200)
        )
        perBatch <- Seq(1, 256)
      do
        withClue(s"$perBatch blocks to a batch: ") {
          val settings = bound.copy(maxBlocksPerMove = perBatch)
          val net = new Net(settings)
          val grown = net.inPlace(net.chain.first, grow)
          within(net.chain.rowsOf(net.chain.first, owner(carol))) shouldBe true
          within(net.chain.rowsOf(grown, owner(carol))) shouldBe false
          val theirs = net.onFork(net.chain.first, replace*)

          // a commit of that block in place, on its own parent: the trie is within the bound there, and is listed
          val direct = new Net(settings)
          val committed = direct.inPlace(direct.chain.first, replace*)
          committed shouldBe theirs
          direct.recordsOf(Seq(committed))(committed.hash).incomplete shouldBe false

          net.moved(theirs).recorded shouldBe 1
          net.recordsOf(Seq(theirs)) shouldBe direct.recordsOf(Seq(committed))
          // no row of the old trie is left behind
          net.holds(theirs)
          // and the block can be reversed
          net.moved(grown)
          net.holds(grown)
        }

      // the other way: a trie the old branch shrank is within the bound on disk and above it in the parent's state
      for
        (bound, within) <- Seq[(CommitSettings, Map[ByteString, ByteString] => Boolean)](
          (CommitSettings(maxEntriesPerOwnerDelete = 6), _.size <= 6),
          (CommitSettings(maxOwnerScanBytes = 600), bytesOf(_) <= 600)
        )
        perBatch <- Seq(1, 256)
      do
        withClue(s"shrunk, $perBatch blocks to a batch: ") {
          val settings = bound.copy(maxBlocksPerMove = perBatch)
          val net = new Net(settings)
          // every slot of carol's set to nothing: her trie is gone, and a new one is two rows at most
          // (another state than the new branch's, so that the two are two blocks)
          val shrunk =
            net.inPlace(net.chain.first, slots(carol, otherSlots.map((k, _) => k -> 0)*), slots(carol, 1 -> 2))
          within(net.chain.rowsOf(net.chain.first, owner(carol))) shouldBe false
          within(net.chain.rowsOf(shrunk, owner(carol))) shouldBe true
          val theirs = net.onFork(net.chain.first, replace*)
          val direct = new Net(settings)
          val committed = direct.inPlace(direct.chain.first, replace*)
          committed shouldBe theirs
          // in place, on its parent, the trie has too many rows to list: a tombstone, and the old rows stay
          direct.recordsOf(Seq(committed))(committed.hash).incomplete shouldBe true
          net.moved(theirs)
          net.recordsOf(Seq(theirs)) shouldBe direct.recordsOf(Seq(committed))
          net.chain.disk shouldBe direct.chain.disk
        }
    }

  "A layer that stands on a block the move applies from its record" should "be there when the move comes to it, however the move is cut into batches" taggedAs
    (UnitTest, ConsensusTest) in {
      Seq(1, 2, 256).foreach { perBatch =>
        withClue(s"$perBatch blocks to a batch: ") {
          val net = new Net(CommitSettings(maxBlocksPerMove = perBatch))
          val ours = grown(net.chain.first, fourBlocks.take(2))((p, txs) => net.inPlace(p, txs*))
          val theirs = grown(ours(0), branch.take(3))((p, txs) => net.onFork(p, txs*))
          net.moved(theirs.last)
          // a block executed on the branch's head, kept for later; then the head goes back to the old branch
          val onTop = net.chain.on(theirs.last)(slots(carol, 77 -> 7))
          net.links(onTop.ref.hash) = Link(onTop.ref, theirs.last.hash)
          val staged = onTop.staged.changes.frozen
          net.moved(ours.last)
          // its layer, as an execution on a pinned branch would have left it: on a block that is now neither the head
          // nor behind it, and that the move will come to by records
          net.chain.state.lock.locked("a test's layer")(
            net.chain.state.overlay
              .register(ForwardLayer.of(onTop.ref, theirs.last, staged), None, ours.last, _ => false, None)
          )
          val moved = net.moved(onTop.ref)
          (moved.reversed, moved.applied, moved.recorded) shouldBe (1, 4, 1)
          net.holds(onTop.ref)
        }
      }
    }

  "A move down" should "leave the state of the ancestor, keep every record and prune nothing; and the way up again is by the records" taggedAs
    (UnitTest, ConsensusTest) in {
      val net = new Net(CommitSettings(retention = new RetentionPolicy(StateConfig.Mode.Basic, (_, _) => 4)))
      val ours = grown(net.chain.first, fourBlocks ++ branch)((p, txs) => net.inPlace(p, txs*))
      val prunedTo = net.chain.appState.getChangesetPrunedTo()
      prunedTo shouldBe Some(ours.last.number - 4 + 1)
      val kept = net.recordsOf(ours.takeRight(4))

      val down = net.moved(ours(5))
      (down.fork, down.reversed, down.applied) shouldBe (ours(5), 2, 0)
      net.holds(ours(5))
      net.chain.appState.getChangesetPrunedTo() shouldBe prunedTo
      net.recordsOf(ours.takeRight(4)) shouldBe kept

      val up = net.moved(ours.last)
      (up.fork, up.reversed, up.applied, up.recorded) shouldBe (ours(5), 0, 2, 0)
      net.holds(ours.last)
      net.chain.appState.getChangesetPrunedTo() shouldBe prunedTo
      net.recordsOf(ours.takeRight(4)) shouldBe kept

      // below what the records reach it is refused, and nothing is changed
      val before = net.chain.everything
      val refused = the[ReorgTooDeep] thrownBy net.move(ours(2))
      refused.reason shouldBe ReorgRefusal.Deep
      net.chain.everything shouldBe before
      net.chain.state.moveInProgress shouldBe false
    }

  "A move whose blocks are worth more than a batch may read" should "be cut into a batch for each, and leave what one batch would" taggedAs
    (UnitTest, ConsensusTest) in {
      // every record and every layer is worth more than a batch may read: one block a batch
      val net = new Net(CommitSettings(maxMoveBatchBytes = 1L))
      val ours = grown(net.chain.first, fourBlocks)((p, txs) => net.inPlace(p, txs*))
      val theirs = grown(ours(0), branch)((p, txs) => net.onFork(p, txs*))
      val over = net.moved(theirs.last)
      (over.reversed, over.applied, over.batches) shouldBe (3, 4, 7)
      net.asked.map(c => c.reverse.size + c.forward.size) shouldBe Vector.fill(7)(1)
      net.holds(theirs.last)
      // and what it wrote is what one batch writes
      val whole = new Net()
      val wholeOurs = grown(whole.chain.first, fourBlocks)((p, txs) => whole.inPlace(p, txs*))
      val wholeTheirs = grown(wholeOurs(0), branch)((p, txs) => whole.onFork(p, txs*))
      whole.moved(wholeTheirs.last).batches shouldBe 1
      net.chain.everything shouldBe whole.chain.everything
    }

  "A batch whose prior values are more than may be kept" should "be a barrier: every view made before it is stale, never wrong, and new ones read the new state" taggedAs
    (UnitTest, ConsensusTest) in {
      Seq(256, 1).foreach { perBatch =>
        withClue(s"$perBatch blocks to a batch: ") {
          val net = new Net(CommitSettings(maxMovePreBytes = 0L, maxBlocksPerMove = perBatch))
          val ours = grown(net.chain.first, fourBlocks)((p, txs) => net.inPlace(p, txs*))
          val theirs = grown(ours(0), branch)((p, txs) => net.onFork(p, txs*))
          val ofOldHead = net.chain.state.readOnly(ours.last)
          val behind = net.chain.state.readOnlyBehind(ours(1), PathStateStorage.Reader.Rpc)
          val onTop = net.layered(ours.last, slots(carol, 5 -> 5))
          val throughLayer = PathMptStorage.readOnly(net.chain.state.viewOf(onTop))
          net.moved(theirs.last)
          net.holds(theirs.last)
          // a view made before reads nothing it should not: the first row the move changed under it is stale
          a[StaleStateViewException] should be thrownBy net.chain.readThrough(ofOldHead, ours.last)
          a[StaleStateViewException] should be thrownBy net.chain.readThrough(behind, ours(1))
          a[StaleStateViewException] should be thrownBy net.chain.readThrough(throughLayer, onTop)
          // views made now read right
          net.chain.readThrough(net.chain.state.readOnly(theirs.last), theirs.last)
          net.chain.readThrough(net.chain.state.readOnlyBehind(ours(0), PathStateStorage.Reader.Rpc), ours(0))
          // a commit in place whose prior values are more than may be kept is a barrier too
          val ofNew = net.chain.state.readOnly(theirs.last)
          net.inPlace(theirs.last, slots(carol, 3 -> 3))
          a[StaleStateViewException] should be thrownBy net.chain.readThrough(ofNew, theirs.last)
        }
      }
    }

  "An attempt that holds the records above its fork" should "keep them from retention while it runs, so that the way back is there however long the branch it commits" taggedAs
    (UnitTest, ConsensusTest) in {
      def attempt(held: Boolean): Net =
        // two blocks keep their record, and a move may undo no more than two, as `reorg.max-depth = floor` makes it
        val net = new Net(
          CommitSettings(
            retention = new RetentionPolicy(StateConfig.Mode.Basic, (_, _) => 2),
            maxReorgDepth = (_, _) => 2
          )
        )
        val fork = net.inPlace(net.chain.first, slots(alice, 99 -> 1))
        val ours = grown(fork, fourBlocks.take(2))((p, txs) => net.inPlace(p, txs*))
        def reverseThenCommit(): Unit =
          // down to the fork, the branch committed in place block by block, then back to the old head
          net.moved(fork)
          val theirs = grown(fork, branch)((p, txs) => net.inPlace(p, txs*))
          net.anchor shouldBe theirs.last
          net.moved(ours.last)
        if held then
          // the way back reverses four blocks, which is more than the policy allows a move: it is the way back
          net.chain.state.withRecordsHeld(fork)(reverseThenCommit())
          net.holds(ours.last)
          // and once the hold is let go the policy is the policy again: three blocks down is one too many
          (the[ReorgTooDeep] thrownBy net.move(net.chain.first)).reason shouldBe ReorgRefusal.Deep
        else
          // four blocks of the branch committed with two kept: the old branch's records are pruned under it
          (the[ReorgTooDeep] thrownBy reverseThenCommit()).reason shouldBe ReorgRefusal.Deep
        net

      attempt(held = false)
      val net = attempt(held = true)
      // nothing above the fork was pruned while it ran; the next commit catches up
      val prunedTo = net.chain.appState.getChangesetPrunedTo().get
      prunedTo should be <= BigInt(3)
      val next = net.inPlace(net.anchor, slots(carol, 1 -> 2))
      net.chain.appState.getChangesetPrunedTo().get should be > prunedTo
      net.chain.appState.getChangesetPrunedTo() shouldBe Some(next.number - 2 + 1)

      // a hold above a block whose records above it are pruned already is refused at once: there is no way back
      val gone = (the[ReorgTooDeep] thrownBy net.chain.state.withRecordsHeld(net.chain.first)(fail("no attempt")))
      gone.reason shouldBe ReorgRefusal.Deep
      gone.getMessage should include("cannot be held")
    }

  "A move up of many blocks" should "prune no height it records, and leave no record without its index or index without its record" taggedAs
    (UnitTest, ConsensusTest) in {
      val net = new Net(CommitSettings(retention = new RetentionPolicy(StateConfig.Mode.Basic, (_, _) => 2)))
      val first = grown(net.chain.first, fourBlocks)((p, txs) => net.inPlace(p, txs*))
      val layers = grown(first.last, branch)((p, txs) => net.layered(p, txs*))
      net.moved(layers.last).recorded shouldBe 4
      net.holds(layers.last)
      // every block the batch recorded has its record, though retention keeps two: the batch pruned below them only
      net.recordsOf(layers) should have size 4
      net.chain.appState.getChangesetPrunedTo() shouldBe Some(layers.head.number)
      (BigInt(1) to layers.last.number).foreach { height =>
        withClue(s"height $height: ") {
          val named = net.chain.records.hashesAt(height)
          named.foreach(hash => net.chain.records.get(hash) should not be empty)
          if height < layers.head.number then named shouldBe empty
        }
      }
      // the next commit catches up
      val next = net.inPlace(layers.last, slots(carol, 1 -> 1))
      net.chain.appState.getChangesetPrunedTo() shouldBe Some(next.number - 2 + 1)
    }

  "A move asked for from inside a move" should "be refused: the caller's rows are made, and the head is not moved by them" taggedAs
    UnitTest in {
      val net = new Net()
      val head = net.inPlace(net.chain.first, fourBlocks.head*)
      val started = net.chain.state.movesStarted
      an[IllegalStateException] should be thrownBy net.move(
        net.chain.first,
        chunk =>
          net.chain.state.moveHead(head, net.links.get, net.pointers)
          net.pointers(chunk)
      )
      net.chain.state.moveInProgress shouldBe false
      net.holds(head)
      // a move is counted, for who builds on the head and must know one began meanwhile, once it writes: one refused
      // before any write moved nothing, and is not (B-249)
      net.chain.state.movesStarted shouldBe started
      net.moved(net.chain.first)
      net.chain.state.movesStarted shouldBe started + 1
    }

  "A move to the block the disk holds" should "write the caller's rows and nothing else" taggedAs UnitTest in {
    val net = new Net()
    val head = net.inPlace(net.chain.first, fourBlocks.head*)
    val epoch = net.chain.state.clock.epoch
    val before = net.chain.everything
    net.move(head) shouldBe MoveOutcome.AlreadyThere
    net.asked shouldBe Vector(Chunk(head, head, Vector.empty, Vector.empty))
    net.chain.everything shouldBe before
    net.chain.state.clock.epoch shouldBe epoch
    // the rows are written: a pointer to another block shows
    net.move(head, _ => net.chain.pointer(net.chain.first)) shouldBe MoveOutcome.AlreadyThere
    net.best shouldBe net.chain.first.hash
  }

  "A reader" should "go on reading the state it was given across a move, and a new one read the new state" taggedAs
    (UnitTest, ConsensusTest) in {
      // one batch, and seven: what a view is given back of the old state is then pieced together from seven moves
      Seq(256, 1).foreach { perBatch =>
        val net = new Net(CommitSettings(maxBlocksPerMove = perBatch))
        val ours = grown(net.chain.first, fourBlocks)((p, txs) => net.inPlace(p, txs*))
        val theirs = grown(ours(0), branch)((p, txs) => net.onFork(p, txs*))
        readersAcross(net, ours, theirs)
      }
    }

  private def readersAcross(net: Net, ours: Vector[BlockRef], theirs: Vector[BlockRef]): Unit =
    val ofOldHead = net.chain.state.readOnly(ours.last)
    val behind = net.chain.state.readOnlyBehind(ours(1), PathStateStorage.Reader.Rpc)
    val ofTarget = PathMptStorage.readOnly(net.chain.state.viewOf(theirs.last))
    net.moved(theirs.last)
    net.chain.readThrough(ofOldHead, ours.last)
    net.chain.readThrough(behind, ours(1))
    net.chain.readThrough(ofTarget, theirs.last)
    net.chain.readThrough(net.chain.state.readOnly(theirs.last), theirs.last)
    // the fork point is behind the new head too
    net.chain.readThrough(net.chain.state.readOnlyBehind(ours(0), PathStateStorage.Reader.Rpc), ours(0))

  "A move in batches" should "leave the head, and the caller's pointer with it, on a block of its way after each batch; and say it is under way throughout" taggedAs
    (UnitTest, ConsensusTest) in {
      val net = new Net(CommitSettings(maxBlocksPerMove = 2))
      val ours = grown(net.chain.first, fourBlocks)((p, txs) => net.inPlace(p, txs*))
      val theirs = grown(ours(0), branch)((p, txs) => net.onFork(p, txs*))
      val way = Vector(ours(3), ours(2), ours(1), ours(0)) ++ theirs
      var seen = Vector.empty[(BlockRef, ByteString, Boolean)]
      net.chain.state.moveInProgress shouldBe false
      val outcome = net.move(
        theirs.last,
        chunk =>
          // between two batches: the state lock is free, and the disk holds a whole state
          seen :+= (net.anchor, net.best, net.chain.state.moveInProgress)
          net.chain.state.lock.holder shouldBe None
          net.chain.disk shouldBe net.chain.rowsOf(net.anchor.stateRoot)
          net.pointers(chunk)
      )
      outcome match
        case moved: MoveOutcome.Moved => moved.batches shouldBe 4
        case other                    => fail(s"not moved: $other")
      net.chain.state.moveInProgress shouldBe false
      // three reversed and four applied, two to a batch: the second batch spans the fork
      net.asked.map(c => (c.reverse.size, c.forward.size)) shouldBe Vector((2, 0), (1, 1), (0, 2), (0, 1))
      seen.map(_._1) shouldBe Vector(ours(3), ours(1), theirs(0), theirs(2))
      seen.foreach { (anchor, best, moving) =>
        way should contain(anchor)
        best shouldBe anchor.hash
        moving shouldBe true
      }
      net.holds(theirs.last)
    }

  it should "stop where a batch fails, with the head on the block the batch before it ended on, and go on from there when asked again" taggedAs
    (UnitTest, ConsensusTest) in {
      val source = new Failing
      val net = new Net(CommitSettings(maxBlocksPerMove = 2), source)
      val ours = grown(net.chain.first, fourBlocks)((p, txs) => net.inPlace(p, txs*))
      val theirs = grown(ours(0), branch)((p, txs) => net.onFork(p, txs*))
      var batches = 0
      an[java.io.IOException] should be thrownBy net.move(
        theirs.last,
        chunk =>
          batches += 1
          // the third batch's write fails
          source.fails = batches == 3
          net.pointers(chunk)
      )
      source.fails = false
      net.chain.state.moveInProgress shouldBe false
      // two batches stand: the head is the first block of the new branch, and it is a whole state
      net.holds(theirs(0))
      // the layers of the blocks that were not applied are still there
      theirs.drop(1).foreach(block => net.chain.state.forwardLayer(block.hash) should not be empty)
      // and that of the block that was applied is not
      net.chain.state.forwardLayer(theirs(0).hash) shouldBe None

      // A node that stopped there and starts again holds that state, and none of the layers: the blocks above are not
      // held, and the way back to the old head is by the records.
      val settings = CommitSettings(maxBlocksPerMove = 2)
      val restarted = new PathStateStorage(net.chain.rows, net.chain.appState, settings = settings)
      restarted.anchor shouldBe Some(theirs(0))
      val notHeld = the[StateUnavailable] thrownBy restarted.moveHead(theirs.last, net.links.get, net.pointers)
      notHeld.heldTo shouldBe Some(theirs(0))
      restarted.moveHead(ours.last, net.links.get, net.pointers) match
        case moved: MoveOutcome.Moved => (moved.reversed, moved.applied, moved.recorded) shouldBe (1, 3, 0)
        case other                    => fail(s"not moved: $other")
      net.chain.disk shouldBe net.chain.rowsOf(ours.last.stateRoot)
      restarted.anchor shouldBe Some(ours.last)
      net.best shouldBe ours.last.hash
    }

  it should "stop where it is when a layer a later batch applies is dropped between two batches, and say to ask again" taggedAs
    (UnitTest, ConsensusTest) in {
      val net = new Net(CommitSettings(maxBlocksPerMove = 1))
      val head = net.inPlace(net.chain.first, fourBlocks.head*)
      val layers = grown(head, branch)((p, txs) => net.layered(p, txs*))
      var batches = 0
      val stopped = the[StateUnavailable] thrownBy net.move(
        layers.last,
        chunk =>
          batches += 1
          // the third block's execution is found not to stand while the second batch is being asked for: the
          // second batch applies a layer that stands, and the third finds its own gone
          if batches == 2 then net.chain.state.dropLayer(layers(2).hash)
          net.pointers(chunk)
      )
      batches shouldBe 3
      stopped.askAgain shouldBe true
      stopped.getMessage should include("stopped at")
      // the first two batches stand, and the third was not written
      net.holds(layers(1))
      (2 to 3).foreach(n => net.chain.state.forwardLayer(layers(n).hash) shouldBe None)
      // asked again, the move says how far it can go now: no further than where it stopped
      (the[StateUnavailable] thrownBy net.move(layers.last)).heldTo shouldBe Some(layers(1))
      net.holds(layers(1))
    }

  "The layers a move applies" should "be held against eviction until its last batch, and be let go after it" taggedAs
    (UnitTest, ConsensusTest) in {
      // room for four executed blocks that no branch holds
      val net = new Net(CommitSettings(maxBlocksPerMove = 1, maxForwardLayers = 4))
      val head = net.inPlace(net.chain.first, fourBlocks.head*)
      val layers = grown(head, branch)((p, txs) => net.layered(p, txs*))
      var batches = 0
      val moved = net.move(
        layers.last,
        chunk =>
          batches += 1
          // between two batches other blocks are executed and kept, more than there is room for: what goes to make
          // room is never a block of the move
          if batches == 2 then
            (1 to 3).foreach { n =>
              val other = net.chain.on(net.anchor)(slots(carol, (90 + n) -> n))
              net.chain.state.commitOverlay(other.staged, other.ref)
            }
            layers.drop(1).foreach(block => net.chain.state.forwardLayer(block.hash) should not be empty)
            // (the layer of the block the first batch applied goes with the move's last batch, as the others do)
            net.chain.state.overlayUsage.pinnedLayers shouldBe 4
          net.pointers(chunk)
      )
      moved match
        case done: MoveOutcome.Moved => done.applied shouldBe 4
        case other                   => fail(s"not moved: $other")
      net.holds(layers.last)
      // and nothing is held for it now
      net.chain.state.overlayUsage.pinnedLayers shouldBe 0
      // nor after a move that failed
      val again = net.inPlace(layers.last, slots(carol, 1 -> 2))
      val more = grown(again, branch.take(2))((p, txs) => net.layered(p, txs*))
      an[IllegalStateException] should be thrownBy net.move(more.last, _ => throw new IllegalStateException("no"))
      net.chain.state.overlayUsage.pinnedLayers shouldBe 0
    }

  "A move" should "be told: what it moved when it is made, and why when it is refused" taggedAs UnitTest in {
    var told = Vector.empty[Any]
    val observer = new CommitObserver:
      override def moved(move: MoveOutcome.Moved): Unit = told :+= move
      override def moveRefused(reason: ReorgRefusal): Unit = told :+= reason
    val net =
      new Net(CommitSettings(observer = observer, retention = new RetentionPolicy(StateConfig.Mode.Basic, (_, _) => 2)))
    val ours = grown(net.chain.first, fourBlocks)((p, txs) => net.inPlace(p, txs*))
    val moved = net.moved(ours(2))
    told shouldBe Vector(moved)
    an[ReorgTooDeep] should be thrownBy net.move(net.chain.first)
    told.last shouldBe ReorgRefusal.Deep
    // a move to the head is no move, and is not told
    net.move(ours(2)) shouldBe MoveOutcome.AlreadyThere
    told should have size 2
  }

  "A move whose write fails" should "change nothing: the anchor, the layers and the rows stand, and the move can be made again" taggedAs
    (UnitTest, ConsensusTest) in {
      val source = new Failing
      val net = new Net(source = source)
      val ours = grown(net.chain.first, fourBlocks)((p, txs) => net.inPlace(p, txs*))
      val theirs = grown(ours(0), branch)((p, txs) => net.onFork(p, txs*))
      val before = net.chain.everything
      val epoch = net.chain.state.clock.epoch
      val view = net.chain.state.readOnly(ours.last)
      source.fails = true
      an[java.io.IOException] should be thrownBy net.move(theirs.last)
      source.fails = false
      net.chain.everything shouldBe before
      net.anchor shouldBe ours.last
      theirs.foreach(block => net.chain.state.forwardLayer(block.hash) should not be empty)
      // a move was announced and did not happen: the epoch has moved on, and a view made before it still reads right
      net.chain.state.clock.epoch shouldBe epoch + 2
      net.chain.readThrough(view, ours.last)
      net.chain.state.moveInProgress shouldBe false
      net.moved(theirs.last)
      net.holds(theirs.last)

      // and a caller whose rows cannot be made: nothing was asked of the disk
      val again = net.chain.everything
      an[IllegalStateException] should be thrownBy net.move(ours.last, _ => throw new IllegalStateException("no"))
      net.chain.everything shouldBe again
      net.chain.state.moveInProgress shouldBe false
      net.holds(theirs.last)
    }

  "A record that does not describe the disk" should "refuse the move before anything is written, as this datadir's corruption" taggedAs
    (UnitTest, ConsensusTest) in {
      def damaged(damage: (Net, Vector[BlockRef]) => Unit, target: Vector[BlockRef] => BlockRef): Unit =
        val net = new Net()
        val ours = grown(net.chain.first, fourBlocks)((p, txs) => net.inPlace(p, txs*))
        damage(net, ours)
        val before = net.chain.everything
        val epoch = net.chain.state.clock.epoch
        val refused = the[ReorgTooDeep] thrownBy net.move(target(ours))
        refused.reason shouldBe ReorgRefusal.Corrupt
        net.chain.everything shouldBe before
        net.chain.state.clock.epoch shouldBe epoch
        net.anchor shouldBe ours.last
        net.chain.state.moveInProgress shouldBe false

      def rewritten(net: Net, block: BlockRef)(change: BlockDiff => BlockDiff): Unit =
        val record = net.chain.records.get(block.hash).flatMap(_.toOption).get
        net.chain.state.lock.locked("a test's damage")(net.chain.records.put(change(record)))

      // a position of the head's record, not the account trie's root, whose two sides both hold a node
      def interior(record: BlockDiff): PositionKey =
        record.changes.collectFirst {
          case (position, Change(Some(_), Some(_))) if position != PositionKey.AccountRoot => position
        }.get

      withClue("what the head's record says it left at a position is not what is there: ") {
        damaged(
          (net, ours) =>
            rewritten(net, ours.last) { record =>
              val position = interior(record)
              val change = record.changes(position)
              // another node than the one the block wrote there
              val other = record.changes(PositionKey.AccountRoot).next
              other should not be change.next
              record.copy(changes = record.changes.updated(position, change.copy(next = other)))
            },
          _(2)
        )
      }
      withClue("a row the head's block wrote was changed on disk since: ") {
        damaged(
          (net, ours) =>
            val record = net.chain.records.get(ours.last.hash).flatMap(_.toOption).get
            val position = interior(record)
            net.chain.source.update(net.chain.rows.rowUpdates(Seq(position -> record.changes(position).prior)))
          ,
          _(2)
        )
      }
      withClue("the records reversed do not leave the target's state root: ") {
        damaged(
          (net, ours) =>
            rewritten(net, ours.last) { record =>
              val root = record.changes(PositionKey.AccountRoot)
              val other = record.changes(interior(record)).prior
              record.copy(changes = record.changes.updated(PositionKey.AccountRoot, root.copy(prior = other)))
            },
          _(2)
        )
      }
    }

  it should "refuse the way back too: a record applied again is held to the rows it was applied over" taggedAs
    (UnitTest, ConsensusTest) in {
      val net = new Net()
      val ours = grown(net.chain.first, fourBlocks)((p, txs) => net.inPlace(p, txs*))
      val theirs = grown(ours(0), branch)((p, txs) => net.onFork(p, txs*))
      net.moved(theirs.last)
      // the record of a block of the old branch says it was applied over another node than its parent's state holds
      val record = net.chain.records.get(ours(1).hash).flatMap(_.toOption).get
      val position = record.changes.collectFirst {
        case (position, Change(Some(_), Some(_))) if position != PositionKey.AccountRoot => position
      }.get
      val other = record.changes(PositionKey.AccountRoot).next
      val damaged =
        record.copy(changes = record.changes.updated(position, record.changes(position).copy(prior = other)))
      net.chain.state.lock.locked("a test's damage")(net.chain.records.put(damaged))
      val before = net.chain.everything
      (the[ReorgTooDeep] thrownBy net.move(ours.last)).reason shouldBe ReorgRefusal.Corrupt
      net.chain.everything shouldBe before
      net.anchor shouldBe theirs.last
    }

  it should "be met under the lock as well: a record that became a tombstone after the plan read its header" taggedAs
    (UnitTest, ConsensusTest) in {
      val net = new Net()
      val ours = grown(net.chain.first, fourBlocks)((p, txs) => net.inPlace(p, txs*))
      val record = net.chain.records.get(ours.last.hash).flatMap(_.toOption).get
      var before = Map.empty[ByteString, ByteString]
      val refused = the[ReorgTooDeep] thrownBy net.move(
        ours(2),
        chunk =>
          net.chain.state.lock.locked("a test's damage")(
            net.chain.records.put(record.copy(changes = Map.empty, incomplete = true))
          )
          before = net.chain.everything
          net.pointers(chunk)
      )
      refused.reason shouldBe ReorgRefusal.Corrupt
      // it says which record, and not only that the state did not come out right
      refused.getMessage should (include(ours.last.show).and(include("lists nothing")))
      net.chain.everything shouldBe before
      net.anchor shouldBe ours.last
    }

  "A row the move leaves as it was" should "not be written: a branch that leaves a trie as the old one left it writes none of its rows" taggedAs
    (UnitTest, ConsensusTest) in {
      // an in-memory source that tells which rows a batch writes with the value they already hold
      final class Rewrites extends EphemDataSource(Map.empty):
        @volatile var same: Vector[ByteString] = Vector.empty
        @volatile var rows: Vector[ByteString] = Vector.empty
        override def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
          val rowNamespaces = Set(Namespaces.StateTriePathNamespace, Namespaces.StorageTriePathNamespace)
          dataSourceUpdates.foreach {
            case written: DataSourceUpdateOptimized if rowNamespaces(written.namespace) =>
              written.toUpsert.foreach { (key, node) =>
                rows :+= ByteString(key)
                if getOptimized(written.namespace, key).exists(_.sameElements(node)) then same :+= ByteString(key)
              }
            case _ => ()
          }
          super.update(dataSourceUpdates)
      val source = new Rewrites
      val net = new Net(source = source)
      // both branches write the same slots of alice's: her storage trie is the same at either head
      val alike: Tx = slots(alice, 41 -> 1, 42 -> 2)
      val ours = grown(net.chain.first, Seq(Seq(alike, account(dave, 1, 1))))((p, txs) => net.inPlace(p, txs*))
      val theirs = grown(net.chain.first, Seq(Seq(alike, account(dave, 2, 2))))((p, txs) => net.onFork(p, txs*))
      val alices = owner(alice)
      net.chain.rowsOf(ours.last, alices) shouldBe net.chain.rowsOf(theirs.last, alices)
      net.chain.rowsOf(ours.last, alices) should not be net.chain.rowsOf(net.chain.first, alices)
      source.rows = Vector.empty
      source.same = Vector.empty
      net.moved(theirs.last)
      net.holds(theirs.last)
      source.rows should not be empty
      source.same shouldBe empty
      // her rows were reversed and applied again inside the batch, and none of them reached the disk
      source.rows.filter(_.startsWith(alices)) shouldBe empty
    }

  "A layer that was not built on its parent's state" should "be dropped and the move refused, with nothing written" taggedAs
    (UnitTest, ConsensusTest) in {
      val net = new Net()
      val head = net.inPlace(net.chain.first, fourBlocks.head*)
      val good = net.layered(head, slots(alice, 41 -> 1))
      // a layer registered for a block on `good` that names a prior the state of `good` does not hold
      val wrong = net.chain.on(good)(slots(alice, 42 -> 2))
      val ref = wrong.ref
      net.links(ref.hash) = Link(ref, good.hash)
      val staged = wrong.staged.changes.frozen
      val position = staged.changes.keys.find(_ != PositionKey.AccountRoot).get
      val defect = staged.copy(changes =
        staged.changes.updated(position, staged.changes(position).copy(prior = Some(ByteString("not the row"))))
      )
      net.chain.state.lock.locked("a test's layer")(
        net.chain.state.overlay.register(ForwardLayer.of(ref, good, defect), None, head, _ => false, None)
      )
      val before = net.chain.everything
      val refused = the[StateUnavailable] thrownBy net.move(ref)
      refused.getMessage should include("was not built on the state the disk holds")
      net.chain.everything shouldBe before
      net.anchor shouldBe head
      net.chain.state.forwardLayer(ref.hash) shouldBe None
      // the layer below it stands, and a move to it is made
      net.moved(good)
      net.holds(good)
    }

  "A block on the way that has no state" should "refuse the move and say how far one could be made" taggedAs
    (UnitTest, ConsensusTest) in {
      val net = new Net()
      val head = net.inPlace(net.chain.first, fourBlocks.head*)
      val held = net.layered(head, slots(alice, 41 -> 1))
      val gap = net.chain.on(held)(slots(alice, 42 -> 2))
      net.links(gap.ref.hash) = Link(gap.ref, held.hash)
      val before = net.chain.everything
      val refused = the[StateUnavailable] thrownBy net.move(gap.ref)
      refused.heldTo shouldBe Some(held)
      refused.askAgain shouldBe false
      net.chain.everything shouldBe before
      net.asked shouldBe empty
      // what it says is held can be moved to
      net.moved(held)
      net.holds(held)
    }

  "A plan the state changed under" should "be made again: a layer dropped, or a block committed, between the plan and the batch" taggedAs
    (UnitTest, ConsensusTest) in {
      // a block is committed in place after the plan was made: the move is planned again, from the new head
      val net = new Net()
      val head = net.inPlace(net.chain.first, fourBlocks.head*)
      val target = net.onFork(net.chain.first, branch.head*)
      val late = net.chain.on(head)(slots(carol, 9 -> 9))
      net.links(late.ref.hash) = Link(late.ref, head.hash)
      var calls = 0
      val outcome = net.move(
        target,
        chunk =>
          calls += 1
          if calls == 1 then late.inPlace()
          net.pointers(chunk)
      )
      outcome match
        case moved: MoveOutcome.Moved => (moved.from, moved.reversed, moved.applied) shouldBe (late.ref, 2, 1)
        case other                    => fail(s"not moved: $other")
      calls shouldBe 2
      net.holds(target)

      // the epoch moves and the head does not (a write that failed): the plan was of another epoch, and is made again
      val still = new Net()
      val top = still.inPlace(still.chain.first, fourBlocks.head*)
      val across = still.onFork(still.chain.first, branch.head*)
      var asks = 0
      still.move(
        across,
        chunk =>
          asks += 1
          if asks == 1 then still.chain.state.clock.inFlight(StateMove(top, Map.empty))(())
          still.pointers(chunk)
      )
      asks shouldBe 2
      still.holds(across)

      // a layer of the plan is dropped as invalid: the move is planned again, and then the block is not held
      val other = new Net()
      val base = other.inPlace(other.chain.first, fourBlocks.head*)
      val first = other.layered(base, slots(alice, 41 -> 1))
      val second = other.layered(first, slots(alice, 42 -> 2))
      val before = other.chain.everything
      val refused = the[StateUnavailable] thrownBy other.move(
        second,
        chunk =>
          other.chain.state.dropLayer(second.hash)
          other.pointers(chunk)
      )
      refused.heldTo shouldBe Some(first)
      other.chain.everything shouldBe before
      other.anchor shouldBe base

      // a state that changes under every plan: the move gives up, and says to ask again
      val restless = new Net()
      var tip = restless.inPlace(restless.chain.first, fourBlocks.head*)
      val aim = restless.onFork(restless.chain.first, branch.head*)
      var n = 0
      val gaveUp = the[StateUnavailable] thrownBy restless.move(
        aim,
        chunk =>
          n += 1
          tip = restless.inPlace(tip, slots(carol, n -> n))
          restless.pointers(chunk)
      )
      gaveUp.askAgain shouldBe true
      n shouldBe 4
      restless.anchor shouldBe tip
      restless.chain.state.moveInProgress shouldBe false
    }

  "A block committed while a move is under way" should "wait for the move to end, and then be judged against the head it left" taggedAs
    (UnitTest, ConsensusTest) in {
      val net = new Net(CommitSettings(maxBlocksPerMove = 2))
      val ours = grown(net.chain.first, fourBlocks)((p, txs) => net.inPlace(p, txs*))
      val theirs = grown(ours(0), branch)((p, txs) => net.onFork(p, txs*))
      // a block that extends the block the first batch ends on: between two batches it would be adopted, and the move
      // would find the head off its way
      val onTheWay = net.chain.onAny(ours(1))(slots(carol, 9 -> 9))
      val committer = new java.util.concurrent.atomic.AtomicReference[Thread]()
      val result = new java.util.concurrent.atomic.AtomicReference[scala.util.Try[CommitOutcome]]()
      var batches = 0
      net.move(
        theirs.last,
        chunk =>
          batches += 1
          if batches == 2 then
            net.anchor shouldBe ours(1)
            val thread = new Thread(() => result.set(scala.util.Try(onTheWay.inPlace()).map(CommitOutcome.Applied(_))))
            committer.set(thread)
            thread.start()
            // it has seen the move under way, let the state lock go, and waits for the move's end
            val deadline = System.nanoTime() + 30000000000L
            while thread.getState != Thread.State.WAITING && System.nanoTime() < deadline do Thread.onSpinWait()
            thread.getState shouldBe Thread.State.WAITING
            // on the move, not in the state lock's queue: nobody holds that lock and nobody waits for it
            net.chain.state.moveInProgress shouldBe true
            net.chain.state.lock.holder shouldBe None
            net.chain.state.lock.waiting shouldBe 0
            result.get shouldBe null
          net.pointers(chunk)
      )
      committer.get.join(30000)
      net.holds(theirs.last)
      // it was not adopted: the head is no longer the block it was executed on
      result.get.isFailure shouldBe true
      result.get.failed.get shouldBe a[StateUnavailable]
    }

  "A store that wrote other bytes than it was given" should "take no further write, by a move or by a commit" taggedAs
    (UnitTest, ConsensusTest) in {
      // an in-memory source that, when told to, stores every row of a batch with its last byte flipped
      final class Mangling extends EphemDataSource(Map.empty):
        @volatile var mangles = false
        override def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
          val rowNamespaces = Set(Namespaces.StateTriePathNamespace, Namespaces.StorageTriePathNamespace)
          super.update(
            if !mangles then dataSourceUpdates
            else
              dataSourceUpdates.map {
                case rows: DataSourceUpdateOptimized if rowNamespaces(rows.namespace) =>
                  rows.copy(toUpsert = rows.toUpsert.map { (key, node) =>
                    key -> node.updated(node.length - 1, (node.last ^ 1).toByte)
                  })
                case other => other
              }
          )
          mangles = false
      val source = new Mangling
      val net = new Net(source = source)
      val head = net.inPlace(net.chain.first, fourBlocks.head*)
      val layers = grown(head, branch)((p, txs) => net.layered(p, txs*))
      // a block executed before the store went wrong, for a commit after it
      val spare = net.chain.on(layers.last)(slots(carol, 1 -> 1))
      source.mangles = true
      val failed = the[StateUnavailable] thrownBy net.move(layers.last)
      failed.getMessage should include("takes no write")
      // what is kept in memory followed the disk before the move said so: the layers that were applied are gone
      layers.foreach(block => net.chain.state.forwardLayer(block.hash) shouldBe None)
      // no state is given out: a block executed on such a store could be judged invalid
      (the[StateUnavailable] thrownBy net.chain.state.view(layers.last)).getMessage should include("is not read")
      (the[StateUnavailable] thrownBy net.chain.state.viewOf(head)).getMessage should include("is not read")
      (the[StateUnavailable] thrownBy net.chain.state.commitOverlay(spare.staged, spare.ref)).getMessage should
        include("is not read")
      // nothing more is written to it
      val after = net.chain.everything
      (the[StateUnavailable] thrownBy net.move(head) should have).message(failed.getMessage)
      net.anchor shouldBe layers.last
      (the[StateUnavailable] thrownBy net.chain.state.commitCanonical(
        Some(spare.staged),
        spare.ref,
        None
      )).getMessage should include("takes no write")
      (the[StateUnavailable] thrownBy net.chain.state.promote(spare.ref, None)).getMessage should include(
        "takes no write"
      )
      net.chain.everything shouldBe after

      // the promotion of one block is the same batch, and fails the same way
      val other = new Mangling
      val promoted = new Net(source = other)
      val block = promoted.layered(promoted.chain.first, fourBlocks.head*)
      other.mangles = true
      (the[StateUnavailable] thrownBy promoted.chain.state.promote(
        block,
        promoted.chain.pointer(block)
      )).getMessage should
        include("is not read")
      promoted.anchor shouldBe block
      promoted.chain.state.forwardLayer(block.hash) shouldBe None
    }
