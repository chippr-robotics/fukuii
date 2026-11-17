package com.chipprbots.ethereum.ephemery

import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

import scala.util.Try

/** Where `fukuii.ephemery.source` says releases come from (spec 028 contracts/config.md): an absolute directory that
  * holds an unpacked release (no network at all), or an https base URL.
  *
  * The URL form decides the host policy, in code and not by key: a GitHub host keeps the allow-list (github.com,
  * api.github.com, `*.githubusercontent.com`); any other https host is a mirror, which may redirect anywhere over
  * https. No source makes the transport weaker than that.
  */
enum ReleaseSource:
  case Directory(path: Path)
  case Https(base: URI, policy: SourcePolicy)

object ReleaseSource:

  /** The refusal never repeats the text it was given when that text may carry credentials. */
  def parse(text: String): Either[ReleaseRefusal.BadSource, ReleaseSource] =
    val trimmed = text.trim
    if trimmed.startsWith("/") && !trimmed.exists(_ < ' ') then Right(Directory(Paths.get(trimmed)))
    else if trimmed.startsWith("https://") then
      Try(new URI(trimmed)).toOption match
        case Some(uri)
            if uri.getHost != null && uri.getRawUserInfo == null && uri.getRawQuery == null &&
              uri.getRawFragment == null =>
          val base = new URI(trimmed.stripSuffix("/"))
          val policy =
            if SourcePolicy.DefaultGithub.permits(uri.getHost) then SourcePolicy.DefaultGithub else SourcePolicy.Mirror
          Right(Https(base, policy))
        case _ =>
          Left(
            ReleaseRefusal.BadSource(
              "fukuii.ephemery.source is an https URL that carries credentials, a query or a fragment, or has no host"
            )
          )
    else if trimmed.startsWith("http://") then
      Left(ReleaseRefusal.BadSource("fukuii.ephemery.source is an http URL; only https is accepted"))
    else
      Left(
        ReleaseRefusal.BadSource(
          "fukuii.ephemery.source is neither an https URL nor an absolute directory"
        )
      )
