package com.chipprbots.scalanet.discovery.ethereum.v5

import cats.effect.{IO, Ref, Resource, Temporal}
import cats.implicits._
import com.chipprbots.scalanet.discovery.crypto.{PrivateKey, SigAlg}
import com.chipprbots.scalanet.discovery.ethereum.{EthereumNodeRecord, KeyValueTag, Node}
import com.chipprbots.scalanet.peergroup.Addressable
import com.typesafe.scalalogging.LazyLogging
import scodec.Codec
import java.net.InetAddress
import scala.concurrent.duration._

/** Main Discovery v5 service interface
  * 
  * Provides high-level operations for node discovery and management
  */
trait DiscoveryService {
  
  /** Look up a node by its ID
    * 
    * Checks local cache first, then performs network lookup if needed
    */
  def getNode(nodeId: Node.Id): IO[Option[Node]]
  
  /** Get all currently known nodes */
  def getNodes: IO[Set[Node]]
  
  /** Add a node to the local cache */
  def addNode(node: Node): IO[Unit]
  
  /** Remove a node from the local cache */
  def removeNode(nodeId: Node.Id): IO[Unit]
  
  /** Update the local node's external address */
  def updateExternalAddress(ip: InetAddress): IO[Unit]
  
  /** Get the local node representation */
  def getLocalNode: IO[Node]
  
  /** Find nodes closest to a target ID */
  def getClosestNodes(target: Node.Id): IO[Seq[Node]]
  
  /** Perform random lookup to discover new nodes */
  def getRandomNodes: IO[Set[Node]]
}

object DiscoveryService {
  
  import DiscoveryNetwork.Peer
  
  /** Internal state for the discovery service */
  private case class State(
    localNode: Node,
    localEnrSeq: Long,
    knownNodes: Map[Node.Id, Node],
    lastSeen: Map[Node.Id, Long]
  )
  
  /** Create a Discovery v5 service
    * 
    * @param privateKey Local node's private key
    * @param node Initial local node information
    * @param config Discovery configuration
    * @param network Discovery network layer
    * @param toAddress Function to convert Node.Address to network address
    * @param tags Optional ENR tags
    * @param sigalg Signature algorithm
    * @param enrCodec ENR codec
    * @param addressable Addressable typeclass
    * @param temporal Temporal effect
    * @return Discovery service resource
    */
  def apply[A](
    privateKey: PrivateKey,
    node: Node,
    config: DiscoveryConfig,
    network: DiscoveryNetwork[A],
    toAddress: Node.Address => A,
    tags: List[KeyValueTag] = Nil
  )(implicit
    sigalg: SigAlg,
    enrCodec: Codec[EthereumNodeRecord.Content],
    addressable: Addressable[A],
    temporal: Temporal[IO]
  ): Resource[IO, DiscoveryService] = {
    
    Resource.make {
      for {
        // Initialize state
        stateRef <- Ref[IO].of(State(
          localNode = node,
          localEnrSeq = 1L,
          knownNodes = Map.empty,
          lastSeen = Map.empty
        ))
        
        // Start handling incoming requests
        cancelToken <- network.startHandling(new RequestHandler(stateRef, network, config))
        
        // Create service instance
        service = new DiscoveryServiceImpl(stateRef, network, config, toAddress, cancelToken)
        
        // Start background tasks
        _ <- startBackgroundTasks(service, config).start
        
      } yield service: DiscoveryService
    } { service =>
      // Cleanup
      service.asInstanceOf[DiscoveryServiceImpl[A]].shutdown
    }
  }
  
  /** Background tasks for periodic discovery and maintenance */
  private def startBackgroundTasks(
    service: DiscoveryService,
    config: DiscoveryConfig
  )(implicit temporal: Temporal[IO]): IO[Unit] = {
    
    val discoveryTask = fs2.Stream.fixedRate[IO](config.discoveryInterval)
      .evalMap(_ => service.getRandomNodes.void)
      .compile
      .drain
      
    val bucketRefreshTask = fs2.Stream.fixedRate[IO](config.bucketRefreshInterval)
      .evalMap(_ => service.getRandomNodes.void)
      .compile
      .drain
    
    discoveryTask.both(bucketRefreshTask).void
  }
  
  /** Request handler for incoming RPC calls */
  private class RequestHandler[A](
    stateRef: Ref[IO, State],
    network: DiscoveryNetwork[A],
    config: DiscoveryConfig
  )(implicit temporal: Temporal[IO]) extends DiscoveryRPC[Peer[A]] with LazyLogging {
    
    import DiscoveryRPC._
    import Payload._
    
    override def ping(peer: Peer[A], localEnrSeq: Long): IO[Option[DiscoveryRPC.PingResult]] = {
      for {
        state <- stateRef.get
        // Respond with our ENR sequence
        result = Some(DiscoveryRPC.PingResult(
          state.localEnrSeq,
          scodec.bits.ByteVector.view(peer.address.asInstanceOf[java.net.InetSocketAddress].getAddress.getAddress),
          peer.address.asInstanceOf[java.net.InetSocketAddress].getPort
        ))
      } yield result
    }
    
    override def findNode(peer: Peer[A], distances: List[Int]): IO[Option[List[EthereumNodeRecord]]] = {
      // Return empty list for now - would implement k-bucket lookup
      IO.pure(Some(List.empty))
    }
    
    override def talkRequest(
      peer: Peer[A], 
      protocol: scodec.bits.ByteVector, 
      request: scodec.bits.ByteVector
    ): IO[Option[scodec.bits.ByteVector]] = {
      // Return empty response for unknown protocols
      IO.pure(Some(scodec.bits.ByteVector.empty))
    }
    
    override def regTopic(
      peer: Peer[A], 
      topic: scodec.bits.ByteVector, 
      enr: EthereumNodeRecord, 
      ticket: scodec.bits.ByteVector
    ): IO[Option[RegTopicResult]] = {
      IO.pure(None)
    }
    
    override def topicQuery(
      peer: Peer[A], 
      topic: scodec.bits.ByteVector
    ): IO[Option[List[EthereumNodeRecord]]] = {
      IO.pure(Some(List.empty))
    }
  }
  
  /** Implementation of DiscoveryService */
  private class DiscoveryServiceImpl[A](
    stateRef: Ref[IO, State],
    network: DiscoveryNetwork[A],
    config: DiscoveryConfig,
    toAddress: Node.Address => A,
    cancelToken: cats.effect.Deferred[IO, Unit]
  )(implicit temporal: Temporal[IO]) extends DiscoveryService with LazyLogging {
    
    override def getNode(nodeId: Node.Id): IO[Option[Node]] = {
      stateRef.get.map(_.knownNodes.get(nodeId))
    }
    
    override def getNodes: IO[Set[Node]] = {
      stateRef.get.map(_.knownNodes.values.toSet)
    }
    
    override def addNode(node: Node): IO[Unit] = {
      for {
        now <- temporal.realTime.map(_.toMillis)
        _ <- stateRef.update { state =>
          state.copy(
            knownNodes = state.knownNodes + (node.id -> node),
            lastSeen = state.lastSeen + (node.id -> now)
          )
        }
        _ <- IO(logger.debug(s"Added node: ${node.id.value.toHex.take(16)}..."))
      } yield ()
    }
    
    override def removeNode(nodeId: Node.Id): IO[Unit] = {
      stateRef.update { state =>
        state.copy(
          knownNodes = state.knownNodes - nodeId,
          lastSeen = state.lastSeen - nodeId
        )
      }
    }
    
    override def updateExternalAddress(ip: InetAddress): IO[Unit] = {
      stateRef.update { state =>
        val updatedNode = state.localNode.copy(
          address = state.localNode.address.copy(ip = ip)
        )
        state.copy(
          localNode = updatedNode,
          localEnrSeq = state.localEnrSeq + 1
        )
      }
    }
    
    override def getLocalNode: IO[Node] = {
      stateRef.get.map(_.localNode)
    }
    
    override def getClosestNodes(target: Node.Id): IO[Seq[Node]] = {
      // Simplified: return all known nodes sorted by XOR distance
      stateRef.get.map { state =>
        val targetHash = Node.kademliaId(target)
        state.knownNodes.values.toSeq
          .sortBy(node => xorDistance(node.kademliaId, targetHash))
          .take(config.kademliaBucketSize)
      }
    }
    
    override def getRandomNodes: IO[Set[Node]] = {
      for {
        state <- stateRef.get
        // Pick random known nodes or bootstrap nodes
        nodes = if (state.knownNodes.isEmpty) {
          config.bootstrapNodes
        } else {
          state.knownNodes.values.take(config.lookupParallelism).toSet
        }
        // Perform lookup queries
        _ <- nodes.toList.traverse { node =>
          val peer = Peer(node.id, toAddress(node.address))
          network.findNode(peer, List(256)).void.handleErrorWith { err =>
            IO(logger.debug(s"Random lookup failed for $peer: ${err.getMessage}"))
          }
        }
      } yield nodes
    }
    
    /** Calculate XOR distance between two hashes */
    private def xorDistance(a: com.chipprbots.scalanet.discovery.hash.Hash, b: com.chipprbots.scalanet.discovery.hash.Hash): BigInt = {
      val aBytes = a.value.bytes.toArray
      val bBytes = b.value.bytes.toArray
      val xor = aBytes.zip(bBytes).map { case (x, y) => (x ^ y).toByte }
      BigInt(1, xor)
    }
    
    /** Shutdown the service */
    def shutdown: IO[Unit] = {
      for {
        _ <- cancelToken.complete(())
        _ <- IO(logger.info("Discovery v5 service stopped"))
      } yield ()
    }
  }
}
