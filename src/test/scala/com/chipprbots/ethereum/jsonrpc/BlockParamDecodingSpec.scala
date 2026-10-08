package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import org.json4s.JsonAST.*
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.jsonrpc.EthBlocksService.BlockParam

/** EIP-1898 block-parameter decoding, mirroring geth's BlockNumberOrHash.UnmarshalJSON. */
class BlockParamDecodingSpec extends AnyFreeSpec with Matchers:

  private object Decoder extends JsonMethodsImplicits:
    def decode(v: JValue): Either[JsonRpcError, BlockParam] = extractBlockParam(v)

  private val hashHex = "0x" + ("ab" * 32)
  private val hash = ByteString(Array.fill(32)(0xab.toByte))

  "extractBlockParam" - {
    "keeps plain tags, numbers and hashes working" in {
      Decoder.decode(JString("latest")) shouldBe Right(BlockParam.Latest)
      Decoder.decode(JString("0x2c")) shouldBe Right(BlockParam.WithNumber(BigInt(0x2c)))
      Decoder.decode(JString(hashHex)) shouldBe Right(BlockParam.WithHash(hash))
    }

    "decodes {blockNumber: hex} like the plain number" in {
      Decoder.decode(JObject("blockNumber" -> JString("0x2c"))) shouldBe
        Decoder.decode(JString("0x2c"))
      Decoder.decode(JObject("blockNumber" -> JString("0x2c"))) shouldBe Right(BlockParam.WithNumber(BigInt(0x2c)))
    }

    "decodes {blockNumber: tag} like the plain tag" in {
      Decoder.decode(JObject("blockNumber" -> JString("finalized"))) shouldBe Right(BlockParam.Finalized)
      Decoder.decode(JObject("blockNumber" -> JString("earliest"))) shouldBe Right(BlockParam.Earliest)
    }

    "keeps {blockHash} (with optional requireCanonical) working" in {
      Decoder.decode(JObject("blockHash" -> JString(hashHex))) shouldBe Right(BlockParam.WithHash(hash))
      Decoder.decode(
        JObject("blockHash" -> JString(hashHex), "requireCanonical" -> JBool(true))
      ) shouldBe Right(BlockParam.WithHash(hash))
    }

    "rejects invalid object shapes with InvalidParams" in {
      Decoder.decode(JObject()).left.map(_.code) shouldBe Left(JsonRpcError.InvalidParams().code)
      Decoder.decode(JObject("blockNumber" -> JString("nope"))).isLeft shouldBe true
      Decoder.decode(JObject("blockNumber" -> JNull)).isLeft shouldBe true
      Decoder.decode(JObject("blockNumber" -> JString(hashHex))).isLeft shouldBe true
      Decoder
        .decode(JObject("blockNumber" -> JString("0x1"), "blockHash" -> JString(hashHex)))
        .left
        .map(_.code) shouldBe Left(JsonRpcError.InvalidParams().code)
    }
  }
