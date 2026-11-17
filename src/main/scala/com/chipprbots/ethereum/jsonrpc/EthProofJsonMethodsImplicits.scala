package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import org.json4s.JsonAST.*

import com.chipprbots.ethereum.jsonrpc.JsonRpcError.InvalidParams
import com.chipprbots.ethereum.jsonrpc.ProofService.GetProofRequest
import com.chipprbots.ethereum.jsonrpc.ProofService.GetProofResponse
import com.chipprbots.ethereum.jsonrpc.ProofService.StorageProofKey
import com.chipprbots.ethereum.jsonrpc.serialization.JsonEncoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder

object EthProofJsonMethodsImplicits extends JsonMethodsImplicits:
  /** go-ethereum `decodeHash` for a storage key: an optional `0x`, then hex of any length up to 64 digits (an odd count
    * is left-padded with a zero). Not hex, or more than 32 bytes, is -32602 with geth's message. The key keeps whether
    * it was written with 63 or 64 digits, which the response echoes as 32 bytes.
    */
  private def extractStorageKey(input: JValue): Either[JsonRpcError, StorageProofKey] =
    input match
      case JString(s) =>
        val digits = if s.startsWith("0x") || s.startsWith("0X") then s.drop(2) else s
        val quoted = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        if !digits.forall(c => Character.digit(c, 16) >= 0 && c < 128) then
          Left(InvalidParams(s"invalid hex in storage key: $quoted"))
        else if (digits.length + 1) / 2 > 32 then
          Left(InvalidParams(s"storage key too long (want at most 32 bytes): $quoted"))
        else
          val v = if digits.isEmpty then BigInt(0) else BigInt(digits, 16)
          Right(StorageProofKey(v, fixed32 = digits.length >= 63))
      case x => Left(InvalidParams(s"Invalid param storage proof key: $x"))

  def extractStorageKeys(input: JValue): Either[JsonRpcError, Seq[StorageProofKey]] =
    import cats.syntax.traverse.*
    input match
      case JArray(elems) => elems.traverse(extractStorageKey)
      case _             => Left(InvalidParams())

  private def padTo32(v: BigInt): Array[Byte] =
    val raw = v.toByteArray.dropWhile(_ == 0)
    Array.fill[Byte](32 - raw.length)(0) ++ raw

  given eth_getProof: (JsonMethodDecoder[GetProofRequest] & JsonEncoder[GetProofResponse]) =
    new JsonMethodDecoder[GetProofRequest] with JsonEncoder[GetProofResponse]:
      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, GetProofRequest] =
        params match
          case Some(JArray((address: JString) :: storageKeys :: (blockNumber: JValue) :: Nil)) =>
            for
              addressParsed <- extractAddress(address)
              storageKeysParsed <- extractStorageKeys(storageKeys)
              blockNumberParsed <- extractBlockParam(blockNumber)
            yield GetProofRequest(addressParsed, storageKeysParsed, blockNumberParsed)
          // The block parameter is OPTIONAL in execution-apis and defaults to "latest".
          // Without this case the omitted-param form fell through to InvalidParams (-32602),
          // which is what rpc-compat's eth_getProof/get-account-proof-default-block sends.
          case Some(JArray((address: JString) :: storageKeys :: Nil)) =>
            for
              addressParsed <- extractAddress(address)
              storageKeysParsed <- extractStorageKeys(storageKeys)
            yield GetProofRequest(addressParsed, storageKeysParsed, BlockParam.Latest)
          case _ => Left(InvalidParams())

      override def encodeJson(t: GetProofResponse): JValue =
        JObject(
          "address" -> encodeAsHex(t.proofAccount.address.bytes),
          "accountProof" -> JArray(t.proofAccount.accountProof.toList.map(ap => encodeAsHex(ap))),
          "balance" -> encodeAsHex(t.proofAccount.balance),
          "codeHash" -> encodeAsHex(t.proofAccount.codeHash),
          "nonce" -> encodeAsHex(t.proofAccount.nonce),
          "storageHash" -> encodeAsHex(t.proofAccount.storageHash),
          "storageProof" -> JArray(t.proofAccount.storageProof.toList.map { sp =>
            JObject(
              "key" -> (if sp.key.fixed32 then encodeAsHex(ByteString(padTo32(sp.key.v))) else encodeAsHex(sp.key.v)),
              "proof" -> JArray(sp.proof.toList.map(p => encodeAsHex(p))),
              "value" -> encodeAsHex(sp.value)
            )
          })
        )
