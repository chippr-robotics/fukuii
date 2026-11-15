package com.chipprbots.ethereum.console.ops

import scala.collection.mutable

import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** `dbcheck` (spec 029 FR-054, FR-057, T097; contracts/actions.md): what it reads, that it is the check of the start
  * and stops nothing, why it is not run on a node where it says nothing, what it found, and how far it is.
  */
class DatabaseActionsSpec extends AnyFlatSpec with Matchers with OptionValues:

  private val Best = BigInt(21_884_102L)
  private val Clean =
    DatabaseActions.Result(Best, 1000, checked = 21_885L, missing = 0L, first = Nil, cancelled = false)

  /** The node's side: whether the check means anything here, what the scan finds, and how often it was run. */
  private class Fake(
      notMeaningful: Option[String] = None,
      found: DatabaseActions.Result = Clean,
      reports: Seq[(Long, Long)] = Nil
  ):
    var scans = 0
    val handler: ActionHandler = DatabaseActions.handlers(
      DatabaseActions.Node(
        notMeaningful = () => notMeaningful,
        best = () => Best,
        step = 1000,
        scan = progress =>
          scans += 1
          reports.foreach((done, of) => progress(done, of))
          found
      )
    )(Actions.DbCheck.name)
    val said: mutable.Buffer[String] = mutable.Buffer.empty
    def preview: Preview = handler.preview(ActionRequest(Actions.DbCheck.name))
    def run(): Either[String, String] = handler.run(ActionRequest(Actions.DbCheck.name), said += _)

  private val Snap = "this node took its state by SNAP: it has no headers below its pivot, by design"

  "dbcheck" should "be the one database action of the registry" taggedAs (UnitTest) in {
    DatabaseActions.handlers(DatabaseActions.Node(() => None, () => Best, 1000, _ => Clean)).keySet shouldBe Set(
      "dbcheck"
    )
  }

  it should "say what it reads, that it is the check of the start, and that it stops nothing" taggedAs (UnitTest) in {
    // Every 1000th block up to 21,884,102 is 21,885 points, two entries each.
    new Fake().preview shouldBe Preview(
      Vector(
        "Reads the number-to-hash entry and the header of every 1000th block up to the best block.",
        "It is the check the node runs when it starts.",
        "It stops nothing and changes nothing: on finding a fault it reports it, here and in the log.",
        "It reads beside block import, and takes seconds to tens of seconds."
      ),
      figures = Vector("The best block is 21884102 now: about 43770 entries.")
    )
  }

  it should "be refused, with the reason, on a node where it says nothing" taggedAs (UnitTest) in {
    val fake = new Fake(notMeaningful = Some(Snap))
    fake.preview shouldBe Preview(Vector.empty, Some(s"not meaningful here: $Snap"))
    // Run all the same, it reads nothing and says the same.
    fake.run() shouldBe Left(s"not meaningful here: $Snap")
    fake.scans shouldBe 0
    fake.said shouldBe empty
  }

  it should "say how many points it read when none is missing" taggedAs (UnitTest) in {
    val fake = new Fake()
    fake.run() shouldBe Right("checked 21885 sample points (every 1000th block up to 21884102): 0 missing")
    fake.scans shouldBe 1
  }

  it should "fail, naming the first five, when some are missing" taggedAs (UnitTest) in {
    val missing = (1 to 6).map(i => s"block ${i * 1000} (no header for its hash)")
    val fake = new Fake(found = Clean.copy(missing = 7L, first = missing))
    fake.run() shouldBe Left(
      "checked 21885 sample points (every 1000th block up to 21884102): 7 missing; the first: " +
        "block 1000 (no header for its hash), block 2000 (no header for its hash), block 3000 (no header for its hash), " +
        "block 4000 (no header for its hash), block 5000 (no header for its hash)"
    )
  }

  // Was a defect, fixed: `Result.cancelled` is never read. A scan that was cancelled part of the way is reported in the
  // words of one that read every point and found nothing missing.
  it should "say that a scan was cancelled, and not report it as a whole check that found nothing" taggedAs (UnitTest) in {
    val cancelled = new Fake(found = Clean.copy(checked = 4_000L, cancelled = true)).run()
    val whole = new Fake(found = Clean.copy(checked = 4_000L)).run()
    cancelled should not be whole
    cancelled.merge should include("cancel")
  }

  it should "say how far it is, as the scan tells it" taggedAs (UnitTest) in {
    val fake = new Fake(reports = Seq(0L -> 21_885L, 10_000L -> 21_885L, 21_885L -> 21_885L))
    fake.run().isRight shouldBe true
    fake.said shouldBe Seq(
      "read 0 of 21885 sample points",
      "read 10000 of 21885 sample points",
      "read 21885 of 21885 sample points"
    )
  }
