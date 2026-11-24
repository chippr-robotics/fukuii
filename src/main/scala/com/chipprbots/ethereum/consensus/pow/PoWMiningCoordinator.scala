package com.chipprbots.ethereum.consensus.pow

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.PostStop
import org.apache.pekko.actor.typed.Signal
import org.apache.pekko.actor.typed.scaladsl.AbstractBehavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.util.Timeout

import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.consensus.pow.PoWMiningCoordinator.CoordinatorProtocol
import com.chipprbots.ethereum.consensus.pow.miners.EthashDAGManager
import com.chipprbots.ethereum.consensus.pow.miners.EthashMiner
import com.chipprbots.ethereum.consensus.pow.miners.Miner
import com.chipprbots.ethereum.consensus.pow.miners.SettledHeadBuild
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.jsonrpc.EthMiningService
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder

object PoWMiningCoordinator:
  trait CoordinatorProtocol

  /** How long the miner waits for a move of the head to end before a turn is given up (spec 018 B-293). */
  val HeadSettleWaitMillis: Long = 2000L

  /** How long the miner waits before it asks again whether it can mine, while it cannot (B-424): the node is syncing,
    * or holds no best block, or not the state of it. A turn is asked for again after this, never at once: at once, a
    * node that cannot mine spun on the actor and logged an ERROR per turn (19 k a second during a SNAP sync).
    */
  val NotReadyRetryDelay: FiniteDuration = 2.seconds

  /** How long the miner waits for the sync controller to say whether it is syncing. No answer counts as syncing: the
    * controller answers nothing while it imports a checkpoint, which it does inside its start.
    */
  val SyncStatusTimeout: FiniteDuration = 5.seconds

  final case class SetMiningMode(mode: MiningMode) extends CoordinatorProtocol

  case object MineNext extends CoordinatorProtocol

  case object StopMining extends CoordinatorProtocol

  case object MiningSuccessful extends CoordinatorProtocol

  case object MiningUnsuccessful extends CoordinatorProtocol

  /** The sync controller's answer to the miner's GetStatus, or the ask's failure (B-424). */
  final private case class SyncStatusReply(status: Try[SyncProtocol.Status]) extends CoordinatorProtocol

  /** The key of the timer that asks for a turn again while the miner cannot mine: one at a time. */
  private case object NotReadyRetryKey

  // MiningMode will allow to remove MockerMiner
  enum MiningMode:
    case RecurrentMining // for normal mining
    case OnDemandMining // for testing

  sealed trait MiningResponse

  case object MiningComplete extends MiningResponse

  def apply(
      syncController: TypedActorRef[com.chipprbots.ethereum.blockchain.sync.SyncController.Command],
      ethMiningService: EthMiningService,
      blockCreator: PoWBlockCreator,
      blockchainReader: BlockchainReader,
      configBuilder: BlockchainConfigBuilder,
      minerOpt: Option[Miner] = None,
      notReadyRetryDelay: FiniteDuration = NotReadyRetryDelay,
      syncStatusTimeout: FiniteDuration = SyncStatusTimeout
  ): Behavior[CoordinatorProtocol] =
    Behaviors
      .setup[CoordinatorProtocol](context =>
        Behaviors.withTimers[CoordinatorProtocol](timers =>
          new PoWMiningCoordinator(
            context,
            timers,
            syncController,
            ethMiningService,
            blockCreator,
            blockchainReader,
            configBuilder,
            minerOpt,
            notReadyRetryDelay,
            syncStatusTimeout
          )
        )
      )

class PoWMiningCoordinator private (
    context: ActorContext[CoordinatorProtocol],
    timers: TimerScheduler[CoordinatorProtocol],
    syncController: TypedActorRef[SyncController.Command],
    ethMiningService: EthMiningService,
    blockCreator: PoWBlockCreator,
    blockchainReader: BlockchainReader,
    configBuilder: BlockchainConfigBuilder,
    minerOpt: Option[Miner],
    notReadyRetryDelay: FiniteDuration,
    syncStatusTimeout: FiniteDuration
) extends AbstractBehavior[CoordinatorProtocol](context):

  import configBuilder.*
  import PoWMiningCoordinator.*
  import PoWMiningCoordinator.MiningMode.*

  // CE3: Using global IORuntime for typed actor operations
  implicit private val scheduler: IORuntime = IORuntime.global
  private val log = context.log
  private val dagManager = new EthashDAGManager(blockCreator)

  // B-424, core-geth's miner.update: mining waits while the node syncs, until the sync has once reached the head peers
  // announced. From then on a sync event does not pause it, or a peer that announces a block it never sends would stop
  // this node's mining for good (go-ethereum stops listening to the downloader after its first DoneEvent).
  private var syncedOnce: Boolean = false

  // Why the miner is waiting, if it is: said once when the wait begins or its reason changes, not on every retry.
  private var waitingFor: Option[String] = None

  override def onMessage(msg: CoordinatorProtocol): Behavior[CoordinatorProtocol] = msg match
    case SetMiningMode(mode) =>
      log.info("Received message {}", SetMiningMode(mode))
      switchMiningMode(mode)

  // Spec 029 slice 7: whatever stops the coordinator (StopMining, a stop from outside, a failure), the miner-running
  // gauge reads 0 afterwards. Pekko delivers PostStop to the behaviour current when the actor stopped, so each of the
  // three behaviours carries the handler: this one before the first SetMiningMode, and the two below.
  override def onSignal: PartialFunction[Signal, Behavior[CoordinatorProtocol]] = { case PostStop =>
    PoWMiningMetrics.recordMinerRunning(false)
    this
  }

  private def recordStoppedOnPostStop(
      behavior: Behaviors.Receive[CoordinatorProtocol]
  ): Behavior[CoordinatorProtocol] =
    behavior.receiveSignal { case (_, PostStop) =>
      PoWMiningMetrics.recordMinerRunning(false)
      Behaviors.same
    }

  private def handleMiningRecurrent(): Behavior[CoordinatorProtocol] =
    recordStoppedOnPostStop(Behaviors.receiveMessage[CoordinatorProtocol] {
      case SetMiningMode(mode) =>
        log.info("Received message {}", SetMiningMode(mode))
        switchMiningMode(mode)

      case MineNext =>
        log.debug("Received message MineNext")
        if syncedOnce then mineOnBestBlock() else askSyncStatus()
        Behaviors.same

      case SyncStatusReply(reply) =>
        reply match
          case Success(SyncProtocol.Status.SyncDone) =>
            if !syncedOnce then
              log.info("Sync has reached the head peers announced: sync events no longer pause mining")
              syncedOnce = true
            mineOnBestBlock()
          // nothing to catch up to: a lone miner, or peers no higher than this node (go-ethereum mines then too)
          case Success(SyncProtocol.Status.NotSyncing) => mineOnBestBlock()
          case Success(_: SyncProtocol.Status.Syncing) => waitForTurn("the node is syncing")
          case Success(SyncProtocol.Status.Stopped(why)) =>
            waitForTurn(s"the sync has stopped ($why); restart the node")
          case Failure(_) =>
            waitForTurn("the sync controller did not say whether it is syncing (starting, or importing a checkpoint)")
        Behaviors.same

      case StopMining =>
        // B-279: paused, not stopped. PoWMining keeps this actor's ref for the node's life and spawns it only once, at
        // start, so a stopped coordinator left every later miner_start messaging a dead actor while answering success.
        // Paused, it mines nothing and waits for SetMiningMode (miner_start) to resume.
        log.info("Mining stopped; the coordinator waits for a start")
        timers.cancel(NotReadyRetryKey)
        waitingFor = None
        PoWMiningMetrics.recordMinerRunning(false)
        paused()
    })

  /** Mining stopped (miner_stop): nothing is mined; a start resumes it. A reply to work asked for before the stop, a
    * turn or a sync status, is dropped.
    */
  private def paused(): Behavior[CoordinatorProtocol] =
    recordStoppedOnPostStop(Behaviors.receiveMessage[CoordinatorProtocol] {
      case SetMiningMode(mode) =>
        log.info("Received message {}", SetMiningMode(mode))
        switchMiningMode(mode)
      case StopMining => Behaviors.same
      case _          => Behaviors.same
    })

  private def handleMiningOnDemand(): Behavior[CoordinatorProtocol] =
    recordStoppedOnPostStop(Behaviors.receiveMessage[CoordinatorProtocol] { case SetMiningMode(mode) =>
      log.info("Received message {}", SetMiningMode(mode))
      switchMiningMode(mode)
    })

  private def switchMiningMode(mode: MiningMode): Behavior[CoordinatorProtocol] = mode match
    case RecurrentMining =>
      PoWMiningMetrics.recordMinerRunning(true)
      context.self ! MineNext
      handleMiningRecurrent()
    case OnDemandMining =>
      // On-demand mining (tests only) mines nothing by itself: not "running", also after a switch from recurrent.
      PoWMiningMetrics.recordMinerRunning(false)
      timers.cancel(NotReadyRetryKey)
      handleMiningOnDemand()

  private def askSyncStatus(): Unit =
    given Timeout = Timeout(syncStatusTimeout)
    context.ask[SyncController.Command, SyncProtocol.Status](
      syncController,
      replyTo => SyncController.WrappedSyncProtocol(SyncProtocol.GetStatus(replyTo))
    )(SyncStatusReply(_))

  /** Mines on the best block if the node holds it and its state (B-424); otherwise asks for a turn again later. */
  private def mineOnBestBlock(): Unit =
    blockchainReader.getBestBlock match
      case None => waitForTurn("the best block is not stored (its header only, during a SNAP sync)")
      case Some(block) if !blockchainReader.holdsStateOf(block.header) =>
        waitForTurn("the state of the best block is not held")
      case Some(block) =>
        waitingFor.foreach(_ => log.info("Mining resumes on block {}", block.number))
        waitingFor = None
        mineWithEthash(block)

  private def waitForTurn(why: String): Unit =
    if !waitingFor.contains(why) then
      log.info("Mining waits: {}. Asked again every {}", why, notReadyRetryDelay)
      waitingFor = Some(why)
    timers.startSingleTimer(NotReadyRetryKey, MineNext, notReadyRetryDelay)

  private def mineWithEthash(bestBlock: Block): Unit =
    log.debug("Mining with Ethash")
    val miner = minerOpt.getOrElse(
      new EthashMiner(
        dagManager,
        blockCreator,
        syncController,
        ethMiningService,
        Some(new SettledHeadBuild(blockchainReader, PoWMiningCoordinator.HeadSettleWaitMillis, SettledHeadBuild.Builds))
      )
    )
    mine(miner, bestBlock)

  private def mine(miner: Miner, bestBlock: Block): Unit =
    miner.processMining(bestBlock).foreach(_ => context.self ! MineNext)(using context.executionContext)
