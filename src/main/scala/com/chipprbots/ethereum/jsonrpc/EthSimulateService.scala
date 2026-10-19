package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import cats.effect.IO

import scala.collection.mutable
import scala.util.boundary
import scala.util.boundary.break

import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.consensus.validators.std.MptListValidator
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.jsonrpc.FilterManager.TxLog
import com.chipprbots.ethereum.ledger.*
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteUtils
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.vm.AmsterdamGas
import com.chipprbots.ethereum.vm.BlockAccessRecorder
import com.chipprbots.ethereum.vm.EvmConfig

object EthSimulateService:

  // --- Request types ---
  case class BlockOverrides(
      number: Option[BigInt] = None,
      time: Option[BigInt] = None,
      gasLimit: Option[BigInt] = None,
      feeRecipient: Option[Address] = None,
      prevRandao: Option[ByteString] = None,
      baseFeePerGas: Option[BigInt] = None,
      blobBaseFee: Option[BigInt] = None,
      difficulty: Option[BigInt] = None
  )

  case class StateOverride(
      balance: Option[BigInt] = None,
      nonce: Option[BigInt] = None,
      code: Option[ByteString] = None,
      state: Option[Map[BigInt, BigInt]] = None,
      stateDiff: Option[Map[BigInt, BigInt]] = None,
      movePrecompileToAddress: Option[Address] = None
  )

  case class BlockStateCall(
      blockOverrides: Option[BlockOverrides] = None,
      stateOverrides: Option[Map[Address, StateOverride]] = None,
      // Each call is a GenericTransaction, decoded strictly by TxArgsJson.decodeForCall (#1629 S4), as go-ethereum's
      // simBlock decodes `Calls []TransactionArgs` (internal/ethapi/simulate.go).
      calls: Option[Seq[TxArgs]] = None
  )

  case class EthSimulateRequest(
      blockStateCalls: Seq[BlockStateCall],
      validation: Boolean = false,
      returnFullTransactions: Boolean = false,
      traceTransfers: Boolean = false,
      blockTag: BlockParam = BlockParam.Latest
  )

  // --- Response types ---
  case class SimulateCallResult(
      status: BigInt,
      returnData: ByteString,
      gasUsed: BigInt,
      maxUsedGas: BigInt, // Gas used before refunds
      logs: Seq[TxLog],
      error: Option[SimulateError] = None
  )

  case class SimulateError(code: Int, message: String, data: Option[ByteString] = None)

  case class SimulateBlockResult(
      header: BlockHeader,
      body: BlockBody,
      transactions: Seq[SignedTransaction],
      senders: Seq[Address], // Actual sender addresses (can't recover from zero signatures)
      calls: Seq[SimulateCallResult],
      receipts: Seq[Receipt]
  )

  case class EthSimulateResponse(blocks: Seq[SimulateBlockResult], returnFullTransactions: Boolean = false)

  // Empty trie root = keccak256(RLP("")) = keccak256(0x80)
  val EmptyTrieRoot: ByteString = ByteString(kec256(rlp.encode(rlp.RLPValue(Array.empty[Byte]))))
  // Empty withdrawals root = empty trie root (no withdrawals = empty MPT)
  val EmptyWithdrawalsRoot: ByteString = EmptyTrieRoot
  // Empty requests hash (Prague) = SHA-256 of empty input
  val EmptyRequestsHash: ByteString = ByteString(
    java.security.MessageDigest.getInstance("SHA-256").digest(Array.empty[Byte])
  )
  // Ommers hash for empty uncles list = keccak256(RLP([]))
  val EmptyOmmersHash: ByteString = ByteString(kec256(rlp.encode(rlp.RLPList())))
  // Empty bloom filter
  val EmptyBloom: ByteString = ByteString(new Array[Byte](256))
  // Empty MPT root
  val EmptyMpt: ByteString = ByteString(kec256(rlp.encode(rlp.RLPValue(Array.empty[Byte]))))
  // Transfer event topic for traceTransfers
  val TransferEventTopic: ByteString = ByteString(kec256("Transfer(address,address,uint256)".getBytes))
  val EthTransferAddress: Address = Address("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")

  val MaxBlockStateCalls = 256

  /** EIP-2681: the largest account nonce, 2^64 - 1. */
  val MaxNonce: BigInt = BigInt("18446744073709551615")

  /** The calls of one simulated block, executed: the world after them, per-call results, the transactions, their
    * senders and receipts, and three gas counters. `receiptGasUsed` is the sum of the receipts' gas (what the global
    * gas budget is charged); `executionGasUsed` and `stateGasUsed` are EIP-8037's two block dimensions, whose maximum
    * is an Amsterdam header's `gasUsed`. Before Amsterdam the execution counter equals the receipt sum and state is 0.
    */
  final private case class ExecutedCalls(
      world: InMemoryWorldStateProxy,
      callResults: Seq[SimulateCallResult],
      transactions: Seq[SignedTransaction],
      senders: Seq[Address],
      receipts: Seq[Receipt],
      receiptGasUsed: BigInt,
      executionGasUsed: BigInt,
      stateGasUsed: BigInt
  )

  /** The simulation's trie-node store: the read-only store, except that a trie update never removes the nodes it
    * replaced. The read-only store keeps a simulation's new nodes in an in-memory buffer, and an update that replaces
    * one deletes it from that buffer, so a world from before a later persist could no longer be read. The EIP-7928
    * access list compares each block access index's starting world with its ending world AFTER the index ran, so the
    * starting world must stay readable. Nodes are content-addressed: keeping them changes no root and no hash. Nothing
    * reaches the database either way; the wrapped store is the read-only one.
    */
  final private class RetainingMptStorage(underlying: MptStorage) extends MptStorage:
    override def get(nodeId: Array[Byte]): com.chipprbots.ethereum.mpt.MptNode = underlying.get(nodeId)
    override def updateNodesInStorage(
        newRoot: Option[com.chipprbots.ethereum.mpt.MptNode],
        toRemove: Seq[com.chipprbots.ethereum.mpt.MptNode]
    ): Option[com.chipprbots.ethereum.mpt.MptNode] = underlying.updateNodesInStorage(newRoot, Nil)
    override def persist(): Unit = underlying.persist()
    override def multiGetNodes(hashes: Seq[Array[Byte]]): Seq[Option[com.chipprbots.ethereum.mpt.MptNode]] =
      underlying.multiGetNodes(hashes)

  /** EIP-55 mixed-case checksum encoding, as go-ethereum's `Address.Hex()` prints addresses in error messages. */
  private[jsonrpc] def checksumAddress(address: Address): String =
    val hex = address.bytes.toArray.map(b => f"${b & 0xff}%02x").mkString
    val hash = kec256(hex.getBytes("US-ASCII")).map(b => f"${b & 0xff}%02x").mkString
    "0x" + hex
      .zip(hash)
      .map { case (c, h) => if c.isLetter && Character.digit(h, 16) >= 8 then c.toUpper else c }
      .mkString

  /** Applies execution-apis state overrides (balance, nonce, code, state, stateDiff, movePrecompileToAddress) to
    * `world` and returns it with the precompile relocations. eth_simulateV1's implementation, shared with eth_call and
    * eth_estimateGas (go-ethereum uses its one `override.StateOverride.Apply` for all three).
    */
  def applyStateOverrides(
      world: InMemoryWorldStateProxy,
      overrides: Map[Address, StateOverride],
      existingRelocations: Map[Address, Address],
      accountStartNonce: UInt256
  ): Either[JsonRpcError, (InMemoryWorldStateProxy, Map[Address, Address])] = boundary {
    var w = world
    var relocations = existingRelocations

    // First pass: validate movePrecompileToAddress and detect collisions
    val allPrecompiles = Set(
      Address(1),
      Address(2),
      Address(3),
      Address(4),
      Address(5),
      Address(6),
      Address(7),
      Address(8),
      Address(9),
      Address(0x0b),
      Address(0x0c),
      Address(0x0d),
      Address(0x0e),
      Address(0x0f),
      Address(0x10),
      Address(0x11),
      Address(0x100)
    )
    val pendingMoves = scala.collection.mutable.ArrayBuffer[(Address, Address)]()
    for (address, ov) <- overrides do
      ov.movePrecompileToAddress.foreach { targetAddr =>
        if !allPrecompiles.contains(address) then
          break(Left(JsonRpcError.LogicError(s"account ${address.toString} is not a precompile")))
        pendingMoves += (address -> targetAddr)
      }
    // Two precompiles moving to the same target is ambiguous — geth silently
    // drops the colliding moves rather than erroring (matches the
    // ethSimulate-move-two-accounts-to-same-38023 testdata expected response).
    val targetCounts = pendingMoves.groupBy(_._2).view.mapValues(_.size).toMap
    pendingMoves.foreach { case (src, tgt) =>
      if targetCounts.getOrElse(tgt, 0) <= 1 then relocations = relocations + (src -> tgt)
    }

    // Second pass: apply overrides — only modify state when something other than
    // movePrecompileToAddress is set; the precompile move is purely a routing
    // override and must not create empty accounts at precompile source addresses.
    for (address, ov) <- overrides do
      val hasStateMutation =
        ov.balance.isDefined || ov.nonce.isDefined || ov.code.isDefined ||
          ov.state.isDefined || ov.stateDiff.isDefined
      if hasStateMutation then
        var account = w.getAccount(address).getOrElse(Account.empty(accountStartNonce))

        ov.balance.foreach(bal => account = account.copy(balance = UInt256(bal)))
        ov.nonce.foreach(n => account = account.copy(nonce = UInt256(n)))

        w = w.saveAccount(address, account)

        ov.code.foreach { code =>
          w = w.saveCode(address, code)
          // Update the account's codeHash immediately (not just in cache)
          // This prevents EIP-161 from deleting the account as "empty"
          val codeHash = if code.isEmpty then Account.EmptyCodeHash else CodeHash(ByteString(kec256(code.toArray)))
          val acctWithCode = w.getAccount(address).getOrElse(Account.empty(accountStartNonce))
          w = w.saveAccount(address, acctWithCode.copy(codeHash = codeHash))
        }

        ov.state.foreach { slots =>
          // Full state replacement: clear all storage, then set specified slots
          // Persist first so any cached storage changes are committed
          w = InMemoryWorldStateProxy.persistState(w)
          // Reset storageRoot to empty and clear storage cache by delete+recreate
          val currentAcct = w.getAccount(address).getOrElse(Account.empty(accountStartNonce))
          // Delete and re-save account to clear storage cache
          w = w.deleteAccount(address)
          w = w.saveAccount(address, currentAcct.copy(storageRoot = Account.EmptyStorageRootHash))
          // Re-apply code if it was set
          if currentAcct.codeHash != Account.EmptyCodeHash then
            // Code is in the EVM code storage, re-associate it
            ov.code.foreach(code => w = w.saveCode(address, code))
          // Write the new slots on fresh (empty) storage
          val storage = w.getStorage(address)
          var s = storage
          for (key, value) <- slots do s = s.store(key, value)
          w = w.saveStorage(address, s)
        }

        ov.stateDiff.foreach { slots =>
          val storage = w.getStorage(address)
          var s = storage
          for (key, value) <- slots do s = s.store(key, value)
          w = w.saveStorage(address, s)
        }
    Right((w, relocations))
  }

class EthSimulateService(
    val blockchain: BlockchainImpl,
    val blockchainReader: BlockchainReader,
    evmCodeStorage: EvmCodeStorage,
    blockPreparator: BlockPreparator,
    val mining: Mining,
    blockchainConfig: BlockchainConfig
) extends ResolveBlock
    with Logger:

  import EthSimulateService.*

  given bcConfig: BlockchainConfig = blockchainConfig

  def ethSimulate(req: EthSimulateRequest): ServiceResponse[EthSimulateResponse] =
    IO {
      doSimulate(req)
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }

  private def doSimulate(req: EthSimulateRequest): Either[JsonRpcError, EthSimulateResponse] = boundary {
    // Validate blockStateCalls count
    if req.blockStateCalls.size > MaxBlockStateCalls then
      break(
        Left(
          JsonRpcError.SimulateClientLimitExceeded(
            s"too many block state calls: ${req.blockStateCalls.size} > $MaxBlockStateCalls"
          )
        )
      )

    // Resolve base block
    val baseBlock = resolveBlock(req.blockTag) match
      case Right(resolved) => resolved.block
      case Left(_)         =>
        // Return -32000 for block not found (not -32602)
        break(Left(JsonRpcError.LogicError(s"header not found")))

    // Pre-validate block number/timestamp ordering
    val validationResult = validateBlockOrdering(req.blockStateCalls, baseBlock.header)
    validationResult match
      case Left(err) => break(Left(err))
      case _         =>

    // Simulated block hash registry for BLOCKHASH opcode support
    val simulatedBlockHashes = mutable.Map[BigInt, ByteString]()

    // Create initial world state from base block
    val evmConfig = EvmConfig.forBlock(baseBlock.header.number.value, baseBlock.header.unixTimestamp, blockchainConfig)
    var world = InMemoryWorldStateProxy(
      evmCodeStorage = evmCodeStorage,
      mptStorage = new RetainingMptStorage(blockchain.getReadOnlyMptStorage()),
      getBlockHashByNumber =
        (n: BigInt) => simulatedBlockHashes.get(n).orElse(blockchainReader.getBlockHeaderByNumber(n).map(_.hash.value)),
      accountStartNonce = blockchainConfig.accountStartNonce,
      stateRootHash = baseBlock.header.stateRoot.value,
      noEmptyAccounts = evmConfig.noEmptyAccounts,
      ethCompatibleStorage = blockchainConfig.ethCompatibleStorage
    )

    var parentHeader = baseBlock.header
    val blockResults = mutable.ArrayBuffer[SimulateBlockResult]()
    val nonceMap = mutable.Map[Address, BigInt]() // Track nonces across blocks
    var globalAccumGas = BigInt(0) // Global gas accumulator across all blocks (geth shares the 50M pool)
    // Once a blockOverride sets feeRecipient, geth keeps it as the default for any
    // subsequent block in the same simulate request that doesn't override it
    // (including auto-generated gap blocks).
    var inheritedFeeRecipient: Option[Address] = None

    // Pre-compute total blocks including gap-filling to validate against limit
    var totalBlocks = 0
    var prevNum = baseBlock.header.number.value
    for bsc <- req.blockStateCalls do
      val targetNum = bsc.blockOverrides.flatMap(_.number).getOrElse(prevNum + 1)
      totalBlocks += (targetNum - prevNum).toInt
      prevNum = targetNum
    if totalBlocks > MaxBlockStateCalls then
      break(
        Left(
          JsonRpcError.SimulateClientLimitExceeded(
            s"too many blocks (including gaps): $totalBlocks > $MaxBlockStateCalls"
          )
        )
      )

    for (blockStateCall, _) <- req.blockStateCalls.zipWithIndex do
      val targetNumber = blockStateCall.blockOverrides.flatMap(_.number).getOrElse((parentHeader.number + 1).value)

      // Generate gap-filling empty blocks if the target number is ahead.
      // Gap blocks inherit the persistent feeRecipient if one was set earlier.
      while (parentHeader.number + 1).value < targetNumber do
        val gapOverrides = inheritedFeeRecipient.map(fr => BlockOverrides(feeRecipient = Some(fr)))
        val gapResult = buildAndFinalizeBlock(
          parentHeader,
          gapOverrides,
          None,
          Seq.empty,
          req.validation,
          req.traceTransfers,
          nonceMap,
          Map.empty,
          globalAccumGas,
          world,
          simulatedBlockHashes
        )
        gapResult match
          case Left(err) => break(Left(err))
          case Right((gapHeader, gapWorld, gapBlockResult, gapGasUsed)) =>
            blockResults += gapBlockResult
            simulatedBlockHashes(gapHeader.number.value) = gapHeader.hash.value
            parentHeader = gapHeader
            world = gapWorld
            globalAccumGas += gapGasUsed

      // Merge inherited feeRecipient into this block's overrides if not specified.
      val effectiveOverrides = blockStateCall.blockOverrides match
        case Some(ov) =>
          if ov.feeRecipient.isEmpty && inheritedFeeRecipient.isDefined then
            Some(ov.copy(feeRecipient = inheritedFeeRecipient))
          else Some(ov)
        case None =>
          inheritedFeeRecipient.map(fr => BlockOverrides(feeRecipient = Some(fr)))
      // Persist any newly set feeRecipient for future blocks.
      effectiveOverrides.flatMap(_.feeRecipient).foreach(fr => inheritedFeeRecipient = Some(fr))

      // Build the actual BSC block
      val bscResult = buildAndFinalizeBlock(
        parentHeader,
        effectiveOverrides,
        blockStateCall.stateOverrides,
        blockStateCall.calls.getOrElse(Seq.empty),
        req.validation,
        req.traceTransfers,
        nonceMap,
        Map.empty,
        globalAccumGas,
        world,
        simulatedBlockHashes
      )
      bscResult match
        case Left(err) => break(Left(err))
        case Right((bscHeader, bscWorld, bscBlockResult, bscGasUsed)) =>
          blockResults += bscBlockResult
          simulatedBlockHashes(bscHeader.number.value) = bscHeader.hash.value
          parentHeader = bscHeader
          world = bscWorld
          globalAccumGas += bscGasUsed

    Right(EthSimulateResponse(blockResults.toSeq, req.returnFullTransactions))
  }

  /** Build, execute, and finalize a single simulated block. Returns (finalHeader, world, blockResult, gasUsed), where
    * gasUsed is the sum of the calls' `gasUsed`, what the global gas budget is charged ("Each call subtracts the
    * `gasUsed` of its call result from this limit"). Before Amsterdam that equals the header's gasUsed.
    */
  private def buildAndFinalizeBlock(
      parentHeader: BlockHeader,
      blockOverrides: Option[BlockOverrides],
      stateOverrides: Option[Map[Address, StateOverride]],
      calls: Seq[TxArgs],
      validation: Boolean,
      traceTransfers: Boolean,
      nonceMap: mutable.Map[Address, BigInt],
      existingRelocations: Map[Address, Address],
      globalGasOffset: BigInt,
      initialWorld: InMemoryWorldStateProxy,
      @annotation.unused simulatedBlockHashes: mutable.Map[BigInt, ByteString]
  ): Either[JsonRpcError, (BlockHeader, InMemoryWorldStateProxy, SimulateBlockResult, BigInt)] = boundary {
    var world = initialWorld

    // Build simulated block header
    val simHeader = buildBlockHeader(parentHeader, blockOverrides, validation)
    // execution-apis BlockOverrides: a client that supports `difficulty` MUST reject it with -32602 for a block after
    // the merge (post-merge difficulty is 0). Post-merge here means the header is not the HefEmpty (pre-London PoW) shape,
    // the only one buildBlockHeader gives a non-zero difficulty.
    if blockOverrides.exists(_.difficulty.isDefined) && simHeader.extraFields != BlockHeader.HeaderExtraFields.HefEmpty
    then
      break(
        Left(
          JsonRpcError.InvalidParams(
            s"block ${simHeader.number.value}: difficulty override is not allowed after the merge"
          )
        )
      )

    // Amsterdam (ETH only; no ETC chain schedules it): the block is processed as a real Amsterdam block is, and its
    // EIP-7928 access list is built from that processing (execution-apis eth_simulateV1 notes, "Block access list").
    val amsterdam = blockchainConfig.isAmsterdamTimestamp(simHeader.unixTimestamp)
    val accessList = Option.when(amsterdam)(new BlockAccessListBuilder)

    // Before Amsterdam, unchanged: the EIP-4788 / EIP-2935 direct storage writes, then the state overrides.
    if !amsterdam then
      // Apply EIP-4788: store parent beacon block root in system contract
      if blockchainConfig.isCancunTimestamp(simHeader.unixTimestamp) then world = applyEip4788(simHeader, world)

      // Apply EIP-2935: store parent block hash in history storage
      if blockchainConfig.isPragueTimestamp(simHeader.unixTimestamp) then world = applyEip2935(simHeader, world)

    // Apply state overrides and build precompile relocations
    var precompileRelocations = existingRelocations
    stateOverrides.foreach { overrides =>
      applyStateOverrides(world, overrides, precompileRelocations) match
        case Right((newWorld, newRelocations)) =>
          world = newWorld
          precompileRelocations = newRelocations
        case Left(err) => break(Left(err))
    }

    // Amsterdam: "State overrides [...] are applied to the pre-state before the pre-execution system calls run. They
    // are not recorded in the block access list at any index." So the overridden world is index 0's starting point,
    // and the EIP-4788 / EIP-2935 calls run as the real block's system calls, recording at index 0.
    if amsterdam then
      val beforePreamble = InMemoryWorldStateProxy.persistState(world)
      val preambleReads = new BlockAccessRecorder
      val afterPreamble = SimulatedBlockSystemCalls.amsterdamPreamble(simHeader, beforePreamble, Some(preambleReads))
      accessList.foreach(_.addIndex(0L, preambleReads, beforePreamble, afterPreamble))
      world = afterPreamble

    // Execute calls (pass through any blob base fee override from blockOverrides)
    val execResult = executeCalls(
      calls,
      simHeader,
      world,
      validation,
      traceTransfers,
      nonceMap,
      precompileRelocations,
      globalGasOffset,
      blockOverrides.flatMap(_.blobBaseFee),
      accessList
    )
    val executed = execResult match
      case Left(err)     => break(Left(err))
      case Right(result) => result
    val txs = executed.transactions
    val receipts = executed.receipts

    world = executed.world

    // Amsterdam: the post-execution phase of a real block, at block access index n + 1 — the EIP-7002 / EIP-7251 /
    // EIP-8282 request system calls (there are no withdrawals: blockOverrides carries none here) — and the
    // requestsHash computed from what they and the EIP-6110 deposit logs return.
    val amsterdamRequestsHash: Option[ByteString] =
      if !amsterdam then None
      else
        val postExecutionReads = new BlockAccessRecorder
        SimulatedBlockSystemCalls.requests(simHeader, world, receipts, Some(postExecutionReads)) match
          case Left(err) => break(Left(JsonRpcError.LogicError(err)))
          case Right((afterRequests, requests)) =>
            val persisted = InMemoryWorldStateProxy.persistState(afterRequests)
            accessList.foreach(_.addIndex(calls.size + 1L, postExecutionReads, world, persisted))
            world = persisted
            Some(BlockExecution.computeRequestsHash(requests))

    // Pre-merge: pay the static block reward (5/3/2 ETH per Byzantium/Constantinople)
    // to the miner. Geth's eth_simulateV1 reflects the reward in the simulated
    // stateRoot, so simulated pre-merge blocks must too. Post-merge blocks have no
    // reward — execution layer pays nothing, withdrawals come from the CL.
    val isPoW = simHeader.extraFields match
      case HefEmpty => true
      case _: HefPostOlympia | _: HefPostShanghai | _: HefPostCancun | _: HefPostPrague | _: HefPostAmsterdam =>
        false
    if isPoW then
      val reward = blockchainConfig.monetaryPolicyConfig.firstEraBlockReward
      val byzantiumReward = blockchainConfig.monetaryPolicyConfig.firstEraReducedBlockReward
      val constantinopleReward = blockchainConfig.monetaryPolicyConfig.firstEraConstantinopleReducedBlockReward
      val n = simHeader.number.value
      val byzantium = blockchainConfig.forkBlockNumbers.byzantiumBlockNumber
      val constantinople = blockchainConfig.forkBlockNumbers.constantinopleBlockNumber
      val finalReward =
        if n >= constantinople then constantinopleReward
        else if n >= byzantium then byzantiumReward
        else reward
      val minerAddr = Address(simHeader.beneficiary)
      val acct = world.getAccount(minerAddr).getOrElse(Account.empty(blockchainConfig.accountStartNonce))
      world = world.saveAccount(minerAddr, acct.increaseBalance(UInt256(finalReward)))

    // Compute Merkle roots
    val transactionsRoot = computeTransactionsRoot(txs)
    val receiptsRoot = computeReceiptsRoot(receipts)
    val logsBloom = computeLogsBloom(receipts)

    // Persist state to compute stateRoot
    val persistedWorld = InMemoryWorldStateProxy.persistState(world)
    val stateRoot = persistedWorld.stateRootHash
    world = persistedWorld

    // Compute blob gas used from blob transactions
    val blobGasUsed = txs.foldLeft(BigInt(0)) { (acc, stx) =>
      stx.tx match
        case blob: BlobTransaction => acc + BigInt(blob.blobVersionedHashes.size) * BigInt(131072)
        case _                     => acc
    }

    // Build final header with computed roots and blob gas
    val finalExtraFields = simHeader.extraFields match
      case p: HefPostPrague => p.copy(blobGasUsed = blobGasUsed)
      // Amsterdam: the simulated block's own blobGasUsed, its EIP-7928 access list hash and its EIP-7685 requestsHash.
      case a: BlockHeader.HeaderExtraFields.HefPostAmsterdam =>
        a.copy(
          blobGasUsed = blobGasUsed,
          blockAccessListHash = accessList.fold(a.blockAccessListHash)(_.build.hash),
          requestsHash = amsterdamRequestsHash.getOrElse(a.requestsHash)
        )
      case other => other
    // EIP-8037: an Amsterdam header's gasUsed is the larger of the block's execution gas and state gas, not the receipts'
    // sum ("Gas after Amsterdam"). Before Amsterdam the execution counter IS the receipts' sum.
    val headerGasUsed =
      if amsterdam then executed.executionGasUsed.max(executed.stateGasUsed) else executed.receiptGasUsed
    val finalHeader = simHeader.copy(
      stateRoot = TrieRoot(stateRoot),
      transactionsRoot = TrieRoot(transactionsRoot),
      receiptsRoot = TrieRoot(receiptsRoot),
      logsBloom = com.chipprbots.ethereum.domain.BloomFilter(logsBloom),
      gasUsed = GasAmount(headerGasUsed),
      extraFields = finalExtraFields
    )

    // Update call results with correct block hash and number
    val blockHash = finalHeader.hash.value
    val updatedCallResults = executed.callResults.zipWithIndex.map { case (cr, _) =>
      cr.copy(logs =
        cr.logs.map(
          _.copy(
            blockHash = blockHash,
            blockNumber = finalHeader.number.value
          )
        )
      )
    }

    // Pre-Shanghai blocks have no withdrawals field in the RLP body. Encoding
    // Some(Seq.empty) adds a 1-byte extra `0xc0` to the body, which throws off
    // both the reported "size" and the block hash for legacy blocks.
    val withdrawalsOpt: Option[Seq[com.chipprbots.ethereum.domain.Withdrawal]] =
      if finalHeader.withdrawalsRoot.isDefined then Some(Seq.empty) else None
    val body = BlockBody(txs, Nil, withdrawalsOpt)
    val blockResult = SimulateBlockResult(finalHeader, body, txs, executed.senders, updatedCallResults, receipts)
    Right((finalHeader, world, blockResult, executed.receiptGasUsed))
  }

  private def validateBlockOrdering(
      blockStateCalls: Seq[BlockStateCall],
      baseHeader: BlockHeader
  ): Either[JsonRpcError, Unit] = boundary {
    var prevNumber = baseHeader.number.value
    // uint64 bit pattern -> unsigned BigInt (not BigInt(...toLong), which sign-extends timestamps
    // >= 2^63 into negative values and corrupts every ordering/gap comparison below).
    var prevTimestamp = baseHeader.unixTimestamp.toUnsignedBigInt

    for (bsc, _) <- blockStateCalls.zipWithIndex do
      val overrides = bsc.blockOverrides.getOrElse(BlockOverrides())
      val targetNumber = overrides.number.getOrElse(prevNumber + 1)

      // Validate block number override doesn't go backwards
      if targetNumber <= prevNumber then
        break(
          Left(
            JsonRpcError.SimulateBlockNumberNotIncreasing(
              s"block numbers must be in order: $targetNumber <= $prevNumber"
            )
          )
        )

      // Compute the minimum timestamp for the target block number
      // Gap blocks each take 12 seconds, so the minimum is prevTimestamp + gapBlocks * 12
      val gapBlocks = targetNumber - prevNumber // Number of blocks between prev and target
      val autoTimestamp = prevTimestamp + gapBlocks * 12
      val timestamp = overrides.time.getOrElse(autoTimestamp)

      // Explicit timestamp must be strictly greater than previous
      if timestamp <= prevTimestamp then
        break(
          Left(
            JsonRpcError.SimulateTimestampNotIncreasing(
              s"block timestamps must be in order: $timestamp <= $prevTimestamp"
            )
          )
        )

      // Gap-aware: if there are gap blocks AND an explicit timestamp, the timestamp
      // must be high enough to accommodate the gap blocks (each +12s)
      if overrides.time.isDefined && gapBlocks > 1 then
        val minTimestamp = prevTimestamp + gapBlocks * 12
        if timestamp < minTimestamp then
          break(
            Left(
              JsonRpcError.SimulateTimestampNotIncreasing(
                s"block timestamps must be in order: $timestamp <= ${minTimestamp - 12}"
              )
            )
          )

      prevNumber = targetNumber
      prevTimestamp = timestamp
    Right(())
  }

  private def buildBlockHeader(
      parentHeader: BlockHeader,
      overrides: Option[BlockOverrides],
      validation: Boolean
  ): BlockHeader =
    val ov = overrides.getOrElse(BlockOverrides())
    val number = ov.number.map(BlockNumber(_)).getOrElse(parentHeader.number + 1)
    // uint64 bit pattern -> unsigned BigInt; see validateBlockOrdering above for the sign-extension bug this avoids.
    val timestamp = ov.time.getOrElse(parentHeader.unixTimestamp.toUnsignedBigInt + 12)
    val gasLimit = ov.gasLimit.map(GasAmount(_)).getOrElse(parentHeader.gasLimit)
    val beneficiary = ov.feeRecipient.map(_.bytes).getOrElse(ByteString(new Array[Byte](20)))
    val prevRandao = ov.prevRandao.getOrElse(ByteString(new Array[Byte](32)))
    val baseFee = ov.baseFeePerGas.getOrElse(
      if !validation then BigInt(0)
      else computeNextBaseFee(parentHeader)
    )
    val parentBeaconBlockRoot = ByteString(new Array[Byte](32)) // Zero for simulated blocks

    // Determine the fork era for the header based on the block's timestamp
    // This handles both pre-merge blocks and fork boundary crossings
    val ts = Timestamp(timestamp.toLong)
    // EIP-4844: simulated block's excessBlobGas derives from parent per spec.
    val simulatedExcessBlobGas =
      val parentExcess = parentHeader.excessBlobGas.getOrElse(BigInt(0))
      val parentUsed = parentHeader.blobGasUsed.getOrElse(BigInt(0))
      val parentBaseFee = parentHeader.baseFee.getOrElse(BigInt(0))
      com.chipprbots.ethereum.consensus.engine.BlobGasUtils.expectedExcessBlobGas(
        parentExcess,
        parentUsed,
        parentBaseFee,
        ts,
        blockchainConfig
      )
    val extraFields =
      if blockchainConfig.isAmsterdamTimestamp(ts) then
        // The 23-field Amsterdam header (EIP-7928 blockAccessListHash, EIP-7843 slotNumber). A Prague-shaped header
        // here gave a simulated Amsterdam block 21 fields, so its hash was not an Amsterdam hash, and SLOTNUM read
        // `slotNumber` from a header that has none.
        //   - blockAccessListHash and requestsHash are placeholders here; buildAndFinalizeBlock replaces both with the
        //     values computed from the simulated execution.
        //   - slotNumber is "Previous block's `slotNumber` + 1" (execution-apis #881; go-ethereum `makeHeaders`), so
        //     SLOTNUM reads it too. The spec omits it when the parent has none, a 22-field header this domain type
        //     cannot carry: a simulated first Amsterdam block on a pre-Amsterdam parent keeps slot 0 instead.
        HefPostAmsterdam(
          baseFee,
          EmptyWithdrawalsRoot,
          BigInt(0),
          simulatedExcessBlobGas,
          parentBeaconBlockRoot,
          EmptyRequestsHash,
          BlockAccessList.EmptyHash,
          slotNumber = parentHeader.slotNumber.fold(BigInt(0))(_ + 1)
        )
      else if blockchainConfig.isPragueTimestamp(ts) then
        HefPostPrague(
          baseFee,
          EmptyWithdrawalsRoot,
          BigInt(0),
          simulatedExcessBlobGas,
          parentBeaconBlockRoot,
          EmptyRequestsHash
        )
      else if blockchainConfig.isCancunTimestamp(ts) then
        HefPostCancun(
          baseFee,
          EmptyWithdrawalsRoot,
          BigInt(0),
          simulatedExcessBlobGas,
          parentBeaconBlockRoot
        )
      else if blockchainConfig.isShanghaiTimestamp(ts) then HefPostShanghai(baseFee, EmptyWithdrawalsRoot)
      else if parentHeader.baseFee.isDefined then HefPostOlympia(baseFee) // Post-London but pre-Shanghai
      else HefEmpty // Pre-London

    // Pre-merge blocks have non-zero difficulty
    val difficulty: Difficulty = extraFields match
      case HefEmpty => ov.difficulty.map(Difficulty(_)).getOrElse(parentHeader.difficulty) // PoW: override or inherit
      case _        => Difficulty.Zero // Post-merge

    BlockHeader(
      parentHash = parentHeader.hash,
      ommersHash = BlockHash(EmptyOmmersHash),
      beneficiary = beneficiary,
      stateRoot = TrieRoot(ByteString(new Array[Byte](32))), // Placeholder — filled after execution
      transactionsRoot = TrieRoot(EmptyMpt),
      receiptsRoot = TrieRoot(EmptyMpt),
      logsBloom = com.chipprbots.ethereum.domain.BloomFilter(EmptyBloom),
      difficulty = difficulty,
      number = number,
      gasLimit = gasLimit,
      gasUsed = GasAmount.Zero, // Placeholder — filled after execution
      unixTimestamp = ts,
      extraData = ByteString.empty,
      mixHash = BlockHash(prevRandao),
      nonce = ByteString(new Array[Byte](8)),
      extraFields = extraFields
    )

  private def applyStateOverrides(
      world: InMemoryWorldStateProxy,
      overrides: Map[Address, StateOverride],
      existingRelocations: Map[Address, Address]
  ): Either[JsonRpcError, (InMemoryWorldStateProxy, Map[Address, Address])] =
    EthSimulateService.applyStateOverrides(world, overrides, existingRelocations, blockchainConfig.accountStartNonce)

  /** The call as the transaction the simulated block carries. go-ethereum (internal/ethapi): `sanitizeCall` fills the
    * nonce and gas (simulate.go:439-456), `CallDefaults` the rest (transaction_args.go:401-450), and the block gets
    * `call.ToTransaction(types.DynamicFeeTxType)` (simulate.go:338, transaction_args.go:512-527). The transaction's
    * type is therefore:
    *   - authorizationList -> 0x4; blobVersionedHashes -> 0x3; otherwise 0x2 at a block with a base fee, so an
    *     accessList alone rides in a 0x2 transaction, as in geth;
    *   - gasPrice -> legacy, except that gasPrice with an accessList needs `"type": "0x1"` (S1 fail-loudly rule,
    *     [[TxArgs.resolveType]]; geth would drop the list from the transaction), and gasPrice with blob fields or an
    *     authorizationList is refused rather than dropping them;
    *   - at a block without a base fee (ETH before London, ETC before Olympia) geth's CallDefaults sets gasPrice to 0,
    *     so the default is legacy, and an accessList gives 0x1 (fukuii keeps the list; geth's gasPrice default drops
    *     it);
    *   - an explicit `type` (which geth does not read) is honoured and checked against the fields present (S1 rule,
    *     kept deliberately).
    *
    * Absent fees are zero (CallDefaults). A type the simulated block's fork does not admit is refused ([[TxTypeGate]],
    * through [[CallTxBuilder.checkActive]]), as are 1559 fields where there is no base fee, before any default can
    * collide with them.
    *
    * Everything here is sanitizeCall / CallDefaults (or a fukuii request-shape rule) and so precedes geth's preCheck.
    * The transaction-validity faults geth finds in preCheck — a blob or set-code transaction without `to`, empty blob
    * hashes or authorizations — are NOT refused here: the transaction is built with them, and [[preCheck]] reports them
    * at geth's position.
    */
  private def simulatedTransaction(
      args: TxArgs,
      header: BlockHeader,
      nonce: BigInt,
      gas: BigInt
  ): Either[JsonRpcError, Transaction] =
    val defaultType = if header.baseFee.isDefined then TxArgs.DynamicFeeType else TxArgs.LegacyType
    val chainId = blockchainConfig.chainId.value
    for
      // CallDefaults (transaction_args.go:403-412): fee families, then chainId; -32000 as geth returns them
      _ <- Either.cond(
        !(args.gasPrice.isDefined && args.hasDynamicFee),
        (),
        TxArgsError.GasPriceAndDynamicFee.toLogicError
      )
      _ <- args.chainId match
        case Some(have) if have.value != chainId => Left(TxArgsError.ChainIdMismatch(have.value, chainId).toLogicError)
        case _                                   => Right(())
      _ <- CallTxBuilder.checkSetCodeWithBlobs(args)
      _ <- CallTxBuilder.checkLondon(args, header)
      txType <- args.resolveType(defaultType, checkCreate = false).left.map(_.toJsonRpcError)
      _ <- CallTxBuilder.checkActive(args.copy(txType = Some(txType)), header)
    yield
      val to = args.to
      val value = args.value.getOrElse(BigInt(0))
      val data = args.callData
      val gasLimit = GasAmount(gas)
      val accessList = args.accessList.getOrElse(Nil)
      val gasPrice = GasPrice(args.gasPrice.map(_.value).getOrElse(BigInt(0)))
      val feeCap = args.maxFeePerGas.getOrElse(BigInt(0))
      val tip = args.maxPriorityFeePerGas.getOrElse(BigInt(0))
      txType match
        case TxArgs.LegacyType => LegacyTransaction(nonce, gasPrice, gasLimit, to, value, data)
        case TxArgs.AccessListType =>
          TransactionWithAccessList(chainId, nonce, gasPrice, gasLimit, to, value, data, accessList)
        case TxArgs.DynamicFeeType =>
          TransactionWithDynamicFee(chainId, nonce, tip, feeCap, gasLimit, to, value, data, accessList)
        case TxArgs.BlobType =>
          // ToTransaction carries a blob fee cap only in a blob transaction (CallDefaults: 0 when absent)
          val blobFeeCap = args.maxFeePerBlobGas.getOrElse(BigInt(0))
          val hashes = args.blobVersionedHashes.getOrElse(Nil)
          BlobTransaction(chainId, nonce, tip, feeCap, gasLimit, to, value, data, accessList, blobFeeCap, hashes)
        case _ => // SetCodeType: resolveType yields only supported types
          val auths = args.authorizationList.getOrElse(Nil)
          SetCodeTransaction(chainId, nonce, tip, feeCap, gasLimit, to, value, data, accessList, auths)

  /** go-ethereum's checks between building the message and running it, in geth's order: `preCheck`
    * (core/state_transition.go:544-650) — nonce, London fee caps, blob hashes, blob fee cap, set-code, init code size,
    * then `buyGas`'s balance check — and then `execute`'s intrinsic-gas check (state_transition.go:677-682).
    *
    * The simulate message sets `SkipTransactionChecks: true` (transaction_args.go:506), so preCheck's EIP-7825 gas
    * limit cap and sender-is-EOA check do not run here, and `SkipNonceChecks` is `!validation` (simulate.go:347).
    * Without validation the vm runs with `NoBaseFee` (simulate.go:304): the London checks are skipped when both fee
    * caps are zero, the blob fee check when the blob fee cap is zero.
    *
    * Codes per internal/ethapi/errors.go:120-151 (`txValidationError`); messages are geth's, wrapped as
    * applyMessageWithEVM wraps them (api.go:833).
    */
  private def preCheck(
      tx: Transaction,
      sender: Address,
      nonceGiven: Boolean,
      header: BlockHeader,
      world: InMemoryWorldStateProxy,
      validation: Boolean,
      blobBaseFeeOverride: Option[BigInt]
  ): Either[JsonRpcError, Unit] =
    val gas = tx.gasLimit.value
    val address = checksumAddress(sender)
    def wrap(msg: String) = s"err: $msg (supplied gas $gas)"
    def internal(msg: String) = Left(JsonRpcError(JsonRpcError.InternalError.code, wrap(msg), None))
    val (feeCap, tipCap) = tx match
      case t: TransactionWithDynamicFee => (t.maxFeePerGas, t.maxPriorityFeePerGas)
      case t: BlobTransaction           => (t.maxFeePerGas, t.maxPriorityFeePerGas)
      case t: SetCodeTransaction        => (t.maxFeePerGas, t.maxPriorityFeePerGas)
      case t                            => (t.gasPrice.value, t.gasPrice.value)
    val (blobGas, blobFeeCap) = tx match
      case b: BlobTransaction => (BigInt(b.blobVersionedHashes.size) * StxLedger.GasPerBlob, b.maxFeePerBlobGas)
      case _                  => (BigInt(0), BigInt(0))
    val account = world.getAccount(sender)
    val stateNonce = account.map(_.nonce.toBigInt).getOrElse(BigInt(0))
    val balance = account.map(_.balance.toBigInt).getOrElse(BigInt(0))
    val evmConfig = EvmConfig.forBlock(header.number.value, header.unixTimestamp, blockchainConfig)
    for
      // nonce (state_transition.go:547-559), only with validation; -38011 / -38010 / -32603 (errors.go:125-128)
      _ <-
        if !validation then Right(())
        else if nonceGiven && stateNonce < tx.nonce then
          Left(
            JsonRpcError.SimulateNonceTooHigh(
              wrap(s"nonce too high: address $address, tx: ${tx.nonce} state: $stateNonce")
            )
          )
        else if nonceGiven && stateNonce > tx.nonce then
          Left(
            JsonRpcError.SimulateNonceTooLow(
              wrap(s"nonce too low: address $address, tx: ${tx.nonce} state: $stateNonce")
            )
          )
        else if stateNonce == MaxNonce then internal(s"nonce has max value: address $address, nonce: $MaxNonce")
        else Right(())
      // London (state_transition.go:574-588): tip above cap -32602, cap below base fee -38012 (errors.go:135-138)
      _ <- header.baseFee match
        case Some(baseFee) if validation || feeCap != 0 || tipCap != 0 =>
          if tipCap > feeCap then
            Left(
              JsonRpcError.InvalidParams(
                wrap(
                  s"max priority fee per gas higher than max fee per gas: address $address, " +
                    s"maxPriorityFeePerGas: $tipCap, maxFeePerGas: $feeCap"
                )
              )
            )
          else if feeCap < baseFee then
            Left(
              JsonRpcError.SimulateBaseFeeTooLow(
                wrap(
                  s"max fee per gas less than block base fee: address $address, maxFeePerGas: $feeCap, baseFee: $baseFee"
                )
              )
            )
          else Right(())
        case _ => Right(())
      // blob hashes (state_transition.go:591-609), -32603
      _ <- tx match
        case b: BlobTransaction =>
          val badVersion = b.blobVersionedHashes.zipWithIndex.collectFirst {
            case (h, i) if h.value.length != 32 || h.value(0) != StxLedger.BlobCommitmentVersionKzg => i
          }
          if b.receivingAddress.isEmpty then internal("blob transaction of type create")
          else if b.blobVersionedHashes.isEmpty then internal("blob transaction missing blob hashes")
          else if blockchainConfig.isOsakaTimestamp(header.unixTimestamp) &&
            b.blobVersionedHashes.size > StxLedger.BlobTxMaxBlobs
          then internal("blob transaction has too many blobs")
          else badVersion.fold(Right(()))(i => internal(s"blob $i has invalid hash version"))
        case _ => Right(())
      // blob fee cap (state_transition.go:611-623), -32603
      _ <-
        if blobGas == 0 || !blockchainConfig.isCancunTimestamp(header.unixTimestamp) then Right(())
        else if !validation && blobFeeCap == 0 then Right(())
        else
          val blobBaseFee = blobBaseFeeOverride.getOrElse(
            com.chipprbots.ethereum.consensus.engine.BlobGasUtils
              .getBlobGasPrice(header.excessBlobGas.getOrElse(BigInt(0)), header.unixTimestamp, blockchainConfig)
          )
          if blobFeeCap < blobBaseFee then
            internal(
              s"max fee per blob gas less than block blob gas fee: address $address blobGasFeeCap: $blobFeeCap, " +
                s"blobBaseFee: $blobBaseFee"
            )
          else Right(())
      // set-code (state_transition.go:625-633), -32603
      _ <- tx match
        case s: SetCodeTransaction if s.receivingAddress.isEmpty =>
          internal(s"EIP-7702 transaction cannot be used to create contract (sender $address)")
        case s: SetCodeTransaction if s.authorizationList.isEmpty =>
          internal(s"EIP-7702 transaction with empty auth list (sender $address)")
        case _ => Right(())
      // EIP-3860 init code size (state_transition.go:635-639, core/vm/common.go:29-40), -38025 (errors.go:145)
      _ <- evmConfig.maxInitCodeSize match
        case Some(max) if tx.isContractInit && evmConfig.eip3860Enabled && tx.payload.size > max =>
          Left(
            JsonRpcError.SimulateMaxInitCodeSizeExceeded(
              wrap(s"max initcode size exceeded: code size ${tx.payload.size} limit $max")
            )
          )
        case _ => Right(())
      // buyGas: gas * fee cap + value + blob gas * blob fee cap, -38014 (errors.go:139-140)
      _ <-
        val want = gas * feeCap + tx.value + blobGas * blobFeeCap
        if balance < want then
          Left(
            JsonRpcError.SimulateInsufficientFunds(
              wrap(s"insufficient funds for gas * price + value: address $address have $balance want $want")
            )
          )
        else Right(())
      // execute: intrinsic gas (state_transition.go:677-682), -38013 (errors.go:141-142)
      _ <-
        val intrinsic = evmConfig.calcTransactionIntrinsicGas(
          tx.payload,
          tx.isContractInit,
          Transaction.accessList(tx),
          tx match
            case s: SetCodeTransaction => s.authorizationList.size
            case _                     => 0
          ,
          tx.receivingAddress,
          UInt256(tx.value),
          sender
        )
        if gas < intrinsic then
          Left(JsonRpcError.SimulateIntrinsicGasTooLow(wrap(s"intrinsic gas too low: have $gas, want $intrinsic")))
        else Right(())
    yield ()

  private def executeCalls(
      calls: Seq[TxArgs],
      blockHeader: BlockHeader,
      initialWorld: InMemoryWorldStateProxy,
      validation: Boolean,
      traceTransfers: Boolean,
      nonceMap: mutable.Map[Address, BigInt],
      precompileRelocations: Map[Address, Address],
      globalGasOffset: BigInt,
      blobBaseFeeOverride: Option[BigInt],
      // EIP-7928: the block's access-list builder, on an Amsterdam block only. Call i records at index i + 1.
      accessList: Option[BlockAccessListBuilder]
  ): Either[JsonRpcError, ExecutedCalls] = boundary {
    var world = initialWorld
    val callResults = mutable.ArrayBuffer[SimulateCallResult]()
    val txs = mutable.ArrayBuffer[SignedTransaction]()
    val senders = mutable.ArrayBuffer[Address]()
    val receipts = mutable.ArrayBuffer[Receipt]()
    var accumGas = BigInt(0)
    // EIP-8037's two block dimensions, before refunds; failed and reverted calls count too.
    var accumExecutionGas = BigInt(0)
    var accumStateGas = BigInt(0)
    val amsterdam = blockchainConfig.isAmsterdamTimestamp(blockHeader.unixTimestamp)
    // EIP-7708: from Amsterdam the protocol itself logs every ether transfer (from 0xff…fe, into the receipts), so
    // `traceTransfers` adds no 0xeeee… logs and "the flag has no effect" (execution-apis #881; go-ethereum
    // `simulate.go`). Blocks before Amsterdam in the same simulation keep the synthetic logs.
    val traceTransfersInBlock = traceTransfers && !amsterdam
    // Block-level logIndex counter — geth numbers logs globally across all calls
    // in the block, including synthetic Transfer logs emitted for traceTransfers.
    var globalLogIndex = 0

    for (call, callIdx) <- calls.zipWithIndex do
      val sender = call.from.getOrElse(Address(0))

      // Resolve nonce (go-ethereum sanitizeCall, simulate.go:439-443: the state nonce when absent)
      val senderNonce = call.nonce.getOrElse {
        nonceMap.getOrElseUpdate(sender, world.getAccount(sender).map(_.nonce.toBigInt).getOrElse(BigInt(0)))
      }

      // Build transaction — default gas = min of remaining global 50M pool and remaining block gas
      val DefaultSimGasLimit = BigInt(50000000)
      val remainingGlobalGas = DefaultSimGasLimit - globalGasOffset - accumGas
      val remainingBlockGas =
        if !amsterdam then blockHeader.gasLimit.value - accumGas
        else
          // "Gas after Amsterdam": the block's gas used so far is max(execution, state), not the receipts' sum.
          // Without validation the default is the smaller remaining dimension; with it, go-ethereum
          // `GasPool.Available`: the remaining state gas when at least TX_MAX_GAS_LIMIT of execution gas remains.
          val remainingExecution = blockHeader.gasLimit.value - accumExecutionGas
          val remainingState = blockHeader.gasLimit.value - accumStateGas
          if validation && remainingExecution >= AmsterdamGas.TxMaxGasLimit then remainingState
          else remainingExecution.min(remainingState)
      val gasLimit = call.gas.map(_.value).getOrElse(remainingGlobalGas.min(remainingBlockGas).max(BigInt(0)))
      val value = call.value.getOrElse(BigInt(0))
      val toAddr = call.to

      // sanitizeCall + CallDefaults + ToTransaction(DynamicFeeTxType) (simulate.go:333-338): the call as a typed
      // transaction. Its type is what the block, the receipt and returnFullTransactions report.
      val tx: Transaction = simulatedTransaction(call, blockHeader, senderNonce, gasLimit) match
        case Left(err) => break(Left(err))
        case Right(t)  => t

      // EIP-2681 "Nonce limit" without validation (simulate.go:342-346, execution-apis #881, go-ethereum #35842): a
      // sender whose account nonce is 2^64 - 1 ends the simulation; the nonce never wraps to zero. With validation,
      // preCheck's nonce block reports it.
      val stateNonce = world.getAccount(sender).map(_.nonce.toBigInt).getOrElse(BigInt(0))
      if !validation && stateNonce == MaxNonce then
        break(
          Left(
            JsonRpcError(
              JsonRpcError.InternalError.code,
              s"nonce has max value: address ${checksumAddress(sender)}, nonce: $MaxNonce",
              None
            )
          )
        )

      // go-ethereum preCheck, buyGas and the intrinsic-gas check, in geth's order (see preCheck)
      preCheck(tx, sender, call.nonce.isDefined, blockHeader, world, validation, blobBaseFeeOverride) match
        case Left(err) => break(Left(err))
        case Right(_)  => ()

      val fakeSignature = com.chipprbots.ethereum.crypto.ECDSASignature(BigInt(0), BigInt(0), BigInt(0))
      val stx = SignedTransaction(tx, fakeSignature)

      // EIP-7928: the state this call's block access index starts from.
      val worldBeforeCall = world

      // Ensure the sender account exists. Its balance is not topped up: preCheck has checked it as geth's buyGas does,
      // with or without validation. The call's nonce only affects the transaction hash/encoding; the account's nonce
      // is managed by the EVM's incrementNonce during execution (geth doesn't set it either).
      val senderAccount = world.getAccount(sender).getOrElse(Account.empty(blockchainConfig.accountStartNonce))
      world = world.saveAccount(sender, senderAccount)

      // Execute transaction
      val accessRecorder = accessList.map(_ => new BlockAccessRecorder)
      val TxResult(newWorld, gasUsed, logs, returnData, vmError, txExecutionGas, txStateGas) =
        blockPreparator.executeTransactionForSimulation(
          stx,
          sender,
          blockHeader,
          world,
          precompileRelocations,
          traceTransfersInBlock,
          blobBaseFeeOverride,
          // "With `validation` disabled, a call can use its whole `gasLimit` as execution gas" (EIP-8037's
          // TX_MAX_GAS_LIMIT cap does not apply). A no-op before Amsterdam, where there is no cap to lift.
          skipExecutionGasCap = !validation,
          accessRecorder = accessRecorder
        )
      for
        builder <- accessList
        recorder <- accessRecorder
      do builder.addIndex(callIdx + 1L, recorder, worldBeforeCall, newWorld)

      world = newWorld

      // Wrap nonce at uint64 boundary if it overflowed (geth uses uint64 for nonces)
      val MaxUint64Plus1 = BigInt("18446744073709551616") // 2^64
      world.getAccount(sender).foreach { acct =>
        if acct.nonce.toBigInt >= MaxUint64Plus1 then
          world = world.saveAccount(sender, acct.copy(nonce = UInt256(acct.nonce.toBigInt % MaxUint64Plus1)))
      }

      // Update nonce tracking
      nonceMap(sender) = (senderNonce + 1) % MaxUint64Plus1

      // EVM logs include synthetic Transfer logs (address 0xeeee...eeee) emitted by
      // CALL/SELFDESTRUCT when traceTransfers is on. Those are API-only and must NOT
      // appear in the receipt (they would change logsBloom + receiptsRoot).
      val realLogs = if traceTransfersInBlock then logs.filter(_.loggerAddress != EthTransferAddress) else logs

      // Build receipt — receipts use real logs only
      val outcome = if vmError.isDefined then FailureOutcome else SuccessOutcome
      val legacyReceipt = LegacyReceipt(
        postTransactionStateHash = outcome,
        cumulativeGasUsed = accumGas + gasUsed,
        logsBloomFilter =
          com.chipprbots.ethereum.domain.BloomFilter(com.chipprbots.ethereum.ledger.BloomFilter.create(realLogs)),
        logs = realLogs
      )
      // The receipt of the call's own type, as block execution builds it (BlockPreparator) and go-ethereum's
      // core.MakeReceipt does (simulate.go): an EIP-2930 or EIP-7702 call no longer gets a legacy receipt.
      val receipt: Receipt = tx match
        case _: LegacyTransaction         => legacyReceipt
        case _: TransactionWithAccessList => Type01Receipt(legacyReceipt)
        case _: TransactionWithDynamicFee => Type02Receipt(legacyReceipt)
        case _: BlobTransaction           => Type03Receipt(legacyReceipt)
        case _: SetCodeTransaction        => Type04Receipt(legacyReceipt)

      accumGas += gasUsed
      accumExecutionGas += txExecutionGas
      accumStateGas += txStateGas
      txs += stx
      senders += sender
      receipts += receipt

      // Build per-call result.
      //
      // For traceTransfers: prepend a synthetic top-level Transfer log so the API
      // sees logIndex first for the outer (tx-initiated) transfer, then later for
      // the inner CALL-emitted ones. This mirrors geth: the top-level value
      // transfer is the first synthetic event, intra-call transfers come after in
      // execution order. The EVM's CALL opcode already emits the inner transfer
      // logs as part of `logs`.
      val topLevelTransferLog =
        if traceTransfersInBlock && value > 0 && vmError.isEmpty then
          Some(
            com.chipprbots.ethereum.domain.TxLogEntry(
              loggerAddress = EthTransferAddress,
              logTopics = Seq(
                TransferEventTopic,
                ByteString(new Array[Byte](12) ++ sender.bytes.toArray),
                ByteString(new Array[Byte](12) ++ toAddr.map(_.bytes.toArray).getOrElse(new Array[Byte](20)))
              ),
              data =
                val raw = UInt256(value).bytes
                ByteString(new Array[Byte](32 - raw.length) ++ raw.toArray)
            )
          )
        else None

      val apiLogEntries = topLevelTransferLog.toSeq ++ logs

      // Geth's logIndex counter advances by ONE per call that *would have* emitted
      // a top-level synthetic transfer log (i.e., calls with value > 0 when
      // traceTransfers is on), even when the call ends in error and produces no
      // visible logs. This matches the observed behavior in the testdata: a
      // sequence of failed value-transfers + one successful value-transfer puts
      // the synthetic log at logIndex == failed-call-count. Real EVM logs from
      // each call still increment the counter once per emitted entry.
      val phantomBumps =
        if traceTransfersInBlock && value > 0 && vmError.isDefined then 1 else 0

      val txLogs = apiLogEntries.map { txLog =>
        val l = TxLog(
          logIndex = globalLogIndex,
          transactionIndex = callIdx,
          transactionHash = stx.hash.value,
          blockHash = ByteString(new Array[Byte](32)), // Placeholder — updated after header finalized
          blockNumber = blockHeader.number.value,
          address = txLog.loggerAddress,
          data = txLog.data,
          topics = txLog.logTopics,
          // uint64 bit pattern -> unsigned BigInt; see BlockResponse.scala for the sign-extension bug this avoids.
          blockTimestamp = Some(blockHeader.unixTimestamp.toUnsignedBigInt)
        )
        globalLogIndex += 1
        l
      }
      globalLogIndex += phantomBumps

      val allLogs = txLogs

      val callResult = vmError match
        case Some(com.chipprbots.ethereum.vm.RevertOccurs) =>
          // Geth wire format for revert:
          //   - call.returnData = "0x" (always empty; payload moves into error.data)
          //   - error.data      = the raw revert payload (always present, even "0x")
          //   - error.message   = "execution reverted" + ": <decoded>" when payload
          //                       starts with the Error(string) selector 0x08c379a0.
          val decoded = decodeErrorString(returnData)
          val msg = decoded.fold("execution reverted")(s => s"execution reverted: $s")
          SimulateCallResult(
            status = BigInt(0),
            returnData = ByteString.empty,
            gasUsed = gasUsed,
            maxUsedGas = gasUsed,
            logs = Seq.empty,
            error = Some(SimulateError(3, msg, Some(returnData)))
          )
        case Some(err) =>
          // Map VM error names to geth-compatible lowercase messages
          val errMsg = err match
            case com.chipprbots.ethereum.vm.OutOfGas            => "out of gas"
            case com.chipprbots.ethereum.vm.InvalidOpCode(code) => s"invalid opcode: 0x${code.toInt.toHexString}"
            case other                                          => other.toString.toLowerCase
          SimulateCallResult(
            status = BigInt(0),
            returnData = returnData,
            gasUsed = gasUsed,
            maxUsedGas = gasUsed,
            logs = Seq.empty,
            error = Some(SimulateError(-32015, errMsg))
          )
        case None =>
          SimulateCallResult(
            status = BigInt(1),
            returnData = returnData,
            gasUsed = gasUsed,
            maxUsedGas = gasUsed,
            logs = allLogs
          )
      callResults += callResult

    Right(
      ExecutedCalls(
        world,
        callResults.toSeq,
        txs.toSeq,
        senders.toSeq,
        receipts.toSeq,
        receiptGasUsed = accumGas,
        executionGasUsed = accumExecutionGas,
        stateGasUsed = accumStateGas
      )
    )
  }

  /** EIP-4788: Store the parent beacon block root in the beacon root system contract */
  private def applyEip4788(
      blockHeader: BlockHeader,
      world: InMemoryWorldStateProxy
  ): InMemoryWorldStateProxy =
    import com.chipprbots.ethereum.ledger.BlockExecution.*
    blockHeader.parentBeaconBlockRoot match
      case Some(beaconRoot) =>
        // Must match BlockExecution.applyEip4788 exactly, or eth_simulateV1 becomes an
        // oracle that disagrees with real execution. See the note there.
        val timestamp = blockHeader.unixTimestamp.toUInt256
        val timestampIdx = timestamp.mod(UInt256(BeaconRootHistoryBufferLength))
        val rootIdx = timestampIdx + UInt256(BeaconRootHistoryBufferLength)
        val account = world
          .getAccount(BeaconRootContractAddress)
          .getOrElse(Account.empty(blockchainConfig.accountStartNonce))
        val w1 =
          if !world.getAccount(BeaconRootContractAddress).isDefined then
            world.saveAccount(BeaconRootContractAddress, account)
          else world
        val storage = w1.getStorage(BeaconRootContractAddress)
        val s1 = storage.store(timestampIdx.toBigInt, timestamp.toBigInt)
        val s2 = s1.store(rootIdx.toBigInt, UInt256(beaconRoot.value).toBigInt)
        w1.saveStorage(BeaconRootContractAddress, s2)
      case None => world

  /** EIP-2935: Store parent block hash in history storage contract */
  private def applyEip2935(
      blockHeader: BlockHeader,
      world: InMemoryWorldStateProxy
  ): InMemoryWorldStateProxy =
    import com.chipprbots.ethereum.ledger.BlockExecution.*
    val blockNumber = blockHeader.number.value
    // Deploy history storage contract if not already deployed
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
    val parentHashValue = UInt256(blockHeader.parentHash.value)
    val slot = (blockNumber - 1) % HistoryServeWindow
    val storage = w1.getStorage(HistoryStorageAddress)
    val updatedStorage = storage.store(slot, parentHashValue.toBigInt)
    w1.saveStorage(HistoryStorageAddress, updatedStorage)

  /** EIP-1559: Compute the base fee for the next block */
  private def computeNextBaseFee(parentHeader: BlockHeader): BigInt =
    val parentBaseFee = parentHeader.baseFee.getOrElse(BigInt(0))
    if parentBaseFee == 0 then return BigInt(0)
    val elasticityMultiplier = 2
    val baseFeeChangeDenominator = 8
    val parentGasTarget = parentHeader.gasLimit / elasticityMultiplier
    if parentGasTarget == GasAmount.Zero then return parentBaseFee
    if parentHeader.gasUsed == parentGasTarget then parentBaseFee
    else if parentHeader.gasUsed > parentGasTarget then
      val gasUsedDelta = (parentHeader.gasUsed - parentGasTarget).value
      val baseFeePerGasDelta = (parentBaseFee * gasUsedDelta / parentGasTarget.value / baseFeeChangeDenominator).max(1)
      parentBaseFee + baseFeePerGasDelta
    else
      val gasUsedDelta = (parentGasTarget - parentHeader.gasUsed).value
      val baseFeePerGasDelta = parentBaseFee * gasUsedDelta / parentGasTarget.value / baseFeeChangeDenominator
      (parentBaseFee - baseFeePerGasDelta).max(0)

  private def computeTransactionsRoot(txs: Seq[SignedTransaction]): ByteString =
    if txs.isEmpty then EmptyMpt
    else
      val stateStorage = StateStorage.getReadOnlyStorage(EphemDataSource())
      val trie = MerklePatriciaTrie[Int, SignedTransaction](source = stateStorage)(
        MptListValidator.intByteArraySerializable,
        SignedTransaction.byteArraySerializable
      )
      ByteString(txs.zipWithIndex.foldLeft(trie)((t, r) => t.put(r._2, r._1)).getRootHash)

  private def computeReceiptsRoot(receipts: Seq[Receipt]): ByteString =
    if receipts.isEmpty then EmptyMpt
    else
      val stateStorage = StateStorage.getReadOnlyStorage(EphemDataSource())
      val trie = MerklePatriciaTrie[Int, Receipt](source = stateStorage)(
        MptListValidator.intByteArraySerializable,
        Receipt.byteArraySerializable
      )
      ByteString(receipts.zipWithIndex.foldLeft(trie)((t, r) => t.put(r._2, r._1)).getRootHash)

  private def computeLogsBloom(receipts: Seq[Receipt]): ByteString =
    if receipts.isEmpty then EmptyBloom
    else
      val blooms = receipts.map(_.logsBloomFilter.toArray)
      ByteString(ByteUtils.or(EmptyBloom.toArray +: blooms*))

  /** ABI-decode the string payload of an `Error(string)` revert (selector 0x08c379a0). Returns None for any other
    * revert payload (custom errors, raw bytes, etc.) so the caller falls back to the bare "execution reverted" message
    * — matching geth's behavior.
    */
  private def decodeErrorString(returnData: ByteString): Option[String] =
    if returnData.length < 4 + 32 + 32 then return None
    val bytes = returnData.toArray
    val selector = (bytes(0) & 0xff, bytes(1) & 0xff, bytes(2) & 0xff, bytes(3) & 0xff)
    if selector != (0x08, 0xc3, 0x79, 0xa0) then return None
    // ABI: head[0] = offset (always 0x20 for a single string arg)
    // head[1] = string length (right-aligned uint256)
    // head[2..] = string bytes (padded to 32-byte boundary)
    val payload = bytes.drop(4)
    val offset = BigInt(1, payload.slice(0, 32))
    if offset != 32 then return None
    val length = BigInt(1, payload.slice(32, 64)).toInt
    if length < 0 || 64 + length > payload.length then return None
    scala.util.Try(new String(payload.slice(64, 64 + length), "UTF-8")).toOption
