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
          case Some(JArray(limitParam :: rest)) =>
            val limit = limitParam match {
              case JInt(n) => Some(n.toInt)
              case JNull   => None
              case _       => return Left(InvalidParams())
            }
            val txParams = rest.headOption.flatMap {
              case obj: JObject => Some(decodeFilterParams(obj))
              case JNull        => None
              case _            => return Left(InvalidParams())
            }
            Right(TxPoolBesuPendingTransactionsRequest(limit, txParams))
          case _ =>
            Left(InvalidParams())
        }

      /** Decode a PendingTransactionsParams filter object.
        *
        * Expected JSON shape (each field is optional):
        * {{{
        * {
        *   "from":     {"eq":  "0xaddr"},
        *   "to":       {"eq":  "0xaddr"} or {"action": "deploy"},
        *   "gas":      {"gt":  "0x5208"},
        *   "gasPrice": {"lt":  "0x..."},
        *   "value":    {"eq":  "0x0"},
        *   "nonce":    {"gt":  "5"}
        * }
        * }}}
        *
        * Unknown fields and unknown predicates are silently ignored (matches Besu's
        * @JsonIgnoreProperties(ignoreUnknown = true) on PendingTransactionsParams).
        */
      private def decodeFilterParams(obj: JObject): TxPoolBesuPendingTransactionsParams = {
        val filters = obj.obj.flatMap {
          case JField(field, JObject(List(JField(predStr, JString(value))))) =>
            val pred = predStr.toLowerCase match {
              case "eq"     => Some(Eq)
              case "gt"     => Some(Gt)
              case "lt"     => Some(Lt)
              case "action" => Some(Action)
              case _        => None
            }
            pred.map(p => TxPoolFilter(field, p, value))
          case _ => None
        }
        TxPoolBesuPendingTransactionsParams(filters)
      }

      override def encodeJson(t: TxPoolBesuPendingTransactionsResponse): JValue =
        JArray(t.pendingTransactions.toList.map(tx => transactionResponseJsonEncoder.encodeJson(tx)))
    }
}
