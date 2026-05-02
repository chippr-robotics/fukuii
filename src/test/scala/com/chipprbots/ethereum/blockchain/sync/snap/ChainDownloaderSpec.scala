package com.chipprbots.ethereum.blockchain.sync.snap

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._

class ChainDownloaderSpec extends AnyFlatSpec with Matchers {

  // Regression for #1162: chain backfill keeps running in the background after SNAPSyncController
  // emits SnapSyncFinalized, but at lower priority. ChainDownloader needs a `YieldToRegularSync(n)`
  // message that mirrors `BoostConcurrency(n)` but downward — the SNAP controller sends it in
  // finalizeSnapSync() so backfill yields peer slots to forward sync.
  "ChainDownloader.YieldToRegularSync" should "carry the new concurrency budget" taggedAs UnitTest in {
    ChainDownloader.YieldToRegularSync(7).maxConcurrent shouldBe 7
    ChainDownloader.YieldToRegularSync(0).maxConcurrent shouldBe 0
  }

  it should "be a distinct message type from BoostConcurrency" taggedAs UnitTest in {
    val yielded: Any = ChainDownloader.YieldToRegularSync(7)
    val boosted: Any = ChainDownloader.BoostConcurrency(7)
    (yielded should not).equal(boosted)
  }
}
