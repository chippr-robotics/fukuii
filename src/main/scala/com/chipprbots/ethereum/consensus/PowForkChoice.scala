package com.chipprbots.ethereum.consensus

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.ChainWeight

/** The proof-of-work fork choice of core-geth: `ForkChoice.ReorgNeeded` (core/forkchoice.go), by total difficulty, with
  * its rule for a tie (B-284; the user's decision of 2026-11-24):
  *
  * {{{
  * reorg := externTd.Cmp(localTD) > 0
  * tie := externTd.Cmp(localTD) == 0
  * if tie {
  *     externNum, localNum := extern.Number.Uint64(), current.Number.Uint64()
  *     if externNum < localNum {
  *         reorg = true
  *     } else if externNum == localNum {
  *         var currentPreserve, externPreserve bool
  *         if f.preserve != nil {
  *             currentPreserve, externPreserve = f.preserve(current), f.preserve(extern)
  *         }
  *         reorg = !currentPreserve && (externPreserve || f.rand.Float64() < 0.5)
  *     }
  * }
  * }}}
  *
  * The lower block wins a tie ("reduces the vulnerability to selfish mining"); at the same height a block this node's
  * miner sealed is kept, or taken; otherwise a coin decides. `preserve` is core-geth's `shouldPreserve`
  * (eth/backend.go), `isLocalBlock`: the block's author, the beneficiary under Ethash, is the etherbase.
  *
  * Not here, and asked where fukuii weighs: the terminal total difficulty arm (a chain that declares one is not weighed
  * by this rule at all: every caller keeps its strict comparison there, so nothing on the ETH family changes) and MESS
  * (ECBP-1100), which `BranchResolution` asks after this says reorganise, as core-geth asks it after `reorg` is true.
  *
  * A tie is broken only between two proof-of-work blocks: a block of no difficulty is a block after a merge, whose head
  * a consensus layer names, and keeps the head here as it did before. No valid block of an ETC chain has none.
  *
  * '''One coin per pair.''' core-geth asks once, when the block is written (`writeBlockAndSetHead`). fukuii weighs the
  * same branch more than once: the headers before the bodies are fetched (`BranchResolution`), the branch when it is
  * executed (`ConsensusImpl`), and what was executed after. So the coin's outcome is kept for the two blocks it was
  * tossed between, as the winner's hash, whichever of them was the head: (A, B) and (B, A) are one question, decided
  * once, and a node that took B over A does not take A back over B on a second coin (forge B1). Bounded (`remembered`);
  * a pair forgotten is tossed again, as core-geth tosses again for a block written twice. One instance per node, shared
  * by every weighing.
  *
  * '''A coin moves the head, nothing more.''' What a weighing answers says whether the rule decided it or a coin did
  * ([[PowForkChoice.Decision]]). A coin is worth a reorganisation on blocks this node holds, as core-geth's is; it is
  * never worth giving this node's state up. The escalation to a SNAP re-sync, the fork recovery's discard and the
  * escalation's count take a branch only on a [[PowForkChoice.Verdict.Reorg]] of the rule ([[verdict]]), and toss no
  * coin: a peer's headers, not yet validated, can claim any weight, and a tie of theirs is no entry in the memo.
  *
  * @param preserve
  *   whether a block is this node's own: kept on a tie at its height, or taken over another
  * @param coin
  *   a number in [0, 1): core-geth's `rand.Float64()`. A real random source in production ([[PowForkChoice.Coin]]);
  *   given by tests
  * @param remembered
  *   how many coin outcomes are kept
  * @param breaksTies
  *   false for [[PowForkChoice.HeavierOnly]]: a tie keeps the head, whatever the heights
  */
final class PowForkChoice(
    preserve: BlockHeader => Boolean,
    coin: () => Double,
    remembered: Int = PowForkChoice.Remembered,
    breaksTies: Boolean = true
):
  import PowForkChoice.Decision
  import PowForkChoice.Verdict

  require(remembered > 0, s"remembered must be positive, was $remembered")

  // the winner of each pair a coin decided, keyed by the pair unordered: (A, B) and (B, A) read one outcome
  private val tossed = new java.util.LinkedHashMap[Set[BlockHash], BlockHash](16, 0.75f, true):
    override def removeEldestEntry(eldest: java.util.Map.Entry[Set[BlockHash], BlockHash]): Boolean =
      size() > remembered

  /** The rule without the coin: whether `extern`, of weight `externWeight`, takes the head from `current`, of weight
    * `currentWeight`; or that a coin is to decide it. Tosses nothing and remembers nothing.
    */
  def verdict(
      current: BlockHeader,
      currentWeight: ChainWeight,
      extern: BlockHeader,
      externWeight: ChainWeight
  ): Verdict =
    val diff = externWeight.totalDifficulty.compare(currentWeight.totalDifficulty)
    if diff > 0 then Verdict.Reorg
    else if diff < 0 || !breaksTies || current.difficulty.value == 0 || extern.difficulty.value == 0 then Verdict.Keep
    else if extern.number < current.number then Verdict.Reorg
    else if extern.number > current.number then Verdict.Keep
    // the head itself, weighed again (a batch that re-presents the head's own blocks, executed up to the head): nothing
    // to take it from. core-geth never asks this: its insertChain skips a known canonical block
    else if extern.hash == current.hash then Verdict.Keep
    else
      // both asked, as core-geth asks both before it reads either
      val currentPreserve = preserve(current)
      val externPreserve = preserve(extern)
      if currentPreserve then Verdict.Keep
      else if externPreserve then Verdict.Reorg
      else Verdict.Coin

  /** core-geth's `ReorgNeeded`, and whether the rule or a coin answered it. A tie the coin decides is decided once for
    * the pair, in either order, and read again after.
    */
  def decide(
      current: BlockHeader,
      currentWeight: ChainWeight,
      extern: BlockHeader,
      externWeight: ChainWeight
  ): Decision =
    verdict(current, currentWeight, extern, externWeight) match
      case Verdict.Reorg => Decision.Reorg
      case Verdict.Keep  => Decision.Keep
      case Verdict.Coin =>
        val pair = Set(current.hash, extern.hash)
        val winner = tossed.synchronized {
          Option(tossed.get(pair)).getOrElse {
            val won = if coin() < 0.5 then extern.hash else current.hash
            tossed.put(pair, won)
            won
          }
        }
        Decision.Coin(taken = winner == extern.hash)

  /** core-geth's `ReorgNeeded`: whether `extern` takes the head from `current`, the rule's answer or the coin's. For a
    * head move only: what must not act on a coin asks [[verdict]].
    */
  def reorgNeeded(
      current: BlockHeader,
      currentWeight: ChainWeight,
      extern: BlockHeader,
      externWeight: ChainWeight
  ): Boolean =
    decide(current, currentWeight, extern, externWeight).reorg

object PowForkChoice:

  /** What the rule says before a coin is tossed. `Reorg` and `Keep` are the rule's own; `Coin`, a tie only a coin
    * decides.
    */
  enum Verdict:
    case Reorg, Keep, Coin

  /** What a weighing answers, and by what: the rule (`Reorg`, `Keep`), or a coin (`Coin`, with its outcome for this
    * order of the pair). Only a `Reorg` is a reason to give anything of this node's up beyond its head.
    */
  enum Decision:
    case Reorg
    case Keep
    case Coin(taken: Boolean)

    /** Whether the head is taken. */
    def reorg: Boolean = this match
      case Decision.Reorg       => true
      case Decision.Keep        => false
      case Decision.Coin(taken) => taken

  /** How many coin outcomes a fork choice keeps: far more than the ties open at once on a live chain. */
  val Remembered: Int = 1024

  /** The production coin: a cryptographically seeded source, as core-geth seeds its `math/rand` from `crypto/rand`. */
  object Coin extends (() => Double):
    private val random = new java.security.SecureRandom()
    def apply(): Double = random.nextDouble()

  /** The fork choice of a node whose own blocks are those of the etherbase `etherbase` gives (it may change at run
    * time: `miner_setEtherbase`), with the production coin. None: no block is the node's own, as core-geth's
    * `isLocalBlock` answers with no etherbase set ([[com.chipprbots.ethereum.consensus.mining.CoinbaseProvider]]
    * `.ownBlocksEtherbase`).
    */
  def ofEtherbase(etherbase: () => Option[Address]): PowForkChoice =
    new PowForkChoice(header => etherbase().exists(_.bytes == header.beneficiary), Coin)

  /** The comparison fukuii made before B-284, strictly heavier: a tie keeps the head, and no coin is tossed. For an
    * evaluation that takes no part in the fork choice: a [[Consensus]] that is not [[ConsensusImpl]] (test doubles),
    * and an import that names its head whatever its weight
    * ([[com.chipprbots.ethereum.blockchain.data.ChainImporter.adoptingOnLayers]]). For test mode and the ethereum/tests
    * runners, which are deterministic (forge W3). And the default of every construction that names no fork choice
    * (`ConsensusImpl`, `BranchResolution`, `DeepForkWeight`, `PowEscalationFeed`): only a node's own
    * (`NodeBuilder.powForkChoice`) tosses a coin.
    */
  val HeavierOnly: PowForkChoice = new PowForkChoice(_ => false, () => 1.0, breaksTies = false)
