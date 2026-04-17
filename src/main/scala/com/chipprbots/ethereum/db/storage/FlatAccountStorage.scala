package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import cats.effect.IO

import fs2.Stream

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource.IterationError

/** Flat storage for accounts — O(1) reads by keccak256(address).
  *
  * Stores RLP-encoded Account(nonce, balance, storageRoot, codeHash) keyed by
  * keccak256(address) (32 bytes) in a dedicated RocksDB column family.
  *
  * Mirrors Besu's BonsaiFullFlatDbStrategy.getFlatAccount:
  *   storage.get(ACCOUNT_INFO_STATE, accountHash.getBytes()) → RLP(account)
  *
  * Key: keccak256(address) — 32 bytes (matches Besu ACCOUNT_INFO_STATE column)
  * Value: RLP-encoded StateTrieAccountValue
  *
  * Benefits:
  *   - O(1) account reads during EVM execution and RPC serving
  *   - Fast SNAP peer serving of GetAccountRange (sequential seek-based scan)
  *   - Efficient state iteration for eth_getBalance, eth_getProof
  *
  * The MPT-based state trie is still maintained for Merkle proof generation and
  * state root computation. Flat storage is a read/write optimisation layer.
  */
class FlatAccountStorage(val dataSource: DataSource) extends TransactionalKeyValueStorage[ByteString, ByteString] {
  val namespace: IndexedSeq[Byte] = Namespaces.FlatAccountNamespace
  def keySerializer: ByteString => IndexedSeq[Byte] = identity
  def keyDeserializer: IndexedSeq[Byte] => ByteString = k => ByteString.fromArrayUnsafe(k.toArray)
  def valueSerializer: ByteString => IndexedSeq[Byte] = identity
  def valueDeserializer: IndexedSeq[Byte] => ByteString = v => ByteString.fromArrayUnsafe(v.toArray)

  /** Look up an account by its hashed address. O(1) RocksDB read.
    *
    * Besu reference: BonsaiFullFlatDbStrategy.getFlatAccount —
    *   storage.get(ACCOUNT_INFO_STATE, accountHash.getBytes().toArrayUnsafe())
    */
  def getAccount(addressHash: ByteString): Option[ByteString] =
    get(addressHash)

  /** Bulk write accounts in one batch (for SNAP sync and block import).
    * Returns a DataSourceBatchUpdate that must be `.commit()`ed.
    */
  def putAccountsBatch(accounts: Seq[(ByteString, ByteString)]): DataSourceBatchUpdate =
    update(Nil, accounts)

  /** Seek-based range scan starting from startHash (inclusive).
    * Returns ordered (addressHash, rlpAccount) pairs.
    *
    * Mirrors Besu's BonsaiFlatDbStrategy.accountsToPairStream —
    *   storage.streamFromKey(ACCOUNT_INFO_STATE, startKeyHash.toArrayUnsafe())
    *
    * Requires the underlying DataSource to be a RocksDbDataSource.
    */
  def seekFrom(startHash: ByteString): Stream[IO, Either[IterationError, (ByteString, ByteString)]] =
    dataSource match {
      case rdb: RocksDbDataSource =>
        rdb.seekFrom(namespace, startHash.toArray).map { result =>
          result.map { case (key, value) =>
            (ByteString.fromArrayUnsafe(key), ByteString.fromArrayUnsafe(value))
          }
        }
      case _ =>
        Stream.empty
    }

  override def storageContent: Stream[IO, Either[IterationError, (ByteString, ByteString)]] =
    dataSource.iterate(namespace).map { result =>
      result.map { case (key, value) => (ByteString.fromArrayUnsafe(key), ByteString.fromArrayUnsafe(value)) }
    }
}
