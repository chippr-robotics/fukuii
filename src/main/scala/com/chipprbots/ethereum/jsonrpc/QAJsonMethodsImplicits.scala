package com.chipprbots.ethereum.jsonrpc

import org.json4s.JsonAST._
import org.json4s._

import com.chipprbots.ethereum.jsonrpc.JsonRpcError.InvalidParams
import com.chipprbots.ethereum.jsonrpc.QAService._
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodCodec

object QAJsonMethodsImplicits extends JsonMethodsImplicits {
  implicit val qa_mineBlocks: JsonMethodCodec[MineBlocksRequest, MineBlocksResponse] =
    new JsonMethodCodec[MineBlocksRequest, MineBlocksResponse] {
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, MineBlocksRequest] =
        params match {
          case Some(JArray(JInt(numBlocks) :: JBool(withTransactions) :: Nil)) =>
            Right(MineBlocksRequest(numBlocks.toInt, withTransactions))
          case Some(JArray(JInt(numBlocks) :: JBool(withTransactions) :: JNull :: Nil)) =>
            Right(MineBlocksRequest(numBlocks.toInt, withTransactions))

          case Some(JArray(JInt(numBlocks) :: JBool(withTransactions) :: JString(parentBlock) :: Nil)) =>
            for {
              parentBlockHash <- extractBytes(parentBlock)
            } yield MineBlocksRequest(numBlocks.toInt, withTransactions, Some(parentBlockHash))
          case _ =>
            Left(InvalidParams())
        }

      def encodeJson(t: MineBlocksResponse): JValue = JObject(
        "responseType" -> JString(t.responseType.entryName),
        "message" -> t.message.fold[JValue](JNull)(JString.apply)
      )
    }
}
