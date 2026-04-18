package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import cats.effect.IO

import org.json4s.JValue

import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage.TransactionLocation
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.Blockchain
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.ledger.StxLedger
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.vm.CallTracer
import com.chipprbots.ethereum.vm.ExecutionTracer
import com.chipprbots.ethereum.vm.PrestateTracer
import com.chipprbots.ethereum.vm.StructLogTracer

/** Service implementing the debug_trace* family of JSON-RPC methods.
  *
  * Besu reference: ethereum/api/src/main/java/org/hyperledger/besu/ethereum/api/jsonrpc/internal/methods/
  *   - DebugTraceTransaction.java — debug_traceTransaction
  *   - DebugTraceBlock.java        — debug_traceBlock (by hash)
  *   - DebugTraceBlockByNumber.java — debug_traceBlockByNumber
  *   - DebugTraceCall.java          — debug_traceCall
  *
  * core-geth reference: eth/tracers/api.go
  *   - traceTx()               — debug_traceTransaction
  *   - traceBlock()            — debug_traceBlockByHash / debug_traceBlockByNumber
  *   - TraceCall()             — debug_traceCall
  *   - TraceCallMany()         — debug_traceCallMany
  *
  * Tracer selection follows core-geth convention:
  *   config.tracer absent or ""  → StructLogTracer (default, opcode-level structLog)
  *   config.tracer = "callTracer"     → CallTracer (nested call tree)
  *   config.tracer = "prestateTracer" → PrestateTracer (pre-tx state snapshot)
  *   any other string                 → unsupported; return error
  */
object DebugTracingService {

  /** Tracer configuration, mirroring go-ethereum tracers.TraceConfig.
    *
    * @param tracer         optional named tracer; absent → default StructLogTracer
    * @param disableStorage suppress storage snapshots per step (StructLogTracer only)
    * @param disableMemory  suppress memory snapshots per step (StructLogTracer only)
    * @param disableStack   suppress stack snapshots per step (unused; StructLogTracer always records stack)
    */
  case class TraceConfig(
      tracer: Option[String] = None,
      disableStorage: Boolean = false,
      disableMemory: Boolean = false,
      disableStack: Boolean = false
  )

  case class TraceTransactionRequest(txHash: ByteString, config: TraceConfig = TraceConfig())
  case class TraceTransactionResponse(result: JValue)

  case class TraceCallRequest(call: EthInfoService.CallTx, block: BlockParam, config: TraceConfig = TraceConfig())
  case class TraceCallResponse(result: JValue)

  case class TraceCallManyRequest(calls: Seq[(EthInfoService.CallTx, TraceConfig)], block: BlockParam)
  case class TraceCallManyResponse(results: Seq[JValue])

  case class TraceBlockByHashRequest(blockHash: ByteString, config: TraceConfig = TraceConfig())
  case class TraceBlockByHashResponse(results: Seq[JValue])

  case class TraceBlockByNumberRequest(block: BlockParam, config: TraceConfig = TraceConfig())
  case class TraceBlockByNumberResponse(results: Seq[JValue])
}

class DebugTracingService(
    val blockchain: Blockchain,
    val blockchainReader: BlockchainReader,
    val mining: Mining,
    stxLedger: StxLedger,
    transactionMappingStorage: TransactionMappingStorage
) extends ResolveBlock {

  import DebugTracingService._

  implicit private val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig

  /** Implements debug_traceTransaction.
    *
    * Besu reference: DebugTraceTransaction.java — looks up block, replays prior txs via
    *   BlockReplay.beforeTransactionInBlock(), then runs the target tx with DebugOperationTracer.
    *
    * core-geth reference: api.go TraceTransaction() → traceTx().
    *
    * Algorithm:
    *   1. Look up block containing txHash via TransactionMappingStorage
    *   2. Recover senders for all txs in block
    *   3. Advance world state to txIndex via advanceWorldToTx (replay of prior txs)
    *   4. Run the target tx with the chosen tracer
    *   5. Return tracer.getResult
    */
  def traceTransaction(req: TraceTransactionRequest): ServiceResponse[TraceTransactionResponse] =
    IO {
      for {
        location <- transactionMappingStorage.get(req.txHash)
          .toRight(JsonRpcError.InvalidParams("Transaction not found"))
        TransactionLocation(blockHash, txIndex) = location
        block <- blockchainReader.getBlockByHash(blockHash)
          .toRight(JsonRpcError.InvalidParams(s"Block not found for hash ${blockHash.toHex}"))
        parentHeader <- blockchainReader.getBlockHeaderByHash(block.header.parentHash)
          .toRight(JsonRpcError.InvalidParams("Parent block not found"))
        stxs = SignedTransactionWithSender.getSignedTransactions(block.body.transactionList)
        _ <- Either.cond(
          txIndex >= 0 && txIndex < stxs.length,
          (),
          JsonRpcError.InvalidParams(s"Transaction index $txIndex out of range")
        )
        targetStx = stxs(txIndex)
        world = stxLedger.advanceWorldToTx(block.header, stxs, txIndex, parentHeader.stateRoot)
        tracer = selectTracer(req.config, Some(world))
        _ = stxLedger.simulateTransactionWithTracer(targetStx, block.header, Some(world), tracer)
      } yield TraceTransactionResponse(tracer.getResult)
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }

  /** Implements debug_traceCall.
    *
    * Besu reference: DebugTraceCall.java — uses BlockReplay to obtain world state at the given
    *   block tag, constructs a synthetic SignedTransactionWithSender, then calls processTracing.
    *
    * core-geth reference: api.go TraceCall() → traceTx().
    */
  def traceCall(req: TraceCallRequest): ServiceResponse[TraceCallResponse] =
    IO {
      for {
        resolved <- resolveBlock(req.block)
        stx <- buildCallTx(req.call, resolved.block)
        world = resolved.pendingState
        tracer = selectTracer(req.config, world)
        _ = stxLedger.simulateTransactionWithTracer(stx, resolved.block.header, world, tracer)
      } yield TraceCallResponse(tracer.getResult)
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }

  /** Implements debug_traceCallMany.
    *
    * core-geth reference: api.go TraceCallMany() — executes a list of (call, traceConfig) pairs
    *   sequentially against the same block context, threading world state between calls.
    *
    * Besu does not implement this method; core-geth alignment only.
    */
  def traceCallMany(req: TraceCallManyRequest): ServiceResponse[TraceCallManyResponse] =
    IO {
      for {
        resolved <- resolveBlock(req.block)
      } yield {
        // Execute calls sequentially. World state is not threaded (each call sees
        // the block's state), matching core-geth TraceCallMany behaviour.
        val results: Seq[JValue] = req.calls.map { case (callTx, config) =>
          buildCallTx(callTx, resolved.block).map { stx =>
            val world  = resolved.pendingState
            val tracer = selectTracer(config, world)
            stxLedger.simulateTransactionWithTracer(stx, resolved.block.header, world, tracer)
            tracer.getResult
          }.getOrElse(org.json4s.JNull)
        }
        TraceCallManyResponse(results)
      }
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }

  /** Implements debug_traceBlockByHash.
    *
    * Besu reference: DebugTraceBlock.java — iterates block transactions, for each one
    *   calls BlockReplay.beforeTransactionInBlock() then processTracing.
    *
    * core-geth reference: api.go traceBlock() — calls traceTx() per transaction.
    */
  def traceBlockByHash(req: TraceBlockByHashRequest): ServiceResponse[TraceBlockByHashResponse] =
    IO {
      for {
        block <- blockchainReader.getBlockByHash(req.blockHash)
          .toRight(JsonRpcError.InvalidParams(s"Block not found for hash ${req.blockHash.toHex}"))
        result <- traceAllTxsInBlock(block, req.config)
      } yield TraceBlockByHashResponse(result)
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }

  /** Implements debug_traceBlockByNumber.
    *
    * Besu reference: DebugTraceBlockByNumber.java — same as DebugTraceBlock but resolves by number.
    *
    * core-geth reference: api.go TraceBlockByNumber() → traceBlock().
    */
  def traceBlockByNumber(req: TraceBlockByNumberRequest): ServiceResponse[TraceBlockByNumberResponse] =
    IO {
      for {
        resolved <- resolveBlock(req.block)
        result <- traceAllTxsInBlock(resolved.block, req.config)
      } yield TraceBlockByNumberResponse(result)
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }

  // ─── Helpers ──────────────────────────────────────────────────────────────────

  /** Replays all transactions in a block, returning one trace result per tx.
    *
    * Each tx is traced independently with a fresh tracer, using advanceWorldToTx to
    * reproduce the exact world state the tx saw on-chain.
    */
  private def traceAllTxsInBlock(block: Block, config: TraceConfig): Either[JsonRpcError, Seq[JValue]] =
    blockchainReader.getBlockHeaderByHash(block.header.parentHash)
      .toRight(JsonRpcError.InvalidParams("Parent block header not found"))
      .map { parentHeader =>
        val stxs = SignedTransactionWithSender.getSignedTransactions(block.body.transactionList)
        stxs.zipWithIndex.map { case (stx, txIndex) =>
          val world  = stxLedger.advanceWorldToTx(block.header, stxs, txIndex, parentHeader.stateRoot)
          val tracer = selectTracer(config, Some(world))
          stxLedger.simulateTransactionWithTracer(stx, block.header, Some(world), tracer)
          tracer.getResult
        }
      }

  /** Selects and constructs a tracer based on config.tracer.
    *
    * core-geth reference: tracers/tracers.go — defaultTracer, "callTracer", "prestateTracer".
    *
    * StructLogTracer is the default (absent or empty tracer name), matching Besu and core-geth.
    * PrestateTracer requires a pre-execution world snapshot; supply via the preWorld param.
    *
    * @param config    trace configuration selecting the tracer type
    * @param preWorld  pre-execution world state (required for prestateTracer, ignored otherwise)
    */
  private def selectTracer(
      config: TraceConfig,
      preWorld: Option[com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy] = None
  ): ExecutionTracer =
    config.tracer.filterNot(_.isEmpty) match {
      case None | Some("structLogger") =>
        new StructLogTracer(
          enableMemory  = !config.disableMemory,
          enableStorage = !config.disableStorage
        )
      case Some("callTracer") =>
        new CallTracer(onlyTopCall = false)
      case Some("prestateTracer") =>
        // PrestateTracer requires the pre-execution world; fall back to StructLogTracer if unavailable
        preWorld match {
          case Some(world) =>
            new PrestateTracer[
              com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy,
              com.chipprbots.ethereum.ledger.InMemoryWorldStateProxyStorage
            ](world)
          case None =>
            new StructLogTracer(
              enableMemory  = !config.disableMemory,
              enableStorage = !config.disableStorage
            )
        }
      case Some(_) =>
        // Unsupported tracer name — fall back to StructLogTracer
        new StructLogTracer(
          enableMemory  = !config.disableMemory,
          enableStorage = !config.disableStorage
        )
    }

  /** Builds a synthetic SignedTransactionWithSender from a CallTx (used in traceCall). */
  private def buildCallTx(
      callTx: EthInfoService.CallTx,
      block: Block
  ): Either[JsonRpcError, SignedTransactionWithSender] = {
    import com.chipprbots.ethereum.domain.LegacyTransaction
    import com.chipprbots.ethereum.crypto.ECDSASignature

    val gasLimit = callTx.gas.getOrElse(block.header.gasLimit)
    val fromAddress = callTx.from
      .map(Address.apply)
      .getOrElse(Address(0))
    val toAddress = callTx.to.map(Address.apply)

    val tx = LegacyTransaction(0, callTx.gasPrice, gasLimit, toAddress, callTx.value, callTx.data)
    val fakeSignature = ECDSASignature(0, 0, 0)
    Right(SignedTransactionWithSender(tx, fakeSignature, fromAddress))
  }
}
