package com.chipprbots.ethereum.jsonrpc

import java.time.Duration

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import cats.data.EitherT
import cats.effect.IO
import cats.effect.SyncIO
import cats.effect.std.Semaphore

import java.util.concurrent.ConcurrentHashMap

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.Transaction
import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps.*
import com.chipprbots.ethereum.jsonrpc.JsonRpcError.*
import com.chipprbots.ethereum.jsonrpc.PersonalService.*
import com.chipprbots.ethereum.keystore.KeyStore
import com.chipprbots.ethereum.keystore.Wallet
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.PendingTransactionsResponse
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.utils.TxPoolConfig

object PersonalService:

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

  case class SendTransactionWithPassphraseRequest(tx: TxArgs, passphrase: String)
  case class SendTransactionWithPassphraseResponse(txHash: ByteString)

  case class SendTransactionRequest(tx: TxArgs)
  case class SendTransactionResponse(txHash: ByteString)

  /** eth_signTransaction (`passphrase = None`: the account must be unlocked) and personal_signTransaction. */
  case class SignTransactionRequest(tx: TxArgs, passphrase: Option[String])
  case class SignTransactionResponse(result: SignTransactionResult)

  case class FillTransactionRequest(tx: TxArgs)
  case class FillTransactionResponse(result: SignTransactionResult)

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

trait PersonalServiceAPI:
  import PersonalService.*

  def importRawKey(req: ImportRawKeyRequest): ServiceResponse[ImportRawKeyResponse]
  def newAccount(req: NewAccountRequest): ServiceResponse[NewAccountResponse]
  def listAccounts(request: ListAccountsRequest): ServiceResponse[ListAccountsResponse]
  def unlockAccount(request: UnlockAccountRequest): ServiceResponse[UnlockAccountResponse]
  def lockAccount(request: LockAccountRequest): ServiceResponse[LockAccountResponse]
  def sign(request: SignRequest): ServiceResponse[SignResponse]
  def ecRecover(req: EcRecoverRequest): ServiceResponse[EcRecoverResponse]
  def sendTransaction(
      request: SendTransactionWithPassphraseRequest
  ): ServiceResponse[SendTransactionWithPassphraseResponse]
  def sendTransaction(request: SendTransactionRequest): ServiceResponse[SendTransactionResponse]
  def signTransaction(request: SignTransactionRequest): ServiceResponse[SignTransactionResponse]
  def fillTransaction(request: FillTransactionRequest): ServiceResponse[FillTransactionResponse]

class PersonalService(
    keyStore: KeyStore,
    blockchainReader: BlockchainReader,
    txPool: ActorRef[PendingTransactionsManager.Command],
    txPoolConfig: TxPoolConfig,
    configBuilder: BlockchainConfigBuilder,
    ethTxService: EthTxService,
    gasEstimator: TxGasEstimator,
    scheduler: Scheduler
) extends PersonalServiceAPI
    with Logger:
  import configBuilder.*

  private val unlockedWallets: ExpiringMap[Address, Wallet] = ExpiringMap.empty(Duration.ofSeconds(defaultUnlockTime))

  def importRawKey(req: ImportRawKeyRequest): ServiceResponse[ImportRawKeyResponse] = IO {
    for
      prvKey <- Right(req.prvKey).filterOrElse(_.length == PrivateKeyLength, InvalidKey)
      addr <- keyStore.importPrivateKey(prvKey, req.passphrase).left.map(handleError)
    yield ImportRawKeyResponse(addr)
  }

  def newAccount(req: NewAccountRequest): ServiceResponse[NewAccountResponse] = IO {
    keyStore
      .newAccount(req.passphrase)
      .map(NewAccountResponse.apply)
      .left
      .map(handleError)
  }

  def listAccounts(request: ListAccountsRequest): ServiceResponse[ListAccountsResponse] = IO {
    keyStore.listAccounts
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
          if duration.isZero then unlockedWallets.addForever(request.address, wallet)
          else unlockedWallets.add(request.address, wallet, duration)
        )

        UnlockAccountResponse(true)
      }
  }

  def lockAccount(request: LockAccountRequest): ServiceResponse[LockAccountResponse] = IO {
    unlockedWallets.remove(request.address)
    Right(LockAccountResponse(true))
  }

  def sign(request: SignRequest): ServiceResponse[SignResponse] = IO {
    import request.*

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
    import req.*
    signature
      .publicKey(getMessageToSign(message))
      .map { publicKey =>
        Right(EcRecoverResponse(Address(crypto.kec256(publicKey))))
      }
      .getOrElse(Left(InvalidParams("unable to recover address")))
  }

  def sendTransaction(
      request: SendTransactionWithPassphraseRequest
  ): ServiceResponse[SendTransactionWithPassphraseResponse] =
    IO(unlockWithPassphrase(request.tx.from, request.passphrase)).flatMap {
      case Right(wallet) => send(request.tx, wallet).map(_.map(SendTransactionWithPassphraseResponse.apply))
      case Left(err)     => IO.pure(Left(err))
    }

  def sendTransaction(request: SendTransactionRequest): ServiceResponse[SendTransactionResponse] =
    IO(request.tx.from.flatMap(unlockedWallets.get)).flatMap {
      case Some(wallet) => send(request.tx, wallet).map(_.map(SendTransactionResponse.apply))
      case None         => IO.pure(Left(AccountLocked))
    }

  /** eth_signTransaction / personal_signTransaction: go-ethereum `TransactionAPI.SignTransaction`
    * (internal/ethapi/api.go:1890-1935). `gas`, the fee fields and `nonce` must be given; the rest is defaulted as for
    * a send (blobs allowed, sidecar version 1 iff Osaka is active at the head and blobs were passed); the transaction's
    * fee must be within the RPC fee cap (`checkTxFee`, api.go:1917; [[RpcTxSubmission]]); the signed transaction is
    * returned, not submitted. personal_signTransaction (geth's former `PersonalAPI.SignTransaction`) first requires
    * `from` ("sender not specified") and signs with the given passphrase instead of an unlocked account.
    */
  def signTransaction(request: SignTransactionRequest): ServiceResponse[SignTransactionResponse] =
    val args = request.tx
    (for
      _ <- EitherT.cond[IO](
        request.passphrase.isEmpty || args.from.isDefined,
        (),
        LogicError("sender not specified")
      )
      _ <- EitherT.fromEither[IO](TxDefaults.checkSignable(args).left.map(_.toJsonRpcError))
      head <- EitherT(chainHead)
      version = if args.blobs.exists(_.nonEmpty) then BlobTxSidecar.versionAt(head) else BlobTxSidecar.Version0
      (filled, tx) <- prepare(args, head, TxDefaults.SidecarConfig(blobSidecarAllowed = true, version))
      _ <- EitherT.fromEither[IO](ethTxService.rpcTxSubmission.feeCapRejection(tx).map(LogicError(_)).toLeft(()))
      wallet <- EitherT.fromEither[IO](signingWallet(args.from, request.passphrase))
      stx = signWith(wallet, tx, head)
    yield SignTransactionResponse(SignTransactionResult(stx, sidecarOf(filled)))).value
      .recover { case _: MissingNodeException => Left(JsonRpcError.NodeNotFound) }

  /** eth_fillTransaction: go-ethereum `TransactionAPI.FillTransaction` (internal/ethapi/api.go:1710-1727). Every
    * default a send would fill (blobs allowed, sidecar version by Osaka at the head), returned unsigned with its RLP.
    * Needs no account, and, as in go-ethereum, meets no RPC fee cap.
    */
  def fillTransaction(request: FillTransactionRequest): ServiceResponse[FillTransactionResponse] =
    (for
      head <- EitherT(chainHead)
      sidecarConfig = TxDefaults.SidecarConfig(blobSidecarAllowed = true, BlobTxSidecar.versionAt(head))
      (filled, tx) <- prepare(request.tx, head, sidecarConfig)
    yield FillTransactionResponse(SignTransactionResult.unsigned(tx, sidecarOf(filled)))).value
      .recover { case _: MissingNodeException => Left(JsonRpcError.NodeNotFound) }

  /** eth_sendTransaction / personal_sendTransaction: go-ethereum `TransactionAPI.SendTransaction`
    * (internal/ethapi/api.go:1688-1719). Blob transactions are refused (geth: "This API is not capable for submitting
    * blob transaction with sidecar"). With `nonce` absent, defaulting it, signing and submitting run under a per-sender
    * lock (geth `nonceLock`), so two concurrent sends from one account cannot be given the same pool nonce.
    */
  private def send(args: TxArgs, wallet: Wallet): IO[Either[JsonRpcError, ByteString]] =
    val run: IO[Either[JsonRpcError, ByteString]] =
      if args.isEip4844 then IO.pure(Left(LogicError("signing blob transactions not supported")))
      else
        (for
          head <- EitherT(chainHead)
          (_, tx) <- prepare(args.copy(from = Some(wallet.address)), head, TxDefaults.SidecarConfig.NotAllowed)
          stx = signWith(wallet, tx, head)
          hash <- EitherT(submitLocal(stx))
        yield hash).value
    val locked = if args.nonce.isEmpty then nonceLock(wallet.address).permit.surround(run) else run
    locked.recover { case _: MissingNodeException => Left(JsonRpcError.NodeNotFound) }

  /** Type gate, go-ethereum `setDefaults`, then `ToTransaction(DynamicFeeTxType)`: legacy when a gas price was given or
    * defaulted (before London), EIP-1559 otherwise, unless the fields or `type` select another type.
    */
  private def prepare(
      args: TxArgs,
      head: BlockHeader,
      sidecar: TxDefaults.SidecarConfig
  ): EitherT[IO, JsonRpcError, (TxArgs, Transaction)] =
    for
      _ <- EitherT.fromEither[IO](TxDefaults.checkTypeActive(args, head).left.map(_.toJsonRpcError))
      filled <- EitherT(TxDefaults.setDefaults(args, defaultsEnv(head), sidecar))
      tx <- EitherT.fromEither[IO](
        filled.toTransaction(blockchainConfig.chainId, TxArgs.DynamicFeeType).left.map(_.toJsonRpcError)
      )
    yield (filled, tx)

  /** The sidecar a filled request carries: only when blobs were passed. (geth attaches an empty sidecar to a signed
    * blob transaction given only versioned hashes, whose network form then carries no blobs; that form is not one a
    * pool accepts, so here such a transaction is returned in its canonical form instead.)
    */
  private def sidecarOf(filled: TxArgs): Option[BlobTxSidecar] =
    if filled.isEip4844 then BlobTxSidecar.fromArgs(filled) else None

  private def signWith(wallet: Wallet, tx: Transaction, head: BlockHeader): SignedTransaction =
    wallet.signTx(tx, TxDefaults.legacySigningChainId(head)).tx

  /** Hand a locally signed transaction to the pool, through the same admission eth_sendRawTransaction applies, as
    * go-ethereum's `SendTransaction` ends in `SubmitTransaction` (api.go:1640-1702): the RPC fee cap and replay
    * protection (#1629 S0b), the pool's rules at the head (S0: `EthTxService.admitLocal` →
    * `TxAdmission.validateLocal`), then the pool's rules against what it holds (`EthTxService.submitToPool`). A refused
    * transaction is never pooled or announced. eth_sendTransaction never carries a blob sidecar.
    */
  private def submitLocal(stx: SignedTransaction): IO[Either[JsonRpcError, ByteString]] =
    IO(ethTxService.admitLocal(stx, sidecar = None)).flatMap {
      case Left(error) => IO.pure(Left(error))
      case Right(admission) =>
        log.debug("Trying to add personal transaction: {}", stx.hash.toHex)
        ethTxService.submitToPool(txPool, stx, admission, None)
    }

  private def defaultsEnv(head: BlockHeader): TxDefaults.Env =
    TxDefaults.Env(
      head = head,
      config = blockchainConfig,
      // go-ethereum `b.SuggestGasTipCap`, the oracle eth_gasPrice and eth_maxPriorityFeePerGas answer from
      suggestGasPrice = () => ethTxService.suggestGasPrice(),
      suggestGasTipCap = () => ethTxService.gasPriceOracle.suggestTipCap(),
      poolNonce = address => poolNonce(address, head),
      estimateGas = args => gasEstimator.estimateGas(args, head)
    )

  private def chainHead: IO[Either[JsonRpcError, BlockHeader]] =
    IO(blockchainReader.getBestBlock.map(_.header).toRight(LogicError("chain head not available")))

  /** go-ethereum `GetPoolNonce` ([[EthTxService.nextPoolNonce]]): the sender's nonce at the head advanced over its
    * pooled transactions with consecutive nonces from it, the nonce `eth_getTransactionCount(addr, "pending")` answers.
    * A nonce-gapped pooled transaction does not count.
    */
  private def poolNonce(address: Address, head: BlockHeader): IO[BigInt] =
    given timeout: Timeout = Timeout(txPoolConfig.pendingTxManagerQueryTimeout)
    given sc: Scheduler = scheduler
    txPool
      .askForTyped[PendingTransactionsResponse](PendingTransactionsManager.GetPendingTransactionsReq(_))
      .map { pendingTxs =>
        val stateNonce =
          getAccount(address, head).map(_.nonce.toBigInt).getOrElse(blockchainConfig.accountStartNonce.toBigInt)
        EthTxService.nextPoolNonce(pendingTxs.pendingTransactions, address, stateNonce)
      }

  private val nonceLocks = new ConcurrentHashMap[Address, Semaphore[IO]]()

  private def nonceLock(address: Address): Semaphore[IO] =
    nonceLocks.computeIfAbsent(address, _ => Semaphore.in[SyncIO, IO](1).unsafeRunSync())

  /** personal_*: the keystore account opened with the passphrase; eth_*: an account unlocked beforehand. */
  private def signingWallet(from: Option[Address], passphrase: Option[String]): Either[JsonRpcError, Wallet] =
    passphrase match
      case Some(pass) => unlockWithPassphrase(from, pass)
      case None       => from.flatMap(unlockedWallets.get).toRight(AccountLocked)

  private def unlockWithPassphrase(from: Option[Address], passphrase: String): Either[JsonRpcError, Wallet] =
    from match
      case Some(address) => keyStore.unlockAccount(address, passphrase).left.map(handleError)
      case None          => Left(AccountLocked)

  private def getAccount(address: Address, head: BlockHeader): Option[Account] =
    blockchainReader.getAccount(blockchainReader.getBestBranch, address, head.number.value)

  private def getMessageToSign(message: ByteString) =
    val prefixed: Array[Byte] =
      0x19.toByte +:
        s"Ethereum Signed Message:\n${message.length}".getBytes ++:
        message.toArray[Byte]

    crypto.kec256(prefixed)

  private val handleError: PartialFunction[KeyStore.KeyStoreError, JsonRpcError] = {
    case KeyStore.DecryptionFailed              => InvalidPassphrase
    case KeyStore.KeyNotFound                   => KeyNotFound
    case KeyStore.PassPhraseTooShort(minLength) => PassPhraseTooShort(minLength)
    case KeyStore.IOError(msg)                  => LogicError(msg)
    case KeyStore.DuplicateKeySaved             => LogicError("account already exists")
  }
