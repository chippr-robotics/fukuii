package com.chipprbots.ethereum.network.snapserver

import java.io.File
import java.io.PrintWriter

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.CodeHash
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.PositionTestKit.RawTrieWalk
import com.chipprbots.ethereum.mpt.PositionTestKit.ReferenceStore
import com.chipprbots.ethereum.mpt.PositionTestKit.Rows
import com.chipprbots.ethereum.mpt.byteStringSerializer
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPValue

/** B-212 (spec 018 T052): one state big enough for SNAP's limits to bite, written twice.
  *
  * `<out>/hash/db` is the datadir the pinned v0.12.1 opens: CF `n` (every trie node of 32 bytes or more, and every
  * root, by its hash) and CF `c` (code by hash). `<out>/position/db` is the same state on the unified layout: CF `t`
  * and CF `u` (one row for each stored position of each trie, a storage trie once under each account that holds it),
  * the anchor in AppState, and CF `c`. `<out>/manifest.tsv` says what is in it, for `SnapServeOracleRunner`.
  *
  * The state (deterministic: every key is a hash of a name):
  *   - [[Accounts]] accounts, with 40 contracts whose code runs from 50 bytes to about 12 KB, two of them sharing one;
  *   - accounts `100` and `110` hold one storage trie of 1500 slots (the same root); `101` holds one of 600; `102` and
  *     `103` hold one of 300; `104` and `105` one of 40; `106`, `107` and `108` hold 1, 2 and 17 slots; every other
  *     account has empty storage;
  *   - the storage tries are ordinary: slot keys are hashes, values the RLP of a trimmed 32-byte word.
  *
  * `scripts/oracle/b212/run.sh` runs this, then the same requests on v0.12.1 (hash store) and on this checkout (hash
  * store, then position store).
  */
object SnapServeOracleSeeder:

  val Accounts = 3000

  private def hex(b: ByteString): String = b.map("%02x".format(_)).mkString
  private def hash(s: String): ByteString = ByteString(kec256(s.getBytes("UTF-8")))

  def config(dir: File): RocksDbConfig = new RocksDbConfig:
    override val createIfMissing: Boolean = true
    override val paranoidChecks: Boolean = true
    override val path: String = dir.getAbsolutePath
    override val maxThreads: Int = 1
    override val maxOpenFiles: Int = 64
    override val verifyChecksums: Boolean = true
    override val levelCompaction: Boolean = true
    override val blockSize: Long = 16384
    override val blockCacheSize: Long = 33554432

  private def withDb[A](dir: File)(body: RocksDbDataSource => A): A =
    dir.mkdirs()
    val ds = RocksDbDataSource(config(dir), Namespaces.nsSeq)
    try body(ds)
    finally ds.close()

  /** The slots of the storage trie named `name`: `count` of them, the same slots wherever the name is. */
  private def slotsOf(name: String, count: Int): Seq[(ByteString, ByteString)] =
    (0 until count).map { i =>
      val value = BigInt(1, kec256(s"$name-value-$i".getBytes("UTF-8")).take(1 + i % 32))
      hash(s"$name-slot-$i") -> ByteString(rlp.encode(RLPValue(value.toByteArray.dropWhile(_ == 0))))
    }

  /** The storage tries, by the index of each account that holds one. */
  private val storages: Map[Int, (String, Int)] = Map(
    100 -> ("big" -> 1500),
    110 -> ("big" -> 1500),
    101 -> ("mid" -> 600),
    102 -> ("pair" -> 300),
    103 -> ("pair" -> 300),
    104 -> ("few" -> 40),
    105 -> ("few" -> 40),
    106 -> ("one" -> 1),
    107 -> ("two" -> 2),
    108 -> ("seventeen" -> 17)
  )

  private def code(i: Int): ByteString =
    val source = if i == 1 then 0 else i // contract 1 has contract 0's code
    ByteString(Array.tabulate[Byte](50 + source * 300)(j => ((j * 31 + source) & 0xff).toByte))

  /** The contracts are accounts `100` to `139`, and `100`, `101` and `110` of the storage holders are among them. */
  private def isContract(i: Int): Boolean = i >= 100 && i < 140

  def seed(out: File): Unit =
    val nodes = new ReferenceStore
    val storageRoots = storages.values.toSet.map { case (name, count) =>
      val trie = slotsOf(name, count).foldLeft(MerklePatriciaTrie[ByteString, ByteString](nodes)) { case (t, (k, v)) =>
        t.put(k, v)
      }
      (name, count) -> TrieRoot(ByteString(trie.getRootHash))
    }.toMap
    val codes = (100 until 140).map(i => i -> code(i)).toMap
    val accounts: Seq[(ByteString, Account, Int)] = (0 until Accounts).map { i =>
      val storage = storages.get(i).map(storageRoots).getOrElse(Account.EmptyStorageRootHash)
      val codeHash =
        if isContract(i) then CodeHash(ByteString(kec256(codes(i).toArray))) else Account.EmptyCodeHash
      (hash(s"oracle-account-$i"), Account(nonce = i % 17, balance = 1000 + i * 13, storage, codeHash), i)
    }
    val state = accounts.foldLeft(MerklePatriciaTrie[ByteString, Account](nodes)) { case (t, (k, a, _)) =>
      t.put(k, a)
    }
    nodes.persist()
    val root = ByteString(state.getRootHash)
    val positions: Map[Option[ByteString], Rows] = RawTrieWalk.statePositions(root.toArray, nodes.load)

    val byHash: Map[ByteString, ByteString] =
      (for
        (_, rows) <- positions.toSeq
        (row, encoded) <- rows.toSeq
        if encoded.length >= 32 || HexPrefix.decode(row.toArray)._1.isEmpty
      yield ByteString(kec256(encoded.toArray)) -> encoded).toMap
    val allCodes = codes.values.toSeq.distinct

    def writeCodes(ds: RocksDbDataSource): Unit =
      val evm = new EvmCodeStorage(ds)
      allCodes.foreach(c => evm.put(ByteString(kec256(c.toArray)), c).commit())

    out.mkdirs()
    withDb(new File(out, "hash/db")) { ds =>
      new NodeStorage(ds).update(Nil, byHash.toSeq.map((h, rlp) => h -> rlp.toArray))
      writeCodes(ds)
    }
    withDb(new File(out, "position/db")) { ds =>
      PathStateTestKit.seedPositions(ds, positions, PathStateTestKit.block(1, root))
      writeCodes(ds)
    }

    val manifest = new PrintWriter(new File(out, "manifest.tsv"), "UTF-8")
    try
      manifest.println("# kind\t...")
      manifest.println(s"root\t${hex(root)}")
      accounts.foreach { (k, a, i) =>
        val quartiles = storages.get(i).fold(Seq("", "", "")) { case (name, count) =>
          val keys = slotsOf(name, count).map(s => hex(s._1)).sorted
          Seq(keys(count / 4), keys(count / 2), keys(count * 3 / 4))
        }
        val storageRoot = storages.get(i).fold("")(s => hex(storageRoots(s).value))
        val codeHash = if isContract(i) then hex(a.codeHash.value) else ""
        manifest.println(
          (Seq("account", hex(k), storages.get(i).fold(0)(_._2).toString, storageRoot, codeHash) ++ quartiles)
            .mkString("\t")
        )
      }
      allCodes.map(c => hex(ByteString(kec256(c.toArray)))).sorted.foreach(h => manifest.println(s"code\t$h"))
      positions(None).keys.toSeq.map(hex).sorted.foreach(p => manifest.println(s"accpath\t$p"))
      val holders = accounts.filter(a => Set(100, 101, 102, 103, 104, 106, 107, 108, 110)(a._3)).map(_._1)
      holders.foreach { owner =>
        positions(Some(owner)).keys.toSeq.map(hex).sorted.foreach(p => manifest.println(s"stopath\t${hex(owner)}\t$p"))
      }
    finally manifest.close()

  def main(args: Array[String]): Unit =
    require(args.length == 1, "usage: SnapServeOracleSeeder <outDir>")
    seed(new File(args(0)))
    println(s"seeded $Accounts accounts into ${args(0)}")
