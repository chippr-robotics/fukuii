package com.chipprbots.ethereum.jsonrpc.server.ipc

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

import scala.jdk.CollectionConverters.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.jsonrpc.server.http.MockableJsonRpcController
import com.chipprbots.ethereum.jsonrpc.server.ipc.JsonRpcIpcServer.JsonRpcIpcServerConfig
import com.chipprbots.ethereum.testing.Tags.*

/** The IPC socket is what protects the IPC endpoint, which serves more namespaces than HTTP and WebSocket by default,
  * so the node sets it to read and write for its owner only (0600) when it binds it, as go-ethereum does. The expected
  * permissions are absolute, and the wide starting permissions of the first test are set explicitly, so the tests do
  * not depend on the umask they run under.
  */
class JsonRpcIpcServerSpec extends AnyFlatSpec with Matchers:

  private val ownerOnly: java.util.Set[PosixFilePermission] = PosixFilePermissions.fromString("rw-------")

  private def posixFileSystem: Boolean = FileSystems.getDefault.supportedFileAttributeViews.contains("posix")

  private def withTempDir[T](body: Path => T): T =
    val dir = Files.createTempDirectory("fukuii-ipc")
    try body(dir)
    finally
      Files.list(dir).iterator.asScala.foreach(Files.deleteIfExists(_))
      Files.deleteIfExists(dir)

  private def ipcConfig(socket: Path): JsonRpcIpcServerConfig = new JsonRpcIpcServerConfig:
    override val enabled: Boolean = true
    override val socketFile: String = socket.toString

  "JsonRpcIpcServer.restrictToOwner" should "reduce any permissions to read and write for the owner" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    assume(posixFileSystem, "needs a POSIX file system")
    withTempDir { dir =>
      Seq("rwxrwxrwx", "rw-rw-rw-", "r--r--r--", "rwxr-x---", "---------").foreach { start =>
        val file = Files.createTempFile(dir, "wide", ".sock")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(start))
        JsonRpcIpcServer.restrictToOwner(file)
        withClue(s"from $start: ")(Files.getPosixFilePermissions(file) shouldBe ownerOnly)
      }
    }
  }

  it should "fail loudly when the permissions cannot be set on a POSIX file system" taggedAs (UnitTest, RPCTest) in {
    assume(posixFileSystem, "needs a POSIX file system")
    withTempDir { dir =>
      val error = intercept[IllegalStateException](JsonRpcIpcServer.restrictToOwner(dir.resolve("missing.ipc")))
      error.getMessage should include("Cannot restrict the IPC socket")
      error.getMessage should include("missing.ipc")
    }
  }

  "JsonRpcIpcServer.run" should "bind a socket that only its owner can read and write" taggedAs (UnitTest, RPCTest) in {
    assume(posixFileSystem, "needs a POSIX file system")
    withTempDir { dir =>
      val socket = dir.resolve("fukuii.ipc")
      // a unix socket path is limited to about 100 characters
      assume(socket.toString.length < 100, s"temp dir path too long for a socket: $socket")
      val server = new JsonRpcIpcServer(new MockableJsonRpcController, ipcConfig(socket))
      try
        server.run()
        Files.exists(socket) shouldBe true
        Files.getPosixFilePermissions(socket) shouldBe ownerOnly
      finally server.close()
    }
  }
