package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHeader

object SyncProtocol:

  /** Marker trait for messages that SNAPSyncController sends up to SyncController. Not sealed because the companion
    * message types live in SNAPSyncController.scala (a separate file). SyncController receives them via a
    * `messageAdapter[SyncControllerReply]`, narrowing the `ActorRef[Any]` that SSC previously held.
    */
  trait SyncControllerReply

  /** All direct subtypes are defined in this file — sealed is now valid (W17 fix). Direct subtypes: Start, GetStatus,
    * MinedBlock, RegularSyncStuck, BlockFetcherStopped, NewCanonicalHead, FetcherStatusTick, PrintStatusTick,
    * ProgressProtocol (all in this file).
    */
  sealed trait RegularSyncCommand

  /** Internal timer ticks used only by RegularSync (in the `sync.regular` sub-package). These are `sealed` variants so
    * external callers cannot accidentally send them; they are not part of the public SyncProtocol API surface.
    */
  case object FetcherStatusTick extends RegularSyncCommand
  case object PrintStatusTick extends RegularSyncCommand

  /** Progress messages sent from BlockFetcher / BlockImporter back to RegularSync. */
  sealed trait ProgressProtocol extends RegularSyncCommand
  object ProgressProtocol:
    case object StartedFetching extends ProgressProtocol
    case class StartingFrom(blockNumber: BigInt) extends ProgressProtocol
    case class GotNewBlock(blockNumber: BigInt) extends ProgressProtocol
    case class ImportedBlock(blockNumber: BigInt, internally: Boolean) extends ProgressProtocol

  sealed trait SyncProtocolMsg
  case object Start extends SyncProtocolMsg with RegularSyncCommand
  final case class GetStatus(replyTo: org.apache.pekko.actor.typed.ActorRef[SyncProtocol.Status])
      extends SyncProtocolMsg
      with RegularSyncCommand
  case class MinedBlock(block: Block) extends SyncProtocolMsg with RegularSyncCommand

  /** Signals that regular sync has hit a wall — repeated state-node fetch exhaustion on the same block, with no peer
    * able to serve our parent stateRoot (typical when the node is many thousands of blocks behind canonical tip and the
    * snap-serve window of every connected peer has moved far past us). The controller responds by clearing the
    * SnapSyncDone flag and re-running SNAP sync from a recent pivot, which is the only viable recovery path.
    */
  final case class RegularSyncStuck(blockNumber: BigInt, missingHash: String)
      extends SyncProtocolMsg
      with RegularSyncCommand

  /** Delivered to RegularSync via watchWith when BlockFetcher terminates (RF-2 Option A). RegularSync re-spawns both
    * BlockFetcher and BlockImporter: BlockImporter holds a captured fetcher ref in its constructor, so it must be
    * replaced together with BlockFetcher to avoid a dead-letter sink.
    */
  case object BlockFetcherStopped extends RegularSyncCommand

  /** Signals that the CL canonical head has advanced (PoS / post-merge chains only).
    *
    * Sent by `SyncController.handleRegularSyncMsg` whenever a `ForkChoiceManager.BeaconHead` arrives while regular sync
    * is running. `RegularSync` forwards it to `BlockBroadcasterActor.AnnounceCanonicalHead` so that peers which
    * completed their ETH STATUS handshake before the FCU was processed learn about the advanced head immediately.
    *
    * This fixes the Hive "fukuii as sync server" bug: `engine_newPayload` stores blocks but does NOT advance
    * `BestBlockNumber`; only `forkchoiceUpdated → saveBestKnownBlocks` does. Without this announcement, an already-
    * connected downloader that saw genesis (0) during STATUS has no way to learn that fukuii is now at a real head.
    *
    * `knownHeader` is populated by `ForkChoiceManager` when the header is already in storage; `None` means the header
    * was not yet available at FCU time and the announcement is skipped (the next FCU will retry).
    *
    * Safety: on PoW chains (ETC/Mordor) `BeaconHead` is never published (`clPivotEnabled=false`), so this message is
    * never emitted — there is ZERO PoW/ETC regression risk.
    */
  final case class NewCanonicalHead(headHash: ByteString, knownHeader: Option[BlockHeader]) extends RegularSyncCommand

  /** Signals that SNAP finalization detected a state root mismatch (snapStateRoot != pivotHeader.stateRoot).
    * SyncController responds by clearing SnapSyncDone and restarting SNAP with a fresh pivot. Mirrors Besu BUG-008
    * class recovery: abort finalization rather than commit a broken state.
    */
  case object HealingImpossible extends SyncProtocolMsg with SyncControllerReply

  /** Sent by NetworkPeerManagerActor to SyncController when a TD-PROXY-GAP is detected at peer handshake.
    * SyncController uses the peer's STATUS-message TD and block number to interpolate and write the correct cumulative
    * TD for the current best block, replacing the genesis-proxy value stored by SNAP finalization when no ETH68 peers
    * were available. Only fired when peerMaxBlock > 0.
    */
  final case class CalibrateChainWeightFromPeer(peerTD: BigInt, peerMaxBlock: BigInt) extends SyncProtocolMsg

  sealed trait Status:
    def syncing: Boolean = this match
      case Status.Syncing(_, _, _) => true
      case Status.NotSyncing       => false
      case Status.SyncDone         => false

    def notSyncing: Boolean = !syncing
  object Status:
    case class Progress(current: BigInt, target: BigInt):
      val isEmpty: Boolean = current == 0 && target == 0
      val nonEmpty: Boolean = !isEmpty
    object Progress:
      val empty: Progress = Progress(0, 0)
    case class Syncing(
        startingBlockNumber: BigInt,
        blocksProgress: Progress,
        stateNodesProgress: Option[Progress] // state download progress (SNAP sync); eth_syncing reports it
    ) extends Status

    case object NotSyncing extends Status
    case object SyncDone extends Status
