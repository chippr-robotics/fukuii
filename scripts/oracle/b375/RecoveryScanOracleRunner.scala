package com.chipprbots.ethereum.blockchain.sync

import java.io.File
import java.io.PrintWriter

import org.apache.pekko.util.ByteString

import scala.io.Source

import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.ArchiveNodeStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.SerializingMptStorage
import com.chipprbots.ethereum.mpt.ShardEnumerator

/** B-375 (spec 018 T214): the recovery scans' verdicts on a seeded hash-layout RocksDB datadir, as plain text.
  *
  * This one file is compiled UNCHANGED into two builds: the pinned v0.12.1 and staging. It lives in package
  * `blockchain.sync` because `StorageRecoveryActor.scanForMissingStorage` and `BytecodeRecoveryActor.scanForMissingBytecodes`
  * are `private[sync]` (the narrowest functions that reach the legacy verdicts; the actors themselves need a Pekko
  * system and a peer). It uses only API the two builds share: `CombinedRecoveryScan`, `CombinedRecoveryScanner`,
  * `ShardEnumerator.enumShards`, the two legacy scan functions, `SerializingMptStorage` over `ArchiveNodeStorage`
  * over `NodeStorage`, `EvmCodeStorage`, `RocksDbDataSource`.
  *
  * args: `<seedDir> <outFile>`. `<seedDir>/manifest.tsv` has one case per line, `name<TAB>stateRoot(hex)<TAB>...`, and
  * each case's datadir is `<seedDir>/<name>/db`. Output: one line per (case, scan), `case<TAB>scan<TAB>verdict`.
  *
  * Verdict text. A scan that throws is `threw <class>`: the nearest named class, as RecoveryScannersPositionSpec names
  * it. Otherwise `code=[sorted hashes] storage=[sorted owner>root] holes=N`. The parallel scanner's shards race to
  * name the owner of a storage trie that two accounts share, so its storage is printed as the sorted ROOTS only; the
  * legacy bytecode scan has no storage and the legacy storage scan no code, and print as the one thing they return.
  */
object RecoveryScanOracleRunner:

  private val quiet: org.slf4j.Logger = LoggerFactory.getLogger("RecoveryScanOracleRunner")

  private def hex(b: ByteString): String = b.map("%02x".format(_)).mkString

  private def named(c: Class[?]): String = if c.getSimpleName.nonEmpty then c.getSimpleName else named(c.getSuperclass)

  private def thrown(run: => String): String =
    try run
    catch case e: Throwable => s"threw ${named(e.getClass)}"

  private def codes(c: Seq[ByteString]): String = c.map(hex).sorted.mkString("[", ",", "]")
  private def pairs(s: Seq[(ByteString, ByteString)]): String =
    s.map((a, b) => s"${hex(a)}>${hex(b)}").sorted.mkString("[", ",", "]")
  private def roots(s: Seq[(ByteString, ByteString)]): String = s.map(p => hex(p._2)).sorted.mkString("[", ",", "]")

  private def config(dir: String): RocksDbConfig = new RocksDbConfig:
    override val createIfMissing: Boolean = false
    override val paranoidChecks: Boolean = true
    override val path: String = dir
    override val maxThreads: Int = 1
    override val maxOpenFiles: Int = 32
    override val verifyChecksums: Boolean = true
    override val levelCompaction: Boolean = true
    override val blockSize: Long = 16384
    override val blockCacheSize: Long = 33554432

  /** Every scan of the state at `root`, by name, as verdict text. */
  private def verdicts(root: ByteString, store: () => MptStorage, evm: EvmCodeStorage): Seq[(String, String)] =
    def combined(run: (CombinedRecoveryScan, MptStorage) => Unit): String = thrown {
      val s = store()
      val scan = new CombinedRecoveryScan(s, evm)
      run(scan, s)
      s"code=${codes(scan.missingBytecodes)} storage=${pairs(scan.missingStorageTries)} holes=${scan.holes}"
    }
    def driver(concurrency: Int): String = thrown {
      val r = new CombinedRecoveryScanner(
        root,
        store,
        evm,
        new AppStateStorage(EphemDataSource()),
        concurrency = concurrency,
        shardDepth = 1
      ).run()
      val storage = if concurrency > 1 then roots(r.missingStorageTries) else pairs(r.missingStorageTries)
      s"code=${codes(r.missingBytecodes)} storage=$storage holes=${r.holes}"
    }
    Seq(
      "scanFrom" -> combined((scan, _) => scan.scanFrom(root)),
      "scanShards" -> combined((scan, s) =>
        ShardEnumerator.enumShards(root, s, 1).foreach(shard => scan.scanShard(shard.root, shard.pathPrefix))
      ),
      "scanner-sequential" -> driver(1),
      "scanner-parallel" -> driver(2),
      "legacy-storage" -> thrown(pairs(StorageRecoveryActor.scanForMissingStorage(root, store(), quiet))),
      "legacy-bytecode" -> thrown(codes(BytecodeRecoveryActor.scanForMissingBytecodes(root, store(), evm, quiet)))
    )

  def main(args: Array[String]): Unit =
    require(args.length == 2, "usage: RecoveryScanOracleRunner <seedDir> <outFile>")
    val seedDir = new File(args(0))
    val out = new PrintWriter(new File(args(1)), "UTF-8")
    try
      val manifest = Source.fromFile(new File(seedDir, "manifest.tsv"), "UTF-8")
      val cases =
        try manifest.getLines().filter(l => l.nonEmpty && !l.startsWith("#")).map(_.split("\t", -1).toList).toList
        finally manifest.close()
      for case name :: rootHex :: _ <- cases do
        val root = ByteString(org.bouncycastle.util.encoders.Hex.decode(rootHex))
        val ds = RocksDbDataSource(config(new File(new File(seedDir, name), "db").getAbsolutePath), Namespaces.nsSeq)
        try
          val evm = new EvmCodeStorage(ds)
          // a fresh handle per call, as production hands each shard one
          val store = () => new SerializingMptStorage(new ArchiveNodeStorage(new NodeStorage(ds))): MptStorage
          verdicts(root, store, evm).foreach((scan, v) => out.println(s"$name\t$scan\t$v"))
        finally ds.close()
    finally out.close()
