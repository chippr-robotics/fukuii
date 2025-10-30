package com.chipprbots.ethereum.consensus.pow.miners

import org.apache.pekko.util.ByteString

import scala.concurrent.Future
import cats.effect.unsafe.IORuntime

import scala.util.Random

import com.chipprbots.ethereum.consensus.blocks.PendingBlock
import com.chipprbots.ethereum.consensus.blocks.PendingBlockAndState
import com.chipprbots.ethereum.consensus.pow.KeccakCalculation
import com.chipprbots.ethereum.consensus.pow.PoWBlockCreator
import com.chipprbots.ethereum.consensus.pow.PoWMiningCoordinator
import com.chipprbots.ethereum.consensus.pow.PoWMiningCoordinator.CoordinatorProtocol
import com.chipprbots.ethereum.consensus.pow.miners.MinerProtocol.MiningResult
import com.chipprbots.ethereum.consensus.pow.miners.MinerProtocol.MiningSuccessful
import com.chipprbots.ethereum.consensus.pow.miners.MinerProtocol.MiningUnsuccessful
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.jsonrpc.EthMiningService
import com.chipprbots.ethereum.utils.BigIntExtensionMethods.BigIntAsUnsigned
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteUtils
import com.chipprbots.ethereum.utils.Logger

class KeccakMiner(
    blockCreator: PoWBlockCreator,
    syncController: org.apache.pekko.actor.ActorRef,
    ethMiningService: EthMiningService
)(implicit scheduler: IORuntime)
    extends Miner
    with Logger {

  import KeccakMiner._

  def processMining(
      bestBlock: Block
  )(implicit blockchainConfig: BlockchainConfig): Future[CoordinatorProtocol] = {
    log.debug("Starting mining with parent block {}", bestBlock.number)
    blockCreator
      .getBlockForMining(bestBlock)
      .map { case PendingBlockAndState(PendingBlock(block, _), _) =>
        val (startTime, miningResult) = doMining(block, blockCreator.miningConfig.mineRounds)

        submitHashRate(ethMiningService, System.nanoTime() - startTime, miningResult)
        handleMiningResult(miningResult, syncController, block)
      }
      .onErrorHandle { ex =>
        log.error("Error occurred while mining: ", ex)
        PoWMiningCoordinator.MiningUnsuccessful
      }
      .runToFuture
  }

  private def doMining(block: Block, numRounds: Int): (Long, MiningResult) = {
    val rlpEncodedHeader = BlockHeader.getEncodedWithoutNonce(block.header)
    val initNonce = BigInt(64, new Random()) // scalastyle:ignore magic.number
    val startTime = System.nanoTime()

    val mined = (0 to numRounds).iterator
      .map { round =>
        val nonce = (initNonce + round) % MaxNonce
        val difficulty = block.header.difficulty
        val hash = KeccakCalculation.hash(rlpEncodedHeader, nonce)
        (KeccakCalculation.isMixHashValid(hash.mixHash, difficulty), hash, nonce, round)
      }
      .collectFirst { case (true, hash, nonce, n) =>
        val nonceBytes = ByteUtils.padLeft(ByteString(nonce.toUnsignedByteArray), 8)
        MiningSuccessful(n + 1, ByteString(hash.mixHash), nonceBytes)
      }
      .getOrElse(MiningUnsuccessful(numRounds))

    (startTime, mined)
  }
}

object KeccakMiner {
  val MaxNonce: BigInt = BigInt(2).pow(64) - 1 // scalastyle:ignore magic.number
}
