package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import com.typesafe.config.ConfigFactory
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto.keyPairFromPrvKey
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** #1727 PR 6 (B-132) pins block import's rewards: moving `BlockPreparator.payBlockReward`'s body into
  * [[BlockRewards]], so that eth_simulateV1 pays what import pays, must not move a balance or a state root that block
  * import produces.
  *
  * Each scenario imports one block through a real `BlockExecution` (`executeForProposer`, which runs
  * `executeBlockUntimed`, the code `executeAndValidateBlock` runs, without the checks against the header), on the
  * shipped ETC, Mordor and ETH chain configs. The block has a 21,000-gas transfer and, on proof-of-work, two ommers,
  * one and two blocks back, with their own miners. Olympia is not scheduled on any shipped chain, so its two scenarios
  * move the shipped ETC and Mordor configs' `olympia-block-number` and nothing else.
  *
  * The balances are worked by hand from the specs, not read from the code: ECIP-1017 (the era reward, 20% less every
  * 5,000,000 blocks on ETC and every 2,000,000 on Mordor; `era = (number - 1) / eraDuration`; 1/32 of the block reward
  * to the miner per ommer; an ommer's miner (8 - distance)/8 of it in era 0 and 1/32 after), ECIP-1111 (from Olympia,
  * `baseFee x gasUsed` to the treasury, the miner keeping the tip), and on ETH EIP-649 and EIP-1234 (5, 3 and 2 ETH,
  * one era) and nothing after the merge. The state roots were recorded by running this spec on the tree BEFORE the move
  * (staging 006844023), and it passes unchanged after.
  */
// scalastyle:off magic.number
class BlockRewardImportPinSpec extends AnyFlatSpec with Matchers:

  private val shippedConfigs = ConfigFactory.load()
  private def shipped(name: String): BlockchainConfig =
    BlockchainConfig.fromRawConfig(shippedConfigs.getConfig(s"fukuii.blockchains.$name"))

  private val etc: BlockchainConfig = shipped("etc")
  private val mordor: BlockchainConfig = shipped("mordor")
  private val eth: BlockchainConfig = shipped("eth")
  private val etcOlympia: BlockchainConfig = etc.withUpdatedForkBlocks(_.copy(olympiaBlockNumber = 23_000_000))
  private val mordorOlympia: BlockchainConfig = mordor.withUpdatedForkBlocks(_.copy(olympiaBlockNumber = 15_000_000))

  // hive's well-known funded key, 0xa94f5374fce5edbc8e2a8697c15331677e6ebf0b
  private val senderKeyPair: AsymmetricCipherKeyPair =
    keyPairFromPrvKey(Hex.decode("45a915e4d060149eb4365960e6a7a45f334393093061116b197e3240065ff2d8"))
  private val Sender: Address = Address(senderKeyPair)
  private val Recipient: Address = Address(0x5151)
  private val Miner: Address = Address(0xa11ce)
  private val OmmerMiner1: Address = Address(0x0e1)
  private val OmmerMiner2: Address = Address(0x0e2)

  private val Gwei = BigInt(1_000_000_000)
  private val SenderFunds = BigInt(10).pow(19)

  private class Chain(config: BlockchainConfig) extends EphemBlockchainTestSetup:
    implicit override lazy val blockchainConfig: BlockchainConfig = config

    override lazy val blockQueue: BlockQueue = BlockQueue(blockchainReader, SyncConfig(Config.config))

    override lazy val blockValidation: BlockValidation = new BlockValidation(
      mining.withValidators(com.chipprbots.ethereum.Mocks.MockValidatorsAlwaysSucceed),
      blockchainReader,
      blockQueue
    )

    lazy val exec: BlockExecution = new BlockExecution(
      blockchain,
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      blockValidation
    )

    /** Saves block `number`'s parent, whose state holds only the funded sender, and returns its header. */
    def saveParent(number: BigInt, difficulty: BigInt, extraFields: HeaderExtraFields): BlockHeader =
      val world = InMemoryWorldStateProxy(
        storagesInstance.storages.evmCodeStorage,
        blockchain.getBackingMptStorage(-1),
        (n: BigInt) => blockchainReader.getBlockHeaderByNumber(n).map(_.hash.value),
        config.accountStartNonce,
        ByteString(MerklePatriciaTrie.EmptyRootHash),
        noEmptyAccounts = false,
        ethCompatibleStorage = config.ethCompatibleStorage
      ).saveAccount(Sender, Account(nonce = config.accountStartNonce, balance = UInt256(SenderFunds)))
      val parent = Fixtures.Blocks.ValidBlock.header.copy(
        number = BlockNumber(number - 1),
        stateRoot = TrieRoot(InMemoryWorldStateProxy.persistState(world).stateRootHash),
        difficulty = Difficulty(difficulty),
        gasLimit = GasAmount(8_000_000),
        gasUsed = GasAmount.Zero,
        unixTimestamp = Timestamp(1_499_999_985L),
        extraFields = extraFields
      )
      blockchainWriter.save(Block(parent, BlockBody.empty), Nil, ChainWeight.zero, saveAsBestBlock = true)
      parent

  /** Imports block `number` (a transfer at `gasPrice`, and `ommers` as (miner, distance)) and renders what it paid. */
  private def importBlock(
      config: BlockchainConfig,
      number: BigInt,
      difficulty: BigInt,
      extraFields: HeaderExtraFields,
      gasPrice: BigInt,
      ommers: Seq[(Address, Int)]
  ): String =
    val chain = new Chain(config)
    given BlockchainConfig = config
    val parent = chain.saveParent(number, difficulty, extraFields)
    val transfer = SignedTransaction.sign(
      LegacyTransaction(
        nonce = config.accountStartNonce.toBigInt,
        gasPrice = GasPrice(gasPrice),
        gasLimit = GasAmount(21_000),
        receivingAddress = Recipient,
        value = Gwei,
        payload = ByteString.empty
      ),
      senderKeyPair,
      Some(config.chainId.value)
    )
    val header = parent.copy(
      parentHash = parent.hash,
      number = BlockNumber(number),
      beneficiary = Miner.bytes,
      gasUsed = GasAmount(21_000),
      unixTimestamp = Timestamp(1_500_000_000L)
    )
    val ommerHeaders = ommers.map { case (miner, distance) =>
      Fixtures.Blocks.ValidBlock.header.copy(number = BlockNumber(number - distance), beneficiary = miner.bytes)
    }
    val result = chain.exec
      .executeForProposer(Block(header, BlockBody(Seq(transfer), ommerHeaders)))
      .fold(e => fail(s"block $number failed: $e"), identity)
    val world = result.worldState
    def balance(address: Address): String = world.getAccount(address).fold("absent")(_.balance.toBigInt.toString)
    s"""receipts: ${result.receipts.map(_.cumulativeGasUsed).mkString(" ")}
       |miner: ${balance(Miner)}
       |ommer1: ${balance(OmmerMiner1)}
       |ommer2: ${balance(OmmerMiner2)}
       |treasury: ${balance(config.treasuryAddress)}
       |stateRoot: ${Hex.toHexString(world.stateRootHash.toArray)}""".stripMargin

  private val TwoOmmers = Seq(OmmerMiner1 -> 1, OmmerMiner2 -> 2)
  private val PowDifficulty = BigInt("14005986920576")

  // ---- ETC mainnet: ECIP-1017, 5,000,000-block eras ----

  "Block import on ETC" should "pay era 0's reward at block 5,000,000: 5 ETC, ommers 7/8 and 6/8 of it" taggedAs (
    UnitTest,
    StateTest
  ) in {
    // miner: 5 + 2 x 5/32 = 5.3125 ETC, plus the fee 21,000 x 1 gwei
    importBlock(etc, 5_000_000, PowDifficulty, HefEmpty, Gwei, TwoOmmers) shouldBe
      """receipts: 21000
        |miner: 5312521000000000000
        |ommer1: 4375000000000000000
        |ommer2: 3750000000000000000
        |treasury: absent
        |stateRoot: 3c2766f322f4a9c7db181d174bfe34a3d6eca24d3289ace7eae41339233597c1""".stripMargin
  }

  it should "pay era 1's reward at block 5,000,001: 4 ETC, ommers 1/32 of it" taggedAs (UnitTest, StateTest) in {
    importBlock(etc, 5_000_001, PowDifficulty, HefEmpty, Gwei, TwoOmmers) shouldBe
      """receipts: 21000
        |miner: 4250021000000000000
        |ommer1: 125000000000000000
        |ommer2: 125000000000000000
        |treasury: absent
        |stateRoot: aea4919ae9fbaba1886005b8ed166f0a6d7b80756aa85688237ec3a272a7f23a""".stripMargin
  }

  it should "pay era 2's reward at block 10,000,001: 3.2 ETC" taggedAs (UnitTest, StateTest) in {
    importBlock(etc, 10_000_001, PowDifficulty, HefEmpty, Gwei, TwoOmmers) shouldBe
      """receipts: 21000
        |miner: 3400021000000000000
        |ommer1: 100000000000000000
        |ommer2: 100000000000000000
        |treasury: absent
        |stateRoot: 0b7e0116092b896d1514da5152d038b1c0807f6fb74ad327d4af076aaf7ec7c5""".stripMargin
  }

  it should "pay era 4's reward at block 22,000,000: 2.048 ETC" taggedAs (UnitTest, StateTest) in {
    importBlock(etc, 22_000_000, PowDifficulty, HefEmpty, Gwei, TwoOmmers) shouldBe
      """receipts: 21000
        |miner: 2176021000000000000
        |ommer1: 64000000000000000
        |ommer2: 64000000000000000
        |treasury: absent
        |stateRoot: 02b91a7f43173706e38559365fa18840910d1a6527fcee940d5b48b9bbc4dcfc""".stripMargin
  }

  it should "credit the treasury first at Olympia (block 23,000,001, era 4), the miner keeping the tip" taggedAs (
    UnitTest,
    StateTest,
    OlympiaTest
  ) in {
    // base fee 1 gwei, gas price 2 gwei: treasury 21,000 x 1 gwei; miner 2.048 + 2 x 0.064 ETC + the 1 gwei tip
    importBlock(etcOlympia, 23_000_001, PowDifficulty, HefPostOlympia(Gwei), Gwei * 2, TwoOmmers) shouldBe
      """receipts: 21000
        |miner: 2176021000000000000
        |ommer1: 64000000000000000
        |ommer2: 64000000000000000
        |treasury: 21000000000000
        |stateRoot: 5d9e9dcddf3573a8ac0aad60a6b87122559ec25ff27b5b38d090f7a94ae2c024""".stripMargin
  }

  // ---- Mordor: ECIP-1017, 2,000,000-block eras ----

  "Block import on Mordor" should "pay era 0's reward at block 2,000,000" taggedAs (UnitTest, StateTest) in {
    importBlock(mordor, 2_000_000, PowDifficulty, HefEmpty, Gwei, TwoOmmers) shouldBe
      """receipts: 21000
        |miner: 5312521000000000000
        |ommer1: 4375000000000000000
        |ommer2: 3750000000000000000
        |treasury: absent
        |stateRoot: 3c2766f322f4a9c7db181d174bfe34a3d6eca24d3289ace7eae41339233597c1""".stripMargin
  }

  it should "pay era 1's reward at block 2,000,001" taggedAs (UnitTest, StateTest) in {
    importBlock(mordor, 2_000_001, PowDifficulty, HefEmpty, Gwei, TwoOmmers) shouldBe
      """receipts: 21000
        |miner: 4250021000000000000
        |ommer1: 125000000000000000
        |ommer2: 125000000000000000
        |treasury: absent
        |stateRoot: aea4919ae9fbaba1886005b8ed166f0a6d7b80756aa85688237ec3a272a7f23a""".stripMargin
  }

  it should "pay era 3's reward at block 6,000,001: 2.56 ETC" taggedAs (UnitTest, StateTest) in {
    importBlock(mordor, 6_000_001, PowDifficulty, HefEmpty, Gwei, TwoOmmers) shouldBe
      """receipts: 21000
        |miner: 2720021000000000000
        |ommer1: 80000000000000000
        |ommer2: 80000000000000000
        |treasury: absent
        |stateRoot: a618855380610f78182234ce3d7251d9331de8ce273ec3a4f37194084ceb99ca""".stripMargin
  }

  it should "credit the treasury first at Olympia (block 15,000,001, era 7: 1.048576 ETC)" taggedAs (
    UnitTest,
    StateTest,
    OlympiaTest
  ) in {
    importBlock(mordorOlympia, 15_000_001, PowDifficulty, HefPostOlympia(Gwei), Gwei * 2, TwoOmmers) shouldBe
      """receipts: 21000
        |miner: 1114133000000000000
        |ommer1: 32768000000000000
        |ommer2: 32768000000000000
        |treasury: 21000000000000
        |stateRoot: 91f6f395aa10adcc5b17d60b740f687d7474d096bcb39fcacb4259266edb1059""".stripMargin
  }

  // ---- ETH mainnet: one era; EIP-649, EIP-1234; nothing after the merge ----

  "Block import on ETH" should "pay 5 ETH before Byzantium (block 4,369,999)" taggedAs (UnitTest, StateTest) in {
    importBlock(eth, 4_369_999, PowDifficulty, HefEmpty, Gwei, TwoOmmers) shouldBe
      """receipts: 21000
        |miner: 5312521000000000000
        |ommer1: 4375000000000000000
        |ommer2: 3750000000000000000
        |treasury: absent
        |stateRoot: 3c2766f322f4a9c7db181d174bfe34a3d6eca24d3289ace7eae41339233597c1""".stripMargin
  }

  it should "pay 3 ETH from Byzantium (block 4,370,000)" taggedAs (UnitTest, StateTest) in {
    importBlock(eth, 4_370_000, PowDifficulty, HefEmpty, Gwei, TwoOmmers) shouldBe
      """receipts: 21000
        |miner: 3187521000000000000
        |ommer1: 2625000000000000000
        |ommer2: 2250000000000000000
        |treasury: absent
        |stateRoot: 0f4a7c057c87081daa3e5144742de4dfba3385fa72f9e41e05429b9c88cad35e""".stripMargin
  }

  it should "pay 2 ETH from Constantinople (block 7,280,000)" taggedAs (UnitTest, StateTest) in {
    importBlock(eth, 7_280_000, PowDifficulty, HefEmpty, Gwei, TwoOmmers) shouldBe
      """receipts: 21000
        |miner: 2125021000000000000
        |ommer1: 1750000000000000000
        |ommer2: 1500000000000000000
        |treasury: absent
        |stateRoot: e8cf18319f7c6775df5af5ea2d820c7543de349c87cb81130b39e24a3ec99ba6""".stripMargin
  }

  it should "pay 2 ETH after London and before the merge, burning the base fee (block 12,965,000)" taggedAs (
    UnitTest,
    StateTest
  ) in {
    // the treasury address is zero on ETH: no credit, and the 1 gwei base fee is burned; the miner keeps the tip
    importBlock(eth, 12_965_000, PowDifficulty, HefPostOlympia(Gwei), Gwei * 2, TwoOmmers) shouldBe
      """receipts: 21000
        |miner: 2125021000000000000
        |ommer1: 1750000000000000000
        |ommer2: 1500000000000000000
        |treasury: absent
        |stateRoot: c59322b72d8d64cfe43ea7e29b6a3d92c3ec51b68642e320615f2b1621c29572""".stripMargin
  }

  it should "pay no reward after the merge (block 15,537,394), the miner keeping the tip" taggedAs (
    UnitTest,
    StateTest
  ) in {
    importBlock(eth, 15_537_394, 0, HefPostOlympia(Gwei), Gwei * 2, Nil) shouldBe
      """receipts: 21000
        |miner: 21000000000000
        |ommer1: absent
        |ommer2: absent
        |treasury: absent
        |stateRoot: c748dc94551679d0347ed103e0b9a112d2c6c0215941ca935d0b6ce0391ee062""".stripMargin
  }
