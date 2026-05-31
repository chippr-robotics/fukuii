package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.{ Actor, ActorLogging, Props }

import scala.concurrent.{ Future, ExecutionContext }
import scala.concurrent.blocking
import scala.util.{ Failure, Success }

import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain.{ BlockchainReader, BlockchainWriter }

/** Runs ChainWeightRepair.repairBidirectional asynchronously so startRegularSync is not blocked.
  *
  * The anchor block must have a correct canonical TD already stored — typically seeded by the
  * operator via -Dfukuii.seed-chain-weights=HASH:TD before this actor is spawned.
  *
  * Lifecycle: Start → spawns Future → RepairDone|RepairError → notifies parent → stops.
  */
class ChainWeightRepairActor(
    anchorBlock: BigInt,
    reader: BlockchainReader,
    writer: BlockchainWriter,
    appStateStorage: AppStateStorage
) extends Actor
    with ActorLogging {

  import ChainWeightRepairActor._

  private val slog = org.slf4j.LoggerFactory.getLogger(getClass)

  implicit val ec: ExecutionContext = context.system.dispatchers.lookup("sync-dispatcher")

  override def preStart(): Unit = self ! Start

  override def receive: Receive = {
    case Start =>
      log.info("ChainWeightRepairActor starting: mode=bidirectional anchor={}", anchorBlock)
      Future {
        blocking {
          ChainWeightRepair.repairBidirectional(anchorBlock, reader, writer, slog)
        }
      }.onComplete {
        case Success(result) => self ! RepairDone(result)
        case Failure(ex)     => self ! RepairError(ex)
      }

    case RepairDone(result) =>
      appStateStorage
        .clearEth68BootstrapMinPivot()
        .and(appStateStorage.setEth68ChainWeightRepairDone())
        .commit()
      log.info(
        "ChainWeightRepairActor complete: walked={} corrected={} hadMismatches={}",
        result.walked, result.corrected, result.hadMismatches
      )
      context.parent ! RepairComplete(result)
      context.stop(self)

    case RepairError(ex) =>
      log.warning("ChainWeightRepairActor failed — will retry on next startup: {}", ex.getMessage)
      context.parent ! RepairFailed(ex)
      context.stop(self)
  }
}

object ChainWeightRepairActor {

  private case object Start
  private case class RepairDone(result: ChainWeightRepair.RepairResult)
  private case class RepairError(ex: Throwable)

  case class RepairComplete(result: ChainWeightRepair.RepairResult)
  case class RepairFailed(ex: Throwable)

  def props(
      anchorBlock: BigInt,
      reader: BlockchainReader,
      writer: BlockchainWriter,
      appStateStorage: AppStateStorage
  ): Props =
    Props(new ChainWeightRepairActor(anchorBlock, reader, writer, appStateStorage))
}
