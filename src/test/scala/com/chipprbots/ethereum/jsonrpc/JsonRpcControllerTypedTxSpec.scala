package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.bouncycastle.util.encoders.Hex
import org.json4s.*
import org.json4s.Formats
import org.json4s.JsonAST.*
import org.json4s.JsonDSL.*
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.jsonrpc.PersonalService.*
import com.chipprbots.ethereum.jsonrpc.serialization.JsonSerializers.OptionNoneToJNullSerializer
import com.chipprbots.ethereum.jsonrpc.serialization.JsonSerializers.QuantitiesSerializer
import com.chipprbots.ethereum.jsonrpc.serialization.JsonSerializers.UnformattedDataJsonSerializer
import com.chipprbots.ethereum.keystore.Wallet
import com.chipprbots.ethereum.testing.Tags.*

/** Dispatch and wire format of eth_sendTransaction, eth_signTransaction, eth_fillTransaction, personal_sendTransaction
  * and personal_signTransaction (#1629 S2): params decode through S1's strict `TxArgsJson`, results are go-ethereum's
  * `{raw, tx}`.
  */
class JsonRpcControllerTypedTxSpec
    extends ScalaTestWithActorTestKit
    with AnyFlatSpecLike
    with Matchers
    with JRCMatchers
    with org.scalamock.scalatest.MockFactory
    with JsonRpcControllerTestSupport
    with ScalaFutures:

  implicit val runtime: IORuntime = IORuntime.global
  implicit private val classicActorSystem: ActorSystem = system.toClassic
  implicit private val actorTestKitImpl: org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit = testKit
  implicit val formats: Formats = DefaultFormats.preservingEmptyValues + OptionNoneToJNullSerializer +
    QuantitiesSerializer + UnformattedDataJsonSerializer

  private val from = Address(42)
  private val to = Address(123)
  private val wallet = Wallet(
    Address(Hex.decode("71562b71999873db5b286df957af199ec94617f7")),
    ByteString(Hex.decode("b71c71a67e1177ad4e901695e1b4b9ee17ae16c6668d313eac2f96dbcda3f291"))
  )
  // geth TestSignTransaction's 0x2 transaction (api_test.go:3048); see TxSigningVectorsSpec
  private val signed = wallet
    .signTx(
      TransactionWithDynamicFee(
        1,
        0,
        0,
        BigInt(0x684ee180),
        GasAmount(21000),
        Some(Address(Hex.decode("703c4b2bd70c169f5717101caee543299fc946c7"))),
        1,
        ByteString.empty,
        Nil
      ),
      None
    )
    .tx
  private val result = SignTransactionResult(signed, None)

  "eth_signTransaction" should "decode TxArgs and return geth's {raw, tx}" taggedAs (
    UnitTest,
    RPCTest
  ) in new JsonRpcControllerFixture:
    var seen: Option[SignTransactionRequest] = None
    personalService.signTransactionFn = req =>
      seen = Some(req)
      IO.pure(Right(SignTransactionResponse(result)))
    val params = List(
      JObject(
        "from" -> from.toString,
        "to" -> to.toString,
        "gas" -> "0x5208",
        "maxFeePerGas" -> "0x684ee180",
        "maxPriorityFeePerGas" -> "0x0",
        "nonce" -> "0x0",
        "input" -> "0x"
      )
    )
    val response = jsonRpcController.handleRequest(newJsonRpcRequest("eth_signTransaction", params)).unsafeRunSync()
    response should haveResult(SignTransactionResult.encode(result))
    seen.map(_.passphrase) shouldBe Some(None)
    seen.flatMap(_.tx.maxFeePerGas) shouldBe Some(BigInt(0x684ee180))
    (SignTransactionResult.encode(result) \ "raw") shouldBe JString(
      "0x02f86601808084684ee18082520894703c4b2bd70c169f5717101caee543299fc946c70180c080a08fabeb142d585dd9247f459f7e6fe77e2520c88d50ba5d220da1533cea8b34e1a0582dd68b21aef36ba23f34e49607329c20d981d30404daf749077f5606785ce7"
    )

  it should "reject geth-invalid params with -32602" taggedAs (UnitTest, RPCTest) in new JsonRpcControllerFixture:
    def call(params: List[JValue]) =
      jsonRpcController.handleRequest(newJsonRpcRequest("eth_signTransaction", params)).unsafeRunSync()
    call(Nil) should haveError(JsonRpcError.InvalidParams("missing value for required argument 0"))
    call(List[JValue](JObject("from" -> JString(from.toString)), JObject())) should haveError(
      JsonRpcError.InvalidParams("too many arguments, want at most 1")
    )
    // a JSON number is not a hexutil quantity
    call(List(JObject("from" -> from.toString, "value" -> JInt(1000)))) should haveError(
      JsonRpcError.InvalidParams(
        "invalid argument 0: json: cannot unmarshal non-string into Go struct field TransactionArgs.value of type hexutil.Big"
      )
    )

  "eth_fillTransaction" should "return the unsigned {raw, tx}" taggedAs (
    UnitTest,
    RPCTest
  ) in new JsonRpcControllerFixture:
    val unsigned = SignTransactionResult.unsigned(signed.tx, None)
    personalService.fillTransactionFn = _ => IO.pure(Right(FillTransactionResponse(unsigned)))
    val response = jsonRpcController
      .handleRequest(
        newJsonRpcRequest("eth_fillTransaction", List(JObject("from" -> from.toString, "to" -> to.toString)))
      )
      .unsafeRunSync()
    response should haveResult(SignTransactionResult.encode(unsigned))

  "personal_signTransaction" should "take the args and a passphrase" taggedAs (
    UnitTest,
    RPCTest
  ) in new JsonRpcControllerFixture:
    var seen: Option[SignTransactionRequest] = None
    personalService.signTransactionFn = req =>
      seen = Some(req)
      IO.pure(Right(SignTransactionResponse(result)))
    val ok = jsonRpcController
      .handleRequest(
        newJsonRpcRequest(
          "personal_signTransaction",
          List[JValue](JObject("from" -> JString(from.toString), "to" -> JString(to.toString)), JString("pw"))
        )
      )
      .unsafeRunSync()
    ok should haveResult(SignTransactionResult.encode(result))
    seen.map(_.passphrase) shouldBe Some(Some("pw"))
    jsonRpcController
      .handleRequest(
        newJsonRpcRequest("personal_signTransaction", List[JValue](JObject("from" -> JString(from.toString))))
      )
      .unsafeRunSync() should haveError(JsonRpcError.InvalidParams("missing value for required argument 1"))

  "eth_sendTransaction" should "pass typed fields through to the service" taggedAs (
    UnitTest,
    RPCTest
  ) in new JsonRpcControllerFixture:
    var seen: Option[SendTransactionRequest] = None
    personalService.sendTransactionFn = req =>
      seen = Some(req)
      IO.pure(Right(SendTransactionResponse(signed.hash.value)))
    val params = List(
      JObject(
        "from" -> from.toString,
        "to" -> to.toString,
        "accessList" -> JArray(List(JObject("address" -> to.toString, "storageKeys" -> JArray(Nil)))),
        "type" -> "0x1",
        "data" -> "0x1234"
      )
    )
    jsonRpcController.handleRequest(newJsonRpcRequest("eth_sendTransaction", params)).unsafeRunSync() should
      haveResult(JString(s"0x${Hex.toHexString(signed.hash.value.toArray)}"))
    seen.map(_.tx.txType) shouldBe Some(Some(TxArgs.AccessListType))
    seen.map(_.tx.callData) shouldBe Some(ByteString(0x12, 0x34))

  it should "reject data and input that differ" taggedAs (UnitTest, RPCTest) in new JsonRpcControllerFixture:
    val params = List(JObject("from" -> from.toString, "to" -> to.toString, "data" -> "0x01", "input" -> "0x02"))
    jsonRpcController.handleRequest(newJsonRpcRequest("eth_sendTransaction", params)).unsafeRunSync() should haveError(
      TxArgsError.DataInputMismatch.toJsonRpcError
    )
