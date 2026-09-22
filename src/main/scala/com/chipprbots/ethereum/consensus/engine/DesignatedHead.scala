package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.util.ByteString

import java.util.concurrent.atomic.AtomicReference

import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockchainReader

/** Read-only view of the head the CONSENSUS LAYER has designated, for use by the p2p import path.
  *
  * WHY THIS EXISTS. Post-merge, chain weight cannot decide a fork. `ChainWeight.increase` adds `header.difficulty` and
  * every post-merge header carries `difficulty = 0`, so every branch rooted at the same parent has exactly the same
  * weight and `newWeight > oldWeight` is unsatisfiable by construction. The two places that ask that question —
  * `BranchResolution.compareBranch` and `ConsensusImpl.importToNewBranch` — therefore refuse EVERY post-merge side
  * branch, which is why a CL-designated branch arriving over p2p is fetched and then silently dropped without ever
  * being executed.
  *
  * The replacement question is not "is it heavier" but "did the consensus layer ask for it". That is the only correct
  * fork choice on a PoS chain: the execution layer does not choose, it follows. This trait is how the two weight sites
  * ask.
  *
  * ETC/Mordor/Gorgoroth — TWO INDEPENDENT REASONS THIS IS UNREACHABLE ON A PoW CHAIN, either of which alone suffices:
  *
  *   1. Both consumers take `Option[DesignatedHead]` and both derive it from the chain being post-merge.
  *      `SyncController` uses its existing `isPoSChain` (`blockchainConfig.terminalTotalDifficulty.isDefined`, the same
  *      predicate behind `clPivotEnabled`); `terminal-total-difficulty` is set in exactly two config files,
  *      `eth-chain.conf` and `sepolia-chain.conf`, and in no PoW chain config. So the value is `None` on ETC and the
  *      PoS arm is not merely false, it is structurally absent. 2. The [[DesignatedHead.LateBound]] holder handed to
  *      `ConsensusImpl` is bound only by `EngineApiBuilder.bindDesignatedHead()`, gated on `network.engine-api.enabled`
  *      AND a configured TTD. Unbound, `headBlockHash` is `None`, and [[DesignatedHead.leadsToDesignatedHead]] is then
  *      false for every input.
  *
  * A reviewer checking ETC impact only has to confirm that no PoW configuration sets `terminal-total-difficulty` and
  * that nothing else calls `bind`. Both are one grep.
  */
trait DesignatedHead:

  /** The `headBlockHash` of the most recent `engine_forkchoiceUpdated`, or `None` if the consensus layer has never
    * spoken (or there is no consensus layer, which is the PoW case).
    */
  def headBlockHash: Option[ByteString]

object DesignatedHead:

  /** Wrap a read of the current fork-choice head. Written out rather than relying on SAM conversion because
    * `headBlockHash` takes no parameter list and so is not SAM-convertible, and because an explicit factory reads
    * better at the two call sites than a bare lambda would.
    */
  def apply(readHead: () => Option[ByteString]): DesignatedHead =
    new DesignatedHead:
      override def headBlockHash: Option[ByteString] = readHead()

  /** How far back from the designated head we are willing to walk while looking for the branch tip.
    *
    * The walk only ever visits headers we already hold, and stops dead at the first one we do not, so the realistic
    * bound is "how far ahead of the branch we just fetched has the CL already pushed payloads at us" — in hive's
    * invalid-ancestor tests, exactly one block. The cap is a guard against a pathological stored-header chain, not a
    * tuning parameter; it is deliberately the same order as one header batch.
    */
  val MaxAncestryWalk: Int = 1024

  /** Late-bound delegate, for the one consumer that cannot be handed a `ForkChoiceManager` at construction time.
    *
    * `EngineApiBuilder`'s self-type already depends on `ConsensusBuilder`, so `ConsensusBuilder` cannot depend back on
    * it to receive a `ForkChoiceManager` without a cake cycle — the identical constraint that forced
    * [[InvalidChainReporter.LateBound]]. `SyncController` has no such problem: it is already handed the
    * `ForkChoiceManager` directly and builds its own `Option[DesignatedHead]` from it.
    *
    * Unbound, `headBlockHash` is `None`. Binding is idempotent-by-last-writer and happens once, on the startup thread.
    */
  final class LateBound extends DesignatedHead:
    private val delegate = new AtomicReference[Option[DesignatedHead]](None)

    def bind(source: DesignatedHead): Unit = delegate.set(Some(source))

    def isBound: Boolean = delegate.get().isDefined

    override def headBlockHash: Option[ByteString] = delegate.get().flatMap(_.headBlockHash)

  /** Does `branchTipHash` lie on the path from the designated head back through our stored headers?
    *
    * This is THE predicate both weight sites use, deliberately shared so there is one implementation to review rather
    * than two that can drift. It answers: "if we adopt this branch, are we moving toward the head the consensus layer
    * named?" — true when the branch tip IS the designated head, or when the designated head descends from it through an
    * unbroken chain of headers we already hold.
    *
    * Why the headers are there to walk: a head the CL names but we cannot execute arrives through `engine_newPayload`,
    * which stores it with `storeBlockByHashOnly` before answering ACCEPTED. fukuii's own log confirms it holds the
    * header at exactly the moment this matters — `Fork choice head … not executed yet (SYNCING, notify-only):
    * headerKnown=true`.
    *
    * Conservative in the safe direction. Any doubt — no source, consensus layer silent, a header missing partway, more
    * than [[MaxAncestryWalk]] hops — returns false, which leaves the caller on its pre-existing behaviour of refusing
    * the branch. It can only ever turn a refusal into an acceptance, never the reverse, and only when the consensus
    * layer has explicitly asked for that branch.
    */
  def leadsToDesignatedHead(
      designatedHead: Option[DesignatedHead],
      blockchainReader: BlockchainReader,
      branchTipHash: ByteString
  ): Boolean =
    designatedHead.flatMap(_.headBlockHash).exists { head =>
      @annotation.tailrec
      def walk(hash: ByteString, hopsLeft: Int): Boolean =
        if hash == branchTipHash then true
        else if hopsLeft <= 0 then false
        else
          blockchainReader.getBlockHeaderByHash(BlockHash(hash)) match
            case Some(header) => walk(header.parentHash.value, hopsLeft - 1)
            case None         => false
      walk(head, MaxAncestryWalk)
    }
