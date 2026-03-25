package com.chipprbots.ethereum.consensus.pow

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.AbstractBehavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.actor.{ActorRef => ClassicActorRef}

import cats.effect.unsafe.IORuntime

import scala.concurrent.duration._

import com.chipprbots.ethereum.consensus.mining.MiningConfig
import com.chipprbots.ethereum.consensus.pow.PoWMiningCoordinator.CoordinatorProtocol
import com.chipprbots.ethereum.consensus.pow.miners.EthashDAGManager
import com.chipprbots.ethereum.consensus.pow.miners.EthashMiner
import com.chipprbots.ethereum.consensus.pow.miners.Miner
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.jsonrpc.EthMiningService
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder

object PoWMiningCoordinator {
  trait CoordinatorProtocol

  final case class SetMiningMode(mode: MiningMode) extends CoordinatorProtocol

  case object MineNext extends CoordinatorProtocol

  case object StopMining extends CoordinatorProtocol

  /** Periodic work regeneration with latest pending transactions (geth recommit equivalent) */
  case object RecommitWork extends CoordinatorProtocol

  /** Dynamically update the recommit interval without restart (geth miner_setRecommitInterval equivalent) */
  final case class SetRecommitInterval(interval: FiniteDuration) extends CoordinatorProtocol

  case object MiningSuccessful extends CoordinatorProtocol

  case object MiningUnsuccessful extends CoordinatorProtocol

  // MiningMode will allow to remove MockerMiner
  sealed trait MiningMode

  case object RecurrentMining extends MiningMode // for normal mining

  case object OnDemandMining extends MiningMode // for testing

  sealed trait MiningResponse

  case object MiningComplete extends MiningResponse

  private val RecommitTimerKey = "recommit"

  def apply(
      syncController: ClassicActorRef,
      ethMiningService: EthMiningService,
      blockCreator: PoWBlockCreator,
      blockchainReader: BlockchainReader,
      configBuilder: BlockchainConfigBuilder,
      miningConfig: MiningConfig,
      minerOpt: Option[Miner] = None
  ): Behavior[CoordinatorProtocol] =
    Behaviors.withTimers[CoordinatorProtocol] { timers =>
      Behaviors
        .setup[CoordinatorProtocol](context =>
          new PoWMiningCoordinator(
            context,
            timers,
            syncController,
            ethMiningService,
            blockCreator,
            blockchainReader,
            configBuilder,
            miningConfig,
            minerOpt
          )
        )
    }
}

class PoWMiningCoordinator private (
    context: ActorContext[CoordinatorProtocol],
    timers: TimerScheduler[CoordinatorProtocol],
    syncController: ClassicActorRef,
    ethMiningService: EthMiningService,
    blockCreator: PoWBlockCreator,
    blockchainReader: BlockchainReader,
    configBuilder: BlockchainConfigBuilder,
    miningConfig: MiningConfig,
    minerOpt: Option[Miner]
) extends AbstractBehavior[CoordinatorProtocol](context) {

  import configBuilder._
  import PoWMiningCoordinator._

  // CE3: Using global IORuntime for typed actor operations
  implicit private val scheduler: IORuntime = IORuntime.global
  5.seconds
  private val log = context.log
  private val dagManager = new EthashDAGManager(blockCreator)

  // Start recommit timer if configured (geth miner.recommit equivalent)
  if (miningConfig.recommitInterval > Duration.Zero) {
    timers.startTimerWithFixedDelay(RecommitTimerKey, RecommitWork, miningConfig.recommitInterval)
    log.info(s"Mining recommit timer started: interval=${miningConfig.recommitInterval}")
  }

  override def onMessage(msg: CoordinatorProtocol): Behavior[CoordinatorProtocol] = msg match {
    case SetMiningMode(mode) =>
      log.info("Received message {}", SetMiningMode(mode))
      switchMiningMode(mode)
  }

  private def handleMiningRecurrent(): Behavior[CoordinatorProtocol] = Behaviors.receiveMessage {
    case SetMiningMode(mode) =>
      log.info("Received message {}", SetMiningMode(mode))
      switchMiningMode(mode)

    case MineNext =>
      log.debug("Received message MineNext")
      blockchainReader
        .getBestBlock()
        .fold {
          log.error("Unable to get block for mining: blockchainReader.getBestBlock() returned None")
          context.self ! MineNext
        } { block =>
          mineWithEthash(block)
        }
      Behaviors.same

    case RecommitWork =>
      // Regenerate work with latest pending transactions (geth recommit).
      // Triggers getWork via the RPC service which rebuilds the block template,
      // caches the new work, and fires HTTP notifications if configured.
      log.debug("Recommit: regenerating work with latest pending transactions")
      import cats.effect.unsafe.implicits.global
      ethMiningService
        .getWork(EthMiningService.GetWorkRequest())
        .unsafeRunAsync {
          case Right(Right(_)) => log.debug("Recommit: work regenerated successfully")
          case Right(Left(err)) => log.warn(s"Recommit: work regeneration failed: $err")
          case Left(ex)         => log.warn(s"Recommit: work regeneration error: ${ex.getMessage}")
        }
      Behaviors.same

    case SetRecommitInterval(interval) =>
      if (interval > Duration.Zero) {
        timers.startTimerWithFixedDelay(RecommitTimerKey, RecommitWork, interval)
        log.info(s"Recommit interval updated to $interval")
      } else {
        timers.cancel(RecommitTimerKey)
        log.info("Recommit timer disabled")
      }
      Behaviors.same

    case StopMining =>
      log.info("Stopping PoWMiningCoordinator...")
      timers.cancel(RecommitTimerKey)
      Behaviors.stopped
  }

  private def handleMiningOnDemand(): Behavior[CoordinatorProtocol] = Behaviors.receiveMessage {
    case SetMiningMode(mode) =>
      log.info("Received message {}", SetMiningMode(mode))
      switchMiningMode(mode)
  }

  private def switchMiningMode(mode: MiningMode): Behavior[CoordinatorProtocol] = mode match {
    case RecurrentMining =>
      context.self ! MineNext
      handleMiningRecurrent()
    case OnDemandMining => handleMiningOnDemand()
  }

  private def mineWithEthash(bestBlock: Block): Unit = {
    log.debug("Mining with Ethash")
    val miner = minerOpt.getOrElse(
      new EthashMiner(dagManager, blockCreator, syncController, ethMiningService)
    )
    mine(miner, bestBlock)
  }

  private def mine(miner: Miner, bestBlock: Block): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global
    miner.processMining(bestBlock).foreach(_ => context.self ! MineNext)
  }
}
