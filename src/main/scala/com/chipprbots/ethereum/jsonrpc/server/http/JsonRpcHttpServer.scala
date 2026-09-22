package com.chipprbots.ethereum.jsonrpc.server.http

import javax.net.ssl.SSLContext

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.cors.javadsl.CorsRejection
import org.apache.pekko.http.cors.scaladsl.CorsDirectives.*
import org.apache.pekko.http.cors.scaladsl.model.HttpOriginMatcher
import org.apache.pekko.http.cors.scaladsl.settings.CorsSettings
import scala.concurrent.Future

import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.server.*
import org.apache.pekko.http.scaladsl.server.Directives.*

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.syntax.all.*

import scala.concurrent.duration.*

import com.typesafe.config.Config as TypesafeConfig
import org.json4s.DefaultFormats
import org.json4s.Formats
import org.json4s.JInt
import org.json4s.native
import org.json4s.native.Serialization

import com.chipprbots.ethereum.faucet.jsonrpc.FaucetJsonRpcController
import com.chipprbots.ethereum.healthcheck.HealthcheckResponse
import com.chipprbots.ethereum.healthcheck.HealthcheckResult
import com.chipprbots.ethereum.jsonrpc.*
import com.chipprbots.ethereum.jsonrpc.graphql.GraphQLService
import com.chipprbots.ethereum.jsonrpc.serialization.JsonSerializers
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcHttpServer.JsonRpcHttpServerConfig
import com.chipprbots.ethereum.security.SSLError
import com.chipprbots.ethereum.utils.BuildInfo
import com.chipprbots.ethereum.utils.ConfigUtils
import com.chipprbots.ethereum.utils.Logger

trait JsonRpcHttpServer extends Json4sSupport with Logger:
  val jsonRpcController: JsonRpcBaseController
  val jsonRpcHealthChecker: JsonRpcHealthChecker
  val config: JsonRpcHttpServerConfig

  /** Optional GraphQL endpoint, mounted at `POST /graphql` when defined. */
  val graphQLService: Option[GraphQLService] = None

  given runtime: IORuntime = IORuntime.global
  given serialization: Serialization.type = native.Serialization

  given formats: Formats = DefaultFormats + JsonSerializers.RpcErrorJsonSerializer

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

  val route: Route = handleRejections(myRejectionHandler) {
    cors(corsSettings) {
      (path("health") & pathEndOrSingleSlash & get) {
        handleHealth()
      } ~ (path("readiness") & pathEndOrSingleSlash & get) {
        handleReadiness()
      } ~ (path("healthcheck") & pathEndOrSingleSlash & get) {
        handleHealthcheck()
      } ~ (path("buildinfo") & pathEndOrSingleSlash & get) {
        handleBuildInfo()
      } ~ path("graphql") {
        post {
          graphQLService match
            case Some(svc) =>
              extractStrictEntity(5.seconds) { entity =>
                val body = entity.data.utf8String
                handleGraphQL(svc, body)
              }
            case None =>
              complete(
                HttpResponse(
                  status = StatusCodes.NotFound,
                  entity =
                    HttpEntity(ContentTypes.`application/json`, """{"errors":[{"message":"graphql disabled"}]}""")
                )
              )
        }
      } ~ (pathEndOrSingleSlash & post) {
        // Materialize the request entity BEFORE the two `entity(as[...])` alternatives below.
        //
        // Those alternatives are tried in order, and for a BATCH request (a JSON array) the
        // first one MUST fail — an array is not a single JsonRpcRequest. Pekko then tries the
        // second. That only works if the entity can be read twice. A streamed entity
        // (HttpEntity.Default / Chunked) is a one-shot source: the first failed unmarshal
        // drains it, the second alternative rejects too, and myRejectionHandler above turns
        // the resulting MalformedRequestContentRejection into
        //   400 {"jsonrpc":"2.0","error":{"code":-32700,...},"id":0}
        // for a batch that was perfectly well-formed.
        //
        // Whether a given batch arrives Strict or streamed depends on TCP arrival timing, so
        // the bug is intermittent and load-dependent: measured in hive's engine-withdrawals
        // suite failing after 1, 3, 5, 7, 11, 19 or 25 successful batches, or not at all
        // within 30, for the SAME test with the SAME payloads across two runs.
        //
        // toStrictEntity gives both alternatives a re-readable buffered entity. The GraphQL
        // branch above already does this with extractStrictEntity for the same reason.
        toStrictEntity(5.seconds) {
          entity(as[JsonRpcRequest]) {
            case statusReq if statusReq.method == FaucetJsonRpcController.Status =>
              handleRequest(statusReq)
            case jsonReq =>
              rateLimit {
                handleRequest(jsonReq)
              }
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
    }
  }

  def handleRequest(request: JsonRpcRequest): StandardRoute =
    complete(handleResponse(jsonRpcController.handleRequest(request)).unsafeToFuture())

  private def handleResponse(f: IO[JsonRpcResponse]): IO[(StatusCode, JsonRpcResponse)] = f.map { jsonRpcResponse =>
    jsonRpcResponse.error match
      case Some(JsonRpcError(error, _, _)) if jsonRpcErrorCodes.contains(error) =>
        (StatusCodes.BadRequest, jsonRpcResponse)
      case _ => (StatusCodes.OK, jsonRpcResponse)
  }

  /** Try to start JSON RPC server
    */
  /** Binds the HTTP server and returns the binding future.
    *
    * Returning the future rather than Unit is what lets a caller wait for the socket to be LISTENING rather than merely
    * for bind() to have been requested. StdNode does exactly that, so that "node started" means the JSON-RPC port
    * actually accepts connections. See StdNode.startJsonRpcHttpServer for why that matters to hive.
    */
  def run(): Future[Http.ServerBinding]

  private def handleHealth(): StandardRoute =
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

  private def handleReadiness(): StandardRoute =
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

  private def handleHealthcheck(): StandardRoute =
    val responseF = jsonRpcHealthChecker.healthCheck
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

  private def handleGraphQL(svc: GraphQLService, body: String): StandardRoute =
    GraphQLService.parseJsonBody(body) match
      case Left(msg) =>
        val escaped = msg.replace("\\", "\\\\").replace("\"", "\\\"")
        val errJson = s"""{"errors":[{"message":"$escaped"}]}"""
        complete(HttpResponse(StatusCodes.BadRequest, entity = HttpEntity(ContentTypes.`application/json`, errJson)))
      case Right(req) =>
        val fut = svc
          .execute(req.query, req.variables, req.operationName)
          .map { case (statusCode, json) =>
            val httpStatus = statusCode match
              case 200 => StatusCodes.OK
              case 400 => StatusCodes.BadRequest
              case 500 => StatusCodes.InternalServerError
              case _   => StatusCodes.OK
            HttpResponse(httpStatus, entity = HttpEntity(ContentTypes.`application/json`, json.noSpaces))
          }
          .unsafeToFuture()
        complete(fut)

  private def handleBuildInfo(): StandardRoute =
    val buildInfo = Serialization.writePretty(BuildInfo.toMap)(DefaultFormats)
    complete(
      HttpResponse(
        status = StatusCodes.OK,
        entity = HttpEntity(ContentTypes.`application/json`, buildInfo)
      )
    )

object JsonRpcHttpServer extends Logger:

  def apply(
      jsonRpcController: JsonRpcBaseController,
      jsonRpcHealthchecker: JsonRpcHealthChecker,
      config: JsonRpcHttpServerConfig,
      fSslContext: () => Either[SSLError, SSLContext],
      graphQLService: Option[GraphQLService] = None
  )(implicit actorSystem: ActorSystem): Either[String, JsonRpcHttpServer] =
    config.mode match
      case "http" =>
        Right(
          new InsecureJsonRpcHttpServer(jsonRpcController, jsonRpcHealthchecker, config, graphQLService)(actorSystem)
        )
      case "https" =>
        Right(
          new SecureJsonRpcHttpServer(jsonRpcController, jsonRpcHealthchecker, config, fSslContext, graphQLService)(
            actorSystem
          )
        )
      case _ => Left(s"Cannot start JSON RPC server: Invalid mode ${config.mode} selected")

  trait RateLimitConfig:
    val enabled: Boolean
    val minRequestInterval: FiniteDuration
    val latestTimestampCacheSize: Int

  object RateLimitConfig:
    def apply(rateLimitConfig: TypesafeConfig): RateLimitConfig =
      new RateLimitConfig:
        override val enabled: Boolean = rateLimitConfig.getBoolean("enabled")
        override val minRequestInterval: FiniteDuration =
          rateLimitConfig.getDuration("min-request-interval").toMillis.millis
        override val latestTimestampCacheSize: Int = rateLimitConfig.getInt("latest-timestamp-cache-size")

  trait JsonRpcHttpServerConfig:
    val mode: String
    val enabled: Boolean
    val interface: String
    val port: Int
    val corsAllowedOrigins: HttpOriginMatcher
    val rateLimit: RateLimitConfig

  object JsonRpcHttpServerConfig:
    def apply(fukuiiConfig: TypesafeConfig): JsonRpcHttpServerConfig =
      val rpcHttpConfig = fukuiiConfig.getConfig("network.rpc.http")

      new JsonRpcHttpServerConfig:
        override val mode: String = rpcHttpConfig.getString("mode")
        override val enabled: Boolean = rpcHttpConfig.getBoolean("enabled")
        override val interface: String = rpcHttpConfig.getString("interface")
        override val port: Int = rpcHttpConfig.getInt("port")

        override val corsAllowedOrigins: HttpOriginMatcher =
          ConfigUtils.parseCorsAllowedOrigins(rpcHttpConfig, "cors-allowed-origins")

        override val rateLimit: RateLimitConfig = RateLimitConfig(rpcHttpConfig.getConfig("rate-limit"))
