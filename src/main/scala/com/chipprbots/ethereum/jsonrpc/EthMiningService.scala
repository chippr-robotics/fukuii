package com.chipprbots.ethereum.jsonrpc

import java.time.Duration
import java.util.Date
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import cats.effect.IO
import cats.syntax.parallel._

import scala.collection.concurrent.TrieMap
import scala.collection.concurrent.{Map => ConcurrentMap}
import scala.concurrent.duration.FiniteDuration

import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.consensus.blocks.PendingBlockAndState
import com.chipprbots.ethereum.consensus.mining.CoinbaseProvider
import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.consensus.mining.MiningConfig
import com.chipprbots.ethereum.consensus.mining.RichMining
import com.chipprbots.ethereum.consensus.pow.EthashUtils
import com.chipprbots.ethereum.consensus.pow.miners.MinerProtocol
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps._
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController.JsonRpcConfig
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.ommers.OmmersPool
import com.chipprbots.ethereum.transactions.TransactionPicker

object EthMiningService {

  case class GetMiningRequest()
  case class GetMiningResponse(isMining: Boolean)

  case class GetWorkRequest()
  case class GetWorkResponse(powHeaderHash: ByteString, dagSeed: ByteString, target: ByteString)

  case class SubmitWorkRequest(nonce: ByteString, powHeaderHash: ByteString, mixHash: ByteString)
  case class SubmitWorkResponse(success: Boolean)

  case class GetCoinbaseRequest()
  case class GetCoinbaseResponse(address: Address)

  case class SubmitHashRateRequest(hashRate: BigInt, id: ByteString)
  case class SubmitHashRateResponse(success: Boolean)

  case class GetHashRateRequest()
  case class GetHashRateResponse(hashRate: BigInt)

  case class StartMinerRequest()
  case class StartMinerResponse(success: Boolean)

  case class StopMinerRequest()
  case class StopMinerResponse(success: Boolean)

  case class GetMinerStatusRequest()
  case class GetMinerStatusResponse(
      isMining: Boolean,
      coinbase: Address,
      hashRate: BigInt,
      blocksMinedCount: Option[Long]
  )

  case class SetEtherbaseRequest(address: Address)
  case class SetEtherbaseResponse(success: Boolean)
}

class EthMiningService(
    blockchainReader: BlockchainReader,
    mining: Mining,
    jsonRpcConfig: JsonRpcConfig,
    ommersPool: ActorRef,
    syncingController: ActorRef,
    val pendingTransactionsManager: ActorRef,
    val getTransactionFromPoolTimeout: FiniteDuration,
    configBuilder: BlockchainConfigBuilder,
    coinbaseProvider: CoinbaseProvider
) extends TransactionPicker {
  import configBuilder._
  import EthMiningService._

  private[this] def fullConsensusConfig = mining.config
  private[this] def miningConfig: MiningConfig = fullConsensusConfig.generic

  val hashRate: ConcurrentMap[ByteString, (BigInt, Date)] = new TrieMap[ByteString, (BigInt, Date)]()
  val lastActive = new AtomicReference[Option[Date]](None)
  private val currentEtherbase = new AtomicReference[Address](miningConfig.coinbase)

  def getMining(req: GetMiningRequest): ServiceResponse[GetMiningResponse] =
    ifEthash(req) { _ =>
      val isMining = lastActive.updateAndGet { (e: Option[Date]) =>
        e.filter { time =>
          Duration.between(time.toInstant, (new Date).toInstant).toMillis < jsonRpcConfig.minerActiveTimeout.toMillis
        }
      }.isDefined

      GetMiningResponse(isMining)
    }

  def getWork(req: GetWorkRequest): ServiceResponse[GetWorkResponse] =
    mining.ifEthash { ethash =>
      reportActive()
      blockchainReader.getBestBlock() match {
        case Some(block) =>
          (getOmmersFromPool(block.hash), getTransactionsFromPool).parMapN { case (ommers, pendingTxs) =>
            val blockGenerator = ethash.blockGenerator
            val PendingBlockAndState(pb, _) = blockGenerator.generateBlock(
              block,
              pendingTxs.pendingTransactions.map(_.stx.tx),
              currentEtherbase.get(),
              ommers.headers,
              None
            )
            Right(
              GetWorkResponse(
                powHeaderHash = ByteString(kec256(BlockHeader.getEncodedWithoutNonce(pb.block.header))),
                dagSeed = EthashUtils
                  .seed(
                    pb.block.header.number.toLong,
                    blockchainConfig.forkBlockNumbers.ecip1099BlockNumber.toLong
                  ),
                target = ByteString((BigInt(2).pow(256) / pb.block.header.difficulty).toByteArray)
              )
            )
          }
        case None =>
          log.error("Getting current best block failed")
          IO.pure(Left(JsonRpcError.InternalError))
      }
    }(IO.pure(Left(JsonRpcError.MiningIsNotEthash)))

  def submitWork(req: SubmitWorkRequest): ServiceResponse[SubmitWorkResponse] =
    mining.ifEthash[ServiceResponse[SubmitWorkResponse]] { ethash =>
      reportActive()
      IO {
        ethash.blockGenerator.getPrepared(req.powHeaderHash) match {
          case Some(pendingBlock) if blockchainReader.getBestBlockNumber() <= pendingBlock.block.header.number =>
            import pendingBlock._
            syncingController ! SyncProtocol.MinedBlock(
              block.copy(header = block.header.copy(nonce = req.nonce, mixHash = req.mixHash))
            )
            Right(SubmitWorkResponse(true))
          case _ =>
            Right(SubmitWorkResponse(false))
        }
      }
    }(IO.pure(Left(JsonRpcError.MiningIsNotEthash)))

  def getCoinbase(req: GetCoinbaseRequest): ServiceResponse[GetCoinbaseResponse] =
    IO.pure(Right(GetCoinbaseResponse(coinbaseProvider.get())))

  def submitHashRate(req: SubmitHashRateRequest): ServiceResponse[SubmitHashRateResponse] =
    ifEthash(req) { req =>
      reportActive()
      val now = new Date
      removeObsoleteHashrates(now)
      hashRate.put(req.id, req.hashRate -> now)
      SubmitHashRateResponse(true)
    }

  def getHashRate(req: GetHashRateRequest): ServiceResponse[GetHashRateResponse] =
    ifEthash(req) { _ =>
      removeObsoleteHashrates(new Date)
      // sum all reported hashRates
      GetHashRateResponse(hashRate.map { case (_, (hr, _)) => hr }.sum)
    }

  def startMiner(req: StartMinerRequest): ServiceResponse[StartMinerResponse] =
    mining.ifEthash[ServiceResponse[StartMinerResponse]] { ethash =>
      IO {
        ethash.sendMiner(MinerProtocol.StartMining)
        log.info("Mining started via RPC")
        Right(StartMinerResponse(true))
      }
    }(IO.pure(Left(JsonRpcError.MiningIsNotEthash)))

  def stopMiner(req: StopMinerRequest): ServiceResponse[StopMinerResponse] =
    mining.ifEthash[ServiceResponse[StopMinerResponse]] { ethash =>
      IO {
        ethash.sendMiner(MinerProtocol.StopMining)
        log.info("Mining stopped via RPC")
        Right(StopMinerResponse(true))
      }
    }(IO.pure(Left(JsonRpcError.MiningIsNotEthash)))

  /** Returns comprehensive mining status information.
    *
    * Provides a consolidated view of the mining state including:
    *   - Whether the node is actively mining (based on recent activity)
    *   - The coinbase address receiving mining rewards
    *   - Current aggregate hashrate from all connected miners
    *   - Blocks mined count (currently reserved for future implementation)
    *
    * Note: blocksMinedCount is always None in the current implementation. Future versions may track and report this
    * metric.
    */
  def getMinerStatus(req: GetMinerStatusRequest): ServiceResponse[GetMinerStatusResponse] =
    ifEthash(req) { _ =>
      val now = new Date
      val isMining = lastActive.updateAndGet { (e: Option[Date]) =>
        e.filter { time =>
          Duration.between(time.toInstant, now.toInstant).toMillis < jsonRpcConfig.minerActiveTimeout.toMillis
        }
      }.isDefined

      removeObsoleteHashrates(now)
      val currentHashRate = hashRate.map { case (_, (hr, _)) => hr }.sum

      GetMinerStatusResponse(
        isMining = isMining,
        coinbase = coinbaseProvider.get(),
        hashRate = currentHashRate,
        blocksMinedCount = None // Reserved for future implementation - would require tracking mined blocks
      )
    }

  def setEtherbase(req: SetEtherbaseRequest): ServiceResponse[SetEtherbaseResponse] =
    ifEthash(req) { request =>
      coinbaseProvider.update(request.address)
      log.info("Updated miner coinbase via eth_setEtherbase to {}", request.address)
      SetEtherbaseResponse(success = true)
    }

  // NOTE This is called from places that guarantee we are running Ethash consensus.
  private def removeObsoleteHashrates(now: Date): Unit =
    hashRate.filterInPlace { case (_, (_, reported)) =>
      Duration.between(reported.toInstant, now.toInstant).toMillis < jsonRpcConfig.minerActiveTimeout.toMillis
    }

  private def reportActive(): Option[Date] = {
    val now = new Date()
    lastActive.updateAndGet(_ => Some(now))
  }

  private def getOmmersFromPool(parentBlockHash: ByteString): IO[OmmersPool.Ommers] =
    mining.ifEthash { ethash =>
      val miningConfig = ethash.config.specific
      implicit val timeout: Timeout = Timeout(miningConfig.ommerPoolQueryTimeout)

      ommersPool
        .askFor[OmmersPool.Ommers](OmmersPool.GetOmmers(parentBlockHash))
        .handleError { ex =>
          log.error("failed to get ommer, mining block with empty ommers list", ex)
          OmmersPool.Ommers(Nil)
        }
    }(IO.pure(OmmersPool.Ommers(Nil))) // NOTE If not Ethash consensus, ommers do not make sense, so => Nil

  private[jsonrpc] def ifEthash[Req, Res](req: Req)(f: Req => Res): ServiceResponse[Res] =
    mining.ifEthash[ServiceResponse[Res]](_ => IO.pure(Right(f(req))))(
      IO.pure(Left(JsonRpcError.MiningIsNotEthash))
    )
}
