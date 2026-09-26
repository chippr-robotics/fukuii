package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.bouncycastle.util.encoders.Hex
import org.json4s.*

import com.chipprbots.ethereum.blockchain.data.GenesisAccount
import com.chipprbots.ethereum.blockchain.data.GenesisData
import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.mining.Protocol
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.consensus.validators.Validators
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.jsonrpc.JsonRpcResponse
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.ledger.EestBlockchainReplay
import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Replays one execution-specs `blockchain_test_engine` fixture through the Engine API, the way hive's
  * `eels/consume-engine` drives a client: the real [[EngineApiController]] in front of a real [[EngineApiService]], on
  * a chain whose genesis the node's own [[GenesisDataLoader]] builds from the fixture (`pre`, `genesisBlockHeader`).
  *
  *   1. `engine_forkchoiceUpdatedV{first payload's version}` to genesis, expecting VALID.
  *   1. Per `engineNewPayloads` entry, `engine_newPayloadV{newPayloadVersion}` with the fixture's `params` untouched.
  *      An `errorCode` must come back as that JSON-RPC error; otherwise the status must be VALID, or INVALID when the
  *      entry has a `validationError`. After a VALID payload, `engine_forkchoiceUpdatedV{forkchoiceUpdatedVersion}`
  *      makes it the head and must answer VALID.
  *   1. The head must end on the fixture's `lastblockhash`.
  *
  * Only which way each payload goes is compared, not the `validationError` text: EEST maps each client's messages to
  * its exception names with per-client regexes, and has none for fukuii. Every call runs on the calling thread
  * (`syncStep`), so block execution gets the worker's stack, as in [[EestBlockchainReplay]].
  */
object EestEngineReplay:

  private def hx(s: String): Array[Byte] = Hex.decode(s.stripPrefix("0x"))
  private def bi(s: String): BigInt = if s.stripPrefix("0x").isEmpty then 0 else BigInt(s.stripPrefix("0x"), 16)
  private def str(v: JValue): String = v.values.toString
  private def optStr(v: JValue): Option[String] = v.toOption.collect { case JString(s) => s }

  private class Env extends EphemBlockchainTestSetup:
    override lazy val validators: Validators = ValidatorsExecutor(Protocol.EngineApi)
    override lazy val vm: VMImpl = new VMImpl

  private val zeroHash = "0x" + "00" * 32

  /** Runs `io` on the calling thread when it never leaves it (every Engine call made here is a chain of IO.delay and
    * IO.defer), so the EVM runs on the replay worker's stack rather than on a cats-effect pool thread's.
    */
  private def runHere[A](io: IO[A]): A =
    io.syncStep(Int.MaxValue).unsafeRunSync() match
      case Right(a)    => a
      case Left(async) => async.unsafeRunSync()(using IORuntime.global)

  /** `(status, validationError)` of the PayloadStatusV1 `statusObject` picks out of the answer, or its JSON-RPC error. */
  private def outcome(response: JsonRpcResponse, statusObject: JValue => JValue): Either[String, (String, String)] =
    response.error match
      case Some(error) => Left(s"error ${error.code} (${error.message})")
      case None =>
        val payloadStatus = statusObject(response.result.getOrElse(JNothing))
        Right(
          (
            optStr(payloadStatus \ "status").getOrElse(s"<no status in $payloadStatus>"),
            optStr(payloadStatus \ "validationError").getOrElse("")
          )
        )

  /** Replays one fixture and returns its divergences from the release; empty means the fixture passes. */
  def replay(t: JValue): Seq[String] =
    val env = new Env
    import env.{blockQueue, blockchain, blockchainReader, blockchainWriter, mining, storagesInstance}
    val chainId = (t \ "config" \ "chainid").toOption.map(v => bi(str(v))).getOrElse(BigInt(1))
    EestBlockchainReplay.configFor(env.blockchainConfig, str(t \ "network"), chainId) match
      case Left(unmodelled) => Seq(unmodelled)
      case Right(config) =>
        given BlockchainConfig = config

        val gh = t \ "genesisBlockHeader"
        val JObject(pre) = t \ "pre": @unchecked
        val alloc = pre.map { case (address, acc) =>
          val code = ByteString(hx(str(acc \ "code")))
          val JObject(storage) = acc \ "storage": @unchecked
          address.stripPrefix("0x") -> GenesisAccount(
            precompiled = None,
            balance = UInt256(bi(str(acc \ "balance"))),
            code = Option.when(code.nonEmpty)(code),
            nonce = Some(UInt256(bi(str(acc \ "nonce")))),
            storage = Option.when(storage.nonEmpty)(storage.map { case (k, v) =>
              UInt256(bi(k)) -> UInt256(bi(str(v)))
            }.toMap)
          )
        }.toMap
        // The production genesis path, fed the fixture's genesis fields as a genesis file gives them (hex quantities).
        new GenesisDataLoader(
          blockchainReader,
          blockchainWriter,
          storagesInstance.storages.evmCodeStorage,
          storagesInstance.storages.stateStorage
        ).loadGenesisData(
          GenesisData(
            nonce = ByteString(hx(str(gh \ "nonce"))),
            mixHash = Some(ByteString(hx(str(gh \ "mixHash")))),
            difficulty = str(gh \ "difficulty"),
            extraData = ByteString(hx(str(gh \ "extraData"))),
            gasLimit = str(gh \ "gasLimit"),
            coinbase = ByteString(hx(str(gh \ "coinbase"))),
            timestamp = str(gh \ "timestamp"),
            alloc = alloc,
            baseFeePerGas = optStr(gh \ "baseFeePerGas"),
            excessBlobGas = optStr(gh \ "excessBlobGas"),
            blobGasUsed = optStr(gh \ "blobGasUsed"),
            slotNumber = optStr(gh \ "slotNumber")
          )
        ).get
        val genesisHash =
          blockchainReader.getBlockHeaderByNumber(0).map(h => "0x" + Hex.toHexString(h.hash.value.toArray))
        val expectedGenesis = str(gh \ "hash")
        if !genesisHash.contains(expectedGenesis) then
          Seq(s"genesis: the loader built $genesisHash, the fixture's is $expectedGenesis")
        else
          val blockValidation = new BlockValidation(mining, blockchainReader, blockQueue)
          val blockExecution = new BlockExecution(
            blockchain,
            blockchainReader,
            blockchainWriter,
            storagesInstance.storages.evmCodeStorage,
            mining.blockPreparator,
            blockValidation
          )
          val service = new EngineApiService(
            blockchainReader,
            blockchainWriter,
            blockExecution,
            new ForkChoiceManager(blockchainReader, blockchainWriter),
            None
          )(config, null) // no pool, so no scheduler: nothing is asked of an actor
          val controller = new EngineApiController(service, None, config)
          var requestId = 0
          def call(method: String, params: List[JValue]): JsonRpcResponse =
            requestId += 1
            runHere(
              controller.handleRequest(JsonRpcRequest("2.0", method, Some(JArray(params)), Some(JInt(requestId))))
            )
          def forkchoiceUpdated(version: String, head: String): Either[String, (String, String)] =
            val state = JObject(
              "headBlockHash" -> JString(head),
              "safeBlockHash" -> JString(zeroHash),
              "finalizedBlockHash" -> JString(zeroHash)
            )
            outcome(call(s"engine_forkchoiceUpdatedV$version", List(state)), _ \ "payloadStatus")

          val JArray(payloads) = t \ "engineNewPayloads": @unchecked
          val firstFcuVersion = payloads.headOption.map(p => str(p \ "forkchoiceUpdatedVersion")).getOrElse("1")
          forkchoiceUpdated(firstFcuVersion, expectedGenesis) match
            case Right(("VALID", _)) =>
              val payloadDivergences = payloads.zipWithIndex.flatMap { case (p, i) =>
                val version = str(p \ "newPayloadVersion")
                val fcuVersion = str(p \ "forkchoiceUpdatedVersion")
                val expectedCode = optStr(p \ "errorCode").map(_.toInt)
                val expectedError = optStr(p \ "validationError")
                val expected = expectedCode.fold(expectedError.fold("VALID")(e => s"INVALID ($e)"))(c => s"error $c")
                val JArray(params) = p \ "params": @unchecked
                val label = s"payload[$i] newPayloadV$version"
                val got = outcome(call(s"engine_newPayloadV$version", params), identity)
                val matches = (expectedCode, got) match
                  case (Some(code), Left(err))    => err.startsWith(s"error $code ")
                  case (Some(_), Right(_))        => false
                  case (None, Left(_))            => false
                  case (None, Right((status, _))) => status == (if expectedError.isDefined then "INVALID" else "VALID")
                val gotText = got match
                  case Left(err)                            => err
                  case Right((status, why)) if why.nonEmpty => s"$status ($why)"
                  case Right((status, _))                   => status
                if !matches then Seq(s"$label: expected $expected, got $gotText")
                else if expectedCode.isEmpty && expectedError.isEmpty then
                  forkchoiceUpdated(fcuVersion, str(params.head \ "blockHash")) match
                    case Right(("VALID", _)) => Nil
                    case other               => Seq(s"$label: forkchoiceUpdatedV$fcuVersion to it answered $other")
                else Nil
              }
              val head = blockchainReader.getBestBlock.map(b => "0x" + Hex.toHexString(b.header.hash.value.toArray))
              val expectedHead = str(t \ "lastblockhash")
              payloadDivergences ++
                (if head.contains(expectedHead) then Nil else Seq(s"head $head != lastblockhash $expectedHead"))
            case other => Seq(s"forkchoiceUpdatedV$firstFcuVersion to genesis answered $other")
