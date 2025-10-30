package com.chipprbots.scalanet.discovery.ethereum.v4

import cats.effect.{IO, Resource, Temporal, Ref, Deferred}
import cats.implicits._
import com.chipprbots.scalanet.discovery.crypto.{PrivateKey, SigAlg}
import com.chipprbots.scalanet.discovery.ethereum.{Node, EthereumNodeRecord}
import com.chipprbots.scalanet.discovery.hash.Hash
import com.chipprbots.scalanet.kademlia.XorOrdering
import com.chipprbots.scalanet.peergroup.Addressable
import fs2.Stream
import java.net.InetAddress
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scodec.{Codec, Attempt}
import scodec.bits.BitVector
import com.typesafe.scalalogging.LazyLogging
import scala.collection.immutable.SortedSet
import com.chipprbots.scalanet.discovery.ethereum.KeyValueTag

/** Represent the minimal set of operations the rest of the system
  * can expect from the service to be able to talk to other peers.
  */
trait DiscoveryService {

  /** Try to look up a node either in the local cache or
    * by performing a recursive lookup on the network. */
  def getNode(nodeId: Node.Id): IO[Option[Node]]

  /** Return all currently bonded nodes. */
  def getNodes: IO[Set[Node]]

  /** Try to get the ENR record of the given node to add it to the cache. */
  def addNode(node: Node): IO[Unit]

  /** Remove a node from the local cache. */
  def removeNode(nodeId: Node.Id): IO[Unit]

  /** Update the local node with an updated external address,
    * incrementing the local ENR sequence.
    */
  def updateExternalAddress(ip: InetAddress): IO[Unit]

  /** The local node representation. */
  def getLocalNode: IO[Node]

  /** Lookup the nodes closest to a given target. */
  def getClosestNodes(target: Node.Id): IO[Seq[Node]]

  /** Lookup a random target, to discover new nodes along the way. */
  def getRandomNodes: IO[Set[Node]]
}

object DiscoveryService {
  import DiscoveryRPC.{Call, Proc}
  import DiscoveryNetwork.Peer
  import KBucketsWithSubnetLimits.SubnetLimits

  type ENRSeq = Long
  type Timestamp = Long
  type StateRef[A] = Ref[IO, State[A]]

  /** Implement the Discovery v4 protocol:
    *
    * https://github.com/ethereum/devp2p/blob/master/discv4.md
    *
    * - maintain the state of K-buckets
    * - return node candidates for the rest of the system
    * - bond with the other nodes
    * - respond to incoming requests
    * - periodically try to discover new nodes
    * - periodically ping nodes
    */
  def apply[A](
      privateKey: PrivateKey,
      node: Node,
      config: DiscoveryConfig,
      network: DiscoveryNetwork[A],
      toAddress: Node.Address => A,
      enrollInBackground: Boolean = false,
      tags: List[KeyValueTag] = Nil
  )(
      implicit sigalg: SigAlg,
      enrCodec: Codec[EthereumNodeRecord.Content],
      addressable: Addressable[A],
      temporal: Temporal[IO]
  ): Resource[IO, DiscoveryService] =
    Resource
      .make {
        for {
          _ <- checkKeySize("private key", privateKey.value, sigalg.PrivateKeyBytesSize)
          _ <- checkKeySize("node ID", node.id.value, sigalg.PublicKeyBytesSize)

          // Use the current time to set the ENR sequence to something fresh.
          now <- temporal.monotonic.map(_.toMillis)
          enr <- IO {
            EthereumNodeRecord.fromNode(node, privateKey, seq = now, tags.flatMap(_.toAttr): _*).require
          }

          stateRef <- Ref[IO].of(State[A](node, enr, SubnetLimits.fromConfig(config)))

          service = new ServiceImpl[A](
            privateKey,
            config,
            network,
            stateRef,
            toAddress,
            KeyValueTag.toFilter(tags)
          )

          // Start handling requests, we need them during enrolling so the peers can ping and bond with us.
          cancelToken <- network.startHandling(service)
          // Contact the bootstrap nodes.
          // Setting the enrolled status here because we could potentially repeat enrollment until it succeeds.
          enroll = service.enroll.guarantee(stateRef.update(_.setEnrolled))
          // Periodically discover new nodes.
          discover = Stream.fixedDelay[IO](config.discoveryPeriod).evalMap(_ => service.lookupRandom).compile.drain
          // Enrollment can be run in the background if it takes very long.
          discoveryFiber <- if (enrollInBackground) {
            (enroll >> discover).start
          } else {
            enroll >> discover.start
          }
        } yield (service, cancelToken, discoveryFiber)
      } {
        case (_, cancelToken, discoveryFiber) =>
          cancelToken.complete(()).void >> discoveryFiber.cancel
      }
      .map(_._1)

  protected[v4] def checkKeySize(name: String, key: BitVector, expectedBytesSize: Int): IO[Unit] =
    IO
      .raiseError(
        new IllegalArgumentException(
          s"Expected the $name to be ${expectedBytesSize} bytes; got ${key.size / 8} bytes."
        )
      )
      .whenA(key.size != expectedBytesSize * 8)

  protected[v4] case class BondingResults(
      // Completed if the remote poor responds with a Pong during the bonding process.
      pongReceived: Deferred[IO, Boolean],
      // Completed if the remote peer pings us during the bonding process.
      pingReceived: Deferred[IO, Unit]
  )
  protected[v4] object BondingResults {
    def apply(): IO[BondingResults] =
      for {
        pong <- Deferred[IO, Boolean]
        ping <- Deferred[IO, Unit]
      } yield BondingResults(pong, ping)

    def unsafe(): BondingResults =
      BondingResults(Deferred.unsafe[IO, Boolean], Deferred.unsafe[IO, Unit])
  }

  protected[v4] type FetchEnrResult = Deferred[IO, Option[EthereumNodeRecord]]

  protected[v4] case class State[A](
      node: Node,
      enr: EthereumNodeRecord,
      // Kademlia buckets with hashes of the nodes' IDs in them.
      kBuckets: KBucketsWithSubnetLimits[A],
      kademliaIdToNodeId: Map[Hash, Node.Id],
      nodeMap: Map[Node.Id, Node],
      enrMap: Map[Node.Id, EthereumNodeRecord],
      // Last time a peer responded with a Pong to our Ping.
      lastPongTimestampMap: Map[Peer[A], Timestamp],
      // Deferred results so we can ensure there's only one concurrent Ping to a given peer.
      bondingResultsMap: Map[Peer[A], BondingResults],
      // Deferred ENR fetches so we only do one at a time to a given peer.
      fetchEnrMap: Map[Peer[A], FetchEnrResult],
      // Indicate whether enrollment hash finished.
      hasEnrolled: Boolean
  ) {
    def isSelf(peer: Peer[A]): Boolean =
      peer.id == node.id

    def withLastPongTimestamp(peer: Peer[A], timestamp: Timestamp): State[A] =
      copy(lastPongTimestampMap = lastPongTimestampMap.updated(peer, timestamp))

    def withBondingResults(peer: Peer[A], results: BondingResults): State[A] =
      copy(bondingResultsMap = bondingResultsMap.updated(peer, results))

    def withEnrAndAddress(
        peer: Peer[A],
        enr: EthereumNodeRecord,
        address: Node.Address,
        addToBucket: Boolean = true
    ): State[A] = {
      copy(
        enrMap = enrMap.updated(peer.id, enr),
        nodeMap = nodeMap.updated(peer.id, Node(peer.id, address)),
        kBuckets =
          if (isSelf(peer))
            kBuckets
          else if (kBuckets.contains(peer))
            kBuckets.touch(peer)
          else if (addToBucket)
            kBuckets.add(peer)
          else
            kBuckets,
        kademliaIdToNodeId = kademliaIdToNodeId.updated(peer.kademliaId, peer.id)
      )
    }

    /** Update the timestamp of the peer in the K-table, if it's still part of it. */
    def withTouch(peer: Peer[A]): State[A] =
      if (kBuckets.contains(peer))
        copy(kBuckets = kBuckets.touch(peer))
      else
        // Not adding because `kademliaIdToNodeId` and `nodeMap` may no longer have this peer.
        this

    def clearBondingResults(peer: Peer[A]): State[A] =
      copy(bondingResultsMap = bondingResultsMap - peer)

    def clearLastPongTimestamp(peer: Peer[A]): State[A] =
      copy(lastPongTimestampMap = lastPongTimestampMap - peer)

    def withEnrFetch(peer: Peer[A], result: FetchEnrResult): State[A] =
      copy(fetchEnrMap = fetchEnrMap.updated(peer, result))

    def clearEnrFetch(peer: Peer[A]): State[A] =
      copy(fetchEnrMap = fetchEnrMap - peer)

    def removePeer(peer: Peer[A], toAddress: Node.Address => A): State[A] = {
      // We'll have ony one node/enr for this peer ID, but it may be with a different address.
      // This can happen if we get a fake neighbor respose from a malicious peer, with the ID
      // of an honest node and an ureachable address. We shouldn't remote the honest node.
      (nodeMap.get(peer.id) match {
        case Some(node) if toAddress(node.address) == peer.address =>
          copy(
            nodeMap = nodeMap - peer.id,
            enrMap = enrMap - peer.id,
            kBuckets = kBuckets.remove(peer),
            kademliaIdToNodeId = kademliaIdToNodeId - peer.kademliaId
          )
        case _ => this
      }).copy(
        // We can always remove these entries as they are keyed by ID+Address.
        lastPongTimestampMap = lastPongTimestampMap - peer,
        bondingResultsMap = bondingResultsMap - peer
      )
    }

    def removePeer(peerId: Node.Id, toAddress: Node.Address => A): State[A] = {
      // Find any Peer records that correspond to this ID.
      val peers: Set[Peer[A]] = (
        nodeMap.get(peerId).map(node => Peer(node.id, toAddress(node.address))).toSeq ++
          lastPongTimestampMap.keys.filter(_.id == peerId).toSeq ++
          bondingResultsMap.keys.filter(_.id == peerId).toSeq
      ).toSet

      copy(
        nodeMap = nodeMap - peerId,
        enrMap = enrMap - peerId,
        lastPongTimestampMap = lastPongTimestampMap -- peers,
        bondingResultsMap = bondingResultsMap -- peers,
        kBuckets = peers.foldLeft(kBuckets)(_ remove _),
        kademliaIdToNodeId = kademliaIdToNodeId - Node.kademliaId(peerId)
      )
    }

    def setEnrolled: State[A] =
      copy(hasEnrolled = true)
  }
  protected[v4] object State {
    def apply[A: Addressable](
        node: Node,
        enr: EthereumNodeRecord,
        subnetLimits: SubnetLimits
    ): State[A] = State[A](
      node = node,
      enr = enr,
      kBuckets = KBucketsWithSubnetLimits[A](node, subnetLimits),
      kademliaIdToNodeId = Map(node.kademliaId -> node.id),
      nodeMap = Map(node.id -> node),
      enrMap = Map(node.id -> enr),
      lastPongTimestampMap = Map.empty[Peer[A], Timestamp],
      bondingResultsMap = Map.empty[Peer[A], BondingResults],
      fetchEnrMap = Map.empty[Peer[A], FetchEnrResult],
      hasEnrolled = false
    )
  }

  protected[v4] class ServiceImpl[A](
      privateKey: PrivateKey,
      config: DiscoveryConfig,
      rpc: DiscoveryRPC[Peer[A]],
      stateRef: StateRef[A],
      toAddress: Node.Address => A,
      enrFilter: KeyValueTag.EnrFilter
  )(
      implicit temporal: Temporal[IO],
      sigalg: SigAlg,
      enrCodec: Codec[EthereumNodeRecord.Content],
      addressable: Addressable[A]
  ) extends DiscoveryService
      with DiscoveryRPC[Peer[A]]
      with LazyLogging {

    override def getLocalNode: IO[Node] =
      stateRef.get.map(_.node)

    override def addNode(node: Node): IO[Unit] =
      maybeFetchEnr(toPeer(node), None)

    override def getNodes: IO[Set[Node]] =
      stateRef.get.map(_.nodeMap.values.toSet)

    override def getNode(nodeId: Node.Id): IO[Option[Node]] =
      stateRef.get.flatMap { state =>
        state.nodeMap.get(nodeId) match {
          case cached @ Some(_) =>
            IO.pure(cached)
          case None =>
            lookup(nodeId).flatMap {
              case closest if closest.head.id == nodeId =>
                maybeFetchEnr(toPeer(closest.head), None) >>
                  stateRef.get.map(_.nodeMap.get(nodeId))
              case _ =>
                IO.pure(None)
            }
        }
      }

    /** Perform a lookup and also make sure the closest results have their ENR records fetched,
      * to rule out the chance that incorrect details were relayed in the Neighbors response.
      */
    override def getClosestNodes(target: Node.Id): IO[Seq[Node]] =
      for {
        closest <- lookup(target)
        // Ensure we have an ENR record, so that the TCP port is retrieved from the source,
        // not just relying on Neighbors to be correct.
        _ <- closest.toList.parTraverse(n => maybeFetchEnr(toPeer(n), None))
        state <- stateRef.get
        // Get the resolved records from state.
        resolved = closest.toList.flatMap(n => state.nodeMap.get(n.id))
      } yield resolved

    override def getRandomNodes: IO[Set[Node]] =
      getClosestNodes(sigalg.newKeyPair._1).map(_.toSet)

    override def removeNode(nodeId: Node.Id): IO[Unit] =
      stateRef.update { state =>
        if (state.node.id == nodeId) state else state.removePeer(nodeId, toAddress)
      }

    /** Update the node and ENR of the local peer with the new address and ping peers with the new ENR seq. */
    override def updateExternalAddress(ip: InetAddress): IO[Unit] = {
      stateRef
        .modify { state =>
          val node = Node(
            state.node.id,
            Node.Address(ip, udpPort = state.node.address.udpPort, tcpPort = state.node.address.tcpPort)
          )
          if (node == state.node)
            state -> Nil
          else {
            val enr = EthereumNodeRecord.fromNode(node, privateKey, state.enr.content.seq + 1).require
            val notify = state.lastPongTimestampMap.keySet.toList
            state.copy(
              node = node,
              enr = enr,
              nodeMap = state.nodeMap.updated(node.id, node),
              enrMap = state.enrMap.updated(node.id, enr)
            ) -> notify
          }
        }
        .flatMap { peers =>
          // Send our new ENR sequence to the peers so they can pull our latest data.
          peers.toList.parTraverse(pingAndMaybeUpdateTimestamp(_)).start.void
        }
    }

    /** Handle incoming Ping request. */
    override def ping: Call[Peer[A], Proc.Ping] =
      caller =>
        maybeRemoteEnrSeq =>
          for {
            // Complete any deferred waiting for a ping from this peer, if we initiated the bonding.
            _ <- completePing(caller)
            // To protect against an eclipse attack filling up the k-table after a reboot,
            // only try to bond with an incoming Ping's peer after the initial enrollment
            // hash finished.
            hasEnrolled <- stateRef.get.map(_.hasEnrolled)
            _ <- isBonded(caller)
              .ifM(
                // We may already be bonded but the remote node could have changed its address.
                // It is possible that this is happening during a bonding, in which case we should
                // wait for our Pong response to get to the remote node and be processed first.
                maybeFetchEnr(caller, maybeRemoteEnrSeq, delay = true),
                // Try to bond back, if this is a new node.
                bond(caller)
              )
              .start.void
              .whenA(hasEnrolled)
            // Return the latet local ENR sequence.
            enrSeq <- localEnrSeq
          } yield Some(Some(enrSeq))

    /** Handle incoming FindNode request. */
    override def findNode: Call[Peer[A], Proc.FindNode] =
      caller =>
        target =>
          respondIfBonded(caller, "FindNode") {
            for {
              state <- stateRef.get
              targetId = Node.kademliaId(target)
              closestNodeIds = state.kBuckets.closestNodes(targetId, config.kademliaBucketSize)
              closestNodes = closestNodeIds
                .map(state.kademliaIdToNodeId)
                .map(state.nodeMap)
            } yield closestNodes
          }

    /** Handle incoming ENRRequest. */
    override def enrRequest: Call[Peer[A], Proc.ENRRequest] =
      caller => _ => respondIfBonded(caller, "ENRRequest")(stateRef.get.map(_.enr))

    // The methods below are `protected[v4]` so that they can be called from tests individually.
    // Initially they were in the companion object as pure functions but there are just too many
    // parameters to pass around.

    protected[v4] def toPeer(node: Node): Peer[A] =
      Peer(node.id, toAddress(node.address))

    protected[v4] def currentTimeMillis: IO[Long] =
      temporal.realTime.map(_.toMillis)

    protected[v4] def localEnrSeq: IO[ENRSeq] =
      stateRef.get.map(_.enr.content.seq)

    /** Check if the given peer has a valid bond at the moment. */
    protected[v4] def isBonded(
        peer: Peer[A]
    ): IO[Boolean] = {
      currentTimeMillis.flatMap { now =>
        stateRef.get.map { state =>
          if (state.isSelf(peer))
            true
          else
            state.lastPongTimestampMap.get(peer) match {
              case None =>
                false
              case Some(timestamp) =>
                timestamp > now - config.bondExpiration.toMillis
            }
        }
      }
    }

    /** Return Some response if the peer is bonded or log some hint about what was requested and return None. */
    protected[v4] def respondIfBonded[T](caller: Peer[A], request: String)(response: IO[T]): IO[Option[T]] =
      isBonded(caller).flatMap {
        case true => response.map(Some(_))
        case false => IO(logger.debug(s"Ignoring $request request from unbonded $caller")).as(None)
      }

    /** Runs the bonding process with the peer, unless already bonded.
      *
      * If the process is already running it waits for the result of that,
      * it doesn't send another ping.
      *
      * If the peer responds it waits for a potential ping to arrive from them,
      * so we can have some reassurance that the peer is also bonded with us
      * and will not ignore our messages.
      */
    protected[v4] def bond(
        peer: Peer[A]
    ): IO[Boolean] =
      isBonded(peer).flatMap {
        case true =>
          // Check that we have an ENR for this peer.
          maybeFetchEnr(peer, maybeRemoteEnrSeq = None, delay = false).start.void.as(true)

        case false =>
          initBond(peer).flatMap {
            case Some(result) =>
              result.pongReceived.get.timeoutTo(config.requestTimeout, IO.pure(false))

            case None =>
              IO(logger.debug(s"Trying to bond with $peer...")) >>
                pingAndMaybeUpdateTimestamp(peer)
                  .flatMap {
                    case Some(maybeRemoteEnrSeq) =>
                      for {
                        _ <- IO(logger.debug(s"$peer responded to bond attempt."))
                        // Allow some time for the reciprocating ping to arrive.
                        _ <- awaitPing(peer)
                        // Complete all bonds waiting on this pong, after any pings were received
                        // so that we can now try and send requests with as much confidence as we can get.
                        _ <- completePong(peer, responded = true)
                        // We need the ENR record for the full address to be verified.
                        // First allow some time for our Pong to go back to the caller.
                        _ <- maybeFetchEnr(peer, maybeRemoteEnrSeq, delay = true).start.void
                      } yield true

                    case None =>
                      for {
                        _ <- IO(logger.debug(s"$peer did not respond to bond attempt."))
                        _ <- removePeer(peer)
                        _ <- completePong(peer, responded = false)
                      } yield false
                  }
                  .guarantee(stateRef.update(_.clearBondingResults(peer)))
          }
      }

    /** Try to ping the remote peer and update the last pong timestamp if they respond. */
    protected[v4] def pingAndMaybeUpdateTimestamp(peer: Peer[A]): IO[Option[Option[ENRSeq]]] =
      for {
        enrSeq <- localEnrSeq
        maybeResponse <- rpc.ping(peer)(Some(enrSeq)).recover {
          case NonFatal(_) => None
        }
        _ <- updateLastPongTime(peer).whenA(maybeResponse.isDefined)
      } yield maybeResponse

    /** Check and modify the bonding state of the peer: if we're already bonding
      * return the Deferred result we can wait on, otherwise add a new Deferred
      * and return None, in which case the caller has to perform the bonding.
      */
    protected[v4] def initBond(peer: Peer[A]): IO[Option[BondingResults]] =
      for {
        results <- BondingResults()
        maybeExistingResults <- stateRef.modify { state =>
          state.bondingResultsMap.get(peer) match {
            case Some(results) =>
              state -> Some(results)

            case None =>
              state.withBondingResults(peer, results) -> None
          }
        }
      } yield maybeExistingResults

    protected[v4] def updateLastPongTime(peer: Peer[A]): IO[Unit] =
      currentTimeMillis.flatMap { now =>
        stateRef.update { state =>
          state.withLastPongTimestamp(peer, now)
        }
      }

    /** Update the bonding state of the peer with the result,
      * notifying all potentially waiting bonding processes about the outcome.
      */
    protected[v4] def completePong(peer: Peer[A], responded: Boolean): IO[Unit] =
      stateRef
        .modify { state =>
          val maybePongReceived = state.bondingResultsMap.get(peer).map(_.pongReceived)
          state.clearBondingResults(peer) -> maybePongReceived
        }
        .flatMap { maybePongReceived =>
          maybePongReceived.fold(IO.unit)(_.complete(responded).void)
        }

    /** Allow the remote peer to ping us during bonding, so that we can have a more
      * fungible expectation that if we send a message they will consider us bonded and
      * not ignore it.
      *
      * The deferred should be completed by the ping handler.
      */
    protected[v4] def awaitPing(peer: Peer[A]): IO[Unit] =
      stateRef.get
        .map { state =>
          state.bondingResultsMap.get(peer).map(_.pingReceived)
        }
        .flatMap { maybePingReceived =>
          maybePingReceived.fold(IO.unit)(_.get.timeoutTo(config.requestTimeout, IO.unit))
        }

    /** Complete any deferred we set up during a bonding process expecting a ping to arrive. */
    protected[v4] def completePing(peer: Peer[A]): IO[Unit] =
      stateRef.get
        .map { state =>
          state.bondingResultsMap.get(peer).map(_.pingReceived)
        }
        .flatMap { maybePingReceived =>
          maybePingReceived.fold(IO.unit)(_.complete(()).attempt.void)
        }

    /** Fetch the remote ENR if we don't already have it or if
      * the sequence number we have is less than what we got just now.
      *
      * The execution might be delayed in case we are expecting the other side
      * to receive our Pong first, lest they think we are unbonded.
      * Passing on the variable so the Deferred is entered into the state.
      *  */
    protected[v4] def maybeFetchEnr(
        peer: Peer[A],
        maybeRemoteEnrSeq: Option[ENRSeq],
        delay: Boolean = false
    ): IO[Unit] =
      for {
        state <- stateRef.get
        maybeEnrAndNode = (state.enrMap.get(peer.id), state.nodeMap.get(peer.id))
        needsFetching = maybeEnrAndNode match {
          case _ if state.isSelf(peer) =>
            false
          case (None, _) =>
            true
          case (Some(enr), _) if maybeRemoteEnrSeq.getOrElse(enr.content.seq) > enr.content.seq =>
            true
          case (_, Some(node)) if toAddress(node.address) != peer.address =>
            true
          case _ =>
            false
        }
        _ <- fetchEnr(peer, delay).whenA(needsFetching)
      } yield ()

    /** Fetch a fresh ENR from the peer and store it.
      *
      * Use delay=true if there's a high chance that the other side is still bonding with us
      * and hasn't received our Pong yet, in which case they'd ignore the ENRRequest.
      */
    protected[v4] def fetchEnr(
        peer: Peer[A],
        delay: Boolean = false
    ): IO[Option[EthereumNodeRecord]] = {
      val waitOrFetch =
        for {
          d <- Deferred[IO, Option[EthereumNodeRecord]]
          decision <- stateRef.modify { state =>
            state.fetchEnrMap.get(peer) match {
              case Some(d) =>
                state -> Left(d)
              case None =>
                state.withEnrFetch(peer, d) -> Right(d)
            }
          }
        } yield decision

      waitOrFetch.flatMap {
        case Left(wait) =>
          wait.get.timeoutTo(config.requestTimeout, IO.pure(None))

        case Right(fetch) =>
          val maybeEnr = bond(peer).flatMap {
            case false =>
              IO(logger.debug(s"Could not bond with $peer to fetch ENR")).as(None)
            case true =>
              IO(logger.debug(s"Fetching the ENR from $peer...")) >>
                rpc
                  .enrRequest(peer)(())
                  .delayBy(if (delay) config.requestTimeout else Duration.Zero)
                  .flatMap {
                    case None =>
                      // At this point we are still bonded with the peer, so they think they can send us requests.
                      // We just have to keep trying to get an ENR for them, until then we can't use them for routing.
                      IO(logger.debug(s"Could not fetch ENR from $peer")).as(None)

                    case Some(enr) =>
                      validateEnr(peer, enr)
                  }
          }

          maybeEnr
            .recoverWith {
              case NonFatal(ex) =>
                IO(logger.debug(s"Failed to fetch ENR from $peer: $ex")).as(None)
            }
            .flatTap(fetch.complete)
            .guarantee(stateRef.update(_.clearEnrFetch(peer)))
      }
    }

    private def validateEnr(peer: Peer[A], enr: EthereumNodeRecord): IO[Option[EthereumNodeRecord]] = {
      enrFilter(enr) match {
        case Left(reject) =>
          IO(logger.debug(s"Ignoring ENR from $peer: $reject")) >>
            removePeer(peer).as(None)

        case Right(()) =>
          EthereumNodeRecord.validateSignature(enr, publicKey = peer.id) match {
            case Attempt.Successful(true) =>
              // Try to extract the node address from the ENR record and update the node database,
              // otherwise if there's no address we can use remove the peer.
              Node.Address.fromEnr(enr) match {
                case None =>
                  IO(logger.debug(s"Could not extract node address from ENR $enr")) >>
                    removePeer(peer).as(None)

                case Some(address) if !address.checkRelay(peer) =>
                  IO(logger.debug(s"Ignoring ENR with $address from $peer because of invalid relay IP.")) >>
                    removePeer(peer).as(None)

                case Some(address) =>
                  IO(logger.info(s"Storing the ENR for $peer")) >>
                    storePeer(peer, enr, address)
              }

            case Attempt.Successful(false) =>
              IO(logger.info(s"Could not validate ENR signature from $peer!")) >>
                removePeer(peer).as(None)

            case Attempt.Failure(err) =>
              IO(logger.error(s"Error validating ENR from $peer: $err")).as(None)
          }
      }
    }

    /** Add the peer to the node and ENR maps, then see if the bucket the node would fit into isn't already full.
      * If it isn't, add the peer to the routing table, otherwise try to evict the least recently seen peer.
      *
      * Returns None if the routing record was discarded or Some if it was added to the k-buckets.
      *
      * NOTE: Half of the network falls in the first bucket, so we only track `k` of them. If we used
      * this component for routing messages it would be a waste to discard the ENR and use `lookup`
      * every time we need to talk to someone on the other half of the address space, so the ENR is
      * stored regardless of whether we enter the record in the k-buckets.
      */
    protected[v4] def storePeer(
        peer: Peer[A],
        enr: EthereumNodeRecord,
        address: Node.Address
    ): IO[Option[EthereumNodeRecord]] = {
      stateRef
        .modify { state =>
          if (state.isSelf(peer))
            state -> None
          else {
            val (_, bucket) = state.kBuckets.getBucket(peer)
            val (addToBucket, maybeEvict) =
              if (bucket.contains(peer.kademliaId) || bucket.size < config.kademliaBucketSize) {
                // We can just update the records, the bucket either has room or won't need to grow.
                true -> None
              } else {
                // We have to consider evicting somebody or dropping this node.
                false -> Some(state.nodeMap(state.kademliaIdToNodeId(bucket.head)))
              }
            // Store the ENR record and maybe update the k-buckets.
            state.withEnrAndAddress(peer, enr, address, addToBucket) -> maybeEvict
          }
        }
        .flatMap {
          case None =>
            IO(logger.debug(s"Added $peer to the k-buckets.")).as(Some(enr))

          case Some(evictionCandidate) =>
            val evictionPeer = toPeer(evictionCandidate)
            pingAndMaybeUpdateTimestamp(evictionPeer).map(_.isDefined).flatMap {
              case true =>
                // Keep the existing record, discard the new.
                // NOTE: We'll still consider them bonded because they reponded to a ping,
                // so we'll respond to queries and maybe even send requests in recursive
                // lookups, but won't return the peer itself in results.
                // A more sophisticated approach would be to put them in a separate replacement
                // cache for the bucket where they can be drafted from if someone cannot bond again.
                IO(logger.debug(s"Not adding $peer to the k-buckets, keeping $evictionPeer")) >>
                  stateRef.update(_.withTouch(evictionPeer)).as(None)

              case false =>
                // Get rid of the non-responding peer and add the new one
                // then try to add this one again (something else might be trying as well,
                // don't want to end up overfilling the bucket).
                IO(logger.debug(s"Evicting $evictionPeer, maybe replacing with $peer")) >>
                  removePeer(evictionPeer) >>
                  storePeer(peer, enr, address)
            }
        }
    }

    /** Forget everything about this peer. */
    protected[v4] def removePeer(peer: Peer[A]): IO[Unit] =
      stateRef.update(_.removePeer(peer, toAddress))

    /** Locate the k closest nodes to a node ID.
      *
      * Note that it keeps going even if we know the target or find it along the way.
      * Due to the way it allows by default 7 seconds for the k closest neighbors to
      * arrive from each peer we ask (or if they return k quicker then it returns earlier)
      * it could be quite slow if it was used for routing.
      *
      * It doesn't wait fetching and validating the ENR records, that happens in the background.
      * Use `getNode` or `getClosestNodes` which wait for that extra step after the lookup.
      *
      * https://github.com/ethereum/devp2p/blob/master/discv4.md#recursive-lookup
      */
    protected[v4] def lookup(target: Node.Id): IO[SortedSet[Node]] = {
      val targetId = Node.kademliaId(target)

      implicit val nodeOrdering: Ordering[Node] =
        XorOrdering[Node, Hash](_.kademliaId)(targetId)

      // Find the 16 closest nodes we know of.
      // We'll contact 'alpha' at a time but eventually try all of them
      // unless better candidates are found.
      val init = for {
        _ <- checkKeySize("target public key", target.value, sigalg.PublicKeyBytesSize)

        state <- stateRef.get

        closestIds = state.kBuckets
          .closestNodes(targetId, config.kademliaBucketSize)

        closestNodes = closestIds.map(state.kademliaIdToNodeId).map(state.nodeMap)

        // In case we haven't been able to bond with the bootstrap nodes at startup,
        // and we don't have enough nodes to contact now, try them again, maybe
        // they are online now. This is so that we don't have to pretend they
        // are online and store them in the ENR map until they really are available.
        closestOrBootstraps = if (closestNodes.size < config.kademliaBucketSize)
          (closestNodes ++ config.knownPeers).distinct.take(config.kademliaBucketSize)
        else closestNodes

      } yield (state.node, closestOrBootstraps)

      def fetchNeighbors(from: Node): IO[List[Node]] = {
        val peer = toPeer(from)

        bond(peer).flatMap {
          case true =>
            rpc
              .findNode(peer)(target)
              .flatMap {
                case None =>
                  for {
                    _ <- IO(logger.debug(s"Received no response for neighbors for $target from ${peer.address}"))
                    // The other node has possibly unbonded from us, or it was still enrolling when we bonded. Try bonding next time.
                    _ <- stateRef.update(_.clearLastPongTimestamp(peer))
                  } yield Nil
                case Some(neighbors) =>
                  IO(logger.debug(s"Received ${neighbors.size} neighbors for $target from ${peer.address}"))
                    .as(neighbors.toList)
              }
              .flatMap { neighbors =>
                neighbors.filterA { neighbor =>
                  if (neighbor.address.checkRelay(peer))
                    IO.pure(true)
                  else
                    IO(logger.debug(s"Ignoring neighbor $neighbor from ${peer.address} because of invalid relay IP."))
                      .as(false)
                }
              }
              .recoverWith {
                case NonFatal(ex) =>
                  IO(logger.debug(s"Failed to fetch neighbors of $target from ${peer.address}: $ex")).as(Nil)
              }
          case false =>
            IO(logger.debug(s"Could not bond with ${peer.address} to fetch neighbors of $target")).as(Nil)
        }
      }

      // Make sure these new nodes can be bonded with before we consider them,
      // otherwise they might appear to be be closer to the target but actually
      // be fakes with unreachable addresses that could knock out legit nodes.
      def bondNeighbors(neighbors: Seq[Node]): IO[Seq[Node]] =
        for {
          _ <- IO(logger.debug(s"Bonding with ${neighbors.size} neighbors..."))
          bonded <- neighbors.toList.parTraverse { neighbor =>
              bond(toPeer(neighbor)).flatMap {
                case true =>
                  IO.pure(Some(neighbor))
                case false =>
                  IO(logger.debug(s"Could not bond with neighbor candidate $neighbor")).as(None)
              }
            }
            .map(_.flatten)
          _ <- IO(logger.debug(s"Bonded with ${bonded.size} neighbors out of ${neighbors.size}."))
        } yield bonded

      def loop(
          local: Node,
          closest: SortedSet[Node],
          asked: Set[Node],
          neighbors: Set[Node]
      ): IO[SortedSet[Node]] = {
        // Contact the alpha closest nodes to the target that we haven't asked before.
        val contacts = closest
          .filterNot(asked)
          .filterNot(_.id == local.id)
          .take(config.kademliaAlpha)
          .toList

        if (contacts.isEmpty) {
          IO(
            logger.debug(s"Lookup for $target finished; asked ${asked.size} nodes, found ${neighbors.size} neighbors.")
          ).as(closest)
        } else {
          IO(
            logger.debug(s"Lookup for $target contacting ${contacts.size} new nodes; asked ${asked.size} nodes so far.")
          ) >>
            contacts.toList.parTraverse(fetchNeighbors)
              .map(_.flatten.distinct.filterNot(neighbors))
              .flatMap(bondNeighbors)
              .flatMap { newNeighbors =>
                val nextClosest = (closest ++ newNeighbors).take(config.kademliaBucketSize)
                val nextAsked = asked ++ contacts
                val nextNeighbors = neighbors ++ newNeighbors
                val newClosest = nextClosest diff closest
                IO(logger.debug(s"Lookup for $target found ${newClosest.size} neighbors closer than before.")) >>
                  loop(local, nextClosest, nextAsked, nextNeighbors)
              }
        }
      }

      init.flatMap {
        case (localNode, closestNodes) =>
          loop(localNode, closest = SortedSet(closestNodes: _*), asked = Set(localNode), neighbors = closestNodes.toSet)
      }
    }

    /** Look up a random node ID to discover new peers. */
    protected[v4] def lookupRandom: IO[Set[Node]] =
      IO(logger.info("Looking up a random target...")) >>
        lookup(target = sigalg.newKeyPair._1)

    /** Look up self with the bootstrap nodes. First we have to fetch their ENR
      * records to verify they are reachable and so that they can participate
      * in the lookup.
      *
      * Return `true` if we managed to get the ENR with at least one boostrap
      * or `false` if none of them responded with a correct ENR,
      * which would mean we don't have anyone to do lookups with.
      */
    protected[v4] def enroll: IO[Boolean] =
      if (config.knownPeers.isEmpty)
        IO.pure(false)
      else {
        for {
          nodeId <- stateRef.get.map(_.node.id)
          bootstrapPeers = config.knownPeers.toList.map(toPeer).filterNot(_.id == nodeId)
          _ <- IO(logger.info(s"Enrolling with ${bootstrapPeers.size} bootstrap nodes."))
          maybeBootstrapEnrs <- bootstrapPeers.parTraverse(fetchEnr(_, delay = true))
          enrolled = maybeBootstrapEnrs.count(_.isDefined)
          succeeded = enrolled > 0
          _ <- if (succeeded) {
            for {
              _ <- IO(
                logger.info(s"Successfully enrolled with $enrolled bootstrap nodes. Performing initial lookup...")
              )
              _ <- lookup(nodeId).attempt.flatMap {
                case Right(_) => IO.unit
                case Left(ex) => IO(logger.error(s"Error during initial lookup", ex))
              }
              nodeCount <- stateRef.get.map(_.nodeMap.size)
              _ <- IO(logger.info(s"Discovered $nodeCount nodes by the end of the lookup."))
            } yield ()
          } else {
            IO(logger.warn("Failed to enroll with any of the the bootstrap nodes."))
          }
        } yield succeeded
      }
  }
}
