package com.chipprbots.ethereum.ledger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.ForkTimestamps

/** The fork schedule [[EestBlockchainReplay.forkTimestamps]] gives each execution-specs fixture `network` name. BPO2
  * has two children in execution-specs' fork tree: Amsterdam, and BPO3 -> BPO4 (tests@v21.0.0), which has no Amsterdam.
  * B-443.
  */
class EestNetworkScheduleSpec extends AnyFlatSpec with Matchers:

  private val throughBpo2 = ForkTimestamps(
    shanghaiTimestamp = Some(0L),
    cancunTimestamp = Some(0L),
    pragueTimestamp = Some(0L),
    osakaTimestamp = Some(0L),
    bpo1Timestamp = Some(0L),
    bpo2Timestamp = Some(0L)
  )

  "EestBlockchainReplay.forkTimestamps" should "map the BPO3 and BPO4 transition networks" taggedAs (UnitTest) in {
    EestBlockchainReplay.forkTimestamps("BPO2ToBPO3AtTime15k") shouldBe
      Some(throughBpo2.copy(bpo3Timestamp = Some(15000L)))
    EestBlockchainReplay.forkTimestamps("BPO3ToBPO4AtTime15k") shouldBe
      Some(throughBpo2.copy(bpo3Timestamp = Some(0L), bpo4Timestamp = Some(15000L)))
    EestBlockchainReplay.forkTimestamps("BPO3") shouldBe Some(throughBpo2.copy(bpo3Timestamp = Some(0L)))
    EestBlockchainReplay.forkTimestamps("BPO4") shouldBe
      Some(throughBpo2.copy(bpo3Timestamp = Some(0L), bpo4Timestamp = Some(0L)))
  }

  it should "keep the Amsterdam networks off the BPO3 line" taggedAs (UnitTest) in {
    EestBlockchainReplay.forkTimestamps("Amsterdam") shouldBe Some(throughBpo2.copy(amsterdamTimestamp = Some(0L)))
    EestBlockchainReplay.forkTimestamps("BPO2ToAmsterdamAtTime15k") shouldBe
      Some(throughBpo2.copy(amsterdamTimestamp = Some(15000L)))
    EestBlockchainReplay.forkTimestamps("Paris") shouldBe Some(ForkTimestamps())
    EestBlockchainReplay.forkTimestamps("Osaka") shouldBe
      Some(throughBpo2.copy(bpo1Timestamp = None, bpo2Timestamp = None))
  }

  it should "refuse transitions that skip a fork or cross branches" taggedAs (UnitTest) in {
    EestBlockchainReplay.forkTimestamps("BPO2ToBPO4AtTime15k") shouldBe None
    EestBlockchainReplay.forkTimestamps("AmsterdamToBPO3AtTime15k") shouldBe None
    EestBlockchainReplay.forkTimestamps("BPO5") shouldBe None
  }
