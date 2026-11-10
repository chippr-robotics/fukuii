package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.AccountTask
import com.chipprbots.ethereum.testing.Tags.*

/** B-044: the account-count estimate that feeds the SNAP progress percentage.
  *
  * The estimate is `accountsDownloaded * 2^256 / covered`, and the accounts percentage is `accountsSynced / estimate`,
  * which at the moment the estimate is computed equals `covered / 2^256`, the share of the keyspace done. That share
  * only goes up, if `covered` counts every range with work left. A range that has been answered and is being inserted
  * chunk by chunk sits in `storingTasks`, in neither `pendingTasks` nor `activeTasks` (#1487), with its `next` already
  * past the response; leaving it out counted the rest of its range as covered, and the percentage jumped ahead by up to
  * a range (6% of the keyspace with 16 ranges) for as long as any range was in that state.
  */
class AccountKeyspaceEstimateSpec extends AnyFlatSpec with Matchers:

  private val Ranges = 16
  private val Keyspace = BigInt(2).pow(256)
  private val RangeSize = Keyspace / Ranges
  private val Root = ByteString(Array.fill[Byte](32)(1))

  private def hash(value: BigInt): ByteString =
    val raw = value.toByteArray.dropWhile(_ == 0)
    ByteString(Array.fill[Byte](32 - raw.length)(0) ++ raw)

  /** Range `index` of 16, with `next` the given share (in sixteenths of the range) of the way in. */
  private def range(index: Int, doneOutOf16: Int = 0): AccountTask =
    val start = RangeSize * index
    val last = if index == Ranges - 1 then Keyspace - 1 else start + RangeSize - 1
    AccountTask(next = hash(start + RangeSize * doneOutOf16 / 16), last = hash(last), rootHash = Root)

  private val Downloaded = 1_000_000L

  "AccountRangeCoordinator.keyspaceEstimate" should
    "give the same estimate whether a range is being stored or queued again" taggedAs UnitTest in {
      // 8 ranges done; 7 untouched and queued; range 7 answered a quarter of the way in (4/16 of it).
      val queued = (8 until 15).map(range(_))
      val answered = range(7, doneOutOf16 = 4)

      val whileStoring = AccountRangeCoordinator.keyspaceEstimate(Downloaded, queued, Nil, Seq(answered))
      val afterRequeue = AccountRangeCoordinator.keyspaceEstimate(Downloaded, queued :+ answered, Nil, Nil)

      withClue("the same ranges have the same work left, whichever set holds the answered one: ") {
        whileStoring shouldBe afterRequeue
      }
    }

  it should "count the rest of a range being stored as keyspace still to do" taggedAs UnitTest in {
    // Remaining work: 7 whole ranges and the 12/16 of range 7 that the answer did not cover: 7.75 of 16, so 8.25 of 16
    // is covered and the estimate is 16 / 8.25 times the accounts so far.
    val queued = (8 until 15).map(range(_))
    val answered = range(7, doneOutOf16 = 4)
    val expected = BigInt(Downloaded) * 16 * 4 / 33 // 16 / 8.25 = 64 / 33

    val estimate = AccountRangeCoordinator.keyspaceEstimate(Downloaded, queued, Nil, Seq(answered))

    estimate.map(BigInt(_)).get should ((be >= expected - 1).and(be <= expected + 1))
  }

  it should "not move when a range goes from queued, to asked of a peer, to being stored" taggedAs UnitTest in {
    val others = (8 until 15).map(range(_))
    val partly = range(15, doneOutOf16 = 8)

    val queued = AccountRangeCoordinator.keyspaceEstimate(Downloaded, others :+ partly, Nil, Nil)
    val asked = AccountRangeCoordinator.keyspaceEstimate(Downloaded, others, Seq(partly), Nil)
    val storing = AccountRangeCoordinator.keyspaceEstimate(Downloaded, others, Nil, Seq(partly))

    queued should not be empty
    asked shouldBe queued
    storing shouldBe queued
  }

  it should "give no estimate before 10,000 accounts, or when no range has work left" taggedAs UnitTest in {
    val queued = (0 until Ranges).map(range(_))

    AccountRangeCoordinator.keyspaceEstimate(9_999L, queued, Nil, Nil) shouldBe None
    AccountRangeCoordinator.keyspaceEstimate(Downloaded, Nil, Nil, Nil) shouldBe None
  }
