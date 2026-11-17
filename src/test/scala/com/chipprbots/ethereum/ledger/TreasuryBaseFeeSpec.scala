package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.Mocks.MockVM
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

/** ECIP-1111: Verify treasury receives baseFee * gasUsed credit post-Olympia. */
class TreasuryBaseFeeSpec extends AnyFlatSpec with Matchers with MockFactory:

  trait TestSetup extends EphemBlockchainTestSetup:
    override lazy val vm: VMImpl = new MockVM()

    val treasuryAddr: Address = Address(0xcdcdcd)
    val minerAddr: Address = Address(0xababab)
    val olympiaBlock: BigInt = 100

    val baseConfig: BlockchainConfig = Config.blockchains.blockchainConfig
    implicit override lazy val blockchainConfig: BlockchainConfig = baseConfig
      .copy(
        treasuryAddress = treasuryAddr,
        forkBlockNumbers = baseConfig.forkBlockNumbers.copy(
          olympiaBlockNumber = olympiaBlock
        )
      )

    val worldState: InMemoryWorldStateProxy = InMemoryWorldStateProxy(
      storagesInstance.storages.evmCodeStorage,
      blockchain.getBackingMptStorage(-1),
      (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash.value),
      UInt256.Zero,
      ByteString(MerklePatriciaTrie.EmptyRootHash),
      noEmptyAccounts = false,
      ethCompatibleStorage = true
    )
      .saveAccount(treasuryAddr, Account(balance = 0))
      .saveAccount(minerAddr, Account(balance = 0))

    def makeBlock(
        number: BigInt,
        gasUsed: BigInt,
        baseFee: Option[BigInt] = None,
        miner: Address = minerAddr
    ): Block =
      val extraFields = baseFee match
        case Some(fee) => HefPostOlympia(fee)
        case None      => HefEmpty
      Block(
        header = Fixtures.Blocks.Genesis.header.copy(
          beneficiary = miner.bytes,
          number = BlockNumber(number),
          gasUsed = GasAmount(gasUsed),
          extraFields = extraFields
        ),
        body = BlockBody(Nil, Nil)
      )

  "ECIP-1111 treasury" should "credit baseFee * gasUsed to treasury post-Olympia" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in new TestSetup:
    val baseFee: BigInt = BigInt(1000000000) // 1 gwei
    val gasUsed: BigInt = BigInt(21000)
    val block: Block = makeBlock(olympiaBlock, gasUsed, Some(baseFee))

    val treasuryBalBefore: UInt256 = worldState.getGuaranteedAccount(treasuryAddr).balance
    val afterWorld: InMemoryWorldStateProxy = mining.blockPreparator.payBlockReward(block, worldState)
    val treasuryBalAfter: UInt256 = afterWorld.getGuaranteedAccount(treasuryAddr).balance

    val expectedCredit: BigInt = baseFee * gasUsed // 21_000_000_000_000
    // Post-Olympia: treasury receives only baseFee * gasUsed (no 80/20 block reward split)
    (treasuryBalAfter - treasuryBalBefore) shouldBe UInt256(expectedCredit)

  it should "not credit baseFee to treasury pre-Olympia" taggedAs (OlympiaTest, ConsensusTest) in new TestSetup:
    val block: Block = makeBlock(olympiaBlock - 1, gasUsed = 21000)

    val treasuryBalBefore: UInt256 = worldState.getGuaranteedAccount(treasuryAddr).balance
    val afterWorld: InMemoryWorldStateProxy = mining.blockPreparator.payBlockReward(block, worldState)
    val treasuryBalAfter: UInt256 = afterWorld.getGuaranteedAccount(treasuryAddr).balance

    // Pre-Olympia: treasury receives nothing (no baseFee redirect)
    (treasuryBalAfter - treasuryBalBefore) shouldBe UInt256.Zero

  it should "not credit baseFee when gasUsed is zero" taggedAs (OlympiaTest, ConsensusTest) in new TestSetup:
    val baseFee: BigInt = BigInt(1000000000)
    val block: Block = makeBlock(olympiaBlock, gasUsed = 0, Some(baseFee))

    val treasuryBalBefore: UInt256 = worldState.getGuaranteedAccount(treasuryAddr).balance
    val afterWorld: InMemoryWorldStateProxy = mining.blockPreparator.payBlockReward(block, worldState)
    val treasuryBalAfter: UInt256 = afterWorld.getGuaranteedAccount(treasuryAddr).balance

    // baseFee * 0 = 0, treasury receives nothing
    (treasuryBalAfter - treasuryBalBefore) shouldBe UInt256.Zero

  it should "fail loudly (OLYMPIA SAFETY) when treasury address is zero post-Olympia" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in new TestSetup:
    override val treasuryAddr: Address = Address(0)

    implicit override lazy val blockchainConfig: BlockchainConfig = baseConfig
      .copy(
        treasuryAddress = treasuryAddr,
        forkBlockNumbers = baseConfig.forkBlockNumbers.copy(
          olympiaBlockNumber = olympiaBlock
        )
      )

    val baseFee: BigInt = BigInt(1000000000)
    val block: Block = makeBlock(olympiaBlock, gasUsed = 21000, Some(baseFee))

    // ECIP-1112 OLYMPIA SAFETY (BlockRewards.creditBaseFeeToTreasury): a zero treasury
    // address post-Olympia is a misconfiguration — the base fee would otherwise be silently
    // mis-credited. The guard fails loudly instead of burning, so payBlockReward must throw.
    val ex: IllegalStateException = intercept[IllegalStateException] {
      mining.blockPreparator.payBlockReward(block, worldState)
    }
    ex.getMessage should include("OLYMPIA SAFETY")
