package com.chipprbots.ethereum.blockchain.sync.snap.actors.storage

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.ActorContext

import scala.concurrent.duration.FiniteDuration

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SnapIntakeBudget
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator
import com.chipprbots.ethereum.network.NetworkPeerManagerActor

/** StorageRangeCoordinator's environment for its modules (spec 016 C-S1, characterization G9): the actor context, its
  * logger and `self`, the constructor's collaborators, the request and batch limits, the two response-budget
  * configuration values, the dispatch gates read at request time (`deferredMerkleization`,
  * `maxConcurrentStorageAccounts`, the spec 014 intake budget) and the #1533 processing settings. Constructor
  * parameters and immutable values only, so it is a capability-like trait, counted apart from the modules'
  * `<Module>State` (research.md R14b). The coordinator Impl implements it.
  *
  * It holds no store: C-S1 touches no storage (coordinator-characterization.md §5), so the coordinator's storage
  * parameters are not here. They move onto `StateIngest` in the first slice whose code uses them (C-S2, C-S3).
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
