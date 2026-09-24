package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.util.ByteString

import scala.annotation.tailrec

import com.chipprbots.ethereum.consensus.engine.DesignatedHead
import com.chipprbots.ethereum.consensus.engine.ForkChoiceManager
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader

/** The block the consensus layer's head depends on and we do not hold. Its hash is what we ask peers about.
  *
  * WHY THIS EXISTS. Regular sync is driven by what PEERS advertise, not by what the consensus layer asks for: the
  * fetcher requests headers by number up to the best height a peer has told us about, and `PeersClient.bestPeer` never
  * selects a peer whose advertised height is 0 (the genesis-peer filter, #1201). An eth/69 peer advertises its height
  * in STATUS and afterwards only through `BlockRangeUpdate`, which go-ethereum sends every 32 blocks or when its range
  * moves backwards (eth/handler.go `shouldSend`). A peer that handshook with us at genesis is therefore invisible to
  * the fetcher until it is 32 blocks further on, however many blocks the consensus layer names in the meantime.
  *
  * Measured on hive `engine`, "Invalid Missing Ancestor Syncing ReOrg, StateRoot, EmptyTxs=True, CanonicalReOrg=True,
  * Invalid P9" (Paris and Cancun). The secondary geth handshakes at genesis, both nodes follow the CL to block 15, then
  * the secondary is re-orged onto a side chain 6'..14'. The CL hands us 15' — ACCEPTED, parent unknown — and polls it
  * for the rest of the test. fukuii logs 103 `No suitable peer found` and never sends the peer a single request. In the
  * `EmptyTxs=False` twin, geth dropped the connection shortly after transaction gossip and redialed, and only the
  * redial's STATUS (height 14) let a request out. That is how the twin passes on CI; locally it failed too.
  *
  * WHAT IS DONE ABOUT IT. When the CL names a head we hold only by hash, [[target]] walks back through the headers we
  * hold and never executed until it reaches the first one we lack. `NetworkPeerManagerActor.ProbeMissingAncestorCmd`
  * then asks every peer whose advertised height is below that block for its header, by hash. Nothing new consumes the
  * reply: `NetworkPeerManagerActor.updateMaxBlock` raises the peer's height from any `BlockHeaders` it sends, and
  * `BlockFetcher` treats any `BlockHeaders` as a new-top candidate. The fetcher's own by-number request, which retries
  * while no peer qualifies, then goes out and the side chain is imported and judged on the existing path.
  *
  * Why no probe in the other cases:
  *   - The head was executed, or is canonical: nothing on its ancestry is missing. This is every FCU of a node that is
  *     keeping up, so a node that is not syncing sends nothing.
  *   - The CL head itself is unknown (no header): we do not know its height, so we cannot tell which peers are behind
  *     it; the SNAP pivot path owns an unknown CL head.
  *   - The walk passes [[DesignatedHead.MaxAncestryWalk]] headers without finding a gap: the same guard the fork-choice
  *     ancestry walk uses against a pathological stored-header chain.
  *
  * ETC/Mordor/Gorgoroth: unreachable. The only caller is `SyncController`'s regular-sync `BeaconHead` arm, and
  * `ForkChoiceManager` publishes `BeaconHead` only to a listener registered when `clPivotEnabled` holds — a configured
  * terminal-total-difficulty, which no PoW chain sets.
  */
object MissingAncestorProbe:

  /** A block we lack on the path to the CL head. `number` is exact: it is one below its child, whose header we hold. */
  final case class Target(hash: ByteString, number: BigInt)

  /** The first block missing from the ancestry of the CL head described by `beaconHead`, if the head is one we hold but
    * never executed. See the object comment for every `None`.
    */
  def target(
      beaconHead: ForkChoiceManager.BeaconHead,
      reader: BlockchainReader,
      maxWalk: Int = DesignatedHead.MaxAncestryWalk
  ): Option[Target] =
    @tailrec
    def walk(header: BlockHeader, hopsLeft: Int): Option[Target] =
      if header.number.value == 0 || executed(header, reader) then None
      else
        reader.getBlockHeaderByHash(header.parentHash) match
          case None         => Some(Target(header.parentHash.value, header.number.value - 1))
          case Some(parent) => if hopsLeft <= 0 then None else walk(parent, hopsLeft - 1)
    beaconHead.knownHeader.flatMap(walk(_, maxWalk))

  /** Canonical at its height, or executed off the canonical chain (receipts stored). The same predicate
    * `EngineApiService.newPayload` uses to decide that a parent's ancestry is ours (`parentValidated`).
    */
  private def executed(header: BlockHeader, reader: BlockchainReader): Boolean =
    reader.getBlockHeaderByNumber(header.number.value).exists(_.hash == header.hash) ||
      reader.getReceiptsByHash(header.hash).isDefined
