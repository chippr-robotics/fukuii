package com.chipprbots.ethereum.blockchain.checkpoint

import java.nio.file.Path
import java.nio.file.Paths

import scala.collection.immutable.ArraySeq

import cats.implicits._

import com.monovore.decline.Command
import com.monovore.decline.Opts

import com.chipprbots.ethereum.db.components.RocksDbDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.nodebuilder.PruningConfigBuilder
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.InstanceConfig
import com.chipprbots.ethereum.utils.InstanceConfigProvider
import com.chipprbots.ethereum.utils.Logger

/** Operator CLI for producing `.checkpoint` archives.
  *
  * Usage from the fukuii launcher:
  * {{{
  *   fukuii sepolia checkpoint export --block 9001234 --output /tmp/sepolia.checkpoint
  *   fukuii etc checkpoint export --block 21000000 --output /tmp/etc.checkpoint --gzip
  * }}}
  *
  * Without arguments or with `--block 0` the current best block is used. The chain is selected the same way the
  * launcher selects it for the node (positional argument, e.g. `etc` / `sepolia`).
  */
object CheckpointCli extends Logger {

  private val blockOpt: Opts[Option[BigInt]] = Opts
    .option[BigInt]("block", "Block number to export. Defaults to the current best block.")
    .orNone

  private val outputOpt: Opts[Path] = Opts
    .option[String]("output", "Output file path for the .checkpoint archive.")
    .map(s => Paths.get(s))

  private val gzipOpt: Opts[Boolean] = Opts.flag("gzip", "Gzip-wrap the output.").orFalse

  private val exportCommand: Command[ExportArgs] = Command(
    name = "export",
    header = "Export the state at a block as a .checkpoint archive."
  )(
    (blockOpt, outputOpt, gzipOpt).mapN(ExportArgs.apply)
  )

  private val rootCommand: Command[Action] = Command(
    name = "checkpoint",
    header = "Produce or inspect .checkpoint archives."
  )(
    Opts.subcommand(exportCommand).map(Action.Export(_))
  )

  def main(args: Array[String]): Unit = {
    val parsed = rootCommand.parse(ArraySeq.unsafeWrapArray(args), sys.env)
    parsed match {
      case Left(help) =>
        System.err.println(help)
        sys.exit(2)
      case Right(Action.Export(args)) =>
        runExport(args) match {
          case Right(r) =>
            log.info(
              s"Exported block ${r.blockNumber} (nodes=${r.nodesExported}, bytecodes=${r.bytecodesExported}, elapsed=${r.elapsedMs / 1000}s)"
            )
          case Left(err) =>
            log.error(s"Export failed: $err")
            sys.exit(1)
        }
    }
  }

  private def runExport(args: ExportArgs): Either[CheckpointExporter.ExportError, CheckpointExporter.ExportResult] = {
    val builder = new ExportBuilder
    val storages = builder.storagesInstance.storages
    val reader = BlockchainReader(storages)
    val blockNumber = args.block.getOrElse {
      val best = storages.appStateStorage.getBestBlockNumber()
      log.info(s"--block not given; using current best block: $best")
      best
    }
    val chainId = builder.blockchainConfig.chainId
    val exporter = new CheckpointExporter(
      storages.nodeStorage,
      storages.evmCodeStorage,
      reader,
      chainId
    )
    exporter.exportArchive(blockNumber, args.output, gzip = args.gzip)
  }

  /** Minimal cake mix for read-only state access. No actors, no validators, no sync — just storage and BlockchainConfig.
    */
  private final class ExportBuilder extends InstanceConfigProvider with BlockchainConfigBuilder {
    override def instanceConfig: InstanceConfig = Config
    lazy val storagesInstance =
      new RocksDbDataSourceComponent with PruningConfigBuilder with Storages.DefaultStorages with InstanceConfigProvider {
        override def instanceConfig: InstanceConfig = Config
      }
  }

  private final case class ExportArgs(block: Option[BigInt], output: Path, gzip: Boolean)

  private sealed trait Action
  private object Action {
    final case class Export(args: ExportArgs) extends Action
  }
}
