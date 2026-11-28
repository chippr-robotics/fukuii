package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import cats.effect.IO

import org.json4s.JValue

import com.chipprbots.ethereum.consensus.engine.ForkChoiceManager
import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.Blockchain
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.StxLedger
import com.chipprbots.ethereum.ledger.TxResult
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteStringUtils.toHex
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

  /** The most blocks one debug_traceChain answers (`toBlock - fromBlock`). go-ethereum streams the range over a
    * subscription and holds no more than a bounded number of blocks in flight; this node answers in one response, with
    * every block's trace in memory, and a structLog or callTracer trace is far larger than the flat Parity trace of
    * trace_filter (which caps at 1000). So the cap is lower: 100 blocks. A longer range is asked for in pieces.
    */
  val MaxTraceChainRange: Long = 100L

  /** Tracer configuration, mirroring go-ethereum's eth/tracers/logger.Config.
    *
    * Field polarity intentionally mirrors go-ethereum exactly — memory/returnData are opt-IN (default off),
    * stack/storage are opt-OUT (default on). See execution-apis src/schemas/opcode-tracer.yaml `TraceConfig` for the
    * normative field names and defaults; every field here defaults to what go-ethereum returns when a caller sends no
    * config object at all (e.g. `debug_traceBlockByNumber(blockParam)` with no second argument).
    *
    * @param tracer
    *   optional named tracer; absent → default StructLogTracer
    * @param disableStorage
    *   suppress storage snapshots per step (StructLogTracer only). Default false (storage ON).
    * @param enableMemory
    *   include memory snapshots per step (StructLogTracer only). Default false (memory OFF) — capturing a full
    *   word-by-word memory snapshot on every opcode of every transaction is expensive, so this must stay opt-in to
    *   match go-ethereum.
    * @param disableStack
    *   suppress stack snapshots per step (unused; StructLogTracer always records stack)
    * @param enableReturnData
    *   include the most-recent-call return data per step (StructLogTracer only). Parsed for forward-compatibility with
    *   go-ethereum's field name, but not yet wired to a capture path — StructLogTracer has no per-step return-data
    *   buffer today. The field is always absent from the response regardless of this setting, which is schema-valid
    *   either way (execution-apis marks `returnData` optional even when the caller asks for it). Tracked as a
    *   follow-up, not required by any current fixture.
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

  case class TraceCallRequest(call: TxArgs, block: BlockParam, config: TraceConfig = TraceConfig())
  case class TraceCallResponse(result: JValue)

  case class TraceCallManyRequest(calls: Seq[(TxArgs, TraceConfig)], block: BlockParam)
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
    transactionMappingStorage: TransactionMappingStorage,
    // go-ethereum --rpc.gascap, as for eth_call (TraceCall runs CallDefaults with the backend's RPCGasCap)
    gasCap: BigInt = CallTxBuilder.DefaultGasCap,
    // the rules of the chain the blocks are replayed under: the node's, unless a caller serves another chain
    chainConfig: BlockchainConfig = Config.blockchains.blockchainConfig,
    private val _forkChoiceManagerOpt: Option[ForkChoiceManager] = None
) extends ResolveBlock:

  // "safe" and "finalized" are the consensus client's, as in EthBlocksService (B-489)
  final override def forkChoiceManagerOpt: Option[ForkChoiceManager] = _forkChoiceManagerOpt

  import DebugTracingService.*

  implicit private val blockchainConfig: BlockchainConfig = chainConfig

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
    StateRead
      .reading {
        for
          found <- CanonicalTxLookup(transactionMappingStorage, blockchainReader, req.txHash)
            .toRight(JsonRpcError.LogicError("Transaction not found"))
          CanonicalTxLookup.Found(block, txIndex) = found
          parentHeader <- blockchainReader
            .getBlockHeaderByHash(block.header.parentHash)
            .toRight(JsonRpcError.LogicError("Parent block not found"))
          stxs = SignedTransactionWithSender.getSignedTransactionsOfBlock(block.header, block.body.transactionList)
          _ <- Either.cond(
            txIndex >= 0 && txIndex < stxs.length,
            (),
            JsonRpcError.InvalidParams(s"Transaction index $txIndex out of range")
          )
          targetStx = stxs(txIndex)
          world = stxLedger.advanceWorldToTx(block.header, stxs, txIndex, parentHeader)
          tracer = selectTracer(req.config, Some(world))
          _ = stxLedger.replayTransaction(targetStx, block.header, world, Some(tracer))
        yield TraceTransactionResponse(tracer.getResult)
      }
      .recover { case _: MissingNodeException =>
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
    StateRead
      .reading {
        for
          resolved <- resolveBlock(req.block)
          tracer = selectTracer(req.config, resolved.pendingState)
          _ <- runTracedCall(req.call, resolved, tracer)
        yield TraceCallResponse(tracer.getResult)
      }
      .recover { case _: MissingNodeException =>
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
    StateRead
      .reading {
        for resolved <- resolveBlock(req.block)
        yield
          // Execute calls sequentially. World state is not threaded (each call sees
          // the block's state), matching core-geth TraceCallMany behaviour.
          val results: Seq[JValue] = req.calls.map { case (callTx, config) =>
            val tracer = selectTracer(config, resolved.pendingState)
            runTracedCall(callTx, resolved, tracer)
              .map(_ => tracer.getResult)
              .getOrElse(org.json4s.JNull)
          }
          TraceCallManyResponse(results)
      }
      .recover { case _: MissingNodeException =>
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
    StateRead
      .reading {
        for
          block <- blockchainReader
            .getBlockByHash(BlockHash(req.blockHash))
            .toRight(JsonRpcError.LogicError(s"Block not found for hash ${req.blockHash.toHex}"))
          result <- traceAllTxsInBlock(block, req.config)
        yield TraceBlockByHashResponse(result)
      }
      .recover { case _: MissingNodeException =>
        Left(JsonRpcError.NodeNotFound)
      }

  /** Implements debug_traceBlockByNumber.
    *
    * Besu reference: DebugTraceBlockByNumber.java — same as DebugTraceBlock but resolves by number.
    *
    * core-geth reference: api.go TraceBlockByNumber() → traceBlock().
    */
  def traceBlockByNumber(req: TraceBlockByNumberRequest): ServiceResponse[TraceBlockByNumberResponse] =
    StateRead
      .reading {
        for
          resolved <- resolveBlock(req.block)
          result <- traceAllTxsInBlock(resolved.block, req.config)
        yield TraceBlockByNumberResponse(result)
      }
      .recover { case _: MissingNodeException =>
        Left(JsonRpcError.NodeNotFound)
      }

  // ─── Helpers ──────────────────────────────────────────────────────────────────

  /** Replays all transactions in a block, returning one trace result per tx, each tagged with its tx hash.
    *
    * Threads the world state forward tx-by-tx instead of calling advanceWorldToTx per index: advanceWorldToTx replays
    * every prior tx from the parent state root, so calling it once per index makes this method O(n^2) in the
    * transaction count. replayTransaction already returns the post-tx world in TxResult.worldState, so we carry that
    * into the next iteration and only build the genuine parent-state world once (matches core-geth's traceBlock, which
    * steps one statedb forward); that first world already holds the block's preamble (DAO drain, EIP-4788, EIP-2935),
    * as block import applies it. advanceWorldToTx itself is untouched — traceTransaction legitimately uses it for a
    * single index.
    */
  private def traceAllTxsInBlock(block: Block, config: TraceConfig): Either[JsonRpcError, Seq[TxTraceResult]] =
    blockchainReader
      .getBlockHeaderByHash(block.header.parentHash)
      .toRight(JsonRpcError.LogicError("Parent block header not found"))
      .map { parentHeader =>
        val stxs = SignedTransactionWithSender.getSignedTransactionsOfBlock(block.header, block.body.transactionList)
        if stxs.isEmpty then Seq.empty
        else
          var currentWorld = stxLedger.advanceWorldToTx(block.header, stxs, 0, parentHeader)
          val resultsBuf = scala.collection.mutable.ArrayBuffer[TxTraceResult]()
          stxs.foreach { stx =>
            val tracer = selectTracer(config, Some(currentWorld))
            val txResult = stxLedger.replayTransaction(stx, block.header, currentWorld, Some(tracer))
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
    *      For each tx: replay it (StxLedger.replayTransaction) → capture worldState.stateRootHash (equivalent to
    *      statedb.IntermediateRoot) 4. Return list of ByteString roots (one per tx)
    *
    * For an Amsterdam block the replay is block execution's own (system-call preamble, then each transaction settled
    * and persisted), so each root is the state root block execution reached after that transaction.
    */
  def intermediateRoots(req: IntermediateRootsRequest): ServiceResponse[IntermediateRootsResponse] =
    StateRead
      .reading {
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
          stxs = SignedTransactionWithSender.getSignedTransactionsOfBlock(block.header, block.body.transactionList)
          roots =
            if stxs.isEmpty then Seq.empty
            else
              // Chain world states tx-by-tx and capture state root after each finalization.
              // On tx error: return partial result (same as core-geth — errors on canon blocks are rare).
              var currentWorld = stxLedger.advanceWorldToTx(block.header, stxs, 0, parentHeader)
              val rootBuf = scala.collection.mutable.ArrayBuffer[ByteString]()
              stxs.foreach { stx =>
                val txResult = stxLedger.replayTransaction(stx, block.header, currentWorld, tracer = None)
                // stateRootHash computes the MPT root of the in-memory trie (equivalent to IntermediateRoot)
                rootBuf += txResult.worldState.stateRootHash
                currentWorld = txResult.worldState
              }
              rootBuf.toSeq
        yield IntermediateRootsResponse(roots)
      }
      .recover { case _: MissingNodeException =>
        Left(JsonRpcError.NodeNotFound)
      }

  /** One block of debug_traceChain, on its parent's state. A read of that state that the disk moved under is run again
    * on a fresh view, the whole block of it ([[StateRead.attempting]]), so one stale read does not fail the range; a
    * state that stays unreadable ends the range with the error [[StateRead.unavailable]] says.
    */
  private def traceChainBlock(
      blockNum: Long,
      config: TraceConfig
  ): Either[JsonRpcError, Option[TraceChainBlockResult]] =
    val branch = blockchainReader.getBestBranch
    blockchainReader.getBlockByNumber(branch, blockNum) match
      case None => Right(None)
      case Some(block) =>
        blockchainReader.getBlockHeaderByHash(block.header.parentHash) match
          case None => Right(None)
          case Some(parentHeader) =>
            val stxs =
              SignedTransactionWithSender.getSignedTransactionsOfBlock(block.header, block.body.transactionList)
            // One scope of reads for each attempt at a block of the range: the state the block is traced on is its
            // parent's, which is behind the head, and is held for this block and no longer.
            StateRead
              .attempting {
                stxs.zipWithIndex.map { case (stx, txIndex) =>
                  val world = stxLedger.advanceWorldToTx(block.header, stxs, txIndex, parentHeader)
                  val tracer = selectTracer(config, Some(world))
                  stxLedger.replayTransaction(stx, block.header, world, Some(tracer))
                  tracer.getResult
                }
              }
              .map(traces => Some(TraceChainBlockResult(block.header.number.value, block.header.hash.value, traces)))

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
        _ <- Either.cond(
          toNum - fromNum <= MaxTraceChainRange,
          (),
          JsonRpcError.InvalidParams(s"Requested range exceeds max of $MaxTraceChainRange blocks")
        )
        results <- ((fromNum + 1) to toNum).foldLeft[Either[JsonRpcError, Vector[TraceChainBlockResult]]](
          Right(Vector.empty)
        ) { case (acc, blockNum) =>
          acc.flatMap(done => traceChainBlock(blockNum, config).map(done ++ _))
        }
      yield results
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }

  /** The world a call runs on: the pending block's, or the state of the resolved block, taken by that block's own
    * header. The message's header, which the call runs under, is not the header of any block.
    */
  private def callWorld(resolved: ResolvedBlock, msg: CallTxBuilder.CallMessage): InMemoryWorldStateProxy =
    resolved.pendingState.getOrElse(stxLedger.callWorld(stateOf = resolved.block.header, executing = msg.header))

  /** The message a trace call runs (go-ethereum `eth/tracers/api.go` `TraceCall`: `CallDefaults` with the RPC gas cap,
    * `ToMessage`, the base fee lowered to 0 for a zero-price message), built by the same [[CallTxBuilder]] as eth_call.
    * `from` defaults to the zero address, as in geth.
    */
  private def buildCallMessage(callTx: TxArgs, block: Block): Either[JsonRpcError, CallTxBuilder.CallMessage] =
    for
      _ <- callTx.validateForCall.left.map(_.toJsonRpcError)
      msg <- CallTxBuilder.build(
        callTx,
        block.header,
        None,
        Address(0),
        CallTxBuilder.callGas(callTx, gasCap),
        Map.empty,
        // geth TraceCall sets SkipExecutionGasCapCheck
        skipExecutionGasCap = true
      )
    yield msg

  /** Runs `callTx` under `tracer`; a pre-check failure (geth `preCheck` / `buyGas` / intrinsic gas) is -32000. */
  private def runTracedCall(
      callTx: TxArgs,
      resolved: ResolvedBlock,
      tracer: ExecutionTracer
  ): Either[JsonRpcError, TxResult] =
    for
      msg <- buildCallMessage(callTx, resolved.block)
      r <- stxLedger
        .simulateCall(msg.stx, msg.header, callWorld(resolved, msg), msg.options, Some(tracer))
        .left
        .map(e => JsonRpcError.LogicError(e.message))
    yield r.txResult
