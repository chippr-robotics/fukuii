package com.chipprbots.ethereum.blockchain.sync.snap.controller

import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler

import scala.concurrent.ExecutionContext

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.StateValidator
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** The SNAP controller's environment (spec 016 T041a, plan.md D1): the actor context and timers, the configuration, the
  * off-thread logger, the storages, and the injected collaborators. It is a capability trait, counted apart from state:
  * nothing here is a field a phase module owns or resets.
  *
  * The controller core mixes this trait in. Its constructor parameters implement the members directly (as `val`s), and
  * so do its existing strict vals `asyncLog` and `snapValidationEc`, whose initializers stay in the core (the
  * dispatcher lookup and the logger lookup run at construction). Metrics are not members: `SNAPSyncMetrics` is a global
  * object.
  *
  * Member cap (FR-016 (c)): 11. A PR that adds a member states the new count and a justification line, and updates the
  * cap in `.claude/agent-protocols/snap-sync.md`.
  */
private[snap] trait SnapControllerEnv:

  def ctx: ActorContext[SNAPSyncController.Command]
  def timers: TimerScheduler[SNAPSyncController.Command]
  def snapSyncConfig: SNAPSyncConfig
  def syncConfig: SyncConfig

  /** Plain SLF4J logger, safe off the actor thread (Future callbacks). On-thread logging uses `ctx.log`. */
  def asyncLog: org.slf4j.Logger

  def appStateStorage: AppStateStorage
  def stateStorage: StateStorage
  def evmCodeStorage: EvmCodeStorage
  def flatSlotStorage: FlatSlotStorage

  /** Builds the `StateValidator` for a storage (test seam; production: `new StateValidator(_)`). */
  def validatorFactory: MptStorage => StateValidator

  /** The dedicated dispatcher for the long trie walks (`snap-validation-dispatcher`, see `pekko.conf`). */
  def snapValidationEc: ExecutionContext
