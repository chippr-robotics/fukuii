package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.util.ByteString

import cats.effect.IO

import org.json4s.JArray
import org.json4s.JNull
import org.json4s.JObject
import org.json4s.JString
import org.json4s.JValue
import org.json4s.JsonAST.JBool
import org.json4s.JsonAST.JInt

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.Withdrawal
import com.chipprbots.ethereum.jsonrpc.JsonRpcError
import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.jsonrpc.JsonRpcResponse
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.BuildInfo
import com.chipprbots.ethereum.utils.Logger

/** Handles Engine API JSON-RPC methods (engine_* namespace). This controller processes raw JSON requests and delegates
  * to EngineApiService.
  *
  * @param blockchainConfig
  *   the fork schedule every version gate reads. The node passes the one its EngineApiService runs on, so the method
  *   windows and the service's execution agree; the default (the global config) keeps the stub-service specs short.
  */
class EngineApiController(
    engineApiService: EngineApiService,
    jsonRpcControllerOpt: Option[com.chipprbots.ethereum.jsonrpc.JsonRpcController] = None,
    blockchainConfig: BlockchainConfig = com.chipprbots.ethereum.utils.Config.blockchains.blockchainConfig
) extends Logger:

  private def reqId(request: JsonRpcRequest): JValue = request.id.getOrElse(JNull)

  def handleRequest(request: JsonRpcRequest): IO[JsonRpcResponse] =
    request.method match
      case "engine_newPayloadV1"        => handleNewPayload(request, version = 1)
      case "engine_newPayloadV2"        => handleNewPayload(request, version = 2)
      case "engine_newPayloadV3"        => handleNewPayload(request, version = 3)
      case "engine_newPayloadV4"        => handleNewPayload(request, version = 4)
      case "engine_newPayloadV5"        => handleNewPayload(request, version = 5)
      case "engine_forkchoiceUpdatedV1" => handleForkchoiceUpdated(request, version = 1)
      case "engine_forkchoiceUpdatedV2" => handleForkchoiceUpdated(request, version = 2)
      case "engine_forkchoiceUpdatedV3" => handleForkchoiceUpdated(request, version = 3)
      case "engine_forkchoiceUpdatedV4" => handleForkchoiceUpdated(request, version = 4)
      case "engine_exchangeCapabilities" =>
        handleExchangeCapabilities(request)
      case "engine_getPayloadV1" => handleGetPayload(request, version = 1)
      case "engine_getPayloadV2" => handleGetPayload(request, version = 2)
      case "engine_getPayloadV3" => handleGetPayload(request, version = 3)
      case "engine_getPayloadV4" => handleGetPayload(request, version = 4)
      case "engine_getPayloadV5" => handleGetPayload(request, version = 5)
      case "engine_getPayloadV6" => handleGetPayload(request, version = 6)
      case "engine_getClientVersionV1" =>
        handleGetClientVersion(request)
      case "engine_getBlobsV1" =>
        handleGetBlobs(request)
      case "engine_getBlobsV2" =>
        handleGetBlobsV2(request)
      case "engine_getPayloadBodiesByHashV1" =>
        handleGetPayloadBodiesByHash(request)
      case "engine_getPayloadBodiesByRangeV1" =>
        handleGetPayloadBodiesByRange(request)
      // CL clients and hive tests send eth_* methods through the authrpc port.
      // Forward to the real JSON-RPC controller for proper responses.
      case method if method.startsWith("eth_") || method.startsWith("net_") || method.startsWith("web3_") =>
        jsonRpcControllerOpt match
          case Some(ctrl) => ctrl.handleRequest(request)
          case None       =>
            // Fallback stubs when JSON-RPC controller is not wired
            method match
              case "eth_syncing" => IO.pure(JsonRpcResponse("2.0", Some(JBool(false)), None, reqId(request)))
              case "eth_blockNumber" =>
                val blockNum = engineApiService.getLatestBlockNumber
                IO.pure(
                  JsonRpcResponse("2.0", Some(JString(s"0x${blockNum.toLong.toHexString}")), None, reqId(request))
                )
              case _ => IO.pure(JsonRpcResponse("2.0", Some(JNull), None, reqId(request)))
      case other =>
        log.warn(s"Engine API: unknown method '$other'")
        IO.pure(JsonRpcResponse("2.0", None, Some(JsonRpcError.MethodNotFound), reqId(request)))

  private val UnsupportedFork = -38005

  private def handleNewPayload(request: JsonRpcRequest, version: Int): IO[JsonRpcResponse] =
    val params = request.params.map(_.arr).getOrElse(Nil)
    params.headOption match
      case Some(payloadJson: JObject) =>
        // Per Engine API spec, newPayload* must never raise a JSON-RPC error on a malformed or
        // deliberately-invalid payload; it must return a PayloadStatus with status=INVALID and
        // validationError describing the problem. hive's engine-withdrawals and engine-api
        // modified-payload tests rely on this.
        val payloadOpt = scala.util.Try(decodeExecutionPayload(payloadJson)).toEither
        payloadOpt match
          case Left(ex) =>
            val msg = Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)
            log.warn("[ENGINE-API] newPayload v{} decode failure: {}", version, msg)
            IO.pure(
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
          case Right(decodedPayload) =>
            var payload = decodedPayload
            val hasWithdrawals = payload.withdrawals.isDefined
            val payloadTimestamp = Timestamp(payload.timestamp)
            val isShanghaiPayload = blockchainConfig.isShanghaiTimestamp(payloadTimestamp)
            val isCancunPayload = blockchainConfig.isCancunTimestamp(payloadTimestamp)
            val isPraguePayload = blockchainConfig.isPragueTimestamp(payloadTimestamp)
            val isAmsterdamPayload = blockchainConfig.isAmsterdamTimestamp(payloadTimestamp)

            // Version enforcement on payload shape (not on method-of-fork — that's -38005).
            // Hive withdrawals suite expects -32602 (InvalidParamsError) for shape mismatches:
            //   newPayloadV1: timestamp < shanghai,  withdrawals absent
            //   newPayloadV2: pre-OR-post-Shanghai,  withdrawals present iff post-Shanghai
            //   newPayloadV3: timestamp ≥ cancun,    withdrawals present
            val InvalidParams = -32602
            // Per engine-api spec and hive's engine-cancun / engine-withdrawals suites:
            //   -32602 (Invalid params): payload shape is wrong for the RPC version (e.g.
            //     V3 called pre-Cancun with V1-shape payload missing all Cancun fields)
            //   -38005 (Unsupported fork): method is wrong for the fork — applies when the
            //     payload IS shaped for Cancun (all Cancun fields present, even if zero) but
            //     timestamp is pre-Cancun, OR when V1/V2 is called for a Cancun payload.
            //
            // For V3 pre-Cancun we have to distinguish the two cases:
            //   - payload has all Cancun fields (blobGasUsed + excessBlobGas present) → -38005
            //     (the CL sent a valid Cancun-shape payload to the wrong fork)
            //   - at least one Cancun field is nil → -32602 (params shape wrong for method)
            val hasAllCancunFields =
              payload.blobGasUsed.isDefined && payload.excessBlobGas.isDefined
            val versionError: Option[(Int, String)] = version match
              case 5 => EngineApiController.newPayloadV5ParamsError(payload, params, isAmsterdamPayload)
              // V4 serves Prague, Osaka and the BPOs (go-ethereum NewPayloadV4: checkFork(Prague, Osaka, BPO1, BPO2)).
              // The two ExecutionPayloadV4 fields are refused first, as params errors, in go-ethereum's order; EEST
              // `bal_invalid_engine_payload_field_before_fork` and `invalid_pre_fork_block_with_slot_number` send
              // each to V4 before Amsterdam and expect -32602. Then the fork window: -38005 from Amsterdam on
              // (amsterdam.md "Update the methods of previous forks"), and before Prague.
              case 4 if payload.slotNumber.isDefined =>
                Some(InvalidParams -> "newPayloadV4: slotNumber not supported pre-Amsterdam, use V5")
              case 4 if payload.blockAccessList.isDefined =>
                Some(InvalidParams -> "newPayloadV4: blockAccessList not supported pre-Amsterdam, use V5")
              case 4 if isAmsterdamPayload =>
                Some(UnsupportedFork -> "newPayloadV4 cannot be used post-Amsterdam, use V5")
              case 4 if !isPraguePayload =>
                Some(UnsupportedFork -> "newPayloadV4 must only be called for Prague/Osaka payloads")
              case 3 if !isCancunPayload && hasAllCancunFields =>
                Some(UnsupportedFork -> "newPayloadV3 cannot be used pre-Cancun")
              case 3 if !isCancunPayload =>
                Some(InvalidParams -> "newPayloadV3 cannot be used pre-Cancun, use V2")
              case 3 if !hasWithdrawals =>
                Some(InvalidParams -> "newPayloadV3 requires withdrawals field")
              case 3 if !hasAllCancunFields =>
                Some(InvalidParams -> "newPayloadV3 requires blobGasUsed and excessBlobGas")
              // V3 requires the parentBeaconBlockRoot third param; we parse it after this check but
              // the expected rejection code for missing is -32602. The test framework's
              // `NewPayloadV3 After Cancun, Nil Beacon Root` variant exercises this.
              case 3 if params.lift(2).forall(_ == org.json4s.JNull) =>
                Some(InvalidParams -> "newPayloadV3 requires parentBeaconBlockRoot")
              // V3 also requires a non-null expectedBlobVersionedHashes array (params[1]).
              // Hive 'NewPayloadV3 Versioned Hashes, Nil Hashes' sends null here and expects
              // -32602 rather than VALID/ACCEPTED.
              case 3 if params.lift(1).forall(_ == org.json4s.JNull) =>
                Some(InvalidParams -> "newPayloadV3 requires expectedBlobVersionedHashes")
              case 2 if isCancunPayload =>
                Some(UnsupportedFork -> "newPayloadV2 cannot be used post-Cancun, use V3")
              case 2 if isShanghaiPayload && !hasWithdrawals =>
                Some(InvalidParams -> "newPayloadV2 post-Shanghai payload must include withdrawals")
              case 2 if !isShanghaiPayload && hasWithdrawals =>
                Some(InvalidParams -> "newPayloadV2 pre-Shanghai payload must not include withdrawals")
              // go-ethereum NewPayloadV2: a pre-Cancun payload carrying either EIP-4844 header field is a params
              // error, not an INVALID block (EEST `test_invalid_pre_fork_block_with_blob_fields`, -32602).
              case 2 if payload.excessBlobGas.isDefined =>
                Some(InvalidParams -> "newPayloadV2: non-nil excessBlobGas pre-cancun")
              case 2 if payload.blobGasUsed.isDefined =>
                Some(InvalidParams -> "newPayloadV2: non-nil blobGasUsed pre-cancun")
              case 1 if hasWithdrawals =>
                Some(InvalidParams -> "newPayloadV1 must not include withdrawals")
              case 1 if isShanghaiPayload =>
                Some(UnsupportedFork -> "newPayloadV1 cannot be used post-Shanghai, use V2")
              case _ => None

            if versionError.isDefined then
              val (code, msg) = versionError.get
              IO.pure(
                JsonRpcResponse("2.0", None, Some(JsonRpcError(code, msg, None)), reqId(request))
              )
            else
              // V3+: params[1] is expectedBlobVersionedHashes, params[2] is parentBeaconBlockRoot.
              // Previously we skipped params[1] entirely, which silently dropped the EIP-4844
              // versioned-hash check the CL relies on — every "NewPayloadV3 Versioned Hashes"
              // hive test passed the payload regardless of what the CL claimed to have seen.
              if version >= 3 then
                val expectedBlobVersionedHashes = params.lift(1).collect { case JArray(items) =>
                  items.collect { case JString(hex) => hexToByteString(hex) }
                }
                val parentBeaconBlockRoot = params.lift(2).collect { case JString(hex) => hexToByteString(hex) }
                payload = payload.copy(
                  expectedBlobVersionedHashes = expectedBlobVersionedHashes,
                  parentBeaconBlockRoot = parentBeaconBlockRoot
                )

              // V4: fourth param is executionRequests (EIP-7685)
              if version >= 4 then
                val executionRequests = params.lift(3).collect { case JArray(items) =>
                  items.collect { case JString(hex) => hexToByteString(hex) }
                }
                payload = payload.copy(executionRequests = executionRequests)

              // validateRequests: reject entries with no type prefix or non-strictly-ascending
              // type bytes. Matches go-ethereum catalyst/api.go:1257. Only applicable Prague+.
              val requestsError: Option[String] =
                if !isPraguePayload then None
                else
                  payload.executionRequests.flatMap { reqs =>
                    reqs.zipWithIndex
                      .collectFirst {
                        case (req, i) if req.length < 2 =>
                          s"empty request at index $i: entry too short, missing type prefix (len=${req.length})"
                      }
                      .orElse {
                        reqs.sliding(2).collectFirst {
                          case Seq(prev, curr) if curr.head <= prev.head =>
                            s"invalid request order: type 0x${"%02x".format(curr.head)} not strictly after 0x${"%02x".format(prev.head)}"
                        }
                      }
                  }

              if requestsError.isDefined then
                IO.pure(
                  JsonRpcResponse(
                    "2.0",
                    None,
                    Some(JsonRpcError(InvalidParams, requestsError.get, None)),
                    reqId(request)
                  )
                )
              else
                engineApiService.newPayload(payload).map { status =>
                  JsonRpcResponse("2.0", Some(encodePayloadStatus(status)), None, reqId(request))
                }
      case _ =>
        IO.pure(
          JsonRpcResponse("2.0", None, Some(JsonRpcError.InvalidParams("missing execution payload")), reqId(request))
        )

  private def handleForkchoiceUpdated(request: JsonRpcRequest, version: Int): IO[JsonRpcResponse] =
    val params = request.params.map(_.arr).getOrElse(Nil)
    params.headOption match
      case Some(fcsJson: JObject) =>
        // A forkchoiceState that does not match ForkchoiceStateV1 is -32602 (shanghai.md / cancun.md
        // engine_forkchoiceUpdatedV2/V3 point 1); nothing is applied. Payload attributes that cannot be
        // decoded stay -38003 (invalid payload attributes).
        val decoded: Either[JsonRpcError, (ForkChoiceState, Option[PayloadAttributes])] =
          for
            fcs <- decodeForkChoiceState(fcsJson).left.map(msg =>
              JsonRpcError.InvalidParams(s"invalid forkchoice state: $msg")
            )
            // engine_forkchoiceUpdatedV4's third parameter, validated like the forkchoice state (-32602, nothing
            // applied) and otherwise ignored: see EngineApiController.custodyColumnsError. Earlier versions have none.
            _ <- Option
              .when(version >= 4)(EngineApiController.custodyColumnsError(params.lift(2)))
              .flatten
              .map(JsonRpcError.InvalidParams(_))
              .toLeft(())
            payloadAttrs <- scala.util
              .Try(params.lift(1).collect { case obj: JObject => decodePayloadAttributes(obj, version) })
              .toEither
              .left
              .map { ex =>
                val msg = Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)
                JsonRpcError(-38003, s"malformed payload attributes: $msg", None)
              }
          yield (fcs, payloadAttrs)
        decoded match
          case Left(error) => IO.pure(JsonRpcResponse("2.0", None, Some(error), reqId(request)))
          case Right((fcs, payloadAttrs)) =>
            val InvalidAttrs = -38003
            val versionError: Option[(Int, String)] =
              payloadAttrs.flatMap(attrs =>
                EngineApiController.payloadAttributesVersionError(version, attrs, blockchainConfig)
              )

            if versionError.isDefined then
              val (code, msg) = versionError.get
              // Per engine-API step ordering (apply forkchoiceState, THEN validate attrs):
              // InvalidAttrs errors STILL require the forkchoice to be applied first. Hive
              // 'Invalid PayloadAttributes, Missing BeaconRoot' asserts HeaderByNumber reflects
              // the new head even on -38003. Forward an attrs-less FCU to the service, then
              // overlay the version error. UnsupportedFork (-38005) does not apply forkchoice —
              // the CL called the wrong method entirely.
              if code == InvalidAttrs then
                // The attributes error is the answer ONLY when the forkchoice state was applied to a VALID head.
                // execution-apis processes payload attributes after applying the forkchoice state and only for a
                // VALID head (paris.md engine_forkchoiceUpdatedV1 point 7, which shanghai.md and cancun.md extend
                // with these checks); hive spells the order out in suites/engine/payload_attributes.go. Every other
                // outcome keeps its own answer: SYNCING and INVALID are payload statuses (hive 'Invalid
                // PayloadAttributes, Missing BeaconRoot, Syncing=True' expects no error), and an inconsistent
                // forkchoice state is -38002 — which this branch used to report as -38003.
                engineApiService.forkchoiceUpdated(fcs, None).map {
                  case Right(response) if response.payloadStatus.status == PayloadStatus.Valid =>
                    JsonRpcResponse("2.0", None, Some(JsonRpcError(code, msg, None)), reqId(request))
                  case outcome => forkchoiceUpdatedResponse(outcome, request)
                }
              else
                IO.pure(
                  JsonRpcResponse("2.0", None, Some(JsonRpcError(code, msg, None)), reqId(request))
                )
            else engineApiService.forkchoiceUpdated(fcs, payloadAttrs).map(forkchoiceUpdatedResponse(_, request))
      case _ =>
        IO.pure(
          JsonRpcResponse("2.0", None, Some(JsonRpcError.InvalidParams("missing fork choice state")), reqId(request))
        )

  /** The JSON-RPC answer for what `EngineApiService.forkchoiceUpdated` returned. Both paths of
    * [[handleForkchoiceUpdated]] answer through here, so an error keeps its code whichever path raised it: the service
    * marks an attributes error with the "ATTR:" prefix (-38003); any other `Left` is an inconsistent forkchoice state
    * (-38002: an unknown safe or finalized block, or one that is not an ancestor of the head).
    */
  private def forkchoiceUpdatedResponse(
      outcome: Either[String, ForkchoiceUpdatedResponse],
      request: JsonRpcRequest
  ): JsonRpcResponse =
    outcome match
      case Right(response) =>
        JsonRpcResponse("2.0", Some(encodeForkchoiceUpdatedResponse(response)), None, reqId(request))
      case Left(errorMsg) if errorMsg.startsWith("ATTR:") =>
        JsonRpcResponse(
          "2.0",
          None,
          Some(JsonRpcError(EngineApiController.InvalidPayloadAttributesCode, errorMsg.stripPrefix("ATTR:"), None)),
          reqId(request)
        )
      case Left(errorMsg) =>
        JsonRpcResponse(
          "2.0",
          None,
          Some(JsonRpcError(EngineApiController.InvalidForkchoiceStateCode, errorMsg, None)),
          reqId(request)
        )

  private def handleExchangeCapabilities(request: JsonRpcRequest): IO[JsonRpcResponse] =
    val clCapabilities = request.params
      .flatMap(_.arr.headOption)
      .collect { case JArray(items) => items.collect { case JString(s) => s } }
      .getOrElse(Nil)

    engineApiService.exchangeCapabilities(clCapabilities).map { supported =>
      JsonRpcResponse("2.0", Some(JArray(supported.map(JString(_)).toList)), None, reqId(request))
    }

  private def handleGetPayload(request: JsonRpcRequest, version: Int): IO[JsonRpcResponse] =
    val payloadIdHex = request.params match
      case Some(JArray(List(JString(id)))) => id
      case _                               => ""
    val payloadId = hexToByteString(payloadIdHex)
    engineApiService.getPayload(payloadId).flatMap {
      case Right(stored) =>
        // Validate the RPC version matches the payload's fork timestamp. Hive's
        // "GetPayloadV2 To Request Cancun Payload" and "GetPayloadV3 To Request Shanghai
        // Payload" tests exercise this — V2 for a Cancun-ts payload and V3 for a
        // Shanghai-ts payload must both return -38005 UNSUPPORTED_FORK.
        EngineApiController.getPayloadForkError(version, stored.header.unixTimestamp, blockchainConfig) match
          case Some(msg) =>
            // Refused BEFORE the payload is resolved, so a wrong-version call does not end its build process:
            // go-ethereum rejects it on the payload ID's version, before Payload.Resolve.
            IO.pure(JsonRpcResponse("2.0", None, Some(JsonRpcError(UnsupportedFork, msg, None)), reqId(request)))
          case None =>
            // The payload brought up to date with the pool, once (EngineApiService.resolvePayload), and answered
            // WHOLE from what that settled: block, receipts, execution requests and blobs bundle, the same on every
            // call for the id. A rebuild keeps the parent and the attributes, so the fork checked above is the fork of
            // what is served.
            engineApiService.resolvePayload(payloadId).map {
              case Left(err) =>
                JsonRpcResponse("2.0", None, Some(JsonRpcError(-38001, err, None)), reqId(request))
              case Right(served) =>
                val block = served.block
                // V1 returns bare ExecutionPayload.
                // V2+ wraps it in ExecutionPayloadEnvelope per Engine API spec.
                // blockValue depends on receipts (effectiveGasPrice per tx).
                lazy val payload = blockToExecutionPayload(block)
                lazy val blockValueHex = computeBlockValue(block, served.receipts)
                lazy val blobsBundleJson: JObject =
                  val bundle = served.blobsBundle
                  JObject(
                    "commitments" -> JArray(bundle.commitments.toList.map(c => JString(byteStringToHex(c)))),
                    "proofs" -> JArray(bundle.proofs.toList.map(p => JString(byteStringToHex(p)))),
                    "blobs" -> JArray(bundle.blobs.toList.map(b => JString(byteStringToHex(b))))
                  )
                // V5 onwards: BlobsBundleV2 (EIP-7594 cell proofs) + executionRequests. V5 and V6 differ only in the
                // payload, V6's being an ExecutionPayloadV4.
                def blobsBundleV2Envelope(executionPayload: JValue): JObject =
                  val bundle = served.blobsBundle
                  JObject(
                    "executionPayload" -> executionPayload,
                    "blockValue" -> JString(blockValueHex),
                    "blobsBundle" -> JObject(
                      "commitments" -> JArray(bundle.commitments.toList.map(c => JString(byteStringToHex(c)))),
                      "proofs" -> JArray(
                        bundle.cellProofsPerBlob.flatten.toList.map(p => JString(byteStringToHex(p)))
                      ),
                      "blobs" -> JArray(bundle.blobs.toList.map(b => JString(byteStringToHex(b))))
                    ),
                    "shouldOverrideBuilder" -> JBool(false),
                    "executionRequests" -> JArray(
                      served.executionRequests.toList.map(r => JString(byteStringToHex(r)))
                    )
                  )
                val result: Either[JsonRpcError, JValue] = version match
                  case 1 => Right(payload)
                  case 2 =>
                    Right(
                      JObject(
                        "executionPayload" -> payload,
                        "blockValue" -> JString(blockValueHex)
                      )
                    )
                  case 3 =>
                    Right(
                      JObject(
                        "executionPayload" -> payload,
                        "blockValue" -> JString(blockValueHex),
                        "blobsBundle" -> blobsBundleJson,
                        "shouldOverrideBuilder" -> JBool(false)
                      )
                    )
                  case 4 => // Prague: BlobsBundleV1 + executionRequests (EIP-7685)
                    Right(
                      JObject(
                        "executionPayload" -> payload,
                        "blockValue" -> JString(blockValueHex),
                        "blobsBundle" -> blobsBundleJson,
                        "shouldOverrideBuilder" -> JBool(false),
                        "executionRequests" -> JArray(
                          served.executionRequests.toList.map(r => JString(byteStringToHex(r)))
                        )
                      )
                    )
                  case 5 => Right(blobsBundleV2Envelope(payload))
                  case _ =>
                    // engine_getPayloadV6 (amsterdam.md): an ExecutionPayloadV4, whose blockAccessList the block does
                    // not carry. A payload without its list, or with one its header does not commit to, cannot be
                    // served: answering it anyway would hand the CL a block no client validates. Unreachable until
                    // the builder produces Amsterdam payloads (#1427).
                    EngineApiController.blockAccessListServeError(block, served.blockAccessList) match
                      case Some(problem) =>
                        log.error("[ENGINE-API] getPayloadV6 {}: cannot serve the payload: {}", payloadIdHex, problem)
                        Left(JsonRpcError(-32603, s"payload cannot be served: $problem", None))
                      case None =>
                        Right(
                          blobsBundleV2Envelope(
                            EngineApiController.blockToExecutionPayload(block, served.blockAccessList)
                          )
                        )
                result match
                  case Right(envelope) => JsonRpcResponse("2.0", Some(envelope), None, reqId(request))
                  case Left(error)     => JsonRpcResponse("2.0", None, Some(error), reqId(request))
            }
      case Left(err) =>
        IO.pure(JsonRpcResponse("2.0", None, Some(JsonRpcError(-38001, err, None)), reqId(request)))
    }

  // Payload JSON encoding and blockValue derivation live in the companion object so the
  // `testing_*` namespace (jsonrpc/TestingService) emits a byte-identical ExecutionPayloadV3.
  // One codec, one shape: a change here cannot silently diverge between the two surfaces.
  private def computeBlockValue(block: Block, receipts: Seq[com.chipprbots.ethereum.domain.Receipt]): String =
    EngineApiController.computeBlockValue(block, receipts)

  private def blockToExecutionPayload(block: Block): JObject =
    EngineApiController.blockToExecutionPayload(block)

  private def handleGetClientVersion(request: JsonRpcRequest): IO[JsonRpcResponse] =
    // Per execution-apis spec, `commit` MUST be the canonical short git SHA — pure
    // hexadecimal characters. Lighthouse's HTTP client validates this and rejects
    // the response with `InvalidClientVersion("Input must contain only hexadecimal
    // characters")` on any non-hex char, which then fails engine_upcheck and stalls
    // the entire CL→EL pipeline. `Config.clientVersion.takeRight(8)` was slicing
    // into the full client identity string (e.g. "fukuii/v0.5.0-abc1234/linux-amd64/...")
    // and including slashes / hyphens. Use `BuildInfo.gitHeadCommit` directly —
    // sbt-buildinfo emits the 7-char abbreviated SHA which is always hex.
    val commitSha = BuildInfo.gitHeadCommit.filter(c => "0123456789abcdef".indexOf(c.toLower) >= 0)
    val clientVersion = JArray(
      List(
        JObject(
          "code" -> JString("FK"),
          "name" -> JString("Fukuii"),
          "version" -> JString(BuildInfo.version),
          "commit" -> JString(commitSha)
        )
      )
    )
    IO.pure(JsonRpcResponse("2.0", Some(clientVersion), None, reqId(request)))

  private def handleGetBlobs(request: JsonRpcRequest): IO[JsonRpcResponse] =
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

  private def handleGetBlobsV2(request: JsonRpcRequest): IO[JsonRpcResponse] =
    // engine_getBlobsV2: returns BlobAndProofV2 | null per versioned hash (EIP-7594 / PeerDAS).
    // Fukuii does not index mempool blobs by versioned hash, so null is returned for every entry;
    // the CL (Lighthouse/Prysm) will fall back to fetching cell proofs from CL peers.
    val hashes = request.params
      .map(_.arr)
      .getOrElse(Nil)
      .headOption
      .collect { case JArray(items) =>
        items
      }
      .getOrElse(Nil)
    val nullEntries = hashes.map(_ => JNull)
    IO.pure(JsonRpcResponse("2.0", Some(JArray(nullEntries)), None, reqId(request)))

  private def handleGetPayloadBodiesByHash(request: JsonRpcRequest): IO[JsonRpcResponse] =
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

  private def handleGetPayloadBodiesByRange(request: JsonRpcRequest): IO[JsonRpcResponse] =
    val params = request.params.map(_.arr).getOrElse(Nil)
    val start = params.headOption
      .collect {
        case JString(hex) => val c = hex.stripPrefix("0x"); if c.isEmpty then BigInt(0) else BigInt(c, 16)
        case JInt(n)      => n
      }
      .getOrElse(BigInt(0))
    val count = params
      .lift(1)
      .collect {
        case JString(hex) => val c = hex.stripPrefix("0x"); if c.isEmpty then BigInt(0) else BigInt(c, 16)
        case JInt(n)      => n
      }
      .getOrElse(BigInt(0))

    // Spec: start<1 or count<1 → -32602 invalid params.
    if start < 1 || count < 1 then
      IO.pure(
        JsonRpcResponse(
          "2.0",
          None,
          Some(com.chipprbots.ethereum.jsonrpc.JsonRpcError.InvalidParams("start and count must be >= 1")),
          reqId(request)
        )
      )
    else
      // Spec: truncate the response at the latest known canonical block — do NOT emit
      // trailing nulls for numbers past the tip. Hive's GetPayloadBodiesByRange test
      // checks the array length against min(count, latest-start+1).
      val latest = engineApiService.getLatestBlockNumber
      if start > latest then IO.pure(JsonRpcResponse("2.0", Some(JArray(Nil)), None, reqId(request)))
      else
        val effectiveCount = count.min(latest - start + 1).min(1024)
        val bodies = (0L until effectiveCount.toLong).map { offset =>
          engineApiService.getPayloadBodyByNumber(start + offset).map(encodePayloadBody).getOrElse(JNull)
        }.toList
        IO.pure(JsonRpcResponse("2.0", Some(JArray(bodies)), None, reqId(request)))

  private def encodePayloadBody(body: (Seq[ByteString], Option[Seq[org.json4s.JValue]])): JValue =
    val (txs, withdrawals) = body
    val txsJson = JArray(txs.map(tx => JString(byteStringToHex(tx))).toList)
    val wsJson = withdrawals.map(ws => JArray(ws.toList)).getOrElse(JNull)
    JObject("transactions" -> txsJson, "withdrawals" -> wsJson)

  // --- JSON encoding/decoding helpers ---

  private def hexToByteString(hex: String): ByteString =
    val clean = hex.stripPrefix("0x")
    if clean.isEmpty then ByteString.empty
    else ByteString(org.bouncycastle.util.encoders.Hex.decode(clean))

  private def byteStringToHex(bs: ByteString): String = "0x" + bs.map("%02x".format(_)).mkString

  private def decodeExecutionPayload(json: JObject): ExecutionPayload =
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
      excessBlobGas = fields.get("excessBlobGas").collect { case JString(hex) => BigInt(hex.stripPrefix("0x"), 16) },
      // ExecutionPayloadV4 (Amsterdam). Absent and null both read as None, which engine_newPayloadV5 answers -32602.
      // A present value must be well-formed DATA / QUANTITY; the BAL's RLP is only decoded later, by the service,
      // so that an undecodable list is an INVALID payload and not a params error.
      blockAccessList = EngineApiController.optionalData(fields, "blockAccessList"),
      slotNumber = EngineApiController.optionalUint64(fields, "slotNumber")
    )

  private def decodeWithdrawal(json: JObject): Withdrawal =
    val fields = json.obj.toMap
    Withdrawal(
      index = extractQuantity(fields, "index"),
      validatorIndex = extractQuantity(fields, "validatorIndex"),
      address = Address(extractString(fields, "address")),
      amount = extractQuantity(fields, "amount")
    )

  /** A ForkchoiceStateV1, or why the JSON is not one: three required fields, each DATA of 32 bytes — a string of `0x`
    * and exactly 64 hex digits, as go-ethereum's `common.Hash` decoding (hexutil.UnmarshalFixedJSON) requires.
    * go-ethereum reads a MISSING field as the zero hash; the spec's structure has all three required, and a CL always
    * sends all three, so a missing one is refused here too.
    */
  private def decodeForkChoiceState(json: JObject): Either[String, ForkChoiceState] =
    val fields = json.obj.toMap
    def isHexDigit(c: Char): Boolean = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
    def hash32(key: String): Either[String, ByteString] =
      fields.get(key) match
        case None => Left(s"$key: missing")
        case Some(JString(s))
            if s.length == 66 && (s.startsWith("0x") || s.startsWith("0X")) && s.drop(2).forall(isHexDigit) =>
          Right(ByteString(org.bouncycastle.util.encoders.Hex.decode(s.drop(2))))
        case Some(_) => Left(s"$key: not a 0x-prefixed 32-byte hash")
    for
      head <- hash32("headBlockHash")
      safe <- hash32("safeBlockHash")
      finalized <- hash32("finalizedBlockHash")
    yield ForkChoiceState(headBlockHash = head, safeBlockHash = safe, finalizedBlockHash = finalized)

  /** The attributes of an `engine_forkchoiceUpdatedV{version}` call. The two PayloadAttributesV4 fields are read for V4
    * only: V1-V3 ignore them exactly as before they existed (go-ethereum V3 does not reject them either), so neither
    * their payload IDs nor their builds can change.
    */
  private def decodePayloadAttributes(json: JObject, version: Int): PayloadAttributes =
    val fields = json.obj.toMap
    val v4 = version >= 4
    PayloadAttributes(
      timestamp = extractQuantity(fields, "timestamp").toLong,
      prevRandao = hexToByteString(extractString(fields, "prevRandao")),
      suggestedFeeRecipient = Address(extractString(fields, "suggestedFeeRecipient")),
      withdrawals = fields.get("withdrawals").collect { case JArray(items) =>
        items.collect { case obj: JObject => decodeWithdrawal(obj) }
      },
      parentBeaconBlockRoot = fields.get("parentBeaconBlockRoot").collect { case JString(hex) => hexToByteString(hex) },
      slotNumber = if v4 then EngineApiController.optionalUint64(fields, "slotNumber") else None,
      targetGasLimit = if v4 then EngineApiController.optionalUint64(fields, "targetGasLimit") else None
    )

  private def encodePayloadStatus(status: PayloadStatusV1): JValue =
    val fields: List[(String, JValue)] = List(
      "status" -> JString(status.status.value),
      "latestValidHash" -> status.latestValidHash.map(h => JString(byteStringToHex(h))).getOrElse(JNull),
      "validationError" -> status.validationError.map(JString(_)).getOrElse(JNull)
    )
    JObject(fields)

  private def encodeForkchoiceUpdatedResponse(response: ForkchoiceUpdatedResponse): JValue =
    val fields: List[(String, JValue)] = List(
      "payloadStatus" -> encodePayloadStatus(response.payloadStatus),
      "payloadId" -> response.payloadId.map(id => JString(byteStringToHex(id))).getOrElse(JNull)
    )
    JObject(fields)

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
          if clean.isEmpty then BigInt(0) else BigInt(clean, 16)
        case JInt(n) => n
      }
      .getOrElse(BigInt(0))

/** Pure Engine-API JSON encoders, shared with the execution-apis `testing_*` namespace.
  *
  * These are the single source of truth for the ExecutionPayloadV3 wire shape and for the `blockValue` derivation.
  * `testing_buildBlockV1` returns the same envelope engine_getPayloadV3+ does, so both must encode identically.
  */
object EngineApiController:

  private val InvalidParamsCode = -32602
  private val InvalidForkchoiceStateCode = -38002
  private val InvalidPayloadAttributesCode = -38003
  private val UnsupportedForkCode = -38005

  private val MaxUint64: BigInt = (BigInt(1) << 64) - 1

  private def isHexDigit(c: Char): Boolean = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')

  /** `s` is DATA: `0x` (or `0X`, as go-ethereum's hexutil accepts) and an even number of hex digits, `bytes` bytes of
    * them when given.
    */
  private def isData(s: String, bytes: Option[Int] = None): Boolean =
    s.length >= 2 && s.length % 2 == 0 && (s.startsWith("0x") || s.startsWith("0X")) &&
      bytes.forall(n => s.length == 2 + 2 * n) && s.drop(2).forall(isHexDigit)

  /** An optional DATA field: None when absent or null, the bytes when well-formed; anything else throws, which the
    * decoders turn into a malformed-payload answer. Stricter than the historical `hexToByteString` on purpose: an odd
    * digit count or a stray character must not be decoded into different bytes than the CL hashed.
    */
  private[engine] def optionalData(fields: Map[String, JValue], key: String): Option[ByteString] =
    fields.get(key) match
      case None | Some(JNull)            => None
      case Some(JString(s)) if isData(s) => Some(ByteString(org.bouncycastle.util.encoders.Hex.decode(s.drop(2))))
      case Some(other)                   => throw new IllegalArgumentException(s"$key: not DATA ($other)")

  /** An optional QUANTITY of at most 64 bits (e.g. `slotNumber`): None when absent or null; out of range or not a
    * quantity throws, as for [[optionalData]].
    */
  private[engine] def optionalUint64(fields: Map[String, JValue], key: String): Option[BigInt] =
    val value = fields.get(key) match
      case None | Some(JNull) => None
      case Some(JString(s))
          if (s.startsWith("0x") || s.startsWith("0X")) && s.length > 2 && s.drop(2).forall(isHexDigit) =>
        Some(BigInt(s.drop(2), 16))
      case Some(JInt(n)) => Some(n)
      case Some(other)   => throw new IllegalArgumentException(s"$key: not a QUANTITY ($other)")
    value.foreach(v => require(v >= 0 && v <= MaxUint64, s"$key: $v does not fit 64 bits"))
    value

  /** The JSON-RPC error `engine_newPayloadV5` must answer, or None when the call may go on to the payload itself.
    *
    * go-ethereum's `NewPayloadV5` checks, in its order (eth/catalyst/api.go): every field and parameter an Amsterdam
    * call must carry — withdrawals, excessBlobGas, blobGasUsed, expectedBlobVersionedHashes, parentBeaconBlockRoot,
    * executionRequests, slotNumber, blockAccessList — each -32602 when missing or null; THEN the fork window, -38005
    * when the timestamp is not Amsterdam. The request-list rules (-32602) and everything about the payload's content
    * come after this.
    *
    * execution-apis amsterdam.md states "-38005 if the timestamp is not Amsterdam" and "-32602 if blockAccessList is
    * missing" without ordering them. go-ethereum's order is the one newPayloadV3 already follows here, as hive expects:
    * a payload missing a field of the method's own fork is a params error whatever its timestamp. EEST
    * `invalid_post_fork_block_without_bal_hash_field` / `invalid_post_fork_block_without_slot_number` send V5 at the
    * fork without one field each and expect -32602.
    *
    * A present but undecodable block access list (the empty byte string included) is not a params error: it is an
    * INVALID payload, decided by `EngineApiService.newPayload`.
    */
  def newPayloadV5ParamsError(
      payload: ExecutionPayload,
      params: List[JValue],
      isAmsterdam: Boolean
  ): Option[(Int, String)] =
    // Why the positional parameter at `index` is unusable, or None: absent/null is "nil", a wrong shape says so.
    def param(index: Int, name: String, fork: String)(wellFormed: PartialFunction[JValue, Unit]): Option[String] =
      params.lift(index) match
        case None | Some(JNull)                           => Some(s"nil $name post-$fork")
        case Some(value) if wellFormed.isDefinedAt(value) => None
        case Some(_)                                      => Some(s"$name is malformed")
    val problem: Option[String] =
      if payload.withdrawals.isEmpty then Some("nil withdrawals post-shanghai")
      else if payload.excessBlobGas.isEmpty then Some("nil excessBlobGas post-cancun")
      else if payload.blobGasUsed.isEmpty then Some("nil blobGasUsed post-cancun")
      else
        param(1, "expectedBlobVersionedHashes", "cancun") { case _: JArray => () }
          .orElse(param(2, "parentBeaconBlockRoot", "cancun") { case JString(s) if isData(s, Some(32)) => () })
          .orElse(param(3, "executionRequests", "prague") { case _: JArray => () })
          .orElse(Option.when(payload.slotNumber.isEmpty)("nil slotNumber post-amsterdam"))
          .orElse(Option.when(payload.blockAccessList.isEmpty)("nil blockAccessList post-amsterdam"))
    problem
      .map(p => InvalidParamsCode -> s"newPayloadV5: $p")
      .orElse(
        Option.when(!isAmsterdam)(UnsupportedForkCode -> "newPayloadV5 must only be called for Amsterdam payloads")
      )

  /** The JSON-RPC error (code, message) an `engine_forkchoiceUpdatedV{version}` call must answer for these payload
    * attributes, or None when they are acceptable for the method. Pure: reads nothing but its arguments.
    *
    * Checks run in go-ethereum's order (eth/catalyst/api.go `ForkchoiceUpdatedV1/V2/V3`): the attribute SHAPE first,
    * the fork window last. The order is observable, because an attribute set that is both mis-shaped and for the wrong
    * fork gets the shape code, and hive asserts it. `ForkchoiceUpdatedV3 To Request Shanghai Payload, Null Beacon Root`
    * sends V3 at a Shanghai timestamp without a beacon root and expects -38003; the `Non-Null Beacon Root` variant
    * (same timestamp, beacon root present) expects -38005. The old matrix tested the fork first and let the
    * null-beacon-root case through as VALID, because its only beacon-root check was confined to Cancun timestamps.
    *
    *   - V1: withdrawals or beacon root present -> -32602 (go-ethereum `paramsErr`), then a Shanghai-or-later timestamp
    *     -> -38005. Deliberate deviation: go-ethereum also accepts Shanghai timestamps on V1 (it reports -32602 only
    *     from Cancun on). We keep the execution-apis "update the methods of previous forks" rule, because a V1 build at
    *     a Shanghai timestamp would carry no withdrawals list, and neither `getPayloadV1` nor `newPayloadV1` here
    *     serves a Shanghai payload, so the payload could never round-trip.
    *   - V2: beacon root present -> -38003; Paris with withdrawals -> -38003; Shanghai without withdrawals -> -38003;
    *     any fork other than Paris/Shanghai -> -38005.
    *   - V3: withdrawals missing -> -38003; beacon root missing -> -38003 (at ANY timestamp); a fork outside
    *     Cancun..BPO5 (i.e. pre-Cancun, or Amsterdam onwards, which needs V4) -> -38005.
    *   - V4 (amsterdam.md engine_forkchoiceUpdatedV4, go-ethereum `ForkchoiceUpdatedV4`): the PayloadAttributesV4
    *     structure — withdrawals, beacon root and slotNumber each -38003 when missing — then the Amsterdam window,
    *     -38005 before it. `targetGasLimit` is NOT required. The spec lists it in PayloadAttributesV4 and says the
    *     builder "MUST use" it; go-ethereum reads it as optional and never rejects its absence. We follow go-ethereum:
    *     hive's engine simulator is checked against it, and refusing would cost a CL that omits the field its slot,
    *     where accepting costs nothing (without a target the build keeps the parent's gas limit, the engine path's
    *     historical policy). Lighthouse always sends it.
    *
    * -38003 answers still apply the forkchoice state first (see `handleForkchoiceUpdated`); -32602 and -38005 do not.
    */
  def payloadAttributesVersionError(
      version: Int,
      attrs: PayloadAttributes,
      blockchainConfig: com.chipprbots.ethereum.utils.BlockchainConfig
  ): Option[(Int, String)] =
    val ts = Timestamp(attrs.timestamp)
    val hasWithdrawals = attrs.withdrawals.isDefined
    val hasBeaconRoot = attrs.parentBeaconBlockRoot.isDefined
    val isShanghai = blockchainConfig.isShanghaiTimestamp(ts)
    val isCancun = blockchainConfig.isCancunTimestamp(ts)
    // The Engine API only exists post-merge, so "not yet Shanghai" is Paris.
    val latestIsParis = !isShanghai
    val latestIsShanghai = isShanghai && !isCancun
    // go-ethereum checkFork(ts, Cancun, Prague, Osaka, BPO1..BPO5): Cancun is active and Amsterdam is not.
    val inV3Window = isCancun && !blockchainConfig.isAmsterdamTimestamp(ts)
    version match
      case 1 =>
        if hasWithdrawals || hasBeaconRoot then
          Some(InvalidParamsCode -> "forkchoiceUpdatedV1: withdrawals and beacon root not supported in V1")
        else if isShanghai then Some(UnsupportedForkCode -> "forkchoiceUpdatedV1 cannot be used post-Shanghai, use V2")
        else None
      case 2 =>
        if hasBeaconRoot then Some(InvalidPayloadAttributesCode -> "forkchoiceUpdatedV2: unexpected beacon root")
        else if latestIsParis && hasWithdrawals then
          Some(InvalidPayloadAttributesCode -> "forkchoiceUpdatedV2: withdrawals before Shanghai")
        else if latestIsShanghai && !hasWithdrawals then
          Some(InvalidPayloadAttributesCode -> "forkchoiceUpdatedV2: missing withdrawals")
        else if !(latestIsParis || latestIsShanghai) then
          Some(UnsupportedForkCode -> "forkchoiceUpdatedV2 must only be called for Paris or Shanghai payloads")
        else None
      case 3 =>
        if !hasWithdrawals then Some(InvalidPayloadAttributesCode -> "forkchoiceUpdatedV3: missing withdrawals")
        else if !hasBeaconRoot then Some(InvalidPayloadAttributesCode -> "forkchoiceUpdatedV3: missing beacon root")
        else if !inV3Window then
          Some(UnsupportedForkCode -> "forkchoiceUpdatedV3 must only be called for Cancun/Prague/Osaka payloads")
        else None
      case _ =>
        if !hasWithdrawals then Some(InvalidPayloadAttributesCode -> "forkchoiceUpdatedV4: missing withdrawals")
        else if !hasBeaconRoot then Some(InvalidPayloadAttributesCode -> "forkchoiceUpdatedV4: missing beacon root")
        else if attrs.slotNumber.isEmpty then
          Some(InvalidPayloadAttributesCode -> "forkchoiceUpdatedV4: missing slot number")
        else if !blockchainConfig.isAmsterdamTimestamp(ts) then
          Some(UnsupportedForkCode -> "forkchoiceUpdatedV4 must only be called for Amsterdam payloads")
        else None

  /** Why `custodyColumns`, engine_forkchoiceUpdatedV4's third parameter, is unacceptable, or None when it is fine:
    * absent or null (the CL provides no custody), or DATA of exactly 16 bytes, the CELLS_PER_EXT_BLOB = 128-bit
    * bitarray (amsterdam.md engine_forkchoiceUpdatedV4 point 3.1: "MUST be a 16-byte DATA value", else -32602;
    * go-ethereum `types.CustodyBitmap`). Lighthouse omits it or sends null when it has no custody set.
    *
    * A valid value is otherwise ignored. Adopting the set is required "when acting as a sampler for type 3
    * transactions" (point 3.2), and fukuii's blob pool does not sample (no EIP-8070 cell custody), so there is nothing
    * to adopt; and the custody update must not affect the fork choice (point 3.4), which ignoring guarantees.
    */
  def custodyColumnsError(param: Option[JValue]): Option[String] =
    param match
      case None | Some(JNull)                      => None
      case Some(JString(s)) if isData(s, Some(16)) => None
      case Some(other)                             => Some(s"custodyColumns: not 16 bytes of DATA ($other)")

  /** The -38005 message when engine_getPayloadV{version} must not serve a payload built for `timestamp`, or None when
    * it may. Each version serves one fork window — go-ethereum's `checkFork` per version (eth/catalyst/api.go), and
    * execution-apis "MUST return -38005: Unsupported fork error if the timestamp of the built payload does not fall
    * within the time frame of the <fork> fork" (cancun.md getPayloadV3, prague.md getPayloadV4, osaka.md getPayloadV5):
    *   - V1: Paris. go-ethereum checks only the payload ID's version for V1; fukuii keeps V1 to Paris, as for the other
    *     V1 methods (see [[payloadAttributesVersionError]]).
    *   - V2: Paris and Shanghai.
    *   - V3: Cancun.
    *   - V4: Prague.
    *   - V5: Osaka and the blob-parameter-only forks after it (go-ethereum: Osaka, BPO1, BPO2).
    *   - V6: Amsterdam (go-ethereum: Amsterdam and the forks after it, BPO3..BPO5 and Bogota, none of which fukuii
    *     schedules; amsterdam.md getPayloadV6).
    *
    * V3 used to serve every payload from Cancun on, V4 every payload before Osaka, and V5 Amsterdam.
    *
    * Pure: reads nothing but its arguments. The controller calls it before resolving the payload, so a refused call
    * neither freezes the payload nor takes anything from it.
    */
  def getPayloadForkError(
      version: Int,
      timestamp: Timestamp,
      blockchainConfig: com.chipprbots.ethereum.utils.BlockchainConfig
  ): Option[String] =
    val shanghai = blockchainConfig.isShanghaiTimestamp(timestamp)
    val cancun = blockchainConfig.isCancunTimestamp(timestamp)
    val prague = blockchainConfig.isPragueTimestamp(timestamp)
    val osaka = blockchainConfig.isOsakaTimestamp(timestamp)
    val amsterdam = blockchainConfig.isAmsterdamTimestamp(timestamp)
    val fork =
      if amsterdam then "an Amsterdam"
      else if blockchainConfig.isBpo2Timestamp(timestamp) then "a BPO2"
      else if blockchainConfig.isBpo1Timestamp(timestamp) then "a BPO1"
      else if osaka then "an Osaka"
      else if prague then "a Prague"
      else if cancun then "a Cancun"
      else if shanghai then "a Shanghai"
      else "a Paris"
    def refuse(serves: String): Some[String] = Some(
      s"getPayloadV$version serves $serves payloads only, not $fork payload"
    )
    version match
      case 1 => if shanghai then refuse("Paris") else None
      case 2 => if cancun then refuse("Paris and Shanghai") else None
      case 3 => if !cancun || prague then refuse("Cancun") else None
      case 4 => if !prague || osaka then refuse("Prague") else None
      case 5 => if !osaka || amsterdam then refuse("Osaka (and BPO)") else None
      case 6 => if !amsterdam then refuse("Amsterdam") else None
      case _ => refuse("no")

  /** Why an Amsterdam payload cannot be served as an ExecutionPayloadV4 (engine_getPayloadV6), or None when it can: the
    * header must be Amsterdam's, and the block access list must be held and hash to the header's `blockAccessListHash`.
    * Pure.
    */
  def blockAccessListServeError(block: Block, blockAccessList: Option[ByteString]): Option[String] =
    (block.header.blockAccessListHash, blockAccessList) match
      case (None, _)       => Some("its header is not an Amsterdam header (no blockAccessListHash)")
      case (Some(_), None) => Some("no block access list is held for it")
      case (Some(committed), Some(list)) if ByteString(kec256(list.toArray)) != committed =>
        Some("its block access list does not hash to the header's blockAccessListHash")
      case _ => None

  def byteStringToHex(bs: ByteString): String = "0x" + bs.map("%02x".format(_)).mkString

  /** blockValue = Σ gasUsedByTx_i × (effectiveGasPrice_i − baseFeePerGas). Miner's priority-fee revenue for the block.
    * Per EIP-3675 V2 envelope, this is what the CL reads to pick the highest-value payload across builders.
    *
    * For each tx:
    *   - gasUsedByTx = receipt.cumulativeGas − previousReceipt.cumulativeGas (since receipts record CUMULATIVE gas, not
    *     per-tx).
    *   - effectiveGasPrice = for legacy / access-list txs: tx.gasPrice. For EIP-1559 / blob: min(maxFeePerGas, baseFee
    *     + maxPriorityFeePerGas).
    */
  def computeBlockValue(
      block: Block,
      receipts: Seq[com.chipprbots.ethereum.domain.Receipt]
  ): String =
    import com.chipprbots.ethereum.domain.{
      TransactionWithAccessList,
      TransactionWithDynamicFee,
      BlobTransaction,
      SetCodeTransaction
    }
    val baseFee = block.header.extraFields match
      case BlockHeader.HeaderExtraFields.HefPostOlympia(bf)               => bf
      case BlockHeader.HeaderExtraFields.HefPostShanghai(bf, _)           => bf
      case BlockHeader.HeaderExtraFields.HefPostCancun(bf, _, _, _, _)    => bf
      case BlockHeader.HeaderExtraFields.HefPostPrague(bf, _, _, _, _, _) => bf
      // Amsterdam: without this the catch-all yields baseFee 0, and every priority fee below is
      // computed against the wrong base — engine_getPayload would report an inflated block value.
      case BlockHeader.HeaderExtraFields.HefPostAmsterdam(bf, _, _, _, _, _, _, _) => bf
      case _                                                                       => BigInt(0)
    if receipts.isEmpty then "0x0"
    else
      val txs = block.body.transactionList
      // derive per-tx gas used from cumulative deltas
      val gasUsedPerTx: Seq[BigInt] = receipts
        .map(_.cumulativeGasUsed)
        .scanLeft(BigInt(0)) { (_, cum) =>
          cum
        }
        .sliding(2, 1)
        .collect { case Seq(prev, cur) => cur - prev }
        .toSeq
      val totalPriorityFee: BigInt = txs
        .zip(gasUsedPerTx)
        .map { case (stx, gasUsed) =>
          val effectiveGasPrice: BigInt = stx.tx match
            case t: TransactionWithDynamicFee => (baseFee + t.maxPriorityFeePerGas).min(t.maxFeePerGas)
            case t: BlobTransaction           => (baseFee + t.maxPriorityFeePerGas).min(t.maxFeePerGas)
            case t: SetCodeTransaction        => (baseFee + t.maxPriorityFeePerGas).min(t.maxFeePerGas)
            case t: TransactionWithAccessList => t.gasPrice.value
            case _                            => stx.tx.gasPrice.value
          val priorityPerGas = (effectiveGasPrice - baseFee).max(0)
          gasUsed * priorityPerGas
        }
        .sum
      s"0x${totalPriorityFee.toString(16)}"

  /** The ExecutionPayloadV1..V4 JSON of `block`. The V4 fields (Amsterdam): `slotNumber` whenever the header carries
    * one, and `blockAccessList` when given — the block does not carry its list, so only a caller that holds it
    * (engine_getPayloadV6) can complete an ExecutionPayloadV4. Pre-Amsterdam blocks encode exactly as before.
    */
  def blockToExecutionPayload(block: Block, blockAccessList: Option[ByteString] = None): JObject =
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
    val (baseFee, blobGasUsed, excessBlobGas) = header.extraFields match
      case BlockHeader.HeaderExtraFields.HefPostOlympia(bf)                   => (Some(bf), None, None)
      case BlockHeader.HeaderExtraFields.HefPostShanghai(bf, _)               => (Some(bf), None, None)
      case BlockHeader.HeaderExtraFields.HefPostCancun(bf, _, bgu, ebg, _)    => (Some(bf), Some(bgu), Some(ebg))
      case BlockHeader.HeaderExtraFields.HefPostPrague(bf, _, bgu, ebg, _, _) => (Some(bf), Some(bgu), Some(ebg))
      // Amsterdam: the catch-all returns (None, None, None), which makes engine_getPayload omit
      // baseFeePerGas, blobGasUsed AND excessBlobGas entirely on an Amsterdam payload.
      case BlockHeader.HeaderExtraFields.HefPostAmsterdam(bf, _, bgu, ebg, _, _, _, _) =>
        (Some(bf), Some(bgu), Some(ebg))
      case _ => (None, None, None)
    val baseFields = List(
      "parentHash" -> JString(hex(header.parentHash.value)),
      "feeRecipient" -> JString(hex(header.beneficiary)),
      "stateRoot" -> JString(hex(header.stateRoot.value)),
      "receiptsRoot" -> JString(hex(header.receiptsRoot.value)),
      "logsBloom" -> JString(hex(header.logsBloom.value)),
      "prevRandao" -> JString(hex(header.mixHash.value)),
      "blockNumber" -> JString(hexQ(header.number.value)),
      "gasLimit" -> JString(hexQ(header.gasLimit.value)),
      "gasUsed" -> JString(hexQ(header.gasUsed.value)),
      "timestamp" -> JString(s"0x${header.unixTimestamp.toHexString}"),
      "extraData" -> JString(hex(header.extraData)),
      "baseFeePerGas" -> JString(hexQ(baseFee.getOrElse(BigInt(0)))),
      "blockHash" -> JString(hex(header.hash.value)),
      "transactions" -> JArray(txs.toList)
    )
    val withdrawalsField = withdrawals.map(w => "withdrawals" -> w).toList
    val blobFields = List(
      blobGasUsed.map(v => "blobGasUsed" -> JString(hexQ(v))),
      excessBlobGas.map(v => "excessBlobGas" -> JString(hexQ(v)))
    ).flatten
    val amsterdamFields = List(
      blockAccessList.map(list => "blockAccessList" -> JString(hex(list))),
      header.slotNumber.map(slot => "slotNumber" -> JString(hexQ(slot)))
    ).flatten
    JObject(baseFields ++ withdrawalsField ++ blobFields ++ amsterdamFields)
