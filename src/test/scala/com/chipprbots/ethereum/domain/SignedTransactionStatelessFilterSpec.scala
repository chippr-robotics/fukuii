package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.utils.NetworkType

/** Tests for the EIP-3860 initcode-cost correctness fix in
  * [[SignedTransactionWithSender.getStatelessValidTransactions]].
  *
  * The stateless pre-filter uses `EvmConfig.forBlock` to compute intrinsic gas. On ETH chains the correct overload is
  * the 3-arg form that applies timestamp-based fork overrides (including EIP-3860 initcode metering, active at
  * Shanghai). The 2-arg form resolves to `LondonConfigBuilder` on ETH (where `spiralBlockNumber > olympiaBlockNumber`)
  * and therefore sets `eip3860Enabled = false`, under-estimating intrinsic gas for contract-creation txs by
  * `ceil(initcode.length / 32) * 2` gas.
  *
  * Test structure:
  *   - Uses a config where `spiralBlockNumber > olympiaBlockNumber` (i.e., `etcForksDisabled = true`), which is the
  *     ETH-style fork schedule where the 2-arg path resolves to LondonConfigBuilder.
  *   - Constructs a contract-creation tx whose `gasLimit` is exactly the London intrinsic gas (no EIP-3860 cost):
  *     sufficient to pass a London-only check but insufficient to pass a Shanghai check.
  *   - Asserts that the tx is filtered OUT — proving the 3-arg (timestamp-aware) path is used.
  */
class SignedTransactionStatelessFilterSpec extends AnyFlatSpec with Matchers:

  // Base ETC config from test resources; all ETC-specific forks sit at 1e18 by default.
  private val etcConfig: BlockchainConfig = Config.blockchains.blockchainConfig

  // ETH-style fork schedule: Olympia = London block (6M, above etcMystique's 5M so Mystique fee
  // schedule is active), Spiral = never. This makes etcForksDisabled = (1e18 > 6M) = true, so
  // EvmConfig.forBlock(olympiaBlockNumber, config) uses LondonConfigBuilder (eip3860Enabled = false).
  private val ethConfig: BlockchainConfig = etcConfig
    .withUpdatedForkBlocks(
      _.copy(
        mystiqueBlockNumber = BigInt(5_000_000),
        olympiaBlockNumber = BigInt(6_000_000),
        spiralBlockNumber = BigInt("1000000000000000000")
      )
    )
    .copy(
      networkType = NetworkType.ETH,
      forkTimestamps = ForkTimestamps(shanghaiTimestamp = Some(0L)) // always activated
    )

  // 1024 non-zero bytes of initcode.  Non-zero matters: G_txdatanonzero = 16, G_txdatazero = 4.
  // Keeping it all non-zero gives a predictable data-cost calculation.
  private val initcode: ByteString = ByteString(Array.fill(1024)(0xff.toByte))

  // London intrinsic gas for this contract-creation tx (EIP-3860 disabled):
  //   G_transaction(21000) + G_txcreate(32000) + txDataNonZero(1024 * 16) + initCodeCost(0) = 69384
  private val LondonIntrinsicGas: BigInt = BigInt(69384)

  // Shanghai intrinsic gas (EIP-3860 enabled):
  //   69384 + ceil(1024 / 32) * 2 = 69384 + 64 = 69448
  private val ShanghaiIntrinsicGas: BigInt = BigInt(69448)

  // Dummy but structurally valid r/s; ECDSA recovery is not performed by the stateless filter.
  private val dummyR = ByteString(Hex.decode("f337e8ca3306c131eabb756aa3701ec7b00bef0d6cc21fbf6a6f291463d58baf"))
  private val dummyS = ByteString(Hex.decode("72216654137b4b58a4ece0a6df87aa1a4faf18ec4091839dd1c722fa9604fd09"))

  private def makeCreateTx(gasLimit: BigInt): SignedTransaction = SignedTransaction(
    tx = LegacyTransaction(
      nonce = 0,
      gasPrice = GasPrice(BigInt("1000000000")),
      gasLimit = GasAmount(gasLimit),
      receivingAddress = None, // contract creation
      value = BigInt(0),
      payload = initcode
    ),
    pointSign = 0x1b.toByte,
    signatureRandom = dummyR,
    signature = dummyS
  )

  it should "reject a contract-creation tx whose gasLimit covers London intrinsic gas but not Shanghai (EIP-3860)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    implicit val cfg: BlockchainConfig = ethConfig
    val tx = makeCreateTx(LondonIntrinsicGas)
    // Post-fix: timestamp-aware forBlock is used → eip3860Enabled = true → tx rejected
    SignedTransactionWithSender.getStatelessValidTransactions(Seq(tx)) shouldBe empty
  }

  it should "admit a contract-creation tx whose gasLimit meets the Shanghai intrinsic gas (EIP-3860 included)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    implicit val cfg: BlockchainConfig = ethConfig
    val tx = makeCreateTx(ShanghaiIntrinsicGas)
    SignedTransactionWithSender.getStatelessValidTransactions(Seq(tx)) should have size 1
  }

  it should "admit a non-creation tx unaffected by EIP-3860 (call tx with same payload)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    implicit val cfg: BlockchainConfig = ethConfig
    // Call tx: no G_txcreate, no initCodeCost — gasLimit covers data cost only
    val callGasLimit: BigInt = BigInt(21000) + BigInt(1024) * 16 // 37384
    val callTx = SignedTransaction(
      tx = LegacyTransaction(
        nonce = 0,
        gasPrice = GasPrice(BigInt("1000000000")),
        gasLimit = GasAmount(callGasLimit),
        receivingAddress = Some(Address(0xcafe)),
        value = BigInt(0),
        payload = initcode
      ),
      pointSign = 0x1b.toByte,
      signatureRandom = dummyR,
      signature = dummyS
    )
    SignedTransactionWithSender.getStatelessValidTransactions(Seq(callTx)) should have size 1
  }

  // ── §ETH-T6-B: EIP-2681 nonce overflow in stateless mempool filter ───────────
  //
  // getStatelessValidTransactions must reject nonces >= 2^64-1 before ECDSA recovery.
  // Applies to both ETC and ETH chains.

  private def makeCallTxWithNonce(n: BigInt): SignedTransaction =
    SignedTransaction(
      tx = LegacyTransaction(
        nonce = n,
        gasPrice = GasPrice(BigInt("1000000000")),
        gasLimit = GasAmount(21000),
        receivingAddress = Some(Address(0xcafe)),
        value = BigInt(0),
        payload = ByteString.empty
      ),
      pointSign = 0x1b.toByte,
      signatureRandom = dummyR,
      signature = dummyS
    )

  it should "admit tx with nonce == 2^64-2 in stateless filter (max valid, EIP-2681)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    implicit val cfg: BlockchainConfig = ethConfig
    SignedTransactionWithSender.getStatelessValidTransactions(
      Seq(makeCallTxWithNonce(BigInt(2).pow(64) - 2))
    ) should have size 1
  }

  it should "reject tx with nonce == 2^64-1 in stateless filter (overflow boundary, EIP-2681)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    implicit val cfg: BlockchainConfig = ethConfig
    SignedTransactionWithSender.getStatelessValidTransactions(
      Seq(makeCallTxWithNonce(BigInt(2).pow(64) - 1))
    ) shouldBe empty
  }

  it should "reject tx with nonce == 2^64 in stateless filter (above overflow boundary, EIP-2681)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    implicit val cfg: BlockchainConfig = ethConfig
    SignedTransactionWithSender.getStatelessValidTransactions(
      Seq(makeCallTxWithNonce(BigInt(2).pow(64)))
    ) shouldBe empty
  }

  it should "reject nonce overflow in stateless filter on ETC (same nonce semantics)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    implicit val cfg: BlockchainConfig = etcConfig
    SignedTransactionWithSender.getStatelessValidTransactions(
      Seq(makeCallTxWithNonce(BigInt(2).pow(64) - 1))
    ) shouldBe empty
  }

  // ── coversIntrinsicGas: exact without recovering the sender ────────────────────
  // The stateless filter used to recover every sender (ECDSA) before checking intrinsic gas, which kept the
  // SignedTransactionsFilterActor busy for seconds on hive's 2,000-tx LargeTxRequest. It now checks first with a
  // sender that is never tx.to and recovers only when that fails under Amsterdam. This pins that the answer is
  // unchanged: for every case it must equal gasLimit >= intrinsic gas computed with the TRUE sender.

  "coversIntrinsicGas" should "agree with the true-sender rule before and under Amsterdam" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    import com.chipprbots.ethereum.crypto
    import com.chipprbots.ethereum.vm.EvmConfig
    implicit val cfg: BlockchainConfig = ethConfig
    val keyPair = crypto.generateKeyPair(new java.security.SecureRandom())
    val sender = Address(keyPair)
    val other = Address(Hex.decode("00000000000000000000000000000000000000aa"))
    val preAmsterdam = EvmConfig.forBlock(BigInt(6_000_000), Timestamp(0L), ethConfig)
    val amsterdam = preAmsterdam.copy(amsterdamEnabled = true)

    def tx(to: Option[Address], value: BigInt, gasLimit: BigInt): SignedTransaction =
      SignedTransaction.sign(
        TransactionWithDynamicFee(
          chainId = ethConfig.chainId.value,
          nonce = BigInt(0),
          maxPriorityFeePerGas = BigInt(1),
          maxFeePerGas = BigInt(1000),
          gasLimit = GasAmount(gasLimit),
          receivingAddress = to,
          value = value,
          payload = ByteString.empty,
          accessList = Nil
        ),
        keyPair,
        Some(ethConfig.chainId.value)
      )

    def trueIntrinsic(config: EvmConfig, to: Option[Address], value: BigInt): BigInt =
      config.calcTransactionIntrinsicGas(ByteString.empty, to.isEmpty, Nil, 0, to, UInt256(value), sender)

    val recipients = Seq(Some(sender), Some(Address(0)), Some(other), None)
    for
      config <- Seq(preAmsterdam, amsterdam)
      to <- recipients
      value <- Seq(BigInt(0), BigInt(1))
      exact = trueIntrinsic(config, to, value)
      gasLimit <- Seq(exact - 1, exact, exact + 1, BigInt(21_000), BigInt(30_000))
      if gasLimit > 0
    do
      withClue(s"amsterdam=${config.amsterdamEnabled} to=$to value=$value gasLimit=$gasLimit exact=$exact: ") {
        SignedTransactionWithSender.coversIntrinsicGas(config, tx(to, value, gasLimit), 0) shouldBe (gasLimit >= exact)
      }
  }
