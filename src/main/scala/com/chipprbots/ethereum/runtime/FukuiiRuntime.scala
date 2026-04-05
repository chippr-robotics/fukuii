package com.chipprbots.ethereum.runtime

import scala.jdk.CollectionConverters._
import scala.util.Try

import com.typesafe.config.ConfigFactory
import com.typesafe.config.{Config => TypesafeConfig}

import com.chipprbots.ethereum.utils.InstanceConfig
import com.chipprbots.ethereum.utils.Logger

/** Multi-network runtime that manages concurrent ChainInstances.
  *
  * Reads the `fukuii-runtime.instances` HOCON block and creates one
  * ChainInstance per entry. Each instance runs its own ActorSystem,
  * database, networking, and Engine API on separate ports.
  *
  * Usage:
  * {{{
  *   val runtime = FukuiiRuntime.fromConfig(ConfigFactory.load())
  *   runtime.startAll()
  *   // ... runtime runs until shutdown
  *   runtime.stopAll()
  * }}}
  */
class FukuiiRuntime(val instances: Map[String, ChainInstance]) extends Logger {

  def startAll(): Unit = {
    log.info(s"Starting Fukuii runtime with ${instances.size} chain instance(s): ${instances.keys.mkString(", ")}")
    instances.foreach { case (id, instance) =>
      log.info(s"Starting chain instance: $id (network=${instance.instanceConfig.blockchains.network})")
      Try(instance.start()).recover { case e =>
        log.error(s"Failed to start chain instance $id: ${e.getMessage}", e)
      }
    }
    log.info(s"All ${instances.size} chain instance(s) started")
  }

  def stopAll(): Unit = {
    log.info(s"Stopping all ${instances.size} chain instance(s)")
    instances.foreach { case (id, instance) =>
      log.info(s"Stopping chain instance: $id")
      Try(instance.shutdown.apply()).recover { case e =>
        log.error(s"Error stopping chain instance $id: ${e.getMessage}", e)
      }
    }
  }

  def getInstance(name: String): Option[ChainInstance] = instances.get(name)
}

object FukuiiRuntime extends Logger {

  /** Parse multi-instance configuration from HOCON.
    *
    * Expected structure:
    * {{{
    *   fukuii-runtime {
    *     instances {
    *       etc { include classpath("conf/etc.conf") }
    *       mordor { include classpath("conf/mordor.conf") }
    *       sepolia { include classpath("conf/sepolia.conf") }
    *     }
    *   }
    * }}}
    *
    * Each instance block must be a valid fukuii configuration (same
    * structure as the standard `fukuii {}` block).
    */
  def fromConfig(rootConfig: TypesafeConfig): FukuiiRuntime = {
    val runtimeConfig = rootConfig.getConfig("fukuii-runtime")
    val instancesConfig = runtimeConfig.getConfig("instances")

    val instances = instancesConfig.root().keySet().asScala.map { instanceId =>
      val rawInstanceConf = instancesConfig.getConfig(instanceId)
      // Each instance block wraps config in a `fukuii { ... }` section — extract it
      val instanceConf = if (rawInstanceConf.hasPath("fukuii")) rawInstanceConf.getConfig("fukuii") else rawInstanceConf
      val ic = new InstanceConfig(instanceConf, instanceId)
      log.info(s"Parsed chain instance: $instanceId (network=${ic.blockchains.network})")
      instanceId -> new ChainInstance(instanceId, ic)
    }.toMap

    new FukuiiRuntime(instances)
  }

  /** Check if multi-instance configuration is present. */
  def isMultiInstance(rootConfig: TypesafeConfig): Boolean =
    rootConfig.hasPath("fukuii-runtime.instances")
}
