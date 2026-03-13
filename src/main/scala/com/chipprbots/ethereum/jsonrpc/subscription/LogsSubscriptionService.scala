package com.chipprbots.ethereum.jsonrpc.subscription

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Props
import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.jsonrpc.FilterManager.TxLog
import com.chipprbots.ethereum.jsonrpc.subscription.SubscriptionEvents.BlockImported
import com.chipprbots.ethereum.jsonrpc.subscription.SubscriptionEvents.ChainReorg
import com.chipprbots.ethereum.jsonrpc.subscription.SubscriptionManager.GetSubscriptionsOfType
import com.chipprbots.ethereum.jsonrpc.subscription.SubscriptionManager.NotifySubscription
import com.chipprbots.ethereum.jsonrpc.subscription.SubscriptionManager.SubscriptionsOfType
import com.chipprbots.ethereum.ledger.BloomFilter
import com.chipprbots.ethereum.utils.Logger

/** Listens for block events and pushes matching log notifications.
  *
  * Emulates Besu's LogsSubscriptionService — uses per-subscription address/topic
  * filtering, sends removed:true logs on chain reorgs.
  *
  * Uses the same bloom filter + topic matching logic as FilterManager.
  */
class LogsSubscriptionService(
    subscriptionManager: ActorRef,
    blockchainReader: BlockchainReader
) extends Actor
    with Logger {

  import context.dispatcher

  override def preStart(): Unit = {
    context.system.eventStream.subscribe(self, classOf[BlockImported])
    context.system.eventStream.subscribe(self, classOf[ChainReorg])
  }

  override def postStop(): Unit = {
    context.system.eventStream.unsubscribe(self)
  }

  override def receive: Receive = {
    case BlockImported(blockNumber) =>
      blockchainReader.getBlockHeaderByNumber(blockNumber).foreach { header =>
        blockchainReader.getBlockByHash(header.hash).foreach { block =>
          blockchainReader.getReceiptsByHash(header.hash).foreach { receipts =>
            notifyMatchingLogs(block, receipts, removed = false)
          }
        }
      }

    case ChainReorg(removedBlocks, addedBlocks) =>
      // Besu/geth pattern: emit removed:true logs from old branch first
      removedBlocks.foreach { block =>
        blockchainReader.getReceiptsByHash(block.header.hash).foreach { receipts =>
          notifyMatchingLogs(block, receipts, removed = true)
        }
      }
      // Then emit new logs from new branch
      addedBlocks.foreach { block =>
        blockchainReader.getReceiptsByHash(block.header.hash).foreach { receipts =>
          notifyMatchingLogs(block, receipts, removed = false)
        }
      }
  }

  private def notifyMatchingLogs(block: Block, receipts: Seq[Receipt], removed: Boolean): Unit = {
    // Ask the subscription manager for all log subscriptions, then filter locally
    import org.apache.pekko.pattern.ask
    import org.apache.pekko.util.Timeout
    import scala.concurrent.duration._
    implicit val timeout: Timeout = Timeout(5.seconds)

    (subscriptionManager ? GetSubscriptionsOfType(SubscriptionType.Logs))
      .mapTo[SubscriptionsOfType]
      .foreach { response =>
        response.subscriptions.foreach {
          case logsSub: LogsSubscription =>
            val matchingLogs = getLogsForSubscription(block, receipts, logsSub.address, logsSub.topics)
            matchingLogs.foreach { txLog =>
              val serialized = SubscriptionJsonSerializers.serializeTxLog(txLog, removed)
              subscriptionManager ! NotifySubscription(logsSub.id, serialized)
            }
          case _ => // ignore non-logs subscriptions
        }
      }
  }

  /** Extract matching logs from a block — same logic as FilterManager.getLogsFromBlock(). */
  private def getLogsForSubscription(
      block: Block,
      receipts: Seq[Receipt],
      address: Option[Address],
      topics: Seq[Seq[ByteString]]
  ): Seq[TxLog] = {
    val bytesToCheckInBloomFilter = address.map(a => Seq(a.bytes)).getOrElse(Nil) ++ topics.flatten

    receipts.zipWithIndex.foldLeft(Seq.empty[TxLog]) { case (logsSoFar, (receipt, txIndex)) =>
      if (
        bytesToCheckInBloomFilter.isEmpty || BloomFilter.containsAnyOf(
          receipt.logsBloomFilter,
          bytesToCheckInBloomFilter
        )
      ) {
        logsSoFar ++ receipt.logs.zipWithIndex
          .filter { case (logEntry, _) =>
            address.forall(_ == logEntry.loggerAddress) && topicsMatch(logEntry.logTopics, topics)
          }
          .map { case (logEntry, logIndex) =>
            val tx = block.body.transactionList(txIndex)
            TxLog(
              logIndex = logIndex,
              transactionIndex = txIndex,
              transactionHash = tx.hash,
              blockHash = block.header.hash,
              blockNumber = block.header.number,
              address = logEntry.loggerAddress,
              data = logEntry.data,
              topics = logEntry.logTopics
            )
          }
      } else logsSoFar
    }
  }

  private def topicsMatch(logTopics: Seq[ByteString], filterTopics: Seq[Seq[ByteString]]): Boolean =
    logTopics.size >= filterTopics.size &&
      filterTopics.zip(logTopics).forall { case (filter, log) => filter.isEmpty || filter.contains(log) }
}

object LogsSubscriptionService {
  def props(subscriptionManager: ActorRef, blockchainReader: BlockchainReader): Props =
    Props(new LogsSubscriptionService(subscriptionManager, blockchainReader))
}
