package com.chipprbots.ethereum.consensus.pow.miners

import org.apache.pekko.actor.typed
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.DispatcherSelector
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success

import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.consensus.blocks.PendingBlockAndState
import com.chipprbots.ethereum.consensus.mining.wrongMiningArgument
import com.chipprbots.ethereum.consensus.pow.PoWBlockCreator
import com.chipprbots.ethereum.consensus.pow.PoWMining
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.Command
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MineBlock
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MineBlocks
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MiningFailed
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MockBlockMined
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MockedMinerResponse
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MockedMinerResponses.MinerIsWorking
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MockedMinerResponses.MinerNotSupported
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MockedMinerResponses.MiningError
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MockedMinerResponses.MiningOrdered
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.Send
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.StartMining
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.StopMining
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.nodebuilder.Node
import com.chipprbots.ethereum.utils.ByteStringUtils

object MockedMiner:
  final val BlockForgerDispatcherId = "fukuii.async.dispatchers.block-forger"

  /** Ticks, one second each, that the miner waits for the import of the block it mined before it gives the run up (the
    * unified state layout, where the next block is built on that block's state as the disk holds it).
    */
  final val MaxImportWaits = 10

  sealed trait Command

  /** Typed-actor envelope: carries an external [[MockedMinerProtocol]] message together with the reply target. The
    * Classic `sender()` reply pattern is replaced by this explicit `replyTo`.
    */
  case class Send(msg: MockedMinerProtocol, replyTo: typed.ActorRef[MockedMinerResponse]) extends Command

  // Internal self-messages.
  private[miners] case object MineBlock extends Command
  private[miners] case class MockBlockMined(result: PendingBlockAndState) extends Command
  private[miners] case class MiningFailed(t: Throwable) extends Command

  // External protocol — unchanged, kept for the Mining `askMiner`/`sendMiner` API and QAService.
  sealed trait MockedMinerProtocol extends MinerProtocol
  case object StartMining extends MockedMinerProtocol
  case object StopMining extends MockedMinerProtocol

  case class MineBlocks(numBlocks: Int, withTransactions: Boolean, parentBlock: Option[ByteString] = None)
      extends MockedMinerProtocol

  def apply(
      blockchainReader: BlockchainReader,
      blockCreator: PoWBlockCreator,
      syncEventListener: TypedActorRef[SyncController.Command],
      configBuilder: BlockchainConfigBuilder
  ): Behavior[Command] =
    Behaviors.setup { context =>
      new MockedMiner(context, blockchainReader, blockCreator, syncEventListener, configBuilder).stopped()
    }

  def spawn(node: Node): typed.ActorRef[Command] =
    node.mining match
      case mining: PoWMining =>
        val blockCreator = new PoWBlockCreator(
          pendingTransactionsManager = node.pendingTransactionsManager,
          getTransactionFromPoolTimeout = node.txPoolConfig.getTransactionFromPoolTimeout,
          mining = mining,
          ommersPool = node.ommersPool,
          coinbaseProvider = node.coinbaseProvider,
          system = node.system.classicSystem
        )
        node.system.classicSystem.spawn(
          MockedMiner(
            blockchainReader = node.blockchainReader,
            blockCreator = blockCreator,
            syncEventListener = node.syncController,
            configBuilder = node
          ),
          "MockedMiner",
          DispatcherSelector.fromConfig(BlockForgerDispatcherId)
        )
      case mining =>
        wrongMiningArgument[PoWMining](mining)

  trait MockedMinerResponse

  object MockedMinerResponses:
    case object MinerIsWorking extends MockedMinerResponse

    case object MiningOrdered extends MockedMinerResponse

    case object MinerNotExist extends MockedMinerResponse

    case class MiningError(errorMsg: String) extends MockedMinerResponse

    case class MinerNotSupported(msg: MockedMinerProtocol) extends MockedMinerResponse

private class MockedMiner(
    context: ActorContext[Command],
    blockchainReader: BlockchainReader,
    blockCreator: PoWBlockCreator,
    syncEventListener: TypedActorRef[SyncController.Command],
    configBuilder: BlockchainConfigBuilder
):
  import configBuilder.*
  // CE3: Using global IORuntime for actor operations
  implicit private val runtime: cats.effect.unsafe.IORuntime = cats.effect.unsafe.IORuntime.global

  private def log = context.log

  def stopped(): Behavior[Command] = Behaviors.receiveMessage {
    case Send(StartMining, _) => waiting()
    case Send(msg, replyTo) =>
      replyTo ! MinerNotSupported(msg)
      Behaviors.same
    case _ => Behaviors.same
  }

  def waiting(): Behavior[Command] = Behaviors.receiveMessage {
    case Send(StopMining, _) => stopped()
    case Send(mineBlocks: MineBlocks, replyTo) =>
      mineBlocks.parentBlock match
        case Some(parentHash) =>
          blockchainReader.getBlockByHash(BlockHash(parentHash)) match
            case Some(parentBlock) => startMiningBlocks(mineBlocks, parentBlock, replyTo)
            case None =>
              val error = s"Unable to get parent block with hash ${ByteStringUtils.hash2string(parentHash)} for mining"
              replyTo ! MiningError(error)
              Behaviors.same
        case None =>
          blockchainReader.getBestBlock
            .fold {
              replyTo ! MiningError("Unable to get best block for mining")
              Behaviors.same[Command]
            } { parentBlock =>
              startMiningBlocks(mineBlocks, parentBlock, replyTo)
            }
    case _ => Behaviors.same
  }

  private def startMiningBlocks(
      mineBlocks: MineBlocks,
      parentBlock: Block,
      replyTo: typed.ActorRef[MockedMinerResponse]
  ): Behavior[Command] =
    context.self ! MineBlock
    replyTo ! MiningOrdered
    working(mineBlocks.numBlocks, mineBlocks.withTransactions, parentBlock, None)

  /** On the unified state layout, what the miner does about a parent whose state is not the one on disk: the block is
    * built on a view of the parent, which is there once the parent's import has committed it and not before.
    */
  private enum ParentState:
    case Held, Awaited, Lost

  private def stateOf(parentBlock: Block): ParentState =
    if blockchainReader.isHeldInPlace(parentBlock.header.hash.value) then ParentState.Held
    else
      blockchainReader.heldInPlace match
        // the state has reached the parent's height, or passed it, as another block's: the parent was refused, or a
        // block beside it was imported, and no wait will bring its state
        case Some((number, _)) if number >= parentBlock.header.number.value => ParentState.Lost
        case _                                                              => ParentState.Awaited

  /** One more tick for the import of `parentBlock`, with the count of blocks left as it was; or the end of the run when
    * [[MockedMiner.MaxImportWaits]] ticks have gone by.
    */
  private def awaitImport(
      numBlocks: Int,
      withTransactions: Boolean,
      parentBlock: Block,
      importWaits: Int
  ): Behavior[Command] =
    if importWaits >= MockedMiner.MaxImportWaits then
      log.error(
        "Mocked mining stopped: block {} was not imported within {} s; {} block(s) were not mined",
        parentBlock.idTag,
        MockedMiner.MaxImportWaits,
        numBlocks
      )
      waiting()
    else
      log.debug("Mocked mining: waiting for the import of block {} ({})", parentBlock.idTag, importWaits + 1)
      context.scheduleOnce(1.second, context.self, MineBlock)
      working(numBlocks, withTransactions, parentBlock, None, importWaits + 1)

  def working(
      numBlocks: Int,
      withTransactions: Boolean,
      parentBlock: Block,
      initialWorldStateBeforeExecution: Option[InMemoryWorldStateProxy],
      importWaits: Int = 0
  ): Behavior[Command] = Behaviors.receiveMessage {
    case Send(_: MineBlocks, replyTo) =>
      replyTo ! MinerIsWorking
      Behaviors.same

    case MineBlock =>
      def build(): Behavior[Command] =
        context.pipeToSelf(
          blockCreator
            .getBlockForMining(parentBlock, withTransactions, initialWorldStateBeforeExecution)
            .unsafeToFuture()
        ) {
          case Success(result) => MockBlockMined(result)
          case Failure(t)      => MiningFailed(t)
        }
        Behaviors.same
      if numBlocks <= 0 then
        log.info(s"Mining all mocked blocks successful")
        waiting()
      else if !blockchainReader.holdsStateInPlace then build()
      else
        stateOf(parentBlock) match
          case ParentState.Held    => build()
          case ParentState.Awaited => awaitImport(numBlocks, withTransactions, parentBlock, importWaits)
          case ParentState.Lost =>
            log.error(
              "Mocked mining stopped: the state on disk is not that of block {}, and is not behind it; {} block(s) " +
                "were not mined",
              parentBlock.idTag,
              numBlocks
            )
            waiting()

    case MockBlockMined(PendingBlockAndState(pendingBlock, state)) =>
      val minedBlock = pendingBlock.block
      log.info(
        s"Mining mocked block {} successful. Included transactions: {}",
        minedBlock.idTag,
        minedBlock.body.transactionList.map(_.hash.toHex)
      )
      // ROOT-c: syncEventListener (= node.syncController) is now a Behavior[Command] ref; wrap the raw SyncProtocol
      // send so it survives the Typed boundary (a bare send would ClassCastException → dead-letter and the mined
      // block would never reach RegularSync).
      syncEventListener ! SyncController.WrappedSyncProtocol(SyncProtocol.MinedBlock(minedBlock))
      // because of using seconds to calculate block timestamp, we can't mine blocks faster than one block per second
      context.scheduleOnce(1.second, context.self, MineBlock)
      // On the unified state layout the next block is built on the mined block's state as its import leaves it on
      // disk, never on the world this build left: that world reads through a view of a state the disk moves away from.
      val inherited = if blockchainReader.holdsStateInPlace then None else Some(state)
      working(numBlocks - 1, withTransactions, minedBlock, inherited)

    case MiningFailed(_: com.chipprbots.ethereum.db.storage.pathstate.StateFault)
        if blockchainReader.holdsStateInPlace && stateOf(parentBlock) != ParentState.Lost =>
      // The state moved between the check and the build, or the build's view went stale: the same wait.
      awaitImport(numBlocks, withTransactions, parentBlock, importWaits)

    case MiningFailed(t) =>
      log.error("Unable to get block for mining", t)
      waiting()

    case Send(StopMining, _) => stopped()

    case Send(StartMining, _) => Behaviors.same

    case _ => Behaviors.same
  }
