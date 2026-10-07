package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.storage.BlockAccessListStorage.*

/** EIP-7928 block access lists (Amsterdam, ETH-family only), by block: key the block hash, value the list's canonical
  * RLP — the bytes whose keccak256 the header's `blockAccessListHash` commits to, and the bytes
  * `engine_getPayloadBodiesByHash/RangeV2` and eth/71 serve as they are.
  *
  * EIP-7928 keeps the list out of the block body ("Clients MUST store BALs separately from blocks and make them
  * available via the engine API") and asks an EL to keep it for at least the weak-subjectivity period, 3,533 epochs. A
  * list is written once its block has validated against it (`BlockchainWriter.storeBlockAccessList`); a block without
  * an entry — every pre-Amsterdam and ETC block, and an Amsterdam block imported before this store existed — reads as
  * `None`, which the serving methods report as `null` ("pre-Amsterdam or pruned").
  *
  * Stored as raw bytes rather than a pickled form: the RLP is already the list's canonical, self-validating encoding
  * (`BlockAccessList.decode` accepts exactly it), so a reader gets the committed bytes back without re-encoding.
  */
class BlockAccessListStorage(val dataSource: DataSource)
    extends TransactionalKeyValueStorage[BlockHash, BlockAccessListRlp]:

  val namespace: IndexedSeq[Byte] = Namespaces.BlockAccessListNamespace
  val keySerializer: BlockHash => IndexedSeq[Byte] = identity
  val keyDeserializer: IndexedSeq[Byte] => BlockHash = bytes => ByteString(bytes*)
  val valueSerializer: BlockAccessListRlp => IndexedSeq[Byte] = identity
  val valueDeserializer: IndexedSeq[Byte] => BlockAccessListRlp = bytes => ByteString(bytes*)

object BlockAccessListStorage:
  type BlockHash = ByteString

  /** A block access list's canonical RLP encoding. */
  type BlockAccessListRlp = ByteString
