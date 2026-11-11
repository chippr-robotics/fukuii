package com.chipprbots.ethereum.db.storage.pathstate

import java.io.File
import java.nio.file.Files

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.NibblePath
import com.chipprbots.ethereum.mpt.PositionTestKit.RawTrieWalk
import com.chipprbots.ethereum.mpt.PositionTestKit.ReferenceStore
import com.chipprbots.ethereum.mpt.PositionTestKit.Rows

/** What the specs of the position store's read side share (spec 018 Track C, C2a part 2): the two data sources they run
  * on, and a state that exists twice, on a hash-keyed store and as rows of CF `t` and CF `u`.
  */
object PathStateTestKit:

  /** The data sources every spec runs on: the in-memory one and RocksDB, each fresh for one test. */
  val dataSources: Seq[(String, (DataSource => Unit) => Unit)] = Seq(
    "EphemDataSource" -> ((test: DataSource => Unit) => test(EphemDataSource())),
    "RocksDB" -> ((test: DataSource => Unit) => onRocksDb(test))
  )

  private def rocksDbAt(directory: File): RocksDbDataSource =
    val config = new RocksDbConfig:
      override val createIfMissing: Boolean = true
      override val paranoidChecks: Boolean = true
      override val path: String = directory.getAbsolutePath
      override val maxThreads: Int = 1
      override val maxOpenFiles: Int = 32
      override val verifyChecksums: Boolean = true
      override val levelCompaction: Boolean = true
      override val blockSize: Long = 16384
      override val blockCacheSize: Long = 33554432
    RocksDbDataSource(config, Namespaces.nsSeq)

  private def onRocksDb(test: DataSource => Unit): Unit =
    val directory = Files.createTempDirectory("pathstate-rocksdb").toFile
    // The directory goes whatever happens to the database: an open that throws, a test that fails, a destroy that
    // throws.
    try
      val dataSource = rocksDbAt(directory)
      try test(dataSource)
      finally dataSource.destroy()
    finally deleteRecursively(directory)

  /** One RocksDB datadir, opened, closed and opened again: what a node's restart is to its storage. `first` runs on the
    * first opening and what it returns is handed to `second`, which runs on the second.
    */
  def onRocksDbReopened[A](first: DataSource => A)(second: (DataSource, A) => Unit): Unit =
    val directory = Files.createTempDirectory("pathstate-rocksdb-reopened").toFile
    try
      val opened = rocksDbAt(directory)
      val carried =
        try first(opened)
        finally opened.close()
      val reopened = rocksDbAt(directory)
      try second(reopened, carried)
      finally reopened.destroy()
    finally deleteRecursively(directory)

  private def deleteRecursively(f: File): Unit =
    Option(f.listFiles()).foreach(_.foreach(deleteRecursively))
    f.delete()
    ()

  def block(number: Int, stateRoot: ByteString): BlockRef =
    BlockRef(ByteString(kec256(s"block-$number".getBytes("UTF-8"))), BigInt(number), stateRoot)

  val EmptyRoot: ByteString = ByteString(MerklePatriciaTrie.EmptyRootHash)

  def owner(address: Address): ByteString = ByteString(kec256(address.bytes.toArray))

  def worldOver(storage: MptStorage, root: ByteString, codes: EvmCodeStorage): InMemoryWorldStateProxy =
    InMemoryWorldStateProxy(
      codes,
      storage,
      (_: BigInt) => None,
      UInt256.Zero,
      root,
      noEmptyAccounts = false,
      ethCompatibleStorage = true
    )

  type World = InMemoryWorldStateProxy

  def withSlots(address: Address, slots: Seq[(Int, Int)])(world: World): World =
    val existing = if world.getAccount(address).isDefined then world else world.saveAccount(address, Account(0, 1))
    existing.saveStorage(
      address,
      slots.foldLeft(existing.getStorage(address)) { case (acc, (key, value)) => acc.store(BigInt(key), BigInt(value)) }
    )

  /** Enough slots that a storage trie has stored nodes below its root. */
  val manySlots: Seq[(Int, Int)] = (1 to 40).map(i => i -> (i * 7919))
  val otherSlots: Seq[(Int, Int)] = (1 to 25).map(i => (i * 3) -> (i + 100))

  val alice: Address = Address(0xa11ce)
  val bob: Address = Address(0xb0b)
  val carol: Address = Address(0xca401)
  val dave: Address = Address(0xda7e)

  /** Accounts with no storage, enough of them that the account trie is a few levels deep. */
  val crowd: Seq[Address] = (1 to 60).map(i => Address(0x10000 + i))

  /** A state built by the production world on a hash-keyed store: the reference every read is held to.
    *
    * `alice` and `bob` hold the same storage, so their storage tries are node for node the same, `carol` holds another,
    * and `dave` and the crowd hold none.
    */
  final class HashedState:
    val nodes = new ReferenceStore
    val codes = new EvmCodeStorage(EphemDataSource())

    def world(root: ByteString): World = worldOver(nodes, root, codes)

    /** `changes` made to the state at `from` and persisted; the new state root. */
    def change(from: ByteString)(changes: World => World): ByteString =
      InMemoryWorldStateProxy.persistState(changes(world(from))).stateRootHash

    val root: ByteString = change(EmptyRoot) { empty =>
      val peopled = crowd.zipWithIndex.foldLeft(empty) { case (world, (address, i)) =>
        world.saveAccount(address, Account(i, 1000 + i))
      }
      withSlots(carol, otherSlots)(withSlots(bob, manySlots)(withSlots(alice, manySlots)(peopled)))
        .saveAccount(dave, Account(7, 7))
    }

    /** Every stored position of the state at `stateRoot`, trie by trie, by a walk of the raw RLP. */
    def positions(stateRoot: ByteString = root): Map[Option[ByteString], Rows] =
      RawTrieWalk.statePositions(stateRoot.toArray, nodes.load)

  /** Writes the state at `stateRoot` into CF `t` and CF `u` with the members SNAP writes them with. */
  def writeState(rows: PathNodeStorage, positions: Map[Option[ByteString], Rows]): Unit =
    positions.foreach { case (trie, trieRows) =>
      trieRows.foreach { case (key, encoded) =>
        val nibbles = HexPrefix.decode(key.toArray)._1
        trie.fold(rows.writeAccountNode(nibbles, encoded.toArray))(rows.writeStorageNode(_, nibbles, encoded.toArray))
      }
    }

  /** Makes `dataSource` a datadir whose position store holds `hashed`'s state as the state of `anchor`: the rows, and
    * the anchor. For specs outside `db.storage`, which cannot write the anchor themselves; the storages have to be
    * built over the data source AFTER this, since the path state storage takes its anchor when it is built.
    */
  def seed(dataSource: DataSource, hashed: HashedState, anchor: BlockRef): Unit =
    seedPositions(dataSource, hashed.positions(), anchor)

  /** The same for any state, given as its stored positions (`RawTrieWalk.statePositions` of a state on a hash store).
    */
  def seedPositions(dataSource: DataSource, positions: Map[Option[ByteString], Rows], anchor: BlockRef): Unit =
    writeState(new PathNodeStorage(dataSource), positions)
    new AppStateStorage(dataSource).putStateAnchor(anchor).commit()

  /** `under`, as a view the disk moves away from: the first `healthy` reads are `under`'s, and every read after them
    * says the view is stale. The count is one for the whole state, so it runs on across the tries of [[forOwner]]. What
    * a reader that holds a storage across many reads (a SNAP serve, a walk) has to be shown.
    */
  final class StaleAfter(
      under: MptStorage,
      healthy: Int,
      reads: java.util.concurrent.atomic.AtomicInteger = new java.util.concurrent.atomic.AtomicInteger
  ) extends MptStorage:
    /** How many reads were asked of the state, stale ones included. */
    def readsAsked: Int = reads.get

    private def read(node: => MptNode): MptNode =
      if reads.incrementAndGet() > healthy then
        throw new StaleStateViewException(block(1, EmptyRoot), 0L, 2L, s"the disk moved after $healthy reads")
      else node

    override def get(nodeId: Array[Byte]): MptNode = read(under.get(nodeId))
    override def getAt(path: NibblePath, hash: Array[Byte]): MptNode = read(under.getAt(path, hash))
    override def forOwner(owner: Option[ByteString]): MptStorage = new StaleAfter(under.forOwner(owner), healthy, reads)
    override def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] =
      throw new UnsupportedOperationException("a view that is only read")
    override def persist(): Unit = ()

  /** A hash store that lost the nodes `missing` names: a read of one says the node is missing, as a store that never
    * had it does.
    */
  final class Lacking(under: MptStorage, missing: Set[ByteString]) extends MptStorage:
    override def get(nodeId: Array[Byte]): MptNode =
      if missing(ByteString(nodeId)) then throw new MerklePatriciaTrie.MissingNodeException(ByteString(nodeId))
      else under.get(nodeId)
    override def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] =
      throw new UnsupportedOperationException("a store that is only read")
    override def persist(): Unit = ()

  /** A state whose keys are chosen, so that its tries have what hashed keys almost never give (a path through either is
    * where a position is miscounted):
    *   - extensions many nibbles long, in the account trie (`3abcd…`, 4 nibbles; `1111 0…`, 62) and in the storage
    *     tries;
    *   - nodes embedded in their parent: `small` holds the slots `big` holds with one-byte values, so the two leaves
    *     under `7777…` sit inside their branch, and that branch inside the extension above it.
    *
    * `holder` and `twin` hold the same storage trie. `holder`, `small` and `coded` have code; `twin` has `holder`'s.
    */
  final class ChosenState(
      val nodes: MptStorage = new ReferenceStore,
      val codes: EvmCodeStorage = new EvmCodeStorage(EphemDataSource())
  ):
    import com.chipprbots.ethereum.domain.CodeHash
    import com.chipprbots.ethereum.domain.TrieRoot
    import com.chipprbots.ethereum.mpt.byteStringSerializer

    /** A node of the state as stored, by its hash. */
    def load(hash: Array[Byte]): Array[Byte] = nodes.get(hash).encode

    val slotKeys: Seq[ByteString] =
      Seq(chosenKey("7777", 1), chosenKey("7777", 2), chosenKey("7778", 0), chosenKey("80", 0), chosenKey("8f", 0xff))

    private def storageOf(value: Int => ByteString): TrieRoot =
      val trie = slotKeys.zipWithIndex.foldLeft(MerklePatriciaTrie[ByteString, ByteString](nodes)) {
        case (trie, (slot, i)) => trie.put(slot, value(i))
      }
      TrieRoot(ByteString(trie.getRootHash))

    val bigStorage: TrieRoot = storageOf(i => ByteString(Array.fill[Byte](33)((i + 1).toByte)))
    val smallStorage: TrieRoot = storageOf(i => ByteString((i + 1).toByte))

    val holderCode: ByteString = ByteString("the holder's code")
    val smallCode: ByteString = ByteString("the code of the account with small slots")
    val codedCode: ByteString = ByteString("the code of an account with no storage")
    private def stored(code: ByteString): CodeHash =
      val hash = ByteString(kec256(code.toArray))
      codes.put(hash, code).commit()
      CodeHash(hash)

    val holder: ByteString = chosenKey("c0ffee", 0)
    val twin: ByteString = chosenKey("c0ffef", 0)
    val small: ByteString = chosenKey("d00d", 0)
    val coded: ByteString = chosenKey("26", 0)

    val accounts: Seq[(ByteString, Account)] = Seq(
      chosenKey("1111", 0) -> Account(nonce = 1, balance = 10),
      chosenKey("1111", 1) -> Account(nonce = 2, balance = 20),
      chosenKey("25", 0) -> Account(nonce = 3, balance = 30),
      coded -> Account(nonce = 4, balance = 40, codeHash = stored(codedCode)),
      chosenKey("3abcde", 0) -> Account(nonce = 5, balance = 50),
      chosenKey("3abcdf", 0) -> Account(nonce = 6, balance = 60),
      holder -> Account(nonce = 7, balance = 70, storageRoot = bigStorage, codeHash = stored(holderCode)),
      twin -> Account(nonce = 8, balance = 80, storageRoot = bigStorage, codeHash = stored(holderCode)),
      small -> Account(nonce = 9, balance = 90, storageRoot = smallStorage, codeHash = stored(smallCode))
    )

    val root: ByteString =
      val state = accounts.foldLeft(MerklePatriciaTrie[ByteString, Account](nodes)) { case (trie, (key, account)) =>
        trie.put(key, account)
      }
      nodes.persist()
      ByteString(state.getRootHash)

  /** A key of 32 bytes: `prefix` (hex), zeros, and `last`. */
  def chosenKey(prefix: String, last: Int): ByteString =
    val head = prefix.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
    ByteString(head ++ new Array[Byte](31 - head.length) :+ last.toByte)

  /** One state held two ways: on a hash store, and as the rows of a position store whose anchor it is. Either can be
    * had with holes: the same nodes lost from both.
    */
  final class TwoWays(val root: ByteString, hashes: MptStorage, load: Array[Byte] => Array[Byte]):
    val anchor: BlockRef = block(1, root)

    /** Every stored position of the state, trie by trie, by the row's key (the path, hex-prefix encoded). */
    val positions: Map[Option[ByteString], Rows] = RawTrieWalk.statePositions(root.toArray, load)

    /** A position of a trie: the trie (no owner for the account trie) and the row's key. */
    type Position = (Option[ByteString], ByteString)

    def onHashes: MptStorage = hashes
    def onPositions: MptStorage = positionsWithout()

    /** The hash of the node stored at `position`. */
    def hashAt(position: Position): ByteString = ByteString(kec256(positions(position._1)(position._2).toArray))

    /** The nibbles of a row's key. */
    def path(position: Position): Array[Byte] = HexPrefix.decode(position._2.toArray)._1

    /** The stored positions of one trie, the root first, then by depth and path. */
    def stored(trie: Option[ByteString]): Seq[Position] =
      positions(trie).keys.toSeq
        .sortBy(row => (HexPrefix.decode(row.toArray)._1.length, row.map("%02x".format(_)).mkString))
        .map(trie -> _)

    /** Every position that holds the node `position` holds: where two accounts have one storage trie, both of theirs.
      * What a hash store loses when it loses that node.
      */
    def everywhere(position: Position): Seq[Position] =
      val node = positions(position._1)(position._2)
      positions.toSeq.flatMap((trie, rows) => rows.collect { case (row, `node`) => trie -> row })

    /** The hash store without the nodes at `holes`. A node two tries hold is lost from both: a hash store has it once.
      */
    def hashesWithout(holes: Position*): MptStorage = new Lacking(hashes, holes.map(hashAt).toSet)

    /** A position store of the state without the rows at `holes`, as a view of the anchor. */
    def positionsWithout(holes: Position*): MptStorage =
      val source = EphemDataSource()
      val kept = positions.map((trie, rows) => trie -> rows.filterNot((row, _) => holes.contains(trie -> row)))
      seedPositions(source, kept, anchor)
      new PathStateStorage(new PathNodeStorage(source), new AppStateStorage(source)).readOnly(anchor)

  /** `under`, watched: every call is noted in order, each batch is kept, and a write can be made to fail or to wait. It
    * is not the in-memory data source to anything that asks, so rows are dumped ([[allRows]]) through `under`.
    */
  final class Watched(val under: DataSource) extends DataSource:
    import cats.effect.IO
    import fs2.Stream
    import com.chipprbots.ethereum.db.dataSource.DataSource.*
    import com.chipprbots.ethereum.db.dataSource.DataUpdate
    import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource.IterationError

    val calls = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val batches = new java.util.concurrent.ConcurrentLinkedQueue[Seq[DataUpdate]]()

    /** Runs on the writer's thread before each batch reaches `under`; what it throws, the write throws. */
    @volatile var beforeUpdate: Seq[DataUpdate] => Unit = _ => ()

    def callsMade: Seq[String] = calls.toArray(Array.empty[String]).toSeq
    def batchesWritten: Seq[Seq[DataUpdate]] = batches.toArray(Array.empty[Seq[DataUpdate]]).toSeq

    override def get(namespace: Namespace, key: Key): Option[Value] =
      calls.add("read")
      under.get(namespace, key)
    override def getOptimized(namespace: Namespace, key: Array[Byte]): Option[Array[Byte]] =
      calls.add("read")
      under.getOptimized(namespace, key)
    override def scanRange(
        namespace: Namespace,
        fromKey: Array[Byte],
        toKeyExclusive: Array[Byte]
    ): Iterator[(Array[Byte], Array[Byte])] =
      calls.add("scan")
      under.scanRange(namespace, fromKey, toKeyExclusive)
    override def scanRangeLimit(
        namespace: Namespace,
        fromKey: Array[Byte],
        toKeyExclusive: Array[Byte],
        limit: Int
    ): Iterator[(Array[Byte], Array[Byte])] =
      calls.add("scan")
      under.scanRangeLimit(namespace, fromKey, toKeyExclusive, limit)
    override def deleteRange(namespace: Namespace, fromKey: Array[Byte], toKeyExclusive: Array[Byte]): Unit =
      calls.add("deleteRange")
      under.deleteRange(namespace, fromKey, toKeyExclusive)
    override def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
      calls.add("update")
      beforeUpdate(dataSourceUpdates)
      batches.add(dataSourceUpdates)
      under.update(dataSourceUpdates)
    override def updateSync(dataSourceUpdates: Seq[DataUpdate]): Unit =
      calls.add("updateSync")
      beforeUpdate(dataSourceUpdates)
      batches.add(dataSourceUpdates)
      under.updateSync(dataSourceUpdates)
    override def clear(): Unit = under.clear()
    override def close(): Unit = under.close()
    override def destroy(): Unit = under.destroy()
    override def iterate(): Stream[IO, Either[IterationError, (Array[Byte], Array[Byte])]] = under.iterate()
    override def iterate(namespace: Namespace): Stream[IO, Either[IterationError, (Array[Byte], Array[Byte])]] =
      under.iterate(namespace)

  /** Every row of the hash store's column family (`n`): what the unified layout must leave as it finds it, since a
    * path-live store has one writer and it is not that one (data-model 7.6).
    */
  def hashStoreRows(dataSource: DataSource): Map[ByteString, ByteString] =
    import cats.effect.unsafe.implicits.global
    dataSource
      .iterate(Namespaces.NodeNamespace)
      .compile
      .toList
      .unsafeRunSync()
      .map {
        case Right((key, value)) => ByteString(key) -> ByteString(value)
        case Left(error)         => throw new IllegalStateException("the hash store could not be read", error.ex)
      }
      .toMap

  /** Every row of CF `t` and CF `u`, by its key in its column family. */
  def allRows(rows: PathNodeStorage): (Map[ByteString, ByteString], Map[ByteString, ByteString]) =
    val accounts = Map.newBuilder[ByteString, ByteString]
    val storage = Map.newBuilder[ByteString, ByteString]
    rows.foreachAccountNodeChunk(1000)(_.foreach((k, v) => accounts += ByteString(k) -> ByteString(v)))
    rows.foreachStorageNodeChunk(1000)(_.foreach((k, v) => storage += ByteString(k) -> ByteString(v)))
    (accounts.result(), storage.result())
