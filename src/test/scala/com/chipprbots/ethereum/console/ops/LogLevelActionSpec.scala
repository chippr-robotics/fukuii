package com.chipprbots.ethereum.console.ops

import cats.effect.unsafe.implicits.global

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import org.scalatest.BeforeAndAfterEach
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.jsonrpc.AdminService
import com.chipprbots.ethereum.testing.Tags.*

/** The `loglevel` action (spec 029 FR-054, T097, T104): its preview from the levels as they are, and the admin
  * service's own functions carrying it out and undoing it. Logback's state is the JVM's: every logger this touches is
  * put back.
  */
class LogLevelActionSpec extends AnyFlatSpec with Matchers with OptionValues with BeforeAndAfterEach:

  private val ctx = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
  private val Parent = "fukuii.test.loglevel"
  private val Child = s"$Parent.child"
  private var rootBefore: Level = scala.compiletime.uninitialized

  override def beforeEach(): Unit =
    rootBefore = ctx.getLogger("ROOT").getLevel
    val _ = AdminService.resetLogLevels()
    ctx.getLogger(Parent).setLevel(Level.WARN)
    ctx.getLogger(Child).setLevel(null)

  override def afterEach(): Unit =
    val _ = AdminService.resetLogLevels()
    ctx.getLogger(Parent).setLevel(null)
    ctx.getLogger(Child).setLevel(null)
    ctx.getLogger("ROOT").setLevel(rootBefore)

  /** The node's side, as StdNode wires it: the admin service's own functions. */
  private val node = LogLevelAction.Node(
    level = name => AdminService.logLevel(name).map(l => LogLevelAction.Level(l.name, l.configured, l.effective)),
    near = prefix => AdminService.logLevels(prefix).map(_.name),
    change = (level, logger) =>
      // The service's changeLogLevel needs no instance state: the object-level functions are what it calls.
      AdminServiceLevels.change(level, logger),
    changed =
      () => AdminService.changedLogLevels.map(c => LogLevelAction.Changed(c.name, c.before, c.now, c.createdByChange)),
    reset =
      () => AdminService.resetLogLevels().map(c => LogLevelAction.Changed(c.name, c.before, c.now, c.createdByChange))
  )
  private val handler = LogLevelAction.handler(node)

  private def set(level: String, logger: String): ActionRequest =
    ActionRequest(Actions.LogLevel.name, Vector("level" -> level, "logger" -> logger))
  private val reset = ActionRequest(Actions.LogLevel.name, Vector("reset" -> "all"))

  "The preview" should "say the level a logger logs at now, the one set on it, and the one after" taggedAs (UnitTest) in {
    val child = handler.preview(set("DEBUG", Child))
    child.refusal shouldBe None
    child.lines.head shouldBe s"$Child: logs at WARN now (set on it: none of its own (it inherits)), DEBUG after."
    val parent = handler.preview(set("DEBUG", Parent))
    parent.lines.head shouldBe s"$Parent: logs at WARN now (set on it: WARN), DEBUG after."
  }

  it should "refuse a logger nobody has, and name the nearest: a mistyped name would log nothing" taggedAs (UnitTest) in {
    val typo = handler.preview(set("DEBUG", s"$Parent.chidl"))
    typo.refusal.value should include(s"there is no logger '$Parent.chidl'")
    typo.refusal.value should include(Child)
    // And it is not made by having been asked about.
    ctx.exists(s"$Parent.chidl") shouldBe null
    handler.run(set("DEBUG", s"$Parent.chidl"), _ => ()) shouldBe Left(s"there is no logger '$Parent.chidl'")
    ctx.exists(s"$Parent.chidl") shouldBe null
  }

  it should "say when nothing would change" taggedAs (UnitTest) in {
    handler.preview(set("WARN", Parent)).refusal.value shouldBe s"$Parent is already at WARN"
    handler.preview(reset).refusal.value should include("no logger's level has been changed")
  }

  it should "warn that the root logger feeds the dashboard's own log view" taggedAs (UnitTest) in {
    handler.preview(set("OFF", "ROOT")).lines.last should include("the dashboard's own Logs view")
    (handler.preview(set("DEBUG", "ROOT")).lines.last should not).include("Logs view")
  }

  "Running it" should "set the level through the admin service, and remember what it was" taggedAs (UnitTest) in {
    handler.run(set("DEBUG", Child), _ => ()) shouldBe Right(s"$Child logs at DEBUG")
    ctx.getLogger(Child).getLevel shouldBe Level.DEBUG
    AdminService.changedLogLevels shouldBe List(
      AdminService.ChangedLogger(Child, None, "DEBUG", createdByChange = false)
    )
    // A second change keeps the first "before": that is what a reset returns to.
    handler.run(set("TRACE", Child), _ => ()) shouldBe Right(s"$Child logs at TRACE")
    AdminService.changedLogLevels.map(_.before) shouldBe List(None)
    handler.run(set("ERROR", Parent), _ => ()) shouldBe Right(s"$Parent logs at ERROR")
    val preview = handler.preview(reset)
    preview.lines should contain(s"$Child: TRACE now, none of its own (it inherits) after.")
    preview.lines should contain(s"$Parent: ERROR now, WARN after.")
  }

  "A reset" should "return every changed logger to what it was, however it was changed, and keep the appenders" taggedAs (
    UnitTest
  ) in {
    val appenders = ctx.getLogger("ROOT").iteratorForAppenders().hasNext
    handler.run(set("DEBUG", Child), _ => ())
    // Changed over JSON-RPC, as admin_changeLogLevel does it: the same record.
    AdminServiceLevels.change("ERROR", Parent) shouldBe Right(())
    handler.run(reset, _ => ()) shouldBe Right("2 loggers returned to the configured level")
    ctx.getLogger(Child).getLevel shouldBe null
    ctx.getLogger(Child).getEffectiveLevel shouldBe Level.WARN
    ctx.getLogger(Parent).getLevel shouldBe Level.WARN
    AdminService.changedLogLevels shouldBe empty
    ctx.getLogger("ROOT").iteratorForAppenders().hasNext shouldBe appenders
  }

  /** `AdminService.changeLogLevel` for one logger, run as the node runs it. */
  private object AdminServiceLevels:
    def change(level: String, logger: String): Either[String, Unit] =
      AdminService
        .setLogLevel(
          AdminService.AdminChangeLogLevelRequest(level, Some(List(if logger == "ROOT" then "" else logger)))
        )
        .unsafeRunSync()
        .left
        .map(_.message)
        .map(_ => ())
