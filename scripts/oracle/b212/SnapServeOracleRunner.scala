package com.chipprbots.ethereum.network.snapserver

import java.io.File
import java.io.PrintWriter

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.io.Source

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.storage.ArchiveNodeStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.SerializingMptStorage
import com.chipprbots.ethereum.network.p2p.messages.SNAP.AccountRange
import com.chipprbots.ethereum.network.p2p.messages.SNAP.ByteCodes
import com.chipprbots.ethereum.network.p2p.messages.SNAP.StorageRanges
import com.chipprbots.ethereum.network.p2p.messages.SNAP.TrieNodes

/** B-212 (spec 018 T052): the bytes `SnapServer` answers a fixed list of SNAP requests with, on a seeded datadir, as
  * text.
  *
  * This one file is compiled UNCHANGED into two builds: the pinned v0.12.1 and staging. It calls the narrowest functions
  * that produce a response message, `SnapServer.serveAccountRange`, `serveStorageRanges` (with `storageRootOf`, as the
  * handler does), `serveTrieNodes` and `serveByteCodes`, and writes each response as the RLP bytes the wire carries
  * (the message's own `toBytes`), in hex. No actor, no peer, no Pekko system. It uses only API the two builds share.
  *
  * `main` serves from the HASH store (`SerializingMptStorage` over `ArchiveNodeStorage` over `NodeStorage`) of
  * `<seedDir>/hash/db`; staging's `SnapServeOraclePositionRunner` calls [[serveAll]] with the position store.
  *
  * args: `<seedDir> <outFile>`. `<seedDir>/manifest.tsv` (written by `SnapServeOracleSeeder`) lists the state root, the
  * accounts (hash, slot count, storage root, code hash, three slot keys), the code hashes and the stored paths of the
  * account trie and of the storage tries of interest. Output: one line per request, `name<TAB>hex(response RLP)`.
  */
object SnapServeOracleRunner:

  final case class Account(
      hash: ByteString,
      slots: Int,
      storageRoot: String,
      codeHash: String,
      quartiles: Seq[ByteString]
  )

  final class Manifest(
      val root: ByteString,
      val accounts: Seq[Account],
      val codes: Seq[ByteString],
      val accountPaths: Seq[ByteString],
      val storagePaths: Map[ByteString, Seq[ByteString]]
  ):
    val sorted: Seq[ByteString] = accounts.map(_.hash).sortBy(hex)

  def hex(b: ByteString): String = b.map("%02x".format(_)).mkString
  def unhex(s: String): ByteString = ByteString(org.bouncycastle.util.encoders.Hex.decode(s))

  def readManifest(file: File): Manifest =
    val src = Source.fromFile(file, "UTF-8")
    val lines =
      try src.getLines().filter(l => l.nonEmpty && !l.startsWith("#")).map(_.split("\t", -1).toList).toList
      finally src.close()
    val root = lines.collectFirst { case "root" :: r :: _ => unhex(r) }.get
    val accounts = lines.collect { case "account" :: h :: n :: sr :: ch :: q1 :: q2 :: q3 :: _ =>
      Account(unhex(h), n.toInt, sr, ch, Seq(q1, q2, q3).filter(_.nonEmpty).map(unhex))
    }
    val codes = lines.collect { case "code" :: h :: _ => unhex(h) }
    val accountPaths = lines.collect { case "accpath" :: p :: _ => unhex(p) }
    val storagePaths = lines.collect { case "stopath" :: a :: p :: _ => unhex(a) -> unhex(p) }
      .groupMap(_._1)(_._2)
    new Manifest(root, accounts, codes, accountPaths, storagePaths)

  private val zero = ByteString(new Array[Byte](32))
  private val max = ByteString(Array.fill[Byte](32)(0xff.toByte))
  private val plenty = BigInt(2 * 1024 * 1024)

  /** `h` plus `delta`, as 32 bytes, wrapping nowhere (a result past 2^256-1 is `max`, one below 0 is `zero`). */
  private def plus(h: ByteString, delta: Int): ByteString =
    val v = BigInt(1, h.toArray) + delta
    if v.signum < 0 then zero
    else if v >= (BigInt(1) << 256) then max
    else
      val raw = v.toByteArray.dropWhile(_ == 0)
      ByteString(Array.fill[Byte](32 - raw.length)(0) ++ raw)

  private def fake(s: String): ByteString = ByteString(kec256(s.getBytes("UTF-8")))

  /** Every request, in order, each by name and with the bytes it is answered with. */
  private def requests(
      m: Manifest,
      store: () => MptStorage,
      evm: EvmCodeStorage
  ): Seq[(String, () => Array[Byte])] =
    val out = mutable.ArrayBuffer.empty[(String, () => Array[Byte])]
    var id = 0
    def add(name: String)(bytes: Int => Array[Byte]): Unit =
      id += 1
      val n = id
      out += (s"%04d-$name".format(n) -> (() => bytes(n)))

    val root = m.root
    val keys = m.sorted
    val budgets = Seq(plenty, BigInt(65536), BigInt(8192), BigInt(1000), BigInt(300), BigInt(1), BigInt(0))

    def accountRange(origin: ByteString, limit: ByteString, budget: BigInt, r: ByteString = root)(n: Int) =
      new AccountRange.AccountRangeEnc(SnapServer.serveAccountRange(n, r, origin, limit, budget, store())).toBytes
    def storageRanges(
        accounts: Seq[ByteString],
        origin: ByteString,
        limit: ByteString,
        budget: BigInt,
        r: ByteString = root
    )(n: Int) =
      val s = store()
      new StorageRanges.StorageRangesEnc(
        SnapServer.serveStorageRanges(n, r, accounts, origin, limit, budget, s, SnapServer.storageRootOf(r, s))
      ).toBytes
    def trieNodes(paths: Seq[Seq[ByteString]], budget: BigInt, r: ByteString = root)(n: Int) =
      new TrieNodes.TrieNodesEnc(SnapServer.serveTrieNodes(n, r, paths, budget, store())).toBytes
    def byteCodes(hashes: Seq[ByteString], budget: BigInt)(n: Int) =
      new ByteCodes.ByteCodesEnc(SnapServer.serveByteCodes(n, hashes, budget, evm)).toBytes

    // ── GetAccountRange ──────────────────────────────────────────────────────────────────────────────────────────
    for b <- budgets do add(s"acct-full-b$b")(accountRange(zero, max, b))
    for b <- Seq(plenty, BigInt(2000), BigInt(1)) do
      add(s"acct-bounded-500-900-b$b")(accountRange(keys(500), keys(900), b))
      add(s"acct-bounded-tight-b$b")(accountRange(keys(1200), keys(1203), b))
    // an origin between two keys, and one just after a key, and a limit between two keys
    for i <- Seq(0, 1, 7, 333, 1499, 2998, keys.size - 2) do
      add(s"acct-after-key$i")(accountRange(plus(keys(i), 1), max, BigInt(8192)))
      add(s"acct-before-key$i")(accountRange(plus(keys(i), -1), plus(keys(i + 1), -1), plenty))
      add(s"acct-single-key$i")(accountRange(keys(i), keys(i), plenty))
      add(s"acct-reversed-key$i")(accountRange(keys(i + 1), keys(i), plenty))
    add("acct-past-last")(accountRange(plus(keys.last, 1), max, plenty))
    add("acct-last-key")(accountRange(keys.last, max, BigInt(1)))
    add("acct-max-max")(accountRange(max, max, plenty))
    add("acct-zero-zero")(accountRange(zero, zero, plenty))
    add("acct-above-2MiB")(accountRange(zero, max, BigInt(1) << 40))
    add("acct-unknown-root")(accountRange(zero, max, plenty, fake("not a state root")))
    add("acct-empty-root")(accountRange(zero, max, plenty, ByteString(kec256(Array[Byte](0x80.toByte)))))

    // ── GetStorageRanges ─────────────────────────────────────────────────────────────────────────────────────────
    val bySlots = m.accounts.filter(_.slots > 0).sortBy(a => (-a.slots, hex(a.hash)))
    val big = bySlots.head
    val mid = bySlots.find(a => a.slots == 600).get
    val sharedPair = m.accounts.filter(a => a.slots == 300).map(_.hash)
    val bigTwin = m.accounts.filter(a => a.slots == big.slots).map(_.hash)
    val small = bySlots.filter(a => a.slots > 0 && a.slots < 40).map(_.hash)
    val empty = m.accounts.find(a => a.slots == 0).map(_.hash).get
    val unknownAccount = fake("no such account")
    val all = Seq(big.hash, mid.hash) ++ sharedPair ++ small ++ Seq(empty, unknownAccount)
    for b <- budgets do
      add(s"stor-big-full-b$b")(storageRanges(Seq(big.hash), zero, max, b))
      add(s"stor-many-full-b$b")(storageRanges(all, zero, max, b))
    for b <- Seq(BigInt(20000), BigInt(6000), BigInt(2500)) do
      add(s"stor-many-b$b")(storageRanges(all, zero, max, b))
      add(s"stor-many-rev-b$b")(storageRanges(all.reverse, zero, max, b))
    add("stor-shared-pair")(storageRanges(sharedPair, zero, max, plenty))
    add("stor-shared-pair-first")(storageRanges(sharedPair.take(1), zero, max, plenty))
    add("stor-shared-pair-second")(storageRanges(sharedPair.drop(1), zero, max, plenty))
    add("stor-shared-pair-tight")(storageRanges(sharedPair, zero, max, BigInt(3000)))
    add("stor-big-and-its-twin")(storageRanges(bigTwin, zero, max, plenty))
    add("stor-big-and-its-twin-tight")(storageRanges(bigTwin, zero, max, BigInt(4000)))
    add("stor-empty")(storageRanges(Seq(empty), zero, max, plenty))
    add("stor-unknown")(storageRanges(Seq(unknownAccount), zero, max, plenty))
    add("stor-no-accounts")(storageRanges(Seq.empty, zero, max, plenty))
    for q <- big.quartiles.zipWithIndex do
      for b <- Seq(plenty, BigInt(8192), BigInt(1))
      do add(s"stor-big-from-q${q._2}-b$b")(storageRanges(Seq(big.hash), q._1, max, b))
      add(s"stor-big-from-q${q._2}-after")(storageRanges(Seq(big.hash), plus(q._1, 1), max, BigInt(4096)))
      add(s"stor-big-from-q${q._2}-before")(storageRanges(Seq(big.hash), plus(q._1, -1), plus(q._1, 1), plenty))
      add(s"stor-many-from-q${q._2}")(storageRanges(all, q._1, max, BigInt(30000)))
    add("stor-big-bounded")(
      storageRanges(Seq(big.hash), big.quartiles.head, big.quartiles(2), BigInt(6000))
    )
    add("stor-big-bounded-plenty")(storageRanges(Seq(big.hash), big.quartiles.head, big.quartiles(2), plenty))
    add("stor-big-reversed")(storageRanges(Seq(big.hash), big.quartiles(2), big.quartiles.head, plenty))
    add("stor-mid-single-slot")(storageRanges(Seq(mid.hash), mid.quartiles(1), mid.quartiles(1), plenty))
    add("stor-past-last")(storageRanges(Seq(big.hash), max, max, plenty))
    add("stor-unknown-root")(storageRanges(Seq(big.hash), zero, max, plenty, fake("not a state root")))
    // ── GetByteCodes ─────────────────────────────────────────────────────────────────────────────────────────────
    val emptyCode = ByteString(kec256(Array.emptyByteArray))
    val absent = fake("no such code")
    for b <- Seq(plenty, BigInt(4096), BigInt(1)) do
      add(s"code-all-b$b")(byteCodes(m.codes, b))
      add(s"code-all-and-absent-b$b")(byteCodes(absent +: m.codes.take(10) :+ absent, b))
    add("code-empty-hash")(byteCodes(Seq(emptyCode), plenty))
    add("code-empty-and-present")(byteCodes(emptyCode +: m.codes.take(3), plenty))
    add("code-absent-only")(byteCodes(Seq(absent), plenty))
    add("code-none")(byteCodes(Seq.empty, plenty))
    add("code-duplicates")(byteCodes(Seq.fill(5)(m.codes.head), plenty))
    add("code-1100-hashes")(byteCodes(Seq.tabulate(1100)(i => m.codes(i % m.codes.size)), BigInt(1) << 40))
    add("code-reverse")(byteCodes(m.codes.reverse, BigInt(20000)))

    // ── GetTrieNodes ─────────────────────────────────────────────────────────────────────────────────────────────
    val rootPath = ByteString(Array[Byte](0x00))
    val missing = Seq(
      ByteString(Array[Byte](0x1f, 0xff.toByte, 0xff.toByte)),
      ByteString(Array[Byte](0x00, 0xff.toByte, 0xff.toByte, 0xff.toByte, 0xff.toByte)),
      ByteString(Array.fill[Byte](40)(0x11)),
      ByteString(Array[Byte](0x30)) // an odd path of 1 nibble: 0, which may or may not hold a node
    )
    for b <- Seq(plenty, BigInt(4096), BigInt(1)) do
      add(s"nodes-root-b$b")(trieNodes(Seq(Seq(rootPath)), b))
      add(s"nodes-account-first64-b$b")(trieNodes(m.accountPaths.take(64).map(Seq(_)), b))
    // every stored node of the account trie, by its path, 100 to a request
    m.accountPaths.grouped(100).zipWithIndex.foreach { (chunk, i) =>
      add(s"nodes-account-chunk$i")(trieNodes(chunk.map(Seq(_)), plenty))
    }
    add("nodes-account-all-tight")(trieNodes(m.accountPaths.map(Seq(_)), BigInt(10000)))
    add("nodes-account-missing")(trieNodes(missing.map(Seq(_)), plenty))
    add("nodes-account-mixed")(trieNodes((m.accountPaths.take(5) ++ missing ++ m.accountPaths.slice(5, 10)).map(Seq(_)), plenty))
    add("nodes-empty-pathset")(trieNodes(Seq(Seq(rootPath), Seq.empty), plenty))
    add("nodes-no-paths")(trieNodes(Seq.empty, plenty))
    add("nodes-unknown-root")(trieNodes(Seq(Seq(rootPath)), plenty, fake("not a state root")))
    // storage paths: a pathset is the account's hash, then the paths in its trie
    for (owner, paths) <- m.storagePaths.toSeq.sortBy(p => hex(p._1)) do
      paths.grouped(60).zipWithIndex.foreach { (chunk, i) =>
        add(s"nodes-storage-${hex(owner).take(8)}-chunk$i")(trieNodes(Seq(owner +: chunk), plenty))
      }
      add(s"nodes-storage-${hex(owner).take(8)}-tight")(trieNodes(Seq(owner +: paths), BigInt(3000)))
      add(s"nodes-storage-${hex(owner).take(8)}-missing")(trieNodes(Seq(owner +: missing), plenty))
    add("nodes-storage-several-accounts")(
      trieNodes(
        m.accountPaths.take(4).map(Seq(_)) ++ m.storagePaths.toSeq.sortBy(p => hex(p._1)).map((o, ps) => o +: ps.take(5)),
        plenty
      )
    )
    add("nodes-storage-shared-pair")(
      trieNodes(sharedPair.map(a => a +: m.storagePaths(a).take(8)), plenty)
    )
    add("nodes-storage-account-with-no-storage")(trieNodes(Seq(Seq(empty, rootPath)), plenty))
    add("nodes-storage-unknown-account")(trieNodes(Seq(Seq(unknownAccount, rootPath)), plenty))
    add("nodes-storage-account-only")(trieNodes(Seq(Seq(big.hash)), plenty))
    // the chains come last: how many pages a chain takes must not renumber the requests that precede it
    // a pagination chain, each next origin the key after the last one served: as a syncing peer walks the state
    locally("acct-chain") {
      var origin = zero
      var page = 0
      var more = true
      while more && page < 400 do
        val here = origin
        val p = page
        add(s"acct-chain-p$p")(accountRange(here, max, BigInt(8192)))
        val served = SnapServer.serveAccountRange(0, root, here, max, BigInt(8192), store())
        more = served.accounts.nonEmpty && served.accounts.last._1 != keys.last
        if more then origin = plus(served.accounts.last._1, 1)
        page += 1
    }

    // a chain over the big trie, continuing from the slot after the last served
    locally("stor-chain") {
      var origin = zero
      var page = 0
      var more = true
      while more && page < 400 do
        val here = origin
        add(s"stor-chain-p$page")(storageRanges(Seq(big.hash), here, max, BigInt(2048)))
        val s = store()
        val served = SnapServer.serveStorageRanges(
          0,
          root,
          Seq(big.hash),
          here,
          max,
          BigInt(2048),
          s,
          SnapServer.storageRootOf(root, s)
        )
        val slots = served.slots.headOption.getOrElse(Seq.empty)
        more = slots.nonEmpty && served.proof.nonEmpty
        if more then origin = plus(slots.last._1, 1)
        page += 1
    }

    out.toSeq

  private def locally(@annotation.unused name: String)(body: => Unit): Unit = body

  /** Serves every request on `store` and `evm` and writes the answers to `outFile`. */
  def serveAll(seedDir: File, outFile: File, store: () => MptStorage, evm: EvmCodeStorage): Int =
    val manifest = readManifest(new File(seedDir, "manifest.tsv"))
    val reqs = requests(manifest, store, evm)
    val out = new PrintWriter(outFile, "UTF-8")
    try
      reqs.foreach { (name, run) =>
        val line =
          try hex(ByteString(run()))
          catch case e: Throwable => s"threw ${e.getClass.getSimpleName}"
        out.println(s"$name\t$line")
      }
    finally out.close()
    reqs.size

  def config(dir: String): RocksDbConfig = new RocksDbConfig:
    override val createIfMissing: Boolean = false
    override val paranoidChecks: Boolean = true
    override val path: String = dir
    override val maxThreads: Int = 1
    override val maxOpenFiles: Int = 64
    override val verifyChecksums: Boolean = true
    override val levelCompaction: Boolean = true
    override val blockSize: Long = 16384
    override val blockCacheSize: Long = 33554432

  def main(args: Array[String]): Unit =
    require(args.length == 2, "usage: SnapServeOracleRunner <seedDir> <outFile>")
    val seedDir = new File(args(0))
    val ds = RocksDbDataSource(config(new File(seedDir, "hash/db").getAbsolutePath), Namespaces.nsSeq)
    try
      val evm = new EvmCodeStorage(ds)
      val store = () => new SerializingMptStorage(new ArchiveNodeStorage(new NodeStorage(ds))): MptStorage
      val n = serveAll(seedDir, new File(args(1)), store, evm)
      println(s"served $n requests from the hash store")
    finally ds.close()
