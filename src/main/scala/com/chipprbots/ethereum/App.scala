package com.chipprbots.ethereum

import com.chipprbots.ethereum.cli.CliLauncher
import com.chipprbots.ethereum.crypto.EcKeyGen
import com.chipprbots.ethereum.crypto.SignatureValidator
import com.chipprbots.ethereum.extvm.VmServerApp
import com.chipprbots.ethereum.faucet.Faucet
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Logger

object App extends Logger {

  def main(args: Array[String]): Unit = {

    val launchFukuii = "fukuii"
    val launchKeytool = "keytool"
    val downloadBootstrap = "bootstrap"
    val vmServer = "vm-server"
    val faucet = "faucet"
    val ecKeyGen = "eckeygen"
    val cli = "cli"
    val sigValidator = "signature-validator"

    args.headOption match {
      case None                  => Fukuii.main(args)
      case Some(`launchFukuii`)  => Fukuii.main(args.tail)
      case Some(`launchKeytool`) => KeyTool.main(args.tail)
      case Some(`downloadBootstrap`) =>
        Config.Db.dataSource match {
          case "rocksdb" => BootstrapDownload.main(args.tail :+ Config.Db.RocksDb.path)
        }
      case Some(`vmServer`)     => VmServerApp.main(args.tail)
      case Some(`faucet`)       => Faucet.main(args.tail)
      case Some(`ecKeyGen`)     => EcKeyGen.main(args.tail)
      case Some(`sigValidator`) => SignatureValidator.main(args.tail)
      case Some(`cli`)          => CliLauncher.main(args.tail)
      case Some(unknown) =>
        log.error(
          s"Unrecognised launcher option $unknown, " +
            s"first parameter must be $launchKeytool, $downloadBootstrap, $launchFukuii, " +
            s"$faucet, $vmServer, $ecKeyGen, $sigValidator or $cli"
        )
    }

  }
}

