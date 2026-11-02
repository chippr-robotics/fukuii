package com.chipprbots.ethereum.blockchain.sync.snap

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import org.apache.pekko.util.ByteString

import scala.annotation.tailrec
import scala.util.Try

import org.bouncycastle.util.encoders.Hex

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
import com.chipprbots.ethereum.domain.Account as DomainAccount
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.LeafNode
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
  * With `corrupt` as a third argument it writes junk under the node's key instead, for a live run with a bad node: on
  * basic pruning inside a valid wrapper at the count it had, so it reads as bytes failing their hash and not as an
  * entry that does not unwrap.
  *
  * With `address <hex> <account|storage|code>` it makes a hole under `newPayload` (#1694), where the deepest-first
  * advice above is the wrong way round: the next block has to touch it. Name a contract every block calls (EIP-4788's)
  * and it deletes the last node on the path to that account, or its storage root, or its code.
  *
  * It refuses any datadir not under [[Allowed]], symlinks resolved. It writes to one thing, and a throw is its only
  * exit other than success, so `runMain` reports a refusal as a failure without taking sbt with it.
  */
object HoleInjectionTool:

  val Allowed: Path = Paths.get("/home/dontpanic/mordor-scarcity")

  val Usage: String =
    "usage: HoleInjectionTool <stopped datadir under " + Allowed + "> <depth of 1 or more> [corrupt]\n" +
      "   or: HoleInjectionTool <stopped datadir> address <hex address> <account|storage|code>\n" +
      "Deletes one trie node from the hash store, `depth` references below the best block's state root, or with `corrupt` " +
      "writes junk bytes under its key."

  enum Mode:
    case Delete
    case Corrupt

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

  /** Picks the hole and makes it, in whichever store this is. A value that hashes to its key is archive's; anything
    * else is basic pruning's wrapper.
    */
  def inject(source: DataSource, depth: Int, mode: Mode = Mode.Delete): Either[String, Hole] =
    val best = new AppStateStorage(source).getBestBlockInfo()
    val nodes = new NodeStorage(source)
    def isBare(hash: ByteString, stored: Array[Byte]): Boolean = ByteString(kec256(stored)) == hash
    def read(hash: ByteString): Option[Array[Byte]] =
      nodes.get(hash).map(stored => if isBare(hash, stored) then stored else StoredNodeTestAccess.unwrapped(stored))
    for
      header <- new BlockHeadersStorage(source).get(best.hash).toRight(s"No header for best block ${best.number}")
      root = header.stateRoot.value
      picked <- pick(root, depth, read)
    yield
      val (position, hash) = picked
      val key = hash.toArray
      val write = mode match
        case Mode.Delete => DataSourceUpdateOptimized(Namespaces.NodeNamespace, Seq(key), Nil)
        case Mode.Corrupt =>
          val stored = nodes.get(hash).get
          val junk = kec256(key ++ "hole-injection".getBytes("UTF-8"))
          val bytes =
            if isBare(hash, stored) then junk
            else StoredNodeTestAccess.wrapped(junk, StoredNodeTestAccess.references(stored))
          DataSourceUpdateOptimized(Namespaces.NodeNamespace, Nil, Seq(key -> bytes))
      source.updateSync(Seq(write))
      Hole(header.number.value, root, position, hash)

  enum AccountPart:
    case Account
    case Storage
    case Code

  /** The hash-referenced nodes on the way to `key` under `root`, last first, and the value at its end. */
  def pathTo(
      root: ByteString,
      key: Array[Byte],
      read: ByteString => Option[Array[Byte]]
  ): Either[String, (List[(AuditPosition, ByteString)], ByteString)] =
    val nibbles = HexPrefix.bytesToNibbles(key)
    @tailrec def go(
        node: MptNode,
        at: AuditPosition,
        left: Array[Byte],
        seen: List[(AuditPosition, ByteString)]
    ): Either[String, (List[(AuditPosition, ByteString)], ByteString)] =
      node match
        case leaf: LeafNode => Right((seen, leaf.value))
        case hash: HashNode =>
          val id = ByteString(hash.hash)
          read(id) match
            case None        => Left(s"Node ${AuditWalk.hexOf(id)} at ${at.describe} is absent already")
            case Some(bytes) => go(MptTraversals.decodeNode(bytes), at, left, (at, id) :: seen)
        case branch: BranchNode if left.nonEmpty =>
          go(branch.children(left.head & 0xf), at.child(left.head & 0xf), left.tail, seen)
        case extension: ExtensionNode =>
          go(extension.next, at.child(extension.sharedKey), left.drop(extension.sharedKey.length), seen)
        case other => Left(s"The trie ends at ${at.describe} in ${other.getClass.getSimpleName}")
    go(HashNode(root.toArray), AuditPosition.AccountRoot, nibbles, Nil)

  /** Deletes the part of `address` that `part` names, under the best block's state root. */
  def injectAt(source: DataSource, address: ByteString, part: AccountPart): Either[String, Hole] =
    val best = new AppStateStorage(source).getBestBlockInfo()
    val nodes = new NodeStorage(source)
    def read(hash: ByteString): Option[Array[Byte]] =
      nodes.get(hash).map(s => if ByteString(kec256(s)) == hash then s else StoredNodeTestAccess.unwrapped(s))
    for
      header <- new BlockHeadersStorage(source).get(best.hash).toRight(s"No header for best block ${best.number}")
      root = header.stateRoot.value
      found <- pathTo(root, kec256(address.toArray), read)
      (path, value) = found
      account <- DomainAccount(value).toEither.left.map(e => s"Not an account at ${AuditWalk.hexOf(address)}: $e")
      target <- part match
        case AccountPart.Account => path.headOption.toRight("No hash-referenced node on the way to the account")
        case AccountPart.Storage => Right((path.head._1, account.storageRoot.value))
        case AccountPart.Code    => Right((path.head._1, account.codeHash.value))
    yield
      val (position, hash) = target
      val space = if part == AccountPart.Code then Namespaces.CodeNamespace else Namespaces.NodeNamespace
      source.updateSync(Seq(DataSourceUpdateOptimized(space, Seq(hash.toArray), Nil)))
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
      case Array(dir, "address", hex, partText) =>
        val part = AccountPart.values
          .find(_.toString.equalsIgnoreCase(partText))
          .getOrElse(die(s"Not account, storage or code: $partText"))
        val address = ByteString(Hex.decode(hex.stripPrefix("0x")))
        val hole = for
          real <- checked(dir)
          rocks <- rocksDbUnder(real)
          found <-
            val source = RocksDbDataSource(rocksConfig(rocks), Namespaces.nsSeq)
            try injectAt(source, address, part)
            finally source.close()
        yield found
        hole.fold(
          die,
          h =>
            println(
              s"""Deleted the $part of $hex.
                 |  block ${h.block}
                 |  root  ${AuditWalk.hexOf(h.root)}
                 |  hash  ${AuditWalk.hexOf(h.hash)}
                 |  under ${h.position.describe}""".stripMargin
            )
        )
      case _ => mainByDepth(args, die)

  private def mainByDepth(args: Array[String], die: String => Nothing): Unit =
    val (datadir, depthText, mode) = args match
      case Array(dir, depth)            => (dir, depth, Mode.Delete)
      case Array(dir, depth, "corrupt") => (dir, depth, Mode.Corrupt)
      case _                            => die("Wrong arguments")
    val depth = depthText.toIntOption.getOrElse(die(s"Depth is not a number: $depthText"))
    val hole = for
      real <- checked(datadir)
      dir <- rocksDbUnder(real)
      found <-
        val source = RocksDbDataSource(rocksConfig(dir), Namespaces.nsSeq)
        try inject(source, depth, mode)
        finally source.close()
    yield found
    val what = mode match
      case Mode.Delete  => "Deleted one node from the hash store."
      case Mode.Corrupt => "Wrote junk bytes over one node in the hash store."
    hole.fold(
      die,
      h =>
        println(
          s"""$what
             |  block    ${h.block}
             |  root     ${AuditWalk.hexOf(h.root)}
             |  hash     ${AuditWalk.hexOf(h.hash)}
             |  position ${h.position.describe}
             |Start the node, and grep its log for: [HEAL-AUDIT].*${AuditWalk.hexOf(h.hash)}""".stripMargin
        )
    )
