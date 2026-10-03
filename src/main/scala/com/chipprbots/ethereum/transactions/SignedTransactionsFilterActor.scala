package com.chipprbots.ethereum.transactions

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.network.PeerEventBusActor.Command as PeerEventBusCommand
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscribeCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.AnnounceTransactions
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.ProperSignedTransactions
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

object SignedTransactionsFilterActor:

  sealed trait Command

  // Inbound: a signed-transactions wire message from a peer, translated from PeerEvent by the message adapter
  private[transactions] case class PeerSignedTransactions(txs: SignedTransactions, peerId: PeerId) extends Command

  // Self-sends for chunked async recovery
  private[transactions] case class RecoveredChunk(
      recoveryId: Long,
      chunkIndex: Int,
      transactions: Set[SignedTransactionWithSender]
  ) extends Command

  private[transactions] case class RecoveryFailed(
      recoveryId: Long,
      chunkIndex: Int,
      reason: Throwable
  ) extends Command

  case class RecoveryState(
      peerId: PeerId,
      nextChunkToEmit: Int,
      totalChunks: Int,
      bufferedChunks: Map[Int, Set[SignedTransactionWithSender]]
  )

  /** @param headTimestamp
    *   the chain head's timestamp: the stateless filter admits a transaction under the rules of the fork active there.
    *   Read only on ETH-family chains, and at most once per inbound message.
    */
  def apply(
      pendingTransactionsManager: ActorRef[PendingTransactionsManager.Command],
      peerEventBus: ActorRef[PeerEventBusCommand],
      headTimestamp: () => Timestamp
  ): Behavior[Command] = Behaviors.setup { context =>

    given blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig
    given ioRuntime: IORuntime = IORuntime.global

    val chunkedRecoveryThreshold = 256
    val recoveryChunkSize = SignedTransaction.batchSize

    var nextRecoveryId: Long = 0L
    var recoveries: Map[Long, RecoveryState] = Map.empty

    // Message adapter: bridges PeerEvent.MessageFromPeer → Typed Command.
    // The Typed peerEventBus registers the subscriber as a TypedActorRef[PeerEvent].
    val peerMsgAdapter: ActorRef[PeerEvent] =
      context.messageAdapter[PeerEvent] {
        case msg: MessageFromPeer =>
          msg.message match
            case txs: SignedTransactions => PeerSignedTransactions(txs, msg.peerId)
            case _                       => PeerSignedTransactions(SignedTransactions(Nil), msg.peerId)
        case e => throw new MatchError(s"unexpected PeerEvent from bus: $e")
      }

    peerEventBus ! SubscribeCmd(
      MessageClassifier(Set(Codes.SignedTransactionsCode), PeerSelector.AllPeers),
      peerMsgAdapter
    )

    def recoverSmallBatch(newTransactions: Seq[SignedTransaction], peerId: PeerId): Unit =
      IO {
        SignedTransactionWithSender.getSignedTransactions(newTransactions, headTimestamp()).toSet
      }.attempt
        .map {
          case Right(correctTransactions) =>
            if correctTransactions.nonEmpty then
              pendingTransactionsManager ! ProperSignedTransactions(correctTransactions, peerId)
          case Left(reason) =>
            context.log.debug(
              "Failed to recover {} signed transactions from peer {}: {}",
              newTransactions.size,
              peerId,
              reason.toString
            )
        }
        .unsafeRunAndForget()

    def recoverLargeBatch(newTransactions: Seq[SignedTransaction], peerId: PeerId, head: => Timestamp): Unit =
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
        RecoveryState(
          peerId,
          nextChunkToEmit = 0,
          totalChunks = chunks.size,
          Map.empty
        )
      )

      val parallelism = math.min(Runtime.getRuntime.availableProcessors, chunks.size).max(1)
      IO.parTraverseN(parallelism)(chunks) { case (chunkIndex, chunk) =>
        IO {
          val recovered = SignedTransactionWithSender.getSignedTransactionsSequential(chunk, head).toSet
          context.self ! RecoveredChunk(recoveryId, chunkIndex, recovered)
        }.handleErrorWith { reason =>
          IO(context.self ! RecoveryFailed(recoveryId, chunkIndex, reason))
        }
      }.void
        .unsafeRunAndForget()

    def flushRecoveredChunks(recoveryId: Long): Unit =
      recoveries.get(recoveryId).foreach { initialState =>
        var state = initialState
        var keepGoing = true
        while keepGoing do
          state.bufferedChunks.get(state.nextChunkToEmit) match
            case Some(transactions) =>
              if transactions.nonEmpty then
                pendingTransactionsManager ! ProperSignedTransactions(transactions, state.peerId)
              state = state.copy(
                nextChunkToEmit = state.nextChunkToEmit + 1,
                bufferedChunks = state.bufferedChunks - state.nextChunkToEmit
              )
            case None =>
              keepGoing = false

        if state.nextChunkToEmit >= state.totalChunks then recoveries -= recoveryId
        else recoveries = recoveries.updated(recoveryId, state)
      }

    Behaviors.receiveMessage {
      case PeerSignedTransactions(SignedTransactions(newTransactions), peerId) =>
        if newTransactions.size >= chunkedRecoveryThreshold then
          // One head for the whole message, so every chunk is filtered under the same fork. Lazy: ETC never reads it.
          lazy val head = headTimestamp()
          val statelessValid = SignedTransactionWithSender.getStatelessValidTransactions(newTransactions, head)
          if statelessValid.nonEmpty then pendingTransactionsManager ! AnnounceTransactions(statelessValid, peerId)
          recoverLargeBatch(statelessValid, peerId, head)
        else recoverSmallBatch(newTransactions, peerId)
        Behaviors.same

      case RecoveredChunk(recoveryId, chunkIndex, transactions) =>
        val updated = recoveries.get(recoveryId).map { state =>
          state.copy(bufferedChunks = state.bufferedChunks.updated(chunkIndex, transactions))
        }
        updated.foreach { state =>
          recoveries = recoveries.updated(recoveryId, state)
          flushRecoveredChunks(recoveryId)
        }
        Behaviors.same

      case RecoveryFailed(recoveryId, chunkIndex, reason) =>
        context.log.debug("Failed to recover sender batch {} chunk {}: {}", recoveryId, chunkIndex, reason.toString)
        context.self ! RecoveredChunk(recoveryId, chunkIndex, Set.empty)
        Behaviors.same
    }
  }
