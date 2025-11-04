package com.chipprbots.ethereum

import com.chipprbots.ethereum.cli.CliLauncher
import com.chipprbots.ethereum.crypto.EcKeyGen
import com.chipprbots.ethereum.crypto.SignatureValidator
import com.chipprbots.ethereum.faucet.Faucet
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Logger

object App extends Logger {

  private def showHelp(): Unit = {
    println(
      """
        |Fukuii Ethereum Client
        |
        |Usage: fukuii [command] [options]
        |
        |Commands:
        |  fukuii [network]       Start Fukuii node (default command)
        |                         Networks: etc, eth, mordor, testnet-internal
        |
        |  cli [subcommand]       Command-line utilities
        |                         Run 'fukuii cli --help' for more information
        |
        |  keytool [options]      Key management tool
        |
        |  bootstrap [path]       Download blockchain bootstrap data
        |
        |  faucet [options]       Run faucet service
        |
        |  eckeygen [options]     Generate EC key pairs
        |
        |  signature-validator    Validate signatures
        |
        |Options:
        |  --help, -h             Show this help message
        |
        |Examples:
        |  fukuii etc                      # Start Ethereum Classic node
        |  fukuii cli --help               # Show CLI utilities help
        |  fukuii cli generate-private-key # Generate a new private key
        |
        |For more information, visit: https://github.com/chippr-robotics/fukuii
        |""".stripMargin
    )
  }

  def main(args: Array[String]): Unit = {

    val launchFukuii = "fukuii"
    val launchKeytool = "keytool"
    val downloadBootstrap = "bootstrap"
    // HIBERNATED: vm-server option commented out
    // val vmServer = "vm-server"
    val faucet = "faucet"
    val ecKeyGen = "eckeygen"
    val cli = "cli"
    val sigValidator = "signature-validator"

    args.headOption match {
      case None                  => Fukuii.main(args)
      case Some("--help" | "-h") => showHelp()
      case Some(`launchFukuii`)  => Fukuii.main(args.tail)
      case Some(`launchKeytool`) => KeyTool.main(args.tail)
      case Some(`downloadBootstrap`) =>
        Config.Db.dataSource match {
          case "rocksdb" => BootstrapDownload.main(args.tail :+ Config.Db.RocksDb.path)
        }
      // HIBERNATED: vm-server case commented out
      // case Some(`vmServer`)     => VmServerApp.main(args.tail)
      case Some(`faucet`)       => Faucet.main(args.tail)
      case Some(`ecKeyGen`)     => EcKeyGen.main(args.tail)
      case Some(`sigValidator`) => SignatureValidator.main(args.tail)
      case Some(`cli`)          => CliLauncher.main(args.tail)
      case Some(unknown) =>
        log.error(
          s"Unrecognised launcher option: $unknown\n" +
            s"Run 'fukuii --help' to see available commands."
        )
    }

  }
}
