package com.chipprbots.ethereum.db.storage.pathstate

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C4a part 4: the reverse indexes that are kept ([[ReverseIndexCache]]). An execution's and a
  * reader's are kept apart, so that what anyone who can reach the node asks for costs block execution nothing.
  */
class ReverseIndexCacheSpec extends AnyFlatSpec with Matchers with LayerChains:

  import PathStateTestKit.*

  /** A chain of ten blocks in place, and the index from its head back to each block behind it. */
  final private class Indexes:
    val chain = new Chain()
    val refs: Vector[BlockRef] = (1 to 10).foldLeft(Vector(chain.first)) { (done, n) =>
      val next = chain.on(done.last)(slots(alice, n -> (n + 1)), account(dave, n, n))
      next.inPlace()
      done :+ next.ref
    }
    val head: BlockRef = refs.last

    /** The index between the head and the block `back` blocks behind it. */
    def back(back: Int): ReverseIndex =
      ReverseIndex.between(chain.records, head, refs(refs.size - 1 - back), 64, Long.MaxValue)
    val byDepth: Map[Int, ReverseIndex] = (1 to 9).map(n => n -> back(n)).toMap

    /** The depths whose index `cache` holds. Looked for as a reader, which takes nothing over, and in the order of the
      * depths: that is a use of each, so the shallowest is then the one longest unused.
      */
    def kept(cache: ReverseIndexCache): Set[Int] =
      (1 to 9).filter { depth =>
        val index = byDepth(depth)
        cache.find(index.from, index.to, 0L, reader = true).isDefined
      }.toSet

  "The kept indexes" should "be found by the two blocks they lie between and the epoch they were built at, and by nothing else" taggedAs
    UnitTest in {
      val indexes = new Indexes
      import indexes.*
      val cache = new ReverseIndexCache(maxKept = 4, maxBytes = Long.MaxValue)
      val index = byDepth(3)
      cache.find(index.from, index.to, 4L, reader = true) shouldBe None

      cache.keep(index, epoch = 4L, reader = true)
      cache.find(index.from, index.to, 4L, reader = true).exists(_ eq index) shouldBe true
      cache.find(index.from, index.to, 4L, reader = false).exists(_ eq index) shouldBe true
      cache.size shouldBe 1
      cache.bytes shouldBe index.bytes
      // another epoch: the records it was built from may be others now
      cache.find(index.from, index.to, 6L, reader = true) shouldBe None
      // other blocks
      cache.find(index.from, byDepth(2).to, 4L, reader = true) shouldBe None
      cache.find(byDepth(2).to, index.to, 4L, reader = true) shouldBe None

      // built again at a later epoch, it takes the place of the one there was
      val again = back(3)
      cache.keep(again, epoch = 6L, reader = true)
      cache.size shouldBe 1
      cache.find(index.from, index.to, 6L, reader = true).exists(_ eq again) shouldBe true
      cache.find(index.from, index.to, 4L, reader = true) shouldBe None

      cache.clear()
      cache.size shouldBe 0
      cache.bytes shouldBe 0L
      cache.find(index.from, index.to, 6L, reader = true) shouldBe None
    }

  "Each kind of caller" should "keep its own few, the least recently used going first" taggedAs UnitTest in {
    val indexes = new Indexes
    import indexes.*
    Seq(true, false).foreach { reader =>
      withClue(s"reader = $reader: ") {
        val cache = new ReverseIndexCache(maxKept = 3, maxBytes = Long.MaxValue)
        (1 to 3).foreach(depth => cache.keep(byDepth(depth), 0L, reader))
        cache.size shouldBe 3
        // the first is used again, so the second is the one longest unused
        cache.find(byDepth(1).from, byDepth(1).to, 0L, reader) should not be empty
        cache.keep(byDepth(4), 0L, reader)
        kept(cache) shouldBe Set(1, 3, 4)
        // `kept` used them in the order 1, 3, 4
        cache.keep(byDepth(5), 0L, reader)
        kept(cache) shouldBe Set(3, 4, 5)
        cache.size shouldBe 3
      }
    }
  }

  "A reader's index" should "make room only among readers': never at the cost of an execution's" taggedAs
    (UnitTest, ConsensusTest) in {
      val indexes = new Indexes
      import indexes.*
      val cache = new ReverseIndexCache(maxKept = 2, maxBytes = Long.MaxValue)
      cache.keep(byDepth(1), 0L, reader = false)
      cache.keep(byDepth(2), 0L, reader = false)
      // a sweep of reads over old blocks
      (3 to 9).foreach(depth => cache.keep(byDepth(depth), 0L, reader = true))
      kept(cache) shouldBe Set(1, 2, 8, 9)
      cache.size shouldBe 4

      // and where the bytes are what is short: the pool is the executions', and a reader's index is not kept
      val two = byDepth(1).bytes + byDepth(2).bytes
      val full = new ReverseIndexCache(maxKept = 4, maxBytes = two + byDepth(3).bytes - 1)
      full.keep(byDepth(1), 0L, reader = false)
      full.keep(byDepth(2), 0L, reader = false)
      full.keep(byDepth(3), 0L, reader = true)
      kept(full) shouldBe Set(1, 2)
      full.bytes shouldBe two
    }

  "An execution's index" should "take a reader's place when it must, and one of its own only when it has as many as it may or no reader's is left" taggedAs
    (UnitTest, ConsensusTest) in {
      val indexes = new Indexes
      import indexes.*
      val bytes = (1 to 4).map(byDepth(_).bytes)
      // each block writes a slot no block before it wrote: a deeper index holds more
      bytes shouldBe bytes.sorted.distinct

      // as many as it may have: its own longest unused goes, and the readers' stay
      val counted = new ReverseIndexCache(maxKept = 2, maxBytes = Long.MaxValue)
      counted.keep(byDepth(1), 0L, reader = true)
      counted.keep(byDepth(2), 0L, reader = true)
      counted.keep(byDepth(3), 0L, reader = false)
      counted.keep(byDepth(4), 0L, reader = false)
      counted.keep(byDepth(5), 0L, reader = false)
      kept(counted) shouldBe Set(1, 2, 4, 5)

      // the bytes are short and it has fewer than it may: a reader's goes, the longest unused of theirs, though the
      // execution's own was used longer ago; and no more go than must
      val short = new ReverseIndexCache(maxKept = 4, maxBytes = bytes(1) + bytes(2) + bytes(3))
      short.keep(byDepth(3), 0L, reader = false)
      short.keep(byDepth(1), 0L, reader = true)
      short.keep(byDepth(2), 0L, reader = true)
      short.keep(byDepth(4), 0L, reader = false)
      kept(short) shouldBe Set(2, 3, 4)
      short.bytes shouldBe bytes(1) + bytes(2) + bytes(3)

      // no reader's is left and the bytes are short still: then its own
      val none = new ReverseIndexCache(maxKept = 4, maxBytes = bytes(0) + bytes(2) - 1)
      none.keep(byDepth(1), 0L, reader = false)
      none.keep(byDepth(2), 0L, reader = true)
      kept(none) shouldBe Set(1, 2)
      none.keep(byDepth(3), 0L, reader = false)
      kept(none) shouldBe Set(3)
      none.bytes shouldBe bytes(2)
    }

  it should "be an execution's from the moment an execution uses one a reader built" taggedAs UnitTest in {
    val indexes = new Indexes
    import indexes.*
    val cache = new ReverseIndexCache(maxKept = 2, maxBytes = Long.MaxValue)
    cache.keep(byDepth(1), 0L, reader = true)
    cache.keep(byDepth(2), 0L, reader = true)
    // a reader that finds it changes nothing
    cache.find(byDepth(1).from, byDepth(1).to, 0L, reader = true) should not be empty
    cache.keep(byDepth(3), 0L, reader = true)
    kept(cache) shouldBe Set(1, 3)
    // an execution finds the first: no reader pushes it out from now on
    cache.find(byDepth(1).from, byDepth(1).to, 0L, reader = false) should not be empty
    (4 to 9).foreach(depth => cache.keep(byDepth(depth), 0L, reader = true))
    kept(cache) shouldBe Set(1, 8, 9)
  }

  "An index that is by itself more than all may hold" should "not be kept, and push nothing out" taggedAs UnitTest in {
    val indexes = new Indexes
    import indexes.*
    byDepth(9).bytes should be > byDepth(1).bytes
    val cache = new ReverseIndexCache(maxKept = 4, maxBytes = byDepth(9).bytes - 1)
    cache.keep(byDepth(1), 0L, reader = false)
    cache.keep(byDepth(9), 0L, reader = false)
    cache.keep(byDepth(9), 0L, reader = true)
    kept(cache) shouldBe Set(1)
    cache.bytes shouldBe byDepth(1).bytes
  }
