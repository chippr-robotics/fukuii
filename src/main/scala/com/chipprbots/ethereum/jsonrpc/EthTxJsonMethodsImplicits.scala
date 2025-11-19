package com.chipprbots.ethereum.jsonrpc

import org.json4s.JsonAST._
import org.json4s.JsonDSL._

import com.chipprbots.ethereum.jsonrpc.EthTxService._
import com.chipprbots.ethereum.jsonrpc.JsonRpcError.InvalidParams
import com.chipprbots.ethereum.jsonrpc.serialization.JsonEncoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonEncoder.OptionToNull._
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder.NoParamsMethodDecoder

object EthTxJsonMethodsImplicits extends JsonMethodsImplicits {

  import org.json4s.CustomSerializer

  // Manual encoder for TxLog to avoid Scala 3 reflection issues
  private def encodeTxLog(log: FilterManager.TxLog): JValue =
    JObject(
      "logIndex" -> encodeAsHex(log.logIndex),
      "transactionIndex" -> encodeAsHex(log.transactionIndex),
      "transactionHash" -> encodeAsHex(log.transactionHash),
      "blockHash" -> encodeAsHex(log.blockHash),
      "blockNumber" -> encodeAsHex(log.blockNumber),
      "address" -> encodeAsHex(log.address.bytes),
      "data" -> encodeAsHex(log.data),
      "topics" -> JArray(log.topics.toList.map(encodeAsHex))
    )

  // Custom serializers for json4s Extraction.decompose to work in tests
  implicit val transactionResponseCustomSerializer: CustomSerializer[TransactionResponse] =
    new CustomSerializer[TransactionResponse](_ =>
      (
        PartialFunction.empty,
        { case tx: TransactionResponse => transactionResponseJsonEncoder.encodeJson(tx) }
      )
    )

  implicit val transactionReceiptResponseCustomSerializer: CustomSerializer[TransactionReceiptResponse] =
    new CustomSerializer[TransactionReceiptResponse](_ =>
      (
        PartialFunction.empty,
        { case receipt: TransactionReceiptResponse => transactionReceiptResponseJsonEncoder.encodeJson(receipt) }
      )
    )

  // Manual encoder for TransactionReceiptResponse to avoid Scala 3 reflection issues
  implicit val transactionReceiptResponseJsonEncoder: JsonEncoder[TransactionReceiptResponse] = { receipt =>
    // Build base fields
    val baseFields = List(
      "transactionHash" -> encodeAsHex(receipt.transactionHash),
      "transactionIndex" -> encodeAsHex(receipt.transactionIndex),
      "blockNumber" -> encodeAsHex(receipt.blockNumber),
      "blockHash" -> encodeAsHex(receipt.blockHash),
      "from" -> encodeAsHex(receipt.from.bytes)
    )

    // Add "to" field only if it's defined (omit for contract creation)
    val toField = receipt.to.map(addr => "to" -> encodeAsHex(addr.bytes)).toList

    // Continue with more fields
    val middleFields = List(
      "cumulativeGasUsed" -> encodeAsHex(receipt.cumulativeGasUsed),
      "gasUsed" -> encodeAsHex(receipt.gasUsed),
      "contractAddress" -> receipt.contractAddress.map(addr => encodeAsHex(addr.bytes)).getOrElse(JNull),
      "logs" -> JArray(receipt.logs.toList.map(encodeTxLog)),
      "logsBloom" -> encodeAsHex(receipt.logsBloom)
    )

    // Add "root" field only if it's defined (pre-Byzantium)
    val rootField = receipt.root.map(r => "root" -> encodeAsHex(r)).toList

    // Add "status" field only if it's defined (post-Byzantium)
    val statusField = receipt.status.map(s => "status" -> encodeAsHex(s)).toList

    JObject(baseFields ::: toField ::: middleFields ::: rootField ::: statusField)
  }

  implicit val transactionResponseJsonEncoder: JsonEncoder[TransactionResponse] = { tx =>
    JObject(
      "hash" -> encodeAsHex(tx.hash),
      "nonce" -> encodeAsHex(tx.nonce),
      "blockHash" -> tx.blockHash.map(encodeAsHex).getOrElse(JNull),
      "blockNumber" -> tx.blockNumber.map(encodeAsHex).getOrElse(JNull),
      "transactionIndex" -> tx.transactionIndex.map(encodeAsHex).getOrElse(JNull),
      "from" -> tx.from.map(encodeAsHex).getOrElse(JNull),
      "to" -> tx.to.map(encodeAsHex).getOrElse(JNull),
      "value" -> encodeAsHex(tx.value),
      "gasPrice" -> encodeAsHex(tx.gasPrice),
      "gas" -> encodeAsHex(tx.gas),
      "input" -> encodeAsHex(tx.input)
    )
  }

  implicit val eth_gasPrice: NoParamsMethodDecoder[GetGasPriceRequest] with JsonEncoder[GetGasPriceResponse] =
    new NoParamsMethodDecoder(GetGasPriceRequest()) with JsonEncoder[GetGasPriceResponse] {
      override def encodeJson(t: GetGasPriceResponse): JValue = encodeAsHex(t.price)
    }

  implicit val eth_pendingTransactions
      : NoParamsMethodDecoder[EthPendingTransactionsRequest] with JsonEncoder[EthPendingTransactionsResponse] =
    new NoParamsMethodDecoder(EthPendingTransactionsRequest()) with JsonEncoder[EthPendingTransactionsResponse] {

      override def encodeJson(t: EthPendingTransactionsResponse): JValue =
        JArray(t.pendingTransactions.toList.map { pendingTx =>
          encodeAsHex(pendingTx.stx.tx.hash)
        })
    }

  implicit val eth_getTransactionByHash
      : JsonMethodDecoder[GetTransactionByHashRequest] with JsonEncoder[GetTransactionByHashResponse] =
    new JsonMethodDecoder[GetTransactionByHashRequest] with JsonEncoder[GetTransactionByHashResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, GetTransactionByHashRequest] =
        params match {
          case Some(JArray(JString(txHash) :: Nil)) =>
            for {
              parsedTxHash <- extractHash(txHash)
            } yield GetTransactionByHashRequest(parsedTxHash)
          case _ => Left(InvalidParams())
        }

      override def encodeJson(t: GetTransactionByHashResponse): JValue =
        JsonEncoder.encode(t.txResponse)
    }

  implicit val eth_getTransactionReceipt
      : JsonMethodDecoder[GetTransactionReceiptRequest] with JsonEncoder[GetTransactionReceiptResponse] =
    new JsonMethodDecoder[GetTransactionReceiptRequest] with JsonEncoder[GetTransactionReceiptResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, GetTransactionReceiptRequest] =
        params match {
          case Some(JArray(JString(txHash) :: Nil)) =>
            for {
              parsedTxHash <- extractHash(txHash)
            } yield GetTransactionReceiptRequest(parsedTxHash)
          case _ => Left(InvalidParams())
        }

      override def encodeJson(t: GetTransactionReceiptResponse): JValue =
        JsonEncoder.encode(t.txResponse)
    }

  implicit val GetTransactionByBlockHashAndIndexResponseEncoder
      : JsonEncoder[GetTransactionByBlockHashAndIndexResponse] =
    new JsonEncoder[GetTransactionByBlockHashAndIndexResponse] {
      override def encodeJson(t: GetTransactionByBlockHashAndIndexResponse): JValue =
        JsonEncoder.encode(t.transactionResponse)
    }

  implicit val GetTransactionByBlockHashAndIndexRequestDecoder
      : JsonMethodDecoder[GetTransactionByBlockHashAndIndexRequest] =
    new JsonMethodDecoder[GetTransactionByBlockHashAndIndexRequest] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, GetTransactionByBlockHashAndIndexRequest] =
        params match {
          case Some(JArray(JString(blockHash) :: transactionIndex :: Nil)) =>
            for {
              parsedBlockHash <- extractHash(blockHash)
              parsedTransactionIndex <- extractQuantity(transactionIndex)
            } yield GetTransactionByBlockHashAndIndexRequest(parsedBlockHash, parsedTransactionIndex)
          case _ => Left(InvalidParams())
        }
    }

  implicit val GetTransactionByBlockNumberAndIndexResponseEncoder
      : JsonEncoder[GetTransactionByBlockNumberAndIndexResponse] =
    new JsonEncoder[GetTransactionByBlockNumberAndIndexResponse] {
      override def encodeJson(t: GetTransactionByBlockNumberAndIndexResponse): JValue =
        JsonEncoder.encode(t.transactionResponse)
    }

  implicit val GetTransactionByBlockNumberAndIndexRequestDecoder
      : JsonMethodDecoder[GetTransactionByBlockNumberAndIndexRequest] =
    new JsonMethodDecoder[GetTransactionByBlockNumberAndIndexRequest] {
      override def decodeJson(
          params: Option[JArray]
      ): Either[JsonRpcError, GetTransactionByBlockNumberAndIndexRequest] =
        params match {
          case Some(JArray(blockParam :: transactionIndex :: Nil)) =>
            for {
              blockParam <- extractBlockParam(blockParam)
              parsedTransactionIndex <- extractQuantity(transactionIndex)
            } yield GetTransactionByBlockNumberAndIndexRequest(blockParam, parsedTransactionIndex)
          case _ => Left(InvalidParams())
        }
    }

  implicit val eth_sendRawTransaction
      : JsonMethodDecoder[SendRawTransactionRequest] with JsonEncoder[SendRawTransactionResponse] =
    new JsonMethodDecoder[SendRawTransactionRequest] with JsonEncoder[SendRawTransactionResponse] {
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, SendRawTransactionRequest] =
        params match {
          case Some(JArray(JString(dataStr) :: Nil)) =>
            for {
              data <- extractBytes(dataStr)
            } yield SendRawTransactionRequest(data)
          case _ => Left(InvalidParams())
        }

      def encodeJson(t: SendRawTransactionResponse): JValue = encodeAsHex(t.transactionHash)
    }

  implicit val RawTransactionResponseJsonEncoder: JsonEncoder[RawTransactionResponse] =
    new JsonEncoder[RawTransactionResponse] {
      override def encodeJson(t: RawTransactionResponse): JValue =
        t.transactionResponse.map((RawTransactionCodec.asRawTransaction _).andThen(encodeAsHex))
    }

}
