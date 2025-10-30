package com.chipprbots.ethereum.jsonrpc.client

import java.util.UUID
import javax.net.ssl.SSLContext

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.ConnectionContext
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.HttpsConnectionContext
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.settings.ClientConnectionSettings
import org.apache.pekko.http.scaladsl.settings.ConnectionPoolSettings
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.stream.StreamTcpException
import org.apache.pekko.stream.scaladsl.TcpIdleTimeoutException

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import io.circe.Decoder
import io.circe.Json
import io.circe.generic.auto._
import io.circe.parser.parse
import io.circe.syntax._

import com.chipprbots.ethereum.jsonrpc.JsonRpcError
import com.chipprbots.ethereum.security.SSLError
import com.chipprbots.ethereum.utils.Logger

abstract class RpcClient(node: Uri, timeout: Duration, getSSLContext: () => Either[SSLError, SSLContext])(implicit
    system: ActorSystem,
    ec: ExecutionContext
) extends Logger {

  import RpcClient._

  lazy val connectionContext: HttpsConnectionContext = if (node.scheme.startsWith("https")) {
    getSSLContext().toOption.fold(Http().defaultClientHttpsContext)(ConnectionContext.httpsClient)
  } else {
    Http().defaultClientHttpsContext
  }

  lazy val connectionPoolSettings: ConnectionPoolSettings = ConnectionPoolSettings(system)
    .withConnectionSettings(
      ClientConnectionSettings(system)
        .withIdleTimeout(timeout)
    )

  protected def doRequest[T: Decoder](method: String, args: Seq[Json]): RpcResponse[T] =
    doJsonRequest(method, args).map(_.flatMap(getResult[T]))

  protected def doJsonRequest(
      method: String,
      args: Seq[Json]
  ): RpcResponse[Json] = {
    val request = prepareJsonRequest(method, args)
    log.info(s"Making RPC call with request: $request")
    makeRpcCall(request.asJson)
  }

  private def getResult[T: Decoder](jsonResponse: Json): Either[RpcError, T] =
    jsonResponse.hcursor.downField("error").as[JsonRpcError] match {
      case Right(error) =>
        Left(RpcClientError(s"Node returned an error: ${error.message} (${error.code})"))
      case Left(_) =>
        jsonResponse.hcursor.downField("result").as[T].left.map(f => RpcClientError(f.message))
    }

  private def makeRpcCall(jsonRequest: Json): IO[Either[RpcError, Json]] = {
    val entity = HttpEntity(ContentTypes.`application/json`, jsonRequest.noSpaces)
    val request = HttpRequest(method = HttpMethods.POST, uri = node, entity = entity)

    IO
      .fromFuture(IO(for {
        response <- Http().singleRequest(request, connectionContext, connectionPoolSettings)
        data <- Unmarshal(response.entity).to[String]
      } yield parse(data).left.map(e => ParserError(e.message))))
      .handleError { (ex: Throwable) =>
        ex match {
          case _: TcpIdleTimeoutException =>
            log.error("RPC request", ex)
            Left(ConnectionError(s"RPC request timeout"))
          case _: StreamTcpException =>
            log.error("Connection not established", ex)
            Left(ConnectionError(s"Connection not established"))
          case _ =>
            log.error("RPC request failed", ex)
            Left(RpcClientError("RPC request failed"))
        }
      }
  }

  private def prepareJsonRequest(method: String, args: Seq[Json]): Json =
    Map(
      "jsonrpc" -> "2.0".asJson,
      "method" -> method.asJson,
      "params" -> args.asJson,
      "id" -> s"${UUID.randomUUID()}".asJson
    ).asJson

}

object RpcClient {
  type RpcResponse[T] = IO[Either[RpcError, T]]

  type Secrets = Map[String, Json]

  sealed trait RpcError {
    def msg: String
  }

  case class ParserError(msg: String) extends RpcError

  case class ConnectionError(msg: String) extends RpcError

  case class RpcClientError(msg: String) extends RpcError
}
