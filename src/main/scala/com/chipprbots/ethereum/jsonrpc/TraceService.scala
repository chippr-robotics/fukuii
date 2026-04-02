package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import cats.effect.IO

import scala.collection.mutable

import org.json4s.JsonAST._
import org.json4s.JsonDSL._

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.ledger._
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.rlp.UInt256RLPImplicits._
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps
import com.chipprbots.ethereum.utils.Hex
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.vm._

object TraceService {

  case class TraceBlockRequest(blockNumber: BlockParam)
  case class TraceBlockResponse(traces: Seq[TransactionTrace])

  case class TraceTransactionRequest(txHash: ByteString)
  case class TraceTransactionResponse(traces: Seq[Trace])

  /** A trace entry in OpenEthereum format. */
  case class Trace(
      action: TraceAction,
      result: Option[TraceResult],
      error: Option[String],
      revertReason: Option[String] = None,
      subtraces: Int,
      traceAddress: Seq[Int],
      transactionHash: Option[ByteString],
      transactionPosition: Option[Int],
      blockNumber: BigInt,
      blockHash: ByteString,
      traceType: String
  )

  /** Groups all traces for a single transaction. */
  case class TransactionTrace(
      txHash: ByteString,
      txIndex: Int,
      traces: Seq[Trace]
  )

  case class TraceAction(
      callType: Option[String],
      from: Address,
      to: Option[Address],
      gas: BigInt,
      input: ByteString,
      value: BigInt,
      creationType: Option[String]
  )

  case class TraceResult(
      gasUsed: BigInt,
      output: ByteString,
      address: Option[Address],
      code: Option[ByteString]
  )

  /** Per-transaction trace data collected during execution. */
  case class TxTraceResult(
      gasUsed: BigInt,
      internalTxs: Seq[InternalTransaction],
      returnData: ByteString,
      error: Option[String],
      sender: Address,
      preWorld: InMemoryWorldStateProxy,
      postWorld: InMemoryWorldStateProxy
  )

  case class TraceFilterRequest(
      fromBlock: Option[BlockParam],
      toBlock: Option[BlockParam],
      fromAddress: Option[Seq[Address]],
      toAddress: Option[Seq[Address]],
      after: Option[Int],
      count: Option[Int]
  )
  case class TraceFilterResponse(traces: Seq[Trace])

  case class TraceReplayBlockRequest(blockNumber: BlockParam, traceTypes: Seq[String])
  case class TraceReplayBlockResponse(results: Seq[ReplayResult])

  case class TraceReplayTransactionRequest(txHash: ByteString, traceTypes: Seq[String])
  case class TraceReplayTransactionResponse(result: ReplayResult)

  case class TraceGetRequest(txHash: ByteString, traceIndex: Seq[Int])
  case class TraceGetResponse(trace: Option[Trace])

  case class ReplayResult(
      transactionHash: ByteString,
      output: ByteString,
      trace: Seq[Trace],
      stateDiff: Option[JValue],
      vmTrace: Option[JValue],
      revertReason: Option[String] = None
  )
}

class TraceService(
    blockchain: BlockchainImpl,
    blockchainReader: BlockchainReader,
    evmCodeStorage: EvmCodeStorage,
    blockPreparator: BlockPreparator,
    transactionMappingStorage: TransactionMappingStorage
)(implicit blockchainConfig: BlockchainConfig)
    extends Logger {
  import TraceService._

  def traceBlock(req: TraceBlockRequest): ServiceResponse[TraceBlockResponse] = IO {
    resolveBlock(req.blockNumber) match {
      case None =>
        Left(JsonRpcError.InvalidParams("Block not found"))
      case Some(block) =>
        replayBlock(block) match {
          case Left(err) =>
            log.error(s"Failed to trace block ${block.header.number}: $err")
            Left(JsonRpcError.InternalError)
          case Right(txTraces) =>
            val allTraces = txTraces.zipWithIndex.flatMap { case (txTrace, txIdx) =>
              val stx = block.body.transactionList(txIdx)
              buildTraces(txTrace, stx, txIdx, block.header)
            }
            val grouped = allTraces.groupBy(_.transactionHash).toSeq
              .sortBy { case (_, traces) => traces.head.transactionPosition.getOrElse(0) }
              .map { case (txHashOpt, traces) =>
                val txIdx = traces.head.transactionPosition.getOrElse(0)
                val txHash = txHashOpt.getOrElse(ByteString.empty)
                TransactionTrace(txHash, txIdx, traces)
              }
            Right(TraceBlockResponse(grouped))
        }
    }
  }

  def traceTransaction(req: TraceTransactionRequest): ServiceResponse[TraceTransactionResponse] = IO {
    findTransactionBlock(req.txHash) match {
      case None =>
        Left(JsonRpcError.InvalidParams("Transaction not found"))
      case Some((block, txIndex)) =>
        replayBlockUpTo(block, txIndex) match {
          case Left(err) =>
            log.error(s"Failed to trace tx ${req.txHash.toArray.map("%02x".format(_)).mkString}: $err")
            Left(JsonRpcError.InternalError)
          case Right(txTrace) =>
            val stx = block.body.transactionList(txIndex)
            val traces = buildTraces(txTrace, stx, txIndex, block.header)
            Right(TraceTransactionResponse(traces))
        }
    }
  }

  private def resolveBlock(blockParam: BlockParam): Option[Block] = blockParam match {
    case BlockParam.Pending => None
    case other =>
      val n = BlockParam.resolveNumber(other, blockchainReader.getBestBlockNumber())
      blockchainReader.getBlockHeaderByNumber(n).flatMap(h => blockchainReader.getBlockByHash(h.hash))
  }

  private def findTransactionBlock(txHash: ByteString): Option[(Block, Int)] =
    transactionMappingStorage.get(txHash.toIndexedSeq).flatMap { location =>
      blockchainReader.getBlockByHash(location.blockHash).map { block =>
        (block, location.txIndex)
      }
    }

  /** Replay all transactions in a block and collect per-tx trace data. */
  private def replayBlock(block: Block): Either[String, Seq[TxTraceResult]] = {
    blockchainReader.getBlockHeaderByHash(block.header.parentHash) match {
      case None => Left("Missing parent block")
      case Some(parentHeader) =>
        val initialWorld = buildInitialWorld(block, parentHeader)
        replayTransactions(block.body.transactionList, block.header, initialWorld, Nil)
    }
  }

  /** Replay transactions up to and including txIndex. */
  private def replayBlockUpTo(block: Block, txIndex: Int): Either[String, TxTraceResult] = {
    blockchainReader.getBlockHeaderByHash(block.header.parentHash) match {
      case None => Left("Missing parent block")
      case Some(parentHeader) =>
        val initialWorld = buildInitialWorld(block, parentHeader)
        val txs = block.body.transactionList.take(txIndex + 1)
        replayTransactions(txs, block.header, initialWorld, Nil) match {
          case Right(results) => Right(results.last)
          case Left(err)      => Left(err)
        }
    }
  }

  private def buildInitialWorld(block: Block, parentHeader: BlockHeader): InMemoryWorldStateProxy =
    InMemoryWorldStateProxy(
      evmCodeStorage = evmCodeStorage,
      blockchain.getBackingMptStorage(block.header.number),
      (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash),
      accountStartNonce = blockchainConfig.accountStartNonce,
      stateRootHash = parentHeader.stateRoot,
      noEmptyAccounts = EvmConfig.forBlock(block.header.number, blockchainConfig).noEmptyAccounts,
      ethCompatibleStorage = blockchainConfig.ethCompatibleStorage
    )

  /** Execute transactions sequentially, collecting trace data from each. */
  private def replayTransactions(
      txs: Seq[SignedTransaction],
      blockHeader: BlockHeader,
      world: InMemoryWorldStateProxy,
      accumulated: Seq[TxTraceResult]
  ): Either[String, Seq[TxTraceResult]] = txs match {
    case Nil => Right(accumulated)
    case stx +: rest =>
      SignedTransaction.getSender(stx) match {
        case None => Left(s"Cannot recover sender for tx ${stx.hash.toHex}")
        case Some(senderAddress) =>
          val account = world
            .getAccount(senderAddress)
            .getOrElse(Account.empty(blockchainConfig.accountStartNonce))
          val worldWithAccount = world.saveAccount(senderAddress, account)

          val traceResult = blockPreparator.executeTransactionWithTrace(stx, senderAddress, blockHeader, worldWithAccount)
          val nextWorld = traceResult.txResult.worldState
          val txTrace = TxTraceResult(
            gasUsed = traceResult.txResult.gasUsed,
            internalTxs = traceResult.internalTxs,
            returnData = traceResult.txResult.vmReturnData,
            error = traceResult.txResult.vmError.map(_.toString),
            sender = senderAddress,
            preWorld = worldWithAccount,
            postWorld = nextWorld
          )
          replayTransactions(rest, blockHeader, nextWorld, accumulated :+ txTrace)
      }
  }

  /** Convert internal transactions into OpenEthereum-compatible traces.
    * Root trace fields are populated from the signed transaction and recovered sender.
    */
  private def buildTraces(
      txTrace: TxTraceResult,
      stx: SignedTransaction,
      txIndex: Int,
      blockHeader: BlockHeader
  ): Seq[Trace] = {
    val isCreate = stx.tx.isContractInit

    // Compute created contract address for root-level CREATE transactions
    val createdAddr: Option[Address] = if (isCreate) {
      val rlpPreimage = rlp.encode(
        RLPList(RLPValue(txTrace.sender.bytes.toArray), UInt256(stx.tx.nonce).toRLPEncodable)
      )
      Some(Address(ByteString(crypto.kec256(rlpPreimage))))
    } else None

    val rootRevertReason = txTrace.error
      .filter(_.toLowerCase.contains("revert"))
      .flatMap(_ => parseRevertReason(txTrace.returnData))

    val rootTrace = Trace(
      action = TraceAction(
        callType = if (isCreate) None else Some("call"),
        from = txTrace.sender,
        to = stx.tx.receivingAddress,
        gas = stx.tx.gasLimit,
        input = stx.tx.payload,
        value = stx.tx.value,
        creationType = if (isCreate) Some("create") else None
      ),
      result = if (txTrace.error.isEmpty) Some(TraceResult(
        gasUsed = txTrace.gasUsed,
        output = if (isCreate) ByteString.empty else txTrace.returnData,
        address = createdAddr,
        code = if (isCreate) Some(txTrace.returnData) else None
      )) else None,
      error = txTrace.error,
      revertReason = rootRevertReason,
      subtraces = txTrace.internalTxs.size,
      traceAddress = Seq.empty,
      transactionHash = Some(stx.hash),
      transactionPosition = Some(txIndex),
      blockNumber = blockHeader.number,
      blockHash = blockHeader.hash,
      traceType = if (isCreate) "create" else "call"
    )

    // Internal traces from CALL/CREATE opcodes
    val internalTraces = txTrace.internalTxs.zipWithIndex.map { case (itx, idx) =>
      val isInternalCreate = itx.opcode == CREATE || itx.opcode == CREATE2
      Trace(
        action = TraceAction(
          callType = if (isInternalCreate) None else Some(callTypeFromOpcode(itx.opcode)),
          from = itx.from,
          to = if (isInternalCreate) None else itx.to,
          gas = itx.gasLimit,
          input = itx.data,
          value = itx.value,
          creationType = if (isInternalCreate) Some(createTypeFromOpcode(itx.opcode)) else None
        ),
        result = Some(TraceResult(
          gasUsed = 0, // gas used per internal call is not tracked in InternalTransaction
          output = ByteString.empty,
          address = if (isInternalCreate) itx.to else None,
          code = if (isInternalCreate) itx.to.map(addr => txTrace.postWorld.getCode(addr)).filter(_.nonEmpty) else None
        )),
        error = None,
        revertReason = None,
        subtraces = 0,
        traceAddress = Seq(idx),
        transactionHash = Some(stx.hash),
        transactionPosition = Some(txIndex),
        blockNumber = blockHeader.number,
        blockHash = blockHeader.hash,
        traceType = if (isInternalCreate) "create" else "call"
      )
    }

    rootTrace +: internalTraces
  }

  private def callTypeFromOpcode(opcode: OpCode): String = opcode match {
    case CALL         => "call"
    case CALLCODE     => "callcode"
    case DELEGATECALL => "delegatecall"
    case STATICCALL   => "staticcall"
    case _            => "call"
  }

  private def createTypeFromOpcode(opcode: OpCode): String = opcode match {
    case CREATE2 => "create2"
    case _       => "create"
  }

  def traceFilter(req: TraceFilterRequest): ServiceResponse[TraceFilterResponse] = IO {
    val fromBlockNum = req.fromBlock.flatMap(resolveBlockNumber).getOrElse(BigInt(0))
    val toBlockNum = req.toBlock.flatMap(resolveBlockNumber).getOrElse(blockchainReader.getBestBlockNumber())

    // Cap range to prevent DoS — max 1000 blocks per filter query
    val maxRange = 1000
    val effectiveTo = fromBlockNum + maxRange min toBlockNum

    var allTraces = Seq.empty[Trace]
    var blockNum = fromBlockNum
    while (blockNum <= effectiveTo) {
      for {
        header <- blockchainReader.getBlockHeaderByNumber(blockNum)
        block <- blockchainReader.getBlockByHash(header.hash)
        txTraces <- replayBlock(block).toOption
      } {
        val blockTraces = txTraces.zipWithIndex.flatMap { case (txTrace, txIdx) =>
          val stx = block.body.transactionList(txIdx)
          buildTraces(txTrace, stx, txIdx, block.header)
        }
        allTraces = allTraces ++ blockTraces
      }
      blockNum += 1
    }

    // Apply address filters
    val filtered = allTraces.filter { trace =>
      val fromMatch = req.fromAddress match {
        case Some(addrs) => addrs.contains(trace.action.from)
        case None        => true
      }
      val toMatch = req.toAddress match {
        case Some(addrs) => trace.action.to.exists(addrs.contains)
        case None        => true
      }
      fromMatch && toMatch
    }

    // Apply pagination
    val afterSkip = req.after.map(n => filtered.drop(n)).getOrElse(filtered)
    val limited = req.count.map(n => afterSkip.take(n)).getOrElse(afterSkip)

    Right(TraceFilterResponse(limited))
  }

  def traceReplayBlock(req: TraceReplayBlockRequest): ServiceResponse[TraceReplayBlockResponse] = IO {
    resolveBlock(req.blockNumber) match {
      case None =>
        Left(JsonRpcError.InvalidParams("Block not found"))
      case Some(block) =>
        replayBlock(block) match {
          case Left(err) =>
            log.error(s"Failed to replay block ${block.header.number}: $err")
            Left(JsonRpcError.InternalError)
          case Right(txTraces) =>
            val includeTrace     = req.traceTypes.contains("trace")
            val includeStateDiff = req.traceTypes.contains("stateDiff")
            val includeVmTrace   = req.traceTypes.contains("vmTrace")

            val results = txTraces.zipWithIndex.map { case (txTrace, txIdx) =>
              val stx = block.body.transactionList(txIdx)
              val traces = if (includeTrace) buildTraces(txTrace, stx, txIdx, block.header) else Seq.empty
              val stateDiff = if (includeStateDiff) Some(computeStateDiff(txTrace, stx, block.header)) else None
              val vmTrace = if (includeVmTrace) computeVmTrace(txTrace, stx, block.header) else None
              val revertReason = txTrace.error
                .filter(_.toLowerCase.contains("revert"))
                .flatMap(_ => parseRevertReason(txTrace.returnData))
              ReplayResult(
                transactionHash = stx.hash,
                output = txTrace.returnData,
                trace = traces,
                stateDiff = stateDiff,
                vmTrace = vmTrace,
                revertReason = revertReason
              )
            }
            Right(TraceReplayBlockResponse(results))
        }
    }
  }

  def traceReplayTransaction(req: TraceReplayTransactionRequest): ServiceResponse[TraceReplayTransactionResponse] = IO {
    findTransactionBlock(req.txHash) match {
      case None =>
        Left(JsonRpcError.InvalidParams("Transaction not found"))
      case Some((block, txIndex)) =>
        replayBlockUpTo(block, txIndex) match {
          case Left(err) =>
            log.error(s"Failed to replay tx: $err")
            Left(JsonRpcError.InternalError)
          case Right(txTrace) =>
            val stx = block.body.transactionList(txIndex)
            val includeTrace     = req.traceTypes.contains("trace")
            val includeStateDiff = req.traceTypes.contains("stateDiff")
            val includeVmTrace   = req.traceTypes.contains("vmTrace")

            val traces = if (includeTrace) buildTraces(txTrace, stx, txIndex, block.header) else Seq.empty
            val stateDiff = if (includeStateDiff) Some(computeStateDiff(txTrace, stx, block.header)) else None
            val vmTrace = if (includeVmTrace) computeVmTrace(txTrace, stx, block.header) else None
            val revertReason = txTrace.error
              .filter(_.toLowerCase.contains("revert"))
              .flatMap(_ => parseRevertReason(txTrace.returnData))
            Right(TraceReplayTransactionResponse(ReplayResult(
              transactionHash = stx.hash,
              output = txTrace.returnData,
              trace = traces,
              stateDiff = stateDiff,
              vmTrace = vmTrace,
              revertReason = revertReason
            )))
        }
    }
  }

  def traceGet(req: TraceGetRequest): ServiceResponse[TraceGetResponse] = IO {
    findTransactionBlock(req.txHash) match {
      case None =>
        Left(JsonRpcError.InvalidParams("Transaction not found"))
      case Some((block, txIndex)) =>
        replayBlockUpTo(block, txIndex) match {
          case Left(err) =>
            log.error(s"Failed to trace tx for trace_get: $err")
            Left(JsonRpcError.InternalError)
          case Right(txTrace) =>
            val stx = block.body.transactionList(txIndex)
            val traces = buildTraces(txTrace, stx, txIndex, block.header)
            val matched = traces.find(_.traceAddress == req.traceIndex)
            Right(TraceGetResponse(matched))
        }
    }
  }

  /** Compute OpenEthereum/Parity stateDiff for a transaction.
    * Re-executes the transaction with a storage-change tracer to capture SSTORE events,
    * then combines with account-level diffs from the pre/post execution worlds.
    *
    * Format: per-account DiffNode — "=" unchanged, {"*":{from,to}} changed, {"+":{...}} created, {"-":{...}} deleted.
    */
  private def computeStateDiff(
      txTrace: TxTraceResult,
      stx: SignedTransaction,
      blockHeader: BlockHeader
  ): JValue = {
    // Re-execute to capture dirty storage slots (preWorld is before persistState, so cache is populated)
    val storageTracer = new StorageChangeTracer(txTrace.preWorld)
    val evmConfig = EvmConfig.forBlock(blockHeader.number, blockchainConfig)
    val tracingVm = new VM[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage](Some(storageTracer))
    val context = ProgramContext[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage](
      stx, blockHeader, txTrace.sender, txTrace.preWorld, evmConfig
    )
    storageTracer.onTxStart(txTrace.sender, stx.tx.receivingAddress, context.startGas, stx.tx.value, stx.tx.payload)
    val reExecResult = tracingVm.run(context)
    storageTracer.onTxEnd(
      context.startGas - reExecResult.gasRemaining,
      reExecResult.returnData,
      reExecResult.error.map(_.toString)
    )

    // All addresses affected by this transaction (from full execution including gas payments)
    val touched = txTrace.postWorld.touchedAccounts ++
      txTrace.postWorld.contractStorages.keySet ++
      storageTracer.changes.keySet

    val entries: List[JField] = touched.toList.sortBy(_.toString).map { addr =>
      val addrHex = "0x" + Hex.toHexString(addr.bytes.toArray)
      val preAcc  = txTrace.preWorld.getAccount(addr)
      val postAcc = txTrace.postWorld.getAccount(addr)

      val balanceDiff = diffNode(
        preAcc.map(a => "0x" + a.balance.toBigInt.toString(16)),
        postAcc.map(a => "0x" + a.balance.toBigInt.toString(16))
      )
      val nonceDiff = diffNode(
        preAcc.map(a => "0x" + a.nonce.toBigInt.toString(16)),
        postAcc.map(a => "0x" + a.nonce.toBigInt.toString(16))
      )
      val preCode  = txTrace.preWorld.getCode(addr)
      val postCode = txTrace.postWorld.getCode(addr)
      val codeDiff = diffNode(
        Some(if (preCode.nonEmpty) "0x" + Hex.toHexString(preCode.toArray) else "0x"),
        Some(if (postCode.nonEmpty) "0x" + Hex.toHexString(postCode.toArray) else "0x")
      )

      val storageDiff: JValue = storageTracer.changes.get(addr) match {
        case None => JObject(Nil)
        case Some(slotChanges) =>
          val fields = slotChanges.toList.flatMap { case (slot, (preVal, postVal)) =>
            val slotHex = "0x" + slot.toString(16).reverse.padTo(64, '0').reverse
            val d = diffNode(
              Some("0x" + preVal.toString(16).reverse.padTo(64, '0').reverse),
              Some("0x" + postVal.toString(16).reverse.padTo(64, '0').reverse)
            )
            if (d == JString("=")) None
            else Some(JField(slotHex, d))
          }
          JObject(fields)
      }

      JField(addrHex,
        ("balance" -> balanceDiff) ~
        ("nonce"   -> nonceDiff) ~
        ("code"    -> codeDiff) ~
        ("storage" -> storageDiff)
      )
    }
    JObject(entries)
  }

  /** Compute Parity vmTrace for a transaction using VmTracer re-execution. */
  private def computeVmTrace(
      txTrace: TxTraceResult,
      stx: SignedTransaction,
      blockHeader: BlockHeader
  ): Option[JValue] = {
    val vmTracer = new VmTracer()
    val evmConfig = EvmConfig.forBlock(blockHeader.number, blockchainConfig)
    val tracingVm = new VM[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage](Some(vmTracer))
    val context = ProgramContext[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage](
      stx, blockHeader, txTrace.sender, txTrace.preWorld, evmConfig
    )
    vmTracer.onTxStart(txTrace.sender, stx.tx.receivingAddress, context.startGas, stx.tx.value, stx.tx.payload)
    val reExecResult = tracingVm.run(context)
    vmTracer.onTxEnd(
      context.startGas - reExecResult.gasRemaining,
      reExecResult.returnData,
      reExecResult.error.map(_.toString)
    )
    Some(vmTracer.getResult)
  }

  /** DiffNode encoding — matches OpenEthereum/Besu/Parity trace_replay format. */
  private def diffNode(pre: Option[String], post: Option[String]): JValue = (pre, post) match {
    case (None, None)             => JNull
    case (None, Some(to))         => JObject(List(JField("+", JString(to))))
    case (Some(from), None)       => JObject(List(JField("-", JString(from))))
    case (Some(f), Some(t)) if f == t => JString("=")
    case (Some(f), Some(t))       => JObject(List(JField("*", ("from" -> JString(f)) ~ ("to" -> JString(t)))))
  }

  /** Parse Solidity revert reason from ABI-encoded error data.
    * Format: 0x08c379a0 (Error(string) selector) + ABI-encoded string.
    */
  private def parseRevertReason(data: ByteString): Option[String] = {
    if (data.length < 68) return None
    val selector = data.take(4)
    if (selector != ByteString(0x08, 0xc3, 0x79, 0xa0)) return None
    try {
      val offset = BigInt(1, data.slice(4, 36).toArray).toInt
      val length = BigInt(1, data.slice(36 + offset, 68 + offset).toArray).toInt
      if (data.length < 68 + offset + length) return None
      Some(new String(data.slice(68 + offset, 68 + offset + length).toArray, "UTF-8"))
    } catch {
      case _: Exception => None
    }
  }

  private def resolveBlockNumber(blockParam: BlockParam): Option[BigInt] = blockParam match {
    case BlockParam.Pending => None
    case other              => Some(BlockParam.resolveNumber(other, blockchainReader.getBestBlockNumber()))
  }

  /** Tracks SSTORE events during VM re-execution for stateDiff computation.
    * Records net change (original pre-tx value → final post-tx value) per (address, slot) pair.
    */
  private class StorageChangeTracer(preWorld: InMemoryWorldStateProxy) extends ExecutionTracer {
    // address -> slot -> (originalPreValue, currentPostValue)
    val changes: mutable.Map[Address, mutable.Map[BigInt, (BigInt, BigInt)]] = mutable.Map.empty

    override def onStep[W <: WorldStateProxy[W, S], S <: Storage[S]](
        opCode: OpCode,
        prevState: ProgramState[W, S],
        nextState: ProgramState[W, S]
    ): Unit = {
      opCode match {
        case SSTORE if prevState.stack.size >= 2 =>
          val addr    = prevState.env.ownerAddr
          val slot    = prevState.stack.peek(0).toBigInt
          val postVal = prevState.stack.peek(1).toBigInt
          val addrMap = changes.getOrElseUpdate(addr, mutable.Map.empty)
          addrMap.get(slot) match {
            case Some((originalPre, _)) =>
              // Subsequent SSTORE to same slot: update post-value only, preserve original pre-value
              addrMap(slot) = (originalPre, postVal)
            case None =>
              // First SSTORE to this slot: record original pre-tx value
              val preVal = preWorld.getStorage(addr).load(slot)
              addrMap(slot) = (preVal, postVal)
          }
        case _ =>
      }
    }

    override def getResult: JValue = JNothing
  }
}
