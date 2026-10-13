package com.chipprbots.ethereum.blockchain.sync.snap.actors.bytecode

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.ActorContext

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SnapIntakeBudget
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StateIngest
import com.chipprbots.ethereum.network.NetworkPeerManagerActor

/** ByteCodeCoordinator's environment for its modules (spec 016 C-B1, characterization G9): the actor context and
  * logger, the constructor's collaborators, the `StateIngest` capability (FR-042: never `StateAccess`, never a raw
  * store) and the worker-pool cap. Constructor parameters and immutable values only, so it is a capability-like trait,
  * counted apart from the modules' `<Module>State` (research.md R14b). The coordinator Impl implements it.
  */
private[actors] trait ByteCodeEnv:
  def context: ActorContext[ByteCodeCoordinator.Command]
  def log: org.slf4j.Logger
  def networkPeerManager: ActorRef[NetworkPeerManagerActor.Command]
  def requestTracker: SNAPRequestTracker
  def cooldownConfig: ByteCodeCoordinator.ByteCodePeerCooldownConfig
  def snapSyncController: ActorRef[SNAPSyncController.Command]
  def intakeBudget: Option[SnapIntakeBudget]
  def stateIngest: StateIngest
  def maxWorkers: Int
