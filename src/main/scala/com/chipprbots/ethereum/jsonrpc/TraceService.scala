package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import cats.effect.IO

import org.json4s.JValue
import org.json4s.JsonAST._
import org.json4s.JsonDSL._
import org.json4s.jvalue2monadic

import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage.TransactionLocation
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.Blockchain
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.LegacyTransaction
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.ledger.StxLedger
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.vm.CallTracer
import com.chipprbots.ethereum.vm.VmTracer

/** Service implementing the trace_* family of JSON-RPC methods (Parity/OpenEthereum format).
  *
  * Besu reference: ethereum/api/src/main/java/org/hyperledger/besu/ethereum/api/jsonrpc/internal/methods/
  *   - TraceTransaction.java         — trace_transaction
  *   - TraceBlock.java               — trace_block
  *   - TraceReplayBlockTransactions.java — trace_replayBlockTransactions
  *   - TraceReplayTransaction.java   — trace_replayTransaction
  *   - TraceCall.java                — trace_call
  *   - TraceCallMany.java            — trace_callMany
  *
  * core-geth reference: internal/ethapi/api.go + eth/tracers/api.go
  *   - TraceCall() → traceTx() with Parity-format result builder
  *
  * Response format: Parity/OpenEthereum flat trace format.
  *   - trace_transaction / trace_block return Seq of flat trace objects.
  *   - trace_replayTransaction / trace_replayBlockTransactions return trace + vmTrace bundle.
  *   - trace_call / trace_callMany return the same replay bundle for a synthetic tx.
  *
  * Note: This implementation uses CallTracer (nested call tree) and VmTracer (vm trace) as
  * the underlying tracers.  The flat Parity trace array is produced by flattenCallTree(),
  * which walks the CallTracer result and emits one entry per call with a traceAddress array.
  */
object TraceService {

  /** Trace replay options — control which trace types are included in the response.
    * Matches OpenEthereum traceReplayTransaction parameter.
    */
  case class TraceOptions(
      trace: Boolean    = true,
      vmTrace: Boolean  = false,
      stateDiff: Boolean = false
  )

  case class TraceTransactionRequest(txHash: ByteString)
  case class TraceTransactionResponse(traces: Seq[JValue])

  case class TraceBlockRequest(block: BlockParam)
  case class TraceBlockResponse(traces: Seq[JValue])

  case class TraceReplayTransactionRequest(txHash: ByteString, options: TraceOptions)
  case class TraceReplayTransactionResponse(result: JValue)

  case class TraceReplayBlockTransactionsRequest(block: BlockParam, options: TraceOptions)
  case class TraceReplayBlockTransactionsResponse(results: Seq[JValue])

  case class TraceCallRequest(call: EthInfoService.CallTx, options: TraceOptions, block: BlockParam)
  case class TraceCallResponse(result: JValue)

  case class TraceCallManyRequest(calls: Seq[(EthInfoService.CallTx, TraceOptions)], block: BlockParam)
  case class TraceCallManyResponse(results: Seq[JValue])
}

class TraceService(
    val blockchain: Blockchain,
    val blockchainReader: BlockchainReader,
    val mining: Mining,
    stxLedger: StxLedger,
    transactionMappingStorage: TransactionMappingStorage
) extends ResolveBlock {

  import TraceService._

  implicit private val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig

  /** Implements trace_transaction.
    *
    * Besu reference: TraceTransaction.java — uses FlatTraceGenerator to produce flat trace array.
    *
    * core-geth reference: api.go TraceTransaction() with Parity output formatter.
    *
    * Returns a flat array of call traces (one per call frame), each with:
    *   action, result, subtraces, traceAddress, transactionHash, transactionPosition, blockHash, blockNumber
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
        tracer = new CallTracer(onlyTopCall = false)
        _ = stxLedger.simulateTransactionWithTracer(targetStx, block.header, Some(world), tracer)
        flat = flattenCallTree(
          tracer.getResult, req.txHash, txIndex,
          block.header.hash, block.header.number
        )
      } yield TraceTransactionResponse(flat)
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }

  /** Implements trace_block.
    *
    * Besu reference: TraceBlock.java — returns flat traces for all txs in the block.
    *
    * core-geth reference: TraceBlock() iterates block txs and produces flat trace per tx.
    */
  def traceBlock(req: TraceBlockRequest): ServiceResponse[TraceBlockResponse] =
    IO {
      for {
        resolved <- resolveBlock(req.block)
        block = resolved.block
        parentHeader <- blockchainReader.getBlockHeaderByHash(block.header.parentHash)
          .toRight(JsonRpcError.InvalidParams("Parent block not found"))
        traces = traceAllTxsFlat(block, parentHeader.stateRoot)
      } yield TraceBlockResponse(traces)
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }

  /** Implements trace_replayTransaction.
    *
    * Besu reference: TraceReplayTransaction.java — runs the tx with CallTracer + optionally VmTracer,
    *   returns { trace: [...], vmTrace: {...}, stateDiff: null }.
    *
    * core-geth reference: api.go TraceTransaction() with {tracer: "prestateTracer"} for stateDiff,
    *   native callTracer for trace.
    */
  def replayTransaction(req: TraceReplayTransactionRequest): ServiceResponse[TraceReplayTransactionResponse] =
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
        result = buildReplayResult(targetStx, block, Some(world), req.txHash, txIndex, req.options)
      } yield TraceReplayTransactionResponse(result)
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }

  /** Implements trace_replayBlockTransactions.
    *
    * Besu reference: TraceReplayBlockTransactions.java — same as replay but for every tx in a block.
    *
    * core-geth reference: TraceBlock() with replay options.
    */
  def replayBlockTransactions(req: TraceReplayBlockTransactionsRequest): ServiceResponse[TraceReplayBlockTransactionsResponse] =
    IO {
      for {
        resolved <- resolveBlock(req.block)
        block = resolved.block
        parentHeader <- blockchainReader.getBlockHeaderByHash(block.header.parentHash)
          .toRight(JsonRpcError.InvalidParams("Parent block not found"))
        stxs = SignedTransactionWithSender.getSignedTransactions(block.body.transactionList)
        results = stxs.zipWithIndex.map { case (stx, txIndex) =>
          val world = stxLedger.advanceWorldToTx(block.header, stxs, txIndex, parentHeader.stateRoot)
          buildReplayResult(stx, block, Some(world), stx.tx.hash, txIndex, req.options)
        }
      } yield TraceReplayBlockTransactionsResponse(results)
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }

  /** Implements trace_call.
    *
    * Besu reference: TraceCall.java — builds a synthetic tx and runs replay.
    *
    * core-geth reference: api.go TraceCall() — same pattern as TraceTransaction but with a
    *   synthetic tx constructed from the call parameters.
    */
  def traceCall(req: TraceCallRequest): ServiceResponse[TraceCallResponse] =
    IO {
      for {
        resolved <- resolveBlock(req.block)
        stx <- buildCallTx(req.call, resolved.block)
        world = resolved.pendingState
        result = buildReplayResult(
          stx, resolved.block, world,
          ByteString.empty, 0, req.options
        )
      } yield TraceCallResponse(result)
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }

  /** Implements trace_callMany.
    *
    * core-geth reference: api.go TraceCallMany() — executes a list of (call, options) pairs.
    *
    * Besu does not implement this method; core-geth alignment only.
    */
  def traceCallMany(req: TraceCallManyRequest): ServiceResponse[TraceCallManyResponse] =
    IO {
      for {
        resolved <- resolveBlock(req.block)
      } yield {
        val results: Seq[JValue] = req.calls.map { case (callTx, options) =>
          buildCallTx(callTx, resolved.block).map { stx =>
            buildReplayResult(stx, resolved.block, resolved.pendingState, ByteString.empty, 0, options)
          }.getOrElse(JNull)
        }
        TraceCallManyResponse(results)
      }
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }

  // ─── Helpers ──────────────────────────────────────────────────────────────────

  /** Traces all txs in a block and returns flat trace objects for all of them concatenated. */
  private def traceAllTxsFlat(block: Block, parentStateRoot: ByteString): Seq[JValue] = {
    val stxs = SignedTransactionWithSender.getSignedTransactions(block.body.transactionList)
    stxs.zipWithIndex.flatMap { case (stx, txIndex) =>
      val world  = stxLedger.advanceWorldToTx(block.header, stxs, txIndex, parentStateRoot)
      val tracer = new CallTracer(onlyTopCall = false)
      stxLedger.simulateTransactionWithTracer(stx, block.header, Some(world), tracer)
      flattenCallTree(tracer.getResult, stx.tx.hash, txIndex, block.header.hash, block.header.number)
    }
  }

  /** Builds a replay result bundle: { trace, vmTrace, stateDiff } based on options. */
  private def buildReplayResult(
      stx: SignedTransactionWithSender,
      block: Block,
      world: Option[com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy],
      txHash: ByteString,
      txIndex: Int,
      options: TraceOptions
  ): JValue = {
    // Always run CallTracer for the trace field (even if options.trace=false, needed for vmTrace sub selection)
    val callTracer = new CallTracer(onlyTopCall = false)
    stxLedger.simulateTransactionWithTracer(stx, block.header, world, callTracer)

    val traceField: JValue = if (options.trace) {
      JArray(flattenCallTree(callTracer.getResult, txHash, txIndex, block.header.hash, block.header.number).toList)
    } else JNull

    val vmTraceField: JValue = if (options.vmTrace) {
      val vmTracer = new VmTracer()
      stxLedger.simulateTransactionWithTracer(stx, block.header, world, vmTracer)
      vmTracer.getResult
    } else JNull

    // stateDiff not yet implemented (deferred to P1-G)
    val txHashField: JValue = if (txHash.nonEmpty) JString(s"0x${txHash.toHex}") else JNull
    ("trace" -> traceField) ~
    ("vmTrace" -> vmTraceField) ~
    ("stateDiff" -> (JNull: JValue)) ~
    ("transactionHash" -> txHashField)
  }

  /** Flattens a nested CallTracer result (JObject) into a flat Parity trace array.
    *
    * Besu reference: FlatTraceGenerator.java — walks call tree, assigns traceAddress arrays.
    *
    * Each frame becomes one trace entry:
    * {{{
    * {
    *   "type": "call" | "create",
    *   "action": { "callType": "call", "from": "0x...", "to": "0x...", "gas": "0x...",
    *               "value": "0x...", "input": "0x..." },
    *   "result": { "gasUsed": "0x...", "output": "0x..." },
    *   "subtraces": N,
    *   "traceAddress": [0, 1, ...],
    *   "transactionHash": "0x...",
    *   "transactionPosition": N,
    *   "blockHash": "0x...",
    *   "blockNumber": N
    * }
    * }}}
    */
  private def flattenCallTree(
      root: JValue,
      txHash: ByteString,
      txIndex: Int,
      blockHash: ByteString,
      blockNumber: BigInt
  ): Seq[JValue] = {
    val buf = scala.collection.mutable.ArrayBuffer[JValue]()

    def walk(node: JValue, addr: List[Int]): Unit = node match {
      case obj: JObject =>
        val calls = (obj \ "calls") match {
          case JArray(cs) => cs
          case _          => Nil
        }
        val traceType = (obj \ "type") match {
          case JString(s) if s.toUpperCase.startsWith("CREATE") => "create"
          case _                                                 => "call"
        }
        val action: JValue = if (traceType == "create") {
          ("from"  -> (obj \ "from")) ~
          ("gas"   -> (obj \ "gas")) ~
          ("value" -> (obj \ "value")) ~
          ("init"  -> (obj \ "input"))
        } else {
          ("callType" -> ((obj \ "type") match { case JString(s) => s.toLowerCase; case _ => "call" })) ~
          ("from"  -> (obj \ "from")) ~
          ("to"    -> (obj \ "to")) ~
          ("gas"   -> (obj \ "gas")) ~
          ("value" -> (obj \ "value")) ~
          ("input" -> (obj \ "input"))
        }
        val resultField: JValue = (obj \ "error") match {
          case JString(_) | JNull => JNull
          case _ =>
            if (traceType == "create")
              ("gasUsed" -> (obj \ "gasUsed")) ~ ("address" -> (obj \ "to")) ~ ("code" -> (obj \ "output"))
            else
              ("gasUsed" -> (obj \ "gasUsed")) ~ ("output" -> (obj \ "output"))
        }
        val txHashField: JValue   = if (txHash.nonEmpty) JString(s"0x${txHash.toHex}") else JNull
        val traceAddrField: JValue = JArray(addr.map(i => JInt(i)))
        val entry: JObject =
          ("type"               -> traceType) ~
          ("action"             -> action) ~
          ("result"             -> resultField) ~
          ("subtraces"          -> calls.length) ~
          ("traceAddress"       -> traceAddrField) ~
          ("transactionHash"    -> txHashField) ~
          ("transactionPosition" -> txIndex) ~
          ("blockHash"          -> s"0x${blockHash.toHex}") ~
          ("blockNumber"        -> blockNumber)
        buf += entry
        calls.zipWithIndex.foreach { case (child, i) => walk(child, addr :+ i) }
      case _ => // not an object, skip
    }

    walk(root, Nil)
    buf.toSeq
  }

  /** Builds a synthetic SignedTransactionWithSender from a CallTx (used in traceCall). */
  private def buildCallTx(
      callTx: EthInfoService.CallTx,
      block: Block
  ): Either[JsonRpcError, SignedTransactionWithSender] = {
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
