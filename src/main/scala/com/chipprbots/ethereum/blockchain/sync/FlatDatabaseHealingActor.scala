package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props}
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import com.chipprbots.ethereum.db.storage.{AppStateStorage, FlatAccountStorage}
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.messages.SNAP.AccountRange
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange.GetAccountRangeEnc
import com.chipprbots.ethereum.blockchain.sync.snap.MerkleProofVerifier
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

/** Post-recovery flat database integrity verifier (Phase 7 A7).
  *
  * After BytecodeRecoveryActor + StorageRecoveryActor complete, this actor issues 256 GetAccountRange probes (one per
  * first-byte segment) to detect accounts that the MPT contains but that flat storage is missing. Each probe covers one
  * 1/256 shard of the 32-byte hash space: segment i spans [i||00..00, i||FF..FF].
  *
  * The SNAP AccountRange response includes a Merkle range proof. verifyAccountRange() confirms the returned accounts
  * are a complete, valid subset of the trie. Any account hash absent from FlatAccountStorage is written immediately.
  *
  * On clean SNAP sync (no crash), flatHealingDone() is written atomically with snapSyncDone() in SNAPSyncController so
  * this actor is skipped entirely on normal restarts. The 256-probe scan only runs on the crash-recovery path.
  *
  * Cursor: segment index (0-255) persisted as an integer after each successful probe, cleared atomically with the
  * done-flag on completion. A crash mid-scan resumes from the last committed segment.
  */
class FlatDatabaseHealingActor(
    stateRoot: ByteString,
    flatAccountStorage: FlatAccountStorage,
    appStateStorage: AppStateStorage,
    networkPeerManager: ActorRef,
    syncController: ActorRef
) extends Actor
    with ActorLogging {

  import FlatDatabaseHealingActor._

  private val NumSegments = 256
  private val ProbeResponseBytes = 65536L
  private val ProbeTimeoutDuration: FiniteDuration = 30.seconds

  private var currentSegment: Int = appStateStorage.getFlatHealingCursor().getOrElse(0)
  private var currentPeer: Option[Peer] = None
  private var pendingRequestId: Option[BigInt] = None
  private var probeTimeout: Option[Cancellable] = None
  private var requestCounter: Long = 0L

  override def preStart(): Unit =
    if (appStateStorage.isFlatHealingDone()) {
      log.info("[FLAT-HEAL] already complete (flag set) — reporting done immediately")
      syncController ! HealingComplete
      context.stop(self)
    } else {
      log.info(
        s"[FLAT-HEAL] starting flat database healing " +
          s"(stateRoot=${stateRoot.take(4).toHex}..., resumeFromSegment=$currentSegment)"
      )
    }

  override def receive: Receive = waitingForPeer

  private def waitingForPeer: Receive = {
    case FlatHealPeerAvailable(peer) =>
      currentPeer = Some(peer)
      sendProbe()
      context.become(probing)

    case _: AccountRange =>
    // discard stale responses while waiting for peer
  }

  private def probing: Receive = {
    case FlatHealPeerAvailable(peer) =>
      currentPeer = Some(peer)

    case msg: AccountRange =>
      pendingRequestId match {
        case Some(expected) if msg.requestId == expected =>
          probeTimeout.foreach(_.cancel())
          probeTimeout = None
          pendingRequestId = None
          handleResponse(msg)
        case _ =>
        // stale response, ignore
      }

    case ProbeTimeout =>
      log.warning(s"[FLAT-HEAL] segment $currentSegment probe timed out — waiting for new peer")
      pendingRequestId = None
      currentPeer = None
      context.become(waitingForPeer)
  }

  private def sendProbe(): Unit = {
    if (currentSegment >= NumSegments) {
      completeHealing()
      return
    }
    currentPeer match {
      case Some(peer) =>
        requestCounter += 1
        val reqId = BigInt(requestCounter)
        pendingRequestId = Some(reqId)
        val startHash = segmentStart(currentSegment)
        val endHash = segmentEnd(currentSegment)
        val probe = GetAccountRange(
          requestId = reqId,
          rootHash = stateRoot,
          startingHash = startHash,
          limitHash = endHash,
          responseBytes = ProbeResponseBytes
        )
        networkPeerManager ! NetworkPeerManagerActor.SendMessage(new GetAccountRangeEnc(probe), peer.id)
        log.debug(s"[FLAT-HEAL] probing segment $currentSegment/255 peer=${peer.id.value}")
        probeTimeout = Some(
          context.system.scheduler.scheduleOnce(ProbeTimeoutDuration, self, ProbeTimeout)(context.dispatcher, self)
        )
      case None =>
        context.become(waitingForPeer)
    }
  }

  private def handleResponse(msg: AccountRange): Unit = {
    val startHash = segmentStart(currentSegment)
    val endHash = segmentEnd(currentSegment)
    MerkleProofVerifier(stateRoot).verifyAccountRange(msg.accounts, msg.proof, startHash, endHash) match {
      case Left(err) =>
        log.warning(s"[FLAT-HEAL] segment $currentSegment proof verification failed: $err — retrying with new peer")
        currentPeer = None
        context.become(waitingForPeer)

      case Right(_) =>
        val missing = msg.accounts.filter { case (hash, _) => flatAccountStorage.getAccount(hash).isEmpty }
        if (missing.nonEmpty) {
          val toWrite = missing.map { case (hash, account) =>
            (hash, ByteString.fromArrayUnsafe(Account.accountSerializer.toBytes(account)))
          }
          flatAccountStorage.putAccountsBatch(toWrite).commit()
          log.info(s"[FLAT-HEAL] segment $currentSegment: repaired ${missing.size} missing accounts")
        }
        advanceSegment()
    }
  }

  private def advanceSegment(): Unit = {
    currentSegment += 1
    if (currentSegment >= NumSegments) {
      completeHealing()
    } else {
      appStateStorage.putFlatHealingCursor(currentSegment).commit()
      sendProbe()
    }
  }

  private def completeHealing(): Unit = {
    appStateStorage.clearFlatHealingCursor().and(appStateStorage.flatHealingDone()).commit()
    log.info(s"[FLAT-HEAL] complete — all $NumSegments segments verified and repaired")
    syncController ! HealingComplete
    context.stop(self)
  }

  private def segmentStart(i: Int): ByteString = {
    val bytes = Array.fill[Byte](32)(0)
    bytes(0) = i.toByte
    ByteString(bytes)
  }

  private def segmentEnd(i: Int): ByteString = {
    val bytes = Array.fill[Byte](32)(0xff.toByte)
    bytes(0) = i.toByte
    ByteString(bytes)
  }
}

object FlatDatabaseHealingActor {

  case class FlatHealPeerAvailable(peer: Peer)
  case object HealingComplete
  case object HealingFailed
  private case object ProbeTimeout

  def props(
      stateRoot: ByteString,
      flatAccountStorage: FlatAccountStorage,
      appStateStorage: AppStateStorage,
      networkPeerManager: ActorRef,
      syncController: ActorRef
  ): Props =
    Props(
      new FlatDatabaseHealingActor(
        stateRoot = stateRoot,
        flatAccountStorage = flatAccountStorage,
        appStateStorage = appStateStorage,
        networkPeerManager = networkPeerManager,
        syncController = syncController
      )
    )
}
