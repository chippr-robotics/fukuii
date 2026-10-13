package com.chipprbots.ethereum.blockchain.sync.snap.actors.account

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SnapIntakeBudget
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.network.NetworkPeerManagerActor

/** AccountRangeCoordinator's environment for its modules (spec 016 C-A1, characterization G9): the actor context, its
  * timers and logger, the constructor's collaborators, the configured worker concurrency and the two response-budget
  * configuration values. Constructor parameters and immutable values only, so it is a capability-like trait, counted
  * apart from the modules' `<Module>State` (research.md R14b). The coordinator Impl implements it.
  *
  * It holds no store: C-A1 touches no storage (coordinator-characterization.md §5), so the coordinator's storage
  * parameters are not here. They move onto `StateIngest` in the first slice whose code uses them (C-A3, C-A4).
  *
  * C-A3 (spec 016 T064) adds what the trie assembly reads: the finalisation Future's logger (`futureLog`, because
  * `ctx.log` is thread-confined), the resume cursors and the storage scheme the per-range StackTrie is built from, and
  * `mptStorage`, the pivot-bound trie store the Hash-scheme StackTrie flushes to. `mptStorage` stays an explicit
  * parameter, not a `StateIngest` member: it is a session value (the core's per-pivot instance), the named FR-042
  * exception recorded at T064.
  */
private[actors] trait AccountRangeEnv:
  def ctx: ActorContext[AccountRangeCoordinator.Command]
  def timers: TimerScheduler[AccountRangeCoordinator.Command]
  def log: org.slf4j.Logger
  def networkPeerManager: ActorRef[NetworkPeerManagerActor.Command]
  def requestTracker: SNAPRequestTracker
  def concurrency: Int
  def snapSyncController: ActorRef[SNAPSyncController.Command]
  def initialResponseBytesConfig: Int
  def minResponseBytesConfig: Int
  def intakeBudget: Option[SnapIntakeBudget]
  def futureLog: org.slf4j.Logger
  def resumeProgress: Map[ByteString, ByteString]
  def storageScheme: StorageScheme
  def mptStorage: MptStorage
