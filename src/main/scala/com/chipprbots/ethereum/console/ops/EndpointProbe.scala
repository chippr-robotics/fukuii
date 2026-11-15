package com.chipprbots.ethereum.console.ops

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

import scala.util.Try
import scala.util.control.NonFatal

/** `test-rpc` (spec 029 FR-054, contracts/actions.md): a client on the node's own listeners. For each enabled transport
  * it asks `web3_clientVersion` and reports the answer and the time; for the Engine API it sends a request with no
  * token and reports that it was refused, which is the answer a working Engine API gives. It never reads or sends the
  * JWT secret.
  *
  * It is an Observe action, and it is not invisible: its calls are calls, counted on the RPC view like any other; on a
  * node with the rate limit on it uses one request's worth of this machine's allowance; and the Engine check counts as
  * one failed authentication. The report says so (conduit, slice 7).
  */
object EndpointProbe:

  enum Kind:
    case Http, Https, Ws, Ipc, Engine

  /** One endpoint to try.
    *
    * @param host
    *   the interface it is bound to; a wildcard is dialled on loopback
    * @param path
    *   the socket file, for IPC
    */
  final case class Endpoint(kind: Kind, host: String, port: Int, path: String = "")

  val ConnectTimeout: Duration = Duration.ofSeconds(2)
  val AnswerTimeout: Duration = Duration.ofSeconds(3)

  private val Request = """{"jsonrpc":"2.0","id":1,"method":"web3_clientVersion","params":[]}"""

  /** The host to dial for an endpoint bound to `host`: itself, or loopback for a wildcard. */
  private[ops] def dialled(host: String): String = host match
    case "0.0.0.0" | ""                                => "127.0.0.1"
    case "::" | "[::]" | "0:0:0:0:0:0:0:0"             => "[::1]"
    case v6 if v6.contains(':') && !v6.startsWith("[") => s"[$v6]"
    case other                                         => other

  private val Result = """"result"\s*:\s*"((?:[^"\\]|\\.)*)"""".r
  private val Code = """"code"\s*:\s*(-?\d+)""".r

  /** What a JSON-RPC answer to `web3_clientVersion` says, in words. */
  private[ops] def verdict(status: Int, body: String): String =
    if status == 429 then "rate limited: the server refused this request as too soon after the last"
    else
      (Result.findFirstMatchIn(body), Code.findFirstMatchIn(body).map(_.group(1))) match
        case (Some(m), _)        => s"answered ${m.group(1)}"
        case (_, Some("-32601")) => "answers, and web3 is not served on this transport"
        case (_, Some(code))     => s"answered with error $code"
        case _ if status != 200  => s"answered HTTP $status"
        case _                   => "answered, and not with JSON-RPC"

  private def millis(startedNanos: Long): String =
    f"${(System.nanoTime() - startedNanos) / 1e6}%.1f ms"

  private def why(e: Throwable): String =
    val cause = Option(e.getCause).filter(_ => e.isInstanceOf[java.util.concurrent.CompletionException]).getOrElse(e)
    cause match
      case _: java.net.ConnectException                 => "nothing is listening there"
      case _: java.net.http.HttpConnectTimeoutException => s"no connection within ${ConnectTimeout.toSeconds} s"
      case _: java.net.http.HttpTimeoutException        => s"no answer within ${AnswerTimeout.toSeconds} s"
      case _: java.util.concurrent.TimeoutException     => s"no answer within ${AnswerTimeout.toSeconds} s"
      case other                                        => Option(other.getMessage).getOrElse(other.toString)

  /** A trust manager that accepts the node's own certificate, which is commonly self-signed, and keeps it to report. It
    * verifies nothing, and the report says that it did not.
    */
  final private class Recording extends X509ExtendedTrustManager:
    val seen = new AtomicReference(Option.empty[X509Certificate])
    private def keep(chain: Array[X509Certificate]): Unit = seen.set(chain.headOption)
    def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
    def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = keep(chain)
    def checkClientTrusted(chain: Array[X509Certificate], authType: String, socket: java.net.Socket): Unit = ()
    def checkServerTrusted(chain: Array[X509Certificate], authType: String, socket: java.net.Socket): Unit = keep(chain)
    def checkClientTrusted(chain: Array[X509Certificate], authType: String, engine: SSLEngine): Unit = ()
    def checkServerTrusted(chain: Array[X509Certificate], authType: String, engine: SSLEngine): Unit = keep(chain)
    def getAcceptedIssuers: Array[X509Certificate] = Array.empty

  private def client(tls: Option[Recording]): HttpClient =
    val builder = HttpClient.newBuilder().connectTimeout(ConnectTimeout)
    tls.foreach { trust =>
      val context = SSLContext.getInstance("TLS")
      context.init(null, Array(trust), null)
      builder.sslContext(context)
    }
    builder.build()

  private def post(c: HttpClient, uri: String): HttpResponse[String] =
    c.send(
      HttpRequest
        .newBuilder(URI.create(uri))
        .timeout(AnswerTimeout)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(Request))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )

  private def http(e: Endpoint, tls: Boolean): String =
    val trust = Option.when(tls)(new Recording)
    val c = client(trust)
    val started = System.nanoTime()
    try
      val answer = post(c, s"${if tls then "https" else "http"}://${dialled(e.host)}:${e.port}/")
      val certificate = trust
        .flatMap(_.seen.get)
        .fold("")(cert =>
          s"; TLS, certificate not verified by this test: ${cert.getSubjectX500Principal.getName}, " +
            s"valid until ${cert.getNotAfter.toInstant.toString.take(10)}" +
            (if cert.getSubjectX500Principal == cert.getIssuerX500Principal then ", self-signed" else "")
        )
      s"${verdict(answer.statusCode(), answer.body())} in ${millis(started)}$certificate"
    catch case NonFatal(failed) => s"FAILED: ${why(failed)}"
    finally Try(c.close())

  private def ws(e: Endpoint): String =
    val c = client(None)
    val started = System.nanoTime()
    val answered = new CompletableFuture[String]()
    val text = new StringBuilder
    val listener = new WebSocket.Listener:
      override def onText(socket: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
        text.append(data)
        if last then answered.complete(text.toString)
        socket.request(1)
        null
      override def onError(socket: WebSocket, error: Throwable): Unit =
        val _ = answered.completeExceptionally(error)
    try
      val socket = c
        .newWebSocketBuilder()
        .connectTimeout(ConnectTimeout)
        .buildAsync(URI.create(s"ws://${dialled(e.host)}:${e.port}/"), listener)
        .get(ConnectTimeout.toMillis, TimeUnit.MILLISECONDS)
      try
        socket.sendText(Request, true).get(AnswerTimeout.toMillis, TimeUnit.MILLISECONDS)
        val body = answered.get(AnswerTimeout.toMillis, TimeUnit.MILLISECONDS)
        s"${verdict(200, body)} in ${millis(started)}"
      finally Try(socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(1, TimeUnit.SECONDS))
    catch case NonFatal(failed) => s"FAILED: ${why(failed)}"
    finally Try(c.close())

  /** One request a connection, a line of JSON and a newline, as the IPC server reads it. */
  private def ipc(e: Endpoint): String =
    val started = System.nanoTime()
    try
      val socket = new org.scalasbt.ipcsocket.UnixDomainSocket(e.path)
      try
        socket.setSoTimeout(AnswerTimeout.toMillis.toInt)
        socket.getOutputStream.write((Request + "\n").getBytes(StandardCharsets.UTF_8))
        socket.getOutputStream.flush()
        val reader =
          new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream, StandardCharsets.UTF_8))
        val line = Option(reader.readLine()).getOrElse("")
        s"${verdict(200, line)} in ${millis(started)}"
      finally Try(socket.close())
    catch case NonFatal(failed) => s"FAILED: ${why(failed)}"

  /** A request with no token: a working Engine API refuses it. */
  private def engine(e: Endpoint): String =
    val c = client(None)
    val started = System.nanoTime()
    try
      val answer = post(c, s"http://${dialled(e.host)}:${e.port}/")
      answer.statusCode() match
        case 401   => s"refused a request with no token, as it must (401) in ${millis(started)}"
        case 200   => "FAILED: it ANSWERED a request with no token: the Engine API is not authenticating"
        case other => s"answered HTTP $other to a request with no token in ${millis(started)}"
    catch case NonFatal(failed) => s"FAILED: ${why(failed)}"
    finally Try(c.close())

  /** One endpoint, tried: its name, where it was dialled, and what came of it. */
  def probe(e: Endpoint): String =
    val (name, where, outcome) = e.kind match
      case Kind.Http   => ("HTTP", s"${dialled(e.host)}:${e.port}", http(e, tls = false))
      case Kind.Https  => ("HTTPS", s"${dialled(e.host)}:${e.port}", http(e, tls = true))
      case Kind.Ws     => ("WebSocket", s"${dialled(e.host)}:${e.port}", ws(e))
      case Kind.Ipc    => ("IPC", e.path, ipc(e))
      case Kind.Engine => ("Engine API", s"${dialled(e.host)}:${e.port}", engine(e))
    s"${name.padTo(11, ' ')} $where  $outcome"

  /** The handler: each enabled endpoint in turn, as the node lists them when asked. */
  def handler(endpoints: () => Seq[Endpoint], probing: Endpoint => String = probe): ActionHandler =
    ActionHandler(
      _ => Preview(Vector("Calls each enabled RPC endpoint on this machine.")),
      (_, progress) =>
        val all = endpoints()
        if all.isEmpty then Right("No RPC transport is enabled on this node: there is nothing to call.")
        else
          val rows = all.map { e =>
            progress(s"calling ${e.kind}")
            probing(e)
          }
          Right(
            (rows ++ Seq(
              "",
              "Each line is one call made by this dashboard from this machine: it is counted on the RPC view like any other,",
              "and the Engine API line is one failed authentication on its counter. No token was read or sent."
            )).mkString("\n")
          )
    )
