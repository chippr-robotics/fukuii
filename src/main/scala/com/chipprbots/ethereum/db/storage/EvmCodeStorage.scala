package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import cats.effect.IO

import fs2.Stream

import com.chipprbots.ethereum.crypto.kec256
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
      com.chipprbots.ethereum.vm.ImportProfile.codeHit(hash)
      Some(hit)
    else
      // One native-to-heap copy, no block-cache fill, and the array is wrapped, not copied twice more (`get` goes
      // through ArraySeq and `ByteString(code.toArray)`, two further copies of up to 64 KB per miss).
      val read = dataSource
        .getOptimizedNoFill(namespace, hash.toArray)
        .map(ByteString.fromArrayUnsafe)
      read.foreach { code =>
        EvmCodeStorage.shared.put(cacheKey(hash), code)
        EvmCodeStorage.sizes.put(cacheKey(hash), Integer.valueOf(code.length))
      }
      read

  /** BAL prefetch of one code: reads it (no block-cache fill) unless the execution cache already holds it, always
    * records its length, and keeps the bytes in the execution cache only while `retain(length)` says the prefetch still
    * has budget there. A code that is not retained was still read, so its file blocks are in the OS page cache for the
    * execution's own read. An absent code is reported, never cached, as in [[getForExecution]].
    */
  def prefetchForExecution(hash: CodeHash, retain: Int => Boolean): EvmCodeStorage.PrefetchOutcome =
    val key = cacheKey(hash)
    if EvmCodeStorage.shared.getOrNull(key) != null then EvmCodeStorage.PrefetchOutcome.AlreadyCached
    else
      dataSource.getOptimizedNoFill(namespace, hash.toArray) match
        case None => EvmCodeStorage.PrefetchOutcome.Absent
        case Some(bytes) =>
          val code = ByteString.fromArrayUnsafe(bytes)
          EvmCodeStorage.sizes.put(key, Integer.valueOf(code.length))
          val kept = retain(code.length)
          if kept then EvmCodeStorage.shared.put(key, code)
          EvmCodeStorage.PrefetchOutcome.Loaded(code.length, kept)

  /** The length of the code under `hash`, without loading the code when it is remembered (this cache, or the code
    * cache). Falls back to a full [[getForExecution]], so an absent code is absent here too.
    */
  def getSizeForExecution(hash: CodeHash): Option[Int] =
    val key = cacheKey(hash)
    val known = EvmCodeStorage.sizes.getOrNull(key)
    if known != null then Some(known.intValue)
    else getForExecution(hash).map(_.length)

  /** A removal through this storage evicts the code from the execution cache, so deleting code (the missing-code
    * scenarios in tests, the background audit's [[deleteBadCode]]) is seen by the very next execution read. The
    * eviction happens when the batch is built: a read that races the commit may cache the not-yet-deleted code again.
    * The audit does delete while blocks execute, and evicts again after its commit; a reader that read before the
    * commit and caches after that eviction keeps bad code cached, which is accepted: rare, on code already rare (spec
    * 022 R-5f).
    */
  override def update(toRemove: Seq[CodeHash], toUpsert: Seq[(CodeHash, Code)]) =
    toRemove.foreach { h =>
      EvmCodeStorage.shared.remove(cacheKey(h))
      EvmCodeStorage.sizes.remove(cacheKey(h))
    }
    super.update(toRemove, toUpsert)

  /** One result per hash, for a long walk: no block-cache fill and no execution cache, so what comes back is what the
    * store holds, unchecked (spec 022 T053).
    */
  def multiGetNoFill(hashes: Seq[CodeHash]): Seq[Option[Code]] =
    dataSource.multiGetOptimizedNoFill(namespace, hashes.map(_.toArray)).map(_.map(ByteString.fromArrayUnsafe))

  /** The background audit's write (spec 022 decision 1): `code` goes in only if `hash` is absent or holds bytes that
    * fail their hash. No lock: nothing counts references to code, and two writers of one hash write the same bytes. The
    * execution caches may hold the bad bytes, so they go, after the write: [[update]] evicts only what it removes.
    */
  def repairCode(hash: CodeHash, code: Code): RepairOutcome =
    def hashesTo(bytes: Array[Byte]): Boolean = java.util.Arrays.equals(kec256(bytes), hash.toArray)
    if !hashesTo(code.toArray) then RepairOutcome.Rejected
    else if dataSource.getOptimizedNoFill(namespace, hash.toArray).exists(hashesTo) then RepairOutcome.AlreadyValid
    else
      put(hash, code).commit()
      EvmCodeStorage.shared.remove(cacheKey(hash))
      EvmCodeStorage.sizes.remove(cacheKey(hash))
      RepairOutcome.Written

  /** [[StateStorage.deleteBadNode]] for code that fails its hash (spec 022 decision 15), under the same lock, so its
    * look from the best root sees no block commit part way. That lock is not what writes code on demand, so the bytes
    * are read again after the look. Code that passes its hash is never deleted.
    */
  def deleteBadCode(hash: CodeHash, stillReached: () => Boolean): BadEntryDelete =
    def goodOrGone: Boolean =
      dataSource
        .getOptimizedNoFill(namespace, hash.toArray)
        .forall(existing => java.util.Arrays.equals(kec256(existing), hash.toArray))
    ReferenceCountedStateStorage.commitLock.synchronized {
      if goodOrGone then BadEntryDelete.NoLongerBad
      else if !stillReached() then BadEntryDelete.NotReached
      else if goodOrGone then BadEntryDelete.NoLongerBad
      else
        remove(hash).commit()
        EvmCodeStorage.shared.remove(cacheKey(hash))
        EvmCodeStorage.sizes.remove(cacheKey(hash))
        BadEntryDelete.Deleted
    }

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
      code => code.length.toLong + 96,
      com.chipprbots.ethereum.utils.StateReadCacheConfig.codeCacheStrong
    )

  /** Code lengths by hash, same ownership rule as [[shared]]: only successfully read code is recorded. */
  private lazy val sizes: com.chipprbots.ethereum.utils.ByteBoundedLru[CacheKey, Integer] =
    new com.chipprbots.ethereum.utils.ByteBoundedLru[CacheKey, Integer](
      com.chipprbots.ethereum.utils.StateReadCacheConfig.codeSizeCacheBytes,
      _ => 256L,
      com.chipprbots.ethereum.utils.StateReadCacheConfig.codeSizeCacheStrong
    )

  enum PrefetchOutcome:
    case AlreadyCached
    case Absent
    case Loaded(length: Int, retained: Boolean)

  type CodeHash = ByteString
  type Code = ByteString
