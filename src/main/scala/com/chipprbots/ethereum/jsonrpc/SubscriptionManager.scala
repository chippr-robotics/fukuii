package com.chipprbots.ethereum.jsonrpc

import java.util.concurrent.atomic.AtomicLong

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.pubsub.Topic
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.scaladsl.SourceQueueWithComplete
import org.apache.pekko.util.ByteString

import org.json4s.*
import org.json4s.native.JsonMethods.*

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.domain.TxLogEntry
import com.chipprbots.ethereum.jsonrpc.serialization.JsonSerializers
import com.chipprbots.ethereum.utils.ByteStringUtils.toHex

/** Manages WebSocket subscriptions for eth_subscribe / eth_unsubscribe.
  *
  * Besu reference: ethereum/api/.../websocket/subscription/SubscriptionManager.java
  * ethereum/api/.../websocket/subscription/blockheaders/NewBlockHeadersSubscriptionService.java
  * ethereum/api/.../websocket/subscription/logs/LogsSubscriptionService.java
  * ethereum/api/.../websocket/subscription/pending/PendingTransactionSubscriptionService.java
  * ethereum/api/.../websocket/subscription/syncing/SyncingSubscriptionService.java
  *
  * Besu uses Vert.x EventBus + Verticle for subscription routing. We use dedicated Pekko Typed Topic[T] actors
  * (blockTopic, pendingTxTopic — same pub/sub semantics) with a Pekko actor instead of a Vert.x Verticle.
  *
  * Connection lifecycle: RegisterConnection → Subscribe/Unsubscribe (0..N) → ConnectionClosed. All subscriptions for a
  * closed connection are automatically cleaned up.
  */
object SubscriptionManager:

  // ── Subscription model ───────────────────────────────────────────────────

  sealed trait Subscription:
    def subscriptionId: Long
    def connectionId: String

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

  // ── Commands ─────────────────────────────────────────────────────────────

  sealed trait Command
  case class RegisterConnection(connId: String, queue: SourceQueueWithComplete[String]) extends Command
  case class ConnectionClosed(connId: String) extends Command
  case class Subscribe(connId: String, subType: String, params: Option[JValue], replyTo: ActorRef[SubscribeResponse])
      extends Command
  case class Unsubscribe(connId: String, subId: Long, replyTo: ActorRef[UnsubscribeResponse]) extends Command

  // private adapters for Topic[T] events
  private case class BlockImported(block: Block) extends Command
  private case class PendingTxArrived(stx: SignedTransactionWithSender) extends Command

  // ── Responses ────────────────────────────────────────────────────────────

  case class SubscribeResponse(result: Either[String, Long])
  case class UnsubscribeResponse(found: Boolean)

  // ── Behavior ─────────────────────────────────────────────────────────────

  def apply(
      blockchainReader: BlockchainReader,
      pendingTxTopic: ActorRef[Topic.Command[NewPendingTransaction]],
      blockTopic: ActorRef[Topic.Command[NewBlockImported]]
  ): Behavior[Command] = Behaviors.setup { ctx =>
    DefaultFormats + JsonSerializers.RpcErrorJsonSerializer
    ctx.executionContext

    var subscriptions: Map[Long, Subscription] = Map.empty
    var connections: Map[String, SourceQueueWithComplete[String]] = Map.empty
    val counter = new AtomicLong(0L)

    // Subscribe to the dedicated Topic[T] channels via message adapters
    val blockAdapter = ctx.messageAdapter[NewBlockImported](e => BlockImported(e.block))
    val pendingTxAdapter = ctx.messageAdapter[NewPendingTransaction](e => PendingTxArrived(e.stx))
    blockTopic ! Topic.Subscribe(blockAdapter)
    pendingTxTopic ! Topic.Subscribe(pendingTxAdapter)

    // ---- subscription builders ----

    def buildSubscription(id: Long, msg: Subscribe): Either[String, Subscription] =
      msg.subType match
        case "newHeads" =>
          val includeTx = msg.params
            .collect { case JObject(fields) =>
              fields.collectFirst { case JField("includeTransactions", JBool(v)) => v }.getOrElse(false)
            }
            .getOrElse(false)
          Right(NewHeadsSubscription(id, msg.connId, includeTx))

        case "logs" =>
          val (address, topics) = msg.params match
            case Some(JObject(fields)) =>
              val addr = fields.collectFirst { case JField("address", v) => parseAddresses(v) }.flatten
              val tops = fields
                .collectFirst { case JField("topics", JArray(ts)) => parseTopics(ts) }
                .getOrElse(Seq.empty)
              (addr, tops)
            case _ => (None, Seq.empty)
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

    def parseAddresses(v: JValue): Option[Seq[Address]] = v match
      case JString(s)   => Some(Seq(Address(ByteString(hexToBytes(s)))))
      case JArray(vals) => Some(vals.collect { case JString(s) => Address(ByteString(hexToBytes(s))) })
      case _            => None

    def parseTopics(ts: List[JValue]): Seq[Seq[ByteString]] =
      ts.map {
        case JString(s) => Seq(ByteString(hexToBytes(s)))
        case JArray(vs) => vs.collect { case JString(s) => ByteString(hexToBytes(s)) }
        case _          => Seq.empty
      }

    def hexToBytes(hex: String): Array[Byte] =
      val h = if hex.startsWith("0x") || hex.startsWith("0X") then hex.drop(2) else hex
      val padded = if h.length % 2 != 0 then "0" + h else h
      padded.grouped(2).map(b => Integer.parseInt(b, 16).toByte).toArray

    // ---- push helpers ----

    def push(connId: String, json: String): Unit =
      connections.get(connId).foreach(_.offer(json))

    def subscriptionEnvelope(subId: Long, result: JValue): String =
      val hex = "0x" + subId.toHexString
      val json = JObject(
        "jsonrpc" -> JString("2.0"),
        "method" -> JString("eth_subscription"),
        "params" -> JObject("subscription" -> JString(hex), "result" -> result)
      )
      compact(render(json))

    // ---- newHeads ----

    def notifyNewHeads(block: Block): Unit =
      subscriptions.values.collect { case s: NewHeadsSubscription => s }.foreach { sub =>
        val result = blockHeaderJson(block, sub.includeTransactions)
        push(sub.connectionId, subscriptionEnvelope(sub.subscriptionId, result))
      }

    def blockHeaderJson(block: Block, includeTransactions: Boolean): JValue =
      val h = block.header
      val base = JObject(
        "number" -> JString("0x" + h.number.value.toString(16)),
        "hash" -> JString("0x" + h.hash.value.toHex),
        "parentHash" -> JString("0x" + h.parentHash.value.toHex),
        "sha3Uncles" -> JString("0x" + h.ommersHash.value.toHex),
        "logsBloom" -> JString("0x" + h.logsBloom.value.toHex),
        "transactionsRoot" -> JString("0x" + h.transactionsRoot.value.toHex),
        "stateRoot" -> JString("0x" + h.stateRoot.value.toHex),
        "receiptsRoot" -> JString("0x" + h.receiptsRoot.value.toHex),
        "miner" -> JString(h.beneficiary.toString),
        "difficulty" -> JString("0x" + h.difficulty.value.toString(16)),
        "extraData" -> JString("0x" + h.extraData.toHex),
        "gasLimit" -> JString("0x" + h.gasLimit.value.toString(16)),
        "gasUsed" -> JString("0x" + h.gasUsed.value.toString(16)),
        "timestamp" -> JString("0x" + h.unixTimestamp.toHexString),
        "nonce" -> JString("0x" + h.nonce.toHex)
      )
      if includeTransactions then
        base.merge(
          JObject(
            "transactions" -> JArray(
              block.body.transactionList.map(tx => JString("0x" + tx.hash.toHex)).toList
            )
          )
        )
      else base

    // ---- logs ----

    def notifyLogs(block: Block): Unit =
      val logSubs = subscriptions.values.collect { case s: LogsSubscription => s }
      if logSubs.isEmpty then return

      val receipts = blockchainReader.getReceiptsByHash(block.header.hash).getOrElse(Seq.empty)
      var blockLogIndex = 0
      receipts.zipWithIndex.foreach { case (receipt, txIndex) =>
        receipt.logs.zipWithIndex.foreach { case (log, localIdx) =>
          val globalIdx = blockLogIndex + localIdx
          logSubs.foreach { sub =>
            if logMatchesSubscription(log, sub) then
              val tx = block.body.transactionList(txIndex)
              val logJson = JObject(
                "removed" -> JBool(false),
                "logIndex" -> JString("0x" + globalIdx.toHexString),
                "transactionIndex" -> JString("0x" + txIndex.toHexString),
                "transactionHash" -> JString("0x" + tx.hash.toHex),
                "blockHash" -> JString("0x" + block.header.hash.value.toHex),
                "blockNumber" -> JString("0x" + block.header.number.value.toString(16)),
                "address" -> JString(log.loggerAddress.toString),
                "data" -> JString("0x" + log.data.toHex),
                "topics" -> JArray(log.logTopics.map(t => JString("0x" + t.toHex)).toList)
              )
              push(sub.connectionId, subscriptionEnvelope(sub.subscriptionId, logJson))
          }
        }
        blockLogIndex += receipt.logs.size
      }

    def logMatchesSubscription(log: TxLogEntry, sub: LogsSubscription): Boolean =
      val addrMatch = sub.address.forall(addrs => addrs.contains(log.loggerAddress))
      val topicMatch = log.logTopics.size >= sub.topics.size &&
        sub.topics.zip(log.logTopics).forall { case (filter, logTopic) =>
          filter.isEmpty || filter.contains(logTopic)
        }
      addrMatch && topicMatch

    // ---- pending transactions ----

    def notifyPendingTxs(stx: SignedTransactionWithSender): Unit =
      subscriptions.values.collect { case s: NewPendingTxsSubscription => s }.foreach { sub =>
        val result: JValue =
          if sub.includeTransactions then pendingTxJson(stx)
          else JString("0x" + stx.tx.hash.toHex)
        push(sub.connectionId, subscriptionEnvelope(sub.subscriptionId, result))
      }

    def pendingTxJson(stx: SignedTransactionWithSender): JValue =
      val tx = stx.tx.tx
      JObject(
        "hash" -> JString("0x" + stx.tx.hash.toHex),
        "nonce" -> JString("0x" + tx.nonce.toString(16)),
        "from" -> JString(stx.senderAddress.toString),
        "to" -> tx.receivingAddress.map(a => JString(a.toString): JValue).getOrElse(JNull),
        "value" -> JString("0x" + tx.value.toString(16)),
        "gas" -> JString("0x" + tx.gasLimit.value.toString(16)),
        "gasPrice" -> JString("0x" + tx.gasPrice.value.toString(16)),
        "input" -> JString("0x" + tx.payload.toHex)
      )

    Behaviors
      .receiveMessage[Command] {
        case RegisterConnection(connId, queue) =>
          ctx.log.debug("WS connection registered: {}", connId)
          connections += (connId -> queue)
          Behaviors.same

        case ConnectionClosed(connId) =>
          ctx.log.debug("WS connection closed: {}", connId)
          connections -= connId
          subscriptions = subscriptions.filterNot { case (_, sub) => sub.connectionId == connId }
          Behaviors.same

        case msg: Subscribe =>
          val id = counter.incrementAndGet()
          buildSubscription(id, msg) match
            case Right(sub) =>
              subscriptions += (id -> sub)
              msg.replyTo ! SubscribeResponse(Right(id))
            case Left(err) =>
              msg.replyTo ! SubscribeResponse(Left(err))
          Behaviors.same

        case Unsubscribe(connId, subId, replyTo) =>
          val found = subscriptions.get(subId).exists(_.connectionId == connId)
          if found then subscriptions -= subId
          replyTo ! UnsubscribeResponse(found)
          Behaviors.same

        case BlockImported(block) =>
          notifyNewHeads(block)
          notifyLogs(block)
          Behaviors.same

        case PendingTxArrived(stx) =>
          notifyPendingTxs(stx)
          Behaviors.same
      }
      .receiveSignal { case (_, org.apache.pekko.actor.typed.PostStop) =>
        blockTopic ! Topic.Unsubscribe(blockAdapter)
        pendingTxTopic ! Topic.Unsubscribe(pendingTxAdapter)
        Behaviors.same
      }
  }
