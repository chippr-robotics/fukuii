package com.chipprbots.ethereum.blockchain.sync

import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.Difficulty

/** Whether the stored chain weight of a block (in practice the best block) is exact, and if not, whether an exact value
  * can be derived from local data (#1654).
  *
  * Fork choice compares one stored weight with another: `ConsensusImpl.importToNewBranch` weighs a branch from its
  * parent's stored weight against the stored weight of the current best block, and `BlockQueue` reads the parent weight
  * from storage. Block import keeps one invariant: every stored weight is its parent's plus its own difficulty.
  *
  *   - A chain whose BASE was estimated (a SNAP or checkpoint pivot) but whose links hold carries the same error at
  *     every block above the base. It cancels for every fork whose fork point lies above the base.
  *   - One rewritten weight does not cancel. It is a jump, and every fork that branches below it is compared across it.
  *
  * So "exact" here means the link to the parent holds and the parent's weight clears [[floor]]. It does NOT mean the
  * base of the chain is exact: a `ChainWeight` carries no provenance, so that cannot be told from storage. Repairing a
  * mis-estimated base means recomputing every stored weight above it, which is a separate decision (#1654 option b3).
  */
object HeadChainWeight:

  enum Status:
    /** The stored weight is the parent's plus this block's difficulty, and the parent's clears the floor. Writing any
      * other value creates a jump.
      */
    case Exact(stored: ChainWeight)

    /** The parent's weight clears the floor, but this block's stored weight is absent or does not follow from it. The
      * exact value is `exact`; `stored` is what is there now.
      */
    case Derivable(stored: Option[ChainWeight], exact: ChainWeight)

    /** Nothing exact is stored: the parent has no weight (a SNAP or checkpoint pivot whose parent headers are not
      * backfilled), or the parent's weight is below the floor (a block-number or genesis proxy, or a chain built on
      * one). Only here may an estimate be written.
      */
    case NoExact

  /** Lower bound on the total difficulty of any real block at height `number`: the genesis weight plus
    * `MinimumDifficulty` for each block above genesis.
    *
    * Ethash clamps every PoW block's difficulty to at least `MinimumDifficulty` (131072, EIP-2), so no real ETC, Mordor
    * or pre-merge ETH weight is below this. It holds on a chain whose blocks carry ~9e6 (Mordor) as well as ~2e15 (ETC
    * mainnet) and needs no per-chain constant. A block-number proxy (`td = number`) and a genesis proxy (`td = genesis
    * weight`) are below it at every height above genesis.
    *
    * Post-merge blocks carry difficulty 0, so a chain that was PoS from genesis (e.g. Hoodi, TD 1) never clears it;
    * such a chain is classified `NoExact`, which is what it was before #1654.
    */
  def floor(number: BigInt, genesisWeight: BigInt): BigInt =
    genesisWeight + number.max(0) * Difficulty.MinimumDifficulty.value

  def isPlausible(number: BigInt, totalDifficulty: BigInt, genesisWeight: BigInt): Boolean =
    totalDifficulty >= floor(number, genesisWeight)

  /** The stored genesis weight, else the genesis difficulty, else 0 when no genesis header is indexed. 0 still gives a
    * sound (looser) floor, and unlike `BlockchainReader.genesisHeader` this never throws.
    */
  def genesisWeight(reader: BlockchainReader): BigInt =
    reader.getBlockHeaderByNumber(0) match
      case Some(genesis) =>
        reader.getChainWeightByHash(genesis.hash).map(_.totalDifficulty.value).getOrElse(genesis.difficulty.value)
      case None => BigInt(0)

  def classify(reader: BlockchainReader, header: BlockHeader): Status =
    val stored = reader.getChainWeightByHash(header.hash)
    if header.number.value == 0 then
      // Genesis has no parent: its exact weight is its own difficulty, as GenesisDataLoader stores it.
      val exact = ChainWeight.totalDifficultyOnly(header.difficulty.value)
      if stored.contains(exact) then Status.Exact(exact) else Status.Derivable(stored, exact)
    else
      reader.getChainWeightByHash(header.parentHash) match
        case Some(parent)
            if isPlausible(header.number.value - 1, parent.totalDifficulty.value, genesisWeight(reader)) =>
          val exact = parent.increase(header)
          if stored.contains(exact) then Status.Exact(exact) else Status.Derivable(stored, exact)
        case _ => Status.NoExact
