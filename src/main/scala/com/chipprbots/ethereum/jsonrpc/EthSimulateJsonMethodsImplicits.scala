package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import org.json4s.JsonAST._
import org.json4s.JsonDSL._
import org.json4s.DefaultFormats
import org.json4s.jvalue2monadic
import org.json4s.jvalue2extractable

import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.jsonrpc.EthSimulateService._
import com.chipprbots.ethereum.jsonrpc.JsonRpcError.InvalidParams
import com.chipprbots.ethereum.jsonrpc.serialization.{JsonEncoder, JsonMethodDecoder}

object EthSimulateJsonMethodsImplicits extends JsonMethodsImplicits {
  implicit override val formats: org.json4s.Formats = org.json4s.DefaultFormats

  implicit val eth_simulateV1: JsonMethodDecoder[EthSimulateRequest] with JsonEncoder[EthSimulateResponse] =
    new JsonMethodDecoder[EthSimulateRequest] with JsonEncoder[EthSimulateResponse] {

      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, EthSimulateRequest] =
        params match {
          case Some(JArray((payload: JObject) :: rest)) =>
            val blockTag = rest match {
              case (bt: JValue) :: _ => extractBlockParam(bt).getOrElse(BlockParam.Latest)
              case _ => BlockParam.Latest
            }
            decodePayload(payload, blockTag)
          case _ => Left(InvalidParams("expected [payload, blockTag]"))
        }

      private def decodePayload(obj: JObject, blockTag: BlockParam): Either[JsonRpcError, EthSimulateRequest] = {
        val validation = (obj \ "validation").extractOpt[Boolean].getOrElse(false)
        val returnFullTxs = (obj \ "returnFullTransactions").extractOpt[Boolean].getOrElse(false)
        val traceTransfers = (obj \ "traceTransfers").extractOpt[Boolean].getOrElse(false)

        val blockStateCalls = (obj \ "blockStateCalls") match {
          case JArray(items) =>
            val parsed = items.map {
              case bsc: JObject => decodeBlockStateCall(bsc)
              case _ => Left(InvalidParams("blockStateCall must be object"))
            }
            if (parsed.exists(_.isLeft)) return parsed.collectFirst { case Left(e) => Left(e) }.get
            Right(parsed.collect { case Right(v) => v })
          case JNothing | JNull => Right(Seq.empty)
          case _ => Left(InvalidParams("blockStateCalls must be array"))
        }

        blockStateCalls.map { bscs =>
          EthSimulateRequest(bscs, validation, returnFullTxs, traceTransfers, blockTag)
        }
      }

      private def decodeBlockStateCall(obj: JObject): Either[JsonRpcError, BlockStateCall] = {
        val blockOverrides = (obj \ "blockOverrides") match {
          case bo: JObject => Some(decodeBlockOverrides(bo))
          case _ => None
        }

        val stateOverrides = (obj \ "stateOverrides") match {
          case JObject(fields) =>
            val parsed = fields.map { case (addrHex, value: JObject) =>
              extractAddress(JString(addrHex)) match {
                case Right(addr) => Right((addr, decodeStateOverride(value)))
                case Left(_) => Left(InvalidParams(s"invalid address: $addrHex"))
              }
              case (k, _) => Left(InvalidParams(s"invalid state override entry: $k"))
            }
            if (parsed.exists(_.isLeft)) return parsed.collectFirst { case Left(e) => Left(e) }.get
            Some(parsed.collect { case Right((k, v)) => (k, v) }.toMap)
          case _ => None
        }

        val calls = (obj \ "calls") match {
          case JArray(items) =>
            val parsed = items.map {
              case c: JObject => decodeSimulateCall(c)
              case _ => Left(InvalidParams("call must be object"))
            }
            if (parsed.exists(_.isLeft)) return parsed.collectFirst { case Left(e) => Left(e) }.get
            Some(parsed.collect { case Right(v) => v })
          case _ => None
        }

        Right(BlockStateCall(blockOverrides, stateOverrides, calls))
      }

      private def decodeBlockOverrides(obj: JObject): BlockOverrides = {
        BlockOverrides(
          number = optQty(obj, "number"),
          time = optQty(obj, "time"),
          gasLimit = optQty(obj, "gasLimit"),
          feeRecipient = (obj \ "feeRecipient").extractOpt[String].flatMap(s =>
            extractAddress(JString(s)).toOption),
          prevRandao = optBytes(obj, "prevRandao"),
          baseFeePerGas = optQty(obj, "baseFeePerGas"),
          blobBaseFee = optQty(obj, "blobBaseFee")
        )
      }

      private def decodeStateOverride(obj: JObject): StateOverride = {
        StateOverride(
          balance = optQty(obj, "balance"),
          nonce = optQty(obj, "nonce"),
          code = optBytes(obj, "code"),
          state = decodeStorageMap(obj, "state"),
          stateDiff = decodeStorageMap(obj, "stateDiff"),
          movePrecompileToAddress = (obj \ "movePrecompileToAddress").extractOpt[String]
            .flatMap(s => extractAddress(JString(s)).toOption)
        )
      }

      private def decodeStorageMap(obj: JObject, field: String): Option[Map[BigInt, BigInt]] = {
        def hexToBigInt(hex: String): BigInt = {
          val clean = hex.stripPrefix("0x").stripPrefix("0X")
          if (clean.isEmpty) BigInt(0)
          else {
            val padded = if (clean.length % 2 != 0) "0" + clean else clean
            BigInt(1, org.bouncycastle.util.encoders.Hex.decode(padded))
          }
        }
        (obj \ field) match {
          case JObject(fields) =>
            Some(fields.map { case (keyHex, JString(valueHex)) =>
              (hexToBigInt(keyHex), hexToBigInt(valueHex))
            }.toMap)
          case _ => None
        }
      }

      private def decodeSimulateCall(obj: JObject): Either[JsonRpcError, SimulateCall] = {
        Right(SimulateCall(
          from = (obj \ "from").extractOpt[String].flatMap(s => extractAddress(JString(s)).toOption),
          to = (obj \ "to").extractOpt[String].flatMap(s => extractAddress(JString(s)).toOption),
          gas = optQty(obj, "gas"),
          value = optQty(obj, "value"),
          input = optBytes(obj, "input").orElse(optBytes(obj, "data")),
          nonce = optQty(obj, "nonce"),
          maxFeePerGas = optQty(obj, "maxFeePerGas"),
          maxPriorityFeePerGas = optQty(obj, "maxPriorityFeePerGas"),
          gasPrice = optQty(obj, "gasPrice"),
          maxFeePerBlobGas = optQty(obj, "maxFeePerBlobGas"),
          `type` = optQty(obj, "type")
        ))
      }

      private def optQty(obj: JObject, field: String): Option[BigInt] =
        (obj \ field).extractOpt[String].flatMap { s =>
          val hex = s.stripPrefix("0x").stripPrefix("0X")
          if (hex.isEmpty) Some(BigInt(0))
          else {
            val padded = if (hex.length % 2 != 0) "0" + hex else hex
            scala.util.Try(BigInt(1, org.bouncycastle.util.encoders.Hex.decode(padded))).toOption
          }
        }

      private def optBytes(obj: JObject, field: String): Option[ByteString] =
        (obj \ field).extractOpt[String].flatMap { s =>
          scala.util.Try(ByteString(org.bouncycastle.util.encoders.Hex.decode(
            s.stripPrefix("0x")))).toOption
        }

      // --- Encoder ---
      override def encodeJson(t: EthSimulateResponse): JValue = {
        JArray(t.blocks.map(encodeSimulatedBlock).toList)
      }

      private def encodeSimulatedBlock(block: SimulateBlockResult): JValue = {
        val h = block.header
        val blockHash = h.hash

        // Standard block header fields
        val headerFields = List(
          "baseFeePerGas" -> encodeAsHex(h.baseFee.getOrElse(BigInt(0))),
          "blobGasUsed" -> encodeAsHex(h.blobGasUsed.getOrElse(BigInt(0))),
          "difficulty" -> encodeAsHex(h.difficulty),
          "excessBlobGas" -> encodeAsHex(h.excessBlobGas.getOrElse(BigInt(0))),
          "extraData" -> encodeAsHex(h.extraData),
          "gasLimit" -> encodeAsHex(h.gasLimit),
          "gasUsed" -> encodeAsHex(h.gasUsed),
          "hash" -> encodeAsHex(blockHash),
          "logsBloom" -> encodeAsHex(h.logsBloom),
          "miner" -> encodeAsHex(h.beneficiary),
          "mixHash" -> encodeAsHex(h.mixHash),
          "nonce" -> encodeAsHex(h.nonce),
          "number" -> encodeAsHex(h.number),
          "parentBeaconBlockRoot" -> encodeAsHex(h.parentBeaconBlockRoot.getOrElse(ByteString(new Array[Byte](32)))),
          "parentHash" -> encodeAsHex(h.parentHash),
          "receiptsRoot" -> encodeAsHex(h.receiptsRoot),
          "requestsHash" -> encodeAsHex(h.requestsHash.getOrElse(EthSimulateService.EmptyRequestsHash)),
          "sha3Uncles" -> encodeAsHex(h.ommersHash),
          "size" -> encodeAsHex(BigInt(Block.size(Block(h, block.body)))),
          "stateRoot" -> encodeAsHex(h.stateRoot),
          "timestamp" -> encodeAsHex(BigInt(h.unixTimestamp)),
          "transactionsRoot" -> encodeAsHex(h.transactionsRoot),
          "uncles" -> JArray(Nil),
          "withdrawals" -> JArray(Nil),
          "withdrawalsRoot" -> encodeAsHex(h.withdrawalsRoot.getOrElse(EthSimulateService.EmptyWithdrawalsRoot))
        )

        // Transactions: just hashes (returnFullTransactions handled later)
        val txField = "transactions" -> JArray(block.transactions.map(tx => encodeAsHex(tx.hash)).toList)

        // Per-call results
        val callsField = "calls" -> JArray(block.calls.map(encodeCallResult(_, blockHash, h)).toList)

        JObject(headerFields :+ txField :+ callsField)
      }

      private def encodeCallResult(cr: SimulateCallResult, blockHash: ByteString, header: BlockHeader): JValue = {
        val baseFields = List(
          "returnData" -> encodeAsHex(cr.returnData),
          "gasUsed" -> encodeAsHex(cr.gasUsed),
          "maxUsedGas" -> encodeAsHex(cr.maxUsedGas),
          "status" -> encodeAsHex(cr.status)
        )

        val logsField = if (cr.error.isEmpty) {
          List("logs" -> JArray(cr.logs.map(log => encodeSimulateTxLog(log, blockHash)).toList))
        } else {
          List("logs" -> JArray(Nil))
        }

        val errorField = cr.error.map { err =>
          val errFields = List(
            "code" -> JInt(err.code),
            "message" -> JString(err.message)
          ) ++ err.data.map(d => "data" -> encodeAsHex(d))
          "error" -> JObject(errFields)
        }.toList

        JObject(baseFields ::: logsField ::: errorField)
      }

      private def encodeSimulateTxLog(log: FilterManager.TxLog, blockHash: ByteString): JValue = {
        JObject(
          "address" -> encodeAsHex(log.address.bytes),
          "blockHash" -> encodeAsHex(blockHash),
          "blockNumber" -> encodeAsHex(log.blockNumber),
          "blockTimestamp" -> encodeAsHex(log.blockTimestamp.getOrElse(BigInt(0))),
          "data" -> encodeAsHex(log.data),
          "logIndex" -> encodeAsHex(log.logIndex),
          "removed" -> JBool(false),
          "topics" -> JArray(log.topics.map(encodeAsHex).toList),
          "transactionHash" -> encodeAsHex(log.transactionHash),
          "transactionIndex" -> encodeAsHex(log.transactionIndex)
        )
      }
    }
}
