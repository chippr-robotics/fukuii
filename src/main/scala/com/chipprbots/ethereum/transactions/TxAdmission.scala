package com.chipprbots.ethereum.transactions

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.crypto.KzgCellProofs
import com.chipprbots.ethereum.domain.SetCodeAuthorization
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.PrefixedRLPEncodable
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPImplicitConversions.toEncodeable
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue
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

    // --- Blob sidecar (txpool validateBlobSidecar / ValidateCells; before Osaka, go-ethereum v1.16 validateBlobTx) ---

    /** A blob transaction carrying no blobs. */
    case BloblessBlobTx extends Rejection("blobless blob transaction")

    /** types BlobTxSidecar.ValidateBlobCommitmentHashes: commitment count. */
    case BlobCommitmentCount(commitments: Int, hashes: Int)
        extends Rejection(s"invalid number of $commitments blob commitments compared to $hashes blob hashes")

    /** types BlobTxSidecar.ValidateBlobCommitmentHashes: a versioned hash not derived from its commitment. */
    case BlobHashMismatch(index: Int, computed: String, have: String)
        extends Rejection(s"blob $index: computed hash $computed mismatches transaction one $have")

    /** txpool ErrTxBlobLimitExceeded (from Osaka; params.BlobTxMaxBlobs). */
    case BlobLimitExceeded(count: Int, limit: Int)
        extends Rejection(s"transaction blob limit exceeded: blob count $count, limit $limit")

    /** go-ethereum v1.16 validateBlobTx's per-transaction blob cap, before Osaka (params.BlobTxMaxBlobs). */
    case TooManyBlobs(count: Int, limit: Int)
        extends Rejection(s"too many blobs in transaction: have $count, permitted $limit")

    /** txpool ErrSidecarFormatError. */
    case SidecarFormat(detail: String) extends Rejection(s"Wrong sidecar format: $detail")

    /** Blob count against another sidecar part, unwrapped (ValidateCells; v1.16 validateBlobTx). */
    case BlobCountMismatch(detail: String) extends Rejection(detail)

    /** Proof count of a pre-Osaka sidecar (v1.16 validateBlobSidecarLegacy / validateBlobSidecarOsaka). */
    case BlobProofCount(proofs: Int, expected: Int)
        extends Rejection(s"invalid number of $proofs blob proofs expected $expected")

    /** txpool ErrKZGVerificationError: cell proofs that do not verify (from Osaka). */
    case KzgVerification(cause: String) extends Rejection(s"KZG verification error: $cause")

    /** v1.16 validateBlobSidecarLegacy: a version-0 blob proof that does not verify (before Osaka). */
    case InvalidBlobProof(index: Int, cause: String) extends Rejection(s"invalid blob $index: $cause")

    /** A pre-Osaka cell proof (v1.16 VerifyCellProofs), unwrapped as go-ethereum v1.16 returns it. */
    case CellProofVerification(cause: String) extends Rejection(cause)

    /** v1.16 blobpool preCheck: a version-1 (cell proof) sidecar before Osaka. */
    case CellProofNotSupported extends Rejection("cell proof is not supported yet")

    // --- Pool state (legacypool add / validateTx, blobpool validateTx) ---

    /** txpool ErrAlreadyKnown: the same transaction is already pooled. */
    case AlreadyKnown extends Rejection("already known")

    /** core ErrInsufficientFunds as ValidateTransactionWithState reports an overdraft over the sender's pending txs. */
    case InsufficientFundsQueued(balance: BigInt, spent: BigInt, cost: BigInt)
        extends Rejection(
          s"insufficient funds for gas * price + value: balance $balance, queued cost $spent, tx cost $cost, " +
            s"overshot ${spent + cost - balance}"
        )

    /** The same, for a replacement: the pending txs plus the replacement's extra cost over the tx it replaces. */
    case InsufficientFundsBumped(balance: BigInt, spent: BigInt, bump: BigInt)
        extends Rejection(
          s"insufficient funds for gas * price + value: balance $balance, queued cost $spent, tx bumped $bump, " +
            s"overshot ${spent + bump - balance}"
        )

    /** txpool ErrReplaceUnderpriced (legacypool list.Add). */
    case ReplaceUnderpriced extends Rejection("replacement transaction underpriced")

    /** txpool ErrReplaceUnderpriced with blobpool's detail. */
    case ReplaceUnderpricedDetail(detail: String) extends Rejection(s"replacement transaction underpriced: $detail")

    /** legacypool ErrOutOfOrderTxFromDelegated. */
    case OutOfOrderFromDelegated extends Rejection("gapped-nonce tx from delegated accounts")

    /** txpool ErrInflightTxLimitReached. */
    case InflightTxLimitReached extends Rejection("in-flight transaction limit reached for delegated accounts")

    /** legacypool ErrAuthorityReserved. */
    case AuthorityReserved extends Rejection("authority already reserved")

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
        // No head reads as before Olympia (no base fee, 1 wei floor), consistent with `minTip` above.
        val baseFee = head.flatMap(_.baseFee).getOrElse(if isOlympiaActive then cfg.baseFeeFloor else BigInt(0))
        Transaction.effectiveGasPrice(tx, Some(baseFee)) - baseFee
    Option.when(tip < minTip)(Rejection.TipBelowMinimum(tip, minTip))

  /** [[TxTypeGate]]: `tx`'s type must be active at `head`. */
  def typeRejection(tx: Transaction, head: BlockHeader)(using cfg: BlockchainConfig): Option[Rejection] =
    TxTypeGate.inactiveFork(tx, head).map(Rejection.TypeNotSupported(txType(tx), _))

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
    * @param sidecar
    *   the sidecar a blob transaction arrived with (the EIP-4844 / EIP-7594 network form), checked by [[blobRejection]]
    * @param accountAt
    *   the sender's account in the head's state, `None` if it has none. May throw `MissingNodeException` when the
    *   head's state is not available locally; the caller reports that.
    * @return
    *   the sender and its account at the head (the empty account if it has none), which the pool's own rules read
    */
  def validateLocal(
      stx: SignedTransaction,
      sidecar: Option[BlobSidecar],
      head: BlockHeader,
      accountAt: Address => Option[Account]
  )(using cfg: BlockchainConfig): Either[Rejection, (Address, Account)] =
    val tx = stx.tx
    val evmConfig = EvmConfig.forBlock(head.number.value, head.unixTimestamp, cfg)
    def check(failure: Option[Rejection]): Either[Rejection, Unit] = failure.toLeft(())
    for
      _ <- check(typeRejection(tx, head))
      _ <- check(initCodeRejection(tx, evmConfig))
      _ <- check(gasCapRejection(tx, head))
      _ <- check(Option.when(head.gasLimit.value < tx.gasLimit.value)(Rejection.ExceedsBlockGasLimit))
      _ <- check(Option.when(feeCap(tx) < tipCap(tx))(Rejection.TipAboveFeeCap))
      sender <- senderOf(stx)
      _ <- check(statelessRejection(stx, head))
      _ <- check(tipRejection(tx, Some(head)))
      _ <- check(blobRejection(tx, sidecar, head))
      _ <- check(tx match
        case sct: SetCodeTransaction if sct.authorizationList.isEmpty => Some(Rejection.EmptyAuthList)
        case _                                                        => None
      )
      account = accountAt(sender).getOrElse(Account.empty(cfg.accountStartNonce))
      _ <- check(stateRejection(tx, account))
    yield (sender, account)

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

  /** The blob fee cap and the sidecar a local blob transaction arrived with, in go-ethereum's order: the pool's rules
    * for the head's fork.
    *
    * From Osaka, go-ethereum master's `validateBlobSidecar` then `ValidateCells` (core/txpool/validation.go:174-235):
    * commitments against the versioned hashes, at most `params.BlobTxMaxBlobs` blobs, a version-1 (cell proof) sidecar
    * with `CELLS_PER_EXT_BLOB` proofs per blob, at least one blob, then the cell proofs verified.
    * eth_sendRawTransaction converts a version-0 sidecar to version 1 before this, as go-ethereum's does
    * (api.go:1745-1755), so the version rule refuses only a sidecar that reaches admission otherwise.
    *
    * Before Osaka go-ethereum master has no admission path at all, so this follows v1.16's `validateBlobTx` and
    * `preCheck` (validation.go:158-207, blobpool.go:1647-1680): at least one blob, at most `params.BlobTxMaxBlobs`, the
    * blob and commitment counts against the hashes, then the proofs of the sidecar's version verified, and a version-1
    * sidecar refused.
    */
  private def blobRejection(tx: Transaction, sidecar: Option[BlobSidecar], head: BlockHeader)(using
      cfg: BlockchainConfig
  ): Option[Rejection] =
    tx match
      case bt: BlobTransaction if bt.maxFeePerBlobGas < 1 => Some(Rejection.BlobFeeCapBelowMinimum(bt.maxFeePerBlobGas))
      case bt: BlobTransaction =>
        sidecar match
          case None => Some(Rejection.MissingSidecar)
          case Some(sc) =>
            val hashes = bt.blobVersionedHashes.map(_.value)
            if cfg.isOsakaTimestamp(head.unixTimestamp) then osakaSidecarRejection(hashes, sc)
            else legacySidecarRejection(hashes, sc)
      case _ => None

  /** params.BlobTxMaxBlobs: the pool's per-transaction blob cap, at every fork. */
  val MaxBlobsPerTx: Int = 6

  /** kzg4844.CellProofsPerBlob. */
  private val CellProofsPerBlob: Int = 128

  private def osakaSidecarRejection(hashes: Seq[ByteString], sc: BlobSidecar): Option[Rejection] =
    val commitments = sc.commitments.size
    commitmentHashRejection(hashes, sc.commitments)
      .orElse(Option.when(hashes.size > MaxBlobsPerTx)(Rejection.BlobLimitExceeded(hashes.size, MaxBlobsPerTx)))
      .orElse(
        Option.when(sc.version != BlobSidecar.Version1)(
          Rejection.SidecarFormat(s"unexpected sidecar version, want: ${BlobSidecar.Version1}, got: ${sc.version}")
        )
      )
      .orElse(
        Option.when(sc.proofs.size != commitments * CellProofsPerBlob)(
          Rejection.SidecarFormat(
            s"invalid number of ${sc.proofs.size} blob proofs expected ${commitments * CellProofsPerBlob}"
          )
        )
      )
      .orElse(Option.when(sc.blobs.isEmpty)(Rejection.BloblessBlobTx))
      .orElse(
        Option.when(sc.blobs.size != commitments)(
          Rejection.BlobCountMismatch(s"invalid number of ${sc.blobs.size} blobs compared to $commitments commitments")
        )
      )
      .orElse(kzg(KzgCellProofs.verifyCellProofs(bytes(sc.blobs), bytes(sc.commitments), bytes(sc.proofs))) match
        case Right(true)  => None
        case Right(false) => Some(Rejection.KzgVerification("invalid proof"))
        case Left(cause)  => Some(Rejection.KzgVerification(cause))
      )

  private def legacySidecarRejection(hashes: Seq[ByteString], sc: BlobSidecar): Option[Rejection] =
    val n = hashes.size
    Option
      .when(n == 0)(Rejection.BloblessBlobTx)
      .orElse(Option.when(n > MaxBlobsPerTx)(Rejection.TooManyBlobs(n, MaxBlobsPerTx)))
      .orElse(
        Option.when(sc.blobs.size != n)(
          Rejection.BlobCountMismatch(s"invalid number of ${sc.blobs.size} blobs compared to $n blob hashes")
        )
      )
      .orElse(commitmentHashRejection(hashes, sc.commitments))
      .orElse(
        if sc.version == BlobSidecar.Version1 then
          // v1.16 verifies the cell proofs in ValidateTxBasics, before preCheck refuses the version
          Option
            .when(sc.proofs.size != n * CellProofsPerBlob)(
              Rejection.BlobProofCount(sc.proofs.size, n * CellProofsPerBlob)
            )
            .orElse(kzg(KzgCellProofs.verifyCellProofs(bytes(sc.blobs), bytes(sc.commitments), bytes(sc.proofs))) match
              case Right(true)  => Some(Rejection.CellProofNotSupported)
              case Right(false) => Some(Rejection.CellProofVerification("invalid proof"))
              case Left(cause)  => Some(Rejection.CellProofVerification(cause))
            )
        else
          Option
            .when(sc.proofs.size != n)(Rejection.BlobProofCount(sc.proofs.size, n))
            .orElse(
              sc.blobs.indices.iterator
                .map { i =>
                  kzg(
                    KzgCellProofs.verifyBlobProof(
                      sc.blobs(i).toArray[Byte],
                      sc.commitments(i).toArray[Byte],
                      sc.proofs(i).toArray[Byte]
                    )
                  ) match
                    case Right(true)  => None
                    case Right(false) => Some(Rejection.InvalidBlobProof(i, "invalid proof"))
                    case Left(cause)  => Some(Rejection.InvalidBlobProof(i, cause))
                }
                .collectFirst { case Some(r) => r }
            )
      )

  /** types BlobTxSidecar.ValidateBlobCommitmentHashes: one commitment per versioned hash, each hash
    * `kzg_to_versioned_hash` of its commitment.
    */
  private def commitmentHashRejection(hashes: Seq[ByteString], commitments: Seq[ByteString]): Option[Rejection] =
    if commitments.size != hashes.size then Some(Rejection.BlobCommitmentCount(commitments.size, hashes.size))
    else
      hashes.indices.iterator
        .map { i =>
          val computed = ByteString(0x01.toByte) ++ ByteString(crypto.sha256(commitments(i).toArray[Byte]).drop(1))
          Option.when(computed != hashes(i))(Rejection.BlobHashMismatch(i, hex(computed), hex(hashes(i))))
        }
        .collectFirst { case Some(r) => r }

  private def bytes(items: Seq[ByteString]): Seq[Array[Byte]] = items.map(_.toArray[Byte])

  private def hex(b: ByteString): String = "0x" + Hex.toHexString(b.toArray[Byte])

  /** Run a c-kzg-4844 call; its exceptions (malformed input, trusted setup not loaded) and the UnsatisfiedLinkError of
    * a node that skipped loading the native library become the reason text, as go-ethereum reports a KZG error.
    */
  private def kzg[A](f: => A): Either[String, A] =
    try Right(f)
    catch
      case e: Exception    => Left(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
      case e: LinkageError => Left(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))

  // ---------------------------------------------------------------------------------------------------------------
  // Pool state: the rules that read what the pool already holds (legacypool add/validateTx, blobpool validateTx)
  // ---------------------------------------------------------------------------------------------------------------

  /** What the pool holds that the pool-state rules read for one transaction.
    *
    * @param senderTxs
    *   every pooled transaction from the sender (the transaction itself excluded)
    * @param hasPendingAuthorization
    *   a pooled set-code transaction names the sender as an authority (go-ethereum `lookup.hasAuth`)
    * @param pooledFrom
    *   the pooled transactions from an address, for the authority reservation rule
    */
  final case class PoolView(
      senderTxs: Seq[Transaction],
      hasPendingAuthorization: Boolean,
      pooledFrom: Address => Seq[Transaction]
  )

  /** Whether the pool-state rules ([[poolRejection]]) apply on this chain: ETH-family chains. On ETC the pool keeps its
    * behaviour from before (#1629 S0b keeps ETC to the RPC fee cap and the unprotected-tx default); core-geth's
    * legacypool has the same already-known, overdraft and price-bump rules.
    */
  def poolStateRulesApply(using cfg: BlockchainConfig): Boolean = cfg.networkType == NetworkType.ETH

  /** The rules go-ethereum's pool applies against what it already holds, in its order (legacypool `add`: already known,
    * then `validateTx`'s overdraft and `validateAuth`, then the replacement price bump; blobpool `validateTx` for a
    * blob transaction). The sender's nonce and balance at the head ([[stateRejection]]) are checked before this.
    *
    * go-ethereum's "pending" list is the sender's executable run: pooled transactions with consecutive nonces from the
    * account's. Only those count toward the overdraft and the delegation limit; a gapped (queued) transaction does not.
    *
    * Not modelled: go-ethereum's separate subpools reserve an address to one of them ("address already reserved") and
    * evict by price when full; fukuii has one pool.
    */
  def poolRejection(
      tx: Transaction,
      alreadyKnown: Boolean,
      account: Account,
      pool: PoolView,
      head: BlockHeader,
      priceBump: Int,
      blobPriceBump: Int
  )(using cfg: BlockchainConfig): Option[Rejection] =
    val run = executableRun(pool.senderTxs, account.nonce.toBigInt)
    Option
      .when(alreadyKnown)(Rejection.AlreadyKnown)
      .orElse(overdraftRejection(tx, account, run))
      .orElse(Option.when(TxTypeGate.activeTypes(head).contains(Transaction.Type04))(()).flatMap { _ =>
        delegationRejection(tx, account, pool, run).orElse(authorityRejection(tx, pool))
      })
      .orElse(replacementRejection(tx, pool.senderTxs, priceBump, blobPriceBump))

  /** The sender's pooled transactions with consecutive nonces from `next`, in nonce order. */
  private def executableRun(senderTxs: Seq[Transaction], next: BigInt): Seq[Transaction] =
    val byNonce = senderTxs.map(t => t.nonce -> t).toMap
    Iterator.iterate(next)(_ + 1).map(byNonce.get).takeWhile(_.isDefined).flatten.toSeq

  /** `ValidateTransactionWithState` (validation.go:293-315): the balance covers the pending transactions plus this one,
    * or, for a replacement, plus its extra cost over the transaction it replaces.
    */
  private def overdraftRejection(tx: Transaction, account: Account, run: Seq[Transaction]): Option[Rejection] =
    val balance = account.balance.toBigInt
    val spent = run.map(upfrontCost).sum
    val cost = upfrontCost(tx)
    run.find(_.nonce == tx.nonce) match
      case Some(prev) =>
        val bump = cost - upfrontCost(prev)
        Option.when(balance < spent + bump)(Rejection.InsufficientFundsBumped(balance, spent, bump))
      case None =>
        Option.when(balance < spent + cost)(Rejection.InsufficientFundsQueued(balance, spent, cost))

  /** `checkDelegationLimit` (legacypool.go:603-623; blobpool.go:1582-1609): a sender that is delegated (has code) or
    * that a pooled set-code transaction authorizes may have one in-flight transaction. legacypool: with nothing pending
    * the transaction must take the account's nonce, otherwise it may only replace a pending one. blobpool: one pooled
    * transaction, which this one may replace.
    */
  private def delegationRejection(
      tx: Transaction,
      account: Account,
      pool: PoolView,
      run: Seq[Transaction]
  ): Option[Rejection] =
    if account.codeHash == Account.EmptyCodeHash && !pool.hasPendingAuthorization then None
    else
      tx match
        case _: BlobTransaction =>
          pool.senderTxs match
            case Seq()                               => None
            case Seq(only) if only.nonce == tx.nonce => None
            case _                                   => Some(Rejection.InflightTxLimitReached)
        case _ =>
          if run.isEmpty then Option.when(tx.nonce != account.nonce.toBigInt)(Rejection.OutOfOrderFromDelegated)
          else Option.unless(run.exists(_.nonce == tx.nonce))(Rejection.InflightTxLimitReached)

  /** `validateAuth` (legacypool.go:627-660): no authority of a set-code transaction may have more than one pooled
    * transaction, nor one in the blob subpool (go-ethereum's reserver holds a blob sender for the blob pool).
    */
  private def authorityRejection(tx: Transaction, pool: PoolView): Option[Rejection] =
    tx match
      case sct: SetCodeTransaction =>
        setCodeAuthorities(sct).iterator
          .map(pool.pooledFrom)
          .collectFirst {
            case txs if txs.size > 1 || txs.exists(_.isInstanceOf[BlobTransaction]) => Rejection.AuthorityReserved
          }
      case _ => None

  /** Replacing a pooled transaction of the same nonce. legacypool `list.Add` (list.go:303-326): both the fee cap and
    * the tip cap must rise, each to at least `(100 + legacyPriceBump)%` of the old (legacypool DefaultConfig.PriceBump
    * 10). blobpool `validateTx` (blobpool.go:1659-1693): the same for the fee cap, tip cap and blob fee cap, with its
    * detailed messages, at blobpool's own bump (blobpool/config.go DefaultConfig.PriceBump 100: "either have patience
    * or be aggressive").
    */
  private def replacementRejection(
      tx: Transaction,
      senderTxs: Seq[Transaction],
      legacyPriceBump: Int,
      blobPriceBump: Int
  ): Option[Rejection] =
    senderTxs.find(_.nonce == tx.nonce).flatMap { old =>
      def thresholdAt(bump: Int)(v: BigInt): BigInt = v * (100 + bump) / 100
      (tx, old) match
        case (b: BlobTransaction, o: BlobTransaction) =>
          val priceBump = blobPriceBump
          val threshold = thresholdAt(priceBump)
          val (feeCap0, tip0, blob0) = (feeCap(o), tipCap(o), o.maxFeePerBlobGas)
          val (feeCap1, tip1, blob1) = (feeCap(b), tipCap(b), b.maxFeePerBlobGas)
          def detail(s: String) = Some(Rejection.ReplaceUnderpricedDetail(s))
          if feeCap1 <= feeCap0 then detail(s"new tx gas fee cap $feeCap1 <= $feeCap0 queued")
          else if tip1 <= tip0 then detail(s"new tx gas tip cap $tip1 <= $tip0 queued")
          else if blob1 <= blob0 then detail(s"new tx blob gas fee cap $blob1 <= $blob0 queued")
          else if feeCap1 < threshold(feeCap0) then
            detail(s"new tx gas fee cap $feeCap1 < $feeCap0 queued + $priceBump% replacement penalty")
          else if tip1 < threshold(tip0) then
            detail(s"new tx gas tip cap $tip1 < $tip0 queued + $priceBump% replacement penalty")
          else if blob1 < threshold(blob0) then
            detail(s"new tx blob gas fee cap $blob1 < $blob0 queued + $priceBump% replacement penalty")
          else None
        case _ =>
          val threshold = thresholdAt(legacyPriceBump)
          val raised = feeCap(tx) > feeCap(old) && tipCap(tx) > tipCap(old)
          val bumped = feeCap(tx) >= threshold(feeCap(old)) && tipCap(tx) >= threshold(tipCap(old))
          Option.unless(raised && bumped)(Rejection.ReplaceUnderpriced)
    }

  /** go-ethereum `Transaction.SetCodeAuthorities`: each authorization's authority (`SetCodeAuthorization.Authority`:
    * homestead-canonical signature values, then recovery; chain id and nonce are not checked), deduplicated, skipping
    * those that do not recover.
    */
  def setCodeAuthorities(tx: SetCodeTransaction): Seq[Address] =
    tx.authorizationList.flatMap(authority).distinct

  private val Secp256k1N: BigInt = BigInt("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)

  private def authority(auth: SetCodeAuthorization): Option[Address] =
    val valuesOk = (auth.v == 0 || auth.v == 1) && auth.r > 0 && auth.r < Secp256k1N &&
      auth.s > 0 && auth.s <= Secp256k1N / 2
    Option.when(valuesOk)(()).flatMap { _ =>
      val sigHash = crypto.kec256(
        rlp.encode(
          PrefixedRLPEncodable(
            0x05,
            RLPList(toEncodeable(auth.chainId), toEncodeable(auth.address.toArray), toEncodeable(auth.nonce))
          )
        )
      )
      val pointSign = if auth.v == 0 then ECDSASignature.negativePointSign else ECDSASignature.positivePointSign
      ECDSASignature(auth.r, auth.s, BigInt(pointSign))
        .publicKey(sigHash)
        .map(key => Address(crypto.kec256(key).drop(12)))
    }

/** A blob transaction's sidecar as it arrived in network form: EIP-4844's `[tx, blobs, commitments, proofs]` (version
  * 0) or EIP-7594's `[tx, version, blobs, commitments, cell_proofs]` (version 1).
  */
final case class BlobSidecar(
    version: Int,
    blobs: Seq[ByteString],
    commitments: Seq[ByteString],
    proofs: Seq[ByteString]
)

object BlobSidecar:
  val Version0: Int = 0
  val Version1: Int = 1

  /** The sidecar of a blob transaction in network form (type byte, then the wrapper list). Fails on a wrapper whose
    * parts are not lists of byte strings.
    */
  def fromNetworkForm(networkForm: Array[Byte]): Either[String, BlobSidecar] =
    def items(e: RLPEncodeable, what: String): Either[String, Seq[ByteString]] =
      e match
        case l: RLPList =>
          l.items.foldLeft[Either[String, Seq[ByteString]]](Right(Vector.empty)) {
            case (acc, RLPValue(b)) => acc.map(_ :+ ByteString(b))
            case (_, _)             => Left(s"blob sidecar $what: not a byte string")
          }
        case _ => Left(s"blob sidecar $what: not a list")
    rlp.rawDecode(networkForm.drop(1)) match
      case l: RLPList if l.items.size == 4 =>
        for
          b <- items(l.items(1), "blobs")
          c <- items(l.items(2), "commitments")
          p <- items(l.items(3), "proofs")
        yield BlobSidecar(Version0, b, c, p)
      case l: RLPList if l.items.size == 5 =>
        val version = l.items(1) match
          case RLPValue(v) => BigInt(1, v).toInt
          case _           => -1
        for
          b <- items(l.items(2), "blobs")
          c <- items(l.items(3), "commitments")
          p <- items(l.items(4), "proofs")
        yield BlobSidecar(version, b, c, p)
      case _ => Left("not a blob transaction network wrapper")
