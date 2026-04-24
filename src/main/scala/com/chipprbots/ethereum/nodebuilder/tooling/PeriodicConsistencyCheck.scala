package com.chipprbots.ethereum.nodebuilder.tooling

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler

import scala.concurrent.duration.DurationInt

import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BlockHeadersStorage
import com.chipprbots.ethereum.db.storage.BlockNumberMappingStorage

import com.chipprbots.ethereum.nodebuilder.tooling.PeriodicConsistencyCheck.ConsistencyCheck
import com.chipprbots.ethereum.utils.Logger

object PeriodicConsistencyCheck {
  def start(
      appStateStorage: AppStateStorage,
      blockNumberMappingStorage: BlockNumberMappingStorage,
      blockHeadersStorage: BlockHeadersStorage,
      shutdown: () => Unit,
      engineApiEnabled: Boolean = false
  ): Behavior[ConsistencyCheck] =
    Behaviors.withTimers { timers =>
      tick(timers)
      PeriodicConsistencyCheck(
        timers,
        appStateStorage,
        blockNumberMappingStorage,
        blockHeadersStorage,
        shutdown,
        engineApiEnabled
      )
        .check()
    }

  sealed trait ConsistencyCheck extends Product with Serializable
  case object Tick extends ConsistencyCheck

  def tick(timers: TimerScheduler[ConsistencyCheck]): Unit =
    timers.startSingleTimer(Tick, 10.minutes)
}

case class PeriodicConsistencyCheck(
    timers: TimerScheduler[ConsistencyCheck],
    appStateStorage: AppStateStorage,
    blockNumberMappingStorage: BlockNumberMappingStorage,
    blockHeadersStorage: BlockHeadersStorage,
    shutdown: () => Unit,
    engineApiEnabled: Boolean = false
) extends Logger {
  import PeriodicConsistencyCheck._

  def check(): Behavior[ConsistencyCheck] = Behaviors.receiveMessage { case Tick =>
    // Match the skip conditions in StdNode.runDBConsistencyCheck: the post-SNAP best block
    // points to a pivot header without the full 0..pivot chain, the mid-SNAP state is even
    // more partial (Bug 28), and Engine API mode uses optimistic imports that don't fill in
    // the chain from genesis. All three would misfire the shutdown.
    if (appStateStorage.isSnapSyncDone()) {
      log.debug("Skipping periodic consistency check: SNAP sync stores only pivot block header")
    } else if (appStateStorage.isSnapSyncInProgress()) {
      log.debug("Skipping periodic consistency check: SNAP sync in progress")
    } else if (engineApiEnabled) {
      log.debug("Skipping periodic consistency check: Engine API mode uses optimistic block import")
    } else {
      log.debug("Running a storage consistency check")
      StorageConsistencyChecker.checkStorageConsistency(
        appStateStorage.getBestBlockNumber(),
        blockNumberMappingStorage,
        blockHeadersStorage,
        shutdown
      )(log)
    }
    tick(timers)
    Behaviors.same
  }
}
