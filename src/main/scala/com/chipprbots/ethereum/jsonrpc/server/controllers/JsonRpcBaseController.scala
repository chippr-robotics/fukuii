package com.chipprbots.ethereum.jsonrpc.server.controllers

import java.time.Duration

import cats.effect.IO

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

import com.typesafe.config.Config as TypesafeConfig
import com.typesafe.config.ConfigValueType
import org.json4s.DefaultFormats
import org.json4s.JNull
import org.json4s.native
import org.json4s.native.Serialization

import com.chipprbots.ethereum.db.storage.pathstate.StateFault
import com.chipprbots.ethereum.jsonrpc.JsonRpcControllerMetrics
import com.chipprbots.ethereum.jsonrpc.JsonRpcError
import com.chipprbots.ethereum.jsonrpc.JsonRpcError.InternalError
import com.chipprbots.ethereum.jsonrpc.JsonRpcError.MethodNotFound
import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.jsonrpc.JsonRpcResponse
import com.chipprbots.ethereum.jsonrpc.NodeJsonRpcHealthChecker.JsonRpcHealthConfig
import com.chipprbots.ethereum.jsonrpc.StateRead
import com.chipprbots.ethereum.jsonrpc.serialization.JsonEncoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcHttpServer.JsonRpcHttpServerConfig
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcWsServer.JsonRpcWsServerConfig
import com.chipprbots.ethereum.jsonrpc.server.ipc.JsonRpcIpcServer.JsonRpcIpcServerConfig
import com.chipprbots.ethereum.utils.Logger

trait ApisBase:
  def available: List[String]

trait JsonRpcBaseController:
  self: ApisBase & Logger =>

  import JsonRpcBaseController.*

  val config: JsonRpcConfig
  implicit def executionContext: ExecutionContext

  def apisHandleFns: Map[String, PartialFunction[JsonRpcRequest, IO[JsonRpcResponse]]]

  def enabledApis: Seq[String]

  given formats: DefaultFormats.type = DefaultFormats

  given serialization: Serialization.type = native.Serialization

  def handleRequest(request: JsonRpcRequest): IO[JsonRpcResponse] =
    val startTimeNanos = System.nanoTime()

    log.debug(s"received request ${request.inspect}")

    val notFoundFn: PartialFunction[JsonRpcRequest, IO[JsonRpcResponse]] = { case _ =>
      JsonRpcControllerMetrics.NotFoundMethodsCounter.increment()
      IO.pure(errorResponse(request, MethodNotFound))
    }

    // The handler of the namespace folded in last is tried first
    val handleFn: PartialFunction[JsonRpcRequest, IO[JsonRpcResponse]] =
      enabledApis.foldLeft(notFoundFn)((fn, api) => apisHandleFns.getOrElse(api, PartialFunction.empty).orElse(fn))

    handleFn(request)
      .flatTap {
        case JsonRpcResponse(_, _, Some(JsonRpcError(code, message, extraData)), _) =>
          IO {
            log.debug(
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

  /** [[handle]] for a method that executes on a block's state and changes nothing but its answer (`eth_call`, a
    * simulation, a trace): when the state moves under it the whole request runs again, on a fresh state
    * ([[StateRead.request]], spec 018 Track C). Never for a method with an effect, which would have it twice.
    */
  def handleRead[Req, Res](
      fn: Req => IO[Either[JsonRpcError, Res]],
      rpcReq: JsonRpcRequest
  )(implicit dec: JsonMethodDecoder[Req], enc: JsonEncoder[Res]): IO[JsonRpcResponse] =
    handle[Req, Res](req => StateRead.request(fn(req)), rpcReq)

  def handle[Req, Res](
      fn: Req => IO[Either[JsonRpcError, Res]],
      rpcReq: JsonRpcRequest
  )(implicit dec: JsonMethodDecoder[Req], enc: JsonEncoder[Res]): IO[JsonRpcResponse] =
    dec.decodeJson(rpcReq.params) match
      case Right(req) =>
        fn(req)
          .map {
            case Right(success) => successResponse(rpcReq, success)
            case Left(error)    => errorResponse(rpcReq, error)
          }
          .handleError {
            // A state that is not held, or that moved under the read once too often, where the method did not answer
            // it itself (spec 018 Track C): the error a client already gets for a state node that does not exist.
            case fault: StateFault => errorResponse(rpcReq, StateRead.unavailable(fault))
            case ex =>
              log.error("Failed to handle RPC request", ex)
              errorResponse(rpcReq, InternalError)
          }
      case Left(error) =>
        IO.pure(errorResponse(rpcReq, error))

  private def successResponse[T](req: JsonRpcRequest, result: T)(implicit enc: JsonEncoder[T]): JsonRpcResponse =
    JsonRpcResponse(req.jsonrpc, Some(enc.encodeJson(result)), None, req.id.getOrElse(JNull))

  def errorResponse[T](req: JsonRpcRequest, error: JsonRpcError): JsonRpcResponse =
    JsonRpcResponse(req.jsonrpc, None, Some(error), req.id.getOrElse(JNull))

object JsonRpcBaseController:

  trait JsonRpcConfig:
    /** The shared set, `network.rpc.apis`: the namespaces of every transport that has no key of its own, and of the
      * Engine API's eth_, net_ and web3_ pass-through.
      */
    def apis: Seq[String]

    /** The namespaces served over HTTP(S): `network.rpc.http.apis`, else the shared set. */
    def httpApis: Seq[String] = apis

    /** The namespaces served over WebSocket: `network.rpc.ws.apis`, else the shared set. */
    def wsApis: Seq[String] = apis

    /** The namespaces served over the IPC socket: `network.rpc.ipc.apis`, else the shared set. */
    def ipcApis: Seq[String] = apis

    def accountTransactionsMaxBlocks: Int
    def minerActiveTimeout: FiniteDuration
    def httpServerConfig: JsonRpcHttpServerConfig
    def wsServerConfig: JsonRpcWsServerConfig
    def ipcServerConfig: JsonRpcIpcServerConfig
    def healthConfig: JsonRpcHealthConfig

  object JsonRpcConfig:

    /** HTTP and WebSocket serve these namespaces until an operator sets `network.rpc.http.apis` / `network.rpc.ws.apis`
      * or `network.rpc.apis`: the standard chain namespaces and fukuii's own account-history method. `personal`,
      * `debug`, `admin` (which also carries the non-standard `net_` peer methods), `qa`, `txpool`, `trace`, `miner`,
      * `test`, `testing` and `mcp` are opt-in, as in go-ethereum.
      */
    val DefaultHttpWsApis: Seq[String] = Seq("eth", "net", "web3", "fukuii")

    /** IPC serves every production namespace by default, as go-ethereum does: the socket is protected by file
      * permissions, not by the network. Left out: `qa` (qa_mineBlocks, a dev-network tool), `test` and `testing` (test
      * mode and test harness tools), `mcp` (an opt-in feature), and `rpc`, which is always on.
      */
    val DefaultIpcApis: Seq[String] =
      Seq("eth", "net", "web3", "fukuii", "personal", "debug", "admin", "miner", "txpool", "trace")

    def apply(fukuiiConfig: TypesafeConfig, availableApis: List[String]): JsonRpcConfig =
      import scala.concurrent.duration.*
      val rpcConfig = fukuiiConfig.getConfig("network.rpc")

      // A transport uses its own key if the operator set one, else `network.rpc.apis` if the operator set it, else the
      // transport's default. The shipped config sets none of the four keys, so "set" means "set by the operator".
      val shared = parseApis(rpcConfig, "apis", availableApis)
      def forTransport(key: String, default: Seq[String]): Seq[String] =
        parseApis(rpcConfig, key, availableApis)
          .orElse(shared)
          .getOrElse(default.filter(availableApis.contains))

      new JsonRpcConfig:
        override val apis: Seq[String] = shared.getOrElse(DefaultHttpWsApis.filter(availableApis.contains))
        override val httpApis: Seq[String] = forTransport("http.apis", DefaultHttpWsApis)
        override val wsApis: Seq[String] = forTransport("ws.apis", DefaultHttpWsApis)
        override val ipcApis: Seq[String] = forTransport("ipc.apis", DefaultIpcApis)

        override def accountTransactionsMaxBlocks: Int = rpcConfig.getInt("account-transactions-max-blocks")
        override def minerActiveTimeout: FiniteDuration = rpcConfig.getDuration("miner-active-timeout").toMillis.millis

        override val httpServerConfig: JsonRpcHttpServerConfig = JsonRpcHttpServerConfig(fukuiiConfig)
        override val wsServerConfig: JsonRpcWsServerConfig = JsonRpcWsServerConfig(fukuiiConfig)
        override val ipcServerConfig: JsonRpcIpcServerConfig = JsonRpcIpcServerConfig(fukuiiConfig)
        override val healthConfig: JsonRpcHealthConfig = JsonRpcHealthConfig(rpcConfig)

    /** Reads one `apis` key under `network.rpc`: a comma-separated string or a list, names trimmed and lower-cased.
      * `None` when the key is absent. An unknown namespace is a startup error that names the key, so a typo is not
      * silently dropped.
      */
    private[server] def parseApis(
        rpcConfig: TypesafeConfig,
        path: String,
        availableApis: List[String]
    ): Option[Seq[String]] =
      if !rpcConfig.hasPath(path) then None
      else
        val provided: Seq[String] = rpcConfig.getValue(path).valueType match
          case ConfigValueType.STRING => rpcConfig.getString(path).split(",").toSeq
          case _                      => rpcConfig.getStringList(path).asScala.toSeq
        val apis = provided.map(_.trim.toLowerCase).filter(_.nonEmpty).distinct
        val invalidApis = apis.diff(availableApis)
        require(
          invalidApis.isEmpty,
          s"Invalid RPC APIs specified in network.rpc.$path: ${invalidApis.mkString(",")}. " +
            s"Availables are ${availableApis.mkString(",")}"
        )
        Some(apis)
