package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.CorruptNodeException
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C4a part 1: forward layers. An executed block whose state is on no row is kept as a layer, read
  * through by the views of its descendants, and promoted to the disk when it is adopted.
  *
  * Two datadirs are given the same blocks. On one each block is committed in place, which C3b holds to a hash store. On
  * the other each is executed on the layers of the blocks before it and kept as a layer. What a view over the layers
  * reads must be what the first datadir holds, position by position; and when the layers are promoted, block by block,
  * the second datadir must be the first, key for key: rows, changeset records, their index, the anchor and the head.
  */
class ForwardLayerSpec extends AnyFlatSpec with Matchers with LayerChains:

  import PathStateStorage.*
  import PathStateTestKit.*

  /** The blocks, kept as layers on one datadir and committed in place on another: read through the layers, then
    * promoted, the first is the second.
    */
  private def sameAsInPlace(settings: CommitSettings, blocks: Seq[Seq[Tx]]): Seq[Commit] =
    val layers = new Chain(settings)
    val inPlace = new Chain(settings)
    val untouched = layers.everything
    val epoch = layers.state.clock.epoch

    // executed, each on the one before
    val (_, kept) = blocks.foldLeft((layers.first, Vector.empty[(BlockRef, Map[ByteString, ByteString], Commit)])) {
      case ((parent, done), transactions) =>
        val onLayers = layers.on(parent)(transactions*)
        onLayers.layered().block shouldBe onLayers.ref
        val reference = inPlace.on(parent)(transactions*)
        reference.ref shouldBe onLayers.ref
        val commit = reference.inPlace()

        // every node of the block's state is read through the layers
        layers.readThrough(layers.state.readOnly(onLayers.ref), onLayers.ref)
        (onLayers.ref, done :+ (onLayers.ref, inPlace.everything, commit))
    }
    // a layer is no row, no record, no anchor and no move of the clock
    layers.everything shouldBe untouched
    layers.state.anchor shouldBe Some(layers.first)
    layers.state.clock.epoch shouldBe epoch
    kept.foreach((ref, _, _) => layers.state.forwardLayer(ref.hash).map(_.block) shouldBe Some(ref))

    // promoted, oldest first: after each, the datadir is the one the block left when it was committed in place
    kept.foreach { (ref, afterInPlace, reference) =>
      withClue(s"the promotion of block ${ref.number}: ") {
        val promoted = layers.promote(ref)
        layers.everything shouldBe afterInPlace
        promoted.copy(nanos = 0) shouldBe reference.copy(nanos = 0)
        layers.state.anchor shouldBe Some(ref)
        layers.state.forwardLayer(ref.hash) shouldBe None
        // the blocks still on layers are read as before, from the new anchor
        kept.map(_._1).filter(_.number > ref.number).foreach { later =>
          layers.readThrough(layers.state.readOnly(later), later)
        }
      }
    }
    kept.map(_._3)

  "A chain kept as forward layers" should "read as the chain committed in place, and be it once promoted" taggedAs
    (UnitTest, ConsensusTest) in {
      val commits = sameAsInPlace(CommitSettings(), fourBlocks)
      commits.map(_.tombstone) shouldBe Seq(None, None, None, None)
    }

  // The rows the block wrote in a trie whose old rows were too many to list are in no entry of its record. A
  // descendant reads them all the same, and a later reset of that trie meets the old rows that were left.
  it should "do so where a block replaces a storage trie that has too many rows to list, and makes it again" taggedAs
    UnitTest in {
      val bobsRows = new HashedState().positions()(Some(owner(bob))).size
      val commits = sameAsInPlace(
        CommitSettings(maxEntriesPerOwnerDelete = bobsRows - 1),
        Seq(
          Seq(delete(bob), slots(bob, (1 to 30).map(i => i -> (i + 7))*)),
          Seq(slots(bob, 2 -> 99, 31 -> 5), account(dave, 9, 9)),
          Seq(delete(bob), slots(alice, 41 -> 1))
        )
      )
      commits.map(_.tombstone) shouldBe
        Seq(Some(TombstoneCause.OwnerDeleteBound), None, Some(TombstoneCause.OwnerDeleteBound))
    }

  // The bound of a replaced trie's rows is held to the rows of the parent's STATE, as an in-place commit holds it to
  // the disk when the parent is there: not to the rows the disk held under the layers when the block was executed.
  // The block is settled when it is promoted, and the disk is then its parent's.
  it should "list a replaced trie, or not, as the block committed in place does: by its rows in the parent's state" taggedAs
    UnitTest in {
      val bobsRows = new HashedState().positions()(Some(owner(bob))).size
      // the disk's rows fit the bound; a layer adds to them, and the state's do not
      val grown = sameAsInPlace(
        CommitSettings(maxEntriesPerOwnerDelete = bobsRows),
        Seq(Seq(slots(alice, 1 -> 5)), Seq(slots(bob, (41 to 90).map(i => i -> i)*)), Seq(delete(bob)))
      )
      grown.map(_.tombstone) shouldBe Seq(None, None, Some(TombstoneCause.OwnerDeleteBound))
      // the disk's rows do not fit the bound; a layer takes most away, and the state's do
      val shrunk = sameAsInPlace(
        CommitSettings(maxEntriesPerOwnerDelete = bobsRows - 1),
        Seq(
          Seq(slots(bob, manySlots.take(36).map((k, _) => k -> 0)*)),
          Seq(account(dave, 9, 9)),
          Seq(delete(bob), slots(bob, 1 -> 1))
        )
      )
      shrunk.map(_.tombstone) shouldBe Seq(None, None, None)
    }

  "A view over forward layers" should "read its state after its oldest layer is promoted, until the log of moves ends" taggedAs
    UnitTest in {
      val chain = new Chain(maxViewAgeMoves = 2)
      val b = chain.on(chain.first)(fourBlocks(0)*)
      b.layered()
      val c = chain.on(b.ref)(fourBlocks(1)*)
      c.layered()
      val view = chain.state.view(c.ref)
      view.base shouldBe chain.first
      view.layered shouldBe true
      val held = PathMptStorage.readOnly(view)
      // and a view of the anchor, which reads through no layer
      val ofFirst = PathMptStorage.readOnly(chain.state.view(chain.first))

      // the disk now holds b's rows; the view takes the rows under its layers for the first block's, and is right:
      // the move says what each row was
      chain.promote(b.ref)
      view.diskHalfAtRestNow shouldBe false
      chain.readThrough(held, c.ref)
      // b removed a storage trie, whose rows were listed and deleted when it was promoted: the old anchor's view has
      // them from the move alone
      chain.readThrough(ofFirst, chain.first)
      // a view made now has one layer, over the new anchor, and reads the same
      val fresh = chain.state.view(c.ref)
      fresh.base shouldBe b.ref
      chain.readThrough(PathMptStorage.readOnly(fresh), c.ref)

      // two more moves, and a third: the log reaches back two
      chain.promote(c.ref)
      chain.readThrough(held, c.ref)
      val d = chain.on(c.ref)(fourBlocks(2)*)
      d.inPlace()
      a[StaleStateViewException] should be thrownBy chain.readThrough(held, c.ref)
    }

  it should "be made again when the disk moves between the look at the clock and the look at the layers" taggedAs
    UnitTest in {
      val chain = new Chain()
      val b = chain.on(chain.first)(fourBlocks(0)*)
      b.layered()
      val c = chain.on(b.ref)(fourBlocks(1)*)
      c.layered()
      var promoted = false
      // the clock was read with the first block as the anchor; b is promoted before the layers are looked at
      val view = chain.state.viewAfter(
        c.ref,
        () =>
          if !promoted then
            promoted = true
            chain.promote(b.ref)
      )
      view.base shouldBe b.ref
      view.createdAt shouldBe chain.state.clock.epoch
      chain.readThrough(PathMptStorage.readOnly(view), c.ref)
    }

  it should "call a wrong node under it an inconsistency of its layers, never a corrupt row, and stale once the disk moved" taggedAs
    UnitTest in {
      val chain = new Chain()
      val b = chain.on(chain.first)(fourBlocks(0)*)
      val layer = b.layered()
      // a row of carol's storage, which the block did not write, and a node that is not the state's
      val (key, node) = chain.rowsOf(chain.first, owner(carol)).head
      val position = PositionKey(Some(owner(carol)), key.drop(PositionKey.OwnerLength))
      layer.lookup(position) shouldBe None
      val other = chain.rowsOf(chain.first, owner(alice)).values.find(_ != node).get
      chain.source.update(chain.rows.rowUpdates(Map(position -> Some(other))))
      def at(storage: MptStorage) = storage.forOwner(position.owner).getAt(position.path, kec256(node.toArray))

      val onLayers = chain.state.readOnly(b.ref)
      a[LayerInconsistent] should be thrownBy at(onLayers)
      // the anchor's own view calls it what it is
      a[CorruptNodeException] should be thrownBy at(chain.state.readOnly(chain.first))
      // a position the layer wrote, asked for under another hash
      val (written, left) = layer.left.collectFirst {
        case (p, ForwardLayer.Left(_, Some(n))) if p.owner.isEmpty => p -> n
      }.get
      a[LayerInconsistent] should be thrownBy
        onLayers.forOwner(None).getAt(written.path, kec256(other.toArray))
      ByteString(onLayers.forOwner(None).getAt(written.path, kec256(left.toArray)).encode) shouldBe left
      // and a node of the layer is found by its hash
      ByteString(onLayers.get(kec256(left.toArray)).encode) shouldBe left
      // a position the layer leaves with no row (the block removed bob's storage, and the layer holds the trie's
      // owner and none of its positions), where a node is named
      val removedKey = chain.rowsOf(chain.first, owner(bob)).keys.head
      val removed = PositionKey(Some(owner(bob)), removedKey.drop(PositionKey.OwnerLength))
      layer.left.contains(removed) shouldBe false
      layer.lookup(removed) shouldBe Some(None)
      a[LayerInconsistent] should be thrownBy
        onLayers.forOwner(removed.owner).getAt(removed.path, kec256(other.toArray))

      // the disk moves: what the old view meets is no longer known to be the state it was made over
      chain.state.clock.inFlight(StateMove(chain.first, Map.empty))(())
      a[StaleStateViewException] should be thrownBy at(onLayers)
      // what a layer gives is the view's whatever the disk does: a wrong node there is the layers' fault still
      a[LayerInconsistent] should be thrownBy
        onLayers.forOwner(None).getAt(written.path, kec256(other.toArray))
    }

  "A forward layer" should "be one for a block, be refused beside the anchor or over a state that is gone, and be bounded" taggedAs
    UnitTest in {
      val chain = new Chain(CommitSettings(maxForwardLayers = 2))
      val b = chain.on(chain.first)(fourBlocks(0)*)
      val again = chain.on(chain.first)(fourBlocks(0)*)
      val layer = b.layered()
      (again.layered() should be).theSameInstanceAs(layer)
      chain.state.forwardLayer(b.ref.hash) shouldBe Some(layer)

      // a block on a layer that is dropped: its parent's state is held nowhere
      val c = chain.on(b.ref)(fourBlocks(1)*)
      chain.state.dropLayer(b.ref.hash)
      chain.state.forwardLayer(b.ref.hash) shouldBe None
      a[StateUnavailable] should be thrownBy chain.state.commitOverlay(c.staged, c.ref)
      a[StateUnavailable] should be thrownBy chain.state.view(c.ref)
      chain.state.forwardLayer(c.ref.hash) shouldBe None

      // changes that are not what the block's header says of the state are no layer
      val wrong = b.ref.copy(stateRoot = chain.first.stateRoot)
      an[IllegalStateException] should be thrownBy chain.state.commitOverlay(b.staged, wrong)
      chain.state.forwardLayer(b.ref.hash) shouldBe None
      // what a layer is held to, by the account trie's root: the state it was found on and the state it leaves
      val frozen = b.staged.changes.frozen
      ForwardLayer.of(b.ref, chain.first, frozen).rootFaults shouldBe empty
      ForwardLayer.of(b.ref, chain.first.copy(stateRoot = b.ref.stateRoot), frozen).rootFaults should have size 1
      ForwardLayer.of(wrong, chain.first, frozen).rootFaults should have size 1
      // a layer answers for each position its block touched, also where the block left what it found
      val (touched, node) = layer.left.collectFirst { case (p, ForwardLayer.Left(_, Some(n))) => p -> n }.get
      val came = PositionKey(Some(owner(carol)), touched.hp)
      val went = PositionKey(Some(owner(alice)), touched.hp)
      val same = ForwardLayer.of(
        b.ref,
        chain.first,
        frozen.copy(changes = frozen.changes ++ Seq(came -> Change(Some(node), Some(node)), went -> Change(None, None)))
      )
      same.lookup(came) shouldBe Some(Some(node))
      same.lookup(went) shouldBe Some(None)
      same.lookup(PositionKey(Some(owner(dave)), touched.hp)) shouldBe None
      // a block that touched nothing leaves the state root as it found it
      val nothing = StagedChanges.Frozen(Map.empty, Nil)
      ForwardLayer.of(b.ref, chain.first, nothing).rootFaults should have size 1
      ForwardLayer.of(wrong, chain.first, nothing).rootFaults shouldBe empty
      // nor are a block's changes a layer of a block at another height
      val elsewhere = b.ref.copy(hash = ByteString(kec256("elsewhere".getBytes)), number = b.ref.number + 1)
      an[IllegalArgumentException] should be thrownBy chain.state.commitOverlay(b.staged, elsewhere)
      chain.state.forwardLayer(elsewhere.hash) shouldBe None

      // as many as may be kept, and no more
      b.layered()
      val sibling = chain.on(chain.first)(account(dave, 1, 1))
      chain.state.commitOverlay(
        sibling.staged,
        sibling.ref.copy(hash = ByteString(kec256("sibling".getBytes)))
      ) shouldBe
        a[OverlayOutcome.Layered]
      val third = chain.on(b.ref)(fourBlocks(1)*)
      (the[StateUnavailable] thrownBy chain.state.commitOverlay(third.staged, third.ref)).getMessage should
        include("2 executed blocks")

      // the block that is committed in place meanwhile is the anchor: it is not kept beside itself, and a layer an
      // earlier execution left of it goes
      val before = chain.everything
      a[StateUnavailable] should be thrownBy chain.state.promote(third.ref, chain.pointer(third.ref))
      chain.everything shouldBe before
      again.inPlace()
      chain.state.forwardLayer(b.ref.hash) shouldBe None
      chain.state.commitOverlay(b.staged, b.ref) shouldBe OverlayOutcome.AlreadyApplied
      chain.state.forwardLayer(b.ref.hash) shouldBe None
      // promoted again, it is the anchor already: the block's own records are written and nothing else
      chain.state.promote(b.ref, None) shouldBe CommitOutcome.AlreadyApplied
    }

  it should "be promoted only from the anchor, and only over the rows it was built on" taggedAs UnitTest in {
    val chain = new Chain()
    val b = chain.on(chain.first)(fourBlocks(0)*)
    val layer = b.layered()
    val c = chain.on(b.ref)(fourBlocks(1)*)
    c.layered()
    val before = chain.everything

    // c's parent is not the anchor: moving the disk there is a head move
    (the[StateUnavailable] thrownBy chain.state.promote(c.ref, chain.pointer(c.ref))).getMessage should
      include("was executed on the state of")
    chain.everything shouldBe before
    chain.state.forwardLayer(c.ref.hash) should not be empty

    // a row b changed is another node than b found there: b is dropped, and nothing is written
    val (position, next) = layer.left.collectFirst { case (p, ForwardLayer.Left(Some(_), Some(next))) => p -> next }.get
    chain.source.update(chain.rows.rowUpdates(Map(position -> Some(next))))
    val tampered = chain.everything
    (the[StateUnavailable] thrownBy chain.state.promote(b.ref, chain.pointer(b.ref))).getMessage should
      include("was not built on the state the disk holds")
    chain.everything shouldBe tampered
    chain.state.anchor shouldBe Some(chain.first)
    chain.state.forwardLayer(b.ref.hash) shouldBe None
    // and so is what was built on it
    chain.state.forwardLayer(c.ref.hash) shouldBe None
    chain.state.lock.holder shouldBe None

    // the same where the row b found a node at is not there
    val other = new Chain()
    val onOther = other.on(other.first)(fourBlocks(0)*)
    onOther.layered()
    other.source.update(other.rows.rowUpdates(Map(position -> None)))
    (the[StateUnavailable] thrownBy other.state.promote(onOther.ref, other.pointer(onOther.ref))).getMessage should
      include("was not built on the state the disk holds")
    other.state.forwardLayer(onOther.ref.hash) shouldBe None
  }

  // What is kept can be promoted, unless the disk is not the state it was built on: a layer that could not be would
  // be the answer to every execution of its block, and a branch with that block in it could never be adopted.
  it should "not be kept when its changes are not a block's, and not stay when its promotion finds that out" taggedAs
    UnitTest in {
      val chain = new Chain()
      val small = ByteString(1, 2, 3)
      // a position of carol's storage trie below its root, which the block does not touch
      val below = chain
        .rowsOf(chain.first, owner(carol))
        .keys
        .map(key => PositionKey(Some(owner(carol)), key.drop(PositionKey.OwnerLength)))
        .find(_.hp != PositionKey.AccountRoot.hp)
        .get

      // a node under 32 bytes has no row of its own
      val bad = chain.on(chain.first)(fourBlocks(0)*)
      bad.staged.changes.record(below.owner, Nil, Seq((below.path, ByteString(kec256(small.toArray)), small.toArray)))
      (the[IllegalStateException] thrownBy chain.state.commitOverlay(bad.staged, bad.ref)).getMessage should
        include("the next is not a stored node")
      chain.state.forwardLayer(bad.ref.hash) shouldBe None
      // nor is a block named by anything but a 32-byte hash
      val b = chain.on(chain.first)(fourBlocks(0)*)
      (the[IllegalStateException] thrownBy chain.state.commitOverlay(
        b.staged,
        b.ref.copy(hash = small)
      )).getMessage should
        include("not 32 bytes")

      // a layer that is one all the same (a defect put it there) goes when its promotion finds it, with what stands
      // on it, and nothing is written
      val frozen = b.staged.changes.frozen
      val defect = frozen.copy(changes = frozen.changes + (below -> Change(None, Some(small))))
      chain.state.lock.locked("a test")(chain.state.overlay.register(ForwardLayer.of(b.ref, chain.first, defect)))
      // (a block that does not come to the node the defect left)
      val c = chain.on(b.ref)(account(dave, 9, 9))
      c.layered()
      val before = chain.everything
      (the[IllegalStateException] thrownBy chain.state.promote(b.ref, chain.pointer(b.ref))).getMessage should
        include("the next is not a stored node")
      chain.everything shouldBe before
      chain.state.anchor shouldBe Some(chain.first)
      Seq(b.ref, c.ref).foreach(gone => chain.state.forwardLayer(gone.hash) shouldBe None)
      chain.state.lock.holder shouldBe None
      // executed again, the block is kept and promoted
      chain.on(chain.first)(fourBlocks(0)*).layered()
      chain.promote(b.ref)
    }

  // ETC has no EIP-6780: a SELFDESTRUCT removes a whole storage trie, of any size. A layer that held a prior for each
  // of its rows would be as large as the trie, and a branch with such a block in it could be too large to keep (forge).
  it should "hold nothing of a storage trie its block destroys, however many rows the trie has" taggedAs
    (UnitTest, ConsensusTest) in {
      def destroyed(grownBy: Int): (Chain, ForwardLayer, BlockRef) =
        val chain = new Chain()
        val parent =
          if grownBy == 0 then chain.first
          else
            val grown = chain.on(chain.first)(slots(bob, (1 to grownBy).map(i => (5000 + i) -> i)*))
            grown.inPlace()
            grown.ref
        (chain, chain.on(parent)(delete(bob)).layered(), parent)

      val (small, ofSmall, _) = destroyed(grownBy = 0)
      val (large, ofLarge, parent) = destroyed(grownBy = 400)
      val rows = large.rowsOf(parent, owner(bob))
      rows.size should be > small.rowsOf(small.first, owner(bob)).size + 400

      // the trie is a mark in the layer, and what the layer holds does not grow with it
      ofLarge.resets shouldBe Seq(owner(bob))
      ofLarge.left.keys.exists(_.owner.contains(owner(bob))) shouldBe false
      ofLarge.positions shouldBe ofSmall.positions
      ofLarge.bytes shouldBe ofSmall.bytes
      ofLarge.bytes should be < rows.iterator.map((key, node) => key.length.toLong + node.length).sum
      // what a layer is taken to hold: its nodes and their keys, and an overhead for each entry and each mark
      ofLarge.bytes shouldBe ofLarge.left.iterator.map { (position, written) =>
        position.rowKey.length.toLong + written.next.fold(0)(_.length)
      }.sum + (ofLarge.positions + ofLarge.resets.size) * ForwardLayer.EntryOverhead

      // every row of it is no row of the block's state, read through the layer
      val view = large.state.view(ofLarge.block)
      rows.keys.foreach { key =>
        val position = PositionKey(Some(owner(bob)), key.drop(PositionKey.OwnerLength))
        ofLarge.lookup(position) shouldBe Some(None)
        view.readFrom(position)._1 shouldBe None
      }
      // and each is deleted, and listed in the block's record, when the layer is promoted
      large.promote(ofLarge.block).tombstone shouldBe None
      large.disk._2.keys.exists(_.startsWith(owner(bob))) shouldBe false
      val record = large.records.get(ofLarge.block.hash).flatMap(_.toOption).get
      record.changes.keys.count(_.owner.contains(owner(bob))) shouldBe rows.size
    }

  "The layers nothing leads to" should "go with the layer they were built on, and when the block they forked from is led back to no more" taggedAs
    UnitTest in {
      // the records of two blocks are kept
      val chain = new Chain(
        CommitSettings(
          maxForwardLayers = 4,
          retention = new RetentionPolicy(StateConfig.Mode.Basic, (_, _) => 2)
        )
      )
      val b = chain.on(chain.first)(fourBlocks(0)*)
      b.layered()
      val c = chain.on(b.ref)(fourBlocks(1)*)
      c.layered()
      // a sibling of b, and a block on it
      val sibling = chain.on(chain.first)(account(dave, 1, 1))
      val beside = sibling.ref.copy(hash = ByteString(kec256("the sibling".getBytes)))
      chain.state.commitOverlay(sibling.staged, beside) shouldBe a[OverlayOutcome.Layered]
      val onSibling = chain.on(beside)(account(dave, 2, 2))
      onSibling.layered()
      val late = chain.on(beside)(account(dave, 3, 3))
      // four layers, and no room for a fifth
      val d = chain.on(c.ref)(fourBlocks(2)*)
      a[StateUnavailable] should be thrownBy chain.state.commitOverlay(d.staged, d.ref)

      // b becomes the anchor. Its own layer goes: it is on disk. Its sibling stays, with what stands on it: the block
      // they forked from is behind the anchor, and b's record leads back to it.
      chain.promote(b.ref)
      chain.state.forwardLayer(b.ref.hash) shouldBe None
      Seq(beside, onSibling.ref, c.ref).foreach(kept => chain.state.forwardLayer(kept.hash) should not be empty)
      // so there is room for one
      d.layered()

      // c, then d: with d the record of b is pruned, and nothing leads back to the first block any more
      chain.promote(c.ref)
      Seq(beside, onSibling.ref).foreach(kept => chain.state.forwardLayer(kept.hash) should not be empty)
      chain.promote(d.ref)
      chain.records.get(b.ref.hash) shouldBe None
      Seq(beside, onSibling.ref).foreach(gone => chain.state.forwardLayer(gone.hash) shouldBe None)
      // a block that was executed on the sibling meanwhile is not kept: its parent's state is held nowhere
      a[StateUnavailable] should be thrownBy chain.state.commitOverlay(late.staged, late.ref)

      // a layer that is dropped takes with it what was built on it
      val e = chain.on(d.ref)(account(dave, 5, 5))
      e.layered()
      val f = chain.on(e.ref)(account(dave, 6, 6))
      f.layered()
      chain.state.dropLayer(e.ref.hash)
      Seq(e.ref, f.ref).foreach(gone => chain.state.forwardLayer(gone.hash) shouldBe None)
    }

  "A promotion" should "keep its layer when the write fails, write the block's own records for the anchor, and not outlive the state" taggedAs
    UnitTest in {
      val failing = new Failing
      val chain = new Chain(CommitSettings(allowsStateDestruction = true), source = failing)
      val b = chain.on(chain.first)(fourBlocks(0)*)
      val layer = b.layered()
      val before = chain.everything

      failing.fails = true
      a[java.io.IOException] should be thrownBy chain.state.promote(b.ref, chain.pointer(b.ref))
      failing.fails = false
      chain.everything shouldBe before
      chain.state.anchor shouldBe Some(chain.first)
      chain.state.forwardLayer(b.ref.hash) shouldBe Some(layer)
      chain.state.lock.holder shouldBe None
      // asked again, it is promoted
      chain.promote(b.ref)
      chain.state.anchor shouldBe Some(b.ref)

      // the anchor promoted again: nothing of the state is written, and the caller's own records are
      val records = chain.appState.putBestBlockInfo(BlockInfo(b.ref.hash, 99))
      chain.state.promote(b.ref, Some(records)) shouldBe CommitOutcome.AlreadyApplied
      chain.appState.getBestBlockNumber() shouldBe 99

      // a block of a hash that is kept, on another parent, is a defect and no layer
      val c = chain.on(b.ref)(fourBlocks(1)*)
      c.layered()
      val d = chain.on(c.ref)(fourBlocks(2)*)
      an[Exception] should be thrownBy chain.state.commitOverlay(d.staged, c.ref)
      // the same where the other parent has the state of the first, so that the block's changes are a block's on it
      val twin = b.ref.copy(hash = ByteString(kec256("b's twin".getBytes)))
      val onFirst = chain.on(b.ref)(account(dave, 7, 7))
      // (there is no layer of b to stand a twin beside: it is the anchor. One level up, then.)
      onFirst.layered()
      val sameState = chain.on(b.ref)(account(dave, 7, 7))
      chain.state.commitOverlay(sameState.staged, onFirst.ref.copy(hash = twin.hash)) shouldBe
        a[OverlayOutcome.Layered]
      val child = chain.on(onFirst.ref)(account(dave, 8, 8))
      child.layered()
      val onTwin = chain.on(onFirst.ref.copy(hash = twin.hash))(account(dave, 8, 8))
      (the[IllegalStateException] thrownBy chain.state.commitOverlay(onTwin.staged, child.ref)).getMessage should
        include("has the hash of an executed block that is kept")
      // and a block of that hash with another state root has no view
      a[StateUnavailable] should be thrownBy chain.state.view(c.ref.copy(stateRoot = b.ref.stateRoot))

      // the state is destroyed: the blocks kept beside it are of no state
      chain.state.destroyStateForNewGenesis()
      chain.state.forwardLayer(c.ref.hash) shouldBe None
    }
