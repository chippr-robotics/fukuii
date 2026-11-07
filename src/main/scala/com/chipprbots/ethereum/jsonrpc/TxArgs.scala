package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.AccessListItem
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlobTransaction
import com.chipprbots.ethereum.domain.BlobVersionedHash
import com.chipprbots.ethereum.domain.ChainId
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.GasPrice
import com.chipprbots.ethereum.domain.LegacyTransaction
import com.chipprbots.ethereum.domain.SetCodeAuthorization
import com.chipprbots.ethereum.domain.SetCodeTransaction
import com.chipprbots.ethereum.domain.Transaction
import com.chipprbots.ethereum.domain.TransactionWithAccessList
import com.chipprbots.ethereum.domain.TransactionWithDynamicFee

/** A rule violation in a [[TxArgs]] object, carrying go-ethereum's message verbatim where geth has one.
  *
  * No JSON-RPC error code is baked in: geth returns these from `setDefaults` / `CallDefaults` as -32000, while some
  * call-style endpoints want -32602. Each endpoint (S2 send/sign/fill, S3 call/estimate/trace, S4 simulate) picks the
  * code via [[toLogicError]] or [[toInvalidParams]]. Errors created with `invalidParams = true` are argument-shape
  * errors that must always surface as -32602; [[toJsonRpcError]] honours that and otherwise falls back to -32000.
  */
final case class TxArgsError(message: String, invalidParams: Boolean = false):
  def toLogicError: JsonRpcError = JsonRpcError.LogicError(message)
  def toInvalidParams: JsonRpcError = JsonRpcError.InvalidParams(message)
  def toJsonRpcError: JsonRpcError = if invalidParams then toInvalidParams else toLogicError

object TxArgsError:
  // go-ethereum internal/ethapi/transaction_args.go (setDefaults / setFeeDefaults / CallDefaults)
  val DataInputMismatch: TxArgsError = TxArgsError(
    """both "data" and "input" are set and not equal. Please use "input" to pass transaction call data"""
  )
  val GasPriceAndDynamicFee: TxArgsError =
    TxArgsError("both gasPrice and (maxFeePerGas or maxPriorityFeePerGas) specified")
  val GasPriceAndAuthorizationList: TxArgsError = TxArgsError("both gasPrice and authorizationList specified")
  val ZeroMaxFeePerBlobGas: TxArgsError = TxArgsError("maxFeePerBlobGas, if specified, must be non-zero")
  val ZeroMaxFeePerGas: TxArgsError = TxArgsError("maxFeePerGas must be non-zero")
  def MaxFeeBelowTip(maxFee: BigInt, tip: BigInt): TxArgsError =
    TxArgsError(s"maxFeePerGas (${hex(maxFee)}) < maxPriorityFeePerGas (${hex(tip)})")
  val EmptyBlobHashes: TxArgsError = TxArgsError("need at least 1 blob for a blob transaction")
  val BlobTxMissingTo: TxArgsError = TxArgsError("""missing "to" in blob transaction""")
  val CreateWithoutData: TxArgsError = TxArgsError("contract creation without any data provided")
  val SetCodeCreate: TxArgsError =
    TxArgsError("""authorizationList provided for contract creation, but "to" field is missing""")
  def ChainIdMismatch(have: BigInt, want: BigInt): TxArgsError =
    TxArgsError(s"chainId does not match node's (have=$have, want=$want)")
  // go-ethereum core/error.go
  val BlobTxCreate: TxArgsError = TxArgsError("blob transaction of type create")
  val SetCodeTxCreate: TxArgsError = TxArgsError("EIP-7702 transaction cannot be used to create contract")
  // Explicit `type` outside 0x0-0x4: a request-shape error, so -32602 (geth's pool returns the same text as -32000).
  val TxTypeNotSupported: TxArgsError = TxArgsError("transaction type not supported", invalidParams = true)
  // fukuii-defined: an unsupported `defaultType` is a bug in the calling endpoint, not in the request, so -32000.
  def UnsupportedDefaultType(t: Byte): TxArgsError =
    TxArgsError(s"unsupported default transaction type ${hex(BigInt(t & 0xff))}")

  // fukuii-defined, deliberate deviation from geth (which silently builds a legacy tx and drops the blobs):
  // fail-loudly rule, user decision 2026-10-18 (#1629). Always -32602.
  val GasPriceWithBlobFields: TxArgsError =
    TxArgsError("blob fields require a blob transaction; gasPrice is not allowed", invalidParams = true)

  // fukuii-defined (geth's TransactionArgs has no `type` field, so it has no equivalent rule)
  // request-shape error -> -32602
  def FieldNotValidForType(field: String, txType: Byte): TxArgsError =
    TxArgsError(s"""field "$field" is not valid for transaction type ${hex(BigInt(txType))}""", invalidParams = true)
  // fukuii-defined: geth dereferences nil here because ToTransaction assumes setDefaults ran. Stays -32000: a missing
  // field at toTransaction time means the endpoint skipped its defaults step (caller bug), not a bad request.
  def MissingField(field: String, txType: Byte): TxArgsError =
    TxArgsError(s"""missing "$field" for transaction type ${hex(BigInt(txType))}""")

  // hexutil.Big's String() form, which geth's fmt %v renders in the fee errors
  private def hex(v: BigInt): String = s"0x${v.toString(16)}"

/** Arguments of a transaction or message call, as received over JSON-RPC.
  *
  * Mirrors execution-apis `GenericTransaction` (src/schemas/transaction.yaml) and go-ethereum's
  * `internal/ethapi/transaction_args.go` `TransactionArgs`, plus the spec's `type` field (which geth does not decode).
  *
  * Every field is optional and presence is significant: geth infers the transaction type from `!= nil`, so an empty
  * list (`"accessList": []`) is present and differs from an absent one. JSON `null` decodes as absent.
  *
  * This is pure data. Nothing here reads chain state, fork configuration or the pool; defaults filling (nonce, gas,
  * fees, chainId) is the caller's job and happens through [[withDefaults]] before [[toTransaction]].
  *
  * Decoding/encoding lives in [[TxArgsJson]].
  */
final case class TxArgs(
    from: Option[Address] = None,
    to: Option[Address] = None,
    gas: Option[GasAmount] = None,
    gasPrice: Option[GasPrice] = None,
    maxFeePerGas: Option[BigInt] = None,
    maxPriorityFeePerGas: Option[BigInt] = None,
    maxFeePerBlobGas: Option[BigInt] = None,
    value: Option[BigInt] = None,
    nonce: Option[BigInt] = None,
    data: Option[ByteString] = None,
    input: Option[ByteString] = None,
    accessList: Option[List[AccessListItem]] = None,
    chainId: Option[ChainId] = None,
    txType: Option[Byte] = None,
    blobVersionedHashes: Option[List[BlobVersionedHash]] = None,
    blobs: Option[List[ByteString]] = None,
    commitments: Option[List[ByteString]] = None,
    proofs: Option[List[ByteString]] = None,
    authorizationList: Option[List[SetCodeAuthorization]] = None
):
  import TxArgs.*
  import TxArgsError.*

  /** Call data, `input` preferred over `data` (geth `args.data()`). Does not check that they agree; see
    * [[validateForSend]].
    */
  def callData: ByteString = input.orElse(data).getOrElse(ByteString.empty)

  /** Whether any EIP-1559 fee field is present. */
  def hasDynamicFee: Boolean = maxFeePerGas.isDefined || maxPriorityFeePerGas.isDefined

  /** Whether the arguments carry EIP-4844 fields (geth `IsEIP4844`). */
  def isEip4844: Boolean = blobVersionedHashes.isDefined || maxFeePerBlobGas.isDefined

  /** Resolve the transaction type.
    *
    * The JSON decoder never sets `txType` (user decision 2026-10-21, #1660 item 10: go-ethereum's `TransactionArgs` has
    * no `type` field, transaction_args.go:42-71, and ignores the key everywhere), so a request's type is always
    * inferred. A `txType` set programmatically (internal callers) still wins and is checked against the fields present.
    * Inference follows go-ethereum `ToTransaction(defaultType)` (transaction_args.go:537-560) exactly:
    *   - authorizationList present, or defaultType 0x4 -> 0x4
    *   - blobVersionedHashes present (or blobs, whose hashes S2 derives), or defaultType 0x3 -> 0x3
    *   - maxFeePerGas or maxPriorityFeePerGas present, or defaultType 0x2 -> 0x2 (geth tests maxFeePerGas only, because
    *     setDefaults has always filled it by then; testing either field gives the same post-defaults result)
    *   - accessList present, or defaultType 0x1 -> 0x1
    *   - otherwise legacy
    *   - then: gasPrice present -> legacy, overriding all of the above, except that gasPrice with an accessList is an
    *     EIP-2930 transaction (transaction_args.go:555-560)
    *   - DEVIATION from geth (deliberate fail-loudly rule, user decision 2026-10-18): where geth's override would
    *     silently drop blob fields given with gasPrice, this returns [[TxArgsError.GasPriceWithBlobFields]] (-32602)
    *     instead.
    *
    * gasPrice together with maxFeePerGas/maxPriorityFeePerGas, or with authorizationList, is an error in either case.
    */
  def resolveType(defaultType: Byte = LegacyType): Either[TxArgsError, Byte] =
    for
      _ <- checkFeeFamilies
      t <- txType match
        case Some(explicit) => checkExplicitType(explicit).map(_ => explicit)
        case None           => inferType(defaultType)
    yield t

  private def checkFeeFamilies: Either[TxArgsError, Unit] =
    if gasPrice.isDefined && hasDynamicFee then Left(GasPriceAndDynamicFee)
    else if gasPrice.isDefined && authorizationList.isDefined then Left(GasPriceAndAuthorizationList)
    else Right(())

  private def inferType(defaultType: Byte): Either[TxArgsError, Byte] =
    if !SupportedTypes.contains(defaultType) then Left(UnsupportedDefaultType(defaultType))
    else
      val inferred: Byte =
        if authorizationList.isDefined || defaultType == SetCodeType then SetCodeType
        else if blobVersionedHashes.isDefined || blobs.isDefined || defaultType == BlobType then BlobType
        else if hasDynamicFee || defaultType == DynamicFeeType then DynamicFeeType
        else if accessList.isDefined || defaultType == AccessListType then AccessListType
        else LegacyType
      if gasPrice.isEmpty then Right(inferred)
      else if blobVersionedHashes.isDefined || blobs.isDefined then Left(GasPriceWithBlobFields)
      else if accessList.isDefined then Right(AccessListType)
      else Right(LegacyType)

  private def checkExplicitType(t: Byte): Either[TxArgsError, Unit] =
    val present: List[(String, Boolean)] = List(
      "gasPrice" -> gasPrice.isDefined,
      "maxFeePerGas" -> maxFeePerGas.isDefined,
      "maxPriorityFeePerGas" -> maxPriorityFeePerGas.isDefined,
      "accessList" -> accessList.isDefined,
      "maxFeePerBlobGas" -> maxFeePerBlobGas.isDefined,
      "blobVersionedHashes" -> blobVersionedHashes.isDefined,
      "blobs" -> blobs.isDefined,
      "commitments" -> commitments.isDefined,
      "proofs" -> proofs.isDefined,
      "authorizationList" -> authorizationList.isDefined
    )
    AllowedFields.get(t) match
      case None => Left(TxTypeNotSupported)
      case Some(allowed) =>
        present.collectFirst { case (name, true) if !allowed.contains(name) => name } match
          case Some(field) => Left(FieldNotValidForType(field, t))
          case None =>
            if to.isEmpty && t == BlobType then Left(BlobTxCreate)
            else if to.isEmpty && t == SetCodeType then Left(SetCodeTxCreate)
            else Right(())

  /** State-free checks go-ethereum applies in `setDefaults` / `setFeeDefaults` for eth_sendTransaction,
    * eth_signTransaction and eth_fillTransaction. Run this on the args as received, before [[withDefaults]].
    *
    * Not covered here (they need chain state or fork config, S2): maximum blob count, London / Cancun activation, fee
    * defaults, chainId default, blob sidecar count/version/KZG checks.
    */
  def validateForSend: Either[TxArgsError, TxArgs] =
    for
      // same order as geth: setFeeDefaults, then the data/input and blob checks of setDefaults, then create checks
      _ <- Either.cond(!maxFeePerBlobGas.contains(BigInt(0)), (), ZeroMaxFeePerBlobGas)
      _ <- checkFeeFamilies
      _ <- checkDynamicFeeSanity
      _ <- Either.cond(data.isEmpty || input.isEmpty || data == input, (), DataInputMismatch)
      _ <- Either.cond(!blobVersionedHashes.exists(_.isEmpty), (), EmptyBlobHashes)
      _ <- to match
        case Some(_)                               => Right(())
        case None if blobVersionedHashes.isDefined => Left(BlobTxMissingTo)
        case None if callData.isEmpty              => Left(CreateWithoutData)
        case None if authorizationList.isDefined   => Left(SetCodeCreate)
        case None                                  => Right(())
      _ <- resolveType()
    yield this

  // geth setFeeDefaults: only when both 1559 fields are given and gasPrice is not
  private def checkDynamicFeeSanity: Either[TxArgsError, Unit] =
    (gasPrice, maxFeePerGas, maxPriorityFeePerGas) match
      case (None, Some(maxFee), Some(tip)) =>
        if maxFee == 0 then Left(ZeroMaxFeePerGas)
        else if maxFee < tip then Left(MaxFeeBelowTip(maxFee, tip))
        else Right(())
      case _ => Right(())

  /** State-free checks go-ethereum applies in `CallDefaults` for eth_call / eth_estimateGas / eth_createAccessList /
    * debug_traceCall. Unlike [[validateForSend]] this does not reject a data/input mismatch (geth's call path silently
    * prefers `input`) nor a contract creation without data.
    */
  def validateForCall: Either[TxArgsError, TxArgs] =
    for
      _ <- Either.cond(!(gasPrice.isDefined && hasDynamicFee), (), GasPriceAndDynamicFee)
      _ <- to match
        case None if blobVersionedHashes.isDefined => Left(BlobTxCreate)
        case None if authorizationList.isDefined   => Left(SetCodeTxCreate)
        case _                                     => Right(())
      _ <- txType.fold(Right(()))(checkExplicitType)
    yield this

  /** Fill every absent field from `d`; present fields are never overwritten. Pure: the caller (S2 `setDefaults`
    * equivalent) computes the values and decides which fee family to supply. `to`, `data`/`input` and `accessList` are
    * intentionally not defaultable: they define what the transaction does, so only the request may set them.
    */
  def withDefaults(d: Defaults): TxArgs =
    copy(
      nonce = nonce.orElse(d.nonce),
      gas = gas.orElse(d.gas),
      gasPrice = gasPrice.orElse(d.gasPrice),
      maxFeePerGas = maxFeePerGas.orElse(d.maxFeePerGas),
      maxPriorityFeePerGas = maxPriorityFeePerGas.orElse(d.maxPriorityFeePerGas),
      maxFeePerBlobGas = maxFeePerBlobGas.orElse(d.maxFeePerBlobGas),
      value = value.orElse(d.value),
      chainId = chainId.orElse(d.chainId),
      blobVersionedHashes = blobVersionedHashes.orElse(d.blobVersionedHashes)
    )

  /** Convert resolved arguments to the domain transaction (geth `ToTransaction(defaultType)`).
    *
    * Pure; does not fill anything except `value` (absent = 0) and call data (absent = empty), which geth also treats as
    * zero values. `nonce`, `gas` and the fee fields of the resolved type must be present, otherwise
    * [[TxArgsError.MissingField]]. A present `chainId` must equal `nodeChainId` (geth `setDefaults`).
    *
    * Legacy: [[LegacyTransaction]] carries no chain id; EIP-155 protection is applied when signing
    * (`SignedTransaction.sign(tx, key, Some(chainId))`). Typed transactions take `chainId` from the args, defaulting to
    * `nodeChainId`.
    */
  def toTransaction(nodeChainId: ChainId, defaultType: Byte = LegacyType): Either[TxArgsError, Transaction] =
    for
      _ <- chainId match
        case Some(have) if have.value != nodeChainId.value => Left(ChainIdMismatch(have.value, nodeChainId.value))
        case _                                             => Right(())
      t <- resolveType(defaultType)
      n <- required(nonce, "nonce", t)
      g <- required(gas, "gas", t)
      cid = chainId.getOrElse(nodeChainId).value
      v = value.getOrElse(BigInt(0))
      al = accessList.getOrElse(Nil)
      tx <- t match
        case LegacyType =>
          required(gasPrice, "gasPrice", t).map(gp => LegacyTransaction(n, gp, g, to, v, callData))
        case AccessListType =>
          required(gasPrice, "gasPrice", t).map(gp => TransactionWithAccessList(cid, n, gp, g, to, v, callData, al))
        case DynamicFeeType =>
          for
            fee <- required(maxFeePerGas, "maxFeePerGas", t)
            tip <- required(maxPriorityFeePerGas, "maxPriorityFeePerGas", t)
          yield TransactionWithDynamicFee(cid, n, tip, fee, g, to, v, callData, al)
        case BlobType =>
          for
            dest <- to.toRight(BlobTxCreate)
            fee <- required(maxFeePerGas, "maxFeePerGas", t)
            tip <- required(maxPriorityFeePerGas, "maxPriorityFeePerGas", t)
            blobFee <- required(maxFeePerBlobGas, "maxFeePerBlobGas", t)
            hashes <- required(blobVersionedHashes, "blobVersionedHashes", t)
          yield BlobTransaction(cid, n, tip, fee, g, Some(dest), v, callData, al, blobFee, hashes)
        case _ => // SetCodeType; resolveType only yields supported types
          for
            dest <- to.toRight(SetCodeTxCreate)
            fee <- required(maxFeePerGas, "maxFeePerGas", t)
            tip <- required(maxPriorityFeePerGas, "maxPriorityFeePerGas", t)
          yield SetCodeTransaction(cid, n, tip, fee, g, Some(dest), v, callData, al, authorizationList.getOrElse(Nil))
    yield tx

  private def required[A](field: Option[A], name: String, t: Byte): Either[TxArgsError, A] =
    field.toRight(MissingField(name, t))

object TxArgs:
  val LegacyType: Byte = 0
  val AccessListType: Byte = Transaction.Type01
  val DynamicFeeType: Byte = Transaction.Type02
  val BlobType: Byte = Transaction.Type03
  val SetCodeType: Byte = Transaction.Type04

  val SupportedTypes: Set[Byte] = Set(LegacyType, AccessListType, DynamicFeeType, BlobType, SetCodeType)

  private val BlobFields = Set("maxFeePerBlobGas", "blobVersionedHashes", "blobs", "commitments", "proofs")

  /** Fee/list fields each explicit `type` may carry (fukuii rule; from execution-apis Transaction*Unsigned). */
  private val AllowedFields: Map[Byte, Set[String]] = Map(
    LegacyType -> Set("gasPrice"),
    AccessListType -> Set("gasPrice", "accessList"),
    DynamicFeeType -> Set("maxFeePerGas", "maxPriorityFeePerGas", "accessList"),
    BlobType -> (Set("maxFeePerGas", "maxPriorityFeePerGas", "accessList") ++ BlobFields),
    SetCodeType -> Set("maxFeePerGas", "maxPriorityFeePerGas", "accessList", "authorizationList")
  )

  /** Values a defaults engine resolved for the absent fields; see [[TxArgs.withDefaults]]. */
  final case class Defaults(
      nonce: Option[BigInt] = None,
      gas: Option[GasAmount] = None,
      gasPrice: Option[GasPrice] = None,
      maxFeePerGas: Option[BigInt] = None,
      maxPriorityFeePerGas: Option[BigInt] = None,
      maxFeePerBlobGas: Option[BigInt] = None,
      value: Option[BigInt] = None,
      chainId: Option[ChainId] = None,
      blobVersionedHashes: Option[List[BlobVersionedHash]] = None
  )

  /** The inverse of [[TxArgs.toTransaction]]: explicit `type`, `input` (never `data`), and every field of the
    * transaction. Legacy transactions carry no chain id, so `chainId` is left absent for them. Use for the `tx` member
    * of eth_fillTransaction / eth_signTransaction results (encode with [[TxArgsJson.encode]]).
    */
  def fromTransaction(tx: Transaction, from: Option[Address] = None): TxArgs =
    val base = TxArgs(
      from = from,
      to = tx.receivingAddress,
      gas = Some(tx.gasLimit),
      value = Some(tx.value),
      nonce = Some(tx.nonce),
      input = Some(tx.payload)
    )
    tx match
      case t: LegacyTransaction =>
        base.copy(txType = Some(LegacyType), gasPrice = Some(t.gasPrice))
      case t: TransactionWithAccessList =>
        base.copy(
          txType = Some(AccessListType),
          chainId = Some(ChainId(t.chainId)),
          gasPrice = Some(t.gasPrice),
          accessList = Some(t.accessList)
        )
      case t: TransactionWithDynamicFee =>
        base.copy(
          txType = Some(DynamicFeeType),
          chainId = Some(ChainId(t.chainId)),
          maxFeePerGas = Some(t.maxFeePerGas),
          maxPriorityFeePerGas = Some(t.maxPriorityFeePerGas),
          accessList = Some(t.accessList)
        )
      case t: BlobTransaction =>
        base.copy(
          txType = Some(BlobType),
          chainId = Some(ChainId(t.chainId)),
          maxFeePerGas = Some(t.maxFeePerGas),
          maxPriorityFeePerGas = Some(t.maxPriorityFeePerGas),
          accessList = Some(t.accessList),
          maxFeePerBlobGas = Some(t.maxFeePerBlobGas),
          blobVersionedHashes = Some(t.blobVersionedHashes)
        )
      case t: SetCodeTransaction =>
        base.copy(
          txType = Some(SetCodeType),
          chainId = Some(ChainId(t.chainId)),
          maxFeePerGas = Some(t.maxFeePerGas),
          maxPriorityFeePerGas = Some(t.maxPriorityFeePerGas),
          accessList = Some(t.accessList),
          authorizationList = Some(t.authorizationList)
        )
