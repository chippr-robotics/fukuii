package com.chipprbots.ethereum.transactions

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** The pool's counters (spec 029 FR-034): each event moves its own counter by what happened, and no other. The pool's
  * use of them is pinned in `PendingTransactionsManagerPoolRulesSpec`.
  */
class TxPoolMetricsSpec extends AnyFlatSpec with Matchers:

  private val zero = TxPoolMetrics.Snapshot(Map.empty, 0, 0, 0, 0, 0, 0)

  "A new pool's counters" should "all be nothing" taggedAs UnitTest in {
    new TxPoolMetrics().snapshot shouldBe zero
  }

  "Each event" should "move its own counter, and no other" taggedAs UnitTest in {
    val m = new TxPoolMetrics
    m.rejected(TxAdmission.Rejection.AlreadyKnown)
    m.snapshot shouldBe zero.copy(rejected = Map("AlreadyKnown" -> 1L))
    m.evicted()
    m.snapshot.evicted shouldBe 1L
    m.expired()
    m.snapshot.expired shouldBe 1L
    m.replaced(2)
    m.snapshot.replaced shouldBe 2L
    m.announcementsReceived(5)
    m.snapshot.announced shouldBe 5L
    m.fetchRequested(4)
    m.snapshot.fetchRequested shouldBe 4L
    m.fetchTimedOut(1)
    m.snapshot.fetchTimedOut shouldBe 1L
    m.snapshot shouldBe TxPoolMetrics.Snapshot(Map("AlreadyKnown" -> 1L), 1, 1, 2, 5, 4, 1)
  }

  "A refusal" should "be counted under its rule's name, whatever the rule's detail" taggedAs UnitTest in {
    val m = new TxPoolMetrics
    m.rejected(TxAdmission.Rejection.NonceTooLow(BigInt(5), BigInt(3)))
    m.rejected(TxAdmission.Rejection.NonceTooLow(BigInt(9), BigInt(1)))
    m.rejected(TxAdmission.Rejection.ReplaceUnderpriced)
    m.rejected("NonceTooFarAhead")
    m.snapshot.rejected shouldBe Map("NonceTooLow" -> 2L, "ReplaceUnderpriced" -> 1L, "NonceTooFarAhead" -> 1L)
  }

  "Nothing happening" should "count nothing: a batch of none is not an event" taggedAs UnitTest in {
    val m = new TxPoolMetrics
    m.replaced(0)
    m.announcementsReceived(0)
    m.fetchRequested(0)
    m.fetchTimedOut(0)
    m.snapshot shouldBe zero
  }

  "Two pools" should "count apart: a test's pool never moves the node's" taggedAs UnitTest in {
    val a = new TxPoolMetrics
    val b = new TxPoolMetrics
    a.evicted()
    b.snapshot shouldBe zero
  }
