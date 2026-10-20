package com.chipprbots.ethereum.network.discovery

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.Resource
import cats.effect.std.Queue
import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success

import com.chipprbots.scalanet.discovery.ethereum.Node as ENode
import com.chipprbots.scalanet.discovery.ethereum.v4
import org.bouncycastle.util.encoders.Hex

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
      randomNodeBufferSize: Int = 0
  )(using runtime: IORuntime): Behavior[Command] =
    Behaviors.setup { ctx =>
      // Capture ctx.log on the actor thread once; use this `log` val (plain SLF4J) in
      // IO/Future callbacks that run on CE worker threads — ctx.log itself enforces
      // checkCurrentActorThread() and will throw if called off-thread.
      val log = ctx.log

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

      def startDiscoveryService(): Unit =
        given IORuntime = runtime
        discoveryResources.allocated
          .unsafeToFuture()
          .onComplete {
            case Failure(ex)     => ctx.self ! StartAttempt(Left(ex))
            case Success(result) => ctx.self ! StartAttempt(Right(result))
          }(runtime.compute)

      def stopDiscoveryService(release: IO[Unit]): Unit =
        given IORuntime = runtime
        release
          .unsafeToFuture()
          .onComplete {
            case Failure(ex) => ctx.self ! StopAttempt(Left(ex))
            case Success(_)  => ctx.self ! StopAttempt(Right(()))
          }(runtime.compute)

      def sendDiscoveredNodesInfo(
          maybeService: Option[v4.DiscoveryService],
          replyTo: ActorRef[DiscoveredNodesInfo]
      ): Unit =
        given IORuntime = runtime
        val task: IO[DiscoveredNodesInfo] =
          val base: IO[Set[Node]] = maybeService.fold(IO.pure(Set.empty[Node])) {
            _.getNodes.map(_.map(toNode))
          }
          base
            .map(_ ++ alreadyDiscoveredNodes)
            .map(_.filterNot(n => n.id == localNodeId))
            .flatTap(nodes => IO(log.debug("Discovered nodes snapshot ({} total) sent", nodes.size.toString)))
            .map(DiscoveredNodesInfo(_))
        task.attempt
          .unsafeToFuture()
          .onComplete {
            case Success(Right(result)) => replyTo ! result
            case Success(Left(ex))      => log.error("Failed to get discovered nodes: {}", ex.getMessage)
            case Failure(ex)            => log.error("Unexpected failure getting discovered nodes: {}", ex.getMessage)
          }(runtime.compute)

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
          }(runtime.compute)

      // The service hasn't been started yet; serves static known nodes only.
      def init(): Behavior[Command] = Behaviors.receiveMessage {
        case GetDiscoveredNodesInfoReq(replyTo) =>
          sendDiscoveredNodesInfo(None, replyTo)
          Behaviors.same

        case GetRandomNodeInfoReq(_) =>
          // Discovery not running; no reply — caller retries on next tick.
          Behaviors.same

        case Start =>
          if discoveryConfig.discoveryEnabled then
            ctx.log.info("Starting peer discovery...")
            startDiscoveryService()
            starting()
          else
            ctx.log.info("Peer discovery is disabled.")
            Behaviors.same

        case Stop | _: StartAttempt | _: StopAttempt =>
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

        case Stop =>
          ctx.log.info("Stopping peer discovery...")
          stopping()

        case StartAttempt(Right((discovery, release))) =>
          ctx.log.info("Peer discovery started.")
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

        case Stop =>
          ctx.log.info("Stopping peer discovery...")
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

  private def formatNodeForLogs(node: Node): String =
    val id = Hex.toHexString(node.id.take(6).toArray)
    s"${com.chipprbots.ethereum.network.getHostName(node.addr)}:${node.tcpPort}/$id"
