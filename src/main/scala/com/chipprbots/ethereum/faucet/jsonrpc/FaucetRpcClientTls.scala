package com.chipprbots.ethereum.faucet.jsonrpc

import java.security.SecureRandom
import javax.net.ssl.SSLContext

import com.typesafe.config.Config

import com.chipprbots.ethereum.security.SSLError
import com.chipprbots.ethereum.tls.OutboundTls
import com.chipprbots.ethereum.tls.TlsConfigError
import com.chipprbots.ethereum.tls.TlsMaterialLoader

/** The TLS of the faucet's client of the node (spec 030 FR-008, FR-040): `faucet.rpc-client.tls-profile`, a profile of
  * `fukuii.tls.profiles` or `system` (the default), or the deprecated `faucet.rpc-client.certificate` block, mapped to
  * a profile whose keystore is both the certificate and the trust, as the client built from it before was. Both forms
  * together are refused. A profile that cannot be loaded fails the faucet's start (FR-012: no fallback).
  */
object FaucetRpcClientTls:

  private val Section = "faucet.rpc-client"

  /** @param rawConfig
    *   the whole configuration (`faucet` is at its root)
    * @param fukuiiConfig
    *   its `fukuii` section, which holds `tls.profiles`
    */
  def context(rawConfig: Config, fukuiiConfig: Config, secureRandom: SecureRandom): Either[SSLError, SSLContext] =
    val section = rawConfig.getConfig(Section)
    val named = section.hasPath("tls-profile") && !section.getIsNull("tls-profile")
    val result: Either[TlsConfigError, SSLContext] =
      OutboundTls.legacyCertificateProfile(section, Section).flatMap {
        case Some(_) if named =>
          Left(
            TlsConfigError(
              s"$Section: set tls-profile or the deprecated certificate block, not both; keep tls-profile and a " +
                "profile under fukuii.tls.profiles"
            )
          )
        case Some(legacy) =>
          TlsMaterialLoader.loadClient(legacy).map(OutboundTls.fromMaterial(_, secureRandom))
        case None =>
          val profile = OutboundTls.profileAt(section, "tls-profile")
          OutboundTls
            .context(fukuiiConfig, profile, secureRandom)
            .left
            .map(e => TlsConfigError(s"$Section.tls-profile = $profile: ${e.message}"))
      }
    result.left.map(e => SSLError(e.message))
