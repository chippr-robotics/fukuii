package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import cats.effect.IO

import fs2.Stream

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource.IterationError
import com.chipprbots.ethereum.db.storage.EvmCodeStorage.*

/** This class is used to store the EVM Code, by using: Key: hash of the code Value: the code
  */
class EvmCodeStorage(val dataSource: DataSource) extends TransactionalKeyValueStorage[CodeHash, Code]:
  val namespace: IndexedSeq[Byte] = Namespaces.CodeNamespace
  def keySerializer: CodeHash => IndexedSeq[Byte] = identity
  def keyDeserializer: IndexedSeq[Byte] => CodeHash = k => ByteString.fromArrayUnsafe(k.toArray)
  def valueSerializer: Code => IndexedSeq[Byte] = identity
  def valueDeserializer: IndexedSeq[Byte] => Code = (code: IndexedSeq[Byte]) => ByteString(code.toArray)

  /** Code read by the EVM, from a bounded cache of what this storage returned earlier. Code is immutable under its
    * hash, and only a successful read is remembered, so an absent code is still reported as absent every time (the
    * missing-code checks and SNAP recovery use [[get]], which never consults the cache).
    */
  private val cacheOwner = EvmCodeStorage.owners.incrementAndGet()
  private def cacheKey(hash: CodeHash) = EvmCodeStorage.CacheKey(cacheOwner, hash)

  def getForExecution(hash: CodeHash): Option[Code] =
    val hit = EvmCodeStorage.shared.getOrNull(cacheKey(hash))
    if hit != null then
      com.chipprbots.ethereum.vm.ImportProfile.codeHit()
      Some(hit)
    else
      val read = get(hash)
      read.foreach(code => EvmCodeStorage.shared.put(cacheKey(hash), code))
      read

  /** A removal through this storage evicts the code from the execution cache, so deleting code (the missing-code
    * scenarios in tests, a future repair path) is seen by the very next execution read. The eviction happens when the
    * batch is built: a read that races the commit may cache the not-yet-deleted code again, which only matters to
    * something that deletes code while executing blocks, and nothing does.
    */
  override def update(toRemove: Seq[CodeHash], toUpsert: Seq[(CodeHash, Code)]) =
    toRemove.foreach(h => EvmCodeStorage.shared.remove(cacheKey(h)))
    super.update(toRemove, toUpsert)

  // overriding to avoid going through IndexedSeq[Byte]
  override def storageContent: Stream[IO, Either[IterationError, (CodeHash, Code)]] =
    dataSource.iterate(namespace).map { result =>
      result.map { case (key, value) => (ByteString.fromArrayUnsafe(key), ByteString.fromArrayUnsafe(value)) }
    }

object EvmCodeStorage:
  /** A cached code belongs to the storage that read it, so one database's code never answers for another's (a missing
    * code must stay missing for the storage that lacks it, however many storages share the cache).
    */
  final private case class CacheKey(owner: Long, hash: ByteString)
  private val owners = new java.util.concurrent.atomic.AtomicLong

  /** One code cache for the whole process, bounded in bytes whatever the number of storages. */
  private lazy val shared: com.chipprbots.ethereum.utils.ByteBoundedLru[CacheKey, ByteString] =
    new com.chipprbots.ethereum.utils.ByteBoundedLru[CacheKey, ByteString](
      com.chipprbots.ethereum.utils.StateReadCacheConfig.codeCacheBytes,
      code => code.length.toLong + 96
    )

  type CodeHash = ByteString
  type Code = ByteString
