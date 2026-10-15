package com.chipprbots.ethereum.blockchain.sync.snap.actors.storage

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.ExecutionContext

import com.chipprbots.ethereum.db.storage.SnapStorageDoneStorage

/** The coordinator state `StorageDoneMarkers` reads and writes (spec 016 C-S3, research.md R14b). The core's behaviour
  * arms (the flush-complete, flush-failed and periodic-flush arms, `onPostStop`, `isComplete`, progress) or the peer
  * dispatch touch every one of them too, or rule (d) keeps them in the core (`pendingFlatBatchAccounts`,
  * `pendingDoneMarkers`, `flatBatchesDoneAhead`, and the immutable `storageDoneStorage` and `flatBatchEc`), so the Impl
  * keeps them as fields. The four `var`s are the ones the module writes; `doneMarkersDisabled` is set only by the
  * core's flush-failed arm.
  */
private[actors] trait StorageDoneMarkersState:
  def stateRoot: ByteString
  var completedTaskCount: Long
  def pendingFlatBatchAccounts: mutable.ArrayBuffer[(ByteString, Seq[(ByteString, ByteString)])]
  var pendingFlatBatchEntries: Int
  var inFlightFlatBatches: Int
  def storageDoneStorage: Option[SnapStorageDoneStorage]
  def pendingDoneMarkers: mutable.ArrayBuffer[(ByteString, ByteString, Long)]
  def doneMarkersDisabled: Boolean
  var flatBatchSeq: Long
  def flatBatchesDoneAhead: mutable.Set[Long]
  def flatBatchEc: ExecutionContext
