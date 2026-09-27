package com.chipprbots.ethereum.ledger

import scala.annotation.tailrec

import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.BlockchainImpl
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.SetCodeTransaction
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.domain.Transaction
import com.chipprbots.ethereum.domain.UInt256
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

  def simulateTransaction(
      stx: SignedTransactionWithSender,
      blockHeader: BlockHeader,
      world: Option[InMemoryWorldStateProxy]
  ): TxResult = simulateTransactionWithTracer(stx, blockHeader, world, tracer = None)

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
