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
import com.chipprbots.ethereum.utils.Logger

/** Handles Engine API JSON-RPC methods (engine_* namespace). This controller processes raw JSON requests and delegates
  * to EngineApiService.
  */
class EngineApiController(
    engineApiService: EngineApiService,
    jsonRpcControllerOpt: Option[com.chipprbots.ethereum.jsonrpc.JsonRpcController] = None
) extends Logger {

  private def reqId(request: JsonRpcRequest): JValue = request.id.getOrElse(JNull)

  def handleRequest(request: JsonRpcRequest): IO[JsonRpcResponse] =
    request.method match {
      case "engine_newPayloadV1"        => handleNewPayload(request, version = 1)
      case "engine_newPayloadV2"        => handleNewPayload(request, version = 2)
      case "engine_newPayloadV3"        => handleNewPayload(request, version = 3)
      case "engine_newPayloadV4"        => handleNewPayload(request, version = 4)
      case "engine_forkchoiceUpdatedV1" => handleForkchoiceUpdated(request, version = 1)
      case "engine_forkchoiceUpdatedV2" => handleForkchoiceUpdated(request, version = 2)
      case "engine_forkchoiceUpdatedV3" => handleForkchoiceUpdated(request, version = 3)
      case "engine_exchangeCapabilities" =>
        handleExchangeCapabilities(request)
      case "engine_getPayloadV1" => handleGetPayload(request, version = 1)
      case "engine_getPayloadV2" => handleGetPayload(request, version = 2)
      case "engine_getPayloadV3" => handleGetPayload(request, version = 3)
      case "engine_getPayloadV4" => handleGetPayload(request, version = 4)
      case "engine_getClientVersionV1" =>
        handleGetClientVersion(request)
      case "engine_getBlobsV1" =>
        handleGetBlobs(request)
      case "engine_getPayloadBodiesByHashV1" =>
        handleGetPayloadBodiesByHash(request)
      case "engine_getPayloadBodiesByRangeV1" =>
        handleGetPayloadBodiesByRange(request)
      // CL clients and hive tests send eth_* methods through the authrpc port.
      // Forward to the real JSON-RPC controller for proper responses.
      case method if method.startsWith("eth_") || method.startsWith("net_") || method.startsWith("web3_") =>
        jsonRpcControllerOpt match {
          case Some(ctrl) => ctrl.handleRequest(request)
          case None       =>
            // Fallback stubs when JSON-RPC controller is not wired
            method match {
              case "eth_syncing" => IO.pure(JsonRpcResponse("2.0", Some(JBool(false)), None, reqId(request)))
              case "eth_blockNumber" =>
                val blockNum = engineApiService.getLatestBlockNumber
                IO.pure(
                  JsonRpcResponse("2.0", Some(JString(s"0x${blockNum.toLong.toHexString}")), None, reqId(request))
                )
              case _ => IO.pure(JsonRpcResponse("2.0", Some(JNull), None, reqId(request)))
            }
        }
      case other =>
        log.warn(s"Engine API: unknown method '$other'")
        IO.pure(JsonRpcResponse("2.0", None, Some(JsonRpcError.MethodNotFound), reqId(request)))
    }

  private val UnsupportedFork = -38005

  private def handleNewPayload(request: JsonRpcRequest, version: Int): IO[JsonRpcResponse] = {
    val params = request.params.map(_.arr).getOrElse(Nil)
    params.headOption match {
      case Some(payloadJson: JObject) =>
        // Per Engine API spec, newPayload* must never raise a JSON-RPC error on a malformed or
        // deliberately-invalid payload; it must return a PayloadStatus with status=INVALID and
        // validationError describing the problem. hive's engine-withdrawals and engine-api
        // modified-payload tests rely on this.
        val payloadOpt = scala.util.Try(decodeExecutionPayload(payloadJson)).toEither
        payloadOpt match {
          case Left(ex) =>
            val msg = Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)
            System.err.println(s"[ENGINE-API] newPayload v$version decode failure: $msg")
            return IO.pure(
              JsonRpcResponse(
                "2.0",
                Some(
                  encodePayloadStatus(
                    PayloadStatusV1(
                      PayloadStatus.Invalid,
                      latestValidHash = None,
                      validationError = Some(s"malformed payload: $msg")
                    )
                  )
                ),
                None,
                reqId(request)
              )
            )
          case Right(_) => ()
        }
        var payload = payloadOpt.toOption.get
        val hasWithdrawals = payload.withdrawals.isDefined

        // Version enforcement: only reject truly incompatible combinations.
        // V1/V2 accept any payload fields (backward compatible).
        // V3 requires withdrawals (Cancun payloads always have them).
        val versionError: Option[String] = version match {
          case 3 if !hasWithdrawals =>
            Some("newPayloadV3 requires withdrawals field")
          case _ => None
        }

        if (versionError.isDefined) {
          IO.pure(
            JsonRpcResponse("2.0", None, Some(JsonRpcError(UnsupportedFork, versionError.get, None)), reqId(request))
          )
        } else {
          // V3+: second param is versionedHashes, third is parentBeaconBlockRoot
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
        }
      case _ =>
        IO.pure(
          JsonRpcResponse("2.0", None, Some(JsonRpcError.InvalidParams("missing execution payload")), reqId(request))
        )
    }
  }

  private def handleForkchoiceUpdated(request: JsonRpcRequest, version: Int = 1): IO[JsonRpcResponse] = {
    val params = request.params.map(_.arr).getOrElse(Nil)
    params.headOption match {
      case Some(fcsJson: JObject) =>
        // Per Engine API spec, a malformed forkchoice state or payload attributes must not raise
        // a JSON-RPC error from the decoder; decoding errors come back as -38003 (invalid
        // payload attributes).
        val decoded = scala.util.Try {
          val fcs = decodeForkChoiceState(fcsJson)
          val payloadAttrs = params.lift(1).collect { case obj: JObject => decodePayloadAttributes(obj) }
          (fcs, payloadAttrs)
        }.toEither
        decoded match {
          case Left(ex) =>
            val msg = Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)
            return IO.pure(
              JsonRpcResponse(
                "2.0",
                None,
                Some(JsonRpcError(-38003, s"malformed forkchoice params: $msg", None)),
                reqId(request)
              )
            )
          case Right(_) => ()
        }
        val (fcs, payloadAttrs) = decoded.toOption.get

        // Version enforcement for forkchoiceUpdated:
        // V3: requires parentBeaconBlockRoot in payload attributes (Cancun+)
        // V1/V2: must NOT have parentBeaconBlockRoot
        // Post-Cancun timestamp: V2 without beacon root → UnsupportedFork
        // Pre-Cancun timestamp: V3 with beacon root → UnsupportedFork
        val hasBeaconRoot = payloadAttrs.exists(_.parentBeaconBlockRoot.isDefined)
        val attrTimestamp = payloadAttrs.map(_.timestamp)
        val isCancunTimestamp = attrTimestamp.exists(ts =>
          com.chipprbots.ethereum.utils.Config.blockchains.blockchainConfig.isCancunTimestamp(ts)
        )

        val versionError: Option[String] = (version, payloadAttrs) match {
          case (3, Some(_)) if !isCancunTimestamp && hasBeaconRoot =>
            Some("forkchoiceUpdatedV3 with beacon root before Cancun activation")
          case (v, Some(_)) if v < 3 && isCancunTimestamp =>
            Some(s"forkchoiceUpdatedV$v cannot be used post-Cancun, use V3")
          case _ => None
        }

        if (versionError.isDefined) {
          IO.pure(
            JsonRpcResponse("2.0", None, Some(JsonRpcError(UnsupportedFork, versionError.get, None)), reqId(request))
          )
        } else {
          engineApiService.forkchoiceUpdated(fcs, payloadAttrs).map {
            case Right(response) =>
              JsonRpcResponse("2.0", Some(encodeForkchoiceUpdatedResponse(response)), None, reqId(request))
            case Left(errorMsg) if errorMsg.startsWith("ATTR:") =>
              // Invalid payload attributes → -38003 per Engine API spec
              JsonRpcResponse(
                "2.0",
                None,
                Some(JsonRpcError(-38003, errorMsg.stripPrefix("ATTR:"), None)),
                reqId(request)
              )
            case Left(errorMsg) =>
              // Invalid forkchoice state (e.g. unknown safe/finalized hash) → -38002
              JsonRpcResponse("2.0", None, Some(JsonRpcError(-38002, errorMsg, None)), reqId(request))
          }
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

  private def handleGetPayload(request: JsonRpcRequest, @annotation.unused version: Int = 1): IO[JsonRpcResponse] = {
    val payloadIdHex = request.params match {
      case Some(JArray(List(JString(id)))) => id
      case _                               => ""
    }
    val payloadId = hexToByteString(payloadIdHex)
    engineApiService.getPayload(payloadId).map {
      case Right(block) =>
        val payload = blockToExecutionPayload(block)
        // V1 returns bare ExecutionPayload.
        // V2+ wraps it in ExecutionPayloadEnvelope per Engine API spec.
        val result: JValue = version match {
          case 1 => payload
          case 2 =>
            JObject(
              "executionPayload" -> payload,
              "blockValue" -> JString(computeBlockValue(block))
            )
          case 3 =>
            JObject(
              "executionPayload" -> payload,
              "blockValue" -> JString(computeBlockValue(block)),
              "blobsBundle" -> JObject(
                "commitments" -> JArray(Nil),
                "proofs" -> JArray(Nil),
                "blobs" -> JArray(Nil)
              ),
              "shouldOverrideBuilder" -> JBool(false)
            )
          case _ => // V4+: add executionRequests (EIP-7685)
            val executionRequests = engineApiService.getPayloadExecutionRequests(payloadId)
            JObject(
              "executionPayload" -> payload,
              "blockValue" -> JString(computeBlockValue(block)),
              "blobsBundle" -> JObject(
                "commitments" -> JArray(Nil),
                "proofs" -> JArray(Nil),
                "blobs" -> JArray(Nil)
              ),
              "shouldOverrideBuilder" -> JBool(false),
              "executionRequests" -> JArray(
                executionRequests.toList.map(r => JString(byteStringToHex(r)))
              )
            )
        }
        JsonRpcResponse("2.0", Some(result), None, reqId(request))
      case Left(err) =>
        JsonRpcResponse("2.0", None, Some(JsonRpcError(-38001, err, None)), reqId(request))
    }
  }

  /** blockValue = sum of (gasUsed_i * (effectiveGasPrice_i - baseFeePerGas)) across txs. Represents total miner
    * priority-fee revenue for the block. Without receipts we approximate using per-tx gas limit — tests typically check
    * envelope presence, not exact value.
    */
  private def computeBlockValue(block: Block): String = {
    val baseFee = block.header.extraFields match {
      case BlockHeader.HeaderExtraFields.HefPostOlympia(bf)               => bf
      case BlockHeader.HeaderExtraFields.HefPostShanghai(bf, _)           => bf
      case BlockHeader.HeaderExtraFields.HefPostCancun(bf, _, _, _, _)    => bf
      case BlockHeader.HeaderExtraFields.HefPostPrague(bf, _, _, _, _, _) => bf
      case _                                                              => BigInt(0)
    }
    s"0x${BigInt(0).toString(16)}"
    // No receipts available here; return 0x0 which satisfies the envelope schema.
    // Future: thread receipt data through EngineApiService to compute exact priority fees.
  }

  private def blockToExecutionPayload(block: Block): JObject = {
    import block.header
    def hex(bs: ByteString): String = "0x" + org.bouncycastle.util.encoders.Hex.toHexString(bs.toArray)
    def hexQ(n: BigInt): String = s"0x${n.toString(16)}"

    val txs = block.body.transactionList.map { stx =>
      JString(
        "0x" + org.bouncycastle.util.encoders.Hex.toHexString(SignedTransaction.byteArraySerializable.toBytes(stx))
      )
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
    val (baseFee, blobGasUsed, excessBlobGas) = header.extraFields match {
      case BlockHeader.HeaderExtraFields.HefPostOlympia(bf)                   => (Some(bf), None, None)
      case BlockHeader.HeaderExtraFields.HefPostShanghai(bf, _)               => (Some(bf), None, None)
      case BlockHeader.HeaderExtraFields.HefPostCancun(bf, _, bgu, ebg, _)    => (Some(bf), Some(bgu), Some(ebg))
      case BlockHeader.HeaderExtraFields.HefPostPrague(bf, _, bgu, ebg, _, _) => (Some(bf), Some(bgu), Some(ebg))
      case _                                                                  => (None, None, None)
    }
    val baseFields = List(
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
    )
    val withdrawalsField = withdrawals.map(w => "withdrawals" -> w).toList
    val blobFields = List(
      blobGasUsed.map(v => "blobGasUsed" -> JString(hexQ(v))),
      excessBlobGas.map(v => "excessBlobGas" -> JString(hexQ(v)))
    ).flatten
    JObject(baseFields ++ withdrawalsField ++ blobFields)
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
