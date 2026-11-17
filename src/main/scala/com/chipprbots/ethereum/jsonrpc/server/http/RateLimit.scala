package com.chipprbots.ethereum.jsonrpc.server.http

import java.time.Duration

import org.apache.pekko.NotUsed
import org.apache.pekko.http.scaladsl.model.RemoteAddress
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directive0
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route

import com.google.common.base.Ticker
import com.google.common.cache.CacheBuilder
import org.json4s.DefaultFormats
import org.json4s.Formats
import org.json4s.Serialization
import org.json4s.native

import com.chipprbots.ethereum.jsonrpc.JsonRpcError
import com.chipprbots.ethereum.jsonrpc.RpcCallStats
import com.chipprbots.ethereum.jsonrpc.serialization.JsonSerializers
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcHttpServer.RateLimitConfig

class RateLimit(config: RateLimitConfig, stats: RpcCallStats = RpcCallStats.Node) extends Directive0 with Json4sSupport:

  implicit override val serialization: Serialization = native.Serialization
  implicit override val formats: Formats = DefaultFormats + JsonSerializers.RpcErrorJsonSerializer

  private lazy val minInterval = config.minRequestInterval.toSeconds

  private lazy val lru =
    val nanoDuration = config.minRequestInterval.toNanos
    val javaDuration = Duration.ofNanos(nanoDuration)
    val ticker: Ticker = new Ticker:
      override def read(): Long = getCurrentTimeNanos
    CacheBuilder
      .newBuilder()
      .weakKeys()
      .expireAfterAccess(javaDuration)
      .ticker(ticker)
      .build[RemoteAddress, NotUsed]()

  private def isBelowRateLimit(ip: RemoteAddress): Boolean =
    var exists = true
    lru.get(
      ip,
      () =>
        exists = false
        NotUsed
    )
    exists

  // Override this to test
  protected def getCurrentTimeNanos: Long = System.nanoTime()

  // Such algebras prevent if-elseif-else boilerplate in the JsonRPCServer code
  // It is also guaranteed that:
  //   1) no IP address is extracted unless config.enabled is true
  //   2) no LRU is created unless config.enabled is true
  //   3) cache is accessed only once (using get)
  override def tapply(f: Unit => Route): Route =
    if config.enabled then
      extractClientIP { ip =>
        if isBelowRateLimit(ip) then
          // Counted for the dashboard (spec 029 FR-039); it cannot throw, and the refusal is what it was.
          stats.rateLimited()
          val err = JsonRpcError.RateLimitError(minInterval)
          complete((StatusCodes.TooManyRequests, err))
        else f.apply(())
      }
    else f.apply(())
