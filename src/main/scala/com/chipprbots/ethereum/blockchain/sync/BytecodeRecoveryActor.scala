package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props}
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.Future
import scala.util.{Success, Failure}

import com.chipprbots.ethereum.db.storage.{AppStateStorage, EvmCodeStorage, StateStorage}
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt._
import com.chipprbots.ethereum.mpt.MptVisitors._

/** Bytecode recovery actor for Bug 20 hardening.
  *
  * On startup after SNAP sync, walks the state trie to find contract accounts
  * whose bytecodes are missing from evmCodeStorage (due to the Bug 20 phase
  * handoff timeout). Collects missing codeHashes and downloads them via SNAP
  * protocol using ByteCodeCoordinator.
  *
  * Lifecycle:
  *   1. Walk state trie, collect missing codeHashes (deduplicated)
  *   2. If none missing → mark recovery done, report to SyncController
  *   3. If missing → download via ByteCodeCoordinator, then mark done
  */
class BytecodeRecoveryActor(
    stateRoot: ByteString,
    stateStorage: StateStorage,
    evmCodeStorage: EvmCodeStorage,
    appStateStorage: AppStateStorage,
    networkPeerManager: ActorRef,
    syncController: ActorRef,
    pivotBlockNumber: BigInt
) extends Actor
    with ActorLogging {

  import BytecodeRecoveryActor._
  import context.dispatcher

  override def preStart(): Unit = {
    log.info(
      s"BytecodeRecoveryActor starting: scanning state trie for missing bytecodes " +
        s"(stateRoot=${stateRoot.take(4).toArray.map("%02x".format(_)).mkString}...)"
    )
    self ! StartScan
  }

  override def receive: Receive = {
    case StartScan =>
      Future {
        scanForMissingBytecodes()
      }.onComplete {
        case Success(result) => self ! ScanResult(result)
        case Failure(ex) =>
          log.error(ex, "Bytecode recovery scan failed")
          self ! ScanResult(Seq.empty)
      }

    case ScanResult(missing) =>
      if (missing.isEmpty) {
        log.info("Bytecode recovery: all contract bytecodes present. Marking recovery complete.")
        appStateStorage.bytecodeRecoveryDone().commit()
        syncController ! RecoveryComplete
        context.stop(self)
      } else {
        log.warning(s"Bytecode recovery: found ${missing.size} missing bytecodes. Starting download...")
        implicit val scheduler: org.apache.pekko.actor.Scheduler = context.system.scheduler
        val requestTracker = new snap.SNAPRequestTracker()
        val coordinator = context.actorOf(
          snap.actors.ByteCodeCoordinator
            .props(
              evmCodeStorage = evmCodeStorage,
              networkPeerManager = networkPeerManager,
              requestTracker = requestTracker,
              batchSize = snap.ByteCodeTask.DEFAULT_BATCH_SIZE,
              snapSyncController = self
            )
            .withDispatcher("sync-dispatcher"),
          "bytecode-recovery-coordinator"
        )
        coordinator ! snap.actors.Messages.StartByteCodeSync(missing)
        context.become(downloading(coordinator, missing.size))
      }
  }

  private def downloading(coordinator: ActorRef, expectedCount: Int): Receive = {
    case snap.actors.Messages.ByteCodePeerAvailable(peer) =>
      coordinator ! snap.actors.Messages.ByteCodePeerAvailable(peer)

    case snap.SNAPSyncController.ByteCodeSyncComplete =>
      log.info(s"Bytecode recovery complete: downloaded bytecodes for $expectedCount missing codeHashes")
      appStateStorage.bytecodeRecoveryDone().commit()
      syncController ! RecoveryComplete
      context.stop(self)

    case snap.SNAPSyncController.ProgressBytecodesDownloaded(_) =>
      // Ignore progress updates

    case _ => // Ignore other messages
  }

  /** Walk the state trie and collect codeHashes of contracts missing from evmCodeStorage. */
  private def scanForMissingBytecodes(): Seq[ByteString] = {
    val mptStorage = stateStorage.getBackingStorage(pivotBlockNumber)
    val rootNode = mptStorage.get(stateRoot.toArray)

    val missing = mutable.ArrayBuffer.empty[ByteString]
    val seen = mutable.HashSet.empty[ByteString]
    var accountCount = 0L
    var contractCount = 0L

    val onLeaf: LeafNode => Unit = { leafNode =>
      accountCount += 1
      if (accountCount % 1_000_000 == 0) {
        log.info(
          s"Bytecode recovery scan: $accountCount accounts, $contractCount contracts, ${missing.size} missing"
        )
      }

      Account(leafNode.value) match {
        case Success(account) =>
          if (account.codeHash != Account.EmptyCodeHash && !seen.contains(account.codeHash)) {
            seen += account.codeHash
            contractCount += 1
            if (evmCodeStorage.get(account.codeHash).isEmpty) {
              missing += account.codeHash
            }
          }
        case Failure(_) => // Skip malformed account RLP
      }
    }

    try {
      val visitor = new LeafWalkVisitor(mptStorage, onLeaf)
      MptTraversals.dispatch(rootNode, visitor)
    } catch {
      case e: MerklePatriciaTrie.MPTException =>
        log.error(
          e,
          s"Trie walk failed at account $accountCount — partial results: ${missing.size} missing bytecodes"
        )
    }

    log.info(
      s"Bytecode recovery scan complete: $accountCount accounts, $contractCount contracts, ${missing.size} missing bytecodes"
    )
    missing.toSeq
  }
}

object BytecodeRecoveryActor {
  private case object StartScan
  private case class ScanResult(missingCodeHashes: Seq[ByteString])

  /** Sent to SyncController when recovery is complete (or skipped) */
  case object RecoveryComplete

  def props(
      stateRoot: ByteString,
      stateStorage: StateStorage,
      evmCodeStorage: EvmCodeStorage,
      appStateStorage: AppStateStorage,
      networkPeerManager: ActorRef,
      syncController: ActorRef,
      pivotBlockNumber: BigInt
  ): Props =
    Props(
      new BytecodeRecoveryActor(
        stateRoot, stateStorage, evmCodeStorage,
        appStateStorage, networkPeerManager, syncController, pivotBlockNumber
      )
    )
}
