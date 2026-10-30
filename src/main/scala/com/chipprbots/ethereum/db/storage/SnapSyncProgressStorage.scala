package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import scala.collection.immutable.ArraySeq

import io.circe.*
import io.circe.parser.decode as circeDecoder
import io.circe.syntax.*

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized
import com.chipprbots.ethereum.db.dataSource.DataUpdate
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

object SnapSyncProgress:
  given encoder: Encoder[SnapSyncProgress] = Encoder.instance { sp =>
    Json.obj(
      "pivotBlock" -> sp.pivotBlock.asJson,
      "accountCursors" -> sp.accountCursors.asJson,
      "storageCursors" -> sp.storageCursors.asJson
    )
  }

  given decoder: Decoder[SnapSyncProgress] = Decoder.instance { c =>
    for
      pivotBlock <- c.downField("pivotBlock").as[Long]
      accountCursors <- c.downField("accountCursors").as[Map[String, String]]
      storageCursors <- c.downField("storageCursors").as[Map[String, String]]
    yield SnapSyncProgress(pivotBlock, accountCursors, storageCursors)
  }

/** Why an account (or a bytecode) is in the durable excluded set (spec 022 D5, data-model.md "Excluded account"). */
enum ExcludedReason(val code: Byte):
  /** Its storage tasks were abandoned at a force-complete, or given up on after repeated stale-root failures. */
  case StorageAbandoned extends ExcludedReason(1)

  /** A storage task was skipped after the maximum number of empty replies. */
  case StorageSkipped extends ExcludedReason(2)

  /** The storage trie built from the download does not hash to the account's storage root. */
  case StorageRootMismatch extends ExcludedReason(3)

  /** The bytecode download gave up on this code hash. The downloader knows the hash, not the accounts that use it. */
  case CodeMissing extends ExcludedReason(4)

object ExcludedReason:
  def fromCode(code: Byte): ExcludedReason =
    values.find(_.code == code).getOrElse(StorageAbandoned)

/** One entry of the durable excluded set. `subject` is the account hash, except under [[ExcludedReason.CodeMissing]],
  * where it is the code hash (see that case). `claimedStorageRoot` and `claimedCodeHash` are what the decision saw,
  * empty when it saw none: they are for the operator; what the entry needs at the current root is read from the account
  * leaf.
  *
  * `dormant` (accounts only): the last look found no such account at the root it looked from. The decision still stands
  * for the account's storage, and a later root can hold the account again (A to B to A, a reorg: spec 022 review hunter
  * W2), so the entry stays. It does not count against a root ([[SnapSyncProgressStorage.hasExcluded]]), and every pass
  * looks at it again.
  */
final case class ExcludedAccount(
    subject: ByteString,
    reason: ExcludedReason,
    claimedStorageRoot: ByteString = ByteString.empty,
    claimedCodeHash: ByteString = ByteString.empty,
    dormant: Boolean = false
):
  def isCodeOnly: Boolean = reason == ExcludedReason.CodeMissing

  /** The entry's place in the set: its kind byte and subject, the part of its key after the versioned prefix. */
  def cursor: ByteString =
    val kind =
      if isCodeOnly then ExcludedAccount.CodeKind
      else if dormant then ExcludedAccount.DormantKind
      else ExcludedAccount.AccountKind
    ByteString(kind) ++ subject

object ExcludedAccount:
  // In this key order: the entries that count against a root sort before the ones that do not.
  private[storage] val AccountKind: Byte = 'a'.toByte
  private[storage] val CodeKind: Byte = 'c'.toByte
  private[storage] val DormantKind: Byte = 'd'.toByte

class SnapSyncProgressStorage(val dataSource: DataSource)
    extends TransactionalKeyValueStorage[ByteString, SnapSyncProgress]:

  val namespace: IndexedSeq[Byte] = Namespaces.SnapSyncProgressNamespace

  def keySerializer: ByteString => IndexedSeq[Byte] = identity
  def keyDeserializer: IndexedSeq[Byte] => ByteString = k => ByteString.fromArrayUnsafe(k.toArray)

  def valueSerializer: SnapSyncProgress => IndexedSeq[Byte] = progress =>
    ArraySeq.unsafeWrapArray(progress.asJson.noSpaces.getBytes("UTF-8"))

  def valueDeserializer: IndexedSeq[Byte] => SnapSyncProgress = bytes =>
    circeDecoder[SnapSyncProgress](new String(bytes.toArray, "UTF-8")) match
      case Right(p)  => p
      case Left(err) => throw new RuntimeException(s"Failed to deserialize SnapSyncProgress: $err")

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
  ): Unit =
    val current = readProgress(stateRoot).getOrElse(SnapSyncProgress(0L, Map.empty, Map.empty))
    val updated = current.copy(
      storageCursors = current.storageCursors + (accountHash.toHex -> nextSlotKey.toHex)
    )
    writeProgress(stateRoot, updated)

  /** Read-modify-write account cursors and pivot block while preserving any storage cursors written concurrently by
    * StorageRangeCoordinator.
    */
  def writeAccountCursors(
      stateRoot: ByteString,
      pivotBlock: Long,
      accountCursors: Map[String, String]
  ): Unit =
    val current = readProgress(stateRoot).getOrElse(SnapSyncProgress(0L, Map.empty, Map.empty))
    val updated = current.copy(pivotBlock = pivotBlock, accountCursors = accountCursors)
    writeProgress(stateRoot, updated)

  /** Remove all SNAP progress entries across all state roots. Called on --snap-clear or full restart. */
  def clearAll(): Unit =
    val keys = storageContent.compile.toList
      .unsafeRunSync()(IORuntime.global)
      .collect { case Right((k, _)) => k }
    if keys.nonEmpty then update(keys, Nil).commit()

  // ---- spec 022 D5: the durable excluded set -------------------------------------------------------------------------
  //
  // In the app-state column family (`s`), not this store's own namespace `p`: `clearAll` and a new SNAP cycle's resume
  // wipe `p`, and the set must outlive both until each entry is complete at the current root. Keys are `SnapExcluded/1/`,
  // a kind byte and a 32-byte subject (the same reason for the column family as `SnapStorageDoneStorage`: no new one, so
  // a downgrade still opens the database; the family's string keys never start with this prefix, and it is read only by
  // this range). Each write ends with a `updateSync`: an entry is durable before anything is done on its strength, and
  // the fsync syncs the WAL up to it. Every read is a page, so no caller ever holds the whole set.

  /** Record `entries`, durably, before any action is taken on them. An entry already there is overwritten. */
  def recordExcluded(entries: Seq[ExcludedAccount]): Unit = updateExcluded(Nil, entries)

  /** Take `entries` out of the set, durably: only when they are complete at the current root. Under `pruning.mode =
    * inmemory` (Hash) a node read as present may be only in the node cache, so "complete" there holds until the cache
    * is lost: the entry is gone and a crash can take the node back. That is one of the reasons the walk stays for
    * inmemory (T051, T052).
    */
  def removeExcluded(entries: Seq[ExcludedAccount]): Unit = updateExcluded(entries, Nil)

  /** Take `remove` out of the set and put `record` in, durably. Atomic up to `ExcludedWriteBatch` keys, which covers a
    * page's moves between kinds. A larger write is batches of that size with the fsync on the last
    * (`storage-rocksdb.md`, and the exclusive lock of the data source is not held for the whole of a 200k-account
    * force-complete), `record` first: a failure or a crash part way loses no entry, and leaves what it did write;
    * writing again is idempotent.
    */
  def updateExcluded(remove: Seq[ExcludedAccount], record: Seq[ExcludedAccount]): Unit =
    import SnapSyncProgressStorage.*
    def batch(removed: Seq[ExcludedAccount], recorded: Seq[ExcludedAccount]): DataUpdate =
      DataSourceUpdateOptimized(
        Namespaces.AppStateNamespace,
        removed.map(excludedKey),
        recorded.map(e => excludedKey(e) -> excludedValue(e))
      )
    val batches: Seq[DataUpdate] =
      if remove.size + record.size <= ExcludedWriteBatch then
        if remove.isEmpty && record.isEmpty then Nil else Seq(batch(remove, record))
      else
        record.grouped(ExcludedWriteBatch).map(batch(Nil, _)).toSeq ++
          remove.grouped(ExcludedWriteBatch).map(batch(_, Nil))
    batches.dropRight(1).foreach(b => dataSource.update(Seq(b)))
    if batches.nonEmpty then dataSource.updateSync(batches.takeRight(1))

  /** Up to `limit` entries in key order, dormant ones last, starting after the entry whose [[ExcludedAccount.cursor]]
    * is `after`.
    */
  def excludedPage(after: Option[ByteString], limit: Int): Seq[ExcludedAccount] =
    import SnapSyncProgressStorage.*
    val from = after.fold(ExcludedPrefix)(c => ExcludedPrefix ++ c.toArray :+ 0.toByte)
    dataSource
      .scanRangeLimit(Namespaces.AppStateNamespace, from, ExcludedEnd, limit)
      .map { case (k, v) => excludedEntry(k, v) }
      .toSeq

  /** Whether the set holds anything that counts against a root: any entry but a dormant one. One bounded read. */
  def hasExcluded: Boolean = excludedKindPresent(ExcludedAccount.AccountKind, ExcludedAccount.DormantKind)

  /** Whether the set holds anything at all, dormant entries included. One bounded read. */
  def hasAnyExcluded: Boolean = excludedPage(None, 1).nonEmpty

  /** Whether the set holds an account-kind entry, dormant or not: an account whose storage nothing has shown complete.
    */
  def hasExcludedAccounts: Boolean =
    excludedKindPresent(ExcludedAccount.AccountKind, ExcludedAccount.CodeKind) ||
      excludedKindPresent(ExcludedAccount.DormantKind, (ExcludedAccount.DormantKind + 1).toByte)

  /** Whether the set holds a code hash the download gave up on. */
  def hasExcludedCode: Boolean = excludedKindPresent(ExcludedAccount.CodeKind, ExcludedAccount.DormantKind)

  private def excludedKindPresent(from: Byte, untilKind: Byte): Boolean =
    import SnapSyncProgressStorage.*
    dataSource
      .scanRangeLimit(Namespaces.AppStateNamespace, ExcludedPrefix :+ from, ExcludedPrefix :+ untilKind, 1)
      .nonEmpty

  /** Drop the dormant entries (one range tombstone): healing completed clean, and a pass has looked at each from the
    * root it completed at. Never at a lazy hand-off: nothing has shown that account complete there.
    */
  def clearDormantExcluded(): Unit =
    import SnapSyncProgressStorage.*
    dataSource.deleteRange(
      Namespaces.AppStateNamespace,
      ExcludedPrefix :+ ExcludedAccount.DormantKind,
      ExcludedPrefix :+ (ExcludedAccount.DormantKind + 1).toByte
    )

  /** Empty the set (one range tombstone): a SNAP cycle is starting from nothing, and may not read an earlier one's
    * decisions.
    */
  def clearExcluded(): Unit =
    dataSource.deleteRange(
      Namespaces.AppStateNamespace,
      SnapSyncProgressStorage.ExcludedPrefix,
      SnapSyncProgressStorage.ExcludedEnd
    )

object SnapSyncProgressStorage:
  private val ExcludedPrefix: Array[Byte] = "SnapExcluded/1/".getBytes(StorageStringCharset.UTF8Charset)
  // Above every kind byte, so [ExcludedPrefix, ExcludedEnd) covers the set and nothing else.
  private val ExcludedEnd: Array[Byte] = ExcludedPrefix :+ 0xff.toByte
  private val AbsentHash: Array[Byte] = new Array[Byte](32)
  // Keys in one write batch: the size `storage-rocksdb.md` names (5-10k).
  private val ExcludedWriteBatch: Int = 5_000

  private def excludedKey(e: ExcludedAccount): Array[Byte] = ExcludedPrefix ++ e.cursor.toArray

  // One reason byte and two 32-byte hashes, zero for one the decision did not see.
  private def excludedValue(e: ExcludedAccount): Array[Byte] =
    def hash(h: ByteString): Array[Byte] = if h.length == 32 then h.toArray else AbsentHash
    (e.reason.code +: hash(e.claimedStorageRoot)) ++ hash(e.claimedCodeHash)

  // The key carries what the entry needs: a value that does not parse costs the claims, never the entry.
  private def excludedEntry(key: Array[Byte], value: Array[Byte]): ExcludedAccount =
    val kind = key(ExcludedPrefix.length)
    val subject = ByteString(key.drop(ExcludedPrefix.length + 1))
    def hash(from: Int): ByteString =
      val h = value.slice(from, from + 32)
      if h.length == 32 && !h.sameElements(AbsentHash) then ByteString(h) else ByteString.empty
    if kind == ExcludedAccount.CodeKind then ExcludedAccount(subject, ExcludedReason.CodeMissing)
    else
      val reason = value.headOption.map(ExcludedReason.fromCode).filterNot(_ == ExcludedReason.CodeMissing)
      ExcludedAccount(
        subject,
        reason.getOrElse(ExcludedReason.StorageAbandoned),
        hash(1),
        hash(33),
        dormant = kind == ExcludedAccount.DormantKind
      )
