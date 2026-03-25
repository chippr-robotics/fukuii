package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import cats.effect.IO

import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.ledger._
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps
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
      error: Option[String]
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
      stateDiff: Option[String], // placeholder — not implemented
      vmTrace: Option[String]    // placeholder — not implemented
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
              buildTraces(txTrace, stx.hash, txIdx, block.header)
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
            val traces = buildTraces(txTrace, stx.hash, txIndex, block.header)
            Right(TraceTransactionResponse(traces))
        }
    }
  }

  private def resolveBlock(blockParam: BlockParam): Option[Block] = blockParam match {
    case BlockParam.WithNumber(n) =>
      blockchainReader.getBlockHeaderByNumber(n).flatMap(h => blockchainReader.getBlockByHash(h.hash))
    case BlockParam.Latest =>
      val best = blockchainReader.getBestBlockNumber()
      blockchainReader.getBlockHeaderByNumber(best).flatMap(h => blockchainReader.getBlockByHash(h.hash))
    case BlockParam.Earliest =>
      blockchainReader.getBlockHeaderByNumber(0).flatMap(h => blockchainReader.getBlockByHash(h.hash))
    case BlockParam.Pending => None
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
            error = traceResult.txResult.vmError.map(_.toString)
          )
          replayTransactions(rest, blockHeader, nextWorld, accumulated :+ txTrace)
      }
  }

  /** Convert internal transactions into OpenEthereum-compatible traces. */
  private def buildTraces(
      txTrace: TxTraceResult,
      txHash: ByteString,
      txIndex: Int,
      blockHeader: BlockHeader
  ): Seq[Trace] = {
    // Root trace for the top-level transaction
    val rootTrace = Trace(
      action = TraceAction(
        callType = Some("call"),
        from = Address(ByteString.empty), // will be filled from tx sender
        to = None,
        gas = 0,
        input = ByteString.empty,
        value = 0,
        creationType = None
      ),
      result = Some(TraceResult(
        gasUsed = txTrace.gasUsed,
        output = txTrace.returnData,
        address = None,
        code = None
      )),
      error = txTrace.error,
      subtraces = txTrace.internalTxs.size,
      traceAddress = Seq.empty,
      transactionHash = Some(txHash),
      transactionPosition = Some(txIndex),
      blockNumber = blockHeader.number,
      blockHash = blockHeader.hash,
      traceType = "call"
    )

    // Internal traces from CALL/CREATE opcodes
    val internalTraces = txTrace.internalTxs.zipWithIndex.map { case (itx, idx) =>
      val isCreate = itx.opcode == CREATE || itx.opcode == CREATE2
      Trace(
        action = TraceAction(
          callType = if (isCreate) None else Some(callTypeFromOpcode(itx.opcode)),
          from = itx.from,
          to = itx.to,
          gas = itx.gasLimit,
          input = itx.data,
          value = itx.value,
          creationType = if (isCreate) Some(createTypeFromOpcode(itx.opcode)) else None
        ),
        result = Some(TraceResult(
          gasUsed = 0, // gas used per internal call is not tracked in InternalTransaction
          output = ByteString.empty,
          address = if (isCreate) itx.to else None,
          code = None
        )),
        error = None,
        subtraces = 0,
        traceAddress = Seq(idx),
        transactionHash = Some(txHash),
        transactionPosition = Some(txIndex),
        blockNumber = blockHeader.number,
        blockHash = blockHeader.hash,
        traceType = if (isCreate) "create" else "call"
      )
    }

    rootTrace +: internalTraces
  }

  private def callTypeFromOpcode(opcode: OpCode): String = opcode match {
    case CALL         => "call"
    case CALLCODE     => "callcode"
    case DELEGATECALL => "delegatecall"
    case STATICCALL   => "staticcall"
    case _                   => "call"
  }

  private def createTypeFromOpcode(opcode: OpCode): String = opcode match {
    case CREATE2 => "create2"
    case _              => "create"
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
          buildTraces(txTrace, stx.hash, txIdx, block.header)
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
            val results = txTraces.zipWithIndex.map { case (txTrace, txIdx) =>
              val stx = block.body.transactionList(txIdx)
              val traces = buildTraces(txTrace, stx.hash, txIdx, block.header)
              ReplayResult(
                transactionHash = stx.hash,
                output = txTrace.returnData,
                trace = traces,
                stateDiff = None,
                vmTrace = None
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
            val traces = buildTraces(txTrace, stx.hash, txIndex, block.header)
            Right(TraceReplayTransactionResponse(ReplayResult(
              transactionHash = stx.hash,
              output = txTrace.returnData,
              trace = traces,
              stateDiff = None,
              vmTrace = None
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
            val traces = buildTraces(txTrace, stx.hash, txIndex, block.header)
            val matched = traces.find(_.traceAddress == req.traceIndex)
            Right(TraceGetResponse(matched))
        }
    }
  }

  private def resolveBlockNumber(blockParam: BlockParam): Option[BigInt] = blockParam match {
    case BlockParam.WithNumber(n) => Some(n)
    case BlockParam.Latest        => Some(blockchainReader.getBestBlockNumber())
    case BlockParam.Earliest      => Some(BigInt(0))
    case BlockParam.Pending       => None
  }
}
