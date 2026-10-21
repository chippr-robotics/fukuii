package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import cats.data.EitherT
import cats.effect.IO

import com.chipprbots.ethereum.consensus.engine.BlobGasUtils
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlobTransaction
import com.chipprbots.ethereum.domain.LegacyTransaction
import com.chipprbots.ethereum.domain.SetCodeTransaction
import com.chipprbots.ethereum.domain.Transaction
import com.chipprbots.ethereum.domain.TransactionWithAccessList
import com.chipprbots.ethereum.domain.TransactionWithDynamicFee
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.GasPrice
import com.chipprbots.ethereum.transactions.TxAdmission
import com.chipprbots.ethereum.transactions.TxTypeGate
import com.chipprbots.ethereum.utils.BlockchainConfig

/** go-ethereum `TransactionArgs.setDefaults` (internal/ethapi/transaction_args.go:103-218) for eth_sendTransaction,
  * eth_signTransaction, eth_fillTransaction and personal_sendTransaction / personal_signTransaction.
  *
  * Same order as geth: blob sidecar, fee defaults, value, nonce, data/input, blob count, create checks, gas estimate,
  * chain id. Every rule that needs no chain state was already applied at decode time ([[TxArgs.validateForSend]]); they
  * are re-checked here at geth's position so a request that reaches this point through another path gets the same
  * answer.
  *
  * Errors are -32000 (geth returns them as plain errors), except those S1 marks as argument-shape errors (-32602).
  */
object TxDefaults:

  /** The chain state and node services the defaults are drawn from, read once per request.
    *
    * @param head
    *   the chain head header (geth `b.CurrentHeader()`): London, Cancun and Osaka are judged at the head, as geth does
    * @param suggestGasPrice
    *   the legacy gas-price oracle (eth_gasPrice); geth `SuggestGasTipCap` before London, where tip and price coincide
    * @param suggestGasTipCap
    *   the tip oracle (eth_maxPriorityFeePerGas)
    * @param poolNonce
    *   geth `GetPoolNonce`: the next nonce for `from`, counting its pending transactions
    * @param estimateGas
    *   geth `DoEstimateGas` against the latest block, on the arguments as filled so far
    */
  final case class Env(
      head: BlockHeader,
      config: BlockchainConfig,
      suggestGasPrice: () => BigInt,
      suggestGasTipCap: () => BigInt,
      poolNonce: Address => IO[BigInt],
      estimateGas: TxArgs => IO[Either[JsonRpcError, BigInt]]
  )

  /** go-ethereum `sidecarConfig`: whether `blobs` may be passed, and the sidecar version to build. */
  final case class SidecarConfig(blobSidecarAllowed: Boolean, blobSidecarVersion: Byte)

  object SidecarConfig:
    /** eth_sendTransaction: geth passes the zero `sidecarConfig{}` (api.go:1708). */
    val NotAllowed: SidecarConfig = SidecarConfig(blobSidecarAllowed = false, BlobTxSidecar.Version0)

  // go-ethereum internal/ethapi/transaction_args.go / api.go messages not already in TxArgsError
  val BlobsNotSupported: TxArgsError = TxArgsError(""""blobs" is not supported for this RPC method""")
  val GasPriceZeroAfterLondon: TxArgsError = TxArgsError("gasPrice must be non-zero after london fork")
  val DynamicFeeBeforeLondon: TxArgsError =
    TxArgsError("maxFeePerGas and maxPriorityFeePerGas are not valid before London is active")
  def TooManyBlobs(have: Int): TxArgsError =
    TxArgsError(s"too many blobs in transaction (have=$have, max=${BlobTxSidecar.MaxBlobsPerTx})")

  /** London is active at `head`: geth `ChainConfig.IsLondon(head.Number)`. fukuii schedules the base fee on
    * `olympiaBlockNumber` for both chain families (on an ETH-family chain it is London's block; on ETC it is Olympia,
    * ECIP-1111), so ETC and Mordor, where Olympia is unscheduled, take geth's pre-London path: legacy gas price.
    */
  def isLondon(head: BlockHeader)(using config: BlockchainConfig): Boolean =
    head.number.value >= config.forkBlockNumbers.olympiaBlockNumber

  /** The transaction type the request asks for by its own fields, before any default is filled: the explicit `type`,
    * else geth's `ToTransaction` inference over the fields that select a fee-market or list type. `None` means the type
    * is decided by the fee defaults (legacy before London, 0x2 after).
    */
  def requestedType(args: TxArgs): Option[Byte] =
    args.txType.orElse {
      if args.authorizationList.isDefined then Some(TxArgs.SetCodeType)
      else if args.blobVersionedHashes.isDefined || args.blobs.isDefined || args.maxFeePerBlobGas.isDefined then
        Some(TxArgs.BlobType)
      else if args.hasDynamicFee then Some(TxArgs.DynamicFeeType)
      else if args.accessList.isDefined then Some(TxArgs.AccessListType)
      else None
    }

  /** Reject a request whose own fields select a transaction type the chain does not run at `head` (ETC/Mordor before
    * Olympia: 0x2, 0x3, 0x4).
    *
    * The type predicates are #1629 S0's [[TxTypeGate]], the pool's own. DEVIATION from geth for sign/fill: geth applies
    * the type gate only in the pool, so it would sign a 0x2 transaction before London (given both fee fields), and
    * before London it fills `gasPrice` for an `authorizationList` or blob request and silently builds a legacy
    * transaction without the list. Both produce transactions the node's own pool refuses; here every send/sign/fill
    * path refuses them up front with the pool's message. (fail-loudly, as S1)
    */
  def checkTypeActive(args: TxArgs, head: BlockHeader)(using config: BlockchainConfig): Either[TxArgsError, Unit] =
    requestedType(args) match
      case Some(t) if TxArgs.SupportedTypes.contains(t) && !TxTypeGate.activeTypes(head).contains(t) =>
        val fork = TxTypeGate.inactiveFork(template(t), head).getOrElse("a later fork")
        Left(TxArgsError(TxAdmission.Rejection.TypeNotSupported(t & 0xff, fork).message))
      case _ => Right(())

  /** A transaction of type `t` with every field zero, so [[TxTypeGate]] (which judges transactions, as the pool does)
    * can name the fork a requested type waits for. Only its type is read.
    */
  private def template(t: Byte): Transaction =
    val to = Some(Address(0))
    t match
      case TxArgs.AccessListType =>
        TransactionWithAccessList(0, 0, GasPrice(0), GasAmount(0), to, 0, ByteString.empty, Nil)
      case TxArgs.DynamicFeeType => TransactionWithDynamicFee(0, 0, 0, 0, GasAmount(0), to, 0, ByteString.empty, Nil)
      case TxArgs.BlobType       => BlobTransaction(0, 0, 0, 0, GasAmount(0), to, 0, ByteString.empty, Nil, 0, Nil)
      case TxArgs.SetCodeType    => SetCodeTransaction(0, 0, 0, 0, GasAmount(0), to, 0, ByteString.empty, Nil, Nil)
      case _                     => LegacyTransaction(0, GasPrice(0), GasAmount(0), to, 0, ByteString.empty)

  /** Fill every absent field, go-ethereum `setDefaults` order. */
  def setDefaults(args: TxArgs, env: Env, sidecar: SidecarConfig): IO[Either[JsonRpcError, TxArgs]] =
    def lift(e: Either[TxArgsError, TxArgs]): EitherT[IO, JsonRpcError, TxArgs] =
      EitherT.fromEither[IO](e.left.map(_.toJsonRpcError))
    def check(cond: Boolean, err: => TxArgsError): EitherT[IO, JsonRpcError, Unit] =
      EitherT.cond[IO](cond, (), err.toJsonRpcError)

    (for
      a <- lift(setBlobTxSidecar(args, sidecar))
      a <- lift(setFeeDefaults(a, env))
      a <- EitherT.rightT[IO, JsonRpcError](a.copy(value = a.value.orElse(Some(BigInt(0)))))
      a <- a.nonce match
        case Some(_) => EitherT.rightT[IO, JsonRpcError](a)
        case None =>
          EitherT
            .liftF[IO, JsonRpcError, BigInt](env.poolNonce(a.from.getOrElse(Address(0))))
            .map(n => a.copy(nonce = Some(n)))
      _ <- check(a.data.isEmpty || a.input.isEmpty || a.data == a.input, TxArgsError.DataInputMismatch)
      _ <- check(!a.blobVersionedHashes.exists(_.isEmpty), TxArgsError.EmptyBlobHashes)
      _ <- check(
        !a.blobVersionedHashes.exists(_.size > BlobTxSidecar.MaxBlobsPerTx),
        TooManyBlobs(a.blobVersionedHashes.map(_.size).getOrElse(0))
      )
      _ <- check(a.to.isDefined || a.blobVersionedHashes.isEmpty, TxArgsError.BlobTxMissingTo)
      _ <- check(a.to.isDefined || a.callData.nonEmpty, TxArgsError.CreateWithoutData)
      _ <- check(a.to.isDefined || a.authorizationList.isEmpty, TxArgsError.SetCodeCreate)
      a <- a.gas match
        case Some(_) => EitherT.rightT[IO, JsonRpcError](a)
        case None    => EitherT(env.estimateGas(a)).map(g => a.copy(gas = Some(GasAmount(g))))
      a <- a.chainId match
        case Some(have) if have.value != env.config.chainId.value =>
          EitherT.leftT[IO, TxArgs](TxArgsError.ChainIdMismatch(have.value, env.config.chainId.value).toJsonRpcError)
        case Some(_) => EitherT.rightT[IO, JsonRpcError](a)
        case None    => EitherT.rightT[IO, JsonRpcError](a.copy(chainId = Some(env.config.chainId)))
    yield a).value

  /** go-ethereum `setBlobTxSidecar` (transaction_args.go:287-390). */
  def setBlobTxSidecar(args: TxArgs, sidecar: SidecarConfig): Either[TxArgsError, TxArgs] =
    if args.blobs.isEmpty then Right(args)
    else if !sidecar.blobSidecarAllowed then Left(BlobsNotSupported)
    else BlobTxSidecar.complete(args, sidecar.blobSidecarVersion)

  /** go-ethereum `setFeeDefaults` (transaction_args.go:221-283), `setCancunFeeDefaults` (:286-297) and
    * `setLondonFeeDefaults` (:300-325).
    *
    * fukuii additions, for the explicit `type` geth does not decode:
    *   - `type` 0x0 / 0x1 without fee fields after London: `gasPrice` from the oracle instead of the 1559 pair, which
    *     those types cannot carry.
    *   - before London, an `accessList` without `type` or `gasPrice` gets `type` 0x1 with the defaulted `gasPrice`;
    *     geth would build a legacy transaction and drop the list (and S1 rejects gasPrice + accessList without a type).
    */
  def setFeeDefaults(args: TxArgs, env: Env): Either[TxArgsError, TxArgs] =
    given config: BlockchainConfig = env.config
    val head = env.head
    for
      _ <- Either.cond(!args.maxFeePerBlobGas.contains(BigInt(0)), (), TxArgsError.ZeroMaxFeePerBlobGas)
      a = setCancunFeeDefaults(args, head)
      _ <- Either.cond(!(a.gasPrice.isDefined && a.hasDynamicFee), (), TxArgsError.GasPriceAndDynamicFee)
      _ <- Either.cond(
        !(a.gasPrice.isDefined && a.authorizationList.isDefined),
        (),
        TxArgsError.GasPriceAndAuthorizationList
      )
      filled <- (a.gasPrice, a.maxFeePerGas, a.maxPriorityFeePerGas) match
        // the transaction specifies its 1559 fees completely: sanity-check, fill nothing
        case (None, Some(maxFee), Some(tip)) =>
          if maxFee == 0 then Left(TxArgsError.ZeroMaxFeePerGas)
          else if maxFee < tip then Left(TxArgsError.MaxFeeBelowTip(maxFee, tip))
          else Right(a)
        case (Some(gp), _, _) =>
          if gp.value == 0 && isLondon(head) then Left(GasPriceZeroAfterLondon) else Right(a)
        case _ if a.txType.exists(t => t == TxArgs.LegacyType || t == TxArgs.AccessListType) =>
          Right(a.copy(gasPrice = Some(GasPrice(env.suggestGasPrice()))))
        case _ if isLondon(head) => setLondonFeeDefaults(a, head, env)
        case _ =>
          if a.hasDynamicFee then Left(DynamicFeeBeforeLondon)
          else
            val typed = if a.accessList.isDefined && a.txType.isEmpty then Some(TxArgs.AccessListType) else a.txType
            Right(a.copy(gasPrice = Some(GasPrice(env.suggestGasPrice())), txType = typed))
    yield filled

  /** Blob fee cap default: twice the head's blob base fee, once Cancun is active at the head. */
  private def setCancunFeeDefaults(args: TxArgs, head: BlockHeader)(using config: BlockchainConfig): TxArgs =
    if config.isCancunTimestamp(head.unixTimestamp) && args.blobVersionedHashes.isDefined &&
      args.maxFeePerBlobGas.isEmpty
    then
      val excess = head.excessBlobGas.getOrElse(BigInt(0))
      val blobBaseFee = BlobGasUtils.getBlobGasPrice(excess, head.unixTimestamp, config)
      args.copy(maxFeePerBlobGas = Some(blobBaseFee * 2))
    else args

  /** Tip from the oracle; fee cap `tip + 2 * baseFee` of the head block. */
  private def setLondonFeeDefaults(args: TxArgs, head: BlockHeader, env: Env): Either[TxArgsError, TxArgs] =
    head.baseFee match
      case None =>
        // geth dereferences head.BaseFee unconditionally here; a London head without one is a broken chain.
        Left(TxArgsError(s"head block ${head.number.value} is past London but has no base fee"))
      case Some(baseFee) =>
        val tip = args.maxPriorityFeePerGas.getOrElse(env.suggestGasTipCap())
        val maxFee = args.maxFeePerGas.getOrElse(tip + baseFee * 2)
        if maxFee < tip then Left(TxArgsError.MaxFeeBelowTip(maxFee, tip))
        else Right(args.copy(maxPriorityFeePerGas = Some(tip), maxFeePerGas = Some(maxFee)))

  /** Arguments geth's eth_signTransaction requires before it fills anything (api.go:1905-1913). */
  def checkSignable(args: TxArgs): Either[TxArgsError, Unit] =
    if args.gas.isEmpty then Left(TxArgsError("gas not specified"))
    else if args.gasPrice.isEmpty && (args.maxPriorityFeePerGas.isEmpty || args.maxFeePerGas.isEmpty) then
      Left(TxArgsError("missing gasPrice or maxFeePerGas/maxPriorityFeePerGas"))
    else if args.nonce.isEmpty then Left(TxArgsError("nonce not specified"))
    else Right(())

  /** The chain id a legacy transaction is signed with: EIP-155 protection from `eip155BlockNumber` at the head, none
    * before (pre-EIP-155 ETC history). geth always signs EIP-155 (`LatestSignerForChainID`).
    */
  def legacySigningChainId(head: BlockHeader)(using config: BlockchainConfig): Option[BigInt] =
    Option.when(head.number.value >= config.forkBlockNumbers.eip155BlockNumber)(config.chainId.value)
