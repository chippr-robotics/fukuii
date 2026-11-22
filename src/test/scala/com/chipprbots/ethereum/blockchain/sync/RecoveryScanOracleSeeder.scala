package com.chipprbots.ethereum.blockchain.sync

import java.io.File
import java.io.PrintWriter

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit.*
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.PositionTestKit.Rows

/** B-375 (spec 018 T214): seeds hash-layout RocksDB datadirs with the injected gaps of `RecoveryScansVerdictSpec`, one
  * datadir for each case, in the form the pinned v0.12.1 opens: CF `n` (nodes by hash) and CF `c` (code by hash).
  * Nothing else is in them: the scans read nothing else.
  *
  * Layout: `<out>/manifest.tsv` and `<out>/<case>/db`. The manifest has, for each case, tab separated: the name; the
  * state root; the hashes of the trie nodes left out (comma separated); the code hashes left out; the hashes of nodes
  * whose bytes are replaced by bytes that are not that node (`hash=what`, `what` being `other-node` or `junk`); what
  * the case injects, in words. `scripts/oracle/b375/run.sh` runs this, then the same scans on v0.12.1 and on staging.
  *
  * The fixture is `RecoveryScansVerdictSpec`'s: alice and bob hold one storage trie, carol another, dave has no
  * storage; alice and carol have one code, dave another.
  */
object RecoveryScanOracleSeeder:

  final case class Case(
      name: String,
      root: ByteString,
      lostNodes: Set[ByteString],
      lostCodes: Set[ByteString],
      replaced: Map[ByteString, String],
      what: String
  )

  private type Position = (Option[ByteString], ByteString)

  private def hex(b: ByteString): String = b.map("%02x".format(_)).mkString

  private def config(dir: String): RocksDbConfig = new RocksDbConfig:
    override val createIfMissing: Boolean = true
    override val paranoidChecks: Boolean = true
    override val path: String = dir
    override val maxThreads: Int = 1
    override val maxOpenFiles: Int = 32
    override val verifyChecksums: Boolean = true
    override val levelCompaction: Boolean = true
    override val blockSize: Long = 16384
    override val blockCacheSize: Long = 33554432

  /** The datadir at `dir`, opened to be written; `body` writes it, and it is closed after. */
  def withDb[A](dir: File)(body: RocksDbDataSource => A): A =
    dir.mkdirs()
    val ds = RocksDbDataSource(config(dir.getAbsolutePath), Namespaces.nsSeq)
    try body(ds)
    finally ds.close()

  /** The fixture and every case over it, by name. */
  final class Fixture:
    val hashed = new HashedState
    val codeA: ByteString = ByteString("alice's code, which carol has too")
    val codeB: ByteString = ByteString("dave's code")
    val root: ByteString = hashed.change(hashed.root)(
      _.saveCode(alice, codeA).saveCode(carol, codeA).saveCode(dave, codeB)
    )
    val rows: Map[Option[ByteString], Rows] = hashed.positions(root)

    def hashOfCode(code: ByteString): ByteString = ByteString(kec256(code.toArray))
    def hashAt(position: Position): ByteString = ByteString(kec256(rows(position._1)(position._2).toArray))

    /** The positions of one trie, the root first. */
    def stored(trie: Option[ByteString]): Seq[Position] =
      rows(trie).keys.toSeq
        .sortBy(row => (HexPrefix.decode(row.toArray)._1.length, hex(row)))
        .map(trie -> _)

    /** Every node a hash store keeps, by hash: a node of 32 bytes or more, or the root of a trie. Smaller nodes are in
      * their parent.
      */
    val nodes: Map[ByteString, ByteString] =
      (for
        (_, trieRows) <- rows.toSeq
        (row, rlp) <- trieRows.toSeq
        if rlp.length >= 32 || HexPrefix.decode(row.toArray)._1.isEmpty
      yield ByteString(kec256(rlp.toArray)) -> rlp).toMap

    private val aliceRoot = stored(Some(owner(alice))).head
    private val carolRoot = stored(Some(owner(carol))).head
    private val accountChild =
      stored(None).find(p => HexPrefix.decode(p._2.toArray)._1.length == 1).get
    private val otherNode = hashAt(stored(None).filter(p => HexPrefix.decode(p._2.toArray)._1.length == 1).apply(1))

    private def everywhere(position: Position): Set[ByteString] = Set(hashAt(position))

    val cases: Seq[Case] =
      def c(
          name: String,
          what: String,
          lost: Set[ByteString] = Set.empty,
          codes: Set[ByteString] = Set.empty,
          replaced: Map[ByteString, String] = Map.empty
      ) = Case(name, root, lost, codes, replaced, what)
      Seq(
        c("whole", "nothing"),
        c("code-shared-by-two-accounts", "the code alice and carol share", codes = Set(hashOfCode(codeA))),
        c("both-codes", "both codes", codes = Set(hashOfCode(codeA), hashOfCode(codeB))),
        c("shared-storage-root", "the storage root alice and bob share", lost = everywhere(aliceRoot)),
        c("carol-storage-root", "carol's storage root", lost = everywhere(carolRoot)),
        c(
          "storage-root-and-code",
          "carol's storage root and dave's code",
          lost = everywhere(carolRoot),
          codes = Set(hashOfCode(codeB))
        ),
        c("account-child", "a node of the account trie below the root", lost = everywhere(accountChild)),
        c(
          "account-root",
          "the root of the account trie",
          lost = Set(root)
        ),
        c(
          "storage-trie-interior",
          "a node of alice and bob's storage trie below its root",
          lost = stored(Some(owner(alice))).drop(1).headOption.map(everywhere).getOrElse(Set.empty)
        ),
        c(
          "corrupt-storage-root",
          "carol's storage root holds bytes that are no node",
          replaced = Map(hashAt(carolRoot) -> "junk")
        ),
        c(
          "corrupt-account-child",
          "an account trie node holds another node's bytes",
          replaced = Map(hashAt(accountChild) -> "other-node")
        )
      )

    /** What the node under `hash` holds in `c`: nothing, or the bytes it has, or the bytes injected. */
    def valueIn(c: Case, hash: ByteString, rlp: ByteString): Option[ByteString] =
      if c.lostNodes(hash) then None
      else
        c.replaced.get(hash) match
          case Some("junk")       => Some(ByteString(0xff.toByte, 1, 2, 3))
          case Some("other-node") => Some(nodes(otherNode))
          case _                  => Some(rlp)

  /** Writes every case under `out`, and the manifest. */
  def seed(out: File): Seq[Case] =
    val fx = new Fixture
    out.mkdirs()
    val manifest = new PrintWriter(new File(out, "manifest.tsv"), "UTF-8")
    try
      manifest.println("# name\troot\tlost-nodes\tlost-codes\treplaced-nodes\twhat")
      fx.cases.foreach { c =>
        withDb(new File(new File(out, c.name), "db")) { ds =>
          val node = new NodeStorage(ds)
          node.update(Nil, fx.nodes.toSeq.flatMap((hash, rlp) => fx.valueIn(c, hash, rlp).map(hash -> _.toArray)))
          val code = new EvmCodeStorage(ds)
          Seq(fx.codeA, fx.codeB).filterNot(b => c.lostCodes(fx.hashOfCode(b))).foreach { b =>
            code.put(fx.hashOfCode(b), b).commit()
          }
        }
        manifest.println(
          Seq(
            c.name,
            hex(c.root),
            c.lostNodes.map(hex).toSeq.sorted.mkString(","),
            c.lostCodes.map(hex).toSeq.sorted.mkString(","),
            c.replaced.map((h, w) => s"${hex(h)}=$w").toSeq.sorted.mkString(","),
            c.what
          ).mkString("\t")
        )
      }
    finally manifest.close()
    fx.cases

  def main(args: Array[String]): Unit =
    require(args.length == 1, "usage: RecoveryScanOracleSeeder <outDir>")
    seed(new File(args(0))).foreach(c => println(s"seeded ${c.name}"))
