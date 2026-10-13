package com.chipprbots.ethereum.blockchain.sync.snap.actors.storage

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.ActorContext

import scala.concurrent.duration.FiniteDuration

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SnapIntakeBudget
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StateIngest
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.network.NetworkPeerManagerActor

/** StorageRangeCoordinator's environment for its modules (spec 016 C-S1, characterization G9): the actor context, its
  * logger and `self`, the constructor's collaborators, the request and batch limits, the two response-budget
  * configuration values, the dispatch gates read at request time (`deferredMerkleization`,
  * `maxConcurrentStorageAccounts`, the spec 014 intake budget) and the #1533 processing settings. Constructor
  * parameters and immutable values only, so it is a capability-like trait, counted apart from the modules'
  * `<Module>State` (research.md R14b). The coordinator Impl implements it.
  *
  * Stores (spec 016 C-S2): the two session values the ordered apply reads, which stay explicit parameters under the
  * named FR-042 exception (tasks.md, Coordinators, "Amendment 2026-10-13"): `mptStorage`, the core's pivot-bound
  * `MptStorage` (the Hash-scheme trie flushes and prebuilt writes), and `snapProgressStorage`, the per-spawn `Option`
  * that decides whether the storage cursor is persisted (`Some` for the controller's spawn, `None` for recovery's).
  * Plus `storageScheme`, which picks the trie. The Path node store is not here: the modules reach it as
  * `stateIngest.pathNodes` (`StateIngest`, adopted in C-S2).
  */
private[actors] trait StorageRangeEnv:
  def context: ActorContext[StorageRangeCoordinator.Command]
  def log: org.slf4j.Logger
  def self: ActorRef[StorageRangeCoordinator.Command]
  def networkPeerManager: ActorRef[NetworkPeerManagerActor.Command]
  def requestTracker: SNAPRequestTracker
  def maxAccountsPerBatch: Int
  def maxInFlightRequests: Int
  def requestTimeout: FiniteDuration
  def snapSyncController: ActorRef[SNAPSyncController.Command]
  def configInitialResponseBytes: Int
  def configMinResponseBytes: Int
  def deferredMerkleization: Boolean
  def maxConcurrentStorageAccounts: Int
  def intakeBudget: Option[SnapIntakeBudget]
  def processing: StorageRangeCoordinator.ProcessingSettings
  def mptStorage: MptStorage
  def snapProgressStorage: Option[SnapSyncProgressStorage]
  def storageScheme: StorageScheme
  def stateIngest: StateIngest
