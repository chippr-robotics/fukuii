package com.chipprbots.ethereum.jsonrpc

import org.json4s.JsonAST._
import org.json4s.jvalue2monadic

import com.chipprbots.ethereum.jsonrpc.EthBlocksService._
import com.chipprbots.ethereum.jsonrpc.EthTxJsonMethodsImplicits.transactionResponseJsonEncoder
import com.chipprbots.ethereum.jsonrpc.JsonRpcError.InvalidParams
import com.chipprbots.ethereum.jsonrpc.serialization.JsonEncoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonEncoder.OptionToNull._
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder.NoParamsMethodDecoder

object EthBlocksJsonMethodsImplicits extends JsonMethodsImplicits {

  import org.json4s.CustomSerializer

  // Custom serializer for json4s Extraction.decompose to work with BlockResponse in tests
  implicit val blockResponseCustomSerializer: CustomSerializer[BlockResponse] =
    new CustomSerializer[BlockResponse](_ =>
      (
        PartialFunction.empty,
        { case block: BlockResponse => blockResponseEncoder.encodeJson(block) }
      )
    )

  // Manual encoder for BlockResponse to avoid Scala 3 reflection issues
  implicit val blockResponseEncoder: JsonEncoder[BlockResponse] = { block =>
    val transactionsField = block.transactions match {
      case Left(hashes) =>
        JArray(hashes.toList.map(encodeAsHex))
      case Right(txs) =>
        JArray(txs.toList.map(tx => JsonEncoder.encode(tx)))
    }

    // Base fields that are always present
    val baseFields = List(
      "number" -> encodeAsHex(block.number),
      "hash" -> block.hash.map(encodeAsHex).getOrElse(JNull),
      "parentHash" -> encodeAsHex(block.parentHash),
      "nonce" -> block.nonce.map(encodeAsHex).getOrElse(JNull),
      "sha3Uncles" -> encodeAsHex(block.sha3Uncles),
      "logsBloom" -> encodeAsHex(block.logsBloom),
      "transactionsRoot" -> encodeAsHex(block.transactionsRoot),
      "stateRoot" -> encodeAsHex(block.stateRoot),
      "receiptsRoot" -> encodeAsHex(block.receiptsRoot),
      "miner" -> block.miner.map(encodeAsHex).getOrElse(JNull),
      "difficulty" -> encodeAsHex(block.difficulty),
      "totalDifficulty" -> block.totalDifficulty.map(encodeAsHex).getOrElse(JNull),
      "extraData" -> encodeAsHex(block.extraData),
      "size" -> encodeAsHex(block.size),
      "gasLimit" -> encodeAsHex(block.gasLimit),
      "gasUsed" -> encodeAsHex(block.gasUsed),
      "timestamp" -> encodeAsHex(block.timestamp),
      "mixHash" -> encodeAsHex(block.mixHash),
      "transactions" -> transactionsField,
      "uncles" -> JArray(block.uncles.toList.map(encodeAsHex))
    )

    // Post-London (EIP-1559) fields
    val baseFeeField = block.baseFeePerGas.map(v => "baseFeePerGas" -> encodeAsHex(v)).toList

    // Post-Shanghai (EIP-4895) fields
    val withdrawalsRootField = block.withdrawalsRoot.map(v => "withdrawalsRoot" -> encodeAsHex(v)).toList
    val withdrawalsField = block.withdrawals.map { ws =>
      "withdrawals" -> JArray(ws.toList.map { w =>
        JObject(w.toList.map { case (k, v) => k -> JString(v) })
      })
    }.toList

    // Post-Cancun (EIP-4844) fields
    val blobGasUsedField = block.blobGasUsed.map(v => "blobGasUsed" -> encodeAsHex(v)).toList
    val excessBlobGasField = block.excessBlobGas.map(v => "excessBlobGas" -> encodeAsHex(v)).toList
    val parentBeaconBlockRootField = block.parentBeaconBlockRoot.map(v => "parentBeaconBlockRoot" -> encodeAsHex(v)).toList

    // Post-Prague (EIP-7685) fields
    val requestsHashField = block.requestsHash.map(v => "requestsHash" -> encodeAsHex(v)).toList

    JObject(baseFields ::: baseFeeField ::: withdrawalsRootField ::: withdrawalsField :::
      blobGasUsedField ::: excessBlobGasField ::: parentBeaconBlockRootField ::: requestsHashField)
  }

  // Encoder for BaseBlockResponse (which is typically BlockResponse)
  implicit val baseBlockResponseEncoder: JsonEncoder[BaseBlockResponse] = {
    case block: BlockResponse => blockResponseEncoder.encodeJson(block)
    case other => throw new IllegalArgumentException(s"Unknown BaseBlockResponse type: ${other.getClass.getName}")
  }

  implicit val eth_blockNumber
      : NoParamsMethodDecoder[BestBlockNumberRequest] with JsonEncoder[BestBlockNumberResponse] =
    new NoParamsMethodDecoder(BestBlockNumberRequest()) with JsonEncoder[BestBlockNumberResponse] {
      override def encodeJson(t: BestBlockNumberResponse): JValue = encodeAsHex(t.bestBlockNumber)
    }

  implicit val eth_getBlockTransactionCountByHash
      : JsonMethodDecoder[TxCountByBlockHashRequest] with JsonEncoder[TxCountByBlockHashResponse] =
    new JsonMethodDecoder[TxCountByBlockHashRequest] with JsonEncoder[TxCountByBlockHashResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, TxCountByBlockHashRequest] =
        params match {
          case Some(JArray(JString(input) :: Nil)) =>
            extractHash(input).map(TxCountByBlockHashRequest.apply)
          case _ => Left(InvalidParams())
        }

      override def encodeJson(t: TxCountByBlockHashResponse): JValue =
        t.txsQuantity.map(count => encodeAsHex(BigInt(count))).getOrElse(JNull)
    }

  implicit val eth_getBlockByHash
      : JsonMethodDecoder[BlockByBlockHashRequest] with JsonEncoder[BlockByBlockHashResponse] =
    new JsonMethodDecoder[BlockByBlockHashRequest] with JsonEncoder[BlockByBlockHashResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, BlockByBlockHashRequest] =
        params match {
          case Some(JArray(JString(blockHash) :: JBool(fullTxs) :: Nil)) =>
            extractHash(blockHash).map(BlockByBlockHashRequest(_, fullTxs))
          case _ => Left(InvalidParams())
        }

      override def encodeJson(t: BlockByBlockHashResponse): JValue =
        JsonEncoder.encode(t.blockResponse)
    }

  implicit val eth_getBlockByNumber: JsonMethodDecoder[BlockByNumberRequest] with JsonEncoder[BlockByNumberResponse] =
    new JsonMethodDecoder[BlockByNumberRequest] with JsonEncoder[BlockByNumberResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, BlockByNumberRequest] =
        params match {
          case Some(JArray(blockStr :: JBool(fullTxs) :: Nil)) =>
            extractBlockParam(blockStr).map(BlockByNumberRequest(_, fullTxs))
          case _ => Left(InvalidParams())
        }

      override def encodeJson(t: BlockByNumberResponse): JValue =
        JsonEncoder.encode(t.blockResponse)
    }

  implicit val eth_getUncleByBlockHashAndIndex
      : JsonMethodDecoder[UncleByBlockHashAndIndexRequest] with JsonEncoder[UncleByBlockHashAndIndexResponse] =
    new JsonMethodDecoder[UncleByBlockHashAndIndexRequest] with JsonEncoder[UncleByBlockHashAndIndexResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, UncleByBlockHashAndIndexRequest] =
        params match {
          case Some(JArray(JString(blockHash) :: uncleIndex :: Nil)) =>
            for {
              hash <- extractHash(blockHash)
              uncleBlockIndex <- extractQuantity(uncleIndex)
            } yield UncleByBlockHashAndIndexRequest(hash, uncleBlockIndex)
          case _ => Left(InvalidParams())
        }

      override def encodeJson(t: UncleByBlockHashAndIndexResponse): JValue = {
        val uncleBlockResponse = JsonEncoder.encode(t.uncleBlockResponse)
        uncleBlockResponse.removeField {
          case JField("transactions", _) => true
          case _                         => false
        }
      }
    }

  implicit val eth_getUncleByBlockNumberAndIndex
      : JsonMethodDecoder[UncleByBlockNumberAndIndexRequest] with JsonEncoder[UncleByBlockNumberAndIndexResponse] =
    new JsonMethodDecoder[UncleByBlockNumberAndIndexRequest] with JsonEncoder[UncleByBlockNumberAndIndexResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, UncleByBlockNumberAndIndexRequest] =
        params match {
          case Some(JArray(blockStr :: uncleIndex :: Nil)) =>
            for {
              block <- extractBlockParam(blockStr)
              uncleBlockIndex <- extractQuantity(uncleIndex)
            } yield UncleByBlockNumberAndIndexRequest(block, uncleBlockIndex)
          case _ => Left(InvalidParams())
        }

      override def encodeJson(t: UncleByBlockNumberAndIndexResponse): JValue = {
        val uncleBlockResponse = JsonEncoder.encode(t.uncleBlockResponse)
        uncleBlockResponse.removeField {
          case JField("transactions", _) => true
          case _                         => false
        }
      }
    }

  implicit val eth_getUncleCountByBlockNumber
      : JsonMethodDecoder[GetUncleCountByBlockNumberRequest] with JsonEncoder[GetUncleCountByBlockNumberResponse] =
    new JsonMethodDecoder[GetUncleCountByBlockNumberRequest] with JsonEncoder[GetUncleCountByBlockNumberResponse] {
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, GetUncleCountByBlockNumberRequest] =
        params match {
          case Some(JArray((blockValue: JValue) :: Nil)) =>
            for {
              block <- extractBlockParam(blockValue)
            } yield GetUncleCountByBlockNumberRequest(block)
          case _ => Left(InvalidParams())
        }

      def encodeJson(t: GetUncleCountByBlockNumberResponse): JValue = encodeAsHex(t.result)
    }

  implicit val eth_getUncleCountByBlockHash
      : JsonMethodDecoder[GetUncleCountByBlockHashRequest] with JsonEncoder[GetUncleCountByBlockHashResponse] =
    new JsonMethodDecoder[GetUncleCountByBlockHashRequest] with JsonEncoder[GetUncleCountByBlockHashResponse] {
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, GetUncleCountByBlockHashRequest] =
        params match {
          case Some(JArray(JString(hash) :: Nil)) =>
            for {
              blockHash <- extractHash(hash)
            } yield GetUncleCountByBlockHashRequest(blockHash)
          case _ => Left(InvalidParams())
        }

      def encodeJson(t: GetUncleCountByBlockHashResponse): JValue = encodeAsHex(t.result)
    }

  implicit val eth_getBlockTransactionCountByNumber: JsonMethodDecoder[GetBlockTransactionCountByNumberRequest]
    with JsonEncoder[GetBlockTransactionCountByNumberResponse] =
    new JsonMethodDecoder[GetBlockTransactionCountByNumberRequest]
      with JsonEncoder[GetBlockTransactionCountByNumberResponse] {
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, GetBlockTransactionCountByNumberRequest] =
        params match {
          case Some(JArray((blockValue: JValue) :: Nil)) =>
            for {
              block <- extractBlockParam(blockValue)
            } yield GetBlockTransactionCountByNumberRequest(block)
          case _ => Left(InvalidParams())
        }

      def encodeJson(t: GetBlockTransactionCountByNumberResponse): JValue = encodeAsHex(t.result)
    }

  // eth_getBlockReceipts
  implicit val eth_getBlockReceipts: JsonMethodDecoder[GetBlockReceiptsRequest] with JsonEncoder[GetBlockReceiptsResponse] =
    new JsonMethodDecoder[GetBlockReceiptsRequest] with JsonEncoder[GetBlockReceiptsResponse] {
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, GetBlockReceiptsRequest] =
        params match {
          case Some(JArray((blockValue: JValue) :: Nil)) =>
            extractBlockParam(blockValue).map(GetBlockReceiptsRequest.apply)
          case _ => Left(InvalidParams())
        }

      def encodeJson(t: GetBlockReceiptsResponse): JValue =
        t.receipts.map(rs => JArray(rs.toList.map(r =>
          EthTxJsonMethodsImplicits.transactionReceiptResponseJsonEncoder.encodeJson(r)
        ))).getOrElse(JNull)
    }

  // eth_feeHistory
  implicit val eth_feeHistory: JsonMethodDecoder[FeeHistoryRequest] with JsonEncoder[FeeHistoryResponse] =
    new JsonMethodDecoder[FeeHistoryRequest] with JsonEncoder[FeeHistoryResponse] {
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, FeeHistoryRequest] =
        params match {
          case Some(JArray(blockCount :: newestBlock :: Nil)) =>
            for {
              count <- extractQuantity(blockCount)
              block <- extractBlockParam(newestBlock)
            } yield FeeHistoryRequest(count, block, None)
          case Some(JArray(blockCount :: newestBlock :: JArray(percentiles) :: Nil)) =>
            for {
              count <- extractQuantity(blockCount)
              block <- extractBlockParam(newestBlock)
            } yield FeeHistoryRequest(count, block, Some(percentiles.collect { case JDouble(d) => d; case JInt(i) => i.toDouble }))
          case _ => Left(InvalidParams())
        }

      def encodeJson(t: FeeHistoryResponse): JValue =
        JObject(
          "oldestBlock" -> encodeAsHex(t.oldestBlock),
          "baseFeePerGas" -> JArray(t.baseFeePerGas.toList.map(encodeAsHex)),
          "gasUsedRatio" -> JArray(t.gasUsedRatio.toList.map(JDouble.apply)),
          "reward" -> t.reward.map(rs => JArray(rs.toList.map(r => JArray(r.toList.map(encodeAsHex))))).getOrElse(JNull)
        )
    }

  // eth_maxPriorityFeePerGas
  implicit val eth_maxPriorityFeePerGas: NoParamsMethodDecoder[MaxPriorityFeePerGasRequest] with JsonEncoder[MaxPriorityFeePerGasResponse] =
    new NoParamsMethodDecoder(MaxPriorityFeePerGasRequest()) with JsonEncoder[MaxPriorityFeePerGasResponse] {
      def encodeJson(t: MaxPriorityFeePerGasResponse): JValue = encodeAsHex(t.maxPriorityFeePerGas)
    }

  // eth_blobBaseFee
  implicit val eth_blobBaseFee: NoParamsMethodDecoder[BlobBaseFeeRequest] with JsonEncoder[BlobBaseFeeResponse] =
    new NoParamsMethodDecoder(BlobBaseFeeRequest()) with JsonEncoder[BlobBaseFeeResponse] {
      def encodeJson(t: BlobBaseFeeResponse): JValue = encodeAsHex(t.blobBaseFee)
    }

  // debug_getRawBlock
  implicit val debug_getRawBlock: JsonMethodDecoder[GetRawBlockRequest] with JsonEncoder[GetRawBlockResponse] =
    new JsonMethodDecoder[GetRawBlockRequest] with JsonEncoder[GetRawBlockResponse] {
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, GetRawBlockRequest] =
        params match {
          case Some(JArray((blockValue: JValue) :: Nil)) =>
            extractBlockParam(blockValue).map(GetRawBlockRequest.apply)
          case _ => Left(InvalidParams())
        }

      def encodeJson(t: GetRawBlockResponse): JValue =
        t.rawBlock.map(encodeAsHex).getOrElse(JNull)
    }

  // debug_getRawHeader
  implicit val debug_getRawHeader: JsonMethodDecoder[GetRawHeaderRequest] with JsonEncoder[GetRawHeaderResponse] =
    new JsonMethodDecoder[GetRawHeaderRequest] with JsonEncoder[GetRawHeaderResponse] {
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, GetRawHeaderRequest] =
        params match {
          case Some(JArray((blockValue: JValue) :: Nil)) =>
            extractBlockParam(blockValue).map(GetRawHeaderRequest.apply)
          case _ => Left(InvalidParams())
        }

      def encodeJson(t: GetRawHeaderResponse): JValue =
        t.rawHeader.map(encodeAsHex).getOrElse(JNull)
    }

  // debug_getRawReceipts
  implicit val debug_getRawReceipts: JsonMethodDecoder[GetRawReceiptsRequest] with JsonEncoder[GetRawReceiptsResponse] =
    new JsonMethodDecoder[GetRawReceiptsRequest] with JsonEncoder[GetRawReceiptsResponse] {
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, GetRawReceiptsRequest] =
        params match {
          case Some(JArray((blockValue: JValue) :: Nil)) =>
            extractBlockParam(blockValue).map(GetRawReceiptsRequest.apply)
          case _ => Left(InvalidParams())
        }

      def encodeJson(t: GetRawReceiptsResponse): JValue =
        t.rawReceipts.map(rs => JArray(rs.toList.map(encodeAsHex))).getOrElse(JNull)
    }

}
