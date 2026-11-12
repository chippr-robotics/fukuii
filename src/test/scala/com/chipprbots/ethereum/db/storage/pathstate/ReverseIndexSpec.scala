package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C4a part 2 (T121 to T123): the state of a block behind the anchor, and of a block on a fork, read
  * through the changeset records with no row touched.
  *
  * A chain is committed in place, block by block. The state of each block behind the head is then read through a view:
  * every node of it, as a hash store the same blocks were run on holds it, and the rows of a storage trie as that state
  * holds them. A fork is executed on a block behind the head, on layers, and read the same way.
  */
class ReverseIndexSpec extends AnyFlatSpec with Matchers with LayerChains:

  import PathStateStorage.*
  import PathStateTestKit.*

  /** A block that does what the blocks before it make matter: a trie replaced whole every third block and made again in
    * the next, slots written and cleared, accounts made and removed.
    */
  private def blockOf(i: Int): Seq[Tx] =
    val always = Seq(slots(alice, i -> (i * 3), (i + 40) -> i), account(dave, i, 100 + i))
    always ++ (i % 3 match
      case 0 => Seq(delete(bob))
      case 1 =>
        Seq(slots(bob, (1 to 12).map(k => k -> (k + i))*), slots(carol, otherSlots.take(i).map((k, _) => k -> 0)*))
      case _ => Seq(slots(bob, 3 -> 0, 20 -> i), delete(crowd(i)), account(eve, i, i).andThen(slots(eve, i -> i)))
    )

  /** `blocks` blocks committed in place on the first; all of them, the first included, oldest first. */
  private def grown(chain: Chain, blocks: Int): Vector[BlockRef] =
    (1 to blocks).foldLeft(Vector(chain.first)) { (refs, i) =>
      val next = chain.on(refs.last)(blockOf(i)*)
      next.inPlace()
      refs :+ next.ref
    }

  /** The view reads the state of `ref`: every node of it. */
  private def reads(chain: Chain, view: StateView, ref: BlockRef): Unit =
    view.block shouldBe ref
    chain.readThrough(PathMptStorage.readOnly(view), ref)

  "The view of a block behind the anchor" should "read that block's state, one block back and as many as there are records" taggedAs
    (UnitTest, ConsensusTest) in {
      val chain = new Chain()
      val refs = grown(chain, 9)
      val head = refs.last
      Seq(1, 2, 8, 9).foreach { back =>
        withClue(s"$back back: ") {
          val view = chain.state.viewOf(refs(refs.size - 1 - back))
          view.base shouldBe head
          view.layered shouldBe true
          view.isAnchorViewNow shouldBe false
          reads(chain, view, refs(refs.size - 1 - back))
        }
      }
      // what the node's readers and its execution are given is the head's state and no other, as before
      a[StateUnavailable] should be thrownBy chain.state.view(refs(refs.size - 2))
      a[StateUnavailable] should be thrownBy chain.state.stage(refs(refs.size - 2))
      // nothing was written for any of it
      chain.state.anchor shouldBe Some(head)
    }

  it should "read the same after the anchor moves on, until the log of moves ends; and be made from records alone after a restart" taggedAs
    UnitTest in {
      val chain = new Chain(maxViewAgeMoves = 2)
      val refs = grown(chain, 5)
      val old = chain.state.viewOf(refs(2))
      reads(chain, old, refs(2))

      // two more blocks: the old view's rows are the anchor's of its day by the log, and its index is of that anchor
      val sixth = chain.on(refs.last)(blockOf(6)*)
      sixth.inPlace()
      val seventh = chain.on(sixth.ref)(blockOf(7)*)
      seventh.inPlace()
      reads(chain, old, refs(2))
      // a view made now is five back
      reads(chain, chain.state.viewOf(refs(2)), refs(2))
      // and one more move than the log holds
      chain.on(seventh.ref)(blockOf(8)*).inPlace()
      a[StaleStateViewException] should be thrownBy chain.readThrough(PathMptStorage.readOnly(old), refs(2))

      // another store over the same datadir, as after a restart: it knows of no block behind its anchor until it
      // walks the records
      val restarted = new PathStateStorage(chain.rows, chain.appState)
      val view = restarted.viewOf(refs(1))
      chain.readThrough(PathMptStorage.readOnly(view), refs(1))
    }

  "The view of a block on a fork" should "read through the records back to the fork point and the fork's layers from there" taggedAs
    (UnitTest, ConsensusTest) in {
      val chain = new Chain()
      val refs = grown(chain, 5)
      // a branch of three from the third block: other blocks than the chain's, on layers
      val forkPoint = refs(2)
      val first = chain.onAny(forkPoint)(blockOf(7)*)
      first.layered()
      val second = chain.onAny(first.ref)(blockOf(8)*)
      second.layered()
      val third = chain.onAny(second.ref)(blockOf(9)*)
      third.layered()
      first.ref.number shouldBe refs(3).number
      first.ref should not be refs(3)

      val view = chain.state.viewOf(third.ref)
      view.base shouldBe refs.last
      Seq(first, second, third).foreach(block => reads(chain, chain.state.viewOf(block.ref), block.ref))

      // the anchor moves on: the view made before reads the same, and so does one made after
      val sixth = chain.on(refs.last)(blockOf(6)*)
      sixth.inPlace()
      reads(chain, view, third.ref)
      reads(chain, chain.state.viewOf(third.ref), third.ref)
      // the fork's layers stand: the block they left the chain at is behind the anchor still
      Seq(first, second, third).foreach(block => chain.state.forwardLayer(block.ref.hash) should not be empty)

      // a block of the anchor's own chain is not kept as a layer beside it
      val again = chain.onAny(refs(2))(blockOf(3)*)
      again.ref shouldBe refs(3)
      chain.state.commitOverlay(again.staged, again.ref) shouldBe OverlayOutcome.BehindTheAnchor
      chain.state.forwardLayer(refs(3).hash) shouldBe None
    }

  "A state behind the anchor" should "not be held where a record is pruned, is a tombstone, cannot be read, or is too far or too much" taggedAs
    UnitTest in {
      // pruned: the records of three blocks are kept
      val pruning = new Chain(CommitSettings(retention = new RetentionPolicy(StateConfig.Mode.Basic, (_, _) => 3)))
      val pruned = grown(pruning, 7)
      reads(pruning, pruning.state.viewOf(pruned(pruned.size - 4)), pruned(pruned.size - 4))
      pruning.records.get(pruned(pruned.size - 4).hash) shouldBe None
      (the[StateUnavailable] thrownBy pruning.state.viewOf(pruned(pruned.size - 5))).getMessage should
        include("is not held")

      // a tombstone: bob's trie has more rows than may be listed when the third block replaces it
      val bounded = new Chain(CommitSettings(maxEntriesPerOwnerDelete = 5))
      val over = grown(bounded, 5)
      bounded.records.get(over(3).hash).flatMap(_.toOption).map(_.incomplete) shouldBe Some(true)
      // the block the tombstone is of, and what came after it, are read; what came before is not
      bounded.readThrough(PathMptStorage.readOnly(bounded.state.viewOf(over(4))), over(4))
      bounded.readThrough(PathMptStorage.readOnly(bounded.state.viewOf(over(3))), over(3))

      // What the view holds at the positions of bob's trie is what a head move back would leave on the disk: the old
      // trie's rows that the tombstone's block left behind among them, but for those a later block wrote over, which
      // a reverse takes away with what was written. So a second datadir is given the same blocks and the last two are
      // reversed there, by their records; and a block promoted on that disk after such a move lists a replaced trie
      // by those rows.
      val reversed = new Chain(CommitSettings(maxEntriesPerOwnerDelete = 5))
      grown(reversed, 5) shouldBe over
      Seq(over(5), over(4)).foreach { undone =>
        val record = reversed.records.get(undone.hash).flatMap(_.toOption).get
        reversed.source.update(reversed.rows.rowUpdates(record.changes.view.mapValues(_.prior)))
      }
      val bobsThen = reversed.disk._2.filter(_._1.startsWith(owner(bob)))
      bobsThen.size should be > bounded.rowsOf(over(3), owner(bob)).size
      val behind = bounded.state.viewOf(over(3))
      val bobsNow = bounded.disk._2.filter(_._1.startsWith(owner(bob)))
      bobsNow should not be bobsThen
      (bobsThen.keySet ++ bobsNow.keySet).foreach { key =>
        val position = PositionKey(Some(owner(bob)), key.drop(PositionKey.OwnerLength))
        withClue(s"${position.show}: ")(behind.readFrom(position)._1.map(ByteString(_)) shouldBe bobsThen.get(key))
      }
      a[StateUnavailable] should be thrownBy bounded.state.viewOf(over(2))
      // asked of the records themselves, with no window to say so first
      (the[StateUnavailable] thrownBy
        ReverseIndex.between(bounded.records, over.last, over(2), 64, Long.MaxValue)).getMessage should
        include("its record lists nothing")

      // a record that cannot be read, one that is another block's, a walk that is too long, an index that is too large
      val chain = new Chain()
      val refs = grown(chain, 5)
      def between(maxHops: Int = 64, maxBytes: Long = Long.MaxValue) =
        ReverseIndex.between(chain.records, refs.last, refs(1), maxHops, maxBytes)
      between().positions should be > 0
      (the[StateUnavailable] thrownBy between(maxHops = 3)).getMessage should include("more than 3 blocks back")
      between(maxHops = 4).to shouldBe refs(1)
      val whole = between().bytes
      between(maxBytes = whole).bytes shouldBe whole
      (the[StateUnavailable] thrownBy between(maxBytes = whole - 1)).getMessage should include("bytes")
      // a record that is itself more than an index may hold is not decoded to find that out
      (the[StateUnavailable] thrownBy between(maxBytes = 64)).getMessage should include("the changeset record of block")
      chain.records.getForWalk(refs.last.hash, 64) shouldBe a[ChangesetStorage.Walked.TooLarge]
      chain.records.getForWalk(refs.last.hash, Long.MaxValue) shouldBe a[ChangesetStorage.Walked.Read]
      chain.records.getForWalk(chain.first.hash, Long.MaxValue) shouldBe ChangesetStorage.Walked.Absent
      (the[StateUnavailable] thrownBy
        ReverseIndex.between(
          chain.records,
          refs.last,
          refs(1).copy(stateRoot = refs(2).stateRoot),
          64,
          Long.MaxValue
        )).getMessage should
        include("not a block the anchor's records lead back to")
      // the anchor named with another state root: its record is not that block's
      (the[StateUnavailable] thrownBy
        ReverseIndex.between(
          chain.records,
          refs.last.copy(stateRoot = refs(1).stateRoot),
          refs(1),
          64,
          Long.MaxValue
        )).getMessage should
        include("is not that block's")
      // a byte of the fourth block's record is turned
      val key = ChangesetStorage.recordKey(refs(4).hash)
      val record = chain.source.getOptimized(Namespaces.StateChangesetNamespace, key).get
      record(record.length / 2) = (record(record.length / 2) ^ 0x40).toByte
      chain.source.update(
        Seq(DataSourceUpdateOptimized(Namespaces.StateChangesetNamespace, Nil, Seq(key -> record)))
      )
      (the[StateUnavailable] thrownBy between()).getMessage should include("cannot be read")
      // the window is made from headers, and stops there too: nothing is behind a record that cannot be read
      val restarted = new PathStateStorage(chain.rows, chain.appState)
      a[StateUnavailable] should be thrownBy restarted.viewOf(refs(1))
      reads(chain, restarted.viewOf(refs(4)), refs(4))
    }

  "A wrong node behind the anchor" should "be the records' inconsistency, never a missing node or a corrupt row" taggedAs
    UnitTest in {
      val chain = new Chain()
      val refs = grown(chain, 4)
      val view = chain.state.viewOf(refs(1))
      val index = ReverseIndex.between(chain.records, refs.last, refs(1), 64, Long.MaxValue)
      val storage: MptStorage = PathMptStorage.readOnly(view)
      val other = chain.rowsOf(refs.last, owner(alice)).values.head

      // a position the records give a node for, asked for under another hash
      val diff = chain.records.get(refs(2).hash).flatMap(_.toOption).get
      val (held, node) = diff.changes.collectFirst {
        case (p, Change(Some(prior), _)) if p.owner.nonEmpty => p -> prior
      }.get
      index.lookup(held) shouldBe Some(Some(node))
      ByteString(storage.forOwner(held.owner).getAt(held.path, kec256(node.toArray)).encode) shouldBe node
      a[LayerInconsistent] should be thrownBy storage.forOwner(held.owner).getAt(held.path, kec256(other.toArray))

      // a position that came to be after the block: the records say no row was there, and a node is named
      val (made, _) = diff.changes.collectFirst { case (p, Change(None, Some(next))) => p -> next }.get
      index.lookup(made) shouldBe Some(None)
      a[LayerInconsistent] should be thrownBy storage.forOwner(made.owner).getAt(made.path, kec256(other.toArray))

      // A row that is not there, where the records say nothing: the anchor's disk has a hole, and this state holds
      // there what the anchor's does. A missing node; and with no position to write a fetched node to, since the row
      // is the anchor's.
      val untouched = chain
        .rowsOf(refs(1), owner(carol))
        .collectFirst {
          case (key, held)
              if index.lookup(PositionKey(Some(owner(carol)), key.drop(PositionKey.OwnerLength))).isEmpty =>
            PositionKey(Some(owner(carol)), key.drop(PositionKey.OwnerLength)) -> held
        }
        .get
      chain.source.update(chain.rows.rowUpdates(Map(untouched._1 -> None)))
      val hole = the[com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException] thrownBy
        storage.forOwner(untouched._1.owner).getAt(untouched._1.path, kec256(untouched._2.toArray))
      hole.location shouldBe None
      // the anchor's own view says where
      val atHead = the[com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException] thrownBy
        chain.state
          .readOnly(refs.last)
          .forOwner(untouched._1.owner)
          .getAt(untouched._1.path, kec256(untouched._2.toArray))
      atHead.location shouldBe Some(untouched._1.hp)

      // what the records give is the view's whatever the disk does: a wrong node there is no stale read after a move
      chain.state.clock.inFlight(StateMove(refs.last, Map.empty))(())
      a[LayerInconsistent] should be thrownBy storage.forOwner(held.owner).getAt(held.path, kec256(other.toArray))
    }

  "The blocks behind the anchor" should "be those its records lead back to: none across a tombstone, none the records have left, none too far" taggedAs
    UnitTest in {
      val chain = new Chain()
      val refs = grown(chain, 5)
      AncestorWindow.of(chain.records, refs.last, 64).ancestors shouldBe refs.init.reverse
      AncestorWindow.of(chain.records, refs.last, 2).ancestors shouldBe Vector(refs(4), refs(3))

      val window = AncestorWindow(refs(3), Vector(refs(2), refs(1), refs(0)))
      window.advancedTo(refs(4), tombstone = false, floor = 0, maxHops = 64) shouldBe
        AncestorWindow(refs(4), Vector(refs(3), refs(2), refs(1), refs(0)))
      // the records below the floor are gone: the block just under it is the last that is led back to
      window.advancedTo(refs(4), tombstone = false, floor = refs(2).number, maxHops = 64).ancestors shouldBe
        Vector(refs(3), refs(2))
      window.advancedTo(refs(4), tombstone = false, floor = 0, maxHops = 2).ancestors shouldBe Vector(refs(3), refs(2))
      window.advancedTo(refs(4), tombstone = true, floor = 0, maxHops = 64) shouldBe AncestorWindow(
        refs(4),
        Vector.empty
      )

      // a tombstone, as the records have it: the walk stops on the block it is of
      val bounded = new Chain(CommitSettings(maxEntriesPerOwnerDelete = 5))
      val over = grown(bounded, 2)
      // a fork from the block behind the head, kept as a layer
      val fork = bounded.onAny(over(1))(account(dave, 77, 77))
      fork.layered()
      bounded.state.forwardLayer(fork.ref.hash) should not be empty
      // the third block replaces a trie with too many rows: its record lists nothing, and nothing is read across it
      val third = bounded.on(over.last)(blockOf(3)*)
      third.inPlace().tombstone should not be empty
      bounded.state.forwardLayer(fork.ref.hash) shouldBe None
      val fourth = bounded.on(third.ref)(blockOf(4)*)
      fourth.inPlace()
      AncestorWindow.of(bounded.records, fourth.ref, 64).ancestors shouldBe Vector(third.ref)
    }
