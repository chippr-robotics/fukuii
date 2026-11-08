// §8a-retro batch 5: DEFERRED — TestActorRef used for .children inspection (Classic-only API);
// migrate when SyncController test no longer needs child inspection (Wave 3 network sprint)
package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.ExplicitlyTriggeredScheduler
import org.apache.pekko.testkit.TestActorRef
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.Await
import scala.concurrent.duration.*

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.Mocks.MockValidatorsAlwaysSucceed
import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.consensus.mining.TestMining
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.CalibrateChainWeightNowCmd
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.GetHandshakedPeersCmd
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RegisterChainWeightCalibrationTargetCmd
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config.SyncConfig

// scalastyle:off magic.number
class ChainWeightCalibrationSpec extends AnyFlatSpec with Matchers:

  // ─── T2.1 Tier 1: exact interpolation from NewBlock ───────────────────────
  "SyncController CalibrateChainWeightFromPeer" should
    "apply Tier 1 interpolation when peerTD and peerMaxBlock are both provided" taggedAs (UnitTest, SyncTest) in
    (new RegularSyncSetup:
      setupBestBlock(blockNum = BigInt(24720000))
      drainRegistration()

      val peerTD: BigInt = BigInt("24000000000000000000000")
      val peerMaxBlock: BigInt = BigInt(25000000)
      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(peerTD, peerMaxBlock)
      )

      val expected: BigInt = peerTD * BigInt(24720000) / peerMaxBlock
      val stored: Option[ChainWeight] = blockchainReader.getChainWeightByHash(
        blockchainReader.getBestBlockHeader.get.hash
      )
      stored shouldBe defined
      stored.get.totalDifficulty.value shouldBe expected
    )

  // ─── T2.2 Tier 2: STATUS-only, no block number ────────────────────────────
  it should "apply Tier 2 (peerTD direct) when peerMaxBlock is 0" taggedAs (UnitTest, SyncTest) in
    (new RegularSyncSetup:
      setupBestBlock(blockNum = BigInt(24720000))
      drainRegistration()

      val peerTD: BigInt = BigInt("24000000000000000000000")
      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.CalibrateChainWeightFromPeer(peerTD, BigInt(0)))

      val stored: Option[ChainWeight] = blockchainReader.getChainWeightByHash(
        blockchainReader.getBestBlockHeader.get.hash
      )
      stored shouldBe defined
      stored.get.totalDifficulty.value shouldBe peerTD
    )

  // ─── T2.3 Tier 3 entry: sentinel triggers local chain computation ──────────
  it should "call calibrateTDFromLocalChain when peerTD is 0 (ETH69 sentinel)" taggedAs (UnitTest, SyncTest) in
    (new RegularSyncSetup:
      setupBestBlock(blockNum = BigInt(24720000))
      drainRegistration()

      // No anchor reachable → calibrateTDFromLocalChain returns false → retry scheduled
      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))
      )

      // Proof: retry was scheduled — advance 30 min and expect CalibrateChainWeightNowCmd
      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectMsg(CalibrateChainWeightNowCmd)
    )

  // ─── T2.4 Tier 3 failure: retry is scheduled when anchor not found ─────────
  it should "schedule a 30-minute retry when calibrateTDFromLocalChain returns false" taggedAs (UnitTest, SyncTest) in
    (new RegularSyncSetup:
      setupBestBlock(blockNum = BigInt(24720000))
      drainRegistration()

      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))
      )

      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectMsg(CalibrateChainWeightNowCmd)
      // No second message without advancing clock again
      networkPeerManager.expectNoMessage(100.millis)
    )

  // ─── T2.5 Tier 3 success: no retry when anchor found and TD written ────────
  it should "not schedule a retry when calibrateTDFromLocalChain succeeds" taggedAs (UnitTest, SyncTest) in
    (new RegularSyncSetup:
      setupAnchorChain(bestBlockNum = 10, anchorNum = 7, anchorTD = BigInt("7000000000000000"))
      drainRegistration()

      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))
      )

      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectNoMessage(200.millis)
    )

  // ─── T2.6 Plausibility gate — below threshold: no write ───────────────────
  it should "not write chain weight when calibratedTD is below genesisWeight × 1000" taggedAs (UnitTest, SyncTest) in
    (new RegularSyncSetup:
      // bestBlock.number = 1; peerTD=100, peerMaxBlock=100000 → calibratedTD = 0 via integer div
      setupBestBlock(blockNum = BigInt(1))
      drainRegistration()

      val beforeHash = blockchainReader.getBestBlockHeader.get.hash
      val beforeWeight: Option[ChainWeight] = blockchainReader.getChainWeightByHash(beforeHash)

      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(BigInt(100), BigInt(100000))
      )

      blockchainReader.getChainWeightByHash(beforeHash) shouldBe beforeWeight
    )

  // ─── T2.7 Plausibility gate — above threshold: write succeeds ─────────────
  it should "write chain weight when calibratedTD is above genesisWeight × 1000" taggedAs (UnitTest, SyncTest) in
    (new RegularSyncSetup:
      setupBestBlock(blockNum = BigInt(24720000))
      drainRegistration()

      // 24e21 * 24720000 / 25000000 ≈ 2.37×10^22 >> 1.7×10^13 threshold
      val peerTD: BigInt = BigInt("24000000000000000000000")
      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(peerTD, BigInt(25000000))
      )

      val stored: Option[ChainWeight] = blockchainReader.getChainWeightByHash(
        blockchainReader.getBestBlockHeader.get.hash
      )
      stored shouldBe defined
      stored.get.totalDifficulty.value should be > BigInt("17179869184000")
    )

  // ─── T3.1 Core traversal: anchor at h7, accumulate h8-h10 ─────────────────
  "calibrateTDFromLocalChain" should
    "find the anchor at h7 and accumulate h8+h9+h10 difficulties" taggedAs (UnitTest, SyncTest) in
    (new RegularSyncSetup:
      val anchorTD: BigInt = BigInt("7000000000000000") // 7×10^15 > 7 × 10^13 → plausible anchor
      val chain: Vector[BlockHeader] = buildParentHashChain(startNum = 7, length = 4) // h7, h8, h9, h10
      val h7: BlockHeader = chain(0); val h8: BlockHeader = chain(1); val h9: BlockHeader = chain(2);
      val h10: BlockHeader = chain(3)

      // h7 has the valid anchor TD
      blockchainWriter.storeChainWeight(h7.hash, ChainWeight.totalDifficultyOnly(anchorTD)).commit()
      // h8–h10 have implausible TDs (well below blockNum × 10^13)
      blockchainWriter.storeChainWeight(h8.hash, ChainWeight.totalDifficultyOnly(BigInt(8))).commit()
      blockchainWriter.storeChainWeight(h9.hash, ChainWeight.totalDifficultyOnly(BigInt(9))).commit()
      blockchainWriter.storeChainWeight(h10.hash, ChainWeight.totalDifficultyOnly(BigInt(10))).commit()
      setBestBlockHeader(h10)

      drainRegistration()
      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))
      )

      // Expected: anchorTD + h8.difficulty + h9.difficulty + h10.difficulty
      val expectedTD: BigInt = anchorTD + h8.difficulty.value + h9.difficulty.value + h10.difficulty.value
      val stored: Option[ChainWeight] = blockchainReader.getChainWeightByHash(h10.hash)
      stored shouldBe defined
      stored.get.totalDifficulty.value shouldBe expectedTD
    )

  // ─── T3.2 No anchor within MaxWalkBlocks: defer ────────────────────────────
  it should "return false and schedule retry when no plausible anchor found within walk limit" taggedAs (
    UnitTest,
    SyncTest
  ) in
    (new RegularSyncSetup:
      setupBestBlock(blockNum = BigInt(24720000))
      drainRegistration()

      // No chain weight stored for the best block → walk hits genesis.parentHash (None) → abort
      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))
      )

      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectMsg(CalibrateChainWeightNowCmd)
    )

  // ─── T3.3 Idempotent: bestBlock already has correct TD ────────────────────
  it should "be idempotent when bestBlock already has a plausible stored TD" taggedAs (UnitTest, SyncTest) in
    (new RegularSyncSetup:
      // bestBlock h10 has anchorTD that passes the plausibility check already
      val correctTD: BigInt = BigInt("24000000000000000000000") // 24e21 >> 10 × 10^13
      val chain: Vector[BlockHeader] = buildParentHashChain(startNum = 10, length = 1)
      val h10: BlockHeader = chain(0)
      blockchainWriter.storeChainWeight(h10.hash, ChainWeight.totalDifficultyOnly(correctTD)).commit()
      setBestBlockHeader(h10)
      drainRegistration()

      // Walk finds anchor at h10 itself (zero gap), re-writes same value
      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))
      )

      val stored: Option[ChainWeight] = blockchainReader.getChainWeightByHash(h10.hash)
      stored.get.totalDifficulty.value shouldBe correctTD

      // No retry needed — success
      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectNoMessage(200.millis)
    )

  // ─── T3.4 Broken parentHash chain aborts cleanly ─────────────────────────
  it should "abort without writing when parentHash lookup returns None" taggedAs (UnitTest, SyncTest) in
    (new RegularSyncSetup:
      // Build h10 whose parentHash points to a header that is NOT stored
      val h10: BlockHeader = Fixtures.Blocks.Genesis.header.copy(
        number = BlockNumber(10),
        parentHash = BlockHash(fakeMissingHash) // parentHash for a header that doesn't exist
      )
      blockchainWriter.storeBlockHeader(h10).commit()
      blockchainWriter.storeChainWeight(h10.hash, ChainWeight.totalDifficultyOnly(BigInt(10))).commit()
      setBestBlockHeader(h10)
      drainRegistration()

      val beforeWeight: Option[ChainWeight] = blockchainReader.getChainWeightByHash(h10.hash)

      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))
      )

      // Weight unchanged — abort on broken chain
      blockchainReader.getChainWeightByHash(h10.hash) shouldBe beforeWeight

      // Retry is scheduled (abort returns false → scheduleTDCalibrationRetry)
      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectMsg(CalibrateChainWeightNowCmd)
    )

  // ─── T3.5 Plausibility gate blocks write of low computed TD ───────────────
  it should "not write when accumulated TD is below genesisWeight × 1000" taggedAs (UnitTest, SyncTest) in
    (new RegularSyncSetup:
      // An anchor that clears the walk's floor (HeadChainWeight.floor: genesis weight 1.7×10^10 + 131072 per block) but
      // whose accumulated TD fails the write gate (genesisWeight × 1000 ≈ 1.7×10^13): h1 with 1.1×10^13, zero gap.
      //
      // #1654: h1's parent is NOT stored. With genesis as its parent, as this test first had it, the head's exact weight
      // is derivable (genesis weight + h1 difficulty) and is written before Tier 3 runs (see the #1654 tests below), so
      // this write gate would never be reached.
      val tightAnchor: BigInt = BigInt("11000000000000") // 1.1×10^13: above the floor, below the write gate
      val h1: BlockHeader = Fixtures.Blocks.Genesis.header.copy(
        number = BlockNumber(1),
        parentHash = BlockHash(fakeMissingHash)
      )
      blockchainWriter.storeBlockHeader(h1).commit()
      blockchainWriter.storeChainWeight(h1.hash, ChainWeight.totalDifficultyOnly(tightAnchor)).commit()
      setBestBlockHeader(h1)
      drainRegistration()

      val beforeWeight: Option[ChainWeight] = blockchainReader.getChainWeightByHash(h1.hash)
      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))
      )

      // 1.1×10^13 < 1.7×10^13 (genesisWeight × 1000) → write gate rejects → weight unchanged
      blockchainReader.getChainWeightByHash(h1.hash) shouldBe beforeWeight
      // Retry is scheduled (plausibility failure → returns false)
      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectMsg(CalibrateChainWeightNowCmd)
    )

  // ─── T3.6 Boundary: gap = MaxWalkBlocks exactly succeeds ──────────────────
  it should "succeed when gap equals MaxWalkBlocks (10000 headers above anchor)" taggedAs (UnitTest, SyncTest) in
    (new RegularSyncSetup:
      val MaxWalk = 10000
      // Build chain: h1 (anchor), h2...h10001 (10000 headers above anchor)
      val anchorTD: BigInt = BigInt("10000000000000000") // 10^16 >> threshold
      val chain: Vector[BlockHeader] = buildParentHashChain(startNum = 1, length = MaxWalk + 1) // h1..h10001
      val anchor = chain.head
      val bestHdr = chain.last

      blockchainWriter.storeChainWeight(anchor.hash, ChainWeight.totalDifficultyOnly(anchorTD)).commit()
      // All headers above anchor: implausible TDs
      chain.tail.foreach { h =>
        blockchainWriter.storeChainWeight(h.hash, ChainWeight.totalDifficultyOnly(BigInt(1))).commit()
      }
      setBestBlockHeader(bestHdr)
      drainRegistration()

      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))
      )

      val stored: Option[ChainWeight] = blockchainReader.getChainWeightByHash(bestHdr.hash)
      stored shouldBe defined
      val expectedTD: BigInt = anchorTD + chain.tail.foldLeft(BigInt(0))((acc, h) => acc + h.difficulty.value)
      stored.get.totalDifficulty.value shouldBe expectedTD
    )

  // ─── T3.7 Boundary: gap = MaxWalkBlocks + 1 defers ───────────────────────
  it should "defer when gap is one past MaxWalkBlocks" taggedAs (UnitTest, SyncTest) in
    (new RegularSyncSetup:
      val MaxWalk = 10000
      // Chain of MaxWalk+2 headers; anchor is at head (h1), gap = MaxWalk+1
      val chain: Vector[BlockHeader] = buildParentHashChain(startNum = 1, length = MaxWalk + 2)
      val anchor = chain.head
      val bestHdr = chain.last
      val anchorTD: BigInt = BigInt("10000000000000000")

      blockchainWriter.storeChainWeight(anchor.hash, ChainWeight.totalDifficultyOnly(anchorTD)).commit()
      chain.tail.foreach { h =>
        blockchainWriter.storeChainWeight(h.hash, ChainWeight.totalDifficultyOnly(BigInt(1))).commit()
      }
      setBestBlockHeader(bestHdr)
      drainRegistration()

      val beforeWeight: Option[ChainWeight] = blockchainReader.getChainWeightByHash(bestHdr.hash)
      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))
      )

      blockchainReader.getChainWeightByHash(bestHdr.hash) shouldBe beforeWeight
      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectMsg(CalibrateChainWeightNowCmd)
    )

  // ─── T3.8 Walk stops at the FIRST plausible anchor (closest to bestBlock) ─
  it should "stop at the first plausible anchor encountered while walking backward" taggedAs (UnitTest, SyncTest) in
    (new RegularSyncSetup:
      // h5 and h10 both have plausible TDs; walk from h15 should find h10 first
      val anchorTD_5: BigInt = BigInt("5000000000000000") // would give wrong lower td
      val anchorTD_10: BigInt = BigInt("10000000000000000") // correct anchor (first hit from h15)
      val chain: Vector[BlockHeader] = buildParentHashChain(startNum = 5, length = 11) // h5..h15
      val h5: BlockHeader = chain(0); val h10: BlockHeader = chain(5); val h15: BlockHeader = chain(10)

      // Store plausible TDs at h5 and h10; implausible at everything else
      blockchainWriter.storeChainWeight(h5.hash, ChainWeight.totalDifficultyOnly(anchorTD_5)).commit()
      blockchainWriter.storeChainWeight(h10.hash, ChainWeight.totalDifficultyOnly(anchorTD_10)).commit()
      chain.filterNot(h => h.hash == h5.hash || h.hash == h10.hash).foreach { h =>
        blockchainWriter.storeChainWeight(h.hash, ChainWeight.totalDifficultyOnly(BigInt(1))).commit()
      }
      setBestBlockHeader(h15)
      drainRegistration()

      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))
      )

      // Should use anchorTD_10 (found first walking back from h15), NOT anchorTD_5
      val stored: Option[ChainWeight] = blockchainReader.getChainWeightByHash(h15.hash)
      stored shouldBe defined
      // accumulated = anchorTD_10 + h11.difficulty + ... + h15.difficulty (5 headers)
      val gapHeaders: Vector[BlockHeader] = chain.slice(6, 11) // h11..h15
      val expectedTD: BigInt = anchorTD_10 + gapHeaders.foldLeft(BigInt(0))(_ + _.difficulty.value)
      stored.get.totalDifficulty.value shouldBe expectedTD
    )

  // ─── T4.1 Retry loop: two consecutive 30-minute retries ──────────────────
  "ChainWeightCalibration retry loop" should
    "fire CalibrateChainWeightNowCmd again after each failed attempt" taggedAs (UnitTest, SyncTest) in
    (new RegularSyncSetup:
      setupBestBlock(blockNum = BigInt(24720000))
      drainRegistration()

      // Attempt 1 → no anchor → retry scheduled
      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))
      )
      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectMsg(CalibrateChainWeightNowCmd)

      // Simulate NPA sending another sentinel (no ETH68 peers yet)
      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))
      )
      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectMsg(CalibrateChainWeightNowCmd)
    )

  // ─── T4.2 Retry terminates on success ────────────────────────────────────
  it should "stop scheduling retries when calibrateTDFromLocalChain succeeds" taggedAs (UnitTest, SyncTest) in
    (new RegularSyncSetup:
      setupBestBlock(blockNum = BigInt(24720000))
      drainRegistration()

      // Attempt 1 fails
      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))
      )
      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectMsg(CalibrateChainWeightNowCmd)

      // Now install an anchor that makes attempt 2 succeed
      val anchorTD: BigInt = BigInt("24000000000000000000000")
      val chain: Vector[BlockHeader] = buildParentHashChain(startNum = 24720000, length = 1)
      val bestHdr: BlockHeader = chain(0)
      blockchainWriter.storeChainWeight(bestHdr.hash, ChainWeight.totalDifficultyOnly(anchorTD)).commit()
      setBestBlockHeader(bestHdr)

      // Attempt 2 succeeds → no retry scheduled
      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))
      )
      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectNoMessage(200.millis)
    )

  // ─── T4.3 ETH68 peer appears at retry: tier 2 fires, no local chain ───────
  it should "use Tier 2 (not local chain) when an ETH68 peer appears at retry time" taggedAs (UnitTest, SyncTest) in
    (new RegularSyncSetup:
      setupBestBlock(blockNum = BigInt(24720000))
      drainRegistration()

      // Attempt 1: sentinel, no anchor → retry scheduled
      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))
      )
      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectMsg(CalibrateChainWeightNowCmd)

      // Attempt 2: ETH68 peer data (tier 2) — no retry expected
      val peerTD: BigInt = BigInt("24000000000000000000000")
      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.CalibrateChainWeightFromPeer(peerTD, BigInt(0)))

      val stored: Option[ChainWeight] = blockchainReader.getChainWeightByHash(
        blockchainReader.getBestBlockHeader.get.hash
      )
      stored.get.totalDifficulty.value shouldBe peerTD

      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectNoMessage(200.millis)
    )

  // ─── #1654: an exact stored head weight is never replaced by an estimate ──
  //
  // The chains below are Mordor-scale: ~9e6 difficulty per block and TD 174823251167868 at the head, the Mordor source
  // node's `before=` value from the issue. The fixture genesis is ETC mainnet's (TD 17179869184).

  "#1654 timed calibration" should
    "keep an exact stored head weight through Tier 1, Tier 2 and Tier 3 (Mordor source-node restart)" taggedAs (
      UnitTest,
      SyncTest
    ) in
    (new RegularSyncSetup:
      // Pivot P has no stored parent (SNAP, headers not backfilled); P+1 and P+2 were imported on top of it, so each
      // stored weight is its parent's plus its difficulty.
      val chain: Vector[BlockHeader] =
        saveLinkedChain(BlockHash(fakeMissingHash), MordorPivot, MordorHeadTD - MordorDifficulty * 2, length = 3)
      val head: BlockHeader = chain.last
      def headTD: BigInt = blockchainReader.getChainWeightByHash(head.hash).get.totalDifficulty.value
      headTD shouldBe MordorHeadTD
      drainRegistration()

      // Tier 1: before #1654 this wrote peerTD * 15,000,002 / 15,000,007, a linear guess.
      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(BigInt("174823400000000"), MordorPivot + 7)
      )
      headTD shouldBe MordorHeadTD
      // Tier 2: the peer's TD as is.
      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(BigInt("174823400000000"), BigInt(0))
      )
      headTD shouldBe MordorHeadTD
      // Tier 3: no write, and no 30-minute retry either: there is nothing to calibrate.
      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))
      )
      headTD shouldBe MordorHeadTD
      blockchainReader.getChainWeightByHash(chain(1).hash).get.totalDifficulty.value shouldBe
        MordorHeadTD - MordorDifficulty
      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectNoMessage(200.millis)
    )

  it should "never rewrite the genesis weight while genesis is the best block" taggedAs (UnitTest, SyncTest) in
    (new RegularSyncSetup:
      val genesis: BlockHeader = Fixtures.Blocks.Genesis.header
      blockchainWriter.save(
        Block(genesis, BlockBody(Nil, Nil)),
        Seq.empty,
        ChainWeight.totalDifficultyOnly(genesis.difficulty.value),
        saveAsBestBlock = true
      )
      drainRegistration()

      // A fresh full-sync node 30 s in, with only an ETH68 STATUS seen: before #1654, Tier 2 stored the network's TD as
      // the genesis weight, and every block imported afterwards carried it.
      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(BigInt("24000000000000000000000"), BigInt(0))
      )

      blockchainReader.getChainWeightByHash(genesis.hash).get.totalDifficulty.value shouldBe genesis.difficulty.value
    )

  it should "derive the head weight from its parent's instead of writing a peer estimate" taggedAs (
    UnitTest,
    SyncTest
  ) in
    (new RegularSyncSetup:
      val chain: Vector[BlockHeader] =
        saveLinkedChain(BlockHash(fakeMissingHash), MordorPivot, MordorHeadTD - MordorDifficulty * 2, length = 3)
      val head: BlockHeader = chain.last
      // An earlier calibration overwrote the head (the issue's `after=` value), and no block was imported since.
      blockchainWriter
        .storeChainWeight(head.hash, ChainWeight.totalDifficultyOnly(BigInt("174823332424315")))
        .commit()
      drainRegistration()

      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(BigInt("174823400000000"), MordorPivot + 7)
      )

      blockchainReader.getChainWeightByHash(head.hash).get.totalDifficulty.value shouldBe MordorHeadTD
    )

  it should "still write an estimate when the head's parent has no stored weight (pivot not backfilled)" taggedAs (
    UnitTest,
    SyncTest
  ) in
    (new RegularSyncSetup:
      val pivot: BigInt = BigInt(24720000)
      // BLOCK_NUMBER_PROXY: SNAP finalization's last resort, `td = pivot`.
      val chain: Vector[BlockHeader] = saveLinkedChain(BlockHash(fakeMissingHash), pivot, pivot, length = 1)
      drainRegistration()

      val peerTD: BigInt = BigInt("24000000000000000000000")
      val peerMaxBlock: BigInt = BigInt(25000000)
      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(peerTD, peerMaxBlock)
      )

      blockchainReader.getChainWeightByHash(chain.last.hash).get.totalDifficulty.value shouldBe
        peerTD * pivot / peerMaxBlock
    )

  it should "still write an estimate on a chain whose links hold but whose base is a block-number proxy" taggedAs (
    UnitTest,
    SyncTest
  ) in
    (new RegularSyncSetup:
      // P stores `td = P`, and P+1 was imported on top: its link holds, but the parent's weight is below the floor.
      val chain: Vector[BlockHeader] =
        saveLinkedChain(BlockHash(fakeMissingHash), MordorPivot, MordorPivot, length = 2)
      val head: BlockHeader = chain.last
      blockchainReader.getChainWeightByHash(head.hash).get.totalDifficulty.value shouldBe
        MordorPivot + MordorDifficulty
      drainRegistration()

      val peerTD: BigInt = BigInt("174823400000000")
      val peerMaxBlock: BigInt = MordorPivot + 11
      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(peerTD, peerMaxBlock)
      )

      blockchainReader.getChainWeightByHash(head.hash).get.totalDifficulty.value shouldBe
        peerTD * head.number.value / peerMaxBlock
    )

  it should "find a Mordor-scale anchor in Tier 3, which the 10^13-per-block floor never could" taggedAs (
    UnitTest,
    SyncTest
  ) in
    (new RegularSyncSetup:
      // Anchor A = 15,000,000 holds a real Mordor-scale TD (1.748e14 < 15M x 10^13 = 1.5e20). The three headers above it
      // carry block-number proxies, so the walk must pass them and stop at A.
      val anchorTD: BigInt = MordorHeadTD - MordorDifficulty * 3
      val chain: Vector[BlockHeader] =
        saveLinkedChain(BlockHash(fakeMissingHash), MordorPivot, anchorTD, length = 4)
      chain.tail.foreach { h =>
        blockchainWriter.storeChainWeight(h.hash, ChainWeight.totalDifficultyOnly(h.number.value)).commit()
      }
      val head: BlockHeader = chain.last
      drainRegistration()

      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))
      )

      blockchainReader.getChainWeightByHash(head.hash).get.totalDifficulty.value shouldBe MordorHeadTD
      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectNoMessage(200.millis)
    )

  // ─── #1654 on a flat PoS chain (difficulty 0 after the merge) ─────────────
  //
  // Post-merge every block adds 0, so a block's weight equals its parent's: the frozen terminal TD. These pin at the
  // handler what HeadChainWeightSpec pins at `classify` (beacon review).

  it should "write nothing on a post-merge head above a SNAP pivot whose TD was a peer-interpolated estimate" taggedAs (
    UnitTest,
    SyncTest
  ) in
    (new RegularSyncSetup:
      // Pivot P stored PEER_INTERPOLATED_TD (plausible, but a guess); P+1..P+3 were imported on top at difficulty 0, so
      // each stores its parent's weight unchanged. The base is estimated; every link holds.
      val pivotEstimate: BigInt = SepoliaFrozenTD * PoSPivot / (PoSPivot + 100)
      val chain: Vector[BlockHeader] =
        saveLinkedChain(BlockHash(fakeMissingHash), PoSPivot, pivotEstimate, length = 4, difficulty = BigInt(0))
      def weights: Vector[BigInt] =
        chain.map(h => blockchainReader.getChainWeightByHash(h.hash).get.totalDifficulty.value)
      weights shouldBe Vector.fill(4)(pivotEstimate)
      drainRegistration()

      // Tier 1: before #1654 this wrote frozenTD * (P+3) / (P+53), below the frozen TD the parent carries.
      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(SepoliaFrozenTD, PoSPivot + 53)
      )
      // Tier 2 and Tier 3 (ETH68 STATUS only; pure ETH69).
      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(SepoliaFrozenTD, BigInt(0))
      )
      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0))
      )

      weights shouldBe Vector.fill(4)(pivotEstimate)
      testScheduler.timePasses(30.minutes)
      networkPeerManager.expectNoMessage(200.millis)
    )

  it should "still run the estimate path when the post-merge head is the SNAP pivot itself (no parent weight)" taggedAs (
    UnitTest,
    SyncTest
  ) in
    (new RegularSyncSetup:
      val pivotEstimate: BigInt = SepoliaFrozenTD * PoSPivot / (PoSPivot + 100)
      val chain: Vector[BlockHeader] =
        saveLinkedChain(BlockHash(fakeMissingHash), PoSPivot, pivotEstimate, length = 1, difficulty = BigInt(0))
      drainRegistration()

      val peerMaxBlock: BigInt = PoSPivot + 53
      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(SepoliaFrozenTD, peerMaxBlock)
      )

      // Unchanged from before #1654: Tier 1's linear interpolation.
      blockchainReader.getChainWeightByHash(chain.head.hash).get.totalDifficulty.value shouldBe
        SepoliaFrozenTD * PoSPivot / peerMaxBlock
    )

  // ─── #1654: the chain-weight marker, and the seed as a base anchor (H6(2)) ─

  it should "write nothing while the chain-weight marker stands, even over a head with no exact weight" taggedAs (
    UnitTest,
    SyncTest
  ) in
    (new RegularSyncSetup:
      setupBestBlock(blockNum = BigInt(24720000))
      // The marker as stored: key `chain.weight.invariant`, value "1".
      storagesInstance.storages.appStateStorage.put("chain.weight.invariant", "1").commit()
      drainRegistration()

      val head: BlockHash = blockchainReader.getBestBlockHeader.get.hash
      val before: Option[ChainWeight] = blockchainReader.getChainWeightByHash(head)
      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.CalibrateChainWeightFromPeer(BigInt("24000000000000000000000"), BigInt(25000000))
      )
      blockchainReader.getChainWeightByHash(head) shouldBe before
    )

  "SyncController -Dfukuii.seed-chain-weights" should
    "re-derive every block above a seed at a base, a block whose parent has no weight" taggedAs (UnitTest, SyncTest) in
    (new RegularSyncSetup:
      // A SNAP pivot whose headers below are not in: its parent has no weight, and four blocks inherit its estimate.
      val chain: Vector[BlockHeader] =
        saveLinkedChain(BlockHash(fakeMissingHash), MordorPivot, MordorHeadTD / 2, length = 5)
      withSeed(s"${hex(chain.head.hash)}:$MordorHeadTD")(drainRegistration())

      chain.zipWithIndex.foreach { (h, i) =>
        blockchainReader.getChainWeightByHash(h.hash).map(_.totalDifficulty.value) shouldBe
          Some(MordorHeadTD + MordorDifficulty * i)
      }
    )

  it should "refuse a seed over a parent with a plausible weight, and leave every weight as it was" taggedAs (
    UnitTest,
    SyncTest
  ) in
    (new RegularSyncSetup:
      val chain: Vector[BlockHeader] =
        saveLinkedChain(BlockHash(fakeMissingHash), MordorPivot, MordorHeadTD, length = 3)
      val before: Vector[Option[ChainWeight]] = chain.map(h => blockchainReader.getChainWeightByHash(h.hash))
      // A head seed: the head's parent has a weight derived from the chain below, so a seed here is a jump.
      withSeed(s"${hex(chain.last.hash)}:${MordorHeadTD * 2}")(drainRegistration())

      chain.map(h => blockchainReader.getChainWeightByHash(h.hash)) shouldBe before
    )

  private def withSeed[A](spec: String)(body: => A): A =
    System.setProperty("fukuii.seed-chain-weights", spec)
    try body
    finally System.clearProperty("fukuii.seed-chain-weights")

  private def hex(hash: BlockHash): String = com.chipprbots.ethereum.utils.Hex.toHexString(hash.value.toArray)

  // ─── Base test setup ──────────────────────────────────────────────────────

  // Sepolia: the TD frozen at the merge, and a post-merge SNAP pivot height.
  private val SepoliaFrozenTD: BigInt = BigInt("17000018015853232")
  private val PoSPivot: BigInt = BigInt(9500000)

  private val MordorPivot: BigInt = BigInt(15000000)
  private val MordorDifficulty: BigInt = BigInt(9000000)
  private val MordorHeadTD: BigInt = BigInt("174823251167868")

  trait RegularSyncSetup extends EphemBlockchainTestSetup with TestSyncConfig with TestSyncPeers:

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
    override lazy val mining: TestMining = buildTestMining().withValidators(validators)

    override def defaultSyncConfig: SyncConfig = super.defaultSyncConfig.copy(
      doSnapSync = false,
      // Long intervals prevent periodic GetHandshakedPeersCmd / block-check messages from
      // appearing in networkPeerManager's queue when timePasses(30.minutes) advances the
      // ExplicitlyTriggeredScheduler — those would arrive before CalibrateChainWeightNowCmd.
      // 4 hours > the longest test window (T4.1 / T8.2 at ~60 minutes).
      peersScanInterval = 4.hours,
      checkForNewBlockInterval = 4.hours,
      peerResponseTimeout = 2.seconds,
      blacklistDuration = 1.second
    )

    // SyncController is Pekko Typed (Group ROOT). Spawn through PropsAdapter so the Classic TestActorRef machinery
    // and `someTimePasses()`/ExplicitlyTriggeredScheduler timing still work.
    lazy val blockTopic: org.apache.pekko.actor.typed.ActorRef[
      org.apache.pekko.actor.typed.pubsub.Topic.Command[com.chipprbots.ethereum.jsonrpc.NewBlockImported]
    ] = system.spawn(
      org.apache.pekko.actor.typed.pubsub.Topic[com.chipprbots.ethereum.jsonrpc.NewBlockImported](
        "block-imported-topic"
      ),
      "block-imported-topic"
    )

    lazy val syncController: TestActorRef[Nothing] = TestActorRef(
      org.apache.pekko.actor.typed.scaladsl.adapter.PropsAdapter(
        SyncController(
          blockchain,
          blockchainReader,
          blockchainWriter,
          storagesInstance.storages.appStateStorage,
          storagesInstance.storages.evmCodeStorage,
          storagesInstance.storages.stateStorage,
          storagesInstance.storages.flatSlotStorage,
          storagesInstance.storages.fastSyncStateStorage,
          consensusAdapter,
          validators,
          peerMessageBus.ref,
          pendingTransactionsManager.ref
            .toTyped[com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command],
          blockTopic,
          ommersPool.ref,
          networkPeerManager.ref,
          blacklist,
          syncConfig,
          this,
          externalSchedulerOpt = Some(system.scheduler),
          // #1654: these fixtures store weights that do not follow from genesis; the pass would repair them.
          chainWeightReconcileOn = false
        )
      )
    )

    // Store genesis so blockchainReader.genesisHeader works (needed for calibration plausibility gate)
    blockchainWriter.storeBlockHeader(Fixtures.Blocks.Genesis.header).commit()
    blockchainWriter
      .storeChainWeight(
        Fixtures.Blocks.Genesis.header.hash,
        ChainWeight.totalDifficultyOnly(Fixtures.Blocks.Genesis.header.difficulty.value)
      )
      .commit()
    blockchainWriter.storeChainWeight(Fixtures.Blocks.Genesis.header.parentHash, ChainWeight.zero).commit()

    // Fake parentHash used in T3.4 (a header that's never stored in DB)
    val fakeMissingHash: ByteString = org.apache.pekko.util.ByteString(
      Array.fill(32)(0xff.toByte)
    )

    /** Start SyncController and kick into regular sync mode. */
    def startSync(): Unit = syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    /** Start sync and drain all startup messages sent to networkPeerManager.
      *
      * startRegularSync() triggers several startup messages to networkPeerManager:
      *   1. RegisterChainWeightCalibrationTargetCmd — sent synchronously 2. GetHandshakedPeersCmd × N (T+0) — each
      *      PeerListSupportNg actor contributes one 3. CalibrateChainWeightNowCmd (T+30s) — startup timed calibration
      *      from SyncController
      *
      * fishForMessage skips GetHandshakedPeersCmd until CalibrateChainWeightNowCmd arrives. After this returns the
      * probe is clean; subsequent expectMsg calls test only the scenario.
      */
    def drainRegistration(): Unit =
      startSync()
      networkPeerManager.expectMsgClass(classOf[RegisterChainWeightCalibrationTargetCmd])
      testScheduler.timePasses(31.seconds)
      networkPeerManager.fishForMessage(3.seconds) {
        case CalibrateChainWeightNowCmd => true // consumed; done
        case _: GetHandshakedPeersCmd   => false // skip — periodic peer-list poll
      }

    /** Store a best block with a given block number so getBestBlock/getBestBlockHeader succeed. */
    def setupBestBlock(blockNum: BigInt): Unit =
      val hdr = Fixtures.Blocks.Genesis.header.copy(number = BlockNumber(blockNum))
      val blk = Block(hdr, BlockBody(Nil, Nil))
      blockchainWriter.save(blk, Seq.empty, ChainWeight.totalDifficultyOnly(BigInt(1)), saveAsBestBlock = true)

    /** Store a header as the best block without a full block body. */
    def setBestBlockHeader(hdr: BlockHeader): Unit =
      blockchainWriter.storeBlockHeader(hdr).commit()
      storagesInstance.storages.appStateStorage
        .putBestBlockInfo(BlockInfo(hdr.hash.value, hdr.number.value))
        .commit()

    /** Build an anchor chain: `length` headers starting at `startNum`, each pointing to the previous via parentHash.
      * The first header's parentHash is genesis.hash. Returns headers in ascending order: [h_startNum, h_(startNum+1),
      * ..., h_(startNum+length-1)].
      */
    def buildParentHashChain(startNum: Int, length: Int): Vector[BlockHeader] =
      var prev = Fixtures.Blocks.Genesis.header
      val buf = scala.collection.mutable.ArrayBuffer.empty[BlockHeader]
      for i <- 0 until length do
        val n = startNum + i
        val h = Fixtures.Blocks.Genesis.header.copy(
          number = BlockNumber(n),
          parentHash = prev.hash,
          // vary a field to ensure a unique hash per block (nonce distinguishes them)
          nonce = org.apache.pekko.util.ByteString(Array.fill(8)(n.toByte))
        )
        blockchainWriter.storeBlockHeader(h).commit()
        buf += h
        prev = h
      buf.toVector

    /** #1654: save `length` blocks (header + empty body) numbered from `startNum`, the first a child of `parentHash`,
      * each with `difficulty` (Mordor-scale by default; 0 for a post-merge chain). The first stores `firstWeight`; each
      * later one stores its parent's weight plus its difficulty, as block import does. The last becomes the best block.
      * Returns them in ascending order.
      */
    def saveLinkedChain(
        parentHash: BlockHash,
        startNum: BigInt,
        firstWeight: BigInt,
        length: Int,
        difficulty: BigInt = MordorDifficulty
    ): Vector[BlockHeader] =
      val buf = scala.collection.mutable.ArrayBuffer.empty[BlockHeader]
      var parent = parentHash
      var weight = ChainWeight.totalDifficultyOnly(firstWeight)
      for i <- 0 until length do
        val n = startNum + i
        val h = Fixtures.Blocks.Genesis.header.copy(
          number = BlockNumber(n),
          parentHash = parent,
          difficulty = Difficulty(difficulty),
          extraData = ByteString(n.toByteArray)
        )
        if i > 0 then weight = weight.increase(h)
        blockchainWriter.save(Block(h, BlockBody(Nil, Nil)), Seq.empty, weight, saveAsBestBlock = i == length - 1)
        buf += h
        parent = h.hash
      buf.toVector

    /** Convenience: build anchor chain and configure anchor TD at the first block. */
    def setupAnchorChain(bestBlockNum: Int, anchorNum: Int, anchorTD: BigInt): Unit =
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

    def cleanup(): Unit = Await.result(system.terminate(), 10.seconds)
// scalastyle:on magic.number
