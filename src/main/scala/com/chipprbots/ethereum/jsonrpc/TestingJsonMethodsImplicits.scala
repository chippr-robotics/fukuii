package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import org.json4s.JsonAST.*

import com.chipprbots.ethereum.consensus.engine.BlobsBundleData
import com.chipprbots.ethereum.consensus.engine.EngineApiController
import com.chipprbots.ethereum.consensus.engine.PayloadAttributes
import com.chipprbots.ethereum.domain.Withdrawal
import com.chipprbots.ethereum.jsonrpc.JsonRpcError.InvalidParams
import com.chipprbots.ethereum.jsonrpc.TestingService.*
import com.chipprbots.ethereum.jsonrpc.serialization.JsonEncoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder

/** Codecs for the execution-apis `testing_*` namespace.
  *
  * The response envelope is deliberately assembled from [[EngineApiController.blockToExecutionPayload]] and
  * [[EngineApiController.computeBlockValue]] rather than from a second encoder: `testing_buildBlockV1` returns the same
  * `ExecutionPayloadV3` + `blockValue` + `blobsBundle` + `shouldOverrideBuilder` envelope that
  * `engine_getPayloadV3`/`V4`/`V5` return, and the two must not be able to drift.
  *
  * Pure serialization: the Prague gate on `executionRequests` is decided by the service (which holds the chain config)
  * and reaches here as `None` meaning "omit the field".
  */
object TestingJsonMethodsImplicits extends JsonMethodsImplicits:

  /** `testing_buildBlockV1(parentBlockHash, payloadAttributes, transactions, extraData?)` */
  given testing_buildBlockV1: (JsonMethodDecoder[BuildBlockRequest] & JsonEncoder[BuildBlockResponse]) =
    new JsonMethodDecoder[BuildBlockRequest] with JsonEncoder[BuildBlockResponse]:

      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, BuildBlockRequest] =
        params match
          case Some(JArray(parentHashJson :: attrsJson :: txsJson :: rest)) =>
            for
              parentHash <- parentHashJson match
                case JString(s) => extractHash(s)
                case _          => Left(InvalidParams("parentBlockHash must be a 32-byte hex string"))
              attrs <- decodePayloadAttributes(attrsJson)
              txs <- decodeTransactionsParam(txsJson)
              extraData <- decodeExtraDataParam(rest.headOption)
            yield BuildBlockRequest(parentHash, attrs, txs, extraData)
          case _ =>
            Left(InvalidParams("testing_buildBlockV1 expects [parentBlockHash, payloadAttributes, transactions]"))

      override def encodeJson(t: BuildBlockResponse): JValue =
        val payload = EngineApiController.blockToExecutionPayload(t.block)
        val blockValue = EngineApiController.computeBlockValue(t.block, t.receipts)
        val base = List(
          "executionPayload" -> payload,
          "blockValue" -> JString(blockValue),
          "blobsBundle" -> encodeBlobsBundle(t.blobsBundle),
          "shouldOverrideBuilder" -> JBool(false)
        )
        // EIP-7685: `executionRequests` only exists from Prague, exactly as in engine_getPayloadV4/V5
        // (the pre-Prague V2/V3 envelopes omit it). None here means the service decided this block
        // is pre-Prague; emitting the field anyway would put a key in the response no other client
        // produces, and hive's rpc-compat diff counts an added key as a mismatch.
        val requests = t.executionRequests.toList.map { reqs =>
          "executionRequests" -> JArray(reqs.toList.map(r => JString(EngineApiController.byteStringToHex(r))))
        }
        JObject(base ++ requests)

  /** `testing_commitBlockV1(payloadAttributes, transactions, extraData?)` */
  given testing_commitBlockV1: (JsonMethodDecoder[CommitBlockRequest] & JsonEncoder[CommitBlockResponse]) =
    new JsonMethodDecoder[CommitBlockRequest] with JsonEncoder[CommitBlockResponse]:

      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, CommitBlockRequest] =
        params match
          case Some(JArray(attrsJson :: txsJson :: rest)) =>
            for
              attrs <- decodePayloadAttributes(attrsJson)
              txs <- decodeTransactionsParam(txsJson)
              extraData <- decodeExtraDataParam(rest.headOption)
            yield CommitBlockRequest(attrs, txs, extraData)
          case _ =>
            Left(InvalidParams("testing_commitBlockV1 expects [payloadAttributes, transactions]"))

      override def encodeJson(t: CommitBlockResponse): JValue =
        JString(EngineApiController.byteStringToHex(t.blockHash))

  /** BlobsBundleV2 (EIP-7594): `proofs` are the flattened per-cell proofs, matching engine_getPayloadV5. */
  private def encodeBlobsBundle(bundle: BlobsBundleData): JObject =
    JObject(
      "commitments" -> JArray(bundle.commitments.toList.map(c => JString(EngineApiController.byteStringToHex(c)))),
      "proofs" -> JArray(
        bundle.cellProofsPerBlob.flatten.toList.map(p => JString(EngineApiController.byteStringToHex(p)))
      ),
      "blobs" -> JArray(bundle.blobs.toList.map(b => JString(EngineApiController.byteStringToHex(b))))
    )

  /** `[]` -> Some(Nil) (empty block); `null` -> None (mempool); `[..]` -> Some(txs). */
  private def decodeTransactionsParam(json: JValue): Either[JsonRpcError, Option[Seq[ByteString]]] =
    json match
      case JNull | JNothing => Right(None)
      case JArray(items) =>
        items
          .foldLeft[Either[JsonRpcError, List[ByteString]]](Right(Nil)) {
            case (Left(err), _) => Left(err)
            case (Right(acc), JString(s)) =>
              extractBytes(s).map(acc :+ _)
            case (Right(_), _) =>
              Left(InvalidParams("transactions entries must be hex strings"))
          }
          .map(Some(_))
      case _ => Left(InvalidParams("transactions must be an array of hex strings or null"))

  /** Absent or `null` -> None. An explicit `"0x"` decodes to `Some(empty)`, which is NOT the same thing: the spec says
    * a provided value MUST be used verbatim, and an empty extraData is a legitimate provided value.
    */
  private def decodeExtraDataParam(json: Option[JValue]): Either[JsonRpcError, Option[ByteString]] =
    json match
      case None | Some(JNull) | Some(JNothing) => Right(None)
      case Some(JString(s))                    => extractBytes(s).map(Some(_))
      case Some(_)                             => Left(InvalidParams("extraData must be a hex string or null"))

  private def decodePayloadAttributes(json: JValue): Either[JsonRpcError, PayloadAttributes] =
    json match
      case JObject(fieldList) =>
        val fields = fieldList.toMap
        for
          timestamp <- fields.get("timestamp") match
            case Some(v) => extractQuantity(v)
            case None    => Left(InvalidParams("payloadAttributes.timestamp is required"))
          prevRandao <- fields.get("prevRandao") match
            case Some(JString(s)) => extractHash(s)
            case _                => Left(InvalidParams("payloadAttributes.prevRandao is required"))
          feeRecipient <- fields.get("suggestedFeeRecipient") match
            case Some(JString(s)) => extractAddress(s)
            case _                => Left(InvalidParams("payloadAttributes.suggestedFeeRecipient is required"))
          withdrawals <- fields.get("withdrawals") match
            case None | Some(JNull) | Some(JNothing) => Right(None)
            case Some(JArray(items)) =>
              items
                .foldLeft[Either[JsonRpcError, List[Withdrawal]]](Right(Nil)) {
                  case (Left(err), _)           => Left(err)
                  case (Right(acc), o: JObject) => decodeWithdrawal(o).map(acc :+ _)
                  case (Right(_), _)            => Left(InvalidParams("withdrawals entries must be objects"))
                }
                .map(Some(_))
            case Some(_) => Left(InvalidParams("withdrawals must be an array"))
          beaconRoot <- fields.get("parentBeaconBlockRoot") match
            case None | Some(JNull) | Some(JNothing) => Right(None)
            case Some(JString(s))                    => extractHash(s).map(Some(_))
            case Some(_) => Left(InvalidParams("parentBeaconBlockRoot must be a 32-byte hex string"))
        yield PayloadAttributes(
          // Timestamp is a uint64 QUANTITY. `.toLong` is the representation PayloadAttributes
          // already uses; comparisons against it elsewhere go through Timestamp() so that values
          // at or above 2^63 stay unsigned.
          timestamp = timestamp.toLong,
          prevRandao = prevRandao,
          suggestedFeeRecipient = feeRecipient,
          withdrawals = withdrawals,
          parentBeaconBlockRoot = beaconRoot
        )
      case _ => Left(InvalidParams("payloadAttributes must be an object"))

  private def decodeWithdrawal(json: JObject): Either[JsonRpcError, Withdrawal] =
    val fields = json.obj.toMap
    for
      index <- fields.get("index").toRight(InvalidParams("withdrawal.index is required")).flatMap(extractQuantity)
      validatorIndex <- fields
        .get("validatorIndex")
        .toRight(InvalidParams("withdrawal.validatorIndex is required"))
        .flatMap(extractQuantity)
      address <- fields.get("address") match
        case Some(JString(s)) => extractAddress(s)
        case _                => Left(InvalidParams("withdrawal.address is required"))
      amount <- fields.get("amount").toRight(InvalidParams("withdrawal.amount is required")).flatMap(extractQuantity)
    yield Withdrawal(index, validatorIndex, address, amount)
