package com.chipprbots.ethereum.jsonrpc.server.http

import java.time.Duration

import org.apache.pekko.NotUsed
import org.apache.pekko.http.scaladsl.model.RemoteAddress
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directive0
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route

import com.google.common.base.Ticker
import com.google.common.cache.CacheBuilder
import org.json4s.DefaultFormats
import org.json4s.Formats
import org.json4s.Serialization
import org.json4s.native

import com.chipprbots.ethereum.jsonrpc.JsonRpcError
import com.chipprbots.ethereum.jsonrpc.serialization.JsonSerializers
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcHttpServer.RateLimitConfig

class RateLimit(config: RateLimitConfig) extends Directive0 with Json4sSupport {

  override implicit val serialization: Serialization = native.Serialization
  override implicit val formats: Formats = DefaultFormats + JsonSerializers.RpcErrorJsonSerializer

  private[this] lazy val minInterval = config.minRequestInterval.toSeconds

  private[this] lazy val lru = {
    val nanoDuration = config.minRequestInterval.toNanos
    val javaDuration = Duration.ofNanos(nanoDuration)
    val ticker: Ticker = new Ticker {
      override def read(): Long = getCurrentTimeNanos
    }
    CacheBuilder
      .newBuilder()
      .weakKeys()
      .expireAfterAccess(javaDuration)
      .ticker(ticker)
      .build[RemoteAddress, NotUsed]()
  }

  private[this] def isBelowRateLimit(ip: RemoteAddress): Boolean = {
    var exists = true
    lru.get(
      ip,
      () => {
        exists = false
        NotUsed
      }
    )
    exists
  }

  // Override this to test
  protected def getCurrentTimeNanos: Long = System.nanoTime()

  // Such algebras prevent if-elseif-else boilerplate in the JsonRPCServer code
  // It is also guaranteed that:
  //   1) no IP address is extracted unless config.enabled is true
  //   2) no LRU is created unless config.enabled is true
  //   3) cache is accessed only once (using get)
  override def tapply(f: Unit => Route): Route =
    if (config.enabled) {
      extractClientIP { ip =>
        if (isBelowRateLimit(ip)) {
          val err = JsonRpcError.RateLimitError(minInterval)
          complete((StatusCodes.TooManyRequests, err))
        } else {
          f.apply(())
        }
      }
    } else f.apply(())

}
