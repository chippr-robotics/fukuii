package com.chipprbots.ethereum.jsonrpc.server.http

import java.net.InetAddress
import java.util.concurrent.TimeUnit

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.apache.pekko.util.ByteString

import cats.effect.IO

import scala.concurrent.duration.FiniteDuration

import org.apache.pekko.http.cors.scaladsl.model.HttpOriginMatcher
import org.json4s.DefaultFormats
import org.json4s.Extraction
import org.json4s.JsonAST.JInt
import org.json4s.JsonAST.JNothing
import org.json4s.JsonAST.JString
import org.json4s.native.JsonMethods
import org.json4s.native.JsonMethods._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.healthcheck.HealthcheckResponse
import com.chipprbots.ethereum.healthcheck.HealthcheckResult
import com.chipprbots.ethereum.jsonrpc._
import com.chipprbots.ethereum.jsonrpc.server.controllers.ApisBase
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController.JsonRpcConfig
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcHttpServer.JsonRpcHttpServerConfig
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcHttpServer.RateLimitConfig
import com.chipprbots.ethereum.utils.BuildInfo
import com.chipprbots.ethereum.utils.Logger

/** Mockable stub controller for testing. This is needed because JsonRpcBaseController 
  * has self-type constraints that make it unmockable directly with ScalaMock in Scala 3.
  * 
  * Note: When this class is used with ScalaMock via `mock[MockableJsonRpcController]`, 
  * all method calls including `config` are intercepted by the mock framework.
  * The null value here is never actually accessed in practice.
  */
class MockableJsonRpcController extends JsonRpcBaseController with ApisBase with Logger {
  override def apisHandleFns: Map[String, PartialFunction[JsonRpcRequest, IO[JsonRpcResponse]]] = Map.empty
  override def enabledApis: Seq[String] = Seq.empty
  override def available: List[String] = List.empty
  @SuppressWarnings(Array("scalafix:DisableSyntax.null"))
  override val config: JsonRpcConfig = null
}

// SCALA 3 MIGRATION: Fixed by having test class extend MockFactory, which satisfies inner trait self-type constraints
class JsonRpcHttpServerSpec
    extends AnyFlatSpec
    with Matchers
    with ScalatestRouteTest
    with org.scalamock.scalatest.MockFactory {

  import JsonRpcHttpServerSpec._
  
  // Provide implicit MockFactory reference for TestSetup instances
  implicit val mockFactoryInstance: org.scalamock.scalatest.MockFactory = this

  it should "respond to healthcheck" taggedAs (UnitTest, RPCTest) in new TestSetup {
    (mockJsonRpcHealthChecker.healthCheck _)
      .expects()
      .returning(IO.pure(HealthcheckResponse(List(HealthcheckResult.ok("peerCount", Some("2"))))))

    val getRequest = HttpRequest(HttpMethods.GET, uri = "/healthcheck")

    getRequest ~> Route.seal(mockJsonRpcHttpServer.route) ~> check {
      status shouldEqual StatusCodes.OK
      parse(responseAs[String]) shouldEqual parse("""{
                                       |  "checks":[
                                       |    { "name": "peerCount", "status": "OK", "info": "2" }
                                       |  ]
                                       |}""".stripMargin)
    }
  }

  it should "respond to healthcheck with an error if one healthcheck fails" taggedAs (UnitTest, RPCTest) in new TestSetup {
    (mockJsonRpcHealthChecker.healthCheck _)
      .expects()
      .returning(
        IO.pure(
          HealthcheckResponse(
            List(
              HealthcheckResult.ok("otherCheck"),
              HealthcheckResult.error("peerCount", "peer count is 0")
            )
          )
        )
      )

    val getRequest = HttpRequest(HttpMethods.GET, uri = "/healthcheck")

    getRequest ~> Route.seal(mockJsonRpcHttpServer.route) ~> check {
      status shouldEqual StatusCodes.InternalServerError
      parse(responseAs[String]) shouldEqual parse("""{
                                       |  "checks":[
                                       |    { "name": "otherCheck", "status": "OK" },
                                       |    { "name": "peerCount", "status": "ERROR", "info": "peer count is 0" }
                                       |  ]
                                       |}""".stripMargin)
    }
  }

  it should "respond to buildinfo" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val buildInfoRequest = HttpRequest(HttpMethods.GET, uri = "/buildinfo")

    buildInfoRequest ~> Route.seal(mockJsonRpcHttpServer.route) ~> check {
      status shouldEqual StatusCodes.OK

      val expected = Extraction.decompose(BuildInfo.toMap)(DefaultFormats)
      val jsonResponse = JsonMethods.parse(responseAs[String])
      val diff = expected.diff(jsonResponse)

      diff.added shouldEqual JNothing
      diff.changed shouldEqual JNothing
      diff.deleted shouldEqual JNothing
    }
  }

  it should "pass valid json request to controller" taggedAs (UnitTest, RPCTest) in new TestSetup {
    (mockJsonRpcController.handleRequest _)
      .expects(*)
      .returning(IO.pure(jsonRpcResponseSuccessful))

    val postRequest =
      HttpRequest(HttpMethods.POST, uri = "/", entity = HttpEntity(MediaTypes.`application/json`, jsonRequest))

    postRequest ~> Route.seal(mockJsonRpcHttpServer.route) ~> check {
      status shouldEqual StatusCodes.OK
      val jsonRpcResponse = JsonRpcHttpServerSpec.parser(responseAs[String])
      jsonRpcResponse.result shouldEqual Some(resultSuccessful)
      jsonRpcResponse.jsonrpc shouldEqual jsonRpc
      jsonRpcResponse.id shouldEqual JInt(id)
    }
  }

  it should "pass valid batch json request to controller" taggedAs (UnitTest, RPCTest) in new TestSetup {
    (mockJsonRpcController.handleRequest _)
      .expects(*)
      .twice()
      .returning(IO.pure(jsonRpcResponseSuccessful))

    val jsonRequests =
      ByteString("""[{"jsonrpc":"2.0", "method": "asd", "id": "1"}, {"jsonrpc":"2.0", "method": "asd", "id": "2"}]""")
    val postRequest =
      HttpRequest(HttpMethods.POST, uri = "/", entity = HttpEntity(MediaTypes.`application/json`, jsonRequests))

    postRequest ~> Route.seal(mockJsonRpcHttpServer.route) ~> check {
      status === StatusCodes.OK
      responseAs[String] shouldEqual """[{"jsonrpc":"2.0","result":"this is a response","id":1},{"jsonrpc":"2.0","result":"this is a response","id":1}]"""
    }
  }

  it should "return BadRequest when malformed request is received" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val jsonRequestInvalid = ByteString("""{"jsonrpc":"2.0", "method": "this is not a valid json""")
    val postRequest =
      HttpRequest(HttpMethods.POST, uri = "/", entity = HttpEntity(MediaTypes.`application/json`, jsonRequestInvalid))

    postRequest ~> Route.seal(mockJsonRpcHttpServer.route) ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "return a CORS Error" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val postRequest = HttpRequest(
      HttpMethods.POST,
      uri = "/",
      headers = Origin(HttpOrigin("http://non_accepted_origin.com")) :: Nil,
      entity = HttpEntity(MediaTypes.`application/json`, jsonRequest)
    )

    import mockJsonRpcHttpServerWithCors.myRejectionHandler
    postRequest ~> Route.seal(mockJsonRpcHttpServerWithCors.route) ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "accept CORS Requests" taggedAs (UnitTest, RPCTest) in new TestSetup {

    (mockJsonRpcController.handleRequest _)
      .expects(*)
      .returning(IO.pure(jsonRpcResponseSuccessful))

    val postRequest = HttpRequest(
      HttpMethods.POST,
      uri = "/",
      headers = Origin(corsAllowedOrigin) :: Nil,
      entity = HttpEntity(MediaTypes.`application/json`, jsonRequest)
    )

    postRequest ~> Route.seal(mockJsonRpcHttpServerWithCors.route) ~> check {
      status shouldEqual StatusCodes.OK
    }
  }

  it should "accept json request with ip restriction and only one request" taggedAs (UnitTest, RPCTest) in new TestSetup {
    (mockJsonRpcController.handleRequest _)
      .expects(*)
      .returning(IO.pure(jsonRpcResponseSuccessful))

    val postRequest =
      HttpRequest(HttpMethods.POST, uri = "/", entity = HttpEntity(MediaTypes.`application/json`, jsonRequest))

    postRequest ~> Route.seal(mockJsonRpcHttpServerWithRateLimit.route) ~> check {
      status shouldEqual StatusCodes.OK
      val jsonRpcResponse = JsonRpcHttpServerSpec.parser(responseAs[String])
      jsonRpcResponse.result shouldEqual Some(resultSuccessful)
      jsonRpcResponse.jsonrpc shouldEqual jsonRpc
      jsonRpcResponse.id shouldEqual JInt(id)
    }
  }

  it should "return too many requests error with ip-restriction enabled and two requests executed taggedAs (UnitTest, RPCTest) in a row" in new TestSetup {
    (mockJsonRpcController.handleRequest _)
      .expects(*)
      .returning(IO.pure(jsonRpcResponseSuccessful))

    val postRequest =
      HttpRequest(HttpMethods.POST, uri = "/", entity = HttpEntity(MediaTypes.`application/json`, jsonRequest))

    postRequest ~> Route.seal(mockJsonRpcHttpServerWithRateLimit.route) ~> check {
      status shouldEqual StatusCodes.OK
      val jsonRpcResponse = JsonRpcHttpServerSpec.parser(responseAs[String])
      jsonRpcResponse.result shouldEqual Some(resultSuccessful)
      jsonRpcResponse.jsonrpc shouldEqual jsonRpc
      jsonRpcResponse.id shouldEqual JInt(id)
    }
    postRequest ~> Route.seal(mockJsonRpcHttpServerWithRateLimit.route) ~> check {
      status shouldEqual StatusCodes.TooManyRequests
    }
  }

  it should "return method not allowed error for batch request with ip-restriction enabled" taggedAs (UnitTest, RPCTest) in new TestSetup {
    // When rate limiting is enabled, batch requests should be rejected without calling handleRequest
    val jsonRequests =
      ByteString("""[{"jsonrpc":"2.0", "method": "asd", "id": "1"}, {"jsonrpc":"2.0", "method": "asd", "id": "2"}]""")
    val postRequest =
      HttpRequest(HttpMethods.POST, uri = "/", entity = HttpEntity(MediaTypes.`application/json`, jsonRequests))

    postRequest ~> Route.seal(mockJsonRpcHttpServerWithRateLimit.route) ~> check {
      status === StatusCodes.MethodNotAllowed
    }
  }

  it should "accept json request after rejected request with ip-restriction enabled once time has passed" taggedAs (UnitTest, RPCTest) in new TestSetup {
    (mockJsonRpcController.handleRequest _)
      .expects(*)
      .twice()
      .returning(IO.pure(jsonRpcResponseSuccessful))

    val postRequest =
      HttpRequest(HttpMethods.POST, uri = "/", entity = HttpEntity(MediaTypes.`application/json`, jsonRequest))

    postRequest ~> Route.seal(mockJsonRpcHttpServerWithRateLimit.route) ~> check {
      status shouldEqual StatusCodes.OK
      val jsonRpcResponse = JsonRpcHttpServerSpec.parser(responseAs[String])
      jsonRpcResponse.result shouldEqual Some(resultSuccessful)
      jsonRpcResponse.jsonrpc shouldEqual jsonRpc
      jsonRpcResponse.id shouldEqual JInt(id)
    }
    postRequest ~> Route.seal(mockJsonRpcHttpServerWithRateLimit.route) ~> check {
      status shouldEqual StatusCodes.TooManyRequests
    }

    mockJsonRpcHttpServerWithRateLimit.mockedTime = 50000000L

    postRequest ~> Route.seal(mockJsonRpcHttpServerWithRateLimit.route) ~> check {
      status shouldEqual StatusCodes.OK
      val jsonRpcResponse = JsonRpcHttpServerSpec.parser(responseAs[String])
      jsonRpcResponse.result shouldEqual Some(resultSuccessful)
      jsonRpcResponse.jsonrpc shouldEqual jsonRpc
      jsonRpcResponse.id shouldEqual JInt(id)
    }
  }

  it should "accept json requests from different IPs with ip-restriction enabled" taggedAs (UnitTest, RPCTest) in new TestSetup {
    (mockJsonRpcController.handleRequest _)
      .expects(*)
      .twice()
      .returning(IO.pure(jsonRpcResponseSuccessful))

    val postRequest =
      HttpRequest(HttpMethods.POST, uri = "/", entity = HttpEntity(MediaTypes.`application/json`, jsonRequest))

    val postRequest2 =
      HttpRequest(
        HttpMethods.POST,
        uri = "/",
        headers = List(`X-Forwarded-For`(RemoteAddress.apply(InetAddress.getByName("1.2.3.4")))),
        entity = HttpEntity(MediaTypes.`application/json`, jsonRequest)
      )

    postRequest ~> Route.seal(mockJsonRpcHttpServerWithRateLimit.route) ~> check {
      status shouldEqual StatusCodes.OK
      val jsonRpcResponse = JsonRpcHttpServerSpec.parser(responseAs[String])
      jsonRpcResponse.result shouldEqual Some(resultSuccessful)
      jsonRpcResponse.jsonrpc shouldEqual jsonRpc
      jsonRpcResponse.id shouldEqual JInt(id)
    }
    postRequest2 ~> Route.seal(mockJsonRpcHttpServerWithRateLimit.route) ~> check {
      status shouldEqual StatusCodes.OK
      val jsonRpcResponse = JsonRpcHttpServerSpec.parser(responseAs[String])
      jsonRpcResponse.result shouldEqual Some(resultSuccessful)
      jsonRpcResponse.jsonrpc shouldEqual jsonRpc
      jsonRpcResponse.id shouldEqual JInt(id)
    }
  }

  it should "return status code OK when throw LogicError" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val jsonRpcError = JsonRpcError.LogicError("Faucet error: Connection not established")
    (mockJsonRpcController.handleRequest _)
      .expects(*)
      .returning(
        IO.pure(
          JsonRpcResponse(
            jsonRpc,
            None,
            Some(JsonRpcError(code = jsonRpcError.code, message = jsonRpcError.message, data = None)),
            JInt(id)
          )
        )
      )

    val postRequest =
      HttpRequest(HttpMethods.POST, uri = "/", entity = HttpEntity(MediaTypes.`application/json`, jsonRequest))

    postRequest ~> Route.seal(mockJsonRpcHttpServer.route) ~> check {
      status shouldEqual StatusCodes.OK
      val jsonRpcResponse = JsonRpcHttpServerSpec.parser(responseAs[String])
      jsonRpcResponse.error shouldEqual Some(jsonRpcError)
      jsonRpcResponse.jsonrpc shouldEqual jsonRpc
      jsonRpcResponse.id shouldEqual JInt(id)
    }
  }

  it should "return status code BadRequest when request invalid is received" taggedAs (UnitTest, RPCTest) in new TestSetup {
    (mockJsonRpcController.handleRequest _)
      .expects(*)
      .returning(
        IO.pure(
          JsonRpcResponse(
            jsonRpc,
            None,
            Some(
              JsonRpcError(
                code = JsonRpcError.InvalidRequest.code,
                message = JsonRpcError.InvalidRequest.message,
                data = None
              )
            ),
            JInt(id)
          )
        )
      )

    val postRequest =
      HttpRequest(HttpMethods.POST, uri = "/", entity = HttpEntity(MediaTypes.`application/json`, jsonRequest))

    postRequest ~> Route.seal(mockJsonRpcHttpServer.route) ~> check {
      status shouldEqual StatusCodes.BadRequest
      val jsonRpcResponse = JsonRpcHttpServerSpec.parser(responseAs[String])
      jsonRpcResponse.error shouldEqual Some(JsonRpcError.InvalidRequest)
      jsonRpcResponse.jsonrpc shouldEqual jsonRpc
      jsonRpcResponse.id shouldEqual JInt(id)
    }
  }

  it should "return status code BadRequest when parser request failure" taggedAs (UnitTest, RPCTest) in new TestSetup {
    (mockJsonRpcController.handleRequest _)
      .expects(*)
      .returning(
        IO.pure(
          JsonRpcResponse(
            jsonRpc,
            None,
            Some(
              JsonRpcError(code = JsonRpcError.ParseError.code, message = JsonRpcError.ParseError.message, data = None)
            ),
            JInt(id)
          )
        )
      )

    val postRequest =
      HttpRequest(HttpMethods.POST, uri = "/", entity = HttpEntity(MediaTypes.`application/json`, jsonRequest))

    postRequest ~> Route.seal(mockJsonRpcHttpServer.route) ~> check {
      status shouldEqual StatusCodes.BadRequest
      val jsonRpcResponse = JsonRpcHttpServerSpec.parser(responseAs[String])
      jsonRpcResponse.error shouldEqual Some(JsonRpcError.ParseError)
      jsonRpcResponse.jsonrpc shouldEqual jsonRpc
      jsonRpcResponse.id shouldEqual JInt(id)
    }
  }

  it should "return status code BadRequest when the request has invalid params" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val error = JsonRpcError.InvalidParams()
    (mockJsonRpcController.handleRequest _)
      .expects(*)
      .returning(
        IO.pure(
          JsonRpcResponse(
            jsonRpc,
            None,
            Some(JsonRpcError(code = error.code, message = error.message, data = None)),
            JInt(id)
          )
        )
      )

    val postRequest =
      HttpRequest(HttpMethods.POST, uri = "/", entity = HttpEntity(MediaTypes.`application/json`, jsonRequest))

    postRequest ~> Route.seal(mockJsonRpcHttpServer.route) ~> check {
      status shouldEqual StatusCodes.BadRequest
      val jsonRpcResponse = parser(responseAs[String])
      jsonRpcResponse.error shouldEqual Some(error)
      jsonRpcResponse.jsonrpc shouldEqual jsonRpc
      jsonRpcResponse.id shouldEqual JInt(id)
    }
  }

  // SCALA 3 MIGRATION: TestSetup takes implicit MockFactory parameter to properly capture mock context.
  // This follows the pattern used in JsonRpcControllerFixture for Scala 3 compatibility.
  // We use mockFactory.mock explicitly to avoid ambiguity with inherited mock method.
  class TestSetup(implicit mockFactory: org.scalamock.scalatest.MockFactory) {

    val jsonRpc = "2.0"
    val id = 1
    val jsonRequest: ByteString = ByteString(s"""{"jsonrpc":"$jsonRpc", "method": "eth_blockNumber", "id": "$id"}""")
    val resultSuccessful: JString = JString("this is a response")
    val jsonRpcResponseSuccessful: JsonRpcResponse = JsonRpcResponse(jsonRpc, Some(resultSuccessful), None, JInt(id))

    val rateLimitConfig: RateLimitConfig = new RateLimitConfig {
      override val enabled: Boolean = false
      override val minRequestInterval: FiniteDuration = FiniteDuration.apply(20, TimeUnit.MILLISECONDS)
      override val latestTimestampCacheSize: Int = 1024
    }

    val serverConfig: JsonRpcHttpServerConfig = new JsonRpcHttpServerConfig {
      override val mode: String = "mockJsonRpc"
      override val enabled: Boolean = true
      override val interface: String = ""
      override val port: Int = 123
      override val corsAllowedOrigins = HttpOriginMatcher.*
      override val rateLimit: RateLimitConfig = rateLimitConfig
    }

    val rateLimitEnabledConfig: RateLimitConfig = new RateLimitConfig {
      override val enabled: Boolean = true
      override val minRequestInterval: FiniteDuration = FiniteDuration.apply(20, TimeUnit.MILLISECONDS)
      override val latestTimestampCacheSize: Int = 1024
    }

    val serverConfigWithRateLimit: JsonRpcHttpServerConfig = new JsonRpcHttpServerConfig {
      override val mode: String = "mockJsonRpc"
      override val enabled: Boolean = true
      override val interface: String = ""
      override val port: Int = 123
      override val corsAllowedOrigins = HttpOriginMatcher.*
      override val rateLimit: RateLimitConfig = rateLimitEnabledConfig
    }

    // Mock the MockableJsonRpcController which satisfies JsonRpcBaseController's self-type constraints
    val mockJsonRpcController: MockableJsonRpcController = mockFactory.mock[MockableJsonRpcController]
    val mockJsonRpcHealthChecker: JsonRpcHealthChecker = mockFactory.mock[JsonRpcHealthChecker]

    val mockJsonRpcHttpServer = new FakeJsonRpcHttpServer(
      jsonRpcController = mockJsonRpcController,
      jsonRpcHealthChecker = mockJsonRpcHealthChecker,
      config = serverConfig,
      cors = serverConfig.corsAllowedOrigins
    )

    val corsAllowedOrigin: HttpOrigin = HttpOrigin("http://localhost:3333")
    val mockJsonRpcHttpServerWithCors = new FakeJsonRpcHttpServer(
      jsonRpcController = mockJsonRpcController,
      jsonRpcHealthChecker = mockJsonRpcHealthChecker,
      config = serverConfig,
      cors = HttpOriginMatcher(corsAllowedOrigin)
    )

    val mockJsonRpcHttpServerWithRateLimit = new FakeJsonRpcHttpServer(
      jsonRpcController = mockJsonRpcController,
      jsonRpcHealthChecker = mockJsonRpcHealthChecker,
      config = serverConfigWithRateLimit,
      cors = serverConfigWithRateLimit.corsAllowedOrigins
    )
  }
}

object JsonRpcHttpServerSpec extends JsonMethodsImplicits {
  import org.json4s._
  import org.json4s.native.JsonMethods._

  private def parserJsonRpcError(jsonRpcError: JValue): JsonRpcError = {
    val code = (jsonRpcError \ "code").extract[Int]
    val message = (jsonRpcError \ "message").extract[String]
    val data = (jsonRpcError \ "data").toOption.map(_.extract[JValue])
    JsonRpcError(code = code, message = message, data = data)
  }

  def parser(jsonRpcResponse: String): JsonRpcResponse = {
    val jsValue = parse(jsonRpcResponse)
    val jsonRpc = (jsValue \ "jsonrpc").extract[String]
    val result = (jsValue \ "result").toOption.map(_.extract[JValue])
    val error = (jsValue \ "error").toOption.map(parserJsonRpcError)
    val id = (jsValue \ "id").extract[JValue]
    JsonRpcResponse(jsonrpc = jsonRpc, result = result, error = error, id = id)
  }

}

class FakeJsonRpcHttpServer(
    val jsonRpcController: JsonRpcBaseController,
    val jsonRpcHealthChecker: JsonRpcHealthChecker,
    val config: JsonRpcHttpServerConfig,
    val cors: HttpOriginMatcher
)(implicit val actorSystem: ActorSystem)
    extends JsonRpcHttpServer
    with Logger {
  def run(): Unit = ()
  override def corsAllowedOrigins: HttpOriginMatcher = cors

  var mockedTime: Long = 0L

  override protected val rateLimit: RateLimit = new RateLimit(config.rateLimit) {
    override protected def getCurrentTimeNanos: Long = FakeJsonRpcHttpServer.this.mockedTime
  }

}
