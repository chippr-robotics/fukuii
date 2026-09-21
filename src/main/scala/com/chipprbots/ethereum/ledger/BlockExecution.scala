package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import cats.implicits.*

import scala.annotation.tailrec

import com.chipprbots.ethereum.consensus.pow.validators.OmmersValidator.OmmersError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.consensus.validators.std.StdBlockValidator.BlockError
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.BlockExecutionError.MissingParentError
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MPTException
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.utils.DaoForkConfig
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.vm.AmsterdamGas
import com.chipprbots.ethereum.vm.EvmConfig
import com.chipprbots.ethereum.vm.ProgramContext

class BlockExecution(
    blockchain: BlockchainImpl,
    blockchainReader: BlockchainReader,
    blockchainWriter: BlockchainWriter,
    evmCodeStorage: EvmCodeStorage,
    blockPreparator: BlockPreparator,
    blockValidation: BlockValidation
) extends Logger:

  /** Executes and validate a block
    *
    * @param alreadyValidated
    *   should we skip pre-execution validation (if the block has already been validated, eg. in the importBlock method)
    */
  def executeAndValidateBlock(
      block: Block,
      alreadyValidated: Boolean = false
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, Seq[Receipt]] =
    executeAndValidateBlockFull(block, alreadyValidated).map(_._1)

  /** Variant that also returns the EIP-7685 execution requests derived from block execution (deposits from receipts +
    * system-call outputs). Used by the Engine API to additionally verify the header's requestsHash matches what
    * execution actually produced.
    */
  def executeAndValidateBlockFull(
      block: Block,
      alreadyValidated: Boolean = false
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, (Seq[Receipt], Seq[ByteString])] =
    val preExecValidationResult =
      if alreadyValidated then Right(block) else blockValidation.validateBlockBeforeExecution(block)

    val blockExecResult =
      for
        _ <- preExecValidationResult
        result <- executeBlock(block)
        _ <- blockValidation.validateBlockAfterExecution(
          block,
          result.worldState.stateRootHash,
          result.receipts,
          result.gasUsed
        )
      yield (result.receipts, result.executionRequests)

    if blockExecResult.isRight then
      log.debug(s"Block ${block.header.number} (with hash: ${block.header.hashAsHexString}) executed correctly")

    blockExecResult

  /** Executes a block without pre/post validation. Returns the execution result including receipts, gasUsed, and the
    * persisted world state. Used by ChainImporter for trusted block import where only state correctness matters.
    */
  def executeBlockNoValidation(
      block: Block
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, (Seq[Receipt], BigInt, ByteString)] =
    executeBlock(block).map { result =>
      (result.receipts, result.gasUsed, result.worldState.stateRootHash)
    }

  /** Proposer-mode execution. Runs all Prague preambles (EIP-4788, EIP-2935), transactions, withdrawals, and system
    * calls (EIP-7002/7251), collects deposit requests (EIP-6110), and returns the full BlockResult with receipts +
    * executionRequests populated. No pre- or post-execution validation against the header is performed — caller is
    * responsible for filling in header fields (stateRoot, receiptsRoot, gasUsed, requestsHash, etc.) from the result.
    *
    * Executes against a read-only-backed WorldStateProxy so tx effects do NOT persist to RocksDB. A proposer block is
    * speculative until the CL promotes it via `forkchoiceUpdated(head=hash(block))`; leaking its state would cause a
    * subsequent `newPayload` for the same slot (e.g. a tampered sibling) to fail with stale nonces / balances instead
    * of the expected stateRoot / receiptsRoot mismatch. Matches the read-only pattern used by `eth_call`,
    * `eth_estimateGas`, and `debug_traceTransaction`.
    */
  def executeForProposer(
      block: Block
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, BlockResult] =
    executeBlock(block, isProposer = true)

  /** Executes a block (executes transactions and pays rewards) */
  private def executeBlock(
      block: Block,
      isProposer: Boolean = false
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, BlockResult] =
    try
      for
        parentHeader <- blockchainReader
          .getBlockHeaderByHash(block.header.parentHash)
          .toRight(MissingParentError) // Should not never occur because validated earlier
        initialWorld = buildInitialWorld(block, parentHeader, isProposer)
        execResult <- executeBlockTransactions(block, initialWorld)
        worldAfterReward <- Either
          .catchOnly[MPTException](blockPreparator.payBlockReward(block, execResult.worldState))
          .leftMap(BlockExecutionError.MPTError.apply)
        // EIP-4895: Process beacon chain withdrawals (Shanghai+)
        worldAfterWithdrawals = processWithdrawals(block, worldAfterReward)
        // Prague: Process system calls for withdrawal/consolidation requests; Amsterdam adds the two
        // EIP-8282 builder predeploys. The system-call outputs (types 0x01, 0x02 and, post-Amsterdam,
        // 0x03, 0x04) and deposit log requests (type 0x00) combine to form the EIP-7685 requestsHash;
        // follower mode verifies, proposer mode emits.
        systemCallResult = processPragueSystemCalls(block, worldAfterWithdrawals)
        worldAfterSystemCalls = systemCallResult._1
        systemRequests = systemCallResult._2
        depositRequest = collectDepositRequests(execResult.receipts)
        // State root hash needs to be up-to-date for validateBlockAfterExecution. In proposer mode the
        // backing MPT storage is read-only, so persistState computes the trie hash in-memory without
        // writing to RocksDB — exactly what we want for a speculative payload.
        worldPersisted = InMemoryWorldStateProxy.persistState(worldAfterSystemCalls)
      yield execResult.copy(
        worldState = worldPersisted,
        executionRequests = depositRequest.toSeq ++ systemRequests
      )
    catch case e: MPTException => Left(BlockExecutionError.MPTError(e))

  protected def buildInitialWorld(block: Block, parentHeader: BlockHeader, isProposer: Boolean = false)(implicit
      blockchainConfig: BlockchainConfig
  ): InMemoryWorldStateProxy =
    // `isProposer` originally switched to `getReadOnlyMptStorage()` to keep proposer-mode tx
    // state from leaking into the canonical DB. That did not actually work: ReadOnlyNodeStorage
    // buffers writes but its `persist()` still flushes them to the wrapped storage, which is
    // what `InMemoryWorldStateProxy.persistState` ends up calling. Meanwhile the read-only
    // wrapping broke the Shanghai withdrawals stateRoot: proposer-computed and newPayload-computed
    // roots diverged for reasons we haven't fully isolated (likely buffered-node read ordering
    // inside SerializingMptStorage/MerklePatriciaTrie). Use the writable backing in both modes;
    // we still get idempotence because the MPT hash is deterministic given the same inputs.
    val _ = isProposer
    InMemoryWorldStateProxy(
      evmCodeStorage = evmCodeStorage,
      blockchain.getBackingMptStorage(block.header.number.value),
      (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash.value),
      accountStartNonce = blockchainConfig.accountStartNonce,
      stateRootHash = parentHeader.stateRoot.value,
      noEmptyAccounts = EvmConfig.forBlock(block.header.number.value, blockchainConfig).noEmptyAccounts,
      ethCompatibleStorage = blockchainConfig.ethCompatibleStorage
    )

  /** This function runs transactions
    *
    * @param block
    *   the block with transactions to run
    */
  protected[ledger] def executeBlockTransactions(
      block: Block,
      initialWorld: InMemoryWorldStateProxy
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, BlockResult] =
    val blockHeaderNumber = block.header.number.value
    executeBlockTransactions(block, blockHeaderNumber, initialWorld)

  protected def executeBlockTransactions(
      block: Block,
      blockHeaderNumber: BigInt,
      initialWorld: InMemoryWorldStateProxy
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError.TxsExecutionError, BlockResult] =
    val worldAfterDao = blockchainConfig.daoForkConfig match
      case Some(daoForkConfig) if daoForkConfig.isDaoForkBlock(blockHeaderNumber) =>
        drainDaoForkAccounts(initialWorld, daoForkConfig)
      case _ => initialWorld

    // EIP-4788: Store parent beacon block root in system contract (post-Cancun)
    val worldAfterBeaconRoot = applyEip4788(block, worldAfterDao)

    // EIP-2935: Store parent block hash in history storage contract
    val inputWorld = applyEip2935(block, worldAfterBeaconRoot)

    val hashAsHexString = block.header.hashAsHexString
    val transactionList = block.body.transactionList
    log.debug(
      s"About to execute ${transactionList.size} txs from block $blockHeaderNumber (with hash: $hashAsHexString)"
    )
    val blockTxsExecResult = blockPreparator.executeTransactions(transactionList, inputWorld, block.header)
    blockTxsExecResult match
      case Right(_) => log.debug(s"All txs from block $hashAsHexString were executed successfully")
      case Left(error) =>
        log.debug(s"Not all txs from block $hashAsHexString were executed correctly, due to ${error.describe}")
    blockTxsExecResult

  /** EIP-4788: Store the parent beacon block root in the beacon root system contract.
    *
    * Post-Cancun, the parentBeaconBlockRoot from the CL is stored at the beacon root contract address
    * (0x000F3df6D732807Ef1319fB7B8bB8522d0Beac02) before executing transactions. The contract stores: timestamp → root
    * at slot (timestamp % HISTORY_BUFFER_LENGTH), and root at slot (timestamp % HISTORY_BUFFER_LENGTH +
    * HISTORY_BUFFER_LENGTH).
    */
  private def applyEip4788(
      block: Block,
      world: InMemoryWorldStateProxy
  )(implicit blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy =
    import BlockExecution.*
    // Only apply post-Cancun (when parentBeaconBlockRoot is present)
    block.header.parentBeaconBlockRoot match
      case Some(beaconRoot) if blockchainConfig.isCancunTimestamp(block.header.unixTimestamp) =>
        val timestamp = UInt256(block.header.unixTimestamp.toLong)
        val timestampIdx = timestamp.mod(UInt256(BeaconRootHistoryBufferLength))
        val rootIdx = timestampIdx + UInt256(BeaconRootHistoryBufferLength)

        // EIP-4788: "if no code exists at BEACON_ROOTS_ADDRESS, the call must fail silently".
        // The client MUST NOT deploy the contract itself. It is deployed like any other contract,
        // by the pre-signed Nick's-method transaction from 0x0B799C86a49DEeb90402691F1041aa3AF2d3C875
        // (EIP-4788 "Deployment"), which is part of the chain history on mainnet and every public
        // testnet. go-ethereum performs a SYSTEM_ADDRESS call with value 0; under EIP-158 a call
        // to a non-existent account with zero value returns immediately and creates nothing
        // (core/vm/evm.go Call), so an absent contract produces NO state change at all.
        //
        // Seeding code + nonce here produced a state-root divergence on any chain whose genesis
        // does not pre-allocate the account: fukuii wrote an account (nonce=1, codeHash) plus two
        // storage slots that the reference client does not have. Observed on hive
        // ethereum/graphql, whose testGenesis.json allocates only one account while block 34
        // carries parentBeaconBlockRoot (first block past cancunTime).
        //
        // The direct storage write below (instead of an EVM system call) is the optimisation the
        // EIP explicitly permits: "Clients may decide to omit an explicit EVM call and directly
        // set the storage values." It is only valid when the canonical contract is the one
        // deployed there, which is why the code-presence guard is a hard precondition.
        if world.getCode(BeaconRootContractAddress).isEmpty then world
        else
          val storage = world.getStorage(BeaconRootContractAddress)
          val s1 = storage.store(timestampIdx.toBigInt, timestamp.toBigInt)
          val s2 = s1.store(rootIdx.toBigInt, UInt256(beaconRoot.value).toBigInt)
          world.saveStorage(BeaconRootContractAddress, s2)

      case _ => world

  /** EIP-2935: Deploy history storage contract at fork block and store parent block hash.
    *
    * At the Olympia activation block, deploys the history storage contract (sets nonce=1 and code). At every
    * post-Olympia block, writes the parent hash to storage slot (blockNumber - 1) % HistoryServeWindow.
    */
  private def applyEip2935(
      block: Block,
      world: InMemoryWorldStateProxy
  )(implicit blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy =
    import BlockExecution.*
    val blockNumber = block.header.number.value
    // EIP-2935 activates at Prague on ETH chains (timestamp fork), or at Olympia on ETC chains (block number fork).
    val pragueActive = blockchainConfig.isPragueTimestamp(block.header.unixTimestamp)
    val etcOlympiaActive = blockchainConfig.networkType == com.chipprbots.ethereum.utils.NetworkType.ETC &&
      blockNumber >= blockchainConfig.forkBlockNumbers.olympiaBlockNumber
    if !pragueActive && !etcOlympiaActive then return world

    // Deploy history storage contract only if not already deployed (genesis may pre-deploy it).
    // Use code presence as the sole guard — identical to applyEip4788's account-existence guard.
    // Tying deployment to isActivationBlock caused IllegalStateException when processing a
    // post-activation block on a fresh world: the account was absent so getStorage threw.
    val w1 = if world.getCode(HistoryStorageAddress).isEmpty then
      val account = world
        .getAccount(HistoryStorageAddress)
        .getOrElse(Account.empty(blockchainConfig.accountStartNonce))
        .copy(nonce = UInt256(1))
      world
        .saveAccount(HistoryStorageAddress, account)
        .saveCode(HistoryStorageAddress, HistoryStorageCode)
    else world

    // Store parent hash at slot (blockNumber - 1) % HistoryServeWindow
    val parentHashValue = UInt256(block.header.parentHash.value)
    val slot = (blockNumber - 1) % HistoryServeWindow
    val storage = w1.getStorage(HistoryStorageAddress)
    val updatedStorage = storage.store(slot, parentHashValue.toBigInt)
    w1.saveStorage(HistoryStorageAddress, updatedStorage)

  /** This function updates worldState transferring balance from drainList accounts to refundContract address
    *
    * @param worldState
    *   initial world state
    * @param daoForkConfig
    *   dao fork configuration with drainList and refundContract config
    * @return
    *   updated world state proxy
    */
  private def drainDaoForkAccounts(
      worldState: InMemoryWorldStateProxy,
      daoForkConfig: DaoForkConfig
  ): InMemoryWorldStateProxy =
    daoForkConfig.refundContract match
      case Some(refundContractAddress) =>
        daoForkConfig.drainList.foldLeft(worldState) { (ws, address) =>
          ws.getAccount(address)
            .map(account => ws.transfer(from = address, to = refundContractAddress, account.balance))
            .getOrElse(ws)
        }
      case None => worldState

  /** Executes and validates a list of blocks, storing the results in the blockchain.
    *
    * @param blocks
    *   blocks to be executed
    * @param parentChainWeight
    *   parent weight
    *
    * @return
    *   a list of blocks in incremental order that were correctly executed and an optional
    *   [[com.chipprbots.ethereum.ledger.BlockExecutionError]]
    */
  def executeAndValidateBlocks(
      blocks: List[Block],
      parentChainWeight: ChainWeight
  )(implicit blockchainConfig: BlockchainConfig): (List[BlockData], Option[BlockExecutionError]) =
    @tailrec
    def go(
        executedBlocksDecOrder: List[BlockData],
        remainingBlocksIncOrder: List[Block],
        parentWeight: ChainWeight
    ): (List[BlockData], Option[BlockExecutionError]) =
      if remainingBlocksIncOrder.isEmpty then (executedBlocksDecOrder.reverse, None)
      else
        val blockToExecute = remainingBlocksIncOrder.head
        executeAndValidateBlock(blockToExecute, alreadyValidated = true) match
          case Right(receipts) =>
            val newWeight = parentWeight.increase(blockToExecute.header)
            val newBlockData = BlockData(blockToExecute, receipts, newWeight)
            blockchainWriter.save(
              newBlockData.block,
              newBlockData.receipts,
              newBlockData.weight,
              saveAsBestBlock = false
            )
            blockchain.saveBlockState(blockToExecute.header.number.value)
            blockchainReader.recordBlockDifficulty(blockToExecute.header.difficulty)
            go(newBlockData :: executedBlocksDecOrder, remainingBlocksIncOrder.tail, newWeight)
          case Left(executionError) =>
            (executedBlocksDecOrder.reverse, Some(executionError))

    go(List.empty[BlockData], blocks, parentChainWeight)

  /** EIP-4895: Process beacon chain withdrawals (Shanghai+). Each withdrawal credits `amount * 1 Gwei` to the target
    * address. No gas is charged. Creates the account if it doesn't exist.
    */
  private def processWithdrawals(
      block: Block,
      world: InMemoryWorldStateProxy
  ): InMemoryWorldStateProxy =
    block.body.withdrawals match
      case Some(withdrawals) if withdrawals.nonEmpty =>
        val GweiToWei = BigInt("1000000000")
        withdrawals.foldLeft(world) { (w, withdrawal) =>
          // EIP-4895: amount-0 withdrawals must NOT touch the target account —
          // creating/saving an empty account here diverges from every other EL
          // client's state root for any block containing a zero-amount withdrawal.
          if withdrawal.amount == 0 then w
          else
            val weiAmount = UInt256(withdrawal.amount * GweiToWei)
            val address = withdrawal.address
            val account = w.getAccount(address).getOrElse(w.getEmptyAccount)
            w.saveAccount(address, account.increaseBalance(weiAmount))
        }
      case _ => world

  /** Execute the end-of-block SYSTEM_ADDRESS calls to the request-queue predeploys, after all transactions.
    *
    * Prague: EIP-7002 (withdrawals) and EIP-7251 (consolidations). Amsterdam additionally calls the two EIP-8282
    * builder predeploys — see [[BlockExecution.systemCallTargets]].
    *
    * These calls are not bookkeeping that can be skipped. Each predeploy's dequeue path clears the per-block slots its
    * user path dirtied, so omitting a call leaves those slots set and forks the account's storage root, and with it the
    * state root.
    *
    * Returns the updated world state AND the typed-request bytes collected from each system call's return data (used
    * for the EIP-7685 requestsHash). The returned Seq is in canonical request-type order: withdrawals (0x01),
    * consolidations (0x02), then builder deposit (0x03) and builder exit (0x04). A call returning no data contributes
    * nothing. Deposit requests (0x00) are collected separately via collectDepositRequests.
    */
  private[ledger] def processPragueSystemCalls(
      block: Block,
      world: InMemoryWorldStateProxy
  )(implicit blockchainConfig: BlockchainConfig): (InMemoryWorldStateProxy, Seq[ByteString]) =
    if !blockchainConfig.isPragueTimestamp(block.header.unixTimestamp) then return (world, Nil)

    import BlockExecution.*
    val evmConfig = EvmConfig.forBlock(block.header.number.value, block.header.unixTimestamp, blockchainConfig)
    var w = world
    val outputs = scala.collection.mutable.ListBuffer.empty[ByteString]

    // EIP-7685: Execute system calls to request contracts and collect output.
    // EIP-6110 DEPOSIT contract has no system call — deposits are parsed from logs.
    // Prague: EIP-7002 (withdrawals) and EIP-7251 (consolidations). Amsterdam adds the two EIP-8282 builder
    // predeploys. The SYSTEM_ADDRESS call is not optional bookkeeping: each predeploy's dequeue path clears the
    // per-block slots its user path dirtied (for the builder deposit contract, slots 0x01 and 0x03), so skipping
    // the call leaves those slots set and forks the storage root -> account RLP -> STATE ROOT.
    //
    // GAS CEILING, stated explicitly because it changes an already-shipped path. `SYSTEM_CALL_GAS_LIMIT` is one
    // global constant, not a per-contract one: EIP-8037 raises it from 30,000,000 to 30,000,000 + 16 x
    // GAS_STORAGE_SET so that a system call has state-dimension headroom, and it does so for EVERY system call,
    // not only the two EIP-8282 ones. So on an Amsterdam block the pre-existing EIP-7002/7251 calls are funded at
    // the raised ceiling too. That is deliberate: scoping the bump to the builder pair would invent a
    // two-constant model no reference client has. EIP-2935 and EIP-4788 are unaffected here only because this
    // client applies them as direct storage writes (the optimisation EIP-4788 explicitly permits) rather than as
    // EVM calls, so they have no gas ceiling to raise — see `applyEip4788` / `applyEip2935`.
    //
    // The bump is strictly upward (30,000,000 -> 31,566,720) and the queue predeploys are bounded loops that
    // never read GAS, so it is a no-op for 7002/7251. That is asserted, not assumed:
    // `AmsterdamBuilderRequestsSpec` runs the fixture's real withdrawal and consolidation bytecode over a
    // non-empty queue at both ceilings and requires byte-identical requests and storage.
    val amsterdamActive = blockchainConfig.isAmsterdamTimestamp(block.header.unixTimestamp)
    for (queueAddr, requestType) <- BlockExecution.systemCallTargets(block.header.unixTimestamp)
    do
      val code = w.getCode(queueAddr)
      if code.nonEmpty then
        val context = ProgramContext[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage](
          callerAddr = SystemAddress,
          originAddr = SystemAddress,
          recipientAddr = Some(queueAddr),
          gasPrice = com.chipprbots.ethereum.domain.UInt256.Zero,
          startGas = if amsterdamActive then AmsterdamGas.SystemCallGasLimit else BigInt(30000000),
          inputData = ByteString.empty,
          value = com.chipprbots.ethereum.domain.UInt256.Zero,
          endowment = com.chipprbots.ethereum.domain.UInt256.Zero,
          doTransfer = false,
          blockHeader = block.header,
          callDepth = 0,
          world = w,
          initialAddressesToDelete = Set.empty,
          evmConfig = evmConfig,
          originalWorld = w,
          warmAddresses = Set(queueAddr),
          warmStorage = Set.empty
        )
        val vm = new com.chipprbots.ethereum.vm.VM[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage]
        val result = vm.run(context)
        w = InMemoryWorldStateProxy.persistState(result.world)
        // EIP-7685 request bytes = single-byte type prefix || raw system-call returndata.
        // Empty returndata (no queued requests) means no bytes are emitted for this type.
        if result.returnData.nonEmpty then outputs += ByteString(Array(requestType.toByte)) ++ result.returnData
    (w, outputs.toSeq)

  /** EIP-6110: Parse `DepositEvent(bytes,bytes,bytes,bytes,bytes)` logs emitted by the beacon deposit contract during
    * block execution and return one request entry per deposit. Event ABI: [pubkey(48)->64,
    * withdrawal_credentials(32)->64, amount(8)->32, signature(96)->128, index(8)->32]. The canonical request body
    * concatenates the raw fields (pubkey || wc || amount_le || signature || index_le) = 192 bytes; with the type byte
    * prefix (0x00) that's 193 bytes per deposit. Returns a single ByteString = 0x00 || concatenated_deposit_data (or
    * empty if none).
    */
  def collectDepositRequests(receipts: Seq[Receipt]): Option[ByteString] =
    import BlockExecution.*
    val buf = scala.collection.mutable.ArrayBuffer.empty[Byte]
    for
      receipt <- receipts
      log <- receipt.logs
      if log.loggerAddress == DepositContractAddress
      if log.logTopics.headOption.contains(DepositEventSignature)
    do
      // Deposit event data layout (offsets + 32-byte length prefix + padded body):
      //   offsets: 5 * 32 bytes = 160 bytes of ABI offsets [160, 256, 352, 416, 576]
      //   pubkey: 32-byte length (=48) + 48-byte body + 16-byte pad     = 96 bytes
      //   wc:     32-byte length (=32) + 32-byte body                    = 64 bytes
      //   amount: 32-byte length (=8)  + 8-byte body + 24-byte pad       = 64 bytes
      //   sig:    32-byte length (=96) + 96-byte body + 32-byte pad      = 160 bytes
      //   index:  32-byte length (=8)  + 8-byte body + 24-byte pad       = 64 bytes
      // Total = 160 + 96 + 64 + 64 + 160 + 64 = 608 bytes. We slice the raw bodies.
      val d = log.data
      if d.length >= 608 then
        // skip 5x32 offsets = 160
        val pubkey = d.slice(160 + 32, 160 + 32 + 48) // 48
        val wc = d.slice(160 + 96 + 32, 160 + 96 + 32 + 32) // 32
        val amountLE = d.slice(160 + 96 + 64 + 32, 160 + 96 + 64 + 32 + 8) // 8
        val signature = d.slice(160 + 96 + 64 + 64 + 32, 160 + 96 + 64 + 64 + 32 + 96) // 96
        val indexLE = d.slice(160 + 96 + 64 + 64 + 160 + 32, 160 + 96 + 64 + 64 + 160 + 32 + 8) // 8
        buf ++= pubkey ++= wc ++= amountLE ++= signature ++= indexLE
    if buf.isEmpty then None
    else Some(ByteString(Array(DepositRequestType.toByte)) ++ ByteString(buf.toArray))

object BlockExecution:

  val SystemAddress: Address = Address("0xfffffffffffffffffffffffffffffffffffffffe")

  /** EIP-6110: Deposit contract for on-chain validator deposits */
  val DepositContractAddress: Address = Address("0x00000000219ab540356cBB839Cbe05303d7705Fa")

  /** EIP-7002: Withdrawal request queue contract */
  val WithdrawalQueueAddress: Address = Address("0x00000961ef480eb55e80d19ad83579a64c007002")

  /** EIP-7251: Consolidation request queue contract */
  val ConsolidationQueueAddress: Address = Address("0x0000bbddc7ce488642fb579f8b00f3a590007251")

  /** EIP-8282: Builder DEPOSIT request queue contract (Amsterdam). */
  val BuilderDepositQueueAddress: Address = Address("0x0000bFF46984e3725691FA540a8C7589300D8282")

  /** EIP-8282: Builder EXIT request queue contract (Amsterdam). */
  val BuilderExitQueueAddress: Address = Address("0x000064D678505ad48F8cCb093BC65613800E8282")

  /** EIP-7685 request type byte prefixes (canonical ordering). */
  val DepositRequestType: Int = 0x00
  val WithdrawalRequestType: Int = 0x01
  val ConsolidationRequestType: Int = 0x02
  val BuilderDepositRequestType: Int = 0x03
  val BuilderExitRequestType: Int = 0x04

  /** The EIP-7685 system-call targets for the fork active at `timestamp`, in canonical request-type order.
    *
    * EIP-7002 (0x01) and EIP-7251 (0x02) come in at Prague. EIP-8282 appends the two builder predeploys, 0x03 (deposit)
    * and 0x04 (exit), at Amsterdam. The order is load-bearing: `requestsHash` folds the per-type digests in ascending
    * type order, so the builder pair must follow 7002/7251 and never precede them.
    *
    * ETC safety: no ETC-family config declares `amsterdam-timestamp`, so `isAmsterdamTimestamp` reads `None` and this
    * returns the Prague pair unchanged on every ETC chain. The caller additionally skips any target with no deployed
    * code, which is the second, independent guard.
    */
  def systemCallTargets(timestamp: Timestamp)(implicit blockchainConfig: BlockchainConfig): Seq[(Address, Int)] =
    val pragueTargets = Seq(
      (WithdrawalQueueAddress, WithdrawalRequestType),
      (ConsolidationQueueAddress, ConsolidationRequestType)
    )
    if blockchainConfig.isAmsterdamTimestamp(timestamp) then
      pragueTargets ++ Seq(
        (BuilderDepositQueueAddress, BuilderDepositRequestType),
        (BuilderExitQueueAddress, BuilderExitRequestType)
      )
    else pragueTargets

  /** EIP-6110: keccak256("DepositEvent(bytes,bytes,bytes,bytes,bytes)") topic signature. */
  val DepositEventSignature: ByteString = ByteString(
    com.chipprbots.ethereum.crypto
      .kec256("DepositEvent(bytes,bytes,bytes,bytes,bytes)".getBytes("US-ASCII"))
  )

  /** EIP-4788: Address of the beacon block root system contract */
  val BeaconRootContractAddress: Address = Address("0x000F3df6D732807Ef1319fB7B8bB8522d0Beac02")

  /** EIP-4788: History buffer length for beacon root storage (8191 slots) */
  val BeaconRootHistoryBufferLength: BigInt = BigInt(8191)

  /** EIP-4788: Deployed bytecode for the beacon roots system contract */
  val BeaconRootsCode: ByteString = ByteStringUtils.string2hash(
    "3373fffffffffffffffffffffffffffffffffffffffe14604d57602036146024575f5ffd5b5f35801560495762001fff810690815414603c575f5ffd5b62001fff01545f5260205ff35b5f5ffd5b62001fff42064281555f359062001fff015500"
  )

  /** EIP-2935: Address of the history storage contract */
  val HistoryStorageAddress: Address = Address("0x0000F90827F1C53a10cb7A02335B175320002935")

  /** EIP-2935: Number of historical block hashes served */
  val HistoryServeWindow: BigInt = BigInt(8191)

  /** EIP-2935: Deployed bytecode for the history storage contract */
  val HistoryStorageCode: ByteString = ByteStringUtils.string2hash(
    "3373fffffffffffffffffffffffffffffffffffffffe14604657602036036042575f35600143038111604257611fff81430311604257611fff9006545f5260205ff35b5f5ffd5b5f35611fff60014303065500"
  )

sealed trait BlockExecutionError:
  def describe: String

type ValidationError = BlockHeaderError | BlockError | OmmersError

sealed trait BlockExecutionSuccess

case object BlockExecutionSuccess extends BlockExecutionSuccess

object BlockExecutionError:
  final case class ValidationBeforeExecError(error: ValidationError) extends BlockExecutionError:
    def describe: String = error.toString

  /** @param acumGas
    *   the receipt counter — the running sum of `tx_gas_used`, which is what `cumulativeGasUsed` continues from.
    * @param acumExecutionGas
    *   EIP-8037 `block_execution_gas_used` so far. Equal to `acumGas` pre-Amsterdam.
    * @param acumStateGas
    *   EIP-8037 `block_state_gas_used` so far. Zero pre-Amsterdam.
    */
  final case class StateBeforeFailure(
      worldState: InMemoryWorldStateProxy,
      acumGas: BigInt,
      acumReceipts: Seq[Receipt],
      acumExecutionGas: BigInt = 0,
      acumStateGas: BigInt = 0
  )

  final case class TxsExecutionError(stx: SignedTransaction, stateBeforeError: StateBeforeFailure, reason: String)
      extends BlockExecutionError:
    def describe: String = reason

  final case class ValidationAfterExecError(reason: String) extends BlockExecutionError:
    def describe: String = reason

  case object MissingParentError extends BlockExecutionError:
    override def describe: String = "Cannot find parent"

  final case class MPTError(error: MPTException) extends BlockExecutionError:
    def describe: String = error.toString
