package com.chipprbots.ethereum.transactions

import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** The bounded trackers behind the pool's known-tx and announcement state (#1517). Time is a fake clock. */
class TxGossipTrackingSpec extends AnyFlatSpec with Matchers:

  private def h(i: Int): ByteString = ByteString(BigInt(i).toByteArray)
  private val peerA = PeerId("a")
  private val peerB = PeerId("b")

  private class Clock:
    var now: Long = 0L
    val fn: () => Long = () => now

  "KnownTransactions" should "track which peers know a hash" taggedAs UnitTest in {
    val known = new KnownTransactions(10, 1.minute, new Clock().fn)
    known.markKnown(h(1), peerA)
    known.isKnown(h(1), peerA) shouldBe true
    known.isKnown(h(1), peerB) shouldBe false
    known.markKnown(h(1), peerB)
    known.isKnown(h(1), peerB) shouldBe true
    known.remove(h(1))
    known.isKnown(h(1), peerA) shouldBe false
  }

  it should "never hold more than its cap, evicting the least recently touched first" taggedAs UnitTest in {
    val known = new KnownTransactions(100, 1.hour, new Clock().fn)
    (1 to 1000).foreach { i =>
      known.markKnown(h(i), peerA)
      known.size should be <= 100
    }
    known.size shouldBe 100
    known.isKnown(h(1000), peerA) shouldBe true
    known.isKnown(h(901), peerA) shouldBe true
    known.isKnown(h(900), peerA) shouldBe false
  }

  it should "keep a recently touched hash over an older one" taggedAs UnitTest in {
    val known = new KnownTransactions(2, 1.hour, new Clock().fn)
    known.markKnown(h(1), peerA)
    known.markKnown(h(2), peerA)
    known.markKnown(h(1), peerB) // touch 1: 2 is now the eldest
    known.markKnown(h(3), peerA)
    known.isKnown(h(1), peerB) shouldBe true
    known.isKnown(h(2), peerA) shouldBe false
  }

  it should "expire entries that stay untouched, even for a peer that stays connected" taggedAs UnitTest in {
    val clock = new Clock
    val known = new KnownTransactions(1000, 1.minute, clock.fn)
    known.markKnown(h(1), peerA)
    clock.now = 59.seconds.toMillis
    known.isKnown(h(1), peerA) shouldBe true
    clock.now = 61.seconds.toMillis
    known.isKnown(h(1), peerA) shouldBe false
    known.markKnown(h(2), peerA) // a later write sweeps the expired head
    known.size shouldBe 1
  }

  it should "remove one entry without touching the rest, at any scale (no whole-map rebuild)" taggedAs UnitTest in {
    val known = new KnownTransactions(300000, 1.hour, new Clock().fn)
    (1 to 200000).foreach(i => known.markKnown(h(i), peerA))
    (1 to 200000 by 2).foreach(i => known.remove(h(i)))
    known.size shouldBe 100000
    known.isKnown(h(2), peerA) shouldBe true
    known.isKnown(h(3), peerA) shouldBe false
  }

  private val peerC = PeerId("c")

  "PendingAnnouncements" should "record, return and remove an announcement" taggedAs UnitTest in {
    val ann = new PendingAnnouncements(10, 1.minute, 5.seconds, new Clock().fn)
    ann.announce(h(1), 2.toByte, BigInt(100), peerA)
    ann.get(h(1), peerA) shouldBe Some(PendingAnnouncements.Announcement(2.toByte, BigInt(100), peerA))
    ann.get(h(1), peerB) shouldBe None
    ann.remove(h(1))
    ann.get(h(1), peerA) shouldBe None
    ann.size shouldBe 0
  }

  it should "never hold more than its cap" taggedAs UnitTest in {
    val ann = new PendingAnnouncements(50, 1.hour, 5.seconds, new Clock().fn)
    (1 to 500).foreach { i =>
      ann.announce(h(i), 0.toByte, BigInt(1), peerA)
      ann.size should be <= 50
    }
    ann.get(h(500), peerA) should not be empty
    ann.get(h(450), peerA) shouldBe None
  }

  it should "expire announcements a connected peer never answered" taggedAs UnitTest in {
    val clock = new Clock
    val ann = new PendingAnnouncements(1000, 2.minutes, 2.hours, clock.fn)
    ann.announce(h(1), 0.toByte, BigInt(1), peerA)
    clock.now = 119.seconds.toMillis
    ann.get(h(1), peerA) should not be empty
    clock.now = 121.seconds.toMillis
    ann.get(h(1), peerA) shouldBe None // an expired announcement no longer validates a reply
    ann.announce(h(2), 0.toByte, BigInt(1), peerA) // and the next write sweeps it out
    ann.size shouldBe 1
  }

  it should "drop only a disconnected peer's announcements" taggedAs UnitTest in {
    val ann = new PendingAnnouncements(1000, 1.hour, 5.seconds, new Clock().fn)
    (1 to 100).foreach(i => ann.announce(h(i), 0.toByte, BigInt(1), if i % 2 == 0 then peerA else peerB))
    ann.removePeer(peerA) shouldBe empty // nobody else announced its hashes: nobody to ask
    ann.size shouldBe 50
    ann.get(h(2), peerA) shouldBe None
    ann.get(h(1), peerB) should not be empty
  }

  // hive devp2p TestBlobTxWithoutSidecar / TestBlobTxWithMismatchedSidecar (go-ethereum #35869): three peers announce
  // one blob tx; the node must ask exactly one of them, and after that one fails, exactly one other.
  it should "request a hash from one announcer at a time, keeping the others as alternates" taggedAs UnitTest in {
    val ann = new PendingAnnouncements(1000, 1.hour, 5.seconds, new Clock().fn)
    ann.announce(h(1), 3.toByte, BigInt(100), peerA)
    ann.schedule(peerA) shouldBe Seq(h(1))
    ann.announce(h(1), 3.toByte, BigInt(100), peerB)
    ann.schedule(peerB) shouldBe empty
    ann.announce(h(1), 3.toByte, BigInt(101), peerC)
    ann.schedule(peerC) shouldBe empty
    ann.announce(h(1), 3.toByte, BigInt(100), peerA) // a re-announcement is not a second request
    ann.schedule(peerA) shouldBe empty
    ann.requestedFrom(h(1)) shouldBe Some(peerA)
    ann.get(h(1), peerC).map(_.size) shouldBe Some(BigInt(101)) // each reply is checked against its sender's own
  }

  it should "move a dropped requester's hashes to the next announcer, and only those" taggedAs UnitTest in {
    val ann = new PendingAnnouncements(1000, 1.hour, 5.seconds, new Clock().fn)
    ann.announce(h(1), 3.toByte, BigInt(100), peerA)
    ann.schedule(peerA) shouldBe Seq(h(1))
    ann.announce(h(2), 3.toByte, BigInt(100), peerB)
    ann.schedule(peerB) shouldBe Seq(h(2)) // B is asked for 2 ...
    ann.announce(h(1), 3.toByte, BigInt(100), peerB) // ... and is A's alternate for 1
    ann.announce(h(1), 3.toByte, BigInt(100), peerC)
    ann.announce(h(2), 3.toByte, BigInt(100), peerA)
    ann.removePeer(peerA) shouldBe Seq(peerB, peerC)
    ann.schedule(peerB) shouldBe empty // B has a request in flight
    ann.schedule(peerC) shouldBe Seq(h(1))
    ann.removePeer(peerB) shouldBe empty // 2 has no announcer left: forgotten
    ann.size shouldBe 1
    ann.removePeer(peerC) shouldBe empty
    ann.size shouldBe 0
  }

  it should "give a timed-out request's hashes to the next announcer, and keep the requester busy" taggedAs UnitTest in {
    val clock = new Clock
    val ann = new PendingAnnouncements(1000, 1.hour, 5.seconds, clock.fn)
    ann.announce(h(1), 3.toByte, BigInt(100), peerA)
    ann.announce(h(1), 3.toByte, BigInt(100), peerB)
    ann.announce(h(1), 3.toByte, BigInt(100), peerC)
    ann.schedule(peerA) shouldBe Seq(h(1))
    clock.now = 5.seconds.toMillis
    ann.timedOut() shouldBe empty
    clock.now = 5.seconds.toMillis + 1
    ann.timedOut() shouldBe Seq(peerB, peerC)
    ann.get(h(1), peerA) shouldBe None // the peer that timed out is not asked again
    ann.busy(peerA) shouldBe true // its request stays in flight until it answers (go-ethereum: "dangling")
    ann.schedule(peerB) shouldBe Seq(h(1))
    ann.schedule(peerC) shouldBe empty
    ann.timedOut() shouldBe empty // the new request has its own full timeout
    clock.now = 10.seconds.toMillis + 2
    ann.timedOut() shouldBe Seq(peerC)
    ann.schedule(peerC) shouldBe Seq(h(1))
    ann.unansweredRequests shouldBe 2
    clock.now = 15.seconds.toMillis + 3
    ann.timedOut() shouldBe empty // nobody left to ask
    ann.size shouldBe 0
    // The last request timed out too, though it moved nowhere: three peers were asked and none answered.
    ann.unansweredRequests shouldBe 3
    ann.delivered(peerA, Nil) shouldBe Seq(peerA) // a late answer frees the peer
    ann.busy(peerA) shouldBe false
  }

  it should "stop timing out a request once its hash is delivered" taggedAs UnitTest in {
    val clock = new Clock
    val ann = new PendingAnnouncements(1000, 1.hour, 5.seconds, clock.fn)
    ann.announce(h(1), 3.toByte, BigInt(100), peerA)
    ann.announce(h(1), 3.toByte, BigInt(100), peerB)
    ann.schedule(peerA) shouldBe Seq(h(1))
    ann.remove(h(1))
    clock.now = 1.minute.toMillis
    ann.timedOut() shouldBe empty
    ann.removePeer(peerB) shouldBe empty
    ann.unansweredRequests shouldBe 0 // a delivered hash's request did not go unanswered
  }

  // B-028: go-ethereum's tx fetcher keeps one GetPooledTransactions in flight per peer (`requests[peer]`), of at most
  // maxTxRetrievals (256) hashes and maxTxRetrievalSize (128 KiB) of announced bytes.
  it should "keep one request in flight per peer until it answers" taggedAs UnitTest in {
    val ann = new PendingAnnouncements(10000, 1.hour, 5.seconds, new Clock().fn)
    (1 to 600).foreach(i => ann.announce(h(i), 2.toByte, BigInt(100), peerA))
    ann.schedule(peerA) shouldBe (1 to 256).map(h)
    ann.busy(peerA) shouldBe true
    ann.schedule(peerA) shouldBe empty
    ann.delivered(peerA, (1 to 256).map(h)) shouldBe Seq(peerA)
    ann.schedule(peerA) shouldBe (257 to 512).map(h)
    ann.delivered(peerA, (257 to 512).map(h))
    ann.schedule(peerA) shouldBe (513 to 600).map(h)
    ann.delivered(peerA, (513 to 600).map(h))
    ann.schedule(peerA) shouldBe empty
    ann.busy(peerA) shouldBe false
    ann.size shouldBe 0
  }

  it should "cap a request at 128 KiB of announced bytes, always asking for at least one hash" taggedAs UnitTest in {
    val ann = new PendingAnnouncements(10000, 1.hour, 5.seconds, new Clock().fn)
    // 40,000 bytes each: the fourth reaches 160,000 >= 131,072, so it is the last one asked for.
    (1 to 6).foreach(i => ann.announce(h(i), 2.toByte, BigInt(40000), peerA))
    ann.schedule(peerA) shouldBe (1 to 4).map(h)
    ann.delivered(peerA, (1 to 4).map(h))
    ann.schedule(peerA) shouldBe (5 to 6).map(h)
    // One tx larger than the cap is still asked for, on its own.
    ann.announce(h(7), 3.toByte, BigInt(200000), peerB)
    ann.announce(h(8), 3.toByte, BigInt(10), peerB)
    ann.schedule(peerB) shouldBe Seq(h(7))
  }

  it should "give the hashes a peer left out of its answer to their other announcers" taggedAs UnitTest in {
    val ann = new PendingAnnouncements(1000, 1.hour, 5.seconds, new Clock().fn)
    Seq(1, 2, 3).foreach(i => ann.announce(h(i), 2.toByte, BigInt(100), peerA))
    ann.announce(h(2), 2.toByte, BigInt(100), peerB)
    ann.schedule(peerA) shouldBe Seq(h(1), h(2), h(3))
    ann.schedule(peerB) shouldBe empty // 2 is being fetched from A
    // A delivers 1 only: it does not have 2 or 3. 2 goes to B; 3 has nobody else and is forgotten.
    ann.delivered(peerA, Seq(h(1))) shouldBe Seq(peerA, peerB)
    ann.get(h(2), peerA) shouldBe None
    ann.schedule(peerA) shouldBe empty
    ann.schedule(peerB) shouldBe Seq(h(2))
    ann.size shouldBe 1
  }

  it should "keep a peer busy, with nothing left in its request, when its hashes are forgotten before it answers" taggedAs UnitTest in {
    val clock = new Clock
    val ann = new PendingAnnouncements(1000, 1.minute, 5.seconds, clock.fn)
    ann.announce(h(1), 2.toByte, BigInt(100), peerA)
    ann.schedule(peerA) shouldBe Seq(h(1))
    clock.now = 61.seconds.toMillis
    ann.announce(h(2), 2.toByte, BigInt(100), peerA) // sweeps 1 out by age
    ann.size shouldBe 1
    ann.schedule(peerA) shouldBe empty // still one request in flight
    ann.delivered(peerA, Nil) shouldBe Seq(peerA) // its answer frees it, and gives up nothing
    ann.get(h(2), peerA) should not be empty
    ann.schedule(peerA) shouldBe Seq(h(2))
  }
