package com.chipprbots.ethereum.db.storage

import java.nio.ByteBuffer

import org.apache.pekko.util.ByteString

import scala.collection.immutable.ArraySeq

import boopickle.CompositePickler
import boopickle.Default.*

import com.chipprbots.ethereum.blockchain.sync.fast.FastSync.*
import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.domain.BloomFilter
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.utils.ByteUtils.compactPickledBytes

object FastSyncStateStorage:

  val syncStateKey: String = "fast-sync-state"

class FastSyncStateStorage(val dataSource: DataSource) extends KeyValueStorage[String, SyncState, FastSyncStateStorage]:
  type T = FastSyncStateStorage

  import FastSyncStateStorage.*

  override val namespace: IndexedSeq[Byte] = Namespaces.FastSyncStateNamespace

  given blockNumberPickler: Pickler[BlockNumber] =
    transformPickler[BlockNumber, BigInt](BlockNumber(_))(_.value)
  given difficultyPickler: Pickler[Difficulty] =
    transformPickler[Difficulty, BigInt](Difficulty(_))(_.value)
  given gasAmountPickler: Pickler[GasAmount] =
    transformPickler[GasAmount, BigInt](GasAmount(_))(_.value)
  given timestampPickler: Pickler[Timestamp] =
    transformPickler[Timestamp, Long](Timestamp(_))(_.toLong)
  given byteStringPickler: Pickler[ByteString] =
    transformPickler[ByteString, Array[Byte]](ByteString(_))(_.toArray[Byte])
  given bloomFilterPickler: Pickler[BloomFilter] =
    transformPickler[BloomFilter, Array[Byte]](arr => BloomFilter.fromArray(arr))(_.toArray)
  given blockHashPickler: Pickler[BlockHash] =
    transformPickler[BlockHash, Array[Byte]](arr => BlockHash(ByteString(arr)))(_.toArray)
  given trieRootPickler: Pickler[TrieRoot] =
    transformPickler[TrieRoot, Array[Byte]](arr => TrieRoot(ByteString(arr)))(_.toArray)

  given headerExtraFieldsPickler: CompositePickler[HeaderExtraFields] =
    compositePickler[HeaderExtraFields]
      .addConcreteType[HefEmpty.type]
      .addConcreteType[HefPostOlympia]
      .addConcreteType[HefPostShanghai]
      .addConcreteType[HefPostCancun]
      .addConcreteType[HefPostPrague]
      // Appended last on purpose: boopickle assigns concrete-type indices in registration
      // order, so appending leaves every already-persisted fast-sync record decodable.
      .addConcreteType[HefPostAmsterdam]

  given hashTypePickler: CompositePickler[HashType] =
    compositePickler[HashType]
      .addConcreteType[StateMptNodeHash]
      .addConcreteType[ContractStorageMptNodeHash]
      .addConcreteType[EvmCodeHash]
      .addConcreteType[StorageRootHash]

  override def keySerializer: String => IndexedSeq[Byte] = k =>
    ArraySeq.unsafeWrapArray(k.getBytes(StorageStringCharset.UTF8Charset))

  override def keyDeserializer: IndexedSeq[Byte] => String = b =>
    new String(b.toArray, StorageStringCharset.UTF8Charset)

  override def valueSerializer: SyncState => IndexedSeq[Byte] = ss => compactPickledBytes(Pickle.intoBytes(ss))

  override def valueDeserializer: IndexedSeq[Byte] => SyncState =
    (bytes: IndexedSeq[Byte]) => Unpickle[SyncState].fromBytes(ByteBuffer.wrap(bytes.toArray[Byte]))

  protected def apply(dataSource: DataSource): FastSyncStateStorage = new FastSyncStateStorage(dataSource)

  def putSyncState(syncState: SyncState): FastSyncStateStorage = put(syncStateKey, syncState)

  def getSyncState(): Option[SyncState] = get(syncStateKey)

  def purge(): FastSyncStateStorage = remove(syncStateKey)
