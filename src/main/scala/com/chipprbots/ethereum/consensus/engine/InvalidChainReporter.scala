package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.util.ByteString

import java.util.concurrent.atomic.AtomicReference

import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.ledger.BlockExecutionError

/** One-way channel from the p2p block-import path into the Engine API's invalid-block registry.
  *
  * WHY THIS EXISTS. `EngineApiService` keeps a private `invalidBlocks` map (blockHash -> latestValidHash) and that map
  * is the ONLY thing that can make `engine_newPayload` / `engine_forkchoiceUpdated` answer INVALID
  * (EngineApiService.scala, the `invalidBlocks.containsKey` guards in `newPayload` and `forkchoiceUpdated`). Before
  * this trait, nothing outside `EngineApiService` could write to it. A consensus-invalid block that arrives over p2p is
  * therefore rejected correctly by `BlockImporter`, but the rejection never becomes an INVALID answer to the consensus
  * layer: hive's engine suite polls `newPayload` 41-58 times per test and receives ACCEPTED/SYNCING every single time
  * (0 INVALID out of 4,940 status replies measured on fc713a9), then times out.
  *
  * DELIBERATELY NARROW. This is not an event bus. It carries one fact, in one direction, and has exactly one
  * implementation in production (`EngineApiService.invalidChainReporter`, which delegates to the private
  * `markInvalidRecursive`). `invalidBlocks` stays private. Do not add methods here to move unrelated import-path events
  * into the engine; add a separate channel or, better, do not.
  *
  * ETC/Mordor/Gorgoroth. Nothing ever binds a reporter on those networks — see [[InvalidChainReporter.LateBound]] — so
  * every call here is a no-op and the ETC import path is byte-for-byte unchanged.
  */
trait InvalidChainReporter:

  /** Record `blockHash` as consensus-invalid, so a later `newPayload`/`forkchoiceUpdated` naming it (or naming a
    * descendant of it) answers INVALID instead of SYNCING/ACCEPTED.
    *
    * @param blockHash
    *   the block that failed validation.
    * @param latestValidHash
    *   the last block on this branch we actually validated and executed. Per the Engine API spec this is "the hash of
    *   the most recent valid block in the branch defined by payload and its ancestors", i.e. the failing block's parent
    *   — NOT the canonical head, which may be on a different branch entirely.
    */
  def reportInvalid(blockHash: ByteString, latestValidHash: ByteString): Unit

object InvalidChainReporter:

  /** Late-bound delegate, because the cake cannot be wired the other way round.
    *
    * `EngineApiBuilder`'s self-type already depends on `ConsensusBuilder` (it needs `blockExecution`), so
    * `ConsensusBuilder` cannot depend on `EngineApiBuilder` to receive a reporter at construction time without creating
    * a cycle. The import path therefore holds this holder from the start and the Engine API binds itself into it at
    * node startup.
    *
    * Binding is gated on `network.engine-api.enabled` (see `EngineApiBuilder.bindInvalidChainReporter`). That gate, not
    * the existence of an `EngineApiService` instance, is what keeps ETC inert: `Node` constructs an `EngineApiService`
    * unconditionally to back the `testing_*` JSON-RPC namespace, so "an EngineApiService exists" would have been the
    * wrong gate.
    *
    * Unbound, every `reportInvalid` is a no-op. Binding is idempotent-by-last-writer and happens once, on the startup
    * thread, before any peer block is imported.
    */
  final class LateBound extends InvalidChainReporter:
    private val delegate = new AtomicReference[Option[InvalidChainReporter]](None)

    def bind(reporter: InvalidChainReporter): Unit = delegate.set(Some(reporter))

    def isBound: Boolean = delegate.get().isDefined

    override def reportInvalid(blockHash: ByteString, latestValidHash: ByteString): Unit =
      delegate.get().foreach(_.reportInvalid(blockHash, latestValidHash))

  /** The prefix of `rest` that provably descends from `invalidBlock` through an unbroken `parentHash` chain.
    *
    * WHY THIS EXISTS. `EngineApiService.markInvalidRecursive` propagates a verdict to descendants, but only to
    * descendants it learned about through `engine_newPayload` (its `acceptedChildrenByParent` index). A block that
    * arrived over p2p is invisible to that index, so the poison chain breaks at the first p2p-supplied link and the
    * CL-supplied tip above it keeps getting ACCEPTED forever.
    *
    * Measured on hive `engine` commit 6c8bc97: hive's "Invalid Missing Ancestor Syncing ReOrg ... Invalid P8" plants
    * the invalid block TWO hops below the tip the CL hands us. The client log shows the import path correctly reporting
    * the invalid block (`import path reported block 46fd0a65… consensus-invalid, latestValidHash=7d0d9971…`) and then
    * answering `newPayload #15: ACCEPTED (parent unknown)` 80 more times, because the intermediate block (14) was
    * fetched from a peer and so is in no engine-side index. Every P8 variant failed, 16/16; every variant where the
    * invalid block IS the tip's direct parent (P9) passed.
    *
    * SAFETY. The link test is done here, block by block, rather than trusting the caller's branch to be contiguous: a
    * block is reported only if its `parentHash` equals the hash of the block proven invalid immediately before it. The
    * walk stops at the first break — it does not skip over a gap and resume. A block whose parent is consensus-invalid
    * is itself consensus-invalid with no execution required and with the SAME `latestValidHash` ("the most recent valid
    * block in the branch defined by payload and its ancestors" — Engine API spec), because the most recent valid
    * ancestor of every block on the poisoned suffix is the invalid block's parent.
    *
    * This does NOT widen [[provesConsensusInvalid]]. Callers must gate on that predicate for the FAILING block first;
    * this helper only carries an already-proven verdict downward along a proven link.
    */
  def provenDescendants(invalidBlock: Block, rest: List[Block]): List[Block] =
    @annotation.tailrec
    def loop(parentHash: BlockHash, remaining: List[Block], acc: List[Block]): List[Block] =
      remaining match
        case b :: tail if b.header.parentHash == parentHash => loop(b.hash, tail, b :: acc)
        case _                                              => acc.reverse
    loop(invalidBlock.hash, rest, Nil)

  /** Substring that identifies the one genuinely ambiguous post-execution failure. See [[provesConsensusInvalid]]. */
  private[ethereum] val GasUsedMismatchMarker = "Block has invalid gas used"

  /** Does this execution failure PROVE the block is consensus-invalid, as opposed to proving only that we do not
    * currently hold enough state to judge it?
    *
    * This predicate is the whole safety argument for the reporting channel. Reporting a block that is merely
    * unjudgeable marks an honest canonical block permanently invalid, and the node then refuses its own chain with no
    * recovery short of a restart. An earlier attempt at this feature did exactly that and was reverted. So the rule is:
    * report only where the error type itself excludes every transient reading.
    *
    * TRUE — the error cannot be produced by incomplete local state:
    *
    *   - `ValidationBeforeExecError` for a header/body rule (gas limit, timestamp, number, txs-root, ommers-root,
    *     withdrawals-root, PoS nonce/ommers, base fee, blob gas, RLP size). These are decided from the block's own
    *     bytes and its parent header, both of which we hold. Two exclusions below.
    *   - `TxsExecutionError`. Raised by the signed-transaction validator (signature, nonce, balance, gas limit, upfront
    *     cost) in `BlockPreparator.executeTransactions`. Not reachable from missing state: the account lookup that
    *     feeds those checks is `InMemoryWorldStateProxy.getAccount`, which THROWS `MissingAccountNodeException` on a
    *     missing trie node rather than returning an empty account, and that surfaces as `MPTError`.
    *   - `ValidationAfterExecError` for the state root or the receipts/bloom checks.
    *
    * FALSE — transient, or not decidable from what we hold:
    *
    *   - `MPTError`, `MissingParentError`: missing state, by definition.
    *   - `ValidationAfterExecError` carrying [[GasUsedMismatchMarker]]. This is THE ambiguous case.
    *     `InMemoryWorldStateProxy.getCode` returns `ByteString.empty` instead of throwing when the bytecode is absent
    *     from `EvmCodeStorage`, so a partially-synced node executes a contract call as if it were a transfer to an EOA,
    *     under-counts gas, and reports a gas mismatch for a perfectly honest block. The disambiguation
    *     (`findMissingContractCode`: `Some` => fetch over SNAP and retry, `None` => genuinely invalid) already exists
    *     in `BlockImporter`, and that is where the gas-used case is reported from. It is never reported from here.
    *   - `HeaderParentNotFoundError`: says only that the parent is not in storage yet. Same failure mode as the
    *     reverted attempt, one layer up.
    *   - `HeaderUnexpectedError`: a string-carrying catch-all (`SyncBlocksValidator` wraps arbitrary messages in it).
    *     No type-level guarantee, so no report.
    *   - `OmmersError`: every ommer rule is relative to local ancestry (`OmmerIsAncestorError`,
    *     `OmmerParentIsNotAncestorError`, `OmmersUsedBeforeError`) or recurses into per-ommer header validation that
    *     can itself yield `HeaderParentNotFoundError`. Incomplete ancestry can produce these on an honest block. Note
    *     this costs nothing on ETH: a post-merge block with a non-empty ommers list fails as
    *     `BlockHeaderError.PoSOmmersError`, which IS reported.
    *
    * ORDERING DEPENDENCY — DO NOT REORDER `StdValidators.validateBlockAfterExecution`. That method checks
    * `header.gasUsed` FIRST and returns early, and only then checks the state root. The early return is what makes a
    * state-root mismatch unambiguous here: reaching the state-root branch proves gas already matched, which proves
    * execution was complete, which rules out the missing-code artifact described above. Swap those two checks and a
    * missing-bytecode node starts permanently invalidating honest blocks through the state-root branch.
    */
  def provesConsensusInvalid(error: BlockExecutionError): Boolean =
    import BlockExecutionError.*
    error match
      case ValidationBeforeExecError(headerError: BlockHeaderError) =>
        headerError match
          case BlockHeaderError.HeaderParentNotFoundError => false
          case BlockHeaderError.HeaderUnexpectedError(_)  => false
          case _                                          => true
      case ValidationBeforeExecError(_: com.chipprbots.ethereum.consensus.pow.validators.OmmersValidator.OmmersError) =>
        false
      case ValidationBeforeExecError(_) =>
        // The remaining arm of `ValidationError` is StdBlockValidator.BlockError: txs-root, ommers-hash,
        // receipts-root, log bloom, withdrawals-root, RLP size. All structural, all decided from the block's bytes.
        true
      case _: TxsExecutionError =>
        true
      case ValidationAfterExecError(reason) =>
        !reason.contains(GasUsedMismatchMarker)
      case MissingParentError | _: MPTError =>
        false
