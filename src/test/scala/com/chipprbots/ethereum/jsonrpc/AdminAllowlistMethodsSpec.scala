package com.chipprbots.ethereum.jsonrpc

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit

import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import com.typesafe.config.ConfigFactory
import org.json4s.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.jsonrpc.AdminService.*
import com.chipprbots.ethereum.network.AllowlistEdit
import com.chipprbots.ethereum.network.BlockedIPRegistry
import com.chipprbots.ethereum.network.NodeAllowlist
import com.chipprbots.ethereum.network.NodeAllowlistStore
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config

/** Spec 030 FR-056, FR-059 (contracts/admin-allowlist.md): `admin_addNodesToAllowlist`,
  * `admin_removeNodesFromAllowlist` and `admin_getNodesAllowlist` as the service answers them: every row of the
  * contract's error table with its code, all-or-nothing, the shape of the list, and an INFO line naming the caller.
  */
class AdminAllowlistMethodsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val testKit = ActorTestKit()
  given org.apache.pekko.actor.typed.Scheduler = testKit.scheduler
  given IORuntime = IORuntime.global
  override protected def afterAll(): Unit = testKit.shutdownTestKit()

  private val ha = "aa" * 64
  private val hb = "bb" * 64
  private val hd = "dd" * 64

  private def service(store: Option[NodeAllowlistStore]): AdminService =
    new AdminService(
      new java.util.concurrent.atomic.AtomicReference(null),
      null,
      null,
      Config.blockchains.blockchainConfig,
      5.seconds,
      "/tmp",
      new BlockedIPRegistry(Set.empty),
      null,
      () => cats.effect.IO.pure(com.chipprbots.ethereum.blockchain.sync.SyncProtocol.Status.NotSyncing),
      allowlist = store.map(_.allowlist).getOrElse(NodeAllowlist.Disabled),
      allowlistStore = store
    )

  private def store(file: Option[Path]): NodeAllowlistStore =
    val fileLine = file.fold("")(p => s"""file = "$p"""")
    NodeAllowlistStore
      .fromConfig(Some(ConfigFactory.parseString(s"""enabled = true, nodes = ["$ha"], $fileLine""")), Nil, Nil, None)
      .toOption
      .flatten
      .get

  private def withFile[A](content: String)(body: (Path, AdminService) => A): A =
    val f = Files.createTempFile("allowlist", ".json")
    Files.writeString(f, content)
    try body(f, service(Some(store(Some(f)))))
    finally Files.deleteIfExists(f)

  private def edit(svc: AdminService, op: AllowlistEdit, entries: String*) =
    svc.editAllowlist(op, AdminAllowlistEditRequest(entries), "ops").unsafeRunSync()

  "admin_addNodesToAllowlist" should "add, persist and log the caller's rule" taggedAs (UnitTest, RPCTest) in {
    val logger = LoggerFactory.getLogger(classOf[AdminService]).asInstanceOf[LogbackLogger]
    val level = logger.getLevel
    logger.setLevel(Level.INFO)
    val appender = com.chipprbots.ethereum.testing.LogCapture.open(logger.getName)
    try
      withFile(s"""["$hb"]""") { (f, svc) =>
        edit(svc, AllowlistEdit.Add, s"enode://$hd@10.0.0.4:30303") shouldBe Right(AdminAllowlistEditResponse(true))
        new String(Files.readAllBytes(f), StandardCharsets.UTF_8) should include(s"enode://$hd@10.0.0.4:30303")
        appender.list.asScala.map(_.getFormattedMessage) should contain(
          "Allowlist: added 1 node(s) [dddddddddddddddd] by ops"
        )
      }
    finally
      appender.close()
      logger.setLevel(level)
  }

  it should "answer each refusal of the contract with its code, changing nothing" taggedAs (UnitTest, RPCTest) in {
    withFile(s"""["$hb"]""") { (f, svc) =>
      def error(op: AllowlistEdit, entries: String*) = edit(svc, op, entries*).left.toOption.get
      error(AllowlistEdit.Add, "x") shouldBe JsonRpcError.InvalidParams("invalid enode or node id: x")
      error(AllowlistEdit.Add) shouldBe JsonRpcError.InvalidParams("expected 1 to 256 entries")
      error(AllowlistEdit.Add, hd, hd) shouldBe JsonRpcError.InvalidParams("duplicate entry: dddddddddddddddd")
      error(AllowlistEdit.Add, hb) shouldBe JsonRpcError.LogicError("already on the allowlist: bbbbbbbbbbbbbbbb")
      error(AllowlistEdit.Remove, hd) shouldBe JsonRpcError.LogicError("not on the allowlist: dddddddddddddddd")
      error(AllowlistEdit.Remove, ha) shouldBe
        JsonRpcError.LogicError("aaaaaaaaaaaaaaaa comes from config and cannot be removed at runtime")
      new String(Files.readAllBytes(f), StandardCharsets.UTF_8).trim shouldBe s"""["$hb"]"""
    }
  }

  it should "say the allowlist is off, or has no file" taggedAs (UnitTest, RPCTest) in {
    edit(service(None), AllowlistEdit.Add, hd).left.toOption.get shouldBe
      JsonRpcError.LogicError(NodeAllowlistStore.DisabledMessage)
    edit(service(Some(store(None))), AllowlistEdit.Add, hd).left.toOption.get shouldBe
      JsonRpcError.LogicError(NodeAllowlistStore.NoFileMessage)
  }

  "admin_getNodesAllowlist" should "list every node by id, with its address and sources, as the contract shapes it" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    withFile(s"""["enode://$hb@10.0.0.2:30303", "$ha"]""") { (f, svc) =>
      val answer = svc.getNodesAllowlist(AdminGetNodesAllowlistRequest()).unsafeRunSync().toOption.get
      val json = AdminJsonMethodsImplicits.admin_getNodesAllowlist.encodeJson(answer)
      json \ "enabled" shouldBe JBool(true)
      json \ "file" shouldBe JString(f.toString)
      (json \ "nodes") shouldBe JArray(
        List(
          JObject("id" -> JString(ha), "enode" -> JNull, "sources" -> JArray(List(JString("config"), JString("file")))),
          JObject(
            "id" -> JString(hb),
            "enode" -> JString(s"enode://$hb@10.0.0.2:30303"),
            "sources" -> JArray(List(JString("file")))
          )
        )
      )
    }
    val off = service(None).getNodesAllowlist(AdminGetNodesAllowlistRequest()).unsafeRunSync().toOption.get
    off shouldBe AdminGetNodesAllowlistResponse(enabled = false, file = None, nodes = Nil)
  }

  "The edit decoder" should "take one array of strings and nothing else" taggedAs (UnitTest, RPCTest) in {
    val decoder = AdminJsonMethodsImplicits.admin_editAllowlist
    decoder.decodeJson(Some(JArray(List(JArray(List(JString(ha))))))) shouldBe Right(AdminAllowlistEditRequest(Seq(ha)))
    decoder.decodeJson(Some(JArray(List(JString(ha))))).isLeft shouldBe true
    decoder.decodeJson(Some(JArray(List(JArray(List(JInt(1))))))).left.toOption.get.code shouldBe -32602
  }
