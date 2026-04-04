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
import com.chipprbots.ethereum.rlp.rawDecode
import com.chipprbots.ethereum.rlp.{encode => rlpEncode}
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Logger

/** Core Engine API logic. Converts ExecutionPayloads to Blocks, validates, and executes them.
  * Integrates with ForkChoiceManager for CL-driven fork choice.
  */
class EngineApiService(
    blockchainReader: BlockchainReader,
    blockchainWriter: BlockchainWriter,
    blockExecution: BlockExecution,
    forkChoiceManager: ForkChoiceManager
)(implicit blockchainConfig: BlockchainConfig)
    extends Logger {

  /** engine_newPayloadV1/V2/V3/V4 — Validate and execute a new payload from the CL. */
  def newPayload(payload: ExecutionPayload): IO[PayloadStatusV1] = IO {
    // 1. Convert ExecutionPayload → Block
    val block = payloadToBlock(payload)

    // 2. Verify block hash matches
    if (block.header.hash != payload.blockHash) {
      log.warn(
        s"newPayload: block hash mismatch. Computed=${block.header.hashAsHexString}, " +
          s"payload=${com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(payload.blockHash)}"
      )
      PayloadStatusV1(InvalidBlockHash("block hash mismatch"))
    } else if (blockchainReader.getBlockHeaderByHash(payload.blockHash).isDefined) {
      // Already known
      PayloadStatusV1(Valid, latestValidHash = Some(payload.blockHash))
    } else if (blockchainReader.getBlockHeaderByHash(payload.parentHash).isEmpty) {
      // Parent not known — we need to sync
      log.info(s"newPayload: parent ${payload.parentHash} not known, returning SYNCING")
      EngineApiMetrics.recordNewPayload("SYNCING", payload.blockNumber.toLong, payload.timestamp)
      PayloadStatusV1(Syncing)
    } else {
      // 3. Execute the block
      blockExecution.executeAndValidateBlock(block) match {
        case Right(receipts) =>
          // Store the block
          blockchainWriter.storeBlock(block)
          blockchainWriter.storeReceipts(block.header.hash, receipts)
          log.info(s"newPayload: block ${block.header.number} executed and stored successfully")
          EngineApiMetrics.recordNewPayload("VALID", payload.blockNumber.toLong, payload.timestamp)
          PayloadStatusV1(Valid, latestValidHash = Some(payload.blockHash))

        case Left(error) =>
          log.warn(s"newPayload: block ${block.header.number} execution failed: $error")
          val latestValid = blockchainReader.getBlockHeaderByHash(payload.parentHash).map(_.hash)
          EngineApiMetrics.recordNewPayload("INVALID", payload.blockNumber.toLong, payload.timestamp)
          PayloadStatusV1(Invalid, latestValidHash = latestValid, validationError = Some(error.toString))
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
        // Head not known — still accept it so CL starts sending newPayload
        log.info(s"forkchoiceUpdated: head ${forkChoiceState.headBlockHash} not known, returning VALID to trigger newPayload flow")
        EngineApiMetrics.recordForkchoiceUpdated("VALID")
        ForkchoiceUpdatedResponse(
          payloadStatus = PayloadStatusV1(Valid, latestValidHash = Some(forkChoiceState.headBlockHash))
        )

      case Right(()) =>
        val payloadId = payloadAttributes.map { attrs =>
          // Generate a payload ID from the attributes (deterministic)
          val idBytes = kec256(
            forkChoiceState.headBlockHash.toArray ++
              BigInt(attrs.timestamp).toByteArray ++
              attrs.prevRandao.toArray ++
              attrs.suggestedFeeRecipient.bytes.toArray
          )
          ByteString(idBytes.take(8))
        }

        EngineApiMetrics.recordForkchoiceUpdated("VALID")
        ForkchoiceUpdatedResponse(
          payloadStatus = PayloadStatusV1(Valid, latestValidHash = Some(forkChoiceState.headBlockHash)),
          payloadId = payloadId
        )
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
      "engine_exchangeCapabilities"
    )
    log.info(s"exchangeCapabilities: CL supports ${clCapabilities.size} methods, we support ${supported.size}")
    supported
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

    val extraFields = (payload.executionRequests, payload.blobGasUsed, payload.excessBlobGas, payload.withdrawals) match {
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

  /** Compute requestsHash per EIP-7685:
    * sha256(sha256(request_0) ++ sha256(request_1) ++ ...)
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
  private def computeWithdrawalsRoot(withdrawals: Seq[Withdrawal]): ByteString = {
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
  }

  /** Compute the transactions trie root via ephemeral MPT (same approach as StdBlockValidator). */
  private def computeTransactionsRoot(txs: Seq[SignedTransaction]): ByteString = {
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
}
