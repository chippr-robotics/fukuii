package com.chipprbots.ethereum.consensus.engine

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.duration._

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route

import com.typesafe.config.{Config => TypesafeConfig, ConfigFactory}

import cats.effect.unsafe.IORuntime

import org.json4s._
import org.json4s.native.JsonMethods._

import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.jsonrpc.JsonRpcResponse
import com.chipprbots.ethereum.jsonrpc.JsonRpcError
import com.chipprbots.ethereum.utils.Logger

/** Separate HTTP server for the Engine API on the authrpc port (default 8551). JWT-authenticated, accepts only engine_*
  * methods.
  *
  * Runs on a **dedicated `ActorSystem`** (see #1209). Pekko HTTP's server-side TCP acceptor, per-connection actor, and
  * request parser all run on the system's `default-dispatcher`. If we share the main node's ActorSystem with this
  * server, the same dispatcher hosts every peer-handshake actor (`PeerActor`, RLPx framing, ETH69_STATUS exchange,
  * blacklisting). On Sepolia / mainnet bootstrap with 16+ simultaneous peer handshakes, the default-dispatcher
  * mailboxes deepen and Lighthouse's `engine_forkchoiceUpdated` POSTs can't reach the handler within its ~8s client
  * timeout — it intermittently stalls for minutes at a time.
  *
  * Pekko HTTP has no `pekko.http.server.dispatcher` config key, so this is the canonical isolation pattern (mirrors
  * go-ethereum's `n.httpAuth` separate `httpServer` and reth's `with_tokio_runtime()` for the auth RPC).
  */
class EngineApiHttpServer(
    controller: EngineApiController,
    jwtAuth: JwtAuthenticator,
    config: EngineApiHttpServer.Config
) extends Logger {

  // Dedicated ActorSystem isolated from the main node's peer-management dispatchers.
  // Loads `engine-api-system.conf` from the classpath which overrides only
  // `pekko.actor.default-dispatcher` and `pekko.http.server.*` — every other Pekko
  // setting (logging, mailboxes, etc.) is inherited from the main `application.conf`.
  private val systemConfig: TypesafeConfig =
    ConfigFactory.load("engine-api-system.conf").withFallback(ConfigFactory.load())
  implicit private val system: ActorSystem = ActorSystem("engine-api", systemConfig)
  implicit private val ec: ExecutionContext = system.dispatcher

  // Dedicated `IORuntime` for Engine API handlers (#1209 follow-up). The previous
  // `IORuntime.global` was shared across the entire JVM — every cats-effect IO
  // (sync coordinators, RegularSync, block import, MPT operations, etc.) competed
  // with Engine API handlers for the same compute pool. On Sepolia bootstrap with
  // 6+ peer handshakes/sec saturating the work-stealing pool's ~4 cores (cats-effect
  // default = `availableProcessors`), `engine_exchangeCapabilities` and
  // `engine_forkchoiceUpdated` requests queued behind sync work and lighthouse's
  // 8 s client timeout fired first — leaving the EL effectively offline.
  //
  // Hive's `ethereum/engine` Paris suite (65 tests) passes 100 % under controlled
  // load with the shared IORuntime, so the Engine API code path is correct; the
  // failure is purely resource contention. A dedicated runtime gives Engine API
  // handlers their own work-stealing pool, mirroring go-ethereum's `n.httpAuth`
  // separate goroutine pool and reth's `with_tokio_runtime()` auth-server isolation.
  //
  // 4 compute threads is sufficient — Lighthouse holds a single keep-alive
  // connection and pipelines at most a handful of in-flight requests. The blocking
  // pool stays default-sized because Engine API handlers don't perform blocking I/O
  // (everything is `IO.delay` over in-memory state or RocksDB which uses its own
  // thread pool).
  private val (engineCompute, shutdownCompute) =
    IORuntime.createWorkStealingComputeThreadPool(threads = 4, threadPrefix = "engine-api-compute")
  private val (engineBlocking, shutdownBlocking) =
    IORuntime.createDefaultBlockingExecutionContext(threadPrefix = "engine-api-blocking")
  private val (engineScheduler, shutdownScheduler) =
    IORuntime.createDefaultScheduler(threadPrefix = "engine-api-scheduler")
  implicit private val ioRuntime: IORuntime = IORuntime(
    compute = engineCompute,
    blocking = engineBlocking,
    scheduler = engineScheduler,
    shutdown = () => {
      shutdownScheduler()
      shutdownBlocking()
      shutdownCompute()
    },
    config = cats.effect.unsafe.IORuntimeConfig()
  )

  implicit private val formats: Formats = DefaultFormats

  private var bindingOpt: Option[Http.ServerBinding] = None

  val route: Route =
    (pathEndOrSingleSlash & post) {
      jwtAuth.authenticate { _ =>
        entity(as[String]) { body =>
          val response =
            try {
              val json = parse(body)
              json match {
                case JArray(requests) =>
                  // Batch request
                  val responses = requests.map { reqJson =>
                    processRequest(reqJson)
                  }
                  Future.sequence(responses).map { resps =>
                    val jsonStr = compact(render(JArray(resps.map(responseToJson).toList)))
                    HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, jsonStr))
                  }
                case obj: JObject =>
                  // Single request
                  processRequest(obj).map { resp =>
                    val jsonStr = compact(render(responseToJson(resp)))
                    HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, jsonStr))
                  }
                case _ =>
                  Future.successful(
                    HttpResponse(
                      StatusCodes.BadRequest,
                      entity = HttpEntity(ContentTypes.`application/json`, """{"error":"Invalid JSON"}""")
                    )
                  )
              }
            } catch {
              case e: Exception =>
                log.error(s"Engine API parse error: ${e.getMessage}")
                Future.successful(
                  HttpResponse(
                    StatusCodes.BadRequest,
                    entity = HttpEntity(ContentTypes.`application/json`, s"""{"error":"${e.getMessage}"}""")
                  )
                )
            }
          complete(response)
        }
      }
    }

  private def processRequest(json: JValue): Future[JsonRpcResponse] = {
    val method = (json \ "method").extractOpt[String].getOrElse("unknown")
    try {
      log.debug(s"Engine API request: method=$method")
      val request = JsonRpcRequest(
        jsonrpc = (json \ "jsonrpc").extractOpt[String].getOrElse("2.0"),
        method = (json \ "method").extract[String],
        params = (json \ "params") match {
          case arr: JArray => Some(arr)
          case _           => None
        },
        id = (json \ "id").extractOpt[JValue]
      )
      controller.handleRequest(request).unsafeToFuture().recover { case e: Exception =>
        log.error(s"Engine API handler error: ${e.getMessage}")
        JsonRpcResponse("2.0", None, Some(JsonRpcError.InternalError), request.id.getOrElse(JNull))
      }
    } catch {
      case e: Exception =>
        log.error(s"Engine API request decode error for method=$method: ${e.getMessage}", e)
        Future.successful(JsonRpcResponse("2.0", None, Some(JsonRpcError.InternalError), JNull))
    }
  }

  private def responseToJson(resp: JsonRpcResponse): JValue = {
    var fields: List[(String, JValue)] = List("jsonrpc" -> JString(resp.jsonrpc))
    resp.result.foreach(r => fields = fields :+ ("result" -> r))
    resp.error.foreach(e =>
      fields = fields :+ ("error" -> JObject(
        "code" -> JInt(e.code),
        "message" -> JString(e.message)
      ))
    )
    fields = fields :+ ("id" -> resp.id)
    JObject(fields)
  }

  def start(): Future[Http.ServerBinding] = {
    val bindFuture = Http().newServerAt(config.interface, config.port).bind(route)
    bindFuture.foreach { binding =>
      bindingOpt = Some(binding)
      log.info(
        s"Engine API server started on ${config.interface}:${config.port} " +
          s"(isolated ActorSystem '${system.name}', default-dispatcher='engine-api-dispatcher')"
      )
    }
    bindFuture
  }

  /** Unbinds the server and terminates the dedicated ActorSystem. Caller should `Await` if shutdown ordering matters
    * (e.g. before the main node's ActorSystem terminates).
    */
  def stop(): Future[Unit] = {
    val terminate = bindingOpt match {
      case Some(binding) =>
        binding.unbind().flatMap { _ =>
          bindingOpt = None
          log.info("Engine API server stopped — terminating isolated ActorSystem + IORuntime")
          system.terminate().map(_ => ())
        }
      case None =>
        // Server never started; still tear down the system to free resources.
        system.terminate().map(_ => ())
    }
    terminate.map { _ =>
      // Shut down the dedicated IORuntime pools after the ActorSystem is gone so any
      // in-flight handler IOs have already drained.
      ioRuntime.shutdown()
    }(scala.concurrent.ExecutionContext.parasitic)
  }

  /** Synchronous shutdown convenience for shutdown hooks that don't have an ec available. */
  def stopSync(timeout: FiniteDuration = 10.seconds): Unit =
    try Await.result(stop(), timeout)
    catch {
      case _: Exception => ()
    }
}

object EngineApiHttpServer {
  case class Config(
      enabled: Boolean = false,
      interface: String = "localhost",
      port: Int = 8551,
      jwtSecretPath: Option[String] = None
  )
}
