package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorLogging
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Props
import org.apache.pekko.util.ByteString

import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage

/** Thin actor wrapper around [[CombinedRecoveryScanner]]: runs ONE parallel, resumable single-pass scan of the state
  * trie (checking both bytecode and storage per account), then emits both gap sets to the parent `SyncController` via
  * [[CombinedRecoveryScanActor.CombinedScanComplete]] and stops. The controller drives the downloads from there.
  *
  * Replaces the two legacy single-threaded full-trie walks (`BytecodeRecoveryActor` + `StorageRecoveryActor` scan
  * phases). The scan is read-only and runs on a bounded pool, so it doesn't block the actor; the result is piped back
  * through `self`. A scan failure (or an empty trie) emits empty gap sets — recovery then proceeds straight to regular
  * sync, which fetches any residue on-demand.
  */
class CombinedRecoveryScanActor(
    stateRoot: ByteString,
    stateStorage: StateStorage,
    evmCodeStorage: EvmCodeStorage,
    appStateStorage: AppStateStorage,
    syncController: ActorRef,
    pivotBlockNumber: BigInt,
    snapSyncConfig: SNAPSyncConfig
) extends Actor
    with ActorLogging {

  import CombinedRecoveryScanActor._
  import context.dispatcher

  override def preStart(): Unit = {
    log.info(
      s"CombinedRecoveryScanActor starting: parallel single-pass scan " +
        s"(stateRoot=${stateRoot.take(4).toArray.map("%02x".format(_)).mkString}..., " +
        s"concurrency=${snapSyncConfig.recoveryScanConcurrency}, shardDepth=${snapSyncConfig.recoveryScanShardDepth})"
    )
    self ! StartScan
  }

  override def receive: Receive = {
    case StartScan =>
      Future {
        val scanner = new CombinedRecoveryScanner(
          scanRoot = stateRoot,
          storageForShard = () => stateStorage.getBackingStorage(pivotBlockNumber),
          evmCodeStorage = evmCodeStorage,
          appStateStorage = appStateStorage,
          concurrency = snapSyncConfig.recoveryScanConcurrency,
          shardDepth = snapSyncConfig.recoveryScanShardDepth
        )
        scanner.run()
      }.onComplete {
        case Success(result) => self ! ScanDone(result)
        case Failure(ex) =>
          log.error(ex, "Combined recovery scan failed — reporting no gaps; regular sync will fetch on-demand")
          self ! ScanDone(RecoveryScanResult(Vector.empty, Vector.empty))
      }

    case ScanDone(result) =>
      log.info(
        s"Combined recovery scan complete: ${result.missingBytecodes.size} missing bytecodes, " +
          s"${result.missingStorageTries.size} missing storage tries"
      )
      syncController ! CombinedScanComplete(result.missingBytecodes, result.missingStorageTries)
      context.stop(self)
  }
}

object CombinedRecoveryScanActor {
  private case object StartScan
  private case class ScanDone(result: RecoveryScanResult)

  /** Sent to SyncController when the combined scan finishes. The controller spawns the (download-only) recovery actors
    * for whichever phases it still needs.
    */
  final case class CombinedScanComplete(
      missingBytecodes: Seq[ByteString],
      missingStorageTries: Seq[(ByteString, ByteString)]
  )

  def props(
      stateRoot: ByteString,
      stateStorage: StateStorage,
      evmCodeStorage: EvmCodeStorage,
      appStateStorage: AppStateStorage,
      syncController: ActorRef,
      pivotBlockNumber: BigInt,
      snapSyncConfig: SNAPSyncConfig
  ): Props =
    Props(
      new CombinedRecoveryScanActor(
        stateRoot,
        stateStorage,
        evmCodeStorage,
        appStateStorage,
        syncController,
        pivotBlockNumber,
        snapSyncConfig
      )
    )
}
