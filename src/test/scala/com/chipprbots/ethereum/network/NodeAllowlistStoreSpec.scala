package com.chipprbots.ethereum.network

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.testkit.ExplicitlyTriggeredScheduler
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import com.typesafe.config.ConfigFactory
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.network.NodeAllowlistStore.ReloadResult
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Hex

/** Spec 030 FR-050, FR-053, FR-058: building the allowlist from config, the file and the static/bootnodes, refusing a
  * contradiction at startup, and reloading the file on a manual clock (no sleeps).
  */
class NodeAllowlistStoreSpec
    extends ScalaTestWithActorTestKit(ConfigFactory.load("explicit-scheduler"))
    with AnyFlatSpecLike
    with Matchers
    with OptionValues
    with EitherValues:

  private val ha = "aa" * 64
  private val hb = "bb" * 64
  private val hc = "cc" * 64
  private val own = "ee" * 64
  private def id(hex: String): ByteString = ByteString(Hex.decode(hex))
  private def uri(hex: String, host: String = "10.0.0.1") = new java.net.URI(s"enode://$hex@$host:30303")

  private def section(enabled: Boolean, nodes: Seq[String], file: Option[Path] = None) =
    val nodeList = nodes.map(n => "\"" + n + "\"").mkString("[", ",", "]")
    val fileLine = file.fold("")(p => s"""file = "${p.toString.replace("\\", "\\\\")}"""")
    Some(ConfigFactory.parseString(s"enabled = $enabled\nnodes = $nodeList\n$fileLine\nreload-interval = 1 minute"))

  private def write(p: Path, content: String): Unit = Files.write(p, content.getBytes(StandardCharsets.UTF_8))
  private def tmp(): Path = Files.createTempFile("allowlist", ".json")
  private def scheduler = system.classicSystem.scheduler.asInstanceOf[ExplicitlyTriggeredScheduler]

  behavior.of("NodeAllowlistStore")

  it should "be off, with no store, by default" taggedAs (UnitTest, NetworkTest) in {
    NodeAllowlistStore.fromConfig(None, Nil, Nil, None).value shouldBe None
    NodeAllowlistStore.fromConfig(section(enabled = false, Seq(ha)), Nil, Nil, None).value shouldBe None
  }

  it should "list the nodes of config and file, and drop the node's own id" taggedAs (UnitTest, NetworkTest) in {
    val f = tmp()
    write(f, s"""["$hb", "enode://$hc@10.0.0.3:30303"]""")
    val store = NodeAllowlistStore
      .fromConfig(section(enabled = true, Seq(ha, own), Some(f)), Nil, Nil, Some(id(own)))
      .value
      .value
    store.allowlist.enabled shouldBe true
    store.allowlist.snapshot.entries.keySet shouldBe Set(id(ha), id(hb), id(hc))
    store.allowlist.snapshot.dialable.map(_.getHost) shouldBe Seq("10.0.0.3")
  }

  it should "refuse the start when a static node or a bootnode is not listed, naming it (FR-053)" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val staticRefusal =
      NodeAllowlistStore.fromConfig(section(enabled = true, Seq(ha)), Seq(uri(hb)), Nil, None).left.value
    staticRefusal should include("static node bbbbbbbbbbbbbbbb")
    staticRefusal should include("node-allowlist")
    val bootRefusal =
      NodeAllowlistStore
        .fromConfig(section(enabled = true, Seq(ha)), Nil, Seq(s"enode://$hc@10.0.0.9:30303"), None)
        .left
        .value
    bootRefusal should include("bootstrap node cccccccccccccccc")
  }

  it should "start when every static node and bootnode is listed, in config or in the file" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val f = tmp()
    write(f, s"""["$hc"]""")
    val store = NodeAllowlistStore
      .fromConfig(section(enabled = true, Seq(ha), Some(f)), Seq(uri(ha)), Seq(s"enode://$hc@10.0.0.9:30303"), None)
      .value
      .value
    // the static node and bootnode entries keep their sources, and a listed static node keeps its address
    store.allowlist.snapshot.entries(id(ha)).sources shouldBe Set(AllowlistSource.Config, AllowlistSource.Static)
    store.allowlist.snapshot.entries(id(hc)).sources shouldBe Set(AllowlistSource.File, AllowlistSource.Bootnode)
  }

  it should "refuse the start for a config or file entry that does not parse" taggedAs (UnitTest, NetworkTest) in {
    NodeAllowlistStore.fromConfig(section(enabled = true, Seq("nonsense")), Nil, Nil, None).left.value should include(
      "nonsense"
    )
    val f = tmp()
    write(f, "not json")
    NodeAllowlistStore.fromConfig(section(enabled = true, Nil, Some(f)), Nil, Nil, None).left.value should include(
      "invalid JSON"
    )
  }

  it should "treat a file that does not exist yet as an empty list" taggedAs (UnitTest, NetworkTest) in {
    val missing = Files.createTempDirectory("allowlist").resolve("none.json")
    val store =
      NodeAllowlistStore.fromConfig(section(enabled = true, Seq(ha), Some(missing)), Nil, Nil, None).value.value
    store.allowlist.snapshot.entries.keySet shouldBe Set(id(ha))
  }

  it should "reload on its interval, picking up a changed file and then seeing no change (FR-058)" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val f = tmp()
    write(f, s"""["$hb"]""")
    val store = NodeAllowlistStore.fromConfig(section(enabled = true, Seq(ha), Some(f)), Nil, Nil, None).value.value
    var generations = Vector.empty[Long]
    store.allowlist.onChange(s => generations = generations :+ s.generation)
    val before = store.allowlist.generation
    store.schedule(1.minute, system.classicSystem.scheduler)(using ExecutionContext.parasitic)

    scheduler.timePasses(59.seconds)
    generations shouldBe empty // not yet due

    write(f, s"""["$hb", "$hc"]""")
    scheduler.timePasses(2.seconds)
    store.allowlist.snapshot.entries.keySet shouldBe Set(id(ha), id(hb), id(hc))
    generations shouldBe Vector(before + 1)

    scheduler.timePasses(1.minute) // same content: the hash matches, nothing happens
    generations shouldBe Vector(before + 1)

    write(f, s"""["$hc"]""") // b removed; a stays through the config
    scheduler.timePasses(1.minute)
    store.allowlist.snapshot.entries.keySet shouldBe Set(id(ha), id(hc))
    generations.size shouldBe 2
  }

  it should "keep the last good list when the file stops parsing, and count one failure per content" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val f = tmp()
    write(f, s"""["$hb"]""")
    val store = NodeAllowlistStore.fromConfig(section(enabled = true, Nil, Some(f)), Nil, Nil, None).value.value
    val failuresBefore = NodeAllowlistMetrics.ReloadFailures.count()

    write(f, """["nonsense""")
    store.reload() shouldBe a[ReloadResult.Failed]
    store.reload() shouldBe a[ReloadResult.Failed]
    store.allowlist.snapshot.entries.keySet shouldBe Set(id(hb))
    NodeAllowlistMetrics.ReloadFailures.count() - failuresBefore shouldBe 1.0

    write(f, s"""["$hc"]""") // fixed: loaded again
    store.reload() shouldBe ReloadResult.Reloaded(true)
    store.allowlist.snapshot.entries.keySet shouldBe Set(id(hc))
  }

  it should "keep the last good list when the file disappears" taggedAs (UnitTest, NetworkTest) in {
    val f = tmp()
    write(f, s"""["$hb"]""")
    val store = NodeAllowlistStore.fromConfig(section(enabled = true, Nil, Some(f)), Nil, Nil, None).value.value
    Files.delete(f)
    store.reload() shouldBe a[ReloadResult.Failed]
    store.allowlist.snapshot.entries.keySet shouldBe Set(id(hb))
  }

  it should "keep a static node listed when a reload drops it from the file" taggedAs (UnitTest, NetworkTest) in {
    val f = tmp()
    write(f, s"""["$hb"]""")
    val store = NodeAllowlistStore
      .fromConfig(section(enabled = true, Nil, Some(f)), Seq(uri(hb)), Nil, None)
      .value
      .value
    write(f, "[]")
    store.reload() shouldBe ReloadResult.Reloaded(true) // File source dropped
    store.allowlist.permits(id(hb)) shouldBe true // still listed through the static node
    store.allowlist.snapshot.entries(id(hb)).sources shouldBe Set(AllowlistSource.Static)
  }

  // ---- spec 030 FR-056, FR-057: runtime edits (slice 10) --------------------------------------------------------

  private val hd = "dd" * 64

  private def editable(file: Path, config: Seq[String] = Seq(ha), static: Seq[java.net.URI] = Nil) =
    NodeAllowlistStore.fromConfig(section(enabled = true, config, Some(file)), static, Nil, Some(id(own))).value.value

  private def onDisk(file: Path): Seq[String] =
    NodeAllowlistStore
      .readFile(file, mustExist = true)
      .value
      ._1
      .map(e => e.enode.map(_.toString).getOrElse(Hex.toHexString(e.id.toArray)))

  it should "add and remove entries, writing the file before the list, so the next reload sees no change" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val f = tmp()
    write(f, s"""["$hb"]""")
    val store = editable(f)
    store.edit(AllowlistEdit.Add, Seq(s"enode://$hc@10.0.0.3:30303", hd)).value shouldBe 2
    onDisk(f) shouldBe Seq(hb, s"enode://$hc@10.0.0.3:30303", hd)
    store.allowlist.permits(id(hc)) shouldBe true
    store.allowlist.snapshot.dialable.map(_.toString) should contain(s"enode://$hc@10.0.0.3:30303")
    store.reload() shouldBe ReloadResult.Unchanged
    store.edit(AllowlistEdit.Remove, Seq(hb)).value shouldBe 1
    onDisk(f) shouldBe Seq(s"enode://$hc@10.0.0.3:30303", hd)
    store.allowlist.permits(id(hb)) shouldBe false
    store.reload() shouldBe ReloadResult.Unchanged
  }

  it should "take a hand edit made since the last load before applying its own" taggedAs (UnitTest, NetworkTest) in {
    val f = tmp()
    write(f, s"""["$hb"]""")
    val store = editable(f)
    write(f, s"""["$hb", "$hc"]""")
    store.edit(AllowlistEdit.Add, Seq(hd)).value shouldBe 1
    onDisk(f) shouldBe Seq(hb, hc, hd)
    store.allowlist.permits(id(hc)) shouldBe true
  }

  it should "refuse, changing nothing, every request the contract names" taggedAs (UnitTest, NetworkTest) in {
    val f = tmp()
    write(f, s"""["$hb"]""")
    val store = editable(f, static = Seq(uri(hc, "10.0.0.3")), config = Seq(ha, hc))
    def refused(op: AllowlistEdit, raws: Seq[String]) = store.edit(op, raws).left.value
    refused(AllowlistEdit.Add, Nil) shouldBe EditError.InvalidParams("expected 1 to 256 entries")
    refused(AllowlistEdit.Add, Seq.fill(257)(hd)) shouldBe EditError.InvalidParams("expected 1 to 256 entries")
    refused(AllowlistEdit.Add, Seq(hd, "nope")) shouldBe EditError.InvalidParams("invalid enode or node id: nope")
    refused(AllowlistEdit.Add, Seq(hd, hd.toUpperCase)) shouldBe EditError.InvalidParams(
      "duplicate entry: dddddddddddddddd"
    )
    refused(AllowlistEdit.Add, Seq(hd, hb)) shouldBe EditError.Refused("already on the allowlist: bbbbbbbbbbbbbbbb")
    refused(AllowlistEdit.Remove, Seq(hd)) shouldBe EditError.Refused("not on the allowlist: dddddddddddddddd")
    refused(AllowlistEdit.Remove, Seq(ha)) shouldBe
      EditError.Refused("aaaaaaaaaaaaaaaa comes from config and cannot be removed at runtime")
    // all-or-nothing: the good entry of a refused request is not added
    store.allowlist.permits(id(hd)) shouldBe false
    onDisk(f) shouldBe Seq(hb)
  }

  it should "refuse an edit while the file does not parse, and keep the list" taggedAs (UnitTest, NetworkTest) in {
    val f = tmp()
    write(f, s"""["$hb"]""")
    val store = editable(f)
    write(f, "[ not json")
    store.edit(AllowlistEdit.Add, Seq(hd)).left.value.message should (include("does not parse").and(
      include(
        "fix it before editing"
      )
    ))
    store.allowlist.permits(id(hb)) shouldBe true
    store.allowlist.permits(id(hd)) shouldBe false
  }

  it should "leave the file and the list unchanged when the write fails" taggedAs (UnitTest, NetworkTest) in {
    val f = tmp()
    write(f, s"""["$hb"]""")
    val store = editable(f)
    val failing = store.edit(AllowlistEdit.Add, Seq(hd), (_, _) => throw new java.io.IOException("disk full"))
    failing.left.value.message shouldBe s"could not write $f: disk full; nothing changed"
    onDisk(f) shouldBe Seq(hb)
    store.allowlist.permits(id(hd)) shouldBe false
  }

  it should "refuse every edit without a file" taggedAs (UnitTest, NetworkTest) in {
    val store = NodeAllowlistStore.fromConfig(section(enabled = true, Seq(ha)), Nil, Nil, None).value.value
    store.edit(AllowlistEdit.Add, Seq(hd)).left.value shouldBe EditError.Refused(NodeAllowlistStore.NoFileMessage)
  }

  "atomicWrite" should "replace the file whole and leave no temporary file" taggedAs (UnitTest, NetworkTest) in {
    val f = tmp()
    write(f, "old")
    NodeAllowlistStore.atomicWrite(f, "new content".getBytes(StandardCharsets.UTF_8))
    new String(Files.readAllBytes(f), StandardCharsets.UTF_8) shouldBe "new content"
    Files.list(f.getParent).filter(_.getFileName.toString.startsWith(s".${f.getFileName}.")).count() shouldBe 0
  }
