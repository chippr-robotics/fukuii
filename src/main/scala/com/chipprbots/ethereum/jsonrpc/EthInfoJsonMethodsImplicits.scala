package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.json4s.*
import org.json4s.JsonAST.*
import org.json4s.JsonDSL.*

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.jsonrpc.EthInfoService.*
import com.chipprbots.ethereum.jsonrpc.JsonRpcError.InvalidParams
import com.chipprbots.ethereum.jsonrpc.PersonalService.SendTransactionRequest
import com.chipprbots.ethereum.jsonrpc.PersonalService.SendTransactionResponse
import com.chipprbots.ethereum.jsonrpc.PersonalService.SignRequest
import com.chipprbots.ethereum.jsonrpc.serialization.JsonEncoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodCodec
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder.NoParamsMethodDecoder

object EthJsonMethodsImplicits extends JsonMethodsImplicits:
  given eth_chainId: (NoParamsMethodDecoder[ChainIdRequest] & JsonEncoder[ChainIdResponse]) =
    new NoParamsMethodDecoder(ChainIdRequest()) with JsonEncoder[ChainIdResponse]:
      def encodeJson(t: ChainIdResponse): JValue = encodeAsHex(t.value)

  given eth_protocolVersion: (NoParamsMethodDecoder[ProtocolVersionRequest] & JsonEncoder[ProtocolVersionResponse]) =
    new NoParamsMethodDecoder(ProtocolVersionRequest()) with JsonEncoder[ProtocolVersionResponse]:
      def encodeJson(t: ProtocolVersionResponse): JValue = t.value

  given eth_syncing: (NoParamsMethodDecoder[SyncingRequest] & JsonEncoder[SyncingResponse]) =
    new NoParamsMethodDecoder(SyncingRequest()) with JsonEncoder[SyncingResponse]:
      def encodeJson(t: SyncingResponse): JValue = t.syncStatus match
        case Some(s) =>
          val base: JObject =
            ("startingBlock" -> encodeAsHex(s.startingBlock)) ~
              ("currentBlock" -> encodeAsHex(s.currentBlock)) ~
              ("highestBlock" -> encodeAsHex(s.highestBlock))
          // knownStates/pulledStates are legacy ETH/63 fast-sync fields. After SNAP sync
          // completes they are 0 (no active trie progress) — reporting 0 falsely implies
          // the state was never downloaded. Omit them when not meaningful, matching
          // go-ethereum's behaviour post-SNAP (which drops these fields entirely).
          if s.knownStates == BigInt(0) && s.pulledStates == BigInt(0) then base
          else
            base ~ ("knownStates" -> encodeAsHex(s.knownStates)) ~
              ("pulledStates" -> encodeAsHex(s.pulledStates))
        case None => false

  given eth_config: (NoParamsMethodDecoder[ConfigRequest] & JsonEncoder[ConfigResponse]) =
    new NoParamsMethodDecoder(ConfigRequest()) with JsonEncoder[ConfigResponse]:
      private def encodeAddress(addr: Address): JString =
        JString(s"0x${Hex.toHexString(addr.bytes.toArray[Byte])}")

      /** EIP-7910 renders the fork id as a 4-byte hex string (the EIP-2124 CRC32 checksum), zero-padded — `0xe272ecbe`,
        * never `0xe272ecbe` truncated or `0x...` sign-extended.
        */
      private def encodeForkId(forkId: BigInt): JString =
        JString(f"0x${forkId & BigInt("ffffffff", 16)}%08x")

      /** Blob counts and the update fraction are plain JSON numbers in EIP-7910, not hex quantities. */
      private def encodeBlobSchedule(bs: BlobScheduleConfig): JObject =
        ("target" -> JInt(bs.target)) ~
          ("max" -> JInt(bs.max)) ~
          ("baseFeeUpdateFraction" -> JInt(bs.baseFeeUpdateFraction))

      private def encodeForkConfig(fc: ForkConfig): JObject =
        // Field order follows EIP-7910's reference output. Optional fields are OMITTED rather
        // than emitted as null: `activationBlock` is the ETC (block-gated) spelling and
        // `activationTime` the ETH one, and a chain has exactly one of them.
        val activation: JObject = fc.activationTime match
          case Some(t) => "activationTime" -> JInt(BigInt(t))
          case None    => "activationBlock" -> encodeAsHex(fc.activationBlock.getOrElse(BigInt(0)))
        val withBlobs = fc.blobSchedule.fold(activation)(bs => activation ~ ("blobSchedule" -> encodeBlobSchedule(bs)))
        val withChainId = withBlobs ~ ("chainId" -> encodeAsHex(fc.chainId))
        val withForkId = fc.forkId.fold(withChainId)(id => withChainId ~ ("forkId" -> encodeForkId(id)))
        withForkId ~
          ("precompiles" -> JObject(fc.precompiles.toList.sortBy(_._1).map { case (name, addr) =>
            JField(name, encodeAddress(addr))
          })) ~
          ("systemContracts" -> JObject(fc.systemContracts.toList.sortBy(_._1).map { case (name, addr) =>
            JField(name, encodeAddress(addr))
          }))

      def encodeJson(t: ConfigResponse): JValue =
        ("current" -> t.current.map(encodeForkConfig).getOrElse(JNull: JValue)) ~
          ("next" -> t.next.map(encodeForkConfig).getOrElse(JNull: JValue)) ~
          ("last" -> t.last.map(encodeForkConfig).getOrElse(JNull: JValue))

  given eth_sendTransaction: JsonMethodCodec[SendTransactionRequest, SendTransactionResponse] =
    new JsonMethodCodec[SendTransactionRequest, SendTransactionResponse]:
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, SendTransactionRequest] =
        params match
          case Some(JArray(JObject(tx) :: _)) =>
            extractTx(tx.toMap).map(SendTransactionRequest.apply)
          case _ =>
            Left(InvalidParams())

      def encodeJson(t: SendTransactionResponse): JValue =
        encodeAsHex(t.txHash)

  given eth_call: (JsonMethodDecoder[CallRequest] & JsonEncoder[CallResponse]) =
    new JsonMethodDecoder[CallRequest] with JsonEncoder[CallResponse]:
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, CallRequest] =
        params match
          case Some(JArray((txObj: JObject) :: (blockValue: JValue) :: Nil)) =>
            for
              blockParam <- extractBlockParam(blockValue)
              tx <- extractCall(txObj)
            yield CallRequest(tx, blockParam)
          case _ => Left(InvalidParams())

      def encodeJson(t: CallResponse): JValue = encodeAsHex(t.returnData)

  given eth_estimateGas: eth_estimateGas = new eth_estimateGas
  class eth_estimateGas extends JsonMethodDecoder[CallRequest] with JsonEncoder[EstimateGasResponse]:
    override def encodeJson(t: EstimateGasResponse): JValue = encodeAsHex(t.gas)

    override def decodeJson(params: Option[JArray]): Either[JsonRpcError, CallRequest] =
      withoutBlockParam.applyOrElse(params, eth_call.decodeJson)

    def withoutBlockParam: PartialFunction[Option[JArray], Either[JsonRpcError, CallRequest]] = {
      case Some(JArray((txObj: JObject) :: Nil)) =>
        extractCall(txObj).map(CallRequest(_, BlockParam.Latest))
    }

  given eth_createAccessList: (JsonMethodDecoder[CreateAccessListRequest] & JsonEncoder[CreateAccessListResponse]) =
    new JsonMethodDecoder[CreateAccessListRequest] with JsonEncoder[CreateAccessListResponse]:
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, CreateAccessListRequest] =
        params match
          case Some(JArray((txObj: JObject) :: (blockValue: JValue) :: Nil)) =>
            for
              blockParam <- extractBlockParam(blockValue)
              tx <- extractCall(txObj)
            yield CreateAccessListRequest(tx, blockParam)
          case Some(JArray((txObj: JObject) :: Nil)) =>
            extractCall(txObj).map(CreateAccessListRequest(_, BlockParam.Latest))
          case _ => Left(InvalidParams())

      def encodeJson(t: CreateAccessListResponse): JValue =
        val fields = List(
          "accessList" -> JArray(t.accessList.toList.map { item =>
            val addr = item("address") match
              case a: com.chipprbots.ethereum.domain.Address => encodeAsHex(a.bytes)
              case other                                     => JString(other.toString)
            val keys = item("storageKeys") match
              case ks: List[?] =>
                JArray(ks.map {
                  case bi: BigInt => JString("0x" + bi.toString(16).reverse.padTo(64, '0').reverse)
                  case other      => JString(other.toString)
                })
              case _ => JArray(Nil)
            JObject("address" -> addr, "storageKeys" -> keys)
          }),
          "gasUsed" -> encodeAsHex(t.gasUsed)
        )
        val errorField = t.error.map(e => "error" -> JString(e)).toList
        JObject(fields ::: errorField)

  given eth_sign: JsonMethodDecoder[SignRequest] = new JsonMethodDecoder[SignRequest]:
    override def decodeJson(params: Option[JArray]): Either[JsonRpcError, SignRequest] =
      params match
        case Some(JArray(JString(addr) :: JString(message) :: _)) =>
          for
            message <- extractBytes(message)
            address <- extractAddress(addr)
          yield SignRequest(message, address, None)
        case _ =>
          Left(InvalidParams())

  def extractCall(obj: JObject): Either[JsonRpcError, CallTx] =
    def toEitherOpt[A, B](opt: Option[Either[A, B]]): Either[A, Option[B]] =
      opt match
        case Some(Right(v)) => Right(Option(v))
        case Some(Left(e))  => Left(e)
        case None           => Right(None)

    for
      from <- toEitherOpt((obj \ "from").extractOpt[String].map(extractBytes))
      to <- toEitherOpt((obj \ "to").extractOpt[String].map(extractBytes))
      gas <- optionalQuantity(obj \ "gas")
      gasPrice <- optionalQuantity(obj \ "gasPrice")
      maxFeePerGas <- optionalQuantity(obj \ "maxFeePerGas")
      value <- optionalQuantity(obj \ "value")
      data <- toEitherOpt((obj \ "data").extractOpt[String].map(extractBytes))
      input <- toEitherOpt((obj \ "input").extractOpt[String].map(extractBytes))
    yield CallTx(
      from = from,
      to = to,
      gas = gas,
      gasPrice = gasPrice.orElse(maxFeePerGas).getOrElse(0),
      value = value.getOrElse(0),
      data = data.orElse(input).getOrElse(ByteString("")),
      gasPriceExplicit = gasPrice.isDefined || maxFeePerGas.isDefined
    )
