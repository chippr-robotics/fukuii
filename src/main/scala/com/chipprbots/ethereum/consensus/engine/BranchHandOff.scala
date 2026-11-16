package com.chipprbots.ethereum.consensus.engine

import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.util.ByteString

/** The hand-off of the consensus layer's branch from the p2p import path to the fork choice's recovery (spec 018 C5a,
  * B-297).
  *
  * On the unified state layout `ConsensusImpl` executes the consensus layer's branch on layers and adopts it by one
  * move. A branch longer than the executed blocks kept beside the disk, or one that forks below what a view reaches, is
  * not committed in place there: that adoption is `StateRecovery`'s alone, which owns it for proof of stake (C4e part
  * 5). With its blocks stored by hash, the branch is handed to that recovery here, as a fork choice that names its tip
  * would hand it.
  *
  * Late-bound for the same reason as [[DesignatedHead.LateBound]]: `ConsensusImpl` is built before the Engine API that
  * owns the recovery and its sink. Bound only on a post-merge chain with the Engine API on and a recovery built (the
  * unified layout); unbound, [[handOff]] answers false and the caller does what it did before.
  */
trait BranchHandOff:

  /** Asks the fork choice's recovery for the state of `tip`, the last block of the consensus layer's branch, every
    * block of which is stored. Never blocks and never executes anything on the calling thread.
    *
    * @return
    *   whether it was handed to a recovery: false when there is none to hand it to
    */
  def handOff(tip: ByteString, why: String): Boolean

  /** Whether a branch handed off now would be taken: asked before the branch's blocks are stored for it. */
  def isBound: Boolean = true

object BranchHandOff:

  /** Unbound, it hands nothing off. Bound once, on the startup thread, by `EngineApiBuilder.bindBranchHandOff`. */
  final class LateBound extends BranchHandOff:
    private val delegate = new AtomicReference[Option[BranchHandOff]](None)

    def bind(target: BranchHandOff): Unit = delegate.set(Some(target))

    override def isBound: Boolean = delegate.get().isDefined

    override def handOff(tip: ByteString, why: String): Boolean = delegate.get().exists(_.handOff(tip, why))
