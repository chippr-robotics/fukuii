package com.chipprbots.ethereum
package consensus
package pow

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.DispatcherSelector
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.util.Timeout

import cats.effect.IO

import scala.concurrent.duration._

import com.chipprbots.ethereum.consensus.blocks.TestBlockGenerator
import com.chipprbots.ethereum.consensus.difficulty.DifficultyCalculator
import com.chipprbots.ethereum.consensus.mining.FullMiningConfig
import com.chipprbots.ethereum.consensus.mining.Protocol
import com.chipprbots.ethereum.consensus.mining.Protocol.AdditionalPoWProtocolData
import com.chipprbots.ethereum.consensus.mining.Protocol.MockedPow
import com.chipprbots.ethereum.consensus.mining.Protocol.NoAdditionalPoWData
import com.chipprbots.ethereum.consensus.mining.Protocol.PoW
import com.chipprbots.ethereum.consensus.mining.Protocol.RestrictedPoW
import com.chipprbots.ethereum.consensus.mining.Protocol.RestrictedPoWMinerData
import com.chipprbots.ethereum.consensus.mining.TestMining
import com.chipprbots.ethereum.consensus.mining.wrongMiningArgument
import com.chipprbots.ethereum.consensus.mining.wrongValidatorsArgument
import com.chipprbots.ethereum.consensus.pow.PoWMiningCoordinator.CoordinatorProtocol
import com.chipprbots.ethereum.consensus.pow.blocks.PoWBlockGenerator
import com.chipprbots.ethereum.consensus.pow.blocks.PoWBlockGeneratorImpl
import com.chipprbots.ethereum.consensus.pow.blocks.RestrictedPoWBlockGeneratorImpl
import com.chipprbots.ethereum.consensus.pow.miners.MinerProtocol
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MockedMinerProtocol
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MockedMinerResponse
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MockedMinerResponses.MinerNotExist
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.consensus.validators.Validators
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.domain.BlockchainImpl
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps.TaskActorOps
import com.chipprbots.ethereum.ledger.BlockPreparator
import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.nodebuilder.Node
import com.chipprbots.ethereum.utils.Logger

/** Implements standard Ethereum mining (Proof of Work).
  */
class PoWMining private (
    val vm: VMImpl,
    evmCodeStorage: EvmCodeStorage,
    blockchain: BlockchainImpl,
    blockchainReader: BlockchainReader,
    val config: FullMiningConfig[EthashConfig],
    val validators: ValidatorsExecutor,
    val blockGenerator: PoWBlockGenerator,
    val difficultyCalculator: DifficultyCalculator
) extends TestMining
    with Logger {

  type Config = EthashConfig

  final private[this] val _blockPreparator = new BlockPreparator(
    vm = vm,
    signedTxValidator = validators.signedTransactionValidator,
    blockchain = blockchain,
    blockchainReader = blockchainReader
  )

  @volatile private[pow] var minerCoordinatorRef: Option[ActorRef[CoordinatorProtocol]] = None
  // TODO in ETCM-773 remove MockedMiner
  @volatile private[pow] var mockedMinerRef: Option[org.apache.pekko.actor.ActorRef] = None

  final val BlockForgerDispatcherId = "mantis.async.dispatchers.block-forger"
  implicit private val timeout: Timeout = 5.seconds

  override def sendMiner(msg: MinerProtocol): Unit =
    msg match {
      case mineBlocks: MockedMiner.MineBlocks => mockedMinerRef.foreach(_ ! mineBlocks)
      case MinerProtocol.StartMining =>
        mockedMinerRef.foreach(_ ! MockedMiner.StartMining)
        minerCoordinatorRef.foreach(_ ! PoWMiningCoordinator.SetMiningMode(PoWMiningCoordinator.RecurrentMining))
      case MinerProtocol.StopMining =>
        mockedMinerRef.foreach(_ ! MockedMiner.StopMining)
        minerCoordinatorRef.foreach(_ ! PoWMiningCoordinator.StopMining)
      case _ => log.warn("SendMiner method received unexpected message {}", msg)
    }

  // no interactions are done with minerCoordinatorRef using the ask pattern
  override def askMiner(msg: MockedMinerProtocol): IO[MockedMinerResponse] =
    mockedMinerRef
      .map(_.askFor[MockedMinerResponse](msg))
      .getOrElse(IO.pure(MinerNotExist))

  private[this] val mutex = new Object

  /*
   * guarantees one miner instance
   * this should not use a atomic* construct as it has side-effects
   *
   * TODO further refactors should focus on extracting two types - one with a miner, one without - based on the config
   */
  private[this] def startMiningProcess(node: Node, blockCreator: PoWBlockCreator): Unit =
    mutex.synchronized {
      if (minerCoordinatorRef.isEmpty && mockedMinerRef.isEmpty) {
        config.generic.protocol match {
          case PoW | RestrictedPoW =>
            log.info("Instantiating PoWMiningCoordinator")
            minerCoordinatorRef = Some(
              node.system.spawn(
                PoWMiningCoordinator(
                  node.syncController,
                  node.ethMiningService,
                  blockCreator,
                  blockchainReader,
                  node.blockchainConfig.forkBlockNumbers.ecip1049BlockNumber,
                  node
                ),
                "PoWMinerCoordinator",
                DispatcherSelector.fromConfig(BlockForgerDispatcherId)
              )
            )
          case MockedPow =>
            log.info("Instantiating MockedMiner")
            mockedMinerRef = Some(MockedMiner(node))
        }
        sendMiner(MinerProtocol.StartMining)
      }
    }

  private[this] def stopMiningProcess(): Unit =
    sendMiner(MinerProtocol.StopMining)

  /** This is used by the [[Mining#blockGenerator blockGenerator]].
    */
  def blockPreparator: BlockPreparator = this._blockPreparator

  /** Starts the mining protocol on the current `node`.
    */
  def startProtocol(node: Node): Unit =
    if (config.miningEnabled) {
      log.info("Mining is enabled. Will try to start configured miner actor")
      val blockCreator = node.mining match {
        case mining: PoWMining =>
          new PoWBlockCreator(
            pendingTransactionsManager = node.pendingTransactionsManager,
            getTransactionFromPoolTimeout = node.txPoolConfig.getTransactionFromPoolTimeout,
            mining = mining,
            ommersPool = node.ommersPool
          )
        case mining => wrongMiningArgument[PoWMining](mining)
      }

      startMiningProcess(node, blockCreator)
    } else log.info("Not starting any miner actor because mining is disabled")

  def stopProtocol(): Unit =
    if (config.miningEnabled) {
      stopMiningProcess()
    }

  def protocol: Protocol = Protocol.PoW

  /** Internal API, used for testing */
  protected def newBlockGenerator(validators: Validators): PoWBlockGenerator =
    validators match {
      case _validators: ValidatorsExecutor =>
        val blockPreparator = new BlockPreparator(
          vm = vm,
          signedTxValidator = validators.signedTransactionValidator,
          blockchain = blockchain,
          blockchainReader = blockchainReader
        )

        new PoWBlockGeneratorImpl(
          evmCodeStorage = evmCodeStorage,
          validators = _validators,
          blockchainReader = blockchainReader,
          miningConfig = config.generic,
          blockPreparator = blockPreparator,
          difficultyCalculator,
          blockTimestampProvider = blockGenerator.blockTimestampProvider
        )

      case _ =>
        wrongValidatorsArgument[ValidatorsExecutor](validators)
    }

  /** Internal API, used for testing */
  def withValidators(validators: Validators): PoWMining =
    validators match {
      case _validators: ValidatorsExecutor =>
        val blockGenerator = newBlockGenerator(validators)

        new PoWMining(
          vm = vm,
          evmCodeStorage = evmCodeStorage,
          blockchain = blockchain,
          blockchainReader = blockchainReader,
          config = config,
          validators = _validators,
          blockGenerator = blockGenerator,
          difficultyCalculator
        )

      case _ => wrongValidatorsArgument[ValidatorsExecutor](validators)
    }

  def withVM(vm: VMImpl): PoWMining =
    new PoWMining(
      vm = vm,
      evmCodeStorage = evmCodeStorage,
      blockchain = blockchain,
      blockchainReader = blockchainReader,
      config = config,
      validators = validators,
      blockGenerator = blockGenerator,
      difficultyCalculator
    )

  /** Internal API, used for testing */
  def withBlockGenerator(blockGenerator: TestBlockGenerator): PoWMining =
    new PoWMining(
      evmCodeStorage = evmCodeStorage,
      vm = vm,
      blockchain = blockchain,
      blockchainReader = blockchainReader,
      config = config,
      validators = validators,
      blockGenerator = blockGenerator.asInstanceOf[PoWBlockGenerator],
      difficultyCalculator = difficultyCalculator
    )

}

object PoWMining {
  // scalastyle:off method.length
  def apply(
      vm: VMImpl,
      evmCodeStorage: EvmCodeStorage,
      blockchain: BlockchainImpl,
      blockchainReader: BlockchainReader,
      config: FullMiningConfig[EthashConfig],
      validators: ValidatorsExecutor,
      additionalEthashProtocolData: AdditionalPoWProtocolData
  ): PoWMining = {
    val difficultyCalculator = DifficultyCalculator
    val blockPreparator = new BlockPreparator(
      vm = vm,
      signedTxValidator = validators.signedTransactionValidator,
      blockchain = blockchain,
      blockchainReader = blockchainReader
    )
    val blockGenerator = additionalEthashProtocolData match {
      case RestrictedPoWMinerData(key) =>
        new RestrictedPoWBlockGeneratorImpl(
          evmCodeStorage = evmCodeStorage,
          validators = validators,
          blockchainReader = blockchainReader,
          miningConfig = config.generic,
          blockPreparator = blockPreparator,
          difficultyCalc = difficultyCalculator,
          minerKeyPair = key
        )
      case NoAdditionalPoWData =>
        new PoWBlockGeneratorImpl(
          evmCodeStorage = evmCodeStorage,
          validators = validators,
          blockchainReader = blockchainReader,
          miningConfig = config.generic,
          blockPreparator = blockPreparator,
          difficultyCalc = difficultyCalculator
        )
    }
    new PoWMining(
      vm = vm,
      evmCodeStorage = evmCodeStorage,
      blockchain = blockchain,
      blockchainReader = blockchainReader,
      config = config,
      validators = validators,
      blockGenerator = blockGenerator,
      difficultyCalculator
    )
  }
}
