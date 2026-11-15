package com.chipprbots.ethereum.console.ops

import scala.collection.mutable

import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** The mining actions of an ETC node (spec 029 FR-054, T097; contracts/actions.md, B-278, B-279): `mining start` is
  * always refused, with the reason that holds; `mining stop` says that nothing in the process undoes it; a coinbase
  * pays from the next block template.
  */
class MiningActionsSpec extends AnyFlatSpec with Matchers with OptionValues:

  private val Now = "0x4f2a0b9c0000000000000000000000000000c91e"
  private val Next = "0x1000000000000000000000000000000000000001"
  private val Zero = "0x0000000000000000000000000000000000000000"

  /** The node's side: whether it has a miner, whether it mines, and every call made of it. */
  private class Fake(
      enabledAtStart: Boolean = true,
      running: Boolean = true,
      answer: Either[String, Unit] = Right(())
  ):
    val calls: mutable.Buffer[String] = mutable.Buffer.empty
    private def called(what: String): Either[String, Unit] =
      calls += what
      answer
    val handlers: Map[String, ActionHandler] = MiningActions.handlers(
      MiningActions.Node(
        enabledAtStart = enabledAtStart,
        running = () => running,
        stop = () => called("stop"),
        coinbase = () => Now,
        setCoinbase = address => called(s"setCoinbase $address")
      )
    )
    def preview(r: ActionRequest): Preview = handlers(r.action).preview(r)
    def run(r: ActionRequest): Either[String, String] = handlers(r.action).run(r, _ => ())

  private val start = ActionRequest(Actions.MiningStart.name)
  private val stop = ActionRequest(Actions.MiningStop.name)
  private def coinbase(address: String): ActionRequest =
    ActionRequest(Actions.Coinbase.name, Vector("address" -> address))

  private def off(enabledAtStart: Boolean, running: Boolean): Fake = new Fake(enabledAtStart, running)

  "The mining actions" should "be the three of the registry" taggedAs (UnitTest) in {
    new Fake().handlers.keySet shouldBe Set("mining start", "mining stop", "coinbase")
  }

  // ---- mining start: never runs -------------------------------------------------------------------------------------

  "mining start" should "always be refused, with the reason that holds on this node now" taggedAs (UnitTest) in {
    // Started with mining off: there is no miner.
    off(false, false).preview(start) shouldBe Preview(Vector.empty, Some(MiningActions.NoMinerToStart))
    // Started with mining on, and stopped since: it cannot be started again in this process.
    off(true, false).preview(start) shouldBe Preview(Vector.empty, Some(MiningActions.CannotStartAgain))
    // Mining: there is nothing to start.
    off(true, true).preview(start) shouldBe Preview(Vector.empty, Some(MiningActions.AlreadyMining))
  }

  it should "say each reason in words an operator can act on" taggedAs (UnitTest) in {
    MiningActions.NoMinerToStart shouldBe
      "this node was started with mining off: there is no miner to start; " +
      "set fukuii.mining.mining-enabled = true and restart"
    MiningActions.CannotStartAgain shouldBe
      "the built-in miner is started when the node starts; after a stop, fukuii cannot start it again: restart the node"
    MiningActions.AlreadyMining shouldBe "the built-in miner is mining: there is nothing to start"
  }

  it should "fail and start nothing, were it run all the same" taggedAs (UnitTest) in {
    for (enabled, running) <- Seq((false, false), (true, false), (true, true)) do
      val fake = off(enabled, running)
      fake.run(start).isLeft shouldBe true
      fake.calls shouldBe empty
    off(false, false).run(start) shouldBe Left(MiningActions.NoMinerToStart)
    off(true, false).run(start) shouldBe Left(MiningActions.CannotStartAgain)
  }

  // FAILS (reported): run on a node that is mining, it says the miner cannot be started "after a stop", where its own
  // preview says the miner is mining. No dialog can confirm it, so the sentence is never reached through the prompt.
  it should "fail with the reason its preview gave, in every state" taggedAs (UnitTest) in {
    for (enabled, running) <- Seq((false, false), (true, false), (true, true)) do
      withClue(s"enabled at start $enabled, mining $running: ") {
        val fake = off(enabled, running)
        fake.run(start).left.toOption shouldBe fake.preview(start).refusal
      }
  }

  // ---- mining stop --------------------------------------------------------------------------------------------------

  "mining stop" should "say that the miner stays stopped until the node restarts, and who stops being paid" taggedAs (
    UnitTest
  ) in {
    new Fake().preview(stop) shouldBe Preview(
      Vector(
        "Stops this node's built-in miner until the node restarts: fukuii cannot start it again while it runs.",
        "A search already running finishes its current round, and a block it finds is still imported.",
        "External miners using eth_getWork are not affected.",
        s"Mining rewards stop going to $Now from this node's own miner."
      )
    )
  }

  it should "be refused when the miner is already stopped, and when the node has none" taggedAs (UnitTest) in {
    off(true, false).preview(stop) shouldBe Preview(Vector.empty, Some(MiningActions.AlreadyStopped))
    MiningActions.AlreadyStopped shouldBe
      "the built-in miner is already stopped; it starts again only when the node restarts"
    off(false, false).preview(stop) shouldBe
      Preview(Vector.empty, Some("this node was started with mining off: there is no miner to stop"))
  }

  it should "stop the miner by the node's function, and hand its failure back unchanged" taggedAs (UnitTest) in {
    val ok = new Fake()
    ok.run(stop) shouldBe Right("the built-in miner is stopped until the node restarts")
    ok.calls shouldBe Seq("stop")
    val refusing = new Fake(answer = Left("the miner refused to stop"))
    refusing.run(stop) shouldBe Left("the miner refused to stop")
    refusing.calls shouldBe Seq("stop")
    // Stopped between the dialog and its answer: nothing is asked of the node.
    val stopped = off(true, false)
    stopped.run(stop) shouldBe Left(MiningActions.AlreadyStopped)
    stopped.calls shouldBe empty
  }

  // FAILS (reported): run on a node started with mining off, it says the miner "starts again only when the node
  // restarts", where its own preview says there is no miner to stop: a restart starts none.
  it should "fail with the reason its preview gave on a node that has no miner" taggedAs (UnitTest) in {
    val none = off(false, false)
    none.run(stop).left.toOption shouldBe none.preview(stop).refusal
    none.calls shouldBe empty
  }

  // ---- coinbase -----------------------------------------------------------------------------------------------------

  "coinbase" should "say the address after and the address now, and that the change is not saved" taggedAs (UnitTest) in {
    new Fake().preview(coinbase(Next)) shouldBe Preview(
      Vector(
        s"Mining rewards for blocks mined from the next block template on go to $Next.",
        s"Work already handed out, and the search now running, still pays $Now.",
        "This change is not saved: on restart the coinbase returns to fukuii.mining.coinbase."
      )
    )
  }

  it should "warn that the zero address has no owner" taggedAs (UnitTest) in {
    val p = new Fake().preview(coinbase(Zero))
    p.refusal shouldBe None
    p.lines.last shouldBe s"$Zero has no owner: rewards sent there are lost permanently."
    p.lines should have size 4
  }

  it should "be refused for what is no address, and for the address it already has" taggedAs (UnitTest) in {
    val bad = Preview(Vector.empty, Some("a coinbase must be 0x followed by exactly 40 hex digits"))
    for address <- Seq("", "0x12", Next.drop(2), Next + "00", Next.dropRight(1) + "g", s" $Next") do
      withClue(s"'$address': ") {
        new Fake().preview(coinbase(address)) shouldBe bad
      }
    new Fake().preview(ActionRequest(Actions.Coinbase.name)) shouldBe bad
    new Fake().preview(coinbase(Now)) shouldBe Preview(Vector.empty, Some(s"the coinbase is already $Now"))
    // In capitals it is the same address.
    new Fake().preview(coinbase("0x" + Now.drop(2).toUpperCase)).refusal.value shouldBe s"the coinbase is already $Now"
  }

  it should "set the address by the node's function, and hand its failure back unchanged" taggedAs (UnitTest) in {
    val ok = new Fake()
    ok.run(coinbase(Next)) shouldBe Right(s"blocks mined from the next template on pay $Next")
    ok.calls shouldBe Seq(s"setCoinbase $Next")
    val refusing = new Fake(answer = Left("the miner refused the address"))
    refusing.run(coinbase(Next)) shouldBe Left("the miner refused the address")
    // What is no address is never handed to the node.
    val unasked = new Fake()
    unasked.run(coinbase("0x12")) shouldBe Left("a coinbase must be 0x followed by exactly 40 hex digits")
    unasked.calls shouldBe empty
  }

  "A proof-of-stake node" should "be told why it has none of these" taggedAs (UnitTest) in {
    MiningActions.NotProofOfWork shouldBe
      "this is a proof-of-stake node: blocks come from the consensus client, and the fee recipient is set there"
  }
