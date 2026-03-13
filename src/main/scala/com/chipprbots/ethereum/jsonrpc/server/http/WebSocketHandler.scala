package com.chipprbots.ethereum.jsonrpc.server.http

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model.ws.Message
import org.apache.pekko.http.scaladsl.model.ws.TextMessage
import org.apache.pekko.pattern.ask
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.Timeout

import cats.effect.unsafe.IORuntime

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try

import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.native.JsonMethods._

import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.jsonrpc.serialization.JsonSerializers
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController
import com.chipprbots.ethereum.jsonrpc.subscription.SubscriptionManager._
import com.chipprbots.ethereum.jsonrpc.subscription.SubscriptionType
import com.chipprbots.ethereum.utils.Logger

/** Creates a Pekko Streams Flow[Message, Message, Any] for each WebSocket connection.
  *
  * Handles both directions:
  *   - Incoming: JSON-RPC requests (eth_subscribe, eth_unsubscribe, or regular RPC)
  *   - Outgoing: Server-push notifications from subscription services
  *
  * Buffer: 10,000 notifications per connection, force-disconnect on overflow (geth pattern).
  * Connection lifecycle: materialized Source.actorRef becomes the connectionRef tracked
  * by SubscriptionManager. On stream completion, Terminated message triggers cleanup.
  */
class WebSocketHandler(
    jsonRpcController: JsonRpcBaseController,
    subscriptionManager: ActorRef,
    notificationBufferSize: Int
)(implicit system: ActorSystem)
    extends Logger {

  import system.dispatcher
  implicit private val ioRuntime: IORuntime = IORuntime.global
  implicit private val askTimeout: Timeout = Timeout(10.seconds)
  implicit private val formats: Formats = DefaultFormats + JsonSerializers.RpcErrorJsonSerializer

  /** Create a WebSocket flow for a single connection. */
  def createFlow(): Flow[Message, Message, Any] = {
    // Pre-materialize the push notification source to get the connectionRef actor
    // OverflowStrategy.fail = force-disconnect when buffer is full (geth: 10K limit)
    val (connectionRef, pushSource) = Source
      .actorRef[SendNotification](
        completionMatcher = PartialFunction.empty,
        failureMatcher = PartialFunction.empty,
        bufferSize = notificationBufferSize,
        overflowStrategy = OverflowStrategy.fail
      )
      .preMaterialize()

    // Register this connection with the SubscriptionManager for lifecycle tracking
    subscriptionManager ! RegisterConnection(connectionRef)

    // Push notifications serialized to TextMessage
    val pushMessages: Source[Message, NotUsed] = pushSource
      .map(sn => TextMessage.Strict(sn.notification.toJsonString): Message)

    // Pekko HTTP WS flow pattern:
    // Incoming messages → process → response messages, merged with push notifications
    Flow[Message]
      .collect { case TextMessage.Strict(text) => text }
      .mapAsync(1)(handleIncomingMessage(connectionRef, _))
      .map(json => TextMessage.Strict(json): Message)
      .merge(pushMessages)
  }

  private def handleIncomingMessage(connectionRef: ActorRef, text: String): Future[String] = {
    Try(parse(text)).toOption match {
      case None =>
        Future.successful(
          compact(render(errorJson(JNull, -32700, "Parse error")))
        )
      case Some(json) =>
        val method = (json \ "method").extractOpt[String].getOrElse("")
        val id = json \ "id"
        val params = (json \ "params").extractOpt[JArray]

        method match {
          case "eth_subscribe"   => handleSubscribe(connectionRef, id, params)
          case "eth_unsubscribe" => handleUnsubscribe(connectionRef, id, params)
          case _ =>
            val request = JsonRpcRequest(
              jsonrpc = (json \ "jsonrpc").extractOpt[String].getOrElse("2.0"),
              method = method,
              params = params,
              id = Some(id)
            )
            jsonRpcController
              .handleRequest(request)
              .map(resp => org.json4s.native.Serialization.write(resp))
              .unsafeToFuture()
        }
    }
  }

  private def handleSubscribe(connectionRef: ActorRef, id: JValue, params: Option[JArray]): Future[String] = {
    val subTypeName = params.flatMap(_.arr.headOption).flatMap(_.extractOpt[String]).getOrElse("")
    val subParams = params.flatMap(_.arr.lift(1))

    val subType = subTypeName match {
      case "newHeads"                => Some(SubscriptionType.NewHeads)
      case "logs"                    => Some(SubscriptionType.Logs)
      case "newPendingTransactions"  => Some(SubscriptionType.NewPendingTransactions)
      case "syncing"                 => Some(SubscriptionType.Syncing)
      case _                         => None
    }

    subType match {
      case None =>
        Future.successful(compact(render(errorJson(id, -32602, s"Unknown subscription type: $subTypeName"))))
      case Some(st) =>
        val mgrParams = parseSubscriptionParams(st, subParams)
        (subscriptionManager ? Subscribe(connectionRef, st, mgrParams))
          .mapTo[SubscribeResult]
          .map {
            case SubscribeResult(Some(subId)) =>
              compact(render(successJson(id, JString("0x" + subId.toHexString))))
            case SubscribeResult(None) =>
              compact(render(errorJson(id, -32000, "Subscription limit reached")))
          }
    }
  }

  private def handleUnsubscribe(connectionRef: ActorRef, id: JValue, params: Option[JArray]): Future[String] = {
    val subIdHex = params.flatMap(_.arr.headOption).flatMap(_.extractOpt[String]).getOrElse("")
    val subId = Try(java.lang.Long.parseLong(subIdHex.stripPrefix("0x"), 16)).toOption

    subId match {
      case None =>
        Future.successful(compact(render(errorJson(id, -32602, "Invalid subscription ID"))))
      case Some(sid) =>
        (subscriptionManager ? Unsubscribe(connectionRef, sid))
          .mapTo[UnsubscribeResult]
          .map(result => compact(render(successJson(id, JBool(result.success)))))
    }
  }

  private def parseSubscriptionParams(
      subType: SubscriptionType,
      params: Option[JValue]
  ): Option[SubscriptionParams] = params.flatMap { p =>
    subType match {
      case SubscriptionType.Logs =>
        val address = (p \ "address").extractOpt[String].map { addr =>
          com.chipprbots.ethereum.domain.Address(
            org.apache.pekko.util.ByteString(
              com.chipprbots.ethereum.utils.Hex.decode(addr.stripPrefix("0x"))
            )
          )
        }
        val topics = (p \ "topics").extractOpt[List[Any]].map { topicList =>
          topicList.map {
            case null => Seq.empty[org.apache.pekko.util.ByteString]
            case s: String =>
              Seq(org.apache.pekko.util.ByteString(com.chipprbots.ethereum.utils.Hex.decode(s.stripPrefix("0x"))))
            case arr: List[_] =>
              arr.collect { case s: String =>
                org.apache.pekko.util.ByteString(com.chipprbots.ethereum.utils.Hex.decode(s.stripPrefix("0x")))
              }
            case _ => Seq.empty[org.apache.pekko.util.ByteString]
          }
        }.getOrElse(Seq.empty)
        Some(LogsParams(address, topics))
      case SubscriptionType.NewPendingTransactions =>
        val fullTx = (p \ "includeTransactions").extractOpt[Boolean].getOrElse(false)
        Some(PendingTxParams(fullTx))
      case _ => None
    }
  }

  private def successJson(id: JValue, result: JValue): JValue =
    ("jsonrpc" -> "2.0") ~ ("id" -> id) ~ ("result" -> result)

  private def errorJson(id: JValue, code: Int, message: String): JValue =
    ("jsonrpc" -> "2.0") ~ ("id" -> id) ~ ("error" -> (("code" -> code) ~ ("message" -> message)))
}
