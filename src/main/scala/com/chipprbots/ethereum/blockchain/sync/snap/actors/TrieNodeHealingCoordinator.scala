package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props, SupervisorStrategy, OneForOneStrategy}
import org.apache.pekko.actor.SupervisorStrategy._
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration._

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.network.Peer

/** TrieNodeHealingCoordinator manages trie node healing workers.
  *
  * @param stateRoot
  *   State root hash
  * @param networkPeerManager
  *   Network manager
  * @param requestTracker
  *   Request tracker
  * @param mptStorage
  *   MPT storage
  * @param batchSize
  *   Batch size for healing requests
  * @param snapSyncController
  *   Parent controller
  */
class TrieNodeHealingCoordinator(
    stateRoot: ByteString,
    networkPeerManager: ActorRef,
    requestTracker: SNAPRequestTracker,
    mptStorage: MptStorage,
    batchSize: Int,
    snapSyncController: ActorRef
) extends Actor
    with ActorLogging {

  import Messages._

  private val trieNodeHealer = new TrieNodeHealer(
    stateRoot = stateRoot,
    networkPeerManager = networkPeerManager,
    requestTracker = requestTracker,
    mptStorage = mptStorage,
    batchSize = batchSize
  )

  private val workers = mutable.ArrayBuffer[ActorRef]()
  private val maxWorkers = 8

  private var nodesHealed: Long = 0

  override def preStart(): Unit = {
    log.info("TrieNodeHealingCoordinator starting")
  }

  override val supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1.minute) {
      case _: Exception =>
        log.warning("Healing worker failed, restarting")
        Restart
    }

  override def receive: Receive = {
    case StartTrieNodeHealing(root) =>
      log.info(s"Starting trie node healing for state root ${root.take(8).toHex}")

    case QueueMissingNodes(nodes) =>
      log.info(s"Queuing ${nodes.size} missing nodes for healing")
      trieNodeHealer.queueNodes(nodes)

    case HealingPeerAvailable(peer) =>
      if (!trieNodeHealer.isComplete && workers.size < maxWorkers) {
        val worker = createWorker()
        worker ! FetchTrieNodes(null, peer)
      }

    case HealingTaskComplete(requestId, result) =>
      result match {
        case Right(count) =>
          nodesHealed += count
          log.info(s"Healing task completed: $count nodes")
          self ! HealingCheckCompletion
        case Left(error) =>
          log.warning(s"Healing task failed: $error")
      }

    case HealingCheckCompletion =>
      if (trieNodeHealer.isComplete) {
        log.info("Trie node healing complete!")
        snapSyncController ! SNAPSyncController.StateHealingComplete
      }

    case HealingGetProgress =>
      val stats = trieNodeHealer.statistics
      sender() ! stats
  }

  private def createWorker(): ActorRef = {
    val worker = context.actorOf(
      TrieNodeHealingWorker.props(
        coordinator = self,
        networkPeerManager = networkPeerManager,
        requestTracker = requestTracker,
        trieNodeHealer = trieNodeHealer
      )
    )
    workers += worker
    log.debug(s"Created healing worker, total: ${workers.size}")
    worker
  }
}

object TrieNodeHealingCoordinator {
  def props(
      stateRoot: ByteString,
      networkPeerManager: ActorRef,
      requestTracker: SNAPRequestTracker,
      mptStorage: MptStorage,
      batchSize: Int,
      snapSyncController: ActorRef
  ): Props =
    Props(
      new TrieNodeHealingCoordinator(
        stateRoot,
        networkPeerManager,
        requestTracker,
        mptStorage,
        batchSize,
        snapSyncController
      )
    )
}
