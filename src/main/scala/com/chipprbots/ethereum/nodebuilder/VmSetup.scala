package com.chipprbots.ethereum.nodebuilder

// import java.lang.ProcessBuilder.Redirect

import org.apache.pekko.actor.ActorSystem

import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.utils.VmConfig
// import com.chipprbots.ethereum.utils.VmConfig.ExternalConfig

/** HIBERNATED: External VM features are currently in hibernation. External VM support is experimental and not
  * production-ready. Default configuration uses vm.mode = "internal" which is fully supported. All external VM code
  * paths have been commented out.
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

      // HIBERNATED: External VM code path commented out
      // case (External, Some(extConf)) =>
      //   log.warning("HIBERNATED: External VM features are experimental and not production-ready")
      //   startExternalVm(extConf)
      //   new ExtVMInterface(extConf, blockchainConfig, testMode)

      case _ =>
        log.error("External VM mode is hibernated. Only vm.mode = 'internal' is supported.")
        throw new RuntimeException("External VM features are hibernated. Use vm.mode = 'internal'")
    }

  // HIBERNATED: All external VM methods commented out
  /*
  private def startExternalVm(externalConfig: ExternalConfig): Unit =
    externalConfig.vmType match {
      case "iele" | "kevm" =>
        log.info(s"Starting external ${externalConfig.vmType} VM process using executable path")
        startStandardVmProcess(externalConfig)

      case "fukuii" =>
        log.info("Starting external Fukuii VM process using executable path")
        startFukuiiVmProcess(externalConfig)

      case "none" =>
        log.info("Using external VM process not managed by Fukuii")
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

  private def startFukuiiVmProcess(externalConfig: ExternalConfig): Unit =
    if (externalConfig.executablePath.isDefined)
      startStandardVmProcess(externalConfig)
    else
      startFukuiiVmInThisProcess()

  private def startFukuiiVmInThisProcess(): Unit =
    VmServerApp.main(Array())
   */

}
