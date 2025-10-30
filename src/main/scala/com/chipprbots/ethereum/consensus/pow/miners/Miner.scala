package com.chipprbots.ethereum.consensus.pow.miners

import org.apache.pekko.actor.{ActorRef => ClassicActorRef}
import org.apache.pekko.util.ByteString

import scala.concurrent.Future

import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.consensus.pow.PoWMiningCoordinator
import com.chipprbots.ethereum.consensus.pow.PoWMiningCoordinator.CoordinatorProtocol
import com.chipprbots.ethereum.consensus.pow.miners.MinerProtocol.MiningResult
import com.chipprbots.ethereum.consensus.pow.miners.MinerProtocol.MiningSuccessful
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.jsonrpc.EthMiningService
import com.chipprbots.ethereum.jsonrpc.EthMiningService.SubmitHashRateRequest
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.utils.Logger

trait Miner extends Logger {
  def processMining(bestBlock: Block)(implicit
      blockchainConfig: BlockchainConfig
  ): Future[CoordinatorProtocol]

  def handleMiningResult(
      miningResult: MiningResult,
      syncController: ClassicActorRef,
      block: Block
  ): CoordinatorProtocol =
    miningResult match {
      case MiningSuccessful(_, mixHash, nonce) =>
        log.info(
          "Mining successful with {} and nonce {}",
          ByteStringUtils.hash2string(mixHash),
          ByteStringUtils.hash2string(nonce)
        )

        syncController ! SyncProtocol.MinedBlock(
          block.copy(header = block.header.copy(nonce = nonce, mixHash = mixHash))
        )
        PoWMiningCoordinator.MiningSuccessful
      case _ =>
        log.info("Mining unsuccessful")
        PoWMiningCoordinator.MiningUnsuccessful
    }

  def submitHashRate(ethMiningService: EthMiningService, time: Long, mineResult: MiningResult): Unit = {
    val hashRate = if (time > 0) (mineResult.triedHashes.toLong * 1000000000) / time else Long.MaxValue
    ethMiningService.submitHashRate(SubmitHashRateRequest(hashRate, ByteString("mantis-miner")))
  }
}
