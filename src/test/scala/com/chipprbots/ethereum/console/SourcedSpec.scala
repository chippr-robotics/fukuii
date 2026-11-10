package com.chipprbots.ethereum.console

import java.time.Duration
import java.time.Instant

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** [[Sourced]]: each polled source's freshness, Fresh → Stale → Unresponsive (spec 027 FR-011, T037). */
class SourcedSpec extends AnyFlatSpec with Matchers:

  import Sourced.*

  private val t0 = Instant.parse("2026-11-07T12:00:00Z")
  private def at(seconds: Long): Instant = t0.plusSeconds(seconds)
  private val missed = "no reply within 500ms"

  private def next(previous: Option[Sourced[Int]], poll: Poll[Int], seconds: Long): Sourced[Int] =
    Sourced.next(previous, poll, at(seconds), missed)

  "Sourced.next" should "be Fresh with the time of an answer" taggedAs (UnitTest) in {
    next(None, Poll.Answered(7), 0) shouldBe Fresh(7, at(0))
    next(Some(Unresponsive("x")), Poll.Answered(8), 3) shouldBe Fresh(8, at(3))
    next(Some(Stale(1, at(0))), Poll.Answered(9), 4) shouldBe Fresh(9, at(4))
  }

  it should "go Fresh → Stale → Unresponsive while a source that answered stays silent" taggedAs (UnitTest) in {
    val fresh = next(None, Poll.Answered(7), 0)
    val stale = next(Some(fresh), Poll.Missed, 1)
    stale shouldBe Stale(7, at(0)) // the last answer, and when it came
    next(Some(stale), Poll.Missed, 5) shouldBe Stale(7, at(0)) // within UnresponsiveAfter of the last answer
    next(Some(stale), Poll.Missed, 6) should matchPattern { case Unresponsive(_) => }
    Sourced.UnresponsiveAfter shouldBe Duration.ofSeconds(5)
  }

  it should "be Unresponsive when a source never answered" taggedAs (UnitTest) in {
    next(None, Poll.Missed, 0) shouldBe Unresponsive(missed)
    // The first reason stays: it is the one that says what went wrong (prism, slice 2 review).
    next(Some(Unresponsive("earlier")), Poll.Missed, 1) shouldBe Unresponsive("earlier")
  }

  it should "be Unresponsive on a failure, whatever came before" taggedAs (UnitTest) in {
    next(Some(Fresh(7, at(0))), Poll.Failed("boom"), 1) shouldBe Unresponsive("boom")
    next(None, Poll.Failed("boom"), 0) shouldBe Unresponsive("boom")
  }

  "Sourced.value" should "show the last answer while Fresh or Stale, and nothing when Unresponsive" taggedAs (
    UnitTest
  ) in {
    Fresh(3, t0).value shouldBe Some(3)
    Stale(4, t0).value shouldBe Some(4)
    (Unresponsive("x"): Sourced[Int]).value shouldBe None
  }

  /** `n` handshaked peers (spec 027 slice 3: the peers source carries each peer, not only the count). */
  private def peers(n: Int): Seq[TuiPeer] = TuiFixtures.peerList(n)

  "NodeStatusSnapshot" should "derive the peer line from the peers source only" taggedAs (UnitTest) in {
    val sync = Fresh(SyncSample(100, 200, "Syncing"), t0)
    def snapshot(peers: Sourced[Seq[TuiPeer]]) = NodeStatusSnapshot(t0, 50, 90, peers, sync)
    snapshot(Fresh(peers(3), t0)).connectionStatus shouldBe "Connected"
    snapshot(Fresh(peers(0), t0)).connectionStatus shouldBe "Searching for peers"
    snapshot(Stale(peers(3), t0)).connectionStatus shouldBe "Connected"
    snapshot(Stale(peers(3), t0)).peerCount shouldBe 3
    snapshot(Unresponsive("x")).connectionStatus shouldBe "Unresponsive (peer manager)"
    snapshot(Unresponsive("x")).peerCount shouldBe 0
    // The sync side is untouched by the peers source.
    snapshot(Unresponsive("x")).syncStatus shouldBe "Syncing"
    snapshot(Unresponsive("x")).currentBlock shouldBe 100
  }

  it should "fall back to the local best block when the sync controller does not answer" taggedAs (UnitTest) in {
    val s = NodeStatusSnapshot(t0, 50, 90, Fresh(peers(3), t0), Unresponsive("x"))
    (s.currentBlock, s.bestBlock, s.syncStatus) shouldBe ((90L, 0L, "Unresponsive (sync controller)"))
    s.snapSync shouldBe None
    s.connectionStatus shouldBe "Connected"
  }

  "NodeStatusSnapshot.agedAt" should "turn both sources Stale, then Unresponsive, when no newer poll arrives" taggedAs (
    UnitTest
  ) in {
    val sample = SyncSample(100, 200, "Syncing")
    val s = NodeStatusSnapshot(t0, 50, 90, Fresh(peers(3), t0), Fresh(sample, t0))
    val staleAfter = Duration.ofMillis(2_500)
    s.agedAt(at(2), staleAfter) shouldBe s
    val late = s.agedAt(at(3), staleAfter)
    (late.peers, late.sync) shouldBe ((Stale(peers(3), t0), Stale(sample, t0)))
    late.connectionStatus shouldBe "Connected" // a stale source still shows its last answer
    val gone = s.agedAt(at(6), staleAfter)
    gone.peers shouldBe Unresponsive("no status poll for 6s")
    gone.sync shouldBe Unresponsive("no status poll for 6s")
    (gone.connectionStatus, gone.syncStatus) shouldBe ((
      "Unresponsive (peer manager)",
      "Unresponsive (sync controller)"
    ))
  }
