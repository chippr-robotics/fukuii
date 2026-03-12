package com.chipprbots.ethereum.nodebuilder

import org.apache.pekko.actor.ActorSystem

import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.utils.VmConfig

/** VM setup — only internal VM is supported. External VM features (IELE, KEVM) were experimental
  * in the original Mantis codebase and have been removed. The configuration key `vm.mode` must be
  * set to "internal" (the default).
  */
object VmSetup extends Logger {

  import VmConfig.VmMode._

  def vm(vmConfig: VmConfig, blockchainConfig: BlockchainConfig, testMode: Boolean)(implicit
      actorSystem: ActorSystem
  ): VMImpl =
    vmConfig.mode match {
      case Internal =>
        log.info("Using Fukuii internal VM")
        new VMImpl

      case _ =>
        log.error("Only vm.mode = 'internal' is supported.")
        throw new RuntimeException("External VM features are not supported. Use vm.mode = 'internal'")
    }

}
