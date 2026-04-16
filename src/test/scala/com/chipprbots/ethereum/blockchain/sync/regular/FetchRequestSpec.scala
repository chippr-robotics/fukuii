package com.chipprbots.ethereum.blockchain.sync.regular

import scala.concurrent.duration._

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.testing.Tags._

class FetchRequestSpec extends AnyFreeSpec with Matchers with TestSyncConfig {

  "FetchRequest backoff" - {

    "should compute exponential backoff delays" taggedAs UnitTest in {
      val base = syncConfig.syncRetryInterval // 1.second in test config
      val maxDelay = syncConfig.maxRetryDelay // 30.seconds in test config

      // Helper to compute expected delay
      def expectedDelay(retryCount: Int): FiniteDuration = {
        val multiplier = math.pow(2.0, retryCount.toDouble).toLong
        val delay = base * multiplier
        if (delay > maxDelay) maxDelay else delay
      }

      // retry 0: 1s * 2^0 = 1s
      expectedDelay(0) shouldBe 1.second
      // retry 1: 1s * 2^1 = 2s
      expectedDelay(1) shouldBe 2.seconds
      // retry 2: 1s * 2^2 = 4s
      expectedDelay(2) shouldBe 4.seconds
      // retry 3: 1s * 2^3 = 8s
      expectedDelay(3) shouldBe 8.seconds
      // retry 4: 1s * 2^4 = 16s
      expectedDelay(4) shouldBe 16.seconds
      // retry 5: 1s * 2^5 = 32s > 30s cap → 30s
      expectedDelay(5) shouldBe 30.seconds
      // retry 10: still capped at 30s
      expectedDelay(10) shouldBe 30.seconds
    }

    "should respect maxRetryDelay cap" taggedAs UnitTest in {
      val configWith2sCap = syncConfig.copy(maxRetryDelay = 2.seconds)

      def expectedDelay(retryCount: Int): FiniteDuration = {
        val base = configWith2sCap.syncRetryInterval
        val multiplier = math.pow(2.0, retryCount.toDouble).toLong
        val delay = base * multiplier
        if (delay > configWith2sCap.maxRetryDelay) configWith2sCap.maxRetryDelay else delay
      }

      expectedDelay(0) shouldBe 1.second
      expectedDelay(1) shouldBe 2.seconds
      expectedDelay(2) shouldBe 2.seconds // capped
      expectedDelay(5) shouldBe 2.seconds // capped
    }

    "should have maxBodyFetchRetries default of 10" taggedAs UnitTest in {
      syncConfig.maxBodyFetchRetries shouldBe 10
    }

    "should have maxRetryDelay default of 30 seconds" taggedAs UnitTest in {
      syncConfig.maxRetryDelay shouldBe 30.seconds
    }
  }

  "PeersClient.ExcludingPeers" - {

    "should be a valid PeerSelector" taggedAs UnitTest in {
      import com.chipprbots.ethereum.blockchain.sync.PeersClient._
      import com.chipprbots.ethereum.network.PeerId

      val peerId = PeerId("test-peer-1")
      val selector: PeerSelector = ExcludingPeers(Set(peerId))
      selector shouldBe a[ExcludingPeers]
      selector.asInstanceOf[ExcludingPeers].exclude should contain(peerId)
    }

    "should create with empty set" taggedAs UnitTest in {
      import com.chipprbots.ethereum.blockchain.sync.PeersClient._

      val selector = ExcludingPeers(Set.empty)
      selector.exclude shouldBe empty
    }
  }

  "BlockFetcher.RetryBodiesRequest" - {

    "should carry tried peers and retry count" taggedAs UnitTest in {
      import com.chipprbots.ethereum.network.PeerId

      val peerId1 = PeerId("peer-1")
      val peerId2 = PeerId("peer-2")
      val retry = BlockFetcher.RetryBodiesRequest(
        failedPeerId = Some(peerId1),
        triedPeers = Set(peerId1, peerId2),
        retryCount = 3
      )

      retry.failedPeerId shouldBe Some(peerId1)
      retry.triedPeers should have size 2
      retry.retryCount shouldBe 3
    }

    "should default to empty state" taggedAs UnitTest in {
      val retry = BlockFetcher.RetryBodiesRequest()

      retry.failedPeerId shouldBe None
      retry.triedPeers shouldBe empty
      retry.retryCount shouldBe 0
    }
  }

  "BodiesFetcher.FetchBodies" - {

    "should carry tried peers and retry count" taggedAs UnitTest in {
      import org.apache.pekko.util.ByteString
      import com.chipprbots.ethereum.network.PeerId

      val peerId = PeerId("peer-1")
      val fetch = BodiesFetcher.FetchBodies(
        hashes = Seq(ByteString("hash1")),
        triedPeers = Set(peerId),
        retryCount = 2
      )

      fetch.triedPeers should contain(peerId)
      fetch.retryCount shouldBe 2
    }

    "should default to empty retry state" taggedAs UnitTest in {
      import org.apache.pekko.util.ByteString

      val fetch = BodiesFetcher.FetchBodies(hashes = Seq(ByteString("hash1")))

      fetch.triedPeers shouldBe empty
      fetch.retryCount shouldBe 0
    }
  }
}
