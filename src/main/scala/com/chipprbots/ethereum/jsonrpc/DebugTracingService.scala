package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import cats.effect.IO

import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.ledger._
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.vm._

object DebugTracingService {

  /** Tracing options matching go-ethereum's TracerConfig. */
  case class TraceConfig(
      disableStack: Boolean = false,
      disableStorage: Boolean = true,
      enableMemory: Boolean = false,
      enableReturnData: Boolean = false,
      limit: Int = 0
  )

  case class DebugTraceTransactionRequest(txHash: ByteString, config: TraceConfig = TraceConfig())
  case class DebugTraceTransactionResponse(
      gas: BigInt,
      failed: Boolean,
      returnValue: ByteString,
      structLogs: Seq[StructLog]
  )

  case class DebugTraceCallRequest(
      tx: EthInfoService.CallTx,
      block: BlockParam,
      config: TraceConfig = TraceConfig()
  )
  case class DebugTraceCallResponse(
      gas: BigInt,
      failed: Boolean,
      returnValue: ByteString,
      structLogs: Seq[StructLog]
  )

  case class DebugTraceBlockRequest(blockParam: BlockParam, config: TraceConfig = TraceConfig())
  case class DebugTraceBlockResponse(results: Seq[TxTraceResult])

  case class TxTraceResult(
      txHash: ByteString,
      result: DebugTraceTransactionResponse
  )

  case class DebugTraceBlockByHashRequest(blockHash: ByteString, config: TraceConfig = TraceConfig())
  case class DebugTraceBlockByNumberRequest(blockNumber: BlockParam, config: TraceConfig = TraceConfig())
}

class DebugTracingService(
    blockchain: BlockchainImpl,
    blockchainReader: BlockchainReader,
    evmCodeStorage: EvmCodeStorage,
    transactionMappingStorage: TransactionMappingStorage,
    stxLedger: StxLedger
)(implicit blockchainConfig: BlockchainConfig)
    extends Logger {
  import DebugTracingService._

  def traceTransaction(req: DebugTraceTransactionRequest): ServiceResponse[DebugTraceTransactionResponse] = IO {
    findTransactionBlock(req.txHash) match {
      case None =>
        Left(JsonRpcError.InvalidParams("Transaction not found"))
      case Some((block, txIndex)) =>
        traceBlockTransaction(block, txIndex, req.config) match {
          case Left(err) => Left(err)
          case Right(response) => Right(response)
        }
    }
  }

  def traceCall(req: DebugTraceCallRequest): ServiceResponse[DebugTraceCallResponse] = IO {
    resolveBlock(req.block) match {
      case None =>
        Left(JsonRpcError.InvalidParams("Block not found"))
      case Some(block) =>
        val tracer = new StructLogTracer(
          enableMemory = req.config.enableMemory,
          enableStorage = !req.config.disableStorage,
          limit = req.config.limit
        )
        val tracingVm = new VM[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage](Some(tracer))
        val evmConfig = EvmConfig.forBlock(block.header.number, blockchainConfig)

        val world = buildInitialWorld(block)
        val gasLimit = req.tx.gas.getOrElse(block.header.gasLimit)
        val gasPrice = req.tx.gasPrice
        val from = req.tx.from.map(Address(_)).getOrElse(Address(0))
        val to = req.tx.to.map(Address(_))
        val value = req.tx.value
        val data = req.tx.data

        val context = ProgramContext[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage](
          callerAddr = from,
          originAddr = from,
          recipientAddr = to,
          gasPrice = UInt256(gasPrice),
          startGas = gasLimit,
          inputData = data,
          value = UInt256(value),
          endowment = UInt256(value),
          doTransfer = false,
          blockHeader = block.header,
          callDepth = 0,
          world = world,
          initialAddressesToDelete = Set(),
          evmConfig = evmConfig,
          originalWorld = world,
          warmAddresses = Set.empty,
          warmStorage = Set.empty
        )

        val result = tracingVm.run(context)
        tracer.setResult(result.gasRemaining, result.returnData, result.error.isDefined)

        val structLogs = if (req.config.disableStack) {
          tracer.getSteps.map(_.copy(stack = Seq.empty))
        } else tracer.getSteps

        Right(DebugTraceCallResponse(
          gas = context.startGas - result.gasRemaining,
          failed = result.error.isDefined,
          returnValue = result.returnData,
          structLogs = structLogs
        ))
    }
  }

  def traceBlock(req: DebugTraceBlockRequest): ServiceResponse[DebugTraceBlockResponse] = IO {
    resolveBlock(req.blockParam) match {
      case None =>
        Left(JsonRpcError.InvalidParams("Block not found"))
      case Some(block) =>
        traceAllBlockTransactions(block, req.config) match {
          case Left(err) => Left(err)
          case Right(results) => Right(DebugTraceBlockResponse(results))
        }
    }
  }

  def traceBlockByHash(req: DebugTraceBlockByHashRequest): ServiceResponse[DebugTraceBlockResponse] = IO {
    blockchainReader.getBlockByHash(req.blockHash) match {
      case None =>
        Left(JsonRpcError.InvalidParams("Block not found"))
      case Some(block) =>
        traceAllBlockTransactions(block, req.config) match {
          case Left(err) => Left(err)
          case Right(results) => Right(DebugTraceBlockResponse(results))
        }
    }
  }

  def traceBlockByNumber(req: DebugTraceBlockByNumberRequest): ServiceResponse[DebugTraceBlockResponse] = IO {
    resolveBlock(req.blockNumber) match {
      case None =>
        Left(JsonRpcError.InvalidParams("Block not found"))
      case Some(block) =>
        traceAllBlockTransactions(block, req.config) match {
          case Left(err) => Left(err)
          case Right(results) => Right(DebugTraceBlockResponse(results))
        }
    }
  }

  private def traceBlockTransaction(
      block: Block,
      txIndex: Int,
      config: TraceConfig
  ): Either[JsonRpcError, DebugTraceTransactionResponse] = {
    blockchainReader.getBlockHeaderByHash(block.header.parentHash) match {
      case None => Left(JsonRpcError.InternalError)
      case Some(parentHeader) =>
        val world = buildWorldFromParent(block, parentHeader)
        // Replay transactions before the target to get correct state
        val (finalWorld, _) = replayPriorTransactions(block.body.transactionList.take(txIndex), block.header, world)

        val stx = block.body.transactionList(txIndex)
        SignedTransaction.getSender(stx) match {
          case None => Left(JsonRpcError.InternalError)
          case Some(sender) =>
            val tracer = new StructLogTracer(
              enableMemory = config.enableMemory,
              enableStorage = !config.disableStorage,
              limit = config.limit
            )
            val tracingVm = new VM[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage](Some(tracer))
            val evmConfig = EvmConfig.forBlock(block.header.number, blockchainConfig)

            val account = finalWorld
              .getAccount(sender)
              .getOrElse(Account.empty(blockchainConfig.accountStartNonce))
            val worldWithAccount = finalWorld.saveAccount(sender, account)

            val context = ProgramContext[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage](
              stx, block.header, sender, worldWithAccount, evmConfig
            )
            val result = tracingVm.run(context)
            tracer.setResult(result.gasRemaining, result.returnData, result.error.isDefined)

            val structLogs = if (config.disableStack) {
              tracer.getSteps.map(_.copy(stack = Seq.empty))
            } else tracer.getSteps

            Right(DebugTraceTransactionResponse(
              gas = context.startGas - result.gasRemaining,
              failed = result.error.isDefined,
              returnValue = result.returnData,
              structLogs = structLogs
            ))
        }
    }
  }

  private def traceAllBlockTransactions(
      block: Block,
      config: TraceConfig
  ): Either[JsonRpcError, Seq[TxTraceResult]] = {
    blockchainReader.getBlockHeaderByHash(block.header.parentHash) match {
      case None => Left(JsonRpcError.InternalError)
      case Some(parentHeader) =>
        val initialWorld = buildWorldFromParent(block, parentHeader)
        block.body.transactionList.zipWithIndex.foldLeft[Either[JsonRpcError, (InMemoryWorldStateProxy, Seq[TxTraceResult])]](
          Right((initialWorld, Seq.empty))
        ) {
          case (Left(err), _) => Left(err)
          case (Right((world, acc)), (stx, _)) =>
            SignedTransaction.getSender(stx) match {
              case None => Left(JsonRpcError.InternalError)
              case Some(sender) =>
                val tracer = new StructLogTracer(
                  enableMemory = config.enableMemory,
                  enableStorage = !config.disableStorage,
                  limit = config.limit
                )
                val tracingVm = new VM[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage](Some(tracer))
                val evmConfig = EvmConfig.forBlock(block.header.number, blockchainConfig)

                val account = world
                  .getAccount(sender)
                  .getOrElse(Account.empty(blockchainConfig.accountStartNonce))
                val worldWithAccount = world.saveAccount(sender, account)

                val context = ProgramContext[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage](
                  stx, block.header, sender, worldWithAccount, evmConfig
                )
                val result = tracingVm.run(context)
                tracer.setResult(result.gasRemaining, result.returnData, result.error.isDefined)

                val structLogs = if (config.disableStack) {
                  tracer.getSteps.map(_.copy(stack = Seq.empty))
                } else tracer.getSteps

                val txResult = TxTraceResult(
                  txHash = stx.hash,
                  result = DebugTraceTransactionResponse(
                    gas = context.startGas - result.gasRemaining,
                    failed = result.error.isDefined,
                    returnValue = result.returnData,
                    structLogs = structLogs
                  )
                )
                Right((result.world, acc :+ txResult))
            }
        }.map(_._2)
    }
  }

  /** Replay transactions without tracing to advance world state. */
  private def replayPriorTransactions(
      txs: Seq[SignedTransaction],
      blockHeader: BlockHeader,
      world: InMemoryWorldStateProxy
  ): (InMemoryWorldStateProxy, BigInt) = {
    txs.foldLeft((world, BigInt(0))) { case ((w, gasUsed), stx) =>
      SignedTransaction.getSender(stx) match {
        case None => (w, gasUsed)
        case Some(sender) =>
          val evmConfig = EvmConfig.forBlock(blockHeader.number, blockchainConfig)
          val account = w.getAccount(sender).getOrElse(Account.empty(blockchainConfig.accountStartNonce))
          val worldWithAccount = w.saveAccount(sender, account)
          val context = ProgramContext[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage](
            stx, blockHeader, sender, worldWithAccount, evmConfig
          )
          val result = LocalVM.run(context)
          (result.world, gasUsed + (context.startGas - result.gasRemaining))
      }
    }
  }

  private def findTransactionBlock(txHash: ByteString): Option[(Block, Int)] =
    transactionMappingStorage.get(txHash).flatMap { location =>
      blockchainReader.getBlockByHash(location.blockHash).map(block => (block, location.txIndex))
    }

  private def resolveBlock(blockParam: BlockParam): Option[Block] = blockParam match {
    case BlockParam.Pending => None
    case other =>
      val n = BlockParam.resolveNumber(other, blockchainReader.getBestBlockNumber())
      blockchainReader.getBlockHeaderByNumber(n).flatMap(h => blockchainReader.getBlockByHash(h.hash))
  }

  private def buildInitialWorld(block: Block): InMemoryWorldStateProxy =
    InMemoryWorldStateProxy(
      evmCodeStorage = evmCodeStorage,
      blockchain.getBackingMptStorage(block.header.number),
      (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash),
      accountStartNonce = blockchainConfig.accountStartNonce,
      stateRootHash = block.header.stateRoot,
      noEmptyAccounts = EvmConfig.forBlock(block.header.number, blockchainConfig).noEmptyAccounts,
      ethCompatibleStorage = blockchainConfig.ethCompatibleStorage
    )

  private def buildWorldFromParent(block: Block, parentHeader: BlockHeader): InMemoryWorldStateProxy =
    InMemoryWorldStateProxy(
      evmCodeStorage = evmCodeStorage,
      blockchain.getBackingMptStorage(block.header.number),
      (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash),
      accountStartNonce = blockchainConfig.accountStartNonce,
      stateRootHash = parentHeader.stateRoot,
      noEmptyAccounts = EvmConfig.forBlock(block.header.number, blockchainConfig).noEmptyAccounts,
      ethCompatibleStorage = blockchainConfig.ethCompatibleStorage
    )
}
