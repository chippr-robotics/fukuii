package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import cats.effect.IO

import org.json4s.JsonAST._
import org.json4s.MonadicJValue.jvalueToMonadic

import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.ledger._
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.vm._

object DebugTracingService {

  /** Tracing options matching go-ethereum's TracerConfig.
    * @param tracer
    *   native tracer name: "callTracer", "prestateTracer", or None for default structLog
    * @param tracerConfig
    *   tracer-specific options as raw JSON (e.g., {"onlyTopCall": true} for callTracer)
    */
  case class TraceConfig(
      disableStack: Boolean = false,
      disableStorage: Boolean = true,
      enableMemory: Boolean = false,
      enableReturnData: Boolean = false,
      limit: Int = 0,
      tracer: Option[String] = None,
      tracerConfig: Option[JValue] = None
  )

  case class DebugTraceTransactionRequest(txHash: ByteString, config: TraceConfig = TraceConfig())
  case class DebugTraceTransactionResponse(
      gas: BigInt,
      failed: Boolean,
      returnValue: ByteString,
      structLogs: Seq[StructLog],
      nativeResult: Option[JValue] = None
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
      structLogs: Seq[StructLog],
      nativeResult: Option[JValue] = None
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
        val world = buildInitialWorld(block)
        val executionTracer = createTracer(req.config, world)
        val tracingVm = new VM[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage](Some(executionTracer))
        val evmConfig = EvmConfig.forBlock(block.header.number, blockchainConfig)

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

        // Fire top-level hooks
        executionTracer.onTxStart(from, to, gasLimit, value, data)
        val result = tracingVm.run(context)
        val gasUsed = context.startGas - result.gasRemaining
        executionTracer.onTxEnd(gasUsed, result.returnData, result.error.map(_.toString))

        // Handle prestateTracer diffMode
        executionTracer match {
          case pt: PrestateTracer[_, _] => pt.setPostWorld(result.world.asInstanceOf[pt.preWorld.type])
          case _ => ()
        }

        buildResponse[DebugTraceCallResponse](executionTracer, req.config, context.startGas, result) { (gas, failed, rv, logs) =>
          DebugTraceCallResponse(gas, failed, rv, logs)
        } { nativeJson =>
          DebugTraceCallResponse(gasUsed, result.error.isDefined, result.returnData, Seq.empty, Some(nativeJson))
        }
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

  /** Create the appropriate tracer based on TraceConfig. */
  private def createTracer(
      config: TraceConfig,
      world: InMemoryWorldStateProxy
  ): ExecutionTracer = {
    config.tracer match {
      case Some("callTracer") =>
        val onlyTopCall = config.tracerConfig
          .flatMap { c =>
            (c \ "onlyTopCall") match {
              case JBool(v) => Some(v)
              case _ => None
            }
          }
          .getOrElse(false)
        new CallTracer(onlyTopCall)

      case Some("prestateTracer") =>
        val diffMode = config.tracerConfig
          .flatMap { c =>
            (c \ "diffMode") match {
              case JBool(v) => Some(v)
              case _ => None
            }
          }
          .getOrElse(false)
        new PrestateTracer(world, diffMode)

      case None | Some("") =>
        new StructLogTracer(
          enableMemory = config.enableMemory,
          enableStorage = !config.disableStorage,
          limit = config.limit
        )

      case Some(unsupported) =>
        throw new IllegalArgumentException(s"Unsupported tracer: $unsupported")
    }
  }

  /** Build a trace response, polymorphic on tracer type. */
  private def buildResponse[R](
      executionTracer: ExecutionTracer,
      config: TraceConfig,
      startGas: BigInt,
      result: ProgramResult[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage]
  )(structLogBuilder: (BigInt, Boolean, ByteString, Seq[StructLog]) => R)(
      nativeBuilder: JValue => R
  ): Either[JsonRpcError, R] = {
    config.tracer match {
      case Some("callTracer") | Some("prestateTracer") =>
        Right(nativeBuilder(executionTracer.getResult))

      case _ =>
        val slt = executionTracer.asInstanceOf[StructLogTracer]
        slt.setResult(result.gasRemaining, result.returnData, result.error.isDefined)
        val structLogs = if (config.disableStack) {
          slt.getSteps.map(_.copy(stack = Seq.empty))
        } else slt.getSteps
        Right(structLogBuilder(
          startGas - result.gasRemaining,
          result.error.isDefined,
          result.returnData,
          structLogs
        ))
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
        val (finalWorld, _) = replayPriorTransactions(block.body.transactionList.take(txIndex), block.header, world)

        val stx = block.body.transactionList(txIndex)
        SignedTransaction.getSender(stx) match {
          case None => Left(JsonRpcError.InternalError)
          case Some(sender) =>
            val account = finalWorld
              .getAccount(sender)
              .getOrElse(Account.empty(blockchainConfig.accountStartNonce))
            val worldWithAccount = finalWorld.saveAccount(sender, account)

            val executionTracer = createTracer(config, worldWithAccount)
            val tracingVm = new VM[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage](Some(executionTracer))
            val evmConfig = EvmConfig.forBlock(block.header.number, blockchainConfig)

            val context = ProgramContext[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage](
              stx, block.header, sender, worldWithAccount, evmConfig
            )

            val to = stx.tx.receivingAddress
            executionTracer.onTxStart(sender, to, context.startGas, stx.tx.value, stx.tx.payload)
            val result = tracingVm.run(context)
            val gasUsed = context.startGas - result.gasRemaining
            executionTracer.onTxEnd(gasUsed, result.returnData, result.error.map(_.toString))

            executionTracer match {
              case pt: PrestateTracer[_, _] => pt.setPostWorld(result.world.asInstanceOf[pt.preWorld.type])
              case _ => ()
            }

            buildResponse[DebugTraceTransactionResponse](executionTracer, config, context.startGas, result) { (gas, failed, rv, logs) =>
              DebugTraceTransactionResponse(gas, failed, rv, logs)
            } { nativeJson =>
              DebugTraceTransactionResponse(gasUsed, result.error.isDefined, result.returnData, Seq.empty, Some(nativeJson))
            }
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
                val account = world
                  .getAccount(sender)
                  .getOrElse(Account.empty(blockchainConfig.accountStartNonce))
                val worldWithAccount = world.saveAccount(sender, account)

                val executionTracer = createTracer(config, worldWithAccount)
                val tracingVm = new VM[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage](Some(executionTracer))
                val evmConfig = EvmConfig.forBlock(block.header.number, blockchainConfig)

                val context = ProgramContext[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage](
                  stx, block.header, sender, worldWithAccount, evmConfig
                )

                val to = stx.tx.receivingAddress
                executionTracer.onTxStart(sender, to, context.startGas, stx.tx.value, stx.tx.payload)
                val result = tracingVm.run(context)
                val gasUsed = context.startGas - result.gasRemaining
                executionTracer.onTxEnd(gasUsed, result.returnData, result.error.map(_.toString))

                executionTracer match {
                  case pt: PrestateTracer[_, _] => pt.setPostWorld(result.world.asInstanceOf[pt.preWorld.type])
                  case _ => ()
                }

                val txResultEither = buildResponse[DebugTraceTransactionResponse](executionTracer, config, context.startGas, result) { (gas, failed, rv, logs) =>
                  DebugTraceTransactionResponse(gas, failed, rv, logs)
                } { nativeJson =>
                  DebugTraceTransactionResponse(gasUsed, result.error.isDefined, result.returnData, Seq.empty, Some(nativeJson))
                }

                txResultEither match {
                  case Left(err) => Left(err)
                  case Right(txResponse) =>
                    Right((result.world, acc :+ TxTraceResult(txHash = stx.hash, result = txResponse)))
                }
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
