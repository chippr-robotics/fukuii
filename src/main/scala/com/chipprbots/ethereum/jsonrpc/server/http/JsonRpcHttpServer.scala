package com.chipprbots.ethereum.jsonrpc.server.http

import javax.net.ssl.SSLContext

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.cors.javadsl.CorsRejection
import org.apache.pekko.http.cors.scaladsl.CorsDirectives._
import org.apache.pekko.http.cors.scaladsl.model.HttpOriginMatcher
import org.apache.pekko.http.cors.scaladsl.settings.CorsSettings
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server._

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.syntax.all._

import scala.concurrent.duration._

import com.typesafe.config.{Config => TypesafeConfig}
import org.json4s.DefaultFormats
import org.json4s.Formats
import org.json4s.JInt
import org.json4s.native
import org.json4s.native.Serialization

import com.chipprbots.ethereum.faucet.jsonrpc.FaucetJsonRpcController
import com.chipprbots.ethereum.healthcheck.HealthcheckResponse
import com.chipprbots.ethereum.healthcheck.HealthcheckResult
import com.chipprbots.ethereum.jsonrpc._
import com.chipprbots.ethereum.jsonrpc.serialization.JsonSerializers
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcHttpServer.JsonRpcHttpServerConfig
import com.chipprbots.ethereum.security.SSLError
import com.chipprbots.ethereum.utils.BuildInfo
import com.chipprbots.ethereum.utils.ConfigUtils
import com.chipprbots.ethereum.utils.Logger

trait JsonRpcHttpServer extends Json4sSupport with Logger {
  val jsonRpcController: JsonRpcBaseController
  val jsonRpcHealthChecker: JsonRpcHealthChecker
  val config: JsonRpcHttpServerConfig

  implicit val runtime: IORuntime = IORuntime.global
  implicit val serialization: Serialization.type = native.Serialization

  implicit val formats: Formats = DefaultFormats + JsonSerializers.RpcErrorJsonSerializer

  def corsAllowedOrigins: HttpOriginMatcher

  lazy val jsonRpcErrorCodes: List[Int] =
    List(JsonRpcError.InvalidRequest.code, JsonRpcError.ParseError.code, JsonRpcError.InvalidParams().code)

  val corsSettings: CorsSettings = (CorsSettings.defaultSettings: @annotation.nowarn("cat=deprecation"))
    .withAllowGenericHttpRequests(true)
    .withAllowedOrigins(corsAllowedOrigins)

  implicit def myRejectionHandler: RejectionHandler =
    RejectionHandler
      .newBuilder()
      .handle {
        case _: MalformedRequestContentRejection =>
          complete((StatusCodes.BadRequest, JsonRpcResponse("2.0", None, Some(JsonRpcError.ParseError), JInt(0))))
        case _: CorsRejection =>
          complete(StatusCodes.Forbidden)
      }
      .result()

  protected val rateLimit = new RateLimit(config.rateLimit)

  val route: Route = cors(corsSettings) {
    (path("health") & pathEndOrSingleSlash & get) {
      handleHealth()
    } ~ (path("readiness") & pathEndOrSingleSlash & get) {
      handleReadiness()
    } ~ (path("healthcheck") & pathEndOrSingleSlash & get) {
      handleHealthcheck()
    } ~ (path("buildinfo") & pathEndOrSingleSlash & get) {
      handleBuildInfo()
    } ~ (pathEndOrSingleSlash & post) {
      // TODO: maybe rate-limit this one too?
      entity(as[JsonRpcRequest]) {
        case statusReq if statusReq.method == FaucetJsonRpcController.Status =>
          handleRequest(statusReq)
        case jsonReq =>
          rateLimit {
            handleRequest(jsonReq)
          }
        // TODO: separate paths for single and multiple requests
        // TODO: to prevent repeated body and json parsing
      } ~ entity(as[Seq[JsonRpcRequest]]) {
        case _ if config.rateLimit.enabled =>
          complete(StatusCodes.MethodNotAllowed, JsonRpcError.MethodNotFound)
        case reqSeq =>
          complete {
            reqSeq.toList
              .traverse(request => jsonRpcController.handleRequest(request))
              .unsafeToFuture()
          }
      }
    }
  }

  def handleRequest(request: JsonRpcRequest): StandardRoute =
    complete(handleResponse(jsonRpcController.handleRequest(request)).unsafeToFuture())

  private def handleResponse(f: IO[JsonRpcResponse]): IO[(StatusCode, JsonRpcResponse)] = f.map { jsonRpcResponse =>
    jsonRpcResponse.error match {
      case Some(JsonRpcError(error, _, _)) if jsonRpcErrorCodes.contains(error) =>
        (StatusCodes.BadRequest, jsonRpcResponse)
      case _ => (StatusCodes.OK, jsonRpcResponse)
    }
  }

  /** Try to start JSON RPC server
    */
  def run(): Unit

  private def handleHealth(): StandardRoute = {
    // Simple liveness check - if server responds, it's alive
    val healthResponse = HealthcheckResponse(
      List(
        HealthcheckResult.ok("server", Some("running"))
      )
    )
    complete(
      HttpResponse(
        status = StatusCodes.OK,
        entity = HttpEntity(ContentTypes.`application/json`, serialization.writePretty(healthResponse))
      )
    )
  }

  private def handleReadiness(): StandardRoute = {
    val responseF = jsonRpcHealthChecker.readinessCheck()
    val httpResponseF =
      responseF.map {
        case response if response.isOK =>
          HttpResponse(
            status = StatusCodes.OK,
            entity = HttpEntity(ContentTypes.`application/json`, serialization.writePretty(response))
          )
        case response =>
          HttpResponse(
            status = StatusCodes.ServiceUnavailable,
            entity = HttpEntity(ContentTypes.`application/json`, serialization.writePretty(response))
          )
      }
    complete(httpResponseF.unsafeToFuture()(runtime))
  }

  private def handleHealthcheck(): StandardRoute = {
    val responseF = jsonRpcHealthChecker.healthCheck()
    val httpResponseF =
      responseF.map {
        case response if response.isOK =>
          HttpResponse(
            status = StatusCodes.OK,
            entity = HttpEntity(ContentTypes.`application/json`, serialization.writePretty(response))
          )
        case response =>
          HttpResponse(
            status = StatusCodes.InternalServerError,
            entity = HttpEntity(ContentTypes.`application/json`, serialization.writePretty(response))
          )
      }
    complete(httpResponseF.unsafeToFuture()(runtime))
  }

  private def handleBuildInfo(): StandardRoute = {
    val buildInfo = Serialization.writePretty(BuildInfo.toMap)(DefaultFormats)
    complete(
      HttpResponse(
        status = StatusCodes.OK,
        entity = HttpEntity(ContentTypes.`application/json`, buildInfo)
      )
    )
  }

}

object JsonRpcHttpServer extends Logger {

  def apply(
      jsonRpcController: JsonRpcBaseController,
      jsonRpcHealthchecker: JsonRpcHealthChecker,
      config: JsonRpcHttpServerConfig,
      fSslContext: () => Either[SSLError, SSLContext]
  )(implicit actorSystem: ActorSystem): Either[String, JsonRpcHttpServer] =
    config.mode match {
      case "http" => Right(new InsecureJsonRpcHttpServer(jsonRpcController, jsonRpcHealthchecker, config)(actorSystem))
      case "https" =>
        Right(
          new SecureJsonRpcHttpServer(jsonRpcController, jsonRpcHealthchecker, config, fSslContext)(
            actorSystem
          )
        )
      case _ => Left(s"Cannot start JSON RPC server: Invalid mode ${config.mode} selected")
    }

  trait RateLimitConfig {
    // TODO: Move the rateLimit.enabled setting upwards:
    // TODO: If we don't need to limit the request rate at all - we don't have to define the other settings
    val enabled: Boolean
    val minRequestInterval: FiniteDuration
    val latestTimestampCacheSize: Int
  }

  object RateLimitConfig {
    // TODO: Use pureconfig
    def apply(rateLimitConfig: TypesafeConfig): RateLimitConfig =
      new RateLimitConfig {
        override val enabled: Boolean = rateLimitConfig.getBoolean("enabled")
        override val minRequestInterval: FiniteDuration =
          rateLimitConfig.getDuration("min-request-interval").toMillis.millis
        override val latestTimestampCacheSize: Int = rateLimitConfig.getInt("latest-timestamp-cache-size")
      }
  }

  trait JsonRpcHttpServerConfig {
    val mode: String
    val enabled: Boolean
    val interface: String
    val port: Int
    val corsAllowedOrigins: HttpOriginMatcher
    val rateLimit: RateLimitConfig
  }

  object JsonRpcHttpServerConfig {
    def apply(fukuiiConfig: TypesafeConfig): JsonRpcHttpServerConfig = {
      val rpcHttpConfig = fukuiiConfig.getConfig("network.rpc.http")

      new JsonRpcHttpServerConfig {
        override val mode: String = rpcHttpConfig.getString("mode")
        override val enabled: Boolean = rpcHttpConfig.getBoolean("enabled")
        override val interface: String = rpcHttpConfig.getString("interface")
        override val port: Int = rpcHttpConfig.getInt("port")

        override val corsAllowedOrigins: HttpOriginMatcher =
          ConfigUtils.parseCorsAllowedOrigins(rpcHttpConfig, "cors-allowed-origins")

        override val rateLimit: RateLimitConfig = RateLimitConfig(rpcHttpConfig.getConfig("rate-limit"))
      }
    }
  }
}
