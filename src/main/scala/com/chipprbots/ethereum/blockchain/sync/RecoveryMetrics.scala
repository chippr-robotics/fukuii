package com.chipprbots.ethereum.blockchain.sync

import java.util.concurrent.atomic.AtomicLong

import com.chipprbots.ethereum.metrics.MetricsContainer

/** Progress gauges for the post-SNAP bytecode + storage recovery scan (see [[BytecodeRecoveryActor]] and
  * [[StorageRecoveryActor]]).
  *
  * The recovery scan walks the entire state trie once after SNAP sync to find — and re-fetch — any contract bytecode or
  * storage sub-tries the streaming/deferred-merkleization build left out. On a large chain (ETC mainnet, ~16.8M
  * accounts) that walk takes a long time and, until now, surfaced nothing on the dashboards: the actors only logged a
  * line every 1M accounts. These gauges make the scan observable on the node-health dashboard — how far it has walked,
  * how many gaps it found, and which phase each recoverer is in.
  *
  * Exposed (Micrometer → Prometheus, `app_` prefix) as e.g. `app_recovery_storage_accountsScanned_gauge`.
  *
  * Phase encoding: 0 = idle, 1 = scanning, 2 = downloading, 3 = complete.
  */
object RecoveryMetrics extends MetricsContainer {

  val PhaseIdle: Long = 0L
  val PhaseScanning: Long = 1L
  val PhaseDownloading: Long = 2L
  val PhaseComplete: Long = 3L

  // --- Bytecode recovery ---
  final private val BytecodeAccountsScanned =
    metrics.registry.gauge("recovery.bytecode.accountsScanned.gauge", new AtomicLong(0L))
  final private val BytecodeContractsFound =
    metrics.registry.gauge("recovery.bytecode.contractsFound.gauge", new AtomicLong(0L))
  final private val BytecodeMissing =
    metrics.registry.gauge("recovery.bytecode.missing.gauge", new AtomicLong(0L))
  final private val BytecodeDownloaded =
    metrics.registry.gauge("recovery.bytecode.downloaded.gauge", new AtomicLong(0L))
  final private val BytecodePhase =
    metrics.registry.gauge("recovery.bytecode.phase.gauge", new AtomicLong(0L))

  // --- Storage recovery ---
  final private val StorageAccountsScanned =
    metrics.registry.gauge("recovery.storage.accountsScanned.gauge", new AtomicLong(0L))
  final private val StorageContractsFound =
    metrics.registry.gauge("recovery.storage.contractsFound.gauge", new AtomicLong(0L))
  final private val StorageMissing =
    metrics.registry.gauge("recovery.storage.missing.gauge", new AtomicLong(0L))
  final private val StorageDownloaded =
    metrics.registry.gauge("recovery.storage.downloaded.gauge", new AtomicLong(0L))
  final private val StoragePhase =
    metrics.registry.gauge("recovery.storage.phase.gauge", new AtomicLong(0L))

  def setBytecodeScanProgress(accountsScanned: Long, contractsFound: Long, missing: Long): Unit = {
    BytecodeAccountsScanned.set(accountsScanned)
    BytecodeContractsFound.set(contractsFound)
    BytecodeMissing.set(missing)
  }
  def setBytecodePhase(phase: Long): Unit = BytecodePhase.set(phase)
  def setBytecodeDownloaded(count: Long): Unit = BytecodeDownloaded.set(count)

  def setStorageScanProgress(accountsScanned: Long, contractsFound: Long, missing: Long): Unit = {
    StorageAccountsScanned.set(accountsScanned)
    StorageContractsFound.set(contractsFound)
    StorageMissing.set(missing)
  }
  def setStoragePhase(phase: Long): Unit = StoragePhase.set(phase)
  def setStorageDownloaded(count: Long): Unit = StorageDownloaded.set(count)
}
