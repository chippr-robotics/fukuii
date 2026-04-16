package com.chipprbots.ethereum.network.handshaker

import java.util.concurrent.atomic.AtomicReference

import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain.Blockchain
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.ForkResolver
import com.chipprbots.ethereum.network.PeerManagerActor.PeerConfiguration
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.NodeStatus

case class NetworkHandshaker private (
    handshakerState: HandshakerState[PeerInfo],
    handshakerConfiguration: NetworkHandshakerConfiguration
) extends Handshaker[PeerInfo] {

  protected def copy(handshakerState: HandshakerState[PeerInfo]): Handshaker[PeerInfo] =
    NetworkHandshaker(handshakerState, handshakerConfiguration)

}

object NetworkHandshaker {

  def apply(handshakerConfiguration: NetworkHandshakerConfiguration): NetworkHandshaker = {
    val initialState = EtcHelloExchangeState(handshakerConfiguration)
    NetworkHandshaker(initialState, handshakerConfiguration)
  }

}

trait NetworkHandshakerConfiguration {
  val nodeStatusHolder: AtomicReference[NodeStatus]
  val blockchain: Blockchain
  val blockchainReader: BlockchainReader
  val appStateStorage: AppStateStorage
  val peerConfiguration: PeerConfiguration
  val forkResolverOpt: Option[ForkResolver]
  val blockchainConfig: BlockchainConfig
}
