package com.chipprbots.ethereum.jsonrpc

import org.json4s.JsonAST.*
import org.json4s.Merge

import com.chipprbots.ethereum.jsonrpc.EthTxJsonMethodsImplicits.transactionResponseJsonEncoder
import com.chipprbots.ethereum.jsonrpc.FukuiiService.GetAccountTransactionsRequest
import com.chipprbots.ethereum.jsonrpc.FukuiiService.GetAccountTransactionsResponse
import com.chipprbots.ethereum.jsonrpc.JsonRpcError.InvalidParams
import com.chipprbots.ethereum.jsonrpc.serialization.JsonEncoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonEncoder.Ops.*
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodCodec
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder
import com.chipprbots.ethereum.transactions.TransactionHistoryService.ExtendedTransactionData

import JsonEncoder.OptionToNull.*

object FukuiiJsonMethodImplicits extends JsonMethodsImplicits:
  given extendedTransactionDataJsonEncoder: JsonEncoder[ExtendedTransactionData] = extendedTxData =>
    val asTxResponse = TransactionResponse(
      extendedTxData.stx,
      extendedTxData.minedTransactionData.map(_.header),
      extendedTxData.minedTransactionData.map(_.transactionIndex)
    )

    val encodedTxResponse = JsonEncoder.encode(asTxResponse)
    val encodedExtension = JObject(
      "isOutgoing" -> extendedTxData.isOutgoing.jsonEncoded,
      "isPending" -> extendedTxData.isPending.jsonEncoded,
      "gasUsed" -> extendedTxData.minedTransactionData.map(_.gasUsed).jsonEncoded,
      "timestamp" -> extendedTxData.minedTransactionData.map(_.timestamp).jsonEncoded
    )

    Merge.merge(encodedTxResponse, encodedExtension)

  given fukuii_getAccountTransactions: JsonMethodCodec[GetAccountTransactionsRequest, GetAccountTransactionsResponse] =
    new JsonMethodDecoder[GetAccountTransactionsRequest] with JsonEncoder[GetAccountTransactionsResponse]:
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, GetAccountTransactionsRequest] =
        params match
          case Some(JArray(JString(addrJson) :: fromBlockJson :: toBlockJson :: Nil)) =>
            for
              addr <- extractAddress(addrJson)
              fromBlock <- extractQuantity(fromBlockJson)
              toBlock <- extractQuantity(toBlockJson)
            yield GetAccountTransactionsRequest(addr, fromBlock to toBlock)
          case _ => Left(InvalidParams())

      override def encodeJson(t: GetAccountTransactionsResponse): JValue =
        JObject("transactions" -> t.transactions.jsonEncoded)
