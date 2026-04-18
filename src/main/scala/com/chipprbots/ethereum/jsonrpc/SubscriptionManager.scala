package com.chipprbots.ethereum.jsonrpc

import java.util.concurrent.atomic.AtomicLong

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorLogging
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Props
import org.apache.pekko.stream.scaladsl.SourceQueueWithComplete
import org.apache.pekko.stream.QueueOfferResult
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext

import org.json4s._
import org.json4s.native.JsonMethods._
import org.json4s.native.Serialization

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.domain.TxLogEntry
import com.chipprbots.ethereum.jsonrpc.serialization.JsonSerializers
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

/** Manages WebSocket subscriptions for eth_subscribe / eth_unsubscribe.
  *
  * Besu reference: ethereum/api/.../websocket/subscription/SubscriptionManager.java
  * ethereum/api/.../websocket/subscription/blockheaders/NewBlockHeadersSubscriptionService.java
  * ethereum/api/.../websocket/subscription/logs/LogsSubscriptionService.java
  * ethereum/api/.../websocket/subscription/pending/PendingTransactionSubscriptionService.java
  * ethereum/api/.../websocket/subscription/syncing/SyncingSubscriptionService.java
  *
  * Besu uses Vert.x EventBus + Verticle for subscription routing. We use ActorSystem.eventStream (same pub/sub
  * semantics) with a Pekko actor instead of a Vert.x Verticle.
  *
  * Connection lifecycle: RegisterConnection → Subscribe/Unsubscribe (0..N) → ConnectionClosed. All subscriptions for a
  * closed connection are automatically cleaned up.
  */
class SubscriptionManager(blockchainReader: BlockchainReader) extends Actor with ActorLogging {

  import SubscriptionManager._

  implicit val formats: Formats = DefaultFormats + JsonSerializers.RpcErrorJsonSerializer
  implicit val ec: ExecutionContext = context.dispatcher

  // subscriptionId → Subscription
  private var subscriptions: Map[Long, Subscription] = Map.empty
  // connectionId → output queue for WS push
  private var connections: Map[String, SourceQueueWithComplete[String]] = Map.empty
  private val counter = new AtomicLong(0L)

  override def preStart(): Unit = {
    context.system.eventStream.subscribe(self, classOf[NewBlockImported])
    context.system.eventStream.subscribe(self, classOf[NewPendingTransaction])
  }

  override def postStop(): Unit =
    context.system.eventStream.unsubscribe(self)

  override def receive: Receive = {
    case RegisterConnection(connId, queue) =>
      log.debug("WS connection registered: {}", connId)
      connections += (connId -> queue)

    case ConnectionClosed(connId) =>
      log.debug("WS connection closed: {}", connId)
      connections -= connId
      subscriptions = subscriptions.filterNot { case (_, sub) => sub.connectionId == connId }

    case msg: Subscribe =>
      val id = counter.incrementAndGet()
      buildSubscription(id, msg) match {
        case Right(sub) =>
          subscriptions += (id -> sub)
          sender() ! SubscribeResponse(Right(id))
        case Left(err) =>
          sender() ! SubscribeResponse(Left(err))
      }

    case Unsubscribe(connId, subId) =>
      val found = subscriptions.get(subId).exists(_.connectionId == connId)
      if (found) subscriptions -= subId
      sender() ! UnsubscribeResponse(found)

    case NewBlockImported(block) =>
      notifyNewHeads(block)
      notifyLogs(block)

    case NewPendingTransaction(stx) =>
      notifyPendingTxs(stx)
  }

  // ---- subscription builders ----

  private def buildSubscription(id: Long, msg: Subscribe): Either[String, Subscription] =
    msg.subType match {
      case "newHeads" =>
        val includeTx = msg.params
          .collect { case JObject(fields) =>
            fields.collectFirst { case JField("includeTransactions", JBool(v)) => v }.getOrElse(false)
          }
          .getOrElse(false)
        Right(NewHeadsSubscription(id, msg.connId, includeTx))

      case "logs" =>
        val (address, topics) = msg.params match {
          case Some(JObject(fields)) =>
            val addr = fields.collectFirst { case JField("address", v) => parseAddresses(v) }.flatten
            val tops = fields
              .collectFirst { case JField("topics", JArray(ts)) => parseTopics(ts) }
              .getOrElse(Seq.empty)
            (addr, tops)
          case _ => (None, Seq.empty)
        }
        Right(LogsSubscription(id, msg.connId, address, topics))

      case "newPendingTransactions" =>
        val includeTx = msg.params
          .collect { case JObject(fields) =>
            fields.collectFirst { case JField("includeTransactions", JBool(v)) => v }.getOrElse(false)
          }
          .getOrElse(false)
        Right(NewPendingTxsSubscription(id, msg.connId, includeTx))

      case "syncing" =>
        Right(SyncingSubscription(id, msg.connId))

      case other =>
        Left(s"Unknown subscription type: $other")
    }

  private def parseAddresses(v: JValue): Option[Seq[Address]] = v match {
    case JString(s)   => Some(Seq(Address(ByteString(hexToBytes(s)))))
    case JArray(vals) => Some(vals.collect { case JString(s) => Address(ByteString(hexToBytes(s))) })
    case _            => None
  }

  private def parseTopics(ts: List[JValue]): Seq[Seq[ByteString]] =
    ts.map {
      case JString(s) => Seq(ByteString(hexToBytes(s)))
      case JArray(vs) => vs.collect { case JString(s) => ByteString(hexToBytes(s)) }
      case _          => Seq.empty
    }

  private def hexToBytes(hex: String): Array[Byte] = {
    val h = if (hex.startsWith("0x") || hex.startsWith("0X")) hex.drop(2) else hex
    val padded = if (h.length % 2 != 0) "0" + h else h
    padded.grouped(2).map(b => Integer.parseInt(b, 16).toByte).toArray
  }

  // ---- push helpers ----

  private def push(connId: String, json: String): Unit =
    connections.get(connId).foreach { queue =>
      queue.offer(json)
    }

  private def subscriptionEnvelope(subId: Long, result: JValue): String = {
    val hex = "0x" + subId.toHexString
    val json = JObject(
      "jsonrpc" -> JString("2.0"),
      "method" -> JString("eth_subscription"),
      "params" -> JObject("subscription" -> JString(hex), "result" -> result)
    )
    compact(render(json))
  }

  // ---- newHeads ----

  private def notifyNewHeads(block: Block): Unit =
    subscriptions.values.collect { case s: NewHeadsSubscription => s }.foreach { sub =>
      val result = blockHeaderJson(block, sub.includeTransactions)
      push(sub.connectionId, subscriptionEnvelope(sub.subscriptionId, result))
    }

  private def blockHeaderJson(block: Block, includeTransactions: Boolean): JValue = {
    val h = block.header
    val base = JObject(
      "number" -> JString("0x" + h.number.toString(16)),
      "hash" -> JString("0x" + h.hash.toHex),
      "parentHash" -> JString("0x" + h.parentHash.toHex),
      "sha3Uncles" -> JString("0x" + h.ommersHash.toHex),
      "logsBloom" -> JString("0x" + h.logsBloom.toHex),
      "transactionsRoot" -> JString("0x" + h.transactionsRoot.toHex),
      "stateRoot" -> JString("0x" + h.stateRoot.toHex),
      "receiptsRoot" -> JString("0x" + h.receiptsRoot.toHex),
      "miner" -> JString(h.beneficiary.toString),
      "difficulty" -> JString("0x" + h.difficulty.toString(16)),
      "extraData" -> JString("0x" + h.extraData.toHex),
      "gasLimit" -> JString("0x" + h.gasLimit.toString(16)),
      "gasUsed" -> JString("0x" + h.gasUsed.toString(16)),
      "timestamp" -> JString("0x" + h.unixTimestamp.toHexString),
      "nonce" -> JString("0x" + h.nonce.toHex)
    )
    if (includeTransactions)
      base.merge(
        JObject(
          "transactions" -> JArray(
            block.body.transactionList.map(tx => JString("0x" + tx.hash.toHex)).toList
          )
        )
      )
    else base
  }

  // ---- logs ----

  private def notifyLogs(block: Block): Unit = {
    val logSubs = subscriptions.values.collect { case s: LogsSubscription => s }
    if (logSubs.isEmpty) return

    val receipts = blockchainReader.getReceiptsByHash(block.header.hash).getOrElse(Seq.empty)
    var blockLogIndex = 0
    receipts.zipWithIndex.foreach { case (receipt, txIndex) =>
      receipt.logs.zipWithIndex.foreach { case (log, localIdx) =>
        val globalIdx = blockLogIndex + localIdx
        logSubs.foreach { sub =>
          if (logMatchesSubscription(log, sub)) {
            val tx = block.body.transactionList(txIndex)
            val logJson = JObject(
              "removed" -> JBool(false),
              "logIndex" -> JString("0x" + globalIdx.toHexString),
              "transactionIndex" -> JString("0x" + txIndex.toHexString),
              "transactionHash" -> JString("0x" + tx.hash.toHex),
              "blockHash" -> JString("0x" + block.header.hash.toHex),
              "blockNumber" -> JString("0x" + block.header.number.toString(16)),
              "address" -> JString(log.loggerAddress.toString),
              "data" -> JString("0x" + log.data.toHex),
              "topics" -> JArray(log.logTopics.map(t => JString("0x" + t.toHex)).toList)
            )
            push(sub.connectionId, subscriptionEnvelope(sub.subscriptionId, logJson))
          }
        }
      }
      blockLogIndex += receipt.logs.size
    }
  }

  private def logMatchesSubscription(log: TxLogEntry, sub: LogsSubscription): Boolean = {
    val addrMatch = sub.address.forall(addrs => addrs.contains(log.loggerAddress))
    val topicMatch = log.logTopics.size >= sub.topics.size &&
      sub.topics.zip(log.logTopics).forall { case (filter, logTopic) =>
        filter.isEmpty || filter.contains(logTopic)
      }
    addrMatch && topicMatch
  }

  // ---- pending transactions ----

  private def notifyPendingTxs(stx: SignedTransactionWithSender): Unit =
    subscriptions.values.collect { case s: NewPendingTxsSubscription => s }.foreach { sub =>
      val result: JValue =
        if (sub.includeTransactions) pendingTxJson(stx)
        else JString("0x" + stx.tx.hash.toHex)
      push(sub.connectionId, subscriptionEnvelope(sub.subscriptionId, result))
    }

  private def pendingTxJson(stx: SignedTransactionWithSender): JValue = {
    val tx = stx.tx.tx
    JObject(
      "hash" -> JString("0x" + stx.tx.hash.toHex),
      "nonce" -> JString("0x" + tx.nonce.toString(16)),
      "from" -> JString(stx.senderAddress.toString),
      "to" -> tx.receivingAddress.map(a => JString(a.toString): JValue).getOrElse(JNull),
      "value" -> JString("0x" + tx.value.toString(16)),
      "gas" -> JString("0x" + tx.gasLimit.toString(16)),
      "gasPrice" -> JString("0x" + tx.gasPrice.toString(16)),
      "input" -> JString("0x" + tx.payload.toHex)
    )
  }
}

object SubscriptionManager {

  sealed trait Subscription {
    def subscriptionId: Long
    def connectionId: String
  }

  case class NewHeadsSubscription(
      subscriptionId: Long,
      connectionId: String,
      includeTransactions: Boolean
  ) extends Subscription

  case class LogsSubscription(
      subscriptionId: Long,
      connectionId: String,
      address: Option[Seq[Address]],
      topics: Seq[Seq[ByteString]]
  ) extends Subscription

  case class NewPendingTxsSubscription(
      subscriptionId: Long,
      connectionId: String,
      includeTransactions: Boolean
  ) extends Subscription

  case class SyncingSubscription(
      subscriptionId: Long,
      connectionId: String
  ) extends Subscription

  // messages
  case class RegisterConnection(connId: String, queue: SourceQueueWithComplete[String])
  case class ConnectionClosed(connId: String)
  case class Subscribe(connId: String, subType: String, params: Option[JValue])
  case class Unsubscribe(connId: String, subId: Long)
  case class SubscribeResponse(result: Either[String, Long])
  case class UnsubscribeResponse(found: Boolean)

  def props(blockchainReader: BlockchainReader): Props =
    Props(new SubscriptionManager(blockchainReader))
}
