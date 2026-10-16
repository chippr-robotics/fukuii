package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import org.apache.pekko.actor.typed.ActorRef

import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StateIngest
import com.chipprbots.ethereum.db.storage.MptStorage

/** TrieNodeHealingCoordinator's environment for its modules (spec 016 C-H1, characterization G9): its logger and
  * `self`, and the constructor's settings the frontier persistence reads (the Layer-2 mirror gate, the spec 004
  * unservable threshold that the gauges report, the frontier backpressure marks). Constructor parameters and immutable
  * values only, so it is a capability-like trait, counted apart from the modules' `<Module>State` (research.md R14b).
  * The coordinator Impl implements it.
  *
  * Stores: `mptStorage`, the core's pivot-bound `MptStorage` that the healed-node flushes write to, a session value
  * that stays an explicit parameter under the named FR-042 exception (tasks.md, Coordinators, "Amendment 2026-10-13").
  * The healing frontier store is not here: the modules reach it as `stateIngest.healingFrontier` (`StateIngest`,
  * adopted in C-H1).
  */
private[actors] trait TrieNodeHealingEnv:
  def log: org.slf4j.Logger
  def self: ActorRef[TrieNodeHealingCoordinator.Command]
  def mptStorage: MptStorage
  def frontierPersistenceEnabled: Boolean
  def decoupledHealServeRoot: Boolean
  def decoupledHealMaxAttemptsNoRefresh: Int
  def frontierHighWater: Int
  def frontierLowWater: Int
  def frontierBackpressureMaxWaitMs: Long
  def stateIngest: StateIngest
