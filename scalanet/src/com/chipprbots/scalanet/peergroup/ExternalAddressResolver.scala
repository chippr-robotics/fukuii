package com.chipprbots.scalanet.peergroup

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.URL

import cats.effect.IO

import scala.util.control.NonFatal

/** Resolve the external address based on a list of URLs that each return the IP of the caller. */
class ExternalAddressResolver(urls: List[String]) {
  def resolve: IO[Option[InetAddress]] =
    ExternalAddressResolver.checkUrls(urls)
}

object ExternalAddressResolver {
  val default = new ExternalAddressResolver(List("http://checkip.amazonaws.com", "http://bot.whatismyipaddress.com"))

  /** Retrieve the external address from a URL that returns a single line containing the IP. */
  def checkUrl(url: String): IO[InetAddress] = IO.async { cb =>
    IO {
      try {
        val ipCheckUrl = new URL(url)
        val in: BufferedReader = new BufferedReader(new InputStreamReader(ipCheckUrl.openStream()))
        cb(Right(InetAddress.getByName(in.readLine())))
      } catch {
        case NonFatal(ex) => cb(Left(ex))
      }
      None
    }
  }

  /** Try multiple URLs until an IP address is found. */
  def checkUrls(urls: List[String]): IO[Option[InetAddress]] = {
    if (urls.isEmpty) {
      IO.pure(None)
    } else {
      checkUrl(urls.head).attempt.flatMap {
        case Left(_) =>
          checkUrls(urls.tail)
        case Right(value) =>
          IO.pure(Some(value))
      }
    }
  }
}
