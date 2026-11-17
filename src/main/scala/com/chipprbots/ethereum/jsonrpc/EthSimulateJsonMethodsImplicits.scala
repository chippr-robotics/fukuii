package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import scala.util.boundary
import scala.util.boundary.break

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
            if parsed.exists(_.isLeft) then parsed.collectFirst { case Left(e) => Left(e) }.get
            else Right(parsed.collect { case Right(v) => v })
          case JNothing | JNull => Right(Seq.empty)
          case _                => Left(InvalidParams("blockStateCalls must be array"))

        blockStateCalls.map { bscs =>
          EthSimulateRequest(bscs, validation, returnFullTxs, traceTransfers, blockTag)
        }

      private def decodeBlockStateCall(obj: JObject): Either[JsonRpcError, BlockStateCall] =
        boundary:
          // go-ethereum's simBlock fields match case-insensitively, as encoding/json matches them, and the last match in
          // document order wins (simulate.go:73-108). The overrides decode as CallOverridesJson decodes them (geth's
          // hexutil rules and checkOverrideKeys); the payload is params[0], so errors read "invalid argument 0: ...".
          def field(name: String): JValue =
            obj.obj.reverseIterator.collectFirst { case (k, v) if k.equalsIgnoreCase(name) => v }.getOrElse(JNothing)

          val blockOverrides = CallOverridesJson.decodeSimulateBlockOverrides(field("blockOverrides"), 0) match
            case Right(ov) => ov
            case Left(err) => break(Left(err))

          val stateOverrides = CallOverridesJson.decodeSimulateStateOverrides(field("stateOverrides"), 0) match
            case Right(ov) => ov
            case Left(err) => break(Left(err))

          val calls = field("calls") match
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
              if parsed.exists(_.isLeft) then break(parsed.collectFirst { case Left(e) => Left(e) }.get)
              Some(parsed.collect { case Right(v) => v })
            case JNothing | JNull => None
            case _                => break(Left(InvalidParams("invalid argument 0: calls must be an array")))

          Right(BlockStateCall(blockOverrides, stateOverrides, calls))

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
        // go-ethereum newRPCTransaction (internal/ethapi/api.go) sets `yParity` for the typed transactions only; a
        // legacy transaction carries v, r and s alone.
        val yParityField = tx match
          case _: com.chipprbots.ethereum.domain.LegacyTransaction => Nil
          case _                                                   => List("yParity" -> encodeAsHex(BigInt(0)))
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
          "s" -> encodeAsHex(BigInt(0))
        ) ::: yParityField
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
