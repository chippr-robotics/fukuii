package com.chipprbots.ethereum.blockchain.sync
import com.chipprbots.ethereum.domain.Block

object SyncProtocol {
  sealed trait SyncProtocolMsg
  case object Start extends SyncProtocolMsg
  case object GetStatus extends SyncProtocolMsg
  case class MinedBlock(block: Block) extends SyncProtocolMsg

  /** Clears persisted fast-sync markers so the next start can enter fast sync again.
    * This is intentionally a "soft" reset: it does not wipe the chain DB.
    */
  case object ResetFastSync extends SyncProtocolMsg
  final case class ResetFastSyncResponse(reset: Boolean) extends SyncProtocolMsg

  /** Requests a safe in-process restart of fast sync.
    * The controller will apply a circuit-breaker cool-off period to avoid thrashing.
    */
  case object RestartFastSync extends SyncProtocolMsg
  final case class RestartFastSyncResponse(started: Boolean, cooldownUntilMillis: Long) extends SyncProtocolMsg

  sealed trait Status {
    def syncing: Boolean = this match {
      case Status.Syncing(_, _, _) => true
      case Status.NotSyncing       => false
      case Status.SyncDone         => false
    }

    def notSyncing: Boolean = !syncing
  }
  object Status {
    case class Progress(current: BigInt, target: BigInt) {
      val isEmpty: Boolean = current == 0 && target == 0
      val nonEmpty: Boolean = !isEmpty
    }
    object Progress {
      val empty: Progress = Progress(0, 0)
    }
    case class Syncing(
        startingBlockNumber: BigInt,
        blocksProgress: Progress,
        stateNodesProgress: Option[Progress] // relevant only in fast sync, but is required by RPC spec
    ) extends Status

    case object NotSyncing extends Status
    case object SyncDone extends Status
  }
}
