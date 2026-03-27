package com.chipprbots.ethereum.consensus.pow.miners

import org.apache.pekko.actor.{ActorRef => ClassicActorRef}
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Random

import com.chipprbots.ethereum.consensus.blocks.PendingBlock
import com.chipprbots.ethereum.consensus.blocks.PendingBlockAndState
import com.chipprbots.ethereum.consensus.pow.EthashUtils
import com.chipprbots.ethereum.consensus.pow.PoWBlockCreator
import com.chipprbots.ethereum.consensus.pow.PoWMiningCoordinator
import com.chipprbots.ethereum.consensus.pow.PoWMiningCoordinator.CoordinatorProtocol
import com.chipprbots.ethereum.consensus.pow.miners.MinerProtocol._
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.jsonrpc.EthMiningService
import com.chipprbots.ethereum.utils.BigIntExtensionMethods._
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteUtils
import com.chipprbots.ethereum.utils.Logger

/** Implementation of Ethash CPU mining worker. Could be started by switching configuration flag "mining.mining-enabled"
  * to true Implementation explanation at https://eth.wiki/concepts/ethash/ethash
  */
class EthashMiner(
    dagManager: EthashDAGManager,
    blockCreator: PoWBlockCreator,
    syncController: ClassicActorRef,
    ethMiningService: EthMiningService
)(implicit scheduler: IORuntime)
    extends Miner
    with Logger {

  import EthashMiner._

  def processMining(
      bestBlock: Block
  )(implicit blockchainConfig: BlockchainConfig): Future[CoordinatorProtocol] = {
    log.debug("Starting mining with parent block {}", bestBlock.number)
    blockCreator
      .getBlockForMining(bestBlock)
      .map { case PendingBlockAndState(PendingBlock(block, _), _) =>
        val blockNumber = block.header.number
        val (startTime, miningResult) = doMining(blockNumber.toLong, block)

        submitHashRate(ethMiningService, System.nanoTime() - startTime, miningResult)
        handleMiningResult(miningResult, syncController, block)
      }
      .handleError { ex =>
        log.error("Error occurred while mining: ", ex)
        PoWMiningCoordinator.MiningUnsuccessful
      }
      .unsafeToFuture()
  }

  private def doMining(blockNumber: Long, block: Block)(implicit
      blockchainConfig: BlockchainConfig
  ): (Long, MiningResult) = {
    val epoch =
      EthashUtils.epoch(blockNumber, blockchainConfig.forkBlockNumbers.ecip1099BlockNumber.toLong)
    val (dagLookup, dagSize) = dagManager.calculateDagSize(blockNumber, epoch)
    val headerHash = crypto.kec256(BlockHeader.getEncodedWithoutNonce(block.header))
    val startTime = System.nanoTime()
    val mineResult =
      mineEthash(headerHash, block.header.difficulty.toLong, dagSize, dagLookup, blockCreator.miningConfig.mineRounds)
    (startTime, mineResult)
  }

  private def mineEthash(
      headerHash: Array[Byte],
      difficulty: Long,
      dagSize: Long,
      dagLookup: Int => Array[Int],
      numRounds: Int
  ): MiningResult = {
    val numThreads = math.max(1, Runtime.getRuntime.availableProcessors() - 2) // reserve 2 for sync/RPC
    if (numThreads <= 1) {
      mineEthashSingleThread(headerHash, difficulty, dagSize, dagLookup, numRounds)
    } else {
      mineEthashParallel(headerHash, difficulty, dagSize, dagLookup, numRounds, numThreads)
    }
  }

  private def mineEthashParallel(
      headerHash: Array[Byte],
      difficulty: Long,
      dagSize: Long,
      dagLookup: Int => Array[Int],
      numRounds: Int,
      numThreads: Int
  ): MiningResult = {
    val roundsPerThread = numRounds / numThreads
    val found = new AtomicBoolean(false)
    implicit val ec: ExecutionContext = scheduler.compute

    val futures = (0 until numThreads).map { threadIdx =>
      Future {
        val rng = new Random()
        val initNonce = BigInt(NumBits, rng)
        val startRound = threadIdx * roundsPerThread
        val endRound = if (threadIdx == numThreads - 1) numRounds - startRound else roundsPerThread

        var round = 0
        var result: MiningResult = MiningUnsuccessful(endRound)
        while (round < endRound && !found.get()) {
          val nonce = (initNonce + round) % MaxNounce
          val nonceBytes = ByteUtils.padLeft(ByteString(nonce.toUnsignedByteArray), 8)
          val pow = EthashUtils.hashimoto(headerHash, nonceBytes.toArray[Byte], dagSize, dagLookup)
          if (EthashUtils.checkDifficulty(difficulty, pow)) {
            found.set(true)
            result = MiningSuccessful(round + 1, pow.mixHash, nonceBytes)
          }
          round += 1
        }
        result
      }
    }

    val results = Await.result(Future.sequence(futures), 5.minutes)
    results.collectFirst { case s: MiningSuccessful => s }
      .getOrElse(MiningUnsuccessful(numRounds))
  }

  private def mineEthashSingleThread(
      headerHash: Array[Byte],
      difficulty: Long,
      dagSize: Long,
      dagLookup: Int => Array[Int],
      numRounds: Int
  ): MiningResult = {
    val initNonce = BigInt(NumBits, new Random())

    (0 to numRounds).iterator
      .map { round =>
        val nonce = (initNonce + round) % MaxNounce
        val nonceBytes = ByteUtils.padLeft(ByteString(nonce.toUnsignedByteArray), 8)
        val pow = EthashUtils.hashimoto(headerHash, nonceBytes.toArray[Byte], dagSize, dagLookup)
        (EthashUtils.checkDifficulty(difficulty, pow), pow, nonceBytes, round)
      }
      .collectFirst { case (true, pow, nonceBytes, n) => MiningSuccessful(n + 1, pow.mixHash, nonceBytes) }
      .getOrElse(MiningUnsuccessful(numRounds))
  }
}

object EthashMiner {
  final val BlockForgerDispatcherId = "fukuii.async.dispatchers.block-forger"

  // scalastyle:off magic.number
  final val MaxNounce: BigInt = BigInt(2).pow(64) - 1

  final val NumBits: Int = 64

  final val DagFilePrefix: ByteString = ByteString(Array(0xfe, 0xca, 0xdd, 0xba, 0xad, 0xde, 0xe1, 0xfe).map(_.toByte))
}
