package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.Withdrawal

/** Engine API domain types per the execution-apis specification. */

/** Execution payload as delivered by engine_newPayload. */
case class ExecutionPayload(
    parentHash: ByteString,
    feeRecipient: Address,
    stateRoot: ByteString,
    receiptsRoot: ByteString,
    logsBloom: ByteString,
    prevRandao: ByteString,
    blockNumber: BigInt,
    gasLimit: BigInt,
    gasUsed: BigInt,
    timestamp: Long,
    extraData: ByteString,
    baseFeePerGas: BigInt,
    blockHash: ByteString,
    transactions: Seq[ByteString],
    // Shanghai+
    withdrawals: Option[Seq[Withdrawal]] = None,
    // Cancun+
    blobGasUsed: Option[BigInt] = None,
    excessBlobGas: Option[BigInt] = None,
    // Cancun+ (passed as separate newPayload param, not in payload object)
    parentBeaconBlockRoot: Option[ByteString] = None,
    // Prague/Electra+ (EIP-7685, passed as separate newPayload param)
    executionRequests: Option[Seq[ByteString]] = None
)

/** Payload attributes for engine_forkchoiceUpdated (optional payload building). */
case class PayloadAttributes(
    timestamp: Long,
    prevRandao: ByteString,
    suggestedFeeRecipient: Address,
    // Shanghai+
    withdrawals: Option[Seq[Withdrawal]] = None,
    // Cancun+
    parentBeaconBlockRoot: Option[ByteString] = None
)

/** Status values for PayloadStatusV1 */
sealed trait PayloadStatus {
  def value: String
}
object PayloadStatus {
  case object Valid extends PayloadStatus { val value = "VALID" }
  case object Invalid extends PayloadStatus { val value = "INVALID" }
  case object Syncing extends PayloadStatus { val value = "SYNCING" }
  case object Accepted extends PayloadStatus { val value = "ACCEPTED" }
  case class InvalidBlockHash(msg: String) extends PayloadStatus { val value = "INVALID_BLOCK_HASH" }
}

/** Response to engine_newPayload */
case class PayloadStatusV1(
    status: PayloadStatus,
    latestValidHash: Option[ByteString] = None,
    validationError: Option[String] = None
)

/** Response to engine_forkchoiceUpdated */
case class ForkchoiceUpdatedResponse(
    payloadStatus: PayloadStatusV1,
    payloadId: Option[ByteString] = None
)

/** Payload ID for tracking built payloads */
case class PayloadId(id: ByteString)
