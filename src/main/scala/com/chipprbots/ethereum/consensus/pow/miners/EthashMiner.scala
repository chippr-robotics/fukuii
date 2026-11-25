package com.chipprbots.ethereum.consensus.pow.miners

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import scala.concurrent.Future
import scala.util.Random

import com.chipprbots.ethereum.consensus.blocks.PendingBlock
import com.chipprbots.ethereum.consensus.blocks.PendingBlockAndState
import com.chipprbots.ethereum.consensus.pow.EthashUtils
import com.chipprbots.ethereum.consensus.pow.PoWBlockCreator
import com.chipprbots.ethereum.consensus.pow.PoWMiningCoordinator
import com.chipprbots.ethereum.consensus.pow.PoWMiningCoordinator.CoordinatorProtocol
import com.chipprbots.ethereum.consensus.pow.miners.MinerProtocol.*
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.jsonrpc.EthMiningService
import com.chipprbots.ethereum.utils.BigIntExtensionMethods.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteUtils
import com.chipprbots.ethereum.utils.Logger

/** Implementation of Ethash CPU mining worker. Could be started by switching configuration flag "mining.mining-enabled"
  * to true Implementation explanation at https://eth.wiki/concepts/ethash/ethash
  */
class EthashMiner(
    dagManager: EthashDAGManager,
    blockCreator: PoWBlockCreator,
    syncController: TypedActorRef[com.chipprbots.ethereum.blockchain.sync.SyncController.Command],
    ethMiningService: EthMiningService,
    // spec 018 B-293: the head is read, and the block built on it, under the head's generation; none builds on the
    // block it is handed, as unit suites do
    settledHead: Option[SettledHeadBuild] = None
)(implicit scheduler: IORuntime)
    extends Miner
    with Logger:

  import EthashMiner.*

  def processMining(
      bestBlock: Block
  )(implicit blockchainConfig: BlockchainConfig): Future[CoordinatorProtocol] =
    val built = settledHead.fold(blockCreator.getBlockForMining(bestBlock).map(made => Right((bestBlock, made))))(
      _(blockCreator.getBlockForMining(_))
    )
    built
      .map {
        case Left(why) =>
          // before the nonce search, so no work is lost (forge): the coordinator asks again at once
          log.info("No block is mined this turn: {}", why)
          PoWMiningCoordinator.MiningUnsuccessful
        case Right((parent, PendingBlockAndState(PendingBlock(block, _), _))) =>
          log.debug("Mining on parent block {}", parent.number)
          minedOn(block)
      }
      .handleError { ex =>
        log.error("Error occurred while mining: ", ex)
        PoWMiningCoordinator.MiningUnsuccessful
      }
      .unsafeToFuture()

  private def minedOn(block: Block)(implicit blockchainConfig: BlockchainConfig): CoordinatorProtocol =
    val (startTime, miningResult) = doMining(block.header.number.toLong, block)
    val elapsedNanos = System.nanoTime() - startTime
    submitHashRate(ethMiningService, elapsedNanos, miningResult)
    handleMiningResult(miningResult, syncController, block, elapsedNanos / 1_000_000L)

  private def doMining(blockNumber: Long, block: Block)(implicit
      blockchainConfig: BlockchainConfig
  ): (Long, MiningResult) =
    val epoch =
      EthashUtils.epoch(blockNumber, blockchainConfig.forkBlockNumbers.ecip1099BlockNumber.toLong)
    val (dag, dagSize) = dagManager.calculateDagSize(blockNumber, epoch)
    val headerHash = crypto.kec256(BlockHeader.getEncodedWithoutNonce(block.header))
    val startTime = System.nanoTime()
    val mineResult =
      mineEthash(headerHash, block.header.difficulty.value, dagSize, dag, blockCreator.miningConfig.mineRounds)
    (startTime, mineResult)

  private def mineEthash(
      headerHash: Array[Byte],
      difficulty: BigInt,
      dagSize: Long,
      dag: MappedDag,
      numRounds: Int
  ): MiningResult =
    val initNonce = BigInt(NumBits, new Random())

    (0 to numRounds).iterator
      .map { round =>
        val nonce = (initNonce + round) % MaxNounce
        val nonceBytes = ByteUtils.padLeft(ByteString(nonce.toUnsignedByteArray), 8)
        val pow = EthashUtils.hashimoto(headerHash, nonceBytes.toArray[Byte], dagSize, dag.item)
        (EthashUtils.checkDifficulty(difficulty, pow), pow, nonceBytes, round)
      }
      .collectFirst { case (true, pow, nonceBytes, n) => MiningSuccessful(n + 1, pow.mixHash, nonceBytes) }
      .getOrElse(MiningUnsuccessful(numRounds))

object EthashMiner:
  final val BlockForgerDispatcherId = "fukuii.async.dispatchers.block-forger"

  // scalastyle:off magic.number
  final val MaxNounce: BigInt = BigInt(2).pow(64) - 1

  final val NumBits: Int = 64

  final val DagFilePrefix: ByteString = ByteString(Array(0xfe, 0xca, 0xdd, 0xba, 0xad, 0xde, 0xe1, 0xfe).map(_.toByte))
