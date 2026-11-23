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

  // ---- compact (B-371) ----------------------------------------------------------------------------------------------

  private val Gib = 1024L * 1024 * 1024

  /** The node's side of `compact`: what the compaction answers, the stores it tells of, and how often it ran. */
  private class FakeCompact(result: DatabaseActions.CompactResult, told: Seq[(String, Int, Int)] = Nil):
    var runs = 0
    val handler: ActionHandler = DatabaseActions
      .compactHandlers(
        DatabaseActions.CompactNode(
          sstBytes = () => Some(3 * Gib),
          stores = 3,
          compact = onStore =>
            runs += 1
            told.foreach((store, at, of) => onStore(store, at, of))
            result
        )
      )(Actions.Compact.name)
    val said: mutable.Buffer[String] = mutable.Buffer.empty
    private val request = ActionRequest(Actions.Compact.name)
    def preview: Preview = handler.preview(request)
    def run(): Either[String, String] = handler.run(request, said += _)

  private val Done: DatabaseActions.CompactResult.Finished = DatabaseActions.CompactResult.Finished(
    Vector(
      DatabaseActions.Compacted("nodes", Some(2 * Gib), Some(Gib)),
      DatabaseActions.Compacted("bodies", Some(Gib), Some(Gib / 2))
    ),
    Vector.empty,
    cancelled = false
  )

  "compact" should "be a registry action, and the one other action of this object" taggedAs (UnitTest) in {
    DatabaseActions
      .compactHandlers(DatabaseActions.CompactNode(() => None, 1, _ => Done))
      .keySet shouldBe Set("compact")
    Actions.named("compact") shouldBe Some(Actions.Compact)
  }

  it should "say what it costs before it is confirmed: heavy I/O, hours, free disk, the size now" taggedAs (UnitTest) in {
    val preview = new FakeCompact(Done).preview
    preview.refusal shouldBe None
    preview.lines.mkString(" ") should include("all 3 stores")
    preview.lines.mkString(" ") should include("takes hours")
    preview.lines.mkString(" ") should include("free disk")
    preview.figures shouldBe Vector("The database's files hold 3.0G now.")
  }

  it should "report each store as it starts, and the bytes before and after" taggedAs (UnitTest) in {
    val fake = new FakeCompact(Done, told = Seq(("nodes", 1, 3), ("bodies", 2, 3)))
    fake.run() shouldBe Right("compacted 2 of 3 stores (3.0G of files before, 1.5G after)")
    fake.said shouldBe Seq("compacting store nodes (1 of 3)", "compacting store bodies (2 of 3)")
  }

  it should "report 'already running' for a second request, and not as a success" taggedAs (UnitTest) in {
    val fake = new FakeCompact(DatabaseActions.CompactResult.AlreadyRunning)
    fake.run() shouldBe Left("already running: a compaction started earlier has not ended")
  }

  it should "report a cancellation as a failure naming the stores not reached" taggedAs (UnitTest) in {
    val cancelled = DatabaseActions.CompactResult.Finished(
      Done.compacted.take(1),
      Vector("bodies", "receipts"),
      cancelled = true
    )
    val answer = new FakeCompact(cancelled).run()
    answer.isLeft shouldBe true
    answer.merge should include("cancelled")
    answer.merge should include("not reached: bodies, receipts")
    answer.merge should include("compacted 1 of 3 stores")
  }
