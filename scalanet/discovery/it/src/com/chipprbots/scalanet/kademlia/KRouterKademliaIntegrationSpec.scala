package com.chipprbots.scalanet.kademlia

import java.security.SecureRandom
import cats.effect.Resource
import com.chipprbots.scalanet.NetUtils
import com.chipprbots.scalanet.kademlia.KNetwork.KNetworkScalanetImpl
import com.chipprbots.scalanet.kademlia.KRouter.NodeRecord
import com.chipprbots.scalanet.peergroup.InetMultiAddress
import cats.effect.IO
import com.chipprbots.scalanet.peergroup.PeerGroup
import scodec.bits.BitVector
import com.chipprbots.scalanet.discovery.crypto.PrivateKey

abstract class KRouterKademliaIntegrationSpec(peerGroupName: String)
    extends KademliaIntegrationSpec(s"KRouter and $peerGroupName") {

  override type PeerRecord = NodeRecord[InetMultiAddress]

  override def generatePeerRecordWithKey: (PeerRecord, PrivateKey) = {
    val randomGen = new SecureRandom()
    val testBitLength = 16
    val address = InetMultiAddress(NetUtils.aRandomAddress())
    val id = KBuckets.generateRandomId(testBitLength, randomGen)
    val privateKey = PrivateKey(BitVector.empty) // Not using cryptography.
    NodeRecord(id, address, address) -> privateKey
  }

  override def makeXorOrdering(baseId: BitVector): Ordering[NodeRecord[InetMultiAddress]] =
    XorNodeOrdering(baseId)

  import com.chipprbots.scalanet.codec.DefaultCodecs._
  import com.chipprbots.scalanet.kademlia.codec.DefaultCodecs._
  implicit val codec = implicitly[scodec.Codec[KMessage[InetMultiAddress]]]

  class KRouterTestNode(
      override val self: PeerRecord,
      router: KRouter[InetMultiAddress]
  ) extends TestNode {
    override def getPeers: IO[Seq[NodeRecord[InetMultiAddress]]] = {
      router.nodeRecords.map(_.values.toSeq)
    }
  }

  def makePeerGroup(
      selfRecord: NodeRecord[InetMultiAddress]
  ): Resource[Task, PeerGroup[InetMultiAddress, KMessage[InetMultiAddress]]]

  private def startRouter(
      selfRecord: NodeRecord[InetMultiAddress],
      routerConfig: KRouter.Config[InetMultiAddress]
  ): Resource[Task, KRouter[InetMultiAddress]] = {
    for {
      peerGroup <- makePeerGroup(selfRecord)
      kademliaNetwork = new KNetworkScalanetImpl(peerGroup)
      router <- Resource.liftF(KRouter.startRouterWithServerPar(routerConfig, kademliaNetwork))
    } yield router
  }

  override def startNode(
      selfRecordWithKey: (PeerRecord, PrivateKey),
      initialNodes: Set[PeerRecord],
      testConfig: TestNodeKademliaConfig
  ): Resource[Task, TestNode] = {
    val (selfRecord, _) = selfRecordWithKey
    val routerConfig = KRouter.Config(
      selfRecord,
      initialNodes,
      alpha = testConfig.alpha,
      k = testConfig.k,
      serverBufferSize = testConfig.serverBufferSize,
      refreshRate = testConfig.refreshRate
    )
    for {
      router <- startRouter(selfRecord, routerConfig)
    } yield new KRouterTestNode(selfRecord, router)
  }

}

class StaticUDPKRouterKademliaIntegrationSpec extends KRouterKademliaIntegrationSpec("StaticUDP") {
  import com.chipprbots.scalanet.peergroup.udp.StaticUDPPeerGroup

  override def makePeerGroup(
      selfRecord: NodeRecord[InetMultiAddress]
  ) = {
    val udpConfig = StaticUDPPeerGroup.Config(selfRecord.routingAddress.inetSocketAddress, channelCapacity = 100)
    StaticUDPPeerGroup[KMessage[InetMultiAddress]](udpConfig)
  }
}

class DynamicUDPKRouterKademliaIntegrationSpec extends KRouterKademliaIntegrationSpec("DynamicUDP") {
  import com.chipprbots.scalanet.peergroup.udp.DynamicUDPPeerGroup

  override def makePeerGroup(
      selfRecord: NodeRecord[InetMultiAddress]
  ) = {
    val udpConfig = DynamicUDPPeerGroup.Config(selfRecord.routingAddress.inetSocketAddress, channelCapacity = 100)
    DynamicUDPPeerGroup[KMessage[InetMultiAddress]](udpConfig)
  }
}
