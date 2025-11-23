package com.chipprbots.ethereum.network.handshaker

import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.network.EtcPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.EtcPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.handshaker.Handshaker.NextMessage
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect.Reasons
import com.chipprbots.ethereum.utils.Logger

trait EtcNodeStatusExchangeState[T <: Message] extends InProgressState[PeerInfo] with Logger {

  val handshakerConfiguration: EtcHandshakerConfiguration

  import handshakerConfiguration._

  def nextMessage: NextMessage =
    NextMessage(
      messageToSend = createStatusMsg(),
      timeout = peerConfiguration.waitForStatusTimeout
    )

  def processTimeout: HandshakerState[PeerInfo] = {
    log.debug("Timeout while waiting status")
    DisconnectedState(Disconnect.Reasons.TimeoutOnReceivingAMessage)
  }

  protected def applyRemoteStatusMessage: RemoteStatus => HandshakerState[PeerInfo] = { (status: RemoteStatus) =>
    log.debug("Peer returned status ({})", status)

    val validNetworkID = status.networkId == handshakerConfiguration.peerConfiguration.networkId
    val validGenesisHash = status.genesisHash == blockchainReader.genesisHeader.hash

    if (validNetworkID && validGenesisHash) {
      forkResolverOpt match {
        case Some(forkResolver) =>
          EtcForkBlockExchangeState(handshakerConfiguration, forkResolver, status)
        case None =>
          ConnectedState(PeerInfo.withForkAccepted(status))
      }
    } else
      DisconnectedState(Reasons.DisconnectRequested)
  }

  protected def getBestBlockHeader(): BlockHeader = {
    val bestBlockNumber = blockchainReader.getBestBlockNumber()
    blockchainReader.getBlockHeaderByNumber(bestBlockNumber).getOrElse(blockchainReader.genesisHeader)
  }

  protected def createStatusMsg(): MessageSerializable

}
