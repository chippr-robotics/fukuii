package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import cats.implicits.*

import scala.annotation.tailrec

import com.chipprbots.ethereum.consensus.pow.validators.OmmersValidator.OmmersError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.consensus.validators.std.StdBlockValidator.BlockError
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.SerializingMptStorage
import com.chipprbots.ethereum.db.storage.StagedBlockState
import com.chipprbots.ethereum.db.storage.pathstate.StateFault
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.BlockExecutionError.MissingParentError
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MPTException
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.utils.DaoForkConfig
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.utils.StateReadCacheConfig
import com.chipprbots.ethereum.vm.AmsterdamGas
import com.chipprbots.ethereum.vm.BlockAccessRecorder
import com.chipprbots.ethereum.vm.EvmConfig
import com.chipprbots.ethereum.vm.ImportProfile
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
    * system-call outputs), and the EIP-7928 block access list it produced (`Some` on an Amsterdam block). Used by the
    * Engine API to additionally verify the header's requestsHash matches what execution actually produced, and to keep
    * the list of a block it accepts.
    */
  def executeAndValidateBlockFull(
      block: Block,
      alreadyValidated: Boolean = false,
      suppliedBlockAccessList: Option[BlockAccessList] = None
  )(implicit
      blockchainConfig: BlockchainConfig
  ): Either[BlockExecutionError, (Seq[Receipt], Seq[ByteString], Option[BlockAccessList])] =
    val hash = block.header.hash.value
    // One execution + commit per block hash at a time, across the Engine API and the regular-sync importer.
    ChainInsertLock.shared(BlockImportGate.withBlock(hash) {
      val staged = blockchain.stageBlockStateApart(block.header)
      // This path commits a block's state apart from the block: the block is stored, and the head moved, by the caller
      // afterwards. On the unified layout the state is kept as a forward layer beside the state the disk holds, and
      // only a fork choice moves the head to it (spec 018 C4d part 2a).
      BlockImportGate.alreadyExecuted(hash, staged) match
        case Some(done) => Right(done.asTuple)
        case None =>
          val result =
            try
              // The 3-argument form stays the single seam subclasses (and tests) override; a block with a list goes
              // through the one that carries it.
              if suppliedBlockAccessList.isEmpty then executeAndValidateStaged(block, alreadyValidated, staged)
              else executeAndValidateStagedWithList(block, alreadyValidated, staged, suppliedBlockAccessList)
            catch
              case t: Throwable =>
                staged.discard()
                throw t
          result match
            // Accepted: its state goes to the database (nobody else commits it here). Rejected: it leaves nothing
            // behind.
            case Right(executed) =>
              timedCommit(block)(staged.commitApart(block.header.number.value, hash))
              BlockImportGate.record(hash, executed)
            case Left(_) => staged.discard()
          result
    })

  /** On the unified state layout the importer commits a block in place or not at all: in the batch that makes it the
    * best block (spec 018 data-model 7.1, and the scope of C3b). A block it executes without adopting it (a side
    * branch, the blocks of a reorganisation) is refused here, before it runs, as a condition of this node's own state:
    * never a verdict on the block (C4e routes them). A payload ahead of the head is kept as a forward layer instead
    * (`executeAndValidateBlockFull`, C4d part 2a).
    *
    * @throws StateUnavailable
    *   for such a block
    */
  private def refuseUnadopted(block: Block, staged: StagedBlockState, what: String): Unit =
    if staged.commitsInPlace then
      throw new com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable(
        s"block ${block.header.number.value} is $what, which the unified state layout cannot hold before its overlay"
      )

  /** Times the block's database commit (one atomic batch since #1465), which `[IMPORT-TIMING]` ends before. */
  private def timedCommit[A](block: Block)(body: => A): A =
    val t = System.nanoTime()
    try body
    finally
      val ms = (System.nanoTime() - t) / 1e6
      val line = f"[IMPORT-COMMIT] block=${block.header.number.value} commit=$ms%.1fms"
      if StateReadCacheConfig.importTimingLog then log.info(line) else log.debug(line)

  /** Executes and validates `block` against `staged`; the caller commits or discards what it staged. */
  protected[ledger] def executeAndValidateStaged(
      block: Block,
      alreadyValidated: Boolean,
      staged: StagedBlockState
  )(implicit
      blockchainConfig: BlockchainConfig
  ): Either[BlockExecutionError, (Seq[Receipt], Seq[ByteString], Option[BlockAccessList])] =
    executeAndValidateStagedWithList(block, alreadyValidated, staged, None)

  /** [[executeAndValidateStaged]] for a block that arrived with its EIP-7928 list (a prefetch hint, never trusted). */
  protected[ledger] def executeAndValidateStagedWithList(
      block: Block,
      alreadyValidated: Boolean,
      staged: StagedBlockState,
      suppliedBlockAccessList: Option[BlockAccessList]
  )(implicit
      blockchainConfig: BlockchainConfig
  ): Either[BlockExecutionError, (Seq[Receipt], Seq[ByteString], Option[BlockAccessList])] =
    val preExecValidationResult =
      if alreadyValidated then Right(block) else blockValidation.validateBlockBeforeExecution(block)

    val blockExecResult =
      for
        _ <- preExecValidationResult
        result <- executeBlock(block, staged = Some(staged), suppliedBlockAccessList = suppliedBlockAccessList)
        _ <- blockValidation.validateBlockAfterExecution(
          block,
          result.worldState.stateRootHash,
          result.receipts,
          result.gasUsed
        )
        _ <- validateRequestsHash(block, result.executionRequests)
        _ <- validateBlockAccessList(block, result.blockAccessList)
      yield (result.receipts, result.executionRequests, result.blockAccessList)

    if blockExecResult.isRight then
      log.debug(s"Block ${block.header.number} (with hash: ${block.header.hashAsHexString}) executed correctly")

    blockExecResult

  /** Executes a block without pre/post validation. Returns the execution result including receipts, gasUsed, and the
    * persisted world state. Used by ChainImporter for trusted block import where only state correctness matters.
    */
  def executeBlockNoValidation(
      block: Block
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, (Seq[Receipt], BigInt, ByteString)] =
    executeBlockNoValidationWithRequests(block).map { case (receipts, gasUsed, root, _, _) =>
      (receipts, gasUsed, root)
    }

  /** [[executeBlockNoValidation]] plus the EIP-7685 requests and the EIP-7928 block access list execution produced, so
    * an import path that validates after the fact (ChainImporter) can check the header's `requestsHash` with
    * [[validateRequestsHash]] and its `blockAccessListHash` with [[validateBlockAccessList]].
    */
  def executeBlockNoValidationWithRequests(
      block: Block
  )(implicit
      blockchainConfig: BlockchainConfig
  ): Either[BlockExecutionError, (Seq[Receipt], BigInt, ByteString, Seq[ByteString], Option[BlockAccessList])] =
    executeBlock(block).map { result =>
      (
        result.receipts,
        result.gasUsed,
        result.worldState.stateRootHash,
        result.executionRequests,
        result.blockAccessList
      )
    }

  /** EIP-7685: a Prague+ header's `requestsHash` must commit to exactly the requests execution produced. Without this
    * check a block could carry any `requestsHash` and still be imported over RLP / devp2p (the Engine API path compares
    * the CL-supplied request list instead, which is equivalent there). EEST `test_consolidation_requests_negative`,
    * `test_invalid_multi_type_requests`, `test_withdrawal_requests_negative`.
    */
  def validateRequestsHash(block: Block, requests: Seq[ByteString])(implicit
      blockchainConfig: BlockchainConfig
  ): Either[BlockExecutionError, Unit] =
    if !blockchainConfig.isPragueTimestamp(block.header.unixTimestamp) then Right(())
    else
      val expected = BlockExecution.computeRequestsHash(requests)
      block.header.requestsHash match
        case Some(h) if h == expected => Right(())
        case other =>
          Left(
            BlockExecutionError.ValidationAfterExecError(
              s"INVALID_REQUESTS: header requestsHash ${other.map(ByteStringUtils.hash2string)} != " +
                s"${ByteStringUtils.hash2string(expected)} computed from ${requests.size} executed request(s)"
            )
          )

  /** EIP-7928: an Amsterdam block's access list — the one its execution produced — must fit the block's gas limit
    * (`BLOCK_ACCESS_LIST_GAS_LIMIT_EXCEEDED`) and be the list the header's `blockAccessListHash` commits to
    * (`INVALID_BLOCK_ACCESS_LIST`): execution-specs `apply_body` / `execute_block`, go-ethereum `ValidateState`.
    * Without it an Amsterdam block would be accepted whatever list it declares, over RLP / devp2p as through the Engine
    * API.
    *
    * Checking the hash covers the list's content completely: the header binds `keccak256(rlp(list))`, and a CL-supplied
    * list's bytes hash to the same value (`payloadToBlock`), so any difference in an account, slot, value or index, and
    * any entry out of order, changes the hash. `None` on an Amsterdam block would be an execution defect, and is
    * rejected rather than trusted. Every earlier fork and every ETC chain passes unchecked.
    */
  def validateBlockAccessList(block: Block, computed: Option[BlockAccessList])(implicit
      blockchainConfig: BlockchainConfig
  ): Either[BlockExecutionError, Unit] =
    if !blockchainConfig.isAmsterdamTimestamp(block.header.unixTimestamp) then Right(())
    else
      computed match
        case None =>
          Left(
            BlockExecutionError.ValidationAfterExecError(
              "INVALID_BLOCK_ACCESS_LIST: execution produced no block access list for an Amsterdam block"
            )
          )
        case Some(accessList) =>
          accessList
            .validateSize(block.header.gasLimit.value)
            .left
            .map(err => BlockExecutionError.ValidationAfterExecError(s"BLOCK_ACCESS_LIST_GAS_LIMIT_EXCEEDED: $err"))
            .flatMap { _ =>
              val computedHash = accessList.hash
              if block.header.blockAccessListHash.contains(computedHash) then Right(())
              else
                Left(
                  BlockExecutionError.ValidationAfterExecError(
                    s"INVALID_BLOCK_ACCESS_LIST: header blockAccessListHash " +
                      s"${block.header.blockAccessListHash.map(ByteStringUtils.hash2string)} != " +
                      s"${ByteStringUtils.hash2string(computedHash)} computed from ${accessList.accounts.size} " +
                      s"account(s) accessed in execution"
                  )
                )
            }

  /** Proposer-mode execution. Runs all Prague preambles (EIP-4788, EIP-2935), transactions, withdrawals, and system
    * calls (EIP-7002/7251), collects deposit requests (EIP-6110), and returns the full BlockResult with receipts +
    * executionRequests populated. No pre- or post-execution validation against the header is performed — caller is
    * responsible for filling in header fields (stateRoot, receiptsRoot, gasUsed, requestsHash, etc.) from the result.
    *
    * Where what it persists goes depends on the state layout; see [[buildInitialWorld]]. On the hash store the trie
    * nodes reach the store, by their hashes, where the import of the same block finds them already written. On the
    * unified layout they go to a layer of this execution's own and are dropped with it: nothing is written but contract
    * code, which is kept by its hash on both. Either way no block, receipt or head is written here, and a block that
    * was built is executed again when it is imported.
    */
  def executeForProposer(
      block: Block
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, BlockResult] =
    executeBlock(block, isProposer = true)

  /** Executes a block (executes transactions and pays rewards), logging where its time went at INFO. */
  private def executeBlock(
      block: Block,
      isProposer: Boolean = false,
      staged: Option[StagedBlockState] = None,
      suppliedBlockAccessList: Option[BlockAccessList] = None
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, BlockResult] =
    val timing = !isProposer && ImportProfile.begin()
    var snapshot: Option[ImportProfile.Snapshot] = None
    val result =
      try executeBlockUntimed(block, isProposer, staged, suppliedBlockAccessList)
      finally if timing then snapshot = Some(ImportProfile.end())
    snapshot.foreach { s =>
      val gas = result.toOption.fold(BigInt(0))(_.gasUsed)
      val line = ImportProfile.format(block.header.number.value, gas, block.body.transactionList.size, s)
      if StateReadCacheConfig.importTimingLog then log.info(line) else log.debug(line)
    }
    result

  private def executeBlockUntimed(
      block: Block,
      isProposer: Boolean,
      staged: Option[StagedBlockState],
      suppliedBlockAccessList: Option[BlockAccessList]
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, BlockResult] =
    // EIP-7928: an Amsterdam block builds its access list as it executes, one block access index at a time — 0 for the
    // preamble system calls, i + 1 per transaction, n + 1 for the withdrawals and the request system calls below.
    val accessList =
      Option.when(blockchainConfig.isAmsterdamTimestamp(block.header.unixTimestamp))(new BlockAccessListBuilder)
    val postExecutionReads = accessList.map(_ => new BlockAccessRecorder)
    var prefetch: Option[BalPrefetcher.Run] = None
    try
      for
        parentHeader <- blockchainReader
          .getBlockHeaderByHash(block.header.parentHash)
          .toRight(MissingParentError) // Should not never occur because validated earlier
        initialWorld = buildInitialWorld(block, parentHeader, isProposer, staged)
        _ = prefetch = startPrefetch(block, parentHeader, isProposer, staged, suppliedBlockAccessList)
        execResult <- executeBlockTransactions(block, initialWorld, accessList)
        worldAfterReward <- Either
          .catchOnly[MPTException](blockPreparator.payBlockReward(block, execResult.worldState))
          .leftMap(BlockExecutionError.MPTError.apply)
        // EIP-4895: Process beacon chain withdrawals (Shanghai+)
        worldAfterWithdrawals = processWithdrawals(block, worldAfterReward, postExecutionReads)
        _ <- requireRequestPredeploysPresent(block, worldAfterWithdrawals)
        // Prague: Process system calls for withdrawal/consolidation requests; Amsterdam adds the two
        // EIP-8282 builder predeploys. The system-call outputs (types 0x01, 0x02 and, post-Amsterdam,
        // 0x03, 0x04) and deposit log requests (type 0x00) combine to form the EIP-7685 requestsHash;
        // follower mode verifies, proposer mode emits.
        //
        // Both halves can INVALIDATE the block (EIP-6110 / EIP-7002 / EIP-7251, EELS
        // `process_checked_system_transaction` / `extract_deposit_data`): a system call that halts or
        // reverts, and a DepositEvent log whose ABI layout is not the canonical one.
        systemCallResult <- processPragueSystemCallsChecked(block, worldAfterWithdrawals, postExecutionReads)
          .leftMap(BlockExecutionError.ValidationAfterExecError.apply)
        worldAfterSystemCalls = systemCallResult._1
        systemRequests = systemCallResult._2
        depositRequest <- (
          if blockchainConfig.isPragueTimestamp(block.header.unixTimestamp) then
            collectDepositRequests(execResult.receipts)
          else Right(None)
        ).leftMap(BlockExecutionError.ValidationAfterExecError.apply)
        // State root hash needs to be up-to-date for validateBlockAfterExecution. For a proposer this is also where
        // the root its header will carry is computed; where the nodes go is buildInitialWorld's storage.
        worldPersisted = ImportProfile.finalPersist(InMemoryWorldStateProxy.persistState(worldAfterSystemCalls))
      yield
        for
          builder <- accessList
          reads <- postExecutionReads
        do
          ImportProfile.balBuild(
            builder.addIndex(block.body.transactionList.size + 1L, reads, execResult.worldState, worldPersisted)
          )
        execResult.copy(
          worldState = worldPersisted,
          executionRequests = depositRequest.toSeq ++ systemRequests,
          blockAccessList = accessList.map(_.build)
        )
    catch case e: MPTException => Left(BlockExecutionError.MPTError(e))
    finally
      // Cancel and drain BEFORE this block's state is committed or pruned: no prefetch read outlives the execution.
      prefetch.foreach(run => ImportProfile.recordPrefetch(run.finish()))

  /** EIP-7928: starts the parent-state prefetch (see [[BalPrefetcher]]) for an Amsterdam block that arrived with its
    * access list. Does nothing, and costs one `Option` test, for a block without a list, a proposer build, a node with
    * no staged storage, and every pre-Amsterdam or ETC block. Consensus-neutral: it only warms caches.
    */
  private def startPrefetch(
      block: Block,
      parentHeader: BlockHeader,
      isProposer: Boolean,
      staged: Option[StagedBlockState],
      candidate: Option[BlockAccessList]
  )(implicit blockchainConfig: BlockchainConfig): Option[BalPrefetcher.Run] =
    if isProposer || candidate.isEmpty || !blockchainConfig.isAmsterdamTimestamp(block.header.unixTimestamp) then None
    else
      val nodeKeys = new BalPrefetcher.Run.NodeKeys
      val run = BalPrefetcher.start(
        candidate,
        block.header.blockAccessListHash,
        block.header.gasLimit.value,
        block.body.transactionList.size,
        parentHeader.stateRoot.value,
        staged.flatMap(_.prefetchReader(StateReadCacheConfig.balPrefetchNodeBudgetBytes, nodeKeys.add)),
        evmCodeStorage,
        blockchainConfig.ethCompatibleStorage,
        nodeKeys
      )
      run.foreach(r => ImportProfile.attachPrefetch(r.attribution))
      run

  protected def buildInitialWorld(
      block: Block,
      parentHeader: BlockHeader,
      isProposer: Boolean = false,
      staged: Option[StagedBlockState] = None
  )(implicit
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
    //
    // On the unified layout a proposer has a storage of its own: the parent's state under a layer that is dropped
    // with it (spec 018 C3c). It is the storage import executes the same block on, less the record of what changed,
    // so the divergence above has no counterpart there; `ProposerOnPositionsSpec` holds a build to the block that
    // import commits. With the guard off there is none, and the store is the one it was.
    InMemoryWorldStateProxy(
      evmCodeStorage = evmCodeStorage,
      staged
        .map(_.storage)
        .orElse(if isProposer then blockchainReader.proposerStateOn(parentHeader) else None)
        .getOrElse(blockchain.getBackingMptStorage(block.header.number.value)) match
        // Execution alone reads through the decoded-node cache; see DecodedNodeCache.
        case serializing: SerializingMptStorage => serializing.cachedForExecution
        case other                              => other,
      // BLOCKHASH walks this block's own ancestry (core-geth GetHashFn), never the canonical index — see
      // AncestorBlockHashes for why the index can name a different chain at the heights being asked about.
      AncestorBlockHashes.forBlock(block.header, blockchainReader),
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
      initialWorld: InMemoryWorldStateProxy,
      accessList: Option[BlockAccessListBuilder] = None
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, BlockResult] =
    val blockHeaderNumber = block.header.number.value
    executeBlockTransactions(block, blockHeaderNumber, initialWorld, accessList)

  protected def executeBlockTransactions(
      block: Block,
      blockHeaderNumber: BigInt,
      initialWorld: InMemoryWorldStateProxy,
      accessList: Option[BlockAccessListBuilder]
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError.TxsExecutionError, BlockResult] =
    val inputWorld = BlockExecution.applyBlockPreamble(block, blockHeaderNumber, initialWorld, accessList)

    val hashAsHexString = block.header.hashAsHexString
    val transactionList = block.body.transactionList
    log.debug(
      s"About to execute ${transactionList.size} txs from block $blockHeaderNumber (with hash: $hashAsHexString)"
    )
    val blockTxsExecResult =
      blockPreparator.executeTransactions(transactionList, inputWorld, block.header, accessList = accessList)
    blockTxsExecResult match
      case Right(_) => log.debug(s"All txs from block $hashAsHexString were executed successfully")
      case Left(error) =>
        log.debug(s"Not all txs from block $hashAsHexString were executed correctly, due to ${error.describe}")
    blockTxsExecResult

  /** Executes and validates a list of blocks, storing the results in the blockchain.
    *
    * @param blocks
    *   blocks to be executed
    * @param parentChainWeight
    *   parent weight
    * @param adoptEachBlock
    *   make each block the best block in the same atomic write that stores it (the extends-best import path). False for
    *   a side branch: `ConsensusImpl.settleHead` decides afterwards which head the node keeps.
    * @param suppliedBlockAccessLists
    *   peer-supplied EIP-7928 lists by block hash (regular-sync catch-up over eth/71). Prefetch hints only.
    *
    * Each block's state writes are staged and committed with the block, or not at all: a block that fails leaves
    * nothing behind, so a retry starting at it applies nothing twice. See [[StagedBlockState]].
    *
    * @return
    *   a list of blocks in incremental order that were correctly executed and an optional
    *   [[com.chipprbots.ethereum.ledger.BlockExecutionError]]
    */
  def executeAndValidateBlocks(
      blocks: List[Block],
      parentChainWeight: ChainWeight,
      adoptEachBlock: Boolean = false
  )(implicit blockchainConfig: BlockchainConfig): (List[BlockData], Option[BlockExecutionError]) =
    executeAndValidateBlocksWithAccessLists(blocks, parentChainWeight, adoptEachBlock, Map.empty)

  /** [[executeAndValidateBlocks]] for blocks that arrived with peer-supplied EIP-7928 lists.
    *
    * '''A batch has a third way to end''' (spec 018 Track C, C4b): this node's own state could not take the next block.
    * The state the block is to be executed on is not the one the disk holds, or is no longer (another writer moved it
    * between two blocks of the batch), or the view it was read through went stale. That says nothing about the block,
    * and nothing about the peer that sent it. It is returned as [[BlockExecutionError.StateFaulted]], which is no
    * verdict: the blocks before it are adopted, each in its own atomic write, exactly as a shorter batch would have
    * left them; the block the fault was met at wrote nothing, and neither it nor the blocks after it were judged. So
    * the batch stops there: executing on would be executing on a state that is not held. A caller tells the two endings
    * apart with [[BlockExecution.BatchOutcome.of]].
    */
  def executeAndValidateBlocksWithAccessLists(
      blocks: List[Block],
      parentChainWeight: ChainWeight,
      adoptEachBlock: Boolean,
      suppliedBlockAccessLists: Map[ByteString, BlockAccessList]
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
        // `alreadyValidated = false`: validate each block BEFORE executing it.
        //
        // `validateBlockBeforeExecution` is the only caller of
        // `blockHeaderValidator.validate` and `ommersValidator.validate` on this path
        // (ValidatorsExecutor.validateBlockBeforeExecution). This method's two callers,
        // ConsensusImpl.importToTop and ConsensusImpl.importToNewBranch, serve bulk p2p
        // import and Engine API newPayload, so passing `true` here meant peer-supplied
        // blocks reached execution with their headers entirely unchecked — no PoW, no
        // difficulty, no gasLimit bound, no ommer rules — and were accepted outright.
        // BlockExecutionPreValidationSpec measured that: 3 of 3 blocks executed with
        // error=None while one block's header validator returned HeaderDifficultyError.
        //
        // `true` was a correct contract in the original Mantis, where blocks were imported
        // one at a time through a path that always validated first. 6ad1dec (bulk
        // `evaluateBranch`) and e168554 (the extends-best skip in ConsensusAdapter) removed
        // that guarantee without retiring the flag.
        //
        // No thread race here despite the comment at ConsensusAdapter.scala:67-71: `go` is a
        // single @tailrec loop on one thread, each iteration's `blockchainWriter.save`
        // completes before the next begins, and `executeBlock` already resolves the parent
        // header from the same storage at BlockExecution.scala:104-106.
        // A fault of the state is no verdict on the block: it is kept apart from the block's own errors, so that
        // nothing downstream can take the one for the other.
        val step: Either[StateFault, Either[BlockExecutionError, BlockData]] =
          try
            Right(
              executeAndCommit(
                blockToExecute,
                parentWeight,
                adoptEachBlock,
                suppliedBlockAccessLists.get(blockToExecute.hash.value),
                () => None
              )
            )
          catch case fault: StateFault => Left(fault)
        step match
          case Right(Right(newBlockData)) =>
            blockchain.saveBlockState(blockToExecute.header.number.value)
            blockchainReader.recordBlockDifficulty(blockToExecute.header.difficulty)
            go(newBlockData :: executedBlocksDecOrder, remainingBlocksIncOrder.tail, newBlockData.weight)
          case Right(Left(executionError)) =>
            (executedBlocksDecOrder.reverse, Some(executionError))
          case Left(fault) =>
            (executedBlocksDecOrder.reverse, Some(BlockExecutionError.StateFaulted(fault)))

    go(List.empty[BlockData], blocks, parentChainWeight)

  /** One block of [[executeAndValidateBlocksWithAccessLists]]: execute, validate and commit atomically. `beforeCommit`
    * runs after validation, immediately before the commit; a `Some` error discards the staged state and is returned
    * instead.
    */
  private def executeAndCommit(
      blockToExecute: Block,
      parentWeight: ChainWeight,
      adoptEachBlock: Boolean,
      suppliedBlockAccessList: Option[BlockAccessList],
      beforeCommit: () => Option[BlockExecutionError]
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, BlockData] =
    val blockHash = blockToExecute.header.hash.value
    // One execution + commit per block hash at a time, across the Engine API and this importer. A block the other
    // path already executed and committed (and that is still applied) is not executed again: its recorded
    // result is used and only the block's own records are written.
    ChainInsertLock.shared(BlockImportGate.withBlock(blockHash) {
      val staged = blockchain.stageBlockState(blockToExecute.header)
      if !adoptEachBlock then refuseUnadopted(blockToExecute, staged, "a block that is not adopted as it is executed")
      val outcome =
        try
          BlockImportGate.alreadyExecuted(blockHash, staged) match
            case Some(done) => Right(done.asTuple)
            // EIP-7928: a list a peer served for this block (eth/71) is a prefetch hint, checked against the
            // header before use; a block without one takes the unchanged path.
            case None =>
              suppliedBlockAccessList match
                case None => executeAndValidateStaged(blockToExecute, alreadyValidated = false, staged)
                case hint =>
                  executeAndValidateStagedWithList(blockToExecute, alreadyValidated = false, staged, hint)
        catch
          case t: Throwable =>
            staged.discard()
            throw t
      outcome.flatMap(executed =>
        beforeCommit() match
          case Some(refusal) => Left(refusal)
          case None          => Right(executed)
      ) match
        case Right(executed @ (receipts, _, blockAccessList)) =>
          val newWeight = parentWeight.increase(blockToExecute.header)
          val newBlockData = BlockData(blockToExecute, receipts, newWeight)
          // ONE atomic write: the block, its receipts and weight, its access list, the state it wrote (trie
          // nodes, reference counts, snapshots, death row) and, when adopting, the best-block pointer. A kill can
          // no longer leave a block's state applied but the block not, and a block that failed above staged
          // nothing at all.
          val base = blockchainWriter.saveBatch(
            newBlockData.block,
            newBlockData.receipts,
            newBlockData.weight,
            saveAsBestBlock = adoptEachBlock
          )
          // EIP-7928: the list the block just validated against.
          val withAccessList =
            blockAccessList.fold(base)(bal =>
              base.and(blockchainWriter.storeBlockAccessList(blockToExecute.header.hash, bal))
            )
          timedCommit(blockToExecute)(
            staged.commitWith(blockToExecute.header.number.value, blockHash, Some(withAccessList))
          )
          BlockImportGate.record(blockHash, executed)
          Right(newBlockData)
        case Left(executionError) =>
          staged.discard()
          Left(executionError)
    })

  /** One block onto the head, for an operator chain import holding [[ChainInsertLock.exclusive]]: the same execution,
    * validation and atomic commit (with the head pointer) as [[executeAndValidateBlocks]], plus `beforeCommit`, checked
    * after validation and immediately before the commit, so a head that moved meanwhile (a consensus client's
    * forkchoiceUpdated is not under the lock) fails the block instead of being overwritten.
    */
  def executeAndAdoptBlockIf(
      block: Block,
      parentWeight: ChainWeight,
      beforeCommit: () => Option[BlockExecutionError]
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, BlockData] =
    val result = executeAndCommit(block, parentWeight, adoptEachBlock = true, None, beforeCommit)
    result.foreach { _ =>
      blockchain.saveBlockState(block.header.number.value)
      blockchainReader.recordBlockDifficulty(block.header.difficulty)
    }
    result

  /** EIP-4895: Process beacon chain withdrawals (Shanghai+). Each withdrawal credits `amount * 1 Gwei` to the target
    * address. No gas is charged. Creates the account if it doesn't exist.
    */
  private def processWithdrawals(
      block: Block,
      world: InMemoryWorldStateProxy,
      accessRecorder: Option[BlockAccessRecorder]
  ): InMemoryWorldStateProxy =
    block.body.withdrawals match
      case Some(withdrawals) if withdrawals.nonEmpty =>
        val GweiToWei = BigInt("1000000000")
        withdrawals.foldLeft(world) { (w, withdrawal) =>
          // EIP-7928: every recipient is read, a zero amount included (execution-specs `create_ether` in
          // `process_withdrawals`), though a zero amount leaves the account untouched below.
          BlockAccessRecorder.account(accessRecorder, withdrawal.address)
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
  /** EIP-7002 / EIP-7251: "If there is no code at <PREDEPLOY_ADDRESS>, the corresponding block MUST be marked invalid."
    * Checked on the world AFTER transactions and withdrawals, so a block that deploys the contract itself is valid
    * (EIP-7002 "Empty code failure": "the empty code validation occurs after block-transactions execution"). EEST:
    * SYSTEM_CONTRACT_EMPTY, test_system_contract_deployment[CancunToPragueAtTime15k-deploy_after_fork-*].
    *
    * Emptiness is read from the account's codeHash, which is state-trie data, rather than from the code bytes, so a
    * node with incomplete code storage cannot misreport a deployed contract as missing and condemn a valid block.
    *
    * Same targets, in the same order, as processPragueSystemCalls (which keeps its silent `code.nonEmpty` skip; that is
    * now unreachable for a block that passes here). Prague-gated: ETC never reaches it.
    */
  private[ledger] def requireRequestPredeploysPresent(
      block: Block,
      world: InMemoryWorldStateProxy
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, Unit] =
    if !blockchainConfig.isPragueTimestamp(block.header.unixTimestamp) then Right(())
    else
      BlockExecution
        .systemCallTargets(block.header.unixTimestamp)
        .collectFirst {
          case (addr, _) if world.getAccount(addr).forall(_.codeHash == Account.EmptyCodeHash) => addr
        }
        .fold(Right(())) { addr =>
          Left(BlockExecutionError.ValidationAfterExecError(s"SYSTEM_CONTRACT_EMPTY: no code at system contract $addr"))
        }

  private[ledger] def processPragueSystemCalls(
      block: Block,
      world: InMemoryWorldStateProxy
  )(implicit blockchainConfig: BlockchainConfig): (InMemoryWorldStateProxy, Seq[ByteString]) =
    processPragueSystemCallsChecked(block, world).fold(err => throw new IllegalStateException(err), identity)

  /** [[processPragueSystemCalls]], reporting a failed system call instead of ignoring it.
    *
    * EIP-7002 / EIP-7251: if the system call fails (halts exceptionally or reverts) the block MUST be deemed invalid.
    * go-ethereum `processRequestsSystemCall` returns `system call failed to execute` on any EVM error; EELS
    * `process_checked_system_transaction` raises InvalidBlock. Previously the result's error was dropped and whatever
    * return data it carried (a REVERT's payload, or nothing) was folded into the requests — EEST
    * `test_system_contract_errors[system_contract_{reverts,throws,out_of_gas}]` imported as VALID.
    *
    * An empty predeploy never reaches here in `executeBlock`: [[requireRequestPredeploysPresent]] has already made that
    * block invalid (SYSTEM_CONTRACT_EMPTY). The `code.nonEmpty` skip below stays as the guard for the direct callers
    * (tests, proposer paths) that do not run that check.
    */
  private[ledger] def processPragueSystemCallsChecked(
      block: Block,
      world: InMemoryWorldStateProxy,
      accessRecorder: Option[BlockAccessRecorder] = None
  )(implicit blockchainConfig: BlockchainConfig): Either[String, (InMemoryWorldStateProxy, Seq[ByteString])] =
    BlockExecution.requestSystemCalls(block.header, world, accessRecorder)

  /** EIP-6110: Parse `DepositEvent(bytes,bytes,bytes,bytes,bytes)` logs emitted by the beacon deposit contract during
    * block execution and return one request entry: 0x00 || (pubkey || wc || amount || signature || index) per deposit,
    * or None if there were none.
    *
    * The contract is the chain's own ([[BlockExecution.depositContractFor]]), not the mainnet constant: Sepolia's is
    * `0x7f02c3e3…295d`. Scanning the mainnet address there finds no deposits, so every Sepolia block carrying one
    * computed `sha256("")` instead of the header's `requestsHash` and was rejected as INVALID_REQUESTS (Sepolia block
    * 11,321,359: 10 deposits, `SepoliaDepositRequestsSpec`).
    *
    * A deposit log whose ABI layout is not exactly the canonical one — 576 bytes, offsets 160/256/320/384/512, sizes
    * 48/32/8/96/8 — makes the BLOCK invalid (EELS `extract_deposit_data`, EEST `test_invalid_layout` /
    * `test_invalid_log_length`: INVALID_DEPOSIT_EVENT_LAYOUT). Previously any log of at least 608 bytes was sliced at
    * fixed positions and any shorter one silently dropped, so a malformed event was misparsed or ignored, never
    * rejected.
    */
  def collectDepositRequests(receipts: Seq[Receipt])(implicit
      blockchainConfig: BlockchainConfig
  ): Either[String, Option[ByteString]] =
    BlockExecution.depositRequests(receipts)

object BlockExecution:

  /** How a batch of blocks ended ([[BlockExecution.executeAndValidateBlocksWithAccessLists]]), with the fault of this
    * node's own state told apart from an error of the block.
    *
    * @param executed
    *   the blocks that were executed, validated and committed, in order: a prefix of the batch
    * @param error
    *   why the block after them is not accepted, when it was judged and failed
    * @param stateFault
    *   why the block after them could not be executed or committed, when the fault is this node's own state's: the
    *   block was not judged. Never set together with `error`
    */
  final case class BatchOutcome(
      executed: List[BlockData],
      error: Option[BlockExecutionError],
      stateFault: Option[StateFault]
  )

  object BatchOutcome:
    /** What a batch returned, with a [[BlockExecutionError.StateFaulted]] taken out of the errors: after this nothing
      * that judges a block by its error can be handed a fault of the state.
      */
    def of(executed: List[BlockData], error: Option[BlockExecutionError]): BatchOutcome = error match
      case Some(BlockExecutionError.StateFaulted(fault)) => BatchOutcome(executed, None, Some(fault))
      case judged                                        => BatchOutcome(executed, judged, None)

  /** What [[requestSystemCalls]] does with a request contract that has no code when its turn comes.
    *
    * The two policies are the two consumers. Block execution cannot reach an empty contract at all
    * (`BlockExecution.requireRequestPredeploysPresent` has already made such a block invalid, on the codeHash), so its
    * `Skip` is only the guard for the direct callers (tests, proposer paths). eth_simulateV1 has no such earlier check
    * and runs go-ethereum's `processRequestsSystemCall`, which fails the block.
    */
  private[ledger] enum EmptyRequestContract:
    /** Go on to the next contract, and add nothing to the requests. */
    case Skip

    /** Fail with go-ethereum's `failed to process <queue>: empty system contract: no code at <EIP-55 address>`. */
    case Fail

  /** go-ethereum's name for the queue a request type comes from, as `PostExecution` words its error (`failed to process
    * withdrawal queue: ...`, state_processor.go:211, :215, :222, :225).
    */
  private def requestQueueName(requestType: Int): String = requestType match
    case WithdrawalRequestType     => "withdrawal queue"
    case ConsolidationRequestType  => "consolidation queue"
    case BuilderDepositRequestType => "builder deposit queue"
    case BuilderExitRequestType    => "builder exit queue"
    case other                     => s"request type 0x${other.toHexString}"

  /** go-ethereum `processRequestsSystemCall`'s refusal of a request contract with no code (state_processor.go:470-472:
    * `empty system contract: no code at %v`), under the wrapper `PostExecution` gives it. `%v` of a `common.Address` is
    * its EIP-55 form (types.go `Format`), and the code size is read from the world the earlier calls left.
    */
  private def emptyRequestContractError(queueAddr: Address, requestType: Int): String =
    val queue = requestQueueName(requestType)
    s"failed to process $queue: empty system contract: no code at ${StxLedger.checksum(queueAddr)}"

  /** The body of `BlockExecution.processPragueSystemCallsChecked` (see there), on a header rather than a block, so that
    * eth_simulateV1 runs the very same EIP-7002 / EIP-7251 / EIP-8282 system calls a real block does
    * ([[SimulatedBlockSystemCalls]]). Moved here unchanged; block execution delegates to it.
    *
    * The two trailing parameters are eth_simulateV1's, and their defaults are block execution's, so a real block runs
    * exactly as before:
    *   - `precompileRelocations`: the block's precompile relocations (`a -> t` moved, `a -> a` deleted, as
    *     [[SimulationOverrides]] and `EthSimulateService.applyStateOverrides` give them). go-ethereum runs these calls
    *     on the same EVM, with the same overridden precompile set, as the block's transactions (simulate.go:403-409),
    *     so a contract the call reaches sees the moved or deleted precompile as a transaction would.
    *   - `emptyContract`: see [[EmptyRequestContract]].
    */
  private[ledger] def requestSystemCalls(
      header: BlockHeader,
      world: InMemoryWorldStateProxy,
      accessRecorder: Option[BlockAccessRecorder],
      precompileRelocations: Map[Address, Address] = Map.empty,
      emptyContract: EmptyRequestContract = EmptyRequestContract.Skip
  )(implicit blockchainConfig: BlockchainConfig): Either[String, (InMemoryWorldStateProxy, Seq[ByteString])] =
    if !blockchainConfig.isPragueTimestamp(header.unixTimestamp) then Right((world, Nil))
    else
      val evmConfig = EvmConfig.forBlock(header.number.value, header.unixTimestamp, blockchainConfig)
      var w = world
      val outputs = scala.collection.mutable.ListBuffer.empty[ByteString]

      // EIP-7685: Execute system calls to request contracts and collect output.
      // EIP-6110 DEPOSIT contract has no system call — deposits are parsed from logs.
      // Prague: EIP-7002 (withdrawals) and EIP-7251 (consolidations). Amsterdam adds the two EIP-8282 builder
      // predeploys. The SYSTEM_ADDRESS call is not optional bookkeeping: each predeploy's dequeue path clears the
      // per-block slots its user path dirtied (for the builder deposit contract, slots 0x01 and 0x03), so skipping
      // the call leaves those slots set and forks the storage root -> account RLP -> STATE ROOT.
      //
      // GAS: on an Amsterdam block every system call, the pre-existing EIP-7002/7251 ones included, gets EIP-8037's
      // state-gas reservoir BESIDE its unchanged 30,000,000 execution grant (see `systemCallContext`). The queue
      // predeploys are bounded loops that never read GAS, so for them it changes nothing; that is asserted, not assumed:
      // `AmsterdamBuilderRequestsSpec` runs the fixture's real withdrawal and consolidation bytecode over a non-empty
      // queue on both sides of the fork and requires byte-identical requests and storage.
      var failure: Option[String] = None
      for (queueAddr, requestType) <- BlockExecution.systemCallTargets(header.unixTimestamp)
      do
        val code = w.getCode(queueAddr)
        if failure.isEmpty && code.nonEmpty then
          // EIP-7928: execution-specs `process_checked_system_transaction` checks the code on an untracked state, then
          // makes the call, which reads the target as any call does. All four share block access index n + 1.
          accessRecorder.foreach(BlockExecution.recordSystemCallTarget(w, queueAddr, _))
          val context =
            BlockExecution.systemCallContext(
              header,
              w,
              queueAddr,
              ByteString.empty,
              evmConfig,
              accessRecorder,
              precompileRelocations
            )
          val vm = new com.chipprbots.ethereum.vm.VM[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage]
          val result = vm.run(context)
          result.error match
            case Some(err) =>
              failure = Some(s"SYSTEM_CONTRACT_CALL_FAILED: system call to $queueAddr failed: $err")
            case None =>
              w = InMemoryWorldStateProxy.persistState(result.world)
              // EIP-7685 request bytes = single-byte type prefix || raw system-call returndata.
              // Empty returndata (no queued requests) means no bytes are emitted for this type.
              if result.returnData.nonEmpty then outputs += ByteString(Array(requestType.toByte)) ++ result.returnData
        else if failure.isEmpty && emptyContract == EmptyRequestContract.Fail then
          // Here `code` is empty. go-ethereum checks the code size of each contract on the world the calls before it left,
          // before it touches the contract (state_processor.go:470), so nothing is recorded for the access list.
          failure = Some(emptyRequestContractError(queueAddr, requestType))
      failure.toLeft((w, outputs.toSeq))

  /** The body of `BlockExecution.collectDepositRequests` (see there), shared with eth_simulateV1. Moved here unchanged.
    */
  private[ledger] def depositRequests(receipts: Seq[Receipt])(implicit
      blockchainConfig: BlockchainConfig
  ): Either[String, Option[ByteString]] =
    val depositContract = depositContractFor(blockchainConfig)
    val deposits = for
      receipt <- receipts
      log <- receipt.logs
      if log.loggerAddress == depositContract
      if log.logTopics.headOption.contains(DepositEventSignature)
    yield log.data
    deposits.toList
      .traverse(extractDepositData)
      .left
      .map(err => s"INVALID_DEPOSIT_EVENT_LAYOUT: $err")
      .map { bodies =>
        if bodies.isEmpty then None
        else Some(bodies.foldLeft(ByteString(Array(DepositRequestType.toByte)))(_ ++ _))
      }

  val SystemAddress: Address = Address("0xfffffffffffffffffffffffffffffffffffffffe")

  /** EIP-6110: the MAINNET beacon deposit contract, and the fallback for a chain that declares none. Never compare a
    * log address with this directly; resolve the chain's contract with [[depositContractFor]].
    */
  val DepositContractAddress: Address = Address("0x00000000219ab540356cBB839Cbe05303d7705Fa")

  /** EIP-6110 `DEPOSIT_CONTRACT_ADDRESS` of the chain being executed: its `deposit-contract-address` if declared,
    * otherwise the mainnet contract.
    *
    * EIP-6110 lists the address as network configuration ("Mainnet" row) that MUST ship with the client; go-ethereum
    * reads `ChainConfig.DepositContractAddress` in `ParseDepositLogs` (Sepolia `0x7f02c3e3…295d`). The fallback is
    * execution-specs': its `requests.py` hard-codes the mainnet address and EEST fixtures declare none. go-ethereum
    * differs only for a custom genesis that omits the field, where its zero value scans address 0x0.
    *
    * Execution and `eth_config` both resolve through here, so the address a node advertises is the one it validates.
    */
  def depositContractFor(blockchainConfig: BlockchainConfig): Address =
    blockchainConfig.depositContractAddress.getOrElse(DepositContractAddress)

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

  /** The top-level context of a SYSTEM_ADDRESS call to `target`: value 0 and no transfer, `target` warm, gas price 0
    * (go-ethereum's system `Message`).
    *
    * Gas: 30,000,000 of execution gas, on every fork. From Amsterdam (`evmConfig.amsterdamEnabled`) EIP-8037 adds a
    * state-gas reservoir of 16 x GAS_STORAGE_SET = 1,566,720 BESIDE that grant, not inside it: execution-specs
    * `process_unchecked_system_transaction` sets `execution_gas_grant = SYSTEM_TRANSACTION_GAS` and
    * `state_gas_reservoir = STORAGE_SET * SYSTEM_MAX_SSTORES_PER_CALL`, and go-ethereum `systemCallGasBudget` does the
    * same. So GAS reads 30,000,000 less what has been spent, a call that needs more than 30,000,000 of execution gas
    * halts whatever reservoir is left, and state charges draw the reservoir first and only then the execution grant
    * (EEST `test_system_call_execution_grant`, `_execution_boundary`, `_reservoir_boundary`). fukuii previously granted
    * the 31,566,720 total as execution gas, with no reservoir.
    *
    * Pre-Amsterdam (ETH Prague/Osaka) the reservoir is 0 and the context is the one the EIP-7002/7251 calls always had.
    * ETC never builds one: its only system-contract write, EIP-2935 at Olympia, is a direct storage write.
    *
    * `precompileRelocations` is eth_simulateV1's (see [[requestSystemCalls]]); empty, as every real block has it, the
    * context is what it was.
    */
  def systemCallContext(
      header: BlockHeader,
      world: InMemoryWorldStateProxy,
      target: Address,
      input: ByteString,
      evmConfig: EvmConfig,
      accessRecorder: Option[BlockAccessRecorder] = None,
      precompileRelocations: Map[Address, Address] = Map.empty
  ): ProgramContext[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage] =
    val stateGasReservoir = if evmConfig.amsterdamEnabled then AmsterdamGas.SystemCallStateGasReservoir else BigInt(0)
    ProgramContext[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage](
      callerAddr = SystemAddress,
      originAddr = SystemAddress,
      recipientAddr = Some(target),
      gasPrice = UInt256.Zero,
      startGas = AmsterdamGas.SystemCallExecutionGas,
      inputData = input,
      value = UInt256.Zero,
      endowment = UInt256.Zero,
      doTransfer = false,
      blockHeader = header,
      callDepth = 0,
      world = world,
      initialAddressesToDelete = Set.empty,
      evmConfig = evmConfig,
      originalWorld = world,
      warmAddresses = Set(target),
      warmStorage = Set.empty,
      precompileRelocations = precompileRelocations,
      stateGasReservoir = stateGasReservoir,
      initialStateGasReservoir = stateGasReservoir,
      accessRecorder = accessRecorder
    )

  /** EIP-7928: what a SYSTEM_ADDRESS call reads before its code runs (execution-specs `create_evm` for a value-0 call):
    * the target, and — should the target carry an EIP-7702 delegation indicator — the delegated address, whose access
    * charge 30,000,000 of gas always covers. SYSTEM_ADDRESS itself is not read: a value-0 call moves no ether, so it
    * enters the list only if the called code reads it (EIP-7928 "Scope and Inclusion").
    */
  private[ledger] def recordSystemCallTarget(
      world: InMemoryWorldStateProxy,
      target: Address,
      recorder: BlockAccessRecorder
  ): Unit =
    recorder.recordAccount(target)
    SetCodeTransaction.parseDelegation(world.getCode(target)).foreach(recorder.recordAccount)

  /** A block's state changes before its first transaction, in block import's order: the DAO fork block's drain; then,
    * from Amsterdam, the EIP-4788 / EIP-2935 system calls (EIP-7928 block access index 0), and before it the direct
    * storage writes of EIP-4788 (ETH from Cancun) and EIP-2935 (ETH from Prague, ETC from Olympia).
    *
    * One function for block import (`executeBlockTransactions`) and block building (`BlockPreparator.prepareBlock`), so
    * the state a miner builds a block on is the state import executes it on.
    */
  private[ethereum] def applyBlockPreamble(
      block: Block,
      blockHeaderNumber: BigInt,
      initialWorld: InMemoryWorldStateProxy,
      accessList: Option[BlockAccessListBuilder]
  )(implicit blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy =
    val worldAfterDao = blockchainConfig.daoForkConfig match
      case Some(daoForkConfig) if daoForkConfig.isDaoForkBlock(blockHeaderNumber) =>
        drainDaoForkAccounts(initialWorld, daoForkConfig)
      case _ => initialWorld

    if blockchainConfig.isAmsterdamTimestamp(block.header.unixTimestamp) then
      // Amsterdam: EIP-4788 and EIP-2935 run as real system calls (see `applyAmsterdamPreambleSystemCalls`), which
      // share EIP-7928 block access index 0.
      val preambleReads = accessList.map(_ => new BlockAccessRecorder)
      val afterPreamble = applyAmsterdamPreambleSystemCalls(block.header, worldAfterDao, preambleReads)
      for
        builder <- accessList
        reads <- preambleReads
      do builder.addIndex(0L, reads, worldAfterDao, afterPreamble)
      afterPreamble
    else
      // EIP-4788: Store parent beacon block root in system contract (post-Cancun)
      val worldAfterBeaconRoot = applyEip4788(block, worldAfterDao)

      // EIP-2935: Store parent block hash in history storage contract
      applyEip2935(block, worldAfterBeaconRoot)

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
    // Only apply post-Cancun (when parentBeaconBlockRoot is present)
    block.header.parentBeaconBlockRoot match
      case Some(beaconRoot) if blockchainConfig.isCancunTimestamp(block.header.unixTimestamp) =>
        // toUInt256, NOT UInt256(_.toLong): the latter sign-extends, so a 2^64-1 timestamp
        // becomes 2^256-1 and lands at ring index 511 instead of 4095 — AND stores that
        // wrong value in the slot, which test_beacon_root_equal_to_timestamp reads back.
        val timestamp = block.header.unixTimestamp.toUInt256
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
    val blockNumber = block.header.number.value
    // EIP-2935 activates at Prague on ETH chains (timestamp fork), or at Olympia on ETC chains (block number fork).
    val pragueActive = blockchainConfig.isPragueTimestamp(block.header.unixTimestamp)
    val etcOlympiaActive = blockchainConfig.networkType == com.chipprbots.ethereum.utils.NetworkType.ETC &&
      blockNumber >= blockchainConfig.forkBlockNumbers.olympiaBlockNumber
    if !pragueActive && !etcOlympiaActive then world
    // ETH (Prague): EIP-2935 "if no code exists at HISTORY_STORAGE_ADDRESS, the call must fail silently".
    // Exactly as for EIP-4788 (see applyEip4788), the client MUST NOT deploy the contract itself: on ETH it is
    // deployed by a regular transaction, and go-ethereum's ProcessParentBlockHash is a SYSTEM_ADDRESS call with
    // value 0, which under EIP-158 returns without creating anything when the account does not exist. Self-deploying
    // here wrote an account (nonce 1, code) plus a slot the reference does not have, and forked the state root of
    // the first Prague block on any chain that deploys the contract in-chain rather than in genesis (EEST
    // test_system_contract_deployment[CancunToPragueAtTime15k-deploy_after_fork / deploy_on_fork_block]).
    else if !etcOlympiaActive && world.getCode(HistoryStorageAddress).isEmpty then world
    else
      // ETC (Olympia, ECIP-1112): the history contract IS deployed by the client at activation, so deploy it here
      // if absent (genesis may pre-deploy it). Code presence is the sole guard. Tying deployment to
      // isActivationBlock caused IllegalStateException when processing a post-activation block on a fresh world:
      // the account was absent so getStorage threw.
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

  /** Amsterdam: the EIP-4788 and EIP-2935 block preamble as execution-specs `apply_body` runs it — two
    * `process_unchecked_system_transaction` calls from SYSTEM_ADDRESS, the beacon roots contract with the parent beacon
    * block root, then the history contract with the parent hash — instead of the direct storage writes of the
    * pre-Amsterdam `applyEip4788` / `applyEip2935` above.
    *
    * The direct writes are the shortcut EIP-4788 permits ("clients may decide to omit an explicit EVM call and directly
    * set the storage values"), and they give the same storage only while the canonical contracts are the code at those
    * addresses. On Amsterdam the shortcut stops being equivalent. EIP-8037 funds each call with an execution grant and
    * a separate state-gas reservoir (see `systemCallContext`), and EEST checks that by putting its own code at both
    * addresses (`test_system_call_execution_grant`, `_execution_boundary`, `_reservoir_boundary`, beacon and history
    * variants): only running the code gives their storage. EIP-7928 also records the calls' reads and writes in the
    * block access list at index 0, which needs the calls to run.
    *
    * With the canonical contracts the storage is the same as the direct writes: the beacon roots contract stores the
    * timestamp and the root at slots `timestamp % 8191` and `timestamp % 8191 + 8191`, and the history contract stores
    * the parent hash at slot `(number - 1) % 8191` (asserted in AmsterdamSystemCallSpec). Every earlier fork and every
    * ETC fork keep the direct writes.
    *
    * Here in the companion rather than on the class so that block execution and the trace replays of an Amsterdam block
    * (`StxLedger.advanceWorldToTx`) run one and the same preamble.
    *
    * `precompileRelocations` is eth_simulateV1's (see [[requestSystemCalls]]); block execution and the trace replays
    * leave it empty.
    */
  private[ledger] def applyAmsterdamPreambleSystemCalls(
      header: BlockHeader,
      world: InMemoryWorldStateProxy,
      accessRecorder: Option[BlockAccessRecorder],
      precompileRelocations: Map[Address, Address] = Map.empty
  )(implicit blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy =
    val afterBeaconRoot = header.parentBeaconBlockRoot.fold(world) { root =>
      uncheckedSystemCall(header, world, BeaconRootContractAddress, root.value, accessRecorder, precompileRelocations)
    }
    uncheckedSystemCall(
      header,
      afterBeaconRoot,
      HistoryStorageAddress,
      header.parentHash.value,
      accessRecorder,
      precompileRelocations
    )

  /** execution-specs `process_unchecked_system_transaction`: a SYSTEM_ADDRESS call to `target`, whose failure is
    * ignored — the call's state changes are dropped and the block stays valid. No code at `target` means nothing to run
    * and nothing changes, as for any zero-value call to an account without code.
    */
  private def uncheckedSystemCall(
      header: BlockHeader,
      world: InMemoryWorldStateProxy,
      target: Address,
      input: ByteString,
      accessRecorder: Option[BlockAccessRecorder],
      precompileRelocations: Map[Address, Address]
  )(implicit blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy =
    // EIP-7928: the target is read even when it has no code (execution-specs `create_evm` resolves its code first;
    // EEST `bal_4788_absent_contract`), and its reads stay when the call fails
    // (`bal_pre_execution_call_failure_keeps_read_drops_write`).
    accessRecorder.foreach(recordSystemCallTarget(world, target, _))
    if world.getCode(target).isEmpty then world
    else
      val evmConfig = EvmConfig.forBlock(header.number.value, header.unixTimestamp, blockchainConfig)
      val context = systemCallContext(header, world, target, input, evmConfig, accessRecorder, precompileRelocations)
      val result =
        new com.chipprbots.ethereum.vm.VM[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage].run(context)
      if result.error.isDefined then world else InMemoryWorldStateProxy.persistState(result.world)

  /** EIP-6110 `DepositEvent` ABI layout: (offset, size) of pubkey, withdrawal_credentials, amount, signature, index. */
  private val DepositEventLength = 576
  private val DepositEventFields: Seq[(Int, Int)] = Seq((160, 48), (256, 32), (320, 8), (384, 96), (512, 8))

  /** EELS `extract_deposit_data`: validate a `DepositEvent` payload's layout and return the unframed 192-byte body. */
  def extractDepositData(data: ByteString): Either[String, ByteString] =
    def word(at: Int): BigInt = BigInt(1, data.slice(at, at + 32).toArray)
    if data.length != DepositEventLength then Left(s"deposit event data length ${data.length} != $DepositEventLength")
    else
      DepositEventFields.zipWithIndex
        .collectFirst {
          case ((offset, _), i) if word(i * 32) != offset  => s"field $i offset ${word(i * 32)} != $offset"
          case ((offset, size), i) if word(offset) != size => s"field $i size ${word(offset)} != $size"
        }
        .toLeft(
          DepositEventFields.map { case (offset, size) => data.slice(offset + 32, offset + 32 + size) }.reduce(_ ++ _)
        )

  /** EIP-7685 `requests_hash`: sha256(sha256(r_0) ++ sha256(r_1) ++ ...), skipping entries that carry only a type byte
    * (go-ethereum `CalcRequestsHash`). Execution never produces such entries; the skip matters only for CL input.
    */
  def computeRequestsHash(requests: Seq[ByteString]): ByteString =
    val outer = java.security.MessageDigest.getInstance("SHA-256")
    requests.foreach { request =>
      if request.length > 1 then
        outer.update(java.security.MessageDigest.getInstance("SHA-256").digest(request.toArray))
    }
    ByteString(outer.digest())

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

  /** '''No error of the block.''' This node's own state could not take it (spec 018 Track C, C4b): the block was not
    * executed, or was executed and not committed, and nothing was judged. It ends a batch the way an error does and is
    * carried here for that; whoever reads it must not take it for a verdict ([[BlockExecution.BatchOutcome.of]] takes
    * it out).
    */
  final case class StateFaulted(fault: StateFault) extends BlockExecutionError:
    def describe: String = s"this node's state could not take the block: ${fault.getMessage}"

/** The chain insert lock: go-ethereum's `chainmu`, for the one writer that is not part of the node's own import paths.
  *
  * Every path that executes and commits a block (the Engine API's `newPayload`, regular sync, branch imports) holds it
  * SHARED for that block, so they keep running concurrently with each other exactly as before. An operator chain import
  * (`admin_importChain`, the offline `import` command) holds it EXCLUSIVE for each of its blocks — from the check that
  * the block extends the head to the commit that makes it the head — so no other path can execute or commit a block in
  * between. Reentrant: the exclusive holder takes the shared lock again inside [[BlockExecution]].
  *
  * Always taken outside [[BlockImportGate]], never inside it.
  */
object ChainInsertLock:
  private val lock = new java.util.concurrent.locks.ReentrantReadWriteLock()

  def shared[A](body: => A): A =
    lock.readLock().lock()
    try body
    finally lock.readLock().unlock()

  def exclusive[A](body: => A): A =
    lock.writeLock().lock()
    try body
    finally lock.writeLock().unlock()

  /** [[exclusive]], if the lock can be had within `wait`; `None` otherwise. For a caller that must not park (an actor's
    * thread: the operator's chain-weight seeds, #1654).
    */
  def tryExclusive[A](wait: scala.concurrent.duration.FiniteDuration)(body: => A): Option[A] =
    if lock.writeLock().tryLock(wait.toNanos, java.util.concurrent.TimeUnit.NANOSECONDS) then
      try Some(body)
      finally lock.writeLock().unlock()
    else None

  /** Whether this thread holds the lock [[exclusive]]ly. For assertions (`ChainWeightReconcile`'s finish, #1654). */
  def heldExclusivelyByCurrentThread: Boolean = lock.isWriteLockedByCurrentThread

/** Serialises block execution + commit per block hash, across every path that executes blocks (the Engine API's
  * `newPayload` and the regular-sync importer).
  *
  * Why: the importer's duplicate check reads the best block, which `newPayload` does not advance until
  * `forkchoiceUpdated`, so both paths executed AND committed the same block concurrently. Execution commits ABSOLUTE
  * reference counts computed from a read-through, so a second commit double-applies them (a replaced node to -1, a
  * created one to 2), and a later re-add lands a live node on 0, on the death row, pruned 64 blocks later: "Missing
  * account trie node ... locationKnown=true" (Platåberget, block 329885+).
  *
  * Per hash, not global: different blocks keep executing concurrently exactly as before, so Engine API latency is
  * unchanged; only two attempts at the SAME block meet here, and the loser waits for the winner (which is also the one
  * whose result it can reuse, instead of executing a multi-second block twice).
  *
  * A waiter reuses the winner's result only while `StagedBlockState.isBlockApplied` still says the winner's state is in
  * force; a block that was undone since (reorg, #1471) executes again. Modes without undo records never report applied,
  * so there the gate only serialises.
  */
object BlockImportGate:
  type Executed = (Seq[Receipt], Seq[ByteString], Option[BlockAccessList])

  final case class Result(receipts: Seq[Receipt], requests: Seq[ByteString], accessList: Option[BlockAccessList]):
    def asTuple: Executed = (receipts, requests, accessList)

  final private class Holder:
    val lock = new java.util.concurrent.locks.ReentrantLock()
    var users = 0

  private val holders = new java.util.concurrent.ConcurrentHashMap[ByteString, Holder]()
  private val maxRemembered = 64
  private val remembered = new java.util.LinkedHashMap[ByteString, Result](16, 0.75f, false):
    override def removeEldestEntry(eldest: java.util.Map.Entry[ByteString, Result]): Boolean = size() > maxRemembered

  def withBlock[A](blockHash: ByteString)(body: => A): A =
    val holder = holders.compute(
      blockHash,
      (_, existing) =>
        val h = if existing == null then new Holder else existing
        h.users += 1
        h
    )
    holder.lock.lock()
    try body
    finally
      holder.lock.unlock()
      holders.compute(
        blockHash,
        (_, h) =>
          h.users -= 1
          if h.users == 0 then null else h
      )

  /** The recorded result of an execution that is still in force, if any. Call under [[withBlock]]. */
  def alreadyExecuted(blockHash: ByteString, staged: StagedBlockState): Option[Result] =
    val known = remembered.synchronized(Option(remembered.get(blockHash)))
    known.filter(_ => staged.isBlockApplied(blockHash))

  /** Forget the execution recorded for `blockHash`: the block was found invalid after it was executed (a payload's
    * requests that are not those its execution derived), and what was recorded is no answer for it (spec 018 C4d part
    * 2a). Call under [[withBlock]], or where no execution of the block can be under way.
    */
  def forget(blockHash: ByteString): Unit =
    remembered.synchronized { remembered.remove(blockHash); () }

  /** Remember a committed execution for a waiter on the same hash. Call under [[withBlock]]. */
  def record(blockHash: ByteString, executed: Executed): Unit =
    remembered.synchronized { remembered.put(blockHash, Result(executed._1, executed._2, executed._3)); () }
