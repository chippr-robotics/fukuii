package com.chipprbots.ethereum.security
import java.security.SecureRandom
import javax.net.ssl.SSLContext

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.typesafe.config.Config

case class SSLError(reason: String)

object SSLContextBuilder:

  /** Builds the SSL context of the `certificate` section under `key`, read from `config` and from nothing else: a node
    * passes its own (instance) configuration, never the process's global one, so each instance of the multi-instance
    * runtime reads its own keystore. Every error names the full key (`<key>.certificate`) the operator has to fix
    * (B-148).
    *
    * @param config
    *   the configuration `key` is relative to
    * @param key
    *   the path of the section holding `certificate`, relative to `config`
    * @param keyPrefix
    *   the path of `config` in the operator's file, for the messages only (`fukuii` for an instance's configuration)
    */
  def build(
      config: Config,
      key: String,
      secureRandom: SecureRandom,
      keyPrefix: String = ""
  ): Either[SSLError, SSLContext] =
    val fullKey = (if keyPrefix.isEmpty then key else s"$keyPrefix.$key") + "." + SSLConfig.key
    Try(SSLConfig(config.getConfig(key))) match
      case Failure(e) =>
        Left(SSLError(s"$fullKey is not readable: ${e.getMessage}"))
      case Success(None) =>
        Left(SSLError(s"$fullKey is not set"))
      case Success(Some(sslConfig)) =>
        SSLContextFactory()
          .createSSLContext(sslConfig, secureRandom)
          .left
          .map(error => SSLError(s"$fullKey: ${error.reason} (keystore-path = ${sslConfig.keyStorePath})"))

trait SSLContextBuilder:
  self: SecureRandomBuilder =>

  /** See [[SSLContextBuilder.build]]. */
  def sslContext(config: Config, key: String, keyPrefix: String = ""): Either[SSLError, SSLContext] =
    SSLContextBuilder.build(config, key, secureRandom, keyPrefix) match
      case Right(context) =>
        log.debug("Loaded ssl config successful")
        Right(context)
      case Left(error) =>
        log.error(s"Loaded ssl config failure - ${error.reason}")
        Left(error)
