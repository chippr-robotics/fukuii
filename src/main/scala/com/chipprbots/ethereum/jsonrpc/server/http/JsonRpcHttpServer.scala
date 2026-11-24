package com.chipprbots.ethereum.jsonrpc.server.http

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.cors.javadsl.CorsRejection
import org.apache.pekko.http.cors.scaladsl.CorsDirectives.*
import org.apache.pekko.http.cors.scaladsl.model.HttpOriginMatcher
import org.apache.pekko.http.cors.scaladsl.settings.CorsSettings
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.server.*
import org.apache.pekko.http.scaladsl.server.Directives.*

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.syntax.all.*

import scala.concurrent.Future
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
import com.chipprbots.ethereum.tls.IdentityRules
import com.chipprbots.ethereum.tls.TlsConfigError
import com.chipprbots.ethereum.tls.TlsServerContext
import com.chipprbots.ethereum.utils.BuildInfo
import com.chipprbots.ethereum.utils.ConfigUtils
import com.chipprbots.ethereum.utils.Logger

trait JsonRpcHttpServer extends Json4sSupport with Logger:
  val jsonRpcController: JsonRpcBaseController
  val jsonRpcHealthChecker: JsonRpcHealthChecker
  val config: JsonRpcHttpServerConfig

  /** Optional GraphQL endpoint, mounted at `POST /graphql` when defined. */
  val graphQLService: Option[GraphQLService] = None

  /** Who is served by which controller (spec 030 FR-020–FR-028). By default every request is served by
    * [[jsonRpcController]]; a listener that asks for client certificates resolves each caller from its TLS session.
    */
  def callers: RpcCallers = RpcCallers.none(jsonRpcController)

  /** The caller of this request, from the TLS session Pekko puts on it (`ssl-session-attribute`, research D-2). */
  private def caller: Directive1[RpcCaller] =
    optionalAttribute(AttributeKeys.sslSession).map(info => callers.forSession(info.map(_.session)))

  private val graphQlDisabled: HttpResponse =
    HttpResponse(
      status = StatusCodes.NotFound,
      entity = HttpEntity(ContentTypes.`application/json`, """{"errors":[{"message":"graphql disabled"}]}""")
    )

  given runtime: IORuntime = IORuntime.global
  given serialization: Serialization.type = native.Serialization

  given formats: Formats = DefaultFormats + JsonSerializers.RpcErrorJsonSerializer

  def corsAllowedOrigins: HttpOriginMatcher

  val corsSettings: CorsSettings = (CorsSettings.defaultSettings: @annotation.nowarn("cat=deprecation"))
    .withAllowGenericHttpRequests(true)
    .withAllowedOrigins(corsAllowedOrigins)

  implicit def myRejectionHandler: RejectionHandler =
    RejectionHandler
      .newBuilder()
      .handle {
        // A body that is not JSON-RPC is answered with the -32700 error in a 200 response, as go-ethereum's HTTP server
        // answers every JSON-RPC error (rpc/http.go); the status says only whether HTTP itself failed (B-025).
        case _: MalformedRequestContentRejection =>
          complete((StatusCodes.OK, JsonRpcResponse("2.0", None, Some(JsonRpcError.ParseError), JInt(0))))
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
        (post & caller) { who =>
          graphQLService match
            case Some(svc) if who.graphQl =>
              extractStrictEntity(5.seconds) { entity =>
                val body = entity.data.utf8String
                handleGraphQL(svc, body)
              }
            case Some(_) =>
              // A caller whose rule does not list graphql gets what a node without GraphQL answers (FR-028).
              who.label.foreach(l => RpcNamespaceDenials.record(l, IdentityRules.GraphQl, "POST /graphql", "http"))
              complete(graphQlDisabled)
            case None =>
              complete(graphQlDisabled)
        }
      } ~ (pathEndOrSingleSlash & post & caller) { who =>
        val controller = who.controller
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
              handleRequest(controller, statusReq)
            case jsonReq =>
              rateLimit {
                handleRequest(controller, jsonReq)
              }
          } ~ entity(as[Seq[JsonRpcRequest]]) {
            case _ if config.rateLimit.enabled =>
              complete(StatusCodes.MethodNotAllowed, JsonRpcError.MethodNotFound)
            case reqSeq =>
              // each element is answered on its own, by the caller's controller (US3 scenario 3)
              complete {
                reqSeq.toList
                  .traverse(request => controller.handleRequest(request))
                  .unsafeToFuture()
              }
          }
        }
      }
    }
  }

  def handleRequest(request: JsonRpcRequest): StandardRoute = handleRequest(jsonRpcController, request)

  private def handleRequest(controller: JsonRpcBaseController, request: JsonRpcRequest): StandardRoute =
    complete(handleResponse(controller.handleRequest(request)).unsafeToFuture())

  // Every JSON-RPC answer, error or not, is a 200: the error is in the body (go-ethereum rpc/http.go). Parse errors,
  // invalid requests and invalid params answered 400 here before (B-025).
  private def handleResponse(f: IO[JsonRpcResponse]): IO[(StatusCode, JsonRpcResponse)] =
    f.map(jsonRpcResponse => (StatusCodes.OK, jsonRpcResponse))

  /** The actor system the server binds in. */
  def actorSystem: ActorSystem

  /** Where the dashboard's RPC view is told whether the server is bound or was stopped; the node's own, and a test's
    * own where a test does not want to touch it.
    */
  protected def callStats: RpcCallStats = RpcCallStats.Node

  /** How long `stop` gives requests in flight before it closes every connection. */
  protected def stopDeadline: FiniteDuration = ServerLifecycle.DefaultHardDeadline

  /** One bind of [[route]] on `config.interface:config.port`. It makes the binding and nothing else: the bound or
    * stopped state is kept, and said to [[callStats]], by [[lifecycle]]. A server that cannot bind (a bad TLS context,
    * an address in use) answers with a failed future, not a throw. A route-only test double that is never bound keeps
    * this default, which says so.
    */
  protected def bind(): Future[Http.ServerBinding] =
    Future.failed(new UnsupportedOperationException("this server is not bound by a real bind"))

  /** The binding, kept: what stops this server and starts it again (`stop`, `start`), and whether it is bound
    * (`isBound`, `boundAddress`). Spec 029 FR-052.
    */
  lazy val lifecycle: ServerLifecycle =
    new ServerLifecycle(RpcCallStats.Http, () => bind(), callStats, stopDeadline)(using actorSystem.dispatcher)

  /** Whether the server speaks TLS (`mode = https`), for the dashboard's previews. */
  def isTls: Boolean = config.tlsEnabled

  /** Starts the server and returns the binding future: `lifecycle.start()` in the servers that bind.
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
    complete(httpResponseF.unsafeToFuture()(using runtime))

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
    complete(httpResponseF.unsafeToFuture()(using runtime))

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
    val buildInfo = Serialization.writePretty(BuildInfo.toMap)(using DefaultFormats)
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
      fTlsContext: () => Either[TlsConfigError, TlsServerContext],
      graphQLService: Option[GraphQLService] = None,
      tlsCallers: Option[RpcCallers] = None
  )(implicit actorSystem: ActorSystem): Either[String, JsonRpcHttpServer] =
    config.mode match
      // `tls { enabled = true }` makes the server secure under either mode value; `mode = "https"` is the deprecated
      // form of the same (spec 030 FR-007), and the start-up check refuses the two together.
      case "http" | "https" if config.tlsEnabled =>
        Right(
          new SecureJsonRpcHttpServer(
            jsonRpcController,
            jsonRpcHealthchecker,
            config,
            fTlsContext,
            graphQLService,
            tlsCallers
          )(using actorSystem)
        )
      case "http" =>
        Right(
          new InsecureJsonRpcHttpServer(jsonRpcController, jsonRpcHealthchecker, config, graphQLService)(using
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

    /** `network.rpc.http.tls.enabled` (spec 030); false where a configuration does not carry the block. */
    val tlsBlockEnabled: Boolean = false

    /** The listener speaks TLS: from its `tls` block, or from the deprecated `mode = "https"`. */
    def tlsEnabled: Boolean = tlsBlockEnabled || mode == "https"
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
        override val tlsBlockEnabled: Boolean =
          rpcHttpConfig.hasPath("tls.enabled") && rpcHttpConfig.getBoolean("tls.enabled")

        override val corsAllowedOrigins: HttpOriginMatcher =
          ConfigUtils.parseCorsAllowedOrigins(rpcHttpConfig, "cors-allowed-origins")

        override val rateLimit: RateLimitConfig = RateLimitConfig(rpcHttpConfig.getConfig("rate-limit"))
