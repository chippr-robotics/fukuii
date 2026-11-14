package com.chipprbots.ethereum.console

import java.time.Instant

import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** The pool observer (spec 029 FR-031 to FR-033): tiers, what arrived, and what became of what left. */
class PoolObserverSpec extends AnyFlatSpec with Matchers with OptionValues:

  private val t0 = Instant.parse("2026-11-07T12:00:00Z")
  private val gwei = BigInt(1_000_000_000L)

  private def tx(
      n: Int,
      sender: String = "0xaa",
      tip: BigInt = gwei,
      nonce: Int = -1,
      local: Boolean = false,
      blob: Boolean = false
  ) =
    PoolTx(
      f"0x$n%064x",
      sender,
      BigInt(if nonce < 0 then n else nonce),
      tip,
      tip * 2,
      blob,
      local,
      t0.minusSeconds(n.toLong)
    )

  private def observe(
      state: PoolObserver.State,
      txs: Seq[PoolTx],
      at: Instant,
      mined: Set[String] = Set.empty,
      suggested: Option[BigInt] = Some(gwei)
  ) = PoolObserver.observe(state, txs, hash => Some(mined.contains(hash)), suggested, 1_000, at)

  "The tiers" should "be the fee against the node's own suggestion: below half, to one and a half, to three times, above" taggedAs (
    UnitTest
  ) in {
    FeeTier.of(gwei * 49 / 100, gwei) shouldBe FeeTier.Low
    FeeTier.of(gwei / 2, gwei) shouldBe FeeTier.Normal
    FeeTier.of(gwei * 3 / 2, gwei) shouldBe FeeTier.Normal
    FeeTier.of(gwei * 151 / 100, gwei) shouldBe FeeTier.High
    FeeTier.of(gwei * 3, gwei) shouldBe FeeTier.High
    FeeTier.of(gwei * 3 + 1, gwei) shouldBe FeeTier.Urgent
    // A suggestion of nothing: every transaction pays at least it.
    FeeTier.of(BigInt(0), BigInt(0)) shouldBe FeeTier.Normal
    val pool = Seq(tx(1, tip = gwei / 4), tx(2), tx(3), tx(4, tip = gwei * 2), tx(5, tip = gwei * 10))
    val (_, sample) = observe(PoolObserver.State(), pool, t0)
    sample.tiers shouldBe Map(FeeTier.Low -> 1, FeeTier.Normal -> 2, FeeTier.High -> 1, FeeTier.Urgent -> 1)
    // With no suggestion there is nothing to measure against: no tier is claimed.
    observe(PoolObserver.State(), pool, t0, suggested = None)._2.tiers shouldBe empty
  }

  // prism, slice 4: the boundary shown for a tier was rounded down while the tier itself was not, so on a chain with
  // fees of a few wei the row said "from 2" for a tier that starts at 3.
  "A tier's boundary" should "be the least fee in the tier, whatever the suggestion: odd, tiny or large" taggedAs (
    UnitTest
  ) in {
    FeeTier.boundaries(gwei * 2) shouldBe Seq(
      FeeTier.Normal -> gwei,
      FeeTier.High -> (gwei * 3 + 1),
      FeeTier.Urgent -> (gwei * 6 + 1)
    )
    val below = Map(FeeTier.Normal -> FeeTier.Low, FeeTier.High -> FeeTier.Normal, FeeTier.Urgent -> FeeTier.High)
    for
      suggested <- Seq(1, 2, 3, 5, 7, 100, 101, 1_000_000_007).map(BigInt(_));
      (tier, least) <- FeeTier.boundaries(suggested)
    do
      withClue(s"suggested $suggested, $tier from $least: ") {
        FeeTier.of(least, suggested) shouldBe tier
        if least > 0 then FeeTier.of(least - 1, suggested) shouldBe below(tier)
      }
  }

  "A sample" should "count the pool: size, blobs, local ones, the oldest, the busiest senders, the best-paying first" taggedAs (
    UnitTest
  ) in {
    val pool = Seq(
      tx(1, "0xaa", local = true),
      tx(2, "0xaa", tip = gwei * 5),
      tx(3, "0xbb", blob = true),
      tx(40, "0xaa"),
      tx(5, "0xcc", tip = gwei * 9)
    )
    val (_, s) = observe(PoolObserver.State(), pool, t0)
    (s.size, s.blobs, s.local, s.capacity) shouldBe (5, 1, 1, 1_000)
    s.at shouldBe t0
    s.oldest.value shouldBe t0.minusSeconds(40)
    s.topSenders shouldBe Seq("0xaa" -> 3, "0xbb" -> 1, "0xcc" -> 1)
    s.txs.map(_.tip) shouldBe Seq(gwei * 9, gwei * 5, gwei, gwei, gwei)
    // The first sight of the pool is not an arrival of everything in it.
    s.arrivedPerSecond shouldBe None
  }

  "What left the pool" should "be told apart: mined, replaced by the same sender and nonce, or dropped" taggedAs (
    UnitTest
  ) in {
    val before = Seq(tx(1), tx(2), tx(3, "0xbb", nonce = 7), tx(4))
    val (s0, _) = observe(PoolObserver.State(), before, t0)
    // 1 was mined, 2 dropped, 3 replaced by 30 (same sender, same nonce), 4 stays, 5 arrives.
    val after = Seq(tx(30, "0xbb", nonce = 7, tip = gwei * 2), tx(4), tx(5))
    val (s1, sample) = observe(s0, after, t0.plusSeconds(1), mined = Set(tx(1).hash))
    s1.steps.last shouldBe PoolObserver.Step(t0, t0.plusSeconds(1), arrived = 2, included = 1, dropped = 1)
    sample.replaced shouldBe 1
    (sample.awaiting, sample.leftUnchecked) shouldBe (0, 0L)
    sample.watchedSince shouldBe t0
  }

  it should "count a transaction whose replacement was mined before the next poll as dropped, which it was" taggedAs (
    UnitTest
  ) in {
    // 3 was replaced by 30, and 30 was mined, both between two polls: the pool holds neither.
    val (s0, _) = observe(PoolObserver.State(), Seq(tx(3, "0xbb", nonce = 7), tx(4)), t0)
    val (s1, sample) = observe(s0, Seq(tx(4)), t0.plusSeconds(1))
    s1.steps.last shouldBe PoolObserver.Step(t0, t0.plusSeconds(1), arrived = 0, included = 0, dropped = 1)
    sample.replaced shouldBe 0
  }

  // prism, slice 4: the sum counted the first step and the divisor left its interval out, so six arrivals in six
  // seconds read as 1.2 a second.
  "The rates" should "be what moved over exactly the time it was counted in, from five seconds of watching" taggedAs (
    UnitTest
  ) in {
    // Each second, one transaction arrives and the oldest is mined.
    val states = (1 to 10).scanLeft(observe(PoolObserver.State(), Seq(tx(0)), t0)) { case ((state, _), i) =>
      observe(state, Seq(tx(i)), t0.plusSeconds(i.toLong), mined = Set(tx(i - 1).hash))
    }
    states(4)._2.arrivedPerSecond shouldBe None // four seconds is too little
    for i <- 5 to 10 do
      withClue(s"after $i s: ") {
        states(i)._2.arrivedPerSecond.value shouldBe 1.0
        states(i)._2.includedPerSecond.value shouldBe 1.0
        states(i)._2.droppedPerSecond.value shouldBe 0.0
      }
  }

  it should "forget what moved more than a minute ago" taggedAs (UnitTest) in {
    // Two arrivals a poll for 30 s, then none for 90 s, a poll every 3 s.
    val busy = (1 to 10).scanLeft(observe(PoolObserver.State(), Nil, t0)) { case ((state, _), i) =>
      observe(state, (1 to 2 * i).map(tx(_)), t0.plusSeconds(3L * i))
    }
    busy.last._2.arrivedPerSecond.value shouldBe (20.0 / 30.0) +- 1e-9
    val quiet = (11 to 40).scanLeft(busy.last) { case ((state, _), i) =>
      observe(state, (1 to 20).map(tx(_)), t0.plusSeconds(3L * i))
    }
    // At 63 s the first step ended exactly a minute ago, and is still counted: 20 arrivals in 63 s.
    val at63 = quiet(11)
    at63._1.steps.head.from shouldBe t0
    at63._2.arrivedPerSecond.value shouldBe (20.0 / 63.0) +- 1e-9
    // At 66 s it has left the window, with its two arrivals and its three seconds: 18 arrivals in 63 s.
    val at66 = quiet(12)
    at66._1.steps.head.from shouldBe t0.plusSeconds(3)
    at66._2.arrivedPerSecond.value shouldBe (18.0 / 63.0) +- 1e-9
    // At 120 s every step with an arrival is more than a minute old.
    val last = quiet.last
    last._1.steps.map(_.arrived).sum shouldBe 0
    last._2.arrivedPerSecond.value shouldBe 0.0
    all(last._1.steps.map(s => java.time.Duration.between(s.at, t0.plusSeconds(120)).getSeconds)) should be <= 60L
  }

  "A pool not watched for a while" should "be seen afresh: what moved while it was not watched is not counted" taggedAs (
    UnitTest
  ) in {
    // A watch with replacements, departures never told apart, some waiting, and steps.
    val watched = PoolObserver.State(
      previous = (1 to 50).map(tx(_)).map(t => t.hash -> t).toMap,
      seenAt = Some(t0),
      since = Some(t0.minusSeconds(40)),
      steps = Vector(PoolObserver.Step(t0.minusSeconds(1), t0, 4, 2, 1)),
      replaced = 3,
      awaiting = Vector("0xdead"),
      leftUnchecked = 2
    )
    // Exactly the longest gap: still the same watch, and everything is compared.
    val (kept, same) = observe(watched, (1 to 50).map(tx(_)), t0.plus(PoolObserver.MaxGap))
    (same.replaced, same.leftUnchecked, same.watchedSince) shouldBe (3L, 2L, t0.minusSeconds(40))
    kept.steps should have size 2
    // A millisecond more: another pool altogether is not twenty-one arrivals and fifty departures.
    val later = t0.plus(PoolObserver.MaxGap).plusMillis(1)
    val (fresh, sample) = observe(watched, (100 to 120).map(tx(_)), later)
    fresh shouldBe PoolObserver.State(
      (100 to 120).map(tx(_)).map(t => t.hash -> t).toMap,
      seenAt = Some(later),
      since = Some(later)
    )
    (sample.replaced, sample.leftUnchecked, sample.awaiting) shouldBe (0L, 0L, 0)
    sample.arrivedPerSecond shouldBe None
    sample.watchedSince shouldBe later
    sample.size shouldBe 21
  }

  // prism, slice 4: only the first 500 departures of a poll were told apart and the rest forgotten, so a full block
  // leaving a full pool showed about a third of what was mined.
  "More departures than one poll looks up" should "wait for the next polls, and be told apart then" taggedAs (
    UnitTest
  ) in {
    val many = (1 to PoolObserver.MaxChecks + 40).map(tx(_))
    val (s0, _) = observe(PoolObserver.State(), many, t0)
    var checks = 0
    def mined(hash: String): Option[Boolean] =
      checks += 1
      Some(hash.nonEmpty)
    val (s1, first) = PoolObserver.observe(s0, Nil, mined, Some(gwei), 1_000, t0.plusSeconds(1))
    checks shouldBe PoolObserver.MaxChecks
    (first.awaiting, first.leftUnchecked) shouldBe (40, 0L)
    s1.steps.last.included shouldBe PoolObserver.MaxChecks
    // The next poll looks up the forty that waited: every departure is counted once, none guessed.
    val (s2, second) = PoolObserver.observe(s1, Nil, mined, Some(gwei), 1_000, t0.plusSeconds(2))
    checks shouldBe PoolObserver.MaxChecks + 40
    (second.awaiting, second.leftUnchecked) shouldBe (0, 0L)
    s2.steps.map(_.included).sum shouldBe many.size
    s2.steps.map(_.dropped).sum shouldBe 0
  }

  it should "count as never told apart only what waits past the bound" taggedAs (UnitTest) in {
    val flood = (1 to PoolObserver.MaxChecks + PoolObserver.MaxAwaiting + 7).map(tx(_))
    val (s0, _) = observe(PoolObserver.State(), flood, t0)
    val (s1, sample) = observe(s0, Nil, t0.plusSeconds(1))
    s1.awaiting should have size PoolObserver.MaxAwaiting.toLong
    (sample.awaiting, sample.leftUnchecked) shouldBe (PoolObserver.MaxAwaiting, 7L)
  }

  // prism, slice 4: a failed read of the index was taken for "not in the index", and a storage error read as a pool
  // dropping its transactions.
  "A departure the index cannot answer for" should "be counted as never told apart, not as dropped" taggedAs (
    UnitTest
  ) in {
    val (s0, _) = observe(PoolObserver.State(), Seq(tx(1), tx(2), tx(3)), t0)
    val answers = Map(tx(1).hash -> Some(true), tx(2).hash -> Some(false), tx(3).hash -> None)
    val (s1, sample) = PoolObserver.observe(s0, Nil, answers, Some(gwei), 1_000, t0.plusSeconds(1))
    s1.steps.last shouldBe PoolObserver.Step(t0, t0.plusSeconds(1), arrived = 0, included = 1, dropped = 1)
    sample.leftUnchecked shouldBe 1
    sample.awaiting shouldBe 0
  }
