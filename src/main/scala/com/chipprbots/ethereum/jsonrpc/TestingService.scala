package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.pubsub.Topic
import org.apache.pekko.util.ByteString

import cats.effect.IO

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.chipprbots.ethereum.consensus.engine.BlobsBundleData
import com.chipprbots.ethereum.consensus.engine.BuiltBlock
import com.chipprbots.ethereum.consensus.engine.EngineApiService
import com.chipprbots.ethereum.consensus.engine.ForkChoiceManager
import com.chipprbots.ethereum.consensus.engine.ForkChoiceState
import com.chipprbots.ethereum.consensus.engine.PayloadAttributes
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Logger

/** The execution-apis `testing_*` namespace.
  *
  * Two methods, both block producers, differing only in where the block goes:
  *   - `testing_buildBlockV1` builds on an ARBITRARY parent and returns the payload. Read-only: no block is stored, the
  *     canonical head does not move.
  *   - `testing_commitBlockV1` builds on the CURRENT canonical head, stores the block, advances the head, emits the
  *     new-head chain event, and returns the new head hash.
  *
  * Spec: execution-apis `src/testing/testing_buildBlockV1.yaml` and `src/testing/testing_commitBlockV1.yaml`.
  *
  * Testing-only surface. The spec says it SHOULD be disabled by default, and it is: `testing` is not in
  * `network.rpc.apis` in any shipped config, so an operator has to opt in explicitly.
  *
  * Both methods go through [[EngineApiService.buildBlockOnParent]], the same builder `engine_forkchoiceUpdated` uses,
  * so a block committed here is bit-identical to the one the CL-driven path would have produced for the same
  * attributes.
  */
object TestingService:

  final case class BuildBlockRequest(
      parentBlockHash: ByteString,
      payloadAttributes: PayloadAttributes,
      // None encodes JSON `null` — "MAY build from the local mempool". Some(Nil) is an explicit
      // empty block. The distinction is load-bearing and must survive decoding.
      transactions: Option[Seq[ByteString]],
      extraData: Option[ByteString]
  )

  final case class BuildBlockResponse(
      block: Block,
      receipts: Seq[Receipt],
      // None => pre-Prague, the `executionRequests` field must be absent from the response entirely.
      executionRequests: Option[Seq[ByteString]],
      blobsBundle: BlobsBundleData
  )

  final case class CommitBlockRequest(
      payloadAttributes: PayloadAttributes,
      transactions: Option[Seq[ByteString]],
      extraData: Option[ByteString]
  )

  final case class CommitBlockResponse(blockHash: ByteString)

class TestingService(
    engineApiService: EngineApiService,
    blockchainReader: BlockchainReader,
    blockchainWriter: BlockchainWriter,
    forkChoiceManager: ForkChoiceManager,
    pendingTransactionsManager: Option[ActorRef[PendingTransactionsManager.Command]],
    blockTopic: Option[ActorRef[Topic.Command[NewBlockImported]]],
    gasLimitTarget: BigInt
)(implicit blockchainConfig: BlockchainConfig)
    extends Logger:

  import TestingService.*

  /** testing_buildBlockV1 — build on the given parent and return the payload. MUST NOT touch the canonical chain. */
  def buildBlock(req: BuildBlockRequest): ServiceResponse[BuildBlockResponse] =
    blockchainReader.getBlockByHash(BlockHash(req.parentBlockHash)) match
      case None =>
        IO.pure(
          Left(
            JsonRpcError.InvalidParams(
              s"unknown parent block ${hex(req.parentBlockHash)}"
            )
          )
        )
      case Some(parent) =>
        buildOn(parent, req.payloadAttributes, req.transactions, req.extraData).map(_.map { case (built, bundle) =>
          val requests =
            if blockchainConfig.isPragueTimestamp(built.block.header.unixTimestamp) then Some(built.executionRequests)
            else None
          BuildBlockResponse(built.block, built.receipts, requests, bundle)
        })

  /** testing_commitBlockV1 — build on the canonical head, insert, and advance the head.
    *
    * On any failure the head MUST NOT move. Ordering below enforces that: the block is only stored after
    * `buildBlockOnParent` has returned Right, and the head only moves after the store succeeds.
    */
  def commitBlock(req: CommitBlockRequest): ServiceResponse[CommitBlockResponse] =
    blockchainReader.getBestBlock match
      case None =>
        IO.pure(Left(JsonRpcError.LogicError("no canonical head block")))
      case Some(head) =>
        buildOn(head, req.payloadAttributes, req.transactions, req.extraData).map(_.map { case (built, _) =>
          val block = built.block
          val hash = block.header.hash

          // Same write sequence engine_newPayload + engine_forkchoiceUpdated perform for a
          // canonical-extending payload: store block, store receipts, promote to canonical head.
          // The world state is already persisted — buildBlockOnParent ran executeForProposer,
          // which calls InMemoryWorldStateProxy.persistState against the writable backing.
          blockchainWriter.storeBlock(block).commit()
          blockchainWriter.storeReceipts(hash, built.receipts).commit()
          forkChoiceManager.applyForkChoiceState(
            ForkChoiceState(
              headBlockHash = hash.value,
              // safe/finalized track the head: this namespace has no beacon chain behind it to
              // justify anything else, and applyForkChoiceState only reads them for logging.
              safeBlockHash = hash.value,
              finalizedBlockHash = hash.value
            )
          ) match
            case Left(err) => log.error("testing_commitBlockV1: fork choice rejected freshly stored head: {}", err)
            case Right(()) => ()

          // Drop the committed transactions from the pool so the next build does not re-queue
          // them (they would fail with NONCE_MISMATCH_TOO_LOW). engine_forkchoiceUpdated does
          // the same on head advance.
          if block.body.transactionList.nonEmpty then
            pendingTransactionsManager.foreach(
              _ ! PendingTransactionsManager.RemoveTransactions(block.body.transactionList)
            )

          // "emit the same chain events it would for any new head" — BlockImporter publishes
          // NewBlockImported on the same topic; eth_subscribe newHeads/logs read it from there.
          blockTopic.foreach(_ ! Topic.Publish(NewBlockImported(block)))

          log.info(
            "testing_commitBlockV1: committed block {} (number={}, txs={})",
            hex(hash.value),
            block.header.number,
            block.body.transactionList.size
          )
          CommitBlockResponse(hash.value)
        })

  /** Shared body of both methods: resolve the transaction list, then build. */
  private def buildOn(
      parent: Block,
      attrs: PayloadAttributes,
      rawTransactions: Option[Seq[ByteString]],
      extraData: Option[ByteString]
  ): IO[Either[JsonRpcError, (BuiltBlock, BlobsBundleData)]] =
    resolveTransactions(parent.header, attrs, rawTransactions).map(_.flatMap { case (txs, sidecars) =>
      // go-ethereum's miner converges the gas limit toward --miner.gaslimit via core.CalcGasLimit.
      // The execution-apis fixtures are generated against it (hive's rpc-compat sets
      // HIVE_TARGET_GAS_LIMIT=60000000 explicitly "so all clients build the same next-block gas
      // limit"), so a proposer that simply inherits the parent's gas limit produces a different
      // block hash on every fixture. The engine path keeps its own policy — see proposerGasLimit.
      val gasLimit = engineApiService.proposerGasLimit(
        parent.header,
        parent.header.number.value + 1,
        Some(gasLimitTarget)
      )
      engineApiService
        .buildBlockOnParent(
          parent,
          attrs,
          txs,
          // "If `extraData` is provided, the client MUST set the `extraData` field of the
          // resulting payload to this value." Absent/null -> empty, matching go-ethereum's
          // testing namespace, NOT the miner's configured header-extra-data.
          extraData.getOrElse(ByteString.empty),
          gasLimit,
          strict = true
        )
        .left
        .map { err =>
          // -32000 (not -32603): go-ethereum reports an unapplicable transaction as a generic
          // server error, and the execution-apis fixtures record that code. hive's rpc-compat
          // redacts `message` before comparing but NOT `code`, so the code has to match.
          JsonRpcError.LogicError(err)
        }
        .map(built => (built, engineApiService.blobsBundleFor(built.block.body.transactionList, sidecars)))
    })

  /** `null` -> mempool; `[]` -> empty block; non-empty -> exactly these, in order, no mempool txs. */
  private def resolveTransactions(
      parent: BlockHeader,
      attrs: PayloadAttributes,
      rawTransactions: Option[Seq[ByteString]]
  ): IO[Either[JsonRpcError, (Seq[SignedTransaction], Map[ByteString, ByteString])]] =
    rawTransactions match
      case None =>
        engineApiService.selectMempoolTransactions(parent, Timestamp(attrs.timestamp)).map(Right(_))
      case Some(raws) =>
        IO.pure(decodeTransactions(raws))

  private def decodeTransactions(
      raws: Seq[ByteString]
  ): Either[JsonRpcError, (Seq[SignedTransaction], Map[ByteString, ByteString])] =
    import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*
    val decoded = raws.zipWithIndex.map { case (raw, idx) =>
      Try(raw.toArray.toSignedTransactionWithSidecar) match
        case Success((stx, sidecar)) =>
          if SignedTransaction.getSender(stx).isEmpty then
            Left(JsonRpcError.InvalidParams(s"transaction at index $idx has an unrecoverable sender"))
          else Right((stx, sidecar))
        case Failure(e) =>
          Left(JsonRpcError.InvalidParams(s"transaction at index $idx is not decodable: ${e.getMessage}"))
    }
    decoded.collectFirst { case Left(err) => err } match
      case Some(err) => Left(err)
      case None =>
        val ok = decoded.collect { case Right(v) => v }
        // Only network-wrapped blob txs (0x03 || rlp([tx, blobs, commitments, proofs])) carry a
        // sidecar. A blob tx supplied in its canonical form has none, and its blobsBundle will be
        // empty — there is nothing to reconstruct it from. That is a property of the input, not a
        // bug here; callers that need the bundle must send the wrapped form.
        val sidecars = ok.collect { case (stx, Some(raw)) => stx.hash.value -> ByteString(raw) }.toMap
        Right((ok.map(_._1), sidecars))

  private def hex(bs: ByteString): String = "0x" + bs.map("%02x".format(_)).mkString
