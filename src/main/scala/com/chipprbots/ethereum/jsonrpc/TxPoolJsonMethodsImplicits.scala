package com.chipprbots.ethereum.jsonrpc

import org.json4s.JsonAST._

import com.chipprbots.ethereum.jsonrpc.EthTxJsonMethodsImplicits.transactionResponseJsonEncoder
import com.chipprbots.ethereum.jsonrpc.JsonRpcError.InvalidParams
import com.chipprbots.ethereum.jsonrpc.TxPoolService._
import com.chipprbots.ethereum.jsonrpc.serialization.JsonEncoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonEncoder.Ops._
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder.NoParamsMethodDecoder

object TxPoolJsonMethodsImplicits extends JsonMethodsImplicits {

  implicit val txpool_besuTransactions
      : NoParamsMethodDecoder[TxPoolBesuTransactionsRequest] with JsonEncoder[TxPoolBesuTransactionsResponse] =
    new NoParamsMethodDecoder(TxPoolBesuTransactionsRequest())
      with JsonEncoder[TxPoolBesuTransactionsResponse] {
      override def encodeJson(t: TxPoolBesuTransactionsResponse): JValue =
        JArray(t.pendingTransactions.toList.map(tx => transactionResponseJsonEncoder.encodeJson(tx)))
    }

  implicit val txpool_besuStatistics
      : NoParamsMethodDecoder[TxPoolBesuStatisticsRequest] with JsonEncoder[TxPoolBesuStatisticsResponse] =
    new NoParamsMethodDecoder(TxPoolBesuStatisticsRequest())
      with JsonEncoder[TxPoolBesuStatisticsResponse] {
      override def encodeJson(t: TxPoolBesuStatisticsResponse): JValue =
        JObject(
          "maxSize"     -> JLong(t.maxSize),
          "localCount"  -> JLong(t.localCount),
          "remoteCount" -> JLong(t.remoteCount)
        )
    }

  implicit val txpool_besuPendingTransactions
      : JsonMethodDecoder[TxPoolBesuPendingTransactionsRequest]
        with JsonEncoder[TxPoolBesuPendingTransactionsResponse] =
    new JsonMethodDecoder[TxPoolBesuPendingTransactionsRequest]
      with JsonEncoder[TxPoolBesuPendingTransactionsResponse] {

      override def decodeJson(
          params: Option[JArray]
      ): Either[JsonRpcError, TxPoolBesuPendingTransactionsRequest] =
        params match {
          case None | Some(JArray(Nil)) =>
            Right(TxPoolBesuPendingTransactionsRequest(None))
          case Some(JArray(JInt(limit) :: _)) =>
            Right(TxPoolBesuPendingTransactionsRequest(Some(limit.toInt)))
          case _ =>
            Left(InvalidParams())
        }

      override def encodeJson(t: TxPoolBesuPendingTransactionsResponse): JValue =
        JArray(t.pendingTransactions.toList.map(tx => transactionResponseJsonEncoder.encodeJson(tx)))
    }
}
