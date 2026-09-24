package com.chipprbots.ethereum.consensus.engine

import java.security.MessageDigest

import org.apache.pekko.util.ByteString

import cats.effect.IO

import com.chipprbots.ethereum.consensus.eip1559.BaseFeeCalculator
import com.chipprbots.ethereum.consensus.engine.PayloadStatus.*
import com.chipprbots.ethereum.consensus.validators.std.MptListValidator
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.domain.Withdrawal.*
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.mpt.ByteArraySerializable
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*
import com.chipprbots.ethereum.rlp.encode as rlpEncode
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Logger

/** Core Engine API logic. Converts ExecutionPayloads to Blocks, validates, and executes them. Integrates with
  * ForkChoiceManager for CL-driven fork choice.
  */
class EngineApiService(
    blockchainReader: BlockchainReader,
    blockchainWriter: BlockchainWriter,
    blockExecution: BlockExecution,
    forkChoiceManager: ForkChoiceManager,
    pendingTransactionsManager: Option[org.apache.pekko.actor.typed.ActorRef[
      com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command
    ]]
)(implicit blockchainConfig: BlockchainConfig, typedScheduler: org.apache.pekko.actor.typed.Scheduler)
    extends Logger:

  import org.apache.pekko.util.Timeout
  import scala.concurrent.duration.*
  implicit private val askTimeout: Timeout = Timeout(3.seconds)

  /*
   * EVICTION POLICY (CLASS A — BEACON approved 2026-06-21):
   * Cap = 64 entries per map.  TTL = 12.8 minutes (2 epochs, 12_800_000_000_000 ns).
   * The CL never sends a "discard payload" call, so eviction is EL self-managed.
   * Two triggers:
   *   PUT: evict the oldest entry (smallest insertedAt across all four maps) when size >= cap.
   *   GET (getPayload only): if age > TTL, remove from all four maps and return 404.
   *        This also cleans up orphaned pendingPayloadRequests entries that V1/V2/V3
   *        getPayload calls never .remove(), since only the V4 path calls
   *        getPayloadExecutionRequests which does the remove.
   */
  private val PayloadCap = 64
  private val PayloadTtlNs = 12_800_000_000_000L // 12.8 min in nanoseconds
  private val evictionLock = new Object()

  /** Pending payloads built by forkchoiceUpdated, keyed by payloadId. */
  private val pendingPayloads = new java.util.concurrent.ConcurrentHashMap[ByteString, Block]()
  // EIP-7685 executionRequests associated with each payloadId, returned by getPayloadV4.
  private val pendingPayloadRequests =
    new java.util.concurrent.ConcurrentHashMap[ByteString, Seq[ByteString]]()
  // Receipts produced while building each payload. Used by engine_getPayloadV2+ to compute the
  // `blockValue` field (sum of priority-fee revenue) per EIP-3675 V2 envelope spec. Without this
  // the envelope returns blockValue=0x0 and the hive engine-withdrawals "GetPayloadV2 Block
  // Value" test fails with want=N, got=0.
  private val pendingPayloadReceipts =
    new java.util.concurrent.ConcurrentHashMap[ByteString, Seq[com.chipprbots.ethereum.domain.Receipt]]()
  // EIP-4844 blob sidecars (blobs, commitments, proofs) for each payload's blob txs. The hive
  // engine-cancun suite's VerifyBlobBundle asserts (a) matching counts, (b) byte-equality
  // against the sidecars the test submitted via eth_sendRawTransaction. We capture sidecars
  // during the proposer's GetPendingTransactions call and hand them to engine_getPayloadV3's
  // blobsBundle envelope.
  // cellProofsPerBlob stashes EIP-7594 PeerDAS cell proofs (128 × 48 bytes per blob)
  // for use by engine_getBlobsV2 (§ETH-T10-B).
  private val pendingPayloadBlobsBundle =
    new java.util.concurrent.ConcurrentHashMap[ByteString, BlobsBundleData]()
  // Insertion timestamps (System.nanoTime) shared by all four maps above, used for eviction.
  private val pendingPayloadTimestamps = new java.util.concurrent.ConcurrentHashMap[ByteString, Long]()

  /** Remove a payloadId from all four pending maps and the timestamp index. */
  private def removePayloadEntry(payloadId: ByteString): Unit =
    pendingPayloads.remove(payloadId)
    pendingPayloadRequests.remove(payloadId)
    pendingPayloadReceipts.remove(payloadId)
    pendingPayloadBlobsBundle.remove(payloadId)
    pendingPayloadTimestamps.remove(payloadId)

  /** If the timestamp map is at cap, find the oldest entry and remove it from all four maps. */
  private def evictOldestIfAtCapacity(): Unit = evictionLock.synchronized {
    if pendingPayloadTimestamps.size() >= PayloadCap then
      var oldestKey: ByteString = null
      var oldestTs: Long = Long.MaxValue
      val iter = pendingPayloadTimestamps.entrySet().iterator()
      while iter.hasNext do
        val e = iter.next()
        if e.getValue < oldestTs then
          oldestTs = e.getValue
          oldestKey = e.getKey
      if oldestKey ne null then removePayloadEntry(oldestKey)
  }

  /** Blocks that returned INVALID via newPayload. Maps blockHash → latestValidHash. forkchoiceUpdated should not accept
    * these as head. Children of invalid blocks inherit the latestValidHash of their invalid parent.
    */
  private val invalidBlocks = new java.util.concurrent.ConcurrentHashMap[ByteString, ByteString]()
  // Index of optimistically-accepted (by-hash-only) blocks, keyed by parentHash → set of child
  // hashes. Used to recursively invalidate descendants when an ancestor is later revealed as
  // INVALID. The hive "Invalid Missing Ancestor Syncing ReOrg" family (24 tests) hangs without
  // this because the test waits up to its internal timeout for our client to detect the invalid
  // chain through an optimistically-accepted descendant.
  private val acceptedChildrenByParent =
    new java.util.concurrent.ConcurrentHashMap[ByteString, java.util.Set[ByteString]]()
  private val zeroHash = ByteString(new Array[Byte](32))

  /** Mark `hash` as INVALID and recursively invalidate every optimistically-accepted descendant. All descendants
    * inherit the same `latestValidHash`.
    *
    * FIRST VERDICT WINS. An existing entry is never overwritten; descendants inherit the STORED latestValidHash. Every
    * legitimate writer derives the value the same way — the invalid block's own parent, or the value already stored for
    * an invalid ancestor — so two writers for one hash agree, and a disagreement means the later one is wrong. That is
    * not hypothetical: a failed p2p reorg used to re-report its already-correct verdict with the batch head's parent as
    * latestValidHash, and `put` replaced the right answer with the wrong one (see BlockImportFailedAt). A conflict is
    * logged at WARN rather than silently dropped. The finalized-watermark prune in `forkchoiceUpdated` still removes
    * entries; this only governs a second write to a live one.
    */
  private def markInvalidRecursive(hash: ByteString, lvh: ByteString): Unit =
    val effectiveLvh = Option(invalidBlocks.putIfAbsent(hash, lvh)) match
      case Some(existing) =>
        if existing != lvh then
          log.warn(
            "[ENGINE-API] block {} already INVALID with latestValidHash={}; ignoring conflicting latestValidHash={}",
            com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(hash),
            com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(existing),
            com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(lvh)
          )
        existing
      case None => lvh
    val children = Option(acceptedChildrenByParent.remove(hash))
    children.foreach { set =>
      val iter = set.iterator()
      while iter.hasNext do
        val child = iter.next()
        blockchainWriter.removeBlockByHash(BlockHash(child)).commit()
        markInvalidRecursive(child, effectiveLvh)
    }

  /** Read-only copy of the invalid-block registry (blockHash -> latestValidHash), for specs that must assert the WHOLE
    * registry — "these entries and nothing else" — rather than probe it hash by hash through forkchoiceUpdated, which
    * has side effects.
    */
  private[engine] def invalidBlocksSnapshot: Map[ByteString, ByteString] =
    import scala.jdk.CollectionConverters.*
    invalidBlocks.asScala.toMap

  /** Give an engine-executed block a `ChainWeight`, so the p2p import path can still resolve branches across it.
    *
    * WHY THIS EXISTS. `storeBlock`/`storeBlockByHashOnly` write a header and a body and nothing else
    * (BlockchainWriter.scala), and neither this class nor `ForkChoiceManager` wrote a weight before this. A node whose
    * chain was built entirely through `engine_newPayload` therefore had NO weight for any of its own blocks, and the
    * first time a competing branch arrived over p2p, `BranchResolution.compareBranch` could not resolve the fork point
    * and bailed:
    *
    * {{{
    * ERROR [BranchResolution] ChainWeight for 6: c05c5658… not found when resolving branch: List(BlockHeader{…})
    * }}}
    *
    * Measured on hive `engine` 6c8bc97: that line appears in 32 of 486 client logs, and those 32 map to all 24 `Invalid
    * Missing Ancestor Syncing ReOrg … CanonicalReOrg=True` failures plus all 4 `Withdrawals … Re-Org Sync` timeouts.
    * The branch was fetched and then discarded unexecuted, so the invalid block on it was never found and the CL was
    * never told.
    *
    * WHAT THE VALUE MEANS — very little, and that is the point. Post-merge every header carries `difficulty = 0`, so
    * `ChainWeight.increase` is the identity and every block on a PoS chain has the genesis weight. The number is not a
    * fork-choice input any more (see `BranchResolution`'s PoS arm, which asks the consensus layer instead). What
    * matters is that the entry EXISTS, because the lookup being `None` is what aborts branch resolution.
    *
    * If the parent has no stored weight we write nothing rather than inventing one. That case means the local chain was
    * seeded by some path that skipped weights (and this method, applied from the next block on, cannot repair history);
    * a fabricated weight would be a worse answer than an absent one on a chain where the number still means something.
    */
  private def storeChainWeightFor(block: Block, parentHeader: Option[BlockHeader]): Unit =
    parentHeader.flatMap(p => blockchainReader.getChainWeightByHash(p.hash)) match
      case Some(parentWeight) =>
        blockchainWriter.storeChainWeight(block.header.hash, parentWeight.increase(block.header)).commit()
      case None =>
        log.debug(
          "[ENGINE-API] no stored ChainWeight for the parent of block {} — not writing one for the child either",
          block.number
        )

  /** The one-way entry point the p2p import path uses to put a block into `invalidBlocks`.
    *
    * `invalidBlocks` and `markInvalidRecursive` stay private: this is deliberately the only door, it takes exactly the
    * two values the registry stores, and it returns nothing. A consensus-invalid block found by `BlockImporter` is
    * otherwise invisible to `newPayload`/`forkchoiceUpdated`, which then answer SYNCING/ACCEPTED forever.
    *
    * Recursion is intentional and is the same `markInvalidRecursive` the engine path uses: descendants that were
    * optimistically accepted while their ancestor was unknown must inherit the verdict and the same `latestValidHash`.
    * Unlike the engine-side call sites this does NOT remove the reported block itself from storage — on the import path
    * a block that failed execution was never saved (`BlockExecution.executeAndValidateBlocks` only writes on `Right`),
    * and `BlockImporter` separately tells `BlockFetcher` to invalidate and blacklist it.
    *
    * See [[InvalidChainReporter]] for which errors may legitimately arrive here.
    */
  val invalidChainReporter: InvalidChainReporter =
    (blockHash, latestValidHash) =>
      log.warn(
        "[ENGINE-API] import path reported block {} consensus-invalid, latestValidHash={}",
        com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(blockHash),
        com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(latestValidHash)
      )
      EngineApiMetrics.recordImportPathInvalidReport()
      markInvalidRecursive(blockHash, latestValidHash)

  /** Return the latest block number from the blockchain storage. */
  def getLatestBlockNumber: BigInt =
    blockchainReader.getBestBlockNumber

  /** engine_newPayloadV1/V2/V3/V4 — Validate and execute a new payload from the CL.
    *
    * Import strategy:
    *   1. If block hash doesn't match → INVALID_BLOCK_HASH 2. If already known → VALID (deduplicate) 3. If parent is
    *      known and we have state → full execution + validation 4. If parent is unknown → optimistic import (store
    *      block, skip execution, return VALID) This enables checkpoint sync where we follow the CL tip without full
    *      history.
    */
  def newPayload(payload: ExecutionPayload): IO[PayloadStatusV1] = IO {
    val block = payloadToBlock(payload)

    if block.header.hash.value != payload.blockHash then
      log.warn(
        "[ENGINE-API] newPayload #{}: block-hash mismatch computed={} payload={}",
        payload.blockNumber,
        block.header.hashAsHexString,
        com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(payload.blockHash)
      )
      // Hash mismatch: integrity error of the payload envelope. Per execution-apis PR #338
      // (https://github.com/ethereum/execution-apis/pull/338), starting from Shanghai (V2+)
      // the engine MUST return INVALID (not INVALID_BLOCK_HASH). V1 accepts either; returning
      // INVALID universally is compliant with all versions. latestValidHash must be null —
      // the corruption is in the payload itself, not attributable to any specific ancestor.
      //
      // Do NOT call removeBlockByHash here: payload.blockHash can collide with a legitimate
      // block (the hive "ParentHash equals BlockHash on NewPayload" test sets blockHash =
      // parentHash, which is the real parent's hash). Removing it would delete the parent.
      // We never stored anything under payload.blockHash in this call, so there is nothing
      // safe and correct to remove.
      PayloadStatusV1(Invalid, latestValidHash = None, validationError = Some("block hash mismatch"))
    else if
      // EIP-4844 versioned-hash check must run BEFORE the "already stored" dedup. The hive
      // "NewPayloadV3 Versioned Hashes, Non-Empty Hashes" tests call newPayloadV3 twice for
      // the same payload — once with matching hashes (expected VALID, block gets stored),
      // and again with tampered hashes (expected INVALID). Without this early check the
      // tampered second call hits the dedup branch and silently returns VALID.
      payload.expectedBlobVersionedHashes.exists { expected =>
        val payloadHashes: Seq[ByteString] =
          block.body.transactionList.flatMap {
            case SignedTransaction(blobTx: com.chipprbots.ethereum.domain.BlobTransaction, _) =>
              blobTx.blobVersionedHashes.map(_.value)
            case _ => Nil
          }
        expected != payloadHashes
      }
    then
      // VersionedHashes mismatch: INVALID per EIP-4844 with latestValidHash=parent.hash.
      // Do NOT add to invalidBlocks — the mismatch is between the CL-supplied
      // `expectedBlobVersionedHashes` argument and the payload's actual blob txs, not a
      // property of the block itself. A later newPayload call with the SAME blockHash but
      // matching versioned hashes must be accepted as VALID (hive 'Invalid NewPayload,
      // VersionedHashes, Syncing=True' sends exactly that pattern and then expects FCU to
      // return VALID, not 'head block was previously invalidated').
      val lvh =
        blockchainReader.getBlockHeaderByHash(BlockHash(payload.parentHash)).map(_.hash.value).getOrElse(zeroHash)
      PayloadStatusV1(Invalid, latestValidHash = Some(lvh), validationError = Some("INVALID_VERSIONED_HASHES"))
    else if blockchainReader.getBlockHeaderByHash(BlockHash(payload.blockHash)).exists { h =>
        blockchainReader.getBlockHeaderByNumber(h.number.value).exists(_.hash.value == payload.blockHash) &&
        blockchainReader.getReceiptsByHash(h.hash).isDefined
      }
    then
      // Already fully stored AND executed — skip re-execution.
      //
      // A canonical number→hash mapping alone is NOT proof of execution and must never be
      // used as one: several sync paths (SNAP pivot bootstrap, header backfill, fast-sync
      // state updates) legitimately publish a number→hash mapping for a block whose body and
      // receipts arrive later. Answering VALID off that mapping claims we validated a block
      // we never ran. Receipts are the codebase's existing was-executed predicate (see
      // `parentValidated` below and EthBlocksService's `isExposed`).
      //
      // Be precise about what this conjunct does and does not prove. Receipts are NOT a
      // guarantee of local execution: ChainDownloader.scala:619/:703 and FastSync.scala:1170
      // store peer-supplied receipts on the backfill path without executing anything, so on a
      // fast/SNAP-synced node a block can have receipts it never earned. What the conjunct
      // does give is a strictly tighter predicate than the mapping alone — every block it
      // admits was already admitted before — so it can only move answers from VALID toward
      // ACCEPTED/SYNCING, never the reverse. That direction is the safe one: re-executing a
      // block we had already validated costs time, whereas skipping execution on a block we
      // had not costs correctness.
      //
      // The one thing it does prove cleanly is the empty-block case: storeReceipts pickles a
      // non-empty value even for an empty Seq, so a zero-tx block yields Some(Nil), not None,
      // and is not forced into needless re-execution.
      PayloadStatusV1(Valid, latestValidHash = Some(payload.blockHash))
    else if invalidBlocks.containsKey(payload.parentHash) then
      // Parent was previously marked INVALID — child inherits invalidity.
      // Propagate the parent's latestValidHash (the last valid ancestor).
      val propagatedLvh = invalidBlocks.get(payload.parentHash) // non-null: containsKey guard
      blockchainWriter.removeBlockByHash(BlockHash(payload.blockHash)).commit()
      markInvalidRecursive(payload.blockHash, propagatedLvh)
      EngineApiMetrics.recordNewPayload("INVALID", payload.blockNumber.toLong, payload.timestamp)
      PayloadStatusV1(
        Invalid,
        latestValidHash = Some(propagatedLvh),
        validationError = Some("parent block was previously invalidated")
      )
    else
      // Try full execution if parent block is known
      val parentKnown = blockchainReader.getBlockHeaderByHash(BlockHash(payload.parentHash)).isDefined
      val parentHeader = blockchainReader.getBlockHeaderByHash(BlockHash(payload.parentHash))

      // Pre-execution header validation (catches modified Number, GasLimit, Timestamp, BlobGas)
      val headerInvalid: Option[String] = parentHeader.flatMap { parent =>
        lazy val expectedBaseFee = BaseFeeCalculator.calcBaseFee(parent, blockchainConfig)
        if block.header.number != parent.number + 1 then
          Some(s"invalid block number: expected ${parent.number + 1} got ${block.header.number}")
        else if block.header.unixTimestamp <= parent.unixTimestamp then
          Some(s"invalid timestamp: ${block.header.unixTimestamp} <= parent ${parent.unixTimestamp}")
        else if block.header.gasLimit < GasAmount(5000) then
          Some(s"gas limit below minimum: ${block.header.gasLimit} < 5000")
        else
          // EIP-1559 gas limit bounds: |gasLimit - effectiveParent| < effectiveParent / 1024.
          //
          // This inline check duplicates BlockHeaderValidatorSkeleton.validateGasLimit and MUST
          // stay in step with it — the engine newPayload path does not route through the skeleton
          // validator, so a rule applied in only one of the two makes the client accept blocks it
          // will not produce (or reject blocks it does produce). Extraction of a shared helper is
          // deliberately left to a separate commit.
          //
          // effectiveParent applies the same one-shot elasticity scaling at the fork-activation
          // block: when the parent is the last pre-fork block and the child is the first post-fork
          // block, go-ethereum validates the ±1/1024 window around parent.gasLimit * multiplier.
          // Some(2) on ETH/Sepolia/hive; None on ETC (see BlockchainConfig field comment).
          // Both the diff and the divisor come from the scaled value.
          val olympiaBlockNumber = blockchainConfig.forkBlockNumbers.olympiaBlockNumber
          val effectiveParentGasLimit =
            blockchainConfig.forkBlockNumbers.olympiaGasLimitElasticity match
              case Some(multiplier)
                  if parent.number.value < olympiaBlockNumber &&
                    block.header.number.value >= olympiaBlockNumber =>
                // GasAmount has *(Long) and *(BigInt) but no *(Int) — widen explicitly.
                parent.gasLimit * BigInt(multiplier)
              case _ => parent.gasLimit
          val diff = (block.header.gasLimit - effectiveParentGasLimit).abs
          val limit = effectiveParentGasLimit / 1024
          // The `!= parent.gasLimit` clause is NOT in go-ethereum. It exists as a guard for the
          // limit == 0 case (a zero/tiny parent gas limit makes the bound 0, so diff >= limit holds
          // even for an unchanged gas limit and an otherwise-valid chain is rejected). It is
          // compared against the RAW parent on purpose: "gas limit unchanged from the parent" is a
          // statement about the actual header field, not the scaled bound input. Removing this
          // clause to match geth is a separate, separately-reviewed change.
          if diff >= limit && block.header.gasLimit != parent.gasLimit then
            Some(s"invalid gas limit change: diff=$diff exceeds bound=$limit")
          // EIP-1559: baseFeePerGas must equal the value computed from the parent (go-ethereum
          // consensus/misc/eip1559.VerifyEIP1559Header, "invalid baseFee"). Like the gas-limit
          // check above, this duplicates a BlockHeaderValidatorSkeleton rule (validateBaseFee)
          // because this path executes with alreadyValidated = true and never reaches the
          // skeleton. Without it a payload whose baseFee is wrong but whose transactions do not
          // depend on it (e.g. an empty block) executes to the header's own state root and is
          // answered VALID. BaseFeeCalculator.calcBaseFee is the same function the skeleton and
          // this service's payload builder use, so validator and producer cannot disagree.
          // payloadToBlock always populates baseFee from payload.baseFeePerGas; the None arm is
          // unreachable today and is rejected rather than skipped should that ever change.
          else if !block.header.baseFee.contains(expectedBaseFee) then
            Some(
              s"INVALID_BASEFEE_PER_GAS: invalid baseFee: have ${block.header.baseFee.getOrElse("none")}, " +
                s"want $expectedBaseFee, parentBaseFee ${parent.baseFee.getOrElse("none")}, " +
                s"parentGasUsed ${parent.gasUsed}"
            )
          // EIP-4844: Validate excessBlobGas against parent.
          // EIP-7691 (Prague) raises target 3→6 blobs; EIP-7892 BPO1/BPO2 raise it 6→8→12.
          // Pass the right target based on the CHILD block's fork timestamp (child is the
          // one being validated; parent may precede the active BPO).
          else if block.header.excessBlobGas.isDefined then
            val parentExcess = parent.excessBlobGas.getOrElse(BigInt(0))
            val parentUsed = parent.blobGasUsed.getOrElse(BigInt(0))
            val parentBaseFee = parent.baseFee.getOrElse(BigInt(0))
            val expectedExcess = BlobGasUtils.expectedExcessBlobGas(
              parentExcess,
              parentUsed,
              parentBaseFee,
              block.header.unixTimestamp,
              blockchainConfig
            )
            val actual = block.header.excessBlobGas.get
            if actual != expectedExcess then
              // Include canonical EEST exception name so the test framework's mapper matches.
              Some(s"INCORRECT_EXCESS_BLOB_GAS: expected $expectedExcess got $actual")
            else
              // Validate blobGasUsed: count blob txs * GAS_PER_BLOB
              val blobTxCount = block.body.transactionList.collect {
                case SignedTransaction(blobTx: com.chipprbots.ethereum.domain.BlobTransaction, _) =>
                  blobTx.blobVersionedHashes.size
              }.sum
              val expectedBlobGas = BigInt(blobTxCount) * BlobGasUtils.GAS_PER_BLOB
              val actualBlobGas = block.header.blobGasUsed.getOrElse(BigInt(0))
              if actualBlobGas != expectedBlobGas then
                Some(s"INCORRECT_BLOB_GAS_USED: expected $expectedBlobGas got $actualBlobGas")
              else None
          else None
      }
      // EIP-4844 (newPayloadV3): the CL declares which versioned hashes it expects this
      // payload to carry. Derive our own ordered list from the payload's blob txs and
      // compare. Mismatch ⇒ INVALID (same response shape as an INCORRECT_* header error).
      val versionedHashesInvalid: Option[String] = payload.expectedBlobVersionedHashes.flatMap { expected =>
        val payloadHashes: Seq[ByteString] =
          block.body.transactionList.flatMap {
            case SignedTransaction(blobTx: com.chipprbots.ethereum.domain.BlobTransaction, _) =>
              blobTx.blobVersionedHashes.map(_.value)
            case _ => Nil
          }
        if expected == payloadHashes then None
        else
          Some(
            s"INVALID_VERSIONED_HASHES: expected ${expected.length} got ${payloadHashes.length} (first mismatch at index " +
              expected.zip(payloadHashes).indexWhere { case (e, p) => e != p } + ")"
          )
      }

      val preExecError = headerInvalid.orElse(versionedHashesInvalid)
      if preExecError.isDefined then
        val latestValid = parentHeader.map(_.hash.value).getOrElse(zeroHash)
        blockchainWriter.removeBlockByHash(BlockHash(payload.blockHash)).commit()
        markInvalidRecursive(payload.blockHash, latestValid)
        EngineApiMetrics.recordNewPayload("INVALID", payload.blockNumber.toLong, payload.timestamp)
        PayloadStatusV1(Invalid, latestValidHash = Some(latestValid), validationError = Some(preExecError.get))
      else

        // Tracks the tx-level reason for an execution failure so we can surface it in
        // PayloadStatus.validationError (for EEST exception mapping, e.g.
        // INSUFFICIENT_ACCOUNT_FUNDS, NONCE_MISMATCH_TOO_LOW).
        val executionErrorReason = new java.util.concurrent.atomic.AtomicReference[Option[String]](None)

        // Parent is "validated" iff it's canonical (has a number→hash mapping to itself) or
        // it's a known sidechain that we executed (has receipts stored). A parent we only
        // know by hash (via storeBlockByHashOnly, i.e. optimistic accept with unknown grand-
        // parent) has unverified ancestry, and a child built on it must NOT be claimed as
        // VALID — hive's "Invalid NewPayload, ParentHash" test expects ACCEPTED/SYNCING.
        val parentValidated = parentHeader.exists { p =>
          blockchainReader.getBlockHeaderByNumber(p.number.value).exists(_.hash == p.hash) ||
          blockchainReader.getReceiptsByHash(p.hash).isDefined
        }
        val executionResult =
          if parentKnown && parentValidated then
            try
              blockExecution.executeAndValidateBlockFull(block, alreadyValidated = true) match
                case Right((receipts, derivedRequests)) =>
                  // EIP-7685: Per Engine API spec, verify the CL-supplied executionRequests match
                  // what block execution actually produced. Mismatch → INVALID (e.g. CL attempted
                  // to inject a deposit/withdrawal request that the execution layer didn't emit).
                  val suppliedRequests = payload.executionRequests.getOrElse(Nil)
                  val requestsMismatch =
                    blockchainConfig.isPragueTimestamp(block.header.unixTimestamp) &&
                      suppliedRequests != derivedRequests
                  if requestsMismatch then
                    val lvh = parentHeader.map(_.hash.value).getOrElse(zeroHash)
                    blockchainWriter.removeBlockByHash(BlockHash(payload.blockHash)).commit()
                    markInvalidRecursive(payload.blockHash, lvh)
                    executionErrorReason.set(
                      Some(
                        s"INVALID_REQUESTS: executionRequests mismatch " +
                          s"(supplied=${suppliedRequests.size}, derived=${derivedRequests.size})"
                      )
                    )
                    log.warn("[ENGINE-API] newPayload #{}: INVALID_REQUESTS", payload.blockNumber)
                    Some(false)
                  else
                    // Detect whether this payload extends canonical (parent == current best) or is a
                    // sidechain. For canonical-extending payloads we write number→hash; for sidechains
                    // we store by-hash-only so later forkchoiceUpdated can promote via
                    // ForkChoiceManager.promoteBranchToCanonical.
                    val extendsCanonical = parentHeader.exists { p =>
                      blockchainReader.getBlockHeaderByNumber(p.number.value).exists(_.hash == p.hash)
                    }
                    if extendsCanonical then blockchainWriter.storeBlock(block).commit()
                    else blockchainWriter.storeBlockByHashOnly(block).commit()
                    blockchainWriter.storeReceipts(block.header.hash, receipts).commit()
                    storeChainWeightFor(block, parentHeader)
                    // NB: do NOT remove txs from the pool here. A newPayload'd block is stored
                    // but not yet canonical (no FCU has advanced bestBlock); the same txs must
                    // remain available for an alternative sibling payload on the same parent
                    // (hive 'Sidechain Reorg' test). Pool removal happens in forkchoiceUpdated
                    // once the block is promoted.
                    log.info(
                      "[ENGINE-API] newPayload #{}: EXECUTED OK (txs={} sidechain={} requests={} headerStateRoot={}...)",
                      payload.blockNumber,
                      block.body.numberOfTxs,
                      !extendsCanonical,
                      derivedRequests.size,
                      block.header.stateRoot.value.take(8).map("%02x".format(_)).mkString
                    )
                    Some(true) // fully executed
                case Left(error) =>
                  error match
                    case com.chipprbots.ethereum.ledger.BlockExecutionError.MPTError(_) |
                        com.chipprbots.ethereum.ledger.BlockExecutionError.MissingParentError =>
                      // Missing state — can't validate, return SYNCING
                      log.warn("[ENGINE-API] newPayload #{}: missing state, SYNCING", payload.blockNumber)
                      None
                    case _ =>
                      // Genuine validation failure (wrong stateRoot, gasUsed, receipts, etc.)
                      val lvh = parentHeader.map(_.hash.value).getOrElse(zeroHash)
                      blockchainWriter.removeBlockByHash(BlockHash(payload.blockHash)).commit()
                      markInvalidRecursive(payload.blockHash, lvh)
                      executionErrorReason.set(Some(error.describe))
                      log.warn("[ENGINE-API] newPayload #{}: INVALID reason={}", payload.blockNumber, error.describe)
                      Some(false)
            catch
              case _: com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MPTException =>
                // Missing state nodes — can't execute, return SYNCING
                log.warn("[ENGINE-API] newPayload #{}: MPT error, SYNCING", payload.blockNumber)
                None
              case e: Exception =>
                log.warn("[ENGINE-API] newPayload #{}: error={} SYNCING", payload.blockNumber, e.getMessage)
                None
          else None // parent not known

        executionResult match
          case Some(true) =>
            // Fully executed and validated
            EngineApiMetrics.recordNewPayload("VALID", payload.blockNumber.toLong, payload.timestamp)
            PayloadStatusV1(Valid, latestValidHash = Some(payload.blockHash))

          case Some(false) =>
            // Execution failed — block is invalid. latestValidHash was stored in invalidBlocks above.
            val latestValid = Option(invalidBlocks.get(payload.blockHash))
            EngineApiMetrics.recordNewPayload("INVALID", payload.blockNumber.toLong, payload.timestamp)
            PayloadStatusV1(
              Invalid,
              latestValidHash = latestValid,
              validationError = Some(executionErrorReason.get().getOrElse("block execution failed"))
            )

          case None =>
            // Parent unknown OR parent known but unvalidated (optimistic chain). Store by
            // hash only so the block doesn't appear in eth_getBlockByNumber / eth_getBlock-
            // ByHash, but can be deduped and retroactively invalidated later.
            blockchainWriter.storeBlockByHashOnly(block).commit()
            // Record parent→child so that if the (still-unknown) ancestor chain is later
            // revealed as INVALID, we can retroactively invalidate this block too. Required
            // by hive's "Invalid Missing Ancestor Syncing ReOrg" tests.
            acceptedChildrenByParent
              .computeIfAbsent(
                payload.parentHash,
                _ => java.util.concurrent.ConcurrentHashMap.newKeySet[ByteString]()
              )
              .add(payload.blockHash)
            log.info("[ENGINE-API] newPayload #{}: ACCEPTED (parent unknown)", payload.blockNumber)
            EngineApiMetrics.recordNewPayload("ACCEPTED", payload.blockNumber.toLong, payload.timestamp)
            PayloadStatusV1(Accepted)
      // end headerInvalid else
  }

  /** engine_forkchoiceUpdatedV1/V2/V3 — Update fork choice state, optionally start payload building. Returns
    * Left(errorMessage) for JSON-RPC error responses (e.g. invalid forkchoice state), Right(response) for normal
    * payload status responses.
    */
  def forkchoiceUpdated(
      forkChoiceState: ForkChoiceState,
      payloadAttributes: Option[PayloadAttributes]
  ): IO[Either[String, ForkchoiceUpdatedResponse]] = IO.defer {
    // Check invalid/unvalidated blocks BEFORE applying fork choice state
    // (applyForkChoiceState calls saveBestKnownBlocks which would make the block canonical)
    val zeroHash = ByteString(new Array[Byte](32))

    if invalidBlocks.containsKey(forkChoiceState.headBlockHash) then
      val latestValid = Option(invalidBlocks.get(forkChoiceState.headBlockHash))
      EngineApiMetrics.recordForkchoiceUpdated("INVALID")
      IO.pure(
        Right(
          ForkchoiceUpdatedResponse(
            payloadStatus = PayloadStatusV1(
              Invalid,
              latestValidHash = latestValid,
              validationError = Some("head block was previously invalidated")
            )
          )
        )
      )
    else
      // Check if the head block is fully stored (number→hash mapping exists).
      // Blocks stored via storeBlockByHashOnly (ACCEPTED) don't have this mapping.
      // Chain-imported blocks (chain.rlp) and newPayload VALID blocks DO have it.
      val headHeader = blockchainReader.getBlockHeaderByHash(BlockHash(forkChoiceState.headBlockHash))
      val blockFullyStored = headHeader.exists { header =>
        blockchainReader
          .getBlockHeaderByNumber(header.number.value)
          .exists(_.hash.value == forkChoiceState.headBlockHash)
      }
      val blockExistsByHash = headHeader.isDefined
      val isGenesis = forkChoiceState.headBlockHash == blockchainReader
        .getBlockHeaderByNumber(0)
        .map(_.hash)
        .getOrElse(ByteString.empty)

      // Per Engine API spec 5.4 + hive "In-Order Consecutive Payload Execution": if the
      // head itself is unknown, return SYNCING first — we can't meaningfully validate
      // safe/finalized ancestry against a head we don't have. The safe/finalized unknown
      // checks are only -38002 errors once we KNOW the head; otherwise the CL is still
      // driving us to sync and the correct response is SYNCING.
      val safeHash = forkChoiceState.safeBlockHash
      val finalizedHash = forkChoiceState.finalizedBlockHash
      val safeUnknown = safeHash != zeroHash && blockchainReader.getBlockHeaderByHash(BlockHash(safeHash)).isEmpty
      val finalizedUnknown =
        finalizedHash != zeroHash && blockchainReader.getBlockHeaderByHash(BlockHash(finalizedHash)).isEmpty
      // Head-known-but-unvalidated: the block was stored optimistically (storeBlockByHashOnly,
      // no receipts, no canonical number mapping) because its parent chain isn't traceable.
      // In this state we're still syncing, so ALL status flavors — including safe/finalized
      // unknown — should yield SYNCING, not -38002. Hive's 'Invalid NewPayload, *VersionedHashes,
      // Syncing=True' tests rely on this.
      val headOptimistic =
        blockExistsByHash && !blockFullyStored && !isGenesis &&
          blockchainReader.getReceiptsByHash(BlockHash(forkChoiceState.headBlockHash)).isEmpty

      if !blockExistsByHash && !isGenesis then
        // Head unknown — client is still syncing to this head. Notify ForkChoiceManager
        // anyway so its BeaconHead listener (SyncController) can drive SNAP-sync pivot
        // selection. Without this, post-merge cold-start hangs forever in CL-PIVOT
        // wait state because the FCU short-circuits before publishBeaconHead fires.
        // notifyBeaconHead publishes and nothing else — see the headOptimistic branch below
        // for why this must not be applyForkChoiceState.
        forkChoiceManager.notifyBeaconHead(forkChoiceState)
        EngineApiMetrics.recordForkchoiceUpdated("SYNCING")
        IO.pure(Right(ForkchoiceUpdatedResponse(payloadStatus = PayloadStatusV1(Syncing))))
      else if headOptimistic then
        // Same rationale as the unknown-head case: drive ForkChoiceManager so SNAP sync
        // can re-pivot on the freshest CL head while we're still optimistically caught up.
        //
        // MUST be notifyBeaconHead, NOT applyForkChoiceState. The head here exists by hash
        // but was never executed (storeBlockByHashOnly: no receipts, no number→hash mapping).
        // applyForkChoiceState finds the header by hash, so it takes its head-known branch and
        // runs promoteBranchToCanonical + saveBestKnownBlocks — writing a canonical number→hash
        // mapping for a block we never validated. newPayload's dedup branch then reads that
        // mapping back as proof of execution and answers VALID for an invalid block; hive
        // invalid_payload.go:242 ("Invalid NewPayload, Transaction *, Syncing=True") requires
        // INVALID. The write also (a) makes headOptimistic self-falsifying, so a repeated FCU
        // took the full path and returned VALID where invalid_payload.go:253 allows only
        // SYNCING|INVALID, and (b) exposed the unexecuted block via eth_getBlockByHash
        // (EthBlocksService `isExposed`), breaking invalid_payload.go:258.
        forkChoiceManager.notifyBeaconHead(forkChoiceState)
        EngineApiMetrics.recordForkchoiceUpdated("SYNCING")
        IO.pure(Right(ForkchoiceUpdatedResponse(payloadStatus = PayloadStatusV1(Syncing))))
      else if safeUnknown || finalizedUnknown then
        val msg = if safeUnknown then "unknown safe block hash" else "unknown finalized block hash"
        EngineApiMetrics.recordForkchoiceUpdated("INVALID")
        IO.pure(Left(msg))
      else if headHeader.isDefined && !isAncestorOrEqual(safeHash, forkChoiceState.headBlockHash, zeroHash) then
        EngineApiMetrics.recordForkchoiceUpdated("INVALID")
        IO.pure(Left("invalid forkchoice state: safe block is not an ancestor of head"))
      else if headHeader.isDefined && !isAncestorOrEqual(finalizedHash, forkChoiceState.headBlockHash, zeroHash)
      then
        EngineApiMetrics.recordForkchoiceUpdated("INVALID")
        IO.pure(Left("invalid forkchoice state: finalized block is not an ancestor of head"))
      else

        forkChoiceManager.applyForkChoiceState(forkChoiceState) match
          case Left(_) =>
            // Head not known — return SYNCING so CL knows we need newPayload
            EngineApiMetrics.recordForkchoiceUpdated("SYNCING")
            IO.pure(Right(ForkchoiceUpdatedResponse(payloadStatus = PayloadStatusV1(Syncing))))

          case Right(()) =>
            // FCU has advanced best-block; purge the head block's txs from the mempool
            // so the next proposer build doesn't re-queue them (would cause
            // NONCE_MISMATCH_TOO_LOW).
            pendingTransactionsManager.foreach { ptm =>
              blockchainReader.getBlockByHash(BlockHash(forkChoiceState.headBlockHash)).foreach { headBlock =>
                if headBlock.body.transactionList.nonEmpty then
                  ptm ! com.chipprbots.ethereum.transactions.PendingTransactionsManager
                    .RemoveTransactions(headBlock.body.transactionList)
              }
            }

            // CLASS B — finalized-watermark prune (BEACON approved 2026-06-21):
            // Remove stale invalidBlocks / acceptedChildrenByParent entries for blocks at
            // or below the finalized height. Blocks ABOVE the watermark are left intact —
            // a block invalid but not yet finalized may still be an FCU head candidate,
            // and premature eviction is a consensus fault.
            if finalizedHash != zeroHash then
              blockchainReader.getBlockHeaderByHash(BlockHash(finalizedHash)).foreach { finalizedHeader =>
                val finalizedNumber = finalizedHeader.number
                invalidBlocks.entrySet().removeIf { e =>
                  blockchainReader.getBlockHeaderByHash(BlockHash(e.getKey)).exists(_.number <= finalizedNumber)
                }
                acceptedChildrenByParent.entrySet().removeIf { e =>
                  blockchainReader.getBlockHeaderByHash(BlockHash(e.getKey)).exists(_.number <= finalizedNumber)
                }
              }

            // Validate payload attributes AFTER applying forkchoice — per engine-API spec
            // step ordering (apply forkchoiceState, THEN check attrs) and hive's
            // 'Invalid PayloadAttributes' test, which asserts the forkchoice IS applied
            // even when attrs are rejected with -38003.
            val invalidAttrsMsg: Option[String] = payloadAttributes.flatMap { attrs =>
              if attrs.timestamp == 0 then Some("invalid payload attributes: zero timestamp")
              else
                blockchainReader.getBlockHeaderByHash(BlockHash(forkChoiceState.headBlockHash)).flatMap { parent =>
                  // Wrap in Timestamp so the comparison is UNSIGNED. attrs.timestamp is a raw
                  // Long decoded from a uint64 Quantity; comparing it as a signed primitive
                  // rejects every payload attribute at or above 2^63 with -38003.
                  if Timestamp(attrs.timestamp) <= parent.unixTimestamp then
                    Some("invalid payload attributes: timestamp too low")
                  else None
                }
            }
            if invalidAttrsMsg.isDefined then
              EngineApiMetrics.recordForkchoiceUpdated("INVALID")
              IO.pure(Left("ATTR:" + invalidAttrsMsg.get))
            else
              payloadAttributes match
                case None =>
                  EngineApiMetrics.recordForkchoiceUpdated("VALID")
                  IO.pure(
                    Right(
                      ForkchoiceUpdatedResponse(
                        payloadStatus = PayloadStatusV1(Valid, latestValidHash = Some(forkChoiceState.headBlockHash)),
                        payloadId = None
                      )
                    )
                  )
                case Some(attrs) =>
                  // Deterministic payload ID MUST be unique for every distinct attribute
                  // combination — hive 'Unique Payload ID' test sends FCUs differing only in
                  // a single withdrawal field or beaconRoot and expects the IDs to differ.
                  // Include withdrawals + beaconRoot in the hash.
                  val withdrawalBytes: Array[Byte] =
                    attrs.withdrawals.toSeq.flatMap { ws =>
                      ws.flatMap { w =>
                        w.index.toByteArray.toSeq ++
                          w.validatorIndex.toByteArray.toSeq ++
                          w.address.bytes.toArray.toSeq ++
                          w.amount.toByteArray.toSeq
                      }
                    }.toArray
                  val beaconRootBytes = attrs.parentBeaconBlockRoot.map(_.toArray).getOrElse(Array.emptyByteArray)
                  val idBytes = kec256(
                    forkChoiceState.headBlockHash.toArray ++
                      BigInt(attrs.timestamp).toByteArray ++
                      attrs.prevRandao.toArray ++
                      attrs.suggestedFeeRecipient.bytes.toArray ++
                      withdrawalBytes ++
                      beaconRootBytes
                  )
                  val id = ByteString(idBytes.take(8))

                  val parentOpt = blockchainReader.getBlockByHash(BlockHash(forkChoiceState.headBlockHash))
                  parentOpt match
                    case None =>
                      EngineApiMetrics.recordForkchoiceUpdated("VALID")
                      IO.pure(
                        Right(
                          ForkchoiceUpdatedResponse(
                            payloadStatus =
                              PayloadStatusV1(Valid, latestValidHash = Some(forkChoiceState.headBlockHash)),
                            payloadId = Some(id)
                          )
                        )
                      )
                    case Some(parent) =>
                      selectMempoolTransactions(Timestamp(attrs.timestamp))
                        .flatMap { case (pendingTxsForBlock, blobTxRawBytesFromPool) =>
                          IO {
                            // Delegate to the single proposer-side builder (buildBlockOnParent). The engine
                            // path keeps its historical policy: parent gas limit (modulo the one-shot EIP-1559
                            // elasticity scale at London activation), the configured header extra-data, and
                            // LENIENT handling of a failed transaction.
                            val payload = buildBlockOnParent(
                              parent,
                              attrs,
                              executableAtParent(parent.header, pendingTxsForBlock),
                              ByteString("fukuii".getBytes),
                              proposerGasLimit(parent.header, parent.header.number.value + 1, None),
                              strict = false
                            ) match
                              case Right(built) => built
                              case Left(err)    =>
                                // Unreachable with strict = false; buildBlockOnParent only returns Left when strict.
                                throw new IllegalStateException(s"lenient proposer build returned Left: $err")
                            evictOldestIfAtCapacity()
                            pendingPayloadTimestamps.put(id, System.nanoTime())
                            pendingPayloads.put(id, payload.block)
                            // Also stash executionRequests so getPayloadV4 can emit them.
                            if payload.executionRequests.nonEmpty then
                              pendingPayloadRequests.put(id, payload.executionRequests)
                            // Stash receipts so getPayloadV2+ can compute the blockValue envelope field.
                            if payload.receipts.nonEmpty then pendingPayloadReceipts.put(id, payload.receipts)
                            // EIP-4844: collect the blob sidecars for every blob tx in the built payload
                            // so engine_getPayloadV3 can emit the blobsBundle envelope. Without this the
                            // envelope has empty arrays while the payload body has blob txs; the hive
                            // engine-cancun VerifyBlobBundle step fails with "expected N blob, got 0".
                            val bundle = buildBlobsBundle(payload.block.body.transactionList, blobTxRawBytesFromPool)
                            if bundle.blobs.nonEmpty then pendingPayloadBlobsBundle.put(id, bundle)
                            log.info(
                              "Built payload {} for block {} (baseFee={}, parent={}, fork={}, requests={})",
                              id.toArray.map("%02x".format(_)).mkString,
                              payload.block.header.number,
                              payload.block.header.baseFee.getOrElse(BigInt(0)),
                              parent.header.number,
                              forkNameAt(payload.block.header.unixTimestamp),
                              payload.executionRequests.size
                            )
                          }.handleError { e =>
                            log.error("Failed to build payload: {}", e.getMessage)
                          }
                        }
                        .map { _ =>
                          EngineApiMetrics.recordForkchoiceUpdated("VALID")
                          Right(
                            ForkchoiceUpdatedResponse(
                              payloadStatus =
                                PayloadStatusV1(Valid, latestValidHash = Some(forkChoiceState.headBlockHash)),
                              payloadId = Some(id)
                            )
                          )
                        }
          // closes: parentOpt match
          // closes: payloadAttributes match
          // end else (invalidAttrs check)
          // end else (safe/finalized check)
          // end case Right
      // end else (blockFullyStored check)
    // end else (invalidBlocks check)
  }

  /** Mempool selection for a proposer build: chain-ID filter, (sender, nonce) ordering, and the EIP-4844/EIP-7691
    * blob-gas cap for the fork active at `timestamp`.
    *
    * Returns the transactions to include and the network-wrapped raw bytes the pool captured for each blob tx. The
    * latter is not recoverable from the canonical transaction encoding, so it has to be carried out of here for
    * `blobsBundle` to be reconstructible.
    */
  def selectMempoolTransactions(timestamp: Timestamp): IO[(Seq[SignedTransaction], Map[ByteString, ByteString])] =
    import com.chipprbots.ethereum.transactions.PendingTransactionsManager.*
    import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
    // Fetch pending transactions from the tx pool using IO.fromFuture so the
    // CE3 compute thread is not blocked waiting for the actor response.
    pendingTransactionsManager
      .map { ptm =>
        IO.fromFuture(
          IO(
            ptm.ask[PendingTransactionsResponse](ref => GetPendingTransactionsReq(ref))
          )
        ).handleErrorWith { e =>
          log.error("Failed to fetch pending txs: {}", e.getMessage)
          IO.pure(PendingTransactionsResponse(Seq.empty))
        }
      }
      .getOrElse(IO.pure(PendingTransactionsResponse(Seq.empty)))
      .map { response =>
        val expectedChainId = blockchainConfig.chainId.value
        val filtered = response.pendingTransactions.map(_.stx.tx).filter { stx =>
          val txChainId: Option[BigInt] = stx.tx match
            case t: com.chipprbots.ethereum.domain.TransactionWithAccessList => Some(t.chainId)
            case t: com.chipprbots.ethereum.domain.TransactionWithDynamicFee => Some(t.chainId)
            case t: com.chipprbots.ethereum.domain.BlobTransaction           => Some(t.chainId)
            case t: com.chipprbots.ethereum.domain.SetCodeTransaction        => Some(t.chainId)
            case _ => None // legacy txs don't have explicit chainID
          txChainId.forall(_ == expectedChainId)
        }
        // Sort by (sender, nonce) so execution processes each sender's txs
        // in nonce order. The pool returns them in arrival order — a blob-tx
        // producer like hive's NewPayloadV3 tests sends nonces N, N+1, ...,
        // and without this sort execution hits NONCE_MISMATCH_TOO_HIGH when
        // tx with nonce N+2 runs before nonce N.
        @annotation.nowarn("cat=deprecation") // Seq[Byte] key uses Ordering.Iterable
        val pendingTxs = filtered.sortBy { stx =>
          val sender =
            SignedTransaction.getSender(stx).map(_.bytes.toArray.toSeq).getOrElse(Seq.empty)
          (sender, stx.tx.nonce)
        }
        if pendingTxs.nonEmpty then log.info("Payload includes {} pending transactions", pendingTxs.size)

        // EIP-4844 / EIP-7691: cap blob-gas included in the payload at the fork's
        // MAX_BLOB_GAS_PER_BLOCK (6 blobs Cancun, 9 blobs Prague). Without this cap
        // the proposer packs every pool blob tx into one block and getPayloadV3's
        // blobsBundle grows past the test's `ExpectedIncludedBlobCount`.
        val pendingTxsForBlock =
          val maxBlobGas = BlobGasUtils.maxBlobGasPerBlock(timestamp, blockchainConfig)
          pendingTxs
            .foldLeft((Seq.empty[SignedTransaction], BigInt(0))) { case ((kept, blobGas), stx) =>
              stx.tx match
                case b: com.chipprbots.ethereum.domain.BlobTransaction =>
                  val add = BigInt(b.blobVersionedHashes.size) * BlobGasUtils.GAS_PER_BLOB
                  if blobGas + add <= maxBlobGas then (kept :+ stx, blobGas + add)
                  else (kept, blobGas) // skip this blob tx, smaller ones later may still fit
                case _ =>
                  (kept :+ stx, blobGas)
            }
            ._1
        (pendingTxsForBlock, response.blobTxNetworkBytes)
      }

  /** The prefix-by-sender of `txs` that can actually execute on top of `parent`: per sender, only the unbroken run of
    * nonces starting at that sender's nonce in `parent`'s state. `txs` must already be ordered by (sender, nonce),
    * which [[selectMempoolTransactions]] guarantees.
    *
    * WHY THIS EXISTS. The pool can hold a transaction the chain has already included. Measured on hive `engine`
    * 13c1e5686, `Invalid Missing Ancestor Syncing ReOrg, GasLimit, EmptyTxs=False, CanonicalReOrg=True, Invalid P8`:
    * branch resolution re-adds the displaced canonical blocks' transactions to the pool before the reorg executes; the
    * pool validates them against whatever the best block is when the message lands, which was the partially-reorged
    * side tip (P7'), where canonical block 14's sender still had nonce 0; the forkchoice back to canonical 15 prunes
    * only block 15's transactions. Our next payload (block 17) then carried block 14's transaction again and every
    * client, us included, rejected it: `NONCE_MISMATCH_TOO_LOW: Got tx nonce 0 but sender in mpt is: 1`. The engine
    * build is LENIENT, so a failed transaction does not abort it — it yields a payload that can never validate.
    *
    * go-ethereum cannot get here: its pool resets on every head change and hands the miner only EXECUTABLE transactions
    * (nonce contiguous from the state nonce), and its miner additionally skips a nonce-too-low transaction rather than
    * including it. This applies the same executability rule at build time, against the parent's own state root, so it
    * holds however the pool came to be stale.
    *
    * Dropped: nonce below the parent-state nonce (already included, or replaced), a nonce gap (would fail with
    * NONCE_MISMATCH_TOO_HIGH), and an unrecoverable sender. A trie node missing at the parent root means we cannot
    * judge, so the list is returned unchanged — exactly the pre-existing behaviour.
    */
  def executableAtParent(parent: BlockHeader, txs: Seq[SignedTransaction]): Seq[SignedTransaction] =
    if txs.isEmpty then txs
    else
      try
        val nextNonce = scala.collection.mutable.Map.empty[Address, BigInt]
        val (kept, dropped) =
          txs.foldLeft((Vector.empty[SignedTransaction], Vector.empty[String])) { case ((keep, drop), stx) =>
            SignedTransaction.getSender(stx) match
              case None => (keep, drop :+ s"${stx.hash.toHex}: unrecoverable sender")
              case Some(sender) =>
                val expected = nextNonce.getOrElseUpdate(
                  sender,
                  blockchainReader
                    .getAccountAtStateRoot(parent.stateRoot.value, sender)
                    .map(_.nonce.toBigInt)
                    .getOrElse(blockchainConfig.accountStartNonce.toBigInt)
                )
                val nonce = stx.tx.nonce
                if nonce == expected then
                  nextNonce.update(sender, expected + 1)
                  (keep :+ stx, drop)
                else if nonce < expected then
                  (keep, drop :+ s"${stx.hash.toHex}: nonce $nonce below state nonce $expected")
                else (keep, drop :+ s"${stx.hash.toHex}: nonce $nonce leaves a gap after $expected")
          }
        if dropped.nonEmpty then
          log.info(
            "Proposer build on block {}: excluding {} non-executable pool transaction(s): {}",
            parent.number,
            dropped.size,
            dropped.mkString("; ")
          )
        kept
      catch
        case e: com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MPTException =>
          log.warn(
            "Proposer build on block {}: parent state unreadable ({}); not filtering pool transactions",
            parent.number,
            e.getMessage
          )
          txs

  /** The gasLimit a proposer puts in the child header.
    *
    * @param target
    *   `None` keeps the parent's gas limit (the engine API's historical policy — the CL does not tell us a target, so
    *   we do not drift). `Some(t)` converges toward `t` at go-ethereum's `CalcGasLimit` rate; the `testing_*` namespace
    *   uses this so fixtures generated against geth (which always has a `--miner.gaslimit`) match.
    *
    * Both branches respect the one-shot EIP-1559 elasticity scale at London/Olympia activation: producer and validator
    * must agree, and BlockHeaderValidatorSkeleton.validateGasLimit centres its +-1/1024 window on the scaled parent at
    * exactly that block.
    */
  def proposerGasLimit(parent: BlockHeader, childNumber: BigInt, target: Option[BigInt]): GasAmount =
    val olympiaActivation = blockchainConfig.forkBlockNumbers.olympiaBlockNumber
    val effectiveParent =
      blockchainConfig.forkBlockNumbers.olympiaGasLimitElasticity match
        case Some(multiplier) if parent.number.value < olympiaActivation && childNumber >= olympiaActivation =>
          // GasAmount has *(Long) and *(BigInt) but no *(Int).
          parent.gasLimit * BigInt(multiplier)
        case _ => parent.gasLimit
    target match
      case None => effectiveParent
      case Some(t) =>
        GasAmount(com.chipprbots.ethereum.consensus.blocks.GasLimitCalculator.calcGasLimit(effectiveParent.value, t))

  /** Fork name for the proposer build log line. */
  private def forkNameAt(ts: Timestamp): String =
    if blockchainConfig.isPragueTimestamp(ts) then "Prague"
    else if blockchainConfig.isCancunTimestamp(ts) then "Cancun"
    else if blockchainConfig.isShanghaiTimestamp(ts) then "Shanghai"
    else "Paris"

  /** EIP-4844 sidecars for `txs`, given the network-wrapped raw bytes captured for each blob tx. */
  def blobsBundleFor(txs: Seq[SignedTransaction], blobTxRawBytes: Map[ByteString, ByteString]): BlobsBundleData =
    buildBlobsBundle(txs, blobTxRawBytes)

  /** Build a block on top of `parent` from CL/test-supplied payload attributes and an explicit transaction list.
    *
    * Single proposer-side block builder for the whole client: `engine_forkchoiceUpdated`'s payload build and the
    * `testing_*` namespace both go through here, so a payload produced by one can never disagree with the other on
    * header field derivation (withdrawalsRoot, excessBlobGas, requestsHash, bloom, roots).
    *
    * Side-effect free with respect to the canonical chain: nothing is stored, no head moves. `executeForProposer` does
    * write trie nodes to the backing MPT storage (see BlockExecution.buildInitialWorld), but writes no block, receipt,
    * or number->hash mapping.
    *
    * @param transactions
    *   exactly the transactions to include, in order. Caller does the mempool selection.
    * @param gasLimit
    *   the child header's gasLimit. Caller's policy: the engine path keeps the parent's (modulo the one-shot EIP-1559
    *   elasticity scale at London activation), the testing path converges toward the configured target.
    * @param strict
    *   true -> a failed transaction aborts the build with Left (the `testing_*` namespace: execution-apis says an
    *   unapplicable transaction MUST be a JSON-RPC error). false -> `engine_forkchoiceUpdated`: never Left; a
    *   transaction that cannot be applied is left out, see [[buildLeniently]].
    */
  def buildBlockOnParent(
      parent: Block,
      attrs: PayloadAttributes,
      transactions: Seq[SignedTransaction],
      extraData: ByteString,
      gasLimit: GasAmount,
      strict: Boolean
  ): Either[String, BuiltBlock] =
    if strict then
      sealProposerBlock(parent, attrs, transactions, extraData, gasLimit)(executeProposerBlock).left.map { err =>
        log.error("Proposer-mode execution failed: {}", err)
        err.describe
      }
    else Right(buildLeniently(parent, attrs, transactions, extraData, gasLimit))

  /** Re-executions a lenient build spends dropping failed transactions one by one before it falls back to keeping only
    * the transactions that ran before the failure. Each attempt re-executes the whole list, so this bounds the cost.
    */
  private val LenientBuildRetries = 8

  private def executeProposerBlock(
      block: Block
  ): Either[com.chipprbots.ethereum.ledger.BlockExecutionError, ProposerExecution] =
    blockExecution
      .executeForProposer(block)
      .map(r => ProposerExecution(r.receipts, r.gasUsed, r.worldState.stateRootHash, r.executionRequests))

  /** `engine_forkchoiceUpdated`'s build: always a payload, and one that executes.
    *
    * A transaction the execution rejects (go-ethereum's `commitTransactions` default arm) is dropped, together with its
    * sender's later transactions (they cannot follow the nonce gap) and every later transaction whose gas limit no
    * longer fits what is left of the block; then the list is executed again. A failure that no transaction explains
    * (system call, deposit-log layout, missing state) yields the empty payload, as go-ethereum keeps its empty payload
    * when a full build fails.
    *
    * WHY. This used to keep EVERY transaction when execution failed and seal the block over the parent's state root
    * with no receipts: a payload that no client, this one included, can validate. hive `Blob Transactions On Block 1, *
    * (Cancun)` observed it as a 6-blob payload where an empty one was expected (the blob transaction could no longer
    * pay the blob base fee); on a live network it is a missed slot.
    *
    * Only when even the empty payload cannot be executed does it still fall back to that old shape — empty now — so
    * forkchoiceUpdated keeps answering with a payloadId.
    */
  private def buildLeniently(
      parent: Block,
      attrs: PayloadAttributes,
      transactions: Seq[SignedTransaction],
      extraData: ByteString,
      gasLimit: GasAmount
  ): BuiltBlock =
    import com.chipprbots.ethereum.ledger.BlockExecutionError.TxsExecutionError
    @scala.annotation.tailrec
    def attempt(txs: Seq[SignedTransaction], retriesLeft: Int): BuiltBlock =
      sealProposerBlock(parent, attrs, txs, extraData, gasLimit)(executeProposerBlock) match
        case Right(built) => built
        case Left(TxsExecutionError(failed, before, reason)) =>
          val failedAt = txs.indexWhere(_.hash == failed.hash)
          val remaining =
            if failedAt < 0 then Nil
            else if retriesLeft > 0 then
              val sender = SignedTransaction.getSender(failed)
              val gasLeft = gasLimit.value - before.acumGas
              val (ran, rest) = txs.splitAt(failedAt)
              ran ++ rest.drop(1).filterNot { stx =>
                // acumGas only grows, so a gas limit above what was left BEFORE the failed
                // transaction can never fit later on either (validateBlockHasEnoughGasLimitForTx).
                SignedTransaction.getSender(stx) == sender || stx.tx.gasLimit.value > gasLeft
              }
            else txs.take(failedAt)
          log.info(
            "Proposer build on block {}: leaving out transaction {} ({}); {} of {} transaction(s) remain",
            parent.header.number,
            failed.hash.toHex,
            reason,
            remaining.size,
            txs.size
          )
          attempt(remaining, retriesLeft - 1)
        case Left(other) if txs.nonEmpty =>
          log.warn(
            "Proposer build on block {}: execution failed for a reason no transaction explains ({}); building the " +
              "empty payload instead",
            parent.header.number,
            other.describe
          )
          attempt(Nil, retriesLeft)
        case Left(other) =>
          log.error(
            "Proposer build on block {}: even the empty payload failed to execute ({}); sealing it over the " +
              "parent's state root",
            parent.header.number,
            other.describe
          )
          sealProposerBlock(parent, attrs, Nil, extraData, gasLimit)(_ =>
            Right(ProposerExecution(Nil, BigInt(0), parent.header.stateRoot.value, Nil))
          ).fold(err => throw new IllegalStateException(s"sealing without execution failed: $err"), identity)
    attempt(transactions, LenientBuildRetries)

  /** What block execution hands the proposer's header derivation. */
  final private case class ProposerExecution(
      receipts: Seq[com.chipprbots.ethereum.domain.Receipt],
      gasUsed: BigInt,
      stateRoot: ByteString,
      executionRequests: Seq[ByteString]
  )

  /** Derive the proposer's header for exactly `transactions`, in order, executing the skeleton block with `execute`.
    * The single place every proposer-built header field comes from.
    */
  private def sealProposerBlock(
      parent: Block,
      attrs: PayloadAttributes,
      transactions: Seq[SignedTransaction],
      extraData: ByteString,
      gasLimit: GasAmount
  )(
      execute: Block => Either[com.chipprbots.ethereum.ledger.BlockExecutionError, ProposerExecution]
  ): Either[com.chipprbots.ethereum.ledger.BlockExecutionError, BuiltBlock] =
    // EIP-1559 base fee for the block we are about to propose.
    //
    // Delegates to the canonical calculator rather than carrying an inline copy. The copy
    // that used to live here had a `parent.number == 0 => parentBaseFee` special case that
    // neither BaseFeeCalculator nor go-ethereum's consensus/misc/eip1559.CalcBaseFee has. On
    // a chain whose genesis is already London (Sepolia, and every hive sim that sets
    // HIVE_FORK_LONDON=0) that made us propose block 1 with the genesis base fee instead of
    // the 1/8 decrease an empty genesis earns: 1,000,000,000 where the rule gives 875,000,000.
    // Every other client computes the latter, so our block 1 was unacceptable to them —
    // invisible only because no validation path recomputed it.
    //
    // The London-activation exemption the copy was reaching for is already in calcBaseFee,
    // keyed correctly on olympiaBlockNumber rather than on the parent being genesis. The copy
    // also floored the decrease at 0 instead of blockchainConfig.baseFeeFloor.
    val baseFee: BigInt = BaseFeeCalculator.calcBaseFee(parent.header, blockchainConfig)
    val emptyWithdrawalsRoot = ByteString(
      kec256(
        com.chipprbots.ethereum.rlp
          .encode(com.chipprbots.ethereum.rlp.RLPValue(Array.empty[Byte]))
      )
    )
    val emptyTrieRoot = ByteString(
      kec256(
        com.chipprbots.ethereum.rlp
          .encode(com.chipprbots.ethereum.rlp.RLPValue(Array.empty[Byte]))
      )
    )

    // Determine which fork is active at the proposed block's timestamp so we emit
    // the correct HeaderExtraFields variant and header fields.
    val attrTs = Timestamp(attrs.timestamp)
    val isShanghai = blockchainConfig.isShanghaiTimestamp(attrTs)
    val isCancun = blockchainConfig.isCancunTimestamp(attrTs)
    val isPrague = blockchainConfig.isPragueTimestamp(attrTs)
    val withdrawals: Seq[com.chipprbots.ethereum.domain.Withdrawal] =
      attrs.withdrawals.getOrElse(Nil)

    // Compute withdrawalsRoot from attrs (Shanghai+ payload attributes).
    val computedWithdrawalsRoot =
      if withdrawals.nonEmpty then computeWithdrawalsRoot(withdrawals)
      else emptyWithdrawalsRoot

    // EIP-4844 / EIP-7691 / EIP-7892 / EIP-7918 excessBlobGas from parent.
    val parentExcessBlobGas = parent.header.excessBlobGas.getOrElse(BigInt(0))
    val parentBlobGasUsed = parent.header.blobGasUsed.getOrElse(BigInt(0))
    val parentBlobBaseFee = parent.header.baseFee.getOrElse(BigInt(0))
    val childExcessBlobGas = BlobGasUtils.expectedExcessBlobGas(
      parentExcessBlobGas,
      parentBlobGasUsed,
      parentBlobBaseFee,
      attrTs,
      blockchainConfig
    )

    val parentBeaconBlockRoot =
      attrs.parentBeaconBlockRoot.getOrElse(ByteString(new Array[Byte](32)))

    // Placeholder extraFields — stateRoot / requestsHash / blobGasUsed are filled in
    // AFTER executing the block (we can't know them yet).
    val initialExtraFields =
      if isPrague then
        HefPostPrague(
          baseFee,
          computedWithdrawalsRoot,
          BigInt(0),
          childExcessBlobGas,
          parentBeaconBlockRoot,
          ByteString.empty
        )
      else if isCancun then
        HefPostCancun(
          baseFee,
          computedWithdrawalsRoot,
          BigInt(0),
          childExcessBlobGas,
          parentBeaconBlockRoot
        )
      else if isShanghai then HefPostShanghai(baseFee, computedWithdrawalsRoot)
      else
        // Paris (post-merge, pre-Shanghai): HefPostOlympia holds only baseFee.
        // Using HefPostShanghai here breaks the blockHash round-trip: getPayloadV1
        // returns a payload with no withdrawals field, and newPayloadV1 reconstructs
        // the header as HefPostOlympia — different RLP, different hash, so every
        // Paris payload we build fails its own newPayload round-trip.
        HefPostOlympia(baseFee)

    // Build post-merge header with skeleton (difficulty=0 so payBlockReward skips PoW rewards)
    val blockNumber = parent.header.number + 1
    val header = BlockHeader(
      parentHash = parent.header.hash,
      ommersHash = BlockHash(
        ByteString(
          kec256(com.chipprbots.ethereum.rlp.encode(com.chipprbots.ethereum.rlp.RLPList()))
        )
      ),
      beneficiary = attrs.suggestedFeeRecipient.bytes,
      stateRoot = TrieRoot.Empty,
      transactionsRoot = TrieRoot(emptyTrieRoot),
      receiptsRoot = TrieRoot(emptyTrieRoot),
      logsBloom = BloomFilter.Empty,
      difficulty = Difficulty.Zero,
      number = blockNumber,
      gasLimit = gasLimit,
      gasUsed = GasAmount.Zero,
      unixTimestamp = Timestamp(attrs.timestamp),
      extraData = extraData,
      mixHash = BlockHash(attrs.prevRandao),
      nonce = ByteString(new Array[Byte](8)),
      extraFields = initialExtraFields
    )
    val body = BlockBody(transactions.toList, Nil, withdrawals = attrs.withdrawals)
    val skeletonBlock = Block(header, body)

    // Route EVERY post-merge proposer build through executeForProposer (which
    // goes through BlockExecution.executeBlock — txs, payBlockReward, withdrawals
    // via processWithdrawals, Prague system calls, then persistState).
    // The previous `if (isPrague) …  else BlockPreparator.prepareBlock` branch
    // was broken for Shanghai/Cancun: BlockPreparator.prepareBlock does NOT call
    // processWithdrawals, so the proposer-built header contained a stateRoot that
    // did not reflect the withdrawals — every withdrawals hive test came back with
    // "Block has invalid state root hash" on its own payload round-trip.
    // executeBlock early-returns cleanly on pre-Prague (processPragueSystemCalls
    // is a no-op outside Prague), so there's nothing to lose by using it always.
    import com.chipprbots.ethereum.consensus.validators.std.MptListValidator.intByteArraySerializable
    import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
    import com.chipprbots.ethereum.domain.Receipt
    execute(skeletonBlock).map { executed =>
      val ProposerExecution(receipts, gasUsedTotal, finalStateRoot, executionRequests) = executed

      val receiptsLogs =
        BloomFilter.Empty.toArray +: receipts.map(_.logsBloomFilter.toArray)
      val bloomFilter = ByteString(com.chipprbots.ethereum.utils.ByteUtils.or(receiptsLogs*))
      def buildMpt[T](
          items: Seq[T],
          ser: com.chipprbots.ethereum.mpt.ByteArraySerializable[T]
      ): ByteString =
        val storage = new com.chipprbots.ethereum.db.storage.SerializingMptStorage(
          new com.chipprbots.ethereum.db.storage.ArchiveNodeStorage(
            new com.chipprbots.ethereum.db.storage.NodeStorage(
              com.chipprbots.ethereum.db.dataSource.EphemDataSource()
            )
          )
        )
        val trie = items.zipWithIndex.foldLeft(
          MerklePatriciaTrie[Int, T](storage)(intByteArraySerializable, ser)
        ) { case (t, (item, idx)) =>
          t.put(idx, item)
        }
        ByteString(trie.getRootHash)

      // Blob-gas accounting: sum GAS_PER_BLOB * blob_count across blob txs.
      val blobGasUsed: BigInt = skeletonBlock.body.transactionList.map {
        case SignedTransaction(blobTx: com.chipprbots.ethereum.domain.BlobTransaction, _) =>
          BigInt(blobTx.blobVersionedHashes.size) * BlobGasUtils.GAS_PER_BLOB
        case _ => BigInt(0)
      }.sum

      // Finalize extraFields with execution-derived values.
      val finalExtraFields = initialExtraFields match
        case _: HefPostPrague =>
          HefPostPrague(
            baseFee,
            computedWithdrawalsRoot,
            blobGasUsed,
            childExcessBlobGas,
            parentBeaconBlockRoot,
            computeRequestsHash(executionRequests)
          )
        case _: HefPostCancun =>
          HefPostCancun(
            baseFee,
            computedWithdrawalsRoot,
            blobGasUsed,
            childExcessBlobGas,
            parentBeaconBlockRoot
          )
        case other => other

      val updatedHeader = header.copy(
        stateRoot = TrieRoot(finalStateRoot),
        receiptsRoot = TrieRoot(buildMpt(receipts, Receipt.byteArraySerializable)),
        transactionsRoot = TrieRoot(
          buildMpt(skeletonBlock.body.transactionList, SignedTransaction.byteArraySerializable)
        ),
        logsBloom = BloomFilter(bloomFilter),
        gasUsed = GasAmount(gasUsedTotal),
        extraFields = finalExtraFields
      )
      BuiltBlock(skeletonBlock.copy(header = updatedHeader), receipts, executionRequests)
    }

  /** engine_getPayloadV1/V2/V3/V4 — Return a previously built payload by ID. */
  def getPayload(payloadId: ByteString): IO[Either[String, Block]] = IO {
    // Do NOT remove: the engine-api spec allows the CL to call getPayload multiple times for
    // the same id (e.g. first getPayloadV1 then getPayloadV2 for the same payload, as the
    // hive engine-withdrawals "Withdrawals Fork on Block N" tests do). Removing on the first
    // read makes any follow-up call fail with "Payload not available".
    //
    // TTL eviction: if the entry is older than PayloadTtlNs we treat it as gone. The
    // removePayloadEntry call also cleans up pendingPayloadRequests entries that V1/V2/V3
    // paths would otherwise orphan (they never call getPayloadExecutionRequests which does
    // the only explicit .remove of that map).
    Option(pendingPayloads.get(payloadId)) match
      case Some(block) =>
        val age = Option(pendingPayloadTimestamps.get(payloadId)).map(System.nanoTime() - _).getOrElse(0L)
        if age > PayloadTtlNs then
          removePayloadEntry(payloadId)
          Left("Payload not available")
        else Right(block)
      case None => Left("Payload not available")
  }

  /** Return the EIP-7685 executionRequests (typed byte strings, type-prefixed) associated with a payload we built. Only
    * non-empty for Prague+ blocks. Used by engine_getPayloadV4 to return the requests alongside the executionPayload
    * envelope.
    */
  def getPayloadExecutionRequests(payloadId: ByteString): Seq[ByteString] =
    Option(pendingPayloadRequests.remove(payloadId)).getOrElse(Nil)

  /** Receipts produced while building this payload. Used by engine_getPayloadV2+ to compute the `blockValue` envelope
    * field. `get` (not `remove`) because the CL may call getPayloadV1 and then getPayloadV2 for the same id (hive
    * withdrawals tests rely on this).
    */
  def getPayloadReceipts(payloadId: ByteString): Seq[com.chipprbots.ethereum.domain.Receipt] =
    Option(pendingPayloadReceipts.get(payloadId)).getOrElse(Nil)

  /** EIP-4844 sidecars for the blob txs included in this payload, for engine_getPayloadV3's blobsBundle envelope. Empty
    * when the payload has no blob txs.
    */
  def getPayloadBlobsBundle(payloadId: ByteString): BlobsBundleData =
    Option(pendingPayloadBlobsBundle.get(payloadId)).getOrElse(BlobsBundleData(Nil, Nil, Nil, Nil))

  /** Parse the EIP-4844 network-wrapped raw bytes (`0x03 || rlp([tx_payload, blobs, commitments, proofs])`) the pool
    * captured for each blob tx, and return the concatenated sidecars for every blob tx actually included in the built
    * payload, in payload order.
    */
  private def buildBlobsBundle(
      txs: Seq[SignedTransaction],
      blobTxRawBytes: Map[ByteString, ByteString]
  ): BlobsBundleData =
    import com.chipprbots.ethereum.rlp.{rawDecode, RLPList, RLPValue}
    import com.chipprbots.ethereum.crypto.KzgCellProofs
    val blobTxHashes = txs.collect {
      case stx @ SignedTransaction(_: com.chipprbots.ethereum.domain.BlobTransaction, _) => stx.hash
    }
    val allBlobs = Seq.newBuilder[ByteString]
    val allCommitments = Seq.newBuilder[ByteString]
    val allProofs = Seq.newBuilder[ByteString]
    val allCellProofsPerBlob = Seq.newBuilder[Seq[ByteString]]
    blobTxHashes.foreach { h =>
      blobTxRawBytes.get(h.value) match
        case Some(raw) if raw.length > 1 && raw(0) == 0x03 =>
          try
            rawDecode(raw.toArray.drop(1)) match
              case RLPList(_, blobs: RLPList, commitments: RLPList, proofs: RLPList) =>
                blobs.items.foreach {
                  case RLPValue(b) =>
                    allBlobs += ByteString(b)
                    val cellProofs: Seq[ByteString] =
                      try
                        val (_, perCellProofs) = KzgCellProofs.computeCellsAndKzgProofs(b)
                        perCellProofs.toSeq.map(ByteString(_))
                      catch
                        case e: Exception =>
                          log.warn(
                            "EIP-7594 cell-proof computation failed for blob in tx {}: {}",
                            h.value.toArray.map("%02x".format(_)).mkString,
                            e.getMessage
                          )
                          Seq.empty
                    allCellProofsPerBlob += cellProofs
                  case _ =>
                }
                commitments.items.foreach { case RLPValue(c) => allCommitments += ByteString(c); case _ => }
                proofs.items.foreach { case RLPValue(p) => allProofs += ByteString(p); case _ => }
              case _ =>
                log.warn(
                  "Blob tx {} sidecar RLP shape unexpected; skipping",
                  h.value.toArray.map("%02x".format(_)).mkString
                )
          catch
            case e: Exception =>
              log.warn(
                "Failed to decode blob tx {} sidecar: {}",
                h.value.toArray.map("%02x".format(_)).mkString,
                e.getMessage
              )
        case _ => // tx from network / historical — we didn't store a sidecar
    }
    BlobsBundleData(allBlobs.result(), allCommitments.result(), allProofs.result(), allCellProofsPerBlob.result())

  /** engine_exchangeCapabilities — return supported Engine API methods. */
  def exchangeCapabilities(clCapabilities: Seq[String]): IO[Seq[String]] = IO {
    val supported = Seq(
      "engine_newPayloadV1",
      "engine_newPayloadV2",
      "engine_newPayloadV3",
      "engine_newPayloadV4",
      "engine_forkchoiceUpdatedV1",
      "engine_forkchoiceUpdatedV2",
      "engine_forkchoiceUpdatedV3",
      "engine_getPayloadV1",
      "engine_getPayloadV2",
      "engine_getPayloadV3",
      "engine_getPayloadV4",
      "engine_getPayloadV5",
      "engine_getBlobsV1",
      "engine_getBlobsV2",
      "engine_getPayloadBodiesByHashV1",
      "engine_getPayloadBodiesByRangeV1",
      "engine_getClientVersionV1",
      "engine_exchangeCapabilities"
    )
    log.info("exchangeCapabilities: clMethods={} supported={}", clCapabilities.size, supported.size)
    supported
  }

  /** Walks back from `descendant`'s header chain up to 8192 blocks checking whether `ancestor` appears. Zero hash is
    * treated as "not present" and short-circuits to true (ancestor disabled). Used by forkchoiceUpdated to verify
    * safe/finalized are ancestors of head per Engine API spec §5.4.
    */
  private def isAncestorOrEqual(ancestor: ByteString, descendant: ByteString, zeroHash: ByteString): Boolean =
    if ancestor == zeroHash then true
    else if ancestor == descendant then true
    else
      var cursor: ByteString = descendant
      var steps = 0
      val maxWalk = 8192
      var found = false
      while !found && steps < maxWalk && cursor != zeroHash do
        blockchainReader.getBlockHeaderByHash(BlockHash(cursor)) match
          case Some(h) =>
            if h.parentHash.value == ancestor then found = true
            else
              cursor = h.parentHash.value; steps += 1
          case None =>
            // Missing ancestor data — assume not present rather than loop forever
            cursor = zeroHash
      found

  /** engine_getPayloadBodiesByHashV1: look up a block body by hash. Returns (rawTransactions, encodedWithdrawals) or
    * None if not found.
    */
  def getPayloadBodyByHash(hash: ByteString): Option[(Seq[ByteString], Option[Seq[org.json4s.JValue]])] =
    blockchainReader.getBlockBodyByHash(BlockHash(hash)).map(bodyToPayloadBody)

  /** engine_getPayloadBodiesByRangeV1: look up a block body by number. */
  def getPayloadBodyByNumber(number: BigInt): Option[(Seq[ByteString], Option[Seq[org.json4s.JValue]])] =
    blockchainReader.getBlockHeaderByNumber(number).flatMap { header =>
      blockchainReader.getBlockBodyByHash(header.hash).map(bodyToPayloadBody)
    }

  private def bodyToPayloadBody(body: BlockBody): (Seq[ByteString], Option[Seq[org.json4s.JValue]]) =
    val rawTxs = body.transactionList.map { stx =>
      ByteString(rlpEncode(SignedTransactionEnc(stx).toRLPEncodable))
    }
    val withdrawals = body.withdrawals.map { ws =>
      ws.map { w =>
        import org.json4s.JValue
        org.json4s.JObject(
          "index" -> org.json4s.JString(s"0x${w.index.toString(16)}"),
          "validatorIndex" -> org.json4s.JString(s"0x${w.validatorIndex.toString(16)}"),
          "address" -> org.json4s.JString(s"0x${w.address.bytes.map("%02x".format(_)).mkString}"),
          "amount" -> org.json4s.JString(s"0x${w.amount.toString(16)}")
        ): JValue
      }
    }
    (rawTxs, withdrawals)

  /** Convert an ExecutionPayload into a Block. */
  private def payloadToBlock(payload: ExecutionPayload): Block =
    // Decode transactions from raw bytes
    val signedTxs = payload.transactions.map { txBytes =>
      txBytes.toArray.toSignedTransaction
    }

    // Determine header extra fields based on which optional payload fields are present
    val withdrawalsRoot = computeWithdrawalsRoot(payload.withdrawals.getOrElse(Seq.empty))
    val pbbr = payload.parentBeaconBlockRoot.getOrElse(ByteString(new Array[Byte](32)))

    val extraFields =
      (payload.executionRequests, payload.blobGasUsed, payload.excessBlobGas, payload.withdrawals) match
        case (Some(requests), Some(bgu), Some(ebg), _) =>
          // Prague/Electra: has executionRequests → HefPostPrague with requestsHash
          HefPostPrague(
            baseFee = payload.baseFeePerGas,
            withdrawalsRoot = withdrawalsRoot,
            blobGasUsed = bgu,
            excessBlobGas = ebg,
            parentBeaconBlockRoot = pbbr,
            requestsHash = computeRequestsHash(requests)
          )
        case (None, Some(bgu), Some(ebg), _) =>
          // Cancun: has blob gas fields
          HefPostCancun(
            baseFee = payload.baseFeePerGas,
            withdrawalsRoot = withdrawalsRoot,
            blobGasUsed = bgu,
            excessBlobGas = ebg,
            parentBeaconBlockRoot = pbbr
          )
        case (_, _, _, Some(_)) =>
          HefPostShanghai(
            baseFee = payload.baseFeePerGas,
            withdrawalsRoot = withdrawalsRoot
          )
        case _ =>
          HefPostOlympia(baseFee = payload.baseFeePerGas)

    val header = BlockHeader(
      parentHash = BlockHash(payload.parentHash),
      ommersHash = BlockHash(BlockHeader.EmptyOmmers),
      beneficiary = payload.feeRecipient.bytes,
      stateRoot = TrieRoot(payload.stateRoot),
      transactionsRoot = TrieRoot(computeTransactionsRoot(signedTxs)),
      receiptsRoot = TrieRoot(payload.receiptsRoot),
      logsBloom = BloomFilter(payload.logsBloom),
      difficulty = Difficulty.Zero,
      number = BlockNumber(payload.blockNumber),
      gasLimit = GasAmount(payload.gasLimit),
      gasUsed = GasAmount(payload.gasUsed),
      unixTimestamp = Timestamp(payload.timestamp),
      extraData = payload.extraData,
      mixHash = BlockHash(payload.prevRandao),
      nonce = ByteString(new Array[Byte](8)),
      extraFields = extraFields
    )

    val body = BlockBody(
      transactionList = signedTxs,
      uncleNodesList = Seq.empty,
      withdrawals = payload.withdrawals
    )

    Block(header, body)

  /** Compute requestsHash per EIP-7685: sha256(sha256(request_0) ++ sha256(request_1) ++ ...)
    */
  private def computeRequestsHash(requests: Seq[ByteString]): ByteString =
    val outerDigest = MessageDigest.getInstance("SHA-256")
    requests.foreach { request =>
      if request.length > 1 then
        val innerDigest = MessageDigest.getInstance("SHA-256")
        innerDigest.update(request.toArray)
        outerDigest.update(innerDigest.digest())
    }
    ByteString(outerDigest.digest())

  /** Compute the withdrawals trie root via ephemeral MPT (same approach as StdBlockValidator). */
  private def computeWithdrawalsRoot(withdrawals: Seq[Withdrawal]): ByteString =
    if withdrawals.isEmpty then BlockHeader.EmptyMpt
    else
      val serializable = new ByteArraySerializable[Withdrawal]:
        override def fromBytes(bytes: Array[Byte]): Withdrawal = bytes.toWithdrawal
        override def toBytes(input: Withdrawal): Array[Byte] = rlpEncode(WithdrawalEnc(input).toRLPEncodable)
      val stateStorage = com.chipprbots.ethereum.db.storage.StateStorage.getReadOnlyStorage(
        com.chipprbots.ethereum.db.dataSource.EphemDataSource()
      )
      val trie = com.chipprbots.ethereum.mpt.MerklePatriciaTrie[Int, Withdrawal](
        source = stateStorage
      )(MptListValidator.intByteArraySerializable, serializable)
      val root = withdrawals.zipWithIndex.foldLeft(trie)((t, r) => t.put(r._2, r._1)).getRootHash
      ByteString(root)

  /** Compute the transactions trie root via ephemeral MPT (same approach as StdBlockValidator). */
  private def computeTransactionsRoot(txs: Seq[SignedTransaction]): ByteString =
    if txs.isEmpty then BlockHeader.EmptyMpt
    else
      val stateStorage = com.chipprbots.ethereum.db.storage.StateStorage.getReadOnlyStorage(
        com.chipprbots.ethereum.db.dataSource.EphemDataSource()
      )
      val trie = com.chipprbots.ethereum.mpt.MerklePatriciaTrie[Int, SignedTransaction](
        source = stateStorage
      )(MptListValidator.intByteArraySerializable, SignedTransaction.byteArraySerializable)
      val root = txs.zipWithIndex.foldLeft(trie)((t, r) => t.put(r._2, r._1)).getRootHash
      ByteString(root)

/** EIP-4844 / EIP-7691 / EIP-7892 blob gas computation utilities.
  *
  * Per-fork blob targets and maxes (EIP-7892 Blob Parameter Only forks):
  *   - Cancun (EIP-4844): target=3, max=6 blobs
  *   - Prague (EIP-7691): target=6, max=9 blobs
  *   - Osaka: target=6, max=9 blobs (no blob change; inherits Prague)
  *   - BPO1 (EIP-7892): target=8, max=12 blobs
  *   - BPO2 (EIP-7892): target=12, max=18 blobs
  *
  * Sepolia BPO schedule (per geth `params.SepoliaChainConfig.BlobScheduleConfig`):
  *   - osaka: 2025-10-14 11:36
  *   - bpo1: 2025-10-21 06:46 (target=8, max=12)
  *   - bpo2: 2025-10-28 02:36 (target=12, max=18)
  *
  * Use the fork-aware `targetBlobGasPerBlock(timestamp, config)` / `maxBlobGasPerBlock(timestamp, config)` for any
  * blob-gas validation; the static `*_TARGET_BLOB_GAS` / `*_MAX_BLOB_GAS` values are kept only as the ladder rungs.
  */
object BlobGasUtils:
  val GAS_PER_BLOB: BigInt = BigInt(131072)

  // Cancun (EIP-4844): 3 target, 6 max
  val CANCUN_TARGET_BLOB_GAS: BigInt = BigInt(393216) // 3 * 131072
  val CANCUN_MAX_BLOB_GAS: BigInt = BigInt(786432) // 6 * 131072

  // Prague (EIP-7691): 6 target, 9 max
  val PRAGUE_TARGET_BLOB_GAS: BigInt = BigInt(786432) // 6 * 131072
  val PRAGUE_MAX_BLOB_GAS: BigInt = BigInt(1179648) // 9 * 131072

  // EIP-7892 BPO1: 10 target, 15 max (Sepolia 2025-10-21).
  // Empirically derived from Sepolia canonical chain (block pair 0x90FFFF → 0x910000):
  //   parent: excess=655360 used=917504 (sum=1572864), child: excess=262144
  //   ⇒ target = 1572864 - 262144 = 1310720 = 10 * 131072
  // Max bound observed = 15 blobs (block 0x910002 used=1966080).
  val BPO1_TARGET_BLOB_GAS: BigInt = BigInt(1310720) // 10 * 131072
  val BPO1_MAX_BLOB_GAS: BigInt = BigInt(1966080) // 15 * 131072

  // EIP-7892 BPO2: 14 target, 21 max (Sepolia 2025-10-28).
  // Empirically derived from Sepolia canonical chain (block pair 10817144 → 10817145):
  //   parent: excess=8912666 used=1310720 (sum=10223386), child: excess=8388378
  //   ⇒ target = 10223386 - 8388378 = 1835008 = 14 * 131072
  // Max bound = target * 1.5 = 21 blobs (matches Prague's 1.5x ratio).
  val BPO2_TARGET_BLOB_GAS: BigInt = BigInt(1835008) // 14 * 131072
  val BPO2_MAX_BLOB_GAS: BigInt = BigInt(2752512) // 21 * 131072

  // Default (Cancun) values
  val TARGET_BLOB_GAS_PER_BLOCK: BigInt = CANCUN_TARGET_BLOB_GAS
  val BLOB_BASE_FEE_UPDATE_FRACTION: BigInt = BigInt(3338477)
  // EIP-7691 (Prague): BLOB_BASE_FEE_UPDATE_FRACTION bumped to scale with 9-blob MAX.
  val PRAGUE_BLOB_BASE_FEE_UPDATE_FRACTION: BigInt = BigInt(5007716)
  // EIP-7892 BPO1/BPO2 update fractions (geth `params.config.go`
  // `DefaultBPO{1,2}BlobConfig.UpdateFraction`). These scale with each fork's MAX so the
  // EIP-7918 reserve-price comparison and the `getBlobGasPrice` exponential give the
  // right answer post-Osaka. Without them, fukuii uses Prague's smaller fraction →
  // higher computed `blobPrice` → reservePrice ≤ blobPrice almost always → EIP-7918
  // alternate branch never triggers and we fall back to EIP-4844, mismatching geth.
  val BPO1_BLOB_BASE_FEE_UPDATE_FRACTION: BigInt = BigInt(8346193)
  val BPO2_BLOB_BASE_FEE_UPDATE_FRACTION: BigInt = BigInt(11684671)
  val MIN_BLOB_BASE_FEE: BigInt = BigInt(1)

  // EIP-7918: BLOB_BASE_COST = 2^13 — the per-blob execution gas cost used in the
  // reserve-price comparison. When `BLOB_BASE_COST * parent.baseFee > blobPrice(parent)`,
  // the alternate excess formula kicks in.
  val BLOB_BASE_COST: BigInt = BigInt(1) << 13

  /** Calculate excess blob gas for a block from its parent's excess and used blob gas. Per EIP-4844: if parent_excess +
    * parent_used < target then 0 else parent_excess + parent_used - target
    *
    * For post-Osaka EIP-7918 behaviour, use `calcExcessBlobGasOsaka` which adds the reserve-price-aware alternate
    * formula.
    */
  def calcExcessBlobGas(
      parentExcessBlobGas: BigInt,
      parentBlobGasUsed: BigInt,
      target: BigInt = TARGET_BLOB_GAS_PER_BLOCK
  ): BigInt =
    val total = parentExcessBlobGas + parentBlobGasUsed
    if total < target then BigInt(0)
    else total - target

  /** EIP-7918 (Osaka) excess-blob-gas calculation. Geth reference: `eip4844.calcExcessBlobGas` — post-Osaka, when
    * `reservePrice > blobPrice`, the protocol uses an alternate formula that preserves a fraction of
    * `parent.blobGasUsed` instead of subtracting `target`. This prevents the blob fee from collapsing in
    * low-utilisation regimes.
    *
    * Formula:
    * {{{
    *   total = parent.excess + parent.used
    *   if (total < target) return 0
    *   if (isOsaka && reservePrice > blobPrice(parent.excess)) {
    *     scaled = parent.used * (max - target) / max         // both in BLOB units
    *     return parent.excess + scaled                       // in GAS units
    *   }
    *   return total - target
    * }}}
    *
    * `reservePrice = BLOB_BASE_COST * parent.baseFee` (in wei) `blobPrice = fakeExponential(1, parent.excess,
    * updateFraction) * GAS_PER_BLOB` (in wei)
    */
  def calcExcessBlobGasOsaka(
      parentExcessBlobGas: BigInt,
      parentBlobGasUsed: BigInt,
      parentBaseFee: BigInt,
      targetGas: BigInt,
      maxGas: BigInt,
      updateFraction: BigInt
  ): BigInt =
    val total = parentExcessBlobGas + parentBlobGasUsed
    if total < targetGas then return BigInt(0)
    val reservePrice = BLOB_BASE_COST * parentBaseFee
    val blobBaseFee = fakeExponential(MIN_BLOB_BASE_FEE, parentExcessBlobGas, updateFraction)
    val blobPrice = blobBaseFee * GAS_PER_BLOB
    if reservePrice > blobPrice then
      // Both `targetGas` and `maxGas` are gas units; the blob-count ratio (Max-Target)/Max
      // is the same regardless of unit, so we can compute against gas directly. Integer
      // division truncates toward zero (same as geth's uint64 arithmetic).
      val scaled = parentBlobGasUsed * (maxGas - targetGas) / maxGas
      parentExcessBlobGas + scaled
    else total - targetGas

  /** Calculate the blob gas price using the fake exponential function. Per EIP-4844:
    * fake_exponential(MIN_BLOB_BASE_FEE, excess_blob_gas, BLOB_BASE_FEE_UPDATE_FRACTION)
    */
  def getBlobGasPrice(excessBlobGas: BigInt): BigInt =
    fakeExponential(MIN_BLOB_BASE_FEE, excessBlobGas, BLOB_BASE_FEE_UPDATE_FRACTION)

  /** Fork-aware blob gas price. Prague (EIP-7691) raises the update fraction. */
  def getBlobGasPrice(
      excessBlobGas: BigInt,
      blockTimestamp: Timestamp,
      blockchainConfig: com.chipprbots.ethereum.utils.BlockchainConfig
  ): BigInt =
    val fraction = updateFractionFor(blockTimestamp, blockchainConfig)
    fakeExponential(MIN_BLOB_BASE_FEE, excessBlobGas, fraction)

  /** Fork-aware blob-base-fee update fraction. Each BPO bumps the fraction proportional to its MAX, matching geth's
    * `params.config.go` `Default{Cancun,Prague,Osaka,BPO1,BPO2}BlobConfig.UpdateFraction`. Osaka itself reuses the
    * Prague value (Osaka's blob params are unchanged from Prague).
    */
  def updateFractionFor(
      blockTimestamp: Timestamp,
      blockchainConfig: com.chipprbots.ethereum.utils.BlockchainConfig
  ): BigInt =
    if blockchainConfig.isBpo2Timestamp(blockTimestamp) then BPO2_BLOB_BASE_FEE_UPDATE_FRACTION
    else if blockchainConfig.isBpo1Timestamp(blockTimestamp) then BPO1_BLOB_BASE_FEE_UPDATE_FRACTION
    else if blockchainConfig.isPragueTimestamp(blockTimestamp) then PRAGUE_BLOB_BASE_FEE_UPDATE_FRACTION
    else BLOB_BASE_FEE_UPDATE_FRACTION

  /** Compute the expected `excessBlobGas` of a child block given its parent and timestamp. Routes through the EIP-7918
    * alternate formula post-Osaka. This is the single canonical entry point for Engine-API payload validation and
    * BlockHeaderValidator.
    *
    * `parentBaseFee` is required post-Osaka (used in the reserve-price comparison). Pass `BigInt(0)` for pre-Osaka
    * chains; it's ignored.
    */
  def expectedExcessBlobGas(
      parentExcessBlobGas: BigInt,
      parentBlobGasUsed: BigInt,
      parentBaseFee: BigInt,
      childTimestamp: Timestamp,
      blockchainConfig: com.chipprbots.ethereum.utils.BlockchainConfig
  ): BigInt =
    val target = targetBlobGasPerBlock(childTimestamp, blockchainConfig)
    if blockchainConfig.isOsakaTimestamp(childTimestamp) then
      val max = maxBlobGasPerBlock(childTimestamp, blockchainConfig)
      val fraction = updateFractionFor(childTimestamp, blockchainConfig)
      calcExcessBlobGasOsaka(parentExcessBlobGas, parentBlobGasUsed, parentBaseFee, target, max, fraction)
    else calcExcessBlobGas(parentExcessBlobGas, parentBlobGasUsed, target)

  /** Fork-aware MAX_BLOB_GAS_PER_BLOCK. EIP-7892 BPOs raise the cap on Sepolia/mainnet post-Osaka; the latest active
    * BPO wins.
    */
  def maxBlobGasPerBlock(
      blockTimestamp: Timestamp,
      blockchainConfig: com.chipprbots.ethereum.utils.BlockchainConfig
  ): BigInt =
    if blockchainConfig.isBpo2Timestamp(blockTimestamp) then BPO2_MAX_BLOB_GAS
    else if blockchainConfig.isBpo1Timestamp(blockTimestamp) then BPO1_MAX_BLOB_GAS
    else if blockchainConfig.isPragueTimestamp(blockTimestamp) then PRAGUE_MAX_BLOB_GAS
    else CANCUN_MAX_BLOB_GAS

  /** Fork-aware TARGET_BLOB_GAS_PER_BLOCK used by `calcExcessBlobGas` and Engine API payload validation. Without BPO
    * awareness, post-Osaka Sepolia blocks fail with `INCORRECT_EXCESS_BLOB_GAS` because we'd subtract the Prague target
    * (6 blobs) instead of the active BPO target (8 or 12 blobs).
    */
  def targetBlobGasPerBlock(
      blockTimestamp: Timestamp,
      blockchainConfig: com.chipprbots.ethereum.utils.BlockchainConfig
  ): BigInt =
    if blockchainConfig.isBpo2Timestamp(blockTimestamp) then BPO2_TARGET_BLOB_GAS
    else if blockchainConfig.isBpo1Timestamp(blockTimestamp) then BPO1_TARGET_BLOB_GAS
    else if blockchainConfig.isPragueTimestamp(blockTimestamp) then PRAGUE_TARGET_BLOB_GAS
    else CANCUN_TARGET_BLOB_GAS

  /** EIP-7918: Osaka blob base fee floored by execution gas cost. Prevents blob base fee from decoupling from execution
    * fee market. Formula (Osaka spec): blob_base_fee = max(current_blob_fee, (blob_base_fee_update_fraction *
    * block_base_fee) / (gas_per_blob * MAX_BLOBS_PER_BLOCK)) Simplified conservative floor: max(current, block_base_fee
    * / CEILING_RATIO).
    */
  def getBlobGasPriceOsaka(excessBlobGas: BigInt, blockBaseFee: BigInt): BigInt =
    val base = fakeExponential(MIN_BLOB_BASE_FEE, excessBlobGas, BLOB_BASE_FEE_UPDATE_FRACTION)
    val floor = blockBaseFee / BigInt(8) // conservative: blob_fee ≥ base_fee / 8
    base.max(floor).max(MIN_BLOB_BASE_FEE)

  /** fake_exponential: approximates factor * e^(numerator / denominator) using Taylor expansion. Per EIP-4844 spec.
    */
  private def fakeExponential(factor: BigInt, numerator: BigInt, denominator: BigInt): BigInt =
    var i = BigInt(1)
    var output = BigInt(0)
    var numeratorAccum = factor * denominator
    while numeratorAccum > 0 do
      output += numeratorAccum
      numeratorAccum = (numeratorAccum * numerator) / (denominator * i)
      i += 1
    output / denominator
