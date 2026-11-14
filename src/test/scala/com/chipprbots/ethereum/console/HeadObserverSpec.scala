package com.chipprbots.ethereum.console

import java.time.Instant

import org.scalatest.LoneElement
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** The head observer (spec 029 FR-023, FR-026): new blocks, gaps and reorgs, each from a bounded walk. */
class HeadObserverSpec extends AnyFlatSpec with Matchers with OptionValues with LoneElement:

  private val t0 = Instant.parse("2026-11-07T12:00:00Z")

  /** A block of branch `branch` at `number`, the child of the same branch's block below it, or of `parent`. */
  private def block(number: Long, branch: String = "a", parent: Option[String] = None): BlockSummary =
    BlockSummary(
      number = number,
      hash = s"0x$branch$number",
      parentHash = parent.getOrElse(s"0x$branch${number - 1}"),
      timestamp = 1_700_000_000L + number * 13,
      gasUsed = BigInt(1_000_000) * (number % 8 + 1),
      gasLimit = BigInt(8_000_000),
      miner = "0x" + "9f" * 20,
      difficulty = BigInt(1),
      seenAt = t0
    )

  /** A store of blocks, counting what is read of it. */
  final private class Store(blocks: Seq[BlockSummary]):
    private val byHash = blocks.map(b => b.hash -> b).toMap
    var reads = 0
    var bodies = 0
    val parentOf: (String, Boolean) => Option[BlockSummary] = (hash, body) =>
      reads += 1
      if body then bodies += 1
      byHash.get(hash).map(b => if body then b.copy(txCount = Some(3)) else b)

  private def chain(from: Long, to: Long, branch: String = "a") = (from to to).map(block(_, branch))

  private def step(state: HeadObserver.State, head: BlockSummary, store: Store, at: Instant = t0, capacity: Int = 64) =
    HeadObserver.observe(state, Some(head), store.parentOf, at, capacity)

  "A straight chain" should "be followed block by block, with no reorg and nothing skipped" taggedAs (UnitTest) in {
    val store = new Store(chain(1, 20))
    val start = step(HeadObserver.State(), block(10), store)
    // The first poll reads back from the head, to fill the observatory.
    start.recent.map(_.number) shouldBe (1L to 10L)
    val next = (11L to 20L).foldLeft(start)((s, n) => step(s, block(n), store))
    next.recent.map(_.number) shouldBe (1L to 20L)
    next.reorgs shouldBe empty
    next.sampled shouldBe false
    // The same head again reads nothing.
    val reads = store.reads
    step(next, block(20), store) shouldBe next.copy(heads = next.heads :+ (t0 -> 20L))
    store.reads shouldBe reads
  }

  it should "keep its newest blocks only, and read at most eight bodies a poll" taggedAs (UnitTest) in {
    val store = new Store(chain(1, 200))
    val filled = step(HeadObserver.State(), block(200), store, capacity = 32)
    filled.recent.map(_.number) shouldBe (169L to 200L)
    store.reads shouldBe HeadObserver.MaxWalk
    store.bodies shouldBe HeadObserver.MaxBodies - 1 // the head's own is the caller's
    filled.recent.count(_.txCount.isDefined) shouldBe HeadObserver.MaxBodies - 1
  }

  "Blocks that arrive faster than they are read" should "leave a gap, and say the list is a sample" taggedAs (
    UnitTest
  ) in {
    val store = new Store(chain(1, 400))
    // A ring longer than one poll's walk: the blocks between two polls are not all read.
    val start = step(HeadObserver.State(), block(100), store, capacity = 200)
    val jumped = step(start, block(300), store, capacity = 200)
    jumped.reorgs shouldBe empty
    jumped.sampled shouldBe true
    jumped.recent.last.number shouldBe 300
    (jumped.recent.map(_.number) should contain).allOf(100L, 236L)
    jumped.recent.map(_.number) should not contain 101L
    jumped.sample.sampled shouldBe true
    // Once the ring holds only blocks read one after another, it is whole again.
    val settled = (301L to 400L).foldLeft(jumped)((s, n) => step(s, block(n), store, capacity = 100))
    settled.sampled shouldBe false
    // A ring no longer than the walk is refilled whole by one poll: the newest blocks, none skipped.
    val short = step(step(HeadObserver.State(), block(100), store), block(300), store)
    short.sampled shouldBe false
    short.recent.map(_.number) shouldBe (237L to 300L)
  }

  "A reorg of depth 3" should "be recorded with its depth, both tips and the block they share" taggedAs (UnitTest) in {
    // Branch b leaves the chain after block 7.
    val fork = block(8, "b", parent = Some("0xa7")) +: chain(9, 11, "b")
    val store = new Store(chain(1, 10) ++ fork)
    val before = step(HeadObserver.State(), block(10), store)
    val after = step(before, fork.last, store, at = t0.plusSeconds(5))
    val reorg = after.reorgs.loneElement
    reorg.depth shouldBe 3
    reorg.depthIsLowerBound shouldBe false
    reorg.oldTip.hash shouldBe "0xa10"
    reorg.newTip.hash shouldBe "0xb11"
    reorg.commonAncestor.value.hash shouldBe "0xa7"
    reorg.at shouldBe t0.plusSeconds(5)
    after.recent.map(_.hash) shouldBe (1 to 7).map(n => s"0xa$n") ++ (8 to 11).map(n => s"0xb$n")
    after.sampled shouldBe false
  }

  "A reorg deeper than the walk" should "be recorded as at least as deep as the blocks it is known to have left" taggedAs (
    UnitTest
  ) in {
    // Branch b shares nothing with what was seen: its blocks from 1 up are another chain.
    val other = chain(1, 100, "b")
    val store = new Store(chain(1, 100) ++ other)
    val before = step(HeadObserver.State(), block(100), store)
    val after = step(before, other.last, store)
    val reorg = after.reorgs.loneElement
    reorg.depthIsLowerBound shouldBe true
    reorg.depth shouldBe before.recent.size // every known block at the heights the walk covered
    reorg.commonAncestor shouldBe None
    reorg.oldTip.hash shouldBe "0xa100"
    after.recent.last.hash shouldBe "0xb100"
  }

  "A head whose parent the node does not have" should "be kept alone, as a sample" taggedAs (UnitTest) in {
    // A SNAP pivot: the header is there, its ancestors are not.
    val store = new Store(Nil)
    val pivot = step(HeadObserver.State(), block(9_000_000), store)
    pivot.recent.map(_.number) shouldBe Seq(9_000_000L)
    pivot.reorgs shouldBe empty
    val next = step(pivot, block(9_000_001), store)
    next.recent.map(_.number) shouldBe Seq(9_000_000L, 9_000_001L)
    next.sampled shouldBe false
  }

  "No head at all" should "change nothing" taggedAs (UnitTest) in {
    HeadObserver.observe(HeadObserver.State(), None, (_, _) => None, t0, 64) shouldBe HeadObserver.State()
  }

  "The reorgs kept" should "be the newest sixty-four" taggedAs (UnitTest) in {
    // The head flips between two siblings.
    val a = block(2, "a", parent = Some("0xa1"))
    val b = block(2, "b", parent = Some("0xa1"))
    val store = new Store(Seq(block(1), a, b))
    val start = step(HeadObserver.State(), a, store)
    val flipped = (1 to 100).foldLeft(start)((s, i) => step(s, if i % 2 == 1 then b else a, store))
    flipped.reorgs should have size HeadObserver.MaxReorgs.toLong
    all(flipped.reorgs.map(_.depth)) shouldBe 1
  }

  "The rates" should "be the head's advance and the gas of the blocks seen, over the last minute" taggedAs (UnitTest) in {
    val store = new Store(chain(1, 400))
    val start = step(HeadObserver.State(), block(100), store, at = t0)
    start.sample.blocksPerSecond shouldBe None // one poll is no rate
    val later = step(start, block(110), store, at = t0.plusSeconds(10))
    later.sample.blocksPerSecond.value shouldBe 1.0 +- 1e-9
    // The gas rate is the mean gas of the blocks seen in the window, at that rate.
    val seen = later.recent
    later.sample.gasPerSecond.value shouldBe (seen.map(b => BigDecimal(b.gasUsed)).sum.toDouble / seen.size) +- 1e-3
    // A poll older than the window is forgotten.
    val muchLater = step(later, block(111), store, at = t0.plusSeconds(120))
    muchLater.heads.map(_._2) shouldBe Seq(111L)
    muchLater.sample.blocksPerSecond shouldBe None
  }

  "Fee percentiles" should "be the tenth, fiftieth and ninetieth of a block's fees, and nothing for an empty block" taggedAs (
    UnitTest
  ) in {
    FeePercentiles.of(Nil) shouldBe None
    FeePercentiles.of(Seq(BigInt(7))) shouldBe Some(FeePercentiles(7, 7, 7))
    FeePercentiles.of((1 to 101).map(BigInt(_)).reverse) shouldBe Some(FeePercentiles(11, 51, 91))
    block(1).copy(gasUsed = 2_000_000, gasLimit = 8_000_000).gasShare shouldBe 0.25
    block(1).copy(gasLimit = 0).gasShare shouldBe 0.0
  }
