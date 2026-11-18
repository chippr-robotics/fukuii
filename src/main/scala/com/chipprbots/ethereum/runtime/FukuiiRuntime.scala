package com.chipprbots.ethereum.runtime

import scala.jdk.CollectionConverters.*
import scala.util.Try

import com.typesafe.config.Config as TypesafeConfig

import com.chipprbots.ethereum.ConfigValidator
import com.chipprbots.ethereum.nodebuilder.NodeStartup
import com.chipprbots.ethereum.utils.InstanceConfig
import com.chipprbots.ethereum.utils.Logger

/** Multi-network runtime that manages concurrent ChainInstances.
  *
  * Reads the `fukuii-runtime.instances` HOCON block and creates one ChainInstance per entry. Each instance runs its own
  * ActorSystem, database, networking, and Engine API on separate ports.
  *
  * Usage:
  * {{{
  *   val runtime = FukuiiRuntime.fromConfig(ConfigFactory.load())
  *   runtime.startAll()
  *   // ... runtime runs until shutdown
  *   runtime.stopAll()
  * }}}
  */
class FukuiiRuntime(val instances: Map[String, ChainInstance]) extends Logger:

  /** Starts every instance; the ids of those that started.
    *
    * One instance that fails to start does not end the JVM: the others serve their own chains. Whatever it threw (a
    * refusal, or any other error, B-274) is logged at ERROR and the instance is shut down within a bound rather than
    * left half-running, with P2P and discovery up and no API. If none started, the caller exits non-zero.
    */
  def startAll(): Set[String] =
    log.info(s"Starting Fukuii runtime with ${instances.size} chain instance(s): ${instances.keys.mkString(", ")}")
    val started = instances.toSeq.flatMap { case (id, instance) =>
      log.info(s"Starting chain instance: $id (network=${instance.instanceConfig.blockchains.network})")
      NodeStartup.startOrShutDown(() => instance.start(), instance.shutdown, NodeStartup.DefaultShutdownBound) match
        case None => Some(id)
        case Some(_) =>
          log.error(s"Chain instance $id failed to start and was shut down")
          None
    }.toSet
    log.info(s"${started.size} of ${instances.size} chain instance(s) started")
    started

  def stopAll(): Unit =
    log.info(s"Stopping all ${instances.size} chain instance(s)")
    instances.foreach { case (id, instance) =>
      log.info(s"Stopping chain instance: $id")
      Try(instance.shutdown.apply()).recover { case e =>
        log.error(s"Error stopping chain instance $id: ${e.getMessage}", e)
      }
    }

  def getInstance(name: String): Option[ChainInstance] = instances.get(name)

object FukuiiRuntime extends Logger:

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
    * Each instance block must be a valid fukuii configuration (same structure as the standard `fukuii {}` block).
    */
  def fromConfig(rootConfig: TypesafeConfig): FukuiiRuntime =
    val runtimeConfig = rootConfig.getConfig("fukuii-runtime")
    val instancesConfig = runtimeConfig.getConfig("instances")

    val instances = instancesConfig
      .root()
      .keySet()
      .asScala
      .map { instanceId =>
        val rawInstanceConf = instancesConfig.getConfig(instanceId)
        // Each instance block wraps config in a `fukuii { ... }` section — extract it
        val instanceConf =
          if rawInstanceConf.hasPath("fukuii") then rawInstanceConf.getConfig("fukuii") else rawInstanceConf
        // Fatal for the single-instance launcher, and so for an instance: it would start and never sync.
        ConfigValidator.stateGuardErrors(instanceConf).headOption.foreach { error =>
          throw new IllegalArgumentException(s"Instance $instanceId: $error")
        }
        val ic = new InstanceConfig(instanceConf, instanceId)
        // ConfigValidator.validate only runs for the single-instance launcher; report removed keys here too.
        ConfigValidator.removedKeyWarnings(instanceConf).foreach(warning => log.warn(s"Instance $instanceId: $warning"))
        log.info(s"Parsed chain instance: $instanceId (network=${ic.blockchains.network})")
        instanceId -> new ChainInstance(instanceId, ic)
      }
      .toMap

    new FukuiiRuntime(instances)

  /** Check if multi-instance configuration is present. */
  def isMultiInstance(rootConfig: TypesafeConfig): Boolean =
    rootConfig.hasPath("fukuii-runtime.instances")
