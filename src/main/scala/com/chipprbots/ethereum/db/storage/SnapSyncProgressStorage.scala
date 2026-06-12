package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import scala.collection.immutable.ArraySeq

import cats.effect.unsafe.IORuntime

import io.circe._
import io.circe.parser.{decode => circeDecoder}
import io.circe.syntax._

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

/** SNAP sync download progress persisted per state root.
  *
  * Stores both account-range cursors and storage-range cursors as a JSON blob keyed by stateRoot. On crash recovery the
  * controller reads progress for the current pivot's stateRoot and passes account cursors back to
  * AccountRangeCoordinator and storage cursors back to StorageRangeCoordinator, allowing both to resume from their last
  * committed position rather than restarting from scratch.
  *
  * Replaces AppStateStorage's plain-text SnapSyncProgress entry (namespace 's', account-only). Storage cursor
  * persistence is new — modelled after go-ethereum's saveSyncStatus/loadSyncStatus.
  */
case class SnapSyncProgress(
    pivotBlock: Long,
    accountCursors: Map[String, String], // taskLast.toHex -> nextAccountKey.toHex
    storageCursors: Map[String, String] // accountHash.toHex -> nextSlotKey.toHex
)

object SnapSyncProgress {
  implicit val encoder: Encoder[SnapSyncProgress] = Encoder.instance { sp =>
    Json.obj(
      "pivotBlock" -> sp.pivotBlock.asJson,
      "accountCursors" -> sp.accountCursors.asJson,
      "storageCursors" -> sp.storageCursors.asJson
    )
  }

  implicit val decoder: Decoder[SnapSyncProgress] = Decoder.instance { c =>
    for {
      pivotBlock <- c.downField("pivotBlock").as[Long]
      accountCursors <- c.downField("accountCursors").as[Map[String, String]]
      storageCursors <- c.downField("storageCursors").as[Map[String, String]]
    } yield SnapSyncProgress(pivotBlock, accountCursors, storageCursors)
  }
}

class SnapSyncProgressStorage(val dataSource: DataSource)
    extends TransactionalKeyValueStorage[ByteString, SnapSyncProgress] {

  val namespace: IndexedSeq[Byte] = Namespaces.SnapSyncProgressNamespace

  def keySerializer: ByteString => IndexedSeq[Byte] = identity
  def keyDeserializer: IndexedSeq[Byte] => ByteString = k => ByteString.fromArrayUnsafe(k.toArray)

  def valueSerializer: SnapSyncProgress => IndexedSeq[Byte] = progress =>
    ArraySeq.unsafeWrapArray(progress.asJson.noSpaces.getBytes("UTF-8"))

  def valueDeserializer: IndexedSeq[Byte] => SnapSyncProgress = bytes =>
    circeDecoder[SnapSyncProgress](new String(bytes.toArray, "UTF-8")) match {
      case Right(p)  => p
      case Left(err) => throw new RuntimeException(s"Failed to deserialize SnapSyncProgress: $err")
    }

  def readProgress(stateRoot: ByteString): Option[SnapSyncProgress] = get(stateRoot)

  def writeProgress(stateRoot: ByteString, progress: SnapSyncProgress): Unit =
    put(stateRoot, progress).commit()

  def clearProgress(stateRoot: ByteString): Unit = remove(stateRoot).commit()

  /** Read-modify-write a single storage cursor. Idempotent on concurrent callers — worst case on crash is a re-download
    * of one storage range, not corruption.
    */
  def writeStorageCursor(
      stateRoot: ByteString,
      accountHash: ByteString,
      nextSlotKey: ByteString
  ): Unit = {
    val current = readProgress(stateRoot).getOrElse(SnapSyncProgress(0L, Map.empty, Map.empty))
    val updated = current.copy(
      storageCursors = current.storageCursors + (accountHash.toHex -> nextSlotKey.toHex)
    )
    writeProgress(stateRoot, updated)
  }

  /** Read-modify-write account cursors and pivot block while preserving any storage cursors written concurrently by
    * StorageRangeCoordinator.
    */
  def writeAccountCursors(
      stateRoot: ByteString,
      pivotBlock: Long,
      accountCursors: Map[String, String]
  ): Unit = {
    val current = readProgress(stateRoot).getOrElse(SnapSyncProgress(0L, Map.empty, Map.empty))
    val updated = current.copy(pivotBlock = pivotBlock, accountCursors = accountCursors)
    writeProgress(stateRoot, updated)
  }

  /** Remove all SNAP progress entries across all state roots. Called on --snap-clear or full restart. */
  def clearAll(): Unit = {
    val keys = storageContent.compile.toList
      .unsafeRunSync()(IORuntime.global)
      .collect { case Right((k, _)) => k }
    if (keys.nonEmpty) update(keys, Nil).commit()
  }
}
