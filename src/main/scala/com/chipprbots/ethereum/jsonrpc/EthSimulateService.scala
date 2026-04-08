package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import cats.effect.IO

import scala.collection.mutable

import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.consensus.validators.std.MptListValidator
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields._
import com.chipprbots.ethereum.jsonrpc.FilterManager.TxLog
import com.chipprbots.ethereum.ledger._
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteUtils
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.vm.EvmConfig

object EthSimulateService {

  // --- Request types ---
  case class BlockOverrides(
      number: Option[BigInt] = None,
      time: Option[BigInt] = None,
      gasLimit: Option[BigInt] = None,
      feeRecipient: Option[Address] = None,
      prevRandao: Option[ByteString] = None,
      baseFeePerGas: Option[BigInt] = None,
      blobBaseFee: Option[BigInt] = None
  )

  case class StateOverride(
      balance: Option[BigInt] = None,
      nonce: Option[BigInt] = None,
      code: Option[ByteString] = None,
      state: Option[Map[BigInt, BigInt]] = None,
      stateDiff: Option[Map[BigInt, BigInt]] = None,
      movePrecompileToAddress: Option[Address] = None
  )

  case class SimulateCall(
      from: Option[Address] = None,
      to: Option[Address] = None,
      gas: Option[BigInt] = None,
      value: Option[BigInt] = None,
      input: Option[ByteString] = None,
      nonce: Option[BigInt] = None,
      maxFeePerGas: Option[BigInt] = None,
      maxPriorityFeePerGas: Option[BigInt] = None,
      gasPrice: Option[BigInt] = None,
      maxFeePerBlobGas: Option[BigInt] = None,
      blobVersionedHashes: Option[Seq[ByteString]] = None,
      accessList: Option[List[AccessListItem]] = None,
      `type`: Option[BigInt] = None
  )

  case class BlockStateCall(
      blockOverrides: Option[BlockOverrides] = None,
      stateOverrides: Option[Map[Address, StateOverride]] = None,
      calls: Option[Seq[SimulateCall]] = None
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
    java.security.MessageDigest.getInstance("SHA-256").digest(Array.empty[Byte]))
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
}

class EthSimulateService(
    val blockchain: BlockchainImpl,
    val blockchainReader: BlockchainReader,
    evmCodeStorage: EvmCodeStorage,
    blockPreparator: BlockPreparator,
    val mining: Mining,
    blockchainConfig: BlockchainConfig
) extends ResolveBlock
    with Logger {

  import EthSimulateService._

  implicit val bcConfig: BlockchainConfig = blockchainConfig

  def ethSimulate(req: EthSimulateRequest): ServiceResponse[EthSimulateResponse] =
    IO {
      doSimulate(req)
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }

  private def doSimulate(req: EthSimulateRequest): Either[JsonRpcError, EthSimulateResponse] = {
    // Validate blockStateCalls count
    if (req.blockStateCalls.size > MaxBlockStateCalls) {
      return Left(JsonRpcError.SimulateClientLimitExceeded(
        s"too many block state calls: ${req.blockStateCalls.size} > $MaxBlockStateCalls"))
    }

    // Resolve base block
    val baseBlock = resolveBlock(req.blockTag) match {
      case Right(resolved) => resolved.block
      case Left(err) => return Left(err)
    }

    // Pre-validate block number/timestamp ordering
    val validationResult = validateBlockOrdering(req.blockStateCalls, baseBlock.header)
    if (validationResult.isLeft) return validationResult.map(_ => null)

    // Create initial world state from base block
    val evmConfig = EvmConfig.forBlock(baseBlock.header.number, baseBlock.header.unixTimestamp, blockchainConfig)
    var world = InMemoryWorldStateProxy(
      evmCodeStorage = evmCodeStorage,
      mptStorage = blockchain.getReadOnlyMptStorage(),
      getBlockHashByNumber = (n: BigInt) => blockchainReader.getBlockHeaderByNumber(n).map(_.hash),
      accountStartNonce = blockchainConfig.accountStartNonce,
      stateRootHash = baseBlock.header.stateRoot,
      noEmptyAccounts = evmConfig.noEmptyAccounts,
      ethCompatibleStorage = blockchainConfig.ethCompatibleStorage
    )

    var parentHeader = baseBlock.header
    val blockResults = mutable.ArrayBuffer[SimulateBlockResult]()
    val nonceMap = mutable.Map[Address, BigInt]() // Track nonces across blocks

    for ((blockStateCall, blockIdx) <- req.blockStateCalls.zipWithIndex) {
      // Build simulated block header
      val simHeader = buildBlockHeader(parentHeader, blockStateCall.blockOverrides, req.validation)

      // Apply EIP-4788: store parent beacon block root in system contract
      if (blockchainConfig.isCancunTimestamp(simHeader.unixTimestamp)) {
        world = applyEip4788(simHeader, world)
      }

      // Apply EIP-2935: store parent block hash in history storage
      if (blockchainConfig.isPragueTimestamp(simHeader.unixTimestamp)) {
        world = applyEip2935(simHeader, world)
      }

      // Apply state overrides
      blockStateCall.stateOverrides.foreach { overrides =>
        world = applyStateOverrides(world, overrides)
      }

      // Execute calls
      val calls = blockStateCall.calls.getOrElse(Seq.empty)
      val execResult = executeCalls(calls, simHeader, world, req.validation, req.traceTransfers, nonceMap)
      execResult match {
        case Left(err) => return Left(err)
        case _ =>
      }
      val (newWorld, callResults, txs, receipts, gasUsed) = execResult.toOption.get

      world = newWorld

      // Compute Merkle roots
      val transactionsRoot = computeTransactionsRoot(txs)
      val receiptsRoot = computeReceiptsRoot(receipts)
      val logsBloom = computeLogsBloom(receipts)

      // Persist state to compute stateRoot
      val persistedWorld = InMemoryWorldStateProxy.persistState(world)
      val stateRoot = persistedWorld.stateRootHash
      world = persistedWorld

      // Build final header with computed roots
      val finalHeader = simHeader.copy(
        stateRoot = stateRoot,
        transactionsRoot = transactionsRoot,
        receiptsRoot = receiptsRoot,
        logsBloom = logsBloom,
        gasUsed = gasUsed
      )

      // Update call results with correct block hash and number
      val blockHash = finalHeader.hash
      val updatedCallResults = callResults.zipWithIndex.map { case (cr, callIdx) =>
        cr.copy(logs = cr.logs.map(_.copy(
          blockHash = blockHash,
          blockNumber = finalHeader.number
        )))
      }

      val body = BlockBody(txs, Nil, Some(Seq.empty))
      blockResults += SimulateBlockResult(finalHeader, body, txs, updatedCallResults, receipts)
      parentHeader = finalHeader
    }

    Right(EthSimulateResponse(blockResults.toSeq, req.returnFullTransactions))
  }

  private def validateBlockOrdering(
      blockStateCalls: Seq[BlockStateCall],
      baseHeader: BlockHeader
  ): Either[JsonRpcError, Unit] = {
    var prevNumber = baseHeader.number
    var prevTimestamp = BigInt(baseHeader.unixTimestamp)

    for ((bsc, idx) <- blockStateCalls.zipWithIndex) {
      val overrides = bsc.blockOverrides.getOrElse(BlockOverrides())
      val number = overrides.number.getOrElse(prevNumber + 1)
      val timestamp = overrides.time.getOrElse(prevTimestamp + 12)

      if (number <= prevNumber) {
        return Left(JsonRpcError.SimulateBlockNumberNotIncreasing(
          s"block numbers must be in order: $number <= $prevNumber"))
      }
      if (timestamp <= prevTimestamp) {
        return Left(JsonRpcError.SimulateTimestampNotIncreasing(
          s"block timestamps must be in order: $timestamp <= $prevTimestamp"))
      }

      prevNumber = number
      prevTimestamp = timestamp
    }
    Right(())
  }

  private def buildBlockHeader(
      parentHeader: BlockHeader,
      overrides: Option[BlockOverrides],
      validation: Boolean
  ): BlockHeader = {
    val ov = overrides.getOrElse(BlockOverrides())
    val number = ov.number.getOrElse(parentHeader.number + 1)
    val timestamp = ov.time.getOrElse(BigInt(parentHeader.unixTimestamp) + 12)
    val gasLimit = ov.gasLimit.getOrElse(parentHeader.gasLimit)
    val beneficiary = ov.feeRecipient.map(_.bytes).getOrElse(ByteString(new Array[Byte](20)))
    val prevRandao = ov.prevRandao.getOrElse(ByteString(new Array[Byte](32)))
    val baseFee = ov.baseFeePerGas.getOrElse(
      if (!validation) BigInt(0)
      else computeNextBaseFee(parentHeader)
    )
    val parentBeaconBlockRoot = ByteString(new Array[Byte](32)) // Zero for simulated blocks

    val extraFields = HefPostPrague(
      baseFee = baseFee,
      withdrawalsRoot = EmptyWithdrawalsRoot,
      blobGasUsed = BigInt(0),
      excessBlobGas = parentHeader.excessBlobGas.getOrElse(BigInt(0)),
      parentBeaconBlockRoot = parentBeaconBlockRoot,
      requestsHash = EmptyRequestsHash
    )

    BlockHeader(
      parentHash = parentHeader.hash,
      ommersHash = EmptyOmmersHash,
      beneficiary = beneficiary,
      stateRoot = ByteString(new Array[Byte](32)), // Placeholder — filled after execution
      transactionsRoot = EmptyMpt,
      receiptsRoot = EmptyMpt,
      logsBloom = EmptyBloom,
      difficulty = BigInt(0),
      number = number,
      gasLimit = gasLimit,
      gasUsed = BigInt(0), // Placeholder — filled after execution
      unixTimestamp = timestamp.toLong,
      extraData = ByteString.empty,
      mixHash = prevRandao,
      nonce = ByteString(new Array[Byte](8)),
      extraFields = extraFields
    )
  }

  private def applyStateOverrides(
      world: InMemoryWorldStateProxy,
      overrides: Map[Address, StateOverride]
  ): InMemoryWorldStateProxy = {
    var w = world
    for ((address, ov) <- overrides) {
      // Get or create account
      var account = w.getAccount(address).getOrElse(Account.empty(blockchainConfig.accountStartNonce))

      // Apply balance override
      ov.balance.foreach(bal => account = account.copy(balance = UInt256(bal)))

      // Apply nonce override
      ov.nonce.foreach(n => account = account.copy(nonce = UInt256(n)))

      w = w.saveAccount(address, account)

      // Apply code override
      ov.code.foreach { code =>
        w = w.saveCode(address, code)
      }

      // Apply state override (full replacement)
      ov.state.foreach { slots =>
        // Clear existing storage by creating fresh storage
        val storage = w.getStorage(address)
        val cleared = storage.store(UInt256.Zero.toBigInt, BigInt(0)) // trigger fresh storage
        var s = cleared
        for ((key, value) <- slots) {
          s = s.store(key, value)
        }
        w = w.saveStorage(address, s)
      }

      // Apply stateDiff (partial update)
      ov.stateDiff.foreach { slots =>
        val storage = w.getStorage(address)
        var s = storage
        for ((key, value) <- slots) {
          s = s.store(key, value)
        }
        w = w.saveStorage(address, s)
      }

      // movePrecompileToAddress is handled separately (advanced feature)
    }
    w
  }

  private def executeCalls(
      calls: Seq[SimulateCall],
      blockHeader: BlockHeader,
      initialWorld: InMemoryWorldStateProxy,
      validation: Boolean,
      traceTransfers: Boolean,
      nonceMap: mutable.Map[Address, BigInt]
  ): Either[JsonRpcError, (InMemoryWorldStateProxy, Seq[SimulateCallResult], Seq[SignedTransaction], Seq[Receipt], BigInt)] = {
    var world = initialWorld
    val callResults = mutable.ArrayBuffer[SimulateCallResult]()
    val txs = mutable.ArrayBuffer[SignedTransaction]()
    val receipts = mutable.ArrayBuffer[Receipt]()
    var accumGas = BigInt(0)
    val baseFee = blockHeader.baseFee.getOrElse(BigInt(0))

    for ((call, callIdx) <- calls.zipWithIndex) {
      val sender = call.from.getOrElse(Address(0))

      // Resolve nonce
      val senderNonce = call.nonce.getOrElse {
        nonceMap.getOrElseUpdate(sender, {
          world.getAccount(sender).map(_.nonce.toBigInt).getOrElse(BigInt(0))
        })
      }

      // Build transaction — default gas = remaining from geth's simulate cap of 50M
      val DefaultSimGasLimit = BigInt(50000000)
      val gasLimit = call.gas.getOrElse(DefaultSimGasLimit - accumGas)
      val value = call.value.getOrElse(BigInt(0))
      val payload = call.input.getOrElse(ByteString.empty)
      val toAddr = call.to

      val maxFeePerGas = call.maxFeePerGas.getOrElse(BigInt(0))
      val gasPrice = call.gasPrice.orElse(call.maxFeePerGas).getOrElse(BigInt(0))

      // Always check: intrinsic gas
      val baseGas = if (toAddr.isEmpty) BigInt(53000) else BigInt(21000)
      val calldataGas = payload.foldLeft(BigInt(0)) { (acc, b) =>
        acc + (if (b == 0) 4 else 16)
      }
      val intrinsicGas = baseGas + calldataGas
      if (call.gas.isDefined && gasLimit < intrinsicGas) {
        return Left(JsonRpcError.SimulateIntrinsicGasTooLow(
          s"err: intrinsic gas too low: have $gasLimit, want $intrinsicGas (supplied gas $gasLimit)"))
      }

      // Always check: insufficient funds for value transfer (non-gas)
      {
        val senderBal = world.getAccount(sender).map(_.balance.toBigInt).getOrElse(BigInt(0))
        if (value > 0 && senderBal < value && !validation) {
          return Left(JsonRpcError.SimulateInsufficientFunds(
            s"err: insufficient funds for gas * price + value: address ${sender.toString} have $senderBal want $value (supplied gas ${blockHeader.gasLimit})"))
        }
      }

      // Validation mode checks
      if (validation) {
        // Check maxFeePerGas >= baseFee
        if (baseFee > 0 && maxFeePerGas < baseFee && !call.gasPrice.isDefined) {
          return Left(JsonRpcError.InvalidParams(
            s"max fee per gas less than block base fee: address ${sender.toString}, maxFeePerGas: $maxFeePerGas, baseFee: $baseFee"))
        }

        // Check nonce
        val expectedNonce = world.getAccount(sender).map(_.nonce.toBigInt).getOrElse(BigInt(0))
        if (call.nonce.isDefined && senderNonce < expectedNonce) {
          return Left(JsonRpcError.InvalidParams(
            s"nonce too low: address ${sender.toString}, tx: $senderNonce state: $expectedNonce"))
        }
        if (call.nonce.isDefined && senderNonce > expectedNonce) {
          return Left(JsonRpcError.InvalidParams(
            s"nonce too high: address ${sender.toString}, tx: $senderNonce state: $expectedNonce"))
        }

        // Check balance for gas + value
        val senderAccount = world.getAccount(sender).getOrElse(Account.empty(blockchainConfig.accountStartNonce))
        val upfrontCost = gasLimit * gasPrice + value
        if (senderAccount.balance.toBigInt < upfrontCost) {
          return Left(JsonRpcError.SimulateInsufficientFunds(
            s"err: insufficient funds for gas * price + value: address ${sender.toString} have ${senderAccount.balance} want $upfrontCost (supplied gas $gasLimit)"))
        }
      }

      // Default transaction type is 2 (EIP-1559) unless explicitly set to 0 (legacy)
      val isLegacy = call.`type`.contains(BigInt(0)) || (call.gasPrice.isDefined && call.maxFeePerGas.isEmpty && !call.`type`.contains(BigInt(2)))
      val tx: Transaction = if (!isLegacy) {
        TransactionWithDynamicFee(
          chainId = blockchainConfig.chainId,
          nonce = senderNonce,
          maxPriorityFeePerGas = call.maxPriorityFeePerGas.getOrElse(BigInt(0)),
          maxFeePerGas = call.maxFeePerGas.getOrElse(BigInt(0)),
          gasLimit = gasLimit,
          receivingAddress = toAddr,
          value = value,
          payload = payload,
          accessList = call.accessList.getOrElse(Nil)
        )
      } else {
        LegacyTransaction(
          nonce = senderNonce,
          gasPrice = gasPrice,
          gasLimit = gasLimit,
          receivingAddress = toAddr,
          value = value,
          payload = payload
        )
      }

      val fakeSignature = com.chipprbots.ethereum.crypto.ECDSASignature(BigInt(0), BigInt(0), BigInt(0))
      val stx = SignedTransaction(tx, fakeSignature)

      // Ensure sender account exists with sufficient balance
      var senderAccount = world.getAccount(sender).getOrElse(Account.empty(blockchainConfig.accountStartNonce))

      // Set correct nonce
      senderAccount = senderAccount.copy(nonce = UInt256(senderNonce))

      // In non-validation mode, ensure sender has enough balance
      if (!validation) {
        val upfrontCost = gasLimit * gasPrice + value
        if (senderAccount.balance < upfrontCost) {
          senderAccount = senderAccount.copy(balance = UInt256(upfrontCost))
        }
      }

      world = world.saveAccount(sender, senderAccount)

      // Execute transaction
      val TxResult(newWorld, gasUsed, logs, returnData, vmError) =
        blockPreparator.executeTransactionForSimulation(stx, sender, blockHeader, world)

      world = newWorld

      // Update nonce tracking
      nonceMap(sender) = senderNonce + 1

      // Build receipt
      val outcome = if (vmError.isDefined) FailureOutcome else SuccessOutcome
      val legacyReceipt = LegacyReceipt(
        postTransactionStateHash = outcome,
        cumulativeGasUsed = accumGas + gasUsed,
        logsBloomFilter = BloomFilter.create(logs),
        logs = logs
      )
      val receipt: Receipt = tx match {
        case _: TransactionWithDynamicFee => Type02Receipt(legacyReceipt)
        case _: LegacyTransaction => legacyReceipt
        case _ => legacyReceipt
      }

      accumGas += gasUsed
      txs += stx
      receipts += receipt

      // Build per-call result
      val txLogs = logs.zipWithIndex.map { case (txLog, logIdx) =>
        val globalLogIdx = receipts.dropRight(1).map(_.logs.size).sum + logIdx
        TxLog(
          logIndex = globalLogIdx,
          transactionIndex = callIdx,
          transactionHash = stx.hash,
          blockHash = ByteString(new Array[Byte](32)), // Placeholder — updated after header finalized
          blockNumber = blockHeader.number,
          address = txLog.loggerAddress,
          data = txLog.data,
          topics = txLog.logTopics,
          blockTimestamp = Some(BigInt(blockHeader.unixTimestamp))
        )
      }

      // Add trace transfer logs if enabled
      val allLogs = if (traceTransfers && value > 0 && vmError.isEmpty) {
        val transferLog = TxLog(
          logIndex = txLogs.size + receipts.dropRight(1).map(_.logs.size).sum,
          transactionIndex = callIdx,
          transactionHash = stx.hash,
          blockHash = ByteString(new Array[Byte](32)),
          blockNumber = blockHeader.number,
          address = EthTransferAddress,
          data = {
            val raw = UInt256(value).bytes
            ByteString(new Array[Byte](32 - raw.length) ++ raw.toArray)
          },
          topics = Seq(
            TransferEventTopic,
            ByteString(new Array[Byte](12) ++ sender.bytes.toArray),
            ByteString(new Array[Byte](12) ++ toAddr.map(_.bytes.toArray).getOrElse(new Array[Byte](20)))
          ),
          blockTimestamp = Some(BigInt(blockHeader.unixTimestamp))
        )
        txLogs :+ transferLog
      } else txLogs

      val callResult = vmError match {
        case Some(com.chipprbots.ethereum.vm.RevertOccurs) =>
          SimulateCallResult(
            status = BigInt(0),
            returnData = returnData,
            gasUsed = gasUsed,
            maxUsedGas = gasUsed,
            logs = Seq.empty,
            error = Some(SimulateError(3, "execution reverted",
              if (returnData.nonEmpty) Some(returnData) else None))
          )
        case Some(err) =>
          SimulateCallResult(
            status = BigInt(0),
            returnData = returnData,
            gasUsed = gasUsed,
            maxUsedGas = gasUsed,
            logs = Seq.empty,
            error = Some(SimulateError(-32015, err.toString))
          )
        case None =>
          SimulateCallResult(
            status = BigInt(1),
            returnData = returnData,
            gasUsed = gasUsed,
            maxUsedGas = gasUsed,
            logs = allLogs
          )
      }
      callResults += callResult
    }

    Right((world, callResults.toSeq, txs.toSeq, receipts.toSeq, accumGas))
  }

  /** EIP-4788: Store the parent beacon block root in the beacon root system contract */
  private def applyEip4788(
      blockHeader: BlockHeader,
      world: InMemoryWorldStateProxy
  ): InMemoryWorldStateProxy = {
    import com.chipprbots.ethereum.ledger.BlockExecution._
    blockHeader.parentBeaconBlockRoot match {
      case Some(beaconRoot) =>
        val timestamp = UInt256(blockHeader.unixTimestamp)
        val timestampIdx = timestamp.mod(UInt256(BeaconRootHistoryBufferLength))
        val rootIdx = timestampIdx + UInt256(BeaconRootHistoryBufferLength)
        val account = world.getAccount(BeaconRootContractAddress)
          .getOrElse(Account.empty(blockchainConfig.accountStartNonce))
        val w1 = if (!world.getAccount(BeaconRootContractAddress).isDefined)
          world.saveAccount(BeaconRootContractAddress, account) else world
        val storage = w1.getStorage(BeaconRootContractAddress)
        val s1 = storage.store(timestampIdx.toBigInt, timestamp.toBigInt)
        val s2 = s1.store(rootIdx.toBigInt, UInt256(beaconRoot).toBigInt)
        w1.saveStorage(BeaconRootContractAddress, s2)
      case None => world
    }
  }

  /** EIP-2935: Store parent block hash in history storage contract */
  private def applyEip2935(
      blockHeader: BlockHeader,
      world: InMemoryWorldStateProxy
  ): InMemoryWorldStateProxy = {
    import com.chipprbots.ethereum.ledger.BlockExecution._
    val blockNumber = blockHeader.number
    // Deploy history storage contract if not already deployed
    val w1 = if (world.getCode(HistoryStorageAddress).isEmpty) {
      val account = world.getAccount(HistoryStorageAddress)
        .getOrElse(Account.empty(blockchainConfig.accountStartNonce))
        .copy(nonce = UInt256(1))
      world.saveAccount(HistoryStorageAddress, account)
        .saveCode(HistoryStorageAddress, HistoryStorageCode)
    } else world
    // Store parent hash at slot (blockNumber - 1) % HistoryServeWindow
    val parentHashValue = UInt256(blockHeader.parentHash)
    val slot = (blockNumber - 1) % HistoryServeWindow
    val storage = w1.getStorage(HistoryStorageAddress)
    val updatedStorage = storage.store(slot, parentHashValue.toBigInt)
    w1.saveStorage(HistoryStorageAddress, updatedStorage)
  }

  /** EIP-1559: Compute the base fee for the next block */
  private def computeNextBaseFee(parentHeader: BlockHeader): BigInt = {
    val parentBaseFee = parentHeader.baseFee.getOrElse(BigInt(0))
    if (parentBaseFee == 0) return BigInt(0)
    val elasticityMultiplier = 2
    val baseFeeChangeDenominator = 8
    val parentGasTarget = parentHeader.gasLimit / elasticityMultiplier
    if (parentGasTarget == 0) return parentBaseFee
    if (parentHeader.gasUsed == parentGasTarget) {
      parentBaseFee
    } else if (parentHeader.gasUsed > parentGasTarget) {
      val gasUsedDelta = parentHeader.gasUsed - parentGasTarget
      val baseFeePerGasDelta = (parentBaseFee * gasUsedDelta / parentGasTarget / baseFeeChangeDenominator).max(1)
      parentBaseFee + baseFeePerGasDelta
    } else {
      val gasUsedDelta = parentGasTarget - parentHeader.gasUsed
      val baseFeePerGasDelta = parentBaseFee * gasUsedDelta / parentGasTarget / baseFeeChangeDenominator
      (parentBaseFee - baseFeePerGasDelta).max(0)
    }
  }

  private def computeTransactionsRoot(txs: Seq[SignedTransaction]): ByteString = {
    if (txs.isEmpty) EmptyMpt
    else {
      val stateStorage = StateStorage.getReadOnlyStorage(EphemDataSource())
      val trie = MerklePatriciaTrie[Int, SignedTransaction](source = stateStorage)(
        MptListValidator.intByteArraySerializable,
        SignedTransaction.byteArraySerializable
      )
      ByteString(txs.zipWithIndex.foldLeft(trie)((t, r) => t.put(r._2, r._1)).getRootHash)
    }
  }

  private def computeReceiptsRoot(receipts: Seq[Receipt]): ByteString = {
    if (receipts.isEmpty) EmptyMpt
    else {
      val stateStorage = StateStorage.getReadOnlyStorage(EphemDataSource())
      val trie = MerklePatriciaTrie[Int, Receipt](source = stateStorage)(
        MptListValidator.intByteArraySerializable,
        Receipt.byteArraySerializable
      )
      ByteString(receipts.zipWithIndex.foldLeft(trie)((t, r) => t.put(r._2, r._1)).getRootHash)
    }
  }

  private def computeLogsBloom(receipts: Seq[Receipt]): ByteString = {
    if (receipts.isEmpty) EmptyBloom
    else {
      val blooms = receipts.map(_.logsBloomFilter.toArray)
      ByteString(ByteUtils.or(EmptyBloom.toArray +: blooms: _*))
    }
  }

}
