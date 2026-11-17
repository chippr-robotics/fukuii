package com.chipprbots.ethereum.consensus.pow.miners

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.util.ByteString

import scala.concurrent.Future

import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.consensus.pow.PoWMiningCoordinator
import com.chipprbots.ethereum.consensus.pow.PoWMiningCoordinator.CoordinatorProtocol
import com.chipprbots.ethereum.consensus.pow.PoWMiningMetrics
import com.chipprbots.ethereum.consensus.pow.miners.MinerProtocol.MiningResult
import com.chipprbots.ethereum.consensus.pow.miners.MinerProtocol.MiningSuccessful
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.jsonrpc.EthMiningService
import com.chipprbots.ethereum.jsonrpc.EthMiningService.SubmitHashRateRequest
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.utils.Logger

trait Miner extends Logger:
  def processMining(bestBlock: Block)(implicit
      blockchainConfig: BlockchainConfig
  ): Future[CoordinatorProtocol]

  /** Hands a sealed block to the sync controller, and counts it in `pow_blocks_mined_total`.
    *
    * "Mined" means what it means for `eth_submitWork`: the sealed block was handed to the sync controller, before its
    * import is known. `miningDurationMs` is the nonce search's time when the miner measured it, 0 when it did not (as
    * `eth_submitWork` records). The count is taken after the send and is atomic sets only, so it cannot change or stop
    * what is sent.
    */
  def handleMiningResult(
      miningResult: MiningResult,
      syncController: TypedActorRef[SyncController.Command],
      block: Block,
      miningDurationMs: Long = 0L
  ): CoordinatorProtocol =
    miningResult match
      case MiningSuccessful(_, mixHash, nonce) =>
        log.info(
          "Mining successful with {} and nonce {}",
          ByteStringUtils.hash2string(mixHash),
          ByteStringUtils.hash2string(nonce)
        )

        syncController ! SyncController.WrappedSyncProtocol(
          SyncProtocol.MinedBlock(
            block.copy(header = block.header.copy(nonce = nonce, mixHash = BlockHash(mixHash)))
          )
        )
        PoWMiningMetrics.recordBlockMined(miningDurationMs)
        PoWMiningCoordinator.MiningSuccessful
      case _ =>
        log.info("Mining unsuccessful")
        PoWMiningCoordinator.MiningUnsuccessful

  def submitHashRate(ethMiningService: EthMiningService, time: Long, mineResult: MiningResult): Unit =
    val hashRate = if time > 0 then (mineResult.triedHashes.toLong * 1000000000) / time else Long.MaxValue
    ethMiningService.submitHashRate(SubmitHashRateRequest(hashRate, ByteString("fukuii-miner")))
