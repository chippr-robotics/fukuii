package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import org.json4s.JsonAST.*

import com.chipprbots.ethereum.jsonrpc.EthUserService.*
import com.chipprbots.ethereum.jsonrpc.JsonRpcError.InvalidParams
import com.chipprbots.ethereum.jsonrpc.serialization.JsonEncoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder

object EthUserJsonMethodsImplicits extends JsonMethodsImplicits:

  given eth_getCode: (JsonMethodDecoder[GetCodeRequest] & JsonEncoder[GetCodeResponse]) =
    new JsonMethodDecoder[GetCodeRequest] with JsonEncoder[GetCodeResponse]:
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, GetCodeRequest] =
        params match
          case Some(JArray((address: JString) :: (blockValue: JValue) :: Nil)) =>
            for
              addr <- extractAddress(address)
              block <- extractBlockParam(blockValue)
            yield GetCodeRequest(addr, block)
          case _ => Left(InvalidParams())

      def encodeJson(t: GetCodeResponse): JValue = encodeAsHex(t.result)

  given eth_getBalance: (JsonMethodDecoder[GetBalanceRequest] & JsonEncoder[GetBalanceResponse]) =
    new JsonMethodDecoder[GetBalanceRequest] with JsonEncoder[GetBalanceResponse]:
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, GetBalanceRequest] =
        params match
          case Some(JArray((addressStr: JString) :: (blockValue: JValue) :: Nil)) =>
            for
              address <- extractAddress(addressStr)
              block <- extractBlockParam(blockValue)
            yield GetBalanceRequest(address, block)
          case _ =>
            Left(InvalidParams())

      def encodeJson(t: GetBalanceResponse): JValue = encodeAsHex(t.value)

  given eth_getStorageAt: (JsonMethodDecoder[GetStorageAtRequest] & JsonEncoder[GetStorageAtResponse]) =
    new JsonMethodDecoder[GetStorageAtRequest] with JsonEncoder[GetStorageAtResponse]:
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, GetStorageAtRequest] =
        params match
          case Some(JArray((addressStr: JString) :: (positionStr: JString) :: (blockValue: JValue) :: Nil)) =>
            val keyHex = positionStr.s.stripPrefix("0x").stripPrefix("0X")
            if keyHex.length > 64 then
              Left(InvalidParams(s"""storage key too long (want at most 32 bytes): "${positionStr.s}""""))
            else
              for
                address <- extractAddress(addressStr)
                position <- extractQuantity(positionStr)
                block <- extractBlockParam(blockValue)
              yield GetStorageAtRequest(address, position, block)
          case _ => Left(InvalidParams())

      def encodeJson(t: GetStorageAtResponse): JValue =
        // eth_getStorageAt returns a full 32-byte zero-padded value per spec
        val padded =
          if t.value.length < 32 then ByteString(new Array[Byte](32 - t.value.length)) ++ t.value
          else t.value
        encodeAsHex(padded)

  given eth_getTransactionCount
      : (JsonMethodDecoder[GetTransactionCountRequest] & JsonEncoder[GetTransactionCountResponse]) =
    new JsonMethodDecoder[GetTransactionCountRequest] with JsonEncoder[GetTransactionCountResponse]:
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, GetTransactionCountRequest] =
        params match
          case Some(JArray((addressStr: JString) :: (blockValue: JValue) :: Nil)) =>
            for
              address <- extractAddress(addressStr)
              block <- extractBlockParam(blockValue)
            yield GetTransactionCountRequest(address, block)
          case _ => Left(InvalidParams())

      def encodeJson(t: GetTransactionCountResponse): JValue = encodeAsHex(t.value)

  given eth_getStorageRoot: (JsonMethodDecoder[GetStorageRootRequest] & JsonEncoder[GetStorageRootResponse]) =
    new JsonMethodDecoder[GetStorageRootRequest] with JsonEncoder[GetStorageRootResponse]:
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, GetStorageRootRequest] =
        params match
          case Some(JArray((addressStr: JString) :: (blockValue: JValue) :: Nil)) =>
            for
              address <- extractAddress(addressStr)
              block <- extractBlockParam(blockValue)
            yield GetStorageRootRequest(address, block)
          case _ => Left(InvalidParams())

      def encodeJson(t: GetStorageRootResponse): JValue = encodeAsHex(t.storageRoot)

  given eth_getStorageValues: (JsonMethodDecoder[GetStorageValuesRequest] & JsonEncoder[GetStorageValuesResponse]) =
    new JsonMethodDecoder[GetStorageValuesRequest] with JsonEncoder[GetStorageValuesResponse]:

      private def decodeSlots(rawAddress: String, slotValues: List[JValue]): Either[JsonRpcError, List[BigInt]] =
        slotValues.foldLeft[Either[JsonRpcError, List[BigInt]]](Right(Nil)) {
          case (acc, slotValue: JString) =>
            val keyHex = slotValue.s.stripPrefix("0x").stripPrefix("0X")
            if keyHex.length > 64 then
              Left(InvalidParams(s"""storage key too long (want at most 32 bytes): "${slotValue.s}""""))
            else
              for
                positions <- acc
                position <- extractQuantity(slotValue)
              yield positions :+ position
          case (_, _) => Left(InvalidParams(s"invalid storage key for address $rawAddress"))
        }

      private def decodeEntries(fields: List[(String, JValue)]): Either[JsonRpcError, Seq[StorageValuesEntry]] =
        if fields.isEmpty then Left(InvalidParams("empty request"))
        else
          fields.foldLeft[Either[JsonRpcError, List[StorageValuesEntry]]](Right(Nil)) {
            case (acc, (rawAddress, JArray(slotValues))) =>
              for
                entries <- acc
                address <- extractAddress(rawAddress)
                slots <- decodeSlots(rawAddress, slotValues)
              yield entries :+ StorageValuesEntry(rawAddress, address, slots)
            case (_, (rawAddress, _)) => Left(InvalidParams(s"invalid storage keys for address $rawAddress"))
          }

      def decodeJson(params: Option[JArray]): Either[JsonRpcError, GetStorageValuesRequest] =
        params match
          case Some(JArray(JObject(fields) :: Nil)) =>
            // Block parameter omitted — defaults to latest.
            decodeEntries(fields).map(GetStorageValuesRequest(_, BlockParam.Latest))
          case Some(JArray(JObject(fields) :: (blockValue: JValue) :: Nil)) =>
            for
              entries <- decodeEntries(fields)
              block <- extractBlockParam(blockValue)
            yield GetStorageValuesRequest(entries, block)
          case _ => Left(InvalidParams())

      def encodeJson(t: GetStorageValuesResponse): JValue =
        def pad(value: ByteString): ByteString =
          if value.length < 32 then ByteString(new Array[Byte](32 - value.length)) ++ value else value

        JObject(t.values.toList.map { case (rawAddress, values) =>
          rawAddress -> JArray(values.map(v => encodeAsHex(pad(v))).toList)
        })
