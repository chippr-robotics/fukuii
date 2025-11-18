package com.chipprbots.ethereum.jsonrpc

import java.time.Duration

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.bouncycastle.util.encoders.Hex
import org.json4s.DefaultFormats
import org.json4s.Formats
import org.json4s.JsonAST._
import org.json4s.JsonDSL._
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.LongPatience
import com.chipprbots.ethereum.WithActorSystemShutDown
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.jsonrpc.PersonalService._
import com.chipprbots.ethereum.jsonrpc.serialization.JsonSerializers.OptionNoneToJNullSerializer
import com.chipprbots.ethereum.jsonrpc.serialization.JsonSerializers.QuantitiesSerializer
import com.chipprbots.ethereum.jsonrpc.serialization.JsonSerializers.UnformattedDataJsonSerializer
import com.chipprbots.ethereum.testing.Tags._

class JsonRpcControllerPersonalSpec
    extends TestKit(ActorSystem("JsonRpcControllerPersonalSpec_System"))
    with AnyFlatSpecLike
    with WithActorSystemShutDown
    with Matchers
    with JRCMatchers
    with org.scalamock.scalatest.MockFactory
    with JsonRpcControllerTestSupport
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with LongPatience
    with Eventually {

  implicit val runtime: IORuntime = IORuntime.global

  implicit val formats: Formats = DefaultFormats.preservingEmptyValues + OptionNoneToJNullSerializer +
    QuantitiesSerializer + UnformattedDataJsonSerializer

  it should "personal_importRawKey" taggedAs (UnitTest, RPCTest) in new JsonRpcControllerFixture {
    val key = "7a44789ed3cd85861c0bbf9693c7e1de1862dd4396c390147ecf1275099c6e6f"
    val keyBytes: ByteString = ByteString(Hex.decode(key))
    val addr: Address = Address("0x00000000000000000000000000000000000000ff")
    val pass = "aaa"

    (personalService.importRawKey _)
      .expects(ImportRawKeyRequest(keyBytes, pass))
      .returning(IO.pure(Right(ImportRawKeyResponse(addr))))

    val params: List[JString] = JString(key) :: JString(pass) :: Nil
    val rpcRequest: JsonRpcRequest = newJsonRpcRequest("personal_importRawKey", params)
    val response: JsonRpcResponse = jsonRpcController.handleRequest(rpcRequest).unsafeRunSync()

    response should haveStringResult(addr.toString)
  }

  it should "personal_newAccount" taggedAs (UnitTest, RPCTest) in new JsonRpcControllerFixture {
    val addr: Address = Address("0x00000000000000000000000000000000000000ff")
    val pass = "aaa"

    (personalService.newAccount _)
      .expects(NewAccountRequest(pass))
      .returning(IO.pure(Right(NewAccountResponse(addr))))

    val params: List[JString] = JString(pass) :: Nil
    val rpcRequest: JsonRpcRequest = newJsonRpcRequest("personal_newAccount", params)
    val response: JsonRpcResponse = jsonRpcController.handleRequest(rpcRequest).unsafeRunSync()

    response should haveStringResult(addr.toString)
  }

  it should "personal_listAccounts" taggedAs (UnitTest, RPCTest) in new JsonRpcControllerFixture {
    val addresses: List[Address] = List(34, 12391, 123).map(Address(_))

    (personalService.listAccounts _)
      .expects(ListAccountsRequest())
      .returning(IO.pure(Right(ListAccountsResponse(addresses))))

    val rpcRequest: JsonRpcRequest = newJsonRpcRequest("personal_listAccounts")
    val response: JsonRpcResponse = jsonRpcController.handleRequest(rpcRequest).unsafeRunSync()

    response should haveResult(JArray(addresses.map(a => JString(a.toString))))
  }

  it should "personal_unlockAccount" taggedAs (UnitTest, RPCTest) in new JsonRpcControllerFixture {
    val address: Address = Address(42)
    val pass = "aaa"
    val params: List[JString] = JString(address.toString) :: JString(pass) :: Nil

    (personalService.unlockAccount _)
      .expects(UnlockAccountRequest(address, pass, None))
      .returning(IO.pure(Right(UnlockAccountResponse(true))))

    val rpcRequest: JsonRpcRequest = newJsonRpcRequest("personal_unlockAccount", params)
    val response: JsonRpcResponse = jsonRpcController.handleRequest(rpcRequest).unsafeRunSync()

    response should haveBooleanResult(true)
  }

  it should "personal_unlockAccount for specified duration" taggedAs (UnitTest, RPCTest) in new JsonRpcControllerFixture {
    val address: Address = Address(42)
    val pass = "aaa"
    val dur = "1"
    val params: List[JString] = JString(address.toString) :: JString(pass) :: JString(dur) :: Nil

    (personalService.unlockAccount _)
      .expects(UnlockAccountRequest(address, pass, Some(Duration.ofSeconds(1))))
      .returning(IO.pure(Right(UnlockAccountResponse(true))))

    val rpcRequest: JsonRpcRequest = newJsonRpcRequest("personal_unlockAccount", params)
    val response: JsonRpcResponse = jsonRpcController.handleRequest(rpcRequest).unsafeRunSync()

    response should haveBooleanResult(true)
  }

  it should "personal_unlockAccount should handle possible duration errors" taggedAs (UnitTest, RPCTest) in new JsonRpcControllerFixture {
    val address: Address = Address(42)
    val pass = "aaa"
    val dur = "alksjdfh"

    val params: List[JString] = JString(address.toString) :: JString(pass) :: JString(dur) :: Nil
    val rpcRequest: JsonRpcRequest = newJsonRpcRequest("personal_unlockAccount", params)
    val response: JsonRpcResponse = jsonRpcController.handleRequest(rpcRequest).unsafeRunSync()

    response should haveError(JsonRpcError(-32602, "Invalid method parameters", None))

    val dur2 = Long.MaxValue
    val params2: List[JValue] = JString(address.toString) :: JString(pass) :: JInt(dur2) :: Nil
    val rpcRequest2: JsonRpcRequest = newJsonRpcRequest("personal_unlockAccount", params2)
    val response2: JsonRpcResponse = jsonRpcController.handleRequest(rpcRequest2).unsafeRunSync()
    response2 should haveError(
      JsonRpcError(-32602, "Duration should be an number of seconds, less than 2^31 - 1", None)
    )
  }

  it should "personal_unlockAccount should handle null passed as a duration for compatibility with Parity and web3j" taggedAs (UnitTest, RPCTest) in new JsonRpcControllerFixture {
    val address: Address = Address(42)
    val pass = "aaa"
    val params: List[JValue] = JString(address.toString) :: JString(pass) :: JNull :: Nil

    (personalService.unlockAccount _)
      .expects(UnlockAccountRequest(address, pass, None))
      .returning(IO.pure(Right(UnlockAccountResponse(true))))

    val rpcRequest: JsonRpcRequest = newJsonRpcRequest("personal_unlockAccount", params)
    val response: JsonRpcResponse = jsonRpcController.handleRequest(rpcRequest).unsafeRunSync()

    response should haveBooleanResult(true)
  }

  it should "personal_lockAccount" taggedAs (UnitTest, RPCTest) in new JsonRpcControllerFixture {
    val address: Address = Address(42)
    val params: List[JString] = JString(address.toString) :: Nil

    (personalService.lockAccount _)
      .expects(LockAccountRequest(address))
      .returning(IO.pure(Right(LockAccountResponse(true))))

    val rpcRequest: JsonRpcRequest = newJsonRpcRequest("personal_lockAccount", params)
    val response: JsonRpcResponse = jsonRpcController.handleRequest(rpcRequest).unsafeRunSync()

    response should haveBooleanResult(true)
  }

  it should "personal_sendTransaction" taggedAs (UnitTest, RPCTest) in new JsonRpcControllerFixture {
    val params: List[JValue] = JObject(
      "from" -> Address(42).toString,
      "to" -> Address(123).toString,
      "value" -> 1000
    ) :: JString("passphrase") :: Nil

    val txHash: ByteString = ByteString(1, 2, 3, 4)

    (personalService
      .sendTransaction(_: SendTransactionWithPassphraseRequest))
      .expects(*)
      .returning(IO.pure(Right(SendTransactionWithPassphraseResponse(txHash))))

    val rpcRequest: JsonRpcRequest = newJsonRpcRequest("personal_sendTransaction", params)
    val response: JsonRpcResponse = jsonRpcController.handleRequest(rpcRequest).unsafeRunSync()

    response should haveResult(JString(s"0x${Hex.toHexString(txHash.toArray)}"))
  }

  it should "personal_sign" taggedAs (UnitTest, RPCTest) in new JsonRpcControllerFixture {

    (personalService.sign _)
      .expects(
        SignRequest(
          ByteString(Hex.decode("deadbeaf")),
          Address(ByteString(Hex.decode("9b2055d370f73ec7d8a03e965129118dc8f5bf83"))),
          Some("thePassphrase")
        )
      )
      .returns(IO.pure(Right(SignResponse(sig))))

    val request: JsonRpcRequest = newJsonRpcRequest(
      "personal_sign",
      List(
        JString(s"0xdeadbeaf"),
        JString(s"0x9b2055d370f73ec7d8a03e965129118dc8f5bf83"),
        JString("thePassphrase")
      )
    )

    val response: JsonRpcResponse = jsonRpcController.handleRequest(request).unsafeRunSync()
    response should haveStringResult(
      "0xa3f20717a250c2b0b729b7e5becbff67fdaef7e0699da4de7ca5895b02a170a12d887fd3b17bfdce3481f10bea41f45ba9f709d39ce8325427b57afcfc994cee1b"
    )
  }

  it should "personal_ecRecover" taggedAs (UnitTest, RPCTest) in new JsonRpcControllerFixture {

    (personalService.ecRecover _)
      .expects(EcRecoverRequest(ByteString(Hex.decode("deadbeaf")), sig))
      .returns(
        IO.pure(
          Right(EcRecoverResponse(Address(ByteString(Hex.decode("9b2055d370f73ec7d8a03e965129118dc8f5bf83")))))
        )
      )

    val request: JsonRpcRequest = newJsonRpcRequest(
      "personal_ecRecover",
      List(
        JString(s"0xdeadbeaf"),
        JString(
          s"0xa3f20717a250c2b0b729b7e5becbff67fdaef7e0699da4de7ca5895b02a170a12d887fd3b17bfdce3481f10bea41f45ba9f709d39ce8325427b57afcfc994cee1b"
        )
      )
    )

    val response: JsonRpcResponse = jsonRpcController.handleRequest(request).unsafeRunSync()
    response should haveStringResult("0x9b2055d370f73ec7d8a03e965129118dc8f5bf83")
  }
}
