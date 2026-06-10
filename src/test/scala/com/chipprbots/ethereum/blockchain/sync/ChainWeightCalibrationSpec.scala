package com.chipprbots.ethereum.blockchain.sync

import scala.concurrent.Await
import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.Props
import org.apache.pekko.testkit.ExplicitlyTriggeredScheduler
import org.apache.pekko.testkit.TestActorRef
import org.apache.pekko.testkit.TestProbe

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.Mocks
import com.chipprbots.ethereum.Mocks.MockValidatorsAlwaysSucceed
import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.CalibrateChainWeightNow
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.GetHandshakedPeers
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RegisterChainWeightCalibrationTarget
import com.chipprbots.ethereum.testing.Tags._

// scalastyle:off magic.number
class ChainWeightCalibrationSpec extends AnyFlatSpec with Matchers {

  // ─── T2.1 Tier 1: exact interpolation from NewBlock ───────────────────────
  "SyncController CalibrateChainWeightFromPeer" should
    "apply Tier 1 interpolation when peerTD and peerMaxBlock are both provided" taggedAs (UnitTest, SyncTest) in
    new RegularSyncSetup {
      setupBestBlock(blockNum = BigInt(24720000))
      drainRegistration()

      val peerTD = BigInt("24000000000000000000000")
      val peerMaxBlock = BigInt(25000000)
      syncController ! SyncProtocol.CalibrateChainWeightFromPeer(peerTD, peerMaxBlock)

      val expected = peerTD * BigInt(24720000) / peerMaxBlock
      val stored = blockchainReader.getChainWeightByHash(
        blockchainReader.getBestBlockHeader().get.hash
      )
      stored shouldBe defined
      stored.get.totalDifficulty shouldBe expected
    }

  // ─── T2.2 Tier 2: STATUS-only, no block number ────────────────────────────
  it should "apply Tier 2 (peerTD direct) when peerMaxBlock is 0" taggedAs (UnitTest, SyncTest) in
    new RegularSyncSetup {
      setupBestBlock(blockNum = BigInt(24720000))
      drainRegistration()

      val peerTD = BigInt("24000000000000000000000")
      syncController ! SyncProtocol.CalibrateChainWeightFromPeer(peerTD, BigInt(0))

      val stored = blockchainReader.getChainWeightByHash(
        blockchainReader.getBestBlockHeader().get.hash
      )
      stored shouldBe defined
      stored.get.totalDifficulty shouldBe peerTD
    }

  // ─── T2.3 Tier 3 entry: sentinel triggers local chain computation ──────────
  it should "call calibrateTDFromLocalChain when peerTD is 0 (ETH69 sentinel)" taggedAs (UnitTest, SyncTest) in
    new RegularSyncSetup {
      setupBestBlock(blockNum = BigInt(24720000))
      drainRegistration()

      // No anchor reachable → calibrateTDFromLocalChain returns false → retry scheduled
      syncController ! SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))

      // Proof: retry was scheduled — advance 30 min and expect CalibrateChainWeightNow
      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectMsg(CalibrateChainWeightNow)
    }

  // ─── T2.4 Tier 3 failure: retry is scheduled when anchor not found ─────────
  it should "schedule a 30-minute retry when calibrateTDFromLocalChain returns false" taggedAs (UnitTest, SyncTest) in
    new RegularSyncSetup {
      setupBestBlock(blockNum = BigInt(24720000))
      drainRegistration()

      syncController ! SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))

      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectMsg(CalibrateChainWeightNow)
      // No second message without advancing clock again
      networkPeerManager.expectNoMsg(100.millis)
    }

  // ─── T2.5 Tier 3 success: no retry when anchor found and TD written ────────
  it should "not schedule a retry when calibrateTDFromLocalChain succeeds" taggedAs (UnitTest, SyncTest) in
    new RegularSyncSetup {
      setupAnchorChain(bestBlockNum = 10, anchorNum = 7, anchorTD = BigInt("7000000000000000"))
      drainRegistration()

      syncController ! SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))

      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectNoMsg(200.millis)
    }

  // ─── T2.6 Plausibility gate — below threshold: no write ───────────────────
  it should "not write chain weight when calibratedTD is below genesisWeight × 1000" taggedAs (UnitTest, SyncTest) in
    new RegularSyncSetup {
      // bestBlock.number = 1; peerTD=100, peerMaxBlock=100000 → calibratedTD = 0 via integer div
      setupBestBlock(blockNum = BigInt(1))
      drainRegistration()

      val beforeHash = blockchainReader.getBestBlockHeader().get.hash
      val beforeWeight = blockchainReader.getChainWeightByHash(beforeHash)

      syncController ! SyncProtocol.CalibrateChainWeightFromPeer(BigInt(100), BigInt(100000))

      blockchainReader.getChainWeightByHash(beforeHash) shouldBe beforeWeight
    }

  // ─── T2.7 Plausibility gate — above threshold: write succeeds ─────────────
  it should "write chain weight when calibratedTD is above genesisWeight × 1000" taggedAs (UnitTest, SyncTest) in
    new RegularSyncSetup {
      setupBestBlock(blockNum = BigInt(24720000))
      drainRegistration()

      // 24e21 * 24720000 / 25000000 ≈ 2.37×10^22 >> 1.7×10^13 threshold
      val peerTD = BigInt("24000000000000000000000")
      syncController ! SyncProtocol.CalibrateChainWeightFromPeer(peerTD, BigInt(25000000))

      val stored = blockchainReader.getChainWeightByHash(
        blockchainReader.getBestBlockHeader().get.hash
      )
      stored shouldBe defined
      stored.get.totalDifficulty should be > BigInt("17179869184000")
    }

  // ─── T3.1 Core traversal: anchor at h7, accumulate h8-h10 ─────────────────
  "calibrateTDFromLocalChain" should
    "find the anchor at h7 and accumulate h8+h9+h10 difficulties" taggedAs (UnitTest, SyncTest) in
    new RegularSyncSetup {
      val anchorTD = BigInt("7000000000000000") // 7×10^15 > 7 × 10^13 → plausible anchor
      val chain = buildParentHashChain(startNum = 7, length = 4) // h7, h8, h9, h10
      val h7 = chain(0); val h8 = chain(1); val h9 = chain(2); val h10 = chain(3)

      // h7 has the valid anchor TD
      blockchainWriter.storeChainWeight(h7.hash, ChainWeight.totalDifficultyOnly(anchorTD)).commit()
      // h8–h10 have implausible TDs (well below blockNum × 10^13)
      blockchainWriter.storeChainWeight(h8.hash, ChainWeight.totalDifficultyOnly(BigInt(8))).commit()
      blockchainWriter.storeChainWeight(h9.hash, ChainWeight.totalDifficultyOnly(BigInt(9))).commit()
      blockchainWriter.storeChainWeight(h10.hash, ChainWeight.totalDifficultyOnly(BigInt(10))).commit()
      setBestBlockHeader(h10)

      drainRegistration()
      syncController ! SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))

      // Expected: anchorTD + h8.difficulty + h9.difficulty + h10.difficulty
      val expectedTD = anchorTD + h8.difficulty + h9.difficulty + h10.difficulty
      val stored = blockchainReader.getChainWeightByHash(h10.hash)
      stored shouldBe defined
      stored.get.totalDifficulty shouldBe expectedTD
    }

  // ─── T3.2 No anchor within MaxWalkBlocks: defer ────────────────────────────
  it should "return false and schedule retry when no plausible anchor found within walk limit" taggedAs (
    UnitTest,
    SyncTest
  ) in
    new RegularSyncSetup {
      setupBestBlock(blockNum = BigInt(24720000))
      drainRegistration()

      // No chain weight stored for the best block → walk hits genesis.parentHash (None) → abort
      syncController ! SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))

      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectMsg(CalibrateChainWeightNow)
    }

  // ─── T3.3 Idempotent: bestBlock already has correct TD ────────────────────
  it should "be idempotent when bestBlock already has a plausible stored TD" taggedAs (UnitTest, SyncTest) in
    new RegularSyncSetup {
      // bestBlock h10 has anchorTD that passes the plausibility check already
      val correctTD = BigInt("24000000000000000000000") // 24e21 >> 10 × 10^13
      val chain = buildParentHashChain(startNum = 10, length = 1)
      val h10 = chain(0)
      blockchainWriter.storeChainWeight(h10.hash, ChainWeight.totalDifficultyOnly(correctTD)).commit()
      setBestBlockHeader(h10)
      drainRegistration()

      // Walk finds anchor at h10 itself (zero gap), re-writes same value
      syncController ! SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))

      val stored = blockchainReader.getChainWeightByHash(h10.hash)
      stored.get.totalDifficulty shouldBe correctTD

      // No retry needed — success
      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectNoMsg(200.millis)
    }

  // ─── T3.4 Broken parentHash chain aborts cleanly ─────────────────────────
  it should "abort without writing when parentHash lookup returns None" taggedAs (UnitTest, SyncTest) in
    new RegularSyncSetup {
      // Build h10 whose parentHash points to a header that is NOT stored
      val h10 = Fixtures.Blocks.Genesis.header.copy(
        number = BigInt(10),
        parentHash = fakeMissingHash // parentHash for a header that doesn't exist
      )
      blockchainWriter.storeBlockHeader(h10).commit()
      blockchainWriter.storeChainWeight(h10.hash, ChainWeight.totalDifficultyOnly(BigInt(10))).commit()
      setBestBlockHeader(h10)
      drainRegistration()

      val beforeWeight = blockchainReader.getChainWeightByHash(h10.hash)

      syncController ! SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))

      // Weight unchanged — abort on broken chain
      blockchainReader.getChainWeightByHash(h10.hash) shouldBe beforeWeight

      // Retry is scheduled (abort returns false → scheduleTDCalibrationRetry)
      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectMsg(CalibrateChainWeightNow)
    }

  // ─── T3.5 Plausibility gate blocks write of low computed TD ───────────────
  it should "not write when accumulated TD is below genesisWeight × 1000" taggedAs (UnitTest, SyncTest) in
    new RegularSyncSetup {
      // Anchor at h1 with absurdly low anchorTD — accumulated result < genesis × 1000
      val tinyAnchorTD = BigInt("1000000000000") // 10^12 < 1 × 10^13 → but wait, h1 has number=1
      // Actually 1×10^12 < 1×10^13 → NOT a plausible anchor! So the walk will abort, not return a below-threshold result.
      // Instead: use a large-numbered block with barely-above-threshold anchor that still leads to below-genesis*1000 result.
      // Simplest: anchor with TD=1 (passes BigInt check), but 1 < genesis*1000 → plausibility rejects it.
      // Use anchor TD that passes the walk-check (> blockNum × MinTDPerBlock) but the final WRITE plausibility fails.
      // h1 with anchorTD = 2×10^13 (> 1×10^13, so plausible anchor) → accumulated td = 2×10^13 + 0 gaps
      // genesis.difficulty ≈ 1.7×10^10, genesisWeight*1000 ≈ 1.7×10^13 < 2×10^13 → passes write gate too.
      // So we need: anchorTD > blockNum × 10^13 (to find the anchor) but accumulated td < genesisWeight * 1000.
      // This is structurally impossible if genesisWeight is based on genesis.difficulty (1.7×10^10) and anchorTD > blockNum×10^13:
      //   anchorTD > blockNum × 10^13 ≥ 1 × 10^13 (for blockNum ≥ 1)
      //   genesisWeight * 1000 ≈ 1.7×10^13
      // So min plausible anchorTD ≈ 10^13 + ε, which is right around the threshold.
      // For blockNum=1: anchorTD must be > 10^13. Threshold is 1.7×10^13. If anchorTD=1.1×10^13 → write gate rejects.
      val tightAnchor = BigInt("11000000000000") // 1.1×10^13 > 1×10^13 → found as anchor
      val chain = buildParentHashChain(startNum = 1, length = 1)
      val h1 = chain(0)
      blockchainWriter.storeChainWeight(h1.hash, ChainWeight.totalDifficultyOnly(tightAnchor)).commit()
      setBestBlockHeader(h1)
      drainRegistration()

      val beforeWeight = blockchainReader.getChainWeightByHash(h1.hash)
      syncController ! SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))

      // 1.1×10^13 < 1.7×10^13 (genesisWeight × 1000) → write gate rejects → weight unchanged
      blockchainReader.getChainWeightByHash(h1.hash) shouldBe beforeWeight
      // Retry is scheduled (plausibility failure → returns false)
      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectMsg(CalibrateChainWeightNow)
    }

  // ─── T3.6 Boundary: gap = MaxWalkBlocks exactly succeeds ──────────────────
  it should "succeed when gap equals MaxWalkBlocks (10000 headers above anchor)" taggedAs (UnitTest, SyncTest) in
    new RegularSyncSetup {
      val MaxWalk = 10000
      // Build chain: h1 (anchor), h2...h10001 (10000 headers above anchor)
      val anchorTD = BigInt("10000000000000000") // 10^16 >> threshold
      val chain = buildParentHashChain(startNum = 1, length = MaxWalk + 1) // h1..h10001
      val anchor = chain.head
      val bestHdr = chain.last

      blockchainWriter.storeChainWeight(anchor.hash, ChainWeight.totalDifficultyOnly(anchorTD)).commit()
      // All headers above anchor: implausible TDs
      chain.tail.foreach { h =>
        blockchainWriter.storeChainWeight(h.hash, ChainWeight.totalDifficultyOnly(BigInt(1))).commit()
      }
      setBestBlockHeader(bestHdr)
      drainRegistration()

      syncController ! SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))

      val stored = blockchainReader.getChainWeightByHash(bestHdr.hash)
      stored shouldBe defined
      val expectedTD = anchorTD + chain.tail.foldLeft(BigInt(0))((acc, h) => acc + h.difficulty)
      stored.get.totalDifficulty shouldBe expectedTD
    }

  // ─── T3.7 Boundary: gap = MaxWalkBlocks + 1 defers ───────────────────────
  it should "defer when gap is one past MaxWalkBlocks" taggedAs (UnitTest, SyncTest) in
    new RegularSyncSetup {
      val MaxWalk = 10000
      // Chain of MaxWalk+2 headers; anchor is at head (h1), gap = MaxWalk+1
      val chain = buildParentHashChain(startNum = 1, length = MaxWalk + 2)
      val anchor = chain.head
      val bestHdr = chain.last
      val anchorTD = BigInt("10000000000000000")

      blockchainWriter.storeChainWeight(anchor.hash, ChainWeight.totalDifficultyOnly(anchorTD)).commit()
      chain.tail.foreach { h =>
        blockchainWriter.storeChainWeight(h.hash, ChainWeight.totalDifficultyOnly(BigInt(1))).commit()
      }
      setBestBlockHeader(bestHdr)
      drainRegistration()

      val beforeWeight = blockchainReader.getChainWeightByHash(bestHdr.hash)
      syncController ! SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))

      blockchainReader.getChainWeightByHash(bestHdr.hash) shouldBe beforeWeight
      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectMsg(CalibrateChainWeightNow)
    }

  // ─── T3.8 Walk stops at the FIRST plausible anchor (closest to bestBlock) ─
  it should "stop at the first plausible anchor encountered while walking backward" taggedAs (UnitTest, SyncTest) in
    new RegularSyncSetup {
      // h5 and h10 both have plausible TDs; walk from h15 should find h10 first
      val anchorTD_5 = BigInt("5000000000000000") // would give wrong lower td
      val anchorTD_10 = BigInt("10000000000000000") // correct anchor (first hit from h15)
      val chain = buildParentHashChain(startNum = 5, length = 11) // h5..h15
      val h5 = chain(0); val h10 = chain(5); val h15 = chain(10)

      // Store plausible TDs at h5 and h10; implausible at everything else
      blockchainWriter.storeChainWeight(h5.hash, ChainWeight.totalDifficultyOnly(anchorTD_5)).commit()
      blockchainWriter.storeChainWeight(h10.hash, ChainWeight.totalDifficultyOnly(anchorTD_10)).commit()
      chain.filterNot(h => h.hash == h5.hash || h.hash == h10.hash).foreach { h =>
        blockchainWriter.storeChainWeight(h.hash, ChainWeight.totalDifficultyOnly(BigInt(1))).commit()
      }
      setBestBlockHeader(h15)
      drainRegistration()

      syncController ! SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))

      // Should use anchorTD_10 (found first walking back from h15), NOT anchorTD_5
      val stored = blockchainReader.getChainWeightByHash(h15.hash)
      stored shouldBe defined
      // accumulated = anchorTD_10 + h11.difficulty + ... + h15.difficulty (5 headers)
      val gapHeaders = chain.slice(6, 11) // h11..h15
      val expectedTD = anchorTD_10 + gapHeaders.foldLeft(BigInt(0))(_ + _.difficulty)
      stored.get.totalDifficulty shouldBe expectedTD
    }

  // ─── T4.1 Retry loop: two consecutive 30-minute retries ──────────────────
  "ChainWeightCalibration retry loop" should
    "fire CalibrateChainWeightNow again after each failed attempt" taggedAs (UnitTest, SyncTest) in
    new RegularSyncSetup {
      setupBestBlock(blockNum = BigInt(24720000))
      drainRegistration()

      // Attempt 1 → no anchor → retry scheduled
      syncController ! SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))
      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectMsg(CalibrateChainWeightNow)

      // Simulate NPA sending another sentinel (no ETH68 peers yet)
      syncController ! SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))
      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectMsg(CalibrateChainWeightNow)
    }

  // ─── T4.2 Retry terminates on success ────────────────────────────────────
  it should "stop scheduling retries when calibrateTDFromLocalChain succeeds" taggedAs (UnitTest, SyncTest) in
    new RegularSyncSetup {
      setupBestBlock(blockNum = BigInt(24720000))
      drainRegistration()

      // Attempt 1 fails
      syncController ! SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))
      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectMsg(CalibrateChainWeightNow)

      // Now install an anchor that makes attempt 2 succeed
      val anchorTD = BigInt("24000000000000000000000")
      val chain = buildParentHashChain(startNum = 24720000, length = 1)
      val bestHdr = chain(0)
      blockchainWriter.storeChainWeight(bestHdr.hash, ChainWeight.totalDifficultyOnly(anchorTD)).commit()
      setBestBlockHeader(bestHdr)

      // Attempt 2 succeeds → no retry scheduled
      syncController ! SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))
      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectNoMsg(200.millis)
    }

  // ─── T4.3 ETH68 peer appears at retry: tier 2 fires, no local chain ───────
  it should "use Tier 2 (not local chain) when an ETH68 peer appears at retry time" taggedAs (UnitTest, SyncTest) in
    new RegularSyncSetup {
      setupBestBlock(blockNum = BigInt(24720000))
      drainRegistration()

      // Attempt 1: sentinel, no anchor → retry scheduled
      syncController ! SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))
      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectMsg(CalibrateChainWeightNow)

      // Attempt 2: ETH68 peer data (tier 2) — no retry expected
      val peerTD = BigInt("24000000000000000000000")
      syncController ! SyncProtocol.CalibrateChainWeightFromPeer(peerTD, BigInt(0))

      val stored = blockchainReader.getChainWeightByHash(
        blockchainReader.getBestBlockHeader().get.hash
      )
      stored.get.totalDifficulty shouldBe peerTD

      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectNoMsg(200.millis)
    }

  // ─── Base test setup ──────────────────────────────────────────────────────

  trait RegularSyncSetup extends EphemBlockchainTestSetup with TestSyncConfig with TestSyncPeers {

    implicit override lazy val system: ActorSystem =
      ActorSystem("ChainWeightCalibrationSpec_System", ConfigFactory.load("explicit-scheduler"))

    private val testSchedulerDelegate = system.scheduler.asInstanceOf[ExplicitlyTriggeredScheduler]
    def testScheduler: ExplicitlyTriggeredScheduler = testSchedulerDelegate

    val networkPeerManager: TestProbe = TestProbe()
    val peerMessageBus: TestProbe = TestProbe()
    val pendingTransactionsManager: TestProbe = TestProbe()
    val ommersPool: TestProbe = TestProbe()

    val blacklist: CacheBasedBlacklist = CacheBasedBlacklist.empty(100)

    override lazy val vm: VMImpl = new VMImpl
    override lazy val validators = new MockValidatorsAlwaysSucceed
    override lazy val mining = buildTestMining().withValidators(validators)

    override def defaultSyncConfig: SyncConfig = super.defaultSyncConfig.copy(
      doFastSync = false,
      doSnapSync = false,
      // Long intervals prevent periodic GetHandshakedPeers / block-check messages from
      // appearing in networkPeerManager's queue when timePasses(30.minutes) advances the
      // ExplicitlyTriggeredScheduler — those would arrive before CalibrateChainWeightNow.
      // 4 hours > the longest test window (T4.1 / T8.2 at ~60 minutes).
      peersScanInterval = 4.hours,
      checkForNewBlockInterval = 4.hours,
      peerResponseTimeout = 2.seconds,
      blacklistDuration = 1.second
    )

    lazy val syncController: TestActorRef[Nothing] = TestActorRef(
      Props(
        new SyncController(
          blockchain,
          blockchainReader,
          blockchainWriter,
          storagesInstance.storages.appStateStorage,
          storagesInstance.storages.blockNumberMappingStorage,
          storagesInstance.storages.evmCodeStorage,
          storagesInstance.storages.stateStorage,
          storagesInstance.storages.nodeStorage,
          storagesInstance.storages.flatSlotStorage,
          storagesInstance.storages.fastSyncStateStorage,
          consensusAdapter,
          validators,
          peerMessageBus.ref,
          pendingTransactionsManager.ref,
          ommersPool.ref,
          networkPeerManager.ref,
          blacklist,
          syncConfig,
          this,
          externalSchedulerOpt = Some(system.scheduler)
        )
      )
    )

    // Store genesis so blockchainReader.genesisHeader works (needed for calibration plausibility gate)
    blockchainWriter.storeBlockHeader(Fixtures.Blocks.Genesis.header).commit()
    blockchainWriter
      .storeChainWeight(
        Fixtures.Blocks.Genesis.header.hash,
        ChainWeight.totalDifficultyOnly(Fixtures.Blocks.Genesis.header.difficulty)
      )
      .commit()
    blockchainWriter.storeChainWeight(Fixtures.Blocks.Genesis.header.parentHash, ChainWeight.zero).commit()

    // Fake parentHash used in T3.4 (a header that's never stored in DB)
    val fakeMissingHash = org.apache.pekko.util.ByteString(
      Array.fill(32)(0xff.toByte)
    )

    /** Start SyncController and kick into regular sync mode. */
    def startSync(): Unit = syncController ! SyncProtocol.Start

    /** Start sync and drain all startup messages sent to networkPeerManager.
      *
      * startRegularSync() triggers several startup messages to networkPeerManager:
      *   1. RegisterChainWeightCalibrationTarget — sent synchronously 2. GetHandshakedPeers × N (T+0) — each actor
      *      mixing PeerListSupportNg with scheduleWithFixedDelay(initialDelay=0) contributes one message 3.
      *      CalibrateChainWeightNow (T+30s) — startup timed calibration from SyncController
      *
      * After this returns the probe is clean; subsequent expectMsg calls test only the scenario.
      */
    def drainRegistration(): Unit = {
      startSync()
      networkPeerManager.expectMsgClass(classOf[RegisterChainWeightCalibrationTarget])
      testScheduler.timePasses(31.seconds)
      // Fish past however many GetHandshakedPeers arrive from PeerListSupportNg actors until
      // the initial 30s CalibrateChainWeightNow is consumed, leaving the probe queue empty.
      networkPeerManager.fishForMessage(3.seconds) {
        case CalibrateChainWeightNow => true // consumed; done
        case GetHandshakedPeers      => false // skip any number of these
      }
    }

    /** Store a best block with a given block number so getBestBlock/getBestBlockHeader succeed. */
    def setupBestBlock(blockNum: BigInt): Unit = {
      val hdr = Fixtures.Blocks.Genesis.header.copy(number = blockNum)
      val blk = Block(hdr, BlockBody(Nil, Nil))
      blockchainWriter.save(blk, Seq.empty, ChainWeight.totalDifficultyOnly(BigInt(1)), saveAsBestBlock = true)
    }

    /** Store a header as the best block without a full block body. */
    def setBestBlockHeader(hdr: BlockHeader): Unit = {
      blockchainWriter.storeBlockHeader(hdr).commit()
      storagesInstance.storages.appStateStorage
        .putBestBlockInfo(BlockInfo(hdr.hash, hdr.number))
        .commit()
    }

    /** Build an anchor chain: `length` headers starting at `startNum`, each pointing to the previous via parentHash.
      * The first header's parentHash is genesis.hash. Returns headers in ascending order: [h_startNum, h_(startNum+1),
      * ..., h_(startNum+length-1)].
      */
    def buildParentHashChain(startNum: Int, length: Int): Vector[BlockHeader] = {
      var prev = Fixtures.Blocks.Genesis.header
      val buf = scala.collection.mutable.ArrayBuffer.empty[BlockHeader]
      for (i <- 0 until length) {
        val n = startNum + i
        val h = Fixtures.Blocks.Genesis.header.copy(
          number = BigInt(n),
          parentHash = prev.hash,
          // vary a field to ensure a unique hash per block (nonce distinguishes them)
          nonce = org.apache.pekko.util.ByteString(Array.fill(8)(n.toByte))
        )
        blockchainWriter.storeBlockHeader(h).commit()
        buf += h
        prev = h
      }
      buf.toVector
    }

    /** Convenience: build anchor chain and configure anchor TD at the first block. */
    def setupAnchorChain(bestBlockNum: Int, anchorNum: Int, anchorTD: BigInt): Unit = {
      val length = bestBlockNum - anchorNum + 1
      val chain = buildParentHashChain(anchorNum, length)
      val anchor = chain(0)
      val bestHdr = chain(length - 1)
      blockchainWriter.storeChainWeight(anchor.hash, ChainWeight.totalDifficultyOnly(anchorTD)).commit()
      // Implausible TDs for all headers above anchor
      chain.tail.foreach { h =>
        blockchainWriter.storeChainWeight(h.hash, ChainWeight.totalDifficultyOnly(BigInt(1))).commit()
      }
      setBestBlockHeader(bestHdr)
    }

    def cleanup(): Unit = Await.result(system.terminate(), 10.seconds)
  }
}
// scalastyle:on magic.number
