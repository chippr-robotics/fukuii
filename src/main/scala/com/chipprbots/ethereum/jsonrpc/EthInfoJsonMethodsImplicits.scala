package com.chipprbots.ethereum.jsonrpc

import org.bouncycastle.util.encoders.Hex
import org.json4s.*
import org.json4s.JsonAST.*
import org.json4s.JsonDSL.*

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.jsonrpc.EthInfoService.*
import com.chipprbots.ethereum.jsonrpc.JsonRpcError.InvalidParams
import com.chipprbots.ethereum.jsonrpc.PersonalService.FillTransactionRequest
import com.chipprbots.ethereum.jsonrpc.PersonalService.FillTransactionResponse
import com.chipprbots.ethereum.jsonrpc.PersonalService.SendTransactionRequest
import com.chipprbots.ethereum.jsonrpc.PersonalService.SendTransactionResponse
import com.chipprbots.ethereum.jsonrpc.PersonalService.SignTransactionRequest
import com.chipprbots.ethereum.jsonrpc.PersonalService.SignTransactionResponse
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
        txArgsParam(params).map(SendTransactionRequest.apply)

      def encodeJson(t: SendTransactionResponse): JValue =
        encodeAsHex(t.txHash)

  given eth_signTransaction: JsonMethodCodec[SignTransactionRequest, SignTransactionResponse] =
    new JsonMethodCodec[SignTransactionRequest, SignTransactionResponse]:
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, SignTransactionRequest] =
        txArgsParam(params).map(SignTransactionRequest(_, passphrase = None))

      def encodeJson(t: SignTransactionResponse): JValue = SignTransactionResult.encode(t.result)

  given eth_fillTransaction: JsonMethodCodec[FillTransactionRequest, FillTransactionResponse] =
    new JsonMethodCodec[FillTransactionRequest, FillTransactionResponse]:
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, FillTransactionRequest] =
        txArgsParam(params).map(FillTransactionRequest.apply)

      def encodeJson(t: FillTransactionResponse): JValue = SignTransactionResult.encode(t.result)

  given eth_call: (JsonMethodDecoder[CallRequest] & JsonEncoder[CallResponse]) =
    new JsonMethodDecoder[CallRequest] with JsonEncoder[CallResponse]:
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, CallRequest] = decodeCallParams(params)

      def encodeJson(t: CallResponse): JValue = encodeAsHex(t.returnData)

  given eth_estimateGas: eth_estimateGas = new eth_estimateGas
  class eth_estimateGas extends JsonMethodDecoder[CallRequest] with JsonEncoder[EstimateGasResponse]:
    override def encodeJson(t: EstimateGasResponse): JValue = encodeAsHex(t.gas)

    override def decodeJson(params: Option[JArray]): Either[JsonRpcError, CallRequest] = decodeCallParams(params)

  /** eth_createAccessList params: `[call, block?, stateOverrides?]` (go-ethereum `CreateAccessList(args, blockNrOrHash
    * *, stateOverrides *StateOverride)`).
    */
  given eth_createAccessList: (JsonMethodDecoder[CreateAccessListRequest] & JsonEncoder[CreateAccessListResponse]) =
    new JsonMethodDecoder[CreateAccessListRequest] with JsonEncoder[CreateAccessListResponse]:
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, CreateAccessListRequest] =
        params match
          case Some(JArray(txObj :: rest)) if rest.size <= 2 =>
            val padded = rest.padTo(2, JNothing)
            for
              tx <- extractCall(txObj)
              block <- optionalBlockParam(padded(0))
              stateOverrides <- CallOverridesJson.decodeStateOverrides(padded(1), 2)
            yield CreateAccessListRequest(tx, block, stateOverrides)
          case _ => Left(InvalidParams())

      // geth AccessListResult: accessList, then error only when the call failed, gasUsed
      def encodeJson(t: CreateAccessListResponse): JValue =
        val fields =
          List("accessList" -> TxArgsJson.encodeAccessList(t.accessList), "gasUsed" -> encodeAsHex(t.gasUsed))
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

  /** A call object (execution-apis `GenericTransaction`), decoded and checked by the shared S1 decoder:
    * [[TxArgsJson.decodeForCall]] (geth `TransactionArgs` + `CallDefaults`' state-free checks). Used by eth_call,
    * eth_estimateGas, eth_createAccessList, debug_traceCall(Many) and trace_call(Many).
    */
  def extractCall(obj: JValue): Either[JsonRpcError, TxArgs] = TxArgsJson.decodeForCall(obj, 0)

  /** eth_call / eth_estimateGas params: `[call, block?, stateOverrides?, blockOverrides?]` (execution-apis; go-ethereum
    * `BlockChainAPI.Call(args, blockNrOrHash *, overrides *StateOverride, blockOverrides *BlockOverrides)`). An absent
    * or null block is `latest`; an absent or null override is none.
    */
  private[jsonrpc] def decodeCallParams(params: Option[JArray]): Either[JsonRpcError, CallRequest] =
    params match
      case Some(JArray(txObj :: rest)) if rest.size <= 3 =>
        val padded = rest.padTo(3, JNothing)
        for
          tx <- extractCall(txObj)
          block <- optionalBlockParam(padded(0))
          stateOverrides <- CallOverridesJson.decodeStateOverrides(padded(1), 2)
          blockOverrides <- CallOverridesJson.decodeBlockOverrides(padded(2), 3)
        yield CallRequest(tx, block, stateOverrides, blockOverrides)
      case _ => Left(InvalidParams())

  private def optionalBlockParam(v: JValue): Either[JsonRpcError, BlockParam] =
    v match
      case JNull | JNothing => Right(BlockParam.Latest)
      case other            => extractBlockParam(other)
