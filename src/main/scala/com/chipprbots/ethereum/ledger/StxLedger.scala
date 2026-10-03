package com.chipprbots.ethereum.ledger

import scala.annotation.tailrec

import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.BlockchainImpl
import com.chipprbots.ethereum.domain.BlockchainReader
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
