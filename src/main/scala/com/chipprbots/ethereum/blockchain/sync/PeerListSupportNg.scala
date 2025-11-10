package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorLogging
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Scheduler

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import com.chipprbots.ethereum.network.EtcPeerManagerActor
import com.chipprbots.ethereum.network.EtcPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.Subscribe
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.PeerDisconnectedClassifier
import com.chipprbots.ethereum.network.PeerEventBusActor.Unsubscribe
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.utils.Config.SyncConfig

trait PeerListSupportNg { self: Actor with ActorLogging =>
  import PeerListSupportNg._
  import Blacklist._

  implicit private val ec: ExecutionContext = context.dispatcher

  protected val bigIntReverseOrdering: Ordering[BigInt] = Ordering[BigInt].reverse

  def etcPeerManager: ActorRef
  def peerEventBus: ActorRef
  def blacklist: Blacklist
  def syncConfig: SyncConfig
  def scheduler: Scheduler

  protected var handshakedPeers: Map[PeerId, PeerWithInfo] = Map.empty

  scheduler.scheduleWithFixedDelay(
    0.seconds,
    syncConfig.peersScanInterval,
    etcPeerManager,
    EtcPeerManagerActor.GetHandshakedPeers
  )(ec, context.self)

  def handlePeerListMessages: Receive = {
    case EtcPeerManagerActor.HandshakedPeers(peers) => updatePeers(peers)
    case PeerDisconnected(peerId)                   => removePeerById(peerId)
  }

  def peersToDownloadFrom: Map[PeerId, PeerWithInfo] = {
    val available = handshakedPeers.filterNot { case (peerId, _) =>
      val isBlacklisted = blacklist.isBlacklisted(peerId)
      if (isBlacklisted) {
        log.debug("Peer {} is blacklisted and excluded from download peers", peerId)
      }
      isBlacklisted
    }
    log.debug("peersToDownloadFrom: {} available out of {} handshaked peers", available.size, handshakedPeers.size)
    available
  }

  def getPeerById(peerId: PeerId): Option[Peer] = handshakedPeers.get(peerId).map(_.peer)

  def getPeerWithHighestBlock: Option[PeerWithInfo] =
    peersToDownloadFrom.values.toList.sortBy(_.peerInfo.maxBlockNumber)(bigIntReverseOrdering).headOption

  def blacklistIfHandshaked(peerId: PeerId, duration: FiniteDuration, reason: BlacklistReason): Unit =
    handshakedPeers.get(peerId) match {
      case Some(peerWithInfo) =>
        log.debug("Blacklisting peer {} ({}) for {} ms. Reason: {}", 
          peerId, peerWithInfo.peer.remoteAddress, duration.toMillis, reason)
        blacklist.add(peerId, duration, reason)
      case None =>
        log.debug("Attempted to blacklist non-handshaked peer {}", peerId)
    }

  private def updatePeers(peers: Map[Peer, PeerInfo]): Unit = {
    val updated = peers.map { case (peer, peerInfo) =>
      (peer.id, PeerWithInfo(peer, peerInfo))
    }
    
    val newPeers = updated.filterNot(p => handshakedPeers.keySet.contains(p._1))
    if (newPeers.nonEmpty) {
      log.debug("Adding {} new handshaked peers", newPeers.size)
      newPeers.foreach { case (peerId, peerWithInfo) =>
        log.debug("New peer {} ({}) - ready: {}, maxBlock: {}, chainWeight: {}", 
          peerId, peerWithInfo.peer.remoteAddress, peerWithInfo.peerInfo.forkAccepted,
          peerWithInfo.peerInfo.maxBlockNumber, peerWithInfo.peerInfo.chainWeight)
        peerEventBus ! Subscribe(PeerDisconnectedClassifier(PeerSelector.WithId(peerId)))
      }
    }
    
    if (handshakedPeers.size != updated.size) {
      log.debug("Handshaked peers changed: {} -> {} peers", handshakedPeers.size, updated.size)
    }
    
    handshakedPeers = updated
  }

  private def removePeerById(peerId: PeerId): Unit =
    if (handshakedPeers.keySet.contains(peerId)) {
      val peerInfo = handshakedPeers(peerId)
      log.debug("Removing disconnected peer {} ({})", peerId, peerInfo.peer.remoteAddress)
      peerEventBus ! Unsubscribe(PeerDisconnectedClassifier(PeerSelector.WithId(peerId)))
      blacklist.remove(peerId)
      log.debug("Removed peer {} from blacklist", peerId)
      handshakedPeers = handshakedPeers - peerId
      log.debug("Remaining handshaked peers: {}", handshakedPeers.size)
    } else {
      log.debug("Attempted to remove non-existent peer {}", peerId)
    }

}

object PeerListSupportNg {
  final case class PeerWithInfo(peer: Peer, peerInfo: PeerInfo)
}
