package com.chipprbots.ethereum.jsonrpc

import java.time.Duration
import java.util.Date
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import cats.effect.IO
import cats.syntax.parallel.*

import scala.annotation.unused
import scala.collection.concurrent.Map as ConcurrentMap
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.FiniteDuration

import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.consensus.blocks.PendingBlockAndState
import com.chipprbots.ethereum.consensus.mining.CoinbaseProvider
import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.consensus.mining.ifEthash
import com.chipprbots.ethereum.consensus.pow.EthashUtils
import com.chipprbots.ethereum.consensus.pow.PoWMiningMetrics
import com.chipprbots.ethereum.consensus.pow.WorkNotifier
import com.chipprbots.ethereum.consensus.pow.miners.MinerProtocol
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController.JsonRpcConfig
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.ommers.OmmersPool
import com.chipprbots.ethereum.transactions.TransactionPicker

object EthMiningService:

  /** What `eth_getWork` and `eth_submitWork` answer on the unified state layout before its overlay. */
  val NoRouteForMinedBlocks: String =
    "fukuii.state.path-live is on: a mined block is imported by network sync, which this node does not run until " +
      "the unified state layout can move its head (spec 018 C4). No work is given and none is taken."

  case class GetMiningRequest()
  case class GetMiningResponse(isMining: Boolean)

  case class GetWorkRequest()
  case class GetWorkResponse(powHeaderHash: ByteString, dagSeed: ByteString, target: ByteString, blockNumber: BigInt)

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

class EthMiningService(
    blockchainReader: BlockchainReader,
    mining: Mining,
    jsonRpcConfig: JsonRpcConfig,
    ommersPool: typed.ActorRef[OmmersPool.Command],
    syncingController: TypedActorRef[SyncController.Command],
    val pendingTransactionsManager: TypedActorRef[
      com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command
    ],
    val getTransactionFromPoolTimeout: FiniteDuration,
    configBuilder: BlockchainConfigBuilder,
    coinbaseProvider: CoinbaseProvider,
    system: ActorSystem
) extends TransactionPicker:
  override lazy val scheduler: Scheduler = system.toTyped.scheduler
  import configBuilder.*
  import EthMiningService.*

  val hashRate: ConcurrentMap[ByteString, (BigInt, Date)] = new TrieMap[ByteString, (BigInt, Date)]()
  val lastActive = new AtomicReference[Option[Date]](None)

  def getMining(req: GetMiningRequest): ServiceResponse[GetMiningResponse] =
    ifEthash(req) { _ =>
      val isMining = lastActive.updateAndGet { (e: Option[Date]) =>
        e.filter { time =>
          Duration.between(time.toInstant, (new Date).toInstant).toMillis < jsonRpcConfig.minerActiveTimeout.toMillis
        }
      }.isDefined

      GetMiningResponse(isMining)
    }

  /** Why work is neither given nor taken on the unified state layout before its overlay; `None` with the guard off.
    *
    * A block that is mined reaches import through network sync, which a node on the in-development guard does not run
    * (spec 018 C3b): it could not follow the side branches and reorganisations that come with it. So a solution
    * submitted here would be answered `true` and the block dropped. Work is refused where it is asked for, and said
    * so, until the head can move (C4b). `mining.mining-enabled` is refused at start for the same reason; these two
    * methods are served with the internal miner off, so they say it themselves.
    */
  private def noRouteForMinedBlocks: Option[JsonRpcError] =
    Option.when(blockchainReader.holdsStateInPlace)(JsonRpcError.LogicError(EthMiningService.NoRouteForMinedBlocks))

  def getWork(req: GetWorkRequest): ServiceResponse[GetWorkResponse] =
    noRouteForMinedBlocks.fold(workFor(req))(refusal => IO.pure(Left(refusal)))

  def submitWork(req: SubmitWorkRequest): ServiceResponse[SubmitWorkResponse] =
    noRouteForMinedBlocks.fold(workSubmitted(req))(refusal => IO.pure(Left(refusal)))

  private def workFor(@unused req: GetWorkRequest): ServiceResponse[GetWorkResponse] =
    mining.ifEthash { ethash =>
      PoWMiningMetrics.recordGetWork()
      reportActive()
      blockchainReader.getBestBlock match
        case Some(block) =>
          (getOmmersFromPool(block.hash.value), getTransactionsFromPool).parMapN { case (ommers, pendingTxs) =>
            val blockGenerator = ethash.blockGenerator
            val PendingBlockAndState(pb, _) = blockGenerator.generateBlock(
              block,
              pendingTxs.pendingTransactions.map(_.stx.tx),
              coinbaseProvider.get(),
              ommers.headers,
              None
            )
            val powHeaderHash = ByteString(kec256(BlockHeader.getEncodedWithoutNonce(pb.block.header)))
            val dagSeed = EthashUtils
              .seed(
                pb.block.header.number.value.toLong,
                blockchainConfig.forkBlockNumbers.ecip1099BlockNumber.toLong
              )
            val target = ByteString((BigInt(2).pow(256) / pb.block.header.difficulty.value).toByteArray)
            val blockNumber = pb.block.header.number.value
            val workResponse = GetWorkResponse(powHeaderHash, dagSeed, target, blockNumber)
            val notifyUrls = ethash.config.generic.notifyUrls
            if notifyUrls.nonEmpty then
              WorkNotifier.notify(
                notifyUrls,
                WorkNotifier.WorkPackage(powHeaderHash, dagSeed, target, blockNumber)
              )(using system)
            Right(workResponse)
          }
        case None =>
          log.error("Getting current best block failed")
          IO.pure(Left(JsonRpcError.InternalError))
    }(IO.pure(Left(JsonRpcError.MiningIsNotEthash)))

  private def workSubmitted(req: SubmitWorkRequest): ServiceResponse[SubmitWorkResponse] =
    mining.ifEthash[ServiceResponse[SubmitWorkResponse]] { ethash =>
      reportActive()
      IO {
        ethash.blockGenerator.getPrepared(req.powHeaderHash) match
          case Some(pendingBlock) =>
            val bestBlockNum = blockchainReader.getBestBlockNumber
            val staleThreshold = ethash.config.generic.staleThreshold
            // core-geth reference: consensus/ethash/sealer.go staleThreshold check
            if bestBlockNum - pendingBlock.block.header.number.value > staleThreshold then
              log.debug(
                "Rejecting stale work submission for block {}, current best {}, threshold {}",
                pendingBlock.block.header.number,
                bestBlockNum,
                staleThreshold
              )
              PoWMiningMetrics.recordStaleShare()
              Right(SubmitWorkResponse(false))
            else
              import pendingBlock.*
              syncingController ! SyncController.WrappedSyncProtocol(
                SyncProtocol.MinedBlock(
                  block.copy(header = block.header.copy(nonce = req.nonce, mixHash = BlockHash(req.mixHash)))
                )
              )
              PoWMiningMetrics.recordBlockMined(0L) // duration tracked at coordinator level
              Right(SubmitWorkResponse(true))
          case _ =>
            Right(SubmitWorkResponse(false))
      }
    }(IO.pure(Left(JsonRpcError.MiningIsNotEthash)))

  def getCoinbase(@unused req: GetCoinbaseRequest): ServiceResponse[GetCoinbaseResponse] =
    IO.pure(Right(GetCoinbaseResponse(coinbaseProvider.get())))

  def submitHashRate(req: SubmitHashRateRequest): ServiceResponse[SubmitHashRateResponse] =
    ifEthash(req) { req =>
      reportActive()
      val now = new Date
      removeObsoleteHashrates(now)
      hashRate.put(req.id, req.hashRate -> now)
      PoWMiningMetrics.updateHashrate(hashRate.map { case (_, (hr, _)) => hr }.sum)
      SubmitHashRateResponse(true)
    }

  def getHashRate(req: GetHashRateRequest): ServiceResponse[GetHashRateResponse] =
    ifEthash(req) { _ =>
      removeObsoleteHashrates(new Date)
      // sum all reported hashRates
      GetHashRateResponse(hashRate.map { case (_, (hr, _)) => hr }.sum)
    }

  def startMiner(@unused req: StartMinerRequest): ServiceResponse[StartMinerResponse] =
    mining.ifEthash[ServiceResponse[StartMinerResponse]] { ethash =>
      IO {
        ethash.sendMiner(MinerProtocol.StartMining)
        log.info("Mining started via RPC")
        Right(StartMinerResponse(true))
      }
    }(IO.pure(Left(JsonRpcError.MiningIsNotEthash)))

  def stopMiner(@unused req: StopMinerRequest): ServiceResponse[StopMinerResponse] =
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
      log.info("Updated miner coinbase to {}", request.address)
      SetEtherbaseResponse(success = true)
    }

  // NOTE This is called from places that guarantee we are running Ethash consensus.
  private def removeObsoleteHashrates(now: Date): Unit =
    hashRate.filterInPlace { case (_, (_, reported)) =>
      Duration.between(reported.toInstant, now.toInstant).toMillis < jsonRpcConfig.minerActiveTimeout.toMillis
    }

  private def reportActive(): Option[Date] =
    val now = new Date()
    lastActive.updateAndGet(_ => Some(now))

  private def getOmmersFromPool(parentBlockHash: ByteString): IO[OmmersPool.Ommers] =
    mining.ifEthash { ethash =>
      val miningConfig = ethash.config.specific
      import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
      import org.apache.pekko.actor.typed.scaladsl.adapter.*
      given timeout: Timeout = Timeout(miningConfig.ommerPoolQueryTimeout)
      given scheduler: org.apache.pekko.actor.typed.Scheduler = system.toTyped.scheduler

      IO.fromFuture(IO(ommersPool.ask[OmmersPool.Ommers](OmmersPool.GetOmmers(parentBlockHash, _))))
        .handleError { ex =>
          log.error("failed to get ommer, mining block with empty ommers list", ex)
          OmmersPool.Ommers(Nil)
        }
    }(IO.pure(OmmersPool.Ommers(Nil))) // NOTE If not Ethash consensus, ommers do not make sense, so => Nil

  private[jsonrpc] def ifEthash[Req, Res](req: Req)(f: Req => Res): ServiceResponse[Res] =
    mining.ifEthash[ServiceResponse[Res]](_ => IO.pure(Right(f(req))))(
      IO.pure(Left(JsonRpcError.MiningIsNotEthash))
    )
