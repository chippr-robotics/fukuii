package com.chipprbots.ethereum.blockchain.sync.snap.actors.account

import java.io.BufferedOutputStream
import java.nio.file.Path

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.ethereum.blockchain.sync.snap.AccountTask
import com.chipprbots.ethereum.blockchain.sync.snap.SnapTrie
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.WorkerRef
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeStats
import com.chipprbots.ethereum.network.Peer

/** The coordinator state `AccountTrieAssembly` reads and writes (spec 016 C-A3, research.md R14b: 12, plus
  * `trieFlushGeneration`, `startTime` and the six task-file handles). The core's behaviour arms, the other modules or
  * the contract carry that C-A4 will move touch every one of them too, or rule (d) keeps them in the core (concrete
  * `val`s, or a `var` whose initializer is not a literal), so the Impl keeps them as fields. The `finalizing` arms read
  * the task files and their counts to answer the controller's file queries during finalisation.
  */
private[actors] trait AccountTrieAssemblyState:
  def stateRoot: ByteString
  def pendingTasks: mutable.PriorityQueue[AccountTask]
  def activeTasks: mutable.Map[BigInt, (AccountTask, WorkerRef, Peer)]
  def completedTasks: mutable.ArrayBuffer[AccountTask]
  def workers: mutable.ArrayBuffer[WorkerRef]
  var accountsDownloaded: Long
  def startTime: Long
  def consumedKeyspace: BigInt
  def storingTasks: mutable.Map[ByteString, AccountTask]
  def taskStackTries: mutable.Map[ByteString, SnapTrie]
  def trieFlushGeneration: Long
  def contractAccountsFile: Path
  def contractAccountsOut: BufferedOutputStream
  def contractAccountsCount: Long
  def contractStorageFile: Path
  def contractStorageOut: BufferedOutputStream
  def contractStorageCount: Long
  def uniqueCodeHashesFile: Path
  def uniqueCodeHashesOut: BufferedOutputStream
  def uniqueCodeHashesCount: Long

/** What `AccountTrieAssembly` calls in the coordinator core: the progress stats a `finalizing` progress query answers
  * with, and the teardown the `finalizing` behaviour's `PostStop` runs.
  */
private[actors] trait AccountRangeCoreApi:
  def calculateProgress(): AccountRangeStats
  def onStop(): Unit

/** What `AccountTrieAssembly` calls in the contract carry: reading the contract and unique-codeHash task files for the
  * controller's queries during finalisation. The coordinator core provides it until C-A4 moves it into
  * `ContractWorkCarry`.
  */
private[actors] trait ContractTaskFilesApi:
  def readContractFile(filePath: Path, out: BufferedOutputStream, count: Long): Seq[(ByteString, ByteString)]
  def readUniqueCodeHashes(): Seq[ByteString]
