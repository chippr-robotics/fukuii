package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import cats.effect.IO

import org.json4s.JValue

import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage.TransactionLocation
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.Blockchain
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.GasPrice
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.ledger.StxLedger
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.vm.CallTracer
import com.chipprbots.ethereum.vm.ExecutionTracer
import com.chipprbots.ethereum.vm.PrestateTracer
import com.chipprbots.ethereum.vm.StructLogTracer

/** Service implementing the debug_trace* family of JSON-RPC methods.
  *
  * Besu reference: ethereum/api/src/main/java/org/hyperledger/besu/ethereum/api/jsonrpc/internal/methods/
  *   - DebugTraceTransaction.java — debug_traceTransaction
  *   - DebugTraceBlock.java — debug_traceBlock (by hash)
  *   - DebugTraceBlockByNumber.java — debug_traceBlockByNumber
  *   - DebugTraceCall.java — debug_traceCall
  *
  * core-geth reference: eth/tracers/api.go
  *   - traceTx() — debug_traceTransaction
  *   - traceBlock() — debug_traceBlockByHash / debug_traceBlockByNumber
  *   - TraceCall() — debug_traceCall
  *   - TraceCallMany() — debug_traceCallMany
  *
  * Tracer selection follows core-geth convention: config.tracer absent or "" → StructLogTracer (default, opcode-level
  * structLog) config.tracer = "callTracer" → CallTracer (nested call tree) config.tracer = "prestateTracer" →
  * PrestateTracer (pre-tx state snapshot) any other string → unsupported; return error
  */
object DebugTracingService:

  /** Tracer configuration, mirroring go-ethereum's eth/tracers/logger.Config.
    *
    * Field polarity intentionally mirrors go-ethereum exactly — memory/returnData are opt-IN
    * (default off), stack/storage are opt-OUT (default on). See
    * execution-apis src/schemas/opcode-tracer.yaml `TraceConfig` for the normative field names
    * and defaults; every field here defaults to what go-ethereum returns when a caller sends no
    * config object at all (e.g. `debug_traceBlockByNumber(blockParam)` with no second argument).
    *
    * @param tracer
    *   optional named tracer; absent → default StructLogTracer
    * @param disableStorage
    *   suppress storage snapshots per step (StructLogTracer only). Default false (storage ON).
    * @param enableMemory
    *   include memory snapshots per step (StructLogTracer only). Default false (memory OFF) —
    *   capturing a full word-by-word memory snapshot on every opcode of every transaction is
    *   expensive, so this must stay opt-in to match go-ethereum.
    * @param disableStack
    *   suppress stack snapshots per step (unused; StructLogTracer always records stack)
    * @param enableReturnData
    *   include the most-recent-call return data per step (StructLogTracer only). Parsed for
    *   forward-compatibility with go-ethereum's field name, but not yet wired to a capture path —
    *   StructLogTracer has no per-step return-data buffer today. The field is always absent from
    *   the response regardless of this setting, which is schema-valid either way (execution-apis
    *   marks `returnData` optional even when the caller asks for it). Tracked as a follow-up, not
    *   required by any current fixture.
    */
  case class TraceConfig(
      tracer: Option[String] = None,
      disableStorage: Boolean = false,
      enableMemory: Boolean = false,
      disableStack: Boolean = false,
      enableReturnData: Boolean = false
  )

  case class TraceTransactionRequest(txHash: ByteString, config: TraceConfig = TraceConfig())
  case class TraceTransactionResponse(result: JValue)

  case class TraceCallRequest(call: EthInfoService.CallTx, block: BlockParam, config: TraceConfig = TraceConfig())
  case class TraceCallResponse(result: JValue)

  case class TraceCallManyRequest(calls: Seq[(EthInfoService.CallTx, TraceConfig)], block: BlockParam)
  case class TraceCallManyResponse(results: Seq[JValue])

  case class TraceBlockByHashRequest(blockHash: ByteString, config: TraceConfig = TraceConfig())
  case class TraceBlockByHashResponse(results: Seq[TxTraceResult])

  case class TraceBlockByNumberRequest(block: BlockParam, config: TraceConfig = TraceConfig())
  case class TraceBlockByNumberResponse(results: Seq[TxTraceResult])

  /** One transaction's trace result within a block-level trace (debug_traceBlockByHash / debug_traceBlockByNumber). The
    * execution-apis / hive openrpc-tracer.json schema requires each array entry to carry BOTH `txHash` and `result` —
    * go-ethereum's traceBlock wraps every per-tx trace the same way so callers can correlate a result with the
    * transaction that produced it without re-deriving hashes.
    */
  case class TxTraceResult(txHash: ByteString, result: JValue)

  /** debug_intermediateRoots params — block hash, optional trace config (ignored for root computation). */
  case class IntermediateRootsRequest(blockHash: ByteString, config: TraceConfig = TraceConfig())
  case class IntermediateRootsResponse(roots: Seq[ByteString])

  /** debug_traceChain params — block range + optional tracer config. Only valid over WebSocket (method returns a
    * subscription ID).
    */
  case class TraceChainRequest(fromBlock: BlockParam, toBlock: BlockParam, config: TraceConfig = TraceConfig())
  case class TraceChainBlockResult(block: BigInt, blockHash: ByteString, traces: Seq[JValue])

class DebugTracingService(
    val blockchain: Blockchain,
    val blockchainReader: BlockchainReader,
    val mining: Mining,
    stxLedger: StxLedger,
    transactionMappingStorage: TransactionMappingStorage
) extends ResolveBlock:

  import DebugTracingService.*

  implicit private val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig

  /** Implements debug_traceTransaction.
    *
    * Besu reference: DebugTraceTransaction.java — looks up block, replays prior txs via
    * BlockReplay.beforeTransactionInBlock(), then runs the target tx with DebugOperationTracer.
    *
    * core-geth reference: api.go TraceTransaction() → traceTx().
    *
    * Algorithm:
    *   1. Look up block containing txHash via TransactionMappingStorage 2. Recover senders for all txs in block 3.
    *      Advance world state to txIndex via advanceWorldToTx (replay of prior txs) 4. Run the target tx with the
    *      chosen tracer 5. Return tracer.getResult
    */
  def traceTransaction(req: TraceTransactionRequest): ServiceResponse[TraceTransactionResponse] =
    IO {
      for
        location <- transactionMappingStorage
          .get(req.txHash)
          .toRight(JsonRpcError.LogicError("Transaction not found"))
        TransactionLocation(blockHash, txIndex) = location
        block <- blockchainReader
          .getBlockByHash(BlockHash(blockHash))
          .toRight(JsonRpcError.LogicError(s"Block not found for hash ${blockHash.toHex}"))
        parentHeader <- blockchainReader
          .getBlockHeaderByHash(block.header.parentHash)
          .toRight(JsonRpcError.LogicError("Parent block not found"))
        stxs = SignedTransactionWithSender.getSignedTransactions(block.body.transactionList)
        _ <- Either.cond(
          txIndex >= 0 && txIndex < stxs.length,
          (),
          JsonRpcError.InvalidParams(s"Transaction index $txIndex out of range")
        )
        targetStx = stxs(txIndex)
        world = stxLedger.advanceWorldToTx(block.header, stxs, txIndex, parentHeader.stateRoot.value)
        tracer = selectTracer(req.config, Some(world))
        _ = stxLedger.simulateTransactionWithTracer(targetStx, block.header, Some(world), tracer)
      yield TraceTransactionResponse(tracer.getResult)
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }

  /** Implements debug_traceCall.
    *
    * Besu reference: DebugTraceCall.java — uses BlockReplay to obtain world state at the given block tag, constructs a
    * synthetic SignedTransactionWithSender, then calls processTracing.
    *
    * core-geth reference: api.go TraceCall() → traceTx().
    */
  def traceCall(req: TraceCallRequest): ServiceResponse[TraceCallResponse] =
    IO {
      for
        resolved <- resolveBlock(req.block)
        stx <- buildCallTx(req.call, resolved.block)
        world = resolved.pendingState
        tracer = selectTracer(req.config, world)
        _ = stxLedger.simulateTransactionWithTracer(stx, resolved.block.header, world, tracer)
      yield TraceCallResponse(tracer.getResult)
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }

  /** Implements debug_traceCallMany.
    *
    * core-geth reference: api.go TraceCallMany() — executes a list of (call, traceConfig) pairs sequentially against
    * the same block context, threading world state between calls.
    *
    * Besu does not implement this method; core-geth alignment only.
    */
  def traceCallMany(req: TraceCallManyRequest): ServiceResponse[TraceCallManyResponse] =
    IO {
      for resolved <- resolveBlock(req.block)
      yield
        // Execute calls sequentially. World state is not threaded (each call sees
        // the block's state), matching core-geth TraceCallMany behaviour.
        val results: Seq[JValue] = req.calls.map { case (callTx, config) =>
          buildCallTx(callTx, resolved.block)
            .map { stx =>
              val world = resolved.pendingState
              val tracer = selectTracer(config, world)
              stxLedger.simulateTransactionWithTracer(stx, resolved.block.header, world, tracer)
              tracer.getResult
            }
            .getOrElse(org.json4s.JNull)
        }
        TraceCallManyResponse(results)
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }

  /** Implements debug_traceBlockByHash.
    *
    * Besu reference: DebugTraceBlock.java — iterates block transactions, for each one calls
    * BlockReplay.beforeTransactionInBlock() then processTracing.
    *
    * core-geth reference: api.go traceBlock() — calls traceTx() per transaction.
    */
  def traceBlockByHash(req: TraceBlockByHashRequest): ServiceResponse[TraceBlockByHashResponse] =
    IO {
      for
        block <- blockchainReader
          .getBlockByHash(BlockHash(req.blockHash))
          .toRight(JsonRpcError.LogicError(s"Block not found for hash ${req.blockHash.toHex}"))
        result <- traceAllTxsInBlock(block, req.config)
      yield TraceBlockByHashResponse(result)
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
      for
        resolved <- resolveBlock(req.block)
        result <- traceAllTxsInBlock(resolved.block, req.config)
      yield TraceBlockByNumberResponse(result)
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }

  // ─── Helpers ──────────────────────────────────────────────────────────────────

  /** Replays all transactions in a block, returning one trace result per tx, each tagged with its tx hash.
    *
    * Threads the world state forward tx-by-tx instead of calling advanceWorldToTx per index: advanceWorldToTx replays
    * every prior tx from the parent state root, so calling it once per index makes this method O(n^2) in the
    * transaction count. simulateTransactionWithTracer already returns the post-tx world in TxResult.worldState, so we
    * carry that into the next iteration and only build the genuine parent-state world once (matches core-geth's
    * traceBlock, which steps one statedb forward). advanceWorldToTx itself is untouched — traceTransaction legitimately
    * uses it for a single index.
    */
  private def traceAllTxsInBlock(block: Block, config: TraceConfig): Either[JsonRpcError, Seq[TxTraceResult]] =
    blockchainReader
      .getBlockHeaderByHash(block.header.parentHash)
      .toRight(JsonRpcError.LogicError("Parent block header not found"))
      .map { parentHeader =>
        val stxs = SignedTransactionWithSender.getSignedTransactions(block.body.transactionList)
        if stxs.isEmpty then Seq.empty
        else
          var currentWorld = stxLedger.advanceWorldToTx(block.header, stxs, 0, parentHeader.stateRoot.value)
          val resultsBuf = scala.collection.mutable.ArrayBuffer[TxTraceResult]()
          stxs.foreach { stx =>
            val tracer = selectTracer(config, Some(currentWorld))
            val txResult = stxLedger.simulateTransactionWithTracer(stx, block.header, Some(currentWorld), tracer)
            resultsBuf += TxTraceResult(stx.tx.hash.value, tracer.getResult)
            currentWorld = txResult.worldState
          }
          resultsBuf.toSeq
      }

  /** Selects and constructs a tracer based on config.tracer.
    *
    * core-geth reference: tracers/tracers.go — defaultTracer, "callTracer", "prestateTracer".
    *
    * StructLogTracer is the default (absent or empty tracer name), matching Besu and core-geth. PrestateTracer requires
    * a pre-execution world snapshot; supply via the preWorld param.
    *
    * @param config
    *   trace configuration selecting the tracer type
    * @param preWorld
    *   pre-execution world state (required for prestateTracer, ignored otherwise)
    */
  private def selectTracer(
      config: TraceConfig,
      preWorld: Option[com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy]
  ): ExecutionTracer =
    config.tracer.filterNot(_.isEmpty) match
      case None | Some("structLogger") =>
        new StructLogTracer(
          enableMemory = config.enableMemory,
          enableStorage = !config.disableStorage
        )
      case Some("callTracer") =>
        new CallTracer(onlyTopCall = false)
      case Some("prestateTracer") =>
        // PrestateTracer requires the pre-execution world; fall back to StructLogTracer if unavailable
        preWorld match
          case Some(world) =>
            new PrestateTracer[
              com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy,
              com.chipprbots.ethereum.ledger.InMemoryWorldStateProxyStorage
            ](world)
          case None =>
            new StructLogTracer(
              enableMemory = config.enableMemory,
              enableStorage = !config.disableStorage
            )
      case Some(_) =>
        // Unsupported tracer name — fall back to StructLogTracer
        new StructLogTracer(
          enableMemory = config.enableMemory,
          enableStorage = !config.disableStorage
        )

  /** Implements debug_intermediateRoots.
    *
    * core-geth reference: eth/tracers/api.go IntermediateRoots() — for each tx in block, execute it and call
    * statedb.IntermediateRoot(deleteEmptyObjects) to get the state root after that tx. Returns [] if block is genesis.
    * Returns partial list if a tx errors (non-fatal per geth).
    *
    * Besu: no direct equivalent — this is a geth/ETC-specific debug method.
    *
    * Algorithm:
    *   1. Resolve block by hash (also check bad-block store — not implemented, skip) 2. Get parent block state root 3.
    *      For each tx: simulate → capture worldState.stateRootHash (equivalent to statedb.IntermediateRoot) 4. Return
    *      list of ByteString roots (one per tx)
    */
  def intermediateRoots(req: IntermediateRootsRequest): ServiceResponse[IntermediateRootsResponse] =
    IO {
      for
        block <- blockchainReader
          .getBlockByHash(BlockHash(req.blockHash))
          .toRight(JsonRpcError.LogicError(s"Block not found for hash ${req.blockHash.toHex}"))
        _ <- Either.cond(
          block.header.number.value > 0,
          (),
          JsonRpcError.LogicError("Genesis block is not traceable")
        )
        parentHeader <- blockchainReader
          .getBlockHeaderByHash(block.header.parentHash)
          .toRight(JsonRpcError.LogicError("Parent block header not found"))
        stxs = SignedTransactionWithSender.getSignedTransactions(block.body.transactionList)
        roots =
          if stxs.isEmpty then Seq.empty
          else
            // Chain world states tx-by-tx and capture state root after each finalization.
            // On tx error: return partial result (same as core-geth — errors on canon blocks are rare).
            var currentWorld = stxLedger.advanceWorldToTx(block.header, stxs, 0, parentHeader.stateRoot.value)
            val rootBuf = scala.collection.mutable.ArrayBuffer[ByteString]()
            stxs.foreach { stx =>
              val txResult = stxLedger.simulateTransaction(stx, block.header, Some(currentWorld))
              // stateRootHash computes the MPT root of the in-memory trie (equivalent to IntermediateRoot)
              rootBuf += txResult.worldState.stateRootHash
              currentWorld = txResult.worldState
            }
            rootBuf.toSeq
      yield IntermediateRootsResponse(roots)
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }

  /** Returns traced results for all blocks in [fromBlock+1, toBlock], one entry per block. Used by debug_traceChain
    * (streaming via WebSocket).
    *
    * core-geth reference: eth/tracers/api.go traceChain() — parallel goroutines per block. We use sequential execution
    * here to avoid concurrent world-state access.
    */
  def traceChainBlockRange(
      fromBlock: BlockParam,
      toBlock: BlockParam,
      config: TraceConfig
  ): IO[Either[JsonRpcError, Seq[TraceChainBlockResult]]] =
    IO {
      for
        fromResolved <- resolveBlock(fromBlock)
        toResolved <- resolveBlock(toBlock)
        fromNum = fromResolved.block.header.number.toLong
        toNum = toResolved.block.header.number.toLong
        _ <- Either.cond(toNum > fromNum, (), JsonRpcError.InvalidParams("end block must come after start block"))
        results = ((fromNum + 1) to toNum).flatMap { blockNum =>
          val branch = blockchainReader.getBestBranch
          blockchainReader.getBlockByNumber(branch, blockNum).flatMap { block =>
            blockchainReader.getBlockHeaderByHash(block.header.parentHash).map { parentHeader =>
              val stxs = SignedTransactionWithSender.getSignedTransactions(block.body.transactionList)
              val traces = stxs.zipWithIndex.map { case (stx, txIndex) =>
                val world = stxLedger.advanceWorldToTx(block.header, stxs, txIndex, parentHeader.stateRoot.value)
                val tracer = selectTracer(config, Some(world))
                stxLedger.simulateTransactionWithTracer(stx, block.header, Some(world), tracer)
                tracer.getResult
              }
              TraceChainBlockResult(block.header.number.value, block.header.hash.value, traces)
            }
          }
        }
      yield results
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }

  /** Builds a synthetic SignedTransactionWithSender from a CallTx (used in traceCall). */
  private def buildCallTx(
      callTx: EthInfoService.CallTx,
      block: Block
  ): Either[JsonRpcError, SignedTransactionWithSender] =
    import com.chipprbots.ethereum.domain.LegacyTransaction
    import com.chipprbots.ethereum.crypto.ECDSASignature

    val gasLimit = callTx.gas.map(GasAmount(_)).getOrElse(block.header.gasLimit)
    val fromAddress = callTx.from
      .map(Address.apply)
      .getOrElse(Address(0))
    val toAddress = callTx.to.map(Address.apply)

    val tx = LegacyTransaction(0, GasPrice(callTx.gasPrice), gasLimit, toAddress, callTx.value, callTx.data)
    val fakeSignature = ECDSASignature(0, 0, 0)
    Right(SignedTransactionWithSender(tx, fakeSignature, fromAddress))
