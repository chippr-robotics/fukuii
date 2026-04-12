package com.chipprbots.ethereum.consensus.engine

import cats.effect.IO

import org.json4s.JArray
import org.json4s.JNull
import org.json4s.JObject
import org.json4s.JString
import org.json4s.JValue
import org.json4s.JsonAST.{JBool, JInt}

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.Withdrawal
import com.chipprbots.ethereum.jsonrpc.JsonRpcError
import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.jsonrpc.JsonRpcResponse
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.utils.Logger

/** Handles Engine API JSON-RPC methods (engine_* namespace). This controller processes raw JSON requests and delegates
  * to EngineApiService.
  */
class EngineApiController(engineApiService: EngineApiService) extends Logger {

  private def reqId(request: JsonRpcRequest): JValue = request.id.getOrElse(JNull)

  def handleRequest(request: JsonRpcRequest): IO[JsonRpcResponse] =
    request.method match {
      case "engine_newPayloadV1" | "engine_newPayloadV2" | "engine_newPayloadV3" =>
        handleNewPayload(request, version = 3)
      case "engine_newPayloadV4" =>
        handleNewPayload(request, version = 4)
      case "engine_forkchoiceUpdatedV1" | "engine_forkchoiceUpdatedV2" | "engine_forkchoiceUpdatedV3" =>
        handleForkchoiceUpdated(request)
      case "engine_exchangeCapabilities" =>
        handleExchangeCapabilities(request)
      case "engine_getPayloadV1" | "engine_getPayloadV2" | "engine_getPayloadV3" | "engine_getPayloadV4" =>
        handleGetPayload(request)
      case "engine_getClientVersionV1" =>
        handleGetClientVersion(request)
      case "engine_getBlobsV1" =>
        handleGetBlobs(request)
      case "engine_getPayloadBodiesByHashV1" =>
        handleGetPayloadBodiesByHash(request)
      case "engine_getPayloadBodiesByRangeV1" =>
        handleGetPayloadBodiesByRange(request)
      // CL clients also send eth_* methods through the authrpc port
      case "eth_syncing" =>
        // Return false (not syncing) — CL uses forkchoiceUpdated SYNCING status to know
        // we need blocks, not eth_syncing. Returning false tells CL the EL is responsive.
        IO.pure(JsonRpcResponse("2.0", Some(JBool(false)), None, reqId(request)))
      case "eth_getBlockByNumber" =>
        // Return null for now — CL just checks if EL is responsive
        IO.pure(JsonRpcResponse("2.0", Some(JNull), None, reqId(request)))
      case "eth_blockNumber" =>
        val blockNum = engineApiService.getLatestBlockNumber
        IO.pure(JsonRpcResponse("2.0", Some(JString(s"0x${blockNum.toLong.toHexString}")), None, reqId(request)))
      case "eth_chainId" =>
        IO.pure(JsonRpcResponse("2.0", Some(JString("0xaa36a7")), None, reqId(request)))
      case "net_version" =>
        IO.pure(JsonRpcResponse("2.0", Some(JString("11155111")), None, reqId(request)))
      case other =>
        log.warn(s"Engine API: unknown method '$other'")
        IO.pure(JsonRpcResponse("2.0", None, Some(JsonRpcError.MethodNotFound), reqId(request)))
    }

  private def handleNewPayload(request: JsonRpcRequest, version: Int): IO[JsonRpcResponse] = {
    val params = request.params.map(_.arr).getOrElse(Nil)
    params.headOption match {
      case Some(payloadJson: JObject) =>
        var payload = decodeExecutionPayload(payloadJson)

        // V3+: second param is versioned hashes array (ignored for now),
        //       third param is parentBeaconBlockRoot
        if (version >= 3) {
          val parentBeaconBlockRoot = params.lift(2).collect { case JString(hex) => hexToByteString(hex) }
          payload = payload.copy(parentBeaconBlockRoot = parentBeaconBlockRoot)
        }

        // V4: fourth param is executionRequests (EIP-7685)
        if (version >= 4) {
          val executionRequests = params.lift(3).collect { case JArray(items) =>
            items.collect { case JString(hex) => hexToByteString(hex) }
          }
          payload = payload.copy(executionRequests = executionRequests)
        }

        engineApiService.newPayload(payload).map { status =>
          JsonRpcResponse("2.0", Some(encodePayloadStatus(status)), None, reqId(request))
        }
      case _ =>
        IO.pure(
          JsonRpcResponse("2.0", None, Some(JsonRpcError.InvalidParams("missing execution payload")), reqId(request))
        )
    }
  }

  private def handleForkchoiceUpdated(request: JsonRpcRequest): IO[JsonRpcResponse] = {
    val params = request.params.map(_.arr).getOrElse(Nil)
    params.headOption match {
      case Some(fcsJson: JObject) =>
        val fcs = decodeForkChoiceState(fcsJson)
        val payloadAttrs = params.lift(1).collect { case obj: JObject => decodePayloadAttributes(obj) }
        engineApiService.forkchoiceUpdated(fcs, payloadAttrs).map { response =>
          JsonRpcResponse("2.0", Some(encodeForkchoiceUpdatedResponse(response)), None, reqId(request))
        }
      case _ =>
        IO.pure(
          JsonRpcResponse("2.0", None, Some(JsonRpcError.InvalidParams("missing fork choice state")), reqId(request))
        )
    }
  }

  private def handleExchangeCapabilities(request: JsonRpcRequest): IO[JsonRpcResponse] = {
    val clCapabilities = request.params
      .flatMap(_.arr.headOption)
      .collect { case JArray(items) => items.collect { case JString(s) => s } }
      .getOrElse(Nil)

    engineApiService.exchangeCapabilities(clCapabilities).map { supported =>
      JsonRpcResponse("2.0", Some(JArray(supported.map(JString(_)).toList)), None, reqId(request))
    }
  }

  private def handleGetPayload(request: JsonRpcRequest): IO[JsonRpcResponse] = {
    val payloadIdHex = request.params match {
      case Some(JArray(List(JString(id)))) => id
      case _ => ""
    }
    val payloadId = hexToByteString(payloadIdHex)
    engineApiService.getPayload(payloadId).map {
      case Right(block) =>
        val payload = blockToExecutionPayload(block)
        JsonRpcResponse("2.0", Some(payload), None, reqId(request))
      case Left(err) =>
        JsonRpcResponse("2.0", None, Some(JsonRpcError(-38001, err, None)), reqId(request))
    }
  }

  private def blockToExecutionPayload(block: Block): JObject = {
    import block.header
    def hex(bs: ByteString): String = "0x" + org.bouncycastle.util.encoders.Hex.toHexString(bs.toArray)
    def hexQ(n: BigInt): String = s"0x${n.toString(16)}"

    val txs = block.body.transactionList.map { stx =>
      JString("0x" + org.bouncycastle.util.encoders.Hex.toHexString(
        SignedTransaction.byteArraySerializable.toBytes(stx)))
    }
    val withdrawals = block.body.withdrawals.map { wds =>
      JArray(wds.map { w =>
        JObject(
          "index" -> JString(hexQ(w.index)),
          "validatorIndex" -> JString(hexQ(w.validatorIndex)),
          "address" -> JString(hex(w.address.bytes)),
          "amount" -> JString(hexQ(w.amount))
        )
      }.toList)
    }
    val baseFee = header.extraFields match {
      case BlockHeader.HeaderExtraFields.HefPostOlympia(baseFee) => Some(baseFee)
      case BlockHeader.HeaderExtraFields.HefPostShanghai(baseFee, _) => Some(baseFee)
      case BlockHeader.HeaderExtraFields.HefPostCancun(baseFee, _, _, _, _) => Some(baseFee)
      case BlockHeader.HeaderExtraFields.HefPostPrague(baseFee, _, _, _, _, _) => Some(baseFee)
      case _ => None
    }
    val fields = List(
      "parentHash" -> JString(hex(header.parentHash)),
      "feeRecipient" -> JString(hex(header.beneficiary)),
      "stateRoot" -> JString(hex(header.stateRoot)),
      "receiptsRoot" -> JString(hex(header.receiptsRoot)),
      "logsBloom" -> JString(hex(header.logsBloom)),
      "prevRandao" -> JString(hex(header.mixHash)),
      "blockNumber" -> JString(hexQ(header.number)),
      "gasLimit" -> JString(hexQ(header.gasLimit)),
      "gasUsed" -> JString(hexQ(header.gasUsed)),
      "timestamp" -> JString(s"0x${header.unixTimestamp.toHexString}"),
      "extraData" -> JString(hex(header.extraData)),
      "baseFeePerGas" -> JString(hexQ(baseFee.getOrElse(BigInt(0)))),
      "blockHash" -> JString(hex(header.hash)),
      "transactions" -> JArray(txs.toList)
    ) ++ withdrawals.map(w => "withdrawals" -> w).toList
    JObject(fields)
  }

  private def handleGetClientVersion(request: JsonRpcRequest): IO[JsonRpcResponse] = {
    val clientVersion = JArray(
      List(
        JObject(
          "code" -> JString("FK"),
          "name" -> JString("Fukuii"),
          "version" -> JString("0.1.240"),
          "commit" -> JString(com.chipprbots.ethereum.utils.Config.clientVersion.takeRight(8))
        )
      )
    )
    IO.pure(JsonRpcResponse("2.0", Some(clientVersion), None, reqId(request)))
  }

  private def handleGetBlobs(request: JsonRpcRequest): IO[JsonRpcResponse] = {
    // engine_getBlobsV1: return null for each requested versioned hash (we don't store blobs)
    // Lighthouse will fall back to fetching blobs from CL peers
    val hashes = request.params
      .map(_.arr)
      .getOrElse(Nil)
      .headOption
      .collect { case JArray(items) =>
        items
      }
      .getOrElse(Nil)
    val nullBlobs = hashes.map(_ => JNull)
    IO.pure(JsonRpcResponse("2.0", Some(JArray(nullBlobs)), None, reqId(request)))
  }

  private def handleGetPayloadBodiesByHash(request: JsonRpcRequest): IO[JsonRpcResponse] = {
    val hashes = request.params
      .map(_.arr)
      .getOrElse(Nil)
      .headOption
      .collect { case JArray(items) =>
        items.collect { case JString(hex) => hexToByteString(hex) }
      }
      .getOrElse(Nil)

    val bodies = hashes.map { hash =>
      engineApiService.getPayloadBodyByHash(hash).map(encodePayloadBody).getOrElse(JNull)
    }
    IO.pure(JsonRpcResponse("2.0", Some(JArray(bodies)), None, reqId(request)))
  }

  private def handleGetPayloadBodiesByRange(request: JsonRpcRequest): IO[JsonRpcResponse] = {
    val params = request.params.map(_.arr).getOrElse(Nil)
    val start = params.headOption
      .collect {
        case JString(hex) => val c = hex.stripPrefix("0x"); if (c.isEmpty) BigInt(0) else BigInt(c, 16)
        case JInt(n)      => n
      }
      .getOrElse(BigInt(0))
    val count = params
      .lift(1)
      .collect {
        case JString(hex) => val c = hex.stripPrefix("0x"); if (c.isEmpty) BigInt(0) else BigInt(c, 16)
        case JInt(n)      => n
      }
      .getOrElse(BigInt(0))

    val bodies = (0L until count.toLong.min(1024L)).map { offset =>
      engineApiService.getPayloadBodyByNumber(start + offset).map(encodePayloadBody).getOrElse(JNull)
    }.toList
    IO.pure(JsonRpcResponse("2.0", Some(JArray(bodies)), None, reqId(request)))
  }

  private def encodePayloadBody(body: (Seq[ByteString], Option[Seq[org.json4s.JValue]])): JValue = {
    val (txs, withdrawals) = body
    val txsJson = JArray(txs.map(tx => JString(byteStringToHex(tx))).toList)
    val wsJson = withdrawals.map(ws => JArray(ws.toList)).getOrElse(JNull)
    JObject("transactions" -> txsJson, "withdrawals" -> wsJson)
  }

  // --- JSON encoding/decoding helpers ---

  private def hexToByteString(hex: String): ByteString = {
    val clean = hex.stripPrefix("0x")
    if (clean.isEmpty) ByteString.empty
    else ByteString(org.bouncycastle.util.encoders.Hex.decode(clean))
  }

  private def byteStringToHex(bs: ByteString): String = "0x" + bs.map("%02x".format(_)).mkString

  private def decodeExecutionPayload(json: JObject): ExecutionPayload = {
    val fields = json.obj.toMap
    ExecutionPayload(
      parentHash = hexToByteString(extractString(fields, "parentHash")),
      feeRecipient = Address(extractString(fields, "feeRecipient")),
      stateRoot = hexToByteString(extractString(fields, "stateRoot")),
      receiptsRoot = hexToByteString(extractString(fields, "receiptsRoot")),
      logsBloom = hexToByteString(extractString(fields, "logsBloom")),
      prevRandao = hexToByteString(extractString(fields, "prevRandao")),
      blockNumber = extractQuantity(fields, "blockNumber"),
      gasLimit = extractQuantity(fields, "gasLimit"),
      gasUsed = extractQuantity(fields, "gasUsed"),
      timestamp = extractQuantity(fields, "timestamp").toLong,
      extraData = hexToByteString(extractString(fields, "extraData")),
      baseFeePerGas = extractQuantity(fields, "baseFeePerGas"),
      blockHash = hexToByteString(extractString(fields, "blockHash")),
      transactions = fields
        .get("transactions")
        .collect { case JArray(items) =>
          items.collect { case JString(hex) => hexToByteString(hex) }
        }
        .getOrElse(Seq.empty),
      withdrawals = fields.get("withdrawals").collect { case JArray(items) =>
        items.collect { case obj: JObject => decodeWithdrawal(obj) }
      },
      blobGasUsed = fields.get("blobGasUsed").collect { case JString(hex) => BigInt(hex.stripPrefix("0x"), 16) },
      excessBlobGas = fields.get("excessBlobGas").collect { case JString(hex) => BigInt(hex.stripPrefix("0x"), 16) }
    )
  }

  private def decodeWithdrawal(json: JObject): Withdrawal = {
    val fields = json.obj.toMap
    Withdrawal(
      index = extractQuantity(fields, "index"),
      validatorIndex = extractQuantity(fields, "validatorIndex"),
      address = Address(extractString(fields, "address")),
      amount = extractQuantity(fields, "amount")
    )
  }

  private def decodeForkChoiceState(json: JObject): ForkChoiceState = {
    val fields = json.obj.toMap
    ForkChoiceState(
      headBlockHash = hexToByteString(extractString(fields, "headBlockHash")),
      safeBlockHash = hexToByteString(extractString(fields, "safeBlockHash")),
      finalizedBlockHash = hexToByteString(extractString(fields, "finalizedBlockHash"))
    )
  }

  private def decodePayloadAttributes(json: JObject): PayloadAttributes = {
    val fields = json.obj.toMap
    PayloadAttributes(
      timestamp = extractQuantity(fields, "timestamp").toLong,
      prevRandao = hexToByteString(extractString(fields, "prevRandao")),
      suggestedFeeRecipient = Address(extractString(fields, "suggestedFeeRecipient")),
      withdrawals = fields.get("withdrawals").collect { case JArray(items) =>
        items.collect { case obj: JObject => decodeWithdrawal(obj) }
      },
      parentBeaconBlockRoot = fields.get("parentBeaconBlockRoot").collect { case JString(hex) => hexToByteString(hex) }
    )
  }

  private def encodePayloadStatus(status: PayloadStatusV1): JValue = {
    var fields: List[(String, JValue)] = List("status" -> JString(status.status.value))
    fields =
      fields :+ ("latestValidHash" -> status.latestValidHash.map(h => JString(byteStringToHex(h))).getOrElse(JNull))
    fields = fields :+ ("validationError" -> status.validationError.map(JString(_)).getOrElse(JNull))
    JObject(fields)
  }

  private def encodeForkchoiceUpdatedResponse(response: ForkchoiceUpdatedResponse): JValue = {
    var fields: List[(String, JValue)] = List("payloadStatus" -> encodePayloadStatus(response.payloadStatus))
    fields = fields :+ ("payloadId" -> response.payloadId.map(id => JString(byteStringToHex(id))).getOrElse(JNull))
    JObject(fields)
  }

  private def extractString(fields: Map[String, JValue], key: String): String =
    fields
      .get(key)
      .collect { case JString(s) => s }
      .getOrElse(
        throw new IllegalArgumentException(s"Missing required field: $key")
      )

  private def extractQuantity(fields: Map[String, JValue], key: String): BigInt =
    fields
      .get(key)
      .collect {
        case JString(hex) =>
          val clean = hex.stripPrefix("0x")
          if (clean.isEmpty) BigInt(0) else BigInt(clean, 16)
        case JInt(n) => n
      }
      .getOrElse(BigInt(0))
}
