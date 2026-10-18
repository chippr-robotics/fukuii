package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.FiniteDuration

import org.bouncycastle.util.encoders.Hex
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Timeouts
import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.crypto.KzgTestSetup
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.branch.EmptyBranch
import com.chipprbots.ethereum.jsonrpc.JsonRpcError.*
import com.chipprbots.ethereum.jsonrpc.PersonalService.*
import com.chipprbots.ethereum.keystore.KeyStore
import com.chipprbots.ethereum.keystore.Wallet
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.TxPoolConfig

/** eth_sendTransaction / personal_sendTransaction, eth_signTransaction / personal_signTransaction and
  * eth_fillTransaction for every transaction type, on the shipped ETH mainnet config (London, Cancun, Prague and Osaka
  * active at the head) and on Mordor (Olympia unscheduled: 0x0 and 0x1 only) (#1629 S2).
  */
class PersonalServiceTypedTxSpec
    extends ScalaTestWithActorTestKit
    with AnyFlatSpecLike
    with Matchers
    with MockFactory
    with ScalaFutures
    with BeforeAndAfterAll:

  implicit val runtime: IORuntime = IORuntime.global
  implicit private val classicActorSystem: ActorSystem = system.toClassic

  override def beforeAll(): Unit =
    super.beforeAll()
    KzgTestSetup.ensureLoaded()

  private val to = Address(0x42)

  // ── eth_sendTransaction ─────────────────────────────────────────────────────

  "eth_sendTransaction" should "build an EIP-1559 transaction with geth's fee defaults after London" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup(TxDefaultsSpec.eth, TxDefaultsSpec.london):
    unlock()
    val res = personal
      .sendTransaction(SendTransactionRequest(TxArgs(from = Some(address), to = Some(to), value = Some(5))))
      .unsafeToFuture()
    replyPTM(PendingTransactionsResponse(Nil))
    val tip = TxDefaultsSpec.eth.minTip
    val expected = wallet
      .signTx(
        TransactionWithDynamicFee(
          1,
          accountNonce,
          tip,
          tip + 2 * 10,
          GasAmount(gasEstimate),
          Some(to),
          5,
          ByteString.empty,
          Nil
        ),
        None
      )
      .tx
    res.futureValue shouldBe Right(SendTransactionResponse(expected.hash.value))
    txPool.expectMsg(AddOrOverrideTransaction(expected))

  it should "build EIP-2930 and EIP-7702 transactions from their fields" taggedAs (UnitTest, RPCTest) in new TestSetup(
    TxDefaultsSpec.eth,
    TxDefaultsSpec.cancun
  ):
    unlock()
    val al = List(AccessListItem(to, List(StorageKey(BigInt(1)))))
    val auth = SetCodeAuthorization(1, Address(0x99), 0, 0, 1, 1)
    // explicit type 0x1 with gasPrice
    val r1 = personal
      .sendTransaction(
        SendTransactionRequest(
          TxArgs(
            from = Some(address),
            to = Some(to),
            nonce = Some(accountNonce),
            gasPrice = Some(GasPrice(2 * gwei)),
            accessList = Some(al),
            txType = Some(1)
          )
        )
      )
      .unsafeRunSync()
    val sent1 = txPool.expectMsgType[AddOrOverrideTransaction].signedTransaction
    sent1.tx shouldBe TransactionWithAccessList(
      1,
      accountNonce,
      GasPrice(2 * gwei),
      GasAmount(gasEstimate + 2400 + 1900),
      Some(to),
      0,
      ByteString.empty,
      al
    )
    r1 shouldBe Right(SendTransactionResponse(sent1.hash.value))
    // authorizationList selects 0x4, fees defaulted
    val r4 = personal
      .sendTransaction(
        SendTransactionRequest(
          TxArgs(
            from = Some(address),
            to = Some(to),
            nonce = Some(accountNonce + 1),
            authorizationList = Some(List(auth))
          )
        )
      )
      .unsafeRunSync()
    val sent4 = txPool.expectMsgType[AddOrOverrideTransaction].signedTransaction
    sent4.tx shouldBe a[SetCodeTransaction]
    sent4.tx.gasLimit shouldBe GasAmount(gasEstimate + 25000)
    sent4.tx.asInstanceOf[SetCodeTransaction].authorizationList shouldBe List(auth)
    r4 shouldBe Right(SendTransactionResponse(sent4.hash.value))
    SignedTransaction.getSender(sent4)(using TxDefaultsSpec.eth) shouldBe Some(address)

  it should "refuse blob transactions, as geth's SendTransaction does" taggedAs (UnitTest, RPCTest) in new TestSetup(
    TxDefaultsSpec.eth,
    TxDefaultsSpec.cancun
  ):
    unlock()
    val hash = BlobVersionedHash(ByteString(1) ++ ByteString(new Array[Byte](31)))
    personal
      .sendTransaction(
        SendTransactionRequest(TxArgs(from = Some(address), to = Some(to), blobVersionedHashes = Some(List(hash))))
      )
      .unsafeRunSync() shouldBe Left(LogicError("signing blob transactions not supported"))
    personal
      .sendTransaction(
        SendTransactionRequest(
          TxArgs(from = Some(address), to = Some(to), nonce = Some(0), blobs = Some(List(zeroBlob)))
        )
      )
      .unsafeRunSync() shouldBe Left(LogicError(""""blobs" is not supported for this RPC method"""))
    txPool.expectNoMessage()

  it should "build an EIP-155 legacy transaction on Mordor and refuse 0x2, 0x3 and 0x4" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup(TxDefaultsSpec.mordor, TxDefaultsSpec.mordorHead):
    unlock()
    val res =
      personal.sendTransaction(SendTransactionRequest(TxArgs(from = Some(address), to = Some(to)))).unsafeToFuture()
    replyPTM(PendingTransactionsResponse(Nil))
    val expected =
      wallet
        .signTx(
          LegacyTransaction(accountNonce, GasPrice(gasPrice), GasAmount(gasEstimate), Some(to), 0, ByteString.empty),
          Some(63)
        )
        .tx
    res.futureValue shouldBe Right(SendTransactionResponse(expected.hash.value))
    txPool.expectMsg(AddOrOverrideTransaction(expected))

    for (args, t, fork) <- List(
        (TxArgs(maxFeePerGas = Some(10), maxPriorityFeePerGas = Some(1)), 2, "Olympia"),
        (TxArgs(authorizationList = Some(List(SetCodeAuthorization(63, to, 0, 0, 1, 1)))), 4, "Olympia"),
        (TxArgs(txType = Some(TxArgs.DynamicFeeType)), 2, "Olympia")
      )
    do
      personal
        .sendTransaction(SendTransactionRequest(args.copy(from = Some(address), to = Some(to), nonce = Some(1))))
        .unsafeRunSync() shouldBe Left(
        LogicError(s"transaction type not supported: type $t rejected, pool not yet in $fork")
      )
    txPool.expectNoMessage()

  it should "refuse what the pool's admission refuses, without pooling it" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup(
    TxDefaultsSpec.eth,
    TxDefaultsSpec.london
  ):
    unlock()
    personal
      .sendTransaction(
        SendTransactionRequest(TxArgs(from = Some(address), to = Some(to), nonce = Some(accountNonce - 1)))
      )
      .unsafeRunSync()
      .left
      .map(_.message) should matchPattern { case Left(m: String) if m.startsWith("nonce too low") => }
    txPool.expectNoMessage()

  it should "serialise nonce defaulting per sender (geth nonceLock)" taggedAs (UnitTest, RPCTest) in new TestSetup(
    TxDefaultsSpec.eth,
    TxDefaultsSpec.london
  ):
    unlock()
    val req = SendTransactionRequest(TxArgs(from = Some(address), to = Some(to)))
    val first = personal.sendTransaction(req).unsafeToFuture()
    val second = personal.sendTransaction(req).unsafeToFuture()
    // Only one nonce query is outstanding: whichever send holds the lock reaches the pool before the other asks. Without
    // the lock the second GetPendingTransactionsReq would arrive where the AddOrOverrideTransaction is expected.
    replyPTM(PendingTransactionsResponse(Nil))
    val firstTx = txPool.expectMsgType[AddOrOverrideTransaction].signedTransaction
    replyPTM(PendingTransactionsResponse(Seq(PendingTransaction(SignedTransactionWithSender(firstTx, address), 0))))
    val secondTx = txPool.expectMsgType[AddOrOverrideTransaction].signedTransaction
    (firstTx.tx.nonce, secondTx.tx.nonce) shouldBe (accountNonce, accountNonce + 1)
    Set(first.futureValue, second.futureValue) shouldBe
      Set(Right(SendTransactionResponse(firstTx.hash.value)), Right(SendTransactionResponse(secondTx.hash.value)))

  // ── eth_signTransaction / personal_signTransaction ──────────────────────────

  "eth_signTransaction" should "require gas, fees and nonce, as geth does" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup(
    TxDefaultsSpec.eth,
    TxDefaultsSpec.london
  ):
    unlock()
    val full = TxArgs(
      from = Some(address),
      to = Some(to),
      gas = Some(GasAmount(21000)),
      maxFeePerGas = Some(100),
      maxPriorityFeePerGas = Some(1),
      nonce = Some(3)
    )
    def sign(a: TxArgs) = personal.signTransaction(SignTransactionRequest(a, None)).unsafeRunSync()
    sign(full.copy(gas = None)) shouldBe Left(LogicError("gas not specified"))
    sign(full.copy(maxFeePerGas = None)) shouldBe Left(
      LogicError("missing gasPrice or maxFeePerGas/maxPriorityFeePerGas")
    )
    sign(full.copy(nonce = None)) shouldBe Left(LogicError("nonce not specified"))
    val signed = sign(full).toOption.get.result
    signed.stx shouldBe wallet
      .signTx(TransactionWithDynamicFee(1, 3, 1, 100, GasAmount(21000), Some(to), 0, ByteString.empty, Nil), None)
      .tx
    signed.raw.toArray.toSignedTransaction shouldBe signed.stx
    txPool.expectNoMessage()

  it should "need an unlocked account" taggedAs (UnitTest, RPCTest) in new TestSetup(
    TxDefaultsSpec.eth,
    TxDefaultsSpec.london
  ):
    personal
      .signTransaction(
        SignTransactionRequest(
          TxArgs(
            from = Some(address),
            to = Some(to),
            gas = Some(GasAmount(21000)),
            gasPrice = Some(GasPrice(1)),
            nonce = Some(0)
          ),
          None
        )
      )
      .unsafeRunSync() shouldBe Left(AccountLocked)

  it should "attach a version 1 blob sidecar after Osaka and return the network form" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup(
    TxDefaultsSpec.eth,
    TxDefaultsSpec.cancun
  ):
    unlock()
    val res = personal
      .signTransaction(
        SignTransactionRequest(
          TxArgs(
            from = Some(address),
            to = Some(to),
            gas = Some(GasAmount(21000)),
            maxFeePerGas = Some(100),
            maxPriorityFeePerGas = Some(1),
            nonce = Some(0),
            blobs = Some(List(zeroBlob))
          ),
          None
        )
      )
      .unsafeRunSync()
      .toOption
      .get
      .result
    res.sidecar.map(_.version) shouldBe Some(BlobTxSidecar.Version1)
    res.sidecar.map(_.proofs.size) shouldBe Some(BlobTxSidecar.CellProofsPerBlob)
    val btx = res.stx.tx.asInstanceOf[BlobTransaction]
    btx.blobVersionedHashes shouldBe res.sidecar.get.commitments.map(BlobTxSidecar.versionedHash)
    btx.maxFeePerBlobGas should be > BigInt(0)
    val (decoded, raw) = res.raw.toArray.toSignedTransactionWithSidecar
    decoded shouldBe res.stx
    raw.map(ByteString(_)) shouldBe Some(res.raw)

  "personal_signTransaction" should "sign with the passphrase and require a sender" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup(
    TxDefaultsSpec.eth,
    TxDefaultsSpec.london
  ):
    keyStore.unlockAccount.expects(address, passphrase).returning(Right(wallet))
    val args = TxArgs(
      from = Some(address),
      to = Some(to),
      gas = Some(GasAmount(21000)),
      gasPrice = Some(GasPrice(7)),
      nonce = Some(4)
    )
    val res = personal.signTransaction(SignTransactionRequest(args, Some(passphrase))).unsafeRunSync()
    res.map(_.result.stx) shouldBe Right(
      wallet.signTx(LegacyTransaction(4, GasPrice(7), GasAmount(21000), Some(to), 0, ByteString.empty), Some(1)).tx
    )
    personal.signTransaction(SignTransactionRequest(args.copy(from = None), Some(passphrase))).unsafeRunSync() shouldBe
      Left(LogicError("sender not specified"))

  // ── eth_fillTransaction ─────────────────────────────────────────────────────

  "eth_fillTransaction" should "fill every default and return the transaction unsigned, without an account" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup(TxDefaultsSpec.eth, TxDefaultsSpec.london):
    val res = personal
      .fillTransaction(FillTransactionRequest(TxArgs(from = Some(address), to = Some(to), value = Some(1))))
      .unsafeToFuture()
    replyPTM(PendingTransactionsResponse(Nil))
    val tip = TxDefaultsSpec.eth.minTip
    val filled = res.futureValue.toOption.get.result
    filled.stx.tx shouldBe TransactionWithDynamicFee(
      1,
      accountNonce,
      tip,
      tip + 20,
      GasAmount(gasEstimate),
      Some(to),
      1,
      ByteString.empty,
      Nil
    )
    filled.stx.signature shouldBe com.chipprbots.ethereum.crypto.ECDSASignature(0, 0, 0)
    filled.sidecar shouldBe None
    txPool.expectNoMessage()

  it should "refuse a 0x2 request on Mordor" taggedAs (UnitTest, RPCTest) in new TestSetup(
    TxDefaultsSpec.mordor,
    TxDefaultsSpec.mordorHead
  ):
    personal
      .fillTransaction(
        FillTransactionRequest(TxArgs(from = Some(address), to = Some(to), maxFeePerGas = Some(5), nonce = Some(0)))
      )
      .unsafeRunSync() shouldBe Left(
      LogicError("transaction type not supported: type 2 rejected, pool not yet in Olympia")
    )

  it should "build a version 0 blob sidecar before Osaka" taggedAs (UnitTest, RPCTest) in new TestSetup(
    TxDefaultsSpec.eth,
    TxDefaultsSpec.cancun.copy(unixTimestamp = Timestamp(1746612311L + 12)) // Prague, not yet Osaka
  ):
    val res = personal
      .fillTransaction(
        FillTransactionRequest(
          TxArgs(
            from = Some(address),
            to = Some(to),
            nonce = Some(0),
            gas = Some(GasAmount(21000)),
            blobs = Some(List(zeroBlob))
          )
        )
      )
      .unsafeRunSync()
      .toOption
      .get
      .result
    res.sidecar.map(_.version) shouldBe Some(BlobTxSidecar.Version0)
    res.sidecar.map(_.proofs.size) shouldBe Some(1)

  class TestSetup(config: BlockchainConfig, head: BlockHeader):
    val prvKey: ByteString = ByteString(Hex.decode("7a44789ed3cd85861c0bbf9693c7e1de1862dd4396c390147ecf1275099c6e6f"))
    val address: Address = Address(Hex.decode("aa6826f00d01fe4085f0c3dd12778e206ce4e2ac"))
    val passphrase = "aaa"
    val wallet: Wallet = Wallet(address, prvKey)
    val accountNonce: BigInt = 7
    val gasEstimate: BigInt = 21000
    val gasPrice: BigInt = 2 * BigInt(10).pow(10)
    val gwei: BigInt = BigInt(10).pow(9)
    val zeroBlob: ByteString = ByteString(new Array[Byte](TxArgsJson.BlobLength))

    val txPoolConfig: TxPoolConfig = new TxPoolConfig:
      override val txPoolSize: Int = 30
      override val pendingTxManagerQueryTimeout: FiniteDuration = Timeouts.normalTimeout
      override val transactionTimeout: FiniteDuration = Timeouts.normalTimeout
      override val getTransactionFromPoolTimeout: FiniteDuration = Timeouts.normalTimeout

    val keyStore: KeyStore = mock[KeyStore]
    val txPool: TestProbe = TestProbe()
    val blockchainReader: BlockchainReader = mock[BlockchainReader]
    (() => blockchainReader.getBestBranch).expects().returning(EmptyBranch).anyNumberOfTimes()
    (() => blockchainReader.getBestBlock).expects().returning(Some(Block(head, BlockBody.empty))).anyNumberOfTimes()
    blockchainReader.getAccount
      .expects(*, address, head.number.value)
      .returning(Some(Account(UInt256(accountNonce), UInt256(BigInt(10).pow(20)))))
      .anyNumberOfTimes()

    implicit val oracleCfg: BlockchainConfig = config
    (() => blockchainReader.getBestBlockHeader).expects().returning(Some(head)).anyNumberOfTimes()
    blockchainReader.getAccountAtStateRoot
      .expects(*, address)
      .returning(Some(Account(UInt256(accountNonce), UInt256(BigInt(10).pow(20)))))
      .anyNumberOfTimes()

    // admission (EthTxService.admitLocal, #1629 S0) runs for real against `head` and the account above
    val ethTxService: EthTxService = new EthTxService(
      mock[BlockchainImpl],
      blockchainReader,
      stub[Mining],
      TestProbe().ref.toTyped[PendingTransactionsManager.Command],
      Timeouts.normalTimeout,
      stub[TransactionMappingStorage],
      system.scheduler
    ):
      override private[jsonrpc] def suggestGasPrice(): BigInt = gasPrice

    val gasEstimator: TxGasEstimator = new TxGasEstimator:
      def estimateGas(args: TxArgs, h: BlockHeader): IO[Either[JsonRpcError, BigInt]] =
        // stands in for the typed estimator (#1629 S3), which prices the lists: EIP-2930 2400 per address and 1900 per
        // storage key, EIP-7702 25000 per authorization (pre-Amsterdam)
        val al = args.accessList.getOrElse(Nil)
        val lists = al.size * 2400 + al.map(_.storageKeys.size).sum * 1900 + args.authorizationList
          .map(_.size)
          .getOrElse(0) * 25000
        IO.pure(Right(gasEstimate + lists))

    val personal = new PersonalService(
      keyStore,
      blockchainReader,
      txPool.ref.toTyped[PendingTransactionsManager.Command],
      txPoolConfig,
      new BlockchainConfigBuilder with com.chipprbots.ethereum.TestInstanceConfigProvider:
        override def blockchainConfig: BlockchainConfig = config
      ,
      ethTxService,
      gasEstimator,
      system.scheduler
    )

    def unlock(): Unit =
      keyStore.unlockAccount.expects(address, passphrase).returning(Right(wallet))
      personal.unlockAccount(UnlockAccountRequest(address, passphrase, None)).unsafeRunSync()

    def replyPTM(response: PendingTransactionsResponse): Unit =
      txPool.expectMsgPF() { case req: GetPendingTransactionsReq => req.replyTo ! response }
