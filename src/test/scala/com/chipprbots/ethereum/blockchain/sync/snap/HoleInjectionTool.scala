package com.chipprbots.ethereum.blockchain.sync.snap

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import org.apache.pekko.util.ByteString

import scala.annotation.tailrec
import scala.util.Try

import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditPosition
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditWalk
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized
import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BlockHeadersStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.StoredNodeTestAccess
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.MptTraversals

/** Makes one hole for a live run of the background audit (spec 022 T049): opens a STOPPED datadir, walks from the best
  * block's state root down `depth` hash references, always the lowest child, deletes that one node from the hash store,
  * and prints what it deleted. So the audit, which walks in key order, finds it within the first few batches.
  *
  * Run it with the node stopped (RocksDB's lock refuses it otherwise). Deeper is better: a shallow node is on the way
  * to many accounts, so import can reach it first, and then the hole is import's on-demand fetch's and not the audit's.
  * {{{
  * flock /home/dontpanic/work/.sbt.lock sbt -Dsbt.color=false -Dsbt.supershell=false -Dsbt.server.forcestart=false \
  *   "Test / runMain com.chipprbots.ethereum.blockchain.sync.snap.HoleInjectionTool /home/dontpanic/mordor-scarcity/<datadir> 6"
  * }}}
  *
  * It refuses any datadir not under [[Allowed]], symlinks resolved. It writes to one thing, and a throw is its only
  * exit other than success, so `runMain` reports a refusal as a failure without taking sbt with it.
  */
object HoleInjectionTool:

  val Allowed: Path = Paths.get("/home/dontpanic/mordor-scarcity")

  val Usage: String =
    "usage: HoleInjectionTool <stopped datadir under " + Allowed + "> <depth of 1 or more>\n" +
      "Deletes one trie node from the hash store, `depth` references below the best block's state root."

  /** The datadir, with symlinks resolved, if it is under `allowed`. */
  def checked(datadir: String, allowed: Path = Allowed): Either[String, Path] =
    Try(Paths.get(datadir).toRealPath()).toEither.left
      .map(e => s"Cannot read $datadir: ${e.getMessage}")
      .flatMap { real =>
        val root = Try(allowed.toRealPath()).getOrElse(allowed)
        if real.startsWith(root) then Right(real) else Left(s"Refusing $real: it is not under $root")
      }

  /** Where RocksDB keeps this datadir, as `db.conf` has it. */
  def rocksDbUnder(datadir: Path): Either[String, Path] =
    Seq(datadir.resolve("rocksdb"), datadir)
      .find(dir => Files.exists(dir.resolve("CURRENT")))
      .toRight(s"No RocksDB under $datadir: neither $datadir/rocksdb/CURRENT nor $datadir/CURRENT")

  final case class Hole(block: BigInt, root: ByteString, position: AuditPosition, hash: ByteString)

  /** The node `depth` references below `root`, always the lowest. */
  def pick(
      root: ByteString,
      depth: Int,
      read: ByteString => Option[Array[Byte]]
  ): Either[String, (AuditPosition, ByteString)] =
    @tailrec def go(hash: ByteString, at: AuditPosition, left: Int): Either[String, (AuditPosition, ByteString)] =
      if left == 0 then Right((at, hash))
      else
        read(hash) match
          case None => Left(s"Node ${AuditWalk.hexOf(hash)} at ${at.describe} is absent already")
          case Some(bytes) =>
            firstChild(MptTraversals.decodeNode(bytes), at) match
              case None =>
                Left(s"The trie ends at ${at.describe}, $left reference(s) short of the depth asked for")
              case Some((child, childAt)) => go(child, childAt, left - 1)
    if depth < 1 then Left(s"Depth $depth: the root itself would be no hole, since the audit waits for an absent root")
    else go(root, AuditPosition.AccountRoot, depth)

  private def firstChild(node: MptNode, at: AuditPosition): Option[(ByteString, AuditPosition)] = node match
    case branch: BranchNode =>
      branch.children.zipWithIndex.collectFirst { case (HashNode(hash), slot) => (ByteString(hash), at.child(slot)) }
    case extension: ExtensionNode =>
      extension.next match
        case HashNode(hash) => Some((ByteString(hash), at.child(extension.sharedKey)))
        case _              => None
    case _ => None

  /** Picks the hole and deletes it, from whichever store this is. A value that hashes to its key is archive's; anything
    * else is basic pruning's wrapper.
    */
  def inject(source: DataSource, depth: Int): Either[String, Hole] =
    val best = new AppStateStorage(source).getBestBlockInfo()
    val nodes = new NodeStorage(source)
    def read(hash: ByteString): Option[Array[Byte]] =
      nodes
        .get(hash)
        .map(stored => if ByteString(kec256(stored)) == hash then stored else StoredNodeTestAccess.unwrapped(stored))
    for
      header <- new BlockHeadersStorage(source).get(best.hash).toRight(s"No header for best block ${best.number}")
      root = header.stateRoot.value
      picked <- pick(root, depth, read)
    yield
      val (position, hash) = picked
      source.updateSync(Seq(DataSourceUpdateOptimized(Namespaces.NodeNamespace, Seq(hash.toArray), Nil)))
      Hole(header.number.value, root, position, hash)

  private[snap] def rocksConfig(dir: Path): RocksDbConfig =
    new RocksDbConfig:
      override val createIfMissing: Boolean = false
      override val paranoidChecks: Boolean = true
      override val path: String = dir.toString
      override val maxThreads: Int = 1
      override val maxOpenFiles: Int = 256
      override val verifyChecksums: Boolean = true
      override val levelCompaction: Boolean = true
      override val blockSize: Long = 16384
      override val blockCacheSize: Long = 67108864

  def main(args: Array[String]): Unit =
    def die(why: String): Nothing = throw new IllegalArgumentException(s"$why\n$Usage")
    args match
      case Array(datadir, depthText) =>
        val depth = depthText.toIntOption.getOrElse(die(s"Depth is not a number: $depthText"))
        val hole = for
          real <- checked(datadir)
          dir <- rocksDbUnder(real)
          found <-
            val source = RocksDbDataSource(rocksConfig(dir), Namespaces.nsSeq)
            try inject(source, depth)
            finally source.close()
        yield found
        hole.fold(
          die,
          h =>
            println(
              s"""Deleted one node from the hash store.
                 |  block    ${h.block}
                 |  root     ${AuditWalk.hexOf(h.root)}
                 |  hash     ${AuditWalk.hexOf(h.hash)}
                 |  position ${h.position.describe}
                 |Start the node, and grep its log for: [HEAL-AUDIT].*${AuditWalk.hexOf(h.hash)}""".stripMargin
            )
        )
      case _ => die("Wrong arguments")
