package com.chipprbots.ethereum.consensus.engine

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route

import cats.effect.unsafe.IORuntime

import org.json4s._
import org.json4s.native.JsonMethods._

import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.jsonrpc.JsonRpcResponse
import com.chipprbots.ethereum.jsonrpc.JsonRpcError
import com.chipprbots.ethereum.utils.Logger

/** Separate HTTP server for the Engine API on the authrpc port (default 8551).
  * JWT-authenticated, accepts only engine_* methods.
  */
class EngineApiHttpServer(
    controller: EngineApiController,
    jwtAuth: JwtAuthenticator,
    config: EngineApiHttpServer.Config
)(implicit system: ActorSystem)
    extends Logger {

  implicit val formats: Formats = DefaultFormats
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val ioRuntime: IORuntime = IORuntime.global

  private var bindingOpt: Option[Http.ServerBinding] = None

  val route: Route = {
    (pathEndOrSingleSlash & post) {
      jwtAuth.authenticate { _ =>
        entity(as[String]) { body =>
          val response = try {
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
  }

  private def processRequest(json: JValue): Future[JsonRpcResponse] = {
    try {
      val method = (json \ "method").extractOpt[String].getOrElse("unknown")
      log.warn(s"Engine API request: method=$method")
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
      log.info(s"Engine API server started on ${config.interface}:${config.port}")
    }
    bindFuture
  }

  def stop(): Future[Unit] = {
    bindingOpt match {
      case Some(binding) =>
        binding.unbind().map { _ =>
          bindingOpt = None
          log.info("Engine API server stopped")
        }
      case None =>
        Future.successful(())
    }
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
