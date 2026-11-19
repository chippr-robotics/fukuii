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

  private def ipcListening: Option[Boolean] =
    import com.chipprbots.ethereum.jsonrpc.RpcCallStats
    RpcCallStats.Node.snapshot.listening.get(RpcCallStats.Ipc)

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
        // Spec 029 FR-039: the server says it is bound, for the dashboard's RPC view, and that it closed.
        ipcListening shouldBe Some(true)
      finally server.close()
      ipcListening shouldBe Some(false)
    }
  }

  // ---- reading requests -----------------------------------------------------------------------------------------

  private def values(input: String): List[String] =
    val in = new java.io.StringReader(input)
    Iterator.continually(JsonRpcIpcServer.readJsonValue(in)).takeWhile(_.isDefined).flatten.toList

  "JsonRpcIpcServer.readJsonValue" should "read a request longer than any buffer whole (spec 030 S11)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val enode = "enode://" + "cd" * 64 + "@127.0.0.1:30305"
    val request = s"""{"jsonrpc":"2.0","id":1,"method":"admin_addNodesToAllowlist","params":[["$enode"]]}"""
    values(request + "\n") shouldBe List(request)
    values(request) shouldBe List(request) // no newline after it, as web3 IPC providers send
  }

  it should "read two requests sent back to back, with or without whitespace between them" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val a = """{"jsonrpc":"2.0","id":1,"method":"eth_blockNumber","params":[]}"""
    val b = """[{"jsonrpc":"2.0","id":2,"method":"net_version","params":[]}]"""
    values(a + b) shouldBe List(a, b)
    values(s"$a\n\n  $b\r\n") shouldBe List(a, b)
  }

  it should "not end a value at a bracket or quote inside a string" taggedAs (UnitTest, RPCTest) in {
    val tricky = """{"id":1,"method":"x","params":["}]{[", "a \"quoted\" }", "back\\slash\\"]}"""
    values(tricky + "{}") shouldBe List(tricky, "{}")
  }

  it should "answer nothing for a request cut off by the end of the stream" taggedAs (UnitTest, RPCTest) in {
    values("""{"jsonrpc":"2.0","id":1,"method":"eth_""") shouldBe Nil
  }

  it should "refuse a request longer than the limit" taggedAs (UnitTest, RPCTest) in {
    intercept[java.io.IOException](JsonRpcIpcServer.readJsonValue(new java.io.StringReader("[" + "1," * 50), 20))
  }

  "JsonRpcIpcServer.toRequest" should "keep the params and the id of a request (spec 030 S11)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    import org.json4s.*
    import org.json4s.native.JsonMethods.parse
    val nested = JsonRpcIpcServer.toRequest(parse("""{"jsonrpc":"2.0","id":7,"method":"m","params":[["a","b"]]}"""))
    nested shouldBe com.chipprbots.ethereum.jsonrpc.JsonRpcRequest(
      "2.0",
      "m",
      Some(JArray(List(JArray(List(JString("a"), JString("b")))))),
      Some(JInt(7))
    )
    JsonRpcIpcServer.toRequest(parse("""{"jsonrpc":"2.0","id":"x","method":"m"}""")).id shouldBe Some(JString("x"))
    JsonRpcIpcServer.toRequest(parse("""{"jsonrpc":"2.0","method":"m","params":[]}""")).id shouldBe None
    JsonRpcIpcServer.toRequest(parse("""{"id":1}""")).method shouldBe ""
  }
