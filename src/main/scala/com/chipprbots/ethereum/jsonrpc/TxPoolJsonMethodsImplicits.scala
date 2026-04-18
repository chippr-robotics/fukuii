package com.chipprbots.ethereum.jsonrpc

import org.json4s.JsonAST._

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.jsonrpc.EthTxJsonMethodsImplicits.transactionResponseJsonEncoder
import com.chipprbots.ethereum.jsonrpc.JsonRpcError.InvalidParams
import com.chipprbots.ethereum.jsonrpc.TxPoolService._
import com.chipprbots.ethereum.jsonrpc.serialization.JsonEncoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder.NoParamsMethodDecoder

object TxPoolJsonMethodsImplicits extends JsonMethodsImplicits {

  implicit val txpool_besuTransactions
      : NoParamsMethodDecoder[TxPoolBesuTransactionsRequest] with JsonEncoder[TxPoolBesuTransactionsResponse] =
    new NoParamsMethodDecoder(TxPoolBesuTransactionsRequest()) with JsonEncoder[TxPoolBesuTransactionsResponse] {
      override def encodeJson(t: TxPoolBesuTransactionsResponse): JValue =
        JArray(t.pendingTransactions.toList.map(tx => transactionResponseJsonEncoder.encodeJson(tx)))
    }

  implicit val txpool_besuStatistics
      : NoParamsMethodDecoder[TxPoolBesuStatisticsRequest] with JsonEncoder[TxPoolBesuStatisticsResponse] =
    new NoParamsMethodDecoder(TxPoolBesuStatisticsRequest()) with JsonEncoder[TxPoolBesuStatisticsResponse] {
      override def encodeJson(t: TxPoolBesuStatisticsResponse): JValue =
        JObject(
          "maxSize" -> JLong(t.maxSize),
          "localCount" -> JLong(t.localCount),
          "remoteCount" -> JLong(t.remoteCount)
        )
    }

  implicit val txpool_besuPendingTransactions: JsonMethodDecoder[TxPoolBesuPendingTransactionsRequest]
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
        * @JsonIgnoreProperties(ignoreUnknown
        *   \= true) on PendingTransactionsParams).
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

  // ── Geth-compatible methods ────────────────────────────────────────────────

  implicit val txpool_content: NoParamsMethodDecoder[TxPoolContentRequest] with JsonEncoder[TxPoolContentResponse] =
    new NoParamsMethodDecoder(TxPoolContentRequest()) with JsonEncoder[TxPoolContentResponse] {
      override def encodeJson(t: TxPoolContentResponse): JValue = {
        def encodeNested(m: Map[String, Map[String, TransactionResponse]]): JObject =
          JObject(m.toList.map { case (sender, byNonce) =>
            sender -> JObject(byNonce.toList.map { case (nonce, tx) =>
              nonce -> transactionResponseJsonEncoder.encodeJson(tx)
            })
          })
        JObject("pending" -> encodeNested(t.pending), "queued" -> encodeNested(t.queued))
      }
    }

  implicit val txpool_contentFrom
      : JsonMethodDecoder[TxPoolContentFromRequest] with JsonEncoder[TxPoolContentFromResponse] =
    new JsonMethodDecoder[TxPoolContentFromRequest] with JsonEncoder[TxPoolContentFromResponse] {
      override def decodeJson(
          params: Option[JArray]
      ): Either[JsonRpcError, TxPoolContentFromRequest] =
        params match {
          case Some(JArray(JString(addr) :: _)) =>
            Right(TxPoolContentFromRequest(Address(addr)))
          case _ =>
            Left(InvalidParams())
        }

      override def encodeJson(t: TxPoolContentFromResponse): JValue = {
        def encodeFlat(m: Map[String, TransactionResponse]): JObject =
          JObject(m.toList.map { case (nonce, tx) =>
            nonce -> transactionResponseJsonEncoder.encodeJson(tx)
          })
        JObject("pending" -> encodeFlat(t.pending), "queued" -> encodeFlat(t.queued))
      }
    }

  implicit val txpool_status: NoParamsMethodDecoder[TxPoolStatusRequest] with JsonEncoder[TxPoolStatusResponse] =
    new NoParamsMethodDecoder(TxPoolStatusRequest()) with JsonEncoder[TxPoolStatusResponse] {
      // core-geth uses hexutil.Uint — serialises as a hex string (e.g. "0x5")
      override def encodeJson(t: TxPoolStatusResponse): JValue =
        JObject(
          "pending" -> JString("0x" + java.lang.Long.toHexString(t.pending)),
          "queued" -> JString("0x" + java.lang.Long.toHexString(t.queued))
        )
    }

  implicit val txpool_inspect: NoParamsMethodDecoder[TxPoolInspectRequest] with JsonEncoder[TxPoolInspectResponse] =
    new NoParamsMethodDecoder(TxPoolInspectRequest()) with JsonEncoder[TxPoolInspectResponse] {
      override def encodeJson(t: TxPoolInspectResponse): JValue = {
        def encodeNested(m: Map[String, Map[String, String]]): JObject =
          JObject(m.toList.map { case (sender, byNonce) =>
            sender -> JObject(byNonce.toList.map { case (nonce, summary) =>
              nonce -> JString(summary)
            })
          })
        JObject("pending" -> encodeNested(t.pending), "queued" -> encodeNested(t.queued))
      }
    }
}
