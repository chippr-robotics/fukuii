package com.chipprbots.ethereum.network.handshaker

import java.util.concurrent.atomic.AtomicReference

import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain.Blockchain
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.network.EtcPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.ForkResolver
import com.chipprbots.ethereum.network.PeerManagerActor.PeerConfiguration
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.NodeStatus

case class EtcHandshaker private (
    handshakerState: HandshakerState[PeerInfo],
    handshakerConfiguration: EtcHandshakerConfiguration
) extends Handshaker[PeerInfo] {

  protected def copy(handshakerState: HandshakerState[PeerInfo]): Handshaker[PeerInfo] =
    EtcHandshaker(handshakerState, handshakerConfiguration)

}

object EtcHandshaker {

  def apply(handshakerConfiguration: EtcHandshakerConfiguration): EtcHandshaker = {
    val initialState = EtcHelloExchangeState(handshakerConfiguration)
    EtcHandshaker(initialState, handshakerConfiguration)
  }

}

trait EtcHandshakerConfiguration {
  val nodeStatusHolder: AtomicReference[NodeStatus]
  val blockchain: Blockchain
  val blockchainReader: BlockchainReader
  val appStateStorage: AppStateStorage
  val peerConfiguration: PeerConfiguration
  val forkResolverOpt: Option[ForkResolver]
  val blockchainConfig: BlockchainConfig
}
