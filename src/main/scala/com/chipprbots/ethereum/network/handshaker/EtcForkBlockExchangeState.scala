package com.chipprbots.ethereum.network.handshaker

import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.network.EtcPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.EtcPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.ForkResolver
import com.chipprbots.ethereum.network.handshaker.Handshaker.NextMessage
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETH62.{BlockHeaders => ETH62BlockHeaders}
import com.chipprbots.ethereum.network.p2p.messages.ETH62.{GetBlockHeaders => ETH62GetBlockHeaders}
import com.chipprbots.ethereum.network.p2p.messages.ETH66
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{BlockHeaders => ETH66BlockHeaders}
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{GetBlockHeaders => ETH66GetBlockHeaders}
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect
import com.chipprbots.ethereum.utils.Logger

case class EtcForkBlockExchangeState(
    handshakerConfiguration: EtcHandshakerConfiguration,
    forkResolver: ForkResolver,
    remoteStatus: RemoteStatus
) extends InProgressState[PeerInfo]
    with Logger {

  import handshakerConfiguration._

  def nextMessage: NextMessage = {
    val getBlockHeadersMsg: MessageSerializable =
      if (Capability.usesRequestId(remoteStatus.capability))
        ETH66GetBlockHeaders(ETH66.nextRequestId, Left(forkResolver.forkBlockNumber), maxHeaders = 1, skip = 0, reverse = false)
      else
        ETH62GetBlockHeaders(Left(forkResolver.forkBlockNumber), maxHeaders = 1, skip = 0, reverse = false)

    NextMessage(
      messageToSend = getBlockHeadersMsg,
      timeout = peerConfiguration.waitForChainCheckTimeout
    )
  }

  private def processForkBlockHeaders(blockHeaders: Seq[BlockHeader]): HandshakerState[PeerInfo] = {
    val forkBlockHeaderOpt = blockHeaders.find(_.number == forkResolver.forkBlockNumber)

    forkBlockHeaderOpt match {
      case Some(forkBlockHeader) =>
        val fork = forkResolver.recognizeFork(forkBlockHeader)

        log.debug("Peer is running the {} fork", fork)

        if (forkResolver.isAccepted(fork)) {
          log.debug("Fork is accepted")
          // setting maxBlockNumber to 0, as we do not know best block number yet
          ConnectedState(PeerInfo.withForkAccepted(remoteStatus))
        } else {
          log.debug("Fork is not accepted")
          DisconnectedState[PeerInfo](Disconnect.Reasons.UselessPeer)
        }

      case None =>
        log.debug("Peer did not respond with fork block header")
        ConnectedState(PeerInfo.withNotForkAccepted(remoteStatus))
    }
  }

  def applyResponseMessage: PartialFunction[Message, HandshakerState[PeerInfo]] = {
    case ETH62BlockHeaders(blockHeaders)    => processForkBlockHeaders(blockHeaders)
    case ETH66BlockHeaders(_, blockHeaders) => processForkBlockHeaders(blockHeaders)
  }

  private def createBlockHeaderResponse(requestId: BigInt, header: Option[BlockHeader]): MessageSerializable =
    if (Capability.usesRequestId(remoteStatus.capability))
      ETH66BlockHeaders(requestId, header.toSeq)
    else
      ETH62BlockHeaders(header.toSeq)

  override def respondToRequest(receivedMessage: Message): Option[MessageSerializable] = receivedMessage match {

    case ETH62GetBlockHeaders(Left(number), numHeaders, _, _)
        if number == forkResolver.forkBlockNumber && numHeaders == 1 =>
      log.debug("Received request for fork block")
      Some(createBlockHeaderResponse(0, blockchainReader.getBlockHeaderByNumber(number)))

    case ETH66GetBlockHeaders(requestId, Left(number), numHeaders, _, _)
        if number == forkResolver.forkBlockNumber && numHeaders == 1 =>
      log.debug("Received request for fork block")
      Some(createBlockHeaderResponse(requestId, blockchainReader.getBlockHeaderByNumber(number)))

    case _ => None

  }

  def processTimeout: HandshakerState[PeerInfo] =
    DisconnectedState(Disconnect.Reasons.TimeoutOnReceivingAMessage)

}
