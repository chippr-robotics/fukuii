package com.chipprbots.ethereum.db.storage.pathstate

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C4a part 3 (T124): the bounds of the overlay (data-model 6.4). The forward layers no branch pins
  * are bounded in number and in bytes, and one more evicts a layer nothing stands on, least recently used first; the
  * layers of a branch under execution are pinned, kept whatever those bounds are, and bounded together in bytes.
  *
  * A block here changes one account, so that every layer holds the same: a bound in bytes is then a count of layers.
  */
class OverlayBudgetSpec extends AnyFlatSpec with Matchers with LayerChains:

  import PathStateStorage.*
  import PathStateTestKit.*

  /** A block that is like no other of the spec: `n` is its own. */
  private def one(n: Int): Tx = account(dave, n, n)

  /** What a layer of such a block holds. */
  private val size: Long =
    val chain = new Chain()
    val sizes = (1 to 3).map(n => chain.on(chain.first)(one(n)).layered().bytes)
    sizes.distinct should have size 1
    sizes.head

  private def keptWith(chain: Chain, pin: BranchPin)(executed: chain.Executed): ForwardLayer =
    chain.state.commitOverlay(executed.staged, executed.ref, Some(pin)) match
      case OverlayOutcome.Kept(layer) => layer
      case other                      => fail(s"block ${executed.ref.show} was not kept: $other")

  private def held(chain: Chain)(refs: BlockRef*): Unit =
    refs.foreach(ref => withClue(s"${ref.show}: ")(chain.state.forwardLayer(ref.hash) should not be empty))
  private def gone(chain: Chain)(refs: BlockRef*): Unit =
    refs.foreach(ref => withClue(s"${ref.show}: ")(chain.state.forwardLayer(ref.hash) shouldBe None))

  "The forward layers no branch pins" should "be as many as may be: one more evicts a layer nothing stands on, never one it stands on" taggedAs
    UnitTest in {
      val chain = new Chain(CommitSettings(maxForwardLayers = 3))
      val b = chain.on(chain.first)(one(1))
      b.layered()
      val c = chain.on(b.ref)(one(2))
      c.layered()
      val d = chain.on(c.ref)(one(3))
      d.layered()
      chain.state.overlayUsage shouldBe OverlayUsage(layers = 3, bytes = 3 * size, 0, 0L, evictions = 0)

      // one chain of three: the only layer nothing stands on is the one the next would stand on
      val e = chain.on(d.ref)(one(4))
      chain.state.commitOverlay(e.staged, e.ref) shouldBe OverlayOutcome.NotKept(NotKeptReason.NoRoom)
      held(chain)(b.ref, c.ref, d.ref)
      gone(chain)(e.ref)
      chain.state.overlayUsage.evictions shouldBe 0

      // a view of d, which is about to go
      val ofD = chain.state.view(d.ref)
      // a sibling of b: d goes for it, and neither b nor c, which something stands on
      val s = chain.on(chain.first)(one(9))
      s.layered()
      gone(chain)(d.ref)
      held(chain)(b.ref, c.ref, s.ref)
      chain.state.overlayUsage shouldBe OverlayUsage(layers = 3, bytes = 3 * size, 0, 0L, evictions = 1)

      // the view that was made goes on reading; no new one is made, and nothing is wrong with the block
      chain.readThrough(PathMptStorage.readOnly(ofD), d.ref)
      a[StateUnavailable] should be thrownBy chain.state.view(d.ref)
      // executed again, it is kept again
      val again = chain.on(c.ref)(one(3))
      again.ref shouldBe d.ref
      again.layered()
      held(chain)(b.ref, c.ref, d.ref)
      gone(chain)(s.ref)
    }

  it should "be evicted least recently used first: a reader's view is no use, an execution staged, a delivery again and a layer kept on one are" taggedAs
    UnitTest in {
      val chain = new Chain(CommitSettings(maxForwardLayers = 3))
      def tip(n: Int): chain.Executed =
        val executed = chain.on(chain.first)(one(n))
        executed.layered()
        executed
      val (x, y, z) = (tip(1), tip(2), tip(3))

      // read, and read again, x is still the layer longest out of use
      chain.state.readOnly(x.ref)
      chain.state.throwaway(x.ref)
      val w = tip(4)
      gone(chain)(x.ref)
      held(chain)(y.ref, z.ref, w.ref)

      // an execution is staged on y: z is now the one
      chain.state.stage(y.ref)
      val v = tip(5)
      gone(chain)(z.ref)
      held(chain)(y.ref, w.ref, v.ref)

      // w is delivered again: y, then
      chain.state.commitOverlay(w.staged, w.ref) shouldBe a[OverlayOutcome.Kept]
      val u = tip(6)
      gone(chain)(y.ref)
      held(chain)(w.ref, v.ref, u.ref)

      // A block is executed on v, and then one is staged on u; the block on v is kept (w goes for it), and taken away
      // again. v was in use when a layer was kept on it, after u was: so u goes before v, though u was staged on last.
      val onV = chain.on(v.ref)(one(7))
      chain.state.stage(u.ref)
      onV.layered()
      gone(chain)(w.ref)
      chain.state.dropLayer(onV.ref.hash)
      held(chain)(v.ref, u.ref)
      val (p, q) = (tip(8), tip(9))
      gone(chain)(u.ref)
      held(chain)(v.ref, p.ref, q.ref)
    }

  it should "hold no more bytes together than they may, and none of them more than one may" taggedAs UnitTest in {
    val chain = new Chain(CommitSettings(maxForwardBytes = 2 * size))
    val x = chain.on(chain.first)(one(1))
    x.layered()
    val y = chain.on(chain.first)(one(2))
    y.layered()
    chain.state.overlayUsage.bytes shouldBe 2 * size
    val z = chain.on(chain.first)(one(3))
    z.layered()
    gone(chain)(x.ref)
    held(chain)(y.ref, z.ref)
    chain.state.overlayUsage shouldBe OverlayUsage(layers = 2, bytes = 2 * size, 0, 0L, evictions = 1)

    // one byte less than two layers hold: one is kept
    val tight = new Chain(CommitSettings(maxForwardBytes = 2 * size - 1))
    val first = tight.on(tight.first)(one(1))
    first.layered()
    tight.on(tight.first)(one(2)).layered()
    gone(tight)(first.ref)

    // a layer that is by itself more than a layer may be is not kept, and nothing goes for it
    val bounded = new Chain(CommitSettings(maxLayerBytes = size))
    val small = bounded.on(bounded.first)(one(1))
    small.layered()
    val large = bounded.on(bounded.first)(one(2), slots(bob, (1 to 30).map(i => i -> (i + 7))*))
    val bytes = new Chain().on(bounded.first)(one(2), slots(bob, (1 to 30).map(i => i -> (i + 7))*)).layered().bytes
    bytes should be > size
    bounded.state.commitOverlay(large.staged, large.ref) shouldBe
      OverlayOutcome.NotKept(NotKeptReason.TooLarge(bytes, size))
    held(bounded)(small.ref)
    gone(bounded)(large.ref)
    bounded.state.overlayUsage shouldBe OverlayUsage(layers = 1, bytes = size, 0, 0L, evictions = 0)
    // and the block is committed in place all the same, by a caller that adopts it
    large.inPlace().block shouldBe large.ref
  }

  "The layers of a branch under execution" should "all be kept while it is pinned, whatever the others are bounded to, and the bounds apply once it is released" taggedAs
    UnitTest in {
      // two of the others, by their number and by their bytes: what the branch holds is counted against neither
      val chain = new Chain(CommitSettings(maxForwardLayers = 2, maxForwardBytes = 2 * size))
      val u1 = chain.on(chain.first)(one(91))
      u1.layered()

      val (executed, u2, u3) = chain.state.withPinnedBranch("a heavier branch") { pin =>
        val executed = (1 to 6).foldLeft(Vector.empty[chain.Executed]) { (done, n) =>
          val next = chain.on(done.lastOption.fold(chain.first)(_.ref))(one(n))
          keptWith(chain, pin)(next)
          done :+ next
        }
        val branch = executed.map(_.ref)
        chain.state.overlayUsage shouldBe OverlayUsage(layers = 1, bytes = size, 6, 6 * size, evictions = 0)

        // the others come and go beside it, within their own bound, and no layer of the branch goes for them
        val u2 = chain.on(chain.first)(one(92))
        u2.layered()
        val u3 = chain.on(chain.first)(one(93))
        u3.layered()
        gone(chain)(u1.ref)
        held(chain)(branch*)
        // nor for a layer kept on the branch that the branch does not hold
        val onBranch = chain.on(branch.last)(one(94))
        onBranch.layered()
        gone(chain)(u2.ref)
        held(chain)(branch*)
        chain.state.dropLayer(onBranch.ref.hash)
        u2.layered()
        chain.state.overlayUsage shouldBe OverlayUsage(layers = 2, bytes = 2 * size, 6, 6 * size, evictions = 2)
        (executed, u2, u3)
      }
      val branch = executed.map(_.ref)

      // released: nothing goes by that alone
      chain.state.overlayUsage shouldBe OverlayUsage(layers = 8, bytes = 8 * size, 0, 0L, evictions = 2)
      held(chain)(branch*)
      // nor when a block of it is delivered again: it is the layer there is, and nothing is kept that was not
      chain.state.commitOverlay(executed(2).staged, executed(2).ref) shouldBe a[OverlayOutcome.Kept]
      chain.state.overlayUsage shouldBe OverlayUsage(layers = 8, bytes = 8 * size, 0, 0L, evictions = 2)
      // the next layer that is kept finds eight where two may be. What the branch left was in use when it was
      // released, after the others: they go first, and then the branch from its tip down
      val u4 = chain.on(chain.first)(one(95))
      u4.layered()
      gone(chain)(u2.ref, u3.ref)
      gone(chain)(branch.tail*)
      held(chain)(branch.head, u4.ref)
      chain.state.overlayUsage shouldBe OverlayUsage(layers = 2, bytes = 2 * size, 0, 0L, evictions = 9)
    }

  it should "count as used when it is released: what was kept beside it meanwhile goes before it" taggedAs UnitTest in {
    val chain = new Chain(CommitSettings(maxForwardLayers = 3))
    val (b1, b2, beside) = chain.state.withPinnedBranch("a branch") { pin =>
      val b1 = chain.on(chain.first)(one(1))
      keptWith(chain, pin)(b1)
      val b2 = chain.on(b1.ref)(one(2))
      keptWith(chain, pin)(b2)
      // kept after the branch's blocks were, and held by nothing
      val beside = chain.on(chain.first)(one(3))
      beside.layered()
      (b1.ref, b2.ref, beside.ref)
    }
    // three, which may be kept. One more: the block beside the branch, and not the branch's tip
    val next = chain.on(chain.first)(one(4))
    next.layered()
    gone(chain)(beside)
    held(chain)(b1, b2, next.ref)
  }

  it should "be refused, as a fault to try again after, when the pinned layers would hold more than they may" taggedAs
    UnitTest in {
      // pinned layers are held to no bound of one layer: a branch is as large as its blocks are
      val chain = new Chain(CommitSettings(pinnedMaxBytes = 3 * size, maxLayerBytes = size - 1))
      chain.state.withPinnedBranch("a long branch") { pin =>
        val kept = (1 to 3).foldLeft(Vector.empty[BlockRef]) { (done, n) =>
          val next = chain.on(done.lastOption.getOrElse(chain.first))(one(n))
          keptWith(chain, pin)(next)
          done :+ next.ref
        }
        val fourth = chain.on(kept.last)(one(4))
        val fault = the[BranchTooLarge] thrownBy chain.state.commitOverlay(fourth.staged, fourth.ref, Some(pin))
        (fault.bytes, fault.cap) shouldBe (4 * size, 3 * size)
        fault shouldBe a[StateFault]
        gone(chain)(fourth.ref)
        held(chain)(kept*)
        chain.state.overlayUsage shouldBe OverlayUsage(0, 0L, pinnedLayers = 3, pinnedBytes = 3 * size, evictions = 0)
        chain.state.lock.holder shouldBe None
        // without the pin the block is held to the bound of one layer
        chain.state.commitOverlay(fourth.staged, fourth.ref) shouldBe
          OverlayOutcome.NotKept(NotKeptReason.TooLarge(size, size - 1))
      }
    }

  it should "take hold of nothing when it is refused, and leave the layers it stood on as they were" taggedAs UnitTest in {
    val chain = new Chain(CommitSettings(pinnedMaxBytes = 2 * size))
    val b = chain.on(chain.first)(one(1))
    b.layered()
    val c = chain.on(b.ref)(one(2))
    c.layered()
    chain.state.withPinnedBranch("a branch on two blocks that are kept") { pin =>
      // with the two it stands on it would hold three, and may hold two
      val d = chain.on(c.ref)(one(3))
      val fault = the[BranchTooLarge] thrownBy chain.state.commitOverlay(d.staged, d.ref, Some(pin))
      (fault.bytes, fault.cap) shouldBe (3 * size, 2 * size)
      gone(chain)(d.ref)
      chain.state.overlayUsage shouldBe OverlayUsage(layers = 2, bytes = 2 * size, 0, 0L, evictions = 0)
    }
    held(chain)(b.ref, c.ref)
  }

  it should "not be pinned by the holder of the state lock, which its release needs" taggedAs UnitTest in {
    val chain = new Chain()
    var ran = false
    val refused = the[IllegalStateException] thrownBy chain.state.lock.locked("a commit") {
      chain.state.withPinnedBranch("inside a commit") { _ =>
        ran = true
      }
    }
    refused.getMessage should include("cannot be pinned by the holder of the state lock")
    ran shouldBe false
    chain.state.lock.holder shouldBe None
  }

  it should "hold what an earlier branch left to the bounds when its own first block is kept" taggedAs UnitTest in {
    val chain = new Chain(CommitSettings(maxForwardLayers = 2))
    val first = chain.state.withPinnedBranch("a branch that is not adopted") { pin =>
      (1 to 4).foldLeft(Vector.empty[BlockRef]) { (done, n) =>
        val next = chain.on(done.lastOption.getOrElse(chain.first))(one(n))
        keptWith(chain, pin)(next)
        done :+ next.ref
      }
    }
    chain.state.overlayUsage shouldBe OverlayUsage(layers = 4, bytes = 4 * size, 0, 0L, evictions = 0)
    // another branch, and nothing kept in between: what the first left goes from its tip down, to what may be kept
    chain.state.withPinnedBranch("the next branch") { pin =>
      val other = chain.on(chain.first)(one(9))
      keptWith(chain, pin)(other)
      chain.state.overlayUsage shouldBe OverlayUsage(layers = 2, bytes = 2 * size, 1, size, evictions = 2)
      held(chain)(first(0), first(1), other.ref)
      gone(chain)(first(2), first(3))
    }
  }

  "The layers a branch left" should "stay as they are when a block that stands on them finds no room in the end" taggedAs
    UnitTest in {
      val chain = new Chain(CommitSettings(maxForwardLayers = 2))
      val (b, c, s) = chain.state.withPinnedBranch("two branches") { pin =>
        val b = chain.on(chain.first)(one(1))
        keptWith(chain, pin)(b)
        val c = chain.on(b.ref)(one(2))
        keptWith(chain, pin)(c)
        val s = chain.on(chain.first)(one(3))
        keptWith(chain, pin)(s)
        (b.ref, c.ref, s.ref)
      }
      // three where two may be. A block on c: s could go for it, and b and c, which it stands on, could not; that
      // would be three still. So nothing goes
      val d = chain.on(c)(one(4))
      chain.state.commitOverlay(d.staged, d.ref) shouldBe OverlayOutcome.NotKept(NotKeptReason.NoRoom)
      held(chain)(b, c, s)
      chain.state.overlayUsage shouldBe OverlayUsage(layers = 3, bytes = 3 * size, 0, 0L, evictions = 0)
      // a block beside them finds room: s, and then c
      val t = chain.on(chain.first)(one(5))
      t.layered()
      gone(chain)(s, c)
      held(chain)(b, t.ref)
    }

  "The layers of a branch under execution" should "take hold of the kept layers it stands on and of a layer delivered again, each counted once whoever holds it" taggedAs
    UnitTest in {
      val chain = new Chain(CommitSettings(maxForwardLayers = 2, pinnedMaxBytes = 3 * size))
      val b = chain.on(chain.first)(one(1))
      b.layered()
      val c = chain.on(b.ref)(one(2))
      c.layered()
      chain.state.overlayUsage shouldBe OverlayUsage(layers = 2, bytes = 2 * size, 0, 0L, evictions = 0)

      chain.state.withPinnedBranch("a branch") { pin =>
        val d = chain.on(c.ref)(one(3))
        keptWith(chain, pin)(d)
        // b and c are the branch's now
        chain.state.overlayUsage shouldBe OverlayUsage(0, 0L, pinnedLayers = 3, pinnedBytes = 3 * size, evictions = 0)

        // a second branch takes hold of c, which is there already, and so of b: three layers still, in what they may hold
        chain.state.withPinnedBranch("another") { other =>
          keptWith(chain, other)(c) shouldBe chain.state.forwardLayer(c.ref.hash).get
          chain.state.overlayUsage.pinnedBytes shouldBe 3 * size
          // a block more of it would be four
          val e = chain.on(c.ref)(one(5))
          a[BranchTooLarge] should be thrownBy chain.state.commitOverlay(e.staged, e.ref, Some(other))
        }
        // the second is released: the first holds all three still
        chain.state.overlayUsage shouldBe OverlayUsage(0, 0L, pinnedLayers = 3, pinnedBytes = 3 * size, evictions = 0)
        // so two others fit beside them, and a third takes the place of one of those
        val (s, t, r) = (chain.on(chain.first)(one(11)), chain.on(chain.first)(one(12)), chain.on(chain.first)(one(13)))
        Seq(s, t, r).foreach(_.layered())
        gone(chain)(s.ref)
        held(chain)(b.ref, c.ref, d.ref, t.ref, r.ref)
      }
      chain.state.overlayUsage.pinnedLayers shouldBe 0
    }

  it should "be released however its execution ends, and leave nothing when it was promoted first" taggedAs UnitTest in {
    val chain = new Chain()
    val b = chain.on(chain.first)(one(1))
    (the[IllegalStateException] thrownBy chain.state.withPinnedBranch("a branch that fails") { pin =>
      keptWith(chain, pin)(b)
      chain.state.overlayUsage.pinnedLayers shouldBe 1
      throw new IllegalStateException("the second block of the branch is invalid")
    } should have).message("the second block of the branch is invalid")
    chain.state.overlayUsage shouldBe OverlayUsage(layers = 1, bytes = size, 0, 0L, evictions = 0)
    chain.state.lock.holder shouldBe None

    // adopted: promoted block by block while it is pinned, and released after
    chain.state.withPinnedBranch("the branch, again") { pin =>
      // b is there still, held by nothing: the branch takes hold of it
      keptWith(chain, pin)(b)
      chain.state.overlayUsage shouldBe OverlayUsage(0, 0L, pinnedLayers = 1, pinnedBytes = size, evictions = 0)
      val c = chain.on(b.ref)(one(2))
      keptWith(chain, pin)(c)
      chain.promote(b.ref)
      chain.state.overlayUsage.pinnedLayers shouldBe 1
      chain.promote(c.ref)
      chain.state.anchor shouldBe Some(c.ref)
    }
    chain.state.overlayUsage shouldBe OverlayUsage(0, 0L, 0, 0L, evictions = 0)
  }

  "Which layer goes first" should "be a fork whose fork point is the oldest block the records lead back to, however lately it was used" taggedAs
    UnitTest in {
      val chain = new Chain(CommitSettings(maxForwardLayers = 3))
      val b1 = chain.on(chain.first)(one(1))
      b1.inPlace()
      val b2 = chain.on(b1.ref)(one(2))
      b2.inPlace()
      // the anchor's chain is the first block, b1, b2: the records lead back to the first
      val onHead = chain.on(b2.ref)(one(31))
      onHead.layered()
      val nearFork = chain.onAny(b1.ref)(one(32))
      nearFork.layered()
      val farFork = chain.onAny(chain.first)(one(33))
      farFork.layered()
      // the far fork is the one last used
      chain.state.stageOf(farFork.ref)

      val next = chain.on(b2.ref)(one(34))
      next.layered()
      gone(chain)(farFork.ref)
      held(chain)(onHead.ref, nearFork.ref, next.ref)
    }

  it should "between layers used alike be a fork's before one on the anchor's chain, and the lower block first" taggedAs
    UnitTest in {
      val chain = new Chain(CommitSettings(maxForwardLayers = 4))
      val refs = (1 to 3).foldLeft(Vector(chain.first)) { (done, n) =>
        val next = chain.on(done.last)(one(n))
        next.inPlace()
        done :+ next.ref
      }
      val (head, behind) = (refs.last, refs(2))
      // what one pin held is in use alike when the pin is released: a layer on the head, a fork of one block from the
      // block behind it, and a fork of two from there
      val (onHead, short, long1, long2) = chain.state.withPinnedBranch("three branches") { pin =>
        val onHead = chain.on(head)(one(41))
        val short = chain.onAny(behind)(one(42))
        val long1 = chain.onAny(behind)(one(43))
        Seq(onHead, short, long1).foreach(keptWith(chain, pin)(_))
        val long2 = chain.onAny(long1.ref)(one(44))
        keptWith(chain, pin)(long2)
        (onHead.ref, short.ref, long1.ref, long2.ref)
      }
      short.number should be < long2.number
      long2.number shouldBe onHead.number

      // four may be kept. One more: the fork's tip at the lower block
      val n1 = chain.on(head)(one(51))
      n1.layered()
      gone(chain)(short)
      held(chain)(onHead, long1, long2)
      // one more: the other fork's tip, before the layer on the head at the same height
      val n2 = chain.on(head)(one(52))
      n2.layered()
      gone(chain)(long2)
      held(chain)(onHead, long1)
      // then the block that fork stood on, which nothing stands on now; and only then the layer on the head
      val n3 = chain.on(head)(one(53))
      n3.layered()
      gone(chain)(long1)
      held(chain)(onHead)
      val n4 = chain.on(head)(one(54))
      n4.layered()
      gone(chain)(onHead)
      held(chain)(n1.ref, n2.ref, n3.ref, n4.ref)
    }

  "What the overlay holds" should "be told after each change of it, with the views that were made again and those that could not be" taggedAs
    UnitTest in {
      val told = mutable.Buffer.empty[OverlayUsage]
      var (retried, stale) = (0, 0)
      var evictedTold = 0L
      val observer = new CommitObserver:
        override def overlay(usage: OverlayUsage): Unit = told += usage
        override def evicted(layers: Long): Unit = evictedTold += layers
        override def viewRetried(): Unit = retried += 1
        override def viewStale(): Unit = stale += 1
      val chain = new Chain(CommitSettings(maxForwardLayers = 1, observer = observer))

      val b = chain.on(chain.first)(one(1))
      b.layered()
      told.last shouldBe OverlayUsage(layers = 1, bytes = size, 0, 0L, evictions = 0)
      val s = chain.on(chain.first)(one(2))
      s.layered()
      told.last shouldBe OverlayUsage(layers = 1, bytes = size, 0, 0L, evictions = 1)
      evictedTold shouldBe 1
      chain.state.withPinnedBranch("a branch") { pin =>
        keptWith(chain, pin)(b)
        told.last shouldBe OverlayUsage(layers = 1, bytes = size, 1, size, evictions = 1)
      }
      told.last shouldBe OverlayUsage(layers = 2, bytes = 2 * size, 0, 0L, evictions = 1)
      chain.state.dropLayer(s.ref.hash)
      told.last shouldBe OverlayUsage(layers = 1, bytes = size, 0, 0L, evictions = 1)
      // each eviction was told once, however often the overlay was told of
      evictedTold shouldBe 1

      // a view of a block is made while its parent is promoted between the two looks: made again, once
      val grown = new Chain(CommitSettings(observer = observer))
      val gb = grown.on(grown.first)(one(1))
      gb.layered()
      val gc = grown.on(gb.ref)(one(3))
      gc.layered()
      var promoted = false
      grown.state.viewAfter(
        gc.ref,
        () =>
          if !promoted then
            promoted = true
            grown.promote(gb.ref)
      )
      (retried, stale) shouldBe (1, 0)
      told.last shouldBe OverlayUsage(layers = 1, bytes = size, 0, 0L, evictions = 0)

      // the disk moves on every time a view is being made: it is given up
      a[StaleStateViewException] should be thrownBy
        grown.state.viewAfter(gc.ref, () => grown.state.clock.inFlight(StateMove(gb.ref, Map.empty))(()))
      stale shouldBe 1
      retried should be > 1
    }

  it should "count the reverse indexes that are kept, within what they may hold together" taggedAs UnitTest in {
    def grown(settings: CommitSettings): (Chain, Vector[BlockRef]) =
      val chain = new Chain(settings)
      val refs = (1 to 4).foldLeft(Vector(chain.first)) { (done, n) =>
        val next = chain.on(done.last)(one(n), slots(alice, n -> n))
        next.inPlace()
        done :+ next.ref
      }
      (chain, refs)

    val (chain, refs) = grown(CommitSettings())
    chain.state.overlayUsage.reverseIndexes shouldBe 0
    chain.readThrough(PathMptStorage.readOnly(chain.state.viewOf(refs(3))), refs(3))
    val one0 = chain.state.overlayUsage
    one0.reverseIndexes shouldBe 1
    one0.reverseBytes should be > 0L
    // asked again, it is the index there is
    chain.state.viewOf(refs(3))
    chain.state.overlayUsage shouldBe one0
    chain.state.viewOf(refs(2))
    val two = chain.state.overlayUsage
    two.reverseIndexes shouldBe 2
    two.reverseBytes should be > one0.reverseBytes
    val second = two.reverseBytes - one0.reverseBytes

    // room for either and not for both: the one that is asked for is the one that is kept
    val (tight, same) = grown(CommitSettings(maxReversePoolBytes = two.reverseBytes - 1))
    same shouldBe refs
    tight.state.viewOf(refs(3))
    tight.state.viewOf(refs(2))
    tight.state.overlayUsage.reverseIndexes shouldBe 1
    tight.state.overlayUsage.reverseBytes shouldBe second
    // an index that is by itself more than they may hold is built for its view, and not kept
    val (none, _) = grown(CommitSettings(maxReversePoolBytes = one0.reverseBytes - 1))
    none.readThrough(PathMptStorage.readOnly(none.state.viewOf(refs(3))), refs(3))
    none.state.overlayUsage.reverseIndexes shouldBe 0
  }
