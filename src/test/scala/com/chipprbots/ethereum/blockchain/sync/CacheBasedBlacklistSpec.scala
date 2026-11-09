package com.chipprbots.ethereum.blockchain.sync

import java.util.concurrent.TimeUnit

import scala.concurrent.duration.*

import com.github.blemale.scaffeine.Scaffeine
import com.google.common.testing.FakeTicker
import org.scalatest.ParallelTestExecution
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.PeerManagerActor.PeerAddress
import com.chipprbots.ethereum.network.PeerScoringManager
import com.chipprbots.ethereum.testing.Tags.*

class CacheBasedBlacklistSpec extends AnyWordSpecLike with Matchers with ParallelTestExecution:
  import Blacklist.*

  private val peer1 = PeerId("1")
  private val peer2 = PeerId("2")
  private val peer3 = PeerId("3")
  private val peer4 = PeerId("4")
  private val peer5 = PeerId("5")

  private val reason = BlacklistReason.ErrorInBlockHeaders
  private val anotherReason = BlacklistReason.BlockBodiesNotMatchingHeaders

  private def withBlacklist(maxElements: Int)(test: CacheBasedBlacklist => Unit): Unit =
    val blacklist = CacheBasedBlacklist.empty(maxElements)
    test(blacklist)

  "CacheBasedBlacklist" should {
    "add elements and respect max number of elements" taggedAs (UnitTest) in withBlacklist(3) { blacklist =>
      blacklist.add(peer1, 1.minute, reason)
      blacklist.add(peer2, 1.minute, reason)
      blacklist.add(peer3, 1.minute, anotherReason)
      blacklist.add(peer4, 1.minute, anotherReason)
      blacklist.add(peer5, 1.minute, reason)
      blacklist.cache.cleanUp()
      val size = blacklist.keys.size
      assert(size <= 3 && size > 0)
    }
    "should expire elements" taggedAs (UnitTest) in {
      val maxSize = 10
      val ticker = new FakeTicker()
      val cache = Scaffeine()
        .expireAfter[BlacklistId, BlacklistReason.BlacklistReasonType](
          create = (_, _) => 60.minutes,
          update = (_, _, _) => 60.minutes,
          read = (_, _, duration) => duration
        )
        .maximumSize(
          maxSize
        )
        .ticker(() => ticker.read())
        .build[BlacklistId, BlacklistReason.BlacklistReasonType]()
      val blacklist = CacheBasedBlacklist(cache)
      blacklist.add(peer1, 1.minute, reason)
      blacklist.add(peer2, 10.minutes, reason)
      blacklist.add(peer3, 3.minutes, anotherReason)
      blacklist.add(peer4, 2.minutes, reason)
      blacklist.add(peer5, 7.minutes, reason)
      blacklist.isBlacklisted(peer2) // just to simulate a read
      blacklist.keys // just to simulate a read
      ticker.advance(5, TimeUnit.MINUTES)
      val expected = Set(peer2, peer5)
      blacklist.cache.cleanUp()
      blacklist.keys must contain theSameElementsAs expected
    }
    "check if given key is part of the list" taggedAs (UnitTest) in withBlacklist(3) { blacklist =>
      blacklist.add(peer1, 1.minute, reason)
      blacklist.add(peer2, 1.minute, anotherReason)
      blacklist.add(peer3, 1.minute, reason)
      assert(blacklist.isBlacklisted(peer2) === true)
      assert(blacklist.isBlacklisted(PeerId("7")) === false)
    }
    "remove id from blacklist" taggedAs (UnitTest) in withBlacklist(3) { blacklist =>
      blacklist.add(peer1, 1.minute, reason)
      blacklist.add(peer2, 1.minute, anotherReason)
      blacklist.add(peer3, 1.minute, reason)
      assert(blacklist.isBlacklisted(peer2) === true)
      blacklist.remove(peer2)
      assert(blacklist.isBlacklisted(peer2) === false)
    }
    "automatically clean up expired entries when calling keys" taggedAs (UnitTest) in {
      val maxSize = 10
      val ticker = new FakeTicker()
      val cache = Scaffeine()
        .expireAfter[BlacklistId, BlacklistReason.BlacklistReasonType](
          create = (_, _) => 60.minutes,
          update = (_, _, _) => 60.minutes,
          read = (_, _, duration) => duration
        )
        .maximumSize(maxSize)
        .ticker(() => ticker.read())
        .build[BlacklistId, BlacklistReason.BlacklistReasonType]()
      val blacklist = CacheBasedBlacklist(cache)

      // Add peers with different expiration times
      blacklist.add(peer1, 1.minute, reason)
      blacklist.add(peer2, 10.minutes, reason)
      blacklist.add(peer3, 3.minutes, anotherReason)

      // Advance time to expire peer1 and peer3
      ticker.advance(5, TimeUnit.MINUTES)

      // keys should automatically clean up and only return non-expired entries
      // Without explicit cleanUp() call, keys should still return only peer2
      val activeKeys = blacklist.keys
      activeKeys must contain theSameElementsAs Set(peer2)
    }
    "return correct count immediately after adding peers" taggedAs (UnitTest) in withBlacklist(10) { blacklist =>
      // Add first peer
      blacklist.add(peer1, 5.minutes, reason)
      assert(blacklist.keys.size === 1)

      // Add second peer
      blacklist.add(peer2, 10.minutes, anotherReason)
      assert(blacklist.keys.size === 2)

      // Add third peer
      blacklist.add(peer3, 3.minutes, reason)
      assert(blacklist.keys.size === 3)

      // Verify all are still blacklisted
      assert(blacklist.isBlacklisted(peer1) === true)
      assert(blacklist.isBlacklisted(peer2) === true)
      assert(blacklist.isBlacklisted(peer3) === true)
    }
  }

  // ---- spec 027 slice 4a: the decorator that feeds the peer scorer ------------------------------------------------

  private class ScoringFixture:
    val scorer = new PeerScoringManager(java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC))
    val underlying: CacheBasedBlacklist = CacheBasedBlacklist.empty(10)
    val blacklist = new ScoringBlacklist(underlying, scorer)

  "ScoringBlacklist" should {
    "record a blacklist for a PeerId and still blacklist it" taggedAs (UnitTest) in {
      val f = new ScoringFixture
      f.blacklist.add(peer1, 1.minute, reason)
      f.scorer.getAllScores.keySet mustBe Set(peer1)
      f.scorer.getScore(peer1).blacklistCount mustBe 1
      f.underlying.isBlacklisted(peer1) mustBe true
      f.blacklist.isBlacklisted(peer1) mustBe true
    }
    "count every blacklisting of the same PeerId" taggedAs (UnitTest) in {
      val f = new ScoringFixture
      f.blacklist.add(peer1, 1.minute, reason)
      f.blacklist.add(peer1, 1.minute, anotherReason)
      f.scorer.getScore(peer1).blacklistCount mustBe 2
    }
    "record nothing for a PeerAddress" taggedAs (UnitTest) in {
      val f = new ScoringFixture
      val address = PeerAddress("10.0.0.1")
      f.blacklist.add(address, 1.minute, reason)
      f.scorer.getAllScores mustBe empty
      f.underlying.isBlacklisted(address) mustBe true
      f.blacklist.isBlacklisted(address) mustBe true
    }
    "delegate isBlacklisted, remove, keys and reasonFor unchanged" taggedAs (UnitTest) in {
      val f = new ScoringFixture
      val address = PeerAddress("10.0.0.2")
      f.blacklist.add(peer1, 1.minute, reason)
      f.blacklist.add(address, 1.minute, anotherReason)

      f.blacklist.keys mustBe f.underlying.keys
      f.blacklist.keys mustBe Set(peer1, address)
      f.blacklist.reasonFor(peer1) mustBe Some(reason.reasonType)
      f.blacklist.reasonFor(address) mustBe Some(anotherReason.reasonType)
      f.blacklist.reasonFor(peer2) mustBe None
      f.blacklist.isBlacklisted(peer2) mustBe false

      f.blacklist.remove(peer1)
      f.underlying.isBlacklisted(peer1) mustBe false
      f.blacklist.keys mustBe Set(address)
      // removal is not an event the scorer hears of: the history stays
      f.scorer.getScore(peer1).blacklistCount mustBe 1
    }
  }
