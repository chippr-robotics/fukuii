package com.chipprbots.ethereum.console.ops

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

import scala.jdk.CollectionConverters.*

import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console.TuiConfig
import com.chipprbots.ethereum.console.TuiFixtures
import com.chipprbots.ethereum.console.TuiRenderer
import com.chipprbots.ethereum.console.TuiView
import com.chipprbots.ethereum.testing.Tags.*

/** The registry against its contract (spec 029 FR-047, FR-054, FR-056, T095): `contracts/actions.md` is read from the
  * working directory, and every row of it is an action of the registry with the row's class, and the other way round.
  */
class ActionsContractSpec extends AnyFlatSpec with Matchers with OptionValues:

  private val ContractPath = "specs/029-tui-command-center/contracts/actions.md"

  private val contract: Vector[String] =
    Files.readAllLines(Paths.get(ContractPath), StandardCharsets.UTF_8).asScala.toVector

  private val classes: Map[String, RiskClass] = RiskClass.values.map(c => s"## ${c.label}" -> c).toMap

  private val Named = """^\|\s*`([^`]+)`\s*\|.*""".r

  /** The lines under each `## ` heading, by the heading. */
  private val sections: Vector[(String, Vector[String])] =
    contract.foldLeft(Vector.empty[(String, Vector[String])]) { (found, line) =>
      if line.startsWith("## ") then found :+ (line.trim -> Vector.empty)
      else if found.isEmpty then found
      else found.init :+ (found.last._1 -> (found.last._2 :+ line))
    }

  /** Every action of the contract with its class, in the contract's order: the rows under the four class headings
    * whose first cell is a name in backticks.
    */
  private val rows: Vector[(String, RiskClass)] =
    sections.flatMap { (heading, lines) =>
      classes.get(heading).toVector.flatMap(risk => lines.collect { case Named(name) => name -> risk })
    }

  /** The first cell of each row of the "Not offered" table, its header and its rule left out. */
  private val notOffered: Vector[String] =
    sections
      .collectFirst { case (heading, lines) if heading.startsWith("## Not offered") => lines }
      .value
      .filter(_.startsWith("|"))
      .map(_.split('|')(1).trim)
      .filterNot(cell => cell == "Requested" || cell.forall(ch => ch == '-' || ch == ' '))

  "The contract" should "have the four classes, each with its actions" taggedAs (UnitTest) in {
    sections.map(_._1).filter(classes.contains) shouldBe Vector("## Observe", "## Adjust", "## Disrupt", "## Halt")
    for risk <- RiskClass.values do
      withClue(s"${risk.label}: ") {
        rows.filter(_._2 == risk) should not be empty
      }
  }

  "The registry" should "be exactly the contract's actions, each with its row's class" taggedAs (UnitTest) in {
    val registry = Actions.all.map(a => a.name -> a.risk)
    // Both directions, each named: a row the registry lacks, and an action no row declares.
    rows.filterNot(registry.contains) shouldBe empty
    registry.filterNot(rows.contains) shouldBe empty
    rows.toSet shouldBe registry.toSet
    // No row is there twice, in one class or in two.
    rows.map(_._1).distinct shouldBe rows.map(_._1)
    rows should have size Actions.all.size.toLong
  }

  // FAILS (reported): `Actions.all` says it is "by class, then as the contract has them", and `mining stop`, a Halt,
  // stands among the Disrupt actions.
  it should "list its actions by class, then as the contract has them" taggedAs (UnitTest) in {
    Actions.all.map(_.risk.ordinal) shouldBe sorted
    Actions.all.map(_.name) shouldBe rows.map(_._1)
  }

  it should "name each action once, and find it by its name" taggedAs (UnitTest) in {
    Actions.all.map(_.name).distinct shouldBe Actions.all.map(_.name)
    for spec <- Actions.all do Actions.named(spec.name).value shouldBe spec
    Actions.named("compact") shouldBe None
    Actions.named("") shouldBe None
  }

  it should "say what each action changes in under fifty characters, and how it is undone" taggedAs (UnitTest) in {
    for spec <- Actions.all do
      withClue(s"${spec.name}: '${spec.changes}' ") {
        spec.changes should not be empty
        spec.changes.length should be < 50
        spec.undo should not be empty
        spec.usage should not be empty
      }
  }

  "The word a Halt asks for" should "be the one the contract gives" taggedAs (UnitTest) in {
    val Word = """^The word to type is `([^`]+)`\.$""".r
    val words = contract.collect { case Word(word) => word }
    words shouldBe Vector(Actions.HaltWord)
    contract should contain("The word to type is `stop`.")
    Actions.HaltWord shouldBe "stop"
  }

  "What is not offered" should "be as many rows in the contract as in the registry, compaction among them" taggedAs (
    UnitTest
  ) in {
    notOffered should have size Actions.NotOffered.size.toLong
    notOffered should contain("Compact the database")
    Actions.NotOffered.map(_._1) should contain("Compact the database")
    Actions.NotOffered.map(_._1).distinct shouldBe Actions.NotOffered.map(_._1)
    for (what, why) <- Actions.NotOffered do
      withClue(s"$what: ") {
        why should not be empty
      }
    // What is not offered is no action of the registry.
    Actions.named("compact") shouldBe None
  }

  // ---- what carries each action out ---------------------------------------------------------------------------------

  /** The actions the dashboard carries out itself, with no handler of the node's: the contract's "Carried out by" is
    * `Health` (deps), `ArtStore` (art), the node's stores through the explorer's own worker (lookup), and the existing
    * quit path (quit). `Tui.setActions` gives the runner the node's handlers and `threshold`, and none for these.
    */
  private val dashboardsOwn: Set[String] =
    Set(Actions.Deps.name, Actions.Art.name, Actions.Lookup.name, Actions.Quit.name)

  private val anything = ActionHandler(_ => Preview(Vector("it will change")), (_, _) => Right("done"))

  /** A runner as the dashboard builds it on a node that offers every action: a handler for each action but its own. */
  private def runnerOfAWholeNode: ActionRunner =
    val inline = new SameThread
    new ActionRunner(
      Actions.all,
      Actions.all.map(_.name).filterNot(dashboardsOwn).map(_ -> anything).toMap,
      clock = TuiFixtures.clock,
      audit = _ => (),
      previewThread = inline,
      actionThread = inline,
      observeThread = inline
    )

  // FAILS (reported): the runner calls an action with no handler "not offered on this node", and `quit`, `art`,
  // `deps` and `lookup` never have one.
  "An action the dashboard carries out itself" should "not be called unavailable by the runner" taggedAs (UnitTest) in {
    val runner = runnerOfAWholeNode
    try runner.view.unavailable shouldBe empty
    finally runner.shutdown()
  }

  // FAILS (reported): the same, as the operator sees it.
  it should "not be drawn as 'not on this node' in the Ops view" taggedAs (UnitTest) in {
    val runner = runnerOfAWholeNode
    try
      val state = TuiFixtures.syncedEtc().withView(TuiView.Ops).withActions(runner.view)
      val frame = TuiRenderer(TuiConfig.default).render(state, 160, 60).map(_.toString)
      frame.filter(_.contains("not on this node")) shouldBe empty
    finally runner.shutdown()
  }
