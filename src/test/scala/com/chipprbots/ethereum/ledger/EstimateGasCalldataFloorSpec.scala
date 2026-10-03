package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.validators.SignedTransactionError.TransactionNotEnoughGasForFloorError
import com.chipprbots.ethereum.consensus.validators.SignedTransactionError.TransactionNotEnoughGasForIntrinsicError
import com.chipprbots.ethereum.consensus.validators.SignedTransactionValid
import com.chipprbots.ethereum.consensus.validators.std.StdSignedTransactionValidator
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefEmpty
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.utils.NetworkType

/** WI-14 (#1430) and forge's review of #1438: `eth_estimateGas` answers the least gas limit that is VALID, so it starts
  * its search at `max(intrinsic, calldata floor)` wherever a calldata floor is a validity rule.
  *
  * The floor is charged after execution, not by the VM, and the search started at 21,000 on every fork. It settled on
  * the intrinsic cost, so for a transaction whose floor is above that it answered a gas limit every node rejects:
  *   - ETH Prague / Osaka, EIP-7623 (a validity rule since #1438): 37,000 against a 61,000 floor.
  *   - ETC Olympia, EIP-7623 under ECIP-1121 (core-geth's Olympia branch rejects sub-floor transactions too): the same.
  *   - ETH Amsterdam, EIP-7976 / EIP-7981: 31,000 against a 79,000 floor. Amsterdam also starts lower: EIP-2780 makes a
  *     self-transfer valid at 12,000, which a 21,000 start overstated.
  *
  * Each answer is checked against the chain, not only against a number: the block validator accepts the estimate and
  * rejects one gas less, and block execution at the estimate succeeds and charges exactly it. Where no floor rule
  * exists (ETH before Prague, ETC before Olympia) the search starts at 21,000 and answers what it did before.
  */
// scalastyle:off magic.number
class EstimateGasCalldataFloorSpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  /** An account that exists, so a value transfer to it creates nothing. */
  private val payee: Address = Address(Hex.decode("83c7e323d189f18725ac510004fdc2941f8c4a78"))

  private def nonZeros(n: Int): ByteString = ByteString(Array.fill[Byte](n)(0x01))

  /** The requests carry a generous gas limit; the search answers the least one at or below it. */
  private val Cap: BigInt = 1_000_000

  private class Ledger(config: BlockchainConfig) extends EphemBlockchainTestSetup:
    implicit override lazy val blockchainConfig: BlockchainConfig = config

    lazy val ledger: StxLedger =
      new StxLedger(
        blockchain,
        blockchainReader,
        storagesInstance.storages.evmCodeStorage,
        mining.blockPreparator,
        this
      )

    val world: InMemoryWorldStateProxy = InMemoryWorldStateProxy.persistState(
      InMemoryWorldStateProxy(
        storagesInstance.storages.evmCodeStorage,
        blockchain.getBackingMptStorage(-1),
        (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash.value),
        UInt256.Zero,
        ByteString(MerklePatriciaTrie.EmptyRootHash),
        noEmptyAccounts = true,
        ethCompatibleStorage = true
      ).saveAccount(senderAddress, Account(nonce = UInt256(0), balance = UInt256(BigInt(10).pow(24))))
        .saveAccount(payee, Account(nonce = UInt256(0), balance = UInt256(1)))
    )

    def estimate(stx: SignedTransaction, header: BlockHeader): BigInt =
      ledger.binarySearchGasEstimation(SignedTransactionWithSender(stx, senderAddress), header, Some(world))

    def lowerBound(stx: SignedTransaction, header: BlockHeader): BigInt =
      ledger.estimationLowerBound(SignedTransactionWithSender(stx, senderAddress), header)

    def execute(stx: SignedTransaction, header: BlockHeader): TxResult =
      mining.blockPreparator.executeTransaction(stx, senderAddress, header, world)

    def validate(stx: SignedTransaction, header: BlockHeader) =
      val sender = Account(nonce = UInt256(0), balance = UInt256(BigInt(10).pow(24)))
      val upfront = UInt256(stx.tx.gasLimit.value * 1_000_000_000 + stx.tx.value)
      StdSignedTransactionValidator.validate(stx, sender, header, upfront, 0)(config)

    /** The estimate is valid, one gas less is not (with `belowError`), and executing the estimate charges exactly it.
      */
    def assertTight(tx: BigInt => SignedTransaction, header: BlockHeader, expected: BigInt, belowError: Any) =
      estimate(tx(Cap), header) shouldBe expected
      validate(tx(expected), header) shouldBe Right(SignedTransactionValid)
      validate(tx(expected - 1), header) shouldBe Left(belowError)
      val executed = execute(tx(expected), header)
      executed.vmError shouldBe None
      executed.gasUsed shouldBe expected

  // ── ETH: the Amsterdam fixture schedule (Cancun 60, Prague 120, Osaka 180, Amsterdam 360) ───────────────────────

  private val atCancun = preAmsterdamHeader(46, 60)
  private val atPrague = preAmsterdamHeader(46, 120)
  private val atOsaka = preAmsterdamHeader(46, 300)
  private val atAmsterdam = amsterdamHeader(46, 460)

  /** 1,000 non-zero bytes, zero value, to an EOA. Before Amsterdam: intrinsic 21,000 + 16,000 = 37,000, EIP-7623 floor
    * 21,000 + 4,000 x 10 = 61,000. Amsterdam: intrinsic 12,000 + 3,000 + 16,000 = 31,000, EIP-7976 floor 12,000 + 3,000
    * + 64,000 = 79,000.
    */
  private def calldataCall(config: BlockchainConfig)(gasLimit: BigInt) =
    legacyTx(Some(payee), value = 0, gasLimit = gasLimit, payload = nonZeros(1000), config = config)

  private def selfTransfer(config: BlockchainConfig)(gasLimit: BigInt) =
    legacyTx(Some(senderAddress), value = 1, gasLimit = gasLimit, config = config)

  private def valueTransfer(config: BlockchainConfig)(gasLimit: BigInt) =
    legacyTx(Some(payee), value = 1, gasLimit = gasLimit, config = config)

  "eth_estimateGas on ETH Prague and Osaka" should "answer the EIP-7623 floor, not the intrinsic cost below it" taggedAs (
    UnitTest,
    StateTest
  ) in new Ledger(amsterdamConfig):
    for header <- Seq(atPrague, atOsaka) do
      lowerBound(calldataCall(amsterdamConfig)(Cap), header) shouldBe BigInt(61000)
      assertTight(calldataCall(amsterdamConfig), header, 61000, TransactionNotEnoughGasForFloorError(60999, 61000))

  it should "answer 21,000 for a plain value transfer, whose floor is its intrinsic cost" taggedAs (
    UnitTest,
    StateTest
  ) in new Ledger(amsterdamConfig):
    estimate(valueTransfer(amsterdamConfig)(Cap), atPrague) shouldBe BigInt(21000)

  "eth_estimateGas on ETH before Prague" should "start at 21,000 and answer the intrinsic cost, as before" taggedAs (
    UnitTest,
    StateTest
  ) in new Ledger(amsterdamConfig):
    lowerBound(calldataCall(amsterdamConfig)(Cap), atCancun) shouldBe BigInt(21000)
    estimate(calldataCall(amsterdamConfig)(Cap), atCancun) shouldBe BigInt(37000)
    validate(calldataCall(amsterdamConfig)(37000), atCancun) shouldBe Right(SignedTransactionValid)

  "eth_estimateGas on ETH Amsterdam" should "answer the EIP-7976 floor for a calldata-carrying call" taggedAs (
    UnitTest,
    StateTest
  ) in new Ledger(amsterdamConfig):
    lowerBound(calldataCall(amsterdamConfig)(Cap), atAmsterdam) shouldBe BigInt(79000)
    assertTight(calldataCall(amsterdamConfig), atAmsterdam, 79000, TransactionNotEnoughGasForFloorError(78999, 79000))

  it should "answer 12,000 for a self-transfer, its EIP-2780 cost" taggedAs (UnitTest, StateTest) in
    new Ledger(amsterdamConfig):
      assertTight(
        selfTransfer(amsterdamConfig),
        atAmsterdam,
        12000,
        TransactionNotEnoughGasForIntrinsicError(11999, 12000)
      )

  it should "answer 21,000 for a value transfer to an existing account" taggedAs (UnitTest, StateTest) in
    new Ledger(amsterdamConfig):
      // TX_BASE 12,000 + COLD_ACCOUNT_ACCESS 3,000 + TX_VALUE_COST 6,000.
      assertTight(
        valueTransfer(amsterdamConfig),
        atAmsterdam,
        21000,
        TransactionNotEnoughGasForIntrinsicError(20999, 21000)
      )

  it should "answer the cap when the cap is below the least valid gas limit, as it always has" taggedAs (
    UnitTest,
    StateTest
  ) in new Ledger(amsterdamConfig):
    ledger.binarySearchGasEstimation(
      SignedTransactionWithSender(calldataCall(amsterdamConfig)(50000), senderAddress),
      atAmsterdam,
      Some(world)
    ) shouldBe BigInt(50000)

  "eth_estimateGas on an ETH chain that schedules no Amsterdam" should "apply EIP-7623 past the Amsterdam time" taggedAs (
    UnitTest,
    StateTest
  ) in new Ledger(preAmsterdamConfig):
    estimate(selfTransfer(preAmsterdamConfig)(Cap), atAmsterdam) shouldBe BigInt(21000)
    estimate(calldataCall(preAmsterdamConfig)(Cap), atAmsterdam) shouldBe BigInt(61000)

  // ── ETC: Olympia at block 1,000 (ECIP-1121), Spiral at 900 ──────────────────────────────────────────────────────

  private val OlympiaBlock = BigInt(1000)

  private val etcOlympiaConfig: BlockchainConfig =
    setup.blockchainConfig
      .copy(networkType = NetworkType.ETC, forkTimestamps = ForkTimestamps())
      .withUpdatedForkBlocks(
        _.copy(
          frontierBlockNumber = 0,
          homesteadBlockNumber = 0,
          eip150BlockNumber = 0,
          eip155BlockNumber = 0,
          eip160BlockNumber = 0,
          eip161BlockNumber = 0,
          byzantiumBlockNumber = 0,
          constantinopleBlockNumber = 0,
          petersburgBlockNumber = 0,
          istanbulBlockNumber = 0,
          atlantisBlockNumber = 0,
          aghartaBlockNumber = 0,
          phoenixBlockNumber = 0,
          magnetoBlockNumber = 0,
          berlinBlockNumber = 0,
          mystiqueBlockNumber = 0,
          spiralBlockNumber = 900,
          olympiaBlockNumber = OlympiaBlock
        )
      )

  private def etcHeader(number: BigInt): BlockHeader =
    Fixtures.Blocks.ValidBlock.header.copy(
      number = BlockNumber(number),
      gasLimit = GasAmount(30_000_000),
      gasUsed = GasAmount.Zero,
      extraFields = if number >= OlympiaBlock then HefPostOlympia(BigInt(1)) else HefEmpty
    )

  "eth_estimateGas on ETC Olympia" should "answer the EIP-7623 floor, as the Olympia validity rule requires" taggedAs (
    UnitTest,
    StateTest,
    OlympiaTest
  ) in new Ledger(etcOlympiaConfig):
    lowerBound(calldataCall(etcOlympiaConfig)(Cap), etcHeader(OlympiaBlock)) shouldBe BigInt(61000)
    assertTight(
      calldataCall(etcOlympiaConfig),
      etcHeader(OlympiaBlock),
      61000,
      TransactionNotEnoughGasForFloorError(60999, 61000)
    )

  "eth_estimateGas on ETC before Olympia" should "start at 21,000 and answer the intrinsic cost, as before" taggedAs (
    UnitTest,
    StateTest,
    OlympiaTest
  ) in new Ledger(etcOlympiaConfig):
    lowerBound(calldataCall(etcOlympiaConfig)(Cap), etcHeader(OlympiaBlock - 1)) shouldBe BigInt(21000)
    estimate(calldataCall(etcOlympiaConfig)(Cap), etcHeader(OlympiaBlock - 1)) shouldBe BigInt(37000)
    validate(calldataCall(etcOlympiaConfig)(37000), etcHeader(OlympiaBlock - 1)) shouldBe Right(SignedTransactionValid)

  it should "start at 21,000 on the loaded ETC test chain, which has no Olympia block" taggedAs (
    UnitTest,
    StateTest,
    OlympiaTest
  ) in {
    val etc: BlockchainConfig = Config.blockchains.blockchainConfig
    new Ledger(etc):
      Seq(selfTransfer(etc)(Cap), calldataCall(etc)(Cap)).foreach { stx =>
        lowerBound(stx, etcHeader(BigInt(5_000_000)).copy(extraFields = HefEmpty)) shouldBe BigInt(21000)
      }
  }
