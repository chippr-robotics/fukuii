package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import java.util.concurrent.atomic.AtomicLong

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.mpt.MptNode

/** The coordinator state `HealVerification` reads and writes (spec 016 C-H3, research.md R14b). The core's behaviour
  * arms (`healVerificationArms`, the completion gate) and the code C-H4 and C-H5 will move touch them too, or rule (d)
  * keeps them in the core, so the Impl keeps them as fields. The verification launchers write the in-flight run's
  * observability (`scopedVerificationActive`, `scopedVerificationSeedCount`, `scopedVerificationStartMs`,
  * `prunedVerificationActive`, `prunedVerificationStartMs`), set `verificationBFSRunning`, reset `prunedSubtreeCount`,
  * and `reverifyCurrentRoot` clears `verificationPassComplete`; they read the walk root `stateRoot` and the completion
  * gate's `flushing` and `trieWalkInProgress`. `prunedEnabled` is an immutable val rule (d) keeps in the core.
  */
private[actors] trait HealVerificationState:
  var stateRoot: ByteString
  var verificationBFSRunning: Boolean
  var verificationPassComplete: Boolean
  var trieWalkInProgress: Boolean
  var flushing: Boolean
  var scopedVerificationActive: Boolean
  var scopedVerificationSeedCount: Int
  var scopedVerificationStartMs: Long
  var prunedVerificationActive: Boolean
  var prunedVerificationStartMs: Long
  def prunedSubtreeCount: AtomicLong
  def prunedEnabled: Boolean

/** What the frontier-rebuild walk calls for a Path-scheme read: `readPathVerifiedNode`, the bytes at the entry's own
  * path, counted present only when they hash to the entry's hash. It stays in the core until C-H3 (`HealVerification`)
  * moves it.
  */
private[actors] trait PathVerifiedReadApi:
  def readPathVerifiedNode(hash: ByteString, pathset: Seq[ByteString]): Option[MptNode]

/** What the core's presence check (`isNodeInStorage`, C-H5's) calls for a Path-scheme read: `readPathVerifiedRlp`, the
  * raw bytes at the entry's own path, returned only when they hash to the entry's hash. `HealVerification` provides it
  * (spec 016 C-H3). A separate trait, so the C-H2 interface stays as recorded.
  */
private[actors] trait PathVerifiedRlpApi:
  def readPathVerifiedRlp(hash: ByteString, pathset: Seq[ByteString]): Option[Array[Byte]]

/** What `reverifyCurrentRoot` calls in the core: `isComplete`, nothing pending and nothing in flight (C-H5's member,
  * still in the core).
  */
private[actors] trait HealCompletionApi:
  def isComplete: Boolean
