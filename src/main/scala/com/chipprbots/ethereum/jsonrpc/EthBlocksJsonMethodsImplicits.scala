package com.chipprbots.ethereum.jsonrpc

import org.json4s.JsonAST.JArray
import org.json4s.JsonAST.JBool
import org.json4s.JsonAST.JField
import org.json4s.JsonAST.JNull
import org.json4s.JsonAST.JObject
import org.json4s.JsonAST.JString
import org.json4s.JsonAST.JValue
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

  // Manual encoder for CheckpointResponse to avoid Scala 3 reflection issues
  private def encodeCheckpointResponse(checkpoint: CheckpointResponse): JValue =
    JObject(
      "signatures" -> JArray(checkpoint.signatures.toList.map(sig => encodeAsHex(sig.toBytes))),
      "signers" -> JArray(checkpoint.signers.toList.map(encodeAsHex))
    )

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

    JObject(
      "number" -> encodeAsHex(block.number),
      "hash" -> block.hash.map(encodeAsHex).getOrElse(JNull),
      "parentHash" -> encodeAsHex(block.parentHash),
      "nonce" -> block.nonce.map(encodeAsHex).getOrElse(JNull),
      "mixHash" -> encodeAsHex(block.mixHash),
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
      "transactions" -> transactionsField,
      "uncles" -> JArray(block.uncles.toList.map(encodeAsHex))
    )
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
}
