package com.chipprbots.ethereum.ledger

import scala.annotation.tailrec

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.consensus.engine.BlobGasUtils
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlobTransaction
import com.chipprbots.ethereum.domain.TransactionWithDynamicFee
import com.chipprbots.ethereum.vm.OutOfGas
import com.chipprbots.ethereum.vm.RevertOccurs
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.BlockchainImpl
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.SetCodeAuthorization
import com.chipprbots.ethereum.domain.SetCodeTransaction
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.domain.Transaction
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.vm.EvmConfig
import com.chipprbots.ethereum.vm.ExecutionTracer
import com.chipprbots.ethereum.vm.ProgramError

class StxLedger(
    blockchain: BlockchainImpl,
    blockchainReader: BlockchainReader,
    evmCodeStorage: EvmCodeStorage,
    blockPreparator: BlockPreparator,
    configBuilder: BlockchainConfigBuilder
):
  import configBuilder.*

  /** Runs `stx` on the bare VM: the upfront charge at `gasPrice`, the intrinsic cost, and the top-level frame, with no
    * transaction-level settlement after it. What eth_call, eth_createAccessList, GraphQL `call`, the eth_estimateGas
    * pre-check, debug_traceCall and trace_call/_callMany run. At an Amsterdam block that frame already carries
    * EIP-2780's recipient-creation and delegation-access charges and EIP-8037's state-gas reservoir (`ProgramContext`),
    * and EIP-7708's transfer logs (`VM`). What it does not do, and block execution
    * (`BlockPreparator.executeTransaction`) does:
    *   - apply and charge an EIP-7702 authorization list (the calls built from RPC arguments carry none);
    *   - charge the calldata floor (EIP-7623 from Prague, EIP-7976 / EIP-7981 at Amsterdam): `gasUsed` is the gas
    *     execution took, never raised to the floor, and a gas limit below the floor is run rather than refused;
    *   - refund the sender, pay the coinbase, delete self-destructed (EIP-8246 at Amsterdam) and empty touched
    *     accounts.
    *
    * Mined transactions are replayed by [[replayTransaction]] instead, which at Amsterdam does all of that.
    */
  def simulateTransaction(
      stx: SignedTransactionWithSender,
      blockHeader: BlockHeader,
      world: Option[InMemoryWorldStateProxy]
  ): TxResult = simulateTransactionWithTracer(stx, blockHeader, world, tracer = None)

  /** Re-executes a transaction that a stored block carries, for the trace and debug RPCs (debug_traceTransaction,
    * debug_traceBlockBy*, debug_intermediateRoots, debug_traceChain, trace_transaction, trace_block, trace_replay*), on
    * `world`, the state just before it in that block ([[advanceWorldToTx]]).
    *
    * In an Amsterdam block it runs as block import ran it, through `BlockPreparator.executeTransaction`, with the
    * tracer on the top-level frame. So the replay applies and charges the EIP-7702 authorization list with EIP-2780's
    * execution and EIP-8037's state gas (and keeps delegations through an in-frame failure), charges the calldata floor
    * (EIP-7976 / EIP-7981), settles EIP-8037 / EIP-7778's accounting, refunds the sender, pays the coinbase, and
    * applies EIP-8246's balance-keeping SELFDESTRUCT and EIP-161's deletions. The world it hands on is the block's own,
    * and the gas the tracer is told at the end is the receipt's, as go-ethereum's tracers report `receipt.GasUsed`.
    *
    * A block before Amsterdam, and every ETC block, replays exactly as every replay did before WI-14: the bare VM of
    * [[simulateTransaction]] / [[simulateTransactionWithTracer]], so that output does not move. That path does none of
    * the settlement above (see [[simulateTransaction]]), which docs/specifications/GLAMSTERDAM.md sets out.
    */
  def replayTransaction(
      stx: SignedTransactionWithSender,
      blockHeader: BlockHeader,
      world: InMemoryWorldStateProxy,
      tracer: Option[ExecutionTracer]
  ): TxResult =
    if !blockchainConfig.isAmsterdamTimestamp(blockHeader.unixTimestamp) then
      tracer match
        case Some(t) => simulateTransactionWithTracer(stx, blockHeader, Some(world), t)
        case None    => simulateTransaction(stx, blockHeader, Some(world))
    else
      val tx = stx.tx.tx
      val sender = stx.senderAddress
      // What block execution does before each transaction (BlockPreparator.executeTransactions): the sender exists.
      val worldWithSender =
        world.saveAccount(sender, world.getAccount(sender).getOrElse(Account.empty(blockchainConfig.accountStartNonce)))
      tracer.foreach(_.onTxStart(sender, tx.receivingAddress, tx.gasLimit.value, tx.value, tx.payload))
      val result = blockPreparator.executeTransaction(stx.tx, sender, blockHeader, worldWithSender, tracer = tracer)
      tracer.foreach(_.onTxEnd(result.gasUsed, result.vmReturnData, result.vmError.map(_.toString)))
      result

  /** Like `simulateTransaction` but threads an optional EVM tracer into the run. Used by `debug_traceTransaction` /
    * `debug_traceCall` to capture per-opcode structLog entries. The sim still honors world / blockHeader so the trace
    * reflects post-block state (historical replay requires archive mode).
    */
  def simulateTransactionWithTracer(
      stx: SignedTransactionWithSender,
      blockHeader: BlockHeader,
      world: Option[InMemoryWorldStateProxy],
      tracer: Option[ExecutionTracer]
  ): TxResult =
    val result = runSimulated(stx, blockHeader, world, tracer)
    val totalGasToRefund = blockPreparator.calcTotalGasToRefund(stx.tx, result, blockHeader.number.value)

    TxResult(
      result.world,
      stx.tx.tx.gasLimit.value - totalGasToRefund,
      result.logs,
      result.returnData,
      result.error
    )

  /** Runs `stx` against a throwaway world and hands back the raw [[PR]].
    *
    * [[simulateTransactionWithTracer]] narrows this to a [[TxResult]]; [[binarySearchGasEstimation]] needs the
    * un-narrowed result because it reads `codeDepositShortfall`, which deliberately does not exist on `TxResult` —
    * keeping it off the type that block execution and the receipt path consume is what makes it impossible for the flag
    * to influence consensus.
    */
  private def runSimulated(
      stx: SignedTransactionWithSender,
      blockHeader: BlockHeader,
      world: Option[InMemoryWorldStateProxy],
      tracer: Option[ExecutionTracer]
  ): PR =
    val tx = stx.tx

    val world1 = world.getOrElse(
      InMemoryWorldStateProxy(
        evmCodeStorage = evmCodeStorage,
        mptStorage = blockchain.getReadOnlyMptStorage(),
        getBlockHashByNumber = (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash.value),
        accountStartNonce = blockchainConfig.accountStartNonce,
        stateRootHash = blockHeader.stateRoot.value,
        noEmptyAccounts = EvmConfig.forBlock(blockHeader.number.value, blockchainConfig).noEmptyAccounts,
        ethCompatibleStorage = blockchainConfig.ethCompatibleStorage
      )
    )

    val senderAddress = stx.senderAddress
    val world2 =
      if world1.getAccount(senderAddress).isEmpty then
        world1.saveAccount(senderAddress, Account.empty(blockchainConfig.accountStartNonce))
      else world1

    val worldForTx = blockPreparator.updateSenderAccountBeforeExecution(tx, senderAddress, world2)
    blockPreparator.runVM(tx, senderAddress, blockHeader, worldForTx, tracer = tracer)

  /** Like [[simulateTransaction]] but attaches a tracer and fires the tx-level lifecycle hooks.
    *
    * Besu reference: DebugTraceTransaction.java — creates DebugOperationTracer, passes via processTracing core-geth
    * reference: eth/tracers/api.go traceTx() — wraps evm.Config.Tracer, calls CaptureStart/CaptureEnd
    *
    * Caller is responsible for selecting the tracer and extracting [[tracer.getResult]] afterwards.
    */
  def simulateTransactionWithTracer(
      stx: SignedTransactionWithSender,
      blockHeader: BlockHeader,
      world: Option[InMemoryWorldStateProxy],
      tracer: ExecutionTracer
  ): TxResult =
    val tx = stx.tx

    val world1 = world.getOrElse(
      InMemoryWorldStateProxy(
        evmCodeStorage = evmCodeStorage,
        mptStorage = blockchain.getReadOnlyMptStorage(),
        getBlockHashByNumber = (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash.value),
        accountStartNonce = blockchainConfig.accountStartNonce,
        stateRootHash = blockHeader.stateRoot.value,
        noEmptyAccounts = EvmConfig.forBlock(blockHeader.number.value, blockchainConfig).noEmptyAccounts,
        ethCompatibleStorage = blockchainConfig.ethCompatibleStorage
      )
    )

    val senderAddress = stx.senderAddress
    val world2 =
      if world1.getAccount(senderAddress).isEmpty then
        world1.saveAccount(senderAddress, Account.empty(blockchainConfig.accountStartNonce))
      else world1

    val worldForTx = blockPreparator.updateSenderAccountBeforeExecution(tx, senderAddress, world2)
    tracer.onTxStart(senderAddress, tx.tx.receivingAddress, tx.tx.gasLimit.value, tx.tx.value, tx.tx.payload)
    val result = blockPreparator.runVMWithTracer(tx, senderAddress, blockHeader, worldForTx, tracer)
    val totalGasToRefund = blockPreparator.calcTotalGasToRefund(tx, result, blockHeader.number.value)
    val gasUsed = tx.tx.gasLimit.value - totalGasToRefund
    tracer.onTxEnd(gasUsed, result.returnData, result.error.map(_.toString))

    TxResult(result.world, gasUsed, result.logs, result.returnData, result.error)

  /** Advances a world state through prior transactions in a block to reach the state just before transaction at
    * [[txIndex]]. Used by [[DebugTracingService]] and [[TraceService]] for historical trace replay.
    *
    * Besu reference: BlockReplay.beforeTransactionInBlock() — replays all transactions up to target index core-geth
    * reference: eth/tracers/api.go computeTxEnv() — builds state via ApplyMessage for each prior tx
    *
    * @param blockHeader
    *   block header containing the transactions
    * @param txs
    *   all transactions in the block with recovered sender addresses
    * @param txIndex
    *   index of the target transaction (0-based); returns parent state if 0
    * @param parentStateRoot
    *   state root of the parent block (baseline)
    * @return
    *   world state at the point just before txs(txIndex) executes
    */
  def advanceWorldToTx(
      blockHeader: BlockHeader,
      txs: Seq[SignedTransactionWithSender],
      txIndex: Int,
      parentStateRoot: org.apache.pekko.util.ByteString
  ): InMemoryWorldStateProxy =
    if !blockchainConfig.isAmsterdamTimestamp(blockHeader.unixTimestamp) then
      val world0 = InMemoryWorldStateProxy(
        evmCodeStorage = evmCodeStorage,
        mptStorage = blockchain.getReadOnlyMptStorage(),
        getBlockHashByNumber = (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash.value),
        accountStartNonce = blockchainConfig.accountStartNonce,
        stateRootHash = parentStateRoot,
        noEmptyAccounts = EvmConfig.forBlock(blockHeader.number.value, blockchainConfig).noEmptyAccounts,
        ethCompatibleStorage = blockchainConfig.ethCompatibleStorage
      )
      (0 until txIndex).foldLeft(world0) { (world, i) =>
        simulateTransaction(txs(i), blockHeader, Some(world)).worldState
      }
    else
      // Amsterdam: the state block execution had before `txs(txIndex)`. The parent's state, then the block's EIP-4788 /
      // EIP-2935 preamble (system calls from Amsterdam, as BlockExecution runs them), then each earlier transaction
      // replayed as block import executed it. BLOCKHASH walks the block's own ancestry, as in block execution
      // (BlockExecution.buildInitialWorld), which matters when the block being traced is not canonical.
      val world0 = InMemoryWorldStateProxy(
        evmCodeStorage = evmCodeStorage,
        mptStorage = new StxLedger.ReplayMptStorage(blockchain.getReadOnlyMptStorage()),
        getBlockHashByNumber = AncestorBlockHashes.forBlock(blockHeader, blockchainReader),
        accountStartNonce = blockchainConfig.accountStartNonce,
        stateRootHash = parentStateRoot,
        noEmptyAccounts = EvmConfig.forBlock(blockHeader.number.value, blockchainConfig).noEmptyAccounts,
        ethCompatibleStorage = blockchainConfig.ethCompatibleStorage
      )
      val afterPreamble = BlockExecution.applyAmsterdamPreambleSystemCalls(blockHeader, world0, accessRecorder = None)
      (0 until txIndex).foldLeft(afterPreamble) { (world, i) =>
        replayTransaction(txs(i), blockHeader, world, tracer = None).worldState
      }

  // ─── Message calls (eth_call, eth_estimateGas, eth_createAccessList, debug_traceCall, trace_call, GraphQL) ───────

  /** A fresh world on `blockHeader`'s state: what [[simulateCall]] runs on when handed none. The RPC layer asks for one
    * only when it must change it first (state overrides) or read it (eth_createAccessList's creation address).
    */
  def worldAt(blockHeader: BlockHeader): InMemoryWorldStateProxy =
    InMemoryWorldStateProxy(
      evmCodeStorage = evmCodeStorage,
      mptStorage = blockchain.getReadOnlyMptStorage(),
      getBlockHashByNumber = (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash.value),
      accountStartNonce = blockchainConfig.accountStartNonce,
      stateRootHash = blockHeader.stateRoot.value,
      noEmptyAccounts = EvmConfig.forBlock(blockHeader.number.value, blockchainConfig).noEmptyAccounts,
      ethCompatibleStorage = blockchainConfig.ethCompatibleStorage
    )

  /** Executes an RPC message call: go-ethereum's `core.ApplyMessage` as `internal/ethapi` `doCall` and
    * `eth/tracers/api.go` `TraceCall` drive it (`SkipNonceChecks`, `SkipTransactionChecks`, `vm.Config{NoBaseFee:
    * true}`), on the bare frame [[simulateTransaction]] runs.
    *
    * First geth's `preCheck` / `buyGas` / intrinsic-gas checks ([[callPreCheck]]); a failure is returned as geth's
    * message, nothing executes. Then, unlike [[simulateTransaction]]:
    *   - the upfront charge is `gasLimit * effectiveGasPrice + blob gas * blob base fee` (geth `buyGas`), not `gasLimit
    *     * maxFeePerGas`;
    *   - an EIP-7702 authorization list is applied, and charged, before the frame, as block execution applies it (geth
    *     `execute` -> `applyAuthorization`), with the recovered authorities warm and the pre-Amsterdam existing-
    *     authority refund;
    *   - `movePrecompileToAddress` relocations and the blob base fee override reach the frame.
    *
    * For a legacy or EIP-2930 message with no authorization list, every one of those is the identity, so the result is
    * byte-for-byte what [[simulateTransaction]] / [[simulateTransactionWithTracer]] return. The frame is the bare VM
    * frame described on [[simulateTransaction]]: no calldata floor, no coinbase payment, no deletions.
    *
    * With a tracer, `onTxStart` / `onTxEnd` fire around the frame, and the tracer rides on a VM of its own, as for
    * [[simulateTransactionWithTracer]].
    */
  def simulateCall(
      stx: SignedTransactionWithSender,
      blockHeader: BlockHeader,
      world: Option[InMemoryWorldStateProxy],
      options: StxLedger.CallOptions,
      tracer: Option[ExecutionTracer]
  ): Either[StxLedger.CallPreCheckError, TxResult] =
    val world1 = world.getOrElse(worldAt(blockHeader))
    callPreCheck(stx, blockHeader, world1, options).map { _ =>
      val (result, authRefund) = runCallFrame(stx, blockHeader, world1, options, tracer)
      val gasUsed = callGasUsed(stx, blockHeader, result, authRefund)
      tracer.foreach(_.onTxEnd(gasUsed, result.returnData, result.error.map(_.toString)))
      TxResult(result.world, gasUsed, result.logs, result.returnData, result.error)
    }

  /** eth_estimateGas on the message-call path (go-ethereum `eth/gasestimator.Estimate`).
    *
    *   1. When the message pays for gas (`feeCap > 0`) the ceiling is lowered to what the sender can afford: `(balance
    *      \- value - blob gas * maxFeePerBlobGas) / feeCap`; a value the balance does not exceed is "insufficient funds
    *      for transfer".
    *   1. The message runs once at the ceiling. A pre-check failure is returned (an intrinsic-gas or floor shortfall as
    *      geth's "gas required exceeds allowance"); a revert is returned with its data.
    *   1. Otherwise the binary search of [[binarySearchGasEstimation]], from [[estimationLowerBound]], on this path.
    *
    * `stx` carries the ceiling as its gas limit.
    */
  def estimateCallGas(
      stx: SignedTransactionWithSender,
      blockHeader: BlockHeader,
      world: Option[InMemoryWorldStateProxy],
      options: StxLedger.CallOptions
  ): Either[StxLedger.CallEstimateFailure, BigInt] =
    import StxLedger.CallEstimateFailure.*
    val world1 = world.getOrElse(worldAt(blockHeader))
    for
      hi <- affordableCeiling(stx, world1)
      stxHi = withGas(stx, hi)
      _ <- callPreCheck(stxHi, blockHeader, world1, options).left.map { e =>
        if e.gasShortfall then PreCheck(s"gas required exceeds allowance ($hi)") else PreCheck(e.message)
      }
      atHi = runCallFrame(stxHi, blockHeader, world1, options, tracer = None)._1
      // gasestimator.Estimate: a failure at the ceiling that is not out-of-gas is the answer (a revert with its data);
      // out of gas at the ceiling is "gas required exceeds allowance"
      _ <- atHi.error match
        case Some(RevertOccurs) => Left(Reverted(atHi.returnData))
        case Some(OutOfGas)     => Left(PreCheck(s"gas required exceeds allowance ($hi)"))
        case Some(other)        => Left(Failed(other))
        case None               => Right(())
    yield
      val lowLimit = estimationLowerBound(stxHi, blockHeader)
      if hi < lowLimit then hi
      else
        StxLedger.binaryChop(lowLimit, hi) { gasLimit =>
          val result = runCallFrame(withGas(stx, gasLimit), blockHeader, world1, options, tracer = None)._1
          // See binarySearchGasEstimation: a pre-Homestead CREATE short of its code deposit is no answer.
          if result.codeDepositShortfall then Some(StxLedger.CodeDepositShortfall)
          else result.error.map(StxLedger.VmFailure.apply)
        }

  /** The authority an EIP-7702 authorization recovers to, under the same checks block execution applies before
    * recovering it (chain id, nonce bound, canonical signature values); `None` when it would be skipped.
    * eth_createAccessList excludes these addresses, as go-ethereum's `AccessList` does.
    */
  def recoverAuthority(auth: SetCodeAuthorization): Option[Address] = blockPreparator.recoverAuthority(auth)

  private def withGas(stx: SignedTransactionWithSender, gas: BigInt): SignedTransactionWithSender =
    stx.copy(tx = stx.tx.copy(tx = Transaction.withGasLimit(GasAmount(gas))(stx.tx.tx)))

  /** geth gasestimator: recap the ceiling with the sender's balance when the message pays for gas. */
  private def affordableCeiling(
      stx: SignedTransactionWithSender,
      world: InMemoryWorldStateProxy
  ): Either[StxLedger.CallEstimateFailure, BigInt] =
    val tx = stx.tx.tx
    val hi = tx.gasLimit.value
    val feeCap = tx.gasPrice.value // the fee cap of a dynamic-fee type, the price of a legacy one
    if feeCap == 0 then Right(hi)
    else
      val balance = world.getBalance(stx.senderAddress).toBigInt
      if tx.value >= balance then Left(StxLedger.CallEstimateFailure.PreCheck("insufficient funds for transfer"))
      else
        val blobUsage = tx match
          case bt: BlobTransaction => BigInt(bt.blobVersionedHashes.size) * StxLedger.GasPerBlob * bt.maxFeePerBlobGas
          case _                   => BigInt(0)
        val available = balance - tx.value
        if blobUsage >= available && blobUsage > 0 then
          Left(StxLedger.CallEstimateFailure.PreCheck("insufficient funds for gas * price + value"))
        else Right(hi.min((available - blobUsage) / feeCap))

  /** The frame of a message call; also returns the pre-Amsterdam EIP-7702 existing-authority refund. */
  private def runCallFrame(
      stx: SignedTransactionWithSender,
      blockHeader: BlockHeader,
      world: InMemoryWorldStateProxy,
      options: StxLedger.CallOptions,
      tracer: Option[ExecutionTracer]
  ): (PR, BigInt) =
    val tx = stx.tx
    val sender = stx.senderAddress
    val world2 =
      if world.getAccount(sender).isEmpty then
        world.saveAccount(sender, Account.empty(blockchainConfig.accountStartNonce))
      else world
    val overrides = SimulationOverrides(
      precompileRelocations = options.precompileRelocations,
      traceTransfers = false,
      blobBaseFeeOverride = options.blobBaseFeeOverride,
      skipExecutionGasCap = options.skipExecutionGasCap
    )
    val worldForTx = blockPreparator.updateSenderAccountBeforeExecution(tx, sender, world2, blockHeader, overrides)
    tracer.foreach(_.onTxStart(sender, tx.tx.receivingAddress, tx.tx.gasLimit.value, tx.tx.value, tx.tx.payload))
    blockPreparator.runCallFrame(tx, sender, blockHeader, worldForTx, overrides, tracer)

  private def callGasUsed(
      stx: SignedTransactionWithSender,
      blockHeader: BlockHeader,
      result: PR,
      authRefund: BigInt
  ): BigInt =
    val withRefund = BlockPreparator.addAuthorizationRefund(result, authRefund)
    stx.tx.tx.gasLimit.value -
      blockPreparator.totalGasToRefundWithAuthorizations(stx.tx, withRefund, authRefund, blockHeader.number.value)

  /** go-ethereum `StateTransition.preCheck` (London fee checks, blob checks, set-code checks), `buyGas`'s balance check
    * and `execute`'s intrinsic-gas and EIP-7623 floor checks, with nonce and EOA checks skipped as a call skips them.
    * Messages are geth's (core/error.go), addresses EIP-55 encoded as geth prints them.
    */
  private[ledger] def callPreCheck(
      stx: SignedTransactionWithSender,
      blockHeader: BlockHeader,
      world: InMemoryWorldStateProxy,
      options: StxLedger.CallOptions
  ): Either[StxLedger.CallPreCheckError, Unit] =
    import StxLedger.CallPreCheckError as E
    val tx = stx.tx.tx
    val sender = stx.senderAddress
    def address = StxLedger.checksum(sender)
    val (feeCap, tipCap) = tx match
      case t: TransactionWithDynamicFee => (t.maxFeePerGas, t.maxPriorityFeePerGas)
      case t: BlobTransaction           => (t.maxFeePerGas, t.maxPriorityFeePerGas)
      case t: SetCodeTransaction        => (t.maxFeePerGas, t.maxPriorityFeePerGas)
      case t                            => (t.gasPrice.value, t.gasPrice.value)
    val (blobGas, blobFeeCap) = tx match
      case bt: BlobTransaction => (BigInt(bt.blobVersionedHashes.size) * StxLedger.GasPerBlob, bt.maxFeePerBlobGas)
      case _                   => (BigInt(0), BigInt(0))
    for
      // London: skipped when both caps are 0 under NoBaseFee
      _ <- blockHeader.baseFee match
        case Some(baseFee) if feeCap != 0 || tipCap != 0 =>
          if tipCap > feeCap then
            Left(
              E(
                s"max priority fee per gas higher than max fee per gas: address $address, " +
                  s"maxPriorityFeePerGas: $tipCap, maxFeePerGas: $feeCap"
              )
            )
          else if feeCap < baseFee then
            Left(
              E(s"max fee per gas less than block base fee: address $address, maxFeePerGas: $feeCap, baseFee: $baseFee")
            )
          else Right(())
        case _ => Right(())
      _ <- tx match
        case bt: BlobTransaction =>
          val badVersion = bt.blobVersionedHashes.zipWithIndex.collectFirst {
            case (h, i) if h.value.length != 32 || h.value(0) != StxLedger.BlobCommitmentVersionKzg => i
          }
          if bt.blobVersionedHashes.isEmpty then Left(E("blob transaction missing blob hashes"))
          // Osaka (EIP-7594): at most BlobTxMaxBlobs per transaction; geth core ErrTooManyBlobs
          else if blockchainConfig.isOsakaTimestamp(blockHeader.unixTimestamp) &&
            bt.blobVersionedHashes.size > StxLedger.BlobTxMaxBlobs
          then Left(E("blob transaction has too many blobs"))
          else if badVersion.isDefined then Left(E(s"blob ${badVersion.get} has invalid hash version"))
          else
            val blobBaseFee = options.blobBaseFeeOverride.getOrElse(
              blockHeader.excessBlobGas
                .map(BlobGasUtils.getBlobGasPrice(_, blockHeader.unixTimestamp, blockchainConfig))
                .getOrElse(BigInt(1))
            )
            if blobFeeCap != 0 && blobFeeCap < blobBaseFee then
              Left(
                E(
                  s"max fee per blob gas less than block blob gas fee: address $address " +
                    s"blobGasFeeCap: $blobFeeCap, blobBaseFee: $blobBaseFee"
                )
              )
            else Right(())
        case sct: SetCodeTransaction if sct.authorizationList.isEmpty =>
          Left(E(s"EIP-7702 transaction with empty auth list (sender $address)"))
        case _ => Right(())
      // EIP-3860 (geth preCheck `vm.CheckMaxInitCodeSize`), the same rule and limit the pool and validator apply
      _ <-
        val evmConfig = EvmConfig.forBlock(blockHeader.number.value, blockHeader.unixTimestamp, blockchainConfig)
        evmConfig.maxInitCodeSize match
          case Some(max) if tx.isContractInit && evmConfig.eip3860Enabled && tx.payload.size > max =>
            Left(E(s"max initcode size exceeded: code size ${tx.payload.size}, limit $max"))
          case _ => Right(())
      _ <-
        val want = tx.gasLimit.value * feeCap + tx.value + blobGas * blobFeeCap
        if want == 0 then Right(())
        else
          val have = world.getBalance(sender).toBigInt
          if have < want then
            Left(E(s"insufficient funds for gas * price + value: address $address have $have want $want"))
          else Right(())
      _ <-
        val (intrinsic, floor) = intrinsicAndFloor(stx, blockHeader)
        val gas = tx.gasLimit.value
        if gas < intrinsic then Left(E(s"intrinsic gas too low: have $gas, want $intrinsic", gasShortfall = true))
        else
          floor match
            case Some(f) if gas < f =>
              Left(E(s"insufficient gas for floor data gas cost: have $gas, want $f"))
            case _ => Right(())
    yield ()

  /** The calldata floor of `stx` where it is a validity rule (EIP-7623 from Prague / ETC Olympia, EIP-7976 at
    * Amsterdam), else `None`. go-ethereum's `ExecutionResult.UsedGas` is never below it, which eth_createAccessList
    * reports.
    */
  def callFloorGas(stx: SignedTransactionWithSender, blockHeader: BlockHeader): Option[BigInt] =
    intrinsicAndFloor(stx, blockHeader)._2

  /** The intrinsic gas of `stx` and, where a calldata floor is a validity rule, the floor; the same functions
    * [[estimationLowerBound]] and the validator use.
    */
  private def intrinsicAndFloor(stx: SignedTransactionWithSender, blockHeader: BlockHeader): (BigInt, Option[BigInt]) =
    val number = blockHeader.number.value
    val timestamp = blockHeader.unixTimestamp
    val tx = stx.tx.tx
    val sender = stx.senderAddress
    val config = EvmConfig.forBlock(number, timestamp, blockchainConfig)
    val accessList = Transaction.accessList(tx)
    val authorizations = tx match
      case sct: SetCodeTransaction => sct.authorizationList.size
      case _                       => 0
    val intrinsic = config.calcTransactionIntrinsicGas(
      tx.payload,
      tx.isContractInit,
      accessList,
      authorizations,
      tx.receivingAddress,
      UInt256(tx.value),
      sender
    )
    val floor =
      if config.amsterdamEnabled then
        Some(
          config.calcAmsterdamCalldataFloorGas(tx.payload, accessList, tx.receivingAddress, UInt256(tx.value), sender)
        )
      else if BlockPreparator.eip7623Active(number, timestamp) then
        Some(
          BlockPreparator.calcFloorDataGas(
            tx.payload,
            config.transactionBaseCost(tx.receivingAddress, UInt256(tx.value), sender)
          )
        )
      else None
    (intrinsic, floor)

  def binarySearchGasEstimation(
      stx: SignedTransactionWithSender,
      blockHeader: BlockHeader,
      world: Option[InMemoryWorldStateProxy]
  ): BigInt =
    val lowLimit = estimationLowerBound(stx, blockHeader)
    val tx = stx.tx
    val highLimit = tx.tx.gasLimit

    if highLimit.value < lowLimit then highLimit.value
    else
      StxLedger.binaryChop(lowLimit, highLimit.value) { gasLimit =>
        val result = runSimulated(
          stx.copy(tx = tx.copy(tx = Transaction.withGasLimit(GasAmount(gasLimit))(tx.tx))),
          blockHeader,
          world,
          tracer = None
        )
        // A pre-Homestead CREATE that ran its init code but could not afford the 200/byte code deposit is a
        // SUCCESS as far as consensus is concerned (Frontier keeps the gas and the state, and go-ethereum's
        // `opCreate` discards the error) — but it deployed NO code, so it is not an acceptable answer for
        // `eth_estimateGas`. Treating it as success here made the binary search converge on the minimum gas to
        // merely RUN the init code, omitting `len(runtimeCode) * G_codedeposit` from the estimate (measured:
        // hive graphql `04_eth_estimateGas_contractDeploy` returned 0xa959, expected 0x1b551 — short by exactly
        // 343 * 200). `codeDepositShortfall` is read ONLY here; it is not a `ProgramError` and no consensus
        // path sees it.
        if result.codeDepositShortfall then Some(StxLedger.CodeDepositShortfall)
        else result.error.map(StxLedger.VmFailure.apply)
      }

  /** Where [[binarySearchGasEstimation]] starts: the smallest gas limit it ever tries.
    *
    * Where a calldata floor is a validity rule, that is the least VALID gas limit, `max(intrinsic, floor)`, computed as
    * `StdSignedTransactionValidator.validateGasLimitEnoughForIntrinsicGas` computes it: the same timestamp-aware
    * config, the same functions and the same activation predicate, so the estimate and the rule cannot disagree.
    *   - Amsterdam: EIP-7976's floor, 64 gas per calldata byte plus EIP-7981's access-list data cost, on EIP-2780's
    *     base.
    *   - Otherwise wherever [[BlockPreparator.eip7623Active]] holds (ETH Prague and Osaka, ETC Olympia): EIP-7623's
    *     `21,000 + 10 * tokens`.
    *
    * The floor is charged after execution, not by the VM. The VM succeeds as soon as the intrinsic cost is covered, so
    * a search starting at 21,000 settled on the intrinsic cost and returned a gas limit every node rejects: 37,000 for
    * a zero-value call carrying 1,000 non-zero bytes on Prague (floor 61,000), 31,000 at Amsterdam (floor 79,000). At
    * Amsterdam 21,000 was also too high a start: EIP-2780 makes a self-transfer valid at 12,000.
    *
    * Everywhere else (ETH before Prague, ETC before Olympia) the start is `G_transaction`, 21,000, exactly as before:
    * gas below the rest of the intrinsic cost fails in the VM, so the search climbs past it.
    *
    * A transaction whose bound exceeds the gas cap is answered with the cap, as one that fails at every limit always
    * was. At Amsterdam a bound above TX_MAX_GAS_LIMIT (2^24) cannot be valid at any gas limit; the search answers the
    * bound all the same.
    */
  private[ledger] def estimationLowerBound(stx: SignedTransactionWithSender, blockHeader: BlockHeader): BigInt =
    val number = blockHeader.number.value
    val timestamp = blockHeader.unixTimestamp
    val floorIsValidityRule =
      blockchainConfig.isAmsterdamTimestamp(timestamp) || BlockPreparator.eip7623Active(number, timestamp)
    if !floorIsValidityRule then EvmConfig.forBlock(number, blockchainConfig).feeSchedule.G_transaction
    else
      val tx = stx.tx.tx
      val sender = stx.senderAddress
      val config = EvmConfig.forBlock(number, timestamp, blockchainConfig)
      val accessList = Transaction.accessList(tx)
      val authorizations = tx match
        case sct: SetCodeTransaction => sct.authorizationList.size
        case _                       => 0
      val intrinsic = config.calcTransactionIntrinsicGas(
        tx.payload,
        tx.isContractInit,
        accessList,
        authorizations,
        tx.receivingAddress,
        UInt256(tx.value),
        sender
      )
      val floor =
        if config.amsterdamEnabled then
          config.calcAmsterdamCalldataFloorGas(tx.payload, accessList, tx.receivingAddress, UInt256(tx.value), sender)
        else
          BlockPreparator.calcFloorDataGas(
            tx.payload,
            config.transactionBaseCost(tx.receivingAddress, UInt256(tx.value), sender)
          )
      intrinsic.max(floor)

object StxLedger:

  /** What an RPC message call carries besides its transaction and header (see [[StxLedger.simulateCall]]).
    *
    * @param precompileRelocations
    *   eth_call / eth_estimateGas `movePrecompileToAddress` state overrides
    * @param blobBaseFeeOverride
    *   the blob base fee the upfront charge uses: 0 when the call's maxFeePerBlobGas is 0 or absent (geth lowers the
    *   blob base fee to 0 then), or a `blobBaseFee` block override
    * @param skipExecutionGasCap
    *   go-ethereum `Message.SkipExecutionGasCapCheck()`: at Amsterdam the whole gas left after the intrinsic cost is
    *   execution gas, not only up to TX_MAX_GAS_LIMIT (EIP-8037). geth sets it for eth_call (internal/ethapi/api.go
    *   `applyMessage`) and debug_traceCall (eth/tracers/api.go `TraceCall`), not for eth_estimateGas (gasestimator) or
    *   eth_createAccessList (api.go `AccessList`). No effect before Amsterdam.
    */
  final case class CallOptions(
      precompileRelocations: Map[Address, Address],
      blobBaseFeeOverride: Option[BigInt],
      skipExecutionGasCap: Boolean
  )

  object CallOptions:
    val Empty: CallOptions = CallOptions(Map.empty, None, skipExecutionGasCap = false)

  /** A message call refused before execution, with go-ethereum's message. `gasShortfall` marks the intrinsic-gas check,
    * the one go-ethereum's estimator treats as "raise the gas limit" (gasestimator.go `execute`:
    * `core.ErrIntrinsicGas`) and so reports as "gas required exceeds allowance"; a calldata-floor shortfall keeps its
    * own message there, as in geth.
    */
  final case class CallPreCheckError(message: String, gasShortfall: Boolean = false)

  /** Why eth_estimateGas has no answer. */
  enum CallEstimateFailure:
    /** Refused before execution, or unaffordable; geth's message. -32000. */
    case PreCheck(message: String)

    /** Reverted at the highest gas tried; the revert data. Error code 3. */
    case Reverted(data: ByteString)

    /** Failed at the highest gas tried for a reason other than gas (geth returns `result.Err`). -32000. */
    case Failed(error: ProgramError)

  /** EIP-7594 (Osaka) per-transaction blob limit, go-ethereum `params.BlobTxMaxBlobs`. */
  val BlobTxMaxBlobs: Int = 6

  /** EIP-4844 GAS_PER_BLOB. */
  val GasPerBlob: BigInt = BigInt(131072)

  /** EIP-4844 VERSIONED_HASH_VERSION_KZG. */
  val BlobCommitmentVersionKzg: Byte = 0x01

  /** EIP-55 mixed-case hex, as go-ethereum's `Address.Hex()` prints addresses in its error messages. */
  private[ledger] def checksum(address: Address): String =
    val hex = address.bytes.toArray.map(b => f"${b & 0xff}%02x").mkString
    val hash = kec256(hex.getBytes("US-ASCII")).map(b => f"${b & 0xff}%02x").mkString
    "0x" + hex
      .zip(hash)
      .map { case (c, h) => if c.isLetter && Character.digit(h, 16) >= 8 then c.toUpper else c }
      .mkString

  /** The node store an Amsterdam replay runs on (`advanceWorldToTx`): the read-only store, whose reads fall through to
    * the node database and whose writes stay in memory, except that it drops the nodes an update would remove.
    *
    * An Amsterdam replay persists every world it produces, the preamble's and each transaction's, as block execution
    * does, and each persist names the nodes it replaces for removal. In the read-only store's buffer a removal deletes
    * the node for good, and a node the replay itself wrote exists nowhere else. The world a transaction was replayed ON
    * would then lose its own root, and every reader that comes back to it would fail with a missing node:
    * `prestateTracer`, which reads the pre-state after the run, and trace_replay*'s second tracer (vmTrace), which runs
    * the transaction again on the same pre-state. Keeping every node keeps every world of the replay readable. The
    * buffer is dropped with the request, and nothing is written to the database either way.
    */
  final private[ledger] class ReplayMptStorage(underlying: MptStorage) extends MptStorage:
    override def get(nodeId: Array[Byte]): MptNode = underlying.get(nodeId)
    override def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] =
      underlying.updateNodesInStorage(newRoot, Nil)
    override def persist(): Unit = underlying.persist()
    override def multiGetNodes(hashes: Seq[Array[Byte]]): Seq[Option[MptNode]] = underlying.multiGetNodes(hashes)
    override def storeRawNodes(nodes: Seq[(org.apache.pekko.util.ByteString, Array[Byte])]): Unit =
      underlying.storeRawNodes(nodes)

  /** Why [[StxLedger.binarySearchGasEstimation]] rejected a candidate gas limit.
    *
    * Deliberately NOT a [[ProgramError]]: [[CodeDepositShortfall]] is not an execution failure — Frontier treats a
    * CREATE that cannot pay its code deposit as a success — it is only an unacceptable answer for `eth_estimateGas`.
    * Modelling it as a `ProgramError` is what let it leak onto the consensus path.
    */
  sealed private[ledger] trait EstimationFailure

  /** The VM halted. Anything a real execution would also treat as a failure. */
  private[ledger] case class VmFailure(error: ProgramError) extends EstimationFailure

  /** Pre-Homestead CREATE succeeded but deployed no code because it could not afford `200 * len(code)`. */
  private[ledger] case object CodeDepositShortfall extends EstimationFailure

  /** Function finds minimal value in some interval for which provided function do not return error If searched value is
    * not in provided interval, function returns maximum value of searched interval
    * @param min
    *   minimum of searched interval
    * @param max
    *   maximum of searched interval
    * @param f
    *   function which return error in case to little value provided
    * @return
    *   minimal value for which provided function do not return error
    */
  @tailrec
  private[ledger] def binaryChop[Err](min: BigInt, max: BigInt)(f: BigInt => Option[Err]): BigInt =
    assert(min <= max)

    if min == max then max
    else
      val mid = min + (max - min) / 2
      val possibleError = f(mid)
      if possibleError.isEmpty then binaryChop(min, mid)(f)
      else binaryChop(mid + 1, max)(f)
