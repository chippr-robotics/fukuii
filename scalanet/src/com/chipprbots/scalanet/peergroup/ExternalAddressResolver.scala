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
  // Timeout values for external IP address resolution
  private val ConnectionTimeoutMillis = 5000 // 5 seconds
  private val ReadTimeoutMillis = 5000       // 5 seconds

  val default = new ExternalAddressResolver(List("http://checkip.amazonaws.com", "http://bot.whatismyipaddress.com"))

  /** Retrieve the external address from a URL that returns a single line containing the IP. */
  def checkUrl(url: String): IO[InetAddress] = IO.async { cb =>
    IO {
      var maybeReader: Option[BufferedReader] = None
      try {
        val ipCheckUrl = new URL(url)
        val connection = ipCheckUrl.openConnection()
        // Set connection and read timeouts to prevent hanging indefinitely
        connection.setConnectTimeout(ConnectionTimeoutMillis)
        connection.setReadTimeout(ReadTimeoutMillis)
        val reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))
        maybeReader = Some(reader)
        
        val ipAddress = reader.readLine()
        if (ipAddress == null || ipAddress.trim.isEmpty) {
          cb(Left(new IllegalStateException(s"No IP address returned from $url")))
        } else {
          cb(Right(InetAddress.getByName(ipAddress.trim)))
        }
      } catch {
        case NonFatal(ex) => cb(Left(ex))
      } finally {
        maybeReader.foreach { reader =>
          try {
            reader.close()
          } catch {
            case NonFatal(_) => // Ignore errors during cleanup
          }
        }
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
