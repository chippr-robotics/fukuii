package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.util.ByteString

import cats.effect.IO

import java.security.MessageDigest

import com.chipprbots.ethereum.consensus.engine.PayloadStatus._
import com.chipprbots.ethereum.consensus.validators.std.MptListValidator
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields._
import com.chipprbots.ethereum.domain.Withdrawal._
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.mpt.ByteArraySerializable
import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.SignedTransactions._
import com.chipprbots.ethereum.rlp.rawDecode
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
  private implicit val askTimeout: Timeout = Timeout(3.seconds)

  /** Pending payloads built by forkchoiceUpdated, keyed by payloadId. */
  private val pendingPayloads = new java.util.concurrent.ConcurrentHashMap[ByteString, Block]()

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
      PayloadStatusV1(InvalidBlockHash("block hash mismatch"))
    } else if (blockchainReader.getBlockHeaderByHash(payload.blockHash).isDefined) {
      PayloadStatusV1(Valid, latestValidHash = Some(payload.blockHash))
    } else {
      // Try full execution if parent block is known
      val parentKnown = blockchainReader.getBlockHeaderByHash(payload.parentHash).isDefined
      val executionResult = if (parentKnown) {
        try
          blockExecution.executeAndValidateBlock(block, alreadyValidated = true) match {
            case Right(receipts) =>
              blockchainWriter.storeBlock(block).commit()
              blockchainWriter.storeReceipts(block.header.hash, receipts).commit()
              System.err.println(
                s"[ENGINE-API] newPayload #${payload.blockNumber}: EXECUTED " +
                  s"(${block.body.numberOfTxs} txs, stateRoot=${block.header.stateRoot.take(8).map("%02x".format(_)).mkString}...)"
              )
              Some(true) // fully executed
            case Left(error) =>
              error match {
                case com.chipprbots.ethereum.ledger.BlockExecutionError.MPTError(_) |
                    com.chipprbots.ethereum.ledger.BlockExecutionError.MissingParentError =>
                  // Missing state data — can't execute yet, fall back to optimistic
                  System.err.println(
                    s"[ENGINE-API] newPayload #${payload.blockNumber}: missing state (${error.reason}), optimistic import"
                  )
                  None
                case _ =>
                  // Genuine validation failure
                  System.err.println(
                    s"[ENGINE-API] newPayload #${payload.blockNumber}: execution INVALID: ${error.reason}"
                  )
                  Some(false)
              }
          }
        catch {
          case e: com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MPTException =>
            // Missing state nodes — can't execute, fall through to optimistic
            System.err.println(
              s"[ENGINE-API] newPayload #${payload.blockNumber}: missing state (${e.getMessage}), optimistic import"
            )
            None
          case e: Exception =>
            System.err.println(
              s"[ENGINE-API] newPayload #${payload.blockNumber}: unexpected error: ${e.getMessage}, optimistic import"
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
          // Execution failed — block is invalid
          val latestValid = blockchainReader.getBlockHeaderByHash(payload.parentHash).map(_.hash)
          EngineApiMetrics.recordNewPayload("INVALID", payload.blockNumber.toLong, payload.timestamp)
          PayloadStatusV1(Invalid, latestValidHash = latestValid, validationError = Some("block execution failed"))

        case None =>
          // Optimistic import: store block without execution.
          // Parent unknown or state unavailable — trust CL consensus.
          // Store block + chain weight (post-merge TD=0) so P2P handshake can look up best block weight.
          blockchainWriter
            .storeBlock(block)
            .and(blockchainWriter.storeChainWeight(block.header.hash, ChainWeight.totalDifficultyOnly(0)))
            .commit()
          blockchainWriter.saveBestKnownBlocks(block.header.hash, block.header.number)
          System.err.println(
            s"[ENGINE-API] newPayload #${payload.blockNumber}: OPTIMISTIC IMPORT " +
              s"(${block.body.numberOfTxs} txs, ${block.body.withdrawals.map(_.size).getOrElse(0)} withdrawals)"
          )
          EngineApiMetrics.recordNewPayload("VALID", payload.blockNumber.toLong, payload.timestamp)
          PayloadStatusV1(Valid, latestValidHash = Some(payload.blockHash))
      }
    }
  }

  /** engine_forkchoiceUpdatedV1/V2/V3 — Update fork choice state, optionally start payload building. */
  def forkchoiceUpdated(
      forkChoiceState: ForkChoiceState,
      payloadAttributes: Option[PayloadAttributes]
  ): IO[ForkchoiceUpdatedResponse] = IO {
    // Always accept the fork choice state from the CL.
    // In checkpoint sync mode, we won't have the head block yet, but returning VALID
    // tells the CL we're ready to receive newPayload calls for new blocks.
    forkChoiceManager.applyForkChoiceState(forkChoiceState) match {
      case Left(_) =>
        // Head not known — return SYNCING so CL knows we need newPayload
        log.info(
          s"forkchoiceUpdated: head ${forkChoiceState.headBlockHash} not known, returning SYNCING"
        )
        EngineApiMetrics.recordForkchoiceUpdated("SYNCING")
        ForkchoiceUpdatedResponse(
          payloadStatus = PayloadStatusV1(Syncing)
        )

      case Right(()) =>
        // Validate payload attributes if present
        val invalidAttrs: Option[ForkchoiceUpdatedResponse] = payloadAttributes.flatMap { attrs =>
          if (attrs.timestamp == 0) {
            Some(ForkchoiceUpdatedResponse(
              payloadStatus = PayloadStatusV1(Invalid,
                latestValidHash = Some(forkChoiceState.headBlockHash),
                validationError = Some("invalid payload attributes: zero timestamp"))))
          } else {
            val parentHeader = blockchainReader.getBlockHeaderByHash(forkChoiceState.headBlockHash)
            parentHeader.flatMap { parent =>
              if (attrs.timestamp <= parent.unixTimestamp) {
                Some(ForkchoiceUpdatedResponse(
                  payloadStatus = PayloadStatusV1(Invalid,
                    latestValidHash = Some(forkChoiceState.headBlockHash),
                    validationError = Some("invalid payload attributes: timestamp too low"))))
              } else None
            }
          }
        }
        if (invalidAttrs.isDefined) {
          EngineApiMetrics.recordForkchoiceUpdated("INVALID")
          invalidAttrs.get
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
              val baseFee: BigInt = if (parent.header.number == 0) parentBaseFee
              else if (parent.header.gasUsed == parentGasTarget) parentBaseFee
              else if (parent.header.gasUsed > parentGasTarget) {
                val delta = parentBaseFee * (parent.header.gasUsed - parentGasTarget) / parentGasTarget / 8
                parentBaseFee + (if (delta == BigInt(0)) BigInt(1) else delta)
              } else {
                val delta = parentBaseFee * (parentGasTarget - parent.header.gasUsed) / parentGasTarget / 8
                if (parentBaseFee - delta < 0) BigInt(0) else parentBaseFee - delta
              }

              // Fetch pending transactions from the tx pool
              val pendingTxs: Seq[SignedTransaction] = try {
                import com.chipprbots.ethereum.transactions.PendingTransactionsManager._
                val response = Await.result(
                  (pendingTransactionsManager ? GetPendingTransactions).mapTo[PendingTransactionsResponse],
                  3.seconds)
                response.pendingTransactions.map(_.stx.tx)
              } catch { case _: Exception => Seq.empty }

              val emptyWithdrawalsRoot = ByteString(kec256(com.chipprbots.ethereum.rlp.encode(
                com.chipprbots.ethereum.rlp.RLPValue(Array.empty[Byte]))))
              val emptyTrieRoot = ByteString(kec256(com.chipprbots.ethereum.rlp.encode(
                com.chipprbots.ethereum.rlp.RLPValue(Array.empty[Byte]))))

              // Build post-merge header directly (difficulty=0 so payBlockReward skips PoW rewards)
              val blockNumber = parent.header.number + 1
              val gasLimit = parent.header.gasLimit // keep parent gas limit
              val header = BlockHeader(
                parentHash = parent.header.hash,
                ommersHash = ByteString(kec256(com.chipprbots.ethereum.rlp.encode(
                  com.chipprbots.ethereum.rlp.RLPList()))),
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
                extraFields = HefPostShanghai(baseFee, emptyWithdrawalsRoot)
              )
              val body = BlockBody(pendingTxs.toList, Nil, withdrawals = Some(Nil))
              val block = Block(header, body)

              // Execute block to compute correct stateRoot
              val blockPreparator = mining.blockPreparator
              val prepared = blockPreparator.prepareBlock(evmCodeStorage, block, parent.header, None)

              // Update header with computed stateRoot, receiptsRoot, gasUsed, logsBloom
              import com.chipprbots.ethereum.consensus.validators.std.MptListValidator.intByteArraySerializable
              import com.chipprbots.ethereum.ledger.BloomFilter
              import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
              import com.chipprbots.ethereum.domain.Receipt
              val receipts = prepared.blockResult.receipts
              val receiptsLogs = BloomFilter.EmptyBloomFilter.toArray +: receipts.map(_.logsBloomFilter.toArray)
              val bloomFilter = ByteString(com.chipprbots.ethereum.utils.ByteUtils.or(receiptsLogs: _*))
              def buildMpt[T](items: Seq[T], ser: com.chipprbots.ethereum.mpt.ByteArraySerializable[T]): ByteString = {
                val storage = new com.chipprbots.ethereum.db.storage.SerializingMptStorage(
                  new com.chipprbots.ethereum.db.storage.ArchiveNodeStorage(
                    new com.chipprbots.ethereum.db.storage.NodeStorage(
                      com.chipprbots.ethereum.db.dataSource.EphemDataSource())))
                val trie = items.zipWithIndex.foldLeft(MerklePatriciaTrie[Int, T](storage)(intByteArraySerializable, ser)) {
                  case (t, (item, idx)) => t.put(idx, item)
                }
                ByteString(trie.getRootHash)
              }
              val updatedHeader = prepared.block.header.copy(
                stateRoot = prepared.stateRootHash,
                receiptsRoot = buildMpt(receipts, Receipt.byteArraySerializable),
                transactionsRoot = buildMpt(prepared.block.body.transactionList, SignedTransaction.byteArraySerializable),
                logsBloom = bloomFilter,
                gasUsed = prepared.blockResult.gasUsed
              )
              val payload = prepared.block.copy(header = updatedHeader)
              pendingPayloads.put(id, payload)
              log.info("Built payload {} for block {} (baseFee={}, parent={})",
                id.toArray.map("%02x".format(_)).mkString, payload.header.number, baseFee, parent.header.number)
            }
          } catch {
            case e: Exception =>
              log.error("Failed to build payload: {}", e.getMessage)
          }
          id
        }

        EngineApiMetrics.recordForkchoiceUpdated("VALID")
        ForkchoiceUpdatedResponse(
          payloadStatus = PayloadStatusV1(Valid, latestValidHash = Some(forkChoiceState.headBlockHash)),
          payloadId = payloadId
        )
        } // end else (invalidAttrs check)
    }
  }

  /** engine_getPayloadV1/V2/V3/V4 — Return a previously built payload by ID. */
  def getPayload(payloadId: ByteString): IO[Either[String, Block]] = IO {
    Option(pendingPayloads.remove(payloadId)) match {
      case Some(block) => Right(block)
      case None => Left("Payload not available")
    }
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
        import org.json4s.JsonDSL._
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
    import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.TypedTransaction._

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
