package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import cats.effect.IO

import fs2.Stream

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource.IterationError

/** Flat storage for contract storage slots — O(1) reads by (accountHash, slotHash).
  *
  * Stores raw slot values keyed by `accountHash ++ slotHash` (64 bytes) in a dedicated RocksDB column family. This is
  * the dominant pattern in modern Ethereum clients:
  *   - geth: snapshot layer (PathDB/HashDB flat storage)
  *   - nethermind: flat DB for SLOAD
  *   - besu: Bonsai Tries flat storage
  *
  * Benefits:
  *   - O(1) SLOAD during EVM execution (vs O(log n) MPT traversal)
  *   - Fast SNAP peer serving of GetStorageRanges (sequential scan by account prefix)
  *   - Efficient state iteration for eth_getProof, debug APIs
  *
  * The MPT-based storage trie is still maintained for Merkle proof generation and state root computation. Flat storage
  * is a read/write optimization layer.
  */
class FlatSlotStorage(val dataSource: DataSource) extends TransactionalKeyValueStorage[ByteString, ByteString] {
  val namespace: IndexedSeq[Byte] = Namespaces.FlatSlotNamespace
  def keySerializer: ByteString => IndexedSeq[Byte] = identity
  def keyDeserializer: IndexedSeq[Byte] => ByteString = k => ByteString.fromArrayUnsafe(k.toArray)
  def valueSerializer: ByteString => IndexedSeq[Byte] = identity
  def valueDeserializer: IndexedSeq[Byte] => ByteString = v => ByteString.fromArrayUnsafe(v.toArray)

  /** Look up a storage slot by account hash and slot hash. O(1) RocksDB read. */
  def getSlot(accountHash: ByteString, slotHash: ByteString): Option[ByteString] =
    get(accountHash ++ slotHash)

  /** Bulk write for SNAP sync: write all slots for an account in one batch. Returns a DataSourceBatchUpdate that must
    * be `.commit()`ed.
    */
  def putSlotsBatch(accountHash: ByteString, slots: Seq[(ByteString, ByteString)]): DataSourceBatchUpdate =
    update(
      Nil,
      slots.map { case (slotHash, value) =>
        (accountHash ++ slotHash) -> value
      }
    )

  override def storageContent: Stream[IO, Either[IterationError, (ByteString, ByteString)]] =
    dataSource.iterate(namespace).map { result =>
      result.map { case (key, value) => (ByteString.fromArrayUnsafe(key), ByteString.fromArrayUnsafe(value)) }
    }
}
