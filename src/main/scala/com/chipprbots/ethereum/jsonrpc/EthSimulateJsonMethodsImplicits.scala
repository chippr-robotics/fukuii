package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import org.json4s.JsonAST.*
import org.json4s.jvalue2extractable
import org.json4s.jvalue2monadic

import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.jsonrpc.EthSimulateService.*
import com.chipprbots.ethereum.jsonrpc.JsonRpcError.InvalidParams
import com.chipprbots.ethereum.jsonrpc.serialization.JsonEncoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder

object EthSimulateJsonMethodsImplicits extends JsonMethodsImplicits:
  implicit override val formats: org.json4s.Formats = org.json4s.DefaultFormats

  given eth_simulateV1: (JsonMethodDecoder[EthSimulateRequest] & JsonEncoder[EthSimulateResponse]) =
    new JsonMethodDecoder[EthSimulateRequest] with JsonEncoder[EthSimulateResponse]:

      override def decodeJson(params: Option[JArray]): Either[JsonRpcError, EthSimulateRequest] =
        params match
          case Some(JArray((payload: JObject) :: rest)) =>
            // An absent or null block tag is "latest" (geth: nil blockNrOrHash); a malformed one is -32602, not
            // silently "latest".
            val blockTag = rest match
              case (JNull | JNothing) :: _ => Right(BlockParam.Latest)
              case (bt: JValue) :: _       => extractBlockParam(bt)
              case _                       => Right(BlockParam.Latest)
            blockTag.flatMap(decodePayload(payload, _))
          case _ => Left(InvalidParams("expected [payload, blockTag]"))

      private def decodePayload(obj: JObject, blockTag: BlockParam): Either[JsonRpcError, EthSimulateRequest] =
        val validation = (obj \ "validation").extractOpt[Boolean].getOrElse(false)
        val returnFullTxs = (obj \ "returnFullTransactions").extractOpt[Boolean].getOrElse(false)
        val traceTransfers = (obj \ "traceTransfers").extractOpt[Boolean].getOrElse(false)

        val blockStateCalls = (obj \ "blockStateCalls") match
          case JArray(items) =>
            val parsed = items.map {
              case bsc: JObject => decodeBlockStateCall(bsc)
              case _            => Left(InvalidParams("blockStateCall must be object"))
            }
            if parsed.exists(_.isLeft) then return parsed.collectFirst { case Left(e) => Left(e) }.get
            Right(parsed.collect { case Right(v) => v })
          case JNothing | JNull => Right(Seq.empty)
          case _                => Left(InvalidParams("blockStateCalls must be array"))

        blockStateCalls.map { bscs =>
          EthSimulateRequest(bscs, validation, returnFullTxs, traceTransfers, blockTag)
        }

      private def decodeBlockStateCall(obj: JObject): Either[JsonRpcError, BlockStateCall] =
        val blockOverrides = (obj \ "blockOverrides") match
          case bo: JObject =>
            unknownKey(bo, KnownBlockOverrideKeys) match
              case Some(k) => return Left(InvalidParams(s"invalid argument 0: unknown field \"$k\" in block override"))
              case None =>
                decodeBlockOverrides(bo) match
                  case Right(ov) => Some(ov)
                  case Left(err) => return Left(err)
          case _ => None

        val stateOverrides = (obj \ "stateOverrides") match
          case JObject(fields) =>
            val parsed = fields.map {
              case (addrHex, value: JObject) =>
                extractAddress(JString(addrHex)) match
                  case Right(addr) =>
                    unknownKey(value, KnownStateOverrideKeys) match
                      case Some(k) =>
                        Left(
                          InvalidParams(s"invalid argument 0: unknown field \"$k\" in state override for $addrHex")
                        )
                      case None => decodeStateOverride(value, addrHex).map(ov => (addr, ov))
                  case Left(_) => Left(InvalidParams(s"invalid address: $addrHex"))
              case (k, _) => Left(InvalidParams(s"invalid state override entry: $k"))
            }
            if parsed.exists(_.isLeft) then return parsed.collectFirst { case Left(e) => Left(e) }.get
            Some(parsed.collect { case Right((k, v)) => (k, v) }.toMap)
          case _ => None

        val calls = (obj \ "calls") match
          case JArray(items) =>
            val parsed = items.map {
              // execution-apis GenericTransaction, decoded as strictly as go-ethereum's TransactionArgs: a malformed
              // address, quantity or blob hash is -32602, never a silently absent field (#1629 S4). The payload is
              // params[0], so errors read "invalid argument 0: ...", as geth reports them. Only the shape is checked
              // here: the field rules (CallDefaults, preCheck) run per call during the simulation, in geth's order,
              // so a request with several faults reports the one geth reports.
              case c: JObject => TxArgsJson.decode(c, argIndex = 0)
              case _          => Left(InvalidParams("call must be object"))
            }
            if parsed.exists(_.isLeft) then return parsed.collectFirst { case Left(e) => Left(e) }.get
            Some(parsed.collect { case Right(v) => v })
          case JNothing | JNull => None
          case _                => return Left(InvalidParams("invalid argument 0: calls must be an array"))

        Right(BlockStateCall(blockOverrides, stateOverrides, calls))

      // execution-apis: "A client MUST reject a key that it does not support with error code -32602." The key sets
      // are the schema's (src/schemas/execute.yaml); `difficulty` is accepted here and rejected post-merge by the
      // service, which knows the simulated block's era.
      private val KnownBlockOverrideKeys =
        Set(
          "number",
          "time",
          "gasLimit",
          "feeRecipient",
          "prevRandao",
          "baseFeePerGas",
          "blobBaseFee",
          "withdrawals",
          "difficulty"
        )
      private val KnownStateOverrideKeys =
        Set("balance", "nonce", "code", "state", "stateDiff", "movePrecompileToAddress")

      private def unknownKey(obj: JObject, known: Set[String]): Option[String] =
        obj.obj.collectFirst { case (k, _) if !known.contains(k) => k }

      // The override fields are decoded as strictly as go-ethereum's JSON unmarshalling of BlockOverrides and
      // OverrideAccount: a value that is not the field's type is -32602 (invalid argument 0), never a silently absent
      // override. (#1660 item 13: the same rule TxArgsJson applies to the calls.)
      private def decodeBlockOverrides(obj: JObject): Either[JsonRpcError, BlockOverrides] =
        val what = "block override"
        for
          number <- optQty(obj, "number", what)
          time <- optQty(obj, "time", what)
          gasLimit <- optQty(obj, "gasLimit", what)
          feeRecipient <- optAddress(obj, "feeRecipient", what)
          prevRandao <- optBytes(obj, "prevRandao", what)
          baseFeePerGas <- optQty(obj, "baseFeePerGas", what)
          blobBaseFee <- optQty(obj, "blobBaseFee", what)
          difficulty <- optQty(obj, "difficulty", what)
          withdrawals <- optWithdrawals(obj, "withdrawals", what)
        yield BlockOverrides(
          number = number,
          time = time,
          gasLimit = gasLimit,
          feeRecipient = feeRecipient,
          prevRandao = prevRandao,
          baseFeePerGas = baseFeePerGas,
          blobBaseFee = blobBaseFee,
          difficulty = difficulty,
          withdrawals = withdrawals
        )

      private def decodeStateOverride(obj: JObject, addrHex: String): Either[JsonRpcError, StateOverride] =
        val what = s"state override for $addrHex"
        for
          balance <- optQty(obj, "balance", what)
          nonce <- optQty(obj, "nonce", what)
          code <- optBytes(obj, "code", what)
          state <- decodeStorageMap(obj, "state", what)
          stateDiff <- decodeStorageMap(obj, "stateDiff", what)
          move <- optAddress(obj, "movePrecompileToAddress", what)
        yield StateOverride(
          balance = balance,
          nonce = nonce,
          code = code,
          state = state,
          stateDiff = stateDiff,
          movePrecompileToAddress = move
        )

      private def invalid(field: String, what: String, value: JValue): JsonRpcError =
        InvalidParams(
          s"invalid argument 0: invalid $field in $what: ${org.json4s.native.JsonMethods.compact(org.json4s.native.JsonMethods.render(value))}"
        )

      private def isHexDigits(s: String): Boolean =
        s.forall(c => (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))

      // hexutil.Bytes: a 0x prefix is required (or the empty string) and the digits are in pairs
      private def hexBytes(hex: String): Option[Array[Byte]] =
        val clean = hex.stripPrefix("0x").stripPrefix("0X")
        Option.when((hex.isEmpty || clean.length != hex.length) && clean.length % 2 == 0 && isHexDigits(clean))(
          org.bouncycastle.util.encoders.Hex.decode(clean)
        )

      // A quantity: the digits are any length (an odd count is padded), as the decoder always accepted
      private def hexToBigInt(hex: String): Option[BigInt] =
        val clean = hex.stripPrefix("0x").stripPrefix("0X")
        if clean.isEmpty then Some(BigInt(0))
        else Option.when(isHexDigits(clean))(BigInt(clean, 16))

      private def decodeStorageMap(
          obj: JObject,
          field: String,
          what: String
      ): Either[JsonRpcError, Option[Map[BigInt, BigInt]]] =
        (obj \ field) match
          case JObject(fields) =>
            val entries = fields.map {
              case (keyHex, v @ JString(valueHex)) =>
                (hexToBigInt(keyHex), hexToBigInt(valueHex)) match
                  case (Some(k), Some(x)) => Right((k, x))
                  case _                  => Left(invalid(field, what, JObject(keyHex -> v)))
              case (keyHex, v) => Left(invalid(field, what, JObject(keyHex -> v)))
            }
            entries.collectFirst { case Left(e) => e } match
              case Some(e) => Left(e)
              case None    => Right(Some(entries.collect { case Right(kv) => kv }.toMap))
          case JNothing | JNull => Right(None)
          case other            => Left(invalid(field, what, other))

      private def optQty(obj: JObject, field: String, what: String): Either[JsonRpcError, Option[BigInt]] =
        (obj \ field) match
          case JNothing | JNull => Right(None)
          case v @ JString(s) =>
            hexToBigInt(s) match
              case Some(n) => Right(Some(n))
              case None    => Left(invalid(field, what, v))
          case other => Left(invalid(field, what, other))

      private def optBytes(obj: JObject, field: String, what: String): Either[JsonRpcError, Option[ByteString]] =
        (obj \ field) match
          case JNothing | JNull => Right(None)
          case v @ JString(s) =>
            hexBytes(s) match
              case Some(b) => Right(Some(ByteString(b)))
              case None    => Left(invalid(field, what, v))
          case other => Left(invalid(field, what, other))

      private def optAddress(obj: JObject, field: String, what: String): Either[JsonRpcError, Option[Address]] =
        (obj \ field) match
          case JNothing | JNull => Right(None)
          case v @ JString(s)   =>
            // common.Address: 0x and exactly 20 bytes
            if s.length == 42 && (s.startsWith("0x") || s.startsWith("0X")) && isHexDigits(s.drop(2)) then
              extractAddress(v) match
                case Right(a) => Right(Some(a))
                case Left(_)  => Left(invalid(field, what, v))
            else Left(invalid(field, what, v))
          case other => Left(invalid(field, what, other))

      // go-ethereum types.Withdrawal's JSON: index, validatorIndex, amount are uint64 quantities, address an address
      private def optWithdrawals(
          obj: JObject,
          field: String,
          what: String
      ): Either[JsonRpcError, Option[Seq[Withdrawal]]] =
        (obj \ field) match
          case JNothing | JNull => Right(None)
          case JArray(items) =>
            val decoded = items.map {
              case w: JObject =>
                for
                  index <- optQty(w, "index", what).flatMap(_.toRight(invalid("withdrawals.index", what, w)))
                  validatorIndex <- optQty(w, "validatorIndex", what)
                    .flatMap(_.toRight(invalid("withdrawals.validatorIndex", what, w)))
                  address <- optAddress(w, "address", what).flatMap(_.toRight(invalid("withdrawals.address", what, w)))
                  amount <- optQty(w, "amount", what).flatMap(_.toRight(invalid("withdrawals.amount", what, w)))
                yield Withdrawal(index, validatorIndex, address, amount)
              case other => Left(invalid(field, what, other))
            }
            decoded.collectFirst { case Left(e) => e } match
              case Some(e) => Left(e)
              case None    => Right(Some(decoded.collect { case Right(w) => w }))
          case other => Left(invalid(field, what, other))

      // --- Encoder ---
      override def encodeJson(t: EthSimulateResponse): JValue =
        JArray(t.blocks.map(b => encodeSimulatedBlock(b, t.returnFullTransactions)).toList)

      private def encodeSimulatedBlock(block: SimulateBlockResult, returnFullTxs: Boolean): JValue =
        val h = block.header
        val blockHash = h.hash.value

        // Standard block header fields — conditionally include fork-specific fields
        val baseHeaderFields = List(
          "difficulty" -> encodeAsHex(h.difficulty.value),
          "extraData" -> encodeAsHex(h.extraData),
          "gasLimit" -> encodeAsHex(h.gasLimit.value),
          "gasUsed" -> encodeAsHex(h.gasUsed.value),
          "hash" -> encodeAsHex(blockHash),
          "logsBloom" -> encodeAsHex(h.logsBloom.value),
          "miner" -> encodeAsHex(h.beneficiary),
          "mixHash" -> encodeAsHex(h.mixHash.value),
          "nonce" -> encodeAsHex(h.nonce),
          "number" -> encodeAsHex(h.number.value),
          "parentHash" -> encodeAsHex(h.parentHash.value),
          "receiptsRoot" -> encodeAsHex(h.receiptsRoot.value),
          "sha3Uncles" -> encodeAsHex(h.ommersHash.value),
          "size" -> encodeAsHex(BigInt(Block.size(Block(h, block.body)))),
          "stateRoot" -> encodeAsHex(h.stateRoot.value),
          // uint64 bit pattern -> unsigned BigInt; see BlockResponse.scala for the sign-extension bug this avoids.
          "timestamp" -> encodeAsHex(h.unixTimestamp.toUnsignedBigInt),
          "transactionsRoot" -> encodeAsHex(h.transactionsRoot.value),
          "uncles" -> JArray(Nil)
        ) ++ (if h.withdrawalsRoot.isDefined then
                List("withdrawals" -> JArray(block.body.withdrawals.getOrElse(Seq.empty).map(encodeWithdrawal).toList))
              else Nil)

        // Conditionally add fork-specific fields
        val baseFeeField = h.baseFee.map(bf => "baseFeePerGas" -> encodeAsHex(bf)).toList
        val blobFields = h.blobGasUsed.map(bg => "blobGasUsed" -> encodeAsHex(bg)).toList ++
          h.excessBlobGas.map(eb => "excessBlobGas" -> encodeAsHex(eb)).toList
        val beaconField =
          h.parentBeaconBlockRoot.map(pb => "parentBeaconBlockRoot" -> encodeAsHex(pb.value)).toList
        val requestsField = h.requestsHash.map(rh => "requestsHash" -> encodeAsHex(rh)).toList
        val withdrawalsRootField = h.withdrawalsRoot.map(wr => "withdrawalsRoot" -> encodeAsHex(wr)).toList
        // Amsterdam (EIP-7928, EIP-7843): only a 23-field header has them, as in go-ethereum's RPCMarshalHeader. The
        // block `hash` above commits to both, so a response that left them out could not be checked against it.
        val amsterdamFields =
          h.blockAccessListHash.map(bal => "blockAccessListHash" -> encodeAsHex(bal)).toList :::
            h.slotNumber.map(slot => "slotNumber" -> encodeAsHex(slot)).toList

        val headerFields =
          baseFeeField ::: blobFields ::: baseHeaderFields ::: beaconField ::: requestsField ::: withdrawalsRootField :::
            amsterdamFields

        // Transactions: hashes or full objects depending on returnFullTransactions flag
        val txField =
          if returnFullTxs then
            "transactions" -> JArray(block.transactions.zipWithIndex.map { case (tx, idx) =>
              val sender =
                if idx < block.senders.size then block.senders(idx) else com.chipprbots.ethereum.domain.Address(0)
              encodeSimulatedTxFull(tx, idx, h, sender)
            }.toList)
          else "transactions" -> JArray(block.transactions.map(tx => encodeAsHex(tx.hash.value)).toList)

        // Per-call results
        val callsField = "calls" -> JArray(block.calls.map(encodeCallResult(_, blockHash, h)).toList)

        JObject(headerFields :+ txField :+ callsField)

      // go-ethereum types.Withdrawal's JSON
      private def encodeWithdrawal(w: Withdrawal): JValue =
        JObject(
          "index" -> encodeAsHex(w.index),
          "validatorIndex" -> encodeAsHex(w.validatorIndex),
          "address" -> encodeAsHex(w.address.bytes),
          "amount" -> encodeAsHex(w.amount)
        )

      private def encodeSimulatedTxFull(
          stx: com.chipprbots.ethereum.domain.SignedTransaction,
          txIdx: Int,
          header: com.chipprbots.ethereum.domain.BlockHeader,
          senderAddr: com.chipprbots.ethereum.domain.Address
      ): JValue =
        val tx = stx.tx
        val blockHash = header.hash.value
        val txType = tx match
          case _: com.chipprbots.ethereum.domain.LegacyTransaction         => BigInt(0)
          case _: com.chipprbots.ethereum.domain.TransactionWithAccessList => BigInt(1)
          case _: com.chipprbots.ethereum.domain.TransactionWithDynamicFee => BigInt(2)
          case _: com.chipprbots.ethereum.domain.BlobTransaction           => BigInt(3)
          case _: com.chipprbots.ethereum.domain.SetCodeTransaction        => BigInt(4)
        val chainId: Option[BigInt] = tx match
          case t: com.chipprbots.ethereum.domain.BlobTransaction           => Some(t.chainId)
          case t: com.chipprbots.ethereum.domain.SetCodeTransaction        => Some(t.chainId)
          case t: com.chipprbots.ethereum.domain.TransactionWithDynamicFee => Some(t.chainId)
          case t: com.chipprbots.ethereum.domain.TransactionWithAccessList => Some(t.chainId)
          case _ => Some(com.chipprbots.ethereum.utils.Config.blockchains.blockchainConfig.chainId.value)
        val sender = senderAddr.bytes
        val effectiveGasPrice = com.chipprbots.ethereum.domain.Transaction.effectiveGasPrice(tx, header.baseFee)
        val baseFields = List(
          "blockHash" -> encodeAsHex(blockHash),
          "blockNumber" -> encodeAsHex(header.number.value),
          // uint64 bit pattern -> unsigned BigInt; see BlockResponse.scala for the sign-extension bug this avoids.
          "blockTimestamp" -> encodeAsHex(header.unixTimestamp.toUnsignedBigInt),
          "from" -> encodeAsHex(sender),
          "gas" -> encodeAsHex(tx.gasLimit.value),
          "gasPrice" -> encodeAsHex(effectiveGasPrice),
          "hash" -> encodeAsHex(stx.hash.value),
          "input" -> encodeAsHex(tx.payload),
          "nonce" -> encodeAsHex(tx.nonce),
          "to" -> tx.receivingAddress.map(a => encodeAsHex(a.bytes)).getOrElse(JNull),
          "transactionIndex" -> encodeAsHex(BigInt(txIdx)),
          "type" -> encodeAsHex(txType),
          "value" -> encodeAsHex(tx.value),
          "v" -> encodeAsHex(BigInt(0)),
          "r" -> encodeAsHex(BigInt(0)),
          "s" -> encodeAsHex(BigInt(0)),
          "yParity" -> encodeAsHex(BigInt(0))
        )
        val chainIdField = chainId.map(c => "chainId" -> encodeAsHex(c)).toList
        val maxFeeFields = tx match
          case t: com.chipprbots.ethereum.domain.BlobTransaction =>
            List(
              "maxFeePerGas" -> encodeAsHex(t.maxFeePerGas),
              "maxPriorityFeePerGas" -> encodeAsHex(t.maxPriorityFeePerGas)
            )
          case t: com.chipprbots.ethereum.domain.TransactionWithDynamicFee =>
            List(
              "maxFeePerGas" -> encodeAsHex(t.maxFeePerGas),
              "maxPriorityFeePerGas" -> encodeAsHex(t.maxPriorityFeePerGas)
            )
          case t: com.chipprbots.ethereum.domain.SetCodeTransaction =>
            List(
              "maxFeePerGas" -> encodeAsHex(t.maxFeePerGas),
              "maxPriorityFeePerGas" -> encodeAsHex(t.maxPriorityFeePerGas)
            )
          case _ => Nil
        // The call's own access list and authorizations, as go-ethereum's RPCTransaction reports them.
        val accessListField = tx match
          case t: com.chipprbots.ethereum.domain.BlobTransaction =>
            List("accessList" -> TxArgsJson.encodeAccessList(t.accessList))
          case t: com.chipprbots.ethereum.domain.TransactionWithDynamicFee =>
            List("accessList" -> TxArgsJson.encodeAccessList(t.accessList))
          case t: com.chipprbots.ethereum.domain.TransactionWithAccessList =>
            List("accessList" -> TxArgsJson.encodeAccessList(t.accessList))
          case t: com.chipprbots.ethereum.domain.SetCodeTransaction =>
            List(
              "accessList" -> TxArgsJson.encodeAccessList(t.accessList),
              "authorizationList" -> TxArgsJson.encodeAuthorizationList(t.authorizationList)
            )
          case _ => Nil
        val blobFields = tx match
          case t: com.chipprbots.ethereum.domain.BlobTransaction =>
            List(
              "maxFeePerBlobGas" -> encodeAsHex(t.maxFeePerBlobGas),
              "blobVersionedHashes" -> JArray(t.blobVersionedHashes.map(h => encodeAsHex(h.value)).toList)
            )
          case _ => Nil
        JObject(baseFields ::: chainIdField ::: maxFeeFields ::: accessListField ::: blobFields)

      private def encodeCallResult(
          cr: SimulateCallResult,
          blockHash: ByteString,
          @annotation.unused _header: BlockHeader
      ): JValue =
        val baseFields = List(
          "returnData" -> encodeAsHex(cr.returnData),
          "gasUsed" -> encodeAsHex(cr.gasUsed),
          "maxUsedGas" -> encodeAsHex(cr.maxUsedGas),
          "status" -> encodeAsHex(cr.status)
        )

        val logsField =
          if cr.error.isEmpty then
            List("logs" -> JArray(cr.logs.map(log => encodeSimulateTxLog(log, blockHash)).toList))
          else List("logs" -> JArray(Nil))

        val errorField = cr.error.map { err =>
          val errFields = List(
            "code" -> JInt(err.code),
            "message" -> JString(err.message)
          ) ++ err.data.map(d => "data" -> encodeAsHex(d))
          "error" -> JObject(errFields)
        }.toList

        JObject(baseFields ::: logsField ::: errorField)

      private def encodeSimulateTxLog(log: FilterManager.TxLog, blockHash: ByteString): JValue =
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
