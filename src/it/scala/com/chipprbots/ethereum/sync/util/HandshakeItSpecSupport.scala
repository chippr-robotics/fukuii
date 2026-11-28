package com.chipprbots.ethereum.sync.util

import cats.effect.IO

import org.scalatest.Assertion
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.sync.util.RegularSyncItSpecUtils.FakePeer
import com.chipprbots.ethereum.utils.Config

/** What the P2P integration specs share: a dial that is waited out on the peers' own state (never on a clock), and what
  * a completed handshake looks like from each end.
  */
trait HandshakeItSpecSupport:
  self: Matchers =>

  /** `dialer` dials `listener` and the call returns once both sides hold the other as a handshaked peer: the
    * PeerManagers' state, not a sleep. Returns what each side holds.
    */
  def dialAndAwaitHandshake(dialer: FakePeer, listener: FakePeer): IO[(Peer, Peer)] =
    for
      _ <- dialer.connectToPeers(Set(listener.node))
      outgoing <- dialer.waitForHandshakedPeers(1)
      incoming <- listener.waitForHandshakedPeers(1)
    yield (outgoing.head, incoming.head)

  /** `outgoing` is what a dialer holds of the node at `listener`, `incoming` what that node holds of the dialer. */
  def assertOutgoingAndIncoming(outgoing: Peer, incoming: Peer, listener: FakePeer): Assertion =
    outgoing.incomingConnection shouldBe false
    outgoing.remoteAddress.getPort shouldBe listener.listenAddress.getPort
    incoming.incomingConnection shouldBe true

  /** What `holder`'s network layer recorded of its single handshaked peer. */
  def singlePeerInfo(holder: FakePeer): IO[PeerInfo] =
    holder.handshakedPeerInfos().map { infos =>
      infos should have size 1
      infos.values.head
    }

  /** What `info` recorded of the chain position of `of` is what `of` had when it sent its STATUS: the best block, and
    * the difficulty or the latest block number, whichever that protocol version carries (eth/68 the total difficulty,
    * eth/69 and later the latest block and no difficulty: the receiver works that out itself).
    */
  def assertChainPosition(info: PeerInfo, of: FakePeer): Assertion =
    val best = of.blockchainReader.getBestBlock.get
    info.remoteStatus.bestHash shouldBe best.hash.value
    info.remoteStatus.capability match
      case Capability.ETH68 =>
        info.remoteStatus.chainWeight.totalDifficulty shouldBe
          of.blockchainReader.getChainWeightByHash(best.hash).get.totalDifficulty
      case _ =>
        info.remoteStatus.latestBlock shouldBe Some(best.number.value)

  /** The STATUS of `of`, as it stood at the handshake, against what `info` recorded of it: the network, the genesis,
    * the chain position, and a version the node supports.
    */
  def assertStatusOf(info: PeerInfo, of: FakePeer): Assertion =
    info.remoteStatus.genesisHash shouldBe
      of.blockchainReader.getBlockByNumber(of.blockchainReader.getBestBranch, 0).get.hash.value
    info.remoteStatus.networkId shouldBe of.peerConf.networkId
    assertChainPosition(info, of)
    Config.supportedCapabilities should contain(info.remoteStatus.capability)
