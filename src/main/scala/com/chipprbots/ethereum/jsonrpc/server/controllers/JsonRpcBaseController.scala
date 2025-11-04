package com.chipprbots.ethereum.jsonrpc.server.controllers

import java.time.Duration

import cats.effect.IO

import scala.collection.immutable.ArraySeq
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

import com.typesafe.config.{Config => TypesafeConfig}
import org.json4s.DefaultFormats
import org.json4s.JsonDSL._
import org.json4s.native
import org.json4s.native.Serialization

import com.chipprbots.ethereum.jsonrpc.JsonRpcControllerMetrics
import com.chipprbots.ethereum.jsonrpc.JsonRpcError
import com.chipprbots.ethereum.jsonrpc.JsonRpcError.InternalError
import com.chipprbots.ethereum.jsonrpc.JsonRpcError.MethodNotFound
import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.jsonrpc.JsonRpcResponse
import com.chipprbots.ethereum.jsonrpc.NodeJsonRpcHealthChecker.JsonRpcHealthConfig
import com.chipprbots.ethereum.jsonrpc.serialization.JsonEncoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcHttpServer.JsonRpcHttpServerConfig
import com.chipprbots.ethereum.jsonrpc.server.ipc.JsonRpcIpcServer.JsonRpcIpcServerConfig
import com.chipprbots.ethereum.utils.Logger

trait ApisBase {
  def available: List[String]
}

trait JsonRpcBaseController {
  self: ApisBase with Logger =>

  import JsonRpcBaseController._

  /** FIXME: We are making mandatory to pass a config in all the Controllers that implements this trait when it is just
    * used for the disabled methods. We should change this behaviour in order to remove this unnecessary dependency.
    */
  val config: JsonRpcConfig
  implicit def executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

  def apisHandleFns: Map[String, PartialFunction[JsonRpcRequest, IO[JsonRpcResponse]]]

  def enabledApis: Seq[String]

  implicit val formats: DefaultFormats.type = DefaultFormats

  implicit val serialization: Serialization.type = native.Serialization

  def handleRequest(request: JsonRpcRequest): IO[JsonRpcResponse] = {
    val startTimeNanos = System.nanoTime()

    log.debug(s"received request ${request.inspect}")

    val notFoundFn: PartialFunction[JsonRpcRequest, IO[JsonRpcResponse]] = { case _ =>
      JsonRpcControllerMetrics.NotFoundMethodsCounter.increment()
      IO.pure(errorResponse(request, MethodNotFound))
    }

    val handleFn: PartialFunction[JsonRpcRequest, IO[JsonRpcResponse]] =
      enabledApis.foldLeft(notFoundFn)((fn, api) => apisHandleFns.getOrElse(api, PartialFunction.empty).orElse(fn))

    handleFn(request)
      .flatTap {
        case JsonRpcResponse(_, _, Some(JsonRpcError(code, message, extraData)), _) =>
          IO {
            log.error(
              s"JsonRpcError from request: ${request.toStringWithSensitiveInformation} - response code: $code and message: $message. " +
                s"${extraData.map(data => s"Extra info: ${data.values}")}"
            )
            JsonRpcControllerMetrics.MethodsErrorCounter.increment()
          }
        case JsonRpcResponse(_, _, None, _) =>
          IO {
            JsonRpcControllerMetrics.MethodsSuccessCounter.increment()

            val time = Duration.ofNanos(System.nanoTime() - startTimeNanos)
            JsonRpcControllerMetrics.recordMethodTime(request.method, time)
          }
      }
      .flatTap(response => IO(log.debug(s"sending response ${response.inspect}")))
      .handleErrorWith { (t: Throwable) =>
        IO {
          JsonRpcControllerMetrics.MethodsExceptionCounter.increment()
          log.error(s"Error serving request: ${request.toStringWithSensitiveInformation}", t)
        } *> IO.raiseError(t)
      }
  }

  def handle[Req, Res](
      fn: Req => IO[Either[JsonRpcError, Res]],
      rpcReq: JsonRpcRequest
  )(implicit dec: JsonMethodDecoder[Req], enc: JsonEncoder[Res]): IO[JsonRpcResponse] =
    dec.decodeJson(rpcReq.params) match {
      case Right(req) =>
        fn(req)
          .map {
            case Right(success) => successResponse(rpcReq, success)
            case Left(error)    => errorResponse(rpcReq, error)
          }
          .handleError { ex =>
            log.error("Failed to handle RPC request", ex)
            errorResponse(rpcReq, InternalError)
          }
      case Left(error) =>
        IO.pure(errorResponse(rpcReq, error))
    }

  private def successResponse[T](req: JsonRpcRequest, result: T)(implicit enc: JsonEncoder[T]): JsonRpcResponse =
    JsonRpcResponse(req.jsonrpc, Some(enc.encodeJson(result)), None, req.id.getOrElse(0))

  def errorResponse[T](req: JsonRpcRequest, error: JsonRpcError): JsonRpcResponse =
    JsonRpcResponse(req.jsonrpc, None, Some(error), req.id.getOrElse(0))

}

object JsonRpcBaseController {

  trait JsonRpcConfig {
    def apis: Seq[String]
    def accountTransactionsMaxBlocks: Int
    def minerActiveTimeout: FiniteDuration
    def httpServerConfig: JsonRpcHttpServerConfig
    def ipcServerConfig: JsonRpcIpcServerConfig
    def healthConfig: JsonRpcHealthConfig
  }

  object JsonRpcConfig {
    def apply(fukuiiConfig: TypesafeConfig, availableApis: List[String]): JsonRpcConfig = {
      import scala.concurrent.duration._
      val rpcConfig = fukuiiConfig.getConfig("network.rpc")

      new JsonRpcConfig {
        override val apis: Seq[String] = {
          val providedApis = rpcConfig.getString("apis").split(",").map(_.trim.toLowerCase)
          val invalidApis = providedApis.diff(availableApis)
          require(
            invalidApis.isEmpty,
            s"Invalid RPC APIs specified: ${invalidApis.mkString(",")}. Availables are ${availableApis.mkString(",")}"
          )
          ArraySeq.unsafeWrapArray(providedApis)
        }

        override def accountTransactionsMaxBlocks: Int = rpcConfig.getInt("account-transactions-max-blocks")
        override def minerActiveTimeout: FiniteDuration = rpcConfig.getDuration("miner-active-timeout").toMillis.millis

        override val httpServerConfig: JsonRpcHttpServerConfig = JsonRpcHttpServerConfig(fukuiiConfig)
        override val ipcServerConfig: JsonRpcIpcServerConfig = JsonRpcIpcServerConfig(fukuiiConfig)
        override val healthConfig: JsonRpcHealthConfig = JsonRpcHealthConfig(rpcConfig)
      }
    }
  }
}
