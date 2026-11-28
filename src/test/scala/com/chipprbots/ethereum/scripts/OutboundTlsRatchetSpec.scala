package com.chipprbots.ethereum.scripts

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

import scala.jdk.CollectionConverters.*
import scala.util.Using

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 030 FR-045, research D-10: the allow-list ratchet of outbound TLS, `scripts/check-outbound-tls.sh`. Its engine
  * is the head writers' (`HeadWriterRatchetSpec`); here, its constructs, its verdicts and this repository.
  */
class OutboundTlsRatchetSpec extends AnyFlatSpec with Matchers:

  private val script: Path = Path.of("scripts/check-outbound-tls.sh").toAbsolutePath

  final private case class Outcome(exit: Int, output: String)

  private def run(args: String*): Outcome =
    val process = new ProcessBuilder(("bash" +: script.toString +: args)*).redirectErrorStream(true).start()
    val output = new String(process.getInputStream.readAllBytes(), UTF_8)
    Outcome(process.waitFor(), output)

  private def check(sources: Map[String, String], listed: String): Outcome =
    val root = Files.createTempDirectory("outbound-tls-ratchet")
    try
      (sources.map((file, text) => s"src/main/scala/$file" -> text) + ("allowlist.txt" -> listed)).foreach {
        (file, text) =>
          val path = root.resolve(file)
          Files.createDirectories(path.getParent)
          Files.writeString(path, text)
      }
      run("--root", root.toString, "--allowlist", root.resolve("allowlist.txt").toString)
    finally Using.resource(Files.walk(root))(_.iterator.asScala.toSeq.reverse.foreach(Files.delete))

  private val client = "a/Client.scala" ->
    """package a
      |class Client:
      |  val ctx = SSLContext.getInstance("TLS")
      |  // SSLContext.getInstance("TLS") in a comment is not counted
      |  val pekko = ConnectionContext.httpsClient(ctx)
      |  val jdk = HttpClient.newBuilder().build()
      |  val in = URI.create(u).toURL().openStream()
      |  def go = Http().singleRequest(r, pekko)
      |""".stripMargin

  private val listed =
    """ssl-context 1 src/main/scala/a/Client.scala USES moved later
      |https-context 1 src/main/scala/a/Client.scala USES moved later
      |jdk-client 1 src/main/scala/a/Client.scala USES moved later
      |url-open 1 src/main/scala/a/Client.scala PLAIN an http URL
      |pekko-request 1 src/main/scala/a/Client.scala USES the factory's context
      |""".stripMargin

  "The outbound TLS ratchet" should "know its five constructs, and pass a tree whose every one is listed" taggedAs
    UnitTest in {
      val outcome = check(Map(client), listed)
      withClue(outcome.output)(outcome.exit shouldBe 0)
    }

  it should "fail a planted SSLContext that the list does not name, saying what to add" taggedAs UnitTest in {
    val planted = "b/Rogue.scala" -> "package b\nobject Rogue:\n  val c = SSLContext.getInstance(\"TLSv1.2\")\n"
    val outcome = check(Map(client, planted), listed)
    outcome.exit shouldBe 1
    outcome.output should include("NOT LISTED: ssl-context in src/main/scala/b/Rogue.scala (1)")
  }

  it should "fail a stale line, and a line with no verdict or no reason" taggedAs UnitTest in {
    val stale = listed + "url-open 1 src/main/scala/a/Gone.scala PLAIN removed\n"
    check(Map(client), stale).output should include("NO LONGER USED: url-open in src/main/scala/a/Gone.scala")
    check(Map(client), listed.replace("PLAIN an http URL", "because")).output should include("NO VERDICT")
    check(Map(client), listed.replace(" PLAIN an http URL", "")).output should include("NO REASON")
  }

  it should "pass this repository as its allow-list says" taggedAs UnitTest in {
    val outcome = run()
    withClue(outcome.output)(outcome.exit shouldBe 0)
  }
