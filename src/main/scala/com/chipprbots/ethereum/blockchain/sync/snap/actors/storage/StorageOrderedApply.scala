package com.chipprbots.ethereum.blockchain.sync.snap.actors.storage

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.ethereum.blockchain.sync.snap.SnapTrie
import com.chipprbots.ethereum.blockchain.sync.snap.StorageTask
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.ReadyStorageChunk
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.TrieNodeWrite

/** The coordinator state `StorageOrderedApply` reads and writes (spec 016 C-S2, research.md R14b). The core's behaviour
  * arms, the peer dispatch (C-S1) or the response processing and done markers that C-S3/C-S4 will move touch every one
  * of them too (or rule (d) keeps them in the core), so the Impl keeps them as fields. The four `var`s are the ones the
  * module writes.
  */
private[actors] trait StorageOrderedApplyState:
  def stateRoot: ByteString
  def tasks: mutable.Queue[StorageTask]
  def accountSubtaskCounters: mutable.Map[ByteString, (Int, Int)]
  var completedAccountCount: Long
  var slotsDownloaded: Long
  var bytesDownloaded: Long
  def pendingAccountTries: mutable.Map[ByteString, SnapTrie]
  def storageTrieCursor: mutable.Map[ByteString, ByteString]
  def pendingOrderedChunks: mutable.Map[ByteString, mutable.TreeMap[ByteString, ReadyStorageChunk]]
  def zeroSlotHash: ByteString
  def lastAppliedSlot: mutable.Map[ByteString, (ByteString, ByteString)]
  def trieNodeWrites: mutable.ArrayBuffer[TrieNodeWrite]
  var trieWriteNanos: Long

/** What `StorageOrderedApply` calls in the coordinator core: the flat-slot staging and the completion markers (C-S3's
  * until it moves), and the subtask split and its completion count (C-S4's until it moves).
  */
private[actors] trait StorageApplyCoreApi:
  def stageFlatSlotChunk(accountHash: ByteString, slots: Seq[(ByteString, ByteString)]): Unit
  def createStorageSubTasks(task: StorageTask, lastSlotReceived: ByteString): Seq[StorageTask]
  def recordSubtaskCompletion(accountHash: ByteString): Boolean
  def stageDoneMarker(accountHash: ByteString, storageRoot: ByteString): Unit
  def recordCompletedTask(task: StorageTask): Unit
