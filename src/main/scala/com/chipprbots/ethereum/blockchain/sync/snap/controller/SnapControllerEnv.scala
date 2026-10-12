package com.chipprbots.ethereum.blockchain.sync.snap.controller

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler

import scala.concurrent.ExecutionContext

import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** The SNAP controller's environment (spec 016 T041a, plan.md D1): the actor context and timers, the configuration, the
  * off-thread logger, the storage capability, and the injected collaborators. It is a capability trait, counted apart
  * from state: nothing here is a field a phase module owns or resets.
  *
  * The controller core mixes this trait in. Its constructor parameters implement the members directly (as `val`s), and
  * so do its existing strict vals `asyncLog`, `snapValidationEc` and `stateIngest`, whose initializers stay in the core
  * (the dispatcher lookup and the logger lookup run at construction). Metrics are not members: `SNAPSyncMetrics` is a
  * global object.
  *
  * Member cap (FR-016 (c)): 11 (11 at T041a; M3 added `networkPeerManager` and `blockchainReader`, two constructor
  * parameters the peer pool's bodies reach; M4 added `blockchainWriter`, `peerEventBus`, `syncController` and
  * `childFactories`, constructor parameters the finalization bodies reach, and `pathNodeStorageOpt`, the first derived
  * store a module needs: 18. T055 replaced the eight storage handles `appStateStorage`, `stateStorage`,
  * `evmCodeStorage`, `flatSlotStorage`, `pathNodeStorageOpt`, `blockchainReader`, `blockchainWriter` and
  * `validatorFactory` with `stateIngest`: 18 - 8 + 1 = 11, FR-041). The env holds no storage handle; the env freeze
  * ratchet (`scripts/snap-split/storage-baseline.json`) keeps the count from rising. A PR that adds a member states the
  * new count and a justification line, and updates the cap in `.claude/agent-protocols/snap-sync.md`.
  */
private[snap] trait SnapControllerEnv:

  val ctx: ActorContext[SNAPSyncController.Command]
  def timers: TimerScheduler[SNAPSyncController.Command]
  def snapSyncConfig: SNAPSyncConfig
  def syncConfig: SyncConfig

  /** Plain SLF4J logger, safe off the actor thread (Future callbacks). On-thread logging uses `ctx.log`. */
  def asyncLog: org.slf4j.Logger

  /** The dedicated dispatcher for the long trie walks (`snap-validation-dispatcher`, see `pekko.conf`). */
  def snapValidationEc: ExecutionContext

  /** The peer manager: the handshaked-peer poll, snap-server dialling, and the child spawns. */
  def networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command]

  /** The peer event bus, passed to the ChainDownloader. */
  def peerEventBus: TypedActorRef[PeerEventBusActor.Command]

  /** The parent `SyncController`: SNAP's replies (finalized, done, healing impossible, bootstrap requests). */
  def syncController: TypedActorRef[SyncProtocol.SyncControllerReply]

  /** How each child is built (spec 016 T012 test seam; production: the children's own `apply`s). */
  def childFactories: ChildFactories

  /** SNAP's storage capability (spec 016 T055): every store a module reads or writes, built once by the core. */
  def stateIngest: StateIngest
