package com.chipprbots.ethereum.network

import java.util.concurrent.Callable

import scala.jdk.CollectionConverters._

import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.classic.methods.HttpPost
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.io.entity.ByteArrayEntity
import org.apache.hc.core5.http.io.entity.EntityUtils
import org.jupnp.model.message.StreamRequestMessage
import org.jupnp.model.message.StreamResponseMessage
import org.jupnp.model.message.UpnpHeaders
import org.jupnp.model.message.UpnpMessage
import org.jupnp.model.message.UpnpRequest
import org.jupnp.model.message.UpnpResponse
import org.jupnp.transport.spi.AbstractStreamClient
import org.jupnp.transport.spi.StreamClientConfiguration

import com.chipprbots.ethereum.utils.Logger

/** Apache HttpClient-based StreamClient implementation for JupnP.
  * 
  * This implementation uses Apache HttpComponents Client 5.x instead of
  * java.net.HttpURLConnection, avoiding the URLStreamHandlerFactory issue
  * that occurs with the default JupnP StreamClient implementations.
  * 
  * This is a minimal implementation that supports the basic HTTP operations
  * needed for UPnP port forwarding.
  */
class ApacheHttpClientStreamClient(val configuration: StreamClientConfiguration)
    extends AbstractStreamClient[StreamClientConfiguration, ApacheHttpClientStreamClient.HttpCallable]()
    with Logger {

  private val httpClient: CloseableHttpClient = {
    import org.apache.hc.client5.http.config.RequestConfig
    import org.apache.hc.core5.util.Timeout
    
    val timeoutMillis = configuration.getTimeoutSeconds() * 1000
    val requestConfig = RequestConfig.custom()
      .setConnectionRequestTimeout(Timeout.ofMilliseconds(timeoutMillis))
      .setResponseTimeout(Timeout.ofMilliseconds(timeoutMillis))
      .build()
    
    HttpClients.custom()
      .setDefaultRequestConfig(requestConfig)
      .build()
  }

  override def getConfiguration(): StreamClientConfiguration = configuration

  override protected def createRequest(
      requestMessage: StreamRequestMessage
  ): ApacheHttpClientStreamClient.HttpCallable = {
    new ApacheHttpClientStreamClient.HttpCallable(requestMessage, httpClient)
  }

  override protected def createCallable(
      requestMessage: StreamRequestMessage,
      httpCallable: ApacheHttpClientStreamClient.HttpCallable
  ): java.util.concurrent.Callable[StreamResponseMessage] = {
    httpCallable
  }

  override def stop(): Unit = {
    try {
      httpClient.close()
    } catch {
      case ex: Exception =>
        log.warn("Error closing Apache HttpClient", ex)
    }
  }

  override protected def abort(callable: ApacheHttpClientStreamClient.HttpCallable): Unit = {
    callable.abort()
  }

  override protected def logExecutionException(t: Throwable): Boolean = {
    log.warn("HTTP request execution failed", t)
    true
  }
}

object ApacheHttpClientStreamClient {

  /** Callable that executes HTTP requests using Apache HttpClient. */
  class HttpCallable(
      requestMessage: StreamRequestMessage,
      httpClient: CloseableHttpClient
  ) extends Callable[StreamResponseMessage]
      with Logger {

    @volatile private var aborted = false

    def abort(): Unit = {
      aborted = true
    }

    override def call(): StreamResponseMessage = {
      if (aborted) {
        return null
      }

      try {
        val uri = requestMessage.getOperation().getURI()
        
        requestMessage.getOperation().getMethod match {
          case UpnpRequest.Method.GET =>
            executeGet(uri.toString())
          case UpnpRequest.Method.POST =>
            executePost(uri.toString())
          case method =>
            log.warn(s"Unsupported HTTP method: $method")
            // Return new response with error status
            new StreamResponseMessage(new UpnpResponse(UpnpResponse.Status.METHOD_NOT_SUPPORTED))
        }
      } catch {
        case ex: Exception if !aborted =>
          log.warn(s"HTTP request failed: ${ex.getMessage}")
          // Return new response with error status
          new StreamResponseMessage(new UpnpResponse(UpnpResponse.Status.INTERNAL_SERVER_ERROR))
        case _: Exception =>
          null
      }
    }

    /** Helper method to populate StreamResponseMessage from Apache HttpClient response */
    private def populateResponse(
        statusCode: Int,
        statusMessage: String,
        response: org.apache.hc.core5.http.ClassicHttpResponse
    ): StreamResponseMessage = {
      val upnpResponse = new UpnpResponse(statusCode, statusMessage)
      val streamResponse = new StreamResponseMessage(upnpResponse)

      // Set response headers
      val headers = new UpnpHeaders()
      response.headerIterator().asScala.foreach { header =>
        headers.add(header.getName(), header.getValue())
      }
      streamResponse.setHeaders(headers)

      // Set response body
      val entity = response.getEntity()
      if (entity != null) {
        val bodyBytes = EntityUtils.toByteArray(entity)
        streamResponse.setBody(UpnpMessage.BodyType.BYTES, bodyBytes)
        // Use charset from Content-Type if available, otherwise UTF-8
        val charset = Option(entity.getContentType())
          .flatMap { contentTypeStr =>
            try {
              Option(ContentType.parse(contentTypeStr).getCharset).map(_.name())
            } catch {
              case _: Exception => None
            }
          }
          .getOrElse("UTF-8")
        // setBodyCharacters expects bytes, properly encode the string representation
        streamResponse.setBodyCharacters(new String(bodyBytes, charset).getBytes(charset))
      }

      streamResponse
    }

    private def executeGet(uri: String): StreamResponseMessage = {
      val request = new HttpGet(uri)
      
      // Set request headers
      requestMessage.getHeaders().asScala.foreach { case (name, values) =>
        values.asScala.foreach { value =>
          request.addHeader(name, value)
        }
      }

      httpClient.execute(request, response => {
        if (aborted) {
          null
        } else {
          populateResponse(response.getCode(), response.getReasonPhrase(), response)
        }
      })
    }

    private def executePost(uri: String): StreamResponseMessage = {
      val request = new HttpPost(uri)
      
      // Set request headers
      requestMessage.getHeaders().asScala.foreach { case (name, values) =>
        values.asScala.foreach { value =>
          request.addHeader(name, value)
        }
      }

      // Set request body
      if (requestMessage.hasBody()) {
        val bodyBytes = requestMessage.getBodyBytes()
        val contentType = requestMessage.getContentTypeHeader()
        val entity = new ByteArrayEntity(
          bodyBytes,
          if (contentType != null) {
            try {
              ContentType.parse(contentType.toString())
            } catch {
              case ex: Exception =>
                log.warn(s"Invalid content type header: '$contentType'. Using default.", ex)
                null
            }
          } else null
        )
        request.setEntity(entity)
      }

      httpClient.execute(request, response => {
        if (aborted) {
          null
        } else {
          populateResponse(response.getCode(), response.getReasonPhrase(), response)
        }
      })
    }
  }
}
