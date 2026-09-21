package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto.generateKeyPair
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.NetworkType
import com.chipprbots.ethereum.vm.EvmConfig

/** T036 — ECIP-1111's treasury credit, asserted inert under Amsterdam rather than argued inert.
  *
  * `BlockPreparator.creditBaseFeeToTreasury` computes `baseFee * blockHeader.gasUsed`. Under EIP-8037 that header field
  * stops being a total and becomes `max(block_execution_gas_used, block_state_gas_used)`, so on an Amsterdam block the
  * treasury would be credited against the bottleneck dimension rather than the gas the senders actually paid. ETC never
  * activates Amsterdam, so this cannot happen — but "cannot happen" is exactly the reasoning that let the EIP-1559
  * elasticity gap through, so it gets assertions instead.
  *
  * Three of them, at the three points where the argument could break:
  *   1. the ETC config never turns the fork on; 2. with the fork off, the two dimensions collapse so `gasUsed` IS the
  *      total; 3. the credit that lands in the treasury equals `baseFee x` the receipt sum, which is what ECIP-1111
  *      means.
  */
// scalastyle:off magic.number
class AmsterdamTreasuryInertnessSpec extends AnyFlatSpec with Matchers:

  trait TestSetup extends EphemBlockchainTestSetup:
    val treasuryAddr: Address = Address(0xcdcdcd)
    val minerAddr: Address = Address(0xababab)
    val olympiaBlock: BigInt = 10

    private val baseConfig: BlockchainConfig = Config.blockchains.blockchainConfig

    /** A live ETC-family config: PoW forks by block number, no timestamp forks at all. */
    implicit override lazy val blockchainConfig: BlockchainConfig = baseConfig.copy(
      networkType = NetworkType.ETC,
      treasuryAddress = treasuryAddr,
      forkBlockNumbers = baseConfig.forkBlockNumbers.copy(olympiaBlockNumber = olympiaBlock)
    )

    val senderKeyPair = generateKeyPair(secureRandom)
    val senderAddress: Address = Address(senderKeyPair)
    val recipient: Address = Address(0x515151)

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
      .saveAccount(recipient, Account(balance = UInt256(1000)))
      .saveAccount(senderAddress, Account(nonce = UInt256(0), balance = UInt256(BigInt("1000000000000000000"))))

    val baseFee: BigInt = BigInt(1000000000)

    val etcHeader: BlockHeader = Fixtures.Blocks.Genesis.header.copy(
      beneficiary = minerAddr.bytes,
      number = BlockNumber(olympiaBlock + 5),
      gasLimit = GasAmount(8_000_000),
      gasUsed = GasAmount.Zero,
      difficulty = Difficulty(1000),
      unixTimestamp = Timestamp(1_700_000_000L),
      extraFields = HefPostOlympia(baseFee)
    )

    def transfer(nonce: BigInt): SignedTransaction =
      SignedTransaction.sign(
        LegacyTransaction(
          nonce = nonce,
          gasPrice = GasPrice(baseFee + 1),
          gasLimit = GasAmount(21000),
          receivingAddress = recipient,
          value = 1,
          payload = ByteString.empty
        ),
        senderKeyPair,
        Some(blockchainConfig.chainId.value)
      )

  "the ETC fork schedule" should "never activate Amsterdam, at any timestamp the treasury path can see" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in new TestSetup:
    blockchainConfig.forkTimestamps.amsterdamTimestamp shouldBe None
    Seq(0L, 1_700_000_000L, Long.MaxValue).foreach { ts =>
      blockchainConfig.isAmsterdamTimestamp(Timestamp(ts)) shouldBe false
      EvmConfig.forBlock(etcHeader.number.value, Timestamp(ts), blockchainConfig).amsterdamEnabled shouldBe false
    }

  "an ETC block's header gasUsed" should "still be a TOTAL: the two dimensions collapse" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in new TestSetup:
    val result: BlockResult = mining.blockPreparator
      .executeTransactions(Seq(transfer(0), transfer(1)), worldState, etcHeader)
      .fold(err => fail(s"ETC block execution failed: ${err.reason}"), identity)

    // Nothing charges state gas without `amsterdamEnabled`, so the maximum degenerates to the sum.
    result.stateGasUsed shouldBe BigInt(0)
    result.executionGasUsed shouldBe BigInt(42000)
    result.gasUsed shouldBe BigInt(42000)
    result.receipts.last.cumulativeGasUsed shouldBe BigInt(42000)
    // The property ECIP-1111's arithmetic silently depends on:
    result.gasUsed shouldBe result.receipts.last.cumulativeGasUsed

  "the ECIP-1111 treasury credit" should "equal baseFee x the gas the senders actually paid" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in new TestSetup:
    val result: BlockResult = mining.blockPreparator
      .executeTransactions(Seq(transfer(0), transfer(1)), worldState, etcHeader)
      .fold(err => fail(s"ETC block execution failed: ${err.reason}"), identity)

    val sealedHeader: BlockHeader = etcHeader.copy(gasUsed = GasAmount(result.gasUsed))
    val block: Block = Block(sealedHeader, BlockBody(Nil, Nil))

    val before: UInt256 = worldState.getGuaranteedAccount(treasuryAddr).balance
    val after: UInt256 =
      mining.blockPreparator.payBlockReward(block, worldState).getGuaranteedAccount(treasuryAddr).balance

    (after - before) shouldBe UInt256(baseFee * 42000)
    // Stated the other way round, because this is the equivalence Amsterdam would break if it ever
    // reached ETC: crediting against the header equals crediting against the receipt total.
    (after - before) shouldBe UInt256(baseFee * result.receipts.last.cumulativeGasUsed)
