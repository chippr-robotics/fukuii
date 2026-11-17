package com.chipprbots.ethereum.consensus.pow

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.PostStop
import org.apache.pekko.actor.typed.Signal
import org.apache.pekko.actor.typed.scaladsl.AbstractBehavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import cats.effect.unsafe.IORuntime

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

  final case class SetMiningMode(mode: MiningMode) extends CoordinatorProtocol

  case object MineNext extends CoordinatorProtocol

  case object StopMining extends CoordinatorProtocol

  case object MiningSuccessful extends CoordinatorProtocol

  case object MiningUnsuccessful extends CoordinatorProtocol

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
      minerOpt: Option[Miner] = None
  ): Behavior[CoordinatorProtocol] =
    Behaviors
      .setup[CoordinatorProtocol](context =>
        new PoWMiningCoordinator(
          context,
          syncController,
          ethMiningService,
          blockCreator,
          blockchainReader,
          configBuilder,
          minerOpt
        )
      )

class PoWMiningCoordinator private (
    context: ActorContext[CoordinatorProtocol],
    syncController: TypedActorRef[com.chipprbots.ethereum.blockchain.sync.SyncController.Command],
    ethMiningService: EthMiningService,
    blockCreator: PoWBlockCreator,
    blockchainReader: BlockchainReader,
    configBuilder: BlockchainConfigBuilder,
    minerOpt: Option[Miner]
) extends AbstractBehavior[CoordinatorProtocol](context):

  import configBuilder.*
  import PoWMiningCoordinator.*
  import PoWMiningCoordinator.MiningMode.*

  // CE3: Using global IORuntime for typed actor operations
  implicit private val scheduler: IORuntime = IORuntime.global
  private val log = context.log
  private val dagManager = new EthashDAGManager(blockCreator)

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
        blockchainReader.getBestBlock
          .fold {
            log.error("Unable to get block for mining: blockchainReader.getBestBlock returned None")
            context.self ! MineNext
          } { block =>
            mineWithEthash(block)
          }
        Behaviors.same

      case StopMining =>
        log.info("Stopping PoWMiningCoordinator...")
        PoWMiningMetrics.recordMinerRunning(false)
        Behaviors.stopped
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
      handleMiningOnDemand()

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
