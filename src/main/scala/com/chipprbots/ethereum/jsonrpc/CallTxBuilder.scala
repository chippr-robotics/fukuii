package com.chipprbots.ethereum.jsonrpc

import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.jsonrpc.EthSimulateService.BlockOverrides
import com.chipprbots.ethereum.ledger.StxLedger
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.consensus.validators.std.StdSignedTransactionValidator
import com.chipprbots.ethereum.transactions.TxTypeGate
import com.chipprbots.ethereum.utils.NetworkType

/** Turns [[TxArgs]] into the message fukuii executes for every message-call endpoint: eth_call, eth_estimateGas,
  * eth_createAccessList, debug_traceCall(Many), trace_call(Many) and GraphQL `call` / `estimateGas`.
  *
  * go-ethereum reference (internal/ethapi): `TransactionArgs.CallDefaults` and `TransactionArgs.ToMessage`
  * (transaction_args.go), `doCall` / `applyMessage` (api.go), `DoEstimateGas` + `eth/gasestimator`, and `API.TraceCall`
  * (eth/tracers/api.go), all of which build a `core.Message` with `SkipNonceChecks` and `SkipTransactionChecks` and run
  * it under `vm.Config{NoBaseFee: true}`.
  *
  * The message is carried as a synthetic, unsigned domain transaction. geth's message has no type; the subtype chosen
  * here is only the vehicle for the fields the EVM reads, and [[com.chipprbots.ethereum.vm.ProgramContext]] reads them
  * from it: the access list (EIP-2929 pre-warming and its intrinsic gas), the blob hashes (BLOBHASH), the authorization
  * list (EIP-7702, applied by `StxLedger.simulateCall` before the frame) and the effective gas price (GASPRICE, upfront
  * charge).
  *
  *   - authorizationList present -> [[SetCodeTransaction]]
  *   - blobVersionedHashes present -> [[BlobTransaction]]
  *   - a base fee at the block, no gasPrice, and maxFeePerGas or maxPriorityFeePerGas -> [[TransactionWithDynamicFee]]
  *   - accessList present -> [[TransactionWithAccessList]] at the legacy price
  *   - otherwise [[LegacyTransaction]]
  *
  * Fees (geth `ToMessage`): with no base fee at the block, the price is `gasPrice` (0 when absent), and 1559 fields are
  * refused as geth's `CallDefaults` refuses them before London; with a base fee, a given `gasPrice` is the price, fee
  * cap and tip; otherwise the fee cap and tip are the 1559 fields (0 when absent) and the price is `min(feeCap, baseFee
  * + tip)`, or 0 when both are 0. A message whose price is 0 runs with the block's base fee lowered to 0 (geth
  * `doCall`: "Lower the basefee to 0 to avoid breaking EVM invariants"), so BASEFEE reads 0 there.
  */
object CallTxBuilder:

  /** go-ethereum `ethconfig.Defaults.RPCGasCap`. `0` means no cap, as in geth. */
  val DefaultGasCap: BigInt = BigInt(50000000)

  /** Config key holding the cap (`--rpc.gascap` in geth). */
  val GasCapPath: String = "network.rpc.gas-cap"

  private val TxGas: BigInt = BigInt(21000)
  private val MaxUint64: BigInt = (BigInt(1) << 64) - 1

  /** The message to execute: the synthetic transaction with its sender, the block header it runs in (block overrides
    * applied, base fee lowered to 0 for a zero-price message), and what the ledger needs that the header cannot carry.
    */
  final case class CallMessage(
      stx: SignedTransactionWithSender,
      header: BlockHeader,
      options: StxLedger.CallOptions
  )

  def gasCapFrom(fukuiiConfig: com.typesafe.config.Config): BigInt =
    if fukuiiConfig.hasPath(GasCapPath) then BigInt(fukuiiConfig.getLong(GasCapPath)) else DefaultGasCap

  /** Gas of a call (geth `CallDefaults`): the cap when absent, a requested gas above the cap is lowered to it. */
  def callGas(args: TxArgs, gasCap: BigInt): BigInt =
    args.gas.map(_.value) match
      case Some(gas) if gasCap > 0 && gas > gasCap => gasCap
      case Some(gas)                               => gas
      case None                                    => if gasCap > 0 then gasCap else MaxUint64 / 2

  /** The highest gas eth_estimateGas tries (go-ethereum `gasestimator.Estimate`): the requested gas if it is at least
    * 21,000, else the block's gas limit; clamped to EIP-7825's per-transaction cap where that cap bounds `tx.gas` (geth
    * gasestimator.go: `hi > params.MaxTxGas && isOsaka && !isAmsterdam`; on ETC from Olympia, ECIP-1121, as S0's pool
    * rule and the validator apply it); then the RPC gas cap.
    */
  def estimateGasCeiling(args: TxArgs, header: BlockHeader, gasCap: BigInt)(using
      blockchainConfig: BlockchainConfig
  ): BigInt =
    val requested = args.gas.map(_.value).filter(_ >= TxGas).getOrElse(header.gasLimit.value)
    val ts = header.unixTimestamp
    val txGasCapped =
      !blockchainConfig.isAmsterdamTimestamp(ts) &&
        (blockchainConfig.isOsakaTimestamp(ts) ||
          (blockchainConfig.networkType == NetworkType.ETC &&
            header.number.value >= blockchainConfig.forkBlockNumbers.olympiaBlockNumber))
    val hi = if txGasCapped then requested.min(StdSignedTransactionValidator.TxGasLimitCap) else requested
    if gasCap > 0 && hi > gasCap then gasCap else hi

  /** Build the message.
    *
    * @param target
    *   the header of the block the call runs on, before overrides
    * @param defaultFrom
    *   the sender when `from` is absent (only evaluated then: it may consult the keystore)
    * @param gas
    *   the message's gas limit ([[callGas]] / [[estimateGasCeiling]])
    * @param precompileRelocations
    *   `movePrecompileToAddress` state overrides, already applied to the world
    * @param skipExecutionGasCap
    *   true for eth_call and the trace calls, false for eth_estimateGas and eth_createAccessList (see
    *   `StxLedger.CallOptions`)
    */
  def build(
      args: TxArgs,
      target: BlockHeader,
      blockOverrides: Option[BlockOverrides],
      defaultFrom: => Address,
      gas: BigInt,
      precompileRelocations: Map[Address, Address],
      skipExecutionGasCap: Boolean
  )(using blockchainConfig: BlockchainConfig): Either[JsonRpcError, CallMessage] =
    val header = applyBlockOverrides(target, blockOverrides)
    for
      _ <- checkSetCodeWithBlobs(args)
      _ <- checkChainId(args)
      _ <- checkLondon(args, header)
      _ <- checkActive(args, header)
    yield
      val (price, feeCap, tip) = fees(args, header.baseFee)
      val dynamic = header.baseFee.isDefined && args.gasPrice.isEmpty && args.hasDynamicFee
      // geth CallDefaults: a blob message without maxFeePerBlobGas gets 0, and a 0 cap lowers the blob base fee to 0
      val blobFeeCap = args.maxFeePerBlobGas.getOrElse(BigInt(0))
      val blobFeeCapGiven = args.maxFeePerBlobGas.isDefined || args.blobVersionedHashes.isDefined
      val blobBaseFeeOverride =
        if blobFeeCapGiven && blobFeeCap == 0 then Some(BigInt(0)) else blockOverrides.flatMap(_.blobBaseFee)

      val chainId = blockchainConfig.chainId.value
      val nonce = args.nonce.getOrElse(BigInt(0)) // no nonce check in a call (SkipNonceChecks); only the hash sees it
      val value = args.value.getOrElse(BigInt(0))
      val data = args.callData
      val accessList = args.accessList.getOrElse(Nil)
      val gasLimit = GasAmount(gas)
      val tx: Transaction = (args.authorizationList, args.blobVersionedHashes) match
        case (Some(auths), _) =>
          SetCodeTransaction(chainId, nonce, tip, feeCap, gasLimit, args.to, value, data, accessList, auths)
        case (None, Some(hashes)) =>
          BlobTransaction(chainId, nonce, tip, feeCap, gasLimit, args.to, value, data, accessList, blobFeeCap, hashes)
        case _ if dynamic =>
          TransactionWithDynamicFee(chainId, nonce, tip, feeCap, gasLimit, args.to, value, data, accessList)
        case _ if args.accessList.isDefined =>
          TransactionWithAccessList(chainId, nonce, GasPrice(price), gasLimit, args.to, value, data, accessList)
        case _ =>
          LegacyTransaction(nonce, GasPrice(price), gasLimit, args.to, value, data)

      val runHeader = if price == 0 then withBaseFee(header, BigInt(0)) else header
      val from = args.from.getOrElse(defaultFrom)
      CallMessage(
        SignedTransactionWithSender(SignedTransaction(tx, ECDSASignature(0, 0, 0)), from),
        runHeader,
        StxLedger.CallOptions(precompileRelocations, blobBaseFeeOverride, skipExecutionGasCap)
      )

  /** go-ethereum `CallDefaults` with a nil base fee: "maxFeePerGas and maxPriorityFeePerGas are not valid before London
    * is active" (-32000). Applies to every block without a base fee: ETH before London, ETC before Olympia (core-geth's
    * CallDefaults is the same). The base fee is the block context's, block overrides applied.
    */
  private def checkLondon(args: TxArgs, header: BlockHeader): Either[JsonRpcError, Unit] =
    if header.baseFee.isEmpty && args.hasDynamicFee then
      Left(JsonRpcError.LogicError("maxFeePerGas and maxPriorityFeePerGas are not valid before London is active"))
    else Right(())

  /** A set-code (EIP-7702) transaction carries no blobs: blob fields together with an authorizationList describe no
    * transaction type, so they are refused (-32602) rather than one of them being dropped. go-ethereum's message would
    * carry both; fukuii refuses, by the fail-loudly rule (S1's precedent).
    */
  private def checkSetCodeWithBlobs(args: TxArgs): Either[JsonRpcError, Unit] =
    if args.authorizationList.isDefined && (args.blobVersionedHashes.isDefined || args.maxFeePerBlobGas.isDefined) then
      Left(
        JsonRpcError.InvalidParams(
          "authorizationList cannot be combined with blobVersionedHashes or maxFeePerBlobGas: a set-code transaction " +
            "carries no blobs"
        )
      )
    else Right(())

  /** `(price, feeCap, tip)` per geth `ToMessage`. */
  private def fees(args: TxArgs, baseFee: Option[BigInt]): (BigInt, BigInt, BigInt) =
    (baseFee, args.gasPrice) match
      case (_, Some(gp)) => (gp.value, gp.value, gp.value)
      case (None, None)  => (BigInt(0), BigInt(0), BigInt(0)) // pre-London (1559 fields refused by checkLondon)
      case (Some(base), None) =>
        val feeCap = args.maxFeePerGas.getOrElse(BigInt(0))
        val tip = args.maxPriorityFeePerGas.getOrElse(BigInt(0))
        val price = if feeCap > 0 || tip > 0 then (base + tip).min(feeCap) else BigInt(0)
        (price, feeCap, tip)

  /** geth `CallDefaults`: a given chainId must be the node's. -32000, as geth returns it. */
  private def checkChainId(args: TxArgs)(using blockchainConfig: BlockchainConfig): Either[JsonRpcError, Unit] =
    args.chainId match
      case Some(have) if have.value != blockchainConfig.chainId.value =>
        Left(TxArgsError.ChainIdMismatch(have.value, blockchainConfig.chainId.value).toLogicError)
      case _ => Right(())

  /** Typed fields the target block cannot execute are refused, -32602 (fukuii rule; see the per-chain notes below).
    *
    * geth has no such check on its call path: it never reads `type`, and it hands blob hashes and authorizations to a
    * VM that may not implement them (BLOBHASH is an invalid opcode before Cancun, delegations are not followed before
    * Prague). That silently returns something other than what the caller asked for, which the fail-loudly rule this
    * codebase follows does not allow (same precedent as S1's gasPrice + accessList rule). So:
    *   - an explicit `type` that is not active at the block (`TxTypeGate.activeTypes`): refused;
    *   - blobVersionedHashes before Cancun (every ETC block; ETH before Cancun): refused;
    *   - authorizationList where EIP-7702 is not active (ETC before Olympia, ETH before Prague): refused;
    *   - maxFeePerGas / maxPriorityFeePerGas at a block without a base fee (ETC before Olympia, ETH before London):
    *     -32000 "maxFeePerGas and maxPriorityFeePerGas are not valid before London is active", geth's `CallDefaults`
    *     error ([[checkLondon]], which runs first);
    *   - accessList: accepted everywhere, as geth accepts it (before Berlin warmth is not priced, so it is inert).
    */
  private def checkActive(args: TxArgs, header: BlockHeader)(using
      blockchainConfig: BlockchainConfig
  ): Either[JsonRpcError, Unit] =
    // the one per-chain source of truth for which types a block admits (S0, transactions/TxAdmission.scala)
    val active = TxTypeGate.activeTypes(header)
    val required: List[(Byte, Boolean)] = List(
      args.txType.getOrElse(TxArgs.LegacyType) -> true,
      TxArgs.BlobType -> args.blobVersionedHashes.isDefined,
      TxArgs.SetCodeType -> args.authorizationList.isDefined
    )
    required.collectFirst { case (t, true) if !active.contains(t) => t } match
      case Some(t) =>
        Left(
          JsonRpcError.InvalidParams(
            s"transaction type not supported: type ${t & 0xff} not active at block ${header.number.value}"
          )
        )
      case None => Right(())

  /** geth `BlockOverrides.Apply` on the block context, here on a copy of the header. A number or time override moves
    * the fork rules with it, as geth's `ChainConfig.Rules(blockCtx.BlockNumber, ..., blockCtx.Time)` does. A base fee
    * override on a header with no base fee field (pre-London) has nothing to set and is ignored.
    */
  def applyBlockOverrides(header: BlockHeader, overrides: Option[BlockOverrides]): BlockHeader =
    overrides.fold(header) { o =>
      val h = header.copy(
        number = o.number.map(BlockNumber(_)).getOrElse(header.number),
        unixTimestamp = o.time.map(t => Timestamp(t.toLong)).getOrElse(header.unixTimestamp),
        gasLimit = o.gasLimit.map(GasAmount(_)).getOrElse(header.gasLimit),
        beneficiary = o.feeRecipient.map(_.bytes).getOrElse(header.beneficiary),
        mixHash = o.prevRandao.map(BlockHash(_)).getOrElse(header.mixHash),
        difficulty = o.difficulty.map(Difficulty(_)).getOrElse(header.difficulty)
      )
      o.baseFeePerGas.fold(h)(withBaseFee(h, _))
    }

  /** The header with its base fee replaced; unchanged when the header has no base fee field. Every header shape that
    * carries a base fee is listed, so an Amsterdam header cannot silently keep its base fee.
    */
  def withBaseFee(header: BlockHeader, baseFee: BigInt): BlockHeader =
    val extra = header.extraFields match
      case HefPostOlympia(_)                    => HefPostOlympia(baseFee)
      case HefPostShanghai(_, wr)               => HefPostShanghai(baseFee, wr)
      case HefPostCancun(_, wr, bg, eb, pb)     => HefPostCancun(baseFee, wr, bg, eb, pb)
      case HefPostPrague(_, wr, bg, eb, pb, rh) => HefPostPrague(baseFee, wr, bg, eb, pb, rh)
      case HefPostAmsterdam(_, wr, bg, eb, pb, rh, bal, slot) =>
        HefPostAmsterdam(baseFee, wr, bg, eb, pb, rh, bal, slot)
      case other => other
    header.copy(extraFields = extra)
