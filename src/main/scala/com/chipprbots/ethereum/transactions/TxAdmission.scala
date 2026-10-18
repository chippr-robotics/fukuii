package com.chipprbots.ethereum.transactions

import com.chipprbots.ethereum.consensus.validators.std.StdSignedTransactionValidator
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlobTransaction
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.LegacyTransaction
import com.chipprbots.ethereum.domain.SetCodeTransaction
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.domain.SignedTransactionWithSender.StatelessRejection
import com.chipprbots.ethereum.domain.Transaction
import com.chipprbots.ethereum.domain.TransactionWithAccessList
import com.chipprbots.ethereum.domain.TransactionWithDynamicFee
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.NetworkType
import com.chipprbots.ethereum.vm.EvmConfig

/** Which transaction types the pool accepts at a given chain head: the one statement of type activation for pool
  * admission, per chain family.
  *
  *   - ETH: 0x1 from Berlin (`berlinBlockNumber`), 0x2 from London (`olympiaBlockNumber`, which is London's block on an
  *     ETH-family chain; hive maps HIVE_FORK_LONDON onto it), 0x3 from Cancun and 0x4 from Prague (timestamps).
  *   - ETC: 0x1 from Magneto (ETC's Berlin, EIP-2930), 0x2 and 0x4 from Olympia (ECIP-1111 / ECIP-1121), 0x3 never (ETC
  *     schedules no Cancun). With Olympia unscheduled, ETC and Mordor accept 0x0 and 0x1 only.
  *
  * go-ethereum applies the same gate against the pool's current head (`txpool.ValidateTransaction`), so a transaction
  * that becomes valid at the next block's fork is rejected until the head reaches that fork.
  */
object TxTypeGate:

  /** The fork `tx`'s type waits for at `head`, or `None` if the type is active there. */
  def inactiveFork(tx: Transaction, head: BlockHeader)(using cfg: BlockchainConfig): Option[String] =
    val isEth = cfg.networkType == NetworkType.ETH
    val number = head.number.value
    val forks = cfg.forkBlockNumbers
    tx match
      case _: LegacyTransaction => None
      case _: TransactionWithAccessList =>
        if isEth then Option.unless(number >= forks.berlinBlockNumber)("Berlin")
        else Option.unless(number >= forks.magnetoBlockNumber.min(forks.berlinBlockNumber))("Magneto")
      case _: TransactionWithDynamicFee =>
        Option.unless(number >= forks.olympiaBlockNumber)(if isEth then "London" else "Olympia")
      case _: BlobTransaction => Option.unless(cfg.isCancunTimestamp(head.unixTimestamp))("Cancun")
      case _: SetCodeTransaction =>
        if isEth then Option.unless(cfg.isPragueTimestamp(head.unixTimestamp))("Prague")
        else Option.unless(number >= forks.olympiaBlockNumber)("Olympia")

  /** The transaction types active at `head`, as type bytes (0 = legacy). */
  def activeTypes(head: BlockHeader)(using cfg: BlockchainConfig): Set[Byte] =
    val isEth = cfg.networkType == NetworkType.ETH
    val number = head.number.value
    val forks = cfg.forkBlockNumbers
    val type1 =
      if isEth then number >= forks.berlinBlockNumber
      else number >= forks.magnetoBlockNumber.min(forks.berlinBlockNumber)
    val type2 = number >= forks.olympiaBlockNumber
    val type3 = cfg.isCancunTimestamp(head.unixTimestamp)
    val type4 = if isEth then cfg.isPragueTimestamp(head.unixTimestamp) else number >= forks.olympiaBlockNumber
    Set(
      Some(0.toByte),
      Option.when(type1)(Transaction.Type01),
      Option.when(type2)(Transaction.Type02),
      Option.when(type3)(Transaction.Type03),
      Option.when(type4)(Transaction.Type04)
    ).flatten

/** Pool admission rules, each stated once, with go-ethereum's error for each.
  *
  * The peer path ([[PendingTransactionsManager]]'s `validateAgainstState` and the stateless filter in
  * [[SignedTransactionWithSender]]) filters on these; local submission (`eth_sendRawTransaction`, GraphQL
  * `sendRawTransaction`) runs [[validateLocal]], which applies them in go-ethereum's order
  * (`txpool.ValidateTransaction`, then `ValidateTransactionWithState`) and reports the first that fails.
  *
  * Strings are go-ethereum's (core/error.go, core/txpool/errors.go, core/txpool/validation.go,
  * core/types/transaction_signing.go), wrapped the way geth wraps them, so a client matching on geth's message prefix
  * matches ours.
  */
object TxAdmission:

  /** Why the pool refuses a transaction. `message` is go-ethereum's error text for the same condition. */
  enum Rejection(val message: String):
    /** core/types ErrTxTypeNotSupported, wrapped as txpool.ValidateTransaction wraps it. */
    case TypeNotSupported(txType: Int, fork: String)
        extends Rejection(s"transaction type not supported: type $txType rejected, pool not yet in $fork")

    /** core ErrMaxInitCodeSizeExceeded (EIP-3860). */
    case InitCodeTooLarge(size: BigInt, limit: BigInt)
        extends Rejection(s"max initcode size exceeded: code size $size, limit $limit")

    /** core ErrGasLimitTooHigh (EIP-7825, Osaka on ETH, Olympia on ETC). */
    case GasLimitTooHigh(cap: BigInt, gas: BigInt)
        extends Rejection(s"transaction gas limit too high (cap: $cap, tx: $gas)")

    /** txpool ErrGasLimit: the head block's gas limit is below the transaction's. */
    case ExceedsBlockGasLimit extends Rejection("exceeds block gas limit")

    /** core ErrTipAboveFeeCap. */
    case TipAboveFeeCap extends Rejection("max priority fee per gas higher than max fee per gas")

    /** txpool ErrInvalidSender wrapping types ErrInvalidChainId. */
    case InvalidChainId(have: BigInt, want: BigInt)
        extends Rejection(s"invalid sender: invalid chain id for signer: have $have want $want")

    /** txpool ErrInvalidSender wrapping types ErrInvalidSig. */
    case InvalidSignature extends Rejection("invalid sender: invalid transaction v, r, s values")

    /** core ErrNonceMax (EIP-2681). */
    case NonceMax extends Rejection("nonce has max value")

    /** core ErrIntrinsicGas. */
    case IntrinsicGasTooLow(gas: BigInt, needed: BigInt)
        extends Rejection(s"intrinsic gas too low: gas $gas, minimum needed $needed")

    /** core ErrFloorDataGas (EIP-7623; Amsterdam EIP-7976 / EIP-7981). */
    case FloorDataGasTooLow(gas: BigInt, needed: BigInt)
        extends Rejection(s"insufficient gas for floor data gas cost: gas $gas, minimum needed $needed")

    /** core ErrFloorDataGas, Amsterdam: max(intrinsic, floor) above TX_MAX_GAS_LIMIT. */
    case IntrinsicCostExceedsCap(intrinsic: BigInt, floor: BigInt)
        extends Rejection(s"insufficient gas for floor data gas cost: intrinsic cost $intrinsic, floor: $floor")

    /** txpool ErrTxGasPriceTooLow: the tip is below the pool's minimum ([[tipRejection]]). */
    case TipBelowMinimum(tip: BigInt, minimum: BigInt)
        extends Rejection(s"transaction gas price below minimum: gas tip cap $tip, minimum needed $minimum")

    /** txpool ErrTxGasPriceTooLow for the blob fee cap (params.BlobTxMinBlobGasprice = 1). */
    case BlobFeeCapBelowMinimum(cap: BigInt)
        extends Rejection(s"transaction gas price below minimum: blob fee cap $cap, minimum needed 1")

    /** txpool validateBlobSidecar. */
    case MissingSidecar extends Rejection("missing sidecar in blob transaction")

    /** txpool.ValidateTransaction, set-code transactions. */
    case EmptyAuthList extends Rejection("set code tx must have at least one authorization tuple")

    /** core ErrNonceTooLow, as ValidateTransactionWithState wraps it. */
    case NonceTooLow(next: BigInt, nonce: BigInt) extends Rejection(s"nonce too low: next nonce $next, tx nonce $nonce")

    /** core ErrInsufficientFunds, as ValidateTransactionWithState wraps it. */
    case InsufficientFunds(balance: BigInt, cost: BigInt)
        extends Rejection(
          s"insufficient funds for gas * price + value: balance $balance, tx cost $cost, overshot ${cost - balance}"
        )

  /** The tip cap a transaction offers: maxPriorityFeePerGas for the fee-market types, gasPrice otherwise. */
  def tipCap(tx: Transaction): BigInt =
    tx match
      case t: TransactionWithDynamicFee => t.maxPriorityFeePerGas
      case t: BlobTransaction           => t.maxPriorityFeePerGas
      case t: SetCodeTransaction        => t.maxPriorityFeePerGas
      case other                        => other.gasPrice.value

  /** The fee cap: maxFeePerGas for the fee-market types, gasPrice otherwise (`Transaction.gasPrice` is the fee cap). */
  def feeCap(tx: Transaction): BigInt = tx.gasPrice.value

  /** The most the sender can be charged: gas * fee cap + value, plus blob gas * blob fee cap for a blob transaction
    * (go-ethereum `Transaction.Cost`).
    */
  def upfrontCost(tx: Transaction): BigInt =
    val blobCost = tx match
      case bt: BlobTransaction =>
        BigInt(bt.blobVersionedHashes.size) *
          com.chipprbots.ethereum.consensus.engine.BlobGasUtils.GAS_PER_BLOB * bt.maxFeePerBlobGas
      case _ => BigInt(0)
    tx.gasLimit.value * feeCap(tx) + tx.value + blobCost

  /** The pool's minimum-tip rule at `head`.
    *
    * ETH-family: go-ethereum's rule. The tip CAP must reach `minTip` (geth's txpool.pricelimit). A tx whose fee cap
    * sits exactly at today's base fee pays no tip at that base fee, yet it is includable once the base fee holds or
    * falls, and geth pools and announces it (hive devp2p Transaction/InvalidTxs/LargeTxRequest).
    *
    * ETC (ECIP-1122): the tip a tx pays at the current base fee must reach the floor: 1 wei before Olympia (core-geth's
    * txpool.pricelimit default), `minTip` from Olympia. Before Olympia an ETC header has no base fee, so the tip is the
    * whole gas price. `baseFeeFloor` stands in for a missing base fee only from Olympia, or when there is no head.
    */
  def tipRejection(tx: Transaction, head: Option[BlockHeader])(using cfg: BlockchainConfig): Option[Rejection] =
    val isEthFamily = cfg.networkType == NetworkType.ETH
    val isOlympiaActive = head.exists(_.number.value >= cfg.forkBlockNumbers.olympiaBlockNumber)
    val minTip = if isEthFamily || isOlympiaActive then cfg.minTip else BigInt(1)
    val tip =
      if isEthFamily then tipCap(tx)
      else
        val baseFee = head.flatMap(_.baseFee).getOrElse {
          if isOlympiaActive || head.isEmpty then cfg.baseFeeFloor else BigInt(0)
        }
        Transaction.effectiveGasPrice(tx, Some(baseFee)) - baseFee
    Option.when(tip < minTip)(Rejection.TipBelowMinimum(tip, minTip))

  /** The checks against the sender's account at the head (`ValidateTransactionWithState`): nonce not below the
    * account's, balance covering [[upfrontCost]]. A nonce above the account's is not refused: the transaction waits.
    */
  def stateRejection(tx: Transaction, account: Account): Option[Rejection] =
    val next = account.nonce.toBigInt
    val balance = account.balance.toBigInt
    val cost = upfrontCost(tx)
    if tx.nonce < next then Some(Rejection.NonceTooLow(next, tx.nonce))
    else if balance < cost then Some(Rejection.InsufficientFunds(balance, cost))
    else None

  /** Admission of a locally submitted transaction against the chain head, in go-ethereum's order: the stateless checks
    * of `txpool.ValidateTransaction`, then the state checks of `ValidateTransactionWithState`. Returns the sender.
    *
    * @param hasSidecar
    *   whether a blob transaction arrived with its sidecar (the EIP-4844 / EIP-7594 network form)
    * @param accountAt
    *   the sender's account in the head's state, `None` if it has none. May throw `MissingNodeException` when the
    *   head's state is not available locally; the caller reports that.
    */
  def validateLocal(
      stx: SignedTransaction,
      hasSidecar: Boolean,
      head: BlockHeader,
      accountAt: Address => Option[Account]
  )(using cfg: BlockchainConfig): Either[Rejection, Address] =
    val tx = stx.tx
    val evmConfig = EvmConfig.forBlock(head.number.value, head.unixTimestamp, cfg)
    def check(failure: Option[Rejection]): Either[Rejection, Unit] = failure.toLeft(())
    for
      _ <- check(TxTypeGate.inactiveFork(tx, head).map(Rejection.TypeNotSupported(txType(tx), _)))
      _ <- check(initCodeRejection(tx, evmConfig))
      _ <- check(gasCapRejection(tx, head))
      _ <- check(Option.when(head.gasLimit.value < tx.gasLimit.value)(Rejection.ExceedsBlockGasLimit))
      _ <- check(Option.when(feeCap(tx) < tipCap(tx))(Rejection.TipAboveFeeCap))
      sender <- senderOf(stx)
      _ <- check(statelessRejection(stx, head))
      _ <- check(tipRejection(tx, Some(head)))
      _ <- check(blobRejection(tx, hasSidecar))
      _ <- check(tx match
        case sct: SetCodeTransaction if sct.authorizationList.isEmpty => Some(Rejection.EmptyAuthList)
        case _                                                        => None
      )
      _ <- check(stateRejection(tx, accountAt(sender).getOrElse(Account.empty(cfg.accountStartNonce))))
    yield sender

  private def txType(tx: Transaction): Int =
    tx match
      case _: LegacyTransaction         => 0
      case _: TransactionWithAccessList => 1
      case _: TransactionWithDynamicFee => 2
      case _: BlobTransaction           => 3
      case _: SetCodeTransaction        => 4

  private def initCodeRejection(tx: Transaction, evmConfig: EvmConfig): Option[Rejection] =
    if !tx.isContractInit || !evmConfig.eip3860Enabled then None
    else
      evmConfig.maxInitCodeSize.collect {
        case max if tx.payload.size > max => Rejection.InitCodeTooLarge(tx.payload.size, max)
      }

  /** EIP-7825's per-transaction gas cap, as go-ethereum's pool applies it: from Osaka until Amsterdam, which caps
    * execution gas rather than `tx.gas` (the Amsterdam intrinsic-cost cap is [[statelessRejection]]'s). ETC applies the
    * cap from Olympia (ECIP-1121), as block validation does.
    */
  private def gasCapRejection(tx: Transaction, head: BlockHeader)(using cfg: BlockchainConfig): Option[Rejection] =
    val isEth = cfg.networkType == NetworkType.ETH
    val capped =
      !cfg.isAmsterdamTimestamp(head.unixTimestamp) &&
        (cfg.isOsakaTimestamp(head.unixTimestamp) ||
          (!isEth && head.number.value >= cfg.forkBlockNumbers.olympiaBlockNumber))
    val cap = StdSignedTransactionValidator.TxGasLimitCap
    Option.when(capped && tx.gasLimit.value > cap)(Rejection.GasLimitTooHigh(cap, tx.gasLimit.value))

  /** The chain ID (typed transactions, and EIP-155 legacy ones) must be this chain's, and the signature must recover a
    * sender. go-ethereum reports both through `types.Sender`.
    */
  private def senderOf(stx: SignedTransaction)(using cfg: BlockchainConfig): Either[Rejection, Address] =
    val want = cfg.chainId.value
    val signedFor: Option[BigInt] = stx.tx match
      case _: LegacyTransaction            => SignedTransaction.legacyEip155ChainId(stx)
      case twal: TransactionWithAccessList => Some(twal.chainId)
      case twdf: TransactionWithDynamicFee => Some(twdf.chainId)
      case btx: BlobTransaction            => Some(btx.chainId)
      case sct: SetCodeTransaction         => Some(sct.chainId)
    signedFor.filter(_ != want) match
      case Some(have) => Left(Rejection.InvalidChainId(have, want))
      case None       => SignedTransaction.getSender(stx).toRight(Rejection.InvalidSignature)

  /** The pool's stateless filter ([[SignedTransactionWithSender.statelessRejection]]) under the rules it uses for the
    * head, so a local transaction meets exactly the intrinsic-gas and floor rule a peer's does.
    */
  private def statelessRejection(stx: SignedTransaction, head: BlockHeader)(using
      cfg: BlockchainConfig
  ): Option[Rejection] =
    SignedTransactionWithSender
      .statelessRejection(stx, SignedTransactionWithSender.admissionRules(head.unixTimestamp))
      .map {
        case StatelessRejection.WrongChainId(have, want)        => Rejection.InvalidChainId(have, want)
        case StatelessRejection.NonceMax                        => Rejection.NonceMax
        case StatelessRejection.IntrinsicGasTooLow(gas, needed) => Rejection.IntrinsicGasTooLow(gas, needed)
        case StatelessRejection.FloorDataGasTooLow(gas, needed) => Rejection.FloorDataGasTooLow(gas, needed)
        case StatelessRejection.IntrinsicCostExceedsCap(i, f)   => Rejection.IntrinsicCostExceedsCap(i, f)
      }

  private def blobRejection(tx: Transaction, hasSidecar: Boolean): Option[Rejection] =
    tx match
      case bt: BlobTransaction if bt.maxFeePerBlobGas < 1 => Some(Rejection.BlobFeeCapBelowMinimum(bt.maxFeePerBlobGas))
      case _: BlobTransaction if !hasSidecar              => Some(Rejection.MissingSidecar)
      case _                                              => None
