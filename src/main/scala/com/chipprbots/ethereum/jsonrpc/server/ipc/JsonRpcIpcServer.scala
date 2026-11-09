package com.chipprbots.ethereum.jsonrpc.server.ipc

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeoutException

import cats.effect.unsafe.IORuntime

import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.util.Try

import org.json4s.*
import org.json4s.JsonAST.JValue
import org.json4s.native
import org.json4s.native.JsonMethods.*
import org.json4s.native.Serialization
import org.scalasbt.ipcsocket.UnixDomainServerSocket

import com.chipprbots.ethereum.jsonrpc.JsonRpcError
import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.jsonrpc.JsonRpcResponse
import com.chipprbots.ethereum.jsonrpc.serialization.JsonSerializers
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController
import com.chipprbots.ethereum.jsonrpc.server.ipc.JsonRpcIpcServer.JsonRpcIpcServerConfig
import com.chipprbots.ethereum.utils.Logger

class JsonRpcIpcServer(jsonRpcController: JsonRpcBaseController, config: JsonRpcIpcServerConfig) extends Logger:

  given runtime: IORuntime = IORuntime.global

  // None until run() assigns; close() is a no-op when None.
  var serverSocket: Option[ServerSocket] = None

  def run(): Unit =
    log.info(s"Starting IPC server: ${config.socketFile}")

    removeSocketFile()

    val socket = new UnixDomainServerSocket(config.socketFile)
    // The socket file is what protects IPC, which serves more namespaces than HTTP and WebSocket by default, so it is
    // restricted to its owner as soon as it is bound; if that cannot be done, the server does not start.
    try JsonRpcIpcServer.restrictToOwner(Paths.get(config.socketFile))
    catch
      case e: Exception =>
        Try(socket.close())
        removeSocketFile()
        throw e
    serverSocket = Some(socket)
    new Thread:
      override def run(): Unit =
        while !socket.isClosed do
          val clientSocket = socket.accept()
          // Note: consider using a thread pool to limit the number of connections/requests
          new ClientThread(jsonRpcController, clientSocket).start()
    .start()

  def close(): Unit =
    serverSocket.foreach(s => Try(s.close()))
    serverSocket = None
    removeSocketFile()

  private def removeSocketFile(): Unit =
    val socketFile = new File(config.socketFile)
    if socketFile.exists() then socketFile.delete()

  class ClientThread(jsonRpcController: JsonRpcBaseController, clientSocket: Socket) extends Thread:

    native.Serialization
    implicit private val formats: Formats = JsonSerializers.formats

    private val out = clientSocket.getOutputStream
    private val in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream))

    private val awaitTimeout = 5.minutes

    private var running = true

    override def run(): Unit =
      while running do handleNextRequest()
      clientSocket.close()

    @tailrec
    private def readNextMessage(accum: String = ""): Option[JValue] =
      val buff = new Array[Char](32)
      if in.read(buff) == -1 then None
      else
        val newData = new String(buff.takeWhile(c => c != '\n' && c.toByte != 0x0))
        val dataSoFar = accum ++ newData
        parseOpt(dataSoFar) match
          case Some(json) => Some(json)
          case None       => readNextMessage(dataSoFar)

    private def handleNextRequest(): Unit =
      readNextMessage() match
        case Some(nextMsgJson) =>
          val request = nextMsgJson.extract[JsonRpcRequest]
          val responseF = jsonRpcController.handleRequest(request)
          try
            val response = responseF.timeout(awaitTimeout).unsafeRunSync()
            out.write((Serialization.write(response) + '\n').getBytes())
            out.flush()
          catch
            case _: TimeoutException =>
              // Send JSON-RPC error response for timeout
              val errorResponse = JsonRpcResponse(
                "2.0",
                None,
                Some(JsonRpcError(-32000, "Request timed out", None)),
                request.id.getOrElse(JNull)
              )
              out.write((Serialization.write(errorResponse) + '\n').getBytes())
              out.flush()
        case None =>
          running = false

object JsonRpcIpcServer extends Logger:

  /** Sets `path` to read and write for its owner only (0600), as go-ethereum does for its IPC socket, whatever the
    * umask the process runs under. A file system without POSIX permissions (Windows) is skipped with a log line. On a
    * POSIX file system a failure is an error: the socket would be open to other users, so the caller must not serve it.
    */
  def restrictToOwner(path: Path): Unit =
    Option(Files.getFileAttributeView(path, classOf[PosixFileAttributeView])) match
      case None =>
        log.info(s"IPC socket permissions not set: the file system of $path has no POSIX permissions")
      case Some(_) =>
        try
          Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
          ()
        catch
          case e: IOException =>
            throw new IllegalStateException(s"Cannot restrict the IPC socket $path to its owner: ${e.getMessage}", e)

  trait JsonRpcIpcServerConfig:
    val enabled: Boolean
    val socketFile: String

  object JsonRpcIpcServerConfig:
    import com.typesafe.config.Config as TypesafeConfig

    def apply(fukuiiConfig: TypesafeConfig): JsonRpcIpcServerConfig =
      val rpcIpcConfig = fukuiiConfig.getConfig("network.rpc.ipc")

      new JsonRpcIpcServerConfig:
        override val enabled: Boolean = rpcIpcConfig.getBoolean("enabled")
        override val socketFile: String = rpcIpcConfig.getString("socket-file")
