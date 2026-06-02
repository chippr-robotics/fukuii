package com.chipprbots.ethereum.transactions

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorLogging
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Props
import org.apache.pekko.dispatch.BoundedMessageQueueSemantics
import org.apache.pekko.dispatch.RequiresMessageQueue
import cats.effect.IO
import cats.effect.unsafe.IORuntime

import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.Subscribe
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.AnnounceTransactions
import com.chipprbots.ethereum.transactions.SignedTransactionsFilterActor.ProperSignedTransactions
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

class SignedTransactionsFilterActor(pendingTransactionsManager: ActorRef, peerEventBus: ActorRef)
    extends Actor
    with ActorLogging
    with RequiresMessageQueue[BoundedMessageQueueSemantics] {

  implicit val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig
  implicit private val ioRuntime: IORuntime = IORuntime.global

  private val chunkedRecoveryThreshold = 256
  private val recoveryChunkSize = SignedTransaction.batchSize
  private var nextRecoveryId: Long = 0L
  private var recoveries: Map[Long, SignedTransactionsFilterActor.RecoveryState] = Map.empty

  peerEventBus ! Subscribe(MessageClassifier(Set(Codes.SignedTransactionsCode), PeerSelector.AllPeers))

  override def receive: Receive = {
    case MessageFromPeer(SignedTransactions(newTransactions), peerId) =>
      if (newTransactions.size >= chunkedRecoveryThreshold) {
        val statelessValid = SignedTransactionWithSender.getStatelessValidTransactions(newTransactions)
        if (statelessValid.nonEmpty)
          pendingTransactionsManager ! AnnounceTransactions(statelessValid, peerId)
        recoverLargeBatch(statelessValid, peerId)
      } else {
        recoverSmallBatch(newTransactions, peerId)
      }

    case SignedTransactionsFilterActor.RecoveredChunk(recoveryId, chunkIndex, transactions) =>
      val updated = recoveries.get(recoveryId).map { state =>
        state.copy(bufferedChunks = state.bufferedChunks.updated(chunkIndex, transactions))
      }
      updated.foreach { state =>
        recoveries = recoveries.updated(recoveryId, state)
        flushRecoveredChunks(recoveryId)
      }

    case SignedTransactionsFilterActor.RecoveryFailed(recoveryId, chunkIndex, reason) =>
      log.debug("Failed to recover sender batch {} chunk {}: {}", recoveryId, chunkIndex, reason.toString)
      self ! SignedTransactionsFilterActor.RecoveredChunk(recoveryId, chunkIndex, Set.empty)
  }

  private def recoverSmallBatch(
      newTransactions: Seq[com.chipprbots.ethereum.domain.SignedTransaction],
      peerId: PeerId
  ): Unit =
    IO {
      SignedTransactionWithSender.getSignedTransactions(newTransactions).toSet
    }.attempt
      .map {
        case Right(correctTransactions) =>
          if (correctTransactions.nonEmpty)
            pendingTransactionsManager ! ProperSignedTransactions(correctTransactions, peerId)
        case Left(reason) =>
          log.debug(
            "Failed to recover {} signed transactions from peer {}: {}",
            newTransactions.size,
            peerId,
            reason.toString
          )
      }
      .unsafeRunAndForget()

  private def recoverLargeBatch(
      newTransactions: Seq[com.chipprbots.ethereum.domain.SignedTransaction],
      peerId: PeerId
  ): Unit = {
    val chunks = newTransactions
      .grouped(recoveryChunkSize)
      .zipWithIndex
      .map { case (chunk, index) =>
        index -> chunk.toVector
      }
      .toVector
    val recoveryId = nextRecoveryId
    nextRecoveryId += 1
    recoveries = recoveries.updated(
      recoveryId,
      SignedTransactionsFilterActor.RecoveryState(
        peerId,
        nextChunkToEmit = 0,
        totalChunks = chunks.size,
        Map.empty
      )
    )

    val parallelism = math.min(Runtime.getRuntime.availableProcessors, chunks.size).max(1)
    IO.parTraverseN(parallelism)(chunks) { case (chunkIndex, chunk) =>
      IO {
        val recovered = SignedTransactionWithSender.getSignedTransactionsSequential(chunk).toSet
        self ! SignedTransactionsFilterActor.RecoveredChunk(recoveryId, chunkIndex, recovered)
      }.handleErrorWith { reason =>
        IO(self ! SignedTransactionsFilterActor.RecoveryFailed(recoveryId, chunkIndex, reason))
      }
    }.void
      .unsafeRunAndForget()
  }

  private def flushRecoveredChunks(recoveryId: Long): Unit =
    recoveries.get(recoveryId).foreach { initialState =>
      var state = initialState
      var keepGoing = true
      while (keepGoing)
        state.bufferedChunks.get(state.nextChunkToEmit) match {
          case Some(transactions) =>
            if (transactions.nonEmpty) pendingTransactionsManager ! ProperSignedTransactions(transactions, state.peerId)
            state = state.copy(
              nextChunkToEmit = state.nextChunkToEmit + 1,
              bufferedChunks = state.bufferedChunks - state.nextChunkToEmit
            )
          case None =>
            keepGoing = false
        }

      if (state.nextChunkToEmit >= state.totalChunks) recoveries -= recoveryId
      else recoveries = recoveries.updated(recoveryId, state)
    }
}

object SignedTransactionsFilterActor {
  def props(pendingTransactionsManager: ActorRef, peerEventBus: ActorRef): Props =
    Props(new SignedTransactionsFilterActor(pendingTransactionsManager, peerEventBus))

  case class ProperSignedTransactions(signedTransactions: Set[SignedTransactionWithSender], peerId: PeerId)
  private case class RecoveredChunk(
      recoveryId: Long,
      chunkIndex: Int,
      transactions: Set[SignedTransactionWithSender]
  )
  private case class RecoveryFailed(recoveryId: Long, chunkIndex: Int, reason: Throwable)
  private case class RecoveryState(
      peerId: PeerId,
      nextChunkToEmit: Int,
      totalChunks: Int,
      bufferedChunks: Map[Int, Set[SignedTransactionWithSender]]
  )
}
