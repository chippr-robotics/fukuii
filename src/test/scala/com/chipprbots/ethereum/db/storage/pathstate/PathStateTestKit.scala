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

  private def onRocksDb(test: DataSource => Unit): Unit =
    val directory = Files.createTempDirectory("pathstate-rocksdb").toFile
    // The directory goes whatever happens to the database: an open that throws, a test that fails, a destroy that
    // throws.
    try
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
      val dataSource = RocksDbDataSource(config, Namespaces.nsSeq)
      try test(dataSource)
      finally dataSource.destroy()
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
    writeState(new PathNodeStorage(dataSource), hashed.positions())
    new AppStateStorage(dataSource).putStateAnchor(anchor).commit()

  /** Every row of CF `t` and CF `u`, by its key in its column family. */
  def allRows(rows: PathNodeStorage): (Map[ByteString, ByteString], Map[ByteString, ByteString]) =
    val accounts = Map.newBuilder[ByteString, ByteString]
    val storage = Map.newBuilder[ByteString, ByteString]
    rows.foreachAccountNodeChunk(1000)(_.foreach((k, v) => accounts += ByteString(k) -> ByteString(v)))
    rows.foreachStorageNodeChunk(1000)(_.foreach((k, v) => storage += ByteString(k) -> ByteString(v)))
    (accounts.result(), storage.result())
