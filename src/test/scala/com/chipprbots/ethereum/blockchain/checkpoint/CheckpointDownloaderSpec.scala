package com.chipprbots.ethereum.blockchain.checkpoint

import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path

import scala.util.Using

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer

import org.scalatest.BeforeAndAfterEach
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.testing.Tags.UnitTest

class CheckpointDownloaderSpec extends AnyWordSpec with Matchers with EitherValues with BeforeAndAfterEach {

  private var server: HttpServer = _
  private var port: Int = _
  private var tmpDir: Path = _

  override def beforeEach(): Unit = {
    tmpDir = Files.createTempDirectory("checkpoint-download-spec")
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    port = server.getAddress.getPort
    server.start()
  }

  override def afterEach(): Unit = {
    if (server != null) server.stop(0)
    if (tmpDir != null) {
      import scala.jdk.CollectionConverters._
      val walk = Files.walk(tmpDir)
      try
        walk.iterator.asScala.toSeq.reverse.foreach(p => Files.deleteIfExists(p))
      finally walk.close()
    }
  }

  private def urlFor(path: String): String = s"http://127.0.0.1:$port$path"

  /** Static-bytes handler that supports HTTP Range. */
  private class RangeHandler(body: Array[Byte], ignoreRange: Boolean = false) extends HttpHandler {
    @volatile var lastRangeHeader: Option[String] = None

    override def handle(exchange: HttpExchange): Unit =
      try {
        lastRangeHeader = Option(exchange.getRequestHeaders.getFirst("Range"))
        val range = if (ignoreRange) None else lastRangeHeader
        range match {
          case Some(r) if r.startsWith("bytes=") =>
            val spec = r.stripPrefix("bytes=")
            val from = spec.split("-").head.toLong.toInt
            val sliced = body.slice(from, body.length)
            exchange.getResponseHeaders.set("Content-Range", s"bytes $from-${body.length - 1}/${body.length}")
            exchange.sendResponseHeaders(206, sliced.length.toLong)
            val out = exchange.getResponseBody
            try out.write(sliced)
            finally out.close()
          case _ =>
            exchange.sendResponseHeaders(200, body.length.toLong)
            val out = exchange.getResponseBody
            try out.write(body)
            finally out.close()
        }
      } catch {
        case _: Throwable => exchange.close()
      }
  }

  "CheckpointDownloader" should {

    "fetch a full file and atomically rename it" taggedAs UnitTest in {
      val payload = ("hello " * 1024).getBytes("UTF-8")
      server.createContext("/checkpoint", new RangeHandler(payload))
      val target = tmpDir.resolve("got.bin")

      val downloader = new CheckpointDownloader()
      downloader.download(urlFor("/checkpoint"), target) shouldBe Right(())

      Files.exists(target) shouldBe true
      Files.readAllBytes(target).toSeq shouldBe payload.toSeq
      Files.exists(target.resolveSibling("got.bin.tmp")) shouldBe false
    }

    "resume from a partial .tmp via HTTP Range" taggedAs UnitTest in {
      val payload = (0 until 4096).map(_.toByte).toArray
      val handler = new RangeHandler(payload)
      server.createContext("/checkpoint", handler)
      val target = tmpDir.resolve("resume.bin")
      val tmp = target.resolveSibling("resume.bin.tmp")

      // Pre-populate `.tmp` with first half so the downloader sees a resume scenario
      Files.write(tmp, payload.slice(0, 1024))

      val downloader = new CheckpointDownloader()
      downloader.download(urlFor("/checkpoint"), target) shouldBe Right(())

      Files.readAllBytes(target).toSeq shouldBe payload.toSeq
      handler.lastRangeHeader shouldBe Some("bytes=1024-")
    }

    "fall back to fresh download when server ignores Range" taggedAs UnitTest in {
      val payload = (0 until 4096).map(_.toByte).toArray
      val handler = new RangeHandler(payload, ignoreRange = true)
      server.createContext("/checkpoint", handler)
      val target = tmpDir.resolve("ignored.bin")
      val tmp = target.resolveSibling("ignored.bin.tmp")
      Files.write(tmp, payload.slice(0, 1024))

      val downloader = new CheckpointDownloader()
      downloader.download(urlFor("/checkpoint"), target) shouldBe Right(())
      Files.readAllBytes(target).toSeq shouldBe payload.toSeq
    }

    "surface non-success HTTP status" taggedAs UnitTest in {
      server.createContext(
        "/missing",
        new HttpHandler {
          override def handle(exchange: HttpExchange): Unit = {
            val body = "not here".getBytes
            exchange.sendResponseHeaders(404, body.length.toLong)
            Using.resource(exchange.getResponseBody)(_.write(body))
          }
        }
      )
      val target = tmpDir.resolve("missing.bin")
      val result = new CheckpointDownloader().download(urlFor("/missing"), target)
      result.left.value match {
        case CheckpointDownloader.HttpError(404, _) => succeed
        case other                                  => fail(s"expected HttpError(404, _), got $other")
      }
      // .tmp is left in place for the next attempt to inspect
      Files.exists(target) shouldBe false
    }

    "skip when target already exists" taggedAs UnitTest in {
      val target = tmpDir.resolve("alreadyhere.bin")
      Files.write(target, "existing".getBytes("UTF-8"))
      server.createContext(
        "/checkpoint",
        new HttpHandler {
          override def handle(exchange: HttpExchange): Unit =
            // Should never be called
            exchange.sendResponseHeaders(500, -1)
        }
      )
      val result = new CheckpointDownloader().download(urlFor("/checkpoint"), target)
      result shouldBe Right(())
      Files.readAllBytes(target).toSeq shouldBe "existing".getBytes("UTF-8").toSeq
    }
  }
}
