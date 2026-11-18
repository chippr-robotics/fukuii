package com.chipprbots.ethereum.tls

import java.nio.file.Path

/** What a listener asks of a client certificate (spec 030 FR-003). */
enum ClientAuth:
  case None, Want, Need

object ClientAuth:

  /** Exactly `none`, `want` or `need`, in lower case (FR-003). */
  def parse(value: String): Either[String, ClientAuth] = value match
    case "none" => Right(ClientAuth.None)
    case "want" => Right(ClientAuth.Want)
    case "need" => Right(ClientAuth.Need)
    case other  => Left(s"'$other' is not a client-auth mode (none, want or need)")

/** The listeners a TLS block can sit on (data-model.md). `RpcHttp` and `RpcWs` are wired (slices 1, 2). */
enum ListenerId(val label: String):
  case RpcHttp extends ListenerId("rpc-http")
  case RpcWs extends ListenerId("rpc-ws")
  case EngineApi extends ListenerId("engine-api")
  case Metrics extends ListenerId("metrics")
  case FaucetHttp extends ListenerId("faucet-http")

/** A keystore or truststore on disk: its file, its type (`pkcs12` or `jks`) and the file holding its password. */
final case class StoreRef(path: Path, storeType: String, passwordFile: Path)

/** `fukuii.tls.profiles.<name>`, or the implicit profile of the legacy `rpc.http.certificate` block.
  *
  * @param configPath
  *   where the profile sits in the operator's file, for the messages
  * @param legacy
  *   the profile comes from the deprecated `certificate` block, whose keys are named `keystore-path` and
  *   `password-file`
  */
final case class TlsProfile(
    name: String,
    configPath: String,
    keystore: Option[StoreRef],
    keyAlias: Option[String],
    truststore: Option[StoreRef],
    protocols: Seq[String],
    cipherSuites: Seq[String],
    legacy: Boolean = false
)

/** One listener's `tls { enabled, profile, client-auth }` block. */
final case class ListenerTls(
    id: ListenerId,
    enabled: Boolean,
    profile: Option[String],
    clientAuth: ClientAuth
)

/** A TLS configuration that cannot be used. `message` is `<config path>: <cause>` and never holds a secret. */
final case class TlsConfigError(message: String)
