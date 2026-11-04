package com.chipprbots.ethereum.consensus.pow

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.AbstractBehavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import cats.effect.unsafe.IORuntime

import com.chipprbots.ethereum.consensus.pow.PoWMiningCoordinator.CoordinatorProtocol
import com.chipprbots.ethereum.consensus.pow.miners.EthashDAGManager
import com.chipprbots.ethereum.consensus.pow.miners.EthashMiner
import com.chipprbots.ethereum.consensus.pow.miners.Miner
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.jsonrpc.EthMiningService
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder

object PoWMiningCoordinator:
  trait CoordinatorProtocol

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

  private def handleMiningRecurrent(): Behavior[CoordinatorProtocol] = Behaviors.receiveMessage {
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
      Behaviors.stopped
  }

  private def handleMiningOnDemand(): Behavior[CoordinatorProtocol] = Behaviors.receiveMessage {
    case SetMiningMode(mode) =>
      log.info("Received message {}", SetMiningMode(mode))
      switchMiningMode(mode)
  }

  private def switchMiningMode(mode: MiningMode): Behavior[CoordinatorProtocol] = mode match
    case RecurrentMining =>
      context.self ! MineNext
      handleMiningRecurrent()
    case OnDemandMining => handleMiningOnDemand()

  private def mineWithEthash(bestBlock: Block): Unit =
    log.debug("Mining with Ethash")
    val miner = minerOpt.getOrElse(
      new EthashMiner(dagManager, blockCreator, syncController, ethMiningService)
    )
    mine(miner, bestBlock)

  private def mine(miner: Miner, bestBlock: Block): Unit =
    miner.processMining(bestBlock).foreach(_ => context.self ! MineNext)(using context.executionContext)
