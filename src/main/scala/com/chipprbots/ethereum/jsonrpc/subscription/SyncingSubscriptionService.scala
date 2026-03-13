package com.chipprbots.ethereum.jsonrpc.subscription

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Props
import org.apache.pekko.util.Timeout

import scala.concurrent.duration._

import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.jsonrpc.subscription.SubscriptionManager.NotifySubscribers
import com.chipprbots.ethereum.utils.Logger

/** Polls sync status and pushes notifications on status changes.
  *
  * Emulates Besu's SyncingSubscriptionService — tracks last known status,
  * only sends notifications when status transitions (syncing↔synced).
  * Polls every 5s via scheduled tick (same interval as Besu).
  */
class SyncingSubscriptionService(
    subscriptionManager: ActorRef,
    syncController: ActorRef
) extends Actor
    with Logger {

  import context.dispatcher

  implicit private val askTimeout: Timeout = Timeout(10.seconds)

  private var lastSyncing: Boolean = false
  private var pollSchedule: Option[org.apache.pekko.actor.Cancellable] = None

  override def preStart(): Unit = {
    pollSchedule = Some(
      context.system.scheduler.scheduleWithFixedDelay(5.seconds, 5.seconds, self, PollSyncStatus)
    )
  }

  override def postStop(): Unit = {
    pollSchedule.foreach(_.cancel())
  }

  override def receive: Receive = {
    case PollSyncStatus =>
      import org.apache.pekko.pattern.ask
      (syncController ? SyncProtocol.GetStatus)
        .mapTo[SyncProtocol.Status]
        .foreach { status =>
          val currentlySyncing = status.syncing
          if (currentlySyncing != lastSyncing) {
            lastSyncing = currentlySyncing
            val result = status match {
              case SyncProtocol.Status.Syncing(startingBlock, progress, _) =>
                SubscriptionJsonSerializers.serializeSyncStatus(
                  syncing = true,
                  startingBlock = startingBlock,
                  currentBlock = progress.current,
                  highestBlock = progress.target
                )
              case _ =>
                SubscriptionJsonSerializers.serializeSyncStatus(
                  syncing = false,
                  startingBlock = 0,
                  currentBlock = 0,
                  highestBlock = 0
                )
            }
            subscriptionManager ! NotifySubscribers(SubscriptionType.Syncing, result)
          }
        }
  }

  private case object PollSyncStatus
}

object SyncingSubscriptionService {
  def props(subscriptionManager: ActorRef, syncController: ActorRef): Props =
    Props(new SyncingSubscriptionService(subscriptionManager, syncController))
}
