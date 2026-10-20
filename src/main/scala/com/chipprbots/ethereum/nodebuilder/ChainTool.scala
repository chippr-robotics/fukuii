package com.chipprbots.ethereum.nodebuilder

import java.nio.file.Paths

import scala.util.Try
import scala.util.control.NonFatal

import com.chipprbots.ethereum.blockchain.data.ChainExporter
import com.chipprbots.ethereum.utils.InstanceConfig
import com.chipprbots.ethereum.utils.Logger

/** Offline chain export and import against the node's own database, with the node stopped — go-ethereum's `geth export`
  * and `geth import` (cmd/geth/chaincmd.go).
  *
  * {{{
  *   fukuii <network> export <file> [<first> [<last>]]   # default 0..head; `.gz` gzips
  *   fukuii <network> import <file> [<file> ...]         # stops at the first invalid block
  * }}}
  *
  * The files are [[com.chipprbots.ethereum.blockchain.data.ChainFile]]s: interchangeable with geth's. What runs: the
  * database, genesis loading, validation and execution, and the actor system a [[StdNode]] builds on construction (with
  * the idle blockchain-host, peer-manager-facade and pending-transaction actors it spawns). What does not: no port is
  * bound and no connection made — no P2P server, discovery, peer dialling, JSON-RPC, Engine API, sync or mining. If a
  * node is running on the same datadir, RocksDB's lock makes the command fail at once, saying so.
  */
object ChainTool extends Logger:

  val Export = "export"
  val Import = "import"

  def isCommand(arg: String): Boolean = arg == Export || arg == Import

  /** Runs `args` (`export ...` or `import ...`) and returns the process exit code. */
  def run(args: Seq[String], instanceConfig: InstanceConfig): Int =
    args.toList match
      case Export :: file :: rest =>
        parseRange(rest) match
          case Left(err) => usage(err)
          case Right((first, last)) =>
            withNode(instanceConfig) { node =>
              val to = last.getOrElse(node.blockchainReader.getBestBlockNumber)
              ChainExporter.exportChain(node.blockchainReader, Paths.get(file), first.getOrElse(BigInt(0)), to) match
                case Right(_)  => 0
                case Left(err) => log.error(err); 1
            }
      case Import :: files if files.nonEmpty =>
        withNode(instanceConfig) { node =>
          given com.chipprbots.ethereum.utils.BlockchainConfig = node.blockchainConfig
          // A fresh datadir has no genesis yet; an initialised one is checked against the configured genesis.
          node.genesisDataLoader.loadGenesisData()
          files.iterator
            .map { file =>
              node.chainImporter.importChainFileStrict(file) match
                case Right(summary) =>
                  log.info(s"Imported $file: ${summary.imported} imported, ${summary.skipped} already present")
                  0
                case Left(failure) =>
                  log.error(s"Import of $file failed: ${failure.describe}")
                  1
            }
            .find(_ != 0)
            .getOrElse(0)
        }
      case _ => usage("expected `export <file> [<first> [<last>]]` or `import <file> [<file> ...]`")

  private def parseRange(rest: List[String]): Either[String, (Option[BigInt], Option[BigInt])] =
    def num(s: String): Either[String, BigInt] =
      val parsed =
        if s.startsWith("0x") || s.startsWith("0X") then Try(BigInt(s.drop(2), 16)) else Try(BigInt(s))
      parsed.toOption.filter(_ >= 0).toRight(s"not a block number: $s")
    rest match
      case Nil           => Right((None, None))
      case f :: Nil      => num(f).map(n => (Some(n), None))
      case f :: l :: Nil => for a <- num(f); b <- num(l) yield (Some(a), Some(b))
      case _             => Left("too many arguments")

  private def usage(problem: String): Int =
    log.error(
      s"$problem\nUsage:\n" +
        "  fukuii <network> export <file> [<first> [<last>]]   write blocks first..last (default 0..head); .gz compresses\n" +
        "  fukuii <network> import <file> [<file> ...]         import, validating every block; stops at the first error"
    )
    2

  private def withNode(instanceConfig: InstanceConfig)(f: ChainToolNode => Int): Int =
    val datadir = Try(instanceConfig.config.getString("datadir")).getOrElse("<datadir>")
    var node: Option[ChainToolNode] = None
    try
      node = Some(new ChainToolNode(instanceConfig))
      f(node.get)
    catch
      case NonFatal(e) if causes(e).exists(isLockError) =>
        log.error(
          s"Cannot open the database in $datadir: it is locked. Is a fukuii node running on $datadir? " +
            "Stop it first; export and import need the node stopped."
        )
        1
      case NonFatal(e) =>
        log.error(s"Chain tool failed: ${e.getClass.getSimpleName}: ${e.getMessage}", e)
        1
    finally node.foreach(_.shutdown())

  private def causes(e: Throwable): Iterator[Throwable] =
    Iterator.iterate(e)(_.getCause).takeWhile(_ != null).take(16)

  /** RocksDB's "While lock file: <dir>/LOCK: Resource temporarily unavailable" — another process holds the database. */
  private[nodebuilder] def isLockError(t: Throwable): Boolean =
    t.isInstanceOf[org.rocksdb.RocksDBException] && Option(t.getMessage).exists(_.contains("lock file"))

  /** A [[StdNode]] whose services are never started: only storage, genesis loading, validation and execution are
    * touched. Shutdown closes the database and nothing else, so the JVM hook cannot start (and then stop) the
    * networking components a full node's shutdown reaches for.
    */
  final private class ChainToolNode(config: InstanceConfig) extends StdNode(config):
    @volatile private var closed = false
    override def shutdown: () => Unit = () =>
      synchronized {
        if !closed then
          closed = true
          Try(storagesInstance.dataSource.close()).failed.foreach(e => log.warn("Error closing the database", e))
      }
