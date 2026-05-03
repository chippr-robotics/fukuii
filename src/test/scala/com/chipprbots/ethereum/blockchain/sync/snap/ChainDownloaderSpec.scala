package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestActorRef
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe

import scala.concurrent.ExecutionContext

import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.testing.Tags._

class ChainDownloaderSpec
    extends TestKit(ActorSystem("ChainDownloaderSpec"))
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll
    with MockFactory
    with TestSyncConfig {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  // Regression for #1162: chain backfill keeps running in the background after SNAPSyncController
  // emits SnapSyncFinalized, but at lower priority. ChainDownloader needs a `YieldToRegularSync(n)`
  // message that mirrors `BoostConcurrency(n)` but downward — the SNAP controller sends it in
  // finalizeSnapSync() so backfill yields peer slots to forward sync.
  "ChainDownloader.YieldToRegularSync" should "carry the new concurrency budget" taggedAs UnitTest in {
    ChainDownloader.YieldToRegularSync(7).maxConcurrent shouldBe 7
  }

  it should "be a distinct message type from BoostConcurrency" taggedAs UnitTest in {
    val yielded: Any = ChainDownloader.YieldToRegularSync(7)
    val boosted: Any = ChainDownloader.BoostConcurrency(7)
    (yielded should not).equal(boosted)
  }

  // Behavioural test: the downloader's dispatch loop wedges if `maxConcurrentRequests` ever drops to
  // zero (the slot check `inFlightCount >= maxConcurrentRequests` would be true forever), stranding
  // the parent `SNAPSyncController` waiting for `ChainDownloader.Done` indefinitely. Clamp to >=1.
  "ChainDownloader" should "clamp YieldToRegularSync(0) to 1 to prevent dispatch wedge" taggedAs UnitTest in {
    val downloader = newDownloader(initialMaxConcurrentRequests = 16)

    downloader ! ChainDownloader.YieldToRegularSync(0)
    downloader.underlyingActor.currentMaxConcurrentRequests shouldBe 1

    downloader ! ChainDownloader.YieldToRegularSync(5)
    downloader.underlyingActor.currentMaxConcurrentRequests shouldBe 5

    system.stop(downloader)
  }

  it should "honour YieldToRegularSync(n) for any n >= 1" taggedAs UnitTest in {
    val downloader = newDownloader(initialMaxConcurrentRequests = 16)

    downloader ! ChainDownloader.YieldToRegularSync(2)
    downloader.underlyingActor.currentMaxConcurrentRequests shouldBe 2

    downloader ! ChainDownloader.YieldToRegularSync(1)
    downloader.underlyingActor.currentMaxConcurrentRequests shouldBe 1

    system.stop(downloader)
  }

  it should "still apply YieldToRegularSync after BoostConcurrency (downward)" taggedAs UnitTest in {
    val downloader = newDownloader(initialMaxConcurrentRequests = 2)

    downloader ! ChainDownloader.BoostConcurrency(16)
    downloader.underlyingActor.currentMaxConcurrentRequests shouldBe 16

    downloader ! ChainDownloader.YieldToRegularSync(2)
    downloader.underlyingActor.currentMaxConcurrentRequests shouldBe 2

    system.stop(downloader)
  }

  // Issue #1169: persist a BackfillTarget at Start so that a node killed mid-backfill can
  // resume standalone after restart.
  it should "persist BackfillTarget on Start so a restart can resume backfill" taggedAs UnitTest in {
    implicit val ec: ExecutionContext = system.dispatcher
    val blockchainReader = mock[BlockchainReader]
    val blockchainWriter = mock[BlockchainWriter]
    val appStateStorage = new AppStateStorage(EphemDataSource())
    val networkPeerManager = TestProbe()
    val peerEventBus = TestProbe()

    // findBestStoredHeader probes block 1; mock it as missing so the binary search falls
    // through and returns 0 immediately, leaving the actor in `downloading` state.
    (blockchainReader.getBlockHeaderByNumber _)
      .expects(BigInt(1))
      .returning(None)
      .anyNumberOfTimes()

    appStateStorage.getBackfillTarget() shouldBe BigInt(0)

    val downloader = TestActorRef[ChainDownloader](
      ChainDownloader.props(
        blockchainReader = blockchainReader,
        blockchainWriter = blockchainWriter,
        appStateStorage = appStateStorage,
        networkPeerManager = networkPeerManager.ref,
        peerEventBus = peerEventBus.ref,
        syncConfig = defaultSyncConfig,
        scheduler = system.scheduler,
        maxConcurrentRequests = 4
      )
    )

    val target = BigInt(19_250_000)
    downloader ! ChainDownloader.Start(target)

    appStateStorage.getBackfillTarget() shouldBe target

    system.stop(downloader)
  }

  // Issue #1169: an UpdateTarget message during the downloading phase persists the bumped
  // target so a restart resumes against the latest target rather than a stale one.
  it should "persist a bumped BackfillTarget on UpdateTarget" taggedAs UnitTest in {
    implicit val ec: ExecutionContext = system.dispatcher
    val blockchainReader = mock[BlockchainReader]
    val blockchainWriter = mock[BlockchainWriter]
    val appStateStorage = new AppStateStorage(EphemDataSource())
    val networkPeerManager = TestProbe()
    val peerEventBus = TestProbe()

    (blockchainReader.getBlockHeaderByNumber _)
      .expects(BigInt(1))
      .returning(None)
      .anyNumberOfTimes()

    val downloader = TestActorRef[ChainDownloader](
      ChainDownloader.props(
        blockchainReader = blockchainReader,
        blockchainWriter = blockchainWriter,
        appStateStorage = appStateStorage,
        networkPeerManager = networkPeerManager.ref,
        peerEventBus = peerEventBus.ref,
        syncConfig = defaultSyncConfig,
        scheduler = system.scheduler,
        maxConcurrentRequests = 4
      )
    )

    downloader ! ChainDownloader.Start(BigInt(1000))
    appStateStorage.getBackfillTarget() shouldBe BigInt(1000)

    downloader ! ChainDownloader.UpdateTarget(BigInt(1500))
    appStateStorage.getBackfillTarget() shouldBe BigInt(1500)

    // A target lower than the current one is ignored.
    downloader ! ChainDownloader.UpdateTarget(BigInt(1200))
    appStateStorage.getBackfillTarget() shouldBe BigInt(1500)

    system.stop(downloader)
  }

  /** Spawn a ChainDownloader for unit-level message-handling tests. The downloader stays in `idle` (no `Start` sent),
    * so the in-flight queues remain empty and we don't need real blockchain or peer infrastructure.
    */
  private def newDownloader(initialMaxConcurrentRequests: Int): TestActorRef[ChainDownloader] = {
    implicit val ec: ExecutionContext = system.dispatcher
    val blockchainReader = mock[BlockchainReader]
    val blockchainWriter = mock[BlockchainWriter]
    val appStateStorage = new AppStateStorage(EphemDataSource())
    val networkPeerManager = TestProbe()
    val peerEventBus = TestProbe()
    TestActorRef[ChainDownloader](
      ChainDownloader.props(
        blockchainReader = blockchainReader,
        blockchainWriter = blockchainWriter,
        appStateStorage = appStateStorage,
        networkPeerManager = networkPeerManager.ref,
        peerEventBus = peerEventBus.ref,
        syncConfig = defaultSyncConfig,
        scheduler = system.scheduler,
        maxConcurrentRequests = initialMaxConcurrentRequests
      )
    )
  }
}
