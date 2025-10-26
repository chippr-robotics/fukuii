package com.chipprbots.ethereum.nodebuilder

import java.lang.ProcessBuilder.Redirect

import akka.actor.ActorSystem

import com.chipprbots.ethereum.extvm.ExtVMInterface
import com.chipprbots.ethereum.extvm.VmServerApp
import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.utils.VmConfig
import com.chipprbots.ethereum.utils.VmConfig.ExternalConfig

/** HIBERNATED: External VM features are currently in hibernation.
  * External VM support is experimental and not production-ready.
  * Default configuration uses vm.mode = "internal" which is fully supported.
  */
object VmSetup extends Logger {

  import VmConfig.VmMode._

  def vm(vmConfig: VmConfig, blockchainConfig: BlockchainConfig, testMode: Boolean)(implicit
      actorSystem: ActorSystem
  ): VMImpl =
    (vmConfig.mode, vmConfig.externalConfig) match {
      case (Internal, _) =>
        log.info("Using Fukuii internal VM")
        new VMImpl

      case (External, Some(extConf)) =>
        log.warn("HIBERNATED: External VM features are experimental and not production-ready")
        startExternalVm(extConf)
        new ExtVMInterface(extConf, blockchainConfig, testMode)

      case _ =>
        throw new RuntimeException("Missing vm.external config for external VM")
    }

  private def startExternalVm(externalConfig: ExternalConfig): Unit =
    externalConfig.vmType match {
      case "iele" | "kevm" =>
        log.info(s"Starting external ${externalConfig.vmType} VM process using executable path")
        startStandardVmProcess(externalConfig)

      case "mantis" =>
        log.info("Starting external Mantis VM process using executable path")
        startMantisVmProcess(externalConfig)

      case "none" =>
        log.info("Using external VM process not managed by Mantis")
      // expect the vm to be started by external means
    }

  /** Runs a standard VM binary that takes $port and $host as input arguments
    */
  private def startStandardVmProcess(externalConfig: ExternalConfig): Unit = {
    import externalConfig._
    require(executablePath.isDefined, s"VM type '$vmType' requires the path to binary to be provided")
    // TODO: we also need host parameter in iele node
    new ProcessBuilder(executablePath.get, port.toString, host)
      .redirectOutput(Redirect.INHERIT)
      .redirectError(Redirect.INHERIT)
      .start()
  }

  private def startMantisVmProcess(externalConfig: ExternalConfig): Unit =
    if (externalConfig.executablePath.isDefined)
      startStandardVmProcess(externalConfig)
    else
      startMantisVmInThisProcess()

  private def startMantisVmInThisProcess(): Unit =
    VmServerApp.main(Array())

}
