package com.chipprbots.ethereum.network.discovery

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.Resource
import cats.effect.std.Queue
import cats.effect.unsafe.IORuntime
import cats.syntax.parallel.*

import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success

import com.chipprbots.scalanet.discovery.crypto.PublicKey
import com.chipprbots.scalanet.discovery.ethereum.Node as ENode
import com.chipprbots.scalanet.discovery.ethereum.v4
import org.bouncycastle.util.encoders.Hex
import scodec.bits.BitVector

import com.chipprbots.ethereum.db.storage.KnownNodesStorage

object PeerDiscoveryManager:

  // Buffer of random candidates, filled by the single [[RandomNodeFeeder]] and drained by GetRandomNodeInfoReq.
  private type RandomNodes = Queue[IO, Node]
  private type Discovery = (v4.DiscoveryService, RandomNodes)

  sealed trait Command
  case object Start extends Command
  case object Stop extends Command
  final private case class StartAttempt(result: Either[Throwable, (Discovery, IO[Unit])]) extends Command
  final private case class StopAttempt(result: Either[Throwable, Unit]) extends Command
  final private case class DnsNodesFound(nodes: Set[Node]) extends Command
  final case class GetDiscoveredNodesInfoReq(replyTo: ActorRef[DiscoveredNodesInfo]) extends Command
  final case class GetRandomNodeInfoReq(replyTo: ActorRef[RandomNodeInfo]) extends Command

  /** Legacy Classic-only messages — NOT part of Command. Used by the Classic bridge in NodeBuilder to translate
    * fire-and-forget Classic requests into typed ask-with-replyTo requests. Remove once PeerManagerActor is migrated to
    * Typed.
    */
  case object GetDiscoveredNodesInfo
  case object GetRandomNodeInfo

  final case class DiscoveredNodesInfo(nodes: Set[Node])
  final case class RandomNodeInfo(node: Node)

  /** How long one GetRandomNodeInfoReq waits for the feeder before giving up without a reply. Below PeerManager's ask
    * timeout (`update-nodes-interval`, 15 s) so an unanswered request does not keep a waiting fiber past its asker.
    */
  private[discovery] val RandomNodeWait: FiniteDuration = 10.seconds

  def apply(
      localNodeId: ByteString,
      discoveryConfig: DiscoveryConfig,
      knownNodesStorage: KnownNodesStorage,
      discoveryServiceResource: Resource[IO, v4.DiscoveryService],
      randomNodeBufferSize: Int = 0,
      dnsLookup: DnsDiscovery.TxtLookup = DnsDiscovery.JndiTxtLookup,
      dnsForkIdFilter: Option[DnsDiscovery.EnrForkIdFilter] = None
  )(using runtime: IORuntime): Behavior[Command] =
    Behaviors.setup { ctx =>
      // Capture ctx.log on the actor thread once; use this `log` val (plain SLF4J) in
      // IO/Future callbacks that run on CE worker threads — ctx.log itself enforces
      // checkCurrentActorThread() and will throw if called off-thread.
      val log = ctx.log
      val self = ctx.self

      val alreadyDiscoveredNodes: Vector[Node] =
        if !discoveryConfig.reuseKnownNodes then Vector.empty
        else
          val bootstrapNodes: Set[Node] = discoveryConfig.bootstrapNodes
          val knownNodes: Set[Node] =
            if !discoveryConfig.discoveryEnabled then Set.empty
            else knownNodesStorage.getKnownNodes.map(Node.fromUri)
          (bootstrapNodes ++ knownNodes).filterNot(n => n.id == localNodeId).toVector

      val discoveryResources: Resource[IO, (v4.DiscoveryService, RandomNodes)] = for
        service <- discoveryServiceResource
        randomNodes <- Resource.eval(Queue.bounded[IO, Node](math.max(1, randomNodeBufferSize)))
        // ONE feeder per running service, cancelled with it (task #52). It used to be a stream compiled afresh by every
        // GetRandomNodeInfoReq, i.e. one full Kademlia lookup per request with no bound on how many ran at once.
        // IO.defer: a fresh lookup per round, not one captured IO reused forever (PR #1090).
        _ <- RandomNodeFeeder
          .supervised(
            lookup = IO.defer(service.getRandomNodes).map(_.map(toNode).filter(n => n.id != localNodeId)),
            offer = randomNodes.offer
          )
          .background
      yield (service, randomNodes)

      // EIP-1459 candidates found so far by the background DNS walk, and how to cancel that walk. Actor-confined plain
      // vars (P10): the walk's threads only send DnsNodesFound, they never touch these.
      var dnsNodes: Set[Node] = Set.empty
      var cancelDnsWalk: Option[() => Future[Unit]] = None

      /** Walk the configured DNS trees off the actor and off `main`, on the discovery runtime's blocking pool. Config
        * load no longer waits for DNS (H9): the static and bootstrap nodes are served from the first request, and these
        * join them as the walk finds them.
        */
      def startDnsWalk(): Unit =
        if cancelDnsWalk.isEmpty && discoveryConfig.dnsDomains.nonEmpty then
          given IORuntime = runtime
          log.info("Resolving {} DNS discovery domain(s) in the background", discoveryConfig.dnsDomains.size)
          val (_, cancel) = DnsDiscovery
            .seed(
              discoveryConfig.dnsDomains,
              dnsLookup,
              discoveryConfig.dnsWalk,
              dnsForkIdFilter,
              nodes => IO(self ! DnsNodesFound(nodes))
            )
            .unsafeToFutureCancelable()
          cancelDnsWalk = Some(cancel)

      def stopDnsWalk(): Unit =
        cancelDnsWalk.foreach(cancel => cancel())
        cancelDnsWalk = None

      /** Remember `nodes` as candidates; returns the ones not seen before. */
      def acceptDnsNodes(nodes: Set[Node]): Set[Node] =
        val fresh = nodes.filterNot(n => n.id == localNodeId || dnsNodes.contains(n))
        dnsNodes = dnsNodes ++ fresh
        fresh

      /** Hand DNS-found nodes to the running discovery service, as enrollment hands it the bootstrap nodes: bond with
        * each and fetch its ENR so it can join the routing table. Unbounded like enrollment; the walk delivers nodes
        * over seconds, not all at once.
        */
      def enrollDnsNodes(service: v4.DiscoveryService, nodes: Set[Node]): Unit =
        if nodes.nonEmpty then
          given IORuntime = runtime
          nodes.toList
            .parTraverse(node => service.addNode(toENode(node)).attempt)
            .unsafeToFuture()
            .onComplete {
              case Success(results) =>
                val failed = results.count(_.isLeft)
                if failed > 0 then
                  log.debug("{} of {} DNS node(s) could not be added to discovery", failed, results.size)
              case Failure(ex) => log.warn("Failed to add DNS nodes to discovery: {}", ex.getMessage)
            }(using runtime.compute)

      def startDiscoveryService(): Unit =
        given IORuntime = runtime
        discoveryResources.allocated
          .unsafeToFuture()
          .onComplete {
            case Failure(ex)     => ctx.self ! StartAttempt(Left(ex))
            case Success(result) => ctx.self ! StartAttempt(Right(result))
          }(using runtime.compute)

      def stopDiscoveryService(release: IO[Unit]): Unit =
        given IORuntime = runtime
        release
          .unsafeToFuture()
          .onComplete {
            case Failure(ex) => ctx.self ! StopAttempt(Left(ex))
            case Success(_)  => ctx.self ! StopAttempt(Right(()))
          }(using runtime.compute)

      def sendDiscoveredNodesInfo(
          maybeService: Option[v4.DiscoveryService],
          replyTo: ActorRef[DiscoveredNodesInfo]
      ): Unit =
        given IORuntime = runtime
        // Snapshot on the actor thread: the IO below runs later, on another one. DNS nodes are bootstrap-like, so
        // they are served exactly when the bootstrap nodes are.
        val dnsCandidates: Set[Node] = if discoveryConfig.reuseKnownNodes then dnsNodes else Set.empty
        val task: IO[DiscoveredNodesInfo] =
          val base: IO[Set[Node]] = maybeService.fold(IO.pure(Set.empty[Node])) {
            _.getNodes.map(_.map(toNode))
          }
          base
            .map(_ ++ alreadyDiscoveredNodes ++ dnsCandidates)
            .map(_.filterNot(n => n.id == localNodeId))
            .flatTap(nodes => IO(log.debug("Discovered nodes snapshot ({} total) sent", nodes.size.toString)))
            .map(DiscoveredNodesInfo(_))
        task.attempt
          .unsafeToFuture()
          .onComplete {
            case Success(Right(result)) => replyTo ! result
            case Success(Left(ex))      => log.error("Failed to get discovered nodes: {}", ex.getMessage)
            case Failure(ex)            => log.error("Unexpected failure getting discovered nodes: {}", ex.getMessage)
          }(using runtime.compute)

      def sendRandomNodeInfo(
          randomNodes: RandomNodes,
          replyTo: ActorRef[RandomNodeInfo]
      ): Unit =
        given IORuntime = runtime
        val task: IO[Option[RandomNodeInfo]] =
          // Queue.take is cancel-safe: a request that times out loses no node.
          randomNodes.take.map(Option(_)).timeoutTo(RandomNodeWait, IO.pure(None)).flatMap {
            case None =>
              IO(log.debug("No random node candidate within {}; the asker will retry", RandomNodeWait)).as(None)
            case Some(node) =>
              IO(log.debug("Random node candidate {} delivered", formatNodeForLogs(node)))
                .as(Some(RandomNodeInfo(node)))
          }
        task.attempt
          .unsafeToFuture()
          .onComplete {
            case Success(Right(Some(result))) => replyTo ! result
            case Success(Right(None))         => ()
            case Success(Left(ex))            => log.error("Failed to get random node: {}", ex.getMessage)
            case Failure(ex)                  => log.error("Unexpected failure getting random node: {}", ex.getMessage)
          }(using runtime.compute)

      // The service hasn't been started yet; serves static known nodes only.
      def init(): Behavior[Command] = Behaviors.receiveMessage {
        case GetDiscoveredNodesInfoReq(replyTo) =>
          sendDiscoveredNodesInfo(None, replyTo)
          Behaviors.same

        case GetRandomNodeInfoReq(_) =>
          // Discovery not running; no reply — caller retries on next tick.
          Behaviors.same

        case Start =>
          // DNS seeding is a candidate source in its own right, so it runs even with discv4 itself disabled.
          startDnsWalk()
          if discoveryConfig.discoveryEnabled then
            ctx.log.info("Starting peer discovery...")
            startDiscoveryService()
            starting()
          else
            ctx.log.info("Peer discovery is disabled.")
            Behaviors.same

        case DnsNodesFound(nodes) =>
          acceptDnsNodes(nodes)
          Behaviors.same

        case Stop =>
          stopDnsWalk()
          Behaviors.same

        case _: StartAttempt | _: StopAttempt =>
          Behaviors.same
      }

      // Waiting for discoveryResources.allocated to complete; still serves static nodes.
      def starting(): Behavior[Command] = Behaviors.receiveMessage {
        case GetDiscoveredNodesInfoReq(replyTo) =>
          sendDiscoveredNodesInfo(None, replyTo)
          Behaviors.same

        case GetRandomNodeInfoReq(_) =>
          Behaviors.same

        case Start =>
          Behaviors.same

        case DnsNodesFound(nodes) =>
          acceptDnsNodes(nodes)
          Behaviors.same

        case Stop =>
          ctx.log.info("Stopping peer discovery...")
          stopDnsWalk()
          stopping()

        case StartAttempt(Right((discovery, release))) =>
          ctx.log.info("Peer discovery started.")
          // DNS nodes that beat the service to it are enrolled now.
          enrollDnsNodes(discovery._1, dnsNodes)
          started(discovery, release)

        case StartAttempt(Left(ex)) =>
          ctx.log.warn(
            "Failed to start peer discovery; will keep running without discovery (static/known nodes only).",
            ex
          )
          init()

        case _: StopAttempt =>
          Behaviors.same
      }

      // Discovery service running; can serve live nodes.
      def started(discovery: Discovery, release: IO[Unit]): Behavior[Command] = Behaviors.receiveMessage {
        case GetDiscoveredNodesInfoReq(replyTo) =>
          sendDiscoveredNodesInfo(Some(discovery._1), replyTo)
          Behaviors.same

        case GetRandomNodeInfoReq(replyTo) =>
          sendRandomNodeInfo(discovery._2, replyTo)
          Behaviors.same

        case Start =>
          Behaviors.same

        case DnsNodesFound(nodes) =>
          enrollDnsNodes(discovery._1, acceptDnsNodes(nodes))
          Behaviors.same

        case Stop =>
          ctx.log.info("Stopping peer discovery...")
          stopDnsWalk()
          stopDiscoveryService(release)
          stopping()

        case _: StartAttempt | _: StopAttempt =>
          Behaviors.same
      }

      // Either stop was requested before start completed, or the running service is being shut down.
      def stopping(): Behavior[Command] = Behaviors.receiveMessage {
        case GetDiscoveredNodesInfoReq(replyTo) =>
          sendDiscoveredNodesInfo(None, replyTo)
          Behaviors.same

        case GetRandomNodeInfoReq(_) =>
          Behaviors.same

        case Start | Stop =>
          Behaviors.same

        case DnsNodesFound(nodes) =>
          acceptDnsNodes(nodes)
          Behaviors.same

        case StartAttempt(Right((_, release))) =>
          // Start completed just as we were stopping — immediately stop the newly started service.
          ctx.log.info("Peer discovery started, now stopping...")
          stopDiscoveryService(release)
          Behaviors.same

        case StartAttempt(Left(ex)) =>
          ctx.log.warn(
            "Failed to start peer discovery while stopping; discovery will remain disabled.",
            ex
          )
          init()

        case StopAttempt(Right(_)) =>
          ctx.log.info("Peer discovery stopped.")
          init()

        case StopAttempt(Left(ex)) =>
          ctx.log.error("Failed to stop peer discovery.", ex)
          init()
      }

      init()
    }

  private def toNode(enode: ENode): Node =
    Node(
      id = ByteString(enode.id.value.toByteArray),
      addr = enode.address.ip,
      tcpPort = enode.address.tcpPort,
      udpPort = enode.address.udpPort
    )

  private def toENode(node: Node): ENode =
    ENode(
      id = PublicKey(BitVector(node.id.toArray[Byte])),
      address = ENode.Address(ip = node.addr, udpPort = node.udpPort, tcpPort = node.tcpPort)
    )

  private def formatNodeForLogs(node: Node): String =
    val id = Hex.toHexString(node.id.take(6).toArray)
    s"${com.chipprbots.ethereum.network.getHostName(node.addr)}:${node.tcpPort}/$id"
