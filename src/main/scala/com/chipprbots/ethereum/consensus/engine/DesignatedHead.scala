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
  * ETC/Mordor/Gorgoroth — WHY THE PoS ARM IS INERT ON A PoW CHAIN. There are two consumers and each has exactly one
  * gate. They are not two layers of defence on one path; they are one gate per path, so each must hold on its own.
  *
  * `BranchResolution` (constructed by `SyncController`): receives `Option[DesignatedHead]` built as `if isPoSChain then
  * forkChoiceManagerOpt.map(...) else None`, where `isPoSChain` is `blockchainConfig.terminalTotalDifficulty.isDefined`
  * — the predicate behind `clPivotEnabled`. `terminal-total-difficulty` is set only in `eth-chain.conf` and
  * `sepolia-chain.conf`, so on a PoW chain the value is `None` and the arm is structurally absent.
  *
  * `ConsensusImpl` (constructed by `NodeBuilder`): receives `Some(LateBound)` on EVERY network, ETC included — the cake
  * forbids handing it a `ForkChoiceManager` at construction. The gate here is BINDING: the holder is bound only by
  * `EngineApiBuilder.bindDesignatedHead()`, which requires `network.engine-api.enabled` AND a configured TTD. On a PoW
  * chain it stays unbound, `headBlockHash` is `None`, and [[DesignatedHead.leadsToDesignatedHead]] is false for every
  * input.
  *
  * Misconfiguration hazard: an operator who force-sets `terminal-total-difficulty` on a PoW chain opens BOTH gates.
  * Then a LIGHTER or equal-TD branch the head designates is accepted — the arm sits after the TD comparison, so it
  * overrides the TD rule and MESS is never consulted on that path — while a HEAVIER branch that MESS rejects is still
  * refused, because MESS lives inside the TD arm, which returns first. That configuration already switches on other PoS
  * paths; the behaviour is pinned, not prevented, by `BranchResolutionSpec`.
  *
  * A reviewer checking ETC impact only has to confirm that no PoW configuration sets `terminal-total-difficulty` and
  * that nothing else calls `bind`. Both are one grep.
  */
trait DesignatedHead:

  /** The `headBlockHash` of the most recent `engine_forkchoiceUpdated` that was not rejected as a known-INVALID head —
    * whether or not we have executed that head — or `None` if the consensus layer has never spoken (or there is no
    * consensus layer, which is the PoW case).
    *
    * Both production sources read `ForkChoiceManager.getRequestedHeadBlockHash`. They must NOT read
    * `ForkChoiceManager.getHeadBlockHash`: that is executed-only, and the case this trait exists for is precisely a CL
    * head stored by hash but not yet executed, which reaches the manager through `notifyBeaconHead` and never moves the
    * executed head. Bound to the executed head, the ancestry walk starts from the OLD canonical tip and refuses every
    * branch it was meant to accept (hive `engine` 4854b7d20: 0 of 28 targets cleared).
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
