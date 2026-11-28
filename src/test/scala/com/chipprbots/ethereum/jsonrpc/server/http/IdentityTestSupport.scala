package com.chipprbots.ethereum.jsonrpc.server.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.net.ssl.SSLContext

import cats.effect.IO

import org.json4s.*
import org.json4s.native.JsonMethods.parse

import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.jsonrpc.JsonRpcResponse
import com.chipprbots.ethereum.jsonrpc.server.controllers.ApisBase
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController.JsonRpcConfig
import com.chipprbots.ethereum.utils.Logger

/** A controller whose namespaces answer every method with its own name, and whose `rpc_modules` lists what it serves:
  * enough to see which namespaces a caller was given. `transport` is the label its denials are logged under.
  */
final class NamespaceEchoController(apis: Seq[String], label: Option[String], transport: String = "http")
    extends JsonRpcBaseController
    with ApisBase
    with Logger:

  private def echo(namespace: String): PartialFunction[JsonRpcRequest, IO[JsonRpcResponse]] = {
    case r if r.method.startsWith(namespace + "_") =>
      IO.pure(JsonRpcResponse("2.0", Some(JString(r.method)), None, r.id.getOrElse(JNull)))
  }

  override def apisHandleFns: Map[String, PartialFunction[JsonRpcRequest, IO[JsonRpcResponse]]] =
    Seq("eth", "net", "web3", "admin", "debug").map(n => n -> echo(n)).toMap +
      ("rpc" -> {
        case r if r.method == "rpc_modules" =>
          IO.pure(
            JsonRpcResponse(
              "2.0",
              Some(JObject(enabledApis.map(_ -> JString("1.0")).toList)),
              None,
              r.id.getOrElse(JNull)
            )
          )
      })

  override def enabledApis: Seq[String] = apis :+ "rpc"
  override def identityRule: Option[String] = label
  override def transportName: String = transport
  override def available: List[String] = List("eth", "net", "web3", "admin", "debug")
  @SuppressWarnings(Array("scalafix:DisableSyntax.null"))
  override val config: JsonRpcConfig = null
  implicit override def executionContext: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

object IdentityTestSupport:

  def request(method: String, id: Int = 1): String = s"""{"jsonrpc":"2.0","id":$id,"method":"$method","params":[]}"""

  /** POSTs `body` to `path` over HTTPS with `ctx`; the status and the parsed body. */
  def post(ctx: SSLContext, port: Int, body: String, path: String = "/"): (Int, JValue) =
    val response = HttpClient
      .newBuilder()
      .sslContext(ctx)
      .build()
      .send(
        HttpRequest
          .newBuilder(URI.create(s"https://localhost:$port$path"))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
    (response.statusCode(), parse(response.body()))

  def errorCode(json: JValue): Option[BigInt] = (json \ "error" \ "code") match
    case JInt(c) => Some(c)
    case _       => None

  def modules(json: JValue): Set[String] = (json \ "result") match
    case JObject(fields) => fields.map(_._1).toSet
    case _               => Set.empty

  def batch(json: JValue): List[JValue] = json match
    case JArray(items) => items
    case _             => Nil
