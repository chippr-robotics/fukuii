package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import org.apache.pekko.actor.typed.ActorRef

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StateIngest
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.network.NetworkPeerManagerActor

/** TrieNodeHealingCoordinator's environment for its modules (spec 016 C-H1, characterization G9): its loggers and
  * `self`, and the constructor's settings the modules read (the spec 004 unservable threshold that the gauges report,
  * the frontier backpressure marks; the storage scheme the frontier walk reads and de-dups by, C-H2; the peer manager
  * the `GetTrieNodes` requests go to, the request tracker, the controller, the configured batch size and the spec 009
  * moving-root flag the dispatch reads, C-H4; the healed-code check flag the response processing reads, C-H5).
  * Constructor parameters and immutable values only, so it is a capability-like trait, counted apart from the modules'
  * `<Module>State` (research.md R14b). The coordinator Impl implements it.
  *
  * `log` is the actor's context logger, for the actor thread only; `asyncLog` is the plain SLF4J logger the off-thread
  * bodies use (the frontier walk on the healing writer and reader dispatchers, C-H2).
  *
  * Stores: `mptStorage`, the core's pivot-bound `MptStorage` that the healed-node flushes write to, a session value
  * that stays an explicit parameter under the named FR-042 exception (tasks.md, Coordinators, "Amendment 2026-10-13").
  */
private[actors] trait TrieNodeHealingEnv:
  def log: org.slf4j.Logger
  def asyncLog: org.slf4j.Logger
  def self: ActorRef[TrieNodeHealingCoordinator.Command]
  def mptStorage: MptStorage
  def storageScheme: StorageScheme
  def decoupledHealServeRoot: Boolean
  def decoupledHealMaxAttemptsNoRefresh: Int
  def frontierHighWater: Int
  def frontierLowWater: Int
  def frontierBackpressureMaxWaitMs: Long
  def stateIngest: StateIngest
  def networkPeerManager: ActorRef[NetworkPeerManagerActor.Command]
  def requestTracker: SNAPRequestTracker
  def snapSyncController: ActorRef[SNAPSyncController.Command]
  def batchSize: Int
  def movingRootDeltaHeal: Boolean
  def healedCodeCheck: Boolean
