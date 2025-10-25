package com.chipprbots.ethereum.jsonrpc.serialization

import org.json4s.JsonAST.JArray

import com.chipprbots.ethereum.jsonrpc.JsonRpcError
import com.chipprbots.ethereum.jsonrpc.JsonRpcError.InvalidParams

trait JsonMethodDecoder[T] {
  def decodeJson(params: Option[JArray]): Either[JsonRpcError, T]
}
object JsonMethodDecoder {
  class NoParamsMethodDecoder[T](request: => T) extends JsonMethodDecoder[T] {
    def decodeJson(params: Option[JArray]): Either[JsonRpcError, T] =
      params match {
        case None | Some(JArray(Nil)) => Right(request)
        case _                        => Left(InvalidParams(s"No parameters expected"))
      }
  }
}
