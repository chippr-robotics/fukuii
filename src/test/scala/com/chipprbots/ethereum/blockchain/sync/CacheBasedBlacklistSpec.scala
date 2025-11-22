package com.chipprbots.ethereum.blockchain.sync

import java.util.concurrent.TimeUnit

import scala.concurrent.duration._

import com.github.blemale.scaffeine.Scaffeine
import com.google.common.testing.FakeTicker
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.network.PeerId

class CacheBasedBlacklistSpec extends AnyWordSpecLike with Matchers {
  import Blacklist._

  private val peer1 = PeerId("1")
  private val peer2 = PeerId("2")
  private val peer3 = PeerId("3")
  private val peer4 = PeerId("4")
  private val peer5 = PeerId("5")

  private val reason = BlacklistReason.ErrorInBlockHeaders
  private val anotherReason = BlacklistReason.BlockBodiesNotMatchingHeaders

  private def withBlacklist(maxElements: Int)(test: CacheBasedBlacklist => Unit): Unit = {
    val blacklist = CacheBasedBlacklist.empty(maxElements)
    test(blacklist)
  }

  "CacheBasedBlacklist" should {
    "add elements and respect max number of elements" taggedAs (UnitTest, SyncTest) in withBlacklist(3) { blacklist =>
      blacklist.add(peer1, 1.minute, reason)
      blacklist.add(peer2, 1.minute, reason)
      blacklist.add(peer3, 1.minute, anotherReason)
      blacklist.add(peer4, 1.minute, anotherReason)
      blacklist.add(peer5, 1.minute, reason)
      blacklist.cache.cleanUp()
      val size = blacklist.keys.size
      assert(size <= 3 && size > 0)
    }
    "should expire elements" taggedAs (UnitTest, SyncTest) in {
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
        .ticker(ticker.read _)
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
    "check if given key is part of the list" taggedAs (UnitTest, SyncTest) in withBlacklist(3) { blacklist =>
      blacklist.add(peer1, 1.minute, reason)
      blacklist.add(peer2, 1.minute, anotherReason)
      blacklist.add(peer3, 1.minute, reason)
      assert(blacklist.isBlacklisted(peer2) === true)
      assert(blacklist.isBlacklisted(PeerId("7")) === false)
    }
    "remove id from blacklist" taggedAs (UnitTest, SyncTest) in withBlacklist(3) { blacklist =>
      blacklist.add(peer1, 1.minute, reason)
      blacklist.add(peer2, 1.minute, anotherReason)
      blacklist.add(peer3, 1.minute, reason)
      assert(blacklist.isBlacklisted(peer2) === true)
      blacklist.remove(peer2)
      assert(blacklist.isBlacklisted(peer2) === false)
    }
    "automatically clean up expired entries when calling keys" taggedAs (UnitTest, SyncTest) in {
      val maxSize = 10
      val ticker = new FakeTicker()
      val cache = Scaffeine()
        .expireAfter[BlacklistId, BlacklistReason.BlacklistReasonType](
          create = (_, _) => 60.minutes,
          update = (_, _, _) => 60.minutes,
          read = (_, _, duration) => duration
        )
        .maximumSize(maxSize)
        .ticker(ticker.read _)
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
    "return correct count immediately after adding peers" taggedAs (UnitTest, SyncTest) in withBlacklist(10) {
      blacklist =>
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

}
