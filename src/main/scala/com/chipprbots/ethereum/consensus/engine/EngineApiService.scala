package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.util.ByteString

import cats.effect.IO

import java.security.MessageDigest

import com.chipprbots.ethereum.consensus.engine.PayloadStatus._
import com.chipprbots.ethereum.consensus.validators.std.MptListValidator
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields._
import com.chipprbots.ethereum.domain.Withdrawal._
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.mpt.ByteArraySerializable
import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.SignedTransactions._
import com.chipprbots.ethereum.rlp.{encode => rlpEncode}
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Logger

/** Core Engine API logic. Converts ExecutionPayloads to Blocks, validates, and executes them. Integrates with
  * ForkChoiceManager for CL-driven fork choice.
  */
class EngineApiService(
    blockchainReader: BlockchainReader,
    blockchainWriter: BlockchainWriter,
    blockExecution: BlockExecution,
    forkChoiceManager: ForkChoiceManager,
    mining: com.chipprbots.ethereum.consensus.mining.Mining,
    evmCodeStorage: com.chipprbots.ethereum.db.storage.EvmCodeStorage,
    pendingTransactionsManager: org.apache.pekko.actor.ActorRef
)(implicit blockchainConfig: BlockchainConfig)
    extends Logger {

  import org.apache.pekko.pattern.ask
  import org.apache.pekko.util.Timeout
  import scala.concurrent.duration._
  import scala.concurrent.Await
  implicit private val askTimeout: Timeout = Timeout(3.seconds)

  /** Pending payloads built by forkchoiceUpdated, keyed by payloadId. */
  private val pendingPayloads = new java.util.concurrent.ConcurrentHashMap[ByteString, Block]()
  // EIP-7685 executionRequests associated with each payloadId, returned by getPayloadV4.
  private val pendingPayloadRequests =
    new java.util.concurrent.ConcurrentHashMap[ByteString, Seq[ByteString]]()
  // Receipts produced while building each payload. Used by engine_getPayloadV2+ to compute the
  // `blockValue` field (sum of priority-fee revenue) per EIP-3675 V2 envelope spec. Without this
  // the envelope returns blockValue=0x0 and the hive engine-withdrawals "GetPayloadV2 Block
  // Value" test fails with want=N, got=0.
  private val pendingPayloadReceipts =
    new java.util.concurrent.ConcurrentHashMap[ByteString, Seq[com.chipprbots.ethereum.domain.Receipt]]()
  // EIP-4844 blob sidecars (blobs, commitments, proofs) for each payload's blob txs. The hive
  // engine-cancun suite's VerifyBlobBundle asserts (a) matching counts, (b) byte-equality
  // against the sidecars the test submitted via eth_sendRawTransaction. We capture sidecars
  // during the proposer's GetPendingTransactions call and hand them to engine_getPayloadV3's
  // blobsBundle envelope.
  case class BlobsBundleData(
      blobs: Seq[ByteString],
      commitments: Seq[ByteString],
      proofs: Seq[ByteString]
  )
  private val pendingPayloadBlobsBundle =
    new java.util.concurrent.ConcurrentHashMap[ByteString, BlobsBundleData]()

  /** Blocks that returned INVALID via newPayload. Maps blockHash → latestValidHash. forkchoiceUpdated should not accept
    * these as head. Children of invalid blocks inherit the latestValidHash of their invalid parent.
    */
  private val invalidBlocks = new java.util.concurrent.ConcurrentHashMap[ByteString, ByteString]()
  // Index of optimistically-accepted (by-hash-only) blocks, keyed by parentHash → set of child
  // hashes. Used to recursively invalidate descendants when an ancestor is later revealed as
  // INVALID. The hive "Invalid Missing Ancestor Syncing ReOrg" family (24 tests) hangs without
  // this because the test waits up to its internal timeout for our client to detect the invalid
  // chain through an optimistically-accepted descendant.
  private val acceptedChildrenByParent =
    new java.util.concurrent.ConcurrentHashMap[ByteString, java.util.Set[ByteString]]()
  private val zeroHash = ByteString(new Array[Byte](32))

  /** Mark `hash` as INVALID and recursively invalidate every optimistically-accepted descendant.
    * All descendants inherit the same `latestValidHash`.
    */
  private def markInvalidRecursive(hash: ByteString, lvh: ByteString): Unit = {
    invalidBlocks.put(hash, lvh)
    val children = Option(acceptedChildrenByParent.remove(hash))
    children.foreach { set =>
      val iter = set.iterator()
      while (iter.hasNext) {
        val child = iter.next()
        blockchainWriter.removeBlockByHash(child).commit()
        markInvalidRecursive(child, lvh)
      }
    }
  }

  /** Return the latest block number from the blockchain storage. */
  def getLatestBlockNumber: BigInt =
    blockchainReader.getBestBlockNumber()

  /** engine_newPayloadV1/V2/V3/V4 — Validate and execute a new payload from the CL.
    *
    * Import strategy:
    *   1. If block hash doesn't match → INVALID_BLOCK_HASH 2. If already known → VALID (deduplicate) 3. If parent is
    *      known and we have state → full execution + validation 4. If parent is unknown → optimistic import (store
    *      block, skip execution, return VALID) This enables checkpoint sync where we follow the CL tip without full
    *      history.
    */
  def newPayload(payload: ExecutionPayload): IO[PayloadStatusV1] = IO {
    val block = payloadToBlock(payload)

    if (block.header.hash != payload.blockHash) {
      System.err.println(
        s"[ENGINE-API] newPayload #${payload.blockNumber}: block-hash mismatch " +
          s"computed=${block.header.hashAsHexString} payload=${com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(payload.blockHash)}"
      )
      // Hash mismatch: integrity error of the payload envelope. Per execution-apis PR #338
      // (https://github.com/ethereum/execution-apis/pull/338), starting from Shanghai (V2+)
      // the engine MUST return INVALID (not INVALID_BLOCK_HASH). V1 accepts either; returning
      // INVALID universally is compliant with all versions. latestValidHash must be null —
      // the corruption is in the payload itself, not attributable to any specific ancestor.
      //
      // Do NOT call removeBlockByHash here: payload.blockHash can collide with a legitimate
      // block (the hive "ParentHash equals BlockHash on NewPayload" test sets blockHash =
      // parentHash, which is the real parent's hash). Removing it would delete the parent.
      // We never stored anything under payload.blockHash in this call, so there is nothing
      // safe and correct to remove.
      PayloadStatusV1(Invalid, latestValidHash = None, validationError = Some("block hash mismatch"))
    } else if ({
      // EIP-4844 versioned-hash check must run BEFORE the "already stored" dedup. The hive
      // "NewPayloadV3 Versioned Hashes, Non-Empty Hashes" tests call newPayloadV3 twice for
      // the same payload — once with matching hashes (expected VALID, block gets stored),
      // and again with tampered hashes (expected INVALID). Without this early check the
      // tampered second call hits the dedup branch and silently returns VALID.
      payload.expectedBlobVersionedHashes.exists { expected =>
        val payloadHashes: Seq[ByteString] =
          block.body.transactionList.flatMap {
            case stx if stx.tx.isInstanceOf[com.chipprbots.ethereum.domain.BlobTransaction] =>
              stx.tx.asInstanceOf[com.chipprbots.ethereum.domain.BlobTransaction].blobVersionedHashes
            case _ => Nil
          }
        expected != payloadHashes
      }
    }) {
      // VersionedHashes mismatch: INVALID per EIP-4844 with latestValidHash=parent.hash.
      // Record in invalidBlocks so a subsequent forkchoiceUpdated(head=this) short-circuits
      // to INVALID (preventing the "unknown safe block hash" detour).
      val lvh = blockchainReader.getBlockHeaderByHash(payload.parentHash).map(_.hash).getOrElse(zeroHash)
      markInvalidRecursive(payload.blockHash, lvh)
      PayloadStatusV1(Invalid, latestValidHash = Some(lvh), validationError = Some("INVALID_VERSIONED_HASHES"))
    } else if (
      blockchainReader.getBlockHeaderByHash(payload.blockHash).exists { h =>
        blockchainReader.getBlockHeaderByNumber(h.number).exists(_.hash == payload.blockHash)
      }
    ) {
      // Already fully stored with number mapping — skip re-execution
      PayloadStatusV1(Valid, latestValidHash = Some(payload.blockHash))
    } else if (invalidBlocks.containsKey(payload.parentHash)) {
      // Parent was previously marked INVALID — child inherits invalidity.
      // Propagate the parent's latestValidHash (the last valid ancestor).
      val propagatedLvh = invalidBlocks.get(payload.parentHash) // non-null: containsKey guard
      blockchainWriter.removeBlockByHash(payload.blockHash).commit()
      markInvalidRecursive(payload.blockHash, propagatedLvh)
      EngineApiMetrics.recordNewPayload("INVALID", payload.blockNumber.toLong, payload.timestamp)
      PayloadStatusV1(
        Invalid,
        latestValidHash = Some(propagatedLvh),
        validationError = Some("parent block was previously invalidated")
      )
    } else {
      // Try full execution if parent block is known
      val parentKnown = blockchainReader.getBlockHeaderByHash(payload.parentHash).isDefined
      val parentHeader = blockchainReader.getBlockHeaderByHash(payload.parentHash)

      // Pre-execution header validation (catches modified Number, GasLimit, Timestamp, BlobGas)
      val headerInvalid: Option[String] = parentHeader.flatMap { parent =>
        if (block.header.number != parent.number + 1)
          Some(s"invalid block number: expected ${parent.number + 1} got ${block.header.number}")
        else if (block.header.unixTimestamp <= parent.unixTimestamp)
          Some(s"invalid timestamp: ${block.header.unixTimestamp} <= parent ${parent.unixTimestamp}")
        else {
          // EIP-1559 gas limit bounds: |gasLimit - parent.gasLimit| < parent.gasLimit / 1024
          val diff = (block.header.gasLimit - parent.gasLimit).abs
          val limit = parent.gasLimit / 1024
          if (diff >= limit && block.header.gasLimit != parent.gasLimit)
            Some(s"invalid gas limit change: diff=$diff exceeds bound=$limit")
          // EIP-4844: Validate excessBlobGas against parent.
          // EIP-7691 (Prague) raises TARGET_BLOB_GAS_PER_BLOCK from 3 to 6 blobs — pass the
          // right target based on the CHILD block's fork timestamp (child is the one being
          // validated; parent may precede Prague activation).
          else if (block.header.excessBlobGas.isDefined) {
            val parentExcess = parent.excessBlobGas.getOrElse(BigInt(0))
            val parentUsed = parent.blobGasUsed.getOrElse(BigInt(0))
            val target =
              if (blockchainConfig.isPragueTimestamp(block.header.unixTimestamp))
                BlobGasUtils.PRAGUE_TARGET_BLOB_GAS
              else BlobGasUtils.CANCUN_TARGET_BLOB_GAS
            val expectedExcess = BlobGasUtils.calcExcessBlobGas(parentExcess, parentUsed, target)
            val actual = block.header.excessBlobGas.get
            if (actual != expectedExcess)
              // Include canonical EEST exception name so the test framework's mapper matches.
              Some(s"INCORRECT_EXCESS_BLOB_GAS: expected $expectedExcess got $actual")
            else {
              // Validate blobGasUsed: count blob txs * GAS_PER_BLOB
              val blobTxCount = block.body.transactionList.collect {
                case stx if stx.tx.isInstanceOf[com.chipprbots.ethereum.domain.BlobTransaction] =>
                  stx.tx.asInstanceOf[com.chipprbots.ethereum.domain.BlobTransaction].blobVersionedHashes.size
              }.sum
              val expectedBlobGas = BigInt(blobTxCount) * BlobGasUtils.GAS_PER_BLOB
              val actualBlobGas = block.header.blobGasUsed.getOrElse(BigInt(0))
              if (actualBlobGas != expectedBlobGas)
                Some(s"INCORRECT_BLOB_GAS_USED: expected $expectedBlobGas got $actualBlobGas")
              else None
            }
          } else None
        }
      }
      // EIP-4844 (newPayloadV3): the CL declares which versioned hashes it expects this
      // payload to carry. Derive our own ordered list from the payload's blob txs and
      // compare. Mismatch ⇒ INVALID (same response shape as an INCORRECT_* header error).
      val versionedHashesInvalid: Option[String] = payload.expectedBlobVersionedHashes.flatMap { expected =>
        val payloadHashes: Seq[ByteString] =
          block.body.transactionList.flatMap {
            case stx if stx.tx.isInstanceOf[com.chipprbots.ethereum.domain.BlobTransaction] =>
              stx.tx.asInstanceOf[com.chipprbots.ethereum.domain.BlobTransaction].blobVersionedHashes
            case _ => Nil
          }
        if (expected == payloadHashes) None
        else
          Some(
            s"INVALID_VERSIONED_HASHES: expected ${expected.length} got ${payloadHashes.length} (first mismatch at index " +
              expected.zip(payloadHashes).indexWhere { case (e, p) => e != p } + ")"
          )
      }

      val preExecError = headerInvalid.orElse(versionedHashesInvalid)
      if (preExecError.isDefined) {
        val latestValid = parentHeader.map(_.hash).getOrElse(zeroHash)
        blockchainWriter.removeBlockByHash(payload.blockHash).commit()
        markInvalidRecursive(payload.blockHash, latestValid)
        EngineApiMetrics.recordNewPayload("INVALID", payload.blockNumber.toLong, payload.timestamp)
        PayloadStatusV1(Invalid, latestValidHash = Some(latestValid), validationError = Some(preExecError.get))
      } else {

        // Tracks the tx-level reason for an execution failure so we can surface it in
        // PayloadStatus.validationError (for EEST exception mapping, e.g.
        // INSUFFICIENT_ACCOUNT_FUNDS, NONCE_MISMATCH_TOO_LOW).
        val executionErrorReason = new java.util.concurrent.atomic.AtomicReference[Option[String]](None)

        // Parent is "validated" iff it's canonical (has a number→hash mapping to itself) or
        // it's a known sidechain that we executed (has receipts stored). A parent we only
        // know by hash (via storeBlockByHashOnly, i.e. optimistic accept with unknown grand-
        // parent) has unverified ancestry, and a child built on it must NOT be claimed as
        // VALID — hive's "Invalid NewPayload, ParentHash" test expects ACCEPTED/SYNCING.
        val parentValidated = parentHeader.exists { p =>
          blockchainReader.getBlockHeaderByNumber(p.number).exists(_.hash == p.hash) ||
          blockchainReader.getReceiptsByHash(p.hash).isDefined
        }
        val executionResult = if (parentKnown && parentValidated) {
          try
            blockExecution.executeAndValidateBlockFull(block, alreadyValidated = true) match {
              case Right((receipts, derivedRequests)) =>
                // EIP-7685: Per Engine API spec, verify the CL-supplied executionRequests match
                // what block execution actually produced. Mismatch → INVALID (e.g. CL attempted
                // to inject a deposit/withdrawal request that the execution layer didn't emit).
                val suppliedRequests = payload.executionRequests.getOrElse(Nil)
                val requestsMismatch =
                  blockchainConfig.isPragueTimestamp(block.header.unixTimestamp) &&
                    suppliedRequests != derivedRequests
                if (requestsMismatch) {
                  val lvh = parentHeader.map(_.hash).getOrElse(zeroHash)
                  blockchainWriter.removeBlockByHash(payload.blockHash).commit()
                  markInvalidRecursive(payload.blockHash, lvh)
                  executionErrorReason.set(
                    Some(
                      s"INVALID_REQUESTS: executionRequests mismatch " +
                        s"(supplied=${suppliedRequests.size}, derived=${derivedRequests.size})"
                    )
                  )
                  System.err.println(s"[ENGINE-API] newPayload #${payload.blockNumber}: INVALID_REQUESTS")
                  Some(false)
                } else {
                  // Detect whether this payload extends canonical (parent == current best) or is a
                  // sidechain. For canonical-extending payloads we write number→hash; for sidechains
                  // we store by-hash-only so later forkchoiceUpdated can promote via
                  // ForkChoiceManager.promoteBranchToCanonical.
                  val extendsCanonical = parentHeader.exists { p =>
                    blockchainReader.getBlockHeaderByNumber(p.number).exists(_.hash == p.hash)
                  }
                  if (extendsCanonical) blockchainWriter.storeBlock(block).commit()
                  else blockchainWriter.storeBlockByHashOnly(block).commit()
                  blockchainWriter.storeReceipts(block.header.hash, receipts).commit()
                  // NB: do NOT remove txs from the pool here. A newPayload'd block is stored
                  // but not yet canonical (no FCU has advanced bestBlock); the same txs must
                  // remain available for an alternative sibling payload on the same parent
                  // (hive 'Sidechain Reorg' test). Pool removal happens in forkchoiceUpdated
                  // once the block is promoted.
                  System.err.println(
                    s"[ENGINE-API] newPayload #${payload.blockNumber}: EXECUTED OK " +
                      s"(${block.body.numberOfTxs} txs, sidechain=${!extendsCanonical}, " +
                      s"requests=${derivedRequests.size}, " +
                      s"headerStateRoot=${block.header.stateRoot.take(8).map("%02x".format(_)).mkString}...)"
                  )
                  Some(true) // fully executed
                }
              case Left(error) =>
                error match {
                  case com.chipprbots.ethereum.ledger.BlockExecutionError.MPTError(_) |
                      com.chipprbots.ethereum.ledger.BlockExecutionError.MissingParentError =>
                    // Missing state — can't validate, return SYNCING
                    System.err.println(
                      s"[ENGINE-API] newPayload #${payload.blockNumber}: missing state, SYNCING"
                    )
                    None
                  case _ =>
                    // Genuine validation failure (wrong stateRoot, gasUsed, receipts, etc.)
                    val lvh = parentHeader.map(_.hash).getOrElse(zeroHash)
                    blockchainWriter.removeBlockByHash(payload.blockHash).commit()
                    markInvalidRecursive(payload.blockHash, lvh)
                    executionErrorReason.set(Some(error.reason.toString))
                    System.err.println(
                      s"[ENGINE-API] newPayload #${payload.blockNumber}: INVALID: ${error.reason}"
                    )
                    Some(false)
                }
            }
          catch {
            case e: com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MPTException =>
              // Missing state nodes — can't execute, return SYNCING
              System.err.println(
                s"[ENGINE-API] newPayload #${payload.blockNumber}: MPT error, SYNCING"
              )
              None
            case e: Exception =>
              System.err.println(
                s"[ENGINE-API] newPayload #${payload.blockNumber}: error: ${e.getMessage}, SYNCING"
              )
              None
          }
        } else {
          None // parent not known
        }

        executionResult match {
          case Some(true) =>
            // Fully executed and validated
            EngineApiMetrics.recordNewPayload("VALID", payload.blockNumber.toLong, payload.timestamp)
            PayloadStatusV1(Valid, latestValidHash = Some(payload.blockHash))

          case Some(false) =>
            // Execution failed — block is invalid. latestValidHash was stored in invalidBlocks above.
            val latestValid = Option(invalidBlocks.get(payload.blockHash))
            EngineApiMetrics.recordNewPayload("INVALID", payload.blockNumber.toLong, payload.timestamp)
            PayloadStatusV1(
              Invalid,
              latestValidHash = latestValid,
              validationError = Some(executionErrorReason.get().getOrElse("block execution failed"))
            )

          case None =>
            // Parent unknown OR parent known but unvalidated (optimistic chain). Store by
            // hash only so the block doesn't appear in eth_getBlockByNumber / eth_getBlock-
            // ByHash, but can be deduped and retroactively invalidated later.
            blockchainWriter.storeBlockByHashOnly(block).commit()
            // Record parent→child so that if the (still-unknown) ancestor chain is later
            // revealed as INVALID, we can retroactively invalidate this block too. Required
            // by hive's "Invalid Missing Ancestor Syncing ReOrg" tests.
            acceptedChildrenByParent.computeIfAbsent(
              payload.parentHash,
              _ => java.util.concurrent.ConcurrentHashMap.newKeySet[ByteString]()
            ).add(payload.blockHash)
            System.err.println(
              s"[ENGINE-API] newPayload #${payload.blockNumber}: ACCEPTED (parent unknown)"
            )
            EngineApiMetrics.recordNewPayload("ACCEPTED", payload.blockNumber.toLong, payload.timestamp)
            PayloadStatusV1(Accepted)
        }
      } // end headerInvalid else
    }
  }

  /** engine_forkchoiceUpdatedV1/V2/V3 — Update fork choice state, optionally start payload building. Returns
    * Left(errorMessage) for JSON-RPC error responses (e.g. invalid forkchoice state), Right(response) for normal
    * payload status responses.
    */
  def forkchoiceUpdated(
      forkChoiceState: ForkChoiceState,
      payloadAttributes: Option[PayloadAttributes]
  ): IO[Either[String, ForkchoiceUpdatedResponse]] = IO {
    // Check invalid/unvalidated blocks BEFORE applying fork choice state
    // (applyForkChoiceState calls saveBestKnownBlocks which would make the block canonical)
    val zeroHash = ByteString(new Array[Byte](32))

    if (invalidBlocks.containsKey(forkChoiceState.headBlockHash)) {
      val latestValid = Option(invalidBlocks.get(forkChoiceState.headBlockHash))
      EngineApiMetrics.recordForkchoiceUpdated("INVALID")
      Right(
        ForkchoiceUpdatedResponse(
          payloadStatus = PayloadStatusV1(
            Invalid,
            latestValidHash = latestValid,
            validationError = Some("head block was previously invalidated")
          )
        )
      )
    } else {
      // Check if the head block is fully stored (number→hash mapping exists).
      // Blocks stored via storeBlockByHashOnly (ACCEPTED) don't have this mapping.
      // Chain-imported blocks (chain.rlp) and newPayload VALID blocks DO have it.
      val headHeader = blockchainReader.getBlockHeaderByHash(forkChoiceState.headBlockHash)
      val blockFullyStored = headHeader.exists { header =>
        blockchainReader.getBlockHeaderByNumber(header.number).exists(_.hash == forkChoiceState.headBlockHash)
      }
      val blockExistsByHash = headHeader.isDefined
      val isGenesis = forkChoiceState.headBlockHash == blockchainReader
        .getBlockHeaderByNumber(0)
        .map(_.hash)
        .getOrElse(ByteString.empty)

      // Per Engine API spec 5.4 + hive "In-Order Consecutive Payload Execution": if the
      // head itself is unknown, return SYNCING first — we can't meaningfully validate
      // safe/finalized ancestry against a head we don't have. The safe/finalized unknown
      // checks are only -38002 errors once we KNOW the head; otherwise the CL is still
      // driving us to sync and the correct response is SYNCING.
      val safeHash = forkChoiceState.safeBlockHash
      val finalizedHash = forkChoiceState.finalizedBlockHash
      val safeUnknown = safeHash != zeroHash && blockchainReader.getBlockHeaderByHash(safeHash).isEmpty
      val finalizedUnknown = finalizedHash != zeroHash && blockchainReader.getBlockHeaderByHash(finalizedHash).isEmpty
      // Head-known-but-unvalidated: the block was stored optimistically (storeBlockByHashOnly,
      // no receipts, no canonical number mapping) because its parent chain isn't traceable.
      // In this state we're still syncing, so ALL status flavors — including safe/finalized
      // unknown — should yield SYNCING, not -38002. Hive's 'Invalid NewPayload, *VersionedHashes,
      // Syncing=True' tests rely on this.
      val headOptimistic =
        blockExistsByHash && !blockFullyStored && !isGenesis &&
          blockchainReader.getReceiptsByHash(forkChoiceState.headBlockHash).isEmpty

      if (!blockExistsByHash && !isGenesis) {
        // Head unknown — client is still syncing to this head
        EngineApiMetrics.recordForkchoiceUpdated("SYNCING")
        Right(ForkchoiceUpdatedResponse(payloadStatus = PayloadStatusV1(Syncing)))
      } else if (headOptimistic) {
        EngineApiMetrics.recordForkchoiceUpdated("SYNCING")
        Right(ForkchoiceUpdatedResponse(payloadStatus = PayloadStatusV1(Syncing)))
      } else if (safeUnknown || finalizedUnknown) {
        val msg = if (safeUnknown) "unknown safe block hash" else "unknown finalized block hash"
        EngineApiMetrics.recordForkchoiceUpdated("INVALID")
        Left(msg)
      } else if (headHeader.isDefined && !isAncestorOrEqual(safeHash, forkChoiceState.headBlockHash, zeroHash)) {
        EngineApiMetrics.recordForkchoiceUpdated("INVALID")
        Left("invalid forkchoice state: safe block is not an ancestor of head")
      } else if (headHeader.isDefined && !isAncestorOrEqual(finalizedHash, forkChoiceState.headBlockHash, zeroHash)) {
        EngineApiMetrics.recordForkchoiceUpdated("INVALID")
        Left("invalid forkchoice state: finalized block is not an ancestor of head")
      } else {

        forkChoiceManager.applyForkChoiceState(forkChoiceState) match {
          case Left(_) =>
            // Head not known — return SYNCING so CL knows we need newPayload
            EngineApiMetrics.recordForkchoiceUpdated("SYNCING")
            Right(ForkchoiceUpdatedResponse(payloadStatus = PayloadStatusV1(Syncing)))

          case Right(()) =>
            // FCU has advanced best-block; purge the head block's txs from the mempool
            // so the next proposer build doesn't re-queue them (would cause
            // NONCE_MISMATCH_TOO_LOW).
            if (pendingTransactionsManager != null) {
              blockchainReader.getBlockByHash(forkChoiceState.headBlockHash).foreach { headBlock =>
                if (headBlock.body.transactionList.nonEmpty)
                  pendingTransactionsManager ! com.chipprbots.ethereum.transactions.PendingTransactionsManager
                    .RemoveTransactions(headBlock.body.transactionList)
              }
            }

            // Validate payload attributes AFTER applying forkchoice — per engine-API spec
            // step ordering (apply forkchoiceState, THEN check attrs) and hive's
            // 'Invalid PayloadAttributes' test, which asserts the forkchoice IS applied
            // even when attrs are rejected with -38003.
            val invalidAttrsMsg: Option[String] = payloadAttributes.flatMap { attrs =>
              if (attrs.timestamp == 0) Some("invalid payload attributes: zero timestamp")
              else {
                blockchainReader.getBlockHeaderByHash(forkChoiceState.headBlockHash).flatMap { parent =>
                  if (attrs.timestamp <= parent.unixTimestamp)
                    Some("invalid payload attributes: timestamp too low")
                  else None
                }
              }
            }
            if (invalidAttrsMsg.isDefined) {
              EngineApiMetrics.recordForkchoiceUpdated("INVALID")
              Left("ATTR:" + invalidAttrsMsg.get)
            } else {

              val payloadId = payloadAttributes.map { attrs =>
                // Deterministic payload ID MUST be unique for every distinct attribute
                // combination — hive 'Unique Payload ID' test sends FCUs differing only in
                // a single withdrawal field or beaconRoot and expects the IDs to differ.
                // Include withdrawals + beaconRoot in the hash.
                val withdrawalBytes: Array[Byte] =
                  attrs.withdrawals.toSeq.flatMap { ws =>
                    ws.flatMap { w =>
                      w.index.toByteArray.toSeq ++
                        w.validatorIndex.toByteArray.toSeq ++
                        w.address.bytes.toArray.toSeq ++
                        w.amount.toByteArray.toSeq
                    }
                  }.toArray
                val beaconRootBytes = attrs.parentBeaconBlockRoot.map(_.toArray).getOrElse(Array.emptyByteArray)
                val idBytes = kec256(
                  forkChoiceState.headBlockHash.toArray ++
                    BigInt(attrs.timestamp).toByteArray ++
                    attrs.prevRandao.toArray ++
                    attrs.suggestedFeeRecipient.bytes.toArray ++
                    withdrawalBytes ++
                    beaconRootBytes
                )
                val id = ByteString(idBytes.take(8))

                // Build the payload using BlockPreparator directly with post-merge header
                try {
                  val parentOpt = blockchainReader.getBlockByHash(forkChoiceState.headBlockHash)
                  parentOpt.foreach { parent =>
                    // Compute EIP-1559 base fee from parent
                    val parentBaseFee = parent.header.baseFee.getOrElse(BigInt("1000000000"))
                    val parentGasTarget = parent.header.gasLimit / 2
                    val baseFee: BigInt =
                      if (parent.header.number == 0) parentBaseFee
                      else if (parent.header.gasUsed == parentGasTarget) parentBaseFee
                      else if (parent.header.gasUsed > parentGasTarget) {
                        val delta = parentBaseFee * (parent.header.gasUsed - parentGasTarget) / parentGasTarget / 8
                        parentBaseFee + (if (delta == BigInt(0)) BigInt(1) else delta)
                      } else {
                        val delta = parentBaseFee * (parentGasTarget - parent.header.gasUsed) / parentGasTarget / 8
                        if (parentBaseFee - delta < 0) BigInt(0) else parentBaseFee - delta
                      }

                    // Fetch pending transactions from the tx pool, filtering by chain ID.
                    // Also capture the network-wrapped raw bytes for EIP-4844 blob txs so
                    // engine_getPayloadV3 can emit them in the blobsBundle envelope.
                    val (pendingTxs, blobTxRawBytesFromPool): (Seq[SignedTransaction], Map[ByteString, ByteString]) =
                      try {
                        import com.chipprbots.ethereum.transactions.PendingTransactionsManager._
                        val future =
                          (pendingTransactionsManager ? GetPendingTransactions).mapTo[PendingTransactionsResponse]
                        val response = Await.result(future, 3.seconds)
                        val expectedChainId = blockchainConfig.chainId
                        val txs = response.pendingTransactions.map(_.stx.tx).filter { stx =>
                          val txChainId: Option[BigInt] = stx.tx match {
                            case t: com.chipprbots.ethereum.domain.TransactionWithAccessList => Some(t.chainId)
                            case t: com.chipprbots.ethereum.domain.TransactionWithDynamicFee => Some(t.chainId)
                            case t: com.chipprbots.ethereum.domain.BlobTransaction           => Some(t.chainId)
                            case t: com.chipprbots.ethereum.domain.SetCodeTransaction        => Some(t.chainId)
                            case _ => None // legacy txs don't have explicit chainID
                          }
                          txChainId.forall(_ == expectedChainId)
                        }
                        if (txs.nonEmpty) log.info("Payload includes {} pending transactions", txs.size)
                        (txs, response.blobTxNetworkBytes)
                      } catch {
                        case e: Exception =>
                          log.error("Failed to fetch pending txs: {}", e.getMessage)
                          (Seq.empty[SignedTransaction], Map.empty[ByteString, ByteString])
                      }

                    // EIP-4844 / EIP-7691: cap blob-gas included in the payload at the fork's
                    // MAX_BLOB_GAS_PER_BLOCK (6 blobs Cancun, 9 blobs Prague). Without this cap
                    // the proposer packs every pool blob tx into one block and getPayloadV3's
                    // blobsBundle grows past the test's `ExpectedIncludedBlobCount`.
                    val pendingTxsForBlock = {
                      val maxBlobGas =
                        if (blockchainConfig.isPragueTimestamp(attrs.timestamp))
                          BlobGasUtils.PRAGUE_MAX_BLOB_GAS
                        else BlobGasUtils.CANCUN_MAX_BLOB_GAS
                      pendingTxs.foldLeft((Seq.empty[SignedTransaction], BigInt(0))) {
                        case ((kept, blobGas), stx) =>
                          stx.tx match {
                            case b: com.chipprbots.ethereum.domain.BlobTransaction =>
                              val add = BigInt(b.blobVersionedHashes.size) * BlobGasUtils.GAS_PER_BLOB
                              if (blobGas + add <= maxBlobGas) (kept :+ stx, blobGas + add)
                              else (kept, blobGas) // skip this blob tx, smaller ones later may still fit
                            case _ =>
                              (kept :+ stx, blobGas)
                          }
                      }._1
                    }

                    val emptyWithdrawalsRoot = ByteString(
                      kec256(
                        com.chipprbots.ethereum.rlp.encode(com.chipprbots.ethereum.rlp.RLPValue(Array.empty[Byte]))
                      )
                    )
                    val emptyTrieRoot = ByteString(
                      kec256(
                        com.chipprbots.ethereum.rlp.encode(com.chipprbots.ethereum.rlp.RLPValue(Array.empty[Byte]))
                      )
                    )

                    // Determine which fork is active at the proposed block's timestamp so we emit
                    // the correct HeaderExtraFields variant and header fields.
                    val isShanghai = blockchainConfig.isShanghaiTimestamp(attrs.timestamp)
                    val isCancun = blockchainConfig.isCancunTimestamp(attrs.timestamp)
                    val isPrague = blockchainConfig.isPragueTimestamp(attrs.timestamp)
                    val withdrawals: Seq[com.chipprbots.ethereum.domain.Withdrawal] =
                      attrs.withdrawals.getOrElse(Nil)

                    // Compute withdrawalsRoot from attrs (Shanghai+ payload attributes).
                    val computedWithdrawalsRoot =
                      if (withdrawals.nonEmpty) computeWithdrawalsRoot(withdrawals)
                      else emptyWithdrawalsRoot

                    // EIP-4844 / EIP-7691 excessBlobGas from parent.
                    val parentBlobTarget =
                      if (isPrague) BlobGasUtils.PRAGUE_TARGET_BLOB_GAS
                      else BlobGasUtils.CANCUN_TARGET_BLOB_GAS
                    val parentExcessBlobGas = parent.header.excessBlobGas.getOrElse(BigInt(0))
                    val parentBlobGasUsed = parent.header.blobGasUsed.getOrElse(BigInt(0))
                    val childExcessBlobGas =
                      BlobGasUtils.calcExcessBlobGas(parentExcessBlobGas, parentBlobGasUsed, parentBlobTarget)

                    val parentBeaconBlockRoot =
                      attrs.parentBeaconBlockRoot.getOrElse(ByteString(new Array[Byte](32)))

                    // Placeholder extraFields — stateRoot / requestsHash / blobGasUsed are filled in
                    // AFTER executing the block (we can't know them yet).
                    val initialExtraFields =
                      if (isPrague)
                        HefPostPrague(
                          baseFee,
                          computedWithdrawalsRoot,
                          BigInt(0),
                          childExcessBlobGas,
                          parentBeaconBlockRoot,
                          ByteString.empty
                        )
                      else if (isCancun)
                        HefPostCancun(
                          baseFee,
                          computedWithdrawalsRoot,
                          BigInt(0),
                          childExcessBlobGas,
                          parentBeaconBlockRoot
                        )
                      else if (isShanghai)
                        HefPostShanghai(baseFee, computedWithdrawalsRoot)
                      else
                        // Paris (post-merge, pre-Shanghai): HefPostOlympia holds only baseFee.
                        // Using HefPostShanghai here breaks the blockHash round-trip: getPayloadV1
                        // returns a payload with no withdrawals field, and newPayloadV1 reconstructs
                        // the header as HefPostOlympia — different RLP, different hash, so every
                        // Paris payload we build fails its own newPayload round-trip.
                        HefPostOlympia(baseFee)

                    // Build post-merge header with skeleton (difficulty=0 so payBlockReward skips PoW rewards)
                    val blockNumber = parent.header.number + 1
                    val gasLimit = parent.header.gasLimit // keep parent gas limit
                    val header = BlockHeader(
                      parentHash = parent.header.hash,
                      ommersHash =
                        ByteString(kec256(com.chipprbots.ethereum.rlp.encode(com.chipprbots.ethereum.rlp.RLPList()))),
                      beneficiary = attrs.suggestedFeeRecipient.bytes,
                      stateRoot = ByteString.empty,
                      transactionsRoot = emptyTrieRoot,
                      receiptsRoot = emptyTrieRoot,
                      logsBloom = ByteString(new Array[Byte](256)),
                      difficulty = 0,
                      number = blockNumber,
                      gasLimit = gasLimit,
                      gasUsed = 0,
                      unixTimestamp = attrs.timestamp,
                      extraData = ByteString("fukuii".getBytes),
                      mixHash = attrs.prevRandao,
                      nonce = ByteString(new Array[Byte](8)),
                      extraFields = initialExtraFields
                    )
                    val body = BlockBody(pendingTxsForBlock.toList, Nil, withdrawals = attrs.withdrawals)
                    val skeletonBlock = Block(header, body)

                    // Route EVERY post-merge proposer build through executeForProposer (which
                    // goes through BlockExecution.executeBlock — txs, payBlockReward, withdrawals
                    // via processWithdrawals, Prague system calls, then persistState).
                    // The previous `if (isPrague) …  else BlockPreparator.prepareBlock` branch
                    // was broken for Shanghai/Cancun: BlockPreparator.prepareBlock does NOT call
                    // processWithdrawals, so the proposer-built header contained a stateRoot that
                    // did not reflect the withdrawals — every withdrawals hive test came back with
                    // "Block has invalid state root hash" on its own payload round-trip.
                    // executeBlock early-returns cleanly on pre-Prague (processPragueSystemCalls
                    // is a no-op outside Prague), so there's nothing to lose by using it always.
                    import com.chipprbots.ethereum.consensus.validators.std.MptListValidator.intByteArraySerializable
                    import com.chipprbots.ethereum.ledger.BloomFilter
                    import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
                    import com.chipprbots.ethereum.domain.Receipt
                    val (receipts, gasUsedTotal, finalStateRoot, executionRequests) =
                      blockExecution.executeForProposer(skeletonBlock) match {
                        case Right(result) =>
                          (result.receipts, result.gasUsed, result.worldState.stateRootHash, result.executionRequests)
                        case Left(err) =>
                          log.error("Proposer-mode execution failed: {}", err)
                          (Seq.empty[Receipt], BigInt(0), parent.header.stateRoot, Seq.empty[ByteString])
                      }

                    val receiptsLogs = BloomFilter.EmptyBloomFilter.toArray +: receipts.map(_.logsBloomFilter.toArray)
                    val bloomFilter = ByteString(com.chipprbots.ethereum.utils.ByteUtils.or(receiptsLogs: _*))
                    def buildMpt[T](
                        items: Seq[T],
                        ser: com.chipprbots.ethereum.mpt.ByteArraySerializable[T]
                    ): ByteString = {
                      val storage = new com.chipprbots.ethereum.db.storage.SerializingMptStorage(
                        new com.chipprbots.ethereum.db.storage.ArchiveNodeStorage(
                          new com.chipprbots.ethereum.db.storage.NodeStorage(
                            com.chipprbots.ethereum.db.dataSource.EphemDataSource()
                          )
                        )
                      )
                      val trie = items.zipWithIndex.foldLeft(
                        MerklePatriciaTrie[Int, T](storage)(intByteArraySerializable, ser)
                      ) { case (t, (item, idx)) =>
                        t.put(idx, item)
                      }
                      ByteString(trie.getRootHash)
                    }

                    // Blob-gas accounting: sum GAS_PER_BLOB * blob_count across blob txs.
                    val blobGasUsed: BigInt = skeletonBlock.body.transactionList.map {
                      case stx if stx.tx.isInstanceOf[com.chipprbots.ethereum.domain.BlobTransaction] =>
                        BigInt(
                          stx.tx.asInstanceOf[com.chipprbots.ethereum.domain.BlobTransaction].blobVersionedHashes.size
                        ) * BlobGasUtils.GAS_PER_BLOB
                      case _ => BigInt(0)
                    }.sum

                    // Finalize extraFields with execution-derived values.
                    val finalExtraFields = initialExtraFields match {
                      case _: HefPostPrague =>
                        HefPostPrague(
                          baseFee,
                          computedWithdrawalsRoot,
                          blobGasUsed,
                          childExcessBlobGas,
                          parentBeaconBlockRoot,
                          computeRequestsHash(executionRequests)
                        )
                      case _: HefPostCancun =>
                        HefPostCancun(
                          baseFee,
                          computedWithdrawalsRoot,
                          blobGasUsed,
                          childExcessBlobGas,
                          parentBeaconBlockRoot
                        )
                      case other => other
                    }

                    val updatedHeader = header.copy(
                      stateRoot = finalStateRoot,
                      receiptsRoot = buildMpt(receipts, Receipt.byteArraySerializable),
                      transactionsRoot =
                        buildMpt(skeletonBlock.body.transactionList, SignedTransaction.byteArraySerializable),
                      logsBloom = bloomFilter,
                      gasUsed = gasUsedTotal,
                      extraFields = finalExtraFields
                    )
                    val payload = skeletonBlock.copy(header = updatedHeader)
                    pendingPayloads.put(id, payload)
                    // Also stash executionRequests so getPayloadV4 can emit them.
                    if (executionRequests.nonEmpty) pendingPayloadRequests.put(id, executionRequests)
                    // Stash receipts so getPayloadV2+ can compute the blockValue envelope field.
                    if (receipts.nonEmpty) pendingPayloadReceipts.put(id, receipts)
                    // EIP-4844: collect the blob sidecars for every blob tx in the built payload
                    // so engine_getPayloadV3 can emit the blobsBundle envelope. Without this the
                    // envelope has empty arrays while the payload body has blob txs; the hive
                    // engine-cancun VerifyBlobBundle step fails with "expected N blob, got 0".
                    val bundle = buildBlobsBundle(payload.body.transactionList, blobTxRawBytesFromPool)
                    if (bundle.blobs.nonEmpty) pendingPayloadBlobsBundle.put(id, bundle)
                    log.info(
                      "Built payload {} for block {} (baseFee={}, parent={}, fork={}, requests={})",
                      id.toArray.map("%02x".format(_)).mkString,
                      payload.header.number,
                      baseFee,
                      parent.header.number,
                      if (isPrague) "Prague" else if (isCancun) "Cancun" else "Shanghai",
                      executionRequests.size
                    )
                  }
                } catch {
                  case e: Exception =>
                    log.error("Failed to build payload: {}", e.getMessage)
                }
                id
              }

              EngineApiMetrics.recordForkchoiceUpdated("VALID")
              Right(
                ForkchoiceUpdatedResponse(
                  payloadStatus = PayloadStatusV1(Valid, latestValidHash = Some(forkChoiceState.headBlockHash)),
                  payloadId = payloadId
                )
              )
            } // end else (invalidAttrs check)
          // end else (safe/finalized check)
          // end case Right
        }
      } // end else (blockFullyStored check)
    } // end else (invalidBlocks check)
  }

  /** engine_getPayloadV1/V2/V3/V4 — Return a previously built payload by ID. */
  def getPayload(payloadId: ByteString): IO[Either[String, Block]] = IO {
    // Do NOT remove: the engine-api spec allows the CL to call getPayload multiple times for
    // the same id (e.g. first getPayloadV1 then getPayloadV2 for the same payload, as the
    // hive engine-withdrawals "Withdrawals Fork on Block N" tests do). Removing on the first
    // read makes any follow-up call fail with "Payload not available".
    Option(pendingPayloads.get(payloadId)) match {
      case Some(block) => Right(block)
      case None        => Left("Payload not available")
    }
  }

  /** Return the EIP-7685 executionRequests (typed byte strings, type-prefixed) associated with a payload we built. Only
    * non-empty for Prague+ blocks. Used by engine_getPayloadV4 to return the requests alongside the executionPayload
    * envelope.
    */
  def getPayloadExecutionRequests(payloadId: ByteString): Seq[ByteString] =
    Option(pendingPayloadRequests.remove(payloadId)).getOrElse(Nil)

  /** Receipts produced while building this payload. Used by engine_getPayloadV2+ to compute the
    * `blockValue` envelope field. `get` (not `remove`) because the CL may call getPayloadV1 and
    * then getPayloadV2 for the same id (hive withdrawals tests rely on this).
    */
  def getPayloadReceipts(payloadId: ByteString): Seq[com.chipprbots.ethereum.domain.Receipt] =
    Option(pendingPayloadReceipts.get(payloadId)).getOrElse(Nil)

  /** EIP-4844 sidecars for the blob txs included in this payload, for engine_getPayloadV3's
    * blobsBundle envelope. Empty when the payload has no blob txs.
    */
  def getPayloadBlobsBundle(payloadId: ByteString): BlobsBundleData =
    Option(pendingPayloadBlobsBundle.get(payloadId)).getOrElse(BlobsBundleData(Nil, Nil, Nil))

  /** Parse the EIP-4844 network-wrapped raw bytes (`0x03 || rlp([tx_payload, blobs, commitments,
    * proofs])`) the pool captured for each blob tx, and return the concatenated sidecars for
    * every blob tx actually included in the built payload, in payload order.
    */
  private def buildBlobsBundle(
      txs: Seq[SignedTransaction],
      blobTxRawBytes: Map[ByteString, ByteString]
  ): BlobsBundleData = {
    import com.chipprbots.ethereum.rlp.{rawDecode, RLPList, RLPValue}
    val blobTxHashes = txs.collect {
      case stx if stx.tx.isInstanceOf[com.chipprbots.ethereum.domain.BlobTransaction] => stx.hash
    }
    val allBlobs = Seq.newBuilder[ByteString]
    val allCommitments = Seq.newBuilder[ByteString]
    val allProofs = Seq.newBuilder[ByteString]
    blobTxHashes.foreach { h =>
      blobTxRawBytes.get(h) match {
        case Some(raw) if raw.length > 1 && raw(0) == 0x03 =>
          try {
            rawDecode(raw.toArray.drop(1)) match {
              case RLPList(_, blobs: RLPList, commitments: RLPList, proofs: RLPList) =>
                blobs.items.foreach { case RLPValue(b) => allBlobs += ByteString(b); case _ => }
                commitments.items.foreach { case RLPValue(c) => allCommitments += ByteString(c); case _ => }
                proofs.items.foreach { case RLPValue(p) => allProofs += ByteString(p); case _ => }
              case _ =>
                log.warn("Blob tx {} sidecar RLP shape unexpected; skipping", h.toArray.map("%02x".format(_)).mkString)
            }
          } catch {
            case e: Exception =>
              log.warn("Failed to decode blob tx {} sidecar: {}", h.toArray.map("%02x".format(_)).mkString, e.getMessage)
          }
        case _ => // tx from network / historical — we didn't store a sidecar
      }
    }
    BlobsBundleData(allBlobs.result(), allCommitments.result(), allProofs.result())
  }

  /** engine_exchangeCapabilities — return supported Engine API methods. */
  def exchangeCapabilities(clCapabilities: Seq[String]): IO[Seq[String]] = IO {
    val supported = Seq(
      "engine_newPayloadV1",
      "engine_newPayloadV2",
      "engine_newPayloadV3",
      "engine_newPayloadV4",
      "engine_forkchoiceUpdatedV1",
      "engine_forkchoiceUpdatedV2",
      "engine_forkchoiceUpdatedV3",
      "engine_getPayloadV1",
      "engine_getPayloadV2",
      "engine_getPayloadV3",
      "engine_getPayloadV4",
      "engine_getBlobsV1",
      "engine_getPayloadBodiesByHashV1",
      "engine_getPayloadBodiesByRangeV1",
      "engine_getClientVersionV1",
      "engine_exchangeCapabilities"
    )
    log.info(s"exchangeCapabilities: CL supports ${clCapabilities.size} methods, we support ${supported.size}")
    supported
  }

  /** Walks back from `descendant`'s header chain up to 8192 blocks checking whether `ancestor` appears. Zero hash is
    * treated as "not present" and short-circuits to true (ancestor disabled). Used by forkchoiceUpdated to verify
    * safe/finalized are ancestors of head per Engine API spec §5.4.
    */
  private def isAncestorOrEqual(ancestor: ByteString, descendant: ByteString, zeroHash: ByteString): Boolean =
    if (ancestor == zeroHash) true
    else if (ancestor == descendant) true
    else {
      var cursor: ByteString = descendant
      var steps = 0
      val maxWalk = 8192
      var found = false
      while (!found && steps < maxWalk && cursor != zeroHash)
        blockchainReader.getBlockHeaderByHash(cursor) match {
          case Some(h) =>
            if (h.parentHash == ancestor) { found = true }
            else { cursor = h.parentHash; steps += 1 }
          case None =>
            // Missing ancestor data — assume not present rather than loop forever
            cursor = zeroHash
        }
      found
    }

  /** engine_getPayloadBodiesByHashV1: look up a block body by hash. Returns (rawTransactions, encodedWithdrawals) or
    * None if not found.
    */
  def getPayloadBodyByHash(hash: ByteString): Option[(Seq[ByteString], Option[Seq[org.json4s.JValue]])] =
    blockchainReader.getBlockBodyByHash(hash).map(bodyToPayloadBody)

  /** engine_getPayloadBodiesByRangeV1: look up a block body by number. */
  def getPayloadBodyByNumber(number: BigInt): Option[(Seq[ByteString], Option[Seq[org.json4s.JValue]])] =
    blockchainReader.getBlockHeaderByNumber(number).flatMap { header =>
      blockchainReader.getBlockBodyByHash(header.hash).map(bodyToPayloadBody)
    }

  private def bodyToPayloadBody(body: BlockBody): (Seq[ByteString], Option[Seq[org.json4s.JValue]]) = {
    val rawTxs = body.transactionList.map { stx =>
      ByteString(rlpEncode(SignedTransactionEnc(stx).toRLPEncodable))
    }
    val withdrawals = body.withdrawals.map { ws =>
      ws.map { w =>
        import org.json4s.JValue
        org.json4s.JObject(
          "index" -> org.json4s.JString(s"0x${w.index.toString(16)}"),
          "validatorIndex" -> org.json4s.JString(s"0x${w.validatorIndex.toString(16)}"),
          "address" -> org.json4s.JString(s"0x${w.address.bytes.map("%02x".format(_)).mkString}"),
          "amount" -> org.json4s.JString(s"0x${w.amount.toString(16)}")
        ): JValue
      }
    }
    (rawTxs, withdrawals)
  }

  /** Convert an ExecutionPayload into a Block. */
  private def payloadToBlock(payload: ExecutionPayload): Block = {
    // Decode transactions from raw bytes
    val signedTxs = payload.transactions.map { txBytes =>
      txBytes.toArray.toSignedTransaction
    }

    // Determine header extra fields based on which optional payload fields are present
    val withdrawalsRoot = computeWithdrawalsRoot(payload.withdrawals.getOrElse(Seq.empty))
    val pbbr = payload.parentBeaconBlockRoot.getOrElse(ByteString(new Array[Byte](32)))

    val extraFields =
      (payload.executionRequests, payload.blobGasUsed, payload.excessBlobGas, payload.withdrawals) match {
        case (Some(requests), Some(bgu), Some(ebg), _) =>
          // Prague/Electra: has executionRequests → HefPostPrague with requestsHash
          HefPostPrague(
            baseFee = payload.baseFeePerGas,
            withdrawalsRoot = withdrawalsRoot,
            blobGasUsed = bgu,
            excessBlobGas = ebg,
            parentBeaconBlockRoot = pbbr,
            requestsHash = computeRequestsHash(requests)
          )
        case (None, Some(bgu), Some(ebg), _) =>
          // Cancun: has blob gas fields
          HefPostCancun(
            baseFee = payload.baseFeePerGas,
            withdrawalsRoot = withdrawalsRoot,
            blobGasUsed = bgu,
            excessBlobGas = ebg,
            parentBeaconBlockRoot = pbbr
          )
        case (_, _, _, Some(_)) =>
          HefPostShanghai(
            baseFee = payload.baseFeePerGas,
            withdrawalsRoot = withdrawalsRoot
          )
        case _ =>
          HefPostOlympia(baseFee = payload.baseFeePerGas)
      }

    val header = BlockHeader(
      parentHash = payload.parentHash,
      ommersHash = BlockHeader.EmptyOmmers,
      beneficiary = payload.feeRecipient.bytes,
      stateRoot = payload.stateRoot,
      transactionsRoot = computeTransactionsRoot(signedTxs),
      receiptsRoot = payload.receiptsRoot,
      logsBloom = payload.logsBloom,
      difficulty = 0,
      number = payload.blockNumber,
      gasLimit = payload.gasLimit,
      gasUsed = payload.gasUsed,
      unixTimestamp = payload.timestamp,
      extraData = payload.extraData,
      mixHash = payload.prevRandao,
      nonce = ByteString(new Array[Byte](8)),
      extraFields = extraFields
    )

    val body = BlockBody(
      transactionList = signedTxs,
      uncleNodesList = Seq.empty,
      withdrawals = payload.withdrawals
    )

    Block(header, body)
  }

  /** Compute requestsHash per EIP-7685: sha256(sha256(request_0) ++ sha256(request_1) ++ ...)
    */
  private def computeRequestsHash(requests: Seq[ByteString]): ByteString = {
    val outerDigest = MessageDigest.getInstance("SHA-256")
    requests.foreach { request =>
      if (request.length > 1) {
        val innerDigest = MessageDigest.getInstance("SHA-256")
        innerDigest.update(request.toArray)
        outerDigest.update(innerDigest.digest())
      }
    }
    ByteString(outerDigest.digest())
  }

  /** Compute the withdrawals trie root via ephemeral MPT (same approach as StdBlockValidator). */
  private def computeWithdrawalsRoot(withdrawals: Seq[Withdrawal]): ByteString =
    if (withdrawals.isEmpty) {
      BlockHeader.EmptyMpt
    } else {
      val serializable = new ByteArraySerializable[Withdrawal] {
        override def fromBytes(bytes: Array[Byte]): Withdrawal = bytes.toWithdrawal
        override def toBytes(input: Withdrawal): Array[Byte] = rlpEncode(WithdrawalEnc(input).toRLPEncodable)
      }
      val stateStorage = com.chipprbots.ethereum.db.storage.StateStorage.getReadOnlyStorage(
        com.chipprbots.ethereum.db.dataSource.EphemDataSource()
      )
      val trie = com.chipprbots.ethereum.mpt.MerklePatriciaTrie[Int, Withdrawal](
        source = stateStorage
      )(MptListValidator.intByteArraySerializable, serializable)
      val root = withdrawals.zipWithIndex.foldLeft(trie)((t, r) => t.put(r._2, r._1)).getRootHash
      ByteString(root)
    }

  /** Compute the transactions trie root via ephemeral MPT (same approach as StdBlockValidator). */
  private def computeTransactionsRoot(txs: Seq[SignedTransaction]): ByteString =
    if (txs.isEmpty) {
      BlockHeader.EmptyMpt
    } else {
      val stateStorage = com.chipprbots.ethereum.db.storage.StateStorage.getReadOnlyStorage(
        com.chipprbots.ethereum.db.dataSource.EphemDataSource()
      )
      val trie = com.chipprbots.ethereum.mpt.MerklePatriciaTrie[Int, SignedTransaction](
        source = stateStorage
      )(MptListValidator.intByteArraySerializable, SignedTransaction.byteArraySerializable)
      val root = txs.zipWithIndex.foldLeft(trie)((t, r) => t.put(r._2, r._1)).getRootHash
      ByteString(root)
    }
}

/** EIP-4844 / EIP-7691 blob gas computation utilities. */
object BlobGasUtils {
  val GAS_PER_BLOB: BigInt = BigInt(131072)

  // Cancun (EIP-4844): 3 target, 6 max
  val CANCUN_TARGET_BLOB_GAS: BigInt = BigInt(393216) // 3 * 131072
  val CANCUN_MAX_BLOB_GAS: BigInt = BigInt(786432) // 6 * 131072

  // Prague (EIP-7691): 6 target, 9 max
  val PRAGUE_TARGET_BLOB_GAS: BigInt = BigInt(786432) // 6 * 131072
  val PRAGUE_MAX_BLOB_GAS: BigInt = BigInt(1179648) // 9 * 131072

  // Default (Cancun) values
  val TARGET_BLOB_GAS_PER_BLOCK: BigInt = CANCUN_TARGET_BLOB_GAS
  val BLOB_BASE_FEE_UPDATE_FRACTION: BigInt = BigInt(3338477)
  // EIP-7691 (Prague): BLOB_BASE_FEE_UPDATE_FRACTION bumped to scale with 9-blob MAX.
  val PRAGUE_BLOB_BASE_FEE_UPDATE_FRACTION: BigInt = BigInt(5007716)
  val MIN_BLOB_BASE_FEE: BigInt = BigInt(1)

  /** Calculate excess blob gas for a block from its parent's excess and used blob gas. Per EIP-4844: if parent_excess +
    * parent_used < target then 0 else parent_excess + parent_used - target
    */
  def calcExcessBlobGas(
      parentExcessBlobGas: BigInt,
      parentBlobGasUsed: BigInt,
      target: BigInt = TARGET_BLOB_GAS_PER_BLOCK
  ): BigInt = {
    val total = parentExcessBlobGas + parentBlobGasUsed
    if (total < target) BigInt(0)
    else total - target
  }

  /** Calculate the blob gas price using the fake exponential function. Per EIP-4844:
    * fake_exponential(MIN_BLOB_BASE_FEE, excess_blob_gas, BLOB_BASE_FEE_UPDATE_FRACTION)
    */
  def getBlobGasPrice(excessBlobGas: BigInt): BigInt =
    fakeExponential(MIN_BLOB_BASE_FEE, excessBlobGas, BLOB_BASE_FEE_UPDATE_FRACTION)

  /** EIP-7918: Osaka blob base fee floored by execution gas cost. Prevents blob base fee from decoupling from execution
    * fee market. Formula (Osaka spec): blob_base_fee = max(current_blob_fee, (blob_base_fee_update_fraction *
    * block_base_fee) / (gas_per_blob * MAX_BLOBS_PER_BLOCK)) Simplified conservative floor: max(current, block_base_fee
    * / CEILING_RATIO).
    */
  def getBlobGasPriceOsaka(excessBlobGas: BigInt, blockBaseFee: BigInt): BigInt = {
    val base = fakeExponential(MIN_BLOB_BASE_FEE, excessBlobGas, BLOB_BASE_FEE_UPDATE_FRACTION)
    val floor = blockBaseFee / BigInt(8) // conservative: blob_fee ≥ base_fee / 8
    base.max(floor).max(MIN_BLOB_BASE_FEE)
  }

  /** fake_exponential: approximates factor * e^(numerator / denominator) using Taylor expansion. Per EIP-4844 spec.
    */
  private def fakeExponential(factor: BigInt, numerator: BigInt, denominator: BigInt): BigInt = {
    var i = BigInt(1)
    var output = BigInt(0)
    var numeratorAccum = factor * denominator
    while (numeratorAccum > 0) {
      output += numeratorAccum
      numeratorAccum = (numeratorAccum * numerator) / (denominator * i)
      i += 1
    }
    output / denominator
  }
}
