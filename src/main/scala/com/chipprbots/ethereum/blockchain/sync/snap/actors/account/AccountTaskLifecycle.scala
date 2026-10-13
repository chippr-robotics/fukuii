package com.chipprbots.ethereum.blockchain.sync.snap.actors.account

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.ethereum.blockchain.sync.snap.AccountTask
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.WorkerRef
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId

/** The coordinator state `AccountTaskLifecycle` reads and writes (spec 016 C-A2, research.md R14b: 18). The core's
  * behaviour arms, the peer dispatch or the trie assembly that C-A3 will move touch every one of them too, so the Impl
  * keeps them as fields.
  */
private[actors] trait AccountTaskLifecycleState:
  def stateRoot: ByteString
  def maxInFlightPerPeer: Int
  def statelessPeers: mutable.Set[PeerId]
  def snaplessPeers: mutable.Set[PeerId]
  def emptyResponseStrikes: mutable.Map[PeerId, Int]
  var pivotRefreshRequested: Boolean
  def pendingTasks: mutable.PriorityQueue[AccountTask]
  def activeTasks: mutable.Map[BigInt, (AccountTask, WorkerRef, Peer)]
  def completedTasks: mutable.ArrayBuffer[AccountTask]
  var lastDispatchOrResponseMs: Long
  def accountsDownloaded: Long
  var bytesDownloaded: Long
  var consumedKeyspace: BigInt
  def storingTasks: mutable.Map[ByteString, AccountTask]
  def knownAvailablePeers: mutable.Set[Peer]
  def peerCooldownUntilMs: mutable.Map[String, Long]
  def lastDispatchTimeMs: mutable.Map[String, Long]
  var consecutiveUnproductiveRefreshes: Int

/** What `AccountTaskLifecycle` calls in the coordinator core: the progress snapshot sent to the controller (made
  * durable first when requested), which a range completed by an empty proof of absence triggers.
  */
private[actors] trait AccountProgressApi:
  def sendProgressSnapshot(durableRequested: Boolean = false): Unit

/** What `AccountTaskLifecycle` calls in the contract carry: identifying the contract accounts of a response for the
  * storage and bytecode task files. The coordinator core provides it until C-A4 moves it into `ContractWorkCarry`.
  */
private[actors] trait ContractWorkCarryApi:
  def identifyContractAccounts(accounts: Seq[(ByteString, Account)]): Unit
