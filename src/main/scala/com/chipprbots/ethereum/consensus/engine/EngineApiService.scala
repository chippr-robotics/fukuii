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

  /** Blocks that returned INVALID via newPayload. Maps blockHash → latestValidHash. forkchoiceUpdated should not accept
    * these as head. Children of invalid blocks inherit the latestValidHash of their invalid parent.
    */
  private val invalidBlocks = new java.util.concurrent.ConcurrentHashMap[ByteString, ByteString]()
  private val zeroHash = ByteString(new Array[Byte](32))

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
        s"[ENGINE-API] newPayload #${payload.blockNumber}: INVALID_BLOCK_HASH " +
          s"computed=${block.header.hashAsHexString} payload=${com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(payload.blockHash)}"
      )
      invalidBlocks.put(payload.blockHash, zeroHash)
      blockchainWriter.removeBlockByHash(payload.blockHash).commit()
      PayloadStatusV1(InvalidBlockHash("block hash mismatch"))
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
      invalidBlocks.put(payload.blockHash, propagatedLvh)
      blockchainWriter.removeBlockByHash(payload.blockHash).commit()
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
      if (headerInvalid.isDefined) {
        val latestValid = parentHeader.map(_.hash).getOrElse(zeroHash)
        invalidBlocks.put(payload.blockHash, latestValid)
        blockchainWriter.removeBlockByHash(payload.blockHash).commit()
        EngineApiMetrics.recordNewPayload("INVALID", payload.blockNumber.toLong, payload.timestamp)
        PayloadStatusV1(Invalid, latestValidHash = Some(latestValid), validationError = Some(headerInvalid.get))
      } else {

        // Tracks the tx-level reason for an execution failure so we can surface it in
        // PayloadStatus.validationError (for EEST exception mapping, e.g.
        // INSUFFICIENT_ACCOUNT_FUNDS, NONCE_MISMATCH_TOO_LOW).
        val executionErrorReason = new java.util.concurrent.atomic.AtomicReference[Option[String]](None)
        val executionResult = if (parentKnown) {
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
                  invalidBlocks.put(payload.blockHash, lvh)
                  blockchainWriter.removeBlockByHash(payload.blockHash).commit()
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
                    invalidBlocks.put(payload.blockHash, lvh)
                    blockchainWriter.removeBlockByHash(payload.blockHash).commit()
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
            // Parent unknown — store block BY HASH ONLY (not by number) so it doesn't
            // appear in eth_getBlockByNumber but can be deduped by hash.
            blockchainWriter.storeBlockByHashOnly(block).commit()
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

      // Validate safe/finalized hashes FIRST, before any SYNCING shortcut.
      // Per Engine API spec 5.4: safeBlockHash and finalizedBlockHash must be ancestors of headBlockHash.
      // If either is unknown or not an ancestor → -38002 invalid forkchoice state.
      val safeHash = forkChoiceState.safeBlockHash
      val finalizedHash = forkChoiceState.finalizedBlockHash
      val safeUnknown = safeHash != zeroHash && blockchainReader.getBlockHeaderByHash(safeHash).isEmpty
      val finalizedUnknown = finalizedHash != zeroHash && blockchainReader.getBlockHeaderByHash(finalizedHash).isEmpty
      if (safeUnknown || finalizedUnknown) {
        val msg = if (safeUnknown) "unknown safe block hash" else "unknown finalized block hash"
        EngineApiMetrics.recordForkchoiceUpdated("INVALID")
        Left(msg)
      } else if (headHeader.isDefined && !isAncestorOrEqual(safeHash, forkChoiceState.headBlockHash, zeroHash)) {
        EngineApiMetrics.recordForkchoiceUpdated("INVALID")
        Left("invalid forkchoice state: safe block is not an ancestor of head")
      } else if (headHeader.isDefined && !isAncestorOrEqual(finalizedHash, forkChoiceState.headBlockHash, zeroHash)) {
        EngineApiMetrics.recordForkchoiceUpdated("INVALID")
        Left("invalid forkchoice state: finalized block is not an ancestor of head")
      } else if (
        blockExistsByHash && !blockFullyStored && !isGenesis &&
        // executed sidechains have receipts stored; parent-unknown ACCEPTED blocks do not.
        blockchainReader.getReceiptsByHash(forkChoiceState.headBlockHash).isEmpty
      ) {
        // Block stored by hash only AND never executed (parent unknown ACCEPTED) — not fully validated
        EngineApiMetrics.recordForkchoiceUpdated("SYNCING")
        Right(ForkchoiceUpdatedResponse(payloadStatus = PayloadStatusV1(Syncing)))
      } else {

        forkChoiceManager.applyForkChoiceState(forkChoiceState) match {
          case Left(_) =>
            // Head not known — return SYNCING so CL knows we need newPayload
            EngineApiMetrics.recordForkchoiceUpdated("SYNCING")
            Right(ForkchoiceUpdatedResponse(payloadStatus = PayloadStatusV1(Syncing)))

          case Right(()) =>
            // Ancestry already validated above; proceed with payload attributes check

            // Validate payload attributes if present. Per Engine API spec, invalid payload
            // attributes (zero or parent-relative timestamp) yield JSON-RPC -38003, NOT a
            // PayloadStatus.INVALID. We tunnel the condition up via Left with a marker prefix
            // so the controller can map it to the correct error code.
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
                // Generate a payload ID from the attributes (deterministic)
                val idBytes = kec256(
                  forkChoiceState.headBlockHash.toArray ++
                    BigInt(attrs.timestamp).toByteArray ++
                    attrs.prevRandao.toArray ++
                    attrs.suggestedFeeRecipient.bytes.toArray
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

                    // Fetch pending transactions from the tx pool, filtering by chain ID
                    val pendingTxs: Seq[SignedTransaction] =
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
                        txs
                      } catch {
                        case e: Exception =>
                          log.error("Failed to fetch pending txs: {}", e.getMessage)
                          Seq.empty
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
                      else
                        HefPostShanghai(baseFee, computedWithdrawalsRoot)

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
                    val body = BlockBody(pendingTxs.toList, Nil, withdrawals = attrs.withdrawals)
                    val skeletonBlock = Block(header, body)

                    // Execute full Prague flow (preambles + txs + withdrawals + system calls + deposit collection).
                    // Fall back to BlockPreparator.prepareBlock (tx-only) for pre-Prague so we don't
                    // needlessly touch the Prague system contracts.
                    import com.chipprbots.ethereum.consensus.validators.std.MptListValidator.intByteArraySerializable
                    import com.chipprbots.ethereum.ledger.BloomFilter
                    import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
                    import com.chipprbots.ethereum.domain.Receipt
                    val (receipts, gasUsedTotal, finalStateRoot, executionRequests) =
                      if (isPrague) {
                        blockExecution.executeForProposer(skeletonBlock) match {
                          case Right(result) =>
                            (result.receipts, result.gasUsed, result.worldState.stateRootHash, result.executionRequests)
                          case Left(err) =>
                            log.error("Proposer-mode Prague execution failed: {}", err)
                            (Seq.empty[Receipt], BigInt(0), parent.header.stateRoot, Seq.empty[ByteString])
                        }
                      } else {
                        val prepared = mining.blockPreparator
                          .prepareBlock(evmCodeStorage, skeletonBlock, parent.header, None)
                        (
                          prepared.blockResult.receipts,
                          prepared.blockResult.gasUsed,
                          prepared.stateRootHash,
                          Seq.empty[ByteString]
                        )
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
    Option(pendingPayloads.remove(payloadId)) match {
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
