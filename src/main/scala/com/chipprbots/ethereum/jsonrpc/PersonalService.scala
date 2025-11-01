package com.chipprbots.ethereum.jsonrpc

import java.time.Duration

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import cats.effect.IO

import scala.util.Try

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps._
import com.chipprbots.ethereum.jsonrpc.JsonRpcError._
import com.chipprbots.ethereum.jsonrpc.PersonalService._
import com.chipprbots.ethereum.keystore.KeyStore
import com.chipprbots.ethereum.keystore.Wallet
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPImplicitConversions._
import com.chipprbots.ethereum.rlp.RLPImplicits.{given, _}
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.AddOrOverrideTransaction
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.PendingTransactionsResponse
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.utils.TxPoolConfig

object PersonalService {

  case class ImportRawKeyRequest(prvKey: ByteString, passphrase: String)
  case class ImportRawKeyResponse(address: Address)

  case class NewAccountRequest(passphrase: String)
  case class NewAccountResponse(address: Address)

  case class ListAccountsRequest()
  case class ListAccountsResponse(addresses: List[Address])

  case class UnlockAccountRequest(address: Address, passphrase: String, duration: Option[Duration])
  case class UnlockAccountResponse(result: Boolean)

  case class LockAccountRequest(address: Address)
  case class LockAccountResponse(result: Boolean)

  case class SendTransactionWithPassphraseRequest(tx: TransactionRequest, passphrase: String)
  case class SendTransactionWithPassphraseResponse(txHash: ByteString)

  case class SendTransactionRequest(tx: TransactionRequest)
  case class SendTransactionResponse(txHash: ByteString)

  case class SendIeleTransactionRequest(tx: IeleTransactionRequest)

  case class SignRequest(message: ByteString, address: Address, passphrase: Option[String])
  case class SignResponse(signature: ECDSASignature)

  case class EcRecoverRequest(message: ByteString, signature: ECDSASignature)
  case class EcRecoverResponse(address: Address)

  val InvalidKey: JsonRpcError = InvalidParams("Invalid key provided, expected 32 bytes (64 hex digits)")
  val InvalidAddress: JsonRpcError = InvalidParams("Invalid address, expected 20 bytes (40 hex digits)")
  val InvalidPassphrase: JsonRpcError = LogicError("Could not decrypt key with given passphrase")
  val KeyNotFound: JsonRpcError = LogicError("No key found for the given address")
  val PassPhraseTooShort: Int => JsonRpcError = minLength =>
    LogicError(s"Provided passphrase must have at least $minLength characters")

  val PrivateKeyLength = 32
  val defaultUnlockTime = 300
}

class PersonalService(
    keyStore: KeyStore,
    blockchainReader: BlockchainReader,
    txPool: ActorRef,
    txPoolConfig: TxPoolConfig,
    configBuilder: BlockchainConfigBuilder
) extends Logger {
  import configBuilder._

  private val unlockedWallets: ExpiringMap[Address, Wallet] = ExpiringMap.empty(Duration.ofSeconds(defaultUnlockTime))

  def importRawKey(req: ImportRawKeyRequest): ServiceResponse[ImportRawKeyResponse] = IO {
    for {
      prvKey <- Right(req.prvKey).filterOrElse(_.length == PrivateKeyLength, InvalidKey)
      addr <- keyStore.importPrivateKey(prvKey, req.passphrase).left.map(handleError)
    } yield ImportRawKeyResponse(addr)
  }

  def newAccount(req: NewAccountRequest): ServiceResponse[NewAccountResponse] = IO {
    keyStore
      .newAccount(req.passphrase)
      .map(NewAccountResponse.apply)
      .left
      .map(handleError)
  }

  def listAccounts(request: ListAccountsRequest): ServiceResponse[ListAccountsResponse] = IO {
    keyStore
      .listAccounts()
      .map(ListAccountsResponse.apply)
      .left
      .map(handleError)
  }

  def unlockAccount(request: UnlockAccountRequest): ServiceResponse[UnlockAccountResponse] = IO {
    keyStore
      .unlockAccount(request.address, request.passphrase)
      .left
      .map(handleError)
      .map { wallet =>
        request.duration.fold(unlockedWallets.add(request.address, wallet))(duration =>
          if (duration.isZero)
            unlockedWallets.addForever(request.address, wallet)
          else
            unlockedWallets.add(request.address, wallet, duration)
        )

        UnlockAccountResponse(true)
      }
  }

  def lockAccount(request: LockAccountRequest): ServiceResponse[LockAccountResponse] = IO {
    unlockedWallets.remove(request.address)
    Right(LockAccountResponse(true))
  }

  def sign(request: SignRequest): ServiceResponse[SignResponse] = IO {
    import request._

    val accountWallet =
      passphrase.fold(unlockedWallets.get(request.address).toRight(AccountLocked)) { pass =>
        keyStore.unlockAccount(address, pass).left.map(handleError)
      }

    accountWallet
      .map { wallet =>
        SignResponse(ECDSASignature.sign(getMessageToSign(message), wallet.keyPair))
      }
  }

  def ecRecover(req: EcRecoverRequest): ServiceResponse[EcRecoverResponse] = IO {
    import req._
    signature
      .publicKey(getMessageToSign(message))
      .map { publicKey =>
        Right(EcRecoverResponse(Address(crypto.kec256(publicKey))))
      }
      .getOrElse(Left(InvalidParams("unable to recover address")))
  }

  def sendTransaction(
      request: SendTransactionWithPassphraseRequest
  ): ServiceResponse[SendTransactionWithPassphraseResponse] = {
    val maybeWalletUnlocked = IO {
      keyStore.unlockAccount(request.tx.from, request.passphrase).left.map(handleError)
    }

    maybeWalletUnlocked.flatMap {
      case Right(wallet) =>
        val futureTxHash = sendTransaction(request.tx, wallet)
        futureTxHash.map(txHash => Right(SendTransactionWithPassphraseResponse(txHash)))
      case Left(err) => IO.pure(Left(err))
    }
  }

  def sendTransaction(request: SendTransactionRequest): ServiceResponse[SendTransactionResponse] =
    IO(unlockedWallets.get(request.tx.from)).flatMap {
      case Some(wallet) =>
        val futureTxHash = sendTransaction(request.tx, wallet)
        futureTxHash.map(txHash => Right(SendTransactionResponse(txHash)))

      case None => IO.pure(Left(AccountLocked))
    }

  def sendIeleTransaction(request: SendIeleTransactionRequest): ServiceResponse[SendTransactionResponse] = {
    import request.tx

    val args = tx.arguments.getOrElse(Nil)
    val dataEither = (tx.function, tx.contractCode) match {
      case (Some(function), None)     => Right(rlp.encode(RLPList(toEncodeable(function), toEncodeable(args))))
      case (None, Some(contractCode)) => Right(rlp.encode(RLPList(toEncodeable(contractCode), toEncodeable(args))))
      case _ => Left(JsonRpcError.InvalidParams("Iele transaction should contain either functionName or contractCode"))
    }

    dataEither match {
      case Right(data) =>
        sendTransaction(
          SendTransactionRequest(
            TransactionRequest(tx.from, tx.to, tx.value, tx.gasLimit, tx.gasPrice, tx.nonce, Some(ByteString(data)))
          )
        )
      case Left(error) =>
        IO.pure(Left(error))
    }
  }

  private def sendTransaction(request: TransactionRequest, wallet: Wallet): IO[ByteString] = {
    implicit val timeout = Timeout(txPoolConfig.pendingTxManagerQueryTimeout)

    val pendingTxsFuture =
      txPool.askFor[PendingTransactionsResponse](PendingTransactionsManager.GetPendingTransactions)
    val latestPendingTxNonceFuture: IO[Option[BigInt]] = pendingTxsFuture.map { pendingTxs =>
      val senderTxsNonces = pendingTxs.pendingTransactions
        .collect { case ptx if ptx.stx.senderAddress == wallet.address => ptx.stx.tx.tx.nonce }
      Try(senderTxsNonces.max).toOption
    }
    latestPendingTxNonceFuture.map { maybeLatestPendingTxNonce =>
      val maybeCurrentNonce = getCurrentAccount(request.from).map(_.nonce.toBigInt)
      val maybeNextTxNonce = maybeLatestPendingTxNonce.map(_ + 1).orElse(maybeCurrentNonce)
      val tx = request.toTransaction(maybeNextTxNonce.getOrElse(blockchainConfig.accountStartNonce))

      val stx = if (blockchainReader.getBestBlockNumber() >= blockchainConfig.forkBlockNumbers.eip155BlockNumber) {
        wallet.signTx(tx, Some(blockchainConfig.chainId))
      } else {
        wallet.signTx(tx, None)
      }
      log.debug("Trying to add personal transaction: {}", stx.tx.hash.toHex)

      txPool ! AddOrOverrideTransaction(stx.tx)

      stx.tx.hash
    }
  }

  private def getCurrentAccount(address: Address): Option[Account] =
    blockchainReader.getAccount(blockchainReader.getBestBranch(), address, blockchainReader.getBestBlockNumber())

  private def getMessageToSign(message: ByteString) = {
    val prefixed: Array[Byte] =
      0x19.toByte +:
        s"Ethereum Signed Message:\n${message.length}".getBytes ++:
        message.toArray[Byte]

    crypto.kec256(prefixed)
  }

  private val handleError: PartialFunction[KeyStore.KeyStoreError, JsonRpcError] = {
    case KeyStore.DecryptionFailed              => InvalidPassphrase
    case KeyStore.KeyNotFound                   => KeyNotFound
    case KeyStore.PassPhraseTooShort(minLength) => PassPhraseTooShort(minLength)
    case KeyStore.IOError(msg)                  => LogicError(msg)
    case KeyStore.DuplicateKeySaved             => LogicError("account already exists")
  }
}
