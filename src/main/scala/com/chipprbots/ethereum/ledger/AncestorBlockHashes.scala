package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import scala.collection.mutable.ArrayBuffer

import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.utils.Logger

/** The BLOCKHASH source for executing one block: the hash of the ancestor at height `n`, found by walking `parentHash`
  * back from the executing block — never by reading the canonical number→hash index.
  *
  * This is core-geth's `GetHashFn` (core/evm.go:93-129; go-ethereum identical). The index names whatever chain the node
  * last wrote at each height, and that is not always the executing block's ancestry: a side-chain block executed during
  * a reorganisation (or an Engine API side-chain payload stored by hash only) has different ancestors at the heights
  * above its fork point, and a reorganisation that is later abandoned or reversed can leave index entries from two
  * chains behind. Reading the index there hands the contract a hash from the wrong chain, the state root diverges from
  * every other client, and a valid block is rejected. The ancestry walk cannot be wrong in that way: the executing
  * block's parent hash is part of the block itself.
  *
  * Semantics, exactly as core-geth: `cache(0)` is the parent hash (height `number - 1`); each further element is the
  * previous element's header's `parentHash`, appended lazily and only as deep as a query needs, then kept for the rest
  * of the block. The first lookup at depth d costs d header reads, every later one within that depth is free; depth 1
  * costs nothing. `n >= number` answers `None` (core-geth: the empty hash). The 256-block window and the exclusion of
  * the current block are applied by the caller, `OpCode.BLOCKHASH`, as in core-geth's `opBlockhash`.
  *
  * ONE deliberate difference. If the walk cannot continue — an ancestor header is not in storage — core-geth returns
  * the empty hash; a core-geth node always has every header, so it never hits that. fukuii can (a SNAP-synced range
  * whose headers were not all downloaded), and there this falls back to the canonical index for the requested height:
  * exactly what fukuii answered before, so a node in that state behaves no worse than it did. It is logged at WARN once
  * per block, because it means BLOCKHASH is being answered from a source that is not proven to be this block's
  * ancestry.
  *
  * Thread safety: one instance serves one block execution, but the closure is carried by every world-state copy made
  * during it, so access is synchronized. Uncontended in practice.
  *
  * @param number
  *   the number of the block being executed
  * @param parentHash
  *   that block's `parentHash`
  */
final class AncestorBlockHashes(
    number: BigInt,
    parentHash: BlockHash,
    headerByHash: BlockHash => Option[BlockHeader],
    canonicalHashByNumber: BigInt => Option[ByteString]
) extends (BigInt => Option[ByteString])
    with Logger:

  // cache(i) is the hash of the block at height number - 1 - i.
  private val cache = ArrayBuffer[ByteString](parentHash.value)
  private var walkBroken = false

  override def apply(n: BigInt): Option[ByteString] = synchronized {
    if n >= number || n < 0 then None
    else
      val idx = number - n - 1
      while idx >= cache.size && !walkBroken do extend()
      if idx < cache.size then Some(cache(idx.toInt))
      else canonicalHashByNumber(n)
  }

  private def extend(): Unit =
    val lastKnownHash = cache.last
    val lastKnownNumber = number - cache.size
    headerByHash(BlockHash(lastKnownHash)) match
      case Some(header) if header.number.value == lastKnownNumber && lastKnownNumber > 0 =>
        cache += header.parentHash.value
      case found =>
        walkBroken = true
        if lastKnownNumber > 0 then
          log.warn(
            "BLOCKHASH ancestry walk for block {} stopped at height {} ({}): {}. Falling back to the canonical index " +
              "below that height.",
            number,
            lastKnownNumber,
            BlockHash(lastKnownHash).toHexString,
            found.fold("header not in storage")(h => s"stored header has number ${h.number}")
          )

object AncestorBlockHashes:

  /** BLOCKHASH source for executing `header`, reading headers from `reader`. */
  def forBlock(header: BlockHeader, reader: BlockchainReader): AncestorBlockHashes =
    new AncestorBlockHashes(
      header.number.value,
      header.parentHash,
      reader.getBlockHeaderByHash,
      (n: BigInt) => reader.getBlockHeaderByNumber(n).map(_.hash.value)
    )
