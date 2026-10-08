package com.chipprbots.ethereum.blockchain.sync.snap

import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized
import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BfsQueueStorage
import com.chipprbots.ethereum.db.storage.HealingFrontierStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.RecoveryProgress
import com.chipprbots.ethereum.db.storage.RocksDbBfsQueueStorage
import com.chipprbots.ethereum.db.storage.SnapStorageDoneStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgress
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.network.SnapGoodPeers
import com.chipprbots.ethereum.network.SnapGoodPeersConfig
import com.chipprbots.ethereum.utils.Hex

/** Golden-bytes vectors for every SNAP on-disk format that spec 016 freezes (research.md R8, task T018, slice S0c).
  *
  * The existing specs round-trip these formats through the encoder they test, so a change made symmetrically to an
  * encoder and its decoder passes them. This suite does not: every format is pinned to hex literals in both directions
  * — encode(value) == golden and decode(golden) == value — and, for the key-value formats, to the exact column family
  * and key the production storage class writes, on a real RocksDB.
  *
  * The vectors were hand-derived from the encoders on post-#1501 staging (1d667bf32), then confirmed in CI. T001b ran
  * this same file, unchanged, against pre-#1501 staging (9a6681cc0). The S0c PR records both run links.
  *
  * DO NOT EDIT A VECTOR. A failure here means an on-disk format changed: a node upgraded across the change would
  * misread (or silently discard) its resume state. If the change is intended, it needs a version bump and a migration,
  * not a new golden.
  */
class SnapFrozenFormatsGoldenSpec extends AnyFlatSpec with Matchers:
  import SnapFrozenFormatsGoldenSpec.*

  // ---------------------------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------------------------

  private def hex(bytes: Array[Byte]): String = Hex.toHexString(bytes)
  private def hexOf(bytes: ByteString): String = Hex.toHexString(bytes.toArray)
  private def unhex(golden: String): Array[Byte] = if golden.isEmpty then Array.emptyByteArray else Hex.decode(golden)
  private def bs(golden: String): ByteString = ByteString(unhex(golden))
  private def utf8(golden: String): String = new String(unhex(golden), UTF_8)
  private def utf8Hex(text: String): String = hex(text.getBytes(UTF_8))

  /** 32 bytes `start, start+1, ...` (wrapping): a value with no repeated bytes, so a shifted or swapped field shows. */
  private def tab(start: Int): ByteString = ByteString(Array.tabulate(32)(j => (start + j).toByte))
  private def fill(b: Int, n: Int = 32): ByteString = ByteString(Array.fill(n)(b.toByte))

  private val AppStateNs = Namespaces.AppStateNamespace
  private val ProgressNs = Namespaces.SnapSyncProgressNamespace
  private val FrontierNs = Namespaces.HealingFrontierNamespace
  private val BfsNs = Namespaces.BfsQueueNamespace

  /** The raw value the store holds under `(namespace, key)`, as hex. */
  private def raw(ds: DataSource, namespace: IndexedSeq[Byte], keyHex: String): Option[String] =
    ds.getOptimized(namespace, unhex(keyHex)).map(hex)

  /** Write golden `(keyHex, valueHex)` pairs straight into the store, bypassing every serializer. */
  private def putRaw(ds: DataSource, namespace: IndexedSeq[Byte], entries: (String, String)*): Unit =
    ds.update(Seq(DataSourceUpdateOptimized(namespace, Nil, entries.map { case (k, v) => unhex(k) -> unhex(v) })))

  private def withRocksDb(test: DataSource => Unit): Unit =
    val dbPath = Files.createTempDirectory("snap-golden-rocksdb").toAbsolutePath.toString
    val dataSource = RocksDbDataSource(
      new RocksDbConfig:
        override val createIfMissing: Boolean = true
        override val paranoidChecks: Boolean = true
        override val path: String = dbPath
        override val maxThreads: Int = 1
        override val maxOpenFiles: Int = 32
        override val verifyChecksums: Boolean = true
        override val levelCompaction: Boolean = true
        override val blockSize: Long = 16384
        override val blockCacheSize: Long = 33554432
      ,
      Namespaces.nsSeq
    )
    try test(dataSource)
    finally
      dataSource.destroy()
      val dir = new File(dbPath)
      !dir.exists() || dir.delete()

  private def withTempDir(test: Path => Unit): Unit =
    val dir = Files.createTempDirectory("snap-golden-files")
    try test(dir)
    finally
      Option(dir.toFile.listFiles()).foreach(_.foreach(_.delete()))
      dir.toFile.delete()

  // ---------------------------------------------------------------------------------------------------------------
  // Values (built field by field; never from a golden)
  // ---------------------------------------------------------------------------------------------------------------

  private val EmptyRoot = bs("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421")
  private val EmptyCodeHash = bs("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470")

  private val StoragePath = "/data/fukuii/snap/fukuii-contract-storage-1234.bin"
  private val CodeHashesPath = "/data/fukuii/snap/fukuii-unique-codehashes-5678.bin"

  // (range `last`, `next` cursor): a partial range, a complete one (next == last) and the top range.
  private val Cursor1 = ("3f" + "ff" * 31, "20" + "00" * 30 + "01")
  private val Cursor2 = ("7f" + "ff" * 31, "7f" + "ff" * 31)
  private val Cursor3 = ("ff" * 32, "c0" + "00" * 31)

  private val FullCheckpoint = AccountResumeCheckpoint(
    stateRoot = tab(0x00),
    pivotBlock = BigInt("123456789012345678901234567890"), // wider than a Long: pivotBlock is a decimal JSON string
    cursors = Seq(Cursor1, Cursor2, Cursor3).map { case (l, n) => bs(l) -> bs(n) }.toMap,
    taskFiles = ContractTaskFiles(StoragePath, 3L, CodeHashesPath, 2L)
  )
  private val EmptyCheckpoint =
    AccountResumeCheckpoint(fill(0x00), BigInt(0), Map.empty, ContractTaskFiles("", 0L, "", 0L))
  private val MaxCheckpoint = AccountResumeCheckpoint(
    stateRoot = fill(0xff),
    pivotBlock = BigInt(Long.MaxValue) + 1,
    cursors = Map(fill(0x00) -> fill(0xff)),
    // Paths are free text: JSON escapes for '\' and '"', raw UTF-8 for non-ASCII.
    taskFiles = ContractTaskFiles("C:\\fukuii\\snap\\\u00e9 \u00fc.bin", Long.MaxValue, "/tmp/\"q\".bin", Long.MaxValue)
  )

  private val FullProgress = SnapSyncProgress(
    pivotBlock = 4242L,
    accountCursors = Map(Cursor1, Cursor2),
    storageCursors = Map(hexOf(tab(0x40)) -> ("00" * 31 + "80"), "ee" * 32 -> "ff" * 32)
  )
  private val EmptyProgress = SnapSyncProgress(0L, Map.empty, Map.empty)
  private val MaxPivotProgress = SnapSyncProgress(Long.MaxValue, Map.empty, Map.empty)

  private val DoneMarkers = Seq(tab(0x40) -> tab(0x60), fill(0xee) -> EmptyRoot)

  // The last entry is a real "nothing to download" task (empty storage root) — the file still carries it.
  private val StorageTasks = Seq(tab(0x40) -> tab(0x60), fill(0xee) -> fill(0x56), tab(0xa0) -> EmptyRoot)
  private val CodeHashes = Seq(EmptyCodeHash, tab(0xd0))

  // node hash -> pathset: account node (2-byte path), storage node (33-byte account path + 1-byte path, which RLP
  // encodes as the bare byte), empty pathset, and an empty segment plus a 56-byte one (RLP long-form string and list).
  private val FrontierEntries: Seq[(ByteString, Seq[ByteString])] = Seq(
    tab(0x20) -> Seq(bs("1a2b")),
    tab(0x80) -> Seq(ByteString(0x00.toByte) ++ fill(0xcc), bs("35")),
    fill(0x00) -> Seq.empty,
    fill(0xff) -> Seq(ByteString.empty, fill(0x77, 56))
  )

  // (hash, pathset, isStorage); entry 3 has a 256-byte segment, so its 2-byte length has a non-zero high byte.
  private def bfsEntries: Seq[(Array[Byte], Seq[Array[Byte]], Boolean)] = Seq(
    (tab(0x80).toArray, Seq(unhex("1a2b")), false),
    (fill(0xee).toArray, Seq((ByteString(0x00.toByte) ++ fill(0xcc)).toArray, unhex("35")), true),
    (fill(0x00).toArray, Seq.empty, true),
    (tab(0x20).toArray, Seq(fill(0x5c, 256).toArray), false)
  )
  private def bfsView(
      hash: Array[Byte],
      pathset: Seq[Array[Byte]],
      isStorage: Boolean
  ): (String, Seq[String], Boolean) =
    (hex(hash), pathset.map(hex), isStorage)

  private val LegacyRanges: Map[ByteString, ByteString] = Map(Cursor1, Cursor2).map { case (l, n) => bs(l) -> bs(n) }

  private val FullRecovery = RecoveryProgress(
    scanRoot = tab(0x00),
    shardCount = 4,
    completedShards = Set(2, 0),
    missingBytecodes = Vector(EmptyCodeHash, tab(0xd0)),
    missingStorageTries = Vector(tab(0x40) -> tab(0x60))
  )
  private val EmptyRecovery = RecoveryProgress(tab(0x00), 1, Set.empty, Vector.empty, Vector.empty)

  private val PeerA = "aa" * 64
  private val PeerB = "0123456789abcdef" * 8
  private val PeerC = "ff" * 64
  private def goodPeersConfig(dir: Path): SnapGoodPeersConfig = SnapGoodPeersConfig(
    enabled = true,
    maxEntries = 16,
    halfLife = 12.hours,
    maxFailedDials = 10,
    maxAge = 7.days,
    redialInterval = 5.minutes,
    file = dir.resolve("snap-good-peers.v1")
  )

  /** An instance of `cls` made without running any constructor (what Objenesis does, without the dependency). */
  private def constructorlessInstance(cls: Class[?]): AnyRef =
    val factoryClass = Class.forName("sun.reflect.ReflectionFactory")
    val factory = factoryClass.getMethod("getReflectionFactory").invoke(null)
    val constructor = factoryClass
      .getMethod("newConstructorForSerialization", classOf[Class[?]], classOf[Constructor[?]])
      .invoke(factory, cls, classOf[Object].getDeclaredConstructor())
      .asInstanceOf[Constructor[?]]
    constructor.newInstance().asInstanceOf[AnyRef]

  /** `SNAPSyncControllerImpl.deserializeSnapProgress` — the migration-only parser for the legacy plain-text progress.
    * It is private and has no encoder, so it is reached by reflection: the declared method is looked up by name through
    * the class hierarchy (so it is still found if a split slice moves it into a mixed-in module trait) and invoked on a
    * constructor-less instance (the method reads no instance state).
    */
  private def deserializeLegacy(data: String): Option[(BigInt, Map[ByteString, ByteString])] =
    val impl = classOf[SNAPSyncControllerImpl]
    def hierarchy(c: Class[?]): Seq[Class[?]] =
      if c == null then Nil else c +: (hierarchy(c.getSuperclass) ++ c.getInterfaces.toSeq.flatMap(hierarchy))
    val method = hierarchy(impl).iterator
      .flatMap(_.getDeclaredMethods)
      .find(m => m.getName.endsWith("deserializeSnapProgress") && m.getParameterTypes.toSeq == Seq(classOf[String]))
      .getOrElse(fail("deserializeSnapProgress(String) not found on SNAPSyncControllerImpl or its supertypes"))
    method.setAccessible(true)
    val target = if Modifier.isStatic(method.getModifiers) then null else constructorlessInstance(impl)
    method.invoke(target, data).asInstanceOf[Option[(BigInt, Map[ByteString, ByteString])]]

  // ---------------------------------------------------------------------------------------------------------------
  // Column families
  // ---------------------------------------------------------------------------------------------------------------

  "The SNAP column families" should "keep their one-byte names" in {
    hex(AppStateNs.toArray) shouldBe "73" // 's': AppStateStorage, the done-markers and the checkpoint
    hex(ProgressNs.toArray) shouldBe "70" // 'p': SnapSyncProgressStorage
    hex(FrontierNs.toArray) shouldBe "67" // 'g': HealingFrontierStorage
    hex(BfsNs.toArray) shouldBe "71" // 'q': BfsQueueStorage
  }

  // ---------------------------------------------------------------------------------------------------------------
  // AccountResumeCheckpoint v1 (JSON under AppStateStorage key "SnapAccountResumeCheckpoint")
  // ---------------------------------------------------------------------------------------------------------------

  "AccountResumeCheckpoint v1" should "encode to the golden bytes" in {
    AccountResumeCheckpoint.CurrentVersion shouldBe 1
    utf8Hex(AccountResumeCheckpoint.encode(FullCheckpoint)) shouldBe CheckpointFull
    utf8Hex(AccountResumeCheckpoint.encode(EmptyCheckpoint)) shouldBe CheckpointEmpty
    utf8Hex(AccountResumeCheckpoint.encode(MaxCheckpoint)) shouldBe CheckpointMax
  }

  it should "decode the golden bytes" in {
    AccountResumeCheckpoint.decode(utf8(CheckpointFull)) shouldBe Right(FullCheckpoint)
    AccountResumeCheckpoint.decode(utf8(CheckpointEmpty)) shouldBe Right(EmptyCheckpoint)
    AccountResumeCheckpoint.decode(utf8(CheckpointMax)) shouldBe Right(MaxCheckpoint)
    AccountResumeCheckpoint.decode(utf8(CheckpointVersion2)) shouldBe Left("unsupported version 2")
  }

  it should "live under the golden AppStateStorage key" in withRocksDb { ds =>
    new AppStateStorage(ds).putSnapAccountResumeCheckpoint(AccountResumeCheckpoint.encode(FullCheckpoint)).commit()
    raw(ds, AppStateNs, KeyAccountResumeCheckpoint) shouldBe Some(CheckpointFull)
  }

  it should "read back from golden bytes written raw" in withRocksDb { ds =>
    putRaw(ds, AppStateNs, KeyAccountResumeCheckpoint -> CheckpointFull)
    new AppStateStorage(ds).getSnapAccountResumeCheckpoint().map(AccountResumeCheckpoint.decode) shouldBe
      Some(Right(FullCheckpoint))
  }

  // ---------------------------------------------------------------------------------------------------------------
  // SnapSyncProgressStorage (CF 'p', key = state root, value = JSON account + storage cursors)
  // ---------------------------------------------------------------------------------------------------------------

  "SnapSyncProgressStorage" should "write the golden keys and values" in withRocksDb { ds =>
    val storage = new SnapSyncProgressStorage(ds)
    // The production write paths: account cursors, then two storage cursors read-modify-written on top.
    storage.writeAccountCursors(tab(0x00), 4242L, Map(Cursor1, Cursor2))
    storage.writeStorageCursor(tab(0x00), tab(0x40), bs("00" * 31 + "80"))
    storage.writeStorageCursor(tab(0x00), fill(0xee), fill(0xff))
    storage.writeProgress(fill(0xff), EmptyProgress)
    storage.writeProgress(fill(0x80), MaxPivotProgress)
    raw(ds, ProgressNs, hexOf(tab(0x00))) shouldBe Some(ProgressFull)
    raw(ds, ProgressNs, "ff" * 32) shouldBe Some(ProgressEmpty)
    raw(ds, ProgressNs, "80" * 32) shouldBe Some(ProgressMaxPivot)
  }

  it should "read the golden bytes back" in withRocksDb { ds =>
    putRaw(
      ds,
      ProgressNs,
      hexOf(tab(0x00)) -> ProgressFull,
      "ff" * 32 -> ProgressEmpty,
      "80" * 32 -> ProgressMaxPivot
    )
    val storage = new SnapSyncProgressStorage(ds)
    storage.readProgress(tab(0x00)) shouldBe Some(FullProgress)
    storage.readProgress(fill(0xff)) shouldBe Some(EmptyProgress)
    storage.readProgress(fill(0x80)) shouldBe Some(MaxPivotProgress)
  }

  // ---------------------------------------------------------------------------------------------------------------
  // SnapStorageDone/ markers (CF 's', key = "SnapStorageDone/" ++ accountHash ++ storageRoot, value 0x01)
  // ---------------------------------------------------------------------------------------------------------------

  "SnapStorageDone markers" should "write the golden keys with value 0x01" in withRocksDb { ds =>
    new SnapStorageDoneStorage(ds).markDone(DoneMarkers).commit()
    raw(ds, AppStateNs, DoneKey1) shouldBe Some("01")
    raw(ds, AppStateNs, DoneKey2) shouldBe Some("01")
  }

  it should "honour golden markers written raw, and clear only the marker key range" in withRocksDb { ds =>
    putRaw(ds, AppStateNs, DoneKey1 -> "01", DoneKey2 -> "01")
    val appState = new AppStateStorage(ds)
    appState.snapSyncDone().commit()
    val done = new SnapStorageDoneStorage(ds)
    done.isDone(tab(0x40), tab(0x60)) shouldBe true
    done.isDone(fill(0xee), EmptyRoot) shouldBe true
    done.isDone(tab(0x40), EmptyRoot) shouldBe false
    done.unfinished(DoneMarkers :+ (fill(0xee) -> tab(0x60)))(identity) shouldBe Seq(fill(0xee) -> tab(0x60))
    done.clear()
    raw(ds, AppStateNs, DoneKey1) shouldBe None
    raw(ds, AppStateNs, DoneKey2) shouldBe None
    appState.isSnapSyncDone() shouldBe true // a neighbouring app-state key survives the range delete
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Contract task files: StorageTaskFile 64-byte entries, 32-byte codeHash entries
  // ---------------------------------------------------------------------------------------------------------------

  "StorageTaskFile entries" should "have the golden layout and location" in {
    StorageTaskFile.EntrySize shouldBe 64
    StorageTaskFile.CodeHashEntrySize shouldBe 32
    StorageTaskFile.DirName shouldBe "snap"
    StorageTaskFile.dirUnder(Path.of("/data/fukuii")) shouldBe Path.of("/data/fukuii/snap")
    // encode: accountHash ++ storageRoot per entry, codeHash per entry, no header, no separator
    hex(StorageTasks.flatMap { case (account, root) => account ++ root }.toArray) shouldBe StorageTaskFileBytes
    hex(CodeHashes.flatten.toArray) shouldBe CodeHashFileBytes
    // decode
    unhex(StorageTaskFileBytes)
      .grouped(StorageTaskFile.EntrySize)
      .map(e => ByteString(e.take(32)) -> ByteString(e.drop(32)))
      .toSeq shouldBe StorageTasks
    unhex(CodeHashFileBytes).grouped(StorageTaskFile.CodeHashEntrySize).map(ByteString(_)).toSeq shouldBe CodeHashes
  }

  it should "pass the production usability checks at exactly the golden size" in withTempDir { dir =>
    val storage = dir.resolve("storage.bin")
    val code = dir.resolve("code.bin")
    val truncated = dir.resolve("truncated.bin")
    Files.write(storage, unhex(StorageTaskFileBytes))
    Files.write(code, unhex(CodeHashFileBytes))
    Files.write(truncated, unhex(StorageTaskFileBytes).dropRight(1))
    StorageTaskFile.isUsable(storage, StorageTaskFile.EntrySize, Some(3L)) shouldBe true
    StorageTaskFile.isUsable(storage, StorageTaskFile.EntrySize, Some(2L)) shouldBe false
    StorageTaskFile.isUsable(storage) shouldBe true
    StorageTaskFile.isUsable(truncated) shouldBe false
    StorageTaskFile.isUsable(code, StorageTaskFile.CodeHashEntrySize, Some(2L)) shouldBe true
    ContractTaskFiles(storage.toString, 3L, code.toString, 2L).validate() shouldBe Right(())
    ContractTaskFiles(storage.toString, 2L, code.toString, 1L).validate() shouldBe Right(()) // longer than counted
    ContractTaskFiles(storage.toString, 4L, code.toString, 2L).validate().isLeft shouldBe true
  }

  // ---------------------------------------------------------------------------------------------------------------
  // HealingFrontierStorage (CF 'g': frontier mirror hash -> RLP pathset, completeness sentinel, subtree records)
  // ---------------------------------------------------------------------------------------------------------------

  private val FrontierGoldens = Seq(FrontierValue1, FrontierValue2, FrontierValue3, FrontierValue4)

  "HealingFrontierStorage" should "write the golden frontier mirror, sentinel and subtree record" in withRocksDb { ds =>
    val storage = new HealingFrontierStorage(ds)
    FrontierEntries.foreach { case (hash, pathset) => storage.put(hash, pathset).commit() }
    storage.markComplete()
    storage.markSubtreeComplete(tab(0x20))
    FrontierEntries.zip(FrontierGoldens).foreach { case ((hash, _), golden) =>
      raw(ds, FrontierNs, hexOf(hash)) shouldBe Some(golden)
    }
    raw(ds, FrontierNs, FrontierCompleteKey) shouldBe Some(FrontierFlagValue)
    raw(ds, FrontierNs, SubtreeCompleteKey1) shouldBe Some(FrontierFlagValue)
    hexOf(HealingFrontierStorage.CompleteMarkerKey) shouldBe FrontierCompleteKey
    hexOf(HealingFrontierStorage.subtreeCompleteKey(tab(0x20))) shouldBe SubtreeCompleteKey1
  }

  it should "load the golden bytes back" in withRocksDb { ds =>
    putRaw(
      ds,
      FrontierNs,
      (FrontierEntries.map { case (hash, _) => hexOf(hash) }.zip(FrontierGoldens) ++
        Seq(FrontierCompleteKey -> FrontierFlagValue, SubtreeCompleteKey1 -> FrontierFlagValue))*
    )
    val storage = new HealingFrontierStorage(ds)
    storage.loadAll().toMap shouldBe FrontierEntries.toMap
    storage.isComplete shouldBe true
    storage.isSubtreeComplete(tab(0x20)) shouldBe true
    storage.isSubtreeComplete(tab(0x80)) shouldBe false
    storage.multiIsSubtreeComplete(Seq(tab(0x20), tab(0x80))) shouldBe Set(tab(0x20))
  }

  // ---------------------------------------------------------------------------------------------------------------
  // BfsQueueStorage (CF 'q': 8-byte big-endian counter -> [32 B hash][1 B n][{2 B len, data} x n][1 B isStorage])
  // ---------------------------------------------------------------------------------------------------------------

  private val BfsGoldens = Seq(BfsEntry0, BfsEntry1, BfsEntry2, BfsEntry3)

  "BfsQueueStorage" should "encode entries and keys to the golden bytes" in {
    bfsEntries.zip(BfsGoldens).foreach { case ((hash, pathset, isStorage), golden) =>
      hex(BfsQueueStorage.encodeEntry(hash, pathset, isStorage)) shouldBe golden
    }
    hex(BfsQueueStorage.encodeEntry(fill(0x11, 31).toArray, Seq.empty, false)) shouldBe BfsEntryShortHash // padded
    hex(BfsQueueStorage.encodeEntry(fill(0x22, 33).toArray, Seq.empty, true)) shouldBe BfsEntryLongHash // truncated
    hex(BfsQueueStorage.longToBytes(0L)) shouldBe "0000000000000000"
    hex(BfsQueueStorage.longToBytes(256L)) shouldBe "0000000000000100"
    hex(BfsQueueStorage.longToBytes(Long.MaxValue)) shouldBe "7fffffffffffffff"
  }

  it should "decode the golden bytes" in {
    bfsEntries.zip(BfsGoldens).foreach { case ((hash, pathset, isStorage), golden) =>
      val e = BfsQueueStorage.decodeEntry(unhex(golden))
      bfsView(e.hash, e.pathset, e.isStorage) shouldBe bfsView(hash, pathset, isStorage)
    }
    val short = BfsQueueStorage.decodeEntry(unhex(BfsEntryShortHash))
    bfsView(short.hash, short.pathset, short.isStorage) shouldBe ("11" * 31 + "00", Seq.empty, false)
  }

  it should "store entries under the golden counter keys" in withRocksDb { ds =>
    new RocksDbBfsQueueStorage(ds, BfsNs).enqueueBatch(bfsEntries)
    BfsGoldens.zipWithIndex.foreach { case (golden, i) => raw(ds, BfsNs, "%016x".format(i)) shouldBe Some(golden) }
  }

  it should "iterate golden bytes written raw" in withRocksDb { ds =>
    putRaw(ds, BfsNs, (BfsGoldens.zipWithIndex.map { case (golden, i) => "%016x".format(i) -> golden })*)
    val read = new RocksDbBfsQueueStorage(ds, BfsNs).iterateRange(0L, 4L).toSeq.flatten
    read.map(e => bfsView(e.hash, e.pathset, e.isStorage)) shouldBe bfsEntries.map { case (h, p, s) =>
      bfsView(h, p, s)
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Legacy plain-text progress (AppStateStorage key "SnapSyncProgress"; read-only migration fallback)
  // ---------------------------------------------------------------------------------------------------------------

  "The legacy SnapSyncProgress string" should "be stored verbatim under the golden key" in withRocksDb { ds =>
    // The golden is what the removed writer (serializeSnapProgress, fd2b6317b) produced: one "k=v\n" line each.
    val appState = new AppStateStorage(ds)
    appState.putSnapSyncProgress(utf8(LegacyProgress)).commit()
    raw(ds, AppStateNs, KeySnapSyncProgress) shouldBe Some(LegacyProgress)
  }

  it should "be read back from golden bytes and parsed by deserializeSnapProgress" in withRocksDb { ds =>
    putRaw(ds, AppStateNs, KeySnapSyncProgress -> LegacyProgress)
    val text = new AppStateStorage(ds).getSnapSyncProgress()
    text shouldBe Some(utf8(LegacyProgress))
    deserializeLegacy(text.get) shouldBe Some((BigInt(4242), LegacyRanges))
    deserializeLegacy("pivotBlock=1") shouldBe Some((BigInt(1), Map.empty))
    deserializeLegacy("") shouldBe None
    deserializeLegacy(Cursor1._1 + "=" + Cursor1._2 + "\n") shouldBe None // no pivot line
    deserializeLegacy("pivotBlock=1\nzz=00\n") shouldBe None // bad hex
  }

  // ---------------------------------------------------------------------------------------------------------------
  // AppStateStorage SNAP phase flags and anchors (UTF-8 key name -> UTF-8 value)
  // ---------------------------------------------------------------------------------------------------------------

  "The AppStateStorage SNAP flags and anchors" should "write the golden key and value bytes" in withRocksDb { ds =>
    val appState = new AppStateStorage(ds)
    Seq(
      appState.snapSyncDone(),
      appState.putSnapSyncPivotBlock(BigInt("98765432109876543210")),
      appState.putSnapSyncMinPivotBlock(BigInt(7)),
      appState.putSnapSyncStateRoot(tab(0x00)),
      appState.putSnapSyncBootstrapTarget(BigInt(1234567)),
      appState.putSnapSyncAccountsComplete(true),
      appState.putSnapSyncStorageComplete(false),
      appState.putSnapSyncBytecodeComplete(true),
      appState.putSnapSyncCodeHashesPath(CodeHashesPath),
      appState.putSnapSyncStorageFilePath(StoragePath),
      appState.putSnapSyncCodeHashesCount(Some(2L)),
      appState.putSnapSyncStorageFileCount(None), // stored as the empty string
      appState.putSnapSyncFinalizedRoot(fill(0xff)),
      appState.bytecodeRecoveryDone(),
      appState.clearBytecodeRecoveryDone(), // writes "false", it does not remove the key
      appState.storageRecoveryDone(),
      appState.putBulkBytecodeRecoveryFailures(3)
    ).foreach(_.commit())
    AppStateAnchors.foreach { case (key, value) =>
      withClue(utf8(key)) {
        raw(ds, AppStateNs, key) shouldBe Some(value)
      }
    }
  }

  it should "read the golden bytes back through the getters" in withRocksDb { ds =>
    putRaw(ds, AppStateNs, AppStateAnchors*)
    val appState = new AppStateStorage(ds)
    appState.isSnapSyncDone() shouldBe true
    appState.getSnapSyncPivotBlock() shouldBe Some(BigInt("98765432109876543210"))
    appState.getSnapSyncMinPivotBlock() shouldBe Some(BigInt(7))
    appState.getSnapSyncStateRoot() shouldBe Some(tab(0x00))
    appState.getSnapSyncBootstrapTarget() shouldBe Some(BigInt(1234567))
    appState.isSnapSyncAccountsComplete() shouldBe true
    appState.isSnapSyncStorageComplete() shouldBe false
    appState.isSnapSyncBytecodeComplete() shouldBe true
    appState.getSnapSyncCodeHashesPath() shouldBe Some(CodeHashesPath)
    appState.getSnapSyncStorageFilePath() shouldBe Some(StoragePath)
    appState.getSnapSyncCodeHashesCount() shouldBe Some(2L)
    appState.getSnapSyncStorageFileCount() shouldBe None
    appState.getSnapSyncFinalizedRoot() shouldBe Some(fill(0xff))
    appState.isBytecodeRecoveryDone() shouldBe false
    appState.isStorageRecoveryDone() shouldBe true
    appState.bulkBytecodeRecoveryFailures() shouldBe 3
  }

  // ---------------------------------------------------------------------------------------------------------------
  // RecoveryProgress v1 (post-SNAP recovery scan checkpoint, AppStateStorage key "RecoveryProgress")
  // ---------------------------------------------------------------------------------------------------------------

  "RecoveryProgress v1" should "encode to and decode from the golden bytes" in {
    utf8Hex(RecoveryProgress.serialize(FullRecovery)) shouldBe RecoveryProgressFull
    utf8Hex(RecoveryProgress.serialize(EmptyRecovery)) shouldBe RecoveryProgressEmpty
    RecoveryProgress.deserialize(utf8(RecoveryProgressFull)) shouldBe Some(FullRecovery)
    RecoveryProgress.deserialize(utf8(RecoveryProgressEmpty)) shouldBe Some(EmptyRecovery)
  }

  it should "live under the golden AppStateStorage key" in withRocksDb { ds =>
    new AppStateStorage(ds).putRecoveryProgress(FullRecovery).commit()
    raw(ds, AppStateNs, KeyRecoveryProgress) shouldBe Some(RecoveryProgressFull)
    putRaw(ds, AppStateNs, KeyRecoveryProgress -> RecoveryProgressEmpty)
    new AppStateStorage(ds).getRecoveryProgress() shouldBe Some(EmptyRecovery)
  }

  // ---------------------------------------------------------------------------------------------------------------
  // snap-good-peers.v1 (network layer; listed in R8 as an inertness oracle)
  // ---------------------------------------------------------------------------------------------------------------

  "snap-good-peers.v1" should "save the golden file" in withTempDir { dir =>
    val peers = new SnapGoodPeers(goodPeersConfig(dir))
    peers.recordServed(PeerA, "10.0.0.1", 30303, 1.5, 1000L)
    peers.recordServed(PeerB, "2001:db8::1", 30304, 2.0, 2000L)
    peers.recordServed(PeerC, "seed.example.org", 65535, 0.25, 3000L)
    peers.recordDialAttempt(PeerA)
    peers.recordDialAttempt(PeerA)
    peers.save()
    SnapGoodPeers.Header shouldBe "fukuii-snap-good-peers v1"
    hex(Files.readAllBytes(dir.resolve("snap-good-peers.v1"))) shouldBe GoodPeersFile
  }

  it should "load the golden file" in withTempDir { dir =>
    Files.write(dir.resolve("snap-good-peers.v1"), unhex(GoodPeersFile))
    val peers = new SnapGoodPeers(goodPeersConfig(dir))
    peers.load(3000L)
    peers.size shouldBe 3
    utf8Hex(peers.render) shouldBe GoodPeersFile
    peers.failedDials(PeerA) shouldBe Some(2)
    peers.failedDials(PeerB) shouldBe Some(0)
    peers.decayedScore(PeerC, 3000L) shouldBe Some(0.25)
    peers.candidates(3000L).map(_._2.toString) shouldBe Seq(
      s"enode://$PeerB@[2001:db8::1]:30304",
      s"enode://$PeerA@10.0.0.1:30303",
      s"enode://$PeerC@seed.example.org:65535"
    )
  }

object SnapFrozenFormatsGoldenSpec:

  // UTF-8 key names in CF 's'.
  val KeyAccountResumeCheckpoint: String = "536e61704163636f756e74526573756d65436865636b706f696e74"
  val KeySnapSyncProgress: String = "536e617053796e6350726f6772657373"
  val KeyRecoveryProgress: String = "5265636f7665727950726f6772657373"

  // {"version":1,"stateRoot":"000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f","pivo...
  val CheckpointFull: String =
    "7b2276657273696f6e223a312c227374617465526f6f74223a2230303031303230333034303530363037303830393061" +
      "306230633064306530663130313131323133313431353136313731383139316131623163316431653166222c22706976" +
      "6f74426c6f636b223a22313233343536373839303132333435363738393031323334353637383930222c22637572736f" +
      "7273223a7b22336666666666666666666666666666666666666666666666666666666666666666666666666666666666" +
      "66666666666666666666666666666666666666666666223a223230303030303030303030303030303030303030303030" +
      "3030303030303030303030303030303030303030303030303030303030303030303030303030303031222c2237666666" +
      "666666666666666666666666666666666666666666666666666666666666666666666666666666666666666666666666" +
      "666666666666666666666666223a22376666666666666666666666666666666666666666666666666666666666666666" +
      "66666666666666666666666666666666666666666666666666666666666666222c226666666666666666666666666666" +
      "666666666666666666666666666666666666666666666666666666666666666666666666666666666666666666666666" +
      "6666223a2263303030303030303030303030303030303030303030303030303030303030303030303030303030303030" +
      "303030303030303030303030303030303030303030227d2c2273746f7261676550617468223a222f646174612f66756b" +
      "7569692f736e61702f66756b7569692d636f6e74726163742d73746f726167652d313233342e62696e222c2273746f72" +
      "616765436f756e74223a332c22636f646548617368657350617468223a222f646174612f66756b7569692f736e61702f" +
      "66756b7569692d756e697175652d636f64656861736865732d353637382e62696e222c22636f6465486173686573436f" +
      "756e74223a327d"
  // {"version":1,"stateRoot":"0000000000000000000000000000000000000000000000000000000000000000","pivo...
  val CheckpointEmpty: String =
    "7b2276657273696f6e223a312c227374617465526f6f74223a2230303030303030303030303030303030303030303030" +
      "303030303030303030303030303030303030303030303030303030303030303030303030303030303030222c22706976" +
      "6f74426c6f636b223a2230222c22637572736f7273223a7b7d2c2273746f7261676550617468223a22222c2273746f72" +
      "616765436f756e74223a302c22636f646548617368657350617468223a22222c22636f6465486173686573436f756e74" +
      "223a307d"
  // {"version":1,"stateRoot":"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff","pivo...
  val CheckpointMax: String =
    "7b2276657273696f6e223a312c227374617465526f6f74223a2266666666666666666666666666666666666666666666" +
      "666666666666666666666666666666666666666666666666666666666666666666666666666666666666222c22706976" +
      "6f74426c6f636b223a2239323233333732303336383534373735383038222c22637572736f7273223a7b223030303030" +
      "303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030" +
      "3030303030303030303030223a2266666666666666666666666666666666666666666666666666666666666666666666" +
      "666666666666666666666666666666666666666666666666666666666666227d2c2273746f7261676550617468223a22" +
      "433a5c5c66756b7569695c5c736e61705c5cc3a920c3bc2e62696e222c2273746f72616765436f756e74223a39323233" +
      "3337323033363835343737353830372c22636f646548617368657350617468223a222f746d702f5c22715c222e62696e" +
      "222c22636f6465486173686573436f756e74223a393232333337323033363835343737353830377d"
  // {"version":2}
  val CheckpointVersion2: String = "7b2276657273696f6e223a327d"
  // {"pivotBlock":4242,"accountCursors":{"3ffffffffffffffffffffffffffffffffffffffffffffffffffffffffff...
  val ProgressFull: String =
    "7b227069766f74426c6f636b223a343234322c226163636f756e74437572736f7273223a7b2233666666666666666666" +
      "666666666666666666666666666666666666666666666666666666666666666666666666666666666666666666666666" +
      "666666666666223a22323030303030303030303030303030303030303030303030303030303030303030303030303030" +
      "30303030303030303030303030303030303030303030303031222c223766666666666666666666666666666666666666" +
      "6666666666666666666666666666666666666666666666666666666666666666666666666666666666666666223a2237" +
      "666666666666666666666666666666666666666666666666666666666666666666666666666666666666666666666666" +
      "666666666666666666666666666666227d2c2273746f72616765437572736f7273223a7b223430343134323433343434" +
      "353436343734383439346134623463346434653466353035313532353335343535353635373538353935613562356335" +
      "6435653566223a2230303030303030303030303030303030303030303030303030303030303030303030303030303030" +
      "303030303030303030303030303030303030303030303830222c22656565656565656565656565656565656565656565" +
      "65656565656565656565656565656565656565656565656565656565656565656565656565656565656565223a226666" +
      "666666666666666666666666666666666666666666666666666666666666666666666666666666666666666666666666" +
      "6666666666666666666666666666227d7d"
  // {"pivotBlock":0,"accountCursors":{},"storageCursors":{}}
  val ProgressEmpty: String =
    "7b227069766f74426c6f636b223a302c226163636f756e74437572736f7273223a7b7d2c2273746f7261676543757273" +
      "6f7273223a7b7d7d"
  // {"pivotBlock":9223372036854775807,"accountCursors":{},"storageCursors":{}}
  val ProgressMaxPivot: String =
    "7b227069766f74426c6f636b223a393232333337323033363835343737353830372c226163636f756e74437572736f72" +
      "73223a7b7d2c2273746f72616765437572736f7273223a7b7d7d"
  // SnapStorageDone/ ++ accountHash ++ storageRoot
  val DoneKey1: String =
    "536e617053746f72616765446f6e652f404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f" +
      "606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f"
  val DoneKey2: String =
    "536e617053746f72616765446f6e652feeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee" +
      "56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"
  // 3 x (32 B accountHash ++ 32 B storageRoot)
  val StorageTaskFileBytes: String =
    "404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f606162636465666768696a6b6c6d6e6f" +
      "707172737475767778797a7b7c7d7e7feeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee" +
      "5656565656565656565656565656565656565656565656565656565656565656a0a1a2a3a4a5a6a7a8a9aaabacadaeaf" +
      "b0b1b2b3b4b5b6b7b8b9babbbcbdbebf56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"
  // 2 x 32 B codeHash
  val CodeHashFileBytes: String =
    "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470d0d1d2d3d4d5d6d7d8d9dadbdcdddedf" +
      "e0e1e2e3e4e5e6e7e8e9eaebecedeeef"
  val FrontierValue1: String = "c3821a2b"
  val FrontierValue2: String = "e3a100cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc35"
  val FrontierValue3: String = "c0"
  val FrontierValue4: String =
    "f83b80b83877777777777777777777777777777777777777777777777777777777777777777777777777777777777777" +
      "77777777777777777777777777"
  val FrontierCompleteKey: String = "5f5f66726f6e746965725f636f6d706c6574655f5f"
  val FrontierFlagValue: String = "c101"
  val SubtreeCompleteKey1: String = "01202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"
  val BfsEntry0: String = "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f0100021a2b00"
  val BfsEntry1: String =
    "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee02002100cccccccccccccccccccccccc" +
      "cccccccccccccccccccccccccccccccccccccccc00013501"
  val BfsEntry2: String = "00000000000000000000000000000000000000000000000000000000000000000001"
  val BfsEntry3: String =
    "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f0101005c5c5c5c5c5c5c5c5c5c5c5c5c" +
      "5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c" +
      "5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c" +
      "5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c" +
      "5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c" +
      "5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c" +
      "5c5c5c00"
  val BfsEntryShortHash: String = "11111111111111111111111111111111111111111111111111111111111111000000"
  val BfsEntryLongHash: String = "22222222222222222222222222222222222222222222222222222222222222220001"
  // 'pivotBlock=4242\n3fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff=20000000000000...
  val LegacyProgress: String =
    "7069766f74426c6f636b3d343234320a3366666666666666666666666666666666666666666666666666666666666666" +
      "66666666666666666666666666666666666666666666666666666666666666663d323030303030303030303030303030" +
      "303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030303030" +
      "310a37666666666666666666666666666666666666666666666666666666666666666666666666666666666666666666" +
      "6666666666666666666666666666666666663d3766666666666666666666666666666666666666666666666666666666" +
      "66666666666666666666666666666666666666666666666666666666666666666666660a"
  // 'v1\n000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f\n4\n0,2\nc5d2460186f7233c92...
  val RecoveryProgressFull: String =
    "76310a303030313032303330343035303630373038303930613062306330643065306631303131313231333134313531" +
      "363137313831393161316231633164316531660a340a302c320a63356432343630313836663732333363393237653764" +
      "6232646363373033633065353030623635336361383232373362376266616438303435643835613437302c6430643164" +
      "326433643464356436643764386439646164626463646464656466653065316532653365346535653665376538653965" +
      "61656265636564656565660a343034313432343334343435343634373438343934613462346334643465346635303531" +
      "353235333534353535363537353835393561356235633564356535663a36303631363236333634363536363637363836" +
      "393661366236633664366536663730373137323733373437353736373737383739376137623763376437653766"
  // 'v1\n000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f\n1\n\n\n'
  val RecoveryProgressEmpty: String =
    "76310a303030313032303330343035303630373038303930613062306330643065306631303131313231333134313531" +
      "363137313831393161316231633164316531660a310a0a0a"
  val GoodPeersFile: String =
    "66756b7569692d736e61702d676f6f642d70656572732076310a61616161616161616161616161616161616161616161" +
      "616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161" +
      "616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161" +
      "616161616161616161610931302e302e302e3109333033303309312e35093130303009320a3031323334353637383961" +
      "626364656630313233343536373839616263646566303132333435363738396162636465663031323334353637383961" +
      "626364656630313233343536373839616263646566303132333435363738396162636465663031323334353637383961" +
      "62636465663031323334353637383961626364656609323030313a6462383a3a3109333033303409322e300932303030" +
      "09300a666666666666666666666666666666666666666666666666666666666666666666666666666666666666666666" +
      "666666666666666666666666666666666666666666666666666666666666666666666666666666666666666666666666" +
      "666666666666666666666666666666666666666666666666666666666666666666666609736565642e6578616d706c65" +
      "2e6f726709363535333509302e3235093330303009300a"

  // AppStateStorage SNAP flags and anchors: (UTF-8 key name, UTF-8 value)
  val AppStateAnchors: Seq[(String, String)] = Seq(
    // SnapSyncDone = "true"
    "536e617053796e63446f6e65" ->
      "74727565",
    // SnapSyncPivotBlock = "98765432109876543210"
    "536e617053796e635069766f74426c6f636b" ->
      "3938373635343332313039383736353433323130",
    // SnapSyncMinPivotBlock = "7"
    "536e617053796e634d696e5069766f74426c6f636b" ->
      "37",
    // SnapSyncStateRoot = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    "536e617053796e635374617465526f6f74" ->
      ("30303031303230333034303530363037303830393061306230633064306530663130313131323133" +
        "313431353136313731383139316131623163316431653166"),
    // SnapSyncBootstrapTarget = "1234567"
    "536e617053796e63426f6f747374726170546172676574" ->
      "31323334353637",
    // SnapSyncAccountsComplete = "true"
    "536e617053796e634163636f756e7473436f6d706c657465" ->
      "74727565",
    // SnapSyncStorageComplete = "false"
    "536e617053796e6353746f72616765436f6d706c657465" ->
      "66616c7365",
    // SnapSyncBytecodeComplete = "true"
    "536e617053796e6342797465636f6465436f6d706c657465" ->
      "74727565",
    // SnapSyncCodeHashesPath = "/data/fukuii/snap/fukuii-unique-codehashes-5678.bin"
    "536e617053796e63436f646548617368657350617468" ->
      ("2f646174612f66756b7569692f736e61702f66756b7569692d756e697175652d636f646568617368" +
        "65732d353637382e62696e"),
    // SnapSyncStorageFilePath = "/data/fukuii/snap/fukuii-contract-storage-1234.bin"
    "536e617053796e6353746f7261676546696c6550617468" ->
      ("2f646174612f66756b7569692f736e61702f66756b7569692d636f6e74726163742d73746f726167" +
        "652d313233342e62696e"),
    // SnapSyncCodeHashesCount = "2"
    "536e617053796e63436f6465486173686573436f756e74" ->
      "32",
    // SnapSyncStorageFileCount = ""
    "536e617053796e6353746f7261676546696c65436f756e74" ->
      "",
    // SnapSyncFinalizedRoot = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
    "536e617053796e6346696e616c697a6564526f6f74" ->
      ("66666666666666666666666666666666666666666666666666666666666666666666666666666666" +
        "666666666666666666666666666666666666666666666666"),
    // BytecodeRecoveryDone = "false"
    "42797465636f64655265636f76657279446f6e65" ->
      "66616c7365",
    // StorageRecoveryDone = "true"
    "53746f726167655265636f76657279446f6e65" ->
      "74727565",
    // BulkBytecodeRecoveryFailures = "3"
    "42756c6b42797465636f64655265636f766572794661696c75726573" ->
      "33"
  )
