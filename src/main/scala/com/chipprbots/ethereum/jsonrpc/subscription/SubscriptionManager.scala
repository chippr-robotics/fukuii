package com.chipprbots.ethereum.jsonrpc.subscription

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Props
import org.apache.pekko.actor.Terminated

import org.json4s.JsonAST.JValue

import com.chipprbots.ethereum.jsonrpc.JsonRpcNotification
import com.chipprbots.ethereum.utils.Logger

/** Global subscription manager — single actor holding ALL subscriptions.
  *
  * Emulates Besu's SubscriptionManager (ConcurrentHashMap + connectionId tracking).
  * Pekko analog: ActorRef replaces connectionId, context.watch replaces event bus cleanup.
  *
  * Lifecycle:
  *   - RegisterConnection: start watching a WebSocket connection actor
  *   - Subscribe: create a typed Subscription, store in map, return hex ID
  *   - Unsubscribe: remove subscription, return success
  *   - NotifySubscribers: called by subscription services to push to matching connections
  *   - Terminated: connection closed — remove ALL subscriptions for that connection
  */
class SubscriptionManager(config: SubscriptionManager.Config) extends Actor with Logger {

  import SubscriptionManager._

  private var subscriptions: Map[Long, Subscription] = Map.empty
  private var subscriptionCounter: Long = 0
  private var connectionSubscriptions: Map[ActorRef, Set[Long]] = Map.empty

  override def receive: Receive = {
    case RegisterConnection(connectionRef) =>
      if (connectionSubscriptions.size >= config.maxActiveConnections) {
        log.warn("Max WebSocket connections ({}) reached, rejecting {}", config.maxActiveConnections, connectionRef)
        sender() ! ConnectionRejected
      } else {
        context.watch(connectionRef)
        connectionSubscriptions = connectionSubscriptions.updated(connectionRef, Set.empty)
        sender() ! ConnectionRegistered
      }

    case Subscribe(connectionRef, subType, params) =>
      val connSubs = connectionSubscriptions.getOrElse(connectionRef, Set.empty)
      if (connSubs.size >= config.maxSubscriptionsPerConnection) {
        log.warn("Max subscriptions ({}) reached for connection {}", config.maxSubscriptionsPerConnection, connectionRef)
        sender() ! SubscribeResult(None)
      } else {
        subscriptionCounter += 1
        val id = subscriptionCounter
        val subscription = createSubscription(id, connectionRef, subType, params)
        subscriptions = subscriptions.updated(id, subscription)
        connectionSubscriptions = connectionSubscriptions.updated(connectionRef, connSubs + id)
        log.debug("Created subscription 0x{} type={} for {}", id.toHexString, subType, connectionRef)
        sender() ! SubscribeResult(Some(id))
      }

    case Unsubscribe(connectionRef, subscriptionId) =>
      subscriptions.get(subscriptionId) match {
        case Some(sub) if sub.connectionRef == connectionRef =>
          removeSubscription(subscriptionId)
          sender() ! UnsubscribeResult(true)
        case _ =>
          sender() ! UnsubscribeResult(false)
      }

    case NotifySubscribers(subType, result) =>
      subscriptions.values
        .filter(_.subscriptionType == subType)
        .foreach { sub =>
          val notification = JsonRpcNotification.subscription("0x" + sub.id.toHexString, result)
          sub.connectionRef ! SendNotification(notification)
        }

    case NotifySubscription(subscriptionId, result) =>
      subscriptions.get(subscriptionId).foreach { sub =>
        val notification = JsonRpcNotification.subscription("0x" + sub.id.toHexString, result)
        sub.connectionRef ! SendNotification(notification)
      }

    case GetSubscriptionsOfType(subType) =>
      sender() ! SubscriptionsOfType(subscriptions.values.filter(_.subscriptionType == subType).toSeq)

    case Terminated(connectionRef) =>
      val subIds = connectionSubscriptions.getOrElse(connectionRef, Set.empty)
      if (subIds.nonEmpty) {
        log.debug("Connection {} terminated, removing {} subscriptions", connectionRef, subIds.size)
        subIds.foreach(id => subscriptions = subscriptions - id)
      }
      connectionSubscriptions = connectionSubscriptions - connectionRef
  }

  private def removeSubscription(id: Long): Unit = {
    subscriptions.get(id).foreach { sub =>
      subscriptions = subscriptions - id
      val connSubs = connectionSubscriptions.getOrElse(sub.connectionRef, Set.empty)
      connectionSubscriptions = connectionSubscriptions.updated(sub.connectionRef, connSubs - id)
    }
  }

  private def createSubscription(
      id: Long,
      connectionRef: ActorRef,
      subType: SubscriptionType,
      params: Option[SubscriptionParams]
  ): Subscription = subType match {
    case SubscriptionType.NewHeads =>
      val includeTransactions = params.collect { case p: NewHeadsParams => p.includeTransactions }.getOrElse(false)
      NewHeadsSubscription(id, connectionRef, includeTransactions)

    case SubscriptionType.Logs =>
      val (address, topics) = params.collect { case p: LogsParams => (p.address, p.topics) }.getOrElse((None, Seq.empty))
      LogsSubscription(id, connectionRef, address, topics)

    case SubscriptionType.NewPendingTransactions =>
      val fullTx = params.collect { case p: PendingTxParams => p.fullTx }.getOrElse(false)
      PendingTxSubscription(id, connectionRef, fullTx)

    case SubscriptionType.Syncing =>
      SyncingSubscription(id, connectionRef)
  }
}

object SubscriptionManager {

  def props(config: Config): Props = Props(new SubscriptionManager(config))

  /** Configuration for SubscriptionManager bounds. */
  trait Config {
    val maxActiveConnections: Int
    val maxSubscriptionsPerConnection: Int
    val notificationBufferSize: Int
  }

  // --- Messages ---

  /** Register a WebSocket connection actor for tracking. */
  case class RegisterConnection(connectionRef: ActorRef)
  case object ConnectionRegistered
  case object ConnectionRejected

  /** Create a subscription for a connection. */
  case class Subscribe(connectionRef: ActorRef, subType: SubscriptionType, params: Option[SubscriptionParams])
  case class SubscribeResult(subscriptionId: Option[Long])

  /** Remove a subscription. */
  case class Unsubscribe(connectionRef: ActorRef, subscriptionId: Long)
  case class UnsubscribeResult(success: Boolean)

  /** Notify all subscribers of a given type — called by subscription services. */
  case class NotifySubscribers(subType: SubscriptionType, result: JValue)

  /** Notify a single subscription — used for per-subscription filtering (logs). */
  case class NotifySubscription(subscriptionId: Long, result: JValue)

  /** Query matching subscriptions — used by subscription services for filtering. */
  case class GetSubscriptionsOfType(subType: SubscriptionType)
  case class SubscriptionsOfType(subscriptions: Seq[Subscription])

  /** Message sent from SubscriptionManager to connection actor to push a notification. */
  case class SendNotification(notification: JsonRpcNotification)

  // --- Subscription parameters ---

  sealed trait SubscriptionParams
  case class NewHeadsParams(includeTransactions: Boolean = false) extends SubscriptionParams
  case class LogsParams(
      address: Option[com.chipprbots.ethereum.domain.Address],
      topics: Seq[Seq[org.apache.pekko.util.ByteString]]
  ) extends SubscriptionParams
  case class PendingTxParams(fullTx: Boolean = false) extends SubscriptionParams
}
