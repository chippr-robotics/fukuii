package com.chipprbots.ethereum.metrics

import java.io.ByteArrayOutputStream

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.ContentType
import org.apache.pekko.http.scaladsl.model.HttpEntity
import org.apache.pekko.http.scaladsl.model.HttpResponse
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route

import scala.concurrent.Future

import io.prometheus.metrics.config.EscapingScheme
import io.prometheus.metrics.expositionformats.ExpositionFormats
import io.prometheus.metrics.model.registry.PrometheusRegistry

import com.chipprbots.ethereum.tls.ListenerId
import com.chipprbots.ethereum.tls.TlsServerContext
import com.chipprbots.ethereum.tls.TlsServerEngine

/** The metrics endpoint over TLS (spec 030 FR-016, research D-7): `GET /metrics` on a Pekko route bound through the
  * same server TLS stack as the RPC listeners, so client auth (`want`, `need`), the handshake-failure count with its
  * reasons (FR-017) and reload (FR-030) are those of every other listener. The body is the registry's scrape in the
  * format the client's `Accept` header asks for, written by Prometheus's own writers: what the plaintext Prometheus
  * server answers for the same registry and header.
  */
object MetricsTlsServer:

  private val formats = ExpositionFormats.init()

  def route(registry: PrometheusRegistry): Route =
    (path("metrics") & get & optionalHeaderValueByName("Accept")) { accept =>
      val header = accept.orNull
      val writer = formats.findWriter(header)
      val out = new ByteArrayOutputStream()
      writer.write(out, registry.scrape(), EscapingScheme.fromAcceptHeader(header))
      ContentType.parse(writer.getContentType) match
        case Right(contentType) =>
          complete(HttpResponse(StatusCodes.OK, entity = HttpEntity(contentType, out.toByteArray)))
        case Left(_) =>
          complete(HttpResponse(StatusCodes.OK, entity = HttpEntity(out.toByteArray)))
    }

  def bind(
      interface: String,
      port: Int,
      server: TlsServerContext,
      registry: PrometheusRegistry = PrometheusRegistry.defaultRegistry
  )(using system: ActorSystem): Future[Http.ServerBinding] =
    val settings = TlsServerEngine.serverSettings(system)
    Http(system)
      .newServerAt(interface, port)
      .enableHttps(TlsServerEngine.connectionContext(server))
      .withSettings(settings)
      .bindFlow(
        TlsServerEngine.observeHandshakes(ListenerId.Metrics, TlsServerEngine.routeHandler(route(registry), settings))
      )
