package com.chipprbots.ethereum.security

import java.security.SecureRandom

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

import com.chipprbots.ethereum.utils.Logger

trait SecureRandomBuilder extends Logger:

  /** The `secure-random-algo` setting. A node reads it from its own configuration (`Node` overrides this with its
    * `instanceConfig`, B-339), so two instances in one JVM each use their own; the stand-alone tools (key generation,
    * the CLI, the faucet) read the process's `fukuii` configuration, as before. A def, read once by `secureRandom`.
    */
  protected def secureRandomAlgo: Option[String] =
    val fukuii: Config = ConfigFactory.load().getConfig("fukuii")
    if fukuii.hasPath("secure-random-algo") then Some(fukuii.getString("secure-random-algo")) else None

  lazy val secureRandom: SecureRandom =
    secureRandomAlgo
      .flatMap(name =>
        Try(SecureRandom.getInstance(name)) match
          case Failure(exception) =>
            log.error(
              s"Couldn't create SecureRandom instance using algorithm $name. Falling-back to default one",
              exception
            )
            None
          case Success(value) =>
            Some(value)
      )
      .getOrElse(new SecureRandom())
