# ByteCode Download Implementation for SNAP Sync

## Overview

This document describes the bytecode download implementation for Fukuii's SNAP sync protocol. Bytecode download is a critical component that enables full contract state synchronization by fetching the executable code for smart contracts discovered during account range sync.

## Architecture

### Components

#### 1. ByteCodeTask
**Location:** `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/ByteCodeTask.scala`

Represents a batch of bytecode hashes to download. Key features:
- Batches up to 16 bytecode requests per task (configurable)
- Tracks pending/done state for each batch
- Calculates download progress
- Validates account hash to code hash pairing

**Creation Methods:**
```scala
// From contract accounts (accountHash, codeHash)
ByteCodeTask.createBytecodeTasksFromAccounts(
  contractAccounts: Seq[(ByteString, ByteString)],
  batchSize: Int = 16
): Seq[ByteCodeTask]

// From code hashes only
ByteCodeTask.createBatchedTasks(
  codeHashes: Seq[ByteString],
  batchSize: Int = 16
): Seq[ByteCodeTask]
```

#### 2. ByteCodeDownloader
**Location:** `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/ByteCodeDownloader.scala`

Manages the download and verification of contract bytecodes. Responsibilities:
- Queues contract accounts for bytecode download
- Sends GetByteCodes requests to SNAP-capable peers
- Verifies bytecode hash matches expected codeHash (keccak256)
- Stores verified bytecodes in EvmCodeStorage
- Tracks download statistics and progress

**Key Methods:**
```scala
// Queue contract accounts for download
def queueContracts(contractAccounts: Seq[(ByteString, ByteString)]): Unit

// Request next batch from a peer
def requestNextBatch(peer: Peer): Option[BigInt]

// Handle ByteCodes response
def handleResponse(response: ByteCodes): Either[String, Int]

// Check if download is complete
def isComplete: Boolean
```

#### 3. AccountRangeDownloader (Modified)
**Location:** `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/AccountRangeDownloader.scala`

Enhanced to identify contract accounts during account sync:
- Detects accounts with `codeHash != Account.EmptyCodeHash`
- Collects `(accountHash, codeHash)` pairs for contracts
- Provides access via `getContractAccounts()` method

**New Methods:**
```scala
// Get collected contract accounts
def getContractAccounts: Seq[(ByteString, ByteString)]

// Get count of contracts found
def getContractAccountCount: Int
```

#### 4. SNAPSyncController (Updated)
**Location:** `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncController.scala`

Orchestrates the bytecode sync phase:
- Added `ByteCodeSync` phase between `AccountRangeSync` and `StorageRangeSync`
- Creates ByteCodeDownloader with contract accounts from AccountRangeDownloader
- Sends periodic bytecode requests to SNAP-capable peers
- Handles ByteCodes responses and tracks progress

**Phase Transitions:**
```
AccountRangeSync → ByteCodeSync → StorageRangeSync → StateHealing → StateValidation
```

## Workflow

### 1. Account Range Sync
During account range download:
```scala
// In AccountRangeDownloader.processAccountRange()
identifyContractAccounts(response.accounts)

// Internally filters for contracts
accounts.collect {
  case (accountHash, account) if account.codeHash != Account.EmptyCodeHash =>
    (accountHash, account.codeHash)
}
```

### 2. ByteCode Sync Initiation
After account range sync completes:
```scala
// In SNAPSyncController
case AccountRangeSyncComplete =>
  currentPhase = ByteCodeSync
  startBytecodeSync()

def startBytecodeSync(): Unit = {
  val contractAccounts = accountRangeDownloader.map(_.getContractAccounts).getOrElse(Seq.empty)
  
  bytecodeDownloader = Some(new ByteCodeDownloader(...))
  bytecodeDownloader.foreach(_.queueContracts(contractAccounts))
  
  // Start periodic request loop
  bytecodeRequestTask = Some(scheduler.scheduleWithFixedDelay(...))
}
```

### 3. Bytecode Download Loop
Periodic requests to SNAP-capable peers:
```scala
def requestByteCodes(): Unit = {
  bytecodeDownloader.foreach { downloader =>
    snapPeers.foreach { peer =>
      downloader.requestNextBatch(peer) match {
        case Some(requestId) => // Request sent
        case None => // No more tasks
      }
    }
  }
}
```

### 4. Response Handling
When ByteCodes response arrives:
```scala
case msg: ByteCodes =>
  bytecodeDownloader.foreach { downloader =>
    downloader.handleResponse(msg) match {
      case Right(count) =>
        progressMonitor.incrementBytecodesDownloaded(count)
        if (downloader.isComplete) {
          self ! ByteCodeSyncComplete
        }
      case Left(error) => // Log error
    }
  }
```

### 5. Verification and Storage
In ByteCodeDownloader:
```scala
def verifyBytecodes(expectedHashes: Seq[ByteString], bytecodes: Seq[ByteString]): Either[String, Unit] = {
  bytecodes.zipWithIndex.foreach { case (code, idx) =>
    val expectedHash = expectedHashes(idx)
    val actualHash = kec256(code)
    if (actualHash != expectedHash) {
      return Left("Hash mismatch")
    }
  }
  Right(())
}

def storeBytecodes(bytecodes: Seq[ByteString]): Either[String, Unit] = {
  evmCodeStorage.synchronized {
    bytecodes.foreach { code =>
      val codeHash = kec256(code)
      evmCodeStorage.put(codeHash, code)
    }
    evmCodeStorage.persist()
  }
  Right(())
}
```

## Configuration

### Batch Size
Default batch size is 16 bytecodes per request, defined in `ByteCodeTask.DEFAULT_BATCH_SIZE`. This can be overridden:

```scala
new ByteCodeDownloader(
  evmCodeStorage = evmCodeStorage,
  etcPeerManager = etcPeerManager,
  requestTracker = requestTracker,
  batchSize = 32  // Custom batch size
)
```

### Response Size Limit
Maximum response size is 2 MB (larger than account/storage due to bytecode size):
```scala
private val maxResponseSize: BigInt = 2 * 1024 * 1024
```

### Request Timeout
Bytecode requests timeout after 30 seconds:
```scala
requestTracker.trackRequest(
  requestId,
  peer,
  SNAPRequestTracker.RequestType.GetByteCodes,
  timeout = 30.seconds
)
```

## Performance Characteristics

### Typical Contract Sizes
- Simple contracts: 1-10 KB
- Medium contracts: 10-50 KB  
- Large contracts: 50-100 KB
- Maximum contract size: 24 KB (EIP-170 limit)

### Batch Efficiency
With 16 codes per batch:
- Typical batch size: 100-500 KB
- Well within 2 MB response limit
- Balances request overhead vs. peer load

### Mainnet Statistics
On Ethereum mainnet:
- ~20-30% of accounts are contracts (estimate)
- For 100M accounts: ~20-30M contract accounts
- At 16 per batch: ~1.25-1.9M bytecode requests
- At 1 request/second/peer: ~350-530 hours with 1 peer

## Security

### Hash Verification
All bytecodes are verified before storage:
```scala
val actualHash = kec256(code)
if (actualHash != expectedHash) {
  return Left("Hash mismatch")
}
```

This ensures:
- Bytecode integrity (no corruption)
- Authenticity (matches account codeHash)
- Protection against malicious peers

### Thread Safety
Storage operations are synchronized:
```scala
evmCodeStorage.synchronized {
  // Store and persist
}
```

## Testing

### Unit Tests
**Location:** `src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/ByteCodeTaskSpec.scala`

Tests cover:
1. Task creation from contract accounts
2. Batching logic (35 accounts → 3 batches: 16, 16, 3)
3. Empty contract list handling
4. Pending/done state tracking
5. Progress calculation
6. Account/code hash validation
7. Empty account hashes allowance

### Integration Testing
Integration tests should verify:
- [ ] End-to-end bytecode download flow
- [ ] Interaction with real SNAP-capable peers
- [ ] Correct storage in EvmCodeStorage
- [ ] Performance under load
- [ ] Error handling and retry logic

## Monitoring

### Progress Tracking
```scala
case class SyncStatistics(
  bytecodesDownloaded: Long,
  bytesDownloaded: Long,
  tasksCompleted: Int,
  tasksActive: Int,
  tasksPending: Int,
  elapsedTimeMs: Long,
  progress: Double
)
```

### Metrics
- `throughputBytecodesPerSec`: Codes downloaded per second
- `throughputBytesPerSec`: Bytes downloaded per second
- `progress`: Completion percentage (0.0 to 1.0)

### Logging
Key log events:
- `"Identified N contract accounts"` - Contract discovery
- `"Queued N bytecode tasks for N contract accounts"` - Task creation
- `"Requesting N bytecodes from N SNAP peers"` - Request loop
- `"Successfully processed N bytecodes"` - Response handling
- `"Bytecode sync complete!"` - Phase completion

## Error Handling

### Timeout Recovery
Timed out requests are automatically retried:
```scala
def handleTimeout(requestId: BigInt): Unit = synchronized {
  activeTasks.remove(requestId).foreach { task =>
    log.warn(s"Bytecode request timeout for task ${task.taskString}")
    task.pending = false
    tasks.enqueue(task)  // Re-queue for retry
  }
}
```

### Verification Failures
Hash mismatches trigger error logging but don't crash:
```scala
case Left(error) =>
  log.warn(s"Bytecode verification failed: $error")
  return Left(s"Verification failed: $error")
```

### Storage Errors
Storage failures are caught and logged:
```scala
try {
  evmCodeStorage.synchronized { ... }
} catch {
  case e: Exception =>
    log.error(s"Failed to store bytecodes: ${e.getMessage}", e)
    Left(s"Storage error: ${e.getMessage}")
}
```

## Future Enhancements

### Potential Optimizations
1. **Parallel Downloads**: Download from multiple peers simultaneously
2. **Caching**: Check EvmCodeStorage before requesting (avoid re-downloads)
3. **Prioritization**: Download frequently-used contracts first
4. **Compression**: Use Snappy compression for bytecode transfer
5. **Deduplication**: Skip duplicate codeHash requests

### Metrics Integration
- [ ] Prometheus metrics for bytecode download rate
- [ ] Grafana dashboard for SNAP sync progress
- [ ] Alerting on slow/failed bytecode sync

### Advanced Error Handling
- [ ] Exponential backoff for repeated failures
- [ ] Peer reputation based on bytecode quality
- [ ] Fallback to alternative peers on verification failure
- [ ] Circuit breaker for persistently failing requests

## References

- [SNAP Protocol Specification](https://github.com/ethereum/devp2p/blob/master/caps/snap.md)
- [EIP-170: Contract Code Size Limit](https://eips.ethereum.org/EIPS/eip-170)
- Core-Geth SNAP Implementation: `eth/protocols/snap/sync.go`
- Fukuii SNAP Sync TODO: `docs/architecture/SNAP_SYNC_TODO.md`
- Fukuii SNAP Sync Status: `docs/architecture/SNAP_SYNC_STATUS.md`

## Contributors

- Implementation: GitHub Copilot
- Review: @realcodywburns
- Integration: Fukuii Team

---

**Last Updated:** 2025-12-02  
**Status:** Implementation Complete, Testing In Progress  
**Version:** 1.0
