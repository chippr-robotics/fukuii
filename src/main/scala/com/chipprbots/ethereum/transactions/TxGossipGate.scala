package com.chipprbots.ethereum.transactions

import java.util.concurrent.atomic.AtomicBoolean

import com.chipprbots.ethereum.utils.Logger

/** Whether this node takes part in transaction gossip from peers: go-ethereum's `handler.synced` flag (eth/handler.go),
  * read through `AcceptTxs()` by every inbound tx handler in eth/protocols/eth/handlers.go
  * (`NewPooledTransactionHashes`, `Transactions`, `PooledTransactions`). An unsynced go-ethereum ignores all three: it
  * requests nothing, stores nothing and marks nothing known.
  *
  * Peer transactions are accepted iff `synced && !stateSyncRunning`:
  *
  *   - `synced` is go-ethereum's flag and, as there, never goes back to false. It is set when the node has caught up
  *     once: an Engine API forkchoiceUpdated that moved the head (go-ethereum's `SetSynced()` in eth/catalyst/api.go),
  *     regular sync importing up to the best block peers have announced (go-ethereum's downloader success callback), or
  *     block production starting (core-geth's `StartMining` calls `enableSyncedFeatures()`).
  *   - `stateSyncRunning` is fukuii's own: SyncController holds it while SNAP sync or the post-SNAP state recovery
  *     runs, when the node has no complete state to validate a transaction against. go-ethereum needs no such term
  *     because its forkchoiceUpdated cannot report a head whose state it lacks; fukuii's SNAP commits the pivot as its
  *     best block, so a forkchoiceUpdated could mark the node synced before the state is there.
  *
  * Why it exists: a Sepolia node in SNAP sync kept accepting blob transactions from peers. With no state, pool
  * admission rejected every one, but their network-form sidecars (130-830 KB each) were stored before admission and
  * never removed; 2.6 GB of them OOM-killed the node every ~10 hours.
  *
  * Local submissions (eth_sendRawTransaction) are not gated, as in go-ethereum.
  *
  * Plain atomics, not an actor: every inbound tx message reads it, so a read must cost nothing.
  */
final class TxGossipGate(initiallySynced: Boolean = false) extends Logger:
  private val synced = new AtomicBoolean(initiallySynced)
  private val stateSyncRunning = new AtomicBoolean(false)

  /** Whether transactions from peers are processed at all. */
  def acceptTxs: Boolean = synced.get() && !stateSyncRunning.get()

  /** The node has caught up (see the class comment for who calls this). Idempotent; logs the first time only. */
  def markSynced(reason: String): Unit =
    if synced.compareAndSet(false, true) then
      log.info("Node considered synced ({}); transaction gossip from peers {}", reason, gossipState)

  /** SNAP sync or state recovery started: the node has no complete state. */
  def stateSyncStarted(): Unit =
    if !stateSyncRunning.getAndSet(true) then
      log.info("State sync running; transaction gossip from peers paused until regular sync takes over")

  /** Regular sync took over from SNAP / state recovery. */
  def stateSyncFinished(): Unit =
    if stateSyncRunning.getAndSet(false) then
      log.info("State sync finished; transaction gossip from peers {}", gossipState)

  private def gossipState: String =
    if acceptTxs then "enabled" else "still disabled (waiting for the node to catch up)"

object TxGossipGate:

  /** A gate that is open from the start, for wiring that has no sync process to open it (tests, tools). */
  def alwaysOpen: TxGossipGate = new TxGossipGate(initiallySynced = true)
