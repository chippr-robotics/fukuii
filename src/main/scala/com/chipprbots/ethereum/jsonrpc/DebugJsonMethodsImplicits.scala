package com.chipprbots.ethereum.jsonrpc

import org.json4s.JsonAST._
import org.json4s.JsonDSL._

import com.chipprbots.ethereum.jsonrpc.DebugService._
import com.chipprbots.ethereum.jsonrpc.JsonRpcError.InvalidParams
import com.chipprbots.ethereum.jsonrpc.serialization.JsonEncoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodCodec
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder.NoParamsMethodDecoder

object DebugJsonMethodsImplicits extends JsonMethodsImplicits {

  implicit val debug_listPeersInfo: JsonMethodCodec[ListPeersInfoRequest, ListPeersInfoResponse] =
    new NoParamsMethodDecoder(ListPeersInfoRequest()) with JsonEncoder[ListPeersInfoResponse] {
      def encodeJson(t: ListPeersInfoResponse): JValue =
        JArray(t.peers.map(a => JString(a.toString)))
    }

  implicit val debug_getRawHeader: JsonMethodDecoder[GetRawHeaderRequest] with JsonEncoder[GetRawHeaderResponse] =
    new JsonMethodDecoder[GetRawHeaderRequest] with JsonEncoder[GetRawHeaderResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, GetRawHeaderRequest] =
        params match {
          case Some(JArray(blockValue :: Nil)) =>
            extractBlockParam(blockValue).map(GetRawHeaderRequest(_))
          case _ => Left(InvalidParams())
        }

      override def encodeJson(t: GetRawHeaderResponse): JValue =
        t.headerRlp.map(encodeAsHex).getOrElse(JNull)
    }

  implicit val debug_getRawBlock: JsonMethodDecoder[GetRawBlockRequest] with JsonEncoder[GetRawBlockResponse] =
    new JsonMethodDecoder[GetRawBlockRequest] with JsonEncoder[GetRawBlockResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, GetRawBlockRequest] =
        params match {
          case Some(JArray(blockValue :: Nil)) =>
            extractBlockParam(blockValue).map(GetRawBlockRequest(_))
          case _ => Left(InvalidParams())
        }

      override def encodeJson(t: GetRawBlockResponse): JValue =
        t.blockRlp.map(encodeAsHex).getOrElse(JNull)
    }

  implicit val debug_getRawReceipts
      : JsonMethodDecoder[GetRawReceiptsRequest] with JsonEncoder[GetRawReceiptsResponse] =
    new JsonMethodDecoder[GetRawReceiptsRequest] with JsonEncoder[GetRawReceiptsResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, GetRawReceiptsRequest] =
        params match {
          case Some(JArray(blockValue :: Nil)) =>
            extractBlockParam(blockValue).map(GetRawReceiptsRequest(_))
          case _ => Left(InvalidParams())
        }

      override def encodeJson(t: GetRawReceiptsResponse): JValue =
        t.receiptsRlp.map(rlps => JArray(rlps.toList.map(encodeAsHex))).getOrElse(JNull)
    }

  implicit val debug_getRawTransaction
      : JsonMethodDecoder[GetRawTransactionRequest] with JsonEncoder[GetRawTransactionResponse] =
    new JsonMethodDecoder[GetRawTransactionRequest] with JsonEncoder[GetRawTransactionResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, GetRawTransactionRequest] =
        params match {
          case Some(JArray(JString(txHash) :: Nil)) =>
            extractHash(txHash).map(GetRawTransactionRequest(_))
          case _ => Left(InvalidParams())
        }

      override def encodeJson(t: GetRawTransactionResponse): JValue =
        t.txRlp.map(encodeAsHex).getOrElse(JNull)
    }

  implicit val debug_getBadBlocks: NoParamsMethodDecoder[GetBadBlocksRequest] with JsonEncoder[GetBadBlocksResponse] =
    new NoParamsMethodDecoder(GetBadBlocksRequest()) with JsonEncoder[GetBadBlocksResponse] {
      override def encodeJson(t: GetBadBlocksResponse): JValue =
        JArray(t.badBlocks.toList.map { bb =>
          ("hash" -> encodeAsHex(bb.hash)) ~ ("number" -> encodeAsHex(bb.number))
        })
    }

  implicit val debug_setHead: JsonMethodDecoder[SetHeadRequest] with JsonEncoder[SetHeadResponse] =
    new JsonMethodDecoder[SetHeadRequest] with JsonEncoder[SetHeadResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, SetHeadRequest] =
        params match {
          case Some(JArray(blockNum :: Nil)) =>
            extractQuantity(blockNum).map(SetHeadRequest(_))
          case _ => Left(InvalidParams())
        }

      override def encodeJson(t: SetHeadResponse): JValue = JBool(t.success)
    }

  implicit val debug_memStats: NoParamsMethodDecoder[MemStatsRequest] with JsonEncoder[MemStatsResponse] =
    new NoParamsMethodDecoder(MemStatsRequest()) with JsonEncoder[MemStatsResponse] {
      override def encodeJson(t: MemStatsResponse): JValue =
        ("heapUsed" -> t.heapUsed) ~
          ("heapMax" -> t.heapMax) ~
          ("heapCommitted" -> t.heapCommitted) ~
          ("nonHeapUsed" -> t.nonHeapUsed) ~
          ("nonHeapCommitted" -> t.nonHeapCommitted) ~
          ("totalMemory" -> t.totalMemory) ~
          ("freeMemory" -> t.freeMemory) ~
          ("maxMemory" -> t.maxMemory)
    }

  implicit val debug_gcStats: NoParamsMethodDecoder[GcStatsRequest] with JsonEncoder[GcStatsResponse] =
    new NoParamsMethodDecoder(GcStatsRequest()) with JsonEncoder[GcStatsResponse] {
      override def encodeJson(t: GcStatsResponse): JValue =
        JArray(t.collectors.toList.map { gc =>
          ("name" -> gc.name) ~
            ("collectionCount" -> gc.collectionCount) ~
            ("collectionTimeMs" -> gc.collectionTimeMs)
        })
    }

  implicit val debug_stacks: NoParamsMethodDecoder[StacksRequest] with JsonEncoder[StacksResponse] =
    new NoParamsMethodDecoder(StacksRequest()) with JsonEncoder[StacksResponse] {
      override def encodeJson(t: StacksResponse): JValue = JString(t.stacks)
    }

  implicit val debug_setVerbosity: JsonMethodDecoder[SetVerbosityRequest] with JsonEncoder[SetVerbosityResponse] =
    new JsonMethodDecoder[SetVerbosityRequest] with JsonEncoder[SetVerbosityResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, SetVerbosityRequest] =
        params match {
          case Some(JArray(JString(level) :: Nil)) => Right(SetVerbosityRequest(level))
          case _ => Left(InvalidParams())
        }
      override def encodeJson(t: SetVerbosityResponse): JValue = JBool(t.success)
    }

  implicit val debug_setVmodule: JsonMethodDecoder[SetVmoduleRequest] with JsonEncoder[SetVmoduleResponse] =
    new JsonMethodDecoder[SetVmoduleRequest] with JsonEncoder[SetVmoduleResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, SetVmoduleRequest] =
        params match {
          case Some(JArray(JString(module) :: JString(level) :: Nil)) =>
            Right(SetVmoduleRequest(module, level))
          case _ => Left(InvalidParams())
        }
      override def encodeJson(t: SetVmoduleResponse): JValue = JBool(t.success)
    }

  implicit val debug_getVerbosity: NoParamsMethodDecoder[GetVerbosityRequest] with JsonEncoder[GetVerbosityResponse] =
    new NoParamsMethodDecoder(GetVerbosityRequest()) with JsonEncoder[GetVerbosityResponse] {
      override def encodeJson(t: GetVerbosityResponse): JValue =
        ("rootLevel" -> JString(t.rootLevel)) ~
          ("modules" -> JObject(t.modules.toList.sorted.map { case (k, v) => JField(k, JString(v)) }))
    }

  // CPU profiling (M-024)
  implicit val debug_startCpuProfile
      : JsonMethodDecoder[StartCpuProfileRequest] with JsonEncoder[StartCpuProfileResponse] =
    new JsonMethodDecoder[StartCpuProfileRequest] with JsonEncoder[StartCpuProfileResponse] {
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, StartCpuProfileRequest] =
        params match {
          case Some(JArray(JString(file) :: Nil)) => Right(StartCpuProfileRequest(Some(file)))
          case Some(JArray(Nil)) | None           => Right(StartCpuProfileRequest(None))
          case _                                  => Left(InvalidParams())
        }
      override def encodeJson(t: StartCpuProfileResponse): JValue = JBool(t.success)
    }

  implicit val debug_stopCpuProfile
      : NoParamsMethodDecoder[StopCpuProfileRequest] with JsonEncoder[StopCpuProfileResponse] =
    new NoParamsMethodDecoder(StopCpuProfileRequest()) with JsonEncoder[StopCpuProfileResponse] {
      override def encodeJson(t: StopCpuProfileResponse): JValue =
        ("file" -> t.file) ~ ("sizeBytes" -> t.sizeBytes)
    }
}
