package com.chipprbots.ethereum.jsonrpc.conformance

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*

import cats.effect.unsafe.IORuntime

import java.io.File

import io.circe.Json
import org.bouncycastle.util.encoders.Hex
import org.json4s.*
import org.json4s.native.JsonMethods.parse
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.testing.Tags.*

/** Typed-transaction conformance of the JSON-RPC and GraphQL interfaces against go-ethereum-produced vectors (#1629
  * S5).
  *
  * The node is execution-apis' test chain ([[RpcCompatNode]], imported and execution-validated in process), the same
  * chain and the same files hive's rpc-compat simulator uses: `tests/<method>/<name>.io`, copied verbatim from
  * execution-apis 62eefaa into `src/test/resources/rpc-compat/tests/`. Each vector goes through
  * `JsonRpcController.handleRequest` on one shared node, in hive's order (lexical by path), and is compared as hive
  * compares it: whole response, error messages dropped when both sides are errors, `speconly` vectors pass unless the
  * node answers an error. The chain carries one mined transaction of every type 0 to 4, so the getTransaction and
  * receipt vectors cover each; eth_sendRawTransaction covers types 0 to 3.
  *
  * A vector fukuii does not yet answer as go-ethereum does is listed in `knownDivergences` with the reason. Its test
  * asserts the divergence is still there, so fixing it fails the test until the entry is removed.
  *
  * The GraphQL part reads the same mined transactions through GraphQL and holds every typed-transaction field to the
  * value go-ethereum's JSON-RPC vectors give for that transaction (geth's GraphQL resolvers compute each one the same
  * way, `graphql/graphql.go`).
  *
  * Tagged SlowTest: the in-process chain import and 90 vectors take 30 s to over 2 min depending on host load, so the
  * suite runs in testStandard rather than the testEssential commit gate.
  */
class TypedTxConformanceSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  private given ActorSystem = system.toClassic
  private given ActorTestKit = testKit
  private given IORuntime = IORuntime.global

  private lazy val node = new RpcCompatNode
  private lazy val head = node.importChain()

  /** The methods whose vectors run here, the ones the typed-transaction slices (#1629 S0-S5) touch. */
  private val methods = Seq(
    "debug_getRawReceipts",
    "debug_getRawTransaction",
    "eth_call",
    "eth_createAccessList",
    "eth_estimateGas",
    "eth_getBlockByHash",
    "eth_getBlockByNumber",
    "eth_getBlockReceipts",
    "eth_getTransactionByBlockHashAndIndex",
    "eth_getTransactionByBlockNumberAndIndex",
    "eth_getTransactionByHash",
    "eth_getTransactionReceipt",
    "eth_sendRawTransaction",
    "txpool_content",
    "txpool_contentFrom",
    "txpool_status"
  )

  /** Vectors fukuii does not answer as go-ethereum does, with why. Kept exact: a fixed one fails until removed. */
  private val knownDivergences: Map[String, String] = Map()

  private def vectorFiles(method: String): Seq[String] =
    val dir = new File(getClass.getClassLoader.getResource(RpcCompatNode.Resources + method).toURI)
    dir.list().filter(_.endsWith(".io")).sorted.toSeq.map(f => s"$method/${f.stripSuffix(".io")}")

  private def call(request: JValue): JValue =
    val req = JsonRpcRequest(
      (request \ "jsonrpc").values.toString,
      (request \ "method").values.toString,
      (request \ "params").toOption.collect { case a: JArray => a },
      (request \ "id").toOption
    )
    RpcCompatVectors.wire(node.controller.handleRequest(req).unsafeRunSync())

  "the execution-apis test chain" should "import, every block execution-validated, to the vectors' head" taggedAs (
    UnitTest,
    RPCTest,
    SlowTest
  ) in {
    head.header.number.value shouldBe BigInt(0x3c)
    node.blockchainReader.getBlockHeaderByNumber(0).map(_.hash) shouldBe Some(
      RpcCompatNode.blocks.head.header.parentHash
    )
    Hex.toHexString(head.header.hash.value.toArray) shouldBe
      "52bccfc9c4d4874b1898c0fbd0ae3b598a0edc7b94d9b796857dbbe1093c32e0"
  }

  for method <- methods; name <- vectorFiles(method) do
    it should s"answer $name as go-ethereum does" taggedAs (UnitTest, RPCTest, SlowTest) in {
      head // the chain first
      val vector = RpcCompatVectors.read(RpcCompatNode.resource(name + ".io"))
      val mismatches = vector.exchanges.flatMap { case (request, expected) =>
        RpcCompatVectors.mismatch(vector, call(request), expected)
      }
      knownDivergences.get(name) match
        case Some(reason) =>
          withClue(s"$name now matches go-ethereum; remove it from knownDivergences ($reason)") {
            mismatches should not be empty
          }
        case None =>
          withClue(s"$name (${vector.comment}): ")(mismatches shouldBe empty)
    }

  // -------------------------------------------------------------------------------------------------------------------
  // GraphQL: the mined transaction of each type, field by field against go-ethereum's JSON-RPC answer for it.

  private def expectedResult(name: String): JValue =
    RpcCompatVectors.read(RpcCompatNode.resource(name + ".io")).exchanges.head._2 \ "result"

  private def graphQL(query: String): Json =
    val (status, body) = node.graphQL.execute(query, None, None).unsafeRunSync()
    withClue(body.noSpaces)(status shouldBe 200)
    body.hcursor.downField("errors").focus shouldBe None
    body.hcursor.downField("data").focus.get

  private def toJson4s(json: Json): JValue = parse(json.noSpaces)

  private val txFields =
    """hash nonce index from { address } to { address } value gasPrice maxFeePerGas maxPriorityFeePerGas
      |maxFeePerBlobGas effectiveTip gas inputData block { number hash baseFeePerGas } status gasUsed
      |cumulativeGasUsed effectiveGasPrice blobGasUsed blobGasPrice createdContract { address } logs { index }
      |r s v yParity type accessList { address storageKeys } raw rawReceipt blobVersionedHashes""".stripMargin

  private val typedTransactions = Seq(
    "legacy-tx" -> ("get-legacy-tx", "get-legacy-receipt"),
    "legacy contract creation" -> ("get-legacy-create", "get-legacy-contract"),
    "access-list (type 1)" -> ("get-access-list", "get-access-list"),
    "dynamic-fee (type 2)" -> ("get-dynamic-fee", "get-dynamic-fee"),
    "blob (type 3)" -> ("get-blob-tx", "get-blob-tx"),
    "set-code (type 4)" -> ("get-setcode-tx", "get-setcode-tx")
  )

  private def big(v: JValue): BigInt = v match
    case JString(s) => BigInt(s.stripPrefix("0x"), 16)
    case other      => fail(s"not a quantity: $other")

  for (label, (txVector, receiptVector)) <- typedTransactions do
    "GraphQL Transaction" should s"report the mined $label transaction as go-ethereum's JSON-RPC does" taggedAs (
      UnitTest,
      RPCTest,
      SlowTest
    ) in {
      head
      val tx = expectedResult(s"eth_getTransactionByHash/$txVector")
      val receipt = expectedResult(s"eth_getTransactionReceipt/$receiptVector")
      val JString(hash) = tx \ "hash": @unchecked
      val got = toJson4s(graphQL(s"""{ transaction(hash: "$hash") { $txFields } }""")) \ "transaction"
      def same(field: String, want: JValue): Unit = withClue(s"$label $field: ")(got \ field shouldBe want)
      def nullIfAbsent(v: JValue): JValue = if v == JNothing then JNull else v

      // Transaction fields geth's RPC object carries under the same name.
      for field <- Seq("hash", "nonce", "value", "gasPrice", "gas", "type", "r", "s", "v") do same(field, tx \ field)
      for field <- Seq("maxFeePerGas", "maxPriorityFeePerGas", "maxFeePerBlobGas", "blobVersionedHashes", "yParity")
      do same(field, nullIfAbsent(tx \ field))
      same("inputData", tx \ "input")
      same("index", tx \ "transactionIndex")
      got \ "from" \ "address" shouldBe tx \ "from"
      (got \ "to") match
        case JNull => tx \ "to" shouldBe JNull
        case to    => to \ "address" shouldBe tx \ "to"
      got \ "block" \ "hash" shouldBe tx \ "blockHash"
      got \ "block" \ "number" shouldBe tx \ "blockNumber"
      // geth's AccessList resolver returns [] for a legacy transaction (tx.AccessList() is empty), not null.
      same("accessList", if tx \ "accessList" == JNothing then JArray(Nil) else tx \ "accessList")

      // Receipt-backed fields: geth's GraphQL reads these from the same receipt eth_getTransactionReceipt returns.
      // A pre-Byzantium receipt has a state root instead of a status: geth's Status resolver answers null.
      same("status", nullIfAbsent(receipt \ "status"))
      same("gasUsed", receipt \ "gasUsed")
      same("cumulativeGasUsed", receipt \ "cumulativeGasUsed")
      same("effectiveGasPrice", receipt \ "effectiveGasPrice")
      same("blobGasUsed", nullIfAbsent(receipt \ "blobGasUsed"))
      same("blobGasPrice", nullIfAbsent(receipt \ "blobGasPrice"))
      (got \ "createdContract") match
        case JNull    => receipt \ "contractAddress" shouldBe JNull
        case contract => contract \ "address" shouldBe receipt \ "contractAddress"
      (got \ "logs").asInstanceOf[JArray].arr.size shouldBe (receipt \ "logs").asInstanceOf[JArray].arr.size

      // A mined fee-market transaction's gasPrice is what it paid, the receipt's effectiveGasPrice (geth GasPrice).
      got \ "gasPrice" shouldBe receipt \ "effectiveGasPrice"
      // effectiveTip = effectiveGasPrice - baseFee once the block has a base fee (geth EffectiveTip).
      val baseFee = got \ "block" \ "baseFeePerGas"
      if baseFee != JNull then big(got \ "effectiveTip") shouldBe big(receipt \ "effectiveGasPrice") - big(baseFee)

      // raw is the EIP-2718 envelope whose keccak is the hash; rawReceipt carries the same type prefix.
      val raw = Hex.decode((got \ "raw").values.toString.stripPrefix("0x"))
      "0x" + Hex.toHexString(kec256(raw)) shouldBe hash
      val txType = big(tx \ "type").toInt
      val rawReceipt = Hex.decode((got \ "rawReceipt").values.toString.stripPrefix("0x"))
      if txType > 0 then
        raw.head.toInt shouldBe txType
        rawReceipt.head.toInt shouldBe txType
      else
        (raw.head & 0xff) should be >= 0xc0
        (rawReceipt.head & 0xff) should be >= 0xc0
    }

  it should "give a legacy transaction's raw as debug_getRawTransaction does" taggedAs (
    UnitTest,
    RPCTest,
    SlowTest
  ) in {
    head
    val vector = RpcCompatVectors.read(RpcCompatNode.resource("debug_getRawTransaction/get-tx.io"))
    val (request, expected) = vector.exchanges.head
    val JString(hash) = (request \ "params")(0): @unchecked
    val got = toJson4s(graphQL(s"""{ transaction(hash: "$hash") { raw } }"""))
    got \ "transaction" \ "raw" shouldBe expected \ "result"
  }
