package com.chipprbots.ethereum.jsonrpc

import java.time.Duration

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

import org.bouncycastle.util.encoders.Hex
import org.scalamock.matchers.MatcherBase
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.Timeouts
import com.chipprbots.ethereum.WithActorSystemShutDown
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.domain.branch.EmptyBranch
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.jsonrpc.JsonRpcError._
import com.chipprbots.ethereum.jsonrpc.PersonalService._
import com.chipprbots.ethereum.keystore.KeyStore
import com.chipprbots.ethereum.keystore.KeyStore.DecryptionFailed
import com.chipprbots.ethereum.keystore.KeyStore.IOError
import com.chipprbots.ethereum.keystore.Wallet
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.transactions.PendingTransactionsManager._
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkBlockNumbers
import com.chipprbots.ethereum.utils.MonetaryPolicyConfig
import com.chipprbots.ethereum.utils.TxPoolConfig
import scala.concurrent.Future
import scala.concurrent.Future
import scala.concurrent.Future
import scala.concurrent.Future
import scala.concurrent.Future

class PersonalServiceSpec
    extends TestKit(ActorSystem("JsonRpcControllerEthSpec_System"))
    with AnyFlatSpecLike
    with WithActorSystemShutDown
    with Matchers
    with MockFactory
    with ScalaFutures
    with NormalPatience
    with Eventually
    with ScalaCheckPropertyChecks {

  implicit val runtime: IORuntime = IORuntime.global

  "PersonalService" should "import private keys" taggedAs (UnitTest, RPCTest) in new TestSetup {
    (keyStore.importPrivateKey _).expects(prvKey, passphrase).returning(Right(address))

    val req: ImportRawKeyRequest = ImportRawKeyRequest(prvKey, passphrase)
    val res: Either[JsonRpcError, ImportRawKeyResponse] = personal.importRawKey(req).unsafeRunSync()

    res shouldEqual Right(ImportRawKeyResponse(address))
  }

  it should "create new accounts" taggedAs (UnitTest, RPCTest) in new TestSetup {
    (keyStore.newAccount _).expects(passphrase).returning(Right(address))

    val req: NewAccountRequest = NewAccountRequest(passphrase)
    val res: Either[JsonRpcError, NewAccountResponse] = personal.newAccount(req).unsafeRunSync()

    res shouldEqual Right(NewAccountResponse(address))
  }

  it should "handle too short passphrase error" taggedAs (UnitTest, RPCTest) in new TestSetup {
    (keyStore.newAccount _).expects(passphrase).returning(Left(KeyStore.PassPhraseTooShort(7)))

    val req: NewAccountRequest = NewAccountRequest(passphrase)
    val res: Either[JsonRpcError, NewAccountResponse] = personal.newAccount(req).unsafeRunSync()

    res shouldEqual Left(PersonalService.PassPhraseTooShort(7))
  }

  it should "list accounts" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val addresses: List[Address] = List(123, 42, 1).map(Address(_))
    (keyStore.listAccounts _).expects().returning(Right(addresses))

    val res: Either[JsonRpcError, ListAccountsResponse] = personal.listAccounts(ListAccountsRequest()).unsafeRunSync()

    res shouldEqual Right(ListAccountsResponse(addresses))
  }

  it should "translate KeyStore errors to JsonRpc errors" taggedAs (UnitTest, RPCTest) in new TestSetup {
    (keyStore.listAccounts _).expects().returning(Left(IOError("boom!")))
    val res1: Either[JsonRpcError, ListAccountsResponse] = personal.listAccounts(ListAccountsRequest()).unsafeRunSync()
    res1 shouldEqual Left(LogicError("boom!"))

    (keyStore.unlockAccount _).expects(*, *).returning(Left(KeyStore.KeyNotFound))
    val res2: Either[JsonRpcError, UnlockAccountResponse] =
      personal.unlockAccount(UnlockAccountRequest(Address(42), "passphrase", None)).unsafeRunSync()
    res2 shouldEqual Left(KeyNotFound)

    (keyStore.unlockAccount _).expects(*, *).returning(Left(KeyStore.DecryptionFailed))
    val res3: Either[JsonRpcError, UnlockAccountResponse] =
      personal.unlockAccount(UnlockAccountRequest(Address(42), "passphrase", None)).unsafeRunSync()
    res3 shouldEqual Left(InvalidPassphrase)
  }

  it should "return an error when trying to import an invalid key" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val invalidKey = prvKey.tail
    val req: ImportRawKeyRequest = ImportRawKeyRequest(invalidKey, passphrase)
    val res: Either[JsonRpcError, ImportRawKeyResponse] = personal.importRawKey(req).unsafeRunSync()
    res shouldEqual Left(InvalidKey)
  }

  it should "unlock an account given a correct passphrase" taggedAs (UnitTest, RPCTest) in new TestSetup {
    (keyStore.unlockAccount _).expects(address, passphrase).returning(Right(wallet))

    val req: UnlockAccountRequest = UnlockAccountRequest(address, passphrase, None)
    val res: Either[JsonRpcError, UnlockAccountResponse] = personal.unlockAccount(req).unsafeRunSync()

    res shouldEqual Right(UnlockAccountResponse(true))
  }

  it should "send a transaction (given sender address and a passphrase)" taggedAs (UnitTest, RPCTest) in new TestSetup {
    (keyStore.unlockAccount _)
      .expects(address, passphrase)
      .returning(Right(wallet))

    (blockchainReader.getBestBlockNumber _).expects().returning(1234)
    (blockchainReader.getAccount _).expects(*, address, BigInt(1234)).returning(Some(Account(nonce, 2 * txValue)))
    (blockchainReader.getBestBlockNumber _).expects().returning(forkBlockNumbers.eip155BlockNumber - 1)

    val req: SendTransactionWithPassphraseRequest = SendTransactionWithPassphraseRequest(tx, passphrase)
    val res: Future[Either[JsonRpcError, SendTransactionWithPassphraseResponse]] =
      personal.sendTransaction(req).unsafeToFuture()

    txPool.expectMsg(GetPendingTransactions)
    txPool.reply(PendingTransactionsResponse(Nil))

    res.futureValue shouldEqual Right(SendTransactionWithPassphraseResponse(stx.hash))
    txPool.expectMsg(AddOrOverrideTransaction(stx))
  }

  it should "send a transaction when having pending txs from the same sender" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val newTx: SignedTransaction = wallet.signTx(tx.toTransaction(nonce + 1), None).tx

    (keyStore.unlockAccount _)
      .expects(address, passphrase)
      .returning(Right(wallet))

    (blockchainReader.getBestBlockNumber _).expects().returning(1234)
    (blockchainReader.getAccount _).expects(*, address, BigInt(1234)).returning(Some(Account(nonce, 2 * txValue)))
    (blockchainReader.getBestBlockNumber _).expects().returning(forkBlockNumbers.eip155BlockNumber - 1)

    val req: SendTransactionWithPassphraseRequest = SendTransactionWithPassphraseRequest(tx, passphrase)
    val res: Future[Either[JsonRpcError, SendTransactionWithPassphraseResponse]] =
      personal.sendTransaction(req).unsafeToFuture()

    txPool.expectMsg(GetPendingTransactions)
    txPool.reply(PendingTransactionsResponse(Seq(PendingTransaction(stxWithSender, 0))))

    res.futureValue shouldEqual Right(SendTransactionWithPassphraseResponse(newTx.hash))
    txPool.expectMsg(AddOrOverrideTransaction(newTx))
  }

  it should "fail to send a transaction given a wrong passphrase" taggedAs (UnitTest, RPCTest) in new TestSetup {
    (keyStore.unlockAccount _)
      .expects(address, passphrase)
      .returning(Left(KeyStore.DecryptionFailed))

    val req: SendTransactionWithPassphraseRequest = SendTransactionWithPassphraseRequest(tx, passphrase)
    val res: Either[JsonRpcError, SendTransactionWithPassphraseResponse] = personal.sendTransaction(req).unsafeRunSync()

    res shouldEqual Left(InvalidPassphrase)
    txPool.expectNoMessage()
  }

  it should "send a transaction (given sender address and using an unlocked account)" taggedAs (UnitTest, RPCTest) in new TestSetup {
    (keyStore.unlockAccount _)
      .expects(address, passphrase)
      .returning(Right(wallet))

    personal.unlockAccount(UnlockAccountRequest(address, passphrase, None)).unsafeRunSync()

    (blockchainReader.getBestBlockNumber _).expects().returning(1234)
    (blockchainReader.getAccount _).expects(*, address, BigInt(1234)).returning(Some(Account(nonce, 2 * txValue)))
    (blockchainReader.getBestBlockNumber _).expects().returning(forkBlockNumbers.eip155BlockNumber - 1)

    val req: SendTransactionRequest = SendTransactionRequest(tx)
    val res: Future[Either[JsonRpcError, SendTransactionResponse]] = personal.sendTransaction(req).unsafeToFuture()

    txPool.expectMsg(GetPendingTransactions)
    txPool.reply(PendingTransactionsResponse(Nil))

    res.futureValue shouldEqual Right(SendTransactionResponse(stx.hash))
    txPool.expectMsg(AddOrOverrideTransaction(stx))
  }

  it should "fail to send a transaction when account is locked" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val req: SendTransactionRequest = SendTransactionRequest(tx)
    val res: Either[JsonRpcError, SendTransactionResponse] = personal.sendTransaction(req).unsafeRunSync()

    res shouldEqual Left(AccountLocked)
    txPool.expectNoMessage()
  }

  it should "lock an unlocked account" taggedAs (UnitTest, RPCTest) in new TestSetup {
    (keyStore.unlockAccount _)
      .expects(address, passphrase)
      .returning(Right(wallet))

    personal.unlockAccount(UnlockAccountRequest(address, passphrase, None)).unsafeRunSync()

    val lockRes: Either[JsonRpcError, LockAccountResponse] =
      personal.lockAccount(LockAccountRequest(address)).unsafeRunSync()
    val txRes: Either[JsonRpcError, SendTransactionResponse] =
      personal.sendTransaction(SendTransactionRequest(tx)).unsafeRunSync()

    lockRes shouldEqual Right(LockAccountResponse(true))
    txRes shouldEqual Left(AccountLocked)
  }

  it should "sign a message when correct passphrase is sent" taggedAs (UnitTest, RPCTest) in new TestSetup {

    (keyStore.unlockAccount _)
      .expects(address, passphrase)
      .returning(Right(wallet))

    val message: ByteString = ByteString(Hex.decode("deadbeaf"))

    val r: ByteString = ByteString(Hex.decode("d237344891a90a389b7747df6fbd0091da20d1c61adb961b4491a4c82f58dcd2"))
    val s: ByteString = ByteString(Hex.decode("5425852614593caf3a922f48a6fe5204066dcefbf6c776c4820d3e7522058d00"))
    val v: Byte = ByteString(Hex.decode("1b")).last

    val req: SignRequest = SignRequest(message, address, Some(passphrase))

    val res: Either[JsonRpcError, SignResponse] = personal.sign(req).unsafeRunSync()
    res shouldEqual Right(SignResponse(ECDSASignature(r, s, v)))

    // Account should still be locked after calling sign with passphrase
    val txReq: SendTransactionRequest = SendTransactionRequest(tx)
    val txRes: Either[JsonRpcError, SendTransactionResponse] = personal.sendTransaction(txReq).unsafeRunSync()
    txRes shouldEqual Left(AccountLocked)

  }

  it should "sign a message using an unlocked account" taggedAs (UnitTest, RPCTest) in new TestSetup {

    (keyStore.unlockAccount _)
      .expects(address, passphrase)
      .returning(Right(wallet))

    val message: ByteString = ByteString(Hex.decode("deadbeaf"))

    val r: ByteString = ByteString(Hex.decode("d237344891a90a389b7747df6fbd0091da20d1c61adb961b4491a4c82f58dcd2"))
    val s: ByteString = ByteString(Hex.decode("5425852614593caf3a922f48a6fe5204066dcefbf6c776c4820d3e7522058d00"))
    val v: Byte = ByteString(Hex.decode("1b")).last

    val req: SignRequest = SignRequest(message, address, None)

    personal.unlockAccount(UnlockAccountRequest(address, passphrase, None)).unsafeRunSync()
    val res: Either[JsonRpcError, SignResponse] = personal.sign(req).unsafeRunSync()
    res shouldEqual Right(SignResponse(ECDSASignature(r, s, v)))
  }

  it should "return an error if signing a message using a locked account" taggedAs (UnitTest, RPCTest) in new TestSetup {

    val message: ByteString = ByteString(Hex.decode("deadbeaf"))

    val req: SignRequest = SignRequest(message, address, None)

    val res: Either[JsonRpcError, SignResponse] = personal.sign(req).unsafeRunSync()
    res shouldEqual Left(AccountLocked)
  }

  it should "return an error when signing a message if passphrase is wrong" taggedAs (UnitTest, RPCTest) in new TestSetup {

    val wrongPassphase = "wrongPassphrase"

    (keyStore.unlockAccount _)
      .expects(address, wrongPassphase)
      .returning(Left(DecryptionFailed))

    val message: ByteString = ByteString(Hex.decode("deadbeaf"))

    val req: SignRequest = SignRequest(message, address, Some(wrongPassphase))

    val res: Either[JsonRpcError, SignResponse] = personal.sign(req).unsafeRunSync()
    res shouldEqual Left(InvalidPassphrase)
  }

  it should "return an error when signing if unexistent address is sent" taggedAs (UnitTest, RPCTest) in new TestSetup {

    (keyStore.unlockAccount _)
      .expects(address, passphrase)
      .returning(Left(KeyStore.KeyNotFound))

    val message: ByteString = ByteString(Hex.decode("deadbeaf"))

    val req: SignRequest = SignRequest(message, address, Some(passphrase))

    val res: Either[JsonRpcError, SignResponse] = personal.sign(req).unsafeRunSync()
    res shouldEqual Left(KeyNotFound)
  }

  it should "recover address form signed message" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val sigAddress: Address = Address(ByteString(Hex.decode("12c2a3b877289050FBcfADC1D252842CA742BE81")))

    val message: ByteString = ByteString(Hex.decode("deadbeaf"))

    val r: ByteString = ByteString(Hex.decode("117b8d5b518dc428d97e5e0c6f870ad90e561c97de8fe6cad6382a7e82134e61"))
    val s: ByteString = ByteString(Hex.decode("396d881ef1f8bc606ef94b74b83d76953b61f1bcf55c002ef12dd0348edff24b"))
    val v: Byte = ByteString(Hex.decode("1b")).last

    val req: EcRecoverRequest = EcRecoverRequest(message, ECDSASignature(r, s, v))

    val res: Either[JsonRpcError, EcRecoverResponse] = personal.ecRecover(req).unsafeRunSync()
    res shouldEqual Right(EcRecoverResponse(sigAddress))
  }

  it should "allow to sign and recover the same message" taggedAs (UnitTest, RPCTest) in new TestSetup {

    (keyStore.unlockAccount _)
      .expects(address, passphrase)
      .returning(Right(wallet))

    val message: ByteString = ByteString(Hex.decode("deadbeaf"))

    personal
      .sign(SignRequest(message, address, Some(passphrase)))
      .unsafeRunSync()
      .left
      .map(_ => fail())
      .map(response => EcRecoverRequest(message, response.signature))
      .foreach { req =>
        val res = personal.ecRecover(req).unsafeRunSync()
        res shouldEqual Right(EcRecoverResponse(address))
      }
  }

  it should "produce not chain specific transaction before eip155" taggedAs (UnitTest, RPCTest) in new TestSetup {
    (keyStore.unlockAccount _)
      .expects(address, passphrase)
      .returning(Right(wallet))

    (blockchainReader.getBestBlockNumber _).expects().returning(1234)
    (blockchainReader.getAccount _).expects(*, address, BigInt(1234)).returning(Some(Account(nonce, 2 * txValue)))
    (blockchainReader.getBestBlockNumber _).expects().returning(forkBlockNumbers.eip155BlockNumber - 1)

    val req: SendTransactionWithPassphraseRequest = SendTransactionWithPassphraseRequest(tx, passphrase)
    val res: Future[Either[JsonRpcError, SendTransactionWithPassphraseResponse]] =
      personal.sendTransaction(req).unsafeToFuture()

    txPool.expectMsg(GetPendingTransactions)
    txPool.reply(PendingTransactionsResponse(Nil))

    res.futureValue shouldEqual Right(SendTransactionWithPassphraseResponse(stx.hash))
    txPool.expectMsg(AddOrOverrideTransaction(stx))
  }

  it should "produce chain specific transaction after eip155" taggedAs (UnitTest, RPCTest) in new TestSetup {
    (keyStore.unlockAccount _)
      .expects(address, passphrase)
      .returning(Right(wallet))

    (blockchainReader.getBestBlockNumber _).expects().returning(1234)
    (blockchainReader.getAccount _).expects(*, address, BigInt(1234)).returning(Some(Account(nonce, 2 * txValue)))
    new Block(Fixtures.Blocks.Block3125369.header, Fixtures.Blocks.Block3125369.body)
    (blockchainReader.getBestBlockNumber _).expects().returning(forkBlockNumbers.eip155BlockNumber)

    val req: SendTransactionWithPassphraseRequest = SendTransactionWithPassphraseRequest(tx, passphrase)
    val res: Future[Either[JsonRpcError, SendTransactionWithPassphraseResponse]] =
      personal.sendTransaction(req).unsafeToFuture()

    txPool.expectMsg(GetPendingTransactions)
    txPool.reply(PendingTransactionsResponse(Nil))

    res.futureValue shouldEqual Right(SendTransactionWithPassphraseResponse(chainSpecificStx.hash))
    txPool.expectMsg(AddOrOverrideTransaction(chainSpecificStx))
  }

  it should "return an error when importing a duplicated key" taggedAs (UnitTest, RPCTest) in new TestSetup {
    (keyStore.importPrivateKey _).expects(prvKey, passphrase).returning(Left(KeyStore.DuplicateKeySaved))

    val req: ImportRawKeyRequest = ImportRawKeyRequest(prvKey, passphrase)
    val res: Either[JsonRpcError, ImportRawKeyResponse] = personal.importRawKey(req).unsafeRunSync()
    res shouldEqual Left(LogicError("account already exists"))
  }

  it should "unlock an account given a correct passphrase for specified duration" taggedAs (UnitTest, RPCTest) in new TestSetup {
    (keyStore.unlockAccount _).expects(address, passphrase).returning(Right(wallet))

    val message: ByteString = ByteString(Hex.decode("deadbeaf"))

    val r: ByteString = ByteString(Hex.decode("d237344891a90a389b7747df6fbd0091da20d1c61adb961b4491a4c82f58dcd2"))
    val s: ByteString = ByteString(Hex.decode("5425852614593caf3a922f48a6fe5204066dcefbf6c776c4820d3e7522058d00"))
    val v: Byte = ByteString(Hex.decode("1b")).last

    val reqSign: SignRequest = SignRequest(message, address, None)

    val req: UnlockAccountRequest = UnlockAccountRequest(address, passphrase, Some(Duration.ofSeconds(2)))
    val res: Either[JsonRpcError, UnlockAccountResponse] = personal.unlockAccount(req).unsafeRunSync()
    res shouldEqual Right(UnlockAccountResponse(true))

    val res2: Either[JsonRpcError, SignResponse] = personal.sign(reqSign).unsafeRunSync()
    res2 shouldEqual Right(SignResponse(ECDSASignature(r, s, v)))

    eventually {
      personal.sign(reqSign).unsafeRunSync() shouldEqual Left(AccountLocked)
    }
  }

  trait TestSetup {
    val prvKey: ByteString = ByteString(Hex.decode("7a44789ed3cd85861c0bbf9693c7e1de1862dd4396c390147ecf1275099c6e6f"))
    val address: Address = Address(Hex.decode("aa6826f00d01fe4085f0c3dd12778e206ce4e2ac"))
    val passphrase = "aaa"

    val nonce = 7
    val txValue = 128000

    val chainId: Byte = 0x03.toByte
    val forkBlockNumbers: ForkBlockNumbers = ForkBlockNumbers.Empty.copy(
      eip155BlockNumber = 12345,
      eip161BlockNumber = 0,
      frontierBlockNumber = 0,
      difficultyBombPauseBlockNumber = 0,
      difficultyBombContinueBlockNumber = 0,
      homesteadBlockNumber = 0,
      eip150BlockNumber = 0,
      eip160BlockNumber = 0,
      eip106BlockNumber = 0,
      byzantiumBlockNumber = 0,
      constantinopleBlockNumber = 0,
      istanbulBlockNumber = 0,
      atlantisBlockNumber = 0,
      aghartaBlockNumber = 0,
      phoenixBlockNumber = 0,
      petersburgBlockNumber = 0,
      ecip1098BlockNumber = 0,
      ecip1097BlockNumber = 0
    )

    val wallet: Wallet = Wallet(address, prvKey)
    val tx: TransactionRequest = TransactionRequest(from = address, to = Some(Address(42)), value = Some(txValue))
    val stxWithSender: SignedTransactionWithSender = wallet.signTx(tx.toTransaction(nonce), None)
    val stx = stxWithSender.tx
    val chainSpecificStx: SignedTransaction = wallet.signTx(tx.toTransaction(nonce), Some(chainId)).tx

    val txPoolConfig: TxPoolConfig = new TxPoolConfig {
      override val txPoolSize: Int = 30
      override val pendingTxManagerQueryTimeout: FiniteDuration = Timeouts.normalTimeout
      override val transactionTimeout: FiniteDuration = Timeouts.normalTimeout
      override val getTransactionFromPoolTimeout: FiniteDuration = Timeouts.normalTimeout
    }

    val keyStore: KeyStore = mock[KeyStore]

    val txPool: TestProbe = TestProbe()
    val blockchainReader: BlockchainReader = mock[BlockchainReader]
    (blockchainReader.getBestBranch _).expects().returning(EmptyBranch).anyNumberOfTimes()
    val blockchain: BlockchainImpl = mock[BlockchainImpl]
    val personal =
      new PersonalService(
        keyStore,
        blockchainReader,
        txPool.ref,
        txPoolConfig,
        new BlockchainConfigBuilder {
          override def blockchainConfig: BlockchainConfig = BlockchainConfig(
            chainId = chainId,
            // unused
            networkId = 1,
            maxCodeSize = None,
            forkBlockNumbers = forkBlockNumbers,
            customGenesisFileOpt = None,
            customGenesisJsonOpt = None,
            accountStartNonce = UInt256.Zero,
            monetaryPolicyConfig = MonetaryPolicyConfig(0, 0, 0, 0),
            daoForkConfig = None,
            bootstrapNodes = Set(),
            gasTieBreaker = false,
            ethCompatibleStorage = true,
            treasuryAddress = Address(0)
          )
        }
      )

    def array[T](arr: Array[T])(implicit ev: ClassTag[Array[T]]): MatcherBase =
      argThat((_: Array[T]).sameElements(arr))
  }
}
