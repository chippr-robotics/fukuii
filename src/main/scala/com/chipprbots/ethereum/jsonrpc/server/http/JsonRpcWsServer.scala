package com.chipprbots.ethereum.jsonrpc.server.http

import java.util.UUID

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.ws.Message
import org.apache.pekko.http.scaladsl.model.ws.TextMessage
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.Timeout

import cats.effect.unsafe.IORuntime

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Failure
import scala.util.Success

import com.typesafe.config.ConfigValueType
import org.json4s.*
import org.json4s.native.JsonMethods.*
import org.json4s.native.Serialization

import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.jsonrpc.SubscriptionManager
import com.chipprbots.ethereum.jsonrpc.SubscriptionManager.*
import com.chipprbots.ethereum.jsonrpc.serialization.JsonSerializers
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController
import com.chipprbots.ethereum.utils.Logger

/** WebSocket JSON-RPC server.
  *
  * Besu reference: ethereum/api/.../websocket/WebSocketService.java — WS connection lifecycle
  * ethereum/api/.../websocket/WebSocketMessageHandler.java — message routing
  * ethereum/api/.../websocket/methods/EthSubscribe.java — eth_subscribe handler
  * ethereum/api/.../websocket/methods/EthUnsubscribe.java — eth_unsubscribe handler
  *
  * Besu uses Vert.x HTTP server + WebSocketHandler. We use Pekko HTTP's handleWebSocketMessages directive which maps
  * naturally to the same flow: incoming messages → route → outgoing messages.
  *
  * eth_subscribe / eth_unsubscribe are routed directly to SubscriptionManager. All other JSON-RPC methods are forwarded
  * to jsonRpcController.handleRequest().
  *
  * Browser connections are accepted only from allowed origins: the `Origin` header is checked against
  * `fukuii.network.rpc.ws.allowed-origins` before the upgrade (see [[WsOriginPolicy]]). The key is optional; when it is
  * absent or empty, pages served from this machine (`localhost`, `127.0.0.1`, `[::1]`, any port) are allowed. Set it to
  * a list of origins, or a comma-separated string, to allow other pages; `*` allows every origin. Clients that send no
  * `Origin` header (CLIs, libraries) are not affected.
  */
class JsonRpcWsServer(
    jsonRpcController: JsonRpcBaseController,
    subscriptionManager: ActorRef[SubscriptionManager.Command],
    config: JsonRpcWsServer.JsonRpcWsServerConfig
)(implicit system: ActorSystem)
    extends Logger:

  given mat: Materializer = Materializer(system)
  given ec: ExecutionContext = system.dispatcher
  given timeout: Timeout = Timeout(30.seconds)
  given scheduler: Scheduler = system.toTyped.scheduler
  given runtime: IORuntime = IORuntime.global
  given formats: Formats = DefaultFormats + JsonSerializers.RpcErrorJsonSerializer
  given serialization: Serialization.type = org.json4s.native.Serialization

  private val originPolicy: WsOriginPolicy = WsOriginPolicy(config.allowedOrigins)

  /** The upgrade is refused with 403 when the request carries an `Origin` header that is not on the allow-list; the
    * connection is not registered with the SubscriptionManager. A request with no `Origin` header is not from a browser
    * and goes through. See [[WsOriginPolicy]]. A request that is not a WebSocket upgrade is answered with 400 and
    * registers nothing: [[buildWsFlow]] registers the connection, so it runs only once the upgrade is in hand.
    */
  val route: Route =
    (pathEndOrSingleSlash | path("ws")) {
      extractRequest { request =>
        val origins = request.headers.filter(_.is("origin")).map(_.value)
        if originPolicy.permits(origins) then
          extractWebSocketUpgrade { upgrade =>
            complete(upgrade.handleMessages(buildWsFlow()))
          }
        else
          log.warn(
            s"Rejected WebSocket connection: origin not allowed: ${origins.map(WsOriginPolicy.sanitize).mkString(", ")}"
          )
          complete(StatusCodes.Forbidden, "Origin not allowed")
      }
    }

  def run(): Unit =
    val bindingF = Http(system).newServerAt(config.interface, config.port).bind(route)
    bindingF.onComplete {
      case Success(b)  => log.info(s"JSON RPC WS server listening on ${b.localAddress}")
      case Failure(ex) => log.error("Cannot start JSON RPC WS server", ex)
    }

  private def buildWsFlow(): Flow[Message, Message, Any] =
    val connId = UUID.randomUUID().toString

    // Outbound queue: SubscriptionManager pushes JSON strings here
    val (queue, outboundSource) = Source
      .queue[String](256, OverflowStrategy.dropHead)
      .preMaterialize()

    subscriptionManager ! RegisterConnection(connId, queue)

    // Inbound sink: parse JSON-RPC, route, push response to queue
    val inboundSink: Sink[Message, Any] = Sink.foreach[Message] {
      case TextMessage.Strict(text) =>
        handleWsMessage(text, connId, queue)
      case TextMessage.Streamed(stream) =>
        stream.runFold("")(_ + _).foreach(text => handleWsMessage(text, connId, queue))
      case _ => ()
    }

    val outboundMessages = outboundSource.map(TextMessage.Strict(_))

    Flow
      .fromSinkAndSourceCoupled(inboundSink, outboundMessages)
      .watchTermination() { (mat, doneFuture) =>
        doneFuture.onComplete(_ => subscriptionManager ! ConnectionClosed(connId))
        mat
      }

  private def handleWsMessage(
      text: String,
      connId: String,
      queue: org.apache.pekko.stream.scaladsl.SourceQueueWithComplete[String]
  ): Unit =
    def sendResponse(json: String): Unit = queue.offer(json)

    def errorResponse(id: JValue, code: Int, message: String): String =
      val resp = JObject(
        "jsonrpc" -> JString("2.0"),
        "id" -> id,
        "error" -> JObject("code" -> JInt(code), "message" -> JString(message))
      )
      compact(render(resp))

    val parsed =
      try Some(parse(text))
      catch case _: Exception => None
    parsed match
      case None =>
        sendResponse(errorResponse(JNull, -32700, "Parse error"))

      case Some(json) =>
        val id = (json \ "id").toOption.getOrElse(JNull)
        val method = (json \ "method") match
          case JString(m) => m
          case _          => ""
        val params = (json \ "params").toOption

        method match
          case "eth_subscribe" =>
            val subType = params.flatMap {
              case JArray(JString(t) :: _) => Some(t)
              case _                       => None
            }
            val subParams = params.flatMap {
              case JArray(_ :: p :: _) => Some(p)
              case _                   => None
            }
            subType match
              case None =>
                sendResponse(errorResponse(id, -32602, "Invalid params: missing subscription type"))
              case Some(t) =>
                subscriptionManager
                  .ask[SubscribeResponse](replyTo => Subscribe(connId, t, subParams, replyTo))
                  .foreach {
                    case SubscribeResponse(Right(subId)) =>
                      val hex = "0x" + subId.toHexString
                      sendResponse(
                        compact(
                          render(
                            JObject(
                              "jsonrpc" -> JString("2.0"),
                              "id" -> id,
                              "result" -> JString(hex)
                            )
                          )
                        )
                      )
                    case SubscribeResponse(Left(err)) =>
                      sendResponse(errorResponse(id, -32602, err))
                  }

          case "eth_unsubscribe" =>
            val subIdOpt = params.flatMap {
              case JArray(JString(s) :: _) =>
                val hex = s.stripPrefix("0x").stripPrefix("0X")
                try Some(java.lang.Long.parseLong(hex, 16))
                catch case _: Exception => None
              case _ => None
            }
            subIdOpt match
              case None =>
                sendResponse(errorResponse(id, -32602, "Invalid params: missing subscription id"))
              case Some(subId) =>
                subscriptionManager.ask[UnsubscribeResponse](replyTo => Unsubscribe(connId, subId, replyTo)).foreach {
                  case UnsubscribeResponse(found) =>
                    sendResponse(
                      compact(
                        render(
                          JObject(
                            "jsonrpc" -> JString("2.0"),
                            "id" -> id,
                            "result" -> JBool(found)
                          )
                        )
                      )
                    )
                }

          case "" =>
            sendResponse(errorResponse(id, -32600, "Invalid request"))

          case _ =>
            // All other JSON-RPC methods: delegate to standard controller
            val requestOpt =
              try Some(json.extract[JsonRpcRequest])
              catch case _: Exception => None
            requestOpt match
              case None =>
                sendResponse(errorResponse(id, -32600, "Invalid request"))
              case Some(req) =>
                jsonRpcController.handleRequest(req).unsafeToFuture().foreach { resp =>
                  sendResponse(Serialization.write(resp))
                }

object JsonRpcWsServer:

  trait JsonRpcWsServerConfig:
    val enabled: Boolean
    val interface: String
    val port: Int

    /** Origins that may open a WebSocket connection from a browser (`fukuii.network.rpc.ws.allowed-origins`). Empty
      * means the default of [[WsOriginPolicy.DefaultAllowedOrigins]]; `*` allows every origin.
      */
    val allowedOrigins: Seq[String] = Seq.empty

  object JsonRpcWsServerConfig:
    def apply(fukuiiConfig: com.typesafe.config.Config): JsonRpcWsServerConfig =
      val wsConfig = fukuiiConfig.getConfig("network.rpc.ws")
      new JsonRpcWsServerConfig:
        override val enabled: Boolean = wsConfig.getBoolean("enabled")
        override val interface: String = wsConfig.getString("interface")
        override val port: Int = wsConfig.getInt("port")
        override val allowedOrigins: Seq[String] = parseAllowedOrigins(wsConfig)

    /** Reads the optional `allowed-origins` key of the `ws` block: a list of strings, or one comma-separated string.
      * Absent (or null) gives an empty list, which selects the default.
      */
    private[http] def parseAllowedOrigins(wsConfig: com.typesafe.config.Config): Seq[String] =
      val key = "allowed-origins"
      if !wsConfig.hasPath(key) then Seq.empty
      else
        wsConfig.getValue(key).valueType match
          case ConfigValueType.LIST => wsConfig.getStringList(key).asScala.toSeq
          case _                    => wsConfig.getString(key).split(',').toSeq
