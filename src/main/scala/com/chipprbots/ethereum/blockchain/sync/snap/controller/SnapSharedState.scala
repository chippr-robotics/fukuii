package com.chipprbots.ethereum.blockchain.sync.snap.controller

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.SyncPhase
import com.chipprbots.ethereum.blockchain.sync.snap.SyncProgressMonitor
import com.chipprbots.ethereum.domain.TrieRoot

/** The SNAP controller's hubs (spec 016 T041a, plan.md D1): the state that almost every phase module reads, and several
  * write. It is the one explicit interface for them.
  *
  * The controller core mixes this trait in and implements every member with its existing field: the three written hubs
  * are plain `var`s (a concrete `var` implements an abstract one, so `x = y` in a module body compiles unchanged), and
  * `progressMonitor` and `requestTracker` are the core's strict vals (their initializers run at construction, so they
  * stay in the core). Phase modules name this trait in their self-type to reach the hubs.
  *
  * Member cap (FR-016 (c)): 5. A PR that adds a member states the new count and a justification line, and updates the
  * cap in `.claude/agent-protocols/snap-sync.md`.
  */
private[snap] trait SnapSharedState:

  var pivotBlock: Option[BigInt]
  var stateRoot: Option[TrieRoot]
  var currentPhase: SyncPhase
  def progressMonitor: SyncProgressMonitor
  def requestTracker: SNAPRequestTracker
