package com.chipprbots.ethereum.blockchain.sync.snap.controller

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.blockchain.sync.snap.SnapIntakeBudget
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.network.p2p.messages.SNAP

/** The SNAP controller's child actors (spec 016 P1, FR-010): the four state-sync coordinators, the ChainDownloader
  * handle, and the #1501 intake budget that every coordinator spawn carries.
  *
  * The controller core mixes this trait in and implements its two abstract members: `intakeBudget` (the core's val; its
  * initializer is impure, so it stays a strict val in the core) and `stopChild` (`ctx.stop`). Phase modules name this
  * trait in their self-type to reach the children.
  *
  * Invariants kept from the code this replaces:
  *   - `stopAll()` stops exactly the four coordinators, in the order the stop sites always used (account, bytecode,
  *     storage, healing). It never stops the ChainDownloader (it survives restart and dormancy) and never the heap
  *     watchdog. There is no `ctx.watch`, and supervision is unchanged.
  *   - Each fan-out sends to the coordinators that exist, in the order the replaced call sites used.
  */
private[snap] trait CoordinatorHandles:

  var accountRangeCoordinator: Option[TypedActorRef[AccountRangeCoordinator.Command]] = None
  var bytecodeCoordinator: Option[TypedActorRef[ByteCodeCoordinator.Command]] = None
  var storageRangeCoordinator: Option[TypedActorRef[StorageRangeCoordinator.Command]] = None
  var trieNodeHealingCoordinator: Option[TypedActorRef[TrieNodeHealingCoordinator.Command]] = None

  /** The ChainDownloader. Kept apart from the four coordinators: `stopAll()` does not touch it. */
  lazy val chainDownloader: ChainDownloaderHandle = new ChainDownloaderHandle(stopChild)

  /** The shared #1501 admission gate, passed to every coordinator spawn. Implemented by the controller core. */
  def intakeBudget: SnapIntakeBudget

  /** Stop one child actor (`ctx.stop`). Implemented by the controller core. */
  protected def stopChild(child: TypedActorRef[Nothing]): Unit

  /** Stop the four state-sync coordinators and clear their refs. Callers reset their own state around it. */
  def stopAll(): Unit =
    accountRangeCoordinator.foreach(stopChild)
    accountRangeCoordinator = None
    bytecodeCoordinator.foreach(stopChild)
    bytecodeCoordinator = None
    storageRangeCoordinator.foreach(stopChild)
    storageRangeCoordinator = None
    trieNodeHealingCoordinator.foreach(stopChild)
    trieNodeHealingCoordinator = None

  /** Tell every existing coordinator that a peer has gone. */
  def broadcastPeerUnavailable(peerId: String): Unit =
    accountRangeCoordinator.foreach(_ ! AccountRangeCoordinator.PeerUnavailable(peerId))
    storageRangeCoordinator.foreach(_ ! StorageRangeCoordinator.StoragePeerUnavailable(peerId))
    bytecodeCoordinator.foreach(_ ! ByteCodeCoordinator.ByteCodePeerUnavailable(peerId))
    trieNodeHealingCoordinator.foreach(_ ! TrieNodeHealingCoordinator.HealingPeerUnavailable(peerId))

  /** A pivot refresh landed on `newStateRoot`: notify every existing coordinator. */
  def pivotRefreshed(newStateRoot: ByteString): Unit =
    accountRangeCoordinator.foreach(_ ! AccountRangeCoordinator.PivotRefreshed(newStateRoot))
    storageRangeCoordinator.foreach(_ ! StorageRangeCoordinator.StoragePivotRefreshed(newStateRoot))
    bytecodeCoordinator.foreach(_ ! ByteCodeCoordinator.ByteCodePivotRefreshed)
    trieNodeHealingCoordinator.foreach(_ ! TrieNodeHealingCoordinator.HealingPivotRefreshed(newStateRoot))

  /** Re-arm only the account and storage coordinators against `root` (the same-root and debounced refresh paths). */
  def reArmRangeCoordinators(root: ByteString): Unit =
    accountRangeCoordinator.foreach(_ ! AccountRangeCoordinator.PivotRefreshed(root))
    storageRangeCoordinator.foreach(_ ! StorageRangeCoordinator.StoragePivotRefreshed(root))

  /** Route a SNAP response to the one coordinator that owns its request type. */
  def forwardResponse(response: SNAP.AccountRange | SNAP.ByteCodes | SNAP.StorageRanges | SNAP.TrieNodes): Unit =
    response match
      case r: SNAP.AccountRange =>
        accountRangeCoordinator.foreach(_ ! AccountRangeCoordinator.AccountRangeResponseMsg(r))
      case r: SNAP.ByteCodes =>
        bytecodeCoordinator.foreach(_ ! ByteCodeCoordinator.ByteCodesResponseMsg(r))
      case r: SNAP.StorageRanges =>
        storageRangeCoordinator.foreach(_ ! StorageRangeCoordinator.StorageRangesResponseMsg(r))
      case r: SNAP.TrieNodes =>
        trieNodeHealingCoordinator.foreach(_ ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(r))
