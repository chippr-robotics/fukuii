package com.chipprbots.ethereum.jsonrpc.subscription

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.util.ByteString

import org.json4s.JsonAST._
import org.json4s.JsonDSL._

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.jsonrpc.FilterManager.TxLog
import com.chipprbots.ethereum.utils.ByteStringUtils

/** Subscription type enum — mirrors Besu's SubscriptionType. */
enum SubscriptionType:
  case NewHeads, Logs, NewPendingTransactions, Syncing

/** Base subscription trait — each subscription tracks its connection (Besu pattern: connectionId). */
sealed trait Subscription {
  def id: Long
  def subscriptionType: SubscriptionType
  def connectionRef: ActorRef
}

case class NewHeadsSubscription(
    id: Long,
    connectionRef: ActorRef,
    includeTransactions: Boolean = false
) extends Subscription {
  val subscriptionType: SubscriptionType = SubscriptionType.NewHeads
}

case class LogsSubscription(
    id: Long,
    connectionRef: ActorRef,
    address: Option[Address],
    topics: Seq[Seq[ByteString]]
) extends Subscription {
  val subscriptionType: SubscriptionType = SubscriptionType.Logs
}

case class PendingTxSubscription(
    id: Long,
    connectionRef: ActorRef,
    fullTx: Boolean = false
) extends Subscription {
  val subscriptionType: SubscriptionType = SubscriptionType.NewPendingTransactions
}

case class SyncingSubscription(
    id: Long,
    connectionRef: ActorRef
) extends Subscription {
  val subscriptionType: SubscriptionType = SubscriptionType.Syncing
}

/** JSON serialization helpers for subscription notifications.
  * Matches geth/Besu notification format.
  */
object SubscriptionJsonSerializers {

  private def hex(bs: ByteString): String = "0x" + ByteStringUtils.hash2string(bs)
  private def hex(n: BigInt): String = "0x" + n.toString(16)

  /** Serialize a block header for newHeads notifications.
    * Format matches geth's output (flat header, no transactions array).
    */
  def serializeBlockHeader(header: BlockHeader): JValue =
    ("number" -> hex(header.number)) ~
      ("hash" -> hex(header.hash)) ~
      ("parentHash" -> hex(header.parentHash)) ~
      ("nonce" -> hex(header.nonce)) ~
      ("sha3Uncles" -> hex(header.ommersHash)) ~
      ("logsBloom" -> hex(header.logsBloom)) ~
      ("transactionsRoot" -> hex(header.transactionsRoot)) ~
      ("stateRoot" -> hex(header.stateRoot)) ~
      ("receiptsRoot" -> hex(header.receiptsRoot)) ~
      ("miner" -> hex(header.beneficiary)) ~
      ("difficulty" -> hex(header.difficulty)) ~
      ("extraData" -> hex(header.extraData)) ~
      ("gasLimit" -> hex(header.gasLimit)) ~
      ("gasUsed" -> hex(header.gasUsed)) ~
      ("timestamp" -> hex(header.unixTimestamp)) ~
      ("mixHash" -> hex(header.mixHash))

  /** Serialize a TxLog for logs notifications.
    * Includes `removed` field for reorg handling (geth/Besu pattern).
    */
  def serializeTxLog(log: TxLog, removed: Boolean = false): JValue =
    ("address" -> hex(log.address.bytes)) ~
      ("topics" -> JArray(log.topics.map(t => JString(hex(t))).toList)) ~
      ("data" -> hex(log.data)) ~
      ("blockNumber" -> hex(log.blockNumber)) ~
      ("transactionHash" -> hex(log.transactionHash)) ~
      ("transactionIndex" -> hex(BigInt(log.transactionIndex.toInt))) ~
      ("blockHash" -> hex(log.blockHash)) ~
      ("logIndex" -> hex(BigInt(log.logIndex.toInt))) ~
      ("removed" -> removed)

  /** Serialize a transaction hash for newPendingTransactions notifications. */
  def serializeTxHash(txHash: ByteString): JValue =
    JString(hex(txHash))

  /** Serialize sync status for syncing notifications. */
  def serializeSyncStatus(syncing: Boolean, startingBlock: BigInt, currentBlock: BigInt, highestBlock: BigInt): JValue =
    if (syncing)
      ("syncing" -> true) ~
        ("status" ->
          (("startingBlock" -> hex(startingBlock)) ~
            ("currentBlock" -> hex(currentBlock)) ~
            ("highestBlock" -> hex(highestBlock))))
    else
      JBool(false)
}
